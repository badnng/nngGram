package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import kotlin.test.*

class OfficialTdSettingsProfilePhotoHistoryTest {
    @Test fun pageReadsOnlyActualSelfAndPreservesRawOffsetsWhileFilteringBadAndFallbackEntries() {
        val f = Fixture()
        val results = mutableListOf<SettingsResult<SettingsProfilePhotoHistoryPage>>()
        f.api.photoHistoryPage(43, 7, 32, { true }, results::add)
        f.identity(personal = 11, public = 12)
        val request = assertIs<TdApi.GetUserProfilePhotos>(f.last.command)
        assertEquals(43L, request.userId); assertEquals(7, request.offset); assertEquals(32, request.limit)
        f.photos(-3, photo(-92), photo(0), photo(11), photo(12), photo(-92), photo(93))
        val page = assertNotNull(results.single().value)
        assertEquals(6, page.rawCount); assertNull(page.totalCount); assertEquals(7, page.offset)
        assertEquals(listOf(-92L, -92L, 93L), page.entries.map { it.photoId })
        assertEquals(listOf(7, 11, 12), page.entries.map { it.rawOffset })
        assertEquals(SettingsProfilePhotoIdentity(43, 90, 11, 12), page.identity)
        assertEquals(3, f.requests.size)
        assertTrue(f.requests.all { it.command is TdApi.GetMe || it.command is TdApi.GetUserFullInfo || it.command is TdApi.GetUserProfilePhotos })
    }

    @Test fun knownShortAndEmptyPagesAreRealSuccessesWithoutInventedTotalsOrAutomaticRequests() {
        val f = Fixture()
        val results = mutableListOf<SettingsResult<SettingsProfilePhotoHistoryPage>>()
        f.api.photoHistoryPage(43, 0, 32, { true }, results::add)
        f.identity(); f.photos(2, photo(90), photo(91))
        val first = assertNotNull(results.single().value)
        assertEquals(2, first.totalCount); assertEquals(2, first.rawCount)
        assertEquals(listOf(90L, 91L), first.entries.map { it.photoId })
        f.api.photoHistoryPage(43, 2, 32, { true }, results::add)
        f.identity(); f.photos(2)
        assertEquals(0, results.last().value?.rawCount)
        assertTrue(assertNotNull(results.last().value).entries.isEmpty())
        assertEquals(6, f.requests.size)
    }

    @Test fun pageInputBoundsRejectWithoutAnyNativeRequest() {
        listOf(Triple(43L, -1, 32), Triple(43L, 0, 0), Triple(43L, 0, 101),
            Triple(0L, 0, 32), Triple(-43L, 0, 32), Triple(43L, Int.MAX_VALUE, 1)).forEach { (id, offset, limit) ->
            val f = Fixture(); var result: SettingsResult<SettingsProfilePhotoHistoryPage>? = null
            f.api.photoHistoryPage(id, offset, limit, { true }) { result = it }
            assertEquals(SettingsError.INVALID_VALUE, result?.error)
            assertTrue(f.requests.isEmpty())
        }
    }

    @Test fun malformedNativeArrayOrOversizeResponseNeverPretendsToBeEmptySuccess() {
        listOf<TdApi.ChatPhotos>(TdApi.ChatPhotos(), TdApi.ChatPhotos(200, Array(101) { photo(it + 1L) })).forEach { response ->
            val f = Fixture(); var result: SettingsResult<SettingsProfilePhotoHistoryPage>? = null
            f.api.photoHistoryPage(43, 0, 100, { true }) { result = it }
            f.identity(); f.last.answer(response)
            assertEquals(SettingsError.REQUEST_FAILED, result?.error); assertNull(result?.value)
        }
    }

    @Test fun metadataCopiesActualFilesDimensionsAndMiniBytesAndUsesOnlyActualAnimationStickerMarkers() {
        val f = Fixture(); var result: SettingsResult<SettingsProfilePhotoHistoryPage>? = null
        val file = TdApi.File().apply {
            id = 72; remote = TdApi.RemoteFile().apply { uniqueId = "native-file-72" }
        }
        val size = TdApi.PhotoSize().apply { photo = file; width = 160; height = 180 }
        val bytes = byteArrayOf(1, 2, 3)
        val mini = TdApi.Minithumbnail(40, 30, bytes)
        val native = photo(-91).apply {
            addedDate = 123; sizes = arrayOf(size); minithumbnail = mini
            smallAnimation = TdApi.AnimatedChatPhoto(); sticker = TdApi.ChatPhotoSticker()
        }
        val array = arrayOf(native)
        f.api.photoHistoryPage(43, 0, 32, { true }) { result = it }; f.identity()
        f.last.answer(TdApi.ChatPhotos(-1, array))
        val entry = assertNotNull(result?.value).entries.single()
        file.id = 999; file.remote.uniqueId = "changed"; size.width = 999; native.id = 999; array[0] = photo(999)
        bytes[0] = 99; mini.width = 90
        assertEquals(-91L, entry.photoId); assertEquals(123, entry.addedDate)
        val variant = entry.variants.single()
        assertEquals(72, variant.file.id); assertEquals("native-file-72", variant.file.uniqueId)
        assertEquals(160, variant.width); assertEquals(180, variant.height)
        assertContentEquals(byteArrayOf(1, 2, 3), entry.minithumbnail?.encodedCopy())
        assertEquals(40, entry.minithumbnail?.width); assertTrue(entry.hasAnimation); assertTrue(entry.hasSticker)
    }

    @Test fun variantsAndMinisAreBoundedAndMalformedFilesNeverBecomeSyntheticVariants() {
        val f = Fixture(); var result: SettingsResult<SettingsProfilePhotoHistoryPage>? = null
        val sizes = Array(20) { index -> TdApi.PhotoSize().apply {
            photo = TdApi.File().apply { id = index + 1 }; width = 120; height = 120
        } }
        sizes[0].width = 0; sizes[1].height = 8193; sizes[2].photo = null; sizes[3].photo.id = 0
        f.api.photoHistoryPage(43, 0, 32, { true }) { result = it }; f.identity()
        f.photos(1, photo(91).apply {
            this.sizes = sizes; minithumbnail = TdApi.Minithumbnail(256, 10, byteArrayOf(1))
        })
        val entry = assertNotNull(result?.value).entries.single()
        assertEquals(12, entry.variants.size)
        assertEquals((5..16).toList(), entry.variants.map { it.file.id })
        assertNull(entry.minithumbnail); assertFalse(entry.hasAnimation); assertFalse(entry.hasSticker)
    }

    @Test fun oversizedOrEmptyMiniBytesAreIgnoredWithoutDiscardingRealPhotoIdentity() {
        listOf(byteArrayOf(), ByteArray(65_537)).forEach { bytes ->
            val f = Fixture(); var result: SettingsResult<SettingsProfilePhotoHistoryPage>? = null
            f.api.photoHistoryPage(43, 0, 32, { true }) { result = it }; f.identity()
            f.photos(1, photo(91).apply { minithumbnail = TdApi.Minithumbnail(40, 40, bytes) })
            assertEquals(91L, result?.value?.entries?.single()?.photoId)
            assertNull(result?.value?.entries?.single()?.minithumbnail)
        }
    }

    @Test fun mismatchedGetMeAccountStopsBeforeFullInfoAndNeverUsesRequestedForeignUserAsSelf() {
        val f = Fixture(); var result: SettingsResult<SettingsProfilePhotoHistoryPage>? = null
        f.api.photoHistoryPage(43, 0, 32, { true }) { result = it }
        f.me(userId = 44)
        assertEquals(SettingsError.NOT_READY, result?.error); assertEquals(1, f.requests.size)
    }

    @Test fun conflictingNonzeroMainPhotoSourcesStopBeforeHistoryAndPreserveSignedIds() {
        val f = Fixture(); var result: SettingsResult<SettingsProfilePhotoHistoryPage>? = null
        f.api.photoHistoryPage(43, 0, 32, { true }) { result = it }
        f.me(-90); f.full(-91)
        assertEquals(SettingsError.CONFLICT, result?.error); assertEquals(2, f.requests.size)
    }

    @Test fun singleAvailableMainPhotoSourceAndZeroAbsenceAreRepresentedWithoutInventedIdentity() {
        listOf(Pair(0L, -90L), Pair(-90L, 0L), Pair(0L, 0L)).forEach { (me, full) ->
            val f = Fixture(); var result: SettingsResult<SettingsProfilePhotoHistoryPage>? = null
            f.api.photoHistoryPage(43, 0, 32, { true }) { result = it }
            f.me(me); f.full(full); f.photos(0)
            assertEquals((if (me != 0L) me else full).takeIf { it != 0L }, result?.value?.identity?.currentPhotoId)
        }
    }

    @Test fun reuseSubmitsOnlyTheExactPreviousIdAndPrivateFlagAfterTwoRoleChecks() {
        val f = Fixture(); val results = mutableListOf<SettingsResult<Unit>>()
        f.api.reuseHistoryPhoto(43, target(-91, 8), { true }, results::add)
        f.identity(); f.targetSlice(-91, 8); f.identity()
        val set = assertIs<TdApi.SetProfilePhoto>(f.last.command)
        assertFalse(set.isPublic)
        assertEquals(-91L, assertIs<TdApi.InputChatPhotoPrevious>(set.photo).chatPhotoId)
        assertTrue(results.isEmpty()); f.last.answer(TdApi.Ok())
        assertEquals(Unit, results.single().value)
        assertEquals(6, f.requests.size)
        assertFalse(f.requests.any { it.command is TdApi.DownloadFile || it.command is TdApi.GetChatHistory })
    }

    @Test fun deleteSubmitsOnlyTheExactSignedHistoryIdAndDoesNotUseCurrentPhotoRemoval() {
        val f = Fixture(); var result: SettingsResult<Unit>? = null
        f.api.deleteHistoryPhoto(43, target(-91, 8), { true }) { result = it }
        f.identity(); f.targetSlice(-91, 8); f.identity()
        assertEquals(-91L, assertIs<TdApi.DeleteProfilePhoto>(f.last.command).profilePhotoId)
        f.last.answer(TdApi.Ok()); assertEquals(Unit, result?.value)
        assertTrue(f.requests.none { it.command is TdApi.SetProfilePhoto })
    }

    @Test fun invalidTargetsAreRejectedBeforeReadAndFrozenProtectedRolesCannotBeDeleted() {
        listOf(target(0), target(91, -1), target(91, Int.MAX_VALUE), target(91).copy(identity = identity(userId = 44))).forEach { target ->
            val f = Fixture(); var result: SettingsResult<Unit>? = null
            f.api.deleteHistoryPhoto(43, target, { true }) { result = it }
            assertEquals(SettingsError.INVALID_VALUE, result?.error); assertTrue(f.requests.isEmpty())
        }
        listOf(identity(current = 91), identity(personal = 91), identity(public = 91)).forEach { roles ->
            val f = Fixture(); var result: SettingsResult<Unit>? = null
            f.api.deleteHistoryPhoto(43, target(91).copy(identity = roles), { true }) { result = it }
            assertEquals(SettingsError.CONFLICT, result?.error); assertTrue(f.requests.isEmpty())
        }
    }

    @Test fun changedMainPersonalPublicOrBotRolePreventsBothMutationTypesBeforeTargetRead() {
        listOf(identity(current = 92), identity(personal = 91), identity(public = 91), identity(bot = true)).forEach { roles ->
            listOf(false, true).forEach { reuse ->
                val f = Fixture(); var result: SettingsResult<Unit>? = null
                if (reuse) f.api.reuseHistoryPhoto(43, target(91), { true }) { result = it }
                else f.api.deleteHistoryPhoto(43, target(91), { true }) { result = it }
                f.identity(roles)
                assertEquals(SettingsError.CONFLICT, result?.error); assertEquals(2, f.requests.size)
            }
        }
    }

    @Test fun missingMovedOrReplacedTargetAtFrozenOffsetCannotAuthorizeAWrite() {
        listOf(emptyArray<TdApi.ChatPhoto>(), arrayOf(photo(92)), arrayOf(photo(91), photo(92))).forEach { array ->
            val f = Fixture(); var result: SettingsResult<Unit>? = null
            f.api.deleteHistoryPhoto(43, target(91, 6), { true }) { result = it }; f.identity()
            assertEquals(6, assertIs<TdApi.GetUserProfilePhotos>(f.last.command).offset)
            f.last.answer(TdApi.ChatPhotos(20, array))
            assertEquals(SettingsError.CONFLICT, result?.error); assertEquals(3, f.requests.size)
        }
    }

    @Test fun targetBecomingCurrentOrFallbackDuringSliceReadConflictsAtFinalRoleCheck() {
        listOf(identity(current = 91), identity(personal = 91), identity(public = 91)).forEach { roles ->
            val f = Fixture(); var result: SettingsResult<Unit>? = null
            f.api.deleteHistoryPhoto(43, target(91), { true }) { result = it }
            f.identity(); f.targetSlice(91); f.identity(roles)
            assertEquals(SettingsError.CONFLICT, result?.error); assertEquals(5, f.requests.size)
            assertTrue(f.requests.none { it.command is TdApi.DeleteProfilePhoto })
        }
    }

    @Test fun botPreviousIsExplicitlyUnsupportedAndNeverDownloadsOrUploadsAReplacement() {
        val f = Fixture(); var result: SettingsResult<Unit>? = null
        f.api.reuseHistoryPhoto(43, target(91).copy(identity = identity(bot = true)), { true }) { result = it }
        f.identity(bot = true)
        assertEquals(SettingsError.UNSUPPORTED, result?.error); assertEquals(2, f.requests.size)
        assertTrue(f.requests.none { it.command is TdApi.SetProfilePhoto || it.command is TdApi.DownloadFile })
    }

    @Test fun botHistoryDeletionIsUnsupportedBecauseNativeWouldRewriteTheIdToCurrentPhoto() {
        val f = Fixture(); var result: SettingsResult<Unit>? = null
        f.api.deleteHistoryPhoto(43, target(91).copy(identity = identity(bot = true)), { true }) { result = it }
        f.identity(bot = true)
        assertEquals(SettingsError.UNSUPPORTED, result?.error)
        assertEquals(2, f.requests.size)
        assertTrue(f.requests.none { it.command is TdApi.DeleteProfilePhoto })
    }

    @Test fun ownerCancellationAtEveryReadBoundaryStopsChainingAndCompletesOnce() {
        repeat(5) { cancelledStage ->
            val f = Fixture(); var active = true
            val results = mutableListOf<SettingsResult<Unit>>()
            f.api.deleteHistoryPhoto(43, target(91), { active }, results::add)
            repeat(cancelledStage) { stage -> f.answerRead(stage) }
            val pending = f.last; active = false
            f.answerRead(cancelledStage)
            pending.answer(TdApi.Ok()); pending.failed()
            assertEquals(SettingsError.NOT_READY, results.single().error)
            assertEquals(cancelledStage + 1, f.requests.size)
        }
    }

    @Test fun retiredOwnerCannotPublishPageOrStartEvenTheFirstRequest() {
        val f = Fixture(); var active = false
        val results = mutableListOf<SettingsResult<SettingsProfilePhotoHistoryPage>>()
        f.api.photoHistoryPage(43, 0, 32, { active }, results::add)
        assertEquals(SettingsError.NOT_READY, results.single().error); assertTrue(f.requests.isEmpty())
        active = true
        f.api.photoHistoryPage(43, 0, 32, { active }, results::add); f.identity()
        active = false; f.photos(1, photo(91))
        assertEquals(SettingsError.NOT_READY, results.last().error); assertNull(results.last().value)
    }

    @Test fun dispatchedMutationLateOkIsStillAcceptedOnceEvenIfOwnerClosed() {
        listOf(false, true).forEach { reuse ->
            val f = Fixture(); var active = true
            val results = mutableListOf<SettingsResult<Unit>>()
            if (reuse) f.api.reuseHistoryPhoto(43, target(91), { active }, results::add)
            else f.api.deleteHistoryPhoto(43, target(91), { active }, results::add)
            f.identity(); f.targetSlice(91); f.identity()
            val write = f.last; active = false
            write.answer(TdApi.Ok()); write.answer(TdApi.Error(500, "private error")); write.failed()
            assertEquals(Unit, results.single().value); assertNull(results.single().error)
        }
    }

    @Test fun nativeErrorsAreMappedWithoutLeakingTheirTextOrRetryingTheMutation() {
        listOf(403 to SettingsError.FORBIDDEN, 429 to SettingsError.RATE_LIMITED, 400 to SettingsError.INVALID_VALUE,
            500 to SettingsError.REQUEST_FAILED).forEach { (code, error) ->
            val f = Fixture(); val results = mutableListOf<SettingsResult<Unit>>()
            f.api.reuseHistoryPhoto(43, target(91), { true }, results::add)
            f.identity(); f.targetSlice(91); f.identity()
            f.last.answer(TdApi.Error(code, "private native error text")); f.last.answer(TdApi.Ok())
            assertEquals(error, results.single().error); assertFalse(results.toString().contains("private"))
            assertEquals(1, f.requests.count { it.command is TdApi.SetProfilePhoto })
        }
    }

    @Test fun duplicateReadCallbacksCannotDuplicateFollowupRequestsOrFinalResults() {
        val f = Fixture(); val results = mutableListOf<SettingsResult<SettingsProfilePhotoHistoryPage>>()
        f.api.photoHistoryPage(43, 0, 32, { true }, results::add)
        val me = f.last; f.me(); me.answer(TdApi.User().apply { id = 43 }); me.failed()
        val full = f.last; f.full(); full.answer(TdApi.UserFullInfo()); full.failed()
        val page = f.last; f.photos(1, photo(91)); page.answer(TdApi.ChatPhotos(0, emptyArray())); page.failed()
        assertEquals(1, results.size); assertEquals(3, f.requests.size)
    }

    @Test fun transportFailuresAndExceptionsAreSingleExplicitFailuresWithoutSyntheticSuccess() {
        val results = mutableListOf<SettingsResult<SettingsProfilePhotoHistoryPage>>()
        val api = OfficialTdSettingsProfilePhotoHistory { _, _, _ -> throw IllegalStateException("private") }
        api.page(43, 0, 32, { true }, results::add)
        assertEquals(SettingsError.REQUEST_FAILED, results.single().error)
        val f = Fixture(); val other = mutableListOf<SettingsResult<SettingsProfilePhotoHistoryPage>>()
        f.api.photoHistoryPage(43, 0, 32, { true }, other::add)
        f.identity(); f.last.failed(); f.last.answer(TdApi.ChatPhotos(0, emptyArray()))
        assertEquals(SettingsError.NOT_READY, other.single().error)
    }

    @Test fun lateNativeRejectionRetainsItsActualErrorAfterOwnerRetirement() {
        val f = Fixture(); var active = true
        val results = mutableListOf<SettingsResult<Unit>>()
        f.api.deleteHistoryPhoto(43, target(91), { active }, results::add)
        f.identity(); f.targetSlice(91); f.identity(); active = false
        f.last.answer(TdApi.Error(403, "private native refusal")); f.last.answer(TdApi.Ok())
        assertEquals(SettingsError.FORBIDDEN, results.single().error)
        assertNull(results.single().value)
    }

    @Test fun accountChangingDuringTargetReadStopsBeforeDispatchAndDoesNotUseForeignFullInfo() {
        val f = Fixture(); var result: SettingsResult<Unit>? = null
        f.api.deleteHistoryPhoto(43, target(91), { true }) { result = it }
        f.identity(); f.targetSlice(91); f.me(userId = 44)
        assertEquals(SettingsError.NOT_READY, result?.error); assertEquals(4, f.requests.size)
        assertTrue(f.requests.none { it.command is TdApi.DeleteProfilePhoto })
    }

    @Test fun unimplementedSessionMethodsAreExplicitlyUnsupportedRatherThanEmptySuccess() {
        val session = object : TdSettingsProfileExtrasSession {
            override fun load(userId: Long, isCurrent: () -> Boolean, result: (SettingsResult<SettingsProfileExtrasData>) -> Unit) = Unit
            override fun setBirthdate(userId: Long, expected: SettingsBirthdate?, desired: SettingsBirthdate?, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) = Unit
            override fun uploadPhoto(userId: Long, expectedPhotoId: Long?, photo: SettingsPreparedProfilePhoto, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) = Unit
            override fun removePhoto(userId: Long, photoId: Long, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) = Unit
        }
        var page: SettingsResult<SettingsProfilePhotoHistoryPage>? = null
        val mutations = mutableListOf<SettingsResult<Unit>>()
        session.photoHistoryPage(43, 0, 32, { true }) { page = it }
        session.reuseHistoryPhoto(43, target(91), { true }, mutations::add)
        session.deleteHistoryPhoto(43, target(91), { true }, mutations::add)
        assertEquals(SettingsError.UNSUPPORTED, page?.error); assertNull(page?.value)
        assertEquals(listOf(SettingsError.UNSUPPORTED, SettingsError.UNSUPPORTED), mutations.map { it.error })
    }

    @Test fun foreignOwnerAndResultCallbacksCanReenterWithoutAnInternalLock() {
        val f = Fixture(); var checks = 0
        val results = mutableListOf<SettingsResult<SettingsProfilePhotoHistoryPage>>()
        f.api.photoHistoryPage(43, 0, 32, { checks++; true }) { result ->
            results += result
            val worker = Thread {
                f.api.photoHistoryPage(43, 0, 0, { true }, results::add)
            }
            worker.start(); worker.join(1_000)
            assertFalse(worker.isAlive)
        }
        f.identity(); f.photos(0)
        assertEquals(2, results.size); assertTrue(checks > 0)
        assertEquals(SettingsError.INVALID_VALUE, results.last().error)
    }

    private class Request(val command: TdApi.Function<*>, val answer: (TdApi.Object) -> Unit, val failed: () -> Unit)
    private class Fixture {
        val requests = mutableListOf<Request>()
        val last get() = requests.last()
        val api = OfficialTdSettingsProfileExtras({ request, done, failed -> requests += Request(request, done, failed) })
        fun me(photoId: Long = 90, userId: Long = 43, bot: Boolean = false) {
            assertIs<TdApi.GetMe>(last.command)
            last.answer(TdApi.User().apply {
                id = userId; profilePhoto = TdApi.ProfilePhoto().apply { id = photoId }
                type = if (bot) TdApi.UserTypeBot() else TdApi.UserTypeRegular()
            })
        }
        fun full(photoId: Long = 90, personal: Long? = null, public: Long? = null) {
            assertEquals(43L, assertIs<TdApi.GetUserFullInfo>(last.command).userId)
            last.answer(TdApi.UserFullInfo().apply {
                photo = photo(photoId); personalPhoto = personal?.let(::photo); publicPhoto = public?.let(::photo)
            })
        }
        fun identity(current: Long = 90, personal: Long? = null, public: Long? = null, bot: Boolean = false) {
            me(current, bot = bot); full(current, personal, public)
        }
        fun identity(roles: SettingsProfilePhotoIdentity) {
            me(roles.currentPhotoId ?: 0, roles.userId, roles.isBot)
            full(roles.currentPhotoId ?: 0, roles.personalPhotoId, roles.publicPhotoId)
        }
        fun photos(total: Int, vararg photos: TdApi.ChatPhoto) {
            assertIs<TdApi.GetUserProfilePhotos>(last.command)
            last.answer(TdApi.ChatPhotos(total, photos.copyOf()))
        }
        fun targetSlice(id: Long, offset: Int = 2) {
            val request = assertIs<TdApi.GetUserProfilePhotos>(last.command)
            assertEquals(offset, request.offset); assertEquals(1, request.limit); assertEquals(43L, request.userId)
            photos(20, photo(id))
        }
        fun answerRead(stage: Int) {
            when (stage) { 0, 3 -> me(); 1, 4 -> full(); 2 -> targetSlice(91) }
        }
    }

    private companion object {
        fun photo(id: Long) = TdApi.ChatPhoto().apply { this.id = id }
        fun identity(userId: Long = 43, current: Long? = 90, personal: Long? = null, public: Long? = null, bot: Boolean = false) =
            SettingsProfilePhotoIdentity(userId, current, personal, public, bot)
        fun target(id: Long, offset: Int = 2) = SettingsProfilePhotoHistoryTarget(identity(), id, offset)
    }
}
