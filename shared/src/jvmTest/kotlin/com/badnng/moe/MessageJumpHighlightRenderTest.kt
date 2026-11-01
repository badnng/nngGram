package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.ChatItem
import com.badnng.moe.chat.ChatMessage
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.ChatToken
import com.badnng.moe.chat.MediaFile
import com.badnng.moe.chat.MediaFormat
import com.badnng.moe.chat.MessageForwardInfo
import com.badnng.moe.chat.MessageForwardOrigin
import com.badnng.moe.chat.MessageKind
import com.badnng.moe.chat.MessageMedia
import com.badnng.moe.chat.MessageReaction
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Isolated CPU frames; no account, native engine, desktop window or system input is opened. */
class MessageJumpHighlightRenderTest {
    @Test
    fun textJumpStripeKeepsTheOpaqueBubbleAndRestoresTheRowWithoutMovingAnyContent() {
        val samples = mutableListOf<Pair<String, BufferedImage>>()
        for (mode in modes) for (density in densities) for (outgoing in listOf(false, true)) {
            val message = ChatMessage(1, 10, outgoing = outgoing, date = 1_700_000_000,
                text = BODY, forwardInfo = MessageForwardInfo(MessageForwardOrigin.HiddenUser(SOURCE)),
                reactions = listOf(MessageReaction("🎉", 6)))
            val captures = backdrops.map { backdrop ->
                capture(message, mode, density, backdrop) { scene, bounds ->
                    val body = scene.textBounds(BODY)
                    val header = scene.textBounds("转发自 $SOURCE")
                    listOf(Offset(bounds.left + 4f * density, body.center.y),
                        Offset(bounds.left + 4f * density, header.center.y))
                }.also { result ->
                    if (backdrop == backdrops.first()) {
                        samples += "$mode $density ${if (outgoing) "out" else "in"} normal" to result.normal
                        samples += "$mode $density ${if (outgoing) "out" else "in"} jump" to result.highlighted
                        samples += "$mode $density ${if (outgoing) "out" else "in"} ended" to result.ended
                    }
                }
            }
            assertBackdropIndependent(captures[0], captures[1], "text/$mode/$density/$outgoing")
        }
        writeContactSheet(samples, "text-jump-highlight")
    }

    @Test
    fun photoHeaderAndCaptionKeepTheirOwnOpaqueCardAboveTheJumpStripe() = withArtwork { photo, _ ->
        val samples = mutableListOf<Pair<String, BufferedImage>>()
        for (mode in modes) for (density in densities) for (captionAbove in listOf(false, true)) {
            val message = ChatMessage(1, 10, date = 1_700_000_000, text = BODY, kind = MessageKind.PHOTO,
                media = MessageMedia(MediaFile(31, localPath = photo.toString(), downloaded = true), width = 240, height = 120),
                showCaptionAboveMedia = captionAbove,
                forwardInfo = MessageForwardInfo(MessageForwardOrigin.HiddenUser(SOURCE)),
                reactions = listOf(MessageReaction("🎉", 6)))
            val captures = backdrops.map { backdrop ->
                capture(message, mode, density, backdrop) { scene, bounds ->
                    val body = scene.textBounds(BODY)
                    val header = scene.textBounds("转发自 $SOURCE")
                    listOf(Offset(bounds.left + 4f * density, body.center.y),
                        Offset(bounds.left + 4f * density, header.center.y))
                }.also { result ->
                    if (backdrop == backdrops.first()) {
                        samples += "$mode $density ${if (captionAbove) "above" else "below"} normal" to result.normal
                        samples += "$mode $density ${if (captionAbove) "above" else "below"} jump" to result.highlighted
                    }
                }
            }
            assertBackdropIndependent(captures[0], captures[1], "photo/$mode/$density/$captionAbove")
        }
        writeContactSheet(samples, "photo-jump-highlight")
    }

    @Test
    fun jumpingToBarePhotosAndTransparentStickersDoesNotIntroduceACard() = withArtwork { photo, sticker ->
        for (mode in modes) for (density in densities) for (kind in listOf(MessageKind.PHOTO, MessageKind.STICKER)) {
            val highlighted = mutableStateOf(false)
            val path = if (kind == MessageKind.PHOTO) photo else sticker
            val message = ChatMessage(1, 10, date = 1_700_000_000, kind = kind,
                media = MessageMedia(MediaFile(32, localPath = path.toString(), downloaded = true),
                    width = 240, height = 120, format = if (kind == MessageKind.PHOTO) MediaFormat.PHOTO else MediaFormat.WEBP))
            var bubble = Rect.Zero
            bubbleScene(message, mode, density, Color.Magenta, highlighted) { bubble = it }.use { scene ->
                scene.awaitArtwork(message.kind)
                val normal = scene.settledFrame()
                val bounds = bubble
                highlighted.value = true
                var stripeVisible = false
                repeat(40) {
                    val frame = scene.settledFrame(1, 16)
                    assertEquals(bounds, bubble, "A jump cannot add padding or minimum width to bare $kind artwork.")
                    val corners = listOf(Offset(bounds.left + density, bounds.top + density),
                        Offset(bounds.right - 2f * density, bounds.top + density))
                    for (point in corners) {
                        assertTrue(colorDistance(normal.at(point), MAGENTA) <= 1,
                            "The normal $kind corners expose the actual wallpaper.")
                        val rowPoint = rowProbe(message, density, point.y)
                        assertEquals(frame.at(rowPoint), frame.at(point),
                            "Transparent $kind corners show only the uniform row stripe, never a new media card.")
                        stripeVisible = stripeVisible || colorDistance(frame.at(rowPoint), MAGENTA) > 3
                    }
                }
                assertTrue(stripeVisible, "A jump still identifies the row containing bare $kind artwork.")
                val ended = scene.settledFrame(64, 64)
                assertEquals(MAGENTA, ended.at(Offset(bounds.left + density, bounds.top + density)),
                    "The bare $kind corner returns to the exact wallpaper when the row stripe ends.")
            }
        }
    }

    @Test
    fun snapshotRefreshDoesNotReplayTheSameJumpAndAChangedTokenDoesNotRetainIt() {
        val message = ChatMessage(1, 10, date = 1_700_000_000, text = BODY)
        val highlighted = mutableStateOf(false)
        val token = mutableStateOf(ChatToken(1, 1, 10))
        val refresh = mutableStateOf(0)
        var bounds = Rect.Zero
        bubbleScene(message, ColorSchemeMode.Light, 1f, Color.Magenta, highlighted, token, refresh) {
            bounds = it
        }.use { scene ->
            val normal = scene.settledFrame()
            val rowPoint = rowProbe(message, 1f, bounds.center.y)
            val rowNormal = normal.at(rowPoint)
            highlighted.value = true
            assertTrue(colorDistance(scene.settledFrame(34, 16).at(rowPoint), rowNormal) > 3,
                "The first navigation produces an actual visible row highlight.")
            highlighted.value = true
            refresh.value++
            assertEquals(rowNormal, scene.settledFrame(190, 16).at(rowPoint),
                "An unrelated native snapshot refresh cannot restart an already active highlight for the same target.")
            highlighted.value = false
            scene.settledFrame(2, 16)
            highlighted.value = true
            assertTrue(colorDistance(scene.settledFrame(34, 16).at(rowPoint), rowNormal) > 3,
                "A later explicit navigation can identify the same target again after the previous jump ends.")
            token.value = ChatToken(2, 1, 10)
            highlighted.value = false
            assertEquals(rowNormal, scene.settledFrame(2, 16).at(rowPoint),
                "Changing the native owner with its normal false state cannot retain the previous token's highlight.")
            assertEquals(rowNormal, scene.settledFrame(40, 16).at(rowPoint),
                "The previous owner's pending animation cannot reappear in the replacement snapshot.")
        }
    }

    private data class Capture(
        val normal: BufferedImage,
        val highlighted: BufferedImage,
        val ended: BufferedImage,
        val normalColors: List<Int>,
        val highlightedColors: List<Int>,
        val endColors: List<Int>,
        val rowNormal: Int,
        val rowHighlighted: Int,
        val rowEnded: Int,
        val geometry: List<Rect>,
    )

    private fun capture(message: ChatMessage, mode: ColorSchemeMode, density: Float, backdrop: Color,
        probes: (ComposePixelScene, Rect) -> List<Offset>): Capture {
        val highlighted = mutableStateOf(false)
        var bounds = Rect.Zero
        bubbleScene(message, mode, density, backdrop, highlighted) { bounds = it }.use { scene ->
            if (message.kind == MessageKind.PHOTO) scene.awaitArtwork(message.kind)
            val normal = scene.settledFrame()
            val normalBounds = bounds
            val geometry = scene.contentGeometry(message) + bounds
            val points = probes(scene, bounds)
            val normalColors = points.map { normal.at(it) }
            val rowPoint = rowProbe(message, density, points.first().y)
            assertTrue(rowPoint.x < bounds.left || rowPoint.x > bounds.right,
                "The stripe probe must inspect actual row space outside the message card.")
            val rowNormal = normal.at(rowPoint)
            highlighted.value = true
            var tinted: ComposePixelFrame? = null
            var largestTint = 3
            // Inspect intermediate drawing rather than settling beyond the transient highlight.
            repeat(40) {
                val frame = scene.settledFrame(1, 16)
                assertEquals(normalBounds, bounds, "Jump highlighting must not remeasure the message surface.")
                assertEquals(geometry, scene.contentGeometry(message) + bounds,
                    "Body, forward attribution, metadata and reactions retain their exact geometry during a jump.")
                assertEquals(normalColors, points.map { frame.at(it) },
                    "The official row stripe stays underneath the complete opaque message surface.")
                val tint = colorDistance(frame.at(rowPoint), rowNormal)
                if (tint > largestTint) {
                    largestTint = tint
                    tinted = frame
                }
            }
            val highlight = assertNotNull(tinted, "The transient jump stripe must actually be visible beside the opaque card.")
            // A highlight is temporary even if its navigation owner has not yet been replaced.
            val ended = scene.settledFrame(64, 64)
            assertEquals(geometry, scene.contentGeometry(message) + bounds,
                "Ending the jump leaves all native text/entity and reaction layout unchanged.")
            val endColors = points.map { ended.at(it) }
            assertEquals(normalColors, endColors, "A completed jump restores the exact ordinary message surface.")
            assertEquals(rowNormal, ended.at(rowPoint), "The transient row stripe ends after the official fade.")
            return Capture(crop(normal, bounds, density), crop(highlight, bounds, density), crop(ended, bounds, density),
                normalColors, points.map { highlight.at(it) }, endColors,
                rowNormal, highlight.at(rowPoint), ended.at(rowPoint), geometry)
        }
    }

    private fun assertBackdropIndependent(first: Capture, second: Capture, reason: String) {
        assertEquals(first.geometry, second.geometry, "Changing wallpaper cannot alter message layout: $reason")
        assertEquals(first.normalColors, second.normalColors, "Ordinary text surfaces remain opaque: $reason")
        assertEquals(first.highlightedColors, second.highlightedColors,
            "The row stripe stays behind the retained incoming/outgoing card, so wallpaper never shows through its body: $reason")
        assertEquals(first.endColors, second.endColors, "The normal opaque surface returns after a jump: $reason")
        assertTrue(colorDistance(first.rowNormal, first.rowHighlighted) > 3 &&
            colorDistance(second.rowNormal, second.rowHighlighted) > 3,
            "Both wallpapers retain a visible temporary row highlight: $reason")
        assertEquals(first.rowNormal, first.rowEnded)
        assertEquals(second.rowNormal, second.rowEnded)
    }

    private fun bubbleScene(message: ChatMessage, mode: ColorSchemeMode, density: Float, backdrop: Color,
        highlighted: MutableState<Boolean>, token: MutableState<ChatToken>? = null,
        refresh: MutableState<Int>? = null, onBounds: (Rect) -> Unit) =
        ComposePixelScene((380 * density).roundToInt(), (380 * density).roundToInt()) {
            val controller = remember { ThemeController(mode) }
            val currentToken = token?.value ?: ChatToken(1, 1, message.chatId)
            val snapshot = remember(currentToken, refresh?.value) {
                ChatSnapshot(enabled = true, token = currentToken,
                    selected = ChatItem(message.chatId, "合成跳转聊天", privateUserId = 20, lastMessage = message),
                    messages = listOf(message))
            }
            CompositionLocalProvider(LocalDensity provides Density(density),
                LocalDesktopChatLayout provides DesktopChatLayout(compact = true)) {
                MiuixTheme(controller = controller) {
                    Box(Modifier.fillMaxSize().background(backdrop).padding(20.dp)) {
                        MessageBubble(message, sender = "合成发送者", showSender = false, endsGroup = true,
                            onBounds = { if (it != null) onBounds(it) }, retrying = false, highlighted = highlighted.value,
                            read = false, onRetry = {}, snapshot = snapshot, features = ChatFeatureActions(),
                            visible = true, animate = false, onEnlarge = {}, onSave = { _, _ -> },
                            onPlay = {}, playing = false, onStopPlaying = {}, onVideoBounds = {},
                            playbackSession = null, onExpandVideo = {}, menuOpen = false, onMenuOpen = {},
                            onMenuDismiss = {}, onReply = {}, onQuote = {}, onJumpToReply = {}, onReact = {},
                            onReadDetails = {}, onEdit = {}, onForward = {}, onDelete = {}, onSearchSender = {},
                            showSenderName = false, showAvatar = false, reserveAvatarSpace = false)
                    }
                }
            }
        }

    private fun ComposePixelScene.textBounds(text: String): Rect = assertNotNull(unmergedSemanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == text }
    }.singleOrNull(), "The synthetic message must compose '$text'.")

    private fun ComposePixelScene.contentGeometry(message: ChatMessage): List<Rect> = buildList {
        if (message.text.isNotEmpty()) add(textBounds(message.text))
        message.forwardInfo?.let { add(textBounds("转发自 $SOURCE")) }
        addAll(unmergedSemanticsBounds {
            it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == messageClock(message.date) }
        })
        addAll(semanticsBounds {
            it.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { value -> value.endsWith("条回应") }
        })
    }

    private fun ComposePixelScene.awaitArtwork(kind: MessageKind) {
        val deadline = System.nanoTime() + 5_000_000_000L
        do {
            val frame = settledFrame(2)
            val artwork = unmergedSemanticsBounds {
                it.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { value ->
                    if (kind == MessageKind.PHOTO) value == "聊天图片" else value.startsWith("贴纸")
                }
            }.singleOrNull()
            if (artwork != null && colorDistance(frame.at(artwork.center), GREEN) <= 3) return
            Thread.sleep(5)
        } while (System.nanoTime() < deadline)
        throw AssertionError("Synthetic $kind pixels did not finish decoding.")
    }

    private fun withArtwork(test: (Path, Path) -> Unit) {
        val directory = Files.createTempDirectory("nnggram-jump-highlight-")
        val photo = directory.resolve("photo.png")
        val sticker = directory.resolve("transparent-sticker.png")
        try {
            val solid = BufferedImage(240, 120, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until solid.height) for (x in 0 until solid.width) solid.setRGB(x, y, GREEN)
            check(ImageIO.write(solid, "png", photo.toFile()))
            val diamond = BufferedImage(240, 120, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until diamond.height) for (x in 0 until diamond.width)
                if (abs(x - 120) / 120f + abs(y - 60) / 60f < .8f) diamond.setRGB(x, y, GREEN)
            check(ImageIO.write(diamond, "png", sticker.toFile()))
            test(photo, sticker)
        } finally {
            Files.deleteIfExists(photo)
            Files.deleteIfExists(sticker)
            Files.deleteIfExists(directory)
        }
    }

    private fun ComposePixelFrame.at(point: Offset): Int = this[point.x.roundToInt(), point.y.roundToInt()]

    private fun rowProbe(message: ChatMessage, density: Float, y: Float): Offset =
        Offset((if (message.outgoing) 24f else 356f) * density, y)

    private fun colorDistance(first: Int, second: Int): Int = listOf(16, 8, 0).maxOf { shift ->
        abs((first ushr shift and 255) - (second ushr shift and 255))
    }

    private fun crop(frame: ComposePixelFrame, bounds: Rect, density: Float): BufferedImage {
        val left = (16f * density).roundToInt().coerceIn(0, frame.width - 1)
        val top = (bounds.top - 5f * density).roundToInt().coerceIn(0, frame.height - 1)
        val right = (364f * density).roundToInt().coerceIn(left + 1, frame.width)
        val bottom = (bounds.bottom + 5f * density).roundToInt().coerceIn(top + 1, frame.height)
        val native = BufferedImage(right - left, bottom - top, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until native.height) for (x in 0 until native.width) native.setRGB(x, y, frame[left + x, top + y])
        val image = BufferedImage((native.width / density).roundToInt(), (native.height / density).roundToInt(),
            BufferedImage.TYPE_INT_ARGB)
        image.createGraphics().let { graphics ->
            try { graphics.drawImage(native, 0, 0, image.width, image.height, null) }
            finally { graphics.dispose() }
        }
        return image
    }

    private fun writeContactSheet(samples: List<Pair<String, BufferedImage>>, name: String) {
        val columns = if (name.startsWith("text")) 3 else 2
        val tileWidth = samples.maxOf { it.second.width } + 16
        val tileHeight = samples.maxOf { it.second.height } + 32
        val image = BufferedImage(columns * tileWidth, ((samples.size + columns - 1) / columns) * tileHeight,
            BufferedImage.TYPE_INT_ARGB)
        image.createGraphics().let { graphics ->
            try {
                graphics.color = java.awt.Color(220, 220, 225)
                graphics.fillRect(0, 0, image.width, image.height)
                samples.forEachIndexed { index, (label, sample) ->
                    val x = index % columns * tileWidth + 8
                    val y = index / columns * tileHeight
                    graphics.color = java.awt.Color.BLACK
                    graphics.drawString(label, x, y + 16)
                    graphics.drawImage(sample, x, y + 24, null)
                }
            } finally { graphics.dispose() }
        }
        val directory = Path.of(System.getProperty("java.io.tmpdir"), "behavior-alignment-media", "jump-highlight")
        Files.createDirectories(directory)
        check(ImageIO.write(image, "png", directory.resolve("$name.png").toFile()))
    }

    private val modes = listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)
    private val densities = listOf(1f, 1.25f)
    private val backdrops = listOf(Color.Magenta, Color.Cyan)

    private companion object {
        const val BODY = "？？？Google 疯了\n合成跳转高亮消息。"
        const val SOURCE = "合成频道"
        val GREEN = 0xFF24BD76.toInt()
        val MAGENTA = 0xFFFF00FF.toInt()
    }
}
