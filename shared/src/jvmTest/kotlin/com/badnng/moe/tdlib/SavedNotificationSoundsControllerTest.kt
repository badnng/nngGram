package com.badnng.moe.tdlib

import com.badnng.moe.chat.MediaFile
import com.badnng.moe.chat.ownedAttachmentLease
import com.badnng.moe.notifications.NotificationSoundLoad
import com.badnng.moe.notifications.NotificationSoundPreview
import com.badnng.moe.notifications.NotificationSoundVolumeStore
import com.badnng.moe.settings.*
import kotlin.test.*

class SavedNotificationSoundsControllerTest {
    @Test fun nativeCallbacksPublishOnlyThroughTheActorAndOldPagesCannotPopulateTheNewOwner() {
        val f = Fixture(); f.open()
        val first = f.snapshot.token; val old = f.session.loads.single()
        old.reply(SettingsResult(data()))
        assertNull(f.snapshot.data); assertTrue(f.snapshot.loading)
        f.act { close(first); open { true } }
        assertFalse(old.current()); assertNotEquals(first, f.snapshot.token)
        old.reply(SettingsResult(data(sound(-55)))); f.drain()
        assertNull(f.snapshot.data)
        f.loaded(data()); assertEquals(listOf(sound()), f.snapshot.data?.sounds)
    }

    @Test fun updatesInvalidateTheGenerationSourceEvenWhenClosedAndNeverSilentlyReloadOrClearErrors() {
        val f = Fixture(); f.session.observer!!.invoke()
        assertEquals(0, f.invalidations); f.drain(); assertEquals(1, f.invalidations)
        f.open(); f.loaded(data()); val revision = f.snapshot.revision
        f.upload(valid = false)
        assertEquals(SettingsError.INVALID_VALUE, f.snapshot.error)
        f.session.observer!!.invoke(); f.drain()
        assertEquals(2, f.invalidations); assertTrue(f.snapshot.refreshRequired)
        assertTrue(f.snapshot.revision > revision); assertEquals(SettingsError.INVALID_VALUE, f.snapshot.error)
        assertEquals(1, f.session.loads.size)
        f.act { close(f.snapshot.token) }
        assertNotNull(f.session.observer)
        f.session.observer!!.invoke(); f.drain(); assertEquals(3, f.invalidations)
        f.act { deactivate() }; assertNull(f.session.observer); assertEquals(1, f.preview.closed)
    }

    @Test fun updatesDuringAReadKeepTheResultExplicitlyStaleAndForeignAccountsAreRejected() {
        val f = Fixture(); f.open()
        f.session.observer!!.invoke(); f.drain(); f.loaded(data())
        assertTrue(f.snapshot.refreshRequired)
        f.act { refresh(f.snapshot.token) }; f.loaded(data())
        assertFalse(f.snapshot.refreshRequired)
        f.act { refresh(f.snapshot.token) }; f.loaded(data().copy(accountUserId = 999))
        assertEquals(100L, f.snapshot.data?.accountUserId)
        assertEquals(SettingsError.CONFLICT, f.snapshot.error); assertTrue(f.snapshot.refreshRequired)
    }

    @Test fun zeroAndDefaultNeedNoSoundLookupWhileEveryOtherSignedIdentityRequiresFreshNativeMetadata() {
        for (id in listOf(0L, -1L, -55L, Long.MIN_VALUE, Long.MAX_VALUE)) {
            val f = Fixture(); f.open(); f.loaded(data(sound(id.takeUnless { it == 0L || it == -1L } ?: 12)))
            val answers = mutableListOf<SettingsResult<Long>>()
            f.act { choose(f.snapshot.token, f.snapshot.revision, id, answers::add) }
            if (id == 0L || id == -1L) {
                assertEquals(id, answers.single().value); assertTrue(f.session.reads.isEmpty())
            } else {
                assertTrue(answers.isEmpty()); assertTrue(f.snapshot.choosing)
                val read = f.session.reads.single()
                assertEquals(100L, read.account); assertEquals(id, read.id)
                read.reply(SettingsResult(sound(id))); read.reply(SettingsResult(error = SettingsError.REQUEST_FAILED))
                assertTrue(answers.isEmpty()); f.drain()
                assertEquals(id, answers.single().value); assertFalse(f.snapshot.choosing)
            }
        }
    }

    @Test fun chooseRejectsMissingStaleAndWrongMetadataAndAlwaysTerminatesAfterPageClosure() {
        val f = Fixture(); f.open(); f.loaded(data())
        val answers = mutableListOf<SettingsResult<Long>>()
        f.act { choose(f.snapshot.token, f.snapshot.revision - 1, 12, answers::add) }
        assertEquals(SettingsError.CONFLICT, answers.single().error)
        f.act { choose(f.snapshot.token, f.snapshot.revision, 88, answers::add) }
        assertEquals(2, answers.size); assertTrue(f.session.reads.isEmpty())
        f.act { choose(f.snapshot.token, f.snapshot.revision, 12, answers::add) }
        val held = f.session.reads.single()
        f.act { close(f.snapshot.token) }
        assertEquals(SettingsError.NOT_READY, answers.last().error); assertEquals(3, answers.size)
        held.reply(SettingsResult(sound())); f.drain(); assertEquals(3, answers.size)
        f.open(); f.loaded(data())
        f.act { choose(f.snapshot.token, f.snapshot.revision, 12, answers::add) }
        f.session.reads.last().reply(SettingsResult(sound().copy(title = "changed"))); f.drain()
        assertEquals(SettingsError.CONFLICT, answers.last().error)
    }

    @Test fun updatesAndNotReadyBetweenChooseDispatchAndActorCompletionCannotChooseAnOldIdentity() {
        for (changed in listOf(true, false)) {
            val f = Fixture(); f.open(); f.loaded(data())
            val answers = mutableListOf<SettingsResult<Long>>()
            f.act { choose(f.snapshot.token, f.snapshot.revision, 12, answers::add) }
            if (changed) { f.session.observer!!.invoke(); f.drain() } else f.allowed = false
            f.session.reads.single().reply(SettingsResult(sound())); f.drain()
            assertNull(answers.single().value)
            assertEquals(if (changed) SettingsError.CONFLICT else SettingsError.NOT_READY, answers.single().error)
        }
    }

    @Test fun previewHasItsOwnCancelableOwnerAndLateCallbacksCannotReviveStoppedOrReplacedPlayback() {
        val f = Fixture(); f.open(); f.loaded(data())
        f.act { preview(f.snapshot.token, 12, 44) }
        val first = f.preview.loads.single(); assertTrue(first.current()); assertTrue(f.snapshot.previewLoading)
        assertEquals(44, first.volume)
        f.act { stopPreview(f.snapshot.token) }
        assertFalse(first.current()); assertEquals(1, first.closed)
        first.reply(SettingsResult(false)); f.drain()
        assertNull(f.snapshot.previewSoundId); assertFalse(f.snapshot.previewLoading)
        f.act { preview(f.snapshot.token, -1, 50) }
        val second = f.preview.loads.last(); second.reply(SettingsResult(true)); f.drain()
        assertEquals(-1L, f.snapshot.previewSoundId); assertTrue(f.snapshot.previewDefaultFallback)
        val oldToken = f.snapshot.token
        f.act { close(oldToken); open { true } }; f.loaded(data())
        f.act { preview(f.snapshot.token, 12, 60); stopPreview(oldToken) }
        assertTrue(f.preview.loads.last().current()); assertFalse(second.current())
        f.preview.loads.last().reply(SettingsResult(error = SettingsError.REQUEST_FAILED)); f.drain()
        assertNull(f.snapshot.previewSoundId); assertEquals(SettingsError.REQUEST_FAILED, f.snapshot.previewError)
    }

    @Test fun unknownLimitsInvalidHashAndOldRevisionsReleaseBorrowedUploadsWithoutNativeWrites() {
        val f = Fixture(); f.open(); f.loaded(data())
        f.upload(valid = false); assertEquals(1, f.cleanup)
        f.upload(expectedRevision = f.snapshot.revision - 1); assertEquals(2, f.cleanup)
        assertTrue(f.session.adds.isEmpty())
        f.act { refresh(f.snapshot.token) }; f.loaded(data().copy(limits = NotificationSoundLimits()))
        f.upload(); assertEquals(3, f.cleanup); assertEquals(SettingsError.NOT_READY, f.snapshot.error)
        assertTrue(f.session.adds.isEmpty())
    }

    @Test fun catalogChangesCancelAnAlreadyPreparedPreviewOutputAndItsLateCallbackCannotRestoreIt() {
        val f = Fixture(); f.open(); f.loaded(data())
        f.act { preview(f.snapshot.token, 12, 50) }
        val prepared = f.preview.loads.single()
        prepared.reply(SettingsResult(false)); f.drain()
        assertEquals(12L, f.snapshot.previewSoundId); assertFalse(f.snapshot.previewLoading)
        assertTrue(prepared.current())
        f.session.observer!!.invoke(); f.drain()
        assertFalse(prepared.current()); assertEquals(1, prepared.closed)
        assertNull(f.snapshot.previewSoundId); assertTrue(f.snapshot.refreshRequired)
        prepared.reply(SettingsResult(false)); f.drain()
        assertNull(f.snapshot.previewSoundId); assertEquals(1, prepared.closed)
    }

    @Test fun wrongGenerationAndRetiredUploadsReleaseTheirBorrowWithoutDispatchOrNewPageState() {
        val f = Fixture(); f.open(); f.loaded(data())
        val wrong = f.snapshot.token.copy(generation = 999)
        val answers = mutableListOf<SettingsResult<Long>>()
        f.act { choose(wrong, f.snapshot.revision, -1, answers::add) }
        assertEquals(SettingsError.NOT_READY, answers.single().error)
        val lease = ownedAttachmentLease { f.cleanup++ }
        val pending = checkNotNull(AttachmentSendLeases().retain(lease))
        val prepared = SettingsPreparedNotificationSound(PreparedNotificationSoundUpload(
            "C:/synthetic/notification.mp3", 512, 1000, "a".repeat(64)), lease)
        lease.release()
        f.act { upload(wrong, f.snapshot.revision, prepared, pending) }
        assertEquals(1, f.cleanup); assertTrue(f.session.adds.isEmpty())
        f.act { deactivate() }; f.upload()
        assertEquals(2, f.cleanup); assertTrue(f.session.adds.isEmpty()); assertFalse(f.snapshot.opened)
    }

    @Test fun uploadRetainedHandleWaitsForTheRealTerminalThenReleasesBeforeActorPublicationExactlyOnce() {
        val f = Fixture(); f.open(); f.loaded(data()); f.upload()
        val held = f.session.adds.single()
        assertEquals(0, f.cleanup); assertTrue(f.snapshot.saving)
        held.reply(SavedNotificationSoundMutationResult(true, sound(24), data(sound(), sound(24))))
        assertEquals(1, f.cleanup); assertTrue(f.snapshot.saving)
        held.reply(SavedNotificationSoundMutationResult(error = SettingsError.REQUEST_FAILED)); f.drain()
        assertEquals(1, f.cleanup); assertFalse(f.snapshot.saving)
        assertEquals(1L, f.snapshot.acceptedRevision); assertFalse(f.snapshot.readbackPending)
        assertEquals(listOf(12L, 24L), f.snapshot.data?.sounds?.map { it.id })
    }

    @Test fun uploadFailureKeepsActualCatalogAndPartialAcceptanceRemainsPendingUntilMatchingExplicitReadback() {
        val f = Fixture(); f.open(); f.loaded(data()); f.upload()
        f.session.adds.last().reply(SavedNotificationSoundMutationResult(error = SettingsError.REQUEST_FAILED)); f.drain()
        assertEquals(1, f.cleanup); assertEquals(data(), f.snapshot.data)
        assertEquals(SettingsError.REQUEST_FAILED, f.snapshot.error); assertFalse(f.snapshot.readbackPending)
        f.act { refresh(f.snapshot.token) }; f.loaded(data()); f.upload()
        f.session.adds.last().reply(SavedNotificationSoundMutationResult(true, sound(24), error = SettingsError.REQUEST_FAILED)); f.drain()
        assertTrue(f.snapshot.readbackPending); assertEquals(1L, f.snapshot.acceptedRevision)
        assertEquals(data(), f.snapshot.data); assertEquals(2, f.cleanup)
        f.act { refresh(f.snapshot.token) }; f.loaded(data())
        assertTrue(f.snapshot.readbackPending)
        f.act { refresh(f.snapshot.token) }; f.loaded(data(sound(), sound(24)))
        assertFalse(f.snapshot.readbackPending)
    }

    @Test fun newOwnerWaitsForOldWriteThenFreshLoadsAndRetirementCannotReleaseAnInFlightUploadEarly() {
        val f = Fixture(); f.open(); f.loaded(data()); f.upload()
        val held = f.session.adds.single()
        f.act { close(f.snapshot.token); open { true } }
        val newToken = f.snapshot.token
        assertTrue(f.snapshot.saving); assertFalse(held.current()); assertEquals(0, f.cleanup)
        f.upload(); assertEquals(1, f.cleanup); assertEquals(1, f.session.adds.size)
        held.reply(SavedNotificationSoundMutationResult(true, sound(24), data(sound(24))))
        assertEquals(2, f.cleanup); f.drain()
        assertEquals(newToken, f.snapshot.token); assertTrue(f.snapshot.loading); assertNull(f.snapshot.data)
        assertEquals(2, f.session.loads.size)
        f.loaded(data(sound(), sound(24))); assertFalse(f.snapshot.readbackPending)
        f.upload(); val retired = f.session.adds.last()
        f.act { deactivate() }; assertEquals(2, f.cleanup)
        retired.reply(SavedNotificationSoundMutationResult(error = SettingsError.NOT_READY))
        assertEquals(3, f.cleanup)
        // Simulate engine discarding actor work after retirement: releasing the file did not need it.
        f.queue.clear(); assertFalse(f.snapshot.opened)
    }

    @Test fun removeChecksRealMetadataButIgnoresMutableFileProgressAndWaitsForObservedRemoval() {
        val f = Fixture(); f.open(); f.loaded(data())
        f.act { remove(f.snapshot.token, f.snapshot.revision, sound().copy(title = "other")) }
        assertEquals(SettingsError.CONFLICT, f.snapshot.error); assertTrue(f.session.removes.isEmpty())
        val progress = sound().copy(file = sound().file.copy(downloading = true, downloadedSize = 17))
        f.act { remove(f.snapshot.token, f.snapshot.revision, progress) }
        val held = f.session.removes.single(); assertEquals(100L, held.account)
        held.reply(SavedNotificationSoundMutationResult(true, actual = data(), error = SettingsError.CONFLICT)); f.drain()
        assertTrue(f.snapshot.readbackPending); assertEquals(data(), f.snapshot.data)
        assertEquals(1L, f.snapshot.acceptedRevision)
        f.act { refresh(f.snapshot.token) }; f.loaded(data(*emptyArray()))
        assertFalse(f.snapshot.readbackPending); assertTrue(f.snapshot.data!!.sounds.isEmpty())
    }

    @Test fun readyAndPageLeasesGateLoadsChoicesPreviewsAndUploadsIncludingLateCallbacks() {
        val f = Fixture(); f.allowed = false; f.open(); assertFalse(f.snapshot.opened)
        f.allowed = true; f.open(); f.page = false
        assertFalse(f.session.loads.single().current())
        f.loaded(data()); assertNull(f.snapshot.data); assertFalse(f.snapshot.opened)
        f.page = true; f.open(); f.loaded(data()); f.allowed = false
        val answers = mutableListOf<SettingsResult<Long>>()
        f.act { choose(f.snapshot.token, f.snapshot.revision, 0, answers::add); preview(f.snapshot.token, 12, 50) }
        f.upload(); assertEquals(SettingsError.NOT_READY, answers.single().error)
        assertEquals(1, f.cleanup); assertTrue(f.session.adds.isEmpty()); assertTrue(f.preview.loads.isEmpty())
    }

    @Test fun throwingNativeAndPreviewBoundariesTerminateWithoutLeakingOrStartingASecondWrite() {
        val f = Fixture(); f.session.throwLoad = true; f.open()
        assertEquals(SettingsError.REQUEST_FAILED, f.snapshot.error)
        f.session.throwLoad = false; f.act { refresh(f.snapshot.token) }; f.loaded(data())
        f.preview.throwPlay = true; f.act { preview(f.snapshot.token, 12, 50) }
        assertEquals(SettingsError.REQUEST_FAILED, f.snapshot.previewError)
        f.session.throwAdd = true; f.upload()
        assertEquals(1, f.cleanup); assertFalse(f.snapshot.saving)
        assertEquals(SettingsError.REQUEST_FAILED, f.snapshot.error)
        f.act { refresh(f.snapshot.token) }; f.loaded(data()); f.session.throwAdd = false; f.upload()
        f.act { remove(f.snapshot.token, f.snapshot.revision, sound()) }
        assertTrue(f.session.removes.isEmpty()); assertEquals(1, f.session.adds.size)
    }

    @Test fun explicitCatalogReadWarmsActualSoundsInListOrderWithAtMostTwoActiveOwners() {
        val f = Fixture(warmEnabled = true)
        assertTrue(f.warmer.loads.isEmpty())
        f.open(); assertTrue(f.warmer.loads.isEmpty())
        f.loaded(data(sound(-55), sound(24), sound(31), sound(42), sound(50)))
        assertEquals(listOf(-55L, 24L), f.warmer.loads.map { it.sound.id })
        assertEquals(2, f.warmer.alive); assertEquals(2, f.warmer.peakAlive)
        assertTrue(f.warmer.loads.all { it.account == 100L && it.current() })
        f.warmer.loads.first().reply(SettingsResult(Unit))
        assertEquals(2, f.warmer.loads.size)
        f.drain()
        assertEquals(listOf(-55L, 24L, 31L), f.warmer.loads.map { it.sound.id })
        f.warmer.loads[1].reply(SettingsResult(error = SettingsError.REQUEST_FAILED)); f.drain()
        assertEquals(listOf(-55L, 24L, 31L, 42L), f.warmer.loads.map { it.sound.id })
        assertNull(f.snapshot.error); assertFalse(f.snapshot.refreshRequired)
        f.warmer.loads[2].reply(SettingsResult(Unit)); f.drain()
        f.warmer.loads[3].reply(SettingsResult(Unit)); f.drain()
        f.warmer.loads[4].reply(SettingsResult(Unit)); f.drain()
        assertEquals(0, f.warmer.alive); assertEquals(2, f.warmer.peakAlive)
        assertTrue(f.preview.loads.isEmpty()); assertTrue(f.session.adds.isEmpty()); assertTrue(f.session.reads.isEmpty())
    }

    @Test fun refreshAndChangedCatalogCancelWarmOwnersAndOldCompletionsNeverDrainIntoANewBatch() {
        val f = Fixture(warmEnabled = true); f.open(); f.loaded(data(sound(), sound(24), sound(31)))
        val first = f.warmer.loads.toList()
        f.act { refresh(f.snapshot.token) }
        assertTrue(first.all { !it.current() && it.closed == 1 }); assertEquals(0, f.warmer.alive)
        first[0].reply(SettingsResult(Unit)); first[1].reply(SettingsResult(Unit)); f.drain()
        assertEquals(2, f.warmer.loads.size)
        f.loaded(data(sound(80), sound(90), sound(100)))
        assertEquals(listOf(12L, 24L, 80L, 90L), f.warmer.loads.map { it.sound.id })
        first[0].reply(SettingsResult(Unit)); f.drain(); assertEquals(4, f.warmer.loads.size)
        f.session.observer!!.invoke(); f.drain()
        assertEquals(0, f.warmer.alive); assertTrue(f.snapshot.refreshRequired)
        assertTrue(f.warmer.loads.takeLast(2).all { !it.current() })
        f.warmer.loads.last().reply(SettingsResult(Unit)); f.drain()
        assertEquals(4, f.warmer.loads.size)
    }

    @Test fun closeAndPageGatesCancelOnlyThisCatalogWarmBatchAndNewPagesRequireNewMetadata() {
        for (close in listOf(true, false)) {
            val f = Fixture(warmEnabled = true); f.open(); f.loaded(data(sound(), sound(24), sound(31)))
            val old = f.warmer.loads.toList()
            if (close) f.act { close(f.snapshot.token) } else { f.page = false; f.allowed = false }
            assertTrue(old.none { it.current() })
            old.first().reply(SettingsResult(Unit)); f.drain()
            assertEquals(0, f.warmer.alive); assertEquals(2, f.warmer.loads.size)
            f.page = true; f.allowed = true; f.open()
            assertEquals(2, f.warmer.loads.size)
            old.last().reply(SettingsResult(Unit)); f.drain(); assertEquals(2, f.warmer.loads.size)
            f.loaded(data(sound(44))); assertEquals(44L, f.warmer.loads.last().sound.id)
            f.act { deactivate() }; assertEquals(0, f.warmer.alive)
        }
    }

    @Test fun synchronousWarmCompletionsAreBoundedStackSafeAndEmptyOrClosedMetadataDoesNotWarm() {
        val f = Fixture(warmEnabled = true, inlineCallbacks = true)
        f.warmer.synchronous = true; f.open(); f.loaded(data(*Array(4096) { sound(it.toLong() + 1) }))
        assertEquals(4096, f.warmer.loads.size); assertEquals(0, f.warmer.alive)
        assertTrue(f.warmer.peakAlive <= 2)
        assertNull(f.snapshot.error); assertTrue(f.preview.loads.isEmpty())
        f.act { refresh(f.snapshot.token) }; f.loaded(data(*emptyArray()))
        assertEquals(4096, f.warmer.loads.size)
        f.act { refresh(f.snapshot.token) }; val pending = f.session.loads.last()
        f.act { close(f.snapshot.token) }
        pending.reply(SettingsResult(data(sound(7000)))); f.drain()
        assertEquals(4096, f.warmer.loads.size); assertFalse(f.snapshot.opened)
    }

    @Test fun warmFailureOrThrowDoesNotBecomeAMetadataSaveErrorOrBlockFreshChoice() {
        val f = Fixture(warmEnabled = true); f.warmer.throwWarm = true
        f.open(); f.loaded(data(sound(), sound(24), sound(31)))
        assertNull(f.snapshot.error); assertFalse(f.snapshot.loading); assertFalse(f.snapshot.refreshRequired)
        val answers = mutableListOf<SettingsResult<Long>>()
        f.act { choose(f.snapshot.token, f.snapshot.revision, 12, answers::add) }
        f.session.reads.last().reply(SettingsResult(sound())); f.drain()
        assertEquals(12L, answers.single().value)
    }

    @Test fun acceptedUploadMarksOnlyItsCurrentOwnersRealNewIdEvenWhenReadbackIsUnknown() {
        val f = Fixture(); f.open(); f.loaded(data()); f.upload()
        assertNull(f.snapshot.lastAddedSoundId)
        f.session.adds.last().reply(SavedNotificationSoundMutationResult(true, sound(-55), error = SettingsError.REQUEST_FAILED))
        f.drain()
        assertEquals(-55L, f.snapshot.lastAddedSoundId); assertTrue(f.snapshot.readbackPending)
        assertEquals(listOf(sound()), f.snapshot.data!!.sounds)
        f.act { refresh(f.snapshot.token) }; f.loaded(data(sound(), sound(-55)))
        assertEquals(-55L, f.snapshot.lastAddedSoundId); assertFalse(f.snapshot.readbackPending)
        f.upload(); assertNull(f.snapshot.lastAddedSoundId)
        f.session.adds.last().reply(SavedNotificationSoundMutationResult(error = SettingsError.REQUEST_FAILED)); f.drain()
        assertNull(f.snapshot.lastAddedSoundId)
        f.act { refresh(f.snapshot.token) }; f.loaded(data()); f.upload()
        f.act { close(f.snapshot.token); open { true } }
        f.session.adds.last().reply(SavedNotificationSoundMutationResult(true, sound(77), data(sound(), sound(77)))); f.drain()
        assertNull(f.snapshot.lastAddedSoundId); assertTrue(f.snapshot.loading)
        f.loaded(data(sound(), sound(77))); assertNull(f.snapshot.lastAddedSoundId)
    }

    @Test fun rejectedOrSpecialAddedIdsAndSuccessfulRemovesNeverMasqueradeAsANewUploadSelection() {
        for (id in listOf(0L, -1L)) {
            val f = Fixture(); f.open(); f.loaded(data()); f.upload()
            f.session.adds.last().reply(SavedNotificationSoundMutationResult(true, sound(id), data())); f.drain()
            assertNull(f.snapshot.lastAddedSoundId)
        }
        val f = Fixture(); f.open(); f.loaded(data()); f.upload()
        f.session.adds.last().reply(SavedNotificationSoundMutationResult(true, sound(24), data(sound(), sound(24)))); f.drain()
        assertEquals(24L, f.snapshot.lastAddedSoundId)
        f.act { remove(f.snapshot.token, f.snapshot.revision, sound(24)) }
        assertNull(f.snapshot.lastAddedSoundId)
        f.session.removes.last().reply(SavedNotificationSoundMutationResult(true, sound(24), data())); f.drain()
        assertNull(f.snapshot.lastAddedSoundId); assertEquals(2L, f.snapshot.acceptedRevision)
    }

    @Test fun theUploadsOwnNativeUpdateDoesNotStaleItsFreshStampedReadbackOrBlockPickerSaveAndWarm() {
        val f = Fixture(warmEnabled = true); f.open()
        f.session.currentNativeRevision = 1
        f.loaded(data().copy(nativeRevision = 1)); f.upload()
        f.session.currentNativeRevision = 2
        f.session.observer!!.invoke(); f.drain()
        assertTrue(f.snapshot.refreshRequired)
        val actual = data(sound(), sound(24)).copy(nativeRevision = 2)
        f.session.adds.last().reply(SavedNotificationSoundMutationResult(true, sound(24), actual)); f.drain()
        assertFalse(f.snapshot.refreshRequired); assertFalse(f.snapshot.readbackPending); assertNull(f.snapshot.error)
        assertEquals(24L, f.snapshot.lastAddedSoundId)
        assertEquals(listOf(12L, 24L), f.warmer.loads.takeLast(2).map { it.sound.id })
        val answers = mutableListOf<SettingsResult<Long>>()
        f.act { choose(f.snapshot.token, f.snapshot.revision, 24, answers::add) }
        assertEquals(24L, f.session.reads.last().id)
        f.session.reads.last().reply(SettingsResult(sound(24))); f.drain()
        assertEquals(24L, answers.single().value)
    }

    @Test fun freshStampedListCanIncludeAnObservedUpdateWithoutRequiringAnotherReload() {
        val f = Fixture(warmEnabled = true); f.open()
        f.session.currentNativeRevision = 2
        f.session.observer!!.invoke(); f.drain()
        f.loaded(data(sound(24)).copy(nativeRevision = 2))
        assertFalse(f.snapshot.refreshRequired); assertNull(f.snapshot.error)
        assertEquals(listOf(24L), f.warmer.loads.map { it.sound.id })
        val answers = mutableListOf<SettingsResult<Long>>()
        f.act { choose(f.snapshot.token, f.snapshot.revision, -1, answers::add) }
        assertEquals(-1L, answers.single().value)
    }

    @Test fun nativeChangeAfterMutationReadbackButBeforeActorPublicationStillRequiresRefreshAndBlocksSave() {
        val f = Fixture(warmEnabled = true); f.open()
        f.session.currentNativeRevision = 1; f.loaded(data().copy(nativeRevision = 1)); f.upload()
        f.session.currentNativeRevision = 2
        f.session.adds.last().reply(SavedNotificationSoundMutationResult(true, sound(24),
            data(sound(), sound(24)).copy(nativeRevision = 2)))
        // The native lock sees this revision before its observer's queued actor notification is processed.
        f.session.currentNativeRevision = 3
        f.drain()
        assertTrue(f.snapshot.refreshRequired); assertTrue(f.snapshot.readbackPending)
        assertEquals(24L, f.snapshot.lastAddedSoundId); assertEquals(0, f.warmer.alive)
        val answers = mutableListOf<SettingsResult<Long>>()
        f.act { choose(f.snapshot.token, f.snapshot.revision, 24, answers::add) }
        assertEquals(SettingsError.NOT_READY, answers.single().error); assertTrue(f.session.reads.isEmpty())
        f.act { refresh(f.snapshot.token) }
        f.loaded(data(sound(), sound(24)).copy(nativeRevision = 3))
        assertFalse(f.snapshot.refreshRequired); assertFalse(f.snapshot.readbackPending)
        assertEquals(2, f.warmer.alive)
    }

    @Test fun nativeChangeAfterListCallbackButBeforeActorPublicationCannotAuthorizeWarmOrSave() {
        val f = Fixture(warmEnabled = true); f.open()
        f.session.currentNativeRevision = 5
        f.session.loads.last().reply(SettingsResult(data(sound(24)).copy(nativeRevision = 5)))
        f.session.currentNativeRevision = 6
        f.drain()
        assertTrue(f.snapshot.refreshRequired); assertTrue(f.warmer.loads.isEmpty())
        val answers = mutableListOf<SettingsResult<Long>>()
        f.act { choose(f.snapshot.token, f.snapshot.revision, -1, answers::add) }
        assertEquals(SettingsError.CONFLICT, answers.single().error)
        f.act { refresh(f.snapshot.token) }; f.loaded(data(sound(24)).copy(nativeRevision = 6))
        assertFalse(f.snapshot.refreshRequired); assertEquals(listOf(24L), f.warmer.loads.map { it.sound.id })
    }

    @Test fun volumeReadsUseTheRealCatalogAccountAndReplyOnceAfterTheLocalStoreReturns() {
        val store = Volumes(); val f = Fixture(volumeStore = store); f.open(); f.loaded(data())
        val before = f.snapshot
        val answers = mutableListOf<SettingsResult<NotificationSoundVolumeReading>>()
        for (target in listOf(NotificationSoundVolumeTarget.Scope(NotificationScope.GROUP_CHATS),
            NotificationSoundVolumeTarget.Chat(-55))) {
            f.act { readVolume(f.snapshot.token, target) {
                assertFalse(store.inside); answers += it
            } }
            assertEquals(100L, answers.last().value?.accountUserId); assertEquals(target, answers.last().value?.target)
            assertNull(answers.last().value?.overridePercent)
        }
        assertEquals(2, answers.size); assertEquals(2, store.reads.size)
        assertEquals(before, f.snapshot); assertEquals(0, f.volumeChanges)
    }

    @Test fun volumeTokenPageReadyUnknownCatalogAndUnavailableStoreGatesNeverTouchLocalPreferences() {
        val store = Volumes(); val f = Fixture(volumeStore = store)
        val answers = mutableListOf<SettingsResult<NotificationSoundVolumeReading>>()
        val target = NotificationSoundVolumeTarget.Chat(44)
        f.open()
        f.act { readVolume(f.snapshot.token, target, answers::add) }
        assertEquals(SettingsError.NOT_READY, answers.last().error)
        f.loaded(data())
        val token = f.snapshot.token
        f.act { readVolume(token.copy(generation = 99), target, answers::add) }
        f.page = false
        f.act { readVolume(token, target, answers::add) }
        f.page = true; f.allowed = false
        f.act { readVolume(token, target, answers::add) }
        f.allowed = true; f.act { close(token); readVolume(token, target, answers::add) }
        assertEquals(5, answers.size); assertTrue(answers.all { it.error == SettingsError.NOT_READY })
        assertTrue(store.reads.isEmpty())
        val unavailable = Fixture(); unavailable.open(); unavailable.loaded(data())
        unavailable.act { readVolume(unavailable.snapshot.token, target, answers::add) }
        assertEquals(SettingsError.NOT_READY, answers.last().error)
    }

    @Test fun successfulChooseCallbackCanImmediatelyCasSaveVolumeWithoutAnyCatalogOrNativeWriteRevision() {
        val store = Volumes(); val f = Fixture(volumeStore = store); f.open(); f.loaded(data())
        val target = NotificationSoundVolumeTarget.Scope(NotificationScope.PRIVATE_CHATS)
        val expected = store.read(100, target).value!!
        val answers = mutableListOf<SettingsResult<NotificationSoundVolumeReading>>()
        var revisionAtCallback = -1L
        f.act { choose(f.snapshot.token, f.snapshot.revision, 12) { chosen ->
            assertEquals(12L, chosen.value); assertFalse(f.snapshot.choosing)
            revisionAtCallback = f.snapshot.revision
            saveVolume(f.snapshot.token, expected, 1) {
                assertFalse(store.inside); answers += it
            }
        } }
        f.session.reads.last().reply(SettingsResult(sound())); f.drain()
        assertEquals(1, answers.single().value?.overridePercent); assertEquals(1, f.volumeChanges)
        assertEquals(revisionAtCallback, f.snapshot.revision); assertEquals(0L, f.snapshot.acceptedRevision)
        assertTrue(f.session.adds.isEmpty()); assertTrue(f.session.removes.isEmpty())
        f.act { saveVolume(f.snapshot.token, answers.single().value!!, null, answers::add) }
        assertNull(answers.last().value?.overridePercent); assertNull(answers.last().error)
        assertEquals(2, f.volumeChanges); assertEquals(revisionAtCallback, f.snapshot.revision)
    }

    @Test fun invalidTargetsPercentsAndForeignAccountReadingsAreRejectedBeforeTheLocalStore() {
        val store = Volumes(); val f = Fixture(volumeStore = store); f.open(); f.loaded(data())
        val target = NotificationSoundVolumeTarget.Chat(-55)
        val expected = NotificationSoundVolumeReading(100, target, null, 1)
        val answers = mutableListOf<SettingsResult<NotificationSoundVolumeReading>>()
        f.act {
            readVolume(f.snapshot.token, NotificationSoundVolumeTarget.Chat(0), answers::add)
            saveVolume(f.snapshot.token, expected.copy(target = NotificationSoundVolumeTarget.Chat(0)), 50, answers::add)
            saveVolume(f.snapshot.token, expected, 0, answers::add)
            saveVolume(f.snapshot.token, expected, 101, answers::add)
            saveVolume(f.snapshot.token, expected.copy(overridePercent = 0), 50, answers::add)
            saveVolume(f.snapshot.token, expected.copy(accountUserId = 999), 50, answers::add)
        }
        assertEquals(6, answers.size)
        assertTrue(answers.take(5).all { it.error == SettingsError.INVALID_VALUE })
        assertEquals(SettingsError.CONFLICT, answers.last().error)
        assertTrue(store.reads.isEmpty()); assertTrue(store.saves.isEmpty()); assertEquals(0, f.volumeChanges)
    }

    @Test fun localVolumeCasConflictsKeepTheDraftAndExplicitRereadCanRetryWithoutServerWrites() {
        val store = Volumes(); val f = Fixture(volumeStore = store); f.open(); f.loaded(data())
        val target = NotificationSoundVolumeTarget.Chat(44)
        val expected = store.read(100, target).value!!
        store.save(expected, 80)
        val answers = mutableListOf<SettingsResult<NotificationSoundVolumeReading>>()
        f.act { saveVolume(f.snapshot.token, expected, 25, answers::add) }
        assertEquals(SettingsError.CONFLICT, answers.single().error); assertEquals(0, f.volumeChanges)
        f.act { readVolume(f.snapshot.token, target, answers::add) }
        assertEquals(80, answers.last().value?.overridePercent)
        f.act { saveVolume(f.snapshot.token, answers.last().value!!, 25, answers::add) }
        assertEquals(25, answers.last().value?.overridePercent); assertEquals(1, f.volumeChanges)
        assertEquals(0L, f.snapshot.acceptedRevision); assertTrue(f.session.adds.isEmpty())
    }

    @Test fun refreshAndPendingCatalogOperationsCannotSaveLocalVolumeAndUnobservedNativeChangesAreStale() {
        for (state in listOf("refresh", "saving", "choosing", "readback", "native")) {
            val store = Volumes(); val f = Fixture(volumeStore = store); f.open()
            f.session.currentNativeRevision = 1; f.loaded(data().copy(nativeRevision = 1))
            val expected = store.read(100, NotificationSoundVolumeTarget.Chat(44)).value!!
            when (state) {
                "refresh" -> { f.session.observer!!.invoke(); f.drain() }
                "saving" -> f.upload()
                "choosing" -> f.act { choose(f.snapshot.token, f.snapshot.revision, 12) {} }
                "readback" -> {
                    f.upload(); f.session.adds.last().reply(SavedNotificationSoundMutationResult(true, sound(24),
                        error = SettingsError.REQUEST_FAILED)); f.drain()
                }
                "native" -> f.session.currentNativeRevision = 2
            }
            val answers = mutableListOf<SettingsResult<NotificationSoundVolumeReading>>()
            f.act { saveVolume(f.snapshot.token, expected, 70, answers::add) }
            assertNotNull(answers.single().error, state)
            assertTrue(store.saves.isEmpty(), state); assertEquals(0, f.volumeChanges, state)
        }
    }

    @Test fun volumeStoreFailureAndThrowTerminateOnceAndRetryWithoutClearingTheCatalogOrDraft() {
        val store = Volumes(); val f = Fixture(volumeStore = store); f.open(); f.loaded(data())
        val target = NotificationSoundVolumeTarget.Chat(44)
        val expected = store.read(100, target).value!!
        val before = f.snapshot
        val answers = mutableListOf<SettingsResult<NotificationSoundVolumeReading>>()
        store.throwRead = true
        f.act { readVolume(f.snapshot.token, target, answers::add) }
        store.throwRead = false; store.throwSave = true
        f.act { saveVolume(f.snapshot.token, expected, 70, answers::add) }
        store.throwSave = false; store.failure = SettingsError.REQUEST_FAILED
        f.act { saveVolume(f.snapshot.token, expected, 70, answers::add) }
        assertEquals(3, answers.size); assertTrue(answers.all { it.error == SettingsError.REQUEST_FAILED })
        assertEquals(before, f.snapshot); assertEquals(0, f.volumeChanges)
        store.failure = null
        f.act { saveVolume(f.snapshot.token, expected, 70, answers::add) }
        assertEquals(70, answers.last().value?.overridePercent); assertEquals(1, f.volumeChanges)
    }

    @Test fun closedOwnerAndMalformedLocalStoreReadingsCannotBeDeliveredToAnotherPage() {
        val store = Volumes(); val f = Fixture(volumeStore = store); f.open(); f.loaded(data())
        val target = NotificationSoundVolumeTarget.Chat(44)
        val answers = mutableListOf<SettingsResult<NotificationSoundVolumeReading>>()
        store.onRead = { f.controller.close(f.snapshot.token) }
        f.act { readVolume(f.snapshot.token, target, answers::add) }
        assertEquals(SettingsError.NOT_READY, answers.single().error)
        store.onRead = {}; f.open(); f.loaded(data())
        store.readingTransform = { it.copy(accountUserId = 999) }
        f.act { readVolume(f.snapshot.token, target, answers::add) }
        assertEquals(SettingsError.CONFLICT, answers.last().error)
        store.readingTransform = { it.copy(overridePercent = 101) }
        f.act { readVolume(f.snapshot.token, target, answers::add) }
        assertEquals(SettingsError.REQUEST_FAILED, answers.last().error)
        store.readingTransform = { it }
        f.act { readVolume(f.snapshot.token, target, answers::add) }
        assertNotNull(answers.last().value); assertNull(answers.last().error)
        assertEquals(4, answers.size); assertEquals(0, f.volumeChanges)
    }

    @Test fun localVolumeSaveCannotCrossPageTokensReadyGateAccountOrGenerationRetirement() {
        val store = Volumes(); val f = Fixture(volumeStore = store); f.open(); f.loaded(data())
        val expected = store.read(100, NotificationSoundVolumeTarget.Chat(-55)).value!!
        val old = f.snapshot.token
        val answers = mutableListOf<SettingsResult<NotificationSoundVolumeReading>>()
        f.act { close(old); open { true } }; f.loaded(data())
        f.act { saveVolume(old, expected, 40, answers::add) }
        f.allowed = false
        f.act { saveVolume(f.snapshot.token, expected, 40, answers::add) }
        f.allowed = true
        f.act { saveVolume(f.snapshot.token.copy(generation = 99), expected, 40, answers::add) }
        f.act { deactivate(); saveVolume(f.snapshot.token, expected, 40, answers::add) }
        assertEquals(4, answers.size); assertTrue(answers.all { it.error == SettingsError.NOT_READY })
        assertTrue(store.saves.isEmpty()); assertEquals(0, f.volumeChanges)
        val otherAccount = Fixture(volumeStore = store); otherAccount.open()
        otherAccount.loaded(data().copy(accountUserId = 200))
        otherAccount.act { saveVolume(otherAccount.snapshot.token, expected, 40, answers::add) }
        assertEquals(SettingsError.CONFLICT, answers.last().error); assertTrue(store.saves.isEmpty())
    }

    private class Load(val current: () -> Boolean, val reply: (SettingsResult<SavedNotificationSoundsData>) -> Unit)
    private class Read(val account: Long, val id: Long, val current: () -> Boolean,
        val reply: (SettingsResult<SavedNotificationSound>) -> Unit)
    private class Write(val current: () -> Boolean, val reply: (SavedNotificationSoundMutationResult) -> Unit,
        val account: Long = 100)
    private class Session : TdSavedNotificationSoundsSession {
        var observer: (() -> Unit)? = null
        var throwLoad = false; var throwAdd = false
        var currentNativeRevision = 0L
        val loads = mutableListOf<Load>(); val reads = mutableListOf<Read>()
        val adds = mutableListOf<Write>(); val removes = mutableListOf<Write>()
        override fun observe(observer: (() -> Unit)?) { this.observer = observer }
        override fun isCurrent(data: SavedNotificationSoundsData) = data.accountUserId == 100L &&
            data.nativeRevision > 0 && data.nativeRevision == currentNativeRevision
        override fun load(active: () -> Boolean, result: (SettingsResult<SavedNotificationSoundsData>) -> Unit) {
            if (throwLoad) error("synthetic")
            loads += Load(active, result)
        }
        override fun read(accountUserId: Long, soundId: Long, active: () -> Boolean,
            result: (SettingsResult<SavedNotificationSound>) -> Unit,
        ) { reads += Read(accountUserId, soundId, active, result) }
        override fun add(expected: SavedNotificationSoundsData, prepared: PreparedNotificationSoundUpload,
            active: () -> Boolean, result: (SavedNotificationSoundMutationResult) -> Unit,
        ) {
            if (throwAdd) error("synthetic")
            adds += Write(active, result, expected.accountUserId)
        }
        override fun remove(accountUserId: Long, expected: SavedNotificationSound, active: () -> Boolean,
            result: (SavedNotificationSoundMutationResult) -> Unit,
        ) { removes += Write(active, result, accountUserId) }
    }
    private class Preview : NotificationSoundPreview {
        class Play(val current: () -> Boolean, val volume: Int, val reply: (SettingsResult<Boolean>) -> Unit) {
            var closed = 0
        }
        val loads = mutableListOf<Play>(); var closed = 0; var throwPlay = false
        override fun play(accountUserId: Long, soundId: Long, volumePercent: Int, active: () -> Boolean,
            result: (SettingsResult<Boolean>) -> Unit,
        ): NotificationSoundLoad {
            if (throwPlay) error("synthetic")
            val play = Play(active, volumePercent, result); loads += play
            return NotificationSoundLoad { play.closed++ }
        }
        override fun close() { closed++ }
    }
    private class Warmer {
        class Warm(val account: Long, val sound: SavedNotificationSound, val current: () -> Boolean,
            val reply: (SettingsResult<Unit>) -> Unit,
        ) { var closed = 0 }
        val loads = mutableListOf<Warm>()
        var alive = 0; var peakAlive = 0; var synchronous = false; var throwWarm = false
        fun warm(account: Long, sound: SavedNotificationSound, active: () -> Boolean,
            result: (SettingsResult<Unit>) -> Unit,
        ): NotificationSoundLoad {
            if (throwWarm) error("synthetic")
            val request = Warm(account, sound, active, result); loads += request
            alive++; peakAlive = maxOf(peakAlive, alive)
            if (synchronous) result(SettingsResult(Unit))
            return NotificationSoundLoad { request.closed++; alive-- }
        }
    }
    private class Volumes : NotificationSoundVolumeStore {
        val reads = mutableListOf<Pair<Long, NotificationSoundVolumeTarget>>()
        val saves = mutableListOf<Pair<NotificationSoundVolumeReading, Int?>>()
        private val values = mutableMapOf<Pair<Long, NotificationSoundVolumeTarget>, Int>()
        private var revision = 1L
        var inside = false; var throwRead = false; var throwSave = false
        var failure: SettingsError? = null
        var onRead: () -> Unit = {}
        var readingTransform: (NotificationSoundVolumeReading) -> NotificationSoundVolumeReading = { it }
        override fun read(accountUserId: Long, target: NotificationSoundVolumeTarget): SettingsResult<NotificationSoundVolumeReading> {
            inside = true
            try {
                if (throwRead) error("synthetic")
                reads += accountUserId to target; onRead()
                return failure?.let { SettingsResult(error = it) } ?: SettingsResult(readingTransform(
                    NotificationSoundVolumeReading(accountUserId, target, values[accountUserId to target], revision)))
            } finally { inside = false }
        }
        override fun save(expected: NotificationSoundVolumeReading, overridePercent: Int?): SettingsResult<NotificationSoundVolumeReading> {
            inside = true
            try {
                if (throwSave) error("synthetic")
                saves += expected to overridePercent
                failure?.let { return SettingsResult(error = it) }
                val key = expected.accountUserId to expected.target
                if (expected.revision != revision || expected.overridePercent != values[key])
                    return SettingsResult(error = SettingsError.CONFLICT)
                if (overridePercent == null) values.remove(key) else values[key] = overridePercent
                revision++
                return SettingsResult(NotificationSoundVolumeReading(expected.accountUserId, expected.target, overridePercent, revision))
            } finally { inside = false }
        }
        override fun resolve(accountUserId: Long, chatId: Long, scope: NotificationScope, globalVolume: Int): SettingsResult<Int> =
            SettingsResult(error = SettingsError.UNSUPPORTED)
    }
    private class Fixture(warmEnabled: Boolean = false, inlineCallbacks: Boolean = false,
        volumeStore: NotificationSoundVolumeStore? = null,
    ) {
        var allowed = true; var page = true; var invalidations = 0; var cleanup = 0; var volumeChanges = 0
        val queue = ArrayDeque<() -> Unit>()
        private var currentActor = false
        val session = Session(); val preview = Preview(); val warmer = Warmer()
        var snapshot = SavedNotificationSoundsSnapshot()
        val controller = SavedNotificationSoundsController(8, session, preview,
            { if (inlineCallbacks && currentActor) it() else queue.addLast(it) },
            { check(currentActor); snapshot = it }, { allowed }, { invalidations++ },
            warmSound = { account, sound, active, result ->
                if (warmEnabled) warmer.warm(account, sound, active, result)
                else { result(SettingsResult(Unit)); NotificationSoundLoad {} }
            }, soundVolumes = volumeStore, soundVolumeChanged = { volumeChanges++ })
        fun act(action: SavedNotificationSoundsController.() -> Unit) {
            queue.addLast { controller.action() }; drain()
        }
        fun drain() {
            check(!currentActor); currentActor = true
            try { while (queue.isNotEmpty()) queue.removeFirst().invoke() } finally { currentActor = false }
        }
        fun open() = act { open { page } }
        fun loaded(data: SavedNotificationSoundsData?, error: SettingsError? = null) {
            session.loads.last().reply(SettingsResult(data, error)); drain()
        }
        fun upload(valid: Boolean = true, expectedRevision: Long = snapshot.revision) {
            val lease = ownedAttachmentLease { cleanup++ }
            val retained = checkNotNull(AttachmentSendLeases().retain(lease))
            val prepared = SettingsPreparedNotificationSound(PreparedNotificationSoundUpload(
                "C:/synthetic/notification.mp3", 512, 1000, if (valid) "a".repeat(64) else ""), lease)
            lease.release()
            act { upload(snapshot.token, expectedRevision, prepared, retained) }
        }
    }
    private companion object {
        fun sound(id: Long = 12) = SavedNotificationSound(id, 1, 1, "synthetic", "",
            MediaFile(id = 7, uniqueId = "fixture-file-$id"))
        fun data(vararg sounds: SavedNotificationSound = arrayOf(sound())) = SavedNotificationSoundsData(
            100, sounds.toList(), NotificationSoundLimits(1024, 3, 10))
    }
}
