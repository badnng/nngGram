package com.badnng.moe.tdlib

import com.badnng.moe.status.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class TdEngineTest {
    @Test
    fun missingConfigurationDoesNotLoadNativeOrCreateAClient() = runTest {
        val (store, transport) = fixture(configured = false)
        val engine = syntheticOpenEngine(store, transport, StandardTestDispatcher(testScheduler))
        engine.start()
        runCurrent()
        assertEquals(EnginePhase.WAITING_CONFIGURATION, engine.state.value.status.engine)
        assertNull(engine.state.value.native)
        assertEquals(0, transport.loads)
        assertEquals(0, transport.created)
        assertTrue(engine.close())
        assertTrue(engine.close())
        assertTrue(engine.state.value.terminated)
        assertFalse(engine.start())
    }

    @Test
    fun repeatedStartsAndRepeatedWaitEventsSubmitParametersAndConnectionConfigurationExactlyOnce() = runTest {
        val (store, transport) = fixture()
        val engine = syntheticOpenEngine(store, transport, StandardTestDispatcher(testScheduler))
        repeat(20) { engine.start() }
        runCurrent()
        assertEquals(1, transport.created)
        assertEquals(0, transport.parameters)
        repeat(5) { transport.authorization(AuthorizationPhase.WAITING_PARAMETERS) }
        runCurrent()
        assertEquals(1, transport.parameters)
        assertEquals(1, transport.proxyConfigurations)
        transport.sessions.single().parameterResult?.invoke(null)
        runCurrent()
        assertEquals(1, transport.proxyConfigurations)
        transport.authorization(AuthorizationPhase.WAITING_LOGIN)
        runCurrent()
        assertEquals(EnginePhase.INITIALIZED, engine.state.value.status.engine)
        assertEquals(AuthorizationPhase.WAITING_LOGIN, engine.state.value.status.authorization)
        assertEquals(ConnectionPhase.UNKNOWN, engine.state.value.status.connection)
        assertEquals(ProxyStatus.DIRECT, engine.state.value.proxy)
        assertTrue(engine.close())
        assertEquals(1, transport.closed)
        assertEquals(EnginePhase.CLOSED, engine.state.value.status.engine)
    }

    @Test
    fun rejectedParametersReleaseTheClientAndAllowARetry() = runTest {
        val (store, transport) = fixture()
        transport.resultCode = 400
        val engine = syntheticOpenEngine(store, transport, StandardTestDispatcher(testScheduler))
        engine.start()
        runCurrent()
        transport.authorization(AuthorizationPhase.WAITING_PARAMETERS)
        runCurrent()
        assertEquals(EnginePhase.FAILED, engine.state.value.status.engine)
        assertEquals(FailureCategory.TDLIB_REQUEST, engine.state.value.status.failure?.category)
        assertFalse(engine.state.value.clientActive)
        assertTrue(engine.state.value.canStart)
        assertEquals(0, transport.proxyConfigurations)
        transport.resultCode = null
        engine.start()
        runCurrent()
        assertEquals(2, transport.created)
        assertTrue(engine.close())
    }

    @Test
    fun networkChangesRemainIndependentOfInitializationAndAuthorization() = runTest {
        val (store, transport) = fixture()
        val engine = syntheticOpenEngine(store, transport, StandardTestDispatcher(testScheduler))
        engine.start()
        runCurrent()
        transport.authorization(AuthorizationPhase.WAITING_PARAMETERS)
        transport.authorization(AuthorizationPhase.WAITING_LOGIN)
        runCurrent()
        for (phase in ConnectionPhase.entries) {
            transport.connection(phase)
            runCurrent()
            assertEquals(phase, engine.state.value.status.connection)
            assertEquals(EnginePhase.INITIALIZED, engine.state.value.status.engine)
            assertEquals(AuthorizationPhase.WAITING_LOGIN, engine.state.value.status.authorization)
            assertNull(engine.state.value.status.failure)
        }
        transport.connection(ConnectionPhase.WAITING_NETWORK)
        runCurrent()
        assertEquals(EnginePhase.INITIALIZED, engine.state.value.status.engine)
        assertTrue(engine.state.value.events.any { it.kind == DiagnosticKind.CONNECTION_CHANGED })
        assertTrue(engine.close())
    }

    @Test
    fun rejectedProxyConfigurationDoesNotTurnSuccessfulInitializationIntoFailure() = runTest {
        val (store, transport) = fixture()
        transport.proxyResultCode = 407
        val engine = syntheticOpenEngine(store, transport, StandardTestDispatcher(testScheduler))
        engine.start()
        runCurrent()
        transport.authorization(AuthorizationPhase.WAITING_PARAMETERS)
        transport.authorization(AuthorizationPhase.WAITING_LOGIN)
        transport.connection(ConnectionPhase.WAITING_NETWORK)
        runCurrent()
        assertEquals(EnginePhase.INITIALIZED, engine.state.value.status.engine)
        assertEquals(ProxyStatus.FAILED, engine.state.value.proxy)
        assertEquals(FailureCategory.PROXY, engine.state.value.proxyFailure?.category)
        assertEquals(0, transport.closed)
        assertNull(engine.state.value.status.failure)
        assertTrue(engine.close())
    }

    @Test
    fun concurrentStopsWaitForClosedAndSendOnlyOneCloseRequest() = runTest {
        val (store, transport) = fixture()
        transport.autoClose = false
        val engine = syntheticOpenEngine(store, transport, StandardTestDispatcher(testScheduler))
        engine.start()
        runCurrent()
        val waiters = List(10) { async { engine.stop() } }
        runCurrent()
        assertEquals(1, transport.closed)
        assertTrue(waiters.none { it.isCompleted })
        assertTrue(engine.state.value.clientActive)
        assertFalse(engine.state.value.canStart)
        assertFailsWith<SetupFailure> { DataDirectoryLease.acquire(store.read()!!.dataDirectory) }
        transport.authorization(AuthorizationPhase.WAITING_PARAMETERS)
        transport.authorization(AuthorizationPhase.READY)
        transport.connection(ConnectionPhase.READY)
        runCurrent()
        assertEquals(0, transport.parameters)
        assertEquals(ConnectionPhase.UNKNOWN, engine.state.value.status.connection)
        assertEquals(EnginePhase.CLOSING, engine.state.value.status.engine)
        transport.authorization(AuthorizationPhase.CLOSED)
        runCurrent()
        waiters.forEach { assertTrue(it.await()) }
        assertFalse(engine.state.value.clientActive)
        assertTrue(engine.state.value.canStart)
        DataDirectoryLease.acquire(store.read()!!.dataDirectory).close()
        assertTrue(engine.close())
        assertTrue(engine.close())
        assertEquals(1, transport.closed)
    }

    @Test
    fun stopTimeoutRetainsTheLeaseUntilLateClosedAndThenPermitsRestart() = runTest {
        val (store, transport) = fixture()
        transport.autoClose = false
        val engine = syntheticOpenEngine(store, transport, StandardTestDispatcher(testScheduler))
        engine.start()
        runCurrent()
        val closing = async { engine.stop(100) }
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        assertFalse(closing.await())
        assertTrue(engine.state.value.closeTimedOut)
        assertTrue(engine.state.value.clientActive)
        assertFalse(engine.state.value.terminated)
        assertFailsWith<SetupFailure> { DataDirectoryLease.acquire(store.read()!!.dataDirectory) }
        repeat(5) { engine.start() }
        runCurrent()
        assertEquals(1, transport.created)
        transport.authorization(AuthorizationPhase.CLOSED)
        runCurrent()
        assertFalse(engine.state.value.closeTimedOut)
        assertTrue(engine.state.value.canStart)
        engine.start()
        runCurrent()
        assertEquals(2, transport.created)
        assertEquals(2L, engine.state.value.generation)
        transport.autoClose = true
        assertTrue(engine.close())
    }

    @Test
    fun timedOutTerminalCloseRemainsPendingUntilNativeClosureAndIsIdempotentAfterward() = runTest {
        val (store, transport) = fixture()
        transport.autoClose = false
        val engine = syntheticOpenEngine(store, transport, StandardTestDispatcher(testScheduler))
        engine.start()
        runCurrent()
        val closing = async { engine.close(100) }
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        assertFalse(closing.await())
        assertFalse(engine.state.value.terminated)
        assertTrue(engine.state.value.clientActive)
        assertTrue(engine.state.value.events.any { it.kind == DiagnosticKind.CLOSE_TIMED_OUT && it.category == FailureCategory.LIFECYCLE })
        transport.authorization(AuthorizationPhase.CLOSED)
        runCurrent()
        assertTrue(engine.state.value.terminated)
        assertFalse(engine.state.value.clientActive)
        assertTrue(engine.close())
        assertTrue(engine.stop())
        assertFalse(engine.start())
        assertEquals(1, transport.closed)
    }

    @Test
    fun callbacksFromClosedAndPreviousGenerationsCannotResurrectTheClient() = runTest {
        val (store, transport) = fixture()
        transport.delayParameters = true
        val engine = syntheticOpenEngine(store, transport, StandardTestDispatcher(testScheduler))
        engine.start()
        runCurrent()
        transport.authorization(AuthorizationPhase.WAITING_PARAMETERS)
        runCurrent()
        val first = transport.sessions.single()
        first.authorization(AuthorizationPhase.CLOSED)
        runCurrent()
        first.parameterResult?.invoke(null)
        first.authorization(AuthorizationPhase.READY)
        first.connection(ConnectionPhase.READY)
        first.error()
        runCurrent()
        assertEquals(EnginePhase.CLOSED, engine.state.value.status.engine)
        assertEquals(ConnectionPhase.UNKNOWN, engine.state.value.status.connection)
        assertEquals(0, transport.proxyConfigurations)
        engine.start()
        runCurrent()
        first.parameterResult?.invoke(400)
        first.authorization(AuthorizationPhase.CLOSED)
        first.connection(ConnectionPhase.READY)
        first.error()
        runCurrent()
        assertEquals(EnginePhase.STARTING, engine.state.value.status.engine)
        assertTrue(engine.state.value.clientActive)
        assertEquals(2L, engine.state.value.generation)
        assertEquals(ConnectionPhase.UNKNOWN, engine.state.value.status.connection)
        assertTrue(engine.close())
    }

    @Test
    fun concurrentStopAndTerminalCloseDisposeOnlyAfterActualClosed() = runTest {
        val (store, transport) = fixture()
        transport.autoClose = false
        val engine = syntheticOpenEngine(store, transport, StandardTestDispatcher(testScheduler))
        engine.start()
        runCurrent()
        val stopping = async { engine.stop() }
        val closing = async { engine.close() }
        runCurrent()
        assertEquals(1, transport.closed)
        transport.authorization(AuthorizationPhase.CLOSED)
        runCurrent()
        assertTrue(stopping.await())
        assertTrue(closing.await())
        assertTrue(engine.state.value.terminated)
        assertFalse(engine.start())
    }

    @Test
    fun unavailableEventLogDoesNotPreventConfigurationGuidance() = runTest {
        val (store, transport) = fixture(configured = false)
        val blocked = Files.createTempFile("blocked-journal-", ".file")
        val engine = syntheticOpenEngine(store, transport, StandardTestDispatcher(testScheduler), SafeEventJournal(blocked))
        engine.start()
        runCurrent()
        assertEquals(EnginePhase.WAITING_CONFIGURATION, engine.state.value.status.engine)
        assertFalse(engine.state.value.eventLogAvailable)
        assertTrue(engine.state.value.events.isNotEmpty())
        assertTrue(engine.close())
    }

    @Test
    fun unexpectedExceptionsNeverCopyTheirMessagesIntoStateOrLogs() = runTest {
        val (store, transport) = fixture()
        transport.loadError = IllegalStateException("sensitive-exception-message")
        val engine = syntheticOpenEngine(store, transport, StandardTestDispatcher(testScheduler))
        engine.start()
        runCurrent()
        assertEquals(FailureCategory.INTERNAL, engine.state.value.status.failure?.category)
        assertFalse(engine.state.value.toString().contains("sensitive-exception-message"))
        val log = Files.readString(java.nio.file.Path.of(engine.state.value.eventLogPath!!))
        assertFalse(log.contains("sensitive-exception-message"))
        assertFalse(log.contains("a".repeat(32)))
        assertTrue(engine.close())
    }

    @Test
    fun terminalFlagIsPublishedOnlyAfterTheFinalClosureEventHasBeenRecorded() = runTest {
        val (store, transport) = fixture()
        val seenAtClosure = mutableListOf<Boolean>()
        lateinit var engine: TdEngine
        engine = syntheticOpenEngine(store, transport, StandardTestDispatcher(testScheduler), nowMillis = {
            if (engine.state.value.status.engine == EnginePhase.CLOSED && engine.state.value.status.authorization == AuthorizationPhase.CLOSED) {
                seenAtClosure += engine.state.value.terminated
            }
            0L
        })
        engine.start()
        runCurrent()
        assertTrue(engine.close())
        assertEquals(listOf(false), seenAtClosure)
        assertTrue(engine.state.value.terminated)
        val log = Files.readString(java.nio.file.Path.of(engine.state.value.eventLogPath!!))
        assertTrue(log.contains("kind=CLOSE_CONFIRMED"))
    }

    private fun fixture(configured: Boolean = true): Pair<ConfigurationStore, FakeTransport> {
        val root = Files.createTempDirectory("engine-test-")
        val path = root.resolve("tdlib.properties")
        if (configured) Files.writeString(path, "api_id=12345\napi_hash=${"a".repeat(32)}\n")
        return ConfigurationStore(path, root.resolve("data")) to FakeTransport()
    }

    private class FakeTransport : TdTransport {
        val sessions = mutableListOf<FakeSession>()
        val created: Int get() = sessions.size
        var parameters = 0
        var proxyConfigurations = 0
        var closed = 0
        var autoClose = true
        var delayParameters = false
        var resultCode: Int? = null
        var proxyResultCode: Int? = null
        var loadError: Exception? = null
        var loads = 0
        fun authorization(phase: AuthorizationPhase) = sessions.last().authorization(phase)
        fun connection(phase: ConnectionPhase) = sessions.last().connection(phase)
        override fun load(): NativeInfo {
            loads++
            loadError?.let { throw it }
            return NativeInfo("fixture-version", "fixture-commit", "fixture-path")
        }
        override fun create(onAuthorization: (AuthorizationDetails) -> Unit, onConnection: (ConnectionPhase) -> Unit, onError: () -> Unit, onChatEvent: (com.badnng.moe.chat.ChatEvent) -> Unit): TdSession =
            FakeSession(onAuthorization, onConnection, onError).also(sessions::add)

        inner class FakeSession(
            val authorizationUpdate: (AuthorizationDetails) -> Unit,
            val connection: (ConnectionPhase) -> Unit,
            val error: () -> Unit,
        ) : SyntheticOpenKeySession {
            fun authorization(phase: AuthorizationPhase) = authorizationUpdate(AuthorizationDetails(phase))
            override fun submitLogin(kind: LoginRequestKind, value: String, result: (LoginResult) -> Unit) {
                error("Lifecycle fixtures must never submit login input")
            }
            var parameterResult: ((Int?) -> Unit)? = null
            override fun setParameters(config: TdConfiguration, directories: DataDirectoryLease, result: (Int?) -> Unit) {
                parameters++
                parameterResult = result
                if (!delayParameters) result(resultCode)
            }
            override fun configureProxy(proxy: ProxyConfiguration?, result: (Int?) -> Unit) {
                proxyConfigurations++
                result(proxyResultCode)
            }
            override fun close() {
                closed++
                if (autoClose) authorization(AuthorizationPhase.CLOSED)
            }
        }
    }
}
