package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import kotlin.test.*

class OfficialTdSettingsProfileColorsTest {
    @Test fun constructionAndUnknownCatalogNeverInventAGetter() {
        val f = Fixture()
        assertTrue(f.requests.isEmpty())
        assertNull(f.api.catalog().nameColors)
        assertNull(f.api.catalog().profileColors)
    }

    @Test fun earlyCatalogsKeepAvailableOrderAndCopyEveryNativeArray() {
        val f = Fixture()
        val update = TdApi.UpdateAccentColors(arrayOf(color(9), color(8)), intArrayOf(8, 0, 9))
        f.api.accept(update)
        val nativePalette = TdApi.ProfileAccentColors(intArrayOf(1), intArrayOf(2, 3), intArrayOf(4, 5))
        f.api.accept(TdApi.UpdateProfileAccentColors(arrayOf(TdApi.ProfileAccentColor(20, nativePalette, nativePalette, 1, 2)), intArrayOf(20)))
        update.colors[0].lightThemeColors[0] = 999
        update.availableAccentColorIds[0] = 999
        nativePalette.storyColors[0] = 999
        val catalog = f.api.catalog()
        assertEquals(listOf(8, 0, 9), catalog.availableNameColorIds)
        assertEquals(listOf(9, 8), catalog.nameColors?.map { it.id })
        assertEquals(listOf(1, 2), catalog.nameColors?.first()?.lightColors)
        assertEquals(listOf(4, 5), catalog.profileColors?.single()?.light?.storyColors)
        assertEquals(2L, catalog.revision)
        assertFailsWith<UnsupportedOperationException> { (catalog.availableNameColorIds as MutableList<Int>).clear() }
    }

    @Test fun actualEmptyCatalogIsKnownAndNullArraysAreErrors() {
        val f = Fixture()
        f.api.accept(TdApi.UpdateAccentColors(emptyArray(), intArrayOf()))
        assertEquals(emptyList(), f.api.catalog().availableNameColorIds)
        f.api.accept(TdApi.UpdateAccentColors())
        assertEquals(emptyList(), f.api.catalog().nameColors)
        assertEquals(SettingsError.INVALID_VALUE, f.api.catalog().nameError)
        assertNull(f.api.catalog().profileColors)
        assertEquals(2L, f.api.catalog().revision)
    }

    @Test fun malformedCatalogPreservesKnownDataAndDisablesItsWrites() {
        val f = Fixture(); f.names()
        val before = f.api.catalog()
        f.api.accept(TdApi.UpdateAccentColors(arrayOf(color(8).apply { lightThemeColors = intArrayOf(-1) }), intArrayOf(8)))
        assertEquals(before.nameColors, f.api.catalog().nameColors)
        assertEquals(SettingsError.INVALID_VALUE, f.api.catalog().nameError)
        val data = f.load()
        val results = f.save(data, SettingsProfileColorValue(8))
        f.answerMe()
        assertEquals(SettingsError.CONFLICT, results.single().error)
        assertEquals(0, f.writes())
    }

    @Test fun invalidCountsMissingObjectsAndDuplicateIdsAreNotTrimmed() {
        listOf(
            TdApi.UpdateAccentColors(arrayOf(color(8)), intArrayOf(8, 8)),
            TdApi.UpdateAccentColors(arrayOf(color(8)), intArrayOf(9)),
            TdApi.UpdateAccentColors(arrayOf(color(8), color(8)), intArrayOf(8)),
            TdApi.UpdateAccentColors(arrayOf(color(8).apply { darkThemeColors = IntArray(4) }), intArrayOf(8)),
            TdApi.UpdateAccentColors(emptyArray(), IntArray(4097)),
        ).forEach { update ->
            val f = Fixture(); f.api.accept(update)
            assertEquals(SettingsError.INVALID_VALUE, f.api.catalog().nameError)
            assertNull(f.api.catalog().nameColors)
        }
    }

    @Test fun profilePaletteValidatesStoryCountAndRgbWithoutLosingNameCatalog() {
        val f = Fixture(); f.names()
        val palette = TdApi.ProfileAccentColors(intArrayOf(1), intArrayOf(2), intArrayOf(3))
        f.api.accept(TdApi.UpdateProfileAccentColors(arrayOf(TdApi.ProfileAccentColor(8, palette, palette, 0, 0)), intArrayOf(8)))
        assertEquals(SettingsError.INVALID_VALUE, f.api.catalog().profileError)
        assertNotNull(f.api.catalog().nameColors)
    }

    @Test fun loadCopiesRawPairsPremiumAndEveryGiftField() {
        val f = Fixture()
        val gift = TdApi.UpgradedGiftColors(-11, -12, -13, 1, intArrayOf(2, 3), 4, intArrayOf(5, 6))
        val data = f.load(user().apply { upgradedGiftColors = gift; backgroundCustomEmojiId = -7; profileBackgroundCustomEmojiId = -8 })
        gift.lightThemeColors[0] = 999
        assertTrue(data.premium)
        assertEquals(SettingsProfileColorValue(0, -7), data.values.name)
        assertEquals(SettingsProfileColorValue(17, -8), data.values.profile)
        assertEquals(SettingsUpgradedGiftColors(-11, -12, -13, 1, listOf(2, 3), 4, listOf(5, 6)), data.values.gift)
        assertFalse(data.toString().contains("-13"))
    }

    @Test fun wrongSelfOrNonRegularGetMeNeverChangesEstablishedIdentity() {
        val f = Fixture(); f.load()
        listOf(user().apply { id = 99 }, user().apply { type = TdApi.UserTypeBot() }).forEach { invalid ->
            val results = mutableListOf<SettingsResult<SettingsProfileColorsData>>()
            f.api.load(43, { true }, results::add); f.last.answer(invalid)
            assertEquals(SettingsError.NOT_READY, results.single().error)
        }
        val wrong = mutableListOf<SettingsResult<SettingsProfileColorsData>>()
        f.api.load(99, { true }, wrong::add); f.last.answer(user().apply { id = 99 })
        assertEquals(SettingsError.NOT_READY, wrong.single().error)
    }

    @Test fun unrelatedUsersAndCosmeticSelfChangesDoNotIncreaseNativeRevision() {
        val f = Fixture(); val start = f.load()
        f.api.accept(TdApi.UpdateUser(user().apply { id = 99; accentColorId = 8 }))
        f.api.accept(TdApi.UpdateUser(user().apply { firstName = "Changed" }))
        assertEquals(start.nativeRevision, f.load().nativeRevision)
        f.api.accept(TdApi.UpdateUser(user().apply { isPremium = false }))
        assertEquals(start.nativeRevision + 1, f.load(user().apply { isPremium = false }).nativeRevision)
    }

    @Test fun newerSelfUpdateWinsOverPendingGetMeAndBlocksAStaleWrite() {
        val f = Fixture(); f.names(); val start = f.load()
        val results = f.save(start, SettingsProfileColorValue(8))
        f.api.accept(TdApi.UpdateUser(user().apply { accentColorId = 9 }))
        f.answerMe()
        assertEquals(SettingsError.CONFLICT, results.single().error)
        assertEquals(0, f.writes())
    }

    @Test fun nonPremiumAndAccessLossNeverReachEitherSetter() {
        listOf(user().apply { isPremium = false }, user().apply { haveAccess = false }).forEach { self ->
            val f = Fixture(); f.names(); val start = f.load(self)
            val results = f.save(start, SettingsProfileColorValue(8)); f.last.answer(self)
            assertNotNull(results.single().error)
            assertEquals(0, f.writes())
        }
    }

    @Test fun nameSaveUsesOnlyOnePairAndOkReadbackChecksActualState() {
        val f = Fixture(); f.names(); val start = f.load()
        val results = f.save(start, SettingsProfileColorValue(8)); f.answerMe()
        val set = assertIs<TdApi.SetAccentColor>(f.last.command)
        assertEquals(8, set.accentColorId); assertEquals(0L, set.backgroundCustomEmojiId)
        f.last.answer(TdApi.Ok()); f.answerMe(user().apply { accentColorId = 8 })
        assertTrue(results.single().locallyAccepted); assertNull(results.single().error)
        assertEquals(1, f.writes())
        assertTrue(f.requests.none { it.command is TdApi.SetProfileAccentColor })
    }

    @Test fun profileResetIsTheActualMinusOneZeroPairAndNeverNeedsNameGiftPermission() {
        val f = Fixture(); val start = f.load(user().apply { upgradedGiftColors = gift() })
        val results = f.save(start, SettingsProfileColorValue(-1), SettingsProfileColorPart.PROFILE)
        f.answerMe(user().apply { upgradedGiftColors = gift() })
        val set = assertIs<TdApi.SetProfileAccentColor>(f.last.command)
        assertEquals(-1, set.profileAccentColorId); assertEquals(0L, set.profileBackgroundCustomEmojiId)
        f.last.answer(TdApi.Ok())
        f.answerMe(user().apply { profileAccentColorId = -1; upgradedGiftColors = gift() })
        assertTrue(results.single().locallyAccepted); assertNull(results.single().error)
        assertNotNull(results.single().actual?.values?.gift)
    }

    @Test fun nameResetUsesSelfModuloSevenAndZeroIsAValidColor() {
        val f = Fixture(); val start = f.load(user().apply { accentColorId = 99 })
        val results = f.save(start, SettingsProfileColorValue(1)); f.answerMe(user().apply { accentColorId = 99 })
        assertEquals(1, assertIs<TdApi.SetAccentColor>(f.last.command).accentColorId)
        f.last.answer(TdApi.Error(400, "no")); assertFalse(results.single().locallyAccepted)
        val g = Fixture(); g.names(); val data = g.load(user().apply { accentColorId = 8 })
        g.save(data, SettingsProfileColorValue(0)); g.answerMe(user().apply { accentColorId = 8 })
        assertEquals(0, assertIs<TdApi.SetAccentColor>(g.last.command).accentColorId)
    }

    @Test fun unknownCatalogRejectsANewColorButRetainsCurrentHiddenColor() {
        val f = Fixture(); val data = f.load(user().apply { accentColorId = 99 })
        val results = f.save(data, SettingsProfileColorValue(8)); f.answerMe(user().apply { accentColorId = 99 })
        assertEquals(SettingsError.INVALID_VALUE, results.single().error)
        f.save(data, SettingsProfileColorValue(99)); f.answerMe(user().apply { accentColorId = 99 })
        assertEquals(99, assertIs<TdApi.SetAccentColor>(f.last.command).accentColorId)
    }

    @Test fun changedCatalogRejectsEvenAStillAvailableCandidate() {
        val f = Fixture(); f.names(); val data = f.load()
        val results = f.save(data, SettingsProfileColorValue(8)); f.names(); f.answerMe()
        assertEquals(SettingsError.CONFLICT, results.single().error); assertEquals(0, f.writes())
    }

    @Test fun nameGiftReplacementRequiresExplicitConfirmation() {
        val f = Fixture(); f.names(); val start = f.load(user().apply { upgradedGiftColors = gift() })
        val results = f.save(start, SettingsProfileColorValue(8)); f.answerMe(user().apply { upgradedGiftColors = gift() })
        assertEquals(SettingsError.CONFLICT, results.single().error); assertEquals(0, f.writes())
        f.save(start, SettingsProfileColorValue(8), replaceGift = true)
        f.answerMe(user().apply { upgradedGiftColors = gift() })
        assertIs<TdApi.SetAccentColor>(f.last.command)
    }

    @Test fun failedOrMismatchedReadbackIsAcceptedWithAnExplicitError() {
        listOf<TdApi.Object>(TdApi.Error(500, "failed read"), user()).forEach { readback ->
            val f = Fixture(); f.names(); val start = f.load()
            val results = f.save(start, SettingsProfileColorValue(8)); f.answerMe(); f.last.answer(TdApi.Ok())
            f.last.answer(readback)
            assertTrue(results.single().locallyAccepted); assertNotNull(results.single().error)
            assertEquals(1, f.writes())
        }
    }

    @Test fun ownUpdateBeforeOkDoesNotInvalidateItsAcceptedReadback() {
        val f = Fixture(); f.names(); val data = f.load()
        val results = f.save(data, SettingsProfileColorValue(8)); f.answerMe()
        f.api.accept(TdApi.UpdateUser(user().apply { accentColorId = 8 }))
        f.last.answer(TdApi.Ok()); f.answerMe(user().apply { accentColorId = 8 })
        assertTrue(results.single().locallyAccepted); assertNull(results.single().error)
    }

    @Test fun setErrorDuplicateCallbacksAndThrownSendCompleteExactlyOnce() {
        val f = Fixture(); f.names(); val data = f.load()
        val results = f.save(data, SettingsProfileColorValue(8)); f.answerMe()
        val set = f.last
        set.answer(TdApi.Error(429, "private")); set.answer(TdApi.Ok()); set.failed()
        assertEquals(SettingsError.RATE_LIMITED, results.single().error)
        val thrown = OfficialTdSettingsProfileColors { _, _, _ -> throw IllegalStateException("secret") }
        val reads = mutableListOf<SettingsResult<SettingsProfileColorsData>>()
        thrown.load(43, { true }, reads::add)
        assertEquals(SettingsError.REQUEST_FAILED, reads.single().error)
    }

    @Test fun navigationAndRetirementPreserveLateNativeAcceptance() {
        listOf(false, true).forEach { retire ->
            val f = Fixture(); f.names(); val data = f.load(); var active = true
            val results = mutableListOf<SettingsProfileColorMutationResult>()
            f.api.save(data, SettingsProfileColorPart.NAME, SettingsProfileColorValue(8), false, { active }, results::add)
            f.answerMe(); val set = f.last
            active = false; if (retire) f.api.retire()
            assertTrue(results.isEmpty())
            set.answer(TdApi.Ok()); set.failed()
            assertTrue(results.single().locallyAccepted)
            assertEquals(SettingsError.NOT_READY, results.single().error)
            assertEquals(1, f.writes())
        }
    }

    @Test fun inactivePreflightAndBusyMutationNeverCreateAnotherSetter() {
        val f = Fixture(); f.names(); val data = f.load(); var active = true
        val results = mutableListOf<SettingsProfileColorMutationResult>()
        f.api.save(data, SettingsProfileColorPart.NAME, SettingsProfileColorValue(8), false, { active }, results::add)
        val pending = f.last
        val busy = f.save(data, SettingsProfileColorValue(9))
        assertEquals(SettingsError.NOT_READY, busy.single().error)
        active = false; pending.answer(user())
        assertEquals(SettingsError.NOT_READY, results.single().error); assertEquals(0, f.writes())
    }

    @Test fun retirementDuringReadbackCompletesAlreadyAcceptedSaveWithoutWaitingForever() {
        val f = Fixture(); f.names(); val data = f.load()
        val results = f.save(data, SettingsProfileColorValue(8)); f.answerMe(); f.last.answer(TdApi.Ok())
        val readback = f.last; assertIs<TdApi.GetMe>(readback.command)
        f.api.retire()
        assertTrue(results.single().locallyAccepted)
        assertEquals(SettingsError.NOT_READY, results.single().error)
        readback.answer(user().apply { accentColorId = 8 }); readback.failed()
        assertEquals(1, results.size)
    }

    @Test fun leaseCallbackChangingNativeStateIsRecheckedBeforeSet() {
        val f = Fixture(); f.names(); val data = f.load()
        var checks = 0
        val results = mutableListOf<SettingsProfileColorMutationResult>()
        f.api.save(data, SettingsProfileColorPart.NAME, SettingsProfileColorValue(8), false, {
            checks++
            if (checks == 4) f.api.accept(TdApi.UpdateUser(user().apply { accentColorId = 9 }))
            true
        }, results::add)
        f.answerMe()
        assertEquals(SettingsError.CONFLICT, results.single().error)
        assertEquals(0, f.writes())
    }

    @Test fun resolveUsesSignedRealFullTypeIdentityAndFiltersForeignOrMissingResults() {
        val f = Fixture(); val results = mutableListOf<SettingsResult<List<SettingsBackgroundEmoji>>>()
        f.api.resolveEmojis(listOf(-7, 8, 9), { true }, results::add)
        assertContentEquals(longArrayOf(-7, 8, 9), assertIs<TdApi.GetCustomEmojiStickers>(f.last.command).customEmojiIds)
        f.last.answer(TdApi.Stickers(arrayOf(sticker(8, 111).apply {
            fullType = TdApi.StickerFullTypeCustomEmoji(8, true)
        }, sticker(999, 112), sticker(-7, 113),
            sticker(9, 114).apply { fullType = TdApi.StickerFullTypeRegular() })))
        assertEquals(listOf(8L, -7L), results.single().value?.map { it.customEmojiId })
        assertEquals(listOf(111L, 113L), results.single().value?.map { it.sticker.id })
        assertEquals(listOf(true, false), results.single().value?.map { it.needsRepainting })
        assertEquals(1, f.requests.size)
        f.names(); val data = f.load()
        f.save(data, SettingsProfileColorValue(8, -7)); f.answerMe()
        assertEquals(-7L, assertIs<TdApi.SetAccentColor>(f.last.command).backgroundCustomEmojiId)
    }

    @Test fun unselectedUnknownEmojiCannotBeSubmittedAndCurrentSignedIdCanRemain() {
        val f = Fixture(); f.names(); val data = f.load(user().apply { backgroundCustomEmojiId = -77 })
        val unknown = f.save(data, SettingsProfileColorValue(8, -88)); f.answerMe(user().apply { backgroundCustomEmojiId = -77 })
        assertEquals(SettingsError.INVALID_VALUE, unknown.single().error)
        f.save(data, SettingsProfileColorValue(8, -77)); f.answerMe(user().apply { backgroundCustomEmojiId = -77 })
        assertEquals(-77L, assertIs<TdApi.SetAccentColor>(f.last.command).backgroundCustomEmojiId)
    }

    @Test fun defaultBackgroundMetadataIsExplicitBoundedAndDoesNotDownloadFiles() {
        val f = Fixture(); val results = mutableListOf<SettingsResult<List<SettingsBackgroundEmoji>>>()
        f.api.backgroundEmojis({ true }, results::add)
        assertIs<TdApi.GetDefaultBackgroundCustomEmojiStickers>(f.last.command)
        f.last.answer(TdApi.Stickers(arrayOf(sticker(-7, 1))))
        assertEquals(-7L, results.single().value?.single()?.customEmojiId)
        val invalid = mutableListOf<SettingsResult<List<SettingsBackgroundEmoji>>>()
        f.api.resolveEmojis(List(201) { it + 1L }, { true }, invalid::add)
        assertEquals(SettingsError.INVALID_VALUE, invalid.single().error)
        assertEquals(1, f.requests.size)
    }

    @Test fun oversizedDefaultMetadataIsAnErrorWithoutAuthorizingACroppedSubset() {
        val f = Fixture(); val results = mutableListOf<SettingsResult<List<SettingsBackgroundEmoji>>>()
        f.api.backgroundEmojis({ true }, results::add)
        f.last.answer(TdApi.Stickers(Array(4097) { sticker(it + 1L, it + 1) }))
        assertEquals(SettingsError.INVALID_VALUE, results.single().error)
        f.names(); val data = f.load()
        val save = f.save(data, SettingsProfileColorValue(8, 1)); f.answerMe()
        assertEquals(SettingsError.INVALID_VALUE, save.single().error)
        assertEquals(0, f.writes())
    }

    @Test fun retirementCompletesReadsOnceClearsMetadataAndIgnoresLateUpdates() {
        val f = Fixture(); f.names()
        val reads = mutableListOf<SettingsResult<SettingsProfileColorsData>>()
        f.api.load(43, { true }, reads::add); val request = f.last
        f.api.retire(); f.api.retire(); request.answer(user()); request.failed(); f.names()
        assertEquals(SettingsError.NOT_READY, reads.single().error)
        assertNull(f.api.catalog().nameColors)
        val again = f.save(SettingsProfileColorsData(43, true, SettingsProfileColorsValues(SettingsProfileColorValue(0), SettingsProfileColorValue(-1)), f.api.catalog()), SettingsProfileColorValue(8))
        assertEquals(SettingsError.NOT_READY, again.single().error)
    }

    @Test fun boundedPendingReadsRejectTheThirtyThirdAndRetirementReleasesAll() {
        val f = Fixture(); val results = mutableListOf<SettingsResult<SettingsProfileColorsData>>()
        repeat(33) { f.api.load(43, { true }, results::add) }
        assertEquals(32, f.requests.size); assertEquals(1, results.size)
        f.api.retire(); assertEquals(33, results.size)
        f.requests.forEach { it.answer(user()) }; assertEquals(33, results.size)
    }

    @Test fun activeObserverAndResultMayReenterAndThrowWithoutHoldingHelperLock() {
        val f = Fixture()
        fun fromOtherThread() {
            val thread = Thread { f.api.catalog() }; thread.start(); thread.join(2000)
            assertFalse(thread.isAlive, "foreign callback was invoked under helper lock")
        }
        f.api.observe { fromOtherThread(); throw IllegalStateException("observer") }
        f.names()
        var count = 0
        f.api.load(43, { fromOtherThread(); true }) { fromOtherThread(); count++; throw IllegalStateException("consumer") }
        val pending = f.last; pending.answer(user()); pending.failed()
        assertEquals(1, count)
    }

    private class Fixture {
        data class Request(val command: TdApi.Function<*>, val answer: (TdApi.Object) -> Unit, val failed: () -> Unit)
        val requests = mutableListOf<Request>()
        val api = OfficialTdSettingsProfileColors { command, answer, failed -> requests += Request(command, answer, failed) }
        val last get() = requests.last()
        fun answerMe(me: TdApi.User = user()) { assertIs<TdApi.GetMe>(last.command); last.answer(me) }
        fun names() { api.accept(TdApi.UpdateAccentColors(arrayOf(color(8), color(9)), intArrayOf(9, 0, 8))) }
        fun load(me: TdApi.User = user()): SettingsProfileColorsData {
            var result: SettingsResult<SettingsProfileColorsData>? = null
            api.load(43, { true }) { result = it }; answerMe(me)
            return assertNotNull(result?.value)
        }
        fun save(data: SettingsProfileColorsData, desired: SettingsProfileColorValue,
            part: SettingsProfileColorPart = SettingsProfileColorPart.NAME, replaceGift: Boolean = false,
        ): MutableList<SettingsProfileColorMutationResult> {
            val results = mutableListOf<SettingsProfileColorMutationResult>()
            api.save(data, part, desired, replaceGift, { true }, results::add); return results
        }
        fun writes() = requests.count { it.command is TdApi.SetAccentColor || it.command is TdApi.SetProfileAccentColor }
    }

    companion object {
        private fun color(id: Int) = TdApi.AccentColor(id, 0, intArrayOf(1, 2), intArrayOf(3), 0)
        private fun user() = TdApi.User().apply {
            id = 43; type = TdApi.UserTypeRegular(); haveAccess = true; isPremium = true
            accentColorId = 0; profileAccentColorId = 17
        }
        private fun gift() = TdApi.UpgradedGiftColors(1, 2, 3, 4, intArrayOf(5), 6, intArrayOf(7))
        private fun sticker(emojiId: Long, fileId: Int) = TdApi.Sticker().apply {
            id = fileId.toLong(); setId = 77; width = 100; height = 100; emoji = "x"
            format = TdApi.StickerFormatWebp(); fullType = TdApi.StickerFullTypeCustomEmoji(emojiId, false)
            sticker = TdApi.File().apply { id = fileId }
        }
    }
}
