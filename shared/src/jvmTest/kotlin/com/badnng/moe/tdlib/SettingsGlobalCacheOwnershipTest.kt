package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.*

/** Only synthetic Java callbacks; these tests never open a client or delete files. */
class SettingsGlobalCacheOwnershipTest {
    @Test fun staleWaitingVisitsCannotCrowdOutTheLatestReadWhileOneRequestIsInFlight() {
        val f = Fixture()
        f.controller.open()
        assertEquals(1, f.calls.size)
        repeat(20) {
            f.controller.close(f.snapshot.token)
            f.controller.open()
            assertTrue(f.snapshot.loading)
            assertEquals(1, f.calls.size, "The original physical request still owns the slot.")
        }
        f.calls[0].reply(stats())
        assertEquals(2, f.calls.size, "The latest live visit must retain a pending read.")
        assertIs<TdApi.GetStorageStatistics>(f.calls[1].command)
        assertTrue(f.snapshot.loading)
        f.calls[1].reply(stats())
        assertFalse(f.snapshot.loading)
        assertEquals(1L, f.snapshot.revision)
        assertEquals(1, f.maximum.get())
    }

    @Test fun pruningAndThePhysicalCallbackCanRaceWithoutHoldingTheQueueLock() {
        val calls = CopyOnWriteArrayList<Pending>()
        val outstanding = AtomicInteger()
        val maximum = AtomicInteger()
        val coordinator = SettingsStorageCoordinator { command, callback, _ ->
            val count = outstanding.incrementAndGet()
            maximum.updateAndGet { maxOf(it, count) }
            calls += Pending(command, callback, outstanding)
            true
        }
        val scanEntered = CountDownLatch(1)
        val releaseScan = CountDownLatch(1)
        val blocked = SettingsProxyProbeOwner {
            scanEntered.countDown()
            check(releaseScan.await(10, TimeUnit.SECONDS))
            false
        }
        coordinator.submit(TdApi.GetStorageStatistics(0), SettingsProxyProbeOwner { true }) { }
        coordinator.submit(TdApi.GetStorageStatistics(0), blocked) { }
        repeat(15) {
            val dead = SettingsProxyProbeOwner { true }
            coordinator.submit(TdApi.GetStorageStatistics(0), dead) { }
            dead.revoke()
        }
        val pruning = thread(isDaemon = true) {
            coordinator.submit(TdApi.GetStorageStatistics(0), SettingsProxyProbeOwner { true }) { }
        }
        assertTrue(scanEntered.await(3, TimeUnit.SECONDS))
        val raw = thread(isDaemon = true) { calls[0].reply(TdApi.StorageStatistics(0, 0, emptyArray())) }
        val extra: Thread
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (raw.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
            assertEquals(Thread.State.BLOCKED, raw.state, "The pump has reserved the owner being scanned.")
            val admitted = CountDownLatch(1)
            extra = thread(isDaemon = true) {
                coordinator.submit(TdApi.GetStorageStatistics(0), SettingsProxyProbeOwner { true }) { }
                admitted.countDown()
            }
            assertTrue(admitted.await(3, TimeUnit.SECONDS), "The concurrent pump freed a waiting position.")
        } finally {
            releaseScan.countDown()
        }
        pruning.join(3000); raw.join(3000); extra.join(3000)
        assertFalse(pruning.isAlive)
        assertFalse(raw.isAlive)
        assertEquals(2, calls.size)
        calls[1].reply(TdApi.StorageStatistics(0, 0, emptyArray()))
        assertEquals(3, calls.size)
        calls[2].reply(TdApi.StorageStatistics(0, 0, emptyArray()))
        assertEquals(0, outstanding.get())
        assertEquals(1, maximum.get())
    }

    @Test fun oldCleanupHoldsTheSlotAcrossCloseAndItsFailureSurvivesTheNewRead() {
        val f = Fixture()
        f.controller.open()
        f.calls[0].reply(stats())
        val oldToken = f.snapshot.token
        val oldRevision = f.snapshot.revision
        f.controller.clear(oldToken, oldRevision, setOf(SettingsStorageFileType.PHOTO))
        assertIs<TdApi.OptimizeStorage>(f.calls[1].command)
        f.controller.close(oldToken)
        f.controller.open()
        assertEquals(2, f.calls.size, "The new read waits for the old physical cleanup.")
        f.calls[1].reply(TdApi.Error(403, "private-path"))
        f.calls[1].reply(TdApi.StorageStatistics(0, 0, emptyArray()))
        assertEquals(3, f.calls.size)
        assertIs<TdApi.GetStorageStatistics>(f.calls[2].command)
        f.calls[2].reply(stats())
        assertEquals(SettingsError.FORBIDDEN, f.snapshot.lastClearError)
        assertEquals(0L, f.snapshot.completedClearRevision)
        assertFalse(f.snapshot.needsFreshStatistics)
        assertEquals(1, f.maximum.get())
        assertFalse(f.snapshot.toString().contains("private-path"))
    }

    @Test fun uncertainThrowKeepsThePhysicalSlotUntilTheLateRawCallback() {
        val f = Fixture()
        f.controller.open()
        f.calls[0].reply(stats())
        f.throwNext = true
        val revision = f.snapshot.revision
        f.controller.clear(f.snapshot.token, revision, setOf(SettingsStorageFileType.PHOTO))
        assertEquals(SettingsError.REQUEST_FAILED, f.snapshot.lastClearError)
        assertTrue(f.snapshot.needsFreshStatistics)
        f.controller.clear(f.snapshot.token, revision, setOf(SettingsStorageFileType.PHOTO))
        assertEquals(2, f.calls.size)
        f.controller.refresh(f.snapshot.token)
        assertEquals(2, f.calls.size, "A logical exception is not a native callback.")
        f.calls[1].reply(TdApi.StorageStatistics(0, 0, emptyArray()))
        assertEquals(3, f.calls.size)
        f.calls[2].reply(stats())
        assertEquals(SettingsError.REQUEST_FAILED, f.snapshot.lastClearError)
        assertFalse(f.snapshot.needsFreshStatistics)
        f.controller.clear(f.snapshot.token, revision, setOf(SettingsStorageFileType.PHOTO))
        assertEquals(3, f.calls.size, "An old confirmation cannot use the new statistics.")
        f.controller.clear(f.snapshot.token, f.snapshot.revision, setOf(SettingsStorageFileType.PHOTO))
        assertEquals(4, f.calls.size)
        assertEquals(1, f.maximum.get())
    }

    @Test fun independentFastReadAndLegacyUnsupportedDoNotConsumeOrReleaseTheSlot() {
        val f = Fixture()
        f.controller.open()
        val legacy = mutableListOf<SettingsResult<SettingsStorage>>()
        f.session.optimizeStorage(legacy::add)
        assertEquals(SettingsError.UNSUPPORTED, legacy.single().error)
        f.session.advanced { }
        assertTrue(f.otherCalls.any { it is TdApi.GetStorageStatisticsFast })
        assertTrue(f.otherCalls.any { it is TdApi.GetProxies })
        assertEquals(1, f.calls.size)
        assertIs<TdApi.GetStorageStatistics>(f.calls.single().command)
        f.controller.close(f.snapshot.token)
        f.controller.open()
        assertEquals(1, f.calls.size)
    }

    @Test fun reentrantCloseAndReopenDuringClearPublicationNeverSendTheOldSelection() {
        val f = Fixture()
        f.controller.open()
        f.calls[0].reply(stats())
        val token = f.snapshot.token
        val revision = f.snapshot.revision
        f.onPublish = { state ->
            if (state.clearing) {
                f.onPublish = null
                f.controller.close(state.token)
                f.controller.open()
            }
        }
        f.controller.clear(token, revision, setOf(SettingsStorageFileType.PHOTO))
        assertEquals(2, f.calls.size)
        assertTrue(f.calls.all { it.command is TdApi.GetStorageStatistics })
        assertTrue(f.snapshot.opened)
        assertTrue(f.snapshot.loading)
        f.calls[1].reply(stats())
        assertEquals(1L, f.snapshot.revision)
    }

    @Test fun callbacksAndTheNextDispatchRunOutsideTheOwnerAndGateLocksEvenWhenSynchronous() {
        val gate = TdUserInteractionGate()
        val owner = SettingsProxyProbeOwner { true }
        val sending = AtomicBoolean()
        val calls = AtomicInteger()
        val coordinator = SettingsStorageCoordinator { _, reply, _ ->
            gate.dispatchStorage {
                sending.set(true)
                try { calls.incrementAndGet(); reply(TdApi.StorageStatistics(0, 0, emptyArray())) }
                finally { sending.set(false) }
                true
            }
        }
        var results = 0
        coordinator.submit(TdApi.GetStorageStatistics(0), owner) {
            assertFalse(Thread.holdsLock(owner.lock))
            assertFalse(sending.get())
            results++
            coordinator.submit(TdApi.GetStorageStatistics(0), owner) {
                assertFalse(Thread.holdsLock(owner.lock))
                assertFalse(sending.get())
                results++
            }
        }
        assertEquals(2, calls.get())
        assertEquals(2, results)
    }

    @Test fun rawBeforeSynchronousThrowWinsAndCannotReoccupyTheSlot() {
        val owner = SettingsProxyProbeOwner { true }
        var calls = 0
        val coordinator = SettingsStorageCoordinator { _, reply, _ ->
            calls++
            reply(TdApi.StorageStatistics(0, 0, emptyArray()))
            throw IllegalStateException("synthetic")
        }
        val results = mutableListOf<SettingsResult<TdApi.Object>>()
        coordinator.submit(TdApi.GetStorageStatistics(0), owner, results::add)
        coordinator.submit(TdApi.GetStorageStatistics(0), owner, results::add)
        assertEquals(2, calls)
        assertEquals(2, results.size)
        assertTrue(results.all { it.value is TdApi.StorageStatistics })
    }

    @Test fun pageRevokeWaitsForCommittedSendAndRejectsAllLaterSends() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closing = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val reply = AtomicReference<((TdApi.Object) -> Unit)?>(null)
        val sends = AtomicInteger()
        val owner = SettingsProxyProbeOwner { true }
        val coordinator = SettingsStorageCoordinator { _, callback, _ ->
            sends.incrementAndGet()
            reply.set(callback)
            entered.countDown()
            check(release.await(3, TimeUnit.SECONDS))
            true
        }
        val worker = thread { coordinator.submit(TdApi.GetStorageStatistics(0), owner) { } }
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        val closer = thread { closing.countDown(); owner.revoke(); closed.countDown() }
        assertTrue(closing.await(3, TimeUnit.SECONDS))
        assertFalse(closed.await(100, TimeUnit.MILLISECONDS))
        release.countDown()
        worker.join(3000); closer.join(3000)
        assertEquals(0L, closed.count)
        reply.get()!!(TdApi.StorageStatistics(0, 0, emptyArray()))
        var rejected: SettingsError? = null
        coordinator.submit(TdApi.GetStorageStatistics(0), owner) { rejected = it.error }
        assertEquals(SettingsError.NOT_READY, rejected)
        assertEquals(1, sends.get())
    }

    @Test fun realStorageSenderProvesNativeGateRejectionBeforeAnySend() {
        val gate = TdUserInteractionGate()
        val calls = mutableListOf<TdApi.Function<*>>()
        val native = OfficialTdSession(offline = true, interactionGate = gate, isAuthorizationReady = { true },
            send = { command, _, _ -> calls += command })
        gate.update(LoginEmailSuggestions(1, requiredPresent = true, requiredEpoch = 1))
        val outcomes = mutableListOf<SettingsResult<SettingsStorageDetails>>()
        native.settings.storage!!.storageDetailsOwned(SettingsProxyProbeOwner { true }, outcomes::add)
        assertTrue(calls.isEmpty())
        assertEquals(SettingsError.NOT_READY, outcomes.single().error)
        gate.satisfyRequired(1)
        native.settings.storage!!.storageDetailsOwned(SettingsProxyProbeOwner { true }, outcomes::add)
        assertIs<TdApi.GetStorageStatistics>(calls.single())
    }

    @Test fun closeOkDoesNotDrainAnOutstandingStatisticsRequest() {
        val calls = mutableListOf<Pair<TdApi.Function<*>, (TdApi.Object) -> Unit>>()
        val native = OfficialTdSession(offline = true, isAuthorizationReady = { true },
            send = { command, reply, _ -> calls += command to reply })
        val storage = native.settings.storage!!
        val results = mutableListOf<SettingsResult<SettingsStorageDetails>>()
        storage.storageDetailsOwned(SettingsProxyProbeOwner { true }, results::add)
        storage.storageDetailsOwned(SettingsProxyProbeOwner { true }, results::add)
        assertEquals(1, calls.count { it.first is TdApi.GetStorageStatistics })
        native.close()
        calls.single { it.first is TdApi.Close }.second(TdApi.Ok())
        assertEquals(1, calls.count { it.first is TdApi.GetStorageStatistics })
        assertTrue(results.isEmpty())
        calls.single { it.first is TdApi.GetStorageStatistics }.second(TdApi.StorageStatistics(0, 0, emptyArray()))
        assertEquals(1, calls.count { it.first is TdApi.GetStorageStatistics })
        assertEquals(SettingsError.NOT_READY, results.last().error)
    }

    private class Fixture {
        val calls = mutableListOf<Pending>()
        val otherCalls = mutableListOf<TdApi.Function<*>>()
        val outstanding = AtomicInteger()
        val maximum = AtomicInteger()
        var throwNext = false
        val session = OfficialTdSettingsSession(storageSend = { command, callback, _ ->
            val count = outstanding.incrementAndGet()
            maximum.updateAndGet { maxOf(it, count) }
            calls += Pending(command, callback, outstanding)
            if (throwNext) { throwNext = false; throw IllegalStateException("synthetic submission ambiguity") }
            true
        }, send = { command, _, _ -> otherCalls += command })
        var snapshot = SettingsStorageSnapshot()
        var onPublish: ((SettingsStorageSnapshot) -> Unit)? = null
        val controller = SettingsStorageController(7, session.storage, { it() }, { snapshot = it; onPublish?.invoke(it) })
    }

    private class Pending(val command: TdApi.Function<*>, val callback: (TdApi.Object) -> Unit,
        val outstanding: AtomicInteger,
    ) {
        private val returned = AtomicBoolean()
        fun reply(value: TdApi.Object) {
            if (returned.compareAndSet(false, true)) outstanding.decrementAndGet()
            callback(value)
        }
    }

    private companion object {
        fun stats() = TdApi.StorageStatistics(30, 3, arrayOf(TdApi.StorageStatisticsByChat(0, 30, 3,
            arrayOf(TdApi.StorageStatisticsByFileType(TdApi.FileTypePhoto(), 30, 3)))))
    }
}
