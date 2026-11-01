package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import java.util.concurrent.atomic.AtomicBoolean

/** Actor-owned UI state. Only real native updates establish the account value. */
internal class ReactionNotificationsController(
    private val generation: Long,
    private val session: TdReactionNotificationsSession,
    private val enqueue: (() -> Unit) -> Unit,
    private val publish: (ReactionNotificationsSnapshot) -> Unit,
    private val isAllowed: () -> Boolean = { true },
    private val terminal: SettingsSpecialMutationBoundary = SettingsSpecialMutationBoundary(enqueue),
) {
    private var snapshot = ReactionNotificationsSnapshot(token = ReactionNotificationsToken(generation))
    private var native = ReactionNotificationNativeState()
    private var nextOwner = 0L
    private var nextAttempt = 0L
    private var latestIntent = 0L
    private var revision = 0L
    private var mutation: Long? = null
    private var waiting: Awaiting? = null
    private var owner = AtomicBoolean(false)
    private var pageLease: () -> Boolean = { false }
    private val retired = AtomicBoolean(false)
    private val allowedGate = AtomicBoolean(false)

    private data class Awaiting(val desired: ReactionNotificationValue, val expectedRevision: Long)

    init {
        session.observe { state -> enqueue { changed(state) } }
    }

    /** Called on the engine actor; this is also where the worker's permission gate is refreshed. */
    fun accepts(token: ReactionNotificationsToken): Boolean {
        val allowed = allowed()
        return !retired.get() && allowed && owner.get() && pageLease() && snapshot.opened && token == snapshot.token
    }

    fun open(pageLease: () -> Boolean) {
        if (retired.get() || !allowed() || !pageLease()) return
        if (owner.get() && this.pageLease()) return
        owner.set(false)
        owner = AtomicBoolean(true)
        this.pageLease = pageLease
        snapshot = ReactionNotificationsSnapshot(
            token = ReactionNotificationsToken(generation, ++nextOwner), opened = true,
            native = native, saving = mutation != null, awaitingUpdate = waiting != null,
        )
        if (mutation == null) {
            val error = readCache()
            snapshot = snapshot.copy(native = native, awaitingUpdate = waiting != null,
                error = error, refreshRequired = error != null)
        }
        emit()
    }

    fun close(token: ReactionNotificationsToken) {
        if (retired.get() || token != snapshot.token) return
        owner.set(false)
        pageLease = { false }
        snapshot = ReactionNotificationsSnapshot(token = ReactionNotificationsToken(generation, ++nextOwner))
        emit()
    }

    fun refreshFromCache(token: ReactionNotificationsToken) {
        if (!accepts(token)) return
        val error = readCache()
        snapshot = snapshot.copy(native = native, error = error, refreshRequired = error != null,
            saving = mutation != null, activeAttempt = snapshot.activeAttempt.takeIf { mutation != null },
            awaitingUpdate = waiting != null)
        emit()
    }

    fun save(token: ReactionNotificationsToken, edit: ReactionNotificationsEdit) {
        if (!accepts(token)) return
        val id = ++nextAttempt
        latestIntent = id
        val cacheError = readCache()
        val error = when {
            mutation != null || waiting != null -> SettingsError.NOT_READY
            cacheError != null -> cacheError
            native.value == null -> SettingsError.NOT_READY
            native.error != null -> native.error
            edit.patch.isEmpty || edit.contentRevision < 0 -> SettingsError.INVALID_VALUE
            edit.expected != native -> SettingsError.CONFLICT
            else -> null
        }
        if (error != null) {
            snapshot = snapshot.copy(native = native, outcome = ReactionNotificationsOutcome(id, edit, error = error),
                error = error, refreshRequired = error == SettingsError.CONFLICT || snapshot.refreshRequired)
            emit()
            return
        }
        mutation = id
        val capturedOwner = owner
        val visible = pageLease
        // Native continuations must never call the actor-owned isAllowed or inspect actor state.
        val authority = terminal.captureAuthority()
        val current = { !retired.get() && capturedOwner.get() && allowedGate.get() && visible() && authority() }
        val once = AtomicBoolean(false)
        snapshot = snapshot.copy(native = native, saving = true, activeAttempt = id, outcome = null,
            error = null, refreshRequired = false)
        emit()
        fun finish(result: ReactionNotificationSaveResult) {
            if (SettingsSpecialMutationContext.receipt()?.outcome == SettingsSpecialWriteOutcome.INDETERMINATE) return
            if (!once.compareAndSet(false, true)) return
            terminal.enqueue { completed(token, id, edit, result, authority) }
        }
        try {
            session.save(edit.expected, edit.patch, current, ::finish)
        } catch (_: Exception) {
            finish(ReactionNotificationSaveResult(error = SettingsError.REQUEST_FAILED))
        }
    }

    fun deactivate() {
        if (!retired.compareAndSet(false, true)) return
        allowedGate.set(false)
        owner.set(false)
        pageLease = { false }
        waiting = null
        session.observe(null)
        snapshot = ReactionNotificationsSnapshot(token = ReactionNotificationsToken(generation, ++nextOwner))
        emit()
    }

    private fun completed(
        token: ReactionNotificationsToken,
        id: Long,
        edit: ReactionNotificationsEdit,
        result: ReactionNotificationSaveResult,
        authority: () -> Boolean,
    ) {
        if (mutation != id) return
        mutation = null
        if (retired.get()) return
        // Callback.actual may predate an update already delivered to the session or the actor.
        val cacheError = readCache()
        var error = result.error ?: cacheError
        if (result.locallyAccepted) {
            val desired = result.desired ?: edit.expected.value?.let(edit.patch::applyTo)
            if (desired == null) {
                error = error ?: SettingsError.INVALID_VALUE
            } else if (native.value == desired && native.error == null) {
                waiting = null
            } else if (native.revision > edit.expected.revision) {
                waiting = null
                error = SettingsError.CONFLICT
            } else {
                waiting = Awaiting(desired, edit.expected.revision)
            }
        }
        val ownsResult = accepts(token)
        if (!authority()) {
            val opened = snapshot.token
            terminal.readback({ (opened != token || authority()) && accepts(opened) }) { refreshFromCache(opened) }
            return
        }
        if (!snapshot.opened || !owner.get() || !pageLease() || !allowed()) return
        snapshot = snapshot.copy(native = native, saving = false, activeAttempt = null,
            awaitingUpdate = waiting != null, error = error,
            refreshRequired = error != null,
            outcome = if (ownsResult && latestIntent == id) {
                ReactionNotificationsOutcome(id, edit, result.locallyAccepted, error)
            } else snapshot.outcome)
        emit()
    }

    private fun changed(state: ReactionNotificationNativeState) {
        if (retired.get() || state.revision <= native.revision) return
        val conflict = adopt(state)
        if (!snapshot.opened || !owner.get() || !pageLease() || !allowed()) return
        snapshot = snapshot.copy(native = native, awaitingUpdate = waiting != null,
            error = if (conflict) SettingsError.CONFLICT else native.error,
            refreshRequired = conflict || native.error != null)
        emit()
    }

    /** A cache read never performs a request, synthesizes defaults, or accepts older revisions. */
    private fun readCache(): SettingsError? = try {
        val current = session.state()
        if (current.revision > native.revision) {
            if (adopt(current)) SettingsError.CONFLICT else current.error
        } else native.error
    } catch (_: Exception) {
        SettingsError.REQUEST_FAILED
    }

    private fun adopt(state: ReactionNotificationNativeState): Boolean {
        native = state
        val pending = waiting ?: return false
        if (state.revision <= pending.expectedRevision) return false
        waiting = null
        return state.error != null || state.value != pending.desired
    }

    private fun allowed(): Boolean = isAllowed().also(allowedGate::set)

    private fun emit() {
        snapshot = snapshot.copy(revision = ++revision)
        publish(snapshot)
    }
}
