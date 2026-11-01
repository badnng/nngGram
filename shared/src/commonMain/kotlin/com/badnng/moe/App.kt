package com.badnng.moe

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badnng.moe.chat.ChatToken
import com.badnng.moe.status.*
import com.badnng.moe.settings.SettingsActions
import com.badnng.moe.notifications.DeviceNotificationsSnapshot
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

data class RuntimeInfo(val platform: String = "Desktop / JVM", val javaVersion: String = "预览环境")

/** Lets the desktop host keep its window chrome in sync with the in-app theme choice. */
val LocalAppThemeObserver = staticCompositionLocalOf<(Boolean) -> Unit> { {} }

@Composable
fun App(
    runtimeInfo: RuntimeInfo = RuntimeInfo(),
    runtime: RuntimeSnapshot? = null,
    onStart: () -> Unit = {},
    onOpenConfig: () -> Unit = {},
    configurationOpenError: String? = null,
    onStop: () -> Unit = {},
    onRestart: () -> Unit = {},
    exitRequested: Boolean = false,
    onContinueExit: () -> Unit = {},
    onSubmitLogin: (LoginToken, LoginRequestKind, String) -> Unit = { _, _, _ -> },
    onSelectChat: (ChatToken, Long) -> Unit = { _, _ -> },
    onLoadChats: (ChatToken) -> Unit = {},
    onLoadOlder: (ChatToken) -> Unit = {},
    onSendText: (ChatToken, com.badnng.moe.chat.RichMessageText, Long) -> Unit = { _, _, _ -> },
    onRetryMessage: (ChatToken, Long) -> Unit = { _, _ -> },
    onMarkRead: (ChatToken) -> Unit = {},
    windowFocused: Boolean = false,
    features: ChatFeatureActions = ChatFeatureActions(),
    initialTheme: ColorSchemeMode = ColorSchemeMode.System,
    persistUiPreferences: Boolean = true,
    settingsActions: SettingsActions = SettingsActions(),
    onExit: () -> Unit = {},
    onCancelQr: (LoginToken) -> Unit = {},
    profileEmojiArtwork: com.badnng.moe.media.ProfileEmojiArtworkProvider? = null,
    profileStickerAvatarArtwork: com.badnng.moe.settings.ProfileStickerAvatarArtworkProvider? = null,
    onAppActivity: () -> Unit = {},
) {
    val suppliedPreferencesAccess = LocalSettingsPreferencesAccess.current
    val preferencesAccess = suppliedPreferencesAccess ?: DeviceSettingsPreferencesAccess
    val settingsPersistence = persistUiPreferences || suppliedPreferencesAccess != null
    val currentAccess = rememberUpdatedState(preferencesAccess)
    val currentPersistence = rememberUpdatedState(settingsPersistence)
    var preferences by remember { mutableStateOf(if (settingsPersistence) preferencesAccess.load() else SettingsPreferences(theme = initialTheme)) }
    var preferenceError by remember { mutableStateOf(false) }
    var fontReloadRevision by remember { mutableLongStateOf(0L) }
    val savePreferences: (SettingsPreferences) -> Boolean = { updated ->
        val saved = !currentPersistence.value || currentAccess.value.save(updated)
        preferenceError = !saved
        if (saved) preferences = updated
        saved
    }
    val saveDeviceNamePreferences: (SettingsPreferences, () -> Boolean) -> Boolean = { updated, current ->
        val saved = if (currentPersistence.value) currentAccess.value.saveIfCurrent(updated, current) else current()
        preferenceError = !saved
        if (saved) preferences = updated
        saved
    }
    // Unrelated preference edits never restore a theme or name captured by an earlier frame.
    val updatePreferences: (SettingsPreferences) -> Unit = {
        savePreferences(it.copy(theme = preferences.theme, manualThemeDark = preferences.manualThemeDark,
            customDeviceModel = preferences.customDeviceModel))
    }
    val observedSystemDark = LocalSettingsSystemThemeObservation.current.current()
    val themeCoordinator = remember { SettingsThemeCoordinator(preferences.themeStatePreferences(), observedSystemDark) }
    val themeState = observeSettingsSystemTheme(themeCoordinator.state, observedSystemDark)
    SideEffect { themeCoordinator.observe(observedSystemDark) }
    val theme = ColorSchemeMode.valueOf(settingsMiuixModeName(themeState))
    val saveTheme: (SettingsThemePreferences) -> Boolean = { value -> savePreferences(preferences.withThemeState(value)) }
    val chooseTheme: (ColorSchemeMode, () -> Boolean) -> Unit = { value, owner ->
        when (settingsThemeBaseMode(value)) {
            ColorSchemeMode.System -> themeCoordinator.chooseFollow(owner, saveTheme)
            ColorSchemeMode.Light -> themeCoordinator.chooseBrightness(false, owner, saveTheme)
            ColorSchemeMode.Dark -> themeCoordinator.chooseBrightness(true, owner, saveTheme)
            else -> Unit
        }
    }
    var folderPreferences by remember { mutableStateOf(if (persistUiPreferences) loadFolderPreferences() else ChatFolderPreferences()) }
    val updateFolderPreferences: (ChatFolderPreferences) -> Unit = { updated ->
        val saved = !persistUiPreferences || saveFolderPreferences(updated)
        preferenceError = !saved
        if (saved) folderPreferences = updated
    }
    val controller = rememberSettingsThemeController(theme, preferences.accentColor, systemDark = themeState.effectiveDark)
    val themeObserver = LocalAppThemeObserver.current
    LaunchedEffect(themeState.effectiveDark, themeObserver) { themeObserver(themeState.effectiveDark) }
    var scenario by remember { mutableStateOf(PreviewScenario.CONFIGURATION) }
    var status by remember { mutableStateOf(scenario.snapshot()) }
    var draft by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    var diagnostics by rememberSaveable { mutableStateOf(false) }
    var preview by rememberSaveable { mutableStateOf(false) }
    var welcomeAcknowledged by rememberSaveable { mutableStateOf(false) }
    val settingsVisibility = remember(runtime?.generation) { ThemeOwnedFlag() }
    var showSettings by settingsVisibility::value
    val settingsState = remember(runtime?.generation) { SettingsWorkspaceState() }
    DisposableEffect(settingsState) { onDispose { settingsState.revokeProxyChecks() } }
    val closeSettings = { settingsState.revokeProxyChecks(); showSettings = false }
    var pendingSupportChatToken by remember(runtime?.generation) {
        mutableStateOf<com.badnng.moe.chat.ChatToken?>(null)
    }
    val settingsSavedState = key(runtime?.generation) { androidx.compose.runtime.saveable.rememberSaveableStateHolder() }
    val workspaceSession = remember(runtime?.generation) { ChatWorkspaceSession(runtime?.chats ?: com.badnng.moe.chat.ChatSnapshot()) }
    val latestRuntime = rememberUpdatedState(runtime)
    val latestSettingsActions = rememberUpdatedState(settingsActions)
    var pendingRootLockEpoch by remember(runtime?.generation) { mutableStateOf<Long?>(null) }
    val localRootBlocked = runtime?.localLock?.rootBlocked == true || pendingRootLockEpoch != null
    val wallpaperBackend = LocalLocalWallpaperBackend.current
        ?: if (settingsPersistence) rememberLocalWallpaperBackend() else null
    val wallpaperScope = rememberCoroutineScope()
    val wallpaperController = remember(wallpaperBackend, runtime?.generation) {
        wallpaperBackend?.let { LocalWallpaperController(it, wallpaperScope) }
    }
    DisposableEffect(wallpaperController) { onDispose { wallpaperController?.close() } }
    LaunchedEffect(wallpaperController, settingsPersistence) {
        wallpaperController?.load()
    }
    LaunchedEffect(wallpaperController, localRootBlocked, showSettings) {
        if (localRootBlocked || !showSettings) wallpaperController?.cancel()
    }
    val currentLocalRootBlocked = rememberUpdatedState(localRootBlocked)
    SideEffect { if (localRootBlocked) themeCoordinator.dismiss(themeCoordinator.pending) }
    LaunchedEffect(runtime?.localLock?.epoch, runtime?.localLock?.rootBlocked) {
        val pending = pendingRootLockEpoch
        val actual = runtime?.localLock
        if (pending != null && actual != null && (actual.rootBlocked || actual.epoch != pending)) pendingRootLockEpoch = null
    }
    val requestLocalLock: () -> Unit = {
        val actual = latestRuntime.value
        if (actual != null && !actual.localLock.rootBlocked && actual.localLock.canLockNow && pendingRootLockEpoch == null) {
            // Close this composition synchronously; the coordinator closes its native witness
            // before asynchronously projecting the matching locked epoch.
            pendingRootLockEpoch = actual.localLock.epoch
            settingsState.revokeProxyChecks()
            pendingSupportChatToken = null
            latestSettingsActions.value.localLock.closeManagement()
            latestSettingsActions.value.localLock.lockNow()
        }
    }
    // Message drafts stay in memory only, including while viewing settings.
    val chatDrafts = remember(runtime?.generation) { mutableStateMapOf<Long, TextFieldValue>() }
    val chatDraftRevisions = remember(runtime?.generation) { ComposerDraftRevisions() }
    LaunchedEffect(runtime?.chats?.acceptedSendId, runtime?.chats?.acceptedSendChatId) {
        if ((runtime?.chats?.acceptedSendId ?: 0) > 0) {
            runtime?.chats?.acceptedSendChatId?.let { chatId ->
                // Only the actual accepted request may clear its draft; preserve newer edits.
                val submitted = runtime.chats.acceptedSendText
                val current = chatDrafts[chatId]
                if (chatDraftRevisions.consumeAccepted(chatId, runtime.chats.acceptedSendId, submitted?.let {
                        com.badnng.moe.chat.RichMessageText(it, runtime.chats.acceptedSendEntities)
                    }, current)) {
                    chatDraftRevisions.changed(chatId, current, TextFieldValue())
                    chatDrafts.remove(chatId)
                }
            }
        }
    }
    LaunchedEffect(runtime?.status?.authorization, runtime?.clientActive, runtime?.chats?.enabled, exitRequested) {
        if (runtime == null || runtime.status.authorization != AuthorizationPhase.READY || !runtime.clientActive ||
            !runtime.chats.enabled || exitRequested) {
            chatDrafts.clear()
            chatDraftRevisions.clear()
        }
    }
    // This condition belongs to the actual account, including while preview is selected.
    val mandatoryLoginEmail = runtime?.clientActive == true && runtime.loginEmail.mandatory && !exitRequested
    val live = runtime.takeUnless { preview }
    val displayedStatus = live?.status ?: status
    LaunchedEffect(pendingSupportChatToken, live?.chats?.token, live?.chats?.selected?.id, showSettings, settingsState.destination,
        mandatoryLoginEmail, localRootBlocked, exitRequested, live?.clientActive) {
        val target = pendingSupportChatToken ?: return@LaunchedEffect
        val actual = live?.chats?.token
        when {
            !showSettings || settingsState.destination != SettingsDestination.HELP || mandatoryLoginEmail || localRootBlocked ||
                exitRequested || live?.clientActive != true || actual == null || actual.generation != target.generation ->
                pendingSupportChatToken = null
            actual == target && target.chatId != null && live != null && live.chats.selected?.id == target.chatId -> {
                workspaceSession.conversationMemory.prepareEntry(target.chatId)
                workspaceSession.requestedConversationChatId.value = target.chatId
                workspaceSession.requestedNavigationFailureRevision.value = live.chats.navigationFailureRevision
                workspaceSession.showConversation.value = true
                pendingSupportChatToken = null
                closeSettings()
            }
            actual.selection >= target.selection -> pendingSupportChatToken = null
        }
    }
    var consumedNotificationOpenRevision by remember(runtime?.generation) { mutableLongStateOf(0L) }
    LaunchedEffect(live?.generation, live?.deviceNotifications?.openChatRevision, mandatoryLoginEmail, localRootBlocked) {
        val revision = live?.deviceNotifications?.openChatRevision ?: 0L
        if (revision > consumedNotificationOpenRevision) {
            consumedNotificationOpenRevision = revision
            if (!exitRequested && !mandatoryLoginEmail && !localRootBlocked) closeSettings()
        }
    }
    LaunchedEffect(runtime?.generation, runtime?.deviceNotifications?.enabled, windowFocused, showSettings, preview,
        exitRequested, runtime?.chats?.token?.chatId, mandatoryLoginEmail, localRootBlocked) {
        if (runtime?.deviceNotifications?.enabled == true) settingsActions.deviceNotifications.context(runtime.generation,
            windowFocused && !exitRequested,
            runtime.chats.token.chatId.takeUnless { showSettings || preview || exitRequested || mandatoryLoginEmail || localRootBlocked },
            mandatoryLoginEmail || localRootBlocked)
    }
    val deviceNotificationActions = settingsActions.deviceNotifications.copy(activate = { identity ->
        if (live?.deviceNotifications?.cards?.any { it.identity == identity } == true && !exitRequested && !mandatoryLoginEmail &&
            !currentLocalRootBlocked.value) {
            closeSettings()
            settingsActions.deviceNotifications.activate(identity)
        }
    })
    val systemDensity = LocalDensity.current
    val interfaceDensity = remember(systemDensity, preferences.interfaceScalePercent) {
        settingsInterfaceDensity(systemDensity, preferences.interfaceScalePercent)
    }

    CompositionLocalProvider(LocalSettingsPreferences provides preferences,
        LocalLocalWallpaperController provides wallpaperController,
        LocalAppEffectiveDark provides themeState.effectiveDark,
        LocalDeviceNotifications provides (live?.deviceNotifications ?: DeviceNotificationsSnapshot()),
        LocalDeviceNotificationActions provides deviceNotificationActions,
        LocalDownloadAccountDataPath provides live?.dataPath,
        LocalDownloadAccountUserId provides live?.chats?.account?.userId,
        LocalSettingsStorageSnapshot provides (live?.storageSettings ?: com.badnng.moe.settings.SettingsStorageSnapshot()),
        LocalSettingsStorageActions provides settingsActions.storage,
        LocalSettingsSystemDensity provides systemDensity, LocalDensity provides interfaceDensity) {
    MiuixTheme(controller = controller) {
    val themeConsumerProbe = LocalSettingsThemeConsumerProbe.current
    val renderedSurface = MiuixTheme.colorScheme.surface
    SideEffect { themeConsumerProbe?.invoke("miuix", renderedSurface.luminance() < 0.5f) }
    ProfileStickerAvatarArtworkHost(
        provider = profileStickerAvatarArtwork.takeIf { live?.clientActive == true && !exitRequested && !mandatoryLoginEmail && !localRootBlocked &&
            live.status.authorization == AuthorizationPhase.READY && live.chats.enabled },
        generation = live?.generation ?: 0L,
    ) {
    ProfileEmojiArtworkHost(
        provider = profileEmojiArtwork.takeIf { live?.clientActive == true && !exitRequested && !mandatoryLoginEmail && !localRootBlocked &&
            live.status.authorization == AuthorizationPhase.READY && live.chats.enabled },
        generation = live?.generation ?: 0L,
        accountUserId = live?.chats?.account?.userId ?: 0L,
        catalog = live?.chats?.appearanceCatalog ?: com.badnng.moe.settings.SettingsProfileColorsCatalog(),
    ) {
    SettingsFontHost(preferences, active = !exitRequested && !localRootBlocked, reloadKey = fontReloadRevision) {
        BoxWithConstraints(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface).localLockUserActivity(onAppActivity)) {
            val contentDensity = LocalDensity.current
            val contentWidth = with(contentDensity) { constraints.maxWidth.toDp() }
            val wide = contentWidth >= 980.dp
            val nativeWelcome = live?.appWelcomeState(active = !exitRequested,
                configurationOpenError = configurationOpenError)
            val introStart = live != null && nativeWelcome == null && !welcomeAcknowledged &&
                !live.loginInitialPhoneMode && live.status.authorization == AuthorizationPhase.WAITING_LOGIN
            val welcomeState = nativeWelcome ?: if (introStart) AppWelcomeState(generation = live!!.generation,
                active = !exitRequested) else null
            val route = when {
                live?.chats?.enabled == true && live.status.authorization == AuthorizationPhase.READY && !exitRequested ->
                    if (showSettings) "settings" else "chat"
                live != null && !exitRequested && welcomeState != null -> "welcome"
                live != null && !exitRequested && live.status.authorization in setOf(AuthorizationPhase.WAITING_LOGIN,
                    AuthorizationPhase.WAITING_CODE, AuthorizationPhase.WAITING_PASSWORD, AuthorizationPhase.OTHER_STEP) -> "auth"
                else -> "runtime"
            }
            val currentThemeRoute = rememberUpdatedState(route)
            val currentThemeBlocked = rememberUpdatedState(exitRequested || mandatoryLoginEmail || localRootBlocked)
            if (localRootBlocked && runtime != null) {
                // A direct branch removes the whole old page, including native Popup,
                // recording and media owners. App-owned drafts and revisions stay above it.
                LocalLockScreen(runtime.localLock, settingsActions.localLock, windowFocused,
                    modifier = Modifier.fillMaxSize(), awaitingLock = pendingRootLockEpoch != null,
                    onStop = onStop, onExit = onExit, active = !exitRequested,
                    onRecheckProtection = onStart.takeIf { runtime.canStart && !exitRequested &&
                        runtime.localLock.phase == com.badnng.moe.settings.LocalLockPhase.RECOVERY })
            } else if (mandatoryLoginEmail && runtime != null) {
                // No outgoing page survives a mandatory lock: Popups, recording and attachment
                // owners must dispose immediately, while the App-owned drafts above survive.
                LoginEmailSetupPage(runtime.loginEmail, settingsActions.loginEmail,
                    com.badnng.moe.settings.LoginEmailEntryKind.MANDATORY,
                    modifier = Modifier.fillMaxSize(),
                    ready = runtime.status.authorization == AuthorizationPhase.READY &&
                        runtime.status.engine == EnginePhase.INITIALIZED &&
                        runtime.proxy in setOf(ProxyStatus.DIRECT, ProxyStatus.ENABLED),
                    onStop = onStop, onExit = onExit)
            } else {
            AnimatedContent(targetState = route,
                modifier = Modifier.fillMaxSize(), label = "settings navigation",
                transitionSpec = {
                    val direction = if (targetState == "chat") -1 else 1
                    (slideInHorizontally(tween(240)) { direction * it / 5 } + fadeIn(tween(200))) togetherWith
                        (slideOutHorizontally(tween(220)) { -direction * it / 5 } + fadeOut(tween(160)))
                }) { chatPage ->
            if (chatPage == "chat" && live != null) {
                val visibilityRevision = settingsVisibility.revision
                val chatThemeLease = remember(live.generation, route == "chat", visibilityRevision) { ThemePageLease() }
                DisposableEffect(chatThemeLease) { onDispose { chatThemeLease.close() } }
                val chatThemeOwner = { chatThemeLease.alive && currentThemeRoute.value == "chat" && !currentThemeBlocked.value &&
                    !showSettings && settingsVisibility.revision == visibilityRevision }
                ChatWorkspace(
                    snapshot = live.chats,
                    connection = live.status.connection,
                    drafts = chatDrafts,
                    draftRevisions = chatDraftRevisions,
                    onSettings = { showSettings = true },
                    onSelectChat = onSelectChat,
                    onLoadChats = onLoadChats,
                    onLoadOlder = onLoadOlder,
                    onSendText = onSendText,
                    onRetryMessage = onRetryMessage,
                    onMarkRead = onMarkRead,
                    windowFocused = windowFocused && !showSettings && !exitRequested,
                    features = features,
                    darkTheme = themeState.effectiveDark,
                    onDarkTheme = {},
                    onOwnedDarkTheme = { dark, menuOwner ->
                        themeCoordinator.chooseBrightness(dark, { chatThemeOwner() && menuOwner() }, saveTheme)
                    },
                    persistUiPreferences = persistUiPreferences,
                    pageActive = route == "chat",
                    session = workspaceSession,
                    settingsFolderPreferences = folderPreferences,
                    onSettingsFolderPreferences = updateFolderPreferences,
                    onLockNow = requestLocalLock.takeIf { live.localLock.canLockNow },
                )
            } else if (chatPage == "settings" && live != null) {
                val visibilityRevision = settingsVisibility.revision
                val themeNavigationRevision = settingsState.themeNavigationRevision
                val settingsThemeOwner = { currentThemeRoute.value == "settings" && !currentThemeBlocked.value &&
                    showSettings && settingsVisibility.revision == visibilityRevision }
                val diagnosticThemeOwner = { settingsThemeOwner() &&
                    settingsState.themeNavigationRevision == themeNavigationRevision && settingsState.destination == SettingsDestination.ADVANCED }
                SettingsWorkspace(live, settingsActions, features, preferences, updatePreferences,
                    folderPreferences, updateFolderPreferences, onClose = closeSettings,
                    onOpenConfig = onOpenConfig, state = settingsState, active = route == "settings",
                    savedState = settingsSavedState, preferenceError = preferenceError,
                    onSaveFont = { font ->
                        if (route == "settings" && !exitRequested) {
                            val saved = savePreferences(preferences.copy(chatFont = font))
                            if (saved) fontReloadRevision++
                            saved
                        } else false
                    },
                    onSupportOpened = { token ->
                        if (route == "settings" && !exitRequested && !mandatoryLoginEmail && !currentLocalRootBlocked.value &&
                            settingsState.destination == SettingsDestination.HELP && live.generation == token.generation)
                            pendingSupportChatToken = token
                    },
                    themeCoordinator = themeCoordinator, themeParentRevision = visibilityRevision,
                    themeParentCurrent = settingsThemeOwner, onSaveTheme = saveTheme,
                    onSaveDeviceName = { name, current, result ->
                        // This local file save is synchronous on the UI thread. The current
                        // predicate is checked at the actual commit boundary, not just in a dialog.
                        if (!settingsThemeOwner() || !current()) result(false)
                        else result(saveDeviceNamePreferences(preferences.copy(customDeviceModel = name)) {
                            settingsThemeOwner() && current()
                        })
                    }) {
                    SettingsPanel(theme, chooseTheme, draft, { draft = it }, diagnostics,
                        { diagnostics = !diagnostics }, runtimeInfo, displayedStatus, runtime = live,
                        onOpenConfig = onOpenConfig, configurationOpenError = configurationOpenError,
                        themeOwner = diagnosticThemeOwner, themeOwnerIdentity = Triple(live.generation, visibilityRevision, themeNavigationRevision),
                        themeActive = route == "settings")
                    if (diagnostics) EventHistory(live)
                }
            } else if (chatPage == "welcome") {
                welcomeState?.let { welcome ->
                    AppWelcomeScreen(welcome.copy(active = welcome.active && route == "welcome"), onContinue = {
                        if (route == "welcome" && !exitRequested) {
                            welcomeAcknowledged = true
                            if (!introStart) {
                                if (live?.clientActive == true) onRestart() else onStart()
                            }
                        }
                    })
                }
            } else if (chatPage == "auth" && live != null) {
                LoginPanel(live, exitRequested, onSubmitLogin, onCancelQr,
                    initialPhoneMode = live.loginInitialPhoneMode, modifier = Modifier.fillMaxSize(), active = route == "auth")
            } else {
            val visibilityRevision = settingsVisibility.revision
            val diagnosticThemeOwner = { currentThemeRoute.value == "runtime" && !currentThemeBlocked.value &&
                settingsVisibility.revision == visibilityRevision }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(if (wide) 32.dp else 20.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Header(live != null)
                if (live?.chats?.enabled == true && !exitRequested) {
                    Choice("返回聊天", true, closeSettings)
                }
                if (runtime != null) {
                    Choice(if (preview) "返回真实运行状态" else "查看界面预览", preview) {
                        settingsState.revokeProxyChecks()
                        preview = !preview
                    }
                }
                val onScenario: (PreviewScenario) -> Unit = { scenario = it; status = it.snapshot() }
                val onConnection: (ConnectionPhase) -> Unit = { status = status.reduce(StatusEvent.ConnectionChanged(it)) }
                if (wide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        StatusWorkspace(scenario, displayedStatus, onScenario, onConnection, Modifier.weight(1f), live, onStart,
                            onStop, onRestart, exitRequested, onContinueExit, onSubmitLogin)
                        SettingsPanel(theme, chooseTheme, draft, { draft = it }, diagnostics,
                            { diagnostics = !diagnostics }, runtimeInfo, displayedStatus, Modifier.width(320.dp), live, onOpenConfig, configurationOpenError,
                            themeOwner = diagnosticThemeOwner, themeOwnerIdentity = visibilityRevision, themeActive = route == "runtime")
                    }
                } else {
                    StatusWorkspace(scenario, displayedStatus, onScenario, onConnection, runtime = live, onStart = onStart,
                        onStop = onStop, onRestart = onRestart, exitRequested = exitRequested,
                        onContinueExit = onContinueExit, onSubmitLogin = onSubmitLogin)
                    SettingsPanel(theme, chooseTheme, draft, { draft = it }, diagnostics,
                        { diagnostics = !diagnostics }, runtimeInfo, displayedStatus, runtime = live, onOpenConfig = onOpenConfig,
                        configurationOpenError = configurationOpenError, themeOwner = diagnosticThemeOwner,
                        themeOwnerIdentity = visibilityRevision, themeActive = route == "runtime")
                }
                if (diagnostics && live != null) EventHistory(live)
                Text("nngGram  ·  Windows x64  ·  聊天与媒体", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            }
            }
            SettingsThemeConfirmationDialog(themeCoordinator, !exitRequested && !mandatoryLoginEmail && !localRootBlocked)
            if (live != null && !exitRequested && !mandatoryLoginEmail && !localRootBlocked &&
                live.status.authorization == AuthorizationPhase.READY)
                DeviceNotificationOverlay(actions = deviceNotificationActions)
            }
        }
    }
}
}
}
}

}

@Composable
private fun Header(live: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).chatSquircleBackground(MiuixTheme.colorScheme.primary, 16.dp), contentAlignment = Alignment.Center) {
            Text("n", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onPrimary)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("nngGram", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("轻盈的桌面，从这里开始", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        Box(Modifier.chatSquircleBackground(MiuixTheme.colorScheme.secondaryContainer, 24.dp).padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text(if (live) "真实 TDLib" else "演示数据", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSecondaryContainer)
        }
    }
}

@Composable
private fun StatusWorkspace(
    scenario: PreviewScenario,
    status: ClientStatus,
    onScenario: (PreviewScenario) -> Unit,
    onConnection: (ConnectionPhase) -> Unit,
    modifier: Modifier = Modifier,
    runtime: RuntimeSnapshot? = null,
    onStart: () -> Unit = {},
    onStop: () -> Unit = {},
    onRestart: () -> Unit = {},
    exitRequested: Boolean = false,
    onContinueExit: () -> Unit = {},
    onSubmitLogin: (LoginToken, LoginRequestKind, String) -> Unit = { _, _, _ -> },
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("运行概览", fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
            Text(runtime?.note ?: "${scenario.description}。", fontSize = 15.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (runtime == null) {
                PreviewScenario.entries.forEach { item -> Choice(item.title, scenario == item) { onScenario(item) } }
            } else if (runtime.canStart && !exitRequested) {
                Choice("启动客户端", true, onStart)
            } else if (runtime.clientActive && status.engine != EnginePhase.CLOSING && !exitRequested) {
                Choice("停止客户端", false, onStop)
                if (status.engine != EnginePhase.FAILED) Choice("重新启动", false, onRestart)
            }
        }
        Card(Modifier.fillMaxWidth(), cornerRadius = 24.dp, insideMargin = PaddingValues(22.dp)) {
            Text(if (runtime == null) "状态预览" else "客户端状态", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(if (runtime == null) "以下为内置演示，不代表后台客户端的运行状态。" else "三项状态独立来自 TDLib。初始化完成不代表账号已登录或网络已连接。", fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Spacer(Modifier.height(24.dp))
            StatusRow("01", "客户端引擎", status.engine.presentation())
            Rule()
            StatusRow("02", "账号授权", status.authorization.presentation())
            Rule()
            StatusRow("03", "网络连接", status.connection.presentation())
        }
        if (runtime != null) {
            LoginPanel(runtime, exitRequested, onSubmitLogin)
        }
        if (runtime?.closeTimedOut == true) {
            Card(Modifier.fillMaxWidth(), cornerRadius = 24.dp, insideMargin = PaddingValues(24.dp)) {
                Text("仍在等待 TDLib 关闭", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                Text("客户端尚未确认关闭，数据目录仍保留占用。确认资源释放后才能重新启动或退出。", fontSize = 14.sp)
                Spacer(Modifier.height(14.dp))
                Choice(if (exitRequested) "继续等待退出" else "继续等待关闭", true, if (exitRequested) onContinueExit else onStop)
            }
        }
        status.failure?.let { failure ->
            Card(Modifier.fillMaxWidth(), cornerRadius = 20.dp, insideMargin = PaddingValues(20.dp)) {
                Text("${if (runtime == null) "演示问题" else "启动问题"} · ${failure.summary}", color = MiuixTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(failure.recovery, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                Text("分类：${failure.category.label()}", fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))
                if (runtime == null) Choice("重新预览加载", false) { onScenario(PreviewScenario.LOADING) }
                else if (runtime.canStart && !exitRequested) Choice("修复后重试", false, onStart)
            }
        }
        runtime?.proxyFailure?.let { failure ->
            Card(Modifier.fillMaxWidth(), cornerRadius = 24.dp, insideMargin = PaddingValues(24.dp)) {
                Text(failure.summary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = MiuixTheme.colorScheme.error)
                Spacer(Modifier.height(10.dp))
                Text(failure.recovery, fontSize = 14.sp)
            }
        }
        if (runtime == null) Card(Modifier.fillMaxWidth(), cornerRadius = 24.dp, insideMargin = PaddingValues(22.dp)) {
            Text("网络状态预览", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text("仅切换网络显示，保留当前引擎与账号状态。", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Spacer(Modifier.height(16.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ConnectionPhase.entries.forEach { phase -> Choice(phase.presentation().label, status.connection == phase) { onConnection(phase) } }
            }
        }
    }
}

@Composable
private fun StatusRow(number: String, title: String, state: StatusPresentation) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(38.dp).chatSquircleBackground(MiuixTheme.colorScheme.secondaryContainer, 19.dp), contentAlignment = Alignment.Center) {
            Text(number, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSecondaryContainer)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Text(state.label, fontSize = 23.sp, fontWeight = FontWeight.SemiBold, color = toneColor(state.tone))
            Text(state.detail, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}

@Composable
private fun SettingsPanel(
    theme: ColorSchemeMode,
    onTheme: (ColorSchemeMode, () -> Boolean) -> Unit,
    draft: TextFieldValue,
    onDraft: (TextFieldValue) -> Unit,
    diagnostics: Boolean,
    onDiagnostics: () -> Unit,
    runtimeInfo: RuntimeInfo,
    status: ClientStatus,
    modifier: Modifier = Modifier,
    runtime: RuntimeSnapshot? = null,
    onOpenConfig: () -> Unit = {},
    configurationOpenError: String? = null,
    themeOwner: () -> Boolean = { true },
    themeOwnerIdentity: Any = Unit,
    themeActive: Boolean = true,
) {
    val pageActive = themeActive && LocalSettingsPageActive.current
    val currentActive = rememberUpdatedState(pageActive)
    val currentOwner = rememberUpdatedState(themeOwner)
    val lease = remember(themeOwnerIdentity, pageActive) { ThemePageLease() }
    DisposableEffect(lease) { onDispose { lease.close() } }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Card(Modifier.fillMaxWidth(), cornerRadius = 24.dp, insideMargin = PaddingValues(22.dp)) {
            Text("基础配置", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(20.dp))
            Text("外观", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("自动" to ColorSchemeMode.System, "浅色" to ColorSchemeMode.Light, "深色" to ColorSchemeMode.Dark).forEach { (name, mode) ->
                    Choice(name, settingsThemeBaseMode(theme) == mode) {
                        if (currentActive.value && lease.alive) onTheme(mode) { lease.alive && currentActive.value && currentOwner.value() }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            if (runtime != null) {
                Text("连接方式：${runtime.proxy.label()}", fontSize = 13.sp)
                Text("可在高级设置中管理代理。", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            } else {
                Text("中文输入体验", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(10.dp))
                TextField(value = draft, onValueChange = onDraft, label = "试着输入一句中文", modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 5)
                Spacer(Modifier.height(10.dp))
                Text("仅在此窗口预览，不会发送或保存。", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                if (draft.text.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(draft.text, fontSize = 14.sp)
                    Spacer(Modifier.height(10.dp))
                    Choice("清空输入", false) { onDraft(TextFieldValue()) }
                }
            }
        }
        Card(Modifier.fillMaxWidth(), cornerRadius = 24.dp, insideMargin = PaddingValues(22.dp)) {
            Text(if (runtime == null) "检查窗口体验" else "运行诊断", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            Text(if (runtime == null) "切换外观、缩放窗口，或输入中文。窄窗口下配置区域会移到状态区域下方。" else "查看原生版本、加载位置与安全事件。手机号、验证码和二步验证密码不会出现在诊断记录中。", fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Spacer(Modifier.height(18.dp))
            Choice(if (diagnostics) "收起诊断" else "查看诊断", diagnostics, onDiagnostics)
            if (diagnostics) {
                Spacer(Modifier.height(16.dp))
                Text("数据来源：${if (runtime == null) "内置演示" else "真实 TDLib"}\n平台：${runtimeInfo.platform}\nJVM：${runtimeInfo.javaVersion}\nTDLib：${runtime?.native?.version ?: "尚未加载"}", fontSize = 12.sp)
                runtime?.native?.let { native ->
                    Spacer(Modifier.height(8.dp))
                    Text("提交：${native.commit}\n原生目录：${native.directory}", fontSize = 12.sp)
                }
                runtime?.dataPath?.let { path ->
                    Spacer(Modifier.height(8.dp))
                    Text("数据目录：$path", fontSize = 12.sp)
                }
                Spacer(Modifier.height(8.dp))
                Text("引擎：${status.engine}\n授权：${status.authorization}\n连接：${status.connection}", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                runtime?.let {
                    Spacer(Modifier.height(8.dp))
                    Text("运行轮次：${it.generation}\n安全事件日志：${it.eventLogPath ?: "尚未建立"}", fontSize = 12.sp)
                    if (!it.eventLogAvailable) {
                        Spacer(Modifier.height(8.dp))
                        Text("本地事件日志暂时无法写入，本次运行记录仍可在下方查看。", fontSize = 12.sp, color = MiuixTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun EventHistory(runtime: RuntimeSnapshot) {
    Card(Modifier.fillMaxWidth(), cornerRadius = 24.dp, insideMargin = PaddingValues(24.dp)) {
        Text("最近事件", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text("最新 12 条记录 · 时间为 UTC · 仅记录状态与分类，不包含凭据或 TDLib 原始响应", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        runtime.events.takeLast(12).asReversed().forEach { event ->
            Spacer(Modifier.height(16.dp))
            Text("${event.timeLabel}  ·  第 ${event.generation} 轮  ·  ${event.summary}", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text("${event.engine.presentation().label} / ${event.authorization.presentation().label} / ${event.connection.presentation().label}", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            event.category?.let { category ->
                Text("${category.label()}${event.requestErrorCode?.let { " · 错误代码 $it" } ?: ""}：${event.recovery}", fontSize = 12.sp)
            }
        }
    }
}

private fun FailureCategory.label(): String = when (this) {
    FailureCategory.CONFIGURATION -> "本地配置"
    FailureCategory.DIRECTORY -> "数据目录"
    FailureCategory.NATIVE -> "原生库"
    FailureCategory.TDLIB_REQUEST -> "TDLib 请求"
    FailureCategory.LIFECYCLE -> "关闭流程"
    FailureCategory.INTERNAL -> "内部运行"
    FailureCategory.PROXY -> "代理配置"
    FailureCategory.AUTHENTICATION -> "账号登录"
}

private fun ProxyStatus.label(): String = when (this) {
    ProxyStatus.NOT_CONFIGURED -> "等待初始化"
    ProxyStatus.CONFIGURING -> "正在应用"
    ProxyStatus.DIRECT -> "直接连接"
    ProxyStatus.ENABLED -> "本地配置的代理"
    ProxyStatus.FAILED -> "应用失败，请检查本地配置"
}

@Composable
private fun Choice(text: String, selected: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, colors = if (selected) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(),
        minWidth = 0.dp, minHeight = 38.dp, insideMargin = PaddingValues(horizontal = 14.dp, vertical = 9.dp)) {
        Text(text, fontSize = 13.sp)
    }
}

@Composable
private fun Rule() {
    Spacer(Modifier.height(20.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.12f)))
    Spacer(Modifier.height(20.dp))
}

@Composable
private fun toneColor(tone: StatusTone): Color = when (tone) {
    StatusTone.ERROR -> MiuixTheme.colorScheme.error
    StatusTone.ACTIVE, StatusTone.SUCCESS -> MiuixTheme.colorScheme.primary
    StatusTone.NEUTRAL -> MiuixTheme.colorScheme.onSurface
}


