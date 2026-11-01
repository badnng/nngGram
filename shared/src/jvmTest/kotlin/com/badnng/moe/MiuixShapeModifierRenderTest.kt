package com.badnng.moe

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.squircle.squircleSurface

class MiuixShapeModifierRenderTest {
    @Test
    fun applicationModifiersMatchNativeMiuixIncludingFallbackAndLeaveTrueCirclesIntact() {
        for (density in listOf(1f, 1.25f, 2f)) {
            for (enabled in listOf(true, false)) for (direction in LayoutDirection.entries) {
                val actual = render(reference = false, density, enabled, direction)
                val native = render(reference = true, density, enabled, direction)
                exportComparison(actual, native, "enabled-$enabled-density-$density-direction-$direction")
                var differentPixels = 0
                for (y in 0 until actual.height) for (x in 0 until actual.width) {
                    if (actual[x, y] != native[x, y]) differentPixels++
                }
                assertEquals(0, differentPixels,
                    "Background, clipping and surface must use their corresponding native modifier: " +
                        "density=$density, enabled=$enabled, direction=$direction")
                fun pixel(x: Float, y: Float) = actual[(x * density).roundToInt(), (y * density).roundToInt()]
                assertEquals(Background.toArgb(), pixel(289f, 105f),
                    "The diagonal outside a genuine circle must stay outside the hit-surface drawing.")
                assertEquals(Fill.toArgb(), pixel(308f, 102f),
                    "The circle's axis must still show its original fill.")
            }
        }
    }

    private fun render(reference: Boolean, density: Float, enabled: Boolean, direction: LayoutDirection): ComposePixelFrame =
        ComposePixelScene((356 * density).roundToInt(), (240 * density).roundToInt()) {
            CompositionLocalProvider(
                LocalDensity provides Density(density),
                LocalLayoutDirection provides direction,
                LocalSquircleEnabled provides enabled,
            ) {
                Box(Modifier.fillMaxSize().background(Background), contentAlignment = AbsoluteAlignment.TopLeft) {
                    for (variant in 0..2) {
                        val radius = when (variant) { 0 -> 0.dp; 1 -> 15.dp; else -> 1_000.dp }
                        Box(Modifier.absoluteOffset(16.dp, (20 + 72 * variant).dp).size(240.dp, 52.dp)
                            .nativeOrApplication(reference, variant, radius)) {
                            Canvas(Modifier.fillMaxSize()) {
                                drawRect(Color.Black, Offset(0f, 7.dp.toPx()), Size(size.width, 9.dp.toPx()))
                                drawRect(Color.Yellow, Offset(0f, size.height - 3.dp.toPx()), Size(size.width, 3.dp.toPx()))
                            }
                        }
                    }
                    Box(Modifier.absoluteOffset(284.dp, 100.dp).size(48.dp).clip(CircleShape).background(Fill))
                }
            }
        }.use { it.settledFrame(3) }

    @Composable
    private fun Modifier.nativeOrApplication(reference: Boolean, variant: Int, radius: Dp): Modifier =
        if (reference) when (variant) {
            0 -> squircleBackground(Fill, radius)
            1 -> squircleClip(radius).background(Fill)
            else -> squircleSurface(Fill, radius)
        } else when (variant) {
            0 -> chatSquircleBackground(Fill, radius)
            1 -> chatSquircleClip(radius).background(Fill)
            else -> chatSquircleSurface(Fill, radius)
        }

    private fun exportComparison(actual: ComposePixelFrame, native: ComposePixelFrame, name: String) {
        val output = File(System.getProperty("java.io.tmpdir"), "miuix-shape-alignment").apply { mkdirs() }
        val image = BufferedImage(actual.width * 2 + 8, actual.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            image.setRGB(x, y, when {
                x < actual.width -> actual[x, y]
                x >= actual.width + 8 -> native[x - actual.width - 8, y]
                else -> Color.White.toArgb()
            })
        }
        check(ImageIO.write(image, "png", File(output, "$name.png")))
    }

    private companion object {
        val Background = Color(0xffe8e2db)
        val Fill = Color(0xff086aae)
    }
}
