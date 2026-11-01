package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.ChatSearchQuery
import com.badnng.moe.chat.ChatSearchState
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.ChatToken
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class SearchFieldClearRenderTest {
    @Test fun theRealNarrowSearchFieldInsetsItsCircularClearButtonAndKeepsHoverInsideTheCircle() {
        for (theme in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) {
            searchScene(theme).use { scene ->
                val resting = scene.settledFrame()
                val field = assertNotNull(scene.descriptionBounds("搜索消息").maxByOrNull { it.width * it.height })
                val clear = scene.clearButtonBounds()
                assertTrue(field.left >= 0f && field.right <= 320f, "The real search field must stay in the 320px pane.")
                assertTrue(clear.left - field.left >= 5f && field.right - clear.right >= 5f,
                    "The clear circle must sit inside the field with trailing breathing room.")
                assertTrue(clear.top - field.top >= 5f && field.bottom - clear.bottom >= 5f,
                    "The clear circle must have space above and below inside the actual TextField.")
                assertTrue(abs(clear.width - clear.height) <= 1f, "The clear target must remain circular in the narrow field.")

                scene.moveTo(clear.center.x, clear.center.y)
                val hovered = scene.settledFrame()
                val left = clear.left.roundToInt()
                val top = clear.top.roundToInt()
                val right = clear.right.roundToInt() - 1
                val bottom = clear.bottom.roundToInt() - 1
                val centerX = clear.center.x.roundToInt()
                val centerY = clear.center.y.roundToInt()
                for ((x, y) in listOf(centerX to top + 2, centerX to bottom - 2, left + 2 to centerY, right - 2 to centerY)) {
                    assertTrue(distance(resting[x, y], hovered[x, y]) >= 3,
                        "Hover must paint the real clear button's circular interior.")
                }
                for ((x, y) in listOf(left + 1 to top + 1, right - 1 to top + 1,
                    left + 1 to bottom - 1, right - 1 to bottom - 1)) {
                    assertTrue(distance(resting[x, y], hovered[x, y]) <= 1,
                        "Clear-button hover must not tint the square corners outside its circle.")
                }
            }
        }
    }

    @Test fun clickingTheRealClearActionEmptiesTheKeywordAndRestoresTextFieldFocus() {
        searchScene(ColorSchemeMode.Light).use { scene ->
            scene.settledFrame()
            assertTrue(scene.editableBounds("nmsl", focused = true).isNotEmpty(), "The real search field must initially receive focus.")
            val clear = scene.clearButtonBounds()
            val focusSink = assertNotNull(scene.descriptionBounds("test focus sink").firstOrNull())
            scene.click(focusSink)
            scene.settledFrame()
            assertTrue(scene.editableBounds("nmsl", focused = false).isNotEmpty(),
                "The test must move focus away before checking that clear restores it.")

            scene.click(clear)
            scene.settledFrame()
            assertTrue(scene.editableBounds("", focused = true).isNotEmpty(),
                "The actual TextField clear callback must empty its editor and restore typing focus.")
            assertFalse(scene.editableBounds("nmsl").isNotEmpty(), "The prior keyword must leave the real editor semantics.")
            assertTrue(scene.descriptionBounds("清除关键词").isEmpty(), "The clear affordance must disappear when the editor is empty.")
        }
    }

    private fun searchScene(mode: ColorSchemeMode): ComposePixelScene = ComposePixelScene(320, 600) {
        val theme = remember { ThemeController(mode) }
        val query = remember { ChatSearchQuery(text = "nmsl") }
        val snapshot = remember { ChatSnapshot(enabled = true, token = ChatToken(1, 0, null), search = ChatSearchState(query = query)) }
        MiuixTheme(controller = theme) {
            val focus = LocalFocusManager.current
            Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
                ConversationSearchPane(snapshot, ChatFeatureActions(), {}, {}, {}, Modifier.fillMaxSize(), initialQuery = query)
                // A test-only real click target removes focus without changing the production search component.
                Box(Modifier.align(Alignment.BottomEnd).size(32.dp).semantics { contentDescription = "test focus sink" }
                    .clickable { focus.clearFocus(force = true) })
            }
        }
    }

    private fun ComposePixelScene.descriptionBounds(description: String): List<Rect> = semanticsBounds { config ->
        description in config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }

    private fun ComposePixelScene.clearButtonBounds(): Rect {
        val icon = assertNotNull(descriptionBounds("清除关键词").firstOrNull())
        return assertNotNull(semanticsBounds { config ->
            config.getOrNull(SemanticsProperties.Role) == Role.Button && config.getOrNull(SemanticsActions.OnClick) != null
        }.filter { it.contains(icon.center) }.minByOrNull { it.width * it.height },
            "The clear icon must belong to a real clickable button.")
    }

    private fun ComposePixelScene.editableBounds(text: String, focused: Boolean? = null): List<Rect> = semanticsBounds { config ->
        config.getOrNull(SemanticsProperties.EditableText)?.text == text &&
            (focused == null || config.getOrNull(SemanticsProperties.Focused) == focused)
    }

    private fun ComposePixelScene.click(bounds: Rect) {
        moveTo(bounds.center.x, bounds.center.y)
        pressAt(bounds.center.x, bounds.center.y)
        releaseAt(bounds.center.x, bounds.center.y)
    }

    private fun distance(first: Int, second: Int): Int = listOf(16, 8, 0).maxOf { shift ->
        abs((first ushr shift and 255) - (second ushr shift and 255))
    }
}
