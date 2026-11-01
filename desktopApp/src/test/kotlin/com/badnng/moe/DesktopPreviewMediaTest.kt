package com.badnng.moe

import com.badnng.moe.chat.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class DesktopPreviewMediaTest {
    @Test
    fun independentPreviewIndexContainsMediaAbsentFromTheTimelineAndCachesEachCategory() {
        val photo = message(10, MessageKind.PHOTO)
        val video = message(11, MessageKind.VIDEO)
        val secondPhoto = message(12, MessageKind.PHOTO)
        val document = message(20, MessageKind.FILE)
        val index = DesktopPreviewMediaIndex(mapOf(98L to mapOf(
            ChatMediaCategory.PHOTOS_AND_VIDEOS to listOf(secondPhoto, video, photo),
            ChatMediaCategory.FILES to listOf(document))), pageSize = 2)
        val initial = snapshot()
        var state = index.load(initial, initial.token, ChatMediaCategory.PHOTOS_AND_VIDEOS, false)
        assertEquals(initial.messages, state.messages)
        assertEquals(listOf(12L, 11L), state.mediaIndex.messages.map { it.id })
        assertEquals(3, state.mediaIndex.totalCount)
        assertEquals("2", state.mediaIndex.nextOffset)
        assertEquals(1, state.mediaIndex.counts[ChatMediaCategory.FILES])
        assertEquals(0, state.mediaIndex.counts[ChatMediaCategory.VOICE])
        state = index.load(state, state.token, ChatMediaCategory.FILES, false)
        assertEquals(listOf(20L), state.mediaIndex.messages.map { it.id })
        state = index.load(state, state.token, ChatMediaCategory.PHOTOS_AND_VIDEOS, false)
        state = index.load(state, state.token, ChatMediaCategory.PHOTOS_AND_VIDEOS, true)
        assertEquals(listOf(12L, 11L, 10L), state.mediaIndex.messages.map { it.id })
        assertEquals("", state.mediaIndex.nextOffset)
        assertSame(state, index.load(state, state.token, ChatMediaCategory.PHOTOS_AND_VIDEOS, true))
    }

    @Test
    fun openingRequiresCurrentIndexMembershipAndLoadsTheHistoricalTargetWithoutReplacingCurrentEdits() {
        val photo = message(10, MessageKind.PHOTO)
        val video = message(11, MessageKind.VIDEO)
        val document = message(1004, MessageKind.FILE)
        val index = DesktopPreviewMediaIndex(mapOf(98L to mapOf(
            ChatMediaCategory.PHOTOS_AND_VIDEOS to listOf(photo, video),
            ChatMediaCategory.FILES to listOf(document))))
        val initial = snapshot().copy(messages = listOf(document.copy(text = "current edited synthetic caption")))
        var state = index.load(initial, initial.token, ChatMediaCategory.PHOTOS_AND_VIDEOS, false)
        assertSame(state, index.open(state, state.token, 1004))
        assertSame(state, index.open(state, state.token.copy(selection = 2), 10))
        state = index.open(state, state.token, 10)
        assertEquals(10L, state.jumpMessageId)
        assertEquals(1L, state.jumpRevision)
        assertTrue(state.messages.any { it.id == 10L })
        assertEquals("current edited synthetic caption", state.messages.single { it.id == 1004L }.text)
        assertEquals(2L, index.open(state, state.token, 10).jumpRevision)
        val other = snapshot(99)
        assertSame(other, index.open(other, other.token, 10))
        val disabled = state.copy(enabled = false)
        assertSame(disabled, index.load(disabled, state.token, ChatMediaCategory.FILES, false))
    }

    @Test
    fun catalogAssetsAreLocalOwnedPhotosAndUnplayableVideoThumbnailsAndCleanupIsIdempotent() {
        val assets = DesktopPreviewMediaAssets.create { path, _ -> Files.write(path, byteArrayOf(1, 2, 3)) }
        val paths = (assets.photos + assets.videoThumbnails).map { Path.of(checkNotNull(it.localPath)) }
        val directory = paths.first().parent
        try {
            val catalog = desktopPreviewMediaCatalog(98, 1_000_000, assets, emptyList())
            val media = catalog.getValue(ChatMediaCategory.PHOTOS_AND_VIDEOS)
            assertEquals(9, media.size)
            assertEquals(6, media.count { it.kind == MessageKind.PHOTO })
            val videos = media.filter { it.kind == MessageKind.VIDEO }
            assertEquals(3, videos.size)
            assertTrue(videos.all {
                val attachment = assertNotNull(it.media)
                attachment.thumbnail?.downloaded == true && attachment.file.localPath == null &&
                    !attachment.file.downloaded && !attachment.file.canDownload && attachment.file.size == 0L
            })
            assertTrue(paths.all { it.startsWith(directory) && Files.isRegularFile(it) })
            assertTrue(assets.photos.all { it.downloaded && !it.canDownload && it.size > 0 })
        } finally { assets.close() }
        assets.close()
        assertTrue(paths.all { Files.notExists(it) })
        assertFalse(Files.exists(directory))
    }

    @Test
    fun failedSyntheticGenerationRemovesOnlyItsOwnedPartialFiles() {
        val attempted = mutableListOf<Path>()
        assertFailsWith<IllegalStateException> {
            DesktopPreviewMediaAssets.create { path, index ->
                attempted.add(path)
                Files.write(path, byteArrayOf(1))
                if (index == 1) error("synthetic writer failure")
            }
        }
        assertEquals(2, attempted.size)
        assertTrue(attempted.all { Files.notExists(it) })
        assertTrue(Files.notExists(attempted.first().parent))
    }

    private fun snapshot(chatId: Long = 98): ChatSnapshot {
        val chat = ChatItem(chatId, "synthetic group")
        return ChatSnapshot(enabled = true, token = ChatToken(1, 1, chatId), selected = chat,
            knownChats = listOf(chat), messages = listOf(ChatMessage(1001, chatId, text = "loaded synthetic history")))
    }

    private fun message(id: Long, kind: MessageKind) = ChatMessage(id, 98, text = "indexed synthetic media", kind = kind)
}
