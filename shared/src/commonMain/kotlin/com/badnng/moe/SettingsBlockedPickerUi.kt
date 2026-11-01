package com.badnng.moe

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.badnng.moe.settings.*
import top.yukonga.miuix.kmp.basic.TextField

internal class SettingsBlockedUiOwner { var alive = true }

/** Security owns native identities and query revisions; this dialog owns only transient input. */
@Composable
internal fun SettingsBlockedPickerUi(
    picker: SettingsBlockedPickerState,
    actions: SettingsActions,
    active: Boolean,
    enabled: Boolean,
    onChoose: (SettingsBlockedCandidate) -> Boolean,
) {
    var source by remember(picker.token) { mutableStateOf(picker.query.source) }
    var text by remember(picker.token) { mutableStateOf(TextFieldValue(picker.query.query)) }
    var closed by remember(picker.token) { mutableStateOf(false) }
    var selected by remember(picker.token) { mutableStateOf(false) }
    var readSubmitted by remember(picker.token) { mutableStateOf(false) }
    val owner = remember(picker.token) { SettingsBlockedUiOwner() }
    val latest by rememberUpdatedState(picker)
    val latestActions by rememberUpdatedState(actions)
    val currentlyActive by rememberUpdatedState(active)
    val currentlyEnabled by rememberUpdatedState(enabled)
    val latestChoose by rememberUpdatedState(onChoose)

    fun close() {
        if (closed) return
        closed = true
        latestActions.closeBlockedPicker(picker.token)
    }
    DisposableEffect(picker.token) {
        onDispose {
            owner.alive = false
            // Retire outstanding candidate reads, never the parent security mutation.
            if (!closed) latestActions.closeBlockedPicker(picker.token)
        }
    }
    LaunchedEffect(active) { if (!active) close() }
    LaunchedEffect(picker.query, picker.loading, picker.error) { readSubmitted = false }

    fun search(nextSource: SettingsBlockedPeerSource = source, nextText: String = text.text, offset: Int = 0) {
        if (!owner.alive || closed || selected || !currentlyActive || !currentlyEnabled) return
        if (nextText.length > 256 || (nextSource == SettingsBlockedPeerSource.SERVER && nextText.isBlank())) return
        readSubmitted = true
        latestActions.searchBlockedPicker(picker.token, SettingsBlockedPeerQuery(
            source = nextSource, query = nextText, offset = offset,
            limit = 32, revision = latest.query.revision,
        ))
    }

    val queryMatches = picker.query.source == source && picker.query.query == text.text
    val queryValid = text.text.length <= 256 && (source != SettingsBlockedPeerSource.SERVER || text.text.isNotBlank())
    val selectable = active && enabled && !closed && !selected && !picker.loading && !readSubmitted && queryMatches
    SecurityConfirmation(show = active && !closed, title = "添加黑名单用户", maxWidth = 460.dp,
        summary = "选择用户后立即屏蔽。", onDismissRequest = { close() }) {
        // One naturally scrolling body keeps source/search/results/Cancel reachable in low windows.
        SecurityDialogBody {
            listOf(SettingsBlockedPeerSource.CONTACTS to "联系人",
                SettingsBlockedPeerSource.CHATS to "已知聊天中的用户",
                SettingsBlockedPeerSource.SERVER to "服务端查找").forEach { (value, label) ->
                SettingsAccountChoice(label, source == value,
                    active && enabled && !closed && !selected && !picker.loading && !readSubmitted) {
                    if (source != value) {
                        source = value
                        search(nextSource = value)
                    }
                }
            }
            TextField(text, { next ->
                text = next
            }, label = "搜索姓名或用户名", singleLine = true,
                enabled = active && enabled && !closed && !selected, modifier = Modifier.fillMaxWidth())
            SettingsAction("搜索", active && enabled && !closed && !selected && !picker.loading && !readSubmitted && queryValid) {
                search()
            }
            if (source == SettingsBlockedPeerSource.SERVER)
                SettingsNote("仅显示原生返回并可解析的用户；不代表全站用户目录。")
            if (text.text.length > 256) SettingsNote("搜索内容过长，请缩短到 256 个字符以内。", error = true)
            else if (source == SettingsBlockedPeerSource.SERVER && text.text.isBlank()) SettingsNote("输入姓名或用户名后搜索。")
            if (picker.loading || readSubmitted) SettingsNote("正在读取真实用户……")
            else if (!queryMatches && queryValid) SettingsNote("点击搜索以读取匹配的真实用户。")
            if (queryMatches) {
                picker.error?.let {
                    SettingsNote(settingsErrorText(it), error = true)
                    SettingsAction("重试读取用户", selectable) { search() }
                }
                picker.entries.forEach { candidate ->
                    SettingsRow(candidate.title.ifBlank { "原生用户" },
                        value = if (candidate.blocked) "已屏蔽" else "屏蔽",
                        enabled = selectable && !candidate.blocked) {
                        val current = latest
                        if (!owner.alive || closed || selected || !currentlyActive || !currentlyEnabled || readSubmitted ||
                            current.token != picker.token || current.loading || current.query != picker.query ||
                            current.query.source != source || current.query.query != text.text ||
                            current.entries.none { it.userId == candidate.userId && !it.blocked }) return@SettingsRow
                        // Synchronous local latch prevents a second row click before snapshot delivery.
                        selected = true
                        if (latestChoose(candidate)) close() else selected = false
                    }
                }
                if (!picker.loading && !readSubmitted && picker.error == null && picker.entries.isEmpty())
                    SettingsNote("没有匹配的可选用户。")
                if (picker.limitReached) SettingsNote("已达到本次候选读取容量，请输入更具体的姓名或用户名继续查找。")
                else if (!picker.end) SettingsAction("加载更多用户", selectable && picker.error == null) {
                    search(offset = latest.nextOffset)
                }
                else if (!picker.loading && picker.error == null && !readSubmitted) SettingsNote("已显示本次查询的全部结果。")
            }
            SettingsAction("取消", active && !closed && !selected) { close() }
        }
    }
}
