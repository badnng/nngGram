package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Synthetic native callbacks only: no client, account, network or system clipboard. */
class OfficialTdSettingsProxyShareTest {
    @Test
    fun singleSharePassesTheActualNativeProxyAndRequestedLinkFormat() {
        val variants = listOf(
            TdApi.ProxyTypeSocks5("synthetic-user", "synthetic-password") to SettingsProxyType.SOCKS5,
            TdApi.ProxyTypeMtproto("synthetic-mt-secret") to SettingsProxyType.MTPROTO,
            TdApi.ProxyTypeWeb("synthetic-path", "synthetic-web-secret") to SettingsProxyType.WEB,
        )
        for ((type, kind) in variants) for (isHttp in listOf(true, false)) {
            val f = Fixture()
            val events = mutableListOf<String>()
            val outcomes = mutableListOf<SettingsResult<List<String>>>()
            val lease = SettingsProxyProbeLease({ true }) { events += "drained" }
            val actual = proxy(type)
            f.api.share(one(kind), isHttp, lease, lease::current) { outcomes += it; events += "result" }
            assertIs<TdApi.GetProxies>(f.calls.single().command)
            f.calls[0].reply(added(actual))
            val linkRequest = assertIs<TdApi.GetInternalLink>(f.calls[1].command)
            val linkType = assertIs<TdApi.InternalLinkTypeProxy>(linkRequest.type)
            assertTrue(linkType.proxy === actual, "The native object, including hidden credentials, must be used.")
            assertEquals(isHttp, linkRequest.isHttp)
            val link = if (isHttp) PUBLIC_LINK else INTERNAL_LINK
            f.calls[1].reply(TdApi.HttpUrl(link))
            assertTrue(outcomes.isEmpty(), "A link alone is not a validated result.")
            assertIs<TdApi.GetProxies>(f.calls[2].command)
            f.calls[2].reply(added(actual))
            assertTrue(outcomes.single().value == listOf(link), "The native output must be returned unchanged.")
            assertNull(outcomes.single().error)
            assertEquals(listOf("drained", "result"), events)
            assertFalse(outcomes.toString().contains(link), "Result rendering must not expose links.")
        }
    }

    @Test
    fun missingDuplicateOrMismatchedNativeSourceCannotGenerateAPlaceholderLink() {
        val expected = one(SettingsProxyType.SOCKS5)
        val native = proxy(TdApi.ProxyTypeSocks5("synthetic-user", "synthetic-password"))
        val entry = item(native)
        val variants = listOf(
            TdApi.AddedProxies(emptyArray()),
            TdApi.AddedProxies(arrayOf(entry, entry)),
            TdApi.AddedProxies(arrayOf(TdApi.AddedProxy(7, 0, false, "", null))),
            TdApi.AddedProxies(arrayOf(item(proxy(TdApi.ProxyTypeSocks5("u", "p"), server = "other.invalid")))),
            TdApi.AddedProxies(arrayOf(item(proxy(TdApi.ProxyTypeSocks5("u", "p"), port = 1081)))),
            TdApi.AddedProxies(arrayOf(item(proxy(TdApi.ProxyTypeMtproto("other"))))),
            TdApi.AddedProxies(arrayOf(item(native, enabled = true))),
        )
        for (source in variants) {
            val f = Fixture()
            val results = mutableListOf<SettingsResult<List<String>>>()
            val drains = AtomicInteger()
            val lease = SettingsProxyProbeLease({ true }, drains::incrementAndGet)
            f.api.share(expected, true, lease, lease::current, results::add)
            f.calls.single().reply(source)
            assertEquals(SettingsError.CONFLICT, results.single().error)
            assertNull(results.single().value)
            assertEquals(1, f.calls.size, "An invalid source must never reach GetInternalLink.")
            assertEquals(1, drains.get())
        }
    }

    @Test
    fun httpIsUnsupportedAndNeverReachesInternalLink() {
        val f = Fixture()
        val results = mutableListOf<SettingsResult<List<String>>>()
        val drains = AtomicInteger()
        val lease = SettingsProxyProbeLease({ true }, drains::incrementAndGet)
        f.api.share(one(SettingsProxyType.HTTP), true, lease, lease::current, results::add)
        if (f.calls.isNotEmpty()) {
            assertIs<TdApi.GetProxies>(f.calls.single().command)
            f.calls.single().reply(added(proxy(TdApi.ProxyTypeHttp("synthetic-user", "synthetic-password", true))))
        }
        assertEquals(SettingsError.UNSUPPORTED, results.single().error)
        assertNull(results.single().value)
        assertTrue(f.calls.none { it.command is TdApi.GetInternalLink })
        assertEquals(1, drains.get())
    }

    @Test
    fun invisibleCredentialsAndWebPathChangesInvalidateTheCompletedLink() {
        val variants = listOf(
            Triple(SettingsProxyType.SOCKS5, TdApi.ProxyTypeSocks5("u1", "p1"), TdApi.ProxyTypeSocks5("u2", "p1")),
            Triple(SettingsProxyType.SOCKS5, TdApi.ProxyTypeSocks5("u1", "p1"), TdApi.ProxyTypeSocks5("u1", "p2")),
            Triple(SettingsProxyType.MTPROTO, TdApi.ProxyTypeMtproto("s1"), TdApi.ProxyTypeMtproto("s2")),
            Triple(SettingsProxyType.WEB, TdApi.ProxyTypeWeb("path1", "s1"), TdApi.ProxyTypeWeb("path2", "s1")),
            Triple(SettingsProxyType.WEB, TdApi.ProxyTypeWeb("path1", "s1"), TdApi.ProxyTypeWeb("path1", "s2")),
        )
        for ((kind, oldType, changedType) in variants) {
            val f = Fixture()
            val outcomes = mutableListOf<SettingsResult<List<String>>>()
            val lease = SettingsProxyProbeLease({ true })
            f.api.share(one(kind), true, lease, lease::current, outcomes::add)
            f.calls[0].reply(added(proxy(oldType)))
            f.calls[1].reply(TdApi.HttpUrl(PUBLIC_LINK))
            assertTrue(outcomes.isEmpty())
            f.calls[2].reply(added(proxy(changedType)))
            assertEquals(SettingsError.CONFLICT, outcomes.single().error)
            assertNull(outcomes.single().value)
            assertFalse(outcomes.toString().contains(PUBLIC_LINK))
        }
    }

    @Test
    fun deletionReplacementAndDuplicateIdOnFinalReadNeverPublishTheLink() {
        val first = proxy(TdApi.ProxyTypeSocks5("u", "p"))
        val entry = item(first)
        val replacements = listOf(
            TdApi.AddedProxies(emptyArray()),
            TdApi.AddedProxies(arrayOf(entry, entry)),
            added(proxy(TdApi.ProxyTypeSocks5("u", "p"), server = "replacement.invalid")),
            added(proxy(TdApi.ProxyTypeSocks5("u", "p"), port = 1081)),
            TdApi.AddedProxies(arrayOf(item(first, enabled = true))),
        )
        for (last in replacements) {
            val f = Fixture()
            val outcomes = mutableListOf<SettingsResult<List<String>>>()
            val lease = SettingsProxyProbeLease({ true })
            f.api.share(one(SettingsProxyType.SOCKS5), true, lease, lease::current, outcomes::add)
            f.calls[0].reply(added(first))
            f.calls[1].reply(TdApi.HttpUrl(PUBLIC_LINK))
            f.calls[2].reply(last)
            assertEquals(SettingsError.CONFLICT, outcomes.single().error)
            assertNull(outcomes.single().value)
        }
    }

    @Test
    fun allShareUsesCompleteSourceOrderAndSkipsHttpWithoutPublishingPartialLinks() {
        val types = listOf(
            TdApi.ProxyTypeWeb("path", "secret") to SettingsProxyType.WEB,
            TdApi.ProxyTypeHttp("u", "p", false) to SettingsProxyType.HTTP,
            TdApi.ProxyTypeSocks5("u", "p") to SettingsProxyType.SOCKS5,
            TdApi.ProxyTypeMtproto("secret") to SettingsProxyType.MTPROTO,
        )
        val ids = listOf(44, 9, 3, 20)
        val native = types.mapIndexed { index, pair ->
            TdApi.AddedProxy(ids[index], 0, false, "", proxy(pair.first, server = "proxy-$index.invalid"))
        }
        val rows = types.mapIndexed { index, pair ->
            SettingsProxy(ids[index], "proxy-$index.invalid", 1080, pair.second, false)
        }
        val f = Fixture()
        val outcomes = mutableListOf<SettingsResult<List<String>>>()
        val lease = SettingsProxyProbeLease({ true })
        f.api.share(SettingsProxyShareTarget.All(rows), true, lease, lease::current, outcomes::add)
        val source = TdApi.AddedProxies(native.toTypedArray())
        f.calls[0].reply(source)
        val shareable = listOf(native[0], native[2], native[3])
        val links = shareable.indices.map { "https://synthetic.invalid/link-$it" }
        for ((index, entry) in shareable.withIndex()) {
            val command = assertIs<TdApi.GetInternalLink>(f.calls[index + 1].command)
            assertTrue(command.isHttp)
            assertTrue(assertIs<TdApi.InternalLinkTypeProxy>(command.type).proxy === entry.proxy)
            assertTrue(outcomes.isEmpty(), "No partial result may escape while the list is being generated.")
            f.calls[index + 1].reply(TdApi.HttpUrl(links[index]))
        }
        assertTrue(outcomes.isEmpty(), "All links still require final source validation.")
        assertIs<TdApi.GetProxies>(f.calls[4].command)
        f.calls[4].reply(source)
        assertTrue(outcomes.single().value == links, "Each accepted native link must retain source order.")
        assertNull(outcomes.single().error)
        assertFalse(links.any { outcomes.toString().contains(it) })
    }

    @Test
    fun aLaterListLinkFailureOrChangedSourceInvalidatesTheEntireResult() {
        val rows = (1..2).map { SettingsProxy(it, "proxy-$it.invalid", 1080, SettingsProxyType.SOCKS5, false) }
        val source = (1..2).map { id ->
            TdApi.AddedProxy(id, 0, false, "", proxy(TdApi.ProxyTypeSocks5("u", "p"), "proxy-$id.invalid"))
        }
        for (lateFailure in listOf(true, false)) {
            val f = Fixture()
            val outcomes = mutableListOf<SettingsResult<List<String>>>()
            val lease = SettingsProxyProbeLease({ true })
            f.api.share(SettingsProxyShareTarget.All(rows), true, lease, lease::current, outcomes::add)
            f.calls[0].reply(TdApi.AddedProxies(source.toTypedArray()))
            f.calls[1].reply(TdApi.HttpUrl(PUBLIC_LINK))
            if (lateFailure) f.calls[2].reply(TdApi.Error(400, "synthetic-private-error"))
            else {
                f.calls[2].reply(TdApi.HttpUrl(PUBLIC_LINK))
                val changed = source.toMutableList()
                changed[1] = TdApi.AddedProxy(2, 0, false, "",
                    proxy(TdApi.ProxyTypeSocks5("u", "changed"), "proxy-2.invalid"))
                f.calls[3].reply(TdApi.AddedProxies(changed.toTypedArray()))
            }
            assertNull(outcomes.single().value)
            assertNotNull(outcomes.single().error)
            assertFalse(outcomes.toString().contains(PUBLIC_LINK))
        }
    }

    @Test
    fun allShareRejectsNativeOrderChangesBeforeAndAfterLinkGeneration() {
        val rows = (1..2).map { SettingsProxy(it, "proxy-$it.invalid", 1080, SettingsProxyType.SOCKS5, false) }
        val source = (1..2).map { id ->
            TdApi.AddedProxy(id, 0, false, "", proxy(TdApi.ProxyTypeSocks5("u", "p"), "proxy-$id.invalid"))
        }
        for (reorderOnFirst in listOf(true, false)) {
            val f = Fixture()
            val outcomes = mutableListOf<SettingsResult<List<String>>>()
            val lease = SettingsProxyProbeLease({ true })
            f.api.share(SettingsProxyShareTarget.All(rows), true, lease, lease::current, outcomes::add)
            f.calls[0].reply(TdApi.AddedProxies((if (reorderOnFirst) source.reversed() else source).toTypedArray()))
            if (!reorderOnFirst) {
                f.calls[1].reply(TdApi.HttpUrl(PUBLIC_LINK))
                f.calls[2].reply(TdApi.HttpUrl(PUBLIC_LINK))
                f.calls[3].reply(TdApi.AddedProxies(source.reversed().toTypedArray()))
            }
            assertEquals(SettingsError.CONFLICT, outcomes.single().error)
            assertNull(outcomes.single().value)
            if (reorderOnFirst) assertEquals(1, f.calls.size)
        }
    }

    @Test
    fun allShareIncludesNativeRowsBeyondTheVisibleBudgetAndRevalidatesThem() {
        val source = (1..258).map { id ->
            TdApi.AddedProxy(id, 0, false, "", proxy(TdApi.ProxyTypeSocks5("u", "p"), "proxy-$id.invalid"))
        }
        val rows = (1..256).map { id ->
            SettingsProxy(id, "proxy-$id.invalid", 1080, SettingsProxyType.SOCKS5, false)
        }
        for (change in listOf("none", "hidden-credentials", "hidden-order")) {
            val f = Fixture()
            val results = mutableListOf<SettingsResult<List<String>>>()
            val lease = SettingsProxyProbeLease({ true })
            f.api.share(SettingsProxyShareTarget.All(rows), true, lease, lease::current, results::add)
            f.calls[0].reply(TdApi.AddedProxies(source.toTypedArray()))
            val links = source.indices.map { "https://synthetic.invalid/link-$it" }
            source.forEachIndexed { index, entry ->
                val command = assertIs<TdApi.GetInternalLink>(f.calls[index + 1].command)
                assertTrue(assertIs<TdApi.InternalLinkTypeProxy>(command.type).proxy === entry.proxy)
                assertTrue(command.isHttp)
                f.calls[index + 1].reply(TdApi.HttpUrl(links[index]))
                assertTrue(results.isEmpty(), "No visible or hidden subset may be published early.")
            }
            val last = source.toMutableList()
            when (change) {
                "hidden-credentials" -> last[256] = TdApi.AddedProxy(257, 0, false, "",
                    proxy(TdApi.ProxyTypeSocks5("u", "changed"), "proxy-257.invalid"))
                "hidden-order" -> { val old = last[256]; last[256] = last[257]; last[257] = old }
            }
            assertIs<TdApi.GetProxies>(f.calls[259].command)
            f.calls[259].reply(TdApi.AddedProxies(last.toTypedArray()))
            if (change == "none") {
                assertTrue(results.single().value == links, "Every native shareable item must retain source order.")
                assertNull(results.single().error)
            } else {
                assertEquals(SettingsError.CONFLICT, results.single().error)
                assertNull(results.single().value)
            }
            assertFalse(links.any { results.toString().contains(it) })
        }
    }

    @Test
    fun allShareRejectsAStaleVisibleWitnessEvenForAddedOrRemovedHttpRows() {
        val web = SettingsProxy(1, "proxy-1.invalid", 1080, SettingsProxyType.WEB, false)
        val http = SettingsProxy(2, "proxy-2.invalid", 1080, SettingsProxyType.HTTP, false)
        val nativeWeb = TdApi.AddedProxy(1, 0, false, "", proxy(TdApi.ProxyTypeWeb("path", "s"), web.server))
        val nativeHttp = TdApi.AddedProxy(2, 0, false, "", proxy(TdApi.ProxyTypeHttp("u", "p", false), http.server))
        val variants = listOf(
            listOf(web) to listOf(nativeWeb, nativeHttp),
            listOf(web, http) to listOf(nativeWeb),
            listOf(web) to emptyList(),
        )
        for ((witness, native) in variants) {
            val f = Fixture()
            val results = mutableListOf<SettingsResult<List<String>>>()
            val lease = SettingsProxyProbeLease({ true })
            f.api.share(SettingsProxyShareTarget.All(witness), true, lease, lease::current, results::add)
            f.calls.single().reply(TdApi.AddedProxies(native.toTypedArray()))
            assertEquals(SettingsError.CONFLICT, results.single().error)
            assertNull(results.single().value)
            assertEquals(1, f.calls.size)
        }
    }

    @Test
    fun allShareRevalidatesVisibleMembershipAndEnabledStateAfterGeneratingLinks() {
        val rows = listOf(
            SettingsProxy(1, "proxy-1.invalid", 1080, SettingsProxyType.WEB, false),
            SettingsProxy(2, "proxy-2.invalid", 1080, SettingsProxyType.HTTP, false),
        )
        val web = TdApi.AddedProxy(1, 0, false, "", proxy(TdApi.ProxyTypeWeb("path", "s"), "proxy-1.invalid"))
        val http = TdApi.AddedProxy(2, 0, false, "", proxy(TdApi.ProxyTypeHttp("u", "p", false), "proxy-2.invalid"))
        val extra = TdApi.AddedProxy(3, 0, false, "", proxy(TdApi.ProxyTypeHttp("u", "p", false), "proxy-3.invalid"))
        val changedEnabled = TdApi.AddedProxy(2, 0, true, "", http.proxy)
        val endings = listOf(listOf(web), listOf(web, http, extra), listOf(web, changedEnabled), listOf(web, http, http))
        for (last in endings) {
            val f = Fixture()
            val results = mutableListOf<SettingsResult<List<String>>>()
            val lease = SettingsProxyProbeLease({ true })
            f.api.share(SettingsProxyShareTarget.All(rows), true, lease, lease::current, results::add)
            f.calls[0].reply(TdApi.AddedProxies(arrayOf(web, http)))
            f.calls[1].reply(TdApi.HttpUrl(PUBLIC_LINK))
            f.calls[2].reply(TdApi.AddedProxies(last.toTypedArray()))
            assertEquals(SettingsError.CONFLICT, results.single().error)
            assertNull(results.single().value)
        }
    }

    @Test
    fun combinedLinksBeyondTheWin32TextLimitFailWithoutATruncatedResult() {
        val rows = listOf(
            SettingsProxy(1, "proxy-1.invalid", 1080, SettingsProxyType.SOCKS5, false),
            SettingsProxy(2, "proxy-2.invalid", 1080, SettingsProxyType.SOCKS5, false),
        )
        val source = rows.map { row ->
            TdApi.AddedProxy(row.id, 0, false, "", proxy(TdApi.ProxyTypeSocks5("u", "p"), row.server))
        }
        // Each URL fits alone; their UTF-16 units, separator and terminating NUL exceed 64 MiB.
        val prefix = "https://synthetic.invalid/"
        val link = prefix + "a".repeat(16_777_215 - prefix.length)
        val f = Fixture()
        val results = mutableListOf<SettingsResult<List<String>>>()
        val lease = SettingsProxyProbeLease({ true })
        f.api.share(SettingsProxyShareTarget.All(rows), true, lease, lease::current, results::add)
        f.calls[0].reply(TdApi.AddedProxies(source.toTypedArray()))
        f.calls[1].reply(TdApi.HttpUrl(link))
        f.calls[2].reply(TdApi.HttpUrl(link))
        if (results.isEmpty()) {
            assertIs<TdApi.GetProxies>(f.calls[3].command)
            f.calls[3].reply(TdApi.AddedProxies(source.toTypedArray()))
        }
        assertNull(results.single().value)
        assertNotNull(results.single().error)
    }

    @Test
    fun inPlaceNativeCredentialMutationCannotAuthorizeAPreviouslyCapturedObject() {
        val f = Fixture()
        val results = mutableListOf<SettingsResult<List<String>>>()
        val lease = SettingsProxyProbeLease({ true })
        val type = TdApi.ProxyTypeSocks5("u", "p1")
        val actual = proxy(type)
        f.api.share(one(SettingsProxyType.SOCKS5), true, lease, lease::current, results::add)
        f.calls[0].reply(added(actual))
        type.password = "p2"
        f.calls[1].reply(TdApi.HttpUrl(PUBLIC_LINK))
        assertEquals(SettingsError.CONFLICT, results.single().error)
        assertNull(results.single().value)
        assertEquals(2, f.calls.size)
    }

    @Test
    fun nativeErrorsAndMalformedResultsNeverProduceShareableContent() {
        for (stage in 0..2) {
            val f = Fixture()
            val outcomes = mutableListOf<SettingsResult<List<String>>>()
            val drains = AtomicInteger()
            val lease = SettingsProxyProbeLease({ true }, drains::incrementAndGet)
            val actual = proxy(TdApi.ProxyTypeSocks5("u", "p"))
            f.api.share(one(SettingsProxyType.SOCKS5), true, lease, lease::current, outcomes::add)
            if (stage > 0) f.calls[0].reply(added(actual))
            if (stage > 1) f.calls[1].reply(TdApi.HttpUrl(PUBLIC_LINK))
            f.calls[stage].reply(TdApi.Error(400, "synthetic-native-private-error"))
            assertNull(outcomes.single().value)
            assertNotNull(outcomes.single().error)
            assertEquals(1, drains.get())
            assertFalse(outcomes.toString().contains("synthetic-native-private-error"))
            assertFalse(outcomes.toString().contains(PUBLIC_LINK))
        }
        for (response in listOf(TdApi.Ok(), TdApi.HttpUrl(""), TdApi.HttpUrl(),
            TdApi.HttpUrl("http://synthetic.invalid/proxy"), TdApi.HttpUrl("https://synthetic.invalid/a\u0000b"))) {
            val f = Fixture()
            val outcomes = mutableListOf<SettingsResult<List<String>>>()
            val lease = SettingsProxyProbeLease({ true })
            f.api.share(one(SettingsProxyType.SOCKS5), true, lease, lease::current, outcomes::add)
            f.calls[0].reply(added(proxy(TdApi.ProxyTypeSocks5("u", "p"))))
            f.calls[1].reply(response)
            assertNull(outcomes.single().value)
            assertNotNull(outcomes.single().error)
            assertEquals(2, f.calls.size, "A malformed link must not continue to a final validation read.")
        }
    }

    @Test
    fun revocationAndDuplicateCallbacksCannotContinueOrPublishAnOldRequest() {
        for (stopAt in 0..2) {
            val f = Fixture()
            val outcomes = mutableListOf<SettingsResult<List<String>>>()
            val valid = AtomicBoolean(true)
            val drains = AtomicInteger()
            val lease = SettingsProxyProbeLease(valid::get, drains::incrementAndGet)
            val source = added(proxy(TdApi.ProxyTypeSocks5("u", "p")))
            f.api.share(one(SettingsProxyType.SOCKS5), true, lease, lease::current, outcomes::add)
            if (stopAt > 0) {
                f.calls[0].reply(source)
                f.calls[0].reply(source)
                assertEquals(2, f.calls.size)
            }
            if (stopAt > 1) {
                f.calls[1].reply(TdApi.HttpUrl(PUBLIC_LINK))
                f.calls[1].reply(TdApi.HttpUrl(PUBLIC_LINK))
                assertEquals(3, f.calls.size)
            }
            valid.set(false)
            f.calls[stopAt].reply(if (stopAt == 1) TdApi.HttpUrl(PUBLIC_LINK) else source)
            f.calls[stopAt].failed()
            valid.set(true)
            assertEquals(1, outcomes.size)
            assertEquals(SettingsError.NOT_READY, outcomes.single().error)
            assertNull(outcomes.single().value)
            assertEquals(stopAt + 1, f.calls.size)
            assertEquals(1, drains.get())
        }
    }

    @Test
    fun throwingSubmissionKeepsItsPhysicalLeaseUntilTheLateRawCallback() {
        val calls = CopyOnWriteArrayList<Pending>()
        val results = CopyOnWriteArrayList<SettingsResult<List<String>>>()
        val drains = AtomicInteger()
        val api = OfficialTdSettingsProxyShare { command, reply, failed ->
            calls += Pending(command, reply, failed)
            if (command is TdApi.GetInternalLink) throw IllegalStateException("synthetic ambiguous submission")
        }
        val lease = SettingsProxyProbeLease({ true }, drains::incrementAndGet)
        api.share(one(SettingsProxyType.SOCKS5), true, lease, lease::current, results::add)
        calls[0].reply(added(proxy(TdApi.ProxyTypeSocks5("u", "p"))))
        assertEquals(SettingsError.REQUEST_FAILED, results.single().error)
        assertNull(results.single().value)
        lease.revoke()
        assertEquals(0, drains.get(), "Logical failure and revocation are not a raw callback.")
        calls[1].reply(TdApi.HttpUrl(PUBLIC_LINK))
        calls[1].failed()
        assertEquals(1, drains.get())
        assertEquals(1, results.size)
        assertEquals(2, calls.size)
    }

    private class Fixture {
        val calls = CopyOnWriteArrayList<Pending>()
        val api = OfficialTdSettingsProxyShare { command, reply, failed -> calls += Pending(command, reply, failed) }
    }

    private data class Pending(val command: TdApi.Function<*>, val reply: (TdApi.Object) -> Unit, val failed: () -> Unit)

    private companion object {
        const val PUBLIC_LINK = "https://synthetic.invalid/proxy-link"
        const val INTERNAL_LINK = "tg://synthetic-proxy-link"
        fun one(type: SettingsProxyType) = SettingsProxyShareTarget.One(0, SettingsProxy(7, "proxy.invalid", 1080, type, false))
        fun proxy(type: TdApi.ProxyType, server: String = "proxy.invalid", port: Int = 1080) = TdApi.Proxy(server, port, type)
        fun item(proxy: TdApi.Proxy, enabled: Boolean = false) = TdApi.AddedProxy(7, 0, enabled, "", proxy)
        fun added(proxy: TdApi.Proxy) = TdApi.AddedProxies(arrayOf(item(proxy)))
    }
}
