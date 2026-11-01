package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import kotlin.test.*

class OfficialTdSettingsSecurityTest {
    @Test
    fun nativeBlockUpdatesNotifyOnlyTheCurrentObserverAndRetirementReleasesIt() {
        val api = OfficialTdSettingsSession { _, _, _ -> fail("Observing updates must not send native requests") }
        var changed = 0
        api.observeSecurityChanges { changed++ }
        api.accept(TdApi.UpdateChatBlockList(-1, TdApi.BlockListMain()))
        api.accept(TdApi.UpdateUserFullInfo(10, TdApi.UserFullInfo()))
        api.accept(TdApi.UpdateUser(TdApi.User()))
        assertEquals(2, changed)
        api.observeSecurityChanges(null)
        api.accept(TdApi.UpdateChatBlockList(-1, null))
        assertEquals(2, changed)
        api.observeSecurityChanges { changed++ }
        api.retire()
        api.accept(TdApi.UpdateChatBlockList(-1, null))
        assertEquals(2, changed)
    }
    @Test
    fun privacyEntryDoesNotLoadAnyManagementListsOrPasswordState() {
        val calls = mutableListOf<TdApi.Function<*>>()
        var received: SettingsResult<SettingsPrivacyData>? = null
        OfficialTdSettingsSession { command, result, _ ->
            calls += command
            when (command) {
                is TdApi.GetUserPrivacySettingRules -> result(TdApi.UserPrivacySettingRules(emptyArray()))
                is TdApi.GetAccountTtl -> result(TdApi.AccountTtl(365))
                else -> fail("Privacy entry must request only privacy rules and TTL")
            }
        }.privacy { received = it }
        assertEquals(PrivacyKey.entries.size + 1, calls.size)
        assertEquals(365, received?.value?.accountTtlDays)
        assertTrue(assertNotNull(received?.value).sessions.isEmpty())
        assertTrue(assertNotNull(received?.value).blocked.isEmpty())
    }

    @Test
    fun blockedCursorAdvancesByRawSliceRatherThanResolvedOrDeduplicatedRows() {
        val fixture = Fixture()
        val results = mutableListOf<SettingsResult<SettingsBlockedPage>>()
        fixture.api.blockedPage(0, results::add)
        val first = assertIs<TdApi.GetBlockedMessageSenders>(fixture.requests.single().command)
        assertEquals(16, first.limit)
        fixture.requests.single().respond(TdApi.MessageSenders(90, arrayOf(
            TdApi.MessageSenderUser(10), TdApi.MessageSenderUser(10), TdApi.MessageSenderUser(0), TdApi.MessageSenderChat(-20),
        )))
        assertEquals(10L, assertIs<TdApi.GetUser>(fixture.requests.last().command).userId)
        fixture.requests.last().respond(TdApi.Error(404, "private name unavailable"))
        assertEquals(-20L, assertIs<TdApi.GetChat>(fixture.requests.last().command).chatId)
        fixture.requests.last().respond(TdApi.Chat().apply { id = -20; title = "Native group" })
        val page = assertNotNull(results.single().value)
        assertEquals(4, page.nextOffset)
        assertEquals(90, page.totalCount)
        assertFalse(page.end)
        assertEquals(listOf(10L, -20L), page.entries.map { it.id })
        assertEquals("", page.entries.first().title)
        fixture.api.blockedPage(page.nextOffset, results::add)
        val second = assertIs<TdApi.GetBlockedMessageSenders>(fixture.requests.last().command)
        assertEquals(4, second.offset)
        assertEquals(40, second.limit)
        fixture.requests.last().respond(TdApi.MessageSenders(90, emptyArray()))
        assertTrue(assertNotNull(results.last().value).end)
        assertEquals(4, results.last().value?.nextOffset)
    }

    @Test
    fun aWebsiteConfirmationRevalidatesTheActualDomainAndBotBeforeDisconnecting() {
        val fixture = Fixture()
        val results = mutableListOf<SettingsResult<SettingsWebsiteDisconnectOutcome>>()
        fixture.api.disconnect(website(), true, results::add)
        fixture.requests.single().respond(TdApi.ConnectedWebsites(arrayOf(nativeWebsite(domain = "changed.test"))))
        assertEquals(SettingsError.NOT_READY, results.single().error)
        assertEquals(1, fixture.requests.size)
        assertTrue(fixture.requests.none { it.command is TdApi.DisconnectWebsite || it.command is TdApi.SetMessageSenderBlockList })
    }

    @Test
    fun disconnectSuccessAndBotBlockFailureRemainAVisiblePartialResult() {
        val fixture = Fixture()
        val results = mutableListOf<SettingsResult<SettingsWebsiteDisconnectOutcome>>()
        fixture.api.disconnect(website(), true, results::add)
        fixture.requests.single().respond(TdApi.ConnectedWebsites(arrayOf(nativeWebsite())))
        val disconnect = fixture.requests.last()
        assertEquals(-77L, assertIs<TdApi.DisconnectWebsite>(disconnect.command).websiteId)
        disconnect.respond(TdApi.Ok())
        disconnect.respond(TdApi.Ok())
        fixture.requests.last().respond(TdApi.User().apply { id = 42; type = TdApi.UserTypeBot() })
        val block = fixture.requests.last()
        val command = assertIs<TdApi.SetMessageSenderBlockList>(block.command)
        assertEquals(42L, assertIs<TdApi.MessageSenderUser>(command.senderId).userId)
        assertIs<TdApi.BlockListMain>(command.blockList)
        block.respond(TdApi.Error(403, "Private bot details"))
        block.respond(TdApi.Ok())
        assertEquals(SettingsWebsiteDisconnectOutcome(true, false), results.single().value)
        assertEquals(SettingsError.FORBIDDEN, results.single().error)
        assertFalse(results.toString().contains("Private"))
    }

    @Test
    fun anUnresolvedWebsiteBotIsNeverReplacedWithTheWebsiteIdentityAndAllDisconnectNeverBlocks() {
        val fixture = Fixture()
        val results = mutableListOf<SettingsResult<SettingsWebsiteDisconnectOutcome>>()
        fixture.api.disconnect(website(), true, results::add)
        fixture.requests.single().respond(TdApi.ConnectedWebsites(arrayOf(nativeWebsite())))
        fixture.requests.last().respond(TdApi.Ok())
        fixture.requests.last().respond(TdApi.User().apply { id = 42; type = TdApi.UserTypeRegular() })
        assertTrue(fixture.requests.none { it.command is TdApi.SetMessageSenderBlockList })
        assertEquals(true, results.single().value?.disconnected)
        assertEquals(false, results.single().value?.botBlocked)
        var all: SettingsResult<Unit>? = null
        fixture.api.disconnectAll { all = it }
        assertIs<TdApi.DisconnectAllWebsites>(fixture.requests.last().command)
        fixture.requests.last().respond(TdApi.Ok())
        assertEquals(Unit, all?.value)
        assertTrue(fixture.requests.none { it.command is TdApi.SetMessageSenderBlockList })
    }

    @Test
    fun websitesKeepRealMetadataSignedIdentitiesAndTheirNativeTotalWithinABoundedView() {
        val fixture = Fixture()
        var result: SettingsResult<SettingsWebsitesData>? = null
        fixture.api.websites { result = it }
        fixture.requests.single().respond(TdApi.ConnectedWebsites(Array(600) { index -> nativeWebsite().apply {
            id = -(index + 1L); lastActiveDate = index
        } }))
        val value = assertNotNull(result?.value)
        assertEquals(600, value.totalCount)
        assertEquals(512, value.entries.size)
        assertEquals(-600L, value.entries.first().id)
        assertEquals("native.test", value.entries.first().domain)
        assertFalse(value.entries.toString().contains("native.test"))
    }

    private class Fixture {
        val requests = mutableListOf<Pending>()
        val api = OfficialTdSettingsSecurity { command, response, failed -> requests += Pending(command, response, failed) }
    }
    private data class Pending(val command: TdApi.Function<*>, val respond: (TdApi.Object) -> Unit, val fail: () -> Unit)
    private companion object {
        fun website() = SettingsWebsite(-77, "native.test", 42, "Native browser", "Windows", 1, 2, "192.0.2.1", "Native location")
        fun nativeWebsite(domain: String = "native.test") = TdApi.ConnectedWebsite(-77, domain, 42,
            "Native browser", "Windows", 1, 2, "192.0.2.1", "Native location")
    }
}
