package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import org.drinkless.tdlib.TdApi
import kotlin.test.*

/** Every response is synthetic; no client, native library or account mutation is involved. */
class MessageMutationTransportTest {
    @Test
    fun actionsRequireNativePermissionsAndDistinguishTextFromCaptionAndVideoNotes() {
        val cases = listOf(
            textContent("private text") to (true to false),
            TdApi.MessagePhoto() to (false to true),
            TdApi.MessageVideo() to (false to true),
            TdApi.MessageDocument() to (false to true),
            TdApi.MessageAudio() to (false to true),
            TdApi.MessageAnimation() to (false to true),
            TdApi.MessageVoiceNote() to (false to true),
            TdApi.MessageVideoNote() to (false to false),
            TdApi.MessageSticker() to (false to false),
        )
        cases.forEach { (content, expected) ->
            val fixture = Fixture()
            val results = mutableListOf<ChatResult<MessageActions>>()
            fixture.session.messageActions(10, 30, results::add)
            fixture.source(message(content), permissions())
            val actions = assertNotNull(results.single().value)
            assertEquals(expected.first, actions.canEditText)
            assertEquals(expected.second, actions.canEditCaption)
            assertTrue(actions.canDeleteForSelf)
            assertTrue(actions.canDeleteForAll)
            assertTrue(actions.canForward)
        }
        val fixture = Fixture()
        val results = mutableListOf<ChatResult<MessageActions>>()
        fixture.session.messageActions(10, 30, results::add)
        fixture.source(message(), TdApi.MessageProperties())
        assertEquals(MessageActions(), results.single().value)
    }

    @Test
    fun sendingMessagesCannotBeEditedOrForwardedEvenIfStaleFlagsSuggestOtherwise() {
        val fixture = Fixture()
        val results = mutableListOf<ChatResult<MessageActions>>()
        fixture.session.messageActions(10, 30, results::add)
        fixture.source(message().apply { sendingState = TdApi.MessageSendingStatePending() })
        val actions = assertNotNull(results.single().value)
        assertFalse(actions.canEditText)
        assertFalse(actions.canEditCaption)
        assertFalse(actions.canForward)
        assertTrue(actions.canDeleteForSelf)
    }

    @Test
    fun deletionUsesExactlyTheConfirmedScopeAndOneMessageId() {
        for (revoke in listOf(false, true)) {
            val fixture = Fixture()
            val results = mutableListOf<ChatResult<Unit>>()
            fixture.session.deleteMessage(10, 30, revoke, results::add)
            fixture.source(message())
            val deletion = assertIs<TdApi.DeleteMessages>(fixture.last.command)
            assertEquals(10L, deletion.chatId)
            assertContentEquals(longArrayOf(30), deletion.messageIds)
            assertEquals(revoke, deletion.revoke)
            fixture.last.result(TdApi.Ok())
            assertEquals(Unit, results.single().value)
        }
    }

    @Test
    fun selfOnlyDeletionNeverFallsBackToDeletingForEveryone() {
        val fixture = Fixture()
        val results = mutableListOf<ChatResult<Unit>>()
        fixture.session.deleteMessage(10, 30, false, results::add)
        fixture.source(message(), permissions().apply { canBeDeletedOnlyForSelf = false })
        assertEquals(ChatError.FORBIDDEN, results.single().error)
        assertTrue(fixture.requests.none { it.command is TdApi.DeleteMessages })
        fixture.session.deleteMessage(10, 30, true, results::add)
        fixture.source(message(), permissions().apply { canBeDeletedForAllUsers = false })
        assertEquals(ChatError.FORBIDDEN, results.last().error)
        assertTrue(fixture.requests.none { it.command is TdApi.DeleteMessages })
    }

    @Test
    fun forwardKeepsSourceAttributionAndCaptionAndNeverEnablesPayment() {
        val fixture = Fixture()
        val results = mutableListOf<ChatResult<List<ChatMessage>>>()
        fixture.session.forwardMessage(10, 30, 20, results::add)
        fixture.source(message())
        val forward = assertIs<TdApi.ForwardMessages>(fixture.last.command)
        assertEquals(10L, forward.fromChatId)
        assertEquals(20L, forward.chatId)
        assertContentEquals(longArrayOf(30), forward.messageIds)
        assertFalse(forward.sendCopy)
        assertFalse(forward.removeCaption)
        assertFalse(forward.options.allowPaidBroadcast)
        assertFalse(forward.options.onlyPreview)
        assertEquals(0L, forward.options.paidMessageStarCount)
        assertNull(forward.topicId)
        fixture.last.result(TdApi.Messages(1, arrayOf(message().apply { id = 70; chatId = 20; sendingState = TdApi.MessageSendingStatePending() })))
        assertEquals(20L, results.single().value?.single()?.chatId)
        assertEquals(MessageDelivery.PENDING, results.single().value?.single()?.delivery)
    }

    @Test
    fun forwardingRechecksPermissionAfterMenuLoadAndHonorsProtectedContent() {
        val fixture = Fixture()
        val actions = mutableListOf<ChatResult<MessageActions>>()
        fixture.session.messageActions(10, 30, actions::add)
        fixture.source(message())
        assertTrue(actions.single().value?.canForward == true)
        val results = mutableListOf<ChatResult<List<ChatMessage>>>()
        fixture.session.forwardMessage(10, 30, 20, results::add)
        fixture.source(message(), permissions().apply { canBeForwarded = false; hasProtectedContentByOtherUser = true })
        assertEquals(ChatError.FORBIDDEN, results.single().error)
        assertTrue(fixture.requests.none { it.command is TdApi.ForwardMessages })
    }

    @Test
    fun nullOrWrongDestinationForwardResponsesAreFailures() {
        val responses = listOf(
            TdApi.Messages(0, emptyArray()),
            TdApi.Messages(1, arrayOfNulls<TdApi.Message>(1)),
            TdApi.Messages(1, arrayOf(message().apply { chatId = 99 })),
            TdApi.Messages(1, arrayOf(message().apply { chatId = 20; id = 0 })),
            TdApi.Messages(1, arrayOf(message().apply { chatId = 20; content = null })),
        )
        responses.forEach { response ->
            val fixture = Fixture()
            val results = mutableListOf<ChatResult<List<ChatMessage>>>()
            fixture.session.forwardMessage(10, 30, 20, results::add)
            fixture.source(message())
            fixture.last.result(response)
            assertEquals(ChatError.REQUEST_FAILED, results.single().error)
        }
    }

    @Test
    fun unchangedTextAndCaptionAreNoOpsThatLeaveOriginalEntitiesUntouched() {
        for (caption in listOf(false, true)) {
            val originalText = TdApi.FormattedText("private bold text", arrayOf(TdApi.TextEntity(0, 7, TdApi.TextEntityTypeBold())))
            val content: TdApi.MessageContent = if (caption) TdApi.MessagePhoto().apply { this.caption = originalText; showCaptionAboveMedia = true }
                else TdApi.MessageText(originalText, null, TdApi.LinkPreviewOptions())
            val fixture = Fixture()
            val results = mutableListOf<ChatResult<ChatMessage>>()
            fixture.session.editMessage(10, 30, originalText.text, caption, results::add)
            fixture.source(message(content))
            assertEquals(originalText.text, results.single().value?.text)
            assertEquals(2, fixture.requests.size)
            assertIs<TdApi.TextEntityTypeBold>(originalText.entities.single().type)
        }
    }

    @Test
    fun changedTextUsesPlainTextEditingAndReturnsAuthoritativeEditDate() {
        val fixture = Fixture()
        val results = mutableListOf<ChatResult<ChatMessage>>()
        fixture.session.editMessage(10, 30, "private edited", false, results::add)
        fixture.source(message())
        val edit = assertIs<TdApi.EditMessageText>(fixture.last.command)
        assertEquals(10L, edit.chatId)
        assertEquals(30L, edit.messageId)
        assertNull(edit.replyMarkup)
        val content = assertIs<TdApi.InputMessageText>(edit.inputMessageContent)
        assertEquals("private edited", content.text.text)
        assertTrue(content.text.entities.isEmpty())
        assertFalse(content.linkPreviewOptions.isDisabled)
        fixture.last.result(message(textContent("private edited")).apply { editDate = 123 })
        assertEquals(123, results.single().value?.editDate)
        assertEquals("private edited", results.single().value?.text)
        assertFalse(results.toString().contains("private"))
    }

    @Test
    fun captionEditsPreserveAboveMediaPositionAndCanRemoveCaption() {
        val fixture = Fixture()
        val results = mutableListOf<ChatResult<ChatMessage>>()
        fixture.session.editMessage(10, 30, "", true, results::add)
        fixture.source(message(TdApi.MessageVideo().apply { caption = TdApi.FormattedText("private caption", emptyArray()); showCaptionAboveMedia = true }))
        val edit = assertIs<TdApi.EditMessageCaption>(fixture.last.command)
        assertEquals("", edit.caption.text)
        assertTrue(edit.caption.entities.isEmpty())
        assertTrue(edit.showCaptionAboveMedia)
        assertNull(edit.replyMarkup)
        fixture.last.result(message(TdApi.MessageVideo().apply { caption = TdApi.FormattedText("", emptyArray()); showCaptionAboveMedia = true }))
        assertEquals("", results.single().value?.text)
    }

    @Test
    fun freshTypeAndPermissionsMustStillPermitTheChosenEditMode() {
        for ((content, caption, allowed) in listOf(
            Triple<TdApi.MessageContent, Boolean, Boolean>(TdApi.MessageVideoNote(), true, true),
            Triple(TdApi.MessagePhoto(), false, true),
            Triple(textContent("old"), false, false),
        )) {
            val fixture = Fixture()
            val results = mutableListOf<ChatResult<ChatMessage>>()
            fixture.session.editMessage(10, 30, "new", caption, results::add)
            fixture.source(message(content), permissions().apply { canBeEdited = allowed })
            assertEquals(ChatError.FORBIDDEN, results.single().error)
            assertTrue(fixture.requests.none { it.command is TdApi.EditMessageText || it.command is TdApi.EditMessageCaption })
        }
    }

    @Test
    fun invalidIdentifiersAndEditorLimitsDoNotReachTheDispatcher() {
        val fixture = Fixture()
        val errors = mutableListOf<ChatError?>()
        fixture.session.editMessage(10, 30, " ", false) { errors += it.error }
        fixture.session.editMessage(10, 30, "🙂".repeat(4097), false) { errors += it.error }
        fixture.session.editMessage(10, 30, "🙂".repeat(1025), true) { errors += it.error }
        fixture.session.deleteMessage(10, 0, false) { errors += it.error }
        fixture.session.forwardMessage(10, 30, 0) { errors += it.error }
        fixture.session.messageActions(0, 30) { errors += it.error }
        assertTrue(fixture.requests.isEmpty())
        assertEquals(ChatError.EMPTY_TEXT, errors.first())
        assertEquals(ChatError.TEXT_TOO_LONG, errors[1])
        assertEquals(ChatError.TEXT_TOO_LONG, errors[2])
        assertTrue(errors.all { it != null })
    }

    @Test
    fun missingOrMismatchedSourceNeverMutatesAndDuplicateResponsesCannotRepeatDeletion() {
        val fixture = Fixture()
        val results = mutableListOf<ChatResult<Unit>>()
        fixture.session.deleteMessage(10, 30, false, results::add)
        val propertiesRequest = fixture.last
        propertiesRequest.result(permissions())
        val sourceRequest = fixture.last
        sourceRequest.result(TdApi.Error(404, "private missing message"))
        propertiesRequest.result(permissions())
        sourceRequest.result(message())
        assertEquals(2, fixture.requests.size)
        assertEquals(ChatError.REQUEST_FAILED, results.single().error)
        assertFalse(results.toString().contains("private"))
        fixture.session.deleteMessage(10, 30, false, results::add)
        fixture.source(message().apply { id = 31 })
        assertTrue(fixture.requests.none { it.command is TdApi.DeleteMessages })
        fixture.session.deleteMessage(10, 30, false, results::add)
        fixture.source(message())
        val delete = fixture.last
        delete.result(TdApi.Ok())
        delete.error()
        delete.result(TdApi.Ok())
        assertEquals(3, results.size)
        assertEquals(Unit, results.last().value)
        assertEquals(1, fixture.requests.count { it.command is TdApi.DeleteMessages })
    }

    @Test
    fun nativeEditDatesArriveThroughMessagesAndTypedUpdates() {
        assertEquals(123, mapChatMessage(message().apply { editDate = 123 }).editDate)
        val events = mutableListOf<ChatEvent>()
        val mapper = TdChatUpdateMapper(events::add)
        mapper.accept(TdApi.UpdateMessageEdited(10, 30, 124, null))
        assertEquals(listOf(ChatEvent.Edited(10, 30, 124), ChatEvent.ReplyMarkup(10, 30, null)), events)
        assertEquals(0, mapChatMessage(message().apply { editDate = -1 }).editDate)
    }

    @Test
    fun malformedSourceAndMismatchedEditResultAreNeverReportedAsSuccessfulEdits() {
        val fixture = Fixture()
        val results = mutableListOf<ChatResult<ChatMessage>>()
        fixture.session.editMessage(10, 30, "new", false, results::add)
        fixture.source(message().apply { content = null })
        assertEquals(ChatError.REQUEST_FAILED, results.last().error)
        assertTrue(fixture.requests.none { it.command is TdApi.EditMessageText })
        fixture.session.editMessage(10, 30, "new", false, results::add)
        fixture.source(message())
        fixture.last.result(message(TdApi.MessagePhoto()))
        assertEquals(ChatError.REQUEST_FAILED, results.last().error)
        assertTrue(results.all { it.value == null })
        assertEquals(ChatError.FORBIDDEN, classifyChatError(TdApi.Error(400, "MESSAGE_DELETE_FORBIDDEN")))
        assertEquals(ChatError.FORBIDDEN, classifyChatError(TdApi.Error(400, "CHAT_FORWARDS_RESTRICTED")))
    }

    private fun textContent(value: String) = TdApi.MessageText(TdApi.FormattedText(value, emptyArray()), null, null)
    private fun message(content: TdApi.MessageContent = textContent("private original")) = TdApi.Message().apply {
        id = 30; chatId = 10; this.content = content; senderId = TdApi.MessageSenderUser(5)
    }
    private fun permissions() = TdApi.MessageProperties().apply {
        canBeEdited = true; canBeDeletedOnlyForSelf = true; canBeDeletedForAllUsers = true; canBeForwarded = true
    }

    private inner class Fixture {
        val requests = mutableListOf<Request>()
        val session = OfficialTdChatSession { command, result, error -> requests += Request(command, result, error) }
        val last get() = requests.last()
        fun source(message: TdApi.Message, properties: TdApi.MessageProperties = permissions()) {
            val request = assertIs<TdApi.GetMessageProperties>(last.command)
            assertEquals(10L, request.chatId)
            assertEquals(30L, request.messageId)
            last.result(properties)
            val get = assertIs<TdApi.GetMessage>(last.command)
            assertEquals(10L, get.chatId)
            assertEquals(30L, get.messageId)
            last.result(message)
        }
    }
    private class Request(val command: TdApi.Function<*>, val result: (TdApi.Object) -> Unit, val error: () -> Unit)
}
