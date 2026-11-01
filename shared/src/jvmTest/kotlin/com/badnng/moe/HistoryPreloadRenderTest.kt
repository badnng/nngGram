package com.badnng.moe

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.input.TextFieldValue
import com.badnng.moe.chat.ChatError
import com.badnng.moe.chat.ChatItem
import com.badnng.moe.chat.ChatMessage
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.ChatToken
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class HistoryPreloadRenderTest {
    @Test
    fun aThreeMessageWindowAutomaticallyLoadsOlderAndKeepsItsVisibleMessageAnchor() {
        var state by mutableStateOf(fixture())
        var olderRequests = 0
        ComposePixelScene(900, 700) {
            TestConversation(state, onOlder = {
                olderRequests++
                state = state.copy(historyLoading = true)
            })
        }.use { scene ->
            scene.settledFrame()
            assertEquals(1, olderRequests, "A short initial native window must fill the viewport automatically.")
            assertTrue(scene.buttonTextBounds("加载更早消息").isEmpty())
            val anchor = assertNotNull(scene.textBounds("synthetic-103").firstOrNull())
            scene.settledFrame()
            assertEquals(1, olderRequests, "An in-flight native batch must not be repeatedly dispatched.")
            state = state.copy(messages = messages(1, 103), historyLoading = false, historyEnd = true)
            scene.settledFrame()
            val retained = assertNotNull(scene.textBounds("synthetic-103").firstOrNull())
            assertTrue(abs(anchor.top - retained.top) <= 1f && abs(anchor.left - retained.left) <= 1f,
                "Prepending older history must preserve the stable visible message anchor: $anchor -> $retained")
            assertEquals(1, olderRequests)
        }
    }

    @Test
    fun automaticLoadingWaitsForActivityAndAFailureRequiresAnExplicitRetry() {
        var state by mutableStateOf(fixture().copy(messages = emptyList()))
        var active by mutableStateOf(false)
        var olderRequests = 0
        ComposePixelScene(900, 700) {
            TestConversation(state, active = active, onOlder = {
                olderRequests++
                state = state.copy(historyError = ChatError.REQUEST_FAILED)
            })
        }.use { scene ->
            scene.settledFrame()
            assertEquals(0, olderRequests)
            active = true
            scene.settledFrame()
            assertEquals(1, olderRequests, "The active empty fallback should request its first native page.")
            scene.settledFrame()
            assertEquals(1, olderRequests, "A failing native request must not create an automatic retry loop.")
            val retry = assertNotNull(scene.buttonTextBounds("重试加载消息").firstOrNull())
            scene.pressAt(retry.center.x, retry.center.y)
            scene.releaseAt(retry.center.x, retry.center.y)
            scene.settledFrame()
            assertEquals(2, olderRequests)
        }
    }

    @Test
    fun aNewerHistoryGapOwnsTheShortWindowBeforeTheOlderDirectionCanLoad() {
        var state by mutableStateOf(fixture().copy(historyHasNewer = true))
        var olderRequests = 0
        var newerRequests = 0
        ComposePixelScene(900, 700) {
            TestConversation(state, onOlder = {
                olderRequests++
                state = state.copy(historyLoading = true)
            }, onNewer = {
                newerRequests++
                state = state.copy(newerLoading = true)
            })
        }.use { scene ->
            scene.settledFrame()
            assertEquals(1, newerRequests)
            assertEquals(0, olderRequests, "Older and newer native windows must not race one another.")
            scene.settledFrame()
            assertEquals(1, newerRequests)
            state = state.copy(newerLoading = false, historyHasNewer = false)
            scene.settledFrame()
            assertEquals(1, olderRequests, "The still-short window should then fill its other loaded edge.")
            assertEquals(1, newerRequests)
        }
    }

    @Composable
    private fun TestConversation(state: ChatSnapshot, active: Boolean = true, onOlder: () -> Unit, onNewer: () -> Unit = {}) {
        val controller = remember { ThemeController(ColorSchemeMode.Light) }
        MiuixTheme(controller = controller) {
            ConversationPane(state, state.selected!!, TextFieldValue(), {}, null, onOlder, {}, {}, {},
                ChatFeatureActions(loadNewer = { onNewer() }), active, {}, {}, {}, {}, false)
        }
    }

    private fun ComposePixelScene.textBounds(text: String): List<Rect> = semanticsBounds { config ->
        config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text }
    }

    private fun ComposePixelScene.buttonTextBounds(text: String): List<Rect> = semanticsBounds { config ->
        config.getOrNull(SemanticsProperties.Role) == Role.Button &&
            config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text }
    }

    private fun fixture(): ChatSnapshot {
        val chat = ChatItem(10, "Synthetic history", canSend = false)
        return ChatSnapshot(enabled = true, token = ChatToken(1, 1, chat.id), selected = chat,
            messages = messages(101, 103), listEnd = true)
    }

    private fun messages(from: Int, to: Int) = (from..to).map { ChatMessage(it.toLong(), 10, text = "synthetic-$it") }
}
