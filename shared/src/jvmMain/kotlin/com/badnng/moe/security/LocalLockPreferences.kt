package com.badnng.moe.security

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes

internal sealed interface LocalLockPreferenceRead {
    class Value(val seconds: Long) : LocalLockPreferenceRead
    data object Failure : LocalLockPreferenceRead
}

internal interface LocalLockPreferences {
    fun read(): LocalLockPreferenceRead
    fun write(seconds: Long): Boolean
}

/** No passwords or keys. Pending files are retained as an explicit uncertain write. */
internal class FileLocalLockPreferences(private val root: LocalProtectionRoot) : LocalLockPreferences {
    private val path = root.path.resolve("local-protection.settings")
    private val pending = root.path.resolve("local-protection.settings.pending")

    override fun read(): LocalLockPreferenceRead {
        return try {
            if (!root.isCurrent() || attributes(pending) != null) return LocalLockPreferenceRead.Failure
            val existing = attributes(path) ?: return LocalLockPreferenceRead.Value(3600)
            if (!existing.isRegularFile || existing.isSymbolicLink) return LocalLockPreferenceRead.Failure
            val bytes = bounded(path) ?: return LocalLockPreferenceRead.Failure
            val text = bytes.toString(Charsets.US_ASCII)
            val match = Regex("^version=1\\nseconds=([0-9]{1,10})\\n$").matchEntire(text)
                ?: return LocalLockPreferenceRead.Failure
            val seconds = match.groupValues[1].toLongOrNull() ?: return LocalLockPreferenceRead.Failure
            if (!validAutoLockSeconds(seconds) || !root.isCurrent()) LocalLockPreferenceRead.Failure
            else LocalLockPreferenceRead.Value(seconds)
        } catch (_: IOException) {
            LocalLockPreferenceRead.Failure
        } catch (_: RuntimeException) {
            LocalLockPreferenceRead.Failure
        }
    }

    override fun write(seconds: Long): Boolean {
        return try {
            if (!validAutoLockSeconds(seconds) || !root.isCurrent() || attributes(pending) != null) return false
            val existing = attributes(path)
            if (existing != null && (!existing.isRegularFile || existing.isSymbolicLink)) return false
            val bytes = "version=1\nseconds=$seconds\n".toByteArray(Charsets.US_ASCII)
            FileChannel.open(pending, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { channel ->
                val input = ByteBuffer.wrap(bytes)
                while (input.hasRemaining()) {
                    if (channel.write(input) <= 0) throw IOException("write unavailable")
                }
                channel.force(true)
            }
            val verified = bounded(pending) ?: return false
            if (!verified.contentEquals(bytes) || !root.isCurrent()) return false
            Files.move(pending, path, ATOMIC_MOVE, REPLACE_EXISTING)
            val committed = bounded(path) ?: return false
            root.isCurrent() && attributes(pending) == null && committed.contentEquals(bytes)
        } catch (_: IOException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun attributes(path: Path): BasicFileAttributes? = try {
        Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) {
        null
    }

    private fun bounded(path: Path): ByteArray? {
        val buffer = ByteBuffer.allocate(65)
        FileChannel.open(path, READ, NOFOLLOW_LINKS).use { channel ->
            while (buffer.hasRemaining()) {
                val count = channel.read(buffer)
                if (count < 0) break
                if (count == 0) throw IOException("read unavailable")
            }
        }
        if (buffer.position() == 65) return null
        return buffer.array().copyOf(buffer.position())
    }
}

internal fun validAutoLockSeconds(seconds: Long): Boolean =
    seconds in 60L..Int.MAX_VALUE.toLong() && seconds % 60 == 0L
