package com.badnng.moe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.badnng.moe.settings.*
import kotlinx.coroutines.flow.MutableStateFlow
import top.yukonga.miuix.kmp.basic.TextField

/** Remains mounted in the notification pane while its owned child pages replace the pane body. */
@Composable
internal fun SettingsNotificationExceptionControls(
    accountToken: SettingsToken,
    snapshot: NotificationExceptionsSnapshot,
    actions: NotificationExceptionsActions,
    active: Boolean = LocalSettingsPageActive.current,
    soundSnapshot: SavedNotificationSoundsSnapshot = SavedNotificationSoundsSnapshot(),
    soundActions: SavedNotificationSoundsActions = SavedNotificationSoundsActions(),
) {
    val pageActive = active && LocalSettingsPageActive.current
    var requestedScope by remember(accountToken.generation) { mutableStateOf<NotificationScope?>(null) }
    val lease = remember(pageActive, accountToken, requestedScope) {
        NotificationExceptionsPageLease(accountToken.generation, requestedScope, snapshot, pageActive)
    }
    val latestActions = rememberUpdatedState(actions)
    val latestSnapshot = rememberUpdatedState(snapshot)
    val latestPageActive = rememberUpdatedState(pageActive)
    // Keep the outgoing drawing and its draft while the page becomes inert. The new lease still
    // cannot act on this token until it independently observes its newly opened owner.
    var ownedToken by remember(accountToken.generation, requestedScope) { mutableStateOf<NotificationExceptionsToken?>(null) }
    SideEffect {
        lease.observe(snapshot)?.let { if (ownedToken != it) ownedToken = it }
        val owned = ownedToken
        // Header/Escape navigation can close the controller without this composable's button.
        // Only a lease that already claimed an open page may recognize its retirement. An
        // opening request, inactive page or older native owner must not clear this intention.
        if (pageActive && owned != null && lease.owns(owned) && !snapshot.opened &&
            snapshot.token.generation == owned.generation && snapshot.token.scope == owned.scope &&
            snapshot.token.owner >= owned.owner) {
            lease.close()
            requestedScope = null
        }
    }
    DisposableEffect(lease) {
        requestedScope?.let { scope ->
            if (lease.active()) latestActions.value.open(accountToken, scope, lease::active)
        }
        onDispose { lease.close()?.let { latestActions.value.close(it) } }
    }

    fun current(): NotificationExceptionsSnapshot? {
        val latest = latestSnapshot.value
        return latest.takeIf { latestPageActive.value && lease.owns(it.token) && it.opened &&
            it.token == ownedToken && it.token == snapshot.token && it.token.generation == accountToken.generation }
    }
    val owned = snapshot.opened && ownedToken == snapshot.token
    if (!owned) {
        SettingsGroup("聊天通知例外") {
            NotificationScope.entries.forEach { scope ->
                SettingsRow("${scope.exceptionLabel()}通知例外", if (requestedScope == scope && pageActive) "正在打开…" else "管理",
                    enabled = pageActive && requestedScope == null) { requestedScope = scope }
            }
            if (requestedScope != null) SettingsAction("取消打开例外", enabled = pageActive) {
                lease.close()?.let { latestActions.value.close(it) }
                requestedScope = null
            }
        }
        return
    }

    val interactive = pageActive && lease.owns(snapshot.token)
    val busy = snapshot.loading || snapshot.saving || snapshot.preparingClear
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        SettingsGroup("${snapshot.token.scope.exceptionLabel()}通知例外") {
            SettingsAction(if (snapshot.saving || snapshot.view == NotificationExceptionsView.LIST) "关闭通知例外" else "返回例外列表",
                enabled = interactive) {
                current()?.let {
                    if (it.saving || it.view == NotificationExceptionsView.LIST) {
                        lease.close()?.let { token -> latestActions.value.close(token) }
                        requestedScope = null
                    } else latestActions.value.back(it.token)
                }
            }
            if (snapshot.loading) SettingsNote("正在读取通知例外…")
            if (snapshot.saving) {
                SettingsNote("正在提交通知例外更改…")
                SettingsNote("已提交的更改仍会处理。")
            }
            if (snapshot.readbackPending) SettingsNote("更改已提交，尚未读回当前设置。")
            snapshot.error?.let { SettingsNote(settingsErrorText(it), error = true) }
        }
        when (snapshot.view) {
            NotificationExceptionsView.LIST -> SettingsExceptionList(snapshot, interactive && !busy && !snapshot.readbackPending,
                canRefresh = interactive && !snapshot.loading && !snapshot.saving,
                onEdit = { id -> current()?.takeIf { !it.loading && !it.saving }?.let { latestActions.value.edit(it.token, id) } },
                onAdd = { current()?.takeIf { !it.loading && !it.saving }?.let { latestActions.value.select(it.token) } },
                onRefresh = { current()?.takeIf { !it.loading && !it.saving }?.let { latestActions.value.refresh(it.token) } },
                onMore = { current()?.takeIf { !it.loading && !it.saving && it.hasMore }?.let { latestActions.value.more(it.token) } },
                onClear = { current()?.takeIf { !it.loading && !it.saving && !it.preparingClear }?.let { latestActions.value.prepareClear(it.token) } })
            NotificationExceptionsView.SELECTOR -> SettingsExceptionSelector(snapshot, interactive && !busy,
                onSearch = { query, server -> current()?.takeIf { !it.saving }?.let { latestActions.value.search(it.token, query, server) } },
                onEdit = { id -> current()?.takeIf { !it.saving && !it.searching }?.let { latestActions.value.edit(it.token, id) } })
            NotificationExceptionsView.EDITOR -> SettingsExceptionEditor(snapshot, interactive && !busy && !snapshot.readbackPending,
                canReload = interactive && !snapshot.loading && !snapshot.saving,
                onReload = { current()?.takeIf { !it.loading && !it.saving }?.let { latestActions.value.reloadEditor(it.token) } },
                onSave = { revision, edit -> current()?.takeIf { !it.loading && !it.saving && !it.readbackPending &&
                    !it.refreshRequired && it.editorRevision == revision && it.editor?.chatId == edit.expected.chatId }
                    ?.let { latestActions.value.save(it.token, revision, edit) } },
                accountToken = accountToken, soundSnapshot = soundSnapshot, soundActions = soundActions)
        }
    }
    val plan = snapshot.clearPlan
    SecurityConfirmation(show = interactive && plan != null, title = "清除此分类的通知例外？", maxWidth = 420.dp,
        onDismissRequest = { current()?.takeIf { !it.saving }?.let { latestActions.value.cancelClear(it.token) } }) {
        if (plan != null) Column(Modifier.fillMaxWidth().heightIn(max = 300.dp)
            .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val displayedTargets = remember(plan) {
                buildList {
                    for (peer in plan.targets) {
                        if (peer.chatId in plan.failures) add(peer)
                        if (size == 12) break
                    }
                    if (size < 12) for (peer in plan.targets) {
                        if (peer.chatId !in plan.failures) add(peer)
                        if (size == 12) break
                    }
                }
            }
            val completedCount = plan.targets.count { it.chatId in plan.completed }
            val failureCount = plan.targets.count { it.chatId in plan.failures }
            SettingsNote("只恢复本次已核实的 ${plan.targets.size} 个聊天的消息通知默认值。故事通知保持原样。")
            SettingsNote("已完成 $completedCount 项，失败 $failureCount 项。")
            displayedTargets.forEach { peer ->
                SettingsRow(peer.title, when {
                    peer.chatId in plan.completed -> "已读回默认值"
                    peer.chatId in plan.failures -> "未完成"
                    else -> "待清除"
                }, summary = plan.failures[peer.chatId]?.let(::settingsErrorText))
            }
            val hiddenCount = plan.targets.size - displayedTargets.size
            if (hiddenCount > 0) SettingsNote("另有 $hiddenCount 个目标未在此展开，仍属于本次清除范围。")
            if (plan.unresolved.isNotEmpty()) SettingsNote("另有 ${plan.unresolved.size} 项未核实，不会清除。", error = true)
            SettingsNote("确认后新增的例外不属于本次目标。")
            if (snapshot.saving) {
                SettingsNote("已提交的更改仍会处理。")
                ChatButton("停止后续清除并关闭") {
                    current()?.takeIf { it.saving && it.clearPlan?.id == plan.id }?.let {
                        lease.close()?.let { token -> latestActions.value.close(token) }
                        requestedScope = null
                    }
                }
            }
            ChatButton("取消清除", enabled = !snapshot.saving) {
                current()?.takeIf { !it.saving && it.clearPlan?.id == plan.id }?.let { latestActions.value.cancelClear(it.token) }
            }
            val remaining = plan.targets.any { it.chatId !in plan.completed }
            ChatButton(if (plan.failures.isEmpty()) "确认清除例外" else "重试本次未完成项", primary = true,
                enabled = !snapshot.saving && remaining) {
                current()?.takeIf { !it.saving && it.clearPlan?.id == plan.id }?.let { latestActions.value.clear(it.token, plan.id) }
            }
        }
    }
}

@Composable
private fun SettingsExceptionList(snapshot: NotificationExceptionsSnapshot, enabled: Boolean, canRefresh: Boolean,
    onEdit: (Long) -> Unit, onAdd: () -> Unit, onRefresh: () -> Unit, onMore: () -> Unit, onClear: () -> Unit) {
    SettingsGroup("例外聊天") {
        SettingsRow("通知例外数量", snapshot.totalIds?.toString() ?: "尚未读取",
            summary = "已解析 ${snapshot.rows.count { it.peer != null }} 项；未核实的聊天不会被自动修改。")
        SettingsAction("添加通知例外", enabled, onClick = onAdd)
        if (!snapshot.loading && snapshot.totalIds == 0) SettingsNote("此分类没有新消息通知例外。")
        snapshot.rows.forEach { row -> ExceptionPeerRow(row, enabled, onEdit) }
        if (snapshot.hasMore) SettingsAction("加载更多例外", enabled, onClick = onMore)
        SettingsAction("重新读取例外列表", enabled = canRefresh, onClick = onRefresh)
        if ((snapshot.totalIds ?: 0) > 1) SettingsAction("清除此分类例外", enabled, destructive = true, onClick = onClear)
        if (snapshot.preparingClear) SettingsNote("正在核实本次清除目标…")
    }
}

@Composable
private fun ExceptionPeerRow(row: NotificationExceptionRow, enabled: Boolean, onEdit: (Long) -> Unit) {
    val peer = row.peer
    SettingsRow(peer?.title ?: "未核实的聊天", if (peer == null) "不可编辑" else null,
        summary = row.error?.let(::settingsErrorText) ?: peer?.values?.let(::exceptionSummary),
        enabled = enabled && peer?.editable == true, onClick = if (peer?.editable == true) ({ onEdit(row.chatId) }) else null)
}

@Composable
private fun SettingsExceptionSelector(snapshot: NotificationExceptionsSnapshot, enabled: Boolean,
    onSearch: (String, Boolean) -> Unit, onEdit: (Long) -> Unit) {
    var query by remember(snapshot.token) { mutableStateOf(TextFieldValue(snapshot.query)) }
    SettingsGroup("添加通知例外") {
        TextField(query, { value ->
            if (value.text.length <= 128) { query = value; onSearch(value.text, false) }
        }, label = "搜索现有聊天", singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth().padding(6.dp))
        SettingsAction("搜索服务器上的聊天", enabled && query.text.isNotBlank() && !snapshot.searching) {
            onSearch(query.text, true)
        }
        SettingsNote("选择要单独设置通知的聊天。")
        if (snapshot.searching) SettingsNote("正在搜索聊天…")
        snapshot.searchError?.let { SettingsNote(settingsErrorText(it), error = true) }
        if (!snapshot.searching && snapshot.candidates.isEmpty()) SettingsNote("没有可选择的聊天。")
        snapshot.candidates.forEach { ExceptionPeerRow(it, enabled && !snapshot.searching, onEdit) }
        SettingsAction("重试聊天搜索", enabled && !snapshot.searching) { onSearch(query.text, false) }
    }
}

@Composable
private fun SettingsExceptionEditor(snapshot: NotificationExceptionsSnapshot, enabled: Boolean, canReload: Boolean,
    onReload: () -> Unit, onSave: (Long, NotificationExceptionEdit) -> Unit,
    accountToken: SettingsToken, soundSnapshot: SavedNotificationSoundsSnapshot, soundActions: SavedNotificationSoundsActions,
) {
    val peer = snapshot.editor
    if (peer == null) {
        SettingsGroup("聊天通知设置") {
            SettingsNote("聊天通知设置尚未读取。")
            snapshot.editorError?.let { SettingsNote(settingsErrorText(it), error = true) }
            SettingsAction("重新读取聊天设置", canReload, onClick = onReload)
        }
        return
    }
    // Native updates only flag uncertainty. Only a controller-issued new read revision replaces this base.
    val base = remember(snapshot.token, peer.chatId, snapshot.editorRevision) { peer }
    var draft by remember(snapshot.token, peer.chatId, snapshot.editorRevision) { mutableStateOf(base.values) }
    var choosingSound by remember(snapshot.token, peer.chatId, snapshot.editorRevision) { mutableStateOf(false) }
    val editable = enabled && peer.editable && !snapshot.refreshRequired
    val latestEditable = rememberUpdatedState(editable)
    SettingsGroup(base.title) {
        SettingsNote("未更改的项目和故事通知会保留其他设置。")
        if (snapshot.refreshRequired) SettingsNote("聊天设置已变化，当前草稿仍保留。重新读取会替换此草稿。", error = true)
        snapshot.editorError?.let { SettingsNote(settingsErrorText(it), error = true) }
        SettingsAction("重新读取聊天设置", canReload, onClick = onReload)
    }
    SettingsGroup("显示通知") {
        ExceptionChoice("通知：使用分类默认值", draft.useDefaultMuteFor, editable) { draft = draft.copy(useDefaultMuteFor = true) }
        ExceptionChoice("通知：开启", !draft.useDefaultMuteFor && draft.muteFor == 0, editable) {
            draft = draft.copy(useDefaultMuteFor = false, muteFor = 0)
        }
        ExceptionChoice("通知：关闭", !draft.useDefaultMuteFor && draft.muteFor > 0, editable) {
            draft = draft.copy(useDefaultMuteFor = false, muteFor = Int.MAX_VALUE)
        }
        if (!draft.useDefaultMuteFor && draft.muteFor in 1 until Int.MAX_VALUE) SettingsNote("当前静音剩余 ${draft.muteFor} 秒；未操作此项时保留原截止时间。")
    }
    SettingsGroup("声音") {
        ExceptionChoice("声音：使用分类默认值", draft.useDefaultSound || draft.soundId == -1L, editable) {
            draft = draft.copy(useDefaultSound = true)
        }
        ExceptionChoice("声音：无声", !draft.useDefaultSound && draft.soundId == 0L, editable) {
            draft = draft.copy(useDefaultSound = false, soundId = 0L)
        }
        if (!base.values.useDefaultSound && base.values.soundId != 0L && base.values.soundId != -1L)
            ExceptionChoice("声音：保留当前自定义声音", !draft.useDefaultSound && draft.soundId == base.values.soundId, editable) {
                draft = draft.copy(useDefaultSound = false, soundId = base.values.soundId)
            }
        SettingsAction("选择聊天提示音", enabled = editable) { choosingSound = true }
    }
    ExceptionBooleanGroup("消息预览", draft.useDefaultShowPreview, draft.showPreview, editable) { inherit, value ->
        draft = draft.copy(useDefaultShowPreview = inherit, showPreview = value)
    }
    ExceptionBooleanGroup("置顶消息提醒", draft.useDefaultDisablePinnedMessageNotifications,
        !draft.disablePinnedMessageNotifications, editable) { inherit, value ->
        draft = draft.copy(useDefaultDisablePinnedMessageNotifications = inherit, disablePinnedMessageNotifications = !value)
    }
    ExceptionBooleanGroup("提及提醒", draft.useDefaultDisableMentionNotifications,
        !draft.disableMentionNotifications, editable) { inherit, value ->
        draft = draft.copy(useDefaultDisableMentionNotifications = inherit, disableMentionNotifications = !value)
    }
    SettingsGroup("保存更改") {
        val dirty = notificationExceptionDirtyFields(base.values, draft)
        SettingsAction("保存通知例外", editable && dirty.isNotEmpty()) {
            onSave(snapshot.editorRevision, NotificationExceptionEdit(base, draft, dirty))
        }
        val defaults = base.values.newMessageDefaults()
        val resetDirty = notificationExceptionDirtyFields(base.values, defaults)
        SettingsAction("恢复此聊天的默认通知", editable && resetDirty.isNotEmpty(), destructive = true) {
            onSave(snapshot.editorRevision, NotificationExceptionEdit(base, defaults, resetDirty))
        }
    }
    SettingsSavedNotificationSoundPicker(choosingSound, accountToken, soundSnapshot, soundActions,
        selectedSoundId = if (draft.useDefaultSound) -1L else draft.soundId,
        onSelected = { soundId ->
            if (latestEditable.value) draft = if (soundId == -1L) draft.copy(useDefaultSound = true)
                else draft.copy(useDefaultSound = false, soundId = soundId)
            choosingSound = false
        }, onDismiss = { choosingSound = false }, active = editable,
        volumeTarget = NotificationSoundVolumeTarget.Chat(base.chatId))
}

@Composable
private fun ExceptionChoice(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) =
    SettingsAccountChoice(label, selected, enabled, onClick)

@Composable
private fun ExceptionBooleanGroup(label: String, inherit: Boolean, value: Boolean, enabled: Boolean,
    onChange: (Boolean, Boolean) -> Unit) {
    SettingsGroup(label) {
        ExceptionChoice("$label：使用分类默认值", inherit, enabled) { onChange(true, value) }
        ExceptionChoice("$label：开启", !inherit && value, enabled) { onChange(false, true) }
        ExceptionChoice("$label：关闭", !inherit && !value, enabled) { onChange(false, false) }
    }
}

/** Defaulted hidden values are not edits; all five groups compare their actual native semantics. */
internal fun notificationExceptionDirtyFields(base: ChatNotificationValues, desired: ChatNotificationValues) = buildSet {
    fun different(aDefault: Boolean, bDefault: Boolean, sameValue: Boolean) = aDefault != bDefault || !bDefault && !sameValue
    if (different(base.useDefaultMuteFor, desired.useDefaultMuteFor, base.muteFor == desired.muteFor)) add(NotificationExceptionField.MUTE)
    val baseSoundDefault = base.useDefaultSound || base.soundId == -1L
    val desiredSoundDefault = desired.useDefaultSound || desired.soundId == -1L
    if (different(baseSoundDefault, desiredSoundDefault, base.soundId == desired.soundId)) add(NotificationExceptionField.SOUND)
    if (different(base.useDefaultShowPreview, desired.useDefaultShowPreview, base.showPreview == desired.showPreview)) add(NotificationExceptionField.PREVIEW)
    if (different(base.useDefaultDisablePinnedMessageNotifications, desired.useDefaultDisablePinnedMessageNotifications,
            base.disablePinnedMessageNotifications == desired.disablePinnedMessageNotifications)) add(NotificationExceptionField.PINNED)
    if (different(base.useDefaultDisableMentionNotifications, desired.useDefaultDisableMentionNotifications,
            base.disableMentionNotifications == desired.disableMentionNotifications)) add(NotificationExceptionField.MENTIONS)
}

private fun NotificationScope.exceptionLabel() = when (this) {
    NotificationScope.PRIVATE_CHATS -> "私聊"
    NotificationScope.GROUP_CHATS -> "群组"
    NotificationScope.CHANNEL_CHATS -> "频道"
}

private fun exceptionSummary(value: ChatNotificationValues) = buildList {
    if (!value.useDefaultMuteFor) add(if (value.muteFor == 0) "通知开启" else "通知关闭")
    if (!value.useDefaultSound && value.soundId != -1L) add(if (value.soundId == 0L) "无声" else "自定义声音")
    if (!value.useDefaultShowPreview) add(if (value.showPreview) "显示预览" else "隐藏预览")
    if (!value.useDefaultDisablePinnedMessageNotifications) add(if (value.disablePinnedMessageNotifications) "关闭置顶提醒" else "开启置顶提醒")
    if (!value.useDefaultDisableMentionNotifications) add(if (value.disableMentionNotifications) "关闭提及提醒" else "开启提及提醒")
}.joinToString(" · ").ifEmpty { "消息通知使用分类默认值" }

private class NotificationExceptionsPageLease(
    private val generation: Long,
    private val scope: NotificationScope?,
    initial: NotificationExceptionsSnapshot,
    initiallyActive: Boolean,
) {
    private val initialToken = initial.token
    private val initiallyOpened = initial.opened
    private val alive = MutableStateFlow(initiallyActive && scope != null)
    private val token = MutableStateFlow<NotificationExceptionsToken?>(null)
    fun active() = alive.value
    fun owns(candidate: NotificationExceptionsToken) = active() && token.value == candidate
    fun observe(snapshot: NotificationExceptionsSnapshot): NotificationExceptionsToken? {
        if (!active() || !snapshot.opened || snapshot.token.generation != generation || snapshot.token.scope != scope) return token.value
        if (token.value == null && (snapshot.token != initialToken || !initiallyOpened)) token.value = snapshot.token
        return token.value
    }
    fun close(): NotificationExceptionsToken? {
        if (!alive.compareAndSet(true, false)) return null
        return token.value
    }
}

