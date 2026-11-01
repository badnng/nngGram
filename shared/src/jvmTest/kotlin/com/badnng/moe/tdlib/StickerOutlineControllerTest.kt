package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import org.drinkless.tdlib.TdApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StickerOutlineControllerTest {
    @Test
    fun onlyKnownStickerIdentitiesReachTheOfflineMethodAndRequestsAreDeduplicated() {
        val fixture = Fixture()
        fixture.controller.loadStickerOutline(fixture.state.token, 777)
        fixture.message(1, 10, MessageKind.PHOTO)
        fixture.controller.loadStickerOutline(fixture.state.token, 10)
        assertTrue(fixture.session.outlines.isEmpty())
        fixture.message(2, 20)
        repeat(3) { fixture.controller.loadStickerOutline(fixture.state.token, 20) }
        assertEquals(listOf(20), fixture.session.outlines.map { it.id })
        assertEquals(0, fixture.session.downloads)
        fixture.session.outlines.single().result(ChatResult("M0 0 L512 0 L256 256z"))
        fixture.flush()
        assertEquals(StickerOutline("M0 0 L512 0 L256 256z", 512, 256), fixture.state.stickerOutlines[20])
        fixture.controller.loadStickerOutline(fixture.state.token, 20)
        assertEquals(1, fixture.session.outlines.size)
        assertFalse(fixture.state.toString().contains("M0"))
        assertFalse(fixture.state.stickerOutlines[20].toString().contains("M0"))
    }

    @Test
    fun lateOutlineCannotCrossSelectionOrReviveADeletedSticker() {
        val fixture = Fixture()
        fixture.message(1, 10)
        val oldToken = fixture.state.token
        fixture.controller.loadStickerOutline(oldToken, 10)
        fixture.controller.event(ChatEvent.Deleted(1, listOf(1), permanent = true))
        fixture.session.outlines.last().result(ChatResult("M0 0 L20 0 L0 20z"))
        fixture.flush()
        assertTrue(fixture.state.stickerOutlines.isEmpty())
        fixture.message(2, 20)
        fixture.controller.loadStickerOutline(fixture.state.token, 20)
        fixture.controller.event(ChatEvent.NewChat(ChatItem(2, "synthetic second", 90)))
        fixture.controller.select(fixture.state.token, 2)
        fixture.flush()
        fixture.session.outlines.last().result(ChatResult("M0 0 L20 0 L0 20z"))
        fixture.flush()
        fixture.controller.loadStickerOutline(oldToken, 20)
        assertTrue(fixture.state.stickerOutlines.isEmpty())
        assertEquals(2, fixture.session.outlines.size)
    }

    @Test
    fun cacheAndOutstandingRequestsAreBoundedWithoutDownloadingFiles() {
        val fixture = Fixture()
        repeat(33) { fixture.message(it.toLong() + 1, it + 100) }
        repeat(33) { fixture.controller.loadStickerOutline(fixture.state.token, it + 100) }
        assertEquals(32, fixture.session.outlines.size)
        fixture.session.outlines.toList().forEach { it.result(ChatResult("M0 0 L20 0 L0 20z")) }
        fixture.flush()
        for (index in 33..163) {
            fixture.message(index.toLong() + 1, index + 100)
            fixture.controller.loadStickerOutline(fixture.state.token, index + 100)
            fixture.session.outlines.last().result(ChatResult("M0 0 L20 0 L0 20z"))
            fixture.flush()
        }
        assertEquals(128, fixture.state.stickerOutlines.size)
        assertFalse(100 in fixture.state.stickerOutlines)
        assertEquals(0, fixture.session.downloads)
        fixture.controller.deactivate()
        fixture.controller.loadStickerOutline(fixture.state.token, 263)
        assertEquals(163, fixture.session.outlines.size)
    }

    @Test
    fun unknownAndOversizedNativePathsRemainEmptyRatherThanInventingAnOutline() {
        val fixture = Fixture()
        fixture.message(1, 10)
        fixture.controller.loadStickerOutline(fixture.state.token, 10)
        fixture.session.outlines.last().result(ChatResult(error = ChatError.REQUEST_FAILED))
        fixture.flush()
        assertEquals("", fixture.state.stickerOutlines[10]?.svgPath)
        fixture.controller.loadStickerOutline(fixture.state.token, 10)
        assertEquals(1, fixture.session.outlines.size)
        fixture.message(2, 20)
        fixture.controller.loadStickerOutline(fixture.state.token, 20)
        fixture.session.outlines.last().result(ChatResult("M".repeat(65_537)))
        fixture.flush()
        assertEquals("", fixture.state.stickerOutlines[20]?.svgPath)
    }

    @Test
    fun nativeAdapterUsesOnlyTheOfflineSvgMethodWithRegularStickerFlags() {
        var request: TdApi.Function<*>? = null
        var complete: ((TdApi.Object) -> Unit)? = null
        val extras = OfficialTdChatExtras(send = { function, callback, _ -> request = function; complete = callback })
        var result: ChatResult<String>? = null
        extras.stickerOutline(42) { result = it }
        val native = assertIs<TdApi.GetStickerOutlineSvgPath>(request)
        assertEquals(42, native.stickerFileId)
        assertFalse(native.forAnimatedEmoji)
        assertFalse(native.forClickedAnimatedEmojiMessage)
        complete?.invoke(TdApi.Text("M0 0 L10 0 L0 10z"))
        assertEquals("M0 0 L10 0 L0 10z", result?.value)
    }

    private class Fixture {
        val session = FakeSession()
        var state = ChatSnapshot()
        private val queued = ArrayDeque<() -> Unit>()
        val controller = ChatController(3, session, queued::addLast, { state = it })
        init {
            controller.start()
            flush()
            controller.event(ChatEvent.NewChat(ChatItem(1, "synthetic", 100)))
            controller.select(state.token, 1)
            flush()
        }
        fun message(id: Long, fileId: Int, kind: MessageKind = MessageKind.STICKER) {
            controller.event(ChatEvent.NewMessage(ChatMessage(id, 1, text = "", outgoing = false, date = 1,
                kind = kind, media = MessageMedia(MediaFile(fileId, canDownload = true), width = 512, height = 256,
                    format = if (kind == MessageKind.STICKER) MediaFormat.WEBP else MediaFormat.PHOTO))))
        }
        fun flush() { while (queued.isNotEmpty()) queued.removeFirst().invoke() }
    }

    private class FakeSession : TdChatSession {
        data class OutlineRequest(val id: Int, val result: (ChatResult<String>) -> Unit)
        val outlines = mutableListOf<OutlineRequest>()
        var downloads = 0
        override fun stickerOutline(fileId: Int, forAnimatedEmoji: Boolean, result: (ChatResult<String>) -> Unit) { outlines += OutlineRequest(fileId, result) }
        override fun download(fileId: Int, result: (ChatResult<MediaFile>) -> Unit) { downloads++ }
        override fun loadChats(limit: Int, result: (ChatResult<ChatLoadResult>) -> Unit) = result(ChatResult(ChatLoadResult(true)))
        override fun history(chatId: Long, fromMessageId: Long, limit: Int, result: (ChatResult<List<ChatMessage>>) -> Unit) = result(ChatResult(emptyList()))
        override fun open(chatId: Long) = Unit
        override fun close(chatId: Long) = Unit
        override fun sendText(chatId: Long, text: String, result: (ChatResult<ChatMessage>) -> Unit) = Unit
        override fun retry(chatId: Long, messageId: Long, result: (ChatResult<List<ChatMessage>>) -> Unit) = Unit
        override fun markRead(chatId: Long, messageIds: List<Long>, result: (ChatResult<Unit>) -> Unit) = Unit
    }
}
