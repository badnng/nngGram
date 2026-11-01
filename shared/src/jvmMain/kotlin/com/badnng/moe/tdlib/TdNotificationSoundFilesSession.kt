package com.badnng.moe.tdlib

import com.badnng.moe.settings.SavedNotificationSound
import com.badnng.moe.settings.SettingsResult

interface NotificationSoundFileLease { fun close() }

/** Immutable bounded native bytes; no local path or fabricated download identity escapes this boundary. */
class NotificationSoundEncoded(val sound: SavedNotificationSound, bytes: ByteArray) {
    private val content = bytes.copyOf()
    val size: Int get() = content.size
    fun bytes(): ByteArray = content.copyOf()
    override fun toString() = "NotificationSoundEncoded(<redacted>)"
}

interface TdNotificationSoundFilesSession {
    fun read(accountUserId: Long, expected: SavedNotificationSound, maximumBytes: Int, active: () -> Boolean,
        result: (SettingsResult<NotificationSoundEncoded>) -> Unit): NotificationSoundFileLease
    fun retire() {}
}
