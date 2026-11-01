package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Folder
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class ContextMenuRowRenderTest {
    @Test fun theReadIndicatorDrawsTwoSideBySideChecksWithinOneFixedIcon() {
        ComposePixelScene(60, 60) {
            val theme = remember { ThemeController(ColorSchemeMode.Dark) }
            MiuixTheme(controller = theme) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    ReaderDoubleCheck(Modifier.offset(20.dp, 20.dp).size(19.dp, 16.dp))
                }
            }
        }.use { scene ->
            val frame = scene.settledFrame()
            val painted = (20 until 36).flatMap { y -> (20 until 39).filter { x ->
                distance(frame[x, y], frame[0, 0]) >= 10
            }.map { x -> x to y } }
            assertTrue(painted.any { it.first < 29 } && painted.any { it.first >= 29 }, "Both checks must paint side by side.")
            assertTrue(painted.maxOf { it.second } - painted.minOf { it.second } <= 10,
                "The read icon must stay on one line instead of wrapping into a second row.")
        }
    }

    @Test fun chatAndMessageMenuRowsClipHoverAndPressToTheirOwnRoundedRectangle() {
        for (message in listOf(false, true)) {
            var clicks = 0
            ComposePixelScene(260, 100) {
                val theme = remember { ThemeController(ColorSchemeMode.Dark) }
                MiuixTheme(controller = theme) {
                    Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surfaceContainer)) {
                        Column(Modifier.offset(20.dp, 20.dp).width(220.dp)) {
                            if (message) MessageMenuRow("归档聊天", MiuixIcons.Regular.Folder, onClick = { clicks++ })
                            else ChatListMenuRow("归档聊天", MiuixIcons.Regular.Folder, true) { clicks++ }
                        }
                    }
                }
            }.use { scene ->
                val resting = scene.settledFrame()
                scene.moveTo(200f, 35f)
                val hovered = scene.settledFrame()
                scene.pressAt(200f, 35f)
                val pressed = scene.settledFrame()
                val bottom = if (message) 53 else 61
                for ((x, y) in listOf(130 to 23, 130 to bottom, 23 to 38, 236 to 38)) {
                    assertTrue(distance(resting[x, y], hovered[x, y]) >= 3,
                        "The real menu row must show hover across its rounded rectangle.")
                    assertTrue(distance(hovered[x, y], pressed[x, y]) >= 3,
                        "The real menu row must show pressed feedback across its rounded rectangle.")
                }
                for ((x, y) in listOf(21 to 21, 238 to 21, 21 to bottom + 1, 238 to bottom + 1)) {
                    assertTrue(distance(resting[x, y], hovered[x, y]) <= 1,
                        "Hover must leave the row's rounded corner visible.")
                    assertTrue(distance(resting[x, y], pressed[x, y]) <= 1,
                        "Pressed feedback must stay inside the 15dp squircle clip.")
                }
                scene.releaseAt(200f, 35f)
                scene.settledFrame()
                assertEquals(1, clicks)
            }
        }
    }

    private fun distance(first: Int, second: Int): Int = listOf(16, 8, 0).maxOf { shift ->
        abs((first ushr shift and 255) - (second ushr shift and 255))
    }
}
