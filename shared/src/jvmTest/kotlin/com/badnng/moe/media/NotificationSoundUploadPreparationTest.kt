package com.badnng.moe.media

import com.badnng.moe.settings.*
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

class NotificationSoundUploadPreparationTest {
    @Test fun copiedBytesHashAndLeaseHaveIndependentOwnershipAndOnlyTheOwnedSeekableFileIsDecoded() = runBlocking {
        withFiles { root, source, bytes ->
            val stage = Files.createDirectory(root.resolve("stages"))
            var command = emptyList<String>()
            val process = FakeProcess(ByteArrayInputStream(ByteArray(262_412)))
            val preparation = preparation(stage) { command = it; process }
            val result = preparation.prepare(source.toString(), limits(bytes.size.toLong(), 2)) { true }
            assertNull(result.error)
            val prepared = assertNotNull(result.value)
            val copy = Path.of(prepared.upload.path)
            assertNotEquals(source, copy)
            assertEquals(stage, copy.parent.parent)
            assertEquals(source.fileName, copy.fileName, "The native InputFileLocal path must retain the user's original basename.")
            assertContentEquals(bytes, Files.readAllBytes(copy))
            assertEquals(bytes.size.toLong(), prepared.upload.sizeBytes)
            assertEquals(1_367L, prepared.upload.durationMillis)
            assertTrue(validatePreparedNotificationSoundUpload(prepared.upload))
            assertEquals(copy.toString(), command[command.indexOf("-i") + 1])
            assertEquals("mp3", command[command.indexOf("-format_whitelist") + 1])
            assertEquals("mp3,mp3float", command[command.indexOf("-codec_whitelist") + 1])
            assertEquals("file,pipe", command[command.indexOf("-protocol_whitelist") + 1])
            assertFalse("-t" in command)
            val retained = assertNotNull(prepared.lease.retain())
            prepared.lease.release()
            assertTrue(Files.exists(copy))
            retained.release(); retained.release()
            assertFalse(Files.exists(copy))
            assertFalse(Files.exists(copy.parent))
            assertContentEquals(bytes, Files.readAllBytes(source))
            assertTrue(process.destroyed.get())
        }
    }

    @Test fun unknownLimitsAndInactiveOwnershipPerformNoSelectionPreparationOrProcessWork() = runBlocking {
        withFiles { root, source, _ ->
            val stage = Files.createDirectory(root.resolve("stages"))
            var starts = 0
            val preparation = preparation(stage) { starts++; FakeProcess(ByteArrayInputStream(ByteArray(192))) }
            for (unknown in listOf(NotificationSoundLimits(), NotificationSoundLimits(100, 1, null),
                NotificationSoundLimits(null, 1, 1), NotificationSoundLimits(100, null, 1), NotificationSoundLimits(100, 1, 0))) {
                assertEquals(SettingsError.NOT_READY, preparation.prepare(source.toString(), unknown) { true }.error)
            }
            assertEquals(SettingsError.NOT_READY, preparation.prepare(source.toString(), limits()) { false }.error)
            assertEquals(0, starts)
            assertEquals(0L, fileCount(stage))
        }
    }

    @Test fun byteLimitsExtensionsHeaderMismatchAndPlaylistsCannotReachTheDecoder() = runBlocking {
        withFiles { root, source, bytes ->
            val stage = Files.createDirectory(root.resolve("stages"))
            var starts = 0
            val preparation = preparation(stage) { starts++; FakeProcess(ByteArrayInputStream(ByteArray(192))) }
            assertEquals(SettingsError.INVALID_VALUE, preparation.prepare(source.toString(), limits(bytes.size - 1L)) { true }.error)
            val playlist = root.resolve("playlist.mp3").also { Files.writeString(it, "#EXTM3U\nhttps://invalid.example/secret.mp3") }
            val wrong = root.resolve("wrong.ogg").also { Files.write(it, bytes) }
            val wave = root.resolve("wrong.wav").also { Files.write(it, bytes) }
            for (path in listOf(playlist, wrong, wave, root)) {
                assertEquals(SettingsError.INVALID_VALUE, preparation.prepare(path.toString(), limits()) { true }.error)
            }
            assertEquals(SettingsError.INVALID_VALUE, preparation.prepare("https://invalid.example/secret.mp3", limits()) { true }.error)
            for (path in listOf(source.toString() + " ", source.toString() + ".", source.parent.resolve("CON.mp3").toString())) {
                assertEquals(SettingsError.INVALID_VALUE, preparation.prepare(path, limits()) { true }.error,
                    "Unsupported basename spelling must fail rather than be trimmed or rewritten for upload.")
            }
            assertEquals(0, starts)
            assertEquals(0L, fileCount(stage))
        }
    }

    @Test fun exactFrameBudgetRejectsTheFirstExcessFrameAndFailuresDeleteOnlyTheirOwnStage() = runBlocking {
        withFiles { root, source, bytes ->
            val stage = Files.createDirectory(root.resolve("stages"))
            val allowed = preparation(stage) { FakeProcess(ByteArrayInputStream(ByteArray(192_000))) }
                .prepare(source.toString(), limits(seconds = 1)) { true }
            assertEquals(1_000L, allowed.value?.upload?.durationMillis)
            allowed.value?.lease?.release()
            for (outcome in listOf(192_004 to 0, 3 to 0, 192 to 1, 0 to 0)) {
                val result = preparation(stage) { FakeProcess(ByteArrayInputStream(ByteArray(outcome.first)), outcome.second) }
                    .prepare(source.toString(), limits(seconds = 1)) { true }
                assertNotNull(result.error)
                assertNull(result.value)
                assertEquals(0L, fileCount(stage))
                assertContentEquals(bytes, Files.readAllBytes(source))
            }
        }
    }

    @Test fun validatorRejectsSameSizeReplacementMissingHashAndReleasedStaging() = runBlocking {
        withFiles { root, source, bytes ->
            val stage = Files.createDirectory(root.resolve("stages"))
            val prepared = assertNotNull(preparation(stage) { FakeProcess(ByteArrayInputStream(ByteArray(192))) }
                .prepare(source.toString(), limits()) { true }.value)
            assertTrue(validatePreparedNotificationSoundUpload(prepared.upload))
            assertFalse(validatePreparedNotificationSoundUpload(prepared.upload.copy(sha256 = "")))
            val copy = Path.of(prepared.upload.path)
            Files.write(copy, bytes.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() })
            assertEquals(prepared.upload.sizeBytes, Files.size(copy))
            assertFalse(validatePreparedNotificationSoundUpload(prepared.upload))
            prepared.lease.release()
            assertFalse(validatePreparedNotificationSoundUpload(prepared.upload))
            assertTrue(Files.exists(source))
        }
    }

    @Test fun cancellationTimeoutAndInactiveOwnershipRetireTheDecoderAndEveryOwnedTemporaryFile() = runBlocking {
        withFiles { root, source, bytes ->
            val stage = Files.createDirectory(root.resolve("stages"))
            for (mode in 0..2) {
                val started = CompletableDeferred<Unit>()
                val active = AtomicBoolean(true)
                val input = BlockingInput()
                val process = FakeProcess(input)
                val preparation = NotificationSoundUploadPreparation({ Path.of("synthetic-ffmpeg.exe") },
                    { started.complete(Unit); process }, { stage }, timeoutMillis = if (mode == 2) 250 else 2_000)
                val result = async(Dispatchers.Default) { preparation.prepare(source.toString(), limits(), active::get) }
                withTimeout(2_000) { started.await() }
                if (mode == 0) withTimeout(2_000) { result.cancelAndJoin() }
                else {
                    if (mode == 1) active.set(false)
                    val failed = withTimeout(3_000) { result.await() }
                    assertEquals(if (mode == 1) SettingsError.NOT_READY else SettingsError.REQUEST_FAILED, failed.error)
                }
                assertTrue(process.destroyed.get())
                assertTrue(input.closed.get())
                assertEquals(0L, fileCount(stage))
                assertContentEquals(bytes, Files.readAllBytes(source))
            }
        }
    }

    @Test fun originalMp3SeekableDecodeHasItsRealGaplessDurationAndPreservesTheOriginalEncoding() = runBlocking {
        val executable = MediaDecoder.findFfmpeg()
        assumeTrue("Prepare the bundled -PmediaSmoke tool for the seekable recording fixture", executable != null)
        withFiles { root, source, bytes ->
            val stage = Files.createDirectory(root.resolve("stages"))
            val preparation = NotificationSoundUploadPreparation(findFfmpeg = { executable }, tempDirectory = { stage })
            val result = preparation.prepare(source.toString(), limits(bytes.size.toLong(), 2)) { true }
            assertNull(result.error)
            val prepared = assertNotNull(result.value)
            assertEquals(1_367L, prepared.upload.durationMillis, "65,603 actual frames round up to 1367 ms, without pipe tail padding.")
            assertContentEquals(bytes, Files.readAllBytes(Path.of(prepared.upload.path)))
            prepared.lease.release()
            assertEquals(SettingsError.INVALID_VALUE, preparation.prepare(source.toString(), limits(seconds = 1)) { true }.error)
            val broken = root.resolve("broken.mp3").also { Files.write(it, byteArrayOf(73, 68, 51, 4, 0, 0, 0, 0, 0, 0)) }
            assertNotNull(preparation.prepare(broken.toString(), limits()) { true }.error)
            assertEquals(0L, fileCount(stage))
        }
    }

    @Test fun realOggOpusIsAcceptedButAnotherOggCodecCannotBypassTheExplicitWhitelist() = runBlocking {
        val executable = MediaDecoder.findFfmpeg()
        assumeTrue("Prepare the bundled -PmediaSmoke tool for the OGG codec fixture", executable != null)
        withFiles { root, source, _ ->
            val stage = Files.createDirectory(root.resolve("stages"))
            val preparation = NotificationSoundUploadPreparation(findFfmpeg = { executable }, tempDirectory = { stage })
            suspend fun encoded(codec: String, name: String): SettingsResult<SettingsPreparedNotificationSound> {
                val target = root.resolve(name)
                MediaTools.capture(executable!!, listOf("-nostdin", "-hide_banner", "-loglevel", "error",
                    "-protocol_whitelist", "file,pipe", "-f", "mp3", "-i", source.toString(), "-map", "0:a:0",
                    "-vn", "-sn", "-dn", "-c:a", codec, "-f", "ogg", target.toString()))
                return preparation.prepare(target.toString(), limits()) { true }
            }
            val opus = encoded("libopus", "valid.oga")
            assertNull(opus.error)
            val accepted = assertNotNull(opus.value)
            assertTrue(accepted.upload.durationMillis in 1_360L..1_380L)
            accepted.lease.release()
            assertNotNull(encoded("flac", "not-whitelisted.ogg").error)
            assertEquals(0L, fileCount(stage))
        }
    }

    @Test fun identicalUnicodeBasenamesRetainTheirSpellingInDifferentOwnersAndNeverOverwriteEachOther() = runBlocking {
        withFiles { root, _, bytes ->
            val stage = Files.createDirectory(root.resolve("stages"))
            val basename = "  我的 铃声_${"名".repeat(24)}.MPEG3"
            val first = Files.createDirectory(root.resolve("first")).resolve(basename).also { Files.write(it, bytes) }
            val second = Files.createDirectory(root.resolve("second")).resolve(basename).also { Files.write(it, bytes) }
            val preparation = preparation(stage) { FakeProcess(ByteArrayInputStream(ByteArray(262_412))) }
            val one = assertNotNull(preparation.prepare(first.toString(), limits()) { true }.value)
            val two = assertNotNull(preparation.prepare(second.toString(), limits()) { true }.value)
            val onePath = Path.of(one.upload.path)
            val twoPath = Path.of(two.upload.path)
            assertEquals(basename, onePath.fileName.toString())
            assertEquals(basename, twoPath.fileName.toString())
            assertNotEquals(onePath.parent, twoPath.parent)
            assertContentEquals(bytes, Files.readAllBytes(onePath))
            assertContentEquals(bytes, Files.readAllBytes(twoPath))
            one.lease.release()
            assertFalse(Files.exists(onePath.parent))
            assertTrue(validatePreparedNotificationSoundUpload(two.upload))
            assertTrue(Files.exists(twoPath))
            two.lease.release()
            assertFalse(Files.exists(twoPath.parent))
            assertEquals(0L, fileCount(stage))
            assertContentEquals(bytes, Files.readAllBytes(first))
            assertContentEquals(bytes, Files.readAllBytes(second))
        }
    }

    @Test fun failedDecodeDeletesOnlyItsCreatedChildAndLeavesUnexpectedDirectoryContentsUntouched() = runBlocking {
        withFiles { root, source, bytes ->
            val stage = Files.createDirectory(root.resolve("stages"))
            var staged: Path? = null
            var foreign: Path? = null
            val preparation = preparation(stage) { command ->
                val target = Path.of(command[command.indexOf("-i") + 1])
                staged = target
                foreign = target.parent.resolve("foreign.txt").also { Files.writeString(it, "unrelated content") }
                FakeProcess(ByteArrayInputStream(ByteArray(0)), 1)
            }
            val result = preparation.prepare(source.toString(), limits()) { true }
            assertNotNull(result.error)
            assertNull(result.value)
            val target = assertNotNull(staged)
            val unrelated = assertNotNull(foreign)
            assertFalse(Files.exists(target))
            assertTrue(Files.exists(target.parent))
            assertEquals("unrelated content", Files.readString(unrelated))
            assertEquals(listOf(unrelated), Files.list(target.parent).use { it.toList() })
            assertContentEquals(bytes, Files.readAllBytes(source))
        }
    }

    private suspend fun withFiles(block: suspend (Path, Path, ByteArray) -> Unit) {
        val root = Files.createTempDirectory("notification-sound-upload-").toAbsolutePath().normalize()
        try {
            val bytes = checkNotNull(javaClass.getResourceAsStream("/telegram-sounds/msg_incoming.mp3")).use { it.readBytes() }
            val source = root.resolve("original.mp3").also { Files.write(it, bytes) }
            block(root, source, bytes)
        } finally { Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { path ->
            check(path.toAbsolutePath().normalize().startsWith(root)); Files.deleteIfExists(path)
        } } }
    }

    private class FakeProcess(private val input: InputStream, private val code: Int = 0) : Process() {
        val destroyed = AtomicBoolean()
        override fun getInputStream() = input
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getErrorStream() = ByteArrayInputStream(ByteArray(0))
        override fun waitFor() = code
        override fun exitValue() = code
        override fun destroy() { destroyed.set(true); input.close() }
        override fun destroyForcibly(): Process { destroy(); return this }
    }
    private class BlockingInput : InputStream() {
        private val ready = CountDownLatch(1)
        val closed = AtomicBoolean()
        override fun read(): Int { while (!closed.get()) try { ready.await() } catch (_: InterruptedException) {}; return -1 }
        override fun close() { closed.set(true); ready.countDown() }
    }
    private companion object {
        fun limits(bytes: Long = 1_000_000, seconds: Int = 2) = NotificationSoundLimits(bytes, seconds, 100)
        fun preparation(stage: Path, process: (List<String>) -> Process) =
            NotificationSoundUploadPreparation({ Path.of("synthetic-ffmpeg.exe") }, process, { stage })
        fun fileCount(path: Path) = Files.list(path).use { it.count() }
    }
}
