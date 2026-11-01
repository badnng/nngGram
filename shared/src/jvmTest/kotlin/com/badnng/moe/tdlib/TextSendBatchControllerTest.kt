package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import kotlin.test.*

/** Native acknowledgements are synthetic. These cases never create a TDLib client. */
class TextSendBatchControllerTest {
    @Test fun partialAcceptanceRetainsDraftReceiptAndExplicitRetryOnlySubmitsTheUnacceptedSuffix() {
        val fixture = Fixture()
        val original = RichMessageText("abcdefghijklmno", listOf(MessageEntity(0, 15, MessageEntityKind.CODE)))
        fixture.controller.sendText(fixture.state.token, original, 7)
        val first = fixture.transport.sends.single()
        assertEquals("abcdefgh", first.content.text)
        fixture.accept(0)
        assertEquals(1L, fixture.state.sendAttemptRevision, "Automatic continuation is not a new user send intent")
        val suffix = fixture.transport.sends.last().content
        assertEquals("ijklmno", suffix.text)
        assertEquals(MessageEntity(0, 7, MessageEntityKind.CODE), suffix.entities.single())
        assertEquals(0L, fixture.state.acceptedSendId)
        fixture.transport.sends[1].completed(ChatResult(error = ChatError.RATE_LIMITED))
        fixture.flush()
        assertFalse(fixture.state.sendPending)
        assertEquals(ChatError.RATE_LIMITED, fixture.state.sendError)
        assertEquals(2, fixture.transport.sends.size)
        fixture.controller.sendText(fixture.state.token, original, 7)
        assertEquals(suffix, fixture.transport.sends[2].content)
        fixture.accept(2)
        assertEquals(2L, fixture.state.sendAttemptRevision, "Only an explicit retry reacquires send intent")
        assertEquals(1L, fixture.state.acceptedSendId)
        assertEquals(original.text, fixture.state.acceptedSendText)
        assertEquals(original.entities, fixture.state.acceptedSendEntities)
        assertFalse(fixture.state.sendPending)
    }

    @Test fun deletedAndRetypedIdenticalTextHasANewRevisionAndStartsANewBatch() {
        val fixture = Fixture()
        val content = RichMessageText("abcdefghijklmno")
        fixture.controller.sendText(fixture.state.token, content, 1)
        fixture.accept(0)
        fixture.transport.sends[1].completed(ChatResult(error = ChatError.REQUEST_FAILED))
        fixture.flush()
        fixture.controller.sendText(fixture.state.token, content, 3)
        assertEquals(fixture.transport.sends[0].content, fixture.transport.sends[2].content)
    }

    @Test fun changingTheReplyAfterPartialFailureStartsANewSubmissionWithTheVisibleReply() {
        val fixture = partialReplyFailure()
        fixture.controller.event(ChatEvent.NewMessage(ChatMessage(43, 1, text = "another source")))
        fixture.controller.setReply(fixture.state.token, 43)
        fixture.controller.sendText(fixture.state.token, replyBatchText, 4)
        assertEquals("abcdefgh", fixture.transport.sends[2].content.text)
        assertEquals(43L, fixture.transport.sends[2].reply?.messageId)
        fixture.accept(1) // A duplicate late completion from the failed old owner cannot consume the new reply.
        assertEquals(43L, fixture.state.replyTarget?.id)
        assertEquals(0L, fixture.state.acceptedSendId)
        assertEquals(3, fixture.transport.sends.size)
    }

    @Test fun clearingTheReplyAfterPartialFailureNeverContinuesWithTheOldReply() {
        val fixture = partialReplyFailure()
        fixture.controller.setReply(fixture.state.token, null)
        fixture.controller.sendText(fixture.state.token, replyBatchText, 4)
        assertEquals("abcdefgh", fixture.transport.sends[2].content.text)
        assertNull(fixture.transport.sends[2].reply)
        fixture.accept(1)
        assertNull(fixture.state.replyTarget)
        assertEquals(0L, fixture.state.acceptedSendId)
    }

    @Test fun reselectingTheSameReplyIsANewOwnerEvenWhenTheTextAndTargetAreIdentical() {
        val fixture = partialReplyFailure()
        fixture.controller.setReply(fixture.state.token, 42)
        fixture.controller.sendText(fixture.state.token, replyBatchText, 4)
        assertEquals("abcdefgh", fixture.transport.sends[2].content.text)
        assertEquals(42L, fixture.transport.sends[2].reply?.messageId)
        fixture.accept(1)
        assertEquals(42L, fixture.state.replyTarget?.id)
        assertEquals(0L, fixture.state.acceptedSendId)
    }

    @Test fun choosingAQuoteAfterPartialFailureStartsWithTheCurrentQuoteAndTheWholeDraft() {
        val fixture = partialReplyFailure()
        val quote = MessageQuote(0, RichMessageText("quote"))
        fixture.controller.setQuote(fixture.state.token, 42, quote)
        fixture.controller.sendText(fixture.state.token, replyBatchText, 4)
        assertEquals("abcdefgh", fixture.transport.sends[2].content.text)
        assertEquals(OutgoingReply(42, quote), fixture.transport.sends[2].reply)
        fixture.accept(1)
        assertEquals(quote, fixture.state.replyQuote)
        assertEquals(0L, fixture.state.acceptedSendId)
    }

    @Test fun switchingAwayAfterPartialFailurePreservesTheReplyOwnerAndOnlyRetriesTheUnacceptedSuffix() {
        val fixture = partialReplyFailure()
        fixture.transport.around = listOf(ChatMessage(42, 1, text = "quote source"))
        fixture.select(2)
        fixture.select(1)
        assertEquals(42L, fixture.state.replyTarget?.id)
        fixture.controller.sendText(fixture.state.token, replyBatchText, 4)
        assertEquals("ijklmno", fixture.transport.sends[2].content.text)
        assertEquals(42L, fixture.transport.sends[2].reply?.messageId)
        fixture.accept(2)
        assertEquals(1L, fixture.state.acceptedSendId)
        assertEquals(replyBatchText.text, fixture.state.acceptedSendText)
        assertNull(fixture.state.replyTarget)
    }

    @Test fun duplicatePartCompletionsCannotAdvanceTwiceOrConsumeTheDraftEarly() {
        val fixture = Fixture()
        fixture.controller.sendText(fixture.state.token, RichMessageText("abcdefghijklmnopq"), 1)
        fixture.accept(0)
        fixture.accept(0)
        assertEquals(2, fixture.transport.sends.size)
        assertEquals(0L, fixture.state.acceptedSendId)
        fixture.accept(1)
        assertEquals(3, fixture.transport.sends.size)
        fixture.accept(2)
        fixture.accept(2)
        assertEquals(1L, fixture.state.acceptedSendId)
    }

    @Test fun switchingChatsStopsAutomaticPartsAndReturningRequiresAnExplicitSameRevisionRetry() {
        val fixture = Fixture()
        val content = RichMessageText("abcdefghijklmno")
        fixture.controller.sendText(fixture.state.token, content, 9)
        fixture.select(2)
        fixture.accept(0)
        assertEquals(1, fixture.transport.sends.size)
        assertEquals(0L, fixture.state.acceptedSendId)
        assertFalse(fixture.state.sendPending)
        fixture.select(1)
        assertEquals(1, fixture.transport.sends.size)
        fixture.controller.sendText(fixture.state.token, content, 9)
        assertEquals("ijklmno", fixture.transport.sends[1].content.text)
        fixture.accept(1)
        assertEquals(content.text, fixture.state.acceptedSendText)
    }

    @Test fun pendingNativeLimitCannotSendIntoANewerSelectionOrSurviveEngineClosure() {
        val fixture = Fixture(knownLimit = null)
        fixture.controller.sendText(fixture.state.token, RichMessageText("abcdefghijklmno"), 1)
        assertTrue(fixture.state.sendPending)
        fixture.select(2)
        fixture.transport.limits.single()(ChatResult(8))
        fixture.flush()
        assertTrue(fixture.transport.sends.isEmpty())
        fixture.controller.sendText(fixture.state.token, RichMessageText("hello"), 1)
        fixture.controller.deactivate()
        fixture.transport.limits.last()(ChatResult(8))
        fixture.flush()
        assertTrue(fixture.transport.sends.isEmpty())
    }

    @Test fun nativeLimitErrorsAndUnboundedPartCountsNeverSubmitPartialText() {
        val fixture = Fixture(knownLimit = null)
        fixture.controller.sendText(fixture.state.token, RichMessageText("abcdefghijklmno"), 1)
        fixture.transport.limits.single()(ChatResult(error = ChatError.REQUEST_FAILED))
        fixture.flush()
        assertEquals(ChatError.REQUEST_FAILED, fixture.state.sendError)
        assertTrue(fixture.transport.sends.isEmpty())
        fixture.controller.sendText(fixture.state.token, RichMessageText("a".repeat(257)), 2)
        fixture.transport.limits.last()(ChatResult(1))
        fixture.flush()
        assertEquals(ChatError.TEXT_TOO_LONG, fixture.state.sendError)
        assertTrue(fixture.transport.sends.isEmpty())
    }

    @Test fun whitespaceOnlyRemainderDoesNotCreateAnUnsendableNativePart() {
        val fixture = Fixture()
        fixture.controller.sendText(fixture.state.token, RichMessageText("abcdefgh" + " ".repeat(8)), 1)
        fixture.accept(0)
        assertEquals(1, fixture.transport.sends.size)
        assertEquals(1L, fixture.state.acceptedSendId)
    }

    @Test fun applicationDomainConfigIsAccountScopedAndAnEmptyNativeSetOverridesDefaults() {
        val fixture = Fixture()
        assertNull(fixture.state.linkConfirmationDomains)
        fixture.transport.domains.single()(ChatResult(emptySet()))
        fixture.flush()
        assertEquals(emptySet(), fixture.state.linkConfirmationDomains)
        val another = Fixture()
        another.controller.deactivate()
        another.transport.domains.single()(ChatResult(setOf("example.org")))
        another.flush()
        assertNull(another.state.linkConfirmationDomains)
    }

    @Test fun lastPartCanReplaceAFrozenUnreadWindowWithoutLosingBatchCompletionOrDraftReceipt() {
        val fixture = Fixture()
        fixture.controller.event(ChatEvent.NewChat(ChatItem(1, "one", 10, unreadCount = 1,
            lastMessage = ChatMessage(100, 1, text = "latest"), lastReadInboxId = 50)))
        fixture.transport.unread = listOf(ChatMessage(51, 1, text = "unread"))
        fixture.select(2)
        fixture.select(1)
        assertTrue(fixture.state.historyHasNewer)
        val originalSelection = fixture.state.token.selection
        fixture.controller.setReply(fixture.state.token, 51)
        val content = RichMessageText("abcdefghijklmno")
        fixture.controller.sendText(fixture.state.token, content, 1)
        fixture.accept(0)
        assertEquals(originalSelection, fixture.state.token.selection, "Intermediate parts preserve the existing native history window")
        assertEquals(2, fixture.transport.sends.size)
        fixture.accept(1)
        assertEquals(originalSelection, fixture.state.token.selection,
            "The completed batch keeps the old drawable selection while a native latest window prepares")
        assertEquals(1L, fixture.state.acceptedSendId)
        assertEquals(content.text, fixture.state.acceptedSendText)
        assertFalse(fixture.state.sendPending)
        assertNull(fixture.state.replyTarget, "An accepted reply must not return while a latest window prepares")
        assertEquals(2, fixture.transport.sends.size)
        fixture.controller.sendText(fixture.state.token, RichMessageText("new"), 2)
        assertEquals(3, fixture.transport.sends.size, "The completed batch must release the submit gate while latest history prepares")
    }

    @Test fun scrollingAfterAnIntermediatePartRevokesTheWholeBatchsLatestNavigation() {
        val fixture = Fixture()
        fixture.controller.event(ChatEvent.NewChat(ChatItem(1, "one", 10, unreadCount = 1,
            lastMessage = ChatMessage(100, 1, text = "latest"), lastReadInboxId = 50)))
        fixture.transport.unread = listOf(ChatMessage(51, 1, text = "unread"))
        fixture.select(2)
        fixture.select(1)
        val histories = fixture.transport.historyCalls
        val token = fixture.state.token
        fixture.controller.sendText(token, RichMessageText("abcdefghijklmno"), 1)
        fixture.accept(0)
        assertEquals(listOf(51L), fixture.state.messages.map { it.id },
            "An accepted intermediate part with a negative temporary id cannot bypass the unloaded newer range")
        fixture.controller.cancelSendLatestNavigation(token)
        fixture.accept(1)
        assertEquals(histories, fixture.transport.historyCalls,
            "A later batch part must retain the original attempt's revoked navigation owner")
        assertEquals(listOf(51L), fixture.state.messages.map { it.id })
        assertEquals(1L, fixture.state.acceptedSendId)
        assertFalse(fixture.state.sendPending)
        assertEquals(token, fixture.state.token)
        fixture.controller.event(ChatEvent.SendSucceeded(ChatMessage(102, 1, outgoing = true,
            text = "abcdefgh", delivery = MessageDelivery.SENT), -100))
        assertEquals(listOf(51L), fixture.state.messages.map { it.id },
            "A later native final identity remains outside the visible old window after navigation was revoked")
    }

    private val replyBatchText = RichMessageText("abcdefghijklmno")

    private fun partialReplyFailure(): Fixture {
        val fixture = Fixture()
        fixture.controller.event(ChatEvent.NewMessage(ChatMessage(42, 1, text = "quote source")))
        fixture.controller.setReply(fixture.state.token, 42)
        fixture.controller.sendText(fixture.state.token, replyBatchText, 4)
        fixture.accept(0)
        fixture.transport.sends[1].completed(ChatResult(error = ChatError.REQUEST_FAILED))
        fixture.flush()
        assertEquals(42L, fixture.state.replyTarget?.id)
        assertEquals(0L, fixture.state.acceptedSendId)
        return fixture
    }

    private class Fixture(knownLimit: Int? = 8) {
        val tasks = ArrayDeque<() -> Unit>()
        val transport = Session(knownLimit)
        var state = ChatSnapshot()
        val controller = ChatController(1, transport, tasks::addLast, { state = it })
        init {
            controller.start()
            controller.event(ChatEvent.NewChat(ChatItem(1, "one", 10)))
            controller.event(ChatEvent.NewChat(ChatItem(2, "two", 9)))
            select(1)
        }
        fun select(chatId: Long) { controller.select(state.token, chatId); flush() }
        fun flush() { while (tasks.isNotEmpty()) tasks.removeFirst().invoke() }
        fun accept(index: Int) {
            val send = transport.sends[index]
            send.completed(ChatResult(ChatMessage(-100L - index, send.chatId, outgoing = true,
                text = send.content.text, entities = send.content.entities, delivery = MessageDelivery.PENDING)))
            flush()
        }
    }

    private class Session(override val knownMessageTextLimit: Int?) : TdChatSession {
        data class Send(val chatId: Long, val content: RichMessageText, val reply: OutgoingReply?, val completed: (ChatResult<ChatMessage>) -> Unit)
        val sends = mutableListOf<Send>()
        val limits = mutableListOf<(ChatResult<Int>) -> Unit>()
        val domains = mutableListOf<(ChatResult<Set<String>>) -> Unit>()
        var unread = emptyList<ChatMessage>()
        var around = emptyList<ChatMessage>()
        var historyCalls = 0
        override fun linkConfirmationDomains(result: (ChatResult<Set<String>>) -> Unit) { domains += result }
        override fun messageTextLimit(result: (ChatResult<Int>) -> Unit) { limits += result }
        override fun sendRichText(chatId: Long, text: RichMessageText, reply: OutgoingReply?, result: (ChatResult<ChatMessage>) -> Unit) {
            sends += Send(chatId, text, reply, result)
        }
        override fun loadChats(limit: Int, result: (ChatResult<ChatLoadResult>) -> Unit) {}
        override fun history(chatId: Long, fromMessageId: Long, limit: Int, result: (ChatResult<List<ChatMessage>>) -> Unit) {
            historyCalls++
            result(ChatResult(emptyList()))
        }
        override fun unreadHistory(chatId: Long, afterMessageId: Long, result: (ChatResult<List<ChatMessage>>) -> Unit) { result(ChatResult(unread)) }
        override fun historyAround(chatId: Long, messageId: Long, result: (ChatResult<List<ChatMessage>>) -> Unit) { result(ChatResult(around)) }
        override fun open(chatId: Long) {}
        override fun close(chatId: Long) {}
        override fun sendText(chatId: Long, text: String, result: (ChatResult<ChatMessage>) -> Unit) { error("Use rich text") }
        override fun retry(chatId: Long, messageId: Long, result: (ChatResult<List<ChatMessage>>) -> Unit) {}
        override fun markRead(chatId: Long, messageIds: List<Long>, result: (ChatResult<Unit>) -> Unit) {}
    }
}
