package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.input.TextFieldValue
import com.badnng.moe.chat.ChatItem
import com.badnng.moe.chat.ChatMediaCategory
import com.badnng.moe.chat.ChatMediaIndexState
import com.badnng.moe.chat.ChatMessage
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.ChatToken
import com.badnng.moe.status.ConnectionPhase
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class ConversationInfoTransitionRenderTest {
    @Test
    fun sidebarRetainsItsPaintedContentUntilTheExitCompletesAndBlocksClosingActions() {
        val chat = ChatItem(10, "合成信息侧栏", basicGroupId = 20)
        val snapshot = infoSnapshot(chat)
        var visible by mutableStateOf(false)
        var closeRequests = 0
        val widths = mutableListOf<Float>()
        val indexLoads = mutableListOf<ChatMediaCategory>()
        var unexpectedReads = 0
        val features = ChatFeatureActions(
            loadMediaIndex = { token, category, more ->
                assertEquals(snapshot.token, token)
                assertTrue(!more)
                indexLoads += category
            },
            visibleMessages = { _, ids, _ -> if (ids.isNotEmpty()) unexpectedReads++ },
        )
        ComposePixelScene(900, 600) {
            val controller = remember { ThemeController(ColorSchemeMode.Light) }
            MiuixTheme(controller = controller) {
                Row(Modifier.fillMaxSize().background(Color.Magenta)) {
                    Box(Modifier.weight(1f).fillMaxHeight())
                    ConversationInfoTransition(visible, onWidthChanged = { widths += it.value }) {
                        ConversationInfoPane(chat, snapshot, features, onJump = {}, onAdmins = {},
                            onClose = { closeRequests++; visible = false }, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }.use { scene ->
            val resting = scene.settledFrame()
            visible = true
            val opened = scene.settledFrame()
            val close = assertNotNull(scene.buttonDescriptionBounds("收起会话信息").firstOrNull())
            val panel = Rect(600f, 0f, 900f, 600f)
            assertTrue(scene.textBounds("简介").isNotEmpty())
            assertTrue(widths.any { it > 0f && it < 300f } && widths.last() == 300f,
                "The sidebar must expand through real measured widths instead of jumping to 300dp.")
            assertTrue(changedPixels(resting, opened, panel) > 1000)
            assertEquals(listOf(ChatMediaCategory.PHOTOS_AND_VIDEOS), indexLoads,
                "Opening information must load the independent native media index once.")

            scene.click(close)
            val exiting = scene.settledFrame(frameCount = 1)
            assertEquals(1, closeRequests)
            assertTrue(changedPixels(resting, exiting, panel) > 100,
                "The first closing frame must retain the information surface for its exit animation.")
            assertTrue(scene.buttonDescriptionBounds("收起会话信息").isEmpty(),
                "Closing content must immediately stop exposing interactive semantics.")
            scene.click(close)
            assertEquals(1, closeRequests, "A retained closing surface must consume a second click.")
            val closed = scene.settledFrame()
            assertTrue(widths.last() == 0f, "The side column must release all of its width after the exit.")
            assertTrue(scene.textBounds("简介").isEmpty())
            assertEquals(0, changedPixels(resting, closed, panel), "The completed exit must leave no painted side column.")
            assertEquals(1, indexLoads.size, "Retaining content while closing must not reload the index.")
            assertEquals(0, unexpectedReads, "Information/index rendering must not submit timeline read receipts.")
        }
    }

    @Test
    fun theNarrowWorkspaceLetsItsNativeInformationDialogFinishClosing() {
        val chat = ChatItem(10, "合成窄窗信息", basicGroupId = 20)
        val snapshot = infoSnapshot(chat)
        var indexLoads = 0
        var unexpectedReads = 0
        val features = ChatFeatureActions(
            loadMediaIndex = { token, category, more ->
                assertEquals(snapshot.token, token)
                assertEquals(ChatMediaCategory.PHOTOS_AND_VIDEOS, category)
                assertTrue(!more)
                indexLoads++
            },
            visibleMessages = { _, ids, _ -> if (ids.isNotEmpty()) unexpectedReads++ },
        )
        ComposePixelScene(780, 760) {
            val controller = remember { ThemeController(ColorSchemeMode.Light) }
            val drafts = remember { mutableStateMapOf<Long, TextFieldValue>() }
            MiuixTheme(controller = controller) {
                ChatWorkspace(snapshot, ConnectionPhase.READY, drafts,
                    onSettings = {}, onSelectChat = { _, _ -> }, onLoadChats = {}, onLoadOlder = {},
                    onSendText = { _, _, _ -> }, onRetryMessage = { _, _ -> }, onMarkRead = {},
                    windowFocused = true, features = features, darkTheme = false, onDarkTheme = {},
                    persistUiPreferences = false)
            }
        }.use { scene ->
            val resting = scene.settledFrame()
            scene.click(assertNotNull(scene.buttonDescriptionBounds("群操作").firstOrNull()))
            scene.settledFrame()
            scene.click(assertNotNull(scene.buttonTextBounds("查看群组信息").firstOrNull()))
            scene.settledFrame()
            assertTrue(scene.textBounds("简介").isNotEmpty(), "The actual narrow workspace must open its information dialog.")
            assertEquals(1, indexLoads)
            val close = assertNotNull(scene.buttonDescriptionBounds("收起会话信息").firstOrNull())
            scene.click(close)
            val exiting = scene.settledFrame(frameCount = 1)
            val viewport = Rect(0f, 0f, 780f, 760f)
            assertTrue(changedPixels(resting, exiting, viewport) > 100,
                "The native Miuix closing animation must retain the dialog after show becomes false.")
            assertTrue(scene.buttonDescriptionBounds("收起会话信息").isEmpty(),
                "The dialog's retained content must stop accepting actions during its exit.")
            scene.settledFrame()
            assertTrue(scene.textBounds("简介").isEmpty(), "The native dialog must finally remove the closed content.")
            assertEquals(1, indexLoads)
            assertEquals(0, unexpectedReads)
        }
    }

    private fun infoSnapshot(chat: ChatItem): ChatSnapshot = ChatSnapshot(
        enabled = true, token = ChatToken(1, 1, chat.id), selected = chat, listEnd = true, historyEnd = true,
        messages = listOf(ChatMessage(1, chat.id, outgoing = true, text = "合成时间线消息")),
        mediaIndex = ChatMediaIndexState(chatId = chat.id, loaded = true, totalCount = 0),
    )

    private fun ComposePixelScene.textBounds(text: String): List<Rect> = semanticsBounds { config ->
        config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text }
    }

    private fun ComposePixelScene.buttonTextBounds(text: String): List<Rect> = semanticsBounds { config ->
        config.getOrNull(SemanticsProperties.Role) == Role.Button &&
            config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text }
    }

    private fun ComposePixelScene.buttonDescriptionBounds(description: String): List<Rect> = semanticsBounds { config ->
        config.getOrNull(SemanticsProperties.Role) == Role.Button &&
            description in config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }

    private fun ComposePixelScene.click(bounds: Rect) {
        moveTo(bounds.center.x, bounds.center.y)
        pressAt(bounds.center.x, bounds.center.y)
        releaseAt(bounds.center.x, bounds.center.y)
    }

    private fun changedPixels(first: ComposePixelFrame, second: ComposePixelFrame, bounds: Rect): Int {
        var count = 0
        for (y in max(0, bounds.top.toInt()) until min(first.height, bounds.bottom.toInt())) {
            for (x in max(0, bounds.left.toInt()) until min(first.width, bounds.right.toInt())) {
                if (first[x, y] != second[x, y]) count++
            }
        }
        return count
    }
}
