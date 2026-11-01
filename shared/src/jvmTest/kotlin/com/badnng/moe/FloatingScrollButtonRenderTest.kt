package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class FloatingScrollButtonRenderTest {
    @Test
    fun hoverAndPressCoverTheWholeCircleWithoutPaintingItsCorners() {
        for (size in listOf(40, 42)) {
            var clicks = 0
            ComposePixelScene(120, 120) {
                val controller = remember { ThemeController(ColorSchemeMode.Dark) }
                MiuixTheme(controller = controller) {
                    Box(Modifier.fillMaxSize().background(Color.Black)) {
                        FloatingScrollButton(up = false, onClick = { clicks++ },
                            modifier = Modifier.offset(40.dp, 48.dp), size = size.dp)
                    }
                }
            }.use { scene ->
                val resting = scene.settledFrame()
                val centerX = 40 + size / 2
                val centerY = 48 + size / 2
                val inside = listOf(
                    44 to centerY,
                    40 + size - 5 to centerY,
                    centerX to 52,
                    centerX to 48 + size - 5,
                )
                val corners = listOf(
                    41 to 49,
                    40 + size - 2 to 49,
                    41 to 48 + size - 2,
                    40 + size - 2 to 48 + size - 2,
                )
                scene.moveTo(centerX.toFloat(), centerY.toFloat())
                val hovered = scene.settledFrame()
                scene.pressAt(centerX.toFloat(), centerY.toFloat())
                val pressed = scene.settledFrame()
                for ((x, y) in inside) {
                    assertTrue(colorDistance(resting[x, y], hovered[x, y]) >= 3,
                        "${size}dp hover must cover the circle at ($x, $y).")
                    assertTrue(colorDistance(hovered[x, y], pressed[x, y]) >= 3,
                        "${size}dp press must cover the circle at ($x, $y).")
                }
                for ((x, y) in corners) {
                    assertTrue(colorDistance(resting[x, y], hovered[x, y]) <= 1,
                        "${size}dp hover escaped the circular clip at ($x, $y).")
                    assertTrue(colorDistance(resting[x, y], pressed[x, y]) <= 1,
                        "${size}dp press escaped the circular clip at ($x, $y).")
                }
                scene.releaseAt(centerX.toFloat(), centerY.toFloat())
                scene.settledFrame()
                assertEquals(1, clicks, "The rendered circle must remain the real click target.")
            }
        }
    }

    @Test
    fun unreadBadgeRemainsVisibleAboveTheCircularButtonWhileHovering() {
        var primaryColor = 0
        ComposePixelScene(120, 120) {
            val controller = remember { ThemeController(ColorSchemeMode.Dark) }
            MiuixTheme(controller = controller) {
                val primary = MiuixTheme.colorScheme.primary.toArgb()
                SideEffect { primaryColor = primary }
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    FloatingScrollButton(up = false, onClick = {}, unreadCount = 128,
                        modifier = Modifier.offset(40.dp, 48.dp))
                }
            }
        }.use { scene ->
            val resting = scene.settledFrame()
            // The badge begins 9px above the 40px button; sample its fill above the clip.
            assertTrue(colorDistance(primaryColor, resting[60, 41]) <= 1,
                "The unread badge must paint above the circular button bounds.")
            scene.moveTo(60f, 68f)
            val hovered = scene.settledFrame()
            assertTrue(colorDistance(primaryColor, hovered[60, 41]) <= 1,
                "Hover clipping must preserve the sibling unread badge.")
            assertEquals(resting[60, 41], hovered[60, 41])
        }
    }

    private fun colorDistance(first: Int, second: Int): Int = max(
        abs((first ushr 16 and 0xff) - (second ushr 16 and 0xff)),
        max(abs((first ushr 8 and 0xff) - (second ushr 8 and 0xff)),
            abs((first and 0xff) - (second and 0xff))),
    )
}
