package com.badnng.moe.settings

import com.badnng.moe.chat.StickerItem

enum class SettingsProfileColorPart { NAME, PROFILE }

data class SettingsNameAccentColor(
    val id: Int, val builtInColorId: Int, val lightColors: List<Int>, val darkColors: List<Int>,
    val minimumChannelBoostLevel: Int = 0,
)

data class SettingsProfileAccentPalette(
    val paletteColors: List<Int>, val backgroundColors: List<Int>, val storyColors: List<Int>,
)

data class SettingsProfileAccentColor(
    val id: Int, val light: SettingsProfileAccentPalette, val dark: SettingsProfileAccentPalette,
    val minimumSupergroupBoostLevel: Int = 0, val minimumChannelBoostLevel: Int = 0,
)

/** Null lists mean unknown, including before an early native update has been received. */
data class SettingsProfileColorsCatalog(
    val nameColors: List<SettingsNameAccentColor>? = null,
    val availableNameColorIds: List<Int>? = null,
    val profileColors: List<SettingsProfileAccentColor>? = null,
    val availableProfileColorIds: List<Int>? = null,
    val revision: Long = 0,
    val nameError: SettingsError? = null, val profileError: SettingsError? = null,
) {
    override fun toString() = "SettingsProfileColorsCatalog(<redacted>)"
}

data class SettingsUpgradedGiftColors(
    val id: Long, val modelCustomEmojiId: Long, val symbolCustomEmojiId: Long,
    val lightAccentColor: Int, val lightColors: List<Int>,
    val darkAccentColor: Int, val darkColors: List<Int>,
) {
    override fun toString() = "SettingsUpgradedGiftColors(<redacted>)"
}

data class SettingsProfileColorValue(val colorId: Int, val backgroundCustomEmojiId: Long = 0) {
    override fun toString() = "SettingsProfileColorValue(<redacted>)"
}

data class SettingsProfileColorsValues(
    val name: SettingsProfileColorValue,
    val profile: SettingsProfileColorValue,
    val gift: SettingsUpgradedGiftColors? = null,
) {
    fun value(part: SettingsProfileColorPart) = if (part == SettingsProfileColorPart.NAME) name else profile
    override fun toString() = "SettingsProfileColorsValues(<redacted>)"
}

/** Current known native state. A fresh GetMe may use TDLib's cache; this is not server CAS. */
data class SettingsProfileColorsData(
    val userId: Long, val premium: Boolean, val values: SettingsProfileColorsValues,
    val catalog: SettingsProfileColorsCatalog, val nativeRevision: Long = 0,
) {
    override fun toString() = "SettingsProfileColorsData(<redacted>)"
}

data class SettingsProfileColorMutationResult(
    val locallyAccepted: Boolean = false, val actual: SettingsProfileColorsData? = null,
    val error: SettingsError? = null,
) {
    override fun toString() = "SettingsProfileColorMutationResult(<redacted>, accepted=$locallyAccepted, error=$error)"
}

/** A real custom-emoji identity, never ordinary sticker-selector send permission. */
data class SettingsBackgroundEmoji(val customEmojiId: Long, val sticker: StickerItem, val needsRepainting: Boolean = false) {
    override fun toString() = "SettingsBackgroundEmoji(<redacted>)"
}
