package com.badnng.moe.notifications

import com.badnng.moe.settings.SettingsResult
import com.badnng.moe.settings.SavedNotificationSound

internal fun interface NotificationSoundLoad { fun close() }

internal data class PreparedNotificationAudio(
    val requestedSoundId: Long, val pcm: NotificationSoundPcm, val defaultFallback: Boolean = false,
) { override fun toString() = "PreparedNotificationAudio(<redacted>)" }

internal interface NotificationSoundSource {
    fun load(accountUserId: Long, soundId: Long, active: () -> Boolean,
        result: (SettingsResult<PreparedNotificationAudio>) -> Unit): NotificationSoundLoad
    fun invalidate()
    fun close()
    /** Opening the actual chooser may warm native bytes, without decoding or opening audio output. */
    fun warm(accountUserId: Long, sound: SavedNotificationSound, active: () -> Boolean,
        result: (SettingsResult<Unit>) -> Unit): NotificationSoundLoad {
        result(SettingsResult(Unit))
        return NotificationSoundLoad {}
    }
}

internal interface NotificationSoundPreview {
    fun play(accountUserId: Long, soundId: Long, volumePercent: Int, active: () -> Boolean,
        result: (SettingsResult<Boolean>) -> Unit): NotificationSoundLoad
    fun close()
}
