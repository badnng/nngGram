package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.MediaFile
import com.badnng.moe.chat.MediaFormat
import com.badnng.moe.chat.MessageMedia
import com.badnng.moe.media.MediaPreview
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class MediaPreviewScaleRenderTest {
    @Test fun defaultFitPreservesTheWholePhotoAndItsLetterbox() = withPhoto { path ->
        ComposePixelScene(140, 140) {
            Box(Modifier.fillMaxSize().background(Color.Magenta)) {
                MediaPreview(path.toString(), MediaFormat.PHOTO,
                    Modifier.offset(20.dp, 20.dp).size(100.dp), animate = false, placeholder = {})
            }
        }.use { scene ->
            val frame = scene.awaitDecodedFrame()
            assertColor(Color.Magenta, frame[70, 25], "Fit must retain its top letterbox.")
            assertColor(Color.Magenta, frame[70, 115], "Fit must retain its bottom letterbox.")
            assertColor(Color.Red, frame[30, 70], "Fit must retain the source's left edge.")
            assertColor(Color.Blue, frame[110, 70], "Fit must retain the source's right edge.")
            assertColor(Color.Green, frame[70, 70], "Fit must center the photo.")
        }
    }

    @Test fun cropFillsTheSquareAndClipsCenteredLandscapeAndPortraitOverflow() {
        for (portrait in listOf(false, true)) withPhoto(portrait) { path ->
            ComposePixelScene(140, 140) {
                Box(Modifier.fillMaxSize().background(Color.Magenta)) {
                    MediaPreview(path.toString(), MediaFormat.PHOTO,
                        Modifier.offset(20.dp, 20.dp).size(100.dp), animate = false, placeholder = {},
                        contentScale = ContentScale.Crop)
                }
            }.use { scene ->
                val frame = scene.awaitDecodedFrame()
                for ((x, y) in listOf(70 to 21, 70 to 118, 21 to 70, 118 to 70)) {
                    assertColor(Color.Green, frame[x, y], "Crop must fill the square with the center of the source.")
                }
                for ((x, y) in listOf(70 to 10, 70 to 130, 10 to 70, 130 to 70)) {
                    assertColor(Color.Magenta, frame[x, y], "Crop must not draw beyond its canvas bounds.")
                }
            }
        }
    }

    @Test fun remotePhotosAndNativeThumbnailsForwardCropAndKeepTheTileCornersClipped() = withPhoto { path ->
        val file = MediaFile(11, downloaded = true, localPath = path.toString())
        val examples = listOf(
            MessageMedia(file),
            MessageMedia(MediaFile(10), thumbnail = file, format = MediaFormat.VIDEO),
        )
        for (media in examples) {
            ComposePixelScene(140, 140) {
                val theme = remember { ThemeController(ColorSchemeMode.Light) }
                MiuixTheme(controller = theme) {
                    Box(Modifier.fillMaxSize().background(Color.Magenta)) {
                        RemoteMedia(media, ChatSnapshot(), ChatFeatureActions(), visible = true, animate = false,
                            modifier = Modifier.offset(20.dp, 20.dp).size(100.dp), compact = true,
                            onClick = {}, cornerRadius = 15.dp, placeholder = {}, contentScale = ContentScale.Crop)
                    }
                }
            }.use { scene ->
                val frame = scene.awaitDecodedFrame()
                assertColor(Color.Green, frame[70, 22], "The tile must fill its upper edge instead of letterboxing.")
                assertColor(Color.Green, frame[70, 117], "The tile must fill its lower edge instead of letterboxing.")
                assertColor(Color.Magenta, frame[21, 21], "The thumbnail must retain its 15dp rounded corner.")
                assertColor(Color.Magenta, frame[118, 118], "Crop must remain inside the rounded tile.")
            }
        }
    }

    private fun ComposePixelScene.awaitDecodedFrame(): ComposePixelFrame {
        val deadline = System.nanoTime() + 5_000_000_000L
        do {
            val frame = settledFrame(2)
            if (sameColor(Color.Green, frame[70, 70])) return frame
            Thread.sleep(5)
        } while (System.nanoTime() < deadline)
        throw AssertionError("The synthetic photo did not finish decoding in the offscreen scene.")
    }

    /** A centered green half with red/blue edges makes crop alignment observable in actual pixels. */
    private fun withPhoto(portrait: Boolean = false, test: (Path) -> Unit) {
        val path = Files.createTempFile("nnggram-media-scale-", ".png")
        try {
            val image = BufferedImage(if (portrait) 100 else 200, if (portrait) 200 else 100, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until image.height) for (x in 0 until image.width) {
                val position = if (portrait) y else x
                image.setRGB(x, y, when {
                    position < 50 -> Color.Red.toArgb()
                    position >= 150 -> Color.Blue.toArgb()
                    else -> Color.Green.toArgb()
                })
            }
            check(ImageIO.write(image, "png", path.toFile()))
            test(path)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    private fun assertColor(expected: Color, actual: Int, message: String) {
        assertTrue(sameColor(expected, actual), message)
    }

    private fun sameColor(expected: Color, actual: Int): Boolean {
        val value = expected.toArgb()
        return listOf(24, 16, 8, 0).all { shift -> abs((value ushr shift and 255) - (actual ushr shift and 255)) <= 1 }
    }
}
