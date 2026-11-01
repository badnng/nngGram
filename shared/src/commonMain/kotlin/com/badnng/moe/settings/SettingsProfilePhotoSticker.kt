package com.badnng.moe.settings

import kotlinx.coroutines.flow.MutableStateFlow

/** Immutable metadata from the selected native ChatPhoto; it grants no media authority. */
data class SettingsProfilePhotoSticker(val type: Type, val background: Background) {
    sealed interface Type {
        data class RegularOrMask(val stickerSetId: Long, val stickerId: Long) : Type {
            override fun toString() = "RegularOrMask(<redacted>)"
        }
        data class CustomEmoji(val customEmojiId: Long) : Type {
            override fun toString() = "CustomEmoji(<redacted>)"
        }
    }
    sealed interface Background {
        data class Solid(val color: Int) : Background
        data class Gradient(val topColor: Int, val bottomColor: Int, val rotationAngle: Int) : Background
        data class Freeform(val first: Int, val second: Int, val third: Int, val fourth: Int? = null) : Background {
            fun colors(): List<Int> = listOfNotNull(first, second, third, fourth)
        }
    }
    override fun toString() = "SettingsProfilePhotoSticker(<redacted>)"
}

/** Selection of a photo, never a caller-supplied sticker, file, path, offset or builder ticket. */
sealed interface SettingsSavedProfileStickerAvatarRequest {
    val parent: SettingsProfileExtrasToken
    val epoch: Long
    val photoId: Long

    data class Current(override val parent: SettingsProfileExtrasToken, override val epoch: Long,
        override val photoId: Long) : SettingsSavedProfileStickerAvatarRequest {
        override fun toString() = "SettingsSavedProfileStickerAvatarRequest.Current(<redacted>)"
    }
    data class History(override val parent: SettingsProfileExtrasToken, override val epoch: Long,
        override val photoId: Long) : SettingsSavedProfileStickerAvatarRequest {
        override fun toString() = "SettingsSavedProfileStickerAvatarRequest.History(<redacted>)"
    }
}

/** Request witness survives an ordinary failure, but never grants artwork after source revocation. */
interface SettingsSavedProfileStickerAvatarLease : AutoCloseable {
    fun requestCurrent(): Boolean
    /** Atomically stop pending native demand; already borrowed pixels remain UI-owned. */
    fun revokeDemand() {}
    override fun close()
}

/** Caller-created one-way demand gate. It carries no photo identity or file authority. */
class SettingsSavedProfileStickerAvatarDemand {
    private val live = MutableStateFlow(true)
    fun current(): Boolean = live.value
    fun revokeDemand() { live.value = false }
}
