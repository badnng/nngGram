package com.badnng.moe.chat

import com.badnng.moe.settings.SettingsNameAccentColor
import com.badnng.moe.settings.SettingsProfileAccentColor
import com.badnng.moe.settings.SettingsProfileAccentPalette
import com.badnng.moe.settings.SettingsProfileColorValue
import com.badnng.moe.settings.SettingsProfileColorsCatalog
import com.badnng.moe.settings.SettingsUpgradedGiftColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PeerAccentPaletteTest {
    @Test
    fun builtInPeerIdsMapToPinnedLightAndNightThemeRoles() {
        val light = listOf(
            PeerBuiltInAccentPalette(0xc03d33, 0xc03d33, 0xff845e, 0xd45246),
            PeerBuiltInAccentPalette(0xce671b, 0xce671b, 0xfebb5b, 0xf68136),
            PeerBuiltInAccentPalette(0x8544d6, 0x8544d6, 0xb694f9, 0x6c61df),
            PeerBuiltInAccentPalette(0x4fad2d, 0x4fad2d, 0x9ad164, 0x46ba43),
            PeerBuiltInAccentPalette(0x2996ad, 0x2996ad, 0x5bcbe3, 0x359ad4),
            PeerBuiltInAccentPalette(0x168acd, 0x168acd, 0x5caffa, 0x408acf),
            PeerBuiltInAccentPalette(0xcd4073, 0xcd4073, 0xff8aac, 0xd95574),
        )
        val night = listOf(
            PeerBuiltInAccentPalette(0xfb6169, 0xffffff, 0xff845e, 0xd45246),
            PeerBuiltInAccentPalette(0xfaa357, 0xffffff, 0xfebb5b, 0xf68136),
            PeerBuiltInAccentPalette(0xb48bf2, 0xffffff, 0xb694f9, 0x6c61df),
            PeerBuiltInAccentPalette(0x85de85, 0xffffff, 0x9ad164, 0x46ba43),
            PeerBuiltInAccentPalette(0x62d4e3, 0xffffff, 0x5bcbe3, 0x359ad4),
            PeerBuiltInAccentPalette(0x65bdf3, 0xffffff, 0x5caffa, 0x408acf),
            PeerBuiltInAccentPalette(0xff5694, 0xffffff, 0xff8aac, 0xd95574),
        )

        for (id in 0..6) {
            assertEquals(light[id], OfficialPeerAccentThemePalette.builtIn(id, dark = false))
            assertEquals(night[id], OfficialPeerAccentThemePalette.builtIn(id, dark = true))
            assertEquals(
                listOf(light[id].nameRgb),
                resolvePeerNamePalette(SettingsProfileColorValue(id), SettingsProfileColorsCatalog(), dark = false)
                    ?.replyColors,
            )
            assertEquals(
                listOf(night[id].nameRgb),
                resolvePeerNamePalette(SettingsProfileColorValue(id), SettingsProfileColorsCatalog(), dark = true)
                    ?.replyColors,
            )
        }
        assertNull(OfficialPeerAccentThemePalette.builtIn(-1, dark = false))
        assertNull(OfficialPeerAccentThemePalette.builtIn(7, dark = true))
    }

    @Test
    fun builtInZeroResolvesWithoutCatalogAndMinusOneIsUnknownForNameColors() {
        val palette = resolvePeerNamePalette(
            value = SettingsProfileColorValue(colorId = 0),
            catalog = SettingsProfileColorsCatalog(),
            dark = false,
        )

        assertEquals(0xc03d33, palette?.nameRgb)
        assertEquals(0xc03d33, palette?.selectedNameRgb)
        assertEquals(listOf(0xc03d33), palette?.replyColors)
        assertNull(resolvePeerNamePalette(SettingsProfileColorValue(-1), SettingsProfileColorsCatalog(), dark = false))
    }

    @Test
    fun signedCustomEmojiIdsAreOpaqueAndOnlyZeroMeansNone() {
        val backgroundId = Long.MIN_VALUE
        val gift = SettingsUpgradedGiftColors(
            id = 1,
            modelCustomEmojiId = Long.MIN_VALUE,
            symbolCustomEmojiId = -19,
            lightAccentColor = 0x123456,
            lightColors = listOf(0x234567),
            darkAccentColor = 0x345678,
            darkColors = listOf(0x456789),
        )
        val catalog = SettingsProfileColorsCatalog()

        val ordinary = resolvePeerNamePalette(
            SettingsProfileColorValue(0, backgroundCustomEmojiId = backgroundId),
            catalog,
            dark = false,
        )
        val withGiftSymbol = resolvePeerNamePalette(
            SettingsProfileColorValue(0, backgroundCustomEmojiId = backgroundId),
            catalog,
            dark = false,
            gift = gift,
        )
        val withoutGiftSymbol = resolvePeerNamePalette(
            SettingsProfileColorValue(0, backgroundCustomEmojiId = backgroundId),
            catalog,
            dark = false,
            gift = gift.copy(symbolCustomEmojiId = 0),
        )
        val noBackground = resolvePeerNamePalette(
            SettingsProfileColorValue(0),
            catalog,
            dark = false,
            gift = gift.copy(symbolCustomEmojiId = 0),
        )

        assertEquals(backgroundId, ordinary?.backgroundCustomEmojiId)
        assertEquals(-19L, withGiftSymbol?.backgroundCustomEmojiId)
        assertEquals(Long.MIN_VALUE, withGiftSymbol?.giftModelCustomEmojiId)
        assertEquals(backgroundId, withoutGiftSymbol?.backgroundCustomEmojiId)
        assertEquals(0L, noBackground?.backgroundCustomEmojiId)
    }

    @Test
    fun hiddenCustomNameColorKeepsEveryReplyColorAndCopiesItsSourceList() {
        val lightColors = mutableListOf(0x112233, 0x445566, 0x778899)
        val catalog = SettingsProfileColorsCatalog(
            nameColors = listOf(SettingsNameAccentColor(12, 3, lightColors, listOf(0xaabbcc))),
            availableNameColorIds = emptyList(),
        )

        val palette = resolvePeerNamePalette(
            value = SettingsProfileColorValue(12, backgroundCustomEmojiId = 84),
            catalog = catalog,
            dark = false,
        )
        lightColors[0] = 0
        val nightPalette = resolvePeerNamePalette(
            value = SettingsProfileColorValue(12),
            catalog = catalog,
            dark = true,
        )

        assertEquals(0x112233, palette?.nameRgb)
        assertNull(palette?.selectedNameRgb)
        assertEquals(listOf(0x112233, 0x445566, 0x778899), palette?.replyColors)
        assertEquals(84L, palette?.backgroundCustomEmojiId)
        assertEquals(0xaabbcc, nightPalette?.nameRgb)
        assertEquals(listOf(0xaabbcc), nightPalette?.replyColors)
    }

    @Test
    fun giftOverridesNamePaletteAndKeepsSymbolAndModelRolesSeparate() {
        val catalog = SettingsProfileColorsCatalog(
            nameColors = listOf(SettingsNameAccentColor(12, 0, listOf(0x010101), listOf(0x020202))),
            availableNameColorIds = emptyList(),
        )
        val gift = SettingsUpgradedGiftColors(
            id = 500,
            modelCustomEmojiId = 700,
            symbolCustomEmojiId = 800,
            lightAccentColor = 0x111111,
            lightColors = listOf(0x121212, 0x131313),
            darkAccentColor = 0xeeeeee,
            darkColors = listOf(0xdddddd, 0xcccccc, 0xbbbbbb),
        )

        val palette = resolvePeerNamePalette(
            value = SettingsProfileColorValue(12, backgroundCustomEmojiId = 900),
            catalog = catalog,
            dark = true,
            gift = gift,
        )

        assertEquals(0xeeeeee, palette?.nameRgb)
        assertNull(palette?.selectedNameRgb)
        assertEquals(listOf(0xdddddd, 0xcccccc, 0xbbbbbb), palette?.replyColors)
        assertEquals(800L, palette?.backgroundCustomEmojiId)
        assertEquals(700L, palette?.giftModelCustomEmojiId)
        assertEquals(
            900L,
            resolvePeerNamePalette(
                value = SettingsProfileColorValue(12, backgroundCustomEmojiId = 900),
                catalog = catalog,
                dark = true,
                gift = gift.copy(symbolCustomEmojiId = 0),
            )?.backgroundCustomEmojiId,
        )
    }

    @Test
    fun profilePalettePreservesIndependentArraysForHiddenId() {
        val paletteColors = mutableListOf(0x111111, 0x222222)
        val backgroundColors = mutableListOf(0x333333, 0x444444)
        val storyColors = mutableListOf(0x555555, 0x666666)
        val profileColors = SettingsProfileAccentPalette(
            paletteColors = paletteColors,
            backgroundColors = backgroundColors,
            storyColors = storyColors,
        )
        val catalog = SettingsProfileColorsCatalog(
            nameColors = listOf(SettingsNameAccentColor(17, 2, listOf(0xaaaaaa), listOf(0xbbbbbb))),
            profileColors = listOf(SettingsProfileAccentColor(6, profileColors, profileColors)),
            availableProfileColorIds = emptyList(),
        )

        val palette = resolvePeerProfilePalette(6, catalog, dark = false)
        paletteColors[0] = 0
        backgroundColors[0] = 0
        storyColors[0] = 0

        assertEquals(listOf(0x111111, 0x222222), palette?.paletteColors)
        assertEquals(listOf(0x333333, 0x444444), palette?.backgroundColors)
        assertEquals(listOf(0x555555, 0x666666), palette?.storyColors)
    }

    @Test
    fun unknownAndMinusOneProfileIdsDoNotBorrowNameColors() {
        val catalog = SettingsProfileColorsCatalog(
            nameColors = listOf(SettingsNameAccentColor(4, 2, listOf(0x123456), listOf(0x654321))),
            profileColors = listOf(
                SettingsProfileAccentColor(
                    4,
                    SettingsProfileAccentPalette(listOf(0x111111), listOf(0x222222), listOf(0x333333, 0x444444)),
                    SettingsProfileAccentPalette(listOf(0xaaaaaa), listOf(0xbbbbbb), listOf(0xcccccc, 0xdddddd)),
                ),
            ),
        )

        assertNull(resolvePeerProfilePalette(-1, catalog, dark = false))
        assertNull(resolvePeerProfilePalette(5, catalog, dark = true))
        assertNull(resolvePeerNamePalette(SettingsProfileColorValue(99), catalog, dark = false))
        assertEquals(
            SettingsProfileAccentPalette(listOf(0xaaaaaa), listOf(0xbbbbbb), listOf(0xcccccc, 0xdddddd)),
            resolvePeerProfilePalette(4, catalog, dark = true),
        )
    }

    @Test
    fun malformedRgbValuesOrColorArrayLengthsAreRejected() {
        val validName = SettingsNameAccentColor(9, 1, listOf(0x123456), listOf(0x654321))
        assertNull(
            resolvePeerNamePalette(
                SettingsProfileColorValue(9),
                SettingsProfileColorsCatalog(nameColors = listOf(validName.copy(lightColors = listOf(-1)))),
                dark = false,
            ),
        )
        assertNull(
            resolvePeerNamePalette(
                SettingsProfileColorValue(9),
                SettingsProfileColorsCatalog(nameColors = listOf(validName.copy(darkColors = emptyList()))),
                dark = true,
            ),
        )
        assertNull(
            resolvePeerNamePalette(
                SettingsProfileColorValue(0),
                SettingsProfileColorsCatalog(),
                dark = false,
                gift = SettingsUpgradedGiftColors(1, 0, 0, 0x1000000, listOf(0x123456), 0, listOf(0x123456)),
            ),
        )
        assertNull(
            resolvePeerProfilePalette(
                3,
                SettingsProfileColorsCatalog(
                    profileColors = listOf(
                        SettingsProfileAccentColor(
                            3,
                            SettingsProfileAccentPalette(listOf(0x111111), listOf(0x222222), listOf(0x333333)),
                            SettingsProfileAccentPalette(listOf(0x111111), listOf(0x222222), listOf(0x333333, 0x444444)),
                        ),
                    ),
                ),
                dark = false,
            ),
        )
    }
}
