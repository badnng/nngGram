package com.badnng.moe

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
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
import com.badnng.moe.settings.*
import com.badnng.moe.status.*
import java.nio.file.Files
import javax.swing.SwingUtilities
import kotlin.test.*
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.layout.DialogDefaults

/** App's actual preference bridge, workspace, Miuix controls and isolated file; only the dialog host is offline. */
class SettingsLocalDeviceNameAppUiTest {
    @Test fun actualAppSavesLatestPreferencesAndAStaleUnrelatedActionCannotRestoreTheOldName() {
        val root = Files.createTempDirectory("device-name-app-")
        try {
            val path = root.resolve("ui-settings.properties")
            val initial = SettingsPreferences(customDeviceModel = "Old", wallpaper = SettingsWallpaper.NIGHT)
            assertTrue(writeSettingsPreferences(path, initial))
            val store = object : SettingsPreferencesAccess {
                override fun load() = readSettingsPreferences(path)
                override fun save(preferences: SettingsPreferences) = writeSettingsPreferences(path, preferences)
            }
            val chat = ChatItem(700, "Synthetic chat")
            val security = SettingsSecuritySnapshot(token = SettingsSecurityToken(17, 4), section = SettingsSecuritySection.SESSIONS,
                sessions = listOf(SettingsSession(0, true, "Native Name", "App", "1", "OS", "1", 1, "", "", 1)))
            val runtime = RuntimeSnapshot(configPath = "synthetic", generation = 17, clientActive = true,
                status = ClientStatus(EnginePhase.INITIALIZED, AuthorizationPhase.READY, ConnectionPhase.READY),
                proxy = ProxyStatus.DIRECT, settings = SettingsSnapshot(token = SettingsToken(17), enabled = true,
                    loaded = setOf(SettingsSection.PRIVACY), security = security),
                chats = ChatSnapshot(enabled = true, token = ChatToken(17, 1, chat.id), selected = chat,
                    account = AccountProfile(123, "Synthetic"), knownChats = listOf(chat), items = listOf(chat),
                    messages = emptyList(), historyEnd = true, listEnd = true))
            val actions = SettingsActions(captureDeviceNameSecurityOwner = { token, target ->
                { token == security.token && target.matchesDevice(security.sessions.single()) }
            })
            var captured: SettingsSendKeyCallbacks? = null
            ComposePixelScene(1100, 1600) {
                CompositionLocalProvider(LocalDensity provides Density(1f), LocalSettingsPreferencesAccess provides store,
                    LocalSettingsSecurityConfirmationHost provides OfflineDialog,
                    LocalSettingsSendKeyCallbacks provides { captured = it }) {
                    Box(Modifier.fillMaxSize()) { App(runtime = runtime, persistUiPreferences = false,
                        windowFocused = true, settingsActions = actions) }
                }
            }.use { scene ->
                scene.settledFrame(); scene.clickDescription("打开账号菜单")
                scene.clickText("设置"); scene.clickText("聊天设置")
                scene.clickText("按 Ctrl+Enter 键发送消息")
                assertEquals(SettingsSendKey.CTRL_ENTER, readSettingsPreferences(path).sendKey)
                scene.clickText("按 Enter 键发送消息")
                assertEquals(SettingsSendKey.ENTER, readSettingsPreferences(path).sendKey)
                val oldActions = assertNotNull(captured, "The actual send-key row callbacks were not composed")
                scene.clickText("隐私与安全"); scene.clickText("编辑当前设备名称")
                onUi { oldActions.ctrlEnter() }; scene.settledFrame()
                assertEquals(SettingsSendKey.CTRL_ENTER, readSettingsPreferences(path).sendKey)
                assertEquals("Old", readSettingsPreferences(path).customDeviceModel)
                val field = assertNotNull(scene.semanticsBounds { it.getOrNull(SemanticsProperties.EditableText) != null }.lastOrNull())
                scene.replaceDeviceNameEditorUsingKeys(field, "Office")
                scene.clickText("保存"); scene.settledFrame()
                assertEquals("Office", readSettingsPreferences(path).customDeviceModel)
                assertEquals(SettingsWallpaper.NIGHT, readSettingsPreferences(path).wallpaper)
                assertEquals(SettingsSendKey.CTRL_ENTER, readSettingsPreferences(path).sendKey)
                onUi { oldActions.enter() }
                scene.settledFrame()
                assertEquals("Office", readSettingsPreferences(path).customDeviceModel)
                assertEquals(SettingsSendKey.ENTER, readSettingsPreferences(path).sendKey)
                assertEquals("Native Name", security.sessions.single().deviceModel)
            }
        } finally { root.toFile().deleteRecursively() }
    }

    private object OfflineDialog : SettingsSecurityConfirmationHost {
        @Composable override fun Content(show: Boolean, title: String?, summary: String?, maxWidth: Dp,
            onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
            if (show) {
                Column(Modifier.fillMaxWidth().padding(12.dp).widthIn(max = maxWidth)
                    .chatSquircleBackground(DialogDefaults.backgroundColor(), 24.dp).padding(12.dp)) {
                    title?.let { Text(it) }; content()
                }
            }
        }
    }

    private fun ComposePixelScene.findText(label: String): Rect? = semanticsBounds { config ->
        config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label } &&
            config.getOrNull(SemanticsActions.OnClick)?.action != null && config.getOrNull(SemanticsProperties.Disabled) == null
    }.lastOrNull { it.width > 10 && it.height > 10 && it.center.x in 1f..1099f && it.center.y in 1f..1599f }
    private fun ComposePixelScene.clickText(label: String) {
        repeat(80) {
            findText(label)?.let { click(it); settledFrame(); return }
            scrollAt(800f, 1300f, deltaY = 5f); settledFrame(2, 16)
        }
        error("Missing action $label")
    }
    private fun ComposePixelScene.clickDescription(label: String) {
        repeat(80) {
            val rect = semanticsBounds { config ->
                label in config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() &&
                    config.getOrNull(SemanticsActions.OnClick)?.action != null && config.getOrNull(SemanticsProperties.Disabled) == null
            }.firstOrNull { it.width > 10 && it.height > 10 && it.center.x in 1f..1099f && it.center.y in 1f..1599f }
            if (rect != null) { click(rect); settledFrame(); return }
            settledFrame(2, 16)
        }
        error("Missing action $label")
    }
    private fun onUi(action: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeAndWait(action)
    }
    private fun ComposePixelScene.click(rect: Rect) {
        moveTo(rect.center.x, rect.center.y); pressAt(rect.center.x, rect.center.y); releaseAt(rect.center.x, rect.center.y)
    }
}
