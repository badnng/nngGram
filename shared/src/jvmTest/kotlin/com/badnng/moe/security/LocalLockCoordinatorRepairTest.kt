package com.badnng.moe.security

import com.badnng.moe.settings.LocalLockIssue
import com.badnng.moe.settings.LocalLockPhase
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** All roots, persistence, entropy and failures are synthetic; no files or native client. */
class LocalLockCoordinatorRepairTest {
    @Test
    fun skipInspectFailureClearsBothBorrowedArraysAndReleasesTheSlotBeforeExplicitRetry() {
        val f = Fixture()
        f.failInspectAt = 1
        f.controller.initialize()
        f.controller.actions.skipSetup()
        f.worker.runNext()
        assertTrue(f.encoded!!.all { it == 0.toByte() })
        assertTrue(f.entropyBytes!!.all { it == 0.toByte() })
        assertEquals(0, f.store.commits)
        assertEquals(0, f.store.finalizations)
        assertTrue(f.starts.isEmpty())
        assertFalse(f.controller.state.value.busy)
        assertTrue(f.controller.currentWitness().blocked)
        assertEquals(LocalLockPhase.NEW_ROOT_SETUP, f.controller.state.value.phase)
        assertEquals(LocalLockIssue.CRYPTO_UNAVAILABLE, f.controller.state.value.issue)

        f.failInspectAt = -1
        f.controller.actions.skipSetup()
        f.worker.runNext()
        assertTrue(f.starts.single().second, "Only a later explicit retry may start the new root.")
    }

    @Test
    fun freshReadbackExceptionBeforeFinalizeOrAfterFinalizeClosesBorrowedKeysAndNeverStarts() {
        for (failure in listOf("stage-digest", "stage-inspect", "final-digest", "final-inspect")) {
            val f = Fixture()
            when (failure) {
                "stage-digest" -> f.failDigestAt = 1
                "final-digest" -> f.failDigestAt = 2
                "stage-inspect" -> f.failInspectAt = 2 // first inspect validates encoded SKIP
                "final-inspect" -> f.failInspectAt = 3
            }
            f.controller.initialize()
            f.controller.actions.skipSetup()
            f.worker.runNext()
            assertEquals(1, f.store.commits)
            assertEquals(if (failure.startsWith("stage")) 0 else 1, f.store.finalizations)
            assertTrue(f.encoded!!.all { it == 0.toByte() }, failure)
            assertTrue(f.entropyBytes!!.all { it == 0.toByte() }, failure)
            assertTrue(f.store.readKeys.isNotEmpty())
            f.store.readKeys.forEach { key ->
                assertEquals(LocalCryptoResult.KeyUnavailable, key.borrow { LocalCryptoResult.Success(Unit) })
            }
            assertTrue(f.starts.isEmpty(), "No readback exception may initialize native storage: $failure")
            assertEquals(LocalLockPhase.RECOVERY, f.controller.state.value.phase)
            assertTrue(f.controller.currentWitness().blocked)
            assertFalse(f.controller.state.value.busy)
            assertTrue(f.worker.pending.isEmpty())
        }
    }

    @Test
    fun finalReadbackExceptionClosesTheActualCreatedKeyAndCannotPublishSuccess() {
        val f = Fixture()
        f.failDigestAt = 2
        f.controller.initialize()
        val password = "synthetic".toCharArray()
        f.controller.actions.create(password, password.copyOf())
        f.worker.runNext()
        assertTrue(password.all { it == '\u0000' })
        assertEquals(1, f.store.commits)
        assertEquals(1, f.store.finalizations)
        assertEquals(LocalCryptoResult.KeyUnavailable, f.crypto.createdKey!!.borrow { LocalCryptoResult.Success(Unit) })
        assertTrue(f.starts.isEmpty())
        assertEquals(LocalLockPhase.RECOVERY, f.controller.state.value.phase)
        assertTrue(f.controller.currentWitness().blocked)
        assertFalse(f.controller.state.value.busy)
    }

    @Test
    fun finalReadbackExceptionAfterRemovalClosesTransferredProofAndKeepsTheOwnerBlocked() {
        val f = Fixture(fresh = false)
        f.controller.initialize()
        f.controller.actions.unlock("synthetic".toCharArray())
        f.worker.runNext()
        val epoch = f.starts.single().first
        assertTrue(f.controller.borrowStartupKey(epoch) {})
        f.controller.nativeStartupResult(epoch, success = true, mismatch401 = false)
        f.controller.actions.verifyManagement("synthetic".toCharArray())
        f.worker.runNext()
        val proof = f.crypto.lastOpened!!
        f.failDigestAt = 1 // replacement records have no fresh-stage readback
        f.controller.actions.remove()
        f.worker.runNext()
        assertEquals(1, f.store.commits)
        assertEquals(1, f.store.finalizations)
        assertEquals(LocalCryptoResult.KeyUnavailable, proof.borrow { LocalCryptoResult.Success(Unit) })
        assertTrue(f.encoded!!.all { it == 0.toByte() })
        assertEquals(1, f.starts.size)
        assertEquals(LocalLockPhase.RECOVERY, f.controller.state.value.phase)
        assertTrue(f.controller.currentWitness().blocked)
        assertFalse(f.controller.state.value.busy)
    }

    private class Fixture(fresh: Boolean = true) {
        private val identity = ByteArray(32) { (it + 1).toByte() }
        val worker = FakeWorker()
        val store = FakeStore(identity, fresh)
        val crypto = FakeCrypto()
        val starts = mutableListOf<Pair<Long, Boolean>>()
        var entropyBytes: ByteArray? = null
        var encoded: ByteArray? = null
        var failInspectAt = -1
        var failDigestAt = -1
        private var inspectCalls = 0
        private var digestCalls = 0
        private val root = LocalProtectionRoot(Path.of("synthetic-repair-no-files"), { identity.copyOf() },
            { true }, { fresh }, revision = { 1L }, durableCommitAvailable = { true })
        val controller = LocalLockCoordinator(root, worker, { epoch, protected -> starts += epoch to protected },
            persistence = store, crypto = crypto,
            preferences = object : LocalLockPreferences {
                override fun read(): LocalLockPreferenceRead = LocalLockPreferenceRead.Value(3600)
                override fun write(seconds: Long) = true
            },
            entropy = CryptoEntropy { bytes -> entropyBytes = bytes; bytes.fill(42) },
            inspectOpenWrap = { bytes, rootId ->
                encoded = bytes
                inspectCalls++
                if (inspectCalls == failInspectAt) throw AssertionError("synthetic OPEN inspect failure")
                LocalOpenWrap.inspect(bytes, rootId)
            },
            recordDigest = { bytes ->
                digestCalls++
                if (digestCalls == failDigestAt) throw AssertionError("synthetic digest failure")
                digest(bytes)
            })
    }

    private class FakeWorker : Executor {
        val pending = mutableListOf<Runnable>()
        override fun execute(command: Runnable) { pending += command }
        fun runNext() { pending.removeAt(0).run() }
    }

    private class FakeStore(private val identity: ByteArray, fresh: Boolean) : LocalProtectionPersistence {
        private var current: ByteArray? = if (fresh) null else byteArrayOf(1, 2, 3)
        private var generation = if (fresh) 0L else 1L
        var commits = 0
        var finalizations = 0
        val readKeys = mutableListOf<LocalDatabaseKey>()

        override fun read(): LocalRecordRead {
            val bytes = current ?: return LocalRecordRead.Absent
            if (bytes.size != LocalOpenWrap.RECORD_BYTES) {
                return LocalRecordRead.Present(LocalStoredEnvelope(bytes.copyOf(), generation, digest(bytes)))
            }
            val inspected = LocalOpenWrap.inspect(bytes, identity)
            if (inspected !is LocalRecordRead.Present) return inspected
            val key = inspected.takeOpenKey()!!
            val envelope = inspected.envelope
            inspected.close()
            readKeys += key
            return LocalRecordRead.Present(envelope, key)
        }

        override fun commit(candidate: ByteArray, expected: LocalStoredEnvelope?): LocalCommitResult {
            commits++
            current = candidate.copyOf()
            generation = (expected?.generation ?: 0) + 1
            return LocalCommitResult.COMMITTED
        }

        override fun finalizeCommit(candidate: ByteArray, expected: LocalStoredEnvelope?): LocalCommitResult {
            finalizations++
            return LocalCommitResult.COMMITTED
        }
    }

    private class FakeCrypto : LocalLockCryptoOperations {
        var createdKey: LocalDatabaseKey? = null
        var lastOpened: LocalDatabaseKey? = null
        private fun key() = LocalDatabaseKey.copyOf(ByteArray(32) { 42 })
        override fun create(password: CharArray, rootIdentity: ByteArray, generation: Long): LocalCryptoResult<WrappedDatabaseKey> {
            val key = key().also { createdKey = it }
            return LocalCryptoResult.Success(WrappedDatabaseKey(byteArrayOf(7, 8, 9), key))
        }
        override fun open(password: CharArray, record: ByteArray, rootIdentity: ByteArray, generation: Long): LocalCryptoResult<LocalDatabaseKey> =
            LocalCryptoResult.Success(key().also { lastOpened = it })
        override fun rewrap(password: CharArray, key: LocalDatabaseKey, rootIdentity: ByteArray, generation: Long): LocalCryptoResult<ByteArray> =
            LocalCryptoResult.Success(byteArrayOf(4, 5, 6))
    }

    private companion object {
        fun digest(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    }
}
