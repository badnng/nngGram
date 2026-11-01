package com.badnng.moe.security

import com.badnng.moe.settings.LocalLockIssue
import com.badnng.moe.settings.LocalLockPhase
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Tests the coordinator contract only; a direct renewal is not evidence of real native Closed. */
class LocalSessionKeyTicketTest {
    @Test
    fun ticketAppearsOnlyAfterColdStartupAndSynchronousBorrowClearsItsTemporaryCopy() {
        val f = Fixture()
        f.controller.initialize()
        assertNull(f.controller.captureSessionKeyTicket())
        f.controller.actions.unlock("synthetic".toCharArray())
        f.worker.runNext()
        assertNull(f.controller.captureSessionKeyTicket())
        val epoch = f.starts.single().first
        assertTrue(f.controller.borrowStartupKey(epoch) {})
        assertNull(f.controller.captureSessionKeyTicket())
        f.controller.nativeStartupResult(epoch, success = true, mismatch401 = false)
        val reads = f.store.reads
        val revisions = f.revisionReads
        val ticket = assertNotNull(f.controller.captureSessionKeyTicket())
        assertSame(ticket, f.controller.captureSessionKeyTicket())
        assertEquals(reads, f.store.reads, "Capturing a ticket performs no disk read.")
        assertEquals(revisions, f.revisionReads, "Capturing a ticket performs no root revision read.")
        assertFalse(ticket.toString().contains("42"))
        var borrowed: ByteArray? = null
        assertTrue(f.controller.borrowSessionKey(ticket) { bytes ->
            assertContentEquals(ByteArray(32) { 42 }, bytes)
            borrowed = bytes
        })
        assertTrue(borrowed!!.all { it == 0.toByte() })
        assertEquals(LocalLockPhase.UNLOCKED, f.state.phase)
    }

    @Test
    fun confirmedClosedRenewalAcceptsSelectionRevisionAndSubmitsSameKeyOncePerNewEpoch() {
        val f = Fixture()
        val old = f.unlockCold()
        f.revision++ // Test models an already committed selection; it does not model native Closed.
        val renewed = assertNotNull(f.controller.renewSessionKeyAfterClosed(old))
        assertNotSame(old, renewed)
        assertTrue(renewed.epoch > old.epoch)
        assertNull(f.controller.captureSessionKeyTicket(), "A pending replacement cannot mint another ticket.")
        assertFalse(f.controller.borrowSessionKey(old) { error("old epoch") })
        assertFalse(f.controller.borrowSessionKey(renewed) { error("create admission is required") })
        var checks = 0
        assertFalse(f.controller.grantSessionCreate(renewed, f.revision - 1) { checks++; true })
        assertEquals(0, checks, "A stale root revision cannot enter the engine checker.")
        assertFalse(f.controller.grantSessionCreate(renewed, f.revision) { checks++; false })
        assertFalse(f.controller.grantSessionCreate(renewed, f.revision) { checks++; throw AssertionError("synthetic owner") })
        assertTrue(f.controller.grantSessionCreate(renewed, f.revision) { checks++; true })
        assertFalse(f.controller.grantSessionCreate(renewed, f.revision) { error("grant is single-use") })
        assertEquals(3, checks)
        var bytes: ByteArray? = null
        assertTrue(f.controller.borrowSessionKey(renewed) {
            bytes = it
            assertContentEquals(ByteArray(32) { 42 }, it)
            val readsBeforeDuplicate = f.store.reads
            val revisionsBeforeDuplicate = f.revisionReads
            assertFalse(f.controller.borrowSessionKey(renewed) { error("reentrant duplicate dispatch") })
            assertEquals(readsBeforeDuplicate, f.store.reads, "A duplicate is rejected before record I/O.")
            assertEquals(revisionsBeforeDuplicate, f.revisionReads, "A duplicate is rejected before root I/O.")
        })
        assertTrue(bytes!!.all { it == 0.toByte() })
        assertFalse(f.controller.borrowSessionKey(renewed) { error("one submission only") })
        f.controller.nativeStartupResult(old.epoch, success = true, mismatch401 = false)
        assertNull(f.controller.captureSessionKeyTicket(), "An old native result cannot settle the pending epoch.")
        f.controller.nativeStartupResult(renewed.epoch, success = true, mismatch401 = false)
        assertSame(renewed, f.controller.captureSessionKeyTicket())
        assertTrue(f.controller.admitSessionPreparation(renewed, f.revision) { true }, "A settled session can later prepare a new switch.")
        assertFalse(f.controller.grantSessionCreate(renewed, f.revision) { error("settled is not pending") })
        assertEquals(1, f.starts.size, "A ticket does not create or rekey a native client by itself.")
    }

    @Test
    fun trueParameter401RevokesThePendingLeaseAndCannotFallBackToAnEmptyKey() {
        val f = Fixture()
        val old = f.unlockCold()
        f.revision++
        val pending = assertNotNull(f.controller.renewSessionKeyAfterClosed(old))
        val retainedKey = f.crypto.lastOpened!!
        assertTrue(f.controller.grantSessionCreate(pending, f.revision) { true })
        assertTrue(f.controller.borrowSessionKey(pending) { assertEquals(32, it.size) })
        f.controller.nativeStartupResult(pending.epoch, success = false, mismatch401 = true)
        assertEquals(LocalLockPhase.RECOVERY, f.state.phase)
        assertEquals(LocalLockIssue.DATABASE_MISMATCH, f.state.issue)
        assertEquals(LocalCryptoResult.KeyUnavailable, retainedKey.borrow { LocalCryptoResult.Success(Unit) })
        assertNull(f.controller.captureSessionKeyTicket())
        assertFalse(f.controller.borrowSessionKey(pending) { error("no empty-key retry") })
        assertFalse(f.controller.borrowSessionKey(old) { error("old key cannot revive") })
        assertFalse(f.controller.suspendSessionStartup(pending), "A stale ticket cannot erase a real mismatch.")
        assertEquals(LocalLockIssue.DATABASE_MISMATCH, f.state.issue)
    }

    @Test
    fun changedRootIdentityRootOwnershipOrProtectionRecordCannotUseTheOldTicket() {
        for (change in listOf("identity", "owner", "record", "revision")) {
            val f = Fixture()
            val old = f.unlockCold()
            val key = f.crypto.lastOpened!!
            when (change) {
                "identity" -> f.identity[0] = 99
                "owner" -> f.currentRoot = false
                "record" -> f.store.present(byteArrayOf(9, 9), 1)
                "revision" -> f.revision++
            }
            assertFalse(f.controller.borrowSessionKey(old) { error("$change cannot authorize a borrowed key") })
            assertEquals(LocalLockPhase.RECOVERY, f.state.phase, change)
            assertEquals(LocalCryptoResult.KeyUnavailable, key.borrow { LocalCryptoResult.Success(Unit) })
            assertNull(f.controller.captureSessionKeyTicket())
        }
    }

    @Test
    fun lockingRevokesKeyAndOnlyTheLastRetiredTicketCanSuspendBeforeANewUnlock() {
        val f = Fixture()
        val old = f.unlockCold()
        val key = f.crypto.lastOpened!!
        f.controller.actions.lockNow()
        assertEquals(LocalLockPhase.HOT_LOCKED, f.state.phase)
        assertNull(f.controller.captureSessionKeyTicket())
        assertFalse(f.controller.borrowSessionKey(old) { error("locked") })
        assertEquals(LocalCryptoResult.KeyUnavailable, key.borrow { LocalCryptoResult.Success(Unit) })
        assertTrue(f.controller.suspendSessionStartup(old)) // Synthetic caller models no native client.
        assertEquals(LocalLockPhase.COLD_LOCKED, f.state.phase)
        f.controller.actions.unlock("synthetic".toCharArray())
        f.worker.runNext()
        val epoch = f.starts.last().first
        assertTrue(f.controller.borrowStartupKey(epoch) {})
        f.controller.nativeStartupResult(epoch, success = true, mismatch401 = false)
        val fresh = assertNotNull(f.controller.captureSessionKeyTicket())
        assertNotSame(old, fresh)
        assertFalse(f.controller.suspendSessionStartup(old), "A retired ticket cannot relock a replacement owner.")
        assertEquals(LocalLockPhase.UNLOCKED, f.state.phase)
    }

    @Test
    fun removingPasswordReplacesTicketButRetainsTheSameNonemptyDatabaseKey() {
        val f = Fixture()
        val old = f.unlockCold()
        f.controller.actions.verifyManagement("synthetic".toCharArray())
        f.worker.runNext()
        f.controller.actions.remove()
        f.worker.runNext()
        assertEquals(LocalLockPhase.UNPROTECTED, f.state.phase)
        val replacement = assertNotNull(f.controller.captureSessionKeyTicket())
        assertNotSame(old, replacement)
        assertFalse(f.controller.borrowSessionKey(old) { error("old record owner") })
        assertTrue(f.controller.borrowSessionKey(replacement) { assertContentEquals(ByteArray(32) { 42 }, it) })
        assertEquals(1, f.starts.size)
    }

    @Test
    fun openWrapStartupAndRenewalUseTheSameNonemptyKeyWithoutPasswordProtection() {
        val f = Fixture()
        val key = LocalDatabaseKey.copyOf(ByteArray(32) { 42 })
        val open = try {
            (LocalOpenWrap.encode(key, f.identity, 1) as LocalCryptoResult.Success<ByteArray>).value
        } finally { key.close() }
        f.store.present(open, 1)
        open.fill(0)
        f.controller.initialize()
        val epoch = f.starts.single().first
        assertTrue(f.starts.single().second)
        assertTrue(f.controller.borrowStartupKey(epoch) { assertContentEquals(ByteArray(32) { 42 }, it) })
        f.controller.nativeStartupResult(epoch, success = true, mismatch401 = false)
        assertEquals(LocalLockPhase.UNPROTECTED, f.state.phase)
        val old = assertNotNull(f.controller.captureSessionKeyTicket())
        f.revision++
        val renewed = assertNotNull(f.controller.renewSessionKeyAfterClosed(old))
        assertTrue(f.controller.grantSessionCreate(renewed, f.revision) { true })
        assertTrue(f.controller.borrowSessionKey(renewed) { assertContentEquals(ByteArray(32) { 42 }, it) })
        f.controller.nativeStartupResult(renewed.epoch, success = true, mismatch401 = false)
        assertSame(renewed, f.controller.captureSessionKeyTicket())
    }

    @Test
    fun stopRevokesTheTicketAndClosesTheOwnedKeyWithoutRevivingTheSession() {
        val f = Fixture()
        val ticket = f.unlockCold()
        val key = f.crypto.lastOpened!!
        var stopped = false
        f.controller.stop { stopped = true }
        assertTrue(stopped)
        assertTrue(f.controller.currentWitness().blocked)
        assertEquals(LocalCryptoResult.KeyUnavailable, key.borrow { LocalCryptoResult.Success(Unit) })
        assertNull(f.controller.captureSessionKeyTicket())
        assertFalse(f.controller.borrowSessionKey(ticket) { error("stopped") })
        assertNull(f.controller.renewSessionKeyAfterClosed(ticket))
        assertFalse(f.controller.suspendSessionStartup(ticket))
    }

    @Test
    fun createGrantIsSingleUseAndLockRevokesPendingBorrowWhetherLockWinsBeforeOrAfterGrant() {
        for (grantFirst in listOf(false, true)) {
            val f = Fixture()
            val old = f.unlockCold()
            val key = f.crypto.lastOpened!!
            f.revision++
            val pending = assertNotNull(f.controller.renewSessionKeyAfterClosed(old))
            if (grantFirst) assertTrue(f.controller.grantSessionCreate(pending, f.revision) { true })
            f.controller.actions.lockNow()
            assertTrue(f.controller.currentWitness().blocked)
            assertFalse(f.controller.grantSessionCreate(pending, f.revision) { error("revoked") })
            assertFalse(f.controller.borrowSessionKey(pending) { error("no dispatch after lock") })
            assertEquals(LocalCryptoResult.KeyUnavailable, key.borrow { LocalCryptoResult.Success(Unit) })
        }
    }

    @Test
    fun preparationAdmissionRechecksOwnerAndRootBeforeEachIoAndNeverHoldsASecret() {
        val f = Fixture()
        val old = f.unlockCold()
        var checks = 0
        assertFalse(f.controller.admitSessionPreparation(old, f.revision + 1) { checks++; true })
        assertEquals(0, checks)
        assertFalse(f.controller.admitSessionPreparation(old, f.revision) { checks++; false })
        assertFalse(f.controller.admitSessionPreparation(old, f.revision) { checks++; throw AssertionError("owner changed") })
        assertTrue(f.controller.admitSessionPreparation(old, f.revision) { checks++; true })
        assertTrue(f.controller.admitSessionPreparation(old, f.revision) { checks++; true })
        assertSame(old, f.controller.captureSessionKeyTicket())
        f.revision++
        val pending = assertNotNull(f.controller.renewSessionKeyAfterClosed(old))
        assertTrue(f.controller.admitSessionPreparation(pending, f.revision) { true })
        assertTrue(f.controller.grantSessionCreate(pending, f.revision) { true })
        assertFalse(f.controller.admitSessionPreparation(pending, f.revision) { error("already granted") })
        f.controller.actions.lockNow()
        assertFalse(f.controller.admitSessionPreparation(pending, f.revision) { error("revoked") })
        assertEquals(4, checks)
    }

    @Test
    fun throwingDuringSynchronousDispatchClearsBorrowedBytesAndRevokesTheLease() {
        val f = Fixture()
        val ticket = f.unlockCold()
        val key = f.crypto.lastOpened!!
        var borrowed: ByteArray? = null
        assertFalse(f.controller.borrowSessionKey(ticket) { borrowed = it; throw AssertionError("synthetic dispatch failure") })
        assertTrue(borrowed!!.all { it == 0.toByte() })
        assertEquals(LocalLockPhase.RECOVERY, f.state.phase)
        assertTrue(f.controller.currentWitness().blocked)
        assertEquals(LocalCryptoResult.KeyUnavailable, key.borrow { LocalCryptoResult.Success(Unit) })
        assertNull(f.controller.captureSessionKeyTicket())
    }

    @Test
    fun nativeBorrowAndLockHaveOneAdmissionOrderAndNeverLeaveTheKeyOpen() {
        val f = Fixture()
        val ticket = f.unlockCold()
        val key = f.crypto.lastOpened!!
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val lockStarted = CountDownLatch(1)
        val locked = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        var borrowed: ByteArray? = null
        val sender = Thread({
            try {
                assertTrue(f.controller.borrowSessionKey(ticket) {
                    borrowed = it
                    entered.countDown()
                    check(release.await(3, TimeUnit.SECONDS))
                })
            } catch (error: Throwable) { failure.compareAndSet(null, error) }
        }, "synthetic-borrow-admission").apply { isDaemon = true; start() }
        var locker: Thread? = null
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            locker = Thread({
                lockStarted.countDown()
                try { f.controller.actions.lockNow() } catch (error: Throwable) { failure.compareAndSet(null, error) }
                finally { locked.countDown() }
            }, "synthetic-lock-admission").apply { isDaemon = true; start() }
            assertTrue(lockStarted.await(2, TimeUnit.SECONDS))
            assertFalse(locked.await(100, TimeUnit.MILLISECONDS), "A dispatch already admitted must finish before lock wins.")
        } finally {
            release.countDown()
            sender.join(2_000)
            locker?.join(2_000)
        }
        assertEquals(null, failure.get())
        assertEquals(0L, locked.count)
        assertTrue(borrowed!!.all { it == 0.toByte() })
        assertEquals(LocalCryptoResult.KeyUnavailable, key.borrow { LocalCryptoResult.Success(Unit) })
        assertTrue(f.controller.currentWitness().blocked)
    }

    @Test
    fun lockReturnsWhileTheRootRevisionCheckIsBlockedAndPreventsNativeDispatch() {
        val f = Fixture()
        val ticket = f.unlockCold()
        val key = f.crypto.lastOpened!!
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val locked = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        var dispatched = false
        var accepted: Boolean? = null
        f.revisionBarrier = entered to release
        val borrower = Thread({
            try { accepted = f.controller.borrowSessionKey(ticket) { dispatched = true } }
            catch (error: Throwable) { failure.compareAndSet(null, error) }
        }, "synthetic-qr-root-check").apply { isDaemon = true; start() }
        var locker: Thread? = null
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            locker = Thread({
                try { f.controller.actions.lockNow() } catch (error: Throwable) { failure.compareAndSet(null, error) }
                finally { locked.countDown() }
            }, "synthetic-qr-lock").apply { isDaemon = true; start() }
            assertTrue(locked.await(2, TimeUnit.SECONDS), "Lock cannot wait for a root I/O check.")
            assertTrue(f.controller.currentWitness().blocked)
        } finally {
            release.countDown()
            borrower.join(2_000)
            locker?.join(2_000)
            f.revisionBarrier = null
        }
        assertEquals(null, failure.get())
        assertEquals(false, accepted)
        assertFalse(dispatched)
        assertEquals(LocalCryptoResult.KeyUnavailable, key.borrow { LocalCryptoResult.Success(Unit) })
    }

    private class Fixture {
        val identity = ByteArray(32) { (it + 1).toByte() }
        var currentRoot = true
        var revision = 1L
        var revisionReads = 0
        @Volatile var revisionBarrier: Pair<CountDownLatch, CountDownLatch>? = null
        val worker = FakeWorker()
        val store = FakeStore(identity)
        val crypto = FakeCrypto()
        val starts = mutableListOf<Pair<Long, Boolean>>()
        private val root = LocalProtectionRoot(Path.of("synthetic-qr-root-no-files"), { identity.copyOf() },
            { currentRoot }, { false }, revision = {
                revisionReads++
                revisionBarrier?.let { (entered, release) ->
                    entered.countDown()
                    check(release.await(3, TimeUnit.SECONDS))
                }
                revision
            }, durableCommitAvailable = { true })
        val controller = LocalLockCoordinator(root, worker, { epoch, protected -> starts += epoch to protected },
            persistence = store, crypto = crypto, preferences = object : LocalLockPreferences {
                override fun read(): LocalLockPreferenceRead = LocalLockPreferenceRead.Value(3_600)
                override fun write(seconds: Long) = true
            })
        val state get() = controller.state.value

        fun unlockCold(): LocalSessionKeyTicket {
            controller.initialize()
            controller.actions.unlock("synthetic".toCharArray())
            worker.runNext()
            val epoch = starts.single().first
            assertTrue(controller.borrowStartupKey(epoch) { assertEquals(32, it.size) })
            controller.nativeStartupResult(epoch, success = true, mismatch401 = false)
            return assertNotNull(controller.captureSessionKeyTicket())
        }
    }

    private class FakeWorker : Executor {
        val pending = mutableListOf<Runnable>()
        override fun execute(command: Runnable) { pending += command }
        fun runNext() { pending.removeAt(0).run() }
    }

    private class FakeStore(private val identity: ByteArray) : LocalProtectionPersistence {
        private var bytes = byteArrayOf(1, 2, 3)
        private var generation = 1L
        var reads = 0
        fun present(value: ByteArray, generation: Long) { bytes = value.copyOf(); this.generation = generation }
        override fun read(): LocalRecordRead {
            reads++
            if (bytes.size == LocalOpenWrap.RECORD_BYTES) return LocalOpenWrap.inspect(bytes, identity)
            return LocalRecordRead.Present(LocalStoredEnvelope(bytes.copyOf(), generation,
                MessageDigest.getInstance("SHA-256").digest(bytes)))
        }
        override fun commit(candidate: ByteArray, expected: LocalStoredEnvelope?): LocalCommitResult {
            present(candidate, (expected?.generation ?: 0) + 1)
            return LocalCommitResult.COMMITTED
        }
        override fun finalizeCommit(candidate: ByteArray, expected: LocalStoredEnvelope?) = LocalCommitResult.COMMITTED
    }

    private class FakeCrypto : LocalLockCryptoOperations {
        var lastOpened: LocalDatabaseKey? = null
        override fun create(password: CharArray, rootIdentity: ByteArray, generation: Long): LocalCryptoResult<WrappedDatabaseKey> =
            LocalCryptoResult.Success(WrappedDatabaseKey(byteArrayOf(7, 8, 9), LocalDatabaseKey.copyOf(ByteArray(32) { 42 })))
        override fun open(password: CharArray, record: ByteArray, rootIdentity: ByteArray, generation: Long): LocalCryptoResult<LocalDatabaseKey> =
            LocalCryptoResult.Success(LocalDatabaseKey.copyOf(ByteArray(32) { 42 }).also { lastOpened = it })
        override fun rewrap(password: CharArray, key: LocalDatabaseKey, rootIdentity: ByteArray, generation: Long): LocalCryptoResult<ByteArray> =
            LocalCryptoResult.Success(byteArrayOf(4, 5, 6))
    }
}
