package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import org.drinkless.tdlib.TdApi
import kotlin.test.*

/** Native same-chat quotes may carry text/entities without an origin. No account or JNI. */
class QuotedReplyContentAuthorControllerTest {
    @Test
    fun visibleNativeQuoteWithNullOriginResolvesOffWindowForwardAuthorWithoutReplacingQuote() {
        val f = Fixture()
        f.quote(1, 500)
        val original = f.currentReply
        assertEquals(10L, original.chatId)
        assertFalse(original.contentAuthorKnown)
        assertTrue(f.replies.isEmpty())
        val unrelated = f.unrelatedRequests
        f.visible(1)
        val request = assertIs<TdApi.GetRepliedMessage>(f.replies.single().command)
        assertEquals(10L, request.chatId)
        assertEquals(1L, request.messageId)
        f.replies.single().done(target(500, TdApi.MessageOriginUser(17)))
        f.flush()
        assertEquals(original.copy(senderId = 33, contentAuthor = ForwardSourceIdentity.User(17), contentAuthorKnown = true), f.currentReply)
        assertEquals(17L, assertIs<TdApi.GetUser>(f.metadata.single().command).userId)
        f.metadata.single().done(TdApi.User().apply { id = 17; firstName = "author"; accentColorId = 8 })
        f.flush()
        assertEquals(8, f.state.senderAppearances[17]?.name?.colorId)
        assertNull(f.state.senderAppearances[33])
        assertEquals(unrelated, f.unrelatedRequests)
        assertEquals(listOf(1L), f.state.messages.map { it.id })
    }

    @Test
    fun hiddenForwardResolvedFromQuoteIsKnownNeutralWithoutForwarderLookup() {
        val f = Fixture()
        f.quote(1, 500)
        val original = f.currentReply
        f.visible(1)
        f.replies.single().done(target(500, TdApi.MessageOriginHiddenUser("hidden author")))
        f.flush()
        assertEquals(original.copy(senderId = 33, contentAuthorKnown = true), f.currentReply)
        assertNull(f.currentReply.contentAuthor)
        assertTrue(f.metadata.isEmpty())
    }

    @Test
    fun inactiveQuoteDoesNotFetchAndLateInactiveResultAllowsVisibleRetry() {
        val f = Fixture()
        f.quote(1, 500)
        f.controller.visibleMessages(f.state.token, listOf(1), false)
        assertTrue(f.replies.isEmpty())
        f.visible(1)
        val first = f.replies.single()
        f.controller.visibleMessages(f.state.token, listOf(1), false)
        first.done(target(500, TdApi.MessageOriginUser(17)))
        f.flush()
        assertFalse(f.currentReply.contentAuthorKnown)
        assertEquals(0L, f.currentReply.senderId)
        assertTrue(f.metadata.isEmpty())
        f.visible(1)
        assertEquals(2, f.replies.size)
        f.replies.last().done(target(500, TdApi.MessageOriginChat(17, "signature")))
        f.flush()
        assertEquals(ForwardSourceIdentity.Chat(17), f.currentReply.contentAuthor)
    }

    @Test
    fun offscreenQuoteResultCannotInstallAuthorAndCanRetryWhenItReturns() {
        val f = Fixture()
        f.quote(1, 500)
        f.visible(1)
        val first = f.replies.single()
        f.visible()
        first.done(target(500, TdApi.MessageOriginUser(17)))
        f.flush()
        assertFalse(f.currentReply.contentAuthorKnown)
        f.visible(1)
        assertEquals(2, f.replies.size)
    }

    @Test
    fun deletingAuthorOnlyResolutionPreservesNativeQuoteEntitiesPositionAndText() {
        val f = Fixture()
        f.quote(1, 500)
        val original = f.currentReply
        f.visible(1)
        f.replies.single().done(target(500, TdApi.MessageOriginUser(17)))
        f.flush()
        val metadata = f.metadata.single()
        f.controller.event(ChatEvent.Deleted(10, listOf(500), true))
        assertEquals(original, f.currentReply)
        assertEquals(7, f.currentReply.quotePosition)
        assertTrue(f.currentReply.isQuote)
        assertTrue(f.currentReply.entities.isNotEmpty())
        metadata.done(TdApi.User().apply { id = 17; firstName = "late"; accentColorId = 8 })
        f.flush()
        assertEquals(original, f.currentReply)
        assertTrue(f.state.senderAppearances.isEmpty())
        assertEquals(1, f.replies.size)
    }

    @Test
    fun deletedOriginalBeforeAuthorReplyCallbackCannotReviveAuthorOrClearNativeQuote() {
        val f = Fixture()
        f.quote(1, 500)
        val original = f.currentReply
        f.visible(1)
        f.controller.event(ChatEvent.Deleted(10, listOf(500), true))
        f.replies.single().done(target(500, TdApi.MessageOriginUser(17)))
        f.flush()
        assertEquals(original, f.currentReply)
        assertTrue(f.metadata.isEmpty())
        assertEquals(listOf(1L), f.state.messages.map { it.id })
    }

    @Test
    fun changedNativeQuoteRejectsOlderWitnessAndResolvesItsOwnAuthor() {
        val f = Fixture()
        f.quote(1, 500)
        f.visible(1)
        val first = f.replies.single()
        f.quote(1, 500, "new quoted text")
        first.done(target(500, TdApi.MessageOriginUser(17)))
        f.flush()
        assertEquals("new quoted text", f.currentReply.text)
        assertFalse(f.currentReply.contentAuthorKnown)
        assertTrue(f.metadata.isEmpty())
        assertEquals(2, f.replies.size)
        f.replies.last().done(target(500, TdApi.MessageOriginHiddenUser("new hidden")))
        f.flush()
        assertTrue(f.currentReply.contentAuthorKnown)
        assertNull(f.currentReply.contentAuthor)
        assertEquals("new quoted text", f.currentReply.text)
    }

    @Test
    fun ordinaryEmptyPreviewStillResolvesEagerlyAndUsesFetchedBody() {
        val f = Fixture()
        f.controller.event(ChatEvent.NewMessage(ChatMessage(1, 10, senderId = 33, outgoing = true,
            replyTo = MessageReply(10, 500))))
        assertEquals(1, f.replies.size)
        f.replies.single().done(target(500, TdApi.MessageOriginUser(17)))
        f.flush()
        assertEquals("full target body", f.currentReply.text)
        assertTrue(f.currentReply.contentAuthorKnown)
        assertEquals(ForwardSourceIdentity.User(17), f.currentReply.contentAuthor)
        assertTrue(f.metadata.isEmpty())
    }

    @Test
    fun quoteAuthorRequestsStayBoundedAndCompleteWithoutLoadingHistory() {
        val f = Fixture()
        (1L..20L).forEach { f.quote(it, it + 500) }
        val before = f.unrelatedRequests
        f.controller.visibleMessages(f.state.token, (1L..20L).toList(), true)
        assertEquals(8, f.replies.size)
        var completed = 0
        while (completed < f.replies.size) {
            val request = f.replies[completed++]
            val containingId = assertIs<TdApi.GetRepliedMessage>(request.command).messageId
            request.done(target(containingId + 500, TdApi.MessageOriginHiddenUser("hidden")))
            f.flush()
            assertTrue(f.replies.size - completed <= 8)
        }
        assertEquals(20, f.replies.size)
        assertEquals(20, f.state.messages.size)
        assertEquals(before, f.unrelatedRequests)
        assertTrue(f.metadata.isEmpty())
    }

    @Test
    fun nativeKnownHiddenQuoteAndAlreadyLoadedOriginalNeedNoAuthorFetch() {
        val f = Fixture()
        f.quote(1, 500, origin = TdApi.MessageOriginHiddenUser("hidden"))
        f.controller.event(ChatEvent.NewMessage(ChatMessage(501, 10, senderId = 33, outgoing = true, text = "loaded")))
        f.quote(2, 501)
        f.visible(1, 2)
        assertTrue(f.replies.isEmpty())
        assertTrue(assertNotNull(f.state.messages.first { it.id == 1L }.replyTo).contentAuthorKnown)
    }

    private class Fixture {
        data class Request(val command: TdApi.Function<*>, val done: (TdApi.Object) -> Unit)
        val requests = mutableListOf<Request>()
        val replies get() = requests.filter { it.command is TdApi.GetRepliedMessage }
        val metadata get() = requests.filter { it.command is TdApi.GetUser || it.command is TdApi.GetChat }
        val unrelatedRequests get() = requests.count {
            it.command is TdApi.GetChatHistory || it.command is TdApi.OpenChat || it.command is TdApi.ViewMessages
        }
        val currentReply get() = assertNotNull(state.messages.single().replyTo)
        private val queued = ArrayDeque<() -> Unit>()
        var state = ChatSnapshot()
        val session = OfficialTdChatSession { command, done, _ -> requests += Request(command, done) }
        val controller = ChatController(1, session, queued::addLast, { state = it })

        init {
            controller.start()
            controller.event(ChatEvent.NewChat(ChatItem(10, "private", order = 100, privateUserId = 33)))
            controller.select(state.token, 10)
        }

        fun quote(id: Long, targetId: Long, text: String = "native quote", origin: TdApi.MessageOrigin? = null) {
            controller.event(ChatEvent.NewMessage(mapChatMessage(TdApi.Message().apply {
                this.id = id; chatId = 10; senderId = TdApi.MessageSenderUser(33); isOutgoing = true
                content = TdApi.MessageText(TdApi.FormattedText("containing", emptyArray()), null, null)
                replyTo = TdApi.MessageReplyToMessage().apply {
                    chatId = 10; messageId = targetId; this.origin = origin
                    quote = TdApi.TextQuote(TdApi.FormattedText(text, arrayOf(TdApi.TextEntity(0, 4, TdApi.TextEntityTypeBold()))), emptyArray(), 7, false)
                }
            })))
        }

        fun visible(vararg ids: Long) = controller.visibleMessages(state.token, ids.toList(), true)
        fun flush() { while (queued.isNotEmpty()) queued.removeFirst().invoke() }
    }

    private companion object {
        fun target(id: Long, origin: TdApi.MessageOrigin) = TdApi.Message().apply {
            this.id = id; chatId = 10; senderId = TdApi.MessageSenderUser(33)
            content = TdApi.MessageText(TdApi.FormattedText("full target body", emptyArray()), null, null)
            forwardInfo = TdApi.MessageForwardInfo(origin, 123, null, "")
        }
    }
}
