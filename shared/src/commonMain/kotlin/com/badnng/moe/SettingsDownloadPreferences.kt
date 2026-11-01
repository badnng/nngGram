package com.badnng.moe

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

internal enum class SettingsDownloadDirectory { DOWNLOADS, ACCOUNT_TEMP, CUSTOM }

/** Global device preference. Account-temporary resolves against the currently active data root. */
@Immutable
internal data class SettingsDownloadPreferences(
    val askEveryTime: Boolean = false,
    val directory: SettingsDownloadDirectory = SettingsDownloadDirectory.DOWNLOADS,
    val customPath: String = "",
) {
    override fun toString(): String = "SettingsDownloadPreferences(askEveryTime=$askEveryTime, directory=$directory, customPath=<local>)"
}

/** Supplied by App from the active RuntimeSnapshot, never inferred by reading configuration credentials. */
internal val LocalDownloadAccountDataPath = staticCompositionLocalOf<String?> { null }
/** Real native user ID; a runtime generation is not a stable account-directory identity. */
internal val LocalDownloadAccountUserId = staticCompositionLocalOf<Long?> { null }

@Composable
internal expect fun SettingsDownloadControls(generation: Long, accountDataPath: String?)
