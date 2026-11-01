package com.badnng.moe

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
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.badnng.moe.settings.*

internal interface PrivacyCommonPageLease { fun active(): Boolean; fun close() }
internal expect fun privacyCommonPageLease(): PrivacyCommonPageLease

/** The harmless day draft survives navigation; a destructive confirmation never enters a Saver. */
internal class PrivacyCommonUiState {
    var choosingTtl by mutableStateOf(false)
    var ttlExpected by mutableStateOf<Int?>(null)
    var ttlDays by mutableStateOf(365)
    var confirmingTtl by mutableStateOf<Pair<Int, Int>?>(null)
    var submittedOwner by mutableStateOf<PrivacyCommonToken?>(null)
    var submittedAfter by mutableStateOf(0L)
    var submittedDays by mutableStateOf<Int?>(null)
    companion object {
        val Saver = listSaver<PrivacyCommonUiState, Any>(save = { listOf(it.choosingTtl, it.ttlExpected ?: 0, it.ttlDays) },
            restore = { values -> PrivacyCommonUiState().apply {
                choosingTtl = values[0] as Boolean
                ttlExpected = (values[1] as Int).takeIf { it in 30..730 }
                ttlDays = (values[2] as Int).coerceIn(30, 730)
            } })
    }
}

@Composable
internal fun PrivacyCommonContent(parent: SettingsToken, snapshot: PrivacyCommonSnapshot, actions: PrivacyCommonActions,
    state: PrivacyCommonUiState, enabled: Boolean,
) {
    val pageActive = LocalSettingsPageActive.current
    val latestActions by rememberUpdatedState(actions)
    val latestToken by rememberUpdatedState(snapshot.token)
    var lease by remember(parent.generation) { mutableStateOf<PrivacyCommonPageLease?>(null) }
    val latestLease by rememberUpdatedState(lease)
    LaunchedEffect(parent, pageActive) {
        lease?.close()
        state.confirmingTtl = null
        if (pageActive) {
            val requested = privacyCommonPageLease()
            lease = requested
            actions.open(parent, requested::active)
        } else actions.close(snapshot.token)
    }
    DisposableEffect(parent.generation) {
        onDispose { latestLease?.close(); state.confirmingTtl = null; latestActions.close(latestToken) }
    }
    val current = snapshot.opened && snapshot.token.generation == parent.generation
    val canEdit = enabled && pageActive && current && !snapshot.loading && !snapshot.busy
    val data = snapshot.data.takeIf { current } ?: PrivacyCommonData()
    val ttlEditable = canEdit && PrivacyCommonField.ACCOUNT_TTL in snapshot.loaded &&
        PrivacyCommonField.ACCOUNT_TTL !in snapshot.refreshRequired && data.accountTtlDays != null
    val latestSnapshot by rememberUpdatedState(snapshot)
    val currentPage by rememberUpdatedState(pageActive)
    LaunchedEffect(snapshot.token, snapshot.acceptedRevision, snapshot.failedRevision) {
        if (state.submittedOwner == snapshot.token && state.submittedDays != null) {
            if (snapshot.operation == PrivacyCommonField.ACCOUNT_TTL && snapshot.acceptedRevision > state.submittedAfter) {
                if (state.ttlDays == state.submittedDays) state.choosingTtl = false
                state.submittedDays = null
            } else if (snapshot.failedRevision > state.submittedAfter) state.submittedDays = null
        }
    }
    if (snapshot.loading && current) SettingsNote("正在读取更多隐私设置……")
    if (snapshot.busy && current) SettingsNote("正在保存隐私设置……")
    snapshot.errors.takeIf { current }?.forEach { (field, error) ->
        SettingsNote("${field.commonLabel()}：${settingsErrorText(error)}", error = true)
    }
    if (current && snapshot.refreshRequired.isNotEmpty()) SettingsNote("部分设置显示上次读取的值，请先刷新再修改。未提交的草稿仍保留。", error = true)
    if (current && snapshot.acceptedRevision > 0 && !snapshot.loading && !snapshot.busy) {
        SettingsNote(if (snapshot.refreshRequired.isEmpty()) "隐私设置已保存。" else "设置已被接受，但最新值读取失败，请刷新。")
    }
    SettingsGroup("常用联系人") {
        val value = data.topPeersEnabled
        if (value == null) SettingsRow("建议常用联系人", "尚未读取")
        else SettingsToggle("建议常用联系人", value, canEdit && PrivacyCommonField.TOP_PEERS in snapshot.loaded &&
            PrivacyCommonField.TOP_PEERS !in snapshot.refreshRequired) { desired ->
            actions.setTopPeers(snapshot.token, value, desired)
        }
        SettingsNote("在搜索中建议经常联系的人。关闭会清除常用联系人排名，不会删除你的联系人或最近搜索。设置由 Telegram 同步。")
    }
    SettingsGroup("来自陌生人的新聊天") {
        val value = data.archive
        if (value == null) SettingsRow("归档并静音", "尚未读取")
        else SettingsToggle("归档并静音", value.archiveAndMuteNewChats,
            canEdit && PrivacyCommonField.ARCHIVE in snapshot.loaded && PrivacyCommonField.ARCHIVE !in snapshot.refreshRequired &&
                (value.archiveAndMuteNewChats || data.archiveCapability == PrivacyArchiveCapability.ALLOWED)) { desired ->
            actions.setArchive(snapshot.token, value, value.copy(archiveAndMuteNewChats = desired))
        }
        SettingsNote(when (data.archiveCapability) {
            PrivacyArchiveCapability.ALLOWED -> "新的陌生人聊天会由 Telegram 自动归档并静音，已有聊天不变。"
            PrivacyArchiveCapability.DENIED -> "此账号当前无法开启这项功能。已开启时仍可关闭。"
            PrivacyArchiveCapability.UNKNOWN -> "正在等待 Telegram 确认此账号是否可以开启。已开启时仍可关闭。"
        })
    }
    SettingsGroup("自动删除账号") {
        SettingsRow("若离线时间超过", data.accountTtlDays?.let { "$it 天" } ?: "尚未读取",
            summary = "达到此离线时长后，Telegram 会删除你的账号及云端消息和联系人。", enabled = ttlEditable) {
            state.ttlExpected = data.accountTtlDays
            state.ttlDays = data.accountTtlDays ?: return@SettingsRow
            state.choosingTtl = true
        }
        if (state.choosingTtl) Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            (listOf(30, 90, 180, 365, 548, 720) + state.ttlDays + listOfNotNull(state.ttlExpected)).distinct().sorted().forEach { days ->
                SettingsAccountChoice("$days 天", state.ttlDays == days, ttlEditable) { state.ttlDays = days }
            }
            if (state.ttlExpected != data.accountTtlDays) SettingsNote("账号删除期限已变化，草稿仍保留。请取消后重新选择。", error = true)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ChatButton("取消", Modifier.weight(1f), onClick = { state.choosingTtl = false })
                ChatButton("保存", Modifier.weight(1f), enabled = ttlEditable && state.ttlExpected == data.accountTtlDays &&
                    state.ttlDays != data.accountTtlDays, primary = true) {
                    val source = state.ttlExpected ?: return@ChatButton
                    state.confirmingTtl = source to state.ttlDays
                }
            }
        }
    }
    SettingsAction("刷新更多隐私设置", pageActive && current && !snapshot.loading && !snapshot.busy) { actions.refresh(snapshot.token) }
    val frozen = state.confirmingTtl
    SecurityConfirmation(show = pageActive && current && frozen != null, title = "更改自动删除规则？", maxWidth = 440.dp,
        onDismissRequest = { state.confirmingTtl = null }) {
        SecurityDialogBody {
            SettingsNote("持续离线 ${frozen?.second ?: 0} 天后，Telegram 将自动删除你的账号及云端消息和联系人。")
            if (frozen?.first != data.accountTtlDays) SettingsNote("账号删除期限已变化，请取消后重新选择。", error = true)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ChatButton("取消", Modifier.weight(1f), onClick = { state.confirmingTtl = null })
                ChatButton("确认", Modifier.weight(1f), enabled = ttlEditable && frozen?.first == data.accountTtlDays, primary = true) {
                    val chosen = frozen ?: return@ChatButton
                    val actual = latestSnapshot
                    if (!currentPage || actual.token != snapshot.token || actual.loading || actual.busy ||
                        PrivacyCommonField.ACCOUNT_TTL in actual.refreshRequired || actual.data.accountTtlDays != chosen.first) return@ChatButton
                    state.submittedOwner = snapshot.token
                    state.submittedAfter = maxOf(snapshot.acceptedRevision, snapshot.failedRevision)
                    state.submittedDays = chosen.second
                    state.confirmingTtl = null
                    actions.setAccountTtl(snapshot.token, chosen.first, chosen.second)
                }
            }
        }
    }
}

private fun PrivacyCommonField.commonLabel() = when (this) {
    PrivacyCommonField.TOP_PEERS -> "常用联系人"
    PrivacyCommonField.ARCHIVE -> "归档并静音"
    PrivacyCommonField.ARCHIVE_CAPABILITY -> "归档功能资格"
    PrivacyCommonField.ACCOUNT_TTL -> "账号删除期限"
}
