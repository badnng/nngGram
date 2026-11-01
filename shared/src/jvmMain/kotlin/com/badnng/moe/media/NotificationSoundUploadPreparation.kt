package com.badnng.moe.media

import com.badnng.moe.chat.AttachmentLease
import com.badnng.moe.chat.ownedAttachmentLease
import com.badnng.moe.settings.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** User-selected bytes remain unchanged; only an owned, seekable copy is decoded without an audio device. */
internal class NotificationSoundUploadPreparation(
    private val findFfmpeg: () -> Path? = MediaDecoder::findFfmpeg,
    private val startProcess: (List<String>) -> Process = { ProcessBuilder(it).start() },
    private val tempDirectory: () -> Path = { Path.of(System.getProperty("java.io.tmpdir")) },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val timeoutMillis: Long = 10_000,
) {
    init { require(timeoutMillis in 1..10_000) }

    suspend fun prepare(path: String, limits: NotificationSoundLimits, active: () -> Boolean): SettingsResult<SettingsPreparedNotificationSound> {
        val staged = AtomicReference<Staged?>()
        val delivered = AtomicReference<SettingsPreparedNotificationSound?>()
        fun failed(error: SettingsError): SettingsResult<SettingsPreparedNotificationSound> {
            delivered.getAndSet(null)?.lease?.release()
            return SettingsResult(error = error)
        }
        try {
            return withContext(dispatcher) {
                if (!limits.known) return@withContext SettingsResult(error = SettingsError.NOT_READY)
                if (!current(active)) return@withContext SettingsResult(error = SettingsError.NOT_READY)
                slots.withPermit {
                    try {
                        withTimeout(timeoutMillis) {
                            val executable = findFfmpeg() ?: return@withTimeout SettingsResult(error = SettingsError.UNSUPPORTED)
                            val copy = runInterruptible(dispatcher) {
                                stage(path, minOf(limits.maximumBytes!!, MAX_BYTES), active).also(staged::set)
                            }
                            val maximumMillis = minOf(limits.maximumDurationSeconds!!.toLong() * 1_000, MAX_MILLIS)
                            val frames = countFrames(executable, copy, maximumMillis, active)
                            val duration = (frames * 1_000 + SAMPLE_RATE - 1) / SAMPLE_RATE
                            val upload = PreparedNotificationSoundUpload(copy.path.toString(), copy.size, duration, copy.sha256)
                            require(runInterruptible(dispatcher) { validatePreparedNotificationSoundUpload(upload) })
                            ensureActive()
                            if (!current(active)) throw InactiveUpload()
                            val result = SettingsPreparedNotificationSound(upload, copy.lease)
                            delivered.set(result); staged.set(null)
                            SettingsResult(result)
                        }
                    } catch (cancelled: CancellationException) {
                        currentCoroutineContext().ensureActive()
                        if (cancelled is TimeoutCancellationException) failed(SettingsError.REQUEST_FAILED) else throw cancelled
                    } catch (_: InactiveUpload) {
                        failed(SettingsError.NOT_READY)
                    } catch (_: IllegalArgumentException) {
                        failed(SettingsError.INVALID_VALUE)
                    } catch (_: Exception) {
                        failed(if (current(active)) SettingsError.REQUEST_FAILED else SettingsError.NOT_READY)
                    } finally { staged.getAndSet(null)?.lease?.release() }
                }
            }
        } catch (failure: Throwable) {
            delivered.getAndSet(null)?.lease?.release()
            staged.getAndSet(null)?.lease?.release()
            throw failure
        }
    }

    private fun stage(source: String, maximumBytes: Long, active: () -> Boolean): Staged {
        if (!current(active)) throw InactiveUpload()
        val original = localSoundPath(source)
        val basename = original.fileName.toString()
        require(basename == source.substringAfterLast('\\').substringAfterLast('/'))
        require(basename.isNotEmpty() && basename != "." && basename != ".." && !basename.endsWith(' ') && !basename.endsWith('.') &&
            basename.none { it.code < 32 || it in "<>:\"/\\|?*" })
        require(basename.substringBefore('.').trimEnd(' ').uppercase(Locale.ROOT) !in RESERVED_NAMES)
        val suffix = when (basename.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "mp3", "mpeg3" -> "mp3"
            "ogg", "oga" -> "ogg"
            else -> throw IllegalArgumentException()
        }
        val before = regularAttributes(original)
        require(before.size() in 1..maximumBytes)
        val bytes = FileChannel.open(original, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            java.nio.channels.Channels.newInputStream(channel).use { it.readNBytes(maximumBytes.toInt() + 1) }
        }
        val after = regularAttributes(original)
        require(bytes.size.toLong() == before.size() && bytes.size.toLong() <= maximumBytes && sameFile(before, after))
        require(if (suffix == "mp3") mp3Header(bytes) else bytes.size >= 27 &&
            bytes[0] == 79.toByte() && bytes[1] == 103.toByte() && bytes[2] == 103.toByte() && bytes[3] == 83.toByte() && bytes[4] == 0.toByte())
        if (!current(active)) throw InactiveUpload()
        val directory = localSoundPath(tempDirectory().toAbsolutePath().normalize().toString())
        require(Files.isDirectory(directory))
        val ownedDirectory = Files.createTempDirectory(directory, "nnggram-notification-sound-").toAbsolutePath().normalize()
        val target = ownedDirectory.resolve(basename).normalize()
        val childOwned = AtomicBoolean()
        val lease = ownedAttachmentLease {
            if (childOwned.get()) runCatching { Files.deleteIfExists(target) }
            // Never recurse: an unexpected file must survive, even when that leaves our directory behind.
            runCatching { Files.deleteIfExists(ownedDirectory) }
        }
        try {
            check(ownedDirectory.parent == directory && target.parent == ownedDirectory && target != original && target.fileName.toString() == basename)
            FileChannel.open(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
                childOwned.set(true)
                val buffer = java.nio.ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
            }
            if (!current(active)) throw InactiveUpload()
            return Staged(target, bytes.size.toLong(), hash(bytes), suffix, lease)
        } catch (failure: Throwable) { lease.release(); throw failure }
    }

    private suspend fun countFrames(executable: Path, source: Staged, maximumMillis: Long, active: () -> Boolean): Long = coroutineScope {
        val process = UploadProcessOwner()
        val cleanup = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { process.close() }
        }
        try {
            if (!current(active)) throw InactiveUpload()
            val codecs = if (source.format == "mp3") "mp3,mp3float" else "vorbis,opus"
            val command = listOf(executable.toString(), "-nostdin", "-hide_banner", "-loglevel", "error",
                "-protocol_whitelist", "file,pipe", "-format_whitelist", source.format, "-codec_whitelist", codecs,
                "-f", source.format, "-i", source.path.toString(), "-map", "0:a:0", "-vn", "-sn", "-dn",
                "-acodec", "pcm_s16le", "-ar", "48000", "-ac", "2", "-f", "s16le", "pipe:1")
            val native = runInterruptible(dispatcher) { startProcess(command).also(process::attach) }
            runInterruptible(dispatcher) { native.outputStream.close() }
            val reader = async { runInterruptible(dispatcher) { pcmFrames(native.inputStream, maximumMillis * 192) } }
            val errors = async { runInterruptible(dispatcher) {
                native.errorStream.use { input -> val buffer = ByteArray(4_096); while (input.read(buffer) >= 0) Unit }
            } }
            val exit = async { runInterruptible(dispatcher) { native.waitFor() } }
            val monitor = launch { while (true) { if (!current(active)) throw InactiveUpload(); delay(10) } }
            try {
                val result = reader.await()
                check(exit.await() == 0)
                errors.await()
                if (!current(active)) throw InactiveUpload()
                result
            } finally { monitor.cancel() }
        } finally { process.close(); cleanup.cancel() }
    }

    private data class Staged(val path: Path, val size: Long, val sha256: String, val format: String, val lease: AttachmentLease)
    private class InactiveUpload : RuntimeException()
    private companion object {
        const val SAMPLE_RATE = 48_000L
        const val MAX_BYTES = 16L * 1_024 * 1_024
        const val MAX_MILLIS = 30_000L
        val RESERVED_NAMES = setOf("CON", "PRN", "AUX", "NUL") + (1..9).flatMap { listOf("COM$it", "LPT$it") }
        val slots = Semaphore(2)
        fun current(active: () -> Boolean) = runCatching(active).getOrDefault(false)
        fun mp3Header(bytes: ByteArray) = bytes.size >= 4 &&
            ((bytes[0] == 73.toByte() && bytes[1] == 68.toByte() && bytes[2] == 51.toByte() && bytes[3].toInt() in 2..4) ||
                ((bytes[0].toInt() and 255) == 255 && (bytes[1].toInt() and 0xE0) == 0xE0 &&
                    (bytes[1].toInt() and 0x06) == 0x02 && (bytes[1].toInt() and 0x18) != 0x08))
        fun pcmFrames(input: InputStream, maximumBytes: Long): Long = input.use {
            val buffer = ByteArray(8_192)
            var size = 0L
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                require(count.toLong() <= maximumBytes - size)
                size += count
            }
            check(size > 0 && size % 4 == 0L)
            size / 4
        }
    }
}

private val notificationUploadPreparation = NotificationSoundUploadPreparation()
internal suspend fun prepareNotificationSoundUpload(path: String, limits: NotificationSoundLimits, active: () -> Boolean) =
    notificationUploadPreparation.prepare(path, limits, active)

/** Blocking worker-only validation of the trusted internal preparation descriptor, not an external duration attestation. */
internal fun validatePreparedNotificationSoundUpload(upload: PreparedNotificationSoundUpload): Boolean = runCatching {
    require(upload.sizeBytes in 1..16L * 1_024 * 1_024 && upload.durationMillis in 1..30_000L && upload.sha256.matches(Regex("[0-9a-fA-F]{64}")))
    val path = localSoundPath(upload.path)
    val before = regularAttributes(path)
    require(before.size() == upload.sizeBytes)
    val digest = MessageDigest.getInstance("SHA-256")
    var size = 0L
    FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
        java.nio.channels.Channels.newInputStream(channel).use { input ->
            val buffer = ByteArray(8_192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(count.toLong() <= upload.sizeBytes - size)
                digest.update(buffer, 0, count); size += count
            }
        }
    }
    require(size == upload.sizeBytes && sameFile(before, regularAttributes(path)))
    digest.digest().hex().equals(upload.sha256, ignoreCase = true)
}.getOrDefault(false)

private fun localSoundPath(value: String): Path {
    val path = Path.of(value)
    require(path.isAbsolute && !value.startsWith("\\\\") && path.toUri().authority.isNullOrBlank())
    return path.toAbsolutePath().normalize()
}
private fun regularAttributes(path: Path) = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).also {
    require(it.isRegularFile && !it.isSymbolicLink)
}
private fun sameFile(first: BasicFileAttributes, second: BasicFileAttributes) = first.size() == second.size() &&
    first.fileKey() == second.fileKey() && first.lastModifiedTime() == second.lastModifiedTime()
private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
private fun ByteArray.hex() = joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

private class UploadProcessOwner {
    private val closed = AtomicBoolean()
    private val process = AtomicReference<Process?>()
    fun attach(value: Process) { process.set(value); if (closed.get()) close() }
    fun close() {
        closed.set(true)
        val current = process.getAndSet(null) ?: return
        runCatching { current.destroyForcibly() }
        runCatching { current.outputStream.close() }
        runCatching { current.inputStream.close() }
        runCatching { current.errorStream.close() }
        // The owned seekable file must not be deleted while a Windows decoder still has it open.
        runCatching { current.waitFor(2, TimeUnit.SECONDS) }
    }
}
