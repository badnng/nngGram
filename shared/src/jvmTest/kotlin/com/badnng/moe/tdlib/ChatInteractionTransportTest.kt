package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import org.drinkless.tdlib.TdApi
import com.badnng.moe.media.AttachmentFiles
import java.nio.file.Files
import kotlin.test.*

/** Synthetic dispatcher tests: no client, native library, account or network is used. */
class ChatInteractionTransportTest {
    @Test
    fun videoAndDocumentKeepFileMetadataAndSecretVideoHasNoDownloadableMedia() {
        val video = TdApi.MessageVideo().apply {
            this.video = TdApi.Video().apply {
                duration = 12; width = 1280; height = 720
                fileName = "private clip.mp4"; mimeType = "video/mp4"
                this.video = file(8)
                thumbnail = TdApi.Thumbnail(TdApi.ThumbnailFormatJpeg(), 320, 180, file(9))
            }
            hasSpoiler = true
        }
        val mapped = assertNotNull(mapMessageMedia(video))
        assertEquals(MediaFormat.VIDEO, mapped.format)
        assertEquals("private clip.mp4", mapped.fileName)
        assertEquals("video/mp4", mapped.mimeType)
        assertEquals(12, mapped.durationSeconds)
        assertEquals(1280, mapped.width)
        assertEquals(720, mapped.height)
        assertEquals(1024L, mapped.file.size)
        assertEquals(9, mapped.thumbnail?.id)
        assertEquals(MediaFormat.PHOTO, mapped.thumbnailFormat)
        assertTrue(mapped.hasSpoiler)
        video.isSecret = true
        assertNull(mapMessageMedia(video))
        val document = TdApi.MessageDocument().apply {
            this.document = TdApi.Document().apply {
                fileName = "private report.pdf"; mimeType = "application/pdf"; this.document = file(10)
                thumbnail = TdApi.Thumbnail(TdApi.ThumbnailFormatPng(), 100, 100, file(11))
            }
        }
        val mappedDocument = assertNotNull(mapMessageMedia(document))
        assertEquals(MediaFormat.FILE, mappedDocument.format)
        assertEquals("private report.pdf", mappedDocument.fileName)
        assertEquals("application/pdf", mappedDocument.mimeType)
        assertEquals(11, mappedDocument.thumbnail?.id)
        assertFalse(mappedDocument.toString().contains("private"))
    }

    @Test
    fun everySendKindUsesAnActualSameChatReplyWithoutPayments() {
        val fixture = Fixture()
        val sticker = StickerItem(8, 9, "🙂", MessageMedia(MediaFile(8), format = MediaFormat.WEBP))
        val actions: List<() -> Unit> = listOf(
            { fixture.session.sendText(10, "private text", 30) { } },
            { fixture.session.sendPhoto(10, "C:/synthetic/photo.jpg", "private caption", 30) { } },
            { fixture.session.sendSticker(10, sticker, 30) { } },
            { fixture.session.sendAttachment(10, LocalAttachment(AttachmentKind.VIDEO, "C:/synthetic/clip.mp4", "clip.mp4", 1024, 5, 640, 480), "private caption", 30) { } },
            { fixture.session.sendAttachment(10, LocalAttachment(AttachmentKind.FILE, "C:/synthetic/report.pdf", "report.pdf", 1024), "private caption", 30) { } },
        )
        actions.forEach { action ->
            action()
            val send = assertIs<TdApi.SendMessage>(fixture.last.command)
            val reply = assertIs<TdApi.InputMessageReplyToMessage>(send.replyTo)
            assertEquals(30L, reply.messageId)
            assertNull(reply.quote)
            assertEquals(0, reply.checklistTaskId)
            assertEquals("", reply.pollOptionId)
            assertEquals(0L, send.options.paidMessageStarCount)
            assertFalse(send.options.allowPaidBroadcast)
            assertFalse(send.options.onlyPreview)
            assertNull(send.replyMarkup)
        }
        val video = assertIs<TdApi.InputMessageVideo>(assertIs<TdApi.SendMessage>(fixture.requests[3].command).inputMessageContent)
        assertEquals("C:/synthetic/clip.mp4", assertIs<TdApi.InputFileLocal>(video.video.video).path)
        assertEquals(5, video.video.duration)
        assertEquals(640, video.video.width)
        assertEquals(480, video.video.height)
        assertFalse(video.video.supportsStreaming)
        assertNull(video.selfDestructType)
        val document = assertIs<TdApi.InputMessageDocument>(assertIs<TdApi.SendMessage>(fixture.requests[4].command).inputMessageContent)
        assertEquals("C:/synthetic/report.pdf", assertIs<TdApi.InputFileLocal>(document.document.document).path)
        assertTrue(document.document.disableContentTypeDetection)
    }

    @Test
    fun invalidReplyAndUnvalidatedVideoNeverReachTheDispatcher() {
        val fixture = Fixture()
        val results = mutableListOf<ChatResult<ChatMessage>>()
        fixture.session.sendText(10, "private text", -1, results::add)
        fixture.session.sendAttachment(10, LocalAttachment(AttachmentKind.VIDEO, "C:/synthetic/a.mp4", "a.mp4", 1), "", null, results::add)
        fixture.session.sendAttachment(10, LocalAttachment(AttachmentKind.FILE, "", "a.pdf", 1), "", null, results::add)
        fixture.session.sendAttachment(10, LocalAttachment(AttachmentKind.FILE, "C:/synthetic/a.pdf", "a.pdf", 1), "a".repeat(1025), null, results::add)
        assertTrue(fixture.requests.isEmpty())
        assertEquals(4, results.size)
        assertTrue(results.all { it.error != null })
    }

    @Test
    fun preparedVideoPassesItsActualThumbnailStreamingAndRichCaptionToNative() {
        val fixture = Fixture()
        val attachment = LocalAttachment(AttachmentKind.VIDEO, "C:/synthetic/clip.mp4", "clip.mp4", 1024, 5, 640, 360,
            supportsStreaming = true, thumbnail = LocalAttachmentThumbnail("C:/synthetic/clip-thumb.jpg", 320, 180, 10_000))
        val caption = RichMessageText("native caption", listOf(MessageEntity(0, 6, MessageEntityKind.BOLD)))
        fixture.session.sendAttachmentReply(10, attachment, caption, OutgoingReply(30)) {}
        val send = assertIs<TdApi.SendMessage>(fixture.last.command)
        val content = assertIs<TdApi.InputMessageVideo>(send.inputMessageContent)
        assertTrue(content.video.supportsStreaming)
        val thumbnail = assertNotNull(content.video.thumbnail)
        assertEquals("C:/synthetic/clip-thumb.jpg", assertIs<TdApi.InputFileLocal>(thumbnail.thumbnail).path)
        assertEquals(320, thumbnail.width)
        assertEquals(180, thumbnail.height)
        assertEquals(640, content.video.width)
        assertEquals(360, content.video.height)
        assertEquals(5, content.video.duration)
        assertEquals(caption.text, content.caption.text)
        assertIs<TdApi.TextEntityTypeBold>(content.caption.entities.single().type)
        assertEquals(30L, assertIs<TdApi.InputMessageReplyToMessage>(send.replyTo).messageId)
        assertFalse(content.hasSpoiler)
        assertFalse(content.showCaptionAboveMedia)
        assertNull(content.video.cover)
    }

    @Test
    fun invalidThumbnailGeometryOrSizeAndZeroDurationCannotBecomeVideoSends() {
        val fixture = Fixture()
        val valid = LocalAttachment(AttachmentKind.VIDEO, "C:/synthetic/clip.mp4", "clip.mp4", 1024, 5, 640, 360,
            thumbnail = LocalAttachmentThumbnail("C:/synthetic/thumb.jpg", 320, 180, 100))
        val results = mutableListOf<ChatResult<ChatMessage>>()
        listOf(valid.copy(durationSeconds = 0), valid.copy(thumbnail = valid.thumbnail!!.copy(width = 321)),
            valid.copy(thumbnail = valid.thumbnail!!.copy(size = 200_000)), valid.copy(thumbnail = valid.thumbnail!!.copy(path = "")))
            .forEach { fixture.session.sendAttachment(10, it, "", null, results::add) }
        assertTrue(fixture.requests.isEmpty())
        assertEquals(4, results.size)
        assertTrue(results.all { it.error != null })
    }

    @Test
    fun changingAConfirmedFileDuringNativeQuoteValidationCannotSendItsReplacement() {
        val fixture = Fixture()
        val file = Files.createTempFile("synthetic-confirmed-send-", ".bin")
        try {
            Files.write(file, byteArrayOf(1, 2, 3))
            val attachment = AttachmentFiles.inspect(file.toString(), AttachmentKind.FILE)
            var result: ChatResult<ChatMessage>? = null
            fixture.session.sendAttachmentReply(10, attachment, RichMessageText("caption"),
                OutgoingReply(30, MessageQuote(0, RichMessageText("private")))) { result = it }
            assertIs<TdApi.GetMessageProperties>(fixture.last.command)
            fixture.last.result(TdApi.MessageProperties().apply { canBeReplied = true })
            assertIs<TdApi.GetMessage>(fixture.last.command)
            fixture.last.result(message(30))
            assertIs<TdApi.GetOption>(fixture.last.command)
            Files.write(file, byteArrayOf(1, 2, 3, 4))
            fixture.last.result(TdApi.OptionValueInteger(1024))
            assertEquals(ChatError.REQUEST_FAILED, result?.error)
            assertTrue(fixture.requests.none { it.command is TdApi.SendMessage })
        } finally { Files.deleteIfExists(file) }
    }

    @Test
    fun replyMetadataPreservesQuoteAndOriginWithoutInventingASender() {
        val native = message(50).apply {
            replyTo = TdApi.MessageReplyToMessage().apply {
                chatId = 10; messageId = 30
                quote = TdApi.TextQuote(TdApi.FormattedText("private quote", emptyArray()), emptyArray(), 0, false)
                origin = TdApi.MessageOriginUser(6)
            }
        }
        val reply = assertNotNull(mapChatMessage(native).replyTo)
        assertEquals(30L, reply.messageId)
        assertEquals("private quote", reply.text)
        assertEquals(6L, reply.senderId)
        assertFalse(reply.senderIsChat)
        val reference = assertIs<TdApi.MessageReplyToMessage>(native.replyTo)
        reference.quote = null
        reference.origin = null
        assertEquals(MessageReply(10, 30), mapChatMessage(native).replyTo)
        reference.content = TdApi.MessageText(TdApi.FormattedText("private original", emptyArray()), null, null)
        reference.origin = TdApi.MessageOriginChat(40, "private author")
        val withContent = assertNotNull(mapChatMessage(native).replyTo)
        assertEquals("private original", withContent.text)
        assertEquals(40L, withContent.senderId)
        assertTrue(withContent.senderIsChat)
        assertFalse(withContent.toString().contains("private"))
    }

    @Test
    fun missingReplyPreviewUsesContainingMessageIdForGetRepliedMessage() {
        val fixture = Fixture()
        val results = mutableListOf<ChatResult<ChatMessage>>()
        fixture.session.getReplyMessage(10, 50, results::add)
        val request = assertIs<TdApi.GetRepliedMessage>(fixture.last.command)
        assertEquals(10L, request.chatId)
        assertEquals(50L, request.messageId)
        fixture.last.result(message(30))
        assertEquals(30L, results.single().value?.id)
    }

    @Test
    fun readDetailsCapabilityGateDoesNotQueryUnavailableViewers() {
        val fixture = Fixture()
        val results = mutableListOf<ChatResult<MessageReadDetails>>()
        fixture.session.messageReadDetails(10, 30, results::add)
        val request = assertIs<TdApi.GetMessageProperties>(fixture.last.command)
        assertEquals(30L, request.messageId)
        fixture.last.result(TdApi.MessageProperties())
        assertEquals(1, fixture.requests.size)
        assertEquals(MessageReadAvailability.UNAVAILABLE, results.single().value?.availability)
        assertTrue(results.single().value?.readers.orEmpty().isEmpty())
    }

    @Test
    fun groupViewersUseNamesFromGetUserAndKeepServerOrderDespiteCallbackOrder() {
        val fixture = Fixture()
        val results = mutableListOf<ChatResult<MessageReadDetails>>()
        fixture.session.messageReadDetails(10, 30, results::add)
        fixture.last.result(TdApi.MessageProperties().apply { canGetViewers = true })
        assertIs<TdApi.GetMessageViewers>(fixture.last.command)
        val viewersRequest = fixture.last
        viewersRequest.result(TdApi.MessageViewers(arrayOf(TdApi.MessageViewer(5, 100), TdApi.MessageViewer(6, 120), TdApi.MessageViewer(5, 100))))
        val users = fixture.requests.filter { it.command is TdApi.GetUser }
        assertEquals(2, users.size)
        assertEquals(5L, assertIs<TdApi.GetUser>(users[0].command).userId)
        users[1].result(user(6, "private second"))
        assertTrue(results.isEmpty())
        users[0].result(user(5, "private first"))
        users[0].error()
        viewersRequest.result(TdApi.MessageViewers(emptyArray()))
        val details = assertNotNull(results.single().value)
        assertEquals(MessageReadAvailability.AVAILABLE, details.availability)
        assertEquals(listOf("private first", "private second"), details.readers.map { it.name })
        assertEquals(listOf(100, 120), details.readers.map { it.readDate })
        assertFalse(details.toString().contains("private"))
        assertFalse(details.readers.toString().contains("private"))
    }

    @Test
    fun emptyViewerListIsAvailableButNeverInventsReaders() {
        val fixture = Fixture()
        val results = mutableListOf<ChatResult<MessageReadDetails>>()
        fixture.session.messageReadDetails(10, 30, results::add)
        fixture.last.result(TdApi.MessageProperties().apply { canGetViewers = true })
        fixture.last.result(TdApi.MessageViewers(emptyArray()))
        assertEquals(MessageReadAvailability.AVAILABLE, results.single().value?.availability)
        assertTrue(results.single().value?.readers.orEmpty().isEmpty())
        assertEquals(2, fixture.requests.size)
    }

    @Test
    fun privateReadDateResolvesPeerThroughChatTypeInsteadOfTreatingChatIdAsUserId() {
        val fixture = Fixture()
        val results = mutableListOf<ChatResult<MessageReadDetails>>()
        fixture.session.messageReadDetails(10, 30, results::add)
        fixture.last.result(TdApi.MessageProperties().apply { canGetReadDate = true })
        assertIs<TdApi.GetMessageReadDate>(fixture.last.command)
        fixture.last.result(TdApi.MessageReadDateRead(1700000000))
        assertEquals(10L, assertIs<TdApi.GetChat>(fixture.last.command).chatId)
        fixture.last.result(TdApi.Chat().apply { id = 10; type = TdApi.ChatTypePrivate(77) })
        assertEquals(77L, assertIs<TdApi.GetUser>(fixture.last.command).userId)
        fixture.last.result(user(77, "private peer"))
        val reader = assertNotNull(results.single().value).readers.single()
        assertEquals(77L, reader.userId)
        assertEquals("private peer", reader.name)
        assertEquals(1700000000, reader.readDate)
    }

    @Test
    fun privateUnreadExpiredAndPrivacyStatesNeverQueryOrInventPeerNames() {
        val statuses = listOf(
            TdApi.MessageReadDateUnread() to MessageReadAvailability.UNREAD,
            TdApi.MessageReadDateTooOld() to MessageReadAvailability.TOO_OLD,
            TdApi.MessageReadDateUserPrivacyRestricted() to MessageReadAvailability.USER_PRIVACY_RESTRICTED,
            TdApi.MessageReadDateMyPrivacyRestricted() to MessageReadAvailability.MY_PRIVACY_RESTRICTED,
        )
        statuses.forEach { (native, expected) ->
            val fixture = Fixture()
            val results = mutableListOf<ChatResult<MessageReadDetails>>()
            fixture.session.messageReadDetails(10, 30, results::add)
            fixture.last.result(TdApi.MessageProperties().apply { canGetReadDate = true })
            fixture.last.result(native)
            assertEquals(expected, results.single().value?.availability)
            assertTrue(results.single().value?.readers.orEmpty().isEmpty())
            assertEquals(2, fixture.requests.size)
        }
    }

    @Test
    fun unexpectedPrivateChatTypeDoesNotCreateAReaderFromGroupId() {
        val fixture = Fixture()
        val results = mutableListOf<ChatResult<MessageReadDetails>>()
        fixture.session.messageReadDetails(10, 30, results::add)
        fixture.last.result(TdApi.MessageProperties().apply { canGetReadDate = true })
        fixture.last.result(TdApi.MessageReadDateRead(100))
        fixture.last.result(TdApi.Chat().apply { id = 10; type = TdApi.ChatTypeBasicGroup(77) })
        assertEquals(MessageReadAvailability.UNAVAILABLE, results.single().value?.availability)
        assertEquals(3, fixture.requests.size)
    }

    @Test
    fun failedNameLookupReturnsSanitizedFailureOnlyOnceInsteadOfDisplayingAnId() {
        val fixture = Fixture()
        val results = mutableListOf<ChatResult<MessageReadDetails>>()
        fixture.session.messageReadDetails(10, 30, results::add)
        fixture.last.result(TdApi.MessageProperties().apply { canGetViewers = true })
        fixture.last.result(TdApi.MessageViewers(arrayOf(TdApi.MessageViewer(5, 100), TdApi.MessageViewer(6, 120))))
        val users = fixture.requests.filter { it.command is TdApi.GetUser }
        users[0].result(TdApi.Error(403, "private native failure"))
        users[0].result(user(5, "private first"))
        users[1].result(user(6, "private second"))
        assertEquals(1, results.size)
        assertEquals(ChatError.FORBIDDEN, results.single().error)
        assertNull(results.single().value)
        assertFalse(results.toString().contains("private"))
    }

    @Test
    fun videoAndDocumentPermissionsAreIndependentAndMembershipUpdatesApplyRestrictions() {
        val events = mutableListOf<ChatEvent>()
        val mapper = TdChatUpdateMapper(events::add)
        mapper.accept(TdApi.UpdateBasicGroup(TdApi.BasicGroup(1, 5, TdApi.ChatMemberStatusMember(0), true, 0)))
        mapper.accept(TdApi.UpdateNewChat(TdApi.Chat().apply {
            id = 10; type = TdApi.ChatTypeBasicGroup(1)
            permissions = TdApi.ChatPermissions().apply { canSendVideos = true; canSendDocuments = false }
        }))
        val chat = assertIs<ChatEvent.NewChat>(events.last()).chat
        assertFalse(chat.canSend)
        assertTrue(chat.canSendVideos)
        assertFalse(chat.canSendDocuments)
        mapper.accept(TdApi.UpdateChatPermissions(10, TdApi.ChatPermissions().apply { canSendVideos = true; canSendDocuments = true }))
        assertEquals(ChatEvent.Permissions(10, false, false, false, true, true), events.last())
        mapper.accept(TdApi.UpdateBasicGroup(TdApi.BasicGroup(1, 5, TdApi.ChatMemberStatusRestricted(true, 0,
            TdApi.ChatPermissions().apply { canSendDocuments = true }), true, 0)))
        assertEquals(ChatEvent.Permissions(10, false, false, false, false, true), events.last())
        mapper.accept(TdApi.UpdateBasicGroup(TdApi.BasicGroup(1, 5, TdApi.ChatMemberStatusLeft(), true, 0)))
        assertEquals(ChatEvent.Permissions(10, false), events.last())
    }

    @Test
    fun newContentModelsRedactNamesQuotesAndPathsFromStringRendering() {
        assertFalse(LocalAttachment(AttachmentKind.FILE, "C:/private/a.pdf", "private filename", 1).toString().contains("private"))
        assertFalse(MessageReply(1, 2, "private quote").toString().contains("private"))
        assertFalse(MessageReader(1, "private reader", 1).toString().contains("private"))
    }

    @Test
    fun savePermissionAndContentProtectionAreAuthoritativeNativeFields() {
        val message = message(50)
        assertFalse(mapChatMessage(message).canBeSaved)
        message.canBeSaved = true
        assertTrue(mapChatMessage(message).canBeSaved)
        val events = mutableListOf<ChatEvent>()
        val mapper = TdChatUpdateMapper(events::add)
        mapper.accept(TdApi.UpdateNewChat(TdApi.Chat().apply { id = 10; hasProtectedContent = true }))
        assertTrue(assertIs<ChatEvent.NewChat>(events.last()).chat.hasProtectedContent)
        mapper.accept(TdApi.UpdateChatHasProtectedContent(10, false))
        assertEquals(ChatEvent.ProtectedContent(10, false), events.last())
    }

    private fun file(id: Int) = TdApi.File().apply { this.id = id; size = 1024; expectedSize = 1024 }
    private fun user(id: Long, name: String) = TdApi.User().apply { this.id = id; firstName = name; lastName = "" }
    private fun message(id: Long) = TdApi.Message().apply {
        this.id = id; chatId = 10; senderId = TdApi.MessageSenderUser(5)
        content = TdApi.MessageText(TdApi.FormattedText("private text", emptyArray()), null, null)
    }
    private class Fixture {
        val requests = mutableListOf<Request>()
        val session = OfficialTdChatSession { command, result, error -> requests += Request(command, result, error) }
        val last get() = requests.last()
    }
    private class Request(val command: TdApi.Function<*>, val result: (TdApi.Object) -> Unit, val error: () -> Unit)
}
