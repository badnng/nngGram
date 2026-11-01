package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import kotlin.test.*

class ContactSignupControllerTest {
    @Test fun eachNewPageLoadsWithAnIsolatedOwnerAndCallbacksPublishOnlyOnTheActor() {
        val f = Fixture(); f.open()
        val first = f.snapshot.token
        val pending = f.session.loads.single()
        pending.reply(SettingsResult(value(true)))
        assertTrue(f.snapshot.loading); f.drain(); assertEquals(value(true), f.snapshot.value)
        f.controller.close(first); f.open()
        assertNotEquals(first, f.snapshot.token); assertEquals(2, f.session.loads.size)
        pending.reply(SettingsResult(value(false))); f.drain()
        assertNull(f.snapshot.value); assertTrue(f.snapshot.loading)
    }

    @Test fun failuresStayUnknownAndRefreshUsesARealLoadRatherThanADefault() {
        val f = Fixture(); f.open(); f.loaded(null, SettingsError.REQUEST_FAILED)
        assertNull(f.snapshot.value); assertTrue(f.snapshot.refreshRequired)
        f.controller.refresh(f.snapshot.token); assertEquals(2, f.session.loads.size)
        f.loaded(value(true, ContactSignupValueSource.DEFAULT))
        assertFalse(f.snapshot.refreshRequired); assertEquals(ContactSignupValueSource.DEFAULT, f.snapshot.value?.source)
    }

    @Test fun mutationRequiresCurrentKnownRevisionExpectedValueAndAccountPermission() {
        val f = Fixture(); f.open(); f.loaded(value(true))
        val token = f.snapshot.token; val revision = f.snapshot.revision
        f.controller.save(token, revision - 1, value(true), false)
        assertEquals(SettingsError.CONFLICT, f.snapshot.error); assertTrue(f.session.saves.isEmpty())
        f.controller.save(token, revision, value(false), false)
        assertTrue(f.session.saves.isEmpty())
        f.allowed = false; f.controller.save(token, revision, value(true), false)
        assertTrue(f.session.saves.isEmpty()); assertFalse(f.controller.accepts(token))
        f.allowed = true; f.controller.save(token, revision, value(true), false)
        assertEquals(1, f.session.saves.size)
        f.controller.save(token, revision, value(true), false)
        assertEquals(1, f.session.saves.size)
    }

    @Test fun matchingPublicReadbackCompletesPendingWithoutClaimingServerConfirmation() {
        val f = Fixture(); f.open(); f.loaded(value(true)); f.save(false)
        assertTrue(f.snapshot.saving)
        f.session.saves.single().reply(ContactSignupSaveResult(false, true, value(false)))
        assertTrue(f.snapshot.saving); f.drain()
        assertFalse(f.snapshot.saving); assertFalse(f.snapshot.readbackPending)
        assertNull(f.snapshot.desiredEnabled); assertEquals(1L, f.snapshot.acceptedRevision)
        assertEquals(value(false), f.snapshot.value)
    }

    @Test fun acceptedMismatchAndFailurePreserveDesiredUntilExplicitMatchingRefresh() {
        for (error in listOf<SettingsError?>(null, SettingsError.REQUEST_FAILED)) {
            val f = Fixture(); f.open(); f.loaded(value(true)); f.save(false)
            f.session.saves.single().reply(ContactSignupSaveResult(false, true,
                actual = if (error == null) value(true) else null, error = error)); f.drain()
            assertTrue(f.snapshot.readbackPending); assertEquals(false, f.snapshot.desiredEnabled)
            assertTrue(f.snapshot.refreshRequired)
            f.save(true); assertEquals(1, f.session.saves.size)
            assertEquals(1, f.session.loads.size)
            f.controller.refresh(f.snapshot.token); f.loaded(value(false))
            assertFalse(f.snapshot.readbackPending); assertNull(f.snapshot.desiredEnabled)
            assertEquals(1, f.session.saves.size)
        }
    }

    @Test fun updatesInvalidateWithoutConfirmingAcceptanceOrAutomaticallyReading() {
        val f = Fixture(); f.open()
        f.session.invalidate(); f.drain()
        assertTrue(f.snapshot.loading); assertTrue(f.snapshot.refreshRequired); assertEquals(1, f.session.loads.size)
        f.loaded(value(true)); f.save(false)
        f.session.saves.single().reply(ContactSignupSaveResult(false, true, value(true))); f.drain()
        f.session.invalidate(); f.drain()
        assertTrue(f.snapshot.readbackPending); assertTrue(f.snapshot.refreshRequired)
        assertEquals(1, f.session.loads.size); assertEquals(1, f.session.saves.size)
    }

    @Test fun oldMutationKeepsANewOwnerWaitingThenFreshlyReadsAfterRealCompletion() {
        val f = Fixture(); f.open(); f.loaded(value(true)); f.save(false)
        val held = f.session.saves.single(); val oldToken = f.snapshot.token
        f.controller.close(oldToken); f.open()
        val newToken = f.snapshot.token
        assertTrue(f.snapshot.saving); assertFalse(held.current()); assertEquals(1, f.session.loads.size)
        held.reply(ContactSignupSaveResult(false, true, error = SettingsError.NOT_READY))
        assertTrue(f.snapshot.saving); f.drain()
        assertEquals(newToken, f.snapshot.token); assertTrue(f.snapshot.loading)
        assertTrue(f.snapshot.readbackPending); assertEquals(false, f.snapshot.desiredEnabled)
        assertEquals(2, f.session.loads.size)
        held.reply(ContactSignupSaveResult(false, true, value(false))); f.drain()
        assertEquals(2, f.session.loads.size); assertEquals(1L, f.snapshot.acceptedRevision)
        f.loaded(value(false)); assertFalse(f.snapshot.readbackPending)
    }

    @Test fun acceptedDesiredSurvivesClosedPagesAndFailedNewOwnerReadsButNotGenerationRetirement() {
        val f = Fixture(); f.open(); f.loaded(value(true)); f.save(false)
        f.controller.close(f.snapshot.token)
        f.session.saves.single().reply(ContactSignupSaveResult(false, true, error = SettingsError.NOT_READY)); f.drain()
        f.open(); assertTrue(f.snapshot.readbackPending)
        f.loaded(null, SettingsError.REQUEST_FAILED)
        assertTrue(f.snapshot.readbackPending); assertEquals(false, f.snapshot.desiredEnabled)
        f.controller.close(f.snapshot.token); f.open(); f.loaded(value(false))
        assertFalse(f.snapshot.readbackPending)
        f.controller.deactivate(); assertFalse(f.snapshot.opened); assertNull(f.session.observer)
        f.open(); assertFalse(f.snapshot.opened)
        val next = Fixture(9); next.open()
        assertFalse(next.snapshot.readbackPending); assertEquals(9L, next.snapshot.token.generation)
    }

    @Test fun pageLeaseAndAccountGateAreRecheckedBeforeNativeContinuationAndActorPublication() {
        for (gate in listOf(false, true)) {
            val f = Fixture(); f.open(); val load = f.session.loads.single()
            if (gate) f.allowed = false else f.page = false
            assertFalse(load.current()); load.reply(SettingsResult(value(true))); f.drain()
            assertNull(f.snapshot.value)
            val write = Fixture(); write.open(); write.loaded(value(true)); write.save(false)
            if (gate) write.allowed = false else write.page = false
            val mutation = write.session.saves.single(); assertFalse(mutation.current())
            mutation.reply(ContactSignupSaveResult(false, true, value(false))); write.drain()
            assertEquals(value(true), write.snapshot.value)
        }
    }

    @Test fun duplicateReadAndWriteCallbacksAndExceptionsTerminateOnceWithoutReplayingRequests() {
        val f = Fixture(); f.open()
        val load = f.session.loads.single(); load.reply(SettingsResult(value(true))); load.reply(SettingsResult(value(false))); f.drain()
        assertEquals(value(true), f.snapshot.value)
        f.save(false); val write = f.session.saves.single()
        write.reply(ContactSignupSaveResult(false, true, value(false)))
        write.reply(ContactSignupSaveResult(false, error = SettingsError.REQUEST_FAILED)); f.drain()
        assertEquals(value(false), f.snapshot.value); assertEquals(1L, f.snapshot.acceptedRevision)
        val throwing = Fixture(); throwing.session.throwLoad = true; throwing.open(); throwing.drain()
        assertEquals(SettingsError.REQUEST_FAILED, throwing.snapshot.error)
        throwing.session.throwLoad = false; throwing.controller.refresh(throwing.snapshot.token); throwing.loaded(value(true))
        throwing.session.throwSave = true; throwing.save(false); throwing.drain()
        assertFalse(throwing.snapshot.saving); assertFalse(throwing.snapshot.readbackPending)
        assertEquals(SettingsError.REQUEST_FAILED, throwing.snapshot.error)
    }

    private class Read(val current: () -> Boolean, val reply: (SettingsResult<ContactSignupValue>) -> Unit)
    private class Write(val expected: ContactSignupValue, val desired: Boolean, val current: () -> Boolean,
        val reply: (ContactSignupSaveResult) -> Unit)
    private class Session : TdContactSignupSession {
        var observer: (() -> Unit)? = null
        var throwLoad = false; var throwSave = false
        val loads = mutableListOf<Read>(); val saves = mutableListOf<Write>()
        override fun observe(observer: (() -> Unit)?) { this.observer = observer }
        override fun load(active: () -> Boolean, result: (SettingsResult<ContactSignupValue>) -> Unit) {
            if (throwLoad) error("synthetic")
            loads += Read(active, result)
        }
        override fun save(expected: ContactSignupValue, desired: Boolean, active: () -> Boolean, result: (ContactSignupSaveResult) -> Unit) {
            if (throwSave) error("synthetic")
            saves += Write(expected, desired, active, result)
        }
        fun invalidate() { observer?.invoke() }
    }
    private class Fixture(generation: Long = 8) {
        var allowed = true; var page = true
        val session = Session()
        val queue = ArrayDeque<() -> Unit>()
        var snapshot = ContactSignupSnapshot()
        val controller = ContactSignupController(generation, session, { queue.addLast(it) }, { snapshot = it }, { allowed })
        fun open() { controller.open { page } }
        fun drain() { while (queue.isNotEmpty()) queue.removeFirst().invoke() }
        fun loaded(value: ContactSignupValue?, error: SettingsError? = null) {
            session.loads.last().reply(SettingsResult(value, error)); drain()
        }
        fun save(desired: Boolean) { controller.save(snapshot.token, snapshot.revision, checkNotNull(snapshot.value), desired) }
    }
    private fun value(enabled: Boolean, source: ContactSignupValueSource = ContactSignupValueSource.EXPLICIT) = ContactSignupValue(enabled, source)
}
