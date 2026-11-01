package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.badnng.moe.chat.ChatItem
import com.badnng.moe.chat.ChatMessage
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.ChatToken
import com.badnng.moe.chat.MessageReply
import com.badnng.moe.chat.MessageKind
import com.badnng.moe.chat.MessageMedia
import com.badnng.moe.chat.MediaFile
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class MessageBubbleLayoutRenderTest {
    @Test
    fun photosUseOfficialCanvasAndPreserveTheDecodedArtworkAspect() {
        withSyntheticPhoto { path ->
            for (density in listOf(1f, 1.25f)) for ((width, height) in listOf(1200 to 600, 600 to 1200, 64 to 48)) {
                writePhoto(path, width, height)
                val message = ChatMessage(1, 10, outgoing = true, kind = MessageKind.PHOTO,
                    date = 1_700_000_000,
                    media = MessageMedia(MediaFile(1, localPath = path.toString(), downloaded = true), width = width, height = height))
                var bubble = Rect.Zero
                bubbleScene(message, 900, density, heightDp = 900, visible = true) { bubble = it }.use { scene ->
                    scene.awaitPhoto()
                    val photo = assertNotNull(scene.descriptionBounds("聊天图片").singleOrNull())
                    val expected = photoMessageSize(width, height)
                    assertClose(expected.width * density, photo.width,
                        "Photos use the official 430dp cap and 100dp minimum canvas.")
                    assertClose(expected.height * density, photo.height, "The complete media canvas has official geometry.")
                    if (!photoResizeExpands(width, height, expected.width, expected.height)) scene.awaitPhotoBackground()
                    val pixels = scene.settledFrame()
                    val art = sharpGreenForegroundBounds(pixels, photo)
                    assertTrue(abs(art.width / art.height - width.toFloat() / height) <= .03f,
                        "The actual decoded photo artwork retains its aspect inside the minimum canvas.")
                    assertClose(0f, photo.left - bubble.left, "A bare photo has no exterior left card gutter.")
                    assertClose(0f, bubble.right - photo.right, "A bare photo has no exterior right card gutter.")
                    assertClose(0f, photo.top - bubble.top, "A bare photo has no exterior top card gutter.")
                    assertClose(0f, bubble.bottom - photo.bottom, "A bare photo has no separate metadata card below it.")
                    scene.moveTo(photo.center.x, photo.center.y)
                    scene.settledFrame(2)
                    val time = assertNotNull(scene.textBounds(messageClock(message.date)).singleOrNull())
                    assertTrue(time.top >= photo.top && time.bottom <= photo.bottom,
                        "Bare-photo metadata overlays the actual image pixels: $photo vs $time")
                    val delivery = assertNotNull(scene.descriptionBounds("已发送").singleOrNull())
                    val infoRight = maxOf(time.right, delivery.right)
                    assertTrue(time.center.y > photo.center.y && photo.right - infoRight in 0f..12f * density,
                        "The complete clock/delivery group belongs at the image's bottom right, including small photos.")
                    // The real delivery glyph is centered inside its measured 16dp slot.
                    val footerBottom = maxOf(time.bottom, delivery.center.y + (16f * density).roundToInt() / 2f)
                    assertTrue(footerBottom <= photo.bottom && delivery.left >= time.right,
                        "Both the time and delivery glyph fit inside the photo overlay.")
                }
            }
        }
    }

    @Test
    fun shortTextKeepsOfficialPaddingAndSharesItsLastLineWithoutOverlappingMetadata() {
        for (density in listOf(1f, 1.25f)) {
            for (outgoing in listOf(false, true)) {
                val message = ChatMessage(1, 10, outgoing = outgoing, date = 1_700_000_000,
                    editDate = 1_700_000_001, text = "合约到了，得收割了")
                var bubble = Rect.Zero
                bubbleScene(message, 600, density) { bubble = it }.use { scene ->
                    val pixels = scene.settledFrame()
                    val text = assertNotNull(scene.textBounds(message.text).firstOrNull())
                    val edited = assertNotNull(scene.textBounds("已编辑").firstOrNull())
                    assertClose(11f * density, text.left - bubble.left,
                        "Text uses the actual official horizontal gutter, outgoing=$outgoing, density=$density")
                    assertClose(8f * density, text.top - bubble.top,
                        "A single text line uses native top padding rather than a forced taller card.")
                    assertTrue(edited.top < text.bottom && edited.bottom <= text.bottom + 1f,
                        "A fitting metadata group shares the final text line: $text vs $edited")
                    val time = assertNotNull(scene.textBounds(messageClock(message.date)).singleOrNull())
                    assertTrue(time.left >= text.right + 9f * density && !text.overlaps(time) && !text.overlaps(edited),
                        "The native trailing gap keeps actual text glyphs clear of metadata.")
                    assertClose(8f * density, bubble.bottom - text.bottom,
                        "One-line content keeps natural bottom padding and visible-content capacity.")
                    assertTrue(bubble.width < 350f * density,
                        "A short text message must use natural width instead of a fixed wide outgoing bubble.")
                    val left = bubble.left.roundToInt()
                    val top = bubble.top.roundToInt()
                    assertTrue(colorDistance(pixels[bubble.center.x.roundToInt(), top + 3], pixels[0, pixels.height - 1]) > 10,
                        "The rounded message surface must actually paint behind its text.")
                    // The real surface must leave its rounded-away outer corner transparent.
                    assertTrue(colorDistance(pixels[left + 1, top + 1], pixels[0, pixels.height - 1]) <= 1,
                        "The message silhouette must preserve its rounded outer corner.")
                }
            }
        }
    }

    @Test
    fun wrappedTextAndQuotedReplyRemainInsideANarrowBubble() {
        val message = ChatMessage(2, 10, date = 1_700_000_000,
            text = "这是一条用于检查中文长消息排版的合成文字，正文应该自然换行。\n第二段继续显示完整内容。\n末行不能被时间遮挡。",
            replyTo = MessageReply(10, 1, text = "今年甚至时间都差不多", senderId = 20))
        var bubble = Rect.Zero
        bubbleScene(message, 320, 1f) { bubble = it }.use { scene ->
            scene.settledFrame()
            val body = assertNotNull(scene.textBounds(message.text).firstOrNull())
            val quote = assertNotNull(scene.textBounds(message.replyTo!!.text).firstOrNull())
            val layout = scene.textLayout(message.text)
            assertTrue(layout.lineCount >= 3, "Native newlines and Chinese wrapping must survive the narrow layout.")
            assertTrue(bubble.right <= 320f && bubble.left >= 0f,
                "The real bubble must stay inside the narrow viewport.")
            assertClose(11f, body.left - bubble.left, "Wrapped text keeps the actual native horizontal padding.")
            assertTrue(body.right <= bubble.right - 10f,
                "Wrapped text must preserve both horizontal gutters.")
            assertTrue(quote.top > bubble.top && quote.bottom + 4f <= body.top,
                "The quoted preview must be padded and visually separated from the body.")
            assertTrue(scene.textBounds("引用者").isNotEmpty(), "The actual reply sender must remain visible.")
            val time = assertNotNull(scene.textBounds(messageClock(message.date)).singleOrNull())
            val last = layout.lineCount - 1
            assertTrue(time.top >= body.bottom ||
                time.left >= body.left + layout.getLineRight(last) + 9f &&
                    time.top >= body.top + layout.getLineTop(last) - 1f,
                "Metadata may use the actual final-line space, but must never cover earlier lines or quoted content.")
            assertTrue(!time.overlaps(quote) && time.bottom <= bubble.bottom - 7f,
                "The quote, body and metadata all remain inside their naturally padded bubble.")
        }
    }

    @Test
    fun normalTextUsesTheOfficialWidthLimitAndSingleSenderGutter() {
        for (density in listOf(1f, 1.25f)) for (outgoing in listOf(false, true)) {
            val message = ChatMessage(1, 10, senderId = 20, outgoing = outgoing,
                text = "Native message words wrap at the official content width. ".repeat(12))
            var bubble = Rect.Zero
            bubbleScene(message, 900, density, heightDp = 900, showSender = true) { bubble = it }.use { scene ->
                val pixels = scene.settledFrame()
                assertClose(430f * density, bubble.width, "Ordinary text keeps the official 430dp width limit.")
                if (outgoing) assertClose(900f * density, bubble.right, "Outgoing bubbles align with the conversation's right edge.")
                else {
                    assertClose(40f * density, bubble.left, "The sender occupies exactly one 33dp avatar plus a 7dp gap.")
                    val sender = assertNotNull(scene.textBounds("合成发送者").singleOrNull())
                    assertClose(11f * density, sender.left - bubble.left, "The sender label belongs inside the message bubble.")
                    val centerY = (bubble.bottom - 16.5f * density).roundToInt()
                    val avatarPixels = (0 until (40 * density).roundToInt()).count { x ->
                        colorDistance(pixels[x, centerY], pixels[0, pixels.height - 1]) > 10
                    }
                    assertTrue(abs(avatarPixels - 33f * density) <= 2f,
                        "The real circular avatar paints a 33dp diameter without consuming the full 40dp sender gutter.")
                }
            }
        }
    }

    @Test
    fun nativeCaptionOrderAndLongCaptionsRemainInsideNarrowPhotoMessages() {
        withSyntheticPhoto { path ->
            writePhoto(path, 64, 48)
            for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) for (density in listOf(1f, 1.25f)) {
                for (above in listOf(false, true)) {
                    val message = ChatMessage(1, 10, outgoing = true, kind = MessageKind.PHOTO,
                        text = "原生说明显示在照片${if (above) "上方" else "下方"}，长说明会扩展画布并在窄窗内自然换行。",
                        showCaptionAboveMedia = above,
                        media = MessageMedia(MediaFile(1, localPath = path.toString(), downloaded = true), width = 64, height = 48))
                    var bubble = Rect.Zero
                    bubbleScene(message, 320, density, visible = true, mode = mode) { bubble = it }.use { scene ->
                        scene.awaitPhoto()
                        scene.awaitPhotoBackground()
                        val photo = assertNotNull(scene.descriptionBounds("聊天图片").singleOrNull())
                        val caption = assertNotNull(scene.textBounds(message.text).singleOrNull())
                        assertTrue(bubble.left >= 0 && bubble.right <= 320f * density)
                        assertClose(320f * density, photo.width, "A long caption expands the edge-aligned photo to the available canvas.")
                        assertClose(0f, photo.left - bubble.left, "Captioned photos remain flush with the bubble's left edge.")
                        assertClose(0f, bubble.right - photo.right, "Captioned photos remain flush with the bubble's right edge.")
                        assertClose(11f * density, caption.left - bubble.left, "Caption text keeps its own official gutter.")
                        assertTrue(if (above) caption.bottom < photo.top else photo.bottom < caption.top,
                            "The native caption flag changes the real layout order.")
                        writeFrame(scene.settledFrame(), "caption-$mode-$density-$above")
                    }
                }
            }
        }
    }

    @Test
    fun extremelyTallAndWidePhotosFitActualNarrowCanvasesInBothThemes() {
        withSyntheticPhoto { path ->
            for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) for (density in listOf(1f, 1.25f)) {
                for ((width, height) in listOf(1 to 10_000, 10_000 to 1)) {
                    writePhoto(path, width, height)
                    val message = ChatMessage(1, 10, outgoing = true, kind = MessageKind.PHOTO,
                        media = MessageMedia(MediaFile(1, localPath = path.toString(), downloaded = true), width = width, height = height))
                    bubbleScene(message, 210, density, heightDp = 600, visible = true, mode = mode) { }.use { scene ->
                        scene.awaitPhoto()
                        scene.awaitPhotoBackground()
                        val photo = assertNotNull(scene.descriptionBounds("聊天图片").singleOrNull())
                        val expected = photoMessageSize(width, height, availableWidth = 210f)
                        assertClose(expected.width * density, photo.width, "A narrow viewport constrains the real photo width.")
                        assertClose(expected.height * density, photo.height, "Portrait fitting uses the viewport, not the narrowed image width again.")
                        writeFrame(scene.settledFrame(), "extreme-$width-$mode-$density")
                    }
                }
            }
        }
    }

    @Test
    fun privateChatsDropTheSenderGutterAndGroupTailOwnsTheBottomAvatar() {
        val message = ChatMessage(1, 10, senderId = 20, text = "群内连续消息的头像位于末条消息底部。\n第二行用于确定真实气泡高度。")
        val privateChat = ChatItem(10, "User", privateUserId = 20)
        val group = ChatItem(10, "Group", basicGroupId = 1)
        for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) for (density in listOf(1f, 1.25f)) {
            var bounds = Rect.Zero
            bubbleScene(message, 360, density, mode = mode, chat = privateChat) { bounds = it }.use { scene ->
                scene.settledFrame()
                assertClose(0f, bounds.left, "An ordinary private message reserves no avatar gutter.")
                assertTrue(scene.textBounds("合成发送者").isEmpty(), "Private messages have no redundant sender header.")
            }
            for (end in listOf(false, true)) {
                bubbleScene(message, 360, density, mode = mode, chat = group, startsGroup = !end, endsGroup = end) { bounds = it }.use { scene ->
                    val frame = scene.settledFrame()
                    assertClose(40f * density, bounds.left, "Every grouped incoming message keeps exactly one sender gutter.")
                    assertEquals(!end, scene.textBounds("合成发送者").isNotEmpty(), "The name belongs to the group head.")
                    val circleY = (bounds.bottom - 16.5f * density).roundToInt()
                    val avatarPixels = (0 until (33f * density).roundToInt()).count { x ->
                        colorDistance(frame[x, circleY], frame[0, frame.height - 1]) > 10
                    }
                    if (end) assertTrue(abs(avatarPixels - 33f * density) <= 2f, "The group-tail avatar is a real bottom-aligned 33dp circle.")
                    else assertEquals(0, avatarPixels, "Group heads reserve the gutter without duplicating an avatar.")
                    writeFrame(frame, "sender-$mode-$density-tail-$end")
                }
            }
        }
    }

    private fun bubbleScene(message: ChatMessage, widthDp: Int, density: Float, heightDp: Int = 400,
        visible: Boolean = false, showSender: Boolean = false,
        mode: ColorSchemeMode = ColorSchemeMode.Light,
        chat: ChatItem? = null, startsGroup: Boolean = true, endsGroup: Boolean = true,
        onBounds: (Rect) -> Unit): ComposePixelScene =
        ComposePixelScene((widthDp * density).roundToInt(), (heightDp * density).roundToInt()) {
            val theme = remember { ThemeController(mode) }
            val snapshot = remember {
                ChatSnapshot(enabled = true, token = ChatToken(1, 1, message.chatId),
                    selected = chat ?: ChatItem(message.chatId, "合成聊天"), messages = listOf(message),
                    senderNames = mapOf(20L to "引用者"))
            }
            CompositionLocalProvider(LocalDensity provides Density(density),
                LocalDesktopChatLayout provides DesktopChatLayout(compact = true)) {
                MiuixTheme(controller = theme) {
                    Box(Modifier.fillMaxSize().background(Color.Magenta)) {
                        val presentation = chat?.let { conversationMessagePresentation(message, it, startsGroup, endsGroup) }
                        MessageBubble(
                            message = message, sender = "合成发送者", showSender = showSender, endsGroup = endsGroup,
                            onBounds = { if (it != null) onBounds(it) }, retrying = false, highlighted = false,
                            read = false, onRetry = {}, snapshot = snapshot, features = ChatFeatureActions(),
                            visible = visible, animate = false, onEnlarge = {}, onSave = { _, _ -> },
                            onPlay = {}, playing = false, onStopPlaying = {}, onVideoBounds = {},
                            playbackSession = null, onExpandVideo = {}, menuOpen = false,
                            onMenuOpen = {}, onMenuDismiss = {}, onReply = {}, onQuote = {},
                            onJumpToReply = {}, onReact = {}, onReadDetails = {}, onEdit = {},
                            onForward = {}, onDelete = {}, onSearchSender = {},
                            showSenderName = presentation?.showSenderName ?: showSender,
                            showAvatar = presentation?.showAvatar ?: (showSender && !message.outgoing),
                            reserveAvatarSpace = presentation?.reserveAvatarSpace ?: !message.outgoing,
                        )
                    }
                }
            }
        }

    private fun ComposePixelScene.textBounds(text: String): List<Rect> = unmergedSemanticsBounds { config ->
        config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text }
    }

    private fun ComposePixelScene.textLayout(text: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        unmergedSemanticsBounds { config ->
            val match = config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text }
            if (match) config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            match
        }
        return layouts.single()
    }

    private fun ComposePixelScene.descriptionBounds(description: String): List<Rect> = unmergedSemanticsBounds {
        description in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }

    private fun ComposePixelScene.awaitPhoto() {
        val deadline = System.nanoTime() + 5_000_000_000L
        do {
            settledFrame(2)
            if (descriptionBounds("聊天图片").isNotEmpty()) return
            Thread.sleep(5)
        } while (System.nanoTime() < deadline)
        throw AssertionError("The isolated synthetic photo did not finish decoding.")
    }

    private fun withSyntheticPhoto(test: (Path) -> Unit) {
        val directory = Files.createTempDirectory("nnggram-message-photo-")
        val path = directory.resolve("synthetic.png")
        try { test(path) } finally {
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
    }

    private fun writePhoto(path: Path, width: Int, height: Int) {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        image.createGraphics().let { graphics ->
            try {
                graphics.color = java.awt.Color(36, 189, 118)
                graphics.fillRect(0, 0, width, height)
            } finally { graphics.dispose() }
        }
        check(ImageIO.write(image, "png", path.toFile()))
    }

    private fun sharpGreenForegroundBounds(frame: ComposePixelFrame, canvas: Rect): Rect {
        var left = frame.width
        var top = frame.height
        var right = 0
        var bottom = 0
        val source = java.awt.Color(36, 189, 118).rgb
        for (y in canvas.top.toInt().coerceAtLeast(0) until canvas.bottom.toInt().coerceAtMost(frame.height)) {
            for (x in canvas.left.toInt().coerceAtLeast(0) until canvas.right.toInt().coerceAtMost(frame.width)) {
                // The underlay has a 48/255 black tint: exact undarkened green identifies
                // only the clear foreground, never the same-source blurred margins.
                if (colorDistance(frame[x, y], source) < 4) {
                    left = minOf(left, x); right = maxOf(right, x + 1)
                    top = minOf(top, y); bottom = maxOf(bottom, y + 1)
                }
            }
        }
        assertTrue(right > left && bottom > top, "The synthetic image must actually be decoded and painted.")
        return Rect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
    }

    private fun ComposePixelScene.awaitPhotoBackground() {
        val deadline = System.nanoTime() + 5_000_000_000L
        do {
            settledFrame(2)
            if (descriptionBounds("照片模糊底图").isNotEmpty()) return
            Thread.sleep(5)
        } while (System.nanoTime() < deadline)
        throw AssertionError("The real inline photo background did not finish worker preparation.")
    }

    private fun writeFrame(frame: ComposePixelFrame, name: String) {
        val directory = Path.of(System.getProperty("java.io.tmpdir"), "behavior-alignment-media")
        Files.createDirectories(directory)
        val image = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until frame.height) for (x in 0 until frame.width) image.setRGB(x, y, frame[x, y])
        check(ImageIO.write(image, "png", directory.resolve("$name.png").toFile()))
    }

    private fun assertClose(expected: Float, actual: Float, reason: String) {
        assertTrue(abs(expected - actual) <= 1.5f, "$reason Expected=$expected, actual=$actual")
    }

    private fun colorDistance(first: Int, second: Int): Int = listOf(16, 8, 0).maxOf { shift ->
        abs((first ushr shift and 255) - (second ushr shift and 255))
    }
}
