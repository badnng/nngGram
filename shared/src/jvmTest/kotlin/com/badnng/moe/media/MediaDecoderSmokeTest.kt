package com.badnng.moe.media

import com.badnng.moe.chat.MediaFormat
import com.badnng.moe.chat.AttachmentKind
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import javax.imageio.ImageIO
import kotlin.test.*

/** Generated, non-account media only. Enable with -PmediaSmoke after preparing the decoder. */
class MediaDecoderSmokeTest {
    @Test
    fun syntheticPcmEncodesAsMonoOpusInAnOggContainerWithoutOpeningAMicrophone() {
        val directory = Files.createTempDirectory("synthetic-voice-codec-")
        val pcm = directory.resolve("voice.pcm")
        val ogg = directory.resolve("voice.ogg")
        try {
            Files.newOutputStream(pcm).use { output ->
                repeat(24_000) { index ->
                    val sample = (kotlin.math.sin(index * 2.0 * Math.PI * 440 / 48_000) * 12_000).toInt()
                    output.write(sample and 255)
                    output.write((sample ushr 8) and 255)
                }
            }
            VoiceRecordingFiles.encode(pcm, ogg)
            val probe = MediaTools.capture(assertNotNull(MediaTools.find("ffprobe.exe")),
                listOf("-v", "error", "-show_entries", "stream=codec_name,channels:format=format_name,duration", "-of", "default", ogg.toString()))
            assertTrue("codec_name=opus" in probe)
            assertTrue("channels=1" in probe)
            assertTrue("format_name=ogg" in probe)
            assertTrue(Files.size(ogg) > 100)
        } finally {
            Files.deleteIfExists(pcm)
            Files.deleteIfExists(ogg)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun generatedH264VideoHasRealAttachmentMetadata() {
        val executable = assertNotNull(MediaDecoder.findFfmpeg())
        val file = Files.createTempFile("synthetic-upload-video-", ".mp4")
        MediaTools.capture(executable, listOf("-nostdin", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i",
            "color=c=blue:s=64x48:r=10:d=0.5", "-c:v", "libx264", "-threads", "1", "-pix_fmt", "yuv420p",
            "-movflags", "+faststart", "-y", file.toString()))
        var attachment: com.badnng.moe.chat.LocalAttachment? = null
        try {
            attachment = AttachmentFiles.inspect(file.toString(), AttachmentKind.VIDEO)
            assertEquals(64, attachment.width)
            assertEquals(48, attachment.height)
            assertEquals(1, attachment.durationSeconds)
            assertTrue(attachment.supportsStreaming)
            val thumbnail = assertNotNull(attachment.thumbnail)
            assertEquals(64, thumbnail.width)
            assertEquals(48, thumbnail.height)
            assertTrue(thumbnail.size in 1 until 200_000L)
            val pixels = assertNotNull(ImageIO.read(java.io.File(thumbnail.path)))
            assertTrue((pixels.getRGB(32, 24) and 255) > 180, "The prepared JPEG contains the selected blue video frame")
            AttachmentFiles.validatePlayback(file.toString())
            val pending = assertNotNull(attachment.lease?.retain())
            attachment.lease?.release()
            assertTrue(Files.isRegularFile(java.nio.file.Path.of(thumbnail.path)))
            pending.release()
            assertFalse(Files.exists(java.nio.file.Path.of(thumbnail.path)))
            assertTrue(Files.isRegularFile(file), "Thumbnail ownership must never delete the user's video")
        } finally { attachment?.lease?.release(); Files.deleteIfExists(file) }
    }

    @Test
    fun aRotatedAnamorphicVideoKeepsDisplayGeometryAndOnlyFastStartPromisesStreaming() {
        val executable = assertNotNull(MediaDecoder.findFfmpeg())
        val original = Files.createTempFile("synthetic-anamorphic-", ".mp4")
        val rotated = Files.createTempFile("synthetic-rotated-", ".mp4")
        var selected: com.badnng.moe.chat.LocalAttachment? = null
        var trailing: com.badnng.moe.chat.LocalAttachment? = null
        try {
            MediaTools.capture(executable, listOf("-nostdin", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i",
                "color=c=blue:s=32x24:r=10:d=0.5", "-vf", "setsar=3/2", "-c:v", "libx264", "-threads", "1", "-pix_fmt", "yuv420p",
                "-y", original.toString()))
            assertFalse(isFastStartMp4(original), "A valid MP4 with trailing moov cannot be advertised as fast-start")
            trailing = AttachmentFiles.inspect(original.toString(), AttachmentKind.VIDEO)
            assertFalse(trailing.supportsStreaming)
            assertEquals(48, trailing.width)
            assertEquals(24, trailing.height)
            MediaTools.capture(executable, listOf("-nostdin", "-hide_banner", "-loglevel", "error", "-display_rotation:v:0", "90",
                "-i", original.toString(), "-c", "copy", "-movflags", "+faststart", "-y", rotated.toString()))
            val rotation = MediaTools.capture(assertNotNull(MediaTools.find("ffprobe.exe")),
                listOf("-v", "error", "-show_entries", "stream_side_data=rotation", "-of", "default", "-i", rotated.toString()))
            assertTrue("rotation=90" in rotation, "The synthetic fixture must contain a real display matrix before testing geometry")
            selected = AttachmentFiles.inspect(rotated.toString(), AttachmentKind.VIDEO)
            assertEquals(24, selected.width)
            assertEquals(48, selected.height)
            assertTrue(selected.supportsStreaming)
            assertEquals(24, selected.thumbnail?.width)
            assertEquals(48, selected.thumbnail?.height)
            Files.write(rotated, byteArrayOf(1, 2, 3))
            assertFalse(AttachmentFiles.matchesSelection(selected))
        } finally {
            selected?.lease?.release()
            trailing?.lease?.release()
            Files.deleteIfExists(original); Files.deleteIfExists(rotated)
        }
    }

    @Test
    fun videoUploadRejectsOtherCodecsAndMalformedInputsWithoutTreatingThemAsExecutable() {
        val executable = assertNotNull(MediaDecoder.findFfmpeg())
        val file = Files.createTempFile("synthetic-non-mp4-", ".webm")
        MediaTools.capture(executable, listOf("-nostdin", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i",
            "color=c=blue:s=32x32:r=10:d=0.2", "-c:v", "libvpx-vp9", "-threads", "1", "-y", file.toString()))
        assertEquals(AttachmentFailure.Reason.VIDEO_FORMAT,
            assertFailsWith<AttachmentFailure> { AttachmentFiles.inspect(file.toString(), AttachmentKind.VIDEO) }.reason)
        assertEquals(AttachmentKind.FILE, AttachmentFiles.inspect(file.toString(), AttachmentKind.FILE).kind)
        val invalid = Files.createTempFile("synthetic-fake-video-", ".mp4")
        Files.writeString(invalid, "synthetic invalid video")
        assertFailsWith<AttachmentFailure> { AttachmentFiles.validatePlayback(invalid.toString()) }
    }

    @Test
    fun h264QuickTimeIsARealVideoButCanOnlyBeSelectedAsAFileInTheMp4SendPath() {
        val executable = assertNotNull(MediaDecoder.findFfmpeg())
        val file = Files.createTempFile("synthetic-quicktime-", ".mov")
        try {
            MediaTools.capture(executable, listOf("-nostdin", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i",
                "color=c=blue:s=32x24:r=10:d=0.5", "-c:v", "libx264", "-threads", "1", "-pix_fmt", "yuv420p",
                "-f", "mov", "-y", file.toString()))
            assertEquals("h264", AttachmentFiles.validatePlayback(file.toString()).codec)
            assertFalse(inspectMp4Layout(file).isMp4)
            assertEquals(AttachmentFailure.Reason.VIDEO_FORMAT,
                assertFailsWith<AttachmentFailure> { AttachmentFiles.inspect(file.toString(), AttachmentKind.VIDEO) }.reason)
            assertEquals(AttachmentKind.FILE, AttachmentFiles.inspect(file.toString(), AttachmentKind.FILE).kind)
        } finally { Files.deleteIfExists(file) }
    }

    @Test
    fun syntheticPhotoDecodesAndPassesAttachmentValidation() {
        val file = Files.createTempFile("synthetic-photo-", ".png")
        ImageIO.write(BufferedImage(32, 20, BufferedImage.TYPE_INT_RGB), "png", file.toFile())
        assertTrue(PhotoAttachmentValidation.validate(file.toString()))
        MediaDecoder.decode(file.toString(), MediaFormat.PHOTO).use { media ->
            assertEquals(32, media.width)
            assertEquals(20, media.height)
            Surface.makeRasterN32Premul(40, 40).use { media.draw(it.canvas, Rect.makeWH(40f, 40f), 0) }
        }
    }

    @Test
    fun malformedAndExtremeAspectAttachmentsAreRejected() {
        val invalid = Files.createTempFile("synthetic-invalid-", ".png")
        Files.writeString(invalid, "synthetic invalid data")
        assertFalse(PhotoAttachmentValidation.validate(invalid.toString()))
        assertFails { MediaDecoder.decode(invalid.toString(), MediaFormat.PHOTO) }
        val long = Files.createTempFile("synthetic-long-", ".png")
        ImageIO.write(BufferedImage(100, 1, BufferedImage.TYPE_INT_RGB), "png", long.toFile())
        assertFalse(PhotoAttachmentValidation.validate(long.toString()))
    }

    @Test
    fun syntheticTgsUsesTheVectorAnimationDecoder() {
        val file = Files.createTempFile("synthetic-sticker-", ".tgs")
        val json = """{"v":"5.5.2","fr":30,"ip":0,"op":30,"w":32,"h":32,"nm":"synthetic","ddd":0,"assets":[],"layers":[]}"""
        GZIPOutputStream(Files.newOutputStream(file)).use { it.write(json.toByteArray()) }
        MediaDecoder.decode(file.toString(), MediaFormat.TGS).use { media ->
            assertEquals(32, media.width)
            assertEquals(1_000L, media.durationMillis)
            Surface.makeRasterN32Premul(32, 32).use { media.draw(it.canvas, Rect.makeWH(32f, 32f), 500) }
        }
    }

    @Test
    fun decompressionLimitRejectsAnOversizedTgs() {
        val file = Files.createTempFile("synthetic-large-sticker-", ".tgs")
        GZIPOutputStream(Files.newOutputStream(file)).use { it.write(ByteArray(2 * 1024 * 1024 + 2)) }
        assertFails { MediaDecoder.decode(file.toString(), MediaFormat.TGS) }
    }

    @Test
    fun syntheticVp9VideoStickerDecodesIntoBoundedAnimatedFrames() {
        val executable = assertNotNull(MediaDecoder.findFfmpeg())
        val file = Files.createTempFile("synthetic-video-sticker-", ".webm")
        val process = ProcessBuilder(executable.toString(), "-nostdin", "-hide_banner", "-loglevel", "error",
            "-f", "lavfi", "-i", "color=c=red:s=32x32:r=20:d=0.25", "-c:v", "libvpx-vp9", "-threads", "1", "-y", file.toString())
            .redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue())
        } finally { process.destroyForcibly() }
        MediaDecoder.decode(file.toString(), MediaFormat.WEBM).use { media ->
            assertEquals(256, media.width)
            assertTrue(media.durationMillis in 50..3_000)
            assertTrue(media.animationSchedule?.animated == true)
            Surface.makeRasterN32Premul(256, 256).use { media.draw(it.canvas, Rect.makeWH(256f, 256f), 150) }
        }
    }

    @Test
    fun aCustomEmojiWithoutAThumbnailKeepsOneStaticVideoFrameUntilItsHoverAnimationIsDecoded() {
        val executable = assertNotNull(MediaDecoder.findFfmpeg())
        val file = Files.createTempFile("synthetic-reaction-static-", ".webm")
        try {
            MediaTools.capture(executable, listOf("-nostdin", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i",
                "color=c=red:s=32x32:r=20:d=0.5", "-c:v", "libvpx-vp9", "-threads", "1", "-y", file.toString()))
            MediaDecoder.decodeReaction(file.toString(), MediaFormat.WEBM, firstFrame = true).use { still ->
                assertEquals(96, still.width)
                assertEquals(96, still.height)
                assertEquals(50L, still.durationMillis, "The static decoder must allocate exactly one 20 fps raster frame.")
                assertFalse(assertNotNull(still.animationSchedule).animated)
                Surface.makeRasterN32Premul(96, 96).use { surface ->
                    still.draw(surface.canvas, Rect.makeWH(96f, 96f), 0)
                    surface.makeImageSnapshot().use { image ->
                        org.jetbrains.skia.Bitmap.makeFromImage(image).use { pixels ->
                            val center = pixels.getColor(48, 48)
                            assertTrue((center ushr 16 and 255) > 160 && (center ushr 8 and 255) < 100,
                                "The single static frame must still show the native emoji image.")
                        }
                    }
                }
            }
            MediaDecoder.decodeReaction(file.toString(), MediaFormat.WEBM, firstFrame = false).use { animated ->
                assertEquals(96, animated.width)
                assertEquals(96, animated.height)
                assertTrue(animated.durationMillis > 50L)
                assertTrue(assertNotNull(animated.animationSchedule).animated,
                    "The separately requested hover resource must retain and schedule the complete video animation.")
            }
            MediaDecoder.decodeGridPreview(file.toString(), MediaFormat.WEBM, firstFrame = true, edge = 160).use { still ->
                assertEquals(160, still.width)
                assertEquals(160, still.height)
                assertEquals(50L, still.durationMillis)
                assertFalse(assertNotNull(still.animationSchedule).animated)
            }
        } finally { Files.deleteIfExists(file) }
    }
}
