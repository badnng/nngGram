package com.badnng.moe.tdlib

import com.badnng.moe.settings.SettingsProfileColorValue
import com.badnng.moe.settings.SettingsProfileColorsValues
import com.badnng.moe.settings.SettingsUpgradedGiftColors
import org.drinkless.tdlib.TdApi
import java.util.Collections

/** Preserve native identities and raw values; availability belongs to the independent catalog. */
internal fun mapPeerAppearance(user: TdApi.User) = SettingsProfileColorsValues(
    SettingsProfileColorValue(user.accentColorId, user.backgroundCustomEmojiId),
    SettingsProfileColorValue(user.profileAccentColorId, user.profileBackgroundCustomEmojiId),
    mapPeerGiftColors(user.upgradedGiftColors),
)

internal fun mapPeerAppearance(chat: TdApi.Chat) = SettingsProfileColorsValues(
    SettingsProfileColorValue(chat.accentColorId, chat.backgroundCustomEmojiId),
    SettingsProfileColorValue(chat.profileAccentColorId, chat.profileBackgroundCustomEmojiId),
    mapPeerGiftColors(chat.upgradedGiftColors),
)

internal fun mapPeerAppearance(update: TdApi.UpdateChatAccentColors) = SettingsProfileColorsValues(
    SettingsProfileColorValue(update.accentColorId, update.backgroundCustomEmojiId),
    SettingsProfileColorValue(update.profileAccentColorId, update.profileBackgroundCustomEmojiId),
    mapPeerGiftColors(update.upgradedGiftColors),
)

private fun mapPeerGiftColors(gift: TdApi.UpgradedGiftColors?): SettingsUpgradedGiftColors? = gift?.let {
    SettingsUpgradedGiftColors(it.id, it.modelCustomEmojiId, it.symbolCustomEmojiId,
        it.lightThemeAccentColor, Collections.unmodifiableList(it.lightThemeColors?.toList().orEmpty()),
        it.darkThemeAccentColor, Collections.unmodifiableList(it.darkThemeColors?.toList().orEmpty()))
}
