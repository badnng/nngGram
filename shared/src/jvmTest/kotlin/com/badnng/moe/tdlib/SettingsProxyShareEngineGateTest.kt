package com.badnng.moe.tdlib

import com.badnng.moe.chat.ChatEvent
import com.badnng.moe.settings.*
import com.badnng.moe.status.*
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

/** Production Engine and actor ownership with a synthetic session and no external resources. */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsProxyShareEngineGateTest {
    @Test
    fun refreshWritesPageAndAccountGatesRevokeBeforeTheActorRuns() = runTest {
        for (cause in listOf("refresh", "load", "enable", "remove", "add", "storage", "close", "page", "authorization", "ordinary")) {
            withEngine { engine, f ->
                val active = AtomicBoolean(true)
                val (load, request) = open(engine, active)
                val outcomes = mutableListOf<SettingsResult<SettingsProxyShareContent>>()
                load.share(request, outcomes::add)
                runCurrent()
                f.current.settings.shares.single().complete(SettingsResult(listOf(PUBLIC_LINK)))
                runCurrent()
                val content = assertNotNull(outcomes.single().value)
                assertTrue(content.textIfCurrent() == PUBLIC_LINK)
                val parent = engine.state.value.settings.token
                when (cause) {
                    "refresh" -> load.refresh()
                    "load" -> assertTrue(engine.loadSettings(parent, SettingsSection.ADVANCED))
                    "enable" -> assertTrue(engine.enableSettingsProxy(parent, 1))
                    "remove" -> assertTrue(engine.removeSettingsProxy(parent, 1))
                    "add" -> assertTrue(engine.addSettingsProxy(parent, SettingsProxyDraft("new.invalid", 1080, SettingsProxyType.SOCKS5)))
                    "storage" -> assertTrue(engine.optimizeSettingsStorage(parent))
                    "close" -> load.close()
                    "page" -> active.set(false)
                    "authorization" -> f.current.auth(AuthorizationPhase.WAITING_PASSWORD)
                    "ordinary" -> f.current.requireEmail()
                }
                var commits = 0
                assertFalse(content.useIfCurrent { commits++ }, "$cause must revoke at its public boundary.")
                assertEquals(0, commits)
                assertNull(content.textIfCurrent())
                assertFalse(engine.state.value.toString().contains(PUBLIC_LINK))
                // No actor advancement occurred between the invalidating action and the assertions.
                runCurrent()
            }
        }
    }

    @Test
    fun aQueuedOlderRequestCannotCancelTheNewerOwner() = runTest {
        withEngine { engine, f ->
            val (load, request) = open(engine)
            val first = mutableListOf<SettingsResult<SettingsProxyShareContent>>()
            val second = mutableListOf<SettingsResult<SettingsProxyShareContent>>()
            load.share(request, first::add)
            load.share(request, second::add)
            runCurrent()
            assertEquals(SettingsError.NOT_READY, first.single().error)
            assertEquals(1, f.current.settings.shares.size)
            f.current.settings.shares.single().complete(SettingsResult(listOf(PUBLIC_LINK)))
            runCurrent()
            assertTrue(assertNotNull(second.single().value).textIfCurrent() == PUBLIC_LINK)
            assertNull(first.single().value)
        }
    }

    @Test
    fun lateOldCallbackCannotPublishOrRevokeANewerShare() = runTest {
        withEngine { engine, f ->
            val (load, request) = open(engine)
            val first = mutableListOf<SettingsResult<SettingsProxyShareContent>>()
            val second = mutableListOf<SettingsResult<SettingsProxyShareContent>>()
            load.share(request, first::add); runCurrent()
            val old = f.current.settings.shares.single()
            load.share(request, second::add); runCurrent()
            assertEquals(SettingsError.NOT_READY, first.single().error)
            val fresh = f.current.settings.shares.last()
            assertTrue(old !== fresh)
            old.complete(SettingsResult(listOf(PUBLIC_LINK))); runCurrent()
            assertEquals(1, first.size)
            assertTrue(second.isEmpty())
            fresh.complete(SettingsResult(listOf(PUBLIC_LINK))); runCurrent()
            assertTrue(assertNotNull(second.single().value).textIfCurrent() == PUBLIC_LINK)
        }
    }

    @Test
    fun stopRevokesAnAlreadyPreparedContentBeforeActorCleanup() = runTest {
        withEngine { engine, f ->
            val (load, request) = open(engine)
            val outcomes = mutableListOf<SettingsResult<SettingsProxyShareContent>>()
            load.share(request, outcomes::add); runCurrent()
            f.current.settings.shares.single().complete(SettingsResult(listOf(PUBLIC_LINK))); runCurrent()
            val content = assertNotNull(outcomes.single().value)
            val stop = async(start = CoroutineStart.UNDISPATCHED) { engine.stop() }
            assertFalse(content.useIfCurrent { fail("Stopping must revoke synchronous clipboard admission") })
            assertNull(content.textIfCurrent())
            runCurrent()
            assertTrue(stop.await())
        }
    }

    @Test
    fun stopImmediatelyRevokesContentAndLateNativeCompletionCannotCrossAGeneration() = runTest {
        withEngine { engine, f ->
            val (firstLoad, firstRequest) = open(engine)
            val first = mutableListOf<SettingsResult<SettingsProxyShareContent>>()
            firstLoad.share(firstRequest, first::add); runCurrent()
            val old = f.current.settings.shares.single()
            val stop = async(start = CoroutineStart.UNDISPATCHED) { engine.stop() }
            runCurrent()
            assertTrue(stop.await())
            assertEquals(SettingsError.NOT_READY, first.single().error)

            assertTrue(engine.start()); runCurrent()
            f.current.auth(AuthorizationPhase.WAITING_PARAMETERS); runCurrent()
            f.current.auth(AuthorizationPhase.READY); runCurrent()
            val (secondLoad, secondRequest) = open(engine)
            assertNotEquals(firstRequest.token.parent.generation, secondRequest.token.parent.generation)
            val second = mutableListOf<SettingsResult<SettingsProxyShareContent>>()
            secondLoad.share(secondRequest, second::add); runCurrent()
            old.complete(SettingsResult(listOf(PUBLIC_LINK))); runCurrent()
            assertNull(first.single().value)
            assertTrue(second.isEmpty())
            f.current.settings.shares.single().complete(SettingsResult(listOf(PUBLIC_LINK))); runCurrent()
            assertTrue(assertNotNull(second.single().value).textIfCurrent() == PUBLIC_LINK)
        }
    }

    private fun TestScope.open(engine: TdEngine, active: AtomicBoolean = AtomicBoolean(true)):
        Pair<SettingsProxyChecksLoad, SettingsProxyShareRequest> {
        val opened = mutableListOf<SettingsProxyChecksToken?>()
        val load = engine.openSettingsProxyChecks(engine.state.value.settings.token, active::get, opened::add)
        runCurrent()
        val token = assertNotNull(opened.single())
        val snapshot = engine.state.value.settings
        assertEquals(token, snapshot.proxyChecks.token)
        val row = snapshot.proxies.single()
        return load to SettingsProxyShareRequest(token, snapshot.proxyChecks.listEpoch,
            SettingsProxyShareTarget.One(0, row), SettingsProxyShareFormat.PUBLIC)
    }

    private suspend fun TestScope.withEngine(block: suspend TestScope.(TdEngine, Fixture) -> Unit) {
        val f = Fixture()
        val engine = syntheticOpenEngine(f.store, f, StandardTestDispatcher(testScheduler))
        try {
            assertTrue(engine.start()); runCurrent()
            f.current.auth(AuthorizationPhase.WAITING_PARAMETERS); runCurrent()
            f.current.auth(AuthorizationPhase.READY); runCurrent()
            assertTrue(engine.state.value.settings.enabled)
            block(engine, f)
        } finally {
            val closing = async(start = CoroutineStart.UNDISPATCHED) { engine.close() }
            runCurrent()
            f.sessions.filter { !it.closed }.forEach { it.auth(AuthorizationPhase.CLOSED) }
            runCurrent()
            assertTrue(closing.await())
            val root = f.root.toAbsolutePath().normalize()
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { path ->
                check(path.toAbsolutePath().normalize().startsWith(root))
                Files.deleteIfExists(path)
            } }
        }
    }

    private class Fixture : TdTransport {
        val root = Files.createTempDirectory("proxy-share-engine-")
        val store = ConfigurationStore(root.resolve("tdlib.properties"), root.resolve("data"))
        val sessions = mutableListOf<NativeSession>()
        val current get() = sessions.last()
        init { Files.writeString(store.path, "api_id=12345\napi_hash=${"a".repeat(32)}\n") }
        override fun load() = NativeInfo("synthetic", "synthetic", "synthetic")
        override fun create(onAuthorization: (AuthorizationDetails) -> Unit, onConnection: (ConnectionPhase) -> Unit,
            onError: () -> Unit, onChatEvent: (ChatEvent) -> Unit): TdSession =
            NativeSession(onAuthorization).also { sessions += it }
    }

    private class NativeSession(private val authorization: (AuthorizationDetails) -> Unit) : SyntheticOpenKeySession {
        private val ready = AtomicBoolean()
        override val interactionGate = TdUserInteractionGate()
        override val settings = FakeSettings()
        var closed = false
            private set
        fun auth(phase: AuthorizationPhase) {
            ready.set(phase == AuthorizationPhase.READY)
            if (phase == AuthorizationPhase.CLOSED) closed = true
            authorization(AuthorizationDetails(phase))
        }
        fun requireEmail() = interactionGate.update(LoginEmailSuggestions(1, requiredPresent = true, requiredEpoch = 1))
        override fun authorizationReady() = ready.get()
        override fun setParameters(config: TdConfiguration, directories: DataDirectoryLease, result: (Int?) -> Unit) = result(null)
        override fun configureProxy(proxy: ProxyConfiguration?, result: (Int?) -> Unit) = result(null)
        override fun submitLogin(kind: LoginRequestKind, value: String, result: (LoginResult) -> Unit) = error("unused")
        override fun close() = auth(AuthorizationPhase.CLOSED)
    }

    private class FakeSettings : TdSettingsSession {
        val shares = mutableListOf<HeldShare>()
        private val row = SettingsProxy(1, "proxy.invalid", 1080, SettingsProxyType.WEB, false)
        override fun advanced(result: (SettingsResult<SettingsAdvancedData>) -> Unit) = result(SettingsResult(SettingsAdvancedData(null, listOf(row))))
        override fun advancedForProxyChecks(lease: SettingsProxyProbeLease, result: (SettingsResult<SettingsAdvancedData>) -> Unit) {
            check(lease.beginProbe())
            lease.finishProbe()
            result(SettingsResult(SettingsAdvancedData(null, listOf(row))))
        }
        override fun shareProxy(target: SettingsProxyShareTarget, isHttp: Boolean, lease: SettingsProxyProbeLease,
            result: (SettingsResult<List<String>>) -> Unit) {
            check(lease.beginProbe())
            val stage = checkNotNull(lease.beginStage())
            if (!lease.dispatch { shares += HeldShare(lease, stage, result) }) {
                lease.endStage(stage); lease.finishProbe(); result(SettingsResult(error = SettingsError.NOT_READY))
            }
        }
        override fun profile(result: (SettingsResult<SettingsProfile>) -> Unit) = unsupported(result)
        override fun saveProfile(edit: SettingsProfileEdit, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun notifications(result: (SettingsResult<Map<NotificationScope, NotificationPreferences>>) -> Unit) = unsupported(result)
        override fun saveNotifications(scope: NotificationScope, value: NotificationPreferences, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun privacy(result: (SettingsResult<SettingsPrivacyData>) -> Unit) = unsupported(result)
        override fun savePrivacy(key: PrivacyKey, base: PrivacyBase, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun setAccountTtl(days: Int, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun terminateSession(id: Long, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun unblock(sender: SettingsBlockedSender, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun optimizeStorage(result: (SettingsResult<SettingsStorage>) -> Unit) = unsupported(result)
        override fun addProxy(draft: SettingsProxyDraft, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun enableProxy(id: Int?, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun removeProxy(id: Int, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        private fun <T> unsupported(result: (SettingsResult<T>) -> Unit) = result(SettingsResult(error = SettingsError.UNSUPPORTED))
    }

    private class HeldShare(private val lease: SettingsProxyProbeLease, private val stage: SettingsProxyProbeLease.Stage,
        private val reply: (SettingsResult<List<String>>) -> Unit) {
        private val drained = AtomicBoolean()
        fun complete(value: SettingsResult<List<String>>) {
            if (drained.compareAndSet(false, true)) { lease.endStage(stage); lease.finishProbe() }
            reply(value)
        }
    }

    private companion object { const val PUBLIC_LINK = "https://synthetic.invalid/proxy" }
}
