package com.badnng.moe.media

import androidx.compose.ui.graphics.ImageBitmap
import com.badnng.moe.settings.SettingsBackgroundEmoji
import com.badnng.moe.settings.SettingsResult

enum class ProfileEmojiArtworkRole { OPTION_THUMBNAIL, PROFILE_MASK, REPLY_MASK, GIFT }

data class ProfileEmojiArtworkToken(
    val generation: Long, val accountUserId: Long, val ownerId: Long, val revision: Long,
    val customEmojiId: Long,
) {
    override fun toString() = "ProfileEmojiArtworkToken(<redacted>)"
}

data class ProfileEmojiArtworkRequest(
    val role: ProfileEmojiArtworkRole, val physicalSizePx: Int, val frameTimeMillis: Long = 0,
)

/** Immutable prepared pixels. Access is valid only while its returned lease remains open. */
interface ProfileEmojiArtwork {
    val image: ImageBitmap?
    val isAlphaMask: Boolean
    val animated: Boolean
    val durationMillis: Long
    val frameDurationMillis: Long
}

interface ProfileEmojiArtworkLease { fun close() }

/** Actual native assets only. This provides no ordinary sticker/reaction sending permission. */
interface ProfileEmojiArtworkProvider {
    val generation: Long
    fun acquire(token: ProfileEmojiArtworkToken, expected: SettingsBackgroundEmoji?, request: ProfileEmojiArtworkRequest,
        active: () -> Boolean, result: (SettingsResult<ProfileEmojiArtwork>) -> Unit): ProfileEmojiArtworkLease
    fun close()
}
