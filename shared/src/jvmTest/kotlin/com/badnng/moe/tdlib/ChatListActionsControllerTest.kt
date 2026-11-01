package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import kotlin.test.*

class ChatListActionsControllerTest {
    @Test
    fun requestsUseKnownChatAndCurrentListWhilePositionsAndMuteRemainUpdateDriven() {
        val fixture = Fixture()
        val results = mutableListOf<ChatResult<Unit>>()
        fixture.controller.chatListAction(fixture.state.token, 1, ChatListRef(), ChatListAction.PIN, results::add)
        assertFalse(ChatListRef() in fixture.state.items.first().pinnedLists)
        val request = fixture.session.actions.single()
        request.result(ChatResult(Unit)); fixture.flush()
        assertEquals(Unit, results.single().value)
        assertFalse(ChatListRef() in fixture.state.items.first().pinnedLists)
        fixture.controller.event(ChatEvent.Position(1, 100, ChatListRef(), true))
        assertTrue(ChatListRef() in fixture.state.items.first().pinnedLists)
        fixture.controller.event(ChatEvent.NotificationMuted(1, true))
        assertTrue(fixture.state.items.first().isMuted)
        fixture.controller.chatListAction(fixture.state.token, 1, ChatListRef(-1), ChatListAction.PIN, results::add)
        fixture.controller.chatListAction(fixture.state.token, 999, ChatListRef(), ChatListAction.ARCHIVE, results::add)
        assertEquals(1, fixture.session.actions.size)
        assertEquals(ChatError.NOT_READY, results.last().error)
    }

    @Test
    fun staleActionCompletionDoesNotChangeTheNewSelectionAndDuplicatesCompleteOnlyOnce() {
        val fixture = Fixture()
        val results = mutableListOf<ChatResult<Unit>>()
        fixture.controller.chatListAction(fixture.state.token, 1, ChatListRef(), ChatListAction.ARCHIVE, results::add)
        val pending = fixture.session.actions.single()
        fixture.controller.select(fixture.state.token, 2); fixture.flush()
        pending.result(ChatResult(Unit)); pending.result(ChatResult(error = ChatError.FORBIDDEN)); fixture.flush()
        assertEquals(ChatError.NOT_READY, results.single().error)
        assertEquals(2L, fixture.state.selected?.id)
        assertTrue((fixture.state.knownChats.first { it.id == 1L }.positions[ChatListRef()] ?: 0) > 0)
    }

    @Test
    fun folderSwitchingKeepsTheChatTokenWithoutAllowingActionsFromThePreviousList() {
        val fixture = Fixture()
        val main = ChatListRef()
        val folder = ChatListRef(7)
        fixture.controller.event(ChatEvent.Folders(listOf(ChatFolderInfo(7, "synthetic"))))
        fixture.controller.event(ChatEvent.Position(1, 50, folder))
        val token = fixture.state.token
        val results = mutableListOf<ChatResult<Unit>>()
        fixture.controller.chatListAction(token, 1, main, ChatListAction.PIN, results::add)
        val pending = fixture.session.actions.single()
        fixture.controller.selectList(token, folder)
        assertEquals(token, fixture.state.token)
        fixture.controller.chatListAction(token, 1, main, ChatListAction.UNPIN, results::add)
        assertEquals(ChatError.NOT_READY, results.last().error)
        assertEquals(1, fixture.session.actions.size)
        fixture.controller.selectList(token, main)
        pending.result(ChatResult(Unit)); fixture.flush()
        assertEquals(ChatError.NOT_READY, results.last().error)
        fixture.controller.chatListAction(token, 1, main, ChatListAction.UNPIN, results::add)
        assertEquals(2, fixture.session.actions.size)
    }

    @Test
    fun aReadFromThePreviousFolderCannotDispatchATrailingMarkerClear() {
        val fixture = Fixture()
        val folder = ChatListRef(7)
        fixture.controller.event(ChatEvent.Folders(listOf(ChatFolderInfo(7, "synthetic"))))
        val results = mutableListOf<ChatResult<Unit>>()
        val token = fixture.state.token
        fixture.controller.chatListAction(token, 1, ChatListRef(), ChatListAction.MARK_READ, results::add)
        fixture.controller.selectList(token, folder)
        fixture.session.views.single().result(ChatResult(Unit)); fixture.flush()
        assertEquals(ChatError.NOT_READY, results.single().error)
        assertTrue(fixture.session.markers.isEmpty())
        fixture.controller.addChatToFolder(token, 1, 7, results::add)
        assertEquals(ChatError.NOT_READY, results.last().error)
        assertTrue(fixture.session.folders.isEmpty())
    }

    @Test
    fun anExplicitReadNeverClearsAManualUnreadMarkerThatArrivesLater() {
        val fixture = Fixture()
        val results = mutableListOf<ChatResult<Unit>>()
        fixture.controller.chatListAction(fixture.state.token, 1, ChatListRef(), ChatListAction.MARK_READ, results::add)
        val read = fixture.session.views.single()
        assertEquals(1L, read.chatId)
        assertEquals(listOf(20L), read.ids)
        assertTrue(read.force)
        fixture.controller.event(ChatEvent.MarkedUnread(1, true))
        read.result(ChatResult(Unit)); fixture.flush()
        assertTrue(fixture.session.markers.isEmpty())
        assertTrue(fixture.state.knownChats.first { it.id == 1L }.markedUnread)
        assertEquals(Unit, results.single().value)
    }

    @Test
    fun aLaterManualUnreadDispatchWinsBeforeItsNativeUpdateArrives() {
        val fixture = Fixture()
        fixture.controller.select(fixture.state.token, 1); fixture.flush()
        fixture.controller.chatListAction(fixture.state.token, 1, ChatListRef(), ChatListAction.MARK_READ) { }
        fixture.controller.markUnread(fixture.state.token)
        assertEquals(listOf(true), fixture.session.markers.map { it.first })
        fixture.session.views.single().result(ChatResult(Unit)); fixture.flush()
        assertEquals(listOf(true), fixture.session.markers.map { it.first })
    }

    @Test
    fun sharedOrUnknownFoldersCannotBeChangedAndNativeFolderCompletionStaysTokenBound() {
        val fixture = Fixture()
        fixture.controller.event(ChatEvent.Folders(listOf(ChatFolderInfo(7, "synthetic"), ChatFolderInfo(8, "shared", isShareable = true))))
        val results = mutableListOf<ChatResult<Unit>>()
        fixture.controller.addChatToFolder(fixture.state.token, 1, 8, results::add)
        fixture.controller.addChatToFolder(fixture.state.token, 1, 999, results::add)
        assertTrue(fixture.session.folders.isEmpty())
        fixture.controller.addChatToFolder(fixture.state.token, 1, 7, results::add)
        fixture.controller.select(fixture.state.token, 2); fixture.flush()
        fixture.session.folders.single()(ChatResult(Unit)); fixture.flush()
        assertEquals(ChatError.NOT_READY, results.last().error)
        assertFalse(ChatListRef(7) in fixture.state.knownChats.first { it.id == 1L }.positions)
    }

    @Test
    fun messageLinkContentCannotCrossSelectionOrReviveADeletedMessage() {
        val fixture = Fixture()
        fixture.controller.select(fixture.state.token, 1); fixture.flush()
        val results = mutableListOf<ChatResult<String>>()
        fixture.controller.requestMessageLink(fixture.state.token, 20, results::add)
        fixture.controller.select(fixture.state.token, 2); fixture.flush()
        fixture.session.links.single()(ChatResult("https://t.me/c/100/20")); fixture.flush()
        assertEquals(ChatError.NOT_READY, results.single().error)
        assertNull(results.single().value)
        fixture.controller.requestMessageLink(fixture.state.token, 20, results::add)
        fixture.controller.event(ChatEvent.Deleted(2, listOf(20), true))
        fixture.session.links.last()(ChatResult("https://t.me/c/200/20")); fixture.flush()
        assertEquals(ChatError.NOT_READY, results.last().error)
        assertNull(results.last().value)
        assertFalse(results.toString().contains("t.me"))
    }

    private class Fixture {
        val session = FakeSession()
        private val queue = ArrayDeque<() -> Unit>()
        var state = ChatSnapshot()
        val controller = ChatController(1, session, { queue.add(it) }, { state = it })
        init {
            controller.start(); flush()
            for (id in 1L..2L) controller.event(ChatEvent.NewChat(ChatItem(id, "synthetic", order = 100 - id,
                lastMessage = ChatMessage(20, id, senderId = 7, text = "synthetic"))))
        }
        fun flush() { while (queue.isNotEmpty()) queue.removeFirst().invoke() }
    }

    private class FakeSession : TdChatSession {
        val actions = mutableListOf<Action>()
        val folders = mutableListOf<(ChatResult<Unit>) -> Unit>()
        val views = mutableListOf<View>()
        val markers = mutableListOf<Pair<Boolean, (ChatResult<Unit>) -> Unit>>()
        val links = mutableListOf<(ChatResult<String>) -> Unit>()
        override fun performChatListAction(chatId: Long, list: ChatListRef, action: ChatListAction, result: (ChatResult<Unit>) -> Unit) {
            actions += Action(chatId, list, action, result)
        }
        override fun addChatToFolder(chatId: Long, folderId: Int, result: (ChatResult<Unit>) -> Unit) { folders += result }
        override fun messageLink(chatId: Long, messageId: Long, result: (ChatResult<String>) -> Unit) { links += result }
        override fun viewMessages(chatId: Long, messageIds: List<Long>, forceRead: Boolean, result: (ChatResult<Unit>) -> Unit) {
            views += View(chatId, messageIds, forceRead, result)
        }
        override fun markUnread(chatId: Long, marked: Boolean, result: (ChatResult<Unit>) -> Unit) { markers += marked to result }
        override fun loadChats(limit: Int, result: (ChatResult<ChatLoadResult>) -> Unit) { result(ChatResult(ChatLoadResult(true))) }
        override fun history(chatId: Long, fromMessageId: Long, limit: Int, result: (ChatResult<List<ChatMessage>>) -> Unit) {
            result(ChatResult(if (fromMessageId == 0L) listOf(ChatMessage(20, chatId, senderId = 7, text = "synthetic")) else emptyList()))
        }
        override fun open(chatId: Long) { }
        override fun close(chatId: Long) { }
        override fun sendText(chatId: Long, text: String, result: (ChatResult<ChatMessage>) -> Unit) { result(ChatResult(error = ChatError.UNSUPPORTED)) }
        override fun retry(chatId: Long, messageId: Long, result: (ChatResult<List<ChatMessage>>) -> Unit) { result(ChatResult(error = ChatError.UNSUPPORTED)) }
        override fun markRead(chatId: Long, messageIds: List<Long>, result: (ChatResult<Unit>) -> Unit) { result(ChatResult(Unit)) }
    }

    private class Action(val chatId: Long, val list: ChatListRef, val action: ChatListAction, val result: (ChatResult<Unit>) -> Unit)
    private class View(val chatId: Long, val ids: List<Long>, val force: Boolean, val result: (ChatResult<Unit>) -> Unit)
}
