package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import kotlin.test.*

class ReactionNotificationsControllerTest {
    @Test fun unknownRemainsUnknownAndRefreshOnlyReadsTheCache() {
        val f = Fixture(); f.open()
        assertNull(f.snapshot.native.value)
        f.save(); assertEquals(SettingsError.NOT_READY, f.snapshot.outcome?.error)
        assertTrue(f.session.writes.isEmpty())
        f.refresh(); assertNull(f.snapshot.native.value)
        assertEquals(3, f.session.stateReads)
        f.update(value(), 1)
        assertEquals(value(), f.snapshot.native.value)
        assertNull(f.snapshot.error)
    }

    @Test fun observersPublishOnlyOnTheActorAndRejectOlderOrRepeatedRevisions() {
        val f = Fixture(); f.open()
        f.session.update(state(value(), 3))
        assertNull(f.snapshot.native.value)
        f.drain(); assertEquals(3L, f.snapshot.native.revision)
        val revision = f.snapshot.revision
        f.session.observer?.invoke(state(value(message = ReactionNotificationSource.NONE), 2))
        f.session.observer?.invoke(state(value(message = ReactionNotificationSource.NONE), 3))
        f.drain()
        assertEquals(value(), f.snapshot.native.value)
        assertEquals(revision, f.snapshot.revision)
    }

    @Test fun pageOwnerIsStableAndOldTokensCannotRefreshSaveOrCloseANewOwner() {
        val f = Fixture(); f.open(); f.update(value(), 1)
        val token = f.snapshot.token
        f.open(); assertEquals(token, f.snapshot.token)
        f.controller.close(token); f.open()
        val next = f.snapshot.token
        assertNotEquals(token, next)
        val reads = f.session.stateReads
        f.controller.refreshFromCache(token); f.controller.save(token, f.edit()); f.controller.close(token)
        assertEquals(reads, f.session.stateReads)
        assertEquals(next, f.snapshot.token)
        assertTrue(f.session.writes.isEmpty())
    }

    @Test fun entireExpectedStateAndRevisionMustMatchEvenWhenObserverIsStillQueued() {
        val f = Fixture(); f.open(); f.update(value(), 1)
        val frozen = f.edit()
        f.session.update(state(value(soundId = 99), 2))
        f.controller.save(f.snapshot.token, frozen)
        assertTrue(f.session.writes.isEmpty())
        assertEquals(SettingsError.CONFLICT, f.snapshot.outcome?.error)
        assertEquals(2L, f.snapshot.native.revision)
        f.drain()
        val wrongValue = f.edit().copy(expected = state(value(soundId = 98), 2))
        f.controller.save(f.snapshot.token, wrongValue)
        assertTrue(f.session.writes.isEmpty())
        val wrongError = f.edit().copy(expected = f.snapshot.native.copy(error = SettingsError.REQUEST_FAILED))
        f.controller.save(f.snapshot.token, wrongError)
        assertTrue(f.session.writes.isEmpty())
    }

    @Test fun invalidEmptyPatchAndUnknownErrorNeverReachNativeSave() {
        val f = Fixture(); f.open(); f.update(value(), 1)
        f.controller.save(f.snapshot.token, f.edit(patch = ReactionNotificationPatch()))
        assertEquals(SettingsError.INVALID_VALUE, f.snapshot.outcome?.error)
        f.update(null, 2, SettingsError.INVALID_VALUE); f.save()
        assertTrue(f.session.writes.isEmpty())
        assertNull(f.snapshot.native.value)
    }

    @Test fun acceptedOkIsLocalAndAwaitsARealUpdateWithoutDuplicateWritesOrRequests() {
        val f = ready(); val edit = f.edit(contentRevision = 41)
        f.controller.save(f.snapshot.token, edit)
        val write = f.session.writes.single()
        assertTrue(f.snapshot.saving); assertEquals(edit.expected, write.expected)
        assertEquals(edit.patch, write.patch)
        f.replyAccepted(write)
        assertFalse(f.snapshot.saving)
        assertTrue(f.snapshot.awaitingUpdate)
        assertEquals(value(), f.snapshot.native.value)
        assertTrue(f.snapshot.outcome?.locallyAccepted == true)
        assertEquals(41L, f.snapshot.outcome?.edit?.contentRevision)
        f.save(); assertEquals(SettingsError.NOT_READY, f.snapshot.outcome?.error)
        f.refresh(); assertTrue(f.snapshot.awaitingUpdate)
        assertEquals(1, f.session.writes.size)
        f.update(edit.patch.applyTo(value()), 2)
        assertFalse(f.snapshot.awaitingUpdate)
        assertFalse(f.snapshot.refreshRequired)
        assertNull(f.snapshot.error)
    }

    @Test fun anyNewMismatchingUpdateEndsWaitingAndExposesActualConflictWithoutRetrying() {
        val f = ready(); f.save(); f.replyAccepted()
        val actual = value(poll = ReactionNotificationSource.CONTACTS, soundId = 4242)
        f.update(actual, 2)
        assertEquals(actual, f.snapshot.native.value)
        assertFalse(f.snapshot.awaitingUpdate)
        assertTrue(f.snapshot.refreshRequired)
        assertEquals(SettingsError.CONFLICT, f.snapshot.error)
        assertEquals(1, f.session.writes.size)
        f.refresh(); assertNull(f.snapshot.error)
        f.save(); assertEquals(2, f.session.writes.size)
    }

    @Test fun newerUnknownOrMalformedUpdateCannotLeaveAnAcceptedWriteWaitingForever() {
        val f = ready(); f.save(); f.replyAccepted()
        f.update(null, 2, SettingsError.INVALID_VALUE)
        assertFalse(f.snapshot.awaitingUpdate)
        assertNull(f.snapshot.native.value)
        assertEquals(SettingsError.CONFLICT, f.snapshot.error)
        assertTrue(f.snapshot.refreshRequired)
    }

    @Test fun updateBeforeOkWinsOverItsOldActualAndDoesNotWaitOrOverwriteNewerFields() {
        val f = ready(); f.save()
        val write = f.session.writes.single()
        val desired = write.patch.applyTo(write.expected.value!!)
        f.update(desired, 2)
        f.replyAccepted(write, actual = state(value(), 1))
        assertEquals(desired, f.snapshot.native.value)
        assertEquals(2L, f.snapshot.native.revision)
        assertFalse(f.snapshot.awaitingUpdate)
        assertNull(f.snapshot.error)
    }

    @Test fun externalUpdateBeforeOkOrStillQueuedIsAConflictRatherThanAnOptimisticOverwrite() {
        for (drainUpdate in listOf(true, false)) {
            val f = ready(); f.save()
            val external = value(showSender = false, story = ReactionNotificationSource.NONE, soundId = 55)
            f.session.update(state(external, 2))
            if (drainUpdate) f.drain()
            f.replyAccepted(actual = state(value(), 1))
            assertEquals(external, f.snapshot.native.value)
            assertFalse(f.snapshot.awaitingUpdate)
            assertEquals(SettingsError.CONFLICT, f.snapshot.error)
            assertEquals(SettingsError.CONFLICT, f.snapshot.outcome?.error)
            assertTrue(f.snapshot.outcome?.locallyAccepted == true)
        }
    }

    @Test fun anOldMutationKeepsTheNextOwnerBusyUntilRealCompletionWithoutClaimingItsSuccess() {
        val f = ready(); f.save()
        val old = f.session.writes.single()
        f.controller.close(f.snapshot.token); f.open()
        val next = f.snapshot.token
        assertTrue(f.snapshot.saving)
        assertNull(f.snapshot.activeAttempt)
        assertFalse(old.current())
        f.save(); assertEquals(1, f.session.writes.size)
        f.update(old.patch.applyTo(value()), 2)
        f.replyAccepted(old)
        assertEquals(next, f.snapshot.token)
        assertFalse(f.snapshot.saving)
        assertFalse(f.snapshot.awaitingUpdate)
        assertFalse(f.snapshot.outcome?.locallyAccepted == true)
        f.save(); assertEquals(2, f.session.writes.size)
    }

    @Test fun lateAcceptanceAfterCloseReleasesBusyAndDoesNotReopenThePage() {
        val f = ready(); f.save()
        val write = f.session.writes.single()
        f.controller.close(f.snapshot.token)
        val closed = f.snapshot
        f.replyAccepted(write)
        assertEquals(closed, f.snapshot)
        f.open(); assertFalse(f.snapshot.saving)
        assertTrue(f.snapshot.awaitingUpdate)
        assertNull(f.snapshot.outcome)
        f.update(write.patch.applyTo(value()), 2)
        assertFalse(f.snapshot.awaitingUpdate)
    }

    @Test fun closedOwnerCancelsTheNotYetDispatchedNativeContinuation() {
        val f = ready(); f.save()
        val write = f.session.writes.single()
        f.controller.close(f.snapshot.token)
        assertFalse(write.current())
        write.reply(ReactionNotificationSaveResult(error = SettingsError.NOT_READY)); f.drain()
        f.open(); assertFalse(f.snapshot.saving)
        assertFalse(f.snapshot.awaitingUpdate)
        assertNull(f.snapshot.outcome)
    }

    @Test fun actorAndNativeReadyChecksAreSeparateAndWorkerNeverCallsTheActorPredicate() {
        val f = ready(); f.save()
        val write = f.session.writes.single()
        f.onWorker { assertTrue(write.current()) }
        f.allowed = false
        assertFalse(f.controller.accepts(f.snapshot.token))
        f.onWorker { assertFalse(write.current()) }
        f.allowed = true
        assertTrue(f.controller.accepts(f.snapshot.token))
        f.page = false
        f.onWorker { assertFalse(write.current()) }
        val blocked = ready(); blocked.session.nativeReady = false; blocked.save(); blocked.drain()
        assertEquals(SettingsError.NOT_READY, blocked.snapshot.outcome?.error)
        assertFalse(blocked.snapshot.saving)
        assertEquals(0, blocked.session.dispatched)
        blocked.allowed = false; blocked.save()
        assertEquals(1, blocked.session.writes.size)
    }

    @Test fun callbacksWhileNotAllowedDoNotPublishAndExplicitCacheRefreshReleasesTheUiBusyState() {
        val f = ready(); f.save(); f.allowed = false
        val before = f.snapshot
        f.replyAccepted()
        assertEquals(before, f.snapshot)
        f.allowed = true; f.refresh()
        assertFalse(f.snapshot.saving)
        assertNull(f.snapshot.activeAttempt)
        assertTrue(f.snapshot.awaitingUpdate)
    }

    @Test fun everyFailureRetainsItsOwnContentRevisionForAnExplicitRetry() {
        for (error in SettingsError.entries) {
            val f = ready(); f.save(contentRevision = 18)
            f.session.writes.single().reply(ReactionNotificationSaveResult(error = error)); f.drain()
            assertFalse(f.snapshot.saving)
            assertFalse(f.snapshot.awaitingUpdate)
            assertEquals(18L, f.snapshot.outcome?.edit?.contentRevision)
            assertEquals(error, f.snapshot.outcome?.error)
            assertFalse(f.snapshot.outcome!!.locallyAccepted)
            f.save(contentRevision = 19)
            assertEquals(2, f.session.writes.size)
        }
    }

    @Test fun duplicateCallbacksAndSaveExceptionsSettleExactlyOnce() {
        val f = ready(); f.save()
        val write = f.session.writes.single()
        f.replyAccepted(write)
        val settled = f.snapshot
        write.reply(ReactionNotificationSaveResult(error = SettingsError.REQUEST_FAILED)); f.drain()
        assertEquals(settled, f.snapshot)
        val throwing = ready(); throwing.session.throwSave = true; throwing.save(); throwing.drain()
        assertFalse(throwing.snapshot.saving)
        assertEquals(SettingsError.REQUEST_FAILED, throwing.snapshot.outcome?.error)
        assertEquals(1, throwing.session.writes.size)
    }

    @Test fun newerImmediateIntentIsNotOverwrittenByTheOlderInFlightOutcome() {
        val f = ready(); f.save(contentRevision = 20)
        f.save(contentRevision = 21)
        assertEquals(21L, f.snapshot.outcome?.edit?.contentRevision)
        assertEquals(SettingsError.NOT_READY, f.snapshot.outcome?.error)
        f.replyAccepted()
        assertEquals(21L, f.snapshot.outcome?.edit?.contentRevision)
        assertFalse(f.snapshot.outcome!!.locallyAccepted)
        assertFalse(f.snapshot.saving)
    }

    @Test fun sameValueWithANewIntentIsNotDeduplicatedByThePreviousContentRevision() {
        val f = ready(); f.save(contentRevision = 1)
        val first = f.session.writes.single()
        f.update(first.patch.applyTo(value()), 2); f.replyAccepted(first)
        val firstAttempt = f.snapshot.outcome!!.attemptId
        f.save(contentRevision = 2)
        assertEquals(2, f.session.writes.size)
        f.replyAccepted(f.session.writes.last())
        assertEquals(2L, f.snapshot.outcome?.edit?.contentRevision)
        assertTrue(f.snapshot.outcome!!.attemptId > firstAttempt)
        assertFalse(f.snapshot.awaitingUpdate)
    }

    @Test fun retirementUnsubscribesAndPreventsAllLateWorkWithoutReopening() {
        val f = ready(); f.save()
        val write = f.session.writes.single()
        val observer = f.session.observer!!
        f.controller.deactivate()
        assertNull(f.session.observer)
        f.onWorker { assertFalse(write.current()) }
        val retired = f.snapshot
        observer(state(value(), 100)); f.replyAccepted(write); f.drain()
        f.open(); f.refresh(); f.save(); f.controller.deactivate()
        assertEquals(retired, f.snapshot)
        assertEquals(1, f.session.writes.size)
    }

    @Test fun cacheReadErrorsNeverInventAValueAndCanBeExplicitlyRetried() {
        val f = Fixture(); f.session.throwRead = true; f.open()
        assertNull(f.snapshot.native.value)
        assertEquals(SettingsError.REQUEST_FAILED, f.snapshot.error)
        f.session.throwRead = false; f.refresh()
        assertNull(f.snapshot.native.value)
        assertNull(f.snapshot.error)
    }

    private data class Write(
        val expected: ReactionNotificationNativeState,
        val patch: ReactionNotificationPatch,
        val current: () -> Boolean,
        val reply: (ReactionNotificationSaveResult) -> Unit,
    )

    private class Session : TdReactionNotificationsSession {
        var observer: ((ReactionNotificationNativeState) -> Unit)? = null
        var cached = ReactionNotificationNativeState()
        var stateReads = 0
        var throwRead = false
        var throwSave = false
        var nativeReady = true
        var dispatched = 0
        val writes = mutableListOf<Write>()
        override fun state(): ReactionNotificationNativeState {
            stateReads++
            if (throwRead) error("synthetic")
            return cached
        }
        override fun observe(observer: ((ReactionNotificationNativeState) -> Unit)?) { this.observer = observer }
        override fun save(expected: ReactionNotificationNativeState, patch: ReactionNotificationPatch,
            active: () -> Boolean, result: (ReactionNotificationSaveResult) -> Unit) {
            writes += Write(expected, patch, active, result)
            if (throwSave) error("synthetic")
            if (!nativeReady || !active()) result(ReactionNotificationSaveResult(error = SettingsError.NOT_READY))
            else dispatched++
        }
        fun update(state: ReactionNotificationNativeState) {
            cached = state
            observer?.invoke(state)
        }
    }

    private class Fixture {
        val session = Session()
        val queue = ArrayDeque<() -> Unit>()
        var allowed = true
        var page = true
        var actor = true
        var snapshot = ReactionNotificationsSnapshot()
        val controller = ReactionNotificationsController(7, session, { queue.addLast(it) }, {
            check(actor); snapshot = it
        }, { check(actor) { "Actor predicate called from native continuation" }; allowed })
        fun open() = controller.open { page }
        fun refresh() = controller.refreshFromCache(snapshot.token)
        fun drain() { while (queue.isNotEmpty()) queue.removeFirst().invoke() }
        fun update(value: ReactionNotificationValue?, revision: Long, error: SettingsError? = null) {
            session.update(state(value, revision, error)); drain()
        }
        fun edit(contentRevision: Long = 1, patch: ReactionNotificationPatch = ReactionNotificationPatch(
            message = ReactionNotificationSource.NONE,
        )) = ReactionNotificationsEdit(snapshot.native, patch, contentRevision)
        fun save(contentRevision: Long = 1) = controller.save(snapshot.token, edit(contentRevision))
        fun replyAccepted(write: Write = session.writes.last(), actual: ReactionNotificationNativeState = session.cached) {
            write.reply(ReactionNotificationSaveResult(true, write.expected.value?.let(write.patch::applyTo), actual))
            drain()
        }
        fun onWorker(block: () -> Unit) { actor = false; try { block() } finally { actor = true } }
    }

    private companion object {
        fun ready() = Fixture().also { it.open(); it.update(value(), 1) }
        fun state(value: ReactionNotificationValue?, revision: Long, error: SettingsError? = null) =
            ReactionNotificationNativeState(value, revision, error)
        fun value(
            message: ReactionNotificationSource = ReactionNotificationSource.ALL,
            story: ReactionNotificationSource = ReactionNotificationSource.CONTACTS,
            poll: ReactionNotificationSource = ReactionNotificationSource.ALL,
            soundId: Long = -1,
            showSender: Boolean = true,
        ) = ReactionNotificationValue(message, story, poll, soundId, showSender)
    }
}
