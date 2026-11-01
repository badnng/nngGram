package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import kotlin.test.*

/** Native markup and source conflict only; no codec, account, UI or download. */
class SettingsProfilePhotoStickerMappingTest {
    @Test fun signedIdentitiesAndEveryNativeBackgroundAreCaptured() {
        val regular = TdApi.ChatPhotoStickerTypeRegularOrMask(-10, -20)
        val emoji = TdApi.ChatPhotoStickerTypeCustomEmoji(-30)
        assertEquals(SettingsProfilePhotoSticker(SettingsProfilePhotoSticker.Type.RegularOrMask(-10, -20),
            SettingsProfilePhotoSticker.Background.Solid(0x123456)),
            mapProfilePhotoSticker(TdApi.ChatPhotoSticker(regular, TdApi.BackgroundFillSolid(0x123456))))
        for (rotation in 0 until 360 step 45) {
            assertEquals(SettingsProfilePhotoSticker(SettingsProfilePhotoSticker.Type.CustomEmoji(-30),
                SettingsProfilePhotoSticker.Background.Gradient(0x010203, 0x040506, rotation)),
                mapProfilePhotoSticker(TdApi.ChatPhotoSticker(emoji, TdApi.BackgroundFillGradient(0x010203, 0x040506, rotation))))
        }
        for (colors in listOf(intArrayOf(1, 2, 3), intArrayOf(1, 2, 3, 4))) {
            val expected = colors.toList()
            val captured = assertNotNull(mapProfilePhotoSticker(TdApi.ChatPhotoSticker(emoji, TdApi.BackgroundFillFreeformGradient(colors))))
            colors[0] = 99
            assertEquals(expected, assertIs<SettingsProfilePhotoSticker.Background.Freeform>(captured.background).colors())
        }
    }

    @Test fun invalidIdentitiesBackgroundsAndMissingFieldsAreNotInvented() {
        val good = TdApi.ChatPhotoStickerTypeCustomEmoji(42)
        val values = listOf(
            TdApi.ChatPhotoSticker(null, TdApi.BackgroundFillSolid(0)),
            TdApi.ChatPhotoSticker(TdApi.ChatPhotoStickerTypeCustomEmoji(0), TdApi.BackgroundFillSolid(0)),
            TdApi.ChatPhotoSticker(TdApi.ChatPhotoStickerTypeRegularOrMask(0, 1), TdApi.BackgroundFillSolid(0)),
            TdApi.ChatPhotoSticker(good, null),
            TdApi.ChatPhotoSticker(good, TdApi.BackgroundFillSolid(-1)),
            TdApi.ChatPhotoSticker(good, TdApi.BackgroundFillGradient(1, 2, 46)),
            TdApi.ChatPhotoSticker(good, TdApi.BackgroundFillGradient(1, 2, -45)),
            TdApi.ChatPhotoSticker(good, TdApi.BackgroundFillGradient(1, 2, 360)),
            TdApi.ChatPhotoSticker(good, TdApi.BackgroundFillFreeformGradient(intArrayOf(1, 2))),
            TdApi.ChatPhotoSticker(good, TdApi.BackgroundFillFreeformGradient(intArrayOf(1, 2, 3, 4, 5))),
        )
        assertNull(mapProfilePhotoSticker(null)); values.forEach { assertNull(mapProfilePhotoSticker(it)) }
    }

    @Test fun stableHistoryComparisonNoticesStickerAndBackgroundChangesButNotOffset() {
        val sticker = assertNotNull(mapProfilePhotoSticker(native(1)))
        val entry = SettingsProfilePhotoHistoryEntry(91, 0, 0, emptyList(), hasSticker = true, sticker = sticker)
        assertTrue(sameHistoryPhotoMetadata(entry, entry.copy(rawOffset = 5)))
        assertFalse(sameHistoryPhotoMetadata(entry, entry.copy(sticker = assertNotNull(mapProfilePhotoSticker(native(2))))))
        assertFalse(sameHistoryPhotoMetadata(entry, entry.copy(invalidSticker = true)))

        val calls = mutableListOf<(TdApi.Object) -> Unit>()
        val pages = mutableListOf<SettingsResult<SettingsProfilePhotoHistoryPage>>()
        val source = OfficialTdSettingsProfilePhotoHistory { _, reply, _ -> calls += reply }
        fun page(offset: Int, color: Int) {
            source.page(43, offset, 32, { true }, pages::add)
            calls.last()(TdApi.User().apply { id = 43; profilePhoto = TdApi.ProfilePhoto().apply { id = 90 } })
            calls.last()(TdApi.UserFullInfo().apply { this.photo = photo(90, native(0)) })
            calls.last()(TdApi.ChatPhotos(2, arrayOf(photo(91, native(color)))))
        }
        page(0, 1)
        val old = assertNotNull(pages.last().value).entries.single()
        assertEquals(sticker, old.sticker)
        val request = SettingsProfilePhotoHistoryAuthorizedFileRequest(43, old.authorization, old.photoId, old.rawOffset,
            SettingsProfilePhotoHistoryMediaUse.EXPLICIT_PREVIEW, 100, 100)
        page(1, 2)
        assertTrue(assertNotNull(pages.last().value).metadataConflict)
        assertEquals(SettingsError.CONFLICT, source.resolve(request).error)
    }

    @Test fun currentUpdateWithSamePhotoAndChangedStickerRevokesTheOriginalOwner() {
        val calls = mutableListOf<(TdApi.Object) -> Unit>()
        val reads = mutableListOf<SettingsResult<SettingsCurrentProfilePhotoRead>>()
        var invalidations = 0
        val source = OfficialTdCurrentProfilePhoto { _, reply, _ -> calls += reply }
        source.read(43, { true }, { invalidations++ }, reads::add)
        calls.last()(TdApi.User().apply { id = 43; profilePhoto = TdApi.ProfilePhoto().apply { id = 90 } })
        calls.last()(TdApi.UserFullInfo().apply { photo = photo(90, native(1)) })
        val captured = assertNotNull(reads.single().value?.photo)
        assertEquals(1, assertIs<SettingsProfilePhotoSticker.Background.Solid>(captured.sticker?.background).color)
        source.accept(TdApi.UpdateUserFullInfo(43, TdApi.UserFullInfo().apply { photo = photo(90, native(2)) }))
        assertEquals(1, invalidations)
    }

    private companion object {
        fun native(color: Int) = TdApi.ChatPhotoSticker(TdApi.ChatPhotoStickerTypeCustomEmoji(-30), TdApi.BackgroundFillSolid(color))
        fun photo(id: Long, sticker: TdApi.ChatPhotoSticker) = TdApi.ChatPhoto().apply {
            this.id = id; this.sticker = sticker; sizes = emptyArray()
        }
    }
}
