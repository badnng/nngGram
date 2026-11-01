package com.badnng.moe.security

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** All files belong to a newly created synthetic directory, never an account root. */
class LocalLockPreferencesTest {
    @Test
    fun missingPreferenceDefaultsToOneHourAndConfirmedWritesAreReadByANewOwner() = inRoot { directory ->
        val root = fakeRoot(directory)
        val preferences = FileLocalLockPreferences(root)
        assertEquals(3_600L, assertIs<LocalLockPreferenceRead.Value>(preferences.read()).seconds)
        assertTrue(preferences.write(300))
        assertEquals(300L, assertIs<LocalLockPreferenceRead.Value>(FileLocalLockPreferences(root).read()).seconds)
        assertFalse(Files.exists(directory.resolve("local-protection.settings.pending")))
        assertTrue(preferences.write(18_000))
        assertEquals(18_000L, assertIs<LocalLockPreferenceRead.Value>(FileLocalLockPreferences(root).read()).seconds)
        assertFalse(preferences.write(0))
        assertFalse(preferences.write(61))
        assertFalse(preferences.write(Int.MAX_VALUE.toLong() + 1))
        assertEquals(18_000L, assertIs<LocalLockPreferenceRead.Value>(preferences.read()).seconds)
    }

    @Test
    fun pendingCorruptAndOversizedSettingsAreExplicitFailures() = inRoot { directory ->
        val preferences = FileLocalLockPreferences(fakeRoot(directory))
        val path = directory.resolve("local-protection.settings")
        val pending = directory.resolve("local-protection.settings.pending")
        Files.writeString(pending, "unfinished")
        assertEquals(LocalLockPreferenceRead.Failure, preferences.read())
        assertFalse(preferences.write(60))
        assertEquals("unfinished", Files.readString(pending))
        Files.delete(pending)
        for (contents in listOf("version=2\nseconds=300\n", "version=1\nseconds=0\n",
            "version=1\nseconds=61\n", "x".repeat(65))) {
            Files.writeString(path, contents)
            assertEquals(LocalLockPreferenceRead.Failure, preferences.read())
        }
    }

    private fun fakeRoot(directory: Path) = LocalProtectionRoot(directory,
        identity = { ByteArray(32) { 1 } }, isCurrent = { true }, freshEligible = { true })

    private inline fun inRoot(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("nnggram-local-lock-preferences-test-")
        try {
            block(directory)
        } finally {
            Files.deleteIfExists(directory.resolve("local-protection.settings.pending"))
            Files.deleteIfExists(directory.resolve("local-protection.settings"))
            Files.deleteIfExists(directory)
        }
    }
}
