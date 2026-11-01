package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import com.badnng.moe.chat.*
import com.badnng.moe.status.ConnectionPhase
import com.badnng.moe.status.presentation
import kotlin.time.Instant
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import kotlin.math.roundToInt

/** Only the explicit offline renderer supplies a value; live UI always uses its animated state. */
val LocalAccountMenuPreviewProgress = staticCompositionLocalOf<Float?> { null }

/** App owns this for an engine generation, including while settings replaces the page. */
internal class ChatWorkspaceSession(snapshot: ChatSnapshot) {
    val showConversation = mutableStateOf(snapshot.selected != null)
    val requestedConversationChatId = mutableStateOf(snapshot.selected?.id)
    val requestedNavigationFailureRevision = mutableStateOf(snapshot.navigationFailureRevision)
    val retainedConversationSnapshot = mutableStateOf(snapshot)
    val previousSelectedChatId = mutableStateOf(snapshot.token.chatId)
    val conversationMemory = ConversationSessionMemory()
    val chatListPaneState = ChatListPaneState(snapshot.activeList)
    val requestedListWidth = mutableStateOf<Dp?>(null)
    val searchOpen = mutableStateOf(false)
    val searchReturnToConversation = mutableStateOf(false)
    val initialSearchQuery = mutableStateOf(ChatSearchQuery())
    val searchOpenRevision = mutableStateOf(0)
    val retainedSearchSnapshot = mutableStateOf(snapshot)
    var searchPaneSession: ConversationSearchSession? = null
}

@Composable
internal fun ChatWorkspace(
    snapshot: ChatSnapshot,
    connection: ConnectionPhase,
    drafts: SnapshotStateMap<Long, TextFieldValue>,
    onSettings: () -> Unit,
    onSelectChat: (ChatToken, Long) -> Unit,
    onLoadChats: (ChatToken) -> Unit,
    onLoadOlder: (ChatToken) -> Unit,
    onSendText: (ChatToken, com.badnng.moe.chat.RichMessageText, Long) -> Unit,
    onRetryMessage: (ChatToken, Long) -> Unit,
    onMarkRead: (ChatToken) -> Unit,
    windowFocused: Boolean,
    features: ChatFeatureActions,
    darkTheme: Boolean,
    onDarkTheme: (Boolean) -> Unit,
    persistUiPreferences: Boolean = true,
    draftRevisions: ComposerDraftRevisions = remember(snapshot.token.generation) { ComposerDraftRevisions() },
    pageActive: Boolean = true,
    session: ChatWorkspaceSession = remember(snapshot.token.generation) { ChatWorkspaceSession(snapshot) },
    settingsFolderPreferences: ChatFolderPreferences? = null,
    onSettingsFolderPreferences: ((ChatFolderPreferences) -> Unit)? = null,
    onLockNow: (() -> Unit)? = null,
    onOwnedDarkTheme: ((Boolean, () -> Boolean) -> Unit)? = null,
) {
    fun updateDraft(chatId: Long, value: TextFieldValue) {
        draftRevisions.changed(chatId, drafts[chatId], value)
        drafts[chatId] = value
    }
    // This switch affects only the narrow layout, never the selected TDLib chat.
    var showConversation by session.showConversation
    var requestedConversationChatId by session.requestedConversationChatId
    var requestedNavigationFailureRevision by session.requestedNavigationFailureRevision
    var retainedConversationSnapshot by session.retainedConversationSnapshot
    val conversationReady = showConversation && requestedConversationChatId != null &&
        snapshot.selected?.id == requestedConversationChatId && snapshot.token.chatId == requestedConversationChatId
    var previousSelectedChatId by session.previousSelectedChatId
    val conversationMemory = session.conversationMemory
    LaunchedEffect(snapshot.token) {
        val selectionWasClosed = previousSelectedChatId != null && snapshot.token.chatId == null && snapshot.selected == null
        previousSelectedChatId = snapshot.token.chatId
        if (selectionWasClosed) {
            requestedConversationChatId = null
            showConversation = false
        }
    }
    LaunchedEffect(snapshot.navigationFailureRevision) {
        if (snapshot.navigationFailureRevision > requestedNavigationFailureRevision) {
            conversationMemory.cancelRequestedPositions()
        }
        if (snapshot.navigationFailureRevision > requestedNavigationFailureRevision && showConversation && !conversationReady) {
            requestedConversationChatId = snapshot.selected?.id
            showConversation = snapshot.selected != null
        }
        requestedNavigationFailureRevision = snapshot.navigationFailureRevision
    }
    SideEffect { if (conversationReady) retainedConversationSnapshot = snapshot }
    val chatListPaneState = remember(session, snapshot.activeList) { session.chatListPaneState.apply { selectList(snapshot.activeList) } }
    val requestConversation: (Long, () -> Unit) -> Unit = { chatId, request ->
        requestedNavigationFailureRevision = snapshot.navigationFailureRevision
        conversationMemory.prepareEntry(chatId)
        requestedConversationChatId = chatId
        showConversation = true
        request()
    }
    val requestTargetConversation: (Long, () -> Unit) -> Unit = { chatId, request ->
        requestedNavigationFailureRevision = snapshot.navigationFailureRevision
        conversationMemory.prepareEntry(chatId, restore = false)
        requestedConversationChatId = chatId
        showConversation = true
        request()
    }
    var manageFolders by remember(snapshot.token.generation) { mutableStateOf(false) }
    var localFolderPreferences by remember { mutableStateOf(if (persistUiPreferences && settingsFolderPreferences == null) loadFolderPreferences() else ChatFolderPreferences()) }
    val folderPreferences = settingsFolderPreferences ?: localFolderPreferences
    val accountMenuVisibility = remember(snapshot.token.generation) { ThemeOwnedFlag() }
    var accountMenuOpen by accountMenuVisibility::value
    val currentThemePageActive = rememberUpdatedState(pageActive)
    val accountThemeLease = remember(snapshot.token.generation, pageActive) { ThemePageLease() }
    DisposableEffect(accountThemeLease) { onDispose { accountThemeLease.close() } }
    val animatedAccountMenuProgress by animateFloatAsState(if (accountMenuOpen) 1f else 0f,
        tween(240, easing = FastOutSlowInEasing), label = "account menu and backdrop")
    val accountMenuProgress = LocalAccountMenuPreviewProgress.current ?: animatedAccountMenuProgress
    val contextMenuBackdrop = remember(snapshot.token.generation) { ContextMenuBackdropState() }
    val workspaceMenuBackdrop = rememberChatBackdrop()
    val contextMenuProgressState = animateFloatAsState(if (contextMenuBackdrop.hasExpandedBlurOwners) 1f else 0f,
        tween(180, easing = FastOutSlowInEasing), label = "context menu backdrop")
    val contextMenuProgress by contextMenuProgressState
    val contextMenuBlurProgress = remember(contextMenuProgressState) { { contextMenuProgressState.value } }
    val workspaceBlurProgress = maxOf(accountMenuProgress, contextMenuProgress)
    var showProfile by remember(snapshot.token.generation) { mutableStateOf(false) }
    var showPhone by remember(snapshot.token.generation) { mutableStateOf(false) }
    var searchOpen by session.searchOpen
    var searchReturnToConversation by session.searchReturnToConversation
    var initialSearchQuery by session.initialSearchQuery
    var searchOpenRevision by session.searchOpenRevision
    var retainedSearchSnapshot by session.retainedSearchSnapshot
    SideEffect { if (searchOpen) retainedSearchSnapshot = snapshot }
    var infoOpen by remember(snapshot.token.generation) { mutableStateOf(false) }
    var infoPaneWidth by remember(snapshot.token.generation) { mutableStateOf(0.dp) }
    var adminsOpen by remember(snapshot.token) { mutableStateOf(false) }
    var adminsMounted by remember(snapshot.token) { mutableStateOf(false) }
    var adminsFromInfo by remember(snapshot.token) { mutableStateOf(false) }
    var avatarBounds by remember { mutableStateOf(Rect.Zero) }
    var menuAnchor by remember { mutableStateOf<Rect?>(null) }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var requestedListWidth by session.requestedListWidth
    LaunchedEffect(pageActive) {
        if (!pageActive) {
            accountMenuOpen = false
            adminsOpen = false
            showProfile = false
            manageFolders = false
        }
    }
    val onAccountBounds: (Rect) -> Unit = { avatarBounds = it }
    val onAccount: () -> Unit = {
        if (!accountMenuOpen) menuAnchor = avatarBounds.translate(-rootOrigin)
        accountMenuOpen = !accountMenuOpen
    }
    LaunchedEffect(accountMenuProgress, accountMenuOpen) {
        if (!accountMenuOpen && accountMenuProgress == 0f) menuAnchor = null
    }
    CompositionLocalProvider(LocalContextMenuBackdrop provides contextMenuBackdrop,
        LocalContextMenuBlurProgress provides contextMenuBlurProgress,
        LocalWorkspaceMenuBackdrop provides workspaceMenuBackdrop) {
    AccountAvatarPhotos(snapshot) {
    ClipboardPasteHost(snapshot, features,
        composerDraft = { token -> token.chatId?.let { drafts[it] } ?: TextFieldValue() },
        composerDraftRevision = { token -> token.chatId?.let(draftRevisions::revision) ?: 0L },
        onComposerDraftAccepted = { transfer ->
            transfer.token.chatId?.let { chatId ->
                if (transfer.canConsume(snapshot.token, drafts[chatId] ?: TextFieldValue(), draftRevisions.revision(chatId))) {
                    draftRevisions.changed(chatId, drafts[chatId], TextFieldValue())
                    drafts.remove(chatId)
                }
            }
        }) {
    Scaffold(Modifier.fillMaxSize(), contentWindowInsets = WindowInsets(0, 0, 0, 0)) {
    BoxWithConstraints(Modifier.fillMaxSize().onSizeChanged { viewport = it }.onGloballyPositioned { rootOrigin = it.boundsInWindow().topLeft }) {
        val density = LocalDensity.current
        val workspaceWidth = with(density) { constraints.maxWidth.toDp() }
        val workspaceHeight = with(density) { constraints.maxHeight.toDp() }
        val layout = desktopChatLayout(workspaceWidth, workspaceHeight)
        val wide = layout.threeColumns
        val navigation = rememberWorkspaceConversationMotion(conversationReady, wide)
        val listInteractive = pageActive && navigation.listInteractive
        val conversationInteractive = pageActive && navigation.conversationInteractive && !(showConversation && !conversationReady)
        LaunchedEffect(conversationInteractive) {
            if (!conversationInteractive) {
                infoOpen = false
                adminsOpen = false
            }
        }
        val listVisible = wide || !conversationReady
        val railVisible = listVisible && layout.showRail && folderPreferences.placement == FolderTabPlacement.LEFT
        val accountAvatarAvailable = pageActive && navigation.listMounted &&
            (layout.showRail && folderPreferences.placement == FolderTabPlacement.LEFT || !searchOpen)
        LaunchedEffect(accountAvatarAvailable, navigation.listInteractive) {
            if (!accountAvatarAvailable || !navigation.listInteractive) accountMenuOpen = false
        }
        val maximumListWidth = maximumChatListWidth(workspaceWidth, if (railVisible) layout.railWidth else 0.dp,
            if (wide && snapshot.selected != null) infoPaneWidth else 0.dp)
        val currentListWidth = constrainedChatListWidth(requestedListWidth ?: layout.listWidth, maximumListWidth)
        val collapsedList = wide && currentListWidth < CollapsedChatListThreshold
        val setListWidth: (Dp) -> Unit = {
            requestedListWidth = constrainedChatListWidth(it, maximumListWidth)
            if (searchOpen && it < CollapsedChatListThreshold) {
                searchOpen = false
                features.closeSearch(snapshot.token)
            }
        }
        val selectList: (ChatListRef) -> Unit = { searchOpen = false; features.closeSearch(snapshot.token); features.selectList(snapshot.token, it) }
        val openSearchQuery: (ChatSearchQuery) -> Unit = { query ->
            if (wide && currentListWidth < 260.dp) setListWidth(260.dp)
            searchReturnToConversation = query.chatId != null && snapshot.selected != null && (wide || showConversation)
            initialSearchQuery = query
            searchOpenRevision++
            session.searchPaneSession = ConversationSearchSession(query, query == snapshot.search.query)
            searchOpen = true
            if (!wide) showConversation = false
            features.search(snapshot.token, initialSearchQuery, false)
        }
        val openSearch: (Long?) -> Unit = { openSearchQuery(ChatSearchQuery(chatId = it)) }
        val openInfo: () -> Unit = { infoOpen = !infoOpen }
        val openAdmins: (Boolean) -> Unit = { fromInfo ->
            adminsFromInfo = fromInfo
            adminsMounted = true
            adminsOpen = true
            features.loadChatDetails(snapshot.token)
        }
        val adminsPopup: @Composable (Boolean) -> Unit = { fromInfo ->
            if (adminsFromInfo == fromInfo) ConversationAdminsPopup(
                show = adminsOpen && conversationInteractive, snapshot = snapshot, features = features,
                onDismissRequest = { if (adminsFromInfo == fromInfo) adminsOpen = false },
                onDismissFinished = { if (adminsFromInfo == fromInfo && !adminsOpen) adminsMounted = false })
        }
        LaunchedEffect(infoOpen, snapshot.token, conversationInteractive) {
            if (infoOpen && conversationInteractive) features.loadChatDetails(snapshot.token)
        }
        CompositionLocalProvider(LocalDesktopChatLayout provides layout) {
            Column(Modifier.fillMaxSize().focusProperties {
                canFocus = accountMenuProgress == 0f && !contextMenuBackdrop.hasExpandedBlurOwners
            }
                .onPreviewKeyEvent {
                    if (accountMenuProgress > 0f) {
                        if (it.key == Key.Escape && it.type == KeyEventType.KeyUp) accountMenuOpen = false
                        true
                    } else if (!wide && showConversation && !conversationReady &&
                        it.key == Key.Escape && it.type == KeyEventType.KeyUp) {
                        showConversation = false
                        true
                    } else false
                }.graphicsLayer {
                clip = true
                val radius = 14.dp.toPx() * workspaceBlurProgress
                renderEffect = if (radius > 0.01f) BlurEffect(radius, radius) else null
            }.then(if (workspaceMenuBackdrop != null) Modifier.layerBackdrop(workspaceMenuBackdrop) else Modifier)) {
                val listContent: @Composable (Modifier) -> Unit = { modifier ->
                    Row(modifier.workspacePageInput(listInteractive)) {
                        // The rail belongs to the departing list, including compact widths with a rail.
                        val mountedRail = layout.showRail && folderPreferences.placement == FolderTabPlacement.LEFT
                        if (mountedRail) {
                            ChatFolderRail(snapshot, onSelect = selectList,
                                modifier = Modifier.width(layout.railWidth).fillMaxHeight(),
                                features = features, accountMenuOpen = accountMenuOpen,
                                onAccountBounds = { if (navigation.listInteractive) onAccountBounds(it) },
                                onAccount = onAccount, preferences = folderPreferences, hideAccountAvatar = true)
                        }
                        WorkspaceSearchTransition(searchOpen, Modifier.weight(1f).fillMaxHeight()) { searching, interactive ->
                            if (searching) key(searchOpenRevision) {
                                ConversationSearchPane(if (searchOpen) snapshot else retainedSearchSnapshot, features,
                                    onSelectChat = { chatId -> requestConversation(chatId) { features.selectSearchChat(snapshot.token, chatId) } },
                                    onJump = { message -> requestTargetConversation(message.chatId) {
                                        features.openSearchMessage(snapshot.token, message.chatId, message.id)
                                    } },
                                    onClose = {
                                        searchOpen = false
                                        features.closeSearch(snapshot.token)
                                        if (!wide && searchReturnToConversation) snapshot.selected?.let {
                                            requestedConversationChatId = it.id
                                            showConversation = true
                                        }
                                    }, modifier = Modifier.fillMaxSize(), initialQuery = initialSearchQuery,
                                    interactive = interactive && listInteractive,
                                    session = checkNotNull(session.searchPaneSession))
                            } else {
                                val listSnapshot = snapshot
                                ChatListPane(listSnapshot,
                                    onSelect = { chatId -> requestConversation(chatId) { onSelectChat(listSnapshot.token, chatId) } },
                                    onRetryLoad = { onLoadChats(snapshot.token) },
                                    onSelectList = selectList, onManageFolders = { manageFolders = true },
                                    leadingContent = if (!mountedRail) ({
                                        AccountAvatarButton(snapshot, features, accountMenuOpen,
                                            onBounds = { if (navigation.listInteractive && listSnapshot.activeList == snapshot.activeList) onAccountBounds(it) },
                                            onClick = onAccount, hideContent = true)
                                    }) else null,
                                    modifier = Modifier.fillMaxSize(), collapsed = collapsedList, state = chatListPaneState,
                                    pageInteractive = listInteractive,
                                    features = features, onSearch = { openSearch(null) }, onLockNow = onLockNow,
                                    folderTabs = if (folderPreferences.placement == FolderTabPlacement.TOP) ({
                                        ChatFolderTopTabs(listSnapshot, selectList, folderPreferences,
                                            state = chatListPaneState.topTabsState)
                                    }) else null)
                            }
                        }
                    }
                }
                val conversationContent: @Composable (Modifier) -> Unit = { modifier ->
                    val paneSnapshot = if (wide || conversationReady) snapshot else retainedConversationSnapshot
                    val selectedChat = paneSnapshot.selected
                    Box(modifier.workspacePageInput(conversationInteractive)) {
                        if (selectedChat == null) {
                            Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
                                ConversationWallpaper(Modifier.matchParentSize())
                            }
                        } else ConversationLatestTransition(paneSnapshot, features.returnToLatest) { shownSnapshot, interactive, returnToLatest ->
                            key(shownSnapshot.token.generation, shownSnapshot.token.selection, selectedChat.id) {
                                ConversationPane(shownSnapshot, shownSnapshot.selected ?: selectedChat, drafts[selectedChat.id] ?: TextFieldValue(),
                                    onDraft = { updateDraft(selectedChat.id, it) },
                                    onBack = if (wide) null else { { showConversation = false } },
                                    onLoadOlder = { onLoadOlder(shownSnapshot.token) },
                                    onSend = {
                                        val content = (drafts[selectedChat.id] ?: TextFieldValue()).richText()
                                        draftRevisions.submitted(shownSnapshot.token, content, snapshot.acceptedSendId)
                                        onSendText(shownSnapshot.token, content, draftRevisions.revision(selectedChat.id))
                                    },
                                    onRetry = { onRetryMessage(shownSnapshot.token, it) },
                                    onMarkRead = { onMarkRead(shownSnapshot.token) },
                                    features = features, pageInteractive = conversationInteractive && interactive,
                                    active = conversationInteractive && interactive && windowFocused &&
                                        !manageFolders && workspaceBlurProgress == 0f && !LocalClipboardPasteActive.current &&
                                        !contextMenuBackdrop.hasMountedMenus && !showProfile && !adminsOpen && !adminsMounted && snapshot.folderEditorId == null,
                                    onSearch = { openSearch(selectedChat.id) }, onAdmins = { openAdmins(false) },
                                    onSearchSender = { openSearchQuery(ChatSearchQuery(chatId = selectedChat.id, senderId = it)) },
                                    onInfo = openInfo, showInfoButton = wide,
                                    modifier = Modifier.fillMaxSize(), onReturnToLatest = returnToLatest,
                                    draftRevision = draftRevisions.revision(selectedChat.id),
                                    readDraft = { drafts[selectedChat.id] ?: TextFieldValue() },
                                    readDraftRevision = { draftRevisions.revision(selectedChat.id) },
                                    sessionMemory = conversationMemory,
                                    onOpenReply = { source, position ->
                                        source.replyTo?.let { reply ->
                                            position?.let {
                                                features.rememberHistoryAnchor(shownSnapshot.token, it.messageId)
                                                conversationMemory.pushReplyReturn(reply.chatId, it)
                                            }
                                            requestTargetConversation(reply.chatId) {
                                                features.openReplyMessage(shownSnapshot.token, source.id)
                                            }
                                        }
                                    },
                                    onReplyReturn = {
                                        conversationMemory.beginReplyReturn(shownSnapshot.token.chatId, shownSnapshot.jumpRevision)?.let { position ->
                                            requestTargetConversation(position.chatId) {
                                                features.openReplyReturn(shownSnapshot.token, position.chatId, position.messageId)
                                            }
                                        }
                                    },
                                    adminsPopup = { adminsPopup(false) })
                            }
                        }
                    }
                }
                if (wide) Row(Modifier.fillMaxWidth().weight(1f)) {
                    val railWidth = if (railVisible) layout.railWidth else 0.dp
                    listContent(Modifier.width(currentListWidth + railWidth).fillMaxHeight())
                    ChatListDivider(currentListWidth, maximumListWidth,
                        onDelta = { delta -> setListWidth(constrainedChatListWidth(requestedListWidth ?: currentListWidth, maximumListWidth) + delta) },
                        onSetWidth = setListWidth)
                    conversationContent(Modifier.weight(1f).fillMaxHeight())
                    snapshot.selected?.let { selectedChat ->
                        ConversationInfoTransition(infoOpen && conversationInteractive, onWidthChanged = { infoPaneWidth = it }) {
                            ConversationInfoPane(selectedChat, snapshot, features,
                                onJump = {}, onAdmins = { openAdmins(true) }, onClose = { infoOpen = false },
                                modifier = Modifier.fillMaxSize(), adminsPopup = { adminsPopup(true) })
                        }
                    }
                } else Box(Modifier.fillMaxWidth().weight(1f).graphicsLayer { clip = true }.workspaceNavigationShade(navigation)) {
                    if (navigation.listMounted) listContent(Modifier.fillMaxSize().workspaceListMotion(navigation))
                    if (navigation.conversationMounted) conversationContent(Modifier.fillMaxSize().workspaceConversationMotion(navigation))
                }
            }
            MessageMenuClearSourceOverlay()
            if (accountMenuProgress > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.10f * accountMenuProgress)))
            if (accountMenuProgress > 0f && avatarBounds != Rect.Zero && viewport != IntSize.Zero) {
                val selfChat = snapshot.account?.let { account -> snapshot.knownChats.firstOrNull { it.privateUserId == account.userId } }
                AccountMenuOverlay(snapshot, features, connection, menuAnchor ?: avatarBounds.translate(-rootOrigin), viewport,
                    darkTheme, { dark ->
                        val revision = accountMenuVisibility.revision
                        val menuOwner = { accountThemeLease.alive && currentThemePageActive.value && accountMenuVisibility.value &&
                            accountMenuVisibility.revision == revision }
                        if (onOwnedDarkTheme != null) onOwnedDarkTheme(dark, menuOwner) else onDarkTheme(dark)
                    }, showPhone, { showPhone = it },
                    onProfile = { accountMenuOpen = false; showProfile = true },
                    onSavedMessages = selfChat?.let { chat -> ({
                        accountMenuOpen = false
                        requestConversation(chat.id) { onSelectChat(snapshot.token, chat.id) }
                    }) },
                    onArchive = {
                        accountMenuOpen = false
                        selectList(ChatListRef(-1))
                    },
                    onManageFolders = { accountMenuOpen = false; manageFolders = true },
                    onSettings = { accountMenuOpen = false; onSettings() },
                    onDismiss = { accountMenuOpen = false }, progress = accountMenuProgress,
                    interactive = pageActive && accountMenuOpen, showAvatar = false)
            }
            if (accountAvatarAvailable && avatarBounds != Rect.Zero) {
                // One persistent photo/interaction instance stays above blur, dimming and the
                // menu backdrop. Opening/closing only changes its pressed state, never its decoder.
                val triggerAnchor = menuAnchor ?: avatarBounds.translate(-rootOrigin)
                AccountAvatarButton(snapshot, features, accountMenuOpen, onBounds = {}, onClick = onAccount,
                    modifier = Modifier.offset { IntOffset(triggerAnchor.left.roundToInt(), triggerAnchor.top.roundToInt()) }
                        .workspaceListMotion(navigation).workspacePageInput(listInteractive)
                        .graphicsLayer {
                            val radius = 14.dp.toPx() * contextMenuProgress
                            renderEffect = if (radius > 0.01f) BlurEffect(radius, radius) else null
                        })
            }
            WindowDialog(infoOpen && conversationInteractive && !wide && snapshot.selected != null, title = "会话信息", maxWidth = 420.dp,
                onDismissRequest = { infoOpen = false }) {
                snapshot.selected?.let { selectedChat ->
                    ConversationInfoContent(infoOpen && !wide) {
                        ConversationInfoPane(selectedChat, snapshot, features,
                            onJump = { infoOpen = false }, onAdmins = { openAdmins(true) },
                            onClose = { infoOpen = false }, modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp),
                            adminsPopup = { adminsPopup(true) })
                    }
                }
            }
        }
    }
    }
    AccountProfileDialog(pageActive && showProfile && snapshot.account != null, snapshot, features, showPhone,
        onDismiss = { showProfile = false })
    ChatFolderManager(pageActive && manageFolders, snapshot, features,
        onDismiss = { manageFolders = false; features.closeFolderEditor(snapshot.token) },
        preferences = folderPreferences, onPreferencesChange = {
            if (onSettingsFolderPreferences != null) onSettingsFolderPreferences(it)
            else if (!persistUiPreferences || saveFolderPreferences(it)) localFolderPreferences = it
        })
    }
    }
    }
}

@Composable
internal fun ChatErrorText(error: ChatError) {
    Text(when (error) {
        ChatError.REQUEST_FAILED -> "请求未完成，请检查连接后重试。"
        ChatError.FORBIDDEN -> "当前没有执行此操作的权限。"
        ChatError.RATE_LIMITED -> "操作过于频繁，请稍后再试。"
        ChatError.TEXT_TOO_LONG -> "内容过长，请缩短后重试。"
        ChatError.EMPTY_TEXT -> "请先填写内容。"
        ChatError.INVALID_QUOTE -> "引文已变化或超过 Telegram 长度限制，请重新选择引用片段。"
        ChatError.UNSUPPORTED -> "此操作暂不支持，请使用官方客户端处理。"
        ChatError.NOT_READY -> "聊天暂未就绪，请等待连接与会话准备完成。"
    }, fontSize = 12.sp, color = MiuixTheme.colorScheme.error)
}

@Composable
internal fun ChatButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    Button(onClick = onClick, modifier = modifier, enabled = enabled,
        colors = if (primary) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(),
        cornerRadius = 15.dp, minHeight = 35.dp, minWidth = 0.dp, insideMargin = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
        Text(text, fontSize = 12.sp)
    }
}

@Composable
internal fun Modifier.chatSurface(): Modifier = chatSquircleBackground(MiuixTheme.colorScheme.surfaceContainer, 20.dp)

internal fun messagePreview(message: ChatMessage): String = buildString {
    if (message.delivery == MessageDelivery.PENDING) append("[等待发送] ")
    if (message.delivery == MessageDelivery.FAILED) append("[发送失败] ")
    if (!message.kind.isTextContent) append(message.kind.label()).append(' ')
    append(message.text)
}.ifBlank { "消息" }

internal fun MessageKind.label(): String = when (this) {
    MessageKind.TEXT -> "文字"
    MessageKind.ANIMATED_EMOJI -> "[表情]"
    MessageKind.PHOTO -> "[图片]"
    MessageKind.VIDEO -> "[视频]"
    MessageKind.ANIMATION -> "[GIF]"
    MessageKind.FILE -> "[文件]"
    MessageKind.VOICE -> "[语音]"
    MessageKind.STICKER -> "[贴纸]"
    MessageKind.SERVICE -> "[服务消息]"
    MessageKind.OTHER -> "[其他消息]"
}

internal fun messageTime(epochSeconds: Int): String = if (epochSeconds <= 0) "" else
    Instant.fromEpochSeconds(epochSeconds.toLong()).toString().take(16).replace('T', ' ') + " UTC"

internal fun ChatSnapshot.listTitle(): String = when (activeList.folderId) {
    0 -> "所有聊天"
    -1 -> "已归档"
    else -> folders.find { it.id == activeList.folderId }?.name ?: "聊天分类"
}
