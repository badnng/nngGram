package com.badnng.moe.settings

/** Local session preferences, independent of TDLib sound identities and server settings. */
sealed interface NotificationSoundVolumeTarget {
    data class Scope(val scope: NotificationScope) : NotificationSoundVolumeTarget
    data class Chat(val chatId: Long) : NotificationSoundVolumeTarget {
        override fun toString() = "NotificationSoundVolumeTarget.Chat(<redacted>)"
    }
}

/** Null is unset. Merely displaying 100% must not create an override. */
data class NotificationSoundVolumeReading(
    val accountUserId: Long,
    val target: NotificationSoundVolumeTarget,
    val overridePercent: Int?,
    val revision: Long,
) {
    override fun toString() = "NotificationSoundVolumeReading(<redacted>)"
}
