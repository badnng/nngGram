package com.badnng.moe

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.badnng.moe.chat.*
import com.badnng.moe.media.LocalAttachmentClipboard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.PopupLayoutPosition
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import kotlin.math.roundToInt

/** The popup belongs to the message anchor and uses the same Miuix controls as the conversation. */
@Composable
internal fun MessageActionsMenu(
    message: ChatMessage,
    snapshot: ChatSnapshot,
    canReply: Boolean,
    canQuote: Boolean,
    onReply: () -> Unit,
    onQuote: () -> Unit,
    onReact: () -> Unit,
    onReadDetails: () -> Unit,
    onReload: () -> Unit,
    onEdit: () -> Unit,
    onForward: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    expanded: Boolean = true,
    features: ChatFeatureActions? = null,
    onSearchSender: ((Long) -> Unit)? = null,
    copyRequest: MessageTextCopyRequest? = null,
    pageInteractive: Boolean = true,
    preview: MessageMenuPreview? = null,
    onMountedChange: (Boolean) -> Unit = {},
    onSaveAs: ((String, String) -> Unit)? = null,
) {
    val acceptsInput = expanded && pageInteractive
    val backdropState = LocalContextMenuBackdrop.current
    val backdropOwner = remember(snapshot.token, message.id) { Any() }
    val currentBackdropBlur by rememberUpdatedState(LocalContextMenuBlurProgress.current)
    val currentPreview by rememberUpdatedState(preview)
    val currentPageInteractive by rememberUpdatedState(pageInteractive)
    val fraction = remember(snapshot.token, message.id) { Animatable(0f) }
    val alpha = remember(snapshot.token, message.id) { Animatable(0f) }
    var mounted by remember(snapshot.token, message.id) { mutableStateOf(false) }
    var popupMounted by remember(snapshot.token, message.id) { mutableStateOf(false) }
    LaunchedEffect(acceptsInput) {
        if (acceptsInput) {
            mounted = true
            popupMounted = true
            launch { fraction.animateTo(1f, ListPopupDefaults.FractionAnimationSpec) }
            alpha.animateTo(1f, ListPopupDefaults.AlphaEnterAnimationSpec)
        } else if (mounted) {
            launch { fraction.animateTo(0f, ListPopupDefaults.FractionAnimationSpec) }
            alpha.animateTo(0f, ListPopupDefaults.AlphaExitAnimationSpec)
            fraction.snapTo(0f)
            // The native hit rectangle ends with the actions. A pure drawing host can
            // keep the same clear source through the workspace's remaining blur tail.
            popupMounted = false
            val returningToTimeline = snapshotFlow {
                when {
                    !currentPageInteractive || currentPreview == null -> false
                    backdropState?.hasExpandedBlurOwnerOtherThan(backdropOwner) == true -> false
                    currentBackdropBlur() <= 0f -> true
                    else -> null
                }
            }.first { it != null } == true
            if (returningToTimeline) {
                // A frame-clock handoff gives the zero-blur workspace a completed draw
                // before removing its clear source. Reopening cancels this entire job.
                withFrameNanos { }
                withFrameNanos { }
            }
            mounted = false
        }
    }
    if (!acceptsInput && !mounted) return
    val hasClearSourceHost = backdropState?.hasClearSourceHost == true
    // Standalone callers without a drawing host keep the original clear-source Popup
    // until handoff; a menu without a source has no visual tail to retain.
    val showPopup = acceptsInput || popupMounted ||
        (mounted && pageInteractive && preview != null && !hasClearSourceHost)
    val tailSource = preview.takeIf { !showPopup && pageInteractive && hasClearSourceHost }
    val mountedChange by rememberUpdatedState(onMountedChange)
    SideEffect { mountedChange(showPopup || tailSource != null) }
    DisposableEffect(Unit) { onDispose { mountedChange(false) } }
    if (showPopup) ContextMenuBackdropRegistration(acceptsInput, owner = backdropOwner)
    ContextMenuClearSourceRegistration(backdropOwner, tailSource)
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    val copyScope = rememberCoroutineScope()
    val attachmentClipboard = LocalAttachmentClipboard.current
    val latestSnapshot by rememberUpdatedState(snapshot)
    val currentToken by rememberUpdatedState(snapshot.token)
    val menuExpanded by rememberUpdatedState(acceptsInput)
    // Freeze a request even in hosts that do not supply a text-selection container.
    val fallbackCopyRequest = remember(snapshot.token, message.id, expanded) {
        messageTextCopyRequest(snapshot.token, message, emptyList())
    }
    val frozenCopyRequest = copyRequest ?: fallbackCopyRequest
    var copyingLink by remember(message.id) { mutableStateOf(false) }
    var linkError by remember(message.id) { mutableStateOf<ChatError?>(null) }
    var copyingMedia by remember(message.id) { mutableStateOf(false) }
    var mediaCopyFailed by remember(message.id) { mutableStateOf(false) }
    var readersExpanded by remember(snapshot.token, message.id) { mutableStateOf(false) }
    var reactionPickerExpanded by remember(snapshot.token, message.id) { mutableStateOf(false) }
    var retainedReactionSnapshot by remember(message.id) { mutableStateOf(snapshot) }
    SideEffect { if (expanded) retainedReactionSnapshot = snapshot }
    val reactionSnapshot = if (expanded) snapshot else retainedReactionSnapshot
    LaunchedEffect(acceptsInput, mounted) {
        if (!acceptsInput && !mounted) reactionPickerExpanded = false
    }
    LaunchedEffect(acceptsInput, message.id) {
        if (acceptsInput && features != null && message.id > 0 && message.delivery == MessageDelivery.SENT) {
            features.loadReactions(snapshot.token, message.id)
            if (message.outgoing) features.loadReadDetails(snapshot.token, message.id)
        }
        if (!acceptsInput) { copyingLink = false; linkError = null; readersExpanded = false }
    }
    val current = snapshot.actionsMessageId == message.id
    val requestable = message.id > 0
    val livePresentation = MessageMenuPresentation(
        actions = snapshot.messageActions.takeIf { current },
        loading = requestable && (!current || snapshot.actionsLoading),
        error = snapshot.actionsError.takeIf { current },
        canReply = canReply,
        canQuote = canQuote,
        editPending = snapshot.editPending,
        mutationPending = snapshot.mutationPending,
    )
    var retainedPresentation by remember(message.id) { mutableStateOf(livePresentation) }
    SideEffect { if (expanded) retainedPresentation = livePresentation }
    // Closing clears the native action selection immediately. Keep the last visible rows while they fade out.
    val presentation = if (expanded) livePresentation else retainedPresentation
    val actions = presentation.actions
    val density = LocalDensity.current
    val viewport = LocalWindowInfo.current.containerSize
    val menuBackdrop = LocalWorkspaceMenuBackdrop.current ?: LocalChatBackdrop.current
    // The action rows intentionally have transparent Miuix feedback surfaces. Give
    // their one shared glass body enough of the real backdrop to remain visibly mixed.
    val menuTintAlpha = if (MiuixTheme.colorScheme.surface.luminance() < 0.5f) 0.72f else 0.60f
    val menuMargin = with(density) { 12.dp.roundToPx() }
    val shadowPadding = with(density) { ChatShortcutShadowPadding.roundToPx() }
    var availableMenuHeight by remember(message.id, menuMargin) { mutableStateOf<Int?>(null) }
    val pickerExpandedNow by rememberUpdatedState(reactionPickerExpanded)
    val position = remember(message.id, menuMargin) { MessageMenuPosition(menuMargin) {
        // The outgoing menu keeps its original measurement while the picker grows.
        if (!pickerExpandedNow) availableMenuHeight = it
    } }
    val popupPosition = remember(position, shadowPadding) { ChatShortcutPopupPosition(position, shadowPadding) }
    val viewportHeight = (viewport.height - shadowPadding * 2).coerceAtLeast(1)
    val fallbackMaxHeight = if (viewport.height > 0) with(density) {
        minOf(availableMenuHeight ?: viewportHeight, viewportHeight).toDp().coerceAtMost(600.dp)
    } else 600.dp
    val menuWidth = if (viewport.width > 0) with(density) {
        (viewport.width - shadowPadding * 2).coerceAtLeast(1).toDp().coerceAtMost(248.dp)
    } else 248.dp
    val pickerWidth = if (viewport.width > 0) with(density) {
        (viewport.width - shadowPadding * 2).coerceAtLeast(1).toDp().coerceAtMost(320.dp)
    } else 320.dp
    val pickerMaxHeight = if (viewport.height > 0) with(density) { viewportHeight.toDp().coerceAtMost(440.dp) } else 440.dp
    var measuredMenuSize by remember(snapshot.token, message.id, reactionPickerExpanded, viewport,
        preview?.anchorBounds, preview?.viewport) { mutableStateOf<IntSize?>(null) }
    var openingSide by remember(snapshot.token, message.id, reactionPickerExpanded, viewport,
        preview?.anchorBounds, preview?.viewport) { mutableStateOf<MessageMenuSide?>(null) }
    val placement = preview?.let { source ->
        val window = IntRect(0, 0, viewport.width, viewport.height)
        val visibleViewport = source.viewport?.let { IntRect(it.left.roundToInt(), it.top.roundToInt(),
            it.right.roundToInt(), it.bottom.roundToInt()).intersect(window) } ?: window
        val anchor = source.anchorBounds
        anchoredMessageMenuPlacement(IntRect(anchor.left.roundToInt(), anchor.top.roundToInt(),
            anchor.right.roundToInt(), anchor.bottom.roundToInt()), visibleViewport,
            IntSize(with(density) { (if (reactionPickerExpanded) pickerWidth else menuWidth).roundToPx() },
                if (openingSide == null && measuredMenuSize != null) checkNotNull(measuredMenuSize).height
                else with(density) { (if (reactionPickerExpanded) pickerMaxHeight else 600.dp).roundToPx() }),
            shadowPadding, with(density) { 8.dp.roundToPx() }, with(density) { 120.dp.roundToPx() },
            alignEnd = message.outgoing, openingSide = openingSide)
    }
    // Native permissions can append rows. Keep the measured opening side and let only its
    // bounded scrollable body grow; changing the source or viewport establishes a new side.
    SideEffect { if (openingSide == null && measuredMenuSize != null) openingSide = placement?.side }
    val maxHeight = placement?.let { with(density) { it.body.height.toDp() } } ?: fallbackMaxHeight
    val currentMenuWidth = placement?.let { with(density) { it.body.width.toDp() } }
    val overlayPosition = remember(snapshot.token, message.id, preview?.bounds, preview?.visibleBounds,
        placement, measuredMenuSize) { MessageMenuOverlayPosition() }
    val menuContent: @Composable () -> Unit = {
            ChatShortcutShadowSpace {
            val focus = remember { FocusRequester() }
            LaunchedEffect(acceptsInput) { if (acceptsInput) focus.requestFocus() }
            Box(Modifier.chatShortcutAnimatedSurface({ fraction.value }, { alpha.value },
                PopupLayoutPosition(placement?.above != true, placement?.above == true, message.outgoing),
                surfaceOpacity = menuTintAlpha)
                .onSizeChanged { measuredMenuSize = it }
                .onPreviewKeyEvent {
                    if (!acceptsInput) true else if (it.key == Key.Escape) {
                        if (it.type == KeyEventType.KeyUp) onDismiss()
                        true
                    } else false
                }.focusRequester(focus).focusable(acceptsInput).pointerInput(acceptsInput) {
                    if (!acceptsInput) awaitPointerEventScope {
                        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                }.then(if (acceptsInput) Modifier.semantics { paneTitle = "消息操作面板" }
                    else Modifier.clearAndSetSemantics {})) {
                ReactionEmojiCache {
                    MessageReactionTransition(reactionPickerExpanded && features != null, acceptsInput, embeddedSurface = true,
                        picker = { interactive ->
                            features?.let {
                                MessageReactionPickerPanel(message, reactionSnapshot, it, onDismiss, acceptsInput,
                                    modifier = Modifier.width(currentMenuWidth ?: pickerWidth).heightIn(
                                        max = if (placement == null) pickerMaxHeight else minOf(pickerMaxHeight, maxHeight)),
                                    embedded = true, focusSearch = interactive)
                            }
                        }, menu = { interactive ->
                            Column(Modifier.width(currentMenuWidth ?: menuWidth).heightIn(max = maxHeight)) {
                                if (features != null && requestable && message.delivery == MessageDelivery.SENT)
                                    MessageQuickReactionBar(message, reactionSnapshot, features,
                                        onMore = { reactionPickerExpanded = true; readersExpanded = false }, onSelected = onDismiss,
                                        embedded = true, interactive = acceptsInput)
                                Box(Modifier.weight(1f, fill = false)) {
                                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                        if (message.outgoing && requestable && message.delivery == MessageDelivery.SENT) {
                                            MessageReadersMenuRow(message.id, snapshot, features, expanded = interactive && readersExpanded,
                                                onClick = { readersExpanded = true; onReadDetails() }, onHover = { if (interactive) readersExpanded = true },
                                                onRetry = onReadDetails, onDismiss = onDismiss)
                                            WorkspaceDivider(Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                                        }
                                        MessageMenuRow("回复", MiuixIcons.Regular.Reply, presentation.canReply, onReply)
                                        if (presentation.canQuote) MessageMenuRow("引文回复", MiuixIcons.Regular.ReplyAll,
                                            presentation.canReply, onQuote)
                                        if (!message.senderIsChat && message.senderId > 0 && onSearchSender != null) {
                                            MessageMenuRow("该用户的其他消息", MiuixIcons.Regular.Search,
                                                onClick = { onDismiss(); onSearchSender(message.senderId) })
                                        }
                                        if (validatedMessageTextCopy(snapshot, frozenCopyRequest, pageInteractive) != null &&
                                            frozenCopyRequest.source.text.isNotEmpty()) {
                                            MessageMenuRow(if (frozenCopyRequest.selection != null) "复制选中文本" else "复制文本",
                                                MiuixIcons.Regular.Copy, onClick = {
                                                val copied = validatedMessageTextCopy(latestSnapshot, frozenCopyRequest,
                                                    menuExpanded && currentPageInteractive) ?: return@MessageMenuRow
                                                clipboard.setText(AnnotatedString(copied.text))
                                                onDismiss()
                                            })
                                        }
                                        val copySource = clipboardMessageSource(reactionSnapshot, message.id)
                                        if (onSaveAs != null && copySource != null) {
                                            MessageMenuRow("另存为", MiuixIcons.Regular.FileDownloads, onClick = {
                                                if (!menuExpanded || !currentPageInteractive ||
                                                    clipboardMessageSource(latestSnapshot, message.id) != copySource) return@MessageMenuRow
                                                val currentMedia = latestSnapshot.messages.firstOrNull { it.id == message.id }?.media
                                                    ?: return@MessageMenuRow
                                                val name = currentMedia.fileName.ifBlank {
                                                    if (copySource.isPhoto) "Telegram-image.jpg" else "Telegram-file"
                                                }
                                                onDismiss()
                                                onSaveAs(copySource.path, name)
                                            })
                                        }
                                        if (attachmentClipboard != null && copySource != null) {
                                            fun copyMedia(asImage: Boolean) {
                                                copyingMedia = true
                                                mediaCopyFailed = false
                                                copyScope.launch {
                                                    try {
                                                        if (copyMessageAttachment(message.id, asImage, attachmentClipboard,
                                                            current = { latestSnapshot }, active = { menuExpanded })) onDismiss()
                                                        else if (menuExpanded) mediaCopyFailed = true
                                                    } catch (cancelled: CancellationException) { throw cancelled }
                                                    catch (_: Exception) { if (menuExpanded) mediaCopyFailed = true }
                                                    finally { copyingMedia = false }
                                                }
                                            }
                                            if (copySource.isPhoto) MessageMenuRow("复制图片", MiuixIcons.Regular.Copy,
                                                enabled = !copyingMedia, onClick = { copyMedia(true) })
                                            MessageMenuRow("复制文件", MiuixIcons.Regular.Copy,
                                                enabled = !copyingMedia, onClick = { copyMedia(false) })
                                            if (mediaCopyFailed) Text("无法复制媒体，请重试。", fontSize = 11.sp,
                                                color = MiuixTheme.colorScheme.error, modifier = Modifier.padding(8.dp))
                                        }
                                        if (features != null && requestable && message.delivery == MessageDelivery.SENT) {
                                            MessageMenuRow(if (copyingLink) "正在获取链接……" else "拷贝消息链接", MiuixIcons.Regular.Link,
                                                enabled = !copyingLink, onClick = {
                                                    copyingLink = true
                                                    linkError = null
                                                    val requestedToken = snapshot.token
                                                    features.requestMessageLink(requestedToken, message.id) { result ->
                                                        copyScope.launch {
                                                            if (menuExpanded && currentToken == requestedToken) {
                                                                copyingLink = false
                                                                val link = result.value
                                                                if (result.error == null && !link.isNullOrBlank()) {
                                                                    clipboard.setText(AnnotatedString(link))
                                                                    onDismiss()
                                                                } else linkError = result.error ?: ChatError.REQUEST_FAILED
                                                            }
                                                        }
                                                    }
                                                })
                                        }
                                        if (features == null) MessageMenuRow("表情回应", MiuixIcons.Regular.Favorites,
                                            message.id > 0 && message.delivery == MessageDelivery.SENT, onReact)
                                        when {
                                            !requestable -> Text("消息发送完成后可查看可用操作。", fontSize = 11.sp,
                                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(8.dp))
                                            presentation.loading -> Text("正在获取可用操作……", fontSize = 11.sp,
                                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(10.dp))
                                            presentation.error != null -> {
                                                ChatErrorText(presentation.error)
                                                MessageMenuRow("重新获取操作", MiuixIcons.Regular.Update, onClick = onReload)
                                            }
                                            actions != null -> {
                                                if (actions.canEditText || actions.canEditCaption) MessageMenuRow(
                                                    if (actions.canEditText) "编辑消息" else "编辑说明", MiuixIcons.Regular.Edit,
                                                    enabled = !presentation.editPending && !presentation.mutationPending, onClick = onEdit)
                                                if (actions.canForward) MessageMenuRow("转发", MiuixIcons.Regular.Forward,
                                                    enabled = !presentation.mutationPending, onClick = onForward)
                                                if (actions.canDeleteForSelf || actions.canDeleteForAll) MessageMenuRow("删除", MiuixIcons.Regular.Delete,
                                                    enabled = !presentation.mutationPending, onClick = onDelete)
                                            }
                                        }
                                        linkError?.let {
                                            Text(if (it == ChatError.FORBIDDEN || it == ChatError.UNSUPPORTED) "此消息没有可复制的消息链接。" else "消息链接获取失败，请重试。",
                                                fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(10.dp))
                                        }
                                    }
                                }
                                }
                    })
                }
            }
            }
    }
    if (showPopup) Popup(popupPositionProvider = if (preview != null) overlayPosition else popupPosition,
        onDismissRequest = { if (acceptsInput) onDismiss() },
        properties = PopupProperties(focusable = acceptsInput, dismissOnClickOutside = !readersExpanded)) {
        CompositionLocalProvider(LocalChatBackdrop provides menuBackdrop) {
            if (preview != null && placement != null) MessageAnchoredMenuLayout(preview, viewport, placement,
                shadowPadding, acceptsInput, overlayPosition, onDismiss,
                fractionProgress = { fraction.value }, alphaProgress = { alpha.value },
                rightAligned = message.outgoing, menuContent = menuContent)
            else menuContent()
        }
    }
}

@Composable
internal fun MessageMenuRow(label: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null) {
    val colors = MiuixTheme.colorScheme
    val color = if (enabled) colors.onSurface else colors.disabledOnSurface
    Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).chatSquircleSurface(Color.Transparent, 15.dp)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 10.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(19.dp), tint = color)
        Text(label, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = color, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

private data class MessageMenuPresentation(
    val actions: MessageActions?,
    val loading: Boolean,
    val error: ChatError?,
    val canReply: Boolean,
    val canQuote: Boolean,
    val editPending: Boolean,
    val mutationPending: Boolean,
)

/** The edit header belongs to the same field as normal messages, like FieldHeader::editMessage. */
@Composable
internal fun MessageEditComposerBar(message: ChatMessage, pending: Boolean, onCancel: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp, top = 10.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(MiuixIcons.Regular.Edit, "编辑预览", Modifier.size(20.dp), tint = colors.primary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(if (message.kind.isTextContent) "编辑消息" else "编辑媒体说明", fontSize = 12.sp,
                lineHeight = 16.sp, color = colors.primary)
            Text(messagePreview(message), fontSize = 12.sp, lineHeight = 16.sp,
                color = colors.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        ComposerActionIcon(MiuixIcons.Regular.Close, "取消编辑", enabled = !pending, onClick = onCancel)
    }
}

@Composable
internal fun DeleteMessageDialog(
    message: ChatMessage,
    actions: MessageActions,
    pending: Boolean,
    error: ChatError?,
    onDelete: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var revoke by remember(message.id) { mutableStateOf<Boolean?>(null) }
    WindowDialog(show = true, title = "删除消息", maxWidth = 480.dp,
        onDismissRequest = { if (!pending) onDismiss() }) {
        Column(Modifier.heightIn(max = 500.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(messagePreview(message), fontSize = 13.sp, maxLines = 4, overflow = TextOverflow.Ellipsis,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Text("选择删除范围", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                if (actions.canDeleteForSelf) ChatButton("仅从我的聊天记录中删除", modifier = Modifier.fillMaxWidth(),
                    primary = revoke == false, enabled = !pending, onClick = { revoke = false })
                if (actions.canDeleteForAll) ChatButton("为所有人删除", modifier = Modifier.fillMaxWidth(),
                    primary = revoke == true, enabled = !pending, onClick = { revoke = true })
                Text(when (revoke) {
                    false -> "只删除你这里的记录，其他参与者仍能看到这条消息。"
                    true -> "这条消息将从所有参与者的聊天记录中删除。此操作无法撤销。"
                    null -> "Telegram 仅允许显示的删除范围。请选择范围后确认。"
                }, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                error?.let { ChatErrorText(it) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChatButton("取消", modifier = Modifier.weight(1f), enabled = !pending, onClick = onDismiss)
                ChatButton(if (pending) "删除中……" else "确认删除", modifier = Modifier.weight(1f), primary = true,
                    enabled = !pending && revoke?.let { if (it) actions.canDeleteForAll else actions.canDeleteForSelf } == true,
                    onClick = { revoke?.let(onDelete) })
            }
        }
    }
}

@Composable
internal fun ForwardMessageDialog(
    message: ChatMessage,
    snapshot: ChatSnapshot,
    pending: Boolean,
    error: ChatError?,
    onForward: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember(message.id) { mutableStateOf(TextFieldValue()) }
    var selectedChat by remember(message.id) { mutableStateOf<Long?>(null) }
    val eligible = remember(snapshot.knownChats, message.kind) {
        snapshot.knownChats.filter { it.acceptsForward(message.kind) }
    }
    val filtered = remember(eligible, query.text) {
        eligible.filter { it.title.contains(query.text.trim(), ignoreCase = true) }
    }
    val destination = eligible.firstOrNull { it.id == selectedChat }
    WindowDialog(show = true, title = "转发消息", maxWidth = 560.dp,
        onDismissRequest = { if (!pending) onDismiss() }) {
        Column(Modifier.heightIn(max = 500.dp).height(460.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(messagePreview(message), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            TextField(value = query, onValueChange = { query = it }, label = "搜索已加载的聊天",
                modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !pending)
            if (filtered.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("没有符合条件的聊天。可返回聊天列表加载更多聊天后再试。", fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            } else LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(filtered, key = { it.id }) { chat ->
                    val chosen = selectedChat == chat.id
                    Text(chat.title.ifBlank { "未命名聊天" }, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                            .chatSquircleSurface(if (chosen) MiuixTheme.colorScheme.secondaryContainer else MiuixTheme.colorScheme.surfaceContainer, 15.dp)
                            .semantics { selected = chosen }.clickable(enabled = !pending) { selectedChat = chat.id }.padding(12.dp))
                }
            }
            destination?.let { Text("转发到：${it.title}", fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            error?.let { ChatErrorText(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChatButton("取消", modifier = Modifier.weight(1f), enabled = !pending, onClick = onDismiss)
                ChatButton(if (pending) "转发中……" else "确认转发", modifier = Modifier.weight(1f), primary = true,
                    enabled = !pending && destination != null, onClick = { destination?.let { onForward(it.id) } })
            }
        }
    }
}

private fun ChatItem.acceptsForward(kind: MessageKind): Boolean = when (kind) {
    MessageKind.PHOTO -> canSendPhotos
    MessageKind.VIDEO -> canSendVideos
    MessageKind.FILE -> canSendDocuments
    MessageKind.STICKER -> canSendStickers
    else -> canSend
}

@Composable
internal fun ReplyComposerBar(message: ChatMessage, snapshot: ChatSnapshot, onCancel: () -> Unit,
    features: ChatFeatureActions? = null) {
    val colors = MiuixTheme.colorScheme
    val media = message.media?.takeUnless { it.hasSpoiler }
    val thumbnail = media?.thumbnail ?: media?.file?.takeIf {
        message.kind == MessageKind.PHOTO || message.kind == MessageKind.STICKER
    }
    val thumbnailFormat = if (media?.thumbnail != null) media.thumbnailFormat ?: MediaFormat.PHOTO else media?.format
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp, top = 10.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(MiuixIcons.Regular.Reply, "回复预览", Modifier.size(20.dp), tint = colors.onSurfaceVariantSummary)
        if (thumbnail != null && thumbnailFormat != null && features != null) {
            RemoteMedia(MessageMedia(thumbnail, format = thumbnailFormat), snapshot, features,
                visible = true, animate = false, compact = true, modifier = Modifier.size(32.dp), cornerRadius = 6.dp,
                retainWhenInactive = true, placeholder = {
                    Box(Modifier.fillMaxSize().chatSquircleBackground(colors.onSurface.copy(alpha = 0.05f), 6.dp),
                        contentAlignment = Alignment.Center) {
                        Icon(MiuixIcons.Regular.Image, null, Modifier.size(16.dp), tint = colors.onSurfaceVariantSummary)
                    }
                })
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("${if (snapshot.replyQuote != null) "引用" else "回复"} ${snapshot.senderLabel(message)}", fontSize = 12.sp,
                lineHeight = 16.sp, fontWeight = FontWeight.Normal,
                color = colors.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val summary = snapshot.replyQuote?.content?.text?.takeIf { it.isNotBlank() }
                ?: when (message.kind) {
                    MessageKind.PHOTO -> message.text.ifBlank { "图片" }
                    MessageKind.STICKER -> listOfNotNull(message.media?.emoji?.takeIf { it.isNotBlank() }, "贴纸").joinToString(" ")
                    else -> messagePreview(message)
                }
            Text(summary, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal,
                color = colors.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        ComposerActionIcon(MiuixIcons.Regular.Close, "取消回复", onClick = onCancel)
    }
}

@Composable
internal fun ReplyReference(reply: MessageReply, snapshot: ChatSnapshot, onJump: ((MessageReply) -> Unit)? = null,
    active: Boolean = true) {
    val original = snapshot.messages.firstOrNull { it.chatId == reply.chatId && it.id == reply.messageId }
    val sender = original?.let { snapshot.senderLabel(it) } ?: when {
        reply.senderIsChat -> snapshot.knownChats.firstOrNull { it.id == reply.senderId }?.title
        reply.senderId != 0L -> snapshot.senderNames[reply.senderId]
        else -> null
    }
    val palette = snapshot.replyNamePalette(reply, original, MiuixTheme.colorScheme.surface.luminance() < .5f)
    val name = palette?.nameRgb?.peerAccentColor() ?: MiuixTheme.colorScheme.onSurfaceVariantSummary
    Box(Modifier.fillMaxWidth().height(IntrinsicSize.Min)
        .chatSquircleSurface(name.copy(alpha = .12f), 8.dp)
        .clickable(enabled = onJump != null, onClickLabel = "跳转到原消息", onClick = { onJump?.invoke(reply) })) {
        if (palette != null && palette.backgroundCustomEmojiId != 0L) CompositionLocalProvider(
            LocalProfileEmojiArtworkIdentity provides Triple(snapshot.token, reply, original?.forwardInfo?.origin to palette)) {
        PeerReplyPatternArtwork(
            palette.backgroundCustomEmojiId, tint = name,
            quote = reply.isQuote, active = active && snapshot.enabled, modifier = Modifier.matchParentSize(),
            giftModelCustomEmojiId = palette.giftModelCustomEmojiId)
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PeerReplyOutline(palette, name, Modifier.width(3.dp).fillMaxHeight().chatSquircleClip(1.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(sender ?: "回复的消息", fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium,
                color = name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val text = when {
                reply.isQuote -> RichMessageText(reply.text, reply.entities)
                original != null && original.text.isNotBlank() -> original.richText()
                reply.text.isNotBlank() -> RichMessageText(reply.text, reply.entities)
                else -> RichMessageText(original?.let(::messagePreview) ?: "引用消息的内容暂不可用")
            }
            RichMessageBody(text, maxLines = if (reply.isQuote) 3 else 1, selectable = false)
            }
        }
    }
}

internal fun ChatSnapshot.senderLabel(message: ChatMessage): String = when {
    message.outgoing -> "你"
    message.senderIsChat -> knownChats.firstOrNull { it.id == message.senderId }?.title ?: "聊天"
    else -> senderNames[message.senderId] ?: "用户"
}
