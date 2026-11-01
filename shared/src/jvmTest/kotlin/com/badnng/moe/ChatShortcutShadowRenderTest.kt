package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import com.badnng.moe.chat.ChatItem
import com.badnng.moe.chat.ChatMessage
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.ChatToken
import com.badnng.moe.chat.MessageActions
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Shadows must survive both the native Popup boundary and its alpha/scale layer. */
class ChatShortcutShadowRenderTest {
    @Test
    fun sharedPopupShadowSpaceRetainsTheBodyPositionAndAnimatedBottomShadow() {
        forEachThemeAndDensity { mode, density ->
            for (progress in listOf(0.5f, 0.99f, 1f)) {
                themedScene(mode, density) {
                    val padding = with(LocalDensity.current) { ChatShortcutShadowPadding.roundToPx() }
                    val bodyPosition = remember(density) { FixedBodyPosition(IntOffset(px(140f, density), px(100f, density))) }
                    val position = remember(bodyPosition, padding) { ChatShortcutPopupPosition(bodyPosition, padding) }
                    Popup(popupPositionProvider = position) {
                        ChatShortcutShadowSpace {
                            Box(Modifier.size(240.dp, 120.dp).graphicsLayer {
                                alpha = progress
                                scaleX = 0.92f + 0.08f * progress
                                scaleY = 0.92f + 0.08f * progress
                                chatShortcutShadowOutsets()
                            }.chatShortcutShadow().chatSquircleSurface(MiuixTheme.colorScheme.surfaceContainer, 15.dp)
                                .semantics { contentDescription = "合成快捷菜单" })
                        }
                    }
                }.use { scene ->
                    val frame = scene.settledFrame()
                    val body = scene.description("合成快捷菜单")
                    if (progress == 1f) {
                        assertTrue(abs(body.left - 140f * density) <= 1f && abs(body.top - 100f * density) <= 1f,
                            "Transparent shadow padding must preserve the existing body position: $body")
                        assertEquals(px(240f, density), body.width.roundToInt())
                    }
                    assertBottomShadow(frame, body, density, "$mode/$density/progress=$progress",
                        minimumContrast = if (progress < 0.9f) 1.0 else 2.0)
                }
            }
        }
    }

    @Test
    fun actualAttachmentPopupPaintsItsBottomShadowAndKeepsItDuringExit() {
        forEachThemeAndDensity { mode, density ->
            val show = mutableStateOf(false)
            var painted: Rect? = null
            val anchor = scaled(Rect(140f, 470f, 172f, 502f), density)
            themedScene(mode, density) {
                CompositionLocalProvider(LocalChatShortcutPreviewBounds provides { painted = it }) {
                    ComposerAttachmentMenu(show.value, anchor,
                        onDismiss = { show.value = false }, onHovered = {}) {
                        // The production popup owns its natural column, reveal clip and animation.
                        Text("合成附件操作", modifier = Modifier.size(150.dp, 42.dp))
                        Text("第二个合成操作", modifier = Modifier.size(150.dp, 42.dp))
                    }
                }
            }.use { scene ->
                val source = scene.settledFrame()
                show.value = true
                val opening = List(8) { scene.settledFrame(1) to painted }
                val opened = scene.settledFrame()
                val body = scene.pane("附件菜单")
                show.value = false
                val closing = List(8) { scene.settledFrame(1) to painted }
                val closed = scene.settledFrame()
                val samples = linkedMapOf("source" to source, "open" to opened, "closed" to closed)
                opening.forEachIndexed { index, sample -> samples["opening-$index"] = sample.first }
                closing.forEachIndexed { index, sample -> samples["closing-$index"] = sample.first }
                saveAttachmentFrames(mode, density, samples, buildList {
                    add("anchor=$anchor body=$body")
                    opening.forEachIndexed { index, sample -> add("opening-$index=${sample.second}") }
                    closing.forEachIndexed { index, sample -> add("closing-$index=${sample.second}") }
                })
                assertTrue(abs(anchor.top - body.bottom - 14f * density) <= 2f * density,
                    "The complete official 10dp shadow allowance plus the requested small 4dp lift must determine the card's entire gap: $anchor -> $body")
                assertBottomShadow(opened, body, density, "attachment/$mode/$density")
                assertPartialAttachmentFrames(opening, body, density, "opening/$mode/$density")
                assertRetainedExitShadow(closing.first().first, body, density, "attachment/$mode/$density")
                assertPartialAttachmentFrames(closing, body, density, "closing/$mode/$density")
                assertTrue(scene.panes("附件菜单").isEmpty())
                assertTrue(painted == null, "Completed exit must release the retained drawn outline.")
                assertNeutralBelow(closed, body, density, "attachment/$mode/$density")
            }
        }
    }

    private fun assertPartialAttachmentFrames(samples: List<Pair<ComposePixelFrame, Rect?>>, body: Rect,
        density: Float, fixture: String) {
        // A short dropdown's late Miuix exit frame can be only 7dp tall. It is still
        // an actual revealed outline; the larger pixel-probe threshold belongs only
        // to the shadow measurement, not to counting real animation frames.
        val partial = samples.filter { (_, outline) -> outline != null &&
            outline.width > density && outline.height > density &&
            (abs(outline.width - body.width) > density || abs(outline.height - body.height) > density) }
        assertTrue(partial.size >= 2,
            "The real Miuix reveal must retain intermediate attachment outlines at $fixture: ${samples.map { it.second }}")
        partial.filter { it.second!!.height > 20f * density }.forEach { (frame, outline) ->
            assertBottomShadow(frame, outline!!, density, "attachment/$fixture", minimumContrast = 0.5)
        }
    }

    private fun saveAttachmentFrames(mode: ColorSchemeMode, density: Float, samples: Map<String, ComposePixelFrame>,
        geometry: List<String>) {
        val directory = File(System.getProperty("java.io.tmpdir"), "attachment-card-anchor/$mode-$density").apply { mkdirs() }
        File(directory, "geometry.txt").writeText(geometry.joinToString("\n"))
        samples.forEach { (name, frame) ->
            val image = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until frame.height) for (x in 0 until frame.width) image.setRGB(x, y, frame[x, y])
            check(ImageIO.write(image, "png", File(directory, "$name.png")))
        }
    }

    @Test
    fun actualMessagePopupPaintsItsBottomShadowAndKeepsItDuringExit() {
        forEachThemeAndDensity { mode, density ->
            val show = mutableStateOf(true)
            val message = ChatMessage(100, 10, text = "合成消息")
            val snapshot = ChatSnapshot(enabled = true, token = ChatToken(1, 1, 10),
                selected = ChatItem(10, "合成聊天"), messages = listOf(message),
                actionsMessageId = message.id, messageActions = MessageActions(canDeleteForSelf = true))
            themedScene(mode, density) {
                Box(Modifier.offset(300.dp, 130.dp).size(180.dp, 80.dp)) {
                    MessageActionsMenu(message, snapshot, canReply = true, canQuote = false,
                        onReply = {}, onQuote = {}, onReact = {}, onReadDetails = {}, onReload = {}, onEdit = {},
                        onForward = {}, onDelete = {}, onDismiss = { show.value = false }, expanded = show.value)
                }
            }.use { scene ->
                scene.settledFrame()
                val body = scene.pane("消息操作面板")
                assertBottomShadow(scene.settledFrame(1), body, density, "message/$mode/$density")
                show.value = false
                assertRetainedExitShadow(scene.settledFrame(1), body, density, "message/$mode/$density")
                scene.settledFrame()
                assertTrue(scene.panes("消息操作面板").isEmpty())
                assertNeutralBelow(scene.settledFrame(1), body, density, "message/$mode/$density")
            }
        }
    }

    @Test
    fun actualStickerPopupPaintsItsBottomShadowAndKeepsItDuringExit() {
        forEachThemeAndDensity { mode, density ->
            val show = mutableStateOf(true)
            val snapshot = ChatSnapshot(enabled = true, token = ChatToken(1, 1, 10),
                selected = ChatItem(10, "合成聊天", canSendStickers = true))
            themedScene(mode, density) {
                StickerPicker(show.value, snapshot, ChatFeatureActions(), active = true,
                    onDismiss = { show.value = false }, anchor = scaled(Rect(620f, 520f, 652f, 552f), density))
            }.use { scene ->
                scene.settledFrame()
                val body = scene.description("贴纸选择面板")
                assertBottomShadow(scene.settledFrame(1), body, density, "sticker/$mode/$density")
                show.value = false
                assertRetainedExitShadow(scene.settledFrame(1), body, density, "sticker/$mode/$density")
                scene.settledFrame()
                assertTrue(scene.descriptions("贴纸选择面板").isEmpty())
                assertNeutralBelow(scene.settledFrame(1), body, density, "sticker/$mode/$density")
            }
        }
    }

    private fun themedScene(mode: ColorSchemeMode, density: Float, content: @Composable () -> Unit) =
        ComposePixelScene(px(800f, density), px(620f, density)) {
            val controller = remember { ThemeController(mode) }
            CompositionLocalProvider(LocalDensity provides Density(density)) {
                MiuixTheme(controller = controller) {
                    Box(Modifier.fillMaxSize().background(Color(Background))) { content() }
                }
            }
        }

    private fun assertBottomShadow(frame: ComposePixelFrame, body: Rect, density: Float, fixture: String,
        minimumContrast: Double = 2.0) {
        // Use the measured body, away from rounded corners and any text; a solid neutral
        // background makes the shadow independent of theme tint and backdrop content.
        val near = averageBelow(frame, body, density, 3f)
        val far = averageBelow(frame, body, density, 26f)
        assertTrue(far - near >= minimumContrast,
            "Bottom shadow was clipped or missing at $fixture: near=$near, far=$far, body=$body")
        assertTrue(far <= BackgroundChannel + 1 && far >= BackgroundChannel - 8,
            "The outer transparent shadow space must return to the scene at $fixture: $far")
    }

    private fun assertRetainedExitShadow(frame: ComposePixelFrame, body: Rect, density: Float, fixture: String) {
        // The first exit frame may already scale the body slightly. Search a small band
        // below its settled boundary instead of assuming an animation implementation.
        val darkest = (1..10).minOf { averageBelow(frame, body, density, it.toFloat()) }
        assertTrue(BackgroundChannel - darkest >= 1.0,
            "The closing Popup dropped its exterior shadow before its content at $fixture: $darkest")
    }

    private fun assertNeutralBelow(frame: ComposePixelFrame, body: Rect, density: Float, fixture: String) {
        val value = averageBelow(frame, body, density, 3f)
        assertTrue(abs(value - BackgroundChannel) <= 1.0,
            "The completed exit left a shadow or retained Popup behind at $fixture: $value")
    }

    private fun averageBelow(frame: ComposePixelFrame, body: Rect, density: Float, belowDp: Float): Double {
        val y = (body.bottom + belowDp * density).roundToInt().coerceIn(0, frame.height - 1)
        val center = body.center.x.roundToInt()
        val halfWidth = (body.width / 5f).roundToInt().coerceAtLeast(1)
        return (center - halfWidth..center + halfWidth).map { x ->
            val pixel = frame[x.coerceIn(0, frame.width - 1), y]
            ((pixel ushr 16 and 255) + (pixel ushr 8 and 255) + (pixel and 255)) / 3.0
        }.average()
    }

    private fun forEachThemeAndDensity(block: (ColorSchemeMode, Float) -> Unit) {
        for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) {
            for (density in listOf(1f, 1.25f)) block(mode, density)
        }
    }

    private fun ComposePixelScene.panes(title: String) = semanticsBounds {
        it.getOrNull(SemanticsProperties.PaneTitle) == title
    }
    private fun ComposePixelScene.pane(title: String) = assertNotNull(panes(title).singleOrNull(), title)
    private fun ComposePixelScene.descriptions(label: String) = semanticsBounds {
        label in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }
    private fun ComposePixelScene.description(label: String) = assertNotNull(descriptions(label).singleOrNull(), label)
    private fun scaled(rect: Rect, density: Float) = Rect(rect.left * density, rect.top * density,
        rect.right * density, rect.bottom * density)
    private fun px(dp: Float, density: Float) = (dp * density).roundToInt()

    private class FixedBodyPosition(private val offset: IntOffset) : PopupPositionProvider {
        override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection,
            popupContentSize: IntSize): IntOffset = offset
    }

    private companion object {
        const val Background = 0xffbdbdbd
        const val BackgroundChannel = 189
    }
}
