package com.badnng.moe.media

import com.badnng.moe.chat.*
import com.badnng.moe.DownloadPreferencesRead
import com.badnng.moe.downloadPreferencesPath
import com.badnng.moe.readDownloadPreferences
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.Properties
import java.util.concurrent.Executors

data class AutoMediaStored(val revision: Long = 0, val policy: AutoMediaPolicy = AutoMediaPolicy())
data class AutoMediaDestination(val askEveryTime: Boolean, val directory: Path?, val fingerprint: String, val targetAvailable: Boolean = true) {
    override fun toString() = "AutoMediaDestination(<redacted>)"
}
enum class AutoMediaWriteResult { SAVED, CONFLICT, FAILED, RETIRED }

/** Callbacks can arrive on workers; the chat controller rejoins its own actor. */
interface AutoMediaPreferencesAccess {
    val dataRoot: Path? get() = null
    fun read(userId: Long, completed: (AutoMediaStored?) -> Unit)
    fun destination(userId: Long, completed: (AutoMediaDestination?) -> Unit) { completed(null) }
    fun write(userId: Long, expected: AutoMediaStored, desired: AutoMediaPolicy, active: () -> Boolean,
        completed: (AutoMediaWriteResult) -> Unit)
}

class AutoMediaPreferencesStore private constructor(
    override val dataRoot: Path,
    private val preferencesPath: () -> Path,
    private val roots: (String?, Long?) -> DownloadDestinationRoots,
) : AutoMediaPreferencesAccess {
    constructor(dataRoot: Path) : this(dataRoot, ::downloadPreferencesPath, ::downloadDestinationRoots)
    /** Tests inject both paths and roots; no user configuration or KnownFolder reads. */
    internal constructor(dataRoot: Path, preferencesPath: Path, roots: (String?, Long?) -> DownloadDestinationRoots) :
        this(dataRoot, { preferencesPath }, roots)
    init { require(dataRoot.isAbsolute) }
    private fun path(userId: Long): Path {
        require(userId > 0)
        return dataRoot.normalize().resolve("ui-settings/user-$userId/auto-media.properties")
    }
    override fun read(userId: Long, completed: (AutoMediaStored?) -> Unit) {
        io.execute { completed(runCatching { readFile(path(userId)) }.getOrNull()) }
    }
    override fun destination(userId: Long, completed: (AutoMediaDestination?) -> Unit) {
        io.execute {
            val value = runCatching {
                require(userId > 0)
                val preferences = (readDownloadPreferences(preferencesPath()) as? DownloadPreferencesRead.Value)?.preferences
                    ?: return@runCatching null
                val directory = if (preferences.askEveryTime) null else runCatching {
                    resolveCheckedDownloadDirectory(preferences, roots(dataRoot.toString(), userId))
                }.getOrNull()
                val available = preferences.askEveryTime || directory != null
                AutoMediaDestination(preferences.askEveryTime, directory,
                    "${preferences.askEveryTime}|${preferences.directory.name}|${directory?.toAbsolutePath()?.normalize()}|$available", available)
            }.getOrNull()
            completed(value)
        }
    }
    override fun write(userId: Long, expected: AutoMediaStored, desired: AutoMediaPolicy, active: () -> Boolean,
        completed: (AutoMediaWriteResult) -> Unit) {
        io.execute { completed(runCatching { writeFile(path(userId), expected, desired, active) }.getOrDefault(AutoMediaWriteResult.FAILED)) }
    }
    companion object {
        private val lock = Any()
        private val io = Executors.newFixedThreadPool(2) { task -> Thread(task, "auto-media-preferences").apply { isDaemon = true } }
        internal fun readFile(path: Path): AutoMediaStored? = try {
            if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) AutoMediaStored()
            else if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 1_048_576) null
            else {
                val p = Properties().apply { Files.newBufferedReader(path).use(::load) }
                require(p.getProperty("version") == "1")
                val revision = p.getProperty("revision").toLong().also { require(it >= 0) }
                val sources = AutoMediaSource.entries.associateWith { source -> AutoMediaSourceRules(
                    AutoMediaType.entries.associateWith { type -> p.getProperty("${source.name}.${type.name}").toLong() }) }
                val peers = p.stringPropertyNames().filter { it.startsWith("peer.") }.associate { key ->
                    key.removePrefix("peer.").toLong() to AutoMediaOverride.valueOf(p.getProperty(key))
                }
                val policy = AutoMediaPolicy(sources, peers)
                require(policy.valid())
                AutoMediaStored(revision, policy)
            }
        } catch (_: Exception) { null }

        internal fun writeFile(path: Path, expected: AutoMediaStored, desired: AutoMediaPolicy, active: () -> Boolean): AutoMediaWriteResult = synchronized(lock) {
            if (!active()) return@synchronized AutoMediaWriteResult.RETIRED
            if (!desired.valid() || expected.revision == Long.MAX_VALUE) return@synchronized AutoMediaWriteResult.FAILED
            val current = readFile(path) ?: return@synchronized AutoMediaWriteResult.FAILED
            if (current != expected) return@synchronized AutoMediaWriteResult.CONFLICT
            var temporary: Path? = null
            try {
                if (!active()) return@synchronized AutoMediaWriteResult.RETIRED
                Files.createDirectories(path.parent)
                temporary = Files.createTempFile(path.parent, "auto-media-", ".tmp")
                val p = Properties().apply {
                    setProperty("version", "1")
                    setProperty("revision", (expected.revision + 1).toString())
                    desired.sources.forEach { (source, rules) -> rules.limits.forEach { (type, value) ->
                        setProperty("${source.name}.${type.name}", value.toString())
                    } }
                    desired.peers.forEach { (id, value) -> setProperty("peer.$id", value.name) }
                }
                Files.newBufferedWriter(temporary).use { p.store(it, "Local account automatic media rules") }
                FileChannel.open(temporary, StandardOpenOption.WRITE).use { it.force(true) }
                if (!active()) return@synchronized AutoMediaWriteResult.RETIRED
                // A successful settings save has an atomic commit; unsupported filesystems report failure.
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                AutoMediaWriteResult.SAVED
            } catch (_: Exception) { AutoMediaWriteResult.FAILED }
            finally { temporary?.let { runCatching { Files.deleteIfExists(it) } } }
        }
    }
}
