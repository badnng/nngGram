package com.badnng.moe.security

import com.badnng.moe.settings.LocalLockPhase
import com.badnng.moe.settings.LocalLockIssue
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Internal owner checks use synthetic roots and bounded latches, never files or native code. */
class LocalSessionOwnerReadTest {
    @Test
    fun validationAdmissionRequiresCurrentGrantedPendingTicketAndCurrentEngineOwner() {
        val f = Fixture()
        val pending = f.pending(grant = false)
        assertFalse(f.controller.admitSessionParameterValidation(pending, f.revision) { true })
        assertTrue(f.controller.grantSessionCreate(pending, f.revision) { true })
        var checks = 0
        assertFalse(f.controller.admitSessionParameterValidation(pending, f.revision + 1) { checks++; true })
        assertEquals(0, checks)
        assertFalse(f.controller.admitSessionParameterValidation(pending, f.revision) { checks++; false })
        assertFalse(f.controller.admitSessionParameterValidation(pending, f.revision) { checks++; throw AssertionError("synthetic owner") })
        assertTrue(f.controller.admitSessionParameterValidation(pending, f.revision) { checks++; true })
        assertEquals(3, checks)
        assertTrue(f.controller.borrowSessionKey(pending, { true }) { assertEquals(32, it.size) })
        assertFalse(f.controller.admitSessionParameterValidation(pending, f.revision) { error("already submitted") })
    }

    @Test
    fun revokeDuringEachOwnerReadStopsBeforeTheNextReadAndNeverDispatches() {
        for (stage in listOf(Stage.ROOT, Stage.REVISION, Stage.IDENTITY, Stage.RECORD)) {
            for (lock in listOf(false, true)) {
                val f = Fixture()
                val pending = f.pending(grant = true)
                val ownedKey = f.crypto.lastOpened!!
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                val validEngine = AtomicBoolean(true)
                val failure = AtomicReference<Throwable?>()
                val result = AtomicReference<Boolean?>()
                val dispatched = AtomicBoolean(false)
                f.barrier.set(Barrier(stage, entered, release))
                val worker = Thread({
                    try { result.set(f.controller.borrowSessionKey(pending, { validEngine.get() }) { dispatched.set(true) }) }
                    catch (error: Throwable) { failure.compareAndSet(null, error) }
                }, "synthetic-owner-read").apply { isDaemon = true; start() }
                try {
                    assertTrue(entered.await(2, TimeUnit.SECONDS), "$stage was not reached")
                    val atBarrier = f.counts()
                    if (lock) f.controller.actions.lockNow() else validEngine.set(false)
                    release.countDown()
                    worker.join(2_000)
                    assertFalse(worker.isAlive)
                    assertEquals(null, failure.get())
                    assertEquals(false, result.get())
                    assertFalse(dispatched.get())
                    val after = f.counts()
                    when (stage) {
                        Stage.ROOT -> assertEquals(atBarrier.drop(1), after.drop(1), "No revision, identity or record read after root revocation.")
                        Stage.REVISION -> assertEquals(atBarrier.drop(2), after.drop(2), "No identity or record read after revision revocation.")
                        Stage.IDENTITY -> assertEquals(atBarrier.drop(3), after.drop(3), "No record read after identity revocation.")
                        Stage.RECORD -> assertEquals(atBarrier, after, "No owner read restarts after record revocation.")
                    }
                    if (lock) {
                        assertTrue(f.controller.currentWitness().blocked)
                        assertEquals(LocalCryptoResult.KeyUnavailable, ownedKey.borrow { LocalCryptoResult.Success(Unit) })
                    } else {
                        assertFalse(f.controller.currentWitness().blocked)
                        assertEquals(LocalLockPhase.UNLOCKED, f.controller.state.value.phase,
                            "A revoked Engine owner is not proof of a corrupt protection record.")
                    }
                } finally {
                    release.countDown()
                    worker.join(2_000)
                    f.barrier.set(null)
                }
            }
        }
    }

    @Test
    fun renewalAndPendingResultStopFollowingReadsWhenTheirOwnerIsRevoked() {
        for (operation in listOf("renew", "result")) for (stage in listOf(Stage.ROOT, Stage.IDENTITY)) {
            for (lock in listOf(false, true)) {
                val f = Fixture()
                val ticket = if (operation == "renew") {
                    f.settled().also { f.revision++ }
                } else {
                    f.pending(grant = true).also {
                        assertTrue(f.controller.borrowSessionKey(it, { true }) {})
                    }
                }
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                val validEngine = AtomicBoolean(true)
                val failure = AtomicReference<Throwable?>()
                val renewed = AtomicReference<LocalSessionKeyTicket?>()
                f.barrier.set(Barrier(stage, entered, release))
                val worker = Thread({
                    try {
                        if (operation == "renew") renewed.set(f.controller.renewSessionKeyAfterClosed(ticket) { validEngine.get() })
                        else f.controller.nativeStartupResult(ticket.epoch, success = true, mismatch401 = false,
                            engineCurrent = { validEngine.get() })
                    } catch (error: Throwable) { failure.compareAndSet(null, error) }
                }, "synthetic-$operation-owner-read").apply { isDaemon = true; start() }
                try {
                    assertTrue(entered.await(2, TimeUnit.SECONDS), "$operation/$stage was not reached")
                    val atBarrier = f.counts()
                    if (lock) f.controller.actions.lockNow() else validEngine.set(false)
                    release.countDown()
                    worker.join(2_000)
                    assertFalse(worker.isAlive)
                    assertEquals(null, failure.get())
                    if (operation == "renew") assertEquals(null, renewed.get())
                    val after = f.counts()
                    if (stage == Stage.ROOT) assertEquals(atBarrier.drop(1), after.drop(1))
                    else assertEquals(atBarrier.drop(3), after.drop(3))
                    if (lock) assertTrue(f.controller.currentWitness().blocked)
                    else {
                        assertFalse(f.controller.currentWitness().blocked)
                        assertEquals(LocalLockPhase.UNLOCKED, f.controller.state.value.phase,
                            "A stale Engine owner cannot be relabeled as a corrupt record.")
                    }
                } finally {
                    release.countDown()
                    worker.join(2_000)
                    f.barrier.set(null)
                }
            }
        }
    }

    @Test
    fun confirmedParameter401CannotBeOverwrittenByAnOldValidationOrSuccessResult() {
        val f = Fixture()
        val pending = f.pending(grant = true)
        assertTrue(f.controller.borrowSessionKey(pending, { true }) {})
        f.controller.nativeStartupResult(pending.epoch, success = false, mismatch401 = true, engineCurrent = { true })
        assertEquals(LocalLockPhase.RECOVERY, f.controller.state.value.phase)
        assertEquals(LocalLockIssue.DATABASE_MISMATCH, f.controller.state.value.issue)
        assertFalse(f.controller.admitSessionParameterValidation(pending, f.revision) { error("retired owner") })
        f.controller.nativeStartupResult(pending.epoch, success = true, mismatch401 = false, engineCurrent = { true })
        assertEquals(LocalLockPhase.RECOVERY, f.controller.state.value.phase)
        assertEquals(LocalLockIssue.DATABASE_MISMATCH, f.controller.state.value.issue)
    }

    private enum class Stage { ROOT, REVISION, IDENTITY, RECORD }
    private data class Barrier(val stage: Stage, val entered: CountDownLatch, val release: CountDownLatch)

    private class Fixture {
        val identity = ByteArray(32) { (it + 1).toByte() }
        var revision = 1L
        val rootReads = AtomicInteger()
        val revisionReads = AtomicInteger()
        val identityReads = AtomicInteger()
        val barrier = AtomicReference<Barrier?>()
        val worker = FakeWorker()
        val store = FakeStore(barrier)
        val crypto = FakeCrypto()
        val starts = mutableListOf<Pair<Long, Boolean>>()
        private val root = LocalProtectionRoot(Path.of("synthetic-owner-read-no-files"), {
            identityReads.incrementAndGet(); waitAt(Stage.IDENTITY); identity.copyOf()
        }, {
            rootReads.incrementAndGet(); waitAt(Stage.ROOT); true
        }, { false }, revision = {
            revisionReads.incrementAndGet(); waitAt(Stage.REVISION); revision
        }, durableCommitAvailable = { true })
        val controller = LocalLockCoordinator(root, worker, { epoch, protected -> starts += epoch to protected },
            persistence = store, crypto = crypto, preferences = object : LocalLockPreferences {
                override fun read(): LocalLockPreferenceRead = LocalLockPreferenceRead.Value(3_600)
                override fun write(seconds: Long) = true
            })

        fun settled(): LocalSessionKeyTicket {
            controller.initialize()
            controller.actions.unlock("synthetic".toCharArray())
            worker.runNext()
            val epoch = starts.single().first
            assertTrue(controller.borrowStartupKey(epoch) {})
            controller.nativeStartupResult(epoch, success = true, mismatch401 = false)
            return assertNotNull(controller.captureSessionKeyTicket())
        }

        fun pending(grant: Boolean): LocalSessionKeyTicket {
            val original = settled()
            revision++
            val pending = assertNotNull(controller.renewSessionKeyAfterClosed(original))
            if (grant) assertTrue(controller.grantSessionCreate(pending, revision) { true })
            return pending
        }

        fun counts() = listOf(rootReads.get(), revisionReads.get(), identityReads.get(), store.reads.get())
        private fun waitAt(stage: Stage) {
            barrier.get()?.takeIf { it.stage == stage }?.let {
                it.entered.countDown()
                check(it.release.await(3, TimeUnit.SECONDS))
            }
        }
    }

    private class FakeWorker : Executor {
        private val pending = mutableListOf<Runnable>()
        override fun execute(command: Runnable) { pending += command }
        fun runNext() { pending.removeAt(0).run() }
    }

    private class FakeStore(private val barrier: AtomicReference<Barrier?>) : LocalProtectionPersistence {
        val reads = AtomicInteger()
        private val bytes = byteArrayOf(1, 2, 3)
        override fun read(): LocalRecordRead {
            reads.incrementAndGet()
            barrier.get()?.takeIf { it.stage == Stage.RECORD }?.let {
                it.entered.countDown()
                check(it.release.await(3, TimeUnit.SECONDS))
            }
            return LocalRecordRead.Present(LocalStoredEnvelope(bytes.copyOf(), 1, MessageDigest.getInstance("SHA-256").digest(bytes)))
        }
        override fun commit(candidate: ByteArray, expected: LocalStoredEnvelope?): LocalCommitResult = error("No record writes")
        override fun finalizeCommit(candidate: ByteArray, expected: LocalStoredEnvelope?): LocalCommitResult = error("No record finalization")
    }

    private class FakeCrypto : LocalLockCryptoOperations {
        var lastOpened: LocalDatabaseKey? = null
        override fun create(password: CharArray, rootIdentity: ByteArray, generation: Long): LocalCryptoResult<WrappedDatabaseKey> = error("Not used")
        override fun open(password: CharArray, record: ByteArray, rootIdentity: ByteArray, generation: Long): LocalCryptoResult<LocalDatabaseKey> =
            LocalCryptoResult.Success(LocalDatabaseKey.copyOf(ByteArray(32) { 42 }).also { lastOpened = it })
        override fun rewrap(password: CharArray, key: LocalDatabaseKey, rootIdentity: ByteArray, generation: Long): LocalCryptoResult<ByteArray> = error("Not used")
    }
}
