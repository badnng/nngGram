package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Pure synthetic callbacks; no native client, account, network or system resources. */
class OfficialTdSettingsProxyProbeTest {
    @Test
    fun availableUsesRealNativeObjectAndDrainsBeforeResult() {
        val types = listOf(
            TdApi.ProxyTypeSocks5("secret-user", "secret-pass") to SettingsProxyType.SOCKS5,
            TdApi.ProxyTypeHttp("http-user", "http-pass", true) to SettingsProxyType.HTTP,
            TdApi.ProxyTypeMtproto("ab".repeat(16)) to SettingsProxyType.MTPROTO,
        )
        for ((type, kind) in types) {
            val f = Fixture()
            val events = mutableListOf<String>()
            val results = mutableListOf<SettingsResult<SettingsProxyProbe>>()
            val lease = SettingsProxyProbeLease({ true }) { events += "drained" }
            val native = TdApi.Proxy("proxy.example", 1080, type)
            f.api.probe(target(kind), lease, lease::current) { results += it; events += "result" }
            f.calls[0].reply(list(native))
            assertSame(native, assertIs<TdApi.PingProxy>(f.calls[1].command).proxy)
            f.calls[1].reply(TdApi.Seconds(0.1254))
            assertTrue(results.isEmpty())
            f.calls[2].reply(list(native))
            assertEquals(SettingsProxyProbe.Available(125), results.single().value)
            assertEquals(listOf("drained", "result"), events)
            assertFalse(results.toString().contains("secret-pass"))
            assertFalse(results.toString().contains("ab".repeat(16)))
        }
    }

    @Test
    fun sameNativeErrorIsUnavailableOnlyWhenPingCompletedAndSourceStillMatches() {
        for (failingStage in 0..2) {
            val f = Fixture()
            val results = mutableListOf<SettingsResult<SettingsProxyProbe>>()
            val drained = AtomicInteger()
            val lease = SettingsProxyProbeLease({ true }, drained::incrementAndGet)
            f.api.probe(target(), lease, lease::current, results::add)
            val native = socks()
            if (failingStage == 0) f.calls[0].reply(TdApi.Error(400, "private-error"))
            else {
                f.calls[0].reply(list(native))
                f.calls[1].reply(if (failingStage == 1) TdApi.Error(400, "private-error") else TdApi.Seconds(0.1))
                assertTrue(results.isEmpty())
                f.calls[2].reply(if (failingStage == 2) TdApi.Error(400, "private-error") else list(native))
            }
            if (failingStage == 1) assertEquals(SettingsProxyProbe.Unavailable, results.single().value)
            else assertEquals(SettingsError.INVALID_VALUE, results.single().error)
            assertEquals(1, drained.get())
            assertFalse(results.toString().contains("private-error"))
        }
    }

    @Test
    fun duplicateOrMismatchedNativeIdentityNeverDispatchesPing() {
        val f = Fixture(); val results = mutableListOf<SettingsResult<SettingsProxyProbe>>()
        val lease = SettingsProxyProbeLease({ true })
        f.api.probe(target(), lease, lease::current, results::add)
        val added = TdApi.AddedProxy(7, 0, false, "", socks())
        f.calls.single().reply(TdApi.AddedProxies(arrayOf(added, added)))
        assertEquals(SettingsError.CONFLICT, results.single().error)
        assertEquals(1, f.calls.size)
    }

    @Test
    fun controlErrorsMalformedMeasurementsAndChangedSourcesNeverBecomeUnavailable() {
        for ((code, expected) in listOf(401 to SettingsError.NOT_READY, 403 to SettingsError.FORBIDDEN,
            429 to SettingsError.RATE_LIMITED)) {
            val f = Fixture(); val outcomes = mutableListOf<SettingsResult<SettingsProxyProbe>>()
            val lease = SettingsProxyProbeLease({ true })
            val native = socks()
            f.api.probe(target(), lease, lease::current, outcomes::add)
            f.calls[0].reply(list(native)); f.calls[1].reply(TdApi.Error(code, "private"))
            assertEquals(2, f.calls.size)
            assertEquals(expected, outcomes.single().error)
            assertNull(outcomes.single().value)
        }
        for (response in listOf(TdApi.Seconds(-1.0), TdApi.Seconds(Double.NaN),
            TdApi.Seconds(Double.POSITIVE_INFINITY), TdApi.Seconds(Double.MAX_VALUE), TdApi.Ok())) {
            val f = Fixture(); val outcomes = mutableListOf<SettingsResult<SettingsProxyProbe>>()
            val lease = SettingsProxyProbeLease({ true }); val native = socks()
            f.api.probe(target(), lease, lease::current, outcomes::add)
            f.calls[0].reply(list(native)); f.calls[1].reply(response)
            assertEquals(2, f.calls.size)
            assertEquals(SettingsError.REQUEST_FAILED, outcomes.single().error)
            assertNull(outcomes.single().value)
        }
        for (latest in listOf(list(socks(password = "different")), TdApi.AddedProxies(emptyArray()))) {
            val f = Fixture(); val outcomes = mutableListOf<SettingsResult<SettingsProxyProbe>>()
            val lease = SettingsProxyProbeLease({ true }); val native = socks()
            f.api.probe(target(), lease, lease::current, outcomes::add)
            f.calls[0].reply(list(native)); f.calls[1].reply(TdApi.Error(400, "offline")); f.calls[2].reply(latest)
            assertEquals(SettingsError.CONFLICT, outcomes.single().error)
            assertNull(outcomes.single().value)
        }
        val http = Fixture(); val httpResults = mutableListOf<SettingsResult<SettingsProxyProbe>>()
        val lease = SettingsProxyProbeLease({ true })
        val oldHttp = TdApi.Proxy("proxy.example", 1080, TdApi.ProxyTypeHttp("user", "pass", false))
        val changedHttp = TdApi.Proxy("proxy.example", 1080, TdApi.ProxyTypeHttp("user", "pass", true))
        http.api.probe(target(SettingsProxyType.HTTP), lease, lease::current, httpResults::add)
        http.calls[0].reply(list(oldHttp)); http.calls[1].reply(TdApi.Error(400, "offline"))
        http.calls[2].reply(list(changedHttp))
        assertEquals(SettingsError.CONFLICT, httpResults.single().error)
    }

    @Test
    fun ownerAndChildrenShareDispatchRevocationBoundary() {
        val owner = SettingsProxyProbeOwner { true }
        val first = owner.newLease(); val second = owner.newLease()
        val inside = CountDownLatch(1); val release = CountDownLatch(1)
        val attempting = CountDownLatch(1); val revoked = CountDownLatch(1)
        val didDispatch = AtomicBoolean()
        val sending = Thread {
            didDispatch.set(first.dispatch { inside.countDown(); release.await(2, TimeUnit.SECONDS) })
        }
        sending.start()
        assertTrue(inside.await(2, TimeUnit.SECONDS))
        val closing = Thread { attempting.countDown(); owner.revoke(); revoked.countDown() }
        closing.start()
        try {
            assertTrue(attempting.await(2, TimeUnit.SECONDS))
            assertFalse(revoked.await(100, TimeUnit.MILLISECONDS))
        }
        finally { release.countDown(); sending.join(2000); closing.join(2000) }
        assertTrue(didDispatch.get())
        assertEquals(0L, revoked.count)
        assertFalse(first.dispatch { fail("revoked child dispatched") })
        assertFalse(second.dispatch { fail("sibling dispatched after owner revocation") })
        assertFalse(owner.newLease().dispatch { fail("new child dispatched after owner revocation") })

        val enabled = AtomicBoolean(false)
        val single = SettingsProxyProbeLease(enabled::get)
        assertFalse(single.current()); enabled.set(true)
        assertFalse(single.dispatch { fail("an observed invalid lease must not revive") })
    }

    @Test
    fun synchronousCallbacksDrainOutsideTheDispatchLock() {
        val owner = SettingsProxyProbeOwner { true }
        val drained = AtomicInteger(); val outside = AtomicBoolean()
        val lease = owner.newLease {
            drained.incrementAndGet()
            val completed = CountDownLatch(1)
            Thread { owner.revoke(); completed.countDown() }.start()
            outside.set(completed.await(2, TimeUnit.SECONDS))
        }
        val results = mutableListOf<SettingsResult<SettingsProxyProbe>>()
        val api = OfficialTdSettingsProxyProbe { command, reply, _ ->
            when (command) {
                is TdApi.GetProxies -> reply(list(socks()))
                is TdApi.PingProxy -> reply(TdApi.Seconds(0.1))
                else -> fail("unexpected command")
            }
        }
        api.probe(target(), lease, lease::current, results::add)
        assertTrue(outside.get())
        assertEquals(1, drained.get())
        assertEquals(SettingsError.NOT_READY, results.single().error)
    }

    @Test
    fun throwingSendRetainsPhysicalSlotUntilLateRawCallback() {
        val calls = CopyOnWriteArrayList<Pending>()
        val drained = AtomicInteger()
        val results = CopyOnWriteArrayList<SettingsResult<SettingsProxyProbe>>()
        val api = OfficialTdSettingsProxyProbe { command, reply, failed ->
            calls += Pending(command, reply, failed)
            if (command is TdApi.PingProxy) throw IllegalStateException("indeterminate submit")
        }
        val lease = SettingsProxyProbeLease({ true }, drained::incrementAndGet)
        api.probe(target(), lease, lease::current, results::add)
        calls[0].reply(list(socks()))
        assertEquals(SettingsError.REQUEST_FAILED, results.single().error)
        lease.revoke()
        assertEquals(0, drained.get())
        calls[1].reply(TdApi.Seconds(0.1)); calls[1].failed()
        assertEquals(1, drained.get())
        assertEquals(1, results.size)
        assertEquals(2, calls.size)
    }

    @Test
    fun duplicatesRetireAndWebDoNotStartUnownedWork() {
        val requests = CopyOnWriteArrayList<Pending>(); val outcomes = CopyOnWriteArrayList<SettingsResult<SettingsProxyProbe>>()
        val session = OfficialTdSettingsSession(send = { command, reply, failed -> requests += Pending(command, reply, failed) })
        val drains = AtomicInteger(); val lease = SettingsProxyProbeLease({ true }, drains::incrementAndGet)
        session.probeProxy(target(), lease, outcomes::add)
        val native = socks()
        val a = Thread { requests[0].reply(list(native)) }; val b = Thread { requests[0].reply(list(native)) }
        a.start(); b.start(); a.join(); b.join()
        assertEquals(2, requests.size)
        requests[1].reply(TdApi.Seconds(0.1)); requests[1].failed()
        assertEquals(3, requests.size)
        session.retire(); requests[2].reply(list(native)); requests[2].failed()
        assertEquals(SettingsError.NOT_READY, outcomes.single().error)
        assertEquals(1, drains.get())

        val webDrains = AtomicInteger(); val web = SettingsProxyProbeLease({ true }, webDrains::incrementAndGet)
        session.probeProxy(target(SettingsProxyType.WEB), web, outcomes::add)
        assertEquals(1, webDrains.get()); assertEquals(3, requests.size)

        val active = Fixture(); val webResults = mutableListOf<SettingsResult<SettingsProxyProbe>>()
        val webLease = SettingsProxyProbeLease({ true })
        active.api.probe(target(SettingsProxyType.WEB), webLease, webLease::current, webResults::add)
        assertEquals(SettingsError.UNSUPPORTED, webResults.single().error)
        assertTrue(active.calls.isEmpty())
    }

    @Test
    fun legacySessionDefaultIsUnsupportedAndDrainsWithoutNativeWork() {
        val events = mutableListOf<String>()
        val lease = SettingsProxyProbeLease({ true }) { events += "drained" }
        val results = mutableListOf<SettingsResult<SettingsProxyProbe>>()
        LegacySession().probeProxy(target(), lease) { results += it; events += "result" }
        assertEquals(SettingsError.UNSUPPORTED, results.single().error)
        assertEquals(listOf("drained", "result"), events)
    }

    private fun target(type: SettingsProxyType = SettingsProxyType.SOCKS5) =
        SettingsProxy(7, "proxy.example", 1080, type, false)
    private fun socks(password: String = "pass") = TdApi.Proxy("proxy.example", 1080,
        TdApi.ProxyTypeSocks5("user", password))
    private fun list(proxy: TdApi.Proxy) = TdApi.AddedProxies(arrayOf(TdApi.AddedProxy(7, 0, false, "", proxy)))
    private class Fixture {
        val calls = CopyOnWriteArrayList<Pending>()
        val api = OfficialTdSettingsProxyProbe { command, reply, failed -> calls += Pending(command, reply, failed) }
    }
    private data class Pending(val command: TdApi.Function<*>, val reply: (TdApi.Object) -> Unit, val failed: () -> Unit)

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
