package com.badnng.moe

import com.badnng.moe.chat.*
import com.badnng.moe.media.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*

class ClipboardPastePreparationTest {
    @Test fun actualFileContentsChoosePhotoOrDocumentWithoutDeletingExplorerFiles() = runBlocking {
        val photo = Files.createTempFile("nnggram-paste-inspect-", ".txt")
        val file = Files.createTempFile("nnggram-paste-inspect-", ".png")
        try {
            check(ImageIO.write(BufferedImage(32, 20, BufferedImage.TYPE_INT_RGB), "png", photo.toFile()))
            Files.writeString(file, "synthetic plain text")
            val prepared = prepareClipboardBatch(ClipboardAttachmentContent.FileList(listOf(photo.toString(), file.toString())))
            assertEquals(listOf(true, false), prepared.items.map { it.asPhoto })
            assertEquals(32, prepared.items.first().attachment.width)
            prepared.close()
            assertTrue(Files.exists(photo) && Files.exists(file), "Clipboard files copied from Explorer never become owned temporary files.")
        } finally { Files.deleteIfExists(photo); Files.deleteIfExists(file) }
    }

    @Test fun screenshotOwnershipSurvivesUiDismissalUntilNativeAndRetainedDisplayHandlesRelease() = runBlocking {
        var closed = 0
        val staged = object : StagedClipboardImage {
            override val path = "synthetic-only.png"
            override val width = 20
            override val height = 20
            override fun close() { closed++ }
        }
        val prepared = prepareClipboardBatch(ClipboardAttachmentContent.PngImage(byteArrayOf(1)),
            stage = { staged }, inspect = { listOf(PreparedClipboardItem(LocalAttachment(AttachmentKind.FILE, staged.path, "native.png", 20), true)) })
        val display = prepared.retainForDisplay()
        val native = assertNotNull(prepared.items.single().lease?.retain())
        assertEquals("截图.png", prepared.items.single().attachment.fileName)
        prepared.close()
        assertEquals(0, closed)
        display.close()
        assertEquals(0, closed)
        native.release()
        native.release()
        prepared.close()
        assertEquals(1, closed)
    }

    @Test fun cancellationAfterStagingAndOversizedListsDoNotLeaveOwnedFiles() = runBlocking {
        var closed = 0
        var inspections = 0
        val staged = object : StagedClipboardImage {
            override val path = "synthetic.png"
            override val width = 20
            override val height = 20
            override fun close() { closed++ }
        }
        assertFailsWith<CancellationException> {
            prepareClipboardBatch(ClipboardAttachmentContent.PngImage(byteArrayOf(1)), stage = { staged }, inspect = { throw CancellationException() })
        }
        assertEquals(1, closed)
        assertFailsWith<IllegalArgumentException> {
            prepareClipboardBatch(ClipboardAttachmentContent.FileList(List(21) { "synthetic" }), inspect = { inspections++; emptyList() })
        }
        assertEquals(0, inspections)
    }
}
