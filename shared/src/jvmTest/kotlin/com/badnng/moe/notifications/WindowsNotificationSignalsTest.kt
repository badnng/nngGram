package com.badnng.moe.notifications

import com.badnng.moe.media.VideoAudioOutput
import com.badnng.moe.media.WaveOutDriver
import com.badnng.moe.media.WinMmAudioOutput
import com.sun.jna.Memory
import com.sun.jna.Pointer
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsNotificationSignalsTest {
    @Test
    fun unsupportedPreparedOutputRejectsWithoutInvokingItsCompletion() {
        val signals = object : DeviceNotificationSignals {
            override fun play(volumePercent: Int) = Unit
            override fun flash() = Unit
            override fun stop() = Unit
            override fun close() = Unit
        }
        assertFalse(signals.playPrepared(preparedPcm(), 50, { true }) { error("Rejected request completed") })
    }

    @Test
    fun preparedPcmKeepsEveryStereoSampleScalesVolumeAndUsesTenMillisecondChunks() {
        val pcm = preparedPcm(5_764)
        val output = PreparedAudioOutput()
        val results = CopyOnWriteArrayList<Boolean>()
        val completed = CountDownLatch(1)
        val signals = WindowsNotificationSignals(
            hwnd = { 0L }, audioFactory = { output }, flashDriver = { error("No window expected") },
            pcmFactory = { error("Prepared sound must not use the default recording") },
        )
        try {
            assertTrue(signals.playPrepared(pcm, 37, { true }) { results += it; completed.countDown() })
            completed.awaitOrFail()
            assertEquals(listOf(true), results)
            assertContentEquals(pcm.samples(37), output.samples())
            assertEquals(listOf(1_920, 1_920, 1_920, 4), output.chunks.map { it.size })
            assertEquals(1, output.drains.get())
            assertEquals(1, output.closes.get())
        } finally {
            signals.close()
        }
    }

    @Test
    fun silentInactiveAndClosedPreparedRequestsRejectWithoutOpeningOrCompleting() {
        val opens = AtomicInteger()
        val callbacks = AtomicInteger()
        val signals = preparedSignals { opens.incrementAndGet(); PreparedAudioOutput() }
        assertFalse(signals.playPrepared(preparedPcm(), 0, { true }) { callbacks.incrementAndGet() })
        assertFalse(signals.playPrepared(preparedPcm(), 100, { false }) { callbacks.incrementAndGet() })
        assertFalse(signals.playPrepared(preparedPcm(), 100, { error("Invalid lease") }) { callbacks.incrementAndGet() })
        signals.close()
        assertFalse(signals.playPrepared(preparedPcm(), 100, { true }) { callbacks.incrementAndGet() })
        assertEquals(0, opens.get())
        assertEquals(0, callbacks.get())
    }

    @Test
    fun preparedAndDefaultUseOneWorkerAndReplaceOnlyTheSinglePendingRequest() {
        val first = FakeAudioOutput(blockWrite = true)
        val next = PreparedAudioOutput()
        val opens = AtomicInteger()
        val replacedResults = CopyOnWriteArrayList<Boolean>()
        val winningResults = CopyOnWriteArrayList<Boolean>()
        val completed = CountDownLatch(1)
        val pcm = preparedPcm()
        val signals = preparedSignals { if (opens.incrementAndGet() == 1) first else next }
        try {
            signals.play(100)
            first.written.awaitOrFail()
            assertTrue(signals.playPrepared(pcm, 90, { true }) { replacedResults += it })
            signals.play(25)
            assertEquals(listOf(false), replacedResults)
            repeat(100) { signals.play(15) }
            assertTrue(signals.playPrepared(pcm, 43, { true }) { winningResults += it; completed.countDown() })
            first.releaseWrite.countDown()
            completed.awaitOrFail()
            assertEquals(listOf(true), winningResults)
            assertEquals(2, opens.get())
            assertContentEquals(pcm.samples(43), next.samples())
            assertEquals(first.threadNames.single(), next.threadNames.distinct().single())
        } finally {
            signals.close()
        }
    }

    @Test
    fun leaseRevokedDuringSlowPreparedOpenClosesWithoutStartWriteOrDrain() {
        val opening = CountDownLatch(1)
        val releaseOpen = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val active = AtomicBoolean(true)
        val output = PreparedAudioOutput()
        val results = CopyOnWriteArrayList<Boolean>()
        val signals = preparedSignals {
            opening.countDown()
            releaseOpen.awaitOrFail()
            output
        }
        try {
            assertTrue(signals.playPrepared(preparedPcm(), 100, active::get) { results += it; completed.countDown() })
            opening.awaitOrFail()
            active.set(false)
            releaseOpen.countDown()
            completed.awaitOrFail()
            assertEquals(listOf(false), results)
            assertEquals(0, output.starts.get())
            assertTrue(output.chunks.isEmpty())
            assertEquals(0, output.drains.get())
            assertEquals(1, output.closes.get())
        } finally {
            releaseOpen.countDown()
            signals.close()
        }
    }

    @Test
    fun preparedLeaseRevocationAtChunkBoundaryStopsTheNextWriteAndDoesNotDrain() {
        val active = AtomicBoolean(true)
        val output = PreparedAudioOutput(afterWrite = { active.set(false) })
        val completed = CountDownLatch(1)
        val results = CopyOnWriteArrayList<Boolean>()
        val signals = preparedSignals { output }
        try {
            signals.playPrepared(preparedPcm(), 100, active::get) { results += it; completed.countDown() }
            completed.awaitOrFail()
            assertEquals(listOf(false), results)
            assertEquals(listOf(1_920), output.chunks.map { it.size })
            assertEquals(0, output.drains.get())
            assertEquals(1, output.closes.get())
        } finally {
            signals.close()
        }
    }

    @Test
    fun inactivePreparedPendingRequestIsConsumedWithoutOpeningAnotherDevice() {
        val first = FakeAudioOutput(blockWrite = true)
        val opens = AtomicInteger()
        val active = AtomicBoolean(true)
        val completed = CountDownLatch(1)
        val results = CopyOnWriteArrayList<Boolean>()
        val signals = preparedSignals { opens.incrementAndGet(); first }
        try {
            signals.play(100)
            first.written.awaitOrFail()
            signals.playPrepared(preparedPcm(), 100, active::get) { results += it; completed.countDown() }
            active.set(false)
            first.releaseWrite.countDown()
            completed.awaitOrFail()
            assertEquals(listOf(false), results)
            assertEquals(1, opens.get())
        } finally {
            signals.close()
        }
    }

    @Test
    fun stopThenCloseCompletesActiveAndPendingPreparedExactlyOnceAndClosesOutput() {
        val output = PreparedAudioOutput(blockWrite = true)
        val activeResults = CopyOnWriteArrayList<Boolean>()
        val pendingResults = CopyOnWriteArrayList<Boolean>()
        val signals = preparedSignals { output }
        try {
            signals.playPrepared(preparedPcm(), 100, { true }) { activeResults += it }
            output.written.awaitOrFail()
            signals.playPrepared(preparedPcm(), 100, { true }) { pendingResults += it }
            signals.stop()
            signals.stop()
            signals.close()
            signals.close()
            assertEquals(listOf(false), activeResults)
            assertEquals(listOf(false), pendingResults)
            assertEquals(1, output.closes.get())
            assertEquals(1, output.chunks.size)
            assertEquals(0, output.drains.get())
        } finally {
            signals.close()
        }
    }

    @Test
    fun stopDuringPreparedOpenFinishesFalseImmediatelyAndLateOutputCannotWrite() {
        val opening = CountDownLatch(1)
        val releaseOpen = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val results = CopyOnWriteArrayList<Boolean>()
        val output = PreparedAudioOutput()
        val signals = preparedSignals {
            opening.countDown()
            releaseOpen.awaitOrFail()
            output
        }
        try {
            signals.playPrepared(preparedPcm(), 100, { true }) { results += it; completed.countDown() }
            opening.awaitOrFail()
            signals.stop()
            completed.awaitOrFail()
            assertEquals(listOf(false), results)
            releaseOpen.countDown()
            output.closed.awaitOrFail()
            signals.close()
            assertEquals(listOf(false), results)
            assertTrue(output.chunks.isEmpty())
            assertEquals(1, output.closes.get())
        } finally {
            releaseOpen.countDown()
            signals.close()
        }
    }

    @Test
    fun preparedSuccessWaitsForCompleteDrainAndCloseCanRevokeItWhileDraining() {
        val draining = CountDownLatch(1)
        val releaseDrain = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val results = CopyOnWriteArrayList<Boolean>()
        val output = PreparedAudioOutput(beforeDrain = { draining.countDown(); releaseDrain.awaitOrFail() })
        val signals = preparedSignals { output }
        try {
            signals.playPrepared(preparedPcm(), 100, { true }) { results += it; completed.countDown() }
            draining.awaitOrFail()
            assertTrue(results.isEmpty())
            signals.close()
            completed.awaitOrFail()
            assertEquals(listOf(false), results)
            releaseDrain.countDown()
            assertEquals(1, output.closes.get())
        } finally {
            releaseDrain.countDown()
            signals.close()
        }
    }

    @Test
    fun preparedCompletionRemainsPendingUntilTheWholeSoundFinishesDraining() {
        val draining = CountDownLatch(1)
        val releaseDrain = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val results = CopyOnWriteArrayList<Boolean>()
        val output = PreparedAudioOutput(beforeDrain = { draining.countDown(); releaseDrain.awaitOrFail() })
        val signals = preparedSignals { output }
        try {
            val pcm = preparedPcm()
            signals.playPrepared(pcm, 200, { true }) { results += it; completed.countDown() }
            draining.awaitOrFail()
            assertContentEquals(pcm.samples(100), output.samples())
            assertTrue(results.isEmpty())
            releaseDrain.countDown()
            completed.awaitOrFail()
            assertEquals(listOf(true), results)
            assertEquals(1, output.closes.get())
        } finally {
            releaseDrain.countDown()
            signals.close()
        }
    }

    @Test
    fun preparedFailuresReportOnlyCategoryFinishFalseAndKeepWorkerAliveDespiteThrowingCallbacks() {
        for (stage in listOf("open", "start", "write", "drain", "close")) {
            val failedOutput = PreparedAudioOutput(failAt = stage)
            val recoveredOutput = PreparedAudioOutput()
            val opens = AtomicInteger()
            val failures = AtomicInteger()
            val failed = CountDownLatch(1)
            val recovered = CountDownLatch(1)
            val results = CopyOnWriteArrayList<Boolean>()
            val signals = preparedSignals {
                if (opens.incrementAndGet() == 1) {
                    if (stage == "open") error("Private device details")
                    failedOutput
                } else recoveredOutput
            }
            signals.setFailureListener { failures.incrementAndGet(); error("Consumer failure") }
            try {
                signals.playPrepared(preparedPcm(), 100, { true }) {
                    results += it
                    failed.countDown()
                    error("Completion failure")
                }
                failed.awaitOrFail()
                assertEquals(listOf(false), results, stage)
                assertEquals(1, failures.get(), stage)
                signals.playPrepared(preparedPcm(), 100, { true }) { results += it; recovered.countDown() }
                recovered.awaitOrFail()
                assertEquals(listOf(false, true), results, stage)
                assertEquals(2, opens.get(), stage)
                if (stage != "open") assertEquals(1, failedOutput.closes.get(), stage)
            } finally {
                signals.close()
            }
        }
    }

    @Test
    fun preparedReplacementCompletionAndFailureListenerRunOutsideTheCommandLock() {
        val first = FakeAudioOutput(blockWrite = true)
        val callbackUnlocked = AtomicBoolean()
        val listenerUnlocked = AtomicBoolean()
        val signals = preparedSignals { first }
        fun anotherThreadCanEnter(): Boolean {
            val entered = CountDownLatch(1)
            Thread { signals.play(0); signals.setFailureListener(null); entered.countDown() }.start()
            return entered.await(2, TimeUnit.SECONDS)
        }
        try {
            signals.play(100)
            first.written.awaitOrFail()
            signals.playPrepared(preparedPcm(), 100, { true }) { callbackUnlocked.set(anotherThreadCanEnter()) }
            signals.play(50)
            assertTrue(callbackUnlocked.get())
            val failed = CountDownLatch(1)
            val broken = preparedSignals { error("Private device details") }
            broken.setFailureListener {
                val entered = CountDownLatch(1)
                Thread { broken.setFailureListener(null); entered.countDown() }.start()
                listenerUnlocked.set(entered.await(2, TimeUnit.SECONDS))
            }
            try {
                broken.playPrepared(preparedPcm(), 100, { true }) { failed.countDown() }
                failed.awaitOrFail()
                assertTrue(listenerUnlocked.get())
            } finally {
                broken.close()
            }
        } finally {
            signals.close()
        }
    }

    @Test
    fun officialDefaultRecordingKeepsItsWholeStereoDurationAndScalesEverySample() {
        val full = deviceDefaultNotificationPcm(100)
        val half = deviceDefaultNotificationPcm(50)
        assertEquals(65_603 * 4, full.size)
        assertContentEquals(full, deviceDefaultNotificationPcm(200))
        assertTrue(deviceDefaultNotificationPcm(-1).all { it == 0.toByte() })
        assertTrue(full.any { it != 0.toByte() })
        for (offset in full.indices step 2) {
            assertTrue(abs(sample(full, offset) - 2 * sample(half, offset)) <= 1)
        }
    }

    @Test
    fun flashUsesWin64LayoutAndStopsTheOriginalTaoWindowAfterHandleReplacement() {
        var hwnd = 0x1234_5678_1234L
        val calls = mutableListOf<FlashCall>()
        val signals = WindowsNotificationSignals(
            hwnd = { hwnd },
            audioFactory = { error("Flash must not open an audio device") },
            flashDriver = { info ->
                calls += FlashCall(
                    size = info.getInt(0),
                    hwnd = Pointer.nativeValue(info.getPointer(8)),
                    flags = info.getInt(16),
                    count = info.getInt(20),
                    timeout = info.getInt(24),
                )
                0 // Previous inactive state is normal, not a failure.
            },
        )
        signals.flash()
        hwnd = 0x5678L
        signals.flash()
        hwnd = 0L
        signals.stop()
        signals.close()
        signals.flash()
        assertEquals(listOf(
            FlashCall(32, 0x1234_5678_1234L, 15, 0, 0),
            FlashCall(32, 0x1234_5678_1234L, 0, 0, 0),
            FlashCall(32, 0x5678L, 15, 0, 0),
            FlashCall(32, 0x5678L, 0, 0, 0),
        ), calls)
    }

    @Test
    fun silentAndDisposedSignalsNeverOpenDevicesOrTouchAnAbsentWindow() {
        val audioOpens = AtomicInteger()
        val flashes = AtomicInteger()
        val signals = WindowsNotificationSignals(
            hwnd = { 0L },
            audioFactory = { audioOpens.incrementAndGet(); FakeAudioOutput() },
            flashDriver = { flashes.incrementAndGet() },
        )
        signals.play(0)
        signals.play(-10)
        signals.flash()
        signals.stop()
        signals.close()
        signals.play(100)
        signals.flash()
        signals.stop()
        signals.close()
        assertEquals(0, audioOpens.get())
        assertEquals(0, flashes.get())
    }

    @Test
    fun burstsKeepOnlyOnePendingSoundAndUseItsLatestVolume() {
        val first = FakeAudioOutput(blockWrite = true)
        val next = FakeAudioOutput()
        val opens = AtomicInteger()
        val signals = WindowsNotificationSignals(
            hwnd = { 0L },
            audioFactory = { if (opens.incrementAndGet() == 1) first else next },
            flashDriver = { 0 },
        )
        try {
            signals.play(100)
            first.written.awaitOrFail()
            repeat(1_000) { signals.play(it % 100 + 1) }
            signals.play(35)
            first.releaseWrite.countDown()
            next.closed.awaitOrFail()
            signals.close()
            assertEquals(2, opens.get())
            assertContentEquals(deviceDefaultNotificationPcm(35), next.samples)
        } finally {
            signals.close()
        }
    }

    @Test
    fun stopClosesTheActiveOutputAndDiscardsEveryCoalescedPendingSound() {
        val first = FakeAudioOutput(blockWrite = true)
        val next = FakeAudioOutput()
        val opens = AtomicInteger()
        val signals = WindowsNotificationSignals(
            hwnd = { 0L },
            audioFactory = { if (opens.incrementAndGet() == 1) first else next },
            flashDriver = { error("No window expected") },
        )
        try {
            signals.play(100)
            first.written.awaitOrFail()
            repeat(1_000) { signals.play(it % 100 + 1) }
            assertEquals(1, opens.get())
            signals.stop()
            first.closed.awaitOrFail()
            // The only post-cancellation output is an explicitly requested new sound.
            signals.play(25)
            next.closed.awaitOrFail()
            assertEquals(2, opens.get())
            assertContentEquals(deviceDefaultNotificationPcm(25), next.samples)
            assertEquals(first.threadNames.single(), next.threadNames.single())
        } finally {
            signals.close()
        }
    }

    @Test
    fun cancellationDuringDeviceOpenClosesItWithoutAnyAudioWrite() {
        val opening = CountDownLatch(1)
        val opened = CountDownLatch(1)
        val output = FakeAudioOutput()
        val signals = WindowsNotificationSignals(
            hwnd = { 0L },
            audioFactory = {
                opening.countDown()
                opened.awaitOrFail()
                output
            },
            flashDriver = { error("No window expected") },
        )
        try {
            signals.play(100)
            opening.awaitOrFail()
            signals.stop()
            opened.countDown()
            output.closed.awaitOrFail()
            assertTrue(output.samples.isEmpty())
            assertEquals(1L, output.written.count)
        } finally {
            opened.countDown()
            signals.close()
        }
    }

    @Test
    fun cancellationDuringRecordingPreparationSkipsTheDeviceAndOnlyANewRequestCanPlay() {
        val preparing = CountDownLatch(1)
        val releasePreparation = CountDownLatch(1)
        val preparations = AtomicInteger()
        val opens = AtomicInteger()
        val output = FakeAudioOutput()
        val signals = WindowsNotificationSignals(
            hwnd = { 0L },
            audioFactory = { opens.incrementAndGet(); output },
            flashDriver = { error("No window expected") },
            pcmFactory = { volume ->
                if (preparations.incrementAndGet() == 1) {
                    preparing.countDown()
                    releasePreparation.awaitOrFail()
                }
                deviceDefaultNotificationPcm(volume)
            },
        )
        try {
            signals.play(100)
            preparing.awaitOrFail()
            signals.stop()
            releasePreparation.countDown()
            signals.play(25)
            output.closed.awaitOrFail()
            assertEquals(2, preparations.get())
            assertEquals(1, opens.get())
            assertContentEquals(deviceDefaultNotificationPcm(25), output.samples)
        } finally {
            releasePreparation.countDown()
            signals.close()
        }
    }

    @Test
    fun aRecordingFailureIsReportedWithoutOpeningAudioAndAnExplicitNewRequestCanRecover() {
        val failed = CountDownLatch(1)
        val preparations = AtomicInteger()
        val opens = AtomicInteger()
        val failures = AtomicInteger()
        val output = FakeAudioOutput()
        val signals = WindowsNotificationSignals(
            hwnd = { 0L },
            audioFactory = { opens.incrementAndGet(); output },
            flashDriver = { error("No window expected") },
            pcmFactory = { volume ->
                if (preparations.incrementAndGet() == 1) error("Fake missing resource")
                deviceDefaultNotificationPcm(volume)
            },
        )
        signals.setFailureListener { failures.incrementAndGet(); failed.countDown() }
        try {
            signals.play(100)
            failed.awaitOrFail()
            assertEquals(1, preparations.get())
            assertEquals(0, opens.get())
            signals.play(60)
            output.closed.awaitOrFail()
            assertEquals(1, failures.get())
            assertEquals(1, opens.get())
            assertContentEquals(deviceDefaultNotificationPcm(60), output.samples)
        } finally {
            signals.close()
        }
    }

    @Test
    fun anUnavailableAudioDeviceDoesNotKillTheSharedWorker() {
        val firstAttempt = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val failures = AtomicInteger()
        val output = FakeAudioOutput()
        val opens = AtomicInteger()
        val signals = WindowsNotificationSignals(
            hwnd = { 0L },
            audioFactory = {
                if (opens.incrementAndGet() == 1) {
                    firstAttempt.countDown()
                    error("Fake disconnected device")
                }
                output
            },
            flashDriver = { 0 },
        )
        signals.setFailureListener {
            failures.incrementAndGet()
            failed.countDown()
        }
        try {
            signals.play(100)
            firstAttempt.awaitOrFail()
            failed.awaitOrFail()
            signals.play(60)
            output.closed.awaitOrFail()
            assertEquals(2, opens.get())
            assertEquals(1, failures.get())
            assertContentEquals(deviceDefaultNotificationPcm(60), output.samples)
        } finally {
            signals.close()
        }
    }

    @Test
    fun flashFailuresAreReportedAfterStopButTheListenerIsClearedOnClose() {
        val failures = AtomicInteger()
        val attempts = AtomicInteger()
        val signals = WindowsNotificationSignals(
            hwnd = { 123L },
            audioFactory = { error("No audio device expected") },
            flashDriver = {
                attempts.incrementAndGet()
                error("Fake User32 failure; must not escape or reach the consumer")
            },
        )
        signals.setFailureListener { failures.incrementAndGet() }
        signals.flash()
        signals.stop()
        signals.flash()
        assertEquals(3, failures.get())
        signals.setFailureListener(null)
        signals.flash()
        assertEquals(3, failures.get())
        // Callback failures are isolated too, and close removes it before stopping native flashing.
        signals.setFailureListener {
            failures.incrementAndGet()
            error("Fake queue failure")
        }
        signals.flash()
        assertEquals(4, failures.get())
        signals.close()
        signals.setFailureListener { failures.incrementAndGet() }
        signals.flash()
        signals.close()
        assertEquals(4, failures.get())
        assertEquals(6, attempts.get())
    }

    @Test
    fun deviceDefaultPcmReachesWinMmAndResetPrecedesNativeMemoryRelease() {
        val driver = FakeWaveOutDriver()
        val signals = WindowsNotificationSignals(
            hwnd = { 0L },
            audioFactory = { WinMmAudioOutput(driver) },
            flashDriver = { error("No window expected") },
        )
        try {
            signals.play(37)
            driver.closed.awaitOrFail()
            assertContentEquals(deviceDefaultNotificationPcm(37), driver.samples.toByteArray())
            assertEquals(listOf("waveOutReset") + List(4) { "waveOutUnprepareHeader" } + "waveOutClose",
                driver.operations.takeLast(6))
        } finally {
            signals.close()
        }
    }

    private data class FlashCall(val size: Int, val hwnd: Long, val flags: Int, val count: Int, val timeout: Int)

    private class PreparedAudioOutput(
        private val blockWrite: Boolean = false,
        private val afterWrite: () -> Unit = {},
        private val beforeDrain: () -> Unit = {},
        private val failAt: String? = null,
    ) : VideoAudioOutput {
        val chunks = CopyOnWriteArrayList<ByteArray>()
        val threadNames = CopyOnWriteArrayList<String>()
        val starts = AtomicInteger()
        val drains = AtomicInteger()
        val closes = AtomicInteger()
        val written = CountDownLatch(1)
        val closed = CountDownLatch(1)
        private val releaseWrite = CountDownLatch(1)
        override fun start() {
            starts.incrementAndGet()
            if (failAt == "start") error("Private start details")
        }
        override fun write(bytes: ByteArray, length: Int) {
            threadNames += Thread.currentThread().name
            chunks += bytes.copyOf(length)
            written.countDown()
            if (failAt == "write") error("Private write details")
            afterWrite()
            if (blockWrite) releaseWrite.awaitOrFail()
        }
        override fun drain() {
            drains.incrementAndGet()
            beforeDrain()
            if (failAt == "drain") error("Private drain details")
        }
        override fun close() {
            closes.incrementAndGet()
            releaseWrite.countDown()
            closed.countDown()
            if (failAt == "close") error("Private close details")
        }
        fun samples(): ByteArray = ByteArrayOutputStream().also { out -> chunks.forEach(out::write) }.toByteArray()
    }

    private class FakeAudioOutput(private val blockWrite: Boolean = false) : VideoAudioOutput {
        val written = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val threadNames = CopyOnWriteArrayList<String>()
        @Volatile var samples = byteArrayOf()
        override fun start() = Unit
        override fun write(bytes: ByteArray, length: Int) {
            threadNames += Thread.currentThread().name
            samples = bytes.copyOf(length)
            written.countDown()
            if (blockWrite) releaseWrite.awaitOrFail()
        }
        override fun drain() = Unit
        override fun close() {
            closed.countDown()
            releaseWrite.countDown()
        }
    }

    private class FakeWaveOutDriver : WaveOutDriver {
        val closed = CountDownLatch(1)
        val samples = ByteArrayOutputStream()
        val operations = CopyOnWriteArrayList<String>()
        override fun call(name: String, vararg arguments: Any?): Int {
            operations += name
            when (name) {
                "waveOutOpen" -> {
                    val format = arguments[2] as Memory
                    assertEquals(1, format.getShort(0).toInt())
                    assertEquals(2, format.getShort(2).toInt())
                    assertEquals(48_000, format.getInt(4))
                    assertEquals(192_000, format.getInt(8))
                    assertEquals(4, format.getShort(12).toInt())
                    assertEquals(16, format.getShort(14).toInt())
                    assertEquals(0, format.getShort(16).toInt())
                    assertEquals(0, arguments[5]) // CALLBACK_NULL, no retained native callbacks.
                    (arguments[0] as Memory).setPointer(0, Pointer(1))
                }
                "waveOutWrite" -> {
                    assertEquals(48, arguments[2])
                    val header = arguments[1] as Pointer
                    samples.write(header.getPointer(0).getByteArray(0, header.getInt(8)))
                    header.setInt(24, 1) // WHDR_DONE; no real audio device or wall-clock playback.
                }
                "waveOutClose" -> closed.countDown()
            }
            return 0
        }
    }

    private companion object {
        fun preparedSignals(audioFactory: () -> VideoAudioOutput) = WindowsNotificationSignals(
            hwnd = { 0L },
            audioFactory = audioFactory,
            flashDriver = { error("Prepared tests must not touch User32") },
        )
        fun preparedPcm(sizeBytes: Int = 5_764): NotificationSoundPcm =
            NotificationSoundPcm(ByteArray(sizeBytes) { index -> (index * 31 + 17).toByte() })
        fun CountDownLatch.awaitOrFail() = assertTrue(await(3, TimeUnit.SECONDS), "Worker did not complete")
        fun sample(bytes: ByteArray, offset: Int): Int =
            ((bytes[offset].toInt() and 255) or (bytes[offset + 1].toInt() shl 8)).toShort().toInt()
    }
}
