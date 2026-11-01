package com.badnng.moe

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.badnng.moe.settings.SettingsPreparedProfileAnimation
import com.badnng.moe.settings.SettingsProfileExtrasSnapshot
import com.badnng.moe.settings.SettingsProfileExtrasToken

/** A new object per opening, even when the account and expected photo identity are unchanged. */
internal class SettingsProfileVideoOpening(
    val parent: SettingsProfileExtrasToken,
    val expectedPhotoId: Long?,
    val initialPath: String? = null,
) {
    val lease = settingsProfileExtrasPageLease()
    var submissionPending by mutableStateOf(false)
    fun close() = lease.close()
}

@Composable
internal expect fun SettingsProfileVideoPicker(
    opening: SettingsProfileVideoOpening?, snapshot: SettingsProfileExtrasSnapshot, enabled: Boolean,
    onDismiss: (SettingsProfileVideoOpening) -> Unit,
    onUpload: (SettingsProfileVideoOpening, SettingsPreparedProfileAnimation, (Long?) -> Unit) -> Unit,
)
