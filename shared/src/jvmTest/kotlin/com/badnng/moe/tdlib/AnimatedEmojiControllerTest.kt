package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import kotlin.test.*

/** Only actor fixtures and native IDs supplied by the synthetic message are used. */
class AnimatedEmojiControllerTest {
    @Test fun pictureBytesAndOfflineOutlineUseTheKnownMessageFileButNeverAnUnreferencedSoundFile() {
        val fixture = Fixture()
        fixture.controller.event(ChatEvent.FileUpdated(MediaFile(399, canDownload = true)))
        fixture.controller.event(ChatEvent.NewMessage(emojiMessage()))
        fixture.controller.downloadFile(fixture.state.token, 399)
        fixture.controller.downloadFile(fixture.state.token, 301)
        fixture.controller.loadStickerOutline(fixture.state.token, 399)
        fixture.controller.loadStickerOutline(fixture.state.token, 301)
        assertEquals(listOf(301), fixture.session.downloads)
        assertEquals(listOf(301), fixture.session.outlines)
        assertFalse(399 in fixture.state.mediaFiles)
        fixture.controller.sendSticker(fixture.state.token, StickerItem(900, 50, "😭", emojiMedia()))
        assertEquals(ChatError.UNSUPPORTED, fixture.state.sendError)
        assertEquals(0, fixture.session.stickerSends, "An emoji message must not authorize an ordinary selector sticker")
    }

    @Test fun anEarlyReadyFileUpdateRemainsAuthoritativeWhenTheContainingEmojiMessageArrives() {
        val fixture = Fixture()
        fixture.controller.event(ChatEvent.FileUpdated(MediaFile(301, localPath = "C:/synthetic/emoji.tgs", downloaded = true)))
        fixture.controller.event(ChatEvent.NewMessage(emojiMessage()))
        assertTrue(fixture.state.mediaFiles.getValue(301).downloaded)
        fixture.controller.downloadFile(fixture.state.token, 301)
        assertTrue(fixture.session.downloads.isEmpty())
    }

    @Test fun aContentUpdateCannotBeRevertedByAnOlderHistoryPageInEitherDirection() {
        for (asEmoji in listOf(false, true)) {
            val fixture = Fixture()
            val current = if (asEmoji) emojiMessage() else ChatMessage(10, 1, text = "current text")
            val stale = if (asEmoji) ChatMessage(10, 1, text = "stale text") else emojiMessage()
            fixture.controller.event(ChatEvent.Content(1, 10, current.text, current.kind, current.media, current.entities))
            fixture.session.histories.first()(ChatResult(listOf(stale)))
            fixture.flush()
            fixture.session.histories.last()(ChatResult(emptyList()))
            fixture.flush()
            val shown = fixture.state.messages.single()
            assertEquals(current.kind, shown.kind)
            assertEquals(current.text, shown.text)
            assertEquals(current.media, shown.media)
            assertEquals(current.entities, shown.entities)
        }
    }

    @Test fun forwardingAnEmojiUsesTextPermissionRatherThanTheStickerPermission() {
        for (textAllowed in listOf(false, true)) {
            val fixture = Fixture(destinationText = textAllowed, destinationStickers = !textAllowed)
            fixture.controller.event(ChatEvent.NewMessage(emojiMessage()))
            fixture.controller.loadMessageActions(fixture.state.token, 10)
            fixture.session.actions.last()(ChatResult(MessageActions(canForward = true)))
            fixture.flush()
            fixture.controller.forwardMessage(fixture.state.token, 10, 2)
            assertEquals(if (textAllowed) listOf(2L) else emptyList(), fixture.session.forwardDestinations)
            if (!textAllowed) assertEquals(ChatError.FORBIDDEN, fixture.state.mutationError)
        }
    }

    @Test fun retryingAFailedEmojiUsesTextPermissionEvenWhenOrdinaryStickersAreForbidden() {
        for (textAllowed in listOf(false, true)) {
            val fixture = Fixture(sourceText = textAllowed, sourceStickers = !textAllowed)
            fixture.controller.event(ChatEvent.NewMessage(emojiMessage().copy(delivery = MessageDelivery.FAILED, canRetry = true)))
            fixture.controller.retry(fixture.state.token, 10)
            assertEquals(if (textAllowed) listOf(10L) else emptyList(), fixture.session.retries)
            if (!textAllowed) assertEquals(ChatError.FORBIDDEN, fixture.state.sendError)
        }
    }

    private class Fixture(sourceText: Boolean = true, sourceStickers: Boolean = true,
        destinationText: Boolean = true, destinationStickers: Boolean = true) {
        private val queued = ArrayDeque<() -> Unit>()
        val session = Session()
        var state = ChatSnapshot()
        val controller = ChatController(8, session, queued::addLast, { state = it })
        init {
            controller.start()
            controller.event(ChatEvent.NewChat(ChatItem(1, "source", 20, canSend = sourceText, canSendStickers = sourceStickers)))
            controller.event(ChatEvent.NewChat(ChatItem(2, "destination", 10, canSend = destinationText, canSendStickers = destinationStickers)))
            controller.select(state.token, 1)
            flush()
        }
        fun flush() { while (queued.isNotEmpty()) queued.removeFirst().invoke() }
    }

    private class Session : TdChatSession {
        val histories = mutableListOf<(ChatResult<List<ChatMessage>>) -> Unit>()
        val downloads = mutableListOf<Int>()
        val outlines = mutableListOf<Int>()
        val actions = mutableListOf<(ChatResult<MessageActions>) -> Unit>()
        val forwardDestinations = mutableListOf<Long>()
        val retries = mutableListOf<Long>()
        var stickerSends = 0
        override fun loadChats(limit: Int, result: (ChatResult<ChatLoadResult>) -> Unit) {}
        override fun history(chatId: Long, fromMessageId: Long, limit: Int, result: (ChatResult<List<ChatMessage>>) -> Unit) { histories += result }
        override fun open(chatId: Long) {}
        override fun close(chatId: Long) {}
        override fun sendText(chatId: Long, text: String, result: (ChatResult<ChatMessage>) -> Unit) {}
        override fun retry(chatId: Long, messageId: Long, result: (ChatResult<List<ChatMessage>>) -> Unit) { retries += messageId }
        override fun markRead(chatId: Long, messageIds: List<Long>, result: (ChatResult<Unit>) -> Unit) {}
        override fun download(fileId: Int, result: (ChatResult<MediaFile>) -> Unit) { downloads += fileId }
        override fun stickerOutline(fileId: Int, forAnimatedEmoji: Boolean, result: (ChatResult<String>) -> Unit) { outlines += fileId }
        override fun messageActions(chatId: Long, messageId: Long, result: (ChatResult<MessageActions>) -> Unit) { actions += result }
        override fun forwardMessage(fromChatId: Long, messageId: Long, toChatId: Long, result: (ChatResult<List<ChatMessage>>) -> Unit) { forwardDestinations += toChatId }
        override fun sendSticker(chatId: Long, sticker: StickerItem, result: (ChatResult<ChatMessage>) -> Unit) { stickerSends++ }
    }

    private companion object {
        fun emojiMedia() = MessageMedia(MediaFile(301, canDownload = true), width = 512, height = 512,
            format = MediaFormat.TGS, emoji = "😭", setId = 50, animatedEmoji = AnimatedEmojiMedia(224, 224, 0, true))
        fun emojiMessage() = ChatMessage(10, 1, text = "😭", kind = MessageKind.ANIMATED_EMOJI,
            media = emojiMedia(), canBeSaved = true)
    }
}
