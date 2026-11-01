package com.badnng.moe.media

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes

/** An actual open object, not a path-based identity observation. */
internal interface AutoMediaOwnedFile : AutoCloseable {
    val identity: String
    fun write(bytes: ByteArray, length: Int)
    fun read(bytes: ByteArray): Int
    fun replace(bytes: ByteArray)
    fun force()
    /** The caller closes this same handle immediately after requesting deletion. */
    fun markDelete()
}

internal interface AutoMediaOwnedFiles {
    fun createNew(path: Path): AutoMediaOwnedFile
    fun openRead(path: Path): AutoMediaOwnedFile
    fun openReadWrite(path: Path): AutoMediaOwnedFile
    fun openReadDelete(path: Path): AutoMediaOwnedFile
}

/** Windows-only protected IO. The pre-existing read-only identity helper is unchanged. */
internal object WindowsAutoMediaOwnedFiles : AutoMediaOwnedFiles {
    private const val FILE_SHARE_READ = 0x00000001
    private const val FILE_READ_ATTRIBUTES = 0x00000080
    private const val DELETE = 0x00010000
    private const val GENERIC_READ = -2147483648
    private const val GENERIC_WRITE = 0x40000000
    private const val CREATE_NEW = 1
    private const val OPEN_EXISTING = 3
    private const val FILE_ATTRIBUTE_NORMAL = 0x00000080
    private const val FILE_ATTRIBUTE_DIRECTORY = 0x00000010
    private const val FILE_ATTRIBUTE_REPARSE_POINT = 0x00000400
    private const val FILE_FLAG_OPEN_REPARSE_POINT = 0x00200000
    private const val FILE_ATTRIBUTE_TAG_INFO = 9
    private const val FILE_ID_INFO = 18
    private const val FILE_DISPOSITION_INFO = 4
    private val kernel by lazy { NativeLibrary.getInstance("kernel32") }
    private fun function(name: String) = kernel.getFunction(name, Function.ALT_CONVENTION)

    override fun createNew(path: Path) = open(path, GENERIC_READ or GENERIC_WRITE or FILE_READ_ATTRIBUTES, CREATE_NEW)
    override fun openRead(path: Path) = open(path, GENERIC_READ or FILE_READ_ATTRIBUTES, OPEN_EXISTING)
    override fun openReadWrite(path: Path) = open(path, GENERIC_READ or GENERIC_WRITE or FILE_READ_ATTRIBUTES, OPEN_EXISTING)
    override fun openReadDelete(path: Path) = open(path, GENERIC_READ or FILE_READ_ATTRIBUTES or DELETE, OPEN_EXISTING)

    private fun open(path: Path, access: Int, disposition: Int): AutoMediaOwnedFile {
        require(path.isAbsolute)
        val raw = function("CreateFileW").invokePointer(arrayOf(WString(extendedWindowsIdentityPath(path)), access,
            FILE_SHARE_READ, null, disposition, FILE_ATTRIBUTE_NORMAL or FILE_FLAG_OPEN_REPARSE_POINT, null))
        if (raw == null || Pointer.nativeValue(raw) == 0L || Pointer.nativeValue(raw) == -1L)
            throw IOException("Unable to acquire an exclusive automatic-copy file handle")
        try {
            val tag = Memory(8).use { buffer ->
                if (function("GetFileInformationByHandleEx").invokeInt(arrayOf(raw, FILE_ATTRIBUTE_TAG_INFO, buffer, 8)) == 0)
                    throw IOException("Unable to inspect automatic-copy file handle")
                buffer.getInt(0)
            }
            if ((tag and (FILE_ATTRIBUTE_DIRECTORY or FILE_ATTRIBUTE_REPARSE_POINT)) != 0)
                throw IOException("Automatic-copy handle is not an ordinary file")
            val identity = Memory(24).use { buffer ->
                if (function("GetFileInformationByHandleEx").invokeInt(arrayOf(raw, FILE_ID_INFO, buffer, 24)) == 0)
                    throw IOException("Unable to identify automatic-copy file handle")
                "win-file-id-v1:" + buffer.getByteArray(0, 24).joinToString("") { "%02x".format(it) }
            }
            return Handle(raw, identity)
        } catch (error: Throwable) {
            runCatching { function("CloseHandle").invokeInt(arrayOf(raw)) }
            throw error
        }
    }

    private class Handle(private val raw: Pointer, override val identity: String) : AutoMediaOwnedFile {
        private var closed = false
        private var deleteMarked = false
        override fun write(bytes: ByteArray, length: Int) {
            check(!closed && !deleteMarked && length in 0..bytes.size)
            var offset = 0
            while (offset < length) {
                val data = if (offset == 0 && length == bytes.size) bytes else bytes.copyOfRange(offset, length)
                val written = IntByReference()
                if (function("WriteFile").invokeInt(arrayOf(raw, data, data.size, written, Pointer.NULL)) == 0 ||
                    written.value !in 1..data.size)
                    throw IOException("Automatic-copy handle write failed")
                offset += written.value
            }
        }
        override fun read(bytes: ByteArray): Int {
            check(!closed && !deleteMarked)
            val count = IntByReference()
            if (function("ReadFile").invokeInt(arrayOf(raw, bytes, bytes.size, count, Pointer.NULL)) == 0)
                throw IOException("Automatic-copy handle read failed")
            if (count.value !in 0..bytes.size) throw IOException("Automatic-copy handle returned an invalid length")
            return if (count.value == 0) -1 else count.value
        }
        override fun force() {
            check(!closed && !deleteMarked)
            if (function("FlushFileBuffers").invokeInt(arrayOf(raw)) == 0) throw IOException("Automatic-copy flush failed")
        }
        override fun replace(bytes: ByteArray) {
            check(!closed && !deleteMarked)
            if (function("SetFilePointerEx").invokeInt(arrayOf(raw, 0L, Pointer.NULL, 0)) == 0)
                throw IOException("Automatic-copy metadata seek failed")
            write(bytes, bytes.size)
            if (function("SetEndOfFile").invokeInt(arrayOf(raw)) == 0) throw IOException("Automatic-copy metadata truncation failed")
            force()
        }
        override fun markDelete() {
            check(!closed && !deleteMarked)
            Memory(1).use { info ->
                info.setByte(0, 1)
                if (function("SetFileInformationByHandle").invokeInt(arrayOf(raw, FILE_DISPOSITION_INFO, info, 1)) == 0)
                    throw IOException("Automatic-copy handle deletion failed")
            }
            deleteMarked = true
        }
        override fun close() {
            if (closed) return
            closed = true
            if (function("CloseHandle").invokeInt(arrayOf(raw)) == 0) throw IOException("Automatic-copy handle close failed")
        }
    }
}

internal object UnsupportedAutoMediaOwnedFiles : AutoMediaOwnedFiles {
    private fun unavailable(): Nothing = throw IOException("Protected automatic-copy file handles are unavailable")
    override fun createNew(path: Path): AutoMediaOwnedFile = unavailable()
    override fun openRead(path: Path): AutoMediaOwnedFile = unavailable()
    override fun openReadWrite(path: Path): AutoMediaOwnedFile = unavailable()
    override fun openReadDelete(path: Path): AutoMediaOwnedFile = unavailable()
}

internal val defaultAutoMediaOwnedFiles: AutoMediaOwnedFiles =
    if (System.getProperty("os.name").startsWith("Windows", true)) WindowsAutoMediaOwnedFiles else UnsupportedAutoMediaOwnedFiles

/** Inspect and delete through the same held file object. A failed proof never falls back to path deletion. */
internal fun deleteExactAutoMediaStage(path: Path, identity: String?, files: AutoMediaOwnedFiles, check: () -> Unit = {}) {
    check()
    try { Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS) }
    catch (_: NoSuchFileException) { return }
    if (identity == null || !path.fileName.toString().startsWith(".nnggram-auto-") ||
        !path.fileName.toString().endsWith(".part")) throw AutoMediaStageAmbiguous()
    files.openReadDelete(path).use { held ->
        if (held.identity != identity) throw AutoMediaStageAmbiguous()
        check()
        held.markDelete()
    }
}
