package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import kotlin.test.*

/** Real current/history native catalogs with synthetic TD responses, never picker grants. */
class SavedProfileStickerAuthorityTest {
    @Test fun currentRequiresExactSelfPhotoAndTicketAndUpdateRevokesItsCapturedSource() {
        val calls = mutableListOf<(TdApi.Object) -> Unit>()
        val reads = mutableListOf<SettingsResult<SettingsCurrentProfilePhotoRead>>()
        var invalidations = 0
        val catalog = OfficialTdCurrentProfilePhoto { _, callback, _ -> calls += callback }
        catalog.read(43, { true }, { invalidations++ }, reads::add)
        calls.last()(self())
        calls.last()(TdApi.UserFullInfo().apply { photo = nativePhoto(90, 1) })
        val photo = assertNotNull(reads.single().value?.photo)
        val exact = SettingsSavedProfileStickerNativeSource.Current(43, photo.authorization, 90)
        val waiting = assertNotNull(catalog.savedStickerTicketWitness(exact))
        val captured = assertNotNull(catalog.captureSavedSticker(exact).value)
        assertTrue(captured.atomicCurrent())
        listOf(exact.copy(userId = 44), exact.copy(authorization = exact.authorization + 1), exact.copy(photoId = 91))
            .forEach { assertNull(catalog.captureSavedSticker(it).value) }
        catalog.accept(TdApi.UpdateUserFullInfo(43, TdApi.UserFullInfo().apply { this.photo = nativePhoto(90, 2) }))
        assertEquals(1, invalidations); assertFalse(captured.atomicCurrent()); assertFalse(waiting())
        assertNull(catalog.captureSavedSticker(exact).value)
    }

    @Test fun historyUsesRealOffsetAndSharesFactsOnlyAcrossCompatiblePages() {
        val calls = mutableListOf<(TdApi.Object) -> Unit>()
        val pages = mutableListOf<SettingsResult<SettingsProfilePhotoHistoryPage>>()
        val catalog = OfficialTdSettingsProfilePhotoHistory { _, callback, _ -> calls += callback }
        fun page(offset: Int, color: Int): SettingsProfilePhotoHistoryEntry {
            catalog.page(43, offset, 32, { true }, pages::add)
            calls.last()(self())
            calls.last()(TdApi.UserFullInfo().apply { photo = nativePhoto(90, 0) })
            calls.last()(TdApi.ChatPhotos(20, arrayOf(nativePhoto(-91, color))))
            return assertNotNull(pages.last().value).entries.single()
        }
        val first = page(7, 1)
        val request = SettingsSavedProfileStickerNativeSource.History(43, first.authorization, first.photoId, first.rawOffset)
        val waiting = assertNotNull(catalog.savedStickerTicketWitness(request))
        val old = assertNotNull(catalog.captureSavedSticker(request, {}).value)
        assertEquals(7, request.rawOffset)
        assertNull(catalog.captureSavedSticker(request.copy(rawOffset = 8), {}).value)
        val duplicate = page(8, 1)
        val second = assertNotNull(catalog.captureSavedSticker(SettingsSavedProfileStickerNativeSource.History(
            43, duplicate.authorization, duplicate.photoId, duplicate.rawOffset), {}).value)
        assertSame(old.facts, second.facts); assertTrue(old.atomicCurrent())
        page(9, 2)
        assertTrue(assertNotNull(pages.last().value).metadataConflict)
        assertFalse(old.atomicCurrent()); assertFalse(second.atomicCurrent()); assertFalse(waiting())
        assertNull(catalog.captureSavedSticker(request, {}).value)
    }

    private companion object {
        fun self() = TdApi.User().apply { id = 43; profilePhoto = TdApi.ProfilePhoto().apply { id = 90 } }
        fun nativePhoto(id: Long, color: Int) = TdApi.ChatPhoto().apply {
            this.id = id; sizes = emptyArray()
            sticker = TdApi.ChatPhotoSticker(TdApi.ChatPhotoStickerTypeCustomEmoji(-30), TdApi.BackgroundFillSolid(color))
        }
    }
}
