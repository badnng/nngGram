package com.badnng.moe.tdlib

import com.badnng.moe.chat.ChatEvent
import com.badnng.moe.chat.ownedAttachmentLease
import com.badnng.moe.settings.*
import com.badnng.moe.status.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsProfileExtrasEngineTest {
    @Test fun pageCanLeaveAfterQueuedOpenBeforeAnyProfileExtrasTokenExists() = runTest {
        val f = Fixture()
        val engine = syntheticOpenEngine(f.store, f.transport, StandardTestDispatcher(testScheduler))
        engine.start(); runCurrent()
        f.transport.auth(AuthorizationPhase.WAITING_PARAMETERS); runCurrent()
        f.transport.auth(AuthorizationPhase.READY); runCurrent()
        engine.loadSettings(engine.state.value.settings.token, SettingsSection.PROFILE); runCurrent()
        val alive = java.util.concurrent.atomic.AtomicBoolean(true)
        assertTrue(engine.openSettingsProfileExtras(engine.state.value.settings.token, 43, alive::get))
        alive.set(false) // UI disposal is synchronous; the actor has not processed Open yet.
        runCurrent()
        assertEquals(0, f.extras.reads)
        assertEquals(0L, engine.state.value.settings.profileExtras.token.userId)
        assertTrue(engine.close())
    }
    @Test fun immediateRejectedUploadReleasesOnlyItsBorrowedHandle() = runTest {
        val f = Fixture()
        val engine = syntheticOpenEngine(f.store, f.transport, StandardTestDispatcher(testScheduler))
        var cleaned = 0
        val photo = photo { cleaned++ }
        assertFalse(engine.uploadSettingsProfilePhoto(SettingsProfileExtrasToken(1, 1, 43), null, photo))
        assertEquals(0, cleaned)
        photo.lease.release()
        assertEquals(1, cleaned)
        assertTrue(engine.close())
    }
    @Test fun actorRejectsAnUploadWhoseOwnerWasClosedAfterSynchronousRetain() = runTest {
        val f = Fixture()
        val engine = syntheticOpenEngine(f.store, f.transport, StandardTestDispatcher(testScheduler))
        engine.start(); runCurrent()
        f.transport.auth(AuthorizationPhase.WAITING_PARAMETERS); runCurrent()
        f.transport.auth(AuthorizationPhase.READY); runCurrent()
        engine.loadSettings(engine.state.value.settings.token, SettingsSection.PROFILE); runCurrent()
        engine.openSettingsProfileExtras(engine.state.value.settings.token, 43); runCurrent()
        val token = engine.state.value.settings.profileExtras.token
        assertEquals(43L, token.userId)
        var cleaned = 0
        val photo = photo { cleaned++ }
        assertTrue(engine.closeSettingsProfileExtras(token))
        assertTrue(engine.uploadSettingsProfilePhoto(token, 91, photo))
        photo.lease.release()
        assertEquals(0, cleaned, "Retain must happen synchronously before the UI can dispose.")
        runCurrent()
        assertEquals(1, cleaned)
        assertTrue(f.extras.uploads.isEmpty(), "Actor validation must reject the stale owner before any native upload.")
        assertTrue(engine.close())
    }
    @Test fun closingDoesNotDeleteNativeInputUntilConfirmedClosedAndLateCallbackIsIdempotent() = runTest {
        val f = Fixture()
        val engine = syntheticOpenEngine(f.store, f.transport, StandardTestDispatcher(testScheduler))
        engine.start(); runCurrent()
        f.transport.auth(AuthorizationPhase.WAITING_PARAMETERS); runCurrent()
        f.transport.auth(AuthorizationPhase.READY); runCurrent()
        engine.loadSettings(engine.state.value.settings.token, SettingsSection.PROFILE); runCurrent()
        engine.openSettingsProfileExtras(engine.state.value.settings.token, 43); runCurrent()
        val token = engine.state.value.settings.profileExtras.token
        var cleaned = 0
        val photo = photo { cleaned++ }
        assertTrue(engine.uploadSettingsProfilePhoto(token, 91, photo)); runCurrent()
        assertEquals(1, f.extras.uploads.size)
        photo.lease.release()
        f.transport.auth(AuthorizationPhase.CLOSING); runCurrent()
        assertEquals(0, cleaned, "Deactivating UI/controllers does not mean TDLib stopped reading the JPEG.")
        f.transport.auth(AuthorizationPhase.CLOSED); runCurrent()
        assertEquals(1, cleaned)
        val closed = engine.state.value.settings
        f.extras.uploads.single()(SettingsResult(Unit)); runCurrent()
        assertEquals(1, cleaned)
        assertEquals(closed, engine.state.value.settings)
        assertTrue(engine.close())
    }
    @Test fun nativeFailureReleasesUploadAndNeverPublishesAcceptedSuccess() = runTest {
        val f = Fixture()
        val engine = syntheticOpenEngine(f.store, f.transport, StandardTestDispatcher(testScheduler))
        engine.start(); runCurrent()
        f.transport.auth(AuthorizationPhase.WAITING_PARAMETERS); runCurrent()
        f.transport.auth(AuthorizationPhase.READY); runCurrent()
        engine.loadSettings(engine.state.value.settings.token, SettingsSection.PROFILE); runCurrent()
        engine.openSettingsProfileExtras(engine.state.value.settings.token, 43); runCurrent()
        var cleaned = 0
        val photo = photo { cleaned++ }
        assertTrue(engine.uploadSettingsProfilePhoto(engine.state.value.settings.profileExtras.token, 91, photo)); runCurrent()
        photo.lease.release()
        f.extras.uploads.single()(SettingsResult(error = SettingsError.FORBIDDEN)); runCurrent()
        assertEquals(1, cleaned)
        assertEquals(0L, engine.state.value.settings.profileExtras.acceptedRevision)
        assertEquals(SettingsError.FORBIDDEN, engine.state.value.settings.profileExtras.error)
        assertTrue(engine.close())
    }

    private class Fixture {
        private val root = Files.createTempDirectory("profile-extras-engine-")
        val store = ConfigurationStore(root.resolve("tdlib.properties"), root.resolve("data"))
        val extras = Extras()
        val transport = Transport(Settings(extras))
        init { Files.writeString(store.path, "api_id=12345\napi_hash=${"a".repeat(32)}\n") }
    }
    private class Transport(private val settings: Settings) : TdTransport {
        lateinit var authorization: (AuthorizationDetails) -> Unit
        fun auth(value: AuthorizationPhase) { authorization(AuthorizationDetails(value)) }
        override fun load() = NativeInfo("synthetic", "synthetic", "synthetic")
        override fun create(onAuthorization: (AuthorizationDetails) -> Unit, onConnection: (ConnectionPhase) -> Unit,
            onError: () -> Unit, onChatEvent: (ChatEvent) -> Unit,
        ): TdSession {
            authorization = onAuthorization
            return object : SyntheticOpenKeySession {
                override val settings: TdSettingsSession = this@Transport.settings
                override fun setParameters(config: TdConfiguration, directories: DataDirectoryLease, result: (Int?) -> Unit) { result(null) }
                override fun configureProxy(proxy: ProxyConfiguration?, result: (Int?) -> Unit) { result(null) }
                override fun submitLogin(kind: LoginRequestKind, value: String, result: (LoginResult) -> Unit) = error("No login")
                override fun close() { auth(AuthorizationPhase.CLOSED) }
            }
        }
    }
    private class Extras : TdSettingsProfileExtrasSession {
        val uploads = mutableListOf<(SettingsResult<Unit>) -> Unit>()
        var reads = 0
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
            reads++
            result(SettingsResult(SettingsProfileExtrasData(userId, 91, null, SettingsCalendarDate(25, 10, 2026))))
        }
        override fun uploadPhoto(userId: Long, expectedPhotoId: Long?, photo: SettingsPreparedProfilePhoto, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) { uploads += nativeWriteResult(result) }
        override fun setBirthdate(userId: Long, expected: SettingsBirthdate?, desired: SettingsBirthdate?, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) { nativeWriteResult(result)(SettingsResult(Unit)) }
        override fun removePhoto(userId: Long, photoId: Long, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) { nativeWriteResult(result)(SettingsResult(Unit)) }
    }
    private class Settings(override val profileExtras: Extras) : TdSettingsSession {
        override fun profile(result: (SettingsResult<SettingsProfile>) -> Unit) { result(SettingsResult(SettingsProfile(43, "Synthetic", "", "", "", ""))) }
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
    private fun photo(cleaned: () -> Unit) = SettingsPreparedProfilePhoto("synthetic.jpg", 512, 32, "00".repeat(32), ownedAttachmentLease(cleaned))
}
