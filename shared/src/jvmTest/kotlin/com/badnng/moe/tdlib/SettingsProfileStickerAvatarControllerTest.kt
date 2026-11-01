package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.*

/** Synthetic actor/native-session boundaries; no account or complete builder UI. */
class SettingsProfileStickerAvatarControllerTest {
    private fun defaultActionsRejectOnceWithoutInventingAMutationResult() {
        val actions = SettingsProfileStickerAvatarActions()
        val parent = SettingsProfileExtrasToken(7, 1, 43)
        val failures = mutableListOf<SettingsError?>()
        actions.open(parent, { true }) { failures += it.error }
        assertEquals(listOf<SettingsError?>(SettingsError.UNSUPPORTED), failures)
        val admitted = mutableListOf<Long?>()
        val results = mutableListOf<ProfileStickerAvatarMutationResult>()
        actions.save(parent, ProfileStickerAvatarPickerTicket(), ProfileStickerAvatarSelectionTicket(),
            91, background(), { true }, admitted::add, results::add)
        assertEquals(listOf<Long?>(null), admitted)
        assertTrue(results.isEmpty())
    }

    @Test fun ownerRequiresCurrentParentAndCloseWorksAfterLeaseRevocation() {
        defaultActionsRejectOnceWithoutInventingAMutationResult()
        val f = Fixture()
        val denied = mutableListOf<SettingsResult<ProfileStickerAvatarPickerTicket>>()
        f.controller.open(f.parent.copy(owner = 2), { true }, denied::add)
        assertEquals(SettingsError.NOT_READY, denied.single().error)
        assertTrue(f.native.opens.isEmpty())
        val picker = f.open()
        val nativeLease = f.native.opens.single().active
        assertTrue(f.onWorkerValue(nativeLease))
        f.parentLease.set(false)
        assertFalse(f.onWorkerValue(nativeLease))
        f.parentLease.set(true)
        assertFalse(f.onWorkerValue(nativeLease), "An observed false never revives the old native lease")
        f.controller.close(f.parent, picker)
        assertEquals(listOf(picker), f.native.closes)
    }

    @Test fun laterCatalogQueryOwnsPublicationAndOldOrDuplicateRepliesAreNotReadyOnce() {
        val f = Fixture()
        val picker = f.open()
        val first = mutableListOf<SettingsResult<ProfileStickerAvatarCandidatePage>>()
        val second = mutableListOf<SettingsResult<ProfileStickerAvatarPackPage>>()
        f.controller.loadDefault(f.parent, picker, { true }, first::add)
        f.controller.loadInstalledPacks(f.parent, picker, ProfileStickerAvatarPackKind.CUSTOM_EMOJI, { true }, second::add)
        f.native.defaultReads.single()(SettingsResult(candidatePage()))
        f.native.packReads.single()(SettingsResult(ProfileStickerAvatarPackPage(emptyList(), false)))
        f.native.defaultReads.single()(SettingsResult(candidatePage()))
        f.drain()
        assertEquals(SettingsError.NOT_READY, first.single().error)
        assertNotNull(second.single().value)
        assertEquals(1, first.size)
        val queryLease = AtomicBoolean(true)
        val third = mutableListOf<SettingsResult<ProfileStickerAvatarCandidatePage>>()
        f.controller.openPack(f.parent, picker, ProfileStickerAvatarPackTicket(), queryLease::get, third::add)
        queryLease.set(false)
        f.native.setReads.single()(SettingsResult(candidatePage()))
        f.drain()
        queryLease.set(true)
        f.native.setReads.single()(SettingsResult(candidatePage()))
        f.drain()
        assertEquals(SettingsError.NOT_READY, third.single().error)
    }

    @Test fun closeCompletesEveryConsumerEvenWhenNativeCallbackWasQueuedOrDropped() {
        val f = Fixture()
        val picker = f.open()
        val outcomes = List(2) { mutableListOf<SettingsResult<ProfileStickerAvatarCandidatePage>>() }
        repeat(2) { index -> f.controller.loadDefault(f.parent, picker, { true }, outcomes[index]::add) }
        f.native.defaultReads.first()(SettingsResult(candidatePage())) // queued but not yet delivered
        f.dropQueue.set(true)
        f.native.defaultReads.last()(SettingsResult(candidatePage())) // callback's actor work is discarded
        f.controller.close(f.parent, picker)
        assertTrue(outcomes.all { it.single().error == SettingsError.NOT_READY })
        assertEquals(2, f.native.defaultReads.size, "Closing consumers does not remove the fake native requests")
        f.dropQueue.set(false)
        f.drain()
        f.native.defaultReads.forEach { it(SettingsResult(candidatePage())) }
        f.drain()
        assertTrue(outcomes.all { it.size == 1 })
        assertEquals(listOf(picker), f.native.closes)
    }

    @Test fun readConsumerBudgetIsEightAndOldCompletionCannotPublishNewQuery() {
        val f = Fixture()
        val picker = f.open()
        val outcomes = List(9) { mutableListOf<SettingsResult<ProfileStickerAvatarCandidatePage>>() }
        repeat(9) { index -> f.controller.loadDefault(f.parent, picker, { true }, outcomes[index]::add) }
        assertEquals(8, f.native.defaultReads.size)
        assertEquals(SettingsError.NOT_READY, outcomes.last().single().error)
        f.native.defaultReads.first()(SettingsResult(candidatePage()))
        f.drain()
        assertEquals(SettingsError.NOT_READY, outcomes.first().single().error)
        f.controller.loadDefault(f.parent, picker, { true }) {}
        assertEquals(9, f.native.defaultReads.size)
    }

    @Test fun selectionMustComeFromSessionAndChangingCatalogInvalidatesTheOldSave() {
        val f = Fixture()
        val picker = f.open()
        val denied = mutableListOf<SettingsResult<ProfileStickerAvatarSelectionTicket>>()
        f.controller.select(f.parent, picker, ProfileStickerAvatarCandidateTicket(), { true }, denied::add)
        assertEquals(SettingsError.CONFLICT, denied.single().error)
        val selection = f.select(picker)
        val selecting = AtomicBoolean(true)
        f.native.onSelect = { selecting.set(false); ProfileStickerAvatarSelectionTicket() }
        val abandoned = mutableListOf<SettingsResult<ProfileStickerAvatarSelectionTicket>>()
        f.controller.select(f.parent, picker, f.native.candidate, selecting::get, abandoned::add)
        assertEquals(SettingsError.NOT_READY, abandoned.single().error)
        val afterAbandoned = mutableListOf<Long?>()
        f.controller.save(f.parent, picker, selection, 91, background(), { true }, afterAbandoned::add) {}
        assertEquals(listOf<Long?>(null), afterAbandoned)
        f.controller.loadDefault(f.parent, picker, { true }) {}
        val admitted = mutableListOf<Long?>()
        f.controller.save(f.parent, picker, selection, 91, background(), { true }, admitted::add) {}
        assertEquals(listOf<Long?>(null), admitted)
        assertEquals(0, f.gate.calls)
        assertTrue(f.native.writes.isEmpty())
    }

    @Test fun sameSourceWindowSuspendsOldMediaAndSaveUntilTheNewPageIsValidated() {
        val f = Fixture()
        val picker = f.open()
        val selection = f.select(picker)
        val oldMedia = assertNotNull(f.controller.captureMedia(f.parent, picker, selection))
        val outcome = mutableListOf<SettingsResult<ProfileStickerAvatarCatalogWindow>>()
        f.controller.loadWindow(f.parent, picker, ProfileStickerAvatarCatalogCursor(), { true }, outcome::add)
        assertFalse(oldMedia())
        assertNull(f.controller.captureMedia(f.parent, picker, selection))
        val blocked = mutableListOf<Long?>()
        f.controller.save(f.parent, picker, selection, 91, background(), { true }, blocked::add) {}
        assertEquals(listOf<Long?>(null), blocked)
        assertTrue(f.native.writes.isEmpty())
        f.native.windowReads.single()(SettingsResult(ProfileStickerAvatarCatalogWindow.Candidates(candidatePage())))
        f.drain()
        assertNotNull(outcome.single().value)
        assertNotNull(f.controller.captureMedia(f.parent, picker, selection))
        val admitted = mutableListOf<Long?>()
        f.controller.save(f.parent, picker, selection, 91, background(), { true }, admitted::add) {}
        assertEquals(listOf<Long?>(101L), admitted)
        assertEquals(1, f.native.writes.size)

        val rejected = mutableListOf<SettingsResult<ProfileStickerAvatarCatalogWindow>>()
        f.controller.loadWindow(f.parent, picker, ProfileStickerAvatarCatalogCursor(), { true }, rejected::add)
        f.native.windowReads.last()(SettingsResult(error = SettingsError.CONFLICT))
        f.drain()
        assertEquals(SettingsError.CONFLICT, rejected.single().error)
        assertNull(f.controller.captureMedia(f.parent, picker, selection))
        val stale = mutableListOf<Long?>()
        f.controller.save(f.parent, picker, selection, 91, background(), { true }, stale::add) {}
        assertEquals(listOf<Long?>(null), stale)
        assertEquals(1, f.native.writes.size)
    }

    @Test fun gateChecksPhotoAndReturnsActualRevisionEvenWhenResultArrivesFirst() {
        val f = Fixture()
        val picker = f.open()
        val selection = f.select(picker)
        val rejected = mutableListOf<Long?>()
        f.controller.save(f.parent, picker, selection, 92, background(), { true }, rejected::add) {}
        assertEquals(listOf<Long?>(null), rejected)
        assertEquals(0, f.gate.calls)
        f.native.onSave = { it(SettingsResult(Unit)); it(SettingsResult(error = SettingsError.FORBIDDEN)) }
        val events = mutableListOf<String>()
        f.controller.save(f.parent, picker, selection, 91, background(), { true },
            { events += "admitted:$it" }, { events += "result:${it.revision}:${it.error}" })
        assertEquals(listOf("result:101:null", "admitted:101"), events)
        assertEquals(1, f.gate.calls)
        assertEquals(91L, f.gate.expected.single())
    }

    @Test fun lateWriteResultRetainsOriginalIdentityAfterCloseAndCallbacksMayThrow() {
        val f = Fixture()
        val picker = f.open()
        val selection = f.select(picker)
        val admissions = mutableListOf<Long?>()
        val results = mutableListOf<ProfileStickerAvatarMutationResult>()
        f.controller.save(f.parent, picker, selection, 91, background(), { true },
            { admissions += it; error("UI admission failure") },
            { results += it; error("UI result failure") })
        val write = f.native.writes.single()
        f.controller.close(f.parent, picker)
        assertFalse(f.onWorkerValue(write.current))
        f.onWorker { write.done(SettingsResult(Unit)); write.done(SettingsResult(error = SettingsError.CONFLICT)) }
        assertEquals(listOf<Long?>(101L), admissions)
        assertEquals(ProfileStickerAvatarMutationResult(f.parent, 101, null), results.single())
        assertEquals(1, results.size)
    }

    @Test fun queryAndSaveLeasesAreOneWayAndNeverReadActorPredicatesOnNativeThreads() {
        val f = Fixture()
        val picker = f.open()
        val selection = f.select(picker)
        val saveActive = AtomicBoolean(true)
        f.controller.save(f.parent, picker, selection, 91, background(), saveActive::get, {}, {})
        val nativeCurrent = f.native.writes.single().current
        assertTrue(f.onWorkerValue(nativeCurrent))
        saveActive.set(false)
        assertFalse(f.onWorkerValue(nativeCurrent))
        saveActive.set(true)
        assertFalse(f.onWorkerValue(nativeCurrent))
        assertTrue(f.actorChecks > 0)
        assertFalse(f.wrongThreadActorCheck.get())
    }

    @Test fun reentrantOpenCannotOverwriteTheNewerOwner() {
        val f = Fixture()
        val original = f.open()
        var nested: ProfileStickerAvatarPickerTicket? = null
        val first = mutableListOf<SettingsResult<ProfileStickerAvatarCandidatePage>>()
        f.controller.loadDefault(f.parent, original, { true }) { response ->
            first += response
            f.controller.open(f.parent, { true }) { nested = it.value }
        }
        val outer = mutableListOf<SettingsResult<ProfileStickerAvatarPickerTicket>>()
        f.controller.open(f.parent, { true }, outer::add)
        assertEquals(SettingsError.NOT_READY, first.single().error)
        assertEquals(SettingsError.NOT_READY, outer.single().error)
        val newest = assertNotNull(nested)
        assertEquals(2, f.native.opens.size)
        val pending = mutableListOf<SettingsResult<ProfileStickerAvatarCandidatePage>>()
        f.controller.loadDefault(f.parent, newest, { true }, pending::add)
        assertTrue(pending.isEmpty())
        assertEquals(2, f.native.defaultReads.size)
    }

    @Test fun reentrantSelectionCannotReplaceTheNewerSelectionWithAnOlderReturnValue() {
        val f = Fixture()
        val picker = f.open()
        val first = ProfileStickerAvatarCandidateTicket()
        val second = ProfileStickerAvatarCandidateTicket()
        val innerTicket = ProfileStickerAvatarSelectionTicket()
        val outerTicket = ProfileStickerAvatarSelectionTicket()
        val inner = mutableListOf<SettingsResult<ProfileStickerAvatarSelectionTicket>>()
        f.native.onSelect = { candidate ->
            if (candidate === first) {
                f.controller.select(f.parent, picker, second, { true }, inner::add)
                outerTicket
            } else innerTicket
        }
        val outer = mutableListOf<SettingsResult<ProfileStickerAvatarSelectionTicket>>()
        f.controller.select(f.parent, picker, first, { true }, outer::add)
        assertSame(innerTicket, inner.single().value)
        assertEquals(SettingsError.NOT_READY, outer.single().error)
        val admitted = mutableListOf<Long?>()
        f.controller.save(f.parent, picker, innerTicket, 91, background(), { true }, admitted::add) {}
        assertEquals(listOf<Long?>(101L), admitted)
    }

    private class Fixture {
        val parent = SettingsProfileExtrasToken(7, 1, 43)
        val parentLease = AtomicBoolean(true)
        val dropQueue = AtomicBoolean()
        val wrongThreadActorCheck = AtomicBoolean()
        val actorThread = Thread.currentThread()
        var actorChecks = 0
        val queue = ConcurrentLinkedQueue<() -> Unit>()
        val native = Native()
        val gate = Gate()
        val controller = SettingsProfileStickerAvatarController(native,
            { if (!dropQueue.get()) queue.add(it) },
            allows = { checked -> actorCheck(); checked == parent && parentLease.get() },
            captureParent = { checked -> actorCheck(); if (checked == parent) parentLease::get else null },
            allowsSave = { checked, photo -> actorCheck(); checked == parent && photo == 91L },
            mutate = gate,
        )

        fun actorCheck() {
            if (Thread.currentThread() !== actorThread) wrongThreadActorCheck.set(true)
            actorChecks++
        }
        fun open(): ProfileStickerAvatarPickerTicket {
            val results = mutableListOf<SettingsResult<ProfileStickerAvatarPickerTicket>>()
            controller.open(parent, { true }, results::add)
            return assertNotNull(results.single().value)
        }
        fun select(picker: ProfileStickerAvatarPickerTicket): ProfileStickerAvatarSelectionTicket {
            val results = mutableListOf<SettingsResult<ProfileStickerAvatarSelectionTicket>>()
            controller.select(parent, picker, native.candidate, { true }, results::add)
            return assertNotNull(results.single().value)
        }
        fun drain() { while (true) (queue.poll() ?: break).invoke() }
        fun onWorker(action: () -> Unit) {
            val done = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            val worker = thread(isDaemon = true) {
                try { action() } catch (error: Throwable) { failure.set(error) } finally { done.countDown() }
            }
            assertTrue(done.await(2, TimeUnit.SECONDS)); worker.join()
            failure.get()?.let { throw it }
        }
        fun onWorkerValue(action: () -> Boolean): Boolean {
            val value = AtomicReference<Boolean>()
            onWorker { value.set(action()) }
            return value.get()
        }
    }

    private class Gate : ProfileStickerAvatarMutationGate {
        var calls = 0
        var nextRevision = 101L
        val expected = mutableListOf<Long?>()
        override fun submit(parent: SettingsProfileExtrasToken, expectedPhotoId: Long?, ownsMutation: () -> Boolean,
            observed: (Long, SettingsResult<Unit>) -> Unit,
            work: (() -> Boolean, (SettingsResult<Unit>) -> Unit) -> Unit,
        ): Long? {
            calls++
            expected += expectedPhotoId
            if (!ownsMutation()) return null
            val revision = nextRevision++
            work(ownsMutation) { observed(revision, it) }
            return revision
        }
    }

    private class Native : TdProfileStickerAvatarSession {
        data class Open(val ticket: ProfileStickerAvatarPickerTicket, val active: () -> Boolean)
        data class Write(val current: () -> Boolean, val done: (SettingsResult<Unit>) -> Unit)
        val opens = mutableListOf<Open>()
        val closes = mutableListOf<ProfileStickerAvatarPickerTicket>()
        val defaultReads = mutableListOf<(SettingsResult<ProfileStickerAvatarCandidatePage>) -> Unit>()
        val packReads = mutableListOf<(SettingsResult<ProfileStickerAvatarPackPage>) -> Unit>()
        val setReads = mutableListOf<(SettingsResult<ProfileStickerAvatarCandidatePage>) -> Unit>()
        val windowReads = mutableListOf<(SettingsResult<ProfileStickerAvatarCatalogWindow>) -> Unit>()
        val writes = mutableListOf<Write>()
        val candidate = ProfileStickerAvatarCandidateTicket()
        var onSave: (((SettingsResult<Unit>) -> Unit) -> Unit)? = null
        var onSelect: ((ProfileStickerAvatarCandidateTicket) -> ProfileStickerAvatarSelectionTicket?)? = null

        override fun open(userId: Long, active: () -> Boolean): ProfileStickerAvatarPickerTicket =
            ProfileStickerAvatarPickerTicket().also { opens += Open(it, active) }
        override fun close(ticket: ProfileStickerAvatarPickerTicket) { closes += ticket }
        override fun retire() {}
        override fun loadDefault(ticket: ProfileStickerAvatarPickerTicket, result: (SettingsResult<ProfileStickerAvatarCandidatePage>) -> Unit) {
            defaultReads += result
        }
        override fun loadInstalledPacks(ticket: ProfileStickerAvatarPickerTicket, kind: ProfileStickerAvatarPackKind,
            result: (SettingsResult<ProfileStickerAvatarPackPage>) -> Unit,
        ) { packReads += result }
        override fun openPack(ticket: ProfileStickerAvatarPickerTicket, packTicket: ProfileStickerAvatarPackTicket,
            result: (SettingsResult<ProfileStickerAvatarCandidatePage>) -> Unit,
        ) { setReads += result }
        override fun loadWindow(ticket: ProfileStickerAvatarPickerTicket, cursor: ProfileStickerAvatarCatalogCursor,
            result: (SettingsResult<ProfileStickerAvatarCatalogWindow>) -> Unit,
        ) { windowReads += result }
        override fun select(ticket: ProfileStickerAvatarPickerTicket, candidateTicket: ProfileStickerAvatarCandidateTicket): ProfileStickerAvatarSelectionTicket? =
            onSelect?.invoke(candidateTicket) ?: if (candidateTicket === candidate) ProfileStickerAvatarSelectionTicket() else null
        override fun save(ticket: ProfileStickerAvatarPickerTicket, selectionTicket: ProfileStickerAvatarSelectionTicket,
            expectedPhotoId: Long?, background: ProfileStickerAvatarBackground, active: () -> Boolean,
            result: (SettingsResult<Unit>) -> Unit,
        ) { writes += Write(active, result); onSave?.invoke(result) }
    }

    private companion object {
        fun background() = checkNotNull(ProfileStickerAvatarBackground.fromColors(listOf(1, 2)))
        fun candidatePage() = ProfileStickerAvatarCandidatePage(emptyList(), false)
    }
}
