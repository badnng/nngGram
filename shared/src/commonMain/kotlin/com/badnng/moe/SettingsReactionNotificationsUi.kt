package com.badnng.moe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.badnng.moe.settings.*
import kotlinx.coroutines.flow.MutableStateFlow
import top.yukonga.miuix.kmp.basic.Switch

/** One lease covers the entry and its detail page; navigation never reacquires the native owner. */
internal class ReactionNotificationsPageLease(
    private val generation: Long,
    private val initial: ReactionNotificationsToken,
    active: Boolean,
) {
    private val alive = MutableStateFlow(active)
    private val owned = MutableStateFlow<ReactionNotificationsToken?>(null)
    fun active() = alive.value
    fun observe(snapshot: ReactionNotificationsSnapshot): ReactionNotificationsToken? {
        if (active() && owned.value == null && snapshot.opened && snapshot.token.generation == generation &&
            snapshot.token.owner > 0 && snapshot.token != initial &&
            (initial.generation != generation || snapshot.token.owner > initial.owner)) {
            owned.value = snapshot.token
        }
        return owned.value
    }
    fun close(): ReactionNotificationsToken? = if (alive.compareAndSet(true, false)) owned.value else null
}

/** Dispatch is synchronous so two clicks before recomposition cannot create two native writes. */
internal class ReactionNotificationsUiIntent {
    var contentRevision by mutableStateOf(0L)
        private set
    var pending by mutableStateOf<ReactionNotificationsEdit?>(null)
        private set
    var failed by mutableStateOf<ReactionNotificationPatch?>(null)
        private set
    private var afterAttempt = 0L
    private var accepted: ReactionNotificationsEdit? = null

    fun submit(snapshot: ReactionNotificationsSnapshot, patch: ReactionNotificationPatch): ReactionNotificationsEdit? {
        if (pending != null || !snapshot.reactionEditable() || patch.isEmpty) return null
        val value = snapshot.native.value ?: return null
        if (patch.applyTo(value) == value) return null
        val edit = ReactionNotificationsEdit(snapshot.native, patch, ++contentRevision)
        afterAttempt = snapshot.outcome?.attemptId ?: 0L
        pending = edit
        failed = null
        accepted = null
        return edit
    }

    fun reconcile(snapshot: ReactionNotificationsSnapshot) {
        val edit = pending
        val outcome = snapshot.outcome?.takeIf { edit != null && it.attemptId > afterAttempt && it.edit == edit }
        if (edit != null && outcome != null) {
            pending = null
            failed = edit.patch.takeUnless { outcome.locallyAccepted && outcome.error == null }
            accepted = edit.takeIf { outcome.locallyAccepted && outcome.error == null && snapshot.awaitingUpdate }
        }
        accepted?.let { previous ->
            if (!snapshot.awaitingUpdate && !snapshot.saving) {
                val desired = previous.expected.value?.let(previous.patch::applyTo)
                if (snapshot.refreshRequired || snapshot.native.error != null || snapshot.native.value != desired) failed = previous.patch
                accepted = null
            }
        }
    }
}

private fun ReactionNotificationsSnapshot.reactionEditable() = opened && native.value != null && native.revision > 0 &&
    native.error == null && !saving && activeAttempt == null && !awaitingUpdate && !refreshRequired

private enum class ReactionSourceField { MESSAGE, POLL }
private data class ReactionSourcePicker(
    val token: ReactionNotificationsToken,
    val native: ReactionNotificationNativeState,
    val field: ReactionSourceField,
)

@Composable
internal fun SettingsReactionNotificationsHost(
    accountToken: SettingsToken,
    snapshot: ReactionNotificationsSnapshot,
    actions: ReactionNotificationsActions,
    active: Boolean,
    content: @Composable (entry: @Composable () -> Unit) -> Unit,
) {
    val pageActive = active && LocalSettingsPageActive.current
    val lease = remember(accountToken, pageActive) {
        ReactionNotificationsPageLease(accountToken.generation, snapshot.token, pageActive)
    }
    val latestActions by rememberUpdatedState(actions)
    val latestSnapshot by rememberUpdatedState(snapshot)
    val latestActive by rememberUpdatedState(pageActive)
    var owned by remember(lease) { mutableStateOf<ReactionNotificationsToken?>(null) }
    var details by remember(lease) { mutableStateOf(false) }
    var picker by remember(lease) { mutableStateOf<ReactionSourcePicker?>(null) }
    var selected by remember(lease) { mutableStateOf(ReactionNotificationSource.ALL) }
    val intent = remember(lease) { ReactionNotificationsUiIntent() }
    SideEffect {
        lease.observe(snapshot)?.let { if (owned != it) owned = it }
        if (snapshot.token == owned) intent.reconcile(snapshot)
    }
    DisposableEffect(lease) {
        if (lease.active()) latestActions.open(accountToken, lease::active)
        onDispose { lease.close()?.let { latestActions.close(it) } }
    }

    fun current(): ReactionNotificationsSnapshot? = latestSnapshot.takeIf {
        latestActive && lease.active() && it.opened && it.token == owned &&
            it.token.generation == accountToken.generation
    }
    fun send(patch: ReactionNotificationPatch, frozen: ReactionNotificationNativeState? = null) {
        val fresh = current() ?: return
        if (frozen != null && fresh.native != frozen) return
        val edit = intent.submit(fresh, patch) ?: return
        latestActions.save(fresh.token, edit)
    }

    val matches = pageActive && lease.active() && owned == snapshot.token && snapshot.opened
    val value = snapshot.native.value.takeIf { matches }
    val editable = matches && snapshot.reactionEditable() && intent.pending == null
    val refreshEnabled = matches && !snapshot.saving && snapshot.activeAttempt == null && intent.pending == null
    val target = picker
    val pickerCurrent = target != null && details && editable && target.token == snapshot.token && target.native == snapshot.native
    LaunchedEffect(pickerCurrent) { if (!pickerCurrent) picker = null }

    @Composable fun Status() {
        when {
            !matches || value == null -> SettingsNote("反应通知设置尚未读取。")
            snapshot.native.error != null -> SettingsNote("当前反应通知设置暂不可用。", error = true)
            snapshot.saving || intent.pending != null -> SettingsNote("正在提交更改…")
            snapshot.awaitingUpdate -> SettingsNote("更改已提交，正在等待当前设置更新。")
            snapshot.refreshRequired -> SettingsNote("设置已发生变化，请查看当前设置后再更改。", error = true)
        }
        if (matches) (snapshot.error ?: snapshot.native.error)?.let { SettingsNote(settingsErrorText(it), error = true) }
        intent.failed?.let { patch ->
            SettingsNote("未能提交：${patch.reactionIntentLabel()}。", error = true)
            SettingsAction("重试反应通知更改", enabled = editable) { send(patch) }
        }
        if (matches && (value == null || snapshot.refreshRequired || snapshot.error != null || snapshot.native.error != null)) {
            SettingsAction("查看当前反应设置", enabled = refreshEnabled) {
                val fresh = current() ?: return@SettingsAction
                if (fresh.saving || fresh.activeAttempt != null || intent.pending != null) return@SettingsAction
                latestActions.refreshFromCache(fresh.token)
            }
        }
    }

    if (!details) {
        content {
            SettingsGroup("反应通知") {
                ReactionNotificationSplitRow("反应和投票", value?.let {
                    it.message != ReactionNotificationSource.NONE || it.poll != ReactionNotificationSource.NONE
                }, value?.let { current -> buildList {
                    if (current.message != ReactionNotificationSource.NONE) add("消息反应")
                    if (current.poll != ReactionNotificationSource.NONE) add("投票")
                }.joinToString("、").ifBlank { "点击更改" } } ?: "尚未读取", editable, matches,
                    onToggle = { enabled ->
                        if (details) return@ReactionNotificationSplitRow
                        val source = if (enabled) ReactionNotificationSource.ALL else ReactionNotificationSource.NONE
                        send(ReactionNotificationPatch(message = source, poll = source), snapshot.native)
                    }, onBody = { if (current() != null) details = true })
                Status()
            }
        }
    } else {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            SettingsAction("返回通知设置", enabled = matches) { picker = null; details = false }
            SettingsGroup("通知我的内容") {
                ReactionSourceField.entries.forEach { field ->
                    val source = value?.let { if (field == ReactionSourceField.MESSAGE) it.message else it.poll }
                    fun patch(source: ReactionNotificationSource) = if (field == ReactionSourceField.MESSAGE)
                        ReactionNotificationPatch(message = source) else ReactionNotificationPatch(poll = source)
                    ReactionNotificationSplitRow(if (field == ReactionSourceField.MESSAGE) "消息反应" else "投票",
                        source?.let { it != ReactionNotificationSource.NONE }, source?.reactionSourceLabel() ?: "尚未读取",
                        editable, editable,
                        onToggle = { if (details) send(patch(if (it) ReactionNotificationSource.ALL else ReactionNotificationSource.NONE), snapshot.native) },
                        onBody = {
                            if (!details) return@ReactionNotificationSplitRow
                            val fresh = current() ?: return@ReactionNotificationSplitRow
                            if (!fresh.reactionEditable() || intent.pending != null || fresh.native != snapshot.native) return@ReactionNotificationSplitRow
                            if (source == ReactionNotificationSource.NONE) send(patch(ReactionNotificationSource.ALL), fresh.native)
                            else if (source != null) {
                                selected = source
                                picker = ReactionSourcePicker(fresh.token, fresh.native, field)
                            }
                        })
                }
            }
            SettingsGroup("反应通知设置") {
                if (value == null) SettingsRow("显示发送者", "尚未读取")
                else SettingsToggle("显示发送者", value.showSender, enabled = editable) {
                    if (details) send(ReactionNotificationPatch(showSender = it), snapshot.native)
                }
                SettingsNote("在通知中显示发送者和表情。")
                Status()
            }
        }
    }

    LocalSettingsSecurityConfirmationHost.current.Content(pickerCurrent, "反应通知来源", null, 400.dp,
        onDismissRequest = { if (picker === target) picker = null }) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(ReactionNotificationSource.ALL, ReactionNotificationSource.CONTACTS).forEach { source ->
                SettingsAccountChoice(source.reactionSourceLabel(), selected == source, pickerCurrent) {
                    if (picker === target && details) selected = source
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ChatButton("取消", Modifier.weight(1f), onClick = { if (picker === target) picker = null })
                ChatButton("确定", Modifier.weight(1f), enabled = pickerCurrent, primary = true, onClick = {
                    if (target == null || picker !== target || !details) return@ChatButton
                    val fresh = current()
                    if (fresh != null && fresh.token == target.token && fresh.native == target.native &&
                        selected != ReactionNotificationSource.NONE) {
                        send(if (target.field == ReactionSourceField.MESSAGE) ReactionNotificationPatch(message = selected)
                            else ReactionNotificationPatch(poll = selected), target.native)
                    }
                    picker = null
                })
            }
        }
    }
}

/** Two sibling targets: the Miuix switch never bubbles into the settings body. Unknown has no fake switch. */
@Composable
private fun ReactionNotificationSplitRow(label: String, checked: Boolean?, value: String, toggleEnabled: Boolean,
    bodyEnabled: Boolean, onToggle: (Boolean) -> Unit, onBody: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (checked != null) Switch(checked, onCheckedChange = onToggle, enabled = toggleEnabled,
            modifier = Modifier.padding(start = 10.dp, end = 4.dp).semantics { contentDescription = "$label 开关" })
        Column(Modifier.weight(1f)) { SettingsRow(label, value, enabled = bodyEnabled, onClick = onBody) }
    }
}

private fun ReactionNotificationSource.reactionSourceLabel() = when (this) {
    ReactionNotificationSource.NONE -> "没有人"
    ReactionNotificationSource.CONTACTS -> "我的联系人"
    ReactionNotificationSource.ALL -> "所有人"
}

private fun ReactionNotificationPatch.reactionIntentLabel() = buildList {
    message?.let { add("消息反应：${it.reactionSourceLabel()}") }
    poll?.let { add("投票：${it.reactionSourceLabel()}") }
    showSender?.let { add(if (it) "显示发送者" else "隐藏发送者") }
}.joinToString("；")
