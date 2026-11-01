package com.badnng.moe.media

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import com.badnng.moe.chat.MediaFormat
import com.badnng.moe.tdlib.ProfileEmojiEncoded
import com.badnng.moe.tdlib.ProfileEmojiFileRole
import org.jetbrains.skia.*
import org.jetbrains.skia.skottie.Animation
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException
import java.util.zip.GZIPInputStream
import kotlin.math.roundToInt

/** Owned native bitmap. The public source exposes it only through an independent live lease. */
internal class PreparedProfileEmojiFrame(
    private val bitmap: Bitmap,
    val isAlphaMask: Boolean,
    val animated: Boolean,
    val durationMillis: Long,
    val frameDurationMillis: Long,
) : AutoCloseable {
    private val alive = AtomicBoolean(true)
    private val composeImage = bitmap.asComposeImageBitmap()
    val pixels: Long = bitmap.width.toLong() * bitmap.height
    val image: ImageBitmap? get() = if (alive.get()) composeImage else null
    internal fun withAnimation(duration: Long, interval: Long): PreparedProfileEmojiFrame {
        check(alive.compareAndSet(true, false))
        return PreparedProfileEmojiFrame(bitmap, isAlphaMask, true, duration, interval)
    }
    override fun close() { if (alive.compareAndSet(true, false)) bitmap.close() }
}

internal fun interface ProfileEmojiArtworkDecoder {
    /** No native path supplied by a consumer; every byte came from the profile-files authority. */
    fun decode(encoded: ProfileEmojiArtworkInput, request: ProfileEmojiArtworkRequest,
        alphaMask: Boolean, active: () -> Boolean): PreparedProfileEmojiFrame
}

/** Trusted worker-only immutable borrow. Public/native callers never receive this buffer. */
internal class ProfileEmojiArtworkInput(native: ProfileEmojiEncoded) {
    val emoji = native.emoji
    val role = native.role
    val file = native.file
    private val content = native.bytes()
    val size get() = content.size
    /** Read only, valid during decode() only. Never mutate or retain beyond that call. */
    internal fun borrowBytes(): ByteArray = content
    override fun toString() = "ProfileEmojiArtworkInput(<redacted>)"
}
internal class ProfileEmojiArtworkBusyException : IllegalStateException("Profile emoji resources are busy")

/** A separate bounded pool: sticker-grid budgets and sound resources never participate. */
internal class ProfileEmojiArtworkWorkers : AutoCloseable {
    private val executor = ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(32),
        { work -> Thread(work, "profile-emoji-artwork").apply { isDaemon = true } })
    fun submit(work: () -> Unit): AutoCloseable? {
        val task = FutureTask<Unit> { work() }
        return try {
            executor.execute(task)
            AutoCloseable { task.cancel(true); executor.remove(task) }
        } catch (_: RejectedExecutionException) { null }
    }
    // Queued owners observe retirement before decoding; drain them so their budget finally runs.
    override fun close() { executor.shutdown() }
}

/** Silent codec subprocess ownership; independently fakeable without opening files or devices. */
internal class ProfileEmojiOwnedCodecProcesses(private val startProcess: (List<String>) -> Process) : AutoCloseable {
    private val alive = AtomicBoolean(true)
    private val processes = ConcurrentHashMap.newKeySet<Process>()
    fun run(command: List<String>, maximum: Int, active: () -> Boolean,
        consume: ((java.io.InputStream) -> ByteArray)? = null): ByteArray {
        require(maximum in 0..24 * 1024 * 1024)
        if (!alive.get() || Thread.currentThread().isInterrupted || !active()) throw InterruptedException()
        val process = startProcess(command)
        processes.add(process)
        val finished = AtomicBoolean()
        val monitor = Thread({
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (!finished.get()) {
                if (System.nanoTime() >= deadline || !alive.get() || !runCatching(active).getOrDefault(false)) {
                    process.destroyForcibly(); break
                }
                try { Thread.sleep(25) } catch (_: InterruptedException) { break }
            }
        }, "profile-emoji-codec-cancellation").apply { isDaemon = true; start() }
        try {
            process.outputStream.close()
            val output = process.inputStream.use { stream -> consume?.invoke(stream) ?: stream.readNBytes(maximum + 1) }
            require(output.size <= maximum)
            if (!alive.get() || Thread.currentThread().isInterrupted || !active()) throw InterruptedException()
            require(process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0)
            return output
        } finally {
            finished.set(true); monitor.interrupt(); process.destroyForcibly()
            runCatching { process.inputStream.close() }; runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
            runCatching { process.waitFor(2, TimeUnit.SECONDS) }
            processes.remove(process)
        }
    }
    override fun close() {
        if (!alive.compareAndSet(true, false)) return
        processes.toList().forEach { process ->
            process.destroyForcibly(); runCatching { process.waitFor(2, TimeUnit.SECONDS) }
        }
    }
}

/** Skia/WebM extraction is completely worker-side. Drawing receives immutable finished pixels. */
internal class SkiaProfileEmojiArtworkDecoder(
    private val ffmpeg: () -> Path? = MediaDecoder::findFfmpeg,
    private val startProcess: (List<String>) -> Process = { command ->
        ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    },
) : ProfileEmojiArtworkDecoder, AutoCloseable {
    private val alive = AtomicBoolean(true)
    private val clips = ConcurrentHashMap<String, ClipPreparation>()
    private val clipLock = Any()
    private val clipOrder = linkedMapOf<String, SilentClip>()
    private var diskBytes = 0L
    private val ownedFiles = linkedMapOf<Path, Long>()
    private val ownedDirectories = linkedSetOf<Path>()
    private val processes = ProfileEmojiOwnedCodecProcesses(startProcess)

    private class SilentClip(val directory: Path, val frames: List<Path>, val size: Long,
        val duration: Long, val interval: Long) {
        var readers = 0
        var retired = false
    }
    private inner class ClipPreparation {
        val future = CompletableFuture<SilentClip>()
        val members = java.util.concurrent.CopyOnWriteArrayList<() -> Boolean>()
        fun current() = alive.get() && members.any { runCatching(it).getOrDefault(false) }
    }
    override fun decode(encoded: ProfileEmojiArtworkInput, request: ProfileEmojiArtworkRequest,
        alphaMask: Boolean, active: () -> Boolean): PreparedProfileEmojiFrame {
        require(alive.get() && request.physicalSizePx in 1..MAX_EDGE && request.frameTimeMillis >= 0)
        require(encoded.size in 1..MAX_ENCODED)
        checkActive(active)
        val bytes = encoded.borrowBytes()
        val media = encoded.emoji.sticker.media
        val format = if (encoded.role == ProfileEmojiFileRole.THUMBNAIL) media.thumbnailFormat else media.format
        return when (format) {
            MediaFormat.WEBP, MediaFormat.PHOTO -> still(bytes, request, alphaMask, active)
            MediaFormat.TGS -> vector(bytes, request, alphaMask, active)
            MediaFormat.WEBM -> video(bytes, request, alphaMask, active,
                "${encoded.emoji.customEmojiId}:${encoded.file.id}:${encoded.file.uniqueId}:${encoded.file.size}:${encoded.role}")
            else -> error("Unsupported profile emoji format")
        }
    }

    private fun still(bytes: ByteArray, request: ProfileEmojiArtworkRequest, mask: Boolean,
        active: () -> Boolean): PreparedProfileEmojiFrame = Data.makeFromBytes(bytes).use { data ->
        Codec.makeFromData(data).use { codec ->
            require(codec.encodedImageFormat in setOf(EncodedImageFormat.WEBP, EncodedImageFormat.PNG, EncodedImageFormat.JPEG) &&
                codec.frameCount == 1 && codec.width in 1..8192 && codec.height in 1..8192 &&
                codec.width.toLong() * codec.height <= MAX_SOURCE_PIXELS)
            checkActive(active)
            val info = ImageInfo(codec.width, codec.height, ColorType.RGBA_8888, ColorAlphaType.PREMUL, codec.colorSpace)
            Bitmap().use { bitmap ->
                require(bitmap.allocPixels(info))
                codec.readPixels(bitmap)
                checkActive(active)
                Image.makeFromBitmap(bitmap).let { image ->
                    raster(image, request.physicalSizePx, mask, false, 0, 0, active)
                }
            }
        }
    }

    private fun vector(bytes: ByteArray, request: ProfileEmojiArtworkRequest, mask: Boolean,
        active: () -> Boolean): PreparedProfileEmojiFrame {
        val json = GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readNBytes(MAX_LOTTIE + 1) }
        require(json.size in 1..MAX_LOTTIE)
        checkActive(active)
        return Data.makeFromBytes(json).use { data -> Animation.makeFromData(data).use { animation ->
            require(animation.width in 1f..2048f && animation.height in 1f..2048f &&
                animation.duration.isFinite() && animation.duration in .01f..60f &&
                animation.fPS.isFinite() && animation.fPS in 1f..120f)
            val duration = (animation.duration * 1000).toLong().coerceAtLeast(1)
            val interval = (1000.0 / animation.fPS).roundToInt().toLong().coerceAtLeast(1)
            val actualTime = frameTime(request.frameTimeMillis, duration, interval)
            val edge = request.physicalSizePx
            val info = ImageInfo(edge, edge, ColorType.RGBA_8888, ColorAlphaType.PREMUL, ColorSpace.sRGB)
            Surface.makeRaster(info).use { surface ->
                surface.canvas.clear(0)
                val scale = minOf(edge / animation.width, edge / animation.height)
                val width = animation.width * scale; val height = animation.height * scale
                animation.seek(actualTime.toFloat() / duration)
                animation.render(surface.canvas, Rect.makeXYWH((edge - width) / 2, (edge - height) / 2, width, height))
                checkActive(active)
                surface.makeImageSnapshot().let { image ->
                    raster(image, edge, mask, true, duration, interval, active)
                }
            }
        } }
    }

    private fun video(bytes: ByteArray, request: ProfileEmojiArtworkRequest, mask: Boolean,
        active: () -> Boolean, identity: String): PreparedProfileEmojiFrame {
        val key = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) } + ":$identity:${request.physicalSizePx}"
        val created = ClipPreparation()
        val preparation = clips.putIfAbsent(key, created) ?: created
        preparation.members.add(active)
        try {
            val future = preparation.future
            if (preparation === created) {
                try {
                    val clip = prepareVideoClip(bytes, request.physicalSizePx, preparation::current)
                    val retired = mutableListOf<SilentClip>()
                    val accepted = synchronized(clipLock) {
                        while (clipOrder.size >= 8) {
                            val oldest = clipOrder.entries.firstOrNull { it.value.readers == 0 } ?: break
                            clipOrder.remove(oldest.key); clips.remove(oldest.key)
                            oldest.value.retired = true; retired += oldest.value
                        }
                        if (!alive.get() || clipOrder.size >= 8) false
                        else { clipOrder[key] = clip; true }
                    }
                    retired.forEach { deleteClip(it) }
                    if (accepted) future.complete(clip)
                    else { deleteClip(clip); throw ProfileEmojiArtworkBusyException() }
                } catch (error: Throwable) { clips.remove(key, created); future.completeExceptionally(error) }
            }
            var awaiting: SilentClip? = null
            while (awaiting == null) {
                checkActive(active)
                try { awaiting = future.get(50, TimeUnit.MILLISECONDS) }
                catch (_: TimeoutException) { /* cancellation checks only while owned work is pending */ }
                catch (error: java.util.concurrent.ExecutionException) { throw error.cause ?: error }
            }
            val clip = checkNotNull(awaiting)
            synchronized(clipLock) {
                if (!alive.get()) throw InterruptedException("Artwork source retired")
                if (clip.retired) throw ProfileEmojiArtworkBusyException()
                clip.readers++
                clipOrder.remove(key); clipOrder[key] = clip
            }
            try {
                val index = ((request.frameTimeMillis % clip.duration) / clip.interval).toInt().coerceIn(0, clip.frames.lastIndex)
                val frameBytes = Files.newInputStream(clip.frames[index]).use { it.readNBytes(MAX_ENCODED + 1) }
                require(frameBytes.size in 1..MAX_ENCODED)
                val image = still(frameBytes, request, mask, active)
                // Share its exact owned bitmap without making a second bitmap allocation.
                return image.withAnimation(clip.duration, clip.interval)
            } finally {
                val remove = synchronized(clipLock) { clip.readers--; clip.retired && clip.readers == 0 }
                if (remove) deleteClip(clip)
            }
        } finally { preparation.members.remove(active) }
    }

    private fun prepareVideoClip(bytes: ByteArray, edge: Int, active: () -> Boolean): SilentClip {
        val executable = ffmpeg() ?: error("Video decoder unavailable")
        checkActive(active)
        // This is our own bounded scratch encoding, never a consumer path or a TDLib path.
        val directory = Files.createTempDirectory("nnggram-profile-emoji-")
        synchronized(clipLock) { ownedDirectories.add(directory) }
        val temporary = directory.resolve("input.webm")
        var retained = false
        try {
            writeScratch(temporary, bytes)
            val probe = executable.resolveSibling(if (executable.fileName.toString().endsWith(".exe", true)) "ffprobe.exe" else "ffprobe")
            require(Files.isRegularFile(probe))
            val metadata = ownedProcess(listOf(probe.toString(), "-v", "error", "-protocol_whitelist", "file,pipe",
                "-select_streams", "v:0", "-show_entries", "stream=width,height,avg_frame_rate:format=duration",
                "-of", "default=noprint_wrappers=1", temporary.toString()), 4096, active)
                .toString(Charsets.UTF_8).lineSequence().mapNotNull { line ->
                    val pair = line.split('=', limit = 2); if (pair.size == 2) pair[0] to pair[1] else null
                }.toMap()
            val width = metadata["width"]?.toIntOrNull() ?: error("Invalid video size")
            val height = metadata["height"]?.toIntOrNull() ?: error("Invalid video size")
            val durationSeconds = metadata["duration"]?.toDoubleOrNull() ?: error("Missing video duration")
            val rate = metadata["avg_frame_rate"]?.split('/') ?: error("Missing video rate")
            val numerator = rate.getOrNull(0)?.toDoubleOrNull() ?: error("Invalid video rate")
            val denominator = rate.getOrNull(1)?.toDoubleOrNull() ?: 1.0
            val fps = numerator / denominator
            require(width in 1..2048 && height in 1..2048 && durationSeconds.isFinite() &&
                durationSeconds in .01..60.0 && fps.isFinite() && fps in 1.0..120.0)
            val duration = (durationSeconds * 1000).toLong().coerceAtLeast(1)
            val sampleRate = minOf(fps, 20.0, 60.0 / durationSeconds)
            val interval = (1000.0 / sampleRate).roundToInt().toLong().coerceAtLeast(1)
            var frameCount = 0
            var frameBytes = 0L
            val nativeFrameBytes = width * height * 4 // Dimensions were bounded before this allocation.
            ownedProcess(profileEmojiSilentClipCommand(executable, temporary, edge, sampleRate), 0, active) { stream ->
                while (frameCount < 60) {
                    checkActive(active)
                    val rgba = stream.readNBytes(nativeFrameBytes)
                    if (rgba.isEmpty()) break
                    require(rgba.size == nativeFrameBytes)
                    // Interpolation must happen only after native-size straight RGBA is premultiplied.
                    val png = profileEmojiVideoFramePng(rgba, width, height, edge) { alive.get() && active() }
                    require(frameBytes + png.size <= MAX_ENCODED)
                    writeScratch(directory.resolve(String.format(Locale.ROOT, "frame-%03d.png", frameCount)), png)
                    frameBytes += png.size
                    frameCount++
                }
                ByteArray(0)
            }
            deleteOwned(temporary)
            val frames = (0 until frameCount).map { directory.resolve(String.format(Locale.ROOT, "frame-%03d.png", it)) }
            require(frames.size in 1..60)
            val size = frames.sumOf { Files.size(it) }
            require(size in 1..MAX_ENCODED.toLong())
            retained = true
            return SilentClip(directory, frames, size, duration, interval)
        } finally {
            if (!retained) deleteDirectory(directory)
        }
    }

    private fun ownedProcess(command: List<String>, maximum: Int, active: () -> Boolean,
        consume: ((java.io.InputStream) -> ByteArray)? = null): ByteArray =
        processes.run(command, maximum, { alive.get() && active() }, consume)

    /** Owns input. AreaRasterImage prepares physical pixel coverage before the snapshot readback. */
    private fun raster(image: Image, edge: Int, mask: Boolean, animated: Boolean, duration: Long,
        interval: Long, active: () -> Boolean): PreparedProfileEmojiFrame = AreaRasterImage(image).use { raster ->
        checkActive(active)
        val scale = minOf(edge.toFloat() / raster.width, edge.toFloat() / raster.height)
        val width = (raster.width * scale).roundToInt().coerceIn(1, edge)
        val height = (raster.height * scale).roundToInt().coerceIn(1, edge)
        raster.prepare(width, height)
        checkActive(active)
        val info = ImageInfo(edge, edge, ColorType.RGBA_8888, ColorAlphaType.PREMUL, image.colorSpace)
        val bitmap = Bitmap()
        try {
            require(bitmap.allocPixels(info))
            val bytes = Surface.makeRaster(info).use { surface ->
                surface.canvas.clear(0)
                raster.draw(surface.canvas, Rect.makeXYWH((edge - width) / 2f, (edge - height) / 2f,
                    width.toFloat(), height.toFloat()))
                surface.makeImageSnapshot().use { snapshot ->
                    require(snapshot.readPixels(bitmap)); checkNotNull(bitmap.readPixels(info))
                }
            }
            if (mask) {
                profileEmojiAlphaMask(bytes)
                // Install alpha-preserving premultiplied white, so SrcIn can tint real artwork.
                Image.makeRaster(info, bytes, edge * 4).use { require(it.readPixels(bitmap)) }
            }
            checkActive(active)
            PreparedProfileEmojiFrame(bitmap, mask, animated, duration, interval)
        } catch (error: Throwable) { bitmap.close(); throw error }
    }

    private fun checkActive(active: () -> Boolean) {
        if (!alive.get() || Thread.currentThread().isInterrupted || !active()) throw InterruptedException("Artwork owner retired")
    }

    override fun close() {
        if (!alive.compareAndSet(true, false)) return
        processes.close()
        val dispose = synchronized(clipLock) {
            clipOrder.values.onEach { it.retired = true }.filter { it.readers == 0 }.also {
                clipOrder.clear(); clips.clear()
            }
        }
        dispose.forEach { deleteClip(it) }
        val directories = synchronized(clipLock) { ownedDirectories.toList() }
        // An open Windows handle can reject deletion. Keep that charge/registration so the
        // final reader or preparation retries cleanup when it relinquishes the exact file.
        directories.forEach(::deleteDirectory)
    }

    private fun writeScratch(path: Path, bytes: ByteArray) {
        while (true) {
            var evicted: SilentClip? = null
            val reserved = synchronized(clipLock) {
                if (!alive.get()) throw InterruptedException("Artwork source retired")
                if (diskBytes + bytes.size <= MAX_ENCODED) {
                    require(path.parent in ownedDirectories && path !in ownedFiles)
                    diskBytes += bytes.size; ownedFiles[path] = bytes.size.toLong(); true
                } else {
                    val old = clipOrder.entries.firstOrNull { it.value.readers == 0 }
                    if (old != null) {
                        clipOrder.remove(old.key); clips.remove(old.key)
                        old.value.retired = true; evicted = old.value
                    }
                    false
                }
            }
            if (reserved) break
            val clip = evicted ?: throw ProfileEmojiArtworkBusyException()
            deleteClip(clip)
        }
        try { Files.write(path, bytes) } catch (error: Throwable) { deleteOwned(path); throw error }
    }

    private fun deleteOwned(path: Path) {
        val registered = synchronized(clipLock) { path in ownedFiles }
        if (!registered) return
        // Only the exact registered file is eligible. Failed deletion retains its disk charge.
        if (runCatching { Files.deleteIfExists(path); true }.getOrDefault(false)) synchronized(clipLock) {
            ownedFiles.remove(path)?.let { diskBytes -= it }
        }
    }

    private fun deleteClip(clip: SilentClip) = deleteDirectory(clip.directory)
    private fun deleteDirectory(directory: Path) {
        val paths = synchronized(clipLock) { ownedFiles.keys.filter { it.parent == directory } }
        paths.forEach(::deleteOwned)
        // Delete an empty owned directory only; never enumerate or remove an unregistered file.
        if (runCatching { Files.deleteIfExists(directory); true }.getOrDefault(false)) synchronized(clipLock) {
            ownedDirectories.remove(directory)
        }
    }

    private companion object {
        const val MAX_EDGE = 512
        const val MAX_ENCODED = 24 * 1024 * 1024
        const val MAX_LOTTIE = 2 * 1024 * 1024
        const val MAX_SOURCE_PIXELS = 24_000_000L
        fun frameTime(time: Long, duration: Long, interval: Long) = (time % duration) / interval * interval
    }
}

internal fun profileEmojiAlphaMask(bytes: ByteArray) {
    require(bytes.size % 4 == 0)
    for (offset in bytes.indices step 4) {
        val alpha = bytes[offset + 3]
        bytes[offset] = alpha; bytes[offset + 1] = alpha; bytes[offset + 2] = alpha
    }
}

/** Native-size straight RGBA enters once; physical coverage is integrated only in PREMUL storage. */
internal fun profileEmojiVideoFramePng(rgba: ByteArray, width: Int, height: Int, edge: Int,
    active: () -> Boolean): ByteArray {
    require(width in 1..2048 && height in 1..2048 && edge in 1..512 && rgba.size == width * height * 4)
    fun checkCurrent() {
        if (Thread.currentThread().isInterrupted || !active()) throw InterruptedException("Artwork owner retired")
    }
    checkCurrent()
    premultiplyMediaRgba(rgba)
    val nativeInfo = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL, ColorSpace.sRGB)
    return AreaRasterImage(Image.makeRaster(nativeInfo, rgba, width * 4)).use { raster ->
        val scale = minOf(edge.toFloat() / width, edge.toFloat() / height)
        val targetWidth = (width * scale).roundToInt().coerceIn(1, edge)
        val targetHeight = (height * scale).roundToInt().coerceIn(1, edge)
        raster.prepare(targetWidth, targetHeight)
        checkCurrent()
        val targetInfo = ImageInfo(edge, edge, ColorType.RGBA_8888, ColorAlphaType.PREMUL, ColorSpace.sRGB)
        Surface.makeRaster(targetInfo).use { surface ->
            surface.canvas.clear(0)
            raster.draw(surface.canvas, Rect.makeXYWH((edge - targetWidth) / 2f, (edge - targetHeight) / 2f,
                targetWidth.toFloat(), targetHeight.toFloat()))
            checkCurrent()
            surface.makeImageSnapshot().use { image ->
                checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).use { it.bytes }
            }
        }
    }
}

/** Exactly one silent native-size frame; worker preparation owns all pixel interpolation. */
internal fun profileEmojiSilentFrameCommand(executable: Path, input: Path, edge: Int, timeMillis: Long): List<String> {
    require(edge in 1..512 && timeMillis >= 0)
    return listOf(executable.toString(), "-nostdin", "-hide_banner", "-loglevel", "error",
        "-protocol_whitelist", "file,pipe", "-threads", "1", "-c:v", "libvpx-vp9", "-i", input.toString(),
        "-ss", String.format(Locale.ROOT, "%.3f", timeMillis / 1000.0), "-an", "-sn", "-dn", "-map", "0:v:0",
        "-pix_fmt", "rgba", "-threads", "1", "-frames:v", "1", "-f", "rawvideo", "pipe:1")
}

/** Finite actual transparent frames, encoded to bounded on-disk PNGs by the worker. */
internal fun profileEmojiSilentClipCommand(executable: Path, input: Path, edge: Int, sampleRate: Double): List<String> {
    require(edge in 1..512 && sampleRate.isFinite() && sampleRate in 1.0..20.0)
    return listOf(executable.toString(), "-nostdin", "-hide_banner", "-loglevel", "error",
        "-protocol_whitelist", "file,pipe", "-threads", "1", "-c:v", "libvpx-vp9", "-i", input.toString(),
        "-an", "-sn", "-dn", "-map", "0:v:0",
        "-vf", "fps=${String.format(Locale.ROOT, "%.8f", sampleRate)}",
        "-pix_fmt", "rgba", "-threads", "1", "-frames:v", "60", "-f", "rawvideo", "pipe:1")
}
