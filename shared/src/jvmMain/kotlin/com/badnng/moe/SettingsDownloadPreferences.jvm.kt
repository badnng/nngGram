package com.badnng.moe

import com.badnng.moe.tdlib.ConfigurationStore
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

private val noDownloadPreferenceChanges = MutableStateFlow(0L)
internal interface DownloadPreferencesAccess {
    val revision: StateFlow<Long> get() = noDownloadPreferenceChanges
    suspend fun read(): DownloadPreferencesRead
    suspend fun write(expected: SettingsDownloadPreferences, desired: SettingsDownloadPreferences): DownloadPreferencesWrite
    suspend fun writeIfCurrent(expected: SettingsDownloadPreferences, desired: SettingsDownloadPreferences,
        active: () -> Boolean): DownloadPreferencesWrite =
        if (active()) write(expected, desired) else DownloadPreferencesWrite.FAILED
}

internal class FileDownloadPreferencesAccess(
    private val path: () -> Path,
    private val replace: (Path, Path) -> Unit = ::replaceDownloadPreferences,
) : DownloadPreferencesAccess {
    private val changes = MutableStateFlow(0L)
    override val revision: StateFlow<Long> get() = changes
    override suspend fun read(): DownloadPreferencesRead = withContext(Dispatchers.IO) { readDownloadPreferences(path()) }
    override suspend fun write(expected: SettingsDownloadPreferences, desired: SettingsDownloadPreferences): DownloadPreferencesWrite =
        writeIfCurrent(expected, desired) { true }
    override suspend fun writeIfCurrent(expected: SettingsDownloadPreferences, desired: SettingsDownloadPreferences,
        active: () -> Boolean): DownloadPreferencesWrite = withContext(Dispatchers.IO) {
        // Emitted inside the IO block even if its caller was canceled while a move blocked.
        try { writeDownloadPreferences(path(), expected, desired, replace, active) }
        finally { changes.update { it + 1 } }
    }
}

/** Offline fixtures replace this boundary; production retains the actual device store and IO dispatcher. */
private val NativeDownloadPreferencesAccess: DownloadPreferencesAccess = FileDownloadPreferencesAccess(::downloadPreferencesPath)
internal val LocalDownloadPreferencesAccess = staticCompositionLocalOf<DownloadPreferencesAccess> { NativeDownloadPreferencesAccess }

internal fun downloadPreferencesPath(): Path = ConfigurationStore.default().path.toAbsolutePath()
    .resolveSibling("ui-downloads.properties")

internal sealed interface DownloadPreferencesRead {
    data class Value(val preferences: SettingsDownloadPreferences) : DownloadPreferencesRead
    data object Failed : DownloadPreferencesRead
}

/** A broken/unreadable store is visible; it must not silently disable the safe chooser default. */
internal fun readDownloadPreferences(path: Path): DownloadPreferencesRead = try {
    if (!Files.exists(path)) DownloadPreferencesRead.Value(SettingsDownloadPreferences())
    else if (!Files.isRegularFile(path) || Files.size(path) > 64 * 1024) DownloadPreferencesRead.Failed
    else {
        val values = Properties().apply { Files.newBufferedReader(path).use(::load) }
        if (values.getProperty("version") != "1") DownloadPreferencesRead.Failed
        else {
            val ask = values.getProperty("ask-every-time")?.toBooleanStrictOrNull()
            val mode = SettingsDownloadDirectory.entries.firstOrNull { it.name == values.getProperty("directory") }
            val custom = values.getProperty("custom-path").orEmpty()
            val preferences = if (ask != null && mode != null) SettingsDownloadPreferences(ask, mode, custom) else null
            if (preferences == null || !validDownloadPreferences(preferences)) DownloadPreferencesRead.Failed
            else DownloadPreferencesRead.Value(preferences)
        }
    }
} catch (_: Exception) { DownloadPreferencesRead.Failed }

internal fun validDownloadPreferences(value: SettingsDownloadPreferences): Boolean {
    if (value.customPath.length > 32_000 || value.customPath.any { it.isISOControl() }) return false
    if (value.customPath.isNotEmpty() && runCatching { Path.of(value.customPath).isAbsolute }.getOrDefault(false).not()) return false
    return value.directory != SettingsDownloadDirectory.CUSTOM || value.customPath.isNotBlank()
}

internal enum class DownloadPreferencesWrite { SAVED, CONFLICT, FAILED }

private val downloadPreferencesWriteLock = Any()

/** Compare before replace so a stale settings page cannot silently consume a newer device preference. */
internal fun writeDownloadPreferences(
    path: Path,
    expected: SettingsDownloadPreferences,
    desired: SettingsDownloadPreferences,
    replace: (Path, Path) -> Unit = ::replaceDownloadPreferences,
    active: () -> Boolean = { true },
): DownloadPreferencesWrite = synchronized(downloadPreferencesWriteLock) {
    if (!active() || !validDownloadPreferences(desired)) return@synchronized DownloadPreferencesWrite.FAILED
    val fresh = readDownloadPreferences(path) as? DownloadPreferencesRead.Value
        ?: return@synchronized DownloadPreferencesWrite.FAILED
    if (fresh.preferences != expected) return@synchronized DownloadPreferencesWrite.CONFLICT
    var temporary: Path? = null
    try {
        val destination = path.toAbsolutePath().normalize()
        Files.createDirectories(destination.parent)
        temporary = Files.createTempFile(destination.parent, "ui-downloads-", ".tmp")
        val values = Properties().apply {
            setProperty("version", "1")
            setProperty("ask-every-time", desired.askEveryTime.toString())
            setProperty("directory", desired.directory.name)
            setProperty("custom-path", desired.customPath)
        }
        Files.newBufferedWriter(temporary).use { values.store(it, "Local download destination settings") }
        if (!active()) return@synchronized DownloadPreferencesWrite.FAILED
        replace(temporary, destination)
        DownloadPreferencesWrite.SAVED
    } catch (_: Exception) { DownloadPreferencesWrite.FAILED }
    finally { temporary?.let { runCatching { Files.deleteIfExists(it) } } }
}

private fun replaceDownloadPreferences(temporary: Path, destination: Path) {
    try {
        Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
    }
}
