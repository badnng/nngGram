package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import java.util.concurrent.atomic.AtomicBoolean

/** Actor-owned state. Native workers only inspect captured atomics and the caller's thread-safe page lease. */
internal class SettingsProfileColorsController(
    private val generation: Long,
    private val session: TdSettingsProfileColorsSession,
    private val enqueue: (() -> Unit) -> Unit,
    private val publish: (SettingsProfileColorsSnapshot) -> Unit,
    private val isAllowed: () -> Boolean = { true },
    private val terminal: SettingsSpecialMutationBoundary = SettingsSpecialMutationBoundary(enqueue),
) {
    private var snapshot = SettingsProfileColorsSnapshot()
    private val alive = AtomicBoolean(true)
    private val allowedGate = AtomicBoolean(false)
    private var owner = AtomicBoolean(false)
    private var pageLease: () -> Boolean = { false }
    private var picker = AtomicBoolean(false)
    private var nextOwner = 0L
    private var nextAttempt = 0L
    private var readRequest = 0L
    private var metadataRequest = 0L
    private var mutationInFlight = false
    // Bookkeeping only: no account values or old editor input survive in this receipt.
    private var lastWriteReceipt: SettingsSpecialWriteReceipt? = null
    private var reloadAfterMutation = false
    private val pageEmojis = LinkedHashMap<Long, SettingsBackgroundEmoji>()
    private val observerQueued = AtomicBoolean(false)

    init {
        session.observe {
            if (alive.get() && observerQueued.compareAndSet(false, true)) enqueue {
                observerQueued.set(false)
                if (current()) {
                    if (mutationInFlight) reloadAfterMutation = true
                    else if (!snapshot.loading) read(explicit = false)
                }
            }
        }
    }

    fun accepts(token: SettingsProfileColorsToken): Boolean = alive.get() && owner.get() && snapshot.token == token

    fun open(userId: Long, active: () -> Boolean = { true }) {
        if (!alive.get() || userId <= 0 || !allowed() || !lease(active)) return
        if (owner.get() && snapshot.token.userId == userId && lease(pageLease)) return
        owner.set(false)
        picker.set(false)
        owner = AtomicBoolean(true)
        picker = AtomicBoolean(false)
        pageLease = active
        readRequest++
        metadataRequest++
        pageEmojis.clear()
        snapshot = SettingsProfileColorsSnapshot(
            token = SettingsProfileColorsToken(generation, ++nextOwner, userId),
            opened = true, loading = true, busy = mutationInFlight,
        )
        emit()
        if (!mutationInFlight) read(explicit = true)
    }

    fun close(token: SettingsProfileColorsToken) {
        if (!accepts(token)) return
        clearPage()
        emit()
    }

    fun deactivate() {
        if (!alive.compareAndSet(true, false)) return
        clearPage()
        runCatching { session.observe(null) }
    }

    fun refresh(token: SettingsProfileColorsToken) {
        if (!accepts(token) || !current() || mutationInFlight || snapshot.loading) return
        read(explicit = true)
    }

    fun save(token: SettingsProfileColorsToken, edit: SettingsProfileColorEdit) {
        if (!accepts(token) || !current() || mutationInFlight || snapshot.loading || snapshot.readbackPending.isNotEmpty()) return
        val data = snapshot.data ?: return
        val attempt = SettingsProfileColorAttempt(++nextAttempt, edit)
        fun reject(error: SettingsError) {
            snapshot = snapshot.copy(error = error, outcomes = snapshot.outcomes +
                (edit.part to SettingsProfileColorOutcome(attempt, error = error)))
            emit()
        }
        if (data.values.value(edit.part) != edit.base) { reject(SettingsError.CONFLICT); return }
        if (!data.premium) { reject(SettingsError.FORBIDDEN); return }
        val desiredEmoji = edit.desired.backgroundCustomEmojiId
        if (desiredEmoji != 0L && desiredEmoji != edit.base.backgroundCustomEmojiId && desiredEmoji !in pageEmojis) {
            reject(SettingsError.INVALID_VALUE); return
        }
        val catalog = data.catalog
        val catalogError = if (edit.part == SettingsProfileColorPart.NAME) catalog.nameError else catalog.profileError
        if (catalogError != null) { reject(SettingsError.CONFLICT); return }
        val reset = if (edit.part == SettingsProfileColorPart.NAME) SettingsProfileColorValue((data.userId % 7).toInt())
            else SettingsProfileColorValue(-1)
        val available = if (edit.part == SettingsProfileColorPart.NAME) catalog.availableNameColorIds else catalog.availableProfileColorIds
        if (edit.desired.colorId < (if (edit.part == SettingsProfileColorPart.NAME) 0 else -1) ||
            edit.desired.colorId != edit.base.colorId && edit.desired != reset && edit.desired.colorId !in available.orEmpty()) {
            reject(SettingsError.INVALID_VALUE); return
        }
        if (edit.part == SettingsProfileColorPart.NAME && data.values.gift != null && !edit.replaceGift) {
            reject(SettingsError.CONFLICT); return
        }
        // Check the actor gate again immediately before handing control to the session.
        if (!current()) { reject(SettingsError.NOT_READY); return }
        val authority = terminal.captureAuthority()
        val pageActive = nativeActive()
        val active = { authority() && pageActive() }
        val once = AtomicBoolean()
        mutationInFlight = true
        readRequest++ // A previously requested observer read cannot overwrite this operation's actual result.
        snapshot = snapshot.copy(busy = true, loading = false, error = null, activeSave = attempt)
        emit()
        fun finish(result: SettingsProfileColorMutationResult) {
            // Unknown is advisory, not a physical terminal: keep both owner slots for a late raw receipt.
            val receipt = SettingsSpecialMutationContext.receipt()
            val uncertain = receipt?.outcome == SettingsSpecialWriteOutcome.INDETERMINATE
            if (uncertain) {
                if (once.get()) return
                terminal.enqueue {
                    if (once.get()) return@enqueue
                    lastWriteReceipt = receipt
                    if (!accepts(token) || !current() || !authority()) return@enqueue
                    snapshot = snapshot.copy(error = SettingsError.REQUEST_FAILED, refreshRequired = true,
                        readbackPending = snapshot.readbackPending + edit.part,
                        outcomes = snapshot.outcomes + (edit.part to SettingsProfileColorOutcome(attempt,
                            locallyAccepted = false, error = SettingsError.REQUEST_FAILED)))
                    emit()
                }
                return
            }
            if (!once.compareAndSet(false, true)) return
            terminal.enqueue {
                lastWriteReceipt = receipt ?: if (result.locallyAccepted)
                    SettingsSpecialWriteReceipt(SettingsSpecialWriteOutcome.ACCEPTED) else null
                mutationInFlight = false
                if (!alive.get()) return@enqueue
                if (!accepts(token) || !current() || !authority()) {
                    reloadAfterMutation = false
                    // This is a fresh current-page read, never continuation of the old write.
                    if (current()) {
                        val readOwner = snapshot.token
                        terminal.readback({ accepts(readOwner) && current() }) { read(explicit = true) }
                    }
                    return@enqueue
                }
                val actual = result.actual?.takeIf { it.userId == token.userId }
                val pending = result.locallyAccepted && actual == null
                val mismatch = actual != null && (actual.values.value(edit.part) != edit.desired ||
                    edit.part == SettingsProfileColorPart.NAME && actual.values.gift != null)
                val error = result.error ?: if (mismatch) SettingsError.CONFLICT else if (pending) SettingsError.REQUEST_FAILED else null
                snapshot = snapshot.copy(busy = false, activeSave = null, data = actual ?: snapshot.data,
                    error = error, refreshRequired = pending || mismatch,
                    readbackPending = if (pending) snapshot.readbackPending + edit.part else snapshot.readbackPending - edit.part,
                    outcomes = snapshot.outcomes + (edit.part to SettingsProfileColorOutcome(attempt, result.locallyAccepted, error)))
                emit()
                if (reloadAfterMutation) {
                    reloadAfterMutation = false
                    terminal.readback({ accepts(token) && current() && authority() }) { read(explicit = false) }
                }
            }
        }
        try {
            if (!current()) finish(SettingsProfileColorMutationResult(error = SettingsError.NOT_READY))
            else session.save(data, edit.part, edit.desired, edit.replaceGift, active, ::finish)
        }
        catch (_: Exception) { finish(SettingsProfileColorMutationResult(error = SettingsError.REQUEST_FAILED)) }
    }

    fun openBackgrounds(token: SettingsProfileColorsToken) {
        if (!accepts(token) || !current() || snapshot.backgroundsLoading || snapshot.data == null) return
        picker.set(false)
        picker = AtomicBoolean(true)
        snapshot = snapshot.copy(backgroundsOpened = true, backgroundsLoading = true, backgroundsError = null)
        emit()
        metadata(token, null)
    }

    fun resolveBackgrounds(token: SettingsProfileColorsToken, ids: List<Long>) {
        if (!accepts(token) || !current() || !snapshot.backgroundsOpened || snapshot.backgroundsLoading || !picker.get()) return
        if (ids.isEmpty()) return
        val values = snapshot.data?.values ?: return
        val known = setOf(values.name.backgroundCustomEmojiId, values.profile.backgroundCustomEmojiId,
            values.gift?.modelCustomEmojiId ?: 0L, values.gift?.symbolCustomEmojiId ?: 0L) + pageEmojis.keys
        if (ids.size > 200 || ids.any { it == 0L || it !in known }) {
            snapshot = snapshot.copy(backgroundsError = SettingsError.INVALID_VALUE); emit(); return
        }
        snapshot = snapshot.copy(backgroundsLoading = true, backgroundsError = null)
        emit()
        metadata(token, ids.distinct())
    }

    fun closeBackgrounds(token: SettingsProfileColorsToken) {
        if (!accepts(token)) return
        picker.set(false)
        metadataRequest++
        snapshot = snapshot.copy(backgroundsOpened = false, backgroundsLoading = false, backgroundsError = null)
        emit()
    }

    private fun metadata(token: SettingsProfileColorsToken, ids: List<Long>?) {
        val id = ++metadataRequest
        val active = nativeActive(picker)
        val once = AtomicBoolean()
        fun finish(response: SettingsResult<List<SettingsBackgroundEmoji>>) {
            if (!once.compareAndSet(false, true)) return
            enqueue {
                if (!accepts(token) || !current() || !active() || id != metadataRequest) return@enqueue
                val entries = response.value
                val invalid = entries != null && (entries.size > (if (ids == null) 4096 else 200) ||
                    entries.any { it.customEmojiId == 0L || ids != null && it.customEmojiId !in ids } ||
                    (pageEmojis.keys + entries.map { it.customEmojiId }).size > 4096)
                if (entries == null || invalid) {
                    snapshot = snapshot.copy(backgroundsLoading = false,
                        backgroundsError = if (invalid) SettingsError.INVALID_VALUE else response.error ?: SettingsError.REQUEST_FAILED)
                } else {
                    entries.forEach { pageEmojis[it.customEmojiId] = it }
                    snapshot = snapshot.copy(backgroundsLoading = false, backgrounds = pageEmojis.values.toList(), backgroundsError = response.error)
                }
                emit()
            }
        }
        try {
            if (!current()) finish(SettingsResult(error = SettingsError.NOT_READY))
            else if (ids == null) session.backgroundEmojis(active, ::finish)
            else session.resolveEmojis(ids, active, ::finish)
        } catch (_: Exception) { finish(SettingsResult(error = SettingsError.REQUEST_FAILED)) }
    }

    private fun read(explicit: Boolean) {
        if (!current() || mutationInFlight) return
        val token = snapshot.token
        val id = ++readRequest
        val active = nativeActive()
        val once = AtomicBoolean()
        snapshot = snapshot.copy(loading = true, busy = false, error = null)
        emit()
        fun finish(response: SettingsResult<SettingsProfileColorsData>) {
            if (!once.compareAndSet(false, true)) return
            enqueue {
                if (!accepts(token) || !current() || !active() || id != readRequest) return@enqueue
                val data = response.value?.takeIf { it.userId == token.userId }
                var outcomes = snapshot.outcomes
                var pending = snapshot.readbackPending
                if (explicit && data != null) {
                    pending.forEach { part ->
                        outcomes[part]?.let { outcome ->
                            val mismatch = data.values.value(part) != outcome.attempt.edit.desired ||
                                part == SettingsProfileColorPart.NAME && data.values.gift != null
                            outcomes = outcomes + (part to outcome.copy(error = if (mismatch) SettingsError.CONFLICT else null))
                        }
                    }
                    pending = emptySet()
                }
                val changed = snapshot.data?.let { old -> data != null && old != data } ?: false
                snapshot = snapshot.copy(loading = false, data = data ?: snapshot.data,
                    error = response.error ?: if (data == null) SettingsError.REQUEST_FAILED else null,
                    refreshRequired = if (explicit && data != null) false else snapshot.refreshRequired || changed,
                    outcomes = outcomes, readbackPending = pending)
                emit()
            }
        }
        try {
            if (!current()) finish(SettingsResult(error = SettingsError.NOT_READY))
            else session.load(token.userId, active, ::finish)
        }
        catch (_: Exception) { finish(SettingsResult(error = SettingsError.REQUEST_FAILED)) }
    }

    private fun nativeActive(extra: AtomicBoolean? = null): () -> Boolean {
        val capturedOwner = owner
        val capturedLease = pageLease
        val capturedAlive = alive
        val capturedAllowed = allowedGate
        return { capturedAlive.get() && capturedAllowed.get() && capturedOwner.get() && (extra == null || extra.get()) && lease(capturedLease) }
    }

    private fun current() = alive.get() && owner.get() && allowed() && lease(pageLease)
    private fun allowed(): Boolean {
        val value = runCatching { isAllowed() }.getOrDefault(false)
        allowedGate.set(value)
        return value
    }
    private fun lease(value: () -> Boolean) = runCatching { value() }.getOrDefault(false)
    private fun clearPage() {
        owner.set(false)
        picker.set(false)
        readRequest++
        metadataRequest++
        pageEmojis.clear()
        snapshot = SettingsProfileColorsSnapshot(token = SettingsProfileColorsToken(generation, ++nextOwner))
    }
    private fun emit() {
        snapshot = snapshot.copy(revision = snapshot.revision + 1)
        publish(snapshot)
    }
}
