package com.badnng.moe.media

import com.badnng.moe.SettingsDownloadDirectory
import com.badnng.moe.SettingsDownloadPreferences
import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import androidx.compose.runtime.staticCompositionLocalOf
import java.nio.file.Files
import java.nio.file.Path

/** No directory is created while displaying settings. Resolution and creation are separate user actions. */
internal data class DownloadDestinationRoots(val downloads: Path?, val accountTemporary: Path?)

/** Offline renderers supply synthetic roots and never read the machine's KnownFolder path. */
internal val LocalDownloadDestinationRootsResolver = staticCompositionLocalOf<(String?, Long?) -> DownloadDestinationRoots> {
    ::downloadDestinationRoots
}

internal fun accountTemporaryDownloadDirectory(accountDataPath: String?, accountUserId: Long?): Path? {
    if (accountUserId == null || accountUserId <= 0) return null
    return accountDataPath?.let { runCatching { Path.of(it) }.getOrNull() }
        ?.takeIf { it.isAbsolute }?.normalize()?.resolve("downloads-temp")?.resolve("user-$accountUserId")
}

internal fun downloadDestinationRoots(accountDataPath: String?, accountUserId: Long?): DownloadDestinationRoots {
    val account = accountTemporaryDownloadDirectory(accountDataPath, accountUserId)
    val downloads = knownWindowsDownloadsDirectory()?.resolve("nngGram")
    return DownloadDestinationRoots(downloads, account)
}

internal fun resolveDownloadDirectory(value: SettingsDownloadPreferences, roots: DownloadDestinationRoots): Path? =
    when (value.directory) {
        SettingsDownloadDirectory.DOWNLOADS -> roots.downloads ?: roots.accountTemporary
        SettingsDownloadDirectory.ACCOUNT_TEMP -> roots.accountTemporary
        SettingsDownloadDirectory.CUSTOM -> runCatching { Path.of(value.customPath) }.getOrNull()?.takeIf { it.isAbsolute }
    }?.toAbsolutePath()?.normalize()

/** IO-only validation; a redirected Downloads or account-temp alias is not implicitly trusted. */
internal fun resolveCheckedDownloadDirectory(value: SettingsDownloadPreferences, roots: DownloadDestinationRoots,
    boundary: SaveTargetBoundary = NativeSaveTargets.capture(),
): Path? = resolveDownloadDirectory(value, roots)?.also { NativeSaveTargets.protect(boundary).require(it) }

/** FOLDERID_Downloads is queried read-only. This follows relocated/localized Downloads folders. */
private fun knownWindowsDownloadsDirectory(): Path? {
    if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        return Path.of(System.getProperty("user.home"), "Downloads").takeIf { Files.isDirectory(it) }
    }
    return try {
        val guid = Memory(16).apply {
            setInt(0, 0x374DE290)
            setShort(4, 0x123F.toShort())
            setShort(6, 0x4565.toShort())
            write(8, byteArrayOf(0x91.toByte(), 0x64, 0x39, 0xC4.toByte(), 0x92.toByte(), 0x5E, 0x46, 0x7B), 0, 8)
        }
        val output = PointerByReference()
        val shell = NativeLibrary.getInstance("shell32").getFunction("SHGetKnownFolderPath", Function.ALT_CONVENTION)
        val status = shell.invokeInt(arrayOf(guid, 0, Pointer.NULL, output))
        val pointer = output.value
        try {
            if (status != 0 || pointer == null) null else Path.of(pointer.getWideString(0)).takeIf { it.isAbsolute }
        } finally {
            if (pointer != null) NativeLibrary.getInstance("ole32").getFunction("CoTaskMemFree", Function.ALT_CONVENTION)
                .invokeVoid(arrayOf(pointer))
        }
    } catch (_: Exception) { null }
}
