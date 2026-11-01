package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One visible management owner, independent of chat selection and of the parent privacy draft. */
internal class SettingsSecurityController(
    private val generation: Long, private val session: TdSettingsSession,
    private val enqueue: (() -> Unit) -> Unit, private val publish: (SettingsSecuritySnapshot) -> Unit,
    private val terminal: SettingsSpecialMutationBoundary,
) {
    // Preserve the existing trailing-lambda constructor while allowing an explicit terminal boundary.
    constructor(generation: Long, session: TdSettingsSession, enqueue: (() -> Unit) -> Unit,
        publish: (SettingsSecuritySnapshot) -> Unit,
    ) : this(generation, session, enqueue, publish, SettingsSpecialMutationBoundary(enqueue))

    private var snapshot = SettingsSecuritySnapshot(token = SettingsSecurityToken(generation))
    private var ownerId = 0L
    private var requestId = 0L
    private var ownerActive = AtomicBoolean(false)
    private var retired = false
    private var blockedOffset = 0
    private var blockedDirty = false
    private var blockedCursorInvalid = true
    private var pickerOwnerId = 0L
    private var pickerRequestId = 0L
    private var pickerReadActive = AtomicBoolean(false)
    private var mutationId = 0L
    private var physicalMutation: Long? = null
    private var nativeMutationContinuation = AtomicBoolean(false)
    private var retireMutationUi: (() -> Unit)? = null
    internal var lastMutationReceipt: SettingsSpecialWriteReceipt? = null
        private set

    init { session.observeSecurityChanges { enqueue { invalidateBlocked() } } }

    fun open(section: SettingsSecuritySection) {
        if (retired || !terminal.captureAuthority()()) return
        pickerReadActive.set(false)
        retireMutationUi?.invoke()
        retireMutationUi = null
        session.retirePasswordOwner(snapshot.token)
        ownerActive.set(false)
        ownerActive = AtomicBoolean(true)
        snapshot = SettingsSecuritySnapshot(SettingsSecurityToken(generation, ++ownerId), section, loading = true)
        blockedOffset = 0
        blockedDirty = false
        blockedCursorInvalid = true
        emit()
        read(reset = true)
    }

    fun accepts(token: SettingsSecurityToken) = !retired && ownerActive.get() && snapshot.section != null && snapshot.token == token

    fun close(token: SettingsSecurityToken) {
        if (!accepts(token)) return
        pickerReadActive.set(false)
        retireMutationUi?.invoke()
        retireMutationUi = null
        session.retirePasswordOwner(token)
        ownerActive.set(false)
        requestId++
        snapshot = SettingsSecuritySnapshot(token = SettingsSecurityToken(generation, ++ownerId))
        blockedOffset = 0
        blockedDirty = false
        blockedCursorInvalid = true
        emit()
    }

    fun deactivate() {
        pickerReadActive.set(false)
        retireMutationUi?.invoke()
        retireMutationUi = null
        session.retirePasswordOwner(snapshot.token)
        ownerActive.set(false)
        retired = true
        session.observeSecurityChanges(null)
        requestId++
        snapshot = SettingsSecuritySnapshot(token = SettingsSecurityToken(generation, ++ownerId))
        emit()
    }

    fun refresh(token: SettingsSecurityToken) {
        if (!accepts(token) || snapshot.busy || snapshot.loading || snapshot.blockedLoadingMore) return
        snapshot = snapshot.copy(loading = true, error = null)
        emit()
        read(reset = true)
    }

    fun password(token: SettingsSecurityToken, command: SettingsPasswordCommand,
        completed: (SettingsResult<SettingsPasswordOutcome>) -> Unit,
    ) {
        if (!canMutate(token, SettingsSecuritySection.PASSWORD) || snapshot.password == null) {
            completed(SettingsResult(error = unavailableError(token))); return
        }
        snapshot = snapshot.copy(busy = true, error = null)
        emit()
        val lease = captureMutationOwner()
        mutating({ session.passwordForOwner(token, command, lease, it) }) { response ->
            snapshot = snapshot.copy(busy = false, error = response.error,
                password = response.value?.state ?: snapshot.password)
            emit()
            completed(response)
        }
    }

    fun disconnect(token: SettingsSecurityToken, target: SettingsWebsite, blockBot: Boolean,
        completed: (SettingsResult<SettingsWebsiteDisconnectOutcome>) -> Unit,
    ) {
        if (!canMutate(token, SettingsSecuritySection.WEBSITES) || snapshot.websites.none {
                it.id == target.id && it.domain == target.domain && it.botUserId == target.botUserId
            }) { completed(SettingsResult(error = unavailableError(token))); return }
        snapshot = snapshot.copy(busy = true, error = null)
        emit()
        val lease = captureMutationOwner()
        mutating({ session.disconnectWebsite(target, blockBot, lease, it) }) { response ->
            if (response.value?.disconnected == true) refreshWebsites(response, completed)
            else { snapshot = snapshot.copy(busy = false, error = response.error); emit(); completed(response) }
        }
    }

    fun disconnectAll(token: SettingsSecurityToken, completed: (SettingsResult<Unit>) -> Unit) {
        if (!canMutate(token, SettingsSecuritySection.WEBSITES) || snapshot.websites.isEmpty()) {
            completed(SettingsResult(error = unavailableError(token))); return
        }
        snapshot = snapshot.copy(busy = true, error = null)
        emit()
        val lease = captureMutationOwner()
        mutating({ session.disconnectAllWebsites(lease, it) }) { response ->
            if (response.value != null) refreshWebsites(response, completed)
            else { snapshot = snapshot.copy(busy = false, error = response.error); emit(); completed(response) }
        }
    }

    private fun <T> refreshWebsites(original: SettingsResult<T>, completed: (SettingsResult<T>) -> Unit) {
        val lease = captureOwner()
        safely({ session.websites(lease, it) }) { refreshed ->
            snapshot = snapshot.copy(busy = false, error = original.error ?: refreshed.error,
                websites = refreshed.value?.entries ?: snapshot.websites,
                websitesTotalCount = refreshed.value?.totalCount ?: snapshot.websitesTotalCount)
            emit()
            completed(SettingsResult(original.value, original.error ?: refreshed.error))
        }
    }

    fun moreBlocked(token: SettingsSecurityToken) {
        if (!canMutate(token, SettingsSecuritySection.BLOCKED) ||
            !blockedCursorInvalid && (snapshot.blockedEnd || snapshot.blockedLimitReached)) return
        snapshot = snapshot.copy(blockedLoadingMore = !blockedCursorInvalid, loading = blockedCursorInvalid, error = null)
        emit()
        read(reset = blockedCursorInvalid)
    }

    fun openBlockedPicker(security: SettingsSecurityToken) {
        if (!canMutate(security, SettingsSecuritySection.BLOCKED) || snapshot.blockedPicker != null) return
        pickerReadActive.set(false)
        val picker = SettingsBlockedPickerToken(security, ++pickerOwnerId)
        snapshot = snapshot.copy(blockedPicker = SettingsBlockedPickerState(picker,
            SettingsBlockedPeerQuery(SettingsBlockedPeerSource.CONTACTS)))
        searchBlockedPicker(picker, snapshot.blockedPicker!!.query)
    }

    fun closeBlockedPicker(picker: SettingsBlockedPickerToken) {
        if (!acceptsPicker(picker)) return
        // The picker owns reads only. An admitted mutation retains the parent security lease.
        pickerReadActive.set(false)
        pickerRequestId++
        snapshot = snapshot.copy(blockedPicker = null)
        emit()
    }

    fun searchBlockedPicker(picker: SettingsBlockedPickerToken, query: SettingsBlockedPeerQuery) {
        val current = snapshot.blockedPicker ?: return
        if (!acceptsPicker(picker) || !terminal.captureAuthority()() || snapshot.busy || physicalMutation != null ||
            query.revision != current.query.revision) return
        val reset = query.offset == 0
        if (query.limit !in 1..100 || query.query.length > 256 || query.offset < 0 ||
            query.source == SettingsBlockedPeerSource.SERVER && query.query.isBlank() ||
            !reset && (current.loading || current.end || current.limitReached || query.offset != current.nextOffset ||
                query.source != current.query.source || query.query != current.query.query || query.limit != current.query.limit)) return
        pickerReadActive.set(false)
        val readActive = AtomicBoolean(true)
        pickerReadActive = readActive
        val parent = captureOwner()
        val authority = terminal.captureAuthority()
        val request = ++pickerRequestId
        val admitted = query.copy(revision = current.query.revision + 1)
        snapshot = snapshot.copy(blockedPicker = current.copy(query = admitted,
            entries = emptyList(), loading = true, end = false,
            nextOffset = if (reset) 0 else current.nextOffset, limitReached = false, error = null))
        emit()
        val completed = AtomicBoolean()
        val lease = { readActive.get() && parent() && authority() }
        fun finish(response: SettingsResult<SettingsBlockedCandidates>) {
            if (!completed.compareAndSet(false, true)) return
            enqueue {
                if (!acceptsPicker(picker) || !lease() || request != pickerRequestId) return@enqueue
                val state = snapshot.blockedPicker ?: return@enqueue
                val page = response.value
                val valid = page != null && page.entries.size <= query.limit && page.entries.all { it.userId > 0 } &&
                    page.nextOffset >= query.offset && (page.end || page.limitReached || page.nextOffset > query.offset)
                snapshot = snapshot.copy(blockedPicker = if (!valid) state.copy(loading = false,
                    error = response.error ?: SettingsError.REQUEST_FAILED)
                else state.copy(loading = false, entries = page!!.entries.distinctBy { it.userId },
                    nextOffset = page.nextOffset, end = page.end, limitReached = page.limitReached, error = null))
                emit()
            }
        }
        try { session.blockedCandidates(admitted, lease, ::finish) }
        catch (_: Exception) { finish(SettingsResult(error = SettingsError.REQUEST_FAILED)) }
    }

    fun blockPickerUser(picker: SettingsBlockedPickerToken, userId: Long, completed: (SettingsResult<Unit>) -> Unit) {
        val state = snapshot.blockedPicker
        if (!acceptsPicker(picker) || !canMutate(picker.security, SettingsSecuritySection.BLOCKED) ||
            state == null || state.loading || state.entries.none { it.userId == userId && !it.blocked } ||
            snapshot.blocked.any { !it.isChat && it.id == userId }) {
            completed(SettingsResult(error = unavailableError(picker.security))); return
        }
        pickerReadActive.set(false)
        snapshot = snapshot.copy(busy = true, error = null)
        emit()
        val lease = captureMutationOwner()
        mutating({ session.blockSecurity(userId, lease, it) }) { response ->
            if (response.value == null) {
                snapshot = snapshot.copy(busy = false, error = response.error)
                emit(); completed(response)
            } else {
                blockedOffset = 0
                blockedCursorInvalid = true
                safely({ session.blockedPage(0, lease, it) }) { first ->
                    applyBlocked(first, reset = true)
                    completed(SettingsResult(response.value, first.error))
                }
            }
        }
    }

    private fun acceptsPicker(picker: SettingsBlockedPickerToken) = accepts(picker.security) &&
        snapshot.section == SettingsSecuritySection.BLOCKED && snapshot.blockedPicker?.token == picker

    fun unblock(token: SettingsSecurityToken, sender: SettingsBlockedSender, completed: (SettingsResult<Unit>) -> Unit) {
        if (!canMutate(token, SettingsSecuritySection.BLOCKED) || snapshot.blocked.none { it.id == sender.id && it.isChat == sender.isChat }) {
            completed(SettingsResult(error = unavailableError(token))); return
        }
        snapshot = snapshot.copy(busy = true, error = null)
        emit()
        val lease = captureMutationOwner()
        mutating({ session.unblockSecurity(sender, lease, it) }) { response ->
            if (response.value == null) {
                snapshot = snapshot.copy(busy = false, error = response.error); emit(); completed(response)
            } else {
                blockedOffset = 0
                blockedCursorInvalid = true
                safely({ session.blockedPage(0, lease, it) }) { first ->
                    applyBlocked(first, reset = true)
                    completed(SettingsResult(response.value, first.error))
                }
            }
        }
    }

    fun terminate(token: SettingsSecurityToken, id: Long, completed: (SettingsResult<Unit>) -> Unit) {
        if (!canMutate(token, SettingsSecuritySection.SESSIONS) || snapshot.sessions.none { it.id == id && !it.isCurrent }) {
            completed(SettingsResult(error = unavailableError(token))); return
        }
        snapshot = snapshot.copy(busy = true, error = null)
        emit()
        val lease = captureMutationOwner()
        mutating({ session.terminateSecuritySession(id, lease, it) }) { response ->
            if (response.value == null) {
                snapshot = snapshot.copy(busy = false, error = response.error); emit(); completed(response)
            } else safely({ session.securitySessionsData(lease, it) }) { refreshed ->
                snapshot = snapshot.copy(busy = false, sessions = refreshed.value?.entries ?: snapshot.sessions,
                    inactiveSessionTtlDays = refreshed.value?.inactiveTtlDays ?: snapshot.inactiveSessionTtlDays,
                    sessionsRefreshRequired = refreshed.value == null, error = refreshed.error)
                emit(); completed(SettingsResult(response.value, refreshed.error))
            }
        }
    }

    fun terminateOthers(token: SettingsSecurityToken, expected: List<SettingsSession>, completed: (SettingsResult<Unit>) -> Unit) {
        if (!canMutate(token, SettingsSecuritySection.SESSIONS)) {
            completed(SettingsResult(error = unavailableError(token))); return
        }
        if (expected.none { !it.isCurrent } || expected.count { it.isCurrent } != 1 || !sameSettingsDevices(expected, snapshot.sessions)) {
            completed(SettingsResult(error = SettingsError.CONFLICT)); return
        }
        val frozen = expected.toList()
        mutateSessions(token, { lease, reply -> session.terminateOtherSecuritySessions(frozen, lease, reply) }, completed)
    }

    fun inactiveTtl(token: SettingsSecurityToken, expected: Int, desired: Int, completed: (SettingsResult<Unit>) -> Unit) {
        if (!canMutate(token, SettingsSecuritySection.SESSIONS)) {
            completed(SettingsResult(error = unavailableError(token))); return
        }
        if (snapshot.inactiveSessionTtlDays != expected) { completed(SettingsResult(error = SettingsError.CONFLICT)); return }
        if (expected !in 1..366 || desired !in 1..366) { completed(SettingsResult(error = SettingsError.INVALID_VALUE)); return }
        mutateSessions(token, { lease, reply -> session.setInactiveSessionTtl(expected, desired, lease, reply) }, completed)
    }

    private fun mutateSessions(token: SettingsSecurityToken,
        submit: (() -> Boolean, (SettingsResult<Unit>) -> Unit) -> Unit, completed: (SettingsResult<Unit>) -> Unit,
    ) {
        if (!canMutate(token, SettingsSecuritySection.SESSIONS)) { completed(SettingsResult(error = unavailableError(token))); return }
        snapshot = snapshot.copy(busy = true, error = null)
        emit()
        val lease = captureMutationOwner()
        mutating({ submit(lease, it) }) { response ->
            if (response.value == null) {
                snapshot = snapshot.copy(busy = false, error = response.error,
                    sessionsRefreshRequired = response.error == SettingsError.CONFLICT); emit(); completed(response)
            } else safely({ session.securitySessionsData(lease, it) }) { refreshed ->
                snapshot = snapshot.copy(busy = false, sessions = refreshed.value?.entries ?: snapshot.sessions,
                    inactiveSessionTtlDays = refreshed.value?.inactiveTtlDays ?: snapshot.inactiveSessionTtlDays,
                    sessionsRefreshRequired = refreshed.value == null, error = refreshed.error)
                emit(); completed(SettingsResult(Unit, refreshed.error))
            }
        }
    }

    fun defaultAutoDelete(token: SettingsSecurityToken, expected: Int, desired: Int, completed: (SettingsResult<Unit>) -> Unit) {
        if (!canMutate(token, SettingsSecuritySection.AUTO_DELETE)) {
            completed(SettingsResult(error = unavailableError(token))); return
        }
        if (snapshot.defaultMessageAutoDeleteSeconds != expected) { completed(SettingsResult(error = SettingsError.CONFLICT)); return }
        if (expected !in 0..365 * 86400 || desired !in 0..365 * 86400 || expected % 86400 != 0 || desired % 86400 != 0) {
            completed(SettingsResult(error = SettingsError.INVALID_VALUE)); return
        }
        snapshot = snapshot.copy(busy = true, error = null)
        emit()
        val lease = captureMutationOwner()
        mutating({ session.setDefaultMessageAutoDeleteTime(expected, desired, lease, it) }) { response ->
            if (response.value == null) {
                snapshot = snapshot.copy(busy = false, error = response.error,
                    defaultAutoDeleteRefreshRequired = response.error == SettingsError.CONFLICT); emit(); completed(response)
            } else safely({ session.defaultMessageAutoDeleteTime(lease, it) }) { refreshed ->
                snapshot = snapshot.copy(busy = false, defaultMessageAutoDeleteSeconds = refreshed.value ?: snapshot.defaultMessageAutoDeleteSeconds,
                    defaultAutoDeleteRefreshRequired = refreshed.value == null, error = refreshed.error)
                emit(); completed(SettingsResult(Unit, refreshed.error))
            }
        }
    }

    private fun canMutate(token: SettingsSecurityToken, section: SettingsSecuritySection) = accepts(token) && terminal.captureAuthority()() &&
        snapshot.section == section && physicalMutation == null && !snapshot.loading && !snapshot.busy && !snapshot.blockedLoadingMore &&
            (section != SettingsSecuritySection.SESSIONS || !snapshot.sessionsRefreshRequired) &&
            (section != SettingsSecuritySection.AUTO_DELETE || !snapshot.defaultAutoDeleteRefreshRequired)

    private fun read(reset: Boolean) {
        val lease = captureOwner()
        when (snapshot.section) {
            SettingsSecuritySection.PASSWORD -> safely({ session.passwordState(lease, it) }) { response ->
                snapshot = snapshot.copy(loading = false, password = response.value ?: snapshot.password, error = response.error)
                emit()
            }
            SettingsSecuritySection.WEBSITES -> safely({ session.websites(lease, it) }) { response ->
                snapshot = snapshot.copy(loading = false, websites = response.value?.entries ?: snapshot.websites,
                    websitesTotalCount = response.value?.totalCount ?: snapshot.websitesTotalCount, error = response.error)
                emit()
            }
            SettingsSecuritySection.BLOCKED -> {
                if (reset) blockedCursorInvalid = true
                val offset = if (reset) 0 else blockedOffset
                safely({ session.blockedPage(offset, lease, it) }) { response -> applyBlocked(response, reset) }
            }
            SettingsSecuritySection.SESSIONS -> safely({ session.securitySessionsData(lease, it) }) { response ->
                snapshot = snapshot.copy(loading = false, sessions = response.value?.entries ?: snapshot.sessions,
                    inactiveSessionTtlDays = response.value?.inactiveTtlDays ?: snapshot.inactiveSessionTtlDays,
                    sessionsRefreshRequired = response.value == null, error = response.error)
                emit()
            }
            SettingsSecuritySection.AUTO_DELETE -> safely({ session.defaultMessageAutoDeleteTime(lease, it) }) { response ->
                snapshot = snapshot.copy(loading = false, defaultMessageAutoDeleteSeconds = response.value ?: snapshot.defaultMessageAutoDeleteSeconds,
                    defaultAutoDeleteRefreshRequired = response.value == null, error = response.error)
                emit()
            }
            null -> Unit
        }
    }

    private fun applyBlocked(response: SettingsResult<SettingsBlockedPage>, reset: Boolean) {
        val page = response.value
        if (page == null) snapshot = snapshot.copy(loading = false, busy = false, blockedLoadingMore = false, error = response.error)
        else {
            blockedOffset = page.nextOffset
            if (reset) blockedCursorInvalid = false
            val joined = (if (reset) page.entries else snapshot.blocked + page.entries).distinctBy { it.isChat to it.id }
            snapshot = snapshot.copy(loading = false, busy = false, blockedLoadingMore = false, error = null,
                blocked = joined.take(MAX_BLOCKED_ENTRIES), blockedTotal = page.totalCount,
                blockedEnd = page.end, blockedLimitReached = !page.end && joined.size >= MAX_BLOCKED_ENTRIES)
        }
        if (blockedDirty && snapshot.section == SettingsSecuritySection.BLOCKED && ownerActive.get()) {
            blockedDirty = false
            blockedCursorInvalid = true
            snapshot = snapshot.copy(loading = true, error = null)
            emit()
            read(reset = true)
        } else emit()
    }

    private fun invalidateBlocked() {
        if (retired || !ownerActive.get() || snapshot.section != SettingsSecuritySection.BLOCKED) return
        blockedDirty = true
        blockedCursorInvalid = true
        if (snapshot.loading || snapshot.busy || snapshot.blockedLoadingMore) return
        blockedDirty = false
        snapshot = snapshot.copy(loading = true, error = null)
        emit()
        read(reset = true)
    }

    private fun <T> safely(submit: ((SettingsResult<T>) -> Unit) -> Unit, receive: (SettingsResult<T>) -> Unit) {
        val token = snapshot.token
        val lease = captureOwner()
        val authority = terminal.captureAuthority()
        val revision = ++requestId
        val completed = AtomicBoolean()
        fun finish(response: SettingsResult<T>) {
            if (!completed.compareAndSet(false, true)) return
            enqueue { if (accepts(token) && lease() && authority() && revision == requestId) receive(response) }
        }
        try { submit(::finish) } catch (_: Exception) { finish(SettingsResult(error = SettingsError.REQUEST_FAILED)) }
    }

    /** A physical result settles the original owner, independently of its retired form continuation. */
    private fun <T> mutating(submit: ((SettingsResult<T>) -> Unit) -> Unit, receive: (SettingsResult<T>) -> Unit) {
        val token = snapshot.token
        val lease = captureOwner()
        val authority = terminal.captureAuthority()
        val id = ++mutationId
        val nativeContinuation = nativeMutationContinuation
        physicalMutation = id
        lastMutationReceipt = null
        val continuation = AtomicReference<((SettingsResult<T>) -> Unit)?>(receive)
        retireMutationUi = { continuation.set(null) }
        val settled = AtomicBoolean()
        fun finish(response: SettingsResult<T>) {
            // A missing receipt means no marked physical write produced this result (validation/preflight).
            val receipt = SettingsSpecialMutationContext.receipt() ?: SettingsSpecialWriteReceipt(SettingsSpecialWriteOutcome.NOT_DISPATCHED)
            val uncertain = receipt.outcome == SettingsSpecialWriteOutcome.INDETERMINATE
            if ((!uncertain && !settled.compareAndSet(false, true)) || (uncertain && settled.get())) return
            if (uncertain) nativeContinuation.set(false)
            terminal.enqueue {
                if (physicalMutation != id) return@enqueue
                lastMutationReceipt = receipt
                // A send-entry failure cannot prove native completion. Keep its physical owner.
                if (uncertain) {
                    val current = continuation.getAndSet(null)
                    retireMutationUi = null
                    if (accepts(token) && lease() && authority()) {
                        current?.invoke(SettingsResult(error = SettingsError.RESULT_UNKNOWN))
                    }
                    return@enqueue
                }
                physicalMutation = null
                retireMutationUi = null
                val current = continuation.getAndSet(null)
                if (accepts(token) && lease() && authority()) current?.invoke(response)
                else revalidateNewOwner(token)
            }
        }
        try { submit(::finish) } catch (_: Exception) {
            // An unmarked throw cannot establish whether a fake/foreign implementation dispatched.
            nativeContinuation.set(false)
            terminal.enqueue {
                if (physicalMutation == id) {
                    lastMutationReceipt = SettingsSpecialWriteReceipt(SettingsSpecialWriteOutcome.INDETERMINATE)
                    val current = continuation.getAndSet(null)
                    retireMutationUi = null
                    if (accepts(token) && lease() && authority()) {
                        current?.invoke(SettingsResult(error = SettingsError.RESULT_UNKNOWN))
                    }
                }
            }
        }
    }
    private fun unavailableError(token: SettingsSecurityToken) =
        if (accepts(token) && terminal.captureAuthority()() && physicalMutation != null &&
            lastMutationReceipt?.outcome == SettingsSpecialWriteOutcome.INDETERMINATE) SettingsError.RESULT_UNKNOWN
        else SettingsError.NOT_READY

    private fun revalidateNewOwner(original: SettingsSecurityToken) {
        val current = snapshot.token
        val lease = ownerActive
        if (retired || current == original || snapshot.section == null || snapshot.loading) return
        terminal.readback({ !retired && accepts(current) && lease.get() }) {
            if (snapshot.loading || physicalMutation != null) return@readback
            snapshot = snapshot.copy(loading = true, error = null)
            emit()
            read(reset = true)
        }
    }
    private fun captureOwner(): () -> Boolean {
        val lease = ownerActive
        val authority = terminal.captureAuthority()
        return { lease.get() && authority() && !retired }
    }
    private fun captureMutationOwner(): () -> Boolean {
        val owner = captureOwner()
        val continuation = AtomicBoolean(true)
        nativeMutationContinuation = continuation
        return { continuation.get() && owner() }
    }
    private fun emit() {
        if (snapshot.section != null && physicalMutation != null &&
            lastMutationReceipt?.outcome == SettingsSpecialWriteOutcome.INDETERMINATE) {
            snapshot = snapshot.copy(error = SettingsError.RESULT_UNKNOWN)
        }
        publish(snapshot)
    }
    private companion object { const val MAX_BLOCKED_ENTRIES = 10_000 }
}
