package com.badnng.moe

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.ChatItem
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.ChatToken
import com.badnng.moe.chat.StickerSection
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Exercises transforms which are entirely outside the glass consumer's composition and draw state. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class MenuBackdropExternalMotionRenderTest {
    @Test
    fun aStableGlassChildRedrawsItsSamplingWhenOnlyAnAncestorLayerTransformChanges() {
        for (density in listOf(1f, 1.25f)) for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) {
            val oracle = mutableStateOf(true)
            val motion = mutableStateOf(Motion())
            var childCompositions = 0
            val countComposition = { childCompositions++; Unit }
            ComposePixelScene((800 * density).roundToInt(), (680 * density).roundToInt()) {
                Theme(density, mode) {
                    val backdrop = assertNotNull(rememberChatBackdrop())
                    CompositionLocalProvider(LocalChatBackdrop provides backdrop) {
                        Box(Modifier.fillMaxSize()) {
                            Box(Modifier.offset(42.dp, 36.dp).size(710.dp, 600.dp)) {
                                SpatialSource(backdrop)
                                if (oracle.value) Box(Modifier.fillMaxSize()
                                    .chatGlassSurface(0.dp, shadow = false, tintAlpha = .30f))
                                else Box(Modifier.offset(200.dp, 140.dp).size(280.dp, 300.dp).graphicsLayer {
                                    // All mutable reads are confined to the ancestor layer block.
                                    // No animation progress, stamp or state is passed to StableGlass.
                                    val value = motion.value
                                    scaleX = value.scaleX
                                    scaleY = value.scaleY
                                    translationX = value.x * density
                                    translationY = value.y * density
                                    transformOrigin = TransformOrigin(value.originX, value.originY)
                                }) {
                                    StableGlass(countComposition)
                                }
                            }
                        }
                    }
                }
            }.use { scene ->
                val reference = scene.settledFrame(6)
                oracle.value = false
                scene.settledFrame(6)
                val initialCompositions = childCompositions
                assertTrue(initialCompositions > 0)
                val directory = directory("$mode-$density-ancestor")
                write(directory.resolve("stationary-window-oracle.png"), reference)
                val motions = listOf(
                    Motion(.82f, .73f, 12f, -16f, 0f, 1f),
                    Motion(.57f, .66f, -13f, 19f, 1f, 0f),
                    Motion(.42f, .55f, 24f, 8f, .5f, .5f),
                    Motion(.68f, .86f, -21f, -12f, 0f, 0f),
                    Motion(.91f, .72f, 16f, 14f, 1f, 1f),
                    Motion(),
                )
                for ((index, value) in motions.withIndex()) {
                    motion.value = value
                    // Each mutation is a new animation frame. Waiting for unrelated composition
                    // or updating a test-specific draw stamp would hide a stale display list.
                    val frame = scene.settledFrame(1, 16)
                    val bounds = scene.description("外部动画玻璃")
                    val points = points(bounds, listOf(.25f, .5f, .75f))
                    val errors = points.map { point ->
                        difference(frame[point.x.roundToInt(), point.y.roundToInt()],
                            reference[point.x.roundToInt(), point.y.roundToInt()])
                    }.sorted()
                    val median = errors[errors.size / 2]
                    val p90 = errors[(errors.size * .9f).toInt().coerceAtMost(errors.lastIndex)]
                    write(directory.resolve("motion-$index.png"), frame)
                    println("External menu transform: density=$density mode=$mode frame=$index motion=$value " +
                        "bounds=$bounds median=$median p90=$p90 compositions=$childCompositions frames=$directory")
                    assertEquals(initialCompositions, childCompositions,
                        "Only the external graphicsLayer may read the changing transform; the glass child must stay composed once.")
                    assertTrue(median <= 4 && p90 <= 7,
                        "An externally animated ancestor must invalidate the child's backdrop sampling immediately: " +
                            "density=$density mode=$mode frame=$index motion=$value bounds=$bounds " +
                            "median=$median p90=$p90 errors=$errors frames=$directory")
                }
            }
        }
    }

    @Test
    fun actualStickerSelectorKeepsWindowRegistrationDuringExitReversalAndViewportChanges() {
        for ((mode, density) in listOf(ColorSchemeMode.Light to 1f, ColorSchemeMode.Dark to 1.25f)) {
            val oracle = mutableStateOf(true)
            val show = mutableStateOf(false)
            val viewport = mutableStateOf(IntSize((760 * density).roundToInt(), (660 * density).roundToInt()))
            val anchor = mutableStateOf(Rect(650 * density, 586 * density, 682 * density, 618 * density))
            val snapshot = ChatSnapshot(enabled = true, token = ChatToken(1, 1, 1),
                selected = ChatItem(1, "Synthetic sticker motion"),
                stickerSections = listOf(StickerSection(null, "最近", emptyList(), loaded = true)))
            ComposePixelScene((760 * density).roundToInt(), (660 * density).roundToInt()) {
                Theme(density, mode) {
                    val backdrop = assertNotNull(rememberChatBackdrop())
                    val originalWindow = LocalWindowInfo.current
                    val window = remember(originalWindow) {
                        object : WindowInfo by originalWindow {
                            override val containerSize: IntSize get() = viewport.value
                        }
                    }
                    CompositionLocalProvider(LocalChatBackdrop provides backdrop,
                        LocalWorkspaceMenuBackdrop provides backdrop, LocalWindowInfo provides window) {
                        Box(Modifier.fillMaxSize()) {
                            Box(Modifier.offset(30.dp, 22.dp).size(700.dp, 625.dp)) {
                                SpatialSource(backdrop)
                                if (oracle.value) Box(Modifier.fillMaxSize().chatGlassSurface(0.dp, shadow = false))
                            }
                            if (!oracle.value) StickerPicker(show.value, snapshot, ChatFeatureActions(), false,
                                { show.value = false }, anchor = anchor.value)
                        }
                    }
                }
            }.use { scene ->
                val reference = scene.settledFrame(6)
                oracle.value = false
                val source = scene.settledFrame(6)
                show.value = true
                val opened = scene.settledFrame()
                val panel = scene.description("贴纸选择面板")
                // The lower empty grid is actual transparent selector glass, clear of the search,
                // empty-state label, footer artwork, corners and both scrollbar/shadow regions.
                val probes = points(panel, listOf(.25f, .5f, .75f), listOf(.49f, .61f, .73f))
                val openedFit = fitAlpha(source, reference, opened, probes)
                assertTrue(abs(openedFit.alpha - 1f) <= .04f && openedFit.rms <= 4 && openedFit.p90 <= 7,
                    "The actual empty grid must match the stationary default glass oracle before motion: $openedFit")
                val directory = directory("$mode-$density-sticker")
                write(directory.resolve("source.png"), source)
                write(directory.resolve("stationary-window-oracle.png"), reference)
                write(directory.resolve("opened.png"), opened)
                var actualAnimationFrames = 0
                fun sample(phase: String, count: Int) {
                    repeat(count) { index ->
                        val frame = scene.settledFrame(1, 16)
                        val fit = fitAlpha(source, reference, frame, probes)
                        write(directory.resolve("$phase-$index.png"), frame)
                        println("Actual sticker backdrop: density=$density mode=$mode phase=$phase frame=$index " +
                            "alpha=${fit.alpha} rms=${fit.rms} p90=${fit.p90} frames=$directory")
                        if (fit.alpha >= .25f) {
                            assertTrue(fit.alpha <= 1.04f && fit.rms <= 4 && fit.p90 <= 7,
                                "AnimatedVisibility must retain the real StickerPicker's window sampling: " +
                                    "density=$density mode=$mode phase=$phase frame=$index fit=$fit frames=$directory")
                            if (fit.alpha < .99f) actualAnimationFrames++
                        }
                    }
                }
                show.value = false
                sample("exit", 8)
                show.value = true
                sample("reverse", 10)
                scene.settledFrame()
                assertTrue(actualAnimationFrames >= 4,
                    "At least four genuinely intermediate exit/reversal frames must be checked: $actualAnimationFrames $directory")
                viewport.value = IntSize((630 * density).roundToInt(), (580 * density).roundToInt())
                anchor.value = Rect(540 * density, 508 * density, 572 * density, 540 * density)
                val resized = scene.settledFrame(6)
                val resizedPanel = scene.description("贴纸选择面板")
                assertTrue(abs(resizedPanel.left - panel.left) > 40f * density &&
                    abs(resizedPanel.height - panel.height) > 20f * density,
                    "The real popup must move and resize instead of only rerendering the same fixture: $panel -> $resizedPanel")
                val resizedProbes = points(resizedPanel, listOf(.25f, .5f, .75f), listOf(.49f, .61f, .73f))
                val resizedFit = fitAlpha(source, reference, resized, resizedProbes)
                write(directory.resolve("resized.png"), resized)
                assertTrue(abs(resizedFit.alpha - 1f) <= .04f && resizedFit.rms <= 4 && resizedFit.p90 <= 7,
                    "A native selector relocation/resize must read its new window coordinates: $resizedFit $directory")
            }
        }
    }

    @Composable
    private fun StableGlass(onComposition: () -> Unit) {
        SideEffect(onComposition)
        Box(Modifier.fillMaxSize().chatGlassSurface(15.dp, shadow = false, tintAlpha = .30f)
            .semantics { contentDescription = "外部动画玻璃" })
    }

    @Composable
    private fun Theme(density: Float, mode: ColorSchemeMode, content: @Composable () -> Unit) {
        val controller = remember { ThemeController(mode) }
        CompositionLocalProvider(LocalDensity provides Density(density)) {
            MiuixTheme(controller = controller, content = content)
        }
    }

    @Composable
    private fun SpatialSource(backdrop: LayerBackdrop) {
        Canvas(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
            drawRect(Brush.horizontalGradient(
                0f to Color(.10f, 0f, 0f), .27f to Color(.85f, 0f, 0f),
                .56f to Color(.15f, 0f, 0f), .82f to Color(.95f, 0f, 0f), 1f to Color(.20f, 0f, 0f)))
            drawRect(Brush.verticalGradient(
                0f to Color(0f, .80f, 0f), .24f to Color(0f, .20f, 0f),
                .62f to Color(0f, .90f, 0f), 1f to Color(0f, .05f, 0f)), blendMode = BlendMode.Plus)
            drawRect(Brush.linearGradient(
                0f to Color(0f, 0f, .15f), .31f to Color(0f, 0f, .90f),
                .73f to Color(0f, 0f, .10f), 1f to Color(0f, 0f, .75f),
                start = Offset.Zero, end = Offset(size.width, size.height)), blendMode = BlendMode.Plus)
        }
    }

    private data class Motion(val scaleX: Float = 1f, val scaleY: Float = 1f, val x: Float = 0f, val y: Float = 0f,
        val originX: Float = 0f, val originY: Float = 0f)

    private data class AlphaFit(val alpha: Float, val rms: Float, val p90: Float)

    private fun fitAlpha(source: ComposePixelFrame, reference: ComposePixelFrame,
        moving: ComposePixelFrame, points: List<Offset>): AlphaFit {
        val pairs = points.flatMap { point ->
            val x = point.x.roundToInt()
            val y = point.y.roundToInt()
            listOf(0, 8, 16).map { shift ->
                val original = source[x, y] ushr shift and 255
                ((reference[x, y] ushr shift and 255) - original).toFloat() to
                    ((moving[x, y] ushr shift and 255) - original).toFloat()
            }
        }
        val denominator = pairs.sumOf { (expected, _) -> (expected * expected).toDouble() }
        assertTrue(denominator > 1000, "The default selector glass must have a clear signal over the real spatial background.")
        val alpha = (pairs.sumOf { (expected, actual) -> (expected * actual).toDouble() } / denominator).toFloat()
        val errors = pairs.map { (expected, actual) -> abs(actual - expected * alpha) }.sorted()
        return AlphaFit(alpha, sqrt(errors.sumOf { (it * it).toDouble() } / errors.size).toFloat(),
            errors[(errors.size * .9f).toInt().coerceAtMost(errors.lastIndex)])
    }

    private fun points(bounds: Rect, fractionsX: List<Float>, fractionsY: List<Float> = fractionsX): List<Offset> = buildList {
        for (y in fractionsY) for (x in fractionsX) add(Offset(bounds.left + bounds.width * x, bounds.top + bounds.height * y))
    }

    private fun ComposePixelScene.description(label: String) = assertNotNull(semanticsBounds {
        label in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }.singleOrNull(), label)

    private fun difference(first: Int, second: Int) =
        listOf(0, 8, 16).maxOf { abs((first ushr it and 255) - (second ushr it and 255)) }

    private fun directory(name: String) = Path.of(System.getProperty("java.io.tmpdir"),
        "menu-backdrop-external-motion", name).also { Files.createDirectories(it) }

    private fun write(path: Path, frame: ComposePixelFrame) {
        val image = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until frame.height) for (x in 0 until frame.width) image.setRGB(x, y, frame[x, y])
        check(ImageIO.write(image, "png", path.toFile()))
    }
}
