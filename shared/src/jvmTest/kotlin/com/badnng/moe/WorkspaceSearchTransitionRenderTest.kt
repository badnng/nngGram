package com.badnng.moe

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.input.TextFieldValue
import com.badnng.moe.chat.ChatItem
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.ChatToken
import com.badnng.moe.status.ConnectionPhase
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class WorkspaceSearchTransitionRenderTest {
    @Test
    fun actualSearchAndBackProduceIntermediatePaintAndRetainTheClosingField() {
        val snapshot = ChatSnapshot(enabled = true, token = ChatToken(1, 0, null),
            items = (1..5).map { ChatItem(it.toLong(), "搜索过渡合成会话$it", order = 10L - it) }, listEnd = true)
        ComposePixelScene(380, 650) {
            val controller = remember { ThemeController(ColorSchemeMode.Light) }
            val drafts = remember { mutableStateMapOf<Long, TextFieldValue>() }
            MiuixTheme(controller = controller) {
                ChatWorkspace(snapshot, ConnectionPhase.READY, drafts, onSettings = {}, onSelectChat = { _, _ -> },
                    onLoadChats = {}, onLoadOlder = {}, onSendText = { _, _, _ -> }, onRetryMessage = { _, _ -> },
                    onMarkRead = {}, windowFocused = true, features = ChatFeatureActions(), darkTheme = false,
                    onDarkTheme = {}, persistUiPreferences = false)
            }
        }.use { scene ->
            val list = scene.settledFrame()
            scene.click(assertNotNull(scene.description("搜索当前聊天列表").firstOrNull()))
            val entering = scene.settledFrame(1)
            val search = scene.settledFrame()
            assertTrue(changed(list, entering) > 50 && changed(entering, search) > 50,
                "Opening must paint a real intermediate frame before settling.")
            scene.click(assertNotNull(scene.description("关闭搜索").firstOrNull()))
            val exiting = scene.settledFrame(1)
            val restored = scene.settledFrame()
            assertTrue(changed(search, exiting) > 50 && changed(exiting, restored) > 50,
                "The back action must retain its outgoing field while it slides away.")
            assertTrue(scene.description("关闭搜索").isEmpty())
            assertTrue(scene.description("搜索当前聊天列表").isNotEmpty())
        }
    }

    private fun ComposePixelScene.description(label: String) = semanticsBounds {
        label in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }
    private fun ComposePixelScene.click(rect: Rect) {
        moveTo(rect.center.x, rect.center.y); pressAt(rect.center.x, rect.center.y); releaseAt(rect.center.x, rect.center.y)
    }
    private fun changed(a: ComposePixelFrame, b: ComposePixelFrame): Int {
        var count = 0
        for (y in 0 until a.height) for (x in 0 until a.width) if (a[x, y] != b[x, y]) count++
        return count
    }
}
