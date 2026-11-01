package com.badnng.moe.tdlib

import com.badnng.moe.status.DiagnosticEvent
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/** Bounded history and a rotating, typed event log. No request, response, configuration or Throwable API. */
class SafeEventJournal(
    private val directory: Path,
    private val maxEvents: Int = 128,
    private val maxFileBytes: Long = 256 * 1_024,
) {
    private val history = ArrayDeque<DiagnosticEvent>()
    private val fileName = "runtime-events-${ProcessHandle.current().pid()}-${journalIds.incrementAndGet()}"
    var path: Path = directory.resolve("$fileName.log").toAbsolutePath().normalize()
        private set
    var available: Boolean = true
        private set

    init {
        require(maxEvents > 0)
        require(maxFileBytes >= 512)
    }

    fun snapshot(): List<DiagnosticEvent> = history.toList()

    fun useStartupDirectory() = useDirectory(directory)

    fun record(event: DiagnosticEvent) {
        val safe = event.copy(
            nativeVersion = event.nativeVersion?.takeIf { it.matches(VERSION) },
            nativeCommit = event.nativeCommit?.takeIf { it.matches(COMMIT) },
        )
        if (history.size == maxEvents) history.removeFirst()
        history.addLast(safe)
        available = persist(listOf(safe))
    }

    /** Once the configured data location is known, also retain the startup history beside its databases. */
    fun useDirectory(directory: Path) {
        val destination = directory.resolve("$fileName.log").toAbsolutePath().normalize()
        if (path == destination) return
        path = destination
        available = persist(history.toList())
    }

    private fun persist(events: List<DiagnosticEvent>): Boolean = try {
        Files.createDirectories(path.parent)
        for (event in events) {
            val line = logLine(event)
            if (Files.exists(path) && Files.size(path) + line.toByteArray(Charsets.UTF_8).size > maxFileBytes) {
                Files.move(path, path.resolveSibling("$fileName.previous.log"), StandardCopyOption.REPLACE_EXISTING)
            }
            Files.writeString(path, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }
        true
    } catch (_: Exception) {
        // A broken log directory must not prevent initialization or emit an exception containing a path.
        false
    }

    private fun logLine(event: DiagnosticEvent): String = buildString {
        append(Instant.ofEpochMilli(event.timestampEpochMillis))
        append("\tgeneration=").append(event.generation)
        append("\tkind=").append(event.kind.name)
        append("\tengine=").append(event.engine.name)
        append("\tauthorization=").append(event.authorization.name)
        append("\tconnection=").append(event.connection.name)
        event.category?.let { append("\tcategory=").append(it.name) }
        event.requestErrorCode?.let { append("\tcode=").append(it) }
        event.nativeVersion?.let { append("\tversion=").append(it) }
        event.nativeCommit?.let { append("\tcommit=").append(it) }
        append('\n')
    }

    companion object {
        private val journalIds = AtomicLong()
        private val VERSION = Regex("[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}")
        private val COMMIT = Regex("[a-fA-F0-9]{40}")

        fun besideConfiguration(path: Path): SafeEventJournal {
            val localRoot = System.getenv("LOCALAPPDATA")?.let(Path::of)
                ?: Path.of(System.getProperty("user.home"), ".local", "share")
            val directory = path.takeIf { it.isAbsolute }?.parent?.resolve("logs") ?: localRoot.resolve("nngGram/logs")
            return SafeEventJournal(directory)
        }
    }
}
