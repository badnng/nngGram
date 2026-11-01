package com.badnng.moe

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badnng.moe.settings.SETTINGS_STORAGE_POLICY_LONG_RETENTION_SECONDS
import com.badnng.moe.settings.SettingsError
import com.badnng.moe.settings.SETTINGS_STORAGE_POLICY_MAX
import com.badnng.moe.settings.SettingsStoragePolicyActions
import com.badnng.moe.settings.SettingsStoragePolicyCompletion
import com.badnng.moe.settings.SettingsStoragePolicyDraft
import com.badnng.moe.settings.SettingsStoragePolicySnapshot
import com.badnng.moe.settings.SettingsStoragePolicySource
import com.badnng.moe.settings.SettingsStoragePolicyStatus
import com.badnng.moe.settings.SettingsStoragePolicyStep
import com.badnng.moe.settings.SettingsStoragePolicyToken
import com.badnng.moe.settings.SettingsStoragePolicyValues
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import kotlin.math.roundToInt

private const val STORAGE_POLICY_KIB_PER_MIB = 1024L
private const val STORAGE_POLICY_KIB_PER_GIB = 1024L * 1024L
private const val STORAGE_POLICY_SECONDS_PER_DAY = 24L * 60L * 60L

private data class StoragePolicyCapOption(val sizeKiB: Long, val label: String)
private data class StoragePolicyRetentionOption(val seconds: Long, val label: String)
private data class StoragePolicyDesiredValues(val sizeKiB: Long, val unusedSeconds: Long, val automatic: Boolean)

private val storagePolicyCapOptions = buildList {
    for (mib in 200..900 step 100) add(StoragePolicyCapOption(mib * STORAGE_POLICY_KIB_PER_MIB, "$mib MB"))
    for (gib in 1..10) add(StoragePolicyCapOption(gib * STORAGE_POLICY_KIB_PER_GIB, "$gib GB"))
}

private val storagePolicyRetentionOptions = listOf(
    StoragePolicyRetentionOption(7L * STORAGE_POLICY_SECONDS_PER_DAY, "保留 7 天"),
    StoragePolicyRetentionOption(30L * STORAGE_POLICY_SECONDS_PER_DAY, "保留 30 天"),
    StoragePolicyRetentionOption(90L * STORAGE_POLICY_SECONDS_PER_DAY, "保留 90 天"),
    StoragePolicyRetentionOption(180L * STORAGE_POLICY_SECONDS_PER_DAY, "保留 180 天"),
    StoragePolicyRetentionOption(365L * STORAGE_POLICY_SECONDS_PER_DAY, "保留 365 天"),
    StoragePolicyRetentionOption(SETTINGS_STORAGE_POLICY_LONG_RETENTION_SECONDS, "长期保留"),
)

/**
 * App-owned in-memory draft lifetime. Pass this from SettingsWorkspaceState when the page can
 * leave composition while a native read/write is pending; standalone callers get a local session.
 */
internal class SettingsStoragePolicyUiSession {
    private var currentGeneration by mutableStateOf<Long?>(null)
    private var currentEditor by mutableStateOf<SettingsStoragePolicyDraftState?>(null)
    private var nextRequestId = 0L
    var expanded by mutableStateOf(false)
        private set

    fun updateExpanded(value: Boolean) { expanded = value }

    fun editor(generation: Long): SettingsStoragePolicyDraftState? =
        currentEditor.takeIf { currentGeneration == generation }

    fun observe(snapshot: SettingsStoragePolicySnapshot) {
        val generation = snapshot.token.generation
        if (currentGeneration != null && currentGeneration != generation) {
            if (currentEditor != null) expanded = false
            currentGeneration = generation
            currentEditor = if (snapshot.opened) SettingsStoragePolicyDraftState(snapshot) else null
            return
        }
        if (!snapshot.opened) return
        if (currentGeneration != generation || currentEditor == null)
            currentEditor = SettingsStoragePolicyDraftState(snapshot)
        else currentEditor?.observe(snapshot)
        currentGeneration = generation
    }

    fun setSize(editor: SettingsStoragePolicyDraftState, snapshot: SettingsStoragePolicySnapshot,
        active: Boolean, sizeKiB: Long,
    ) {
        if (!editor.canEdit(snapshot, active) || sizeKiB !in 0..SETTINGS_STORAGE_POLICY_MAX) return
        editor.updateSize(sizeKiB)
    }

    fun setRetention(editor: SettingsStoragePolicyDraftState, snapshot: SettingsStoragePolicySnapshot,
        active: Boolean, seconds: Long,
    ) {
        if (!editor.canEdit(snapshot, active) || seconds !in 0..SETTINGS_STORAGE_POLICY_MAX) return
        editor.updateRetention(seconds)
    }

    fun setAutomatic(editor: SettingsStoragePolicyDraftState, snapshot: SettingsStoragePolicySnapshot,
        active: Boolean, enabled: Boolean,
    ) {
        if (!editor.canEdit(snapshot, active)) return
        editor.updateAutomatic(enabled)
    }

    fun discard(editor: SettingsStoragePolicyDraftState, snapshot: SettingsStoragePolicySnapshot,
        active: Boolean,
    ) {
        if (!active) return
        editor.discard(snapshot)
    }

    fun beginRefresh(editor: SettingsStoragePolicyDraftState, snapshot: SettingsStoragePolicySnapshot,
        active: Boolean,
    ): Boolean {
        if (!active || editor.token != snapshot.token || snapshot.loading || snapshot.saving || editor.pending != null) return false
        return editor.beginRefresh()
    }

    fun beginSave(editor: SettingsStoragePolicyDraftState, snapshot: SettingsStoragePolicySnapshot,
        active: Boolean,
    ): SettingsStoragePolicyUiRequest? {
        if (!editor.canEdit(snapshot, active) || editor.draft.empty) return null
        val requestId = ++nextRequestId
        val request = SettingsStoragePolicyUiRequest(editor.token, snapshot.revision, editor.draft, requestId,
            editor.localRevision)
        return request.takeIf(editor::beginSave)
    }

    /** The callback talks to the stable session directly and remains valid after UI disposal. */
    fun complete(completion: SettingsStoragePolicyCompletion) {
        val editor = currentEditor?.takeIf { currentGeneration == completion.token.generation } ?: return
        editor.complete(completion)
    }
}

internal data class SettingsStoragePolicyUiRequest(
    val token: SettingsStoragePolicyToken,
    val expectedRevision: Long,
    val draft: SettingsStoragePolicyDraft,
    val requestId: Long,
    val draftRevision: Long,
)

internal class SettingsStoragePolicyDraftState(snapshot: SettingsStoragePolicySnapshot) {
    var token by mutableStateOf(snapshot.token)
    var baselineRevision by mutableStateOf(snapshot.revision)
    var baselineValues by mutableStateOf(snapshot.values)
    var draft by mutableStateOf(SettingsStoragePolicyDraft())
        private set
    private var desired by mutableStateOf(snapshot.values.toDesired())
    private var partialRefreshPending by mutableStateOf(false)
    private var explicitRefreshPending by mutableStateOf(false)
    private var sizeIntent by mutableStateOf(false)
    private var retentionIntent by mutableStateOf(false)
    private var automaticIntent by mutableStateOf(false)
    private var restoreAutomaticIntent by mutableStateOf(false)
    var pending by mutableStateOf<SettingsStoragePolicyUiRequest?>(null)
        private set
    var completion by mutableStateOf<SettingsStoragePolicyCompletion?>(snapshot.lastCompletion)
        private set
    var awaitingSnapshot by mutableStateOf<SettingsStoragePolicyValues?>(null)
        private set
    var conflict by mutableStateOf(false)
        private set
    var refreshRequired by mutableStateOf(snapshot.refreshRequired || !snapshot.values.known || snapshot.readErrors.isNotEmpty())
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var localRevision by mutableStateOf(0L)
        private set
    private var ownerChangedDuringPending by mutableStateOf(false)
    private var lastSnapshot by mutableStateOf(snapshot)

    val changed: Boolean get() = !draft.empty
    val desiredSizeKiB: Long? get() = desired?.sizeKiB
    val desiredUnusedSeconds: Long? get() = desired?.unusedSeconds
    val desiredAutomatic: Boolean? get() = desired?.automatic
    val hasSizeIntent: Boolean get() = sizeIntent
    val hasRetentionIntent: Boolean get() = retentionIntent
    val hasAutomaticIntent: Boolean get() = automaticIntent || restoreAutomaticIntent

    fun canEdit(snapshot: SettingsStoragePolicySnapshot, active: Boolean): Boolean =
        active && token == snapshot.token && snapshot.opened && !snapshot.loading && !snapshot.saving &&
            snapshot.revision == baselineRevision && snapshot.values == baselineValues &&
            snapshot.values.known && snapshot.readErrors.isEmpty() && !snapshot.refreshRequired &&
            !refreshRequired && !conflict && pending == null && awaitingSnapshot == null

    fun updateDraft(next: SettingsStoragePolicyDraft) {
        if (next == draft) return
        draft = next
        localRevision++
        message = null
        completion = null
    }

    fun updateSize(sizeKiB: Long) {
        desired = desired?.copy(sizeKiB = sizeKiB)
        sizeIntent = sizeKiB != baselineValues.sizeKiB.effective
        recomputeDraft()
    }

    fun updateRetention(seconds: Long) {
        desired = desired?.copy(unusedSeconds = seconds)
        retentionIntent = seconds != baselineValues.unusedSeconds.effective
        recomputeDraft()
    }

    fun updateAutomatic(enabled: Boolean) {
        desired = desired?.copy(automatic = enabled)
        automaticIntent = enabled != baselineValues.automatic.effective
        recomputeDraft()
    }

    private fun recomputeDraft() {
        val target = desired ?: return
        updateDraft(SettingsStoragePolicyDraft(
            sizeKiB = target.sizeKiB.takeIf { sizeIntent && it != baselineValues.sizeKiB.effective },
            unusedSeconds = target.unusedSeconds.takeIf { retentionIntent && it != baselineValues.unusedSeconds.effective },
            automatic = target.automatic.takeIf { automaticIntent || restoreAutomaticIntent }
                ?.takeUnless { it == baselineValues.automatic.effective },
        ))
    }

    fun beginSave(request: SettingsStoragePolicyUiRequest): Boolean {
        if (pending != null || request.token != token || request.draftRevision != localRevision) return false
        pending = request
        message = null
        completion = null
        return true
    }

    fun beginRefresh(): Boolean {
        if (pending != null) return false
        completion = null
        explicitRefreshPending = partialRefreshPending
        message = if (explicitRefreshPending) "正在重新读取当前设置；未完成的更改会保留。"
        else if (changed) "正在刷新设置；当前更改会保留。" else null
        return true
    }

    fun discard(snapshot: SettingsStoragePolicySnapshot) {
        if (pending != null || awaitingSnapshot != null || snapshot.token != token) return
        baselineValues = snapshot.values
        baselineRevision = snapshot.revision
        draft = SettingsStoragePolicyDraft()
        desired = snapshot.values.toDesired()
        sizeIntent = false
        retentionIntent = false
        automaticIntent = false
        restoreAutomaticIntent = false
        partialRefreshPending = false
        explicitRefreshPending = false
        awaitingSnapshot = null
        conflict = false
        message = null
        completion = null
        refreshRequired = snapshot.refreshRequired || !snapshot.values.known || snapshot.readErrors.isNotEmpty()
        localRevision++
    }

    fun observe(snapshot: SettingsStoragePolicySnapshot) {
        if (!snapshot.opened || snapshot.token.generation != token.generation || snapshot.loading) return
        lastSnapshot = snapshot
        val pendingRequest = pending
        if (pendingRequest != null) {
            if (snapshot.token != pendingRequest.token) {
                token = snapshot.token
                ownerChangedDuringPending = true
                conflict = true
                refreshRequired = true
                message = "页面或账号已切换，草稿已保留；原请求结束后请刷新当前设置。"
            }
            snapshot.lastCompletion?.takeIf { it.requestId == pendingRequest.requestId && it.token == pendingRequest.token }?.let(::complete)
            return
        }

        val awaiting = awaitingSnapshot
        if (awaiting != null) {
            if (snapshot.token == token && snapshot.values == awaiting && snapshot.readErrors.isEmpty()) {
                baselineValues = snapshot.values
                baselineRevision = snapshot.revision
                awaitingSnapshot = null
                refreshRequired = snapshot.refreshRequired
                conflict = false
            } else if (snapshot.token != token) {
                token = snapshot.token
                refreshRequired = true
                conflict = true
            }
            return
        }

        if (partialRefreshPending && !explicitRefreshPending) {
            if (snapshot.token != token) {
                token = snapshot.token
                conflict = true
                refreshRequired = true
                message = "页面或账号已切换，草稿已保留；请刷新当前设置后再处理。"
            } else {
                refreshRequired = true
            }
            return
        }

        if (explicitRefreshPending && partialRefreshPending && snapshot.values.known && snapshot.readErrors.isEmpty() &&
            !snapshot.refreshRequired) {
            token = snapshot.token
            val previousDesired = desired
            baselineValues = snapshot.values
            baselineRevision = snapshot.revision
            desired = StoragePolicyDesiredValues(
                sizeKiB = if (sizeIntent) previousDesired?.sizeKiB ?: snapshot.values.sizeKiB.effective!! else snapshot.values.sizeKiB.effective!!,
                unusedSeconds = if (retentionIntent) previousDesired?.unusedSeconds ?: snapshot.values.unusedSeconds.effective!! else snapshot.values.unusedSeconds.effective!!,
                automatic = if (automaticIntent || restoreAutomaticIntent) previousDesired?.automatic ?: snapshot.values.automatic.effective!!
                    else snapshot.values.automatic.effective!!,
            )
            if (desired?.automatic == snapshot.values.automatic.effective) restoreAutomaticIntent = false
            refreshRequired = false
            conflict = false
            partialRefreshPending = false
            explicitRefreshPending = false
            recomputeDraft()
            message = "已重新读取当前设置，原更改草稿已保留。"
            return
        } else if (explicitRefreshPending && (!snapshot.values.known || snapshot.readErrors.isNotEmpty() || snapshot.refreshRequired)) {
            explicitRefreshPending = false
            refreshRequired = true
            message = "仍有设置未能读取；草稿已保留，请稍后再刷新。"
            return
        }

        if (snapshot.token != token) {
            if (!changed) {
                acceptFreshSnapshot(snapshot)
            } else if (!ownerChangedDuringPending && snapshot.values == baselineValues && snapshot.readErrors.isEmpty() &&
                !snapshot.refreshRequired) {
                // A new page owner may adopt this draft only after a fresh, exact baseline read.
                token = snapshot.token
                baselineRevision = snapshot.revision
                refreshRequired = false
                conflict = false
                message = "已重新读取当前设置，未提交的更改仍保留。"
            } else {
                token = snapshot.token
                conflict = true
                refreshRequired = true
                message = "当前设置已变化，草稿已保留。请刷新后放弃草稿或确认当前值。"
            }
            ownerChangedDuringPending = false
            return
        }

        if (!changed) {
            acceptFreshSnapshot(snapshot)
        } else if (snapshot.values == baselineValues && snapshot.readErrors.isEmpty() && !snapshot.refreshRequired) {
            // Reads advance the controller revision even when the exact native values are unchanged.
            baselineRevision = snapshot.revision
            refreshRequired = false
            if (!ownerChangedDuringPending) conflict = false
            if (message == "正在刷新设置；当前更改会保留。") message = "已刷新设置；当前更改仍保留。"
        } else {
            refreshRequired = snapshot.refreshRequired || !snapshot.values.known || snapshot.readErrors.isNotEmpty()
            if (snapshot.values != baselineValues) {
                conflict = true
                message = "当前设置已变化，草稿已保留。请刷新后放弃草稿或确认当前值。"
            } else if (refreshRequired) {
                message = "仍有设置未能读取；草稿已保留，请稍后再刷新。"
            }
        }
    }

    private fun acceptFreshSnapshot(snapshot: SettingsStoragePolicySnapshot) {
        token = snapshot.token
        baselineRevision = snapshot.revision
        baselineValues = snapshot.values
        desired = snapshot.values.toDesired()
        sizeIntent = false
        retentionIntent = false
        automaticIntent = false
        restoreAutomaticIntent = false
        refreshRequired = snapshot.refreshRequired || !snapshot.values.known || snapshot.readErrors.isNotEmpty()
        conflict = false
        if (!changed && awaitingSnapshot == null) message = null
    }

    fun complete(received: SettingsStoragePolicyCompletion) {
        val submitted = pending ?: return
        if (received.token != submitted.token || received.requestId != submitted.requestId ||
            submitted.draftRevision != localRevision) return
        pending = null
        completion = received
        captureIncompleteIntent(received)
        val ownerStillMatches = token == received.token && !ownerChangedDuringPending
        if (!ownerStillMatches) {
            conflict = true
            refreshRequired = true
            message = "页面或账号已切换，草稿已保留；请刷新当前设置后再处理。"
            ownerChangedDuringPending = false
            return
        }
        val actual = received.actual
        val confirmed = received.status == SettingsStoragePolicyStatus.CONFIRMED ||
            received.status == SettingsStoragePolicyStatus.UNCHANGED
        if (confirmed && actual?.complete == true && actualMatches(submitted.draft, actual.values)) {
            baselineValues = actual.values
            desired = actual.values.toDesired()
            sizeIntent = false
            retentionIntent = false
            automaticIntent = false
            restoreAutomaticIntent = false
            draft = SettingsStoragePolicyDraft()
            awaitingSnapshot = actual.values
            refreshRequired = false
            conflict = false
            message = if (received.status == SettingsStoragePolicyStatus.CONFIRMED)
                "设置已保存，并已由系统读回确认。" else "当前值与所选内容一致，未产生写入。"
            localRevision++
            if (lastSnapshot.token == received.token && lastSnapshot.values == actual.values &&
                lastSnapshot.readErrors.isEmpty() && !lastSnapshot.loading && !lastSnapshot.saving) {
                baselineRevision = lastSnapshot.revision
                awaitingSnapshot = null
                refreshRequired = lastSnapshot.refreshRequired
            }
        } else {
            if (received.status == SettingsStoragePolicyStatus.CONFLICT ||
                received.status == SettingsStoragePolicyStatus.INCOMPLETE || received.refreshRequired) {
                conflict = received.status == SettingsStoragePolicyStatus.CONFLICT
                refreshRequired = true
            }
            message = when (received.status) {
                SettingsStoragePolicyStatus.CONFLICT -> "保存前发现当前设置已变化；草稿已保留，请刷新后重新确认。"
                SettingsStoragePolicyStatus.INCOMPLETE -> "有些设置未能完成读回确认；草稿已保留，请刷新后重试。"
                SettingsStoragePolicyStatus.REJECTED -> "设置未保存；草稿已保留。请确认账号与页面仍有效后重试。"
                else -> "当前结果无法确认；草稿已保留，请刷新后重试。"
            }
        }
        ownerChangedDuringPending = false
    }

    private fun captureIncompleteIntent(received: SettingsStoragePolicyCompletion) {
        if (received.status != SettingsStoragePolicyStatus.INCOMPLETE) return
        partialRefreshPending = true
        val actualAutomatic = received.actual?.values?.automatic?.takeIf { it.known }?.effective
        val automaticWasOn = baselineValues.automatic.effective == true
        val disabledByThisSave = received.steps.any {
            it.step == SettingsStoragePolicyStep.DISABLE_AUTOMATIC && it.accepted
        }
        val automaticWasConfirmed = received.steps.any {
            it.step == SettingsStoragePolicyStep.ENABLE_AUTOMATIC && it.confirmed
        }
        if (automaticWasOn && disabledByThisSave && desired?.automatic == true && actualAutomatic != true &&
            !automaticWasConfirmed) restoreAutomaticIntent = true
    }

    private fun actualMatches(draft: SettingsStoragePolicyDraft, values: SettingsStoragePolicyValues): Boolean =
        (draft.sizeKiB == null || values.sizeKiB.effective == draft.sizeKiB) &&
            (draft.unusedSeconds == null || values.unusedSeconds.effective == draft.unusedSeconds) &&
            (draft.automatic == null || values.automatic.effective == draft.automatic)
}

private fun SettingsStoragePolicyValues.toDesired(): StoragePolicyDesiredValues? = if (known) StoragePolicyDesiredValues(
    sizeKiB = sizeKiB.effective!!, unusedSeconds = unusedSeconds.effective!!, automatic = automatic.effective!!,
) else null

/** A small in-place editor; native reads and writes are owned by the settings action bridge. */
@Composable
internal fun SettingsStoragePolicyControls(
    snapshot: SettingsStoragePolicySnapshot,
    actions: SettingsStoragePolicyActions,
    session: SettingsStoragePolicyUiSession? = null,
) {
    val active = LocalSettingsPageActive.current
    val localSession = remember { SettingsStoragePolicyUiSession() }
    val draftSession = session ?: localSession
    val latestSnapshot by rememberUpdatedState(snapshot)
    val latestActions by rememberUpdatedState(actions)
    val latestActive by rememberUpdatedState(active)
    val requested = draftSession.expanded
    var lease by remember { mutableStateOf<SettingsStoragePageLease?>(null) }
    val latestLease by rememberUpdatedState(lease)

    DisposableEffect(Unit) {
        onDispose { latestLease?.close(); latestActions.close(latestSnapshot.token) }
    }
    LaunchedEffect(snapshot) { draftSession.observe(snapshot) }
    LaunchedEffect(active, requested, snapshot.opened, snapshot.token.generation, lease) {
        if (!active || !requested) {
            latestLease?.close()
            if (latestSnapshot.opened) latestActions.close(latestSnapshot.token)
        } else if (latestLease?.active() != true) {
            latestLease?.close()
            val next = settingsStoragePageLease()
            lease = next
            latestActions.open(latestSnapshot.token.generation, next::active)
        }
    }

    SettingsGroup("缓存清理策略") {
        SettingsAction(if (requested) "收起缓存占用上限和保留期限" else "缓存占用上限和保留期限",
            enabled = active && !snapshot.saving && draftSession.editor(snapshot.token.generation)?.pending == null) {
            if (active) draftSession.updateExpanded(!requested)
        }
    }
    if (!requested || !active) return

    if (!snapshot.opened) {
        SettingsGroup("缓存占用上限和保留期限") {
            if (snapshot.loading) SettingsRow("正在读取当前设置…")
            snapshot.error?.let { SettingsNote(storagePolicyError(it), error = true) }
            SettingsAction("重新读取设置", enabled = !snapshot.loading && !snapshot.saving) {
                val next = settingsStoragePageLease()
                lease?.close(); lease = next
                actions.open(snapshot.token.generation, next::active)
            }
        }
        return
    }

    val editor = draftSession.editor(snapshot.token.generation)
    if (editor == null || snapshot.loading) {
        SettingsGroup("缓存占用上限和保留期限") {
            SettingsRow(if (snapshot.loading) "正在读取当前清理设置…" else "尚未读取当前清理设置")
            snapshot.error?.let { SettingsNote(storagePolicyError(it), error = true) }
            SettingsAction("刷新当前设置", enabled = !snapshot.loading && !snapshot.saving) {
                actions.refresh(snapshot.token)
            }
        }
        return
    }

    val displayValues = when {
        editor.awaitingSnapshot != null -> editor.awaitingSnapshot!!
        editor.completion?.token == snapshot.token -> editor.completion?.actual?.values ?: snapshot.values
        else -> snapshot.values
    }
    val editable = editor.canEdit(snapshot, active)
    val draft = editor.draft
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsGroup("缓存占用上限和保留期限") {
            if (snapshot.loading) SettingsRow("正在读取当前清理设置…")
            SettingsStoragePolicyCapacity(editor, snapshot, draftSession, active, displayValues, editable)
            SettingsStoragePolicyRetention(editor, snapshot, draftSession, active, displayValues, editable)
        }
        SettingsGroup("自动清理") {
            val automatic = displayValues.automatic
            if (automatic.known) {
                val desiredAutomatic = if (editor.hasAutomaticIntent) editor.desiredAutomatic ?: automatic.effective == true
                    else automatic.effective == true
                SettingsToggle("自动清理缓存", desiredAutomatic, editable) { enabled ->
                    draftSession.setAutomatic(editor, snapshot, active, enabled)
                }
                SettingsNote(if (desiredAutomatic != automatic.effective) "更改待保存" else "当前状态：${policySourceLabel(automatic.source)}")
            } else {
                SettingsRow("自动清理缓存", "状态未知", "未读取实际值；当前未猜测开关状态。")
            }
            SettingsNote("关闭后不会安排之后的自动清理；已经开始的一轮会继续。")
        }
        SettingsGroup("其他清理规则") {
            SettingsNote(storagePolicyOtherRules(displayValues))
        }
        editor.message?.let { SettingsNote(it, error = editor.conflict ||
            editor.completion?.status == SettingsStoragePolicyStatus.CONFLICT ||
            editor.completion?.status == SettingsStoragePolicyStatus.INCOMPLETE ||
            editor.completion?.status == SettingsStoragePolicyStatus.REJECTED) }
        (editor.completion?.steps ?: snapshot.steps).forEach { step ->
            SettingsNote("${policyStepLabel(step.step)}：${when {
                step.confirmed -> "已确认"
                step.accepted -> "正在核实"
                step.error != null -> storagePolicyError(step.error)
                else -> "未完成"
            }}", error = !step.confirmed)
        }
        if (!snapshot.values.known || snapshot.readErrors.isNotEmpty() || snapshot.refreshRequired || editor.refreshRequired) {
            SettingsNote("当前部分设置未知，先刷新后才能安全编辑或保存；草稿会保留。", error = true)
        }
        snapshot.error?.let { SettingsNote(storagePolicyError(it), error = true) }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChatButton("刷新设置", enabled = active && !snapshot.loading && !snapshot.saving && editor.pending == null,
                modifier = Modifier.weight(1f)) {
                    if (draftSession.beginRefresh(editor, snapshot, active)) actions.refresh(snapshot.token)
                }
            ChatButton(if (editor.conflict) "放弃草稿" else "取消更改",
                enabled = active && !editor.draft.empty && editor.pending == null && editor.awaitingSnapshot == null,
                modifier = Modifier.weight(1f)) { draftSession.discard(editor, snapshot, active) }
            ChatButton(if (editor.pending != null || snapshot.saving) "正在保存…" else "保存设置",
                enabled = editable && !editor.draft.empty && editor.pending == null,
                primary = true, modifier = Modifier.weight(1f)) {
                    val request = draftSession.beginSave(editor, latestSnapshot, latestActive) ?: return@ChatButton
                    latestActions.save(request.token, request.expectedRevision, request.draft, request.requestId) { result ->
                        draftSession.complete(result)
                    }
                }
        }
    }
}

@Composable
private fun SettingsStoragePolicyCapacity(
    editor: SettingsStoragePolicyDraftState,
    snapshot: SettingsStoragePolicySnapshot,
    session: SettingsStoragePolicyUiSession,
    active: Boolean,
    values: SettingsStoragePolicyValues,
    editable: Boolean,
) {
    val number = values.sizeKiB
    val displayed = if (editor.hasSizeIntent) editor.desiredSizeKiB else number.effective
    val index = displayed?.let(::nearestStoragePolicyCapIndex) ?: 0
    val exactOption = displayed?.let { value -> storagePolicyCapOptions.any { it.sizeKiB == value } } == true
    val pending = editor.hasSizeIntent && displayed != null && displayed != number.effective
    val text = when {
        !number.known && editor.desiredSizeKiB == null -> "当前值未知"
        pending -> "${storagePolicyCapOptions[index].label} · 待保存"
        else -> "${storagePolicySizeLabel(number.effective!!)} · ${policySourceLabel(number.source)}" +
            if (!exactOption && number.source == SettingsStoragePolicySource.EXPLICIT) " · 当前自定义值" else ""
    }
    SettingsRow("缓存占用上限", text)
    if (number.known) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Slider(value = index.toFloat(), onValueChange = { value ->
                if (editable && value.isFinite()) {
                    val nextIndex = value.roundToInt().coerceIn(storagePolicyCapOptions.indices)
                    if (nextIndex != index) session.setSize(editor, snapshot, active, storagePolicyCapOptions[nextIndex].sizeKiB)
                }
            }, enabled = editable, valueRange = 0f..storagePolicyCapOptions.lastIndex.toFloat(),
                steps = storagePolicyCapOptions.size - 2, modifier = Modifier.fillMaxWidth().semantics {
                    contentDescription = "缓存占用上限"
                    setProgress { value ->
                        if (!editable || !value.isFinite()) false else {
                            val nextIndex = value.roundToInt().coerceIn(storagePolicyCapOptions.indices)
                            if (nextIndex != index) session.setSize(editor, snapshot, active, storagePolicyCapOptions[nextIndex].sizeKiB)
                            true
                        }
                    }
                }.onPreviewKeyEvent { event ->
                    if (!editable || event.isAltPressed || event.isCtrlPressed || event.isMetaPressed ||
                        event.key != Key.DirectionLeft && event.key != Key.DirectionRight) false
                    else {
                        if (event.type == KeyEventType.KeyDown) {
                            val nextIndex = (index + if (event.key == Key.DirectionLeft) -1 else 1)
                                .coerceIn(storagePolicyCapOptions.indices)
                            if (nextIndex != index) session.setSize(editor, snapshot, active, storagePolicyCapOptions[nextIndex].sizeKiB)
                        }
                        true
                    }
                }.focusable(editable))
            SettingsNote("此容量为本账号本地缓存的合计上限，不包括保存到下载目录的副本。")
        }
    } else {
        SettingsNote("容量上限未能读取；先刷新后再编辑。", error = true)
    }
}

@Composable
private fun SettingsStoragePolicyRetention(
    editor: SettingsStoragePolicyDraftState,
    snapshot: SettingsStoragePolicySnapshot,
    session: SettingsStoragePolicyUiSession,
    active: Boolean,
    values: SettingsStoragePolicyValues,
    editable: Boolean,
) {
    val number = values.unusedSeconds
    val desired = if (editor.hasRetentionIntent) editor.desiredUnusedSeconds else number.effective
    val desiredOption = desired?.let { value -> storagePolicyRetentionOptions.firstOrNull { it.seconds == value } }
    if (number.known) {
        val currentOption = storagePolicyRetentionOptions.firstOrNull { it.seconds == number.effective }
        SettingsRow("当前保留期限", currentOption?.label ?: storagePolicyDurationLabel(number.effective!!), policySourceLabel(number.source))
        if (editor.hasRetentionIntent && desired != number.effective) SettingsRow("待保存期限", desiredOption?.label ?: storagePolicyDurationLabel(desired!!))
    } else {
        SettingsRow("当前保留期限", "未知", "读取失败；未选择替代时长。")
    }
    storagePolicyRetentionOptions.forEach { option ->
        val selected = desired == option.seconds
        SettingsRow(option.label, when {
            selected && editor.hasRetentionIntent && desired != number.effective -> "待保存"
            selected && number.known -> "当前"
            else -> null
        }, enabled = editable) {
            session.setRetention(editor, snapshot, active, option.seconds)
        }
    }
    SettingsNote("‘长期保留’最长约 68 年，仍有期限。")
}

private fun nearestStoragePolicyCapIndex(sizeKiB: Long): Int = storagePolicyCapOptions.indices.minBy { index ->
    val candidate = storagePolicyCapOptions[index].sizeKiB
    kotlin.math.abs(candidate - sizeKiB)
}

private fun storagePolicySizeLabel(sizeKiB: Long): String = formatSettingsBytes(sizeKiB * 1024L)

private fun storagePolicyDurationLabel(seconds: Long): String = when {
    seconds == SETTINGS_STORAGE_POLICY_LONG_RETENTION_SECONDS -> "长期保留（约 68 年，有限）"
    seconds % STORAGE_POLICY_SECONDS_PER_DAY == 0L -> "${seconds / STORAGE_POLICY_SECONDS_PER_DAY} 天"
    seconds % 3600L == 0L -> "${seconds / 3600L} 小时"
    seconds % 60L == 0L -> "${seconds / 60L} 分钟"
    else -> "$seconds 秒"
}

private fun policySourceLabel(source: SettingsStoragePolicySource): String = when (source) {
    SettingsStoragePolicySource.EXPLICIT -> "当前已设置"
    SettingsStoragePolicySource.DEFAULT -> "当前默认值"
    SettingsStoragePolicySource.UNKNOWN -> "当前值未知"
}

private fun storagePolicyOtherRules(values: SettingsStoragePolicyValues): String = buildString {
    val count = values.fileCount.takeIf { it.known }?.effective
    val immunity = values.immunitySeconds.takeIf { it.known }?.effective
    append("文件数量限制：${count?.let { "$it 个" } ?: "暂不可用"}；")
    append("新文件保护期：${immunity?.let(::storagePolicyDurationLabel) ?: "暂不可用"}。")
    append("实际占用可能暂时高于上限。")
}

private fun policyStepLabel(step: SettingsStoragePolicyStep): String = when (step) {
    SettingsStoragePolicyStep.DISABLE_AUTOMATIC -> "切换自动清理状态"
    SettingsStoragePolicyStep.SIZE -> "更新缓存容量"
    SettingsStoragePolicyStep.UNUSED_TIME -> "更新保留期限"
    SettingsStoragePolicyStep.ENABLE_AUTOMATIC -> "启用自动清理"
}

private fun storagePolicyError(error: SettingsError): String = when (error) {
    SettingsError.NOT_READY -> "账号或页面已失效，请重新读取设置。"
    SettingsError.REQUEST_FAILED -> "读取或保存未完成，请刷新后重试。"
    SettingsError.RESULT_UNKNOWN -> "请求已提交，结果尚未确认；请先刷新状态"
    SettingsError.FORBIDDEN -> "当前账号不允许更改此设置。"
    SettingsError.RATE_LIMITED -> "操作过于频繁，请稍后重试。"
    SettingsError.INVALID_VALUE -> "所选数值无效，请重新选择。"
    SettingsError.UNSUPPORTED -> "当前连接暂不支持此设置。"
    SettingsError.CONFLICT -> "当前设置已变化，请刷新后重新确认。"
}
