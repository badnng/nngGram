package com.badnng.moe

import java.nio.file.Files
import kotlin.test.*

class SettingsDownloadPreferencesTest {
    @Test fun versionedAtomicRoundTripPreservesHiddenCustomPathAndSafeMissingDefault() = temporary { root ->
        val path = root.resolve("prefs/ui-downloads.properties")
        val defaults = SettingsDownloadPreferences()
        assertEquals(defaults, assertIs<DownloadPreferencesRead.Value>(readDownloadPreferences(path)).preferences)
        assertFalse(Files.exists(path))
        val expected = SettingsDownloadPreferences(false, SettingsDownloadDirectory.CUSTOM, root.resolve("中文下载").toString())
        assertEquals(DownloadPreferencesWrite.SAVED, writeDownloadPreferences(path, defaults, expected))
        assertEquals(expected, assertIs<DownloadPreferencesRead.Value>(readDownloadPreferences(path)).preferences)
        val hidden = expected.copy(askEveryTime = true, directory = SettingsDownloadDirectory.DOWNLOADS)
        assertEquals(DownloadPreferencesWrite.SAVED, writeDownloadPreferences(path, expected, hidden))
        assertEquals(hidden, assertIs<DownloadPreferencesRead.Value>(readDownloadPreferences(path)).preferences)
        Files.list(path.parent).use { assertEquals(1L, it.count()) }
        assertFalse(hidden.toString().contains("中文下载"))
    }

    @Test fun staleExpectedCannotReplaceNewerPreferences() = temporary { root ->
        val path = root.resolve("downloads.properties")
        val first = SettingsDownloadPreferences()
        val other = first.copy(askEveryTime = !first.askEveryTime)
        assertEquals(DownloadPreferencesWrite.SAVED, writeDownloadPreferences(path, first, other))
        assertEquals(DownloadPreferencesWrite.CONFLICT, writeDownloadPreferences(path, first, first.copy(directory = SettingsDownloadDirectory.ACCOUNT_TEMP)))
        assertEquals(other, assertIs<DownloadPreferencesRead.Value>(readDownloadPreferences(path)).preferences)
    }

    @Test fun malformedVersionPathAndUnreadableTargetRemainFailuresWithoutOverwriting() = temporary { root ->
        val path = root.resolve("downloads.properties")
        for (contents in listOf("version=2\n", "version=1\nask-every-time=false\ndirectory=CUSTOM\ncustom-path=relative\n",
            "version=1\nask-every-time=maybe\ndirectory=DOWNLOADS\n")) {
            Files.writeString(path, contents)
            assertEquals(DownloadPreferencesRead.Failed, readDownloadPreferences(path))
            assertEquals(DownloadPreferencesWrite.FAILED, writeDownloadPreferences(path, SettingsDownloadPreferences(), SettingsDownloadPreferences()))
            assertEquals(contents, Files.readString(path))
        }
        assertEquals(DownloadPreferencesRead.Failed, readDownloadPreferences(root))
    }

    @Test fun invalidCustomDestinationCannotCommitOrReplaceThePreviousValue() = temporary { root ->
        val path = root.resolve("downloads.properties")
        val original = SettingsDownloadPreferences()
        for (custom in listOf("", "relative", "bad\u0000path")) {
            assertEquals(DownloadPreferencesWrite.FAILED, writeDownloadPreferences(path, original,
                original.copy(directory = SettingsDownloadDirectory.CUSTOM, customPath = custom)))
            assertFalse(Files.exists(path))
        }
    }

    private fun temporary(block: (java.nio.file.Path) -> Unit) {
        val root = Files.createTempDirectory("nnggram-download-prefs-test-")
        try { block(root) } finally { root.toFile().deleteRecursively() }
    }
}
