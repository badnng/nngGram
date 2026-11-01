package com.badnng.moe.media

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

class VoiceRecordingSessionTest {
    @Test
    fun aDeviceThatStopsProducingPcmStillHitsTheWallClockLimitAndCloses() = runBlocking {
        val directory = Files.createTempDirectory("voice-no-data-test-")
        val closed = AtomicBoolean(false)
        val time = AtomicLong()
        val session = VoiceRecordingSession({ object : VoicePcmInput {
            override fun read(destination: ByteArray) = 0
            override fun stop() = Unit
            override fun close() { closed.set(true) }
        } }, { _, _ -> error("No PCM can encode") }, directory, nowNanos = { time.getAndAdd(60_000_000_000L) })
        withTimeout(5_000) { session.state.first { it.stage == VoiceRecordingStage.FAILED } }
        assertTrue(closed.get())
        session.close()
        withTimeout(5_000) { while (Files.exists(directory)) kotlinx.coroutines.delay(5) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun closingBeforeTheCaptureCoroutineStartsStillReleasesBothOwners() = runTest {
        val directory = Files.createTempDirectory("voice-before-start-test-")
        var opened = false
        val session = VoiceRecordingSession({ opened = true; error("Capture must remain unopened") }, { _, _ -> }, directory,
            StandardTestDispatcher(testScheduler))
        session.close()
        runCurrent()
        assertFalse(opened)
        assertFalse(Files.exists(directory))
    }

    @Test
    fun cancellingAnEncoderWaitsForItsOutputHandleToCloseBeforeFinalCleanup() = runBlocking {
        val directory = Files.createTempDirectory("voice-encoding-cancel-test-")
        val input = FakeInput()
        val entered = CountDownLatch(1)
        val outputClosed = AtomicBoolean(false)
        val session = VoiceRecordingSession({ input }, { _, ogg ->
            try {
                Files.newOutputStream(ogg).use { stream ->
                    stream.write(byteArrayOf(79, 103, 103, 83))
                    entered.countDown()
                    CountDownLatch(1).await()
                }
            } finally { outputClosed.set(true) }
        }, directory)
        assertTrue(input.captured.await(3, TimeUnit.SECONDS))
        session.stop()
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        session.close()
        withTimeout(5_000) { while (Files.exists(directory)) kotlinx.coroutines.delay(5) }
        assertTrue(outputClosed.get())
    }

    @Test
    fun stoppingBuildsARealPcmWaveformAndTheSendLeaseOutlivesTheDialog() = runBlocking {
        val directory = Files.createTempDirectory("voice-session-test-")
        val input = FakeInput()
        val session = VoiceRecordingSession({ input }, { pcm, ogg ->
            assertEquals(19_200L, Files.size(pcm))
            Files.write(ogg, byteArrayOf(79, 103, 103, 83, 1, 2, 3, 4))
        }, directory)
        assertTrue(input.captured.await(3, TimeUnit.SECONDS))
        session.stop()
        val ready = withTimeout(5_000) { session.state.first { it.stage == VoiceRecordingStage.READY } }
        val draft = assertNotNull(ready.draft)
        assertEquals(1, draft.durationSeconds)
        assertEquals(63, draft.waveform.size)
        assertTrue(draft.waveform.any { it.toInt() != 0 })
        assertTrue(input.closed.get())
        assertFalse(Files.exists(directory.resolve("voice.pcm")))
        val engineHandle = assertNotNull(draft.lease.retain())
        session.close()
        assertTrue(Files.exists(Path.of(draft.path)), "Closing the recording UI cannot remove a retained upload.")
        engineHandle.release()
        withTimeout(5_000) { while (Files.exists(directory)) kotlinx.coroutines.delay(5) }
    }

    @Test
    fun cancellationClosesTheInputBeforeRemovingTemporaryFiles() = runBlocking {
        val directory = Files.createTempDirectory("voice-cancel-test-")
        val input = FakeInput()
        val session = VoiceRecordingSession({ input }, { _, _ -> error("Cancelled recordings cannot encode") }, directory)
        assertTrue(input.captured.await(3, TimeUnit.SECONDS))
        session.close()
        withTimeout(5_000) {
            while (!input.closed.get() || Files.exists(directory)) kotlinx.coroutines.delay(5)
        }
    }

    @Test
    fun waveformPackingUsesActualPeaksAndKeepsAllOneHundredFiveBitValues() {
        assertEquals(32768, pcmPeak(byteArrayOf(0, 0x80.toByte(), 0xff.toByte(), 0x7f), 4))
        assertTrue(packVoiceWaveform(List(100) { 0 }).all { it.toInt() == 0 })
        val wave = packVoiceWaveform(List(100) { if (it % 2 == 0) 0 else 32_768 })
        val unpacked = (0 until 100).map { index ->
            val bit = index * 5
            val next = wave.getOrNull(bit / 8 + 1)?.toInt()?.and(255) ?: 0
            (((wave[bit / 8].toInt() and 255) or (next shl 8)) ushr (bit % 8)) and 31
        }
        assertEquals(List(100) { if (it % 2 == 0) 0 else 31 }, unpacked)
    }

    private class FakeInput : VoicePcmInput {
        val captured = CountDownLatch(1)
        val closed = AtomicBoolean(false)
        private var blocks = 0
        override fun read(destination: ByteArray): Int {
            if (blocks == 10) { captured.countDown(); return 0 }
            blocks++
            for (offset in destination.indices step 2) {
                destination[offset] = 0
                destination[offset + 1] = blocks.toByte()
            }
            return destination.size
        }
        override fun stop() = Unit
        override fun close() { closed.set(true) }
    }
}
