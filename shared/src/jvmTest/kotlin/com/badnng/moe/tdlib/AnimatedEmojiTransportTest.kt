package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import org.drinkless.tdlib.TdApi
import kotlin.test.*

/** Synthetic TDLib objects and callbacks only; no account, JNI, audio or media download. */
class AnimatedEmojiTransportTest {
    @Test fun nativeAnimatedEmojiRetainsTextStickerFormatAndSeparatePresentationMetadata() {
        val formats = listOf(
            TdApi.StickerFormatWebp() to MediaFormat.WEBP,
            TdApi.StickerFormatTgs() to MediaFormat.TGS,
            TdApi.StickerFormatWebm() to MediaFormat.WEBM,
        )
        for ((nativeFormat, expectedFormat) in formats) for (regular in listOf(false, true)) {
            val content = animatedEmoji(nativeFormat).apply { animatedEmoji.isRegularEmoji = regular }
            val mapped = mapChatMessage(message(content))
            assertEquals(MessageKind.ANIMATED_EMOJI, mapped.kind)
            assertEquals(EMOJI, mapped.text, "Use the message emoji, never the sticker's fallback label")
            val media = assertNotNull(mapped.media)
            assertEquals(expectedFormat, media.format)
            assertEquals(7, media.file.id)
            assertEquals(8, media.thumbnail?.id)
            val presentation = assertNotNull(media.animatedEmoji)
            assertEquals(224, presentation.stickerWidth)
            assertEquals(192, presentation.stickerHeight)
            assertEquals(5, presentation.fitzpatrickType)
            assertEquals(regular, presentation.isRegularEmoji)
            assertNotEquals(content.animatedEmoji.sound.id, media.file.id)
            assertNotEquals(content.animatedEmoji.sound.id, media.thumbnail?.id)
            content.animatedEmoji.sound = file(199)
            assertEquals(mapped, mapChatMessage(message(content)), "Sound files do not belong to picture snapshots")
            content.animatedEmoji.sound = null
            assertEquals(mapped, mapChatMessage(message(content)))
        }
    }

    @Test fun customIdentityComesOnlyFromTheNativeFullTypeAndCoversTheWholeUtf16Emoji() {
        for (customId in listOf(771L, -771L)) {
            val content = animatedEmoji().apply {
                animatedEmoji.sticker.id = 1234
                animatedEmoji.sticker.fullType = TdApi.StickerFullTypeCustomEmoji(customId, false)
            }
            assertEquals(listOf(MessageEntity(0, EMOJI.length, MessageEntityKind.CUSTOM_EMOJI, id = customId)),
                mapChatMessage(message(content)).entities)
            val formatted = assertNotNull(formattedContent(content))
            assertEquals(EMOJI, formatted.text)
            val entity = formatted.entities.single()
            assertEquals(0, entity.offset)
            assertEquals(EMOJI.length, entity.length)
            assertEquals(customId, assertIs<TdApi.TextEntityTypeCustomEmoji>(entity.type).customEmojiId)
        }
        for (fullType in listOf(null, TdApi.StickerFullTypeRegular(), TdApi.StickerFullTypeCustomEmoji(0, false))) {
            val content = animatedEmoji().apply { animatedEmoji.sticker.fullType = fullType }
            assertTrue(mapChatMessage(message(content)).entities.isEmpty(), "Sticker id and set id never authorize a custom emoji identity")
        }
    }

    @Test fun missingOrUnsupportedArtworkKeepsTheNativeEmojiTextWithoutInventingMedia() {
        val cases = listOf(
            TdApi.MessageAnimatedEmoji(null, EMOJI),
            animatedEmoji().apply { animatedEmoji.sticker = null },
            animatedEmoji().apply { animatedEmoji.sticker.sticker = null },
            animatedEmoji().apply { animatedEmoji.sticker.format = null },
            animatedEmoji().apply { animatedEmoji.sticker.sticker.id = 0 },
            animatedEmoji().apply { animatedEmoji.sticker.sticker.id = -7 },
        )
        cases.forEach { content ->
            val mapped = mapChatMessage(message(content))
            assertEquals(MessageKind.ANIMATED_EMOJI, mapped.kind)
            assertEquals(EMOJI, mapped.text)
            assertNull(mapped.media)
        }
    }

    @Test fun nativeAnimatedEmojiArtworkAcceptsOnlyKnownFormatsAndPositiveFileIds() {
        for (format in listOf(TdApi.StickerFormatWebp(), TdApi.StickerFormatTgs(), TdApi.StickerFormatWebm())) {
            val content = animatedEmoji(format)
            assertEquals(7, assertNotNull(mapMessageMedia(content)).file.id)
            for (invalid in listOf(0, -1)) {
                content.animatedEmoji.sticker.sticker.id = invalid
                assertNull(mapMessageMedia(content))
            }
        }
    }

    @Test fun historyAndNewMessageMappingRetainAnimatedEmojiWithoutRequestingItsSound() {
        val fixture = Fixture()
        val results = mutableListOf<ChatResult<List<ChatMessage>>>()
        fixture.session.history(10, 0, 20, results::add)
        assertIs<TdApi.GetChatHistory>(fixture.last.command)
        val native = message(animatedEmoji())
        fixture.last.reply(TdApi.Messages(1, arrayOf(native)))
        val history = assertNotNull(results.single().value).single()
        assertEquals(MessageKind.ANIMATED_EMOJI, history.kind)
        assertEquals(EMOJI, history.text)
        assertEquals(CUSTOM_ID, history.entities.single().id)
        assertEquals(7, history.media?.file?.id)
        assertEquals(1, fixture.requests.size, "Mapping an emoji or its sound must not start a download")

        val events = mutableListOf<ChatEvent>()
        TdChatUpdateMapper(events::add).accept(TdApi.UpdateNewMessage(native))
        val arrival = assertIs<ChatEvent.NewMessage>(events.single()).message
        assertEquals(history, arrival)
        assertTrue(fixture.requests.none { it.command is TdApi.DownloadFile })
    }

    @Test fun nativeContentUpdatesCanConvertTextToAnimatedEmojiAndBackWithoutStaleArtworkOrEntities() {
        val events = mutableListOf<ChatEvent>()
        val mapper = TdChatUpdateMapper(events::add)
        mapper.accept(TdApi.UpdateMessageContent(10, 30, animatedEmoji()))
        val emoji = assertIs<ChatEvent.Content>(events.single())
        assertEquals(10L, emoji.chatId)
        assertEquals(30L, emoji.messageId)
        assertEquals(MessageKind.ANIMATED_EMOJI, emoji.kind)
        assertEquals(EMOJI, emoji.text)
        assertEquals(CUSTOM_ID, emoji.entities.single().id)
        assertNotNull(emoji.media?.animatedEmoji)

        mapper.accept(TdApi.UpdateMessageContent(10, 30, textContent("Plain again")))
        val text = assertIs<ChatEvent.Content>(events.last())
        assertEquals(MessageKind.TEXT, text.kind)
        assertEquals("Plain again", text.text)
        assertTrue(text.entities.isEmpty())
        assertNull(text.media)

        mapper.accept(TdApi.UpdateMessageContent(10, 30, animatedEmoji().apply { animatedEmoji.sticker.sticker = file(70) }))
        val replacement = assertIs<ChatEvent.Content>(events.last())
        assertEquals(MessageKind.ANIMATED_EMOJI, replacement.kind)
        assertEquals(70, replacement.media?.file?.id)
    }

    @Test fun nativeSendAcceptanceMayResolveAnOrdinaryEmojiTextIntoAnimatedEmoji() {
        val fixture = Fixture()
        val results = mutableListOf<ChatResult<ChatMessage>>()
        fixture.session.sendRichText(10, RichMessageText(EMOJI), null, results::add)
        val send = assertIs<TdApi.SendMessage>(fixture.last.command)
        assertEquals(EMOJI, assertIs<TdApi.InputMessageText>(send.inputMessageContent).text.text)
        fixture.last.reply(message(animatedEmoji()))
        val accepted = assertNotNull(results.single().value)
        assertNull(results.single().error)
        assertEquals(MessageKind.ANIMATED_EMOJI, accepted.kind)
        assertEquals(CUSTOM_ID, accepted.entities.single().id)
        assertEquals(1, fixture.requests.size)
    }

    @Test fun animatedEmojiEditActionsRequireCurrentNativePermissionAndCompletedDelivery() {
        for ((allowed, pending, expected) in listOf(Triple(true, false, true), Triple(false, false, false), Triple(true, true, false))) {
            val fixture = Fixture()
            val results = mutableListOf<ChatResult<MessageActions>>()
            fixture.session.messageActions(10, 30, results::add)
            fixture.source(message(animatedEmoji()).apply { if (pending) sendingState = TdApi.MessageSendingStatePending() }, allowed)
            val actions = assertNotNull(results.single().value)
            assertEquals(expected, actions.canEditText)
            assertFalse(actions.canEditCaption)
        }
    }

    @Test fun unchangedEmojiEditsPreserveTheOriginalCustomEntityAndDoNotSubmitAnEdit() {
        for (rich in listOf(false, true)) {
            val fixture = Fixture()
            val results = mutableListOf<ChatResult<ChatMessage>>()
            if (rich) fixture.session.editRichMessage(10, 30, richEmoji(), false, results::add)
            else fixture.session.editMessage(10, 30, EMOJI, false, results::add)
            fixture.source(message(animatedEmoji()))
            val unchanged = assertNotNull(results.single().value)
            assertEquals(MessageKind.ANIMATED_EMOJI, unchanged.kind)
            assertEquals(richEmoji().entities, unchanged.entities)
            assertEquals(2, fixture.requests.size)
            assertTrue(fixture.requests.none { it.command is TdApi.EditMessageText })
        }
    }

    @Test fun editingPlainTextIntoEmojiAcceptsTheNativeAnimatedContentResponse() {
        val fixture = Fixture()
        val results = mutableListOf<ChatResult<ChatMessage>>()
        fixture.session.editRichMessage(10, 30, RichMessageText(EMOJI), false, results::add)
        fixture.source(message(textContent("Before")))
        val edit = assertIs<TdApi.EditMessageText>(fixture.last.command)
        assertEquals(EMOJI, assertIs<TdApi.InputMessageText>(edit.inputMessageContent).text.text)
        fixture.last.reply(message(animatedEmoji()))
        assertNull(results.single().error)
        assertEquals(MessageKind.ANIMATED_EMOJI, results.single().value?.kind)
        assertEquals(richEmoji().entities, results.single().value?.entities)
    }

    @Test fun editingAnimatedEmojiBackToTextAcceptsTheNativeTextResponse() {
        val fixture = Fixture()
        val results = mutableListOf<ChatResult<ChatMessage>>()
        fixture.session.editRichMessage(10, 30, RichMessageText("After"), false, results::add)
        fixture.source(message(animatedEmoji()))
        val edit = assertIs<TdApi.EditMessageText>(fixture.last.command)
        assertEquals("After", assertIs<TdApi.InputMessageText>(edit.inputMessageContent).text.text)
        fixture.last.reply(message(textContent("After")))
        val edited = assertNotNull(results.single().value)
        assertNull(results.single().error)
        assertEquals(MessageKind.TEXT, edited.kind)
        assertEquals("After", edited.text)
        assertTrue(edited.entities.isEmpty())
        assertNull(edited.media)
    }

    @Test fun quotePreflightUsesTheOriginalEmojiAndItsRealCustomEntity() {
        val fixture = Fixture()
        fixture.session.sendRichText(10, RichMessageText("Reply"), OutgoingReply(30, MessageQuote(0, richEmoji()))) {}
        fixture.source(message(animatedEmoji()), canReply = true)
        assertEquals("message_reply_quote_length_max", assertIs<TdApi.GetOption>(fixture.last.command).name)
        fixture.last.reply(TdApi.OptionValueInteger(1024))
        val send = assertIs<TdApi.SendMessage>(fixture.last.command)
        val reply = assertIs<TdApi.InputMessageReplyToMessage>(send.replyTo)
        assertEquals(30L, reply.messageId)
        val quote = assertNotNull(reply.quote)
        assertEquals(0, quote.position)
        assertEquals(EMOJI, quote.text.text)
        val entity = quote.text.entities.single()
        assertEquals(0, entity.offset)
        assertEquals(EMOJI.length, entity.length)
        assertEquals(CUSTOM_ID, assertIs<TdApi.TextEntityTypeCustomEmoji>(entity.type).customEmojiId)
        assertTrue(fixture.requests.none { it.command is TdApi.DownloadFile })
    }

    private fun richEmoji() = RichMessageText(EMOJI, listOf(MessageEntity(0, EMOJI.length, MessageEntityKind.CUSTOM_EMOJI, id = CUSTOM_ID)))

    private fun animatedEmoji(format: TdApi.StickerFormat = TdApi.StickerFormatTgs()) = TdApi.MessageAnimatedEmoji(
        TdApi.AnimatedEmoji().apply {
            sticker = TdApi.Sticker().apply {
                id = 1234
                setId = 321
                width = 512
                height = 512
                emoji = "fallback label"
                this.format = format
                fullType = TdApi.StickerFullTypeCustomEmoji(CUSTOM_ID, false)
                sticker = file(7)
                thumbnail = TdApi.Thumbnail(TdApi.ThumbnailFormatWebp(), 128, 128, file(8))
            }
            stickerWidth = 224
            stickerHeight = 192
            fitzpatrickType = 5
            isRegularEmoji = true
            sound = file(99)
        },
        EMOJI,
    )

    private fun file(fileId: Int) = TdApi.File().apply {
        id = fileId
        size = 1024
        expectedSize = 1024
        local = TdApi.LocalFile().apply { canBeDownloaded = true }
    }

    private fun textContent(text: String) = TdApi.MessageText(TdApi.FormattedText(text, emptyArray()), null, null)

    private fun message(content: TdApi.MessageContent) = TdApi.Message().apply {
        id = 30
        chatId = 10
        isOutgoing = true
        this.content = content
    }

    private class Fixture {
        val requests = mutableListOf<Request>()
        val session = OfficialTdChatSession { command, reply, _ -> requests += Request(command, reply) }
        val last: Request get() = requests.last()

        fun source(message: TdApi.Message, canEdit: Boolean = true, canReply: Boolean = true) {
            val properties = assertIs<TdApi.GetMessageProperties>(last.command)
            assertEquals(10L, properties.chatId)
            assertEquals(30L, properties.messageId)
            last.reply(TdApi.MessageProperties().apply { canBeEdited = canEdit; canBeReplied = canReply })
            val get = assertIs<TdApi.GetMessage>(last.command)
            assertEquals(10L, get.chatId)
            assertEquals(30L, get.messageId)
            last.reply(message)
        }
    }

    private class Request(val command: TdApi.Function<*>, val reply: (TdApi.Object) -> Unit)

    private companion object {
        const val EMOJI = "👩🏽‍💻"
        const val CUSTOM_ID = 771L
    }
}
