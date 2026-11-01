package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import kotlin.test.*

class OfficialTdSettingsAdvancedTest {
    @Test
    fun proxyCredentialsReachOnlyTheirNativeTypeAndSnapshotsRemainRedacted() {
        val fixture = Fixture()
        val results = mutableListOf<SettingsResult<Unit>>()
        val drafts = listOf(
            SettingsProxyDraft(" proxy.example ", 1080, SettingsProxyType.SOCKS5, "native-user", "native-password"),
            SettingsProxyDraft("proxy.example", 8080, SettingsProxyType.HTTP, "http-user", "http-password"),
            SettingsProxyDraft("proxy.example", 443, SettingsProxyType.MTPROTO, secret = "ab".repeat(16)),
        )
        drafts.forEachIndexed { index, draft ->
            fixture.api.addProxy(draft, results::add)
            val pending = fixture.requests.last()
            val request = assertIs<TdApi.AddProxy>(pending.command)
            assertEquals("proxy.example", request.proxy.server)
            assertEquals(draft.port, request.proxy.port)
            assertTrue(request.enable)
            assertEquals("", request.comment)
            when (draft.type) {
                SettingsProxyType.SOCKS5 -> assertIs<TdApi.ProxyTypeSocks5>(request.proxy.type).let {
                    assertEquals(draft.username, it.username)
                    assertEquals(draft.password, it.password)
                }
                SettingsProxyType.HTTP -> assertIs<TdApi.ProxyTypeHttp>(request.proxy.type).let {
                    assertEquals(draft.username, it.username)
                    assertEquals(draft.password, it.password)
                    assertFalse(it.httpOnly)
                }
                SettingsProxyType.MTPROTO -> assertEquals(draft.secret,
                    assertIs<TdApi.ProxyTypeMtproto>(request.proxy.type).secret)
                SettingsProxyType.WEB -> error("This fixture includes only the three supported creation types")
            }
            assertFalse(draft.toString().contains("proxy.example"))
            pending.respond(TdApi.AddedProxy(index + 1, 0, true, "", request.proxy))
        }
        assertEquals(listOf(Unit, Unit, Unit), results.map { it.value })
        val beforeUnsupported = fixture.requests.size
        fixture.api.addProxy(SettingsProxyDraft("web.example", 443, SettingsProxyType.WEB), results::add)
        assertEquals(beforeUnsupported, fixture.requests.size)
        assertEquals(SettingsError.UNSUPPORTED, results.last().error)

        val listResults = mutableListOf<SettingsResult<List<SettingsProxy>>>()
        fixture.api.proxies(listResults::add)
        fixture.requests.last().respond(TdApi.AddedProxies(arrayOf(
            TdApi.AddedProxy(9, 0, true, "", TdApi.Proxy("proxy.example", 1080,
                TdApi.ProxyTypeSocks5("private-user", "private-password"))),
            TdApi.AddedProxy(10, 0, false, "", TdApi.Proxy("mt.example", 443,
                TdApi.ProxyTypeMtproto("cd".repeat(16)))),
        )))
        val proxies = assertNotNull(listResults.single().value)
        assertEquals(listOf(9, 10), proxies.map { it.id })
        assertEquals(listOf(true, false), proxies.map { it.enabled })
        val presentation = listOf(proxies.toString(), listResults.toString(), SettingsSnapshot(proxies = proxies).toString())
        for (text in presentation) {
            assertFalse(text.contains("private-user"))
            assertFalse(text.contains("private-password"))
            assertFalse(text.contains("cd".repeat(16)))
        }
    }

    @Test
    fun invalidProxyDraftsNeverEnterTheNativeBoundary() {
        val fixture = Fixture()
        val results = mutableListOf<SettingsResult<Unit>>()
        for (draft in listOf(
            SettingsProxyDraft("server.example", 0, SettingsProxyType.SOCKS5),
            SettingsProxyDraft("server.example/path with spaces", 443, SettingsProxyType.HTTP),
            SettingsProxyDraft("server.example", 443, SettingsProxyType.MTPROTO, secret = "xyz"),
        )) fixture.api.addProxy(draft, results::add)
        assertEquals(List(3) { SettingsError.INVALID_VALUE }, results.map { it.error })
        assertTrue(fixture.requests.isEmpty())
    }

    @Test
    fun theEnabledNativeProxySurvivesTheListBoundEvenWhenItIsReturnedLast() {
        val fixture = Fixture()
        val results = mutableListOf<SettingsResult<List<SettingsProxy>>>()
        fixture.api.proxies(results::add)
        fixture.requests.single().respond(TdApi.AddedProxies(Array(300) { index ->
            TdApi.AddedProxy(index + 1, 0, index == 299, "",
                TdApi.Proxy("proxy-$index.example", 1080, TdApi.ProxyTypeSocks5("", "")))
        }))
        val proxies = assertNotNull(results.single().value)
        assertTrue(proxies.size <= 256)
        assertEquals(300, proxies.single { it.enabled }.id)
    }

    @Test
    fun legacyCleanupWithoutAnExplicitTypeSelectionNeverSendsGc() {
        val fixture = Fixture()
        val results = mutableListOf<SettingsResult<SettingsStorage>>()
        fixture.api.optimizeStorage(results::add)
        fixture.api.optimizeStorage(results::add)
        assertTrue(fixture.requests.isEmpty())
        assertEquals(listOf(SettingsError.UNSUPPORTED, SettingsError.UNSUPPORTED), results.map { it.error })
    }

    @Test
    fun legacyCleanupAndDuplicatedProxyCallbacksCannotProduceSuccess() {
        val fixture = Fixture()
        val cleanup = mutableListOf<SettingsResult<SettingsStorage>>()
        fixture.api.optimizeStorage(cleanup::add)
        assertEquals(SettingsError.UNSUPPORTED, cleanup.single().error)
        assertTrue(fixture.requests.isEmpty())

        val results = mutableListOf<SettingsResult<Unit>>()
        fixture.api.enableProxy(null, results::add)
        val disable = fixture.requests.last()
        assertIs<TdApi.DisableProxy>(disable.command)
        disable.fail()
        disable.respond(TdApi.Ok())
        disable.respond(TdApi.Error(400, "private proxy credentials"))
        assertEquals(SettingsError.NOT_READY, results.single().error)
        assertFalse(results.toString().contains("private proxy credentials"))
    }

    @Test
    fun signedSessionIdentitiesAndCurrentFlagsSurviveTheBoundedNativeList() {
        val fixture = Fixture()
        val results = mutableListOf<SettingsResult<List<SettingsSession>>>()
        fixture.api.sessions(results::add)
        fixture.requests.single().respond(TdApi.Sessions(Array(300) { index ->
            TdApi.Session().apply {
                id = -(index + 1L)
                isCurrent = index == 0
                deviceModel = "Native Device $index"
                applicationName = "Native Application"
                applicationVersion = "1"
                platform = "Windows"
                systemVersion = "11"
                lastActiveDate = 123
                ipAddress = "192.0.2.1"
                location = "Native Location"
            }
        }, 180))
        val sessions = assertNotNull(results.single().value)
        assertEquals(256, sessions.size)
        assertEquals(-1L, sessions.first().id)
        assertTrue(sessions.first().isCurrent)
        assertEquals("Native Device 0", sessions.first().deviceModel)
        assertFalse(sessions.first().toString().contains("192.0.2.1"))
        val terminationResults = mutableListOf<SettingsResult<Unit>>()
        fixture.api.terminateSession(-2, terminationResults::add)
        assertEquals(-2L, assertIs<TdApi.TerminateSession>(fixture.requests.last().command).sessionId)
        fixture.requests.last().respond(TdApi.Ok())
        assertEquals(Unit, terminationResults.single().value)
        fixture.api.terminateSession(0, terminationResults::add)
        assertEquals(SettingsError.INVALID_VALUE, terminationResults.last().error)
        assertEquals(2, fixture.requests.size)
    }

    @Test
    fun blockedPageRetainsRealSenderIdentitiesWithoutInventingNamesOrLoadingAnotherPage() {
        val fixture = Fixture()
        val results = mutableListOf<SettingsResult<List<SettingsBlockedSender>>>()
        fixture.api.blocked(results::add)
        val request = assertIs<TdApi.GetBlockedMessageSenders>(fixture.requests.single().command)
        assertIs<TdApi.BlockListMain>(request.blockList)
        assertEquals(0, request.offset)
        assertEquals(100, request.limit)
        val identities = Array<TdApi.MessageSender>(102) { index ->
            if (index == 1) TdApi.MessageSenderChat(-200) else TdApi.MessageSenderUser(index + 1L)
        }
        fixture.requests.single().respond(TdApi.MessageSenders(900, identities))
        for (index in 0 until 100) {
            val pending = fixture.requests[index + 1]
            when (val lookup = pending.command) {
                is TdApi.GetUser -> if (lookup.userId == 3L) {
                    pending.respond(TdApi.Error(400, "private lookup error"))
                } else pending.respond(TdApi.User().apply {
                    id = lookup.userId
                    firstName = "Native"
                    lastName = "User ${lookup.userId}"
                })
                is TdApi.GetChat -> pending.respond(TdApi.Chat().apply { id = lookup.chatId; title = "Native Chat" })
                else -> fail("Unexpected blocked sender lookup")
            }
        }
        val blocked = assertNotNull(results.single().value)
        assertEquals(100, blocked.size)
        assertEquals(SettingsBlockedSender(1, false, "Native User 1"), blocked[0])
        assertEquals(SettingsBlockedSender(-200, true, "Native Chat"), blocked[1])
        assertEquals(SettingsBlockedSender(3, false, ""), blocked[2])
        assertEquals(101, fixture.requests.size)
        assertFalse(blocked.any { it.id == 101L || it.id == 102L })
        assertFalse(results.toString().contains("private lookup error"))

        val unblocked = mutableListOf<SettingsResult<Unit>>()
        fixture.api.unblock(blocked[1], unblocked::add)
        val unblock = assertIs<TdApi.SetMessageSenderBlockList>(fixture.requests.last().command)
        assertEquals(-200L, assertIs<TdApi.MessageSenderChat>(unblock.senderId).chatId)
        assertNull(unblock.blockList)
        fixture.requests.last().respond(TdApi.Ok())
        assertEquals(Unit, unblocked.single().value)
    }

    @Test
    fun ttlUsesThePinnedNativeRangeAndErrorClassificationNeverExposesResponseText() {
        val fixture = Fixture()
        val results = mutableListOf<SettingsResult<Unit>>()
        fixture.api.setAccountTtl(29, results::add)
        fixture.api.setAccountTtl(731, results::add)
        assertTrue(fixture.requests.isEmpty())
        fixture.api.setAccountTtl(730, results::add)
        assertEquals(730, assertIs<TdApi.SetAccountTtl>(fixture.requests.single().command).ttl.days)
        fixture.requests.single().respond(TdApi.Error(429, "private phone number"))
        assertEquals(SettingsError.RATE_LIMITED, results.last().error)
        assertFalse(results.toString().contains("private phone number"))
        assertEquals(SettingsError.NOT_READY, settingsError(TdApi.Error(401, "AUTH_KEY_UNREGISTERED")))
        assertEquals(SettingsError.FORBIDDEN, settingsError(TdApi.Error(403, "private session info")))
        assertEquals(SettingsError.REQUEST_FAILED, settingsError(TdApi.Ok()))
    }

    private class Fixture {
        val requests = mutableListOf<Pending>()
        val api = OfficialTdSettingsAdvanced { command, respond, fail -> requests += Pending(command, respond, fail) }
    }

    private data class Pending(
        val command: TdApi.Function<*>,
        val respond: (TdApi.Object) -> Unit,
        val fail: () -> Unit,
    )
}
