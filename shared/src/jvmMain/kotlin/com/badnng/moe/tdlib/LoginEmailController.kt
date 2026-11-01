package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import java.util.concurrent.atomic.AtomicBoolean

/** Actor-owned public state; page secrets never enter this controller or its snapshot. */
internal class LoginEmailController(
    private val generation: Long, private val session: TdLoginEmailSession,
    private val enqueue: (() -> Unit) -> Unit, private val publish: (LoginEmailSnapshot) -> Unit,
) {
    private var snapshot = LoginEmailSnapshot(generation = generation)
    private val active = AtomicBoolean(true)
    private var started = false
    private var nextOwner = 0L
    private var requestId = 0L
    private var ownerActive = AtomicBoolean(false)
    private var pageLease: () -> Boolean = { false }
    private var hidingOptionalEpoch: Long? = null

    /** Safe before READY: subscription and publication only, never a network request. */
    fun start() {
        if (!active.get() || started) return
        started = true
        acceptSuggestions(session.suggestions())
        session.observeSuggestions { value -> enqueue { acceptSuggestions(value) } }
    }

    fun deactivate() {
        if (!active.compareAndSet(true, false)) return
        retireOwner()
        session.observeSuggestions(null)
        session.retireAll()
        snapshot = LoginEmailSnapshot(generation = generation)
        emit()
    }

    private fun acceptSuggestions(value: LoginEmailSuggestions) {
        if (!active.get() || value.revision < snapshot.suggestions.revision) return
        val mandatory = value.requiredPresent && snapshot.satisfiedRequiredEpoch != value.requiredEpoch
        hidingOptionalEpoch?.let { epoch ->
            if (!value.contains(LoginEmailEntryKind.OPTIONAL, epoch) || mandatory) {
                hidingOptionalEpoch = null
                requestId++
                snapshot = snapshot.copy(busy = false, issue = null)
            }
        }
        val token = snapshot.token
        if (token != null && (!value.contains(token.kind, token.suggestionEpoch) ||
                token.kind == LoginEmailEntryKind.OPTIONAL && mandatory)) {
            retireOwner()
            snapshot = snapshot.copy(token = null, openingId = 0, busy = false, availability = LoginEmailAvailability.UNKNOWN,
                phase = LoginEmailPhase.EMAIL, codeInfo = null, challenge = null, issue = null, verificationAccepted = false)
        }
        snapshot = snapshot.copy(suggestions = value, mandatory = mandatory)
        emit()
    }

    fun open(expectedGeneration: Long, openingId: Long, kind: LoginEmailEntryKind, lease: () -> Boolean) {
        if (!active.get() || !session.authorizationReady() || expectedGeneration != generation || openingId == 0L || !lease()) return
        val raw = session.suggestions()
        acceptSuggestions(raw)
        if (!raw.contains(kind, raw.epoch(kind)) || kind == LoginEmailEntryKind.OPTIONAL && snapshot.mandatory ||
            kind == LoginEmailEntryKind.MANDATORY && !snapshot.mandatory) return
        retireOwner()
        ownerActive = AtomicBoolean(true)
        pageLease = lease
        val token = LoginEmailSetupToken(generation, ++nextOwner, raw.epoch(kind), kind)
        snapshot = snapshot.copy(token = token, openingId = openingId, availability = LoginEmailAvailability.CHECKING,
            phase = LoginEmailPhase.EMAIL, busy = true, codeInfo = null, challenge = null, issue = null,
            verificationAccepted = false, fulfilledElsewhere = false)
        emit()
        probe(token)
    }

    fun accepts(token: LoginEmailSetupToken): Boolean = active.get() && ownerActive.get() && pageLease() &&
        snapshot.token == token && session.suggestions().contains(token.kind, token.suggestionEpoch) &&
        (token.kind == LoginEmailEntryKind.MANDATORY || !snapshot.mandatory) && session.authorizationReady()

    fun close(token: LoginEmailSetupToken) {
        // The external page lease may already be closed. Closing must still retire its exact token.
        if (!active.get() || snapshot.token != token) return
        retireOwner()
        snapshot = snapshot.copy(token = null, openingId = 0, busy = false, availability = LoginEmailAvailability.UNKNOWN,
            phase = LoginEmailPhase.EMAIL, codeInfo = null, challenge = null, issue = null, verificationAccepted = false)
        emit()
    }

    private fun retireOwner() {
        ownerActive.set(false)
        hidingOptionalEpoch = null
        requestId++
        snapshot.token?.let(session::retire)
        pageLease = { false }
    }

    private fun guard(token: LoginEmailSetupToken): () -> Boolean {
        val owner = ownerActive
        val page = pageLease
        return { active.get() && owner.get() && page() && session.suggestions().contains(token.kind, token.suggestionEpoch) && session.authorizationReady() }
    }

    private fun probe(token: LoginEmailSetupToken) {
        val current = guard(token)
        request(token, { done -> session.probe(token, current, done) }, LoginEmailAvailability.FAILED) { availability ->
            snapshot = snapshot.copy(availability = availability, busy = false, issue = when (availability) {
                LoginEmailAvailability.FAILED -> LoginEmailIssue.REQUEST_FAILED
                LoginEmailAvailability.UNKNOWN -> LoginEmailIssue.STATE_CHANGED
                else -> null
            })
            emit()
            if (availability == LoginEmailAvailability.NOT_REQUIRED) proof(token)
        }
    }

    fun submitEmail(token: LoginEmailSetupToken, email: String, completed: (LoginEmailOutcome) -> Unit) {
        if (!canSubmit(token) || snapshot.phase != LoginEmailPhase.EMAIL) { completed(notReady()); return }
        snapshot = snapshot.copy(busy = true, issue = null)
        emit()
        val current = guard(token)
        request(token, { done -> session.sendEmail(token, email, current, done) }, LoginEmailOutcome(issue = LoginEmailIssue.REQUEST_FAILED)) { response ->
            val code = response.codeInfo
            val challenge = response.challenge
            snapshot = if (code != null && challenge?.owner == token && response.issue == null)
                snapshot.copy(busy = false, phase = LoginEmailPhase.CODE, codeInfo = code, challenge = challenge, issue = null)
            else snapshot.copy(busy = false, issue = response.issue ?: LoginEmailIssue.REQUEST_FAILED)
            emit()
            completed(response)
            if (response.issue == LoginEmailIssue.NOT_AVAILABLE) proof(token)
        }
    }

    fun resend(challenge: LoginEmailChallengeToken, completed: (LoginEmailOutcome) -> Unit) {
        val token = challenge.owner
        if (!canSubmit(token) || snapshot.phase != LoginEmailPhase.CODE || snapshot.challenge != challenge) {
            completed(notReady()); return
        }
        snapshot = snapshot.copy(busy = true, issue = null)
        emit()
        val current = guard(token)
        request(token, { done -> session.resend(challenge, current, done) }, LoginEmailOutcome(issue = LoginEmailIssue.REQUEST_FAILED)) { response ->
            snapshot = snapshot.copy(busy = false, codeInfo = response.codeInfo ?: snapshot.codeInfo,
                challenge = response.challenge ?: snapshot.challenge, issue = response.issue)
            emit()
            completed(response)
        }
    }

    fun check(challenge: LoginEmailChallengeToken, code: String, completed: (LoginEmailOutcome) -> Unit) {
        val token = challenge.owner
        if (!canSubmit(token) || snapshot.phase != LoginEmailPhase.CODE || snapshot.challenge != challenge) {
            completed(notReady()); return
        }
        snapshot = snapshot.copy(busy = true, issue = null)
        emit()
        val current = guard(token)
        request(token, { done -> session.check(challenge, code, current, done) }, LoginEmailOutcome(issue = LoginEmailIssue.REQUEST_FAILED)) { response ->
            snapshot = if (response.verificationAccepted && response.issue == null)
                snapshot.copy(busy = false, phase = LoginEmailPhase.PROOF_PENDING, challenge = null,
                    verificationAccepted = true, issue = null)
            else snapshot.copy(busy = false, issue = response.issue ?: LoginEmailIssue.REQUEST_FAILED)
            emit()
            completed(response)
            if (response.verificationAccepted && response.issue == null) proof(token)
        }
    }

    fun refreshProof(token: LoginEmailSetupToken) {
        if (!accepts(token) || snapshot.busy) return
        if (snapshot.phase == LoginEmailPhase.PROOF_PENDING || snapshot.availability == LoginEmailAvailability.NOT_REQUIRED) proof(token)
        else {
            snapshot = snapshot.copy(availability = LoginEmailAvailability.CHECKING, busy = true, issue = null)
            emit()
            probe(token)
        }
    }

    private fun proof(token: LoginEmailSetupToken) {
        if (!accepts(token)) return
        snapshot = snapshot.copy(phase = LoginEmailPhase.PROOF_PENDING, busy = true, issue = null)
        emit()
        val current = guard(token)
        request(token, { done -> session.proof(token, current, done) }, LoginEmailProof(LoginEmailAvailability.FAILED, issue = LoginEmailIssue.REQUEST_FAILED)) { proof ->
            val complete = proof.availability == LoginEmailAvailability.NOT_REQUIRED && proof.hasLoginEmail && proof.issue == null
            // A failed external-fulfillment check may later find setup available again. Only
            // an unaccepted owner may return to email entry; an accepted code needs proof only.
            val mayEnterEmail = !snapshot.verificationAccepted && proof.availability == LoginEmailAvailability.ALLOWED && proof.issue == null
            if (complete && token.kind == LoginEmailEntryKind.MANDATORY) {
                session.satisfyRequired(token.suggestionEpoch)
                snapshot = snapshot.copy(satisfiedRequiredEpoch = token.suggestionEpoch, mandatory = false)
            }
            snapshot = snapshot.copy(busy = false, availability = proof.availability,
                phase = when { complete -> LoginEmailPhase.COMPLETE; mayEnterEmail -> LoginEmailPhase.EMAIL; else -> LoginEmailPhase.PROOF_PENDING },
                issue = if (complete || mayEnterEmail) null else proof.issue ?: LoginEmailIssue.PROOF_UNCONFIRMED,
                fulfilledElsewhere = complete && !snapshot.verificationAccepted)
            emit()
        }
    }

    fun hideOptional(expectedGeneration: Long, epoch: Long) {
        if (!active.get() || !session.authorizationReady() || expectedGeneration != generation || snapshot.busy || snapshot.mandatory ||
            !session.suggestions().contains(LoginEmailEntryKind.OPTIONAL, epoch)) return
        val revision = ++requestId
        hidingOptionalEpoch = epoch
        snapshot = snapshot.copy(busy = true, issue = null)
        emit()
        val satisfiedEpoch = snapshot.satisfiedRequiredEpoch
        val current = {
            val suggestions = session.suggestions()
            active.get() && suggestions.contains(LoginEmailEntryKind.OPTIONAL, epoch) &&
                (!suggestions.requiredPresent || suggestions.requiredEpoch == satisfiedEpoch) && session.authorizationReady()
        }
        val completed = AtomicBoolean()
        fun finish(response: LoginEmailOutcome) {
            if (completed.compareAndSet(false, true)) enqueue {
                if (active.get() && revision == requestId && current()) {
                    hidingOptionalEpoch = null
                    snapshot = snapshot.copy(busy = false, issue = response.issue)
                    emit() // Membership changes only when the actual native removed delta arrives.
                }
            }
        }
        try { session.hideOptional(epoch, current, ::finish) }
        catch (_: Exception) { finish(LoginEmailOutcome(issue = LoginEmailIssue.REQUEST_FAILED)) }
    }

    private fun canSubmit(token: LoginEmailSetupToken) = accepts(token) && !snapshot.busy &&
        snapshot.availability == LoginEmailAvailability.ALLOWED
    private fun notReady() = LoginEmailOutcome(issue = LoginEmailIssue.NOT_AVAILABLE)
    private fun <T> request(token: LoginEmailSetupToken, submit: ((T) -> Unit) -> Unit, failure: T, receive: (T) -> Unit) {
        val revision = ++requestId
        val completed = AtomicBoolean()
        fun finish(response: T) { if (completed.compareAndSet(false, true)) enqueue {
            if (accepts(token) && revision == requestId) receive(response)
        } }
        try { submit(::finish) } catch (_: Exception) { finish(failure) }
    }
    private fun emit() = publish(snapshot)
}
