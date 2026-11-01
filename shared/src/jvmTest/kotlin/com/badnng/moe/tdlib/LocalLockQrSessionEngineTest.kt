package com.badnng.moe.tdlib

import com.badnng.moe.chat.ChatEvent
import com.badnng.moe.media.NativeSaveTargetRegistry
import com.badnng.moe.media.SaveTargetIssue
import com.badnng.moe.security.*
import com.badnng.moe.settings.LocalLockIssue
import com.badnng.moe.settings.LocalLockPhase
import com.badnng.moe.status.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Only a temporary synthetic root and fake native callbacks; no account or DLL. */
class LocalLockQrSessionEngineTest {
    @Test
    fun switchingWaitsForClosedAndSubmitsTheSameNonemptyKeyBeforeARealParameter401() = runBlocking {
        val f = Fixture()
        try {
            f.openChallenge()
            val old = f.transport.sessions.single()
            val token = f.engine.state.value.loginToken
            val root = f.dataRoot()
            val selection = AuthenticationDatabaseSelectionStore(root)
            assertTrue(f.engine.cancelQrLogin(token))
            withTimeout(5_000) { while (old.closeCalls.get() == 0) delay(5) }
            assertEquals(1, f.transport.sessions.size)
            assertEquals(AuthenticationDatabaseChoice(), selection.read())
            assertFailsWith<SetupFailure> { DataDirectoryLease.acquire(root).close() }

            old.authorization(AuthorizationPhase.CLOSED) // Explicit fake Closed; no actual native claim.
            withTimeout(5_000) { while (f.transport.sessions.size != 2) delay(5) }
            val next = f.transport.sessions.last()
            assertTrue(f.engine.state.value.loginInitialPhoneMode)
            val selected = selection.directory(selection.read())
            assertTrue(selected.startsWith(root.resolve("authentication-attempts")))
            assertTrue(Files.isDirectory(root.resolve("database")))
            assertFailsWith<SetupFailure> { DataDirectoryLease.acquire(root).close() }
            next.authorization(AuthorizationPhase.WAITING_PARAMETERS)
            withTimeout(5_000) { while (next.parameterCallback == null) delay(5) }
            val firstKey = assertNotNull(old.borrowedCopy)
            val secondKey = assertNotNull(next.borrowedCopy)
            assertContentEquals(ByteArray(32) { 42 }, firstKey)
            assertContentEquals(firstKey, secondKey)
            assertEquals(1, next.parameterCalls.get())
            next.parameterCallback!!(TdParametersResult(code = 401, parametersRejected401 = true))
            withTimeout(5_000) {
                f.engine.localLockState.first { it.phase == LocalLockPhase.RECOVERY && it.issue == LocalLockIssue.DATABASE_MISMATCH }
            }
            assertEquals(1, next.parameterCalls.get(), "A parameter 401 cannot dispatch an empty-key retry.")
            assertFalse(f.engine.cancelQrLogin(token))
        } finally {
            f.dispose()
        }
    }

    @Test
    fun a401NotAttributedToTheDatabaseParameterDoesNotClaimAKeyMismatchOrRetry() = runBlocking {
        val f = Fixture()
        try {
            f.openChallenge()
            val old = f.transport.sessions.single()
            assertTrue(f.engine.cancelQrLogin(f.engine.state.value.loginToken))
            withTimeout(5_000) { while (old.closeCalls.get() == 0) delay(5) }
            old.authorization(AuthorizationPhase.CLOSED)
            withTimeout(5_000) { while (f.transport.sessions.size != 2) delay(5) }
            val next = f.transport.sessions.last()
            next.authorization(AuthorizationPhase.WAITING_PARAMETERS)
            withTimeout(5_000) { while (next.parameterCallback == null) delay(5) }
            next.parameterCallback!!(TdParametersResult(code = 401, parametersRejected401 = false))
            withTimeout(5_000) { f.engine.localLockState.first { it.phase == LocalLockPhase.RECOVERY } }
            assertEquals(LocalLockIssue.IO_FAILURE, f.engine.localLockState.value.issue)
            assertEquals(1, next.parameterCalls.get())
            assertEquals(2, f.transport.sessions.size)
        } finally {
            f.dispose()
        }
    }

    @Test
    fun lockWhileSelectionWriteIsBlockedPreventsTheReplacementClientAndPreservesCommittedFiles() = runBlocking {
        val f = Fixture()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        f.engine.qrSelectionAdvance = { store, expected ->
            entered.countDown()
            check(release.await(7, TimeUnit.SECONDS))
            store.beginPhoneAttempt(expected)
        }
        try {
            f.openChallenge()
            val root = f.dataRoot()
            val old = f.transport.sessions.single()
            assertTrue(f.engine.cancelQrLogin(f.engine.state.value.loginToken))
            withTimeout(5_000) { while (old.closeCalls.get() == 0) delay(5) }
            old.authorization(AuthorizationPhase.CLOSED)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            f.engine.localLockActions.lockNow()
            assertTrue(f.engine.localLockState.value.rootBlocked)
            assertFailsWith<SetupFailure> { DataDirectoryLease.acquire(root).close() }
            release.countDown()
            withTimeout(5_000) { f.engine.localLockState.first { it.phase == LocalLockPhase.COLD_LOCKED } }
            assertEquals(1, f.transport.sessions.size)
            assertTrue(Files.isDirectory(root.resolve("database")))
            val choice = AuthenticationDatabaseSelectionStore(root).read()
            assertTrue(AuthenticationDatabaseSelectionStore(root).directory(choice).startsWith(root))
        } finally {
            release.countDown()
            f.dispose()
        }
    }

    @Test
    fun stopDuringSelectionIoKeepsTheRootLeaseUntilWorkReturnsAndNeverStartsAnotherClient() = runBlocking {
        val f = Fixture()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        f.engine.qrSelectionAdvance = { store, expected ->
            entered.countDown()
            check(release.await(7, TimeUnit.SECONDS))
            store.beginPhoneAttempt(expected)
        }
        try {
            f.openChallenge()
            val root = f.dataRoot()
            val old = f.transport.sessions.single()
            assertTrue(f.engine.cancelQrLogin(f.engine.state.value.loginToken))
            withTimeout(5_000) { while (old.closeCalls.get() == 0) delay(5) }
            old.authorization(AuthorizationPhase.CLOSED)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val stopped = async(start = CoroutineStart.UNDISPATCHED) { f.engine.stop() }
            assertFalse(stopped.isCompleted)
            assertFailsWith<SetupFailure> { DataDirectoryLease.acquire(root).close() }
            release.countDown()
            assertTrue(withTimeout(10_000) { stopped.await() })
            assertEquals(1, f.transport.sessions.size)
            assertTrue(Files.isDirectory(root.resolve("database")))
        } finally {
            release.countDown()
            f.dispose()
        }
    }

    @Test
    fun admittedCreateCanReturnAfterLockButOnlyClosesUntilRealClosedAndNeverSubmitsParameters() = runBlocking {
        val f = Fixture()
        val enteredCreate = CountDownLatch(1)
        val releaseCreate = CountDownLatch(1)
        val lockReturned = CountDownLatch(1)
        try {
            f.openChallenge()
            val root = f.dataRoot()
            val old = f.transport.sessions.single()
            f.transport.nextCreateBarrier = enteredCreate to releaseCreate
            assertTrue(f.engine.cancelQrLogin(f.engine.state.value.loginToken))
            withTimeout(5_000) { while (old.closeCalls.get() == 0) delay(5) }
            old.authorization(AuthorizationPhase.CLOSED)
            assertTrue(enteredCreate.await(5, TimeUnit.SECONDS), "The fake create must already own admission.")
            val locker = Thread({
                try { f.engine.localLockActions.lockNow() } finally { lockReturned.countDown() }
            }, "synthetic-qr-create-lock").apply { isDaemon = true; start() }
            try {
                assertTrue(lockReturned.await(2, TimeUnit.SECONDS), "Lock must not wait for an admitted native create.")
                assertTrue(f.engine.localLockState.value.rootBlocked)
            } finally {
                releaseCreate.countDown()
                locker.join(2_000)
            }
            withTimeout(5_000) { while (f.transport.sessions.size != 2) delay(5) }
            val next = f.transport.sessions.last()
            withTimeout(5_000) { while (next.closeCalls.get() == 0) delay(5) }
            assertFailsWith<SetupFailure> { DataDirectoryLease.acquire(root).close() }
            next.authorization(AuthorizationPhase.WAITING_PARAMETERS)
            assertTrue(f.engine.localLockState.value.rootBlocked)
            next.authorization(AuthorizationPhase.CLOSED)
            withTimeout(5_000) { f.engine.localLockState.first { it.phase == LocalLockPhase.COLD_LOCKED } }
            assertEquals(0, next.parameterCalls.get(), "A late client must not receive old key or parameters.")
            assertEquals(0, next.loginCalls.get())
            assertEquals(0, next.proxyCalls.get())
        } finally {
            releaseCreate.countDown()
            f.dispose()
        }
    }

    @Test
    fun stopDuringAdmittedCreateWaitsForClosedAndNeverSubmitsParameters() = runBlocking {
        val f = Fixture()
        val enteredCreate = CountDownLatch(1)
        val releaseCreate = CountDownLatch(1)
        try {
            f.openChallenge()
            val root = f.dataRoot()
            val old = f.transport.sessions.single()
            f.transport.nextCreateBarrier = enteredCreate to releaseCreate
            assertTrue(f.engine.cancelQrLogin(f.engine.state.value.loginToken))
            withTimeout(5_000) { while (old.closeCalls.get() == 0) delay(5) }
            old.authorization(AuthorizationPhase.CLOSED)
            assertTrue(enteredCreate.await(5, TimeUnit.SECONDS))
            val stopped = async(start = CoroutineStart.UNDISPATCHED) { f.engine.stop() }
            assertFalse(stopped.isCompleted, "Stop cannot report completion while admitted create is still in flight.")
            releaseCreate.countDown()
            withTimeout(5_000) { while (f.transport.sessions.size != 2) delay(5) }
            val next = f.transport.sessions.last()
            withTimeout(5_000) { while (next.closeCalls.get() == 0) delay(5) }
            assertFalse(stopped.isCompleted)
            assertFailsWith<SetupFailure> { DataDirectoryLease.acquire(root).close() }
            next.authorization(AuthorizationPhase.WAITING_PARAMETERS)
            next.authorization(AuthorizationPhase.CLOSED)
            assertTrue(withTimeout(10_000) { stopped.await() })
            assertEquals(0, next.parameterCalls.get())
            assertEquals(0, next.loginCalls.get())
            assertEquals(0, next.proxyCalls.get())
        } finally {
            releaseCreate.countDown()
            f.dispose()
        }
    }

    @Test
    fun partialCreateFailureKeepsUnknownOwnerAndRootUntilAnExplicitClosedCallback() = runBlocking {
        val f = Fixture(nativeRoots = true)
        try {
            f.openChallenge()
            val root = f.dataRoot()
            val old = f.transport.sessions.single()
            f.transport.throwAfterNextCreateBegins = true
            assertTrue(f.engine.cancelQrLogin(f.engine.state.value.loginToken))
            withTimeout(5_000) { while (old.closeCalls.get() == 0) delay(5) }
            old.authorization(AuthorizationPhase.CLOSED)
            withTimeout(5_000) { f.engine.state.first { it.status.engine == EnginePhase.FAILED && it.clientActive } }
            assertEquals(2, f.transport.sessions.size)
            val uncertain = f.transport.sessions.last()
            assertEquals(0, uncertain.parameterCalls.get())
            assertEquals(SaveTargetIssue.UNKNOWN_NATIVE_ROOTS, f.registry.capture().check(root.resolve("outside.synthetic")))
            assertFailsWith<SetupFailure> { DataDirectoryLease.acquire(root).close() }
            val stopped = async(start = CoroutineStart.UNDISPATCHED) { f.engine.stop() }
            assertFalse(stopped.isCompleted, "A create exception is not evidence of native Closed.")
            uncertain.authorization(AuthorizationPhase.CLOSED)
            assertTrue(withTimeout(10_000) { stopped.await() })
            assertEquals(0, uncertain.parameterCalls.get())
            assertNull(f.registry.capture().check(root.resolve("outside.synthetic")), "Only the new post-Closed snapshot can observe retirement.")
        } finally {
            f.dispose()
        }
    }

    @Test
    fun uncertainSelectionCommitCannotBeReportedAsSuccessfulOrRolledBackByGuessing() = runBlocking {
        val f = Fixture()
        f.engine.qrSelectionAdvance = { store, expected ->
            store.beginPhoneAttempt(expected)
            throw IllegalStateException("synthetic failure after selection commit")
        }
        try {
            f.openChallenge()
            val root = f.dataRoot()
            val old = f.transport.sessions.single()
            assertTrue(f.engine.cancelQrLogin(f.engine.state.value.loginToken))
            withTimeout(5_000) { while (old.closeCalls.get() == 0) delay(5) }
            old.authorization(AuthorizationPhase.CLOSED)
            withTimeout(5_000) { f.engine.state.first { it.status.engine == EnginePhase.FAILED } }
            assertEquals(1, f.transport.sessions.size)
            val selection = AuthenticationDatabaseSelectionStore(root)
            val choice = selection.read()
            assertTrue(selection.directory(choice).startsWith(root.resolve("authentication-attempts")))
            assertTrue(Files.isDirectory(root.resolve("database")))
            assertFalse(f.engine.state.value.clientActive)
        } finally {
            f.dispose()
        }
    }

    private class Fixture(nativeRoots: Boolean = false) {
        private val root = Files.createTempDirectory("nnggram-qr-session-test-")
        private val configPath = root.resolve("tdlib.properties").also {
            Files.writeString(it, "api_id=12345\napi_hash=${"a".repeat(32)}\n")
        }
        val transport = SyntheticTransport(nativeRoots)
        private val store = SyntheticPersistence()
        val registry = NativeSaveTargetRegistry()
        val engine = TdEngine(ConfigurationStore(configPath, root.resolve("data")), transport, Dispatchers.Default,
            saveTargetRegistry = registry)

        init {
            engine.localLockCoordinatorFactory = { lease, worker, start, changed ->
                LocalLockCoordinator(lease, worker, start, onWitnessChanged = changed, persistence = store,
                    crypto = SyntheticCrypto(), preferences = SyntheticPreferences())
            }
            engine.localLockIdleFactory = { due, availability ->
                LocalLockIdleController(object : LocalLockIdleSource {
                    override fun tickMillis() = 1_000L
                    override fun lastInputDword() = 1_000L
                }, LocalLockDeadlineScheduler { _, _ -> LocalLockDeadlineHandle {} }, due, availability)
            }
        }

        suspend fun openChallenge() {
            assertTrue(engine.start())
            withTimeout(5_000) { engine.localLockState.first { it.phase == LocalLockPhase.COLD_LOCKED } }
            engine.localLockActions.unlock("synthetic".toCharArray())
            withTimeout(5_000) { while (transport.sessions.isEmpty()) delay(5) }
            val old = transport.sessions.single()
            old.authorization(AuthorizationPhase.WAITING_PARAMETERS)
            withTimeout(5_000) { while (old.parameterCallback == null) delay(5) }
            old.parameterCallback!!(TdParametersResult(code = null))
            withTimeout(5_000) { engine.localLockState.first { it.phase == LocalLockPhase.UNLOCKED } }
            old.authorization(AuthorizationDetails(AuthorizationPhase.OTHER_STEP, otherStep = LoginOtherStep.DEVICE_CONFIRMATION,
                presentation = LoginPresentation(qrLink = "tg://login?token=synthetic")))
            withTimeout(5_000) { engine.state.first { it.loginQrPhoneSwitchAvailable && it.canSubmitLogin(LoginRequestKind.RESET_PHONE) } }
        }

        fun dataRoot(): Path = Path.of(checkNotNull(engine.state.value.dataPath))
        suspend fun dispose() {
            transport.sessions.forEach { it.autoClose = true }
            transport.sessions.lastOrNull()?.authorization(AuthorizationPhase.CLOSED)
            withTimeout(10_000) { engine.close() }
        }
    }

    private class SyntheticPersistence : LocalProtectionPersistence {
        private val record = LocalStoredEnvelope(byteArrayOf(7, 8), 1, ByteArray(32) { 1 })
        override fun read(): LocalRecordRead = LocalRecordRead.Present(record)
        override fun commit(candidate: ByteArray, expected: LocalStoredEnvelope?): LocalCommitResult = error("No test writes a protection record")
        override fun finalizeCommit(candidate: ByteArray, expected: LocalStoredEnvelope?): LocalCommitResult = error("No test finalizes a protection record")
    }

    private class SyntheticCrypto : LocalLockCryptoOperations {
        override fun create(password: CharArray, rootIdentity: ByteArray, generation: Long): LocalCryptoResult<WrappedDatabaseKey> = error("Not used")
        override fun open(password: CharArray, record: ByteArray, rootIdentity: ByteArray, generation: Long): LocalCryptoResult<LocalDatabaseKey> =
            LocalCryptoResult.Success(LocalDatabaseKey.copyOf(ByteArray(32) { 42 }))
        override fun rewrap(password: CharArray, key: LocalDatabaseKey, rootIdentity: ByteArray, generation: Long): LocalCryptoResult<ByteArray> = error("Not used")
    }

    private class SyntheticPreferences : LocalLockPreferences {
        override fun read(): LocalLockPreferenceRead = LocalLockPreferenceRead.Value(3_600)
        override fun write(seconds: Long) = error("No test writes preferences")
    }

    private class SyntheticTransport(override val hasNativeFileScanRoots: Boolean) : TdTransport {
        val sessions = CopyOnWriteArrayList<SyntheticSession>()
        @Volatile var nextCreateBarrier: Pair<CountDownLatch, CountDownLatch>? = null
        @Volatile var throwAfterNextCreateBegins = false
        override fun load() = NativeInfo("synthetic", "synthetic", "synthetic")
        override fun create(onAuthorization: (AuthorizationDetails) -> Unit, onConnection: (ConnectionPhase) -> Unit,
            onError: () -> Unit, onChatEvent: (ChatEvent) -> Unit): TdSession {
            nextCreateBarrier?.let { (entered, release) ->
                nextCreateBarrier = null
                entered.countDown()
                check(release.await(7, TimeUnit.SECONDS))
            }
            val session = SyntheticSession(onAuthorization).also { sessions += it }
            if (throwAfterNextCreateBegins) {
                throwAfterNextCreateBegins = false
                throw IllegalStateException("synthetic uncertain create")
            }
            return session
        }
    }

    private class SyntheticSession(private val onAuthorization: (AuthorizationDetails) -> Unit) : TdSession {
        val closeCalls = AtomicInteger()
        val parameterCalls = AtomicInteger()
        val loginCalls = AtomicInteger()
        val proxyCalls = AtomicInteger()
        @Volatile var parameterCallback: ((TdParametersResult) -> Unit)? = null
        @Volatile var borrowedCopy: ByteArray? = null
        @Volatile var autoClose = false
        fun authorization(phase: AuthorizationPhase) = authorization(AuthorizationDetails(phase))
        fun authorization(details: AuthorizationDetails) = onAuthorization(details)
        override fun setParameters(config: TdConfiguration, directories: DataDirectoryLease, result: (Int?) -> Unit) = error("Protected roots require a nonempty key")
        override fun setParameters(config: TdConfiguration, directories: DataDirectoryLease, key: ByteArray,
            result: (TdParametersResult) -> Unit) {
            parameterCalls.incrementAndGet()
            borrowedCopy = key.copyOf() // Synchronous synthetic copy; never retain the borrowed array.
            parameterCallback = result
        }
        override fun configureProxy(proxy: ProxyConfiguration?, result: (Int?) -> Unit) { proxyCalls.incrementAndGet(); result(null) }
        override fun submitLogin(kind: LoginRequestKind, value: String, result: (LoginResult) -> Unit) {
            loginCalls.incrementAndGet()
            error("No login submission")
        }
        override fun close() { closeCalls.incrementAndGet(); if (autoClose) authorization(AuthorizationPhase.CLOSED) }
    }
}
