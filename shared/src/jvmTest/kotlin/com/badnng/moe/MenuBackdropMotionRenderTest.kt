package com.badnng.moe

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.basic.PopupLayoutPosition
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Window-coordinate pixel regressions, using the actual shared menu surface and Miuix sampler. */
class MenuBackdropMotionRenderTest {
    @Test
    fun movingMenuGlassSamplesTheCurrentWindowPixelsWithoutScalingItsSource() {
        val positions = listOf(
            "below-left" to PopupLayoutPosition(showBelow = true, showAbove = false, isRightAligned = false),
            "below-right" to PopupLayoutPosition(showBelow = true, showAbove = false, isRightAligned = true),
            "above-right" to PopupLayoutPosition(showBelow = false, showAbove = true, isRightAligned = true),
            "center-left" to PopupLayoutPosition(showBelow = false, showAbove = false, isRightAligned = false),
        )
        for (density in listOf(1f, 1.25f)) for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) {
            for ((name, position) in positions) {
                val fraction = mutableStateOf(1f)
                var visibleBounds: Rect? = null
                ComposePixelScene((1060 * density).roundToInt(), (900 * density).roundToInt()) {
                    MotionTheme(density, mode) {
                        val backdrop = assertNotNull(rememberChatBackdrop())
                        CompositionLocalProvider(LocalChatBackdrop provides backdrop,
                            LocalChatShortcutPreviewBounds provides { visibleBounds = it }) {
                            Box(Modifier.fillMaxSize()) {
                                // Neither the recorded layer nor its consumer starts at the window origin.
                                Box(Modifier.offset(68.dp, 44.dp).size(900.dp, 760.dp)) {
                                    SpatialSource(backdrop)
                                    Box(Modifier.offset(180.dp, 190.dp).size(320.dp, 360.dp)
                                        .chatShortcutAnimatedSurface({ fraction.value }, { 1f }, position,
                                            surfaceOpacity = 0.30f))
                                }
                            }
                        }
                    }
                }.use { scene ->
                    // A stationary glass surface is the oracle at each *window* pixel. Its shape
                    // covers every later revealed interior, so no test-side blur implementation
                    // or reproduction of the production sampling matrix can hide an error.
                    val stationary = scene.settledFrame(6)
                    val fullBounds = assertNotNull(visibleBounds)
                    val directory = fixtureDirectory("$mode-$density-$name")
                    writeFrame(directory.resolve("stationary.png"), stationary)
                    for (progress in listOf(0.25f, 0.5f, 0.75f, 1f)) {
                        fraction.value = progress
                        val moving = scene.settledFrame(4)
                        val currentBounds = assertNotNull(visibleBounds)
                        val points = interiorPoints(currentBounds)
                        assertTrue(points.size >= 15 && currentBounds.height >= 20f * density,
                            "The fixture must sample the revealed glass interior: $name/$progress/$density/$mode $currentBounds")
                        assertTrue(points.all { fullBounds.contains(it) },
                            "The stationary oracle must cover the identical window pixels: $currentBounds / $fullBounds")
                        val errors = points.map { point ->
                            distance(moving[point.x.roundToInt(), point.y.roundToInt()],
                                stationary[point.x.roundToInt(), point.y.roundToInt()])
                        }.sorted()
                        val median = errors[errors.size / 2]
                        val upper = errors[(errors.size * 0.9f).toInt().coerceAtMost(errors.lastIndex)]
                        writeFrame(directory.resolve("fraction-$progress.png"), moving)
                        val fixture = "$name/$progress density=$density mode=$mode bounds=$currentBounds frames=$directory"
                        println("Menu backdrop window alignment: $fixture median=$median p90=$upper errors=$errors")
                        // Broad non-periodic bands survive the real 12px blur. A small allowance
                        // covers fractional pixel edges and the blur kernel's transformed radius;
                        // changing where the background is sampled produces a much larger error.
                        assertTrue(median <= 4 && upper <= 7,
                            "The moving menu magnifies or offsets its sampled source instead of reading the current window pixels: " +
                                "median=$median p90=$upper errors=$errors $fixture")
                    }
                }
            }
        }
    }

    @Test
    fun realNativePopupKeepsItsBackdropRegisteredWhileTheMenuExits() {
        for (density in listOf(1f, 1.25f)) for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) {
            val expanded = mutableStateOf(false)
            var visibleBounds: Rect? = null
            ComposePixelScene((1060 * density).roundToInt(), (900 * density).roundToInt()) {
                MotionTheme(density, mode) {
                    val backdrop = assertNotNull(rememberChatBackdrop())
                    CompositionLocalProvider(LocalChatBackdrop provides backdrop,
                        LocalChatShortcutPreviewBounds provides { visibleBounds = it }) {
                        Box(Modifier.fillMaxSize()) {
                            Box(Modifier.offset(68.dp, 44.dp).size(900.dp, 760.dp)) {
                                SpatialSource(backdrop)
                                Box(Modifier.offset(600.dp, 120.dp).size(36.dp)) {
                                    // This is the production Popup, position provider, shadow padding,
                                    // animation specs, composition-local capture and common surface.
                                    ChatShortcutPopup(expanded.value, { expanded.value = false },
                                        width = 320.dp, surfaceOpacity = 0.30f) {
                                        Box(Modifier.fillMaxWidth().height(300.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }.use { scene ->
                val source = scene.settledFrame(6)
                expanded.value = true
                val stationary = scene.settledFrame()
                val fullBounds = assertNotNull(visibleBounds)
                val directory = fixtureDirectory("$mode-$density-real-popup")
                writeFrame(directory.resolve("source.png"), source)
                writeFrame(directory.resolve("stationary.png"), stationary)
                expanded.value = false
                var measuredFrames = 0
                repeat(24) { index ->
                    val moving = scene.settledFrame(1, stepMillis = 16)
                    val currentBounds = visibleBounds
                    if (currentBounds != null && currentBounds.width in
                        (fullBounds.width * 0.25f)..(fullBounds.width * 0.93f) &&
                        currentBounds.height > 20f * density) {
                        val points = interiorPoints(currentBounds)
                        assertTrue(points.all { fullBounds.contains(it) },
                            "The relocated Popup and its stationary oracle must share window coordinates: $currentBounds / $fullBounds")
                        // The real animation also changes alpha. Fit one uniform alpha from all
                        // channels/points; it may fade the glass but may not change its registration.
                        // A spatially wrong source cannot satisfy this shared-alpha pixel model.
                        val fit = fitUniformAlpha(source, stationary, moving, points)
                        writeFrame(directory.resolve("exit-$index.png"), moving)
                        println("Actual Popup backdrop alignment: density=$density mode=$mode frame=$index " +
                            "bounds=$currentBounds alpha=${fit.alpha} rms=${fit.rms} p90=${fit.p90} frames=$directory")
                        if (fit.alpha >= 0.25f) {
                            measuredFrames++
                            assertTrue(fit.alpha <= 1.04f && fit.rms <= 4f && fit.p90 <= 7f,
                                "A fading native Popup must sample the stationary source at its actual window position: " +
                                    "fit=$fit density=$density mode=$mode frame=$index bounds=$currentBounds frames=$directory")
                        }
                    }
                }
                assertTrue(measuredFrames >= 1,
                    "At least one visibly moving, nontransparent actual Popup frame must be checked: " +
                        "density=$density mode=$mode frames=$directory")
            }
        }
    }

    @Composable
    private fun MotionTheme(density: Float, mode: ColorSchemeMode, content: @Composable () -> Unit) {
        val controller = remember { ThemeController(mode) }
        CompositionLocalProvider(LocalDensity provides Density(density)) {
            MiuixTheme(controller = controller, content = content)
        }
    }

    @Composable
    private fun SpatialSource(backdrop: LayerBackdrop) {
        Canvas(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
            // Independent irregular broad bands in both axes make a unique spatial fingerprint.
            // Unlike repeating thin stripes, these cannot be averaged to a uniform blur color.
            drawRect(Brush.horizontalGradient(
                0f to Color(0.10f, 0f, 0f), 0.27f to Color(0.85f, 0f, 0f),
                0.56f to Color(0.15f, 0f, 0f), 0.82f to Color(0.95f, 0f, 0f),
                1f to Color(0.20f, 0f, 0f),
            ))
            drawRect(Brush.verticalGradient(
                0f to Color(0f, 0.80f, 0f), 0.24f to Color(0f, 0.20f, 0f),
                0.62f to Color(0f, 0.90f, 0f), 1f to Color(0f, 0.05f, 0f),
            ), blendMode = BlendMode.Plus)
            drawRect(Brush.linearGradient(
                0f to Color(0f, 0f, 0.15f), 0.31f to Color(0f, 0f, 0.90f),
                0.73f to Color(0f, 0f, 0.10f), 1f to Color(0f, 0f, 0.75f),
                start = Offset.Zero, end = Offset(size.width, size.height),
            ), blendMode = BlendMode.Plus)
        }
    }

    private fun interiorPoints(bounds: Rect): List<Offset> = buildList {
        for (y in listOf(0.35f, 0.5f, 0.65f)) for (x in listOf(0.2f, 0.35f, 0.5f, 0.65f, 0.8f)) {
            add(Offset(bounds.left + bounds.width * x, bounds.top + bounds.height * y))
        }
    }

    private data class AlphaFit(val alpha: Float, val rms: Float, val p90: Float)

    private fun fitUniformAlpha(source: ComposePixelFrame, stationary: ComposePixelFrame,
        moving: ComposePixelFrame, points: List<Offset>): AlphaFit {
        val pairs = points.flatMap { point ->
            val x = point.x.roundToInt()
            val y = point.y.roundToInt()
            listOf(0, 8, 16).map { channel ->
                val original = source[x, y] ushr channel and 255
                val expected = (stationary[x, y] ushr channel and 255) - original
                val actual = (moving[x, y] ushr channel and 255) - original
                expected.toFloat() to actual.toFloat()
            }
        }
        val denominator = pairs.sumOf { (expected, _) -> (expected * expected).toDouble() }
        assertTrue(denominator > 1000, "The real glass must have a visible, spatially identifiable signal.")
        val alpha = (pairs.sumOf { (expected, actual) -> (expected * actual).toDouble() } / denominator).toFloat()
        val errors = pairs.map { (expected, actual) -> abs(actual - expected * alpha) }.sorted()
        return AlphaFit(alpha, sqrt(errors.sumOf { (it * it).toDouble() } / errors.size).toFloat(),
            errors[(errors.size * 0.9f).toInt().coerceAtMost(errors.lastIndex)])
    }

    private fun fixtureDirectory(name: String): Path =
        Path.of(System.getProperty("java.io.tmpdir"), "menu-backdrop-motion", name).also { Files.createDirectories(it) }

    private fun writeFrame(path: Path, frame: ComposePixelFrame) {
        val image = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until frame.height) for (x in 0 until frame.width) image.setRGB(x, y, frame[x, y])
        check(ImageIO.write(image, "png", path.toFile()))
    }

    private fun distance(a: Int, b: Int): Int =
        listOf(0, 8, 16).maxOf { abs((a ushr it and 255) - (b ushr it and 255)) }
}
