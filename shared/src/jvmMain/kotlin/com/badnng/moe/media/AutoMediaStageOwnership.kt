package com.badnng.moe.media

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID

internal class AutoMediaStageAmbiguous : IOException("An automatic-copy stage cannot be safely attributed")

/** One persistent slot per account. No directory listing or unverified path deletion. */
internal class AutoMediaStageSlot private constructor(
    private val scope: AutoMediaCopyScope,
    private val boundary: SaveTargetBoundary,
    private val active: () -> Boolean,
    private val files: AutoMediaOwnedFiles,
) {
    private data class Reservation(val destination: Path, val stage: Path, val identityFileId: String? = null)
    private data class Identity(val reservationId: String, val stageId: String, val bindingStarted: Boolean = false)
    private data class Binding(val reservationId: String, val identityFileId: String, val journal: Path,
        val key: String, val intent: String, val destinationScope: String, val destination: Path,
        val target: Path, val stage: Path, val stageId: String, val hash: String, val length: Long)

    private val marker = scope.journalDirectory.resolve("stage-owner-v1.properties")
    private val identityPath = scope.journalDirectory.resolve("stage-owner-v1.identity")
    private val bindingPath = scope.journalDirectory.resolve("stage-owner-v1.binding")
    private var reservation: Reservation? = null
    private var reservationId: String? = null
    private var stageIdentity: String? = null
    private var identityFileId: String? = null
    private var binding: Binding? = null
    private var bindingFileId: String? = null

    private fun check(path: Path) {
        boundary.require(path)
        if (!active() || !boundary.current()) throw SaveTargetRejected(SaveTargetIssue.RETIRED)
    }

    /** The returned handle is the sole writer; its ID is persisted before any payload is written. */
    fun createStage(): Pair<Path, AutoMediaOwnedFile> {
        check(scope.destination); check(marker); check(identityPath); check(bindingPath)
        if (present(marker) || present(identityPath) || present(bindingPath)) throw AutoMediaStageAmbiguous()
        val stage = scope.destination.resolve(".nnggram-auto-${UUID.randomUUID()}.part")
        val reserved = Reservation(scope.destination, stage)
        val reservedId = createMetadata(marker, reservationProperties(reserved))
        reservation = reserved
        reservationId = reservedId
        check(stage)
        val handle = files.createNew(stage)
        try {
            val id = handle.identity
            val ownerId = createMetadata(identityPath, identityProperties(Identity(reservedId, id)))
            val identified = reserved.copy(identityFileId = ownerId)
            check(marker)
            files.openReadWrite(marker).use { held ->
                if (held.identity != reservedId || readReservation(readProperties(held)) != reserved) throw AutoMediaStageAmbiguous()
                check(marker)
                held.replace(encode(reservationProperties(identified)))
            }
            reservation = identified
            stageIdentity = id
            identityFileId = ownerId
            return stage to handle
        } catch (error: Throwable) {
            runCatching { handle.close() }
            throw error
        }
    }

    fun validate(stage: Path) {
        if (reservation?.stage != stage || stageIdentity == null) throw AutoMediaStageAmbiguous()
        check(stage)
        files.openRead(stage).use { if (it.identity != stageIdentity) throw AutoMediaStageAmbiguous() }
    }

    fun validateWriter(stage: Path, held: AutoMediaOwnedFile) {
        check(stage)
        if (reservation?.stage != stage || stageIdentity == null || stageIdentity != held.identity) throw AutoMediaStageAmbiguous()
    }

    fun expectedIdentity(stage: Path): String {
        if (reservation?.stage != stage) throw AutoMediaStageAmbiguous()
        return stageIdentity ?: throw AutoMediaStageAmbiguous()
    }

    fun ownsStage(stage: Path): Boolean = reservation?.stage == stage && binding != null

    /** A collision may update only the target on the same held metadata file object. */
    fun attach(journal: Path, record: AutoMediaCopyRecord) {
        val reserved = reservation ?: throw AutoMediaStageAmbiguous()
        val rid = reservationId ?: throw AutoMediaStageAmbiguous()
        val iid = identityFileId ?: throw AutoMediaStageAmbiguous()
        val sid = stageIdentity ?: throw AutoMediaStageAmbiguous()
        validate(reserved.stage)
        if (record.stage != reserved.stage || record.destination != reserved.destination || record.stageIdentity != sid ||
            !validJournal(journal, record.key) || !validRecord(record)) throw AutoMediaStageAmbiguous()
        val next = Binding(rid, iid, journal, record.key, record.intent, record.destinationScope, record.destination,
            record.target, record.stage, sid, record.contentHash, record.length)
        val previous = binding
        if (previous == null) {
            // Persist that binding was attempted before creating its separate record.
            // If that record later goes missing, recovery must not treat it as unbound.
            check(identityPath)
            files.openReadWrite(identityPath).use { held ->
                val expected = Identity(rid, sid)
                if (held.identity != iid || readIdentity(readProperties(held)) != expected) throw AutoMediaStageAmbiguous()
                check(identityPath)
                held.replace(encode(identityProperties(expected.copy(bindingStarted = true))))
            }
            bindingFileId = createMetadata(bindingPath, bindingProperties(next))
        } else {
            if (previous.copy(target = next.target) != next) throw AutoMediaStageAmbiguous()
            check(bindingPath)
            files.openReadWrite(bindingPath).use { held ->
                if (held.identity != bindingFileId || readBinding(readProperties(held)) != previous) throw AutoMediaStageAmbiguous()
                check(bindingPath)
                held.replace(encode(bindingProperties(next)))
            }
        }
        binding = next
    }

    /** Holds the verified object against concurrent replacement during publication. */
    fun holdVerified(path: Path, identity: String?, length: Long, hash: String): AutoMediaOwnedFile {
        if (identity == null || length < 0 || !hash.matches(Regex("[0-9a-f]{64}"))) throw AutoMediaStageAmbiguous()
        check(path)
        val held = files.openRead(path)
        try {
            if (held.identity != identity) throw AutoMediaStageAmbiguous()
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = ByteArray(128 * 1024)
            var count = 0L
            while (true) {
                check(path)
                val read = held.read(bytes)
                if (read < 0) break
                digest.update(bytes, 0, read)
                count += read
                if (count > length) throw AutoMediaStageAmbiguous()
            }
            if (count != length || digest.digest().joinToString("") { "%02x".format(it) } != hash) throw AutoMediaStageAmbiguous()
            check(path)
            return held
        } catch (error: Throwable) {
            runCatching { held.close() }
            throw error
        }
    }

    /** Recheck the pathname after an authorization wait while the verified object remains held. */
    fun stillNamesHeld(path: Path, held: AutoMediaOwnedFile): Boolean {
        check(path)
        return files.openRead(path).use { it.identity == held.identity }
    }

    private fun recover() {
        check(marker); check(identityPath); check(bindingPath)
        val hasMarker = present(marker)
        val hasIdentity = present(identityPath)
        val hasBinding = present(bindingPath)
        if (!hasMarker) {
            if (hasIdentity || hasBinding) throw AutoMediaStageAmbiguous()
            return
        }
        files.openReadDelete(marker).use { reserveHandle ->
            val reserved = readReservation(readProperties(reserveHandle))
            check(reserved.stage)
            if (!hasIdentity) {
                // A reservation without an identity record cannot prove the marker or stage is still ours.
                throw AutoMediaStageAmbiguous()
            }
            files.openReadDelete(identityPath).use { identityHandle ->
                val identity = readIdentity(readProperties(identityHandle))
                if (identity.reservationId != reserveHandle.identity || reserved.identityFileId != identityHandle.identity)
                    throw AutoMediaStageAmbiguous()
                if (!hasBinding) {
                    if (identity.bindingStarted) throw AutoMediaStageAmbiguous()
                    if (present(reserved.stage)) safeDelete(reserved.stage, identity.stageId)
                    deleteHeld(marker, reserveHandle)
                    deleteHeld(identityPath, identityHandle)
                    return
                }
                files.openReadDelete(bindingPath).use { bindingHandle ->
                    val linked = readBinding(readProperties(bindingHandle))
                    if (!identity.bindingStarted || linked.reservationId != reserveHandle.identity || linked.identityFileId != identityHandle.identity ||
                        linked.destination != reserved.destination || linked.stage != reserved.stage || linked.stageId != identity.stageId ||
                        !validJournal(linked.journal, linked.key)) throw AutoMediaStageAmbiguous()
                    check(linked.journal)
                    // Missing after binding cannot prove that publication never happened.
                    if (!present(linked.journal)) throw AutoMediaStageAmbiguous()
                    val journalHandle = try { files.openRead(linked.journal) } catch (_: Exception) { throw AutoMediaStageAmbiguous() }
                    journalHandle.use { heldJournal ->
                        val record = try { parseAutoMediaCopyRecord(readProperties(heldJournal, 65_536)) }
                        catch (_: Exception) { throw AutoMediaStageAmbiguous() }
                        if (!matches(linked, record) || !validRecord(record)) throw AutoMediaStageAmbiguous()
                        val checkPath = if (present(record.stage)) record.stage else record.target
                        if (!present(checkPath)) throw AutoMediaStageAmbiguous()
                        holdVerified(checkPath, record.stageIdentity, record.length, record.contentHash).use {
                            fun transfer() {
                                // Reservation first: a crash with leftover metadata blocks another stage.
                                deleteHeld(marker, reserveHandle)
                                deleteHeld(identityPath, identityHandle)
                                deleteHeld(bindingPath, bindingHandle)
                            }
                            if (record.state == AutoMediaCopyState.COMMITTED && checkPath != record.target) {
                                holdVerified(record.target, record.targetIdentity, record.length, record.contentHash).use { transfer() }
                            } else transfer()
                        }
                        if (record.state == AutoMediaCopyState.COMMITTED && present(record.stage))
                            safeDelete(record.stage, checkNotNull(record.stageIdentity))
                    }
                }
            }
        }
    }

    private fun safeDelete(path: Path, identity: String) {
        deleteExactAutoMediaStage(path, identity, files) { check(path) }
    }

    private fun deleteHeld(path: Path, held: AutoMediaOwnedFile) {
        check(path)
        held.markDelete()
        held.close()
    }

    private fun matches(bound: Binding, record: AutoMediaCopyRecord): Boolean =
        record.key == bound.key && record.intent == bound.intent && record.destinationScope == bound.destinationScope &&
            record.destination == bound.destination && record.target == bound.target && record.stage == bound.stage &&
            record.stageIdentity == bound.stageId && record.contentHash == bound.hash && record.length == bound.length

    private fun validRecord(record: AutoMediaCopyRecord): Boolean = try {
        validAutoMediaCopyRecord(record) &&
            run { check(record.destination); NioAutoMediaCopyIo.existingDestinationScope(record.destination) == record.destinationScope }
    } catch (_: Exception) { false }

    private fun validJournal(path: Path, key: String): Boolean = path.isAbsolute && path.normalize() == path &&
        key.matches(Regex("[0-9a-f]{64}")) && path.parent?.parent == scope.journalDirectory &&
        path.fileName.toString() == "$key.properties" && path.parent.fileName.toString() == key.take(2)

    private fun createMetadata(path: Path, properties: Properties): String {
        check(path)
        Files.createDirectories(scope.journalDirectory)
        files.createNew(path).use { held ->
            val data = encode(properties)
            held.write(data, data.size)
            held.force()
            check(path)
            return held.identity
        }
    }

    private fun encode(properties: Properties): ByteArray = ByteArrayOutputStream().use { output ->
        properties.store(output, "Automatic stage ownership")
        output.toByteArray()
    }

    private fun readProperties(held: AutoMediaOwnedFile, maxBytes: Int = 16_384): Properties {
        val output = ByteArrayOutputStream()
        val bytes = ByteArray(4096)
        while (true) {
            val count = held.read(bytes)
            if (count < 0) break
            if (output.size() + count > maxBytes) throw AutoMediaStageAmbiguous()
            output.write(bytes, 0, count)
        }
        return try { Properties().apply { ByteArrayInputStream(output.toByteArray()).reader(Charsets.UTF_8).use(::load) } }
        catch (_: Exception) { throw AutoMediaStageAmbiguous() }
    }

    private fun reservationProperties(value: Reservation) = Properties().apply {
        setProperty("version", "2"); setProperty("destination", value.destination.toString()); setProperty("stage", value.stage.toString())
        value.identityFileId?.let { setProperty("identity-file-id", it) }
    }
    private fun identityProperties(value: Identity) = Properties().apply {
        setProperty("version", "2"); setProperty("reservation-id", value.reservationId); setProperty("stage-id", value.stageId)
        setProperty("binding-started", if (value.bindingStarted) "1" else "0")
    }
    private fun bindingProperties(value: Binding) = Properties().apply {
        setProperty("version", "2"); setProperty("reservation-id", value.reservationId); setProperty("identity-file-id", value.identityFileId)
        setProperty("journal", value.journal.toString()); setProperty("key", value.key); setProperty("intent", value.intent)
        setProperty("destination-scope", value.destinationScope); setProperty("destination", value.destination.toString())
        setProperty("target", value.target.toString()); setProperty("stage", value.stage.toString()); setProperty("stage-id", value.stageId)
        setProperty("sha256", value.hash); setProperty("length", value.length.toString())
    }
    private fun Properties.value(name: String): String = getProperty(name)?.takeIf { it.isNotBlank() && it.length <= 32_000 }
        ?: throw AutoMediaStageAmbiguous()
    private fun Properties.checkVersion() { if (value("version") != "2") throw AutoMediaStageAmbiguous() }
    private fun readReservation(p: Properties): Reservation = try {
        p.checkVersion()
        val destination = Path.of(p.value("destination")); val stage = Path.of(p.value("stage"))
        if (!destination.isAbsolute || destination.normalize() != destination || stage.parent != destination ||
            !validAutoMediaStageName(stage)) throw AutoMediaStageAmbiguous()
        Reservation(destination, stage, p.getProperty("identity-file-id")?.takeIf { it.isNotBlank() })
    } catch (_: Exception) { throw AutoMediaStageAmbiguous() }
    private fun readIdentity(p: Properties): Identity {
        p.checkVersion()
        val bindingStarted = when (p.value("binding-started")) { "0" -> false; "1" -> true; else -> throw AutoMediaStageAmbiguous() }
        return Identity(p.value("reservation-id"), p.value("stage-id"), bindingStarted)
    }
    private fun readBinding(p: Properties): Binding = try {
        p.checkVersion()
        Binding(p.value("reservation-id"), p.value("identity-file-id"), Path.of(p.value("journal")), p.value("key"),
            p.value("intent"), p.value("destination-scope"), Path.of(p.value("destination")), Path.of(p.value("target")),
            Path.of(p.value("stage")), p.value("stage-id"), p.value("sha256"), p.value("length").toLong())
    } catch (_: Exception) { throw AutoMediaStageAmbiguous() }
    private fun present(path: Path): Boolean = try {
        Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS); true
    } catch (_: NoSuchFileException) { false }

    companion object {
        private val locks = Array(64) { Any() }
        fun use(scope: AutoMediaCopyScope, boundary: SaveTargetBoundary, active: () -> Boolean, files: AutoMediaOwnedFiles,
            block: (AutoMediaStageSlot) -> AutoMediaCopyResult): AutoMediaCopyResult {
            val key = scope.journalDirectory.toString().hashCode() and Int.MAX_VALUE
            return synchronized(locks[key % locks.size]) {
                val slot = AutoMediaStageSlot(scope, boundary, active, files)
                try { slot.recover() }
                catch (rejected: SaveTargetRejected) { throw rejected }
                catch (_: Exception) { throw AutoMediaStageAmbiguous() }
                try {
                    val result = block(slot)
                    val recovered = try { slot.recover(); true } catch (_: Exception) { false }
                    if (recovered) result else when (result) {
                        is AutoMediaCopyResult.Pending -> result
                        is AutoMediaCopyResult.Saved -> AutoMediaCopyResult.Pending(AutoMediaCopyIssue.RECOVERY_AMBIGUOUS, true)
                        else -> AutoMediaCopyResult.Pending(AutoMediaCopyIssue.RECOVERY_AMBIGUOUS)
                    }
                } catch (error: Exception) {
                    val recovered = try { slot.recover(); true } catch (_: Exception) { false }
                    if (!recovered) AutoMediaCopyResult.Pending(AutoMediaCopyIssue.RECOVERY_AMBIGUOUS,
                        publicFileMayExist = error is AutoMediaCloseAfterPublication)
                    else throw error
                } catch (error: Throwable) {
                    runCatching { slot.recover() }
                    throw error
                }
            }
        }
    }
}
