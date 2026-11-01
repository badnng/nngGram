package com.badnng.moe

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.badnng.moe.chat.*
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextFieldDefaults
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
internal fun ChatListPane(
    snapshot: ChatSnapshot,
    onSelect: (Long) -> Unit,
    onRetryLoad: () -> Unit,
    onSelectList: (ChatListRef) -> Unit,
    onManageFolders: () -> Unit,
    modifier: Modifier = Modifier,
    leadingContent: (@Composable () -> Unit)? = null,
    features: ChatFeatureActions,
    onSearch: () -> Unit = {},
    folderTabs: (@Composable () -> Unit)? = null,
    collapsed: Boolean = false,
    state: ChatListPaneState = rememberChatListPaneState(snapshot.token.generation, snapshot.activeList),
    pageInteractive: Boolean = true,
    onLockNow: (() -> Unit)? = null,
) {
    val colors = MiuixTheme.colorScheme
    val compact = LocalDesktopChatLayout.current.compact
    val slim = !LocalDesktopChatLayout.current.showRail
    val paneState = state.forList(snapshot.activeList)
    var query by paneState::query
    val chats = remember(snapshot.items, query.text) { visibleChatItems(snapshot.items, ChatListFilter.ALL, query.text) }
    val listState = paneState.listState
    val tabScroll = state.tabScroll
    val scrollScope = rememberCoroutineScope()
    val showScrollToTop by remember(listState) { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    LaunchedEffect(paneState, query.text) {
        if (paneState.appliedQuery != null && paneState.appliedQuery != query.text) listState.scrollToItem(0)
        paneState.appliedQuery = query.text
    }
    val revealSelectedTabOnMount = !state.headerHasMounted
    SideEffect { state.headerHasMounted = true }
    val backdrop = rememberChatBackdrop()
    var headerHeight by remember { mutableIntStateOf(0) }
    var listViewportHeight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val headerInset = with(density) { headerHeight.toDp() }
    val headerFeather = 24.dp
    val bodyTarget = remember(snapshot, paneState, query.text) {
        ChatListBodyTarget(snapshot, paneState, query.text)
    }
    val headerContent: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth()) {
            if (collapsed) {
                Column(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    leadingContent?.invoke()
                    WorkspaceActionIcon(MiuixIcons.Regular.Search, "搜索聊天", onSearch,
                        modifier = Modifier.chatListHeaderControlSurface(18.dp, circular = true))
                    if (onLockNow != null && query.text.isEmpty()) WorkspaceActionIcon(MiuixIcons.Regular.Lock, "锁定应用", onLockNow,
                        enabled = pageInteractive, modifier = Modifier.chatListHeaderControlSurface(18.dp, circular = true))
                    folderTabs?.invoke()
                }
            } else {
                Row(Modifier.fillMaxWidth()
                    .padding(start = if (slim) 12.dp else 18.dp, end = if (slim) 8.dp else 22.dp,
                        top = if (compact) 18.dp else 28.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    leadingContent?.invoke()
                    ChatListSearch(query, { query = it }, Modifier.weight(1f).onFocusChanged { if (it.isFocused) onSearch() })
                    if (onLockNow != null && query.text.isEmpty()) WorkspaceActionIcon(MiuixIcons.Regular.Lock, "锁定应用", onLockNow,
                        enabled = pageInteractive, modifier = Modifier.chatListHeaderControlSurface(18.dp, circular = true))
                    if (slim) WorkspaceActionIcon(MiuixIcons.Regular.More, "管理文件夹", onManageFolders,
                        modifier = Modifier.chatListHeaderControlSurface(18.dp, circular = true))
                }
                if (folderTabs != null) folderTabs() else Row(Modifier.fillMaxWidth()
                    .verticalWheelToHorizontal(tabScroll).horizontalScroll(tabScroll)
                    .padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp)) {
                    ChatListTab("全部", snapshot.activeList.folderId == 0,
                        revealSelectedOnMount = revealSelectedTabOnMount,
                        revealSelectedOnChange = false,
                        unreadCount = if (slim) deviceFolderUnread(snapshot, ChatListRef())?.takeIf { it > 0 } else null) {
                        if (snapshot.activeList.folderId != 0) onSelectList(ChatListRef())
                    }
                    (if (slim) snapshot.folders else snapshot.folders.take(2)).forEach { folder ->
                        ChatListTab(folder.name, snapshot.activeList.folderId == folder.id,
                            revealSelectedOnMount = revealSelectedTabOnMount,
                            revealSelectedOnChange = false,
                            unreadCount = if (slim) deviceFolderUnread(snapshot, ChatListRef(folder.id))?.takeIf { it > 0 } else null) {
                            if (snapshot.activeList.folderId != folder.id) onSelectList(ChatListRef(folder.id))
                        }
                    }
                    if (slim || snapshot.activeList.folderId == -1) {
                        ChatListTab("已归档", snapshot.activeList.folderId == -1,
                            revealSelectedOnMount = revealSelectedTabOnMount,
                            revealSelectedOnChange = false,
                            unreadCount = deviceFolderUnread(snapshot, ChatListRef(-1))?.takeIf { it > 0 }) {
                            if (snapshot.activeList.folderId != -1) onSelectList(ChatListRef(-1))
                        }
                    }
                }
            }
        }
    }
    Box(modifier.background(colors.surface)) {
        CompositionLocalProvider(LocalChatBackdrop provides backdrop) {
        // Folder changes move only the sampled list body. The actual header and its
        // controls remain mounted once above this source through every transition frame.
        key(snapshot.token.generation) {
            AnimatedContent(bodyTarget,
                modifier = Modifier.fillMaxSize().then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier),
                contentKey = { it.snapshot.activeList }, label = "folder list body",
                transitionSpec = {
                    val order = targetState.snapshot.folders.map { it.id }.toMutableList().apply {
                        add(targetState.snapshot.mainListPosition.coerceIn(0, size), 0)
                        add(-1)
                    }
                    val direction = if (order.indexOf(targetState.snapshot.activeList.folderId) >=
                        order.indexOf(initialState.snapshot.activeList.folderId)) 1 else -1
                    (slideInHorizontally(tween(220)) { direction * it / 8 } + fadeIn(tween(180))) togetherWith
                        (slideOutHorizontally(tween(180)) { -direction * it / 8 } + fadeOut(tween(140)))
                }) { shown ->
                val interactive = pageInteractive && shown.snapshot.activeList == snapshot.activeList && shown.state === paneState
                ChatListBody(shown, headerInset, collapsed, features, interactive, onSelect, onRetryLoad,
                    onViewportHeight = { if (interactive) listViewportHeight = it })
            }
        }
        // This glass consumer must be outside the scrolling layer it samples.
        androidx.compose.animation.AnimatedVisibility(showScrollToTop,
            Modifier.align(if (collapsed) Alignment.BottomCenter else Alignment.BottomStart)
                .padding(start = if (collapsed) 0.dp else 10.dp, bottom = 10.dp),
            enter = fadeIn(), exit = fadeOut()) {
            FloatingScrollButton(up = true, modifier = Modifier.padding(8.dp),
                onClick = { scrollScope.launch { listState.animateScrollToItem(0) } },
                size = if (compact) 32.dp else 40.dp)
        }
        Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().onSizeChanged { headerHeight = it.height }) {
            ChatProgressiveBlur(backdrop, bottom = false,
                Modifier.matchParentSize().blurFeather(headerFeather, bottom = false))
            headerContent()
        }
        // Keep the thumb sharp and interactive above the glass, outside its sampled source.
        if (chats.isNotEmpty() && listViewportHeight > 0) {
            WorkspaceScrollbar(listState, Modifier.align(Alignment.TopEnd)
                .height(with(density) { listViewportHeight.toDp() })
                .padding(start = 2.dp, end = 2.dp, top = headerInset + 8.dp, bottom = 8.dp))
        }
        }
    }
}

/** Freeze the outgoing folder's query and exact scroll owner while its body exits. */
private data class ChatListBodyTarget(
    val snapshot: ChatSnapshot,
    val state: ChatListPaneListState,
    val query: String,
) {
    override fun toString() = "ChatListBodyTarget(redacted)"
}

@Composable
private fun ChatListBody(
    target: ChatListBodyTarget,
    headerInset: Dp,
    collapsed: Boolean,
    features: ChatFeatureActions,
    interactive: Boolean,
    onSelect: (Long) -> Unit,
    onRetryLoad: () -> Unit,
    onViewportHeight: (Int) -> Unit,
) {
    val snapshot = target.snapshot
    val colors = MiuixTheme.colorScheme
    val liveChats = remember(snapshot.items, target.query) {
        visibleChatItems(snapshot.items, ChatListFilter.ALL, target.query)
    }
    val menuOrder = remember(target.state, snapshot.token.generation, snapshot.activeList, target.query) { ChatListMenuOrder() }
    val chats = menuOrder.display(liveChats, snapshot.items)
    val nativeIds = remember(snapshot.items) { snapshot.items.mapTo(mutableSetOf()) { it.id } }
    val listState = target.state.listState
    var visibleViewport by remember { mutableStateOf<Rect?>(null) }
    val headerInsetPixels = with(LocalDensity.current) { headerInset.toPx() }
    val visible by remember(listState) {
        derivedStateOf { listState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? Long }.toSet() }
    }
    Column(Modifier.fillMaxSize().workspacePageInput(interactive)) {
        if (chats.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f).padding(top = headerInset)
                .padding(if (collapsed) 8.dp else 24.dp), contentAlignment = Alignment.Center) {
                Text(when {
                    collapsed -> if (snapshot.listLoading) "加载中" else "暂无"
                    snapshot.listLoading -> "正在加载聊天……"
                    snapshot.listError != null -> "聊天列表暂时无法加载"
                    target.query.isNotBlank() -> "没有匹配的会话"
                    snapshot.listEnd -> "这个分类里还没有聊天"
                    else -> "等待聊天列表更新……"
                }, fontSize = if (collapsed) 11.sp else 14.sp, color = colors.onSurfaceVariantSummary)
            }
        } else {
            Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { onViewportHeight(it.height) }
                .onGloballyPositioned { coordinates ->
                    val bounds = coordinates.boundsInWindow()
                    // The fixed header is drawn above this scrolling body. A lifted
                    // partially visible row keeps the same viewport clipping.
                    visibleViewport = Rect(bounds.left, (bounds.top + headerInsetPixels).coerceAtMost(bounds.bottom),
                        bounds.right, bounds.bottom)
                }) {
                LazyColumn(Modifier.fillMaxSize().scrollEndHaptic().overScrollVertical(), state = listState,
                    contentPadding = PaddingValues(start = if (collapsed) 8.dp else 10.dp,
                        end = if (collapsed) 8.dp else 10.dp, top = headerInset + 8.dp, bottom = 24.dp),
                    // Keep the official row's internal geometry; separate this app's
                    // independent Miuix cards with the user's requested breathing room.
                    verticalArrangement = Arrangement.spacedBy(if (collapsed) 5.dp else 4.dp)) {
                    items(chats, key = { it.id }) { chat ->
                        val menuOwner = remember(menuOrder, snapshot.token, chat.id) { Any() }
                        var resumeAtTop by remember(menuOwner) { mutableStateOf(false) }
                        val sourceAvailable = chat.id in nativeIds
                        val row: @Composable () -> Unit = {
                            ChatListRow(chat, snapshot.selected?.id == chat.id, snapshot, features,
                                interactive && chat.id in visible, collapsed) {
                                if (interactive && sourceAvailable) onSelect(chat.id)
                            }
                        }
                        // Retain the source row and its graphics layer through exit;
                        // the anchor closes its native popup independently of this gate.
                        ChatListActionsAnchor(chat, snapshot, features, interactive = interactive,
                            visibleViewport = visibleViewport, sourceAvailable = sourceAvailable, orderHoldKey = menuOrder,
                            onOrderHold = {
                                resumeAtTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
                                menuOrder.hold(menuOwner, chats)
                            }, onOrderRelease = {
                                if (menuOrder.isHeldBy(menuOwner)) {
                                    if (resumeAtTop) listState.requestScrollToItem(0, 0)
                                    menuOrder.release(menuOwner)
                                }
                            }, content = row)
                    }
                    item { Spacer(Modifier.navigationBarsPadding()) }
                }
            }
        }
        snapshot.listError?.let {
            if (collapsed) {
                ChatButton("重试", Modifier.fillMaxWidth().padding(4.dp),
                    enabled = interactive && !snapshot.listLoading, onClick = { if (interactive) onRetryLoad() })
            } else {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { ChatErrorText(it) }
                    ChatButton("重试", enabled = interactive && !snapshot.listLoading,
                        onClick = { if (interactive) onRetryLoad() })
                }
            }
        }
    }
}

@Composable
internal fun ChatListSearch(value: TextFieldValue, onValueChange: (TextFieldValue) -> Unit, modifier: Modifier = Modifier) {
    val colors = MiuixTheme.colorScheme
    TextField(
        value = value,
        onValueChange = onValueChange,
        label = "Search",
        useLabelAsPlaceholder = true,
        singleLine = true,
        textStyle = MiuixTheme.textStyles.main.copy(fontSize = 16.sp, lineHeight = 20.sp),
        insideMargin = DpSize(14.dp, 10.dp),
        cornerRadius = 22.dp,
        colors = TextFieldDefaults.textFieldColors(
            backgroundColor = Color.Transparent,
            labelColor = colors.onSurfaceVariantSummary,
        ),
        leadingIcon = {
            Icon(MiuixIcons.Regular.Search, null, Modifier.padding(start = 14.dp, end = 8.dp).size(20.dp),
                tint = colors.onSurfaceVariantSummary)
        },
        trailingIcon = if (value.text.isNotEmpty()) ({
            SearchFieldClearButton("清除搜索") { onValueChange(TextFieldValue()) }
        }) else null,
        modifier = modifier.heightIn(min = 44.dp)
            .chatListHeaderControlSurface(22.dp)
            .semantics { contentDescription = "搜索当前聊天列表" },
    )
}

@Composable
internal fun ChatListTab(
    label: String,
    isSelected: Boolean,
    unreadCount: Int? = null,
    revealSelectedOnMount: Boolean = true,
    revealSelectedOnChange: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val compact = LocalDesktopChatLayout.current.compact
    val fontSize = if (compact) 12.sp else 14.sp
    val labelWidth = chatTabLabelWidth(label, fontSize)
    val bringIntoView = remember { BringIntoViewRequester() }
    var initialSelection by remember { mutableStateOf(true) }
    LaunchedEffect(isSelected) {
        val reveal = if (initialSelection) revealSelectedOnMount else revealSelectedOnChange
        if (isSelected && reveal) {
            bringIntoView.bringIntoView()
        }
        initialSelection = false
    }
    Box(Modifier.widthIn(min = if (compact) 50.dp else 70.dp, max = 128.dp).height(if (compact) 32.dp else 36.dp)
        .bringIntoViewRequester(bringIntoView)
        .chatListHeaderControlSurface(16.dp)
        .clickable(role = Role.Tab, onClick = onClick).semantics { selected = isSelected }
        .padding(horizontal = if (compact) 10.dp else 16.dp), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(label, fontSize = fontSize, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isSelected) colors.primary else colors.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = (if (unreadCount != null) Modifier.weight(1f, fill = false) else Modifier).width(labelWidth))
            unreadCount?.let { ChatBadge(it) }
        }
    }
}

/** Reserve both real font advances so selection changes never resize the header's labels. */
@Composable
internal fun chatTabLabelWidth(label: String, fontSize: TextUnit): Dp {
    val measurer = rememberTextMeasurer(cacheSize = 4)
    val style = MiuixTheme.textStyles.main.copy(fontSize = fontSize)
    val normal = measurer.measure(label, style = style.copy(fontWeight = FontWeight.Normal), softWrap = false, maxLines = 1)
    val selected = measurer.measure(label, style = style.copy(fontWeight = FontWeight.SemiBold), softWrap = false, maxLines = 1)
    return with(LocalDensity.current) { maxOf(normal.size.width, selected.size.width).toDp() }
}

/** Sample the list once for each control, with its downward shadow kept outside the glass. */
@Composable
internal fun Modifier.chatListHeaderControlSurface(
    cornerRadius: Dp,
    circular: Boolean = false,
): Modifier {
    val dark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
    val shape = if (circular) CircleShape else rememberMiuixSquircleShape(cornerRadius)
    val context = LocalGraphicsContext.current
    val shadow = remember(dark) {
        Shadow(radius = 6.dp, color = Color.Black.copy(alpha = if (dark) 0.45f else 0.16f),
            offset = DpOffset(0.dp, 3.dp))
    }
    val painter = remember(context, shape, shadow) { context.shadowContext.createDropShadowPainter(shape, shadow) }
    return drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val path = when (outline) {
            is Outline.Generic -> outline.path
            is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
            is Outline.Rectangle -> Path().apply { addRect(outline.rect) }
        }
        onDrawBehind {
            clipPath(path, ClipOp.Difference) { with(painter) { draw(size) } }
        }
    }.chatGlassSurface(cornerRadius, shadow = false, circular = circular)
}

@Composable
private fun ChatListRow(
    chat: ChatItem, isSelected: Boolean, snapshot: ChatSnapshot, features: ChatFeatureActions,
    visible: Boolean, collapsed: Boolean, onClick: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    if (collapsed) {
        Box(Modifier.fillMaxWidth().height(64.dp)) {
            Box(Modifier.matchParentSize()
                .chatSquircleSurface(if (isSelected) colors.tertiaryContainer else Color.Transparent, 16.dp)
                .clickable(onClick = onClick).semantics { selected = isSelected; contentDescription = chat.title },
                contentAlignment = Alignment.Center) {
                ChatIdentityAvatar(chat, snapshot, features, size = 44.dp, visible = visible)
            }
            if (chat.unreadCount > 0 || chat.markedUnread) {
                // The badge is a sibling of the clipped selection surface and stays inside the row.
                UnreadBadge(chat.unreadCount.takeIf { it > 0 }, compact = true,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 2.dp, bottom = 5.dp))
            }
        }
        return
    }
    // tdesktop a7f453 defaultDialogRow: 62px, 46px photo at (10,8),
    // name at (68,10), preview at (68,34). Keep controls in the same Miuix surface.
    val rowTextStyle = chatMessageTextStyle()
    val tags = chatFolderTags(chat, snapshot)
    Box(Modifier.fillMaxWidth().height(if (tags.isEmpty()) 62.dp else 72.dp)
        .chatSquircleSurface(if (isSelected) colors.tertiaryContainer else colors.surfaceContainer, 16.dp)
        .clickable(onClick = onClick).semantics { selected = isSelected }) {
        ChatIdentityAvatar(chat, snapshot, features, size = 46.dp, visible = visible,
            modifier = Modifier.padding(start = 10.dp, top = 8.dp))
        Row(Modifier.fillMaxWidth().padding(start = 68.dp, end = 10.dp).offset(y = 10.dp),
            verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(chat.title.ifBlank { "未命名聊天" }, style = rowTextStyle.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(chat.lastMessage?.let { chatListTime(it.date) }.orEmpty(), style = rowTextStyle,
                color = if (isSelected) colors.primary else colors.onSurfaceVariantActions, maxLines = 1)
        }
        Row(Modifier.fillMaxWidth().padding(start = 68.dp, end = 10.dp).offset(y = if (tags.isEmpty()) 34.dp else 30.dp),
            verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(chat.lastMessage?.let { (if (it.outgoing) "你：" else "") + messagePreview(it) } ?: "暂无消息",
                style = rowTextStyle, color = colors.onSurfaceVariantSummary,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (chat.unreadCount > 0 || chat.markedUnread) ChatBadge(chat.unreadCount.takeIf { it > 0 })
            else if (snapshot.activeList in chat.pinnedLists) {
                Icon(MiuixIcons.Regular.Pin, "已置顶", Modifier.size(16.dp), tint = colors.onSurfaceVariantActions)
            }
        }
        if (tags.isNotEmpty()) ChatFolderTagStrip(tags,
            Modifier.fillMaxWidth().padding(start = 68.dp, end = 15.dp).offset(y = 52.dp), selected = isSelected)
    }
}
