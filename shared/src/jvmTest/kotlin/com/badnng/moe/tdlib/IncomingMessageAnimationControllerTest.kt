package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import kotlin.test.*

class IncomingMessageAnimationControllerTest {
    @Test
    fun historyAndLastMessagePreviewsNeverCreateIncomingAnimationIdentities() {
        val fixture = selectedFixture(completeHistory = false)
        fixture.complete(fixture.session.histories.last(), listOf(message(10)))
        fixture.complete(fixture.session.histories.last(), emptyList())
        fixture.event(ChatEvent.LastMessage(1, message(11), 100))
        fixture.event(ChatEvent.Content(1, 10, "synthetic edit", MessageKind.TEXT))
        fixture.event(ChatEvent.Edited(1, 10, 7))
        fixture.event(ChatEvent.Inbox(1, 0, 11))
        fixture.event(ChatEvent.Outbox(1, 11))
        fixture.event(ChatEvent.Reactions(1, 10, emptyList()))
        fixture.event(ChatEvent.NewMessage(message(10)))

        assertEquals(listOf(10L, 11L), fixture.state.messages.map { it.id })
        assertTrue(fixture.state.messages.all { it.incomingAnimationId == null })
        assertNull(fixture.state.selected?.lastMessage?.incomingAnimationId)
    }

    @Test
    fun nativeArrivalAfterLastMessageGetsOneIdentityRetainedByUpdatesAndDuplicateEvents() {
        val fixture = selectedFixture()
        fixture.event(ChatEvent.LastMessage(1, message(10), 100))
        assertNull(fixture.current(10).incomingAnimationId)
        fixture.event(ChatEvent.NewMessage(message(10)))
        val first = assertNotNull(fixture.current(10).incomingAnimationId)

        fixture.event(ChatEvent.NewMessage(message(10)))
        fixture.event(ChatEvent.LastMessage(1, message(10), 100))
        fixture.event(ChatEvent.Content(1, 10, "synthetic edit", MessageKind.TEXT))
        fixture.event(ChatEvent.Edited(1, 10, 7))
        fixture.event(ChatEvent.Reactions(1, 10, emptyList()))
        fixture.event(ChatEvent.Inbox(1, 0, 10))
        assertEquals(first, fixture.current(10).incomingAnimationId)

        fixture.event(ChatEvent.NewMessage(message(11)))
        assertTrue(assertNotNull(fixture.current(11).incomingAnimationId) > first)
        assertNull(fixture.current(11).sendAnimationId)
    }

    @Test
    fun lateHistoryPreservesTheArrivalIdentityButDoesNotAnimateItsOtherMessages() {
        val fixture = selectedFixture(completeHistory = false)
        fixture.event(ChatEvent.NewMessage(message(30)))
        val arrival = assertNotNull(fixture.current(30).incomingAnimationId)
        fixture.complete(fixture.session.histories.last(), listOf(message(20), message(30)))
        fixture.complete(fixture.session.histories.last(), emptyList())

        assertEquals(arrival, fixture.current(30).incomingAnimationId)
        assertNull(fixture.current(20).incomingAnimationId)
        assertEquals(listOf(20L, 30L), fixture.state.messages.map { it.id })
    }

    @Test
    fun outgoingEventsServiceMessagesAndSendAcceptanceKeepTheirSeparateAnimationIdentity() {
        val fixture = selectedFixture()
        fixture.event(ChatEvent.NewMessage(message(10).copy(outgoing = true)))
        fixture.event(ChatEvent.NewMessage(message(11).copy(kind = MessageKind.SERVICE)))
        fixture.controller.sendText(fixture.state.token, "synthetic draft")
        fixture.session.sends.single()(ChatResult(message(-1).copy(outgoing = true, delivery = MessageDelivery.PENDING)))
        fixture.flush()
        val sendIdentity = assertNotNull(fixture.current(-1).sendAnimationId)
        fixture.event(ChatEvent.SendSucceeded(message(12).copy(outgoing = true), -1))

        assertTrue(fixture.state.messages.all { it.incomingAnimationId == null })
        assertEquals(sendIdentity, fixture.current(12).sendAnimationId)
        assertEquals(-1L, fixture.current(12).presentationId)
    }

    @Test
    fun replacementKeepsTheExistingIncomingIdentityWithoutCreatingAnotherOne() {
        val fixture = selectedFixture()
        fixture.event(ChatEvent.NewMessage(message(10)))
        val arrival = assertNotNull(fixture.current(10).incomingAnimationId)
        fixture.event(ChatEvent.SendSucceeded(message(20), 10))
        assertEquals(arrival, fixture.current(20).incomingAnimationId)
        assertEquals(10L, fixture.current(20).presentationId)

        fixture.event(ChatEvent.LastMessage(1, message(20), 100))
        assertEquals(arrival, fixture.current(20).incomingAnimationId)
        fixture.event(ChatEvent.SendSucceeded(message(21).copy(outgoing = true), 19))
        assertNull(fixture.current(21).incomingAnimationId)
    }

    @Test
    fun offscreenArrivalsAndReselectionHistoryCannotReplayAnAlreadyObservedNativeEvent() {
        val fixture = selectedFixture()
        fixture.chat(2)
        fixture.event(ChatEvent.NewMessage(message(10)))
        val arrival = assertNotNull(fixture.current(10).incomingAnimationId)
        fixture.event(ChatEvent.NewMessage(message(20, chatId = 2)))
        assertEquals(listOf(10L), fixture.state.messages.map { it.id })
        assertNull(fixture.state.knownChats.single { it.id == 2L }.lastMessage?.incomingAnimationId)

        fixture.select(2)
        fixture.complete(fixture.session.histories.last(), listOf(message(20, chatId = 2)))
        fixture.complete(fixture.session.histories.last(), emptyList())
        fixture.event(ChatEvent.NewMessage(message(20, chatId = 2)))
        assertNull(fixture.current(20).incomingAnimationId)
        fixture.event(ChatEvent.NewMessage(message(21, chatId = 2)))
        assertTrue(assertNotNull(fixture.current(21).incomingAnimationId) > arrival)

        fixture.select(1)
        fixture.complete(fixture.session.histories.last(), listOf(message(10)))
        fixture.complete(fixture.session.histories.last(), emptyList())
        fixture.event(ChatEvent.NewMessage(message(10)))
        assertNull(fixture.current(10).incomingAnimationId)
    }

    @Test
    fun anArrivalBeyondTheUnreadGapNeverEntersHistoryOrReplaysWhenNativePagingReachesIt() {
        val fixture = Fixture()
        fixture.chat(1, unread = 5, lastRead = 100, newest = 110)
        fixture.select(1)
        fixture.event(ChatEvent.NewMessage(message(111)))
        assertTrue(fixture.state.messages.isEmpty())
        assertEquals(111L, fixture.state.selected?.lastMessage?.id)
        assertNull(fixture.state.selected?.lastMessage?.incomingAnimationId)

        fixture.complete(fixture.session.unread.single(), listOf(message(100), message(101)))
        assertTrue(fixture.state.historyHasNewer)
        assertTrue(fixture.state.messages.all { it.incomingAnimationId == null })
        fixture.controller.loadNewer(fixture.state.token)
        fixture.complete(fixture.session.newer.single(), (101L..111L).map { message(it) })
        assertFalse(fixture.state.historyHasNewer)
        assertNull(fixture.current(111).incomingAnimationId)
        fixture.event(ChatEvent.NewMessage(message(111)))
        assertNull(fixture.current(111).incomingAnimationId)

        fixture.event(ChatEvent.NewMessage(message(112)))
        assertNotNull(fixture.current(112).incomingAnimationId)
    }

    @Test
    fun aRetiredControllerCannotPublishLateIncomingAnimationSignals() {
        val fixture = selectedFixture()
        val before = fixture.state
        fixture.controller.deactivate()
        fixture.event(ChatEvent.NewMessage(message(10)))
        assertSame(before, fixture.state)
    }

    @Test
    fun historyOriginsStayWithinTheLoadedWindowAndOffscreenTrafficRetainsNoAnimationSignals() {
        val fixture = selectedFixture(completeHistory = false)
        fixture.complete(fixture.session.histories.last(), (1L..20L).map { message(it) })
        fixture.complete(fixture.session.histories.last(), emptyList())
        fixture.chat(2)
        repeat(200) { fixture.event(ChatEvent.NewMessage(message(1_000L + it, chatId = 2))) }
        assertEquals((1L..20L).toSet(), fixture.historyOrigins())
        assertNull(fixture.state.knownChats.single { it.id == 2L }.lastMessage?.incomingAnimationId)

        fixture.event(ChatEvent.Deleted(1, (1L..10L).toList(), permanent = true))
        assertEquals((11L..20L).toSet(), fixture.historyOrigins())
        fixture.event(ChatEvent.SendSucceeded(message(30), 20))
        assertEquals((11L..19L).toSet() + 30L, fixture.historyOrigins())
        fixture.event(ChatEvent.NewMessage(message(30)))
        assertNull(fixture.current(30).incomingAnimationId)

        fixture.select(2)
        assertTrue(fixture.historyOrigins().isEmpty())
        fixture.complete(fixture.session.histories.last(), emptyList())
        fixture.event(ChatEvent.NewMessage(message(20, chatId = 2)))
        assertNotNull(fixture.current(20).incomingAnimationId)
        assertTrue(fixture.historyOrigins().isEmpty())
    }

    private class Fixture {
        var state = ChatSnapshot()
        private val queued = ArrayDeque<() -> Unit>()
        val session = FakeSession()
        val controller = ChatController(1, session, queued::addLast, { state = it })

        init { controller.start(); flush() }

        fun chat(id: Long, unread: Int = 0, lastRead: Long = 0, newest: Long? = null) = event(ChatEvent.NewChat(
            ChatItem(id, "synthetic chat", order = 100, unreadCount = unread, lastReadInboxId = lastRead,
                lastMessage = newest?.let { message(it, id) })))
        fun select(id: Long) { controller.select(state.token, id); flush() }
        fun event(event: ChatEvent) = controller.event(event)
        fun current(id: Long) = state.messages.single { it.id == id }
        @Suppress("UNCHECKED_CAST")
        fun historyOrigins(): Set<Long> = (ChatController::class.java.getDeclaredField("historyMessageIds").apply {
            isAccessible = true
        }.get(controller) as Set<Long>).toSet()
        fun flush() { while (queued.isNotEmpty()) queued.removeFirst()() }
        fun complete(call: HistoryCall, messages: List<ChatMessage>) { call.done(ChatResult(messages)); flush() }
    }

    private class FakeSession : TdChatSession {
        val histories = mutableListOf<HistoryCall>()
        val unread = mutableListOf<HistoryCall>()
        val newer = mutableListOf<HistoryCall>()
        val sends = mutableListOf<(ChatResult<ChatMessage>) -> Unit>()

        override fun loadChats(limit: Int, result: (ChatResult<ChatLoadResult>) -> Unit) { result(ChatResult(ChatLoadResult(true))) }
        override fun history(chatId: Long, fromMessageId: Long, limit: Int, result: (ChatResult<List<ChatMessage>>) -> Unit) {
            histories += HistoryCall(result)
        }
        override fun unreadHistory(chatId: Long, afterMessageId: Long, result: (ChatResult<List<ChatMessage>>) -> Unit) {
            unread += HistoryCall(result)
        }
        override fun newerHistory(chatId: Long, fromMessageId: Long, result: (ChatResult<List<ChatMessage>>) -> Unit) {
            newer += HistoryCall(result)
        }
        override fun open(chatId: Long) = Unit
        override fun close(chatId: Long) = Unit
        override fun sendText(chatId: Long, text: String, result: (ChatResult<ChatMessage>) -> Unit) { sends += result }
        override fun retry(chatId: Long, messageId: Long, result: (ChatResult<List<ChatMessage>>) -> Unit) = Unit
        override fun markRead(chatId: Long, messageIds: List<Long>, result: (ChatResult<Unit>) -> Unit) = Unit
    }

    private data class HistoryCall(val done: (ChatResult<List<ChatMessage>>) -> Unit)

    private companion object {
        fun message(id: Long, chatId: Long = 1) = ChatMessage(id, chatId, text = "synthetic message")
        fun selectedFixture(completeHistory: Boolean = true) = Fixture().apply {
            chat(1)
            select(1)
            if (completeHistory) complete(session.histories.last(), emptyList())
        }
    }
}
