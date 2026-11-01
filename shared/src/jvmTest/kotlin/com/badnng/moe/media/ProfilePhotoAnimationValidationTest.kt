package com.badnng.moe.media

import com.badnng.moe.chat.AttachmentLease
import com.badnng.moe.chat.ownedAttachmentLease
import com.badnng.moe.settings.SettingsError
import com.badnng.moe.settings.SettingsPreparedProfileAnimation
import com.badnng.moe.settings.SettingsResult
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class ProfilePhotoAnimationValidationTest {
    @Test fun realDaemonWorkerOwnsIndependentHandleThroughActualWorkAfterUiClose() {
        val path = Files.createTempFile("profile-validation-test-", ".mp4")
        val owner = ownedAttachmentLease { Files.deleteIfExists(path) }
        val workerHandle = assertNotNull(owner.retain())
        val started = CountDownLatch(1)
        val continueWork = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val caller = Thread.currentThread()
        val validation = ProfilePhotoAnimationValidation { received ->
            try {
                assertNotSame(caller, Thread.currentThread())
                assertTrue(Thread.currentThread().isDaemon)
                assertSame(workerHandle, received.lease)
                started.countDown()
                assertTrue(continueWork.await(5, TimeUnit.SECONDS))
                assertTrue(Files.exists(path))
                true
            } catch (error: Throwable) { failure.set(error); throw error }
        }
        val response = AtomicReference<SettingsResult<Unit>>()
        try {
            validation.validate(animation(owner), workerHandle, { true }) { response.set(it); completed.countDown() }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            owner.release()
            assertTrue(Files.exists(path))
            assertEquals(1L, completed.count)
        } finally { owner.release(); continueWork.countDown() }
        assertTrue(completed.await(5, TimeUnit.SECONDS))
        failure.get()?.let { throw it }
        assertEquals(Unit, response.get().value)
        assertFalse(Files.exists(path))
    }

    @Test fun globalWorkerHasOnlyEightQueueSlotsAndRejectsWithoutCallerWork() {
        val started = CountDownLatch(1)
        val continueWork = CountDownLatch(1)
        val completed = CountDownLatch(10)
        val released = AtomicInteger()
        val probeCalls = AtomicInteger()
        val responses = Collections.synchronizedList(mutableListOf<SettingsResult<Unit>>())
        val validator = ProfilePhotoAnimationValidation {
            probeCalls.incrementAndGet(); started.countDown()
            check(continueWork.await(5, TimeUnit.SECONDS))
            true
        }
        fun submit() {
            val handle = ownedAttachmentLease { released.incrementAndGet() }
            validator.validate(animation(handle), handle, { true }) { responses.add(it); completed.countDown() }
        }
        try {
            submit()
            assertTrue(started.await(5, TimeUnit.SECONDS))
            repeat(9) { submit() }
            assertEquals(1, probeCalls.get())
            assertEquals(SettingsError.REQUEST_FAILED, responses.single().error)
            assertEquals(1, released.get())
        } finally { continueWork.countDown() }
        assertTrue(completed.await(5, TimeUnit.SECONDS))
        assertEquals(9, probeCalls.get())
        assertEquals(10, released.get())
        assertEquals(9, responses.count { it.value == Unit })
    }

    @Test fun invalidAndThrowingValidatorsReleaseOnceAndReturnTypedErrors() {
        listOf(false, true).forEach { throws ->
            val finished = CountDownLatch(1)
            val releases = AtomicInteger()
            val response = AtomicReference<SettingsResult<Unit>>()
            val handle = ownedAttachmentLease { releases.incrementAndGet() }
            val validator = ProfilePhotoAnimationValidation {
                if (throws) error("private command/path/stderr") else false
            }
            validator.validate(animation(handle), handle, { true }) { response.set(it); finished.countDown() }
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertEquals(if (throws) SettingsError.REQUEST_FAILED else SettingsError.INVALID_VALUE, response.get().error)
            assertEquals(1, releases.get())
            assertFalse(response.get().toString().contains("private"))
        }
    }

    @Test fun cancellationDuringValidationWaitsForWorkBeforeReleasing() {
        val started = CountDownLatch(1)
        val continueWork = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val active = AtomicBoolean(true)
        val releases = AtomicInteger()
        val response = AtomicReference<SettingsResult<Unit>>()
        val handle = ownedAttachmentLease { releases.incrementAndGet() }
        val validator = ProfilePhotoAnimationValidation {
            started.countDown(); check(continueWork.await(5, TimeUnit.SECONDS)); true
        }
        try {
            validator.validate(animation(handle), handle, active::get) { response.set(it); finished.countDown() }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            active.set(false)
            assertEquals(0, releases.get())
            assertEquals(1L, finished.count)
        } finally { continueWork.countDown() }
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertEquals(SettingsError.NOT_READY, response.get().error)
        assertEquals(1, releases.get())
    }

    @Test fun staleAdmissionNeverRunsValidatorAndThrowingCallbackIsNotRetried() {
        val called = AtomicInteger()
        val released = AtomicInteger()
        val handle = ownedAttachmentLease { released.incrementAndGet() }
        ProfilePhotoAnimationValidation { error("must not probe") }
            .validate(animation(handle), handle, { false }) {
                assertEquals(SettingsError.NOT_READY, it.error)
                called.incrementAndGet(); error("callback failed")
            }
        assertEquals(1, called.get())
        assertEquals(1, released.get())
    }

    @Test fun workerCallbackFailureIsNotRetriedAndDoesNotBreakTheSharedWorker() {
        val callback = CountDownLatch(1)
        val nextCallback = CountDownLatch(1)
        val calls = AtomicInteger()
        val releases = AtomicInteger()
        val validator = ProfilePhotoAnimationValidation { true }
        val first = ownedAttachmentLease { releases.incrementAndGet() }
        validator.validate(animation(first), first, { true }) {
            calls.incrementAndGet(); callback.countDown(); error("callback failed")
        }
        assertTrue(callback.await(5, TimeUnit.SECONDS))
        val second = ownedAttachmentLease { releases.incrementAndGet() }
        validator.validate(animation(second), second, { true }) { nextCallback.countDown() }
        assertTrue(nextCallback.await(5, TimeUnit.SECONDS))
        assertEquals(1, calls.get())
        assertEquals(2, releases.get())
    }

    private fun animation(lease: AttachmentLease) = SettingsPreparedProfileAnimation(
        "unused-synthetic.mp4", 42, 800, 2_000, 0.0, "a".repeat(64), lease,
    )
}
