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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.badnng.moe.settings.*

/** Candidate identities are native metadata. Selection never enters or reads the selected channel. */
@Composable
internal fun SettingsPersonalChannelControls(
    parentToken: SettingsToken, userId: Long, extras: SettingsProfileExtrasSnapshot,
    actions: SettingsProfileExtrasActions, enabled: Boolean,
) {
    val active = LocalSettingsPageActive.current
    val data = extras.data
    val current = extras.token.generation == parentToken.generation && extras.token.userId == userId
    val canEdit = enabled && active && current && data != null && !extras.busy && !extras.loading
    var opened by rememberSaveable(parentToken.generation, userId) { mutableStateOf(false) }
    var expected by rememberSaveable(parentToken.generation, userId) { mutableStateOf(0L) }
    var chosen by rememberSaveable(parentToken.generation, userId) { mutableStateOf<Long?>(null) }
    var draftRevision by rememberSaveable(parentToken.generation, userId) { mutableStateOf(0L) }
    var selectionOwner by remember { mutableStateOf(0L) }
    var submittedOwner by remember { mutableStateOf<Long?>(null) }
    var submittedDraft by remember { mutableStateOf(0L) }
    var submittedAfter by remember { mutableStateOf(0L) }
    var confirmation by remember { mutableStateOf<SettingsPersonalChannel?>(null) }
    var removeConfirmation by remember { mutableStateOf(false) }
    var lease by remember { mutableStateOf<SettingsProfileExtrasPageLease?>(null) }
    var leaseToken by remember { mutableStateOf<SettingsProfileExtrasToken?>(null) }
    val latestLease by rememberUpdatedState(lease)
    val latestActions by rememberUpdatedState(actions)
    val latestToken by rememberUpdatedState(extras.token)

    fun close() {
        lease?.close()
        opened = false; chosen = null; submittedOwner = null; confirmation = null; removeConfirmation = false
        actions.closePersonalChannels(extras.token)
    }
    fun open(resetDraft: Boolean) {
        lease?.close()
        val page = settingsProfileExtrasPageLease()
        lease = page
        leaseToken = extras.token
        selectionOwner++
        submittedOwner = null
        if (resetDraft) { expected = data?.personalChatId ?: 0; chosen = expected; draftRevision++ }
        opened = true
        actions.openPersonalChannels(extras.token, page::active)
    }
    DisposableEffect(parentToken.generation, userId) {
        onDispose { latestLease?.close(); latestActions.closePersonalChannels(latestToken) }
    }
    LaunchedEffect(active, current, extras.token, extras.loading) {
        if (leaseToken != extras.token) {
            lease?.close()
            submittedOwner = null; confirmation = null; removeConfirmation = false
        }
        if (!active || !current) {
            lease?.close(); confirmation = null; removeConfirmation = false
            actions.closePersonalChannels(extras.token)
        } else if (!extras.loading && opened && (leaseToken != extras.token || lease?.active() != true)) open(resetDraft = false)
    }
    LaunchedEffect(extras.acceptedRevision, extras.failedRevision) {
        val submitted = submittedOwner
        if (submitted != null && extras.operation == SettingsProfileExtrasOperation.PERSONAL_CHANNEL) {
            if (extras.acceptedRevision > submittedAfter) {
                if (submitted == selectionOwner && submittedDraft == draftRevision) close()
                else submittedOwner = null
            } else if (extras.failedRevision > submittedAfter) submittedOwner = null
        }
    }

    SettingsGroup("个人频道") {
        SettingsRow("个人频道", when {
            data == null -> "尚未读取"
            data.personalChatId == 0L -> "未设置"
            data.personalChannel != null -> data.personalChannel.title
            else -> "当前频道暂时无法读取"
        }, enabled = canEdit && !opened, onClick = { open(resetDraft = true) })
        if (data?.personalChannelError != null) {
            SettingsNote("当前个人频道的身份暂时无法读取。", error = true)
            ChatButton("刷新个人频道", enabled = canEdit) { actions.refresh(extras.token) }
        }
        if (opened && active && current) Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingsNote("选择你创建的公开频道，显示在个人资料中。")
            if (extras.channelsLoading) SettingsNote("正在读取可选频道……")
            extras.channels.forEach { channel ->
                SettingsAccountChoice(channel.title, chosen == channel.chatId, canEdit && !extras.channelsLoading) {
                    chosen = channel.chatId; draftRevision++
                }
                SettingsNote("@${channel.username}")
            }
            if (!extras.channelsLoading && extras.channelsOpened && extras.channels.isEmpty() && extras.channelsUnavailable == 0 &&
                !extras.channelsHasMore && !extras.channelsLimited && extras.channelsError == null) SettingsNote("没有可用于个人资料的公开频道。")
            if (extras.channelsUnavailable > 0) SettingsNote("${extras.channelsUnavailable} 个候选暂时无法读取或已不再适用，可重新读取列表。", error = true)
            if (extras.channelsLimited) SettingsNote("本次候选列表未完整返回或达到 512 个的资源上限。已显示的频道仍会在保存前重新核对。")
            extras.channelsTotal?.let { total -> SettingsNote("已显示 ${extras.channels.size} 个 · 原生近似总量 $total") }
            if (extras.channelsError != null) SettingsNote("频道列表读取失败，已有选择已保留。请重试。", error = true)
            if (extras.operation == SettingsProfileExtrasOperation.PERSONAL_CHANNEL && extras.error != null)
                SettingsNote(when (extras.error) {
                    SettingsError.CONFLICT -> "当前频道或选择资格已改变，请刷新资料并重新确认。"
                    SettingsError.FORBIDDEN -> "此频道当前不允许设为个人频道，选择已保留。"
                    SettingsError.RATE_LIMITED -> "修改过于频繁，请稍后重试。"
                    else -> "个人频道未保存，选择已保留。"
                }, error = true)
            if (extras.channelsHasMore) ChatButton("更多频道", enabled = canEdit && !extras.channelsLoading) {
                actions.morePersonalChannels(extras.token)
            }
            ChatButton("重新读取频道", enabled = canEdit && !extras.channelsLoading) { open(resetDraft = false) }
            val target = extras.channels.firstOrNull { it.chatId == chosen }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ChatButton("取消频道修改", Modifier.weight(1f), onClick = ::close)
                ChatButton("保存个人频道", Modifier.weight(1f), primary = true,
                    enabled = canEdit && !extras.channelsLoading && target != null && target.chatId != expected,
                    onClick = { confirmation = target })
            }
            if (expected != 0L) ChatButton("移除个人频道", enabled = canEdit && !extras.channelsLoading) { removeConfirmation = true }
        }
    }
    val target = confirmation
    ProfileExtrasConfirmation(target != null && active, "设置个人频道？", canEdit && target?.let { it in extras.channels } == true,
        onDismiss = { confirmation = null }, onConfirm = {
            if (target != null) {
                submittedOwner = selectionOwner; submittedDraft = draftRevision; submittedAfter = extras.startedRevision
                actions.savePersonalChannel(extras.token, expected, target.chatId); confirmation = null
            }
        }) { if (target != null) SettingsNote("将把“${target.title}”设为个人频道。保存前会重新核对账号、当前频道和选择资格。") }
    ProfileExtrasConfirmation(removeConfirmation && active, "移除个人频道？", canEdit && expected != 0L,
        onDismiss = { removeConfirmation = false }, onConfirm = {
            submittedOwner = selectionOwner; submittedDraft = draftRevision; submittedAfter = extras.startedRevision
            actions.savePersonalChannel(extras.token, expected, 0); removeConfirmation = false
        }) { SettingsNote("个人资料将不再展示已确认的当前频道。频道本身及其中消息会保留。") }
}
