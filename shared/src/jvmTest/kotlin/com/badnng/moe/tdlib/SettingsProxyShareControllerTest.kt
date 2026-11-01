package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.*

/** Real actor-owned page controller with independently held synthetic physical callbacks. */
class SettingsProxyShareControllerTest {
    @Test
    fun shareWaitsForOneOfTwoActualProbeDrainsAndAutomaticChecksContinue() {
        val f = Fixture((1..4).map(::socks))
        assertEquals(2, f.probes.size)
        val outcomes = mutableListOf<SettingsResult<SettingsProxyShareContent>>()
        f.share(f.one(0), outcomes::add)
        assertTrue(f.shares.isEmpty(), "A user request must wait while both native slots are occupied.")
        f.probes[0].logical(SettingsResult(SettingsProxyProbe.Available(12)))
        f.flush()
        assertTrue(f.shares.isEmpty(), "Logical completion alone cannot release a physical slot.")
        f.probes[0].drain()
        f.flush()
        assertEquals(1, f.shares.size)
        assertEquals(2, f.outstanding())
        assertEquals(2, f.probes.size, "The explicit share runs before additional automatic checks.")
        f.probes[0].drain()
        f.flush()
        assertEquals(2, f.outstanding(), "A duplicate raw completion cannot release a newer owner's slot.")
        f.shares.single().complete(SettingsResult(listOf(PUBLIC_LINK)))
        f.flush()
        assertEquals(3, f.probes.size, "The existing automatic queue must resume after the share drains.")
        assertEquals(2, f.outstanding())
        assertNotNull(outcomes.single().value)
        assertFalse(outcomes.toString().contains(PUBLIC_LINK))
    }

    @Test
    fun exactPageTokenEpochAndRowAreRequiredBeforeNativeWork() {
        val rows = listOf(web(1), web(2))
        val f = Fixture(rows)
        val initial = f.one(0)
        val bad = listOf(
            initial.copy(token = initial.token.copy(owner = initial.token.owner + 1)),
            initial.copy(listEpoch = initial.listEpoch + 1),
            initial.copy(target = SettingsProxyShareTarget.One(1, rows[0])),
            initial.copy(target = SettingsProxyShareTarget.One(0, rows[0].copy(port = 1081))),
            initial.copy(target = SettingsProxyShareTarget.All(rows.reversed())),
            initial.copy(target = SettingsProxyShareTarget.All(rows), format = SettingsProxyShareFormat.INTERNAL),
        )
        for (request in bad) {
            val results = mutableListOf<SettingsResult<SettingsProxyShareContent>>()
            f.share(request, results::add)
            assertNull(results.single().value)
            assertNotNull(results.single().error)
        }
        assertTrue(f.shares.isEmpty())
        val wrongLease = SettingsProxyChecksPageLease { true }
        val results = mutableListOf<SettingsResult<SettingsProxyShareContent>>()
        f.controller.share(f.parent, wrongLease, initial, results::add)
        assertEquals(SettingsError.NOT_READY, results.single().error)
        assertTrue(f.shares.isEmpty())
    }

    @Test
    fun allTargetCopiesTheVisibleWitnessAndTheResultUsesSourceOrder() {
        val rows = listOf(web(1), web(2))
        val f = Fixture(rows)
        val mutableRows = rows.toMutableList()
        val request = f.one(0).copy(target = SettingsProxyShareTarget.All(mutableRows))
        val results = mutableListOf<SettingsResult<SettingsProxyShareContent>>()
        f.share(request, results::add)
        mutableRows.reverse()
        val task = f.shares.single()
        assertEquals(rows, assertIs<SettingsProxyShareTarget.All>(task.target).rows)
        val links = listOf(PUBLIC_LINK, "https://synthetic.invalid/second")
        task.complete(SettingsResult(links))
        f.flush()
        val content = assertNotNull(results.single().value)
        assertTrue(content.textIfCurrent() == links.joinToString("\n\n"))
        assertFalse(content.toString().contains(PUBLIC_LINK))
        assertFalse(f.snapshot.toString().contains(PUBLIC_LINK))
        content.close()
        assertNull(content.textIfCurrent())
    }

    @Test
    fun supersedingAndRefreshingRequestsInvalidateResultsWithoutReleasingAnInFlightSlot() {
        val f = Fixture(listOf(web(1)))
        val first = mutableListOf<SettingsResult<SettingsProxyShareContent>>()
        val second = mutableListOf<SettingsResult<SettingsProxyShareContent>>()
        f.share(f.one(0), first::add)
        val old = f.shares.single()
        f.share(f.one(0), second::add)
        assertEquals(SettingsError.NOT_READY, first.single().error)
        assertEquals(2, f.shares.size)
        val new = f.shares.last()
        assertEquals(2, f.outstanding())
        f.controller.invalidateAdvanced()
        assertEquals(SettingsError.NOT_READY, second.single().error)
        assertEquals(2, f.outstanding(), "Refresh cannot stand in for real native callbacks.")
        old.complete(SettingsResult(listOf(PUBLIC_LINK)))
        new.complete(SettingsResult(listOf(PUBLIC_LINK)))
        f.flush()
        assertEquals(0, f.outstanding())
        assertEquals(1, first.size)
        assertEquals(1, second.size)
        assertNull(first.single().value)
        assertNull(second.single().value)
    }

    @Test
    fun cancellationCallbackCanReopenAndStartANewEpochWithoutOldCleanupErasingIt() {
        val f = Fixture(listOf(web(1)))
        val oldToken = assertNotNull(f.token)
        val oldEpoch = f.snapshot.listEpoch
        val replacementPage = SettingsProxyChecksPageLease { true }
        var replacementToken: SettingsProxyChecksToken? = null
        val fresh = mutableListOf<SettingsResult<SettingsProxyShareContent>>()
        val old = mutableListOf<SettingsResult<SettingsProxyShareContent>>()
        f.share(f.one(0)) { result ->
            old += result
            if (result.error == SettingsError.NOT_READY) {
                f.controller.open(f.parent, replacementPage) { replacementToken = it }
                f.controller.advancedLoaded(1)
                val request = SettingsProxyShareRequest(assertNotNull(replacementToken), f.snapshot.listEpoch,
                    SettingsProxyShareTarget.One(0, f.source.proxies[0]), SettingsProxyShareFormat.PUBLIC)
                f.controller.share(f.parent, replacementPage, request, fresh::add)
            }
        }
        val oldPhysical = f.shares.single()
        f.controller.invalidateAdvanced()
        val newToken = assertNotNull(replacementToken)
        assertNotEquals(oldToken, newToken)
        assertEquals(newToken, f.snapshot.token)
        assertTrue(f.snapshot.listEpoch > oldEpoch)
        assertEquals(f.source.proxies, f.snapshot.rows.map { it.proxy })
        assertEquals(2, f.shares.size)
        oldPhysical.complete(SettingsResult(listOf(PUBLIC_LINK))); f.flush()
        assertEquals(1, old.size)
        assertTrue(fresh.isEmpty())
        f.shares.last().complete(SettingsResult(listOf(SECOND_PUBLIC_LINK))); f.flush()
        assertTrue(assertNotNull(fresh.single().value).textIfCurrent() == SECOND_PUBLIC_LINK)
        assertEquals(newToken, f.snapshot.token)
    }

    @Test
    fun closingAnOldContentCannotRevokeANewerShare() {
        val f = Fixture(listOf(web(1)))
        val first = mutableListOf<SettingsResult<SettingsProxyShareContent>>()
        f.share(f.one(0), first::add)
        f.shares.single().complete(SettingsResult(listOf(PUBLIC_LINK)))
        f.flush()
        val old = assertNotNull(first.single().value)
        old.close()
        val second = mutableListOf<SettingsResult<SettingsProxyShareContent>>()
        f.share(f.one(0), second::add)
        f.flush() // Execute the first content's queued cleanup after the second request exists.
        f.shares.last().complete(SettingsResult(listOf(SECOND_PUBLIC_LINK)))
        f.flush()
        assertNull(old.textIfCurrent())
        val current = assertNotNull(second.single().value)
        assertTrue(current.textIfCurrent() == SECOND_PUBLIC_LINK)
        assertTrue(current.useIfCurrent { throw IllegalStateException("synthetic clipboard failure") },
            "The return value records invocation; a throwing consumer is not reported as clipboard success.")
        assertTrue(current.textIfCurrent() == SECOND_PUBLIC_LINK)
    }

    @Test
    fun commitAndPageRevocationShareTheSameSynchronousBoundary() {
        val f = Fixture(listOf(web(1)))
        val results = mutableListOf<SettingsResult<SettingsProxyShareContent>>()
        f.share(f.one(0), results::add)
        f.shares.single().complete(SettingsResult(listOf(PUBLIC_LINK)))
        f.flush()
        val content = assertNotNull(results.single().value)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val revoked = CountDownLatch(1)
        val called = AtomicBoolean(false)
        val writer = thread {
            content.useIfCurrent { entered.countDown(); release.await(2, TimeUnit.SECONDS); called.set(true) }
        }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val closer = thread { f.page.revoke(); revoked.countDown() }
        try {
            assertFalse(revoked.await(100, TimeUnit.MILLISECONDS), "Revoke must wait for an admitted commit.")
        } finally {
            release.countDown(); writer.join(2000); closer.join(2000)
        }
        assertTrue(called.get())
        assertEquals(0L, revoked.count)
        assertNull(content.textIfCurrent())
        assertFalse(content.useIfCurrent { fail("A revoked owner must not commit") })
    }

    private class Fixture(rows: List<SettingsProxy>) {
        val parent = SettingsToken(9, 1)
        val page = SettingsProxyChecksPageLease { true }
        val probes = mutableListOf<Probe>()
        val shares = mutableListOf<Share>()
        val queued = ArrayDeque<() -> Unit>()
        var source = SettingsSnapshot(token = parent, enabled = true,
            loaded = setOf(SettingsSection.ADVANCED), proxies = rows)
        var snapshot = SettingsProxyChecksSnapshot()
        var token: SettingsProxyChecksToken? = null
        val controller = SettingsProxyChecksController(
            source = { source }, advancedRevision = { 1L },
            startProbe = { row, lease, result -> probes += Probe(row, lease, result) },
            startAdvancedRead = { _, _ -> error("No metadata read is started by this fixture") },
            budget = SettingsProxyProbeBudget(), enqueue = { queued.addLast(it) },
            publish = { snapshot = it; source = source.copy(proxyChecks = it) },
            requestAdvancedLoad = { _, _ -> }, advancedReadResult = { _, _, _ -> },
            advancedReadCancelled = { _, _ -> },
            startShare = { target, http, lease, result -> shares += Share(target, http, lease, result) },
        )
        init {
            controller.open(parent, page) { token = it }
            controller.advancedLoaded(1)
            assertNotNull(token)
        }
        fun one(index: Int) = SettingsProxyShareRequest(assertNotNull(token), snapshot.listEpoch,
            SettingsProxyShareTarget.One(index, source.proxies[index]), SettingsProxyShareFormat.PUBLIC)
        fun share(request: SettingsProxyShareRequest, result: (SettingsResult<SettingsProxyShareContent>) -> Unit) =
            controller.share(parent, page, request, result)
        fun flush() { while (queued.isNotEmpty()) queued.removeFirst().invoke() }
        fun outstanding() = probes.count { !it.drained } + shares.count { !it.drained }
    }

    private abstract class Held(private val lease: SettingsProxyProbeLease) {
        private val stage: SettingsProxyProbeLease.Stage
        var drained = false
            private set
        init { check(lease.beginProbe()); stage = checkNotNull(lease.beginStage()) }
        fun drain() {
            if (!drained) { drained = true; lease.endStage(stage); lease.finishProbe() }
        }
    }

    private class Probe(val row: SettingsProxy, lease: SettingsProxyProbeLease,
        private val reply: (SettingsResult<SettingsProxyProbe>) -> Unit,
    ) : Held(lease) {
        fun logical(value: SettingsResult<SettingsProxyProbe>) = reply(value)
    }

    private class Share(val target: SettingsProxyShareTarget, val http: Boolean, lease: SettingsProxyProbeLease,
        private val reply: (SettingsResult<List<String>>) -> Unit,
    ) : Held(lease) {
        fun complete(value: SettingsResult<List<String>>) { drain(); reply(value) }
    }

    private companion object {
        const val PUBLIC_LINK = "https://synthetic.invalid/first"
        const val SECOND_PUBLIC_LINK = "https://synthetic.invalid/second"
        fun socks(id: Int) = SettingsProxy(id, "proxy-$id.invalid", 1080, SettingsProxyType.SOCKS5, false)
        fun web(id: Int) = SettingsProxy(id, "proxy-$id.invalid", 1080, SettingsProxyType.WEB, false)
    }
}
