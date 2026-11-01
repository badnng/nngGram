package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import kotlin.test.*

/** Fake native source only; no user account, codec or UI acceptance. */
class OfficialTdCurrentProfilePhotoTest {
    @Test fun freshMatchingSelfAndPrimaryPermitCurrentEvenWhenRoleIdsAlias() {
        val f = Fixture(); f.open()
        f.me(photoId = -90, personal = true)
        f.full(main = photo(-90), personal = photo(-90), public = photo(-90))
        assertEquals(SettingsCurrentProfilePhotoStatus.READY, f.result().status)
        assertEquals(-90L, f.result().photo?.photoId)
        assertTrue(assertNotNull(f.result().photo).authorization > 0)
        assertTrue(f.calls.none { it.command is TdApi.GetUserProfilePhotos })
    }

    @Test fun emptyUnknownConflictAndActualAlternativeRoleRemainDistinct() {
        data class Scenario(val me: Long?, val main: Long?, val personal: Long?, val public: Long?,
            val personalFlag: Boolean, val expected: SettingsCurrentProfilePhotoStatus)
        val values = listOf(
            Scenario(null, null, null, null, false, SettingsCurrentProfilePhotoStatus.EMPTY),
            Scenario(90, null, null, null, false, SettingsCurrentProfilePhotoStatus.UNKNOWN),
            Scenario(90, 91, null, null, false, SettingsCurrentProfilePhotoStatus.CONFLICT),
            Scenario(null, 91, null, null, false, SettingsCurrentProfilePhotoStatus.CONFLICT),
            Scenario(90, 91, 90, null, true, SettingsCurrentProfilePhotoStatus.ROLE_CONFLICT),
            Scenario(90, null, null, 90, false, SettingsCurrentProfilePhotoStatus.ROLE_CONFLICT),
            Scenario(null, null, null, 90, false, SettingsCurrentProfilePhotoStatus.ROLE_CONFLICT),
        )
        values.forEach { value ->
            val f = Fixture(); f.open(); f.me(photoId = value.me, personal = value.personalFlag)
            f.full(value.main?.let(::photo), value.personal?.let(::photo), value.public?.let(::photo))
            assertEquals(value.expected, f.result().status); assertNull(f.result().photo)
        }
    }

    @Test fun selfMismatchStopsBeforeFullInfoAndNativeFailureDoesNotInventAnEmptyPhoto() {
        val mismatch = Fixture(); mismatch.open(); mismatch.me(userId = 44, photoId = 90)
        assertEquals(SettingsError.NOT_READY, mismatch.results.single().error); assertEquals(1, mismatch.calls.size)
        val failed = Fixture(); failed.open(); failed.me(photoId = 90)
        failed.last.reply(TdApi.Error(500, "private"))
        assertNotNull(failed.results.single().error); assertNull(failed.results.single().value)
        val malformed = Fixture(); malformed.open(); malformed.me(photoId = 0); malformed.full(null)
        assertEquals(SettingsCurrentProfilePhotoStatus.CONFLICT, malformed.result().status)
    }

    @Test fun currentMetadataIsDeepCopiedAndPublicFilesNeverExposeReadyPathsOrProgress() {
        val f = Fixture(); f.open(); f.me(photoId = 90)
        val file = nativeFile(7, "first", "secret")
        val size = TdApi.PhotoSize().apply { width = 160; height = 160; photo = file }
        val big = TdApi.AnimatedChatPhoto().apply { length = 640; this.file = nativeFile(8, "", "animation-secret"); mainFrameTimestamp = 1.25 }
        val bytes = byteArrayOf(1, 2, 3)
        val original = photo(90).apply { sizes = arrayOf(size); animation = big; minithumbnail = TdApi.Minithumbnail(10, 10, bytes) }
        f.full(original)
        val captured = assertNotNull(f.result().photo)
        assertNull(captured.variants.single().file.localPath); assertFalse(captured.variants.single().file.downloaded)
        assertNull(captured.animation?.file?.localPath); assertFalse(assertNotNull(captured.animation).file.downloaded)
        assertEquals("", captured.animation?.file?.uniqueId)
        file.id = 99; file.remote.uniqueId = "changed"; size.width = 999; big.length = 1; bytes[0] = 9
        assertEquals(7, captured.variants.single().file.id); assertEquals("first", captured.variants.single().file.uniqueId)
        assertEquals(640, captured.animation?.length); assertContentEquals(byteArrayOf(1, 2, 3), captured.minithumbnail?.encodedCopy())
        assertFailsWith<UnsupportedOperationException> { (captured.variants as MutableList<*>).clear() }
    }

    @Test fun onlyTheCurrentTypedTicketAndUseCanResolveActualFile() {
        val f = Fixture(); f.open(); f.me(photoId = 90)
        f.full(photo(90).apply { sizes = arrayOf(TdApi.PhotoSize().apply { width = 160; height = 160; photo = nativeFile(7, "file-7", "private") }) })
        val current = assertNotNull(f.result().photo)
        val request = SettingsCurrentProfilePhotoAuthorizedFileRequest(43, current.authorization, 90,
            SettingsCurrentProfilePhotoMediaUse.STATIC_PREVIEW, 200, 200)
        assertEquals("private", f.source.resolve(request).value?.file?.localPath)
        listOf(request.copy(userId = 44), request.copy(authorization = request.authorization + 1),
            request.copy(photoId = 91)).forEach { assertNotNull(f.source.resolve(it).error) }
        assertNotNull(f.source.resolveAnimation(request).error)
    }

    @Test fun unknownAnimationUniqueIsResolvedFromGetFileAndStableFactsCannotChange() {
        val f = Fixture(); f.open(); f.me(photoId = 90)
        f.full(photo(90).apply { animation = TdApi.AnimatedChatPhoto().apply {
            length = 640; file = nativeFile(8, "", ""); mainFrameTimestamp = 2.0
        } })
        val photo = assertNotNull(f.result().photo)
        val request = SettingsCurrentProfilePhotoAuthorizedFileRequest(43, photo.authorization, 90,
            SettingsCurrentProfilePhotoMediaUse.ANIMATION_PREVIEW, 200, 200)
        val unknown = assertNotNull(f.source.resolveAnimation(request).value).file
        assertEquals("", unknown.uniqueId)
        assertEquals(SettingsError.NOT_READY, f.source.observeFile(request, unknown).error)
        val known = unknown.copy(uniqueId = "native-known", size = 100)
        assertEquals(Unit, f.source.observeFile(request, known).value)
        assertEquals(SettingsError.CONFLICT, f.source.observeFile(request, known.copy(uniqueId = "different")).error)
        assertEquals(1, f.invalidations)
        assertNotNull(f.source.resolveAnimation(request).error)
    }

    @Test fun knownSizeIsRetainedEvenWhenUniqueIsStillUnknown() {
        val f = Fixture(); f.open(); f.me(photoId = 90)
        f.full(photo(90).apply { animation = TdApi.AnimatedChatPhoto().apply {
            length = 640; file = nativeFile(8, "", ""); mainFrameTimestamp = 2.0
        } })
        val photo = assertNotNull(f.result().photo)
        val request = SettingsCurrentProfilePhotoAuthorizedFileRequest(43, photo.authorization, 90,
            SettingsCurrentProfilePhotoMediaUse.ANIMATION_PREVIEW, 200, 200)
        val unknown = assertNotNull(f.source.resolveAnimation(request).value).file
        assertEquals(SettingsError.NOT_READY, f.source.observeFile(request, unknown.copy(size = 100)).error)
        assertEquals(SettingsError.CONFLICT, f.source.observeFile(request, unknown.copy(size = 200, uniqueId = "resolved")).error)
    }

    @Test fun unknownIdentityMayResolveWithoutChangingPreviouslyKnownSize() {
        val f = Fixture(); f.open(); f.me(photoId = 90)
        f.full(photo(90).apply { animation = TdApi.AnimatedChatPhoto().apply {
            length = 640; file = nativeFile(8, "", ""); mainFrameTimestamp = 2.0
        } })
        val photo = assertNotNull(f.result().photo)
        val request = SettingsCurrentProfilePhotoAuthorizedFileRequest(43, photo.authorization, 90,
            SettingsCurrentProfilePhotoMediaUse.ANIMATION_PREVIEW, 200, 200)
        val unknown = assertNotNull(f.source.resolveAnimation(request).value).file
        assertEquals(SettingsError.NOT_READY, f.source.observeFile(request, unknown.copy(size = 100)).error)
        assertEquals(Unit, f.source.observeFile(request, unknown.copy(size = 100, uniqueId = "resolved")).value)
        assertEquals(Unit, f.source.observeFile(request, unknown.copy(size = 100, uniqueId = "resolved", localPath = "moved")).value)
        assertEquals(SettingsError.CONFLICT, f.source.observeFile(request, unknown.copy(size = 100)).error)
    }

    @Test fun animationUsesLargeThenRealSmallAndRejectsInvalidTimestamp() {
        fun animation(id: Int, length: Int, timestamp: Double) = TdApi.AnimatedChatPhoto().apply {
            this.length = length; this.file = nativeFile(id, "file-$id", ""); mainFrameTimestamp = timestamp
        }
        val f = Fixture(); f.open(); f.me(photoId = 90)
        f.full(photo(90).apply { this.animation = animation(8, 640, 1.25); smallAnimation = animation(9, 160, 2.0) })
        val captured = assertNotNull(f.result().photo)
        val request = SettingsCurrentProfilePhotoAuthorizedFileRequest(43, captured.authorization, 90,
            SettingsCurrentProfilePhotoMediaUse.ANIMATION_PREVIEW, 200, 200)
        assertEquals(8, f.source.resolveAnimation(request).value?.file?.id)
        val fallback = captured.copy(animation = null)
        assertEquals(9, selectCurrentProfilePhotoAnimation(fallback, request).value?.file?.id)
        assertEquals(SettingsError.INVALID_VALUE, selectCurrentProfilePhotoAnimation(
            captured.copy(animation = captured.animation?.copy(mainFrameTimestampSeconds = Double.NaN)), request).error)
    }

    @Test fun newerOpeningRevokesOnlyThePriorCurrentTicketAndLateCallbacksCannotReplaceIt() {
        val f = Fixture(); f.open(); f.me(photoId = 90); f.full(photo(90))
        val first = assertNotNull(f.result().photo)
        var pendingActive = true
        f.open { pendingActive }; f.me(photoId = 91)
        val late = f.last; pendingActive = false
        f.open(); f.me(photoId = 92); f.full(photo(92).apply {
            sizes = arrayOf(TdApi.PhotoSize().apply { width = 160; height = 160; photo = nativeFile(7, "new", "private") })
        })
        val second = assertNotNull(f.result().photo)
        late.reply(TdApi.UserFullInfo().apply { photo = photo(91) })
        val stale = SettingsCurrentProfilePhotoAuthorizedFileRequest(43, first.authorization, first.photoId,
            SettingsCurrentProfilePhotoMediaUse.STATIC_PREVIEW, 100, 100)
        assertNotNull(f.source.resolve(stale).error)
        assertNotEquals(first.authorization, second.authorization); assertEquals(92L, second.photoId)
        assertEquals("new", f.source.resolve(stale.copy(authorization = second.authorization, photoId = 92)).value?.file?.uniqueId)
    }

    @Test fun stickerOnlyIsExplicitMetadataAndNeverBecomesAnInventedAnimation() {
        val f = Fixture(); f.open(); f.me(photoId = 90)
        f.full(photo(90).apply { sticker = TdApi.ChatPhotoSticker() })
        val current = assertNotNull(f.result().photo)
        assertTrue(current.hasSticker); assertFalse(current.hasAnimation); assertNull(current.preferredAnimation())
    }

    @Test fun realSelfUpdatesOnlyInvalidateConflictingStablePhotoMetadata() {
        fun animated(timestamp: Double, unique: String, path: String) = photo(90).apply {
            animation = TdApi.AnimatedChatPhoto().apply {
                length = 640; file = nativeFile(8, unique, path); mainFrameTimestamp = timestamp
            }
        }
        val f = Fixture(); f.open(); f.me(photoId = 90); f.full(animated(1.0, "native-8", "old"))
        val current = assertNotNull(f.result().photo)
        val request = SettingsCurrentProfilePhotoAuthorizedFileRequest(43, current.authorization, 90,
            SettingsCurrentProfilePhotoMediaUse.ANIMATION_PREVIEW, 160, 160)
        f.source.accept(TdApi.UpdateUser(TdApi.User().apply { id = 44; profilePhoto = TdApi.ProfilePhoto().apply { id = 91 } }))
        f.source.accept(TdApi.UpdateUser(TdApi.User().apply { id = 43; firstName = "unrelated"; profilePhoto = TdApi.ProfilePhoto().apply { id = 90 } }))
        f.source.accept(TdApi.UpdateUserFullInfo(43, TdApi.UserFullInfo().apply { photo = animated(1.0, "native-8", "moved") }))
        assertEquals(0, f.invalidations); assertNotNull(f.source.resolveAnimation(request).value)
        f.source.accept(TdApi.UpdateUserFullInfo(43, TdApi.UserFullInfo().apply { photo = animated(1.5, "native-8", "moved") }))
        assertEquals(1, f.invalidations); assertNotNull(f.source.resolveAnimation(request).error)
    }

    @Test fun updateCannotContradictFactsLearnedFromAnAuthorizedGetFile() {
        fun animated(unique: String, size: Long) = photo(90).apply {
            animation = TdApi.AnimatedChatPhoto().apply {
                length = 640; file = nativeFile(8, unique, "").apply { this.size = size }; mainFrameTimestamp = 1.0
            }
        }
        val f = Fixture(); f.open(); f.me(photoId = 90); f.full(animated("", 0))
        val current = assertNotNull(f.result().photo)
        val request = SettingsCurrentProfilePhotoAuthorizedFileRequest(43, current.authorization, 90,
            SettingsCurrentProfilePhotoMediaUse.ANIMATION_PREVIEW, 160, 160)
        val observed = assertNotNull(f.source.resolveAnimation(request).value).file.copy(uniqueId = "known", size = 100)
        assertEquals(Unit, f.source.observeFile(request, observed).value)
        f.source.accept(TdApi.UpdateUserFullInfo(43, TdApi.UserFullInfo().apply { photo = animated("", 0) }))
        assertEquals(0, f.invalidations)
        f.source.accept(TdApi.UpdateUserFullInfo(43, TdApi.UserFullInfo().apply { photo = animated("other", 100) }))
        assertEquals(1, f.invalidations); assertNotNull(f.source.resolveAnimation(request).error)
    }

    @Test fun successiveUpdatesRetainKnownUniqueAndSizeAcrossUnknownSnapshots() {
        fun animated(unique: String, size: Long) = photo(90).apply { animation = TdApi.AnimatedChatPhoto().apply {
            length = 640; file = nativeFile(8, unique, "").apply { this.size = size }; mainFrameTimestamp = 1.0
        } }
        for ((unique, size) in listOf("changed" to 100L, "known" to 200L)) {
            val f = Fixture(); f.open(); f.me(photoId = 90); f.full(animated("", 0))
            f.source.accept(TdApi.UpdateUserFullInfo(43, TdApi.UserFullInfo().apply { photo = animated("known", 100) }))
            f.source.accept(TdApi.UpdateUserFullInfo(43, TdApi.UserFullInfo().apply { photo = animated("", 0) }))
            assertEquals(0, f.invalidations)
            f.source.accept(TdApi.UpdateUserFullInfo(43, TdApi.UserFullInfo().apply { photo = animated(unique, size) }))
            assertEquals(1, f.invalidations)
        }
    }

    @Test fun successiveUpdatesDuringReadCannotForgetEarlierKnownFacts() {
        fun animated(unique: String, size: Long) = photo(90).apply { animation = TdApi.AnimatedChatPhoto().apply {
            length = 640; file = nativeFile(8, unique, "").apply { this.size = size }; mainFrameTimestamp = 1.0
        } }
        val f = Fixture(); f.open(); f.me(photoId = 90); val full = f.last
        f.source.accept(TdApi.UpdateUserFullInfo(43, TdApi.UserFullInfo().apply { photo = animated("known", 100) }))
        f.source.accept(TdApi.UpdateUserFullInfo(43, TdApi.UserFullInfo().apply { photo = animated("", 0) }))
        assertEquals(0, f.invalidations)
        f.source.accept(TdApi.UpdateUserFullInfo(43, TdApi.UserFullInfo().apply { photo = animated("other", 100) }))
        assertEquals(1, f.invalidations)
        full.reply(TdApi.UserFullInfo().apply { photo = animated("other", 100) })
        assertEquals(SettingsError.NOT_READY, f.results.single().error)
    }

    @Test fun changedSelfDuringReadCannotCombineOldAndNewSources() {
        val f = Fixture(); f.open(); f.me(photoId = 90)
        val full = f.last
        f.source.accept(TdApi.UpdateUser(TdApi.User().apply { id = 43; profilePhoto = TdApi.ProfilePhoto().apply { id = 91 } }))
        assertEquals(1, f.invalidations)
        full.reply(TdApi.UserFullInfo().apply { photo = photo(90) })
        assertEquals(SettingsError.NOT_READY, f.results.single().error)
    }

    @Test fun updateBeforeGetMeReplyIsComparedWithoutRejectingMatchingCacheUpdates() {
        fun update(id: Long) = TdApi.UpdateUser(TdApi.User().apply {
            this.id = 43; profilePhoto = TdApi.ProfilePhoto().apply { this.id = id }
        })
        val matching = Fixture(); matching.open(); matching.source.accept(update(90)); matching.me(photoId = 90)
        matching.full(photo(90)); assertEquals(SettingsCurrentProfilePhotoStatus.READY, matching.result().status)
        val changed = Fixture(); changed.open(); changed.source.accept(update(91)); changed.me(photoId = 90)
        assertEquals(SettingsError.CONFLICT, changed.results.single().error)
        assertEquals(1, changed.calls.size)
    }

    @Test fun fullInfoUpdateDuringReadMustAgreeWithFinalStableMetadata() {
        fun animated(timestamp: Double) = photo(90).apply { animation = TdApi.AnimatedChatPhoto().apply {
            length = 640; file = nativeFile(8, "native-8", ""); mainFrameTimestamp = timestamp
        } }
        val consistent = Fixture(); consistent.open(); consistent.me(photoId = 90)
        consistent.source.accept(TdApi.UpdateUserFullInfo(43, TdApi.UserFullInfo().apply { photo = animated(1.0) }))
        consistent.full(animated(1.0)); assertEquals(SettingsCurrentProfilePhotoStatus.READY, consistent.result().status)
        val changed = Fixture(); changed.open(); changed.me(photoId = 90)
        changed.source.accept(TdApi.UpdateUserFullInfo(43, TdApi.UserFullInfo().apply { photo = animated(2.0) }))
        changed.full(animated(1.0)); assertEquals(SettingsError.CONFLICT, changed.results.single().error)
    }

    @Test fun emptyCurrentCanBeInvalidatedByARealNewSelfPhoto() {
        val f = Fixture(); f.open(); f.me(photoId = null); f.full(null)
        assertEquals(SettingsCurrentProfilePhotoStatus.EMPTY, f.result().status)
        f.source.accept(TdApi.UpdateUser(TdApi.User().apply { id = 43; profilePhoto = TdApi.ProfilePhoto().apply { id = 90 } }))
        assertEquals(1, f.invalidations)
    }

    @Test fun settingsSessionForwardsSelfUpdatesToTheIndependentCurrentAuthority() {
        val calls = mutableListOf<Call>(); val results = mutableListOf<SettingsResult<SettingsCurrentProfilePhotoRead>>()
        val session = OfficialTdSettingsSession { command, reply, failed -> calls += Call(command, reply, failed) }
        var invalidations = 0
        session.profileExtras.currentProfilePhoto(43, { true }, { invalidations++ }, results::add)
        calls.last().reply(TdApi.User().apply { id = 43; profilePhoto = TdApi.ProfilePhoto().apply { id = 90 } })
        calls.last().reply(TdApi.UserFullInfo().apply { photo = photo(90) })
        assertEquals(SettingsCurrentProfilePhotoStatus.READY, results.single().value?.status)
        session.accept(TdApi.UpdateUser(TdApi.User().apply { id = 43; profilePhoto = TdApi.ProfilePhoto().apply { id = 91 } }))
        assertEquals(1, invalidations); session.retire()
    }

    private data class Call(val command: TdApi.Function<*>, val reply: (TdApi.Object) -> Unit, val failed: () -> Unit)
    private class Fixture {
        val calls = mutableListOf<Call>(); val results = mutableListOf<SettingsResult<SettingsCurrentProfilePhotoRead>>()
        val source = OfficialTdCurrentProfilePhoto { command, reply, failed -> calls += Call(command, reply, failed) }
        val last get() = calls.last()
        var invalidations = 0
        fun open(active: () -> Boolean = { true }) { source.read(43, active, { invalidations++ }, results::add) }
        fun me(userId: Long = 43, photoId: Long? = 90, personal: Boolean = false) {
            assertIs<TdApi.GetMe>(last.command)
            last.reply(TdApi.User().apply { id = userId; profilePhoto = photoId?.let { TdApi.ProfilePhoto().apply {
                id = it; isPersonal = personal
            } } })
        }
        fun full(main: TdApi.ChatPhoto?, personal: TdApi.ChatPhoto? = null, public: TdApi.ChatPhoto? = null) {
            assertIs<TdApi.GetUserFullInfo>(last.command)
            last.reply(TdApi.UserFullInfo().apply { photo = main; personalPhoto = personal; publicPhoto = public })
        }
        fun result() = assertNotNull(results.last().value)
    }
    private companion object {
        fun photo(id: Long) = TdApi.ChatPhoto().apply { this.id = id; sizes = emptyArray() }
        fun nativeFile(id: Int, unique: String, path: String) = TdApi.File().apply {
            this.id = id; remote = TdApi.RemoteFile().apply { uniqueId = unique }
            local = TdApi.LocalFile().apply { this.path = path; isDownloadingCompleted = path.isNotBlank() }
        }
    }
}
