package com.badnng.moe.tdlib

import com.badnng.moe.chat.MediaFile
import com.badnng.moe.settings.SettingsBackgroundEmoji
import com.badnng.moe.settings.SettingsResult

enum class ProfileEmojiResourceDomain { OPTION, PROFILE, REPLY }

/** A render owner's revision, independent of ordinary sticker/reaction send permission. */
data class ProfileEmojiResourceToken(
    val generation: Long,
    val accountUserId: Long,
    val domain: ProfileEmojiResourceDomain,
    val ownerId: Long,
    val revision: Long,
) {
    override fun toString() = "ProfileEmojiResourceToken(<redacted>)"
}

enum class ProfileEmojiFileRole { THUMBNAIL, FULL }

interface ProfileEmojiFileLease { fun close() }

/** Bounded, owned encoded input. Neither a native path nor mutable shared bytes escape. */
class ProfileEmojiEncoded(
    val emoji: SettingsBackgroundEmoji,
    val role: ProfileEmojiFileRole,
    val file: MediaFile,
    bytes: ByteArray,
) {
    init { require(bytes.size in 1..24 * 1024 * 1024) }
    private val content = bytes.copyOf()
    val size: Int get() = content.size
    fun bytes(): ByteArray = content.copyOf()
    override fun toString() = "ProfileEmojiEncoded(<redacted>)"
}

interface TdProfileEmojiFilesSession {
    fun read(
        token: ProfileEmojiResourceToken,
        expected: SettingsBackgroundEmoji,
        role: ProfileEmojiFileRole,
        maximumBytes: Int,
        active: () -> Boolean,
        result: (SettingsResult<ProfileEmojiEncoded>) -> Unit,
    ): ProfileEmojiFileLease
    fun retire() {}
}
