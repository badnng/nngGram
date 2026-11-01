package com.badnng.moe

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badnng.moe.settings.LocalLockActions
import com.badnng.moe.settings.LocalLockIssue
import com.badnng.moe.settings.LocalLockSnapshot
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField

private enum class LocalLockSettingsStep { HOME, CHANGE_VERIFY, CHANGE, REMOVE_VERIFY, REMOVE_CONFIRM, AUTO_LOCK }

@Composable
internal fun SettingsLocalLockPage(snapshot: LocalLockSnapshot, actions: LocalLockActions,
    active: Boolean, onBack: () -> Unit, modifier: Modifier = Modifier) {
    var step by remember(snapshot.epoch) { mutableStateOf(LocalLockSettingsStep.HOME) }
    var pendingRevision by remember(snapshot.epoch) { mutableStateOf<Long?>(null) }
    val latestSnapshot = rememberUpdatedState(snapshot)
    val latestActions = rememberUpdatedState(actions)
    val latestActive = rememberUpdatedState(active)
    val latestBack = rememberUpdatedState(onBack)
    DisposableEffect(Unit) { onDispose { latestActions.value.closeManagement() } }
    LaunchedEffect(active) { if (!active) { actions.closeManagement(); step = LocalLockSettingsStep.HOME } }
    LaunchedEffect(snapshot.operationRevision, snapshot.busy, snapshot.managementAuthorized, snapshot.enabled) {
        if (pendingRevision != null && snapshot.operationRevision != pendingRevision && !snapshot.busy) pendingRevision = null
        if (!snapshot.busy && (!snapshot.enabled || !snapshot.managementAuthorized) &&
            step in setOf(LocalLockSettingsStep.CHANGE, LocalLockSettingsStep.REMOVE_CONFIRM, LocalLockSettingsStep.AUTO_LOCK)) {
            step = LocalLockSettingsStep.HOME
        }
    }
    fun close() {
        actions.cancel()
        actions.closeManagement()
        latestBack.value()
    }
    val enabled = active && !snapshot.busy && snapshot.retryAfterMillis <= 0 && pendingRevision == null
    val freshVerification = step == LocalLockSettingsStep.CHANGE_VERIFY || step == LocalLockSettingsStep.REMOVE_VERIFY
    Column(modifier.fillMaxWidth().localLockUserActivity { if (latestActive.value) latestActions.value.managementActivity() },
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(if (step == LocalLockSettingsStep.AUTO_LOCK) "自动锁" else "本地密码", Modifier.semantics { heading() },
            fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        if (snapshot.enabled && snapshot.resumeNotificationsUnavailable) {
            SettingsNote("系统恢复通知暂不可用；恢复后将在下次窗口活动时检查锁定")
        }
        when {
            !snapshot.enabled -> {
                SettingsNote("本地密码与 Telegram 两步验证分别设置。下载媒体不在此密码的加密范围内；遗忘后无法通过云端找回。")
                if (snapshot.canCreate) LocalLockCredentialForm(snapshot, actions, LocalLockCredentialKind.CREATE,
                    active, LocalWindowInfo.current.isWindowFocused)
                else SettingsNote(localLockIssueText(snapshot.issue.takeUnless { it == LocalLockIssue.NONE }
                    ?: LocalLockIssue.MIGRATION_UNAVAILABLE))
            }
            freshVerification || !snapshot.managementAuthorized -> {
                SettingsNote(if (freshVerification) "继续此操作前，请重新输入本地密码。" else "请输入本地密码，验证后管理本机保护设置。")
                key(step) {
                    LocalLockCredentialForm(snapshot, actions, LocalLockCredentialKind.VERIFY,
                        active, LocalWindowInfo.current.isWindowFocused, onAccepted = {
                            if (latestActive.value && latestSnapshot.value.managementAuthorized) step = when (step) {
                                LocalLockSettingsStep.CHANGE_VERIFY -> LocalLockSettingsStep.CHANGE
                                LocalLockSettingsStep.REMOVE_VERIFY -> LocalLockSettingsStep.REMOVE_CONFIRM
                                else -> LocalLockSettingsStep.HOME
                            }
                        })
                }
            }
            step == LocalLockSettingsStep.CHANGE -> {
                SettingsNote("输入新的本地密码；两次输入必须一致，新密码应与原密码不同。")
                LocalLockCredentialForm(snapshot, actions, LocalLockCredentialKind.CHANGE,
                    active, LocalWindowInfo.current.isWindowFocused, onAccepted = { step = LocalLockSettingsStep.HOME })
            }
            step == LocalLockSettingsStep.REMOVE_CONFIRM -> {
                SettingsNote("关闭后，本机应用和本地数据不再受此密码保护，数据库密钥将以本机可直接读取的方式保存。")
                SettingsNote("之后重新启用也无法撤销已复制的无密码记录或备份。现有下载与系统副本仍按原有方式保留。")
                SettingsAction("确认关闭本地密码", enabled = enabled && snapshot.canRemove, destructive = true) {
                    val current = latestSnapshot.value
                    if (latestActive.value && current.canRemove && current.managementAuthorized && !current.busy && pendingRevision == null) {
                        pendingRevision = current.operationRevision
                        latestActions.value.remove()
                    }
                }
                SettingsAction("取消关闭", enabled = enabled) { step = LocalLockSettingsStep.HOME }
            }
            step == LocalLockSettingsStep.AUTO_LOCK -> LocalLockAutoLockContent(snapshot, actions, enabled,
                onBack = { step = LocalLockSettingsStep.HOME })
            else -> {
                SettingsGroup("本机保护") {
                    SettingsRow("本地密码", "已启用")
                    SettingsRow("修改本地密码", enabled = enabled && snapshot.canChange) {
                        step = LocalLockSettingsStep.CHANGE_VERIFY
                        actions.closeManagement()
                    }
                    SettingsRow("自动锁", localLockDurationText(snapshot.autoLockSeconds), enabled = enabled && snapshot.canAutoLock) {
                        step = LocalLockSettingsStep.AUTO_LOCK
                    }
                    SettingsAction("关闭本地密码", enabled = enabled && snapshot.canRemove, destructive = true) {
                        step = LocalLockSettingsStep.REMOVE_VERIFY
                        actions.closeManagement()
                    }
                }
                SettingsNote("管理验证在 60 秒无操作后失效。锁定可保护本机界面与交互，不能清除进程或系统中的所有数据副本。")
                if (snapshot.idleFallback) SettingsNote("系统空闲信息暂不可用，自动锁使用应用内活动计时。")
            }
        }
        if (pendingRevision != null || snapshot.busy) SettingsNote("正在保存本机保护设置……")
        if (snapshot.issue != LocalLockIssue.NONE && snapshot.managementAuthorized)
            SettingsNote(localLockIssueText(snapshot.issue), error = true)
        ChatButton("返回隐私与安全", Modifier.fillMaxWidth(), onClick = ::close)
    }
}

private val localLockDurations = listOf(60L, 300L, 3600L, 18000L)

internal fun localLockDurationText(seconds: Long): String = when (seconds) {
    60L -> "1 分钟"
    300L -> "5 分钟"
    3600L -> "1 小时"
    18000L -> "5 小时"
    else -> "${seconds / 3600} 时 ${(seconds % 3600) / 60} 分"
}

/** Parses minutes without trimming, overflow or a zero-duration auto-lock. */
internal fun localLockCustomSeconds(hours: String, minutes: String): Long? {
    if (hours.isEmpty() || minutes.isEmpty() || hours.any { it !in '0'..'9' } || minutes.any { it !in '0'..'9' }) return null
    val hour = hours.toLongOrNull() ?: return null
    val minute = minutes.toLongOrNull()?.takeIf { it in 0..59 } ?: return null
    if (hour > Int.MAX_VALUE / 3600L) return null
    return (hour * 3600 + minute * 60).takeIf { it in 1..Int.MAX_VALUE.toLong() }
}

@Composable
private fun LocalLockAutoLockContent(snapshot: LocalLockSnapshot, actions: LocalLockActions, enabled: Boolean, onBack: () -> Unit) {
    var custom by remember { mutableStateOf(snapshot.autoLockSeconds !in localLockDurations) }
    var hours by remember { mutableStateOf(if (custom) (snapshot.autoLockSeconds / 3600).toString() else "10") }
    var minutes by remember { mutableStateOf(if (custom) ((snapshot.autoLockSeconds % 3600) / 60).toString().padStart(2, '0') else "00") }
    var pendingRevision by remember { mutableStateOf<Long?>(null) }
    var invalid by remember { mutableStateOf(false) }
    val latestSnapshot = rememberUpdatedState(snapshot)
    val latestEnabled = rememberUpdatedState(enabled)
    val latestActions = rememberUpdatedState(actions)
    LaunchedEffect(snapshot.operationRevision, snapshot.busy) {
        if (pendingRevision != null && snapshot.operationRevision != pendingRevision && !snapshot.busy) pendingRevision = null
    }
    fun submit(seconds: Long?) {
        val current = latestSnapshot.value
        if (!latestEnabled.value || !current.canAutoLock || !current.managementAuthorized || current.busy || pendingRevision != null) return
        if (seconds == null) { invalid = true; return }
        invalid = false
        pendingRevision = current.operationRevision
        latestActions.value.setAutoLockSeconds(seconds)
    }
    val canEdit = enabled && snapshot.canAutoLock && pendingRevision == null
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingsNote("在所选时间内没有系统或应用活动后锁定。更改保存后立即重新计时。")
        SettingsGroup("自动锁定时间") {
            localLockDurations.forEach { seconds ->
                SettingsAccountChoice(localLockDurationText(seconds), !custom && snapshot.autoLockSeconds == seconds, canEdit) {
                    custom = false
                    submit(seconds)
                }
            }
            SettingsAccountChoice("自定义", custom, canEdit) { custom = true }
        }
        if (custom) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextField(hours, { hours = it; invalid = false }, label = "时", enabled = canEdit, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    modifier = Modifier.weight(1f))
                TextField(minutes, { minutes = it; invalid = false }, label = "分", enabled = canEdit, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit(localLockCustomSeconds(hours, minutes)) }),
                    modifier = Modifier.weight(1f))
            }
            if (invalid) SettingsNote("请输入有效且非零的时长，分钟为 0–59。", error = true)
            ChatButton("保存自动锁时间", Modifier.fillMaxWidth(), enabled = canEdit, primary = true) {
                submit(localLockCustomSeconds(hours, minutes))
            }
        }
        if (pendingRevision != null) SettingsNote("正在保存自动锁时间……")
        if (snapshot.idleFallback) SettingsNote("系统空闲信息暂不可用，当前使用应用内活动计时。")
        SettingsAction("返回本地密码", onClick = onBack)
    }
}
