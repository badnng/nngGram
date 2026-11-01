package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import kotlin.test.*

class OfficialTdSettingsPrivacyPeersTest {
    @Test
    fun malformedQueriesNeverRequestNativeContextOrIdentityMetadata() {
        val fixture = Fixture()
        val results = mutableListOf<SettingsResult<SettingsPrivacyPeers>>()
        listOf(
            query().copy(limit = 0), query().copy(limit = 33), query().copy(limit = 101),
            query().copy(query = "x".repeat(257)),
            query(PrivacyPeerSource.SERVER).copy(query = "  "),
            query(PrivacyPeerSource.SELECTED).copy(selected = List(33) { PrivacyPeerId(it + 1L, false) }),
        ).forEach { fixture.api.search(it, results::add) }
        assertTrue(fixture.requests.isEmpty())
        assertEquals(List(6) { SettingsError.INVALID_VALUE }, results.map { it.error })
    }

    @Test
    fun eachExplicitLimitIsPassedToNativeContactsWithoutInventingACursorOrTotal() {
        val fixture = Fixture()
        for (limit in listOf(32, 64, 100)) {
            val results = mutableListOf<SettingsResult<SettingsPrivacyPeers>>()
            fixture.api.search(query().copy(query = "Native contact", limit = limit), results::add)
            fixture.context()
            val search = fixture.requests.last()
            assertIs<TdApi.SearchContacts>(search.command).let {
                assertEquals("Native contact", it.query)
                assertEquals(limit, it.limit)
            }
            search.respond(TdApi.Users().apply { totalCount = 999; userIds = longArrayOf(51) })
            fixture.user(51)
            val peers = assertNotNull(results.single().value)
            assertEquals(listOf(PrivacyPeerId(51, false)), peers.entries.map { it.identity })
            assertEquals("First 51 Last", peers.entries.single().title)
            assertFalse(peers.mayHaveMore) // Native totalCount is not a cursor or an exact filtered total.
        }
        assertMetadataOnly(fixture)
    }

    @Test
    fun selfRepliesAndVerificationAreFilteredFromRawContactsWithoutResolvingTheirUsers() {
        val fixture = Fixture()
        val results = mutableListOf<SettingsResult<SettingsPrivacyPeers>>()
        fixture.api.search(query(), results::add)
        fixture.context()
        fixture.requests.last().respond(TdApi.Users().apply {
            totalCount = 100; userIds = LongArray(32) { listOf(43L, 47L, 48L)[it % 3] }
        })
        val peers = assertNotNull(results.single().value)
        assertTrue(peers.entries.isEmpty())
        assertTrue(peers.mayHaveMore) // Filtering must not pretend the raw native slice was exhausted.
        assertTrue(fixture.requests.none { it.command is TdApi.GetUser })
        assertEquals(4, fixture.requests.size)
        assertMetadataOnly(fixture)
    }

    @Test
    fun localMainChatsLocalQueryAndExplicitServerSearchUseTheirRealNativeSources() {
        for ((source, text) in listOf(PrivacyPeerSource.CHATS to "", PrivacyPeerSource.CHATS to "known",
            PrivacyPeerSource.SERVER to "public")) {
            val fixture = Fixture()
            val results = mutableListOf<SettingsResult<SettingsPrivacyPeers>>()
            fixture.api.search(query(source).copy(query = text, limit = 64), results::add)
            fixture.context()
            val search = fixture.requests.last()
            when {
                source == PrivacyPeerSource.SERVER -> assertIs<TdApi.SearchChatsOnServer>(search.command).let {
                    assertEquals(text, it.query); assertEquals(64, it.limit)
                }
                text.isEmpty() -> assertIs<TdApi.GetChats>(search.command).let {
                    assertIs<TdApi.ChatListMain>(it.chatList); assertEquals(64, it.limit)
                }
                else -> assertIs<TdApi.SearchChats>(search.command).let {
                    assertEquals(text, it.query); assertEquals(64, it.limit)
                }
            }
            search.respond(TdApi.Chats().apply { chatIds = longArrayOf() })
            assertEquals(SettingsPrivacyPeers(emptyList(), false), results.single().value)
            assertMetadataOnly(fixture)
        }
    }

    @Test
    fun nativePrivateChatResolvesToItsRealUserAndOnlyRealActiveGroupsRemainSelectable() {
        val fixture = Fixture()
        val results = mutableListOf<SettingsResult<SettingsPrivacyPeers>>()
        val selected = listOf(PrivacyPeerId(11, true), PrivacyPeerId(-20, true), PrivacyPeerId(-30, true),
            PrivacyPeerId(-40, true), PrivacyPeerId(-50, true))
        fixture.api.search(query(PrivacyPeerSource.SELECTED).copy(selected = selected), results::add)
        fixture.context()
        fixture.chat(11, TdApi.ChatTypePrivate().apply { userId = 51 })
        fixture.user(51)
        fixture.chat(-20, TdApi.ChatTypeBasicGroup().apply { basicGroupId = 200 })
        assertEquals(200L, assertIs<TdApi.GetBasicGroup>(fixture.requests.last().command).basicGroupId)
        fixture.requests.last().respond(TdApi.BasicGroup().apply { id = 200; isActive = true })
        fixture.chat(-30, TdApi.ChatTypeSupergroup().apply { supergroupId = 300; isChannel = false })
        assertEquals(300L, assertIs<TdApi.GetSupergroup>(fixture.requests.last().command).supergroupId)
        fixture.requests.last().respond(TdApi.Supergroup().apply { id = 300; isChannel = false })
        fixture.chat(-40, TdApi.ChatTypeSupergroup().apply { supergroupId = 400; isChannel = true })
        fixture.chat(-50, TdApi.ChatTypeSecret().apply { userId = 52 })
        val peers = assertNotNull(results.single().value)
        assertEquals(listOf(PrivacyPeerId(51, false), PrivacyPeerId(-20, true), PrivacyPeerId(-30, true)), peers.entries.map { it.identity })
        assertEquals(listOf(false, true, true), peers.entries.map { it.isGroup })
        assertFalse(peers.mayHaveMore)
        assertTrue(fixture.requests.none { it.command is TdApi.GetSupergroup && it.command.supergroupId == 400L })
        assertMetadataOnly(fixture)
    }

    @Test
    fun mismatchedNativeIdsDeletedUsersInactiveGroupsAndDirectMessageSupergroupsAreNeverFabricated() {
        val fixture = Fixture()
        val results = mutableListOf<SettingsResult<SettingsPrivacyPeers>>()
        val selected = listOf(PrivacyPeerId(51, false), PrivacyPeerId(52, false), PrivacyPeerId(-20, true),
            PrivacyPeerId(-30, true), PrivacyPeerId(-40, true), PrivacyPeerId(-50, true))
        fixture.api.search(query(PrivacyPeerSource.SELECTED).copy(selected = selected), results::add)
        fixture.context()
        assertEquals(51L, assertIs<TdApi.GetUser>(fixture.requests.last().command).userId)
        fixture.requests.last().respond(TdApi.User().apply { id = 999; type = TdApi.UserTypeRegular() })
        assertEquals(52L, assertIs<TdApi.GetUser>(fixture.requests.last().command).userId)
        fixture.requests.last().respond(TdApi.User().apply { id = 52; type = TdApi.UserTypeDeleted() })
        assertEquals(-20L, assertIs<TdApi.GetChat>(fixture.requests.last().command).chatId)
        fixture.requests.last().respond(TdApi.Chat().apply { id = -999; type = TdApi.ChatTypeBasicGroup() })
        fixture.chat(-30, TdApi.ChatTypeBasicGroup().apply { basicGroupId = 300 })
        fixture.requests.last().respond(TdApi.BasicGroup().apply { id = 300; isActive = false })
        fixture.chat(-40, TdApi.ChatTypeBasicGroup().apply { basicGroupId = 400 })
        fixture.requests.last().respond(TdApi.BasicGroup().apply { id = 999; isActive = true })
        fixture.chat(-50, TdApi.ChatTypeSupergroup().apply { supergroupId = 500 })
        fixture.requests.last().respond(TdApi.Supergroup().apply { id = 500; isDirectMessagesGroup = true })
        assertEquals(SettingsPrivacyPeers(emptyList(), false), results.single().value)
        assertMetadataOnly(fixture)
    }

    @Test
    fun selectedResolutionRemainsBoundedAndDeduplicatesTheActualIdentity() {
        val fixture = Fixture()
        val results = mutableListOf<SettingsResult<SettingsPrivacyPeers>>()
        val selected = (101L..131L).map { PrivacyPeerId(it, false) } + PrivacyPeerId(101, false)
        fixture.api.search(query(PrivacyPeerSource.SELECTED).copy(selected = selected), results::add)
        fixture.context()
        for (id in 101L..131L) fixture.user(id)
        val peers = assertNotNull(results.single().value)
        assertEquals(31, peers.entries.size)
        assertEquals(31, fixture.requests.count { it.command is TdApi.GetUser })
        assertFalse(peers.mayHaveMore)
        assertMetadataOnly(fixture)
    }

    @Test
    fun aFailedIdentityLookupDoesNotPublishInventedNamesAndEveryNativeCompletionIsUsedOnce() {
        val fixture = Fixture()
        val results = mutableListOf<SettingsResult<SettingsPrivacyPeers>>()
        fixture.api.search(query(PrivacyPeerSource.SELECTED).copy(selected = listOf(PrivacyPeerId(51, false))), results::add)
        fixture.context(duplicates = true)
        val user = fixture.requests.last()
        user.respond(TdApi.Error(403, "private native contact detail"))
        user.respond(TdApi.User().apply { id = 51; firstName = "Late name"; type = TdApi.UserTypeRegular() })
        user.fail()
        assertNull(results.single().value)
        assertEquals(SettingsError.FORBIDDEN, results.single().error)
        assertFalse(results.toString().contains("private native"))
        assertFalse(results.toString().contains("Late name"))
        assertMetadataOnly(fixture)
    }

    @Test
    fun retiredScopedSendStopsTheNativeSearchChainBeforeItsNextMetadataRequest() {
        val fixture = Fixture()
        val results = mutableListOf<SettingsResult<SettingsPrivacyPeers>>()
        fixture.api.search(query(), results::add)
        val me = fixture.requests.single()
        fixture.current = false
        me.respond(TdApi.User().apply { id = 43; type = TdApi.UserTypeRegular() })
        me.respond(TdApi.User().apply { id = 43 })
        me.fail()
        assertEquals(1, fixture.requests.size)
        assertEquals(SettingsError.REQUEST_FAILED, results.single().error)
        assertNull(results.single().value)
    }

    private data class Request(val command: TdApi.Function<*>, val respond: (TdApi.Object) -> Unit, val fail: () -> Unit)
    private class Fixture {
        val requests = mutableListOf<Request>()
        var current = true
        val api = OfficialTdSettingsPrivacyPeers { command, respond, fail ->
            if (current) requests += Request(command, respond, fail) else fail()
        }
        fun context(duplicates: Boolean = false, premium: Boolean = false) {
            val me = requests.last()
            assertIs<TdApi.GetMe>(me.command)
            me.respond(TdApi.User().apply { id = 43; isPremium = premium; type = TdApi.UserTypeRegular() })
            if (duplicates) { me.respond(TdApi.User().apply { id = 999 }); me.fail() }
            val replies = requests.last()
            assertEquals("replies_bot_chat_id", assertIs<TdApi.GetOption>(replies.command).name)
            replies.respond(TdApi.OptionValueInteger(47))
            if (duplicates) { replies.respond(TdApi.OptionValueInteger(999)); replies.fail() }
            val verification = requests.last()
            assertEquals("verification_codes_bot_chat_id", assertIs<TdApi.GetOption>(verification.command).name)
            verification.respond(TdApi.OptionValueInteger(48))
            if (duplicates) { verification.respond(TdApi.OptionValueInteger(999)); verification.fail() }
        }
        fun user(id: Long) {
            assertEquals(id, assertIs<TdApi.GetUser>(requests.last().command).userId)
            requests.last().respond(TdApi.User().apply { this.id = id; firstName = "First $id"; lastName = "Last"; type = TdApi.UserTypeRegular() })
        }
        fun chat(id: Long, type: TdApi.ChatType) {
            assertEquals(id, assertIs<TdApi.GetChat>(requests.last().command).chatId)
            requests.last().respond(TdApi.Chat().apply { this.id = id; title = "Native chat $id"; this.type = type })
        }
    }
    private fun query(source: PrivacyPeerSource = PrivacyPeerSource.CONTACTS) =
        PrivacyPeerQuery(PrivacyExceptionTarget.ALLOW, 5, 8, source)

    private fun assertMetadataOnly(fixture: Fixture) {
        assertTrue(fixture.requests.all { it.command is TdApi.GetMe || it.command is TdApi.GetOption ||
            it.command is TdApi.GetUser || it.command is TdApi.GetChat || it.command is TdApi.GetBasicGroup ||
            it.command is TdApi.GetSupergroup || it.command is TdApi.SearchContacts || it.command is TdApi.SearchChats ||
            it.command is TdApi.SearchChatsOnServer || it.command is TdApi.GetChats })
    }
}
