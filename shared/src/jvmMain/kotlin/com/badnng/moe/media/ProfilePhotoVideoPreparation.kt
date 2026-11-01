package com.badnng.moe.media

import com.badnng.moe.chat.AttachmentLease
import com.badnng.moe.chat.ownedAttachmentLease
import com.badnng.moe.settings.SettingsPreparedProfileAnimation
import com.badnng.moe.settings.SettingsPreparedProfilePhoto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.ByteArrayOutputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/** Fixed failures only: never expose commands, local paths, stderr, codecs or decoder exception text. */
internal class ProfilePhotoVideoFailure(val reason: Reason) : Exception(reason.name) {
    enum class Reason { INVALID_SOURCE, TOOLS_MISSING, INVALID_EDIT, PREPARATION_FAILED }
}

/** Coordinates are in the oriented, square-pixel source; timestamps are on the source timeline. */
internal data class ProfilePhotoVideoEdit(
    val crop: ProfilePhotoCrop,
    val fromMillis: Long,
    val tillMillis: Long,
    val coverMillis: Long,
) {
    override fun toString() = "ProfilePhotoVideoEdit(<redacted>)"
}

/** Only our immutable copy is probed/decoded. Closing the editor cannot delete a worker's retained copy. */
internal class ProfilePhotoVideoSource internal constructor(
    internal val path: Path,
    val size: Long,
    val width: Int,
    val height: Int,
    val durationMillis: Long,
    internal val streamIndex: Int,
    internal val sha256: String,
    private val lease: AttachmentLease,
) : AutoCloseable {
    internal fun retain(): AttachmentLease? = lease.retain()
    override fun close() = lease.release()
    override fun toString() = "ProfilePhotoVideoSource(<redacted>)"
}

private val profileVideoWorker = Semaphore(1)
private val profilePhotoVideoPreparation = ProfilePhotoVideoPreparation()

internal suspend fun readProfilePhotoVideoSource(path: String): ProfilePhotoVideoSource =
    profilePhotoVideoPreparation.read(path)

internal suspend fun prepareProfilePhotoVideo(
    source: ProfilePhotoVideoSource,
    edit: ProfilePhotoVideoEdit,
): SettingsPreparedProfileAnimation = profilePhotoVideoPreparation.prepare(source, edit)

/** A real frame from the encoded, trimmed timeline; this JPEG has its own preview ownership. */
internal suspend fun prepareProfilePhotoVideoCover(animation: SettingsPreparedProfileAnimation): SettingsPreparedProfilePhoto =
    profilePhotoVideoPreparation.cover(animation)

/** Blocking file hashing, full bounded output probing and decoding: call only from a worker. */
internal fun validatePreparedProfileAnimation(animation: SettingsPreparedProfileAnimation): Boolean =
    profilePhotoVideoPreparation.validate(animation)

/** Owns no UI or TDLib state. The one global slot includes worker-only submission validation. */
internal class ProfilePhotoVideoPreparation(
    private val findFfmpeg: () -> Path? = MediaDecoder::findFfmpeg,
    private val findFfprobe: () -> Path? = { MediaTools.find("ffprobe.exe") },
    private val startProcess: (List<String>) -> Process = {
        ProcessBuilder(it).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    },
    private val tempDirectory: () -> Path = { Path.of(System.getProperty("java.io.tmpdir")) },
    private val processTimeoutMillis: Long = 90_000,
) {
    init { require(processTimeoutMillis in 1..90_000) }

    suspend fun read(path: String): ProfilePhotoVideoSource {
        val delivered = AtomicReference<ProfilePhotoVideoSource?>()
        try {
            return runInterruptible(Dispatchers.IO) {
                serial { permit ->
                    sanitized(ProfilePhotoVideoFailure.Reason.INVALID_SOURCE) { stage(path, permit).also(delivered::set) }
                }
            }
        } catch (failure: Throwable) {
            delivered.getAndSet(null)?.close()
            throw failure
        }
    }

    suspend fun prepare(source: ProfilePhotoVideoSource, edit: ProfilePhotoVideoEdit): SettingsPreparedProfileAnimation {
        val retained = source.retain() ?: fail(ProfilePhotoVideoFailure.Reason.INVALID_SOURCE)
        val delivered = AtomicReference<SettingsPreparedProfileAnimation?>()
        try {
            return runInterruptible(Dispatchers.IO) {
                serial { permit ->
                    sanitized(ProfilePhotoVideoFailure.Reason.PREPARATION_FAILED) {
                        encode(source, edit, retained, permit).also(delivered::set)
                    }
                }
            }
        } catch (failure: Throwable) {
            delivered.getAndSet(null)?.lease?.release()
            throw failure
        } finally { retained.release() }
    }

    suspend fun cover(animation: SettingsPreparedProfileAnimation): SettingsPreparedProfilePhoto {
        val retained = animation.lease.retain() ?: fail(ProfilePhotoVideoFailure.Reason.INVALID_SOURCE)
        val delivered = AtomicReference<SettingsPreparedProfilePhoto?>()
        try {
            return runInterruptible(Dispatchers.IO) {
                serial { permit ->
                    sanitized(ProfilePhotoVideoFailure.Reason.PREPARATION_FAILED) {
                        coverFrame(animation, retained, permit).also(delivered::set)
                    }
                }
            }
        } catch (failure: Throwable) {
            delivered.getAndSet(null)?.lease?.release()
            throw failure
        } finally { retained.release() }
    }

    fun validate(animation: SettingsPreparedProfileAnimation): Boolean {
        val retained = animation.lease.retain() ?: return false
        try {
            return serial { permit ->
                validateOutput(animation, retained, permit)
                true
            }
        } catch (interrupted: InterruptedException) { throw interrupted }
        catch (failure: ProfilePhotoVideoFailure) { throw failure }
        catch (_: Exception) { return false }
        finally { retained.release() }
    }

    private fun stage(value: String, permit: ProfileVideoWorkerPermit): ProfilePhotoVideoSource {
        val ffprobe = findFfprobe() ?: fail(ProfilePhotoVideoFailure.Reason.TOOLS_MISSING)
        val ffmpeg = findFfmpeg() ?: fail(ProfilePhotoVideoFailure.Reason.TOOLS_MISSING)
        val original = localPath(value)
        val before = attributes(original)
        require(before.size() in 1..MAX_SOURCE_BYTES)
        val target = temporary(".video")
        val lease = ownedAttachmentLease { runCatching { Files.deleteIfExists(target) } }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            FileChannel.open(original, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                Channels.newInputStream(channel).use { input ->
                    Files.newOutputStream(target, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS).use { output ->
                        val buffer = ByteArray(65_536)
                        while (true) {
                            interrupted()
                            check(System.nanoTime() < deadline)
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(count.toLong() <= before.size() - copied)
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            copied += count
                        }
                    }
                }
            }
            require(copied == before.size() && sameFile(before, attributes(original)))
            val hash = digest.digest().hex()
            // Detect a same-length rewrite during copying, even if the editor restored the mtime.
            require(hashFile(original, copied, deadline) == hash && sameFile(before, attributes(original)))
            require(hashFile(target, copied, deadline) == hash)
            val probe = probe(ffprobe, target, countFrames = false, permit, lease)
            val info = probe.info
            require(info.width.toLong() * info.height <= MAX_SOURCE_PIXELS &&
                info.displayWidth.toLong() * info.displayHeight <= MAX_SOURCE_PIXELS)
            require(info.duration >= 1.0)
            // Probe alone is not proof that the picked container contains decodable pictures.
            command(ffmpeg, inputOptions + listOf("-i", target.toString(), "-map", "0:${info.streamIndex}",
                "-frames:v", "1", "-an", "-sn", "-dn", "-f", "null", "-"), 20_000, permit, listOf(lease))
            require(hashFile(target, copied) == hash)
            return ProfilePhotoVideoSource(target, copied, info.displayWidth, info.displayHeight,
                (info.duration * 1_000).roundToLong(), info.streamIndex, hash, lease)
        } catch (failure: Throwable) { lease.release(); throw failure }
    }

    private fun encode(source: ProfilePhotoVideoSource, edit: ProfilePhotoVideoEdit,
        retainedSource: AttachmentLease, permit: ProfileVideoWorkerPermit,
    ): SettingsPreparedProfileAnimation {
        if (!validEdit(source, edit)) fail(ProfilePhotoVideoFailure.Reason.INVALID_EDIT)
        require(hashFile(source.path, source.size) == source.sha256)
        val ffmpeg = findFfmpeg() ?: fail(ProfilePhotoVideoFailure.Reason.TOOLS_MISSING)
        val target = temporary(".mp4")
        val lease = ownedAttachmentLease { runCatching { Files.deleteIfExists(target) } }
        try {
            val duration = edit.tillMillis - edit.fromMillis
            val filter = cropFilter(source, edit.crop)
            command(ffmpeg, inputOptions + listOf("-ss", seconds(edit.fromMillis), "-i", source.path.toString(),
                "-map", "0:${source.streamIndex}", "-t", seconds(duration), "-an", "-sn", "-dn",
                "-map_metadata", "-1", "-map_chapters", "-1", "-vf", filter, "-c:v", "libx264", "-threads", "1",
                "-preset", "veryfast", "-b:v", "1250000", "-maxrate", "1400000", "-bufsize", "700000",
                "-pix_fmt", "yuv420p", "-fps_mode", "cfr", "-metadata:s:v:0", "rotate=0",
                "-movflags", "+faststart", "-fs", (MAX_OUTPUT_BYTES + 65_536).toString(), "-f", "mp4", "-y", target.toString()),
                processTimeoutMillis, permit, listOf(retainedSource, lease), target)
            require(hashFile(source.path, source.size) == source.sha256)
            val size = attributes(target).size()
            require(size in 1..MAX_OUTPUT_BYTES)
            val info = probe(findFfprobe() ?: fail(ProfilePhotoVideoFailure.Reason.TOOLS_MISSING), target,
                countFrames = true, permit, lease).info
            // -fs must never turn a budget-truncated shorter clip into a successful selection.
            require(abs(info.duration * 1_000 - duration) <= 34.0)
            val prepared = SettingsPreparedProfileAnimation(target.toString(), size, DIMENSION,
                (info.duration * 1_000).roundToLong(), (edit.coverMillis - edit.fromMillis) / 1_000.0,
                hashFile(target, size), lease)
            validateOutput(prepared, lease, permit)
            return prepared
        } catch (failure: Throwable) { lease.release(); throw failure }
    }

    private fun coverFrame(animation: SettingsPreparedProfileAnimation, retained: AttachmentLease,
        permit: ProfileVideoWorkerPermit,
    ): SettingsPreparedProfilePhoto {
        validateOutput(animation, retained, permit)
        val ffmpeg = findFfmpeg() ?: fail(ProfilePhotoVideoFailure.Reason.TOOLS_MISSING)
        val target = temporary(".jpg")
        val lease = ownedAttachmentLease { runCatching { Files.deleteIfExists(target) } }
        try {
            // Seek-by-time may discard the final picture when the chosen time is inside its display interval.
            // The verified CFR output has at most 288 pictures, so select its actual containing frame by index.
            val frame = floor(animation.mainFrameTimestampSeconds * 30.0).toInt()
            command(ffmpeg, inputOptions + listOf("-i", animation.path, "-map", "0:v:0", "-an", "-sn", "-dn",
                "-frames:v", "1", "-vf", "select=eq(n\\,$frame),scale=320:320:flags=area,setsar=1",
                "-fps_mode", "vfr", "-c:v", "mjpeg", "-q:v", "3", "-threads", "1", "-y", target.toString()),
                20_000, permit, listOf(retained, lease), target)
            require(hashFile(localPath(animation.path), animation.size) == animation.sha256)
            val size = attributes(target).size()
            val cover = SettingsPreparedProfilePhoto(target.toString(), size, 320, hashFile(target, size), lease)
            require(validatePreparedProfilePhoto(cover))
            return cover
        } catch (failure: Throwable) { lease.release(); throw failure }
    }

    private fun validateOutput(animation: SettingsPreparedProfileAnimation, retained: AttachmentLease, permit: ProfileVideoWorkerPermit) {
        require(animation.size in 1..MAX_OUTPUT_BYTES && animation.dimension == DIMENSION &&
            animation.durationMillis in MIN_DURATION_MILLIS..MAX_DURATION_MILLIS &&
            animation.mainFrameTimestampSeconds.isFinite() && animation.mainFrameTimestampSeconds >= 0 &&
            animation.mainFrameTimestampSeconds < animation.durationMillis / 1_000.0 &&
            animation.sha256.matches(Regex("[a-f0-9]{64}")))
        val file = localPath(animation.path)
        val before = attributes(file)
        require(before.size() == animation.size && hashFile(file, animation.size) == animation.sha256)
        require(inspectMp4Layout(file).isMp4)
        val probe = probe(findFfprobe() ?: fail(ProfilePhotoVideoFailure.Reason.TOOLS_MISSING), file,
            countFrames = true, permit, retained)
        val info = probe.info
        require(info.codec == "h264" && !info.hasAudio && info.width == DIMENSION && info.height == DIMENSION &&
            info.displayWidth == DIMENSION && info.displayHeight == DIMENSION && abs(info.aspectRatio - 1.0) < .000001 &&
            info.duration in 1.0..9.600001 && (info.duration * 1_000).roundToLong() == animation.durationMillis &&
            animation.mainFrameTimestampSeconds < info.duration && probe.fps == 30.0 && probe.frames in 30..288 &&
            abs(info.duration - probe.frames / 30.0) <= .034)
        require(sameFile(before, attributes(file)) && hashFile(file, animation.size) == animation.sha256)
    }

    private data class Probe(val info: AttachmentFiles.VideoInfo, val fps: Double?, val frames: Int)

    private fun probe(executable: Path, file: Path, countFrames: Boolean, permit: ProfileVideoWorkerPermit, lease: AttachmentLease): Probe {
        val text = command(executable, listOf("-v", "error", "-protocol_whitelist", "file,pipe",
            "-threads", "1", "-max_alloc", "67108864", "-probesize", "8000000", "-analyzeduration", "5000000") +
            (if (countFrames) listOf("-count_frames") else emptyList()) + listOf("-show_entries",
            "stream=index,codec_type,codec_name,width,height,duration,sample_aspect_ratio,r_frame_rate,nb_read_frames:" +
                "stream_disposition=attached_pic:stream_side_data=rotation:format=format_name,duration",
            "-of", "default", "-i", file.toString()), 20_000, permit, listOf(lease))
        val info = AttachmentFiles.parseVideoInfo(text, includeAudio = true)
        val fields = text.lineSequence().dropWhile { it != "[STREAM]" }.drop(1).takeWhile { it != "[/STREAM]" }
            .mapNotNull { line -> line.indexOf('=').takeIf { it > 0 }?.let { line.take(it) to line.substring(it + 1).trim() } }
            .toMap()
        // Encoded output contains exactly our single video stream. Source fps is deliberately unrestricted.
        val rate = fields["r_frame_rate"]?.split('/')
        val fps = if (rate?.size == 2) rate[0].toDoubleOrNull()?.let { numerator ->
            rate[1].toDoubleOrNull()?.takeIf { it > 0 }?.let { numerator / it }
        } else null
        return Probe(info, fps, fields["nb_read_frames"]?.toIntOrNull() ?: 0)
    }

    /** Poll pipes instead of a blocking read: interruption closes a child even if it never writes stdout. */
    private fun command(executable: Path, arguments: List<String>, timeoutMillis: Long,
        permit: ProfileVideoWorkerPermit, leases: List<AttachmentLease>, output: Path? = null,
    ): String {
        val started = System.nanoTime()
        val releaseChild = retainProfileVideoChild(permit, leases)
        var process: Process? = null
        val bytes = ByteArrayOutputStream()
        try {
            interrupted()
            process = startProcess(listOf(executable.toString()) + arguments)
            process.outputStream.close()
            val buffer = ByteArray(4_096)
            while (true) {
                interrupted()
                check(System.nanoTime() - started < TimeUnit.MILLISECONDS.toNanos(minOf(timeoutMillis, processTimeoutMillis)))
                if (output != null) require(Files.size(output) <= MAX_OUTPUT_BYTES + 65_536)
                while (process.inputStream.available() > 0) {
                    interrupted()
                    val count = process.inputStream.read(buffer, 0, minOf(buffer.size, process.inputStream.available()))
                    if (count <= 0) break
                    require(bytes.size() <= 65_536 - count)
                    bytes.write(buffer, 0, count)
                }
                if (process.waitFor(10, TimeUnit.MILLISECONDS)) {
                    // A terminated child has flushed its pipe; drain on the next iteration without blocking at EOF.
                    if (process.inputStream.available() > 0) continue
                    check(process.exitValue() == 0)
                    return bytes.toString(Charsets.UTF_8)
                }
            }
        } finally {
            val child = process
            if (child == null) {
                releaseChild()
            } else {
                val wasInterrupted = Thread.interrupted()
                try {
                    runCatching { child.destroyForcibly() }
                    runCatching { child.outputStream.close() }
                    runCatching { child.inputStream.close() }
                    runCatching { child.errorStream.close() }
                    if (runCatching { child.waitFor(2, TimeUnit.SECONDS) }.getOrDefault(false)) releaseChild()
                    else ProfileVideoProcessReaper.watch(child, releaseChild)
                } finally { if (wasInterrupted) Thread.currentThread().interrupt() }
            }
        }
    }

    private fun temporary(suffix: String): Path {
        val directory = localPath(tempDirectory().toString())
        require(Files.isDirectory(directory))
        return Files.createTempFile(directory, "nnggram-profile-video-", suffix)
    }

    companion object {
        // These are local resource budgets, not claimed official profile-video source/byte constants.
        const val MAX_SOURCE_BYTES = 4_000_000_000L
        const val MAX_SOURCE_PIXELS = 32_000_000L
        const val MAX_OUTPUT_BYTES = 2_000_000L
        const val DIMENSION = 800
        const val MIN_DURATION_MILLIS = 1_000L
        const val MAX_DURATION_MILLIS = 9_600L

        private val inputOptions = listOf("-nostdin", "-hide_banner", "-loglevel", "error", "-protocol_whitelist", "file,pipe",
            "-threads", "1", "-filter_threads", "1", "-filter_complex_threads", "1", "-max_alloc", "67108864")

        internal fun validEdit(source: ProfilePhotoVideoSource, edit: ProfilePhotoVideoEdit): Boolean =
            edit.crop.x.isFinite() && edit.crop.y.isFinite() && edit.crop.zoom.isFinite() &&
                edit.crop.x in 0f..1f && edit.crop.y in 0f..1f && edit.crop.zoom in 1f..4f &&
                edit.fromMillis >= 0 && edit.tillMillis >= edit.fromMillis && edit.tillMillis <= source.durationMillis &&
                edit.tillMillis - edit.fromMillis in MIN_DURATION_MILLIS..MAX_DURATION_MILLIS &&
                edit.coverMillis >= edit.fromMillis && edit.coverMillis < edit.tillMillis

        internal fun cropFilter(source: ProfilePhotoVideoSource, crop: ProfilePhotoCrop): String {
            val side = floor(minOf(source.width, source.height) / crop.zoom.toDouble()).toInt().coerceAtLeast(1)
            val left = floor((source.width - side) * crop.x.toDouble()).toInt()
            val top = floor((source.height - side) * crop.y.toDouble()).toInt()
            // FFmpeg applies display-matrix autorotation before these filters; then normalize anamorphic pixels.
            return "scale=${source.width}:${source.height}:flags=area,setsar=1," +
                "crop=$side:$side:$left:$top:exact=1,scale=800:800:flags=area,setsar=1,setpts=PTS-STARTPTS,fps=30,format=yuv420p"
        }

        private fun seconds(millis: Long) = String.format(Locale.ROOT, "%.3f", millis / 1_000.0)
        private fun localPath(value: String): Path = Path.of(value).also {
            require(it.isAbsolute && !value.startsWith("\\\\") && it.toUri().authority.isNullOrBlank())
        }.normalize()
        private fun attributes(path: Path): BasicFileAttributes =
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).also {
                require(it.isRegularFile && !it.isSymbolicLink)
            }
        private fun sameFile(first: BasicFileAttributes, second: BasicFileAttributes) =
            first.size() == second.size() && first.fileKey() == second.fileKey() && first.lastModifiedTime() == second.lastModifiedTime()

        private fun hashFile(path: Path, expectedSize: Long, deadline: Long = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)): String {
            require(expectedSize in 1..MAX_SOURCE_BYTES)
            val before = attributes(path)
            require(before.size() == expectedSize)
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                Channels.newInputStream(channel).use { input ->
                    val buffer = ByteArray(65_536)
                    while (true) {
                        interrupted()
                        check(System.nanoTime() < deadline)
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(count.toLong() <= expectedSize - size)
                        digest.update(buffer, 0, count)
                        size += count
                    }
                }
            }
            require(size == expectedSize && sameFile(before, attributes(path)))
            return digest.digest().hex()
        }

        private fun ByteArray.hex() = joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        private fun interrupted() { if (Thread.currentThread().isInterrupted) throw InterruptedException() }
        private fun fail(reason: ProfilePhotoVideoFailure.Reason): Nothing = throw ProfilePhotoVideoFailure(reason)
        private inline fun <T> sanitized(reason: ProfilePhotoVideoFailure.Reason, block: () -> T): T = try { block() }
            catch (failure: ProfilePhotoVideoFailure) { throw failure }
            catch (failure: CancellationException) { throw failure }
            catch (failure: InterruptedException) { throw failure }
            catch (_: Exception) { fail(reason) }
        private inline fun <T> serial(block: (ProfileVideoWorkerPermit) -> T): T {
            // A quarantined child can keep the one slot; queued callers still cancel or fail within a finite wait.
            if (!profileVideoWorker.tryAcquire(90, TimeUnit.SECONDS)) fail(ProfilePhotoVideoFailure.Reason.PREPARATION_FAILED)
            val permit = ProfileVideoWorkerPermit()
            try { return block(permit) } finally { permit.release() }
        }
    }
}

/** The execution and its child each own a reference; a late child exit may release the last reference. */
private class ProfileVideoWorkerPermit {
    private val references = AtomicInteger(1)
    fun retain() { check(references.incrementAndGet() > 1) }
    fun release() {
        val remaining = references.decrementAndGet()
        check(remaining >= 0)
        if (remaining == 0) profileVideoWorker.release()
    }
}

private fun retainProfileVideoChild(permit: ProfileVideoWorkerPermit, leases: List<AttachmentLease>): () -> Unit {
    val retained = mutableListOf<AttachmentLease>()
    try { leases.forEach { retained += checkNotNull(it.retain()) } }
    catch (failure: Throwable) { retained.forEach { it.release() }; throw failure }
    permit.retain()
    val released = AtomicBoolean()
    return {
        if (released.compareAndSet(false, true)) {
            try { retained.forEach { it.release() } } finally { permit.release() }
        }
    }
}

/** At most one deferred child exists: its permit prevents all following workers from starting a process. */
private object ProfileVideoProcessReaper {
    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "profile-video-process-reaper").apply { isDaemon = true }
    }

    fun watch(process: Process, release: () -> Unit) {
        val poll = object : Runnable {
            override fun run() {
                if (runCatching { process.waitFor(0, TimeUnit.MILLISECONDS) }.getOrDefault(false)) release()
                else executor.schedule(this, 50, TimeUnit.MILLISECONDS)
            }
        }
        executor.execute(poll)
    }
}
