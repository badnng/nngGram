package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import kotlin.test.*

class ChatMediaIndexControllerTest {
    @Test
    fun firstOpenQueriesAllServerCountsAndDoesNotChangeTimelinePaginationOrReadState() {
        val fixture = Fixture()
        val before = fixture.state
        val histories = fixture.session.histories.size
        fixture.load()
        assertEquals(ChatMediaCategory.entries.toSet(), fixture.session.counts.map { it.category }.toSet())
        assertTrue(fixture.session.counts.all { it.chatId == 10L })
        assertEquals(0L, fixture.session.pages.single().from)
        fixture.page(fixture.session.pages.single(), listOf(message(100)), "90", 500)
        fixture.session.counts.forEach { it.done(ChatResult(500)) }
        fixture.flush()
        assertEquals(500, fixture.state.mediaIndex.totalCount)
        assertEquals(5, fixture.state.mediaIndex.counts.size)
        assertEquals(listOf(100L), fixture.state.mediaIndex.messages.map { it.id })
        assertEquals(before.messages, fixture.state.messages)
        assertEquals(before.unreadBoundary, fixture.state.unreadBoundary)
        assertEquals(before.historyEnd, fixture.state.historyEnd)
        assertEquals(before.historyHasNewer, fixture.state.historyHasNewer)
        assertEquals(histories, fixture.session.histories.size)
        assertTrue(fixture.session.reads.isEmpty())
        assertTrue(fixture.session.downloads.isEmpty())
    }

    @Test
    fun categoryPagesKeepTheirOwnCursorAndLateCallbacksCannotReplaceTheCurrentTab() {
        val fixture = Fixture()
        fixture.load()
        val photos = fixture.session.pages.last()
        fixture.load(ChatMediaCategory.FILES)
        val files = fixture.session.pages.last()
        fixture.page(photos, listOf(message(100)), "90")
        assertEquals(ChatMediaCategory.FILES, fixture.state.mediaIndex.category)
        assertTrue(fixture.state.mediaIndex.loading)
        assertTrue(fixture.state.mediaIndex.messages.isEmpty())
        fixture.page(files, listOf(message(80)), "70")
        fixture.load()
        assertEquals(2, fixture.session.pages.size)
        assertEquals(listOf(100L), fixture.state.mediaIndex.messages.map { it.id })
        fixture.load(more = true)
        val more = fixture.session.pages.last()
        assertEquals(90L, more.from)
        fixture.page(more, listOf(message(100), message(90), message(89, 11), message(0)), "90")
        assertEquals(listOf(100L, 90L), fixture.state.mediaIndex.messages.map { it.id })
        assertEquals("", fixture.state.mediaIndex.nextOffset)
        fixture.load(more = true)
        assertEquals(3, fixture.session.pages.size)
        fixture.page(photos, listOf(message(200)), "190")
        assertEquals(listOf(100L, 90L), fixture.state.mediaIndex.messages.map { it.id })
    }

    @Test
    fun changingChatInvalidatesPagesCountsAndCallbacksEvenWhenReturningToTheSameChat() {
        val fixture = Fixture()
        fixture.load()
        val oldToken = fixture.state.token
        val page = fixture.session.pages.last()
        val count = fixture.session.counts.first()
        fixture.select(11)
        fixture.select(10)
        fixture.load()
        fixture.page(page, listOf(message(100)))
        count.done(ChatResult(900))
        fixture.flush()
        assertTrue(fixture.state.mediaIndex.messages.isEmpty())
        assertTrue(fixture.state.mediaIndex.counts.isEmpty())
        assertTrue(fixture.state.mediaIndex.loading)
        fixture.controller.loadMediaIndex(oldToken, ChatMediaCategory.FILES)
        fixture.controller.openMediaIndexMessage(oldToken, 100)
        assertEquals(2, fixture.session.pages.size)
        assertTrue(fixture.session.around.isEmpty())
    }

    @Test
    fun onlyMessagesFromTheCurrentIndexAuthorizeHistoryAroundAndNativeFailurePreservesHistory() {
        val fixture = Fixture()
        fixture.load()
        fixture.page(fixture.session.pages.last(), listOf(message(100)))
        fixture.controller.openMediaIndexMessage(fixture.state.token, 999)
        assertTrue(fixture.session.around.isEmpty())
        fixture.load(ChatMediaCategory.FILES)
        fixture.controller.openMediaIndexMessage(fixture.state.token, 100)
        assertTrue(fixture.session.around.isEmpty())
        fixture.page(fixture.session.pages.last(), listOf(message(80)))
        fixture.controller.openMediaIndexMessage(fixture.state.token, 80)
        assertEquals(80L, fixture.session.around.single().id)
        assertEquals(10L, fixture.session.around.single().chatId)
        val original = fixture.state.messages
        fixture.session.around.single().done(ChatResult(error = ChatError.FORBIDDEN))
        fixture.flush()
        assertEquals(original, fixture.state.messages)
        assertEquals(ChatError.FORBIDDEN, fixture.state.historyError)
        assertNull(fixture.state.jumpMessageId)
    }

    @Test
    fun deletionAndContentChangesDuringQueriesCannotReviveStaleMediaOrCounts() {
        val fixture = Fixture()
        fixture.load()
        val page = fixture.session.pages.last()
        val count = fixture.session.counts.first()
        fixture.controller.event(ChatEvent.Deleted(10, listOf(100), permanent = true))
        fixture.controller.event(ChatEvent.Deleted(10, listOf(99), permanent = false))
        fixture.controller.event(ChatEvent.Content(10, 98, "replacement without media", MessageKind.TEXT))
        fixture.page(page, listOf(message(100), message(99), message(98), message(97)), total = 4)
        count.done(ChatResult(4))
        fixture.flush()
        assertEquals(listOf(97L), fixture.state.mediaIndex.messages.map { it.id })
        assertNull(fixture.state.mediaIndex.totalCount)
        assertFalse(fixture.state.mediaIndex.loaded)
        assertTrue(fixture.state.mediaIndex.counts.isEmpty())
        fixture.controller.event(ChatEvent.Deleted(10, listOf(97), permanent = true))
        assertTrue(fixture.state.mediaIndex.messages.isEmpty())
        fixture.controller.openMediaIndexMessage(fixture.state.token, 97)
        assertTrue(fixture.session.around.isEmpty())
    }

    @Test
    fun countFailuresStayUnknownWhilePageErrorsCanRetryWithoutClearingOtherCategoryCaches() {
        val fixture = Fixture()
        fixture.load()
        val photos = fixture.session.pages.last()
        val count = fixture.session.counts.first { it.category == ChatMediaCategory.PHOTOS_AND_VIDEOS }
        count.done(ChatResult(error = ChatError.FORBIDDEN))
        photos.done(ChatResult(error = ChatError.RATE_LIMITED))
        fixture.flush()
        assertNull(fixture.state.mediaIndex.totalCount)
        assertEquals(ChatError.FORBIDDEN, fixture.state.mediaIndex.countErrors[ChatMediaCategory.PHOTOS_AND_VIDEOS])
        assertEquals(ChatError.RATE_LIMITED, fixture.state.mediaIndex.error)
        assertFalse(fixture.state.mediaIndex.loaded)
        fixture.load(ChatMediaCategory.FILES)
        fixture.page(fixture.session.pages.last(), listOf(message(80)))
        fixture.load()
        assertEquals(3, fixture.session.pages.size)
        assertTrue(fixture.state.mediaIndex.loading)
        fixture.page(fixture.session.pages.last(), listOf(message(100)))
        fixture.load(ChatMediaCategory.FILES)
        assertEquals(listOf(80L), fixture.state.mediaIndex.messages.map { it.id })
        assertFalse(fixture.state.mediaIndex.loading)
    }

    @Test
    fun folderNavigationPreservesTheCurrentMediaIndexAndItsPendingPage() {
        val fixture = Fixture()
        fixture.load()
        val pending = fixture.session.pages.last()
        val token = fixture.state.token
        fixture.controller.selectList(token, ChatListRef(-1))
        fixture.page(pending, listOf(message(100)))
        assertEquals(token, fixture.state.token)
        assertEquals(listOf(100L), fixture.state.mediaIndex.messages.map { it.id })
        assertEquals(ChatListRef(-1), fixture.state.activeList)
    }

    @Test
    fun aNewMessageInvalidatesCachedPagesAndReopeningTheSameCategoryQueriesTheNativeIndexAgain() {
        val fixture = Fixture()
        fixture.load()
        fixture.page(fixture.session.pages.last(), listOf(message(100)), "90")
        fixture.session.counts.forEach { it.done(ChatResult(500)) }
        fixture.flush()
        assertTrue(fixture.state.mediaIndex.loaded)
        val calls = fixture.session.pages.size
        val counts = fixture.session.counts.size
        fixture.controller.event(ChatEvent.NewMessage(message(300)))
        assertFalse(fixture.state.mediaIndex.loaded)
        assertEquals(listOf(100L), fixture.state.mediaIndex.messages.map { it.id })
        assertNull(fixture.state.mediaIndex.totalCount)
        assertEquals(calls, fixture.session.pages.size)
        assertEquals(counts, fixture.session.counts.size)
        fixture.load(more = true)
        assertEquals(calls, fixture.session.pages.size)
        fixture.load()
        assertEquals(0L, fixture.session.pages.last().from)
        assertEquals(calls + 1, fixture.session.pages.size)
        fixture.page(fixture.session.pages.last(), listOf(message(300), message(100)), total = 501)
        assertEquals(listOf(300L, 100L), fixture.state.mediaIndex.messages.map { it.id })
        assertEquals(501, fixture.state.mediaIndex.totalCount)
        assertTrue(fixture.state.mediaIndex.loaded)
    }

    @Test
    fun nativeSendConfirmationInvalidatesAnIndexRefreshedWhileTheMessageWasPending() {
        val fixture = Fixture()
        fixture.controller.event(ChatEvent.NewMessage(message(-100).copy(outgoing = true, delivery = MessageDelivery.PENDING)))
        fixture.load()
        fixture.page(fixture.session.pages.last(), listOf(message(100)))
        assertTrue(fixture.state.mediaIndex.loaded)
        fixture.controller.event(ChatEvent.SendSucceeded(message(300).copy(outgoing = true), -100))
        assertFalse(fixture.state.mediaIndex.loaded)
        fixture.load()
        fixture.page(fixture.session.pages.last(), listOf(message(300), message(100)))
        assertEquals(listOf(300L, 100L), fixture.state.mediaIndex.messages.map { it.id })
    }

    private class Fixture {
        var state = ChatSnapshot()
        private val queue = ArrayDeque<() -> Unit>()
        val session = FakeSession()
        val controller = ChatController(1, session, queue::addLast, { state = it })

        init {
            controller.start()
            flush()
            controller.event(ChatEvent.NewChat(ChatItem(10, "synthetic group", order = 100)))
            controller.event(ChatEvent.NewChat(ChatItem(11, "another synthetic group", order = 90)))
            select(10)
        }

        fun flush() { while (queue.isNotEmpty()) queue.removeFirst()() }
        fun select(id: Long) { controller.select(state.token, id); flush() }
        fun load(category: ChatMediaCategory = ChatMediaCategory.PHOTOS_AND_VIDEOS, more: Boolean = false) {
            controller.loadMediaIndex(state.token, category, more)
        }
        fun page(call: PageCall, messages: List<ChatMessage>, next: String = "", total: Int = messages.size) {
            call.done(ChatResult(ChatSearchPage(messages = messages, nextOffset = next, totalCount = total)))
            flush()
        }
    }

    private class FakeSession : TdChatSession {
        val pages = mutableListOf<PageCall>()
        val counts = mutableListOf<CountCall>()
        val around = mutableListOf<AroundCall>()
        val histories = mutableListOf<Pair<Long, Long>>()
        val reads = mutableListOf<List<Long>>()
        val downloads = mutableListOf<Int>()

        override fun mediaIndex(chatId: Long, category: ChatMediaCategory, fromMessageId: Long, result: (ChatResult<ChatSearchPage>) -> Unit) {
            pages += PageCall(chatId, category, fromMessageId, result)
        }
        override fun mediaIndexCount(chatId: Long, category: ChatMediaCategory, result: (ChatResult<Int>) -> Unit) {
            counts += CountCall(chatId, category, result)
        }
        override fun historyAround(chatId: Long, messageId: Long, result: (ChatResult<List<ChatMessage>>) -> Unit) {
            around += AroundCall(chatId, messageId, result)
        }
        override fun loadChats(limit: Int, result: (ChatResult<ChatLoadResult>) -> Unit) { result(ChatResult(ChatLoadResult(true))) }
        override fun history(chatId: Long, fromMessageId: Long, limit: Int, result: (ChatResult<List<ChatMessage>>) -> Unit) {
            histories += chatId to fromMessageId
            result(ChatResult(if (fromMessageId == 0L) listOf(message(200, chatId)) else emptyList()))
        }
        override fun markRead(chatId: Long, messageIds: List<Long>, result: (ChatResult<Unit>) -> Unit) { reads += messageIds }
        override fun download(fileId: Int, result: (ChatResult<MediaFile>) -> Unit) { downloads += fileId }
        override fun open(chatId: Long) = Unit
        override fun close(chatId: Long) = Unit
        override fun sendText(chatId: Long, text: String, result: (ChatResult<ChatMessage>) -> Unit) = Unit
        override fun retry(chatId: Long, messageId: Long, result: (ChatResult<List<ChatMessage>>) -> Unit) = Unit
    }

    private data class PageCall(val chatId: Long, val category: ChatMediaCategory, val from: Long, val done: (ChatResult<ChatSearchPage>) -> Unit)
    private data class CountCall(val chatId: Long, val category: ChatMediaCategory, val done: (ChatResult<Int>) -> Unit)
    private data class AroundCall(val chatId: Long, val id: Long, val done: (ChatResult<List<ChatMessage>>) -> Unit)

    private companion object {
        fun message(id: Long, chatId: Long = 10) = ChatMessage(id, chatId, text = "synthetic indexed media", kind = MessageKind.PHOTO)
    }
}
