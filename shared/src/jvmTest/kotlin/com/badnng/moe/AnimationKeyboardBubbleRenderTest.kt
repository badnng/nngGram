package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.*
import com.badnng.moe.chat.MessageInlineKeyboard as NativeInlineKeyboard
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.*
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class AnimationKeyboardBubbleRenderTest {
    @Test fun nativeAnimationAndKeyboardRenderTogetherAtTheMessageWidthInBothThemesAndDensities() {
        val directory = Files.createTempDirectory("synthetic-gif-keyboard-")
        try {
            val thumbnail = directory.resolve("thumbnail.png")
            val bitmap = BufferedImage(120, 160, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width)
                bitmap.setRGB(x, y, if ((x / 20 + y / 20) % 2 == 0) 0x307EBB else 0x77BD99)
            check(ImageIO.write(bitmap, "png", thumbnail.toFile()))
            for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) for (density in listOf(1f, 1.25f)) {
                val message = ChatMessage(1, 10, text = "合成 GIF 说明 · native caption", kind = MessageKind.ANIMATION,
                    media = MessageMedia(MediaFile(7, size = 1000, canDownload = true), thumbnail = MediaFile(8, localPath = thumbnail.toString(), downloaded = true),
                        width = 360, height = 480, format = MediaFormat.ANIMATION),
                    inlineKeyboard = NativeInlineKeyboard(listOf(listOf(
                        MessageInlineButton("查看产物", MessageInlineButtonKind.URL, "https://example.com/artifacts"),
                        MessageInlineButton("构建详情", MessageInlineButtonKind.URL, "https://example.com/build")))))
                val snapshot = ChatSnapshot(enabled = true, token = ChatToken(1, 1, 10),
                    selected = ChatItem(10, "Synthetic chat"), messages = listOf(message))
                var bubble = Rect.Zero
                ComposePixelScene((366 * density).roundToInt(), (540 * density).roundToInt()) {
                    val theme = remember { ThemeController(mode) }
                    CompositionLocalProvider(LocalDensity provides Density(density),
                        LocalDesktopChatLayout provides DesktopChatLayout(compact = true)) {
                        MiuixTheme(controller = theme) {
                            Box(Modifier.fillMaxSize().background(Color(0xFF6A987E)).padding(top = 16.dp, end = 8.dp)) {
                                Bubble(message, snapshot, enabled = true) { bubble = it }
                            }
                        }
                    }
                }.use { scene ->
                    var frame = scene.settledFrame()
                    val deadline = System.nanoTime() + 3_000_000_000L
                    while (scene.unmergedSemanticsBounds {
                        "GIF 缩略图" in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                    }.isEmpty() && System.nanoTime() < deadline) {
                        Thread.sleep(5)
                        frame = scene.settledFrame(2)
                    }
                    val image = scene.unmergedSemanticsBounds {
                        "GIF 缩略图" in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                    }.single()
                    assertTrue(abs(image.width / density - 240f) <= 1f)
                    assertTrue(abs(image.height / density - 320f) <= 1f)
                    val left = scene.textBounds("查看产物").single()
                    val right = scene.textBounds("构建详情").single()
                    assertTrue(abs(left.top - right.top) <= 1f)
                    assertTrue(abs(left.width - right.width) <= 1f)
                    assertTrue(left.top > image.bottom, "Native keyboard belongs below its media and caption.")
                    assertTrue(left.left >= bubble.left && right.right <= bubble.right)
                    assertTrue(right.bottom <= bubble.bottom)
                    assertTrue(scene.textBounds("[其他消息]").isEmpty())
                    export(frame, "${mode.name.lowercase()}-${(density * 100).roundToInt()}")
                }
            }
        } finally {
            Files.deleteIfExists(directory.resolve("thumbnail.png"))
            Files.deleteIfExists(directory)
        }
    }

    @Test fun departingBubbleKeepsItsNativeKeyboardDrawingButCannotOpenALink() {
        val message = ChatMessage(1, 10, text = "合成消息", inlineKeyboard = NativeInlineKeyboard(listOf(listOf(
            MessageInlineButton("查看详情", MessageInlineButtonKind.URL, "https://example.com/detail")))))
        val snapshot = ChatSnapshot(enabled = true, token = ChatToken(1, 1, 10),
            selected = ChatItem(10, "Synthetic chat"), messages = listOf(message))
        ComposePixelScene(366, 300) {
            val theme = remember { ThemeController(ColorSchemeMode.Light) }
            MiuixTheme(controller = theme) {
                Box(Modifier.fillMaxSize()) { Bubble(message, snapshot, enabled = false) {} }
            }
        }.use { scene ->
            scene.settledFrame()
            val button = scene.textBounds("查看详情").single()
            scene.pressAt(button.center.x, button.center.y)
            scene.releaseAt(button.center.x, button.center.y)
            scene.settledFrame()
            assertTrue(scene.textBounds("将使用默认浏览器打开以下地址：").isEmpty())
            assertTrue(scene.textBounds("查看详情").isNotEmpty())
        }
    }

    @Composable private fun Bubble(message: ChatMessage, snapshot: ChatSnapshot, enabled: Boolean, onBounds: (Rect) -> Unit) {
        MessageBubble(message, "Synthetic sender", showSender = false, endsGroup = true,
            onBounds = { if (it != null) onBounds(it) }, retrying = false, highlighted = false,
            read = false, onRetry = {}, snapshot = snapshot, features = ChatFeatureActions(),
            visible = false, animate = false, onEnlarge = {}, onSave = { _, _ -> },
            onPlay = {}, playing = false, onStopPlaying = {}, onVideoBounds = {},
            playbackSession = null, onExpandVideo = {}, menuOpen = false,
            onMenuOpen = {}, onMenuDismiss = {}, onReply = {}, onQuote = {},
            onJumpToReply = {}, onReact = {}, onReadDetails = {}, onEdit = {},
            onForward = {}, onDelete = {}, onSearchSender = {}, pageInteractive = enabled,
            onOpenLink = { error("Synthetic UI tests must never open a real browser.") })
    }

    private fun ComposePixelScene.textBounds(text: String) = semanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == text }
    }

    private fun export(frame: ComposePixelFrame, name: String) {
        val output = Path.of("build/animation-keyboard-bubble")
        Files.createDirectories(output)
        val image = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until frame.height) for (x in 0 until frame.width) image.setRGB(x, y, frame[x, y])
        ImageIO.write(image, "png", output.resolve("$name.png").toFile())
    }
}
