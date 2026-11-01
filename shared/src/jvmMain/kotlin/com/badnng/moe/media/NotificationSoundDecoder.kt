package com.badnng.moe.media

import com.badnng.moe.notifications.NotificationSoundPcm
import com.badnng.moe.settings.SettingsError
import com.badnng.moe.settings.SettingsResult
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Independent rendering boundary. It never opens an audio device, URL, source file or temporary file. */
internal class NotificationSoundDecoder(
    private val findFfmpeg: () -> Path? = MediaDecoder::findFfmpeg,
    private val startProcess: (List<String>) -> Process = { ProcessBuilder(it).start() },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val timeoutMillis: Long = 10_000,
) {
    init { require(timeoutMillis in 1..10_000) }

    suspend fun decode(encoded: ByteArray, maxDurationMillis: Long, active: () -> Boolean): SettingsResult<NotificationSoundPcm> =
        withContext(dispatcher) {
            if (encoded.isEmpty() || encoded.size > MAX_ENCODED_BYTES || maxDurationMillis <= 0)
                return@withContext SettingsResult(error = SettingsError.INVALID_VALUE)
            if (!current(active)) return@withContext SettingsResult(error = SettingsError.NOT_READY)
            slots.withPermit {
                try {
                    ensureActive()
                    if (!current(active)) return@withPermit SettingsResult(error = SettingsError.NOT_READY)
                    val executable = findFfmpeg() ?: return@withPermit SettingsResult(error = SettingsError.UNSUPPORTED)
                    val source = encoded.copyOf()
                    val duration = minOf(maxDurationMillis, NotificationSoundPcm.MAX_DURATION_MILLIS)
                    val budget = (duration * NotificationSoundPcm.BYTES_PER_MILLISECOND).toInt()
                    val samples = render(executable, source, budget, active)
                    if (!current(active)) SettingsResult(error = SettingsError.NOT_READY)
                    else SettingsResult(NotificationSoundPcm(samples))
                } catch (error: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    if (error is TimeoutCancellationException) SettingsResult(error = SettingsError.REQUEST_FAILED) else throw error
                } catch (_: InactiveSound) {
                    SettingsResult(error = SettingsError.NOT_READY)
                } catch (_: ExcessivePcm) {
                    SettingsResult(error = SettingsError.INVALID_VALUE)
                } catch (_: Exception) {
                    SettingsResult(error = if (current(active)) SettingsError.REQUEST_FAILED else SettingsError.NOT_READY)
                }
            }
        }

    private suspend fun render(executable: Path, source: ByteArray, budget: Int, active: () -> Boolean): ByteArray {
        val handle = ProcessOwner()
        try {
            return withTimeout(timeoutMillis) {
                coroutineScope {
                    // A child canceled alongside blocked IO closes the process before the scope waits for readers.
                    val cleanup = launch(start = CoroutineStart.UNDISPATCHED) {
                        try { awaitCancellation() } finally { handle.close() }
                    }
                    try {
                        val command = listOf(executable.toString(), "-nostdin", "-hide_banner", "-loglevel", "error",
                            "-protocol_whitelist", "pipe", "-i", "pipe:0", "-map", "0:a:0", "-vn", "-sn", "-dn",
                            "-acodec", "pcm_s16le", "-ar", "48000", "-ac", "2", "-f", "s16le", "pipe:1")
                        val process = runInterruptible(dispatcher) { startProcess(command).also(handle::attach) }
                        ensureActive()
                        if (!current(active)) throw InactiveSound()
                        val writer = async { runInterruptible(dispatcher) {
                            process.outputStream.use { output ->
                                var offset = 0
                                while (offset < source.size) {
                                    val count = minOf(8_192, source.size - offset)
                                    output.write(source, offset, count); offset += count
                                }
                            }
                        } }
                        val errors = async { runInterruptible(dispatcher) { drainErrors(process.errorStream) } }
                        val reader = async { runInterruptible(dispatcher) { readPcm(process.inputStream, budget) } }
                        val exit = async { runInterruptible(dispatcher) { process.waitFor() } }
                        val monitor = launch {
                            while (true) {
                                if (!current(active)) throw InactiveSound()
                                delay(10)
                            }
                        }
                        try {
                            val result = reader.await()
                            writer.await()
                            check(exit.await() == 0)
                            errors.await()
                            check(result.isNotEmpty() && result.size % NotificationSoundPcm.FRAME_BYTES == 0)
                            result
                        } finally { monitor.cancel() }
                    } finally { handle.close(); cleanup.cancel() }
                }
            }
        } finally { handle.close() }
    }

    private fun readPcm(input: InputStream, budget: Int): ByteArray = input.use {
        val result = ByteArrayOutputStream(minOf(budget, 65_536))
        val buffer = ByteArray(8_192)
        while (true) {
            val count = it.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            if (count > budget - result.size()) throw ExcessivePcm()
            result.write(buffer, 0, count)
        }
        result.toByteArray()
    }

    /** Drain continuously with a fixed buffer; retain and expose no stderr bytes or paths. */
    private fun drainErrors(input: InputStream) = input.use {
        val buffer = ByteArray(4_096)
        var boundedCount = 0
        while (true) {
            val count = it.read(buffer)
            if (count < 0) break
            boundedCount = minOf(16_384, boundedCount + count)
        }
    }

    private class ProcessOwner {
        private val closed = AtomicBoolean()
        private val process = AtomicReference<Process?>()
        fun attach(value: Process) {
            process.set(value)
            if (closed.get()) close()
        }
        fun close() {
            closed.set(true)
            val current = process.getAndSet(null) ?: return
            runCatching { current.destroyForcibly() }
            runCatching { current.outputStream.close() }
            runCatching { current.inputStream.close() }
            runCatching { current.errorStream.close() }
        }
    }

    private class InactiveSound : RuntimeException()
    private class ExcessivePcm : RuntimeException()
    private companion object {
        const val MAX_ENCODED_BYTES = 16 * 1_024 * 1_024
        val slots = Semaphore(2)
        fun current(active: () -> Boolean) = runCatching(active).getOrDefault(false)
    }
}
