package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import kotlin.test.*

class LoginEmailControllerTest {
    @Test fun startupBeforeReadyPublishesAnEarlyMandatoryConditionWithoutAnyProbe() {
        val f = Fixture(required = true, ready = false)
        assertTrue(f.state.mandatory)
        assertTrue(f.state.suggestions.requiredPresent)
        assertNull(f.state.token)
        assertTrue(f.calls.isEmpty(), "Subscription is safe before authorization READY.")
        assertFalse(f.gate.allowsOrdinary())
        f.open()
        assertNull(f.state.token)
        assertTrue(f.calls.isEmpty())
    }

    @Test fun optionalOpenAndHideBeforeReadyAreSubscriptionOnly() {
        val f = Fixture(ready = false)
        f.open()
        f.controller.hideOptional(1, 1)
        assertNull(f.state.token)
        assertFalse(f.state.busy)
        assertTrue(f.calls.isEmpty())
    }

    @Test fun aQueuedFreshProofCannotUnlockRequiredAfterNativeAuthorizationLeavesReady() {
        val f = Fixture(required = true)
        f.open()
        f.reply(TdApi.Error(404, "Not Found"))
        f.reply(TdApi.Error(404, "Not Found"))
        f.last.reply(passwordState("remote***")) // Native completion is now waiting in the actor queue.
        f.authorizationReady = false
        f.drain()
        assertTrue(f.state.mandatory)
        assertEquals(0L, f.state.satisfiedRequiredEpoch)
        assertFalse(f.gate.allowsOrdinary())
        assertNotEquals(LoginEmailPhase.COMPLETE, f.state.phase)
    }

    @Test fun queuedOpenWithAnAlreadyClosedPageLeaseDoesNotReadNativeState() {
        val f = Fixture(required = true)
        f.controller.open(1, 1, LoginEmailEntryKind.MANDATORY) { false }
        assertNull(f.state.token)
        assertTrue(f.calls.isEmpty())
        assertTrue(f.state.mandatory)
    }

    @Test fun absentSuggestionWrongGenerationAndOptionalWhileRequiredDoNotCreateAnOwner() {
        val absent = Fixture(present = false)
        absent.open()
        assertNull(absent.state.token)
        val f = Fixture(required = true)
        f.controller.open(2, 1, LoginEmailEntryKind.MANDATORY) { true }
        f.controller.open(1, 1, LoginEmailEntryKind.OPTIONAL) { true }
        assertTrue(f.calls.isEmpty())
        assertNull(f.state.token)
    }

    @Test fun noTwoFactorOwnerAdvancesOnlyOnTheNativeCodeInfoAndRejectsDuplicateSubmission() {
        val f = Fixture(required = true)
        f.openAllowed()
        val values = mutableListOf<LoginEmailOutcome>()
        f.controller.submitEmail(f.token, "private@example.test", values::add)
        assertTrue(f.state.busy)
        f.controller.submitEmail(f.token, "other@example.test", values::add)
        assertEquals(LoginEmailIssue.NOT_AVAILABLE, values.single().issue)
        f.reply(TdApi.Ok())
        assertEquals(LoginEmailPhase.EMAIL, f.state.phase)
        f.reply(TdApi.EmailAddressAuthenticationCodeInfo("p***", 0))
        assertEquals(LoginEmailPhase.CODE, f.state.phase)
        assertEquals(0, f.state.codeInfo?.length)
        assertEquals(f.token, f.state.challenge?.owner)
        assertFalse(f.state.busy)
        assertFalse(f.snapshots.toString().contains("private@example.test"))
    }

    @Test fun acceptedCodeNeedsFreshPatternAnd404ProofBeforeRequiredCanUnlock() {
        val f = Fixture(required = true)
        f.openAllowed()
        f.sentCode()
        val result = mutableListOf<LoginEmailOutcome>()
        f.controller.check(assertNotNull(f.state.challenge), "1234", result::add)
        f.reply(TdApi.Ok())
        assertTrue(result.single().verificationAccepted)
        assertEquals(LoginEmailPhase.PROOF_PENDING, f.state.phase)
        assertTrue(f.state.busy)
        assertTrue(f.state.mandatory)
        f.reply(TdApi.Error(404, "Not Found"))
        f.reply(passwordState(""))
        assertEquals(LoginEmailIssue.PROOF_UNCONFIRMED, f.state.issue)
        assertTrue(f.state.mandatory)
        assertFalse(f.gate.allowsOrdinary())
        val count = f.calls.size
        f.controller.check(LoginEmailChallengeToken(f.token, 1), "1234", result::add)
        assertEquals(count, f.calls.size, "A code already accepted must not be retried as a status refresh.")
        f.controller.refreshProof(f.token)
        f.reply(TdApi.Error(404, "Not Found"))
        f.reply(passwordState("p***"))
        assertEquals(LoginEmailPhase.COMPLETE, f.state.phase)
        assertFalse(f.state.mandatory)
        assertTrue(f.gate.allowsOrdinary())
        assertEquals(f.token.suggestionEpoch, f.state.satisfiedRequiredEpoch)
        assertTrue(f.state.suggestions.requiredPresent, "Completion must not fabricate a removed native action.")
        assertFalse(f.state.fulfilledElsewhere)
    }

    @Test fun initial404PlusActualPatternRecognizesExternalFulfillmentWithoutPretendingLocalSave() {
        val f = Fixture(required = true)
        f.open()
        f.reply(TdApi.Error(404, "Not Found"))
        assertEquals(LoginEmailPhase.PROOF_PENDING, f.state.phase)
        assertIs<TdApi.IsLoginEmailAddressRequired>(f.last.command)
        f.reply(TdApi.Error(404, "Not Found"))
        f.reply(passwordState("remote***"))
        assertEquals(LoginEmailPhase.COMPLETE, f.state.phase)
        assertTrue(f.state.fulfilledElsewhere)
        assertFalse(f.state.verificationAccepted)
        assertFalse(f.state.mandatory)
        assertFalse(f.calls.any { it.command is TdApi.SetLoginEmailAddress })
    }

    @Test fun failedProbeRemainsLockedAndOnlyExplicitRefreshRetries() {
        val f = Fixture(required = true)
        f.open()
        f.last.fail(); f.drain()
        assertEquals(LoginEmailAvailability.FAILED, f.state.availability)
        assertFalse(f.state.busy)
        assertTrue(f.state.mandatory)
        assertEquals(1, f.calls.size)
        f.controller.refreshProof(f.token)
        assertEquals(2, f.calls.size)
        f.reply(TdApi.Ok())
        assertEquals(LoginEmailAvailability.ALLOWED, f.state.availability)
    }

    @Test fun unacceptedExternalProofCanReturnToEmailEntryWhenAFreshProbeAllowsSetupAgain() {
        val f = Fixture(required = true)
        f.open()
        f.reply(TdApi.Error(404, "Not Found"))
        f.reply(TdApi.Error(404, "Not Found"))
        f.reply(passwordState(""))
        assertEquals(LoginEmailPhase.PROOF_PENDING, f.state.phase)
        assertEquals(LoginEmailIssue.PROOF_UNCONFIRMED, f.state.issue)
        assertTrue(f.state.mandatory)
        f.controller.refreshProof(f.token)
        f.reply(TdApi.Ok())
        f.reply(passwordState(""))
        assertEquals(LoginEmailPhase.EMAIL, f.state.phase)
        assertEquals(LoginEmailAvailability.ALLOWED, f.state.availability)
        assertNull(f.state.issue)
        assertFalse(f.state.verificationAccepted)
        f.controller.submitEmail(f.token, "private@example.test") {}
        f.reply(TdApi.Ok())
        assertIs<TdApi.SetLoginEmailAddress>(f.last.command)
        assertTrue(f.state.mandatory)
    }

    @Test fun staleObserverReplayCannotReplaceANewerRequiredIdentity() {
        val native = OfficialTdLoginEmail({ _, _, _ -> fail("Startup must not request native state") })
        val newer = LoginEmailSuggestions(revision = 2, requiredPresent = true, requiredEpoch = 2)
        val older = LoginEmailSuggestions(revision = 1, optionalPresent = true, optionalEpoch = 1)
        val reordered = object : TdLoginEmailSession by native {
            override fun suggestions() = LoginEmailSuggestions()
            override fun observeSuggestions(observer: ((LoginEmailSuggestions) -> Unit)?) {
                observer?.invoke(newer)
                observer?.invoke(older)
            }
        }
        val snapshots = mutableListOf<LoginEmailSnapshot>()
        val queued = ArrayDeque<() -> Unit>()
        LoginEmailController(3, reordered, { queued.addLast(it) }, snapshots::add).start()
        while (queued.isNotEmpty()) queued.removeFirst().invoke()
        assertEquals(newer, snapshots.last().suggestions)
        assertTrue(snapshots.last().mandatory)
        assertFalse(snapshots.last().suggestions.optionalPresent)
    }

    @Test fun nativeRequirementStillPresentAfterCheckDoesNotUnlockEvenWithPattern() {
        val f = Fixture(required = true)
        f.openAllowed(); f.sentCode()
        f.controller.check(assertNotNull(f.state.challenge), "1234") {}
        f.reply(TdApi.Ok())
        f.reply(TdApi.Ok())
        f.reply(passwordState("p***"))
        assertEquals(LoginEmailPhase.PROOF_PENDING, f.state.phase)
        assertTrue(f.state.mandatory)
        assertFalse(f.gate.allowsOrdinary())
        assertEquals(LoginEmailIssue.PROOF_UNCONFIRMED, f.state.issue)
    }

    @Test fun aNewRequiredEpochCannotBeSatisfiedByThePreviousOwnersLateProof() {
        val f = Fixture(required = true)
        f.open()
        f.reply(TdApi.Error(404, "Not Found"))
        f.reply(TdApi.Error(404, "Not Found"))
        val pendingState = f.last
        f.delta(required = true, remove = true, add = true)
        pendingState.reply(passwordState("old***")); f.drain()
        assertTrue(f.state.mandatory)
        assertNull(f.state.token)
        assertEquals(2L, f.state.suggestions.requiredEpoch)
        assertEquals(0L, f.state.satisfiedRequiredEpoch)
        assertFalse(f.gate.allowsOrdinary())
        f.open()
        assertEquals(2L, f.token.suggestionEpoch)
    }

    @Test fun nativeRequiredRemovalRetiresItsFormWithoutCallingTheDraftSuccessful() {
        val f = Fixture(required = true)
        f.openAllowed()
        f.delta(required = true, remove = true)
        f.drain()
        assertFalse(f.state.mandatory)
        assertNull(f.state.token)
        assertEquals(LoginEmailPhase.EMAIL, f.state.phase)
        assertFalse(f.state.verificationAccepted)
        assertTrue(f.gate.allowsOrdinary())
    }

    @Test fun requiredArrivalRetiresOptionalFormBeforeOldRequestCanStartAnotherStage() {
        val f = Fixture()
        f.openAllowed()
        val results = mutableListOf<LoginEmailOutcome>()
        f.controller.submitEmail(f.token, "private@example.test", results::add)
        val oldProbe = f.last
        f.delta(required = true, add = true)
        oldProbe.reply(TdApi.Ok()); f.drain()
        assertTrue(f.state.mandatory)
        assertNull(f.state.token)
        assertTrue(results.isEmpty())
        assertFalse(f.calls.any { it.command is TdApi.SetLoginEmailAddress })
    }

    @Test fun replacingThePageDoesNotLetAnOlderCallbackOrOpeningSnapshotMutateItsCodePhase() {
        val f = Fixture()
        var oldPage = true
        f.controller.open(1, 5, LoginEmailEntryKind.OPTIONAL) { oldPage }
        val oldToken = f.token
        val oldProbe = f.last
        oldPage = false
        f.controller.close(oldToken)
        f.controller.open(1, 6, LoginEmailEntryKind.OPTIONAL) { true }
        val replacement = f.token
        assertNotEquals(oldToken, replacement)
        oldProbe.reply(TdApi.Ok()); f.drain()
        assertEquals(replacement, f.token)
        assertEquals(6L, f.state.openingId)
        assertEquals(LoginEmailAvailability.CHECKING, f.state.availability)
        f.reply(TdApi.Ok())
        assertEquals(LoginEmailAvailability.ALLOWED, f.state.availability)
    }

    @Test fun externalPageLeaseStopsPendingSendBeforeItsTokenCloseReachesTheActor() {
        val f = Fixture()
        var page = true
        f.controller.open(1, 5, LoginEmailEntryKind.OPTIONAL) { page }
        f.reply(TdApi.Ok())
        f.controller.submitEmail(f.token, "private@example.test") {}
        val before = f.calls.size
        page = false
        f.reply(TdApi.Ok())
        assertEquals(before, f.calls.size)
        assertTrue(f.state.busy, "A disposed page's callback cannot publish success; its eventual exact close clears state.")
        val token = f.token
        f.controller.close(token)
        assertNull(f.state.token)
        assertFalse(f.state.busy)
    }

    @Test fun optionalHideWaitsForNativeDeltaInsteadOfInventingMembershipOrCompletion() {
        val f = Fixture()
        f.openAllowed()
        f.controller.hideOptional(1, 1)
        assertIs<TdApi.HideSuggestedAction>(f.last.command)
        f.reply(TdApi.Ok())
        assertTrue(f.state.suggestions.optionalPresent)
        assertNotEquals(LoginEmailPhase.COMPLETE, f.state.phase)
        f.delta(required = false, remove = true); f.drain()
        assertFalse(f.state.suggestions.optionalPresent)
        assertNull(f.state.token)
    }

    @Test fun optionalRemovalWhileAnOwnerlessHideIsPendingClearsBusyAndAllowsANewEpochAction() {
        val f = Fixture()
        assertNull(f.state.token)
        f.controller.hideOptional(1, 1)
        val oldHide = f.last
        assertTrue(f.state.busy)
        f.delta(required = false, remove = true); f.drain()
        assertFalse(f.state.busy)
        assertFalse(f.state.suggestions.optionalPresent)
        oldHide.reply(TdApi.Ok()); f.drain()
        assertFalse(f.state.busy)
        f.delta(required = false, add = true); f.drain()
        f.controller.hideOptional(1, 2)
        assertEquals(2, f.calls.size)
        assertIs<TdApi.HideSuggestedAction>(f.last.command)
        f.reply(TdApi.Error(500, "private native detail"))
        assertFalse(f.state.busy)
        assertEquals(LoginEmailIssue.REQUEST_FAILED, f.state.issue)
    }

    @Test fun requiredArrivalCancelsAnOwnerlessHideWithoutWaitingForItsCallback() {
        val f = Fixture()
        f.controller.hideOptional(1, 1)
        val oldHide = f.last
        f.delta(required = true, add = true); f.drain()
        assertTrue(f.state.mandatory)
        assertFalse(f.state.busy)
        oldHide.reply(TdApi.Ok()); f.drain()
        assertTrue(f.state.mandatory)
        assertTrue(f.state.suggestions.requiredPresent)
        assertTrue(f.state.suggestions.optionalPresent)
        f.controller.open(1, 2, LoginEmailEntryKind.MANDATORY) { true }
        assertEquals(LoginEmailEntryKind.MANDATORY, f.token.kind)
        assertIs<TdApi.IsLoginEmailAddressRequired>(f.last.command)
    }

    @Test fun unrelatedOptionalEpochChangesKeepTheRequiredFormsAvailabilityAndOwnership() {
        val f = Fixture(required = true)
        f.openAllowed()
        val owner = f.token
        val before = f.calls.size
        f.delta(required = false, add = true); f.drain()
        f.delta(required = false, remove = true); f.drain()
        assertEquals(owner, f.token)
        assertTrue(f.state.mandatory)
        assertEquals(LoginEmailAvailability.ALLOWED, f.state.availability)
        assertEquals(before, f.calls.size)
        assertFalse(f.gate.allowsOrdinary())
    }

    @Test fun aSatisfiedRequiredIdentityDoesNotPreventHidingTheIndependentOptionalSuggestion() {
        val f = Fixture(required = true)
        f.delta(required = false, add = true); f.drain()
        f.open()
        f.reply(TdApi.Error(404, "Not Found"))
        f.reply(TdApi.Error(404, "Not Found"))
        f.reply(passwordState("remote***"))
        assertFalse(f.state.mandatory)
        assertTrue(f.state.suggestions.requiredPresent)
        f.controller.hideOptional(1, 1)
        assertIs<TdApi.HideSuggestedAction>(f.last.command)
        f.reply(TdApi.Ok())
        assertFalse(f.state.busy)
        assertTrue(f.state.suggestions.optionalPresent)
        assertTrue(f.state.suggestions.requiredPresent)
        assertFalse(f.state.mandatory)
    }

    @Test fun failedSetAndWrongCodePreserveTheRetryablePhaseWithoutPublishingSecrets() {
        val f = Fixture()
        f.openAllowed()
        f.controller.submitEmail(f.token, "private@example.test") {}
        f.reply(TdApi.Ok()); f.reply(TdApi.Error(400, "EMAIL_INVALID"))
        assertEquals(LoginEmailPhase.EMAIL, f.state.phase)
        assertEquals(LoginEmailIssue.EMAIL_INVALID, f.state.issue)
        f.sentCode()
        f.controller.check(assertNotNull(f.state.challenge), "private-code") {}
        f.reply(TdApi.Error(400, "CODE_INVALID"))
        assertEquals(LoginEmailPhase.CODE, f.state.phase)
        assertEquals(LoginEmailIssue.CODE_INVALID, f.state.issue)
        assertFalse(f.snapshots.toString().contains("private-code"))
        assertFalse(f.snapshots.toString().contains("private@example.test"))
    }

    @Test fun deactivationUnsubscribesAndLateNativeCallbacksCannotRestartTheFlow() {
        val f = Fixture(required = true)
        f.open()
        val pending = f.last
        f.controller.deactivate()
        val count = f.snapshots.size
        pending.reply(TdApi.Ok()); f.drain()
        f.delta(required = true, remove = true); f.drain()
        assertEquals(count, f.snapshots.size)
        assertNull(f.state.token)
        assertEquals(1, f.calls.size)
    }

    private class Call(val command: TdApi.Function<*>, val reply: (TdApi.Object) -> Unit, val fail: () -> Unit)
    private class Fixture(required: Boolean = false, present: Boolean = true, ready: Boolean = true) {
        val gate = TdUserInteractionGate()
        val collector = TdLoginEmailSuggestions(gate)
        val calls = mutableListOf<Call>()
        var authorizationReady = ready
        private val native = OfficialTdLoginEmail({ request, reply, fail -> calls += Call(request, reply, fail) }, collector, gate, { authorizationReady })
        val snapshots = mutableListOf<LoginEmailSnapshot>()
        private val queue = ArrayDeque<() -> Unit>()
        val controller = LoginEmailController(1, native, { queue.addLast(it) }, snapshots::add)
        val kind = if (required) LoginEmailEntryKind.MANDATORY else LoginEmailEntryKind.OPTIONAL
        val state get() = snapshots.last()
        val token get() = assertNotNull(state.token)
        val last get() = calls.last()
        init { if (present) delta(required, add = true); controller.start(); drain() }
        fun open() = controller.open(1, 1, kind) { true }
        fun openAllowed() { open(); reply(TdApi.Ok()) }
        fun reply(value: TdApi.Object) { last.reply(value); drain() }
        fun drain() { while (queue.isNotEmpty()) queue.removeFirst().invoke() }
        fun delta(required: Boolean, add: Boolean = false, remove: Boolean = false) {
            val action = TdApi.SuggestedActionSetLoginEmailAddress(!required)
            collector.accept(TdApi.UpdateSuggestedActions(if (add) arrayOf(action) else emptyArray(), if (remove) arrayOf(action) else emptyArray()))
        }
        fun sentCode() {
            controller.submitEmail(token, "private@example.test") {}
            reply(TdApi.Ok()); reply(TdApi.EmailAddressAuthenticationCodeInfo("p***", 4))
        }
    }
    private companion object {
        fun passwordState(pattern: String) = TdApi.PasswordState().apply {
            hasPassword = false; loginEmailAddressPattern = pattern
        }
    }
}
