package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import org.drinkless.tdlib.TdApi
import kotlin.test.*

/** Offline outline callbacks only: these fixtures never create a TDLib client or download bytes. */
class AnimatedEmojiOutlineTest {
    @Test fun nativeAdaptersPairTheRequestedEmojiVariantWithTheOfflineNonClickedMethod() {
        for (throughSession in listOf(false, true)) for (animated in listOf(false, true)) {
            val requests = mutableListOf<NativeRequest>()
            val send: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit = { command, result, _ ->
                requests += NativeRequest(command, result)
            }
            val results = mutableListOf<ChatResult<String>>()
            if (throughSession) OfficialTdChatSession(send).stickerOutline(301, animated, results::add)
            else OfficialTdChatExtras(send).stickerOutline(301, animated, results::add)
            val native = assertIs<TdApi.GetStickerOutlineSvgPath>(requests.single().command)
            assertEquals(301, native.stickerFileId)
            assertEquals(animated, native.forAnimatedEmoji)
            assertFalse(native.forClickedAnimatedEmojiMessage)
            requests.single().result(TdApi.Text(PATH))
            assertEquals(PATH, results.single().value)
            assertNull(results.single().error)
            assertEquals(1, requests.size)
            assertTrue(requests.none { it.command is TdApi.DownloadFile })
        }
    }

    @Test fun knownEmojiArtworkWinsOverAnOrdinaryReferenceAndRetainsItsOwnOutlineViewbox() {
        val fixture = Fixture()
        fixture.message(1, animated = false, width = 100, height = 50)
        fixture.controller.loadStickerOutline(fixture.state.token, FILE_ID)
        val ordinary = fixture.session.outlines.single()
        assertFalse(ordinary.forAnimatedEmoji)
        fixture.message(2, animated = true, width = 512, height = 256)
        ordinary.result(ChatResult(PATH))
        fixture.flush()
        assertTrue(fixture.state.stickerOutlines.isEmpty(), "A pending ordinary path cannot adopt the now-prioritized emoji geometry")
        repeat(3) { fixture.controller.loadStickerOutline(fixture.state.token, FILE_ID) }
        assertEquals(2, fixture.session.outlines.size)
        val request = fixture.session.outlines.last()
        assertEquals(FILE_ID, request.fileId)
        assertTrue(request.forAnimatedEmoji)
        request.result(ChatResult(PATH))
        fixture.flush()
        assertEquals(StickerOutline(PATH, 512, 256), fixture.state.stickerOutlines[FILE_ID])
        assertEquals(0, fixture.session.downloads)
    }

    @Test fun sharedFileCacheKeepsTheViewboxOfItsAcceptedVariantInEitherDirection() {
        for (animatedFirst in listOf(false, true)) {
            val fixture = Fixture()
            val firstWidth = if (animatedFirst) 512 else 100
            val firstHeight = if (animatedFirst) 256 else 50
            fixture.message(1, animatedFirst, firstWidth, firstHeight)
            fixture.controller.loadStickerOutline(fixture.state.token, FILE_ID)
            val original = fixture.session.outlines.single()
            assertEquals(animatedFirst, original.forAnimatedEmoji)
            val originalPath = path(firstWidth, firstHeight)
            original.result(ChatResult(originalPath))
            fixture.flush()
            val cached = StickerOutline(originalPath, firstWidth, firstHeight)
            assertEquals(cached, fixture.state.stickerOutlines[FILE_ID])

            fixture.replace(1, !animatedFirst, if (animatedFirst) 100 else 512, if (animatedFirst) 50 else 256)
            fixture.controller.loadStickerOutline(fixture.state.token, FILE_ID)
            assertEquals(1, fixture.session.outlines.size, "The same file may reuse an outline with its own source viewbox")
            assertEquals(cached, fixture.state.stickerOutlines[FILE_ID], "A new surface must not relabel cached path coordinates")
            assertEquals(0, fixture.session.downloads)
        }
    }

    @Test fun aLateEmojiOutlineCannotCrossTheSelectedChatIdentity() {
        val fixture = Fixture()
        fixture.message(1, animated = true)
        val oldToken = fixture.state.token
        fixture.controller.loadStickerOutline(oldToken, FILE_ID)
        val oldRequest = fixture.session.outlines.single()
        fixture.controller.event(ChatEvent.NewChat(ChatItem(2, "other synthetic chat", 90)))
        fixture.controller.select(fixture.state.token, 2)
        fixture.flush()
        oldRequest.result(ChatResult(PATH))
        fixture.flush()
        fixture.controller.loadStickerOutline(oldToken, FILE_ID)
        assertTrue(fixture.state.stickerOutlines.isEmpty())
        assertEquals(1, fixture.session.outlines.size)
        assertEquals(0, fixture.session.downloads)
    }

    @Test fun aLateEmojiOutlineCannotReviveADeletedMessage() {
        val fixture = Fixture()
        fixture.message(1, animated = true)
        fixture.controller.loadStickerOutline(fixture.state.token, FILE_ID)
        val pending = fixture.session.outlines.single()
        fixture.controller.event(ChatEvent.Deleted(1, listOf(1), permanent = true))
        pending.result(ChatResult(PATH))
        fixture.flush()
        assertTrue(fixture.state.stickerOutlines.isEmpty())
        fixture.controller.loadStickerOutline(fixture.state.token, FILE_ID)
        assertEquals(1, fixture.session.outlines.size)
    }

    @Test fun aLateReplyMustStillMatchBothTheArtworkDimensionsAndTheRequestedEmojiVariant() {
        val changes = listOf(
            // Equal dimensions isolate the native flag check from the geometry check.
            Change(true, false, 512, 256),
            Change(false, true, 512, 256),
            // An unchanged flag still cannot relabel a path requested for different dimensions.
            Change(true, true, 256, 128),
        )
        changes.forEach { change ->
            val fixture = Fixture()
            fixture.message(1, change.before, 512, 256)
            fixture.controller.loadStickerOutline(fixture.state.token, FILE_ID)
            val old = fixture.session.outlines.single()
            assertEquals(change.before, old.forAnimatedEmoji)
            fixture.replace(1, change.after, change.width, change.height)
            old.result(ChatResult(PATH))
            fixture.flush()
            assertTrue(fixture.state.stickerOutlines.isEmpty())

            fixture.controller.loadStickerOutline(fixture.state.token, FILE_ID)
            assertEquals(2, fixture.session.outlines.size)
            val current = fixture.session.outlines.last()
            assertEquals(change.after, current.forAnimatedEmoji)
            val currentPath = path(change.width, change.height)
            current.result(ChatResult(currentPath))
            fixture.flush()
            assertEquals(StickerOutline(currentPath, change.width, change.height), fixture.state.stickerOutlines[FILE_ID])
            assertEquals(0, fixture.session.downloads)
        }
    }

    private class Fixture {
        val session = Session()
        var state = ChatSnapshot()
        private val queued = ArrayDeque<() -> Unit>()
        val controller = ChatController(12, session, queued::addLast, { state = it })

        init {
            controller.start()
            flush()
            controller.event(ChatEvent.NewChat(ChatItem(1, "synthetic chat", 100)))
            controller.select(state.token, 1)
            flush()
        }

        fun message(id: Long, animated: Boolean, width: Int = 512, height: Int = 256) {
            controller.event(ChatEvent.NewMessage(ChatMessage(id, 1, text = if (animated) "🙂" else "",
                kind = if (animated) MessageKind.ANIMATED_EMOJI else MessageKind.STICKER,
                media = media(animated, width, height))))
        }

        fun replace(id: Long, animated: Boolean, width: Int, height: Int) {
            controller.event(ChatEvent.Content(1, id, if (animated) "🙂" else "",
                if (animated) MessageKind.ANIMATED_EMOJI else MessageKind.STICKER, media(animated, width, height)))
        }

        fun flush() { while (queued.isNotEmpty()) queued.removeFirst().invoke() }
    }

    private class Session : TdChatSession {
        data class OutlineRequest(val fileId: Int, val forAnimatedEmoji: Boolean, val result: (ChatResult<String>) -> Unit)
        val outlines = mutableListOf<OutlineRequest>()
        var downloads = 0

        override fun stickerOutline(fileId: Int, forAnimatedEmoji: Boolean, result: (ChatResult<String>) -> Unit) {
            outlines += OutlineRequest(fileId, forAnimatedEmoji, result)
        }

        override fun download(fileId: Int, result: (ChatResult<MediaFile>) -> Unit) { downloads++ }
        override fun loadChats(limit: Int, result: (ChatResult<ChatLoadResult>) -> Unit) = result(ChatResult(ChatLoadResult(true)))
        override fun history(chatId: Long, fromMessageId: Long, limit: Int, result: (ChatResult<List<ChatMessage>>) -> Unit) =
            result(ChatResult(emptyList()))
        override fun open(chatId: Long) = Unit
        override fun close(chatId: Long) = Unit
        override fun sendText(chatId: Long, text: String, result: (ChatResult<ChatMessage>) -> Unit) = Unit
        override fun retry(chatId: Long, messageId: Long, result: (ChatResult<List<ChatMessage>>) -> Unit) = Unit
        override fun markRead(chatId: Long, messageIds: List<Long>, result: (ChatResult<Unit>) -> Unit) = Unit
    }

    private data class Change(val before: Boolean, val after: Boolean, val width: Int, val height: Int)
    private class NativeRequest(val command: TdApi.Function<*>, val result: (TdApi.Object) -> Unit)

    private companion object {
        const val FILE_ID = 301
        const val PATH = "M0 0 L512 0 L0 256z"

        fun path(width: Int, height: Int) = "M0 0 L$width 0 L0 ${height}z"

        fun media(animated: Boolean, width: Int, height: Int) = MessageMedia(MediaFile(FILE_ID, canDownload = true),
            width = width, height = height, format = MediaFormat.TGS,
            animatedEmoji = if (animated) AnimatedEmojiMedia(width, height, 0, false) else null)
    }
}
