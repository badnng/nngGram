package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.AccountProfile
import com.badnng.moe.chat.ChatItem
import com.badnng.moe.chat.ChatToken
import com.badnng.moe.media.DownloadDestinationRoots
import com.badnng.moe.media.LocalDownloadDestinationRootsResolver
import com.badnng.moe.settings.*
import com.badnng.moe.status.ClientStatus
import com.badnng.moe.status.AuthorizationPhase
import com.badnng.moe.status.ConnectionPhase
import com.badnng.moe.status.EnginePhase
import com.badnng.moe.status.ProxyStatus
import com.badnng.moe.status.RuntimeSnapshot
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Real workspace controls, pointer and scroll events, with synthetic checks only. */
class SettingsProxyChecksUiTest {
    @Test
    fun oldOwnerAndChangedRowsCannotDisplayAnEarlierMeasurement() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.settledFrame()
            val old = f.calls.single()
            val oldToken = f.publish(old, 1)
            scene.settledFrame()
            scene.reveal("alpha.invalid:1080", f)
            // The title can be fully visible while its summary remains below the viewport.
            scene.reveal("正在检测…… · 当前连接：已连接", f)
            assertTrue(scene.hasText("正在检测…… · 当前连接：已连接"))
            assertEquals(1, f.calls.size, "Scrolling and recomposition must not reopen the page.")
            onUi { f.runtime = f.runtime.copy(status = f.runtime.status.copy(connection = ConnectionPhase.CONNECTING_PROXY)) }
            f.writeChecks(oldToken, listOf(SettingsProxyCheckStatus.Available(53), SettingsProxyCheckStatus.Available(125),
                SettingsProxyCheckStatus.NotTested, SettingsProxyCheckStatus.Unavailable, SettingsProxyCheckStatus.NotTested))
            scene.settledFrame()
            scene.reveal("延迟 53 ms · 当前连接：连接代理中", f)
            onUi { f.runtime = f.runtime.copy(status = f.runtime.status.copy(connection = ConnectionPhase.READY)) }
            f.ack(old, SettingsProxyChecksToken(old.parent.copy(revision = 88), 99))
            scene.settledFrame()
            scene.reveal("未检测 · 当前连接：已连接", f)
            f.ack(old, oldToken); scene.settledFrame()

            val tab = scene.tab(SettingsDestination.CHAT)
            scene.click(tab)
            assertFalse(old.current(), "The actual category click must revoke before another frame.")
            assertEquals(1, old.closes.get())
            scene.settledFrame()
            scene.click(scene.tab(SettingsDestination.ADVANCED)); scene.settledFrame()
            val fresh = f.calls.last()
            assertEquals(2, f.calls.size)
            assertTrue(fresh.current())

            // A late old acknowledgement and an old native snapshot do not become this visit's owner.
            f.ack(old, oldToken)
            f.writeChecks(oldToken, listOf(SettingsProxyCheckStatus.Available(999),
                SettingsProxyCheckStatus.Available(999), SettingsProxyCheckStatus.NotTested,
                SettingsProxyCheckStatus.Unavailable, SettingsProxyCheckStatus.NotTested))
            scene.settledFrame()
            scene.reveal("alpha.invalid:1080", f)
            assertFalse(scene.hasText("延迟 999 ms · 当前连接：已连接"))
            assertTrue(scene.hasText("未检测 · 当前连接：已连接"))

            val freshToken = f.publish(fresh, 2)
            scene.settledFrame()
            assertTrue(scene.hasText("正在检测…… · 当前连接：已连接"))
            // Same id and same list position are insufficient when the complete row changes.
            onUi { f.update { it.copy(proxies = it.proxies.toMutableList().also { rows ->
                rows[0] = rows[0].copy(port = 1081)
            }) } }
            scene.settledFrame()
            scene.reveal("alpha.invalid:1081", f)
            assertTrue(scene.hasText("未检测 · 当前连接：已连接"))
            assertFalse(scene.hasText("正在检测…… · 当前连接：已连接"))
            assertEquals(freshToken, f.runtime.settings.proxyChecks.token)
            assertTrue(fresh.current())
        }
    }

    @Test
    fun inactiveDisabledChangedParentAndDisposalEachRetireTheirOwnVisit() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.settledFrame(); val inactive = f.calls.single(); f.publish(inactive, 41); scene.settledFrame()
            onUi { f.active = false }; scene.settledFrame()
            assertFalse(inactive.current()); assertEquals(1, inactive.closes.get())
            onUi { f.active = true }; scene.settledFrame()
            val disabled = f.calls.last(); assertEquals(2, f.calls.size)
            f.ack(inactive, SettingsProxyChecksToken(inactive.parent, 41))
            onUi { f.update { it.copy(enabled = false) } }; scene.settledFrame()
            assertFalse(disabled.current()); assertEquals(1, disabled.closes.get())
            onUi { f.update { it.copy(enabled = true) } }; scene.settledFrame()
            val parentChanged = f.calls.last(); assertEquals(3, f.calls.size)
            onUi { f.update { it.copy(token = it.token.copy(revision = it.token.revision + 1)) } }; scene.settledFrame()
            assertFalse(parentChanged.current()); assertEquals(1, parentChanged.closes.get())
            val unmounted = f.calls.last(); assertEquals(4, f.calls.size)
            assertEquals(4, unmounted.parent.revision)
            onUi { f.mounted = false }; scene.settledFrame()
            assertFalse(unmounted.current()); assertEquals(1, unmounted.closes.get())
            f.ack(unmounted, SettingsProxyChecksToken(unmounted.parent, 44)); scene.settledFrame()
            assertEquals(4, f.calls.size, "Late acknowledgements cannot reopen an unmounted page.")
        }
    }

    @Test
    fun actualAppCloseRetiresTheProxyPageBeforeItsSettingsTransition() {
        val f = Fixture()
        val selected = ChatItem(700, "Synthetic chat")
        val runtime = f.runtime.copy(clientActive = true, proxy = ProxyStatus.DIRECT,
            status = ClientStatus(EnginePhase.INITIALIZED, AuthorizationPhase.READY, ConnectionPhase.READY),
            chats = ChatSnapshot(enabled = true, token = ChatToken(17, 1, selected.id), selected = selected,
                account = AccountProfile(123, "Synthetic"), knownChats = listOf(selected), items = listOf(selected),
                messages = emptyList(), historyEnd = true, listEnd = true))
        ComposePixelScene(1100, 900) {
            CompositionLocalProvider(LocalDensity provides Density(1f), LocalDownloadPreferencesAccess provides f.downloads,
                LocalDownloadDestinationRootsResolver provides { _, _ -> DownloadDestinationRoots(
                    java.nio.file.Path.of("C:/Synthetic/Downloads/nngGram"), java.nio.file.Path.of("C:/Synthetic/Account/downloads-temp/user-1")) },
                LocalSettingsSecurityConfirmationHost provides InlineConfirmation) {
                Box(Modifier.fillMaxSize()) {
                    App(runtime = runtime, settingsActions = f.actions, persistUiPreferences = false, windowFocused = true)
                }
            }
        }.use { scene ->
            scene.settledFrame(); scene.click(scene.description("打开账号菜单")); scene.settledFrame()
            scene.click(scene.description("设置")); scene.settledFrame()
            scene.click(scene.tab(SettingsDestination.ADVANCED)); scene.settledFrame()
            val call = f.calls.single(); assertTrue(call.current())
            scene.click(scene.description("返回聊天"))
            assertFalse(call.current(), "App's real close route revokes before the outgoing transition frame.")
            assertEquals(1, call.closes.get())
            scene.settledFrame()
        }
    }

    @Test
    fun closeAndReopenInOnePointerTurnStartsASeparateOwner() {
        val f = Fixture().apply { reopenOnClose = true }
        f.scene().use { scene ->
            scene.settledFrame()
            val old = f.calls.single()
            val oldToken = f.publish(old, 50)
            scene.settledFrame()
            val revision = f.state.proxyNavigationRevision
            scene.click(scene.description("返回聊天"))
            assertEquals(1, f.closeCount)
            assertTrue(f.active, "The host reopens before any intermediate frame is rendered.")
            assertEquals(SettingsDestination.ADVANCED, f.state.destination)
            assertFalse(old.current(), "The actual close click synchronously retires the previous owner.")
            assertEquals(1, old.closes.get())
            assertTrue(f.state.proxyNavigationRevision > revision)
            assertEquals(1, f.calls.size, "No render has occurred since the close and reopen.")
            scene.settledFrame()
            assertEquals(2, f.calls.size, "The still-active page must open a new owner after coalesced navigation.")
            val fresh = f.calls.last()
            assertEquals(old.parent, fresh.parent)
            assertTrue(fresh.current())
            f.ack(old, oldToken)
            f.writeChecks(oldToken, listOf(SettingsProxyCheckStatus.Available(999),
                SettingsProxyCheckStatus.Available(999), SettingsProxyCheckStatus.NotTested,
                SettingsProxyCheckStatus.Unavailable, SettingsProxyCheckStatus.NotTested))
            scene.settledFrame()
            scene.reveal("未检测 · 当前连接：已连接", f)
            assertFalse(scene.hasText("延迟 999 ms · 当前连接：已连接"))
            f.publish(fresh, 51); scene.settledFrame()
            scene.reveal("正在检测…… · 当前连接：已连接", f)
            assertEquals(2, f.calls.size, "Renders and scrolling must not reopen the fresh owner again.")
        }
    }

    @Test
    fun narrowBackCloseResizeAndRefreshUseTheActualControls() {
        val f = Fixture(width = 350, height = 300)
        f.scene(maxWidth = 1000).use { scene ->
            scene.settledFrame()
            val first = f.calls.single()
            f.publish(first, 11); scene.settledFrame()
            scene.click(scene.description("返回设置分类"))
            assertFalse(first.current(), "Narrow back revokes before its retained outgoing page animates.")
            assertEquals(1, first.closes.get())
            scene.settledFrame()
            scene.click(scene.revealTab(SettingsDestination.ADVANCED, f)); scene.settledFrame()
            val second = f.calls.last()
            assertEquals(2, f.calls.size)
            f.publish(second, 12); scene.settledFrame()

            onUi { f.width = 1000 }; scene.settledFrame()
            onUi { f.width = 350 }; scene.settledFrame()
            assertEquals(2, f.calls.size, "A still-visible detail survives resize without reopening checks.")
            assertTrue(second.current())
            scene.click(scene.description("刷新")); scene.settledFrame()
            assertEquals(listOf(second), f.refreshes)
            assertTrue(f.normalLoads.isEmpty(), "ADVANCED refresh must not use the parent-only load action.")
            assertEquals(2, f.calls.size, "Refresh belongs to the current page; it is not a new page open.")
            scene.reveal("未检测 · 当前连接：已连接", f)

            onUi { f.width = 1000 }; scene.settledFrame()
            scene.click(scene.description("返回聊天"))
            assertFalse(second.current(), "The workspace close button revokes synchronously.")
            assertEquals(1, second.closes.get())
            assertEquals(1, f.closeCount)
            scene.settledFrame()
        }
    }

    @Test
    fun refreshAndErrorRetryUseOnlyTheirCapturedLivePage() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.settledFrame()
            val old = f.calls.single()
            f.publish(old, 61); scene.settledFrame()
            var oldPage: SettingsProxyChecksPageLease? = null
            onUi { oldPage = f.state.proxyChecksOwner.current }
            val capturedOldPage = assertNotNull(oldPage)

            scene.click(scene.description("刷新")); scene.settledFrame()
            assertEquals(listOf(old), f.refreshes)
            assertEquals(1, old.refreshAttempts.get())
            assertTrue(f.normalLoads.isEmpty())
            onUi { f.update { it.copy(errors = mapOf(SettingsSection.ADVANCED to SettingsError.REQUEST_FAILED)) } }
            scene.settledFrame()
            scene.click(scene.reveal("重新读取", f)); scene.settledFrame()
            assertEquals(listOf(old, old), f.refreshes)
            assertEquals(2, old.refreshAttempts.get())
            assertTrue(f.normalLoads.isEmpty(), "Both ADVANCED controls must use the same page-owned handle.")

            scene.click(scene.tab(SettingsDestination.CHAT))
            assertFalse(old.current())
            onUi { f.state.proxyChecksOwner.refresh(capturedOldPage) }
            assertEquals(2, old.refreshAttempts.get(), "A retained callback cannot refresh after navigation.")
            scene.settledFrame()
            scene.click(scene.tab(SettingsDestination.ADVANCED)); scene.settledFrame()
            val fresh = f.calls.last()
            assertEquals(2, f.calls.size)
            onUi { f.state.proxyChecksOwner.refresh(capturedOldPage) }
            assertEquals(2, old.refreshAttempts.get())
            assertEquals(0, fresh.refreshAttempts.get(), "An old callback cannot be redirected to a new owner.")
            scene.click(scene.reveal("重新读取", f)); scene.settledFrame()
            assertEquals(listOf(old, old, fresh), f.refreshes)
            assertEquals(1, fresh.refreshAttempts.get())
            assertTrue(f.normalLoads.isEmpty())

            scene.click(scene.tab(SettingsDestination.PROFILE)); scene.settledFrame()
            assertEquals(listOf(SettingsSection.PROFILE), f.normalLoads)
            scene.click(scene.description("刷新")); scene.settledFrame()
            assertEquals(listOf(SettingsSection.PROFILE, SettingsSection.PROFILE), f.normalLoads,
                "Other sections retain the original ordinary load and refresh path.")
            assertEquals(listOf(old, old, fresh), f.refreshes)
        }
    }

    @Test
    fun retiredProxyRowsAndConfirmationCannotSubmitBeforeOrAfterReentry() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.settledFrame(); val old = f.calls.single(); f.publish(old, 71); scene.settledFrame()
            scene.reveal("不使用代理", f); val direct = scene.captureAction("不使用代理")
            scene.reveal("beta.invalid:443", f); val select = scene.captureAction("beta.invalid:443")
            scene.reveal("删除 beta.invalid:443", f); val startRemoval = scene.captureAction("删除 beta.invalid:443")
            scene.click(scene.reveal("删除 beta.invalid:443", f)); scene.settledFrame()
            scene.reveal("删除", f); val confirmRemoval = scene.captureAction("删除")

            scene.click(scene.tab(SettingsDestination.CHAT))
            assertFalse(old.current(), "Navigation retires the old page before another frame.")
            onUi { direct.semantics(); select.semantics(); startRemoval.semantics(); confirmRemoval.semantics() }
            assertTrue(f.enabledIds.isEmpty()); assertTrue(f.removedIds.isEmpty())
            scene.settledFrame(); scene.click(scene.tab(SettingsDestination.ADVANCED)); scene.settledFrame()
            assertEquals(2, f.calls.size)
            onUi { direct.application(); select.application(); startRemoval.application(); confirmRemoval.application() }
            scene.settledFrame()
            assertTrue(f.enabledIds.isEmpty()); assertTrue(f.removedIds.isEmpty())
            assertTrue(scene.texts("删除选中的代理配置？").isEmpty(), "The old delete entry must not reopen a new-page dialog.")

            scene.click(scene.reveal("beta.invalid:443", f)); scene.settledFrame()
            scene.click(scene.reveal("不使用代理", f)); scene.settledFrame()
            assertEquals(listOf(102, null), f.enabledIds)
            scene.click(scene.reveal("删除 beta.invalid:443", f)); scene.settledFrame()
            scene.click(scene.reveal("删除", f)); scene.settledFrame()
            assertEquals(listOf(102), f.removedIds, "The new page's real confirmation remains functional.")
        }
    }

    @Test
    fun retiredValidAddFormCannotSubmitToAReenteredPage() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.settledFrame(); val old = f.calls.single(); f.publish(old, 72); scene.settledFrame()
            scene.click(scene.reveal("添加代理", f)); scene.settledFrame()
            scene.typeIntoField("服务器", "synthetic.invalid", f)
            scene.typeIntoField("端口", "1080", f)
            scene.reveal("保存代理", f); val save = scene.captureAction("保存代理")
            scene.click(scene.reveal("保存代理", f)); scene.settledFrame()
            assertEquals(1, f.added.size)
            assertEquals("synthetic.invalid", f.added.single().server)
            assertEquals(1080, f.added.single().port)

            scene.click(scene.tab(SettingsDestination.CHAT))
            assertFalse(old.current())
            onUi { save.semantics() }
            assertEquals(1, f.added.size, "The old save is rejected synchronously after leaving.")
            scene.settledFrame(); scene.click(scene.tab(SettingsDestination.ADVANCED)); scene.settledFrame()
            onUi { save.application() }; scene.settledFrame()
            assertEquals(1, f.added.size, "The old save cannot target a new page with the same parent token.")
        }
    }

    @Test
    fun allStatesAndActionsRemainReachableAcrossThemesDensityAndViewport() {
        for ((width, height) in listOf(1000 to 700, 350 to 300))
            for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) for (density in listOf(1f, 1.25f)) {
                val f = Fixture(width, height, density, mode)
                f.scene().use { scene ->
                    scene.settledFrame(); val call = f.calls.single(); f.publish(call, 20); scene.settledFrame()
                    scene.reveal("alpha.invalid:1080", f)
                    scene.reveal("正在检测…… · 当前连接：已连接", f)
                    scene.settledFrame().save("selected-${width}x$height-$mode-$density", f)
                    scene.reveal("beta.invalid:443", f)
                    scene.reveal("延迟 125 ms", f)
                    scene.click(scene.reveal("beta.invalid:443", f)); scene.settledFrame()
                    assertEquals(listOf<Int?>(102), f.enabledIds)
                    scene.reveal("web.invalid:80", f)
                    scene.reveal("未检测", f)
                    scene.reveal("delta.invalid:1080", f)
                    scene.reveal("不可用", f)
                    scene.reveal("error.invalid:1080", f)
                    scene.reveal("未检测（检查未完成）", f)
                    scene.settledFrame().save("bottom-${width}x$height-$mode-$density", f)
                    assertEquals(1, f.calls.size)
                    assertTrue(scene.texts("检测代理").isEmpty(), "Checking is automatic, with no replacement button.")
                }
            }
    }

    @Test
    fun deleteConfirmationRevalidatesTheCompleteProxyBeforeDispatch() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.settledFrame(); f.publish(f.calls.single(), 31); scene.settledFrame()
            scene.click(scene.reveal("删除 beta.invalid:443", f)); scene.settledFrame()
            scene.reveal("删除选中的代理配置？", f)
            onUi { f.update { it.copy(proxies = it.proxies.toMutableList().also { rows ->
                rows[1] = rows[1].copy(server = "replacement.invalid")
            }) } }
            scene.settledFrame()
            scene.click(scene.reveal("删除", f)); scene.settledFrame()
            assertTrue(f.removedIds.isEmpty(), "Replacing a same-id proxy invalidates the captured confirmation.")
            scene.click(scene.reveal("删除 replacement.invalid:443", f)); scene.settledFrame()
            scene.click(scene.reveal("删除", f)); scene.settledFrame()
            assertEquals(listOf(102), f.removedIds)
        }
    }

    private class Call(val parent: SettingsToken, val current: () -> Boolean,
        val opened: (SettingsProxyChecksToken?) -> Unit) {
        val closes = AtomicInteger()
        val refreshAttempts = AtomicInteger()
    }

    private class Fixture(width: Int = 1000, val height: Int = 700, val density: Float = 1f,
        val mode: ColorSchemeMode = ColorSchemeMode.Light) {
        var width by mutableStateOf(width)
        var active by mutableStateOf(true)
        var mounted by mutableStateOf(true)
        var reopenOnClose = false
        var closeCount = 0
        val calls = mutableListOf<Call>()
        val enabledIds = mutableListOf<Int?>()
        val removedIds = mutableListOf<Int>()
        val added = mutableListOf<SettingsProxyDraft>()
        val refreshes = mutableListOf<Call>()
        val normalLoads = mutableListOf<SettingsSection>()
        val state = SettingsWorkspaceState().apply { destination = SettingsDestination.ADVANCED; detailOpen = true }
        var runtime by mutableStateOf(RuntimeSnapshot(configPath = "synthetic/settings.properties", generation = 17,
            status = ClientStatus(connection = ConnectionPhase.READY), chats = ChatSnapshot(),
            settings = SettingsSnapshot(token = SettingsToken(17, 3), enabled = true,
                loaded = setOf(SettingsSection.ADVANCED), proxies = proxies)))
        val actions = SettingsActions(
            proxyChecks = SettingsProxyChecksActions(open = { parent, current, opened ->
                val call = Call(parent, current, opened).also(calls::add)
                object : SettingsProxyChecksLoad {
                    override fun close() { call.closes.incrementAndGet() }
                    override fun refresh() {
                        call.refreshAttempts.incrementAndGet()
                        if (current()) {
                            refreshes += call
                            update { it.copy(proxyChecks = SettingsProxyChecksSnapshot()) }
                        }
                    }
                }
            }),
            load = { _, section -> normalLoads += section },
            enableProxy = { _, id -> enabledIds += id }, removeProxy = { _, id -> removedIds += id },
            addProxy = { _, draft -> added += draft },
        )
        val downloads = object : DownloadPreferencesAccess {
            private var value = SettingsDownloadPreferences()
            override suspend fun read(): DownloadPreferencesRead = DownloadPreferencesRead.Value(value)
            override suspend fun write(expected: SettingsDownloadPreferences, desired: SettingsDownloadPreferences): DownloadPreferencesWrite {
                if (expected != value) return DownloadPreferencesWrite.CONFLICT
                value = desired
                return DownloadPreferencesWrite.SAVED
            }
        }

        fun update(transform: (SettingsSnapshot) -> SettingsSnapshot) {
            runtime = runtime.copy(settings = transform(runtime.settings))
        }

        fun ack(call: Call, token: SettingsProxyChecksToken?) {
            Thread { call.opened(token) }.apply { start(); join() }
        }

        fun publish(call: Call, owner: Long): SettingsProxyChecksToken {
            val token = SettingsProxyChecksToken(call.parent, owner)
            ack(call, token)
            writeChecks(token, listOf(SettingsProxyCheckStatus.Checking, SettingsProxyCheckStatus.Available(125),
                SettingsProxyCheckStatus.NotTested, SettingsProxyCheckStatus.Unavailable, SettingsProxyCheckStatus.NotTested))
            return token
        }

        fun writeChecks(token: SettingsProxyChecksToken, states: List<SettingsProxyCheckStatus>) {
            val write = { update { snapshot -> snapshot.copy(proxyChecks = SettingsProxyChecksSnapshot(token, 1,
                snapshot.proxies.mapIndexed { index, proxy -> SettingsProxyCheckRow(index, proxy, states[index],
                    if (index == 4) SettingsError.REQUEST_FAILED else null) })) } }
            if (SwingUtilities.isEventDispatchThread()) write() else SwingUtilities.invokeAndWait(write)
        }

        fun scene(maxWidth: Int = width) = ComposePixelScene((maxWidth * density).roundToInt(), (height * density).roundToInt()) {
            val theme = remember { ThemeController(mode) }
            val preferences = remember { SettingsPreferences(theme = mode) }
            CompositionLocalProvider(LocalDensity provides Density(density), LocalSettingsPreferences provides preferences,
                LocalDownloadPreferencesAccess provides downloads,
                LocalDownloadDestinationRootsResolver provides { _, _ -> DownloadDestinationRoots(
                    java.nio.file.Path.of("C:/Synthetic/Downloads/nngGram"), java.nio.file.Path.of("C:/Synthetic/Account/downloads-temp/user-1")) },
                LocalDesktopChatLayout provides desktopChatLayout(width.dp, height.dp),
                LocalSettingsSecurityConfirmationHost provides InlineConfirmation) {
                MiuixTheme(controller = theme) {
                    Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
                        Box(Modifier.requiredSize(width.dp, height.dp)) {
                            if (mounted) SettingsWorkspace(runtime, actions, ChatFeatureActions(), preferences, {}, ChatFolderPreferences(), {},
                                onClose = {
                                    closeCount++
                                    active = false
                                    if (reopenOnClose) active = true
                                }, onOpenConfig = {}, state = state, active = active)
                        }
                    }
                }
            }
        }
    }

    /** Render the identical dialog body in-scene; production still uses the Miuix native WindowDialog. */
    private object InlineConfirmation : SettingsSecurityConfirmationHost {
        @Composable override fun Content(show: Boolean, title: String?, summary: String?, maxWidth: Dp,
            onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
            if (show) Column(Modifier.fillMaxWidth().widthIn(max = maxWidth).heightIn(max = 260.dp)
                .chatSquircleBackground(DialogDefaults.backgroundColor(), 24.dp).padding(12.dp)) {
                title?.let { Text(it) }; content()
            }
        }
    }

    private fun ComposePixelScene.texts(label: String) = unmergedSemanticsBounds { config ->
        config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label }
    }.filter { it.width > 0 && it.height > 0 }
    private fun ComposePixelScene.hasText(label: String) = texts(label).isNotEmpty()
    private fun ComposePixelScene.description(label: String) = assertNotNull(semanticsBounds {
        label in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty() ||
            (it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == label } &&
                it.getOrNull(SemanticsActions.OnClick) != null)
    }.firstOrNull(), label)
    private class CapturedControlClick(val semantics: () -> Boolean, val application: () -> Unit)

    private fun ComposePixelScene.captureAction(label: String): CapturedControlClick {
        var captured: CapturedControlClick? = null
        semanticsBounds { config ->
            val action = config.getOrNull(SemanticsActions.OnClick)?.action
            if (config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label } &&
                config.getOrNull(SemanticsProperties.Disabled) == null && action != null) {
                captured = CapturedControlClick(action, freezeApplicationClick(action, label))
                true
            } else false
        }
        return assertNotNull(captured, "The actual enabled control must expose its callback: $label")
    }

    /**
     * Foundation 1.12.1's actual OnClick semantics is an invokedynamic wrapper over a
     * ClickableNode. Freeze its current application callback while the node is attached:
     * after navigation the semantics wrapper tries to play a sound through a detached node.
     * Any changed runtime shape fails explicitly rather than substituting a test guard.
     */
    private fun freezeApplicationClick(semantics: () -> Boolean, label: String): () -> Unit {
        val wrapper = semantics.javaClass
        val nodeType = Class.forName("androidx.compose.foundation.AbstractClickableNode", false, wrapper.classLoader)
        check(wrapper.isHidden && wrapper.isSynthetic && wrapper.nestHost == nodeType &&
            wrapper.name.startsWith("androidx.compose.foundation.AbstractClickableNode\$\$Lambda") &&
            wrapper.interfaces.singleOrNull() == Function0::class.java) {
            "Unexpected Foundation semantics wrapper for $label: ${wrapper.name}"
        }
        val capture = wrapper.declaredFields.singleOrNull()
            ?: error("Expected one Foundation node capture for $label")
        check(capture.name == "arg\$1" && capture.type == nodeType &&
            java.lang.reflect.Modifier.isPrivate(capture.modifiers) && java.lang.reflect.Modifier.isFinal(capture.modifiers) &&
            capture.trySetAccessible()) { "Unexpected Foundation node capture for $label" }
        val node = capture.get(semantics) ?: error("Missing Foundation node for $label")
        val clickableType = Class.forName("androidx.compose.foundation.ClickableNode", false, wrapper.classLoader)
        check(node.javaClass == clickableType && nodeType.isInstance(node) &&
            clickableType.getMethod("isAttached").invoke(node) == true) { "Expected an attached ClickableNode for $label" }
        val field = nodeType.getDeclaredField("onClick")
        val signature = field.genericType as? java.lang.reflect.ParameterizedType
            ?: error("Expected a parameterized Foundation onClick for $label")
        check(field.type == Function0::class.java && java.lang.reflect.Modifier.isPrivate(field.modifiers) &&
            !java.lang.reflect.Modifier.isStatic(field.modifiers) && signature.rawType == Function0::class.java &&
            signature.actualTypeArguments.singleOrNull() == Unit::class.java &&
            field.trySetAccessible()) { "Unexpected Foundation onClick field for $label" }
        val application = field.get(node) as? Function0<*> ?: error("Missing actual application onClick for $label")
        return { check(application.invoke() === Unit) { "Unexpected application onClick return for $label" } }
    }
    private fun ComposePixelScene.typeIntoField(label: String, value: String, f: Fixture) {
        val labelBounds = reveal(label, f)
        val field = assertNotNull(unmergedSemanticsBounds { it.getOrNull(SemanticsProperties.EditableText) != null }
            .firstOrNull { it.overlaps(labelBounds) }, "The actual editable field must contain $label")
        click(field.intersect(labelBounds)); settledFrame(2, 16)
        for (character in value) {
            keyStroke(KeyEvent.getExtendedKeyCodeForChar(character.code), character)
            settledFrame(1, 16)
        }
        settledFrame(4, 16)
    }
    private fun ComposePixelScene.tab(destination: SettingsDestination) = assertNotNull(semanticsBounds {
        it.getOrNull(SemanticsProperties.Role) == Role.Tab &&
            it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == destination.title }
    }.firstOrNull(), destination.title)
    private fun ComposePixelScene.click(bounds: Rect) {
        moveTo(bounds.center.x, bounds.center.y); pressAt(bounds.center.x, bounds.center.y); releaseAt(bounds.center.x, bounds.center.y)
    }
    @OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
    private fun ComposePixelScene.naturalTextBounds(label: String): List<Rect> {
        val result = mutableListOf<Rect>()
        onUi {
            val field = ComposePixelScene::class.java.getDeclaredField("scene")
            check(field.type == ImageComposeScene::class.java && field.trySetAccessible())
            val scene = field.get(this) as ImageComposeScene
            fun visit(node: SemanticsNode) {
                if (node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label }) {
                    val position = node.positionInRoot; val size = node.size
                    result += Rect(position.x, position.y, position.x + size.width, position.y + size.height)
                }
                node.children.forEach(::visit)
            }
            scene.semanticsOwners.forEach { visit(it.unmergedRootSemanticsNode) }
        }
        return result
    }
    private fun ComposePixelScene.reveal(label: String, f: Fixture): Rect =
        reveal(f, naturalCandidates = { naturalTextBounds(label) }) { texts(label) }
    private fun ComposePixelScene.revealTab(destination: SettingsDestination, f: Fixture): Rect = reveal(f, header = false) {
        semanticsBounds { it.getOrNull(SemanticsProperties.Role) == Role.Tab &&
            it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == destination.title } }
    }

    private fun ComposePixelScene.reveal(f: Fixture, header: Boolean = true,
        naturalCandidates: (() -> List<Rect>)? = null, candidates: () -> List<Rect>,
    ): Rect {
        val top = if (header) 64f * f.density else 0f
        val bottom = f.height * f.density
        fun visible() = candidates().firstOrNull { clipped ->
            clipped.width > 0 && clipped.height >= 8 * f.density && clipped.top >= top && clipped.bottom <= bottom - 1 &&
                (naturalCandidates?.invoke()?.any { full ->
                    kotlin.math.abs(full.left - clipped.left) <= .75f && kotlin.math.abs(full.top - clipped.top) <= .75f &&
                        kotlin.math.abs(full.right - clipped.right) <= .75f && kotlin.math.abs(full.bottom - clipped.bottom) <= .75f
                } ?: true)
        }
        var direction = 1; var step = 8f * f.density; var previous = 0
        val trace = ArrayDeque<String>()
        repeat(180) { attempt ->
            visible()?.let { return it }
            // Clipped semantics disappear outside the viewport. Natural attached bounds
            // still identify which way to wheel; they never satisfy visible() by themselves.
            val target = naturalCandidates?.invoke()?.firstOrNull { it.width > 0 && it.height > 0 }
                ?: candidates().firstOrNull { it.width > 0 && it.height > 0 }
            var position = 0f; var maximum = 0f
            val scrollers = semanticsBounds { config -> config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.let {
                position = it.value(); maximum = it.maxValue()
            }; config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
            direction = if (target != null) { if (target.top < top) -1 else 1 }
                else if (position <= .5f) 1 else if (position >= maximum - .5f) -1 else direction
            if (target == null) step = 8f * f.density
            else if (previous != 0 && previous != direction) step = (step / 2).coerceAtLeast(.02f)
            previous = direction
            val x = if (f.width >= 760 && header) f.width * f.density * .78f else f.width * f.density / 2f
            val scroll = scrollers.lastOrNull { it.left <= x && it.right >= x && it.height > 20 }
            val y = scroll?.center?.y ?: bottom / 2f
            val delta = direction * step
            trace.addLast("step=$attempt viewport=$top..$bottom target=$target " +
                "scroll=$scroll allScrollers=$scrollers position=$position/$maximum pointer=($x,$y) delta=$delta")
            if (trace.size > 16) trace.removeFirst()
            scrollAt(x, y, deltaY = delta)
            // Finish the actual smooth wheel movement before deciding to reverse or halve the step.
            settledFrame()
        }
        return assertNotNull(visible(), "Actual pointer scrolling must expose $targetDescription at ${f.width}x${f.height}: ${candidates()}; last steps=$trace")
    }

    private fun ComposePixelFrame.save(name: String, f: Fixture) {
        val image = BufferedImage((f.width * f.density).roundToInt(), (f.height * f.density).roundToInt(), BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) for (x in 0 until image.width) image.setRGB(x, y, this[x, y])
        val target = File("build/settings-workspace-validation/proxy-checks/$name.png")
        target.parentFile.mkdirs()
        assertTrue(ImageIO.write(image, "png", target))
    }

    private fun onUi(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeAndWait(block)
    }

    companion object {
        private const val targetDescription = "the requested settings control"
        private val proxies = listOf(
            SettingsProxy(101, "alpha.invalid", 1080, SettingsProxyType.SOCKS5, true),
            SettingsProxy(102, "beta.invalid", 443, SettingsProxyType.HTTP, false),
            SettingsProxy(103, "web.invalid", 80, SettingsProxyType.WEB, false),
            SettingsProxy(104, "delta.invalid", 1080, SettingsProxyType.MTPROTO, false),
            SettingsProxy(105, "error.invalid", 1080, SettingsProxyType.SOCKS5, false),
        )
    }
}
