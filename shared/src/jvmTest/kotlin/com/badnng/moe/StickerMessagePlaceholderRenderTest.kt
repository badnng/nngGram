package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
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
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Native outline coordinates and actual masked-shimmer pixels, with no account or network IO. */
class StickerMessagePlaceholderRenderTest {
    @Test
    fun nativeOutlineKeepsItsShapeAndShimmersOnlyWhileVisibleAndActive() {
        for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) {
            for (density in listOf(1f, 1.25f)) {
                val visible = mutableStateOf(true)
                val animate = mutableStateOf(true)
                val media = pendingMedia()
                val snapshot = snapshot(media)
                val outlineCalls = mutableListOf<Int>()
                val transfer = ArtworkDownloadBrokerFixture { media.file }
                transfer.seedPendingUser(snapshot.token, media.file.id)
                transfer.use {
                scene(mode, density) {
                    StickerMessageMedia(media, snapshot,
                        transfer.actions { _, id -> outlineCalls += id },
                        visible.value, animate.value, Modifier.offset(24.dp, 24.dp).size(224.dp, 112.dp))
                }.use { scene ->
                    val bounds = bounds(density)
                    val first = scene.settledFrame(3)
                    assertNativeSilhouette(first, bounds)
                    assertTrue(scene.textLabels().isEmpty(),
                        "The native outline must replace emoji, loader text, and generic media cards.")
                    var moving = first
                    var changes = 0
                    repeat(12) {
                        val next = scene.settledFrame(1)
                        changes = maxOf(changes, changedPixels(first, next, bounds))
                        moving = next
                    }
                    assertTrue(changes > 20, "The real clipped highlight must move across the native silhouette.")
                    assertNativeSilhouette(moving, bounds)
                    animate.value = false
                    val stopped = scene.settledFrame(4)
                    assertEquals(0, changedPixels(stopped, scene.settledFrame(8), bounds),
                        "An inactive conversation must not keep a placeholder animation running.")
                    animate.value = true
                    visible.value = false
                    val invisible = scene.settledFrame(4)
                    assertEquals(0, changedPixels(invisible, scene.settledFrame(8), bounds),
                        "An offscreen sticker cannot run its placeholder highlight.")
                    assertEquals(1, transfer.windows.size, "An outline may join the actual pending owner but cannot start a second native range.")
                    assertEquals(1, transfer.acquired.size)
                    assertEquals(MediaDownloadPurpose.ARTWORK, transfer.acquired.single().purpose)
                    assertEquals(transfer.acquired.toList(), transfer.released.toList(), "The offscreen sticker must actually release its own lease.")
                    assertTrue(transfer.cancellations.isEmpty(), "An offscreen outline releases itself without cancelling the independent USER owner.")
                    assertTrue(outlineCalls.isEmpty(), "An available outline must not be requested again.")
                    if (mode == ColorSchemeMode.Light && density == 1f) export(first, "native-outline-loading-light")
                }
                }
            }
        }
    }

    @Test
    fun failedLocalDecodeKeepsTheNativeTemplateWithoutAnOpaqueOrEmojiFallback() {
        val directory = Files.createTempDirectory("nnggram-sticker-corrupt-preview-")
        val file = directory.resolve("corrupt-sticker.webp")
        try {
            Files.writeString(file, "synthetic invalid sticker bytes")
            val media = pendingMedia().copy(file = MediaFile(71, downloaded = true, localPath = file.toString()))
            val snapshot = snapshot(media)
            val downloads = mutableListOf<Int>()
            scene(ColorSchemeMode.Light, 1f) {
                StickerMessageMedia(media, snapshot, ChatFeatureActions(downloadFile = { _, id -> downloads += id }),
                    visible = true, animate = false, modifier = Modifier.offset(24.dp, 24.dp).size(224.dp, 112.dp))
            }.use { scene ->
                val pending = scene.settledFrame(3)
                assertNativeSilhouette(pending, bounds(1f))
                // Yield for the real isolated decoder. A decode failure must keep exactly the same
                // template and geometry, rather than substituting its generic error Text node.
                repeat(12) { Thread.sleep(5); scene.settledFrame(1) }
                val failed = scene.settledFrame(4)
                assertNativeSilhouette(failed, bounds(1f))
                assertEquals(0, changedPixels(pending, failed, bounds(1f)),
                    "A failure may not collapse or replace the native loading silhouette.")
                assertTrue(scene.textLabels().isEmpty(), "Failed sticker decoding must not show the native emoji or ordinary media failure labels.")
                assertTrue(downloads.isEmpty(), "A decoder failure must not silently re-download a native completed file.")
                export(failed, "native-outline-decode-failure")
            }
        } finally {
            Files.deleteIfExists(file)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun successfulDecodeReplacesTheOutlineAndDoesNotRoundAwayTheStickerOwnPixels() {
        val directory = Files.createTempDirectory("nnggram-sticker-own-edges-")
        val path = directory.resolve("synthetic-native-sticker.png")
        try {
            val image = BufferedImage(224, 112, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until image.height) for (x in 0 until image.width) image.setRGB(x, y, GREEN)
            check(ImageIO.write(image, "png", path.toFile()))
            val media = mutableStateOf(pendingMedia())
            for (density in listOf(1f, 1.25f)) {
                media.value = pendingMedia()
                scene(ColorSchemeMode.Light, density) {
                    StickerMessageMedia(media.value, snapshot(media.value), ChatFeatureActions(),
                        visible = true, animate = false, modifier = Modifier.offset(24.dp, 24.dp).size(224.dp, 112.dp))
                }.use { scene ->
                    val nativeBounds = bounds(density)
                    assertNativeSilhouette(scene.settledFrame(3), nativeBounds)
                    media.value = media.value.copy(file = MediaFile(71, downloaded = true, localPath = path.toString()))
                    val deadline = System.nanoTime() + 5_000_000_000L
                    var pixels = scene.settledFrame(2)
                    while (!isGreen(pixels[nativeBounds.center.x.roundToInt(), nativeBounds.center.y.roundToInt()]) &&
                        System.nanoTime() < deadline) {
                        Thread.sleep(5)
                        pixels = scene.settledFrame(2)
                    }
                    for ((x, y) in listOf(nativeBounds.left + 1f to nativeBounds.top + 1f,
                        nativeBounds.right - 2f to nativeBounds.top + 1f,
                        nativeBounds.left + 1f to nativeBounds.bottom - 2f,
                        nativeBounds.right - 2f to nativeBounds.bottom - 2f,
                        nativeBounds.center.x to nativeBounds.center.y)) {
                        assertTrue(isGreen(pixels[x.roundToInt(), y.roundToInt()]),
                            "The sticker's own edge pixels must replace the silhouette without a photo-style corner clip.")
                    }
                    assertTrue(scene.textLabels().isEmpty())
                    if (density == 1f) export(pixels, "native-sticker-own-edges")
                }
            }
        } finally {
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
    }

    private fun pendingMedia() = MessageMedia(MediaFile(71, downloading = true, canDownload = true),
        width = 512, height = 256, format = MediaFormat.WEBP, emoji = "🙂")

    private fun snapshot(media: MessageMedia) = ChatSnapshot(enabled = true, token = ChatToken(1, 1, 10),
        selected = ChatItem(10, "Synthetic native outline"),
        messages = listOf(ChatMessage(1, 10, kind = MessageKind.STICKER, media = media)),
        stickerOutlines = mapOf(media.file.id to StickerOutline("M128 0 L256 64 L128 128 L0 64 Z", 256, 128)))

    private fun scene(mode: ColorSchemeMode, density: Float, content: @androidx.compose.runtime.Composable () -> Unit) =
        ComposePixelScene((280 * density).roundToInt(), (180 * density).roundToInt()) {
            val controller = remember { ThemeController(mode) }
            CompositionLocalProvider(LocalDensity provides Density(density)) {
                MiuixTheme(controller = controller) {
                    Box(Modifier.fillMaxSize().background(Color(BACKDROP))) { content() }
                }
            }
        }

    private fun bounds(density: Float) = Rect(24 * density, 24 * density, 248 * density, 136 * density)

    private fun assertNativeSilhouette(pixels: ComposePixelFrame, bounds: Rect) {
        assertTrue(colorDistance(pixels[bounds.center.x.roundToInt(), bounds.center.y.roundToInt()], BACKDROP) > 10,
            "The supplied native path must actually render before the full file is available.")
        for ((x, y) in listOf(bounds.left + 5f to bounds.top + 5f,
            bounds.right - 6f to bounds.top + 5f, bounds.left + 5f to bounds.bottom - 6f,
            bounds.right - 6f to bounds.bottom - 6f)) {
            assertTrue(colorDistance(pixels[x.roundToInt(), y.roundToInt()], BACKDROP) <= 1,
                "Outside the native diamond silhouette remains transparent; no rounded rectangle template is allowed.")
        }
        // The native diamond reaches both horizontal and vertical bounds. This also catches
        // a fixed-size center glyph masquerading as the native outline.
        for ((x, y) in listOf(bounds.center.x to bounds.top + 5f, bounds.center.x to bounds.bottom - 6f,
            bounds.left + 5f to bounds.center.y, bounds.right - 6f to bounds.center.y)) {
            assertTrue(colorDistance(pixels[x.roundToInt(), y.roundToInt()], BACKDROP) > 10,
                "The native outline must scale in its own coordinate space across the complete sticker bounds.")
        }
    }

    private fun ComposePixelScene.textLabels(): List<Rect> = semanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().isNotEmpty()
    }

    private fun changedPixels(first: ComposePixelFrame, next: ComposePixelFrame, bounds: Rect): Int {
        var count = 0
        for (y in bounds.top.roundToInt() until bounds.bottom.roundToInt())
            for (x in bounds.left.roundToInt() until bounds.right.roundToInt()) if (first[x, y] != next[x, y]) count++
        return count
    }

    private fun export(frame: ComposePixelFrame, name: String) {
        val directory = Path.of(System.getProperty("java.io.tmpdir"), "sticker-message-layout")
        Files.createDirectories(directory)
        val image = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until frame.height) for (x in 0 until frame.width) image.setRGB(x, y, frame[x, y])
        check(ImageIO.write(image, "png", directory.resolve("$name.png").toFile()))
    }

    private fun colorDistance(first: Int, second: Int): Int = listOf(24, 16, 8, 0).maxOf { shift ->
        abs((first ushr shift and 255) - (second ushr shift and 255))
    }

    private fun isGreen(pixel: Int) = (pixel ushr 8 and 255) > 130 &&
        (pixel ushr 16 and 255) < 80 && (pixel and 255) in 70..170

    private companion object {
        val BACKDROP = 0xFFEF24DB.toInt()
        val GREEN = 0xFF24BD76.toInt()
    }
}
