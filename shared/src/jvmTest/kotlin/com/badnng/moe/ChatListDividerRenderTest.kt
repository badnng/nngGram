package com.badnng.moe

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class ChatListDividerRenderTest {
    @Test
    fun restingSplitPaintsNoLineWhileHoverStillShowsTheResizeGuide() {
        var surface = 0
        ComposePixelScene(40, 100) {
            val controller = remember { ThemeController(ColorSchemeMode.Light) }
            MiuixTheme(controller = controller) {
                val color = MiuixTheme.colorScheme.surface.toArgb()
                SideEffect { surface = color }
                Box(Modifier.fillMaxSize().offset(10.dp)) {
                    ChatListDivider(280.dp, 480.dp, onDelta = {}, onSetWidth = {})
                }
            }
        }.use { scene ->
            scene.moveTo(35f, 50f)
            val resting = scene.settledFrame()
            for (x in 10 until 16) assertEquals(surface, resting[x, 50], "Resting split painted a line at x=$x.")
            scene.moveTo(13f, 50f)
            val hovered = scene.settledFrame()
            assertTrue((10 until 16).any { hovered[it, 50] != surface }, "Hover must still reveal the resize guide.")
        }
    }

    @Test
    fun pointerDragChangesWidthInBothDirectionsAndCanReachTheAvatarMinimum() {
        var observedWidth = 280.dp
        ComposePixelScene(600, 200) {
            val controller = remember { ThemeController(ColorSchemeMode.Light) }
            var width by remember { mutableStateOf(280.dp) }
            observedWidth = width
            MiuixTheme(controller = controller) {
                Box(Modifier.fillMaxSize().offset(280.dp)) {
                    ChatListDivider(width, 480.dp,
                        onDelta = { width = constrainedChatListWidth(width + it, 480.dp) },
                        onSetWidth = { width = constrainedChatListWidth(it, 480.dp) })
                }
            }
        }.use { scene ->
            scene.settledFrame()
            scene.pressAt(283f, 100f)
            scene.settledFrame()
            scene.dragTo(343f, 100f)
            scene.settledFrame()
            assertEquals(340.dp, observedWidth)
            scene.dragTo(253f, 100f)
            scene.settledFrame()
            assertEquals(250.dp, observedWidth)
            scene.dragTo(20f, 100f)
            scene.settledFrame()
            assertEquals(72.dp, observedWidth)
            scene.releaseAt(20f, 100f)
            scene.settledFrame()
        }
    }
}
