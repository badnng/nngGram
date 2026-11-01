package com.badnng.moe

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.ChatFolderInfo
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.folders.*
import kotlin.random.Random
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

internal interface FolderSettingsPageLease { fun active(): Boolean; fun close() }
internal expect fun folderSettingsPageLease(): FolderSettingsPageLease

private class FolderOverviewPage(val lease: FolderSettingsPageLease, val generation: Long, val pageId: Long) {
    var token: FolderSettingsToken? = null
    fun owns(snapshot: FolderSettingsSnapshot): Boolean {
        if (!lease.active() || !snapshot.opened || snapshot.token.generation != generation || snapshot.token.pageId != pageId) return false
        return token?.let { it == snapshot.token } ?: true
    }
}

private data class FolderApplyConfirmation(val token: FolderSettingsToken, val draft: FolderOverviewDraft)

/** The overview belongs to the native controller; leaving it only revokes work, never applies it. */
@Composable
internal fun FolderSettingsFollowupOverview(
    snapshot: ChatSnapshot, features: ChatFeatureActions, preferences: ChatFolderPreferences,
    onPreferencesChange: (ChatFolderPreferences) -> Unit, state: LazyListState, modifier: Modifier = Modifier,
) {
    val active = LocalSettingsPageActive.current
    val settings = snapshot.folderSettings
    val actions = features.folders
    val currentActions by rememberUpdatedState(actions)
    var page by remember(snapshot.token.generation) { mutableStateOf<FolderOverviewPage?>(null) }
    var confirmation by remember(snapshot.token.generation) { mutableStateOf<FolderApplyConfirmation?>(null) }
    var colorFolder by remember(snapshot.token.generation) { mutableStateOf<Int?>(null) }
    DisposableEffect(active, snapshot.token.generation) {
        val requested = FolderOverviewPage(folderSettingsPageLease(), snapshot.token.generation, Random.nextLong(1, Long.MAX_VALUE))
        page = requested
        if (active) actions.open(snapshot.token, requested.pageId, requested.lease::active) else requested.lease.close()
        onDispose {
            requested.lease.close()
            requested.token?.let { currentActions.close(it) }
            confirmation = null
            colorFolder = null
            if (page === requested) page = null
        }
    }
    val owner = page
    val owned = active && owner?.owns(settings) == true
    // Claim only the first owner published for this page's identity. An outgoing
    // composition must not close another page's subsequently published token.
    SideEffect { if (owned && owner?.token == null) owner?.token = settings.token }
    val draft = settings.draft
    val busy = settings.busy || settings.loading || snapshot.folderSaving
    val enabled = owned && !busy
    val editable = enabled && !settings.conflict && draft != null
    val dirty = draft?.dirty == true
    val independent = editable && !dirty
    val capability = settings.capabilities
    val premiumEditable = capability?.premium == true && snapshot.account?.isPremium != false
    val applyRequiresPremium = draft?.let { value -> value.order.filter {
        it !is FolderDraftEntry.Existing || it.id !in value.removed
    }.indexOf(FolderDraftEntry.Main) > 0 } == true
    val applyAllowed = !applyRequiresPremium || premiumEditable
    val effectiveCount = draft?.let { value -> value.order.count { it != FolderDraftEntry.Main &&
        (it !is FolderDraftEntry.Existing || it.id !in value.removed) } } ?: settings.nativeState.folders.size
    val capacity = capability?.folderLimit?.let { effectiveCount < it } == true
    val folders = draft?.source?.folders ?: settings.nativeState.folders
    val recommendations = settings.recommendations.associateBy { it.key }
    LaunchedEffect(active, settings.token, draft?.revision, settings.conflict, settings.busy, premiumEditable) {
        if (!active || confirmation?.let { it.token != settings.token || it.draft != draft } == true || settings.conflict || settings.busy || !applyAllowed)
            confirmation = null
        if (!active || !independent || !premiumEditable) colorFolder = null
    }
    LazyColumn(modifier.fillMaxWidth().scrollEndHaptic().overScrollVertical(), state = state,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SettingsNote("按聊天用途整理文件夹。排序、推荐和移除先保留在本页草稿，点“应用更改”后才提交。")
            when {
                settings.busy -> SettingsRow("正在提交文件夹更改…", summary = "已发出的操作无法撤回；离页会停止尚未发送的步骤。")
                settings.loading || !owned -> SettingsRow("正在读取文件夹设置…")
            }
            capability?.let { SettingsRow(if (premiumEditable) "Telegram 高级版" else "普通账号", "$effectiveCount / ${it.folderLimit}",
                "当前账号的原生文件夹容量；指定聊天上限 ${it.chosenChatLimit}。") }
            if (settings.acceptedSteps > 0) SettingsNote("TDLib 已接受 ${settings.acceptedSteps} 项操作并安排同步；跨设备结果仍以服务端同步为准。")
            if (settings.conflict) SettingsNote("文件夹已在其他位置更改。当前草稿保留；放弃草稿后才能读取并编辑最新内容。", error = true)
            settings.error?.let { SettingsNote(settingsErrorText(it), error = true) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChatButton("刷新推荐与能力", Modifier.weight(1f), enabled = enabled && !dirty) { actions.refresh(settings.token) }
                ChatButton("放弃草稿", Modifier.weight(1f), enabled = enabled && (dirty || settings.conflict)) { actions.reset(settings.token) }
            }
        }
        item { SettingsGroup("我的文件夹") {
            SettingsNote(if (premiumEditable) "包含“全部”的完整顺序。上移或下移后应用。" else "普通账号的“全部”固定在首位。")
        } }
        items(draft?.order.orEmpty(), key = { it.folderEntryKey() }) { entry ->
            val index = draft!!.order.indexOf(entry)
            val existing = (entry as? FolderDraftEntry.Existing)?.let { value -> folders.firstOrNull { it.id == value.id } }
            val removed = existing != null && existing.id in draft.removed
            val label = when (entry) {
                FolderDraftEntry.Main -> "全部"
                is FolderDraftEntry.Existing -> existing?.name ?: "文件夹不可用"
                is FolderDraftEntry.Recommended -> recommendations[entry.key]?.title ?: "推荐不可用"
            }
            SettingsGroup(label) {
                SettingsRow(label, value = when { removed -> "待移除"; entry is FolderDraftEntry.Recommended -> "待添加"; else -> null },
                    summary = if (existing?.isShareable == true) "共享文件夹定义只读。" else null,
                    enabled = independent && existing != null && !removed,
                    onClick = existing?.let { folder -> ({ if (independent && !removed) features.editFolder(snapshot.token, folder.id) }) })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val previous = draft.order.getOrNull(index - 1)
                    val next = draft.order.getOrNull(index + 1)
                    val canMoveMain = premiumEditable
                    ChatButton("上移", Modifier.weight(1f).semantics { contentDescription = "上移 $label" },
                        enabled = editable && !removed && index > 0 && (canMoveMain || entry != FolderDraftEntry.Main && previous != FolderDraftEntry.Main)) {
                        actions.move(settings.token, entry, -1)
                    }
                    ChatButton("下移", Modifier.weight(1f).semantics { contentDescription = "下移 $label" },
                        enabled = editable && !removed && next != null && (canMoveMain || entry != FolderDraftEntry.Main && next != FolderDraftEntry.Main)) {
                        actions.move(settings.token, entry, 1)
                    }
                    if (existing != null) ChatButton(if (removed) "恢复" else "移除", Modifier.weight(1f)
                        .semantics { contentDescription = if (removed) "恢复 $label" else "移除 $label" },
                        enabled = editable && !existing.isShareable && (!removed || capacity)) {
                        if (removed) actions.restore(settings.token, existing.id) else actions.remove(settings.token, existing.id)
                    }
                    if (entry is FolderDraftEntry.Recommended) ChatButton("取消添加", Modifier.weight(1f)
                        .semantics { contentDescription = "取消添加 $label" }, enabled = editable) {
                        actions.stageRecommendation(settings.token, entry.key)
                    }
                }
                if (existing != null && !removed) {
                    ChatButton("颜色：${folderColorLabel(existing.colorId)}", Modifier.fillMaxWidth()
                        .semantics { contentDescription = "设置 $label 的颜色" }, enabled = independent && premiumEditable &&
                        settings.nativeState.tagsEnabled && !existing.isShareable) {
                        colorFolder = if (colorFolder == existing.id) null else existing.id
                    }
                    if (colorFolder == existing.id && premiumEditable) FolderNativeColorChoices(existing, independent && premiumEditable &&
                        settings.nativeState.tagsEnabled && !existing.isShareable) { color ->
                        colorFolder = null
                        actions.color(settings.token, existing, color)
                    }
                }
            }
        }
        item {
            SettingsAction("创建新文件夹", enabled = independent && capacity) { features.editFolder(snapshot.token, 0) }
            if (!capacity && capability != null) SettingsNote("已达到当前文件夹容量。移除并应用更改后可创建新文件夹。")
            if (capability == null && !settings.loading) SettingsNote("无法确定原生容量，请刷新后再添加。")
            SettingsAction("应用更改", enabled = editable && dirty && capability != null && applyAllowed) {
                draft?.let { confirmation = FolderApplyConfirmation(settings.token, it) }
            }
            if (dirty) SettingsNote("离页会保留未提交草稿，不会自动删除或添加文件夹。")
            if (dirty && !applyAllowed) SettingsNote("当前账号无法提交“全部”不在首位的顺序。草稿仍保留，可放弃草稿后按普通账号规则重新排序。", error = true)
        }
        if (settings.recommendations.isNotEmpty()) item {
            SettingsGroup("推荐文件夹") {
                settings.recommendations.forEach { recommendation ->
                    val staged = FolderDraftEntry.Recommended(recommendation.key) in draft?.order.orEmpty()
                    SettingsRow(recommendation.title, if (staged) "取消添加" else "添加到草稿", recommendation.description,
                        enabled = editable && (capacity || staged)) { actions.stageRecommendation(settings.token, recommendation.key) }
                }
            }
        }
        item {
            SettingsGroup("聊天行文件夹标签") {
                SettingsToggle("显示文件夹标签", settings.nativeState.tagsEnabled,
                    enabled = independent && premiumEditable) { actions.toggleTags(settings.token, it) }
                SettingsNote(if (premiumEditable) "标签开关与颜色直接提交到 TDLib。“无颜色”对应原生色号 -1。"
                    else "文件夹标签与颜色需要 Telegram 高级版。此处不会启动购买。")
            }
        }
        item {
            SettingsGroup("标签位置（本机）") {
                SettingsAccountChoice("左侧标签", preferences.placement == FolderTabPlacement.LEFT, active) {
                    onPreferencesChange(preferences.copy(placement = FolderTabPlacement.LEFT))
                }
                SettingsAccountChoice("将分组显示在顶部", preferences.placement == FolderTabPlacement.TOP, active) {
                    onPreferencesChange(preferences.copy(placement = FolderTabPlacement.TOP))
                }
            }
            SettingsGroup("标签外观（本机）") {
                listOf(FolderTabAppearance.DEFAULT to "默认", FolderTabAppearance.TEXT_ONLY to "纯文本",
                    FolderTabAppearance.ICON_TEXT to "文字和图标", FolderTabAppearance.ICON_ONLY to "仅限图标").forEach { (appearance, label) ->
                    SettingsAccountChoice(label, preferences.appearance == appearance, active) { onPreferencesChange(preferences.copy(appearance = appearance)) }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    val target = confirmation?.takeIf { active && owned && !busy && !settings.conflict && applyAllowed && it.token == settings.token && it.draft == draft }
    LocalSettingsSecurityConfirmationHost.current.Content(target != null, "应用文件夹更改", null, 520.dp, { confirmation = null }) {
        if (target != null) Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsNote("将按本页草稿提交 ${target.draft.removed.size} 项移除、${target.draft.order.count { it is FolderDraftEntry.Recommended }} 项添加和完整顺序。只移除文件夹，聊天保留，不退出群组或频道。")
            if (target.draft.removed.isNotEmpty()) SettingsRow("将移除", summary = target.draft.source.folders
                .filter { it.id in target.draft.removed }.joinToString("、") { it.name })
            SettingsRow("最终顺序", summary = target.draft.order.filter { it !is FolderDraftEntry.Existing || it.id !in target.draft.removed }
                .joinToString(" → ") { entry -> when (entry) {
                    FolderDraftEntry.Main -> "全部"
                    is FolderDraftEntry.Existing -> target.draft.source.folders.firstOrNull { it.id == entry.id }?.name ?: "文件夹不可用"
                    is FolderDraftEntry.Recommended -> recommendations[entry.key]?.title ?: "推荐不可用"
                } })
            SettingsNote("这是多步提交。TDLib 接受的操作不能撤回；后续失败时会保留尚未接受的部分。")
            ChatButton("取消应用", onClick = { confirmation = null })
            ChatButton("确认应用更改", primary = true, onClick = {
                confirmation = null
                if (active && owner?.lease?.active() == true && settings.token == target.token && draft == target.draft && applyAllowed)
                    actions.apply(target.token, target.draft.revision)
            })
        }
    }
}

private fun FolderDraftEntry.folderEntryKey(): String = when (this) {
    FolderDraftEntry.Main -> "main"
    is FolderDraftEntry.Existing -> "folder:$id"
    is FolderDraftEntry.Recommended -> "recommended:$key"
}
private fun folderColorLabel(color: Int) = when (color) {
    -1 -> "无颜色"
    0 -> "红色"
    1 -> "橙色"
    2 -> "绿色"
    3 -> "海蓝色"
    4 -> "蓝色"
    5 -> "紫色"
    6 -> "粉色"
    else -> "颜色不可用"
}

@Composable
private fun FolderNativeColorChoices(folder: ChatFolderInfo, enabled: Boolean, onSelect: (Int) -> Unit) {
    (-1..6).chunked(4).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            row.forEach { color -> ChatButton(folderColorLabel(color), Modifier.weight(1f), enabled = enabled && folder.colorId != color,
                primary = folder.colorId == color, onClick = { onSelect(color) }) }
        }
    }
}
