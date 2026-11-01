package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.*

/** Requires the accompanying parent patch; draft tests have not been compiled or run. */
class SettingsProfileStickerAvatarParentIntegrationTest {
    @Test fun observedRealResultIsRecordedBeforeUnlockedObserverAndObserverThrowCannotBlockConvergence() {
        val f = Fixture().apply { loaded(43) }
        val picker = f.openPicker()
        val selection = f.select(picker)
        val parent = f.snapshot.token
        var recordedBeforeObserver = false
        val duplicateReturned = AtomicBoolean(false)
        val observerEntered = CountDownLatch(1)
        val finishObserver = CountDownLatch(1)
        val results = mutableListOf<ProfileStickerAvatarMutationResult>()
        val admissions = mutableListOf<Long?>()
        lateinit var write: Native.Write
        f.throwPublish.set(true)
        f.parent.saveStickerAvatar(parent, picker, selection, 91, background(), { true }, admissions::add) { result ->
            results += result
            recordedBeforeObserver = f.receipts.find(checkNotNull(f.parent.mutationReceiptScope), parent,
                result.revision, SettingsProfileExtrasOperation.UPLOAD_STICKER_AVATAR) == SettingsResult(Unit)
            val done = CountDownLatch(1)
            thread(isDaemon = true) {
                try { write.done(SettingsResult(error = SettingsError.FORBIDDEN)) }
                finally { done.countDown() }
            }
            duplicateReturned.set(done.await(2, TimeUnit.SECONDS))
            observerEntered.countDown()
            finishObserver.await(2, TimeUnit.SECONDS)
            error("synthetic observer failure")
        }
        f.throwPublish.set(false)
        write = f.native.writes.single()
        val revision = assertNotNull(admissions.single())
        val before = AtomicBoolean(false)
        thread(isDaemon = true) { before.set(write.current()) }.join(2_000)
        assertTrue(before.get())
        val worker = thread(isDaemon = true) { write.done(SettingsResult(Unit)) }
        try {
            assertTrue(observerEntered.await(2, TimeUnit.SECONDS))
            assertTrue(recordedBeforeObserver)
            assertTrue(duplicateReturned.get(), "A second thread must enter the result lock while observer is running")
            assertTrue(f.queue.isNotEmpty(), "Actor settlement is enqueued before the foreign observer")
            f.drain()
            assertEquals(revision, f.snapshot.acceptedRevision)
        } finally {
            finishObserver.countDown()
            worker.join(2_000)
        }
        assertFalse(worker.isAlive)
        assertEquals(ProfileStickerAvatarMutationResult(parent, revision, null), results.single())
        assertEquals(1, f.receipts.snapshot().size)
        f.extras.reads.last().done(SettingsResult(f.data(43)))
        f.drain()
        assertEquals(revision, f.snapshot.completedRevision)
        assertFalse(f.snapshot.busy)
        f.parent.deactivate()
        val after = AtomicBoolean(true)
        thread(isDaemon = true) { after.set(write.current()) }.join(2_000)
        assertFalse(after.get())
    }

    @Test fun closingOldParentCannotOverwriteAReentrantNewerParentOpening() {
        val f = Fixture().apply { loaded(43) }
        val original = f.snapshot.token
        val picker = f.openPicker()
        val outcomes = mutableListOf<SettingsResult<ProfileStickerAvatarCandidatePage>>()
        f.parent.loadStickerAvatarDefault(original, picker, { true }) {
            outcomes += it
            f.parent.open(45)
        }
        f.parent.open(44)
        assertEquals(SettingsError.NOT_READY, outcomes.single().error)
        assertEquals(45L, f.snapshot.token.userId)
        assertEquals(listOf(43L, 45L), f.extras.reads.map { it.userId })
        assertEquals(listOf(picker), f.native.closes)
    }

    @Test fun busyMutationRejectsOldCatalogInputsButCloseAndLateWriteResultsStillWork() {
        val f = Fixture().apply { loaded(43) }
        val parent = f.snapshot.token
        val picker = f.openPicker()
        val selection = f.select(picker)
        val admitted = mutableListOf<Long?>()
        val results = mutableListOf<ProfileStickerAvatarMutationResult>()
        f.parent.saveStickerAvatar(parent, picker, selection, 91, background(), { true }, admitted::add, results::add)
        val revision = assertNotNull(admitted.single())
        val firstWrite = f.native.writes.single()
        f.native.preflight = firstWrite
        assertTrue(f.snapshot.busy)
        assertTrue(firstWrite.current(), "The active save lease must not read the busy flag")

        val opens = mutableListOf<SettingsResult<ProfileStickerAvatarPickerTicket>>()
        val defaults = mutableListOf<SettingsResult<ProfileStickerAvatarCandidatePage>>()
        val packs = mutableListOf<SettingsResult<ProfileStickerAvatarPackPage>>()
        val openedPack = mutableListOf<SettingsResult<ProfileStickerAvatarCandidatePage>>()
        val selections = mutableListOf<SettingsResult<ProfileStickerAvatarSelectionTicket>>()
        f.parent.openStickerAvatar(parent, { true }, opens::add)
        f.parent.loadStickerAvatarDefault(parent, picker, { true }, defaults::add)
        f.parent.loadStickerAvatarPacks(parent, picker, ProfileStickerAvatarPackKind.CUSTOM_EMOJI, { true }, packs::add)
        f.parent.openStickerAvatarPack(parent, picker, ProfileStickerAvatarPackTicket(), { true }, openedPack::add)
        f.parent.selectStickerAvatar(parent, picker, f.native.candidate, { true }, selections::add)
        assertEquals(SettingsError.NOT_READY, opens.single().error)
        assertEquals(SettingsError.NOT_READY, defaults.single().error)
        assertEquals(SettingsError.NOT_READY, packs.single().error)
        assertEquals(SettingsError.NOT_READY, openedPack.single().error)
        assertEquals(SettingsError.NOT_READY, selections.single().error)
        assertEquals(1, f.native.opens)
        assertEquals(1, f.native.selections)
        assertEquals(0, f.native.defaultReads)
        assertEquals(0, f.native.installedReads)
        assertEquals(0, f.native.packReads)
        assertEquals(0, f.native.preflightCancellations)
        assertTrue(firstWrite.current())
        assertTrue(results.isEmpty())
        assertTrue(f.receipts.snapshot().isEmpty())

        // Cleanup is still allowed and the fake native session reports its actual preflight result.
        f.parent.closeStickerAvatar(parent, picker)
        assertEquals(1, f.native.preflightCancellations)
        assertFalse(firstWrite.current())
        assertEquals(ProfileStickerAvatarMutationResult(parent, revision, SettingsError.NOT_READY), results.single())
        assertEquals(SettingsResult<Unit>(error = SettingsError.NOT_READY), f.receipts.find(
            checkNotNull(f.parent.mutationReceiptScope), parent, revision, SettingsProfileExtrasOperation.UPLOAD_STICKER_AVATAR))
        f.drain()
        f.extras.reads.last().done(SettingsResult(f.data(43)))
        f.drain()
        assertFalse(f.snapshot.busy)

        // A second save has passed the fake native write boundary: close cannot suppress its late Ok.
        val secondPicker = f.openPicker()
        val secondSelection = f.select(secondPicker)
        val secondAdmissions = mutableListOf<Long?>()
        val secondResults = mutableListOf<ProfileStickerAvatarMutationResult>()
        f.parent.saveStickerAvatar(parent, secondPicker, secondSelection, 91, background(), { true },
            secondAdmissions::add, secondResults::add)
        val secondRevision = assertNotNull(secondAdmissions.single())
        val secondWrite = f.native.writes.last()
        f.parent.closeStickerAvatar(parent, secondPicker)
        assertTrue(secondResults.isEmpty())
        secondWrite.done(SettingsResult(Unit))
        assertEquals(ProfileStickerAvatarMutationResult(parent, secondRevision, null), secondResults.single())
        assertEquals(SettingsResult(Unit), f.receipts.find(checkNotNull(f.parent.mutationReceiptScope), parent,
            secondRevision, SettingsProfileExtrasOperation.UPLOAD_STICKER_AVATAR))
    }

    private class Fixture {
        val native = Native()
        val extras = Extras(native)
        val receipts = SettingsProfileMutationReceipts()
        val queue = ConcurrentLinkedQueue<() -> Unit>()
        val throwPublish = AtomicBoolean(false)
        var snapshot = SettingsProfileExtrasSnapshot()
        val parent = SettingsProfileExtrasController(7, extras, { queue.add(it) }, {
            snapshot = it
            if (throwPublish.get()) error("synthetic snapshot observer failure")
        },
            profileMutationReceipts = receipts)

        fun data(userId: Long) = SettingsProfileExtrasData(userId, 91, null, SettingsCalendarDate(25, 10, 2026))
        fun loaded(userId: Long) {
            parent.open(userId)
            extras.reads.last().done(SettingsResult(data(userId)))
            drain()
        }
        fun openPicker(): ProfileStickerAvatarPickerTicket {
            val result = mutableListOf<SettingsResult<ProfileStickerAvatarPickerTicket>>()
            parent.openStickerAvatar(snapshot.token, { true }, result::add)
            return assertNotNull(result.single().value)
        }
        fun select(picker: ProfileStickerAvatarPickerTicket): ProfileStickerAvatarSelectionTicket {
            val result = mutableListOf<SettingsResult<ProfileStickerAvatarSelectionTicket>>()
            parent.selectStickerAvatar(snapshot.token, picker, native.candidate, { true }, result::add)
            return assertNotNull(result.single().value)
        }
        fun drain() { while (true) (queue.poll() ?: break).invoke() }
    }

    private class Extras(override val stickerAvatar: TdProfileStickerAvatarSession) : TdSettingsProfileExtrasSession {
        data class Read(val userId: Long, val done: (SettingsResult<SettingsProfileExtrasData>) -> Unit)
        val reads = mutableListOf<Read>()
        override fun load(userId: Long, isCurrent: () -> Boolean, result: (SettingsResult<SettingsProfileExtrasData>) -> Unit) {
            reads += Read(userId, result)
        }
        override fun setBirthdate(userId: Long, expected: SettingsBirthdate?, desired: SettingsBirthdate?,
            isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit,
        ) = result(SettingsResult(error = SettingsError.UNSUPPORTED))
        override fun uploadPhoto(userId: Long, expectedPhotoId: Long?, photo: SettingsPreparedProfilePhoto,
            isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit,
        ) = result(SettingsResult(error = SettingsError.UNSUPPORTED))
        override fun removePhoto(userId: Long, photoId: Long, isCurrent: () -> Boolean,
            result: (SettingsResult<Unit>) -> Unit,
        ) = result(SettingsResult(error = SettingsError.UNSUPPORTED))
    }

    private class Native : TdProfileStickerAvatarSession {
        data class Write(val current: () -> Boolean, val done: (SettingsResult<Unit>) -> Unit)
        val candidate = ProfileStickerAvatarCandidateTicket()
        val writes = mutableListOf<Write>()
        val closes = mutableListOf<ProfileStickerAvatarPickerTicket>()
        var opens = 0
        var selections = 0
        var defaultReads = 0
        var installedReads = 0
        var packReads = 0
        var preflight: Write? = null
        var preflightCancellations = 0
        /** Explicit final fake native reply; duplicate and late replies still reach production. */
        private fun nativeWriteResult(result: (SettingsResult<Unit>) -> Unit): (SettingsResult<Unit>) -> Unit = { response ->
            val receipt = when {
                response.value != null && response.error == null -> SettingsSpecialWriteReceipt(SettingsSpecialWriteOutcome.ACCEPTED)
                response.error != null -> SettingsSpecialWriteReceipt(SettingsSpecialWriteOutcome.REJECTED, response.error)
                else -> SettingsSpecialWriteReceipt(SettingsSpecialWriteOutcome.INDETERMINATE)
            }
            SettingsSpecialMutationContext.withReceipt(receipt) { result(response) }
        }
        private fun invalidatePreflight() {
            val current = preflight ?: return
            preflight = null
            preflightCancellations++
            current.done(SettingsResult(error = SettingsError.NOT_READY))
        }
        override fun open(userId: Long, active: () -> Boolean): ProfileStickerAvatarPickerTicket {
            opens++
            invalidatePreflight()
            return ProfileStickerAvatarPickerTicket()
        }
        override fun close(ticket: ProfileStickerAvatarPickerTicket) { closes += ticket; invalidatePreflight() }
        override fun retire() {}
        override fun loadDefault(ticket: ProfileStickerAvatarPickerTicket,
            result: (SettingsResult<ProfileStickerAvatarCandidatePage>) -> Unit,
        ) { defaultReads++; invalidatePreflight() /* Otherwise deliberately never calls back. */ }
        override fun loadInstalledPacks(ticket: ProfileStickerAvatarPickerTicket, kind: ProfileStickerAvatarPackKind,
            result: (SettingsResult<ProfileStickerAvatarPackPage>) -> Unit,
        ) { installedReads++; invalidatePreflight(); result(SettingsResult(error = SettingsError.UNSUPPORTED)) }
        override fun openPack(ticket: ProfileStickerAvatarPickerTicket, packTicket: ProfileStickerAvatarPackTicket,
            result: (SettingsResult<ProfileStickerAvatarCandidatePage>) -> Unit,
        ) { packReads++; invalidatePreflight(); result(SettingsResult(error = SettingsError.UNSUPPORTED)) }
        override fun select(ticket: ProfileStickerAvatarPickerTicket, candidateTicket: ProfileStickerAvatarCandidateTicket): ProfileStickerAvatarSelectionTicket? =
            run { selections++; invalidatePreflight(); if (candidateTicket === candidate) ProfileStickerAvatarSelectionTicket() else null }
        override fun save(ticket: ProfileStickerAvatarPickerTicket, selectionTicket: ProfileStickerAvatarSelectionTicket,
            expectedPhotoId: Long?, background: ProfileStickerAvatarBackground, active: () -> Boolean,
            result: (SettingsResult<Unit>) -> Unit,
        ) { writes += Write(active, nativeWriteResult(result)) }
    }

    private companion object { fun background() = checkNotNull(ProfileStickerAvatarBackground.fromColors(listOf(1, 2))) }
}
