package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.AccountProfile
import com.badnng.moe.chat.ChatItem
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.ChatToken
import com.badnng.moe.settings.SettingsSnapshot
import com.badnng.moe.settings.SettingsToken
import com.badnng.moe.status.AuthorizationPhase
import com.badnng.moe.status.ClientStatus
import com.badnng.moe.status.ConnectionPhase
import com.badnng.moe.status.EnginePhase
import com.badnng.moe.status.ProxyStatus
import com.badnng.moe.status.RuntimeSnapshot
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.math.roundToInt
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.layout.DialogDefaults

/** Real App, settings workspace and account menu; the window dialog alone is hosted inline. */
class SettingsThemeAppIntegrationUiTest {
    @Test fun actualSettingsInputsObserveEventsAndSaveOnlyAfterConfirmationAcrossSizes() {
        for ((width, height) in listOf(1040 to 700, 350 to 300))
            for (density in listOf(1f, 1.25f)) for (systemDark in listOf(false, true)) {
                val f = Fixture(width, height, density, systemDark)
                try {
                    f.scene().use { scene ->
                        scene.settledFrame()
                        assertEquals(systemDark, f.observed.last())
                        scene.openChatSettings(f)
                        scene.clickText(if (systemDark) "深色" else "浅色", f, Region.DETAIL)
                        assertTrue(f.store.attempts.isEmpty())
                        val opposite = if (systemDark) "浅色" else "深色"
                        scene.clickText(opposite, f, Region.DETAIL)
                        scene.settledFrame().save("actual-settings-${width}x$height-$density-$systemDark")
                        assertTrue(scene.hasText("关闭并切换"))
                        scene.clickText("取消", f, Region.ALL)
                        assertTrue(f.store.attempts.isEmpty())
                        scene.clickText(opposite, f, Region.DETAIL)
                        scene.clickText("关闭并切换", f, Region.ALL)
                        scene.settledFrame()
                        assertEquals(1, f.store.attempts.size)
                        val saved = readSettingsPreferences(f.store.path)
                        assertEquals(false, saved.themeStatePreferences().followSystem)
                        assertEquals(!systemDark, saved.themeStatePreferences().manualDark)
                        assertEquals(SettingsThemeFamily.MONET, saved.themeStatePreferences().family)
                        assertEquals(SettingsAccentColor.GREEN, saved.accentColor)
                        assertEquals(SettingsWallpaper.AUTOMATIC, saved.wallpaper)
                        assertEquals(!systemDark, f.observed.last())
                        f.onUi { f.source.value = !systemDark }; scene.settledFrame()
                        f.onUi { f.source.value = systemDark }; scene.settledFrame()
                        assertEquals(!systemDark, f.observed.last(), "Explicit theme ignores later source events")
                        scene.clickText("跟随系统", f, Region.DETAIL)
                        scene.settledFrame()
                        assertTrue(readSettingsPreferences(f.store.path).themeStatePreferences().followSystem)
                        assertEquals(systemDark, f.observed.last())
                        val writes = f.store.attempts.size
                        f.onUi { f.source.value = !systemDark }; scene.settledFrame()
                        assertEquals(!systemDark, f.observed.last())
                        assertEquals(writes, f.store.attempts.size, "System events must never rewrite settings")
                    }
                } finally { f.close() }
            }
    }

    @Test fun actualAppMiuixWallpaperMenuAndWindowObserverConsumeOneEffectiveBrightness() {
        val f = Fixture(1040, 700, 1f, false)
        try {
            f.scene().use { scene ->
                scene.settledFrame()
                assertEquals(false, f.observed.last())
                assertEquals(false, f.consumers["miuix"])
                assertEquals(false, f.consumers["wallpaper"])
                f.onUi { f.source.value = true }; scene.settledFrame()
                assertEquals(true, f.observed.last())
                assertEquals(true, f.consumers["miuix"])
                assertEquals(true, f.consumers["wallpaper"])
                f.onUi { f.source.value = null }; scene.settledFrame()
                scene.ensureAccountMenuTrigger(f)
                scene.clickDescription("打开账号菜单", f)
                scene.clickText("夜间模式", f, Region.MENU)
                // The single-column list owns the menu, so re-enter the real selected
                // chat before asserting its actual wallpaper consumer again.
                scene.clickDescription("关闭账号菜单", f)
                scene.returnToSelectedChat(f)
                assertEquals(700L, f.runtime?.chats?.selected?.id)
                scene.settledFrame()
                assertEquals(false, f.observed.last())
                assertEquals(false, f.consumers["miuix"])
                assertEquals(false, f.consumers["wallpaper"])
                assertTrue(f.store.attempts.isEmpty())
                f.onUi { f.source.value = true }; scene.settledFrame()
                assertEquals(true, f.observed.last())
                assertEquals(true, f.consumers["miuix"])
                assertEquals(true, f.consumers["wallpaper"])
                assertTrue(f.store.attempts.isEmpty())
            }
        } finally { f.close() }
    }

    @Test fun actualAppUnknownChoiceRetainsFollowAndKnownSourceResumes() {
        val f = Fixture(1040, 700, 1f, true)
        try {
            f.scene().use { scene ->
                scene.settledFrame()
                scene.openChatSettings(f)
                f.onUi { f.source.value = null }; scene.settledFrame()
                assertEquals(true, f.observed.last(), "Losing an observation keeps the last real appearance")
                scene.clickText("浅色", f, Region.DETAIL)
                scene.settledFrame()
                assertEquals(false, f.observed.last())
                assertTrue(f.store.attempts.isEmpty(), "The persisted fallback already matches; this only repaints")
                scene.clickText("深色", f, Region.DETAIL)
                scene.settledFrame()
                assertEquals(true, f.observed.last())
                val saved = readSettingsPreferences(f.store.path).themeStatePreferences()
                assertTrue(saved.followSystem)
                assertTrue(saved.manualDark)
                assertFalse(scene.hasText("关闭并切换"))
                val writes = f.store.attempts.size
                f.onUi { f.source.value = false }; scene.settledFrame()
                assertEquals(false, f.observed.last())
                f.onUi { f.source.value = true }; scene.settledFrame()
                assertEquals(true, f.observed.last())
                assertEquals(writes, f.store.attempts.size)
            }
        } finally { f.close() }
    }

    @Test fun actualAppRejectedSaveAndStalePageConfirmationCannotChangeTheme() {
        val f = Fixture(1040, 700, 1f, false)
        try {
            f.scene().use { scene ->
                scene.settledFrame()
                scene.openChatSettings(f)
                val advanced = scene.captureTextClick("高级设置")
                val chat = scene.captureTextClick("聊天设置")
                scene.clickText("深色", f, Region.DETAIL)
                val stale = f.captureDialogConfirm()
                f.onUi { advanced(); chat(); stale() }
                scene.settledFrame()
                assertTrue(f.store.attempts.isEmpty(), "Parent destination ABA revokes the real pending callback")
                assertEquals(false, f.observed.last())
                scene.clickText("深色", f, Region.DETAIL)
                f.onUi { f.store.allow = false }
                scene.clickText("关闭并切换", f, Region.ALL)
                scene.settledFrame()
                assertEquals(1, f.store.attempts.size)
                assertFalse(Files.exists(f.store.path))
                assertEquals(false, f.observed.last())
                assertTrue(scene.hasText("设置未能保存到本机，请重试。"))
                f.onUi { f.store.allow = true }
                scene.clickText("深色", f, Region.DETAIL)
                scene.clickText("关闭并切换", f, Region.ALL)
                scene.settledFrame()
                assertEquals(2, f.store.attempts.size)
                assertTrue(readSettingsPreferences(f.store.path).themeStatePreferences().manualDark)
                assertEquals(true, f.observed.last())
            }
        } finally { f.close() }
    }

    @Test fun actualObservedSystemAbaRevokesOldConfirmationAndCannotConsumeANewOne() {
        val f = Fixture(1040, 700, 1f, false)
        try {
            f.scene().use { scene ->
                scene.settledFrame()
                scene.openChatSettings(f)
                scene.clickText("深色", f, Region.DETAIL)
                val original = f.captureDialog()
                val stale = original.confirm
                f.onUi { f.source.value = true }; scene.settledFrame()
                f.onUi { f.source.value = false }; scene.settledFrame()
                f.onUi { stale() }; scene.settledFrame()
                assertTrue(f.store.attempts.isEmpty())
                scene.clickText("深色", f, Region.DETAIL)
                assertTrue(f.captureDialog().pending !== original.pending, "A new confirmation must own a distinct pending identity")
                f.onUi { stale() }; scene.settledFrame()
                assertTrue(scene.hasText("关闭并切换"), "An older callback must not consume the newer pending identity")
                assertTrue(f.store.attempts.isEmpty())
                scene.clickText("关闭并切换", f, Region.ALL)
                assertEquals(1, f.store.attempts.size)
            }
        } finally { f.close() }
    }

    @Test fun actualAccountMenuConfirmationIsOwnedByTheSpecificMenuOpening() {
        val f = Fixture(1040, 700, 1f, false)
        try {
            f.scene().use { scene ->
                scene.settledFrame()
                scene.ensureAccountMenuTrigger(f)
                val toggleMenu = scene.captureDescriptionClick("打开账号菜单")
                scene.clickDescription("打开账号菜单", f)
                scene.clickText("夜间模式", f, Region.MENU)
                val stale = f.captureDialogConfirm()
                f.onUi { toggleMenu(); toggleMenu(); stale() }
                scene.settledFrame()
                assertTrue(f.store.attempts.isEmpty(), "A close/reopen invalidates the prior menu confirmation")
                assertEquals(false, f.observed.last())
                scene.clickText("夜间模式", f, Region.MENU)
                scene.clickText("关闭并切换", f, Region.ALL)
                scene.settledFrame()
                assertEquals(true, f.observed.last())
                assertEquals(false, readSettingsPreferences(f.store.path).themeStatePreferences().followSystem)
            }
        } finally { f.close() }
    }

    @Test fun actualDiagnosticEntryUsesTheSameConfirmationAndLosesOwnershipWhenRouteChanges() {
        val f = Fixture(1040, 700, 1f, false, initialLive = false)
        try {
            f.scene().use { scene ->
                scene.settledFrame()
                scene.clickText("深色", f, Region.DETAIL)
                val stale = f.captureDialogConfirm()
                f.onUi { f.runtime = f.readyRuntime() }; scene.settledFrame()
                f.onUi { stale() }; scene.settledFrame()
                assertTrue(f.store.attempts.isEmpty())
                assertEquals(false, f.observed.last())
            }
        } finally { f.close() }
    }

    @Test fun pendingThemeConfirmationPreservesAnUnrelatedActualPreferenceChange() {
        val f = Fixture(1040, 700, 1f, false)
        try {
            f.scene().use { scene ->
                scene.settledFrame()
                scene.openChatSettings(f)
                scene.clickText("蓝色", f, Region.DETAIL)
                val changeAccent = scene.captureTextClick("紫色")
                val writes = f.store.attempts.size
                scene.clickText("深色", f, Region.DETAIL)
                val pending = f.captureDialog().pending
                // A previously bound preference action may complete while confirmation is pending.
                f.onUi { assertTrue(changeAccent()) }
                scene.settledFrame()
                val intermediate = readSettingsPreferences(f.store.path)
                assertEquals(SettingsAccentColor.PURPLE, intermediate.accentColor)
                assertTrue(intermediate.themeStatePreferences().followSystem)
                assertSame(pending, f.captureDialog().pending)
                scene.clickText("关闭并切换", f, Region.ALL)
                scene.settledFrame()
                val saved = readSettingsPreferences(f.store.path)
                assertFalse(saved.themeStatePreferences().followSystem)
                assertTrue(saved.themeStatePreferences().manualDark)
                assertEquals(SettingsAccentColor.PURPLE, saved.accentColor)
                assertEquals(SettingsWallpaper.AUTOMATIC, saved.wallpaper)
                assertEquals(SettingsSendKey.CTRL_ENTER, saved.sendKey)
                assertFalse(saved.stickerLoop)
                assertEquals(125, saved.interfaceScalePercent)
                assertEquals(SettingsChatFont(SettingsChatFontMode.SYSTEM_DEFAULT), saved.chatFont)
                assertEquals(writes + 2, f.store.attempts.size)
            }
        } finally { f.close() }
    }

    @Test fun disposedAppCannotAcceptItsPreviouslyBoundThemeConfirmation() {
        val f = Fixture(1040, 700, 1f, false)
        try {
            var stale: (() -> Unit)? = null
            f.scene().use { scene ->
                scene.settledFrame()
                scene.openChatSettings(f)
                scene.clickText("深色", f, Region.DETAIL)
                stale = f.captureDialogConfirm()
                assertTrue(f.store.attempts.isEmpty())
            }
            val captured = assertNotNull(stale)
            f.onUi { captured() }
            assertTrue(f.store.attempts.isEmpty(), "Disposed App ownership must reject its old confirmation")
            assertFalse(Files.exists(f.store.path))
        } finally { f.close() }
    }

    private class Source(value: Boolean?) : SettingsSystemThemeObservation {
        var value by mutableStateOf(value)
        @Composable override fun current() = value
    }

    private class Store(val path: Path) : SettingsPreferencesAccess {
        var allow = true
        val attempts = CopyOnWriteArrayList<SettingsPreferences>()
        private val initial = SettingsPreferences(accentColor = SettingsAccentColor.GREEN, wallpaper = SettingsWallpaper.AUTOMATIC,
            sendKey = SettingsSendKey.CTRL_ENTER, stickerLoop = false, interfaceScalePercent = 125,
            chatFont = SettingsChatFont(SettingsChatFontMode.SYSTEM_DEFAULT))
            .withThemeState(SettingsThemePreferences(true, false, SettingsThemeFamily.MONET))
        override fun load(): SettingsPreferences = if (Files.exists(path)) readSettingsPreferences(path) else initial
        override fun save(preferences: SettingsPreferences): Boolean {
            attempts += preferences
            return allow && writeSettingsPreferences(path, preferences)
        }
    }

    private class Fixture(val width: Int, val height: Int, val density: Float, systemDark: Boolean?, initialLive: Boolean = true) {
        private val root = Files.createTempDirectory("theme-app-ui-")
        val store = Store(root.resolve("preferences.properties"))
        val source = Source(systemDark)
        val observed = CopyOnWriteArrayList<Boolean>()
        val consumers = ConcurrentHashMap<String, Boolean>()
        private var dialogCallbacks: SettingsThemeConfirmationCallbacks? = null
        private val dialogProbe: (SettingsThemeConfirmationCallbacks) -> Unit = { dialogCallbacks = it }
        private val observer: (Boolean) -> Unit = { observed += it }
        private val consumer: (String, Boolean) -> Unit = { name, dark -> consumers[name] = dark }
        var runtime by mutableStateOf(if (initialLive) readyRuntime() else null)
        fun readyRuntime(): RuntimeSnapshot {
            val chat = ChatItem(700, "Synthetic theme chat")
            return RuntimeSnapshot(configPath = "synthetic/theme.properties", generation = 17, clientActive = true,
                status = ClientStatus(EnginePhase.INITIALIZED, AuthorizationPhase.READY, ConnectionPhase.READY),
                proxy = ProxyStatus.DIRECT, settings = SettingsSnapshot(token = SettingsToken(17), enabled = true),
                chats = ChatSnapshot(enabled = true, token = ChatToken(17, 1, chat.id), selected = chat,
                    account = AccountProfile(123, "Synthetic account"), knownChats = listOf(chat), items = listOf(chat),
                    messages = emptyList(), historyEnd = true, listEnd = true))
        }
        fun onUi(action: () -> Unit) {
            if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeAndWait(action)
        }
        fun captureDialog(): SettingsThemeConfirmationCallbacks =
            assertNotNull(dialogCallbacks, "The real App dialog must expose its current callbacks")
        fun captureDialogConfirm(): () -> Unit = captureDialog().confirm
        fun scene() = ComposePixelScene((width * density).roundToInt(), (height * density).roundToInt()) {
            CompositionLocalProvider(LocalDensity provides Density(density),
                LocalSettingsSystemThemeObservation provides source,
                LocalSettingsPreferencesAccess provides store,
                LocalSettingsSecurityConfirmationHost provides OfflineConfirmation,
                LocalSettingsThemeConsumerProbe provides consumer,
                LocalSettingsThemeConfirmationCallbacks provides dialogProbe,
                LocalAppThemeObserver provides observer) {
                // Avoid all other device preferences; the supplied store is isolated explicitly.
                App(runtime = runtime, persistUiPreferences = false, windowFocused = true)
            }
        }
        fun close() { root.toFile().deleteRecursively() }
    }

    private object OfflineConfirmation : SettingsSecurityConfirmationHost {
        @Composable override fun Content(show: Boolean, title: String?, summary: String?, maxWidth: Dp,
            onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
            if (show) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .25f)), contentAlignment = Alignment.Center) {
                Column(Modifier.widthIn(max = maxWidth).fillMaxWidth().padding(12.dp)
                    .chatSquircleBackground(DialogDefaults.backgroundColor(), 24.dp).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    title?.let { Text(it) }
                    content()
                }
            }
        }
    }

    private enum class Region { MENU, CATEGORY, DETAIL, ALL }
    private fun ComposePixelScene.openChatSettings(f: Fixture) {
        ensureAccountMenuTrigger(f)
        clickDescription("打开账号菜单", f)
        clickText("设置", f, Region.MENU)
        clickText("聊天设置", f, Region.CATEGORY)
    }

    /** Use the actual navigation and wait until the list's input tree is interactive. */
    private fun ComposePixelScene.ensureAccountMenuTrigger(f: Fixture) {
        var requestedBack = false
        repeat(80) {
            if (actionableDescription("打开账号菜单", f) != null) return
            if (!requestedBack) actionableDescription("返回聊天列表", f)?.let { back ->
                click(back)
                requestedBack = true
            }
            // Render one real Compose frame; do not assume the transition duration implies readiness.
            settledFrame(1, 16)
        }
        error("Neither an interactive account trigger nor a completed return to the chat list appeared")
    }

    private fun ComposePixelScene.actionableDescription(label: String, f: Fixture): Rect? {
        val width = f.width * f.density
        val height = f.height * f.density
        return semanticsBounds { config ->
            label in config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() &&
                config.getOrNull(SemanticsActions.OnClick)?.action != null &&
                config.getOrNull(SemanticsProperties.Disabled) == null
        }.firstOrNull { rect -> rect.width >= 8f && rect.height >= 8f &&
            rect.center.x in 1f..(width - 1f) && rect.center.y in 1f..(height - 1f) }
    }

    private fun ComposePixelScene.awaitActionableDescription(label: String, f: Fixture): Rect {
        repeat(80) {
            actionableDescription(label, f)?.let { return it }
            settledFrame(1, 16)
        }
        error("Actual App action was not attached and interactive: $label")
    }

    private fun ComposePixelScene.awaitAccountMenu(f: Fixture) {
        repeat(80) {
            val pane = semanticsBounds { it.getOrNull(SemanticsProperties.PaneTitle) == "账号菜单" }
                .any { it.width > 0f && it.height > 0f }
            if (pane && actionableDescription("关闭账号菜单", f) != null) return
            settledFrame(1, 16)
        }
        error("The actual account menu did not become interactive")
    }

    private fun ComposePixelScene.actionableText(label: String, f: Fixture): Rect? {
        val width = f.width * f.density
        val height = f.height * f.density
        return semanticsBounds { config ->
            config.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == label } &&
                config.getOrNull(SemanticsActions.OnClick)?.action != null &&
                config.getOrNull(SemanticsProperties.Disabled) == null
        }.firstOrNull { it.width >= 8f && it.height >= 8f && it.center.x in 0f..width && it.center.y in 2f..(height - 2f) }
    }

    /** Closing-menu animation can still consume input: retry the real row until the conversation owns input. */
    private fun ComposePixelScene.returnToSelectedChat(f: Fixture) {
        repeat(100) {
            if (actionableDescription("返回聊天列表", f) != null) return
            actionableText("Synthetic theme chat", f)?.let { click(it) }
            settledFrame(1, 16)
        }
        error("The actual selected chat did not become the interactive conversation")
    }

    private fun ComposePixelScene.hasText(label: String): Boolean = textBounds(label).isNotEmpty()
    private fun ComposePixelScene.textBounds(label: String): List<Rect> = semanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == label }
    }.filter { it.width > 0f && it.height > 0f }

    /** The smallest scrollable surface beneath the actual pointer supplies its current range. */
    private fun ComposePixelScene.scrollRangeAt(x: Float, y: Float): Pair<Float, Float>? {
        val ranges = mutableListOf<Pair<Float, Float>>()
        val bounds = semanticsBounds { config ->
            val range = config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
            if (range == null) false else {
                ranges += range.value() to range.maxValue()
                true
            }
        }
        return ranges.zip(bounds).filter { (_, rect) ->
            rect.width > 0f && rect.height > 0f && rect.contains(Offset(x, y))
        }.minByOrNull { (_, rect) -> rect.width * rect.height }?.first
    }

    private fun ComposePixelScene.clickText(label: String, f: Fixture, region: Region) {
        val width = f.width * f.density
        val height = f.height * f.density
        val x = when (region) {
            Region.MENU -> (120f * f.density).coerceAtMost(width * .5f)
            Region.CATEGORY -> width * .22f
            Region.DETAIL -> width * .75f
            Region.ALL -> width * .5f
        }
        val y = height * .6f
        // A fully clipped node has no usable bounds. Search from the real scroll origin
        // and then forward, using the current range instead of assuming its direction.
        for (delta in listOf(-8f, 8f)) {
            var stalls = 0
            for (attempt in 0 until 100) {
                val candidate = actionableText(label, f)
                if (candidate != null) { click(candidate); settledFrame(); return }
                val before = scrollRangeAt(x, y)
                if (before != null && (delta < 0f && before.first <= .5f ||
                        delta > 0f && before.first >= before.second - .5f)) {
                    // Layout/transition may still attach the target or update maxValue.
                    if (++stalls >= 3) break
                    settledFrame(1, 16)
                    continue
                }
                scrollAt(x, y, deltaY = delta)
                settledFrame(2, 32)
                val after = scrollRangeAt(x, y)
                if (before != null && after != null && abs(after.first - before.first) < .5f) stalls++ else stalls = 0
                if (stalls >= 3) break
            }
        }
        error("Could not reveal actual App control $label in $region")
    }

    private fun ComposePixelScene.clickDescription(label: String, f: Fixture) {
        click(awaitActionableDescription(label, f))
        settledFrame(1, 16)
        if (label == "打开账号菜单") awaitAccountMenu(f)
    }

    private fun ComposePixelScene.captureTextClick(label: String): () -> Boolean {
        var callback: (() -> Boolean)? = null
        semanticsBounds {
            if (it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == label })
                it.getOrNull(SemanticsActions.OnClick)?.action?.let { callback = it }
            false
        }
        return assertNotNull(callback, "Missing actual action for $label")
    }

    private fun ComposePixelScene.captureDescriptionClick(label: String): () -> Boolean {
        var callback: (() -> Boolean)? = null
        semanticsBounds {
            if (label in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty())
                it.getOrNull(SemanticsActions.OnClick)?.action?.let { callback = it }
            false
        }
        return assertNotNull(callback, "Missing actual action for $label")
    }

    private fun ComposePixelScene.click(rect: Rect) {
        moveTo(rect.center.x, rect.center.y); pressAt(rect.center.x, rect.center.y); releaseAt(rect.center.x, rect.center.y)
    }

    private fun ComposePixelFrame.save(name: String) {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, this[x, y])
        val file = Path.of("build/settings-system-theme-integration-recovery-draft/frames/$name.png").toFile()
        file.parentFile.mkdirs()
        assertTrue(ImageIO.write(image, "png", file))
    }
}
