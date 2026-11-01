package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class UnreadBadgeRenderTest {
    @Test
    fun cappedUnreadGlyphsAreCenteredInBothBadgeSizes() {
        for (compact in listOf(true, false)) {
            var badgeBounds: Rect? = null
            var foreground = 0
            var count by mutableIntStateOf(1_000)
            ComposePixelScene(120, 80) {
                val controller = remember { ThemeController(ColorSchemeMode.Dark) }
                MiuixTheme(controller = controller) {
                    val color = MiuixTheme.colorScheme.onPrimary.toArgb()
                    SideEffect { foreground = color }
                    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                        UnreadBadge(count, compact = compact,
                            modifier = Modifier.onGloballyPositioned { badgeBounds = it.boundsInRoot() })
                    }
                }
            }.use { scene ->
                val frame = scene.settledFrame()
                val bounds = assertNotNull(badgeBounds)
                val ink = buildList {
                    for (y in bounds.top.toInt() until bounds.bottom.toInt()) {
                        for (x in bounds.left.toInt() until bounds.right.toInt()) {
                            if (distance(frame[x, y], foreground) < 70) add(x to y)
                        }
                    }
                }
                assertTrue(ink.isNotEmpty(), "The actual badge text must be visible.")
                val inkCenterX = (ink.minOf { it.first } + ink.maxOf { it.first } + 1) / 2f
                val inkCenterY = (ink.minOf { it.second } + ink.maxOf { it.second } + 1) / 2f
                assertTrue(abs(inkCenterX - bounds.center.x) <= 1.5f, "999+ must be horizontally centered: $inkCenterX vs ${bounds.center.x}.")
                assertTrue(abs(inkCenterY - bounds.center.y) <= 1.5f, "999+ must be vertically centered: $inkCenterY vs ${bounds.center.y}.")

                count = 9_999_999
                val capped = scene.settledFrame()
                assertEquals(bounds, badgeBounds, "Larger counts must keep the same 999+ pill dimensions.")
                for (y in 0 until frame.height) for (x in 0 until frame.width) {
                    assertEquals(frame[x, y], capped[x, y], "Counts above 999 must render the same capped label.")
                }
            }
        }
    }

    private fun distance(first: Int, second: Int): Int = max(
        abs((first ushr 16 and 0xff) - (second ushr 16 and 0xff)),
        max(abs((first ushr 8 and 0xff) - (second ushr 8 and 0xff)),
            abs((first and 0xff) - (second and 0xff))),
    )
}
