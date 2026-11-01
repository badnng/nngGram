package com.badnng.moe

import androidx.compose.runtime.Composable
import com.badnng.moe.settings.NotificationSoundLimits
import com.badnng.moe.settings.SettingsPreparedNotificationSound

/** Ownership of the prepared lease transfers to onPrepared; cancellation releases it locally. */
@Composable
internal expect fun SettingsNotificationSoundPicker(
    show: Boolean, limits: NotificationSoundLimits, onDismiss: () -> Unit,
    onPrepared: (SettingsPreparedNotificationSound) -> Unit,
)
