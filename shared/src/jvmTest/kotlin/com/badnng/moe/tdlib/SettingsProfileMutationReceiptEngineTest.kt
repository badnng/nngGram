package com.badnng.moe.tdlib

import com.badnng.moe.chat.ChatEvent
import com.badnng.moe.settings.*
import com.badnng.moe.status.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.*

/** Real Engine/actor/controller/store composition with an intentionally ungated fake native session. */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsProfileMutationReceiptEngineTest {
    @Test fun workerResultIsStoredBeforeTheEngineActorPublishesTheAcceptedRevision() {
        runTest {
            withEngine { engine, transport ->
                val session = transport.sessions.single()
                val token = openExtras(engine)
                val revision = submitBirthdate(engine, token)
                val write = session.extras.writes.single()
                assertEquals(43L, write.userId)
                assertEquals(SettingsBirthdate(4, 5), write.desired)
                val before = engine.state.value.settings.profileExtras
                assertTrue(before.busy)
                assertTrue(engine.profileMutationReceipts.snapshot().isEmpty())

                onWorker { write.result(SettingsResult(Unit)) }
                assertEquals(before, engine.state.value.settings.profileExtras, "Actor has not drained the callback")
                val receipt = assertReceipt(engine, token, revision, SettingsResult(Unit))
                assertEquals(SettingsResult(Unit), engine.profileMutationReceipts.find(
                    receipt.key.scope, token, revision, SettingsProfileExtrasOperation.BIRTHDATE))

                runCurrent()
                val after = engine.state.value.settings.profileExtras
                assertEquals(token, after.token)
                assertEquals(revision, after.acceptedRevision)
                assertEquals(revision, after.completedRevision)
                assertFalse(after.busy)
                assertEquals(1, engine.profileMutationReceipts.snapshot().size)
            }
        }
    }

    @Test fun reopenedSameUserDoesNotConsumeThePreviousOwnersLateAcceptance() {
        runTest {
            withEngine { engine, transport ->
                val session = transport.sessions.single()
                val original = openExtras(engine)
                val originalRevision = submitBirthdate(engine, original)
                val oldWrite = session.extras.writes.single()
                assertTrue(engine.closeSettingsProfileExtras(original)); runCurrent()
                val replacement = openExtras(engine)
                assertEquals(original.generation, replacement.generation)
                assertEquals(original.userId, replacement.userId)
                assertNotEquals(original.owner, replacement.owner)
                val before = engine.state.value.settings.profileExtras
                assertTrue(before.busy)
                assertEquals(0L, before.acceptedRevision)

                onWorker { oldWrite.result(SettingsResult(Unit)) }
                val oldReceipt = assertReceipt(engine, original, originalRevision, SettingsResult(Unit))
                assertEquals(before, engine.state.value.settings.profileExtras)
                runCurrent()
                val after = engine.state.value.settings.profileExtras
                assertEquals(replacement, after.token)
                assertEquals(0L, after.acceptedRevision)
                assertFalse(after.busy)
                assertFalse(after.loading)

                val nextRevision = submitBirthdate(engine, replacement, SettingsBirthdate(5, 5))
                assertTrue(nextRevision > originalRevision)
                val nextWrite = session.extras.writes.last()
                onWorker { nextWrite.result(SettingsResult(error = SettingsError.FORBIDDEN)) }
                val all = engine.profileMutationReceipts.snapshot()
                assertEquals(2, all.size)
                val nextReceipt = all.single { it.key.token == replacement }
                assertSame(oldReceipt.key.scope, nextReceipt.key.scope)
                assertEquals(nextRevision, nextReceipt.key.revision)
                assertEquals(SettingsProfileExtrasOperation.BIRTHDATE, nextReceipt.key.operation)
                assertEquals(SettingsResult<Unit>(error = SettingsError.FORBIDDEN), nextReceipt.result)
                runCurrent()
                assertEquals(0L, engine.state.value.settings.profileExtras.acceptedRevision)
                assertEquals(nextRevision, engine.state.value.settings.profileExtras.failedRevision)
            }
        }
    }

    @Test fun stopDropsQueuedSettingsWorkButRetainsTheFirstActualCallbackOnce() {
        runTest {
            withEngine { engine, transport ->
                val session = transport.sessions.single()
                session.closeImmediately = false
                val token = openExtras(engine)
                val revision = submitBirthdate(engine, token)
                val write = session.extras.writes.single()
                val stopping = async { engine.stop() }
                runCurrent()
                assertEquals(1, session.closeCalls)
                assertFalse(stopping.isCompleted, "Closed has not been confirmed")
                val stoppedSettings = engine.state.value.settings
                assertTrue(engine.profileMutationReceipts.snapshot().isEmpty())

                onWorker {
                    write.result(SettingsResult(Unit))
                    write.result(SettingsResult(error = SettingsError.CONFLICT))
                }
                assertReceipt(engine, token, revision, SettingsResult(Unit))
                assertEquals(stoppedSettings, engine.state.value.settings)
                runCurrent() // The old SettingsWork is explicitly discarded while closeSent is true.
                assertEquals(stoppedSettings, engine.state.value.settings)
                assertEquals(1, engine.profileMutationReceipts.snapshot().size)
                session.authorize(AuthorizationPhase.CLOSED); runCurrent()
                assertTrue(stopping.await())
                assertReceipt(engine, token, revision, SettingsResult(Unit))
            }
        }
    }

    @Test fun sameEngineKeepsDistinctScopesAndResultsAcrossNativeGenerations() {
        runTest {
            withEngine { engine, transport ->
                val oldSession = transport.sessions.single()
                val oldToken = openExtras(engine)
                val oldRevision = submitBirthdate(engine, oldToken)
                val oldWrite = oldSession.extras.writes.single()
                val stopping = async { engine.stop() }; runCurrent()
                assertTrue(stopping.await())
                assertTrue(engine.profileMutationReceipts.snapshot().isEmpty())

                assertTrue(engine.start()); runCurrent()
                val newSession = transport.sessions.last()
                assertNotSame(oldSession, newSession)
                readyAndLoadProfile(engine, newSession)
                val newToken = openExtras(engine)
                val newRevision = submitBirthdate(engine, newToken)
                val newWrite = newSession.extras.writes.single()
                assertTrue(newToken.generation > oldToken.generation)
                assertEquals(oldToken.userId, newToken.userId)
                assertEquals(oldRevision, newRevision)

                onWorker { newWrite.result(SettingsResult(error = SettingsError.FORBIDDEN)) }
                onWorker { oldWrite.result(SettingsResult(Unit)) }
                val receipts = engine.profileMutationReceipts.snapshot()
                assertEquals(2, receipts.size)
                val oldReceipt = receipts.single { it.key.token == oldToken }
                val newReceipt = receipts.single { it.key.token == newToken }
                assertNotSame(oldReceipt.key.scope, newReceipt.key.scope)
                assertEquals(oldRevision, oldReceipt.key.revision)
                assertEquals(newRevision, newReceipt.key.revision)
                assertEquals(SettingsProfileExtrasOperation.BIRTHDATE, oldReceipt.key.operation)
                assertEquals(SettingsProfileExtrasOperation.BIRTHDATE, newReceipt.key.operation)
                assertEquals(SettingsResult(Unit), oldReceipt.result)
                assertEquals(SettingsResult<Unit>(error = SettingsError.FORBIDDEN), newReceipt.result)
                assertNull(engine.profileMutationReceipts.find(oldReceipt.key.scope, newToken, newRevision,
                    SettingsProfileExtrasOperation.BIRTHDATE))
                assertNull(engine.profileMutationReceipts.find(newReceipt.key.scope, oldToken, oldRevision,
                    SettingsProfileExtrasOperation.BIRTHDATE))
                assertTrue(engine.state.value.settings.profileExtras.busy)

                runCurrent() // The old generation's callback work cannot settle the new owner.
                val after = engine.state.value.settings.profileExtras
                assertEquals(newToken, after.token)
                assertEquals(0L, after.acceptedRevision)
                assertEquals(newRevision, after.failedRevision)
                assertEquals(SettingsError.FORBIDDEN, after.error)
                assertEquals(2, engine.profileMutationReceipts.snapshot().size)
            }
        }
    }

    @Test fun stoppingAndConfirmedClosedNeverInventAReceiptWithoutCallback() {
        runTest {
            withEngine { engine, transport ->
                val session = transport.sessions.single()
                session.closeImmediately = false
                val token = openExtras(engine)
                submitBirthdate(engine, token)
                assertEquals(1, session.extras.writes.size)
                val stopping = async { engine.stop() }; runCurrent()
                assertEquals(1, session.closeCalls)
                assertTrue(engine.profileMutationReceipts.snapshot().isEmpty())
                assertFalse(stopping.isCompleted)
                session.authorize(AuthorizationPhase.CLOSED); runCurrent()
                assertTrue(stopping.await())
                assertTrue(engine.profileMutationReceipts.snapshot().isEmpty())
            }
        }
    }

    private suspend fun TestScope.withEngine(block: suspend TestScope.(TdEngine, FakeTransport) -> Unit) {
        val fixture = Fixture()
        val engine = syntheticOpenEngine(fixture.store, fixture.transport, StandardTestDispatcher(testScheduler))
        try {
            assertTrue(engine.start()); runCurrent()
            readyAndLoadProfile(engine, fixture.transport.sessions.single())
            block(engine, fixture.transport)
        } finally {
            fixture.transport.sessions.lastOrNull()?.let { session ->
                session.closeImmediately = true
                if (session.closeCalls > 0 && !session.closed) session.authorize(AuthorizationPhase.CLOSED)
            }
            val close = async { engine.close() }
            runCurrent()
            val closed = close.await()
            if (closed) fixture.delete()
            assertTrue(closed)
        }
    }

    private fun TestScope.readyAndLoadProfile(engine: TdEngine, session: FakeSession) {
        session.authorize(AuthorizationPhase.WAITING_PARAMETERS); runCurrent()
        session.authorize(AuthorizationPhase.READY); runCurrent()
        assertTrue(engine.state.value.settings.enabled)
        assertTrue(engine.loadSettings(engine.state.value.settings.token, SettingsSection.PROFILE)); runCurrent()
        assertEquals(43L, engine.state.value.settings.profile?.userId)
    }

    private fun TestScope.openExtras(engine: TdEngine): SettingsProfileExtrasToken {
        assertTrue(engine.openSettingsProfileExtras(engine.state.value.settings.token, 43)); runCurrent()
        val page = engine.state.value.settings.profileExtras
        assertEquals(43L, page.token.userId)
        assertEquals(91L, page.data?.photoId)
        assertFalse(page.loading)
        return page.token
    }

    private fun TestScope.submitBirthdate(engine: TdEngine, token: SettingsProfileExtrasToken,
        desired: SettingsBirthdate = SettingsBirthdate(4, 5),
    ): Long {
        assertTrue(engine.saveSettingsProfileBirthdate(token, null, desired)); runCurrent()
        val page = engine.state.value.settings.profileExtras
        assertEquals(token, page.token)
        assertTrue(page.busy)
        assertEquals(SettingsProfileExtrasOperation.BIRTHDATE, page.operation)
        assertTrue(page.startedRevision > 0)
        return page.startedRevision
    }

    private fun assertReceipt(engine: TdEngine, token: SettingsProfileExtrasToken, revision: Long,
        result: SettingsResult<Unit>,
    ): SettingsProfileMutationReceipts.Receipt {
        val receipt = engine.profileMutationReceipts.snapshot().single()
        assertEquals(token, receipt.key.token)
        assertEquals(revision, receipt.key.revision)
        assertEquals(SettingsProfileExtrasOperation.BIRTHDATE, receipt.key.operation)
        assertEquals(result, receipt.result)
        return receipt
    }

    private fun onWorker(action: () -> Unit) {
        val completed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val worker = thread(isDaemon = true, name = "profile-receipt-engine-worker") {
            try { action() } catch (error: Throwable) { failure.set(error) } finally { completed.countDown() }
        }
        assertTrue(completed.await(2, TimeUnit.SECONDS))
        worker.join(2_000)
        assertFalse(worker.isAlive)
        failure.get()?.let { throw it }
    }

    private class Fixture {
        val root: Path = Files.createTempDirectory("nnggram-profile-receipt-engine-").toRealPath()
        val store = ConfigurationStore(root.resolve("tdlib.properties"), root.resolve("data"))
        val transport = FakeTransport()
        init { Files.writeString(store.path, "api_id=12345\napi_hash=${"a".repeat(32)}\n") }

        fun delete() {
            val parent = Path.of(System.getProperty("java.io.tmpdir")).toRealPath()
            val checked = root.toRealPath()
            check(checked.parent == parent && checked.fileName.toString().startsWith("nnggram-profile-receipt-engine-"))
            Files.walk(checked).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { path ->
                    check(path.toAbsolutePath().normalize().startsWith(checked))
                    Files.deleteIfExists(path)
                }
            }
        }
    }

    private class FakeTransport : TdTransport {
        val sessions = mutableListOf<FakeSession>()
        override fun load() = NativeInfo("synthetic", "synthetic", "synthetic")
        override fun create(onAuthorization: (AuthorizationDetails) -> Unit, onConnection: (ConnectionPhase) -> Unit,
            onError: () -> Unit, onChatEvent: (ChatEvent) -> Unit,
        ): TdSession = FakeSession(onAuthorization).also(sessions::add)
    }

    private class FakeSession(private val authorization: (AuthorizationDetails) -> Unit) : SyntheticOpenKeySession {
        val extras = FakeExtras()
        override val settings: TdSettingsSession = FakeSettings(extras)
        var closeImmediately = true
        var closeCalls = 0
        var closed = false
        fun authorize(phase: AuthorizationPhase) {
            if (phase == AuthorizationPhase.CLOSED) closed = true
            authorization(AuthorizationDetails(phase))
        }
        override fun setParameters(config: TdConfiguration, directories: DataDirectoryLease, result: (Int?) -> Unit) { result(null) }
        override fun configureProxy(proxy: ProxyConfiguration?, result: (Int?) -> Unit) { result(null) }
        override fun submitLogin(kind: LoginRequestKind, value: String, result: (LoginResult) -> Unit) = error("No login in receipt tests")
        override fun close() {
            closeCalls++
            if (closeImmediately) authorize(AuthorizationPhase.CLOSED)
        }
    }

    /** No fake current check, callback deduplication, or synthetic completion on close. */
    private class FakeExtras : TdSettingsProfileExtrasSession {
        data class Write(val userId: Long, val expected: SettingsBirthdate?, val desired: SettingsBirthdate?,
            val current: () -> Boolean, val result: (SettingsResult<Unit>) -> Unit)
        val writes = mutableListOf<Write>()
        /** This fake callback is the final native write reply, including late and duplicate replies. */
        private fun nativeWriteResult(result: (SettingsResult<Unit>) -> Unit): (SettingsResult<Unit>) -> Unit = { response ->
            val receipt = when {
                response.value != null && response.error == null -> SettingsSpecialWriteReceipt(SettingsSpecialWriteOutcome.ACCEPTED)
                response.error != null -> SettingsSpecialWriteReceipt(SettingsSpecialWriteOutcome.REJECTED, response.error)
                else -> SettingsSpecialWriteReceipt(SettingsSpecialWriteOutcome.INDETERMINATE)
            }
            SettingsSpecialMutationContext.withReceipt(receipt) { result(response) }
        }
        override fun load(userId: Long, isCurrent: () -> Boolean, result: (SettingsResult<SettingsProfileExtrasData>) -> Unit) {
            result(SettingsResult(SettingsProfileExtrasData(userId, 91, null, SettingsCalendarDate(27, 10, 2026))))
        }
        override fun setBirthdate(userId: Long, expected: SettingsBirthdate?, desired: SettingsBirthdate?,
            isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit,
        ) { writes += Write(userId, expected, desired, isCurrent, nativeWriteResult(result)) }
        override fun uploadPhoto(userId: Long, expectedPhotoId: Long?, photo: SettingsPreparedProfilePhoto,
            isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit,
        ) { result(SettingsResult(error = SettingsError.UNSUPPORTED)) }
        override fun removePhoto(userId: Long, photoId: Long, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) {
            result(SettingsResult(error = SettingsError.UNSUPPORTED))
        }
    }

    private class FakeSettings(override val profileExtras: FakeExtras) : TdSettingsSession {
        override fun profile(result: (SettingsResult<SettingsProfile>) -> Unit) {
            result(SettingsResult(SettingsProfile(43, "Synthetic", "", "", "", "")))
        }
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
        private fun <T> unsupported(result: (SettingsResult<T>) -> Unit) { result(SettingsResult(error = SettingsError.UNSUPPORTED)) }
    }
}
