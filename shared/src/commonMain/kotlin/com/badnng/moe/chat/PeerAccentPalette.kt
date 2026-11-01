package com.badnng.moe.chat

import com.badnng.moe.settings.SettingsProfileAccentPalette
import com.badnng.moe.settings.SettingsProfileColorValue
import com.badnng.moe.settings.SettingsProfileColorsCatalog
import com.badnng.moe.settings.SettingsUpgradedGiftColors

data class PeerBuiltInAccentPalette(
    val nameRgb: Int,
    val selectedNameRgb: Int,
    val avatarTopRgb: Int,
    val avatarBottomRgb: Int,
)

object OfficialPeerAccentThemePalette {
    /**
     * The default Telegram Desktop light/night theme roles adapted to peer IDs.
     * These are application theme values, not RGB values supplied by TDLib.
     */
    fun builtIn(id: Int, dark: Boolean): PeerBuiltInAccentPalette? = when (id) {
        0 -> if (dark) PeerBuiltInAccentPalette(0xfb6169, 0xffffff, 0xff845e, 0xd45246)
            else PeerBuiltInAccentPalette(0xc03d33, 0xc03d33, 0xff845e, 0xd45246)
        1 -> if (dark) PeerBuiltInAccentPalette(0xfaa357, 0xffffff, 0xfebb5b, 0xf68136)
            else PeerBuiltInAccentPalette(0xce671b, 0xce671b, 0xfebb5b, 0xf68136)
        2 -> if (dark) PeerBuiltInAccentPalette(0xb48bf2, 0xffffff, 0xb694f9, 0x6c61df)
            else PeerBuiltInAccentPalette(0x8544d6, 0x8544d6, 0xb694f9, 0x6c61df)
        3 -> if (dark) PeerBuiltInAccentPalette(0x85de85, 0xffffff, 0x9ad164, 0x46ba43)
            else PeerBuiltInAccentPalette(0x4fad2d, 0x4fad2d, 0x9ad164, 0x46ba43)
        4 -> if (dark) PeerBuiltInAccentPalette(0x62d4e3, 0xffffff, 0x5bcbe3, 0x359ad4)
            else PeerBuiltInAccentPalette(0x2996ad, 0x2996ad, 0x5bcbe3, 0x359ad4)
        5 -> if (dark) PeerBuiltInAccentPalette(0x65bdf3, 0xffffff, 0x5caffa, 0x408acf)
            else PeerBuiltInAccentPalette(0x168acd, 0x168acd, 0x5caffa, 0x408acf)
        6 -> if (dark) PeerBuiltInAccentPalette(0xff5694, 0xffffff, 0xff8aac, 0xd95574)
            else PeerBuiltInAccentPalette(0xcd4073, 0xcd4073, 0xff8aac, 0xd95574)
        else -> null
    }
}

data class PeerNameAccentPalette(
    val nameRgb: Int,
    val selectedNameRgb: Int?,
    val replyColors: List<Int>,
    val backgroundCustomEmojiId: Long,
    val giftModelCustomEmojiId: Long = 0,
)

fun resolvePeerNamePalette(
    value: SettingsProfileColorValue,
    catalog: SettingsProfileColorsCatalog,
    dark: Boolean,
    gift: SettingsUpgradedGiftColors? = null,
): PeerNameAccentPalette? {
    if (gift != null) {
        val accent = if (dark) gift.darkAccentColor else gift.lightAccentColor
        if (!accent.isRgb()) return null
        val replyColors = gift.colors(dark).rgbList(1..3) ?: return null
        val backgroundEmojiId = gift.symbolCustomEmojiId.takeIf { it != 0L }
            ?: value.backgroundCustomEmojiId
        return PeerNameAccentPalette(
            nameRgb = accent,
            selectedNameRgb = null,
            replyColors = replyColors,
            backgroundCustomEmojiId = backgroundEmojiId,
            giftModelCustomEmojiId = gift.modelCustomEmojiId,
        )
    }

    val builtIn = OfficialPeerAccentThemePalette.builtIn(value.colorId, dark)
    if (builtIn != null) {
        return PeerNameAccentPalette(
            nameRgb = builtIn.nameRgb,
            selectedNameRgb = builtIn.selectedNameRgb,
            replyColors = listOf(builtIn.nameRgb),
            backgroundCustomEmojiId = value.backgroundCustomEmojiId,
        )
    }

    if (value.colorId < 7) return null
    val custom = catalog.nameColors?.singleOrNull { it.id == value.colorId } ?: return null
    if (custom.id < 7 || custom.builtInColorId !in 0..6) return null
    val colors = (if (dark) custom.darkColors else custom.lightColors).rgbList(1..3) ?: return null
    return PeerNameAccentPalette(
        nameRgb = colors.first(),
        selectedNameRgb = null,
        replyColors = colors,
        backgroundCustomEmojiId = value.backgroundCustomEmojiId,
    )
}

fun resolvePeerProfilePalette(
    id: Int,
    catalog: SettingsProfileColorsCatalog,
    dark: Boolean,
): SettingsProfileAccentPalette? {
    if (id < 0) return null
    val profile = catalog.profileColors?.singleOrNull { it.id == id } ?: return null
    val source = if (dark) profile.dark else profile.light
    val palette = source.paletteColors.rgbList(1..2) ?: return null
    val background = source.backgroundColors.rgbList(1..2) ?: return null
    val story = source.storyColors.rgbList(2..2) ?: return null
    return SettingsProfileAccentPalette(palette, background, story)
}

private fun SettingsUpgradedGiftColors.colors(dark: Boolean): List<Int> =
    if (dark) darkColors else lightColors

private fun List<Int>.rgbList(length: IntRange): List<Int>? {
    if (size !in length || any { !it.isRgb() }) return null
    return toList()
}

private fun Int.isRgb(): Boolean = this in 0..0xffffff
