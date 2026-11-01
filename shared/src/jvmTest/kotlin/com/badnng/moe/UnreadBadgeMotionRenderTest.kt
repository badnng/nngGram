package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Actual shared badge rendering, with a deterministic clock and synthetic counts only. */
class UnreadBadgeMotionRenderTest {
    @Test
    fun increasesAndDecreasesHaveRealIntermediateFramesAndContinuouslySizedPills() = variants { fixture ->
        fixture.scene().use { scene ->
            val start = scene.settledFrame()
            val smallWidth = fixture.bounds.width
            fixture.count = 1_000
            val increasing = fixture.frames(scene)
            val large = scene.settledFrame()
            val largeWidth = fixture.bounds.width
            assertTrue(largeWidth > smallWidth)
            assertTrue(increasing.any { it.second > smallWidth && it.second < largeWidth },
                "The actual pill must pass through intermediate widths when its label grows.")
            assertTrue(increasing.any { !samePixels(it.first, start) && !samePixels(it.first, large) },
                "The displayed glyphs must animate between their actual old and new labels.")
            assertTrue(increasing.all { it.second in smallWidth..largeWidth }, "Growth must not overshoot.")
            fixture.assertDescription(scene, "1000 条未读")
            fixture.export(start, "increase-start")
            fixture.export(increasing[5].first, "increase-middle")
            fixture.export(large, "increase-finish")

            fixture.count = 9
            val decreasing = fixture.frames(scene)
            val small = scene.settledFrame()
            assertTrue(decreasing.any { it.second > smallWidth && it.second < largeWidth },
                "The actual pill must also shrink through intermediate widths.")
            assertTrue(decreasing.any { !samePixels(it.first, large) && !samePixels(it.first, small) })
            assertTrue(decreasing.all { it.second in smallWidth..largeWidth }, "Shrinkage must not overshoot.")
            assertTrue(samePixels(start, small), "Returning to the same count must recover its exact settled pixels.")
            fixture.assertDescription(scene, "9 条未读")
            fixture.export(decreasing[5].first, "decrease-middle")
            fixture.export(small, "decrease-finish")
        }
    }

    @Test
    fun quickUpdatesAndReversalsConvergeToTheLatestActualCount() = variants { fixture ->
        fixture.scene().use { scene ->
            scene.settledFrame()
            for (count in listOf(999, 1_000, 999_999, 99, 9, 21, 2)) {
                fixture.count = count
                scene.settledFrame(2, 16)
                fixture.assertDescription(scene, "$count 条未读")
            }
            val settled = scene.settledFrame()
            fixture.export(settled, "rapid-reversal-finish")
            assertTrue(samePixels(settled, scene.settledFrame(8, 16)),
                "Finished transitions must stay stationary instead of retaining old glyphs or running forever.")
            fixture.animated = false
            val original = scene.settledFrame()
            if (!samePixels(settled, original)) fixture.export(original, "rapid-reversal-original-failure")
            assertTrue(samePixels(settled, original),
                "The latest count after interruption must match the original static layout exactly.")
        }
    }

    @Test
    fun equalCappedLabelsStayStillAndThresholdChangesAnimate() = variants { fixture ->
        fixture.count = 1_000
        fixture.scene().use { scene ->
            val capped = scene.settledFrame()
            val width = fixture.bounds.width
            for (count in listOf(1_001, 9_999, Int.MAX_VALUE, 1_000)) {
                fixture.count = count
                repeat(4) {
                    assertTrue(samePixels(capped, scene.settledFrame(1, 16)),
                        "An updated native count with the same 999+ label must not animate.")
                    assertEquals(width, fixture.bounds.width)
                }
                fixture.assertDescription(scene, "$count 条未读")
            }
            fixture.count = 999
            val frames = fixture.frames(scene)
            val belowThreshold = scene.settledFrame()
            assertTrue(frames.any { !samePixels(it.first, capped) && !samePixels(it.first, belowThreshold) })
            fixture.export(frames[5].first, "cap-threshold-middle")
            fixture.export(belowThreshold, "cap-threshold-finish")
        }
    }

    @Test
    fun allSettledCountsAndUnnumberedDotsPreserveTheOriginalLayoutAndPixels() = variants { fixture ->
        fixture.scene().use { scene ->
            for (count in listOf(0, 1, 9, 10, 99, 100, 999, 1_000, null)) {
                fixture.animated = true
                fixture.count = count
                val animated = scene.settledFrame()
                val bounds = fixture.bounds
                val expectedHeight = (if (count == null) 8 else if (fixture.compact) 18 else 22) * fixture.density
                assertTrue(abs(bounds.height - expectedHeight) <= 0.5f)
                fixture.assertDescription(scene, count?.let { "$it 条未读" } ?: "未读")
                fixture.animated = false
                val original = scene.settledFrame()
                assertEquals(bounds, fixture.bounds)
                if (!samePixels(animated, original)) {
                    fixture.export(animated, "static-$count-animated-failure")
                    fixture.export(original, "static-$count-original-failure")
                }
                assertTrue(samePixels(animated, original),
                    "Settled count $count must retain the original centered glyphs, pill, padding and height.")
            }
        }
    }

    private fun variants(test: (Fixture) -> Unit) {
        for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) {
            for (density in listOf(1f, 1.25f)) {
                for (compact in listOf(true, false)) test(Fixture(mode, density, compact))
            }
        }
    }

    private inner class Fixture(val mode: ColorSchemeMode, val density: Float, val compact: Boolean) {
        var count: Int? by mutableStateOf(9)
        var animated by mutableStateOf(true)
        private var measuredBounds: Rect? = null
        val bounds: Rect get() = assertNotNull(measuredBounds)

        fun scene() = ComposePixelScene(160, 80) {
            CompositionLocalProvider(LocalDensity provides Density(density)) {
                val controller = remember { ThemeController(mode) }
                MiuixTheme(controller = controller) {
                    Box(Modifier.fillMaxSize().background(if (mode == ColorSchemeMode.Light) Color.White else Color.Black),
                        contentAlignment = Alignment.Center) {
                        val modifier = Modifier.onGloballyPositioned { measuredBounds = it.boundsInRoot() }
                        if (animated) UnreadBadge(count, modifier, compact) else OriginalBadge(count, modifier, compact)
                    }
                }
            }
        }

        fun frames(scene: ComposePixelScene): List<Pair<ComposePixelFrame, Float>> =
            List(16) { scene.settledFrame(1, 16) to bounds.width }

        fun assertDescription(scene: ComposePixelScene, description: String) {
            assertEquals(1, scene.semanticsBounds {
                it.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true
            }.size, "Accessibility must expose the actual latest native count, even during a transition.")
        }

        fun export(frame: ComposePixelFrame, name: String) {
            val folder = Path.of(System.getProperty("java.io.tmpdir"), "unread-badge-motion",
                "${mode.name.lowercase()}-${density}-${if (compact) "compact" else "regular"}")
            Files.createDirectories(folder)
            val image = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until frame.height) for (x in 0 until frame.width) image.setRGB(x, y, frame[x, y])
            check(ImageIO.write(image, "png", folder.resolve("$name.png").toFile()))
        }
    }

    /** Frozen pre-change layout: animate neither its glyphs nor its dimensions. */
    @Composable
    private fun OriginalBadge(count: Int?, modifier: Modifier, compact: Boolean) {
        val colors = MiuixTheme.colorScheme
        if (count == null) {
            Box(modifier.size(8.dp).background(colors.primary, CircleShape))
        } else {
            val height = if (compact) 18.dp else 22.dp
            val fontSize = if (compact) 10.sp else 11.sp
            Box(modifier.height(height).widthIn(min = height).background(colors.primary, CircleShape)
                .padding(horizontal = if (compact) 5.dp else 6.dp), contentAlignment = Alignment.Center) {
                Text(if (count > 999) "999+" else count.toString(),
                    style = MiuixTheme.textStyles.main.copy(fontSize = fontSize, lineHeight = fontSize,
                        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
                        fontFeatureSettings = "tnum", textAlign = TextAlign.Center),
                    fontWeight = FontWeight.SemiBold, color = colors.onPrimary, maxLines = 1, softWrap = false)
            }
        }
    }

    private fun samePixels(first: ComposePixelFrame, second: ComposePixelFrame): Boolean =
        first.width == second.width && first.height == second.height &&
            (0 until first.height).all { y -> (0 until first.width).all { x -> first[x, y] == second[x, y] } }
}
