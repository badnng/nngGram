package com.badnng.moe

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.ChatItem
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.ChatToken
import com.badnng.moe.chat.MediaFile
import com.badnng.moe.chat.MessageMedia
import com.badnng.moe.chat.StickerItem
import com.badnng.moe.chat.StickerSection
import com.badnng.moe.status.ConnectionPhase
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Actual native selector and workspace; all media and source pixels are synthetic and offline. */
class StickerPickerGlassRenderTest {
    @Test
    fun nativeSelectorCapturesWorkspaceMixedGlassOutsideTheTimelineLocalAndRetainsItsExit() {
        for (mode in appearances) for (density in densities) {
            val show = mutableStateOf(false)
            val blue = mutableStateOf(false)
            val ownership = ContextMenuBackdropState()
            val anchor = Rect(740f * density, 730f * density, 772f * density, 762f * density)
            val snapshot = fixture()
            ComposePixelScene((880 * density).roundToInt(), (800 * density).roundToInt()) {
                val controller = remember { ThemeController(mode) }
                CompositionLocalProvider(LocalDensity provides Density(density), LocalContextMenuBackdrop provides ownership) {
                    MiuixTheme(controller = controller) {
                        val backdrop = assertNotNull(rememberChatBackdrop())
                        CompositionLocalProvider(LocalWorkspaceMenuBackdrop provides backdrop, LocalChatBackdrop provides null) {
                            Box(Modifier.fillMaxSize()) {
                                // A real nonzero source origin catches sampling in Popup-local coordinates.
                                Canvas(Modifier.offset(80.dp, 40.dp).size(780.dp, 750.dp).layerBackdrop(backdrop)) {
                                    val stripe = 4.dp.toPx()
                                    repeat((size.width / stripe).toInt() + 1) { index ->
                                        val color = if (index % 2 != 0) Color.White else if (index * stripe < size.width / 2f)
                                            Color.Black else if (blue.value) Color.Blue else Color.Red
                                        drawRect(color, Offset(index * stripe, 0f), Size(stripe, size.height))
                                    }
                                }
                                StickerPicker(show.value, snapshot, ChatFeatureActions(), active = false,
                                    onDismiss = { show.value = false }, anchor = anchor)
                            }
                        }
                    }
                }
            }.use { scene ->
                val name = "${mode.name.lowercase()}-$density"
                val source = scene.settledFrame()
                show.value = true
                val entering = scene.settledFrame(5, 16)
                entering.save("$name-entering")
                val opened = scene.settledFrame()
                val panel = scene.description("贴纸选择面板")
                val x = (panel.right - 14f * density).roundToInt()
                val otherX = x - (4f * density).roundToInt()
                val y = panel.center.y.roundToInt()
                assertTrue(distance(source[x, y], source[otherX, y]) >= 150,
                    "The actual background must have high-frequency source detail: $name")
                assertTrue(distance(opened[x, y], opened[otherX, y]) <= 22,
                    "The actual native sticker card must soften the source detail: $name")
                assertTrue(distance(entering[x, y], opened[x, y]) > 4,
                    "The saved entering image must be an actual intermediate alpha/scale frame: $name")
                assertTrue(ownership.hasMountedMenus && ownership.isExpanded)
                assertFalse(ownership.hasExpandedBlurOwners,
                    "Local mixed glass must keep the original composer button crisp: $name")
                assertTrue(abs(panel.width - 345f * density) <= 1.5f)
                opened.save("$name-open-red")
                blue.value = true
                val changed = scene.settledFrame()
                assertTrue(distance(opened[x, y], changed[x, y]) > 10,
                    "An opaque shell or missing backdrop cannot react to a changed source: $name")
                assertEquals(panel, scene.description("贴纸选择面板"), "The tint update cannot change official capacity.")
                changed.save("$name-open-blue")
                show.value = false
                // Transition setup can use several frames before the animation clock starts.
                // Compare the whole card with both stable endpoints: one flat tint pixel can
                // stay unchanged even though its neighbouring source stripes are fading in.
                val exitFrames = List(12) { index ->
                    val frame = scene.settledFrame(1, 16)
                    if (index == 1) {
                        assertTrue(ownership.hasMountedMenus && !ownership.isExpanded,
                            "Closing retains drawing and timeline suppression while dropping actions: $name")
                        assertTrue(scene.descriptions("发送贴纸 合成").isEmpty())
                    }
                    Triple(frame, ownership.hasMountedMenus, ownership.isExpanded)
                }
                val closed = scene.settledFrame()
                assertFalse(ownership.hasMountedMenus)
                val endpointDifference = averageDifference(changed, closed, panel, density)
                assertTrue(endpointDifference > 30,
                    "The two stable endpoints must differ across the actual card area: $name")
                val intermediate = exitFrames.firstOrNull { (frame, mounted, expanded) ->
                    mounted && !expanded &&
                        averageDifference(frame, changed, panel, density) >= endpointDifference * .2 &&
                        averageDifference(frame, closed, panel, density) >= endpointDifference * .2
                }
                if (intermediate == null) exitFrames.forEachIndexed { index, (frame) -> frame.save("$name-exit-candidate-$index") }
                assertNotNull(intermediate,
                    "A mounted exit frame must differ materially from both the stable open and fully closed card: $name")
                    .first.save("$name-exiting")
                closed.save("$name-closed")
                // The source is unchanged during this final comparison, including its shadow band.
                show.value = true
                val reopened = scene.settledFrame()
                val shadowY = (panel.bottom + 3f * density).roundToInt()
                val shadowX = panel.center.x.roundToInt()
                assertTrue(distance(reopened[shadowX, shadowY], closed[shadowX, shadowY]) >= 2,
                    "The real 10dp native allowance must retain a visible downward shadow: $name")
            }
        }
    }

    @Test
    fun theWorkspaceStickerGlyphKeepsItsActualPixelsAndHoverTargetWhenItsSelectorOpens() {
        for (mode in appearances) for (density in densities) {
            val snapshot = fixture()
            ComposePixelScene((366 * density).roundToInt(), (800 * density).roundToInt()) {
                val controller = remember { ThemeController(mode) }
                val drafts = remember { mutableStateMapOf<Long, TextFieldValue>() }
                CompositionLocalProvider(LocalDensity provides Density(density)) {
                    MiuixTheme(controller = controller) {
                        ChatWorkspace(snapshot, ConnectionPhase.READY, drafts, {}, { _, _ -> }, {}, {},
                            { _, _, _ -> }, { _, _ -> }, {}, true, ChatFeatureActions(),
                            mode == ColorSchemeMode.Dark, {}, persistUiPreferences = false)
                    }
                }
            }.use { scene ->
                val name = "workspace-${mode.name.lowercase()}-$density"
                val initial = scene.settledFrame()
                val trigger = scene.description("贴纸")
                val initialContrast = glyphContrast(initial, trigger, density)
                assertTrue(initialContrast > 28, "The actual 20dp Miuix sticker glyph must paint: $name / $initialContrast")
                scene.moveTo(trigger.center.x, trigger.center.y)
                val opened = scene.settledFrame()
                assertTrue(scene.descriptions("贴纸选择面板").isNotEmpty(), "The actual hover target must open the selector: $name")
                assertEquals(trigger, scene.description("贴纸"))
                val openContrast = glyphContrast(opened, trigger, density)
                assertTrue(openContrast >= initialContrast * .75,
                    "Opening the selector must retain the button glyph instead of turning it into a blurred spot: $name / $initialContrast -> $openContrast")
                initial.save("$name-before")
                opened.save("$name-open")
            }
        }
    }

    private fun fixture(): ChatSnapshot {
        val item = StickerItem(2, 3, "合成", MessageMedia(MediaFile(102)))
        return ChatSnapshot(enabled = true, token = ChatToken(1, 1, 1),
            selected = ChatItem(1, "合成贴纸玻璃测试"), historyEnd = true, listEnd = true,
            stickers = listOf(item), stickerSections = listOf(StickerSection(null, "最近", listOf(item), loaded = true)))
    }

    private fun glyphContrast(frame: ComposePixelFrame, bounds: Rect, density: Float): Int {
        val center = bounds.center
        val radius = (9f * density).roundToInt()
        val values = (center.y.roundToInt() - radius..center.y.roundToInt() + radius).flatMap { y ->
            (center.x.roundToInt() - radius..center.x.roundToInt() + radius).map { x ->
                val pixel = frame[x, y]
                ((pixel ushr 16 and 255) + (pixel ushr 8 and 255) + (pixel and 255)) / 3
            }
        }
        return values.max() - values.min()
    }

    private fun ComposePixelScene.descriptions(label: String) = semanticsBounds {
        label in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }
    private fun ComposePixelScene.description(label: String) = assertNotNull(descriptions(label).singleOrNull(), label)
    private fun distance(a: Int, b: Int): Int = listOf(0, 8, 16).maxOf { abs((a ushr it and 255) - (b ushr it and 255)) }
    private fun averageDifference(first: ComposePixelFrame, second: ComposePixelFrame, bounds: Rect, density: Float): Double {
        val step = (3f * density).roundToInt().coerceAtLeast(1)
        var sum = 0L
        var count = 0
        for (y in bounds.top.roundToInt().coerceAtLeast(0) until bounds.bottom.roundToInt().coerceAtMost(first.height) step step) {
            for (x in bounds.left.roundToInt().coerceAtLeast(0) until bounds.right.roundToInt().coerceAtMost(first.width) step step) {
                sum += distance(first[x, y], second[x, y])
                count++
            }
        }
        return sum.toDouble() / count.coerceAtLeast(1)
    }
    private fun ComposePixelFrame.save(name: String) {
        val directory = File(System.getProperty("java.io.tmpdir"), "sticker-picker-glass")
        check(directory.isDirectory || directory.mkdirs())
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, width, height, IntArray(width * height) { this[it % width, it / width] }, 0, width)
        check(ImageIO.write(image, "png", File(directory, "$name.png")))
    }

    private companion object {
        val appearances = listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)
        val densities = listOf(1f, 1.25f)
    }
}
