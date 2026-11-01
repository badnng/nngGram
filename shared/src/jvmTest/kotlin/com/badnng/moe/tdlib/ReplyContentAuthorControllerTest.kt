package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import org.drinkless.tdlib.TdApi
import kotlin.test.*

/** Real native mapping and actor callbacks, entirely synthetic and offline. */
class ReplyContentAuthorControllerTest {
    @Test
    fun nativeReplyOriginKeepsTypedContentAuthorAndHiddenKnownNeutral() {
        val cases = listOf(
            TdApi.MessageOriginUser(17) to ForwardSourceIdentity.User(17),
            TdApi.MessageOriginChat(17, "signature") to ForwardSourceIdentity.Chat(17),
            TdApi.MessageOriginChannel(17, 500, "signature") to ForwardSourceIdentity.Chat(17),
            TdApi.MessageOriginHiddenUser("hidden") to null,
        )
        for ((origin, expected) in cases) {
            val reply = assertNotNull(mapMessageReply(TdApi.MessageReplyToMessage().apply {
                chatId = 999; messageId = 500; this.origin = origin
            }))
            assertTrue(reply.contentAuthorKnown)
            assertEquals(expected, reply.contentAuthor)
        }
        val absent = assertNotNull(mapMessageReply(TdApi.MessageReplyToMessage().apply { chatId = 999; messageId = 500 }))
        assertFalse(absent.contentAuthorKnown)
        assertNull(absent.contentAuthor)
    }

    @Test
    fun offWindowGetRepliedMessagePreservesForwardedUserAndChatSameNumberIndependently() {
        val f = Fixture()
        f.reply(1, 500)
        f.reply(2, 501)
        val before = f.unrelatedRequests
        f.replies[0].done(target(500, TdApi.MessageOriginUser(17)))
        f.replies[1].done(target(501, TdApi.MessageOriginChat(17, "signature")))
        f.flush()
        assertEquals(listOf(1L, 2L), f.state.messages.map { it.id })
        val first = assertNotNull(f.state.messages[0].replyTo)
        val second = assertNotNull(f.state.messages[1].replyTo)
        assertEquals(33L, first.senderId, "Keep the original sender label separately from its content author.")
        assertEquals(ForwardSourceIdentity.User(17), first.contentAuthor)
        assertEquals(ForwardSourceIdentity.Chat(17), second.contentAuthor)
        assertTrue(first.contentAuthorKnown && second.contentAuthorKnown)
        assertTrue(f.metadata.isEmpty())
        f.visible(1, 2)
        assertEquals(2, f.metadata.size)
        f.metadata.single { it.command is TdApi.GetUser }.done(TdApi.User().apply {
            id = 17; firstName = "author"; accentColorId = 8; profileAccentColorId = 40
        })
        f.metadata.single { it.command is TdApi.GetChat }.done(TdApi.Chat().apply {
            id = 17; title = "chat author"; accentColorId = 9; profileAccentColorId = 41
        })
        f.flush()
        assertEquals(8, f.state.peerAppearance(17, false)?.name?.colorId)
        assertEquals(9, f.state.peerAppearance(17, true)?.name?.colorId)
        assertNull(f.state.peerAppearance(33, false))
        assertEquals(before, f.unrelatedRequests)
        assertEquals(listOf(1L, 2L), f.state.messages.map { it.id })
    }

    @Test
    fun offWindowHiddenForwardAuthorIsKnownNeutralAndNeverQueriesForwarder() {
        val f = Fixture()
        f.reply(1, 500)
        f.replies.single().done(target(500, TdApi.MessageOriginHiddenUser("hidden author")))
        f.flush()
        val reply = assertNotNull(f.state.messages.single().replyTo)
        assertEquals(33L, reply.senderId)
        assertTrue(reply.contentAuthorKnown)
        assertNull(reply.contentAuthor)
        f.visible(1)
        assertTrue(f.metadata.isEmpty())
        assertTrue(f.state.senderAppearances.isEmpty())
    }

    @Test
    fun offWindowOrdinaryReplyUsesItsRealSenderAndRetainsTextAndEntities() {
        val f = Fixture()
        f.reply(1, 500)
        f.replies.single().done(target(500).apply {
            content = TdApi.MessageText(TdApi.FormattedText("bold text", arrayOf(TdApi.TextEntity(0, 4, TdApi.TextEntityTypeBold()))), null, null)
        })
        f.flush()
        val reply = assertNotNull(f.state.messages.single().replyTo)
        assertEquals(ForwardSourceIdentity.User(33), reply.contentAuthor)
        assertTrue(reply.contentAuthorKnown)
        assertEquals("bold text", reply.text)
        assertEquals(1, reply.entities.size)
        f.visible(1)
        assertEquals(33L, assertIs<TdApi.GetUser>(f.metadata.single().command).userId)
    }

    @Test
    fun ordinaryChatSenderRemainsAChatContentAuthor() {
        val f = Fixture()
        f.reply(1, 500)
        f.replies.single().done(target(500).apply { senderId = TdApi.MessageSenderChat(33) })
        f.flush()
        val reply = assertNotNull(f.state.messages.single().replyTo)
        assertTrue(reply.senderIsChat)
        assertEquals(ForwardSourceIdentity.Chat(33), reply.contentAuthor)
        f.visible(1)
        assertEquals(33L, assertIs<TdApi.GetChat>(f.metadata.single().command).chatId)
    }

    @Test
    fun deletingFetchedOriginalClearsAuthorAndRejectsPendingColorCallback() {
        val f = Fixture()
        f.reply(1, 500)
        f.replies.single().done(target(500, TdApi.MessageOriginUser(17)))
        f.flush()
        f.visible(1)
        val pending = f.metadata.single()
        f.controller.event(ChatEvent.Deleted(999, listOf(500), true))
        val reply = assertNotNull(f.state.messages.single().replyTo)
        assertFalse(reply.contentAuthorKnown)
        assertNull(reply.contentAuthor)
        assertEquals(0L, reply.senderId)
        assertEquals("", reply.text)
        pending.done(TdApi.User().apply { id = 17; firstName = "late"; accentColorId = 8 })
        f.flush()
        assertTrue(f.state.senderAppearances.isEmpty())
        assertEquals(1, f.metadata.size)
    }

    @Test
    fun deletedOriginalBeforeReplyCallbackCannotReviveContentAuthor() {
        val f = Fixture()
        f.reply(1, 500)
        f.controller.event(ChatEvent.Deleted(999, listOf(500), true))
        f.replies.single().done(target(500, TdApi.MessageOriginUser(17)))
        f.flush()
        val reply = assertNotNull(f.state.messages.single().replyTo)
        assertFalse(reply.contentAuthorKnown)
        assertNull(reply.contentAuthor)
        f.visible(1)
        assertTrue(f.metadata.isEmpty())
    }

    @Test
    fun oldSelectionReplyCallbackCannotAddAuthorsOrMessagesToNewSelection() {
        val f = Fixture()
        f.reply(1, 500)
        val pending = f.replies.single()
        f.controller.event(ChatEvent.NewChat(ChatItem(20, "other", order = 80, privateUserId = 18)))
        f.controller.select(f.state.token, 20)
        pending.done(target(500, TdApi.MessageOriginUser(17)))
        f.flush()
        assertEquals(20L, f.state.token.chatId)
        assertTrue(f.state.messages.isEmpty())
        assertTrue(f.state.senderAppearances.isEmpty())
        assertTrue(f.metadata.isEmpty())
    }

    private class Fixture {
        data class Request(val command: TdApi.Function<*>, val done: (TdApi.Object) -> Unit)
        val requests = mutableListOf<Request>()
        val replies get() = requests.filter { it.command is TdApi.GetRepliedMessage }
        val metadata get() = requests.filter { it.command is TdApi.GetUser || it.command is TdApi.GetChat }
        val unrelatedRequests get() = requests.count {
            it.command is TdApi.GetChatHistory || it.command is TdApi.OpenChat || it.command is TdApi.ViewMessages
        }
        private val queued = ArrayDeque<() -> Unit>()
        var state = ChatSnapshot()
        val session = OfficialTdChatSession { command, done, _ -> requests += Request(command, done) }
        val controller = ChatController(1, session, queued::addLast, { state = it })

        init {
            controller.start()
            controller.event(ChatEvent.NewChat(ChatItem(10, "private", order = 100, privateUserId = 33)))
            controller.select(state.token, 10)
        }

        fun reply(id: Long, targetId: Long) = controller.event(ChatEvent.NewMessage(ChatMessage(id, 10,
            senderId = 33, outgoing = true, text = "containing message", replyTo = MessageReply(999, targetId))))
        fun visible(vararg ids: Long) = controller.visibleMessages(state.token, ids.toList(), true)
        fun flush() { while (queued.isNotEmpty()) queued.removeFirst().invoke() }
    }

    private companion object {
        fun target(id: Long, origin: TdApi.MessageOrigin? = null) = TdApi.Message().apply {
            this.id = id; chatId = 999; senderId = TdApi.MessageSenderUser(33)
            content = TdApi.MessageText(TdApi.FormattedText("original text", emptyArray()), null, null)
            forwardInfo = origin?.let { TdApi.MessageForwardInfo(it, 123, null, "") }
        }
    }
}
