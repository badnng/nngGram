package com.badnng.moe.media

import com.sun.nio.file.ExtendedOpenOption
import androidx.compose.runtime.staticCompositionLocalOf
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale

/** Filesystem capability boundary used by both the real saver and fault-injected fixtures. */
internal interface SavedMediaFileIo {
    fun createLink(target: Path, temporary: Path)
    fun createNew(target: Path): SeekableByteChannel
    fun replace(temporary: Path, target: Path)
    fun deleteTemporary(temporary: Path)
}

internal object NioSavedMediaFileIo : SavedMediaFileIo {
    override fun createLink(target: Path, temporary: Path) { Files.createLink(target, temporary) }
    override fun createNew(target: Path): SeekableByteChannel = Files.newByteChannel(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    override fun replace(temporary: Path, target: Path) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING) }
    override fun deleteTemporary(temporary: Path) { Files.deleteIfExists(temporary) }
}

internal val LocalSavedMediaFileIo = staticCompositionLocalOf<SavedMediaFileIo> { NioSavedMediaFileIo }

/** Explicit save copies only; it never opens a saved attachment or changes TDLib's cache directory. */
internal object SavedMediaFiles {
    private val unsafeShortcutExtensions = setOf("lnk", "scf", "url", "website")
    private const val MAX_COLLISIONS = 10_000

    fun safeDownloadName(suggested: String): String {
        val safe = AttachmentFiles.safeName(suggested)
        return if (safe.substringAfterLast('.', "").lowercase(Locale.ROOT) in unsafeShortcutExtensions) "$safe.download" else safe
    }

    /** On Windows the opened source handle denies deletion/rewrites until the copy commits. */
    fun saveUnique(source: Path, directory: Path, suggested: String, isActive: () -> Boolean = { true }): Path {
        return saveUnique(source, directory, suggested, NativeSaveTargets.capture(), isActive)
    }

    fun saveUnique(source: Path, directory: Path, suggested: String, boundary: SaveTargetBoundary,
        isActive: () -> Boolean = { true },
    ): Path = saveUniqueWithIo(source, directory, suggested, boundary, NioSavedMediaFileIo, isActive)

    fun saveUniqueWithIo(source: Path, directory: Path, suggested: String, boundary: SaveTargetBoundary,
        io: SavedMediaFileIo, isActive: () -> Boolean = { true },
    ): Path {
        val guard = NativeSaveTargets.protect(boundary)
        val publication = Publication()
        check(isActive())
        guard.require(directory)
        Files.createDirectories(directory)
        val parent = directory.toRealPath()
        guard.require(parent)
        val name = safeDownloadName(suggested)
        return withPreparedCopy(source, parent, guard, io, publication, isActive) { temporary ->
            val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
            val stem = name.take(dot)
            val extension = name.substring(dot)
            for (index in 0 until MAX_COLLISIONS) {
                val candidate = parent.resolve(if (index == 0) name else "$stem (${index + 1})$extension")
                check(candidate.parent == parent)
                check(isActive())
                try {
                    commitNew(temporary, candidate, guard, io, publication, isActive)
                    return@withPreparedCopy candidate
                } catch (collision: FileAlreadyExistsException) {
                    if (publication.possible) throw SaveTargetPublicationUncertain(collision)
                }
            }
            throw java.io.IOException("No available destination")
        }
    }

    fun saveSelected(source: Path, selected: Path, replaceExisting: Boolean, isActive: () -> Boolean = { true }): Path {
        return saveSelected(source, selected, replaceExisting, NativeSaveTargets.capture(), isActive)
    }

    fun saveSelected(source: Path, selected: Path, replaceExisting: Boolean, boundary: SaveTargetBoundary,
        isActive: () -> Boolean = { true },
    ): Path = saveSelectedWithIo(source, selected, replaceExisting, boundary, NioSavedMediaFileIo, isActive)

    fun saveSelectedWithIo(source: Path, selected: Path, replaceExisting: Boolean, boundary: SaveTargetBoundary,
        io: SavedMediaFileIo, isActive: () -> Boolean = { true },
    ): Path {
        val guard = NativeSaveTargets.protect(boundary)
        val publication = Publication()
        check(isActive())
        val target = selected.toAbsolutePath().normalize()
        require(target.fileName != null && target.parent != null)
        val protectedName = safeDownloadName(target.fileName.toString())
        val protectedTarget = target.resolveSibling(protectedName)
        guard.require(protectedTarget)
        if (Files.exists(protectedTarget) && Files.isSameFile(source, protectedTarget)) throw SaveTargetRejected(SaveTargetIssue.SAME_SOURCE)
        return withPreparedCopy(source, protectedTarget.parent.toRealPath(), guard, io, publication, isActive) { temporary ->
            guard.require(protectedTarget)
            check(isActive() && guard.current())
            if (replaceExisting) {
                publication.possible = true
                io.replace(temporary, protectedTarget)
            } else commitNew(temporary, protectedTarget, guard, io, publication, isActive)
            if (replaceExisting) checkPublished(protectedTarget, guard, isActive)
            protectedTarget
        }
    }

    /** CREATE_NEW also protects the fallback on filesystems without hard links. */
    private class Publication(var possible: Boolean = false)

    private fun commitNew(temporary: Path, target: Path, guard: SaveTargetBoundary, io: SavedMediaFileIo,
        publication: Publication, isActive: () -> Boolean) {
        guard.require(target)
        check(isActive() && guard.current())
        var ambiguousLink = false
        var linked = false
        try {
            publication.possible = true
            io.createLink(target, temporary)
            linked = true
        } catch (exists: FileAlreadyExistsException) { publication.possible = false; throw exists }
        catch (_: UnsupportedOperationException) { publication.possible = false }
        catch (_: java.nio.file.FileSystemException) { ambiguousLink = true }
        if (linked) { checkPublished(target, guard, isActive); return }
        guard.require(target)
        publication.possible = true
        val output = try { io.createNew(target) }
        catch (exists: FileAlreadyExistsException) {
            if (ambiguousLink) throw SaveTargetPublicationUncertain(exists)
            publication.possible = false
            throw exists
        }
        output.use {
            Files.newByteChannel(temporary, StandardOpenOption.READ).use { input ->
                val buffer = ByteBuffer.allocate(128 * 1024)
                while (input.read(buffer) >= 0) {
                    if (!isActive() || !guard.current()) throw SaveTargetRejected(SaveTargetIssue.RETIRED)
                    buffer.flip()
                    while (buffer.hasRemaining()) output.write(buffer)
                    buffer.clear()
                }
            }
        }
        checkPublished(target, guard, isActive)
    }

    /** A change after publication cannot be undone safely; report a possibly existing copy. */
    private fun checkPublished(target: Path, guard: SaveTargetBoundary, isActive: () -> Boolean) {
        try {
            guard.require(target)
            if (!isActive() || !guard.current()) throw SaveTargetRejected(SaveTargetIssue.RETIRED)
        } catch (rejected: SaveTargetRejected) {
            throw SaveTargetRejected(rejected.issue, publicFileMayExist = true)
        }
    }

    private fun <T> withPreparedCopy(source: Path, parent: Path, guard: SaveTargetBoundary, io: SavedMediaFileIo,
        publication: Publication, isActive: () -> Boolean, commit: (Path) -> T): T {
        try {
            return withPreparedCopyInternal(source, parent, guard, io, isActive, commit)
        } catch (error: Exception) {
            if (publication.possible && error !is SaveTargetPublicationUncertain) throw SaveTargetPublicationUncertain(error)
            throw error
        }
    }

    private fun <T> withPreparedCopyInternal(source: Path, parent: Path, guard: SaveTargetBoundary, io: SavedMediaFileIo,
        isActive: () -> Boolean, commit: (Path) -> T): T {
        guard.require(parent)
        check(isActive() && guard.current())
        require(Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS))
        val options = mutableSetOf<java.nio.file.OpenOption>(StandardOpenOption.READ)
        if (System.getProperty("os.name").startsWith("Windows", true)) {
            options += ExtendedOpenOption.NOSHARE_DELETE
            options += ExtendedOpenOption.NOSHARE_WRITE
        }
        FileChannel.open(source, options).use { input ->
            val before = Files.readAttributes(source, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            val temporary = Files.createTempFile(parent, ".nnggram-save-", ".part")
            try {
                FileChannel.open(temporary, StandardOpenOption.WRITE).use { output ->
                    val buffer = ByteBuffer.allocate(128 * 1024)
                    while (input.read(buffer) >= 0) {
                        check(isActive() && guard.current())
                        buffer.flip()
                        while (buffer.hasRemaining()) output.write(buffer)
                        buffer.clear()
                        if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                    }
                    output.force(true)
                }
                val after = Files.readAttributes(source, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                check(before.size() == after.size() && before.lastModifiedTime() == after.lastModifiedTime() &&
                    before.fileKey() == after.fileKey() && Files.size(temporary) == before.size())
                guard.require(parent)
                check(isActive() && guard.current())
                return commit(temporary)
            } finally { io.deleteTemporary(temporary) }
        }
    }
}
