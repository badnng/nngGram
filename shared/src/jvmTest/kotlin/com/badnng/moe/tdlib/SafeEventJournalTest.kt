package com.badnng.moe.tdlib

import com.badnng.moe.status.*
import java.nio.file.Files
import kotlin.test.*

class SafeEventJournalTest {
    @Test
    fun memoryHistoryKeepsOnlyTheNewestEventsWithTheirTimestampsAndGenerations() {
        val journal = SafeEventJournal(Files.createTempDirectory("event-history-"), maxEvents = 3)
        repeat(8) { journal.record(event(it.toLong())) }
        assertEquals(listOf(5L, 6L, 7L), journal.snapshot().map { it.generation })
        assertEquals("00:00:00 UTC", journal.snapshot().first().timeLabel)
        assertTrue(journal.available)
        val saved = Files.readString(journal.path)
        assertTrue(saved.contains("1970-01-01T00:00:00Z\tgeneration=0"))
        assertTrue(saved.contains("kind=CONNECTION_CHANGED"))
    }

    @Test
    fun journalRejectsArbitraryNativeStringsAndNeverSerializesPathsOrFreeText() {
        val journal = SafeEventJournal(Files.createTempDirectory("safe-event-"))
        journal.record(event(1).copy(nativeVersion = "sensitive-version\napi_hash=fixture-secret", nativeCommit = "phone=fixture-phone"))
        journal.record(event(2).copy(nativeVersion = "1.8.69", nativeCommit = "a".repeat(40), category = FailureCategory.PROXY, requestErrorCode = 407))
        val saved = Files.readString(journal.path)
        assertFalse(saved.contains("sensitive-version"))
        assertFalse(saved.contains("fixture-secret"))
        assertFalse(saved.contains("fixture-phone"))
        assertFalse(saved.contains(journal.path.toString()))
        assertNull(journal.snapshot().first().nativeVersion)
        assertNull(journal.snapshot().first().nativeCommit)
        assertTrue(saved.contains("version=1.8.69"))
        assertTrue(saved.contains("category=PROXY\tcode=407"))
    }

    @Test
    fun diskHistoryRotatesToOneBackupAndBothFilesStayWithinTheBound() {
        val directory = Files.createTempDirectory("rotated-events-")
        val journal = SafeEventJournal(directory, maxFileBytes = 512)
        repeat(30) { journal.record(event(it.toLong())) }
        val files = Files.list(directory).use { it.toList() }
        assertEquals(2, files.size)
        assertTrue(files.all { Files.size(it) <= 512 })
        assertTrue(files.any { it.fileName.toString().endsWith(".previous.log") })
        assertTrue(Files.readString(journal.path).contains("generation=29"))
    }

    @Test
    fun failedPersistenceRetainsMemoryAndCanRecoverAtAnotherDirectory() {
        val occupied = Files.createTempFile("occupied-journal-directory-", ".file")
        val journal = SafeEventJournal(occupied)
        journal.record(event(1))
        assertFalse(journal.available)
        assertEquals(1, journal.snapshot().size)
        journal.useDirectory(Files.createTempDirectory("recovered-event-directory-"))
        assertTrue(journal.available)
        assertTrue(Files.readString(journal.path).contains("generation=1"))
    }

    @Test
    fun separateInstancesDoNotShareAFileAndStartupCanReturnToItsFallbackDirectory() {
        val initial = Files.createTempDirectory("event-fallback-")
        val first = SafeEventJournal(initial)
        val second = SafeEventJournal(initial)
        assertNotEquals(first.path, second.path)
        val original = first.path
        first.record(event(1))
        first.useDirectory(Files.createTempDirectory("event-database-location-"))
        assertNotEquals(original, first.path)
        first.useStartupDirectory()
        assertEquals(original, first.path)
        assertTrue(first.available)
    }

    @Test
    fun aRootConfigurationPathCannotCrashJournalConstruction() {
        val root = Files.createTempDirectory("configuration-root-").root
        assertTrue(SafeEventJournal.besideConfiguration(root).path.isAbsolute)
    }

    private fun event(generation: Long) = DiagnosticEvent(
        timestampEpochMillis = 0,
        generation = generation,
        kind = DiagnosticKind.CONNECTION_CHANGED,
        engine = EnginePhase.INITIALIZED,
        authorization = AuthorizationPhase.WAITING_LOGIN,
        connection = ConnectionPhase.WAITING_NETWORK,
    )
}
