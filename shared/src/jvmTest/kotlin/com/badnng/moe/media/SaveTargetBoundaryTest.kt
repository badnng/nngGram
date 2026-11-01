package com.badnng.moe.media

import com.badnng.moe.SettingsDownloadDirectory
import com.badnng.moe.SettingsDownloadPreferences
import com.badnng.moe.DownloadPreferencesRead
import com.badnng.moe.DownloadPreferencesWrite
import com.badnng.moe.readDownloadPreferences
import com.badnng.moe.writeDownloadPreferences
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** Own synthetic directories only; alias tests model resolution, not a Windows junction acceptance. */
class SaveTargetBoundaryTest {
    @Test fun noNativeIsDistinctFromUnknownAndEveryInventoryChangeRetiresOldCaptures() = temporary { root ->
        val registry = NativeSaveTargetRegistry()
        val noNative = registry.capture()
        assertNull(noNative.check(root.resolve("output")))
        val registration = registry.registerUnknown(1)
        assertEquals(SaveTargetIssue.RETIRED, noNative.check(root))
        val unknown = registry.capture()
        assertEquals(SaveTargetIssue.UNKNOWN_NATIVE_ROOTS, unknown.check(root.resolve("output")))
        val database = Files.createDirectories(root.resolve("account/database"))
        val files = Files.createDirectories(root.resolve("account/files"))
        registration.accepted(database, files)
        assertEquals(SaveTargetIssue.RETIRED, unknown.check(root))
        val known = registry.capture()
        assertNull(known.check(root.resolve("output/new/file.bin")))
        registration.confirmedClosed()
        assertEquals(SaveTargetIssue.RETIRED, known.check(root))
        assertNull(registry.capture().check(root.resolve("output")))
    }

    @Test fun bothActualSelectedRootsAndTheirMissingDescendantsAreRejectedWithoutCreation() = temporary { root ->
        val selected = Files.createDirectories(root.resolve("accounts/selected"))
        val database = Files.createDirectories(selected.resolve("database"))
        val files = Files.createDirectories(selected.resolve("files"))
        val registry = NativeSaveTargetRegistry()
        registry.registerUnknown(23).accepted(database, files)
        val guard = registry.capture()
        for (path in listOf(database, files, database.resolve("temp/missing.bin"), files.resolve("photos/new/file.bin"))) {
            assertEquals(SaveTargetIssue.NATIVE_SCAN_TREE, guard.check(path))
        }
        assertNull(guard.check(root.resolve("configured-but-not-selected/files/copy.bin")))
        assertNull(guard.check(selected.resolve("downloads-temp/user-43/copy.bin")))
        assertFalse(Files.exists(database.resolve("temp")))
        assertFalse(Files.exists(files.resolve("photos")))
    }

    @Test fun logicalCaseAndResolvedAliasesAreCheckedIndependently() = temporary { root ->
        val database = Files.createDirectories(root.resolve("database"))
        val files = Files.createDirectories(root.resolve("files"))
        val alias = Files.createDirectories(root.resolve("redirected-Downloads"))
        val paths = object : SaveTargetPathAccess by NioSaveTargetPathAccess {
            override fun realPath(path: Path): Path = if (path.startsWith(alias)) files.resolve(alias.relativize(path)) else path.toRealPath()
        }
        val registry = NativeSaveTargetRegistry(paths, ignoreCase = true)
        registry.registerUnknown(1).accepted(database, files)
        assertEquals(SaveTargetIssue.NATIVE_SCAN_TREE, registry.capture().check(alias.resolve("new/copy.bin")))
        assertEquals(SaveTargetIssue.NATIVE_SCAN_TREE, registry.capture().check(root.resolve("FiLeS/missing.bin")))
        assertNull(registry.capture().check(root.resolve("files-sibling/copy.bin")))
    }

    @Test fun unresolvableRootOrDestinationIsUnknownRatherThanPermissionToWrite() = temporary { root ->
        val database = Files.createDirectories(root.resolve("database"))
        val files = Files.createDirectories(root.resolve("files"))
        for (failure in listOf(database, root.resolve("out"))) {
            val paths = object : SaveTargetPathAccess by NioSaveTargetPathAccess {
                override fun realPath(path: Path): Path {
                    if (path == failure) throw IOException("synthetic resolution failure")
                    return path.toRealPath()
                }
                override fun exists(path: Path): Boolean {
                    if (path == failure) throw IOException("synthetic denied ancestor")
                    return NioSaveTargetPathAccess.exists(path)
                }
            }
            val registry = NativeSaveTargetRegistry(paths)
            registry.registerUnknown(1).accepted(database, files)
            assertEquals(SaveTargetIssue.UNKNOWN_NATIVE_ROOTS, registry.capture().check(root.resolve("out/new.bin")))
        }
    }

    @Test fun realBackgroundInventoryCannotBeReplacedByPreviewRootsOrAnInjectedAllowBoundary() = temporary { root ->
        val registration = NativeSaveTargets.registry.registerUnknown(999)
        try {
            val fakePreview = object : SaveTargetBoundary { override fun check(target: Path): SaveTargetIssue? = null }
            val source = root.resolve("source.bin").also { Files.writeString(it, "private synthetic source") }
            val rejected = assertFailsWith<SaveTargetRejected> {
                SavedMediaFiles.saveUnique(source, root.resolve("preview-output"), "copy.bin", fakePreview)
            }
            assertEquals(SaveTargetIssue.UNKNOWN_NATIVE_ROOTS, rejected.issue)
            assertFalse(Files.exists(root.resolve("preview-output")))
            assertEquals("private synthetic source", Files.readString(source))
        } finally { registration.confirmedClosed() }
    }

    @Test fun injectedRetiredOwnerCannotBypassRequireEvenIfItsCheckReturnsNull() = temporary { root ->
        val stale = object : SaveTargetBoundary {
            override fun current() = false
            override fun check(target: Path): SaveTargetIssue? = null
        }
        val source = root.resolve("source.bin").also { Files.writeString(it, "source") }
        val directory = root.resolve("output")
        val rejected = assertFailsWith<SaveTargetRejected> { SavedMediaFiles.saveUnique(source, directory, "copy.bin", stale) }
        assertEquals(SaveTargetIssue.RETIRED, rejected.issue)
        assertFalse(rejected.publicFileMayExist)
        assertFalse(Files.exists(directory))
        assertEquals("source", Files.readString(source))
    }

    @Test fun retiredSettingsWriteLeavesThePreviousPreferenceAndNoTemporaryFile() = temporary { root ->
        val path = root.resolve("ui-downloads.properties")
        val original = SettingsDownloadPreferences()
        var checks = 0
        val result = writeDownloadPreferences(path, original, original.copy(askEveryTime = true)) { ++checks < 2 }
        assertEquals(DownloadPreferencesWrite.FAILED, result)
        assertEquals(original, (readDownloadPreferences(path) as DownloadPreferencesRead.Value).preferences)
        assertFalse(Files.exists(path))
        Files.list(root).use { assertEquals(0L, it.count()) }
    }

    @Test fun allDirectoryModesUseTheSameBoundaryIncludingDefaultAndAccountFallbacks() = temporary { root ->
        val database = Files.createDirectories(root.resolve("account/database"))
        val files = Files.createDirectories(root.resolve("account/files"))
        val registry = NativeSaveTargetRegistry()
        registry.registerUnknown(1).accepted(database, files)
        val guard = registry.capture()
        for (preferences in listOf(
            SettingsDownloadPreferences(false),
            SettingsDownloadPreferences(false, SettingsDownloadDirectory.ACCOUNT_TEMP),
            SettingsDownloadPreferences(false, SettingsDownloadDirectory.CUSTOM, files.resolve("custom").toString()),
        )) {
            assertEquals(SaveTargetIssue.NATIVE_SCAN_TREE, assertFailsWith<SaveTargetRejected> {
                resolveCheckedDownloadDirectory(preferences, DownloadDestinationRoots(files.resolve("downloads"), database.resolve("temp")), guard)
            }.issue)
        }
        assertFailsWith<SaveTargetRejected> {
            resolveCheckedDownloadDirectory(SettingsDownloadPreferences(false), DownloadDestinationRoots(null, database.resolve("fallback")), guard)
        }
        assertNull(resolveCheckedDownloadDirectory(SettingsDownloadPreferences(false), DownloadDestinationRoots(null, null), guard))
    }

    @Test fun rejectedUniqueAndSelectedSavesPreserveSourceAndExistingDestination() = temporary { root ->
        val database = Files.createDirectories(root.resolve("database"))
        val files = Files.createDirectories(root.resolve("files"))
        val source = root.resolve("source.bin").also { Files.writeString(it, "new bytes") }
        val target = files.resolve("existing.bin").also { Files.writeString(it, "existing user file") }
        val registry = NativeSaveTargetRegistry()
        registry.registerUnknown(1).accepted(database, files)
        val guard = registry.capture()
        assertFailsWith<SaveTargetRejected> { SavedMediaFiles.saveUnique(source, files.resolve("new"), "copy.bin", guard) }
        assertFailsWith<SaveTargetRejected> { SavedMediaFiles.saveSelected(source, target, true, guard) }
        assertFalse(Files.exists(files.resolve("new")))
        assertEquals("existing user file", Files.readString(target))
        assertEquals("new bytes", Files.readString(source))
    }

    @Test fun aTargetWhichBecomesForbiddenAtFinalReplacementNeverConsumesTheOldFile() = temporary { root ->
        val source = root.resolve("source.bin").also { Files.writeString(it, "new") }
        val target = root.resolve("saved.bin").also { Files.writeString(it, "old") }
        var targetChecks = 0
        val guard = object : SaveTargetBoundary {
            override fun check(path: Path): SaveTargetIssue? =
                if (path == target && ++targetChecks >= 2) SaveTargetIssue.UNKNOWN_NATIVE_ROOTS else null
        }
        assertFailsWith<SaveTargetRejected> { SavedMediaFiles.saveSelected(source, target, true, guard) }
        assertEquals("old", Files.readString(target))
        assertEquals("new", Files.readString(source))
        Files.list(root).use { assertEquals(2L, it.count()) }
    }

    @Test fun aChangeDetectedAfterPublicationReportsThePossibleCopyAndNeverRetriesOrDeletesIt() = temporary { root ->
        val source = root.resolve("source.bin").also { Files.writeString(it, "source") }
        val output = Files.createDirectories(root.resolve("output"))
        val target = output.resolve("copy.bin")
        var targetChecks = 0
        val guard = object : SaveTargetBoundary {
            override fun check(path: Path): SaveTargetIssue? =
                if (path == target && ++targetChecks >= 2) SaveTargetIssue.UNKNOWN_NATIVE_ROOTS else null
        }
        assertFailsWith<SaveTargetPublicationUncertain> { SavedMediaFiles.saveUnique(source, output, "copy.bin", guard) }
        assertEquals("source", Files.readString(source))
        assertEquals("source", Files.readString(target))
        Files.list(output).use { assertEquals(1L, it.count()) }
    }

    @Test fun sameSourceIsAnExplicitFailureEvenOutsideTheKnownScanTrees() = temporary { root ->
        val source = root.resolve("source.bin").also { Files.writeString(it, "native bytes") }
        assertEquals(SaveTargetIssue.SAME_SOURCE, assertFailsWith<SaveTargetRejected> {
            SavedMediaFiles.saveSelected(source, source, true)
        }.issue)
        assertEquals("native bytes", Files.readString(source))
    }

    private fun temporary(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("save-target-boundary-")
        try { block(root) } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { path ->
                check(path.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize()))
                Files.deleteIfExists(path)
            } }
        }
    }
}
