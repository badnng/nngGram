package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import java.util.concurrent.atomic.AtomicBoolean

interface TdLoginEmailSession {
    fun authorizationReady(): Boolean = true
    fun suggestions(): LoginEmailSuggestions
    fun observeSuggestions(observer: ((LoginEmailSuggestions) -> Unit)?)
    fun probe(token: LoginEmailSetupToken, isCurrent: () -> Boolean, result: (LoginEmailAvailability) -> Unit)
    fun sendEmail(token: LoginEmailSetupToken, email: String, isCurrent: () -> Boolean, result: (LoginEmailOutcome) -> Unit)
    fun resend(challenge: LoginEmailChallengeToken, isCurrent: () -> Boolean, result: (LoginEmailOutcome) -> Unit)
    fun check(challenge: LoginEmailChallengeToken, code: String, isCurrent: () -> Boolean, result: (LoginEmailOutcome) -> Unit)
    fun proof(token: LoginEmailSetupToken, isCurrent: () -> Boolean, result: (LoginEmailProof) -> Unit)
    fun hideOptional(epoch: Long, isCurrent: () -> Boolean, result: (LoginEmailOutcome) -> Unit)
    fun retire(token: LoginEmailSetupToken)
    fun satisfyRequired(epoch: Long)
    fun retireAll()
}

/** One broker per TDLib instance: its hidden last-email field is shared by setup and configured changes. */
internal class OfficialTdLoginEmail(
    private val send: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit,
    private val suggestions: TdLoginEmailSuggestions = TdLoginEmailSuggestions(),
    private val gate: TdUserInteractionGate = TdUserInteractionGate(),
    private val isAuthorizationReady: () -> Boolean = { true },
) : TdLoginEmailSession {
    private data class Owner(val setup: LoginEmailSetupToken? = null, val security: SettingsSecurityToken? = null)
    private data class Challenge(val owner: Owner, val revision: Long)
    private val lock = Any()
    private var active = true
    private var flight = 0L
    private var nextFlight = 0L
    private var nextChallenge = 0L
    private var challenge: Challenge? = null
    private var retiredSetup: Pair<Long, Long>? = null
    private var retiredSecurity: Pair<Long, Long>? = null

    override fun authorizationReady() = isAuthorizationReady()
    override fun suggestions() = suggestions.snapshot()
    override fun observeSuggestions(observer: ((LoginEmailSuggestions) -> Unit)?) = suggestions.observe(observer)
    override fun satisfyRequired(epoch: Long) = gate.satisfyRequired(epoch)
    override fun retire(token: LoginEmailSetupToken): Unit {
        retireOwner(Owner(setup = token))
    }
    fun retireConfigured(token: SettingsSecurityToken) = retireOwner(Owner(security = token))
    private fun retireOwner(owner: Owner) = synchronized(lock) {
        retireConfiguredContinuations(owner)
        if (challenge?.owner == owner) challenge = null
        owner.setup?.let { retiredSetup = latest(retiredSetup, it.generation to it.owner) }
        owner.security?.let { retiredSecurity = latest(retiredSecurity, it.generation to it.owner) }
        // Never release an in-flight request here. Only its original terminal callback may do so.
    }
    override fun retireAll() {
        retireConfiguredContinuations()
        synchronized(lock) { active = false; challenge = null }
        suggestions.observe(null)
    }

    private fun current(token: LoginEmailSetupToken, isCurrent: () -> Boolean): Boolean =
        token.generation > 0 && token.owner > 0 && synchronized(lock) { active && !retired(Owner(setup = token)) } &&
            isCurrent() && suggestions.snapshot().contains(token.kind, token.suggestionEpoch) &&
            (token.kind == LoginEmailEntryKind.MANDATORY || gate.allowsOrdinary()) && isAuthorizationReady()

    override fun probe(token: LoginEmailSetupToken, isCurrent: () -> Boolean, result: (LoginEmailAvailability) -> Unit) {
        val allowed = { current(token, isCurrent) }
        request(TdApi.IsLoginEmailAddressRequired(), allowed) { response ->
            result(if (!allowed()) LoginEmailAvailability.UNKNOWN else availability(response))
        }
    }

    override fun sendEmail(token: LoginEmailSetupToken, email: String, isCurrent: () -> Boolean, result: (LoginEmailOutcome) -> Unit) {
        val value = email.trim()
        if (!validEmail(value)) { result(LoginEmailOutcome(issue = LoginEmailIssue.EMAIL_INVALID)); return }
        val owner = Owner(setup = token)
        val allowed = { current(token, isCurrent) }
        operation(owner, allowed, result) { done ->
            synchronized(lock) { challenge = null }
            request(TdApi.IsLoginEmailAddressRequired(), allowed) { response ->
                if (response !is TdApi.Ok) done(LoginEmailOutcome(issue = if (response is TdApi.Error && response.code == 404)
                    LoginEmailIssue.NOT_AVAILABLE else issue(response)))
                else request(TdApi.SetLoginEmailAddress(value), allowed) { sent ->
                    done(codeOutcome(owner, sent, allowed, token))
                }
            }
        }
    }

    override fun resend(challenge: LoginEmailChallengeToken, isCurrent: () -> Boolean, result: (LoginEmailOutcome) -> Unit) {
        val owner = Owner(setup = challenge.owner)
        val allowed = { current(challenge.owner, isCurrent) }
        if (!owns(owner, challenge.revision)) { result(LoginEmailOutcome(issue = LoginEmailIssue.CODE_EXPIRED)); return }
        operation(owner, allowed, result) { done ->
            request(TdApi.ResendLoginEmailAddressCode(), { allowed() && owns(owner, challenge.revision) }) { response ->
                done(codeOutcome(owner, response, allowed, challenge.owner))
            }
        }
    }

    override fun check(challenge: LoginEmailChallengeToken, code: String, isCurrent: () -> Boolean, result: (LoginEmailOutcome) -> Unit) {
        if (code.isBlank() || code.length > 100) { result(LoginEmailOutcome(issue = LoginEmailIssue.CODE_INVALID)); return }
        val owner = Owner(setup = challenge.owner)
        val allowed = { current(challenge.owner, isCurrent) }
        if (!owns(owner, challenge.revision)) { result(LoginEmailOutcome(issue = LoginEmailIssue.CODE_EXPIRED)); return }
        operation(owner, allowed, result) { done ->
            request(TdApi.CheckLoginEmailAddressCode(TdApi.EmailAddressAuthenticationCode(code)), { allowed() && owns(owner, challenge.revision) }) { response ->
                if (response is TdApi.Ok && allowed()) {
                    synchronized(lock) { if (this.challenge?.owner == owner) this.challenge = null }
                    done(LoginEmailOutcome(verificationAccepted = true))
                } else {
                    val failure = issue(response)
                    done(LoginEmailOutcome(issue = failure))
                }
            }
        }
    }

    override fun proof(token: LoginEmailSetupToken, isCurrent: () -> Boolean, result: (LoginEmailProof) -> Unit) {
        val allowed = { current(token, isCurrent) }
        request(TdApi.IsLoginEmailAddressRequired(), allowed) { response ->
            val availability = availability(response)
            if (!allowed()) result(LoginEmailProof(LoginEmailAvailability.UNKNOWN, issue = LoginEmailIssue.STATE_CHANGED))
            else if (availability == LoginEmailAvailability.FAILED) result(LoginEmailProof(availability, issue = issue(response)))
            else request(TdApi.GetPasswordState(), allowed) { state ->
                if (state is TdApi.PasswordState && allowed()) result(LoginEmailProof(availability,
                    state.loginEmailAddressPattern.orEmpty().isNotBlank()))
                else result(LoginEmailProof(availability, issue = if (!allowed()) LoginEmailIssue.STATE_CHANGED else issue(state)))
            }
        }
    }

    override fun hideOptional(epoch: Long, isCurrent: () -> Boolean, result: (LoginEmailOutcome) -> Unit) {
        val allowed = { synchronized(lock) { active } && isCurrent() && gate.allowsOrdinary() &&
            suggestions.snapshot().contains(LoginEmailEntryKind.OPTIONAL, epoch) && isAuthorizationReady() }
        request(TdApi.HideSuggestedAction(TdApi.SuggestedActionSetLoginEmailAddress(true)), allowed) { response ->
            result(if (response is TdApi.Ok && allowed()) LoginEmailOutcome(completed = true)
            else LoginEmailOutcome(issue = if (!allowed()) LoginEmailIssue.STATE_CHANGED else issue(response)))
        }
    }

    /** Existing MANAGE remains configured-only; it shares the exact same hidden native challenge. */
    fun configured(token: SettingsSecurityToken, command: SettingsPasswordCommand, isCurrent: () -> Boolean,
        result: (SettingsResult<SettingsPasswordOutcome>) -> Unit,
    ) {
        if (command.operation !in LOGIN_OPERATIONS || command.email.length > 320 || command.code.length > 100 ||
            command.operation == SettingsPasswordOperation.SET_LOGIN_EMAIL && !validEmail(command.email.trim()) ||
            command.operation == SettingsPasswordOperation.CONFIRM_LOGIN_EMAIL && command.code.isBlank()) {
            result(SettingsResult(error = SettingsError.INVALID_VALUE)); return
        }
        val owner = Owner(security = token)
        val allowed = { synchronized(lock) { active && !retired(owner) } && gate.allowsOrdinary() && isCurrent() && isAuthorizationReady() }
        if (command.operation != SettingsPasswordOperation.SET_LOGIN_EMAIL && !owns(owner)) {
            result(SettingsResult(SettingsPasswordOutcome(issue = SettingsPasswordIssue.CODE_EXPIRED), SettingsError.NOT_READY)); return
        }
        if (!allowed()) { result(SettingsResult(error = SettingsError.NOT_READY)); return }
        val id = synchronized(lock) { if (!active || flight != 0L) 0L else (++nextFlight).also { flight = it } }
        if (id == 0L) { result(SettingsResult(error = SettingsError.NOT_READY)); return }
        val record = ConfiguredFlight(owner, id, allowed, result)
        synchronized(lock) { configuredFlight = record }
        configuredRequest(record, TdApi.GetPasswordState()) { response ->
            val current = (response as? TdApi.PasswordState)?.let(::configuredState)
            if (current == null) finishConfigured(record, SettingsResult(error = response?.let { com.badnng.moe.tdlib.settingsError(it) } ?: SettingsError.NOT_READY))
            else if (!current.loginEmailSupported) finishConfigured(record,
                SettingsResult(SettingsPasswordOutcome(state = current), SettingsError.UNSUPPORTED))
            else when (command.operation) {
                SettingsPasswordOperation.SET_LOGIN_EMAIL -> {
                    synchronized(lock) { challenge = null }
                    configuredRequest(record, TdApi.SetLoginEmailAddress(command.email.trim()), mutation = true) { sent ->
                        val outcome = codeOutcome(owner, sent, allowed)
                        finishConfigured(record, SettingsResult(SettingsPasswordOutcome(codeInfo = outcome.codeInfo,
                            issue = passwordIssue(outcome.issue)), outcome.issue?.let(::settingsError)))
                    }
                }
                SettingsPasswordOperation.RESEND_LOGIN_EMAIL -> configuredRequest(record,
                    TdApi.ResendLoginEmailAddressCode(), mutation = true) { sent ->
                    val outcome = codeOutcome(owner, sent, allowed)
                    finishConfigured(record, SettingsResult(SettingsPasswordOutcome(codeInfo = outcome.codeInfo,
                        issue = passwordIssue(outcome.issue)), outcome.issue?.let(::settingsError)))
                }
                SettingsPasswordOperation.CONFIRM_LOGIN_EMAIL -> configuredRequest(record,
                    TdApi.CheckLoginEmailAddressCode(TdApi.EmailAddressAuthenticationCode(command.code)), mutation = true) { checked ->
                    if (checked !is TdApi.Ok) {
                        val failure = issue(checked)
                        finishConfigured(record, SettingsResult(SettingsPasswordOutcome(issue = passwordIssue(failure)), settingsError(failure)))
                    }
                    else {
                        synchronized(lock) { if (challenge?.owner == owner) challenge = null }
                        configuredRequest(record, TdApi.GetPasswordState()) { fresh ->
                            val updated = (fresh as? TdApi.PasswordState)?.let(::configuredState)
                            finishConfigured(record, if (updated?.loginEmailSupported == true)
                                SettingsResult(SettingsPasswordOutcome(state = updated, verified = true))
                            else SettingsResult(SettingsPasswordOutcome(issue = SettingsPasswordIssue.STATE_CHANGED), SettingsError.NOT_READY))
                        }
                    }
                }
                else -> finishConfigured(record, SettingsResult(error = SettingsError.UNSUPPORTED))
            }
        }
    }

    private class ConfiguredFlight(val owner: Owner, val id: Long, val allowed: () -> Boolean,
        val result: (SettingsResult<SettingsPasswordOutcome>) -> Unit,
    ) {
        val continuations = mutableSetOf<java.util.concurrent.atomic.AtomicReference<((TdApi.Object?) -> Unit)?>>()
        var receipt: SettingsSpecialWriteReceipt? = null
        var completed = false
    }
    private var configuredFlight: ConfiguredFlight? = null

    private fun retireConfiguredContinuations(owner: Owner? = null) = synchronized(lock) {
        configuredFlight?.takeIf { owner == null || it.owner == owner }?.let { record ->
            record.continuations.forEach { it.set(null) }
            record.continuations.clear()
        }
    }

    private fun finishConfigured(record: ConfiguredFlight, response: SettingsResult<SettingsPasswordOutcome>) {
        val receipt = synchronized(lock) { record.receipt } ?:
            SettingsSpecialMutationContext.receipt() ?: SettingsSpecialWriteReceipt(SettingsSpecialWriteOutcome.NOT_DISPATCHED)
        val unknown = receipt.outcome == SettingsSpecialWriteOutcome.INDETERMINATE
        val deliver = synchronized(lock) {
            if (record.completed || flight != record.id) false else {
                if (!unknown) {
                    record.completed = true
                    flight = 0
                    if (configuredFlight === record) configuredFlight = null
                }
                record.continuations.forEach { it.set(null) }
                record.continuations.clear()
                true
            }
        }
        if (deliver) SettingsSpecialMutationContext.withReceipt(receipt) {
            // A receipt survives page retirement; an email challenge or verified capability does not.
            record.result(if (record.allowed() && !unknown) response else SettingsResult(error = receipt.error ?:
                if (unknown) SettingsError.RESULT_UNKNOWN else SettingsError.NOT_READY))
        }
    }

    private fun configuredRequest(record: ConfiguredFlight, function: TdApi.Function<*>, mutation: Boolean = false,
        result: (TdApi.Object?) -> Unit,
    ) {
        // The shared broker may receive guardLocal for mandatory setup; configured changes also
        // need the ordinary email-policy admission. Exact-write marking must survive both gates.
        val continuation = java.util.concurrent.atomic.AtomicReference<((TdApi.Object?) -> Unit)?>(result)
        synchronized(lock) { record.continuations.add(continuation) }
        val completed = AtomicBoolean()
        val confirmation = function is TdApi.CheckLoginEmailAddressCode
        fun finish(response: TdApi.Object?) {
            val incoming = SettingsSpecialMutationContext.receipt()
            val receipt = if (!mutation) null else when (response) {
                is TdApi.Error -> SettingsSpecialWriteReceipt(SettingsSpecialWriteOutcome.REJECTED, com.badnng.moe.tdlib.settingsError(response))
                null -> incoming ?: SettingsSpecialWriteReceipt(SettingsSpecialWriteOutcome.INDETERMINATE)
                else -> if (confirmation && response is TdApi.Ok || !confirmation && response is TdApi.EmailAddressAuthenticationCodeInfo)
                    SettingsSpecialWriteReceipt(SettingsSpecialWriteOutcome.ACCEPTED)
                else SettingsSpecialWriteReceipt(SettingsSpecialWriteOutcome.INDETERMINATE)
            }
            val unknown = receipt?.outcome == SettingsSpecialWriteOutcome.INDETERMINATE
            if (unknown && completed.get() || !unknown && !completed.compareAndSet(false, true)) return
            synchronized(lock) { if (receipt != null) record.receipt = receipt }
            if (!record.allowed() || unknown || continuation.get() == null) {
                finishConfigured(record, SettingsResult(error = receipt?.error ?: SettingsError.NOT_READY))
            } else {
                synchronized(lock) { record.continuations.remove(continuation) }
                SettingsSpecialMutationContext.withReceipt(synchronized(lock) { record.receipt } ?: incoming) {
                    continuation.getAndSet(null)?.invoke(response)
                }
            }
        }
        if (!record.allowed()) {
            SettingsSpecialMutationContext.withReceipt(SettingsSpecialWriteReceipt(SettingsSpecialWriteOutcome.NOT_DISPATCHED)) { finish(null) }
            return
        }
        val configuredSend = gate.guard { request, receive, failed ->
            // Recheck READY/owner under the gate's admission lock, just before the shared send.
            if (record.allowed()) send(request, receive, failed)
            else SettingsSpecialMutationContext.withReceipt(SettingsSpecialWriteReceipt(SettingsSpecialWriteOutcome.NOT_DISPATCHED)) {
                finish(null)
            }
        }
        try {
            if (mutation) SettingsSpecialMutationContext.write(function, configuredSend, { finish(it) }, { finish(null) })
            else configuredSend(function, { finish(it) }, { finish(null) })
        } catch (_: Exception) { finish(null) }
    }

    private fun configuredState(value: TdApi.PasswordState) = SettingsPasswordState(value.hasPassword,
        value.passwordHint.orEmpty(), value.hasRecoveryEmailAddress, value.hasPassportData,
        value.recoveryEmailAddressCodeInfo?.let { SettingsEmailCodeInfo(it.emailAddressPattern.orEmpty(), it.length) },
        value.loginEmailAddressPattern.orEmpty(), value.pendingResetDate)
    private fun owns(owner: Owner, revision: Long? = null) = synchronized(lock) {
        challenge?.let { it.owner == owner && (revision == null || it.revision == revision) } == true
    }

    /** Owners are monotonic within one engine generation; two watermarks avoid an unbounded retired set. */
    private fun retired(owner: Owner): Boolean {
        val value = owner.setup?.let { it.generation to it.owner } ?: owner.security?.let { it.generation to it.owner } ?: return true
        val floor = if (owner.setup != null) retiredSetup else retiredSecurity
        return floor != null && (value.first < floor.first || value.first == floor.first && value.second <= floor.second)
    }
    private fun latest(before: Pair<Long, Long>?, after: Pair<Long, Long>): Pair<Long, Long> =
        if (before == null || after.first > before.first || after.first == before.first && after.second > before.second) after else before

    private fun codeOutcome(owner: Owner, response: TdApi.Object?, allowed: () -> Boolean, token: LoginEmailSetupToken? = null): LoginEmailOutcome {
        if (!allowed()) return LoginEmailOutcome(issue = LoginEmailIssue.STATE_CHANGED)
        if (response !is TdApi.EmailAddressAuthenticationCodeInfo || response.length !in 0..100) return LoginEmailOutcome(issue = issue(response))
        val value = synchronized(lock) {
            if (!active || retired(owner)) null
            else Challenge(owner, ++nextChallenge).also { challenge = it }
        } ?: return LoginEmailOutcome(issue = LoginEmailIssue.STATE_CHANGED)
        return LoginEmailOutcome(SettingsEmailCodeInfo(response.emailAddressPattern.orEmpty(), response.length),
            token?.let { LoginEmailChallengeToken(it, value.revision) })
    }

    private fun operation(owner: Owner, allowed: () -> Boolean, result: (LoginEmailOutcome) -> Unit,
        execute: ((LoginEmailOutcome) -> Unit) -> Unit,
    ) {
        if (!allowed()) { result(LoginEmailOutcome(issue = LoginEmailIssue.STATE_CHANGED)); return }
        val id = synchronized(lock) { if (!active || flight != 0L) 0L else (++nextFlight).also { flight = it } }
        if (id == 0L) { result(LoginEmailOutcome(issue = LoginEmailIssue.BUSY)); return }
        val completed = AtomicBoolean()
        fun done(outcome: LoginEmailOutcome) {
            if (!completed.compareAndSet(false, true)) return
            val stillCurrent = allowed()
            synchronized(lock) {
                if (flight == id) flight = 0
                if (!stillCurrent && challenge?.owner == owner) challenge = null
            }
            // No capability or accepted result belongs to a page that lost ownership while
            // native completion was being delivered. Its original callback still releases flight.
            result(if (stillCurrent) outcome else LoginEmailOutcome(issue = LoginEmailIssue.STATE_CHANGED))
        }
        try { execute(::done) } catch (_: Exception) { done(LoginEmailOutcome(issue = LoginEmailIssue.REQUEST_FAILED)) }
    }

    private fun request(function: TdApi.Function<*>, allowed: () -> Boolean, result: (TdApi.Object?) -> Unit) {
        val completed = AtomicBoolean()
        fun finish(value: TdApi.Object?) { if (completed.compareAndSet(false, true)) result(value) }
        if (!allowed()) { finish(null); return }
        try { send(function, { finish(it) }, { finish(null) }) } catch (_: Exception) { finish(null) }
    }

    private fun availability(response: TdApi.Object?) = when {
        response is TdApi.Ok -> LoginEmailAvailability.ALLOWED
        response is TdApi.Error && response.code == 404 -> LoginEmailAvailability.NOT_REQUIRED
        else -> LoginEmailAvailability.FAILED
    }
    private fun issue(response: TdApi.Object?): LoginEmailIssue = when {
        response is TdApi.Error && (response.code == 429 || response.message.orEmpty().matches(Regex("FLOOD_(?:PREMIUM_)?WAIT_[0-9]+"))) -> LoginEmailIssue.TOO_MANY_ATTEMPTS
        response is TdApi.Error -> when (response.message) {
            "EMAIL_INVALID", "EMAIL_NOT_ALLOWED" -> LoginEmailIssue.EMAIL_INVALID
            "CODE_INVALID", "EMAIL_CODE_INVALID" -> LoginEmailIssue.CODE_INVALID
            "CODE_EXPIRED", "EMAIL_CODE_EXPIRED", "EMAIL_HASH_EXPIRED", "No login email address code was sent" -> LoginEmailIssue.CODE_EXPIRED
            "EMAIL_NOT_SETUP" -> LoginEmailIssue.NOT_AVAILABLE
            else -> LoginEmailIssue.REQUEST_FAILED
        }
        else -> LoginEmailIssue.REQUEST_FAILED
    }
    private fun passwordIssue(issue: LoginEmailIssue?) = when (issue) {
        LoginEmailIssue.EMAIL_INVALID -> SettingsPasswordIssue.EMAIL_INVALID
        LoginEmailIssue.CODE_INVALID -> SettingsPasswordIssue.CODE_INVALID
        LoginEmailIssue.CODE_EXPIRED -> SettingsPasswordIssue.CODE_EXPIRED
        LoginEmailIssue.NOT_AVAILABLE -> SettingsPasswordIssue.RECOVERY_UNAVAILABLE
        LoginEmailIssue.STATE_CHANGED, LoginEmailIssue.PROOF_UNCONFIRMED -> SettingsPasswordIssue.STATE_CHANGED
        else -> null
    }
    private fun settingsError(issue: LoginEmailIssue) = when (issue) {
        LoginEmailIssue.TOO_MANY_ATTEMPTS -> SettingsError.RATE_LIMITED
        LoginEmailIssue.EMAIL_INVALID, LoginEmailIssue.CODE_INVALID, LoginEmailIssue.CODE_EXPIRED -> SettingsError.INVALID_VALUE
        LoginEmailIssue.BUSY, LoginEmailIssue.STATE_CHANGED, LoginEmailIssue.NOT_AVAILABLE -> SettingsError.NOT_READY
        else -> SettingsError.REQUEST_FAILED
    }
    private fun validEmail(value: String): Boolean {
        val at = value.indexOf('@')
        return value.length in 3..320 && at > 0 && at == value.lastIndexOf('@') &&
            value.indexOf('.', at + 1) > at + 1 && !value.endsWith('.') && value.none { it.isWhitespace() || it.isISOControl() }
    }
    private companion object { val LOGIN_OPERATIONS = setOf(SettingsPasswordOperation.SET_LOGIN_EMAIL,
        SettingsPasswordOperation.RESEND_LOGIN_EMAIL, SettingsPasswordOperation.CONFIRM_LOGIN_EMAIL) }
}
