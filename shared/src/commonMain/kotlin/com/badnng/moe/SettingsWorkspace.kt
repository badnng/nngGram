package com.badnng.moe

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.settings.*
import com.badnng.moe.status.RuntimeSnapshot
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal enum class SettingsDestination(val title: String, val section: SettingsSection? = null) {
    PROFILE("我的账号", SettingsSection.PROFILE), NOTIFICATIONS("通知与声音", SettingsSection.NOTIFICATIONS),
    PRIVACY("隐私与安全", SettingsSection.PRIVACY), CHAT("聊天设置"), FOLDERS("文件夹"),
    ADVANCED("高级设置", SettingsSection.ADVANCED), DEVICES("扬声器和摄像头"),
    ENHANCED("Enhanced Settings"), POWER("电池与动画"), LANGUAGE("语言"), HELP("帮助"),
}

/** Retained by App across settings/chat navigation; contains no persistent account content. */
internal class SettingsWorkspaceState {
    val proxyChecksOwner = SettingsProxyChecksPageOwner()
    val storageOwner = SettingsStoragePageOwner()
    var proxyNavigationRevision by mutableLongStateOf(0L)
        private set
    private var destinationValue by mutableStateOf(SettingsDestination.PROFILE)
    private var detailOpenValue by mutableStateOf(false)
    var localLockOpen by mutableStateOf(false)
    var themeNavigationRevision by mutableLongStateOf(0L)
        private set
    var destination: SettingsDestination
        get() = destinationValue
        set(value) { if (destinationValue != value) {
            revokeProxyChecks()
            localLockOpen = false
            destinationValue = value
            themeNavigationRevision++
        } }
    var detailOpen: Boolean
        get() = detailOpenValue
        set(value) { if (detailOpenValue != value) {
            if (!value) {
                revokeProxyChecks()
                localLockOpen = false
            }
            detailOpenValue = value
            themeNavigationRevision++
        } }
    fun revokeProxyChecks() {
        proxyChecksOwner.close()
        storageOwner.invalidate()
        // A close and reopen can be coalesced before Compose observes inactive state.
        // Change the lease key even when the same page and parent token remain visible.
        proxyNavigationRevision++
    }
    val categoryScroll = ScrollState(0)
    val scroll = SettingsDestination.entries.associateWith { ScrollState(0) }
    val folderSession = FolderSettingsSession()
    val autoMediaDraftSession = AutoMediaEditorDraftSession()
    val storagePolicyUiSession = SettingsStoragePolicyUiSession()
    val fontEditor = SettingsFontEditorSession()
}

internal val LocalSettingsPageActive = staticCompositionLocalOf { true }
/** Unclipped size of the settings detail scroller, independent of its scroll offset. */
internal val LocalSettingsDetailViewportSize = compositionLocalOf<IntSize?> { null }

@Composable
internal fun SettingsWorkspace(
    runtime: RuntimeSnapshot,
    actions: SettingsActions,
    features: ChatFeatureActions,
    preferences: SettingsPreferences,
    onPreferences: (SettingsPreferences) -> Unit,
    folderPreferences: ChatFolderPreferences,
    onFolderPreferences: (ChatFolderPreferences) -> Unit,
    onClose: () -> Unit,
    onOpenConfig: () -> Unit,
    state: SettingsWorkspaceState,
    active: Boolean = true,
    preferenceError: Boolean = false,
    savedState: androidx.compose.runtime.saveable.SaveableStateHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder(),
    onSaveFont: (SettingsChatFont) -> Boolean = { false },
    onSupportOpened: (com.badnng.moe.chat.ChatToken) -> Unit = {},
    themeCoordinator: SettingsThemeCoordinator? = null,
    themeParentRevision: Long = 0L,
    themeParentCurrent: () -> Boolean = { true },
    onSaveTheme: ((SettingsThemePreferences) -> Boolean)? = null,
    onSaveDeviceName: (String, () -> Boolean, (Boolean) -> Unit) -> Unit = { _, _, result -> result(false) },
    runtimeContent: @Composable () -> Unit = {},
) {
    val snapshot = runtime.settings
    val currentPreferences = rememberUpdatedState(preferences)
    val currentUpdate = rememberUpdatedState(onPreferences)
    val currentThemeParent = rememberUpdatedState(themeParentCurrent)
    val theme = if (themeCoordinator != null) themeCoordinator else {
        val observed = LocalSettingsSystemThemeObservation.current.current()
        val local = remember(state, runtime.generation) { SettingsThemeCoordinator(preferences.themeStatePreferences(), observed) }
        SideEffect { local.observe(observed) }
        local
    }
    val saveTheme: (SettingsThemePreferences) -> Boolean = onSaveTheme ?: { value ->
        currentUpdate.value(currentPreferences.value.withThemeState(value)); true
    }
    val themeNavRevision = state.themeNavigationRevision
    val themeLease = remember(state, runtime.generation, active, themeParentRevision) { ThemePageLease() }
    DisposableEffect(themeLease) { onDispose { themeLease.close() } }
    // Native security tokens are actor-owned; the lease also revokes synchronously when
    // this workspace's own back action starts, before the next native snapshot arrives.
    val securityLease = remember(snapshot.security.token, active) { ThemePageLease() }
    DisposableEffect(securityLease) { onDispose { securityLease.close() } }
    val latestSecurity = rememberUpdatedState(snapshot.security)
    val latestDeviceNameSave = rememberUpdatedState(onSaveDeviceName)
    val nativeDeviceNameTarget = snapshot.security.sessions.singleOrNull { it.isCurrent }?.copy(lastActiveDate = 0)
    val nativeDeviceNameOwner = remember(snapshot.security.token, nativeDeviceNameTarget, snapshot.security.loading,
        snapshot.security.busy, snapshot.security.sessionsRefreshRequired, active) {
        nativeDeviceNameTarget?.let { actions.captureDeviceNameSecurityOwner(snapshot.security.token, it) } ?: { false }
    }
    val workspaceFocus = remember { FocusRequester() }
    var workspaceHasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(active, workspaceHasFocus) {
        if (active && !workspaceHasFocus) workspaceFocus.requestFocus()
    }
    SideEffect { state.autoMediaDraftSession.bindAccount(runtime.chats.autoMediaSettings.accountUserId) }
    val destination = state.destination
    val localLockOpen = destination == SettingsDestination.PRIVACY && state.localLockOpen
    val securityOpen = destination == SettingsDestination.PRIVACY && snapshot.security.section != null
    val exceptions = snapshot.notificationExceptions
    val exceptionsOpen = destination == SettingsDestination.NOTIFICATIONS && exceptions.opened
    val exceptionScrolls = remember(exceptions.token) {
        NotificationExceptionsView.entries.associateWith { ScrollState(0) }
    }
    val detailScroll = if (exceptionsOpen) exceptionScrolls.getValue(exceptions.view) else state.scroll.getValue(destination)
    LaunchedEffect(exceptions.token, exceptions.editor?.chatId) {
        exceptionScrolls.getValue(NotificationExceptionsView.EDITOR).scrollTo(0)
    }
    val closeWorkspace = { state.localLockOpen = false; state.revokeProxyChecks(); onClose() }
    val back = {
        if (localLockOpen) { actions.localLock.cancel(); actions.localLock.closeManagement(); state.localLockOpen = false }
        else if (securityOpen) { securityLease.close(); actions.closeSecurity(snapshot.security.token) }
        else if (exceptionsOpen) {
            if (exceptions.saving || exceptions.view == NotificationExceptionsView.LIST)
                actions.notificationExceptions.close(exceptions.token)
            else actions.notificationExceptions.back(exceptions.token)
        }
        else if (state.detailOpen) state.detailOpen = false
        else closeWorkspace()
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)
        .workspacePageInput(active)) {
        // SubcomposeMeasureScope mutates its density in place. Read the composition
        // density directly so unchanged physical constraints still update dp breakpoints.
        val density = LocalDensity.current
        var detailViewportSize by remember { mutableStateOf(IntSize.Zero) }
        val workspaceWidth = with(density) { constraints.maxWidth.toDp() }
        val twoColumns = workspaceWidth >= 760.dp
        val navigation = rememberWorkspaceConversationMotion(state.detailOpen, twoColumns)
        val detailActive = active && navigation.conversationInteractive
        // Capture the concrete visit for both refresh controls. A retained callback must
        // not discover a later page merely because it has the same parent settings token.
        val proxyChecksPage = state.proxyChecksOwner.current
        LaunchedEffect(snapshot.token.generation, snapshot.enabled, destination, detailActive) {
            destination.section?.let { section ->
                if (section != SettingsSection.ADVANCED && detailActive && snapshot.enabled &&
                    section !in snapshot.loaded && section !in snapshot.loading)
                    actions.load(snapshot.token, section)
            }
        }
        // Keep the detail subtree at the same composition position while resizing. Moving
        // it between Row/AnimatedContent branches would discard an unsubmitted form.
        val showCategories = navigation.listMounted
        val showDetail = navigation.conversationMounted
        val categoryWidth = if (twoColumns) (workspaceWidth * .28f).coerceIn(236.dp, 304.dp) else workspaceWidth
        AccountAvatarPhotos(runtime.chats) {
            Box(Modifier.fillMaxSize().onPreviewKeyEvent {
                if (it.key == Key.Escape && it.type == KeyEventType.KeyUp) {
                    if (localLockOpen || securityOpen || exceptionsOpen) back()
                    else if (twoColumns) closeWorkspace() else back()
                    true
                } else false
            }.focusRequester(workspaceFocus).onFocusChanged { workspaceHasFocus = it.hasFocus }.focusable(enabled = active)) {
                if (showCategories) Column(Modifier.width(categoryWidth).fillMaxHeight()
                    .workspaceListMotion(navigation).workspacePageInput(active && navigation.listInteractive)
                    .background(MiuixTheme.colorScheme.surfaceContainer)) {
                    SettingsHeader("设置", "返回聊天", closeWorkspace)
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                    Column(Modifier.fillMaxSize().verticalScroll(state.categoryScroll)
                        .padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SettingsAccountSummary(runtime.chats, features) {
                            state.destination = SettingsDestination.PROFILE
                            state.detailOpen = true
                        }
                        Spacer(Modifier.height(10.dp))
                        SettingsDestination.entries.forEach { item ->
                            val selected = twoColumns && destination == item
                            Row(Modifier.fillMaxWidth()
                                .chatSquircleSurface(if (selected) MiuixTheme.colorScheme.secondaryContainer else Color.Transparent, 15.dp)
                                .selectable(selected = selected, role = Role.Tab) {
                                    state.destination = item
                                    state.detailOpen = true
                                }.padding(horizontal = 14.dp, vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                Icon(settingsIcon(item), null, Modifier.size(21.dp), tint = if (selected)
                                    MiuixTheme.colorScheme.onSecondaryContainer else MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                Text(item.title, Modifier.weight(1f), fontSize = 14.sp,
                                    color = if (selected) MiuixTheme.colorScheme.onSecondaryContainer else MiuixTheme.colorScheme.onSurface)
                            }
                        }
                        Spacer(Modifier.height(18.dp))
                        SettingsInterfaceScaleControl(preferences, onPreferences,
                            enabled = active && navigation.listInteractive)
                        Spacer(Modifier.height(12.dp))
                        SettingsNote("nngGram · Telegram")
                    }
                    WorkspaceScrollbar(state.categoryScroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                    }
                }
                if (twoColumns) Box(Modifier.offset(x = categoryWidth).width(1.dp).fillMaxHeight()
                    .background(MiuixTheme.colorScheme.onSurface.copy(alpha = .08f)))
                // Stable key and Box preserve the selected content on density/width changes.
                Box(Modifier.offset(x = if (twoColumns) categoryWidth + 1.dp else 0.dp)
                    .width(if (twoColumns) workspaceWidth - categoryWidth - 1.dp else workspaceWidth).fillMaxHeight()
                    .workspaceConversationMotion(navigation).workspacePageInput(detailActive)
                    .then(if (showDetail) Modifier else Modifier.settingsHidden())) {
                    CompositionLocalProvider(LocalSettingsPageActive provides detailActive) {
                    Column(Modifier.fillMaxSize()) {
                        SettingsHeader(if (localLockOpen) "本地密码" else destination.title,
                            if (localLockOpen) "返回隐私与安全" else if (twoColumns) null else if (exceptionsOpen)
                            (if (exceptions.saving || exceptions.view == NotificationExceptionsView.LIST) "返回通知设置" else "返回例外列表") else "返回设置分类",
                            if (twoColumns && !localLockOpen) null else back,
                            onRefresh = if (exceptionsOpen) {
                                if (detailActive && !exceptions.loading && !exceptions.saving && !exceptions.preparingClear) {
                                    { when (exceptions.view) {
                                        NotificationExceptionsView.LIST -> actions.notificationExceptions.refresh(exceptions.token)
                                        NotificationExceptionsView.EDITOR -> actions.notificationExceptions.reloadEditor(exceptions.token)
                                        NotificationExceptionsView.SELECTOR -> actions.notificationExceptions.search(exceptions.token, exceptions.query, false)
                                    } }
                                } else null
                            } else destination.section?.takeIf { detailActive && snapshot.enabled && !securityOpen && !localLockOpen &&
                                it !in snapshot.loading && it !in snapshot.saving }?.let { section ->
                                    {
                                        if (section == SettingsSection.ADVANCED) state.proxyChecksOwner.refresh(proxyChecksPage)
                                        else actions.load(snapshot.token, section)
                                    }
                                })
                        if (destination == SettingsDestination.FOLDERS) {
                            ChatFolderSettingsContent(runtime.chats, features, folderPreferences, onFolderPreferences,
                                Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp), session = state.folderSession)
                        } else Box(Modifier.fillMaxWidth().weight(1f).onSizeChanged { detailViewportSize = it }) {
                        CompositionLocalProvider(LocalSettingsDetailViewportSize provides detailViewportSize) {
                        Column(Modifier.fillMaxSize().verticalScroll(detailScroll)
                            .padding(horizontal = if (workspaceWidth < 440.dp) 14.dp else 24.dp, vertical = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Column(Modifier.widthIn(max = 800.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                                if (preferenceError) SettingsNote("设置未能保存到本机，请重试。", error = true)
                                destination.section?.takeUnless { localLockOpen }?.let { section ->
                                    if (section in snapshot.loading) SettingsNote("正在读取设置……")
                                    if (section in snapshot.saving) SettingsNote("正在保存……")
                                    if (snapshot.errors[section] != null) {
                                        SettingsNote(settingsErrorText(snapshot.errors.getValue(section)), error = true)
                                        SettingsAction("重新读取", enabled = section !in snapshot.loading && section !in snapshot.saving) {
                                            if (section == SettingsSection.ADVANCED) state.proxyChecksOwner.refresh(proxyChecksPage)
                                            else actions.load(snapshot.token, section)
                                        }
                                    }
                                }
                                savedState.SaveableStateProvider(destination.name) {
                                    when (destination) {
                                        SettingsDestination.PROFILE -> SettingsProfilePage(snapshot, actions, runtime.chats, features)
                                        SettingsDestination.NOTIFICATIONS -> SettingsNotificationsPage(snapshot, actions)
                                        SettingsDestination.PRIVACY -> {
                                            if (localLockOpen) {
                                                SettingsLocalLockPage(runtime.localLock, actions.localLock, detailActive,
                                                    onBack = { state.localLockOpen = false })
                                            } else {
                                            val securityToken = snapshot.security.token
                                            val parentCurrent = {
                                                securityLease.alive && themeLease.alive && active && currentThemeParent.value() &&
                                                    nativeDeviceNameOwner() &&
                                                    state.themeNavigationRevision == themeNavRevision &&
                                                    state.destination == SettingsDestination.PRIVACY &&
                                                    (twoColumns || state.detailOpen) &&
                                                    latestSecurity.value.token == securityToken &&
                                                    latestSecurity.value.section == SettingsSecuritySection.SESSIONS
                                            }
                                            CompositionLocalProvider(LocalSettingsDeviceNameHost provides SettingsDeviceNameHost(
                                                customName = preferences.customDeviceModel,
                                                current = parentCurrent,
                                                retireSecurity = securityLease::close,
                                                save = { name, current, result ->
                                                    latestDeviceNameSave.value(name, { parentCurrent() && current() }, result)
                                                },
                                            )) { SettingsPrivacyPage(snapshot, actions, runtime.localLock,
                                                onOpenLocalLock = { state.localLockOpen = true }) }
                                            if (!securityOpen) SettingsLoginEmailContent(runtime.loginEmail,
                                                actions.loginEmail, detailActive)
                                            }
                                        }
                                        SettingsDestination.CHAT -> SettingsChatPage(preferences, onPreferences, onSaveFont, state.fontEditor,
                                            theme, { themeLease.alive && active && currentThemeParent.value() &&
                                                state.themeNavigationRevision == themeNavRevision && state.destination == SettingsDestination.CHAT &&
                                                (twoColumns || state.detailOpen) }, saveTheme)
                                        SettingsDestination.ADVANCED -> {
                                            SettingsAdvancedPage(snapshot, actions, onOpenConfig,
                                                connection = runtime.status.connection, proxyChecksOwner = state.proxyChecksOwner,
                                                storageOwner = state.storageOwner,
                                                proxyNavigationRevision = state.proxyNavigationRevision) {
                                                key(runtime.generation) {
                                                    SettingsStoragePolicyControls(runtime.storagePolicySettings,
                                                        actions.storagePolicy, session = state.storagePolicyUiSession)
                                                    SettingsAutoMediaControls(runtime.chats.autoMediaSettings,
                                                        onSave = { expected, policy, admitted, completed ->
                                                            features.saveAutoMedia(runtime.chats.token, expected, policy, admitted, completed)
                                                        },
                                                        active = detailActive && runtime.chats.enabled,
                                                        hoistedDraft = state.autoMediaDraftSession)
                                                }
                                            }
                                            runtimeContent()
                                        }
                                        SettingsDestination.DEVICES -> SettingsPlaceholder("扬声器和摄像头", "通话设备、麦克风测试和摄像头预览将在后续补齐。")
                                        SettingsDestination.HELP -> SettingsHelpPage(preferences,
                                            chatToken = runtime.chats.token, support = actions.support,
                                            onSupportOpened = onSupportOpened)
                                        SettingsDestination.ENHANCED, SettingsDestination.POWER, SettingsDestination.LANGUAGE ->
                                            SettingsPlaceholder(destination.title, "此分类暂未启用。")
                                        SettingsDestination.FOLDERS -> Unit
                                    }
                                }
                            }
                        }
                        }
                        WorkspaceScrollbar(detailScroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                        }
                    }
                    }
                }
            }
        }
    }
    if (themeCoordinator == null) SettingsThemeConfirmationDialog(theme, active)
}

/** Unselected narrow detail stays composed for drafts but cannot draw, focus or receive input. */
@Composable
private fun Modifier.settingsHidden(): Modifier = workspacePageInput(false)
    .then(Modifier.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) { /* Retain composition without placing. */ }
    })

@Composable
internal fun SettingsHeader(title: String, backLabel: String? = null, onBack: (() -> Unit)? = null,
    onRefresh: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (onBack != null) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Color.Transparent)
                .clickable(role = Role.Button, onClickLabel = backLabel, onClick = onBack)
                .semantics { contentDescription = backLabel ?: "返回" }, contentAlignment = Alignment.Center) {
                Icon(MiuixIcons.Regular.Back, null, Modifier.size(22.dp), tint = MiuixTheme.colorScheme.onSurface)
            }
        }
        Text(title, Modifier.weight(1f).semantics { heading() }, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        if (onRefresh != null) SettingsActionInline("刷新", onRefresh)
    }
}

@Composable
private fun SettingsActionInline(label: String, onClick: () -> Unit) {
    Box(Modifier.chatSquircleSurface(Color.Transparent, 15.dp).clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 12.dp)) {
        Text(label, fontSize = 13.sp, color = MiuixTheme.colorScheme.primary)
    }
}

@Composable
private fun SettingsAccountSummary(snapshot: ChatSnapshot, features: ChatFeatureActions, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().chatSquircleSurface(Color.Transparent, 15.dp).clickable(onClick = onClick)
        .padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AccountAvatarButton(snapshot, features, false, onBounds = {}, onClick = onClick, modifier = Modifier.size(52.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(snapshot.account?.displayName ?: "我的账号", fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            snapshot.account?.username?.takeIf(String::isNotBlank)?.let {
                Text("@$it", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
internal fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, Modifier.padding(horizontal = 14.dp, vertical = 5.dp).semantics { heading() },
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MiuixTheme.colorScheme.primary)
        Column(Modifier.fillMaxWidth().chatSquircleBackground(MiuixTheme.colorScheme.surfaceContainer, 18.dp)
            .padding(horizontal = 6.dp, vertical = 5.dp), content = content)
    }
}

@Composable
internal fun SettingsRow(label: String, value: String? = null, summary: String? = null,
    enabled: Boolean = true, onClick: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().chatSquircleSurface(Color.Transparent, 15.dp)
        .then(if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier)
        .padding(horizontal = 12.dp, vertical = 13.dp)) {
        SettingsValueLayout(label, value, summary, enabled)
    }
}

/** A blue value centers against the complete title/description, with a small extra right inset. */
@Composable
internal fun SettingsValueLayout(label: String, value: String?, summary: String? = null, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().padding(end = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(label, Modifier.fillMaxWidth(), fontSize = 14.sp,
                color = if (enabled) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.disabledOnSurface)
            summary?.let { SettingsNote(it) }
        }
        value?.let { Text(it, Modifier.weight(.8f), fontSize = 13.sp,
            color = MiuixTheme.colorScheme.primary, textAlign = TextAlign.End, maxLines = 3, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
internal fun SettingsToggle(label: String, checked: Boolean, enabled: Boolean = true, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().chatSquircleSurface(Color.Transparent, 15.dp)
        .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
        .padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp,
            color = if (enabled) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.disabledOnSurface)
        Switch(checked, null, enabled = enabled, modifier = Modifier.clearAndSetSemantics {})
    }
}

@Composable
internal fun SettingsNote(text: String, error: Boolean = false) {
    Text(text, fontSize = 12.sp, color = if (error) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary)
}

@Composable
internal fun SettingsAction(label: String, enabled: Boolean = true, destructive: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().chatSquircleSurface(Color.Transparent, 15.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 13.dp)) {
        Text(label, fontSize = 14.sp, color = if (!enabled) MiuixTheme.colorScheme.disabledOnSurface
            else if (destructive) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.primary)
    }
}

@Composable
private fun SettingsPlaceholder(title: String, description: String) {
    SettingsGroup(title) { SettingsRow(description) }
}

private fun settingsIcon(destination: SettingsDestination): ImageVector = when (destination) {
    SettingsDestination.PROFILE -> MiuixIcons.ContactsCircle
    SettingsDestination.NOTIFICATIONS -> MiuixIcons.Regular.Messages
    SettingsDestination.PRIVACY -> MiuixIcons.Hide
    SettingsDestination.CHAT -> MiuixIcons.Theme
    SettingsDestination.FOLDERS -> MiuixIcons.Folder
    SettingsDestination.ADVANCED, SettingsDestination.ENHANCED -> MiuixIcons.Settings
    SettingsDestination.DEVICES -> MiuixIcons.Phone
    SettingsDestination.POWER -> MiuixIcons.Regular.GridView
    SettingsDestination.LANGUAGE -> MiuixIcons.Regular.Edit
    SettingsDestination.HELP -> MiuixIcons.Regular.Messages
}

internal fun settingsErrorText(error: SettingsError) = when (error) {
    SettingsError.NOT_READY -> "账号尚未就绪，请稍后重试。"
    SettingsError.REQUEST_FAILED -> "请求未完成，请检查连接后重试。"
    SettingsError.RESULT_UNKNOWN -> "请求已提交，结果尚未确认；请先刷新状态"
    SettingsError.FORBIDDEN -> "此账号无权更改该设置。"
    SettingsError.RATE_LIMITED -> "操作过于频繁，请稍后再试。"
    SettingsError.INVALID_VALUE -> "内容无效，请检查后重试。"
    SettingsError.UNSUPPORTED -> "此项设置当前不可用。"
    SettingsError.CONFLICT -> "设置已变化，当前草稿仍保留。请重新读取后再修改。"
}
