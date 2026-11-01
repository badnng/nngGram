package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.*
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Real decoded transparent sticker pixels, rather than a synthetic Box posing as media. */
class StickerMessageRenderTest {
    @Test
    fun stickerArtworkUsesOfficialNativeBoundsAndLeavesItsTransparentCornersUnfilled() {
        withDiamond { path ->
            for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) {
                for (density in listOf(1f, 1.25f)) {
                    for ((index, dimensions) in listOf(512 to 256, 256 to 512, 64 to 48).withIndex()) {
                        val (width, height) = dimensions
                        writeDiamond(path, width, height)
                        val scale = minOf(1f, 224f / maxOf(width, height))
                        val expectedWidth = width * scale
                        val expectedHeight = height * scale
                        val message = sticker(path, width, height, outgoing = index % 2 == 0)
                        var bubble = Rect.Zero
                        scene(message, mode, density, widthDp = 600) { bubble = it }.use { scene ->
                            val pixels = scene.awaitDecodedMedia()
                            val artwork = assertNotNull(scene.stickerBounds().singleOrNull())
                            assertClose(expectedWidth * density, artwork.width,
                                "A sticker keeps its native aspect and the official 224dp upper bound: $dimensions/$density")
                            assertClose(expectedHeight * density, artwork.height,
                                "The sticker must not inherit the former arbitrary 192dp image limit.")
                            assertTrue(artwork.left >= bubble.left - 1f && artwork.right <= bubble.right + 1f)
                            assertClose(bubble.top, artwork.top,
                                "The bare sticker must begin at the content edge, without ordinary bubble top padding.")
                            assertTransparentCorners(pixels, artwork,
                                "Transparent sticker pixels must show the wallpaper rather than either media or message-card fill.")
                            val middle = pixels[artwork.center.x.roundToInt(), artwork.center.y.roundToInt()]
                            assertTrue(isGreen(middle), "The assertion must inspect the decoded synthetic artwork.")
                            assertTrue(scene.descriptionBounds("聊天图片").isEmpty(),
                                "A sticker must retain its native media semantics instead of becoming a photo.")
                            if (width == 512 && mode == ColorSchemeMode.Light && density == 1f)
                                export(pixels, "bare-sticker-landscape-light")
                        }
                    }
                }
            }
        }
    }

    @Test
    fun narrowStickerKeepsItsAspectAndAllReactionsAndMetadataOutsideItsPixels() {
        withDiamond { path ->
            val reactions = listOf("👍", "❤️", "🔥", "🎉", "😁", "🤔").mapIndexed { index, emoji ->
                MessageReaction(emoji, index + 1, isChosen = index == 0)
            }
            for (outgoing in listOf(false, true)) {
                for (density in listOf(1f, 1.25f)) {
                    val message = sticker(path, 512, 256, outgoing).copy(
                        editDate = 1_700_000_001, reactions = reactions)
                    var bubble = Rect.Zero
                    scene(message, ColorSchemeMode.Light, density, widthDp = 240) { bubble = it }.use { scene ->
                        val pixels = scene.awaitDecodedMedia()
                        val artwork = assertNotNull(scene.stickerBounds().singleOrNull())
                        assertTrue(artwork.left >= 0f && artwork.right <= 240 * density + 1f,
                            "The full native sticker fits the actual narrow message width.")
                        assertTrue(artwork.width <= 224 * density + 1f)
                        assertClose(2f, artwork.width / artwork.height, "Width constraints preserve the source aspect ratio.", 0.02f)
                        assertTransparentCorners(pixels, artwork, "Resizing must not introduce a filled media frame.")
                        val chips = scene.semanticsBounds { node ->
                            node.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it.endsWith("条回应") }
                        }
                        assertEquals(reactions.size, chips.size, "The bare sticker still owns all of its native reaction controls.")
                        val edited = assertNotNull(scene.textBounds("已编辑").singleOrNull())
                        val footerItems = chips + edited
                        footerItems.forEach { footer ->
                            assertTrue(footer.left >= bubble.left - 1f && footer.right <= bubble.right + 1f,
                                "Footer controls must remain within the complete message bounds: $outgoing/$density/$footer/$bubble")
                            assertTrue(footer.top >= artwork.bottom - 1f && footer.bottom <= bubble.bottom + 1f,
                                "Footer controls sit outside the sticker artwork without being clipped: $footer/$artwork/$bubble")
                        }
                        chips.forEach { assertFalse(it.overlaps(edited), "Time/edit text must not overlap a reaction.") }
                        assertTrue(chips.map { it.top.roundToInt() }.distinct().size > 1,
                            "A narrow footer wraps instead of shrinking or clipping native reaction controls.")
                        if (!outgoing && density == 1f) export(pixels, "bare-sticker-narrow-reactions")
                    }
                }
            }
        }
    }

    @Test
    fun ordinaryPhotosUseTheirEdgeAlignedCanvasIndependentOfStickerArtworkLimits() {
        withPhotoFile { path ->
            writeOpaquePhoto(path, 300, 150)
            val message = ChatMessage(1, 10, outgoing = true, kind = MessageKind.PHOTO, date = 1_700_000_000,
                media = MessageMedia(MediaFile(31, localPath = path.toString(), downloaded = true),
                    width = 300, height = 150, format = MediaFormat.PHOTO))
            var bubble = Rect.Zero
            scene(message, ColorSchemeMode.Light, 1f, widthDp = 600) { bubble = it }.use { scene ->
                val pixels = scene.awaitDecodedMedia()
                val photo = assertNotNull(scene.descriptionBounds("聊天图片").singleOrNull())
                assertTrue(scene.stickerBounds().isEmpty(), "The same native-size source must use photo semantics.")
                assertClose(bubble.left, photo.left, "A bare photo canvas starts at its message edge.")
                assertClose(bubble.top, photo.top, "A bare photo canvas has no sticker or text top inset.")
                assertClose(bubble.right, photo.right, "A bare photo canvas ends at its message edge.")
                assertClose(bubble.bottom, photo.bottom, "A bare photo has no surrounding footer card.")
                assertClose(300f, photo.width,
                    "A 300dp photo keeps its independent photo canvas instead of the sticker 224dp artwork cap.")
                assertClose(150f, photo.height, "The photo canvas preserves its native 2:1 dimensions.")
                assertTrue(photo.width > 224f, "Ordinary photo layout must not inherit the sticker artwork limit.")
                assertClose(2f, photo.width / photo.height, "Photo sizing preserves the source aspect ratio.", 0.02f)
                assertTrue(isGreen(pixels[photo.center.x.roundToInt(), photo.center.y.roundToInt()]),
                    "The independent photo canvas contains the decoded photo pixels.")
                val beside = pixels[(photo.left - 3f).roundToInt(), photo.center.y.roundToInt()]
                assertTrue(colorDistance(beside, BACKDROP) <= 1,
                    "Bare photo pixels meet the message edge without an opaque padding band outside the canvas.")
            }
        }
    }

    private fun scene(message: ChatMessage, mode: ColorSchemeMode, density: Float, widthDp: Int,
        onBounds: (Rect) -> Unit) = ComposePixelScene((widthDp * density).roundToInt(), (500 * density).roundToInt()) {
        val theme = remember { ThemeController(mode) }
        val snapshot = ChatSnapshot(enabled = true, token = ChatToken(1, 1, message.chatId),
            selected = ChatItem(message.chatId, "Synthetic sticker chat"), messages = listOf(message))
        CompositionLocalProvider(LocalDensity provides Density(density),
            LocalDesktopChatLayout provides DesktopChatLayout(compact = true)) {
            MiuixTheme(controller = theme) {
                Box(Modifier.fillMaxSize().background(Color(BACKDROP)).padding(top = 24.dp)) {
                    Bubble(message, snapshot, onBounds)
                }
            }
        }
    }

    @Composable
    private fun Bubble(message: ChatMessage, snapshot: ChatSnapshot, onBounds: (Rect) -> Unit) {
        MessageBubble(message = message, sender = "Synthetic sender", showSender = false, endsGroup = true,
            onBounds = { if (it != null) onBounds(it) }, retrying = false, highlighted = false,
            read = false, onRetry = {}, snapshot = snapshot, features = ChatFeatureActions(),
            visible = true, animate = false, onEnlarge = {}, onSave = { _, _ -> }, onPlay = {},
            playing = false, onStopPlaying = {}, onVideoBounds = {}, playbackSession = null,
            onExpandVideo = {}, menuOpen = false, onMenuOpen = {}, onMenuDismiss = {},
            onReply = {}, onQuote = {}, onJumpToReply = {}, onReact = {}, onReadDetails = {},
            onEdit = {}, onForward = {}, onDelete = {}, onSearchSender = {})
    }

    private fun sticker(path: Path, width: Int, height: Int, outgoing: Boolean) =
        ChatMessage(1, 10, outgoing = outgoing, kind = MessageKind.STICKER, date = 1_700_000_000,
            media = MessageMedia(MediaFile(31, localPath = path.toString(), downloaded = true),
                width = width, height = height, format = MediaFormat.WEBP, emoji = "🙂"))

    private fun ComposePixelScene.stickerBounds(): List<Rect> = unmergedSemanticsBounds {
        it.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { label -> label.startsWith("贴纸") }
    }

    private fun ComposePixelScene.descriptionBounds(description: String): List<Rect> = unmergedSemanticsBounds {
        description in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }

    private fun ComposePixelScene.textBounds(text: String): List<Rect> = semanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == text }
    }

    private fun ComposePixelScene.awaitDecodedMedia(): ComposePixelFrame {
        val deadline = System.nanoTime() + 5_000_000_000L
        do {
            val pixels = settledFrame(2)
            if ((stickerBounds() + descriptionBounds("聊天图片")).any { bounds ->
                isGreen(pixels[bounds.center.x.roundToInt(), bounds.center.y.roundToInt()])
            }) return pixels
            Thread.sleep(5)
        } while (System.nanoTime() < deadline)
        throw AssertionError("The isolated synthetic message media did not finish decoding.")
    }

    private fun assertTransparentCorners(pixels: ComposePixelFrame, bounds: Rect, reason: String) {
        for ((x, y) in listOf(bounds.left + 4f to bounds.top + 4f, bounds.right - 5f to bounds.top + 4f,
            bounds.left + 4f to bounds.bottom - 5f, bounds.right - 5f to bounds.bottom - 5f)) {
            assertTrue(colorDistance(pixels[x.roundToInt(), y.roundToInt()], BACKDROP) <= 1, reason)
        }
    }

    private fun withDiamond(test: (Path) -> Unit) {
        val directory = Files.createTempDirectory("nnggram-sticker-message-")
        val path = directory.resolve("synthetic-transparent-diamond.png")
        try {
            writeDiamond(path, 256, 128)
            test(path)
        } finally {
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
    }

    private fun withPhotoFile(test: (Path) -> Unit) {
        val directory = Files.createTempDirectory("nnggram-sticker-message-photo-")
        val path = directory.resolve("synthetic-photo.png")
        try {
            test(path)
        } finally {
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
    }

    private fun writeDiamond(path: Path, width: Int, height: Int) {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val inside = abs((x + 0.5f) / (width / 2f) - 1f) + abs((y + 0.5f) / (height / 2f) - 1f) < 1f
            image.setRGB(x, y, if (inside) 0xFF24BD76.toInt() else 0)
        }
        check(ImageIO.write(image, "png", path.toFile()))
    }

    private fun writeOpaquePhoto(path: Path, width: Int, height: Int) {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) for (x in 0 until image.width)
            image.setRGB(x, y, 0xFF24BD76.toInt())
        check(ImageIO.write(image, "png", path.toFile()))
    }

    private fun export(frame: ComposePixelFrame, name: String) {
        val directory = Path.of(System.getProperty("java.io.tmpdir"), "sticker-message-layout")
        Files.createDirectories(directory)
        val image = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until frame.height) for (x in 0 until frame.width) image.setRGB(x, y, frame[x, y])
        check(ImageIO.write(image, "png", directory.resolve("$name.png").toFile()))
    }

    private fun assertClose(expected: Float, actual: Float, message: String, tolerance: Float = 1.5f) {
        assertTrue(abs(expected - actual) <= tolerance, "$message Expected=$expected, actual=$actual")
    }

    private fun isGreen(pixel: Int) = (pixel ushr 8 and 255) > 130 &&
        (pixel ushr 16 and 255) < 80 && (pixel and 255) in 70..170

    private fun colorDistance(first: Int, second: Int): Int = listOf(24, 16, 8, 0).maxOf { shift ->
        abs((first ushr shift and 255) - (second ushr shift and 255))
    }

    private companion object {
        val BACKDROP = 0xFFEF24DB.toInt()
    }
}
