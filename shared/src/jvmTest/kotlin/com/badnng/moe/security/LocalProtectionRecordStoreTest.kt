package com.badnng.moe.security

import com.badnng.moe.settings.LocalLockIssue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Each case owns a newly created synthetic root; no account or native session is used. */
class LocalProtectionRecordStoreTest {
    private val identity = ByteArray(LocalEnvelopeFormat.ROOT_BYTES) { (it + 1).toByte() }

    @Test
    fun explicitUnavailableDurabilityRejectsCommitWithoutWriting() = inSyntheticRoot { directory ->
        val root = root(directory, durable = false)
        val store = LocalProtectionRecordStore(root)
        assertEquals(LocalRecordRead.Absent, store.read())
        assertEquals(LocalCommitResult.UNAVAILABLE, store.commit(envelope(identity), null))
        assertFalse(Files.exists(directory.resolve("local-protection.wrap")))
        assertFalse(Files.exists(directory.resolve("local-protection.pending")))
    }

    @Test
    fun pendingCandidateIsRecoveryEvidenceAndIsNeverMistakenForAbsentProtection() = inSyntheticRoot { directory ->
        val pending = directory.resolve("local-protection.pending")
        val contents = byteArrayOf(3, 1, 4)
        Files.write(pending, contents)
        val store = LocalProtectionRecordStore(root(directory))
        assertEquals(LocalLockIssue.IO_FAILURE, assertIs<LocalRecordRead.Failed>(store.read()).issue)
        assertTrue(Files.readAllBytes(pending).contentEquals(contents))
        assertFalse(Files.exists(directory.resolve("local-protection.wrap")))
    }

    @Test
    fun previousBackupOrPendingMustEnterRecoveryForAnyRecordCombination() = inSyntheticRoot { directory ->
        val record = directory.resolve("local-protection.wrap")
        val pending = directory.resolve("local-protection.pending")
        val previous = directory.resolve("local-protection.previous")
        val store = LocalProtectionRecordStore(root(directory))
        for (hasRecord in listOf(false, true)) for (hasPending in listOf(false, true)) for (hasPrevious in listOf(false, true)) {
            if (!hasPending && !hasPrevious) continue
            if (hasRecord) Files.write(record, envelope(identity)) else Files.deleteIfExists(record)
            if (hasPending) Files.write(pending, byteArrayOf(1)) else Files.deleteIfExists(pending)
            if (hasPrevious) Files.write(previous, byteArrayOf(2)) else Files.deleteIfExists(previous)
            assertEquals(LocalLockIssue.IO_FAILURE, assertIs<LocalRecordRead.Failed>(store.read()).issue)
            assertEquals(hasRecord, Files.exists(record))
            assertEquals(hasPending, Files.exists(pending))
            assertEquals(hasPrevious, Files.exists(previous))
        }
    }

    @Test
    fun backendSuccessWithoutAReadBackDoesNotReportCommitted() = inSyntheticRoot { directory ->
        var calls = 0
        val backend = LocalLockCommitBackend { _, _, _, _ -> calls++; LocalCommitResult.COMMITTED }
        val store = LocalProtectionRecordStore(root(directory), backend)
        assertEquals(LocalCommitResult.INDETERMINATE, store.commit(envelope(identity), null))
        assertEquals(1, calls)
        assertTrue(Files.exists(directory.resolve("local-protection.pending")))
        assertEquals(LocalLockIssue.IO_FAILURE, assertIs<LocalRecordRead.Failed>(store.read()).issue)
    }

    @Test
    fun backendThatCopiesButLeavesPendingCannotClaimCommit() = inSyntheticRoot { directory ->
        val backend = LocalLockCommitBackend { pending, record, _, _ ->
            Files.copy(pending, record)
            LocalCommitResult.COMMITTED
        }
        val store = LocalProtectionRecordStore(root(directory), backend)
        assertEquals(LocalCommitResult.INDETERMINATE, store.commit(envelope(identity), null))
        assertTrue(Files.exists(directory.resolve("local-protection.pending")))
        assertTrue(Files.exists(directory.resolve("local-protection.wrap")))
        assertEquals(LocalLockIssue.IO_FAILURE, assertIs<LocalRecordRead.Failed>(store.read()).issue)
    }

    @Test
    fun replacingRecordKeepsTheOldBackupUntilSeparateValidatedFinalization() = inSyntheticRoot { directory ->
        val path = directory.resolve("local-protection.wrap")
        val backup = directory.resolve("local-protection.previous")
        val oldBytes = envelope(identity, generation = 1)
        val candidate = envelope(identity, generation = 2)
        Files.write(path, oldBytes)
        val fake = LocalLockCommitBackend { pending, record, previous, replacing ->
            assertTrue(replacing)
            Files.copy(record, previous)
            Files.copy(pending, record, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            Files.delete(pending)
            LocalCommitResult.COMMITTED
        }
        val store = LocalProtectionRecordStore(root(directory), fake)
        val old = assertIs<LocalRecordRead.Present>(store.read())
        val expected = old.envelope
        old.close()
        assertEquals(LocalCommitResult.COMMITTED, store.commit(candidate, expected))
        assertTrue(Files.readAllBytes(backup).contentEquals(oldBytes))
        assertEquals(LocalLockIssue.IO_FAILURE, assertIs<LocalRecordRead.Failed>(store.read()).issue)

        Files.write(backup, oldBytes.copyOf().also { it[it.lastIndex] = 1 })
        assertEquals(LocalCommitResult.INDETERMINATE, store.finalizeCommit(candidate, expected))
        assertTrue(Files.exists(backup), "A mismatching backup must remain available for recovery.")
        Files.write(backup, oldBytes)
        assertEquals(LocalCommitResult.COMMITTED, store.finalizeCommit(candidate, expected))
        assertFalse(Files.exists(backup))
        val saved = assertIs<LocalRecordRead.Present>(store.read())
        assertEquals(2L, saved.envelope.generation)
        saved.close()
    }

    @Test
    fun truncatedOversizedUnknownAndWrongRootRecordsCannotBecomeAbsent() = inSyntheticRoot { directory ->
        val file = directory.resolve("local-protection.wrap")
        val store = LocalProtectionRecordStore(root(directory))
        val original = envelope(identity)
        Files.write(file, original.copyOf(original.size - 1))
        assertEquals(LocalLockIssue.CORRUPT_RECORD, assertIs<LocalRecordRead.Failed>(store.read()).issue)
        Files.write(file, original + byteArrayOf(0))
        assertEquals(LocalLockIssue.CORRUPT_RECORD, assertIs<LocalRecordRead.Failed>(store.read()).issue)
        Files.write(file, original.copyOf().also { it[8] = 2 })
        assertEquals(LocalLockIssue.UNSUPPORTED_RECORD, assertIs<LocalRecordRead.Failed>(store.read()).issue)
        Files.write(file, original.copyOf().also { it[LocalEnvelopeFormat.ROOT_OFFSET] = 99 })
        assertEquals(LocalLockIssue.ROOT_IDENTITY_UNAVAILABLE, assertIs<LocalRecordRead.Failed>(store.read()).issue)
    }

    @Test
    fun readChecksRootOwnershipAgainAndStructuralRecognitionIsNotPasswordProof() = inSyntheticRoot { directory ->
        Files.write(directory.resolve("local-protection.wrap"), envelope(identity))
        val recognized = LocalProtectionRecordStore(root(directory)).read()
        val present = assertIs<LocalRecordRead.Present>(recognized)
        assertEquals(1L, present.envelope.generation)
        assertEquals(32, present.envelope.revision.size)
        // This fixture deliberately contains an invalid GCM payload. A structural read
        // recognizes its bounded format but does not authenticate or unlock it.
        val opened = LocalPasscodeCryptoCore.forTesting(
            PasscodeKdf { _, _ -> ByteArray(32) },
            CryptoEntropy { it.fill(1) },
        ).open("synthetic".toCharArray(), present.envelope.bytes, identity, 1)
        assertEquals(LocalCryptoResult.AuthenticationFailed, opened)

        var checks = 0
        val changing = LocalProtectionRoot(directory, { identity.copyOf() }, { ++checks == 1 }, { true })
        assertEquals(LocalLockIssue.ROOT_IDENTITY_UNAVAILABLE,
            assertIs<LocalRecordRead.Failed>(LocalProtectionRecordStore(changing).read()).issue)
    }

    private fun root(directory: Path, durable: Boolean = true) = LocalProtectionRoot(
        path = directory,
        identity = { identity.copyOf() },
        isCurrent = { true },
        freshEligible = { true },
        durableCommitAvailable = { durable },
    )

    private fun envelope(rootIdentity: ByteArray, generation: Long = 1): ByteArray =
        ByteBuffer.allocate(LocalEnvelopeFormat.RECORD_BYTES).order(ByteOrder.BIG_ENDIAN).apply {
            put(LocalEnvelopeFormat.magic)
            put(1); put(1); put(1); put(0)
            putInt(LocalEnvelopeFormat.MEMORY_KIB)
            putInt(LocalEnvelopeFormat.ITERATIONS)
            putInt(LocalEnvelopeFormat.LANES)
            putLong(generation)
            put(rootIdentity)
            put(ByteArray(LocalEnvelopeFormat.SALT_BYTES))
            put(ByteArray(LocalEnvelopeFormat.NONCE_BYTES))
            putShort(48)
            put(ByteArray(48))
        }.array()

    private inline fun inSyntheticRoot(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("nnggram-local-lock-record-test-")
        try {
            block(directory)
        } finally {
            // Delete only the three exact fixture files this test can create, never traverse.
            Files.deleteIfExists(directory.resolve("local-protection.pending"))
            Files.deleteIfExists(directory.resolve("local-protection.wrap"))
            Files.deleteIfExists(directory.resolve("local-protection.previous"))
            Files.deleteIfExists(directory)
        }
    }
}
