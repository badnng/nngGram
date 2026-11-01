package com.badnng.moe.tdlib

import com.badnng.moe.chat.ChatEvent
import com.badnng.moe.security.LocalCryptoResult
import com.badnng.moe.security.LocalDatabaseKey
import com.badnng.moe.security.LocalLockCoordinator
import com.badnng.moe.security.LocalLockCryptoOperations
import com.badnng.moe.security.LocalLockDeadlineHandle
import com.badnng.moe.security.LocalLockDeadlineScheduler
import com.badnng.moe.security.LocalLockIdleController
import com.badnng.moe.security.LocalLockIdleSource
import com.badnng.moe.security.LocalLockPreferenceRead
import com.badnng.moe.security.LocalLockPreferences
import com.badnng.moe.security.LocalProtectionPersistence
import com.badnng.moe.security.LocalRecordRead
import com.badnng.moe.security.LocalStoredEnvelope
import com.badnng.moe.security.WrappedDatabaseKey
import com.badnng.moe.settings.LocalLockIssue
import com.badnng.moe.settings.LocalLockPhase
import com.badnng.moe.status.AuthorizationDetails
import com.badnng.moe.status.AuthorizationPhase
import com.badnng.moe.status.ConnectionPhase
import com.badnng.moe.status.LoginRequestKind
import com.badnng.moe.status.LoginResult
import com.badnng.moe.status.NativeInfo
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Synthetic directory, fake transport/crypto, no native DLL or account is opened. */
class TdLocalLockColdStartTest {
    @Test
    fun coldGatePrecedesDatabaseAndSelectionThen401NeverRetriesTheEmptyKey() = runBlocking {
        val root = Files.createTempDirectory("nnggram-lock-engine-test-")
        val data = root.resolve("data")
        val retained = data.resolve("authentication-attempts").resolve("synthetic-retained")
        Files.createDirectories(retained)
        val marker = retained.resolve("marker")
        Files.writeString(marker, "synthetic")
        val configPath = root.resolve("tdlib.properties")
        Files.writeString(configPath, "api_id=12345\napi_hash=${"a".repeat(32)}\n")
        val transport = SyntheticTransport()
        val store = SyntheticPersistence()
        val engine = TdEngine(ConfigurationStore(configPath, data), transport, Dispatchers.Default)
        engine.localLockCoordinatorFactory = { lease, worker, start, changed ->
            LocalLockCoordinator(lease, worker, start, onWitnessChanged = changed, persistence = store,
                crypto = SyntheticCrypto(), preferences = SyntheticPreferences())
        }
        engine.localLockIdleFactory = { due, availability ->
            LocalLockIdleController(SyntheticIdleSource(), SyntheticDeadlines(), due, availability)
        }
        try {
            assertTrue(engine.start())
            withTimeout(5_000) { engine.localLockState.first { it.phase == LocalLockPhase.COLD_LOCKED } }
            assertEquals(0, transport.creations.get())
            assertFalse(Files.exists(data.resolve("database")))
            assertFalse(Files.exists(data.resolve("files")))
            assertEquals("synthetic", Files.readString(marker))

            val password = "synthetic".toCharArray()
            engine.localLockActions.unlock(password)
            val session = withTimeout(5_000) { transport.created.await() }
            session.authorization(AuthorizationPhase.WAITING_PARAMETERS)
            withTimeout(5_000) { session.parametersReceived.await() }
            assertTrue(password.all { it == '\u0000' })
            assertEquals(1, session.parameterCalls)
            assertEquals(32, session.parameterLength)
            assertTrue(session.nonemptySyntheticKey)
            session.parameterCallback!!(TdParametersResult(code = 401, parametersRejected401 = true))
            withTimeout(5_000) {
                engine.localLockState.first { it.phase == LocalLockPhase.RECOVERY && it.issue == LocalLockIssue.DATABASE_MISMATCH }
            }
            assertEquals(1, session.parameterCalls, "A native 401 cannot dispatch a second request with an empty key.")
            assertEquals(1, transport.creations.get())
            assertEquals("synthetic", Files.readString(marker))
            assertEquals(0, store.commits)
        } finally {
            withTimeout(10_000) { engine.close() }
        }
    }

    @Test
    fun manualLockImmediatelyProjectsBlockedWitnessWhileCommitIsBlocked() = runBlocking {
        projectionWhileCommitBlocked(automatic = false)
    }

    @Test
    fun autoLockImmediatelyProjectsBlockedWitnessWhileCommitIsBlocked() = runBlocking {
        projectionWhileCommitBlocked(automatic = true)
    }

    private suspend fun projectionWhileCommitBlocked(automatic: Boolean) {
        val root = Files.createTempDirectory("nnggram-lock-engine-projection-")
        val configPath = root.resolve("tdlib.properties")
        Files.writeString(configPath, "api_id=12345\napi_hash=${"a".repeat(32)}\n")
        val transport = SyntheticTransport()
        val store = SyntheticPersistence()
        val source = SyntheticIdleSource()
        val timer = SyntheticDeadlines()
        val owner = AtomicReference<LocalLockCoordinator>()
        val engine = TdEngine(ConfigurationStore(configPath, root.resolve("data")), transport, Dispatchers.Default)
        engine.localLockCoordinatorFactory = { lease, worker, start, changed ->
            LocalLockCoordinator(lease, worker, start, onWitnessChanged = changed, persistence = store,
                crypto = SyntheticCrypto(), preferences = SyntheticPreferences()).also(owner::set)
        }
        engine.localLockIdleFactory = { due, availability -> LocalLockIdleController(source, timer, due, availability) }
        var lockThread: Thread? = null
        val lockReturned = CountDownLatch(1)
        try {
            assertTrue(engine.start())
            withTimeout(5_000) { engine.localLockState.first { it.phase == LocalLockPhase.COLD_LOCKED } }
            engine.localLockActions.unlock("synthetic".toCharArray())
            val session = withTimeout(5_000) { transport.created.await() }
            session.authorization(AuthorizationPhase.WAITING_PARAMETERS)
            withTimeout(5_000) { session.parametersReceived.await() }
            session.parameterCallback!!(TdParametersResult(code = null))
            withTimeout(5_000) { engine.localLockState.first { it.phase == LocalLockPhase.UNLOCKED } }
            session.authorization(AuthorizationPhase.READY)
            withTimeout(5_000) { engine.state.first { it.status.authorization == AuthorizationPhase.READY } }
            withTimeout(5_000) { while (!timer.hasActive()) delay(5) }

            engine.localLockActions.verifyManagement("synthetic".toCharArray())
            withTimeout(5_000) { engine.localLockState.first { it.managementAuthorized && it.canRemove } }
            val staged = CountDownLatch(1)
            val release = CountDownLatch(1)
            store.commitBarrier = staged to release
            engine.localLockActions.remove()
            assertTrue(staged.await(5, TimeUnit.SECONDS), "The synthetic commit must be blocked in persistence.")
            try {
                if (automatic) {
                    source.now = 3_601_000
                    timer.fireActive()
                } else {
                    lockThread = Thread({
                        try { engine.localLockActions.lockNow() } finally { lockReturned.countDown() }
                    }, "synthetic-engine-lock")
                        .apply { isDaemon = true; start() }
                }
                val shown = withTimeout(5_000) { engine.localLockState.first { it.phase == LocalLockPhase.HOT_LOCKED } }
                withTimeout(5_000) { engine.state.first { it.localLock.phase == LocalLockPhase.HOT_LOCKED } }
                assertTrue(owner.get().currentWitness().blocked)
                assertEquals(1L, release.count, "The underlying fake write has not completed when the lock is projected.")
                assertTrue(shown.rootBlocked)
                assertFalse(shown.canManage || shown.canChange || shown.canRemove || shown.canAutoLock || shown.canLockNow)
                assertEquals(owner.get().currentWitness().epoch, shown.epoch)
                if (!automatic) assertTrue(lockReturned.await(2, TimeUnit.SECONDS),
                    "The manual lock request must return while the synthetic write is still blocked.")
            } finally {
                release.countDown()
                lockThread?.join(3_000)
            }
            withTimeout(5_000) { engine.localLockState.first { it.phase == LocalLockPhase.RECOVERY } }
            assertEquals(0, store.finalizations)
            assertTrue(store.previousRetained)
        } finally {
            store.commitBarrier?.second?.countDown()
            withTimeout(10_000) { engine.close() }
        }
    }

    private class SyntheticPersistence : LocalProtectionPersistence {
        private val record = LocalStoredEnvelope(byteArrayOf(7, 8), 1, ByteArray(32) { 1 })
        @Volatile var commits = 0
        @Volatile var finalizations = 0
        @Volatile var previousRetained = false
        @Volatile var commitBarrier: Pair<CountDownLatch, CountDownLatch>? = null
        override fun read(): LocalRecordRead = if (previousRetained) LocalRecordRead.Failed(LocalLockIssue.IO_FAILURE)
            else LocalRecordRead.Present(record)
        override fun commit(candidate: ByteArray, expected: LocalStoredEnvelope?): com.badnng.moe.security.LocalCommitResult {
            commits++
            val barrier = commitBarrier ?: error("Cold unlock cannot write a record")
            barrier.first.countDown()
            check(barrier.second.await(7, TimeUnit.SECONDS)) { "Synthetic staged write timed out" }
            previousRetained = expected != null
            return com.badnng.moe.security.LocalCommitResult.COMMITTED
        }
        override fun finalizeCommit(candidate: ByteArray, expected: LocalStoredEnvelope?): com.badnng.moe.security.LocalCommitResult {
            finalizations++
            previousRetained = false
            return com.badnng.moe.security.LocalCommitResult.COMMITTED
        }
    }

    private class SyntheticCrypto : LocalLockCryptoOperations {
        override fun create(password: CharArray, rootIdentity: ByteArray, generation: Long): LocalCryptoResult<WrappedDatabaseKey> =
            error("Not used")
        override fun open(password: CharArray, record: ByteArray, rootIdentity: ByteArray, generation: Long): LocalCryptoResult<LocalDatabaseKey> =
            LocalCryptoResult.Success(LocalDatabaseKey.copyOf(ByteArray(32) { 42 }))
        override fun rewrap(password: CharArray, key: LocalDatabaseKey, rootIdentity: ByteArray, generation: Long): LocalCryptoResult<ByteArray> =
            error("Not used")
    }

    private class SyntheticPreferences : LocalLockPreferences {
        override fun read(): LocalLockPreferenceRead = LocalLockPreferenceRead.Value(3_600)
        override fun write(seconds: Long): Boolean = error("Cold unlock cannot write preferences")
    }

    private class SyntheticIdleSource : LocalLockIdleSource {
        @Volatile var now = 1_000L
        override fun tickMillis(): Long = now
        override fun lastInputDword(): Long = 1_000
    }

    private class SyntheticDeadlines : LocalLockDeadlineScheduler {
        private class Task(val callback: () -> Unit, val cancelled: AtomicBoolean = AtomicBoolean(false))
        private val tasks = CopyOnWriteArrayList<Task>()
        override fun schedule(delayMillis: Long, callback: () -> Unit): LocalLockDeadlineHandle {
            require(delayMillis > 0)
            val task = Task(callback)
            tasks += task
            return LocalLockDeadlineHandle { task.cancelled.set(true) }
        }
        fun hasActive(): Boolean = tasks.any { !it.cancelled.get() }
        fun fireActive() {
            val task = tasks.last { !it.cancelled.get() }
            task.callback()
        }
    }

    private class SyntheticTransport : TdTransport {
        val creations = AtomicInteger()
        val created = CompletableDeferred<SyntheticSession>()
        override fun load() = NativeInfo("synthetic", "synthetic", "synthetic")
        override fun create(onAuthorization: (AuthorizationDetails) -> Unit, onConnection: (ConnectionPhase) -> Unit,
            onError: () -> Unit, onChatEvent: (ChatEvent) -> Unit): TdSession {
            creations.incrementAndGet()
            return SyntheticSession(onAuthorization).also { created.complete(it) }
        }
    }

    private class SyntheticSession(private val onAuthorization: (AuthorizationDetails) -> Unit) : TdSession {
        val parametersReceived = CompletableDeferred<Unit>()
        @Volatile var parameterCalls = 0
        @Volatile var parameterLength = 0
        @Volatile var nonemptySyntheticKey = false
        @Volatile var parameterCallback: ((TdParametersResult) -> Unit)? = null
        fun authorization(phase: AuthorizationPhase) = onAuthorization(AuthorizationDetails(phase))
        override fun setParameters(config: TdConfiguration, directories: DataDirectoryLease, result: (Int?) -> Unit) =
            error("A protected start cannot call the legacy empty-key overload")
        override fun setParameters(config: TdConfiguration, directories: DataDirectoryLease, key: ByteArray,
            result: (TdParametersResult) -> Unit) {
            parameterCalls++
            parameterLength = key.size
            nonemptySyntheticKey = key.contentEquals(ByteArray(32) { 42 })
            parameterCallback = result // Only the callback is retained, never the borrowed key array.
            parametersReceived.complete(Unit)
        }
        override fun configureProxy(proxy: ProxyConfiguration?, result: (Int?) -> Unit) = result(null)
        override fun submitLogin(kind: LoginRequestKind, value: String, result: (LoginResult) -> Unit) =
            error("The fixture never submits login")
        override fun close() = authorization(AuthorizationPhase.CLOSED)
    }
}
