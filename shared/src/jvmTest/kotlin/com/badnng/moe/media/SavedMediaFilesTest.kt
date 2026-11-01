package com.badnng.moe.media

import com.badnng.moe.SettingsDownloadDirectory
import com.badnng.moe.SettingsDownloadPreferences
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class SavedMediaFilesTest {
    @Test fun exactDirectoriesAreDistinctAndMissingSystemDownloadsFallsBackOnlyToAccountTemp() = temporary { root ->
        val roots = DownloadDestinationRoots(root.resolve("Downloads/nngGram"), root.resolve("account/downloads-temp"))
        assertEquals(roots.downloads, resolveDownloadDirectory(SettingsDownloadPreferences(false), roots))
        assertEquals(roots.accountTemporary, resolveDownloadDirectory(SettingsDownloadPreferences(false, SettingsDownloadDirectory.ACCOUNT_TEMP), roots))
        assertEquals(root.resolve("custom"), resolveDownloadDirectory(SettingsDownloadPreferences(false, SettingsDownloadDirectory.CUSTOM, root.resolve("custom").toString()), roots))
        assertEquals(roots.accountTemporary, resolveDownloadDirectory(SettingsDownloadPreferences(false), roots.copy(downloads = null)))
        assertNull(resolveDownloadDirectory(SettingsDownloadPreferences(false, SettingsDownloadDirectory.ACCOUNT_TEMP), roots.copy(accountTemporary = null)))
        assertFalse(Files.exists(roots.downloads))
    }

    @Test fun namesCannotEscapeDirectoryAndWindowsShortcutNamesAreProtected() = temporary { root ->
        val source = root.resolve("source.bin").also { Files.writeString(it, "native bytes") }
        val directory = root.resolve("saved")
        val saved = SavedMediaFiles.saveUnique(source, directory, "../../evil.LNK")
        assertEquals(directory.toRealPath(), saved.parent)
        assertEquals("evil.LNK.download", saved.fileName.toString())
        assertEquals("native bytes", Files.readString(saved))
        assertEquals("_CON.txt", SavedMediaFiles.safeDownloadName("CON.txt"))
        assertEquals("file.url.download", SavedMediaFiles.safeDownloadName("file.url"))
    }

    @Test fun repeatedAndConcurrentSavesNeverReplaceEarlierOrRacingFiles() = temporary { root ->
        val source = root.resolve("source.bin").also { Files.writeString(it, "native bytes") }
        val directory = root.resolve("saved").also { Files.createDirectories(it) }
        Files.writeString(directory.resolve("report.txt"), "existing")
        val pool = Executors.newFixedThreadPool(4)
        try {
            val tasks = List(8) { Callable { SavedMediaFiles.saveUnique(source, directory, "report.txt") } }
            val paths = pool.invokeAll(tasks).map { it.get(20, TimeUnit.SECONDS) }
            assertEquals(8, paths.toSet().size)
            assertEquals("existing", Files.readString(directory.resolve("report.txt")))
            paths.forEach { assertEquals("native bytes", Files.readString(it)) }
            Files.list(directory).use { assertEquals(9L, it.count()) }
        } finally { pool.shutdownNow() }
    }

    @Test fun replacementRequiresExplicitPermissionAndMissingSourceDoesNotTouchDestination() = temporary { root ->
        val source = root.resolve("source.bin").also { Files.writeString(it, "new") }
        val target = root.resolve("target.bin").also { Files.writeString(it, "old") }
        assertFails { SavedMediaFiles.saveSelected(source, target, false) }
        assertEquals("old", Files.readString(target))
        assertEquals(target, SavedMediaFiles.saveSelected(source, target, true))
        assertEquals("new", Files.readString(target))
        assertFails { SavedMediaFiles.saveSelected(root.resolve("missing"), target, true) }
        assertEquals("new", Files.readString(target))
        assertEquals(SaveTargetIssue.SAME_SOURCE,
            assertFailsWith<SaveTargetRejected> { SavedMediaFiles.saveSelected(source, source, false) }.issue)
        Files.list(root).use { assertEquals(2L, it.count()) }
    }

    @Test fun retiredOwnerCannotCommitAndTemporaryCopyIsRemoved() = temporary { root ->
        val source = root.resolve("source.bin").also { Files.write(it, ByteArray(400_000) { 7 }) }
        val directory = root.resolve("saved")
        var checks = 0
        assertFails { SavedMediaFiles.saveUnique(source, directory, "retired.bin") { ++checks < 4 } }
        Files.list(directory).use { assertEquals(0L, it.count()) }
        assertEquals(400_000L, Files.size(source))
    }

    @Test fun anAlreadyRetiredOwnerCannotCreateAnAccountDirectoryOrReportASameFileSave() = temporary { root ->
        val source = root.resolve("source.bin").also { Files.writeString(it, "native bytes") }
        val directory = root.resolve("old-account/downloads-temp/user-123")
        assertFails { SavedMediaFiles.saveUnique(source, directory, "retired.bin") { false } }
        assertFalse(Files.exists(root.resolve("old-account")))
        assertFails { SavedMediaFiles.saveSelected(source, source, false) { false } }
        assertEquals("native bytes", Files.readString(source))
        Files.list(root).use { assertEquals(1L, it.count()) }
    }

    @Test fun sameDatabaseRootKeepsDifferentNativeAccountsSeparateAndUnknownIdentityHasNoTemporaryFallback() = temporary { root ->
        val first = accountTemporaryDownloadDirectory(root.toString(), 123)
        val next = accountTemporaryDownloadDirectory(root.toString(), 456)
        assertEquals(root.resolve("downloads-temp/user-123"), first)
        assertEquals(root.resolve("downloads-temp/user-456"), next)
        assertNotEquals(first, next)
        for (unknown in listOf(null, 0L, -1L)) {
            assertNull(accountTemporaryDownloadDirectory(root.toString(), unknown))
            assertNull(resolveDownloadDirectory(SettingsDownloadPreferences(false), DownloadDestinationRoots(null,
                accountTemporaryDownloadDirectory(root.toString(), unknown))))
        }
        assertNull(accountTemporaryDownloadDirectory("relative", 123))
        assertFalse(Files.exists(first))
        assertFalse(Files.exists(next))
    }

    private fun temporary(block: (java.nio.file.Path) -> Unit) {
        val root = Files.createTempDirectory("nnggram-saved-media-test-")
        try { block(root) } finally { root.toFile().deleteRecursively() }
    }
}
