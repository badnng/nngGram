package com.badnng.moe.tdlib

import com.badnng.moe.status.AuthorizationPhase
import com.badnng.moe.status.AuthorizationDetails
import com.badnng.moe.status.ConnectionPhase
import com.badnng.moe.status.DiagnosticKind
import com.badnng.moe.status.EnginePhase
import com.badnng.moe.status.FailureCategory
import com.badnng.moe.status.ProxyStatus
import com.badnng.moe.status.RuntimeSnapshot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.channels.FileChannel
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.DosFileAttributeView
import java.util.Properties
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A standalone JVM entry point: deliberately returns naturally instead of calling System.exit. */
object NativeAcceptanceProcess {
    @JvmStatic
    fun main(arguments: Array<String>) = runBlocking {
        require(arguments.size == 3)
        val (scenario, directory, nativeDirectory) = arguments
        val root = Path.of(directory)
        require(root.isAbsolute && Files.isDirectory(root))
        val data = root.resolve("用户 数据")
        val config = root.resolve("fixture.properties")
        if (scenario != "missing-configuration") {
            Files.writeString(config, "api_id=1\napi_hash=${"0".repeat(32)}\nuse_test_dc=true\n")
        }
        if (scenario == "missing-dll") {
            val incompleteBundle = Files.createDirectories(root.resolve("不完整 原生包"))
            Files.copy(Path.of(nativeDirectory).resolve("manifest.properties"), incompleteBundle.resolve("manifest.properties"))
            System.setProperty("nnggram.tdlib.dir", incompleteBundle.toString())
        }
        if (scenario == "occupied-directory") {
            Files.createDirectories(data)
            Files.writeString(data.resolve("database"), "This is a fixture file, not a directory.")
        }
        val readOnlyFile = if (scenario == "permission-denied") {
            Files.createDirectories(data)
            Files.writeString(data.resolve("client.lock"), "").also { file ->
                Files.getFileAttributeView(file, DosFileAttributeView::class.java).setReadOnly(true)
                // A genuine Windows write denial, independent of the engine's error mapping.
                // This is a denied critical file, not a claim that a directory's ACL was changed.
                assertFailsWith<AccessDeniedException> {
                    FileChannel.open(file, StandardOpenOption.WRITE).use { }
                }
            }
        } else null
        val transport = CountingTransport()
        val engine = TdEngine(ConfigurationStore(config, data), transport)
        try {
            when (scenario) {
                "initialize", "restart" -> {
                    repeat(if (scenario == "restart") 10 else 1) { round ->
                        repeat(100) { engine.start() }
                        val snapshot = awaitInitialized(engine)
                        assertNativePin(snapshot)
                        assertTrue(snapshot.clientActive)
                        assertTrue(Files.list(data.resolve("database")).use { it.findAny().isPresent })
                        assertFailsWith<SetupFailure> { DataDirectoryLease.acquire(data) }
                        withTimeout(10_000) {
                            engine.state.first { it.status.connection == ConnectionPhase.WAITING_NETWORK }
                        }
                        assertTrue(engine.stop(15_000), "TDLib did not report Closed during round ${round + 1}")
                        assertEquals(EnginePhase.CLOSED, engine.state.value.status.engine)
                        assertFalse(engine.state.value.clientActive)
                        assertFalse(engine.state.value.terminated, "stop() must permit another start")
                        assertEquals(round + 1, transport.created.get(), "Repeated starts created an extra native client")
                        assertEquals(round + 1, transport.parameters.get(), "Parameters were submitted more than once")
                        assertEquals(round + 1, transport.closed.get(), "Close was submitted more than once")
                        DataDirectoryLease.acquire(data).close()
                    }
                }
                "missing-configuration" -> {
                    engine.start()
                    val snapshot = withTimeout(40_000) {
                        engine.state.first {
                            it.status.engine == EnginePhase.WAITING_CONFIGURATION || it.status.engine == EnginePhase.FAILED
                        }
                    }
                    assertEquals(EnginePhase.WAITING_CONFIGURATION, snapshot.status.engine, snapshot.status.failure?.toString())
                    assertNativePin(snapshot)
                    assertFalse(snapshot.clientActive)
                    assertEquals(0, transport.created.get())
                    assertFalse(Files.exists(data), "Missing credentials should not create a database")
                    Files.writeString(config, "api_id=1\napi_hash=${"0".repeat(32)}\nuse_test_dc=true\n")
                    engine.start()
                    assertNativePin(awaitInitialized(engine))
                    assertTrue(engine.stop(15_000))
                    DataDirectoryLease.acquire(data).close()
                }
                "missing-dll", "occupied-directory", "permission-denied" -> {
                    engine.start()
                    val snapshot = withTimeout(40_000) { engine.state.first { it.status.engine == EnginePhase.FAILED } }
                    assertFalse(snapshot.clientActive)
                    assertEquals(0, transport.created.get())
                    assertTrue(snapshot.canStart, "A setup failure must remain recoverable")
                    assertTrue(assertNotNull(snapshot.status.failure).recovery.isNotBlank())
                    assertEquals(
                        if (scenario == "missing-dll") FailureCategory.NATIVE else FailureCategory.DIRECTORY,
                        snapshot.status.failure!!.category,
                        "The failure must be attributed to the actual native/directory boundary",
                    )
                    if (scenario == "missing-dll") {
                        assertTrue(snapshot.status.failure!!.summary.contains("缺少原生依赖"))
                    } else {
                        assertNativePin(snapshot)
                    }
                    if (scenario == "occupied-directory") Files.delete(data.resolve("database"))
                    readOnlyFile?.let { Files.getFileAttributeView(it, DosFileAttributeView::class.java).setReadOnly(false) }
                    if (scenario != "missing-dll") DataDirectoryLease.acquire(data).close()
                    if (scenario == "missing-dll") System.setProperty("nnggram.tdlib.dir", nativeDirectory)
                    engine.start()
                    assertNativePin(awaitInitialized(engine))
                    assertEquals(1, transport.created.get(), "A repaired setup failure must create exactly one client")
                    assertTrue(engine.stop(15_000), "The recovered native client did not close")
                    DataDirectoryLease.acquire(data).close()
                }
                else -> error("Unknown synthetic acceptance scenario")
            }
        } finally {
            assertTrue(engine.close(15_000), "The child did not finish graceful TDLib shutdown")
            readOnlyFile?.let { Files.getFileAttributeView(it, DosFileAttributeView::class.java).setReadOnly(false) }
        }
        assertTrue(engine.close(1_000), "Shutdown must be idempotent")
        assertFalse(engine.state.value.clientActive)
        assertTrue(engine.state.value.terminated, "close() must leave the actor permanently terminated")
        assertFalse(engine.state.value.canStart)
        assertFalse(engine.start(), "A disposed engine must reject restart requests")
        assertTrue(engine.state.value.events.any { it.kind == DiagnosticKind.CLOSE_CONFIRMED })
        assertTrue(engine.state.value.events.size <= 128, "Runtime history must remain bounded")
        assertTrue(engine.state.value.eventLogAvailable, "Fixture event logging failed")
        Files.walk(root).use { paths ->
            val logs = paths.filter { it.fileName.toString().startsWith("runtime-events-") && it.fileName.toString().endsWith(".log") }
                .toList()
            assertTrue(logs.isNotEmpty(), "Expected real event log files in the isolated fixture")
            logs.forEach { path ->
                    val log = Files.readString(path)
                    assertFalse(log.contains("0".repeat(32)), "The synthetic API hash appeared in a diagnostic log")
                    assertFalse(log.contains("api_id") || log.contains("api_hash"), "Configuration fields appeared in a diagnostic log")
                    assertFalse(log.contains(config.toString()), "Configuration paths must not enter typed event records")
                }
        }
        val native = engine.state.value.native
        println("ACCEPTANCE_OK $scenario; created=${transport.created.get()}; closed=${transport.closed.get()}; version=${native?.version ?: "not-loaded"}; commit=${native?.commit ?: "not-loaded"}")
    }

    private suspend fun awaitInitialized(engine: TdEngine): RuntimeSnapshot {
        val snapshot = withTimeout(40_000) {
            engine.state.first {
                (it.status.authorization == AuthorizationPhase.WAITING_LOGIN &&
                    it.status.engine == EnginePhase.INITIALIZED && it.proxy == ProxyStatus.DIRECT) ||
                    it.status.engine == EnginePhase.FAILED
            }
        }
        assertEquals(EnginePhase.INITIALIZED, snapshot.status.engine, snapshot.status.failure?.toString())
        assertEquals(AuthorizationPhase.WAITING_LOGIN, snapshot.status.authorization)
        return snapshot
    }

    private fun assertNativePin(snapshot: RuntimeSnapshot) {
        val native = assertNotNull(snapshot.native)
        val pin = Properties().apply {
            NativeAcceptanceProcess::class.java.getResourceAsStream("/tdlib-source.properties")!!.use(::load)
        }
        assertEquals(pin.getProperty("commit"), native.commit)
        assertTrue(native.version.isNotBlank())
    }

    private class CountingTransport(private val delegate: TdTransport = OfficialTdTransport(offline = true)) : TdTransport by delegate {
        val created = AtomicInteger()
        val parameters = AtomicInteger()
        val closed = AtomicInteger()

        override fun create(
            onAuthorization: (AuthorizationDetails) -> Unit,
            onConnection: (ConnectionPhase) -> Unit,
            onError: () -> Unit,
            onChatEvent: (com.badnng.moe.chat.ChatEvent) -> Unit,
        ): TdSession {
            created.incrementAndGet()
            val session = delegate.create(onAuthorization, onConnection, onError, onChatEvent)
            return object : TdSession by session {
                override fun setParameters(config: TdConfiguration, directories: DataDirectoryLease, result: (Int?) -> Unit) {
                    parameters.incrementAndGet()
                    session.setParameters(config, directories, result)
                }

                override fun close() {
                    closed.incrementAndGet()
                    session.close()
                }
            }
        }
    }
}
