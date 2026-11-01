package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import org.drinkless.tdlib.TdApi
import kotlin.test.*

/** Native synthetic messages only: no TDLib client, account, download or network. */
class AnimationMessageTransportTest {
    @Test
    fun gifAndMp4AnimationsRetainNativeFilesDimensionsCaptionEntitiesAndSpoiler() {
        for (mime in listOf("image/gif", "video/mp4")) {
            val content = animation(mime)
            val message = mapChatMessage(TdApi.Message().apply {
                id = 10; chatId = 20; this.content = content; canBeSaved = false
            })
            assertEquals(MessageKind.ANIMATION, message.kind)
            assertEquals("原生 caption", message.text)
            assertEquals(1, message.entities.size)
            assertEquals(3, message.entities.single().offset)
            assertEquals(7, message.entities.single().length)
            assertFalse(message.canBeSaved)
            val media = assertNotNull(message.media)
            assertEquals(MediaFormat.ANIMATION, media.format)
            assertEquals(7, media.file.id)
            assertEquals(8, media.thumbnail?.id)
            assertEquals(MediaFormat.PHOTO, media.thumbnailFormat)
            assertEquals(480, media.width)
            assertEquals(640, media.height)
            assertEquals(3, media.durationSeconds)
            assertEquals(mime, media.mimeType)
            assertEquals("synthetic-private-animation", media.fileName)
            assertTrue(media.hasSpoiler)
            assertFalse(message.toString().contains("caption"))
            assertFalse(media.toString().contains("private"))
        }
    }

    @Test
    fun secretAnimationsExposeNeitherPrimaryFileNorThumbnail() {
        val content = animation("image/gif").apply { isSecret = true }
        assertNull(mapMessageMedia(content))
        assertNull(mapMessageMedia(TdApi.MessageAnimation()))
        content.isSecret = false
        content.animation.animation = null
        assertNull(mapMessageMedia(content))
    }

    @Test
    fun unavailableNativeDimensionsUseOnlyTheActualNativeThumbnail() {
        val content = animation("image/gif")
        content.animation.width = 0
        content.animation.height = -1
        content.animation.duration = -1
        val media = assertNotNull(mapMessageMedia(content))
        assertEquals(120, media.width)
        assertEquals(160, media.height)
        assertEquals(0, media.durationSeconds)
    }

    @Test
    fun nativeContentUpdatesCarryNewAnimationIdentityWithoutReinterpretingCaption() {
        val events = mutableListOf<ChatEvent>()
        val mapper = TdChatUpdateMapper(events::add)
        val content = animation("video/mp4")
        content.caption = TdApi.FormattedText("**plain native caption**", emptyArray())
        content.animation.animation = file(99)
        mapper.accept(TdApi.UpdateMessageContent(20, 10, content))
        val update = assertIs<ChatEvent.Content>(events.single())
        assertEquals(20L, update.chatId)
        assertEquals(10L, update.messageId)
        assertEquals(MessageKind.ANIMATION, update.kind)
        assertEquals(99, update.media?.file?.id)
        assertEquals("**plain native caption**", update.text)
        assertTrue(update.entities.isEmpty())
    }

    private fun animation(mime: String) = TdApi.MessageAnimation().apply {
        animation = TdApi.Animation().apply {
            duration = 3; width = 480; height = 640
            fileName = "synthetic-private-animation"; mimeType = mime
            animation = file(7)
            thumbnail = TdApi.Thumbnail(TdApi.ThumbnailFormatJpeg(), 120, 160, file(8))
        }
        caption = TdApi.FormattedText("原生 caption", arrayOf(TdApi.TextEntity(3, 7, TdApi.TextEntityTypeBold())))
        hasSpoiler = true
    }

    private fun file(id: Int) = TdApi.File().apply {
        this.id = id; size = 1024; expectedSize = 1024
        local = TdApi.LocalFile().apply { canBeDownloaded = true }
    }
}
