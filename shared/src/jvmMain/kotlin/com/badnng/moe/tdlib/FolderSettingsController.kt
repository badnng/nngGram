package com.badnng.moe.tdlib

import com.badnng.moe.chat.ChatFolderInfo
import com.badnng.moe.folders.*
import com.badnng.moe.settings.*
import java.util.concurrent.atomic.AtomicBoolean

/** Actor-owned overview draft and bounded native update reconciliation, independent of chat selection. */
internal class FolderSettingsController(
    private val generation: Long,
    private val session: TdFolderSettingsSession,
    private val enqueue: (() -> Unit) -> Unit,
    private val schedule: (Long, () -> Unit) -> Unit,
    private val publish: (FolderSettingsSnapshot) -> Unit,
    private val enqueueTerminal: (() -> Unit) -> Unit = enqueue,
    private val captureAuthority: () -> (() -> Boolean) = { { true } },
    private val mutationReceipt: () -> FolderMutationReceipt = { FolderMutationReceipt.RESPONSE },
) {
    private var snapshot = FolderSettingsSnapshot(token = FolderSettingsToken(generation))
    private var owner = AtomicBoolean(false)
    private var external: () -> Boolean = { false }
    private var authority: () -> Boolean = { false }
    private var nextOwner = 0L
    private var request = 0L
    @Volatile private var retired = false
    @Volatile private var nativeAccountPremium: Boolean? = null
    private var known = false
    private var recommendationsLoaded = false
    @Volatile private var pending: Pending? = null
    private var late: Pending? = null
    private enum class Kind { REMOVE, CREATE, REORDER, TAGS, COLOR }
    private class Pending(val kind: Kind, val before: FolderNativeState, val revision: Long,
        val token: FolderSettingsToken, val lease: AtomicBoolean, val external: () -> Boolean, val authority: () -> Boolean,
        val id: Int = 0, val key: Long = 0, val ids: List<Int> = emptyList(), val main: Int = 0,
        val enabled: Boolean = false, val color: Int = -1, val premiumRequired: Boolean = false) {
        var receipt = FolderMutationReceipt.NOT_DISPATCHED
        var timedOut = false
        var accepted = false
        var created: ChatFolderInfo? = null
    }

    fun accepts(token: FolderSettingsToken) = !retired && snapshot.opened && snapshot.token == token && owner.get()
    fun accountPremium(value: Boolean) {
        if (retired || nativeAccountPremium == value) return
        nativeAccountPremium = value
        // This is an authoritative update for the current GetMe identity. Preserve all drafts;
        // regaining Premium still needs the normal fresh capability read before granting it.
        if (!value && snapshot.capabilities?.premium == true) {
            snapshot = snapshot.copy(capabilities = snapshot.capabilities?.copy(premium = false))
            emit()
        }
    }
    fun nativeUpdate(value: FolderNativeState) {
        if (retired) return
        if (value.folders.size > 100 || value.folders.any { it.id < 2 } ||
            value.folders.map { it.id }.distinct().size != value.folders.size || value.mainPosition !in 0..value.folders.size) {
            known = false
            snapshot = snapshot.copy(conflict = true, error = SettingsError.UNSUPPORTED)
            emit(); return
        }
        known = true
        snapshot = snapshot.copy(nativeState = value, nativeRevision = snapshot.nativeRevision + 1)
        val operation = pending
        if (operation != null) {
            if (operation.accepted) settle(operation)
        } else {
            val old = late
            if (old != null && matches(old, value)) {
                late = null
                if (sameDraft(old)) rebase(old)
                else if (snapshot.draft?.dirty != true) snapshot = snapshot.copy(conflict = false, error = null)
            }
            val draft = snapshot.draft
            snapshot = if (draft == null || !draft.dirty) snapshot.copy(draft = FolderOverviewDraft.from(value, (draft?.revision ?: 0) + 1))
            else snapshot.copy(conflict = snapshot.conflict || draft.source != value)
        }
        emit()
    }

    fun open(pageLease: () -> Boolean, pageId: Long = 0) {
        if (retired || !pageLease()) return
        owner.set(false); owner = AtomicBoolean(true); external = pageLease
        authority = captureAuthority()
        snapshot = snapshot.copy(token = FolderSettingsToken(generation, ++nextOwner, pageId), opened = true,
            loading = true, busy = false, error = if (pending != null || late != null) SettingsError.NOT_READY else null,
            conflict = pending != null || late != null,
            draft = if (known) FolderOverviewDraft.from(snapshot.nativeState, (snapshot.draft?.revision ?: 0) + 1) else null)
        emit(); read()
    }
    fun close(token: FolderSettingsToken) {
        if (!accepts(token)) return
        owner.set(false); request++
        recommendationsLoaded = false
        snapshot = snapshot.copy(opened = false, loading = false, busy = false, draft = null,
            recommendations = emptyList(), token = FolderSettingsToken(generation, ++nextOwner))
        // Cleanup is allowed after lock, but does not project cached sensitive content.
        publish(FolderSettingsSnapshot(token = snapshot.token))
    }
    fun deactivate() {
        retired = true; owner.set(false); request++; pending = null; late = null; session.retire()
        snapshot = FolderSettingsSnapshot(token = FolderSettingsToken(generation, ++nextOwner))
    }
    fun refresh(token: FolderSettingsToken) {
        if (!editable(token) || snapshot.draft?.dirty == true) return
        recommendationsLoaded = false
        snapshot = snapshot.copy(loading = true, error = null); emit(); read()
    }
    private fun read() {
        val token = snapshot.token; val lease = owner; val outside = external; val id = ++request
        val pageAuthority = authority
        val current = { !retired && lease.get() && outside() && pageAuthority() }
        val latest = session.latestNativeState(current)
        if (latest != null && current() && accepts(token) && request == id) nativeUpdate(latest)
        safely<FolderCapabilities>({ callback -> session.capabilities(current, callback) }) { response ->
            if (!accepts(token) || !outside() || !pageAuthority() || request != id) return@safely
            if (response.value == null) { snapshot = snapshot.copy(loading = false, error = response.error); emit(); return@safely }
            snapshot = snapshot.copy(capabilities = response.value.copy(premium = response.value.premium && nativeAccountPremium != false))
            if (recommendationsLoaded) { snapshot = snapshot.copy(loading = false); emit() }
            else safely<List<FolderRecommendation>>({ callback -> session.recommendations(current, callback) }) received@ { suggestions ->
                if (!accepts(token) || !outside() || !pageAuthority() || request != id) return@received
                recommendationsLoaded = suggestions.value != null
                snapshot = snapshot.copy(loading = false, recommendations = suggestions.value ?: snapshot.recommendations, error = suggestions.error)
                emit()
            }
        }
    }

    fun stageRecommendation(token: FolderSettingsToken, key: Long) = edit(token) { draft ->
        val entry = FolderDraftEntry.Recommended(key)
        if (entry in draft.order) draft.copy(order = draft.order - entry)
        else if (snapshot.recommendations.none { it.key == key } || effectiveCount(draft) >= limit()) null
        else draft.copy(order = draft.order + entry)
    }
    fun move(token: FolderSettingsToken, entry: FolderDraftEntry, delta: Int) = edit(token) { draft ->
        val index = draft.order.indexOf(entry); val next = index + delta
        if (delta !in listOf(-1, 1) || index < 0 || next !in draft.order.indices ||
            !premiumAllowed() && (entry == FolderDraftEntry.Main || draft.order[next] == FolderDraftEntry.Main)) null
        else draft.copy(order = draft.order.toMutableList().apply { add(next, removeAt(index)) })
    }
    fun remove(token: FolderSettingsToken, id: Int) = edit(token) { draft ->
        if (draft.source.folders.none { it.id == id && !it.isShareable }) null else draft.copy(removed = draft.removed + id)
    }
    fun restore(token: FolderSettingsToken, id: Int) = edit(token) { draft ->
        if (id !in draft.removed || effectiveCount(draft) >= limit()) null else draft.copy(removed = draft.removed - id)
    }
    fun reset(token: FolderSettingsToken) {
        if (!editable(token)) return
        snapshot = snapshot.copy(draft = FolderOverviewDraft.from(snapshot.nativeState, (snapshot.draft?.revision ?: 0) + 1),
            conflict = false, error = null, acceptedSteps = 0)
        emit()
    }
    private fun edit(token: FolderSettingsToken, transform: (FolderOverviewDraft) -> FolderOverviewDraft?) {
        if (!editable(token) || snapshot.conflict) return
        val old = snapshot.draft ?: return
        val updated = transform(old) ?: return
        if (updated == old) return
        snapshot = snapshot.copy(draft = updated.copy(revision = old.revision + 1), error = null, acceptedSteps = 0); emit()
    }
    private fun limit() = snapshot.capabilities?.folderLimit ?: 0
    private fun premiumAllowed() = snapshot.capabilities?.premium == true && nativeAccountPremium != false
    private fun desiredMainPosition(draft: FolderOverviewDraft) = draft.order.takeWhile { it != FolderDraftEntry.Main }
        .count { it !is FolderDraftEntry.Existing || it.id !in draft.removed }
    private fun effectiveCount(draft: FolderOverviewDraft) = draft.order.count { it != FolderDraftEntry.Main &&
        (it !is FolderDraftEntry.Existing || it.id !in draft.removed) }
    private fun editable(token: FolderSettingsToken) = accepts(token) && external() && authority() && known &&
        pending == null && late == null && !snapshot.busy && !snapshot.loading

    fun apply(token: FolderSettingsToken, revision: Long) {
        if (!editable(token)) return
        val draft = snapshot.draft ?: return
        if (!draft.dirty || draft.revision != revision || snapshot.conflict || draft.source != snapshot.nativeState || effectiveCount(draft) > limit()) {
            fail(SettingsError.CONFLICT); return
        }
        if (!premiumAllowed() && desiredMainPosition(draft) != 0) { fail(SettingsError.FORBIDDEN); return }
        snapshot = snapshot.copy(busy = true, operation = FolderSettingsOperation.APPLY, error = null, acceptedSteps = 0)
        emit(); nextApply()
    }
    private fun nextApply() {
        val draft = snapshot.draft ?: run { fail(SettingsError.NOT_READY); return }
        if (!owner.get() || !external() || !authority() || !snapshot.opened) { fail(SettingsError.NOT_READY); return }
        if (draft.source != snapshot.nativeState) { fail(SettingsError.CONFLICT); return }
        if (!premiumAllowed() && desiredMainPosition(draft) != 0) { fail(SettingsError.FORBIDDEN); return }
        val remove = draft.removed.firstOrNull()
        if (remove != null) { start(Kind.REMOVE, id = remove); return }
        val add = draft.order.filterIsInstance<FolderDraftEntry.Recommended>().firstOrNull()
        if (add != null) { start(Kind.CREATE, key = add.key); return }
        val ids = draft.order.filterIsInstance<FolderDraftEntry.Existing>().map { it.id }
        val main = draft.order.indexOf(FolderDraftEntry.Main)
        if (ids.size != ids.distinct().size || ids.toSet() != snapshot.nativeState.folders.map { it.id }.toSet() ||
            main !in 0..ids.size || !premiumAllowed() && main != 0) { fail(SettingsError.CONFLICT); return }
        if (ids == snapshot.nativeState.folders.map { it.id } && main == snapshot.nativeState.mainPosition) {
            snapshot = snapshot.copy(busy = false, draft = FolderOverviewDraft.from(snapshot.nativeState, draft.revision + 1),
                completedRevision = draft.revision, conflict = false); emit()
        } else start(Kind.REORDER, ids = ids, main = main)
    }
    fun toggleTags(token: FolderSettingsToken, enabled: Boolean) {
        if (!editable(token) || snapshot.draft?.dirty == true || !premiumAllowed() || snapshot.nativeState.tagsEnabled == enabled) return
        snapshot = snapshot.copy(busy = true, operation = FolderSettingsOperation.TAGS, error = null, acceptedSteps = 0)
        emit(); start(Kind.TAGS, enabled = enabled)
    }
    fun color(token: FolderSettingsToken, folder: ChatFolderInfo, colorId: Int) {
        if (!editable(token) || snapshot.draft?.dirty == true || !premiumAllowed() || !snapshot.nativeState.tagsEnabled ||
            folder.isShareable || colorId !in -1..6 || colorId == folder.colorId || snapshot.nativeState.folders.none { it == folder }) return
        snapshot = snapshot.copy(busy = true, operation = FolderSettingsOperation.COLOR, error = null, acceptedSteps = 0)
        emit(); start(Kind.COLOR, id = folder.id, color = colorId)
    }

    private fun start(kind: Kind, id: Int = 0, key: Long = 0, ids: List<Int> = emptyList(), main: Int = 0,
        enabled: Boolean = false, color: Int = -1,
    ) {
        val task = Pending(kind, snapshot.nativeState, snapshot.draft?.revision ?: 0, snapshot.token, owner, external, captureAuthority(),
            id, key, ids, main, enabled, color, kind == Kind.COLOR || kind == Kind.TAGS ||
                (snapshot.operation == FolderSettingsOperation.APPLY && (snapshot.draft?.let(::desiredMainPosition) ?: 0) != 0))
        pending = task
        val current = { !retired && task.lease.get() && task.external() && task.authority() && pending === task &&
            (!task.premiumRequired || nativeAccountPremium != false) }
        // Start before preflight/native dispatch. No receipt or old epoch can suppress this deadline.
        schedule(5_000) { enqueueTerminal {
            if (!retired && pending === task) {
                task.timedOut = true
                pending = null; late = task
                if (task.accepted && sameDraft(task)) applyAcceptedIdentity(task)
                if (matches(task, snapshot.nativeState)) {
                    late = null
                    if (sameDraft(task)) rebase(task)
                }
                snapshot = snapshot.copy(busy = false, conflict = late != null, error = SettingsError.NOT_READY)
                emit()
            }
        } }
        fun completed(response: SettingsResult<ChatFolderInfo?>, receipt: FolderMutationReceipt) {
            if (retired) return
            val tracked = pending === task || late === task
            task.receipt = receipt
            if (!tracked) return
            // A local gate rejection/preflight result is not a TDLib mutation receipt.
            if (receipt != FolderMutationReceipt.RESPONSE && receipt != FolderMutationReceipt.ERROR_RESPONSE) {
                if (pending === task) pending = null
                if (receipt == FolderMutationReceipt.UNKNOWN) {
                    late = task.takeUnless { matches(it, snapshot.nativeState) }
                } else if (late === task) late = null
                if (samePage(task)) {
                    snapshot = snapshot.copy(busy = false, conflict = receipt == FolderMutationReceipt.UNKNOWN || snapshot.conflict,
                        error = if (receipt == FolderMutationReceipt.UNKNOWN) SettingsError.NOT_READY else response.error)
                    emit()
                }
                return
            }
            if (receipt == FolderMutationReceipt.RESPONSE && response.error != null ||
                receipt == FolderMutationReceipt.ERROR_RESPONSE && response.error == null) {
                pending = null; late = task
                task.receipt = FolderMutationReceipt.UNKNOWN
                if (samePage(task)) {
                    snapshot = snapshot.copy(busy = false, conflict = true, error = SettingsError.NOT_READY)
                    emit()
                }
                return
            }
            if (response.error != null) {
                if (pending === task) pending = null
                if (late === task) late = null
                if (samePage(task)) fail(response.error)
                return
            }
            task.accepted = true; task.created = response.value
            if (kind == Kind.CREATE && task.created == null) {
                pending = null; late = task
                if (samePage(task)) fail(SettingsError.NOT_READY)
                return
            }
            if (samePage(task)) snapshot = snapshot.copy(acceptedSteps = snapshot.acceptedSteps + 1)
            if (late === task) {
                if (matches(task, snapshot.nativeState)) {
                    late = null
                    if (sameDraft(task)) rebase(task) else rebaseCleanPage()
                } else if (sameDraft(task)) applyAcceptedIdentity(task)
            } else if (!settle(task) && !samePage(task)) {
                pending = null; late = task
            }
            emit()
        }
        when (kind) {
            Kind.CREATE -> mutation<ChatFolderInfo>({ session.createRecommended(key, task.before, current, it) }) { response, receipt ->
                completed(SettingsResult(response.value, response.error), receipt)
            }
            else -> mutation<Unit>({ callback -> when (kind) {
                Kind.REMOVE -> session.remove(id, task.before, current, callback)
                Kind.REORDER -> session.reorder(ids, main, task.before, current, callback)
                Kind.TAGS -> session.toggleTags(enabled, task.before, current, callback)
                Kind.COLOR -> session.color(task.before.folders.single { it.id == id }, color, task.before, current, callback)
                else -> error("Unexpected folder operation")
            } }) { response, receipt -> completed(SettingsResult(error = response.error ?:
                SettingsError.REQUEST_FAILED.takeIf { response.value == null }), receipt) }
        }
    }

    private fun rebaseCleanPage() {
        if (snapshot.draft?.dirty == true) return
        snapshot = snapshot.copy(draft = FolderOverviewDraft.from(snapshot.nativeState,
            (snapshot.draft?.revision ?: 0) + 1), conflict = false, error = null)
    }

    private fun samePage(task: Pending) = accepts(task.token) && task.lease.get() && task.external() && task.authority()
    private fun sameDraft(task: Pending) = snapshot.token == task.token && snapshot.draft?.revision == task.revision

    private fun <T> mutation(work: ((SettingsResult<T>) -> Unit) -> Unit,
        receive: (SettingsResult<T>, FolderMutationReceipt) -> Unit,
    ) {
        val once = AtomicBoolean()
        fun complete(result: SettingsResult<T>) {
            val receipt = mutationReceipt()
            if (once.compareAndSet(false, true)) enqueueTerminal { if (!retired) receive(result, receipt) }
        }
        try { FolderMutationTerminal.scoped { work(::complete) } }
        catch (_: Exception) { FolderMutationTerminal.scoped(receipt = FolderMutationReceipt.UNKNOWN) {
            complete(SettingsResult(error = SettingsError.REQUEST_FAILED))
        } }
    }

    /** May be called by either the acknowledgement or an earlier/later native update. */
    private fun settle(task: Pending): Boolean {
        if (pending !== task || !task.accepted || !matches(task, snapshot.nativeState)) return false
        pending = null
        if (sameDraft(task)) rebase(task) else rebaseCleanPage()
        if (task.kind == Kind.REMOVE || task.kind == Kind.CREATE || task.kind == Kind.REORDER) {
            if (samePage(task)) nextApply()
            else snapshot = snapshot.copy(busy = false)
        } else { snapshot = snapshot.copy(busy = false, conflict = false); emit() }
        return true
    }
    private fun matches(task: Pending, value: FolderNativeState): Boolean = when (task.kind) {
        Kind.REMOVE -> value == task.before.copy(folders = task.before.folders.filterNot { it.id == task.id },
            mainPosition = task.before.mainPosition - if (task.before.folders.indexOfFirst { it.id == task.id } < task.before.mainPosition) 1 else 0)
        Kind.CREATE -> task.created?.let { created ->
            val index = value.folders.indexOfFirst { it.id == created.id }
            index >= 0 && value.folders[index] == created && value.folders.filterNot { it.id == created.id } == task.before.folders &&
                value.tagsEnabled == task.before.tagsEnabled &&
                value.mainPosition - (if (index < value.mainPosition) 1 else 0) == task.before.mainPosition
        } == true
        Kind.REORDER -> value == task.before.copy(folders = task.ids.map { id -> task.before.folders.single { it.id == id } }, mainPosition = task.main)
        Kind.TAGS -> value == task.before.copy(tagsEnabled = task.enabled)
        Kind.COLOR -> value == task.before.copy(folders = task.before.folders.map { if (it.id == task.id) it.copy(colorId = task.color) else it })
    }
    private fun applyAcceptedIdentity(task: Pending) {
        val draft = snapshot.draft?.takeIf { it.revision == task.revision } ?: return
        snapshot = snapshot.copy(draft = when (task.kind) {
            Kind.REMOVE -> draft.copy(order = draft.order.filterNot { it == FolderDraftEntry.Existing(task.id) }, removed = draft.removed - task.id)
            Kind.CREATE -> task.created?.let { created -> draft.copy(order = draft.order.map {
                if (it == FolderDraftEntry.Recommended(task.key)) FolderDraftEntry.Existing(created.id) else it
            }) } ?: draft
            else -> draft
        }, recommendations = if (task.kind == Kind.CREATE) snapshot.recommendations.filterNot { it.key == task.key } else snapshot.recommendations)
    }
    private fun rebase(task: Pending) {
        applyAcceptedIdentity(task)
        val draft = snapshot.draft?.takeIf { it.revision == task.revision } ?: return
        snapshot = snapshot.copy(draft = if (task.kind == Kind.TAGS || task.kind == Kind.COLOR)
            FolderOverviewDraft.from(snapshot.nativeState, draft.revision + 1) else draft.copy(source = snapshot.nativeState), conflict = false)
    }
    private fun fail(error: SettingsError) {
        snapshot = snapshot.copy(busy = false, error = error,
            conflict = snapshot.conflict || error == SettingsError.CONFLICT || snapshot.draft?.source != snapshot.nativeState)
        emit()
    }
    private fun <T> safely(work: ((SettingsResult<T>) -> Unit) -> Unit, receive: (SettingsResult<T>) -> Unit) {
        val once = AtomicBoolean()
        fun complete(result: SettingsResult<T>) { if (once.compareAndSet(false, true)) enqueue { if (!retired) receive(result) } }
        try { work(::complete) } catch (_: Exception) { complete(SettingsResult(error = SettingsError.REQUEST_FAILED)) }
    }
    private fun emit() {
        if (snapshot.opened && owner.get() && external() && authority()) publish(snapshot)
    }
}
