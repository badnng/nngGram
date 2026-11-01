package com.badnng.moe.notifications

import com.badnng.moe.settings.*

/** Actor-owned local preference boundary. No sound playback or native account mutation. */
internal interface NotificationSoundVolumeStore {
    fun read(accountUserId: Long, target: NotificationSoundVolumeTarget): SettingsResult<NotificationSoundVolumeReading>
    fun save(expected: NotificationSoundVolumeReading, overridePercent: Int?): SettingsResult<NotificationSoundVolumeReading>
    /** Real notification chat override, then real native category override, then app-wide volume. */
    fun resolve(accountUserId: Long, chatId: Long, scope: NotificationScope, globalVolume: Int): SettingsResult<Int>
}
