package com.badnng.moe.media

import androidx.compose.ui.graphics.ImageBitmap
import com.badnng.moe.chat.MediaFile
import com.badnng.moe.chat.MediaFormat
import com.badnng.moe.settings.*
import com.badnng.moe.tdlib.*
import org.jetbrains.skia.*
import org.junit.Assume.assumeTrue
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPOutputStream
import kotlin.test.*

/** Synthetic local media through the real saved provider/codec, never an account or native catalog. */
class ProfileStickerAvatarSavedCodecSmokeTest {
    @Test fun savedWebpRealPixelsRetainSourceAfterReaderCloseAndStopOnRevocation() {
        smokeOnly()
        val bytes = image(EncodedImageFormat.WEBP)
        val pixels = ProfileStickerAvatarPixelBudget(); val disk = ProfileStickerAvatarDiskBudget()
        val codec = SkiaProfileStickerAvatarCodec(pixels, disk, { error("WEBP must not start FFmpeg") })
        Harness(bytes, MediaFormat.WEBP, 8, 4, true, codec, pixels, disk).use { f ->
            val (owner, clip) = f.open()
            assertFalse(clip.animated); assertEquals(0L, clip.durationMillis); assertTrue(f.reader.closed.get())
            assertTrue(owner.requestCurrent()); assertTrue(clip.active())
            val (frame, artwork) = f.frame(clip, 0)
            assertTrue(artwork.needsRepainting); assertColorNear(0xffff4010.toInt(), artwork.image!!.pixel(16, 16), 15)
            assertTrue(pixels.used() > 0)
            f.source.set(false)
            assertFalse(owner.requestCurrent()); assertFalse(clip.active()); assertNull(artwork.image)
            assertTrue(pixels.used() > 0, "Revocation cannot dispose a UI-borrowed frame")
            frame.close(); owner.close(); f.await { pixels.used() == 0L }
        }
    }

    @Test fun savedTgsUsesRealAnimatedFramesAndStopsAtSourceGate() {
        smokeOnly()
        val pixels = ProfileStickerAvatarPixelBudget(); val disk = ProfileStickerAvatarDiskBudget()
        val codec = SkiaProfileStickerAvatarCodec(pixels, disk, { error("TGS must not start FFmpeg") })
        Harness(gzip(lottie()), MediaFormat.TGS, 16, 16, false, codec, pixels, disk).use { f ->
            val (owner, clip) = f.open(); assertTrue(clip.animated); assertEquals(1000L, clip.durationMillis)
            val (first, firstArt) = f.frame(clip, 0)
            assertFalse(firstArt.needsRepainting); assertColorNear(0xff00ff00.toInt(), firstArt.image!!.pixel(16, 16), 3)
            first.close()
            // The real codec floors requests to its 33 ms hint: 500 seeks 495,
            // before this fixture's held color changes. Sample beyond that boundary.
            val secondTime = clip.frameDurationMillis * 16
            assertTrue(secondTime in 501 until clip.durationMillis)
            val (second, secondArt) = f.frame(clip, secondTime)
            assertColorNear(0xffff0000.toInt(), secondArt.image!!.pixel(16, 16), 3)
            f.source.set(false); assertFalse(clip.active()); assertNull(secondArt.image)
            second.close(); owner.close(); f.await { pixels.used() == 0L }
        }
    }

    @Test fun savedWebmUsesOneRealSilentExtractionAndSourceRevocation() {
        smokeOnly()
        val binary = MediaDecoder.findFfmpeg(); assumeTrue("Bundled FFmpeg is required", binary != null)
        val folder = Files.createTempDirectory("saved-avatar-codec-")
        val raw = folder.resolve("fixture.rgba"); val video = folder.resolve("fixture.webm")
        val commands = CopyOnWriteArrayList<List<String>>()
        val pixels = ProfileStickerAvatarPixelBudget(); val disk = ProfileStickerAvatarDiskBudget()
        try {
            val rgba = ByteArray(8 * 4 * 4 * 5) { index ->
                val frame = index / (8 * 4 * 4)
                when (index % 4) { 0 -> if (frame < 2) 255 else 0; 2 -> if (frame >= 2) 255 else 0; 3 -> 255; else -> 0 }.toByte()
            }
            Files.write(raw, rgba)
            MediaTools.capture(checkNotNull(binary), listOf("-nostdin", "-hide_banner", "-loglevel", "error",
                "-f", "rawvideo", "-pixel_format", "rgba", "-video_size", "8x4", "-framerate", "10", "-i", raw.toString(),
                "-an", "-sn", "-dn", "-c:v", "libvpx-vp9", "-threads", "1", "-lossless", "1", "-auto-alt-ref", "0",
                "-pix_fmt", "yuva420p", "-y", video.toString()))
            val codec = SkiaProfileStickerAvatarCodec(pixels, disk, { binary }, ProfileStickerAvatarProcesses(start = { command ->
                commands += command.toList(); ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            }))
            Harness(Files.readAllBytes(video), MediaFormat.WEBM, 8, 4, false, codec, pixels, disk).use { f ->
                val (owner, clip) = f.open(); assertTrue(clip.animated); assertTrue(clip.durationMillis in 450..550)
                val (first, firstArt) = f.frame(clip, 0); assertColorNear(0xffff0000.toInt(), firstArt.image!!.pixel(16, 16), 20); first.close()
                val (second, secondArt) = f.frame(clip, 200); assertColorNear(0xff0000ff.toInt(), secondArt.image!!.pixel(16, 16), 20)
                assertEquals(2, commands.size, "A single probe and extraction serve every frame")
                assertTrue(commands.single { "rawvideo" in it }.containsAll(listOf("-an", "-sn", "-dn")))
                f.source.set(false); assertFalse(clip.active()); assertNull(secondArt.image)
                second.close(); owner.close(); f.await { pixels.used() == 0L && disk.used() == 0L }
            }
        } finally { Files.deleteIfExists(raw); Files.deleteIfExists(video); Files.deleteIfExists(folder) }
    }

    private class Harness(bytes: ByteArray, format: MediaFormat, width: Int, height: Int, repaint: Boolean,
        codec: ProfileStickerAvatarCodec, private val pixels: ProfileStickerAvatarPixelBudget,
        private val disk: ProfileStickerAvatarDiskBudget) : AutoCloseable {
        val source = AtomicBoolean(true); private val budget = ProfileStickerAvatarEncodedBudget()
        private val ui = Ui(); private val workers = ProfileStickerAvatarArtworkWorkers()
        val reader = Reader(bytes, format, width, height, repaint, source, budget)
        private val provider = JvmProfileStickerAvatarArtworkProvider(1, reader, ui, codec, workers)
        private val request = SettingsSavedProfileStickerAvatarRequest.Current(SettingsProfileExtrasToken(1, 7, 9), 3, 77)
        fun open(): Pair<ProfileStickerAvatarSavedPreviewLease, ProfileStickerAvatarPreviewClip> {
            val output = AtomicReference<SettingsResult<ProfileStickerAvatarPreviewClip>?>()
            val owner = provider.savedPreview(request, 32, { true }) { output.set(it) }
            await { output.get() != null }
            val response = checkNotNull(output.get()); assertNull(response.error); return owner to assertNotNull(response.value)
        }
        fun frame(clip: ProfileStickerAvatarPreviewClip, time: Long): Pair<ProfileStickerAvatarArtworkLease, ProfileStickerAvatarArtwork> {
            val output = AtomicReference<SettingsResult<ProfileStickerAvatarArtwork>?>()
            val owner = clip.frame(time, { true }) { output.set(it) }
            await { output.get() != null }
            val response = checkNotNull(output.get()); assertNull(response.error); return owner to assertNotNull(response.value)
        }
        fun await(predicate: () -> Boolean) {
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (!predicate()) { ui.drain(); check(System.nanoTime() < end) { "Synthetic saved codec did not settle" }; Thread.sleep(10) }
            ui.drain()
        }
        override fun close() { provider.close(); ui.drain(); workers.close(); await { budget.used() == 0L && pixels.used() == 0L && disk.used() == 0L } }
    }
    private class Ui : ProfileStickerAvatarUiDispatch {
        private val queue = ConcurrentLinkedQueue<Pair<() -> Unit, () -> Unit>>()
        override fun post(deliver: () -> Unit, discard: () -> Unit) { queue.add(deliver to discard) }
        fun drain() { while (true) (queue.poll() ?: break).first() }
    }
    private class Reader(private val bytes: ByteArray, private val format: MediaFormat, private val width: Int, private val height: Int,
        private val repaint: Boolean, private val source: AtomicBoolean, private val budget: ProfileStickerAvatarEncodedBudget) : ProfileStickerAvatarEncodedReader {
        val closed = AtomicBoolean()
        override fun thumbnail(parent: SettingsProfileExtrasToken, picker: ProfileStickerAvatarPickerTicket,
            candidate: ProfileStickerAvatarCandidateTicket, active: () -> Boolean,
            result: (SettingsResult<ProfileStickerAvatarEncoded>) -> Unit) = AutoCloseable {}
        override fun preview(parent: SettingsProfileExtrasToken, picker: ProfileStickerAvatarPickerTicket,
            selection: ProfileStickerAvatarSelectionTicket, active: () -> Boolean,
            result: (SettingsResult<ProfileStickerAvatarEncoded>) -> Unit) = AutoCloseable {}
        override fun savedPreview(request: SettingsSavedProfileStickerAvatarRequest, active: () -> Boolean,
            result: (SettingsResult<ProfileStickerAvatarEncoded>) -> Unit): SettingsSavedProfileStickerAvatarLease {
            val file = MediaFile(1, bytes.size.toLong(), uniqueId = "synthetic")
            val media = ProfileStickerAvatarOwnedMedia(ProfileStickerAvatarMediaRole.FULL,
                ProfileStickerAvatarAsset(file, format, width, height), repaint)
            val original = ProfileStickerAvatarEncoded.take(media, bytes.copyOf(), checkNotNull(budget.reserve(bytes.size.toLong())))
            val narrowed = checkNotNull(original.restrictSource(source::get)); original.close()
            result(SettingsResult(value = narrowed)) // Deliberately precedes the returned lease.
            return object : SettingsSavedProfileStickerAvatarLease {
                override fun requestCurrent() = !closed.get() && source.get() && active()
                override fun close() { closed.set(true) }
            }
        }
    }
    private fun image(format: EncodedImageFormat): ByteArray {
        val rgba = ByteArray(8 * 4 * 4) { index -> when (index % 4) { 0 -> 255; 1 -> 64; 2 -> 16; else -> 255 }.toByte() }
        val info = ImageInfo(8, 4, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL, ColorSpace.sRGB)
        return Image.makeRaster(info, rgba, 32).use { image -> checkNotNull(image.encodeToData(format)).use { it.bytes } }
    }
    private fun gzip(text: String): ByteArray = ByteArrayOutputStream().also { output ->
        GZIPOutputStream(output).use { it.write(text.encodeToByteArray()) }
    }.toByteArray()
    private fun lottie() = """{"v":"5.5.2","fr":30,"ip":0,"op":30,"w":16,"h":16,"ddd":0,"assets":[],"layers":[{"ddd":0,"ind":1,"ty":4,"nm":"synthetic","sr":1,"ks":{"o":{"a":0,"k":100},"r":{"a":0,"k":0},"p":{"a":0,"k":[0,0,0]},"a":{"a":0,"k":[0,0,0]},"s":{"a":0,"k":[100,100,100]}},"ao":0,"shapes":[{"ty":"rc","d":1,"s":{"a":0,"k":[16,16]},"p":{"a":0,"k":[8,8]},"r":{"a":0,"k":0}},{"ty":"fl","c":{"a":1,"k":[{"t":0,"s":[0,1,0,1],"h":1},{"t":15,"s":[1,0,0,1],"h":1},{"t":30,"s":[0,1,0,1]}]},"o":{"a":0,"k":100}}],"ip":0,"op":30,"st":0,"bm":0}]}"""
    private fun ImageBitmap.pixel(x: Int, y: Int): Int = IntArray(width * height).also { readPixels(it) }[y * width + x]
    private fun assertColorNear(expected: Int, actual: Int, tolerance: Int) {
        for (shift in listOf(24, 16, 8, 0)) assertTrue(
            kotlin.math.abs((expected ushr shift and 255) - (actual ushr shift and 255)) <= tolerance,
            "Expected ${expected.toUInt().toString(16)}, got ${actual.toUInt().toString(16)}")
    }
    private fun smokeOnly() = assumeTrue("Run synthetic codec tests with -PmediaSmoke", !System.getProperty("nnggram.ffmpeg.path").isNullOrBlank())
}
