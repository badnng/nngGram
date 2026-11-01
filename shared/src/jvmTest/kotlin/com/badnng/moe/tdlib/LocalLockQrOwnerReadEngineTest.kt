package com.badnng.moe.tdlib

import com.badnng.moe.chat.ChatEvent
import com.badnng.moe.security.*
import com.badnng.moe.settings.LocalLockIssue
import com.badnng.moe.settings.LocalLockPhase
import com.badnng.moe.status.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.coroutines.CoroutineContext

/** Temporary synthetic root and controllable callbacks; no live user or Native DLL. */
class LocalLockQrOwnerReadEngineTest {
    @Test
    fun lockOrStopWhileRootCheckBlocksPreventsSelectionReadAndParameterDispatch() = runBlocking {
        for (stop in listOf(false, true)) {
            val f = Fixture()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val selectionReads = AtomicInteger()
            f.engine.qrStartupRootCurrent = { lease ->
                entered.countDown()
                check(release.await(7, TimeUnit.SECONDS))
                lease.isCurrent()
            }
            f.engine.qrStartupReadSelection = { store -> selectionReads.incrementAndGet(); store.read() }
            try {
                val next = f.openReplacement()
                next.authorization(AuthorizationPhase.WAITING_PARAMETERS)
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val stopped = if (stop) async(start = CoroutineStart.UNDISPATCHED) { f.engine.stop() } else null
                if (!stop) {
                    f.engine.localLockActions.lockNow()
                    assertTrue(f.owner.get().currentWitness().blocked)
                }
                release.countDown()
                withTimeout(5_000) { while (next.closeCalls.get() == 0) delay(5) }
                assertEquals(0, selectionReads.get())
                assertEquals(0, next.parameterCalls.get())
                assertEquals(0, next.loginCalls.get())
                assertFailsWith<SetupFailure> { DataDirectoryLease.acquire(f.dataRoot).close() }
                if (stopped != null) assertFalse(stopped.isCompleted, "Stop waits for the actual fake Closed callback.")
                next.authorization(AuthorizationPhase.CLOSED)
                if (stopped != null) assertTrue(withTimeout(10_000) { stopped.await() })
                else withTimeout(5_000) { f.engine.localLockState.first { it.phase == LocalLockPhase.COLD_LOCKED } }
                assertEquals(0, selectionReads.get())
                assertEquals(0, next.parameterCalls.get())
            } finally {
                release.countDown()
                f.dispose()
            }
        }
    }

    @Test
    fun blockedWitnessBeforeEngineNotificationStillClosesWithoutMislabelingWorkerFailureOrResult() = runBlocking {
        for (submitted in listOf(false, true)) {
            val f = Fixture()
            val rootEntered = CountDownLatch(1)
            val rootRelease = CountDownLatch(1)
            val witnessEntered = CountDownLatch(1)
            val witnessRelease = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            val selectionReads = AtomicInteger()
            if (!submitted) f.engine.qrStartupRootCurrent = { lease ->
                rootEntered.countDown()
                check(rootRelease.await(7, TimeUnit.SECONDS))
                lease.isCurrent()
            }
            f.engine.qrStartupReadSelection = { store -> selectionReads.incrementAndGet(); store.read() }
            var locker: Thread? = null
            try {
                val next = f.openReplacement()
                next.authorization(AuthorizationPhase.WAITING_PARAMETERS)
                if (submitted) withTimeout(5_000) { while (next.parameterCallback == null) delay(5) }
                else assertTrue(rootEntered.await(5, TimeUnit.SECONDS))
                f.witnessHook = { witness ->
                    if (witness.blocked && f.owner.get().state.value.phase == LocalLockPhase.HOT_LOCKED) {
                        witnessEntered.countDown()
                        check(witnessRelease.await(7, TimeUnit.SECONDS))
                    }
                }
                locker = Thread({
                    try { f.engine.localLockActions.lockNow() } catch (error: Throwable) { failure.compareAndSet(null, error) }
                }, "synthetic-delayed-lock-notice").apply { isDaemon = true; start() }
                assertTrue(witnessEntered.await(5, TimeUnit.SECONDS))
                assertTrue(f.owner.get().currentWitness().blocked)
                assertEquals(LocalLockPhase.HOT_LOCKED, f.owner.get().state.value.phase)
                if (submitted) next.parameterCallback!!(TdParametersResult(code = null)) else rootRelease.countDown()
                withTimeout(5_000) { while (next.closeCalls.get() == 0) delay(5) }
                assertEquals(if (submitted) 1 else 0, next.parameterCalls.get())
                if (!submitted) assertEquals(0, selectionReads.get())
                assertFalse(f.engine.state.value.status.engine == EnginePhase.FAILED,
                    "A blocked witness is a close request, not a synthetic initialization failure.")
                assertFailsWith<SetupFailure> { DataDirectoryLease.acquire(f.dataRoot).close() }
                witnessRelease.countDown()
                locker.join(3_000)
                assertEquals(null, failure.get())
                next.authorization(AuthorizationPhase.CLOSED)
                withTimeout(5_000) { f.engine.localLockState.first { it.phase == LocalLockPhase.COLD_LOCKED } }
                assertFalse(f.engine.localLockState.value.issue == LocalLockIssue.DATABASE_MISMATCH)
            } finally {
                rootRelease.countDown()
                witnessRelease.countDown()
                locker?.join(3_000)
                f.dispose()
            }
        }
    }

    @Test
    fun confirmedParameter401RemainsRecoveryWhenClosedArrivesBeforeItsWitnessNotification() = runBlocking {
        val f = Fixture()
        val witnessEntered = CountDownLatch(1)
        val witnessRelease = CountDownLatch(1)
        try {
            val next = f.openReplacement()
            next.authorization(AuthorizationPhase.WAITING_PARAMETERS)
            withTimeout(5_000) { while (next.parameterCallback == null) delay(5) }
            f.witnessHook = { witness ->
                if (witness.blocked && f.owner.get().state.value.phase == LocalLockPhase.RECOVERY) {
                    witnessEntered.countDown()
                    check(witnessRelease.await(7, TimeUnit.SECONDS))
                }
            }
            next.parameterCallback!!(TdParametersResult(code = 401, parametersRejected401 = true))
            assertTrue(witnessEntered.await(5, TimeUnit.SECONDS))
            assertEquals(LocalLockPhase.RECOVERY, f.owner.get().state.value.phase)
            assertEquals(LocalLockIssue.DATABASE_MISMATCH, f.owner.get().state.value.issue)
            next.authorization(AuthorizationPhase.CLOSED)
            withTimeout(5_000) { f.engine.state.first { !it.clientActive } }
            witnessRelease.countDown()
            withTimeout(5_000) {
                f.engine.localLockState.first { it.phase == LocalLockPhase.RECOVERY && it.issue == LocalLockIssue.DATABASE_MISMATCH }
            }
            assertEquals(LocalLockIssue.DATABASE_MISMATCH, f.owner.get().state.value.issue)
            assertFalse(f.owner.get().state.value.phase == LocalLockPhase.COLD_LOCKED)
        } finally {
            witnessRelease.countDown()
            f.dispose()
        }
    }

    @Test
    fun oldParameterResultAfterLockAndNewUnlockStillClosesTheOldClientWithoutFailure() = runBlocking {
        val f = Fixture()
        val witnessEntered = CountDownLatch(1)
        val witnessRelease = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        var locker: Thread? = null
        try {
            val next = f.openReplacement()
            next.authorization(AuthorizationPhase.WAITING_PARAMETERS)
            withTimeout(5_000) { while (next.parameterCallback == null) delay(5) }
            val oldEpoch = f.owner.get().currentWitness().epoch
            f.witnessHook = { witness ->
                if (witness.blocked && f.owner.get().state.value.phase == LocalLockPhase.HOT_LOCKED) {
                    witnessEntered.countDown()
                    check(witnessRelease.await(7, TimeUnit.SECONDS))
                }
            }
            locker = Thread({
                try { f.engine.localLockActions.lockNow() } catch (error: Throwable) { failure.compareAndSet(null, error) }
            }, "synthetic-old-epoch-lock").apply { isDaemon = true; start() }
            assertTrue(witnessEntered.await(5, TimeUnit.SECONDS))
            f.engine.localLockActions.unlock("synthetic".toCharArray())
            withTimeout(5_000) {
                while (f.owner.get().currentWitness().blocked || f.owner.get().currentWitness().epoch <= oldEpoch ||
                    f.owner.get().captureSessionKeyTicket() == null) delay(5)
            }
            next.parameterCallback!!(TdParametersResult(code = -1))
            withTimeout(5_000) { while (next.closeCalls.get() == 0) delay(5) }
            assertFalse(f.engine.state.value.status.engine == EnginePhase.FAILED,
                "An old epoch's callback is a close request, not a current initialization failure.")
            witnessRelease.countDown()
            locker.join(3_000)
            assertEquals(null, failure.get())
            next.authorization(AuthorizationPhase.CLOSED)
            withTimeout(5_000) { f.engine.localLockState.first { it.phase == LocalLockPhase.COLD_LOCKED } }
            assertFalse(f.engine.localLockState.value.issue == LocalLockIssue.DATABASE_MISMATCH)
        } finally {
            witnessRelease.countDown()
            locker?.join(3_000)
            f.dispose()
        }
    }

    @Test
    fun alreadyQueuedLocalParametersHandledCannotReopenAfterWitnessIsRevoked() = runBlocking {
        val dispatcher = StepDispatcher()
        val f = Fixture(dispatcher)
        try {
            val next = f.openReplacementStepped(dispatcher)
            next.authorization(AuthorizationPhase.WAITING_PARAMETERS)
            dispatcher.until { next.parameterCallback != null }
            dispatcher.drain()
            assertEquals(null, f.owner.get().captureSessionKeyTicket(), "Replacement is still awaiting its native result.")
            next.parameterCallback!!(TdParametersResult(code = null))
            // Step the result action and coordinator worker only. When the ticket settles,
            // LocalParametersHandled has been enqueued but its actor has not yet resumed.
            dispatcher.until { f.owner.get().captureSessionKeyTicket() != null }
            assertEquals(0, next.closeCalls.get())
            f.engine.localLockActions.lockNow()
            assertTrue(f.owner.get().currentWitness().blocked)
            dispatcher.drain()
            assertTrue(next.closeCalls.get() > 0)
            assertFalse(f.engine.state.value.status.engine == EnginePhase.FAILED)
            assertEquals(1, next.parameterCalls.get(), "The already submitted request is not repeated.")
            next.authorization(AuthorizationPhase.CLOSED)
            dispatcher.until { f.engine.localLockState.value.phase == LocalLockPhase.COLD_LOCKED }
            assertFalse(f.engine.localLockState.value.issue == LocalLockIssue.DATABASE_MISMATCH)
        } finally {
            f.witnessHook = null
            f.transport.sessions.forEach { it.autoClose = true }
            if (f.engine.state.value.clientActive) f.transport.sessions.lastOrNull()?.authorization(AuthorizationPhase.CLOSED)
            val closing = async(dispatcher, start = CoroutineStart.UNDISPATCHED) { f.engine.close() }
            dispatcher.until { closing.isCompleted }
            assertTrue(closing.await())
        }
    }

    private class Fixture(engineDispatcher: CoroutineDispatcher = Dispatchers.Default) {
        private val root = Files.createTempDirectory("nnggram-qr-owner-read-")
        val dataRoot: Path = root.resolve("data")
        private val config = root.resolve("tdlib.properties").also {
            Files.writeString(it, "api_id=12345\napi_hash=${"a".repeat(32)}\n")
        }
        val transport = SyntheticTransport()
        val owner = AtomicReference<LocalLockCoordinator>()
        @Volatile var witnessHook: ((LocalLockWitness) -> Unit)? = null
        val engine = TdEngine(ConfigurationStore(config, dataRoot), transport, engineDispatcher)
        init {
            engine.localLockCoordinatorFactory = { lease, worker, start, changed ->
                LocalLockCoordinator(lease, worker, start, onWitnessChanged = { witness -> witnessHook?.invoke(witness); changed(witness) },
                    persistence = SyntheticPersistence(), crypto = SyntheticCrypto(), preferences = SyntheticPreferences()).also(owner::set)
            }
            engine.localLockIdleFactory = { due, availability ->
                LocalLockIdleController(object : LocalLockIdleSource {
                    override fun tickMillis() = 1_000L
                    override fun lastInputDword() = 1_000L
                }, LocalLockDeadlineScheduler { _, _ -> LocalLockDeadlineHandle {} }, due, availability)
            }
        }

        suspend fun openReplacement(): SyntheticSession {
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
            assertTrue(engine.cancelQrLogin(engine.state.value.loginToken))
            withTimeout(5_000) { while (old.closeCalls.get() == 0) delay(5) }
            old.authorization(AuthorizationPhase.CLOSED)
            withTimeout(5_000) { while (transport.sessions.size != 2) delay(5) }
            return transport.sessions.last()
        }

        fun openReplacementStepped(dispatcher: StepDispatcher): SyntheticSession {
            assertTrue(engine.start())
            dispatcher.until { engine.localLockState.value.phase == LocalLockPhase.COLD_LOCKED }
            engine.localLockActions.unlock("synthetic".toCharArray())
            dispatcher.until { transport.sessions.isNotEmpty() }
            val old = transport.sessions.single()
            old.authorization(AuthorizationPhase.WAITING_PARAMETERS)
            dispatcher.until { old.parameterCallback != null }
            old.parameterCallback!!(TdParametersResult(code = null))
            dispatcher.until { engine.localLockState.value.phase == LocalLockPhase.UNLOCKED }
            old.authorization(AuthorizationDetails(AuthorizationPhase.OTHER_STEP, otherStep = LoginOtherStep.DEVICE_CONFIRMATION,
                presentation = LoginPresentation(qrLink = "tg://login?token=synthetic")))
            dispatcher.until { engine.state.value.loginQrPhoneSwitchAvailable && engine.state.value.canSubmitLogin(LoginRequestKind.RESET_PHONE) }
            assertTrue(engine.cancelQrLogin(engine.state.value.loginToken))
            dispatcher.until { old.closeCalls.get() > 0 }
            old.authorization(AuthorizationPhase.CLOSED)
            dispatcher.until { transport.sessions.size == 2 && engine.state.value.clientActive }
            dispatcher.drain()
            return transport.sessions.last()
        }

        suspend fun dispose() {
            witnessHook = null
            transport.sessions.forEach { it.autoClose = true }
            if (engine.state.value.clientActive) transport.sessions.lastOrNull()?.authorization(AuthorizationPhase.CLOSED)
            withTimeout(10_000) { engine.close() }
        }
    }

    /** One queued coroutine dispatch at a time; no timer, native call or system event. */
    private class StepDispatcher : CoroutineDispatcher() {
        private val tasks = ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
        private fun step(): Boolean {
            val next = tasks.poll() ?: return false
            next.run()
            return true
        }
        fun until(done: () -> Boolean) {
            repeat(2_000) {
                if (done()) return
                check(step()) { "Synthetic actor stalled before the expected state" }
            }
            error("Synthetic actor exceeded its bounded step budget")
        }
        fun drain() {
            repeat(2_000) { if (!step()) return }
            error("Synthetic actor did not reach an idle queue")
        }
    }

    private class SyntheticPersistence : LocalProtectionPersistence {
        private val record = LocalStoredEnvelope(byteArrayOf(7, 8), 1, ByteArray(32) { 1 })
        override fun read(): LocalRecordRead = LocalRecordRead.Present(record)
        override fun commit(candidate: ByteArray, expected: LocalStoredEnvelope?): LocalCommitResult = error("No record writes")
        override fun finalizeCommit(candidate: ByteArray, expected: LocalStoredEnvelope?): LocalCommitResult = error("No record finalization")
    }

    private class SyntheticCrypto : LocalLockCryptoOperations {
        override fun create(password: CharArray, rootIdentity: ByteArray, generation: Long): LocalCryptoResult<WrappedDatabaseKey> = error("Not used")
        override fun open(password: CharArray, record: ByteArray, rootIdentity: ByteArray, generation: Long): LocalCryptoResult<LocalDatabaseKey> =
            LocalCryptoResult.Success(LocalDatabaseKey.copyOf(ByteArray(32) { 42 }))
        override fun rewrap(password: CharArray, key: LocalDatabaseKey, rootIdentity: ByteArray, generation: Long): LocalCryptoResult<ByteArray> = error("Not used")
    }

    private class SyntheticPreferences : LocalLockPreferences {
        override fun read(): LocalLockPreferenceRead = LocalLockPreferenceRead.Value(3_600)
        override fun write(seconds: Long) = error("No preference writes")
    }

    private class SyntheticTransport : TdTransport {
        val sessions = CopyOnWriteArrayList<SyntheticSession>()
        override fun load() = NativeInfo("synthetic", "synthetic", "synthetic")
        override fun create(onAuthorization: (AuthorizationDetails) -> Unit, onConnection: (ConnectionPhase) -> Unit,
            onError: () -> Unit, onChatEvent: (ChatEvent) -> Unit): TdSession =
            SyntheticSession(onAuthorization).also { sessions += it }
    }

    private class SyntheticSession(private val onAuthorization: (AuthorizationDetails) -> Unit) : TdSession {
        val closeCalls = AtomicInteger()
        val parameterCalls = AtomicInteger()
        val loginCalls = AtomicInteger()
        @Volatile var parameterCallback: ((TdParametersResult) -> Unit)? = null
        @Volatile var autoClose = false
        fun authorization(phase: AuthorizationPhase) = authorization(AuthorizationDetails(phase))
        fun authorization(details: AuthorizationDetails) = onAuthorization(details)
        override fun setParameters(config: TdConfiguration, directories: DataDirectoryLease, result: (Int?) -> Unit) = error("Protected start requires a key")
        override fun setParameters(config: TdConfiguration, directories: DataDirectoryLease, key: ByteArray,
            result: (TdParametersResult) -> Unit) { parameterCalls.incrementAndGet(); assertEquals(32, key.size); parameterCallback = result }
        override fun configureProxy(proxy: ProxyConfiguration?, result: (Int?) -> Unit) = result(null)
        override fun submitLogin(kind: LoginRequestKind, value: String, result: (LoginResult) -> Unit) { loginCalls.incrementAndGet(); error("No login") }
        override fun close() { closeCalls.incrementAndGet(); if (autoClose) authorization(AuthorizationPhase.CLOSED) }
    }
}
