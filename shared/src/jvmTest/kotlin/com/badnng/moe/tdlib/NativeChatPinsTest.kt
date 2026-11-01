package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import org.drinkless.tdlib.TdApi
import kotlin.test.*

class NativeChatPinsTest {
    @Test
    fun newChatsKeepNativePinStateForEachListAndIgnoreZeroOrderPins() {
        val fixture = Fixture()
        fixture.chat(20, position(main, 200, true), position(archive, 100), position(folder, 80, true),
            position(ChatListRef(8), 0, true))

        assertEquals(setOf(main, folder), fixture.chatState(20).pinnedLists)
        assertEquals(listOf(20L), fixture.state.items.map { it.id })

        fixture.chat(20, position(main, 190), position(archive, 100, true))
        assertEquals(setOf(archive), fixture.chatState(20).pinnedLists)
        assertEquals(190L, fixture.chatState(20).order)
    }

    @Test
    fun individualPositionUpdatesUnpinOnlyTheirOwnListAndRemovalClearsItsPin() {
        val fixture = Fixture()
        fixture.chat(20, position(main, 200, true), position(archive, 100, true), position(folder, 80, true))

        fixture.mapper.accept(TdApi.UpdateChatPosition(20, position(folder, 90)))
        assertEquals(setOf(main, archive), fixture.chatState(20).pinnedLists)
        assertEquals(90L, fixture.chatState(20).positions[folder])

        fixture.mapper.accept(TdApi.UpdateChatPosition(20, position(main, 0, true)))
        assertEquals(setOf(archive), fixture.chatState(20).pinnedLists)
        assertTrue(fixture.state.items.isEmpty())
        fixture.controller.selectList(fixture.state.token, archive)
        assertEquals(listOf(20L), fixture.state.items.map { it.id })
        assertTrue(archive in fixture.state.items.single().pinnedLists)
    }

    @Test
    fun lastMessagePositionReplacementReplacesPinsInsteadOfRetainingRemovedLists() {
        val fixture = Fixture()
        fixture.chat(20, position(main, 200, true), position(folder, 80, true))

        fixture.mapper.accept(TdApi.UpdateChatLastMessage(20, message(1, 20), arrayOf(
            position(main, 150), position(archive, 120, true),
        )))

        val chat = fixture.chatState(20)
        assertEquals(setOf(archive), chat.pinnedLists)
        assertEquals(mapOf(main to 150L, archive to 120L), chat.positions)
        assertEquals(1L, chat.lastMessage?.id)
        assertEquals(150L, chat.order)
    }

    @Test
    fun draftPositionsCarryPinChangesAndAnEmptyReplacementClearsAllMembership() {
        val fixture = Fixture()
        fixture.chat(20, position(main, 200, true), position(archive, 100, true))

        fixture.mapper.accept(TdApi.UpdateChatDraftMessage(20, null, arrayOf(
            position(main, 180), position(folder, 80, true), position(archive, 0, true),
        )))
        assertEquals(setOf(folder), fixture.chatState(20).pinnedLists)
        fixture.controller.selectList(fixture.state.token, folder)
        assertEquals(listOf(20L), fixture.state.items.map { it.id })

        fixture.mapper.accept(TdApi.UpdateChatDraftMessage(20, null, emptyArray()))
        assertTrue(fixture.chatState(20).pinnedLists.isEmpty())
        assertTrue(fixture.chatState(20).positions.isEmpty())
        assertTrue(fixture.state.items.isEmpty())
        assertEquals(0L, fixture.chatState(20).order)
    }

    @Test
    fun pinIndicatorsNeverReorderTheNativePositions() {
        val fixture = Fixture()
        fixture.chat(20, position(main, 100, true))
        fixture.chat(21, position(main, 200))
        assertEquals(listOf(21L, 20L), fixture.state.items.map { it.id })

        fixture.mapper.accept(TdApi.UpdateChatPosition(20, position(main, 300, true)))
        assertEquals(listOf(20L, 21L), fixture.state.items.map { it.id })
    }

    @Test
    fun messagePreviewWithoutPinMetadataPreservesOnlyPinsForSurvivingPositions() {
        val fixture = Fixture()
        fixture.chat(20, position(main, 200, true), position(archive, 100, true))

        fixture.controller.event(ChatEvent.LastMessage(20, null, 150))
        assertEquals(setOf(main, archive), fixture.chatState(20).pinnedLists)

        fixture.controller.event(ChatEvent.LastMessage(20, null, 0))
        assertEquals(setOf(archive), fixture.chatState(20).pinnedLists)
    }

    private class Fixture {
        var state = ChatSnapshot()
        private val queued = ArrayDeque<() -> Unit>()
        private val session = OfficialTdChatSession { _, _, _ -> }
        val controller = ChatController(1, session, queued::addLast, { state = it })
        val mapper = TdChatUpdateMapper(controller::event)

        init {
            controller.start()
            controller.event(ChatEvent.Folders(listOf(ChatFolderInfo(7, "synthetic folder"))))
        }

        fun chat(id: Long, vararg positions: TdApi.ChatPosition) {
            mapper.accept(TdApi.UpdateNewChat(TdApi.Chat().apply {
                this.id = id
                title = "synthetic chat $id"
                type = TdApi.ChatTypePrivate(1)
                this.positions = arrayOf(*positions)
            }))
        }

        fun chatState(id: Long) = state.knownChats.single { it.id == id }
    }

    private companion object {
        val main = ChatListRef()
        val archive = ChatListRef(-1)
        val folder = ChatListRef(7)

        fun position(list: ChatListRef, order: Long, pinned: Boolean = false): TdApi.ChatPosition {
            val nativeList = when (list.folderId) {
                0 -> TdApi.ChatListMain()
                -1 -> TdApi.ChatListArchive()
                else -> TdApi.ChatListFolder(list.folderId)
            }
            return TdApi.ChatPosition(nativeList, order, pinned, null)
        }

        fun message(id: Long, chatId: Long) = TdApi.Message().apply {
            this.id = id
            this.chatId = chatId
            senderId = TdApi.MessageSenderUser(1)
            content = TdApi.MessageText().apply { text = TdApi.FormattedText("synthetic", emptyArray()) }
        }
    }
}
