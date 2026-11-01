package com.badnng.moe.tdlib

import com.badnng.moe.chat.ChatError
import com.badnng.moe.chat.ChatEvent
import com.badnng.moe.chat.ChatMessage
import com.badnng.moe.chat.ChatResult
import com.badnng.moe.chat.MessageEntity
import com.badnng.moe.chat.MessageEntityKind
import com.badnng.moe.chat.MessageLinkPreview
import com.badnng.moe.chat.MessageLinkPreviewOptions
import com.badnng.moe.chat.RichMessageText
import org.drinkless.tdlib.TdApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull

/** Synthetic transport responses only: no JNI, account, network or native client is created. */
class MessagePreviewNativeTest {
    @Test fun ordinaryRichTextSendAllowsNativeLinkPreviewsByDefault() {
        val fixture = Fixture()
        val text = RichMessageText("Example https://example.org", listOf(MessageEntity(0, 7, MessageEntityKind.BOLD)))
        fixture.session.sendRichText(10, text, null) {}
        val send = assertIs<TdApi.SendMessage>(fixture.last.command)
        val input = assertIs<TdApi.InputMessageText>(send.inputMessageContent)
        val options = assertNotNull(input.linkPreviewOptions)
        assertFalse(options.isDisabled)
        assertFalse(options.forceSmallMedia)
        assertFalse(options.forceLargeMedia)
        assertFalse(options.showAboveText)
        assertEquals(text, mapRichText(input.text))
        assertEquals(0L, send.options.paidMessageStarCount)
        assertFalse(send.options.onlyPreview)
    }

    @Test fun richTextEditRechecksTheNativeMessageAndCopiesEveryPreviewOption() {
        for (disabled in listOf(false, true)) for (small in listOf(false, true)) for (large in listOf(false, true)) {
            val above = !disabled
            val originalOptions = TdApi.LinkPreviewOptions(disabled, "https://example.org/chosen", small, large, above)
            val fixture = Fixture()
            fixture.session.editRichMessage(10, 30, RichMessageText("Edited text"), false) {}
            val properties = assertIs<TdApi.GetMessageProperties>(fixture.last.command)
            assertEquals(10L, properties.chatId)
            assertEquals(30L, properties.messageId)
            fixture.last.reply(TdApi.MessageProperties().apply { canBeEdited = true })
            val get = assertIs<TdApi.GetMessage>(fixture.last.command)
            assertEquals(10L, get.chatId)
            assertEquals(30L, get.messageId)
            fixture.last.reply(message(TdApi.MessageText(formatted("Original text"), null, originalOptions)))

            val edit = assertIs<TdApi.EditMessageText>(fixture.last.command)
            val input = assertIs<TdApi.InputMessageText>(edit.inputMessageContent)
            val copied = assertNotNull(input.linkPreviewOptions)
            val expected = MessageLinkPreviewOptions(disabled, "https://example.org/chosen", small, large, above)
            assertEquals(expected, mapLinkPreviewOptions(copied))
            assertEquals(expected, mapLinkPreviewOptions(originalOptions))
            assertNotSame(originalOptions, copied)
            assertEquals("Edited text", input.text.text)
            assertEquals(3, fixture.requests.size)

            copied.isDisabled = !disabled
            copied.url = "https://example.org/other"
            copied.forceSmallMedia = !small
            copied.forceLargeMedia = !large
            copied.showAboveText = !above
            assertEquals(expected, mapLinkPreviewOptions(originalOptions), "A request must not mutate the native snapshot object")
        }
    }

    @Test fun editingAMessageWithoutPreviewOptionsStillAllowsNativePreviews() {
        val fixture = Fixture()
        fixture.session.editRichMessage(10, 30, RichMessageText("Edited text"), false) {}
        fixture.last.reply(TdApi.MessageProperties().apply { canBeEdited = true })
        fixture.last.reply(message(TdApi.MessageText(formatted("Original text"), null, null)))
        val input = assertIs<TdApi.InputMessageText>(assertIs<TdApi.EditMessageText>(fixture.last.command).inputMessageContent)
        assertFalse(assertNotNull(input.linkPreviewOptions).isDisabled)
    }

    @Test fun messageMappingKeepsNativePreviewMetadataAndRichDescriptionWithoutParsingItsTitle() {
        val native = previewContent()
        val mapped = mapChatMessage(message(native))
        assertEquals(expectedOptions(), mapped.linkPreviewOptions)
        assertEquals(expectedPreview(), mapped.linkPreview)
        assertEquals(listOf(MessageEntity(0, 7, MessageEntityKind.BOLD)), mapped.entities)
        assertFalse(mapped.showCaptionAboveMedia)

        native.linkPreviewOptions.url = "https://example.org/mutated"
        native.linkPreview.title = "Changed title"
        native.linkPreview.description.entities[0].offset = 0
        assertEquals(expectedOptions(), mapped.linkPreviewOptions)
        assertEquals(expectedPreview(), mapped.linkPreview, "Native mutable objects must not remain attached to UI snapshots")
    }

    @Test fun messageContentUpdatesKeepTheSamePreviewMetadataAndEntitiesAsInitialMapping() {
        val native = previewContent()
        val initial = mapChatMessage(message(native))
        val events = mutableListOf<ChatEvent>()
        TdChatUpdateMapper(events::add).accept(TdApi.UpdateMessageContent(10, 30, native))
        val update = assertIs<ChatEvent.Content>(events.single())
        assertEquals(10L, update.chatId)
        assertEquals(30L, update.messageId)
        assertEquals(initial.text, update.text)
        assertEquals(initial.entities, update.entities)
        assertEquals(expectedOptions(), update.linkPreviewOptions)
        assertEquals(expectedPreview(), update.linkPreview)
    }

    @Test fun mediaCaptionPositionAndEntitiesSurviveInitialAndUpdatedContent() {
        for (above in listOf(false, true)) {
            val captions: List<TdApi.MessageContent> = listOf(
                TdApi.MessagePhoto().apply { caption = richDescription(); showCaptionAboveMedia = above },
                TdApi.MessageVideo().apply { caption = richDescription(); showCaptionAboveMedia = above },
                TdApi.MessageAnimation().apply { caption = richDescription(); showCaptionAboveMedia = above },
            )
            captions.forEach { content ->
                val mapped = mapChatMessage(message(content))
                assertEquals(above, mapped.showCaptionAboveMedia)
                assertEquals(expectedDescription().text, mapped.text)
                assertEquals(expectedDescription().entities, mapped.entities)
                assertNull(mapped.linkPreview)
                assertNull(mapped.linkPreviewOptions)

                val events = mutableListOf<ChatEvent>()
                TdChatUpdateMapper(events::add).accept(TdApi.UpdateMessageContent(10, 30, content))
                val update = assertIs<ChatEvent.Content>(events.single())
                assertEquals(above, update.showCaptionAboveMedia)
                assertEquals(mapped.entities, update.entities)
                assertNull(update.linkPreview)
                assertNull(update.linkPreviewOptions)
            }
        }
    }

    @Test fun previewRemovalUpdatesExplicitlyCarryEmptyPreviewState() {
        val events = mutableListOf<ChatEvent>()
        val mapper = TdChatUpdateMapper(events::add)
        mapper.accept(TdApi.UpdateMessageContent(10, 30, previewContent()))
        mapper.accept(TdApi.UpdateMessageContent(10, 30, TdApi.MessageText(formatted("Plain text"), null, null)))
        assertNotNull(assertIs<ChatEvent.Content>(events.first()).linkPreview)
        val removed = assertIs<ChatEvent.Content>(events.last())
        assertNull(removed.linkPreview)
        assertNull(removed.linkPreviewOptions)
        assertFalse(removed.showCaptionAboveMedia)
    }

    @Test fun nativeTextLimitUsesTheExactOptionNameAndAcceptsOnlyTheBoundedIntegerContract() {
        for (limit in listOf(1L, 4096L, 65_536L)) {
            val fixture = Fixture()
            val results = mutableListOf<ChatResult<Int>>()
            fixture.session.messageTextLimit(results::add)
            assertEquals("message_text_length_max", assertIs<TdApi.GetOption>(fixture.last.command).name)
            fixture.last.reply(TdApi.OptionValueInteger(limit))
            assertEquals(limit.toInt(), results.single().value)
            assertNull(results.single().error)
        }
        val invalid: List<TdApi.Object> = listOf(
            TdApi.OptionValueInteger(0), TdApi.OptionValueInteger(-1), TdApi.OptionValueInteger(65_537),
            TdApi.OptionValueInteger(Long.MAX_VALUE), TdApi.OptionValueString("4096"), TdApi.OptionValueEmpty(),
        )
        invalid.forEach { response ->
            val fixture = Fixture()
            val results = mutableListOf<ChatResult<Int>>()
            fixture.session.messageTextLimit(results::add)
            fixture.last.reply(response)
            assertEquals(ChatError.REQUEST_FAILED, results.single().error)
            assertNull(results.single().value)
        }
    }

    @Test fun nativeTextLimitErrorsRemainErrorsAndTransportCancellationCompletesOnce() {
        val fixture = Fixture()
        val results = mutableListOf<ChatResult<Int>>()
        fixture.session.messageTextLimit(results::add)
        fixture.last.reply(TdApi.Error(403, "synthetic permission error"))
        fixture.last.reply(TdApi.OptionValueInteger(4096))
        fixture.last.cancel()
        assertEquals(ChatError.FORBIDDEN, results.single().error)
        assertEquals(403, results.single().code)
        assertNull(results.single().value)

        val cancelled = Fixture()
        val cancelledResults = mutableListOf<ChatResult<Int>>()
        cancelled.session.messageTextLimit(cancelledResults::add)
        cancelled.last.cancel()
        assertEquals(ChatError.REQUEST_FAILED, cancelledResults.single().error)
    }

    @Test fun applicationConfigExplicitEmptyWhitelistIsAnAuthoritativeEmptySet() {
        val fixture = Fixture()
        val results = mutableListOf<ChatResult<Set<String>>>()
        fixture.session.linkConfirmationDomains(results::add)
        assertIs<TdApi.GetApplicationConfig>(fixture.last.command)
        fixture.last.reply(config(TdApi.JsonValueArray(emptyArray())))
        assertEquals(emptySet(), results.single().value)
        assertNull(results.single().error)

        val valid = Fixture()
        val validResults = mutableListOf<ChatResult<Set<String>>>()
        valid.session.linkConfirmationDomains(validResults::add)
        valid.last.reply(config(TdApi.JsonValueArray(arrayOf(
            TdApi.JsonValueString("example.org"), TdApi.JsonValueString("docs.example.org"), TdApi.JsonValueString("example.org"),
        ))))
        assertEquals(setOf("example.org", "docs.example.org"), validResults.single().value)
    }

    @Test fun malformedOrUnavailableConfigReturnsAnErrorForTheControllersFallback() {
        val invalid: List<TdApi.Object> = listOf(
            TdApi.Error(500, "synthetic config failure"),
            TdApi.JsonValueObject(emptyArray()),
            TdApi.JsonValueObject(arrayOf(TdApi.JsonObjectMember("unrelated_domains", TdApi.JsonValueArray(emptyArray())))),
            config(TdApi.JsonValueString("example.org")),
            config(TdApi.JsonValueArray(arrayOf(TdApi.JsonValueString("example.org"), TdApi.JsonValueNull()))),
            config(TdApi.JsonValueArray().apply { values = null }),
            config(TdApi.JsonValueArray(arrayOf(TdApi.JsonValueString().apply { value = null }))),
            config(TdApi.JsonValueArray(arrayOf(TdApi.JsonValueString("x".repeat(254))))),
            config(TdApi.JsonValueArray(Array(4097) { TdApi.JsonValueString("example.org") })),
        )
        invalid.forEach { response ->
            val fixture = Fixture()
            val results = mutableListOf<ChatResult<Set<String>>>()
            fixture.session.linkConfirmationDomains(results::add)
            fixture.last.reply(response)
            assertEquals(ChatError.REQUEST_FAILED, results.single().error)
            assertNull(results.single().value, "Invalid config must not masquerade as an explicit empty whitelist")
        }
    }

    private fun message(content: TdApi.MessageContent) = TdApi.Message().apply {
        id = 30
        chatId = 10
        isOutgoing = true
        this.content = content
    }

    private fun previewContent() = TdApi.MessageText(
        TdApi.FormattedText("Example https://example.org", arrayOf(TdApi.TextEntity(0, 7, TdApi.TextEntityTypeBold()))),
        TdApi.LinkPreview().apply {
            url = "https://example.org/chosen"
            displayUrl = "example.org/chosen"
            siteName = "Example site"
            title = "**Native title**"
            description = richDescription()
            showAboveText = true
        },
        TdApi.LinkPreviewOptions(true, "https://example.org/chosen", true, false, true),
    )

    private fun richDescription() = TdApi.FormattedText("🙂链接", arrayOf(
        TdApi.TextEntity(2, 2, TdApi.TextEntityTypeTextUrl("https://example.org/description")),
        TdApi.TextEntity(2, 2, TdApi.TextEntityTypeBold()),
    ))

    private fun expectedDescription() = RichMessageText("🙂链接", listOf(
        MessageEntity(2, 2, MessageEntityKind.TEXT_URL, "https://example.org/description"),
        MessageEntity(2, 2, MessageEntityKind.BOLD),
    ))

    private fun expectedOptions() = MessageLinkPreviewOptions(true, "https://example.org/chosen", true, false, true)

    private fun expectedPreview() = MessageLinkPreview(
        "https://example.org/chosen", "example.org/chosen", "Example site", "**Native title**", expectedDescription(), true,
    )

    private fun formatted(text: String) = TdApi.FormattedText(text, emptyArray())

    private fun config(value: TdApi.JsonValue) = TdApi.JsonValueObject(arrayOf(TdApi.JsonObjectMember("whitelisted_domains", value)))

    private class Fixture {
        val requests = mutableListOf<Request>()
        val session = OfficialTdChatSession { command, reply, cancel -> requests += Request(command, reply, cancel) }
        val last: Request get() = requests.last()
    }

    private class Request(val command: TdApi.Function<*>, val reply: (TdApi.Object) -> Unit, val cancel: () -> Unit)
}
