package com.badnng.moe.media

import androidx.compose.ui.graphics.ImageBitmap
import com.badnng.moe.chat.MediaFile
import com.badnng.moe.chat.MediaFormat
import com.badnng.moe.chat.MessageMedia
import com.badnng.moe.chat.StickerItem
import com.badnng.moe.settings.SettingsBackgroundEmoji
import com.badnng.moe.tdlib.ProfileEmojiEncoded
import com.badnng.moe.tdlib.ProfileEmojiFileRole
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Surface
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import java.util.zip.GZIPOutputStream
import kotlin.math.abs
import kotlin.test.*

/** Draft tests: only synthetic in-memory assets and a pure process-command adapter. */
class ProfileEmojiArtworkDecoderTest {
    @Test fun staticArtworkPreservesColorAndAlphaInsideAnAspectFitCanvas() {
        val decoder = decoderWithoutProcesses()
        decoder.decode(encoded(png(8, 4, 255, 64, 16, 128)), request(16), false) { true }.use { frame ->
            val image = assertNotNull(frame.image)
            assertEquals(16, image.width)
            assertEquals(16, image.height)
            assertFalse(frame.isAlphaMask)
            assertFalse(frame.animated)
            assertEquals(0L, frame.durationMillis)
            assertEquals(0L, frame.frameDurationMillis)
            assertEquals(0, image.pixel(8, 0), "Letterboxing remains transparent.")
            assertArgbNear(0x80ff4010.toInt(), image.pixel(8, 8))
        }
    }

    @Test fun maskPreparationUsesTheActualArtworkAlphaAndDiscardsItsRgb() {
        decoderWithoutProcesses().decode(encoded(png(8, 4, 255, 64, 16, 128)), request(16), true) { true }.use { frame ->
            assertTrue(frame.isAlphaMask)
            val image = assertNotNull(frame.image)
            assertEquals(0, image.pixel(8, 0))
            assertArgbNear(0x80ffffff.toInt(), image.pixel(8, 8))
        }
    }

    @Test fun alphaMaskBytesRemainPremultipliedIncludingTransparentAndFractionalEdges() {
        val bytes = byteArrayOf(0, 0, 0, 0, 30, 20, 10, 64, 7, 8, 9, 0xff.toByte())
        profileEmojiAlphaMask(bytes)
        assertContentEquals(byteArrayOf(0, 0, 0, 0, 64, 64, 64, 64,
            0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte()), bytes)
        assertFailsWith<IllegalArgumentException> { profileEmojiAlphaMask(ByteArray(3)) }
    }

    @Test fun nativeVideoPixelsPremultiplyBeforeNonIntegralAreaReduction() {
        // Transparent blue must contribute coverage/alpha, but none of its hidden RGB.
        val rgba = ByteArray(3 * 3 * 4) { index ->
            when ((index / 4) % 3) {
                0 -> when (index % 4) { 0, 3 -> 255; else -> 0 }
                1 -> when (index % 4) { 2 -> 255; else -> 0 }
                else -> when (index % 4) { 1, 3 -> 255; else -> 0 }
            }.toByte()
        }
        val png = profileEmojiVideoFramePng(rgba, 3, 3, 2) { true }
        decoderWithoutProcesses().decode(encoded(png), request(2), false) { true }.use { frame ->
            val image = assertNotNull(frame.image)
            for (y in 0..1) {
                assertArgbNear(0xaaff0000.toInt(), image.pixel(0, y))
                assertArgbNear(0xaa00ff00.toInt(), image.pixel(1, y))
            }
        }
    }

    @Test fun nativeVideoPixelPreparationKeepsAspectFitAndRejectsInvalidOrRetiredWork() {
        val rgba = ByteArray(4 * 2 * 4) { if (it % 4 == 0 || it % 4 == 3) 0xff.toByte() else 0 }
        val png = profileEmojiVideoFramePng(rgba, 4, 2, 4) { true }
        decoderWithoutProcesses().decode(encoded(png), request(4), false) { true }.use { frame ->
            val image = assertNotNull(frame.image)
            assertEquals(0, image.pixel(2, 0))
            assertEquals(0, image.pixel(2, 3))
            assertArgbNear(0xffff0000.toInt(), image.pixel(2, 1))
        }
        assertFailsWith<IllegalArgumentException> { profileEmojiVideoFramePng(ByteArray(4), 2049, 1, 4) { true } }
        assertFailsWith<IllegalArgumentException> { profileEmojiVideoFramePng(ByteArray(3), 1, 1, 4) { true } }
        assertFailsWith<IllegalArgumentException> { profileEmojiVideoFramePng(ByteArray(4), 1, 1, 513) { true } }
        assertFailsWith<InterruptedException> { profileEmojiVideoFramePng(ByteArray(4), 1, 1, 4) { false } }
    }

    @Test fun theRequestedThumbnailFormatControlsDecodeIndependentlyOfTheFullAnimation() {
        val bytes = png(2, 2, 24, 200, 80, 255)
        val input = encoded(bytes, fullFormat = MediaFormat.WEBM, role = ProfileEmojiFileRole.THUMBNAIL,
            thumbnailFormat = MediaFormat.PHOTO)
        decoderWithoutProcesses().decode(input, request(8), false) { true }.use { frame ->
            assertFalse(frame.animated)
            assertArgbNear(0xff18c850.toInt(), assertNotNull(frame.image).pixel(4, 4))
        }
    }

    @Test fun syntheticTgsRendersActualPixelsWithAnimationTimingWithoutOpeningAProcess() {
        val input = encoded(gzip(lottie()), fullFormat = MediaFormat.TGS)
        decoderWithoutProcesses().decode(input, request(16, 1234), false) { true }.use { frame ->
            assertTrue(frame.animated)
            assertEquals(1000L, frame.durationMillis)
            assertEquals(33L, frame.frameDurationMillis)
            assertArgbNear(0xff00ff00.toInt(), assertNotNull(frame.image).pixel(8, 8))
        }
    }

    @Test fun malformedStaticAndTgsInputsFailWithoutAProcessOrPublishedFrame() {
        val decoder = decoderWithoutProcesses()
        assertFails { decoder.decode(encoded(byteArrayOf(1, 2, 3)), request(16), false) { true } }
        assertFails { decoder.decode(encoded(byteArrayOf(1, 2, 3), MediaFormat.TGS), request(16), false) { true } }
        assertFails { decoder.decode(encoded(gzip("not json"), MediaFormat.TGS), request(16), false) { true } }
        assertFails { decoder.decode(encoded(png(1, 1, 0, 0, 0, 255), MediaFormat.FILE), request(16), false) { true } }
    }

    @Test fun rasterDimensionAndSourcePixelBudgetsRejectHeadersBeforeLargeAllocation() {
        val decoder = decoderWithoutProcesses()
        listOf(8193 to 1, 6000 to 5000).forEach { (width, height) ->
            val input = encoded(pngHeaderDimensions(png(1, 1, 0, 0, 0, 255), width, height))
            assertFailsWith<IllegalArgumentException>("Reject $width x $height before allocating its pixels") {
                decoder.decode(input, request(16), false) { true }
            }
        }
    }

    @Test fun inflatedTgsAndVectorGeometryHaveIndependentBounds() {
        val decoder = decoderWithoutProcesses()
        val inflated = gzip(" ".repeat(2 * 1024 * 1024 + 1))
        assertFailsWith<IllegalArgumentException> {
            decoder.decode(encoded(inflated, MediaFormat.TGS), request(16), false) { true }
        }
        listOf(lottie(width = 2049), lottie(fps = 121), lottie(frames = 1860)).forEach { json ->
            assertFailsWith<IllegalArgumentException> {
                decoder.decode(encoded(gzip(json), MediaFormat.TGS), request(16), false) { true }
            }
        }
    }

    @Test fun invalidPhysicalSizesAndNegativeTimesFailBeforeAnyDecode() {
        val decoder = decoderWithoutProcesses()
        val input = encoded(byteArrayOf(1))
        listOf(request(0), request(513), request(16, -1)).forEach { invalid ->
            assertFailsWith<IllegalArgumentException> { decoder.decode(input, invalid, false) { true } }
        }
    }

    @Test fun inactiveAndMidDecodeCancellationNeverReturnPreparedArtwork() {
        val decoder = decoderWithoutProcesses()
        val input = encoded(png(4, 4, 200, 100, 50, 255))
        assertFailsWith<InterruptedException> { decoder.decode(input, request(16), false) { false } }
        var checks = 0
        assertFailsWith<InterruptedException> {
            decoder.decode(input, request(16), false) { ++checks < 3 }
        }
        assertEquals(3, checks)
    }

    @Test fun workerInputKeepsOneOwnedEncodedBufferAcrossRepeatedReadOnlyBorrows() {
        val input = encoded(byteArrayOf(7, 3, 9, 2))
        val first = input.borrowBytes()
        assertEquals(4, input.size)
        assertContentEquals(byteArrayOf(7, 3, 9, 2), first)
        assertSame(first, input.borrowBytes(), "Worker reads must not allocate a second encoded buffer per frame.")
    }

    @Test fun preparedFrameOwnsItsBitmapAndCloseIsIdempotent() {
        val bitmap = Surface.makeRasterN32Premul(3, 2).use { surface ->
            surface.canvas.clear(0xff40a0d0.toInt())
            surface.makeImageSnapshot().use { Bitmap.makeFromImage(it) }
        }
        val frame = PreparedProfileEmojiFrame(bitmap, false, false, 0, 0)
        assertEquals(6L, frame.pixels)
        assertNotNull(frame.image)
        assertFalse(bitmap.isClosed)
        frame.close()
        frame.close()
        assertTrue(bitmap.isClosed)
        assertNull(frame.image, "Closing must immediately revoke public access to native pixels.")
    }

    @Test fun webmExtractionCommandIsOneSilentLocalFrameAndUsesLocaleIndependentTime() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val executable = Path.of("synthetic tools", "ffmpeg.exe")
            val input = Path.of("synthetic data", "emoji.webm")
            val command = profileEmojiSilentFrameCommand(executable, input, 96, 1234)
            assertEquals(executable.toString(), command.first())
            assertEquals(input.toString(), command[command.indexOf("-i") + 1])
            assertEquals("1.234", command[command.indexOf("-ss") + 1])
            assertEquals("file,pipe", command[command.indexOf("-protocol_whitelist") + 1])
            assertTrue(command.containsAll(listOf("-nostdin", "-an", "-sn", "-dn")))
            assertEquals("0:v:0", command[command.indexOf("-map") + 1])
            assertEquals("1", command[command.indexOf("-frames:v") + 1])
            assertEquals("rgba", command[command.indexOf("-pix_fmt") + 1])
            assertEquals("rawvideo", command[command.indexOf("-f") + 1])
            assertEquals("pipe:1", command.last())
            assertFalse(command.contains("-vf"), "Native-size straight pixels must reach the PREMUL area worker unchanged.")
            assertTrue(command.none { it.contains("scale=") || it.contains("pad=") || it.contains("bicubic") })
            assertTrue(command.none { it.contains("http://") || it.contains("https://") || it == "-re" })
        } finally { Locale.setDefault(original) }
    }

    @Test fun frameCommandRejectsUnboundedDimensionsAndNegativeTimes() {
        val executable = Path.of("ffmpeg.exe")
        val input = Path.of("emoji.webm")
        listOf(0, 513, Int.MAX_VALUE).forEach { edge ->
            assertFailsWith<IllegalArgumentException> { profileEmojiSilentFrameCommand(executable, input, edge, 0) }
        }
        assertFailsWith<IllegalArgumentException> { profileEmojiSilentFrameCommand(executable, input, 16, -1) }
    }

    @Test fun silentClipCommandBoundsFramesAndSamplingWithoutTruncatingDurationOrOpeningAudio() {
        val command = profileEmojiSilentClipCommand(Path.of("ffmpeg.exe"), Path.of("emoji.webm"), 128, 12.5)
        assertEquals("file,pipe", command[command.indexOf("-protocol_whitelist") + 1])
        assertTrue(command.containsAll(listOf("-nostdin", "-an", "-sn", "-dn")))
        assertEquals("0:v:0", command[command.indexOf("-map") + 1])
        assertFalse(command.contains("-t"), "Frame extraction must cover the native duration rather than truncate it.")
        assertEquals("60", command[command.indexOf("-frames:v") + 1])
        assertEquals("rgba", command[command.indexOf("-pix_fmt") + 1])
        assertEquals("rawvideo", command[command.indexOf("-f") + 1])
        assertEquals("fps=12.50000000", command[command.indexOf("-vf") + 1])
        assertTrue(command.none { it.contains("scale=") || it.contains("pad=") || it.contains("bicubic") },
            "The subprocess must retain native pixels until worker PREMUL area preparation.")
        assertEquals("pipe:1", command.last())
    }

    @Test fun silentClipCommandRejectsInvalidSamplingAndUnboundedDimensions() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, 20.1).forEach { fps ->
            assertFailsWith<IllegalArgumentException> {
                profileEmojiSilentClipCommand(Path.of("ffmpeg.exe"), Path.of("emoji.webm"), 16, fps)
            }
        }
        listOf(0, 513).forEach { edge ->
            assertFailsWith<IllegalArgumentException> {
                profileEmojiSilentClipCommand(Path.of("ffmpeg.exe"), Path.of("emoji.webm"), edge, 20.0)
            }
        }
    }

    @Test fun ownedCodecProcessesReturnExactBoundedBytesAndCloseEveryPipe() {
        val process = FakeCodecProcess(byteArrayOf(3, 1, 4, 1))
        ProfileEmojiOwnedCodecProcesses { process }.use { owner ->
            assertContentEquals(byteArrayOf(3, 1, 4, 1), owner.run(listOf("fake-codec"), 4, { true }))
            process.assertReleased()
        }
    }

    @Test fun ownedCodecProcessesRejectExcessOutputAndStillReleaseTheProcess() {
        val process = FakeCodecProcess(ByteArray(5) { it.toByte() })
        ProfileEmojiOwnedCodecProcesses { process }.use { owner ->
            assertFailsWith<IllegalArgumentException> { owner.run(listOf("fake-codec"), 4, { true }) }
            process.assertReleased()
        }
    }

    @Test fun closingOwnedCodecProcessesDestroysAndUnblocksAnOutstandingPipeRead() {
        val process = FakeCodecProcess(blocking = true)
        val starts = AtomicInteger()
        val owner = ProfileEmojiOwnedCodecProcesses { starts.incrementAndGet(); process }
        val task = FutureTask { owner.run(listOf("fake-codec"), 4, { true }) }
        try {
            Thread(task, "synthetic-codec-close-test").apply { isDaemon = true; start() }
            assertTrue(process.readStarted.await(5, TimeUnit.SECONDS))
            owner.close()
            owner.close()
            assertFailsWith<ExecutionException> { task.get(5, TimeUnit.SECONDS) }
            assertTrue(task.isDone)
            process.assertReleased()
            assertFails { owner.run(listOf("fake-codec"), 4, { true }) }
            assertEquals(1, starts.get(), "A closed owner must not create another codec process.")
        } finally { owner.close() }
    }

    @Test fun retiringActivityDestroysAndUnblocksAReadWithoutExplicitOwnerClose() {
        val process = FakeCodecProcess(blocking = true)
        val active = AtomicBoolean(true)
        ProfileEmojiOwnedCodecProcesses { process }.use { owner ->
            val task = FutureTask { owner.run(listOf("fake-codec"), 4, active::get) }
            Thread(task, "synthetic-codec-activity-test").apply { isDaemon = true; start() }
            assertTrue(process.readStarted.await(5, TimeUnit.SECONDS))
            active.set(false)
            assertFailsWith<ExecutionException> { task.get(5, TimeUnit.SECONDS) }
            assertTrue(task.isDone)
            process.assertReleased()
        }
    }

    @Test fun inactiveOrUnboundedCodecWorkIsRejectedWithoutCreatingAProcess() {
        val starts = AtomicInteger()
        ProfileEmojiOwnedCodecProcesses { starts.incrementAndGet(); FakeCodecProcess() }.use { owner ->
            assertFailsWith<InterruptedException> { owner.run(listOf("fake-codec"), 4, { false }) }
            assertFailsWith<IllegalArgumentException> { owner.run(listOf("fake-codec"), -1, { true }) }
            assertFailsWith<IllegalArgumentException> { owner.run(listOf("fake-codec"), 24 * 1024 * 1024 + 1, { true }) }
            assertEquals(0, starts.get())
        }
    }

    /** A fake OS boundary: destroy releases a blocking input stream; no child process exists. */
    private class FakeCodecProcess(bytes: ByteArray = ByteArray(0), private val blocking: Boolean = false) : Process() {
        val readStarted = CountDownLatch(1)
        private val destroyed = CountDownLatch(1)
        private val alive = AtomicBoolean(blocking)
        private val destroyCalls = AtomicInteger()
        private val waitCalls = AtomicInteger()
        private val stdinClosed = AtomicBoolean()
        private val stdoutClosed = AtomicBoolean()
        private val stderrClosed = AtomicBoolean()
        private val content = ByteArrayInputStream(bytes)
        private val stdout = object : InputStream() {
            private fun beginRead() {
                readStarted.countDown()
                if (blocking) {
                    if (!destroyed.await(5, TimeUnit.SECONDS)) throw IOException("Synthetic blocked read timed out")
                    throw IOException("Synthetic process was destroyed")
                }
            }
            override fun read(): Int { beginRead(); return content.read() }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                beginRead()
                return content.read(buffer, offset, length)
            }
            override fun close() { stdoutClosed.set(true); content.close() }
        }
        private val stdin = object : ByteArrayOutputStream() {
            override fun close() { stdinClosed.set(true); super.close() }
        }
        private val stderr = object : ByteArrayInputStream(ByteArray(0)) {
            override fun close() { stderrClosed.set(true); super.close() }
        }
        override fun getInputStream(): InputStream = stdout
        override fun getOutputStream(): OutputStream = stdin
        override fun getErrorStream(): InputStream = stderr
        override fun waitFor(): Int {
            waitCalls.incrementAndGet()
            if (blocking) destroyed.await()
            alive.set(false)
            return 0
        }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            waitCalls.incrementAndGet()
            return if (blocking) destroyed.await(timeout, unit) else true.also { alive.set(false) }
        }
        override fun exitValue(): Int {
            if (alive.get()) throw IllegalThreadStateException("Synthetic process is active")
            return 0
        }
        override fun isAlive(): Boolean = alive.get()
        override fun destroy() { destroyForcibly() }
        override fun destroyForcibly(): Process {
            destroyCalls.incrementAndGet()
            alive.set(false)
            destroyed.countDown()
            return this
        }
        fun assertReleased() {
            assertTrue(destroyCalls.get() > 0)
            assertTrue(waitCalls.get() > 0, "Cleanup waits for process termination.")
            assertFalse(isAlive())
            assertTrue(stdinClosed.get())
            assertTrue(stdoutClosed.get())
            assertTrue(stderrClosed.get())
        }
    }

    private fun decoderWithoutProcesses() = SkiaProfileEmojiArtworkDecoder(
        ffmpeg = { error("Static/TGS tests must not resolve FFmpeg") },
        startProcess = { error("Static/TGS tests must not open a process") },
    )

    private fun request(edge: Int, time: Long = 0) =
        ProfileEmojiArtworkRequest(ProfileEmojiArtworkRole.OPTION_THUMBNAIL, edge, time)

    private fun encoded(bytes: ByteArray, fullFormat: MediaFormat = MediaFormat.WEBP,
        role: ProfileEmojiFileRole = ProfileEmojiFileRole.FULL, thumbnailFormat: MediaFormat = MediaFormat.WEBP): ProfileEmojiArtworkInput {
        val full = MediaFile(17, bytes.size.toLong(), uniqueId = "synthetic-full")
        val thumbnail = MediaFile(18, bytes.size.toLong(), uniqueId = "synthetic-thumbnail")
        val emoji = SettingsBackgroundEmoji(71, StickerItem(81, 91, "x", MessageMedia(
            full, thumbnail, 8, 4, fullFormat, thumbnailFormat = thumbnailFormat,
        )))
        return ProfileEmojiArtworkInput(ProfileEmojiEncoded(emoji, role,
            if (role == ProfileEmojiFileRole.FULL) full else thumbnail, bytes))
    }

    private fun png(width: Int, height: Int, red: Int, green: Int, blue: Int, alpha: Int): ByteArray {
        val bytes = ByteArray(width * height * 4) { index ->
            when (index % 4) { 0 -> red; 1 -> green; 2 -> blue; else -> alpha }.toByte()
        }
        val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL, ColorSpace.sRGB)
        return Image.makeRaster(info, bytes, width * 4).use { image ->
            checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).use { it.bytes }
        }
    }

    private fun pngHeaderDimensions(source: ByteArray, width: Int, height: Int): ByteArray = source.copyOf().also { bytes ->
        ByteBuffer.wrap(bytes).putInt(16, width).putInt(20, height)
        val crc = CRC32().apply { update(bytes, 12, 17) }.value.toInt()
        ByteBuffer.wrap(bytes).putInt(29, crc)
    }

    private fun gzip(text: String): ByteArray = ByteArrayOutputStream().also { output ->
        GZIPOutputStream(output).use { it.write(text.encodeToByteArray()) }
    }.toByteArray()

    private fun lottie(width: Int = 16, fps: Int = 30, frames: Int = 30) =
        """{"v":"5.5.2","fr":$fps,"ip":0,"op":$frames,"w":$width,"h":16,"ddd":0,"assets":[],"layers":[{"ddd":0,"ind":1,"ty":4,"nm":"synthetic","sr":1,"ks":{"o":{"a":0,"k":100},"r":{"a":0,"k":0},"p":{"a":0,"k":[0,0,0]},"a":{"a":0,"k":[0,0,0]},"s":{"a":0,"k":[100,100,100]}},"ao":0,"shapes":[{"ty":"rc","d":1,"s":{"a":0,"k":[16,16]},"p":{"a":0,"k":[8,8]},"r":{"a":0,"k":0}},{"ty":"fl","c":{"a":0,"k":[0,1,0,1]},"o":{"a":0,"k":100}}],"ip":0,"op":$frames,"st":0,"bm":0}]}"""

    private fun ImageBitmap.pixel(x: Int, y: Int): Int = IntArray(width * height).also { readPixels(it) }[y * width + x]

    private fun assertArgbNear(expected: Int, actual: Int) {
        for (shift in listOf(24, 16, 8, 0)) {
            assertTrue(abs((expected ushr shift and 255) - (actual ushr shift and 255)) <= 2,
                "Expected ARGB ${expected.toUInt().toString(16)}, got ${actual.toUInt().toString(16)}")
        }
    }
}
