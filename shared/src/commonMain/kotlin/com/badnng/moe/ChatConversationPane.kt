package com.badnng.moe

import androidx.compose.animation.core.tween
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.badnng.moe.chat.*
import com.badnng.moe.media.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.utils.overScrollVertical

/** Explicit offline regression probe; production leaves message geometry inside the conversation. */
internal val LocalConversationPreviewMessageBounds = staticCompositionLocalOf<((Long, Rect?) -> Unit)?> { null }
internal val LocalConversationPreviewLatestState = staticCompositionLocalOf<((String) -> Unit)?> { null }

@Composable
internal fun ConversationPane(
    snapshot: ChatSnapshot,
    chat: ChatItem,
    draft: TextFieldValue,
    onDraft: (TextFieldValue) -> Unit,
    onBack: (() -> Unit)?,
    onLoadOlder: () -> Unit,
    onSend: () -> Unit,
    onRetry: (Long) -> Unit,
    onMarkRead: () -> Unit,
    features: ChatFeatureActions,
    active: Boolean,
    onSearch: () -> Unit,
    onSearchSender: (Long) -> Unit,
    onAdmins: () -> Unit,
    onInfo: () -> Unit,
    showInfoButton: Boolean,
    modifier: Modifier = Modifier,
    onReturnToLatest: (() -> Unit)? = null,
    adminsPopup: @Composable () -> Unit = {},
    pageInteractive: Boolean = true,
    draftRevision: Long = 0,
    readDraft: () -> TextFieldValue = { draft },
    readDraftRevision: () -> Long = { draftRevision },
    sessionMemory: ConversationSessionMemory = remember(snapshot.token.generation) { ConversationSessionMemory() },
    onOpenReply: ((ChatMessage, ConversationReadPosition?) -> Unit)? = null,
    onReplyReturn: (() -> Unit)? = null,
) {
    // Navigation ownership is independent of activity: a popup pauses the timeline
    // while remaining interactive, whereas a departing page must close every popup.
    val currentPageInteractive by rememberUpdatedState(pageInteractive)
    val compactLayout = LocalDesktopChatLayout.current.compact
    val horizontalPadding = 24.dp
    val headerTopPadding = if (compactLayout) 16.dp else 20.dp
    val density = LocalDensity.current
    val messageScroll = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val composerFocusRequester = remember { FocusRequester() }
    val inheritedModalFocusReturn = LocalComposerModalFocusReturn.current
    val modalFocusReturn = inheritedModalFocusReturn ?: remember { ComposerModalFocusReturn() }
    if (inheritedModalFocusReturn == null) ComposerModalFocusReturnEffect(modalFocusReturn)
    val composerWindow = LocalWindowInfo.current
    val previewMessageBounds = LocalConversationPreviewMessageBounds.current
    val previewLatestState = LocalConversationPreviewLatestState.current
    val windowMotion = LocalHistoryWindowMotion.current
    val outgoingWindow = windowMotion?.outgoing == true
    val token = snapshot.token
    val entryWasReplyReturn = remember(token) { !outgoingWindow && sessionMemory.requestedPosition(chat.id) != null }
    val entryReplyReturnJumpRevision = remember(token) { sessionMemory.requestedJumpRevision(chat.id) }
    var pendingEntryPosition by remember(token) {
        mutableStateOf(if (!outgoingWindow) sessionMemory.takeEntryPosition(chat.id) else null)
    }
    val latestFollow = remember(token) {
        ConversationLatestFollowState(snapshot).apply { if (pendingEntryPosition != null) navigate(atLatest = false) }
    }
    var entryPositionRequested by remember(token) { mutableStateOf(entryWasReplyReturn) }
    var entryPositionJumpRevision by remember(token) {
        mutableLongStateOf(entryReplyReturnJumpRevision ?: snapshot.jumpRevision)
    }
    LaunchedEffect(sessionMemory.returnRevision, token) {
        sessionMemory.requestedPosition(chat.id)?.let {
            if (pendingEntryPosition != it) entryPositionJumpRevision =
                sessionMemory.requestedJumpRevision(chat.id) ?: snapshot.jumpRevision
            pendingEntryPosition = it
            entryPositionRequested = true // The return command already requested the native history.
        }
    }
    var showStickers by remember(snapshot.token) { mutableStateOf(false) }
    var stickerTriggerHovered by remember(snapshot.token) { mutableStateOf(false) }
    var stickerPanelHovered by remember(snapshot.token) { mutableStateOf(false) }
    var stickerAutoHide by remember(snapshot.token) { mutableStateOf(false) }
    var stickerPickerMounted by remember(snapshot.token) { mutableStateOf(false) }
    var stickerAnchor by remember(snapshot.token) { mutableStateOf<Rect?>(null) }
    var photoToken by remember { mutableStateOf<ChatToken?>(null) }
    var photoSubmitted by remember { mutableStateOf(false) }
    var photoDraftTransfer by remember { mutableStateOf<ComposerDraftTransfer?>(null) }
    var photoFocusIntent by remember(token) { mutableStateOf<ComposerModalFocusReturn.Intent?>(null) }
    var photoError by remember { mutableStateOf<ChatError?>(null) }
    var voiceToken by remember { mutableStateOf<ChatToken?>(null) }
    val voiceControl = remember { VoiceRecordingControl() }
    var voiceReceiptAtOpen by remember { mutableStateOf(0L) }
    var voiceSubmitted by remember { mutableStateOf(false) }
    var attachmentKind by remember { mutableStateOf(AttachmentKind.FILE) }
    var attachmentToken by remember { mutableStateOf<ChatToken?>(null) }
    var attachmentSubmitted by remember { mutableStateOf(false) }
    var attachmentDraftTransfer by remember { mutableStateOf<ComposerDraftTransfer?>(null) }
    var attachmentFocusIntent by remember(token) { mutableStateOf<ComposerModalFocusReturn.Intent?>(null) }
    var attachmentError by remember { mutableStateOf<ChatError?>(null) }
    var enlarged by remember { mutableStateOf<ChatMessage?>(null) }
    var menuMessageId by remember { mutableStateOf<Long?>(null) }
    var reactionTarget by remember { mutableStateOf<Long?>(null) }
    var saveTarget by remember { mutableStateOf<ChatMediaActionTarget?>(null) }
    var playMessageId by remember { mutableStateOf<Long?>(null) }
    var videoInitiallyMuted by remember { mutableStateOf(false) }
    var userVideoMessageId by remember { mutableStateOf<Long?>(null) }
    var videoExpanded by remember { mutableStateOf(false) }
    val videoBounds = remember { mutableStateMapOf<Long, Rect>() }
    val messageBounds = remember { mutableStateMapOf<Long, Rect>() }
    var videoViewport by remember { mutableStateOf(Rect.Zero) }
    var headerBounds by remember { mutableStateOf(Rect.Zero) }
    // The whole painted bottom overlay, including the progressive blur feather, occludes
    // content. The editor's own hit/semantics bounds stay naturally measured below.
    var composerBounds by remember { mutableStateOf(Rect.Zero) }
    var stoppedVideoIds by remember { mutableStateOf(emptySet<Long>()) }
    var mutationTarget by remember { mutableStateOf<ChatMutationTarget?>(null) }
    var mutationSubmitted by remember { mutableStateOf(false) }
    var mutationRevisionAtSubmit by remember { mutableStateOf(0L) }
    var composerActionError by remember { mutableStateOf<ChatError?>(null) }
    var highlightedMessageId by remember(token) { mutableStateOf<Long?>(null) }
    var navigationHint by remember { mutableStateOf<String?>(null) }
    var conversationMenuOpen by remember(snapshot.token) { mutableStateOf(false) }
    var conversationMenuMounted by remember(snapshot.token) { mutableStateOf(false) }
    var composerMenuOpen by remember(snapshot.token) { mutableStateOf(false) }
    var pinnedListOpen by remember(snapshot.token) { mutableStateOf(false) }
    var pinnedHidden by remember(snapshot.token) { mutableStateOf(false) }
    var appliedJumpRevision by remember(snapshot.token) { mutableStateOf(-1L) }
    val positioning = snapshot.jumpMessageId != null && appliedJumpRevision != snapshot.jumpRevision
    val mediaActive = active && pageInteractive && !showStickers && !stickerPickerMounted && photoToken == null && voiceToken == null && attachmentToken == null &&
        enlarged == null && menuMessageId == null && saveTarget == null &&
        reactionTarget == null && mutationTarget == null && !conversationMenuOpen && !conversationMenuMounted &&
        !composerMenuOpen && !pinnedListOpen && !positioning && pendingEntryPosition == null
    val reading = mediaActive && !videoExpanded
    val currentEditShortcutActive by rememberUpdatedState(mediaActive && !videoExpanded)
    val currentSnapshot by rememberUpdatedState(snapshot)
    val currentReadDraft by rememberUpdatedState(readDraft)
    val currentReadDraftRevision by rememberUpdatedState(readDraftRevision)
    val currentOnDraft by rememberUpdatedState(onDraft)
    val canReturnToComposer by rememberUpdatedState({
        currentPageInteractive && !outgoingWindow && currentSnapshot.enabled && currentSnapshot.token == token &&
            currentSnapshot.selected?.canSend == true && currentSnapshot.editTarget == null &&
            composerWindow.isWindowFocused && photoToken == null && attachmentToken == null && voiceToken == null &&
            !showStickers && enlarged == null && !videoExpanded
    })
    DisposableEffect(modalFocusReturn, token, pageInteractive, outgoingWindow, composerFocusRequester) {
        val owner = if (pageInteractive && !outgoingWindow) modalFocusReturn.register(token, { canReturnToComposer() }) {
            composerFocusRequester.requestFocus()
        } else null
        onDispose { owner?.let(modalFocusReturn::unregister) }
    }
    val editing = snapshot.editTarget
    // Keep the normal per-chat draft owned by the workspace. Entering an edit replaces only
    // the field's active value; cancel/success exposes that untouched draft and its selection.
    var editValue by remember(token, editing?.id, snapshot.editDraftRevision) {
        mutableStateOf(editing?.let { sessionMemory.editValue(it, snapshot.editDraftRevision) } ?: TextFieldValue())
    }
    var previousEdit by remember(token) { mutableStateOf(editing?.let { it.id to snapshot.editDraftRevision }) }
    LaunchedEffect(token, editing?.id, snapshot.editDraftRevision) {
        val current = editing?.let { it.id to snapshot.editDraftRevision }
        previousEdit?.takeIf { it != current }?.let { sessionMemory.clearEdit(chat.id, it.first, it.second) }
        previousEdit = current
    }
    var confirmDiscardEdit by remember(token, editing?.id) { mutableStateOf(false) }
    fun cancelEdit() {
        if (!currentPageInteractive || editing == null || snapshot.editPending) return
        if (editValue.richText() != editing.richText()) confirmDiscardEdit = true
        else features.cancelEdit(token)
    }
    LaunchedEffect(token, editing?.id, outgoingWindow, pageInteractive) {
        if (editing != null && !outgoingWindow && pageInteractive) {
            showStickers = false
            photoToken = null
            photoSubmitted = false
            attachmentToken = null
            attachmentSubmitted = false
            // The same editor is retained. Let its new text layout replace the previous
            // draft's layout before the desktop IME asks for the new caret rectangle.
            withFrameNanos { }
            if (currentPageInteractive) composerFocusRequester.requestFocus()
        }
    }
    LaunchedEffect(token) {
        if (voiceToken != null && voiceToken != token) {
            voiceToken = null
            voiceSubmitted = false
            voiceControl.cancel()
        }
    }
    LaunchedEffect(pageInteractive) {
        if (!pageInteractive) {
            showStickers = false
            stickerAutoHide = false
            stickerTriggerHovered = false
            stickerPanelHovered = false
            conversationMenuOpen = false
            pinnedListOpen = false
            menuMessageId = null
            reactionTarget = null
            mutationTarget = null
            mutationSubmitted = false
            confirmDiscardEdit = false
            photoToken = null
            photoSubmitted = false
            attachmentToken = null
            attachmentSubmitted = false
            saveTarget = null
            enlarged = null
            videoExpanded = false
            voiceControl.cancel()
            voiceToken = null
            voiceSubmitted = false
            features.closeMessageActions(token)
            features.closeReadDetails(token)
            features.closeReactions(token)
        }
    }
    fun startVoice(held: Boolean) {
        if (!currentPageInteractive || voiceToken != null || editing != null || !chat.canSendVoiceNotes || snapshot.sendPending) return
        showStickers = false
        features.visibleMessages(token, emptyList(), false)
        if (held) voiceControl.beginHeldRecording() else voiceControl.beginLockedRecording()
        voiceReceiptAtOpen = snapshot.acceptedSendId
        voiceSubmitted = false
        voiceToken = token
    }
    // Telegram Desktop TabbedPanel uses a 300 ms leave timer; allow the pointer
    // to cross the small gap from the composer button into the independent Popup.
    LaunchedEffect(showStickers, stickerAutoHide, stickerTriggerHovered, stickerPanelHovered) {
        if (showStickers && stickerAutoHide && !stickerTriggerHovered && !stickerPanelHovered) {
            delay(300)
            showStickers = false
        }
    }
    fun openStickers() {
        if (!currentPageInteractive || voiceToken != null) return
        if (!showStickers) {
            features.visibleMessages(token, emptyList(), false)
            features.loadStickers(token, null)
        }
        showStickers = true
    }
    val visibleIds by remember {
        derivedStateOf {
            val layout = messageScroll.layoutInfo
            val byPresentation = currentSnapshot.messages.associateBy { it.presentationId }
            if (composerBounds.height <= 0f) emptySet() else layout.visibleItemsInfo
                .filter { it.offset + it.size > layout.viewportStartOffset && it.offset < layout.viewportEndOffset }
                .mapNotNull { (it.key as? Long)?.let { key -> byPresentation[key]?.id } }.filter { id ->
                    messageBounds[id]?.let { visibleConversationArea(it, videoViewport, headerBounds, composerBounds) > 0f } == true
                }.toSet()
        }
    }
    val currentVisibleAction by rememberUpdatedState(features.visibleMessages)
    LaunchedEffect(token, pageInteractive, outgoingWindow) {
        val position = pendingEntryPosition
        if (position != null && pageInteractive && !outgoingWindow && !entryPositionRequested) {
            entryPositionRequested = true
            entryPositionJumpRevision = snapshot.jumpRevision
            features.loadHistoryAnchor(token, position.messageId)
        }
    }
    val entryTargetPresent = pendingEntryPosition?.let { position ->
        snapshot.messages.any { it.id == position.messageId && it.chatId == position.chatId }
    } == true
    LaunchedEffect(token, pendingEntryPosition, snapshot.jumpRevision, snapshot.jumpMessageId,
        snapshot.historyLoading, entryTargetPresent) {
        val position = pendingEntryPosition ?: return@LaunchedEffect
        // A newer native jump has acknowledged this request. If it is deleted or
        // superseded before measurement, its offset must no longer suspend the page.
        if (entryPositionRequested && !snapshot.historyLoading && snapshot.jumpRevision > entryPositionJumpRevision &&
            (snapshot.jumpMessageId != position.messageId || !entryTargetPresent)) {
            pendingEntryPosition = null
            sessionMemory.completePosition(position.chatId)
        }
    }
    LaunchedEffect(snapshot.historyError, snapshot.historyLoading, snapshot.navigationFailureRevision) {
        if (snapshot.historyError != null) {
            pendingEntryPosition = null
            sessionMemory.completePosition(chat.id)
        }
    }
    LaunchedEffect(token, pageInteractive, outgoingWindow) {
        if (pageInteractive && !outgoingWindow) {
            var registeredMessageId: Long? = null
            snapshotFlow {
                val state = currentSnapshot
                val item = state.messages.asReversed().getOrNull(messageScroll.firstVisibleItemIndex)
                if (pendingEntryPosition != null || state.historyLoading || item == null || item.id <= 0 ||
                    messageScroll.layoutInfo.visibleItemsInfo.none { it.key == item.presentationId }) null
                else ConversationReadPosition(chat.id, item.id, messageScroll.firstVisibleItemScrollOffset,
                    atLatest = messageScroll.firstVisibleItemIndex == 0 && messageScroll.firstVisibleItemScrollOffset == 0 &&
                        !state.historyHasNewer && (state.jumpMessageId == null || state.jumpRevision == appliedJumpRevision))
            }.filterNotNull().distinctUntilChanged().collectLatest { position ->
                sessionMemory.rememberPosition(position)
                if (registeredMessageId != position.messageId) {
                    features.rememberHistoryAnchor(token, position.messageId)
                    registeredMessageId = position.messageId
                }
            }
        }
    }
    LaunchedEffect(token, snapshot.jumpRevision, snapshot.jumpMessageId, snapshot.historyLoading, snapshot.pinned.loading) {
        val target = snapshot.jumpMessageId ?: return@LaunchedEffect
        if (appliedJumpRevision == snapshot.jumpRevision) return@LaunchedEffect
        if (snapshot.unreadBoundary?.firstUnreadMessageId == target && snapshot.pinned.loading) return@LaunchedEffect
        val index = snapshot.messages.asReversed().indexOfFirst { it.id == target }
        if (!snapshot.historyLoading && index >= 0) {
            val restoredPosition = pendingEntryPosition?.takeIf { it.messageId == target && it.chatId == chat.id }
            var placementLayout = messageScroll.layoutInfo
            var placementIndex = index
            var placementOffset = restoredPosition?.offset ?: 0
            messageScroll.requestScrollToItem(placementIndex, placementOffset)
            if (restoredPosition == null && snapshot.unreadBoundary?.firstUnreadMessageId == target) {
                val itemHeight = withTimeoutOrNull(1000) {
                    snapshotFlow {
                        val expectedTop = with(density) {
                            (headerTopPadding + 16.dp + ConversationMessageVerticalOffset).toPx()
                        } + headerBounds.height
                        if (headerBounds.height > 0f && videoViewport.height > 0f &&
                            messageScroll.layoutInfo.afterContentPadding >= expectedTop - 1f)
                            messageScroll.layoutInfo.visibleItemsInfo.firstOrNull {
                                it.key == currentSnapshot.messages.firstOrNull { message -> message.id == target }?.presentationId
                            }?.size else null
                    }.filterNotNull().first()
                }
                val currentIndex = currentSnapshot.messages.asReversed().indexOfFirst { it.id == target }
                if (itemHeight != null && currentIndex >= 0) {
                    val desiredTop = (headerBounds.bottom - videoViewport.top).coerceAtLeast(0f) + with(density) {
                        (16.dp + ConversationMessageVerticalOffset).toPx()
                    }
                    val bottom = videoViewport.height - messageScroll.layoutInfo.beforeContentPadding
                    // Geometry observations can resume during measure. Schedule the next pass
                    // rather than forcing a nested measure from this snapshot observer.
                    placementLayout = messageScroll.layoutInfo
                    placementIndex = currentIndex
                    placementOffset = (desiredTop + itemHeight - bottom).toInt()
                    messageScroll.requestScrollToItem(placementIndex, placementOffset)
                }
            }
            // Incoming pages draw during their transition before accepting input. The
            // native navigation intent still owns this visual; only retained outgoing pages skip it.
            highlightedMessageId = target.takeIf { snapshot.jumpHighlight && !outgoingWindow }
            // requestScrollToItem queues a remeasure, unless the requested position
            // already matches. Do not wait for a new layout object for that no-op.
            // First layout/font preparation may exceed a wall-clock timeout. This
            // owner-scoped wait is cancelled by a changed/deleted jump or disposed page.
            val placed = snapshotFlow {
                val layout = messageScroll.layoutInfo
                val bounds = messageBounds[target]
                bounds != null && bounds.height > 0f &&
                    (layout !== placementLayout || messageScroll.firstVisibleItemIndex == placementIndex &&
                        messageScroll.firstVisibleItemScrollOffset == placementOffset)
            }.first { it }
            val latestState = currentSnapshot
            if (latestState.token != token || latestState.jumpRevision != snapshot.jumpRevision ||
                latestState.jumpMessageId != target) return@LaunchedEffect
            appliedJumpRevision = snapshot.jumpRevision
            if (!outgoingWindow) latestFollow.completeJump(latestState,
                atLatest = messageScroll.firstVisibleItemIndex == 0 && messageScroll.firstVisibleItemScrollOffset <= 1)
            previewLatestState?.invoke("placed=$placed, applied=$appliedJumpRevision, " +
                "intent=${latestFollow.followingLatest}, index=${messageScroll.firstVisibleItemIndex}, " +
                "offset=${messageScroll.firstVisibleItemScrollOffset}, outgoing=$outgoingWindow")
            if (restoredPosition != null) {
                pendingEntryPosition = null
                sessionMemory.completePosition(chat.id)
            }
        }
    }
    LaunchedEffect(token, highlightedMessageId) {
        if (highlightedMessageId != null) { delay(MessageJumpHighlightDurationMillis.toLong()); highlightedMessageId = null }
    }
    LaunchedEffect(snapshot.replyTarget?.id, snapshot.replyQuote, pageInteractive) {
        if (pageInteractive && chat.canSend && snapshot.replyQuote != null && snapshot.editTarget == null && !snapshot.sendPending) {
            composerFocusRequester.requestFocus()
        }
    }
    LaunchedEffect(visibleIds) { stoppedVideoIds = stoppedVideoIds.intersect(visibleIds) }
    LaunchedEffect(token, mediaActive, videoExpanded) {
        if (mediaActive && !videoExpanded) snapshotFlow {
            val state = currentSnapshot
            val candidates = state.messages.mapNotNull { message ->
                val media = message.media ?: return@mapNotNull null
                val bounds = videoBounds[message.id] ?: return@mapNotNull null
                val file = state.mediaFiles[media.file.id] ?: media.file
                val playable = file.downloaded && !file.localPath.isNullOrEmpty() ||
                    (file.canDownload || file.downloading || file.id in state.downloadingFiles)
                if (media.format != MediaFormat.VIDEO || message.delivery != MessageDelivery.SENT ||
                    !(userVideoMessageId == message.id && playMessageId == message.id || state.autoPlayAllowed(message)) ||
                    message.id != playMessageId && (media.hasSpoiler || !playable || file.id in state.fileErrors)) null
                else VideoAutoplayCandidate(message.id, bounds)
            }
            if (composerBounds.height <= 0f) null
            else selectAutoplayVideo(candidates, videoViewport, playMessageId, stoppedVideoIds, headerBounds, composerBounds)
        }.distinctUntilChanged().collectLatest { next ->
            if (next != playMessageId) {
                // Let a newly visible video settle briefly; release an offscreen one immediately.
                if (next != null) delay(120)
                videoInitiallyMuted = true
                if (next != userVideoMessageId) userVideoMessageId = null
                playMessageId = next
            }
        }
    }
    val playingMessage = snapshot.messages.firstOrNull { it.id == playMessageId && it.media?.format == MediaFormat.VIDEO }
    val playbackSession = playingMessage?.let { message ->
        val media = checkNotNull(message.media)
        val file = snapshot.mediaFiles[media.file.id] ?: media.file
        key(message.id, file.id) {
            rememberVideoPlaybackSession(file.localPath?.takeIf { file.downloaded }.orEmpty(),
                active = mediaActive && (videoExpanded || message.id in visibleIds), streamSize = file.size,
                initiallyMuted = videoInitiallyMuted,
                readerFactory = { ownedMediaRangeReader(features, token, message.id, file.id) })
        }
    }
    LaunchedEffect(snapshot.acceptedSendId) {
        if (voiceSubmitted && snapshot.acceptedSendId > voiceReceiptAtOpen &&
            snapshot.acceptedSendChatId == voiceToken?.chatId && snapshot.acceptedSendText == null) {
            voiceToken = null
            voiceSubmitted = false
        }
    }
    LaunchedEffect(snapshot.messages, menuMessageId, reactionTarget, mutationTarget) {
        if (playMessageId != null && snapshot.messages.none { it.id == playMessageId && it.media?.format == MediaFormat.VIDEO }) {
            playMessageId = null
            videoExpanded = false
        }
        if (menuMessageId != null && snapshot.messages.none { it.id == menuMessageId }) {
            menuMessageId = null
            features.closeMessageActions(token)
        }
        if (enlarged != null && snapshot.messages.none { it.id == enlarged?.id && it.kind == MessageKind.PHOTO }) enlarged = null
        if (mutationTarget != null && snapshot.messages.none { it.id == mutationTarget?.messageId }) {
            mutationTarget = null
            mutationSubmitted = false
            features.closeMessageActions(token)
        }
        if (reactionTarget != null && snapshot.messages.none { it.id == reactionTarget }) {
            reactionTarget = null
            features.closeReactions(token)
        }
    }
    LaunchedEffect(snapshot.mutationRevision) {
        val target = mutationTarget
        if (mutationSubmitted && target != null && snapshot.mutationRevision > mutationRevisionAtSubmit &&
            snapshot.mutationMessageId == target.messageId && snapshot.mutationKind == target.kind) {
            mutationTarget = null
            mutationSubmitted = false
            features.closeMessageActions(token)
        }
    }
    DisposableEffect(token) {
        onDispose {
            features.closeMessageActions(token)
            features.closeReadDetails(token)
            features.closeReactions(token)
        }
    }
    DisposableEffect(token, reading) {
        if (!reading) currentVisibleAction(token, emptyList(), false)
        onDispose { currentVisibleAction(token, emptyList(), false) }
    }
    LaunchedEffect(token, reading) {
        if (reading) snapshotFlow {
            currentSnapshot.messages.filter { it.id in visibleIds && !it.outgoing && it.delivery == MessageDelivery.SENT }.map { it.id }
        }.distinctUntilChanged().collectLatest { ids ->
            if (ids.isNotEmpty()) delay(300)
            currentVisibleAction(token, ids, true)
        }
    }
    val latestProximity = with(density) { 72.dp.roundToPx() }
    val nearLatest by remember(latestProximity) { derivedStateOf {
        // A single tall photo/text item can be many viewports high. Its index alone does
        // not mean the reader is at the bottom, and must not trigger following new messages.
        messageScroll.firstVisibleItemIndex == 0 && messageScroll.firstVisibleItemScrollOffset <= latestProximity
    } }
    val canFollowLatest by rememberUpdatedState(active && pageInteractive && !outgoingWindow &&
        enlarged == null && !videoExpanded)
    val cancelSendLatestNavigation by rememberUpdatedState(features.cancelSendLatestNavigation)
    LaunchedEffect(token, active, pageInteractive, outgoingWindow) {
        if (!active || !pageInteractive || outgoingWindow) cancelSendLatestNavigation(token)
    }
    val latestFollowScrollConnection = remember(token) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && canFollowLatest &&
                    (consumed.y != 0f || available.y != 0f)) {
                    // Desktop wheel input can begin and finish in one dispatch, so
                    // isScrollInProgress may never be observable as true. Post-scroll
                    // sees the actual consumed position and owns the user's intent.
                    latestFollow.navigate(atLatest = messageScroll.firstVisibleItemIndex == 0 &&
                        messageScroll.firstVisibleItemScrollOffset <= 1)
                    cancelSendLatestNavigation(token)
                }
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(token) {
        fun observePosition() = ConversationLatestFollowObservation(
            snapshot = currentSnapshot,
            index = messageScroll.firstVisibleItemIndex,
            offset = messageScroll.firstVisibleItemScrollOffset,
            scrolling = messageScroll.isScrollInProgress,
            interactive = canFollowLatest,
            positioning = currentSnapshot.jumpMessageId != null && currentSnapshot.jumpRevision != appliedJumpRevision,
            // A discrete wheel can finish after its final geometry was observed.
            // Intent changes must also wake the bottom completion/follow observer.
            following = latestFollow.followingLatest,
        )
        snapshotFlow { observePosition() }.collectLatest {
            // A placement/user-input job can complete after emission but before this
            // collector resumes. Never let an old pending phase cancel its new intent.
            val observed = observePosition()
            val follow = latestFollow.observe(observed.snapshot,
                // The floating button has a 72 dp reveal threshold. Following uses
                // the actual bottom, as the official wasAtBottom check does, so a
                // small deliberate touchpad scroll is not immediately pulled back.
                atLatest = observed.index == 0 && observed.offset <= 1,
                scrolling = observed.scrolling, interactive = observed.interactive, positioning = observed.positioning)
            previewLatestState?.invoke("observe: pending=${observed.positioning}, intent=${observed.following}, " +
                "interactive=${observed.interactive}, index=${observed.index}, offset=${observed.offset}, " +
                "follow=$follow, now=${latestFollow.followingLatest}")
            if (follow && !observed.positioning && observed.index == 0 && observed.offset <= 1 &&
                observed.snapshot.jumpMessageId != null) {
                // Completion is idempotent in the actor. A rejected loading race may
                // retry on the next ready snapshot instead of latching the old marker.
                features.completeHistoryJump(token, observed.snapshot.jumpRevision)
            }
            if (follow && (observed.index != 0 || observed.offset != 0) &&
                currentSnapshot.token == token && canFollowLatest) {
                // Schedule the next pass rather than nesting a measure from a layout
                // observation. This also retains the bottom through media/composer
                // reflow and successive sends without stealing an older reading anchor.
                messageScroll.requestScrollToItem(0)
            }
        }
    }
    val sendAnimations = remember(token) { SendAnimationTracker() }
    val incomingAnimations = remember(token) { IncomingAnimationTracker() }
    // Preserve the pre-insertion intent. The 72 dp button threshold is deliberately
    // unrelated, and keyed remeasurement may have moved the old bottom row already.
    val followingForEntrance = latestFollow.followingLatest
    val incomingEntrancePlans = remember(snapshot.messages, snapshot.historyLoading, snapshot.newerLoading,
        snapshot.historyHasNewer, snapshot.jumpMessageId, snapshot.jumpRevision,
        followingForEntrance, reading, outgoingWindow) {
        incomingAnimations.update(snapshot, atLatest = followingForEntrance,
            active = reading && !outgoingWindow)
    }
    val sendAnimationFrame = remember(snapshot.messages, snapshot.historyLoading, snapshot.newerLoading,
        snapshot.historyHasNewer, snapshot.jumpMessageId, snapshot.jumpRevision, followingForEntrance, reading, outgoingWindow) {
        sendAnimations.update(snapshot, atLatest = followingForEntrance, active = reading && !outgoingWindow)
    }
    var completedSendPlacement by remember(token) { mutableStateOf(0L) }
    val animateSendPlacement = sendAnimationFrame.placementRevision > completedSendPlacement
    LaunchedEffect(sendAnimationFrame.placementRevision) {
        if (sendAnimationFrame.placementRevision > 0) {
            delay(220)
            completedSendPlacement = sendAnimationFrame.placementRevision
        }
    }
    val currentLoadOlder by rememberUpdatedState(onLoadOlder)
    val currentLoadNewer by rememberUpdatedState(features.loadNewer)
    LaunchedEffect(token, reading) {
        if (reading) snapshotFlow {
            val state = currentSnapshot
            val initialPositionReady = state.jumpMessageId == null || state.jumpRevision == appliedJumpRevision
            if (state.token != token || !state.enabled || !initialPositionReady || state.historyLoading || state.newerLoading || videoViewport.height <= 0f) null
            else {
                val older = !state.historyEnd && state.historyError == null
                val newer = state.historyHasNewer && state.newerError == null
                val layout = messageScroll.layoutInfo
                val direction = if (state.messages.isEmpty()) {
                    HistoryPreloadDirection.OLDER.takeIf { older }
                } else historyPreloadDirection(
                    messageCount = state.messages.size,
                    viewportStart = layout.viewportStartOffset,
                    viewportEnd = layout.viewportEndOffset,
                    items = layout.visibleItemsInfo.map { HistoryPreloadItem(it.index, it.offset, it.size) },
                    hasOlder = older,
                    hasNewer = newer,
                )
                direction?.let { HistoryPreloadRequest(it, state.messages.firstOrNull()?.id, state.messages.lastOrNull()?.id) }
            }
        }.distinctUntilChanged().collectLatest { request ->
            if (request != null) {
                // Let initial geometry and header positioning settle, and cancel if a menu opens
                // or a native page starts in the meantime. One direction owns each native window.
                delay(100)
                if (currentSnapshot.token != token) return@collectLatest
                if (request.direction == HistoryPreloadDirection.OLDER) currentLoadOlder()
                else currentLoadNewer(token)
            }
        }
    }
    val dayStartIds = remember(snapshot.messages) {
        snapshot.messages.filterIndexed { index, message ->
            message.date > 0 && (index == 0 || messageDayLabel(snapshot.messages[index - 1].date) != messageDayLabel(message.date))
        }.map { it.id }.toSet()
    }
    val savedMessages = chat.privateUserId != null && chat.privateUserId == snapshot.account?.userId
    val continuationIds = remember(snapshot.messages, dayStartIds, snapshot.unreadBoundary?.firstUnreadMessageId, savedMessages) {
        conversationContinuationIds(snapshot.messages, dayStartIds + listOfNotNull(snapshot.unreadBoundary?.firstUnreadMessageId),
            savedMessages = savedMessages)
    }
    val groupEndIds = remember(snapshot.messages, continuationIds) {
        snapshot.messages.filterIndexed { index, _ -> snapshot.messages.getOrNull(index + 1)?.id !in continuationIds }
            .map { it.id }.toSet()
    }
    val save = saveTarget
    val saveAllowed = save?.let { snapshot.isCurrentMedia(it) &&
        snapshot.messages.firstOrNull { message -> message.id == it.messageId }?.canBeSaved == true && !chat.hasProtectedContent } == true
    LaunchedEffect(save, saveAllowed) {
        if (!saveAllowed) saveTarget = null
    }
    val glassBackdrop = rememberChatBackdrop()
    val reactionAdditionIntents = remember(snapshot.token) { ReactionAdditionIntents() }
    LaunchedEffect(reactionAdditionIntents.current?.id) {
        val id = reactionAdditionIntents.current?.id ?: return@LaunchedEffect
        delay(4500)
        reactionAdditionIntents.finish(id)
    }
    val menuViewport = videoViewport.takeIf { it.width > 0f && it.height > 0f }?.let { viewport ->
        Rect(viewport.left, maxOf(viewport.top, if (headerBounds.height > 0f) headerBounds.bottom else viewport.top),
            viewport.right, minOf(viewport.bottom, if (composerBounds.height > 0f) composerBounds.top else viewport.bottom))
    }?.takeUnless { it.isEmpty }
    CompositionLocalProvider(LocalChatBackdrop provides glassBackdrop,
        LocalConversationMessageMenuViewport provides menuViewport,
        LocalReactionAdditionIntents provides reactionAdditionIntents) {
    Box(modifier.then(if (inheritedModalFocusReturn == null) Modifier.observeComposerModalFocusInput(modalFocusReturn)
        else Modifier).background(MiuixTheme.colorScheme.surface)) {
        ConversationOverlayLayout(content = { composerHeight ->
        val composerInset = with(density) {
            if (composerHeight > 0) (composerHeight + ConversationComposerBlurFeather.roundToPx()).toDp() else 0.dp
        }
        // Move the measured message content down while leaving the editor and its occlusion
        // fixed. Matching opposite padding changes also retain the timeline's scroll extent.
        val historyBottomPadding = composerInset + 8.dp - ConversationMessageVerticalOffset
        Box(Modifier.fillMaxSize().clipToBounds().onGloballyPositioned { videoViewport = it.boundsInWindow() }) {
            val historyTopPadding = headerTopPadding + with(density) { headerBounds.height.toDp() } +
                16.dp + ConversationMessageVerticalOffset
            Box(Modifier.matchParentSize().then(if (glassBackdrop != null)
                Modifier.layerBackdrop(glassBackdrop) else Modifier)) {
            ConversationWallpaper(Modifier.matchParentSize())
            if (snapshot.messages.isEmpty()) {
                Box(Modifier.fillMaxSize().historyWindowMotion(windowMotion)
                    .padding(top = historyTopPadding, bottom = historyBottomPadding), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(if (snapshot.historyError != null) "消息暂时无法加载" else if (snapshot.historyEnd) "还没有消息" else "正在加载消息……",
                            fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        if (snapshot.historyError != null) {
                            ChatErrorText(snapshot.historyError)
                            ChatButton("重试加载消息", enabled = !snapshot.historyLoading, onClick = onLoadOlder)
                        }
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().historyWindowMotion(windowMotion)
                    .nestedScroll(latestFollowScrollConnection)
                    .scrollEndHaptic().overScrollVertical(), state = messageScroll, reverseLayout = true,
                    contentPadding = PaddingValues(top = historyTopPadding, bottom = historyBottomPadding)) {
                    items(snapshot.messages.asReversed(), key = { it.presentationId }) { message ->
                        IncomingMessageEntrance(incomingEntrancePlans[message.presentationId], incomingAnimations,
                            active = reading && !outgoingWindow, atLatest = followingForEntrance,
                            modifier = Modifier.animateItem(fadeInSpec = null,
                                // Keep the animator node and its measured positions mounted between sends.
                                // Removing it makes the next send treat all existing rows as arriving
                                // from beyond the viewport before bottom-following resets the offsets.
                                placementSpec = if (animateSendPlacement) tween(220) else snap(), fadeOutSpec = null)) {
                            if (snapshot.unreadBoundary?.firstUnreadMessageId == message.id) UnreadMessageDivider(0.dp)
                            if (message.id in dayStartIds) {
                                Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), contentAlignment = Alignment.Center) {
                                    Text(messageDayLabel(message.date), fontSize = 12.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        modifier = Modifier.chatSquircleBackground(MiuixTheme.colorScheme.secondaryContainer, 14.dp)
                                            .padding(horizontal = 16.dp, vertical = 5.dp))
                                }
                            }
                            SendBubbleEntrance(sendAnimationFrame.plans[message.presentationId], sendAnimations,
                                active = reading && !outgoingWindow, atLatest = followingForEntrance) {
                                val presentation = conversationMessagePresentation(message, chat,
                                    startsGroup = message.id !in continuationIds || snapshot.unreadBoundary?.firstUnreadMessageId == message.id,
                                    endsGroup = message.id in groupEndIds)
                                MessageBubble(message, sender = snapshot.senderLabel(message), retrying = message.id in snapshot.retryingIds,
                                    showSender = message.id !in continuationIds || snapshot.unreadBoundary?.firstUnreadMessageId == message.id,
                                    endsGroup = message.id in groupEndIds,
                                    showSenderName = presentation.showSenderName, showAvatar = presentation.showAvatar,
                                    reserveAvatarSpace = presentation.reserveAvatarSpace,
                                        onBounds = { bounds ->
                                            if (bounds == null) messageBounds.remove(message.id) else messageBounds[message.id] = bounds
                                            previewMessageBounds?.invoke(message.id, bounds)
                                        },
                                    highlighted = highlightedMessageId == message.id,
                                    horizontalContentPadding = horizontalPadding,
                                    read = message.outgoing && message.delivery == MessageDelivery.SENT && message.id <= chat.lastReadOutboxId,
                                    pageInteractive = pageInteractive && !outgoingWindow,
                                    onRetry = { if (currentPageInteractive) onRetry(message.id) }, snapshot = snapshot, features = features,
                                    visible = enlarged == null && !videoExpanded && message.id in visibleIds,
                                    animate = reading && message.id in visibleIds,
                                    playing = playMessageId == message.id && !videoExpanded,
                                    onStopPlaying = {
                                        stoppedVideoIds = stoppedVideoIds + message.id
                                        playMessageId = null
                                        videoExpanded = false
                                    },
                                    onVideoBounds = { bounds ->
                                        if (bounds == null) videoBounds.remove(message.id) else videoBounds[message.id] = bounds
                                    },
                                    playbackSession = if (playMessageId == message.id) playbackSession else null,
                                    onExpandVideo = {
                                        if (!currentPageInteractive) return@MessageBubble
                                        features.visibleMessages(token, emptyList(), false)
                                        stoppedVideoIds = stoppedVideoIds - message.id
                                        userVideoMessageId = message.id
                                        if (playMessageId != message.id) videoInitiallyMuted = true
                                        if (playMessageId == message.id) playbackSession?.enterViewer()
                                        playMessageId = message.id
                                        videoExpanded = true
                                    },
                                    onEnlarge = { if (currentPageInteractive) { features.visibleMessages(token, emptyList(), false); enlarged = message } },
                                    onSave = { path, name ->
                                        if (!currentPageInteractive) return@MessageBubble
                                        features.visibleMessages(token, emptyList(), false)
                                        message.media?.let { saveTarget = ChatMediaActionTarget(message.id, it.file.id, path, name) }
                                    },
                                    onSaveAs = { path, name ->
                                        if (!currentPageInteractive) return@MessageBubble
                                        features.visibleMessages(token, emptyList(), false)
                                        message.media?.let { saveTarget = ChatMediaActionTarget(message.id, it.file.id, path, name, forceChooseLocation = true) }
                                    },
                                    onPlay = {
                                        if (!currentPageInteractive) return@MessageBubble
                                        stoppedVideoIds = stoppedVideoIds - message.id
                                        userVideoMessageId = message.id
                                        if (playMessageId != message.id) videoInitiallyMuted = false
                                        playMessageId = message.id
                                        videoExpanded = false
                                    },
                                    menuOpen = pageInteractive && menuMessageId == message.id,
                                    onMenuOpen = {
                                        if (!currentPageInteractive) return@MessageBubble
                                        features.visibleMessages(token, emptyList(), false)
                                        menuMessageId = message.id
                                        if (message.id > 0) features.loadMessageActions(token, message.id)
                                    },
                                    onMenuDismiss = {
                                        if (menuMessageId == message.id && currentSnapshot.token == token) {
                                            menuMessageId = null
                                            features.closeMessageActions(token)
                                        }
                                    },
                                    onReply = {
                                        if (!currentPageInteractive) return@MessageBubble
                                        features.setReply(token, message.id)
                                        menuMessageId = null
                                        features.closeMessageActions(token)
                                    },
                                    onEdit = {
                                        if (!currentPageInteractive) return@MessageBubble
                                        if (voiceToken == null) features.startEdit(token, message.id)
                                        else navigationHint = "请先结束当前录音，再编辑消息。"
                                        menuMessageId = null
                                        features.closeMessageActions(token)
                                    },
                                    onQuote = { quote ->
                                        if (!currentPageInteractive) return@MessageBubble
                                        features.setQuote(token, message.id, quote)
                                        menuMessageId = null
                                        features.closeMessageActions(token)
                                    },
                                    onJumpToReply = { reply ->
                                        if (!currentPageInteractive) return@MessageBubble
                                        if (reply.chatId != chat.id && currentSnapshot.knownChats.none { it.id == reply.chatId }) {
                                            navigationHint = "原消息所属聊天尚不可用。"
                                            return@MessageBubble
                                        }
                                        val anchor = currentSnapshot.messages.asReversed().getOrNull(messageScroll.firstVisibleItemIndex)
                                            ?.takeIf { it.id > 0 }?.let {
                                                ConversationReadPosition(chat.id, it.id, messageScroll.firstVisibleItemScrollOffset)
                                            }
                                        navigationHint = null
                                        latestFollow.navigate(atLatest = false)
                                        if (onOpenReply != null) onOpenReply(message, anchor)
                                        else {
                                            anchor?.let {
                                                features.rememberHistoryAnchor(token, it.messageId)
                                                sessionMemory.pushReplyReturn(reply.chatId, it)
                                            }
                                            features.openReplyMessage(token, message.id)
                                        }
                                    },
                                    onReact = {
                                        if (!currentPageInteractive) return@MessageBubble
                                        features.visibleMessages(token, emptyList(), false)
                                        reactionTarget = message.id
                                        menuMessageId = null
                                        features.closeMessageActions(token)
                                        features.loadReactions(token, message.id)
                                    },
                                    onForward = {
                                        if (!currentPageInteractive) return@MessageBubble
                                        mutationTarget = ChatMutationTarget(message.id, ChatMutationKind.FORWARD,
                                            snapshot.messageActions ?: MessageActions())
                                        mutationSubmitted = false
                                        menuMessageId = null
                                    },
                                    onDelete = {
                                        if (!currentPageInteractive) return@MessageBubble
                                        mutationTarget = ChatMutationTarget(message.id, ChatMutationKind.DELETE,
                                            snapshot.messageActions ?: MessageActions())
                                        mutationSubmitted = false
                                        menuMessageId = null
                                    },
                                    onReadDetails = {
                                        if (currentPageInteractive) features.loadReadDetails(token, message.id)
                                    }, onSearchSender = { if (currentPageInteractive) onSearchSender(it) })
                            }
                        }
                    }
                    item(key = "history-page") {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (snapshot.historyLoading) Text("正在加载更早消息……", fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(vertical = 8.dp))
                            snapshot.historyError?.let { error ->
                                ChatErrorText(error)
                                ChatButton("重试加载消息", enabled = !snapshot.historyLoading, onClick = onLoadOlder)
                            }
                        }
                    }
                }
            }
            WorkspaceScrollbar(messageScroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight().historyWindowMotion(windowMotion)
                .padding(top = historyTopPadding, bottom = historyBottomPadding, end = 3.dp), reverseLayout = true)
            }
            snapshot.newerError?.let { error ->
                Row(Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = historyBottomPadding + 16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChatErrorText(error)
                    ChatButton("重试加载后续消息", onClick = { features.loadNewer(token) })
                }
            }
            if (!outgoingWindow) BoxWithConstraints(Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .padding(start = if (compactLayout) 12.dp else 24.dp, end = if (compactLayout) 12.dp else 24.dp,
                    top = headerTopPadding), contentAlignment = Alignment.TopCenter) {
                val available = constraints.maxDpSize(LocalDensity.current)
                Column(Modifier.width(conversationHeaderWidth(available.width))
                    .onGloballyPositioned { headerBounds = it.boundsInWindow(clipBounds = false) }) {
                    ConversationHeader(chat, snapshot, features, active, onBack,
                        menuOpen = pageInteractive && conversationMenuOpen, onMenuOpen = {
                            conversationMenuOpen = it && currentPageInteractive
                            if (it) conversationMenuMounted = true
                        }, onMarkRead = { if (currentPageInteractive) onMarkRead() },
                        onBounds = {}, onSearch = { if (currentPageInteractive) onSearch() },
                        onDetails = { if (currentPageInteractive) onAdmins() }, onMedia = { if (currentPageInteractive) onInfo() },
                        showInfoButton = showInfoButton, onPinned = { if (currentPageInteractive) pinnedListOpen = true },
                        onTogglePinned = { if (currentPageInteractive) pinnedHidden = !pinnedHidden }, pinnedHidden = pinnedHidden,
                        onMenuDismissFinished = { if (!conversationMenuOpen) conversationMenuMounted = false },
                        adminsPopup = adminsPopup)
                    ChatReportSpamPanel(snapshot, features, pageInteractive = pageInteractive,
                        modifier = Modifier.padding(top = 8.dp))
                    if (!pinnedHidden) PinnedMessageBar(snapshot, features,
                        onOpenList = { if (currentPageInteractive) pinnedListOpen = true }, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        }, composer = {
        // Only the naturally sized controls occupy the bottom overlay. The timeline remains
        // full height so scrolling messages pass behind the input surface and through its gaps.
        if (!outgoingWindow) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .onGloballyPositioned {
                val bounds = it.boundsInWindow(clipBounds = false)
                composerBounds = Rect(bounds.left,
                    bounds.top - with(density) { ConversationComposerBlurFeather.roundToPx() }, bounds.right, bounds.bottom)
            }) {
        ChatProgressiveBlur(glassBackdrop, bottom = true,
            Modifier.matchParentSize().blurFeather(ConversationComposerBlurFeather, bottom = true))
        Column(Modifier.fillMaxWidth()
            .padding(start = 12.dp, end = 24.dp, top = 8.dp, bottom = 20.dp)
            .semantics { contentDescription = "消息输入区域" },
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            snapshot.readError?.let { Box(Modifier.padding(horizontal = horizontalPadding, vertical = 6.dp)) { ChatErrorText(it) } }
            navigationHint?.let { Text(it, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                composerActionError?.let { ChatErrorText(it) }
                    if (editing != null) snapshot.editError?.let { ChatErrorText(it) }
                    else if (snapshot.sendErrorChatId == chat.id) snapshot.sendError?.let { ChatErrorText(it) }
                    Box(Modifier.fillMaxWidth().onPreviewKeyEvent {
                        if (voiceToken == null) false else {
                            if (it.key == Key.Escape && it.type == KeyEventType.KeyUp) voiceControl.cancel()
                            else if (currentPageInteractive && voiceControl.isLocked && !snapshot.sendPending && !voiceSubmitted &&
                                it.key == Key.Enter && it.type == KeyEventType.KeyUp && !it.isShiftPressed) voiceControl.send()
                            true
                        }
                    }) {
                    // Keep the held microphone's pointer target mounted while the naturally
                    // measured record bar replaces its pixels. No second recording is created.
                    Box(Modifier.fillMaxWidth().graphicsLayer { alpha = if (voiceToken == null) 1f else 0f }
                        .then(if (voiceToken == null) Modifier else Modifier.clearAndSetSemantics {})) {
                    ConversationComposer(
                        value = if (editing != null) editValue else draft,
                        onValueChange = { if (voiceToken == null) {
                            if (editing != null) { editValue = it; sessionMemory.rememberEdit(editing, it, snapshot.editDraftRevision) }
                            else onDraft(it)
                        } },
                        canSend = (editing != null || chat.canSend) && voiceToken == null,
                        sendPending = if (editing != null) snapshot.editPending else snapshot.sendPending,
                        canSendPhotos = editing == null && chat.canSendPhotos && voiceToken == null,
                        canSendStickers = editing == null && chat.canSendStickers && voiceToken == null,
                        canSendVideos = editing == null && chat.canSendVideos && voiceToken == null,
                        canSendDocuments = editing == null && chat.canSendDocuments && voiceToken == null,
                        canSendVoiceNotes = editing == null && chat.canSendVoiceNotes,
                        editing = editing != null,
                        allowEmptySubmit = editing != null && !editing.kind.isTextContent,
                        onVoice = { startVoice(held = false) },
                        onVoicePress = { startVoice(held = true) },
                        onVoiceRelease = { cancelled ->
                            if (voiceToken == token) {
                                if (cancelled || !currentPageInteractive) voiceControl.cancel() else voiceControl.send()
                            }
                        },
                        onVoiceLock = { if (currentPageInteractive && voiceToken == token) voiceControl.lock() },
                        focusRequester = composerFocusRequester,
                        onSend = { if (currentPageInteractive && voiceToken == null) {
                            if (editing != null) features.editMessage(token, editValue.richText()) else onSend()
                        } },
                        onEscape = if (editing != null || showStickers || snapshot.replyTarget != null) ({
                            if (showStickers) { showStickers = false; stickerAutoHide = false }
                            else if (editing != null) cancelEdit()
                            else features.setReply(token, null)
                        }) else null,
                        onEditLast = {
                            if (!currentPageInteractive) return@ConversationComposer
                            composerActionError = null
                            if (mediaActive && !videoExpanded && draft.text.isEmpty()) {
                                features.requestPreviousEditable(token, null) { result ->
                                    scope.launch {
                                        if (currentSnapshot.token == token && currentPageInteractive && currentEditShortcutActive &&
                                            currentReadDraft().text.isEmpty() && currentSnapshot.editTarget == null) {
                                            result.value?.let { features.startEdit(token, it) }
                                        }
                                    }
                                }
                            }
                        },
                        onPhoto = {
                            if (!currentPageInteractive) return@ConversationComposer
                            features.visibleMessages(token, emptyList(), false)
                            photoToken = token
                            photoDraftTransfer = ComposerDraftTransfer(token, currentReadDraft(), currentReadDraftRevision())
                            photoError = null
                            photoSubmitted = false
                        },
                        onSticker = {
                            if (showStickers) { showStickers = false; stickerAutoHide = false }
                            else { stickerAutoHide = stickerTriggerHovered; openStickers() }
                        },
                        onStickerHoverChanged = { hovered ->
                            stickerTriggerHovered = hovered && currentPageInteractive && voiceToken == null
                            stickerAutoHide = true
                            if (stickerTriggerHovered) openStickers()
                        },
                        onVideo = {
                            if (!currentPageInteractive) return@ConversationComposer
                            features.visibleMessages(token, emptyList(), false)
                            attachmentKind = AttachmentKind.VIDEO
                            attachmentToken = token
                            attachmentDraftTransfer = ComposerDraftTransfer(token, currentReadDraft(), currentReadDraftRevision())
                            attachmentError = null
                            attachmentSubmitted = false
                        },
                        onFile = {
                            if (!currentPageInteractive) return@ConversationComposer
                            features.visibleMessages(token, emptyList(), false)
                            attachmentKind = AttachmentKind.FILE
                            attachmentToken = token
                            attachmentDraftTransfer = ComposerDraftTransfer(token, currentReadDraft(), currentReadDraftRevision())
                            attachmentError = null
                            attachmentSubmitted = false
                        },
                        onMenuOpenChange = {
                            composerMenuOpen = it
                            if (it) { showStickers = false; stickerAutoHide = false }
                        },
                        onStickerAnchorChanged = { stickerAnchor = it },
                        replyContent = if (editing != null) ({
                            MessageEditComposerBar(editing, snapshot.editPending, onCancel = ::cancelEdit)
                        }) else snapshot.replyTarget?.let { reply -> ({
                            ReplyComposerBar(reply, snapshot, onCancel = { features.setReply(token, null) }, features = features)
                        }) },
                        interactive = pageInteractive,
                    )
                    }
                    VoiceRecordingBar(show = pageInteractive && voiceToken != null, onDismiss = { voiceToken = null; voiceSubmitted = false },
                        onSend = { recording -> voiceToken?.takeIf {
                            currentPageInteractive && it == currentSnapshot.token && currentSnapshot.editTarget == null
                        }?.let {
                            voiceSubmitted = true
                            features.sendVoiceNote(it, recording)
                        } }, pending = snapshot.sendPending,
                        error = snapshot.sendError.takeIf { voiceSubmitted && snapshot.sendErrorChatId == voiceToken?.chatId },
                        control = voiceControl)
                    }
            }
        }
        }
        }, floating = { composerHeight ->
            // A completed unread/search/reply jump is an anchor, not evidence of newer
            // messages. Only a pending jump or a real native gap overrides scroll geometry.
            val hasReplyReturn = sessionMemory.replyReturn?.destinationChatId == chat.id
            if (!outgoingWindow && (hasReplyReturn || !nearLatest || positioning || snapshot.historyHasNewer) && snapshot.messages.isNotEmpty()) {
                // Enter the progressive feather by the requested small offset. Drawing above
                // the overlay keeps the downward shadow intact and clear of the actual input.
                val blurTop = with(density) {
                    if (composerHeight > 0) (composerHeight + ConversationComposerBlurFeather.roundToPx()).toDp() else 0.dp
                }
                FloatingScrollButton(up = false, unreadCount = chat.unreadCount, downwardShadow = true,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp,
                        bottom = (blurTop - ConversationLatestButtonVerticalOffset).coerceAtLeast(0.dp)),
                    onClick = {
                        if (!currentPageInteractive || pendingEntryPosition != null) return@FloatingScrollButton
                        if (hasReplyReturn) {
                            val returning = sessionMemory.replyReturn?.takeIf { it.destinationChatId == chat.id }
                                ?: return@FloatingScrollButton
                            latestFollow.navigate(atLatest = false)
                            // Lock synchronously, including the workspace delegate: a second
                            // pointer event must not pop a nested return before native placement.
                            pendingEntryPosition = returning.position
                            entryPositionJumpRevision = snapshot.jumpRevision
                            entryPositionRequested = true
                            if (onReplyReturn != null) onReplyReturn()
                            else sessionMemory.beginReplyReturn(chat.id, snapshot.jumpRevision)?.let { anchor ->
                                pendingEntryPosition = anchor
                                features.openReplyReturn(token, anchor.chatId, anchor.messageId)
                            }
                        } else if (snapshot.historyHasNewer || positioning) {
                            sessionMemory.prepareEntry(chat.id, restore = false)
                            sessionMemory.completePosition(chat.id)
                            pendingEntryPosition = null
                            if (onReturnToLatest != null) onReturnToLatest() else features.returnToLatest(token)
                        }
                        else scope.launch {
                            latestFollow.navigate(atLatest = true)
                            messageScroll.animateScrollToItem(0)
                        }
                    })
            }
        })
        if (confirmDiscardEdit && editing != null) WindowDialog(show = pageInteractive, title = "放弃修改？", maxWidth = 360.dp,
            onDismissRequest = { confirmDiscardEdit = false }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("你对这条消息的修改将不会保存。")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChatButton("继续编辑", Modifier.weight(1f), onClick = { confirmDiscardEdit = false })
                    ChatButton("放弃修改", Modifier.weight(1f), primary = true, onClick = {
                        if (!currentPageInteractive) return@ChatButton
                        confirmDiscardEdit = false
                        features.cancelEdit(token)
                        composerFocusRequester.requestFocus()
                    })
                }
            }
        }
        if (pinnedListOpen && pageInteractive) PinnedMessagesDialog(snapshot, features, onDismiss = { pinnedListOpen = false })
        enlarged?.let { captured ->
            val message = snapshot.messages.firstOrNull { it.id == captured.id } ?: captured
            message.media?.let { media ->
                MediaViewer(media, snapshot, features, modifier = Modifier.matchParentSize(),
                    canSave = message.canBeSaved && !chat.hasProtectedContent,
                    onSave = { path, name -> saveTarget = ChatMediaActionTarget(message.id, media.file.id, path, name) },
                    onDismiss = { enlarged = null })
            }
        }
        if (videoExpanded && playingMessage != null && playbackSession != null) {
            val media = checkNotNull(playingMessage.media)
            val file = snapshot.mediaFiles[media.file.id] ?: media.file
            val path = file.localPath?.takeIf { file.downloaded }
            val total = maxOf(file.size, file.expectedSize)
            val saveVideo: (() -> Unit)? = if (path != null && playingMessage.canBeSaved && !chat.hasProtectedContent) ({
                saveTarget = ChatMediaActionTarget(playingMessage.id, file.id, path, media.fileName.ifBlank { "Telegram-video.mp4" })
            }) else null
            MessageBodyInteractionProvider(playingMessage, snapshot, pageInteractive,
                currentSnapshot = { currentSnapshot }) {
            VideoPlayback(show = true, path = path.orEmpty(), session = playbackSession, expanded = true,
                active = mediaActive, modifier = Modifier.matchParentSize(), onDismiss = {
                    playbackSession.leaveViewer()
                    videoExpanded = false
                },
                caption = playingMessage.richText(), title = media.fileName, onSave = saveVideo,
                allowCaptionCopy = playingMessage.canBeSaved && !chat.hasProtectedContent,
                canCopyCaptionNow = {
                    val latest = currentSnapshot
                    val source = latest.messages.firstOrNull { it.id == playingMessage.id && it.chatId == playingMessage.chatId }
                    latest.token == token && latest.selected?.id == playingMessage.chatId &&
                        latest.selected?.hasProtectedContent == false && source?.canBeSaved == true &&
                        source.richText() == playingMessage.richText()
                },
                downloadedFraction = if (file.downloaded) 1f else if (total > 0) (file.downloadedSize.toDouble() / total).toFloat() else 0f)
            }
        }
    }
    }
    CompositionLocalProvider(LocalReactionAdditionIntents provides reactionAdditionIntents) {
    MessageReactionPicker(snapshot.messages.firstOrNull { pageInteractive && it.id == reactionTarget }, snapshot, features, onDismiss = {
            reactionTarget = null
            features.closeReactions(token)
        }, anchor = reactionTarget?.let { messageBounds[it] })
    }
    StickerPicker(showStickers && pageInteractive, snapshot, features, active, onDismiss = { showStickers = false; stickerAutoHide = false },
        anchor = stickerAnchor, onMountedChange = { stickerPickerMounted = it },
        onHoverChanged = { hovered -> stickerPanelHovered = hovered; if (hovered) stickerAutoHide = true },
        onSent = { sentToken ->
            // The explicit sticker action returns to this field; a later native result never owns focus.
            if (currentPageInteractive && currentSnapshot.enabled && currentSnapshot.token == sentToken &&
                currentSnapshot.selected?.canSend == true && currentSnapshot.editTarget == null && voiceToken == null) {
                composerFocusRequester.requestFocus()
            }
        })
    PhotoAttachmentDialog(show = pageInteractive && photoToken != null && editing == null,
        onDismiss = {
            modalFocusReturn.cancel(photoFocusIntent)
            photoFocusIntent = null
            photoToken = null; photoSubmitted = false; photoDraftTransfer = null
        }, onSend = { path, caption ->
        photoDraftTransfer?.takeIf { !photoSubmitted && currentPageInteractive && photoToken == it.token &&
            it.token == currentSnapshot.token && currentSnapshot.editTarget == null }?.let { transfer ->
            photoSubmitted = true
            val focusIntent = modalFocusReturn.begin(transfer.token)
            photoFocusIntent = focusIntent
            photoError = null
            var completionHandled = false
            val complete: (ChatResult<Unit>) -> Unit = { result -> scope.launch {
                if (completionHandled) return@launch
                completionHandled = true
                if (result.error == null && result.value != null && currentSnapshot.enabled && currentPageInteractive &&
                    currentSnapshot.editTarget == null && transfer.canConsume(currentSnapshot.token, currentReadDraft(), currentReadDraftRevision())) {
                    currentOnDraft(TextFieldValue())
                }
                if (currentPageInteractive && photoToken == transfer.token && photoDraftTransfer === transfer &&
                    currentSnapshot.token == transfer.token && currentSnapshot.editTarget == null) {
                    photoSubmitted = false
                    photoError = result.error ?: ChatError.REQUEST_FAILED.takeIf { result.value == null }
                    if (photoError == null) {
                        photoToken = null
                        photoDraftTransfer = null
                        photoFocusIntent = null
                        modalFocusReturn.accepted(focusIntent) { photoToken == null }
                    } else {
                        modalFocusReturn.cancel(focusIntent)
                    }
                } else modalFocusReturn.cancel(focusIntent)
            }; Unit }
            try { features.submitClipboardPhoto(transfer.token, path, caption, null, complete) }
            catch (_: Exception) { complete(ChatResult(error = ChatError.REQUEST_FAILED)) }
        }
    }, pending = editing != null || photoSubmitted || snapshot.sendPending || (photoToken != null && photoToken != token),
        error = if (photoToken != null && photoToken != token) ChatError.NOT_READY
            else photoError, initialCaption = photoDraftTransfer?.caption ?: TextFieldValue())
    AttachmentDialog(show = pageInteractive && attachmentToken != null && editing == null, kind = attachmentKind,
        onDismiss = {
            modalFocusReturn.cancel(attachmentFocusIntent)
            attachmentFocusIntent = null
            attachmentToken = null; attachmentSubmitted = false; attachmentDraftTransfer = null
        }, onSend = { attachment, caption ->
            attachmentDraftTransfer?.takeIf { !attachmentSubmitted && currentPageInteractive && attachmentToken == it.token &&
                it.token == currentSnapshot.token && currentSnapshot.editTarget == null }?.let { transfer ->
                attachmentSubmitted = true
                val focusIntent = modalFocusReturn.begin(transfer.token)
                attachmentFocusIntent = focusIntent
                attachmentError = null
                var completionHandled = false
                val complete: (ChatResult<Unit>) -> Unit = { result -> scope.launch {
                    if (completionHandled) return@launch
                    completionHandled = true
                    if (result.error == null && result.value != null && currentSnapshot.enabled && currentPageInteractive &&
                        currentSnapshot.editTarget == null && transfer.canConsume(currentSnapshot.token, currentReadDraft(), currentReadDraftRevision())) {
                        currentOnDraft(TextFieldValue())
                    }
                    if (currentPageInteractive && attachmentToken == transfer.token && attachmentDraftTransfer === transfer &&
                        currentSnapshot.token == transfer.token && currentSnapshot.editTarget == null) {
                        attachmentSubmitted = false
                        attachmentError = result.error ?: ChatError.REQUEST_FAILED.takeIf { result.value == null }
                        if (attachmentError == null) {
                            attachmentToken = null
                            attachmentDraftTransfer = null
                            attachmentFocusIntent = null
                            modalFocusReturn.accepted(focusIntent) { attachmentToken == null }
                        } else {
                            modalFocusReturn.cancel(focusIntent)
                        }
                    } else modalFocusReturn.cancel(focusIntent)
                }; Unit }
                try { features.submitClipboardFile(transfer.token, attachment, caption, complete) }
                catch (_: Exception) { complete(ChatResult(error = ChatError.REQUEST_FAILED)) }
            }
        }, pending = editing != null || attachmentSubmitted || snapshot.sendPending || (attachmentToken != null && attachmentToken != token),
        error = if (attachmentToken != null && attachmentToken != token) ChatError.NOT_READY
            else attachmentError, initialCaption = attachmentDraftTransfer?.caption ?: TextFieldValue())
    if (pageInteractive && save != null && saveAllowed) {
        SaveMediaFile(show = true, path = save.path, suggestedName = save.name,
            forceChooseLocation = save.forceChooseLocation, onDismiss = { saveTarget = null })
    }
    mutationTarget?.takeIf { pageInteractive }?.let { target ->
        snapshot.messages.firstOrNull { it.id == target.messageId }?.let { message ->
            val error = snapshot.mutationError.takeIf { mutationSubmitted &&
                snapshot.mutationMessageId == target.messageId && snapshot.mutationKind == target.kind }
            val dismiss = {
                mutationTarget = null
                mutationSubmitted = false
                features.closeMessageActions(token)
            }
            when (target.kind) {
                ChatMutationKind.DELETE -> DeleteMessageDialog(message, target.actions, snapshot.mutationPending, error,
                    onDelete = { revoke ->
                        if (!currentPageInteractive) return@DeleteMessageDialog
                        mutationSubmitted = true
                        mutationRevisionAtSubmit = snapshot.mutationRevision
                        features.deleteMessage(token, message.id, revoke)
                    }, onDismiss = dismiss)
                ChatMutationKind.FORWARD -> ForwardMessageDialog(message, snapshot, snapshot.mutationPending, error,
                    onForward = { targetChatId ->
                        if (!currentPageInteractive) return@ForwardMessageDialog
                        mutationSubmitted = true
                        mutationRevisionAtSubmit = snapshot.mutationRevision
                        features.forwardMessage(token, message.id, targetChatId)
                    }, onDismiss = dismiss)
            }
        }
    }
}

@Immutable
private data class ChatMutationTarget(val messageId: Long, val kind: ChatMutationKind, val actions: MessageActions) {
    override fun toString() = "ChatMutationTarget(<redacted>)"
}

@Immutable
private data class ChatMediaActionTarget(val messageId: Long, val fileId: Int, val path: String, val name: String = "",
    val forceChooseLocation: Boolean = false) {
    override fun toString() = "ChatMediaActionTarget(<redacted>)"
}

private fun ChatSnapshot.isCurrentMedia(target: ChatMediaActionTarget): Boolean {
    val media = messages.firstOrNull { it.id == target.messageId }?.media ?: return false
    if (media.file.id != target.fileId) return false
    val file = mediaFiles[target.fileId] ?: media.file
    return file.downloaded && file.localPath == target.path
}

private data class ChatScrollAnchor(val messageId: Long, val offset: Int)

private data class HistoryPreloadRequest(val direction: HistoryPreloadDirection, val oldestId: Long?, val newestId: Long?)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun MessageBubble(
    message: ChatMessage,
    sender: String,
    showSender: Boolean,
    endsGroup: Boolean,
    onBounds: (Rect?) -> Unit,
    retrying: Boolean,
    highlighted: Boolean,
    read: Boolean,
    onRetry: () -> Unit,
    snapshot: ChatSnapshot,
    features: ChatFeatureActions,
    visible: Boolean,
    animate: Boolean,
    onEnlarge: (MessageMedia) -> Unit,
    onSave: (String, String) -> Unit,
    onPlay: () -> Unit,
    playing: Boolean,
    onStopPlaying: () -> Unit,
    onVideoBounds: (Rect?) -> Unit,
    playbackSession: VideoPlaybackSession?,
    onExpandVideo: () -> Unit,
    menuOpen: Boolean,
    onMenuOpen: () -> Unit,
    onMenuDismiss: () -> Unit,
    onReply: () -> Unit,
    onQuote: (MessageQuote) -> Unit,
    onJumpToReply: (MessageReply) -> Unit,
    onReact: () -> Unit,
    onReadDetails: () -> Unit,
    onEdit: () -> Unit,
    onForward: () -> Unit,
    onDelete: () -> Unit,
    onSearchSender: (Long) -> Unit,
    pageInteractive: Boolean = true,
    onOpenLink: (String) -> Boolean = ::openExternalChatLink,
    showSenderName: Boolean = showSender,
    showAvatar: Boolean = showSender && !message.outgoing,
    reserveAvatarSpace: Boolean = !message.outgoing,
    horizontalContentPadding: Dp = 0.dp,
    onSaveAs: ((String, String) -> Unit)? = null,
) {
    val compactLayout = LocalDesktopChatLayout.current.compact
    val senderAvatarSize = 33.dp
    val bareSticker = message.kind == MessageKind.STICKER && message.media != null
    val animatedEmoji = message.kind == MessageKind.ANIMATED_EMOJI
    val inlinePhoto = message.kind == MessageKind.PHOTO && message.media != null
    val desiredEmojiCanvasSize = if (message.media == null) 38.dp else 112.dp
    val unwrapped = bareSticker || animatedEmoji
    var mediaHovered by remember(snapshot.token, message.id) { mutableStateOf(false) }
    val content = message.richText()
    val displaySenderName = !animatedEmoji && showSenderName && (!message.outgoing || message.senderIsChat)
    val latestOutgoingMedia = message.outgoing &&
        snapshot.selected?.takeIf { it.id == message.chatId }?.lastMessage?.let {
            it.id == message.id && it.chatId == message.chatId
        } == true && snapshot.selected?.privateUserId?.let { it == snapshot.account?.userId } != true
    val currentBubbleSnapshot by rememberUpdatedState(snapshot)
    val copyAllowed = message.canBeSaved && snapshot.token.chatId == message.chatId &&
        snapshot.selected?.id == message.chatId && snapshot.selected?.hasProtectedContent == false
    val sourceAvailable = snapshot.enabled && snapshot.token.chatId == message.chatId &&
        snapshot.selected?.id == message.chatId && snapshot.messages.any { it.id == message.id && it.chatId == message.chatId }
    if (message.media?.format == MediaFormat.FILE)
        AutomaticMediaVisibility(features, snapshot.token, message.id, visible && animate && pageInteractive && sourceAvailable)
    var menuOwnerToken by remember { mutableStateOf(snapshot.token) }
    SideEffect { if (!menuOpen) menuOwnerToken = snapshot.token }
    val menuIdentityValid = menuOwnerToken == snapshot.token
    val effectiveMenuOpen = menuOpen && pageInteractive && sourceAvailable && menuIdentityValid
    LaunchedEffect(menuOpen, pageInteractive, sourceAvailable, menuIdentityValid) {
        if (menuOpen && !effectiveMenuOpen) onMenuDismiss()
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val currentDensity = LocalDensity.current
        val available = constraints.maxDpSize(currentDensity)
        val contentMaxWidth = (available.width - horizontalContentPadding * 2).coerceAtLeast(1.dp)
        val emojiCanvasSize = minOf(desiredEmojiCanvasSize,
            (contentMaxWidth - if (reserveAvatarSpace) 40.dp else 0.dp).coerceAtLeast(1.dp))
        val availablePhotoWidth = (contentMaxWidth - if (reserveAvatarSpace) 40.dp else 0.dp).coerceAtLeast(1.dp).value
        val photoHasBubble = content.text.isNotEmpty() || message.replyTo != null || message.forwardInfo != null || displaySenderName
        val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
        val captionStyle = chatMessageTextStyle()
        val measuredCaptionWidth = remember(message.kind, content, captionStyle, currentDensity) {
            if (message.kind != MessageKind.PHOTO || content.text.isEmpty()) 0f else with(currentDensity) {
                textMeasurer.measure(content.text, style = captionStyle).size.width.toDp().value
            }
        }
        // Message::minWidthForMedia reserves the measured image info plus 24dp.
        val minimumPhotoInfoWidth = if (inlinePhoto) with(currentDensity) {
            textMeasurer.measure(messageClock(message.date), style = captionStyle).size.width.toDp().value +
                (if (message.editDate > 0) textMeasurer.measure("已编辑", style = captionStyle).size.width.toDp().value + 8f else 0f) +
                (if (message.outgoing) 26f + 8f else 0f) + 24f
        } else 0f
        val naturalMediaSize = message.media?.let {
            when {
                animatedEmoji -> MessageMediaSize(112f, 112f)
                bareSticker -> stickerMessageSize(it.width, it.height)
                message.kind == MessageKind.ANIMATION -> animationMessageSize(it.width, it.height)
                message.kind == MessageKind.PHOTO -> photoMessageSize(it.width, it.height,
                    availableWidth = availablePhotoWidth, hasBubble = photoHasBubble, captionWidth = measuredCaptionWidth,
                    minimumInfoWidth = minimumPhotoInfoWidth)
                else -> messageMediaSize(it.width, it.height, maxEdge = 520f)
            }
        }
        val mediaWidth = when {
            animatedEmoji -> (if (message.replyTo != null || message.forwardInfo != null) 260.dp else emojiCanvasSize)
                .coerceAtMost((contentMaxWidth - if (reserveAvatarSpace) 40.dp else 0.dp).coerceAtLeast(1.dp))
            message.media?.format == MediaFormat.FILE -> 392.dp
            message.media != null -> checkNotNull(naturalMediaSize).width.dp + if (bareSticker || inlinePhoto) 0.dp else 22.dp
            content.entities.any { it.kind == MessageEntityKind.PRE } -> 534.dp
            else -> null
        }
        // Keep selection in memory and discard it when the message or conversation changes.
        val selectionState = remember(snapshot.token, message.presentationId, content) { SelectionState() }
        var menuQuote by remember(snapshot.token, message.id, content) { mutableStateOf<MessageQuote?>(null) }
        var menuCopyRequest by remember(snapshot.token, message.id) { mutableStateOf<MessageTextCopyRequest?>(null) }
        var menuAnchorBounds by remember(snapshot.token, message.id) { mutableStateOf<Rect?>(null) }
        val currentVideoBounds by rememberUpdatedState(onVideoBounds)
        val currentMessageBounds by rememberUpdatedState(onBounds)
        DisposableEffect(message.id, message.media?.file?.id) {
            onDispose { currentVideoBounds(null) }
        }
        DisposableEffect(message.id) {
            onDispose { currentMessageBounds(null) }
        }
        MessageActionsAnchor(sourceKey = snapshot.token to message.id, expanded = effectiveMenuOpen,
            pageInteractive = pageInteractive, anchorBounds = menuAnchorBounds, onDismiss = onMenuDismiss,
            content = { sourceModifier ->
        Row(Modifier.fillMaxWidth().then(sourceModifier).messageJumpHighlight(highlighted, snapshot.token to message.presentationId)
            .padding(horizontal = horizontalContentPadding)
            .padding(bottom = if (message.id == snapshot.messages.lastOrNull()?.id) 0.dp
            else if (endsGroup) 10.dp else 3.dp),
            horizontalArrangement = if (message.outgoing) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Top) {
            if (reserveAvatarSpace) {
                if (showAvatar) {
                    SenderIdentityAvatar(message, snapshot, features, visible = visible,
                        modifier = Modifier.align(Alignment.Bottom), size = senderAvatarSize)
                } else {
                    Spacer(Modifier.width(senderAvatarSize))
                }
                Spacer(Modifier.width(7.dp))
            }
            Box(Modifier.widthIn(min = if (unwrapped) 0.dp else 72.dp,
                max = if (message.media != null || content.entities.any { it.kind == MessageEntityKind.PRE }) 614.dp else 430.dp)
                .then(if (mediaWidth != null) Modifier.width(mediaWidth) else Modifier.width(IntrinsicSize.Max))
                .onPointerEvent(PointerEventType.Enter) { if (animatedEmoji || inlinePhoto) mediaHovered = true }
                .onPointerEvent(PointerEventType.Exit) { if (animatedEmoji || inlinePhoto) mediaHovered = false }
                .onGloballyPositioned {
                    val bounds = it.boundsInWindow(clipBounds = false)
                    menuAnchorBounds = bounds
                    onBounds(bounds)
                }
                .onPointerEvent(PointerEventType.Press, pass = PointerEventPass.Initial) { event ->
                    if (pageInteractive && sourceAvailable && event.buttons.isSecondaryPressed) {
                        // Freeze before the focusable popup clears the text selection.
                        menuQuote = content.quoteFromSelection(selectionState.selectedTexts)
                        menuCopyRequest = messageTextCopyRequest(snapshot.token, message, selectionState.selectedTexts)
                        menuOwnerToken = snapshot.token
                        onMenuOpen()
                        event.changes.forEach { it.consume() }
                    }
                }) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (bareSticker && displaySenderName) Text(sender,
                        style = chatMessageTextStyle().copy(fontWeight = FontWeight.SemiBold),
                        color = peerSenderNameColor(message, snapshot),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (inlinePhoto) {
                        PhotoMessageContent(message, snapshot, features, checkNotNull(naturalMediaSize),
                            sender = sender, displaySenderName = displaySenderName,
                            visible = visible, active = animate, pageInteractive = pageInteractive,
                            showImageInfo = mediaHovered || message.delivery != MessageDelivery.SENT || latestOutgoingMedia,
                            read = read, retrying = retrying, compactLayout = compactLayout,
                            selectionState = selectionState, copyAllowed = copyAllowed,
                            currentSnapshot = { currentBubbleSnapshot }, onOpenLink = onOpenLink,
                            onEnlarge = { onEnlarge(checkNotNull(message.media)) }, onJumpToReply = onJumpToReply,
                            onRetry = onRetry)
                    } else {
                        val surface = if (unwrapped) Modifier else Modifier
                            .chatSquircleBackground(if (message.outgoing) lerp(MiuixTheme.colorScheme.surfaceContainer, MiuixTheme.colorScheme.primary, 0.1f)
                                else MiuixTheme.colorScheme.surfaceContainer, 15.dp)
                            .padding(horizontal = 11.dp, vertical = 8.dp)
                        Column(Modifier.fillMaxWidth().then(surface), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            if (!unwrapped && displaySenderName) Text(sender,
                                style = chatMessageTextStyle().copy(fontWeight = FontWeight.SemiBold),
                                color = peerSenderNameColor(message, snapshot),
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (animatedEmoji && (message.forwardInfo != null || message.replyTo != null)) {
                                Column(Modifier.fillMaxWidth()
                                    .chatSquircleBackground(MiuixTheme.colorScheme.surfaceContainer.copy(alpha = .94f), 8.dp)
                                    .padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    message.forwardInfo?.let { ForwardedMessageHeader(it, snapshot) }
                                    message.replyTo?.let { ReplyReference(it, snapshot, onJump = onJumpToReply, active = visible && animate && pageInteractive && sourceAvailable) }
                                }
                            } else {
                                message.forwardInfo?.let { ForwardedMessageHeader(it, snapshot) }
                                message.replyTo?.let { ReplyReference(it, snapshot, onJump = onJumpToReply, active = visible && animate && pageInteractive && sourceAvailable) }
                            }
                            val media = message.media
                            val captionAbove = message.showCaptionAboveMedia && content.text.isNotEmpty() &&
                                message.kind in setOf(MessageKind.PHOTO, MessageKind.VIDEO, MessageKind.ANIMATION) && media != null
                            val textBody: @Composable ((androidx.compose.ui.text.TextLayoutResult) -> Unit) -> Unit = { reportLayout ->
                                MessageBodyInteractionProvider(message, snapshot, pageInteractive,
                                    currentSnapshot = { currentBubbleSnapshot }, openLink = onOpenLink) {
                                    InlineMessageEmojiProvider(snapshot, features, visible, active = animate) {
                                        RichMessageBody(content, selectionState = selectionState, allowCopy = copyAllowed,
                                            canCopyNow = {
                                                val latest = currentBubbleSnapshot
                                                val source = latest.messages.firstOrNull { it.id == message.id && it.chatId == message.chatId }
                                                latest.token == snapshot.token && latest.selected?.id == message.chatId &&
                                                    latest.selected?.hasProtectedContent == false && source?.canBeSaved == true && source.richText() == content
                                            }, onTextLayout = reportLayout)
                                    }
                                }
                            }
                            val metadata: @Composable () -> Unit = {
                                FlowRow(modifier = if (unwrapped) Modifier
                                    .chatSquircleBackground(MiuixTheme.colorScheme.surfaceContainer.copy(alpha = .82f), 6.dp)
                                    .padding(horizontal = 4.dp, vertical = 1.dp) else Modifier,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                                    verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(messageClock(message.date), style = chatMessageTextStyle(),
                                        color = MiuixTheme.colorScheme.onSurfaceVariantActions)
                                    if (message.editDate > 0) Text("已编辑", style = chatMessageTextStyle(),
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                    if (message.outgoing) OutgoingDeliveryIndicator(message, read, retrying, compactLayout)
                                }
                            }
                            if (captionAbove) textBody { }
                            if (animatedEmoji) {
                                Box(Modifier.fillMaxWidth().height(emojiCanvasSize)) {
                                    AnimatedEmojiMessageContent(message, snapshot, features, visible,
                                        active = animate && pageInteractive, modifier = Modifier
                                            .align(if (message.outgoing) Alignment.TopEnd else Alignment.TopStart)
                                            .size(emojiCanvasSize))
                                    if (mediaHovered || message.delivery != MessageDelivery.SENT ||
                                        latestOutgoingMedia || message.outgoing && media == null) {
                                        Box(Modifier.align(Alignment.BottomEnd)) { metadata() }
                                    }
                                }
                            } else if (media != null && message.kind == MessageKind.ANIMATION) {
                                BoxWithConstraints(Modifier.fillMaxWidth()) {
                                    val available = constraints.maxDpSize(LocalDensity.current)
                                    val display = animationMessageSize(media.width, media.height, availableWidth = available.width.value)
                                    AnimationMessageContent(media, snapshot, features, visible = visible, active = animate && pageInteractive,
                                        messageId = message.id, modifier = Modifier.size(display.width.dp, display.height.dp))
                                }
                            } else if (media != null && (media.format == MediaFormat.VIDEO || media.format == MediaFormat.FILE)) {
                                AttachmentMedia(media, snapshot, features, visible,
                                    canSave = message.canBeSaved && snapshot.selected?.hasProtectedContent == false,
                                    onSave = onSave, onPlay = onPlay, messageId = message.id, playing = playing,
                                    active = animate && visible, onStopPlaying = onStopPlaying, playbackSession = playbackSession,
                                    onExpand = onExpandVideo,
                                    modifier = if (media.format == MediaFormat.VIDEO) Modifier.onGloballyPositioned {
                                        onVideoBounds(it.boundsInWindow(clipBounds = false))
                                    } else Modifier)
                            } else if (media != null) BoxWithConstraints(Modifier.fillMaxWidth()) {
                                val available = constraints.maxDpSize(LocalDensity.current)
                                if (bareSticker) {
                                    val display = stickerMessageSize(media.width, media.height, availableWidth = available.width.value)
                                    StickerMessageMedia(media, snapshot, features, visible, animate,
                                        modifier = Modifier.size(display.width.dp, display.height.dp))
                                } else {
                                    val display = messageMediaSize(media.width, media.height, availableWidth = available.width.value, maxEdge = 520f)
                                    RemoteMedia(media, snapshot, features, visible, animate,
                                        modifier = Modifier.size(display.width.dp, display.height.dp))
                                }
                            }
                            else if (message.kind != MessageKind.TEXT) {
                                Text(message.kind.label(), fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            }
                            val footer: @Composable () -> Unit = {
                                MessageReactionRow(message, snapshot, features, active = visible && animate,
                                    metadata = if (animatedEmoji) ({}) else metadata)
                            }
                            if (!animatedEmoji && message.text.isNotEmpty() && !captionAbove) MessageTextFooterLayout(
                                canInline = media == null && message.reactions.isEmpty() && content.entities.none {
                                    it.kind == MessageEntityKind.PRE || it.kind == MessageEntityKind.BLOCK_QUOTE ||
                                        it.kind == MessageEntityKind.EXPANDABLE_BLOCK_QUOTE
                                },
                                layoutKey = content,
                                body = textBody, footer = footer,
                            ) else footer()
                            if (message.delivery == MessageDelivery.FAILED) {
                                if (message.canRetry) {
                                    if (message.retryAfterSeconds > 0) Text("Telegram 要求等待至少 ${message.retryAfterSeconds} 秒后重试。",
                                        fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                    ChatButton(if (retrying) "重试中……" else "重试发送", enabled = !retrying, onClick = onRetry)
                                } else {
                                    Text("此消息无法直接重试，请使用官方客户端查看详情。", fontSize = 11.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                }
                            }
                        }
                    }
                    MessageInlineKeyboard(message, snapshot, enabled = pageInteractive,
                        currentSnapshot = { currentBubbleSnapshot }, openLink = onOpenLink)
                }
            }
        }
        }, menu = { preview, mounted ->
                MessageActionsMenu(message, snapshot,
                    canReply = message.id > 0 && message.delivery == MessageDelivery.SENT && snapshot.selected?.let {
                        it.canSend || it.canSendPhotos || it.canSendStickers || it.canSendVideos || it.canSendDocuments
                    } == true,
                    canQuote = menuQuote != null,
                    onReply = onReply, onQuote = { menuQuote?.let(onQuote) }, onReact = onReact,
                    onReadDetails = onReadDetails, onDismiss = onMenuDismiss,
                    onReload = { features.loadMessageActions(snapshot.token, message.id) },
                    onEdit = onEdit, onForward = onForward, onDelete = onDelete, expanded = effectiveMenuOpen,
                    features = features, onSearchSender = onSearchSender,
                    copyRequest = menuCopyRequest, pageInteractive = pageInteractive && sourceAvailable && menuIdentityValid,
                    preview = preview, onMountedChange = mounted, onSaveAs = onSaveAs)
        })
    }
}

