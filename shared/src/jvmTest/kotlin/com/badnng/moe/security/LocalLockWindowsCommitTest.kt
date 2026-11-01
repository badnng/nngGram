package com.badnng.moe.security

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/** Exercises the raw decision boundary with fake calls; it never loads kernel32. */
class LocalLockWindowsCommitTest {
    private val directory = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
        .resolve("nnggram-synthetic-commit-names")
    private val pending = directory.resolve("local-protection.pending")
    private val record = directory.resolve("local-protection.wrap")
    private val backup = directory.resolve("local-protection.previous")

    @Test
    fun onlyTheThreeNormalizedAbsoluteSiblingNamesReachWindowsCalls() {
        val fake = FakeCalls()
        var loads = 0
        val adapter = LocalLockWindowsCommitAdapter(isWindows = { true }, calls = { loads++; fake })
        assertEquals(LocalCommitResult.UNAVAILABLE,
            adapter.commit(Path.of("local-protection.pending"), record, backup, false))
        assertEquals(LocalCommitResult.UNAVAILABLE,
            adapter.commit(pending, record, directory.resolve("other.previous"), false))
        assertEquals(LocalCommitResult.UNAVAILABLE,
            adapter.commit(pending, record, directory.resolve("child").resolve("local-protection.previous"), false))
        assertEquals(LocalCommitResult.UNAVAILABLE,
            adapter.commit(directory.resolve("nested").resolve("..").resolve("local-protection.pending"), record, backup, false))
        val nonWindows = LocalLockWindowsCommitAdapter(isWindows = { false }, calls = { loads++; fake })
        assertEquals(LocalCommitResult.UNAVAILABLE, nonWindows.commit(pending, record, backup, false))
        assertEquals(0, loads)
        assertEquals(0, fake.publishes + fake.replacements)
    }

    @Test
    fun newAndReplacementUseDistinctSingleCallsWithTheActualNames() {
        val fake = FakeCalls()
        val adapter = LocalLockWindowsCommitAdapter(isWindows = { true }, calls = { fake })
        assertEquals(LocalCommitResult.COMMITTED, adapter.commit(pending, record, backup, false))
        assertEquals(1, fake.publishes)
        assertEquals(0, fake.replacements)
        assertEquals(listOf(pending, record), fake.lastPaths)
        assertEquals(LocalCommitResult.COMMITTED, adapter.commit(pending, record, backup, true))
        assertEquals(1, fake.publishes)
        assertEquals(1, fake.replacements)
        assertEquals(listOf(pending, record, backup), fake.lastPaths)
    }

    @Test
    fun uncertainIssuedNativeCallNeverBecomesSuccessfulOrAnAutomaticRetry() {
        val fake = FakeCalls().apply { result = false }
        val adapter = LocalLockWindowsCommitAdapter(isWindows = { true }, calls = { fake })
        assertEquals(LocalCommitResult.INDETERMINATE, adapter.commit(pending, record, backup, false))
        assertEquals(1, fake.publishes)
        fake.throws = true
        assertEquals(LocalCommitResult.INDETERMINATE, adapter.commit(pending, record, backup, true))
        assertEquals(1, fake.replacements)
        val unavailable = LocalLockWindowsCommitAdapter(isWindows = { true }, calls = { error("synthetic loader failure") })
        assertEquals(LocalCommitResult.UNAVAILABLE, unavailable.commit(pending, record, backup, true))
    }

    private class FakeCalls : LocalLockWindowsCalls {
        var publishes = 0
        var replacements = 0
        var result = true
        var throws = false
        var lastPaths: List<Path> = emptyList()
        override fun publishNew(pending: Path, record: Path): Boolean {
            publishes++
            lastPaths = listOf(pending, record)
            if (throws) error("synthetic issued failure")
            return result
        }
        override fun replaceWithBackup(pending: Path, record: Path, backup: Path): Boolean {
            replacements++
            lastPaths = listOf(pending, record, backup)
            if (throws) error("synthetic issued failure")
            return result
        }
    }
}
