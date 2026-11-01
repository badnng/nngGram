package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import java.util.concurrent.atomic.AtomicBoolean

/** Policy and statistics have independent owners/revisions. No constructor or cancellation writes. */
internal class SettingsStoragePolicyController(
    private val generation: Long,
    private val session: TdSettingsStoragePolicySession,
    private val enqueue: (() -> Unit) -> Unit,
    private val publish: (SettingsStoragePolicySnapshot) -> Unit,
    private val isAllowed: () -> Boolean = { true },
    private val terminal: SettingsSpecialMutationBoundary = SettingsSpecialMutationBoundary(enqueue),
) {
    private var snapshot = SettingsStoragePolicySnapshot(token = SettingsStoragePolicyToken(generation))
    private var nextOwner = 0L
    private var request = 0L
    private var revision = 0L
    private var mutationInFlight = false
    private var owner = AtomicBoolean(false)
    private var pageLease: () -> Boolean = { false }
    private var pageAuthority: () -> Boolean = { false }
    @Volatile private var retired = false

    init { session.observe { enqueue { changed() } } }

    fun accepts(token: SettingsStoragePolicyToken) = !retired && owner.get() && pageLease() && pageAuthority() && isAllowed() &&
        snapshot.opened && snapshot.token == token

    fun open(active: () -> Boolean) {
        if (retired || !isAllowed() || !active()) return
        if (owner.get() && pageLease() && pageAuthority()) return
        owner.set(false)
        owner = AtomicBoolean(true)
        pageLease = active
        pageAuthority = terminal.captureAuthority()
        snapshot = SettingsStoragePolicySnapshot(SettingsStoragePolicyToken(generation, ++nextOwner),
            opened = true, loading = !mutationInFlight, saving = mutationInFlight)
        emit()
        if (!mutationInFlight) read()
    }

    fun close(token: SettingsStoragePolicyToken) {
        if (retired || snapshot.token != token) return
        owner.set(false)
        pageLease = { false }
        pageAuthority = { false }
        request++
        snapshot = SettingsStoragePolicySnapshot(token = SettingsStoragePolicyToken(generation, ++nextOwner))
        emit()
    }

    fun deactivate() {
        retired = true
        owner.set(false)
        pageLease = { false }
        pageAuthority = { false }
        request++
        session.observe(null)
        snapshot = SettingsStoragePolicySnapshot(token = SettingsStoragePolicyToken(generation, ++nextOwner))
        emit()
    }

    fun refresh(token: SettingsStoragePolicyToken) {
        if (!accepts(token) || snapshot.loading || snapshot.saving || mutationInFlight) return
        snapshot = snapshot.copy(loading = true, error = null)
        emit(); read()
    }

    fun save(token: SettingsStoragePolicyToken, expectedRevision: Long, draft: SettingsStoragePolicyDraft,
        requestId: Long, completed: (SettingsStoragePolicyCompletion) -> Unit,
    ) {
        val error = when {
            !accepts(token) || snapshot.loading || snapshot.saving || mutationInFlight -> SettingsError.NOT_READY
            requestId <= 0 || !draft.valid -> SettingsError.INVALID_VALUE
            snapshot.refreshRequired || !snapshot.values.known || snapshot.readErrors.isNotEmpty() || snapshot.revision != expectedRevision -> SettingsError.CONFLICT
            else -> null
        }
        if (error != null) {
            val response = SettingsStoragePolicyCompletion.rejected(token, requestId, error)
            if (accepts(token)) {
                snapshot = snapshot.copy(error = error, lastCompletion = response,
                    refreshRequired = snapshot.refreshRequired || error == SettingsError.CONFLICT)
                emit()
            }
            completed(response); return
        }
        mutationInFlight = true
        val id = ++request
        val localOwner = owner
        val visible = pageLease
        val authority = terminal.captureAuthority()
        val current = { !retired && localOwner.get() && visible() && isAllowed() && authority() }
        val once = AtomicBoolean()
        val settled = AtomicBoolean()
        snapshot = snapshot.copy(saving = true, steps = emptyList(), lastCompletion = null, error = null)
        emit()
        fun receipt(outcome: SettingsStoragePolicySaveOutcome): SettingsStoragePolicyCompletion {
            val delivered = if (current()) outcome else outcome.copy(
                // Keep true per-step receipts; revoking UI authority never turns them into
                // a native failure. Only account values are withheld from an old form.
                status = if (outcome.steps.any { it.accepted || it.receipt == SettingsStoragePolicyReceipt.INDETERMINATE })
                    SettingsStoragePolicyStatus.INCOMPLETE else outcome.status,
                actual = null, refreshRequired = outcome.refreshRequired || outcome.steps.any { it.accepted })
            return SettingsStoragePolicyCompletion(token, requestId, delivered.status, delivered.steps,
                delivered.actual, delivered.error, delivered.refreshRequired, delivered.remainingSteps, delivered.physicalPending)
        }
        fun revalidateCurrentPage() {
            val target = snapshot.token
            val targetOwner = owner
            val visible = pageLease
            terminal.readback({ !retired && targetOwner.get() && visible() && accepts(target) && !mutationInFlight }) {
                snapshot = snapshot.copy(saving = false, loading = true)
                emit(); read()
            }
        }
        fun apply(outcome: SettingsStoragePolicySaveOutcome, response: SettingsStoragePolicyCompletion,
            revalidate: Boolean = false,
        ) {
            if (!outcome.physicalPending && !settled.compareAndSet(false, true)) return
            terminal.enqueue {
                // Retained physical ownership is independent of the closed page. A late
                // terminal must not clear a replacement flight or publish old account data.
                if (!outcome.physicalPending) mutationInFlight = false
                if (retired) return@enqueue
                if (accepts(token) && current() && request == id) {
                    val actual = outcome.actual
                    snapshot = snapshot.copy(saving = outcome.physicalPending, values = actual?.values ?: snapshot.values,
                        readErrors = actual?.errors ?: snapshot.readErrors,
                        revision = if (actual != null) ++revision else snapshot.revision,
                        refreshRequired = response.refreshRequired || actual?.complete != true,
                        steps = response.steps, error = response.error, lastCompletion = response)
                    emit()
                    if (revalidate) revalidateCurrentPage()
                } else if (!outcome.physicalPending && accepts(snapshot.token)) {
                    // A replacement waits for the original native callback, then reads its actual effects.
                    snapshot = snapshot.copy(saving = false)
                    emit(); revalidateCurrentPage()
                }
            }
        }
        fun finish(outcome: SettingsStoragePolicySaveOutcome) {
            if (!once.compareAndSet(false, true)) return
            val response = receipt(outcome)
            apply(outcome, response)
            // Even a retired page gets its exact terminal receipt; token/request identity prevent
            // it consuming another form. Delivery does not depend on a still-running Engine queue.
            completed(response)
        }
        try {
            session.saveOwned(snapshot.values, draft, current, { steps -> if (!once.get()) enqueue {
                if (mutationInFlight && accepts(token) && current() && request == id) {
                    snapshot = snapshot.copy(steps = steps.toList()); emit()
                }
            } }, ::finish, { outcome ->
                // Settlement is deliberately separate from the form's one logical completion.
                if (once.get() && !outcome.physicalPending) apply(outcome, receipt(outcome), revalidate = true)
            })
        } catch (_: Exception) {
            finish(SettingsStoragePolicySaveOutcome(SettingsStoragePolicyStatus.INCOMPLETE,
                error = SettingsError.REQUEST_FAILED, refreshRequired = true, physicalPending = true))
        }
    }

    private fun read() {
        val token = snapshot.token
        val id = ++request
        val localOwner = owner
        val visible = pageLease
        val once = AtomicBoolean()
        val authority = terminal.captureAuthority()
        val current = { !retired && localOwner.get() && visible() && isAllowed() && authority() }
        fun finish(value: SettingsStoragePolicyRead) {
            if (!once.compareAndSet(false, true)) return
            enqueue {
                if (accepts(token) && current() && request == id) {
                    snapshot = snapshot.copy(loading = false, values = value.values, readErrors = value.errors,
                        revision = ++revision, refreshRequired = !value.complete,
                        error = value.errors.values.firstOrNull())
                    emit()
                }
            }
        }
        try { session.load(current, ::finish) }
        catch (_: Exception) { finish(SettingsStoragePolicyRead(errors = SettingsStoragePolicyField.entries.associateWith { SettingsError.REQUEST_FAILED })) }
    }

    private fun changed() {
        if (!accepts(snapshot.token) || mutationInFlight) return
        request++
        snapshot = snapshot.copy(loading = false, revision = ++revision, refreshRequired = true, error = SettingsError.CONFLICT)
        emit()
    }

    private fun emit() = publish(snapshot)
}
