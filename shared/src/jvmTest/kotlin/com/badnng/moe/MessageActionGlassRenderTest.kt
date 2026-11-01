package com.badnng.moe

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.ChatItem
import com.badnng.moe.chat.ChatMessage
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.ChatToken
import com.badnng.moe.chat.MessageActions
import com.badnng.moe.chat.MessageReactionOptions
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** The real message Popup must keep visibly mixed action rows, rather than almost opaque white ones. */
class MessageActionGlassRenderTest {
    @Test
    fun realMessageActionsBlurChangingPixelsAndKeepReadableTextWithoutDuplicatingTheSource() {
        for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) for (density in listOf(1f, 1.25f)) {
            val fixture = Fixture(mode, density)
            fixture.scene().use { scene ->
                val before = scene.settledFrame()
                val original = fixture.bubble
                scene.secondaryClickAt(original.center.x, original.center.y)
                val opening = scene.settledFrame(6, stepMillis = 16)
                fixture.assertSourceUnchanged(before, opening, original, "opening")
                val opened = scene.settledFrame()
                val menu = scene.menu()
                val reply = scene.button("回复")
                assertTrue(!menu.overlaps(original), "The actual actions must stay beside the original message: $mode/$density")
                assertEquals(1, fixture.sourceMounts, "Opening must retain the original message composition.")
                assertEquals(1, scene.textNodes(SOURCE_TEXT).size, "The source must have only one actual text composition.")
                fixture.assertSourceUnchanged(before, opened, original, "opened")

                val x = (reply.right - 18f * density).roundToInt()
                val otherX = (x - 4f * density).roundToInt()
                val y = reply.center.y.roundToInt()
                assertTrue(distance(before[x, y], before[otherX, y]) >= 140,
                    "The row must cover actual high-frequency source detail: $mode/$density")
                assertTrue(distance(opened[x, y], opened[otherX, y]) <= 22,
                    "The actual reply row must sample and blur that detail: $mode/$density")

                fixture.phase = 1
                val changed = scene.settledFrame()
                val minimumMix = if (mode == ColorSchemeMode.Light) 38 else 26
                assertTrue(distance(opened[x, y], changed[x, y]) >= minimumMix,
                    "Action rows must visibly retain the real changing source, not just an almost opaque tint: " +
                        "$mode/$density, ${distance(opened[x, y], changed[x, y])}")
                val paddingX = menu.center.x.roundToInt()
                val paddingY = (menu.bottom - 3f * density).roundToInt()
                assertTrue(distance(opened[paddingX, paddingY], changed[paddingX, paddingY]) >= minimumMix,
                    "The rows and their shared padding must use one sampled glass body: $mode/$density")

                // Solid white and black bound both text contrast and the no-background-text case.
                for (phase in listOf(2, 3)) {
                    fixture.phase = phase
                    val frame = scene.settledFrame()
                    for (label in listOf("回复", "该用户的其他消息", "复制文本", "拷贝消息链接", "转发")) {
                        assertReadable(scene, frame, label, fixture.foreground, density, "$mode/$density/$phase")
                    }
                    save(frame, "$mode-${density}x-${if (phase == 2) "bright" else "dark"}")
                }

                scene.click(scene.button("回复"))
                val closing = scene.settledFrame(3, stepMillis = 16)
                fixture.assertSourceUnchanged(before, closing, original, "closing")
                scene.settledFrame()
                assertEquals(1, fixture.replies, "The actual native-message reply action still dispatches once.")
                assertTrue(scene.menus().isEmpty())
                assertEquals(1, fixture.sourceMounts)

                // Retained exit pixels do not authorize an action after page ownership is lost.
                scene.secondaryClickAt(original.center.x, original.center.y)
                scene.settledFrame()
                val staleReply = scene.button("回复")
                fixture.interactive = false
                scene.settledFrame(1, stepMillis = 16)
                scene.click(staleReply)
                scene.settledFrame()
                assertEquals(1, fixture.replies, "A stale page must retain its existing action validation.")
                assertTrue(scene.menus().isEmpty())

                save(before, "$mode-${density}x-before")
                save(opening, "$mode-${density}x-opening")
                save(opened, "$mode-${density}x-opened")
                save(changed, "$mode-${density}x-changed")
                save(closing, "$mode-${density}x-closing")
            }
        }
    }

    private class Fixture(val mode: ColorSchemeMode, val density: Float) {
        private val message = ChatMessage(101, 10, senderId = 20, text = SOURCE_TEXT, canBeSaved = true)
        private val snapshot = ChatSnapshot(enabled = true, token = ChatToken(1, 1, 10),
            selected = ChatItem(10, "Synthetic glass group", basicGroupId = 7), messages = listOf(message),
            actionsMessageId = message.id, messageActions = MessageActions(canForward = true),
            reactionMessageId = message.id, reactionOptions = MessageReactionOptions(listOf("👍", "❤️"), canAdd = true))
        var phase by mutableStateOf(0)
        var expanded by mutableStateOf(false)
        var interactive by mutableStateOf(true)
        var bubble = Rect.Zero
        var foreground = 0
        var sourceMounts = 0
        var replies = 0

        fun scene() = ComposePixelScene((900 * density).roundToInt(), (660 * density).roundToInt()) {
            val theme = remember { ThemeController(mode) }
            CompositionLocalProvider(LocalDensity provides Density(density),
                LocalDesktopChatLayout provides DesktopChatLayout(compact = true),
                LocalConversationMessageMenuViewport provides Rect(0f, 0f, 900f * density, 660f * density)) {
                MiuixTheme(controller = theme) {
                    val backdrop = assertNotNull(rememberChatBackdrop())
                    foreground = MiuixTheme.colorScheme.onSurface.toArgb()
                    CompositionLocalProvider(LocalChatBackdrop provides backdrop, LocalWorkspaceMenuBackdrop provides backdrop) {
                        Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                            Canvas(Modifier.fillMaxSize()) {
                                val stripe = 4.dp.toPx()
                                repeat((size.width / stripe).toInt() + 1) { index ->
                                    val color = when (phase) {
                                        2 -> Color.White
                                        3 -> Color.Black
                                        else -> if (index % 2 != 0) Color.White
                                            else if (phase == 1) Color(0xff244df0) else Color(0xffe52f48)
                                    }
                                    drawRect(color, Offset(index * stripe, 0f), Size(stripe, size.height))
                                }
                            }
                            Box(Modifier.offset(80.dp, 70.dp).width(550.dp)) {
                                DisposableEffect(Unit) { sourceMounts++; onDispose {} }
                                MessageBubble(message, "Synthetic sender", showSender = true, endsGroup = true,
                                    onBounds = { if (it != null) bubble = it }, retrying = false, highlighted = false,
                                    read = false, onRetry = {}, snapshot = snapshot, features = ChatFeatureActions(),
                                    visible = true, animate = false, onEnlarge = {}, onSave = { _, _ -> },
                                    onPlay = {}, playing = false, onStopPlaying = {}, onVideoBounds = {},
                                    playbackSession = null, onExpandVideo = {}, menuOpen = expanded,
                                    onMenuOpen = { expanded = true }, onMenuDismiss = { expanded = false },
                                    onReply = { replies++; expanded = false }, onQuote = {}, onJumpToReply = {}, onReact = {},
                                    onReadDetails = {}, onEdit = {}, onForward = {}, onDelete = {}, onSearchSender = {},
                                    pageInteractive = interactive, reserveAvatarSpace = true)
                            }
                        }
                    }
                }
            }
        }

        fun assertSourceUnchanged(before: ComposePixelFrame, after: ComposePixelFrame, source: Rect, stage: String) {
            var same = 0
            var total = 0
            val insetX = (16f * density).roundToInt()
            val insetY = (5f * density).roundToInt()
            for (y in source.top.roundToInt() + insetY until source.bottom.roundToInt() - insetY)
                for (x in source.left.roundToInt() + insetX until source.right.roundToInt() - insetX) {
                    total++
                    if (distance(before[x, y], after[x, y]) <= 2) same++
                }
            assertTrue(total > 100 && same >= total * .995f,
                "The original message must remain clear and painted once during $stage: $mode/$density, $same/$total")
        }
    }

    private fun assertReadable(scene: ComposePixelScene, frame: ComposePixelFrame, label: String,
        foreground: Int, density: Float, fixture: String) {
        val row = scene.button(label)
        val text = assertNotNull(scene.textNodes(label).singleOrNull(), "Missing actual row text: $label/$fixture")
        val background = frame[(row.right - 18f * density).roundToInt(), row.center.y.roundToInt()]
        var closest = 0
        var closestDistance = Int.MAX_VALUE
        var cores = 0
        for (y in text.top.roundToInt() until text.bottom.roundToInt())
            for (x in text.left.roundToInt() until text.right.roundToInt()) {
                val pixel = frame[x, y]
                val difference = distance(pixel, foreground)
                if (difference < closestDistance) { closestDistance = difference; closest = pixel }
                if (difference <= 20) cores++
            }
        assertTrue(cores >= 3 && closestDistance <= 12,
            "The real action glyphs retain their explicit readable foreground: $label/$fixture")
        assertTrue(contrast(closest, background) >= 4.5,
            "The actual action text must retain 4.5:1 contrast over bright and dark changing content: $label/$fixture")
    }

    private fun ComposePixelScene.menus() = semanticsBounds {
        it.getOrNull(SemanticsProperties.PaneTitle) == "消息操作面板"
    }
    private fun ComposePixelScene.menu() = assertNotNull(menus().singleOrNull())
    private fun ComposePixelScene.textNodes(label: String) = unmergedSemanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == label }
    }
    private fun ComposePixelScene.button(label: String) = assertNotNull(semanticsBounds {
        it.getOrNull(SemanticsProperties.Role) == Role.Button &&
            it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == label }
    }.singleOrNull(), "Missing real action row: $label")
    private fun ComposePixelScene.click(bounds: Rect) {
        moveTo(bounds.center.x, bounds.center.y)
        pressAt(bounds.center.x, bounds.center.y)
        releaseAt(bounds.center.x, bounds.center.y)
    }

    private fun save(frame: ComposePixelFrame, name: String) {
        val directory = Path.of(System.getProperty("nngGram.messageActionGlassFrames")
            ?: Path.of(System.getProperty("java.io.tmpdir"), "message-action-glass").toString())
        Files.createDirectories(directory)
        val image = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until frame.height) for (x in 0 until frame.width) image.setRGB(x, y, frame[x, y])
        assertTrue(ImageIO.write(image, "png", directory.resolve("$name.png").toFile()))
    }

    private companion object {
        const val SOURCE_TEXT = "One original synthetic message"
        fun distance(a: Int, b: Int): Int = listOf(0, 8, 16).maxOf {
            abs((a ushr it and 255) - (b ushr it and 255))
        }
        fun contrast(first: Int, second: Int): Double {
            fun luminance(pixel: Int): Double {
                fun linear(shift: Int): Double {
                    val channel = (pixel ushr shift and 255) / 255.0
                    return if (channel <= .04045) channel / 12.92 else ((channel + .055) / 1.055).pow(2.4)
                }
                return .2126 * linear(16) + .7152 * linear(8) + .0722 * linear(0)
            }
            val a = luminance(first)
            val b = luminance(second)
            return (maxOf(a, b) + .05) / (minOf(a, b) + .05)
        }
    }
}
