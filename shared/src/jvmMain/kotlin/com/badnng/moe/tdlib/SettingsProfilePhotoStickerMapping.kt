package com.badnng.moe.tdlib

import com.badnng.moe.settings.SettingsProfilePhotoSticker
import org.drinkless.tdlib.TdApi

/** Captures only native sticker markup; no sticker or file is fetched here. */
internal fun mapProfilePhotoSticker(native: TdApi.ChatPhotoSticker?): SettingsProfilePhotoSticker? {
    val value = native ?: return null
    val type = when (val identity = value.type) {
        is TdApi.ChatPhotoStickerTypeRegularOrMask -> if (identity.stickerSetId != 0L && identity.stickerId != 0L)
            SettingsProfilePhotoSticker.Type.RegularOrMask(identity.stickerSetId, identity.stickerId) else return null
        is TdApi.ChatPhotoStickerTypeCustomEmoji -> if (identity.customEmojiId != 0L)
            SettingsProfilePhotoSticker.Type.CustomEmoji(identity.customEmojiId) else return null
        else -> return null
    }
    fun valid(color: Int) = color in 0..0xFFFFFF
    val background = when (val fill = value.backgroundFill) {
        is TdApi.BackgroundFillSolid -> if (valid(fill.color)) SettingsProfilePhotoSticker.Background.Solid(fill.color) else return null
        is TdApi.BackgroundFillGradient -> if (valid(fill.topColor) && valid(fill.bottomColor) &&
            fill.rotationAngle in 0 until 360 && fill.rotationAngle % 45 == 0)
            SettingsProfilePhotoSticker.Background.Gradient(fill.topColor, fill.bottomColor, fill.rotationAngle) else return null
        is TdApi.BackgroundFillFreeformGradient -> {
            val colors = fill.colors ?: return null
            if (colors.size !in 3..4 || colors.any { !valid(it) }) return null
            SettingsProfilePhotoSticker.Background.Freeform(colors[0], colors[1], colors[2], colors.getOrNull(3))
        }
        else -> return null
    }
    return SettingsProfilePhotoSticker(type, background)
}
