package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import kotlin.test.*

class SettingsStoragePolicyControllerTest {
    @Test fun constructionAndClosingAreLazyAndOnlyAnActiveOpenReadsTheFiveOptionPolicy() {
        val f = Fixture()
        assertTrue(f.session.reads.isEmpty())
        f.controller.open { false }
        assertTrue(f.session.reads.isEmpty())
        f.open()
        val read = f.session.reads.single()
        assertTrue(read.active())
        val token = f.state.token
        f.controller.close(token)
        assertFalse(read.active())
        read.done(SettingsStoragePolicyRead(values())); f.drain()
        assertFalse(f.state.opened)
        assertTrue(f.session.saves.isEmpty())
    }

    @Test fun staleRevisionAndUnknownValuesCannotBeginASaveAndExplicitRefreshRestoresEligibility() {
        val f = Fixture(); f.loaded()
        val results = mutableListOf<SettingsStoragePolicyCompletion>()
        f.controller.save(f.state.token, f.state.revision - 1, SettingsStoragePolicyDraft(sizeKiB = 200000), 1, results::add)
        assertEquals(SettingsError.CONFLICT, results.single().error)
        assertTrue(f.session.saves.isEmpty())
        f.controller.refresh(f.state.token)
        f.session.reads.last().done(SettingsStoragePolicyRead(errors = mapOf(SettingsStoragePolicyField.IMMUNITY to SettingsError.REQUEST_FAILED)))
        f.drain()
        assertTrue(f.state.refreshRequired)
        f.controller.save(f.state.token, f.state.revision, SettingsStoragePolicyDraft(automatic = true), 2, results::add)
        assertEquals(SettingsError.CONFLICT, results.last().error)
        assertTrue(f.session.saves.isEmpty())
        f.controller.refresh(f.state.token)
        f.session.reads.last().done(SettingsStoragePolicyRead(values())); f.drain()
        f.controller.save(f.state.token, f.state.revision, SettingsStoragePolicyDraft(automatic = true), 3, results::add)
        assertEquals(1, f.session.saves.size)
    }

    @Test fun aSavePublishesItsActualReceiptOnceAndLateProgressCannotReplaceTheTerminalSteps() {
        val f = Fixture(); f.loaded()
        val token = f.state.token
        val before = f.state.revision
        val results = mutableListOf<SettingsStoragePolicyCompletion>()
        f.controller.save(token, before, SettingsStoragePolicyDraft(automatic = true), 7, results::add)
        val save = f.session.saves.single()
        val steps = listOf(SettingsStoragePolicyStepResult(SettingsStoragePolicyStep.ENABLE_AUTOMATIC, true, true))
        save.progress(steps); f.drain()
        assertTrue(f.state.saving)
        val actual = SettingsStoragePolicyRead(values(true))
        save.done(SettingsStoragePolicySaveOutcome(SettingsStoragePolicyStatus.CONFIRMED, steps, actual))
        assertEquals(token, results.single().token)
        assertEquals(7L, results.single().requestId)
        assertTrue(f.state.saving, "Receipts must not depend on actor publication having already happened.")
        f.drain()
        assertFalse(f.state.saving)
        assertEquals(actual.values, f.state.values)
        assertTrue(f.state.revision > before)
        assertEquals(results.single(), f.state.lastCompletion)
        save.progress(emptyList())
        save.done(SettingsStoragePolicySaveOutcome(SettingsStoragePolicyStatus.INCOMPLETE, error = SettingsError.REQUEST_FAILED))
        f.drain()
        assertEquals(1, results.size)
        assertEquals(steps, f.state.steps)
    }

    @Test fun replacementOwnerWaitsForTheOriginalResultAndThenReadsItsRealPartialEffects() {
        val f = Fixture(); f.loaded()
        val old = f.state.token
        val results = mutableListOf<SettingsStoragePolicyCompletion>()
        f.controller.save(old, f.state.revision, SettingsStoragePolicyDraft(sizeKiB = 200000), 4, results::add)
        val pending = f.session.saves.single()
        f.controller.close(old)
        f.open()
        val replacement = f.state.token
        assertNotEquals(old, replacement)
        assertTrue(f.state.saving)
        assertEquals(1, f.session.reads.size)
        assertFalse(pending.active())
        val accepted = listOf(SettingsStoragePolicyStepResult(SettingsStoragePolicyStep.DISABLE_AUTOMATIC, true))
        pending.done(SettingsStoragePolicySaveOutcome(SettingsStoragePolicyStatus.CONFIRMED, accepted, SettingsStoragePolicyRead(values(true))))
        assertEquals(SettingsStoragePolicyStatus.INCOMPLETE, results.single().status)
        assertEquals(old, results.single().token)
        assertNull(results.single().actual)
        f.drain()
        assertEquals(2, f.session.reads.size)
        assertTrue(f.state.loading)
        assertFalse(f.state.saving)
        f.session.reads.last().done(SettingsStoragePolicyRead(values(false))); f.drain()
        assertEquals(replacement, f.state.token)
        assertEquals(false, f.state.values.automatic.effective)
        assertNull(f.state.lastCompletion)
    }

    @Test fun partialFailureKeepsConfirmedStepsActualUnknownFieldsAndRequiresExplicitRefresh() {
        val f = Fixture(); f.loaded()
        val results = mutableListOf<SettingsStoragePolicyCompletion>()
        f.controller.save(f.state.token, f.state.revision, SettingsStoragePolicyDraft(sizeKiB = 200000, automatic = true), 1, results::add)
        val steps = listOf(SettingsStoragePolicyStepResult(SettingsStoragePolicyStep.SIZE, true, true),
            SettingsStoragePolicyStepResult(SettingsStoragePolicyStep.ENABLE_AUTOMATIC, error = SettingsError.REQUEST_FAILED))
        val actual = SettingsStoragePolicyRead(values().copy(sizeKiB = SettingsStoragePolicyNumber()),
            mapOf(SettingsStoragePolicyField.SIZE to SettingsError.REQUEST_FAILED))
        f.session.saves.single().done(SettingsStoragePolicySaveOutcome(SettingsStoragePolicyStatus.INCOMPLETE, steps, actual,
            SettingsError.REQUEST_FAILED, true)); f.drain()
        assertTrue(f.state.refreshRequired)
        assertEquals(steps, f.state.steps)
        assertEquals(actual.values, f.state.values)
        f.controller.save(f.state.token, f.state.revision, SettingsStoragePolicyDraft(automatic = true), 2, results::add)
        assertEquals(SettingsError.CONFLICT, results.last().error)
        assertEquals(1, f.session.saves.size)
        assertEquals(1, f.session.reads.size, "No hidden retry or rollback may start after partial acceptance.")
    }

    @Test fun synchronousPermissionLossRejectsQueuedReadPublicationAndStopsAnActiveSaveCapability() {
        val f = Fixture(); f.open()
        f.session.reads.single().done(SettingsStoragePolicyRead(values()))
        f.allowed = false
        f.drain()
        assertFalse(f.state.values.known)
        f.controller.close(f.state.token)
        assertFalse(f.state.opened)
        f.allowed = true; f.loaded()
        val results = mutableListOf<SettingsStoragePolicyCompletion>()
        f.controller.save(f.state.token, f.state.revision, SettingsStoragePolicyDraft(automatic = true), 8, results::add)
        val pending = f.session.saves.single()
        f.allowed = false
        assertFalse(pending.active())
        pending.done(SettingsStoragePolicySaveOutcome(SettingsStoragePolicyStatus.CONFIRMED,
            listOf(SettingsStoragePolicyStepResult(SettingsStoragePolicyStep.ENABLE_AUTOMATIC, true, true)), SettingsStoragePolicyRead(values(true))))
        assertEquals(SettingsStoragePolicyStatus.INCOMPLETE, results.single().status)
        assertNull(results.single().actual)
        f.drain()
        assertNotEquals(SettingsStoragePolicyStatus.CONFIRMED, f.state.lastCompletion?.status)
    }

    @Test fun aNativeOptionUpdateInvalidatesTheOldReadAndRevisionWithoutWritingOrAutoRefreshing() {
        val f = Fixture(); f.open()
        val old = f.session.reads.single()
        f.session.changed?.invoke()
        old.done(SettingsStoragePolicyRead(values())); f.drain()
        assertTrue(f.state.refreshRequired)
        assertFalse(f.state.loading)
        assertFalse(f.state.values.known)
        assertTrue(f.session.saves.isEmpty())
        assertEquals(1, f.session.reads.size)
        f.controller.refresh(f.state.token)
        f.session.reads.last().done(SettingsStoragePolicyRead(values())); f.drain()
        assertFalse(f.state.refreshRequired)
        assertTrue(f.state.values.known)
    }

    @Test fun deactivationUnsubscribesAndOriginalCompletionStillRetiresItsOwnPendingRequestOnly() {
        val f = Fixture(); f.loaded()
        val results = mutableListOf<SettingsStoragePolicyCompletion>()
        f.controller.save(f.state.token, f.state.revision, SettingsStoragePolicyDraft(automatic = true), 11, results::add)
        val pending = f.session.saves.single()
        f.controller.deactivate()
        val closed = f.state
        assertNull(f.session.changed)
        pending.done(SettingsStoragePolicySaveOutcome(SettingsStoragePolicyStatus.REJECTED, error = SettingsError.NOT_READY))
        f.drain()
        assertEquals(1, results.size)
        assertEquals(closed, f.state)
        assertFalse(pending.active())
    }

    @Test fun trueTerminalSurvivesOldEpochWhileOnlyTheNewPageReadsBackAndNoWriteIsRepeated() {
        val f = Fixture(); f.loaded()
        val old = f.state.token
        val results = mutableListOf<SettingsStoragePolicyCompletion>()
        f.controller.save(old, f.state.revision, SettingsStoragePolicyDraft(sizeKiB = 200000), 21, results::add)
        val pending = f.session.saves.single()
        f.allowed = false; f.epoch++
        f.controller.close(old)
        val closed = f.state
        val step = SettingsStoragePolicyStepResult(SettingsStoragePolicyStep.SIZE, accepted = true)
        pending.done(SettingsStoragePolicySaveOutcome(SettingsStoragePolicyStatus.INCOMPLETE, listOf(step),
            error = SettingsError.NOT_READY, refreshRequired = true))
        f.drain()
        assertEquals(closed, f.state)
        assertEquals(SettingsStoragePolicyReceipt.ACCEPTED, results.single().steps.single().receipt)
        assertNull(results.single().actual)
        f.allowed = true; f.epoch++
        f.open()
        assertFalse(f.state.saving)
        assertEquals(2, f.session.reads.size)
        f.session.reads.last().done(SettingsStoragePolicyRead(values())); f.drain()
        assertEquals(1, f.session.saves.size)
        assertNull(f.state.lastCompletion)
    }

    @Test fun unknownCompletionRetainsPhysicalOwnershipUntilLateTerminalAndCurrentOwnerGetsOneReadOnlyRevalidation() {
        val f = Fixture(); f.loaded()
        val old = f.state.token
        val results = mutableListOf<SettingsStoragePolicyCompletion>()
        f.controller.save(old, f.state.revision, SettingsStoragePolicyDraft(automatic = true), 22, results::add)
        val pending = f.session.saves.single()
        val unknown = SettingsStoragePolicyStepResult(SettingsStoragePolicyStep.ENABLE_AUTOMATIC,
            error = SettingsError.REQUEST_FAILED, receipt = SettingsStoragePolicyReceipt.INDETERMINATE)
        pending.done(SettingsStoragePolicySaveOutcome(SettingsStoragePolicyStatus.INCOMPLETE, listOf(unknown),
            error = SettingsError.REQUEST_FAILED, refreshRequired = true, physicalPending = true))
        f.drain()
        assertTrue(f.state.saving)
        assertTrue(results.single().physicalPending)
        f.controller.close(old)
        f.open()
        val replacement = f.state.token
        assertTrue(f.state.saving)
        assertEquals(1, f.session.reads.size)
        f.controller.refresh(replacement)
        assertEquals(1, f.session.reads.size)
        val accepted = unknown.copy(accepted = true, error = null, receipt = SettingsStoragePolicyReceipt.ACCEPTED)
        val terminal = SettingsStoragePolicySaveOutcome(SettingsStoragePolicyStatus.INCOMPLETE, listOf(accepted),
            refreshRequired = true)
        pending.settled(terminal); pending.settled(terminal)
        f.drain()
        assertFalse(f.state.saving)
        assertEquals(2, f.session.reads.size)
        assertEquals(1, results.size, "A form receives only its one original completion.")
        assertEquals(1, f.session.saves.size)
        assertEquals(replacement, f.state.token)
        assertNull(f.state.lastCompletion)
    }

    @Test fun relockBeforeFreshReadbackRunsDiscardsItWithoutLeavingThePhysicalSlotBusy() {
        val f = Fixture(); f.loaded()
        val old = f.state.token
        f.controller.save(old, f.state.revision, SettingsStoragePolicyDraft(automatic = true), 23, {})
        val pending = f.session.saves.single()
        f.allowed = false; f.epoch++
        f.controller.close(old)
        f.allowed = true; f.epoch++
        f.open()
        pending.done(SettingsStoragePolicySaveOutcome(SettingsStoragePolicyStatus.INCOMPLETE,
            listOf(SettingsStoragePolicyStepResult(SettingsStoragePolicyStep.ENABLE_AUTOMATIC, accepted = true)),
            refreshRequired = true))
        f.drainOne() // Physical settlement schedules a read owned by the replacement page.
        assertFalse(f.state.saving)
        f.allowed = false; f.epoch++
        f.drain()
        assertEquals(1, f.session.reads.size)
        assertFalse(f.state.loading)
        f.controller.close(f.state.token)
        f.allowed = true; f.epoch++
        f.open()
        assertEquals(2, f.session.reads.size)
        assertFalse(f.state.saving)
        assertEquals(1, f.session.saves.size)
    }

    @Test fun oldAuthorityCannotResumeAfterUnlockEvenWithoutTheDeferredPageClose() {
        val f = Fixture(); f.loaded()
        val old = f.state.token
        f.controller.save(old, f.state.revision, SettingsStoragePolicyDraft(automatic = true), 24, {})
        val pending = f.session.saves.single()
        f.allowed = false; f.epoch++
        assertFalse(pending.active())
        f.allowed = true; f.epoch++
        assertFalse(pending.active())
        assertFalse(f.controller.accepts(old))
        pending.done(SettingsStoragePolicySaveOutcome(SettingsStoragePolicyStatus.INCOMPLETE,
            listOf(SettingsStoragePolicyStepResult(SettingsStoragePolicyStep.ENABLE_AUTOMATIC, accepted = true)),
            refreshRequired = true))
        f.drain()
        assertEquals(1, f.session.reads.size)
        f.open()
        assertNotEquals(old, f.state.token)
        assertEquals(2, f.session.reads.size)
        assertFalse(f.state.saving)
    }

    @Test fun latePhysicalTerminalAfterDeactivationCannotPublishOrStartAnyRead() {
        val f = Fixture(); f.loaded()
        val results = mutableListOf<SettingsStoragePolicyCompletion>()
        f.controller.save(f.state.token, f.state.revision, SettingsStoragePolicyDraft(automatic = true), 25, results::add)
        val pending = f.session.saves.single()
        pending.done(SettingsStoragePolicySaveOutcome(SettingsStoragePolicyStatus.INCOMPLETE,
            error = SettingsError.REQUEST_FAILED, refreshRequired = true, physicalPending = true))
        f.controller.deactivate()
        val retired = f.state
        pending.settled(SettingsStoragePolicySaveOutcome(SettingsStoragePolicyStatus.INCOMPLETE,
            listOf(SettingsStoragePolicyStepResult(SettingsStoragePolicyStep.ENABLE_AUTOMATIC, accepted = true)),
            refreshRequired = true))
        f.drain()
        assertEquals(retired, f.state)
        assertEquals(1, results.size)
        assertEquals(1, f.session.reads.size)
        assertEquals(1, f.session.saves.size)
    }

    @Test fun invalidatedPageLeaseCannotProjectItsTrueReceiptAndReplacementStartsOnlyARead() {
        val f = Fixture()
        var page = true
        f.controller.open { page }
        f.session.reads.single().done(SettingsStoragePolicyRead(values())); f.drain()
        val old = f.state.token
        val results = mutableListOf<SettingsStoragePolicyCompletion>()
        f.controller.save(old, f.state.revision, SettingsStoragePolicyDraft(automatic = true), 26, results::add)
        val pending = f.session.saves.single()
        val before = f.state
        page = false
        pending.done(SettingsStoragePolicySaveOutcome(SettingsStoragePolicyStatus.CONFIRMED,
            listOf(SettingsStoragePolicyStepResult(SettingsStoragePolicyStep.ENABLE_AUTOMATIC, true, true)),
            SettingsStoragePolicyRead(values(true))))
        f.drain()
        assertEquals(before, f.state)
        assertNull(results.single().actual)
        assertEquals(SettingsStoragePolicyReceipt.ACCEPTED, results.single().steps.single().receipt)
        f.open()
        assertNotEquals(old, f.state.token)
        assertEquals(2, f.session.reads.size)
        assertFalse(f.state.saving)
        assertEquals(1, f.session.saves.size)
    }

    private class Fixture {
        var allowed = true
        var epoch = 0L
        val session = FakeSession()
        private data class Work(val epoch: Long, val terminal: Boolean, val work: () -> Unit)
        private val queue = ArrayDeque<Work>()
        var state = SettingsStoragePolicySnapshot()
        private val boundary = SettingsSpecialMutationBoundary(
            post = { queue.addLast(Work(epoch, true, it)) },
            authority = { val captured = epoch; { allowed && captured == epoch } },
            postReadback = { current, work -> queue.addLast(Work(epoch, false) { if (current()) work() }) },
        )
        val controller = SettingsStoragePolicyController(9, session,
            { queue.addLast(Work(epoch, false, it)) }, { state = it }, { allowed }, boundary)
        fun open() = controller.open { true }
        fun loaded() { open(); session.reads.last().done(SettingsStoragePolicyRead(values())); drain() }
        fun drainOne() {
            val item = queue.removeFirst()
            if (item.terminal || (allowed && item.epoch == epoch)) item.work()
        }
        fun drain() { while (queue.isNotEmpty()) drainOne() }
    }
    private data class Read(val active: () -> Boolean, val done: (SettingsStoragePolicyRead) -> Unit)
    private data class Save(val active: () -> Boolean, val progress: (List<SettingsStoragePolicyStepResult>) -> Unit,
        val done: (SettingsStoragePolicySaveOutcome) -> Unit,
        val settled: (SettingsStoragePolicySaveOutcome) -> Unit = {})
    private class FakeSession : TdSettingsStoragePolicySession {
        var changed: (() -> Unit)? = null
        val reads = mutableListOf<Read>()
        val saves = mutableListOf<Save>()
        override fun observe(observer: (() -> Unit)?) { changed = observer }
        override fun load(active: () -> Boolean, result: (SettingsStoragePolicyRead) -> Unit) { reads += Read(active, result) }
        override fun save(expected: SettingsStoragePolicyValues, draft: SettingsStoragePolicyDraft, active: () -> Boolean,
            progress: (List<SettingsStoragePolicyStepResult>) -> Unit, result: (SettingsStoragePolicySaveOutcome) -> Unit,
        ) { saves += Save(active, progress, result) }
        override fun saveOwned(expected: SettingsStoragePolicyValues, draft: SettingsStoragePolicyDraft, active: () -> Boolean,
            progress: (List<SettingsStoragePolicyStepResult>) -> Unit, result: (SettingsStoragePolicySaveOutcome) -> Unit,
            settled: (SettingsStoragePolicySaveOutcome) -> Unit,
        ) { saves += Save(active, progress, result, settled) }
    }
    private companion object {
        fun number(value: Long) = SettingsStoragePolicyNumber(value, value, SettingsStoragePolicySource.EXPLICIT)
        fun values(automatic: Boolean = false) = SettingsStoragePolicyValues(number(102400), number(82800),
            SettingsStoragePolicySwitch(automatic, automatic, SettingsStoragePolicySource.EXPLICIT), number(40000), number(3600))
    }
}
