package com.badnng.moe.media

import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AttachmentClipboardTest {
    @Test
    fun stagedScreenshotOwnsOnlyItsGeneratedPngAndCloseIsIdempotent() = runBlocking {
        val png = syntheticPng()
        val staged = stageClipboardImage(png)
        val file = Path.of(staged.path)
        try {
            assertEquals(3, staged.width)
            assertEquals(2, staged.height)
            assertTrue(Files.isRegularFile(file))
            assertContentEquals(png, Files.readAllBytes(file))
            assertEquals("StagedClipboardImage(<redacted>)", staged.toString())
        } finally {
            staged.close()
            staged.close()
        }
        assertFalse(Files.exists(file))
    }

    @Test
    fun copyingALocalImageEncodesPngWithoutOwningOrDeletingItsSourceFile() = runBlocking {
        val source = Files.createTempFile("nnggram-copy-image-test-", ".png")
        val bytes = syntheticPng()
        try {
            Files.write(source, bytes)
            val encoded = clipboardImagePng(source.toString())
            val staged = stageClipboardImage(encoded)
            try {
                assertEquals(3, staged.width)
                assertEquals(2, staged.height)
                assertTrue(staged.path != source.toString())
            } finally { staged.close() }
            assertContentEquals(bytes, Files.readAllBytes(source), "Clipboard copy must never consume the user's file.")
        } finally { Files.deleteIfExists(source) }
    }

    @Test
    fun invalidClipboardBytesAndNonImageFilesCannotBecomeSendableScreenshots() = runBlocking {
        assertFails { stageClipboardImage(byteArrayOf(1, 2, 3)) }
        val source = Files.createTempFile("nnggram-copy-invalid-test-", ".bin")
        try {
            Files.write(source, byteArrayOf(1, 2, 3))
            assertFails { clipboardImagePng(source.toString()) }
            assertTrue(Files.exists(source))
        } finally { Files.deleteIfExists(source) }
    }

    @Test
    fun attachmentPayloadsRedactPathsAndImageBytes() {
        assertEquals("ClipboardAttachmentContent.FileList(<redacted>)",
            ClipboardAttachmentContent.FileList(listOf("C:\\synthetic-private.png")).toString())
        assertEquals("ClipboardAttachmentContent.PngImage(<redacted>)",
            ClipboardAttachmentContent.PngImage(byteArrayOf(1, 2, 3)).toString())
    }

    private fun syntheticPng(): ByteArray = Surface.makeRasterN32Premul(3, 2).use { surface ->
        surface.canvas.clear(0xff3dbce8.toInt())
        surface.makeImageSnapshot().use { image ->
            checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).use { it.bytes }
        }
    }
}
