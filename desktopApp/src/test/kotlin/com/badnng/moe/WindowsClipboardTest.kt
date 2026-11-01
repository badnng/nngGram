@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@file:Suppress("DEPRECATION")

package com.badnng.moe

import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.asAwtTransferable
import androidx.compose.ui.text.AnnotatedString
import com.badnng.moe.media.ClipboardAttachmentContent
import kotlinx.coroutines.runBlocking
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowsClipboardTest {
    @Test
    fun filePasteTakesPriorityOverTextAndImageAndTheComposeEntryRetainsItsPureContainers() = runBlocking {
        val fake = FakeClipboard()
        val paths = listOf("C:\\合成目录\\截图.png", "D:\\synthetic.txt")
        fake.contents[WindowsClipboardFormats.FILE_DROP] = WindowsClipboardFormats.encodeFiles(paths)
        fake.contents[WindowsClipboardFormats.UNICODE_TEXT] = WindowsClipboardFormats.encodeText("合成文字")
        fake.contents[fake.pngFormat] = byteArrayOf(1, 2, 3)
        val clipboard = WindowsClipboard(123, fake)
        assertTrue(clipboard.hasAttachments())
        assertEquals(0, fake.opens, "Availability must not acquire the system clipboard.")
        assertEquals(paths, assertIs<ClipboardAttachmentContent.FileList>(clipboard.readAttachments()).paths)
        val entry = assertNotNull(clipboard.getClipEntry()?.asAwtTransferable)
        assertEquals(paths, (entry.getTransferData(DataFlavor.javaFileListFlavor) as List<*>).map { (it as File).path })
        assertEquals("合成文字", entry.getTransferData(DataFlavor.stringFlavor))
        assertEquals(fake.opens, fake.closes)
        assertFalse(fake.readFormats.contains(fake.pngFormat), "File paste must not decode a competing image.")
    }

    @Test
    fun corruptPngFallsBackToDibAndValidPngIsExposedWithoutAnAwtToolkit() = runBlocking {
        val fake = FakeClipboard()
        val raster = ClipboardRaster(2, 1, intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt()))
        fake.contents[fake.pngFormat] = byteArrayOf(1, 2, 3)
        fake.contents[WindowsClipboardFormats.DIB] = WindowsClipboardFormats.encodeDibV5(raster)
        val clipboard = WindowsClipboard(123, fake)
        val payload = assertIs<ClipboardAttachmentContent.PngImage>(clipboard.readAttachments())
        assertContentEquals(raster.argb, WindowsClipboardFormats.pngToRaster(payload.bytes).argb)
        val transferable = assertNotNull(clipboard.getClipEntry()?.asAwtTransferable)
        assertTrue(transferable.isDataFlavorSupported(PNG_CLIPBOARD_FLAVOR))
        val stream = assertIs<InputStream>(transferable.getTransferData(PNG_CLIPBOARD_FLAVOR))
        assertEquals(2 to 1, WindowsClipboardFormats.validatePng(stream.use { it.readAllBytes() }))
        assertEquals(fake.opens, fake.closes)
    }

    @Test
    fun copyImagePublishesPngAndCompatibleDibAndRejectsInvalidInputBeforeClearingOldContent() = runBlocking {
        val fake = FakeClipboard()
        val clipboard = WindowsClipboard(123, fake)
        val png = WindowsClipboardFormats.rasterToPng(ClipboardRaster(2, 1, intArrayOf(-1, 0xff398ad1.toInt())))
        clipboard.writePng(png)
        assertContentEquals(png, fake.contents[fake.pngFormat])
        assertEquals(2, WindowsClipboardFormats.decodeDib(assertNotNull(fake.contents[WindowsClipboardFormats.DIB_V5])).width)
        val publications = fake.publications
        assertFailsWith<IllegalArgumentException> { clipboard.writePng(byteArrayOf(1, 2, 3)) }
        assertEquals(publications, fake.publications)
        assertContentEquals(png, fake.contents[fake.pngFormat])
        clipboard.setClipEntry(clipboard.getClipEntry())
        assertContentEquals(png, fake.contents[fake.pngFormat])
    }

    @Test
    fun copiedFilesAndExistingTextUseNativeFormatsAndNullExplicitlyClearsThem() = runBlocking {
        val fake = FakeClipboard()
        val clipboard = WindowsClipboard(123, fake)
        val paths = listOf("C:\\synthetic.bin")
        clipboard.writeFiles(paths)
        assertEquals(paths, WindowsClipboardFormats.decodeFiles(assertNotNull(fake.contents[WindowsClipboardFormats.FILE_DROP]), fake.ansiCharset))
        clipboard.setClipEntry(clipboard.getClipEntry())
        assertEquals(paths, WindowsClipboardFormats.decodeFiles(assertNotNull(fake.contents[WindowsClipboardFormats.FILE_DROP]), fake.ansiCharset))
        clipboard.setClipEntry(ClipEntry(StringSelection("合成复制内容")))
        assertFalse(clipboard.hasAttachments())
        assertEquals("合成复制内容", clipboard.manager.getText()?.text)
        clipboard.manager.setText(AnnotatedString("合成第二份内容"))
        assertEquals("合成第二份内容", clipboard.getClipEntry()?.asAwtTransferable?.getTransferData(DataFlavor.stringFlavor))
        clipboard.setClipEntry(null)
        assertTrue(fake.contents.isEmpty())
        assertNull(clipboard.getClipEntry())
        assertEquals(fake.opens, fake.closes)
    }

    @Test
    fun transientLocksAreRetriedAndPermanentCopyFailureNeverReportsSuccess() = runBlocking {
        val fake = FakeClipboard()
        fake.busyOpens = 2
        val clipboard = WindowsClipboard(123, fake)
        clipboard.writeFiles(listOf("C:\\synthetic.txt"))
        assertEquals(3, fake.opens)
        assertEquals(1, fake.closes)
        fake.busyOpens = 6
        val before = fake.publications
        assertFailsWith<ClipboardBusy> { clipboard.writeFiles(listOf("C:\\another-synthetic.txt")) }
        assertEquals(before, fake.publications)
    }

    private class FakeClipboard : WindowsClipboardAccess {
        override val pngFormat = 0xc123
        override val ansiCharset: Charset = Charset.forName("GBK")
        val contents = mutableMapOf<Int, ByteArray>()
        val readFormats = mutableListOf<Int>()
        var opens = 0
        var closes = 0
        var publications = 0
        var busyOpens = 0
        private var opened = false
        override fun open(owner: Long): Boolean {
            assertEquals(123L, owner)
            opens++
            if (busyOpens > 0) { busyOpens--; return false }
            assertFalse(opened)
            opened = true
            return true
        }
        override fun close() { assertTrue(opened); opened = false; closes++ }
        override fun available(format: Int) = format in contents
        override fun read(format: Int, maxBytes: Int): ByteArray? {
            assertTrue(opened)
            readFormats += format
            return contents[format]?.takeIf { it.size <= maxBytes }?.copyOf()
        }
        override fun publish(formats: Map<Int, ByteArray>) {
            assertTrue(opened)
            contents.clear()
            contents.putAll(formats.mapValues { it.value.copyOf() })
            publications++
        }
    }
}
