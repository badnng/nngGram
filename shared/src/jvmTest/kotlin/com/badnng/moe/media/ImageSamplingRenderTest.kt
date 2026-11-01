package com.badnng.moe.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.badnng.moe.ComposePixelScene
import com.badnng.moe.chat.MediaFormat
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Independent pixel-area references, without real media, FFmpeg, account state or GPU assertions. */
class ImageSamplingRenderTest {
    @Test
    fun streamingFramesUseLinearFilteringWithoutNearestStepsOrCubicHalos() {
        val source = raster(32, 16) { x, _ -> gray(if (x < 11) 64 else 192) }
        source.toImage().use { image ->
            Surface.makeRasterN32Premul(45, 23).use { surface ->
                surface.canvas.clear(WHITE)
                surface.canvas.drawMediaImage(image, Rect.makeWH(45f, 23f))
                val actual = surface.makeImageSnapshot().use(::readRaster)
                assertTrue(actual.colors.all { (it ushr 16 and 255) in 63..193 },
                    "Streaming artwork must not acquire cubic overshoot.")
                assertTrue((0 until actual.width).any { (actual[it, 11] ushr 16 and 255) in 66..190 },
                    "Fractional video-frame scaling must interpolate the edge instead of choosing nearest pixels.")
            }
        }
        val rgba = byteArrayOf(-1, 0, 0, -1, 0, -1, 0, 0)
        premultiplyMediaRgba(rgba)
        Image.makeRaster(ImageInfo(2, 1, ColorType.RGBA_8888, ColorAlphaType.PREMUL), rgba, 8).use { image ->
            Surface.makeRasterN32Premul(9, 3).use { surface ->
                surface.canvas.clear(0xFF808080.toInt())
                surface.canvas.drawMediaImage(image, Rect.makeWH(9f, 3f))
                val actual = surface.makeImageSnapshot().use(::readRaster)
                assertTrue(actual.colors.all { (it ushr 8 and 255) == (it and 255) && (it ushr 16 and 255) >= 128 },
                    "Straight-alpha decoder pixels must not create a green or dark video-frame fringe.")
            }
        }
    }

    @Test
    fun finePatternsPreserveTheirCoverageAcrossDensityAndDownsampleRatios() {
        val patterns = listOf(
            "stripes" to raster(192, 128) { x, _ -> gray(if (x % 3 == 0) 255 else 0) },
            "checker" to raster(192, 128) { x, y -> gray(if ((x / 3 + y / 3) % 2 == 0) 255 else 0) },
        )
        for ((name, source) in patterns) verifyFilteredRasters(source) { actual, expected, context ->
            assertTrue(abs(actual.mean(16) - expected.mean(16)) <= 3.5,
                "$name must preserve average coverage instead of selecting a texture phase: $context")
            assertTrue(actual.meanAbsoluteError(expected) <= 24.0,
                "$name must approach the independent area reference without aliasing: $context")
            if (context == "still/default/1.25/0.25") writeComparison(name, source, actual, expected, 1.25f, .25f)
        }
    }

    @Test
    fun oddSizedFineStripesRetainRegionalCoverageAtNonIntegralRatiosAndDifferentSourcePhases() {
        val targetWidth = 173
        val targetHeight = 113
        for (vertical in listOf(false, true)) for (phase in listOf(0, 1, 2, 5)) {
            val source = raster(997, 653) { x, y ->
                val coordinate = (if (vertical) x else y) + phase
                gray(if (coordinate.mod(7) < 3) 255 else 0)
            }
            val expected = areaReference(source, targetWidth, targetHeight, WHITE)
            withMedia(source, "still") { media ->
                for (density in listOf(1f, 1.25f)) {
                    // A physical 173 px output is deliberately unrelated to the odd source's
                    // texel grid. Both density paths must choose the same effective footprint.
                    val ratio = targetWidth.toFloat() / (source.width * density)
                    val actual = render(media, source, density, ratio, highQuality = false)
                    val quality = render(media, source, density, ratio, highQuality = true)
                    val context = "${if (vertical) "vertical" else "horizontal"}/phase=$phase/density=$density"
                    assertEquals(targetWidth, actual.width)
                    assertEquals(targetHeight, actual.height)
                    assertTrue(actual.maximumChannelError(quality) <= 1,
                        "Quality entry points must retain one non-integral sampling policy: $context")
                    assertTrue(abs(actual.mean(16) - expected.mean(16)) <= 3.5,
                        "Odd-sized fine textures must preserve their total source coverage: $context")
                    assertTrue(actual.meanAbsoluteError(expected) <= 24.0,
                        "A non-integral target must not retain source-phase aliasing: $context")
                    for (row in 0..1) for (column in 0..1) {
                        val left = targetWidth * column / 2
                        val right = targetWidth * (column + 1) / 2
                        val top = targetHeight * row / 2
                        val bottom = targetHeight * (row + 1) / 2
                        var actualSum = 0.0
                        var expectedSum = 0.0
                        var absoluteError = 0.0
                        for (y in top until bottom) for (x in left until right) {
                            val drawn = actual[x, y] ushr 16 and 255
                            val reference = expected[x, y] ushr 16 and 255
                            actualSum += drawn
                            expectedSum += reference
                            absoluteError += abs(drawn - reference)
                        }
                        val count = (right - left) * (bottom - top)
                        assertTrue(abs(actualSum - expectedSum) / count <= 4.0,
                            "A global average must not hide a bright/dark regional moire: $context/region=$column,$row")
                        assertTrue(absoluteError / count <= 24.0,
                            "Each region must retain the area's fine-texture coverage: $context/region=$column,$row")
                    }
                    if (density == 1.25f && phase == 1)
                        writeComparison("odd-${if (vertical) "vertical" else "horizontal"}-phase$phase",
                            source, actual, expected, density, ratio)
                }
            }
        }
    }

    @Test
    fun onePixelDiagonalInkDoesNotDisappearOrBecomeDarkMoires() {
        val source = raster(192, 128) { x, y ->
            gray(if ((x - 2 * y).mod(23) == 0 || (2 * x + y).mod(31) == 0) 32 else 224)
        }
        verifyFilteredRasters(source) { actual, expected, context ->
            assertTrue(abs(actual.mean(16) - expected.mean(16)) <= 3.5,
                "Thin diagonals must retain their total ink coverage: $context")
            assertTrue(actual.meanAbsoluteError(expected) <= 18.0,
                "Diagonal downsampling must distribute ink rather than produce disconnected dark pixels: $context")
            if (context == "still/default/1.25/0.25") writeComparison("diagonal", source, actual, expected, 1.25f, .25f)
        }
    }

    @Test
    fun grayEdgesRemainInsideTheSourceRangeWithoutCubicHalosOrBroadBlur() {
        val source = raster(192, 48) { x, _ -> gray(if (x < 73) 64 else 192) }
        for (kind in listOf("still", "frames")) withMedia(source, kind) { media ->
            for (density in listOf(1f, 1.25f)) for (ratio in listOf(.7f, .4f)) for (highQuality in listOf(false, true)) {
                val actual = render(media, source, density, ratio, highQuality)
                val expected = areaReference(source, actual.width, actual.height, WHITE)
                assertTrue(actual.colors.all { (it ushr 16 and 255) in 63..193 },
                    "A non-negative resampling filter must not invent dark/light edge halos: $kind/$density/$ratio/$highQuality")
                val centerRow = actual.height / 2
                val transition = (0 until actual.width).count { (actual[it, centerRow] ushr 16 and 255) in 66..190 }
                assertTrue(transition in 1..4, "Filtering must have a bounded transition instead of applying broad blur.")
                assertTrue(actual.meanAbsoluteError(expected) <= 4.5, "The edge must retain its location and contrast.")
                if (kind == "still" && density == 1.25f && ratio == .4f && !highQuality)
                    writeComparison("gray-edge", source, actual, expected, density, ratio)
            }
        }
    }

    @Test
    fun transparentHiddenColorsCannotProduceGreenOrDarkFringesOverEitherTheme() {
        val source = raster(192, 128) { x, y ->
            if ((x - 96) * (x - 96) + (y - 64) * (y - 64) < 47 * 47 && x > y / 2) RED else 0x0000FF00
        }
        for (background in listOf(0xFFF4F4F4.toInt(), 0xFF202020.toInt())) {
            for (kind in listOf("still", "frames")) withMedia(source, kind) { media ->
                for (density in listOf(1f, 1.25f)) for (ratio in listOf(.5f, .125f)) {
                    val actual = render(media, source, density, ratio, highQuality = kind == "frames", background)
                    val expected = areaReference(source, actual.width, actual.height, background)
                    assertTrue(actual.colors.all { abs((it ushr 8 and 255) - (it and 255)) <= 1 },
                        "Hidden green RGB must not leak through premultiplied alpha interpolation.")
                    assertTrue(actual.colors.all { (it ushr 16 and 255) >= (background ushr 16 and 255) - 1 },
                        "Red over gray must not acquire an artificial black edge.")
                    assertTrue(actual.meanAbsoluteError(expected) <= 5.0,
                        "Transparent artwork must preserve area coverage over both backgrounds.")
                    if (kind == "still" && density == 1.25f && ratio == .125f)
                        writeComparison("alpha-${if (background == 0xFFF4F4F4.toInt()) "light" else "dark"}",
                            source, actual, expected, density, ratio, background)
                }
            }
        }
    }

    @Test
    fun syntheticPhotoRetainsBroadColorStructureAndOneToOnePixelsStayExact() {
        val source = syntheticPhoto()
        verifyFilteredRasters(source) { actual, expected, context ->
            assertTrue(actual.meanAbsoluteError(expected) <= 13.0,
                "Photo gradients, contours and detail must remain close to area coverage: $context")
            for (channel in listOf(16, 8, 0)) assertTrue(abs(actual.mean(channel) - expected.mean(channel)) <= 3.5,
                "Downsampling must not shift the photograph's overall color balance: $context")
            if (context == "still/default/1.25/0.25") writeComparison("synthetic-photo", source, actual, expected, 1.25f, .25f)
        }
        for (kind in listOf("still", "frames")) withMedia(source, kind) { media ->
            for (density in listOf(1f, 1.25f)) for (highQuality in listOf(false, true)) {
                val actual = render(media, source, density, 1f / density, highQuality)
                assertTrue(actual.maximumChannelError(source) <= 1,
                    "A physical 1:1 image must not receive a blur/sharpen pass: $kind/$density/$highQuality")
            }
        }
    }

    @Test
    fun actualMediaPreviewDefaultAndExplicitQualityShareTheFilteredPixels() {
        val source = raster(192, 128) { x, _ -> gray(if (x % 3 == 0) 255 else 0) }
        val path = Files.createTempFile("nnggram-image-sampling-", ".png")
        try {
            source.writePng(path)
            ComposePixelScene(180, 100) {
                CompositionLocalProvider(LocalDensity provides Density(1.25f)) {
                    Box(Modifier.fillMaxSize().background(Color.White)) {
                        MediaPreview(path.toString(), MediaFormat.PHOTO, Modifier.offset(8.dp, 8.dp).size(48.dp, 32.dp),
                            contentDescription = "default-sampling", animate = false, placeholder = {})
                        MediaPreview(path.toString(), MediaFormat.PHOTO, Modifier.offset(72.dp, 8.dp).size(48.dp, 32.dp),
                            contentDescription = "quality-sampling", animate = false, placeholder = {}, highQuality = true)
                    }
                }
            }.use { scene ->
                val deadline = System.nanoTime() + 5_000_000_000L
                while (scene.unmergedSemanticsBounds {
                    it.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { value -> value.endsWith("-sampling") }
                }.size != 2 && System.nanoTime() < deadline) { scene.settledFrame(2); Thread.sleep(5) }
                val bounds = scene.unmergedSemanticsBounds {
                    it.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { value -> value.endsWith("-sampling") }
                }.sortedBy { it.left }
                assertEquals(2, bounds.size, "Both real previews must finish decoding.")
                val frame = scene.settledFrame(2)
                val first = SampleRaster(60, 40, IntArray(60 * 40) { index -> frame[10 + index % 60, 10 + index / 60] })
                val second = SampleRaster(60, 40, IntArray(60 * 40) { index -> frame[90 + index % 60, 10 + index / 60] })
                assertTrue(first.maximumChannelError(second) <= 1,
                    "Ordinary media and the former high-quality entry must use one sampling policy.")
                assertTrue(first.meanAbsoluteError(areaReference(source, 60, 40, WHITE)) <= 24.0,
                    "The real default preview must actually downsample, rather than agree on nearest-neighbour pixels.")
            }
        } finally { Files.deleteIfExists(path) }
    }

    private fun verifyFilteredRasters(source: SampleRaster, verify: (SampleRaster, SampleRaster, String) -> Unit) {
        for (kind in listOf("still", "frames")) withMedia(source, kind) { media ->
            for (density in listOf(1f, 1.25f)) for (ratio in listOf(.5f, .25f, .125f)) {
                for (highQuality in listOf(false, true)) {
                    val actual = render(media, source, density, ratio, highQuality)
                    val expected = areaReference(source, actual.width, actual.height, WHITE)
                    verify(actual, expected, "$kind/${if (highQuality) "highQuality" else "default"}/$density/$ratio")
                }
            }
        }
    }

    private fun withMedia(source: SampleRaster, kind: String, test: (DecodedMedia) -> Unit) {
        val image = source.toImage()
        val media = if (kind == "frames") DecodedMedia.Frames(listOf(image)) else DecodedMedia.Still(image)
        media.use(test)
    }

    private fun render(media: DecodedMedia, source: SampleRaster, density: Float, ratio: Float,
        highQuality: Boolean, background: Int = WHITE): SampleRaster {
        val width = (source.width * density * ratio).roundToInt().coerceAtLeast(1)
        val height = (source.height * density * ratio).roundToInt().coerceAtLeast(1)
        media.prepareForSize(width, height)
        return Surface.makeRasterN32Premul(width, height).use { surface ->
            surface.canvas.clear(background)
            surface.canvas.scale(density, density)
            val bounds = Rect.makeWH(width / density, height / density)
            if (highQuality) media.drawHighQuality(surface.canvas, bounds, 0L) else media.draw(surface.canvas, bounds, 0L)
            surface.makeImageSnapshot().use(::readRaster)
        }
    }

    /** A separate source-pixel integral, intentionally independent of Skia sampling and mip levels. */
    private fun areaReference(source: SampleRaster, width: Int, height: Int, background: Int): SampleRaster =
        raster(width, height) { x, y ->
            val left = x.toDouble() * source.width / width
            val right = (x + 1).toDouble() * source.width / width
            val top = y.toDouble() * source.height / height
            val bottom = (y + 1).toDouble() * source.height / height
            var alpha = 0.0
            val channels = DoubleArray(3)
            for (sy in floor(top).toInt() until ceil(bottom).toInt().coerceAtMost(source.height)) {
                for (sx in floor(left).toInt() until ceil(right).toInt().coerceAtMost(source.width)) {
                    val weight = (minOf(right, sx + 1.0) - maxOf(left, sx.toDouble())) *
                        (minOf(bottom, sy + 1.0) - maxOf(top, sy.toDouble()))
                    val pixel = source[sx, sy]
                    val opacity = (pixel ushr 24 and 255) / 255.0
                    alpha += opacity * weight
                    for ((index, shift) in listOf(16, 8, 0).withIndex()) channels[index] += (pixel ushr shift and 255) * opacity * weight
                }
            }
            val area = (right - left) * (bottom - top)
            var pixel = 0xFF000000.toInt()
            for ((index, shift) in listOf(16, 8, 0).withIndex()) {
                val channel = channels[index] / area + (background ushr shift and 255) * (1.0 - alpha / area)
                pixel = pixel or (channel.roundToInt().coerceIn(0, 255) shl shift)
            }
            pixel
        }

    private fun syntheticPhoto() = raster(192, 128) { x, y ->
        val red = (35 + 130.0 * x / 191 + 28.0 * y / 127).roundToInt()
        val green = (75 + 115.0 * y / 127).roundToInt()
        val blue = (175 - 100.0 * x / 191).roundToInt()
        when {
            (x - 52) * (x - 52) + (y - 46) * (y - 46) < 24 * 24 -> 0xFFC46B47.toInt()
            x in 105..169 && y in 24..86 -> if ((x + 2 * y) % 5 == 0) 0xFF437E59.toInt() else 0xFF8CAF89.toInt()
            y in 104..106 || x in 90..91 -> 0xFF273547.toInt()
            else -> 0xFF000000.toInt() or (red shl 16) or (green shl 8) or blue
        }
    }

    private fun writeComparison(name: String, source: SampleRaster, actual: SampleRaster, expected: SampleRaster,
        density: Float, ratio: Float, background: Int = WHITE) {
        val nearest = renderLegacy(source, actual.width, actual.height, SamplingMode.DEFAULT, background)
        val cubic = renderLegacy(source, actual.width, actual.height, SamplingMode.MITCHELL, background)
        val spacing = 8
        val atlas = raster(actual.width * 4 + spacing * 5, actual.height + spacing * 2) { x, y ->
            if (y < spacing || y >= spacing + actual.height) background
            else {
                val column = (x - spacing) / (actual.width + spacing)
                val local = (x - spacing).mod(actual.width + spacing)
                if (x < spacing || column !in 0..3 || local >= actual.width) background
                else listOf(nearest, cubic, actual, expected)[column][local, y - spacing]
            }
        }
        val directory = Path.of(System.getProperty("java.io.tmpdir"), "image-sampling-renders")
        Files.createDirectories(directory)
        atlas.writePng(directory.resolve("$name-${(density * 100).roundToInt()}-${(ratio * 1000).roundToInt()}.png"))
    }

    private fun renderLegacy(source: SampleRaster, width: Int, height: Int, sampling: SamplingMode, background: Int) =
        source.toImage().use { image -> Surface.makeRasterN32Premul(width, height).use { surface ->
            surface.canvas.clear(background)
            surface.canvas.drawImageRect(image, Rect.makeWH(source.width.toFloat(), source.height.toFloat()),
                Rect.makeWH(width.toFloat(), height.toFloat()), sampling, null, true)
            surface.makeImageSnapshot().use(::readRaster)
        } }

    private fun readRaster(image: Image) = Bitmap.makeFromImage(image).use { bitmap ->
        SampleRaster(bitmap.width, bitmap.height, IntArray(bitmap.width * bitmap.height) { index ->
            bitmap.getColor(index % bitmap.width, index / bitmap.width)
        })
    }

    private fun raster(width: Int, height: Int, color: (Int, Int) -> Int) =
        SampleRaster(width, height, IntArray(width * height) { color(it % width, it / width) })

    private fun gray(value: Int) = 0xFF000000.toInt() or (value shl 16) or (value shl 8) or value

    private class SampleRaster(val width: Int, val height: Int, val colors: IntArray) {
        operator fun get(x: Int, y: Int) = colors[y * width + x]
        fun mean(channel: Int) = colors.sumOf { (it ushr channel and 255).toDouble() } / colors.size
        fun meanAbsoluteError(other: SampleRaster): Double = colors.indices.sumOf { index ->
            listOf(16, 8, 0).sumOf { shift -> abs((colors[index] ushr shift and 255) - (other.colors[index] ushr shift and 255)) }
        }.toDouble() / (colors.size * 3)
        fun maximumChannelError(other: SampleRaster): Int = colors.indices.maxOf { index ->
            listOf(24, 16, 8, 0).maxOf { shift -> abs((colors[index] ushr shift and 255) - (other.colors[index] ushr shift and 255)) }
        }
        fun toImage(): Image {
            val bytes = ByteArray(width * height * 4)
            for (index in colors.indices) for ((offset, shift) in listOf(16, 8, 0, 24).withIndex())
                bytes[index * 4 + offset] = (colors[index] ushr shift and 255).toByte()
            return Image.makeRaster(ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL), bytes, width * 4)
        }
        fun writePng(path: Path) {
            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            image.setRGB(0, 0, width, height, colors, 0, width)
            check(ImageIO.write(image, "png", path.toFile()))
        }
    }

    private companion object {
        val WHITE = 0xFFFFFFFF.toInt()
        val RED = 0xFFFF0000.toInt()
    }
}
