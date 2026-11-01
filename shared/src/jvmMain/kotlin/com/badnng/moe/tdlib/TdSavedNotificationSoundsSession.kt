package com.badnng.moe.tdlib

import com.badnng.moe.settings.*

/** Metadata and account mutation only. Byte acquisition and audio output have separate ownership. */
interface TdSavedNotificationSoundsSession {
    fun load(active: () -> Boolean, result: (SettingsResult<SavedNotificationSoundsData>) -> Unit)
    fun read(accountUserId: Long, soundId: Long, active: () -> Boolean,
        result: (SettingsResult<SavedNotificationSound>) -> Unit)
    fun add(expected: SavedNotificationSoundsData, prepared: PreparedNotificationSoundUpload, active: () -> Boolean,
        result: (SavedNotificationSoundMutationResult) -> Unit)
    fun remove(accountUserId: Long, expected: SavedNotificationSound, active: () -> Boolean,
        result: (SavedNotificationSoundMutationResult) -> Unit)
    fun observe(observer: (() -> Unit)?) {}
    /** Checks only this client's current cached revision; never claims server confirmation. */
    fun isCurrent(data: SavedNotificationSoundsData): Boolean = false
    fun retire() {}
}
