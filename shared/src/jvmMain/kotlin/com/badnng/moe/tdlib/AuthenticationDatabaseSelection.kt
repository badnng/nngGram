package com.badnng.moe.tdlib

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.*
import java.util.UUID

/** A directory identity is never an authorization proof. Only TDLib's actual state proves that. */
internal data class AuthenticationDatabaseChoice(
    val relativeDirectory: String = ".",
    val phoneMode: Boolean = false,
    val revision: Long = 0,
)

internal class AuthenticationDatabaseSelectionException : RuntimeException("Authentication database selection unavailable")

/** Injectable persistence boundary; commit must be atomic or leave the prior value intact. */
internal interface AuthenticationSelectionJournal {
    fun read(): String?
    fun commit(text: String)
}

/**
 * Caller holds the existing DataDirectoryLease for the entire configured root while using this
 * store, including between native generations. The active child has its own lease when non-root.
 * This class never reads native databases, interprets directory existence as login, moves account
 * files, deletes an old attempt, or sends LogOut/Destroy. Begin only after the old client is Closed.
 */
internal class AuthenticationDatabaseSelectionStore(
    root: Path,
    journal: AuthenticationSelectionJournal? = null,
    private val newId: () -> String = { UUID.randomUUID().toString().replace("-", "") },
) {
    private val root = root.toAbsolutePath().normalize()
    private val canonicalRoot = try { this.root.toRealPath() } catch (_: Exception) { throw AuthenticationDatabaseSelectionException() }
    private val journal = journal ?: FileAuthenticationSelectionJournal(this.root, canonicalRoot)

    @Synchronized
    fun read(): AuthenticationDatabaseChoice = protect {
        requireRoot()
        val text = journal.read() ?: return@protect AuthenticationDatabaseChoice()
        if (text.length > 512) throw AuthenticationDatabaseSelectionException()
        val rows = text.split('\n').filter(String::isNotEmpty)
        if (rows.size != 4 || rows[0] != "version=1") throw AuthenticationDatabaseSelectionException()
        if (!rows[1].startsWith("active=") || !rows[2].startsWith("phone=") || !rows[3].startsWith("revision="))
            throw AuthenticationDatabaseSelectionException()
        val relative = rows[1].removePrefix("active=")
        val phone = when (rows[2]) { "phone=0" -> false; "phone=1" -> true; else -> throw AuthenticationDatabaseSelectionException() }
        val revision = rows[3].removePrefix("revision=").toLongOrNull()?.takeIf { it > 0 }
            ?: throw AuthenticationDatabaseSelectionException()
        val choice = AuthenticationDatabaseChoice(relative, phone, revision)
        directory(choice)
        choice
    }

    /** Resolve only our exact relative namespace; reject traversal, links and swapped ancestors. */
    @Synchronized
    fun directory(choice: AuthenticationDatabaseChoice): Path = protect {
        requireRoot()
        if (choice.revision < 0 || (choice.relativeDirectory == "." && choice.phoneMode))
            throw AuthenticationDatabaseSelectionException()
        if (choice.relativeDirectory == ".") return@protect root
        if (!choice.relativeDirectory.matches(Regex("authentication-attempts/[a-f0-9]{32}")))
            throw AuthenticationDatabaseSelectionException()
        var directory = root
        for (part in choice.relativeDirectory.split('/')) {
            directory = directory.resolve(part)
            if (!Files.isDirectory(directory, NOFOLLOW_LINKS) || Files.isSymbolicLink(directory) ||
                !directory.toRealPath().startsWith(canonicalRoot)) throw AuthenticationDatabaseSelectionException()
        }
        directory
    }

    /** Preserve every previous database; choose a fresh native auth attempt with phone-first intent. */
    @Synchronized
    fun beginPhoneAttempt(expected: AuthenticationDatabaseChoice): AuthenticationDatabaseChoice = protect {
        if (read() != expected || expected.revision == Long.MAX_VALUE) throw AuthenticationDatabaseSelectionException()
        val parent = root.resolve("authentication-attempts")
        if (!Files.exists(parent, NOFOLLOW_LINKS)) Files.createDirectory(parent)
        requireSafeDirectory(parent)
        var chosen: AuthenticationDatabaseChoice? = null
        for (attempt in 0 until 8) {
            val id = newId()
            if (!id.matches(Regex("[a-f0-9]{32}"))) throw AuthenticationDatabaseSelectionException()
            val candidate = parent.resolve(id)
            try {
                Files.createDirectory(candidate)
                chosen = AuthenticationDatabaseChoice("authentication-attempts/$id", true, expected.revision + 1)
                break
            } catch (_: java.nio.file.FileAlreadyExistsException) {
                // Existing content is never reused or overwritten by a new authorization attempt.
            }
        }
        val choice = chosen ?: throw AuthenticationDatabaseSelectionException()
        directory(choice)
        commit(choice)
        choice
    }

    /** Adopt a previously known database only after the root host obtains a real native proof. */
    @Synchronized
    fun selectKnown(expected: AuthenticationDatabaseChoice, previous: AuthenticationDatabaseChoice): AuthenticationDatabaseChoice = protect {
        if (read() != expected || expected.revision == Long.MAX_VALUE) throw AuthenticationDatabaseSelectionException()
        directory(previous)
        val choice = previous.copy(revision = expected.revision + 1)
        commit(choice)
        choice
    }

    private fun commit(choice: AuthenticationDatabaseChoice) {
        val text = "version=1\nactive=${choice.relativeDirectory}\nphone=${if (choice.phoneMode) 1 else 0}\nrevision=${choice.revision}\n"
        journal.commit(text)
        // A fake or platform adapter that acknowledges a different write is never treated as saved.
        if (journal.read() != text) throw AuthenticationDatabaseSelectionException()
    }

    private fun requireRoot() {
        if (!Files.isDirectory(root) || root.toRealPath() != canonicalRoot) throw AuthenticationDatabaseSelectionException()
    }
    private fun requireSafeDirectory(path: Path) {
        if (!Files.isDirectory(path, NOFOLLOW_LINKS) || Files.isSymbolicLink(path) ||
            !path.toRealPath().startsWith(canonicalRoot)) throw AuthenticationDatabaseSelectionException()
    }
    private inline fun <T> protect(work: () -> T): T = try { work() } catch (_: Exception) { throw AuthenticationDatabaseSelectionException() }
}

/** Atomic create-new candidate, forced bytes, readback, then atomic replacement; no unsafe fallback. */
private class FileAuthenticationSelectionJournal(private val root: Path, private val canonicalRoot: Path) : AuthenticationSelectionJournal {
    private val path = root.resolve("authentication-selection.properties")
    override fun read(): String? {
        requireRoot()
        if (!Files.exists(path, NOFOLLOW_LINKS)) return null
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || Files.isSymbolicLink(path) || Files.size(path) > 512)
            throw AuthenticationDatabaseSelectionException()
        return Files.readString(path, Charsets.UTF_8)
    }
    override fun commit(text: String) {
        requireRoot()
        if (Files.exists(path, NOFOLLOW_LINKS) && (!Files.isRegularFile(path, NOFOLLOW_LINKS) || Files.isSymbolicLink(path)))
            throw AuthenticationDatabaseSelectionException()
        val candidate = root.resolve(".authentication-selection-${UUID.randomUUID()}.tmp")
        val bytes = text.toByteArray(Charsets.UTF_8)
        var created = false
        try {
            FileChannel.open(candidate, CREATE_NEW, WRITE).use { channel ->
                created = true
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            if (!Files.readAllBytes(candidate).contentEquals(bytes)) throw AuthenticationDatabaseSelectionException()
            requireRoot()
            Files.move(candidate, path, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            bytes.fill(0)
            // Only this create-new journal candidate is cleaned; no account or attempt is deleted.
            if (created) Files.deleteIfExists(candidate)
        }
    }
    private fun requireRoot() {
        if (!Files.isDirectory(root) || root.toRealPath() != canonicalRoot) throw AuthenticationDatabaseSelectionException()
    }
}
