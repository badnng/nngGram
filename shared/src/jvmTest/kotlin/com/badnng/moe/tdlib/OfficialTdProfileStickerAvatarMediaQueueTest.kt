package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

/** Real broker, synthetic native calls and an isolated deterministic scheduler; no account. */
class OfficialTdProfileStickerAvatarMediaQueueTest {

    @Test fun twelveVisibleCandidatesWaitForPhysicalZeroAndFullWinsTheNextSlot() {
        val f = Fixture(initial = candidates(12))
        f.native.holdReads = true
        val thumbnails = List(12) { f.thumbnail(it) }
        f.clock.runCurrent()
        assertEquals(8, f.readers.used())
        assertEquals(4, f.readers.waiting())
        assertEquals(8, f.native.reads.size)
        assertEquals(8, f.native.pendingReads.size)
        assertTrue(thumbnails.all { it.results.isEmpty() })
        val calls = f.transport.calls.size
        val bytes = f.budget.used()
        val full = f.full()
        assertTrue(full.results.isEmpty())
        assertEquals(5, f.readers.waiting())
        assertEquals(calls, f.transport.calls.size)
        assertEquals(bytes, f.budget.used())

        thumbnails.first().lease.close()
        f.clock.runCurrent()
        assertEquals(SettingsError.NOT_READY, thumbnails.first().results.single().error)
        assertEquals(8, f.readers.used(), "A closed consumer still has a native chunk")
        assertEquals(8, f.native.reads.size)
        assertEquals(calls, f.transport.calls.size)
        f.native.completeNextRead()
        f.clock.runCurrent()
        assertEquals(8, f.readers.used())
        assertEquals(4, f.readers.waiting())
        assertEquals(9, f.native.reads.size)
        assertEquals(1000, f.native.reads.last().fileId, "The selected full wins before four thumbnails")
        assertTrue(full.results.isEmpty())
        assertEquals(65_536L, f.budget.headroom())
        assertTrue(f.budget.accounted() <= 64L * 1024 * 1024)
        (thumbnails + full).forEach { it.lease.close() }
        f.clock.runCurrent()
        while (f.native.pendingReads.isNotEmpty()) { f.native.completeNextRead(); f.clock.runCurrent() }
        assertEquals(0, f.readers.used())
        assertEquals(0, f.readers.waiting())
        assertEquals(0L, f.budget.used())
    }

    @Test fun closeAndStalePickerCannotReviveQueuedCandidates() {
        val f = Fixture(initial = candidates(10))
        f.native.holdReads = true
        val live = List(8) { f.thumbnail(it) }
        f.clock.runCurrent()
        val active = AtomicBoolean(true)
        val gone = f.thumbnail(8) { active.get() }
        val replacement = f.thumbnail(8)
        active.set(false)
        gone.lease.close()
        assertEquals(SettingsError.NOT_READY, gone.results.single().error)
        assertEquals(1, f.readers.waiting())
        live.first().lease.close()
        f.clock.runCurrent()
        f.native.completeNextRead()
        f.clock.runCurrent()
        assertTrue(replacement.results.isEmpty())
        assertEquals(1017, f.native.reads.last().fileId, "Only the fresh lease is admitted")
        assertEquals(1, f.native.reads.count { it.fileId == 1017 })
        (live + replacement).forEach { it.lease.close() }
        f.clock.runCurrent()
        while (f.native.pendingReads.isNotEmpty()) { f.native.completeNextRead(); f.clock.runCurrent() }

        val stale = Fixture(initial = candidates(9))
        stale.native.holdReads = true
        val old = List(8) { stale.thumbnail(it) }
        stale.clock.runCurrent()
        val queued = stale.thumbnail(8)
        assertNotNull(stale.api.open(43) { true }) // revokes the original picker/candidates
        val priorCalls = stale.transport.calls.size
        old.first().lease.close()
        stale.clock.runCurrent()
        stale.native.completeNextRead()
        stale.clock.runCurrent()
        assertEquals(SettingsError.CONFLICT, queued.results.single().error)
        assertEquals(priorCalls, stale.transport.calls.size, "The stale ticket never issues metadata")
        old.forEach { it.lease.close() }
        stale.clock.runCurrent()
        while (stale.native.pendingReads.isNotEmpty()) { stale.native.completeNextRead(); stale.clock.runCurrent() }
        assertEquals(0, stale.readers.used())
    }

    @Test fun separateServicesShareOnePhysicalLimitAndWakeAcrossServices() {
        val readers = ProfileStickerAvatarReaderBudget()
        val budget = ProfileStickerAvatarEncodedBudget()
        val a = Fixture(initial = candidates(8), readers = readers, budget = budget)
        val b = Fixture(initial = candidates(3), readers = readers, budget = budget)
        a.native.holdReads = true
        b.native.holdReads = true
        val fromA = List(6) { a.thumbnail(it) }
        val fromB = List(2) { b.thumbnail(it) }
        a.clock.runCurrent(); b.clock.runCurrent()
        val queued = b.thumbnail(2)
        assertEquals(8, readers.used())
        assertEquals(1, readers.waiting())
        val prior = b.transport.calls.size
        fromA.first().lease.close()
        a.clock.runCurrent(); b.clock.runCurrent()
        assertEquals(prior, b.transport.calls.size)
        a.native.completeNextRead()
        a.clock.runCurrent(); b.clock.runCurrent()
        assertEquals(prior + 2, b.transport.calls.size)
        assertEquals(3, b.native.reads.size)
        assertEquals(8, readers.used())
        (fromA + fromB + queued).forEach { it.lease.close() }
        a.clock.runCurrent(); b.clock.runCurrent()
        while (a.native.pendingReads.isNotEmpty()) { a.native.completeNextRead(); a.clock.runCurrent() }
        while (b.native.pendingReads.isNotEmpty()) { b.native.completeNextRead(); b.clock.runCurrent() }
        assertEquals(0, readers.used())
        assertEquals(0L, budget.used())
    }

    @Test fun waitingBoundIsExplicitAndCancelMakesRoomWithoutDispatch() {
        val readers = ProfileStickerAvatarReaderBudget(limit = 1, maxWaiting = 2)
        val f = Fixture(initial = candidates(4), readers = readers)
        f.native.holdReads = true
        val held = f.thumbnail(0)
        f.clock.runCurrent()
        val first = f.thumbnail(1)
        val second = f.thumbnail(2)
        val calls = f.transport.calls.size
        val denied = f.thumbnail(3)
        assertEquals(SettingsError.RATE_LIMITED, denied.results.single().error)
        assertEquals(2, readers.waiting())
        assertEquals(calls, f.transport.calls.size)
        first.lease.close()
        val replacement = f.thumbnail(3)
        assertTrue(replacement.results.isEmpty())
        assertEquals(2, readers.waiting())
        assertEquals(calls, f.transport.calls.size)
        listOf(held, second, replacement).forEach { it.lease.close() }
        f.clock.runCurrent()
        f.native.completeNextRead(); f.clock.runCurrent()
        assertEquals(0, readers.used())
        assertEquals(0, readers.waiting())
    }

    @Test fun defaultWaitingLimitIs128AcrossTheSharedBudget() {
        val readers = ProfileStickerAvatarReaderBudget(limit = 1)
        val held = AtomicReference<ProfileStickerAvatarReaderBudget.Token>()
        readers.submit(readers.waiter(ProfileStickerAvatarMediaRole.THUMBNAIL) { slot, _ -> held.set(slot) })
        val waiters = List(128) {
            readers.waiter(ProfileStickerAvatarMediaRole.THUMBNAIL) { slot, _ -> slot.release() }.also { waiter ->
                assertEquals(ProfileStickerAvatarReaderBudget.Submission.ACCEPTED, readers.submit(waiter))
            }
        }
        val denied = readers.waiter(ProfileStickerAvatarMediaRole.FULL) { slot, _ -> slot.release() }
        assertEquals(ProfileStickerAvatarReaderBudget.Submission.FULL, readers.submit(denied))
        assertEquals(128, readers.waiting())
        waiters.forEach { it.cancel() }
        held.get().release()
        assertEquals(0, readers.used())
        assertEquals(0, readers.waiting())
    }

    @Test fun admissionCallbackCanReenterFromAnotherThreadWithoutBudgetLock() {
        val readers = ProfileStickerAvatarReaderBudget(limit = 1)
        val observed = CountDownLatch(1)
        val token = AtomicReference<ProfileStickerAvatarReaderBudget.Token>()
        val waiter = readers.waiter(ProfileStickerAvatarMediaRole.THUMBNAIL) { slot, _ ->
            token.set(slot)
            Thread({ readers.used(); observed.countDown() }, "reader-budget-reentry").apply { isDaemon = true; start() }
            assertTrue(observed.await(2, TimeUnit.SECONDS), "Foreign callback held the reader budget monitor")
        }
        assertEquals(ProfileStickerAvatarReaderBudget.Submission.ACCEPTED, readers.submit(waiter))
        assertEquals(1, readers.used())
        token.get().release()
        assertEquals(0, readers.used())
    }

    @Test fun fullAndThumbnailsAreFifoAndTheWakeCallbackIsOutsideTheBudgetLock() {
        val readers = ProfileStickerAvatarReaderBudget(limit = 1)
        val order = mutableListOf<String>()
        val tokens = ArrayDeque<ProfileStickerAvatarReaderBudget.Token>()
        val reentered = CountDownLatch(1)
        fun offer(role: ProfileStickerAvatarMediaRole, name: String) {
            val waiter = readers.waiter(role) { slot, deferred ->
                order += name
                tokens.addLast(slot)
                if (deferred && name == "full-1") {
                    Thread({ readers.waiting(); reentered.countDown() }, "queue-wakeup-reentry").apply {
                        isDaemon = true; start()
                    }
                    assertTrue(reentered.await(2, TimeUnit.SECONDS))
                }
            }
            assertEquals(ProfileStickerAvatarReaderBudget.Submission.ACCEPTED, readers.submit(waiter))
        }
        offer(ProfileStickerAvatarMediaRole.THUMBNAIL, "held")
        offer(ProfileStickerAvatarMediaRole.THUMBNAIL, "thumb-1")
        offer(ProfileStickerAvatarMediaRole.FULL, "full-1")
        offer(ProfileStickerAvatarMediaRole.THUMBNAIL, "thumb-2")
        offer(ProfileStickerAvatarMediaRole.FULL, "full-2")
        repeat(5) { tokens.removeFirst().release() }
        assertEquals(listOf("held", "full-1", "full-2", "thumb-1", "thumb-2"), order)
        assertEquals(0, readers.used())
        assertEquals(0, readers.waiting())
    }

    @Test fun closingAnAlreadyReservedButUnexecutedWaiterCannotTransferItsSlotEarly() {
        val readers = ProfileStickerAvatarReaderBudget(limit = 1)
        val holder = AtomicReference<ProfileStickerAvatarReaderBudget.Token>()
        readers.submit(readers.waiter(ProfileStickerAvatarMediaRole.THUMBNAIL) { slot, _ -> holder.set(slot) })
        val f = Fixture(readers = readers)
        val first = f.thumbnail()
        val next = f.thumbnail()
        val calls = f.transport.calls.size
        holder.get().release() // reserve first and enqueue its producer, but do not run it
        first.lease.close()
        assertTrue(f.clock.runLast(), "Run the cancelled consumer's slot release first")
        assertEquals(1, readers.used())
        assertEquals(1, readers.waiting())
        assertEquals(calls, f.transport.calls.size)
        assertTrue(f.clock.runOne()) // cancelled producer exits; next can now be scheduled
        f.clock.runCurrent()
        assertEquals(SettingsError.NOT_READY, first.results.single().error)
        assertNotNull(next.results.single().value).close()
        f.clock.runCurrent()
        assertEquals(0, readers.used())
    }

    @Test fun synchronousResultMayRetireAndReenterTheSameService() {
        val f = Fixture()
        f.api.close(f.picker)
        val outcomes = mutableListOf<SettingsError?>()
        f.files.thumbnail(f.picker, f.candidate.ticket, { true }) { first ->
            outcomes += first.error
            f.files.retire()
            f.files.thumbnail(f.picker, f.candidate.ticket, { true }) { nested -> outcomes += nested.error }
        }
        f.clock.runCurrent()
        assertEquals(listOf<SettingsError?>(SettingsError.CONFLICT, SettingsError.NOT_READY), outcomes)
        assertEquals(0, f.readers.used())
    }

    @Test fun cancelledMetadataRetainsReadersUntilRealCallbackAndDuplicateCannotWakeTwice() {
        val f = Fixture(initial = candidates(9))
        f.transport.holdMe = true
        val old = List(7) { f.thumbnail(it) }
        f.clock.runCurrent()
        assertEquals(7, f.transport.heldMe.size)
        old.forEach { it.lease.close() }
        f.clock.runCurrent()
        assertEquals(7, f.readers.used())
        assertEquals(7, f.transport.heldMe.size)
        val calls = f.transport.calls.size
        val eighth = f.thumbnail(7) // reader reserved; waits on the native media gate
        val ninth = f.thumbnail(8) // waits for a global reader
        assertTrue(eighth.results.isEmpty())
        assertTrue(ninth.results.isEmpty())
        assertEquals(8, f.readers.used())
        assertEquals(1, f.readers.waiting())
        assertEquals(calls, f.transport.calls.size)
        val completed = f.transport.heldMe.removeFirst()
        completed.reply(user())
        completed.reply(user())
        f.clock.runCurrent()
        assertEquals(calls + 1, f.transport.calls.size, "The native gate admits just one new media RPC")
        assertEquals(7, f.transport.heldMe.size)
        assertEquals(8, f.readers.used())
        assertEquals(0, f.readers.waiting())
        assertTrue(eighth.results.isEmpty())
        assertTrue(ninth.results.isEmpty())
        eighth.lease.close(); ninth.lease.close()
        f.clock.runCurrent()
        while (f.transport.heldMe.isNotEmpty()) {
            val callback = f.transport.heldMe.removeFirst()
            callback.reply(user()); callback.reply(user())
            f.clock.runCurrent()
        }
        assertEquals(0, f.readers.used())
    }

    @Test fun sevenHeldMediaLeaveBothOperationAndNativeCapacityForSave() {
        val f = Fixture(initial = candidates(8))
        f.transport.holdMe = true
        val media = List(8) { f.thumbnail(it) }
        f.clock.runCurrent()
        assertEquals(7, f.transport.heldMe.size, "The eighth media must wait before GetMe")
        assertEquals(8, f.readers.used())
        val originalCalls = f.transport.calls.size
        val saves = mutableListOf<SettingsResult<Unit>>()
        val background = assertNotNull(ProfileStickerAvatarBackground.fromColors(listOf(0x336699)))
        f.api.save(f.picker, f.selection, null, background, { true }, saves::add)
        assertEquals(8, f.transport.heldMe.size, "Save uses the reserved native interval")
        assertEquals(originalCalls + 1, f.transport.calls.size)
        f.transport.heldMe.removeLast().reply(user()) // Save GetMe -> source -> final GetMe
        assertTrue(saves.isEmpty())
        assertEquals(8, f.transport.heldMe.size)
        f.transport.heldMe.removeLast().reply(user()) // final GetMe -> synthetic write
        assertEquals(Unit, saves.single().value)
        assertEquals(1, f.transport.writes)
        assertEquals(7, f.transport.heldMe.size, "Waiting media did not consume the reserved slot")
        assertTrue(media.all { it.results.isEmpty() })
        media.forEach { it.lease.close() }
        f.clock.runCurrent()
        while (f.transport.heldMe.isNotEmpty()) f.transport.heldMe.removeFirst().reply(user())
        f.clock.runCurrent()
        assertEquals(0, f.readers.used())
    }

    @Test fun reservationUsesTotalOccupancyIncludingNonMedia() {
        val f = Fixture(initial = candidates(7))
        f.transport.holdMe = true
        val media = List(6) { f.thumbnail(it) }
        f.clock.runCurrent()
        val background = assertNotNull(ProfileStickerAvatarBackground.fromColors(listOf(0x336699)))
        f.api.save(f.picker, f.selection, null, background, { true }) {}
        assertEquals(7, f.transport.heldMe.size)
        val calls = f.transport.calls.size
        val seventhMedia = f.thumbnail(6)
        assertTrue(seventhMedia.results.isEmpty())
        assertEquals(calls, f.transport.calls.size, "Six media plus one Save already occupy seven slots")
        assertEquals(7, f.transport.heldMe.size)
        (media + seventhMedia).forEach { it.lease.close() }
        f.files.retire(); f.api.retire()
        while (f.transport.heldMe.isNotEmpty()) f.transport.heldMe.removeFirst().reply(user())
        f.clock.runCurrent()
        assertEquals(0, f.readers.used())
    }

    @Test fun mediaWaitsForBothExistingSaveOperationsAndTheirPhysicalNativeSlots() {
        val f = Fixture()
        val background = assertNotNull(ProfileStickerAvatarBackground.fromColors(listOf(0x336699)))
        f.transport.holdMe = true
        val saveActive = List(8) { AtomicBoolean(true) }
        val saves = mutableListOf<SettingsResult<Unit>>()
        saveActive.forEach { active ->
            f.api.save(f.picker, f.selection, null, background, { active.get() }, saves::add)
        }
        assertEquals(8, f.transport.heldMe.size)
        val calls = f.transport.calls.size
        val pending = f.thumbnail()
        assertTrue(pending.results.isEmpty())
        assertEquals(calls, f.transport.calls.size, "The media admission waits for an operation")
        saveActive[0].set(false)
        f.transport.heldMe.removeFirst().reply(user())
        assertEquals(1, saves.size)
        assertEquals(SettingsError.NOT_READY, saves.single().error)
        assertEquals(calls, f.transport.calls.size, "Seven total operations still reserve one for Save")
        saveActive[1].set(false)
        f.transport.heldMe.removeFirst().reply(user())
        f.clock.runCurrent()
        assertEquals(2, saves.size)
        assertEquals(calls + 1, f.transport.calls.size)
        assertTrue(pending.results.isEmpty())
        pending.lease.close()
        f.files.retire()
        f.api.retire()
        while (f.transport.heldMe.isNotEmpty()) f.transport.heldMe.removeFirst().reply(user())
        f.clock.runCurrent()
        assertEquals(0, f.readers.used())

        val physical = Fixture()
        physical.transport.holdMe = true
        repeat(8) { physical.api.save(physical.picker, physical.selection, null, background, { true }) {} }
        assertEquals(8, physical.transport.heldMe.size)
        assertNotNull(physical.api.select(physical.picker, physical.candidate.ticket)) // cancel saves, not their RPCs
        val before = physical.transport.calls.size
        val waiting = physical.thumbnail()
        assertTrue(waiting.results.isEmpty())
        assertEquals(before, physical.transport.calls.size, "Operations ended but their native RPCs remain")
        val firstOld = physical.transport.heldMe.removeFirst()
        firstOld.reply(user()); firstOld.reply(user())
        assertEquals(before, physical.transport.calls.size, "Seven total native calls reserve one for Save")
        val secondOld = physical.transport.heldMe.removeFirst()
        secondOld.reply(user()); secondOld.reply(user())
        physical.clock.runCurrent()
        assertEquals(before + 1, physical.transport.calls.size)
        assertTrue(waiting.results.isEmpty())
        waiting.lease.close()
        physical.files.retire(); physical.api.retire()
        while (physical.transport.heldMe.isNotEmpty()) physical.transport.heldMe.removeFirst().reply(user())
        physical.clock.runCurrent()
        assertEquals(0, physical.readers.used())
    }

    @Test fun metadataUnknownDispatchAndBlockedConsumerKeepTheReaderUntilActualExit() {
        val f = Fixture()
        f.transport.holdMe = true
        f.transport.throwAfterHeld = true
        val failed = f.thumbnail()
        f.clock.runCurrent()
        assertEquals(SettingsError.REQUEST_FAILED, failed.results.single().error)
        assertEquals(1, f.readers.used())
        val callback = f.transport.heldMe.removeFirst()
        callback.reply(user()); callback.reply(user())
        f.clock.runCurrent()
        assertEquals(0, f.readers.used())
        assertEquals(1, failed.results.size)

        val blocked = Fixture()
        val grant = assertNotNull(blocked.api.thumbnailMedia(blocked.picker, blocked.candidate.ticket))
        blocked.transport.holdMe = true
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val terminals = AtomicInteger()
        blocked.api.resolveMediaOwned(grant, { true }, { terminals.incrementAndGet() }) {
            entered.countDown()
            release.await(3, TimeUnit.SECONDS)
            throw AssertionError("consumer callback throws")
        }
        blocked.clock.runCurrent()
        val native = blocked.transport.heldMe.removeFirst()
        Thread({ try { native.reply(user()); blocked.clock.runCurrent() } finally { returned.countDown() } }, "blocked-metadata-callback").apply {
            isDaemon = true; start()
        }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            blocked.api.closeMedia(grant)
            assertEquals(0, terminals.get(), "Consumer and native processing still own the result")
        } finally { release.countDown() }
        assertTrue(returned.await(2, TimeUnit.SECONDS))
        assertEquals(1, terminals.get())
        native.reply(user())
        assertEquals(1, terminals.get())
    }

    @Test fun uncertainSchedulerEnqueueRetainsReservationUntilTheQueuedRunnableExits() {
        val readers = ProfileStickerAvatarReaderBudget(limit = 1)
        val holder = AtomicReference<ProfileStickerAvatarReaderBudget.Token>()
        readers.submit(readers.waiter(ProfileStickerAvatarMediaRole.THUMBNAIL) { slot, _ -> holder.set(slot) })
        val f = Fixture(readers = readers)
        val failed = f.thumbnail()
        val next = f.thumbnail()
        val calls = f.transport.calls.size
        f.clock.throwAfterImmediate = true
        holder.get().release()
        assertEquals(SettingsError.REQUEST_FAILED, failed.results.single().error)
        assertTrue(f.clock.runLast(), "Run consumer cleanup ahead of the uncertain queued runnable")
        assertEquals(1, readers.used(), "The scheduled runnable still owns its slot reference")
        assertEquals(1, readers.waiting())
        assertEquals(calls, f.transport.calls.size)
        assertTrue(f.clock.runOne())
        f.clock.runCurrent()
        assertNotNull(next.results.single().value).close()
        f.clock.runCurrent()
        assertEquals(0, readers.used())
        assertEquals(0, readers.waiting())
        assertEquals(0L, f.budget.used())
    }

    @Test fun isolatedConsumerAndDispatchPoolsAllowEitherCallbackDependency() {
        for (aWaitsForB in listOf(false, true)) {
            val f = Fixture(initial = candidates(8), realAsync = true)
            f.transport.holdMe = true
            val firstNative = CountDownLatch(1)
            f.transport.heldMeSignal = firstNative
            val aEntered = CountDownLatch(1)
            val bEntered = CountDownLatch(1)
            val aDone = CountDownLatch(1)
            val bDone = CountDownLatch(1)
            val aTerminal = CountDownLatch(1)
            val bTerminal = CountDownLatch(1)
            val releaseA = CountDownLatch(1)
            val grants = List(8) { index ->
                assertNotNull(f.api.thumbnailMedia(f.picker, f.page.candidates[index].ticket))
            }
            f.api.resolveMediaOwned(grants[0], { true }, { aTerminal.countDown() }) {
                aEntered.countDown()
                if (aWaitsForB) assertTrue(bDone.await(3, TimeUnit.SECONDS))
                else assertTrue(releaseA.await(3, TimeUnit.SECONDS))
                aDone.countDown()
            }
            assertTrue(firstNative.await(3, TimeUnit.SECONDS))
            val aNative = f.transport.heldMe.removeFirst()
            val otherNative = CountDownLatch(6)
            f.transport.heldMeSignal = otherNative
            for (index in 1..6) f.api.resolveMediaOwned(grants[index], { true }, {}) {}
            assertTrue(otherNative.await(3, TimeUnit.SECONDS))
            f.api.resolveMediaOwned(grants[7], { true }, { bTerminal.countDown() }) {
                bEntered.countDown()
                if (!aWaitsForB) assertTrue(aDone.await(3, TimeUnit.SECONDS))
                bDone.countDown()
            }
            // The response returns on this synthetic receiver while the two result consumers run elsewhere.
            f.transport.holdMe = false
            aNative.reply(user())
            try {
                assertTrue(aEntered.await(3, TimeUnit.SECONDS))
                assertTrue(bEntered.await(3, TimeUnit.SECONDS))
                if (!aWaitsForB) {
                    assertFalse(aTerminal.await(100, TimeUnit.MILLISECONDS), "A consumer still owns its result")
                    assertFalse(bTerminal.await(100, TimeUnit.MILLISECONDS), "B is still waiting for A")
                    releaseA.countDown()
                }
                assertTrue(aDone.await(3, TimeUnit.SECONDS))
                assertTrue(bDone.await(3, TimeUnit.SECONDS))
                assertTrue(aTerminal.await(3, TimeUnit.SECONDS))
                assertTrue(bTerminal.await(3, TimeUnit.SECONDS))
            } finally {
                releaseA.countDown()
                grants.forEach(f.api::closeMedia)
                f.api.retire()
                while (f.transport.heldMe.isNotEmpty()) f.transport.heldMe.removeFirst().reply(user())
            }
        }
    }

    @Test fun fullAcrossBothHelperGatesWinsTheNativeReservation() {
        val f = Fixture(initial = candidates(8))
        f.transport.holdMe = true
        f.api.loadDefault(f.picker) {} // old catalog GetMe remains physically in flight
        val stale = f.transport.heldMe.removeFirst()
        f.transport.holdMe = false
        f.refreshCatalog()
        f.transport.holdMe = true
        val thumbnails = List(7) { f.thumbnail(it) }
        f.clock.runCurrent()
        assertEquals(6, f.transport.heldMe.size, "Old catalog and six thumbnails use seven native slots")
        val selected = f.full()
        assertEquals(8, f.readers.used())
        val before = f.transport.calls.size
        f.transport.heldMe.removeFirst().reply(user())
        f.clock.runCurrent()
        assertTrue(f.transport.calls[before].command is TdApi.GetMe, "Selected full wins before thumbnail source")
        assertEquals(before + 1, f.transport.calls.size)
        assertTrue(selected.results.isEmpty())
        (thumbnails + selected).forEach { it.lease.close() }
        f.files.retire(); f.api.retire(); f.clock.runCurrent()
        stale.reply(user())
        while (f.transport.heldMe.isNotEmpty()) f.transport.heldMe.removeFirst().reply(user())
        f.clock.runCurrent()
        assertEquals(0, f.readers.used())
    }

    @Test fun fullBarrierAlsoBlocksAConcurrentThumbnailFastPath() {
        val f = Fixture(initial = candidates(7))
        f.transport.holdMe = true
        val thumbnails = List(7) { f.thumbnail(it) }
        f.clock.runCurrent()
        val selected = f.full()
        val background = assertNotNull(ProfileStickerAvatarBackground.fromColors(listOf(0x336699)))
        val saveActive = AtomicBoolean(true)
        f.api.save(f.picker, f.selection, null, background, { saveActive.get() }) {}
        assertEquals(8, f.transport.heldMe.size)
        f.transport.holdDefaults = true
        f.transport.heldMe.removeFirst().reply(user()) // its source waits: total native is seven
        saveActive.set(false)
        f.transport.heldMe.removeLast().reply(user()) // frees Save; one thumbnail source is reserved
        f.clock.runCurrent()
        assertEquals(1, f.transport.heldDefaults.size)
        val before = f.transport.calls.size
        val gate = Clock.Gate()
        f.async.deliverySubmitGate = gate
        val wrongSelf = f.transport.heldMe.removeFirst()
        val done = CountDownLatch(1)
        Thread({ try { wrongSelf.reply(user(99)) } finally { done.countDown() } }, "metadata-finish-gate").apply {
            isDaemon = true; start()
        }
        try {
            assertTrue(gate.entered.await(2, TimeUnit.SECONDS)) // operation removed; its pump is paused
            f.transport.heldMe.removeFirst().reply(user()) // a concurrent thumbnail attempts the fast path
            f.clock.runCurrent()
            assertTrue(f.transport.calls[before].command is TdApi.GetMe, "Full reserved before the racing source")
            assertTrue(f.transport.calls[before + 1].command is TdApi.GetDefaultProfilePhotoCustomEmojiStickers)
        } finally { gate.release.countDown() }
        assertTrue(done.await(2, TimeUnit.SECONDS))
        (thumbnails + selected).forEach { it.lease.close() }
        f.files.retire(); f.api.retire(); f.clock.runCurrent()
        while (f.transport.heldMe.isNotEmpty()) f.transport.heldMe.removeFirst().reply(user())
        while (f.transport.heldDefaults.isNotEmpty()) f.transport.heldDefaults.removeFirst().reply(TdApi.Stickers(f.transport.stickers))
        f.clock.runCurrent()
        assertEquals(0, f.readers.used())
    }

    @Test fun sameRoleFifoUsesOneReservationSequenceAcrossHelperGates() {
        for (admissionFirst in listOf(true, false)) {
            val f = Fixture(initial = candidates(6))
            f.transport.holdMe = true
            f.transport.holdDefaults = true
            val thumbnails = List(6) { f.thumbnail(it) }
            val firstFull = f.full()
            f.clock.runCurrent()
            val firstFullNative = f.transport.heldMe.removeLast()
            val background = assertNotNull(ProfileStickerAvatarBackground.fromColors(listOf(0x336699)))
            val saveActive = AtomicBoolean(true)
            f.api.save(f.picker, f.selection, null, background, { saveActive.get() }) {}
            val saveNative = f.transport.heldMe.removeLast()
            val secondFull = if (admissionFirst) f.full() else null
            firstFullNative.reply(user()) // source queues while all seven media/native positions are busy
            val laterFull = secondFull ?: f.full()
            thumbnails.first().lease.close() // operation released; its native callback is still held
            val before = f.transport.calls.size
            saveActive.set(false)
            saveNative.reply(user()) // one native and operation become available together
            f.clock.runCurrent()
            val command = f.transport.calls[before].command
            if (admissionFirst) assertTrue(command is TdApi.GetMe, "Older FULL admission reserves first")
            else assertTrue(command is TdApi.GetDefaultProfilePhotoCustomEmojiStickers,
                "Older FULL source reserves before the later FULL admission")
            (thumbnails + firstFull + laterFull).forEach { it.lease.close() }
            f.files.retire(); f.api.retire(); f.clock.runCurrent()
            while (f.transport.heldMe.isNotEmpty()) f.transport.heldMe.removeFirst().reply(user())
            while (f.transport.heldDefaults.isNotEmpty()) f.transport.heldDefaults.removeFirst().reply(TdApi.Stickers(f.transport.stickers))
            f.clock.runCurrent()
            assertEquals(0, f.readers.used())
        }
    }

    @Test fun dispatchAfterExecuteAndUnknownEnqueueDoNotReleaseEarly() {
        val f = Fixture()
        val grant = assertNotNull(f.api.thumbnailMedia(f.picker, f.candidate.ticket))
        val terminals = AtomicInteger()
        val results = mutableListOf<SettingsResult<ProfileStickerAvatarOwnedMedia>>()
        f.async.holdDispatchExit = true
        f.api.resolveMediaOwned(grant, { true }, { terminals.incrementAndGet() }, results::add)
        f.clock.runCurrent()
        assertNotNull(results.single().value)
        assertEquals(2, f.async.dispatchExits.size)
        assertEquals(0, terminals.get(), "Both producer afterExecute calls are still held")
        val firstExit = f.async.dispatchExits.removeFirst()
        firstExit(); firstExit()
        assertEquals(0, terminals.get())
        f.async.dispatchExits.removeFirst().invoke()
        assertEquals(1, terminals.get())
        f.api.closeMedia(grant)

        val uncertain = Fixture()
        uncertain.async.throwAfterDispatchEnqueue = true
        val calls = uncertain.transport.calls.size
        val read = uncertain.thumbnail()
        read.lease.close()
        assertTrue(uncertain.clock.runLast()) // release consumer base before uncertain dispatch executes
        assertEquals(1, uncertain.readers.used())
        uncertain.clock.runCurrent()
        assertEquals(SettingsError.NOT_READY, read.results.single().error)
        assertEquals(calls, uncertain.transport.calls.size, "The cancelled queued dispatch never sends")
        assertEquals(0, uncertain.readers.used())

        val rejected = Fixture()
        rejected.async.rejectNextDispatch = true
        val before = rejected.transport.calls.size
        val denied = rejected.thumbnail()
        rejected.clock.runCurrent()
        assertEquals(SettingsError.RATE_LIMITED, denied.results.single().error)
        assertEquals(before, rejected.transport.calls.size)
        assertEquals(0, rejected.readers.used(), "Known rejection never owned a native send")

        val synchronous = Fixture()
        synchronous.transport.throwAfterReply = true
        val accepted = synchronous.thumbnail()
        synchronous.clock.runCurrent()
        assertNotNull(accepted.results.single().value).close()
        synchronous.clock.runCurrent()
        assertEquals(0, synchronous.readers.used(), "A throw after the real callback does not settle twice")
    }

    @Test fun consumerAfterExecuteAndUnknownDeliveryEnqueueKeepTerminalOwned() {
        val f = Fixture()
        val grant = assertNotNull(f.api.thumbnailMedia(f.picker, f.candidate.ticket))
        val terminals = AtomicInteger()
        val results = mutableListOf<SettingsResult<ProfileStickerAvatarOwnedMedia>>()
        f.async.holdConsumerExit = true
        f.async.throwAfterDeliveryEnqueue = true
        f.api.resolveMediaOwned(grant, { true }, { terminals.incrementAndGet() }, results::add)
        f.clock.runCurrent()
        assertNotNull(results.single().value)
        assertEquals(1, f.async.consumerExits.size)
        assertEquals(0, terminals.get(), "Consumer runnable has not completed afterExecute")
        val exited = f.async.consumerExits.removeFirst()
        exited(); exited()
        assertEquals(1, terminals.get())
        f.api.closeMedia(grant)

        val rejected = Fixture()
        rejected.async.maxConsumerTickets = 0
        val read = rejected.thumbnail()
        rejected.clock.runCurrent()
        assertEquals(SettingsError.RATE_LIMITED, read.results.single().error)
        assertEquals(0, rejected.readers.used(), "Pre-admission BUSY released the unissued metadata retain")
    }

    @Test fun consumerAdmissionIsBoundedAcrossOldDirectAndNewServiceWithoutDroppingAcceptedResults() {
        val old = Fixture()
        old.transport.holdMe = true
        val grant = assertNotNull(old.api.thumbnailMedia(old.picker, old.candidate.ticket))
        val results = mutableListOf<SettingsResult<ProfileStickerAvatarOwnedMedia>>()
        repeat(128) {
            assertEquals(ProfileStickerAvatarMediaAdmission.ACCEPTED,
                old.api.resolveMediaOwned(grant, { true }, {}, results::add))
        }
        assertEquals(128, old.async.usedConsumers())
        val directReturned = CountDownLatch(1)
        val directResults = mutableListOf<SettingsResult<ProfileStickerAvatarOwnedMedia>>()
        Thread({
            old.api.resolveMedia(grant, { true }, directResults::add)
            directReturned.countDown()
        }, "legacy-direct-caller").apply { isDaemon = true; start() }
        assertTrue(directReturned.await(2, TimeUnit.SECONDS))
        assertEquals(SettingsError.RATE_LIMITED, directResults.single().error,
            "The legacy callback contract reports definite pre-admission BUSY once")

        // A new service shares the same async budget, just as a new engine generation does.
        val newer = Fixture(asyncOverride = old.async)
        val before = newer.transport.calls.size
        val denied = List(8) { newer.thumbnail() }
        newer.clock.runCurrent()
        assertTrue(denied.all { it.results.single().error == SettingsError.RATE_LIMITED })
        assertEquals(before, newer.transport.calls.size)
        assertEquals(0, newer.readers.used(), "Unadmitted metadata never holds a physical slot")
        old.clock.runCurrent() // the explicitly rejected old helper admissions deliver and return tickets
        assertTrue(old.async.usedConsumers() < 128)
        val accepted = newer.thumbnail()
        old.clock.runCurrent(); newer.clock.runCurrent()
        old.clock.runCurrent(); newer.clock.runCurrent()
        assertNotNull(accepted.results.single().value).close()
        newer.clock.runCurrent()
        old.api.closeMedia(grant); old.api.retire(); old.clock.runCurrent()
        while (old.transport.heldMe.isNotEmpty()) old.transport.heldMe.removeFirst().reply(user())
        old.clock.runCurrent()
        assertEquals(128, results.size, "Every accepted direct call received exactly one result")
        assertEquals(0, old.async.usedConsumers())
    }

    @Test fun queuedSuccessRechecksTheOriginalGrantAndOwnerAtDelivery() {
        for (replaceOwner in listOf(false, true)) {
            val f = Fixture()
            val grant = assertNotNull(f.api.thumbnailMedia(f.picker, f.candidate.ticket))
            val results = mutableListOf<SettingsResult<ProfileStickerAvatarOwnedMedia>>()
            val terminals = AtomicInteger()
            f.async.holdDeliveryActions = true
            assertEquals(ProfileStickerAvatarMediaAdmission.ACCEPTED,
                f.api.resolveMediaOwned(grant, { true }, { terminals.incrementAndGet() }, results::add))
            f.clock.runCurrent()
            assertEquals(1, f.async.deliveryActions.size)
            assertTrue(results.isEmpty())
            if (replaceOwner) assertNotNull(f.api.open(43) { true }) else f.api.closeMedia(grant)
            f.async.deliveryActions.removeFirst().invoke()
            f.clock.runCurrent()
            assertEquals(SettingsError.NOT_READY, results.single().error)
            assertNull(results.single().value)
            assertEquals(1, terminals.get())
            assertEquals(0, f.async.usedConsumers())
        }
    }

    private fun candidates(count: Int): Array<TdApi.Sticker> = Array(count) { index ->
        sticker(-700L - index, 1000 + 2 * index, 1001 + 2 * index)
    }

    private class Request(val lease: ProfileStickerAvatarReadLease,
        val results: MutableList<SettingsResult<ProfileStickerAvatarEncoded>>)

    private class Fixture(
        initial: Array<TdApi.Sticker> = arrayOf(sticker()),
        val budget: ProfileStickerAvatarEncodedBudget = ProfileStickerAvatarEncodedBudget(),
        val readers: ProfileStickerAvatarReaderBudget = ProfileStickerAvatarReaderBudget(),
        scheduler: ((Long, () -> Unit) -> Unit)? = null,
        brokerDispatcher: ((() -> Unit) -> Unit)? = null,
        realAsync: Boolean = false,
        asyncOverride: ProfileStickerAvatarMediaAsync? = null,
    ) {
        val clock = Clock()
        private val schedule = scheduler ?: clock::schedule
        private val dispatch: (() -> Unit) -> Unit = brokerDispatcher ?: { action -> schedule(0, action) }
        val transport = Transport(initial)
        val async = ManualAsync(clock)
        val api = if (realAsync) OfficialTdProfileStickerAvatar(transport::send)
            else OfficialTdProfileStickerAvatar(asyncOverride ?: async, transport::send)
        val picker = assertNotNull(api.open(43) { true })
        val native = Native()
        val broker = TdDownloadBroker(native, schedule, { clock.now }, dispatch)
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
            candidate = page.candidates.first()
            selection = assertNotNull(api.select(picker, candidate.ticket))
        }

        fun fullSize(size: Int) {
            val file = checkNotNull(sticker.sticker)
            file.size = size.toLong(); file.expectedSize = size.toLong()
            file.local!!.isDownloadingCompleted = true; file.local!!.downloadedSize = size.toLong()
            native.files[file.id] = mapMediaFile(file)
            native.bytes[file.id] = ByteArray(size) { (it + file.id).toByte() }
            refreshCatalog()
        }

        fun thumbnail(index: Int = 0, active: () -> Boolean = { true }): Request {
            val output = mutableListOf<SettingsResult<ProfileStickerAvatarEncoded>>()
            val lease = files.thumbnail(picker, page.candidates[index].ticket, active, output::add)
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
        val calls = Collections.synchronizedList(mutableListOf<Call>())
        val heldMe = ConcurrentLinkedDeque<Call>()
        val heldDefaults = ConcurrentLinkedDeque<Call>()
        @Volatile var holdMe = false
        @Volatile var holdDefaults = false
        @Volatile var heldMeSignal: CountDownLatch? = null
        var throwAfterHeld = false
        var throwAfterReply = false
        var writes = 0
        var selfId = 43L
        private val defaultCount = AtomicInteger()
        var asyncDefaultNumber: Int? = null
        var pack: TdApi.StickerSet? = null
        fun send(command: TdApi.Function<*>, reply: (TdApi.Object) -> Unit, fail: () -> Unit) {
            val call = Call(command, reply, fail)
            calls += call
            when (command) {
                is TdApi.GetMe -> if (holdMe) {
                    heldMe.addLast(call)
                    heldMeSignal?.countDown()
                    if (throwAfterHeld) { throwAfterHeld = false; throw IllegalStateException("unknown native dispatch") }
                } else {
                    reply(user(selfId))
                    if (throwAfterReply) { throwAfterReply = false; throw IllegalStateException("after synchronous callback") }
                }
                is TdApi.GetDefaultProfilePhotoCustomEmojiStickers -> {
                    val callNumber = defaultCount.incrementAndGet()
                    if (holdDefaults) { heldDefaults.addLast(call); return }
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
                is TdApi.SetProfilePhoto -> { writes++; reply(TdApi.Ok()) }
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

    private class ManualAsync(private val clock: Clock) : ProfileStickerAvatarMediaAsync {
        var holdDispatchExit = false
        var throwAfterDispatchEnqueue = false
        var rejectNextDispatch = false
        var throwAfterDeliveryEnqueue = false
        var holdConsumerExit = false
        var holdDeliveryActions = false
        var deliverySubmitGate: Clock.Gate? = null
        val dispatchExits = ArrayDeque<() -> Unit>()
        val consumerExits = ArrayDeque<() -> Unit>()
        val deliveryActions = ArrayDeque<() -> Unit>()
        private val consumerLock = Any()
        var maxConsumerTickets = 128
        private var consumerTickets = 0
        fun usedConsumers(): Int = synchronized(consumerLock) { consumerTickets }

        override fun dispatch(action: () -> Unit, exited: () -> Unit, rejected: () -> Unit) {
            if (rejectNextDispatch) { rejectNextDispatch = false; rejected(); return }
            clock.schedule(0) {
                try { action() } finally {
                    if (holdDispatchExit) dispatchExits.addLast(exited) else clock.schedule(0, exited)
                }
            }
            if (throwAfterDispatchEnqueue) { throwAfterDispatchEnqueue = false; throw IllegalStateException("enqueued") }
        }

        override fun reserveConsumer(): ProfileStickerAvatarMediaConsumerPermit? = synchronized(consumerLock) {
            if (consumerTickets >= maxConsumerTickets) null else {
                consumerTickets++
                object : ProfileStickerAvatarMediaConsumerPermit {
                    private val submitted = AtomicBoolean()
                    private val released = AtomicBoolean()
                    override fun deliver(action: () -> Unit, exited: () -> Unit) {
                        check(submitted.compareAndSet(false, true))
                        val after = {
                            try { exited() } finally {
                                if (released.compareAndSet(false, true)) synchronized(consumerLock) { consumerTickets-- }
                            }
                        }
                        val task = {
                            try { action() } finally {
                                if (holdConsumerExit) consumerExits.addLast(after) else clock.schedule(0, after)
                            }
                        }
                        if (holdDeliveryActions) deliveryActions.addLast(task) else clock.schedule(0, task)
                        if (throwAfterDeliveryEnqueue) {
                            throwAfterDeliveryEnqueue = false
                            throw IllegalStateException("delivery enqueued")
                        }
                        val gate = deliverySubmitGate
                        deliverySubmitGate = null
                        if (gate != null) { gate.entered.countDown(); gate.release.await(3, TimeUnit.SECONDS) }
                    }
                }
            }
        }
    }

    private class Clock {
        class Gate(val entered: CountDownLatch = CountDownLatch(1), val release: CountDownLatch = CountDownLatch(1))
        @Volatile var now = 0L
        @Volatile var blockNextImmediate: Gate? = null
        @Volatile var throwAfterImmediate = false
        private val queued = mutableListOf<Pair<Long, () -> Unit>>()
        fun schedule(delay: Long, action: () -> Unit) {
            val gate = synchronized(queued) {
                queued += now + delay to action
                if (delay == 0L) blockNextImmediate.also { blockNextImmediate = null } else null
            }
            if (delay == 0L && throwAfterImmediate) {
                throwAfterImmediate = false
                throw IllegalStateException("unknown scheduler enqueue")
            }
            if (gate != null) { gate.entered.countDown(); gate.release.await(3, TimeUnit.SECONDS) }
        }
        fun runCurrent() = advance(0)
        fun runOne(): Boolean {
            val next = synchronized(queued) {
                queued.filter { it.first <= now }.minByOrNull { it.first }?.also { queued.remove(it) }
            } ?: return false
            next.second()
            return true
        }
        fun runLast(): Boolean {
            val next = synchronized(queued) {
                queued.indexOfLast { it.first <= now }.let { index ->
                    if (index < 0) null else queued.removeAt(index)
                }
            } ?: return false
            next.second()
            return true
        }
        fun advance(delay: Long) {
            val target = now + delay
            repeat(20_000) {
                val next = synchronized(queued) {
                    queued.filter { it.first <= target }.minByOrNull { it.first }?.also { queued.remove(it) }
                } ?: run { now = target; return }
                now = next.first; next.second()
            }
            error("Unbounded fake scheduling")
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
