package com.badnng.moe

import androidx.compose.ui.unit.IntSize
import com.badnng.moe.chat.MediaMiniThumbnail
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*

/** Only synthetic, encoded inline bytes; no account file or native download is used. */
class MediaMiniThumbnailWorkerTest {
    @Test
    fun inlineBytesAreCroppedBeforeBlurWithoutCreatingALocalThumbnailFile() {
        val encoded = png(80, 80) { _, y -> when {
            y < 20 -> RED
            y >= 60 -> BLUE
            else -> GREEN
        } }
        val mini = MediaMiniThumbnail(80, 80, encoded)
        val original = encoded.copyOf()
        val directory = Files.createTempDirectory("inline-mini-worker-")
        try {
            val absent = directory.resolve("native-thumbnail-not-downloaded.png")
            prepareMediaPhotoBackground(absent.toString(), mini, IntSize(200, 100)).use { image ->
                assertEquals(80, image.width)
                assertEquals(40, image.height)
                Bitmap.makeFromImage(image).use { pixels ->
                    assertEquals(GREEN, pixels.getColor(1, 1))
                    assertEquals(GREEN, pixels.getColor(78, 38))
                }
            }
            assertFalse(Files.exists(absent), "Inline preparation cannot invent a local media file.")
            Files.list(directory).use { assertEquals(0L, it.count()) }
            assertContentEquals(original, encoded)
            assertContentEquals(original, mini.encodedCopy())
        } finally { Files.deleteIfExists(directory) }
    }

    @Test
    fun actualInlinePixelsUseTheSameRadiusThreeTriangularBlur() {
        val mini = MediaMiniThumbnail(40, 40, png(40, 40) { x, y ->
            if (x == 20 && y == 20) WHITE else BLACK
        })
        prepareMediaPhotoBackground(mini, IntSize(400, 400)).use { image ->
            assertEquals(40, image.width)
            assertEquals(40, image.height)
            Bitmap.makeFromImage(image).use { pixels ->
                assertEquals(15, pixels.getColor(20, 20) and 255)
                assertEquals(11, pixels.getColor(21, 20) and 255)
                assertEquals(7, pixels.getColor(22, 20) and 255)
                assertEquals(3, pixels.getColor(23, 20) and 255)
                assertEquals(0, pixels.getColor(24, 20) and 255)
                assertEquals(pixels.getColor(21, 20), pixels.getColor(20, 21))
            }
        }
    }

    @Test
    fun downsamplingInlineAlphaDoesNotLeakTransparentRgbIntoTheBlur() {
        val mini = MediaMiniThumbnail(240, 120, png(240, 120) { x, _ ->
            if (x < 120) RED else 0x0000FF00
        })
        prepareMediaPhotoBackground(mini, IntSize(400, 200)).use { image ->
            assertEquals(40, image.width)
            assertEquals(20, image.height)
            Bitmap.makeFromImage(image).use { pixels ->
                assertEquals(RED, pixels.getColor(4, 10))
                val edge = pixels.getColor(20, 10)
                assertTrue(edge ushr 24 in 1..254, "The actual premultiplied boundary remains translucent.")
                assertEquals(0, edge ushr 8 and 255, "Hidden green RGB cannot contaminate the visible red edge.")
                assertEquals(0, edge and 255)
                assertTrue((edge ushr 16 and 255) >= 250)
                assertEquals(0, pixels.getColor(35, 10) ushr 24)
            }
        }
    }

    @Test
    fun actualCodecDimensionsRejectOversizedCompressedImagesBeforeRasterDecode() {
        val dimensionOverflow = MediaMiniThumbnail(40, 1, png(256, 1) { _, _ -> GREEN })
        assertFailsWith<IllegalArgumentException> {
            prepareMediaPhotoBackground(dimensionOverflow, IntSize(400, 100)).close()
        }
        val compressed = ByteArrayOutputStream().use { output ->
            val image = BufferedImage(4096, 4096, BufferedImage.TYPE_BYTE_BINARY)
            check(ImageIO.write(image, "png", output))
            output.toByteArray()
        }
        assertTrue(compressed.size <= MediaMiniThumbnail.MAX_ENCODED_BYTES)
        val compressedBomb = MediaMiniThumbnail(40, 40, compressed)
        assertFailsWith<IllegalArgumentException> {
            prepareMediaPhotoBackground(compressedBomb, IntSize(400, 400)).close()
        }
        // A rejected decode must release the shared worker slot for the next real image.
        prepareMediaPhotoBackground(solidMini(GREEN), IntSize(400, 400)).use {
            assertEquals(GREEN, color(it))
        }
    }

    @Test
    fun malformedBytesMissingSourcesAndInvalidViewportsFailWithoutFabricatingPixels() {
        val malformed = MediaMiniThumbnail(40, 40, byteArrayOf(1, 3, 5, 7))
        assertFails { prepareMediaPhotoBackground(malformed, IntSize(400, 400)).close() }
        assertFailsWith<IllegalArgumentException> {
            prepareMediaPhotoBackground(solidMini(GREEN), IntSize.Zero).close()
        }
        assertFailsWith<IllegalArgumentException> {
            prepareMediaPhotoBackground(null, null, IntSize(400, 400)).close()
        }
        val directory = Files.createTempDirectory("inline-mini-missing-")
        try {
            assertFails {
                prepareMediaPhotoBackground(directory.resolve("absent.png").toString(), null, IntSize(400, 400)).close()
            }
            Files.list(directory).use { assertEquals(0L, it.count()) }
        } finally { Files.deleteIfExists(directory) }
    }

    @Test
    fun aReadyLocalThumbnailWinsAndInvalidLocalBytesFallBackToTheRealInlineImage() {
        val path = Files.createTempFile("inline-mini-local-", ".png")
        try {
            val mini = solidMini(BLUE)
            Files.write(path, png(80, 80) { _, _ -> RED })
            prepareMediaPhotoBackground(path.toString(), mini, IntSize(400, 400)).use {
                assertEquals(RED, color(it), "Ready local bytes take precedence over the minithumbnail.")
            }
            Files.write(path, byteArrayOf(1, 3, 5, 7))
            prepareMediaPhotoBackground(path.toString(), mini, IntSize(400, 400)).use {
                assertEquals(BLUE, color(it), "A broken local source keeps the actual inline fallback.")
            }
            assertFails { prepareMediaPhotoBackground(path.toString(), null, IntSize(400, 400)).close() }
        } finally { Files.deleteIfExists(path) }
    }

    @Test
    fun readyInlinePixelsSurvivePendingLocalReplacementAndResizeUntilANewerRasterInstalls() {
        val holder = MediaSpoilerThumbnailHolder()
        val initialRequest = holder.beginPreparation()
        val ready = prepareMediaPhotoBackground(solidMini(BLUE), IntSize(400, 400))
        assertSame(ready, holder.install(ready, initialRequest))
        val localRequest = holder.beginPreparation()
        assertFalse(ready.isClosed)
        assertEquals(BLUE, color(holder), "A pending local source cannot blank ready inline pixels.")
        val resizedRequest = holder.beginPreparation()
        val obsoleteLocal = prepareMediaPhotoBackground(solidMini(RED), IntSize(200, 100))
        assertNull(holder.install(obsoleteLocal, localRequest))
        assertTrue(obsoleteLocal.isClosed)
        assertFalse(ready.isClosed)
        assertEquals(BLUE, color(holder), "The stale viewport cannot overwrite current ready pixels.")
        val resized = prepareMediaPhotoBackground(solidMini(GREEN), IntSize(200, 100))
        assertSame(resized, holder.install(resized, resizedRequest))
        assertTrue(ready.isClosed)
        assertEquals(GREEN, color(holder))
        holder.close()
        assertTrue(resized.isClosed)
    }

    @Test
    fun cancellationAndDisposalRejectLateWorkersWithoutLosingTheReadyRaster() {
        val holder = MediaSpoilerThumbnailHolder()
        val ready = prepareMediaPhotoBackground(solidMini(BLUE), IntSize(400, 400))
        holder.install(ready, holder.beginPreparation())
        val cancelled = holder.beginPreparation()
        holder.invalidatePreparation(cancelled)
        val late = prepareMediaPhotoBackground(solidMini(RED), IntSize(400, 400))
        assertNull(holder.install(late, cancelled))
        assertTrue(late.isClosed)
        assertFalse(ready.isClosed)
        assertEquals(BLUE, color(holder))
        val active = holder.beginPreparation()
        holder.invalidatePreparation(cancelled)
        val replacement = prepareMediaPhotoBackground(solidMini(GREEN), IntSize(400, 400))
        assertSame(replacement, holder.install(replacement, active), "An old cancellation cannot revoke the current request.")
        assertTrue(ready.isClosed)
        val disposedRequest = holder.beginPreparation()
        holder.close()
        holder.close()
        assertTrue(replacement.isClosed)
        val disposedLate = prepareMediaPhotoBackground(solidMini(RED), IntSize(400, 400))
        assertNull(holder.install(disposedLate, disposedRequest))
        assertTrue(disposedLate.isClosed)
        assertEquals(WHITE, color(holder), "A disposed owner never draws or revives a raster.")
    }

    private fun solidMini(color: Int) = MediaMiniThumbnail(40, 40, png(40, 40) { _, _ -> color })

    private fun color(image: Image): Int = Bitmap.makeFromImage(image).use {
        it.getColor(image.width / 2, image.height / 2)
    }

    private fun color(holder: MediaSpoilerThumbnailHolder): Int = Surface.makeRasterN32Premul(8, 8).use { surface ->
        surface.canvas.clear(WHITE)
        holder.drawCropped(surface.canvas, 8f, 8f)
        surface.makeImageSnapshot().use(::color)
    }

    private fun png(width: Int, height: Int, color: (Int, Int) -> Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, color(x, y))
        return ByteArrayOutputStream().use { output ->
            check(ImageIO.write(image, "png", output))
            output.toByteArray()
        }
    }

    private companion object {
        const val RED = 0xFFFF0000.toInt()
        const val GREEN = 0xFF00FF00.toInt()
        const val BLUE = 0xFF0000FF.toInt()
        const val BLACK = 0xFF000000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
    }
}
