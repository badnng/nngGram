package com.badnng.moe

import com.badnng.moe.chat.MediaFile
import com.badnng.moe.chat.MediaFormat
import com.badnng.moe.chat.MessageMedia
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConversationMediaPreviewTest {
    @Test fun anUndownloadedPhotoWithoutThumbnailDoesNotDownloadTheOriginalInTheIndex() {
        val photo = MessageMedia(MediaFile(10, canDownload = true))

        assertNull(conversationMediaPreview(photo, emptyMap()))
    }

    @Test fun aNativeVideoThumbnailIsTheOnlyFileExposedToThePreview() {
        val video = MessageMedia(MediaFile(10, canDownload = true), thumbnail = MediaFile(11, canDownload = true),
            format = MediaFormat.VIDEO, thumbnailFormat = MediaFormat.WEBP, durationSeconds = 90)

        val preview = conversationMediaPreview(video, emptyMap())

        assertEquals(11, preview?.file?.id)
        assertEquals(MediaFormat.WEBP, preview?.format)
        assertNull(preview?.thumbnail)
        assertFalse(preview?.file?.downloaded ?: true)
    }

    @Test fun aVideoWithoutThumbnailNeverExposesItsOriginalEvenWhenDownloaded() {
        val video = MessageMedia(MediaFile(10, downloaded = true, localPath = "offline-video.mp4"), format = MediaFormat.VIDEO)

        assertNull(conversationMediaPreview(video, emptyMap()))
    }

    @Test fun aCompletedNativePhotoCanBeReusedWithoutAnotherDownload() {
        val photo = MessageMedia(MediaFile(10, canDownload = true))

        assertEquals(photo, conversationMediaPreview(photo,
            mapOf(10 to photo.file.copy(downloaded = true, localPath = "offline-photo.png"))))
    }

    @Test fun anAlreadyDownloadedPhotoTakesPrecedenceOverItsSmallThumbnailWithoutRequestingAnotherFile() {
        val photo = MessageMedia(MediaFile(10, canDownload = true), thumbnail = MediaFile(11), hasSpoiler = true)
        val downloaded = photo.file.copy(downloaded = true, localPath = "offline-photo.png")

        assertEquals(photo, conversationMediaPreview(photo, mapOf(10 to downloaded)))
        assertTrue(conversationMediaPreview(photo, mapOf(10 to downloaded))?.hasSpoiler == true)
        assertEquals(11, conversationMediaPreview(photo, emptyMap())?.file?.id,
            "An incomplete original must leave the index on its native thumbnail.")
        assertEquals(11, conversationMediaPreview(photo, mapOf(10 to downloaded.copy(localPath = null)))?.file?.id,
            "A completed flag without a usable native path must not displace the thumbnail.")
    }

    @Test fun aThumbnailPreservesSpoilerProtection() {
        val photo = MessageMedia(MediaFile(10), thumbnail = MediaFile(11), hasSpoiler = true)

        assertTrue(conversationMediaPreview(photo, emptyMap())?.hasSpoiler == true)
    }
}
