package com.badnng.moe.tdlib

import com.badnng.moe.chat.ChatEvent
import com.badnng.moe.settings.*
import com.badnng.moe.status.*
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.drinkless.tdlib.TdApi
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.*

/** Real Engine and page controller plus the real native probe chain, using synthetic callbacks only. */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsProxyChecksEngineGateTest {
    @Test fun storageReopenWaitsForTheOldRawCallbackAndKeepsTheNewOwner() = runTest {
        withEngine { engine, f ->
            val settings = f.current.settings
            val first = assertNotNull(engine.openSettingsStorageOwned { true })
            runCurrent()
            assertEquals(1, settings.storageCalls.size)
            first.close()
            val second = assertNotNull(engine.openSettingsStorageOwned { true })
            runCurrent()
            assertEquals(1, settings.storageCalls.size)
            settings.storageCalls[0].reply(TdApi.StorageStatistics(0, 0, emptyArray()))
            runCurrent()
            assertEquals(2, settings.storageCalls.size)
            assertTrue(engine.state.value.storageSettings.loading)
            settings.storageCalls[1].reply(TdApi.StorageStatistics(0, 0, emptyArray()))
            runCurrent()
            assertTrue(engine.state.value.storageSettings.opened)
            assertFalse(engine.state.value.storageSettings.loading)
            assertEquals(1, f.physical.maximum.get())
            second.close()
        }
    }

    @Test fun storageStopRevokesBeforeActorAndCannotReopenOldReadySession() = runTest {
        withEngine { engine, f ->
            assertNotNull(engine.openSettingsStorageOwned { true })
            runCurrent()
            assertEquals(1, f.current.settings.storageCalls.size)
            val stop = async(start = CoroutineStart.UNDISPATCHED) { engine.stop() }
            assertNull(engine.openSettingsStorageOwned { true })
            assertEquals(1, f.current.settings.storageCalls.size)
            runCurrent()
            assertTrue(stop.await())
            f.current.settings.storageCalls.single().reply(TdApi.StorageStatistics(0, 0, emptyArray()))
            runCurrent()
            assertFalse(engine.state.value.storageSettings.opened)
        }
    }

    @Test fun storageQueuedAfterCloseCannotCrossAnUnconsumedNativeLock() = runTest {
        withEngine { engine, f ->
            val first = assertNotNull(engine.openSettingsStorageOwned { true })
            runCurrent()
            first.close()
            assertNotNull(engine.openSettingsStorageOwned { true })
            runCurrent()
            f.current.requireEmail()
            f.current.settings.storageCalls.single().reply(TdApi.StorageStatistics(0, 0, emptyArray()))
            assertEquals(1, f.current.settings.storageCalls.size)
            f.current.satisfyEmail()
            runCurrent()
            assertEquals(1, f.current.settings.storageCalls.size)
        }
    }

    @Test fun readyPageQueuesEveryNativeRowAndWorkerResultsWaitForTheActor() = runTest {
        withEngine { engine, f ->
            val settings = f.current.settings
            val opened = mutableListOf<SettingsProxyChecksToken?>()
            val load = engine.openSettingsProxyChecks(engine.state.value.settings.token, { true }, opened::add)
            assertTrue(opened.isEmpty())
            assertEquals(0, settings.advancedReads)
            runCurrent()
            val token = assertNotNull(opened.single())
            assertEquals(token, engine.state.value.settings.proxyChecks.token)
            assertEquals(1, settings.advancedReads)
            assertTrue(settings.probes.isEmpty(), "The page metadata owns one physical slot before any probe.")
            val metadata = settings.metadataReads.single()
            assertIs<TdApi.GetStorageStatisticsFast>(metadata.pending.command)
            assertEquals(1, f.physical.outstanding.get())
            metadata.pending.reply(TdApi.StorageStatisticsFast(12, 2, 4096, 0, 0))
            assertIs<TdApi.GetProxies>(metadata.pending.command)
            assertTrue(settings.probes.isEmpty(), "Metadata storage and proxy reads are serial.")
            assertEquals(1, f.physical.outstanding.get())
            metadata.pending.reply(settings.nativeRows())
            runCurrent()
            assertEquals(0, settings.ordinaryAdvancedReads)
            assertEquals(settings.rows, engine.state.value.settings.proxyChecks.rows.map { it.proxy })
            assertEquals(2, settings.probes.size)
            assertTrue(engine.state.value.settings.proxyChecks.rows.filter { it.proxy.type != SettingsProxyType.WEB }
                .all { it.status == SettingsProxyCheckStatus.Checking })
            assertEquals(SettingsProxyCheckStatus.NotTested, engine.state.value.settings.proxyChecks.rows.last().status)

            val first = settings.probes.first()
            thread { first.complete(0.1254) }.join()
            assertEquals(SettingsProxyCheckStatus.Checking, engine.state.value.settings.proxyChecks.rows.first().status)
            runCurrent()
            assertEquals(SettingsProxyCheckStatus.Available(125), engine.state.value.settings.proxyChecks.rows.first().status)
            val second = settings.probes[1]
            second.unavailable()
            runCurrent()
            assertEquals(SettingsProxyCheckStatus.Unavailable, engine.state.value.settings.proxyChecks.rows[1].status)
            settings.drainAll()
            runCurrent()
            // Slots wake the real actor; completing one wave admits the remaining offscreen rows.
            repeat(settings.rows.size) {
                settings.drainAll()
                runCurrent()
            }
            assertEquals(settings.rows.filter { it.type != SettingsProxyType.WEB }.map { it.id }, settings.probes.map { it.expected.id })
            assertTrue(f.physical.maximum.get() <= 2)
            assertEquals(0, f.physical.outstanding.get())
            val count = settings.probes.size
            runCurrent()
            assertEquals(count, settings.probes.size)
            load.close()
            runCurrent()
            assertNull(engine.state.value.settings.proxyChecks.token)
        }
    }

    @Test fun immediateNativeAuthorizationAndOrdinaryGateLossRejectBeforeSessionLoading() = runTest {
        for (mandatory in listOf(false, true)) withEngine { engine, f ->
            if (mandatory) f.current.requireEmail() else f.current.auth(AuthorizationPhase.OTHER_STEP)
            assertEquals(AuthorizationPhase.READY, engine.state.value.status.authorization)
            val opened = mutableListOf<SettingsProxyChecksToken?>()
            engine.openSettingsProxyChecks(engine.state.value.settings.token, { true }, opened::add).close()
            assertEquals(listOf<SettingsProxyChecksToken?>(null), opened)
            runCurrent()
            assertEquals(0, f.current.settings.advancedReads)
            assertTrue(f.current.settings.probes.isEmpty())
            assertEquals(0, f.physical.outstanding.get())
        }
    }

    @Test fun queuedOpenRechecksBothNativeGatesAndReportsOneRejectedOpening() = runTest {
        for (mandatory in listOf(false, true)) withEngine { engine, f ->
            val opened = mutableListOf<SettingsProxyChecksToken?>()
            val load = engine.openSettingsProxyChecks(engine.state.value.settings.token, { true }, opened::add)
            if (mandatory) f.current.requireEmail() else f.current.auth(AuthorizationPhase.OTHER_STEP)
            assertTrue(opened.isEmpty())
            runCurrent()
            assertEquals(listOf<SettingsProxyChecksToken?>(null), opened)
            assertEquals(0, f.current.settings.advancedReads)
            assertTrue(f.current.settings.probes.isEmpty())
            load.close(); load.close(); runCurrent()
            assertEquals(1, opened.size)
        }
    }

    @Test fun staleTokensInactiveThrowingAndRevokedQueuedVisitsNeverLoadAList() = runTest {
        withEngine { engine, f ->
            val token = engine.state.value.settings.token
            val opened = mutableListOf<SettingsProxyChecksToken?>()
            for (bad in listOf(token.copy(generation = token.generation - 1), token.copy(revision = token.revision + 1))) {
                engine.openSettingsProxyChecks(bad, { true }, opened::add).close()
            }
            for (active in listOf<() -> Boolean>({ false }, { error("synthetic owner failure") })) {
                engine.openSettingsProxyChecks(token, active, opened::add).close()
            }
            val active = AtomicBoolean(true)
            val load = engine.openSettingsProxyChecks(token, active::get, opened::add)
            active.set(false)
            runCurrent()
            active.set(true)
            runCurrent()
            assertEquals(List<SettingsProxyChecksToken?>(5) { null }, opened)
            assertEquals(0, f.current.settings.advancedReads)
            assertTrue(f.current.settings.probes.isEmpty())
            load.close()
        }
    }

    @Test fun handleCloseRevokesBeforeActorCleanupAndAnOldCloseCannotRetireTheNextVisit() = runTest {
        withEngine { engine, f ->
            val parent = engine.state.value.settings.token
            val firstOpened = mutableListOf<SettingsProxyChecksToken?>()
            val firstLoad = engine.openSettingsProxyChecks(parent, { true }, firstOpened::add)
            runCurrent()
            completeMetadata(f.current.settings)
            val old = f.current.settings.probes.toList()
            val oldToken = assertNotNull(firstOpened.single())
            assertTrue(old.all { it.lease.current() })
            firstLoad.close()
            assertTrue(old.none { it.lease.current() })
            assertEquals(oldToken, engine.state.value.settings.proxyChecks.token, "The actor has not handled cleanup yet.")
            val nextOpened = mutableListOf<SettingsProxyChecksToken?>()
            val nextLoad = engine.openSettingsProxyChecks(parent, { true }, nextOpened::add)
            runCurrent()
            val nextToken = assertNotNull(nextOpened.single())
            assertNotEquals(oldToken, nextToken)
            assertEquals(2, f.current.settings.probes.size, "Revoked requests still hold both physical slots.")
            firstLoad.close(); firstLoad.close(); runCurrent()
            assertEquals(nextToken, engine.state.value.settings.proxyChecks.token)
            old.forEach { it.pending.reply(f.current.settings.nativeRows()) }
            runCurrent()
            assertTrue(old.all { it.calls.size == 1 }, "Late first reads must not start PingProxy.")
            assertEquals(2, f.current.settings.probes.size, "The reopened page must read metadata before probing.")
            assertEquals(2, f.current.settings.metadataReads.size)
            completeMetadata(f.current.settings)
            assertEquals(4, f.current.settings.probes.size)
            assertEquals(nextToken, engine.state.value.settings.proxyChecks.token)
            assertTrue(engine.state.value.settings.proxyChecks.rows.filter { it.proxy.type != SettingsProxyType.WEB }
                .all { it.status == SettingsProxyCheckStatus.Checking })
            assertTrue(f.physical.maximum.get() <= 2)
            nextLoad.close()
        }
    }

    @Test fun stopRevisionRejectsAnEarlierQueuedOpenBeforeStopReachesTheActor() = runTest {
        withEngine { engine, f ->
            val opened = mutableListOf<SettingsProxyChecksToken?>()
            engine.openSettingsProxyChecks(engine.state.value.settings.token, { true }, opened::add)
            val stopped = async(start = CoroutineStart.UNDISPATCHED) { engine.stop() }
            assertFalse(stopped.isCompleted)
            assertTrue(engine.state.value.settings.enabled)
            runCurrent()
            assertTrue(stopped.await())
            assertEquals(listOf<SettingsProxyChecksToken?>(null), opened)
            assertTrue(f.current.settings.probes.isEmpty())
            assertEquals(0, f.current.settings.advancedReads)
        }
    }

    @Test fun handleCloseWaitsForACommittedSendAndPreventsAnyLaterProbeStage() = runTest {
        withEngine { engine, f ->
            val load = engine.openSettingsProxyChecks(engine.state.value.settings.token, { true }) { }
            runCurrent()
            completeMetadata(f.current.settings)
            val probe = f.current.settings.probes.first()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val attemptingClose = CountDownLatch(1)
            val closed = CountDownLatch(1)
            probe.beforeNativeSubmit = { command ->
                if (command is TdApi.PingProxy) {
                    entered.countDown()
                    check(release.await(3, TimeUnit.SECONDS))
                }
            }
            val sender = thread { probe.pending.reply(f.current.settings.nativeRows()) }
            var closer: Thread? = null
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                closer = thread { attemptingClose.countDown(); load.close(); closed.countDown() }
                assertTrue(attemptingClose.await(2, TimeUnit.SECONDS))
                assertFalse(closed.await(100, TimeUnit.MILLISECONDS), "close must wait for the shared dispatch boundary.")
            } finally {
                release.countDown()
                sender.join(2000)
                closer?.join(2000)
            }
            assertEquals(0L, closed.count)
            assertFalse(probe.lease.current())
            assertEquals(2, probe.calls.size, "The committed PingProxy may finish physically.")
            probe.pending.reply(TdApi.Seconds(0.1))
            runCurrent()
            assertEquals(2, probe.calls.size, "Revocation prevents the final GetProxies stage.")
            assertNull(engine.state.value.settings.proxyChecks.token)
            assertTrue(f.physical.maximum.get() <= 2)
        }
    }

    @Test fun nativeGateLossAtEveryProbeStageIsMonotonicBeforeTheActorRuns() = runTest {
        for (mandatory in listOf(false, true)) for (stage in 0..2) withEngine { engine, f ->
            val opened = mutableListOf<SettingsProxyChecksToken?>()
            engine.openSettingsProxyChecks(engine.state.value.settings.token, { true }, opened::add)
            runCurrent()
            completeMetadata(f.current.settings)
            val probe = f.current.settings.probes.first()
            if (stage >= 1) probe.pending.reply(f.current.settings.nativeRows())
            if (stage >= 2) probe.pending.reply(TdApi.Seconds(0.3))
            assertEquals(stage + 1, probe.calls.size)
            assertTrue(probe.lease.current())
            if (mandatory) f.current.requireEmail() else f.current.auth(AuthorizationPhase.OTHER_STEP)
            assertFalse(probe.lease.current(), "Native loss must be visible before actor snapshot updates.")
            if (mandatory) f.current.satisfyEmail() else f.current.auth(AuthorizationPhase.READY)
            assertFalse(probe.lease.current(), "Restoring the native gate cannot revive a visit.")
            val pending = probe.pending
            pending.reply(if (pending.command is TdApi.PingProxy) TdApi.Seconds(0.3) else f.current.settings.nativeRows())
            pending.reply(f.current.settings.nativeRows())
            runCurrent()
            assertEquals(stage + 1, probe.calls.size)
            assertNull(engine.state.value.settings.proxyChecks.token)
            assertTrue(engine.state.value.settings.proxyChecks.rows.isEmpty())
            val next = mutableListOf<SettingsProxyChecksToken?>()
            engine.openSettingsProxyChecks(engine.state.value.settings.token, { true }, next::add)
            runCurrent()
            assertNotNull(next.single())
            assertNotEquals(opened.single(), next.single())
            assertTrue(f.physical.maximum.get() <= 2)
        }
    }

    @Test fun inFlightStopRevokesSynchronouslyAndItsLateCallbacksOnlyDrain() = runTest {
        withEngine { engine, f ->
            engine.openSettingsProxyChecks(engine.state.value.settings.token, { true }) { }
            runCurrent()
            completeMetadata(f.current.settings)
            val probes = f.current.settings.probes.toList()
            val stopped = async(start = CoroutineStart.UNDISPATCHED) { engine.stop() }
            assertTrue(probes.none { it.lease.current() })
            assertEquals(2, f.physical.outstanding.get())
            runCurrent()
            assertTrue(stopped.await())
            assertEquals(2, f.physical.outstanding.get(), "Confirmed Closed alone is not a raw callback.")
            probes.forEach { probe ->
                probe.pending.reply(f.current.settings.nativeRows())
                probe.calls.first().reply(f.current.settings.nativeRows())
            }
            runCurrent()
            assertEquals(0, f.physical.outstanding.get())
            assertTrue(probes.all { it.calls.size == 1 })
            assertNull(engine.state.value.settings.proxyChecks.token)
        }
    }

    @Test fun timeoutClosedAndNewGenerationKeepTheBudgetUntilOldRawCallbacksReturn() = runTest {
        withEngine { engine, f ->
            val oldNative = f.current
            val oldLoad = engine.openSettingsProxyChecks(engine.state.value.settings.token, { true }) { }
            runCurrent()
            completeMetadata(f.current.settings)
            val old = oldNative.settings.probes.toList()
            f.autoClose = false
            val stopped = async(start = CoroutineStart.UNDISPATCHED) { engine.stop(timeoutMillis = 1) }
            runCurrent()
            advanceTimeBy(1); runCurrent()
            assertFalse(stopped.await())
            assertTrue(engine.state.value.closeTimedOut)
            assertEquals(2, f.physical.outstanding.get())
            oldNative.auth(AuthorizationPhase.CLOSED); runCurrent()
            f.autoClose = true
            assertTrue(engine.start()); runCurrent()
            f.current.auth(AuthorizationPhase.WAITING_PARAMETERS); runCurrent()
            f.current.auth(AuthorizationPhase.READY); runCurrent()
            assertNotSame(oldNative, f.current)
            val opened = mutableListOf<SettingsProxyChecksToken?>()
            engine.openSettingsProxyChecks(engine.state.value.settings.token, { true }, opened::add)
            runCurrent()
            val token = assertNotNull(opened.single())
            assertTrue(f.current.settings.probes.isEmpty(), "A fresh controller must share the occupied physical budget.")
            assertEquals(0, f.current.settings.advancedReads, "Metadata may not bypass two old physical requests.")
            oldLoad.close()
            old[0].pending.reply(oldNative.settings.nativeRows()); runCurrent()
            assertTrue(f.current.settings.probes.isEmpty())
            assertEquals(1, f.current.settings.advancedReads)
            assertEquals(2, f.physical.outstanding.get(), "Metadata and the other old request share both slots.")
            completeMetadata(f.current.settings)
            assertEquals(1, f.current.settings.probes.size)
            old[0].calls.first().reply(oldNative.settings.nativeRows()); runCurrent()
            assertEquals(1, f.current.settings.probes.size, "Duplicate old callbacks cannot release a second permit.")
            old[1].pending.reply(oldNative.settings.nativeRows()); runCurrent()
            assertEquals(2, f.current.settings.probes.size)
            assertEquals(token, engine.state.value.settings.proxyChecks.token)
            assertTrue(old.all { it.calls.size == 1 })
            assertEquals(2, f.physical.outstanding.get())
            assertTrue(f.physical.maximum.get() <= 2)

            // A callback retained by the previous transport cannot invalidate the new session's witness.
            oldNative.auth(AuthorizationPhase.OTHER_STEP)
            assertTrue(f.current.settings.probes.all { it.lease.current() })
            runCurrent()
            assertEquals(token, engine.state.value.settings.proxyChecks.token)
            f.current.settings.probes.first().complete(0.25); runCurrent()
            assertEquals(SettingsProxyCheckStatus.Available(250), engine.state.value.settings.proxyChecks.rows.first().status)
        }
    }

    @Test fun queuedSuccessfulResultCannotCrossCloseOrARecoveredOrdinaryLock() = runTest {
        for (lock in listOf(false, true)) withEngine { engine, f ->
            val oldOpened = mutableListOf<SettingsProxyChecksToken?>()
            val oldLoad = engine.openSettingsProxyChecks(engine.state.value.settings.token, { true }, oldOpened::add)
            runCurrent()
            completeMetadata(f.current.settings)
            val old = f.current.settings.probes.first()
            thread { old.complete(0.777) }.join()
            assertTrue(engine.state.value.settings.proxyChecks.rows.none { it.status is SettingsProxyCheckStatus.Available })
            if (lock) {
                f.current.requireEmail(); f.current.satisfyEmail()
            } else oldLoad.close()
            runCurrent()
            assertNull(engine.state.value.settings.proxyChecks.token)
            val nextOpened = mutableListOf<SettingsProxyChecksToken?>()
            engine.openSettingsProxyChecks(engine.state.value.settings.token, { true }, nextOpened::add)
            runCurrent()
            val next = assertNotNull(nextOpened.single())
            assertNotEquals(oldOpened.single(), next)
            completeMetadata(f.current.settings)
            old.calls.last().reply(f.current.settings.nativeRows())
            runCurrent()
            assertEquals(next, engine.state.value.settings.proxyChecks.token)
            assertTrue(engine.state.value.settings.proxyChecks.rows.none { it.status is SettingsProxyCheckStatus.Available })
        }
    }

    @Test fun repeatedVisitsWithoutCallbacksCannotCreateMorePhysicalWork() = runTest {
        withEngine { engine, f ->
            var previous: SettingsProxyChecksLoad? = null
            var token: SettingsProxyChecksToken? = null
            repeat(8) {
                previous?.close()
                previous = engine.openSettingsProxyChecks(engine.state.value.settings.token, { true }) { token = it }
                runCurrent()
                completeMetadata(f.current.settings)
                assertNotNull(token)
                assertEquals(2, f.physical.outstanding.get())
                assertEquals(2, f.current.settings.probes.size)
            }
            val latest = token
            val abandoned = f.current.settings.probes.toList()
            abandoned.forEach { it.pending.reply(f.current.settings.nativeRows()) }
            runCurrent()
            assertEquals(2, f.current.settings.probes.size)
            completeMetadata(f.current.settings)
            assertEquals(latest, engine.state.value.settings.proxyChecks.token)
            assertEquals(4, f.current.settings.probes.size)
            assertEquals(2, f.physical.outstanding.get())
            assertTrue(abandoned.all { it.calls.size == 1 })
            assertTrue(f.physical.maximum.get() <= 2)
            previous?.close()
        }
    }

    @Test fun throwingOpenedConsumerAndDuplicateNativeCallbacksLeaveTheActorUsable() = runTest {
        withEngine { engine, f ->
            var callbacks = 0
            val first = engine.openSettingsProxyChecks(engine.state.value.settings.token, { true }) {
                callbacks++
                error("synthetic consumer failure")
            }
            runCurrent()
            completeMetadata(f.current.settings)
            assertEquals(1, callbacks)
            val probe = f.current.settings.probes.first()
            probe.complete(0.2)
            probe.calls.last().reply(f.current.settings.nativeRows())
            runCurrent()
            assertEquals(1, callbacks)
            first.close()
            val opened = mutableListOf<SettingsProxyChecksToken?>()
            engine.openSettingsProxyChecks(engine.state.value.settings.token, { true }, opened::add)
            runCurrent()
            completeMetadata(f.current.settings)
            assertNotNull(opened.single())
            assertTrue(engine.state.value.clientActive)
            assertTrue(f.physical.maximum.get() <= 2)
        }
    }

    @Test fun refreshKeepsTheVisitAndItsMetadataWaitsForAnActualFreeSlot() = runTest {
        withEngine { engine, f ->
            val opened = mutableListOf<SettingsProxyChecksToken?>()
            val load = engine.openSettingsProxyChecks(engine.state.value.settings.token, { true }, opened::add)
            runCurrent()
            completeMetadata(f.current.settings)
            val token = assertNotNull(opened.single())
            val beforeEpoch = engine.state.value.settings.proxyChecks.listEpoch
            val old = f.current.settings.probes.toList()
            load.refresh()
            assertEquals(1, f.current.settings.metadataReads.size, "Refresh only schedules actor work at the public boundary.")
            runCurrent()
            assertEquals(token, engine.state.value.settings.proxyChecks.token)
            assertTrue(engine.state.value.settings.proxyChecks.rows.isEmpty())
            assertTrue(old.none { it.lease.current() })
            assertEquals(1, f.current.settings.metadataReads.size, "Both revoked raw callbacks still own physical slots.")
            assertEquals(2, f.physical.outstanding.get())
            old[0].pending.reply(f.current.settings.nativeRows()); runCurrent()
            assertEquals(2, f.current.settings.metadataReads.size)
            assertIs<TdApi.GetStorageStatisticsFast>(f.current.settings.metadataReads.last().pending.command)
            assertEquals(2, f.physical.outstanding.get(), "The metadata chain shares capacity with the other old probe.")
            completeMetadata(f.current.settings)
            assertEquals(token, engine.state.value.settings.proxyChecks.token)
            assertTrue(engine.state.value.settings.proxyChecks.listEpoch > beforeEpoch)
            assertEquals(f.current.settings.rows, engine.state.value.settings.proxyChecks.rows.map { it.proxy })
            assertEquals(3, f.current.settings.probes.size)
            old[1].pending.reply(f.current.settings.nativeRows()); runCurrent()
            assertEquals(4, f.current.settings.probes.size)
            assertEquals(0, f.current.settings.ordinaryAdvancedReads)
            assertTrue(old.all { it.calls.size == 1 })
            assertTrue(f.physical.maximum.get() <= 2)
            load.close()
        }
    }

    @Test fun queuedRefreshCannotBecomeAnOrdinaryReadAfterCloseOrAReplacementVisit() = runTest {
        for (replace in listOf(false, true)) withEngine { engine, f ->
            val opened = mutableListOf<SettingsProxyChecksToken?>()
            val load = engine.openSettingsProxyChecks(engine.state.value.settings.token, { true }, opened::add)
            runCurrent()
            completeMetadata(f.current.settings)
            val oldToken = assertNotNull(opened.single())
            val old = f.current.settings.probes.toList()
            load.refresh()
            load.close()
            val next = mutableListOf<SettingsProxyChecksToken?>()
            val nextLoad = if (replace) engine.openSettingsProxyChecks(engine.state.value.settings.token, { true }, next::add) else null
            runCurrent()
            assertEquals(1, f.current.settings.metadataReads.size)
            assertEquals(0, f.current.settings.ordinaryAdvancedReads)
            assertEquals(2, f.current.settings.probes.size)
            if (replace) assertNotEquals(oldToken, assertNotNull(next.single()))
            else assertNull(engine.state.value.settings.proxyChecks.token)
            old.forEach { it.pending.reply(f.current.settings.nativeRows()) }
            runCurrent()
            if (replace) {
                assertEquals(2, f.current.settings.metadataReads.size, "Only the replacement visit owns the resumed metadata read.")
                completeMetadata(f.current.settings)
                assertEquals(next.single(), engine.state.value.settings.proxyChecks.token)
                assertEquals(4, f.current.settings.probes.size)
            } else {
                assertEquals(1, f.current.settings.metadataReads.size)
                assertTrue(engine.state.value.settings.proxyChecks.rows.isEmpty())
                assertEquals(0, f.physical.outstanding.get())
            }
            load.refresh(); runCurrent()
            assertEquals(if (replace) 2 else 1, f.current.settings.metadataReads.size)
            assertEquals(0, f.current.settings.ordinaryAdvancedReads)
            assertTrue(f.physical.maximum.get() <= 2)
            nextLoad?.close()
        }
    }

    @Test fun queuedRefreshRechecksNativeAuthorizationOrdinaryAndStopBeforeDispatch() = runTest {
        for (loss in listOf("authorization", "mandatory", "stop")) withEngine { engine, f ->
            val load = engine.openSettingsProxyChecks(engine.state.value.settings.token, { true }) { }
            runCurrent()
            completeMetadata(f.current.settings)
            val old = f.current.settings.probes.toList()
            load.refresh()
            val stopped = if (loss == "stop") async(start = CoroutineStart.UNDISPATCHED) { engine.stop() } else null
            when (loss) {
                "authorization" -> { f.current.auth(AuthorizationPhase.OTHER_STEP); f.current.auth(AuthorizationPhase.READY) }
                "mandatory" -> { f.current.requireEmail(); f.current.satisfyEmail() }
            }
            assertEquals(2, f.physical.outstanding.get())
            runCurrent()
            if (stopped != null) assertTrue(stopped.await())
            assertEquals(1, f.current.settings.metadataReads.size)
            assertEquals(0, f.current.settings.ordinaryAdvancedReads)
            assertNull(engine.state.value.settings.proxyChecks.token)
            old.forEach { it.pending.reply(f.current.settings.nativeRows()) }
            runCurrent()
            assertEquals(1, f.current.settings.metadataReads.size)
            assertEquals(0, f.physical.outstanding.get())
            assertTrue(old.all { it.calls.size == 1 })
            assertTrue(f.physical.maximum.get() <= 2)
        }
    }

    @Test fun pageCloseAndNativeLossAtEachMetadataStagePreventTheFollowingNativeWork() = runTest {
        for (loss in listOf("close", "authorization", "mandatory", "stop")) for (stage in 0..1) withEngine { engine, f ->
            val load = engine.openSettingsProxyChecks(engine.state.value.settings.token, { true }) { }
            runCurrent()
            val metadata = f.current.settings.metadataReads.single()
            if (stage == 1) metadata.pending.reply(TdApi.StorageStatisticsFast(12, 2, 4096, 0, 0))
            assertEquals(stage + 1, metadata.calls.size)
            assertEquals(1, f.physical.outstanding.get())
            val stopped = if (loss == "stop") async(start = CoroutineStart.UNDISPATCHED) { engine.stop() } else null
            when (loss) {
                "close" -> load.close()
                "authorization" -> { f.current.auth(AuthorizationPhase.OTHER_STEP); f.current.auth(AuthorizationPhase.READY) }
                "mandatory" -> { f.current.requireEmail(); f.current.satisfyEmail() }
            }
            assertFalse(metadata.lease.current())
            assertEquals(1, f.physical.outstanding.get(), "Revocation does not prove that the raw metadata request returned.")
            val pending = metadata.pending
            pending.reply(if (stage == 0) TdApi.StorageStatisticsFast(12, 2, 4096, 0, 0) else f.current.settings.nativeRows())
            pending.reply(f.current.settings.nativeRows())
            runCurrent()
            if (stopped != null) assertTrue(stopped.await())
            assertEquals(stage + 1, metadata.calls.size)
            assertTrue(f.current.settings.probes.isEmpty())
            assertEquals(0, f.current.settings.ordinaryAdvancedReads)
            assertNull(engine.state.value.settings.proxyChecks.token)
            assertEquals(0, f.physical.outstanding.get())
            assertTrue(f.physical.maximum.get() <= 2)
        }
    }

    @Test fun stopRequestedWhileActorIsBlockedRejectsANewPageBeforeAnyNativeRead() = runBlocking {
        withRealtimeEngine { engine, f ->
            val actor = ActorBlock(f.current.settings)
            try {
                actor.block(engine)
                assertFalse(engine.stop(timeoutMillis = 0))
                assertTrue(engine.state.value.settings.enabled, "The blocked actor still publishes the old enabled settings.")
                assertEquals(AuthorizationPhase.READY, engine.state.value.status.authorization)
                assertTrue(f.current.authorizationReady())
                val opened = mutableListOf<SettingsProxyChecksToken?>()
                val load = engine.openSettingsProxyChecks(engine.state.value.settings.token, { true }, opened::add)
                assertEquals(listOf<SettingsProxyChecksToken?>(null), opened, "The public boundary must reject without waiting for the Stop actor action.")
                assertEquals(0, f.current.settings.advancedReads)
                assertTrue(f.current.settings.metadataReads.isEmpty())
                assertTrue(f.current.settings.probes.isEmpty())
                assertEquals(0, f.physical.outstanding.get())
                load.refresh(); load.close()
                assertEquals(1, opened.size)
            } finally {
                actor.release()
            }
        }
    }

    @Test fun stopInterleavedWithPublicAdmissionAndRegistryPublicationCannotAdmitANewVisit() = runBlocking {
        // First call blocks before publication; second call blocks after registry insertion.
        for (pauseAt in listOf(1, 2)) withRealtimeEngine { engine, f ->
            val actor = ActorBlock(f.current.settings)
            val activeEntered = CountDownLatch(1)
            val activeRelease = CountDownLatch(1)
            val activeCalls = AtomicInteger()
            val opened = CopyOnWriteArrayList<SettingsProxyChecksToken?>()
            val returned = AtomicReference<SettingsProxyChecksLoad?>()
            val failure = AtomicReference<Throwable?>()
            var opener: Thread? = null
            try {
                actor.block(engine)
                val parent = engine.state.value.settings.token
                opener = thread(name = "proxy-page-open-race") {
                    try {
                        returned.set(engine.openSettingsProxyChecks(parent, {
                            if (activeCalls.incrementAndGet() == pauseAt) {
                                activeEntered.countDown()
                                check(activeRelease.await(5, TimeUnit.SECONDS))
                            }
                            true
                        }, opened::add))
                    } catch (error: Throwable) { failure.set(error) }
                }
                assertTrue(activeEntered.await(2, TimeUnit.SECONDS))
                assertFalse(engine.stop(timeoutMillis = 0))
                assertTrue(engine.state.value.settings.enabled)
                assertTrue(f.current.authorizationReady())
                activeRelease.countDown()
                opener.join(2000)
                assertFalse(opener.isAlive)
                assertNull(failure.get())
                assertEquals(listOf<SettingsProxyChecksToken?>(null), opened, "Stop must reject visits added either side of its synchronous registry revocation.")
                assertEquals(pauseAt, activeCalls.get())
                assertEquals(0, f.current.settings.advancedReads)
                assertEquals(0, f.physical.outstanding.get())
                returned.get()?.refresh()
                returned.get()?.close()
                assertEquals(1, opened.size)
            } finally {
                activeRelease.countDown()
                opener?.join(2000)
                actor.release()
            }
        }
    }

    @Test fun proxyMutationQueuedBeforePageCloseCannotEscapeIntoAnOrdinaryReadback() = runTest {
        withEngine { engine, f ->
            val load = engine.openSettingsProxyChecks(engine.state.value.settings.token, { true }) { }
            runCurrent()
            completeMetadata(f.current.settings)
            val old = f.current.settings.probes.toList()
            assertEquals(2, f.physical.outstanding.get())
            assertTrue(engine.enableSettingsProxy(engine.state.value.settings.token, 2))
            load.close()
            assertTrue(old.none { it.lease.current() })
            assertTrue(f.current.settings.proxyEnables.isEmpty(), "The actor has not consumed the queued native mutation.")
            runCurrent()
            val enabled = f.current.settings.proxyEnables.single()
            assertEquals(2, enabled.id)
            assertEquals(1, f.current.settings.metadataReads.size)
            assertEquals(0, f.current.settings.ordinaryAdvancedReads)
            assertEquals(2, f.physical.outstanding.get())
            enabled.reply(SettingsResult(Unit))
            runCurrent()
            assertEquals(1, f.current.settings.metadataReads.size, "A revoked page cannot authorize a managed readback.")
            assertEquals(0, f.current.settings.ordinaryAdvancedReads, "The missing active page must not become a generic advanced read.")
            assertNull(engine.state.value.settings.proxyChecks.token)
            assertEquals(2, f.physical.outstanding.get(), "Mutation ACK and logical close cannot drain old probe requests.")
            old.forEach { it.pending.reply(f.current.settings.nativeRows()) }
            runCurrent()
            assertEquals(0, f.physical.outstanding.get())
            assertEquals(1, f.current.settings.metadataReads.size)
            assertEquals(0, f.current.settings.ordinaryAdvancedReads)
            assertTrue(old.all { it.calls.size == 1 })
            assertTrue(f.physical.maximum.get() <= 2)
        }
    }

    private fun TestScope.completeMetadata(settings: Settings) {
        settings.metadataReads.filter { !it.terminal }.forEach { it.complete() }
        runCurrent()
    }

    /** Blocks an unrelated real Settings input, without holding any proxy dispatch/revoke lock. */
    private class ActorBlock(settings: Settings) {
        private val entered = CountDownLatch(1)
        private val released = CountDownLatch(1)
        init {
            settings.beforeProfileRead = {
                entered.countDown()
                check(released.await(8, TimeUnit.SECONDS))
            }
        }
        fun block(engine: TdEngine) {
            assertTrue(engine.loadSettings(engine.state.value.settings.token, SettingsSection.PROFILE))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
        }
        fun release() { released.countDown() }
    }

    private suspend fun withRealtimeEngine(block: suspend (TdEngine, Fixture) -> Unit) {
        val f = Fixture()
        val executor = Executors.newSingleThreadExecutor { command ->
            Thread(command, "proxy-stop-admission-actor").apply { isDaemon = true }
        }
        val actorDispatcher = executor.asCoroutineDispatcher()
        val engine = syntheticOpenEngine(f.store, f, actorDispatcher)
        try {
            assertTrue(engine.start())
            withTimeout(5_000) { engine.state.first { it.clientActive } }
            f.current.auth(AuthorizationPhase.WAITING_PARAMETERS)
            withTimeout(5_000) { engine.state.first { it.proxy == ProxyStatus.DIRECT } }
            f.current.auth(AuthorizationPhase.READY)
            withTimeout(5_000) { engine.state.first { it.settings.enabled } }
            block(engine, f)
        } finally {
            try {
                f.autoClose = true
                assertTrue(engine.close(timeoutMillis = 5_000))
            } finally {
                actorDispatcher.close()
                check(executor.awaitTermination(2, TimeUnit.SECONDS))
                val root = f.root.toAbsolutePath().normalize()
                Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { path ->
                    check(path.toAbsolutePath().normalize().startsWith(root))
                    Files.deleteIfExists(path)
                } }
            }
        }
    }

    private suspend fun TestScope.withEngine(block: suspend TestScope.(TdEngine, Fixture) -> Unit) {
        val f = Fixture()
        val engine = syntheticOpenEngine(f.store, f, StandardTestDispatcher(testScheduler))
        try {
            assertTrue(engine.start()); runCurrent()
            f.current.auth(AuthorizationPhase.WAITING_PARAMETERS); runCurrent()
            f.current.auth(AuthorizationPhase.READY); runCurrent()
            assertTrue(engine.state.value.settings.enabled)
            block(engine, f)
        } finally {
            f.autoClose = true
            val closing = async(start = CoroutineStart.UNDISPATCHED) { engine.close() }
            runCurrent()
            f.sessions.filter { !it.closed }.forEach { it.auth(AuthorizationPhase.CLOSED) }
            runCurrent()
            assertTrue(closing.await())
            val root = f.root.toAbsolutePath().normalize()
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { path ->
                check(path.toAbsolutePath().normalize().startsWith(root))
                Files.deleteIfExists(path)
            } }
        }
    }

    private class Fixture : TdTransport {
        val root = Files.createTempDirectory("proxy-checks-engine-")
        val store = ConfigurationStore(root.resolve("tdlib.properties"), root.resolve("data"))
        val physical = Physical()
        val sessions = CopyOnWriteArrayList<NativeSession>()
        val current: NativeSession get() = sessions.last()
        @Volatile var autoClose = true
        init { Files.writeString(store.path, "api_id=12345\napi_hash=${"a".repeat(32)}\n") }
        override fun load() = NativeInfo("synthetic", "synthetic", "synthetic")
        override fun create(onAuthorization: (AuthorizationDetails) -> Unit, onConnection: (ConnectionPhase) -> Unit,
            onError: () -> Unit, onChatEvent: (ChatEvent) -> Unit,
        ): TdSession = NativeSession(this, onAuthorization).also { sessions += it }
    }

    private class NativeSession(private val fixture: Fixture, private val authorization: (AuthorizationDetails) -> Unit) : SyntheticOpenKeySession {
        private val ready = AtomicBoolean()
        override val interactionGate = TdUserInteractionGate()
        override val settings = Settings(fixture.physical)
        @Volatile var closed = false
            private set
        fun auth(phase: AuthorizationPhase) {
            ready.set(phase == AuthorizationPhase.READY)
            if (phase == AuthorizationPhase.CLOSED) closed = true
            authorization(AuthorizationDetails(phase))
        }
        fun requireEmail() = interactionGate.update(LoginEmailSuggestions(1, requiredPresent = true, requiredEpoch = 1))
        fun satisfyEmail() = interactionGate.satisfyRequired(1)
        override fun authorizationReady() = ready.get()
        override fun setParameters(config: TdConfiguration, directories: DataDirectoryLease, result: (Int?) -> Unit) = result(null)
        override fun configureProxy(proxy: ProxyConfiguration?, result: (Int?) -> Unit) = result(null)
        override fun submitLogin(kind: LoginRequestKind, value: String, result: (LoginResult) -> Unit) = error("No login in synthetic proxy fixture")
        override fun close() { if (fixture.autoClose) auth(AuthorizationPhase.CLOSED) }
    }

    /** Counts actual synthetic transport submissions, independently of actor and visible state. */
    private class Physical {
        val outstanding = AtomicInteger()
        val maximum = AtomicInteger()
        fun sent() {
            val count = outstanding.incrementAndGet()
            maximum.updateAndGet { maxOf(it, count) }
        }
        fun returned() { check(outstanding.decrementAndGet() >= 0) }
    }

    private class NativeCall(
        val command: TdApi.Function<*>,
        private val result: (TdApi.Object) -> Unit,
        private val physical: Physical,
    ) {
        private val returned = AtomicBoolean()
        val pending: Boolean get() = !returned.get()
        fun reply(value: TdApi.Object) {
            if (returned.compareAndSet(false, true)) physical.returned()
            // Duplicate raw callbacks are intentional; the real probe/lease must deduplicate them.
            result(value)
        }
    }

    private class Probe(val expected: SettingsProxy, val lease: SettingsProxyProbeLease, private val settings: Settings) {
        val calls = CopyOnWriteArrayList<NativeCall>()
        @Volatile var beforeNativeSubmit: ((TdApi.Function<*>) -> Unit)? = null
        @Volatile var terminal = false
            private set
        val pending: NativeCall get() = calls.single { it.pending }
        fun start(physical: Physical, result: (SettingsResult<SettingsProxyProbe>) -> Unit) {
            val native = OfficialTdSettingsProxyProbe { command, reply, _ ->
                beforeNativeSubmit?.invoke(command)
                physical.sent()
                calls += NativeCall(command, reply, physical)
            }
            // The fixture supplies no account/actor gate; production Engine's captured lease owns it.
            native.probe(expected, lease, { true }) { terminal = true; result(it) }
        }
        fun complete(seconds: Double = 0.1) {
            repeat(3) {
                if (!terminal) {
                    val call = pending
                    call.reply(if (call.command is TdApi.PingProxy) TdApi.Seconds(seconds) else settings.nativeRows())
                }
            }
            assertTrue(terminal, "A probe must finish within its three serial native phases.")
        }
        fun unavailable() {
            pending.reply(settings.nativeRows())
            pending.reply(TdApi.Error(400, "synthetic unreachable"))
            pending.reply(settings.nativeRows())
        }
    }

    /** Uses the production serial storage -> proxy-list read and the same raw-request counter. */
    private class MetadataRead(val lease: SettingsProxyProbeLease, private val settings: Settings) {
        val calls = CopyOnWriteArrayList<NativeCall>()
        @Volatile var terminal = false
            private set
        val pending: NativeCall get() = calls.single { it.pending }
        fun start(physical: Physical, result: (SettingsResult<SettingsAdvancedData>) -> Unit) {
            val native = OfficialTdSettingsProxyChecksRead { command, reply, _ ->
                physical.sent()
                calls += NativeCall(command, reply, physical)
            }
            native.read(lease, { true }) { terminal = true; result(it) }
        }
        fun complete() {
            repeat(2) {
                if (!terminal) {
                    val call = pending
                    call.reply(when (call.command) {
                        is TdApi.GetStorageStatisticsFast -> TdApi.StorageStatisticsFast(12, 2, 4096, 0, 0)
                        is TdApi.GetProxies -> settings.nativeRows()
                        else -> error("Unexpected synthetic metadata command")
                    })
                }
            }
            assertTrue(terminal, "Metadata must finish within its two serial native phases.")
        }
    }

    /** Settings reads are immediate; every probe uses the real three-stage native state machine. */
    private class Settings(private val physical: Physical) : TdSettingsSession {
        data class ProxyEnable(val id: Int?, val reply: (SettingsResult<Unit>) -> Unit)
        val rows = (1..6).map { SettingsProxy(it, "proxy-$it.example", 1080, SettingsProxyType.SOCKS5, it == 1) } +
            SettingsProxy(7, "web.example", 443, SettingsProxyType.WEB, false)
        @Volatile var ordinaryAdvancedReads = 0
            private set
        @Volatile var beforeProfileRead: (() -> Unit)? = null
        val metadataReads = CopyOnWriteArrayList<MetadataRead>()
        val advancedReads: Int get() = ordinaryAdvancedReads + metadataReads.size
        val probes = CopyOnWriteArrayList<Probe>()
        val proxyEnables = CopyOnWriteArrayList<ProxyEnable>()
        val storageCalls = CopyOnWriteArrayList<NativeCall>()
        override val storage: TdSettingsStorageSession = OfficialTdSettingsStorage { command, reply, _ ->
            physical.sent()
            storageCalls += NativeCall(command, reply, physical)
        }
        override fun advanced(result: (SettingsResult<SettingsAdvancedData>) -> Unit) {
            ordinaryAdvancedReads++
            result(SettingsResult(SettingsAdvancedData(null, rows)))
        }
        override fun advancedForProxyChecks(lease: SettingsProxyProbeLease,
            result: (SettingsResult<SettingsAdvancedData>) -> Unit,
        ) {
            val read = MetadataRead(lease, this)
            metadataReads += read
            read.start(physical, result)
        }
        override fun probeProxy(expected: SettingsProxy, lease: SettingsProxyProbeLease,
            result: (SettingsResult<SettingsProxyProbe>) -> Unit,
        ) {
            val probe = Probe(expected, lease, this)
            probes += probe
            probe.start(physical, result)
        }
        fun nativeRows() = TdApi.AddedProxies(rows.map {
            TdApi.AddedProxy(it.id, 0, it.enabled, "", TdApi.Proxy(it.server, it.port,
                if (it.type == SettingsProxyType.WEB) TdApi.ProxyTypeWeb()
                else TdApi.ProxyTypeSocks5("synthetic-private-user", "synthetic-private-password")))
        }.toTypedArray())
        fun drainAll() { probes.filter { !it.terminal }.forEach { it.complete() } }
        override fun profile(result: (SettingsResult<SettingsProfile>) -> Unit) {
            beforeProfileRead?.invoke()
            unsupported(result)
        }
        override fun saveProfile(edit: SettingsProfileEdit, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun notifications(result: (SettingsResult<Map<NotificationScope, NotificationPreferences>>) -> Unit) = unsupported(result)
        override fun saveNotifications(scope: NotificationScope, value: NotificationPreferences, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun privacy(result: (SettingsResult<SettingsPrivacyData>) -> Unit) = unsupported(result)
        override fun savePrivacy(key: PrivacyKey, base: PrivacyBase, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun setAccountTtl(days: Int, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun terminateSession(id: Long, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun unblock(sender: SettingsBlockedSender, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun optimizeStorage(result: (SettingsResult<SettingsStorage>) -> Unit) = unsupported(result)
        override fun addProxy(draft: SettingsProxyDraft, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun enableProxy(id: Int?, result: (SettingsResult<Unit>) -> Unit) { proxyEnables += ProxyEnable(id, result) }
        override fun removeProxy(id: Int, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        private fun <T> unsupported(result: (SettingsResult<T>) -> Unit) = result(SettingsResult(error = SettingsError.UNSUPPORTED))
    }
}
