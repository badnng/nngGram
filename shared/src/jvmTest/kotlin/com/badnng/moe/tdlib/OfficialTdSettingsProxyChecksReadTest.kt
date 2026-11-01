package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Fake native callbacks only; no account, client, network or system resources. */
class OfficialTdSettingsProxyChecksReadTest {
    @Test
    fun officialSessionReadsSeriallyAndDrainsBeforeDeliveringRedactedData() {
        val f = Fixture()
        val events = mutableListOf<String>()
        val results = mutableListOf<SettingsResult<SettingsAdvancedData>>()
        val lease = SettingsProxyProbeLease({ true }) { events += "drained" }
        f.session.advancedForProxyChecks(lease) { results += it; events += "result" }
        assertIs<TdApi.GetStorageStatisticsFast>(f.calls.single().command)
        assertEquals(1, f.outstanding.get())
        f.calls[0].reply(storage())
        assertIs<TdApi.GetProxies>(f.calls[1].command)
        assertTrue(results.isEmpty())
        assertEquals(1, f.outstanding.get())
        f.calls[1].reply(proxies())
        val value = assertNotNull(results.single().value)
        assertEquals(SettingsStorage(12, 2, 4096), value.storage)
        assertEquals(listOf(SettingsProxy(7, "proxy.example", 1080, SettingsProxyType.SOCKS5, false)), value.proxies)
        assertEquals(listOf("drained", "result"), events)
        assertEquals(0, f.outstanding.get())
        assertEquals(1, f.maximum.get())
        assertFalse(results.toString().contains("private-pass"))
    }

    @Test
    fun normalErrorsKeepTheExistingAdvancedAggregationAndNeverExposeNativeText() {
        val f = Fixture(); val results = mutableListOf<SettingsResult<SettingsAdvancedData>>()
        val lease = SettingsProxyProbeLease({ true })
        f.session.advancedForProxyChecks(lease, results::add)
        f.calls[0].reply(TdApi.Error(400, "private-storage"))
        assertIs<TdApi.GetProxies>(f.calls[1].command)
        f.calls[1].reply(proxies())
        assertEquals(SettingsError.INVALID_VALUE, results.single().error)
        assertFalse(results.toString().contains("private-storage"))

        val g = Fixture(); val errors = mutableListOf<SettingsResult<SettingsAdvancedData>>()
        val otherLease = SettingsProxyProbeLease({ true })
        g.session.advancedForProxyChecks(otherLease, errors::add)
        g.calls[0].reply(TdApi.Error(400, "private-storage"))
        g.calls[1].reply(TdApi.Error(429, "private-proxy"))
        assertEquals(SettingsError.RATE_LIMITED, errors.single().error)
        assertFalse(errors.toString().contains("private-proxy"))
    }

    @Test
    fun revocationAtEitherStagePreventsFollowupsAndStalePublication() {
        for (after in 0..1) {
            val f = Fixture(); val results = mutableListOf<SettingsResult<SettingsAdvancedData>>()
            val drains = AtomicInteger(); val owner = SettingsProxyProbeOwner { true }
            val lease = owner.newLease(drains::incrementAndGet)
            f.session.advancedForProxyChecks(lease, results::add)
            if (after == 0) {
                owner.revoke(); f.calls[0].reply(storage())
                assertEquals(1, f.calls.size)
            } else {
                f.calls[0].reply(storage()); owner.revoke(); f.calls[1].reply(proxies())
                assertEquals(2, f.calls.size)
            }
            assertEquals(SettingsError.NOT_READY, results.single().error)
            assertNull(results.single().value)
            assertEquals(1, drains.get())
            assertEquals(0, f.outstanding.get())
        }
    }

    @Test
    fun closeWaitsForCommittedSubmitThenDeniesTheNextStage() {
        val entered = CountDownLatch(1); val resume = CountDownLatch(1)
        val attempted = CountDownLatch(1); val closed = CountDownLatch(1)
        val f = Fixture { command, _ ->
            if (command is TdApi.GetStorageStatisticsFast) {
                entered.countDown(); check(resume.await(2, TimeUnit.SECONDS))
            }
        }
        val owner = SettingsProxyProbeOwner { true }; val lease = owner.newLease()
        val results = CopyOnWriteArrayList<SettingsResult<SettingsAdvancedData>>()
        val sender = Thread { f.session.advancedForProxyChecks(lease, results::add) }
        sender.start(); assertTrue(entered.await(2, TimeUnit.SECONDS))
        val closer = Thread { attempted.countDown(); owner.revoke(); closed.countDown() }
        closer.start()
        try {
            assertTrue(attempted.await(2, TimeUnit.SECONDS))
            assertFalse(closed.await(100, TimeUnit.MILLISECONDS))
        } finally { resume.countDown(); sender.join(2000); closer.join(2000) }
        assertEquals(0L, closed.count)
        f.calls[0].reply(storage())
        assertEquals(1, f.calls.size)
        assertEquals(SettingsError.NOT_READY, results.single().error)
    }

    @Test
    fun synchronousNativeCallbacksAndDrainObserverRunOutsideDispatchLock() {
        val owner = SettingsProxyProbeOwner { true }
        val drains = AtomicInteger(); val outside = AtomicBoolean()
        val lease = owner.newLease {
            drains.incrementAndGet()
            val done = CountDownLatch(1)
            Thread { owner.revoke(); done.countDown() }.start()
            outside.set(done.await(2, TimeUnit.SECONDS))
        }
        val results = mutableListOf<SettingsResult<SettingsAdvancedData>>()
        val session = OfficialTdSettingsSession(send = { command, reply, _ ->
            when (command) {
                is TdApi.GetStorageStatisticsFast -> reply(storage())
                is TdApi.GetProxies -> reply(proxies())
                else -> fail("unexpected command")
            }
        })
        session.advancedForProxyChecks(lease, results::add)
        assertTrue(outside.get())
        assertEquals(1, drains.get())
        assertEquals(SettingsError.NOT_READY, results.single().error)
    }

    @Test
    fun duplicateCallbacksCannotSpawnASecondStageOrReleaseTwice() {
        val f = Fixture(); val results = CopyOnWriteArrayList<SettingsResult<SettingsAdvancedData>>()
        val drains = AtomicInteger(); val lease = SettingsProxyProbeLease({ true }, drains::incrementAndGet)
        f.session.advancedForProxyChecks(lease, results::add)
        val first = Thread { f.calls[0].reply(storage()) }
        val second = Thread { f.calls[0].reply(storage()) }
        first.start(); second.start(); first.join(); second.join()
        assertEquals(2, f.calls.size)
        f.calls[1].reply(proxies()); f.calls[1].failed()
        assertEquals(1, results.size)
        assertEquals(1, drains.get())
        assertEquals(0, f.outstanding.get())
        assertEquals(1, f.maximum.get())
    }

    @Test
    fun throwingAfterPossibleSubmissionKeepsSlotUntilRawCallbackWithoutFurtherRead() {
        for (throwAt in 0..1) {
            val f = Fixture { _, index -> if (index == throwAt) error("indeterminate submit") }
            val results = mutableListOf<SettingsResult<SettingsAdvancedData>>()
            val drains = AtomicInteger(); val lease = SettingsProxyProbeLease({ true }, drains::incrementAndGet)
            f.session.advancedForProxyChecks(lease, results::add)
            if (throwAt == 1) f.calls[0].reply(storage())
            assertEquals(SettingsError.REQUEST_FAILED, results.single().error)
            assertEquals(0, drains.get())
            lease.revoke()
            assertEquals(0, drains.get())
            f.calls[throwAt].reply(if (throwAt == 0) storage() else proxies())
            f.calls[throwAt].failed()
            assertEquals(1, drains.get())
            assertEquals(1, results.size)
            assertEquals(throwAt + 1, f.calls.size)
            assertEquals(0, f.outstanding.get())
        }
    }

    @Test
    fun legacyDefaultAndRetiredSessionNeverInitiateNewNativeWork() {
        val defaultDrains = AtomicInteger(); val defaultResults = mutableListOf<SettingsResult<SettingsAdvancedData>>()
        LegacySession().advancedForProxyChecks(SettingsProxyProbeLease({ true }, defaultDrains::incrementAndGet), defaultResults::add)
        assertEquals(SettingsError.UNSUPPORTED, defaultResults.single().error)
        assertEquals(1, defaultDrains.get())

        val f = Fixture(); f.session.retire()
        val drains = AtomicInteger(); val results = mutableListOf<SettingsResult<SettingsAdvancedData>>()
        f.session.advancedForProxyChecks(SettingsProxyProbeLease({ true }, drains::incrementAndGet), results::add)
        assertEquals(SettingsError.NOT_READY, results.single().error)
        assertEquals(1, drains.get()); assertTrue(f.calls.isEmpty())

        val later = Fixture(); val late = mutableListOf<SettingsResult<SettingsAdvancedData>>()
        val lateDrains = AtomicInteger()
        later.session.advancedForProxyChecks(SettingsProxyProbeLease({ true }, lateDrains::incrementAndGet), late::add)
        later.session.retire(); later.calls[0].reply(storage())
        assertEquals(SettingsError.NOT_READY, late.single().error)
        assertEquals(1, lateDrains.get()); assertEquals(1, later.calls.size)
    }

    private class Fixture(private val onSend: (TdApi.Function<*>, Int) -> Unit = { _, _ -> }) {
        val calls = CopyOnWriteArrayList<Pending>()
        val outstanding = AtomicInteger()
        val maximum = AtomicInteger()
        val session = OfficialTdSettingsSession(send = { command, reply, failed ->
            val pending = Pending(command, reply, failed, outstanding)
            calls += pending
            val now = outstanding.incrementAndGet()
            maximum.updateAndGet { previous -> maxOf(previous, now) }
            onSend(command, calls.size - 1)
        })
    }

    private class Pending(val command: TdApi.Function<*>, private val receive: (TdApi.Object) -> Unit,
        private val failure: () -> Unit, private val outstanding: AtomicInteger,
    ) {
        private val terminal = AtomicBoolean()
        fun reply(value: TdApi.Object) { if (terminal.compareAndSet(false, true)) outstanding.decrementAndGet(); receive(value) }
        fun failed() { if (terminal.compareAndSet(false, true)) outstanding.decrementAndGet(); failure() }
    }

    private fun storage() = TdApi.StorageStatisticsFast(12, 2, 4096, 0, 0)
    private fun proxies() = TdApi.AddedProxies(arrayOf(TdApi.AddedProxy(7, 0, false, "",
        TdApi.Proxy("proxy.example", 1080, TdApi.ProxyTypeSocks5("private-user", "private-pass")))))

    private class LegacySession : TdSettingsSession {
        override fun profile(result: (SettingsResult<SettingsProfile>) -> Unit) = error("unused")
        override fun saveProfile(edit: SettingsProfileEdit, result: (SettingsResult<Unit>) -> Unit) = error("unused")
        override fun notifications(result: (SettingsResult<Map<NotificationScope, NotificationPreferences>>) -> Unit) = error("unused")
        override fun saveNotifications(scope: NotificationScope, value: NotificationPreferences,
            result: (SettingsResult<Unit>) -> Unit) = error("unused")
        override fun privacy(result: (SettingsResult<SettingsPrivacyData>) -> Unit) = error("unused")
        override fun savePrivacy(key: PrivacyKey, base: PrivacyBase, result: (SettingsResult<Unit>) -> Unit) = error("unused")
        override fun setAccountTtl(days: Int, result: (SettingsResult<Unit>) -> Unit) = error("unused")
        override fun terminateSession(id: Long, result: (SettingsResult<Unit>) -> Unit) = error("unused")
        override fun unblock(sender: SettingsBlockedSender, result: (SettingsResult<Unit>) -> Unit) = error("unused")
        override fun advanced(result: (SettingsResult<SettingsAdvancedData>) -> Unit) = error("unused")
        override fun optimizeStorage(result: (SettingsResult<SettingsStorage>) -> Unit) = error("unused")
        override fun addProxy(draft: SettingsProxyDraft, result: (SettingsResult<Unit>) -> Unit) = error("unused")
        override fun enableProxy(id: Int?, result: (SettingsResult<Unit>) -> Unit) = error("unused")
        override fun removeProxy(id: Int, result: (SettingsResult<Unit>) -> Unit) = error("unused")
    }
}
