package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import com.badnng.moe.chatFolderTags
import org.drinkless.tdlib.TdApi
import kotlin.test.*

class FolderMembershipTransportTest {
    @Test fun orderingPositionsNeverAuthorizeATagAndNativeMembershipNeedsNoFolderPosition() {
        val f = Fixture()
        f.mapper.accept(TdApi.UpdateNewChat(chat(10).apply {
            positions = arrayOf(TdApi.ChatPosition(TdApi.ChatListFolder(2), 900, false, null))
            chatLists = emptyArray()
        }))
        assertTrue(chatFolderTags(f.state.knownChats.single(), f.state).isEmpty())
        f.mapper.accept(TdApi.UpdateNewChat(chat(10).apply {
            positions = emptyArray(); chatLists = arrayOf(TdApi.ChatListFolder(2))
        }))
        val item = f.state.knownChats.single()
        assertTrue(item.positions.isEmpty())
        assertEquals(listOf(2), chatFolderTags(item, f.state).map { it.id })
    }

    @Test fun nativeAddedRemovedAndNewChatMetadataUpdateTheActualMembershipWithoutLoadingMessages() {
        val f = Fixture()
        f.mapper.accept(TdApi.UpdateChatAddedToList(10, TdApi.ChatListFolder(2)))
        assertTrue(f.state.knownChats.isEmpty(), "An unknown native identity must not create a fabricated chat.")
        f.mapper.accept(TdApi.UpdateNewChat(chat(10).apply { chatLists = emptyArray() }))
        f.mapper.accept(TdApi.UpdateChatAddedToList(10, TdApi.ChatListFolder(2)))
        assertEquals(setOf(ChatListRef(2)), f.state.knownChats.single().chatLists)
        f.mapper.accept(TdApi.UpdateChatRemovedFromList(10, TdApi.ChatListFolder(2)))
        f.mapper.accept(TdApi.UpdateChatPosition(10, TdApi.ChatPosition(TdApi.ChatListFolder(2), 800, false, null)))
        assertTrue(f.state.knownChats.single().chatLists!!.isEmpty())
        assertTrue(chatFolderTags(f.state.knownChats.single(), f.state).isEmpty())
        assertTrue(f.commands.isEmpty(), "Membership updates must not open/read history or download files.")
    }

    @Test fun initiallyUnknownMembershipStaysUnknownUntilFullNativeChatMetadataArrives() {
        val f = Fixture()
        f.mapper.accept(TdApi.UpdateNewChat(chat(10).apply { chatLists = null }))
        f.mapper.accept(TdApi.UpdateChatAddedToList(10, TdApi.ChatListFolder(2)))
        assertNull(f.state.knownChats.single().chatLists)
        assertTrue(chatFolderTags(f.state.knownChats.single(), f.state).isEmpty())
        f.mapper.accept(TdApi.UpdateNewChat(chat(10).apply { chatLists = arrayOf(TdApi.ChatListFolder(2)) }))
        assertEquals(listOf(2), chatFolderTags(f.state.knownChats.single(), f.state).map { it.id })
    }

    @Test fun nativeTagEnableOrderColorAndCurrentFolderExclusionRemainIndependentFromPremiumDisplay() {
        val f = Fixture()
        f.mapper.accept(TdApi.UpdateNewChat(chat(10).apply { chatLists = arrayOf(TdApi.ChatListFolder(2), TdApi.ChatListFolder(3)) }))
        val item = f.state.knownChats.single()
        val source = f.state.copy(folders = listOf(ChatFolderInfo(3, "Life", colorId = 5), ChatFolderInfo(2, "Work", colorId = 2)),
            account = AccountProfile(43, "Synthetic", isPremium = true))
        assertEquals(listOf(3, 2), chatFolderTags(item, source).map { it.id })
        assertEquals(listOf(3), chatFolderTags(item, source.copy(activeList = ChatListRef(2))).map { it.id })
        assertEquals(listOf(2), chatFolderTags(item, source.copy(folders = source.folders.map { if (it.id == 3) it.copy(colorId = -1) else it })).map { it.id })
        // Entry::hasChatsFilterTags checks native tagsEnabled and color membership, not Premium.
        assertEquals(listOf(3, 2), chatFolderTags(item, source.copy(account = source.account!!.copy(isPremium = false))).map { it.id })
        assertTrue(chatFolderTags(item, source.copy(folderTagsEnabled = false)).isEmpty())
    }

    @Test fun updateFoldersPreservesTagsAndInviteOwnershipMetadataWithoutGrantingSharedEdits() {
        val events = mutableListOf<ChatEvent>()
        val mapper = TdChatUpdateMapper(events::add)
        mapper.accept(TdApi.UpdateChatFolders(arrayOf(TdApi.ChatFolderInfo().apply {
            id = 2; name = TdApi.ChatFolderName(TdApi.FormattedText("Shared", emptyArray()), false)
            colorId = 6; isShareable = true; hasMyInviteLinks = true
        }), 1, true))
        val folders = assertIs<ChatEvent.Folders>(events.single())
        assertTrue(folders.tagsEnabled)
        assertEquals(1, folders.mainPosition)
        assertTrue(folders.folders.single().hasMyInviteLinks)
        assertTrue(folders.folders.single().isShareable)
    }

    private class Fixture {
        val commands = mutableListOf<TdApi.Function<*>>()
        var state = ChatSnapshot()
        val controller = ChatController(7, OfficialTdChatSession { command, _, _ -> commands += command }, {}, { state = it })
        val mapper = TdChatUpdateMapper(controller::event)
        init { controller.event(ChatEvent.Folders(listOf(ChatFolderInfo(2, "Work", colorId = 2), ChatFolderInfo(3, "Life", colorId = 5)), tagsEnabled = true)) }
    }
    private companion object {
        fun chat(value: Long) = TdApi.Chat().apply {
            id = value; title = "Synthetic"; type = TdApi.ChatTypePrivate(51)
            positions = emptyArray(); chatLists = emptyArray()
        }
    }
}
