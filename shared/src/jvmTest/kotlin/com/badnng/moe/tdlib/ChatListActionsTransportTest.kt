package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import org.drinkless.tdlib.TdApi
import kotlin.test.*

class ChatListActionsTransportTest {
    @Test
    fun archiveAndPinActionsUseTheirActualNativeList() {
        val fixture = Fixture()
        fixture.session.performChatListAction(10, ChatListRef(), ChatListAction.ARCHIVE) { }
        assertIs<TdApi.ChatListArchive>(assertIs<TdApi.AddChatToList>(fixture.last.command).chatList)
        fixture.session.performChatListAction(10, ChatListRef(-1), ChatListAction.UNARCHIVE) { }
        assertIs<TdApi.ChatListMain>(assertIs<TdApi.AddChatToList>(fixture.last.command).chatList)
        fixture.session.performChatListAction(10, ChatListRef(7), ChatListAction.PIN) { }
        val pin = assertIs<TdApi.ToggleChatIsPinned>(fixture.last.command)
        assertEquals(7, assertIs<TdApi.ChatListFolder>(pin.chatList).chatFolderId)
        assertTrue(pin.isPinned)
        fixture.session.performChatListAction(10, ChatListRef(7), ChatListAction.UNPIN) { }
        assertFalse(assertIs<TdApi.ToggleChatIsPinned>(fixture.last.command).isPinned)
    }

    @Test
    fun mutingChangesOnlyTheMuteOverrideAndPreservesAllOtherNativeNotificationSettings() {
        val fixture = Fixture()
        val original = TdApi.ChatNotificationSettings(true, 0, true, 99, true, false, true, true,
            true, 88, true, false, true, true, true, false)
        val results = mutableListOf<ChatResult<Unit>>()
        fixture.session.performChatListAction(10, ChatListRef(), ChatListAction.MUTE, results::add)
        fixture.last.result(TdApi.Chat().apply { id = 10; notificationSettings = original })
        val updated = assertIs<TdApi.SetChatNotificationSettings>(fixture.last.command).notificationSettings
        assertFalse(updated.useDefaultMuteFor)
        assertEquals(Int.MAX_VALUE, updated.muteFor)
        assertEquals(99L, updated.soundId)
        assertEquals(88L, updated.storySoundId)
        assertEquals(original.useDefaultSound, updated.useDefaultSound)
        assertEquals(original.useDefaultShowPreview, updated.useDefaultShowPreview)
        assertEquals(original.showPreview, updated.showPreview)
        assertEquals(original.useDefaultMuteStories, updated.useDefaultMuteStories)
        assertEquals(original.muteStories, updated.muteStories)
        assertEquals(original.useDefaultShowStoryPoster, updated.useDefaultShowStoryPoster)
        assertEquals(original.disablePinnedMessageNotifications, updated.disablePinnedMessageNotifications)
        assertEquals(original.disableMentionNotifications, updated.disableMentionNotifications)
        assertTrue(original.useDefaultMuteFor)
        fixture.last.result(TdApi.Ok())
        assertEquals(10L, assertIs<TdApi.GetChat>(fixture.last.command).chatId)
        assertTrue(results.isEmpty(), "The shared lane remains owned until the actual readback finishes.")
        fixture.last.result(TdApi.Chat().apply { id = 10; notificationSettings = updated })
        assertEquals(Unit, results.single().value)
    }

    @Test
    fun addingToAFolderRetainsRichNameRulesPinsAndUnrelatedOverrides() {
        val fixture = Fixture()
        fixture.session.addChatToFolder(10, 7) { }
        assertEquals(7, assertIs<TdApi.GetChatFolder>(fixture.last.command).chatFolderId)
        val original = TdApi.ChatFolder().apply {
            name = TdApi.ChatFolderName(TdApi.FormattedText("Synthetic", arrayOf(TdApi.TextEntity(0, 3, TdApi.TextEntityTypeBold()))), true)
            icon = TdApi.ChatFolderIcon("Custom"); colorId = 4
            pinnedChatIds = longArrayOf(20); includedChatIds = longArrayOf(30); excludedChatIds = longArrayOf(10, 40)
            excludeMuted = true; excludeRead = true; excludeArchived = true
            includeContacts = true; includeNonContacts = true; includeBots = true; includeGroups = true; includeChannels = true
        }
        fixture.last.result(original)
        val edited = assertIs<TdApi.EditChatFolder>(fixture.last.command)
        assertEquals(7, edited.chatFolderId)
        assertSame(original.name, edited.folder.name)
        assertSame(original.icon, edited.folder.icon)
        assertContentEquals(longArrayOf(20), edited.folder.pinnedChatIds)
        assertContentEquals(longArrayOf(30, 10), edited.folder.includedChatIds)
        assertContentEquals(longArrayOf(40), edited.folder.excludedChatIds)
        assertTrue(edited.folder.excludeMuted && edited.folder.excludeRead && edited.folder.excludeArchived)
        assertTrue(edited.folder.includeContacts && edited.folder.includeNonContacts && edited.folder.includeBots &&
            edited.folder.includeGroups && edited.folder.includeChannels)
        assertContentEquals(longArrayOf(10, 40), original.excludedChatIds)
    }

    @Test
    fun sharedFoldersAreReadOnlyAndAlreadyIncludedChatsDoNotCreateDuplicateRules() {
        val fixture = Fixture()
        val results = mutableListOf<ChatResult<Unit>>()
        fixture.session.addChatToFolder(10, 7, results::add)
        fixture.last.result(TdApi.ChatFolder().apply { isShareable = true })
        assertEquals(ChatError.FORBIDDEN, results.single().error)
        assertEquals(1, fixture.requests.size)
        fixture.session.addChatToFolder(10, 7, results::add)
        fixture.last.result(TdApi.ChatFolder().apply { pinnedChatIds = longArrayOf(10); excludedChatIds = longArrayOf() })
        assertEquals(Unit, results.last().value)
        assertEquals(2, fixture.requests.size)
    }

    @Test
    fun messageLinksRequireNativeCapabilityAndMatchingMessageIdentity() {
        val fixture = Fixture()
        val results = mutableListOf<ChatResult<String>>()
        fixture.session.messageLink(-100, 20, results::add)
        fixture.last.result(TdApi.MessageProperties().apply { canGetLink = false })
        assertEquals(ChatError.FORBIDDEN, results.last().error)
        assertEquals(1, fixture.requests.size)
        fixture.session.messageLink(-100, 20, results::add)
        fixture.last.result(TdApi.MessageProperties().apply { canGetLink = true })
        fixture.last.result(message(-200))
        assertEquals(ChatError.REQUEST_FAILED, results.last().error)
        assertTrue(fixture.requests.none { it.command is TdApi.GetMessageLink })
    }

    @Test
    fun linksAreReturnedOnlyFromNativeHttpsValuesAndDuplicateErrorsRemainSanitized() {
        val fixture = Fixture()
        val results = mutableListOf<ChatResult<String>>()
        fixture.session.messageLink(-100, 20, results::add)
        fixture.last.result(TdApi.MessageProperties().apply { canGetLink = true })
        fixture.last.result(message(-100))
        val command = assertIs<TdApi.GetMessageLink>(fixture.last.command)
        assertEquals(-100L, command.chatId)
        assertEquals(20L, command.messageId)
        assertEquals(0, command.mediaTimestamp)
        assertFalse(command.forAlbum || command.inMessageThread)
        val pending = fixture.last
        pending.result(TdApi.MessageLink("https://t.me/c/100/20", false))
        pending.result(TdApi.Error(403, "synthetic-private-native-text"))
        pending.error()
        assertEquals("https://t.me/c/100/20", results.single().value)
        assertFalse(results.toString().contains("t.me"))
        fixture.session.performChatListAction(10, ChatListRef(), ChatListAction.ARCHIVE) { result ->
            assertEquals(ChatError.FORBIDDEN, result.error)
            assertFalse(result.toString().contains("private"))
        }
        fixture.last.result(TdApi.Error(403, "synthetic-private-native-text"))
    }

    @Test
    fun nonHttpsRelativeAndMalformedNativeLinksAreRejected() {
        for (link in listOf("http://t.me/c/100/20", "javascript:synthetic", "/c/100/20", "https:///missing-host", "https://bad host/20")) {
            val fixture = Fixture()
            var result: ChatResult<String>? = null
            fixture.session.messageLink(-100, 20) { result = it }
            fixture.last.result(TdApi.MessageProperties().apply { canGetLink = true })
            fixture.last.result(message(-100))
            fixture.last.result(TdApi.MessageLink(link, false))
            assertEquals(ChatError.REQUEST_FAILED, result?.error)
            assertNull(result?.value)
        }
    }

    @Test
    fun nativeMuteUpdatesResolveScopeDefaultsWithoutOverwritingExplicitChatOverrides() {
        val events = mutableListOf<ChatEvent>()
        val mapper = TdChatUpdateMapper(events::add)
        mapper.accept(TdApi.UpdateScopeNotificationSettings(TdApi.NotificationSettingsScopePrivateChats(),
            TdApi.ScopeNotificationSettings().apply { muteFor = 1000 }))
        mapper.accept(TdApi.UpdateNewChat(TdApi.Chat().apply {
            id = 10; type = TdApi.ChatTypePrivate(7)
            notificationSettings = TdApi.ChatNotificationSettings().apply { useDefaultMuteFor = true }
        }))
        assertTrue(assertIs<ChatEvent.NewChat>(events.last()).chat.isMuted)
        mapper.accept(TdApi.UpdateChatNotificationSettings(10,
            TdApi.ChatNotificationSettings().apply { useDefaultMuteFor = false; muteFor = 0 }))
        assertEquals(ChatEvent.NotificationMuted(10, false), events.last())
        val count = events.size
        mapper.accept(TdApi.UpdateScopeNotificationSettings(TdApi.NotificationSettingsScopePrivateChats(),
            TdApi.ScopeNotificationSettings().apply { muteFor = 2000 }))
        assertEquals(count, events.size)
        mapper.accept(TdApi.UpdateChatNotificationSettings(10,
            TdApi.ChatNotificationSettings().apply { useDefaultMuteFor = true }))
        assertEquals(ChatEvent.NotificationMuted(10, true), events.last())
    }

    @Test
    fun privateGroupAndChannelMuteScopesAreIsolated() {
        val events = mutableListOf<ChatEvent>()
        val mapper = TdChatUpdateMapper(events::add)
        listOf(10L to TdApi.ChatTypePrivate(7), 20L to TdApi.ChatTypeBasicGroup(9), 30L to TdApi.ChatTypeSupergroup(11, true)).forEach { (id, type) ->
            mapper.accept(TdApi.UpdateNewChat(TdApi.Chat().apply {
                this.id = id; this.type = type
                notificationSettings = TdApi.ChatNotificationSettings().apply { useDefaultMuteFor = true }
            }))
        }
        val scopes = listOf(TdApi.NotificationSettingsScopePrivateChats() to 10L,
            TdApi.NotificationSettingsScopeGroupChats() to 20L, TdApi.NotificationSettingsScopeChannelChats() to 30L)
        scopes.forEach { (scope, expectedId) ->
            val start = events.size
            mapper.accept(TdApi.UpdateScopeNotificationSettings(scope, TdApi.ScopeNotificationSettings().apply { muteFor = 1000 }))
            assertEquals(listOf(ChatEvent.NotificationMuted(expectedId, true)), events.drop(start))
        }
    }

    private fun message(chatId: Long) = TdApi.Message().apply {
        id = 20; this.chatId = chatId; content = TdApi.MessageText(TdApi.FormattedText("synthetic", emptyArray()), null, null)
    }
    private class Fixture {
        val requests = mutableListOf<Request>()
        val session = OfficialTdChatSession { command, result, error -> requests += Request(command, result, error) }
        val last get() = requests.last()
    }
    private class Request(val command: TdApi.Function<*>, val result: (TdApi.Object) -> Unit, val error: () -> Unit)
}
