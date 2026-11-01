package com.badnng.moe.tdlib

import java.nio.file.Files
import kotlin.test.*

class AuthenticationDatabaseSelectionTest {
    @Test fun noJournalUsesTheOriginalRootAndARealAtomicJournalSurvivesRestartWithoutMovingOldData() {
        val root = Files.createTempDirectory("authentication-selection-")
        val oldFile = Files.createDirectories(root.resolve("database")).resolve("fixture")
        Files.writeString(oldFile, "old-db-content")
        val store = AuthenticationDatabaseSelectionStore(root, newId = { "a".repeat(32) })
        assertEquals(root, store.directory(store.read()))
        val attempt = store.beginPhoneAttempt(store.read())
        assertTrue(attempt.phoneMode)
        assertEquals(1, attempt.revision)
        assertEquals(root.resolve("authentication-attempts/${"a".repeat(32)}"), store.directory(attempt))
        assertEquals(attempt, AuthenticationDatabaseSelectionStore(root).read())
        assertEquals("old-db-content", Files.readString(oldFile))
        assertTrue(Files.isDirectory(root.resolve("database")))
    }

    @Test fun expectedChoiceConflictAndExistingAttemptNeverOverwriteAFormerDatabase() {
        val root = Files.createTempDirectory("authentication-conflict-")
        val ids = ArrayDeque(listOf("a".repeat(32), "a".repeat(32), "b".repeat(32)))
        val store = AuthenticationDatabaseSelectionStore(root, newId = { ids.removeFirst() })
        val initial = store.read()
        val first = store.beginPhoneAttempt(initial)
        val file = store.directory(first).resolve("native-data")
        Files.writeString(file, "preserve")
        assertFailsWith<AuthenticationDatabaseSelectionException> { store.beginPhoneAttempt(initial) }
        val second = store.beginPhoneAttempt(first)
        assertTrue(second.relativeDirectory.endsWith("b".repeat(32)))
        assertEquals("preserve", Files.readString(file))
        assertEquals(second, store.read())
    }

    @Test fun journalFailureKeepsItsPreviousChoiceAndNeverDeletesAnyDatabase() {
        val root = Files.createTempDirectory("authentication-write-failure-")
        val journal = FakeJournal()
        val ids = ArrayDeque(listOf("a".repeat(32), "b".repeat(32)))
        val store = AuthenticationDatabaseSelectionStore(root, journal) { ids.removeFirst() }
        val first = store.beginPhoneAttempt(store.read())
        journal.fail = true
        assertFailsWith<AuthenticationDatabaseSelectionException> { store.beginPhoneAttempt(first) }
        assertEquals(first, store.read())
        assertTrue(Files.isDirectory(root.resolve("authentication-attempts/${"a".repeat(32)}")))
        assertTrue(Files.isDirectory(root.resolve("authentication-attempts/${"b".repeat(32)}")))
    }

    @Test fun malformedOrEscapingSelectionsFailClosedRatherThanSilentlyOpeningTheRoot() {
        val root = Files.createTempDirectory("authentication-malformed-")
        val journal = FakeJournal()
        val store = AuthenticationDatabaseSelectionStore(root, journal)
        for (bad in listOf("../outside", "authentication-attempts/../database", "C:/private", "authentication-attempts/AAAA", "./database", "database")) {
            journal.text = "version=1\nactive=$bad\nphone=1\nrevision=1\n"
            assertFailsWith<AuthenticationDatabaseSelectionException> { store.read() }
        }
        for (bad in listOf("", "version=2", "version=1\nactive=.\nphone=0\nrevision=0\n", "version=1\nactive=.\nphone=1\nrevision=2\n")) {
            journal.text = bad
            assertFailsWith<AuthenticationDatabaseSelectionException> { store.read() }
        }
        assertFailsWith<AuthenticationDatabaseSelectionException> { store.directory(AuthenticationDatabaseChoice("../outside")) }
    }

    @Test fun selectKnownNeedsTheCurrentRevisionAndDoesNotInventAuthorizationFromAnExistingFolder() {
        val root = Files.createTempDirectory("authentication-known-")
        val store = AuthenticationDatabaseSelectionStore(root, newId = { "a".repeat(32) })
        val original = store.read()
        val attempt = store.beginPhoneAttempt(original)
        val selected = store.selectKnown(attempt, original)
        assertEquals(".", selected.relativeDirectory)
        assertFalse(selected.phoneMode)
        assertEquals(2, selected.revision)
        assertEquals(selected, store.read())
        assertFailsWith<AuthenticationDatabaseSelectionException> { store.selectKnown(attempt, original) }
    }

    @Test fun attemptNamespaceCannotBeReplacedByAnOrdinaryFileAndUnexpectedIdsAreRejected() {
        val root = Files.createTempDirectory("authentication-path-")
        Files.writeString(root.resolve("authentication-attempts"), "not-a-directory")
        assertFailsWith<AuthenticationDatabaseSelectionException> { AuthenticationDatabaseSelectionStore(root).beginPhoneAttempt(AuthenticationDatabaseChoice()) }
        val otherRoot = Files.createTempDirectory("authentication-id-")
        assertFailsWith<AuthenticationDatabaseSelectionException> { AuthenticationDatabaseSelectionStore(otherRoot, newId = { "../escape" }).beginPhoneAttempt(AuthenticationDatabaseChoice()) }
    }

    private class FakeJournal : AuthenticationSelectionJournal {
        var text: String? = null
        var fail = false
        override fun read() = text
        override fun commit(text: String) { if (fail) throw java.io.IOException("fixture"); this.text = text }
    }
}
