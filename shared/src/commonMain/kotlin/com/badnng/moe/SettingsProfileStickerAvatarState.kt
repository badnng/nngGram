package com.badnng.moe

import androidx.compose.runtime.*
import com.badnng.moe.settings.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlin.math.*

/** A one-way, thread-safe predicate. It never reads Compose state from a native worker. */
internal class AvatarUiLease {
    private val live = MutableStateFlow(true)
    fun active(): Boolean = live.value
    fun close() { live.value = false }
}

internal sealed interface AvatarCatalogSource {
    data object Default : AvatarCatalogSource
    data class Packs(val kind: ProfileStickerAvatarPackKind, val query: String = "") : AvatarCatalogSource
    data class Pack(val value: ProfileStickerAvatarPack) : AvatarCatalogSource
}

/** Every opening is distinct, including reopening the same account/photo. All methods except lease predicates run on UI. */
internal class SettingsProfileStickerAvatarOpening(
    val parent: SettingsProfileExtrasToken,
    val expectedPhotoId: Long?,
    private val actions: SettingsProfileStickerAvatarActions,
    private val scope: CoroutineScope,
) {
    val pageLease = settingsProfileExtrasPageLease()
    private val lifetime = AvatarUiLease()
    private var environmentCurrent by mutableStateOf(false)
    private var externalEnabled by mutableStateOf(false)
    private var externalBusy by mutableStateOf(false)
    private var currentPhotoId by mutableStateOf(expectedPhotoId)
    private var openAttempt: AvatarUiLease? = null
    /** The actor retains open's predicate as part of this picker's owner, beyond open admission. */
    private var pickerOwner: AvatarUiLease? = null
    private var query: AvatarUiLease? = null
    private var selectionAttempt: AvatarUiLease? = null
    private var writeLease: AvatarUiLease? = null
    private var openTimer: Job? = null
    private var queryTimer: Job? = null
    private var sourceWatch: Job? = null
    private var selectionTimer: Job? = null
    private var submitted: AvatarPreviewEditor.Submission<ProfileStickerAvatarSelectionTicket>? = null
    private var submissionFailure: Pair<Long, SettingsError>? = null
    val editor = AvatarPreviewEditor<ProfileStickerAvatarSelectionTicket>(checkNotNull(AvatarPreviewPalettes.get(0)))
    var picker by mutableStateOf<ProfileStickerAvatarPickerTicket?>(null); private set
    var source by mutableStateOf<AvatarCatalogSource>(AvatarCatalogSource.Default); private set
    var catalogEpoch by mutableLongStateOf(0); private set
    var candidates by mutableStateOf<List<ProfileStickerAvatarCandidate>>(emptyList()); private set
    var packs by mutableStateOf<List<ProfileStickerAvatarPack>>(emptyList()); private set
    var previousWindow by mutableStateOf<ProfileStickerAvatarCatalogCursor?>(null); private set
    var nextWindow by mutableStateOf<ProfileStickerAvatarCatalogCursor?>(null); private set
    var windowNumber by mutableIntStateOf(1); private set
    var limited by mutableStateOf(false); private set
    var catalogInvalidated by mutableStateOf(false); private set
    var loading by mutableStateOf(false); private set
    var selecting by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var retryCatalogAllowed by mutableStateOf(false); private set
    var windowNotice by mutableStateOf<String?>(null); private set
    private var windowNoticeRevision by mutableLongStateOf(0L)
    var errorRevision by mutableLongStateOf(0); private set
    private fun recordError(value: String?, retryCatalog: Boolean = value != null) {
        error = value; retryCatalogAllowed = value != null && retryCatalog; errorRevision++
    }
    private fun recordWindowNotice(value: String?) { windowNotice = value; windowNoticeRevision++ }
    var selectedCandidate by mutableStateOf<ProfileStickerAvatarCandidateTicket?>(null); private set
    private var selectedIdentity: ProfileStickerAvatarIdentity? = null
    // The editor may retain an expired ticket as a local draft; this flag alone controls UI use.
    private var selectionAuthorized by mutableStateOf(false)
    private var previewSnapshotOwner: Any? = null
    private var previewSnapshot: ((suspendMedia: Boolean) -> Boolean)? = null
    private var frozenSelection: ProfileStickerAvatarSelectionTicket? = null
    private var frozenPreview by mutableStateOf<ProfileStickerAvatarFrozenDisplay?>(null)
    var confirmation by mutableStateOf<AvatarPreviewEditor.Confirmation<ProfileStickerAvatarSelectionTicket>?>(null); private set
    var colorsEdit by mutableStateOf<AvatarPreviewEditor.ColorsEdit?>(null); private set
    private var editingHsl: MutableList<AvatarHsl>? = null
    private var confirmedCustomHsl: List<AvatarHsl>? = null
    private var confirmedCustomRgb: List<Int>? = null
    var colorControlRevision by mutableLongStateOf(0); private set
    var version by mutableIntStateOf(0); private set
    var accepted by mutableStateOf(false); private set
    val active: Boolean get() = lifetime.active() && pageLease.active() && environmentCurrent
    /** Captured by workers; unlike active, this never reads UI-owned state. */
    fun mediaOwnerActive(): Boolean = lifetime.active() && pageLease.active()
    val editable: Boolean get() = version.let { active && externalEnabled && !externalBusy && !editor.pending && !selecting && !loading }
    val editingEnabled: Boolean get() = editable && confirmation == null && colorsEdit == null
    // Switching sources may cancel a pending catalog read; it never enables edits or writes.
    val catalogNavigationEnabled: Boolean get() = version.let {
        active && externalEnabled && !externalBusy && !editor.pending && !selecting && confirmation == null && colorsEdit == null
    }
    val waitingForSave: Boolean get() = version.let { editor.pending }
    val selectionUsable: Boolean get() = selectionAuthorized && !loading && !catalogInvalidated && editor.selection != null
    val selectionNeedsReselection: Boolean get() = editor.selection != null && !selectionAuthorized && !loading
    fun refresh() { version++ }

    fun registerPreviewSnapshot(owner: Any, capture: (suspendMedia: Boolean) -> Boolean) {
        previewSnapshotOwner = owner; previewSnapshot = capture
    }

    fun unregisterPreviewSnapshot(owner: Any) {
        if (previewSnapshotOwner === owner) { previewSnapshotOwner = null; previewSnapshot = null }
    }

    fun frozenPreviewFor(selection: ProfileStickerAvatarSelectionTicket?): ProfileStickerAvatarFrozenDisplay? =
        frozenPreview.takeIf { selection != null && frozenSelection === selection }

    fun retainFrozenPreview(selection: ProfileStickerAvatarSelectionTicket, value: ProfileStickerAvatarFrozenDisplay): Boolean {
        if (!active || editor.selection !== selection) { runCatching { value.close() }; return false }
        clearFrozenPreview(); frozenSelection = selection; frozenPreview = value
        return true
    }

    fun clearFrozenPreview(selection: ProfileStickerAvatarSelectionTicket? = null) {
        if (selection != null && frozenSelection !== selection) return
        val previous = frozenPreview; frozenPreview = null; frozenSelection = null
        runCatching { previous?.close() }
    }

    fun retainPreviewBeforeNavigation(): Boolean {
        val retained = previewSnapshot?.let { runCatching { it(false) }.getOrDefault(false) } ?: true
        if (!retained) recordWindowNotice("无法保留当前预览，请稍后重试。")
        else recordWindowNotice(null)
        return retained
    }

    fun environment(current: Boolean, enabled: Boolean, busy: Boolean, photoId: Long?) {
        environmentCurrent = current; externalEnabled = enabled; externalBusy = busy; currentPhotoId = photoId
    }

    fun open() {
        if (!active || !externalEnabled || externalBusy || picker != null || openAttempt != null) return
        val attempt = AvatarUiLease(); openAttempt = attempt; loading = true; recordError(null)
        // A late successful open still has to close its exact ticket even after composition is disposed.
        val delivered = MutableStateFlow(false)
        try { actions.open(parent, { lifetime.active() && pageLease.active() && attempt.active() }) { result ->
            val ticket = result.value
            val job = scope.launch {
                yield()
                if (!delivered.compareAndSet(false, true)) return@launch
                if (!active || openAttempt !== attempt || !attempt.active()) {
                    attempt.close()
                    ticket?.let { runCatching { actions.close(parent, it) } }
                    return@launch
                }
                openAttempt = null; openTimer?.cancel(); openTimer = null; loading = false
                if (ticket == null || result.error != null) {
                    attempt.close()
                    ticket?.let { runCatching { actions.close(parent, it) } }; recordError("无法读取表情头像，请重试。")
                } else {
                    pickerOwner = attempt
                    picker = ticket
                    val originalVersion = ticket.catalogInvalidations.value
                    sourceWatch?.cancel()
                    sourceWatch = scope.launch {
                        ticket.catalogInvalidations.collect { value ->
                            if (value != originalVersion && active && picker === ticket && !catalogInvalidated)
                                sourceChanged()
                        }
                    }
                    if (editable) load(AvatarCatalogSource.Default) else recordError("资料就绪后请重试读取内容。")
                }
            }
            job.invokeOnCompletion { if (it != null && delivered.compareAndSet(false, true)) ticket?.let { p -> runCatching { actions.close(parent, p) } } }
        } } catch (_: Exception) {
            if (openAttempt === attempt) { attempt.close(); openAttempt = null; loading = false; recordError("无法读取表情头像，请重试。") }
        }
        openTimer?.cancel()
        openTimer = scope.launch {
            delay(15_000)
            if (openAttempt === attempt && attempt.active()) {
                attempt.close(); openAttempt = null; loading = false; recordError("读取超时，请重试。")
            }
        }
    }

    fun load(target: AvatarCatalogSource, preserveStaleDraft: Boolean = false) {
        val p = picker ?: return
        if (!catalogNavigationEnabled) return
        query?.close(); queryTimer?.cancel(); selectionAttempt?.close(); selectionTimer?.cancel(); selectionAttempt = null; selecting = false
        // A fresh catalog read retires native selection authority. A failed window can still
        // retain its local draft while the user reloads and explicitly selects again.
        val previousPack = source as? AvatarCatalogSource.Pack
        val returningFromPack = previousPack != null && target is AvatarCatalogSource.Packs &&
            target.kind == previousPack.value.kind && target.query.isEmpty()
        val keepDraft = preserveStaleDraft && (source === target || returningFromPack) &&
            !selectionAuthorized && editor.selection != null
        if (!keepDraft) { editor.invalidateSelection(); selectedIdentity = null; clearFrozenPreview() }
        selectionAuthorized = false; selectedCandidate = null; refresh()
        val request = AvatarUiLease(); query = request; catalogEpoch++; source = target; loading = true; recordError(null)
        candidates = emptyList(); packs = emptyList(); previousWindow = null; nextWindow = null; windowNumber = 1; limited = false; recordWindowNotice(null)
        val once = MutableStateFlow(false)
        fun accept(page: ProfileStickerAvatarCandidatePage?, list: ProfileStickerAvatarPackPage?, err: SettingsError?) {
            scope.launch {
                yield()
                if (!once.compareAndSet(false, true) || !active || query !== request || !request.active() ||
                    editor.pending || picker !== p || source != target) return@launch
                query = null; queryTimer?.cancel(); queryTimer = null; request.close(); loading = false
                if (err != null || page == null && list == null) recordError(catalogError(err))
                else {
                    candidates = page?.candidates.orEmpty(); packs = list?.packs.orEmpty()
                    limited = page?.limited ?: list?.limited ?: false
                    previousWindow = page?.previous ?: list?.previous
                    nextWindow = page?.next ?: list?.next
                    catalogInvalidated = false
                }
            }
        }
        val activeRequest = { lifetime.active() && pageLease.active() && request.active() }
        try { when (target) {
            AvatarCatalogSource.Default -> actions.loadDefault(parent, p, activeRequest) { accept(it.value, null, it.error) }
            is AvatarCatalogSource.Packs -> if (target.query.isEmpty())
                actions.loadInstalledPacks(parent, p, target.kind, activeRequest) { accept(null, it.value, it.error) }
            else actions.searchInstalled(parent, p, target.kind, target.query, activeRequest) { accept(null, it.value, it.error) }
            is AvatarCatalogSource.Pack -> actions.openPack(parent, p, target.value.ticket, activeRequest) { accept(it.value, null, it.error) }
        } } catch (_: Exception) {
            if (query === request) { request.close(); query = null; loading = false; recordError("无法读取内容，请重试。") }
        }
        queryTimer = scope.launch {
            delay(15_000)
            if (query === request && request.active()) {
                request.close(); query = null; loading = false; recordError("读取超时，请重试。")
            }
        }
    }

    private fun catalogError(error: SettingsError?): String = when (error) {
        SettingsError.RATE_LIMITED -> "内容处理资源繁忙或超出预算，请重试。"
        SettingsError.CONFLICT -> "目录已变化，请重新读取。"
        SettingsError.INVALID_VALUE -> "搜索内容过长或目录数据无效。"
        SettingsError.UNSUPPORTED -> "当前来源暂不支持此操作。"
        else -> "无法读取内容，请重试。"
    }

    private fun sourceChanged() {
        catalogInvalidated = true
        selectionAuthorized = false
        recordWindowNotice(null)
        clearFrozenPreview()
        val invalidatedPack = source as? AvatarCatalogSource.Pack
        if (invalidatedPack != null) source = AvatarCatalogSource.Packs(invalidatedPack.value.kind)
        query?.close(); query = null; queryTimer?.cancel(); queryTimer = null
        selectionAttempt?.close(); selectionAttempt = null; selectionTimer?.cancel(); selectionTimer = null
        loading = false; selecting = false; catalogEpoch++
        candidates = emptyList(); packs = emptyList(); previousWindow = null; nextWindow = null; limited = false
        selectedCandidate = null; selectedIdentity = null
        if (!editor.pending) {
            editor.invalidateSelection(); confirmation = null; colorsEdit = null; editingHsl = null
            recordError(if (invalidatedPack != null) "贴纸包已更新，请重新读取已安装包并选择。" else "目录已更新，请重新读取。")
        } else recordError("目录已更新，正在等待原有保存结果。")
        refresh()
    }

    fun searchInstalled(epoch: Long, owner: ProfileStickerAvatarPickerTicket, origin: AvatarCatalogSource.Packs, text: String) {
        if (!editingEnabled || picker !== owner || catalogEpoch != epoch || source !== origin) return
        load(AvatarCatalogSource.Packs(origin.kind, text.trim()))
    }

    /** Only a cursor from this exact displayed window may start a same-source transition. */
    fun moveWindow(epoch: Long, owner: ProfileStickerAvatarPickerTicket, origin: AvatarCatalogSource,
        cursor: ProfileStickerAvatarCatalogCursor) {
        if (!editingEnabled || picker !== owner || catalogEpoch != epoch || source !== origin ||
            (cursor !== previousWindow && cursor !== nextWindow)) return
        val requestedWindow = if (cursor === previousWindow) (windowNumber - 1).coerceAtLeast(1)
            else (windowNumber + 1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val retainedSelection = editor.selection?.takeIf { selectionUsable }
        // Retain only already-prepared display pixels before the native call revokes its
        // grant. A failed retain leaves this page and selection untouched and retryable.
        if (retainedSelection != null && previewSnapshot?.let { capture ->
                runCatching { capture(true) }.getOrDefault(false)
            } == false) {
            recordWindowNotice("无法保留当前预览，未能翻页。请重试，或重新读取并选择。")
            return
        }
        recordWindowNotice(null)
        selectionAuthorized = false
        query?.close(); queryTimer?.cancel(); selectionAttempt?.close(); selectionTimer?.cancel()
        selectionAttempt = null; selecting = false
        val request = AvatarUiLease(); query = request; catalogEpoch++; loading = true; recordError(null)
        candidates = emptyList(); packs = emptyList(); previousWindow = null; nextWindow = null; limited = false
        selectedCandidate = null; refresh()
        val once = MutableStateFlow(false)
        try { actions.loadWindow(parent, owner, cursor, { lifetime.active() && pageLease.active() && request.active() }) { response ->
            scope.launch {
                yield()
                if (!once.compareAndSet(false, true) || !active || query !== request || !request.active() ||
                    editor.pending || picker !== owner || source !== origin) return@launch
                query = null; queryTimer?.cancel(); queryTimer = null; request.close(); loading = false
                val page = response.value
                val matchesSource = when (origin) {
                    is AvatarCatalogSource.Packs -> page is ProfileStickerAvatarCatalogWindow.Packs
                    else -> page is ProfileStickerAvatarCatalogWindow.Candidates
                }
                if (response.error != null || page == null || !matchesSource) {
                    selectedCandidate = null; recordError(catalogError(response.error)); refresh()
                } else {
                    // Native returns this page only after matching the same source revision.
                    selectionAuthorized = retainedSelection != null && editor.selection === retainedSelection
                    windowNumber = requestedWindow
                    when (page) {
                        is ProfileStickerAvatarCatalogWindow.Candidates -> {
                            candidates = page.page.candidates; limited = page.page.limited
                            selectedCandidate = if (selectionAuthorized) candidates.singleOrNull { it.identity == selectedIdentity }?.ticket else null
                            previousWindow = page.page.previous; nextWindow = page.page.next
                        }
                        is ProfileStickerAvatarCatalogWindow.Packs -> {
                            packs = page.page.packs; limited = page.page.limited
                            previousWindow = page.page.previous; nextWindow = page.page.next
                        }
                    }
                    catalogInvalidated = false
                    refresh()
                }
            }
        } } catch (_: Exception) {
            if (query === request) {
                request.close(); query = null; loading = false; selectedCandidate = null
                recordError("无法读取内容，请重试。"); refresh()
            }
        }
        queryTimer = scope.launch {
            delay(15_000)
            if (query === request && request.active()) {
                request.close(); query = null; loading = false; selectedCandidate = null
                recordError("读取超时，请重新读取。"); refresh()
            }
        }
    }

    fun openPack(epoch: Long, owner: ProfileStickerAvatarPickerTicket, origin: AvatarCatalogSource,
        pack: ProfileStickerAvatarPack) {
        if (!editingEnabled || picker !== owner || catalogEpoch != epoch || source !== origin ||
            origin !is AvatarCatalogSource.Packs || origin.kind != pack.kind || packs.none { it === pack && it.ticket === pack.ticket }) return
        load(AvatarCatalogSource.Pack(pack))
    }

    fun returnToPacks(epoch: Long, owner: ProfileStickerAvatarPickerTicket, origin: AvatarCatalogSource.Pack) {
        if (!editingEnabled || picker !== owner || catalogEpoch != epoch || source !== origin) return
        load(AvatarCatalogSource.Packs(origin.value.kind))
    }

    fun navigateCatalog(epoch: Long, owner: ProfileStickerAvatarPickerTicket, origin: AvatarCatalogSource,
        target: AvatarCatalogSource) {
        if (!catalogNavigationEnabled || picker !== owner || catalogEpoch != epoch || source !== origin) return
        load(target)
    }

    fun retryCatalog(epoch: Long, owner: ProfileStickerAvatarPickerTicket, origin: AvatarCatalogSource, revision: Long) {
        if (!editingEnabled || picker !== owner || catalogEpoch != epoch || source !== origin || loading ||
            error == null || !retryCatalogAllowed || errorRevision != revision) return
        if (origin is AvatarCatalogSource.Pack) {
            // A native window transition has already revoked the pack's old list ticket.
            // Re-enter the installed list to obtain a fresh pack capability.
            load(AvatarCatalogSource.Packs(origin.value.kind), preserveStaleDraft = selectionNeedsReselection)
        } else load(origin, preserveStaleDraft = selectionNeedsReselection)
    }

    fun reloadAfterPreviewFailure(epoch: Long, owner: ProfileStickerAvatarPickerTicket, origin: AvatarCatalogSource, revision: Long) {
        if (!editingEnabled || picker !== owner || catalogEpoch != epoch || source !== origin ||
            windowNotice == null || windowNoticeRevision != revision) return
        if (origin is AvatarCatalogSource.Pack) load(AvatarCatalogSource.Packs(origin.value.kind)) else load(origin)
    }

    fun currentWindowNoticeRevision(): Long = windowNoticeRevision

    fun retryOpen(revision: Long) {
        if (!active || picker != null || openAttempt != null || error == null || errorRevision != revision) return
        open()
    }

    fun select(candidate: ProfileStickerAvatarCandidate, epoch: Long, owner: ProfileStickerAvatarPickerTicket,
        origin: AvatarCatalogSource) {
        val p = picker ?: return
        if (!editingEnabled || p !== owner || catalogEpoch != epoch || source !== origin ||
            candidates.none { it === candidate && it.ticket === candidate.ticket }) return
        selectionAttempt?.close(); selectionTimer?.cancel(); val request = AvatarUiLease(); selectionAttempt = request
        editor.invalidateSelection(); selectionAuthorized = false; selectedCandidate = null; selectedIdentity = null; clearFrozenPreview(); selecting = true; recordError(null); recordWindowNotice(null); refresh()
        val once = MutableStateFlow(false)
        try { actions.select(parent, p, candidate.ticket, { lifetime.active() && pageLease.active() && request.active() }) { result ->
            scope.launch {
                yield()
                if (!once.compareAndSet(false, true) || !active || selectionAttempt !== request || !request.active() ||
                    picker !== p || editor.pending || candidates.none { it.ticket === candidate.ticket }) return@launch
                selectionAttempt = null; selectionTimer?.cancel(); selectionTimer = null; request.close(); selecting = false
                val selected = result.value
                if (selected == null || result.error != null) recordError("无法选择此内容，请重试。")
                else { editor.select(selected, true); selectionAuthorized = true; selectedCandidate = candidate.ticket; selectedIdentity = candidate.identity }
                refresh()
            }
        } } catch (_: Exception) {
            if (selectionAttempt === request) { request.close(); selectionAttempt = null; selecting = false; recordError("无法选择此内容，请重试。") }
        }
        selectionTimer = scope.launch {
            delay(15_000)
            if (selectionAttempt === request && request.active()) {
                request.close(); selectionAttempt = null; selecting = false; recordError("选择超时，请重试。")
            }
        }
    }

    fun choosePalette(index: Int) { if (editable && editor.choosePalette(index, true)) { if (selectionUsable) recordError(null); refresh() } }
    fun chooseCustom() { if (editable && editor.chooseCustom(true)) { if (selectionUsable) recordError(null); refresh() } }
    fun startColors() {
        if (!editable) return
        runCatching { previewSnapshot?.invoke(false) }
        editor.editColors(true)?.let { edit ->
            val rgb = edit.colors()
            val saved = confirmedCustomHsl?.takeIf { editor.selectedPalette == null && confirmedCustomRgb == rgb && it.size == rgb.size }
            editingHsl = (saved ?: rgb.map { AvatarHsl.fromRgb(it) }).toMutableList()
            colorsEdit = edit; colorControlRevision++; refresh()
        }
    }
    fun colorIndex(edit: AvatarPreviewEditor.ColorsEdit, index: Int) {
        if (editable && editor.selectColor(edit, index, true)) { colorControlRevision++; refresh() }
    }
    fun colorHsl(edit: AvatarPreviewEditor.ColorsEdit): AvatarHsl =
        editingHsl?.takeIf { colorsEdit === edit }?.getOrNull(edit.selectedIndex)
            ?: AvatarHsl.fromRgb(edit.colors()[edit.selectedIndex])
    fun colorChange(edit: AvatarPreviewEditor.ColorsEdit, index: Int, revision: Long, part: AvatarHslPart, value: Float) {
        val hsl = editingHsl ?: return
        if (!editable || colorsEdit !== edit || index != edit.selectedIndex || revision != colorControlRevision || index !in hsl.indices || !value.isFinite()) return
        val previous = hsl[index]
        val next = when (part) {
            AvatarHslPart.HUE -> previous.copy(hue = value.coerceIn(0f, 360f))
            AvatarHslPart.SATURATION -> previous.copy(saturation = (value / 100f).coerceIn(0f, 1f))
            AvatarHslPart.LIGHTNESS -> previous.copy(lightness = (value / 100f).coerceIn(0f, 1f))
        }
        if (editor.changeColor(edit, next.rgb(), true)) { hsl[index] = next; refresh() }
    }
    fun colorAdd(edit: AvatarPreviewEditor.ColorsEdit) {
        val values = editingHsl ?: return
        if (!editable || colorsEdit !== edit || values.isEmpty()) return
        val last = values.last()
        val next = last.copy(lightness = if (last.lightness > .5f) last.lightness * .7f else .5f + last.lightness * .5f)
        if (editor.addColor(edit, next.rgb(), true)) { values.add(next); colorControlRevision++; refresh() }
    }
    fun colorRemove(edit: AvatarPreviewEditor.ColorsEdit) {
        val values = editingHsl ?: return
        if (editable && colorsEdit === edit && editor.removeColor(edit, true)) { values.removeAt(values.lastIndex); colorControlRevision++; refresh() }
    }
    fun finishColors(edit: AvatarPreviewEditor.ColorsEdit, apply: Boolean) {
        if (!editable || colorsEdit !== edit) return
        val result = editingHsl?.toList() ?: return
        if (editor.finishColors(edit, apply, true)) {
            if (apply) { confirmedCustomHsl = result; confirmedCustomRgb = edit.colors().toList() }
            editingHsl = null; colorsEdit = null; refresh()
        }
    }
    fun confirm() {
        if (!editable || !selectionUsable || currentPhotoId != expectedPhotoId) { if (active && currentPhotoId != expectedPhotoId) recordError("当前头像已更改，请关闭后重新打开。"); return }
        editor.confirm(true)?.let { confirmation = it; recordError(null); refresh() }
    }
    fun cancelConfirm(value: AvatarPreviewEditor.Confirmation<ProfileStickerAvatarSelectionTicket>) {
        if (active && editor.cancelConfirmation(value, true)) { confirmation = null; refresh() }
    }
    fun submit(value: AvatarPreviewEditor.Confirmation<ProfileStickerAvatarSelectionTicket>) {
        val p = picker ?: return
        if (!editable || !selectionUsable || confirmation !== value || currentPhotoId != expectedPhotoId) return
        val background = ProfileStickerAvatarBackground.fromColors(value.colors.values()) ?: return
        val submission = editor.submit(value, true) ?: return
        runCatching { previewSnapshot?.invoke(false) }
        submitted = submission; submissionFailure = null; query?.close(); queryTimer?.cancel(); query = null; selectionAttempt?.close(); selectionTimer?.cancel(); selectionAttempt = null
        loading = false; selecting = false; recordError(null); recordWindowNotice(null); refresh()
        val writeOwner = AvatarUiLease(); writeLease = writeOwner
        // Closing this UI ends its consumer; the actor retains the actual dispatched write/result.
        try { actions.save(parent, p, value.selection, expectedPhotoId, background,
            { lifetime.active() && pageLease.active() && writeOwner.active() },
            { revision -> scope.launch { yield(); if (active && submitted === submission) {
                editor.admitted(submission, revision, true); settle(submission); refresh()
            } } },
            { result -> scope.launch { yield(); if (active && submitted === submission && result.token == parent) {
                if (editor.completed(submission, result.revision, result.error == null, true)) {
                    submissionFailure = result.error?.let { result.revision to it }
                    settle(submission); refresh()
                }
            } } }) } catch (_: Exception) {
            // Dispatch may already have occurred: do not synthesize a rejection or release actor ownership.
            recordError("正在等待保存结果，可关闭此窗口。")
        }
    }
    private fun settle(value: AvatarPreviewEditor.Submission<ProfileStickerAvatarSelectionTicket>) {
        val success = value.settled ?: return
        if (submitted !== value) return
        val failure = submissionFailure?.takeIf { it.first == value.admittedRevision }?.second
        submitted = null
        submissionFailure = null
        writeLease?.close(); writeLease = null
        if (success) accepted = true else {
            confirmation?.let { editor.cancelConfirmation(it, true) }; confirmation = null
            if (catalogInvalidated) {
                editor.invalidateSelection(); selectedCandidate = null; selectedIdentity = null
                recordError("目录已更新，请重新读取并选择。")
            } else when (failure) {
                SettingsError.CONFLICT -> {
                    // The actor did not prove this selection or profile is still current.
                    // Keep the local draft but never reuse its ticket as an authorization.
                    selectionAuthorized = false; catalogInvalidated = true; catalogEpoch++
                    val pack = source as? AvatarCatalogSource.Pack
                    if (pack != null) source = AvatarCatalogSource.Packs(pack.value.kind)
                    candidates = emptyList(); packs = emptyList(); previousWindow = null; nextWindow = null; limited = false
                    selectedCandidate = null
                    recordError("头像或所选内容已变化，请重新读取并选择；头像变更时请关闭后重开。")
                }
                SettingsError.RATE_LIMITED -> recordError("内容处理资源繁忙，选择和颜色已保留，请重试保存。", retryCatalog = false)
                else -> recordError("未能保存，选择和颜色已保留。请重试。", retryCatalog = false)
            }
        }
    }
    fun close() {
        if (!lifetime.active()) return
        lifetime.close(); pageLease.close(); openAttempt?.close(); pickerOwner?.close(); query?.close(); selectionAttempt?.close(); writeLease?.close()
        pickerOwner = null; selectionAuthorized = false; previewSnapshotOwner = null; previewSnapshot = null; clearFrozenPreview()
        openTimer?.cancel(); queryTimer?.cancel(); selectionTimer?.cancel(); sourceWatch?.cancel()
        val owned = picker; picker = null
        editor.close(); submitted = null; submissionFailure = null; confirmation = null; colorsEdit = null; editingHsl = null; confirmedCustomHsl = null; confirmedCustomRgb = null
        owned?.let { runCatching { actions.close(parent, it) } }
    }
}

/** 24-bit sRGB conversion for the actual HSL controls; preserves alpha by never touching artwork. */
internal enum class AvatarHslPart { HUE, SATURATION, LIGHTNESS }
internal data class AvatarHsl(val hue: Float, val saturation: Float, val lightness: Float) {
    fun rgb(): Int {
        val h = ((hue % 360f) + 360f) % 360f; val s = saturation.coerceIn(0f, 1f); val l = lightness.coerceIn(0f, 1f)
        val c = (1f - abs(2f * l - 1f)) * s; val x = c * (1f - abs((h / 60f) % 2f - 1f)); val m = l - c / 2f
        val (r, g, b) = when (h.toInt() / 60) { 0 -> Triple(c,x,0f); 1 -> Triple(x,c,0f); 2 -> Triple(0f,c,x); 3 -> Triple(0f,x,c); 4 -> Triple(x,0f,c); else -> Triple(c,0f,x) }
        fun channel(v: Float) = ((v + m) * 255f).roundToInt().coerceIn(0,255)
        return (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
    }
    companion object {
        fun fromRgb(rgb: Int): AvatarHsl {
            val r = (rgb shr 16 and 255) / 255f; val g = (rgb shr 8 and 255) / 255f; val b = (rgb and 255) / 255f
            val hi = max(r, max(g,b)); val lo = min(r,min(g,b)); val d = hi-lo; val l = (hi+lo)/2f
            val h = when { d == 0f -> 0f; hi == r -> 60f * (((g-b)/d) % 6f); hi == g -> 60f * ((b-r)/d+2f); else -> 60f*((r-g)/d+4f) }
            return AvatarHsl((h+360f)%360f, if (d == 0f) 0f else d/(1f-abs(2f*l-1f)), l)
        }
    }
}
