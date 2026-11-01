package com.badnng.moe.media

import com.sun.nio.file.ExtendedOpenOption
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID

/** Account identity includes the actual database root, never a runtime generation or file id. */
internal data class AutoMediaCopyAccount(val userId: Long, val dataRoot: Path) {
    override fun toString() = "AutoMediaCopyAccount(<redacted>)"
}

/** Only a native-completed file may enter this boundary. Size zero remains unknown. */
internal data class AutoMediaCopyRequest(
    val account: AutoMediaCopyAccount,
    val fileId: Int,
    val remoteUniqueId: String?,
    val source: Path,
    val nativeSize: Long,
    val destination: Path,
    val suggestedName: String,
) {
    override fun toString() = "AutoMediaCopyRequest(<redacted>)"
}

internal enum class AutoMediaCopyIssue {
    INVALID_SOURCE, JOURNAL_UNAVAILABLE, PREPARATION_FAILED, AUTHORIZATION_RETIRED,
    COMMIT_UNAVAILABLE, PUBLIC_WRITTEN_RECORD_PENDING, RECOVERY_AMBIGUOUS, USER_CHANGED_TARGET,
    TARGET_UNSAFE, TARGET_UNKNOWN,
}

internal sealed interface AutoMediaCopyResult {
    data class Saved(val path: Path, val alreadyPresent: Boolean = false) : AutoMediaCopyResult {
        override fun toString() = "AutoMediaCopyResult.Saved(<redacted>)"
    }
    data class Failed(val issue: AutoMediaCopyIssue) : AutoMediaCopyResult
    data class Pending(val issue: AutoMediaCopyIssue, val publicFileMayExist: Boolean = false) : AutoMediaCopyResult
    data object Retired : AutoMediaCopyResult
}

internal enum class AutoMediaCopyState { PREPARED, COMMITTED }

/** Emitted only when CREATE_NEW itself proves the target was already present. */
internal class AutoMediaCommitCollision : FileAlreadyExistsException("automatic-copy target")
internal class AutoMediaCloseAfterPublication(cause: Throwable) : java.io.IOException("Automatic copy needs verification", cause)

private fun AutoMediaCopyResult?.publicMayExist(): Boolean = this is AutoMediaCopyResult.Saved ||
    this is AutoMediaCopyResult.Pending && publicFileMayExist

internal data class AutoMediaCopyScope(
    val accountScope: String,
    val destinationScope: String,
    val destination: Path,
    val journalDirectory: Path,
    /** Exact pre-fix Windows scope, whose NIO directory identities were null. */
    val legacyAccountScope: String? = null,
    val legacyDestinationScope: String? = null,
) {
    override fun toString() = "AutoMediaCopyScope(<redacted>)"
}

internal data class AutoMediaCopyRecord(
    val key: String,
    val intent: String,
    val state: AutoMediaCopyState,
    val destinationScope: String,
    val destination: Path,
    val target: Path,
    val stage: Path,
    val stageIdentity: String?,
    val contentHash: String,
    val length: Long,
    val targetIdentity: String? = null,
) {
    override fun toString() = "AutoMediaCopyRecord(<redacted>, state=$state)"
}

/** Identical structural and state checks apply both before and after slot transfer. */
internal fun validAutoMediaCopyRecord(record: AutoMediaCopyRecord): Boolean = try {
    record.key.matches(Regex("[0-9a-f]{64}")) && UUID.fromString(record.intent).toString() == record.intent &&
        record.destinationScope.matches(Regex("[0-9a-f]{64}")) && record.destination.isAbsolute &&
        record.destination.normalize() == record.destination && record.stage.parent == record.destination &&
        record.target.parent == record.destination && record.stage.normalize() == record.stage &&
        record.target.normalize() == record.target && record.stage != record.target &&
        SavedMediaFiles.safeDownloadName(record.target.fileName.toString()) == record.target.fileName.toString() &&
        validAutoMediaJournalStageName(record.stage) &&
        record.contentHash.matches(Regex("[0-9a-f]{64}")) && record.length >= 0 && !record.stageIdentity.isNullOrBlank() &&
        when (record.state) {
            AutoMediaCopyState.PREPARED -> record.targetIdentity == null
            AutoMediaCopyState.COMMITTED -> record.targetIdentity == record.stageIdentity
        }
} catch (_: Exception) { false }

/** Journal records also include stages created by the previous createTempFile implementation. */
private fun validAutoMediaJournalStageName(path: Path): Boolean = try {
    val part = requireNotNull(path.fileName)
    val name = part.toString()
    part.nameCount == 1 && !part.isAbsolute && name.startsWith(".nnggram-auto-") && name.endsWith(".part") &&
        name.none { it < ' ' || it in "<>:\"/\\|?*" }
} catch (_: Exception) { false }

/** New reservation names remain canonical UUIDs. */
internal fun validAutoMediaStageName(path: Path): Boolean = try {
    val name = path.fileName.toString()
    if (!name.startsWith(".nnggram-auto-") || !name.endsWith(".part")) false
    else name.removePrefix(".nnggram-auto-").removeSuffix(".part").let { UUID.fromString(it).toString() == it }
} catch (_: Exception) { false }

/** Its source handle stays leased until the journal has committed or retained its stage. */
internal class AutoMediaPreparedCopy(
    val stage: Path,
    val identity: String?,
    val contentHash: String,
    val length: Long,
    private val releaseSource: () -> Unit = {},
) : AutoCloseable {
    override fun close() = releaseSource()
    override fun toString() = "AutoMediaPreparedCopy(<redacted>)"
}

/** Every public-write and journal/crash boundary can be independently fault-injected. */
internal interface AutoMediaCopyIo {
    /** Only the real NIO adapter owns durable stage slots; test fakes can remain in-memory. */
    val durableStageOwnership: Boolean get() = false
    val ownedFiles: AutoMediaOwnedFiles get() = defaultAutoMediaOwnedFiles
    fun scope(account: AutoMediaCopyAccount, destination: Path, active: () -> Boolean): AutoMediaCopyScope
    fun prepareCopy(source: Path, destination: Path, nativeSize: Long, active: () -> Boolean): AutoMediaPreparedCopy
    fun prepareOwnedCopy(source: Path, destination: Path, nativeSize: Long, slot: AutoMediaStageSlot?,
        active: () -> Boolean): AutoMediaPreparedCopy = prepareCopy(source, destination, nativeSize, active)
    fun commitNoReplace(stage: Path, target: Path, active: () -> Boolean): String?
    fun verifyRecordedCopy(path: Path, identity: String?, length: Long, contentHash: String, active: () -> Boolean): Boolean
    fun exists(path: Path): Boolean
    /** Null is missing; corrupt/unreadable records throw and must never become missing. */
    fun loadJournal(path: Path): AutoMediaCopyRecord?
    fun writeJournalAtomically(path: Path, record: AutoMediaCopyRecord)
    fun cleanupOwnedStage(stage: Path, identity: String?)
}

/** Blocking worker contract. No directory listing, user-directory scan or persistent-key LRU. */
internal class AutoMediaCopyJournal(private val io: AutoMediaCopyIo = NioAutoMediaCopyIo) {
    fun copy(request: AutoMediaCopyRequest, active: () -> Boolean, authorizeCommit: () -> Boolean): AutoMediaCopyResult =
        copy(request, NativeSaveTargets.capture(), active, authorizeCommit)

    fun copy(request: AutoMediaCopyRequest, boundary: SaveTargetBoundary, active: () -> Boolean,
        authorizeCommit: () -> Boolean,
    ): AutoMediaCopyResult {
        if (!active()) return AutoMediaCopyResult.Retired
        if (request.account.userId <= 0 || request.fileId <= 0 || request.nativeSize < 0 ||
            !request.account.dataRoot.isAbsolute || !request.destination.isAbsolute || executableAutoMediaName(request.suggestedName))
            return AutoMediaCopyResult.Failed(AutoMediaCopyIssue.INVALID_SOURCE)
        val guard = NativeSaveTargets.protect(boundary)
        return try {
            guard.require(request.destination)
            AutoMediaCopyJournal(BoundaryAutoMediaCopyIo(io, guard)).copyInternal(request,
                { active() && guard.current() }, { guard.current() && authorizeCommit() }, guard)
        } catch (_: AutoMediaCloseAfterPublication) {
            AutoMediaCopyResult.Pending(AutoMediaCopyIssue.RECOVERY_AMBIGUOUS, true)
        } catch (_: AutoMediaStageAmbiguous) {
            AutoMediaCopyResult.Pending(AutoMediaCopyIssue.RECOVERY_AMBIGUOUS, true)
        } catch (rejected: SaveTargetRejected) {
            if (rejected.issue == SaveTargetIssue.RETIRED) AutoMediaCopyResult.Retired
            else AutoMediaCopyResult.Failed(targetCopyIssue(rejected))
        } catch (_: Exception) { failedOrRetired({ active() && guard.current() }, AutoMediaCopyIssue.PREPARATION_FAILED) }
    }

    private fun copyInternal(request: AutoMediaCopyRequest, active: () -> Boolean, authorizeCommit: () -> Boolean,
        boundary: SaveTargetBoundary): AutoMediaCopyResult {
        if (!active()) return AutoMediaCopyResult.Retired
        if (request.account.userId <= 0 || request.fileId <= 0 || request.nativeSize < 0 ||
            !request.account.dataRoot.isAbsolute || !request.destination.isAbsolute || executableAutoMediaName(request.suggestedName))
            return AutoMediaCopyResult.Failed(AutoMediaCopyIssue.INVALID_SOURCE)
        val scope = try { io.scope(request.account, request.destination, active) }
        catch (rejected: SaveTargetRejected) { throw rejected }
        catch (_: Exception) { return failedOrRetired(active, AutoMediaCopyIssue.PREPARATION_FAILED) }
        return if (io.durableStageOwnership) AutoMediaStageSlot.use(scope, boundary, active, io.ownedFiles) { slot ->
            copyScoped(request, scope, active, authorizeCommit, slot)
        } else copyScoped(request, scope, active, authorizeCommit, null)
    }

    private fun copyScoped(request: AutoMediaCopyRequest, scope: AutoMediaCopyScope, active: () -> Boolean,
        authorizeCommit: () -> Boolean, slot: AutoMediaStageSlot?): AutoMediaCopyResult {
        val uniqueId = request.remoteUniqueId?.takeIf { it.isNotEmpty() }
        val stableKey = uniqueId?.let { copyDigest("remote-v1", scope.accountScope, it, scope.destinationScope) }
        fun legacyKey(kind: String, vararg identity: String): String? {
            if (scope.legacyAccountScope == null && scope.legacyDestinationScope == null) return null
            return copyDigest(kind, scope.legacyAccountScope ?: scope.accountScope, *identity,
                scope.legacyDestinationScope ?: scope.destinationScope)
        }
        if (stableKey != null) {
            val legacy = legacyKey("remote-v1", checkNotNull(uniqueId))
            return withKeyLocks(stableKey, legacy) {
                perform(request, scope, stableKey, null, active, authorizeCommit, legacy, slot)
            }
        }
        // With no remote identity the content fallback can only be known after reading
        // the completed source. A duplicate stage is private and is never a public copy.
        val prepared = try { io.prepareOwnedCopy(request.source, scope.destination, request.nativeSize, slot, active) }
        catch (ambiguous: AutoMediaStageAmbiguous) { throw ambiguous }
        catch (rejected: SaveTargetRejected) { throw rejected }
        catch (_: Exception) { return failedOrRetired(active, AutoMediaCopyIssue.PREPARATION_FAILED) }
        var outcome: AutoMediaCopyResult? = null
        try {
            val key = copyDigest("content-v1", scope.accountScope, prepared.contentHash, prepared.length.toString(),
                SavedMediaFiles.safeDownloadName(clearBidi(request.suggestedName)), scope.destinationScope)
            val legacy = legacyKey("content-v1", prepared.contentHash, prepared.length.toString(),
                SavedMediaFiles.safeDownloadName(clearBidi(request.suggestedName)))
            return withKeyLocks(key, legacy) { perform(request, scope, key, prepared, active, authorizeCommit, legacy, slot) }
                .also { outcome = it }
        } finally {
            try { prepared.close() }
            catch (error: Exception) { if (outcome.publicMayExist()) throw AutoMediaCloseAfterPublication(error) else throw error }
        }
    }

    private fun perform(request: AutoMediaCopyRequest, scope: AutoMediaCopyScope, key: String,
        supplied: AutoMediaPreparedCopy?, active: () -> Boolean, authorizeCommit: () -> Boolean,
        legacyKey: String?, slot: AutoMediaStageSlot?): AutoMediaCopyResult {
        val journalPath = scope.journalDirectory.resolve(key.take(2)).resolve("$key.properties")
        val existing = try { io.loadJournal(journalPath) }
        catch (rejected: SaveTargetRejected) { throw rejected }
        catch (_: Exception) {
            supplied?.let { cleanup(it) }
            return AutoMediaCopyResult.Pending(AutoMediaCopyIssue.RECOVERY_AMBIGUOUS, true)
        }
        if (existing != null) {
            supplied?.let { cleanup(it) }
            if (existing.key != key || existing.destinationScope != scope.destinationScope ||
                existing.destination != scope.destination || !validAutoMediaCopyRecord(existing))
                return AutoMediaCopyResult.Pending(AutoMediaCopyIssue.RECOVERY_AMBIGUOUS, true)
            return recover(existing, journalPath, active, authorizeCommit, slot)
        }
        if (legacyKey != null && legacyKey != key) {
            val legacy = try { io.loadJournal(scope.journalDirectory.resolve(legacyKey.take(2)).resolve("$legacyKey.properties")) }
            catch (rejected: SaveTargetRejected) { throw rejected }
            catch (_: Exception) {
                supplied?.let { cleanup(it) }
                return AutoMediaCopyResult.Pending(AutoMediaCopyIssue.RECOVERY_AMBIGUOUS, true)
            }
            if (legacy != null) {
                supplied?.let { cleanup(it) }
                if (legacy.key != legacyKey || legacy.destination != scope.destination ||
                    legacy.destinationScope != (scope.legacyDestinationScope ?: scope.destinationScope) ||
                    !validAutoMediaCopyRecord(legacy))
                    return AutoMediaCopyResult.Pending(AutoMediaCopyIssue.RECOVERY_AMBIGUOUS, true)
                // A legacy record proves a prior intent but contains no stable past
                // Windows identity. Never convert the identity fix into a new copy.
                return AutoMediaCopyResult.Pending(AutoMediaCopyIssue.RECOVERY_AMBIGUOUS, io.exists(legacy.target))
            }
        }
        if (!active()) { supplied?.let { cleanup(it) }; return AutoMediaCopyResult.Retired }
        val prepared = supplied ?: try { io.prepareOwnedCopy(request.source, scope.destination, request.nativeSize, slot, active) }
        catch (ambiguous: AutoMediaStageAmbiguous) { throw ambiguous }
        catch (rejected: SaveTargetRejected) { throw rejected }
        catch (_: Exception) { return failedOrRetired(active, AutoMediaCopyIssue.PREPARATION_FAILED) }
        var outcome: AutoMediaCopyResult? = null
        try {
            if (!active()) { cleanup(prepared); return AutoMediaCopyResult.Retired }
            val name = SavedMediaFiles.safeDownloadName(clearBidi(request.suggestedName))
            val intent = UUID.randomUUID().toString()
            for (index in 0 until MAX_COLLISIONS) {
                val target = scope.destination.resolve(candidateName(name, index))
                if (io.exists(target)) continue
                val record = AutoMediaCopyRecord(key, intent, AutoMediaCopyState.PREPARED, scope.destinationScope,
                    scope.destination, target, prepared.stage, prepared.identity, prepared.contentHash, prepared.length)
                if (slot != null) try { slot.attach(journalPath, record) }
                catch (_: Exception) { return AutoMediaCopyResult.Pending(AutoMediaCopyIssue.RECOVERY_AMBIGUOUS) }
                try { io.writeJournalAtomically(journalPath, record) }
                catch (rejected: SaveTargetRejected) {
                    if (slot != null) return AutoMediaCopyResult.Pending(AutoMediaCopyIssue.RECOVERY_AMBIGUOUS)
                    throw rejected
                }
                catch (_: Exception) {
                    // The journal move may have completed before a later IO failure.
                    // The durable slot checks the exact record before cleaning its stage.
                    if (slot != null) return AutoMediaCopyResult.Pending(AutoMediaCopyIssue.RECOVERY_AMBIGUOUS)
                    cleanup(prepared)
                    return AutoMediaCopyResult.Failed(AutoMediaCopyIssue.JOURNAL_UNAVAILABLE)
                }
                // This callback rejoins the engine actor. It consumes the current account,
                // message/file, policy and target authorization immediately before publish.
                if (!active() || !authorizeCommit()) return AutoMediaCopyResult.Retired
                try {
                    val targetIdentity = publish(record, active, slot)
                    return finish(record.copy(targetIdentity = targetIdentity), journalPath, slot).also { outcome = it }
                } catch (_: AutoMediaCommitCollision) {
                    // Only this unambiguous preexisting-target error permits a different
                    // candidate in this same live intent. Persist that candidate first.
                    continue
                } catch (rejected: SaveTargetRejected) {
                    return AutoMediaCopyResult.Pending(targetCopyIssue(rejected), true).also { outcome = it }
                } catch (_: Exception) {
                    // Publication may have happened before a later identity/read failure.
                    // A second boundary check must not erase that fact.
                    return AutoMediaCopyResult.Pending(AutoMediaCopyIssue.COMMIT_UNAVAILABLE, true).also { outcome = it }
                }
            }
            return AutoMediaCopyResult.Pending(AutoMediaCopyIssue.COMMIT_UNAVAILABLE)
        } finally {
            if (supplied == null) try { prepared.close() }
            catch (error: Exception) { if (outcome.publicMayExist()) throw AutoMediaCloseAfterPublication(error) else throw error }
        }
    }

    private fun recover(record: AutoMediaCopyRecord, journalPath: Path, active: () -> Boolean,
        authorizeCommit: () -> Boolean, slot: AutoMediaStageSlot?): AutoMediaCopyResult {
        if (!active()) return AutoMediaCopyResult.Retired
        var commitAttempted = false
        var publicMayExist = record.state == AutoMediaCopyState.COMMITTED
        val result = try {
            if (record.state == AutoMediaCopyState.COMMITTED) {
                val verified = if (slot == null) io.verifyRecordedCopy(record.target, record.targetIdentity,
                    record.length, record.contentHash, active) else {
                    val held = try { slot.holdVerified(record.target, record.targetIdentity, record.length, record.contentHash) }
                    catch (rejected: SaveTargetRejected) { throw rejected }
                    catch (_: Exception) { null }
                    if (held == null) false else held.use { true }
                }
                if (!verified)
                    AutoMediaCopyResult.Pending(AutoMediaCopyIssue.USER_CHANGED_TARGET, io.exists(record.target))
                else if (!active() || !authorizeCommit()) AutoMediaCopyResult.Pending(AutoMediaCopyIssue.RECOVERY_AMBIGUOUS, true)
                else cleanupCommitted(record, slot, alreadyPresent = true)
            } else if (io.exists(record.target)) {
                // Hard-link publication preserves the stage file identity. Unknown or
                // changed identities stop here; hash equality alone cannot adopt a file.
                publicMayExist = true
                val committed = record.copy(targetIdentity = record.stageIdentity)
                if (slot == null) {
                    if (!io.verifyRecordedCopy(record.target, record.stageIdentity, record.length, record.contentHash, active))
                        AutoMediaCopyResult.Pending(AutoMediaCopyIssue.RECOVERY_AMBIGUOUS, true)
                    else if (!active() || !authorizeCommit()) AutoMediaCopyResult.Pending(AutoMediaCopyIssue.RECOVERY_AMBIGUOUS, true)
                    else finish(committed, journalPath, slot)
                } else {
                    val held = try { slot.holdVerified(record.target, record.stageIdentity, record.length, record.contentHash) }
                    catch (rejected: SaveTargetRejected) { throw rejected }
                    catch (_: Exception) { null }
                    if (held == null) AutoMediaCopyResult.Pending(AutoMediaCopyIssue.RECOVERY_AMBIGUOUS, true)
                    else {
                        val failure = held.use { verified ->
                            if (!active() || !authorizeCommit()) AutoMediaCopyResult.Pending(AutoMediaCopyIssue.RECOVERY_AMBIGUOUS, true)
                            else if (!slot.stillNamesHeld(record.target, verified)) AutoMediaCopyResult.Pending(AutoMediaCopyIssue.RECOVERY_AMBIGUOUS, true)
                            else persistCommitted(committed, journalPath)
                        }
                        failure ?: cleanupCommitted(committed, slot)
                    }
                }
            } else if (io.verifyRecordedCopy(record.stage, record.stageIdentity, record.length, record.contentHash, active)) {
                if (!active() || !authorizeCommit()) AutoMediaCopyResult.Retired
                else {
                    commitAttempted = true
                    val identity = publish(record, active, slot)
                    finish(record.copy(targetIdentity = identity), journalPath, slot)
                }
            } else AutoMediaCopyResult.Pending(AutoMediaCopyIssue.RECOVERY_AMBIGUOUS)
        } catch (rejected: SaveTargetRejected) {
            AutoMediaCopyResult.Pending(targetCopyIssue(rejected), true)
        } catch (_: Exception) {
            AutoMediaCopyResult.Pending(if (commitAttempted) AutoMediaCopyIssue.COMMIT_UNAVAILABLE else AutoMediaCopyIssue.RECOVERY_AMBIGUOUS,
                publicMayExist || commitAttempted || runCatching { io.exists(record.target) }.getOrDefault(true))
        }
        val recordedPublicWrite = result is AutoMediaCopyResult.Pending && result.publicFileMayExist
        return if (!active() && result !is AutoMediaCopyResult.Saved && !recordedPublicWrite) AutoMediaCopyResult.Retired else result
    }

    private fun publish(record: AutoMediaCopyRecord, active: () -> Boolean, slot: AutoMediaStageSlot?): String? {
        val held = slot?.holdVerified(record.stage, record.stageIdentity, record.length, record.contentHash)
        try {
            val targetIdentity = io.commitNoReplace(record.stage, record.target, active)
            if (slot != null && (targetIdentity == null || targetIdentity != record.stageIdentity)) throw AutoMediaStageAmbiguous()
            return targetIdentity
        }
        finally { held?.close() }
    }

    private fun finish(record: AutoMediaCopyRecord, journalPath: Path, slot: AutoMediaStageSlot?): AutoMediaCopyResult {
        return persistCommitted(record, journalPath) ?: cleanupCommitted(record, slot)
    }

    private fun persistCommitted(record: AutoMediaCopyRecord, journalPath: Path): AutoMediaCopyResult.Pending? {
        if (!validAutoMediaCopyRecord(record.copy(state = AutoMediaCopyState.COMMITTED)))
            return AutoMediaCopyResult.Pending(AutoMediaCopyIssue.RECOVERY_AMBIGUOUS, true)
        try { io.writeJournalAtomically(journalPath, record.copy(state = AutoMediaCopyState.COMMITTED)) }
        catch (rejected: SaveTargetRejected) { return AutoMediaCopyResult.Pending(targetCopyIssue(rejected), true) }
        catch (_: Exception) { return AutoMediaCopyResult.Pending(AutoMediaCopyIssue.PUBLIC_WRITTEN_RECORD_PENDING, true) }
        return null
    }

    private fun cleanupCommitted(record: AutoMediaCopyRecord, slot: AutoMediaStageSlot?,
        alreadyPresent: Boolean = false): AutoMediaCopyResult {
        if (!validAutoMediaCopyRecord(record.copy(state = AutoMediaCopyState.COMMITTED)))
            return AutoMediaCopyResult.Pending(AutoMediaCopyIssue.RECOVERY_AMBIGUOUS, true)
        if (slot?.ownsStage(record.stage) == true) return AutoMediaCopyResult.Saved(record.target, alreadyPresent)
        try { io.cleanupOwnedStage(record.stage, record.stageIdentity) }
        catch (rejected: SaveTargetRejected) { return AutoMediaCopyResult.Pending(targetCopyIssue(rejected), true) }
        catch (_: Exception) { return AutoMediaCopyResult.Pending(AutoMediaCopyIssue.RECOVERY_AMBIGUOUS, true) }
        return AutoMediaCopyResult.Saved(record.target, alreadyPresent)
    }

    private fun cleanup(prepared: AutoMediaPreparedCopy) {
        runCatching { io.cleanupOwnedStage(prepared.stage, prepared.identity) }
    }

    private fun failedOrRetired(active: () -> Boolean, issue: AutoMediaCopyIssue): AutoMediaCopyResult =
        if (active()) AutoMediaCopyResult.Failed(issue) else AutoMediaCopyResult.Retired

    companion object {
        private const val MAX_COLLISIONS = 10_000
        private val stripes = Array(64) { Any() }
        private fun stripeIndex(key: String) = (key.hashCode() and Int.MAX_VALUE) % stripes.size
        private fun <T> withKeyLocks(key: String, legacy: String?, block: () -> T): T {
            val currentIndex = stripeIndex(key)
            val legacyIndex = legacy?.let(::stripeIndex) ?: currentIndex
            if (currentIndex == legacyIndex) return synchronized(stripes[currentIndex], block)
            // Wait for an already accepted pre-fix worker too. Fixed stripe order
            // prevents migration keys from creating a lock-order cycle.
            return synchronized(stripes[minOf(currentIndex, legacyIndex)]) {
                synchronized(stripes[maxOf(currentIndex, legacyIndex)], block)
            }
        }
        private fun candidateName(name: String, index: Int): String {
            if (index == 0) return name
            val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
            return "${name.take(dot)} (${index + 1})${name.substring(dot)}"
        }
    }
}

private fun targetCopyIssue(rejected: SaveTargetRejected) =
    if (rejected.issue == SaveTargetIssue.NATIVE_SCAN_TREE || rejected.issue == SaveTargetIssue.SAME_SOURCE)
        AutoMediaCopyIssue.TARGET_UNSAFE else AutoMediaCopyIssue.TARGET_UNKNOWN

private fun clearBidi(value: String): String = value.map {
    if (it in "\u200e\u200f\u202a\u202b\u202d\u202e\u2066\u2067") '_' else it
}.joinToString("")

private fun copyDigest(vararg values: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    values.forEach { value ->
        val bytes = value.toByteArray(Charsets.UTF_8)
        digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
        digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal fun parseAutoMediaCopyRecord(p: Properties): AutoMediaCopyRecord {
    require(p.getProperty("version") == "1")
    fun value(name: String): String = requireNotNull(p.getProperty(name))
    val record = AutoMediaCopyRecord(value("key"), value("intent"), AutoMediaCopyState.valueOf(value("state")),
        value("destination-scope"), Path.of(value("destination")), Path.of(value("target")), Path.of(value("stage")),
        p.getProperty("stage-identity"), value("sha256"), value("length").toLong(), p.getProperty("target-identity"))
    require(record.key.matches(Regex("[0-9a-f]{64}")) && record.intent == UUID.fromString(record.intent).toString())
    return record
}

internal object NioAutoMediaCopyIo : AutoMediaCopyIo {
    override val durableStageOwnership = true
    override fun scope(account: AutoMediaCopyAccount, destination: Path, active: () -> Boolean): AutoMediaCopyScope {
        check(active())
        val accountRoot = account.dataRoot.toRealPath()
        check(active())
        Files.createDirectories(destination)
        val parent = destination.toRealPath()
        val accountIdentity = fileIdentity(accountRoot)
        val legacyAccountIdentity = attributes(accountRoot).fileKey()?.toString()
        val legacyDestinationIdentity = attributes(parent).fileKey()?.toString()
        val store = Files.getFileStore(parent)
        val accountScope = copyDigest(account.userId.toString(), accountRoot.toString(), accountIdentity.orEmpty())
        val destinationScope = existingDestinationScope(parent)
        val legacyAccount = copyDigest(account.userId.toString(), accountRoot.toString(), legacyAccountIdentity.orEmpty())
        val legacyDestination = copyDigest(parent.toString(), legacyDestinationIdentity.orEmpty(), store.name(), store.type())
        return AutoMediaCopyScope(accountScope, destinationScope, parent,
            accountRoot.resolve("ui-settings/user-${account.userId}/auto-copies-v1"),
            legacyAccount.takeIf { it != accountScope }, legacyDestination.takeIf { it != destinationScope })
    }

    /** Recompute a recorded scope without creating a directory during slot recovery. */
    internal fun existingDestinationScope(destination: Path): String {
        val parent = destination.toRealPath()
        require(parent == destination)
        val store = Files.getFileStore(parent)
        return copyDigest(parent.toString(), fileIdentity(parent).orEmpty(), store.name(), store.type())
    }

    override fun prepareCopy(source: Path, destination: Path, nativeSize: Long, active: () -> Boolean): AutoMediaPreparedCopy =
        throw IOException("A durable automatic-copy stage slot is required")

    override fun prepareOwnedCopy(source: Path, destination: Path, nativeSize: Long, slot: AutoMediaStageSlot?,
        active: () -> Boolean): AutoMediaPreparedCopy = prepareCopyInternal(source, destination, nativeSize, active,
            checkNotNull(slot))

    private fun prepareCopyInternal(source: Path, destination: Path, nativeSize: Long, active: () -> Boolean,
        slot: AutoMediaStageSlot): AutoMediaPreparedCopy {
        check(active())
        require(nativeSize >= 0 && Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS))
        val options = mutableSetOf<java.nio.file.OpenOption>(StandardOpenOption.READ)
        if (System.getProperty("os.name").startsWith("Windows", true)) {
            options += ExtendedOpenOption.NOSHARE_DELETE
            options += ExtendedOpenOption.NOSHARE_WRITE
        }
        val input = FileChannel.open(source, options)
        try {
            val before = attributes(source)
            val sourceIdentity = fileIdentity(source)
            require(nativeSize == 0L || nativeSize == before.size())
            check(active())
            val hash = MessageDigest.getInstance("SHA-256")
            var length = 0L
            val created = slot.createStage()
            val preparedStage = created.first
            fun transfer(write: (ByteArray, Int) -> Unit) {
                val bytes = ByteArray(128 * 1024)
                val buffer = ByteBuffer.wrap(bytes)
                while (true) {
                    check(active())
                    if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    hash.update(bytes, 0, read)
                    length += read
                    write(bytes, read)
                    buffer.clear()
                }
            }
            created.second.use { output ->
                check(preparedStage.parent == destination)
                slot.validateWriter(preparedStage, output)
                transfer { bytes, read -> output.write(bytes, read) }
                output.force()
            }
            val after = attributes(source)
            slot.validate(preparedStage)
            check(sameAttributes(before, after) && (sourceIdentity == null || sourceIdentity == fileIdentity(source)) &&
                length == before.size() && Files.size(preparedStage) == length && active())
            return AutoMediaPreparedCopy(preparedStage, slot.expectedIdentity(preparedStage),
                hash.digest().joinToString("") { "%02x".format(it) }, length) { input.close() }
        } catch (error: Throwable) {
            input.close()
            throw error
        }
    }

    override fun commitNoReplace(stage: Path, target: Path, active: () -> Boolean): String? {
        require(stage.parent == target.parent)
        check(active())
        // A link is atomic CREATE_NEW and retains the recorded stage identity. No
        // ordinary move fallback: its replacement behavior is provider-dependent.
        try { Files.createLink(target, stage) }
        catch (_: FileAlreadyExistsException) { throw AutoMediaCommitCollision() }
        return fileIdentity(target)
    }

    override fun verifyRecordedCopy(path: Path, identity: String?, length: Long, contentHash: String, active: () -> Boolean): Boolean {
        if (!active() || identity == null || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return false
        return runCatching {
            val before = attributes(path)
            if (fileIdentity(path) != identity || before.size() != length) return@runCatching false
            val hash = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input ->
                val bytes = ByteArray(128 * 1024)
                while (true) {
                    check(active())
                    val read = input.read(bytes)
                    if (read < 0) break
                    hash.update(bytes, 0, read)
                }
            }
            sameAttributes(before, attributes(path)) && fileIdentity(path) == identity &&
                hash.digest().joinToString("") { "%02x".format(it) } == contentHash
        }.getOrDefault(false)
    }

    override fun exists(path: Path): Boolean = Files.exists(path, LinkOption.NOFOLLOW_LINKS)

    override fun loadJournal(path: Path): AutoMediaCopyRecord? {
        if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) return null
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) in 1L..65_536L)
        return parseAutoMediaCopyRecord(Properties().apply { Files.newBufferedReader(path).use(::load) })
    }

    override fun writeJournalAtomically(path: Path, record: AutoMediaCopyRecord) {
        Files.createDirectories(path.parent)
        val temporary = Files.createTempFile(path.parent, ".auto-copy-journal-", ".tmp")
        try {
            val p = Properties().apply {
                setProperty("version", "1")
                setProperty("key", record.key)
                setProperty("intent", record.intent)
                setProperty("state", record.state.name)
                setProperty("destination-scope", record.destinationScope)
                setProperty("destination", record.destination.toString())
                setProperty("target", record.target.toString())
                setProperty("stage", record.stage.toString())
                record.stageIdentity?.let { setProperty("stage-identity", it) }
                setProperty("sha256", record.contentHash)
                setProperty("length", record.length.toString())
                record.targetIdentity?.let { setProperty("target-identity", it) }
            }
            Files.newBufferedWriter(temporary).use { p.store(it, "Automatic saved copy journal") }
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { it.force(true) }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }

    override fun cleanupOwnedStage(stage: Path, identity: String?) {
        deleteExactAutoMediaStage(stage, identity, ownedFiles)
    }

    private fun attributes(path: Path): BasicFileAttributes =
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    private fun fileIdentity(path: Path): String? = stableMediaFileIdentity(path)
    private fun sameAttributes(before: BasicFileAttributes, after: BasicFileAttributes): Boolean =
        before.isRegularFile == after.isRegularFile && before.size() == after.size() &&
            before.fileKey() == after.fileKey() && before.lastModifiedTime() == after.lastModifiedTime()
}
