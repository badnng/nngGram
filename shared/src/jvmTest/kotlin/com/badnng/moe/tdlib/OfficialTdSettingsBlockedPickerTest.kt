package com.badnng.moe.tdlib

import com.badnng.moe.security.LocalLockWitness
import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import kotlin.test.*

class OfficialTdSettingsBlockedPickerTest {
    @Test fun eachSourceUsesNativeSearchAndOnlyPrivateChatsResolveToRealUsers() {
        for ((source, text) in listOf(SettingsBlockedPeerSource.CONTACTS to "person",
            SettingsBlockedPeerSource.CHATS to "", SettingsBlockedPeerSource.CHATS to "known",
            SettingsBlockedPeerSource.SERVER to "public")) {
            val f = Fixture()
            val results = mutableListOf<SettingsResult<SettingsBlockedCandidates>>()
            f.api.search(SettingsBlockedPeerQuery(source, text), results::add)
            f.context()
            val search = f.last
            when {
                source == SettingsBlockedPeerSource.CONTACTS -> assertIs<TdApi.SearchContacts>(search.command).let {
                    assertEquals(text, it.query); assertTrue(it.limit >= 32)
                }
                source == SettingsBlockedPeerSource.SERVER -> assertIs<TdApi.SearchChatsOnServer>(search.command).let { assertEquals(text, it.query) }
                text.isEmpty() -> assertIs<TdApi.GetChats>(search.command).let { assertIs<TdApi.ChatListMain>(it.chatList) }
                else -> assertIs<TdApi.SearchChats>(search.command).let { assertEquals(text, it.query) }
            }
            if (source == SettingsBlockedPeerSource.CONTACTS) search.reply(TdApi.Users(1, longArrayOf(51)))
            else {
                search.reply(TdApi.Chats(3, longArrayOf(101, -20, -30)))
                f.chat(101, TdApi.ChatTypePrivate(51))
            }
            f.user(51)
            f.full(51)
            if (source != SettingsBlockedPeerSource.CONTACTS) {
                f.chat(-20, TdApi.ChatTypeBasicGroup(200))
                f.chat(-30, TdApi.ChatTypeSupergroup(300, true))
            }
            assertEquals(listOf(SettingsBlockedCandidate(51, "Native 51", false)), results.single().value?.entries)
            assertTrue(f.calls.none { it.command is TdApi.OpenChat || it.command is TdApi.DownloadFile || it.command is TdApi.SetMessageSenderBlockList })
        }
    }

    @Test fun excludedNativeIdsAndServiceIdsNeverBecomeRowsAndBlockedUsersRemainDisabled() {
        val f = Fixture()
        val results = mutableListOf<SettingsResult<SettingsBlockedCandidates>>()
        f.api.search(SettingsBlockedPeerQuery(SettingsBlockedPeerSource.CONTACTS), results::add)
        f.context()
        f.last.reply(TdApi.Users(5, longArrayOf(43, 47, 48, 1000, 51)))
        f.user(51)
        f.full(51, blocked = true)
        assertEquals(listOf(SettingsBlockedCandidate(51, "Native 51", true)), results.single().value?.entries)
        assertEquals(listOf(51L), f.calls.mapNotNull { (it.command as? TdApi.GetUser)?.userId })
    }

    @Test fun anUnresolvedNativeIdentityCannotBeReplacedWithTheRequestedFakeIdOrName() {
        val f = Fixture()
        val results = mutableListOf<SettingsResult<SettingsBlockedCandidates>>()
        f.api.search(SettingsBlockedPeerQuery(SettingsBlockedPeerSource.CONTACTS), results::add)
        f.context()
        f.last.reply(TdApi.Users(1, longArrayOf(51)))
        f.last.reply(TdApi.User().apply { id = 999; firstName = "Wrong native identity"; type = TdApi.UserTypeRegular() })
        assertTrue(results.single().value?.entries.orEmpty().isEmpty())
        assertTrue(f.calls.none { it.command is TdApi.GetUserFullInfo || it.command is TdApi.SetMessageSenderBlockList })
        assertFalse(results.toString().contains("Wrong native identity"))
    }

    @Test fun incompleteRawNativeResultsAdvanceWithoutPretendingExhaustionOrASourceLimit() {
        val f = Fixture()
        val results = mutableListOf<SettingsResult<SettingsBlockedCandidates>>()
        f.api.search(SettingsBlockedPeerQuery(SettingsBlockedPeerSource.CONTACTS), results::add)
        f.context()
        f.last.reply(TdApi.Users(900, longArrayOf(51)))
        f.user(51)
        f.full(51)
        val page = assertNotNull(results.single().value)
        assertFalse(page.end)
        assertFalse(page.limitReached)
        assertEquals(1, page.nextOffset)
        f.api.search(SettingsBlockedPeerQuery(SettingsBlockedPeerSource.CONTACTS, offset = page.nextOffset), results::add)
        f.context()
        f.last.reply(TdApi.Users(900, longArrayOf(51, 52)))
        f.user(52); f.full(52)
        assertEquals(listOf(52L), results.last().value?.entries?.map { it.userId })
        assertEquals(2, results.last().value?.nextOffset)
        assertFalse(assertNotNull(results.last().value).end)
    }

    @Test fun nativeServerHundredPrefixReportsItsRealBoundaryInsteadOfAnExactDirectoryEnd() {
        val f = Fixture()
        val results = mutableListOf<SettingsResult<SettingsBlockedCandidates>>()
        f.api.search(SettingsBlockedPeerQuery(SettingsBlockedPeerSource.SERVER, "public", offset = 96), results::add)
        f.context()
        f.last.reply(TdApi.Chats(100, LongArray(100) { it + 1L }))
        for (id in 97L..100L) f.chat(id, TdApi.ChatTypeBasicGroup(id))
        val page = assertNotNull(results.single().value)
        assertEquals(100, page.nextOffset)
        assertFalse(page.end)
        assertTrue(page.limitReached)
    }

    @Test fun actualNativeTerminalsSurviveLockAndUnknownIsNotInventedRejection() {
        for (response in listOf<TdApi.Object>(TdApi.Ok(), TdApi.Error(403, "synthetic forbidden"))) {
            val f = Fixture()
            val receipts = mutableListOf<SettingsSpecialWriteReceipt?>()
            f.api.block(51) { receipts += SettingsSpecialMutationContext.receipt() }
            f.context(); f.user(51); f.full(51)
            val write = f.last
            val request = assertIs<TdApi.SetMessageSenderBlockList>(write.command)
            assertEquals(51L, assertIs<TdApi.MessageSenderUser>(request.senderId).userId)
            assertIs<TdApi.BlockListMain>(request.blockList)
            write.failed()
            assertEquals(SettingsSpecialWriteOutcome.INDETERMINATE, receipts.single()?.outcome)
            f.lock()
            write.reply(response)
            assertEquals(listOf(SettingsSpecialWriteOutcome.INDETERMINATE,
                if (response is TdApi.Ok) SettingsSpecialWriteOutcome.ACCEPTED else SettingsSpecialWriteOutcome.REJECTED),
                receipts.map { it?.outcome })
            assertEquals(1, f.calls.count { it.command is TdApi.SetMessageSenderBlockList })
            assertTrue(write.exactScope, "The physical block command must carry its own exact write scope.")
        }
    }

    @Test fun lockingBeforeTheFirstNativeRequestDispatchesNothing() {
        val f = Fixture()
        f.lock()
        val results = mutableListOf<SettingsResult<Unit>>()
        f.api.block(51, results::add)
        assertTrue(f.calls.isEmpty())
        assertNull(results.single().value)
    }

    private data class Pending(val command: TdApi.Function<*>, val reply: (TdApi.Object) -> Unit,
        val failed: () -> Unit, val exactScope: Boolean)
    private class Fixture {
        var witness = LocalLockWitness(1, false)
        private val gate = TdUserInteractionGate().apply { bindLocalWitness { witness } }
        val calls = mutableListOf<Pending>()
        val api = OfficialTdSettingsBlockedPeers(gate.guard { command, reply, failed ->
            calls += Pending(command, reply, failed, SettingsSpecialMutationContext.marks(command))
        })
        val last get() = calls.last()
        fun lock() { witness = LocalLockWitness(2, true); gate.localWitnessChanged() }
        fun context() {
            assertIs<TdApi.GetMe>(last.command)
            last.reply(TdApi.User().apply { id = 43; type = TdApi.UserTypeRegular() })
            assertEquals("replies_bot_chat_id", assertIs<TdApi.GetOption>(last.command).name)
            last.reply(TdApi.OptionValueInteger(47))
            assertEquals("verification_codes_bot_chat_id", assertIs<TdApi.GetOption>(last.command).name)
            last.reply(TdApi.OptionValueInteger(48))
        }
        fun user(id: Long) {
            assertEquals(id, assertIs<TdApi.GetUser>(last.command).userId)
            last.reply(TdApi.User().apply { this.id = id; firstName = "Native $id"; type = TdApi.UserTypeRegular() })
        }
        fun full(id: Long, blocked: Boolean = false) {
            assertEquals(id, assertIs<TdApi.GetUserFullInfo>(last.command).userId)
            last.reply(TdApi.UserFullInfo().apply { blockList = if (blocked) TdApi.BlockListMain() else null })
        }
        fun chat(id: Long, type: TdApi.ChatType) {
            assertEquals(id, assertIs<TdApi.GetChat>(last.command).chatId)
            last.reply(TdApi.Chat().apply { this.id = id; this.type = type })
        }
    }
}
