package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import kotlin.test.*

/** All responses are synthetic; this fixture never creates a client or opens a network connection. */
class OfficialTdSettingsProxyLatencyTest {
    @Test
    fun usesTheActualNativeProxyForEverySupportedTypeAndConvertsOnlyNativeSeconds() {
        val nativeTypes = listOf(
            TdApi.ProxyTypeSocks5("private-user", "private-pass") to SettingsProxyType.SOCKS5,
            TdApi.ProxyTypeHttp("http-user", "http-pass", true) to SettingsProxyType.HTTP,
            TdApi.ProxyTypeMtproto("ab".repeat(16)) to SettingsProxyType.MTPROTO,
        )
        nativeTypes.forEach { (type, kind) ->
            val f = Fixture()
            val expected = target(kind)
            val native = TdApi.Proxy(expected.server, expected.port, type)
            val result = mutableListOf<SettingsResult<SettingsProxyLatency>>()
            f.api.ping(expected, { true }, result::add)
            assertIs<TdApi.GetProxies>(f.requests.single().command)
            f.requests[0].respond(list(native))
            val ping = assertIs<TdApi.PingProxy>(f.requests[1].command)
            assertSame(native, ping.proxy)
            f.requests[1].respond(TdApi.Seconds(0.1254))
            assertTrue(result.isEmpty())
            assertIs<TdApi.GetProxies>(f.requests[2].command)
            f.requests[2].respond(list(native))
            assertEquals(SettingsProxyLatency(125), result.single().value)
            assertNull(result.single().error)
            assertFalse(result.toString().contains("private-pass"))
            assertFalse(result.toString().contains("ab".repeat(16)))
        }
    }

    @Test
    fun unknownWebAndMismatchedOrAmbiguousRowsNeverSendPing() {
        val f = Fixture()
        val result = mutableListOf<SettingsResult<SettingsProxyLatency>>()
        f.api.ping(target(SettingsProxyType.WEB), { true }, result::add)
        assertEquals(SettingsError.UNSUPPORTED, result.single().error)
        assertTrue(f.requests.isEmpty())

        f.api.ping(target(), { true }, result::add)
        val wrong = TdApi.Proxy("other.example", 1080, TdApi.ProxyTypeSocks5("", ""))
        f.requests.last().respond(list(wrong))
        assertEquals(SettingsError.CONFLICT, result.last().error)
        assertEquals(1, f.requests.size)

        f.api.ping(target(), { true }, result::add)
        val native = socks()
        f.requests.last().respond(TdApi.AddedProxies(arrayOf(added(native), added(native))))
        assertEquals(SettingsError.CONFLICT, result.last().error)
        assertEquals(2, f.requests.size)
    }

    @Test
    fun deletionAndSameIdCredentialOrHttpModeReplacementNeverPublishTheOldValue() {
        val replacements: List<Pair<TdApi.Proxy, TdApi.AddedProxies>> = listOf(
            socks() to TdApi.AddedProxies(emptyArray()),
            socks() to list(socks(password = "changed")),
            TdApi.Proxy("proxy.example", 1080, TdApi.ProxyTypeHttp("user", "pass", false)) to
                list(TdApi.Proxy("proxy.example", 1080, TdApi.ProxyTypeHttp("user", "pass", true))),
        )
        replacements.forEach { (original, replacement) ->
            val f = Fixture()
            val kind = if (original.type is TdApi.ProxyTypeHttp) SettingsProxyType.HTTP else SettingsProxyType.SOCKS5
            val result = mutableListOf<SettingsResult<SettingsProxyLatency>>()
            f.api.ping(target(kind), { true }, result::add)
            f.requests[0].respond(list(original))
            f.requests[1].respond(TdApi.Seconds(0.5))
            f.requests[2].respond(replacement)
            assertEquals(SettingsError.CONFLICT, result.single().error)
            assertNull(result.single().value)
        }
    }

    @Test
    fun revokedOwnerStopsFollowupAndCannotPublishOnAnyLateStage() {
        for (stopAfter in 0..2) {
            val f = Fixture()
            var active = true
            val result = mutableListOf<SettingsResult<SettingsProxyLatency>>()
            f.api.ping(target(), { active }, result::add)
            val native = socks()
            if (stopAfter == 0) {
                active = false
                f.requests[0].respond(list(native))
                assertEquals(1, f.requests.size)
            } else {
                f.requests[0].respond(list(native))
                if (stopAfter == 1) {
                    active = false
                    f.requests[1].respond(TdApi.Seconds(0.1))
                    assertEquals(2, f.requests.size)
                } else {
                    f.requests[1].respond(TdApi.Seconds(0.1))
                    active = false
                    f.requests[2].respond(list(native))
                }
            }
            assertEquals(SettingsError.NOT_READY, result.single().error)
            assertNull(result.single().value)
        }
    }

    @Test
    fun officialSessionRetirementAlsoRevokesAnAlreadyDispatchedFinalRead() {
        val requests = mutableListOf<Pending>()
        val session = OfficialTdSettingsSession(send = { command, respond, fail ->
            requests += Pending(command, respond, fail)
        })
        val result = mutableListOf<SettingsResult<SettingsProxyLatency>>()
        val native = socks()
        session.pingProxy(target(), { true }, result::add)
        requests[0].respond(list(native))
        requests[1].respond(TdApi.Seconds(0.1))
        session.retire()
        requests[2].respond(list(native))
        assertEquals(SettingsError.NOT_READY, result.single().error)
        assertNull(result.single().value)
    }

    @Test
    fun malformedNumbersAndNativeErrorsAreNotInventedOrLeakedAndCallbacksAreOnceOnly() {
        for (seconds in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.MAX_VALUE)) {
            val f = Fixture()
            val result = mutableListOf<SettingsResult<SettingsProxyLatency>>()
            val native = socks()
            f.api.ping(target(), { true }, result::add)
            f.requests[0].respond(list(native))
            f.requests[0].respond(list(native))
            assertEquals(2, f.requests.size)
            f.requests[1].respond(TdApi.Seconds(seconds))
            f.requests[1].respond(TdApi.Seconds(0.1))
            assertEquals(3, f.requests.size)
            f.requests[2].respond(list(native))
            f.requests[2].fail()
            assertEquals(SettingsError.REQUEST_FAILED, result.single().error)
            assertNull(result.single().value)
        }
        val f = Fixture()
        val result = mutableListOf<SettingsResult<SettingsProxyLatency>>()
        val native = socks()
        f.api.ping(target(), { true }, result::add)
        f.requests[0].respond(list(native))
        f.requests[1].respond(TdApi.Error(400, "private-password"))
        f.requests[2].respond(list(native))
        assertEquals(SettingsError.INVALID_VALUE, result.single().error)
        assertFalse(result.toString().contains("private-password"))
    }

    private fun target(type: SettingsProxyType = SettingsProxyType.SOCKS5) =
        SettingsProxy(7, "proxy.example", 1080, type, false)
    private fun socks(password: String = "pass") = TdApi.Proxy("proxy.example", 1080,
        TdApi.ProxyTypeSocks5("user", password))
    private fun added(proxy: TdApi.Proxy) = TdApi.AddedProxy(7, 0, false, "", proxy)
    private fun list(proxy: TdApi.Proxy) = TdApi.AddedProxies(arrayOf(added(proxy)))

    private class Fixture {
        val requests = mutableListOf<Pending>()
        val api = OfficialTdSettingsProxyLatency { command, respond, fail -> requests += Pending(command, respond, fail) }
    }
    private data class Pending(val command: TdApi.Function<*>, val respond: (TdApi.Object) -> Unit, val fail: () -> Unit)
}
