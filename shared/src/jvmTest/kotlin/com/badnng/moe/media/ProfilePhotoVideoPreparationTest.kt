package com.badnng.moe.media

import com.badnng.moe.chat.ownedAttachmentLease
import com.badnng.moe.settings.SettingsPreparedProfileAnimation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import kotlin.test.*

/** Fake process tests establish ownership/cancellation/bounds only; codec evidence is the separate smoke class. */
class ProfilePhotoVideoPreparationTest {
    @Test fun copiedSourceAcceptsTheAttachmentBudgetAndNeverOwnsTheUserFile() = runBlocking {
        withFiles { original, owned ->
            val bytes = ByteArray(2_000_123) { (it % 251).toByte() }
            Files.write(original, bytes)
            val commands = mutableListOf<List<String>>()
            val preparation = fakePreparation(owned) { command -> commands += command; CompletedProcess(probeText()) }
            val source = preparation.read(original.toString())
            try {
                assertEquals(2_000_123L, source.size, "The 2 MB budget applies only to encoded output.")
                assertEquals(240, source.width)
                assertEquals(60, source.height)
                assertEquals(4_000L, source.durationMillis)
                assertContentEquals(bytes, Files.readAllBytes(source.path))
                assertEquals(2, commands.size, "Read requires probe and a real decode command, not just metadata.")
                assertTrue(commands.all { original.toString() !in it && source.path.toString() in it })
                Files.write(original, byteArrayOf(1, 2, 3))
                assertContentEquals(bytes, Files.readAllBytes(source.path))
                val retained = assertNotNull(source.retain())
                source.close(); source.close()
                assertTrue(Files.exists(source.path))
                assertNull(source.retain())
                retained.release(); retained.release()
                assertFalse(Files.exists(source.path))
                assertTrue(Files.exists(original))
                assertFalse(source.toString().contains(source.path.toString()))
            } finally { source.close() }
        }
    }

    @Test fun invalidCropAndSourceTimelineNeverStartAnEncoder() = runBlocking {
        withFiles { original, owned ->
            val calls = AtomicInteger()
            val preparation = fakePreparation(owned) { calls.incrementAndGet(); CompletedProcess(probeText()) }
            val source = preparation.read(original.toString())
            try {
                val valid = ProfilePhotoVideoEdit(ProfilePhotoCrop(), 1_000, 3_000, 2_999)
                assertTrue(ProfilePhotoVideoPreparation.validEdit(source, valid))
                val invalid = listOf(
                    valid.copy(crop = ProfilePhotoCrop(x = Float.NaN)),
                    valid.copy(crop = ProfilePhotoCrop(y = Float.POSITIVE_INFINITY)),
                    valid.copy(crop = ProfilePhotoCrop(zoom = Float.NEGATIVE_INFINITY)),
                    valid.copy(crop = ProfilePhotoCrop(x = -1f)),
                    valid.copy(crop = ProfilePhotoCrop(zoom = 4.1f)),
                    valid.copy(fromMillis = -1), valid.copy(tillMillis = 4_001),
                    valid.copy(tillMillis = 1_999), valid.copy(coverMillis = 999),
                    valid.copy(coverMillis = 3_000), valid.copy(tillMillis = Long.MIN_VALUE),
                )
                invalid.forEach { edit ->
                    assertEquals(ProfilePhotoVideoFailure.Reason.INVALID_EDIT,
                        assertFailsWith<ProfilePhotoVideoFailure> { preparation.prepare(source, edit) }.reason)
                }
                assertEquals(2, calls.get())
                assertTrue(ProfilePhotoVideoPreparation.cropFilter(source, ProfilePhotoCrop(x = 1f, zoom = 2f))
                    .startsWith("scale=240:60:flags=area,setsar=1,crop=30:30:210:15:exact=1,"))
            } finally { source.close() }
        }
    }

    @Test fun sameLengthChangedOwnedBytesAreRejectedEvenWhenMtimeWasRestored() = runBlocking {
        withFiles { original, owned ->
            val calls = AtomicInteger()
            val preparation = fakePreparation(owned) { calls.incrementAndGet(); CompletedProcess(probeText()) }
            val source = preparation.read(original.toString())
            try {
                val mtime = Files.getLastModifiedTime(source.path)
                val changed = Files.readAllBytes(source.path).also { it[0] = (it[0].toInt() xor 1).toByte() }
                Files.write(source.path, changed)
                Files.setLastModifiedTime(source.path, mtime)
                val failure = assertFailsWith<ProfilePhotoVideoFailure> {
                    preparation.prepare(source, ProfilePhotoVideoEdit(ProfilePhotoCrop(), 1_000, 3_000, 2_999))
                }
                assertEquals(ProfilePhotoVideoFailure.Reason.PREPARATION_FAILED, failure.reason)
                assertEquals(2, calls.get(), "A changed owned source must fail before any encode command.")
                assertNull(failure.cause)
                assertFalse(failure.toString().contains(source.path.toString()))
            } finally { source.close() }
        }
    }

    @Test fun cancellationKillsAProbeThatNeverWritesAndDiscardsItsOwnedCopy() = runBlocking {
        withFiles { original, owned ->
            val process = AtomicReference<HangingProcess?>()
            val preparation = fakePreparation(owned) { HangingProcess().also(process::set) }
            val job = async(Dispatchers.Default) { preparation.read(original.toString()) }
            withTimeout(2_000) { while (process.get() == null) delay(5) }
            assertEquals(1L, Files.list(owned).use { it.count() })
            job.cancelAndJoin()
            assertTrue(assertNotNull(process.get()).destroyed)
            assertEquals(0L, Files.list(owned).use { it.count() })
            assertTrue(Files.exists(original))
        }
    }

    @Test fun cancellingAQueuedWorkerDoesNotOccupyASecondProcessSlot() = runBlocking {
        withFiles { original, owned ->
            val calls = AtomicInteger()
            val process = AtomicReference<HangingProcess?>()
            val preparation = fakePreparation(owned) {
                calls.incrementAndGet(); HangingProcess().also(process::set)
            }
            val first = async(Dispatchers.Default) { preparation.read(original.toString()) }
            withTimeout(2_000) { while (process.get() == null) delay(5) }
            val second = async(Dispatchers.Default) { preparation.read(original.toString()) }
            delay(30)
            second.cancelAndJoin()
            assertEquals(1, calls.get())
            assertEquals(1L, Files.list(owned).use { it.count() })
            first.cancelAndJoin()
            assertEquals(0L, Files.list(owned).use { it.count() })
        }
    }

    @Test fun anEncodingWorkerRetainsTheSourceAfterEditorCloseAndCancellationReleasesBothFiles() = runBlocking {
        withFiles { original, owned ->
            val process = AtomicReference<HangingProcess?>()
            val preparation = fakePreparation(owned) { command ->
                if ("-c:v" in command) HangingProcess().also(process::set) else CompletedProcess(probeText())
            }
            val source = preparation.read(original.toString())
            try {
                val job = async(Dispatchers.Default) {
                    preparation.prepare(source, ProfilePhotoVideoEdit(ProfilePhotoCrop(), 1_000, 3_000, 2_999))
                }
                withTimeout(2_000) { while (process.get() == null) delay(5) }
                source.close()
                assertTrue(Files.exists(source.path), "A running encoder owns its own source lease.")
                assertEquals(2L, Files.list(owned).use { it.count() })
                job.cancelAndJoin()
                assertTrue(assertNotNull(process.get()).destroyed)
                assertEquals(0L, Files.list(owned).use { it.count() })
                assertTrue(Files.exists(original))
            } finally { source.close() }
        }
    }

    @Test fun delayedExitAfterKillRetainsBothFilesAndTheOnlySlotUntilTerminationIsConfirmed() = runBlocking {
        withFiles { original, owned ->
            val calls = AtomicInteger()
            val started = AtomicReference<DelayedExitProcess?>()
            val delayed = DelayedExitProcess()
            val preparation = fakePreparation(owned) { command ->
                calls.incrementAndGet()
                if ("-c:v" in command) delayed.also(started::set) else CompletedProcess(probeText())
            }
            val source = preparation.read(original.toString())
            val encoding = async(Dispatchers.Default) {
                preparation.prepare(source, ProfilePhotoVideoEdit(ProfilePhotoCrop(), 1_000, 3_000, 2_999))
            }
            var queued: kotlinx.coroutines.Deferred<ProfilePhotoVideoSource>? = null
            var next: ProfilePhotoVideoSource? = null
            try {
                withTimeout(2_000) { while (started.get() == null) delay(5) }
                source.close()
                withTimeout(3_000) { encoding.cancelAndJoin() }
                assertTrue(delayed.killed)
                assertTrue(delayed.isAlive())
                assertTrue(Files.exists(source.path))
                assertEquals(2L, Files.list(owned).use { it.count() },
                    "Both the source and partially written output must outlive an unconfirmed child exit.")
                val waiting = async(Dispatchers.Default) { preparation.read(original.toString()) }
                queued = waiting
                delay(50)
                assertEquals(3, calls.get(), "An unconfirmed child keeps the global slot after its caller has cancelled.")
                assertEquals(2L, Files.list(owned).use { it.count() })
                delayed.confirmExit()
                next = withTimeout(2_000) { waiting.await() }
                assertEquals(5, calls.get())
                assertFalse(Files.exists(source.path))
                assertEquals(1L, Files.list(owned).use { it.count() }, "Only the newly selected source remains.")
            } finally {
                delayed.confirmExit()
                encoding.cancelAndJoin()
                queued?.cancelAndJoin()
                next?.close(); source.close()
                withTimeout(2_000) { while (Files.list(owned).use { it.count() } != 0L) delay(5) }
            }
        }
    }

    @Test fun timeoutOversizedProbeAndNativeFailureAreBoundedAndRedacted() = runBlocking {
        withFiles { original, owned ->
            val timedOut = HangingProcess()
            val preparation = ProfilePhotoVideoPreparation({ Path.of("ffmpeg.exe") }, { Path.of("ffprobe.exe") },
                { timedOut }, { owned }, processTimeoutMillis = 30)
            assertEquals(ProfilePhotoVideoFailure.Reason.INVALID_SOURCE,
                assertFailsWith<ProfilePhotoVideoFailure> { preparation.read(original.toString()) }.reason)
            assertTrue(timedOut.destroyed)
            val tooMuch = fakePreparation(owned) { CompletedProcess("a".repeat(65_537)) }
            assertFailsWith<ProfilePhotoVideoFailure> { tooMuch.read(original.toString()) }
            val nativeFailure = fakePreparation(owned) { error("secret-path-and-native-output") }
            val failure = assertFailsWith<ProfilePhotoVideoFailure> { nativeFailure.read(original.toString()) }
            assertNull(failure.cause)
            assertEquals("INVALID_SOURCE", failure.message)
            assertEquals(0L, Files.list(owned).use { it.count() })
        }
    }

    @Test fun nonFiniteOrOutOfRangeOutputDescriptorsDoNotReachNativeProbe() {
        var calls = 0
        val preparation = ProfilePhotoVideoPreparation({ null }, { calls++; null })
        val lease = ownedAttachmentLease {}
        val base = SettingsPreparedProfileAnimation("synthetic-redacted.mp4", 1, 800, 1_000, .5, "00".repeat(32), lease)
        try {
            listOf(base.copy(mainFrameTimestampSeconds = Double.NaN), base.copy(mainFrameTimestampSeconds = Double.POSITIVE_INFINITY),
                base.copy(mainFrameTimestampSeconds = -1.0), base.copy(mainFrameTimestampSeconds = 1.0),
                base.copy(mainFrameTimestampSeconds = 1.001),
                base.copy(size = 2_000_001), base.copy(dimension = 801), base.copy(durationMillis = 9_601),
                base.copy(sha256 = "bad")).forEach { assertFalse(preparation.validate(it)) }
            assertEquals(0, calls)
            assertEquals("SettingsPreparedProfileAnimation(<redacted>)", base.toString())
        } finally { lease.release() }
    }

    private fun fakePreparation(owned: Path, start: (List<String>) -> Process) = ProfilePhotoVideoPreparation(
        { Path.of("ffmpeg.exe") }, { Path.of("ffprobe.exe") }, start, { owned }, processTimeoutMillis = 2_000)

    private suspend fun withFiles(block: suspend (Path, Path) -> Unit) {
        val original = Files.createTempFile("synthetic-profile-video-source-", ".bin")
        val owned = Files.createTempDirectory("synthetic-profile-video-owned-")
        Files.write(original, byteArrayOf(10, 20, 30, 40))
        try { block(original, owned) }
        finally {
            // Do not hide leaked worker files with a recursive cleanup; each test must return its leases.
            Files.deleteIfExists(original)
            Files.deleteIfExists(owned)
        }
    }

    private fun probeText() = """
        [STREAM]
        index=0
        codec_type=video
        codec_name=synthetic-codec
        width=120
        height=60
        sample_aspect_ratio=2:1
        r_frame_rate=24/1
        nb_read_frames=96
        [/STREAM]
        [FORMAT]
        format_name=synthetic-container
        duration=4.000
        [/FORMAT]
    """.trimIndent()

    private class CompletedProcess(text: String) : Process() {
        private val input = ByteArrayInputStream(text.toByteArray())
        override fun getInputStream() = input
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun waitFor() = 0
        override fun waitFor(timeout: Long, unit: TimeUnit) = true
        override fun exitValue() = 0
        override fun destroy() = Unit
        override fun destroyForcibly(): Process = this
        override fun isAlive() = false
    }

    private class HangingProcess : Process() {
        @Volatile var destroyed = false
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun waitFor(): Int { while (!destroyed) Thread.sleep(5); return 1 }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            if (!destroyed) Thread.sleep(minOf(10L, unit.toMillis(timeout)).coerceAtLeast(1))
            return destroyed
        }
        override fun exitValue(): Int { check(destroyed); return 1 }
        override fun destroy() { destroyed = true }
        override fun destroyForcibly(): Process { destroyed = true; return this }
        override fun isAlive() = !destroyed
    }

    private class DelayedExitProcess : Process() {
        private val exited = CountDownLatch(1)
        @Volatile var killed = false
        fun confirmExit() = exited.countDown()
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun waitFor(): Int { exited.await(); return 1 }
        override fun waitFor(timeout: Long, unit: TimeUnit) = exited.await(timeout, unit)
        override fun exitValue(): Int {
            if (exited.count != 0L) throw IllegalThreadStateException()
            return 1
        }
        override fun destroy() { killed = true }
        override fun destroyForcibly(): Process { killed = true; return this }
        override fun isAlive() = exited.count != 0L
    }
}

/** The existing MediaDecoderSmokeTest class-name exclusion enables this class only with -PmediaSmoke. */
class MediaDecoderSmokeTestProfilePhotoVideo {
    @Test fun actualNonMp4SourceAboveTwoMegabytesProducesCroppedTrimmedSilentVideoWithALateCover() = runBlocking {
        val ffmpeg = assertNotNull(MediaDecoder.findFfmpeg())
        val original = Files.createTempFile("synthetic-profile-raw-", ".nut")
        var source: ProfilePhotoVideoSource? = null
        var animation: SettingsPreparedProfileAnimation? = null
        try {
            MediaTools.capture(ffmpeg, listOf("-nostdin", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i",
                "color=c=red:s=160x120:r=12:d=4,drawbox=x=80:y=0:w=80:h=120:color=green:t=fill," +
                    "drawbox=x=0:y=0:w=80:h=120:color=blue:t=fill:enable='gte(t,2)'",
                "-c:v", "rawvideo", "-threads", "1", "-pix_fmt", "rgb24", "-f", "nut", "-y", original.toString()))
            assertTrue(Files.size(original) > 2_000_000, "The actual source fixture must exercise the larger input budget.")
            val selected = readProfilePhotoVideoSource(original.toString()).also { source = it }
            assertEquals(160, selected.width)
            assertEquals(120, selected.height)
            Files.write(original, byteArrayOf(1, 2, 3)) // The immutable owned selection must still encode.
            val prepared = prepareProfilePhotoVideo(selected,
                ProfilePhotoVideoEdit(ProfilePhotoCrop(x = 0f, zoom = 2f), 1_500, 3_500, 3_499)).also { animation = it }
            assertEquals(800, prepared.dimension)
            assertEquals(2_000L, prepared.durationMillis)
            assertEquals(1.999, prepared.mainFrameTimestampSeconds, .000001)
            assertTrue(prepared.size in 1..2_000_000)
            assertTrue(validatePreparedProfileAnimation(prepared))
            assertEquals(ProfilePhotoVideoFailure.Reason.TOOLS_MISSING,
                assertFailsWith<ProfilePhotoVideoFailure> {
                    ProfilePhotoVideoPreparation(findFfprobe = { null }).validate(prepared)
                }.reason)
            assertFalse(validatePreparedProfileAnimation(prepared.copy(mainFrameTimestampSeconds = Double.NaN)))
            assertFalse(validatePreparedProfileAnimation(prepared.copy(mainFrameTimestampSeconds = prepared.durationMillis / 1_000.0)))
            assertFalse(validatePreparedProfileAnimation(prepared.copy(durationMillis = 1_999)))
            assertColor(frameColor(ffmpeg, Path.of(prepared.path), .1), red = true)
            assertColor(frameColor(ffmpeg, Path.of(prepared.path), 1.9), red = false)
            val cover = prepareProfilePhotoVideoCover(prepared)
            try {
                assertEquals(320, cover.dimension)
                assertTrue(validatePreparedProfilePhoto(cover))
                val pixels = assertNotNull(ImageIO.read(Path.of(cover.path).toFile()))
                assertColor(pixels.getRGB(160, 160), red = false)
                assertTrue(Files.exists(Path.of(prepared.path)), "The cover owns its JPEG independently from the animation.")
            } finally { cover.lease.release() }
            assertFalse(Files.exists(Path.of(cover.path)))
            val right = prepareProfilePhotoVideo(selected,
                ProfilePhotoVideoEdit(ProfilePhotoCrop(x = 1f, zoom = 2f), 1_500, 3_500, 3_499))
            try {
                val color = frameColor(ffmpeg, Path.of(right.path), 1.9)
                assertTrue((color ushr 8 and 255) > 90 && (color and 255) < 60 && (color ushr 16 and 255) < 60,
                    "Moving the crop to the right must encode the actual green source pixels.")
            } finally { right.lease.release() }
            val output = Path.of(prepared.path)
            val retained = assertNotNull(prepared.lease.retain())
            prepared.lease.release()
            assertTrue(Files.exists(output), "A retained upload must survive editor disposal.")
            val mtime = Files.getLastModifiedTime(output)
            val changed = Files.readAllBytes(output).also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            Files.write(output, changed); Files.setLastModifiedTime(output, mtime)
            assertFalse(validatePreparedProfileAnimation(prepared.copy(lease = retained)),
                "Even equal-size, equal-mtime corruption must fail SHA-256 under a live upload handle.")
            retained.release()
            assertFalse(Files.exists(output))
        } finally {
            animation?.lease?.release(); source?.close(); Files.deleteIfExists(original)
        }
    }

    @Test fun actualDisplayRotationAndAnamorphicPixelsAreNormalizedBeforeTheSquareCrop() = runBlocking {
        val ffmpeg = assertNotNull(MediaDecoder.findFfmpeg())
        val original = Files.createTempFile("synthetic-profile-anamorphic-", ".mp4")
        val rotated = Files.createTempFile("synthetic-profile-rotated-", ".mp4")
        var source: ProfilePhotoVideoSource? = null
        var prepared: SettingsPreparedProfileAnimation? = null
        try {
            MediaTools.capture(ffmpeg, listOf("-nostdin", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i",
                "color=c=red:s=80x40:r=10:d=2,drawbox=x=40:y=0:w=40:h=40:color=blue:t=fill,setsar=2/1",
                "-c:v", "libx264", "-threads", "1", "-pix_fmt", "yuv420p", "-y", original.toString()))
            MediaTools.capture(ffmpeg, listOf("-nostdin", "-hide_banner", "-loglevel", "error", "-display_rotation:v:0", "90",
                "-i", original.toString(), "-c", "copy", "-y", rotated.toString()))
            val rotation = MediaTools.capture(assertNotNull(MediaTools.find("ffprobe.exe")), listOf("-v", "error",
                "-show_entries", "stream_side_data=rotation", "-of", "default", "-i", rotated.toString()))
            assertTrue("rotation=90" in rotation, "The fixture must contain a real display matrix.")
            val selected = readProfilePhotoVideoSource(rotated.toString()).also { source = it }
            assertEquals(40, selected.width)
            assertEquals(160, selected.height)
            val result = prepareProfilePhotoVideo(selected,
                ProfilePhotoVideoEdit(ProfilePhotoCrop(y = 1f), 0, 2_000, 1_999)).also { prepared = it }
            assertTrue(validatePreparedProfileAnimation(result))
            assertColor(frameColor(ffmpeg, Path.of(result.path), .5), red = true)
        } finally {
            prepared?.lease?.release(); source?.close()
            Files.deleteIfExists(original); Files.deleteIfExists(rotated)
        }
    }

    @Test fun finalValidatorRejectsRealWrongCodecAudioAndARenamedStaticImage() {
        val ffmpeg = assertNotNull(MediaDecoder.findFfmpeg())
        val file = Files.createTempFile("synthetic-profile-invalid-output-", ".mp4")
        val lease = ownedAttachmentLease {}
        try {
            fun descriptor() = SettingsPreparedProfileAnimation(file.toString(), Files.size(file), 800, 1_000, .5,
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))
                    .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }, lease)
            MediaTools.capture(ffmpeg, listOf("-nostdin", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i",
                "color=c=red:s=800x800:r=30:d=1", "-c:v", "mpeg4", "-threads", "1", "-an", "-y", file.toString()))
            assertFalse(validatePreparedProfileAnimation(descriptor()))
            MediaTools.capture(ffmpeg, listOf("-nostdin", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i",
                "color=c=red:s=800x800:r=30:d=1", "-f", "lavfi", "-i", "sine=frequency=440:duration=1",
                "-c:v", "libx264", "-threads", "1", "-c:a", "aac", "-shortest", "-y", file.toString()))
            assertFalse(validatePreparedProfileAnimation(descriptor()))
            ImageIO.write(java.awt.image.BufferedImage(800, 800, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", file.toFile())
            assertFalse(validatePreparedProfileAnimation(descriptor()), "Renaming a still image to MP4 must not count as encoding.")
        } finally { lease.release(); Files.deleteIfExists(file) }
    }

    private fun frameColor(ffmpeg: Path, file: Path, seconds: Double): Int {
        val frame = Files.createTempFile("synthetic-profile-frame-", ".png")
        try {
            MediaTools.capture(ffmpeg, listOf("-nostdin", "-hide_banner", "-loglevel", "error", "-ss", seconds.toString(),
                "-i", file.toString(), "-an", "-sn", "-dn", "-frames:v", "1", "-threads", "1", "-y", frame.toString()))
            val pixels = assertNotNull(ImageIO.read(frame.toFile()))
            return pixels.getRGB(pixels.width / 2, pixels.height / 2)
        } finally { Files.deleteIfExists(frame) }
    }

    private fun assertColor(color: Int, red: Boolean) {
        val r = color ushr 16 and 255
        val b = color and 255
        assertTrue(if (red) r > 180 && b < 60 else b > 180 && r < 60,
            "The encoded selected frame must contain the actual ${if (red) "red" else "blue"} source pixels.")
    }
}
