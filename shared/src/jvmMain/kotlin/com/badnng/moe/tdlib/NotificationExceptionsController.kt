package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import java.util.concurrent.atomic.AtomicBoolean

/** Page state lives on the Engine actor; a dispatched mutation keeps its terminal owner after close. */
internal class NotificationExceptionsController(
    private val generation: Long,
    private val session: TdNotificationExceptionsSession,
    private val enqueue: (() -> Unit) -> Unit,
    private val publish: (NotificationExceptionsSnapshot) -> Unit,
    private val isAllowed: () -> Boolean = { true },
    private val terminal: SettingsSpecialMutationBoundary = SettingsSpecialMutationBoundary(enqueue),
) {
    private var snapshot = NotificationExceptionsSnapshot(token = NotificationExceptionsToken(generation))
    private var nextOwner = 0L
    private var nextPlan = 0L
    private var revision = 0L
    private var editorRevision = 0L
    private var acceptedRevision = 0L
    private var nativeChanges = 0L
    private var listDirty = false
    private var owner = AtomicBoolean(false)
    private var page: () -> Boolean = { false }
    @Volatile private var retired = false
    private val reads = mutableSetOf<AtomicBoolean>()
    private var ids = emptyList<Long>()
    private var cursor = 0
    private var editorId: Long? = null
    private var mutation: Mutation? = null
    private val clearReadbackPending = mutableSetOf<Long>()
    internal var lastClearReceipts: Map<Long, NotificationSettingsWriteReceipt> = emptyMap()
        private set

    private class Mutation(val token: NotificationExceptionsToken, val active: () -> Boolean, val clearing: Boolean = false)

    init { session.observe { chatId -> enqueue { changed(chatId) } } }

    fun accepts(token: NotificationExceptionsToken) = !retired && owner.get() && snapshot.opened &&
        snapshot.token == token && allowed(page)

    fun open(scope: NotificationScope, active: () -> Boolean) {
        if (retired || !allowed(active)) return
        if (owner.get() && allowed(page) && snapshot.opened && snapshot.token.scope == scope) {
            if (mutation == null && snapshot.view == NotificationExceptionsView.LIST && snapshot.totalIds == null && reads.isEmpty()) loadList()
            return
        }
        retirePage()
        owner = AtomicBoolean(true); page = active
        snapshot = NotificationExceptionsSnapshot(token = NotificationExceptionsToken(generation, ++nextOwner, scope),
            opened = true, saving = mutation != null, editorRevision = editorRevision)
        emit()
        if (mutation == null) loadList()
    }

    fun close(token: NotificationExceptionsToken) {
        if (retired || token != snapshot.token) return
        retirePage()
        snapshot = NotificationExceptionsSnapshot(token = NotificationExceptionsToken(generation, ++nextOwner, token.scope),
            editorRevision = editorRevision)
        emit()
    }

    fun deactivate() {
        retirePage(); retired = true
        session.observe(null)
    }

    fun refresh(token: NotificationExceptionsToken) {
        if (!available(token) || snapshot.view != NotificationExceptionsView.LIST) return
        loadList()
    }

    fun more(token: NotificationExceptionsToken) {
        if (!available(token) || snapshot.view != NotificationExceptionsView.LIST || !snapshot.hasMore) return
        snapshot = snapshot.copy(loading = true, error = null); emit()
        loadBatch()
    }

    fun select(token: NotificationExceptionsToken) {
        if (!available(token) || snapshot.view == NotificationExceptionsView.SELECTOR) return
        cancelReads(); editorId = null
        snapshot = snapshot.copy(view = NotificationExceptionsView.SELECTOR, editor = null, editorError = null,
            clearPlan = null, query = "", candidates = emptyList())
        search(token, "", false)
    }

    fun search(token: NotificationExceptionsToken, query: String, remote: Boolean) {
        if (!accepts(token) || mutation != null || snapshot.preparingClear || snapshot.view != NotificationExceptionsView.SELECTOR) return
        cancelReads()
        if (query.length > 128) {
            snapshot = snapshot.copy(query = query, searching = false, candidates = emptyList(), searchError = SettingsError.INVALID_VALUE)
            emit(); return
        }
        snapshot = snapshot.copy(query = query, searching = true, candidates = emptyList(), searchError = null); emit()
        read({ active, result -> session.search(token.scope, query, remote && query.isNotBlank(), active, result) },
            SettingsResult<List<NotificationExceptionRow>>(error = SettingsError.REQUEST_FAILED)) { result ->
            val rows = result.value?.take(128)?.filter { it.chatId != 0L }?.distinctBy { it.chatId }?.map { row ->
                val peer = row.peer
                when {
                    row.error != null -> NotificationExceptionRow(row.chatId, error = row.error)
                    peer == null || !validPeer(peer, row.chatId, token.scope) -> NotificationExceptionRow(row.chatId, error = SettingsError.REQUEST_FAILED)
                    else -> row
                }
            }
            snapshot = snapshot.copy(searching = false, candidates = rows.orEmpty(),
                searchError = result.error ?: if (result.value == null) SettingsError.REQUEST_FAILED
                    else if (result.value.orEmpty().take(128).any { it.chatId == 0L }) SettingsError.INVALID_VALUE else null)
            emit()
        }
    }

    fun edit(token: NotificationExceptionsToken, chatId: Long) {
        if (!available(token) || chatId == 0L || (chatId !in ids && snapshot.candidates.none { it.chatId == chatId })) return
        cancelReads(); editorId = chatId
        snapshot = snapshot.copy(view = NotificationExceptionsView.EDITOR, editor = null, editorError = null,
            clearPlan = null, searching = false, loading = true)
        emit(); readEditor(chatId)
    }

    fun back(token: NotificationExceptionsToken) {
        if (!accepts(token) || mutation != null || snapshot.view == NotificationExceptionsView.LIST) return
        cancelReads(); editorId = null
        snapshot = snapshot.copy(view = NotificationExceptionsView.LIST, loading = false, searching = false,
            preparingClear = false, editor = null, editorError = null, clearPlan = null)
        if (listDirty || snapshot.refreshRequired) loadList() else emit()
    }

    fun reloadEditor(token: NotificationExceptionsToken) {
        val chatId = editorId ?: return
        if (!available(token) || snapshot.view != NotificationExceptionsView.EDITOR) return
        cancelReads(); snapshot = snapshot.copy(loading = true, editorError = null); emit(); readEditor(chatId)
    }

    fun save(token: NotificationExceptionsToken, expectedRevision: Long, edit: NotificationExceptionEdit) {
        if (!available(token) || snapshot.view != NotificationExceptionsView.EDITOR) return
        val current = snapshot.editor
        if (current != null && !current.editable) {
            snapshot = snapshot.copy(editorError = SettingsError.FORBIDDEN); emit(); return
        }
        if (current == null || current != edit.expected || expectedRevision != snapshot.editorRevision ||
            !validPeer(edit.expected, editorId ?: 0L, token.scope)) {
            snapshot = snapshot.copy(editorError = SettingsError.CONFLICT); emit(); return
        }
        val operation = Mutation(token, mutationAuthority())
        mutation = operation
        snapshot = snapshot.copy(saving = true, editorError = null); emit()
        write(edit, operation) { result ->
            if (result.locallyAccepted) acceptedRevision++
            if (accepts(token) && operation.active() && mutation === operation) {
                val actual = result.actual?.takeIf { validPeer(it, edit.expected.chatId, token.scope) }
                val success = result.error == null && actual != null && actual.editable
                if (success) {
                    listDirty = true
                    snapshot = snapshot.copy(editor = actual, editorRevision = ++editorRevision,
                        editorError = null, readbackPending = false, refreshRequired = false)
                    updateRow(actual!!)
                } else {
                    if (result.locallyAccepted) listDirty = true
                    snapshot = snapshot.copy(editorError = result.error ?: SettingsError.REQUEST_FAILED,
                        readbackPending = result.locallyAccepted || snapshot.readbackPending, refreshRequired = true)
                }
            }
            finishMutation(operation)
        }
    }

    fun prepareClear(token: NotificationExceptionsToken) {
        if (!available(token) || snapshot.view != NotificationExceptionsView.LIST) return
        cancelReads()
        clearReadbackPending.clear()
        snapshot = snapshot.copy(preparingClear = true, clearPlan = null, error = null); emit()
        read({ active, result -> session.list(token.scope, active, result) },
            SettingsResult<List<Long>>(error = SettingsError.REQUEST_FAILED)) { result ->
            val frozen = freeze(result)
            if (frozen == null) {
                snapshot = snapshot.copy(preparingClear = false, error = listError(result)); emit()
            } else prepareBatch(frozen, 0, mutableListOf(), mutableListOf())
        }
    }

    fun cancelClear(token: NotificationExceptionsToken) {
        if (!accepts(token) || mutation != null || !snapshot.preparingClear && snapshot.clearPlan == null) return
        cancelReads()
        snapshot = snapshot.copy(preparingClear = false, clearPlan = null); emit()
    }

    fun clear(token: NotificationExceptionsToken, planId: Long) {
        if (!available(token) || snapshot.view != NotificationExceptionsView.LIST) return
        val plan = snapshot.clearPlan?.takeIf { it.id == planId } ?: return
        val targets = if (plan.failures.isNotEmpty()) plan.targets.filter { it.chatId in plan.failures }
            else plan.targets.filterNot { it.chatId in plan.completed }
        if (targets.isEmpty() || targets.size > MAX_IDS) return
        val operation = Mutation(token, mutationAuthority(), clearing = true)
        mutation = operation
        snapshot = snapshot.copy(saving = true, error = null); emit()
        lastClearReceipts = plan.receipts + targets.associate { it.chatId to NotificationSettingsWriteReceipt.NOT_DISPATCHED }
        clearNext(operation, planId, targets, 0)
    }

    private fun loadList() {
        cancelReads(); ids = emptyList(); cursor = 0
        val scope = snapshot.token.scope
        val observed = nativeChanges
        snapshot = snapshot.copy(loading = true, rows = emptyList(), totalIds = null, hasMore = false,
            error = null, clearPlan = null, refreshRequired = false)
        emit()
        read({ active, result -> session.list(scope, active, result) },
            SettingsResult<List<Long>>(error = SettingsError.REQUEST_FAILED)) { result ->
            val frozen = freeze(result)
            if (frozen == null) {
                listDirty = true
                snapshot = snapshot.copy(loading = false, error = listError(result), refreshRequired = true); emit()
            } else {
                ids = frozen; cursor = 0
                listDirty = nativeChanges != observed
                snapshot = snapshot.copy(totalIds = ids.size, hasMore = ids.isNotEmpty(), refreshRequired = nativeChanges != observed)
                if (ids.isEmpty()) { snapshot = snapshot.copy(loading = false); emit() } else loadBatch()
            }
        }
    }

    private fun loadBatch() {
        val scope = snapshot.token.scope
        val batch = ids.subList(cursor, minOf(cursor + BATCH, ids.size)).toList()
        read({ active, result -> session.resolve(scope, batch, active, result) },
            SettingsResult<List<NotificationExceptionRow>>(error = SettingsError.REQUEST_FAILED)) { result ->
            cursor += batch.size
            val rows = verifiedRows(scope, batch, result)
            snapshot = snapshot.copy(loading = false, rows = snapshot.rows + rows.filter { it.peer?.values?.hasNewMessageException != false },
                hasMore = cursor < ids.size, error = result.error)
            emit()
        }
    }

    private fun readEditor(chatId: Long) {
        val scope = snapshot.token.scope
        read({ active, result -> session.read(scope, chatId, active, result) },
            SettingsResult<NotificationExceptionPeer>(error = SettingsError.REQUEST_FAILED)) { result ->
            val actual = result.value?.takeIf { validPeer(it, chatId, scope) }
            if (result.error == null && actual != null) {
                snapshot = snapshot.copy(loading = false, editor = actual, editorRevision = ++editorRevision,
                    editorError = null, readbackPending = false, refreshRequired = false)
            } else snapshot = snapshot.copy(loading = false, editorError = result.error ?: SettingsError.REQUEST_FAILED, refreshRequired = true)
            emit()
        }
    }

    private fun prepareBatch(frozen: List<Long>, offset: Int, targets: MutableList<NotificationExceptionPeer>, unresolved: MutableList<NotificationExceptionRow>) {
        if (offset >= frozen.size) {
            snapshot = snapshot.copy(preparingClear = false,
                clearPlan = NotificationExceptionsClearPlan(++nextPlan, targets.toList(), unresolved.toList()))
            emit(); return
        }
        val scope = snapshot.token.scope
        val batch = frozen.subList(offset, minOf(offset + BATCH, frozen.size)).toList()
        read({ active, result -> session.resolve(scope, batch, active, result) },
            SettingsResult<List<NotificationExceptionRow>>(error = SettingsError.REQUEST_FAILED)) { result ->
            verifiedRows(scope, batch, result).forEach { row ->
                val peer = row.peer
                when {
                    row.error != null || peer == null -> unresolved += row
                    !peer.editable -> unresolved += NotificationExceptionRow(row.chatId, error = SettingsError.FORBIDDEN)
                    peer.values.hasNewMessageException -> targets += peer
                }
            }
            prepareBatch(frozen, offset + batch.size, targets, unresolved)
        }
    }

    private fun clearNext(operation: Mutation, planId: Long, targets: List<NotificationExceptionPeer>, index: Int) {
        if (mutation !== operation) return
        if (index >= targets.size) { finishMutation(operation); return }
        if (!accepts(operation.token) || !operation.active() || snapshot.clearPlan?.id != planId) {
            abortClear(operation, planId, targets, index); return
        }
        val peer = targets[index]
        // This item is in flight: absence means awaiting a receipt, never a claim of non-dispatch.
        lastClearReceipts = lastClearReceipts - peer.chatId
        val edit = NotificationExceptionEdit(peer, peer.values.newMessageDefaults(), NotificationExceptionField.entries.toSet())
        write(edit, operation) { result ->
            lastClearReceipts = lastClearReceipts + (peer.chatId to result.receipt)
            if (result.locallyAccepted) acceptedRevision++
            if (!accepts(operation.token) || !operation.active() || snapshot.clearPlan?.id != planId) {
                abortClear(operation, planId, targets, index, result.locallyAccepted); return@write
            }
            val actual = result.actual?.takeIf { validPeer(it, peer.chatId, operation.token.scope) }
            // A retry may discover that its earlier accepted Set already produced defaults.
            val success = actual != null && !actual.values.hasNewMessageException
            val plan = snapshot.clearPlan!!
            val completed = if (success) plan.completed + peer.chatId else plan.completed
            val failures = if (success) plan.failures - peer.chatId else plan.failures +
                (peer.chatId to (result.error ?: SettingsError.REQUEST_FAILED))
            if (success) clearReadbackPending.remove(peer.chatId)
            else if (result.locallyAccepted || result.receipt == NotificationSettingsWriteReceipt.INDETERMINATE) clearReadbackPending += peer.chatId
            if (actual != null) updateRow(actual)
            listDirty = true
            snapshot = snapshot.copy(clearPlan = plan.copy(completed = completed, failures = failures, receipts = lastClearReceipts),
                readbackPending = clearReadbackPending.isNotEmpty(),
                refreshRequired = true, error = failures.values.firstOrNull())
            emit()
            clearNext(operation, planId, targets, index + 1)
        }
    }

    private fun abortClear(operation: Mutation, planId: Long, targets: List<NotificationExceptionPeer>, index: Int, accepted: Boolean = false) {
        val plan = snapshot.clearPlan?.takeIf { it.id == planId }
        if (!retired && snapshot.token == operation.token && plan != null) {
            val unfinished = targets.subList(index, targets.size).filterNot { it.chatId in plan.completed }
                .associate { it.chatId to SettingsError.NOT_READY }
            if (accepted) clearReadbackPending += targets[index].chatId
            snapshot = snapshot.copy(clearPlan = plan.copy(failures = plan.failures + unfinished, receipts = lastClearReceipts),
                readbackPending = clearReadbackPending.isNotEmpty(), error = SettingsError.NOT_READY, refreshRequired = true)
        }
        finishMutation(operation)
    }

    private fun write(edit: NotificationExceptionEdit, operation: Mutation, completed: (NotificationExceptionWriteResult) -> Unit) {
        val once = AtomicBoolean()
        val receive: (NotificationExceptionWriteResult) -> Unit = { result ->
            if (result.receipt == NotificationSettingsWriteReceipt.INDETERMINATE) {
                if (!once.get()) terminal.enqueue {
                    if (mutation === operation) {
                        if (operation.clearing) lastClearReceipts = lastClearReceipts +
                            (edit.expected.chatId to NotificationSettingsWriteReceipt.INDETERMINATE)
                        if (accepts(operation.token) && operation.active()) {
                            snapshot = snapshot.copy(readbackPending = true, refreshRequired = true,
                                error = result.error ?: SettingsError.REQUEST_FAILED)
                            emit()
                        }
                    }
                }
            } else if (once.compareAndSet(false, true)) terminal.enqueue { completed(result) }
        }
        if (!operation.active()) { receive(NotificationExceptionWriteResult(error = SettingsError.NOT_READY)); return }
        try { session.save(edit, operation.active, receive) }
        catch (_: Exception) { receive(NotificationExceptionWriteResult(error = SettingsError.REQUEST_FAILED)) }
    }

    private fun finishMutation(operation: Mutation) {
        if (mutation !== operation) return
        mutation = null
        if (retired || !snapshot.opened) return
        if (retireInactivePage(snapshot.token)) return
        snapshot = snapshot.copy(saving = false)
        if (snapshot.token != operation.token && accepts(snapshot.token)) {
            val current = snapshot.token
            terminal.readback({ accepts(current) && mutation == null }) { loadList() }
        } else emit()
    }

    private fun updateRow(peer: NotificationExceptionPeer) {
        // Only a later list/membership read removes a row; plan completion is a separate fact.
        snapshot = snapshot.copy(rows = snapshot.rows.map { if (it.chatId == peer.chatId) NotificationExceptionRow(peer.chatId, peer) else it })
    }

    private fun verifiedRows(scope: NotificationScope, requested: List<Long>, result: SettingsResult<List<NotificationExceptionRow>>): List<NotificationExceptionRow> {
        val returned = result.value.orEmpty().filter { it.chatId in requested }.associateBy { it.chatId }
        return requested.map { id ->
            val row = returned[id]
            val peer = row?.peer
            when {
                result.error != null -> NotificationExceptionRow(id, error = result.error)
                row == null -> NotificationExceptionRow(id, error = SettingsError.REQUEST_FAILED)
                row.error != null -> NotificationExceptionRow(id, error = row.error)
                peer == null || !validPeer(peer, id, scope) -> NotificationExceptionRow(id, error = SettingsError.REQUEST_FAILED)
                else -> NotificationExceptionRow(id, peer)
            }
        }
    }

    private fun freeze(result: SettingsResult<List<Long>>): List<Long>? = result.value?.takeIf {
        result.error == null && it.size <= MAX_IDS && it.none { id -> id == 0L }
    }?.distinct()

    private fun listError(result: SettingsResult<List<Long>>) = result.error ?: if (result.value == null)
        SettingsError.REQUEST_FAILED else SettingsError.INVALID_VALUE

    private fun validPeer(peer: NotificationExceptionPeer, chatId: Long, scope: NotificationScope) =
        chatId != 0L && peer.chatId == chatId && peer.scope == scope

    private fun available(token: NotificationExceptionsToken) = accepts(token) && mutation == null &&
        !snapshot.loading && !snapshot.preparingClear && !snapshot.searching

    private fun allowed(active: () -> Boolean) = !retired && runCatching { isAllowed() && active() }.getOrDefault(false)
    private fun mutationAuthority(): () -> Boolean {
        val active = currentPage()
        val authority = terminal.captureAuthority()
        return { active() && authority() }
    }
    private fun currentPage(): () -> Boolean {
        val local = owner; val visible = page
        return { local.get() && allowed(visible) }
    }

    private fun <T> read(perform: (() -> Boolean, (T) -> Unit) -> Unit, failure: T, completed: (T) -> Unit) {
        val token = snapshot.token
        val pageActive = currentPage()
        val lease = AtomicBoolean(true)
        val once = AtomicBoolean()
        reads += lease
        val active = { lease.get() && pageActive() }
        val receive: (T) -> Unit = { result ->
            if (once.compareAndSet(false, true)) enqueue {
                reads.remove(lease)
                val current = lease.get() && pageActive() && accepts(token)
                lease.set(false)
                if (current) completed(result) else retireInactivePage(token)
            }
        }
        if (!active()) { receive(failure); return }
        try { perform(active, receive) } catch (_: Exception) { receive(failure) }
    }

    private fun changed(chatId: Long?) {
        if (retired) return
        nativeChanges++
        listDirty = true
        if (accepts(snapshot.token)) {
            if (snapshot.view != NotificationExceptionsView.EDITOR || chatId == null || chatId == editorId) {
                snapshot = snapshot.copy(refreshRequired = true); emit()
            }
        }
    }

    private fun cancelReads() { reads.forEach { it.set(false) }; reads.clear() }
    private fun retireInactivePage(token: NotificationExceptionsToken): Boolean {
        if (!retired && snapshot.opened && snapshot.token == token && !runCatching(page).getOrDefault(false)) {
            close(token); return true
        }
        return false
    }
    private fun retirePage() {
        owner.set(false); page = { false }; cancelReads()
        ids = emptyList(); cursor = 0; editorId = null
        listDirty = false
        clearReadbackPending.clear()
    }
    private fun emit() {
        if (retired) return
        snapshot = snapshot.copy(revision = ++revision, acceptedRevision = acceptedRevision)
        publish(snapshot)
    }

    private companion object {
        const val BATCH = 32
        const val MAX_IDS = 32_768
    }
}
