package com.badnng.moe

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowsClipboardFormatsTest {
    @Test
    fun chineseFilesUseWideDropFilesAndLegacyAnsiIsDecodedWithTheNativeCodePage() {
        val paths = listOf("C:\\合成目录\\截图.png", "\\\\server\\share\\合成文件.txt")
        val encoded = WindowsClipboardFormats.encodeFiles(paths)
        assertEquals(paths, WindowsClipboardFormats.decodeFiles(encoded, Charset.forName("GBK")))
        val ansiNames = (paths.first() + "\u0000\u0000").toByteArray(Charset.forName("GBK"))
        val ansi = ByteBuffer.allocate(20 + ansiNames.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(20).putInt(0).putInt(0).putInt(0).putInt(0).put(ansiNames).array()
        assertEquals(paths.take(1), WindowsClipboardFormats.decodeFiles(ansi, Charset.forName("GBK")))
    }

    @Test
    fun malformedOrUnboundedFileListsAreRejectedBeforeTheyCanBecomeAttachments() {
        val encoded = WindowsClipboardFormats.encodeFiles(listOf("C:\\synthetic.txt"))
        assertNull(WindowsClipboardFormats.decodeFiles(encoded.copyOf(encoded.size - 2), Charsets.UTF_8))
        val badOffset = encoded.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(0, Int.MAX_VALUE) }
        assertNull(WindowsClipboardFormats.decodeFiles(badOffset, Charsets.UTF_8))
        assertFailsWith<IllegalArgumentException> { WindowsClipboardFormats.encodeFiles(listOf("relative.txt")) }
        assertFailsWith<IllegalArgumentException> { WindowsClipboardFormats.encodeFiles(listOf("C:\\bad\u0000name.txt")) }
        assertFailsWith<IllegalArgumentException> { WindowsClipboardFormats.encodeFiles(List(65) { "C:\\synthetic-$it.txt" }) }
    }

    @Test
    fun bottomUp24BitDibRowsHonorPaddingAndAreReturnedInDisplayOrder() {
        val dib = ByteBuffer.allocate(40 + 16).order(ByteOrder.LITTLE_ENDIAN)
        dib.putInt(0, 40).putInt(4, 2).putInt(8, 2).putShort(12, 1).putShort(14, 24)
        // Bottom row blue/white, then top row red/green; each row ends with two padding bytes.
        dib.position(40)
        dib.put(byteArrayOf(-1, 0, 0, -1, -1, -1, 91, 92, 0, 0, -1, 0, -1, 0, 93, 94))
        val decoded = WindowsClipboardFormats.decodeDib(dib.array())
        assertEquals(2, decoded.width)
        assertEquals(2, decoded.height)
        assertContentEquals(intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt(), 0xff0000ff.toInt(), -1), decoded.argb)
    }

    @Test
    fun dibV5RoundTripsTopDownStraightAlphaAndExportsAnActualPng() {
        val raster = ClipboardRaster(2, 2, intArrayOf(0xffff0000.toInt(), 0x80402010.toInt(), 0, 0xff1e9fde.toInt()))
        val dib = WindowsClipboardFormats.encodeDibV5(raster)
        assertEquals(-2, ByteBuffer.wrap(dib).order(ByteOrder.LITTLE_ENDIAN).getInt(8))
        assertContentEquals(raster.argb, WindowsClipboardFormats.decodeDib(dib).argb)
        val png = WindowsClipboardFormats.rasterToPng(raster)
        assertEquals(2 to 2, WindowsClipboardFormats.validatePng(png))
        val decoded = WindowsClipboardFormats.pngToRaster(png)
        raster.argb.zip(decoded.argb).forEach { (expected, actual) ->
            for (shift in listOf(24, 16, 8, 0)) assertTrue(abs((expected ushr shift and 255) - (actual ushr shift and 255)) <= 1)
        }
    }

    @Test
    fun bitmapsRejectOverflowCompressionMissingPixelsAndOverlappingMasks() {
        val original = WindowsClipboardFormats.encodeDibV5(ClipboardRaster(1, 1, intArrayOf(-1)))
        fun invalid(offset: Int, value: Int): ByteArray = original.copyOf().also {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value)
        }
        for (bytes in listOf(invalid(4, Int.MAX_VALUE), invalid(8, Int.MIN_VALUE), invalid(16, 1),
            invalid(44, 0x00ff0000), invalid(112, 2).also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(116, 4) },
            original.copyOf(original.size - 1))) {
            assertFails { WindowsClipboardFormats.decodeDib(bytes) }
        }
        assertFails { WindowsClipboardFormats.validatePng(byteArrayOf(1, 2, 3)) }
    }

    @Test
    fun unicodeTextPreservesChineseAndRejectsAnUnterminatedNativeBuffer() {
        val sample = "合成剪贴板\nemoji 👍"
        assertEquals(sample, WindowsClipboardFormats.decodeText(WindowsClipboardFormats.encodeText(sample)))
        assertNull(WindowsClipboardFormats.decodeText(sample.toByteArray(Charsets.UTF_16LE)))
        assertEquals("合成", WindowsClipboardFormats.decodeText(WindowsClipboardFormats.encodeText("合成\u0000不可见")))
    }
}
