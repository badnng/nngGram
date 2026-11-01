package com.badnng.moe.media

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Path
import kotlin.test.*

class SavedMediaPublicationTest {
    @Test fun unsupportedHardLinkAndPartialCreateNewFailureReportsPossibleFileEvenWithoutFileKey() = fixture { root ->
        val source = root.resolve("source").also { Files.writeString(it, "source bytes") }
        val output = Files.createDirectories(root.resolve("output"))
        val existing = output.resolve("earlier.bin").also { Files.writeString(it, "earlier") }
        val io = partialWriteIo()
        assertFailsWith<SaveTargetPublicationUncertain> {
            SavedMediaFiles.saveUniqueWithIo(source, output, "copy.bin", NativeSaveTargets.capture(), io)
        }
        assertEquals("sour", Files.readString(output.resolve("copy.bin")))
        assertEquals("source bytes", Files.readString(source))
        assertEquals("earlier", Files.readString(existing))
    }

    @Test fun failureAfterHardLinkPublicationAndTemporaryCleanupNeverMasksThePossibleTarget() = fixture { root ->
        val source = root.resolve("source").also { Files.writeString(it, "source bytes") }
        val output = Files.createDirectories(root.resolve("output"))
        val io = object : SavedMediaFileIo by NioSavedMediaFileIo {
            override fun deleteTemporary(temporary: Path) { throw IOException("synthetic cleanup failure") }
        }
        assertFailsWith<SaveTargetPublicationUncertain> {
            SavedMediaFiles.saveUniqueWithIo(source, output, "copy.bin", NativeSaveTargets.capture(), io)
        }
        assertEquals("source bytes", Files.readString(output.resolve("copy.bin")))
        assertEquals("source bytes", Files.readString(source))
    }

    @Test fun linkCreatedBeforeAnOrdinaryExceptionStillReportsAnUncertainPublicFile() = fixture { root ->
        val source = root.resolve("source").also { Files.writeString(it, "source bytes") }
        val output = Files.createDirectories(root.resolve("output"))
        val io = object : SavedMediaFileIo by NioSavedMediaFileIo {
            override fun createLink(target: Path, temporary: Path) {
                NioSavedMediaFileIo.createLink(target, temporary)
                throw IOException("synthetic exception after link")
            }
        }
        assertFailsWith<SaveTargetPublicationUncertain> {
            SavedMediaFiles.saveUniqueWithIo(source, output, "copy.bin", NativeSaveTargets.capture(), io)
        }
        assertEquals("source bytes", Files.readString(output.resolve("copy.bin")))
        assertEquals("source bytes", Files.readString(source))
        Files.list(output).use { assertEquals(1L, it.count()) }
    }

    @Test fun outputCloseFailureAfterFullCreateNewLeavesThePublicResultVisibleForInspection() = fixture { root ->
        val source = root.resolve("source").also { Files.writeString(it, "source bytes") }
        val output = Files.createDirectories(root.resolve("output"))
        val io = object : SavedMediaFileIo by NioSavedMediaFileIo {
            override fun createLink(target: Path, temporary: Path) { throw UnsupportedOperationException() }
            override fun createNew(target: Path): SeekableByteChannel {
                val channel = NioSavedMediaFileIo.createNew(target)
                return object : SeekableByteChannel by channel {
                    override fun close() { channel.close(); throw IOException("synthetic close failure") }
                }
            }
        }
        assertFailsWith<SaveTargetPublicationUncertain> {
            SavedMediaFiles.saveUniqueWithIo(source, output, "copy.bin", NativeSaveTargets.capture(), io)
        }
        assertEquals("source bytes", Files.readString(output.resolve("copy.bin")))
        assertEquals("source bytes", Files.readString(source))
    }

    @Test fun aCollisionExceptionDuringTheWriteDoesNotStartAnotherFile() = fixture { root ->
        val source = root.resolve("source").also { Files.writeString(it, "source bytes") }
        val output = Files.createDirectories(root.resolve("output"))
        val io = object : SavedMediaFileIo by NioSavedMediaFileIo {
            override fun createLink(target: Path, temporary: Path) { throw UnsupportedOperationException() }
            override fun createNew(target: Path): SeekableByteChannel {
                val channel = NioSavedMediaFileIo.createNew(target)
                return object : SeekableByteChannel by channel {
                    override fun write(src: ByteBuffer): Int {
                        channel.write(src)
                        throw FileAlreadyExistsException("synthetic error after creation")
                    }
                }
            }
        }
        assertFailsWith<SaveTargetPublicationUncertain> {
            SavedMediaFiles.saveUniqueWithIo(source, output, "copy.bin", NativeSaveTargets.capture(), io)
        }
        assertEquals("source bytes", Files.readString(output.resolve("copy.bin")))
        assertEquals("source bytes", Files.readString(source))
        Files.list(output).use { assertEquals(1L, it.count()) }
    }

    companion object {
        internal fun partialWriteIo(): SavedMediaFileIo = object : SavedMediaFileIo by NioSavedMediaFileIo {
            override fun createLink(target: Path, temporary: Path) { throw UnsupportedOperationException("synthetic unsupported link") }
            override fun createNew(target: Path): SeekableByteChannel {
                val underlying = NioSavedMediaFileIo.createNew(target)
                return object : SeekableByteChannel by underlying {
                    override fun write(src: ByteBuffer): Int {
                        val limit = src.limit()
                        try {
                            src.limit(minOf(src.position() + 4, limit))
                            underlying.write(src)
                        } finally { src.limit(limit) }
                        throw IOException("synthetic partial public write")
                    }
                }
            }
        }
    }

    private fun fixture(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("manual-publication-")
        try { block(root) } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { path ->
                check(path.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize()))
                Files.deleteIfExists(path)
            } }
        }
    }
}
