package com.badnng.moe

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.input.TextFieldValue
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.status.ConnectionPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class WorkspaceEmptyStateRenderTest {
    @Test
    fun unselectedConversationShowsOnlyWallpaperBesideOneResizableDivider() {
        ComposePixelScene(1160, 796) {
            val theme = remember { ThemeController(ColorSchemeMode.Light) }
            val drafts = remember { mutableStateMapOf<Long, TextFieldValue>() }
            MiuixTheme(controller = theme) {
                ChatWorkspace(ChatSnapshot(enabled = true, listEnd = true), ConnectionPhase.READY, drafts,
                    onSettings = {}, onSelectChat = { _, _ -> }, onLoadChats = {}, onLoadOlder = {},
                    onSendText = { _, _, _ -> }, onRetryMessage = { _, _ -> }, onMarkRead = {},
                    windowFocused = true, features = ChatFeatureActions(), darkTheme = false,
                    onDarkTheme = {}, persistUiPreferences = false)
            }
        }.use { scene ->
            val frame = scene.settledFrame()
            assertTrue(scene.semanticsBounds { config ->
                config.getOrNull(SemanticsProperties.Text).orEmpty().any {
                    it.text == "选择一个聊天，开始阅读和发送消息"
                }
            }.isEmpty())
            val dividers = scene.semanticsBounds { config ->
                "调整聊天列表宽度" in config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            }
            assertEquals(1, dividers.size)
            val divider = assertNotNull(dividers.singleOrNull())
            val left = divider.left.toInt()
            // The old list border painted a second full-height line immediately before
            // the independently draggable divider. Empty list geometry excludes scrollbars.
            for (y in listOf(400, 600, 740)) {
                assertEquals(frame[left - 5, y], frame[left - 1, y],
                    "The chat-list edge must not paint a second separator at y=$y.")
            }
        }
    }
}
