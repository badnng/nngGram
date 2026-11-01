package com.badnng.moe

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.ChatItem
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.ChatToken
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Exercises the real header, relocated Scaffold host and every sampled enter/exit frame. */
class ShortcutPopupAnimationRenderTest {
    @Test
    fun headerOperationsHaveOneAnimatedGlassOutlineAndAnUnclippedBottomShadow() {
        for (density in listOf(1f, 1.25f)) for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) {
            verifyHeaderMenu(density, mode)
        }
    }

    private fun verifyHeaderMenu(density: Float, mode: ColorSchemeMode) {
        val expanded = mutableStateOf(false)
        var dismissFinished = 0
        var drawnBounds: Rect? = null
        val chat = ChatItem(10, "Synthetic group", basicGroupId = 20)
        val snapshot = ChatSnapshot(enabled = true, selected = chat, token = ChatToken(1, 1, chat.id))
        val samples = mutableListOf<Sample>()
        ComposePixelScene((900 * density).roundToInt(), (480 * density).roundToInt()) {
            val controller = remember { ThemeController(mode) }
            CompositionLocalProvider(LocalDensity provides Density(density),
                LocalChatShortcutPreviewBounds provides { drawnBounds = it }) {
                MiuixTheme(controller = controller) {
                    Scaffold(Modifier.fillMaxSize(), contentWindowInsets = WindowInsets(0, 0, 0, 0)) {
                        // Keep this local below the host, as in the real workspace. The popup
                        // must retain this exact source when its content moves into the host.
                        val backdrop = assertNotNull(rememberChatBackdrop())
                        CompositionLocalProvider(LocalChatBackdrop provides backdrop) {
                            Box(Modifier.fillMaxSize()) {
                                Canvas(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                                    val stripe = 4.dp.toPx()
                                    repeat((size.width / stripe).toInt() + 1) { index ->
                                        drawRect(if (index % 2 == 0) Color(0xffe0528e) else Color(0xff27c8bd),
                                            topLeft = Offset(index * stripe, 0f), size = Size(stripe, size.height))
                                    }
                                }
                                ConversationHeader(chat, snapshot, ChatFeatureActions(), active = true, onBack = null,
                                    menuOpen = expanded.value, onMenuOpen = { expanded.value = it }, onMarkRead = {},
                                    onBounds = {}, onSearch = {}, onDetails = {}, onMedia = {}, showInfoButton = true,
                                    onPinned = {}, onTogglePinned = {}, pinnedHidden = false,
                                    onMenuDismissFinished = { dismissFinished++ },
                                    modifier = Modifier.padding(horizontal = 40.dp, vertical = 24.dp))
                            }
                        }
                    }
                }
            }
        }.use { scene ->
            fun capture(phase: String, index: Int, settled: Boolean = false) =
                scene.capture(phase, index, settled) { drawnBounds }
            val source = scene.settledFrame()
            val anchor = assertNotNull(scene.semanticsBounds {
                "群操作" in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            }.singleOrNull())
            scene.moveTo(anchor.center.x, anchor.center.y)
            scene.pressAt(anchor.center.x, anchor.center.y)
            scene.releaseAt(anchor.center.x, anchor.center.y)
            scene.moveTo(20f * density, 450f * density)
            repeat(8) { samples += capture("opening", it) }
            val opened = capture("open", 0, settled = true)
            samples += opened
            expanded.value = false
            repeat(8) { samples += capture("closing", it) }
            val closed = capture("closed", 0, settled = true)
            samples += closed

            val panel = assertNotNull(opened.panel, "The real animated glass panel must expose its own bounds.")
            val visiblePanel = assertNotNull(opened.visible, "The settled menu must actually draw a glass outline.")
            val row = assertNotNull(opened.row, "The actual operation row must remain present after opening.")
            val report = exportFrames(source, samples, panel, density, mode)
            val fixture = "density=$density mode=$mode frames=$report"
            val probeX = (panel.right - 18 * density).roundToInt()
            val probeY = row.center.y.roundToInt()
            val glassSignal = distance(opened.pixels[probeX, probeY], source[probeX, probeY])
            assertTrue(glassSignal > 70, "The fixture must resolve the actual tinted glass body: $glassSignal $fixture")
            // Shadows are a small darkening of a saturated source. A second opaque/native
            // container produces a much larger signal, even during a nearly transparent frame.
            val ghostThreshold = maxOf(70, (glassSignal * 0.62f).roundToInt())
            val region = panel.expand(36 * density)
            val visible = samples.filter { it.panel != null && it.row != null }
            for (sample in visible) {
                val bounds = assertNotNull(sample.panel)
                val action = assertNotNull(sample.row)
                val painted = sample.visible ?: Rect.Zero
                val outside = count(sample.pixels, region) { x, y ->
                    y >= panel.top - 3 * density && !painted.expand(2 * density).contains(Offset(x + 0.5f, y + 0.5f)) &&
                        distance(sample.pixels[x, y], source[x, y]) > ghostThreshold
                }
                assertTrue(outside <= 12 * density * density,
                    "A second filled outline remains outside the animated menu: phase=${sample.phase}/${sample.index} " +
                        "outside=$outside threshold=$ghostThreshold visible=$painted full=$bounds $fixture")
                assertTrue(action.left >= bounds.left - density && action.right <= bounds.right + density &&
                    action.top >= bounds.top - density && action.bottom <= bounds.bottom + density,
                    "The action row and glass must stay inside the same animated outline: $sample $fixture")
                assertTrue(abs(action.width / bounds.width - row.width / panel.width) < 0.04f &&
                    abs((action.top - bounds.top) / bounds.height - (row.top - panel.top) / panel.height) < 0.04f,
                    "The action row must scale and move with its glass instead of animating independently: $sample $fixture")
            }
            for (phase in listOf("opening", "closing")) {
                val partial = visible.filter { it.phase == phase && it.visible != null &&
                    (abs(it.visible.width - visiblePanel.width) > density || abs(it.visible.height - visiblePanel.height) > density) }
                assertTrue(partial.size >= 2,
                    "The real $phase transition must retain at least two observable revealed/scaled frames: $fixture")
            }
            verifyBottomShadow(source, opened, visiblePanel, density, fixture)
            visible.filter { it.phase != "open" && it.visible != null && it.visible.height > 30 * density }.filter { sample ->
                val p = sample.visible!!
                val x = (p.right - 18 * density).roundToInt()
                val y = minOf(sample.row!!.center.y, p.bottom - 12 * density).roundToInt()
                distance(sample.pixels[x, y], source[x, y]) > glassSignal * 0.65
            }.forEach { verifyBottomShadow(source, it, it.visible!!, density, fixture) }
            assertTrue(closed.panel == null && closed.row == null && closed.visible == null && dismissFinished > 0,
                "Exit completion must remove the retained panel and action rows: $fixture")
            val residual = count(closed.pixels, region) { x, y ->
                y >= panel.top - 3 * density && distance(closed.pixels[x, y], source[x, y]) > 2
            }
            assertTrue(residual == 0, "Closing must restore every sampled source pixel without a residual plate or shadow: $residual $fixture")
        }
    }

    private fun verifyBottomShadow(source: ComposePixelFrame, sample: Sample, panel: Rect, density: Float, fixture: String) {
        fun darkening(distanceDp: Float): Double {
            val y = (panel.bottom + distanceDp * density).roundToInt()
            val x = panel.center.x.roundToInt()
            return ((x - (10 * density).roundToInt())..(x + (10 * density).roundToInt())).map { at ->
                luminance(source[at, y]) - luminance(sample.pixels[at, y])
            }.average()
        }
        val near = darkening(2f)
        val middle = darkening(14f)
        val far = darkening(30f)
        assertTrue(near > 1 && near > far + 1 && middle >= far - 0.5 && abs(far) <= 1.5,
            "The bottom shadow must exist beyond the visible panel and fade smoothly: " +
                "phase=${sample.phase}/${sample.index} near=$near middle=$middle far=$far $fixture")
    }

    private data class Sample(val phase: String, val index: Int, val pixels: ComposePixelFrame, val panel: Rect?,
        val row: Rect?, val visible: Rect?)

    private fun ComposePixelScene.capture(phase: String, index: Int, settled: Boolean = false,
        visible: () -> Rect?): Sample {
        val pixels = settledFrame(if (settled) 24 else 1)
        return Sample(phase, index, pixels,
            semanticsBounds { "快捷操作菜单" in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }.singleOrNull(),
            semanticsBounds { it.getOrNull(SemanticsProperties.Role) == Role.Button &&
                it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == "查看群组信息" } }.singleOrNull(),
            visible())
    }

    private fun exportFrames(source: ComposePixelFrame, samples: List<Sample>, panel: Rect, density: Float,
        mode: ColorSchemeMode): Path {
        val directory = Path.of(System.getProperty("java.io.tmpdir"), "shortcut-popup-animation", "${mode}-$density")
        Files.createDirectories(directory)
        val crop = panel.expand(36 * density)
        fun write(name: String, frame: ComposePixelFrame) {
            val left = floor(crop.left).toInt().coerceAtLeast(0)
            val top = floor(crop.top).toInt().coerceAtLeast(0)
            val right = ceil(crop.right).toInt().coerceAtMost(frame.width)
            val bottom = ceil(crop.bottom).toInt().coerceAtMost(frame.height)
            val image = BufferedImage(right - left, bottom - top, BufferedImage.TYPE_INT_ARGB)
            for (y in top until bottom) for (x in left until right) image.setRGB(x - left, y - top, frame[x, y])
            check(ImageIO.write(image, "png", directory.resolve("$name.png").toFile()))
        }
        write("source", source)
        samples.forEach { write("${it.phase}-${it.index}", it.pixels) }
        Files.writeString(directory.resolve("geometry.txt"), samples.joinToString("\n") {
            "${it.phase}/${it.index}: full=${it.panel} visible=${it.visible} row=${it.row}"
        })
        println("Synthetic shortcut animation frames: $directory")
        return directory
    }

    private fun count(frame: ComposePixelFrame, region: Rect, predicate: (Int, Int) -> Boolean): Int {
        var count = 0
        for (y in floor(region.top).toInt().coerceAtLeast(0) until ceil(region.bottom).toInt().coerceAtMost(frame.height))
            for (x in floor(region.left).toInt().coerceAtLeast(0) until ceil(region.right).toInt().coerceAtMost(frame.width))
                if (predicate(x, y)) count++
        return count
    }

    private fun Rect.expand(value: Float) = Rect(left - value, top - value, right + value, bottom + value)
    private fun luminance(pixel: Int) = ((pixel ushr 16 and 255) + (pixel ushr 8 and 255) + (pixel and 255)) / 3
    private fun distance(a: Int, b: Int) = listOf(0, 8, 16).maxOf { abs((a ushr it and 255) - (b ushr it and 255)) }
}
