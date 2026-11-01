package com.badnng.moe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.badnng.moe.settings.*
import kotlin.coroutines.resume
import kotlin.random.Random
import top.yukonga.miuix.kmp.basic.TextField

/** Only native identities and draft values are saved in the route's transient saveable registry. */
internal val SettingsPrivacyDraftSaver = listSaver<PrivacyDraft?, String>(save = { draft ->
    if (draft == null) emptyList() else listOf(draft.source.base?.name.orEmpty(), draft.source.editable.toString(),
        draft.source.rules.joinToString(";") { it.kind.name + ":" + it.ids.joinToString(",") }, draft.base.name,
        draft.allowUsers.joinToString(","), draft.allowChats.joinToString(","), draft.denyUsers.joinToString(","),
        draft.denyChats.joinToString(","), draft.allowPremium.toString(), draft.revision.toString())
}, restore = { values ->
    if (values.isEmpty()) null else runCatching {
        fun ids(value: String) = if (value.isEmpty()) emptyList() else value.split(',').map(String::toLong)
        val rules = if (values[2].isEmpty()) emptyList() else values[2].split(';').map { encoded ->
            val parts = encoded.split(':', limit = 2)
            PrivacyRule(PrivacyRuleKind.valueOf(parts[0]), ids(parts[1]))
        }
        PrivacyDraft(PrivacyRules(values[0].takeIf(String::isNotEmpty)?.let(PrivacyBase::valueOf), rules, values[1].toBoolean()),
            PrivacyBase.valueOf(values[3]), ids(values[4]), ids(values[5]), ids(values[6]), ids(values[7]),
            values[8].toBoolean(), values[9].toLong())
    }.getOrNull()
})

private class PrivacyEditorLease { var active = true; var token: PrivacyEditorToken? = null }

@Composable
internal fun SettingsPrivacyDraftEditor(
    snapshot: SettingsSnapshot, actions: SettingsActions, key: PrivacyKey, draft: PrivacyDraft,
    enabled: Boolean, onChange: (PrivacyDraft) -> Unit, onCancel: () -> Unit, onSave: (PrivacyEditorToken) -> Unit,
) {
    val active = LocalSettingsPageActive.current
    var editor by remember(snapshot.token.generation, key) { mutableStateOf<PrivacyEditorToken?>(null) }
    var openingError by remember(snapshot.token.generation, key) { mutableStateOf<SettingsError?>(null) }
    var retryOpen by remember { mutableStateOf(0) }
    var choosing by remember(snapshot.token.generation, key) { mutableStateOf<PrivacyExceptionTarget?>(null) }
    var queryRevision by remember(snapshot.token.generation, key) { mutableStateOf(0L) }
    val currentDraft by rememberUpdatedState(draft)
    val currentActions by rememberUpdatedState(actions)
    DisposableEffect(active, snapshot.token, key, retryOpen) {
        val lease = PrivacyEditorLease()
        // Owned synchronously, before GetMe or any other native eligibility request can finish.
        val openingRequestId = Random.nextLong(1, Long.MAX_VALUE)
        editor = null
        openingError = null
        if (active) actions.openPrivacyEditor(snapshot.token, key, openingRequestId) { response ->
            val token = response.value
            if (!lease.active) token?.let { currentActions.closePrivacyEditor(snapshot.token, it) }
            else { lease.token = token; editor = token; openingError = response.error }
        }
        onDispose {
            lease.active = false
            if (active) currentActions.cancelPrivacyEditorOpening(snapshot.token, openingRequestId)
            lease.token?.let { currentActions.closePrivacyEditor(snapshot.token, it) }
        }
    }
    val conflict = draft.source != snapshot.privacy[key] || snapshot.errors[SettingsSection.PRIVACY] == SettingsError.CONFLICT
    val replacement = draft.replacement(key)
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsNote(key.settingsQuestion())
        val choices = if (key == PrivacyKey.FIND_BY_PHONE_NUMBER) listOf(PrivacyBase.EVERYBODY, PrivacyBase.CONTACTS)
            else PrivacyBase.entries
        choices.forEach { base ->
            SettingsAccountChoice(base.settingsLabel(), draft.base == base, enabled && choosing == null) {
                onChange(draft.copy(base = base, revision = draft.revision + 1))
            }
        }
        if (key != PrivacyKey.FIND_BY_PHONE_NUMBER) {
            if (draft.base != PrivacyBase.EVERYBODY) SettingsRow("始终允许", privacyDraftListSummary(draft, PrivacyExceptionTarget.ALLOW),
                enabled = enabled && editor != null && choosing == null) { choosing = PrivacyExceptionTarget.ALLOW }
            if (draft.base != PrivacyBase.NOBODY) SettingsRow("永不允许", privacyDraftListSummary(draft, PrivacyExceptionTarget.DENY),
                enabled = enabled && editor != null && choosing == null) { choosing = PrivacyExceptionTarget.DENY }
            SettingsNote("群组例外适用于群组成员。子选择只更新当前草稿，保存本页后才会生效。")
        }
        if (key == PrivacyKey.VOICE_VIDEO_MESSAGES) SettingsNote("此规则仅限 Telegram 高级版账号，打开和保存时都会重新验证资格。")
        val special = draft.source.rules.any { it.kind in setOf(PrivacyRuleKind.ALLOW_BOTS, PrivacyRuleKind.RESTRICT_BOTS, PrivacyRuleKind.RESTRICT_CONTACTS) }
        if (special) SettingsNote("当前原生机器人及联系人特殊规则和优先次序会保留。")
        openingError?.let { error ->
            SettingsNote(settingsErrorText(error), error = true)
            SettingsAction("重新验证编辑资格", enabled = enabled) { retryOpen++ }
        }
        if (conflict) SettingsNote("原生规则已变化，当前草稿仍保留。请取消本次编辑并重新读取后再修改。", error = true)
        if (replacement == null) SettingsNote("规则无法安全保存：请检查用户总数不超过 1000、群组总数不超过 20，且允许与禁止名单互斥。", error = true)
        val target = choosing
        val token = editor
        if (target != null && token != null) SettingsPrivacyPeerPicker(snapshot.token, token, actions, target, draft,
            enabled = enabled && active && !conflict,
            nextRevision = { ++queryRevision },
            onCancel = { choosing = null },
            onDone = { selected, premium -> onChange(currentDraft.withSelection(target, selected, premium)); choosing = null })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ChatButton("取消", Modifier.weight(1f), enabled = enabled, onClick = onCancel)
            ChatButton("保存", Modifier.weight(1f), enabled = enabled && active && !conflict && token != null && choosing == null &&
                replacement != null && replacement != draft.source, primary = true, onClick = { token?.let(onSave) })
        }
    }
}

@Composable
private fun SettingsPrivacyPeerPicker(
    settingsToken: SettingsToken, editor: PrivacyEditorToken, actions: SettingsActions,
    target: PrivacyExceptionTarget, draft: PrivacyDraft, enabled: Boolean, nextRevision: () -> Long,
    onCancel: () -> Unit, onDone: (List<PrivacyPeerId>, Boolean) -> Unit,
) {
    var selected by remember(editor, target, draft.revision) { mutableStateOf(draft.selected(target)) }
    var premium by remember(editor, target, draft.revision) { mutableStateOf(draft.allowPremium) }
    var source by remember(editor, target) { mutableStateOf(PrivacyPeerSource.CONTACTS) }
    var text by remember(editor, target) { mutableStateOf(TextFieldValue()) }
    var textDirty by remember(editor, target) { mutableStateOf(false) }
    var limit by remember(editor, target) { mutableStateOf(32) }
    var page by remember(editor, target) { mutableStateOf(0) }
    var refresh by remember(editor, target) { mutableStateOf(0L) }
    var peers by remember(editor, target) { mutableStateOf<SettingsPrivacyPeers?>(null) }
    var error by remember(editor, target) { mutableStateOf<SettingsError?>(null) }
    var loading by remember(editor, target) { mutableStateOf(false) }
    val currentActions by rememberUpdatedState(actions)
    val currentNextRevision by rememberUpdatedState(nextRevision)
    val currentDraft by rememberUpdatedState(draft)
    DisposableEffect(editor, target) {
        onDispose {
            // Empty selected-query is an actor-only invalidation; it sends no native metadata requests.
            currentActions.privacyPeers(settingsToken, editor, PrivacyPeerQuery(target, currentDraft.revision,
                currentNextRevision(), PrivacyPeerSource.SELECTED)) {}
        }
    }
    LaunchedEffect(editor, target, draft.revision, source, page, refresh, enabled, text.text, textDirty) {
        if (!enabled) return@LaunchedEffect
        // Cancel the previous native chain immediately; typing and changing source revoke its ownership.
        actions.privacyPeers(settingsToken, editor, PrivacyPeerQuery(target, draft.revision,
            nextRevision(), PrivacyPeerSource.SELECTED)) {}
        loading = false
        error = null
        peers = null
        if (source == PrivacyPeerSource.SERVER && text.text.isBlank()) return@LaunchedEffect
        if (source != PrivacyPeerSource.SELECTED && textDirty) return@LaunchedEffect
        val revision = nextRevision()
        val query = PrivacyPeerQuery(target, draft.revision, revision, source, text.text, limit,
            if (source == PrivacyPeerSource.SELECTED) selected.drop(page * 32).take(32) else emptyList())
        var live = true
        loading = true
        error = null
        peers = null
        try {
            kotlinx.coroutines.suspendCancellableCoroutine<Unit> { continuation ->
                continuation.invokeOnCancellation { live = false }
                actions.privacyPeers(settingsToken, editor, query) { response ->
                    if (live && continuation.isActive) {
                        peers = response.value; error = response.error; loading = false
                        continuation.resume(Unit)
                    }
                }
            }
        } finally { live = false }
    }
    val users = selected.count { !it.isChat }
    val chats = selected.count { it.isChat }
    val otherUsers = if (target == PrivacyExceptionTarget.ALLOW) draft.denyUsers else draft.allowUsers
    val otherChats = if (target == PrivacyExceptionTarget.ALLOW) draft.denyChats else draft.allowChats
    val totalUsers = users + otherUsers.count { id -> selected.none { !it.isChat && it.id == id } }
    val totalChats = chats + otherChats.count { id -> selected.none { it.isChat && it.id == id } }
    val valid = totalUsers <= 1000 && totalChats <= 20
    SettingsGroup(if (target == PrivacyExceptionTarget.ALLOW) "选择允许例外" else "选择禁止例外") {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsNote("当前名单：$users 个用户、$chats 个群组。已选身份可随时移除；不可用身份不会被新结果代替。")
            listOf(PrivacyPeerSource.CONTACTS to "联系人", PrivacyPeerSource.CHATS to "已知聊天与群组",
                PrivacyPeerSource.SERVER to "服务端查找已知聊天", PrivacyPeerSource.SELECTED to "当前已选").forEach { (value, label) ->
                SettingsAccountChoice(label, source == value, enabled && !loading) {
                    source = value; page = 0; limit = 32; textDirty = false
                    if (source == PrivacyPeerSource.SERVER && text.text.isBlank()) peers = null
                }
            }
            if (source != PrivacyPeerSource.SELECTED) {
                TextField(text, { text = it; limit = 32; textDirty = true }, label = "搜索姓名或群名", singleLine = true,
                    enabled = enabled, modifier = Modifier.fillMaxWidth())
                ChatButton("搜索", Modifier.fillMaxWidth(), enabled = enabled && !loading &&
                    (source != PrivacyPeerSource.SERVER || text.text.isNotBlank())) { limit = 32; textDirty = false; refresh++ }
            }
            if (target == PrivacyExceptionTarget.ALLOW && editor.key == PrivacyKey.CHAT_INVITES)
                SettingsToggle("始终允许高级版用户", premium, enabled) { premium = it }
            if (loading) SettingsNote("正在读取真实身份…")
            error?.let { SettingsNote(settingsErrorText(it), error = true)
                SettingsAction("重试身份读取", enabled = enabled && !loading) { refresh++ } }
            Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                val entries = peers?.entries.orEmpty()
                if (source == PrivacyPeerSource.SELECTED) selected.drop(page * 32).take(32).forEach { identity ->
                    val peer = entries.find { it.identity == identity }
                    SettingsRow(peer?.title ?: if (identity.isChat) "无法读取的原生群组" else "无法读取的原生用户",
                        "移除", summary = if (identity.isChat) "群组成员" else "用户", enabled = enabled) {
                        selected = selected - identity
                    }
                } else entries.forEach { peer ->
                    val checked = peer.identity in selected
                    val countedOnOtherSide = if (peer.isGroup) peer.identity.id in otherChats else peer.identity.id in otherUsers
                    SettingsRow(peer.title, if (checked) "已选择" else "选择", summary = if (peer.isGroup) "群组成员" else "用户",
                        enabled = enabled && (checked || countedOnOtherSide || if (peer.isGroup) totalChats < 20 else totalUsers < 1000)) {
                        selected = if (checked) selected - peer.identity else selected + peer.identity
                    }
                }
                if (!loading && entries.isEmpty()) SettingsNote(if (source == PrivacyPeerSource.SELECTED) "本页没有可读取的已选身份。" else "没有匹配的可选联系人或群组。")
            }
            if (source == PrivacyPeerSource.SELECTED) {
                if (page > 0) SettingsAction("上一页已选", enabled = enabled && !loading) { page-- }
                if ((page + 1) * 32 < selected.size) SettingsAction("下一页已选", enabled = enabled && !loading) { page++ }
            } else if (peers?.mayHaveMore == true && limit < 100) SettingsAction("读取更多候选", enabled = enabled && !loading) {
                limit = if (limit == 32) 64 else 100; refresh++
            }
            if (source != PrivacyPeerSource.SELECTED && peers?.mayHaveMore == true && limit == 100)
                SettingsNote("最多显示 100 个候选，请输入更具体的姓名或群名继续查找。")
            if (!valid) SettingsNote("允许和禁止两边合计最多 1000 个用户、20 个群组。", error = true)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChatButton("取消选择", Modifier.weight(1f), enabled = enabled, onClick = onCancel)
                ChatButton("保存选择", Modifier.weight(1f), primary = true, enabled = enabled && valid,
                    onClick = { onDone(selected, premium) })
            }
        }
    }
}

private fun privacyDraftListSummary(draft: PrivacyDraft, target: PrivacyExceptionTarget): String {
    val identities = draft.selected(target)
    val users = identities.count { !it.isChat }
    val chats = identities.count { it.isChat }
    val suffix = if (target == PrivacyExceptionTarget.ALLOW && draft.allowPremium) "，高级版用户" else ""
    return "$users 个用户、$chats 个群组$suffix"
}
