package com.badnng.moe

import com.badnng.moe.media.ProfilePhotoCrop
import com.badnng.moe.media.ProfilePhotoPreparation
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import javax.imageio.IIOImage
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

class ProfileAvatarSelectionTest {
    @Test fun actualStaticImageContentWinsOverVideoExtensionAndUsesExistingCropper() = runBlocking<Unit> {
        for (format in listOf("png", "bmp", "gif")) {
            val path = Files.createTempFile("avatar-content-", ".mp4")
            try {
                assertTrue(ImageIO.write(BufferedImage(32, 16, BufferedImage.TYPE_INT_RGB), format, path.toFile()))
                val result = assertIs<ProfileAvatarSelection.Photo>(readProfileAvatarSelection(path.toString()))
                assertEquals(32, result.source.width)
                assertEquals(16, result.source.height)
                val prepared = ProfilePhotoPreparation.prepare(result.source, ProfilePhotoCrop())
                try { assertEquals(16, prepared.dimension) } finally { prepared.lease.release() }
            } finally { Files.deleteIfExists(path) }
        }
    }

    @Test fun failedImageDecodeHandsOriginalPathToValidatingVideoEditorWithoutUpload() = runBlocking<Unit> {
        val path = Files.createTempFile("avatar-content-", ".mov")
        try {
            // A failed image decode enters video validation; it cannot create a photo upload.
            Files.write(path, byteArrayOf(0, 0, 0, 24, 102, 116, 121, 112, 105, 115, 111, 109))
            val result = assertIs<ProfileAvatarSelection.Video>(readProfileAvatarSelection(path.toString()))
            assertEquals(path.toString(), result.path)
        } finally { Files.deleteIfExists(path) }
    }

    @Test fun animatedImageUsesOnlyItsFirstFrameInTheOfficialImageBranch() = runBlocking<Unit> {
        val path = Files.createTempFile("avatar-animated-", ".gif")
        val writer = ImageIO.getImageWritersByFormatName("gif").next()
        try {
            ImageIO.createImageOutputStream(path.toFile()).use { stream ->
                writer.output = stream
                writer.prepareWriteSequence(null)
                for (color in listOf(0xffff0000.toInt(), 0xff0000ff.toInt())) {
                    val frame = BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB)
                    for (y in 0 until 32) for (x in 0 until 32) frame.setRGB(x, y, color)
                    writer.writeToSequence(IIOImage(frame, null, null), null)
                }
                writer.endWriteSequence()
            }
            val selected = assertIs<ProfileAvatarSelection.Photo>(readProfileAvatarSelection(path.toString()))
            assertTrue(selected.source.animated)
            val photo = ProfilePhotoPreparation.prepare(selected.source, ProfilePhotoCrop())
            try {
                val pixel = ImageIO.read(java.io.File(photo.path)).getRGB(16, 16)
                assertTrue((pixel ushr 16 and 255) > 200)
                assertTrue((pixel and 255) < 40, "Only the red first frame may become the prepared JPEG.")
            } finally { photo.lease.release() }
        } finally { writer.dispose(); Files.deleteIfExists(path) }
    }

    @Test fun corruptImageExtensionStaysInOfficialBadPhotoBranch() = runBlocking<Unit> {
        val path = Files.createTempFile("avatar-content-", ".JPG")
        try {
            Files.write(path, byteArrayOf(1, 2, 3))
            assertFails { readProfileAvatarSelection(path.toString()) }
        } finally { Files.deleteIfExists(path) }
    }

    @Test fun cancelledImageReadCannotTurnIntoAVideoCallback() = runBlocking<Unit> {
        assertFailsWith<CancellationException> {
            readProfileAvatarSelection("ignored") { throw CancellationException() }
        }
    }

    @Test fun chooserOwnershipRejectsEarlierAttemptAndEveryCompletionAfterDisposal() {
        val owner = ProfileAvatarSelectionOwner()
        val first = owner.begin()
        assertTrue(owner.current(first))
        val second = owner.begin()
        assertFalse(owner.current(first)); assertTrue(owner.current(second))
        owner.close(); owner.close()
        assertFalse(owner.current(first)); assertFalse(owner.current(second))
        assertFalse(owner.current(owner.begin()))
    }
}
