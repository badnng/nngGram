package com.badnng.moe.tdlib

import com.badnng.moe.chat.MediaFile
import com.badnng.moe.settings.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.*

/** Fake-session/actor evidence only; no native files, account, UI or download acceptance. */
class SettingsProfilePhotoHistoryMediaControllerTest {
    @Test fun catalogConflictSignalRevokesReadyAndPendingLeasesBeforePublishingTheNewPage() {
        val f = Fixture(); f.controller.refreshHistory(f.token)
        val animation = SettingsProfilePhotoAnimation(ready.copy(uniqueId = ""), 640, 1.0)
        f.page(entries = listOf(f.entry().copy(hasAnimation = true, animation = animation)), total = 2)
        f.acquire(); f.acquire(f.request().copy(use = SettingsProfilePhotoHistoryMediaUse.EXPLICIT_ANIMATION))
        val (one, two) = f.session.files
        one.done(SettingsResult(ready)); f.drain(); f.results.clear()
        val epoch = f.snapshot.history.epoch
        f.controller.moreHistory(f.token)
        f.session.pages.last()(SettingsResult(SettingsProfilePhotoHistoryPage(f.identity,
            listOf(f.entry(12).copy(rawOffset = 1)), 1, 2, 1, metadataConflict = true))); f.drain()
        assertTrue(f.snapshot.history.refreshRequired); assertTrue(f.snapshot.history.epoch > epoch)
        assertEquals(1, one.closes); assertEquals(1, two.closes)
        assertFalse(one.current()); assertFalse(two.current())
        two.done(SettingsResult(ready)); f.drain(); assertTrue(f.results.isEmpty())
    }

    @Test fun readyNativeResultStrengthensUnknownEvidenceForLaterDuplicateComparison() {
        for (change in 0..2) {
            val f = Fixture(); f.controller.refreshHistory(f.token)
            val unknown = SettingsProfilePhotoAnimation(ready.copy(size = 0, uniqueId = ""), 640, 1.0)
            val first = f.entry().copy(hasAnimation = true, animation = unknown)
            f.page(entries = listOf(first), total = 2)
            f.acquire(f.request().copy(use = SettingsProfilePhotoHistoryMediaUse.EXPLICIT_ANIMATION))
            val native = f.session.files.single()
            native.done(SettingsResult(ready)); f.drain()
            assertNotNull(f.results.single().value); f.results.clear()
            f.controller.moreHistory(f.token)
            val laterFile = when (change) {
                0 -> ready
                1 -> ready.copy(uniqueId = "different-native-unique")
                else -> ready.copy(size = 101)
            }
            val later = first.copy(rawOffset = 1, authorization = 72, animation = unknown.copy(file = laterFile))
            f.page(entries = listOf(later), offset = 1, total = 2)
            assertEquals(change != 0, f.snapshot.history.refreshRequired)
            if (change == 0) { assertEquals(0, native.closes); assertTrue(native.current()) }
            else { assertEquals(1, native.closes); assertFalse(native.current()) }
        }
    }

    @Test fun nativeFileConflictSignalsRefreshButNetworkFailureKeepsTheExistingEpoch() {
        for (error in listOf(SettingsError.CONFLICT, SettingsError.REQUEST_FAILED)) {
            val f = Fixture(); f.acquire(); f.acquire()
            val (readyFile, pendingFile) = f.session.files
            readyFile.done(SettingsResult(ready)); f.drain(); f.results.clear()
            pendingFile.done(SettingsResult(error = error)); f.drain()
            assertEquals(error == SettingsError.CONFLICT, f.snapshot.history.refreshRequired)
            if (error == SettingsError.CONFLICT) assertEquals(1, readyFile.closes) else {
                assertEquals(0, readyFile.closes); assertEquals(SettingsError.REQUEST_FAILED, f.results.single().error)
            }
        }
    }

    @Test fun contradictoryDuplicateAcrossPagesClosesAllHistoryLeasesAndRequiresRefresh() {
        for (change in 0..6) {
            val f = Fixture(); f.controller.refreshHistory(f.token)
            val big = SettingsProfilePhotoAnimation(ready, 640, 1.0)
            val small = SettingsProfilePhotoAnimation(ready.copy(id = 9, uniqueId = "native-9"), 160, 0.25)
            val first = f.entry().copy(hasAnimation = true, animation = big, smallAnimation = small)
            f.page(entries = listOf(first), total = 2)
            val oldEpoch = f.snapshot.history.epoch
            val oldPageSource = f.session.pageSources.last()
            f.acquire(); f.acquire(f.request().copy(use = SettingsProfilePhotoHistoryMediaUse.EXPLICIT_ANIMATION))
            val pending = f.session.files.toList()
            f.controller.moreHistory(f.token)
            val duplicate = first.copy(rawOffset = 1, authorization = 72)
            val changed = when (change) {
                0 -> duplicate.copy(animation = big.copy(mainFrameTimestampSeconds = 1.25))
                1 -> duplicate.copy(animation = big.copy(file = big.file.copy(id = 77)))
                2 -> duplicate.copy(animation = big.copy(file = big.file.copy(uniqueId = "other")))
                3 -> duplicate.copy(animation = big.copy(length = 800))
                4 -> duplicate.copy(animation = big.copy(file = big.file.copy(size = 101)))
                5 -> duplicate.copy(smallAnimation = small.copy(mainFrameTimestampSeconds = 0.5))
                else -> duplicate.copy(variants = duplicate.variants.map { it.copy(width = it.width + 1) })
            }
            f.page(entries = listOf(changed), offset = 1, total = 2)
            assertTrue(f.snapshot.history.refreshRequired, "Stable metadata $change")
            assertTrue(f.snapshot.history.epoch > oldEpoch)
            assertTrue(pending.all { it.closes == 1 && !it.current() })
            assertFalse(oldPageSource())
            pending.forEach { it.done(SettingsResult(ready)) }; f.drain()
            assertTrue(f.results.isEmpty())
        }
    }

    @Test fun compatibleDuplicateRetainsOwnerButLaterKnownSizeContradictionRevokesIt() {
        val f = Fixture(); f.controller.refreshHistory(f.token)
        val animation = SettingsProfilePhotoAnimation(ready.copy(size = 0), 640, 1.0)
        val first = f.entry().copy(hasAnimation = true, animation = animation)
        f.page(entries = listOf(first), total = 3)
        val epoch = f.snapshot.history.epoch
        f.acquire(f.request().copy(use = SettingsProfilePhotoHistoryMediaUse.EXPLICIT_ANIMATION))
        val pending = f.session.files.single()
        f.controller.moreHistory(f.token)
        val later = first.copy(rawOffset = 1, authorization = 72,
            animation = animation.copy(file = animation.file.copy(size = 100, expectedSize = 500, localPath = "moved")))
        f.page(entries = listOf(later), offset = 1, total = 3)
        assertFalse(f.snapshot.history.refreshRequired); assertEquals(epoch, f.snapshot.history.epoch)
        assertEquals(1, f.snapshot.history.entries.size); assertTrue(pending.current()); assertEquals(0, pending.closes)
        f.controller.moreHistory(f.token)
        val contradictory = later.copy(rawOffset = 2, authorization = 73,
            animation = animation.copy(file = animation.file.copy(size = 200)))
        f.page(entries = listOf(contradictory), offset = 2, total = 3)
        assertTrue(f.snapshot.history.refreshRequired); assertEquals(1, pending.closes); assertFalse(pending.current())
    }

    @Test fun samePageDuplicateMustHaveCompatibleStableMetadataEvenAtDifferentOffsets() {
        for (conflict in listOf(false, true)) {
            val f = Fixture(); f.controller.refreshHistory(f.token)
            val animation = SettingsProfilePhotoAnimation(ready, 640, 1.0)
            val first = f.entry().copy(hasAnimation = true, animation = animation)
            val second = first.copy(rawOffset = 1, animation = if (conflict) animation.copy(mainFrameTimestampSeconds = 1.5) else animation)
            f.page(entries = listOf(first, second), total = 2, rawCount = 2)
            assertEquals(conflict, f.snapshot.history.refreshRequired)
            if (!conflict) assertEquals(listOf(11L), f.snapshot.history.entries.map { it.photoId })
        }
    }

    @Test fun ordinaryNextPageFailureKeepsExistingEpochAndReadyFileLease() {
        val f = Fixture()
        f.acquire(); val pending = f.session.files.single()
        pending.done(SettingsResult(ready)); f.drain()
        val epoch = f.snapshot.history.epoch
        f.controller.moreHistory(f.token)
        f.session.pages.last()(SettingsResult(error = SettingsError.REQUEST_FAILED)); f.drain()
        assertFalse(f.snapshot.history.refreshRequired); assertEquals(epoch, f.snapshot.history.epoch)
        assertEquals(SettingsError.REQUEST_FAILED, f.snapshot.history.error)
        assertEquals(0, pending.closes); assertTrue(pending.current())
    }

    @Test fun unknownAnimationUniqueAcceptsOnlyTheReadyFileReturnedByTheAuthorizedSession() {
        for (actualUnique in listOf("native-resolved", "")) {
            val f = Fixture(); f.controller.refreshHistory(f.token)
            val unknown = SettingsProfilePhotoAnimation(ready.copy(uniqueId = ""), 640, 1.0)
            f.page(entries = listOf(f.entry().copy(hasAnimation = true, animation = unknown)))
            f.acquire(f.request().copy(use = SettingsProfilePhotoHistoryMediaUse.EXPLICIT_ANIMATION))
            val pending = f.session.files.single()
            pending.done(SettingsResult(ready.copy(uniqueId = actualUnique))); f.drain()
            if (actualUnique.isEmpty()) assertEquals(SettingsError.REQUEST_FAILED, f.results.single().error)
            else assertEquals(actualUnique, f.results.single().value?.uniqueId)
        }
    }

    @Test fun selectedAnimationUsesExistingEpochTicketAndRedactsEvenAnUntrustedSessionSnapshot() {
        val f = Fixture(); f.controller.refreshHistory(f.token)
        val native = SettingsProfilePhotoAnimation(ready.copy(downloading = true, downloadedSize = 95,
            uploading = true, uploadedSize = 80), 640, 2.25)
        f.page(entries = listOf(f.entry().copy(hasAnimation = true, animation = native)))
        val published = assertNotNull(f.snapshot.history.entries.single().animation)
        assertNull(published.file.localPath); assertFalse(published.file.downloaded); assertFalse(published.file.downloading)
        assertEquals(0, published.file.downloadedSize); assertFalse(published.file.uploading); assertEquals(0, published.file.uploadedSize)
        val selection = AtomicBoolean(true)
        f.acquire(f.request().copy(use = SettingsProfilePhotoHistoryMediaUse.EXPLICIT_ANIMATION), selection::get)
        val call = f.session.files.single()
        assertEquals(SettingsProfilePhotoHistoryMediaUse.EXPLICIT_ANIMATION, call.request.use)
        assertEquals(71, call.request.authorization); assertEquals(11, call.request.photoId); assertEquals(0, call.request.rawOffset)
        call.done(SettingsResult(ready)); f.drain()
        assertEquals(ready, f.results.single().value)
        selection.set(false); assertFalse(call.current())
    }

    @Test fun animationSelectionLeaseAndEpochRevokeLateNativeCompletion() {
        for (stop in 0..2) {
            val f = Fixture(); f.controller.refreshHistory(f.token)
            f.page(entries = listOf(f.entry().copy(hasAnimation = true, animation = SettingsProfilePhotoAnimation(ready, 640, 1.0))))
            val selection = AtomicBoolean(true)
            f.acquire(f.request().copy(use = SettingsProfilePhotoHistoryMediaUse.EXPLICIT_ANIMATION), selection::get)
            val pending = f.session.files.single()
            when (stop) {
                0 -> selection.set(false)
                1 -> f.controller.refreshHistory(f.token)
                else -> f.controller.closeHistory(f.token)
            }
            pending.done(SettingsResult(ready)); f.drain()
            assertTrue(f.results.isEmpty()); assertFalse(pending.current()); assertEquals(1, pending.closes)
        }
    }

    @Test fun animationActorRejectsWrongReadyIdentitySizeAndMissingMetadata() {
        val missing = Fixture()
        missing.acquire(missing.request().copy(use = SettingsProfilePhotoHistoryMediaUse.EXPLICIT_ANIMATION))
        assertEquals(SettingsError.UNSUPPORTED, missing.results.single().error)
        assertTrue(missing.session.files.isEmpty())
        for (invalid in listOf(ready.copy(id = 99), ready.copy(uniqueId = "changed"), ready.copy(size = 101),
            ready.copy(size = 24L * 1024 * 1024 + 1), ready.copy(localPath = null))) {
            val f = Fixture(); f.controller.refreshHistory(f.token)
            f.page(entries = listOf(f.entry().copy(hasAnimation = true, animation = SettingsProfilePhotoAnimation(ready, 640, 1.0))))
            f.acquire(f.request().copy(use = SettingsProfilePhotoHistoryMediaUse.EXPLICIT_ANIMATION))
            val call = f.session.files.single(); call.done(SettingsResult(invalid)); f.drain()
            assertEquals(SettingsError.REQUEST_FAILED, f.results.single().error); assertEquals(1, call.closes)
        }
    }

    @Test fun animationActorRejectsMalformedMetadataFromANonNativeSession() {
        val metadata = SettingsProfilePhotoAnimation(ready, 640, 1.0)
        val malformed = listOf(metadata.copy(length = 0), metadata.copy(length = 1281),
            metadata.copy(mainFrameTimestampSeconds = Double.NaN), metadata.copy(mainFrameTimestampSeconds = -1.0),
            metadata.copy(file = ready.copy(id = 0)),
            metadata.copy(file = ready.copy(size = 24L * 1024 * 1024 + 1)))
        malformed.forEach { invalid ->
            val f = Fixture(); f.controller.refreshHistory(f.token)
            f.page(entries = listOf(f.entry().copy(hasAnimation = true, animation = invalid)))
            f.acquire(f.request().copy(use = SettingsProfilePhotoHistoryMediaUse.EXPLICIT_ANIMATION))
            assertEquals(SettingsError.INVALID_VALUE, f.results.single().error)
            assertTrue(f.session.files.isEmpty())
        }
    }

    @Test fun admissionRequiresCurrentParentEpochMembershipAndNativeTicket() {
        val f = Fixture()
        val request = f.request()
        listOf(request.copy(parent = request.parent.copy(owner = request.parent.owner + 1)),
            request.copy(epoch = request.epoch - 1), request.copy(photoId = 999), request.copy(photoId = 0))
            .forEach { f.acquire(it) }
        assertEquals(4, f.results.size)
        assertTrue(f.results.all { it.error == SettingsError.CONFLICT })
        assertTrue(f.session.files.isEmpty())
        f.controller.refreshHistory(f.token)
        f.page(entries = listOf(f.entry(11, ticket = 0)))
        f.acquire()
        assertEquals(SettingsError.CONFLICT, f.results.last().error)
        assertTrue(f.session.files.isEmpty())
    }

    @Test fun authorizedRequestComesOnlyFromCurrentEntry() {
        val f = Fixture()
        f.acquire()
        assertEquals(SettingsProfilePhotoHistoryAuthorizedFileRequest(43, 71, 11, 0,
            SettingsProfilePhotoHistoryMediaUse.VISIBLE_CELL, 130, 130), f.session.files.single().request)
        f.acquire(f.request().copy(use = SettingsProfilePhotoHistoryMediaUse.EXPLICIT_PREVIEW, targetWidthPx = 2048))
        assertEquals(SettingsProfilePhotoHistoryMediaUse.EXPLICIT_PREVIEW, f.session.files.last().request.use)
        assertEquals(2048, f.session.files.last().request.targetWidthPx)
    }

    @Test fun dimensionsAreBoundedWithoutRejectingHighDensityCells() {
        val f = Fixture()
        for ((width, height) in listOf(0 to 100, -1 to 100, 4097 to 100, 100 to Int.MAX_VALUE)) {
            f.acquire(f.request().copy(targetWidthPx = width, targetHeightPx = height))
            assertEquals(SettingsError.INVALID_VALUE, f.results.last().error)
        }
        assertTrue(f.session.files.isEmpty())
        f.acquire(f.request().copy(targetWidthPx = 1024, targetHeightPx = 1024))
        assertEquals(1, f.session.files.size)
    }

    @Test fun nativeCompletionWaitsForActorAndPublishesOnceWithoutAddingPathsToSnapshot() {
        val f = Fixture()
        f.acquire()
        val native = f.session.files.single()
        native.done(SettingsResult(ready))
        native.done(SettingsResult(ready.copy(localPath = "ignored-second-path")))
        assertTrue(f.results.isEmpty())
        f.drain()
        assertEquals(listOf(ready), f.results.map { it.value })
        assertNull(f.snapshot.history.entries.single().variants.single().file.localPath)
        assertEquals(0, native.closes, "Ready ownership lasts until UI close.")
    }

    @Test fun synchronousCallbackAndEarlyCloseBeforeNativeHandleAssignmentReleaseExactlyOnce() {
        val f = Fixture()
        f.session.onAcquire = { pending ->
            pending.done(SettingsResult(ready))
            f.controller.closeHistory(f.token)
        }
        val lease = f.acquire()
        val native = f.session.files.single()
        assertEquals(1, native.closes)
        lease.close(); lease.close()
        f.drain()
        assertEquals(1, native.closes)
        assertTrue(f.results.isEmpty())
        assertFalse(native.current())
    }

    @Test fun closingOneCellDoesNotReleaseAnotherOwnerOfSamePhoto() {
        val f = Fixture()
        val first = f.acquire()
        val second = f.acquire()
        val (one, two) = f.session.files
        first.close(); first.close()
        assertFalse(one.current())
        assertTrue(two.current())
        assertEquals(1, one.closes)
        assertEquals(0, two.closes)
        one.done(SettingsResult(ready)); two.done(SettingsResult(ready))
        f.drain()
        assertEquals(listOf(ready), f.results.map { it.value })
        second.close()
        assertEquals(1, two.closes)
    }

    @Test fun parentHistoryRefreshReopenAndDeactivationSynchronouslyCloseAllChildren() {
        val operations: List<(Fixture) -> Unit> = listOf(
            { it.controller.closeHistory(it.token) }, { it.controller.refreshHistory(it.token) },
            { it.controller.openHistory(it.token) { true } }, { it.controller.close(it.token) },
            { it.controller.deactivate() },
        )
        operations.forEach { stop ->
            val f = Fixture()
            f.acquire(); f.acquire()
            f.session.files.first().done(SettingsResult(ready)); f.drain()
            f.results.clear()
            stop(f)
            assertTrue(f.session.files.all { !it.current() && it.closes == 1 })
            f.session.files.last().done(SettingsResult(ready)); f.drain()
            assertTrue(f.results.isEmpty())
        }
    }

    @Test fun roleConflictClosesChildrenAndOldEpochCannotInstallIntoSamePhotoOnNewPage() {
        val f = Fixture()
        val oldRequest = f.request()
        f.acquire()
        f.controller.moreHistory(f.token)
        f.page(identity = f.identity.copy(publicPhotoId = 11), offset = 1)
        assertTrue(f.snapshot.history.refreshRequired)
        assertEquals(1, f.session.files.single().closes)
        assertFalse(f.session.files.single().current())
        f.session.files.single().done(SettingsResult(ready)); f.drain()
        assertTrue(f.results.isEmpty())
        f.controller.refreshHistory(f.token)
        f.page(entries = listOf(f.entry(11, ticket = 72)))
        f.acquire(oldRequest)
        assertEquals(SettingsError.CONFLICT, f.results.last().error)
        assertEquals(1, f.session.files.size)
    }

    @Test fun parentCurrentPhotoConflictClosesReadyAndPendingLeases() {
        val f = Fixture()
        f.acquire(); f.acquire()
        f.session.files.first().done(SettingsResult(ready)); f.drain()
        f.controller.refresh(f.token)
        f.session.reads.last()(SettingsResult(f.data.copy(photoId = 92))); f.drain()
        assertTrue(f.snapshot.history.refreshRequired)
        assertTrue(f.session.files.all { it.closes == 1 && !it.current() })
    }

    @Test fun moreLoadingRetainsExistingVisibleEntryAdmissionAndCompletion() {
        val f = Fixture()
        f.acquire()
        f.controller.moreHistory(f.token)
        assertTrue(f.snapshot.history.loading)
        f.acquire()
        assertEquals(2, f.session.files.size)
        f.session.files.forEach { assertTrue(it.current()); it.done(SettingsResult(ready)) }
        f.drain()
        assertEquals(listOf(ready, ready), f.results.map { it.value })
    }

    @Test fun capturedCurrentPredicatesAndNativeCallbackCanRunOnWorkerBeforeActorPublication() {
        val f = Fixture()
        val visible = AtomicBoolean(true)
        f.acquire(active = visible::get)
        val native = f.session.files.single()
        var workerCurrent = false
        thread {
            workerCurrent = native.current()
            native.done(SettingsResult(ready))
        }.join()
        assertTrue(workerCurrent)
        assertTrue(f.results.isEmpty())
        visible.set(false)
        f.drain()
        assertTrue(f.results.isEmpty())
        assertEquals(1, native.closes)
    }

    @Test fun closedParentAndSelectorPredicatesPreventWorkerPublicationBeforeActorCleanup() {
        for (parent in listOf(true, false)) {
            val f = Fixture()
            f.acquire()
            if (parent) f.parentVisible.set(false) else f.selectorVisible.set(false)
            val native = f.session.files.single()
            assertFalse(native.current())
            native.done(SettingsResult(ready)); f.drain()
            assertTrue(f.results.isEmpty())
            assertEquals(1, native.closes)
        }
    }

    @Test fun invalidReadyFileAndDuplicateFailureCloseOnceAndPermitExplicitRetry() {
        for (invalid in listOf(ready.copy(id = 0), ready.copy(uniqueId = ""),
            ready.copy(localPath = null), ready.copy(downloaded = false))) {
            val f = Fixture()
            f.acquire()
            val native = f.session.files.single()
            native.done(SettingsResult(invalid)); native.done(SettingsResult(ready)); f.drain()
            assertEquals(SettingsError.REQUEST_FAILED, f.results.single().error)
            assertEquals(1, native.closes)
            f.acquire()
            assertEquals(2, f.session.files.size)
        }
    }

    @Test fun registryBoundsAllLiveLeasesIncludingReadyAndReclaimsClosedSlots() {
        val f = Fixture()
        val leases = (1..64).map {
            f.acquire().also { f.session.files.last().done(SettingsResult(ready)); f.drain() }
        }
        f.results.clear()
        f.acquire()
        assertEquals(SettingsError.RATE_LIMITED, f.results.single().error)
        assertEquals(64, f.session.files.size)
        leases.first().close()
        f.acquire()
        assertEquals(65, f.session.files.size)
        f.controller.closeHistory(f.token)
        assertTrue(f.session.files.all { it.closes == 1 })
    }

    @Test fun refreshingAnEpochSynchronouslyRevokesItsNativePageTicketSources() {
        val f = Fixture()
        val first = f.session.pageSources.single()
        assertTrue(first())
        f.controller.moreHistory(f.token)
        val more = f.session.pageSources.last()
        f.page(entries = listOf(f.entry(12).copy(rawOffset = 1)), offset = 1)
        assertTrue(first())
        assertTrue(more())
        f.controller.refreshHistory(f.token)
        assertFalse(first(), "A cached native ticket must not survive its original history epoch.")
        assertFalse(more())
        val fresh = f.session.pageSources.last()
        assertTrue(fresh())
        f.controller.closeHistory(f.token)
        assertFalse(fresh())
    }

    private class Fixture {
        val parentVisible = AtomicBoolean(true)
        val selectorVisible = AtomicBoolean(true)
        val data = SettingsProfileExtrasData(43, 91, null, SettingsCalendarDate(26, 10, 2026))
        val identity = SettingsProfilePhotoIdentity(43, 91)
        val session = Session()
        private val queue = ArrayDeque<() -> Unit>()
        var snapshot = SettingsProfileExtrasSnapshot()
        val results = mutableListOf<SettingsResult<MediaFile>>()
        val controller = SettingsProfileExtrasController(7, session, { queue.addLast(it) }, { snapshot = it })
        val token get() = snapshot.token
        init {
            controller.open(43, parentVisible::get)
            session.reads.last()(SettingsResult(data)); drain()
            controller.openHistory(token, selectorVisible::get)
            page()
        }
        fun drain() { while (queue.isNotEmpty()) queue.removeFirst()() }
        fun entry(id: Long = 11, ticket: Long = 71) = SettingsProfilePhotoHistoryEntry(id, 0, 0,
            listOf(SettingsProfilePhotoVariant(ready, 130, 130)), authorization = ticket)
        fun page(entries: List<SettingsProfilePhotoHistoryEntry> = listOf(entry()),
            identity: SettingsProfilePhotoIdentity = this.identity, offset: Int = 0, total: Int? = null, rawCount: Int = 1,
        ) {
            session.pages.last()(SettingsResult(SettingsProfilePhotoHistoryPage(identity, entries, rawCount, total, offset)))
            drain()
        }
        fun request() = SettingsProfilePhotoHistoryMediaRequest(token, snapshot.history.epoch, 11,
            SettingsProfilePhotoHistoryMediaUse.VISIBLE_CELL, 130, 130)
        fun acquire(request: SettingsProfilePhotoHistoryMediaRequest = request(), active: () -> Boolean = { true }) =
            controller.acquireHistoryFile(request, active) { results += it }
    }

    private class Session : TdSettingsProfileExtrasSession {
        class File(val request: SettingsProfilePhotoHistoryAuthorizedFileRequest, val current: () -> Boolean,
            val done: (SettingsResult<MediaFile>) -> Unit,
        ) : SettingsProfilePhotoHistoryFileLease {
            var closes = 0
            override fun close() { closes++ }
        }
        val reads = mutableListOf<(SettingsResult<SettingsProfileExtrasData>) -> Unit>()
        val pages = mutableListOf<(SettingsResult<SettingsProfilePhotoHistoryPage>) -> Unit>()
        val pageSources = mutableListOf<() -> Boolean>()
        val files = mutableListOf<File>()
        var onAcquire: (File) -> Unit = {}
        override fun acquireHistoryFile(request: SettingsProfilePhotoHistoryAuthorizedFileRequest, active: () -> Boolean,
            result: (SettingsResult<MediaFile>) -> Unit,
        ): SettingsProfilePhotoHistoryFileLease = File(request, active, result).also { files += it; onAcquire(it) }
        override fun load(userId: Long, isCurrent: () -> Boolean, result: (SettingsResult<SettingsProfileExtrasData>) -> Unit) { reads += result }
        override fun photoHistoryPage(userId: Long, offset: Int, limit: Int, isCurrent: () -> Boolean,
            result: (SettingsResult<SettingsProfilePhotoHistoryPage>) -> Unit,
        ) { pages += result; pageSources += isCurrent }
        override fun setBirthdate(userId: Long, expected: SettingsBirthdate?, desired: SettingsBirthdate?, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) {}
        override fun uploadPhoto(userId: Long, expectedPhotoId: Long?, photo: SettingsPreparedProfilePhoto, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) {}
        override fun removePhoto(userId: Long, photoId: Long, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) {}
    }

    private companion object {
        val ready = MediaFile(8, size = 100, localPath = "synthetic-ready.jpg", downloaded = true, uniqueId = "native-8")
    }
}
