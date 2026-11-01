package com.badnng.moe.notifications

import com.badnng.moe.settings.SettingsError
import com.badnng.moe.settings.SettingsResult
import com.badnng.moe.tdlib.NotificationSoundFileLease
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.test.*

class NotificationSoundPreviewPlayerTest {
    @Test fun admissionWaitsForActualOutputAndReturnsOnlyTheTypedDefaultFallback() {
        Fixture().use { f ->
            val results = f.play()
            assertTrue(results.isEmpty())
            f.source.reply(fallback = true)
            assertEquals(1, f.signals.requests.size)
            assertTrue(results.isEmpty(), "Queue admission is not a played result.")
            f.signals.complete(true)
            assertEquals(true, results.single().value)
            assertNull(results.single().error)
            f.signals.complete(false)
            f.source.reply(fallback = false)
            assertEquals(1, results.size)
            assertEquals(1, f.source.releases)
            assertEquals(0, f.source.closed)
        }
    }

    @Test fun actualOutputFailureAndRefusedAdmissionNeverClaimPlayback() {
        Fixture().use { f ->
            val failed = f.play()
            f.source.reply()
            f.signals.complete(false)
            assertEquals(SettingsError.REQUEST_FAILED, failed.single().error)
            f.signals.admit = false
            val refused = f.play()
            f.source.reply()
            assertEquals(SettingsError.REQUEST_FAILED, refused.single().error)
            assertNull(refused.single().value)
        }
    }

    @Test fun synchronousSourceAndSynchronousCompletionAttachAfterFinishSafely() {
        Fixture().use { f ->
            f.source.immediate = true
            f.signals.immediate = true
            val results = f.play()
            assertEquals(false, results.single().value)
            assertEquals(1, f.source.releases)
        }
    }

    @Test fun cancelBeforeDownloadAndLateSourceCompletionCannotOpenAudio() {
        Fixture().use { f ->
            val results = mutableListOf<SettingsResult<Boolean>>()
            val lease = f.player.play(7, -22, 80, { true }, results::add)
            lease.close()
            f.source.reply()
            assertEquals(SettingsError.NOT_READY, results.single().error)
            assertEquals(0, f.signals.requests.size)
            assertEquals(1, f.source.releases)
        }
    }

    @Test fun replacementStopsOnlyItsPreviewAndOldCallbacksCannotStopNewPlayback() {
        Fixture().use { f ->
            val first = f.play()
            f.source.reply()
            val firstOutput = f.signals.requests.single()
            val second = f.play()
            assertEquals(SettingsError.NOT_READY, first.single().error)
            assertEquals(1, f.signals.stops)
            f.source.reply()
            assertEquals(2, f.signals.requests.size)
            firstOutput.completed(false)
            assertTrue(second.isEmpty())
            assertEquals(1, f.signals.stops)
            f.signals.complete(true)
            assertEquals(false, second.single().value)
        }
    }

    @Test fun closeStopsOwnOutputReleasesPendingOwnerAndNeverClosesTheSharedSource() {
        Fixture().use { f ->
            val results = f.play()
            f.source.reply()
            f.player.close()
            assertEquals(SettingsError.NOT_READY, results.single().error)
            assertEquals(1, f.signals.closes)
            assertEquals(1, f.source.releases)
            assertEquals(0, f.source.closed)
            f.signals.complete(true)
            assertEquals(1, results.size)
            assertEquals(SettingsError.NOT_READY, f.play().single().error)
            assertEquals(1, f.source.loads)
        }
    }

    @Test fun lostOwnershipCannotReactivateAndSourceErrorsNeverQueueOutput() {
        Fixture().use { f ->
            var active = true
            val results = mutableListOf<SettingsResult<Boolean>>()
            f.player.play(7, -22, 80, { active }, results::add)
            active = false
            assertFalse(f.source.lastActive!!())
            active = true
            f.source.reply()
            assertEquals(SettingsError.NOT_READY, results.single().error)
            assertTrue(f.signals.requests.isEmpty())
            val failed = f.play()
            f.source.reply(error = SettingsError.FORBIDDEN)
            assertEquals(SettingsError.FORBIDDEN, failed.single().error)
            assertTrue(f.signals.requests.isEmpty())
            assertEquals(SettingsError.INVALID_VALUE, f.play(0).single().error)
            assertEquals(2, f.source.loads)
        }
    }

    @Test fun mismatchedPreparedIdentityIsRejectedBeforeOutput() {
        Fixture().use { f ->
            val results = f.play()
            f.source.reply(soundId = -55)
            assertEquals(SettingsError.CONFLICT, results.single().error)
            assertTrue(f.signals.requests.isEmpty())
        }
    }

    @Test fun fileBrokerProgressCanCheckActiveWhilePreviewCompletionClosesItsNativeFileLease() {
        Fixture().use { f ->
            val fileLock = ReentrantLock()
            val fileHeld = CountDownLatch(1)
            val closeEntered = CountDownLatch(1)
            val progressFinished = CountDownLatch(1)
            val failures = ConcurrentLinkedQueue<Throwable>()
            val active = AtomicBoolean(true)
            val fileLease = object : NotificationSoundFileLease {
                override fun close() {
                    closeEntered.countDown()
                    check(fileLock.tryLock(5, TimeUnit.SECONDS)) { "Timed out closing synthetic native file lease" }
                    fileLock.unlock()
                }
            }
            f.source.onRelease = fileLease::close
            val results = f.play()
            val progress = Thread {
                try {
                    fileLock.lock()
                    try {
                        fileHeld.countDown()
                        check(closeEntered.await(5, TimeUnit.SECONDS))
                        // A native broker may call this gate while holding its file-owner lock.
                        active.set(f.source.lastActive!!())
                        progressFinished.countDown()
                    } finally { fileLock.unlock() }
                } catch (failure: Throwable) { failures.add(failure) }
            }.apply { isDaemon = true }
            val completion = Thread {
                try {
                    check(fileHeld.await(5, TimeUnit.SECONDS))
                    // Completion is synchronous and still inside the preview's admission callback.
                    f.source.reply(error = SettingsError.FORBIDDEN)
                } catch (failure: Throwable) { failures.add(failure) }
            }.apply { isDaemon = true }
            try {
                progress.start(); completion.start()
                assertTrue(progressFinished.await(2, TimeUnit.SECONDS), "Active must not take the preview lock while native-file close waits for the broker.")
            } finally { progress.join(6_000); completion.join(6_000) }
            assertFalse(progress.isAlive || completion.isAlive)
            assertTrue(failures.isEmpty())
            assertFalse(active.get())
            assertEquals(SettingsError.FORBIDDEN, results.single().error)
            assertEquals(1, f.source.releases)
            assertTrue(f.signals.requests.isEmpty())
        }
    }

    private class Fixture : AutoCloseable {
        val source = Source()
        val signals = Signals()
        val player = NotificationSoundPreviewPlayer(source, signals)
        fun play(volume: Int = 80) = mutableListOf<SettingsResult<Boolean>>().also {
            player.play(7, -22, volume, { true }, it::add)
        }
        override fun close() = player.close()
    }

    private class Source : NotificationSoundSource {
        var loads = 0
        var releases = 0
        var closed = 0
        var immediate = false
        var onRelease: () -> Unit = {}
        var lastActive: (() -> Boolean)? = null
        var callback: ((SettingsResult<PreparedNotificationAudio>) -> Unit)? = null
        override fun load(accountUserId: Long, soundId: Long, active: () -> Boolean,
            result: (SettingsResult<PreparedNotificationAudio>) -> Unit): NotificationSoundLoad {
            loads++; callback = result; lastActive = active
            if (immediate) reply()
            var released = false
            return NotificationSoundLoad { if (!released) { released = true; releases++; onRelease() } }
        }
        fun reply(fallback: Boolean = false, error: SettingsError? = null, soundId: Long = -22) {
            callback?.invoke(if (error != null) SettingsResult(error = error)
                else SettingsResult(PreparedNotificationAudio(soundId, NotificationSoundPcm(ByteArray(192)), fallback)))
        }
        override fun invalidate() = Unit
        override fun close() { closed++ }
    }

    private class Signals : DeviceNotificationSignals {
        data class Request(val active: () -> Boolean, val completed: (Boolean) -> Unit)
        val requests = mutableListOf<Request>()
        var admit = true
        var immediate = false
        var stops = 0
        var closes = 0
        override fun play(volumePercent: Int) = error("Preview must play prepared PCM")
        override fun playPrepared(pcm: NotificationSoundPcm, volumePercent: Int, active: () -> Boolean,
            completed: (Boolean) -> Unit): Boolean {
            assertEquals(80, volumePercent)
            if (!admit) return false
            assertTrue(active())
            requests.add(Request(active, completed))
            if (immediate) completed(true)
            return true
        }
        fun complete(played: Boolean) = requests.last().completed(played)
        override fun flash() = error("Preview does not flash the window")
        override fun stop() { stops++ }
        override fun close() { closes++ }
    }
}
