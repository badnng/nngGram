package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import java.util.concurrent.atomic.AtomicBoolean

/** Actor-owned page state; a native mutation outlives its original page until its real completion. */
internal class ContactSignupController(
    private val generation: Long,
    private val session: TdContactSignupSession,
    private val enqueue: (() -> Unit) -> Unit,
    private val publish: (ContactSignupSnapshot) -> Unit,
    private val isAllowed: () -> Boolean = { true },
    private val terminal: SettingsSpecialMutationBoundary = SettingsSpecialMutationBoundary(enqueue),
) {
    private var snapshot = ContactSignupSnapshot(token = ContactSignupToken(generation))
    private var nextOwner = 0L
    private var request = 0L
    private var revision = 0L
    private var acceptedRevision = 0L
    private var mutationInFlight = false
    private var acceptedDesired: Boolean? = null
    private var owner = AtomicBoolean(false)
    private var pageLease: () -> Boolean = { false }
    @Volatile private var retired = false

    init { session.observe { enqueue { changed() } } }
    fun accepts(token: ContactSignupToken) = !retired && owner.get() && pageLease() && isAllowed() &&
        snapshot.opened && token == snapshot.token

    fun open(active: () -> Boolean) {
        if (retired || !isAllowed() || !active() || (owner.get() && pageLease())) return
        owner.set(false); owner = AtomicBoolean(true); pageLease = active
        snapshot = ContactSignupSnapshot(token = ContactSignupToken(generation, ++nextOwner), opened = true,
            loading = !mutationInFlight, saving = mutationInFlight, desiredEnabled = acceptedDesired,
            readbackPending = acceptedDesired != null, acceptedRevision = acceptedRevision)
        emit()
        if (!mutationInFlight) read()
    }

    fun close(token: ContactSignupToken) {
        if (retired || token != snapshot.token) return
        owner.set(false); pageLease = { false }; request++
        snapshot = ContactSignupSnapshot(token = ContactSignupToken(generation, ++nextOwner))
        emit()
    }

    fun deactivate() {
        retired = true; owner.set(false); pageLease = { false }; request++
        acceptedDesired = null; session.observe(null)
        snapshot = ContactSignupSnapshot(token = ContactSignupToken(generation, ++nextOwner))
        emit()
    }

    fun refresh(token: ContactSignupToken) {
        if (!accepts(token) || mutationInFlight || snapshot.loading || snapshot.saving) return
        snapshot = snapshot.copy(loading = true, error = null); emit(); read()
    }

    fun save(token: ContactSignupToken, expectedRevision: Long, expected: ContactSignupValue, desired: Boolean) {
        if (!accepts(token)) return
        val error = when {
            mutationInFlight || snapshot.loading || snapshot.saving || snapshot.readbackPending -> SettingsError.NOT_READY
            snapshot.refreshRequired || snapshot.value == null || snapshot.revision != expectedRevision ||
                snapshot.value != expected -> SettingsError.CONFLICT
            else -> null
        }
        if (error != null) { snapshot = snapshot.copy(error = error); emit(); return }
        mutationInFlight = true
        val id = ++request; val localOwner = owner; val visible = pageLease
        val authority = terminal.captureAuthority()
        val current = { !retired && localOwner.get() && visible() && authority() && isAllowed() }
        val once = AtomicBoolean()
        snapshot = snapshot.copy(saving = true, desiredEnabled = desired, error = null); emit()
        fun finish(outcome: ContactSignupSaveResult) {
            if (SettingsSpecialMutationContext.receipt()?.outcome == SettingsSpecialWriteOutcome.INDETERMINATE) return
            if (!once.compareAndSet(false, true)) return
            terminal.enqueue settle@ {
                mutationInFlight = false
                if (retired) return@settle
                if (outcome.locallyAccepted) { acceptedDesired = desired; acceptedRevision++ }
                if (accepts(token) && current() && request == id) {
                    val actual = outcome.actual
                    if (actual != null && actual.enabled == acceptedDesired) acceptedDesired = null
                    snapshot = snapshot.copy(saving = false, value = actual ?: snapshot.value,
                        desiredEnabled = acceptedDesired, readbackPending = acceptedDesired != null,
                        revision = ++revision, acceptedRevision = acceptedRevision, error = outcome.error,
                        refreshRequired = outcome.error != null || actual == null || acceptedDesired != null)
                    emit()
                } else {
                    val opened = snapshot.token
                    terminal.readback({ (opened != token || current()) && accepts(opened) }) {
                        snapshot = snapshot.copy(saving = false, loading = true, desiredEnabled = acceptedDesired,
                            readbackPending = acceptedDesired != null, acceptedRevision = acceptedRevision)
                        emit(); read()
                    }
                }
            }
        }
        try { session.save(expected, desired, current, ::finish) }
        catch (_: Exception) { finish(ContactSignupSaveResult(desired, error = SettingsError.REQUEST_FAILED)) }
    }

    private fun read() {
        val token = snapshot.token; val id = ++request; val localOwner = owner; val visible = pageLease
        val current = { !retired && localOwner.get() && visible() && isAllowed() }
        val once = AtomicBoolean()
        fun finish(result: SettingsResult<ContactSignupValue>) {
            if (!once.compareAndSet(false, true)) return
            enqueue {
                if (!accepts(token) || !current() || request != id) return@enqueue
                if (result.value?.enabled == acceptedDesired) acceptedDesired = null
                snapshot = snapshot.copy(loading = false, value = result.value, desiredEnabled = acceptedDesired,
                    readbackPending = acceptedDesired != null, revision = ++revision,
                    acceptedRevision = acceptedRevision, error = result.error,
                    refreshRequired = result.value == null || acceptedDesired != null)
                emit()
            }
        }
        try { session.load(current, ::finish) }
        catch (_: Exception) { finish(SettingsResult(error = SettingsError.REQUEST_FAILED)) }
    }

    private fun changed() {
        if (retired || !snapshot.opened || !owner.get() || !pageLease() || !isAllowed()) return
        // GetOption itself emits UpdateOption. Invalidate only; never launch or cancel a read here.
        snapshot = snapshot.copy(revision = ++revision, refreshRequired = true)
        emit()
    }

    private fun emit() = publish(snapshot)
}
