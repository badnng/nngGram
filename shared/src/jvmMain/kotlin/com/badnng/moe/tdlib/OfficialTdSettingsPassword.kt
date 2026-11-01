package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Passwords, complete email addresses and codes stay inside the current request chain. */
internal class OfficialTdSettingsPassword(
    private val send: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit,
    private val isCurrent: () -> Boolean,
    private val specialWrites: Boolean,
) {
    constructor(send: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit) : this(send, { true }, false)
    private val ownerLock = Any()
    private var retired = false
    private val continuations = mutableSetOf<AtomicReference<((TdApi.Object?) -> Unit)?>>()
    private var terminalResult: ((SettingsResult<SettingsPasswordOutcome>) -> Unit)? = null
    @Volatile private var physicalReceipt: SettingsSpecialWriteReceipt? = null

    /** Drop app-owned secret-bearing continuations, retaining only the controller's physical settlement. */
    fun retire() = synchronized(ownerLock) {
        retired = true
        continuations.forEach { it.set(null) }
        continuations.clear()
    }
    private fun current() = synchronized(ownerLock) { !retired } && isCurrent()
    /** Live sessions inject their one instance-wide broker; standalone state helpers have no owner. */
    internal var loginEmailDelegate: ((SettingsPasswordCommand, (SettingsResult<SettingsPasswordOutcome>) -> Unit) -> Unit)? = null
    fun state(result: (SettingsResult<SettingsPasswordState>) -> Unit) {
        request(TdApi.GetPasswordState()) { response ->
            if (response is TdApi.PasswordState) result(SettingsResult(mapState(response)))
            else result(SettingsResult(error = error(response)))
        }
    }

    fun execute(command: SettingsPasswordCommand, result: (SettingsResult<SettingsPasswordOutcome>) -> Unit) {
        terminalResult = result
        if (!valid(command)) {
            result(SettingsResult(error = SettingsError.INVALID_VALUE))
            return
        }
        if (command.operation in setOf(SettingsPasswordOperation.SET_LOGIN_EMAIL,
                SettingsPasswordOperation.RESEND_LOGIN_EMAIL, SettingsPasswordOperation.CONFIRM_LOGIN_EMAIL)) {
            loginEmailDelegate?.let { it(command, result); return }
        }
        when (command.operation) {
            SettingsPasswordOperation.VERIFY_CURRENT -> verifyCurrent(command.currentPassword, result)
            SettingsPasswordOperation.SET_PASSWORD -> changePassword(command, result)
            SettingsPasswordOperation.REMOVE_PASSWORD -> passwordMutation(
                TdApi.SetPassword(command.currentPassword, "", "", false, ""), false, "", null, result)
            SettingsPasswordOperation.SET_RECOVERY_EMAIL -> passwordMutation(
                TdApi.SetRecoveryEmailAddress(command.currentPassword, command.email), true,
                command.currentPassword, command.email, result)
            SettingsPasswordOperation.CONFIRM_RECOVERY_EMAIL -> request(TdApi.CheckRecoveryEmailAddressCode(command.code)) { response ->
                if (response !is TdApi.PasswordState) result(failure(response))
                else {
                    val current = mapState(response)
                    when {
                        current.recoveryEmailCodeInfo != null -> result(rejected(current,
                            SettingsPasswordIssue.CONFIRMATION_NOT_ACCEPTED))
                        command.currentPassword.isEmpty() -> result(SettingsResult(SettingsPasswordOutcome(state = current)))
                        !current.hasPassword -> result(rejected(current, SettingsPasswordIssue.STATE_CHANGED))
                        else -> verifyAcceptedState(current, command.currentPassword, command.email, result, verified = true)
                    }
                }
            }
            SettingsPasswordOperation.RESEND_RECOVERY_EMAIL -> request(TdApi.ResendRecoveryEmailAddressCode()) { response ->
                if (response !is TdApi.PasswordState) result(failure(response))
                else {
                    val current = mapState(response)
                    if (current.recoveryEmailCodeInfo == null) result(rejected(current, SettingsPasswordIssue.CODE_EXPIRED))
                    else result(SettingsResult(SettingsPasswordOutcome(current, current.recoveryEmailCodeInfo)))
                }
            }
            SettingsPasswordOperation.CANCEL_RECOVERY_EMAIL -> request(TdApi.CancelRecoveryEmailAddressVerification()) { response ->
                if (response !is TdApi.PasswordState) result(failure(response))
                else {
                    val current = mapState(response)
                    if (current.recoveryEmailCodeInfo != null) result(rejected(current,
                        SettingsPasswordIssue.CONFIRMATION_NOT_ACCEPTED))
                    else result(SettingsResult(SettingsPasswordOutcome(state = current)))
                }
            }
            SettingsPasswordOperation.REQUEST_RECOVERY -> requestCode(TdApi.RequestPasswordRecovery(), result)
            SettingsPasswordOperation.VERIFY_RECOVERY_CODE -> request(TdApi.CheckPasswordRecoveryCode(command.code)) { response ->
                if (response is TdApi.Ok) result(SettingsResult(SettingsPasswordOutcome(verified = true)))
                else result(failure(response))
            }
            SettingsPasswordOperation.RECOVER_PASSWORD -> passwordMutation(
                TdApi.RecoverPassword(command.code, command.newPassword, command.hint),
                command.newPassword.isNotEmpty(), command.newPassword, null, result)
            SettingsPasswordOperation.REQUEST_RESET -> reset(result)
            SettingsPasswordOperation.CANCEL_RESET -> request(TdApi.CancelPasswordReset()) { response ->
                if (response !is TdApi.Ok) result(failure(response))
                else readOutcomeState(result) { current ->
                    if (current.pendingResetDate > 0) rejected(current, SettingsPasswordIssue.STATE_CHANGED)
                    else SettingsResult(SettingsPasswordOutcome(state = current))
                }
            }
            SettingsPasswordOperation.SET_LOGIN_EMAIL -> withLoginEmail(result) {
                requestCode(TdApi.SetLoginEmailAddress(command.email), result)
            }
            SettingsPasswordOperation.RESEND_LOGIN_EMAIL -> withLoginEmail(result) {
                requestCode(TdApi.ResendLoginEmailAddressCode(), result)
            }
            SettingsPasswordOperation.CONFIRM_LOGIN_EMAIL -> withLoginEmail(result) {
                request(TdApi.CheckLoginEmailAddressCode(TdApi.EmailAddressAuthenticationCode(command.code))) { response ->
                    if (response !is TdApi.Ok) result(failure(response))
                    else readOutcomeState(result) { current ->
                        if (!current.loginEmailSupported) rejected(current, SettingsPasswordIssue.STATE_CHANGED)
                        else SettingsResult(SettingsPasswordOutcome(state = current, verified = true))
                    }
                }
            }
        }
    }

    private fun verifyCurrent(password: String, result: (SettingsResult<SettingsPasswordOutcome>) -> Unit) {
        request(TdApi.GetRecoveryEmailAddress(password)) { response ->
            if (response !is TdApi.RecoveryEmailAddress) result(failure(response))
            // TDLib can return an empty address after a remote password removal. Check the public state too.
            else state { responseState ->
                val current = responseState.value
                if (current == null) result(SettingsResult(error = responseState.error ?: SettingsError.REQUEST_FAILED))
                else if (!current.hasPassword) result(rejected(current, SettingsPasswordIssue.PASSWORD_CHANGED))
                else if (current.pendingResetDate == 0) result(SettingsResult(SettingsPasswordOutcome(state = current, verified = true)))
                else request(TdApi.CancelPasswordReset()) { cancelled ->
                    if (cancelled !is TdApi.Ok) result(failure(cancelled))
                    else readOutcomeState(result) { refreshed ->
                        if (!refreshed.hasPassword || refreshed.pendingResetDate > 0)
                            rejected(refreshed, SettingsPasswordIssue.STATE_CHANGED)
                        else SettingsResult(SettingsPasswordOutcome(state = refreshed, verified = true))
                    }
                }
            }
        }
    }

    private fun changePassword(command: SettingsPasswordCommand, result: (SettingsResult<SettingsPasswordOutcome>) -> Unit) {
        passwordMutation(TdApi.SetPassword(command.currentPassword, command.newPassword, command.hint,
            command.setRecoveryEmail, if (command.setRecoveryEmail) command.email else ""), true,
            command.newPassword, command.email.takeIf { command.setRecoveryEmail }, result)
    }

    private fun passwordMutation(
        function: TdApi.Function<*>, hasPassword: Boolean, effectivePassword: String, expectedEmail: String?,
        result: (SettingsResult<SettingsPasswordOutcome>) -> Unit,
    ) {
        request(function) { response ->
            if (response !is TdApi.PasswordState) result(failure(response))
            else {
                val current = mapState(response)
                when {
                    current.recoveryEmailCodeInfo != null -> {
                        if (hasPassword) result(SettingsResult(SettingsPasswordOutcome(current, current.recoveryEmailCodeInfo)))
                        else result(rejected(current, SettingsPasswordIssue.CONFIRMATION_NOT_ACCEPTED))
                    }
                    current.hasPassword != hasPassword -> result(rejected(current, SettingsPasswordIssue.STATE_CHANGED))
                    hasPassword -> verifyAcceptedState(current, effectivePassword, expectedEmail, result)
                    else -> result(SettingsResult(SettingsPasswordOutcome(state = current)))
                }
            }
        }
    }

    private fun verifyAcceptedState(
        current: SettingsPasswordState, password: String, expectedEmail: String?,
        result: (SettingsResult<SettingsPasswordOutcome>) -> Unit,
        verified: Boolean = false,
    ) {
        if (expectedEmail != null && current.hasRecoveryEmail != expectedEmail.isNotEmpty()) {
            result(rejected(current, SettingsPasswordIssue.CONFIRMATION_NOT_ACCEPTED))
            return
        }
        request(TdApi.GetRecoveryEmailAddress(password)) { response ->
            if (response !is TdApi.RecoveryEmailAddress) {
                val failed = failure(response)
                result(failed.copy(value = (failed.value ?: SettingsPasswordOutcome()).copy(state = current)))
            } else if (expectedEmail != null && !response.recoveryEmailAddress.orEmpty().equals(expectedEmail, ignoreCase = true)) {
                result(rejected(current, SettingsPasswordIssue.CONFIRMATION_NOT_ACCEPTED))
            } else result(SettingsResult(SettingsPasswordOutcome(state = current, verified = verified)))
        }
    }

    private fun reset(result: (SettingsResult<SettingsPasswordOutcome>) -> Unit) {
        request(TdApi.ResetPassword()) { response ->
            when (response) {
                is TdApi.ResetPasswordResultPending -> if (response.pendingResetDate > 0) {
                    result(SettingsResult(SettingsPasswordOutcome(reset = SettingsPasswordReset(
                        SettingsPasswordResetKind.PENDING, response.pendingResetDate))))
                } else result(failure())
                is TdApi.ResetPasswordResultDeclined -> if (response.retryDate > 0) {
                    result(SettingsResult(SettingsPasswordOutcome(reset = SettingsPasswordReset(
                        SettingsPasswordResetKind.DECLINED, response.retryDate))))
                } else result(failure())
                is TdApi.ResetPasswordResultOk -> readOutcomeState(result) { current ->
                    if (current.hasPassword || current.pendingResetDate > 0 || current.recoveryEmailCodeInfo != null) {
                        rejected(current, SettingsPasswordIssue.STATE_CHANGED)
                    } else SettingsResult(SettingsPasswordOutcome(state = current,
                        reset = SettingsPasswordReset(SettingsPasswordResetKind.OK)))
                }
                else -> result(failure(response))
            }
        }
    }

    private fun withLoginEmail(result: (SettingsResult<SettingsPasswordOutcome>) -> Unit, action: () -> Unit) {
        state { response ->
            val current = response.value
            if (current == null) result(SettingsResult(error = response.error ?: SettingsError.REQUEST_FAILED))
            // Suggested-action availability is not implemented; never infer it from a locally entered email.
            else if (!current.loginEmailSupported) result(SettingsResult(
                value = SettingsPasswordOutcome(state = current), error = SettingsError.UNSUPPORTED))
            else action()
        }
    }

    private fun requestCode(function: TdApi.Function<*>, result: (SettingsResult<SettingsPasswordOutcome>) -> Unit) {
        request(function) { response ->
            if (response is TdApi.EmailAddressAuthenticationCodeInfo && response.length in 0..MAX_NATIVE_CODE_LENGTH) {
                result(SettingsResult(SettingsPasswordOutcome(codeInfo = mapCode(response))))
            } else result(failure(response))
        }
    }

    private fun readOutcomeState(
        result: (SettingsResult<SettingsPasswordOutcome>) -> Unit,
        map: (SettingsPasswordState) -> SettingsResult<SettingsPasswordOutcome>,
    ) {
        state { response ->
            val current = response.value
            if (current == null) result(SettingsResult(error = response.error ?: SettingsError.REQUEST_FAILED))
            else result(map(current))
        }
    }

    private fun valid(command: SettingsPasswordCommand): Boolean {
        if (command.currentPassword.length > MAX_PASSWORD_LENGTH || command.newPassword.length > MAX_PASSWORD_LENGTH ||
            command.hint.length > MAX_PASSWORD_LENGTH || command.email.length > MAX_EMAIL_LENGTH ||
            command.code.length > MAX_CODE_LENGTH || command.email.isNotEmpty() && command.email.isBlank()) return false
        return when (command.operation) {
            SettingsPasswordOperation.VERIFY_CURRENT, SettingsPasswordOperation.REMOVE_PASSWORD,
            SettingsPasswordOperation.SET_RECOVERY_EMAIL -> command.currentPassword.isNotEmpty()
            SettingsPasswordOperation.SET_PASSWORD -> command.newPassword.isNotEmpty() &&
                (command.hint.isEmpty() || command.hint != command.newPassword)
            SettingsPasswordOperation.CONFIRM_RECOVERY_EMAIL -> command.code.isNotBlank() &&
                (command.currentPassword.isEmpty() && command.email.isEmpty() ||
                    command.currentPassword.isNotEmpty() && command.email.isNotEmpty())
            SettingsPasswordOperation.VERIFY_RECOVERY_CODE, SettingsPasswordOperation.CONFIRM_LOGIN_EMAIL -> command.code.isNotBlank()
            SettingsPasswordOperation.RECOVER_PASSWORD -> command.code.isNotBlank() &&
                (command.hint.isEmpty() || command.hint != command.newPassword)
            SettingsPasswordOperation.SET_LOGIN_EMAIL -> command.email.isNotBlank()
            else -> true
        }
    }

    private fun failure(response: TdApi.Object? = null): SettingsResult<SettingsPasswordOutcome> {
        val issue = (response as? TdApi.Error)?.message?.let { message -> when (message) {
            "PASSWORD_HASH_INVALID" -> SettingsPasswordIssue.PASSWORD_INVALID
            "SRP_PASSWORD_CHANGED", "PASSWORD_MISSING" -> SettingsPasswordIssue.PASSWORD_CHANGED
            "CODE_INVALID", "EMAIL_CODE_INVALID", "Invalid recovery code", "Verification code must be non-empty" ->
                SettingsPasswordIssue.CODE_INVALID
            "CODE_EXPIRED", "EMAIL_CODE_EXPIRED", "EMAIL_HASH_EXPIRED", "PASSWORD_RECOVERY_EXPIRED",
            "No login email address code was sent" -> SettingsPasswordIssue.CODE_EXPIRED
            "EMAIL_INVALID", "EMAIL_NOT_ALLOWED" -> SettingsPasswordIssue.EMAIL_INVALID
            "PASSWORD_RECOVERY_NA", "PASSWORD_RECOVERY_NOT_AVAILABLE", "EMAIL_NOT_SETUP" ->
                SettingsPasswordIssue.RECOVERY_UNAVAILABLE
            else -> null
        } }
        return SettingsResult(value = issue?.let { SettingsPasswordOutcome(issue = it) }, error = error(response))
    }

    private fun rejected(current: SettingsPasswordState, issue: SettingsPasswordIssue) = SettingsResult(
        value = SettingsPasswordOutcome(state = current, codeInfo = current.recoveryEmailCodeInfo, issue = issue),
        error = SettingsError.INVALID_VALUE)

    private fun error(response: TdApi.Object?): SettingsError = response?.let(::settingsError) ?: SettingsError.NOT_READY

    /** Parent binds both generation and page ownership to send, including every chained request. */
    private fun request(function: TdApi.Function<*>, result: (TdApi.Object?) -> Unit) {
        val mutation = specialWrites && (function is TdApi.SetPassword || function is TdApi.SetRecoveryEmailAddress ||
            function is TdApi.CheckRecoveryEmailAddressCode || function is TdApi.ResendRecoveryEmailAddressCode ||
            function is TdApi.CancelRecoveryEmailAddressVerification || function is TdApi.RequestPasswordRecovery ||
            function is TdApi.RecoverPassword || function is TdApi.ResetPassword || function is TdApi.CancelPasswordReset ||
            function is TdApi.SetLoginEmailAddress || function is TdApi.ResendLoginEmailAddressCode ||
            function is TdApi.CheckLoginEmailAddressCode)
        val completed = AtomicBoolean()
        // Keep only a response discriminator in callbacks, never the secret-bearing native command.
        val responseKind = when (function) {
            is TdApi.SetPassword, is TdApi.SetRecoveryEmailAddress, is TdApi.CheckRecoveryEmailAddressCode,
            is TdApi.ResendRecoveryEmailAddressCode, is TdApi.CancelRecoveryEmailAddressVerification,
            is TdApi.RecoverPassword -> 1
            is TdApi.RequestPasswordRecovery, is TdApi.SetLoginEmailAddress, is TdApi.ResendLoginEmailAddressCode -> 2
            is TdApi.ResetPassword -> 3
            else -> 0
        }
        val continuation = AtomicReference<((TdApi.Object?) -> Unit)?>(result)
        synchronized(ownerLock) { if (retired) continuation.set(null) else continuations.add(continuation) }
        fun finish(response: TdApi.Object?) {
            val metadata = SettingsSpecialMutationContext.receipt()
            val accepted = when (responseKind) {
                1 -> response is TdApi.PasswordState
                2 -> response is TdApi.EmailAddressAuthenticationCodeInfo
                3 -> response is TdApi.ResetPasswordResultOk || response is TdApi.ResetPasswordResultPending || response is TdApi.ResetPasswordResultDeclined
                else -> response is TdApi.Ok
            }
            val actual = if (!mutation) null else when {
                response is TdApi.Error -> SettingsSpecialWriteReceipt(SettingsSpecialWriteOutcome.REJECTED, settingsError(response))
                accepted -> SettingsSpecialWriteReceipt(SettingsSpecialWriteOutcome.ACCEPTED)
                else -> metadata?.takeIf { it.outcome == SettingsSpecialWriteOutcome.NOT_DISPATCHED }
                    ?: SettingsSpecialWriteReceipt(SettingsSpecialWriteOutcome.INDETERMINATE)
            }
            val unknown = actual?.outcome == SettingsSpecialWriteOutcome.INDETERMINATE
            if (unknown && completed.get() || !unknown && !completed.compareAndSet(false, true)) return
            if (mutation) physicalReceipt = actual
            if (!current() || unknown) {
                retire()
                val receipt = when {
                    response is TdApi.Error -> SettingsSpecialWriteReceipt(SettingsSpecialWriteOutcome.REJECTED, settingsError(response))
                    else -> physicalReceipt ?: metadata ?: SettingsSpecialWriteReceipt(SettingsSpecialWriteOutcome.NOT_DISPATCHED)
                }
                SettingsSpecialMutationContext.withReceipt(receipt) {
                    // No state/hint/address or verified capability is delivered to a retired form.
                    terminalResult?.invoke(SettingsResult(error = receipt.error ?:
                        if (receipt.outcome == SettingsSpecialWriteOutcome.INDETERMINATE) SettingsError.RESULT_UNKNOWN else SettingsError.NOT_READY))
                }
            } else {
                synchronized(ownerLock) { continuations.remove(continuation) }
                SettingsSpecialMutationContext.withReceipt(physicalReceipt ?: metadata) {
                    continuation.getAndSet(null)?.invoke(response)
                }
            }
        }
        if (!current()) { finish(null); return }
        try {
            if (mutation) SettingsSpecialMutationContext.write(function, send, { finish(it) }, { finish(null) })
            else send(function, { finish(it) }, { finish(null) })
        }
        catch (_: Exception) { finish(null) }
    }

    private companion object {
        const val MAX_PASSWORD_LENGTH = 4096
        const val MAX_EMAIL_LENGTH = 320
        const val MAX_CODE_LENGTH = 100
        const val MAX_NATIVE_CODE_LENGTH = 100
        fun mapCode(value: TdApi.EmailAddressAuthenticationCodeInfo) =
            SettingsEmailCodeInfo(value.emailAddressPattern.orEmpty(), value.length)
        fun mapState(value: TdApi.PasswordState) = SettingsPasswordState(value.hasPassword,
            value.passwordHint.orEmpty(), value.hasRecoveryEmailAddress, value.hasPassportData,
            value.recoveryEmailAddressCodeInfo?.let(::mapCode), value.loginEmailAddressPattern.orEmpty(),
            value.pendingResetDate)
    }
}
