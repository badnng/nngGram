package com.badnng.moe.media

import com.badnng.moe.chat.*
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class ProgressiveVideoSourceTest {
    @Test fun concurrentHttpConnectionsHaveIndependentOwnersAndFinishingOneLeavesTheOtherAlive() {
        val next = AtomicInteger()
        val pending = ConcurrentHashMap<Int, (ChatResult<ByteArray>) -> Unit>()
        val offsets = ConcurrentHashMap<Int, Long>()
        val released = CopyOnWriteArrayList<Int>()
        val factory = MediaRangeReader(read = { _, _, _ -> error("Factory cannot read") }, fork = {
            val id = next.incrementAndGet()
            MediaRangeReader(read = { offset, _, completed -> offsets[id] = offset; pending[id] = completed }, release = { released += id })
        })
        ProgressiveVideoSource(1000, factory).use { source ->
            val first = CompletableFuture.supplyAsync { connection(source.url, "bytes=0-3").inputStream.use { it.readBytes() } }
            await { pending.size == 1 }
            val second = CompletableFuture.supplyAsync { connection(source.url, "bytes=700-703").inputStream.use { it.readBytes() } }
            await { pending.size == 2 }
            assertEquals(setOf(0L, 700L), offsets.values.toSet())
            val secondId = offsets.entries.single { it.value == 700L }.key
            pending.getValue(secondId)(ChatResult(ByteArray(4) { 2 }))
            assertContentEquals(ByteArray(4) { 2 }, second.get(2, TimeUnit.SECONDS))
            await { secondId in released }
            assertEquals(listOf(secondId), released.toList())
            val firstId = offsets.entries.single { it.value == 0L }.key
            pending.getValue(firstId)(ChatResult(ByteArray(4) { 1 }))
            assertContentEquals(ByteArray(4) { 1 }, first.get(2, TimeUnit.SECONDS))
            await { released.size == 2 }
        }
    }

    @Test fun headAndRetiredEpochRoutesCannotOpenAChildLease() {
        val opens = AtomicInteger()
        val factory = MediaRangeReader(read = { _, _, _ -> error("Factory cannot read") }, fork = {
            opens.incrementAndGet()
            MediaRangeReader(read = { _, count, completed -> completed(ChatResult(ByteArray(count))) })
        })
        ProgressiveVideoSource(100, factory).use { source ->
            val old = source.url
            connection(old).apply { requestMethod = "HEAD" }.let { assertEquals(200, it.responseCode); it.disconnect() }
            assertEquals(0, opens.get())
            source.cancelReaders()
            assertEquals(404, connection(old, "bytes=0-3").responseCode)
            assertEquals(0, opens.get())
            assertEquals(4, connection(source.url, "bytes=0-3").inputStream.use { it.readBytes() }.size)
            assertEquals(1, opens.get())
        }
    }

    @Test fun parentCloseAndChildCreationShareOneLifecycleBarrier() {
        val creating = CountDownLatch(1)
        val continueCreation = CountDownLatch(1)
        val opens = AtomicInteger()
        val releases = AtomicInteger()
        val reads = AtomicInteger()
        val factory = MediaRangeReader(read = { _, _, _ -> error("Factory cannot read") }, fork = {
            opens.incrementAndGet()
            creating.countDown()
            check(continueCreation.await(3, TimeUnit.SECONDS))
            MediaRangeReader(read = { _, _, _ -> reads.incrementAndGet() }, release = { releases.incrementAndGet() })
        })
        val source = ProgressiveVideoSource(100, factory)
        val url = source.url
        val request = CompletableFuture.supplyAsync { runCatching { connection(url, "bytes=0-3").inputStream.use { it.readBytes() } } }
        assertTrue(creating.await(2, TimeUnit.SECONDS))
        val close = CompletableFuture.runAsync { source.close() }
        await { !source.isOpen }
        continueCreation.countDown()
        close.get(2, TimeUnit.SECONDS)
        request.get(2, TimeUnit.SECONDS)
        assertEquals(1, opens.get())
        assertEquals(1, releases.get())
        assertEquals(0, reads.get())
    }

    @Test fun completedMetadataReleasesOnlyItsProbeConnection() {
        val callbacks = ConcurrentHashMap<Boolean, (ChatResult<ByteArray>) -> Unit>()
        val released = CopyOnWriteArrayList<Boolean>()
        val factory = MediaRangeReader(read = { _, _, _ -> error("Factory cannot read") }, fork = { probe ->
            MediaRangeReader(read = { _, _, completed -> callbacks[probe] = completed }, release = { released += probe })
        })
        ProgressiveVideoSource(100, factory).use { source ->
            val video = CompletableFuture.supplyAsync { connection(source.url, "bytes=0-3").inputStream.use { it.readBytes() } }
            val probe = CompletableFuture.supplyAsync { runCatching { connection(source.probeUrl, "bytes=50-53").inputStream.use { it.readBytes() } } }
            await { callbacks.size == 2 }
            source.releaseProbe()
            probe.get(2, TimeUnit.SECONDS)
            assertEquals(listOf(true), released.toList())
            callbacks.getValue(false)(ChatResult(ByteArray(4)))
            assertEquals(4, video.get(2, TimeUnit.SECONDS).size)
            await { released.size == 2 }
        }
    }

    @Test fun unknownNativeSizeStreamsPastExpectedMetadataUntilNativeEof() {
        val total = 9 * 1024 * 1024 + 17
        var reads = 0
        ProgressiveVideoSource(0) { offset, count, result ->
            reads++
            val length = minOf(count.toLong(), total - offset).coerceAtLeast(0).toInt()
            result(ChatResult(ByteArray(length) { ((offset + it) % 251).toByte() }))
        }.use { source ->
            val request = connection(source.url)
            assertEquals(200, request.responseCode)
            assertNull(request.getHeaderField("Content-Length"))
            val bytes = request.inputStream.use { it.readBytes() }
            assertEquals(total, bytes.size)
            assertEquals(((total - 1) % 251).toByte(), bytes.last())
            assertTrue(reads > 128)
        }
    }

    @Test fun metadataAndPlaybackUseSeparateLeasesAndCloseReleasesThem() {
        val playback = AtomicInteger()
        val probe = AtomicInteger()
        val releases = AtomicInteger()
        val reader = MediaRangeReader(
            read = { _, count, result -> playback.incrementAndGet(); result(ChatResult(ByteArray(count) { 1 })) },
            probe = { _, count, result -> probe.incrementAndGet(); result(ChatResult(ByteArray(count) { 2 })) },
            release = { releases.incrementAndGet() },
        )
        val source = ProgressiveVideoSource(100, reader)
        assertContentEquals(ByteArray(4) { 2 }, connection(source.probeUrl, "bytes=0-3").inputStream.use { it.readBytes() })
        assertContentEquals(ByteArray(4) { 1 }, connection(source.url, "bytes=0-3").inputStream.use { it.readBytes() })
        assertEquals(1, probe.get())
        assertEquals(1, playback.get())
        source.close()
        source.close()
        assertEquals(1, releases.get())
    }

    @Test fun unknownSizeCanAcquireRealRangeSemanticsAfterANativeSizeUpdate() {
        var nativeSize = 0L
        val reader = MediaRangeReader({ _, count, result -> result(ChatResult(ByteArray(count))) })
        ProgressiveVideoSource(0, reader) { nativeSize }.use { source ->
            nativeSize = 1000
            val request = connection(source.url, "bytes=900-")
            assertEquals(206, request.responseCode)
            assertEquals("bytes 900-999/1000", request.getHeaderField("Content-Range"))
            assertEquals(100, request.inputStream.use { it.readBytes() }.size)
        }
    }

    @Test fun progressingMetadataCanRunBeyondThirtySecondsWhileAnIdleGapStillExpires() {
        val deadline = MediaProgressDeadline(0, 15_000)
        for (time in 1_000L..60_000L step 1_000) assertFalse(deadline.expired(time, time - 500, true))
        assertTrue(deadline.expired(60_001, 44_000, true))
        assertTrue(deadline.expired(1, 1, false))
    }

    @Test fun cancellingUnavailableReadersClosesTheirRequestsPromptly() {
        val attempts = AtomicInteger()
        ProgressiveVideoSource(100) { _, _, result ->
            attempts.incrementAndGet()
            result(ChatResult(error = ChatError.NOT_READY))
        }.use { source ->
            val read = CompletableFuture.supplyAsync {
                runCatching { connection(source.url, "bytes=0-9").inputStream.use { it.readNBytes(10) } }
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (!source.buffering.value && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(source.buffering.value)
            source.cancelReaders()
            assertTrue(read.get(1, TimeUnit.SECONDS).let { it.isFailure || it.getOrNull()?.isEmpty() == true })
            assertTrue(attempts.get() > 0)
        }
    }

    @Test fun rangeRequestsReturnOnlyRequestedBytesAndHeadNeverReadsContent() {
        val offsets = mutableListOf<Long>()
        ProgressiveVideoSource(1000) { offset, count, result ->
            synchronized(offsets) { offsets.add(offset) }
            result(ChatResult(ByteArray(count) { ((offset + it) % 256).toByte() }))
        }.use { source ->
            val request = connection(source.url, "bytes=700-799")
            assertEquals(206, request.responseCode)
            assertEquals("bytes 700-799/1000", request.getHeaderField("Content-Range"))
            assertContentEquals(ByteArray(100) { ((700 + it) % 256).toByte() }, request.inputStream.use { it.readBytes() })
            assertEquals(listOf(700L), offsets)
            connection(source.url).apply { requestMethod = "HEAD" }.let { assertEquals(200, it.responseCode); it.disconnect() }
            assertEquals(1, offsets.size)
        }
    }

    @Test fun unavailableChunksBufferAndRetryWithoutReturningZeroFilledBytes() {
        val attempts = AtomicInteger()
        ProgressiveVideoSource(100) { _, count, result ->
            result(if (attempts.incrementAndGet() < 3) ChatResult(error = ChatError.NOT_READY) else ChatResult(ByteArray(count) { 42 }))
        }.use { source ->
            val bytes = connection(source.url, "bytes=0-9").inputStream.use { it.readBytes() }
            assertContentEquals(ByteArray(10) { 42 }, bytes)
            assertEquals(3, attempts.get())
            assertFalse(source.buffering.value)
        }
    }

    @Test fun invalidRangesAndUnknownTokensNeverReadAccountData() {
        val attempts = AtomicInteger()
        ProgressiveVideoSource(100) { _, _, _ -> attempts.incrementAndGet() }.use { source ->
            assertEquals(416, connection(source.url, "bytes=100-200").responseCode)
            assertEquals(416, connection(source.url, "bytes=0-2,5-7").responseCode)
            assertEquals(404, connection(source.url.substringBeforeLast('/') + "/wrong").responseCode)
            assertEquals(0, attempts.get())
        }
        assertEquals(90L..99L, ProgressiveVideoSource.parseRange("bytes=-10", 100))
        assertNull(ProgressiveVideoSource.parseRange("bytes=9223372036854775808-", 100))
    }

    private fun connection(url: String, range: String? = null): HttpURLConnection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
        connectTimeout = 3000; readTimeout = 5000
        range?.let { setRequestProperty("Range", it) }
    }
    private fun await(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!predicate() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(predicate(), "Synthetic range lifecycle did not reach its expected state")
    }
}
