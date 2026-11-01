package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import org.drinkless.tdlib.TdApi
import kotlin.test.*

/** Synthetic native objects only; attribution lookups never open chats or retrieve their messages. */
class MessageForwardInfoTest {
    @Test
    fun allNativeOriginsKeepTheirDistinctIdentitiesAndSignaturesIndependentlyOfAReply() {
        val cases = listOf(
            TdApi.MessageOriginUser(17) to MessageForwardOrigin.User(17),
            TdApi.MessageOriginChat(-17, "native anonymous admin") to MessageForwardOrigin.Chat(-17, "native anonymous admin"),
            TdApi.MessageOriginChannel(-200, 900, "native signature") to MessageForwardOrigin.Channel(-200, 900, "native signature"),
            TdApi.MessageOriginHiddenUser("native hidden sender") to MessageForwardOrigin.HiddenUser("native hidden sender"),
        )
        for ((native, expected) in cases) {
            val mapped = mapChatMessage(nativeMessage().apply {
                forwardInfo = TdApi.MessageForwardInfo(native, 123, null, "")
                replyTo = TdApi.MessageReplyToMessage().apply { chatId = 10; messageId = 44 }
            })
            assertEquals(expected, mapped.forwardInfo?.origin)
            assertEquals(123, mapped.forwardInfo?.date)
            assertEquals(44, mapped.replyTo?.messageId)
            assertEquals("synthetic body", mapped.text)
        }
    }

    @Test
    fun immediateSourceNeverReplacesTheOriginalAuthorAndImportedOnlyMessagesAreNotForwards() {
        val mapped = mapChatMessage(nativeMessage().apply {
            forwardInfo = TdApi.MessageForwardInfo(TdApi.MessageOriginHiddenUser("original author"), 123,
                TdApi.ForwardSource(-17, 44, TdApi.MessageSenderChat(-99), "immediate sender", 456, true), "native psa")
            importInfo = TdApi.MessageImportInfo("imported sender", 789)
        })
        val info = assertNotNull(mapped.forwardInfo)
        assertEquals(MessageForwardOrigin.HiddenUser("original author"), info.origin)
        assertEquals(MessageForwardSource(-17, 44, -99, true, "immediate sender", 456, true), info.source)
        assertEquals("native psa", info.publicServiceAnnouncementType)
        assertEquals(MessageImportInfo("imported sender", 789), mapped.importInfo)
        assertEquals("转发自 original author", ChatSnapshot().forwardedHeaderText(info))

        val importedOnly = mapChatMessage(nativeMessage().apply { importInfo = TdApi.MessageImportInfo("imported sender", 789) })
        assertNull(importedOnly.forwardInfo)
        assertNotNull(importedOnly.importInfo)
        assertNull(mapChatMessage(nativeMessage()).forwardInfo)
    }

    @Test
    fun actualForwardRequestKeepsNativeAuthorAndCaptionWithNoPaidSend() {
        val fixture = Transport()
        var result = ChatResult<List<ChatMessage>>()
        fixture.session.forwardMessage(10, 50, 20) { result = it }
        assertIs<TdApi.GetMessageProperties>(fixture.requests.last().command)
        fixture.requests.last().done(TdApi.MessageProperties().apply { canBeForwarded = true })
        assertIs<TdApi.GetMessage>(fixture.requests.last().command)
        fixture.requests.last().done(nativeMessage(50))
        val request = assertIs<TdApi.ForwardMessages>(fixture.requests.last().command)
        assertFalse(request.sendCopy)
        assertFalse(request.removeCaption)
        assertEquals(0, request.options.paidMessageStarCount)
        assertFalse(request.options.allowPaidBroadcast)
        fixture.requests.last().done(TdApi.Messages(1, arrayOf(nativeMessage(60, 20).apply {
            forwardInfo = TdApi.MessageForwardInfo(TdApi.MessageOriginUser(17), 123, null, "")
        })))
        val message = assertNotNull(result.value).single()
        assertEquals(MessageForwardOrigin.User(17), message.forwardInfo?.origin)
        assertEquals("synthetic body", message.text)
        assertNull(message.replyTo)
    }

    @Test
    fun nativeNameLookupsKeepUserAndChatNamespacesSeparateAndValidateTheReturnedIdentity() {
        val fixture = Transport()
        val results = mutableListOf<ChatResult<String>>()
        fixture.session.forwardSourceName(ForwardSourceIdentity.User(17), results::add)
        assertEquals(17, assertIs<TdApi.GetUser>(fixture.requests.last().command).userId)
        fixture.requests.last().done(user(17, "actual user"))
        assertEquals("actual user", results.last().value)
        fixture.session.forwardSourceName(ForwardSourceIdentity.Chat(17), results::add)
        assertEquals(17, assertIs<TdApi.GetChat>(fixture.requests.last().command).chatId)
        fixture.requests.last().done(TdApi.Chat().apply { id = 17; title = "actual chat" })
        assertEquals("actual chat", results.last().value)
        fixture.session.forwardSourceName(ForwardSourceIdentity.User(17), results::add)
        fixture.requests.last().done(user(18, "unrelated user"))
        assertNull(results.last().value)
        assertEquals(ChatError.REQUEST_FAILED, results.last().error)
        fixture.session.forwardSourceName(ForwardSourceIdentity.Chat(17), results::add)
        fixture.requests.last().done(user(17, "wrong native namespace"))
        assertNull(results.last().value)
        assertTrue(fixture.requests.all { it.command is TdApi.GetUser || it.command is TdApi.GetChat })
    }

    @Test
    fun errorsDuplicateNativeCallbacksAndInvalidIdsNeverExposeNativeTextOrCompleteTwice() {
        val fixture = Transport()
        val results = mutableListOf<ChatResult<String>>()
        fixture.session.forwardSourceName(ForwardSourceIdentity.User(17), results::add)
        val request = fixture.requests.last()
        request.done(TdApi.Error(403, "private native details"))
        request.done(user(17, "private name"))
        request.failed()
        assertEquals(1, results.size)
        assertEquals(ChatError.FORBIDDEN, results.single().error)
        assertFalse(results.toString().contains("private"))
        fixture.session.forwardSourceName(ForwardSourceIdentity.User(0), results::add)
        fixture.session.forwardSourceName(ForwardSourceIdentity.Chat(0), results::add)
        assertEquals(1, fixture.requests.size)
        assertTrue(results.drop(1).all { it.error == ChatError.REQUEST_FAILED })
    }

    @Test
    fun selectedForwardOriginsResolveOnlyOnceAndHiddenNamesNeverBecomeLookups() {
        val fixture = Controller()
        fixture.message(1, MessageForwardOrigin.User(17))
        fixture.message(2, MessageForwardOrigin.User(17))
        fixture.message(3, MessageForwardOrigin.HiddenUser("exact hidden sender"))
        assertEquals(1, fixture.sourceRequests.size)
        assertEquals(17, assertIs<TdApi.GetUser>(fixture.sourceRequests.single().command).userId)
        fixture.sourceRequests.single().done(user(17, "resolved author"))
        fixture.flush()
        assertEquals("resolved author", fixture.snapshot.forwardSourceNames[ForwardSourceIdentity.User(17)])
        assertEquals("转发自 resolved author", fixture.snapshot.forwardedHeaderText(fixture.snapshot.messages.first().forwardInfo!!))
        assertEquals("转发自 exact hidden sender", fixture.snapshot.forwardedHeaderText(fixture.snapshot.messages.last().forwardInfo!!))
        assertEquals(3, fixture.snapshot.messages.size)
    }

    @Test
    fun knownNativeNamesAvoidExtraLookupsAndTheirLaterUpdatesOverrideAnOlderResolution() {
        val fixture = Controller()
        fixture.controller.event(ChatEvent.SenderName(17, "known author"))
        fixture.controller.event(ChatEvent.NewChat(ChatItem(17, "known chat")))
        fixture.message(1, MessageForwardOrigin.User(17))
        fixture.message(2, MessageForwardOrigin.Chat(17, "admin"))
        assertTrue(fixture.sourceRequests.isEmpty())
        assertEquals("转发自 known author", fixture.snapshot.forwardedHeaderText(fixture.snapshot.messages.first().forwardInfo!!))
        assertEquals("转发自 known chat (admin)", fixture.snapshot.forwardedHeaderText(fixture.snapshot.messages.last().forwardInfo!!))

        fixture.message(3, MessageForwardOrigin.User(18))
        fixture.controller.event(ChatEvent.SenderName(18, "new native name"))
        fixture.sourceRequests.single().done(user(18, "older lookup name"))
        fixture.flush()
        assertEquals("转发自 new native name", fixture.snapshot.forwardedHeaderText(fixture.snapshot.messages.last().forwardInfo!!))
    }

    @Test
    fun lateSourceLookupsCannotCrossASelectionOrReviveADeletedSourceMessage() {
        val fixture = Controller()
        fixture.message(1, MessageForwardOrigin.User(17))
        val old = fixture.sourceRequests.single()
        fixture.controller.event(ChatEvent.NewChat(ChatItem(20, "another synthetic chat", order = 80)))
        fixture.controller.select(fixture.snapshot.token, 20)
        assertEquals(20, fixture.snapshot.token.chatId, "The fixture must establish a selectable native-list chat first.")
        old.done(user(17, "old selection author"))
        fixture.flush()
        assertTrue(fixture.snapshot.forwardSourceNames.isEmpty())
        assertTrue(fixture.snapshot.messages.isEmpty())

        fixture.controller.select(fixture.snapshot.token, 10)
        fixture.message(2, MessageForwardOrigin.User(18))
        val deleted = fixture.sourceRequests.last()
        fixture.controller.event(ChatEvent.Deleted(10, listOf(2), true))
        deleted.done(user(18, "deleted message author"))
        fixture.flush()
        assertTrue(fixture.snapshot.forwardSourceNames.isEmpty())
        assertTrue(fixture.snapshot.messages.isEmpty())
    }

    @Test
    fun aRetiredControllerIgnoresLateNameLookupsAndOtherChatListPreviewsDoNotResolveSources() {
        val fixture = Controller()
        fixture.controller.event(ChatEvent.NewChat(ChatItem(20, "other chat", lastMessage = ChatMessage(50, 20,
            forwardInfo = MessageForwardInfo(MessageForwardOrigin.User(19))))))
        assertTrue(fixture.sourceRequests.isEmpty())
        fixture.message(1, MessageForwardOrigin.User(17))
        val old = fixture.sourceRequests.single()
        val lastPublished = fixture.snapshot
        val publications = fixture.publications
        fixture.controller.deactivate()
        old.done(user(17, "retired author"))
        fixture.flush()
        // Retirement changes actor-private state without publishing over the next controller's state.
        assertEquals(publications, fixture.publications)
        assertEquals(lastPublished, fixture.snapshot)
        assertTrue(fixture.snapshot.forwardSourceNames.isEmpty())
    }

    @Test
    fun privateAttributionContentAndSourceIdsStayRedactedInEveryModelString() {
        val info = MessageForwardInfo(MessageForwardOrigin.Channel(-123456, 998877, "private signature"), 1,
            MessageForwardSource(-123456, 998877, senderName = "private immediate source"), "private psa")
        val models = listOf(info, info.origin, info.source, MessageImportInfo("private imported sender"),
            MessageForwardOrigin.HiddenUser("private hidden sender"), ForwardSourceIdentity.User(998877),
            ChatMessage(1, 10, forwardInfo = info, importInfo = MessageImportInfo("private")),
            ChatSnapshot(forwardSourceNames = mapOf(ForwardSourceIdentity.User(998877) to "private name")))
        val printed = models.joinToString()
        assertFalse(printed.contains("private"))
        assertFalse(printed.contains("123456"))
        assertFalse(printed.contains("998877"))
    }

    private class Transport {
        data class Request(val command: TdApi.Function<*>, val done: (TdApi.Object) -> Unit, val failed: () -> Unit)
        val requests = mutableListOf<Request>()
        val session = OfficialTdChatSession { command, done, failed -> requests += Request(command, done, failed) }
    }

    private class Controller {
        val transport = Transport()
        val sourceRequests get() = transport.requests.filter { it.command is TdApi.GetUser || it.command is TdApi.GetChat }
        var snapshot = ChatSnapshot()
        var publications = 0
        private val queued = ArrayDeque<() -> Unit>()
        val controller = ChatController(1, transport.session, queued::addLast, { snapshot = it; publications++ })

        init {
            controller.start()
            controller.event(ChatEvent.NewChat(ChatItem(10, "synthetic chat", order = 100)))
            controller.select(snapshot.token, 10)
        }

        fun message(id: Long, origin: MessageForwardOrigin) = controller.event(ChatEvent.NewMessage(ChatMessage(id, 10,
            senderId = 33, text = "synthetic body", forwardInfo = MessageForwardInfo(origin))))

        fun flush() { while (queued.isNotEmpty()) queued.removeFirst().invoke() }
    }

    private companion object {
        fun nativeMessage(id: Long = 1, chatId: Long = 10) = TdApi.Message().apply {
            this.id = id
            this.chatId = chatId
            senderId = TdApi.MessageSenderUser(33)
            content = TdApi.MessageText().apply { text = TdApi.FormattedText("synthetic body", emptyArray()) }
        }

        fun user(id: Long, name: String) = TdApi.User().apply { this.id = id; firstName = name }
    }
}
