package com.badnng.moe

import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

/** Native access is a recording fake; no actual account or system clipboard is touched. */
class WindowsProxyTextClipboardTest {
    @Test
    fun preparedTextPublishesExactUtf16OnlyAfterCurrentOwnerIsChecked() {
        val fake = FakeAccess()
        val clipboard = WindowsClipboard(123, fake)
        val value = "https://synthetic.invalid/代理-😀"
        val prepared = assertNotNull(clipboard.prepareProxyText(value))
        assertEquals(0, fake.opens)
        assertEquals(0, fake.publications)
        assertTrue(prepared.publishIfCurrent { true })
        assertEquals(1, fake.opens)
        assertEquals(1, fake.closes)
        assertEquals(1, fake.publications)
        assertEquals(setOf(WindowsClipboardFormats.UNICODE_TEXT), fake.contents.keys)
        assertTrue(WindowsClipboardFormats.decodeText(assertNotNull(fake.contents[WindowsClipboardFormats.UNICODE_TEXT])) == value)
        val exposedBuffer = assertNotNull(fake.originalBuffer)
        assertTrue(exposedBuffer.any { it != 0.toByte() })
        prepared.close()
        assertTrue(exposedBuffer.all { it == 0.toByte() }, "Close must clear the preparation's temporary bytes.")
        assertFalse(prepared.publishIfCurrent { true })
        assertEquals(1, fake.publications)
    }

    @Test
    fun ownerLostDuringOpenAndBusyClipboardLeavePreviousContentUntouched() {
        val fake = FakeAccess()
        val previous = byteArrayOf(4, 5, 6)
        fake.contents[999] = previous.copyOf()
        val clipboard = WindowsClipboard(123, fake)
        val prepared = assertNotNull(clipboard.prepareProxyText("https://synthetic.invalid/proxy"))
        val current = AtomicBoolean(true)
        fake.onOpen = { current.set(false) }
        assertFalse(prepared.publishIfCurrent(current::get))
        assertEquals(1, fake.opens)
        assertEquals(1, fake.closes)
        assertEquals(0, fake.publications)
        assertContentEquals(previous, fake.contents[999])
        fake.busy = true
        assertFalse(prepared.publishIfCurrent { true })
        assertEquals(2, fake.opens)
        assertEquals(1, fake.closes)
        assertEquals(0, fake.publications)
        assertContentEquals(previous, fake.contents[999])
        prepared.close()
    }

    @Test
    fun embeddedNulIsRejectedRatherThanPublishingATruncatedLink() {
        val fake = FakeAccess()
        val clipboard = WindowsClipboard(123, fake)
        assertNull(clipboard.prepareProxyText("https://synthetic.invalid/first\u0000hidden"))
        assertEquals(0, fake.opens)
        assertEquals(0, fake.publications)
    }

    private class FakeAccess : WindowsClipboardAccess {
        override val pngFormat = 0xc123
        override val ansiCharset: Charset = Charsets.UTF_8
        val contents = mutableMapOf<Int, ByteArray>()
        var originalBuffer: ByteArray? = null
        var opens = 0
        var closes = 0
        var publications = 0
        var busy = false
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
        override fun read(format: Int, maxBytes: Int): ByteArray? = error("unused")
        override fun publish(formats: Map<Int, ByteArray>) {
            check(opened)
            originalBuffer = formats[WindowsClipboardFormats.UNICODE_TEXT]
            contents.clear()
            formats.forEach { (format, bytes) -> contents[format] = bytes.copyOf() }
            publications++
        }
    }
}
