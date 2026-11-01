package com.badnng.moe

import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import androidx.compose.ui.unit.IntSize
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*

class MediaSpoilerThumbnailTest {
    @Test fun photoBackgroundCropsBeforeBlurAndPreservesSmallNativeCropPixels() {
        withPhotoBands(80, 80, 20) { path ->
            prepareMediaPhotoBackground(path.toString(), IntSize(200, 100)).use { image ->
                assertEquals(80, image.width)
                assertEquals(40, image.height)
                Bitmap.makeFromImage(image).use { pixels ->
                    assertEquals(0xFF00FF00.toInt(), pixels.getColor(1, 1), "Discard red/blue borders before applying the blur kernel.")
                    assertEquals(0xFF00FF00.toInt(), pixels.getColor(78, 38))
                }
            }
        }
    }

    @Test fun photoBackgroundReducesOnlyLargeCropsToTheFortyPixelTarget() {
        withPhotoBands(320, 240, 40) { path ->
            prepareMediaPhotoBackground(path.toString(), IntSize(200, 100)).use { image ->
                assertEquals(40, image.width)
                assertEquals(20, image.height)
                Bitmap.makeFromImage(image).use { pixels ->
                    assertEquals(0xFF00FF00.toInt(), pixels.getColor(1, 1))
                    assertEquals(0xFF00FF00.toInt(), pixels.getColor(38, 18))
                }
            }
        }
        withPhotoBands(120, 30, 0) { path ->
            prepareMediaPhotoBackground(path.toString(), IntSize(400, 100)).use { image ->
                assertEquals(40, image.width, "Either crop dimension exceeding 100 triggers the 40px reduction.")
                assertEquals(10, image.height)
            }
        }
    }

    @Test fun anOlderViewportPreparationCannotReplaceANewerPhotoBackground() {
        fun image() = Surface.makeRasterN32Premul(4, 4).use { it.makeImageSnapshot() }
        val holder = MediaSpoilerThumbnailHolder()
        val older = holder.beginPreparation()
        val newer = holder.beginPreparation()
        val stale = image()
        val ready = image()
        assertNull(holder.install(stale, older))
        assertTrue(stale.isClosed)
        assertSame(ready, holder.install(ready, newer))
        holder.close()
        assertTrue(ready.isClosed)
    }

    @Test fun blurUsesThePinnedRadiusThreeTriangularKernelWithoutExtraBoxPasses() {
        val path = Files.createTempFile("synthetic-spoiler-impulse-", ".png")
        try {
            val source = BufferedImage(40, 40, BufferedImage.TYPE_INT_RGB)
            source.setRGB(20, 20, 0xFFFFFF)
            check(ImageIO.write(source, "png", path.toFile()))
            prepareMediaSpoilerThumbnail(path.toString()).use { image -> Bitmap.makeFromImage(image).use { pixels ->
                assertEquals(15, pixels.getColor(20, 20) and 255)
                assertEquals(11, pixels.getColor(21, 20) and 255)
                assertEquals(7, pixels.getColor(22, 20) and 255)
                assertEquals(3, pixels.getColor(23, 20) and 255)
                assertEquals(0, pixels.getColor(24, 20) and 255)
                assertEquals(pixels.getColor(21, 20), pixels.getColor(20, 21))
            } }
        } finally { Files.deleteIfExists(path) }
    }

    @Test fun actualThumbnailIsReducedToFortyPixelsAndLosesFineDetailBeforeDrawing() {
        val path = Files.createTempFile("synthetic-spoiler-source-", ".png")
        try {
            val source = BufferedImage(320, 160, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until source.height) for (x in 0 until source.width) {
                val color = if (x < 160) 0xFFCC4020.toInt() else 0xFF2084CC.toInt()
                source.setRGB(x, y, if ((x + y) % 2 == 0) color else color xor 0x00303030)
            }
            check(ImageIO.write(source, "png", path.toFile()))
            prepareMediaSpoilerThumbnail(path.toString()).use { image ->
                assertEquals(40, image.width)
                assertEquals(20, image.height)
                Bitmap.makeFromImage(image).use { pixels ->
                    val left = pixels.getColor(3, 10)
                    val right = pixels.getColor(36, 10)
                    assertTrue((left ushr 16 and 255) > (left and 255) + 70, "The real source's warm side must remain observable.")
                    assertTrue((right and 255) > (right ushr 16 and 255) + 70, "The real source's cool side must remain observable.")
                    assertTrue(kotlin.math.abs((pixels.getColor(3, 9) and 255) - (left and 255)) < 5,
                        "The 1px detail must be removed before this raster reaches UI drawing.")
                }
            }
        } finally { Files.deleteIfExists(path) }
    }

    @Test fun replacementDisposalAndLateWorkerCompletionCloseEveryOwnedRaster() {
        fun image() = Surface.makeRasterN32Premul(4, 4).use { surface ->
            surface.canvas.clear(0xFF4080C0.toInt())
            surface.makeImageSnapshot()
        }
        val first = image()
        val second = image()
        val late = image()
        val holder = MediaSpoilerThumbnailHolder()
        assertSame(first, holder.install(first))
        assertSame(second, holder.install(second))
        assertTrue(first.isClosed)
        holder.close()
        holder.close()
        assertTrue(second.isClosed)
        assertNull(holder.install(late))
        assertTrue(late.isClosed)
        Surface.makeRasterN32Premul(4, 4).use { surface ->
            surface.canvas.clear(0xFFFFFFFF.toInt())
            holder.draw(surface.canvas, Rect.makeWH(4f, 4f))
            surface.makeImageSnapshot().use { result -> Bitmap.makeFromImage(result).use { pixels ->
                assertEquals(0xFFFFFFFF.toInt(), pixels.getColor(2, 2))
            } }
        }
    }

    private fun withPhotoBands(width: Int, height: Int, border: Int, test: (java.nio.file.Path) -> Unit) {
        val path = Files.createTempFile("synthetic-photo-backdrop-", ".png")
        try {
            val source = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until height) for (x in 0 until width) source.setRGB(x, y, when {
                y < border -> 0xFF0000
                y >= height - border -> 0x0000FF
                else -> 0x00FF00
            })
            check(ImageIO.write(source, "png", path.toFile()))
            test(path)
        } finally { Files.deleteIfExists(path) }
    }
}
