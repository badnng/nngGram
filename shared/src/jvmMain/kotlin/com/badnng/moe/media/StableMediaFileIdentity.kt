package com.badnng.moe.media

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.WString
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** Windows BasicFileAttributes.fileKey() is always null, including on ordinary NTFS. */
internal fun stableMediaFileIdentity(path: Path): String? {
    val nioKey = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey()
    if (nioKey != null) return nioKey.toString()
    return if (System.getProperty("os.name").startsWith("Windows", true)) windowsIdentity.identity(path) else null
}

private val windowsIdentity by lazy { WindowsMediaFileIdentityReader(JnaWindowsFileIdentityAccess) }

/** Only FILE_READ_ATTRIBUTES is requested. No bytes, write/delete rights or directory scan. */
internal interface WindowsFileIdentityAccess {
    fun openAttributes(path: String): Long?
    /** Exact FILE_ID_INFO: 8-byte volume serial followed by the full 128-bit identifier. */
    fun fileIdInfo(handle: Long): ByteArray?
    fun close(handle: Long)
}

internal class WindowsMediaFileIdentityReader(private val access: WindowsFileIdentityAccess) {
    fun identity(path: Path): String? {
        if (!path.isAbsolute) return null
        return runCatching {
            val handle = access.openAttributes(extendedWindowsIdentityPath(path))?.takeUnless { it == 0L || it == -1L }
                ?: return@runCatching null
            try {
                val bytes = access.fileIdInfo(handle)?.takeIf { it.size == 24 } ?: return@runCatching null
                // Versioned, architecture-independent bytes; neither a filename nor a
                // content hash can replace the filesystem's actual identity.
                "win-file-id-v1:" + bytes.joinToString("") { "%02x".format(it) }
            } finally { access.close(handle) }
        }.getOrNull()
    }
}

internal fun extendedWindowsIdentityPath(path: Path): String {
    val value = path.toAbsolutePath().normalize().toString()
    return when {
        value.startsWith("\\\\?\\") -> value
        value.startsWith("\\\\") -> "\\\\?\\UNC\\${value.drop(2)}"
        else -> "\\\\?\\$value"
    }
}

private object JnaWindowsFileIdentityAccess : WindowsFileIdentityAccess {
    private val kernel by lazy { NativeLibrary.getInstance("kernel32") }
    override fun openAttributes(path: String): Long? {
        val handle = kernel.getFunction("CreateFileW", Function.ALT_CONVENTION).invokePointer(arrayOf(
            WString(path), 0x0080, 0x0007, null, 3, 0x0220_0000, null,
            // FILE_READ_ATTRIBUTES, SHARE_READ|WRITE|DELETE, OPEN_EXISTING,
            // BACKUP_SEMANTICS (directories) | OPEN_REPARSE_POINT (no-follow).
        ))
        return handle?.let { Pointer.nativeValue(it) }?.takeUnless { it == 0L || it == -1L }
    }

    override fun fileIdInfo(handle: Long): ByteArray? = Memory(24).use { info ->
        val success = kernel.getFunction("GetFileInformationByHandleEx", Function.ALT_CONVENTION)
            .invokeInt(arrayOf(Pointer(handle), 18, info, 24)) // FileIdInfo = 0x12
        if (success == 0) null else info.getByteArray(0, 24)
    }

    override fun close(handle: Long) {
        check(kernel.getFunction("CloseHandle", Function.ALT_CONVENTION).invokeInt(arrayOf(Pointer(handle))) != 0)
    }
}
