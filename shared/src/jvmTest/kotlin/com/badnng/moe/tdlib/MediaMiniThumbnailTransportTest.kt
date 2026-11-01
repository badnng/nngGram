package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import org.drinkless.tdlib.TdApi
import kotlin.test.*

/** Native mapping only: synthetic encoded content is not decoded or downloaded by this boundary. */
class MediaMiniThumbnailTransportTest {
    @Test
    fun photoPreservesItsInlineBytesBeforeEitherNativeFileIsDownloaded() {
        val encoded = byteArrayOf(7, 11, 19, 23)
        val native = TdApi.Minithumbnail(40, 30, encoded)
        val content = photo(native)
        val media = assertNotNull(mapMessageMedia(content))
        val mini = assertNotNull(media.minithumbnail)
        assertEquals(40, mini.width)
        assertEquals(30, mini.height)
        assertContentEquals(encoded, mini.encodedCopy())
        encoded[0] = 99
        native.data = byteArrayOf(1)
        assertContentEquals(byteArrayOf(7, 11, 19, 23), mini.encodedCopy())
        assertEquals(12, media.file.id)
        assertEquals(11, media.thumbnail?.id)
        assertEquals(1280, media.width)
        assertEquals(960, media.height)
        assertFalse(media.file.downloaded)
        assertFalse(assertNotNull(media.thumbnail).downloaded)
        assertTrue(media.hasSpoiler)
        assertFalse(media.toString().contains("7, 11"))
    }

    @Test
    fun videoAndAnimationKeepTheSameInlineSourceAlongsideTheirIndependentFiles() {
        val expected = MediaMiniThumbnail(32, 18, byteArrayOf(3, 5, 8))
        val video = assertNotNull(mapMessageMedia(video(nativeMini(expected))))
        assertEquals(expected, video.minithumbnail)
        assertEquals(MediaFormat.VIDEO, video.format)
        assertEquals(21, video.file.id)
        assertEquals(22, video.thumbnail?.id)
        assertEquals(1280, video.width)
        assertEquals(720, video.height)
        assertEquals(7, video.durationSeconds)
        assertTrue(video.hasSpoiler)
        val animation = assertNotNull(mapMessageMedia(animation(nativeMini(expected))))
        assertEquals(expected, animation.minithumbnail)
        assertEquals(MediaFormat.ANIMATION, animation.format)
        assertEquals(31, animation.file.id)
        assertEquals(32, animation.thumbnail?.id)
        assertEquals(640, animation.width)
        assertEquals(360, animation.height)
        assertEquals(4, animation.durationSeconds)
        assertTrue(animation.hasSpoiler)
    }

    @Test
    fun secretPhotosVideosAndAnimationsNeverExposeInlineBytesOrFileIdentities() {
        val mini = TdApi.Minithumbnail(40, 30, byteArrayOf(1, 2, 3))
        assertNull(mapMessageMedia(photo(mini).apply { isSecret = true }))
        assertNull(mapMessageMedia(video(mini).apply { isSecret = true }))
        assertNull(mapMessageMedia(animation(mini).apply { isSecret = true }))
    }

    @Test
    fun invalidAndAbsentInlineMetadataIsIgnoredWithoutDroppingTheActualMedia() {
        val invalid = listOf<TdApi.Minithumbnail?>(
            null,
            TdApi.Minithumbnail().apply { width = 40; height = 30; data = null },
            TdApi.Minithumbnail(40, 30, byteArrayOf()),
            TdApi.Minithumbnail(0, 30, byteArrayOf(1)),
            TdApi.Minithumbnail(40, -1, byteArrayOf(1)),
            TdApi.Minithumbnail(256, 30, byteArrayOf(1)),
            TdApi.Minithumbnail(40, 256, byteArrayOf(1)),
            TdApi.Minithumbnail(40, 30, ByteArray(MediaMiniThumbnail.MAX_ENCODED_BYTES + 1)),
        )
        for (mini in invalid) {
            assertNull(mapMiniThumbnail(mini))
            for (content in listOf(photo(mini), video(mini), animation(mini))) {
                val mapped = assertNotNull(mapMessageMedia(content))
                assertNull(mapped.minithumbnail)
                assertTrue(mapped.file.id > 0)
                assertNotNull(mapped.thumbnail)
            }
        }
        // TL says "usually" <= 40; the pinned native decoder accepts unsigned-byte dimensions.
        val largest = assertNotNull(mapMiniThumbnail(TdApi.Minithumbnail(
            MediaMiniThumbnail.MAX_DIMENSION, MediaMiniThumbnail.MAX_DIMENSION,
            ByteArray(MediaMiniThumbnail.MAX_ENCODED_BYTES),
        )))
        assertEquals(255, largest.width)
        assertEquals(65_536, largest.encodedCopy().size)
    }

    @Test
    fun immutableInlineContentHasValueEqualityAndCannotBeChangedThroughWorkerCopies() {
        val original = byteArrayOf(2, 3, 5, 7)
        val value = MediaMiniThumbnail(40, 20, original)
        val hash = value.hashCode()
        original.fill(0)
        value.encodedCopy().fill(1)
        val equivalent = MediaMiniThumbnail(40, 20, byteArrayOf(2, 3, 5, 7))
        assertEquals(equivalent, value)
        assertEquals(hash, value.hashCode())
        assertEquals(equivalent.hashCode(), hash)
        assertNotEquals(MediaMiniThumbnail(20, 40, equivalent.encodedCopy()), value)
        assertNotEquals(MediaMiniThumbnail(40, 20, byteArrayOf(2, 3, 5, 8)), value)
        assertEquals("MediaMiniThumbnail(<redacted>)", value.toString())
        assertEquals(setOf(value), setOf(equivalent))
    }

    @Test
    fun constructionEnforcesTheSameBoundsBeforeCopyingAccountContent() {
        for ((width, height) in listOf(0 to 1, 1 to 0, -1 to 1, 1 to -1, 256 to 1, 1 to 256)) {
            assertFailsWith<IllegalArgumentException> { MediaMiniThumbnail(width, height, byteArrayOf(1)) }
        }
        assertFailsWith<IllegalArgumentException> { MediaMiniThumbnail(1, 1, byteArrayOf()) }
        assertFailsWith<IllegalArgumentException> {
            MediaMiniThumbnail(1, 1, ByteArray(MediaMiniThumbnail.MAX_ENCODED_BYTES + 1))
        }
    }

    @Test
    fun historyNewMessagesAndContentUpdatesPreserveTheSameNativeMiniThumbnail() {
        val mini = TdApi.Minithumbnail(40, 30, byteArrayOf(13, 17, 23))
        val content = photo(mini)
        val message = TdApi.Message().apply { id = 100; chatId = 10; this.content = content }
        val fromHistory = assertNotNull(mapChatMessage(message).media?.minithumbnail)
        val events = mutableListOf<ChatEvent>()
        val mapper = TdChatUpdateMapper(events::add)
        mapper.accept(TdApi.UpdateNewMessage(message))
        mapper.accept(TdApi.UpdateMessageContent(10, 100, content))
        assertEquals(fromHistory, assertIs<ChatEvent.NewMessage>(events[0]).message.media?.minithumbnail)
        assertEquals(fromHistory, assertIs<ChatEvent.Content>(events[1]).media?.minithumbnail)
        assertFalse(events.toString().contains("13, 17"))
    }

    private fun nativeMini(value: MediaMiniThumbnail) =
        TdApi.Minithumbnail(value.width, value.height, value.encodedCopy())

    private fun photo(mini: TdApi.Minithumbnail?) = TdApi.MessagePhoto().apply {
        photo = TdApi.Photo(false, mini, arrayOf(
            TdApi.PhotoSize("s", file(11), 80, 60, intArrayOf()),
            TdApi.PhotoSize("y", file(12), 1280, 960, intArrayOf()),
        ))
        hasSpoiler = true
    }

    private fun video(mini: TdApi.Minithumbnail?) = TdApi.MessageVideo().apply {
        video = TdApi.Video().apply {
            width = 1280; height = 720; duration = 7; minithumbnail = mini
            video = file(21)
            thumbnail = TdApi.Thumbnail(TdApi.ThumbnailFormatJpeg(), 320, 180, file(22))
        }
        hasSpoiler = true
    }

    private fun animation(mini: TdApi.Minithumbnail?) = TdApi.MessageAnimation().apply {
        animation = TdApi.Animation().apply {
            width = 640; height = 360; duration = 4; minithumbnail = mini
            animation = file(31)
            thumbnail = TdApi.Thumbnail(TdApi.ThumbnailFormatJpeg(), 160, 90, file(32))
        }
        hasSpoiler = true
    }

    private fun file(id: Int) = TdApi.File().apply {
        this.id = id
        local = TdApi.LocalFile().apply { canBeDownloaded = true }
    }
}
