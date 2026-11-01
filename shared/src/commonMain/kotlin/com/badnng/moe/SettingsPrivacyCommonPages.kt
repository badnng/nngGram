package com.badnng.moe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.badnng.moe.settings.*
import top.yukonga.miuix.kmp.basic.TextField

@Composable
internal fun SettingsSessionCommonOptions(security: SettingsSecuritySnapshot, actions: SettingsActions,
    active: Boolean, parentSubmitting: Boolean, writesAllowed: Boolean,
) {
    var frozen by remember(security.token) { mutableStateOf<List<SettingsSession>?>(null) }
    var ttlSource by remember(security.token) { mutableStateOf<Int?>(null) }
    var ttlDraft by remember(security.token) { mutableStateOf(0) }
    var submitting by remember(security.token) { mutableStateOf(false) }
    var notice by remember(security.token) { mutableStateOf<String?>(null) }
    val latest by rememberUpdatedState(security)
    val currentlyActive by rememberUpdatedState(active)
    val enabled = writesAllowed && active && !security.loading && !security.busy && !submitting && !parentSubmitting && !security.sessionsRefreshRequired
    LaunchedEffect(active) { if (!active) { frozen = null; ttlSource = null } }
    notice?.let { SettingsNote(it) }
    if (security.sessionsRefreshRequired) SettingsNote("当前显示的是上次读取的设备与期限，请先刷新列表再操作。", error = true)
    val others = security.sessions.filterNot { it.isCurrent }
    if (others.isNotEmpty()) SettingsGroup("其他已登录设备") {
        SettingsRow("其他会话", "${others.size} 个", summary = "终止后，这些设备需要重新登录。当前设备将保持登录。")
        SettingsAction("终止所有其他会话", enabled && security.sessions.count { it.isCurrent } == 1, destructive = true) {
            frozen = security.sessions.toList()
        }
    }
    SettingsGroup("自动终止会话") {
        SettingsRow("若未活动时间超过", security.inactiveSessionTtlDays?.let { "$it 天" } ?: "尚未读取",
            summary = "Telegram 会自动终止达到此闲置时间的设备会话。",
            enabled = enabled && security.inactiveSessionTtlDays != null) {
            ttlSource = security.inactiveSessionTtlDays
            ttlDraft = security.inactiveSessionTtlDays ?: return@SettingsRow
        }
        val source = ttlSource
        if (source != null) Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            (listOf(7, 30, 90, 180, 365) + source).distinct().sorted().forEach { days ->
                SettingsAccountChoice("$days 天", ttlDraft == days, enabled) { ttlDraft = days }
            }
            if (source != security.inactiveSessionTtlDays) SettingsNote("会话闲置期限已变化，请取消后重新选择。", error = true)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ChatButton("取消", Modifier.weight(1f), enabled = !submitting, onClick = { ttlSource = null })
                ChatButton("保存", Modifier.weight(1f), enabled = enabled && source == security.inactiveSessionTtlDays && ttlDraft != source,
                    primary = true) {
                    if (!currentlyActive || latest.error == SettingsError.RESULT_UNKNOWN || latest.token != security.token || submitting || latest.busy || latest.loading ||
                        latest.inactiveSessionTtlDays != source) return@ChatButton
                    submitting = true
                    val owner = security.token
                    val desired = ttlDraft
                    actions.saveInactiveSessionTtl(owner, source, desired) { result ->
                        if (!currentlyActive || latest.token != owner) return@saveInactiveSessionTtl
                        submitting = false
                        notice = resultText(result, "会话闲置期限已保存。", "会话闲置期限已保存，但刷新失败，请刷新列表。")
                        if (result.value != null) ttlSource = null
                    }
                }
            }
        }
    }
    val expected = frozen
    val identitiesMatch = expected != null && sameSettingsDevices(expected, security.sessions)
    SecurityConfirmation(show = active && expected != null && !security.loading, title = "终止所有其他会话？", maxWidth = 440.dp,
        onDismissRequest = { frozen = null }) {
        SecurityDialogBody {
            SettingsNote("当前设备将保持登录。以下设备将退出 Telegram，需要重新登录才能访问你的账号。")
            expected.orEmpty().filterNot { it.isCurrent }.forEach { device ->
                SettingsRow(device.deviceModel.ifBlank { device.applicationName.ifBlank { "设备" } },
                    summary = listOf(device.applicationName, device.location).filter(String::isNotBlank).joinToString(" · "))
            }
            if (!identitiesMatch) SettingsNote("已登录设备已变化，请取消后重新确认。", error = true)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ChatButton("取消", Modifier.weight(1f), onClick = { frozen = null })
                ChatButton("终止其他会话", Modifier.weight(1f), enabled = enabled && identitiesMatch, primary = true) {
                    val chosen = expected ?: return@ChatButton
                    if (!currentlyActive || latest.error == SettingsError.RESULT_UNKNOWN || latest.token != security.token || submitting || latest.busy || latest.loading ||
                        !sameSettingsDevices(chosen, latest.sessions)) return@ChatButton
                    submitting = true
                    val owner = security.token
                    actions.terminateOtherSecuritySessions(owner, chosen) { result ->
                        if (!currentlyActive || latest.token != owner) return@terminateOtherSecuritySessions
                        submitting = false
                        notice = resultText(result, "其他会话已终止。", "其他会话已终止，但列表刷新失败，请刷新列表。")
                    }
                    frozen = null
                }
            }
        }
    }
}

@Composable
internal fun SettingsDefaultAutoDeletePage(security: SettingsSecuritySnapshot, actions: SettingsActions, active: Boolean, writesAllowed: Boolean) {
    val source = security.defaultMessageAutoDeleteSeconds
    var draft by remember(security.token) { mutableStateOf(source ?: 0) }
    var dirty by remember(security.token) { mutableStateOf(false) }
    var customDays by remember(security.token) { mutableStateOf("") }
    var custom by remember(security.token) { mutableStateOf(false) }
    var frozen by remember(security.token) { mutableStateOf<Pair<Int, Int>?>(null) }
    var submitting by remember(security.token) { mutableStateOf(false) }
    var notice by remember(security.token) { mutableStateOf<String?>(null) }
    val latest by rememberUpdatedState(security)
    val currentlyActive by rememberUpdatedState(active)
    val enabled = writesAllowed && active && !security.loading && !security.busy && !submitting && source != null && !security.defaultAutoDeleteRefreshRequired
    val desired = if (custom) customDays.toIntOrNull()?.takeIf { it in 1..365 }?.times(86400) else draft
    LaunchedEffect(active) { if (!active) frozen = null }
    LaunchedEffect(source, dirty) { if (source != null && !dirty) draft = source }
    fun submit(expected: Int, value: Int) {
        if (!currentlyActive || latest.error == SettingsError.RESULT_UNKNOWN || submitting || latest.token != security.token || latest.loading || latest.busy ||
            latest.defaultMessageAutoDeleteSeconds != expected) return
        submitting = true
        val owner = security.token
        actions.saveDefaultMessageAutoDeleteTime(owner, expected, value) { result ->
            if (!currentlyActive || latest.token != owner) return@saveDefaultMessageAutoDeleteTime
            submitting = false
            notice = resultText(result, "新聊天默认消息保留时长已保存。", "设置已保存，但读取最新值失败，请刷新列表。")
            if (result.value != null && result.error == null) { custom = false; dirty = false }
        }
    }
    SettingsNote("设置之后创建的聊天的默认消息保留时长。聊天中的消息将在设定时间后由 Telegram 自动删除。已有聊天的设置保持不变。")
    notice?.let { SettingsNote(it) }
    if (security.defaultAutoDeleteRefreshRequired) SettingsNote("当前显示的是上次读取的默认期限，请先刷新列表再操作。草稿仍保留。", error = true)
    SettingsGroup("自动删除时间") {
        if (source == null) SettingsRow("默认消息保留时长尚未读取")
        else {
            (listOf(0, 86400, 7 * 86400, 31 * 86400) + source).distinct().sorted().forEach { seconds ->
                SettingsAccountChoice(autoDeleteLabel(seconds), !custom && draft == seconds, enabled) { draft = seconds; custom = false; dirty = true }
            }
            SettingsAccountChoice("自定义时间", custom, enabled) { custom = true; dirty = true }
            if (custom) Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(customDays, { value -> if (value.length <= 3 && value.all { it in '0'..'9' }) customDays = value },
                    label = "天数（1–365）", enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                if (customDays.isNotEmpty() && desired == null) SettingsNote("请输入 1–365 之间的天数。", error = true)
            }
            SettingsAction("保存", enabled && desired != null && desired != source) {
                val next = desired ?: return@SettingsAction
                if (source == 0 && next != 0) frozen = source to next else submit(source, next)
            }
        }
    }
    val confirmation = frozen
    SecurityConfirmation(show = active && confirmation != null, title = "开启新聊天自动删除？", maxWidth = 440.dp,
        onDismissRequest = { frozen = null }) {
        SecurityDialogBody {
            SettingsNote("之后创建的聊天将默认在 ${autoDeleteLabel(confirmation?.second ?: 0)}后自动删除消息。")
            if (source != confirmation?.first) SettingsNote("默认保留时长已变化，请取消后重新选择。", error = true)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ChatButton("取消", Modifier.weight(1f), onClick = { frozen = null })
                ChatButton("开启", Modifier.weight(1f), enabled = enabled && source == confirmation?.first, primary = true) {
                    val chosen = confirmation ?: return@ChatButton
                    frozen = null
                    submit(chosen.first, chosen.second)
                }
            }
        }
    }
}

private fun autoDeleteLabel(seconds: Int) = if (seconds == 0) "关闭" else "${seconds / 86400} 天"
private fun resultText(result: SettingsResult<Unit>, success: String, refreshFailed: String) = when {
    result.value != null && result.error == null -> success
    result.value != null -> refreshFailed
    else -> result.error?.let(::settingsErrorText) ?: "操作未完成，请重试。"
}
