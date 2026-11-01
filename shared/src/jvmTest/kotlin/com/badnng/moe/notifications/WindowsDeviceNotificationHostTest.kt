package com.badnng.moe.notifications

import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class WindowsDeviceNotificationHostTest {
    @Test fun bootstrapReadsCommittedRegistrationWithoutAnyPersistentWrite() = withFixture { f ->
        assertTrue(f.registration.enable().succeeded)
        f.boundary.events.clear()
        f.host.bootstrap(previouslyEnabled = true)
        f.pump { f.host.snapshot().status == WindowsDeviceNotificationStatus.ENABLED }
        assertTrue(f.boundary.events.isEmpty())
        assertEquals(1, f.driver.initializations.get())
    }

    @Test fun bootstrapDoesNotActivateOrRepairAPreferenceManifestCrash() = withFixture { f ->
        f.registration.enable(awaitSelectionCommit = true)
        val pending = f.boundary.manifest
        f.boundary.events.clear()
        f.host.bootstrap(previouslyEnabled = true, activationLaunch = true)
        f.executor.drain()
        assertEquals(WindowsDeviceNotificationStatus.CONFLICT, f.host.snapshot().status)
        assertEquals(0, f.driver.initializations.get())
        assertEquals(pending, f.boundary.manifest)
        assertTrue(f.boundary.events.isEmpty())
    }

    @Test fun choosingAppAfterAPendingStartupKeepsTheRealConflictAndRemovalEntrance() = withFixture { f ->
        f.registration.enable(awaitSelectionCommit = true)
        val pending = f.boundary.manifest
        val registry = f.boundary.registry
        val shortcut = f.boundary.shortcut
        f.boundary.events.clear()
        f.host.bootstrap(previouslyEnabled = true, activationLaunch = true)
        f.executor.drain()
        assertEquals(WindowsDeviceNotificationStatus.CONFLICT, f.host.snapshot().status)
        val app = AtomicReference<Boolean?>()
        f.host.select(7, false) { app.set(it) }
        f.pump { app.get() != null }
        assertEquals(true, app.get())
        assertTrue(f.commit())
        assertEquals(WindowsDeviceNotificationStatus.CONFLICT, f.host.snapshot().status,
            "The UI offers owned-registration removal for CONFLICT; APP selection must retain it")
        assertFalse(f.host.snapshot().registered)
        assertEquals(pending, f.boundary.manifest)
        assertEquals(registry, f.boundary.registry)
        assertEquals(shortcut, f.boundary.shortcut)
        assertTrue(f.boundary.events.isEmpty(), "APP selection must not silently repair or remove startup registration")
        assertEquals(0, f.driver.initializations.get())
    }

    @Test fun successfulDriverRemainsProvisionalUntilDurablePreferenceCommit() = withFixture { f ->
        assertTrue(f.selectWindows())
        assertEquals(WindowsDeviceNotificationStatus.ENABLING, f.host.snapshot().status)
        assertEquals(WindowsToastManifestStage.SELECTION_PENDING, f.boundary.manifest!!.stage)
        assertTrue(f.commit())
        assertEquals(WindowsDeviceNotificationStatus.ENABLED, f.host.snapshot().status)
        assertEquals(WindowsToastManifestStage.COMMITTED, f.boundary.manifest!!.stage)
        f.host.retire(7)
        f.executor.drain()
        assertEquals(WindowsToastRegistrationStatus.READY, f.registration.readState().status)
    }

    @Test fun driverInitializationFailureRollsBackNewCandidateButPreservesAnExistingRegistration() {
        for (existing in listOf(false, true)) withFixture { f ->
            if (existing) assertTrue(f.registration.enable().succeeded)
            val before = f.boundary.manifest
            f.driver.failInitialize = true
            assertFalse(f.selectWindows())
            assertEquals(WindowsDeviceNotificationStatus.FAILED, f.host.snapshot().status)
            assertEquals(before, f.boundary.manifest)
            assertEquals(existing, f.boundary.registry != null)
            assertEquals(existing, f.boundary.shortcut != null)
        }
    }

    @Test fun failedPreferenceSaveCancelsOnlyTheNewRegistration() {
        for (existing in listOf(false, true)) withFixture { f ->
            if (existing) assertTrue(f.registration.enable().succeeded)
            val before = f.boundary.manifest
            assertTrue(f.selectWindows())
            f.host.cancelSelection(7)
            f.pump { f.host.snapshot().status != WindowsDeviceNotificationStatus.ENABLING }
            assertEquals(before, f.boundary.manifest)
            assertEquals(existing, f.boundary.registry != null)
            assertEquals(existing, f.boundary.shortcut != null)
            assertNotEquals(WindowsDeviceNotificationStatus.ENABLED, f.host.snapshot().status)
        }
    }

    @Test fun persistentCommitFailureStopsRuntimeAndRollsBackTheExactCandidate() = withFixture { f ->
        assertTrue(f.selectWindows())
        f.boundary.failOnce = "manifest:COMMITTED"
        assertFalse(f.commit())
        assertEquals(WindowsDeviceNotificationStatus.FAILED, f.host.snapshot().status)
        assertNull(f.boundary.manifest)
        assertNull(f.boundary.registry)
        assertNull(f.boundary.shortcut)
        f.host.synchronize(7, listOf(card()), false)
        assertTrue(f.driver.shown.isEmpty())
    }

    @Test fun switchingToAppWhileDriverIsPendingRejectsLateEnableAndCleansItsCandidate() = withFixture { f ->
        f.driver.resumeInitialize = CountDownLatch(1)
        val first = AtomicReference<Boolean?>()
        f.host.select(7, true) { first.set(it) }
        f.pump { f.driver.enteredInitialize.count == 0L }
        assertEquals(WindowsToastManifestStage.SELECTION_PENDING, f.boundary.manifest!!.stage)
        val app = AtomicReference<Boolean?>()
        f.host.select(7, false) { app.set(it) }
        f.driver.resumeInitialize!!.countDown()
        f.pump { app.get() != null && first.get() != null && f.boundary.manifest == null }
        assertEquals(false, first.get())
        assertEquals(true, app.get())
        assertFalse(f.host.snapshot().registered)
        assertNotEquals(WindowsDeviceNotificationStatus.ENABLED, f.host.snapshot().status)
    }

    @Test fun retirementAndOwnerReplacementRejectLateEnableWithoutLeakingNewOwnedRegistration() {
        for (replace in listOf(false, true)) withFixture { f ->
            f.driver.resumeInitialize = CountDownLatch(1)
            val first = AtomicReference<Boolean?>()
            f.host.select(7, true) { first.set(it) }
            f.pump { f.driver.enteredInitialize.count == 0L }
            if (replace) f.host.bind(8, { f.events += it }, {}) else f.host.retire(7)
            f.driver.resumeInitialize!!.countDown()
            f.pump { first.get() != null && f.boundary.manifest == null }
            assertEquals(false, first.get())
            assertNull(f.boundary.registry)
            assertNull(f.boundary.shortcut)
            assertTrue(f.events.isEmpty())
        }
    }

    @Test fun closeDrainsOwnedCleanupInsteadOfDiscardingItWithShutdownNow() = withFixture { f ->
        f.driver.resumeInitialize = CountDownLatch(1)
        val result = AtomicReference<Boolean?>()
        f.host.select(7, true) { result.set(it) }
        f.pump { f.driver.enteredInitialize.count == 0L }
        f.host.close()
        f.driver.resumeInitialize!!.countDown()
        f.pump { result.get() != null && f.boundary.manifest == null }
        assertEquals(false, result.get())
        assertNull(f.boundary.registry)
        assertNull(f.boundary.shortcut)
    }

    @Test fun backendFailureRemainsFailedAfterPolicyAndNoLaterCardsReachTheDriver() = withFixture { f ->
        assertTrue(f.selectWindows())
        assertTrue(f.commit())
        f.driver.failShow = true
        f.host.synchronize(7, listOf(card()), false)
        f.pump { f.host.snapshot().status == WindowsDeviceNotificationStatus.FAILED }
        val policy = AtomicReference<WindowsNotificationPolicy?>()
        f.host.policy(7) { policy.set(it) }
        f.pump { policy.get() != null }
        assertEquals(WindowsDeviceNotificationStatus.FAILED, f.host.snapshot().status)
        assertEquals(WindowsToastRegistrationStatus.READY, f.registration.readState().status)
        val shown = f.driver.shown.size
        f.host.synchronize(7, listOf(card().copy(text = "another native update")), false)
        f.executor.drain()
        assertEquals(shown, f.driver.shown.size)
        assertTrue(f.events.any { it == WindowsToastHostEvent.Failed(7) })
    }

    @Test fun failedSaveWhenSwitchingToAppRestoresThePreviouslyCommittedRuntime() = withFixture { f ->
        assertTrue(f.selectWindows()); assertTrue(f.commit())
        val original = f.boundary.manifest
        val app = AtomicReference<Boolean?>()
        f.host.select(7, false) { app.set(it) }
        f.pump { app.get() != null }
        assertEquals(true, app.get())
        f.host.cancelSelection(7)
        f.pump { f.host.snapshot().status == WindowsDeviceNotificationStatus.ENABLED }
        assertEquals(original, f.boundary.manifest)
        assertEquals(2, f.driver.initializations.get())
    }
}

private inline fun withFixture(body: (HostFixture) -> Unit) {
    val fixture = HostFixture()
    try { body(fixture) } finally { fixture.driver.resumeInitialize?.countDown(); fixture.host.close(); fixture.executor.drain() }
}

private class HostFixture {
    val boundary = WindowsToastRegistrationTestBoundary()
    val registration = WindowsToastRegistration(boundary)
    val driver = HostFakeDriver()
    val executor = HostManualExecutor()
    val events = CopyOnWriteArrayList<WindowsToastHostEvent>()
    val host = WindowsDeviceNotificationHost(registration, WindowsToastBackend { driver }, executor = executor)
    init { host.bind(7, { events += it }, {}) }
    fun selectWindows(): Boolean {
        val result = AtomicReference<Boolean?>()
        host.select(7, true) { result.set(it) }
        pump { result.get() != null }
        return result.get()!!
    }
    fun commit(): Boolean {
        val result = AtomicReference<Boolean?>()
        host.commitSelection(7) { result.set(it) }
        pump { result.get() != null }
        return result.get()!!
    }
    fun pump(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) { executor.drain(); if (!condition()) Thread.sleep(1) }
        executor.drain()
        assertTrue(condition(), "Host/driver contract did not settle")
    }
}

private class HostFakeDriver : WindowsToastDriver {
    val initializations = AtomicInteger()
    val enteredInitialize = CountDownLatch(1)
    @Volatile var resumeInitialize: CountDownLatch? = null
    @Volatile var failInitialize = false
    @Volatile var failShow = false
    val shown = CopyOnWriteArrayList<String>()
    override fun initialize(aumid: String, clsid: String, event: (WindowsToastEvent) -> Unit) {
        initializations.incrementAndGet(); enteredInitialize.countDown()
        check(resumeInitialize?.await(5, TimeUnit.SECONDS) != false)
        if (failInitialize) error("Fake driver initialization failure")
    }
    override fun show(nonce: String, tag: String, group: String, title: String, text: String, restored: Boolean) {
        shown += nonce
        if (failShow) error("Fake native show failure")
    }
    override fun remove(nonce: String) {}
    override fun policy() = WindowsNotificationPolicy()
    override fun close() {}
}

private class HostManualExecutor : AbstractExecutorService() {
    private val queue = ConcurrentLinkedQueue<Runnable>()
    @Volatile private var stopped = false
    override fun execute(command: Runnable) { if (stopped) throw java.util.concurrent.RejectedExecutionException(); queue.add(command) }
    fun drain() { while (true) (queue.poll() ?: return).run() }
    override fun shutdown() { stopped = true }
    override fun shutdownNow(): MutableList<Runnable> { stopped = true; return mutableListOf<Runnable>().apply { while (true) add(queue.poll() ?: break) } }
    override fun isShutdown() = stopped
    override fun isTerminated() = stopped && queue.isEmpty()
    override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
}

private fun card() = DeviceNotificationCard(DeviceNotificationIdentity(7, 2, 3), "Native title", "Native body", 1)
