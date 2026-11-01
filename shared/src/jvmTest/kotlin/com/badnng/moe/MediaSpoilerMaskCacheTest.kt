package com.badnng.moe

import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class MediaSpoilerMaskCacheTest {
    @Test fun sharedDensityMasksEvictOnlyUnusedFramesAndStayInsideTheTotalPixelBudget() {
        val prepared = mutableListOf<Pair<Int, Image>>()
        val cache = MediaSpoilerMaskCache(pixelBudget = 60L * 160 * 160) { tile ->
            Surface.makeRasterN32Premul(tile * 10, tile * 6).use { surface ->
                surface.makeImageSnapshot().also { prepared += tile to it }
            }
        }
        val first = cache.acquire(128)
        val simultaneous = cache.acquire(160)
        assertEquals(listOf(128), prepared.map { it.first }, "Overlapping densities reuse a live linear-sampled mask instead of exceeding budget.")
        first.close()
        assertFalse(prepared.single().second.isClosed)
        simultaneous.close()
        val replacement = cache.acquire(160)
        assertEquals(listOf(128, 160), prepared.map { it.first })
        assertTrue(prepared.first().second.isClosed, "Evict all sixty unused density frames together.")
        cache.close()
        assertFalse(prepared.last().second.isClosed, "A live drawing lease must outlive cache shutdown.")
        replacement.close()
        replacement.close()
        assertTrue(prepared.last().second.isClosed)
    }

    @Test fun shutdownDuringWorkerGenerationClosesTheLateAtlasWithoutInstallingIt() {
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        var prepared: Image? = null
        val cache = MediaSpoilerMaskCache { tile ->
            started.countDown()
            check(finish.await(5, TimeUnit.SECONDS))
            Surface.makeRasterN32Premul(tile * 10, tile * 6).use { surface ->
                surface.makeImageSnapshot().also { prepared = it }
            }
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val worker = executor.submit<Boolean> { runCatching { cache.acquire(128) }.isFailure }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            cache.close()
            finish.countDown()
            assertTrue(worker.get(5, TimeUnit.SECONDS))
            assertTrue(assertNotNull(prepared).isClosed)
        } finally { finish.countDown(); cache.close(); executor.shutdownNow() }
    }
}
