package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

/** Synthetic native responses + the actual TdDownloadBroker; no account, paths or codec are used. */
class OfficialTdProfileStickerAvatarFilesTest {
    @Test fun visibleThumbnailAndSelectedFullHaveSeparateNativeFilesAndSourceRechecks() {
        val f = Fixture()
        val thumb = f.thumbnail()
        f.clock.runCurrent()
        val thumbnail = assertNotNull(thumb.results.single().value)
        assertEquals(ProfileStickerAvatarMediaRole.THUMBNAIL, thumbnail.role)
        assertEquals(MediaFormat.WEBP, thumbnail.format)
        assertTrue(thumbnail.borrow { assertContentEquals(f.native.bytes.getValue(71), it) })
        thumbnail.close()
        f.sticker.sticker!!.local!!.path = "changed-local-path"
        f.sticker.sticker!!.local!!.downloadedSize = 17
        val full = f.full()
        f.clock.runCurrent()
        val artwork = assertNotNull(full.results.single().value)
        assertEquals(ProfileStickerAvatarMediaRole.FULL, artwork.role)
        assertTrue(artwork.borrow { assertContentEquals(f.native.bytes.getValue(70), it) })
        assertEquals(listOf(71, 70), f.native.reads.map { it.fileId }.distinct())
        assertTrue(f.transport.calls.count { it.command is TdApi.GetDefaultProfilePhotoCustomEmojiStickers } >= 5)
        assertTrue(f.transport.calls.none { it.command is TdApi.GetCustomEmojiStickers })
        artwork.close()
        assertEquals(0L, f.budget.used())
    }

    @Test fun forgedForeignAndReselectedTicketsCannotGrantMedia() {
        val f = Fixture()
        val foreign = Fixture()
        val rejected = mutableListOf<SettingsResult<ProfileStickerAvatarEncoded>>()
        f.files.thumbnail(f.picker, ProfileStickerAvatarCandidateTicket(), { true }, rejected::add)
        f.files.thumbnail(f.picker, foreign.candidate.ticket, { true }, rejected::add)
        assertEquals(2, rejected.size)
        assertTrue(rejected.all { it.error != null })
        val forged = ProfileStickerAvatarMediaGrant(ProfileStickerAvatarMediaRole.FULL,
            AtomicBoolean(true), AtomicBoolean(true), AtomicBoolean(true))
        val forgedResult = mutableListOf<SettingsResult<ProfileStickerAvatarOwnedMedia>>()
        f.api.resolveMedia(forged, { true }, forgedResult::add)
        f.clock.runCurrent()
        assertEquals(SettingsError.NOT_READY, forgedResult.single().error)
        val first = assertNotNull(f.api.fullMedia(f.picker, f.selection))
        f.selection = assertNotNull(f.api.select(f.picker, f.candidate.ticket))
        assertFalse(first.atomicCurrent())
        val stale = mutableListOf<SettingsResult<ProfileStickerAvatarOwnedMedia>>()
        f.api.resolveMedia(first, { true }, stale::add)
        f.clock.runCurrent()
        assertEquals(SettingsError.NOT_READY, stale.single().error)
        assertTrue(f.native.reads.isEmpty())
        val wrongSelf = Fixture()
        wrongSelf.transport.selfId = 44
        val wrong = wrongSelf.thumbnail()
        wrongSelf.clock.runCurrent()
        assertEquals(SettingsError.NOT_READY, wrong.results.single().error)
        assertTrue(wrongSelf.native.reads.isEmpty())
    }

    @Test fun duplicatedDefaultIdentityCannotAuthorizeEitherMediaRole() {
        val first = sticker(customId = -700, fullId = 70, thumbId = 71)
        val second = sticker(customId = -700, fullId = 80, thumbId = 81)
        val f = Fixture(initial = arrayOf(first, second))
        assertTrue(f.page.candidates.isEmpty(), "A duplicate identity cannot receive a selection ticket.")
        assertTrue(f.page.limited, "The conflicting native input is explicitly reported.")
        assertTrue(f.native.reads.isEmpty())
        assertTrue(f.native.downloads.isEmpty())
        val becameDuplicate = Fixture()
        becameDuplicate.transport.stickers = arrayOf(becameDuplicate.sticker,
            sticker(customId = -700, fullId = 80, thumbId = 81))
        val priorRevision = becameDuplicate.picker.catalogRevision.value
        val stale = becameDuplicate.full()
        becameDuplicate.clock.runCurrent()
        assertEquals(SettingsError.NOT_READY, stale.results.single().error)
        assertEquals(priorRevision + 1, becameDuplicate.picker.catalogRevision.value)
        assertNull(becameDuplicate.api.select(becameDuplicate.picker, becameDuplicate.candidate.ticket))
        assertTrue(becameDuplicate.native.reads.isEmpty())
    }

    @Test fun thumbnailCannotAliasTheFullFileOrFallBackToFull() {
        val f = Fixture(initial = arrayOf(sticker(fullId = 70, thumbId = 70)))
        val thumb = f.thumbnail()
        assertNotNull(thumb.results.single().error)
        assertTrue(f.native.reads.isEmpty())
        val full = f.full()
        f.clock.runCurrent()
        assertNotNull(full.results.single().value).close()
        assertEquals(listOf(70), f.native.reads.map { it.fileId }.distinct())

        val sameRemote = Fixture()
        sameRemote.sticker.thumbnail!!.file!!.remote!!.uniqueId = sameRemote.sticker.sticker!!.remote!!.uniqueId
        sameRemote.refreshCatalog()
        assertNotNull(sameRemote.thumbnail().results.single().error)
        assertTrue(sameRemote.native.reads.isEmpty())
    }

    @Test fun revalidationRejectsChangedFileFormatDimensionsAndPublicationSwap() {
        val mutations: List<(TdApi.Sticker) -> Unit> = listOf(
            { it.sticker!!.remote!!.uniqueId = "different" },
            { it.format = TdApi.StickerFormatTgs() },
            { it.width++ },
            { it.thumbnail!!.width++ },
            { it.thumbnail!!.file!!.id++ },
        )
        mutations.forEachIndexed { index, mutation ->
            val f = Fixture()
            mutation(f.sticker)
            val read = if (index >= 3) f.thumbnail() else f.full()
            f.clock.runCurrent()
            assertNotNull(read.results.single().error)
            assertTrue(f.native.reads.isEmpty())
        }
        val f = Fixture()
        f.native.onRead = { f.sticker.sticker!!.remote!!.uniqueId = "replaced-after-read" }
        val revision = f.picker.catalogRevision.value
        val read = f.full()
        f.clock.runCurrent()
        assertEquals(SettingsError.CONFLICT, read.results.single().error)
        assertNull(read.results.single().value)
        assertEquals(revision + 1, f.picker.catalogRevision.value)
        assertNull(f.api.fullMedia(f.picker, f.selection))
        assertNull(f.api.select(f.picker, f.candidate.ticket))
        assertEquals(0L, f.budget.used())
        assertEquals(0, f.readers.used())
    }

    @Test fun queuedSourceConflictCannotSurviveRefreshCloseOrCallerInactivity() {
        for (transition in 0..2) {
            val f = Fixture()
            var active = true
            val output = mutableListOf<SettingsResult<ProfileStickerAvatarEncoded>>()
            val revision = f.picker.catalogRevision.value
            f.native.onRead = { f.sticker.sticker!!.remote!!.uniqueId = "replaced-after-read" }
            f.files.full(f.picker, f.selection, { active }, output::add)
            var steps = 0
            while (f.picker.catalogRevision.value == revision && steps++ < 200) assertTrue(f.clock.runOne())
            assertEquals(revision + 1, f.picker.catalogRevision.value)
            assertTrue(output.isEmpty(), "Conflict consumer must still be queued before transition=$transition")
            when (transition) {
                0 -> f.refreshCatalog()
                1 -> f.api.close(f.picker)
                else -> active = false
            }
            f.clock.runCurrent()
            assertEquals(SettingsError.NOT_READY, output.single().error, "transition=$transition")
            assertNull(output.single().value)
            assertEquals(0L, f.budget.used())
            assertEquals(0, f.readers.used())
            if (transition == 0) assertNotNull(f.api.fullMedia(f.picker, f.selection))
        }
    }

    @Test fun unknownSizeUsesBoundedWindowAndRejectsOversizedCompletion() {
        for (size in listOf(32, 512 * 1024 + 1)) {
            val f = Fixture()
            f.sticker.thumbnail!!.file!!.apply { this.size = 0; expectedSize = 0; local!!.isDownloadingCompleted = false }
            f.refreshCatalog()
            val file = mapMediaFile(f.sticker.thumbnail!!.file!!)
            f.native.files[file.id] = file
            val read = f.thumbnail()
            f.clock.runCurrent()
            assertTrue(f.native.downloads.isNotEmpty())
            assertTrue(f.native.downloads.all { it.offset + it.limit <= 512L * 1024 })
            val content = ByteArray(size) { it.toByte() }
            val ready = file.copy(size = size.toLong(), expectedSize = size.toLong(), downloaded = true,
                downloadedSize = size.toLong())
            f.native.files[file.id] = ready
            f.native.bytes[file.id] = content
            f.broker.observe(ready)
            f.clock.runCurrent()
            if (size <= 512 * 1024) assertNotNull(read.results.single().value).close()
            else {
                assertNotNull(read.results.single().error)
                assertTrue(f.native.reads.isEmpty())
            }
        }
    }

    @Test fun cancellationRetainsPendingPhysicalReadBudgetUntilItsLateCallbackDrains() {
        val f = Fixture()
        f.native.holdReads = true
        val read = f.full()
        f.clock.runCurrent()
        assertEquals(1, f.native.pendingReads.size)
        val reserved = f.budget.used()
        assertTrue(reserved > 65_536)
        read.lease.close()
        assertEquals(SettingsError.NOT_READY, read.results.single().error)
        f.clock.runCurrent()
        assertEquals(reserved, f.budget.used())
        assertEquals(1, f.readers.used())
        f.native.completeNextRead()
        f.clock.runCurrent()
        assertEquals(0L, f.budget.used())
        assertEquals(0, f.readers.used())
        assertEquals(1, read.results.size)

        // The owned broker token survives close and drains on the actual late prefix callback.
        val orphan = Fixture()
        orphan.native.holdPrefixes = true
        val pending = orphan.full()
        orphan.clock.runCurrent()
        assertEquals(1, orphan.native.pendingPrefixes.size)
        pending.lease.close()
        orphan.broker.close()
        orphan.clock.runCurrent()
        assertTrue(orphan.budget.used() > 0)
        assertEquals(1, orphan.readers.used())
        orphan.native.completeNextPrefix()
        orphan.clock.runCurrent()
        assertEquals(0L, orphan.budget.used())
        assertEquals(0, orphan.readers.used())
    }

    @Test fun workerRetainKeepsTheSharedPeakReservationUntilActualRelease() {
        val reservation = 64L + 65_536
        val f = Fixture(budget = ProfileStickerAvatarEncodedBudget(reservation * 2 - 1 + 65_536))
        val first = f.full()
        f.clock.runCurrent()
        val encoded = assertNotNull(first.results.single().value)
        val worker = assertNotNull(encoded.retain())
        encoded.close()
        assertEquals(reservation, f.budget.used())
        val denied = f.full()
        f.clock.runCurrent()
        assertEquals(SettingsError.RATE_LIMITED, denied.results.single().error)
        assertTrue(worker.borrow { assertEquals(64, it.size) })
        val copiedBytes = assertNotNull(worker.reserveCopy(64))
        assertEquals(reservation + 64, f.budget.used())
        worker.close()
        assertEquals(64L, f.budget.used())
        copiedBytes.close()
        assertEquals(0L, f.budget.used())
        val retried = f.full()
        f.clock.runCurrent()
        assertNotNull(retried.results.single().value).close()
        val synchronousClose = Fixture()
        var called = false
        synchronousClose.files.full(synchronousClose.picker, synchronousClose.selection, { true }) {
            val value = assertNotNull(it.value)
            value.close()
            assertTrue(synchronousClose.budget.used() > 0, "The publication stack still owns the encoded data.")
            called = true
        }
        synchronousClose.clock.runCurrent()
        assertTrue(called)
        assertEquals(0L, synchronousClose.budget.used())
    }

    @Test fun otherOwnerSurvivesCancellationOnTheRealSharedBroker() {
        val f = Fixture()
        val source = mapMediaFile(f.sticker.sticker!!).copy(downloaded = false, downloadedSize = 0)
        f.sticker.sticker!!.local!!.isDownloadingCompleted = false
        f.native.files[source.id] = source
        f.refreshCatalog()
        val other = MediaDownloadOwner(ChatToken(2, 4, 9), 35, MediaDownloadPurpose.USER, 18)
        val otherResults = mutableListOf<ChatResult<MediaFile>>()
        f.broker.full(other, source, otherResults::add)
        val beforeSourceRead = f.transport.calls.count { it.command is TdApi.GetDefaultProfilePhotoCustomEmojiStickers }
        val read = f.full()
        f.clock.runCurrent() // confirm this owner has performed source validation before closing
        assertTrue(f.transport.calls.count { it.command is TdApi.GetDefaultProfilePhotoCustomEmojiStickers } > beforeSourceRead)
        assertTrue(read.results.isEmpty())
        read.lease.close()
        assertTrue(f.broker.manages(source.id))
        assertTrue(f.native.cancelled.isEmpty())
        val ready = source.copy(downloaded = true, downloadedSize = source.size)
        f.native.files[source.id] = ready
        f.broker.observe(ready)
        f.clock.runCurrent()
        assertTrue(otherResults.single().value?.downloaded == true)
        assertEquals(SettingsError.NOT_READY, read.results.single().error)
    }

    @Test fun cancelledMetadataStillConsumesItsPhysicalTokenUntilNativeReply() {
        val f = Fixture()
        f.transport.holdMe = true
        repeat(7) { index ->
            val request = f.thumbnail()
            f.clock.runCurrent() // GetMe must actually be sent before consumer cancellation.
            assertEquals(index + 1, f.transport.heldMe.size)
            request.lease.close()
            assertEquals(SettingsError.NOT_READY, request.results.single().error)
            f.clock.runCurrent()
            assertEquals(index + 1, f.transport.heldMe.size)
            assertEquals(index + 1, f.readers.used(), "Closed consumers do not drain native metadata")
        }
        val eighth = f.thumbnail()
        f.clock.runCurrent()
        assertTrue(eighth.results.isEmpty(), "The eighth reader waits at the helper gate")
        assertEquals(7, f.transport.heldMe.size)
        assertEquals(8, f.readers.used())
        val calls = f.transport.calls.size
        val ninth = f.thumbnail()
        assertTrue(ninth.results.isEmpty(), "The ninth reader waits in the shared reader queue")
        assertEquals(1, f.readers.waiting())
        assertEquals(calls, f.transport.calls.size)
        val once = f.transport.heldMe.removeFirst()
        once.reply(user())
        once.reply(user())
        f.clock.runCurrent()
        assertEquals(calls + 1, f.transport.calls.size, "Only the actual callback admits a new GetMe")
        assertEquals(7, f.transport.heldMe.size)
        assertEquals(8, f.readers.used())
        assertEquals(0, f.readers.waiting())
        assertTrue(eighth.results.isEmpty())
        assertTrue(ninth.results.isEmpty())
        eighth.lease.close(); ninth.lease.close()
        f.clock.runCurrent()
        while (f.transport.heldMe.isNotEmpty()) {
            val pending = f.transport.heldMe.removeFirst()
            pending.reply(user()); pending.reply(user())
            f.clock.runCurrent()
        }
        assertEquals(0, f.readers.used())

        val live = Fixture()
        live.transport.holdMe = true
        val readers = List(8) { live.thumbnail() }
        live.clock.runCurrent()
        assertEquals(7, live.transport.heldMe.size)
        val waiting = live.thumbnail()
        assertTrue(waiting.results.isEmpty())
        assertEquals(1, live.readers.waiting())
        readers.first().lease.close()
        live.clock.runCurrent()
        assertEquals(8, live.readers.used(), "Close cannot release a GetMe still in flight")
        assertEquals(1, live.readers.waiting())
        val actual = live.transport.heldMe.removeFirst()
        actual.reply(user()); actual.reply(user())
        live.clock.runCurrent()
        assertTrue(waiting.results.isEmpty())
        assertEquals(0, live.readers.waiting(), "The actual physical callback wakes the next reader")
        assertEquals(8, live.readers.used())
        readers.forEach { it.lease.close() }
        waiting.lease.close()
        live.clock.runCurrent()
        while (live.transport.heldMe.isNotEmpty()) {
            val pending = live.transport.heldMe.removeFirst()
            pending.reply(user()); pending.reply(user())
            live.clock.runCurrent()
        }
        assertEquals(0, live.readers.used())
    }

    @Test fun timeoutAndRetireNeverPublishLateResults() {
        val f = Fixture()
        f.transport.holdMe = true
        val read = f.thumbnail()
        f.clock.advance(30_000)
        assertEquals(SettingsError.REQUEST_FAILED, read.results.single().error)
        f.files.retire()
        f.transport.heldMe.removeFirst().reply(user())
        f.clock.runCurrent()
        assertEquals(1, read.results.size)
        assertTrue(f.native.reads.isEmpty())
        assertEquals(0L, f.budget.used())
    }

    @Test fun installedRegularStickerMediaRequiresTheSameLiveNativePack() {
        val f = Fixture()
        val item = TdApi.Sticker().apply {
            id = -30; setId = -20; width = 100; height = 100
            format = TdApi.StickerFormatWebp(); fullType = TdApi.StickerFullTypeRegular()
            sticker = file(80, 64)
            thumbnail = TdApi.Thumbnail(TdApi.ThumbnailFormatWebp(), 32, 32, file(81, 24))
        }
        val pack = TdApi.StickerSet().apply {
            id = -20; isInstalled = true; isArchived = false
            stickerType = TdApi.StickerTypeRegular(); stickers = arrayOf(item)
        }
        f.transport.pack = pack
        f.native.register(item.sticker!!)
        f.native.register(item.thumbnail!!.file!!)
        val packs = mutableListOf<SettingsResult<ProfileStickerAvatarPackPage>>()
        f.api.loadInstalledPacks(f.picker, ProfileStickerAvatarPackKind.REGULAR, packs::add)
        val packTicket = assertNotNull(packs.single().value).packs.single().ticket
        val candidates = mutableListOf<SettingsResult<ProfileStickerAvatarCandidatePage>>()
        f.api.openPack(f.picker, packTicket, candidates::add)
        f.candidate = assertNotNull(candidates.single().value).candidates.single()
        f.selection = assertNotNull(f.api.select(f.picker, f.candidate.ticket))
        val ready = f.full()
        f.clock.runCurrent()
        assertNotNull(ready.results.single().value).close()
        val reads = f.native.reads.size
        val priorRevision = f.picker.catalogRevision.value
        pack.isArchived = true
        val rejected = f.full()
        f.clock.runCurrent()
        assertEquals(SettingsError.NOT_READY, rejected.results.single().error)
        assertEquals(priorRevision + 1, f.picker.catalogRevision.value)
        assertNull(f.api.select(f.picker, f.candidate.ticket))
        assertEquals(reads, f.native.reads.size)
    }

    @Test fun throwingSuccessfulCallbacksReleaseOnlyTheirOwnHandlesAndReadersRemainReusable() {
        val f = Fixture()
        repeat(3) {
            var received = false
            f.files.full(f.picker, f.selection, { true }) { response ->
                received = response.value != null
                throw AssertionError("synthetic callback failure")
            }
            f.clock.runCurrent()
            assertTrue(received)
            assertEquals(0L, f.budget.used())
            assertEquals(0, f.readers.used())
        }
        val worker = AtomicReference<ProfileStickerAvatarEncoded?>()
        f.files.full(f.picker, f.selection, { true }) { response ->
            worker.set(response.value?.retain())
            throw AssertionError("worker retained before callback failure")
        }
        f.clock.runCurrent()
        assertNotNull(worker.get())
        assertTrue(f.budget.used() > 0)
        assertEquals(0, f.readers.used())
        worker.get()!!.close()
        assertEquals(0L, f.budget.used())
        val retried = f.full()
        f.clock.runCurrent()
        assertNotNull(retried.results.single().value).close()
    }

    @Test fun publicationKeepsItsBudgetWhileCallbackBlocksAndAnotherThreadEnqueuesWork() {
        val executor = Executors.newSingleThreadScheduledExecutor { action ->
            Thread(action, "synthetic-profile-publisher").apply { isDaemon = true }
        }
        val releaseCallback = CountDownLatch(1)
        try {
            val schedule: (Long, () -> Unit) -> Unit = { delay, action ->
                executor.schedule(Runnable { action() }, delay, TimeUnit.MILLISECONDS)
                Unit
            }
            val f = Fixture(scheduler = schedule)
            f.transport.asyncDefaultNumber = 3
            val callbackEntered = CountDownLatch(1)
            val callbackReturned = CountDownLatch(1)
            val valid = AtomicBoolean()
            f.files.full(f.picker, f.selection, { true }) { response ->
                valid.set(response.value != null)
                response.value?.close()
                callbackEntered.countDown()
                releaseCallback.await(3, TimeUnit.SECONDS)
                callbackReturned.countDown()
            }
            assertTrue(callbackEntered.await(3, TimeUnit.SECONDS))
            assertTrue(valid.get())
            assertTrue(f.budget.used() > 0, "The publisher stack still holds encoded bytes")
            val posterDone = CountDownLatch(1)
            val scheduledTasks = CountDownLatch(25)
            Thread({
                repeat(25) { schedule(0) { scheduledTasks.countDown() } }
                posterDone.countDown()
            }, "synthetic-concurrent-scheduler").apply { isDaemon = true; start() }
            assertTrue(posterDone.await(2, TimeUnit.SECONDS))
            assertFalse(scheduledTasks.await(100, TimeUnit.MILLISECONDS), "Serial work ran while the callback was blocked")
            assertTrue(f.budget.used() > 0)
            releaseCallback.countDown()
            assertTrue(callbackReturned.await(3, TimeUnit.SECONDS))
            assertTrue(scheduledTasks.await(3, TimeUnit.SECONDS))
            val drained = CountDownLatch(1)
            fun checkBudget() {
                if (f.budget.used() == 0L && f.readers.used() == 0) drained.countDown()
                else schedule(1) { checkBudget() }
            }
            schedule(0) { checkBudget() }
            assertTrue(drained.await(3, TimeUnit.SECONDS))
        } finally {
            releaseCallback.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun nextChunkStartsOnlyAfterTerminalAndHandlerAndTheirSerialReleaseInEitherOrder() {
        val handlerFirstDispatch = ConcurrentLinkedQueue<() -> Unit>()
        val first = Fixture(brokerDispatcher = { handlerFirstDispatch.add(it) })
        first.fullSize(3 * 65_536)
        first.native.holdReads = true
        val firstRequest = first.full()
        first.clock.runCurrent()
        assertEquals(1, first.native.reads.size)
        first.native.completeNextRead()
        val gate = Clock.Gate()
        first.clock.blockNextImmediate = gate
        val delivery = assertNotNull(handlerFirstDispatch.poll())
        val deliveryDone = CountDownLatch(1)
        Thread({ try { delivery() } finally { deliveryDone.countDown() } }, "blocked-owned-completed").apply {
            isDaemon = true; start()
        }
        try {
            assertTrue(gate.entered.await(2, TimeUnit.SECONDS))
            first.clock.runCurrent()
            assertEquals(1, first.native.reads.size, "No next read may start while the prior terminal is blocked")
            assertTrue(first.native.pendingReads.isEmpty())
        } finally { gate.release.countDown() }
        assertTrue(deliveryDone.await(2, TimeUnit.SECONDS))
        assertEquals(1, first.native.reads.size)
        assertTrue(first.clock.runOne(), "The retained references must be released on the serial queue")
        assertEquals(2, first.native.reads.size)
        firstRequest.lease.close()
        first.clock.runCurrent()
        first.native.completeNextRead()
        assertNotNull(handlerFirstDispatch.poll()).invoke()
        first.clock.runCurrent()
        assertEquals(0L, first.budget.used())

        val terminalFirstDispatch = ConcurrentLinkedQueue<() -> Unit>()
        val second = Fixture(brokerDispatcher = { terminalFirstDispatch.add(it) })
        second.fullSize(3 * 65_536)
        second.native.holdReads = true
        val secondRequest = second.full()
        second.clock.runCurrent()
        second.native.completeNextRead()
        assertNotNull(terminalFirstDispatch.poll()).invoke()
        assertEquals(1, second.native.reads.size, "Terminal alone cannot advance before the handler exits")
        assertTrue(second.clock.runOne(), "The response handler must run first")
        assertEquals(1, second.native.reads.size, "The handler cannot advance before serial release")
        assertTrue(second.clock.runOne(), "The release action advances exactly once")
        assertEquals(2, second.native.reads.size)
        secondRequest.lease.close()
        second.clock.runCurrent()
        second.native.completeNextRead()
        assertNotNull(terminalFirstDispatch.poll()).invoke()
        second.clock.runCurrent()
        assertEquals(0L, second.budget.used())
    }

    @Test fun permanentReceiverHeadroomCoversBlockedProducerAndUnprocessedBatchRemainsReserved() {
        val total = 64L * 1024 * 1024
        val budget = ProfileStickerAvatarEncodedBudget()
        assertEquals(65_536L, budget.headroom())
        assertEquals(total - 65_536, budget.dynamicCapacity())
        assertEquals(65_536L, budget.accounted())
        val dispatch = ConcurrentLinkedQueue<() -> Unit>()
        val f = Fixture(budget = budget, brokerDispatcher = { dispatch.add(it) })
        f.fullSize(65_536)
        f.native.holdReads = true
        val request = f.full()
        f.clock.runCurrent()
        request.lease.close()
        f.clock.runCurrent()
        assertTrue(budget.used() > 0)
        val producerHeld = CountDownLatch(1)
        val allowProducerReturn = CountDownLatch(1)
        val producerExited = CountDownLatch(1)
        f.native.afterRead = {
            producerHeld.countDown()
            allowProducerReturn.await(3, TimeUnit.SECONDS)
        }
        val pending = f.native.pendingReads.removeFirst()
        Thread({ try { pending() } finally { producerExited.countDown() } }, "single-synthetic-producer").apply {
            isDaemon = true; start()
        }
        try {
            assertTrue(producerHeld.await(2, TimeUnit.SECONDS))
            assertNotNull(dispatch.poll()).invoke()
            f.clock.runCurrent()
            assertEquals(0L, budget.used(), "Only the permanent receiver allowance remains charged")
            val fill = assertNotNull(budget.reserve(budget.dynamicCapacity()))
            assertEquals(total, budget.accounted())
            assertNull(budget.reserve(1))
            allowProducerReturn.countDown()
            assertTrue(producerExited.await(2, TimeUnit.SECONDS))
            fill.release()
            f.files.retire()
            assertEquals(65_536L, budget.accounted())
            val laterSession = Fixture(budget = budget)
            laterSession.files.retire()
            assertEquals(65_536L, budget.accounted())
        } finally { allowProducerReturn.countDown() }

        val batchBudget = ProfileStickerAvatarEncodedBudget()
        val batchDispatch = ConcurrentLinkedQueue<() -> Unit>()
        val batch = Fixture(budget = batchBudget, brokerDispatcher = { batchDispatch.add(it) })
        batch.fullSize(65_536)
        batch.native.holdReads = true
        val first = batch.full()
        val second = batch.full()
        batch.clock.runCurrent()
        assertEquals(2, batch.native.pendingReads.size)
        val bothReservations = 2L * (65_536 + 65_536)
        assertEquals(bothReservations, batchBudget.used())
        first.lease.close(); second.lease.close()
        batch.clock.runCurrent()
        assertEquals(bothReservations, batchBudget.used(), "Unprocessed Data remain charged separately")
        batch.native.completeNextRead(); batch.native.completeNextRead()
        assertNotNull(batchDispatch.poll()).invoke()
        assertNotNull(batchDispatch.poll()).invoke()
        batch.clock.runCurrent()
        assertEquals(0L, batchBudget.used())
        assertEquals(65_536L, batchBudget.accounted())
    }

    private class Request(val lease: ProfileStickerAvatarReadLease,
        val results: MutableList<SettingsResult<ProfileStickerAvatarEncoded>>)

    private class Fixture(
        initial: Array<TdApi.Sticker> = arrayOf(sticker()),
        val budget: ProfileStickerAvatarEncodedBudget = ProfileStickerAvatarEncodedBudget(),
        scheduler: ((Long, () -> Unit) -> Unit)? = null,
        brokerDispatcher: ((() -> Unit) -> Unit)? = null,
    ) {
        val clock = Clock()
        private val schedule = scheduler ?: clock::schedule
        private val dispatch: (() -> Unit) -> Unit = brokerDispatcher ?: { action -> schedule(0, action) }
        val transport = Transport(initial)
        private val mediaAsync = ManualMediaAsync(schedule)
        val api = OfficialTdProfileStickerAvatar(mediaAsync, transport::send)
        val picker = assertNotNull(api.open(43) { true })
        val native = Native()
        val broker = TdDownloadBroker(native, schedule, { clock.now }, dispatch)
        val readers = ProfileStickerAvatarReaderBudget()
        val files = OfficialTdProfileStickerAvatarFiles(api, broker, budget, readers, schedule, { clock.now })
        lateinit var page: ProfileStickerAvatarCandidatePage
        lateinit var candidate: ProfileStickerAvatarCandidate
        lateinit var selection: ProfileStickerAvatarSelectionTicket
        val sticker: TdApi.Sticker get() = transport.stickers.first()

        init {
            initial.forEach { item ->
                item.sticker?.let { native.register(it) }
                item.thumbnail?.file?.let { native.register(it) }
            }
            refreshCatalog()
        }

        fun refreshCatalog() {
            val output = mutableListOf<SettingsResult<ProfileStickerAvatarCandidatePage>>()
            api.loadDefault(picker, output::add)
            page = assertNotNull(output.single().value)
            page.candidates.firstOrNull()?.let {
                candidate = it
                selection = assertNotNull(api.select(picker, candidate.ticket))
            }
        }

        fun fullSize(size: Int) {
            val file = checkNotNull(sticker.sticker)
            file.size = size.toLong(); file.expectedSize = size.toLong()
            file.local!!.isDownloadingCompleted = true; file.local!!.downloadedSize = size.toLong()
            native.files[file.id] = mapMediaFile(file)
            native.bytes[file.id] = ByteArray(size) { (it + file.id).toByte() }
            refreshCatalog()
        }

        fun thumbnail(): Request {
            val output = mutableListOf<SettingsResult<ProfileStickerAvatarEncoded>>()
            val lease = files.thumbnail(picker, candidate.ticket, { true }, output::add)
            return Request(lease, output)
        }

        fun full(): Request {
            val output = mutableListOf<SettingsResult<ProfileStickerAvatarEncoded>>()
            val lease = files.full(picker, selection, { true }, output::add)
            return Request(lease, output)
        }
    }

    private class Transport(var stickers: Array<TdApi.Sticker>) {
        class Call(val command: TdApi.Function<*>, val reply: (TdApi.Object) -> Unit, val fail: () -> Unit)
        val calls = mutableListOf<Call>()
        val heldMe = ArrayDeque<Call>()
        var holdMe = false
        var selfId = 43L
        private val defaultCount = AtomicInteger()
        var asyncDefaultNumber: Int? = null
        var pack: TdApi.StickerSet? = null
        fun send(command: TdApi.Function<*>, reply: (TdApi.Object) -> Unit, fail: () -> Unit) {
            val call = Call(command, reply, fail)
            calls += call
            when (command) {
                is TdApi.GetMe -> if (holdMe) heldMe.addLast(call) else reply(user(selfId))
                is TdApi.GetDefaultProfilePhotoCustomEmojiStickers -> {
                    val callNumber = defaultCount.incrementAndGet()
                    if (callNumber == asyncDefaultNumber) Thread({ reply(TdApi.Stickers(stickers)) }, "synthetic-native-callback").apply {
                        isDaemon = true; start()
                    } else reply(TdApi.Stickers(stickers))
                }
                is TdApi.GetInstalledStickerSets -> {
                    val selected = pack
                    if (selected == null) fail() else {
                        val info = TdApi.StickerSetInfo().apply {
                            id = selected.id; title = "synthetic"; size = 1
                            isInstalled = true; stickerType = TdApi.StickerTypeRegular()
                        }
                        reply(TdApi.StickerSets(1, arrayOf(info)))
                    }
                }
                is TdApi.GetStickerSet -> pack?.let(reply) ?: fail()
                else -> fail()
            }
        }
    }

    private class Native : DownloadBrokerNative {
        data class Download(val fileId: Int, val offset: Long, val limit: Long)
        data class Read(val fileId: Int, val offset: Long, val count: Int)
        val files = mutableMapOf<Int, MediaFile>()
        val bytes = mutableMapOf<Int, ByteArray>()
        val downloads = mutableListOf<Download>()
        val reads = mutableListOf<Read>()
        val cancelled = mutableListOf<Int>()
        val pendingReads = ArrayDeque<() -> Unit>()
        val pendingPrefixes = ArrayDeque<() -> Unit>()
        var holdReads = false
        var holdPrefixes = false
        var onRead: (() -> Unit)? = null
        var afterRead: (() -> Unit)? = null

        fun register(file: TdApi.File) {
            val mapped = mapMediaFile(file)
            if (mapped.id in files) return
            files[mapped.id] = mapped
            bytes[mapped.id] = ByteArray(mapped.size.toInt()) { (it + mapped.id).toByte() }
        }

        override fun download(fileId: Int, priority: Int, offset: Long, limit: Long, completed: (ChatResult<MediaFile>) -> Unit) {
            downloads += Download(fileId, offset, limit)
            completed(ChatResult(files.getValue(fileId)))
        }
        override fun prefix(fileId: Int, offset: Long, completed: (ChatResult<Long>) -> Unit) {
            val file = files.getValue(fileId)
            val count = if (file.downloaded) (bytes.getValue(fileId).size.toLong() - offset).coerceAtLeast(0) else 0L
            val action = { completed(ChatResult(count)) }
            if (holdPrefixes) pendingPrefixes.addLast(action) else action()
        }
        fun completeNextPrefix() { pendingPrefixes.removeFirst().invoke() }
        override fun read(fileId: Int, offset: Long, count: Int, completed: (ChatResult<ByteArray>) -> Unit) {
            reads += Read(fileId, offset, count)
            val source = bytes.getValue(fileId)
            val part = source.copyOfRange(offset.toInt(), offset.toInt() + count)
            val action: () -> Unit = {
                onRead?.invoke()
                completed(ChatResult(part))
                afterRead?.invoke()
            }
            if (holdReads) pendingReads.addLast(action) else action()
        }
        fun completeNextRead() { pendingReads.removeFirst().invoke() }
        override fun refresh(fileId: Int, completed: (ChatResult<MediaFile>) -> Unit) = completed(ChatResult(files.getValue(fileId)))
        override fun cancel(fileId: Int, completed: (ChatResult<Unit>) -> Unit) { cancelled += fileId; completed(ChatResult(Unit)) }
    }

    /** Runs through the same serial, non-reentrant scheduler as this fixture. */
    private class ManualMediaAsync(private val schedule: (Long, () -> Unit) -> Unit) : ProfileStickerAvatarMediaAsync {
        private val lock = Any()
        private var tickets = 0

        private fun post(action: () -> Unit, exited: () -> Unit) {
            val task: () -> Unit = {
                try { action() } finally {
                    // The scheduler is serial and non-reentrant: this executes only after task returns.
                    try { schedule(0, exited) } catch (_: Throwable) { /* unknown enqueue: retain */ }
                }
            }
            schedule(0, task) // A throw may be after enqueue; helper retains until the real task exits.
        }

        override fun dispatch(action: () -> Unit, exited: () -> Unit, rejected: () -> Unit) = post(action, exited)

        override fun reserveConsumer(): ProfileStickerAvatarMediaConsumerPermit? = synchronized(lock) {
            if (tickets >= 128) null else {
                tickets++
                object : ProfileStickerAvatarMediaConsumerPermit {
                    private val submitted = AtomicBoolean()
                    private val released = AtomicBoolean()
                    override fun deliver(action: () -> Unit, exited: () -> Unit) {
                        check(submitted.compareAndSet(false, true))
                        val after: () -> Unit = {
                            try { exited() } finally {
                                if (released.compareAndSet(false, true)) synchronized(lock) { tickets-- }
                            }
                        }
                        post(action, after)
                    }
                }
            }
        }
    }

    private class Clock {
        class Gate(val entered: CountDownLatch = CountDownLatch(1), val release: CountDownLatch = CountDownLatch(1))
        @Volatile var now = 0L
        @Volatile var blockNextImmediate: Gate? = null
        private val queued = mutableListOf<Pair<Long, () -> Unit>>()
        private val runner = Any()
        private var running = false // guarded by runner; enforces non-reentrancy, including same-thread calls
        fun schedule(delay: Long, action: () -> Unit) {
            val gate = synchronized(queued) {
                queued += now + delay to action
                if (delay == 0L) blockNextImmediate.also { blockNextImmediate = null } else null
            }
            if (gate != null) { gate.entered.countDown(); gate.release.await(3, TimeUnit.SECONDS) }
        }
        fun runCurrent() = advance(0)
        fun runOne(): Boolean = synchronized(runner) {
            check(!running) { "The fake serial scheduler must be non-reentrant" }
            running = true
            try {
                val next = synchronized(queued) {
                    queued.filter { it.first <= now }.minByOrNull { it.first }?.also { queued.remove(it) }
                } ?: return@synchronized false
                next.second()
                true
            } finally { running = false }
        }
        fun advance(delay: Long) = synchronized(runner) {
            check(!running) { "The fake serial scheduler must be non-reentrant" }
            running = true
            try {
                val target = now + delay
                repeat(20_000) {
                    val next = synchronized(queued) {
                        queued.filter { it.first <= target }.minByOrNull { it.first }?.also { queued.remove(it) }
                    } ?: run { now = target; return@synchronized }
                    now = next.first; next.second()
                }
                error("Unbounded fake scheduling")
            } finally { running = false }
        }
    }

    private companion object {
        fun user(userId: Long = 43) = TdApi.User().apply { id = userId }
        fun sticker(customId: Long = -700, fullId: Int = 70, thumbId: Int = 71) = TdApi.Sticker().apply {
            id = 0; setId = 0; width = 128; height = 96
            format = TdApi.StickerFormatWebp()
            fullType = TdApi.StickerFullTypeCustomEmoji(customId, true)
            sticker = file(fullId, 64)
            thumbnail = TdApi.Thumbnail(TdApi.ThumbnailFormatWebp(), 32, 32, file(thumbId, 24))
        }
        fun file(fileId: Int, length: Long) = TdApi.File().apply {
            id = fileId; size = length; expectedSize = length
            local = TdApi.LocalFile().apply { isDownloadingCompleted = true; canBeDownloaded = true; downloadedSize = length }
            remote = TdApi.RemoteFile().apply { uniqueId = "synthetic-$fileId" }
        }
    }
}
