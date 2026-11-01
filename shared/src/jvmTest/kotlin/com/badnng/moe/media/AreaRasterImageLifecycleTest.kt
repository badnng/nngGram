package com.badnng.moe.media

import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.test.*

class AreaRasterImageLifecycleTest {
    @Test
    fun derivedImagesStayInsideTheTotalBudgetAndEvictionAndDisposalReleaseNativeImages() {
        val source = image(32)
        val raster = AreaRasterImage(source, pixelBudget = 80)
        try {
            raster.prepare(8, 8)
            val first = cache(raster).values.single()
            assertFalse(first.isClosed)
            raster.prepare(6, 6)
            raster.prepare(4, 4)
            val remaining = cache(raster).values.toList()
            assertTrue(first.isClosed, "An evicted target must release its native pixels immediately.")
            assertTrue(remaining.sumOf { it.width.toLong() * it.height } <= 80)
            assertFalse(source.isClosed, "Eviction must retain the original for full-size viewing.")
            raster.close()
            raster.close()
            assertTrue(source.isClosed)
            assertTrue(remaining.all { it.isClosed }, "Disposal must close all retained target dimensions.")
        } finally { raster.close() }
    }

    @Test
    fun aPreparationWaitingForItsWorkerSlotDoesNotKeepADisposedSourceAliveOrReviveIt() {
        val slots = Class.forName("com.badnng.moe.media.MediaImageSamplingKt")
            .getDeclaredField("areaSlots").apply { isAccessible = true }.get(null) as Semaphore
        assertTrue(slots.tryAcquire(2, 5, TimeUnit.SECONDS))
        val source = image(32)
        val raster = AreaRasterImage(source)
        val started = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor { work -> Thread(work).apply { isDaemon = true } }
        var holdsSlots = true
        try {
            val waiting = executor.submit {
                started.countDown()
                raster.prepare(8, 8)
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            raster.close()
            assertTrue(source.isClosed, "A queued preparation has not acquired a source-reading lease.")
            slots.release(2)
            holdsSlots = false
            waiting.get(5, TimeUnit.SECONDS)
            assertTrue(cache(raster).isEmpty(), "Late preparation must not install pixels after disposal.")
            Surface.makeRasterN32Premul(8, 8).use { surface ->
                surface.canvas.clear(0xFFFFFFFF.toInt())
                raster.draw(surface.canvas, Rect.makeWH(8f, 8f))
                surface.makeImageSnapshot().use { result -> Bitmap.makeFromImage(result).use { pixels ->
                    assertEquals(0xFFFFFFFF.toInt(), pixels.getColor(4, 4), "A closed source cannot draw again.")
                } }
            }
        } finally {
            // Once released above, the background call is complete and owns no permit.
            if (holdsSlots) slots.release(2)
            raster.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun simultaneousAvatarSizesRemainCachedWithoutRepeatedBackgroundPreparation() {
        val source = image(160)
        val raster = AreaRasterImage(source)
        val sizes = listOf(33, 44, 48, 56, 64, 72)
        try {
            sizes.forEach { raster.prepare(it, it) }
            assertEquals(sizes.size, cache(raster).size)
            repeat(3) {
                sizes.forEach { edge ->
                    Surface.makeRasterN32Premul(edge, edge).use { surface ->
                        raster.draw(surface.canvas, Rect.makeWH(edge.toFloat(), edge.toFloat()))
                    }
                }
            }
            assertEquals(sizes.map { it to it }.toSet(), cache(raster).keys)
            val queued = AreaRasterImage::class.java.getDeclaredField("queued").apply { isAccessible = true }.get(raster)
            assertNull(queued, "Stable mounted avatar sizes must not continually invalidate each other's target rasters.")
        } finally { raster.close() }
    }

    @Test
    fun anUnavailableImmutableSourceIsNotReadAgainForOtherMountedSizes() {
        val source = image(32)
        var attempts = 0
        val raster = AreaRasterImage(source, readSourcePixels = { _, _ ->
            attempts++
            error("Synthetic source pixels unavailable")
        })
        try {
            assertFailsWith<Exception> { raster.prepare(8, 8) }
            listOf(12, 16, 8, 12).forEach { edge -> raster.prepare(edge, edge) }
            repeat(3) {
                for (edge in listOf(8, 12)) Surface.makeRasterN32Premul(edge, edge).use { surface ->
                    raster.draw(surface.canvas, Rect.makeWH(edge.toFloat(), edge.toFloat()))
                    surface.makeImageSnapshot().use { result -> Bitmap.makeFromImage(result).use { pixels ->
                        assertEquals(0xFF5A9BC2.toInt(), pixels.getColor(edge / 2, edge / 2),
                            "Failed area preparation must retain a drawable linear source fallback.")
                    } }
                }
            }
            assertEquals(1, attempts, "Other mounted sizes must not retry the same immutable-source failure.")
            assertTrue(cache(raster).isEmpty())
            val queued = AreaRasterImage::class.java.getDeclaredField("queued").apply { isAccessible = true }.get(raster)
            assertNull(queued, "Two failed target sizes must not alternate background work while idle.")
        } finally { raster.close() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun cache(raster: AreaRasterImage): Map<Pair<Int, Int>, Image> =
        AreaRasterImage::class.java.getDeclaredField("targets").apply { isAccessible = true }.get(raster) as Map<Pair<Int, Int>, Image>

    private fun image(edge: Int): Image = Surface.makeRasterN32Premul(edge, edge).use { surface ->
        surface.canvas.clear(0xFF5A9BC2.toInt())
        surface.makeImageSnapshot()
    }
}
