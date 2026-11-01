package com.badnng.moe

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.MessageEntity
import com.badnng.moe.chat.MessageEntityKind
import com.badnng.moe.chat.RichMessageText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Mouse gestures stay in the CPU scene and never open links or access an account/clipboard. */
class MessageTextSelectionRenderTest {
    @Test
    fun nativeStyleLinkAndHiddenSpoilerKeepMouseSelectionAcrossUnrelatedRecomposition() {
        for (kind in listOf(MessageEntityKind.TEXT_URL, MessageEntityKind.SPOILER)) {
            val content = RichMessageText("Native text selection remains available",
                listOf(MessageEntity(0, 11, kind, value = if (kind == MessageEntityKind.TEXT_URL) "https://example.invalid/" else "")))
            val state = SelectionState()
            var generation by mutableStateOf(0)
            var measured: TextLayoutResult? = null
            ComposePixelScene(560, 220) {
                MiuixTheme(controller = remember { ThemeController(ColorSchemeMode.Light) }) {
                    Column(Modifier.fillMaxSize().padding(24.dp)) {
                        val current = generation
                        RichMessageBody(content, selectionState = state,
                            // Native message updates recreate this current-snapshot guard too.
                            canCopyNow = { current >= 0 }, onTextLayout = { measured = it })
                    }
                }
            }.use { scene ->
                scene.settledFrame()
                val bounds = scene.bodyBounds(content.text)
                val layout = assertNotNull(measured)
                // Start in ordinary glyphs: pressing the URL itself has a separate
                // native drag/link action and must not be confused with this regression.
                val start = bounds.caret(layout, 12)
                val middle = bounds.caret(layout, 17)
                val end = bounds.caret(layout, 21)
                scene.moveTo(start.x, start.y)
                scene.pressAt(start.x, start.y)
                scene.dragTo(middle.x, middle.y)
                scene.settledFrame()
                val before = state.selectedTexts.map { it.text }
                assertTrue(before.any { it.isNotEmpty() }, "The real mouse drag must establish a selection ($kind).")
                generation++
                scene.settledFrame()
                assertEquals(before, state.selectedTexts.map { it.text },
                    "An unrelated current-snapshot guard must not turn native annotations into changed text ($kind).")
                scene.dragTo(end.x, end.y)
                scene.releaseAt(end.x, end.y)
                scene.settledFrame()
                assertEquals(content.text.substring(12, 21), state.selectedTexts.joinToString("") { it.text },
                    "The same mouse drag must continue after recomposition ($kind).")
            }
        }
    }

    @Test
    fun losingFocusAndReturningStartsANewMouseSelection() {
        val content = RichMessageText("Mouse selection recovers after focus changes")
        val state = SelectionState()
        var measured: TextLayoutResult? = null
        ComposePixelScene(560, 260) {
            MiuixTheme(controller = remember { ThemeController(ColorSchemeMode.Light) }) {
                val focus = LocalFocusManager.current
                Column(Modifier.fillMaxSize().padding(24.dp)) {
                    RichMessageBody(content, selectionState = state, onTextLayout = { measured = it })
                    Box(Modifier.padding(top = 30.dp).size(160.dp, 40.dp).clickable {
                        focus.clearFocus(force = true)
                    })
                }
            }
        }.use { scene ->
            scene.settledFrame()
            val bounds = scene.bodyBounds(content.text)
            val layout = assertNotNull(measured)
            fun select(startOffset: Int, endOffset: Int) {
                val start = bounds.caret(layout, startOffset)
                val end = bounds.caret(layout, endOffset)
                scene.moveTo(start.x, start.y)
                scene.pressAt(start.x, start.y)
                scene.dragTo(end.x, end.y)
                scene.releaseAt(end.x, end.y)
                scene.settledFrame()
            }
            select(0, 5)
            assertEquals("Mouse", state.selectedTexts.joinToString("") { it.text })
            scene.pressAt(80f, 100f)
            scene.releaseAt(80f, 100f)
            scene.settledFrame()
            assertTrue(state.selectedTexts.isEmpty(), "Focus loss must release the old selection.")
            select(6, 15)
            assertEquals("selection", state.selectedTexts.joinToString("") { it.text },
                "Returning to the message must establish a new drag without stale pointer ownership.")
        }
    }

    private fun ComposePixelScene.bodyBounds(text: String): Rect = assertNotNull(unmergedSemanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == text }
    }.firstOrNull())

    private fun Rect.caret(layout: TextLayoutResult, offset: Int): Offset {
        val caret = layout.getCursorRect(offset)
        return Offset(left + caret.left, top + caret.center.y)
    }
}
