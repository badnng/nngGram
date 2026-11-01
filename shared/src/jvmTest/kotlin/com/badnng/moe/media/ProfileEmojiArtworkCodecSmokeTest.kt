package com.badnng.moe.media

import androidx.compose.ui.graphics.ImageBitmap
import com.badnng.moe.chat.MediaFile
import com.badnng.moe.chat.MediaFormat
import com.badnng.moe.chat.MessageMedia
import com.badnng.moe.chat.StickerItem
import com.badnng.moe.settings.SettingsBackgroundEmoji
import com.badnng.moe.tdlib.ProfileEmojiEncoded
import com.badnng.moe.tdlib.ProfileEmojiFileRole
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.test.*

/** Generated local pixels only. -PmediaSmoke provides codecs; no account, audio or device opens. */
class ProfileEmojiArtworkCodecSmokeTest {
    @Test fun transparentVp9UsesNativePixelsAndReusesOneOwnedSilentClipUntilClose() {
        val executable = MediaDecoder.findFfmpeg()
        assumeTrue("Prepare the bundled -PmediaSmoke codec for the profile artwork smoke", executable != null)
        val directory = Files.createTempDirectory("synthetic-profile-artwork-")
        val raw = directory.resolve("rgba.raw")
        val webm = directory.resolve("emoji.webm")
        val commands = CopyOnWriteArrayList<List<String>>()
        val ownedInputs = CopyOnWriteArrayList<Path>()
        val decoder = SkiaProfileEmojiArtworkDecoder(startProcess = { command ->
            commands.add(command.toList())
            val inputIndex = command.indexOf("-i")
            val input = Path.of(if (inputIndex >= 0) command[inputIndex + 1] else command.last())
            ownedInputs.add(input)
            ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        })
        try {
            val rgba = ByteArray(8 * 4 * 4 * 5) { index ->
                val left = (index / 4) % 8 < 4
                when (index % 4) {
                    0 -> if (left) 255 else 0
                    2 -> if (left) 0 else 255 // Hidden blue in the fully transparent half.
                    3 -> if (left) 128 else 0
                    else -> 0
                }.toByte()
            }
            Files.write(raw, rgba)
            MediaTools.capture(assertNotNull(executable), listOf("-nostdin", "-hide_banner", "-loglevel", "error",
                "-f", "rawvideo", "-pixel_format", "rgba", "-video_size", "8x4", "-framerate", "10",
                "-i", raw.toString(), "-an", "-sn", "-dn", "-c:v", "libvpx-vp9", "-threads", "1",
                "-lossless", "1", "-auto-alt-ref", "0", "-pix_fmt", "yuva420p", "-y", webm.toString()))
            val bytes = Files.readAllBytes(webm)
            val file = MediaFile(117, bytes.size.toLong(), uniqueId = "synthetic-profile-vp9")
            val emoji = SettingsBackgroundEmoji(171, StickerItem(181, 191, "x",
                MessageMedia(file, null, 8, 4, MediaFormat.WEBM)), needsRepainting = true)
            val input = ProfileEmojiArtworkInput(ProfileEmojiEncoded(emoji, ProfileEmojiFileRole.FULL, file, bytes))
            decoder.decode(input, ProfileEmojiArtworkRequest(ProfileEmojiArtworkRole.PROFILE_MASK, 4), true) { true }.use { frame ->
                assertTrue(frame.animated)
                assertTrue(frame.isAlphaMask)
                assertTrue(frame.durationMillis in 450..550)
                assertEquals(100L, frame.frameDurationMillis)
                val image = assertNotNull(frame.image)
                assertEquals(4, image.width)
                assertEquals(4, image.height)
                assertEquals(0, image.pixel(0, 0), "Aspect-fit letterboxing remains transparent.")
                assertColorNear(0x80ffffff.toInt(), image.pixel(0, 1))
                assertEquals(0, image.pixel(3, 1), "Native VP9 alpha reaches the worker.")
            }
            decoder.decode(input, ProfileEmojiArtworkRequest(ProfileEmojiArtworkRole.GIFT, 4, 250), false) { true }.use { frame ->
                assertFalse(frame.isAlphaMask)
                assertTrue(frame.animated)
                assertColorNear(0x80ff0000.toInt(), assertNotNull(frame.image).pixel(0, 1))
            }
            decoder.decode(input, ProfileEmojiArtworkRequest(ProfileEmojiArtworkRole.PROFILE_MASK, 4, 400), true) { true }.close()
            assertEquals(2, commands.size, "Frames and mask/color roles share exactly one FFprobe and one owned extraction.")
            val extraction = commands.single { "rawvideo" in it }
            assertTrue(extraction.containsAll(listOf("-an", "-sn", "-dn")))
            assertEquals("fps=10.00000000", extraction[extraction.indexOf("-vf") + 1])
            assertTrue(extraction.none { "scale=" in it || "pad=" in it || "bicubic" in it })
            val scratch = ownedInputs.map { it.parent }.distinct()
            assertEquals(1, scratch.size)
            assertTrue(scratch.all { Files.isDirectory(it) })
            assertTrue(ownedInputs.all { !Files.exists(it) }, "Scratch input is removed once the clip is ready.")
            decoder.close()
            decoder.close()
            assertTrue(scratch.all { !Files.exists(it) }, "Close removes the exact owned PNGs and their empty scratch directory.")
            assertTrue(Files.isRegularFile(webm), "Decoder ownership cannot delete the fixture source.")
        } finally {
            decoder.close()
            Files.deleteIfExists(raw)
            Files.deleteIfExists(webm)
            Files.deleteIfExists(directory)
        }
    }

    private fun ImageBitmap.pixel(x: Int, y: Int): Int = IntArray(width * height).also { readPixels(it) }[y * width + x]
    private fun assertColorNear(expected: Int, actual: Int) {
        for (shift in listOf(24, 16, 8, 0)) assertTrue(
            abs((expected ushr shift and 255) - (actual ushr shift and 255)) <= 8,
            "Expected ARGB ${expected.toUInt().toString(16)}, got ${actual.toUInt().toString(16)}")
    }
}
