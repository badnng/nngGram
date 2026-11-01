package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import kotlin.test.*

class OfficialTdLoginEmailTest {
    @Test fun authorizationMustBeReadyBeforeAnyEmailSetupReadWriteHideOrConfiguredRequest() {
        val f = Fixture(ready = false)
        val availability = mutableListOf<LoginEmailAvailability>()
        val outcomes = mutableListOf<LoginEmailOutcome>()
        val proofs = mutableListOf<LoginEmailProof>()
        val configured = mutableListOf<SettingsResult<SettingsPasswordOutcome>>()
        f.api.probe(f.token, { true }, availability::add)
        outcomes += f.sendEmail()
        f.api.proof(f.token, { true }, proofs::add)
        f.api.hideOptional(1, { true }, outcomes::add)
        f.api.configured(SettingsSecurityToken(1, 1), SettingsPasswordCommand(SettingsPasswordOperation.SET_LOGIN_EMAIL,
            email = "private@example.test"), { true }, configured::add)
        assertEquals(listOf(LoginEmailAvailability.UNKNOWN), availability)
        assertTrue(outcomes.all { it.issue == LoginEmailIssue.STATE_CHANGED })
        assertEquals(LoginEmailIssue.STATE_CHANGED, proofs.single().issue)
        assertEquals(SettingsError.NOT_READY, configured.single().error)
        assertTrue(f.calls.isEmpty())
    }

    @Test fun losingReadyDuringSetupProbeOrSetPreventsItsNextNativeStepAndChallenge() {
        val beforeSet = Fixture()
        val first = beforeSet.sendEmail()
        beforeSet.authorizationReady = false
        beforeSet.last.reply(TdApi.Ok())
        assertEquals(1, beforeSet.calls.size)
        assertEquals(LoginEmailIssue.STATE_CHANGED, first.single().issue)
        val duringSet = Fixture()
        val second = duringSet.sendEmail()
        duringSet.last.reply(TdApi.Ok())
        duringSet.authorizationReady = false
        duringSet.last.reply(TdApi.EmailAddressAuthenticationCodeInfo("p***", 4))
        assertEquals(2, duringSet.calls.size)
        assertEquals(LoginEmailIssue.STATE_CHANGED, second.single().issue)
        assertNull(second.single().challenge)
        assertNull(second.single().codeInfo)
    }

    @Test fun losingReadyDuringEitherProofStageCannotIssueFreshReadOrReturnFulfillment() {
        val beforeState = Fixture(required = true)
        val first = mutableListOf<LoginEmailProof>()
        beforeState.api.proof(beforeState.token, { true }, first::add)
        beforeState.authorizationReady = false
        beforeState.last.reply(TdApi.Error(404, "Not Found"))
        assertEquals(1, beforeState.calls.size)
        assertEquals(LoginEmailIssue.STATE_CHANGED, first.single().issue)
        val duringState = Fixture(required = true)
        val second = mutableListOf<LoginEmailProof>()
        duringState.api.proof(duringState.token, { true }, second::add)
        duringState.last.reply(TdApi.Error(404, "Not Found"))
        assertIs<TdApi.GetPasswordState>(duringState.last.command)
        duringState.authorizationReady = false
        duringState.last.reply(state(pattern = "remote***"))
        assertEquals(LoginEmailIssue.STATE_CHANGED, second.single().issue)
        assertFalse(second.single().hasLoginEmail)
        assertFalse(duringState.gate.allowsOrdinary())
    }

    @Test fun losingReadyDuringConfiguredFreshReadPreventsItsSet() {
        val f = Fixture()
        val values = mutableListOf<SettingsResult<SettingsPasswordOutcome>>()
        f.api.configured(SettingsSecurityToken(1, 1), SettingsPasswordCommand(SettingsPasswordOperation.SET_LOGIN_EMAIL,
            email = "private@example.test"), { true }, values::add)
        f.authorizationReady = false
        f.last.reply(state(pattern = "existing***"))
        assertEquals(1, f.calls.size)
        assertEquals(SettingsError.NOT_READY, values.single().error)
        assertFalse(values.single().value?.verified == true)
    }

    @Test fun unconfiguredNoTwoFactorSetupUsesRealSuggestionProbeAndCodeResponse() {
        val f = Fixture(required = true)
        val response = f.sendEmail()
        assertIs<TdApi.IsLoginEmailAddressRequired>(f.last.command)
        f.last.reply(TdApi.Ok())
        assertEquals("private@example.test", assertIs<TdApi.SetLoginEmailAddress>(f.last.command).newLoginEmailAddress)
        f.last.reply(TdApi.EmailAddressAuthenticationCodeInfo("p***@example.test", 0))
        val sent = response.single()
        assertNull(sent.issue)
        assertEquals(0, sent.codeInfo?.length)
        assertEquals(f.token, sent.challenge?.owner)
        assertFalse(f.calls.any { it.command is TdApi.GetRecoveryEmailAddress || it.command is TdApi.GetMe })
        assertFalse(response.toString().contains("private@example.test"))
    }

    @Test fun absentSuggestionCannotBorrowSetupPermissionOrIssueAnyNativeRequest() {
        val f = Fixture(present = false)
        assertEquals(LoginEmailIssue.STATE_CHANGED, f.sendEmail().single().issue)
        assertTrue(f.calls.isEmpty())
    }

    @Test fun probeDistinguishesOkNotFoundAndNetworkWithoutChangingMembership() {
        for ((native, expected) in listOf(TdApi.Ok() to LoginEmailAvailability.ALLOWED,
                TdApi.Error(404, "Not Found") to LoginEmailAvailability.NOT_REQUIRED,
                TdApi.Error(500, "private network detail") to LoginEmailAvailability.FAILED)) {
            val f = Fixture()
            val values = mutableListOf<LoginEmailAvailability>()
            f.api.probe(f.token, { true }, values::add)
            f.last.reply(native)
            assertEquals(expected, values.single())
            assertTrue(f.api.suggestions().optionalPresent)
        }
    }

    @Test fun setRequiresANewSuccessfulProbeAndDoesNotTreatNotFoundAsAcceptedEmail() {
        val f = Fixture()
        val values = f.sendEmail()
        f.last.reply(TdApi.Error(404, "Not Found"))
        assertEquals(LoginEmailIssue.NOT_AVAILABLE, values.single().issue)
        assertEquals(1, f.calls.size)
        assertFalse(values.single().completed)
    }

    @Test fun failedSetCannotAuthorizeResendOrCheckUsingNativeLastEmail() {
        val f = Fixture()
        val sent = f.sendEmail()
        f.last.reply(TdApi.Ok())
        f.last.reply(TdApi.Error(400, "EMAIL_INVALID"))
        assertEquals(LoginEmailIssue.EMAIL_INVALID, sent.single().issue)
        val fake = LoginEmailChallengeToken(f.token, 1)
        val results = mutableListOf<LoginEmailOutcome>()
        f.api.resend(fake, { true }, results::add)
        f.api.check(fake, "1234", { true }, results::add)
        assertEquals(listOf(LoginEmailIssue.CODE_EXPIRED, LoginEmailIssue.CODE_EXPIRED), results.map { it.issue })
        assertEquals(2, f.calls.size)
    }

    @Test fun closingOwnerKeepsSingleflightUntilOriginalCallbackAndCannotGrantNewChallenge() {
        val f = Fixture()
        var oldPage = true
        val old = f.sendEmail(current = { oldPage })
        f.last.reply(TdApi.Ok())
        val pendingSet = f.last
        oldPage = false
        f.api.retire(f.token)
        val replacement = f.token.copy(owner = 2)
        val blocked = f.sendEmail(replacement)
        assertEquals(LoginEmailIssue.BUSY, blocked.single().issue)
        assertEquals(2, f.calls.size)
        pendingSet.reply(TdApi.EmailAddressAuthenticationCodeInfo("old***", 4))
        assertEquals(LoginEmailIssue.STATE_CHANGED, old.single().issue)
        assertNull(old.single().challenge)
        val retried = f.sendEmail(replacement)
        f.last.reply(TdApi.Ok())
        f.last.reply(TdApi.EmailAddressAuthenticationCodeInfo("new***", 5))
        assertEquals(replacement, retried.single().challenge?.owner)
        pendingSet.fail()
        assertEquals(1, old.size)
    }

    @Test fun retirementDuringTheCompletionLeaseCheckCannotPublishOrStoreAChallenge() {
        val f = Fixture()
        var retireDuringCheck = false
        val response = f.sendEmail(current = {
            if (retireDuringCheck) {
                retireDuringCheck = false
                f.api.retire(f.token)
            }
            true
        })
        f.last.reply(TdApi.Ok())
        retireDuringCheck = true
        f.last.reply(TdApi.EmailAddressAuthenticationCodeInfo("old***", 4))
        assertEquals(LoginEmailIssue.STATE_CHANGED, response.single().issue)
        assertNull(response.single().challenge)
        assertNull(response.single().codeInfo)
        val replacement = f.sendEmail(f.token.copy(owner = 2))
        f.last.reply(TdApi.Ok())
        f.last.reply(TdApi.EmailAddressAuthenticationCodeInfo("new***", 4))
        assertEquals(2L, replacement.single().challenge?.owner?.owner)
    }

    @Test fun resendRotatesExactChallengeAndOldCodeCapabilityCannotCrossIt() {
        val f = Fixture()
        val first = f.challenge()
        val resent = mutableListOf<LoginEmailOutcome>()
        f.api.resend(first, { true }, resent::add)
        assertIs<TdApi.ResendLoginEmailAddressCode>(f.last.command)
        f.last.reply(TdApi.EmailAddressAuthenticationCodeInfo("p***", 6))
        val second = assertNotNull(resent.single().challenge)
        assertNotEquals(first, second)
        val stale = mutableListOf<LoginEmailOutcome>()
        val count = f.calls.size
        f.api.check(first, "123456", { true }, stale::add)
        assertEquals(LoginEmailIssue.CODE_EXPIRED, stale.single().issue)
        assertEquals(count, f.calls.size)
    }

    @Test fun invalidAndExpiredCodeKeepOnlyTheSameOwnersExplicitResendCapability() {
        val f = Fixture()
        val challenge = f.challenge()
        val values = mutableListOf<LoginEmailOutcome>()
        f.api.check(challenge, "0000", { true }, values::add)
        f.last.reply(TdApi.Error(400, "CODE_INVALID"))
        assertEquals(LoginEmailIssue.CODE_INVALID, values.last().issue)
        f.api.check(challenge, "0000", { true }, values::add)
        f.last.reply(TdApi.Error(400, "EMAIL_HASH_EXPIRED"))
        assertEquals(LoginEmailIssue.CODE_EXPIRED, values.last().issue)
        f.api.resend(challenge, { true }, values::add)
        assertIs<TdApi.ResendLoginEmailAddressCode>(f.last.command)
        f.last.reply(TdApi.EmailAddressAuthenticationCodeInfo("p***", 4))
        assertNull(values.last().issue)
    }

    @Test fun acceptedCodeIsNotCompletionAndFreshProofReadsActualPasswordStateWithoutTwoFactor() {
        val f = Fixture(required = true)
        val challenge = f.challenge()
        val values = mutableListOf<LoginEmailOutcome>()
        f.api.check(challenge, "1234", { true }, values::add)
        assertEquals("1234", assertIs<TdApi.EmailAddressAuthenticationCode>(
            assertIs<TdApi.CheckLoginEmailAddressCode>(f.last.command).code).code)
        f.last.reply(TdApi.Ok())
        assertTrue(values.single().verificationAccepted)
        assertFalse(values.single().completed)
        assertFalse(f.gate.allowsOrdinary())
        val proof = mutableListOf<LoginEmailProof>()
        f.api.proof(f.token, { true }, proof::add)
        f.last.reply(TdApi.Error(404, "Not Found"))
        assertIs<TdApi.GetPasswordState>(f.last.command)
        f.last.reply(state(hasPassword = false, pattern = "p***"))
        assertTrue(proof.single().hasLoginEmail)
        assertEquals(LoginEmailAvailability.NOT_REQUIRED, proof.single().availability)
        assertFalse(f.gate.allowsOrdinary()) // The controller applies exact-epoch proof, not Check's Ok.
    }

    @Test fun proofKeepsAvailabilitySeparateFromEmptyOrFailedState() {
        val f = Fixture(required = true)
        val proofs = mutableListOf<LoginEmailProof>()
        f.api.proof(f.token, { true }, proofs::add)
        f.last.reply(TdApi.Error(404, "Not Found"))
        f.last.reply(state(pattern = ""))
        assertFalse(proofs.last().hasLoginEmail)
        f.api.proof(f.token, { true }, proofs::add)
        f.last.reply(TdApi.Ok())
        f.last.reply(state(pattern = "p***"))
        assertEquals(LoginEmailAvailability.ALLOWED, proofs.last().availability)
        assertTrue(proofs.last().hasLoginEmail)
        f.api.proof(f.token, { true }, proofs::add)
        f.last.reply(TdApi.Error(404, "Not Found"))
        f.last.fail()
        assertEquals(LoginEmailIssue.REQUEST_FAILED, proofs.last().issue)
        assertFalse(f.gate.allowsOrdinary())
    }

    @Test fun closingDuringProbePreventsEveryFollowingNativeStage() {
        val f = Fixture()
        var current = true
        val values = f.sendEmail(current = { current })
        current = false
        f.last.reply(TdApi.Ok())
        assertEquals(1, f.calls.size)
        assertEquals(LoginEmailIssue.STATE_CHANGED, values.single().issue)
        val proof = mutableListOf<LoginEmailProof>()
        current = true
        f.api.proof(f.token, { current }, proof::add)
        current = false
        f.last.reply(TdApi.Error(404, "Not Found"))
        assertEquals(2, f.calls.size)
        assertEquals(LoginEmailIssue.STATE_CHANGED, proof.single().issue)
    }

    @Test fun configuredChangeAndSuggestedSetupShareOneInstanceFlight() {
        val f = Fixture()
        val old = mutableListOf<SettingsResult<SettingsPasswordOutcome>>()
        val owner = SettingsSecurityToken(1, 7)
        f.api.configured(owner, SettingsPasswordCommand(SettingsPasswordOperation.SET_LOGIN_EMAIL,
            email = "configured@example.test"), { true }, old::add)
        assertIs<TdApi.GetPasswordState>(f.last.command)
        assertEquals(LoginEmailIssue.BUSY, f.sendEmail().single().issue)
        f.last.reply(state(pattern = "existing***"))
        assertIs<TdApi.SetLoginEmailAddress>(f.last.command)
        f.last.reply(TdApi.EmailAddressAuthenticationCodeInfo("c***", 4))
        assertNull(old.single().error)
        val setup = f.sendEmail()
        f.last.reply(TdApi.Ok())
        f.last.reply(TdApi.EmailAddressAuthenticationCodeInfo("s***", 4))
        assertNotNull(setup.single().challenge)
        val count = f.calls.size
        f.api.configured(owner, SettingsPasswordCommand(SettingsPasswordOperation.RESEND_LOGIN_EMAIL), { true }, old::add)
        assertEquals(SettingsPasswordIssue.CODE_EXPIRED, old.last().value?.issue)
        assertEquals(count, f.calls.size)
    }

    @Test fun configuredEmptyPatternCannotBeReinterpretedAsUnconfiguredSetup() {
        val f = Fixture()
        val result = mutableListOf<SettingsResult<SettingsPasswordOutcome>>()
        f.api.configured(SettingsSecurityToken(1, 1), SettingsPasswordCommand(SettingsPasswordOperation.SET_LOGIN_EMAIL,
            email = "configured@example.test"), { true }, result::add)
        f.last.reply(state(hasPassword = false, pattern = ""))
        assertEquals(SettingsError.UNSUPPORTED, result.single().error)
        assertEquals(1, f.calls.size)
    }

    @Test fun configuredFailedSetDoesNotPermitResendFromNativeHiddenLastAddress() {
        val f = Fixture()
        val owner = SettingsSecurityToken(1, 1)
        val results = mutableListOf<SettingsResult<SettingsPasswordOutcome>>()
        f.api.configured(owner, SettingsPasswordCommand(SettingsPasswordOperation.SET_LOGIN_EMAIL,
            email = "configured@example.test"), { true }, results::add)
        f.last.reply(state(pattern = "existing***"))
        f.last.reply(TdApi.Error(400, "EMAIL_NOT_ALLOWED"))
        assertEquals(SettingsPasswordIssue.EMAIL_INVALID, results.single().value?.issue)
        f.api.configured(owner, SettingsPasswordCommand(SettingsPasswordOperation.RESEND_LOGIN_EMAIL), { true }, results::add)
        assertEquals(SettingsPasswordIssue.CODE_EXPIRED, results.last().value?.issue)
        assertEquals(2, f.calls.size)
    }

    @Test fun configuredConfirmationMustReadBackActualPatternBeforeReportingVerified() {
        val f = Fixture()
        val owner = SettingsSecurityToken(1, 1)
        val results = mutableListOf<SettingsResult<SettingsPasswordOutcome>>()
        f.api.configured(owner, SettingsPasswordCommand(SettingsPasswordOperation.SET_LOGIN_EMAIL,
            email = "configured@example.test"), { true }, results::add)
        f.last.reply(state(pattern = "existing***"))
        f.last.reply(TdApi.EmailAddressAuthenticationCodeInfo("c***", 4))
        f.api.configured(owner, SettingsPasswordCommand(SettingsPasswordOperation.CONFIRM_LOGIN_EMAIL, code = "1234"), { true }, results::add)
        f.last.reply(state(pattern = "existing***"))
        f.last.reply(TdApi.Ok())
        assertIs<TdApi.GetPasswordState>(f.last.command)
        assertEquals(1, results.size)
        f.last.reply(state(pattern = "new***"))
        assertTrue(results.last().value?.verified == true)
        assertEquals("new***", results.last().value?.state?.loginEmailPattern)
    }

    @Test fun configuredCheckOkWithFailedOrEmptyFreshStateCannotReportVerifiedOrReuseTheCode() {
        for (fresh in listOf<TdApi.Object>(TdApi.Error(500, "private native detail"), state(pattern = ""))) {
            val f = Fixture()
            val owner = SettingsSecurityToken(1, 1)
            val results = mutableListOf<SettingsResult<SettingsPasswordOutcome>>()
            f.api.configured(owner, SettingsPasswordCommand(SettingsPasswordOperation.SET_LOGIN_EMAIL,
                email = "configured@example.test"), { true }, results::add)
            f.last.reply(state(pattern = "existing***"))
            f.last.reply(TdApi.EmailAddressAuthenticationCodeInfo("c***", 4))
            f.api.configured(owner, SettingsPasswordCommand(SettingsPasswordOperation.CONFIRM_LOGIN_EMAIL, code = "1234"), { true }, results::add)
            f.last.reply(state(pattern = "existing***"))
            f.last.reply(TdApi.Ok())
            assertIs<TdApi.GetPasswordState>(f.last.command)
            f.last.reply(fresh)
            assertNotNull(results.last().error)
            assertFalse(results.last().value?.verified == true)
            assertEquals(SettingsPasswordIssue.STATE_CHANGED, results.last().value?.issue)
            val count = f.calls.size
            f.api.configured(owner, SettingsPasswordCommand(SettingsPasswordOperation.CONFIRM_LOGIN_EMAIL, code = "1234"), { true }, results::add)
            assertEquals(SettingsPasswordIssue.CODE_EXPIRED, results.last().value?.issue)
            assertEquals(count, f.calls.size)
        }
    }

    @Test fun requiredArrivalDuringConfiguredFreshReadStopsItsSetAndKeepsTheSetupPathIndependent() {
        val f = Fixture()
        val results = mutableListOf<SettingsResult<SettingsPasswordOutcome>>()
        f.api.configured(SettingsSecurityToken(1, 1), SettingsPasswordCommand(SettingsPasswordOperation.SET_LOGIN_EMAIL,
            email = "configured@example.test"), { true }, results::add)
        val pendingState = f.last
        f.collector.accept(TdApi.UpdateSuggestedActions(arrayOf(TdApi.SuggestedActionSetLoginEmailAddress(false)), emptyArray()))
        pendingState.reply(state(pattern = "existing***"))
        assertEquals(1, f.calls.size)
        assertEquals(SettingsError.NOT_READY, results.single().error)
        assertFalse(results.single().value?.verified == true)
        assertFalse(f.gate.allowsOrdinary())
        val mandatory = f.token.copy(kind = LoginEmailEntryKind.MANDATORY)
        val setup = f.sendEmail(mandatory)
        assertIs<TdApi.IsLoginEmailAddressRequired>(f.last.command)
        f.last.reply(TdApi.Ok())
        f.last.reply(TdApi.EmailAddressAuthenticationCodeInfo("m***", 4))
        assertEquals(mandatory, setup.single().challenge?.owner)
        assertFalse(f.gate.allowsOrdinary())
    }

    @Test fun requiredArrivalAfterConfiguredCheckOkPreventsTheFreshStateRequestAndAnyVerifiedResult() {
        val f = Fixture()
        val owner = SettingsSecurityToken(1, 1)
        val results = mutableListOf<SettingsResult<SettingsPasswordOutcome>>()
        f.api.configured(owner, SettingsPasswordCommand(SettingsPasswordOperation.SET_LOGIN_EMAIL,
            email = "configured@example.test"), { true }, results::add)
        f.last.reply(state(pattern = "existing***"))
        f.last.reply(TdApi.EmailAddressAuthenticationCodeInfo("c***", 4))
        f.api.configured(owner, SettingsPasswordCommand(SettingsPasswordOperation.CONFIRM_LOGIN_EMAIL, code = "1234"), { true }, results::add)
        f.last.reply(state(pattern = "existing***"))
        val pendingCheck = f.last
        assertIs<TdApi.CheckLoginEmailAddressCode>(pendingCheck.command)
        val count = f.calls.size
        f.collector.accept(TdApi.UpdateSuggestedActions(arrayOf(TdApi.SuggestedActionSetLoginEmailAddress(false)), emptyArray()))
        pendingCheck.reply(TdApi.Ok())
        assertEquals(count, f.calls.size)
        assertFalse(results.last().value?.verified == true)
        assertEquals(SettingsError.NOT_READY, results.last().error)
        assertFalse(f.gate.allowsOrdinary())
    }

    @Test fun replacingAConfiguredOwnerDoesNotBorrowItsOldNativeChallenge() {
        val f = Fixture()
        val old = SettingsSecurityToken(1, 1)
        val replacement = SettingsSecurityToken(1, 2)
        val results = mutableListOf<SettingsResult<SettingsPasswordOutcome>>()
        f.api.configured(old, SettingsPasswordCommand(SettingsPasswordOperation.SET_LOGIN_EMAIL,
            email = "configured@example.test"), { true }, results::add)
        f.last.reply(state(pattern = "existing***"))
        f.last.reply(TdApi.EmailAddressAuthenticationCodeInfo("c***", 4))
        f.api.retireConfigured(old)
        val count = f.calls.size
        f.api.configured(replacement, SettingsPasswordCommand(SettingsPasswordOperation.RESEND_LOGIN_EMAIL), { true }, results::add)
        assertEquals(SettingsPasswordIssue.CODE_EXPIRED, results.last().value?.issue)
        assertEquals(count, f.calls.size)
        f.api.configured(replacement, SettingsPasswordCommand(SettingsPasswordOperation.SET_LOGIN_EMAIL,
            email = "replacement@example.test"), { true }, results::add)
        f.last.reply(state(pattern = "existing***"))
        f.last.reply(TdApi.EmailAddressAuthenticationCodeInfo("new***", 0))
        assertEquals(0, results.last().value?.codeInfo?.length)
    }

    @Test fun realSessionFacadesUseTheSameBrokerAndAnEarlyRequiredDeltaGatesOrdinaryRequests() {
        val gate = TdUserInteractionGate()
        val suggestions = TdLoginEmailSuggestions(gate)
        suggestions.accept(TdApi.UpdateSuggestedActions(arrayOf(TdApi.SuggestedActionSetLoginEmailAddress(false)), emptyArray()))
        val calls = mutableListOf<TdApi.Function<*>>()
        val session = OfficialTdSession(false, gate, suggestions) { command, _, _ -> calls += command }
        assertTrue(assertNotNull(session.loginEmail).suggestions().requiredPresent)
        assertFalse(session.interactionGate.allowsOrdinary())
        val result = mutableListOf<SettingsResult<SettingsPasswordOutcome>>()
        assertNotNull(session.settings).passwordForOwner(SettingsSecurityToken(1, 1),
            SettingsPasswordCommand(SettingsPasswordOperation.SET_LOGIN_EMAIL, email = "private@example.test"), { true }, result::add)
        assertNotNull(result.single().error)
        assertTrue(calls.isEmpty())
        session.close()
        assertIs<TdApi.Close>(calls.single())
    }

    @Test fun realSessionEmailBrokerSharesTheReadySupplierWithoutInventingAMandatoryLock() {
        val gate = TdUserInteractionGate()
        val suggestions = TdLoginEmailSuggestions(gate)
        suggestions.accept(TdApi.UpdateSuggestedActions(arrayOf(TdApi.SuggestedActionSetLoginEmailAddress(true)), emptyArray()))
        var ready = false
        val calls = mutableListOf<Call>()
        val session = OfficialTdSession(false, gate, suggestions, { ready }) { request, reply, fail -> calls += Call(request, reply, fail) }
        val email = session.loginEmail
        val token = LoginEmailSetupToken(1, 1, 1, LoginEmailEntryKind.OPTIONAL)
        val values = mutableListOf<LoginEmailAvailability>()
        assertFalse(email.authorizationReady())
        assertTrue(gate.allowsOrdinary(), "Authorization readiness is independent of the mandatory-email condition.")
        email.probe(token, { true }, values::add)
        assertTrue(calls.isEmpty())
        ready = true
        email.probe(token, { true }, values::add)
        assertIs<TdApi.IsLoginEmailAddressRequired>(calls.single().command)
        ready = false
        calls.single().reply(TdApi.Ok())
        assertEquals(listOf(LoginEmailAvailability.UNKNOWN, LoginEmailAvailability.UNKNOWN), values)
        session.close()
        assertIs<TdApi.Close>(calls.last().command)
    }

    @Test fun hideUsesOnlyExactOptionalActionAndNeverFabricatesMembershipRemoval() {
        val f = Fixture()
        val values = mutableListOf<LoginEmailOutcome>()
        f.api.hideOptional(f.token.suggestionEpoch, { true }, values::add)
        assertTrue(assertIs<TdApi.SuggestedActionSetLoginEmailAddress>(assertIs<TdApi.HideSuggestedAction>(f.last.command).action).canBeHidden)
        f.last.reply(TdApi.Ok())
        assertTrue(values.single().completed)
        assertTrue(f.api.suggestions().optionalPresent)
        val required = Fixture(required = true)
        required.api.hideOptional(required.token.suggestionEpoch, { true }, values::add)
        assertEquals(LoginEmailIssue.STATE_CHANGED, values.last().issue)
        assertTrue(required.calls.isEmpty())
    }

    @Test fun retiredBrokerAndSuggestionEpochChangesRejectLateCapabilitiesAndNativeChains() {
        val f = Fixture(required = true)
        val sent = f.sendEmail()
        val pending = f.last
        f.replaceSuggestion()
        pending.reply(TdApi.Ok())
        assertEquals(LoginEmailIssue.STATE_CHANGED, sent.single().issue)
        assertEquals(1, f.calls.size)
        f.api.retireAll()
        assertEquals(LoginEmailIssue.STATE_CHANGED, f.sendEmail(f.token.copy(suggestionEpoch = 2)).single().issue)
        assertEquals(1, f.calls.size)
    }

    @Test fun explicitRetirementRejectsLateOwnerEvenBeforeTheCallersLeaseChanges() {
        val f = Fixture()
        val response = f.sendEmail()
        f.last.reply(TdApi.Ok())
        val pending = f.last
        f.api.retire(f.token)
        pending.reply(TdApi.EmailAddressAuthenticationCodeInfo("old***", 4))
        assertEquals(LoginEmailIssue.STATE_CHANGED, response.single().issue)
        assertNull(response.single().challenge)
        val count = f.calls.size
        assertEquals(LoginEmailIssue.STATE_CHANGED, f.sendEmail().single().issue)
        assertEquals(count, f.calls.size)
        assertTrue(f.sendEmail(f.token.copy(owner = 2)).isEmpty())
        assertEquals(count + 1, f.calls.size)
    }

    private class Call(val command: TdApi.Function<*>, val reply: (TdApi.Object) -> Unit, val fail: () -> Unit)
    private class Fixture(required: Boolean = false, present: Boolean = true, ready: Boolean = true) {
        val gate = TdUserInteractionGate()
        val collector = TdLoginEmailSuggestions(gate)
        val calls = mutableListOf<Call>()
        var authorizationReady = ready
        val api = OfficialTdLoginEmail({ request, reply, fail -> calls += Call(request, reply, fail) }, collector, gate, { authorizationReady })
        private val kind = if (required) LoginEmailEntryKind.MANDATORY else LoginEmailEntryKind.OPTIONAL
        val token = LoginEmailSetupToken(1, 1, 1, kind)
        val last get() = calls.last()
        init { if (present) collector.accept(TdApi.UpdateSuggestedActions(arrayOf(TdApi.SuggestedActionSetLoginEmailAddress(!required)), emptyArray())) }
        fun sendEmail(token: LoginEmailSetupToken = this.token, current: () -> Boolean = { true }): MutableList<LoginEmailOutcome> {
            val values = mutableListOf<LoginEmailOutcome>()
            api.sendEmail(token, " private@example.test ", current, values::add)
            return values
        }
        fun challenge(): LoginEmailChallengeToken {
            val values = sendEmail()
            last.reply(TdApi.Ok())
            last.reply(TdApi.EmailAddressAuthenticationCodeInfo("p***", 4))
            return assertNotNull(values.single().challenge)
        }
        fun replaceSuggestion() {
            val action = TdApi.SuggestedActionSetLoginEmailAddress(kind == LoginEmailEntryKind.OPTIONAL)
            collector.accept(TdApi.UpdateSuggestedActions(arrayOf(action), arrayOf(action)))
        }
    }
    private companion object {
        fun state(hasPassword: Boolean = true, pattern: String = "") = TdApi.PasswordState().apply {
            this.hasPassword = hasPassword; loginEmailAddressPattern = pattern
        }
    }
}
