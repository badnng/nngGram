package com.badnng.moe.media

import com.badnng.moe.settings.SettingsError
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class NotificationSoundDecoderTest {
    @Test fun pipeInputIsOwnedAndCanonicalCommandCannotUseNetworkOrSourceFiles() = runBlocking {
        val encoded = byteArrayOf(1, 2, 3, 4)
        val process = FakeProcess(ByteArrayInputStream(ByteArray(192)), errors = ByteArrayInputStream(ByteArray(100_000)))
        var command = emptyList<String>()
        val caller = Thread.currentThread().id
        val decoder = NotificationSoundDecoder({ Path.of("synthetic-ffmpeg.exe") }, { arguments ->
            assertNotEquals(caller, Thread.currentThread().id)
            command = arguments
            encoded.fill(9)
            process
        })
        val result = decoder.decode(encoded, 1_000) { true }
        assertNull(result.error)
        assertEquals(1L, result.value?.durationMillis)
        assertContentEquals(byteArrayOf(1, 2, 3, 4), process.written.toByteArray())
        assertEquals("pipe", command[command.indexOf("-protocol_whitelist") + 1])
        assertEquals("pipe:0", command[command.indexOf("-i") + 1])
        assertEquals("pipe:1", command.last())
        assertFalse("-t" in command, "Overlong output must be rejected rather than truncated into a plausible ringtone.")
        assertTrue(process.destroyed.get())
    }

    @Test fun invalidInputsMissingToolsMalformedOutputAndNativeFailureAreExplicitAndRedacted() = runBlocking {
        var started = 0
        val decoder = NotificationSoundDecoder({ Path.of("synthetic.exe") }, { started++; FakeProcess(ByteArrayInputStream(ByteArray(4))) })
        assertEquals(SettingsError.INVALID_VALUE, decoder.decode(byteArrayOf(), 1_000) { true }.error)
        assertEquals(SettingsError.INVALID_VALUE, decoder.decode(ByteArray(16 * 1_024 * 1_024 + 1), 1_000) { true }.error)
        assertEquals(SettingsError.INVALID_VALUE, decoder.decode(byteArrayOf(1), 0) { true }.error)
        assertEquals(SettingsError.NOT_READY, decoder.decode(byteArrayOf(1), 1_000) { false }.error)
        assertEquals(0, started)
        assertEquals(SettingsError.UNSUPPORTED, NotificationSoundDecoder(findFfmpeg = { null }).decode(byteArrayOf(1), 1_000) { true }.error)
        for ((bytes, code) in listOf(ByteArray(0) to 0, ByteArray(3) to 0, ByteArray(192) to 1)) {
            val process = FakeProcess(ByteArrayInputStream(bytes), code)
            val result = NotificationSoundDecoder({ Path.of("synthetic.exe") }, { process }).decode(byteArrayOf(1), 1_000) { true }
            assertEquals(SettingsError.REQUEST_FAILED, result.error)
            assertTrue(process.destroyed.get())
        }
        val failed = NotificationSoundDecoder({ Path.of("synthetic.exe") }, { throw IOException("private source path and encoded content") })
            .decode(byteArrayOf(1), 1_000) { true }
        assertEquals(SettingsError.REQUEST_FAILED, failed.error)
        assertFalse(failed.toString().contains("private source"))
    }

    @Test fun oneFrameBeyondTheActualOutputDurationBudgetIsRejectedAndTheProcessIsStopped() = runBlocking {
        val process = FakeProcess(ByteArrayInputStream(ByteArray(196)))
        val result = NotificationSoundDecoder({ Path.of("synthetic.exe") }, { process }).decode(byteArrayOf(1), 1) { true }
        assertEquals(SettingsError.INVALID_VALUE, result.error)
        assertNull(result.value)
        assertTrue(process.destroyed.get())
    }

    @Test fun timeoutAndInactiveOwnershipKillBlockingReadersWithoutReturningPartialPcm() = runBlocking {
        for (inactive in listOf(false, true)) {
            val input = BlockingInput(ByteArray(192))
            val process = FakeProcess(input)
            val started = CompletableDeferred<Unit>()
            val active = AtomicBoolean(true)
            val decoder = NotificationSoundDecoder({ Path.of("synthetic.exe") }, { started.complete(Unit); process },
                timeoutMillis = if (inactive) 1_000 else 250)
            val result = async(Dispatchers.Default) { decoder.decode(byteArrayOf(1), 1_000, active::get) }
            withTimeout(2_000) { started.await() }
            if (inactive) active.set(false)
            val terminal = withTimeout(2_000) { result.await() }
            assertEquals(if (inactive) SettingsError.NOT_READY else SettingsError.REQUEST_FAILED, terminal.error)
            assertNull(terminal.value)
            assertTrue(input.closed.get())
            assertTrue(process.destroyed.get())
        }
    }

    @Test fun callerCancellationActivelyClosesReadersAndWriterEvenWhenTheyIgnoreThreadInterrupts() = runBlocking {
        val input = BlockingInput(ByteArray(192))
        val errors = BlockingInput(ByteArray(0))
        val output = BlockingOutput()
        val process = FakeProcess(input, errors = errors, output = output)
        val started = CompletableDeferred<Unit>()
        val decoder = NotificationSoundDecoder({ Path.of("synthetic.exe") }, { started.complete(Unit); process })
        val job = async(Dispatchers.Default) { decoder.decode(byteArrayOf(1), 1_000) { true } }
        withTimeout(2_000) { started.await() }
        withTimeout(2_000) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
        assertTrue(process.destroyed.get())
        assertTrue(input.closed.get())
        assertTrue(errors.closed.get())
        assertTrue(output.closed.get())
    }

    @Test fun concurrentCallersShareOnlyTwoRunningDecoderSlots() = runBlocking {
        val processes = Collections.synchronizedList(mutableListOf<Pair<FakeProcess, BlockingInput>>())
        val starts = AtomicInteger()
        val running = AtomicInteger()
        val maximum = AtomicInteger()
        val decoder = NotificationSoundDecoder({ Path.of("synthetic.exe") }, {
            val input = BlockingInput(ByteArray(192))
            val count = running.incrementAndGet()
            maximum.updateAndGet { maxOf(it, count) }
            FakeProcess(input, stopped = { running.decrementAndGet() }).also { process ->
                processes += process to input
                starts.incrementAndGet()
            }
        })
        val results = List(3) { async(Dispatchers.Default) { decoder.decode(byteArrayOf(1), 1_000) { true } } }
        withTimeout(2_000) { while (starts.get() < 2) delay(1) }
        assertEquals(2, starts.get())
        processes[0].second.release()
        withTimeout(2_000) { while (starts.get() < 3) delay(1) }
        synchronized(processes) { processes.forEach { it.second.release() } }
        results.awaitAll().forEach { assertNull(it.error) }
        assertEquals(2, maximum.get())
        assertEquals(0, running.get())
    }

    /** -PmediaSmoke supplies the existing bundled executable; otherwise JUnit records an explicit skip. */
    @Test fun originalOfficialMp3DecodesCompletelyAndAnInsufficientRenderBudgetIsRejectedSilently() = runBlocking {
        val tool = MediaDecoder.findFfmpeg()
        assumeTrue("Run with the prepared mediaSmoke decoder for the original recording fixture", tool != null)
        val encoded = checkNotNull(javaClass.getResourceAsStream("/telegram-sounds/msg_incoming.mp3")).use { it.readBytes() }
        val decoder = NotificationSoundDecoder(findFfmpeg = { tool })
        val result = decoder.decode(encoded, 2_000) { true }
        assertNull(result.error)
        val pcm = assertNotNull(result.value)
        assertTrue(pcm.sizeBytes in 262_412..280_000)
        assertTrue(pcm.durationMillis in 1_366L..1_459L)
        assertTrue(pcm.samples(100).any { it != 0.toByte() })
        assertEquals(SettingsError.INVALID_VALUE, decoder.decode(encoded, 1_000) { true }.error)
    }

    private class FakeProcess(
        private val input: InputStream,
        private val code: Int = 0,
        private val errors: InputStream = ByteArrayInputStream(ByteArray(0)),
        private val output: OutputStream? = null,
        private val stopped: () -> Unit = {},
    ) : Process() {
        val written = ByteArrayOutputStream()
        val destroyed = AtomicBoolean()
        override fun getInputStream() = input
        override fun getErrorStream() = errors
        override fun getOutputStream(): OutputStream = output ?: written
        override fun waitFor() = code
        override fun exitValue() = code
        override fun destroy() {
            if (!destroyed.compareAndSet(false, true)) return
            runCatching { input.close() }; runCatching { errors.close() }; runCatching { output?.close() }
            stopped()
        }
        override fun destroyForcibly(): Process { destroy(); return this }
    }

    /** Deliberately ignores interruption: only explicit process/stream retirement can unblock it. */
    private class BlockingInput(bytes: ByteArray) : InputStream() {
        private val data = ByteArrayInputStream(bytes)
        private val ready = CountDownLatch(1)
        val closed = AtomicBoolean()
        fun release() = ready.countDown()
        private fun awaitReady() { while (ready.count > 0) try { ready.await() } catch (_: InterruptedException) {} }
        override fun read(): Int { awaitReady(); return if (closed.get()) -1 else data.read() }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int { awaitReady(); return if (closed.get()) -1 else data.read(bytes, offset, length) }
        override fun close() { closed.set(true); ready.countDown() }
    }

    private class BlockingOutput : OutputStream() {
        val closed = AtomicBoolean()
        private val done = CountDownLatch(1)
        override fun write(value: Int) { while (!closed.get()) try { done.await() } catch (_: InterruptedException) {}; throw IOException("retired") }
        override fun close() { closed.set(true); done.countDown() }
    }
}
