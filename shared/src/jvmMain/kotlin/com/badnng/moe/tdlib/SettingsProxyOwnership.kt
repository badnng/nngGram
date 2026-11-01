package com.badnng.moe.tdlib

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties

/** Only the ownership marker is local. Proxy identities and credentials remain in TDLib's database. */
class SettingsProxyOwnership(private val path: Path) {
    fun usesNativeSelection(): Boolean = try {
        if (!Files.isRegularFile(path) || Files.size(path) > MAX_MARKER_BYTES) false
        else {
            val content = Files.readString(path)
            if (content.length > MAX_MARKER_BYTES) false else {
                val values = Properties().apply { content.reader().use(::load) }
                values.stringPropertyNames() == setOf("version", "owner") &&
                    values.getProperty("version") == "1" && values.getProperty("owner") == "tdlib"
            }
        }
    } catch (_: IOException) { false }
    catch (_: IllegalArgumentException) { false }
    catch (_: SecurityException) { false }

    fun markNativeSelection(): Boolean {
        var temporary: Path? = null
        return try {
            val destination = path.toAbsolutePath()
            val parent = destination.parent
            Files.createDirectories(parent)
            temporary = Files.createTempFile(parent, "settings-connection-", ".tmp")
            Files.writeString(temporary, "version=1\nowner=tdlib\n")
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
            }
            true
        } catch (_: IOException) { false }
        catch (_: SecurityException) { false }
        finally {
            temporary?.let {
                try { Files.deleteIfExists(it) } catch (_: IOException) { } catch (_: SecurityException) { }
            }
        }
    }

    private companion object {
        const val MAX_MARKER_BYTES = 128L
    }
}
