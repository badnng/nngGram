package com.badnng.moe.media

import com.badnng.moe.chat.AttachmentKind
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.ByteBuffer
import com.badnng.moe.chat.LocalAttachment
import com.badnng.moe.chat.ownedAttachmentLease
import kotlinx.coroutines.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class AttachmentFilesTest {
    @Test
    fun oneProbeFindsVideoAfterAudioAndKeepsTheContainerTimeline() {
        val output = """
            [STREAM]
            codec_name=aac
            codec_type=audio
            duration=4.0
            [/STREAM]
            [STREAM]
            codec_name=h264
            codec_type=video
            width=640
            height=360
            sample_aspect_ratio=1:1
            duration=3.0
            [SIDE_DATA]
            rotation=-90
            [/SIDE_DATA]
            [/STREAM]
            [FORMAT]
            format_name=mov,mp4,m4a,3gp,3g2,mj2
            duration=4.0
            [/FORMAT]
        """.trimIndent()
        val info = AttachmentFiles.parseVideoInfo(output, includeAudio = true)
        assertEquals("h264", info.codec)
        assertEquals(360, info.width)
        assertEquals(640, info.height)
        assertEquals(360.0 / 640, info.aspectRatio)
        assertEquals(4.0, info.duration)
        assertTrue(info.hasAudio)
        assertFalse(AttachmentFiles.parseVideoInfo(output, includeAudio = false).hasAudio)
    }

    @Test
    fun probeRejectsMissingVideoAndFallsBackToVideoDurationWhenContainerIsUnknown() {
        assertFailsWith<AttachmentFailure> {
            AttachmentFiles.parseVideoInfo("[STREAM]\ncodec_type=audio\nduration=4.0\n[/STREAM]", includeAudio = true)
        }
        val info = AttachmentFiles.parseVideoInfo("""
            [STREAM]
            codec_name=h264
            codec_type=video
            width=320
            height=240
            duration=2.0
            [/STREAM]
            [FORMAT]
            format_name=mp4
            duration=N/A
            [/FORMAT]
        """.trimIndent(), includeAudio = true)
        assertEquals(2.0, info.duration)
        assertFalse(info.hasAudio)
    }

    @Test
    fun attachedPicturesAreNotVideoStreamsAndDisplayDimensionsIncludeSarAndRotation() {
        val info = AttachmentFiles.parseVideoInfo("""
            [STREAM]
            index=0
            codec_type=video
            codec_name=mjpeg
            width=800
            height=800
            DISPOSITION:attached_pic=1
            [/STREAM]
            [STREAM]
            index=3
            codec_type=video
            codec_name=h264
            width=720
            height=576
            sample_aspect_ratio=16:15
            duration=3
            rotation=90
            DISPOSITION:attached_pic=0
            [/STREAM]
            [FORMAT]
            format_name=mov,mp4,m4a,3gp,3g2,mj2
            duration=4
            [/FORMAT]
        """.trimIndent(), includeAudio = true)
        assertEquals("h264", info.codec)
        assertEquals(3, info.streamIndex)
        assertEquals(576, info.displayWidth)
        assertEquals(768, info.displayHeight)
        assertEquals(4.0, info.duration, "Playback and sending retain the existing full container timeline")
        assertEquals(576.0 / 768, info.aspectRatio)
        assertFailsWith<AttachmentFailure> {
            AttachmentFiles.parseVideoInfo("[STREAM]\ncodec_type=video\ncodec_name=mjpeg\nwidth=32\nheight=32\nattached_pic=1\nduration=3\n[/STREAM]", false)
        }
    }

    @Test
    fun streamingRequiresRealMp4BrandsAndACompleteMoovBeforeTheFirstMediaBox() {
        val file = Files.createTempFile("synthetic-mp4-boxes-", ".mp4")
        fun box(type: String, data: ByteArray = ByteArray(4)): ByteArray =
            ByteBuffer.allocate(data.size + 8).putInt(data.size + 8).put(type.toByteArray()).put(data).array()
        val mp4 = box("ftyp", "isom\u0000\u0000\u0000\u0000mp42".toByteArray())
        val quicktime = box("ftyp", "qt  \u0000\u0000\u0000\u0000qt  ".toByteArray())
        val threeGp = box("ftyp", "3gp5\u0000\u0000\u0000\u0000isom".toByteArray())
        val moov = box("moov")
        val mdat = box("mdat")
        try {
            Files.write(file, mp4 + moov + mdat)
            assertTrue(isFastStartMp4(file))
            Files.write(file, mp4 + mdat + moov)
            assertTrue(inspectMp4Layout(file).isMp4, "A trailing-moov MP4 remains an allowed video container")
            assertFalse(isFastStartMp4(file), "A filename or MP4 demuxer label cannot promise early playback")
            Files.write(file, quicktime + moov + mdat)
            assertFalse(inspectMp4Layout(file).isMp4)
            assertFalse(isFastStartMp4(file))
            Files.write(file, threeGp + moov + mdat)
            assertFalse(inspectMp4Layout(file).isMp4, "An isom-compatible 3GP major brand is not an MP4 selection")
            Files.write(file, mp4 + ByteBuffer.allocate(16).putInt(1).put("moov".toByteArray()).putLong(Long.MAX_VALUE).array() + mdat)
            assertFalse(isFastStartMp4(file), "Extended boxes cannot escape the actual file")
            Files.write(file, mp4 + moov + mdat.copyOf(9))
            assertFalse(isFastStartMp4(file), "Truncated media data is not a complete fast-start file")
        } finally { Files.deleteIfExists(file) }
    }

    @Test
    fun confirmedFileIdentityRejectsAChangedSelectionAtTheSamePath() {
        val file = Files.createTempFile("attachment-version-", ".bin")
        try {
            Files.write(file, byteArrayOf(1, 2, 3))
            val selection = AttachmentFiles.inspect(file.toString(), AttachmentKind.FILE)
            assertTrue(AttachmentFiles.matchesSelection(selection))
            Files.write(file, byteArrayOf(1, 2, 3, 4))
            assertFalse(AttachmentFiles.matchesSelection(selection))
        } finally { Files.deleteIfExists(file) }
    }

    @Test
    fun aCancelledPreparationReleasesACompletedThumbnailBeforeItsResultCanReachTheDialog() = runBlocking {
        val entered = CountDownLatch(1)
        val complete = CountDownLatch(1)
        val cleaned = AtomicInteger()
        val task = launch(Dispatchers.Default) {
            prepareAttachmentSelection("synthetic-selection", AttachmentKind.VIDEO) { _, kind ->
                val result = LocalAttachment(kind, "synthetic-selection", "clip.mp4", 100, 1, 64, 48,
                    lease = ownedAttachmentLease { cleaned.incrementAndGet() })
                entered.countDown()
                // Model a process read already completing while cancellation interrupts the owner.
                var finished = false
                while (!finished) {
                    try { check(complete.await(5, TimeUnit.SECONDS)); finished = true }
                    catch (_: InterruptedException) { }
                }
                result
            }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            task.cancel()
            complete.countDown()
            withTimeout(5_000) { task.join() }
            assertTrue(task.isCancelled)
            assertEquals(1, cleaned.get())
        } finally {
            complete.countDown()
            task.cancelAndJoin()
        }
    }

    @Test
    fun aDialogCloseOwnsDeliveredAndLateWorkerFilesWithoutWaitingForAnotherComposition() {
        val source = Files.createTempFile("synthetic-selection-source-", ".mp4")
        val first = Files.createTempFile("synthetic-delivered-thumbnail-", ".jpg")
        val late = Files.createTempFile("synthetic-late-thumbnail-", ".jpg")
        val owner = AttachmentSelectionOwner()
        fun selection(thumbnail: java.nio.file.Path) = LocalAttachment(AttachmentKind.VIDEO, source.toString(), "clip.mp4", 100,
            1, 64, 48, lease = ownedAttachmentLease { Files.deleteIfExists(thumbnail) })
        try {
            assertTrue(owner.replace(selection(first)))
            // The worker has delivered, but no new keyed DisposableEffect has had a chance to apply.
            owner.close()
            assertFalse(Files.exists(first))
            assertFalse(owner.replace(selection(late)), "A disposed dialog cannot accept another completed worker")
            assertFalse(Files.exists(late))
            owner.close()
            assertTrue(Files.exists(source), "Neither handoff path owns the user's original video")
        } finally {
            owner.close()
            Files.deleteIfExists(first); Files.deleteIfExists(late); Files.deleteIfExists(source)
        }
    }

    @Test
    fun documentsKeepTheirOriginalNameAndNeverExposePathsInDiagnostics() {
        val directory = Files.createTempDirectory("attachment-test-")
        val file = directory.resolve("测试 文档.txt")
        Files.writeString(file, "synthetic document")
        val result = AttachmentFiles.inspect(file.toString(), AttachmentKind.FILE)
        assertEquals("测试 文档.txt", result.fileName)
        assertEquals(Files.size(file), result.size)
        assertFalse(result.toString().contains(file.toString()))
        assertFalse(result.toString().contains(result.fileName))
        assertFailsWith<AttachmentFailure> { AttachmentFiles.inspect(directory.toString(), AttachmentKind.FILE) }
        assertFailsWith<AttachmentFailure> { AttachmentFiles.inspect(directory.resolve("missing.txt").toString(), AttachmentKind.FILE) }
    }

    @Test
    fun suggestedNamesCannotEscapeSaveDirectoryOrUseWindowsDevices() {
        assertEquals("report.pdf", AttachmentFiles.safeName("../../report.pdf"))
        assertEquals("report.pdf", AttachmentFiles.safeName("C:\\private\\report.pdf"))
        assertEquals("_CON.txt", AttachmentFiles.safeName("CON.txt"))
        assertEquals("_lpt9", AttachmentFiles.safeName("lpt9"))
        assertEquals("下载文件", AttachmentFiles.safeName(" .. "))
        assertEquals("a_b_.txt", AttachmentFiles.safeName("a:b?.txt"))
    }

    @Test
    fun savingDoesNotOverwriteWithoutExplicitChoiceAndLeavesNoPartialFiles() {
        val directory = Files.createTempDirectory("attachment-save-")
        val source = directory.resolve("source.bin")
        val target = directory.resolve("target.bin")
        Files.writeString(source, "new synthetic content")
        Files.writeString(target, "old synthetic content")
        assertFailsWith<FileAlreadyExistsException> { AttachmentFiles.save(source, target, replaceExisting = false) }
        assertEquals("old synthetic content", Files.readString(target))
        AttachmentFiles.save(source, target, replaceExisting = true)
        assertEquals(Files.readString(source), Files.readString(target))
        AttachmentFiles.save(source, source, replaceExisting = true)
        assertEquals("new synthetic content", Files.readString(source))
        Files.list(directory).use { assertEquals(2L, it.count()) }
    }
}
