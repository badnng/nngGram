package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import kotlin.test.*

class OfficialTdSettingsPasswordTest {
    @Test
    fun passwordStatePreservesNativePendingAndMaskedFieldsWithoutFullEmailOrSecrets() {
        val fixture = Fixture()
        val results = mutableListOf<SettingsResult<SettingsPasswordState>>()
        fixture.api.state(results::add)
        assertIs<TdApi.GetPasswordState>(fixture.last.command)
        fixture.last.respond(passwordState(pending = true, resetDate = 12345, login = "l***@example.test"))
        val state = assertNotNull(results.single().value)
        assertTrue(state.hasPassword)
        assertTrue(state.hasRecoveryEmail)
        assertTrue(state.hasPassportData)
        assertEquals("native hint", state.passwordHint)
        assertEquals(SettingsEmailCodeInfo("r***@example.test", 0), state.recoveryEmailCodeInfo)
        assertEquals(12345, state.pendingResetDate)
        assertTrue(state.loginEmailSupported)
        assertFalse(state.toString().contains("native hint"))
        assertFalse(results.toString().contains("example.test"))
        fixture.last.respond(passwordState(hasPassword = false))
        fixture.last.fail()
        assertEquals(1, results.size)
    }

    @Test
    fun currentPasswordIsVerifiedNativelyAndTheCompleteAddressNeverLeavesTheHelper() {
        val fixture = Fixture()
        val results = fixture.execute(SettingsPasswordCommand(SettingsPasswordOperation.VERIFY_CURRENT,
            currentPassword = "  exact password  "))
        assertEquals("  exact password  ", assertIs<TdApi.GetRecoveryEmailAddress>(fixture.last.command).password)
        fixture.last.respond(TdApi.RecoveryEmailAddress("full-private-address@example.test"))
        assertIs<TdApi.GetPasswordState>(fixture.last.command)
        assertTrue(results.isEmpty())
        fixture.last.respond(passwordState())
        assertTrue(assertNotNull(results.single().value).verified)
        assertFalse(results.toString().contains("full-private-address"))
    }

    @Test
    fun wrongCurrentPasswordDoesNotEnterManagementOrRequestAnotherState() {
        val fixture = Fixture()
        val results = fixture.execute(SettingsPasswordCommand(SettingsPasswordOperation.VERIFY_CURRENT, currentPassword = "wrong"))
        fixture.last.respond(TdApi.Error(400, "PASSWORD_HASH_INVALID"))
        assertEquals(SettingsPasswordIssue.PASSWORD_INVALID, results.single().value?.issue)
        assertEquals(SettingsError.INVALID_VALUE, results.single().error)
        assertFalse(results.single().value?.verified == true)
        assertEquals(1, fixture.requests.size)
    }

    @Test
    fun verifyingTheCurrentPasswordCancelsAPendingResetBeforeUnlockingManagement() {
        val fixture = Fixture()
        val results = fixture.execute(SettingsPasswordCommand(SettingsPasswordOperation.VERIFY_CURRENT, currentPassword = "known"))
        fixture.last.respond(TdApi.RecoveryEmailAddress("private@example.test"))
        fixture.last.respond(passwordState(resetDate = 12345))
        assertIs<TdApi.CancelPasswordReset>(fixture.last.command)
        assertTrue(results.isEmpty())
        fixture.last.respond(TdApi.Ok())
        assertIs<TdApi.GetPasswordState>(fixture.last.command)
        fixture.last.respond(passwordState(resetDate = 0))
        assertTrue(assertNotNull(results.single().value).verified)
        assertEquals(0, results.single().value?.state?.pendingResetDate)
        val failed = Fixture()
        val failedResults = failed.execute(SettingsPasswordCommand(SettingsPasswordOperation.VERIFY_CURRENT, currentPassword = "known"))
        failed.last.respond(TdApi.RecoveryEmailAddress("private@example.test"))
        failed.last.respond(passwordState(resetDate = 12345))
        failed.last.respond(TdApi.Error(500, "private failure"))
        assertNotNull(failedResults.single().error)
        assertFalse(failedResults.single().value?.verified == true)
        assertEquals(3, failed.requests.size)
    }

    @Test
    fun aPasswordRemovedRemotelyCannotTurnAnEmptyNativePrivateStateIntoSuccessfulVerification() {
        val fixture = Fixture()
        val results = fixture.execute(SettingsPasswordCommand(SettingsPasswordOperation.VERIFY_CURRENT, currentPassword = "stale"))
        fixture.last.respond(TdApi.RecoveryEmailAddress(""))
        fixture.last.respond(passwordState(hasPassword = false))
        assertEquals(SettingsPasswordIssue.PASSWORD_CHANGED, results.single().value?.issue)
        assertNotNull(results.single().error)
        assertFalse(results.single().value?.verified == true)
    }

    @Test
    fun settingPasswordUsesExactFieldsAndPendingEmailIsOnlyAnAcceptedNextStage() {
        val fixture = Fixture()
        val command = SettingsPasswordCommand(SettingsPasswordOperation.SET_PASSWORD,
            currentPassword = " old ", newPassword = " new ", hint = "native hint",
            email = "new@example.test", setRecoveryEmail = true)
        val results = fixture.execute(command)
        val native = assertIs<TdApi.SetPassword>(fixture.last.command)
        assertEquals(" old ", native.oldPassword)
        assertEquals(" new ", native.newPassword)
        assertEquals("native hint", native.newHint)
        assertTrue(native.setRecoveryEmailAddress)
        assertEquals("new@example.test", native.newRecoveryEmailAddress)
        fixture.last.respond(passwordState(hasPassword = false, pending = true))
        assertEquals(SettingsEmailCodeInfo("r***@example.test", 0), results.single().value?.codeInfo)
        assertNotNull(results.single().value?.state?.recoveryEmailCodeInfo)
        assertNull(results.single().error)
        assertFalse(results.single().value?.verified == true)
        assertEquals(1, fixture.requests.size)
        assertFalse(command.toString().contains(" new "))
        assertFalse(command.toString().contains("new@example.test"))
    }

    @Test
    fun passwordChangeWithoutEmailChangeStillVerifiesTheActualNewPassword() {
        val fixture = Fixture()
        val results = fixture.execute(SettingsPasswordCommand(SettingsPasswordOperation.SET_PASSWORD,
            currentPassword = "old", newPassword = "new", hint = "hint", email = "ignored@example.test"))
        val native = assertIs<TdApi.SetPassword>(fixture.last.command)
        assertFalse(native.setRecoveryEmailAddress)
        assertEquals("", native.newRecoveryEmailAddress)
        fixture.last.respond(passwordState())
        assertEquals("new", assertIs<TdApi.GetRecoveryEmailAddress>(fixture.last.command).password)
        assertTrue(results.isEmpty())
        fixture.last.respond(TdApi.RecoveryEmailAddress("existing@example.test"))
        assertNotNull(results.single().value?.state)
        assertNull(results.single().error)
    }

    @Test
    fun aPasswordMutationCannotClaimSuccessFromAnUnexpectedHasPasswordValue() {
        val fixture = Fixture()
        val results = fixture.execute(SettingsPasswordCommand(SettingsPasswordOperation.SET_PASSWORD, newPassword = "new"))
        fixture.last.respond(passwordState(hasPassword = false))
        assertEquals(SettingsPasswordIssue.STATE_CHANGED, results.single().value?.issue)
        assertNotNull(results.single().error)
        assertEquals(1, fixture.requests.size)
    }

    @Test
    fun explicitRemovalUsesAnEmptyNewPasswordAndNeverForwardsOtherFormFields() {
        val fixture = Fixture()
        val results = fixture.execute(SettingsPasswordCommand(SettingsPasswordOperation.REMOVE_PASSWORD,
            currentPassword = "old", newPassword = "ignored", hint = "ignored", email = "ignored@example.test",
            setRecoveryEmail = true))
        val native = assertIs<TdApi.SetPassword>(fixture.last.command)
        assertEquals("old", native.oldPassword)
        assertEquals("", native.newPassword)
        assertEquals("", native.newHint)
        assertFalse(native.setRecoveryEmailAddress)
        assertEquals("", native.newRecoveryEmailAddress)
        fixture.last.respond(passwordState(hasPassword = false))
        assertFalse(assertNotNull(results.single().value?.state).hasPassword)
        assertNull(results.single().error)

        val pending = fixture.execute(SettingsPasswordCommand(SettingsPasswordOperation.REMOVE_PASSWORD, currentPassword = "old"))
        fixture.last.respond(passwordState(pending = true))
        assertEquals(SettingsPasswordIssue.CONFIRMATION_NOT_ACCEPTED, pending.single().value?.issue)
        assertNotNull(pending.single().error)
    }

    @Test
    fun immediateRecoveryEmailChangesMustMatchTheActualNativeAddress() {
        val fixture = Fixture()
        val results = fixture.execute(SettingsPasswordCommand(SettingsPasswordOperation.SET_RECOVERY_EMAIL,
            currentPassword = "current", email = "expected@example.test"))
        val native = assertIs<TdApi.SetRecoveryEmailAddress>(fixture.last.command)
        assertEquals("current", native.password)
        assertEquals("expected@example.test", native.newRecoveryEmailAddress)
        fixture.last.respond(passwordState())
        fixture.last.respond(TdApi.RecoveryEmailAddress("old@example.test"))
        assertEquals(SettingsPasswordIssue.CONFIRMATION_NOT_ACCEPTED, results.single().value?.issue)
        assertNotNull(results.single().error)

        val removed = fixture.execute(SettingsPasswordCommand(SettingsPasswordOperation.SET_RECOVERY_EMAIL, currentPassword = "current"))
        assertEquals("", assertIs<TdApi.SetRecoveryEmailAddress>(fixture.last.command).newRecoveryEmailAddress)
        fixture.last.respond(passwordState(recoveryEmail = false))
        fixture.last.respond(TdApi.RecoveryEmailAddress(""))
        assertNull(removed.single().error)
        assertFalse(assertNotNull(removed.single().value?.state).hasRecoveryEmail)
    }

    @Test
    fun invalidConfirmationReturnedAsPendingPasswordStateIsNeverSuccess() {
        val fixture = Fixture()
        val results = fixture.execute(confirmation())
        assertEquals("1234", assertIs<TdApi.CheckRecoveryEmailAddressCode>(fixture.last.command).code)
        fixture.last.respond(passwordState(pending = true))
        assertEquals(SettingsPasswordIssue.CONFIRMATION_NOT_ACCEPTED, results.single().value?.issue)
        assertNotNull(results.single().error)
        assertNotNull(results.single().value?.state?.recoveryEmailCodeInfo)
        assertEquals(1, fixture.requests.size)
    }

    @Test
    fun expiredConfirmationWhosePendingStateDisappearedMustStillMatchTheRequestedEmail() {
        val fixture = Fixture()
        val results = fixture.execute(confirmation())
        fixture.last.respond(passwordState())
        assertEquals("effective", assertIs<TdApi.GetRecoveryEmailAddress>(fixture.last.command).password)
        fixture.last.respond(TdApi.RecoveryEmailAddress("old@example.test"))
        assertEquals(SettingsPasswordIssue.CONFIRMATION_NOT_ACCEPTED, results.single().value?.issue)
        assertNotNull(results.single().error)

        val accepted = fixture.execute(confirmation())
        fixture.last.respond(passwordState())
        fixture.last.respond(TdApi.RecoveryEmailAddress("EXPECTED@EXAMPLE.TEST"))
        assertNull(accepted.single().error)
        assertNull(accepted.single().value?.issue)
        assertTrue(accepted.single().value?.verified == true)
        assertNull(accepted.single().value?.state?.recoveryEmailCodeInfo)
        assertFalse(accepted.toString().contains("EXPECTED"))
    }

    @Test
    fun resumedPendingConfirmationWithoutLocalSecretsReportsOnlyTheNewNativeState() {
        val fixture = Fixture()
        val command = SettingsPasswordCommand(SettingsPasswordOperation.CONFIRM_RECOVERY_EMAIL, code = "1234")
        val pending = fixture.execute(command)
        fixture.last.respond(passwordState(pending = true))
        assertEquals(SettingsPasswordIssue.CONFIRMATION_NOT_ACCEPTED, pending.single().value?.issue)
        assertNotNull(pending.single().error)
        val noLongerPending = fixture.execute(command)
        fixture.last.respond(passwordState())
        assertNull(noLongerPending.single().error)
        assertNotNull(noLongerPending.single().value?.state)
        assertFalse(noLongerPending.single().value?.verified == true)
        assertEquals(2, fixture.requests.size)
        val noPassword = fixture.execute(command)
        fixture.last.respond(passwordState(hasPassword = false, recoveryEmail = false))
        assertNull(noPassword.single().error)
        assertFalse(noPassword.single().value?.verified == true)
        assertFalse(assertNotNull(noPassword.single().value?.state).hasPassword)
        assertEquals(3, fixture.requests.size)
    }

    @Test
    fun confirmationRequiresAnExistingPasswordAndTheExpectedRecoveryEmailPresence() {
        val fixture = Fixture()
        val removed = fixture.execute(confirmation())
        fixture.last.respond(passwordState(hasPassword = false))
        assertEquals(SettingsPasswordIssue.STATE_CHANGED, removed.single().value?.issue)
        val noEmail = fixture.execute(confirmation())
        fixture.last.respond(passwordState(recoveryEmail = false))
        assertEquals(SettingsPasswordIssue.CONFIRMATION_NOT_ACCEPTED, noEmail.single().value?.issue)
        assertEquals(2, fixture.requests.size)
    }

    @Test
    fun resendingAndCancellingRecoveryEmailUseTheirSeparateNativeStateResults() {
        val fixture = Fixture()
        val resend = fixture.execute(SettingsPasswordCommand(SettingsPasswordOperation.RESEND_RECOVERY_EMAIL))
        assertIs<TdApi.ResendRecoveryEmailAddressCode>(fixture.last.command)
        fixture.last.respond(passwordState(pending = true))
        assertNotNull(resend.single().value?.codeInfo)
        assertNull(resend.single().error)
        val expired = fixture.execute(SettingsPasswordCommand(SettingsPasswordOperation.RESEND_RECOVERY_EMAIL))
        fixture.last.respond(passwordState())
        assertEquals(SettingsPasswordIssue.CODE_EXPIRED, expired.single().value?.issue)
        assertNotNull(expired.single().error)
        val cancel = fixture.execute(SettingsPasswordCommand(SettingsPasswordOperation.CANCEL_RECOVERY_EMAIL))
        assertIs<TdApi.CancelRecoveryEmailAddressVerification>(fixture.last.command)
        fixture.last.respond(passwordState(hasPassword = false))
        assertNull(cancel.single().error)
        val stillPending = fixture.execute(SettingsPasswordCommand(SettingsPasswordOperation.CANCEL_RECOVERY_EMAIL))
        fixture.last.respond(passwordState(pending = true))
        assertNotNull(stillPending.single().error)
    }

    @Test
    fun recoveryRequestsAllowTheNativeZeroCodeLengthAndCodeVerificationDoesNotChangePassword() {
        val fixture = Fixture()
        val requested = fixture.execute(SettingsPasswordCommand(SettingsPasswordOperation.REQUEST_RECOVERY))
        assertIs<TdApi.RequestPasswordRecovery>(fixture.last.command)
        fixture.last.respond(TdApi.EmailAddressAuthenticationCodeInfo("a***@example.test", 0))
        assertEquals(0, requested.single().value?.codeInfo?.length)
        assertNull(requested.single().value?.state)
        val checked = fixture.execute(SettingsPasswordCommand(SettingsPasswordOperation.VERIFY_RECOVERY_CODE, code = " 0123 "))
        assertEquals(" 0123 ", assertIs<TdApi.CheckPasswordRecoveryCode>(fixture.last.command).recoveryCode)
        fixture.last.respond(TdApi.Ok())
        assertTrue(assertNotNull(checked.single().value).verified)
        assertNull(checked.single().value?.state)
        assertEquals(2, fixture.requests.size)
    }

    @Test
    fun recoveryChangesOrExplicitlyRemovesThePasswordOnlyAtTheFinalNativeOperation() {
        val fixture = Fixture()
        val restored = fixture.execute(SettingsPasswordCommand(SettingsPasswordOperation.RECOVER_PASSWORD,
            newPassword = "replacement", hint = "new hint", code = "recovery-code"))
        val native = assertIs<TdApi.RecoverPassword>(fixture.last.command)
        assertEquals("recovery-code", native.recoveryCode)
        assertEquals("replacement", native.newPassword)
        assertEquals("new hint", native.newHint)
        fixture.last.respond(passwordState())
        assertEquals("replacement", assertIs<TdApi.GetRecoveryEmailAddress>(fixture.last.command).password)
        fixture.last.respond(TdApi.RecoveryEmailAddress("private@example.test"))
        assertNull(restored.single().error)
        assertFalse(restored.single().value?.verified == true)
        val removed = fixture.execute(SettingsPasswordCommand(SettingsPasswordOperation.RECOVER_PASSWORD, code = "recovery-code"))
        assertEquals("", assertIs<TdApi.RecoverPassword>(fixture.last.command).newPassword)
        fixture.last.respond(passwordState(hasPassword = false))
        assertNull(removed.single().error)
    }

    @Test
    fun delayedResetPreservesPendingAndDeclinedDatesAndConfirmsSuccessfulRemovalByReadingState() {
        val fixture = Fixture()
        val pending = fixture.execute(SettingsPasswordCommand(SettingsPasswordOperation.REQUEST_RESET))
        assertIs<TdApi.ResetPassword>(fixture.last.command)
        fixture.last.respond(TdApi.ResetPasswordResultPending(1000))
        assertEquals(SettingsPasswordReset(SettingsPasswordResetKind.PENDING, 1000), pending.single().value?.reset)
        val declined = fixture.execute(SettingsPasswordCommand(SettingsPasswordOperation.REQUEST_RESET))
        fixture.last.respond(TdApi.ResetPasswordResultDeclined(2000))
        assertEquals(SettingsPasswordReset(SettingsPasswordResetKind.DECLINED, 2000), declined.single().value?.reset)
        val ok = fixture.execute(SettingsPasswordCommand(SettingsPasswordOperation.REQUEST_RESET))
        fixture.last.respond(TdApi.ResetPasswordResultOk())
        assertIs<TdApi.GetPasswordState>(fixture.last.command)
        assertTrue(ok.isEmpty())
        fixture.last.respond(passwordState(hasPassword = false))
        assertEquals(SettingsPasswordReset(SettingsPasswordResetKind.OK), ok.single().value?.reset)
        assertNull(ok.single().error)
        val unchanged = fixture.execute(SettingsPasswordCommand(SettingsPasswordOperation.REQUEST_RESET))
        fixture.last.respond(TdApi.ResetPasswordResultOk())
        fixture.last.respond(passwordState())
        assertEquals(SettingsPasswordIssue.STATE_CHANGED, unchanged.single().value?.issue)
        assertNotNull(unchanged.single().error)
    }

    @Test
    fun cancellingResetRequiresTheReadBackResetDateToBeCleared() {
        val fixture = Fixture()
        val stillPending = fixture.execute(SettingsPasswordCommand(SettingsPasswordOperation.CANCEL_RESET))
        assertIs<TdApi.CancelPasswordReset>(fixture.last.command)
        fixture.last.respond(TdApi.Ok())
        fixture.last.respond(passwordState(resetDate = 1234))
        assertEquals(SettingsPasswordIssue.STATE_CHANGED, stillPending.single().value?.issue)
        val cancelled = fixture.execute(SettingsPasswordCommand(SettingsPasswordOperation.CANCEL_RESET))
        fixture.last.respond(TdApi.Ok())
        fixture.last.respond(passwordState())
        assertNull(cancelled.single().error)
        assertEquals(0, cancelled.single().value?.state?.pendingResetDate)
    }

    @Test
    fun loginEmailNeedsTheActualNativePatternAndNeverGuessesSuggestedActionAvailability() {
        val fixture = Fixture()
        for (operation in listOf(SettingsPasswordOperation.SET_LOGIN_EMAIL,
            SettingsPasswordOperation.CONFIRM_LOGIN_EMAIL, SettingsPasswordOperation.RESEND_LOGIN_EMAIL)) {
            val before = fixture.requests.size
            val results = fixture.execute(SettingsPasswordCommand(operation, email = "new@example.test", code = "1234"))
            assertIs<TdApi.GetPasswordState>(fixture.last.command)
            fixture.last.respond(passwordState(login = ""))
            assertEquals(SettingsError.UNSUPPORTED, results.single().error)
            assertEquals(before + 1, fixture.requests.size)
        }
    }

    @Test
    fun supportedLoginEmailUsesIndependentCodeRequestsAndTheAuthenticationCodeObject() {
        val fixture = Fixture()
        val set = fixture.execute(SettingsPasswordCommand(SettingsPasswordOperation.SET_LOGIN_EMAIL, email = "login@example.test"))
        fixture.last.respond(passwordState(login = "o***@example.test"))
        assertEquals("login@example.test", assertIs<TdApi.SetLoginEmailAddress>(fixture.last.command).newLoginEmailAddress)
        fixture.last.respond(TdApi.EmailAddressAuthenticationCodeInfo("l***@example.test", 6))
        assertEquals(6, set.single().value?.codeInfo?.length)
        val resend = fixture.execute(SettingsPasswordCommand(SettingsPasswordOperation.RESEND_LOGIN_EMAIL))
        fixture.last.respond(passwordState(login = "o***@example.test"))
        assertIs<TdApi.ResendLoginEmailAddressCode>(fixture.last.command)
        fixture.last.respond(TdApi.EmailAddressAuthenticationCodeInfo("l***@example.test", 6))
        assertNull(resend.single().error)
        val confirm = fixture.execute(SettingsPasswordCommand(SettingsPasswordOperation.CONFIRM_LOGIN_EMAIL, code = "001234"))
        fixture.last.respond(passwordState(login = "o***@example.test"))
        val native = assertIs<TdApi.CheckLoginEmailAddressCode>(fixture.last.command)
        assertEquals("001234", assertIs<TdApi.EmailAddressAuthenticationCode>(native.code).code)
        fixture.last.respond(TdApi.Ok())
        assertIs<TdApi.GetPasswordState>(fixture.last.command)
        fixture.last.respond(passwordState(login = "l***@example.test"))
        assertNull(confirm.single().error)
        assertTrue(confirm.single().value?.verified == true)
        assertEquals("l***@example.test", confirm.single().value?.state?.loginEmailPattern)
    }

    @Test
    fun classifiedErrorsStayTypedAndNeverExposeNativeMessages() {
        val errors = mapOf(
            "PASSWORD_HASH_INVALID" to SettingsPasswordIssue.PASSWORD_INVALID,
            "SRP_PASSWORD_CHANGED" to SettingsPasswordIssue.PASSWORD_CHANGED,
            "CODE_INVALID" to SettingsPasswordIssue.CODE_INVALID,
            "Invalid recovery code" to SettingsPasswordIssue.CODE_INVALID,
            "EMAIL_HASH_EXPIRED" to SettingsPasswordIssue.CODE_EXPIRED,
            "No login email address code was sent" to SettingsPasswordIssue.CODE_EXPIRED,
            "EMAIL_INVALID" to SettingsPasswordIssue.EMAIL_INVALID,
            "PASSWORD_RECOVERY_NA" to SettingsPasswordIssue.RECOVERY_UNAVAILABLE,
        )
        for ((message, issue) in errors) {
            val fixture = Fixture()
            val results = fixture.execute(SettingsPasswordCommand(SettingsPasswordOperation.REQUEST_RECOVERY))
            fixture.last.respond(TdApi.Error(400, message))
            assertEquals(issue, results.single().value?.issue)
            assertNotNull(results.single().error)
            assertFalse(results.toString().contains(message))
        }
        val fixture = Fixture()
        val unknown = fixture.execute(SettingsPasswordCommand(SettingsPasswordOperation.REQUEST_RECOVERY))
        fixture.last.respond(TdApi.Error(500, "private-account-specific-server-message"))
        assertEquals(SettingsError.REQUEST_FAILED, unknown.single().error)
        assertFalse(unknown.toString().contains("private-account"))
    }

    @Test
    fun everyChainedRequestRechecksTheParentsGenerationAndPageGuard() {
        val fixture = Fixture()
        val results = fixture.execute(confirmation())
        val pending = fixture.last
        fixture.active = false
        pending.respond(passwordState())
        assertEquals(1, fixture.requests.size)
        assertEquals(SettingsError.NOT_READY, results.single().error)
        assertFalse(results.single().value?.verified == true)
    }

    @Test
    fun duplicateCallbacksCannotCreateExtraNativeChainsOrCompleteTwice() {
        val fixture = Fixture()
        val results = fixture.execute(confirmation())
        val pending = fixture.last
        pending.respond(passwordState())
        pending.respond(passwordState())
        pending.fail()
        assertEquals(2, fixture.requests.size)
        val address = fixture.last
        address.respond(TdApi.RecoveryEmailAddress("expected@example.test"))
        address.respond(TdApi.RecoveryEmailAddress("different@example.test"))
        address.fail()
        assertEquals(1, results.size)
        assertNull(results.single().error)
    }

    @Test
    fun invalidOrOversizedInputNeverReachesNativeAndTransportFailureStillCompletes() {
        val fixture = Fixture()
        val invalid = listOf(
            SettingsPasswordCommand(SettingsPasswordOperation.VERIFY_CURRENT),
            SettingsPasswordCommand(SettingsPasswordOperation.SET_PASSWORD),
            SettingsPasswordCommand(SettingsPasswordOperation.SET_PASSWORD, newPassword = "same", hint = "same"),
            SettingsPasswordCommand(SettingsPasswordOperation.REMOVE_PASSWORD),
            confirmation().copy(currentPassword = ""),
            confirmation().copy(email = ""),
            confirmation().copy(code = " "),
            SettingsPasswordCommand(SettingsPasswordOperation.SET_LOGIN_EMAIL),
            SettingsPasswordCommand(SettingsPasswordOperation.RECOVER_PASSWORD, code = "a".repeat(101)),
            SettingsPasswordCommand(SettingsPasswordOperation.SET_PASSWORD, newPassword = "a".repeat(4097)),
            SettingsPasswordCommand(SettingsPasswordOperation.SET_RECOVERY_EMAIL, currentPassword = "p", email = "a".repeat(321)),
        )
        for (command in invalid) assertEquals(SettingsError.INVALID_VALUE, fixture.execute(command).single().error)
        assertTrue(fixture.requests.isEmpty())
        val failed = fixture.execute(SettingsPasswordCommand(SettingsPasswordOperation.REQUEST_RECOVERY))
        fixture.last.fail()
        fixture.last.respond(TdApi.EmailAddressAuthenticationCodeInfo("x***@example.test", 5))
        assertEquals(SettingsError.NOT_READY, failed.single().error)
        val thrown = mutableListOf<SettingsResult<SettingsPasswordOutcome>>()
        OfficialTdSettingsPassword { _, _, _ -> error("private exception details") }.execute(
            SettingsPasswordCommand(SettingsPasswordOperation.REQUEST_RECOVERY), thrown::add)
        assertNotNull(thrown.single().error)
        assertFalse(thrown.toString().contains("private exception"))
    }

    @Test
    fun everyAcceptedNativeCodeLengthCanBeSubmittedWithinTheSameBound() {
        val fixture = Fixture()
        val requested = fixture.execute(SettingsPasswordCommand(SettingsPasswordOperation.REQUEST_RECOVERY))
        fixture.last.respond(TdApi.EmailAddressAuthenticationCodeInfo("r***@example.test", 100))
        assertEquals(100, requested.single().value?.codeInfo?.length)
        val code = "1".repeat(100)
        val verified = fixture.execute(SettingsPasswordCommand(SettingsPasswordOperation.VERIFY_RECOVERY_CODE, code = code))
        assertEquals(code, assertIs<TdApi.CheckPasswordRecoveryCode>(fixture.last.command).recoveryCode)
        fixture.last.respond(TdApi.Ok())
        assertTrue(assertNotNull(verified.single().value).verified)
        val before = fixture.requests.size
        val oversized = fixture.execute(SettingsPasswordCommand(SettingsPasswordOperation.VERIFY_RECOVERY_CODE, code = "1".repeat(101)))
        assertEquals(SettingsError.INVALID_VALUE, oversized.single().error)
        assertEquals(before, fixture.requests.size)
    }

    private class Fixture {
        val requests = mutableListOf<Pending>()
        var active = true
        val api = OfficialTdSettingsPassword { command, result, failed ->
            if (active) requests += Pending(command, result, failed) else failed()
        }
        val last: Pending get() = requests.last()
        fun execute(command: SettingsPasswordCommand): MutableList<SettingsResult<SettingsPasswordOutcome>> {
            val results = mutableListOf<SettingsResult<SettingsPasswordOutcome>>()
            api.execute(command, results::add)
            return results
        }
    }
    private class Pending(val command: TdApi.Function<*>, val respond: (TdApi.Object) -> Unit, val fail: () -> Unit)

    private fun confirmation() = SettingsPasswordCommand(SettingsPasswordOperation.CONFIRM_RECOVERY_EMAIL,
        currentPassword = "effective", email = "expected@example.test", code = "1234")

    private fun passwordState(
        hasPassword: Boolean = true, recoveryEmail: Boolean = true, pending: Boolean = false,
        resetDate: Int = 0, login: String = "",
    ) = TdApi.PasswordState(hasPassword, "native hint", recoveryEmail, true,
        if (pending) TdApi.EmailAddressAuthenticationCodeInfo("r***@example.test", 0) else null, login, resetDate)
}
