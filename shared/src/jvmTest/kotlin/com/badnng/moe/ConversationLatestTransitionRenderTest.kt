package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.ChatItem
import com.badnng.moe.chat.ChatMessage
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.ChatToken
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class ConversationLatestTransitionRenderTest {
    @Test
    fun aLatestRequestRetainsTheOldWindowAndScrollsRealWindowsWithoutMovingControls() {
        val oldToken = ChatToken(1, 1, 10)
        val newToken = oldToken.copy(selection = 2)
        val old = ChatSnapshot(enabled = true, token = oldToken, selected = ChatItem(10, "合成会话"),
            messages = listOf(ChatMessage(1, 10, text = "旧窗口")), historyHasNewer = true)
        var state by mutableStateOf(old)
        var requests = 0
        ComposePixelScene(400, 640) {
            val theme = remember { ThemeController(ColorSchemeMode.Light) }
            MiuixTheme(controller = theme) {
            ConversationLatestTransition(state, onReturnToLatest = {
                assertEquals(oldToken, it)
                requests++
                state = old.copy(token = newToken, messages = emptyList(), historyHasNewer = false,
                    historyLoading = true)
            }) { shown, _, requestLatest ->
                val motion = LocalHistoryWindowMotion.current
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().historyWindowMotion(motion)
                        .background(if (shown.token == oldToken) Color.Red else Color.Blue))
                    if (motion?.outgoing != true) {
                        Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(40.dp).background(Color.White))
                        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(40.dp).background(Color.White)
                            .semantics { contentDescription = "请求最新窗口" }
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                                onClick = requestLatest))
                    }
                }
            }
            }
        }.use { scene ->
            val before = scene.settledFrame()
            val button = scene.semanticsBounds {
                "请求最新窗口" in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            }.single()
            scene.pressAt(button.center.x, button.center.y)
            scene.releaseAt(button.center.x, button.center.y)
            val waiting = scene.settledFrame()
            assertEquals(1, requests)
            assertEquals(before[200, 300], waiting[200, 300], "Native loading must not replace the old viewport with an empty flash.")
            scene.pressAt(button.center.x, button.center.y)
            scene.releaseAt(button.center.x, button.center.y)
            scene.settledFrame(1)
            assertEquals(1, requests, "Retained outgoing controls must not submit another native request.")

            state = old.copy(token = newToken, messages = listOf(ChatMessage(100, 10, text = "最新窗口")), historyHasNewer = false)
            val blueRows = mutableListOf<Int>()
            repeat(10) {
                val frame = scene.settledFrame(1)
                blueRows += (40 until 600).count { y -> frame[200, y] == 0xff0000ff.toInt() }
                assertEquals(0xffffffff.toInt(), frame[200, 20], "The header remains stationary while history moves.")
                assertEquals(0xffffffff.toInt(), frame[200, 620], "The composer remains stationary while history moves.")
            }
            assertTrue(blueRows.filter { it in 1..559 }.distinct().size >= 3,
                "Several raster frames must show the new window scrolling in, not one direct replacement: $blueRows")
            assertEquals(560, blueRows.last())
            assertEquals(1, scene.semanticsBounds {
                "请求最新窗口" in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            }.size, "Only the current window remains interactive after the transition.")
        }
    }
}
