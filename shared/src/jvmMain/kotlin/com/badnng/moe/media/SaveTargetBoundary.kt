package com.badnng.moe.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

internal enum class SaveTargetIssue { NATIVE_SCAN_TREE, UNKNOWN_NATIVE_ROOTS, RETIRED, SAME_SOURCE }

internal class SaveTargetRejected(val issue: SaveTargetIssue, val publicFileMayExist: Boolean = false) :
    IOException("Saved-copy target is unavailable ($issue)")
/** A public target may already exist; callers must not silently create another copy. */
internal class SaveTargetPublicationUncertain(cause: Throwable) : IOException("Saved-copy publication needs verification", cause)

/** Blocking filesystem checks belong on an IO worker, never the UI or TD actor. */
internal interface SaveTargetBoundary {
    fun current(): Boolean = true
    fun check(target: Path): SaveTargetIssue?
    fun require(target: Path) { check(target)?.let { throw SaveTargetRejected(it) } }
}

internal interface SaveTargetPathAccess {
    /** Permission/IO failures throw; only a proven absent path returns false. */
    fun exists(path: Path): Boolean
    fun realPath(path: Path): Path
    fun directory(path: Path): Boolean
}

internal object NioSaveTargetPathAccess : SaveTargetPathAccess {
    override fun exists(path: Path): Boolean = try {
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        true
    } catch (_: NoSuchFileException) { false }
    override fun realPath(path: Path): Path = path.toRealPath()
    override fun directory(path: Path): Boolean = Files.readAttributes(path, BasicFileAttributes::class.java).isDirectory
}

/** A true native client registers Unknown before create, then its accepted, selected lease roots. */
class NativeSaveTargetRegistry internal constructor(
    private val paths: SaveTargetPathAccess = NioSaveTargetPathAccess,
    private val ignoreCase: Boolean = System.getProperty("os.name").startsWith("Windows", true),
) {
    private data class Roots(val database: Path, val files: Path)
    private data class Snapshot(val revision: Long, val roots: List<Roots?>)
    private val lock = Any()
    private val nextId = AtomicLong()
    private val registrations = linkedMapOf<Long, Roots?>()
    private val mutableRevision = MutableStateFlow(0L)
    internal val revision: StateFlow<Long> get() = mutableRevision

    internal fun registerUnknown(generation: Long): Registration {
        val id = nextId.incrementAndGet()
        synchronized(lock) { registrations[id] = null; mutableRevision.value++ }
        return Registration(this, id, generation)
    }

    private fun known(id: Long, database: Path, files: Path) = synchronized(lock) {
        if (registrations.containsKey(id)) {
            registrations[id] = if (database.isAbsolute && files.isAbsolute) Roots(database.normalize(), files.normalize()) else null
            mutableRevision.value++
        }
    }

    private fun closed(id: Long) = synchronized(lock) {
        if (registrations.containsKey(id)) { registrations.remove(id); mutableRevision.value++ }
    }

    internal fun capture(): SaveTargetBoundary {
        val snapshot = synchronized(lock) { Snapshot(mutableRevision.value, registrations.values.toList()) }
        return object : SaveTargetBoundary {
            override fun current() = mutableRevision.value == snapshot.revision
            override fun check(target: Path): SaveTargetIssue? {
                if (!current()) return SaveTargetIssue.RETIRED
                if (!target.isAbsolute) return SaveTargetIssue.UNKNOWN_NATIVE_ROOTS
                if (snapshot.roots.any { it == null }) return SaveTargetIssue.UNKNOWN_NATIVE_ROOTS
                if (snapshot.roots.isEmpty()) return null // Explicitly no native client, e.g. a standalone tool.
                return try {
                    val logical = target.toAbsolutePath().normalize()
                    val real = resolveTarget(logical)
                    val allRoots = snapshot.roots.filterNotNull().flatMap { listOf(it.database, it.files) }
                    val blocked = allRoots.any { root ->
                        val canonical = paths.realPath(root).toAbsolutePath().normalize()
                        require(paths.directory(canonical))
                        within(logical, root) || within(real, canonical) || within(logical, canonical) || within(real, root)
                    }
                    if (!current()) SaveTargetIssue.RETIRED
                    else if (blocked) SaveTargetIssue.NATIVE_SCAN_TREE else null
                } catch (_: Exception) {
                    if (!current()) SaveTargetIssue.RETIRED else SaveTargetIssue.UNKNOWN_NATIVE_ROOTS
                }
            }
        }
    }

    /** Resolve the nearest proven existing ancestor without creating or enumerating anything. */
    private fun resolveTarget(target: Path): Path {
        var ancestor = target
        val missing = ArrayDeque<Path>()
        while (!paths.exists(ancestor)) {
            missing.addFirst(checkNotNull(ancestor.fileName))
            ancestor = checkNotNull(ancestor.parent)
        }
        var real = paths.realPath(ancestor).toAbsolutePath().normalize()
        if (missing.isNotEmpty()) check(paths.directory(real))
        missing.forEach { real = real.resolve(it) }
        return real.normalize()
    }

    private fun within(target: Path, root: Path): Boolean {
        fun key(path: Path): String = path.toAbsolutePath().normalize().toString().replace('\\', '/').trimEnd('/')
            .let { if (ignoreCase) it.lowercase(Locale.ROOT) else it }
        val candidate = key(target)
        val parent = key(root)
        return candidate == parent || candidate.startsWith("$parent/")
    }

    class Registration internal constructor(private val registry: NativeSaveTargetRegistry, private val id: Long, val generation: Long) {
        fun accepted(database: Path, files: Path) = registry.known(id, database, files)
        /** Only a confirmed native Closed may remove a real/unknown client fence. */
        fun confirmedClosed() = registry.closed(id)
        override fun toString() = "NativeSaveTargetRegistration(<redacted>)"
    }
}

/** UI preview roots never replace this process-wide inventory of actual native clients. */
internal object NativeSaveTargets {
    val registry = NativeSaveTargetRegistry()
    fun capture() = registry.capture()
    fun protect(boundary: SaveTargetBoundary): SaveTargetBoundary {
        val native = capture()
        return object : SaveTargetBoundary {
            override fun current() = boundary.current() && native.current()
            override fun check(target: Path): SaveTargetIssue? {
                if (!current()) return SaveTargetIssue.RETIRED
                val issue = boundary.check(target) ?: native.check(target)
                return if (current()) issue else SaveTargetIssue.RETIRED
            }
        }
    }
}

/** All journal IO retains the original recorded identity; refusal never migrates or removes it. */
internal class BoundaryAutoMediaCopyIo(private val delegate: AutoMediaCopyIo, private val boundary: SaveTargetBoundary) : AutoMediaCopyIo {
    override val durableStageOwnership get() = delegate.durableStageOwnership
    override val ownedFiles get() = delegate.ownedFiles
    override fun scope(account: AutoMediaCopyAccount, destination: Path, active: () -> Boolean): AutoMediaCopyScope {
        boundary.require(destination)
        // The journal is a write destination too, even when a root aliases the account directory.
        boundary.require(account.dataRoot.resolve("ui-settings/user-${account.userId}/auto-copies-v1"))
        return delegate.scope(account, destination, active).also {
            boundary.require(it.destination)
            boundary.require(it.journalDirectory)
        }
    }
    override fun prepareCopy(source: Path, destination: Path, nativeSize: Long, active: () -> Boolean): AutoMediaPreparedCopy {
        boundary.require(destination)
        val prepared = delegate.prepareCopy(source, destination, nativeSize, active)
        try { boundary.require(prepared.stage) } catch (error: Throwable) { prepared.close(); throw error }
        return prepared
    }
    override fun prepareOwnedCopy(source: Path, destination: Path, nativeSize: Long, slot: AutoMediaStageSlot?,
        active: () -> Boolean): AutoMediaPreparedCopy {
        boundary.require(destination)
        val prepared = delegate.prepareOwnedCopy(source, destination, nativeSize, slot, active)
        try { boundary.require(prepared.stage) } catch (error: Throwable) { prepared.close(); throw error }
        return prepared
    }
    override fun commitNoReplace(stage: Path, target: Path, active: () -> Boolean): String? {
        boundary.require(stage); boundary.require(target)
        if (!active() || !boundary.current()) throw SaveTargetRejected(SaveTargetIssue.RETIRED)
        val identity = delegate.commitNoReplace(stage, target, active)
        if (!active() || !boundary.current()) throw SaveTargetRejected(SaveTargetIssue.RETIRED, publicFileMayExist = true)
        boundary.require(target)
        return identity
    }
    override fun verifyRecordedCopy(path: Path, identity: String?, length: Long, contentHash: String, active: () -> Boolean): Boolean {
        boundary.require(path)
        return delegate.verifyRecordedCopy(path, identity, length, contentHash, active)
    }
    override fun exists(path: Path): Boolean { boundary.require(path); return delegate.exists(path) }
    override fun loadJournal(path: Path): AutoMediaCopyRecord? { boundary.require(path); return delegate.loadJournal(path) }
    override fun writeJournalAtomically(path: Path, record: AutoMediaCopyRecord) {
        boundary.require(path); boundary.require(record.destination); boundary.require(record.stage); boundary.require(record.target)
        delegate.writeJournalAtomically(path, record)
        if (!boundary.current()) throw SaveTargetRejected(SaveTargetIssue.RETIRED, publicFileMayExist = record.state == AutoMediaCopyState.COMMITTED)
    }
    override fun cleanupOwnedStage(stage: Path, identity: String?) {
        boundary.require(stage)
        if (durableStageOwnership) deleteExactAutoMediaStage(stage, identity, ownedFiles) { boundary.require(stage) }
        else delegate.cleanupOwnedStage(stage, identity)
    }
}
