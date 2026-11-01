package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.*

/** Fake metadata/account requests and isolated temporary staged files; no client or audio output. */
class OfficialTdSavedNotificationSoundsTest {
    @Test fun catalogPreservesSixFieldsSignedServerIdsAndIndependentFileIdentitiesWithoutDownloads() {
        val f = Fixture(); val results = mutableListOf<SettingsResult<SavedNotificationSoundsData>>()
        val sounds = listOf(sound(Long.MIN_VALUE, 41), sound(-2, 42), sound(Long.MAX_VALUE, 43))
        f.api.load(f::active, results::add); f.loadSteps(sounds)
        val data = assertNotNull(results.single().value)
        assertEquals(1L, data.accountUserId); assertTrue(data.limits.known)
        assertEquals(sounds.map(::saved), data.sounds)
        assertEquals(listOf("GetMe", "GetSavedNotificationSounds", "GetOption", "GetOption", "GetOption"), f.names())
        assertFalse(data.toString().contains("synthetic sound")); f.assertMetadataOnly()
    }

    @Test fun unknownOrFailedDynamicLimitsPreserveReadableMetadataButNeverGuessUploadPermission() {
        val invalid = listOf<TdApi.Object>(TdApi.OptionValueEmpty(), TdApi.OptionValueBoolean(true),
            TdApi.OptionValueInteger(0), TdApi.OptionValueInteger(-1), TdApi.Error(500, "private native error"))
        for (bad in invalid) for (slot in 0..2) {
            val f = Fixture(); val results = mutableListOf<SettingsResult<SavedNotificationSoundsData>>()
            val options = limits().toMutableList().apply { this[slot] = bad }
            f.api.load(f::active, results::add); f.loadSteps(listOf(sound()), options)
            val actual = assertNotNull(results.single().value)
            assertEquals(1, actual.sounds.size); assertFalse(actual.limits.known); assertNotNull(actual.limitsError)
            assertFalse(results.single().toString().contains("private native error"))
        }
    }

    @Test fun oversizedDuplicateOrMalformedCatalogsFailInsteadOfSilentlyTruncating() {
        val malformed = listOf(
            Array(4097) { sound(it.toLong() + 2, it + 1) }, arrayOf(sound(), sound()),
            arrayOf(sound(0)), arrayOf(sound(-1)), arrayOf(sound().apply { sound.id = 0 }),
            arrayOf(sound().apply { sound.remote.uniqueId = "" }))
        for (native in malformed) {
            val f = Fixture(); val results = mutableListOf<SettingsResult<SavedNotificationSoundsData>>()
            f.api.load(f::active, results::add); f.last.reply(user()); f.last.reply(TdApi.NotificationSounds(native))
            assertNull(results.single().value); assertNotNull(results.single().error); assertEquals(2, f.requests.size)
        }
    }

    @Test fun readChecksFreshAccountActualServerIdAndRealNativeFileWithoutUsingTheFileIdAsSoundId() {
        val f = Fixture(); val results = mutableListOf<SettingsResult<SavedNotificationSound>>()
        f.api.read(1, -2, f::active, results::add); f.last.reply(user())
        assertEquals(-2L, assertIs<TdApi.GetSavedNotificationSound>(f.last.command).notificationSoundId)
        f.last.reply(sound(-2, 84)); assertEquals(84, results.single().value?.file?.id)
        for (wrong in 0..3) {
            val bad = Fixture(); val failed = mutableListOf<SettingsResult<SavedNotificationSound>>()
            bad.api.read(1, -2, bad::active, failed::add)
            if (wrong == 0) bad.last.reply(user(2)) else {
                bad.last.reply(user())
                bad.last.reply(when (wrong) {
                    1 -> sound(Long.MIN_VALUE)
                    2 -> sound().apply { sound.id = 0 }
                    else -> TdApi.Error(404, "not available")
                })
            }
            assertNull(failed.single().value); assertNotNull(failed.single().error)
            assertEquals(if (wrong == 0) 1 else 2, bad.requests.size)
        }
        f.assertMetadataOnly()
    }

    @Test fun firstGetMeBindsThisSessionAndALaterUserIdCannotReplaceIt() {
        val f = Fixture(); val initial = mutableListOf<SettingsResult<SavedNotificationSoundsData>>()
        f.api.load(f::active, initial::add); f.loadSteps(emptyList())
        assertEquals(1L, initial.single().value?.accountUserId)

        val read = mutableListOf<SettingsResult<SavedNotificationSound>>()
        f.api.read(2, -2, f::active, read::add)
        assertIs<TdApi.GetMe>(f.last.command)
        f.last.reply(user(2))
        assertEquals(SettingsError.CONFLICT, read.single().error)
        assertEquals(0, f.requests.count { it.command is TdApi.GetSavedNotificationSound })
    }

    @Test fun onlyTheBoundAccountBecomingNonRegularInvalidatesTheCatalogObserver() {
        val f = Fixture(); val initial = mutableListOf<SettingsResult<SavedNotificationSoundsData>>()
        f.api.load(f::active, initial::add); f.loadSteps(emptyList())
        var invalidations = 0
        f.api.observe { invalidations++ }

        val read = mutableListOf<SettingsResult<SavedNotificationSound>>()
        f.api.read(1, -2, f::active, read::add); f.last.reply(user())
        f.api.accept(TdApi.UpdateUser(user(2).apply { type = TdApi.UserTypeDeleted() }))
        f.api.accept(TdApi.UpdateUser(user(1).apply { firstName = "Updated regular profile" }))
        assertEquals(0, invalidations)
        f.last.reply(sound())
        assertNull(read.single().error, "Other-user or regular profile updates must not cancel an active sound read.")

        f.api.accept(TdApi.UpdateUser(user(1).apply { type = TdApi.UserTypeDeleted() }))
        assertEquals(1, invalidations)
    }

    @Test fun addRechecksLimitsCountActualRegularFileSizeAndDurationBeforeAnInputFileLocalWrite() = upload { staged ->
        for (bad in 0..6) {
            val f = Fixture(); val results = mutableListOf<SavedNotificationSoundMutationResult>()
            val descriptor = when (bad) {
                0 -> staged.copy(sizeBytes = staged.sizeBytes + 1)
                1 -> staged.copy(durationMillis = 2001)
                2 -> staged.copy(durationMillis = 0)
                3 -> staged.copy(path = Path.of(staged.path).parent.toString())
                4 -> staged.copy(path = "relative.mp3")
                else -> staged
            }
            f.api.add(data(), descriptor, f::active, results::add)
            val catalog = if (bad == 5) listOf(sound()) else emptyList()
            val options = when (bad) { 5 -> limits(count = 1); 6 -> limits(bytes = 15); else -> limits() }
            f.loadSteps(catalog, options)
            assertEquals(SettingsError.INVALID_VALUE, results.single().error)
            assertTrue(f.requests.none { it.command is TdApi.AddSavedNotificationSound })
        }
        val unknown = Fixture(); val results = mutableListOf<SavedNotificationSoundMutationResult>()
        unknown.api.add(data(), staged, unknown::active, results::add)
        unknown.loadSteps(emptyList(), listOf(TdApi.OptionValueEmpty(), TdApi.OptionValueInteger(2), TdApi.OptionValueInteger(10)))
        assertNotNull(results.single().error); assertTrue(unknown.requests.none { it.command is TdApi.AddSavedNotificationSound })
    }

    @Test fun addUsesTheCallerStagedFileAndOnlyClaimsAcceptanceAfterTheRealSoundResponseAndReadback() = upload { staged ->
        val f = Fixture(); val results = mutableListOf<SavedNotificationSoundMutationResult>(); val created = sound(Long.MIN_VALUE, 47)
        f.api.add(data(), staged, f::active, results::add); f.loadSteps(emptyList())
        val request = assertIs<TdApi.AddSavedNotificationSound>(f.last.command)
        assertEquals(staged.path, assertIs<TdApi.InputFileLocal>(request.sound).path)
        assertTrue(results.isEmpty()); f.last.reply(created)
        assertTrue(results.isEmpty()); f.loadSteps(listOf(created))
        assertTrue(results.single().locallyAccepted); assertEquals(saved(created), results.single().sound)
        assertEquals(saved(created), results.single().actual?.sounds?.single()); assertNull(results.single().error)
        assertTrue(Files.exists(Path.of(staged.path)), "The helper does not own or delete the staged file.")
        f.assertMetadataOnly()
    }

    @Test fun preparedSha256IsVerifiedAtAddAndRejectsSameSizeStagedFileMutation() = upload { staged ->
        val path = Path.of(staged.path)
        val original = Files.readAllBytes(path)
        val prepared = staged.copy(sha256 = sha256(original))

        val valid = Fixture(); val accepted = mutableListOf<SavedNotificationSoundMutationResult>()
        valid.api.add(data(), prepared, valid::active, accepted::add); valid.loadSteps(emptyList())
        assertEquals(staged.path, assertIs<TdApi.InputFileLocal>(
            assertIs<TdApi.AddSavedNotificationSound>(valid.last.command).sound).path)
        assertTrue(accepted.isEmpty())

        val changed = original.copyOf().also { it[0] = (it[0].toInt() xor 0x40).toByte() }
        Files.write(path, changed)
        assertEquals(original.size.toLong(), Files.size(path), "The staged file keeps the prepared size.")
        val invalid = Fixture(); val rejected = mutableListOf<SavedNotificationSoundMutationResult>()
        invalid.api.add(data(), prepared, invalid::active, rejected::add); invalid.loadSteps(emptyList())
        assertEquals(SettingsError.INVALID_VALUE, rejected.single().error)
        assertTrue(invalid.requests.none { it.command is TdApi.AddSavedNotificationSound })
    }

    @Test fun removeComparesImmutableSoundAndFileIdentityButIgnoresDownloadProgressChanges() {
        val original = sound()
        val updated = sound().apply {
            sound.local = TdApi.LocalFile().apply { isDownloadingActive = true; downloadedSize = 3; path = "private cache" }
            sound.remote.isUploadingActive = true; sound.remote.uploadedSize = 5
        }
        val f = Fixture(); val results = mutableListOf<SavedNotificationSoundMutationResult>()
        f.api.remove(1, saved(original), f::active, results::add)
        f.last.reply(user()); f.last.reply(updated)
        assertEquals(-2L, assertIs<TdApi.RemoveSavedNotificationSound>(f.last.command).notificationSoundId)
        f.last.reply(TdApi.Ok()); f.loadSteps(emptyList())
        assertTrue(results.single().locallyAccepted); assertNull(results.single().error)
        for (change in 0..2) {
            val bad = Fixture(); val rejected = mutableListOf<SavedNotificationSoundMutationResult>()
            bad.api.remove(1, saved(original), bad::active, rejected::add); bad.last.reply(user())
            bad.last.reply(sound().apply { when (change) { 0 -> sound.id = 99; 1 -> sound.remote.uniqueId = "different"; else -> title = "different" } })
            assertEquals(SettingsError.CONFLICT, rejected.single().error)
            assertTrue(bad.requests.none { it.command is TdApi.RemoveSavedNotificationSound })
        }
    }

    @Test fun acceptedAddOrRemoveKeepsLocalAcceptanceWhenReadbackFailsOrTheOwnerRetires() = upload { staged ->
        for (add in listOf(false, true)) for (retire in listOf(false, true)) {
            val f = Fixture(); val results = mutableListOf<SavedNotificationSoundMutationResult>()
            if (add) { f.api.add(data(), staged, f::active, results::add); f.loadSteps(emptyList()) }
            else { f.api.remove(1, saved(sound()), f::active, results::add); f.last.reply(user()); f.last.reply(sound()) }
            val mutation = f.last; val count = f.requests.size
            if (retire) f.api.retire()
            mutation.reply(if (add) sound() else TdApi.Ok()); mutation.reply(if (add) sound() else TdApi.Ok()); mutation.failed()
            if (retire) assertEquals(count, f.requests.size) else f.last.reply(TdApi.Error(500, "private"))
            assertTrue(results.single().locallyAccepted); assertNull(results.single().actual)
            assertEquals(if (retire) SettingsError.NOT_READY else SettingsError.REQUEST_FAILED, results.single().error)
        }
    }

    @Test fun mutationQueueWaitsThroughReadbackAndCancelsQueuedOldOwnersWithoutAnExtraWrite() {
        val f = Fixture(); val first = mutableListOf<SavedNotificationSoundMutationResult>(); val second = mutableListOf<SavedNotificationSoundMutationResult>()
        var secondActive = true
        f.api.remove(1, saved(sound()), f::active, first::add)
        f.api.remove(1, saved(sound(Long.MIN_VALUE, 77)), { secondActive }, second::add)
        assertEquals(1, f.requests.size)
        f.last.reply(user()); f.last.reply(sound()); f.last.reply(TdApi.Ok())
        assertTrue(first.isEmpty()); assertTrue(second.isEmpty()); assertIs<TdApi.GetMe>(f.last.command)
        secondActive = false; val count = f.requests.size; f.last.reply(TdApi.Error(500, "synthetic"))
        assertTrue(first.single().locallyAccepted); assertEquals(SettingsError.NOT_READY, second.single().error)
        assertEquals(count, f.requests.size)
    }

    @Test fun catalogUpdatesDeduplicateTheReadItselfButRejectGenuinelyChangedListsDuringReadOrOptions() {
        val f = Fixture(); var invalidations = 0; f.api.observe { invalidations++ }
        val results = mutableListOf<SettingsResult<SavedNotificationSoundsData>>()
        f.api.load(f::active, results::add); f.last.reply(user())
        f.api.accept(TdApi.UpdateSavedNotificationSounds(longArrayOf(-2)))
        f.last.reply(TdApi.NotificationSounds(arrayOf(sound())))
        limits().forEach { f.last.reply(it) }
        assertEquals(1, results.single().value?.sounds?.size)
        f.api.accept(TdApi.UpdateSavedNotificationSounds(longArrayOf(-2))); assertEquals(0, invalidations)
        val stale = mutableListOf<SettingsResult<SavedNotificationSoundsData>>()
        f.api.load(f::active, stale::add); f.last.reply(user()); f.last.reply(TdApi.NotificationSounds(arrayOf(sound())))
        f.api.accept(TdApi.UpdateSavedNotificationSounds(longArrayOf(Long.MIN_VALUE)))
        limits().forEach { f.last.reply(it) }
        assertEquals(SettingsError.CONFLICT, stale.single().error); assertNull(stale.single().value)
        assertEquals(1, invalidations); f.assertMetadataOnly()
    }

    @Test fun nativeErrorsDiscardsThrowsDuplicatesAndInactivePagesAlwaysReachOneTerminalResult() {
        for (stage in 0..4) for (throwing in listOf(false, true)) {
            val requests = mutableListOf<Pending>()
            val api = OfficialTdSavedNotificationSounds { command, reply, failed ->
                val index = requests.size; requests += Pending(command, reply, failed)
                if (index == stage) { if (throwing) error("synthetic") else failed() }
            }
            val results = mutableListOf<SettingsResult<SavedNotificationSoundsData>>()
            api.load({ true }, results::add)
            if (stage >= 1) requests.last().reply(user())
            if (stage >= 2) requests.last().reply(TdApi.NotificationSounds(arrayOf(sound())))
            if (stage >= 3) requests.last().reply(TdApi.OptionValueInteger(1024))
            if (stage >= 4) requests.last().reply(TdApi.OptionValueInteger(2))
            if (stage in 2..3) repeat(4 - stage) { requests.last().reply(TdApi.OptionValueInteger(10)) }
            requests[stage].failed(); requests[stage].reply(TdApi.Ok())
            val result = results.single()
            if (stage <= 1) assertNotNull(result.error) else assertNotNull(result.value?.limitsError)
        }
        val f = Fixture(); val results = mutableListOf<SettingsResult<SavedNotificationSound>>()
        f.api.read(1, -2, f::active, results::add)
        val me = f.last; me.reply(user()); me.reply(user()); me.failed()
        assertEquals(2, f.requests.size)
        f.enabled = false; f.last.reply(sound()); assertEquals(SettingsError.NOT_READY, results.single().error)
    }

    @Test fun mutationPreflightsRejectAnotherAccountAndNativeRevisionChangesNeverAuthorizeStaleRemoval() = upload { staged ->
        val f = Fixture(); val results = mutableListOf<SavedNotificationSoundMutationResult>()
        f.api.add(data().copy(accountUserId = 2), staged, f::active, results::add); f.last.reply(user())
        assertEquals(SettingsError.CONFLICT, results.single().error); assertEquals(1, f.requests.size)
        val remove = Fixture(); val rejected = mutableListOf<SavedNotificationSoundMutationResult>()
        remove.api.remove(1, saved(sound()), remove::active, rejected::add); remove.last.reply(user())
        remove.api.accept(TdApi.UpdateSavedNotificationSounds(longArrayOf()))
        remove.last.reply(sound())
        assertEquals(SettingsError.CONFLICT, rejected.single().error)
        assertTrue(remove.requests.none { it.command is TdApi.RemoveSavedNotificationSound })
    }

    @Test fun boundedQueueRetirementSettlesEveryQueuedOwnerAndNeverStartsAnExtraNativeRequest() {
        val f = Fixture(); val results = mutableListOf<SavedNotificationSoundMutationResult>()
        f.api.remove(1, saved(sound()), f::active, results::add)
        repeat(33) { f.api.remove(1, saved(sound()), f::active, results::add) }
        assertEquals(SettingsError.RATE_LIMITED, results.single().error)
        assertEquals(1, f.requests.size)
        f.api.retire(); f.api.retire()
        assertEquals(33, results.size)
        f.last.reply(user()); f.last.reply(user()); f.last.failed()
        assertEquals(34, results.size); assertEquals(1, f.requests.size)
        assertTrue(results.all { !it.locallyAccepted && it.error in setOf(SettingsError.NOT_READY, SettingsError.RATE_LIMITED) })
    }

    @Test fun actualLoadStampAuthorizesOnlyTheCurrentAccountCatalogAndARealListChangeRequiresANewLoad() {
        val f = Fixture(); val loaded = mutableListOf<SettingsResult<SavedNotificationSoundsData>>()
        assertFalse(f.api.isCurrent(data()))
        f.api.load(f::active, loaded::add); f.loadSteps(listOf(sound()))
        val first = assertNotNull(loaded.single().value)
        assertTrue(first.nativeRevision > 0); assertTrue(f.api.isCurrent(first))
        assertFalse(f.api.isCurrent(first.copy(nativeRevision = 0)))
        assertFalse(f.api.isCurrent(first.copy(nativeRevision = first.nativeRevision + 1)))
        assertFalse(f.api.isCurrent(first.copy(accountUserId = 2)))
        assertFalse(f.api.isCurrent(first.copy(sounds = emptyList())))
        val before = f.requests.size
        f.api.accept(TdApi.UpdateSavedNotificationSounds(longArrayOf(Long.MIN_VALUE)))
        assertFalse(f.api.isCurrent(first)); assertEquals(before, f.requests.size)
        val refreshed = mutableListOf<SettingsResult<SavedNotificationSoundsData>>()
        f.api.load(f::active, refreshed::add); f.loadSteps(listOf(sound(Long.MIN_VALUE, 77)))
        val actual = assertNotNull(refreshed.single().value)
        assertTrue(actual.nativeRevision > first.nativeRevision)
        assertTrue(f.api.isCurrent(actual)); assertFalse(f.api.isCurrent(first))
        f.assertMetadataOnly()
    }

    @Test fun catalogStampSurvivesOrdinaryProfileUpdatesButNotBoundAccountInvalidationOrRetirement() {
        val f = Fixture(); val loaded = mutableListOf<SettingsResult<SavedNotificationSoundsData>>()
        f.api.load(f::active, loaded::add); f.loadSteps(listOf(sound()))
        val actual = assertNotNull(loaded.single().value); val count = f.requests.size
        f.api.accept(TdApi.UpdateUser(user().apply { firstName = "updated profile" }))
        f.api.accept(TdApi.UpdateUser(user(2).apply { type = TdApi.UserTypeDeleted() }))
        assertTrue(f.api.isCurrent(actual), "Regular profile metadata and other users do not invalidate the account catalog.")
        assertEquals(count, f.requests.size)
        f.api.accept(TdApi.UpdateUser(user().apply { type = TdApi.UserTypeDeleted() }))
        assertFalse(f.api.isCurrent(actual))
        f.api.retire(); f.api.retire()
        assertFalse(f.api.isCurrent(actual)); assertFalse(f.api.isCurrent(actual.copy(accountUserId = 0)))
        assertEquals(count, f.requests.size)
    }

    private class Pending(val command: TdApi.Function<*>, val reply: (TdApi.Object) -> Unit, val failed: () -> Unit)
    private class Fixture {
        var enabled = true
        val requests = mutableListOf<Pending>()
        val api = OfficialTdSavedNotificationSounds { command, reply, failed -> requests += Pending(command, reply, failed) }
        val last get() = requests.last()
        fun active() = enabled
        fun loadSteps(sounds: List<TdApi.NotificationSound>, options: List<TdApi.Object> = limits()) {
            assertIs<TdApi.GetMe>(last.command); last.reply(user())
            assertIs<TdApi.GetSavedNotificationSounds>(last.command); last.reply(TdApi.NotificationSounds(sounds.toTypedArray()))
            for ((index, value) in options.withIndex()) {
                assertEquals(listOf("notification_sound_size_max", "notification_sound_duration_max", "notification_sound_count_max")[index],
                    assertIs<TdApi.GetOption>(last.command).name)
                last.reply(value)
            }
        }
        fun names() = requests.map { it.command.javaClass.simpleName }
        fun assertMetadataOnly() { assertTrue(requests.all { it.command is TdApi.GetMe || it.command is TdApi.GetOption ||
            it.command is TdApi.GetSavedNotificationSounds || it.command is TdApi.GetSavedNotificationSound ||
            it.command is TdApi.AddSavedNotificationSound || it.command is TdApi.RemoveSavedNotificationSound }) }
    }
    private companion object {
        fun user(id: Long = 1) = TdApi.User().apply { this.id = id; type = TdApi.UserTypeRegular() }
        fun sound(id: Long = -2, fileId: Int = 42) = TdApi.NotificationSound(id, 1, 123, "synthetic sound", "opaque data",
            TdApi.File().apply { this.id = fileId; size = 16; expectedSize = 16
                local = TdApi.LocalFile(); remote = TdApi.RemoteFile().apply { uniqueId = "unique-$fileId" } })
        fun saved(value: TdApi.NotificationSound) = SavedNotificationSound(value.id, value.duration, value.date, value.title, value.data, mapMediaFile(value.sound))
        fun data() = SavedNotificationSoundsData(1, emptyList(), NotificationSoundLimits(1024, 2, 10))
        fun limits(bytes: Long = 1024, count: Long = 10): List<TdApi.Object> =
            listOf(TdApi.OptionValueInteger(bytes), TdApi.OptionValueInteger(2), TdApi.OptionValueInteger(count))
        fun upload(body: (PreparedNotificationSoundUpload) -> Unit) {
            val directory = Files.createTempDirectory("notification-sound-metadata-")
            val path = directory.resolve("staged.mp3")
            try { Files.write(path, ByteArray(16)); body(PreparedNotificationSoundUpload(path.toAbsolutePath().toString(), 16, 500)) }
            finally { Files.deleteIfExists(path); Files.deleteIfExists(directory) }
        }
        fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            (it.toInt() and 255).toString(16).padStart(2, '0')
        }
    }
}
