package com.badnng.moe.tdlib

import com.badnng.moe.chat.ChatEvent
import com.badnng.moe.settings.*
import com.badnng.moe.status.*
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

/** Engine admission/owner retirement only. Synthetic OPEN is not a password-lock fixture. */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsBlockedPickerEngineTest {
    @Test fun actualSecurityActionsRespectTheAccountGateAndRetiredWritesKeepTheirRealTerminal() = runTest {
        for (mode in listOf("gate", "accepted", "rejected")) {
            val f = Transport()
            val engine = syntheticOpenEngine(f.store, f, StandardTestDispatcher(testScheduler))
            try {
                assertTrue(engine.start()); runCurrent()
                f.current.auth(AuthorizationPhase.WAITING_PARAMETERS); runCurrent()
                f.current.auth(AuthorizationPhase.READY); runCurrent()
                assertTrue(engine.openSettingsSecurity(engine.state.value.settings.token, SettingsSecuritySection.BLOCKED))
                runCurrent()
                val security = engine.state.value.settings.security.token
                assertTrue(engine.openSettingsBlockedPicker(security)); runCurrent()
                val picker = assertNotNull(engine.state.value.settings.security.blockedPicker).token
                assertEquals(listOf(51L), engine.state.value.settings.security.blockedPicker!!.entries.map { it.userId })
                val forms = mutableListOf<SettingsResult<Unit>>()
                if (mode == "gate") {
                    f.current.interactionGate.update(LoginEmailSuggestions(1, requiredPresent = true, requiredEpoch = 1))
                    assertFalse(engine.blockSettingsPickerUser(picker, 51, forms::add))
                    runCurrent()
                    assertTrue(f.current.settings.pending.isEmpty(), "Public Engine admission must precede physical dispatch.")
                    assertNull(forms.single().value)
                } else {
                    assertTrue(engine.blockSettingsPickerUser(picker, 51, forms::add)); runCurrent()
                    val pending = f.current.settings.pending.single()
                    assertTrue(pending.exactScope)
                    assertTrue(engine.closeSettingsSecurity(security)); runCurrent()
                    val closed = engine.state.value.settings.security
                    assertNull(closed.section)
                    pending.reply(if (mode == "accepted") TdApi.Ok() else TdApi.Error(403, "synthetic forbidden"))
                    runCurrent()
                    assertEquals(if (mode == "accepted") SettingsSpecialWriteOutcome.ACCEPTED else SettingsSpecialWriteOutcome.REJECTED,
                        f.current.settings.receipts.single()?.outcome)
                    assertEquals(closed, engine.state.value.settings.security)
                    assertTrue(forms.isEmpty(), "A retired parent form never consumes its old native completion.")
                    assertEquals(1, f.current.settings.pageReads, "The old parent cannot start its accepted-write readback.")
                    assertFalse(engine.blockSettingsPickerUser(picker, 51) { })
                    assertEquals(1, f.current.settings.pending.size)
                }
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
    }

    private class Transport : TdTransport {
        val root = Files.createTempDirectory("blocked-picker-engine-")
        val store = ConfigurationStore(root.resolve("tdlib.properties"), root.resolve("data"))
        val sessions = mutableListOf<Session>()
        val current get() = sessions.last()
        init { Files.writeString(store.path, "api_id=12345\napi_hash=${"a".repeat(32)}\n") }
        override fun load() = NativeInfo("synthetic", "synthetic", "synthetic")
        override fun create(onAuthorization: (AuthorizationDetails) -> Unit, onConnection: (ConnectionPhase) -> Unit,
            onError: () -> Unit, onChatEvent: (ChatEvent) -> Unit): TdSession = Session(onAuthorization).also { sessions += it }
    }
    private class Session(private val authorization: (AuthorizationDetails) -> Unit) : SyntheticOpenKeySession {
        private val ready = AtomicBoolean()
        override val interactionGate = TdUserInteractionGate()
        override val settings = Settings(interactionGate)
        var closed = false
        fun auth(phase: AuthorizationPhase) {
            ready.set(phase == AuthorizationPhase.READY)
            if (phase == AuthorizationPhase.CLOSED) closed = true
            authorization(AuthorizationDetails(phase))
        }
        override fun authorizationReady() = ready.get()
        override fun setParameters(config: TdConfiguration, directories: DataDirectoryLease, result: (Int?) -> Unit) = result(null)
        override fun configureProxy(proxy: ProxyConfiguration?, result: (Int?) -> Unit) = result(null)
        override fun submitLogin(kind: LoginRequestKind, value: String, result: (LoginResult) -> Unit) = error("unused")
        override fun close() = auth(AuthorizationPhase.CLOSED)
    }
    private data class Pending(val reply: (TdApi.Object) -> Unit, val exactScope: Boolean)
    private class Settings(gate: TdUserInteractionGate) : TdSettingsSession {
        val pending = mutableListOf<Pending>()
        val receipts = mutableListOf<SettingsSpecialWriteReceipt?>()
        var pageReads = 0
        private val send = gate.guard { command, reply, _ ->
            assertIs<TdApi.SetMessageSenderBlockList>(command)
            pending += Pending(reply, SettingsSpecialMutationContext.marks(command))
        }
        override fun blockedPage(offset: Int, isCurrent: () -> Boolean, result: (SettingsResult<SettingsBlockedPage>) -> Unit) {
            pageReads++
            result(SettingsResult(SettingsBlockedPage(emptyList(), 0, 0, true)))
        }
        override fun blockedCandidates(query: SettingsBlockedPeerQuery, isCurrent: () -> Boolean,
            result: (SettingsResult<SettingsBlockedCandidates>) -> Unit) {
            assertTrue(isCurrent())
            result(SettingsResult(SettingsBlockedCandidates(listOf(SettingsBlockedCandidate(51, "Native user", false)), 1, true)))
        }
        override fun blockSecurity(userId: Long, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) {
            assertTrue(isCurrent()); assertEquals(51L, userId)
            SettingsSpecialMutationContext.write(TdApi.SetMessageSenderBlockList(TdApi.MessageSenderUser(userId), TdApi.BlockListMain()),
                send, { response ->
                    receipts += SettingsSpecialMutationContext.receipt()
                    result(if (response is TdApi.Ok) SettingsResult(Unit) else SettingsResult(error = SettingsError.FORBIDDEN))
                }, { result(SettingsResult(error = SettingsError.RESULT_UNKNOWN)) })
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
        override fun advanced(result: (SettingsResult<SettingsAdvancedData>) -> Unit) = unsupported(result)
        override fun optimizeStorage(result: (SettingsResult<SettingsStorage>) -> Unit) = unsupported(result)
        override fun addProxy(draft: SettingsProxyDraft, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun enableProxy(id: Int?, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun removeProxy(id: Int, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        private fun <T> unsupported(result: (SettingsResult<T>) -> Unit) = result(SettingsResult(error = SettingsError.UNSUPPORTED))
    }
}
