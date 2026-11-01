package com.badnng.moe

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class ChatGlassRenderTest {
    @Test
    fun progressiveEdgesReallyBlurTheirSourceAndTheGlassTracksSourceChanges() {
        for (density in listOf(1f, 1.25f)) {
            val colored = mutableStateOf(false)
            ComposePixelScene((340 * density).roundToInt(), (360 * density).roundToInt()) {
                val controller = remember { ThemeController(ColorSchemeMode.Light) }
                CompositionLocalProvider(LocalDensity provides Density(density)) {
                    MiuixTheme(controller = controller) {
                        val backdrop = assertNotNull(rememberChatBackdrop())
                        CompositionLocalProvider(LocalChatBackdrop provides backdrop) {
                            Box(Modifier.fillMaxSize()) {
                                Canvas(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                                    val stripe = 4.dp.toPx()
                                    repeat((size.width / stripe).toInt() + 1) { index ->
                                        drawRect(if (index % 2 != 0) Color.White else if (colored.value) Color.Blue else Color.Black,
                                            topLeft = Offset(index * stripe, 0f), size = Size(stripe, size.height))
                                    }
                                }
                                ChatProgressiveBlur(backdrop, bottom = false, Modifier.size(180.dp, 120.dp))
                                ChatProgressiveBlur(backdrop, bottom = true, Modifier.offset(y = 240.dp).size(180.dp, 120.dp))
                                Box(Modifier.offset(220.dp, 40.dp).size(80.dp).chatGlassSurface(15.dp))
                            }
                        }
                    }
                }
            }.use { scene ->
                val before = scene.settledFrame()
                fun sample(frame: ComposePixelFrame, x: Int, y: Int) = frame[(x * density).roundToInt(), (y * density).roundToInt()]
                fun contrast(y: Int) = distance(sample(before, 82, y), sample(before, 86, y))
                assertTrue(contrast(12) < contrast(115) - 30,
                    "Top edge must lose stripe detail progressively, rather than painting an opaque gradient: $density")
                assertTrue(contrast(348) < contrast(245) - 30,
                    "The composer gradient must mirror the top bar: $density")
                colored.value = true
                val after = scene.settledFrame()
                assertTrue(distance(sample(before, 260, 80), sample(after, 260, 80)) > 15,
                    "Mixed glass must resample the changing content below it, not remain a static tint: $density")
            }
        }
    }

    private fun distance(a: Int, b: Int): Int = listOf(0, 8, 16).maxOf { abs((a ushr it and 255) - (b ushr it and 255)) }
}
