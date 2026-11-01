package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.ChatMessage
import com.badnng.moe.chat.ChatPinnedState
import com.badnng.moe.chat.ChatSnapshot
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class PinnedMessageHoverRenderTest {
    @Test
    fun pinActionHoverIsCircularAndPinnedTextHoverFollowsItsMiuixRectangle() {
        for (density in listOf(1f, 1.25f)) {
            var pinClicks = 0
            var messageClicks = 0
            val snapshot = ChatSnapshot(pinned = ChatPinnedState(messages = listOf(ChatMessage(100, 10, text = "合成置顶内容")), totalCount = 1))
            ComposePixelScene((380 * density).roundToInt(), (150 * density).roundToInt()) {
                val controller = remember { ThemeController(ColorSchemeMode.Dark) }
                androidx.compose.runtime.CompositionLocalProvider(LocalDensity provides Density(density)) {
                    MiuixTheme(controller = controller) {
                        Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
                            PinnedMessageBar(snapshot, ChatFeatureActions(openPinnedMessage = { _, _ -> messageClicks++ }),
                                onOpenList = { pinClicks++ }, modifier = Modifier.offset(20.dp, 20.dp).width(340.dp))
                        }
                    }
                }
            }.use { scene ->
                val resting = scene.settledFrame()
                val pin = assertNotNull(scene.semanticsBounds {
                    it.getOrNull(SemanticsProperties.Role) == Role.Button &&
                        "查看全部置顶消息" in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                }.singleOrNull())
                val text = assertNotNull(scene.semanticsBounds {
                    it.getOrNull(SemanticsProperties.Role) == Role.Button &&
                        it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == "合成置顶内容" }
                }.singleOrNull())
                for (bounds in listOf(pin, text)) {
                    scene.moveTo(bounds.center.x, bounds.center.y)
                    val hover = scene.settledFrame()
                    scene.pressAt(bounds.center.x, bounds.center.y)
                    val pressed = scene.settledFrame()
                    val side = (bounds.left + 2f * density).roundToInt() to bounds.center.y.roundToInt()
                    assertTrue(distance(resting[side.first, side.second], hover[side.first, side.second]) >= 3,
                        "Hover must cover the whole shaped action, not just the icon/text.")
                    val corners = listOf(
                        (bounds.left + density).roundToInt() to (bounds.top + density).roundToInt(),
                        (bounds.right - density).roundToInt() to (bounds.top + density).roundToInt(),
                    )
                    for ((x, y) in corners) {
                        assertTrue(distance(resting[x, y], hover[x, y]) <= 1,
                            "Hover must not fill the action's removed corner: $bounds/$density")
                        assertTrue(distance(resting[x, y], pressed[x, y]) <= 1,
                            "Press feedback must follow the same curved boundary.")
                    }
                    scene.releaseAt(bounds.center.x, bounds.center.y)
                    scene.moveTo(2f, 2f)
                    scene.settledFrame()
                }
                assertEquals(1, pinClicks)
                assertEquals(1, messageClicks)
            }
        }
    }

    private fun distance(first: Int, second: Int): Int = listOf(16, 8, 0).maxOf {
        abs((first ushr it and 255) - (second ushr it and 255))
    }
}
