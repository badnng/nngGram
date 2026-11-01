package com.badnng.moe

import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

/** All access is fake: these tests never open a system clipboard or load a Win32 DLL. */
class WindowsProxyQrClipboardTest {
    @Test
    fun explicitQrCopyPublishesMatchingPngAndDibWithAQuietBorder() {
        val fake = FakeAccess()
        val clipboard = WindowsClipboard(123, fake)
        val modules = BooleanArray(21 * 21).apply { this[0] = true; this[20 * 21 + 20] = true }
        var checks = 0
        val prepared = assertNotNull(clipboard.prepareProxyQr(21, modules))
        assertEquals(0, fake.opens, "Preparation must never open the native clipboard.")
        assertTrue(prepared.publishIfCurrent { checks++; true })
        prepared.close()
        assertTrue(fake.originalBuffers.all { bytes -> bytes.all { it == 0.toByte() } },
            "Closing the preparation must clear its temporary image bytes.")
        assertFalse(prepared.publishIfCurrent { true }, "A closed preparation cannot publish again.")
        assertEquals(1, checks, "The current owner must be checked after clipboard open.")
        assertEquals(1, fake.opens)
        assertEquals(1, fake.closes)
        assertEquals(1, fake.publications)
        assertEquals(setOf(fake.pngFormat, WindowsClipboardFormats.DIB_V5), fake.contents.keys)
        val png = WindowsClipboardFormats.pngToRaster(assertNotNull(fake.contents[fake.pngFormat]))
        val dib = WindowsClipboardFormats.decodeDib(assertNotNull(fake.contents[WindowsClipboardFormats.DIB_V5]))
        assertEquals(116, png.width)
        assertEquals(116, png.height)
        assertEquals(png.width, dib.width)
        assertEquals(png.height, dib.height)
        assertContentEquals(png.argb, dib.argb)
        assertEquals(0xffffffff.toInt(), png.argb[0])
        assertEquals(0xff000000.toInt(), png.argb[16 * png.width + 16])
        assertEquals(0xffffffff.toInt(), png.argb[16 * png.width + 20])
        assertEquals(0xff000000.toInt(), png.argb[96 * png.width + 96])
        assertTrue(modules[0] && modules[20 * 21 + 20], "The host must not mutate its caller's matrix.")
    }

    @Test
    fun staleBeforeOrDuringOpenNeverPublishesOrClearsPreviousContent() {
        val fake = FakeAccess()
        val original = byteArrayOf(7, 8, 9)
        fake.contents[999] = original.copyOf()
        val clipboard = WindowsClipboard(123, fake)
        val modules = BooleanArray(21 * 21)
        val prepared = assertNotNull(clipboard.prepareProxyQr(21, modules))
        assertFalse(prepared.publishIfCurrent { false })
        assertEquals(1, fake.opens)
        val current = AtomicBoolean(true)
        fake.onOpen = { current.set(false) }
        assertFalse(prepared.publishIfCurrent(current::get))
        prepared.close()
        assertEquals(2, fake.opens)
        assertEquals(2, fake.closes)
        assertEquals(0, fake.publications)
        assertContentEquals(original, fake.contents[999])
    }

    @Test
    fun busyAndThrowingNativePublishAreFailuresWithoutAnAsyncRetry() {
        val fake = FakeAccess()
        val clipboard = WindowsClipboard(123, fake)
        val modules = BooleanArray(21 * 21)
        fake.busy = true
        val first = assertNotNull(clipboard.prepareProxyQr(21, modules))
        assertFalse(first.publishIfCurrent { true })
        first.close()
        assertEquals(1, fake.opens)
        assertEquals(0, fake.closes)
        assertEquals(0, fake.publications)
        fake.busy = false
        fake.failPublish = true
        val second = assertNotNull(clipboard.prepareProxyQr(21, modules))
        assertFalse(second.publishIfCurrent { true })
        second.close()
        assertEquals(2, fake.opens)
        assertEquals(1, fake.closes)
        assertEquals(0, fake.publications)
        assertTrue(fake.contents.isEmpty())
    }

    @Test
    fun malformedMatricesFailBeforeAnyNativeAccess() {
        val fake = FakeAccess()
        val clipboard = WindowsClipboard(123, fake)
        assertNull(clipboard.prepareProxyQr(20, BooleanArray(400)))
        assertNull(clipboard.prepareProxyQr(22, BooleanArray(484)))
        assertNull(clipboard.prepareProxyQr(178, BooleanArray(178 * 178)))
        assertNull(clipboard.prepareProxyQr(21, BooleanArray(440)))
        assertEquals(0, fake.opens)
        assertEquals(0, fake.publications)
    }

    private class FakeAccess : WindowsClipboardAccess {
        override val pngFormat = 0xc123
        override val ansiCharset: Charset = Charsets.UTF_8
        val contents = mutableMapOf<Int, ByteArray>()
        val originalBuffers = mutableListOf<ByteArray>()
        var opens = 0
        var closes = 0
        var publications = 0
        var busy = false
        var failPublish = false
        var onOpen: () -> Unit = {}
        private var opened = false
        override fun open(owner: Long): Boolean {
            assertEquals(123L, owner)
            opens++
            if (busy) return false
            opened = true
            onOpen()
            return true
        }
        override fun close() { check(opened); opened = false; closes++ }
        override fun available(format: Int) = format in contents
        override fun read(format: Int, maxBytes: Int): ByteArray? = error("No reads are needed")
        override fun publish(formats: Map<Int, ByteArray>) {
            check(opened)
            if (failPublish) error("synthetic publish failure")
            contents.clear()
            formats.forEach { (format, bytes) -> originalBuffers += bytes; contents[format] = bytes.copyOf() }
            publications++
        }
    }
}
