package com.badnng.moe

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badnng.moe.chat.*
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun ChatBadge(count: Int?) {
    UnreadBadge(count)
}

internal data class FolderEditorOwner(val folderId: Int, val revision: Long)

/** Transient account content retained by the settings route, never serialized to preferences. */
internal class FolderSettingsEditor(val owner: FolderEditorOwner, val source: ChatFolder) {
    var draft by mutableStateOf(source)
    var name by mutableStateOf(TextFieldValue(source.name))
    var query by mutableStateOf(TextFieldValue())
    val listState = LazyListState()
}

internal class FolderSettingsSession {
    private var generation: Long? = null
    private val editors = mutableMapOf<FolderEditorOwner, FolderSettingsEditor>()
    private var submittedOwner: FolderEditorOwner? = null
    var overviewScrollState = LazyListState()
        private set

    fun observe(snapshot: ChatSnapshot) {
        if (generation != snapshot.token.generation) {
            generation = snapshot.token.generation
            editors.clear()
            submittedOwner = null
            overviewScrollState = LazyListState()
        }
        val submitted = submittedOwner ?: return
        if (!snapshot.folderSaving && snapshot.folderEditorId == null && snapshot.folderError == null) {
            // Native acceptance closes the editor. Only its submitted revision is consumed,
            // including acceptance received while this settings category was unmounted.
            discard(submitted)
        } else if (!snapshot.folderSaving && snapshot.folderError != null &&
            snapshot.folderEditorId == submitted.folderId && snapshot.folderRevision == submitted.revision) {
            submittedOwner = null
        }
    }

    fun editor(source: ChatFolder, revision: Long): FolderSettingsEditor {
        val owner = FolderEditorOwner(source.id, revision)
        // Native editing has one active owner. Keep at most that owner and an outstanding
        // submission; a new native selection must not resurrect an older cancelled draft.
        editors.keys.retainAll(setOfNotNull(owner, submittedOwner))
        return editors.getOrPut(owner) { FolderSettingsEditor(owner, source) }
    }

    fun submitted(owner: FolderEditorOwner) { submittedOwner = owner }

    fun discard(owner: FolderEditorOwner) {
        editors.remove(owner)
        if (submittedOwner == owner) submittedOwner = null
    }
}

@Composable
internal fun ChatFolderManager(
    show: Boolean, snapshot: ChatSnapshot, features: ChatFeatureActions, onDismiss: () -> Unit,
    preferences: ChatFolderPreferences = ChatFolderPreferences(),
    onPreferencesChange: (ChatFolderPreferences) -> Unit = {},
) {
    val session = remember(snapshot.token.generation) { FolderSettingsSession() }
    session.observe(snapshot)
    // Keep the original modal entry point for chat-list management shortcuts.
    WindowDialog(show = show, maxWidth = 540.dp, onDismissRequest = {
        if (!snapshot.folderSaving) {
            if (snapshot.folderEditorId != null) {
                session.discard(FolderEditorOwner(snapshot.folderEditorId, snapshot.folderRevision))
                features.closeFolderEditor(snapshot.token)
            } else onDismiss()
        }
    }) {
        CompositionLocalProvider(LocalSettingsPageActive provides true) {
            FolderSettingsPages(snapshot, features, preferences, onPreferencesChange, session,
                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp), onClose = onDismiss)
        }
    }
}

/** The settings workspace embeds these pages directly; only destructive confirmation is modal. */
@Composable
internal fun ChatFolderSettingsContent(
    snapshot: ChatSnapshot,
    features: ChatFeatureActions,
    preferences: ChatFolderPreferences,
    onPreferencesChange: (ChatFolderPreferences) -> Unit,
    modifier: Modifier = Modifier,
    session: FolderSettingsSession = remember(snapshot.token.generation) { FolderSettingsSession() },
) {
    FolderSettingsPages(snapshot, features, preferences, onPreferencesChange, session,
        modifier = modifier.fillMaxSize())
}

@Composable
private fun FolderSettingsPages(
    snapshot: ChatSnapshot,
    features: ChatFeatureActions,
    preferences: ChatFolderPreferences,
    onPreferencesChange: (ChatFolderPreferences) -> Unit,
    session: FolderSettingsSession,
    modifier: Modifier,
    onClose: (() -> Unit)? = null,
) {
    val active = LocalSettingsPageActive.current
    session.observe(snapshot)
    var retainedEditor by remember(snapshot.token.generation) { mutableStateOf<FolderSettingsEditor?>(null) }
    val currentEditor = snapshot.folderEditor?.takeIf { it.id == snapshot.folderEditorId }
        ?.let { session.editor(it, snapshot.folderRevision) }
    if (currentEditor != null) SideEffect { retainedEditor = currentEditor }
    val cancelEditor = {
        if (active && !snapshot.folderSaving) {
            snapshot.folderEditorId?.let { session.discard(FolderEditorOwner(it, snapshot.folderRevision)) }
            features.closeFolderEditor(snapshot.token)
        }
    }
    val editing = snapshot.folderEditorId != null
    AnimatedContent(targetState = editing, modifier = modifier, label = "folder overview and editor",
        transitionSpec = {
            val direction = if (targetState) 1 else -1
            (slideInHorizontally(tween(240)) { direction * it / 6 } + fadeIn(tween(180))) togetherWith
                (slideOutHorizontally(tween(240)) { -direction * it / 6 } + fadeOut(tween(150)))
        }) { editorPage ->
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val editorId = snapshot.folderEditorId ?: retainedEditor?.owner?.folderId
            FolderPageHeader(if (editorPage) {
                if (editorId == 0) "新建文件夹" else "编辑文件夹"
            } else "文件夹", onBack = if (editorPage) cancelEditor else null,
                enabled = active && !snapshot.folderSaving, onClose = onClose?.let { close -> ({
                    if (active && !snapshot.folderSaving) {
                        snapshot.folderEditorId?.let { session.discard(FolderEditorOwner(it, snapshot.folderRevision)) }
                        close()
                    }
                }) })
            if (!editorPage) CompositionLocalProvider(LocalSettingsPageActive provides (active && !editing)) {
                FolderSettingsFollowupOverview(snapshot, features, preferences, onPreferencesChange,
                    modifier = Modifier.weight(1f), state = session.overviewScrollState)
            }
            else {
                val editor = if (snapshot.folderEditorId != null) currentEditor else retainedEditor
                if (editor == null) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (snapshot.folderLoading) "正在读取文件夹设置……" else "文件夹设置读取失败", fontSize = 14.sp)
                    snapshot.folderError?.let { FolderErrorText(it) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChatButton("返回", modifier = Modifier.weight(1f), enabled = active && !snapshot.folderSaving, onClick = cancelEditor)
                        ChatButton("重新读取", modifier = Modifier.weight(1f), enabled = active && !snapshot.folderLoading,
                            onClick = { snapshot.folderEditorId?.let { features.editFolder(snapshot.token, it) } })
                    }
                } else key(editor.owner) {
                    FolderEditor(editor, snapshot, features, session, cancelEditor, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun FolderPageHeader(title: String, onBack: (() -> Unit)?, enabled: Boolean, onClose: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        onBack?.let { FolderActionIcon(MiuixIcons.Regular.Back, "返回文件夹", enabled, it) }
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        onClose?.let { FolderActionIcon(MiuixIcons.Regular.Close, "关闭文件夹设置", enabled, it) }
    }
}


@Composable
private fun FolderActionIcon(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(36.dp).clip(CircleShape)
        .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, Modifier.size(20.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

@Composable
private fun FolderEditor(editor: FolderSettingsEditor, snapshot: ChatSnapshot, features: ChatFeatureActions,
    session: FolderSettingsSession, onCancel: () -> Unit, modifier: Modifier) {
    val source = editor.source
    var draft by editor::draft
    var name by editor::name
    var query by editor::query
    val active = LocalSettingsPageActive.current
    val current = snapshot.folderEditorId == editor.owner.folderId && snapshot.folderRevision == editor.owner.revision
    val enabled = active && current && !snapshot.folderSaving && !source.isShareable
    val knownChats = remember(snapshot.knownChats, query.text) {
        snapshot.knownChats.filter { query.text.isBlank() || it.title.contains(query.text, ignoreCase = true) }.sortedBy { it.title }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth().scrollEndHaptic().overScrollVertical(), state = editor.listState) {
            item {
                TextField(name, { next ->
                    draft = if (name.composition == null && next.composition == null) draft.withNameText(next.text,
                        name.selection.start, name.selection.end, next.selection.start, next.selection.end)
                    else draft.withNameText(next.text)
                    name = next
                }, label = "分类名称", enabled = enabled && source.nameEditable,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), singleLine = true)
            }
            if (draft.nameEntities.isNotEmpty() && source.nameEditable) item {
                FolderToggle("名称表情动画", draft.animateCustomEmoji, enabled) { draft = draft.copy(animateCustomEmoji = it) }
            }
            if (!source.nameEditable) item {
                Text("此名称包含当前编辑器不支持的原生格式。名称会原样保留；其他规则仍可编辑。", fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            if (source.isShareable) item {
                Text("此分类由邀请链接共享。为保留其共享设置，请使用官方客户端编辑。", fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(bottom = 12.dp))
            }
            item { Text("自动包含", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp)) }
            item { FolderToggle("联系人", draft.includeContacts, enabled) { draft = draft.copy(includeContacts = it) } }
            item { FolderToggle("非联系人", draft.includeNonContacts, enabled) { draft = draft.copy(includeNonContacts = it) } }
            item { FolderToggle("机器人", draft.includeBots, enabled) { draft = draft.copy(includeBots = it) } }
            item { FolderToggle("群组", draft.includeGroups, enabled) { draft = draft.copy(includeGroups = it) } }
            item { FolderToggle("频道", draft.includeChannels, enabled) { draft = draft.copy(includeChannels = it) } }
            item { Text("自动排除", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 8.dp)) }
            item { FolderToggle("已静音", draft.excludeMuted, enabled) { draft = draft.copy(excludeMuted = it) } }
            item { FolderToggle("已读聊天", draft.excludeRead, enabled) { draft = draft.copy(excludeRead = it) } }
            item { FolderToggle("已归档", draft.excludeArchived, enabled) { draft = draft.copy(excludeArchived = it) } }
            item {
                Text("指定聊天", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 8.dp))
                Text("点“包含”或“排除”覆盖自动规则。未加载到的聊天和原有置顶设置会保留。", fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(bottom = 8.dp))
                TextField(query, { query = it }, label = "筛选已加载的聊天", enabled = active && current,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), singleLine = true)
            }
            items(knownChats, key = { it.id }) { chat ->
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(chat.title, modifier = Modifier.weight(1f), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    ChatButton("包含", primary = chat.id in draft.includedChatIds || chat.id in draft.pinnedChatIds, enabled = enabled,
                        onClick = {
                            val included = chat.id in draft.includedChatIds || chat.id in draft.pinnedChatIds
                            draft = draft.copy(includedChatIds = if (included) draft.includedChatIds - chat.id else draft.includedChatIds + chat.id,
                                pinnedChatIds = if (included) draft.pinnedChatIds - chat.id else draft.pinnedChatIds,
                                excludedChatIds = draft.excludedChatIds - chat.id)
                        })
                    ChatButton("排除", primary = chat.id in draft.excludedChatIds, enabled = enabled,
                        onClick = { draft = draft.copy(excludedChatIds = if (chat.id in draft.excludedChatIds) draft.excludedChatIds - chat.id else draft.excludedChatIds + chat.id,
                            includedChatIds = draft.includedChatIds - chat.id, pinnedChatIds = draft.pinnedChatIds - chat.id) })
                }
            }
            item { Spacer(Modifier.height(24.dp).navigationBarsPadding()) }
        }
        snapshot.folderError?.let { FolderErrorText(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChatButton(if (draft == source) "未修改" else "还原", modifier = Modifier.weight(1f), enabled = enabled && draft != source,
                onClick = { draft = source; name = TextFieldValue(source.name) })
            ChatButton("取消", modifier = Modifier.weight(1f), enabled = active && current && !snapshot.folderSaving,
                onClick = onCancel)
            ChatButton(if (snapshot.folderSaving) "保存中" else "保存", modifier = Modifier.weight(1f), primary = true,
                enabled = enabled && draft.name.isNotBlank() && (draft != source || source.id == 0),
                onClick = {
                    if (enabled) {
                        session.submitted(editor.owner)
                        features.saveFolder(snapshot.token, draft)
                    }
                })
        }
    }
}

@Composable
private fun FolderToggle(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).chatSquircleSurface(Color.Transparent, 15.dp)
        .clickable(enabled = enabled, role = Role.Checkbox) { onChange(!checked) }
        .padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Checkbox(if (checked) ToggleableState.On else ToggleableState.Off, onClick = null, enabled = enabled)
        Text(label, fontSize = 14.sp)
    }
}

@Composable
private fun FolderErrorText(error: ChatError) {
    when (error) {
        ChatError.EMPTY_TEXT -> Text("请填写名称，并至少选择一种聊天类别或一个指定聊天。", fontSize = 12.sp,
            color = MiuixTheme.colorScheme.error)
        ChatError.TEXT_TOO_LONG -> Text("分类名称最多 12 个字符。", fontSize = 12.sp,
            color = MiuixTheme.colorScheme.error)
        ChatError.NOT_READY -> Text("文件夹来源已更改。草稿已保留，请取消后重新打开核对；不会覆盖其他位置的新设置。", fontSize = 12.sp,
            color = MiuixTheme.colorScheme.error)
        else -> ChatErrorText(error)
    }
}
