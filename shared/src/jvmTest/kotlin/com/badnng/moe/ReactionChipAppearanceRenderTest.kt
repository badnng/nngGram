package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
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
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class ReactionChipAppearanceRenderTest {
    @Test
    fun selectedCountHasAFullOpaqueAccentAndReadableForegroundInBothThemes() = withAssets { still, _ ->
        for (density in listOf(1f, 1.25f, 2f)) for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) {
            val reactions = listOf(MessageReaction("🔥", 123, true), MessageReaction("👍", 4))
            var selectedInk = 0
            var ordinaryFill = 0
            scene(reactions, still, density = density, mode = mode, colors = { _, ink, ordinary ->
                selectedInk = ink; ordinaryFill = ordinary
            }).use { scene ->
                val frame = scene.awaitArtwork()
                val selected = scene.chip("🔥")
                val ordinary = scene.chip("👍")
                val fillPixel = frame[(selected.right - 4 * density).roundToInt(), selected.center.y.roundToInt()]
                assertEquals(255, fillPixel ushr 24 and 255,
                    "The actual selected surface must be opaque: $mode/$density")
                assertEquals(ordinaryFill,
                    frame[(ordinary.right - 4 * density).roundToInt(), ordinary.center.y.roundToInt()])
                assertTrue(contrast(fillPixel, ordinaryFill) >= 1.5,
                    "The selected accent must remain visibly distinct from the ordinary reaction surface.")
                val count = assertNotNull(scene.unmergedSemanticsBounds { node ->
                    node.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "123" }
                }.singleOrNull())
                var inkPixels = 0
                var actualInk = 0
                var closestInkDistance = Int.MAX_VALUE
                for (y in count.top.roundToInt() until count.bottom.roundToInt())
                    for (x in count.left.roundToInt() until count.right.roundToInt()) {
                        val pixel = frame[x, y]
                        if (near(pixel, selectedInk, 12)) inkPixels++
                        val distance = listOf(0, 8, 16).sumOf { shift ->
                            abs((pixel ushr shift and 255) - (selectedInk ushr shift and 255))
                        }
                        if (distance < closestInkDistance) { closestInkDistance = distance; actualInk = pixel }
                    }
                assertTrue(inkPixels > 2, "The actual count glyph must use the theme's readable on-accent foreground.")
                assertTrue(contrast(fillPixel, actualInk) >= 4.5,
                    "The actual small count glyph and selected surface must retain 4.5:1 contrast: $mode/$density")
                export(frame, "selected-$mode-$density")
            }
        }
    }

    @Test
    fun officialCompactChipsKeepTheEighteenPixelArtworkAndFourPixelInterChipGap() = withAssets { still, _ ->
        for (density in listOf(1f, 1.25f, 2f)) {
            scene(listOf(MessageReaction("🔥", 2, true), MessageReaction("👍", 9)), still, density = density).use { scene ->
                val frame = scene.awaitArtwork()
                val first = scene.chip("🔥")
                val second = scene.chip("👍")
                assertEquals((22 * density).roundToInt().toFloat(), first.height,
                    "A compact native reaction row must retain the official 22dp height.")
                assertEquals((4 * density).roundToInt().toFloat(), second.left - first.right,
                    "Adjacent reactions reserve the official 4dp gap.")
                val green = buildList {
                    for (y in first.top.roundToInt() until first.bottom.roundToInt())
                        for (x in first.left.roundToInt() until first.right.roundToInt()) if (isGreen(frame[x, y])) add(x to y)
                }
                assertTrue(green.isNotEmpty())
                val artworkLeft = green.minOf { it.first }
                val artworkRight = green.maxOf { it.first } + 1
                val artworkTop = green.minOf { it.second }
                val artworkBottom = green.maxOf { it.second } + 1
                assertTrue(abs(artworkLeft - first.left - 5 * density) <= 1,
                    "Artwork starts at the official 5dp inset.")
                assertTrue(abs(artworkRight - artworkLeft - 18 * density) <= 1)
                assertTrue(abs(artworkBottom - artworkTop - 18 * density) <= 1)
                export(frame, "compact-body-$density")
            }
        }
    }

    @Test
    fun onlyCompleteSmallNativeAvatarListsReplaceTheCountAndKeepTheOfficialOverlap() = withAssets { still, avatar ->
        val density = 1.25f
        for (total in listOf(1, 2, 3, 100)) {
            val recent = (1..if (total <= 3) total else 1).map { MessageReactionSender(it.toLong()) }
            val reactions = listOf(MessageReaction("🔥", total, true, recentSenders = recent))
            val avatars = (1L..3L).associateWith { MediaFile(10 + it.toInt(), downloaded = true, localPath = avatar.toString()) }
            scene(reactions, still, avatars, density).use { scene ->
                val frame = scene.awaitArtwork(expectAvatar = total <= 3)
                val chip = scene.chip("🔥")
                if (total <= 3) {
                    assertFalse(scene.hasCount(total.toString()), "A complete small native recent list replaces the numeral.")
                    val officialWidth = (45 + (total - 1) * 13) * density
                    assertTrue(abs(chip.width - officialWidth) <= 2,
                        "20dp portraits overlap by 7dp and share 1dp spacing after the icon: total=$total, width=${chip.width}")
                } else {
                    assertTrue(scene.hasCount("100"), "One or a few recent senders must not hide a count of 100.")
                    assertEquals(0, countMagenta(frame), "A partial group recent list must retain the numerical branch.")
                }
                export(frame, "native-avatar-count-$total")
            }
        }
        val missing = listOf(MessageReaction("🔥", 1, true, recentSenders = listOf(MessageReactionSender(99))))
        scene(missing, still, density = density).use { scene ->
            scene.awaitArtwork()
            assertTrue(scene.hasCount("1"), "A native sender without actual avatar media must not make the count disappear.")
        }
    }

    private fun scene(reactions: List<MessageReaction>, still: Path, avatars: Map<Long, MediaFile> = emptyMap(),
        density: Float = 1f, mode: ColorSchemeMode = ColorSchemeMode.Light,
        colors: (Int, Int, Int) -> Unit = { _, _, _ -> }) =
        ComposePixelScene((300 * density).roundToInt(), (110 * density).roundToInt()) {
            val controller = remember { ThemeController(mode) }
            val message = ChatMessage(1, 10, reactions = reactions)
            val snapshot = ChatSnapshot(enabled = true, token = ChatToken(1, 1, 10),
                selected = ChatItem(10, "合成群", basicGroupId = 1), senderAvatars = avatars,
                reactionVisuals = reactions.associate { reaction -> reaction.key to ReactionVisual(reaction.key,
                    staticIcon = MessageMedia(MediaFile(1, localPath = still.toString(), downloaded = true), format = MediaFormat.PHOTO)) })
            CompositionLocalProvider(LocalDensity provides Density(density)) {
                MiuixTheme(controller = controller) {
                    colors(MiuixTheme.colorScheme.primary.toArgb(), MiuixTheme.colorScheme.onPrimary.toArgb(),
                        MiuixTheme.colorScheme.surfaceContainer.toArgb())
                    Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface).padding(24.dp)) {
                        MessageReactionRow(message, snapshot, ChatFeatureActions(), active = true)
                    }
                }
            }
        }

    private fun ComposePixelScene.chip(emoji: String): Rect = assertNotNull(semanticsBounds { node ->
        node.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it.startsWith("$emoji ") && it.endsWith("条回应") }
    }.singleOrNull())

    private fun ComposePixelScene.hasCount(value: String) = unmergedSemanticsBounds { node ->
        node.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == value }
    }.isNotEmpty()

    private fun ComposePixelScene.awaitArtwork(expectAvatar: Boolean = false): ComposePixelFrame {
        val deadline = System.nanoTime() + 4_000_000_000L
        do {
            val frame = settledFrame(2)
            var green = 0
            for (y in 0 until frame.height) for (x in 0 until frame.width) if (isGreen(frame[x, y])) green++
            if (green > 100 && (!expectAvatar || countMagenta(frame) > 20)) return frame
            Thread.sleep(5)
        } while (System.nanoTime() < deadline)
        error("Synthetic reaction artwork did not decode.")
    }

    private fun withAssets(test: (Path, Path) -> Unit) {
        val directory = Files.createTempDirectory("synthetic-compact-reaction-")
        try {
            val still = directory.resolve("green.png")
            val avatar = directory.resolve("magenta.png")
            for ((path, color) in listOf(still to 0xFF14C828.toInt(), avatar to 0xFFC614C6.toInt())) {
                val image = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
                for (y in 0 until 32) for (x in 0 until 32) image.setRGB(x, y, color)
                check(ImageIO.write(image, "png", path.toFile()))
            }
            test(still, avatar)
        } finally {
            Files.list(directory).use { files -> files.forEach(Files::delete) }
            Files.delete(directory)
        }
    }

    private fun export(frame: ComposePixelFrame, name: String) {
        val directory = Path.of(System.getProperty("java.io.tmpdir"), "reaction-chip-appearance")
        Files.createDirectories(directory)
        val image = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until frame.height) for (x in 0 until frame.width) image.setRGB(x, y, frame[x, y])
        check(ImageIO.write(image, "png", directory.resolve("$name.png").toFile()))
    }

    private fun near(first: Int, second: Int, tolerance: Int) = listOf(0, 8, 16, 24).all {
        abs((first ushr it and 255) - (second ushr it and 255)) <= tolerance
    }
    private fun isGreen(pixel: Int) = (pixel ushr 8 and 255) > 160 && (pixel ushr 16 and 255) < 90 && (pixel and 255) < 90
    private fun countMagenta(frame: ComposePixelFrame): Int {
        var count = 0
        for (y in 0 until frame.height) for (x in 0 until frame.width) {
            val pixel = frame[x, y]
            if ((pixel ushr 16 and 255) > 150 && (pixel and 255) > 150 && (pixel ushr 8 and 255) < 70) count++
        }
        return count
    }
    private fun contrast(first: Int, second: Int): Double {
        fun luminance(pixel: Int): Double {
            fun linear(shift: Int): Double {
                val channel = (pixel ushr shift and 255) / 255.0
                return if (channel <= 0.04045) channel / 12.92 else ((channel + 0.055) / 1.055).pow(2.4)
            }
            return 0.2126 * linear(16) + 0.7152 * linear(8) + 0.0722 * linear(0)
        }
        val one = luminance(first)
        val two = luminance(second)
        return (maxOf(one, two) + 0.05) / (minOf(one, two) + 0.05)
    }
}
