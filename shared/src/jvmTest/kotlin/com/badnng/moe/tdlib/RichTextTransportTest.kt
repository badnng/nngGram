package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import org.drinkless.tdlib.TdApi
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

class RichTextTransportTest {
    @Test fun textCaptionsAndUpdatesKeepNativeEntities() {
        val text = TdApi.FormattedText("🙂链接", arrayOf(TdApi.TextEntity(2, 2, TdApi.TextEntityTypeTextUrl("https://example.org")),
            TdApi.TextEntity(2, 2, TdApi.TextEntityTypeBold()), TdApi.TextEntity(1, 1, TdApi.TextEntityTypeItalic())))
        val message = message(text)
        assertEquals(2, mapChatMessage(message).entities.size)
        message.content = TdApi.MessagePhoto().apply { caption = text }
        assertEquals(2, mapChatMessage(message).entities.size)
        val events = mutableListOf<ChatEvent>()
        TdChatUpdateMapper(events::add).accept(TdApi.UpdateMessageContent(1, 2, message.content))
        assertEquals(2, assertIs<ChatEvent.Content>(events.single()).entities.size)
    }

    @Test fun formattedTextIsSentWithEntitiesAndNoPaidSend() {
        val fixture = Fixture()
        fixture.session.sendRichText(1, RichMessageText("中文链接", listOf(MessageEntity(0, 2, MessageEntityKind.BOLD),
            MessageEntity(2, 2, MessageEntityKind.TEXT_URL, "https://example.org"))), null) {}
        val command = assertIs<TdApi.SendMessage>(fixture.last.command)
        val text = assertIs<TdApi.InputMessageText>(command.inputMessageContent).text
        assertEquals(2, text.entities.size)
        assertIs<TdApi.TextEntityTypeBold>(text.entities.first().type)
        assertEquals(0L, command.options.paidMessageStarCount)
    }

    @Test fun quoteChecksNativePermissionsAndOriginalThenUsesServerLengthLimit() {
        val fixture = Fixture()
        val quote = MessageQuote(3, RichMessageText("引用"))
        fixture.session.sendRichText(1, RichMessageText("答复"), OutgoingReply(2, quote)) {}
        assertIs<TdApi.GetMessageProperties>(fixture.last.command)
        fixture.last.reply(TdApi.MessageProperties().apply { canBeReplied = true })
        assertIs<TdApi.GetMessage>(fixture.last.command)
        fixture.last.reply(message(TdApi.FormattedText("🙂 引用 后文", arrayOf(TdApi.TextEntity(3, 2, TdApi.TextEntityTypeBold()),
            TdApi.TextEntity(3, 2, TdApi.TextEntityTypeTextUrl("https://example.org"))))))
        assertIs<TdApi.GetOption>(fixture.last.command)
        fixture.last.reply(TdApi.OptionValueInteger(1024))
        val reply = assertIs<TdApi.InputMessageReplyToMessage>(assertIs<TdApi.SendMessage>(fixture.last.command).replyTo)
        assertEquals(3, reply.quote.position)
        assertEquals("引用", reply.quote.text.text)
        assertIs<TdApi.TextEntityTypeBold>(reply.quote.text.entities.single().type)
        assertEquals(0, reply.quote.text.entities.single().offset)
    }

    @Test fun changedQuoteAndRevokedPermissionNeverSend() {
        listOf(false, true).forEach { allowed ->
            val fixture = Fixture()
            var result: ChatResult<ChatMessage>? = null
            fixture.session.sendRichText(1, RichMessageText("答复"), OutgoingReply(2, MessageQuote(0, RichMessageText("原文")))) { result = it }
            fixture.last.reply(TdApi.MessageProperties().apply { canBeReplied = allowed })
            fixture.last.reply(message(TdApi.FormattedText("已修改", emptyArray())))
            assertEquals(if (allowed) ChatError.INVALID_QUOTE else ChatError.FORBIDDEN, result?.error)
            assertTrue(fixture.requests.none { it.command is TdApi.SendMessage })
        }
    }

    @Test fun sparseVideoBytesAreReadOnlyAfterTdlibConfirmsTheRequestedPrefix() {
        val availablePrefix = AtomicLong(0)
        val fixture = Fixture(availablePrefix)
        try {
            var result: ChatResult<ByteArray>? = null
            fixture.session.readVideoPart(7, 1000, 64) { result = it }
            val downloadRequest = fixture.requests.single { it.command is TdApi.DownloadFile }
            val download = assertIs<TdApi.DownloadFile>(downloadRequest.command)
            assertEquals(1000L, download.offset)
            assertEquals(32, download.priority)
            assertEquals(TdDownloadBroker.STREAM_WINDOW, download.limit)
            assertEquals(ChatError.NOT_READY, result?.error)
            downloadRequest.reply(TdApi.File().apply { id = 7 })
            assertTrue(fixture.requests.none { it.command is TdApi.ReadFilePart }, "False acceptance still authorizes zero sparse reads.")
            availablePrefix.set(12)
            fixture.session.readVideoPart(7, 1000, 64) { result = it }
            val readRequest = fixture.requests.single { it.command is TdApi.ReadFilePart }
            val read = assertIs<TdApi.ReadFilePart>(readRequest.command)
            assertEquals(7, read.fileId)
            assertEquals(1000L, read.offset)
            assertEquals(12L, read.count)
            val readEvent = fixture.prefixEvents.indexOf("read:12")
            assertTrue(readEvent > 0)
            assertEquals("prefix:12", fixture.prefixEvents[readEvent - 1], "The exact-offset native positive prefix must precede ReadFilePart.")
            readRequest.reply(TdApi.Data(ByteArray(12) { it.toByte() }))
            assertEquals(12, result?.value?.size)
            assertEquals(1, fixture.requests.count { it.command is TdApi.DownloadFile }, "Prefix polls and cached reads must not restart the native window.")
        } finally { fixture.session.retireDownloads() }
    }

    @Test fun photoAndDocumentCaptionsTransmitRichEntities() {
        val content = RichMessageText("说明 链接", listOf(MessageEntity(0, 2, MessageEntityKind.BOLD),
            MessageEntity(3, 2, MessageEntityKind.TEXT_URL, "https://example.org")))
        val photo = Fixture()
        photo.session.sendPhotoReply(1, "C:/synthetic/photo.png", content, null) {}
        val photoInput = assertIs<TdApi.InputMessagePhoto>(assertIs<TdApi.SendMessage>(photo.last.command).inputMessageContent)
        assertEquals(2, photoInput.caption.entities.size)
        val document = Fixture()
        document.session.sendAttachmentReply(1, LocalAttachment(AttachmentKind.FILE, "C:/synthetic/file.txt", "file.txt", 10), content, null) {}
        val fileInput = assertIs<TdApi.InputMessageDocument>(assertIs<TdApi.SendMessage>(document.last.command).inputMessageContent)
        assertEquals(2, fileInput.caption.entities.size)
        assertIs<TdApi.TextEntityTypeTextUrl>(fileInput.caption.entities[1].type)
    }

    @Test fun formattingOnlyEditReachesNativeAndUnchangedFormattingDoesNotStripEntities() {
        val fixture = Fixture()
        val original = message(TdApi.FormattedText("文字", arrayOf(TdApi.TextEntity(0, 2, TdApi.TextEntityTypeBold()))))
        fixture.session.editRichMessage(1, 2, RichMessageText("文字"), false) {}
        fixture.last.reply(TdApi.MessageProperties().apply { canBeEdited = true })
        fixture.last.reply(original)
        assertIs<TdApi.EditMessageText>(fixture.last.command)
        val second = Fixture()
        second.session.editRichMessage(1, 2, mapRichText(assertIs<TdApi.MessageText>(original.content).text), false) {}
        second.last.reply(TdApi.MessageProperties().apply { canBeEdited = true })
        second.last.reply(original)
        assertTrue(second.requests.none { it.command is TdApi.EditMessageText })
    }

    private fun message(text: TdApi.FormattedText) = TdApi.Message().apply {
        id = 2; chatId = 1; isOutgoing = true; content = TdApi.MessageText(text, null, null)
    }
    private class Fixture(private val availablePrefix: AtomicLong? = null) {
        val requests = CopyOnWriteArrayList<Request>()
        val prefixEvents = CopyOnWriteArrayList<String>()
        val session = OfficialTdChatSession { command, reply, _ ->
            requests.add(Request(command, reply))
            if (command is TdApi.GetFileDownloadedPrefixSize && availablePrefix != null) {
                assertEquals(7, command.fileId)
                assertEquals(1000L, command.offset)
                val bytes = availablePrefix.get()
                prefixEvents += "prefix:$bytes"
                reply(TdApi.FileDownloadedPrefixSize(bytes))
            } else if (command is TdApi.ReadFilePart) prefixEvents += "read:${command.count}"
        }
        val last get() = requests.last()
    }
    private class Request(val command: TdApi.Function<*>, val reply: (TdApi.Object) -> Unit)
}
