package com.badnng.moe

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.badnng.moe.chat.*
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.PopupLayoutPosition
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ChatListActionsAnchor(
    chat: ChatItem, snapshot: ChatSnapshot, features: ChatFeatureActions,
    interactive: Boolean = true, visibleViewport: Rect? = null, sourceAvailable: Boolean = true,
    orderHoldKey: Any? = null, onOrderHold: () -> Unit = {}, onOrderRelease: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val currentInteractive by rememberUpdatedState(interactive)
    val currentAvailable by rememberUpdatedState(sourceAvailable)
    val currentOrderHold by rememberUpdatedState(onOrderHold)
    var open by remember(snapshot.token, snapshot.activeList, chat.id, orderHoldKey) { mutableStateOf(false) }
    var position by remember { mutableStateOf(Offset.Zero) }
    var cardSize by remember { mutableStateOf(IntSize.Zero) }
    var cardBounds by remember { mutableStateOf(Rect.Zero) }
    var clippedBounds by remember { mutableStateOf(Rect.Zero) }
    var menuMounted by remember(snapshot.token, snapshot.activeList, chat.id, orderHoldKey) { mutableStateOf(false) }
    val cardLayer = rememberGraphicsLayer()
    val visibleBounds = if (visibleViewport != null) clippedBounds.intersect(visibleViewport) else clippedBounds
    LaunchedEffect(interactive, sourceAvailable) { if (!interactive || !sourceAvailable) open = false }
    LaunchedEffect(visibleBounds) { if (visibleBounds.isEmpty) open = false }
    LaunchedEffect(open, menuMounted, orderHoldKey, snapshot.token, snapshot.activeList, chat.id) {
        if (!open && !menuMounted) {
            val release = onOrderRelease
            // Paint the restored source in its held slot before a subsequent layout
            // applies native reordering; closing alpha alone does not release the hold.
            withFrameNanos { }
            withFrameNanos { }
            if (!open && !menuMounted) release()
        }
    }
    DisposableEffect(snapshot.token, snapshot.activeList, chat.id, orderHoldKey) {
        val release = onOrderRelease
        onDispose { release() }
    }
    Box(Modifier.fillMaxWidth().onPointerEvent(PointerEventType.Press, pass = PointerEventPass.Initial) { event ->
        if (currentInteractive && currentAvailable && event.buttons.isSecondaryPressed) {
            position = event.changes.firstOrNull()?.position ?: Offset.Zero
            currentOrderHold()
            open = true
            event.changes.forEach { it.consume() }
        }
    }) {
        // Record only the source card, before the workspace's blur. The Popup is a sibling.
        Box(Modifier.fillMaxWidth().onSizeChanged { cardSize = it }.onGloballyPositioned {
            cardBounds = it.boundsInWindow(clipBounds = false)
            clippedBounds = it.boundsInWindow()
        }.drawWithCache {
            onDrawWithContent {
                if (open || menuMounted) {
                    cardLayer.record { this@onDrawWithContent.drawContent() }
                } else drawContent()
            }
        }) { content() }
        key(orderHoldKey) {
            ChatListActionsMenu(chat, snapshot, features, position, expanded = open && interactive && sourceAvailable, onDismiss = { open = false },
                preview = cardSize.takeIf { it.width > 0 && it.height > 0 }?.let {
                    ChatListMenuPreview(cardLayer, it, cardBounds, visibleBounds)
                }, onMountedChange = { menuMounted = it })
        }
    }
}

/** A transient reference to the mounted source card, without a second avatar or row composition. */
internal class ChatListMenuPreview(val layer: GraphicsLayer, val size: IntSize, val bounds: Rect, val visibleBounds: Rect)

@Composable
internal fun ChatListActionsMenu(
    chat: ChatItem, snapshot: ChatSnapshot, features: ChatFeatureActions, position: Offset,
    expanded: Boolean, onDismiss: () -> Unit,
    preview: ChatListMenuPreview? = null,
    onMountedChange: (Boolean) -> Unit = {},
) {
    val fraction = remember(snapshot.token, snapshot.activeList, chat.id) { Animatable(0f) }
    val alpha = remember(snapshot.token, snapshot.activeList, chat.id) { Animatable(0f) }
    var mounted by remember(snapshot.token, snapshot.activeList, chat.id) { mutableStateOf(false) }
    LaunchedEffect(expanded) {
        if (expanded) {
            mounted = true
            launch { fraction.animateTo(1f, ListPopupDefaults.FractionAnimationSpec) }
            alpha.animateTo(1f, ListPopupDefaults.AlphaEnterAnimationSpec)
        } else if (mounted) {
            launch { fraction.animateTo(0f, ListPopupDefaults.FractionAnimationSpec) }
            alpha.animateTo(0f, ListPopupDefaults.AlphaExitAnimationSpec)
            fraction.snapTo(0f)
            mounted = false
        }
    }
    if (!expanded && !mounted) return
    val currentMountedChange by rememberUpdatedState(onMountedChange)
    SideEffect { currentMountedChange(true) }
    DisposableEffect(Unit) { onDispose { currentMountedChange(false) } }
    ContextMenuBackdropRegistration(expanded)
    val token = snapshot.token
    val scope = rememberCoroutineScope()
    val currentToken by rememberUpdatedState(snapshot.token)
    val currentExpanded by rememberUpdatedState(expanded)
    var folders by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<ChatError?>(null) }
    val colors = MiuixTheme.colorScheme
    val menuBackdrop = LocalWorkspaceMenuBackdrop.current ?: LocalChatBackdrop.current
    fun completed(result: ChatResult<Unit>) {
        scope.launch {
            if (currentToken == token && currentExpanded) {
                pending = false
                error = result.error ?: if (result.value == null) ChatError.REQUEST_FAILED else null
                if (error == null) onDismiss()
            }
        }
    }
    fun act(action: ChatListAction) {
        if (!currentExpanded || pending) return
        pending = true
        error = null
        features.chatListAction(token, chat.id, snapshot.activeList, action, ::completed)
    }
    val density = LocalDensity.current
    val viewport = LocalWindowInfo.current.containerSize
    val shadowPadding = 32.dp
    val shadowPaddingPixels = with(density) { shadowPadding.roundToPx() }
    val gapPixels = with(density) { 8.dp.roundToPx() }
    val maxWidth = if (viewport.width > 0) with(density) {
        (viewport.width - shadowPaddingPixels * 2).coerceAtLeast(1).toDp().coerceAtMost(238.dp)
    } else 238.dp
    val maxHeight = if (viewport.height > 0) with(density) {
        val available = if (preview == null) viewport.height - shadowPaddingPixels * 2
        else maxOf(preview.visibleBounds.top.roundToInt() - gapPixels - shadowPaddingPixels,
            viewport.height - preview.visibleBounds.bottom.roundToInt() - gapPixels - shadowPaddingPixels)
        available.coerceAtLeast(1).toDp().coerceAtMost(440.dp)
    } else 440.dp
    val focus = remember { FocusRequester() }
    var menuAbove by remember { mutableStateOf(false) }
    val menuContent: @Composable () -> Unit = {
        LaunchedEffect(expanded) { if (expanded) focus.requestFocus() }
        // Real space contains the shared Miuix reveal's shadow inside the native window.
        Box(Modifier.padding(shadowPadding)) {
            Column(Modifier.width(maxWidth).heightIn(max = maxHeight)
                .chatShortcutAnimatedSurface({ fraction.value }, { alpha.value },
                    PopupLayoutPosition(!menuAbove, menuAbove, false))
                .onPreviewKeyEvent {
                    if (!expanded) true else if (it.key == Key.Escape) {
                        if (it.type == KeyEventType.KeyUp) onDismiss()
                        true
                    } else false
                }.focusRequester(focus).focusable(expanded).pointerInput(expanded) {
                    if (!expanded) awaitPointerEventScope {
                        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                }.then(if (expanded) Modifier.semantics { paneTitle = "聊天操作" } else Modifier.clearAndSetSemantics {})
                .verticalScroll(rememberScrollState()).padding(8.dp)) {
                if (folders) {
                    ChatListMenuRow("返回", MiuixIcons.Regular.Back, !pending) { folders = false; error = null }
                    Text("添加到文件夹", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = colors.primary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                    val editable = snapshot.folders.filterNot { it.isShareable }
                    if (editable.isEmpty()) Text("暂无可编辑的文件夹", fontSize = 12.sp, color = colors.onSurfaceVariantSummary,
                        modifier = Modifier.padding(12.dp))
                    editable.forEach { folder ->
                        val included = (chat.positions[ChatListRef(folder.id)] ?: 0) > 0
                        ChatListMenuRow(folder.name + if (included) " · 已添加" else "", MiuixIcons.Regular.Folder, !pending && !included) {
                            pending = true
                            error = null
                            features.addChatToFolder(token, chat.id, folder.id, ::completed)
                        }
                    }
                } else {
                    val archived = (chat.positions[ChatListRef(-1)] ?: 0) > 0
                    val pinned = snapshot.activeList in chat.pinnedLists
                    val unread = chat.unreadCount > 0 || chat.markedUnread
                    ChatListMenuRow(if (archived) "取消归档" else "归档聊天", MiuixIcons.Regular.Folder, !pending) {
                        act(if (archived) ChatListAction.UNARCHIVE else ChatListAction.ARCHIVE)
                    }
                    ChatListMenuRow(if (pinned) "取消置顶" else "置顶聊天", MiuixIcons.Regular.Pin, !pending) {
                        act(if (pinned) ChatListAction.UNPIN else ChatListAction.PIN)
                    }
                    ChatListMenuRow(if (chat.isMuted) "开启通知" else "关闭通知", if (chat.isMuted) MiuixIcons.Regular.VolumeUp else MiuixIcons.Regular.VolumeOff, !pending) {
                        act(if (chat.isMuted) ChatListAction.UNMUTE else ChatListAction.MUTE)
                    }
                    ChatListMenuRow(if (unread) "标记为已读" else "标记为未读", MiuixIcons.Regular.Messages, !pending) {
                        act(if (unread) ChatListAction.MARK_READ else ChatListAction.MARK_UNREAD)
                    }
                    ChatListMenuRow("添加到文件夹", MiuixIcons.Regular.AddFolder, !pending) { folders = true }
                }
                if (pending) Text("正在处理……", fontSize = 12.sp, color = colors.onSurfaceVariantSummary, modifier = Modifier.padding(12.dp))
                error?.let { Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) { ChatErrorText(it) } }
            }
        }
    }
    val popupPosition = remember(position, shadowPaddingPixels, preview != null) {
        if (preview != null) ChatListOverlayPosition else ChatListMenuPosition(position, shadowPaddingPixels)
    }
    Popup(popupPositionProvider = popupPosition,
        onDismissRequest = { if (expanded) onDismiss() }, properties = PopupProperties(focusable = expanded)) {
        CompositionLocalProvider(LocalChatBackdrop provides menuBackdrop) {
            if (preview != null) ChatListAnchoredMenuLayout(preview, viewport, shadowPaddingPixels, gapPixels,
                expanded, onDismiss, onDirection = { menuAbove = it }, menuContent = menuContent)
            else menuContent()
        }
    }
}

@Composable
internal fun ChatListMenuRow(label: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    val color = if (enabled) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.disabledOnSurface
    Row(Modifier.fillMaxWidth().chatSquircleSurface(Color.Transparent, 15.dp)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.size(20.dp), tint = color)
        Text(label, fontSize = 13.sp, color = color, modifier = Modifier.weight(1f))
    }
}

internal class ChatListMenuPosition(private val offset: Offset, private val shadowPadding: Int = 0) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset =
        IntOffset((anchorBounds.left + offset.x.roundToInt() - shadowPadding).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            (anchorBounds.top + offset.y.roundToInt() - shadowPadding).coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)))
}


