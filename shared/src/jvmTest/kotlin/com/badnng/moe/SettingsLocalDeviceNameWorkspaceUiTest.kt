package com.badnng.moe

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.settings.*
import com.badnng.moe.status.RuntimeSnapshot
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities
import kotlin.test.*
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Exercises the actual workspace's navigation and security lease, with a real local file. */
class SettingsLocalDeviceNameWorkspaceUiTest {
    @Test fun realBackAndDestinationAbaRevokeTheOldSaveBeforeAnotherFrame() {
        for (kind in listOf("back", "destination")) Fixture().use { f -> f.scene().use { scene ->
            scene.settledFrame()
            scene.click(scene.actionBounds("编辑当前设备名称")); scene.settledFrame()
            val editor = assertNotNull(f.callbacks)
            val back = if (kind == "back") scene.actionBounds("返回隐私与安全") else null
            val chat = if (kind == "destination") scene.actionBounds("聊天设置") else null
            val privacy = if (kind == "destination") scene.actionBounds("隐私与安全") else null
            if (back != null) scene.click(back) else {
                scene.click(assertNotNull(chat)); scene.click(assertNotNull(privacy))
            }
            f.onUi { assertNotNull(editor.save)() }
            scene.settledFrame()
            assertEquals(0, f.attempts)
            assertEquals("Old", readSettingsPreferences(f.path).customDeviceModel)
        } }
    }

    @Test fun liveWitnessRevokedWithoutARecompositionPreventsTheActualFileSave() {
        Fixture().use { f -> f.scene().use { scene ->
            scene.settledFrame(); scene.click(scene.actionBounds("编辑当前设备名称")); scene.settledFrame()
            val editor = assertNotNull(f.callbacks)
            f.onUi {
                f.nativeOwner.set(false)
                assertNotNull(editor.save)()
            }
            assertEquals(0, f.attempts)
            assertEquals("Old", readSettingsPreferences(f.path).customDeviceModel)
        } }
    }

    private class Fixture : AutoCloseable {
        private val root = Files.createTempDirectory("device-name-workspace-")
        val path = root.resolve("ui-settings.properties")
        val state = SettingsWorkspaceState().apply { destination = SettingsDestination.PRIVACY; detailOpen = true }
        val nativeOwner = AtomicBoolean(true)
        var preferences by mutableStateOf(SettingsPreferences(customDeviceModel = "Old"))
        var callbacks: SettingsDeviceNameUiCallbacks? = null
        var attempts = 0
        private val token = SettingsSecurityToken(5, 7)
        private val security = SettingsSecuritySnapshot(token = token, section = SettingsSecuritySection.SESSIONS,
            sessions = listOf(SettingsSession(0, true, "Native", "App", "1", "OS", "1", 5, "", "", 2)))
        private val runtime = RuntimeSnapshot(configPath = "synthetic", generation = 5, settings = SettingsSnapshot(token = SettingsToken(5, 1),
            enabled = true, loaded = setOf(SettingsSection.PRIVACY), security = security), chats = ChatSnapshot())
        private val actions = SettingsActions(captureDeviceNameSecurityOwner = { owner, _ ->
            { owner == token && nativeOwner.get() }
        })
        init { assertTrue(writeSettingsPreferences(path, preferences)) }
        fun onUi(action: () -> Unit) {
            if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeAndWait(action)
        }
        fun scene() = ComposePixelScene(1100, 1200) {
            val theme = remember { ThemeController(ColorSchemeMode.Light) }
            CompositionLocalProvider(LocalDensity provides Density(1f), LocalSettingsDeviceNameUiCallbacks provides { callbacks = it },
                LocalSettingsSecurityConfirmationHost provides SilentConfirmationHost) {
                MiuixTheme(controller = theme) {
                    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                        SettingsWorkspace(runtime, actions, ChatFeatureActions(), preferences, {}, ChatFolderPreferences(), {},
                            onClose = {}, onOpenConfig = {}, state = state, onSaveDeviceName = { name, current, result ->
                                val saved = if (current()) {
                                    attempts++
                                    writeSettingsPreferences(path, preferences.copy(customDeviceModel = name)).also {
                                        if (it) preferences = preferences.copy(customDeviceModel = name)
                                    }
                                } else false
                                result(saved)
                            })
                    }
                }
            }
        }
        override fun close() { root.toFile().deleteRecursively() }
    }

    private object SilentConfirmationHost : SettingsSecurityConfirmationHost {
        @Composable override fun Content(show: Boolean, title: String?, summary: String?, maxWidth: Dp,
            onDismissRequest: () -> Unit, content: @Composable () -> Unit) = Unit
    }

    private fun ComposePixelScene.actionBounds(label: String): Rect = assertNotNull(semanticsBounds { config ->
        config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label } &&
            config.getOrNull(SemanticsActions.OnClick)?.action != null
    }.firstOrNull { it.width > 0f && it.height > 0f }, label)
    private fun ComposePixelScene.click(rect: Rect) {
        moveTo(rect.center.x, rect.center.y); pressAt(rect.center.x, rect.center.y); releaseAt(rect.center.x, rect.center.y)
    }
}
