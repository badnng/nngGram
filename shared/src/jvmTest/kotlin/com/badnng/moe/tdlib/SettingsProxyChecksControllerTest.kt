package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.*

/** Actor integration with a held physical stage; no TDLib client, account or network. */
class SettingsProxyChecksControllerTest {
    @Test fun everyPageRowIsScheduledWithTwoPhysicalSlotsAndWebIsNeverProbed() {
        val f = Fixture()
        val owner = f.open()
        val rows = (1..256).map { proxy(it, if (it == 8) SettingsProxyType.WEB else SettingsProxyType.SOCKS5) }
        f.resolve(rows)
        assertEquals(owner.token, f.state.proxyChecks.token)
        assertEquals(rows, f.state.proxyChecks.rows.map { it.proxy })
        assertEquals(2, f.session.requests.size)
        assertEquals(2, f.session.outstanding())
        assertEquals(SettingsProxyCheckStatus.NotTested, f.state.proxyChecks.rows[7].status)

        var answered = 0
        while (answered < rows.count { it.type != SettingsProxyType.WEB }) {
            val request = f.session.requests[answered++]
            request.complete(SettingsResult(SettingsProxyProbe.Available(request.row.id.toLong())))
            f.flush()
            assertTrue(f.session.outstanding() <= 2)
        }
        assertEquals(rows.filter { it.type != SettingsProxyType.WEB }.map { it.id }, f.session.requests.map { it.row.id })
        assertTrue(f.state.proxyChecks.rows.filter { it.proxy.type != SettingsProxyType.WEB }.all {
            it.status == SettingsProxyCheckStatus.Available(it.proxy.id.toLong())
        })
    }

    @Test fun oldPageKeepsRealSlotsUntilDrainAndCannotCloseOrPublishIntoReopenedPage() {
        val f = Fixture()
        val first = f.open()
        val rows = (1..4).map { proxy(it) }
        f.resolve(rows)
        val old = f.session.requests.toList()
        assertEquals(2, f.session.outstanding())
        first.lease.revoke()
        f.controller.closeProxyChecks(f.token, first.lease)
        val second = f.open()
        assertNotEquals(first.token, second.token)
        assertEquals(1, f.session.reads.size, "A new page read must wait for a physical slot.")
        assertEquals(2, f.session.requests.size, "Closing is not native completion.")
        f.controller.closeProxyChecks(f.token, first.lease)
        assertEquals(second.token, f.state.proxyChecks.token)

        old[0].complete(SettingsResult(SettingsProxyProbe.Available(999)))
        f.flush()
        assertEquals(2, f.session.reads.size)
        assertEquals(2, f.session.outstanding(), "One old probe and the metadata read own both slots.")
        f.resolve(rows)
        assertEquals(3, f.session.requests.size)
        assertEquals(SettingsProxyCheckStatus.Checking, f.state.proxyChecks.rows[0].status)
        old[0].complete(SettingsResult(SettingsProxyProbe.Available(777)))
        f.flush()
        assertEquals(3, f.session.requests.size, "A duplicate old callback cannot release a replacement slot.")
        assertEquals(2, f.session.outstanding())
        old[1].complete(SettingsResult(SettingsProxyProbe.Unavailable))
        f.flush()
        assertEquals(4, f.session.requests.size)
        assertTrue(f.state.proxyChecks.rows.all { it.status == SettingsProxyCheckStatus.Checking })
    }

    @Test fun refreshAndMutationRevokeTheOldListBeforeLateLogicalAndPhysicalResults() {
        val f = Fixture()
        f.open()
        val first = listOf(proxy(1), proxy(2), proxy(3))
        f.resolve(first)
        val old = f.session.requests.toList()
        old[0].logical(SettingsResult(SettingsProxyProbe.Available(901)))
        f.controller.load(f.token, SettingsSection.ADVANCED)
        assertTrue(f.state.proxyChecks.rows.isEmpty())
        f.flush()
        assertTrue(f.state.proxyChecks.rows.isEmpty(), "A result already queued before refresh is still stale.")
        val replacement = listOf(proxy(1, server = "replacement.invalid"), proxy(4))
        assertEquals(2, f.session.requests.size)
        old[0].drain()
        f.flush()
        assertEquals(2, f.session.reads.size)
        f.resolve(replacement)
        assertEquals("replacement.invalid", f.session.requests.last().row.server)
        old[1].complete(SettingsResult(SettingsProxyProbe.Available(902)))
        f.flush()
        assertTrue(f.state.proxyChecks.rows.none { it.status == SettingsProxyCheckStatus.Available(901) ||
            it.status == SettingsProxyCheckStatus.Available(902) })

        f.controller.enableProxy(f.token, 4)
        assertTrue(f.state.proxyChecks.rows.isEmpty(), "Mutation admission revokes the current list before native work.")
        assertEquals(1, f.session.enables.size)
    }

    @Test fun sourceAndAmbiguousErrorsStayNotTestedWhileActualProbeFailureIsUnavailable() {
        val f = Fixture()
        f.open()
        val rows = listOf(proxy(1), proxy(2), proxy(3), proxy(3), proxy(5, SettingsProxyType.WEB))
        f.resolve(rows)
        assertEquals(listOf(1, 2), f.session.requests.map { it.row.id })
        assertEquals(SettingsProxyCheckStatus.NotTested, f.state.proxyChecks.rows[2].status)
        assertEquals(SettingsError.CONFLICT, f.state.proxyChecks.rows[2].error)
        assertEquals(SettingsProxyCheckStatus.NotTested, f.state.proxyChecks.rows[4].status)
        assertNull(f.state.proxyChecks.rows[4].error)
        f.session.requests[0].complete(SettingsResult(error = SettingsError.CONFLICT))
        f.session.requests[1].complete(SettingsResult(SettingsProxyProbe.Unavailable))
        f.flush()
        assertEquals(SettingsProxyCheckStatus.NotTested, f.state.proxyChecks.rows[0].status)
        assertEquals(SettingsError.CONFLICT, f.state.proxyChecks.rows[0].error)
        assertEquals(SettingsProxyCheckStatus.Unavailable, f.state.proxyChecks.rows[1].status)

        f.controller.load(f.token, SettingsSection.ADVANCED)
        f.session.reads.last().complete(SettingsResult(error = SettingsError.REQUEST_FAILED))
        f.flush()
        assertTrue(f.state.proxyChecks.rows.isEmpty())
        assertEquals(SettingsError.REQUEST_FAILED, f.state.errors[SettingsSection.ADVANCED])
        assertEquals(2, f.session.requests.size, "A failed fresh section read must not retry or reuse cached rows.")
    }

    @Test fun acceptedProxyWriteReadbackUsesTheSamePhysicalBudgetAndCloseNeverClaimsConfirmation() {
        val f = Fixture()
        val page = f.open()
        f.resolve(listOf(proxy(1), proxy(2), proxy(3)))
        val old = f.session.requests.toList()
        f.controller.enableProxy(f.token, 1)
        f.session.enableResults.single()(SettingsResult(Unit))
        f.flush()
        assertTrue(SettingsSection.ADVANCED in f.state.saving)
        assertEquals(1, f.session.reads.size, "Readback cannot bypass two held probe slots.")
        old[0].drain()
        f.flush()
        assertEquals(2, f.session.reads.size)
        assertEquals(2, f.session.outstanding())
        page.lease.revoke()
        f.controller.closeProxyChecks(f.token, page.lease)
        assertTrue(SettingsSection.ADVANCED !in f.state.saving)
        assertEquals(SettingsError.NOT_READY, f.state.errors[SettingsSection.ADVANCED])
        assertNull(f.state.failedSaveRevision[SettingsSection.ADVANCED], "An accepted write is pending readback, not a failed native write.")
        assertNotNull(f.state.startedSaveRevision[SettingsSection.ADVANCED])
        f.session.reads.last().complete(SettingsResult(SettingsAdvancedData(null, listOf(proxy(99)))))
        f.flush()
        assertEquals(listOf(1, 2, 3), f.state.proxies.map { it.id })
        assertTrue(f.state.completedSaveRevision[SettingsSection.ADVANCED] == null)
    }

    @Test fun closeBeforeProxyWriteAckCannotFallBackToUnbudgetedReadback() {
        val f = Fixture()
        val page = f.open()
        f.resolve(listOf(proxy(1), proxy(2), proxy(3)))
        val old = f.session.requests.toList()
        f.controller.enableProxy(f.token, 1)
        page.lease.revoke()
        f.controller.closeProxyChecks(f.token, page.lease)
        assertTrue(SettingsSection.ADVANCED in f.state.saving)
        f.session.enableResults.single()(SettingsResult(Unit))
        f.flush()
        assertTrue(f.session.ordinaryReads.isEmpty(), "An old page ACK must not start the unbudgeted aggregate read.")
        assertEquals(1, f.session.reads.size)
        assertEquals(2, f.session.outstanding())
        assertTrue(SettingsSection.ADVANCED !in f.state.saving)
        assertEquals(SettingsError.NOT_READY, f.state.errors[SettingsSection.ADVANCED])
        assertNull(f.state.failedSaveRevision[SettingsSection.ADVANCED], "Page departure cannot turn a real acceptance into failure.")
        assertNotNull(f.state.startedSaveRevision[SettingsSection.ADVANCED])
        assertNull(f.state.completedSaveRevision[SettingsSection.ADVANCED])

        f.open()
        assertEquals(1, f.session.reads.size, "The new page metadata must wait for old physical callbacks.")
        old.first().drain()
        f.flush()
        assertEquals(2, f.session.reads.size)
        assertEquals(2, f.session.outstanding())
    }

    @Test fun revokedPageStillIdentifiesAUserWriteQueuedBeforeItsCleanup() {
        val f = Fixture()
        val page = f.open()
        f.resolve(listOf(proxy(1), proxy(2)))
        assertEquals(2, f.session.outstanding())
        // UI queued the write, then synchronously revoked the page before actor delivery;
        // the corresponding actor cleanup is still behind this write in the queue.
        page.lease.revoke()
        f.controller.enableProxy(f.token, 1)
        assertEquals(listOf<Int?>(1), f.session.enables)
        f.controller.closeProxyChecks(f.token, page.lease)
        f.session.enableResults.single()(SettingsResult(Unit))
        f.flush()
        assertTrue(f.session.ordinaryReads.isEmpty())
        assertEquals(1, f.session.reads.size)
        assertEquals(2, f.session.outstanding())
        assertEquals(SettingsError.NOT_READY, f.state.errors[SettingsSection.ADVANCED])
        assertNull(f.state.failedSaveRevision[SettingsSection.ADVANCED], "Retain the original accepted revision for a new page.")
        assertNotNull(f.state.startedSaveRevision[SettingsSection.ADVANCED])
        assertNull(f.state.completedSaveRevision[SettingsSection.ADVANCED])
    }

    @Test fun stalePageRefreshCannotStartMetadataOrRetireItsReplacement() {
        val f = Fixture()
        val old = f.open()
        f.resolve(listOf(proxy(1)))
        old.lease.revoke()
        f.controller.closeProxyChecks(f.token, old.lease)
        val replacement = f.open()
        val count = f.session.reads.size
        f.controller.refreshProxyChecks(f.token, old.lease)
        assertEquals(count, f.session.reads.size)
        assertEquals(replacement.token, f.state.proxyChecks.token)
    }

    @Test fun identicalListAfterRefreshIsANewEpochAndAnOldResultCannotPopulateIt() {
        val f = Fixture()
        f.open()
        val rows = listOf(proxy(1), proxy(2), proxy(3))
        f.resolve(rows)
        val oldEpoch = f.state.proxyChecks.listEpoch
        val old = f.session.requests.toList()
        f.controller.load(f.token, SettingsSection.ADVANCED)
        assertEquals(2, f.session.requests.size)
        old[0].complete(SettingsResult(SettingsProxyProbe.Available(555)))
        f.flush()
        f.resolve(rows)
        assertNotEquals(oldEpoch, f.state.proxyChecks.listEpoch)
        assertEquals(3, f.session.requests.size)
        assertTrue(f.state.proxyChecks.rows.none { it.status == SettingsProxyCheckStatus.Available(555) })
    }

    @Test fun reopeningDoesNotAdoptARevokedPagesPendingRead() {
        val f = Fixture()
        val first = f.open()
        assertEquals(1, f.session.reads.size)
        first.lease.revoke()
        f.controller.closeProxyChecks(f.token, first.lease)
        val second = f.open()
        assertNotEquals(first.token, second.token)
        assertEquals(2, f.session.reads.size)
        f.resolve(listOf(proxy(1), proxy(2), proxy(3)))
        assertEquals(second.token, f.state.proxyChecks.token)
        assertEquals(listOf(1), f.session.requests.map { it.row.id })
        f.session.reads.first().complete(SettingsResult(SettingsAdvancedData(null, listOf(proxy(99)))))
        f.flush()
        assertEquals(listOf(1, 2), f.session.requests.map { it.row.id })
        assertEquals(listOf(1, 2, 3), f.state.proxies.map { it.id })
    }

    @Test fun legacyUnsupportedSessionDrainsEveryPermitAndReportsNotTested() {
        val f = Fixture()
        f.session.defaultUnsupported = true
        f.open()
        f.resolve((1..5).map { proxy(it) })
        f.flush()
        assertTrue(f.session.requests.isEmpty())
        assertTrue(f.state.proxyChecks.rows.all { it.status == SettingsProxyCheckStatus.NotTested &&
            it.error == SettingsError.UNSUPPORTED })
    }

    @Test fun pageCloseSynchronouslyRevokesTheSharedDispatchOwner() {
        val page = SettingsProxyChecksPageLease { true }
        val owner = SettingsProxyProbeOwner { page.current() }
        assertTrue(page.attach(owner))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closeStarted = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val dispatched = AtomicBoolean()
        val worker = thread {
            dispatched.set(owner.newLease().dispatch {
                entered.countDown()
                check(release.await(2, TimeUnit.SECONDS))
            })
        }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val closer = thread { closeStarted.countDown(); page.revoke(); closed.countDown() }
        assertTrue(closeStarted.await(2, TimeUnit.SECONDS))
        try {
            assertFalse(closed.await(50, TimeUnit.MILLISECONDS), "close waits for a dispatch already in progress")
        } finally {
            release.countDown()
            worker.join()
            closer.join()
        }
        assertTrue(closed.await(2, TimeUnit.SECONDS))
        assertTrue(dispatched.get())
        assertFalse(owner.newLease().dispatch { error("A closed page cannot dispatch") })
    }

    @Test fun synchronousCloseDuringLoadingPublicationCannotStartThePageRead() {
        val session = FakeSession()
        val work = ArrayDeque<() -> Unit>()
        var state = SettingsSnapshot()
        lateinit var page: SettingsProxyChecksPageLease
        var closeDuringLoading = false
        val controller = SettingsController(7, session, enqueue = { work.addLast(it) }, publish = {
            state = it
            if (closeDuringLoading && SettingsSection.ADVANCED in it.loading) {
                closeDuringLoading = false
                page.revoke()
            }
        })
        controller.start()
        page = SettingsProxyChecksPageLease { true }
        closeDuringLoading = true
        controller.openProxyChecks(state.token, page) { assertNotNull(it) }
        assertTrue(session.reads.isEmpty(), "No page-owned native request may start after close returned.")
        assertTrue(SettingsSection.ADVANCED !in state.loading)
    }

    @Test fun newerReentrantLoadSurvivesTheOldPendingReadCancellation() {
        val f = Fixture()
        f.open()
        assertEquals(1, f.session.reads.size)
        var retried = false
        f.onPublish = { state ->
            if (!retried && SettingsSection.ADVANCED in state.loading && state.proxyChecks.rows.isEmpty()) {
                retried = true
                f.controller.load(f.token, SettingsSection.ADVANCED)
            }
        }
        f.controller.load(f.token, SettingsSection.ADVANCED)
        assertTrue(retried)
        assertEquals(2, f.session.reads.size, "The nested newer read retains its own physical slot.")
        assertTrue(SettingsSection.ADVANCED in f.state.loading)
        f.resolve(listOf(proxy(2)))
        assertEquals(listOf(2), f.state.proxies.map { it.id })
        assertEquals(listOf(2), f.session.requests.map { it.row.id })
        f.session.reads.first().complete(SettingsResult(SettingsAdvancedData(null, listOf(proxy(99)))))
        f.flush()
        assertEquals(listOf(2), f.state.proxies.map { it.id })
    }

    @Test fun pageOpenedFromClosePublicationGetsItsOwnReadAfterOldLoadingClears() {
        val f = Fixture()
        val first = f.open()
        var reopened: Opened? = null
        var once = false
        f.onPublish = { state ->
            if (!once && state.proxyChecks.token == null) {
                once = true
                reopened = f.open()
            }
        }
        first.lease.revoke()
        f.controller.closeProxyChecks(f.token, first.lease)
        val next = assertNotNull(reopened)
        assertEquals(next.token, f.state.proxyChecks.token)
        assertEquals(2, f.session.reads.size)
        f.resolve(listOf(proxy(8)))
        assertEquals(listOf(8), f.state.proxies.map { it.id })
        f.session.reads.first().complete(SettingsResult(SettingsAdvancedData(null, listOf(proxy(99)))))
        f.flush()
        assertEquals(listOf(8), f.state.proxies.map { it.id })
    }

    @Test fun retryFromFailurePublicationKeepsTheNewManagedRead() {
        val f = Fixture()
        f.open()
        var retried = false
        f.onPublish = { state ->
            if (!retried && state.errors[SettingsSection.ADVANCED] == SettingsError.REQUEST_FAILED) {
                retried = true
                f.controller.load(f.token, SettingsSection.ADVANCED)
            }
        }
        f.session.reads.first().complete(SettingsResult(error = SettingsError.REQUEST_FAILED))
        f.flush()
        assertTrue(retried)
        assertEquals(2, f.session.reads.size)
        assertTrue(SettingsSection.ADVANCED in f.state.loading)
        f.resolve(listOf(proxy(3)))
        assertEquals(listOf(3), f.state.proxies.map { it.id })
        assertEquals(listOf(3), f.session.requests.map { it.row.id })
    }

    @Test fun newerReentrantLoadPreventsTheOlderMutationFromBeingSubmitted() {
        val f = Fixture()
        f.open()
        f.resolve(listOf(proxy(1), proxy(2)))
        val old = f.session.requests.toList()
        var reloaded = false
        f.onPublish = { state ->
            if (!reloaded && state.proxyChecks.rows.isEmpty() && SettingsSection.ADVANCED !in state.loading &&
                SettingsSection.ADVANCED !in state.saving) {
                reloaded = true
                f.controller.load(f.token, SettingsSection.ADVANCED)
            }
        }
        f.controller.enableProxy(f.token, 1)
        assertTrue(reloaded)
        assertTrue(f.session.enables.isEmpty())
        assertTrue(SettingsSection.ADVANCED in f.state.loading)
        old.first().drain()
        f.flush()
        f.resolve(listOf(proxy(1), proxy(2)))
        assertTrue(f.session.enables.isEmpty())
    }

    @Test fun sharedBudgetSurvivesControllerRetirement() {
        val budget = SettingsProxyProbeBudget(2)
        val older = Fixture(generation = 7, budget = budget)
        val gate = AtomicBoolean(true)
        older.open(gate::get)
        older.resolve(listOf(proxy(1), proxy(2)))
        val old = older.session.requests.toList()
        var witnessed = false
        thread { witnessed = old[0].lease.current() }.join()
        assertTrue(witnessed)
        older.controller.deactivate()
        gate.set(false)

        val newer = Fixture(generation = 8, budget = budget)
        newer.open()
        assertTrue(newer.session.reads.isEmpty(), "A new page read also waits for old physical slots.")
        assertTrue(newer.session.requests.isEmpty(), "A new controller does not reset old outstanding native work.")
        old[0].complete(SettingsResult(SettingsProxyProbe.Available(5)))
        newer.flush()
        assertEquals(1, newer.session.reads.size)
        newer.resolve(listOf(proxy(7), proxy(8)))
        assertEquals(listOf(7), newer.session.requests.map { it.row.id })
        old[1].complete(SettingsResult(SettingsProxyProbe.Available(6)))
        newer.flush()
        assertEquals(listOf(7, 8), newer.session.requests.map { it.row.id })
        older.flush()
        assertNull(older.state.proxyChecks.token)
    }

    @Test fun workerWitnessNeverCallsTheActorOnlySourceSupplier() {
        val actorThread = Thread.currentThread()
        var sourceReads = 0
        val session = FakeSession()
        val token = SettingsToken(9, 1)
        var state = SettingsSnapshot(token = token, enabled = true, loaded = setOf(SettingsSection.ADVANCED),
            proxies = listOf(proxy(1)))
        val work = ArrayDeque<() -> Unit>()
        val controller = SettingsProxyChecksController(
            source = {
                check(Thread.currentThread() === actorThread) { "Actor-only source read on worker" }
                sourceReads++
                state
            },
            advancedRevision = { 1L }, startProbe = session::probeProxy,
            startAdvancedRead = session::advancedForProxyChecks,
            budget = SettingsProxyProbeBudget(), enqueue = { work.addLast(it) },
            publish = { state = state.copy(proxyChecks = it) }, requestAdvancedLoad = { _, _ -> },
            advancedReadResult = { _, _, _ -> }, advancedReadCancelled = { _, _ -> },
        )
        controller.open(token, SettingsProxyChecksPageLease { true }) { assertNotNull(it) }
        controller.advancedLoaded(1)
        val readsBefore = sourceReads
        var witnessed = false
        thread { witnessed = session.requests.single().lease.current() }.join()
        assertTrue(witnessed)
        assertEquals(readsBefore, sourceReads)
    }

    @Test fun aThrowingSenderCannotPretendItsPossiblySubmittedOperationDrained() {
        val f = Fixture()
        f.session.throwAfterBegin = true
        f.open()
        f.resolve(listOf(proxy(1), proxy(2), proxy(3)))
        f.flush()
        assertEquals(2, f.session.requests.size)
        assertTrue(f.state.proxyChecks.rows.take(2).all { it.status == SettingsProxyCheckStatus.NotTested })
        assertEquals(SettingsProxyCheckStatus.Checking, f.state.proxyChecks.rows[2].status)
        f.session.requests[0].drain()
        f.flush()
        assertEquals(3, f.session.requests.size)
    }

    @Test fun uncertainMetadataSendRetainsItsSlotAcrossPagesUntilRealDrain() {
        val f = Fixture()
        f.session.throwMetadataAfterBegin = true
        val first = f.open()
        f.flush()
        first.lease.revoke()
        f.controller.closeProxyChecks(f.token, first.lease)
        val second = f.open()
        f.flush()
        second.lease.revoke()
        f.controller.closeProxyChecks(f.token, second.lease)
        f.open()
        f.flush()
        assertEquals(2, f.session.reads.size, "Two ambiguous metadata sends keep the shared budget full.")
        assertEquals(2, f.session.outstanding())
        f.session.reads.first().complete(SettingsResult(error = SettingsError.REQUEST_FAILED))
        f.flush()
        assertEquals(3, f.session.reads.size, "Only a real old stage callback permits the waiting page read.")
        assertEquals(2, f.session.outstanding())
    }

    private class Fixture(generation: Long = 7, budget: SettingsProxyProbeBudget = SettingsProxyProbeBudget()) {
        val session = FakeSession()
        private val work = ArrayDeque<() -> Unit>()
        var state = SettingsSnapshot()
        var onPublish: ((SettingsSnapshot) -> Unit)? = null
        val controller = SettingsController(generation, session,
            enqueue = { work.addLast(it) },
            publish = { state = it; onPublish?.invoke(it) },
            proxyProbeBudget = budget)
        val token get() = state.token

        init { controller.start() }

        fun open(valid: () -> Boolean = { true }): Opened {
            val page = SettingsProxyChecksPageLease(valid)
            var received: SettingsProxyChecksToken? = null
            controller.openProxyChecks(token, page) { received = it }
            return Opened(page, assertNotNull(received))
        }

        fun resolve(rows: List<SettingsProxy>) {
            assertTrue(session.reads.isNotEmpty())
            session.reads.last().complete(SettingsResult(SettingsAdvancedData(null, rows)))
            flush()
        }

        fun flush() { while (work.isNotEmpty()) work.removeFirst().invoke() }
    }

    private data class Opened(val lease: SettingsProxyChecksPageLease, val token: SettingsProxyChecksToken)

    private class FakeSession : TdSettingsSession {
        val reads = mutableListOf<AdvancedRequest>()
        val ordinaryReads = mutableListOf<(SettingsResult<SettingsAdvancedData>) -> Unit>()
        val requests = mutableListOf<Request>()
        val enables = mutableListOf<Int?>()
        val enableResults = mutableListOf<(SettingsResult<Unit>) -> Unit>()
        var throwAfterBegin = false
        var throwMetadataAfterBegin = false
        var defaultUnsupported = false

        override fun advanced(result: (SettingsResult<SettingsAdvancedData>) -> Unit) { ordinaryReads += result }
        override fun advancedForProxyChecks(lease: SettingsProxyProbeLease, result: (SettingsResult<SettingsAdvancedData>) -> Unit) {
            check(lease.beginProbe())
            val stage = checkNotNull(lease.beginStage())
            val sent = lease.dispatch { reads += AdvancedRequest(lease, stage, result) }
            if (!sent) {
                lease.endStage(stage); lease.finishProbe()
                result(SettingsResult(error = SettingsError.NOT_READY))
            } else if (throwMetadataAfterBegin) error("synthetic uncertain metadata dispatch")
        }
        override fun probeProxy(expected: SettingsProxy, lease: SettingsProxyProbeLease,
            result: (SettingsResult<SettingsProxyProbe>) -> Unit,
        ) {
            if (defaultUnsupported) { super<TdSettingsSession>.probeProxy(expected, lease, result); return }
            check(lease.beginProbe())
            val stage = checkNotNull(lease.beginStage())
            val sent = lease.dispatch { requests += Request(expected, lease, stage, result) }
            if (!sent) {
                lease.endStage(stage); lease.finishProbe()
                result(SettingsResult(error = SettingsError.NOT_READY))
            } else if (throwAfterBegin) error("synthetic uncertain dispatch")
        }
        fun outstanding() = requests.count { !it.drained.get() } + reads.count { !it.drained.get() }
        override fun enableProxy(id: Int?, result: (SettingsResult<Unit>) -> Unit) { enables += id; enableResults += result }
        override fun profile(result: (SettingsResult<SettingsProfile>) -> Unit) = unsupported(result)
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
        override fun removeProxy(id: Int, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        private fun <T> unsupported(result: (SettingsResult<T>) -> Unit) { result(SettingsResult(error = SettingsError.UNSUPPORTED)) }
    }

    private class AdvancedRequest(private val lease: SettingsProxyProbeLease,
        private val stage: SettingsProxyProbeLease.Stage, private val result: (SettingsResult<SettingsAdvancedData>) -> Unit,
    ) {
        val drained = AtomicBoolean()
        fun complete(value: SettingsResult<SettingsAdvancedData>) {
            if (drained.compareAndSet(false, true)) { lease.endStage(stage); lease.finishProbe() }
            result(value)
        }
    }

    private class Request(val row: SettingsProxy, val lease: SettingsProxyProbeLease,
        private val stage: SettingsProxyProbeLease.Stage, private val result: (SettingsResult<SettingsProxyProbe>) -> Unit,
    ) {
        val drained = AtomicBoolean()
        fun logical(value: SettingsResult<SettingsProxyProbe>) = result(value)
        fun drain() {
            if (drained.compareAndSet(false, true)) { lease.endStage(stage); lease.finishProbe() }
        }
        fun complete(value: SettingsResult<SettingsProxyProbe>) { drain(); result(value) }
    }

    private companion object {
        fun proxy(id: Int, type: SettingsProxyType = SettingsProxyType.SOCKS5, server: String = "proxy-$id.invalid") =
            SettingsProxy(id, server, 1080, type, enabled = false)
    }
}
