package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import org.drinkless.tdlib.TdApi
import kotlin.test.*

class ChatTransportTest {
    @Test
    fun mainListLoadsThroughUpdatesAndOnly404MeansEndOfList() {
        val harness = Harness()
        val results = mutableListOf<ChatResult<ChatLoadResult>>()
        harness.session.loadChats(50, results::add)
        val request = assertIs<TdApi.LoadChats>(harness.requests.single().request)
        assertIs<TdApi.ChatListMain>(request.chatList)
        assertEquals(50, request.limit)
        harness.requests.single().result(TdApi.Ok())
        assertEquals(ChatLoadResult(false), results.single().value)
        harness.session.loadChats(50, results::add)
        harness.requests.last().result(TdApi.Error(404, "private details"))
        assertEquals(ChatLoadResult(true), results.last().value)
        assertNull(results.last().error)
        harness.session.loadChats(50, results::add)
        harness.requests.last().result(TdApi.Error(500, "private details"))
        assertEquals(ChatError.REQUEST_FAILED, results.last().error)
        assertNull(results.last().value)
    }

    @Test
    fun historyUsesExactCursorAndPreservesShortPagesWithoutGuessingEnd() {
        val harness = Harness()
        val results = mutableListOf<ChatResult<List<ChatMessage>>>()
        harness.session.history(20, 100, 50, results::add)
        val request = assertIs<TdApi.GetChatHistory>(harness.requests.single().request)
        assertEquals(20, request.chatId)
        assertEquals(100, request.fromMessageId)
        assertEquals(0, request.offset)
        assertEquals(50, request.limit)
        assertFalse(request.onlyLocal)
        harness.requests.single().result(TdApi.Messages(1000, arrayOf(message(90), message(80))))
        assertEquals(listOf(90L, 80L), results.single().value?.map { it.id })
        assertNull(results.single().error)
    }

    @Test
    fun openAndCloseDoNotAutomaticallyMarkMessagesRead() {
        val harness = Harness()
        harness.session.open(20)
        harness.session.close(20)
        assertEquals(20, assertIs<TdApi.OpenChat>(harness.requests[0].request).chatId)
        assertEquals(20, assertIs<TdApi.CloseChat>(harness.requests[1].request).chatId)
        assertEquals(2, harness.requests.size)
    }

    @Test
    fun explicitReadActionUsesOnlySuppliedDistinctMessageIds() {
        val harness = Harness()
        val results = mutableListOf<ChatResult<Unit>>()
        harness.session.markRead(20, listOf(100, 101, 100), results::add)
        val request = assertIs<TdApi.ViewMessages>(harness.requests.single().request)
        assertEquals(20, request.chatId)
        assertContentEquals(longArrayOf(100, 101), request.messageIds)
        assertIs<TdApi.MessageSourceChatHistory>(request.source)
        assertTrue(request.forceRead)
        harness.requests.single().result(TdApi.Ok())
        assertEquals(Unit, results.single().value)
        harness.session.markRead(20, emptyList(), results::add)
        assertEquals(1, harness.requests.size)
        assertEquals(2, results.size)
    }

    @Test
    fun plainTextSendPreservesUnicodeAndWhitespaceWithoutPaymentOrMarkup() {
        val harness = Harness()
        val results = mutableListOf<ChatResult<ChatMessage>>()
        val text = "  合成测试🙂\n**原样文本**  "
        harness.session.sendText(20, text, results::add)
        val request = assertIs<TdApi.SendMessage>(harness.requests.single().request)
        assertEquals(20, request.chatId)
        assertNull(request.topicId)
        assertNull(request.replyTo)
        assertNull(request.replyMarkup)
        assertFalse(request.options.allowPaidBroadcast)
        assertEquals(0, request.options.paidMessageStarCount)
        assertFalse(request.options.onlyPreview)
        assertNull(request.options.schedulingState)
        val content = assertIs<TdApi.InputMessageText>(request.inputMessageContent)
        assertEquals(text, content.text.text)
        assertTrue(content.text.entities.isEmpty())
        assertFalse(content.linkPreviewOptions.isDisabled)
        assertFalse(content.clearDraft)
        harness.requests.single().result(message(-10).apply { sendingState = TdApi.MessageSendingStatePending(0) })
        assertEquals(MessageDelivery.PENDING, results.single().value?.delivery)
    }

    @Test
    fun blankTextNeverCreatesANativeRequest() {
        val harness = Harness()
        val results = mutableListOf<ChatResult<ChatMessage>>()
        harness.session.sendText(20, " \n\t ", results::add)
        assertTrue(harness.requests.isEmpty())
        assertEquals(ChatError.EMPTY_TEXT, results.single().error)
    }

    @Test
    fun retryUsesExistingFailedMessageWithoutAuthorizingStarsAndHandlesUnsendableResponse() {
        val harness = Harness()
        val results = mutableListOf<ChatResult<List<ChatMessage>>>()
        harness.session.retry(20, -10, results::add)
        val request = assertIs<TdApi.ResendMessages>(harness.requests.single().request)
        assertEquals(20, request.chatId)
        assertContentEquals(longArrayOf(-10), request.messageIds)
        assertNull(request.quote)
        assertEquals(0, request.paidMessageStarCount)
        harness.requests.single().result(TdApi.Messages(1, arrayOf(message(-11))))
        assertEquals(-11, results.single().value?.single()?.id)
        harness.session.retry(20, -12, results::add)
        harness.requests.last().result(TdApi.Messages(1, arrayOfNulls<TdApi.Message>(1)))
        assertEquals(ChatError.REQUEST_FAILED, results.last().error)
    }

    @Test
    fun requestErrorsAreFiniteAndNativeTextNeverEscapes() {
        val cases = listOf(
            Triple(429, "private rate details", ChatError.RATE_LIMITED),
            Triple(400, "FLOOD_WAIT_10", ChatError.RATE_LIMITED),
            Triple(400, "SLOWMODE_WAIT_10", ChatError.RATE_LIMITED),
            Triple(400, "ALLOW_PAYMENT_REQUIRED_10", ChatError.UNSUPPORTED),
            Triple(403, "private forbidden details", ChatError.FORBIDDEN),
            Triple(400, "CHAT_WRITE_FORBIDDEN", ChatError.FORBIDDEN),
            Triple(400, "MESSAGE_TOO_LONG", ChatError.TEXT_TOO_LONG),
            Triple(400, "MESSAGE_EMPTY", ChatError.EMPTY_TEXT),
            Triple(400, "MESSAGE_EMPTY private suffix", ChatError.REQUEST_FAILED),
            Triple(500, "private message contents", ChatError.REQUEST_FAILED),
        )
        for ((code, nativeText, expected) in cases) {
            val harness = Harness()
            val results = mutableListOf<ChatResult<ChatMessage>>()
            harness.session.sendText(20, "synthetic private message", results::add)
            harness.requests.single().result(TdApi.Error(code, nativeText))
            assertEquals(expected, results.single().error)
            assertEquals(code, results.single().code)
            assertFalse(results.toString().contains("private"))
        }
    }

    @Test
    fun requestsCompleteOnceForErrorExceptionAndUnexpectedResponses() {
        for (response in listOf(TdApi.Error(500, "private"), TdApi.OptionValueString("private"), message(100))) {
            val harness = Harness()
            val results = mutableListOf<ChatResult<ChatMessage>>()
            harness.session.sendText(20, "private", results::add)
            val request = harness.requests.single()
            request.result(response)
            request.error()
            request.result(message(101))
            assertEquals(1, results.size)
            assertFalse(results.toString().contains("private"))
        }
        val results = mutableListOf<ChatResult<ChatMessage>>()
        val throwing = OfficialTdChatSession { _, _, _ -> throw IllegalStateException("private exception") }
        throwing.sendText(20, "private", results::add)
        assertEquals(ChatError.REQUEST_FAILED, results.single().error)
        assertFalse(results.toString().contains("private"))
    }

    @Test
    fun sendFailureRetriesRequireSupportedFixesAndRepresentableDelay() {
        fun mapped(state: TdApi.MessageSendingStateFailed) = mapChatMessage(message(-10).apply { sendingState = state })
        fun failed() = TdApi.MessageSendingStateFailed(TdApi.Error(500, "private"), true, false, false, false, 0, 1.1)
        assertEquals(MessageDelivery.FAILED, mapped(failed()).delivery)
        assertTrue(mapped(failed()).canRetry)
        assertEquals(2, mapped(failed()).retryAfterSeconds)
        val unsupported = listOf(
            failed().apply { canRetry = false },
            failed().apply { needAnotherSender = true },
            failed().apply { needAnotherReplyQuote = true },
            failed().apply { needDropReply = true },
            failed().apply { requiredPaidMessageStarCount = 1 },
            failed().apply { retryAfter = Double.NaN },
            failed().apply { retryAfter = Double.POSITIVE_INFINITY },
            failed().apply { retryAfter = Int.MAX_VALUE.toDouble() + 1 },
        )
        unsupported.forEach { assertFalse(mapped(it).canRetry) }
        assertEquals(0, mapped(failed().apply { retryAfter = -1.0 }).retryAfterSeconds)
        assertFalse(mapped(failed()).toString().contains("private"))
    }

    @Test
    fun textAndMediaMappingUsesExplicitFieldsAndSafeFallbacks() {
        val native = message(100).apply {
            senderId = TdApi.MessageSenderChat(88)
            isOutgoing = true
            date = 1234
        }
        val mapped = mapChatMessage(native)
        assertEquals("synthetic private text", mapped.text)
        assertEquals(88, mapped.senderId)
        assertTrue(mapped.senderIsChat)
        assertTrue(mapped.outgoing)
        assertEquals(1234, mapped.date)
        assertEquals(MessageKind.TEXT, mapped.kind)
        assertEquals(MessageDelivery.SENT, mapped.delivery)
        val photo = TdApi.MessagePhoto().apply { caption = TdApi.FormattedText("synthetic private caption", emptyArray()) }
        assertEquals("synthetic private caption" to MessageKind.PHOTO, mapMessageContent(photo))
        val unknown = object : TdApi.MessageContent() {
            override fun getConstructor() = Int.MIN_VALUE
            override fun toString() = error("Native object rendering must never be called")
        }
        assertEquals("" to MessageKind.OTHER, mapMessageContent(unknown))
    }

    @Test
    fun listMappingIncludesMainPositionReplacementDraftMovementAndReadCounts() {
        val events = mutableListOf<ChatEvent>()
        val mapper = TdChatUpdateMapper(events::add)
        mapper.accept(TdApi.UpdateNewChat(chat(20).apply {
            positions = arrayOf(TdApi.ChatPosition(TdApi.ChatListArchive(), 999, false, null), main(100))
            unreadCount = 3
            lastReadOutboxMessageId = 80
        }))
        val first = assertIs<ChatEvent.NewChat>(events.single()).chat
        assertEquals(100, first.order)
        assertEquals(3, first.unreadCount)
        assertEquals(80, first.lastReadOutboxId)
        assertTrue(first.canSend)
        mapper.accept(TdApi.UpdateChatPosition(20, TdApi.ChatPosition(TdApi.ChatListArchive(), 500, false, null)))
        assertEquals(ChatEvent.Position(20, 500, ChatListRef(-1)), events.last())
        mapper.accept(TdApi.UpdateChatLastMessage(20, message(100), emptyArray()))
        assertEquals(0, assertIs<ChatEvent.LastMessage>(events.last()).order)
        mapper.accept(TdApi.UpdateChatDraftMessage(20, null, arrayOf(main(200))))
        assertEquals(ChatEvent.Positions(20, mapOf(ChatListRef() to 200L)), events.last())
        mapper.accept(TdApi.UpdateChatPosition(20, main(0)))
        assertEquals(ChatEvent.Position(20, 0), events.last())
        mapper.accept(TdApi.UpdateChatReadInbox(20, 100, 0))
        assertEquals(ChatEvent.Inbox(20, 0, 100), events.last())
        mapper.accept(TdApi.UpdateChatReadOutbox(20, 100))
        assertEquals(ChatEvent.Outbox(20, 100), events.last())
        assertFalse(events.toString().contains("private"))
    }

    @Test
    fun messageUpdatesCarryReplacementIdsAndIgnoreCacheEviction() {
        val events = mutableListOf<ChatEvent>()
        val mapper = TdChatUpdateMapper(events::add)
        mapper.accept(TdApi.UpdateNewMessage(message(-10)))
        assertIs<ChatEvent.NewMessage>(events.last())
        mapper.accept(TdApi.UpdateMessageSendSucceeded(message(100), -10))
        assertEquals(-10, assertIs<ChatEvent.SendSucceeded>(events.last()).oldMessageId)
        assertEquals(100, assertIs<ChatEvent.SendSucceeded>(events.last()).message.id)
        mapper.accept(TdApi.UpdateMessageSendFailed(message(-12), -11, TdApi.Error(500, "private error")))
        assertEquals(-11, assertIs<ChatEvent.SendFailed>(events.last()).oldMessageId)
        assertEquals(MessageDelivery.FAILED, assertIs<ChatEvent.SendFailed>(events.last()).message.delivery)
        mapper.accept(TdApi.UpdateMessageContent(20, 100, TdApi.MessageText(TdApi.FormattedText("private edit", emptyArray()), null, null)))
        assertEquals("private edit", assertIs<ChatEvent.Content>(events.last()).text)
        val count = events.size
        mapper.accept(TdApi.UpdateDeleteMessages(20, longArrayOf(100), false, true))
        assertEquals(count, events.size)
        mapper.accept(TdApi.UpdateDeleteMessages(20, longArrayOf(100), true, false))
        assertEquals(ChatEvent.Deleted(20, listOf(100), true), events.last())
        assertFalse(events.toString().contains("private"))
    }

    @Test
    fun membershipAndChannelPostingRightsOverrideDefaultPermissions() {
        val events = mutableListOf<ChatEvent>()
        val mapper = TdChatUpdateMapper(events::add)
        mapper.accept(TdApi.UpdateNewChat(chat(20).apply { type = TdApi.ChatTypeBasicGroup(30) }))
        assertFalse(assertIs<ChatEvent.NewChat>(events.last()).chat.canSend)
        mapper.accept(TdApi.UpdateBasicGroup(TdApi.BasicGroup(30, 1, TdApi.ChatMemberStatusMember(0), true, 0)))
        // This native fixture permits basic text only; voice permission is independently false.
        assertEquals(ChatEvent.Permissions(20, true, false, false, false, false, canSendVoiceNotes = false), events.last())
        mapper.accept(TdApi.UpdateChatPermissions(20, TdApi.ChatPermissions()))
        assertEquals(ChatEvent.Permissions(20, false), events.last())
        mapper.accept(TdApi.UpdateBasicGroup(TdApi.BasicGroup(30, 1, TdApi.ChatMemberStatusCreator(false, true), true, 0)))
        assertEquals(ChatEvent.Permissions(20, true), events.last())
        mapper.accept(TdApi.UpdateBasicGroup(TdApi.BasicGroup(30, 1, TdApi.ChatMemberStatusLeft(), true, 0)))
        assertEquals(ChatEvent.Permissions(20, false), events.last())

        mapper.accept(TdApi.UpdateSupergroup(TdApi.Supergroup().apply { id = 40; status = TdApi.ChatMemberStatusMember(0); isChannel = true }))
        mapper.accept(TdApi.UpdateNewChat(chat(21).apply { type = TdApi.ChatTypeSupergroup(40, true) }))
        assertFalse(assertIs<ChatEvent.NewChat>(events.last()).chat.canSend)
        mapper.accept(TdApi.UpdateSupergroup(TdApi.Supergroup().apply {
            id = 40
            status = TdApi.ChatMemberStatusAdministrator(false, TdApi.ChatAdministratorRights().apply { canPostMessages = true })
            isChannel = true
        }))
        assertEquals(ChatEvent.Permissions(21, true), events.last())
        mapper.accept(TdApi.UpdateSupergroup(TdApi.Supergroup().apply {
            id = 40
            status = TdApi.ChatMemberStatusAdministrator(false, TdApi.ChatAdministratorRights())
            isChannel = true
        }))
        assertEquals(ChatEvent.Permissions(21, false), events.last())
    }

    @Test
    fun secretAndUnknownChatTypesStayReadOnlyAndRestrictedMembersCannotSend() {
        val events = mutableListOf<ChatEvent>()
        val mapper = TdChatUpdateMapper(events::add)
        mapper.accept(TdApi.UpdateNewChat(chat(20).apply { type = TdApi.ChatTypeSecret(1, 2) }))
        assertFalse(assertIs<ChatEvent.NewChat>(events.last()).chat.canSend)
        mapper.accept(TdApi.UpdateNewChat(chat(21).apply { type = null }))
        assertFalse(assertIs<ChatEvent.NewChat>(events.last()).chat.canSend)
        mapper.accept(TdApi.UpdateBasicGroup(TdApi.BasicGroup(30, 1, TdApi.ChatMemberStatusRestricted(true, 0, TdApi.ChatPermissions()), true, 0)))
        mapper.accept(TdApi.UpdateNewChat(chat(22).apply { type = TdApi.ChatTypeBasicGroup(30) }))
        assertFalse(assertIs<ChatEvent.NewChat>(events.last()).chat.canSend)
    }

    @Test
    fun displayNamesAndTitlesAreAvailableToUiButNotStringRendering() {
        val events = mutableListOf<ChatEvent>()
        val mapper = TdChatUpdateMapper(events::add)
        mapper.accept(TdApi.UpdateUser(TdApi.User().apply { id = 1; firstName = "private first"; lastName = "private last" }))
        assertEquals("private first private last", assertIs<ChatEvent.SenderName>(events.last()).name)
        mapper.accept(TdApi.UpdateChatTitle(20, "private title"))
        assertEquals("private title", assertIs<ChatEvent.Title>(events.last()).title)
        assertFalse(events.toString().contains("private"))
        assertFalse(ChatItem(20, "private title", lastMessage = mapChatMessage(message(100))).toString().contains("private"))
        assertFalse(ChatResult(mapChatMessage(message(100))).toString().contains("private"))
    }

    private fun message(id: Long) = TdApi.Message().apply {
        this.id = id
        chatId = 20
        senderId = TdApi.MessageSenderUser(1)
        content = TdApi.MessageText(TdApi.FormattedText("synthetic private text", emptyArray()), null, null)
    }

    private fun chat(id: Long) = TdApi.Chat().apply {
        this.id = id
        title = "synthetic private title"
        type = TdApi.ChatTypePrivate(1)
        permissions = TdApi.ChatPermissions().apply { canSendBasicMessages = true }
        positions = arrayOf(main(100))
    }

    private fun main(order: Long) = TdApi.ChatPosition(TdApi.ChatListMain(), order, false, null)

    private class Harness {
        val requests = mutableListOf<Request>()
        val session = OfficialTdChatSession { request, result, error -> requests += Request(request, result, error) }
    }

    private class Request(val request: TdApi.Function<*>, val result: (TdApi.Object) -> Unit, val error: () -> Unit)
}
