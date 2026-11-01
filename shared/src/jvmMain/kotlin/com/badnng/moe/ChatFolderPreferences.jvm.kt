package com.badnng.moe

import com.badnng.moe.tdlib.ConfigurationStore
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties

internal actual fun loadFolderPreferences(): ChatFolderPreferences = try {
    readFolderPreferences(folderPreferencesPath())
} catch (_: Exception) { ChatFolderPreferences() }

internal actual fun saveFolderPreferences(preferences: ChatFolderPreferences): Boolean = try {
    writeFolderPreferences(folderPreferencesPath(), preferences)
} catch (_: Exception) { false }

private fun folderPreferencesPath(): Path =
    ConfigurationStore.default().path.toAbsolutePath().resolveSibling("ui-folders.properties")

internal fun readFolderPreferences(path: Path): ChatFolderPreferences {
    if (!Files.isRegularFile(path)) return ChatFolderPreferences()
    val values = Properties().apply { Files.newBufferedReader(path).use(::load) }
    return ChatFolderPreferences(
        placement = FolderTabPlacement.entries.firstOrNull { it.name == values.getProperty("placement") } ?: FolderTabPlacement.LEFT,
        appearance = FolderTabAppearance.entries.firstOrNull { it.name == values.getProperty("appearance") } ?: FolderTabAppearance.DEFAULT,
    )
}

internal fun writeFolderPreferences(path: Path, preferences: ChatFolderPreferences): Boolean {
    var temporary: Path? = null
    return try {
        val parent = path.toAbsolutePath().parent
        Files.createDirectories(parent)
        temporary = Files.createTempFile(parent, "ui-folders-", ".tmp")
        val values = Properties().apply {
            setProperty("placement", preferences.placement.name)
            setProperty("appearance", preferences.appearance.name)
        }
        Files.newBufferedWriter(temporary).use { values.store(it, "Local folder presentation") }
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
        }
        true
    } catch (_: Exception) { false }
    finally { temporary?.let { try { Files.deleteIfExists(it) } catch (_: Exception) { } } }
}
