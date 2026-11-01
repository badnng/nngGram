package com.badnng.moe

import com.sun.jna.Function
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.WString
import java.nio.charset.Charset

/** The fake implementation used by tests never loads a DLL or touches the OS clipboard. */
internal interface WindowsClipboardAccess {
    val pngFormat: Int
    val ansiCharset: Charset
    fun open(owner: Long): Boolean
    fun close()
    fun available(format: Int): Boolean
    fun read(format: Int, maxBytes: Int): ByteArray?
    fun publish(formats: Map<Int, ByteArray>)
}

internal class Win32ClipboardAccess : WindowsClipboardAccess {
    private val user = NativeLibrary.getInstance("user32")
    private val kernel = NativeLibrary.getInstance("kernel32")
    override val pngFormat = user.function("RegisterClipboardFormatW").invokeInt(arrayOf(WString("PNG"))).also {
        check(it != 0) { "Cannot register native image clipboard format" }
    }
    override val ansiCharset: Charset = runCatching { Charset.forName(System.getProperty("native.encoding")) }
        .getOrElse { Charset.forName("windows-1252") }

    override fun open(owner: Long): Boolean = user.function("OpenClipboard").invokeInt(arrayOf(Pointer(owner))) != 0
    override fun close() { user.function("CloseClipboard").invokeInt(emptyArray()) }
    override fun available(format: Int): Boolean = user.function("IsClipboardFormatAvailable").invokeInt(arrayOf(format)) != 0

    override fun read(format: Int, maxBytes: Int): ByteArray? {
        if (!available(format)) return null
        val memory = user.function("GetClipboardData").invokePointer(arrayOf(format)) ?: return null
        val size = kernel.function("GlobalSize").invokeLong(arrayOf(memory))
        if (size !in 1L..maxBytes.toLong()) return null
        val data = kernel.function("GlobalLock").invokePointer(arrayOf(memory)) ?: return null
        return try { data.getByteArray(0, size.toInt()) }
        finally { kernel.function("GlobalUnlock").invokeInt(arrayOf(memory)) }
    }

    override fun publish(formats: Map<Int, ByteArray>) {
        val owned = linkedMapOf<Int, Pointer>()
        try {
            // Prepare every handle before EmptyClipboard. A failed allocation does
            // not destroy the previous clipboard, and every untransferred handle
            // remains ours to release.
            for ((format, bytes) in formats) {
                require(bytes.isNotEmpty() && bytes.size <= WindowsClipboardFormats.MAX_IMAGE_BYTES)
                val memory = checkNotNull(kernel.function("GlobalAlloc").invokePointer(arrayOf(2, bytes.size.toLong())))
                owned[format] = memory
                val data = checkNotNull(kernel.function("GlobalLock").invokePointer(arrayOf(memory)))
                try { data.write(0, bytes, 0, bytes.size) }
                finally { kernel.function("GlobalUnlock").invokeInt(arrayOf(memory)) }
            }
            check(user.function("EmptyClipboard").invokeInt(emptyArray()) != 0)
            for ((format, memory) in owned.toMap()) {
                checkNotNull(user.function("SetClipboardData").invokePointer(arrayOf(format, memory)))
                owned.remove(format) // Windows now owns this GMEM_MOVEABLE HGLOBAL.
            }
        } finally {
            owned.values.forEach { kernel.function("GlobalFree").invokePointer(arrayOf(it)) }
        }
    }

    private fun NativeLibrary.function(name: String) = getFunction(name, Function.ALT_CONVENTION)
}
