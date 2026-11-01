package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import kotlin.test.*

class SettingsControllerTest {
    @Test
    fun staleGenerationAndRevisionCannotReadOrWriteAndRetirementDropsQueuedCallbacks() {
        val fixture = Fixture()
        val token = fixture.state.token
        fixture.controller.load(token.copy(generation = token.generation + 1), SettingsSection.PROFILE)
        fixture.controller.load(token.copy(revision = token.revision + 1), SettingsSection.PROFILE)
        fixture.controller.saveProfile(token, SettingsProfileEdit(ProfileField.NAME, "unloaded"))
        assertTrue(fixture.session.profiles.isEmpty())
        assertTrue(fixture.session.profileWrites.isEmpty())

        fixture.controller.load(token, SettingsSection.PROFILE)
        fixture.session.profiles.single()(SettingsResult(profile("Late Native Name")))
        assertNull(fixture.state.profile)
        fixture.controller.deactivate()
        val retired = fixture.state
        assertFalse(retired.enabled)
        assertNotEquals(token, retired.token)
        fixture.flush()
        assertEquals(retired, fixture.state)
        assertFalse(fixture.controller.accepts(token))
        fixture.controller.start()
        fixture.controller.load(token, SettingsSection.PROFILE)
        assertEquals(1, fixture.session.profiles.size)
        assertNull(fixture.state.profile)
    }

    @Test
    fun aNewerReadSupersedesAnOlderReadWithoutAllowingItsLateOrDuplicateResult() {
        val fixture = Fixture()
        fixture.controller.load(fixture.state.token, SettingsSection.PROFILE)
        fixture.controller.load(fixture.state.token, SettingsSection.PROFILE)
        val older = fixture.session.profiles[0]
        val current = fixture.session.profiles[1]
        older(SettingsResult(profile("Older")))
        fixture.flush()
        assertNull(fixture.state.profile)
        assertTrue(SettingsSection.PROFILE in fixture.state.loading)

        current(SettingsResult(profile("Current")))
        current(SettingsResult(profile("Duplicate")))
        older(SettingsResult(error = SettingsError.FORBIDDEN))
        fixture.flush()
        assertEquals("Current", fixture.state.profile?.firstName)
        assertFalse(SettingsSection.PROFILE in fixture.state.loading)
        assertTrue(SettingsSection.PROFILE in fixture.state.loaded)
        assertTrue(fixture.state.errors.isEmpty())
    }

    @Test
    fun aSaveKeepsNativeValuesAndGatesFurtherActionsUntilTheAcceptedWriteIsReadBack() {
        val fixture = Fixture()
        fixture.loadProfile("Original")
        val token = fixture.state.token
        fixture.controller.saveProfile(token, SettingsProfileEdit(ProfileField.NAME, "Requested", "Surname"))
        val save = fixture.session.profileWrites.single()
        assertEquals("Requested", save.edit.value)
        assertEquals("Surname", save.edit.secondary)
        assertEquals("Original", fixture.state.profile?.firstName)
        assertTrue(SettingsSection.PROFILE in fixture.state.saving)
        assertTrue(fixture.state.completedSaveRevision.isEmpty())
        fixture.controller.load(token, SettingsSection.PROFILE)
        fixture.controller.saveProfile(token, SettingsProfileEdit(ProfileField.NAME, "Second"))
        assertEquals(1, fixture.session.profiles.size)
        assertEquals(1, fixture.session.profileWrites.size)

        save.result(SettingsResult(Unit))
        save.result(SettingsResult(error = SettingsError.FORBIDDEN))
        assertEquals(1, fixture.session.profiles.size)
        fixture.flush()
        assertEquals(2, fixture.session.profiles.size)
        assertEquals("Original", fixture.state.profile?.firstName)
        assertTrue(SettingsSection.PROFILE in fixture.state.saving)
        assertTrue(fixture.state.completedSaveRevision.isEmpty())

        fixture.session.profiles.last()(SettingsResult(profile("Canonical Native Name")))
        fixture.flush()
        assertEquals("Canonical Native Name", fixture.state.profile?.firstName)
        assertFalse(SettingsSection.PROFILE in fixture.state.saving)
        assertNotNull(fixture.state.completedSaveRevision[SettingsSection.PROFILE])
        assertTrue(fixture.state.errors.isEmpty())
    }

    @Test
    fun failedWritesAndReadbacksRetainValuesUntilAnAcceptedWriteIsReadBack() {
        val fixture = Fixture()
        fixture.loadProfile("Retained")
        val token = fixture.state.token
        fixture.controller.saveProfile(token, SettingsProfileEdit(ProfileField.BIO, "New bio"))
        fixture.session.profileWrites.last().result(SettingsResult(error = SettingsError.RATE_LIMITED))
        fixture.flush()
        assertEquals(1, fixture.session.profiles.size)
        assertEquals("Retained", fixture.state.profile?.firstName)
        assertEquals(SettingsError.RATE_LIMITED, fixture.state.errors[SettingsSection.PROFILE])
        assertTrue(fixture.state.saving.isEmpty())
        assertTrue(fixture.state.completedSaveRevision.isEmpty())

        fixture.controller.saveProfile(token, SettingsProfileEdit(ProfileField.BIO, "Retry bio"))
        val acceptedRevision = fixture.state.startedSaveRevision.getValue(SettingsSection.PROFILE)
        fixture.session.profileWrites.last().result(SettingsResult(Unit))
        fixture.flush()
        fixture.session.profiles.last()(SettingsResult(error = SettingsError.REQUEST_FAILED))
        fixture.flush()
        assertEquals("Retained", fixture.state.profile?.firstName)
        assertEquals(SettingsError.REQUEST_FAILED, fixture.state.errors[SettingsSection.PROFILE])
        assertTrue(fixture.state.completedSaveRevision.isEmpty())

        fixture.controller.load(token, SettingsSection.PROFILE)
        fixture.session.profiles.last()(SettingsResult(profile("Read Later")))
        fixture.flush()
        assertEquals("Read Later", fixture.state.profile?.firstName)
        assertTrue(fixture.state.errors.isEmpty())
        assertEquals(acceptedRevision, fixture.state.completedSaveRevision[SettingsSection.PROFILE])
    }

    @Test
    fun currentAndUnknownDevicesCannotBeTerminatedButKnownSignedIdentitiesCan() {
        val fixture = Fixture()
        val current = session(-10, true)
        val other = session(-20, false)
        fixture.controller.load(fixture.state.token, SettingsSection.PRIVACY)
        fixture.session.privacyReads.single()(SettingsResult(SettingsPrivacyData(
            emptyMap(), emptyMap(), 365, listOf(current, other), emptyList())))
        fixture.flush()
        val token = fixture.state.token
        fixture.controller.terminateSession(token, current.id)
        fixture.controller.terminateSession(token, -999)
        assertTrue(fixture.session.terminations.isEmpty())
        fixture.controller.terminateSession(token.copy(generation = 999), other.id)
        assertTrue(fixture.session.terminations.isEmpty())

        fixture.controller.terminateSession(token, other.id)
        assertEquals(other.id, fixture.session.terminations.single().id)
        assertEquals(listOf(current, other), fixture.state.sessions)
        fixture.session.terminations.single().result(SettingsResult(Unit))
        fixture.flush()
        assertEquals(2, fixture.session.privacyReads.size)
        assertEquals(listOf(current, other), fixture.state.sessions)
        fixture.session.privacyReads.last()(SettingsResult(SettingsPrivacyData(
            emptyMap(), emptyMap(), 365, listOf(current), emptyList())))
        fixture.flush()
        assertEquals(listOf(current), fixture.state.sessions)
        assertNotNull(fixture.state.completedSaveRevision[SettingsSection.PRIVACY])
    }

    @Test
    fun sectionRequestsStayIndependentAndRetiringAnInFlightWriteNeverStartsItsRefresh() {
        val fixture = Fixture()
        fixture.controller.load(fixture.state.token, SettingsSection.PROFILE)
        fixture.controller.load(fixture.state.token, SettingsSection.NOTIFICATIONS)
        val notification = NotificationPreferences(muteFor = 120, soundId = 17)
        fixture.session.notificationReads.single()(SettingsResult(mapOf(NotificationScope.PRIVATE_CHATS to notification)))
        fixture.flush()
        assertTrue(SettingsSection.PROFILE in fixture.state.loading)
        assertFalse(SettingsSection.NOTIFICATIONS in fixture.state.loading)
        assertEquals(notification, fixture.state.notifications[NotificationScope.PRIVATE_CHATS])
        fixture.session.profiles.single()(SettingsResult(profile("Original")))
        fixture.flush()
        fixture.controller.saveProfile(fixture.state.token, SettingsProfileEdit(ProfileField.NAME, "Retired write"))
        fixture.controller.deactivate()
        val retired = fixture.state
        fixture.session.profileWrites.single().result(SettingsResult(Unit))
        fixture.flush()
        assertEquals(retired, fixture.state)
        assertEquals(1, fixture.session.profiles.size)
        assertTrue(fixture.state.completedSaveRevision.isEmpty())
    }

    private class Fixture {
        val session = FakeSession()
        private val queue = ArrayDeque<() -> Unit>()
        var state = SettingsSnapshot()
        val controller = SettingsController(7, session, { queue.addLast(it) }, { state = it })

        init { controller.start() }

        fun flush() { while (queue.isNotEmpty()) queue.removeFirst().invoke() }

        fun loadProfile(name: String) {
            controller.load(state.token, SettingsSection.PROFILE)
            session.profiles.last()(SettingsResult(profile(name)))
            flush()
        }
    }

    private class FakeSession : TdSettingsSession {
        val profiles = mutableListOf<(SettingsResult<SettingsProfile>) -> Unit>()
        val profileWrites = mutableListOf<ProfileWrite>()
        val notificationReads = mutableListOf<(SettingsResult<Map<NotificationScope, NotificationPreferences>>) -> Unit>()
        val privacyReads = mutableListOf<(SettingsResult<SettingsPrivacyData>) -> Unit>()
        val terminations = mutableListOf<Termination>()

        override fun profile(result: (SettingsResult<SettingsProfile>) -> Unit) { profiles += result }
        override fun saveProfile(edit: SettingsProfileEdit, result: (SettingsResult<Unit>) -> Unit) {
            profileWrites += ProfileWrite(edit, result)
        }
        override fun notifications(result: (SettingsResult<Map<NotificationScope, NotificationPreferences>>) -> Unit) {
            notificationReads += result
        }
        override fun privacy(result: (SettingsResult<SettingsPrivacyData>) -> Unit) { privacyReads += result }
        override fun terminateSession(id: Long, result: (SettingsResult<Unit>) -> Unit) { terminations += Termination(id, result) }
        override fun saveNotifications(scope: NotificationScope, value: NotificationPreferences, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun savePrivacy(key: PrivacyKey, base: PrivacyBase, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun setAccountTtl(days: Int, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun unblock(sender: SettingsBlockedSender, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun advanced(result: (SettingsResult<SettingsAdvancedData>) -> Unit) = unsupported(result)
        override fun optimizeStorage(result: (SettingsResult<SettingsStorage>) -> Unit) = unsupported(result)
        override fun addProxy(draft: SettingsProxyDraft, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun enableProxy(id: Int?, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun removeProxy(id: Int, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        private fun <T> unsupported(result: (SettingsResult<T>) -> Unit) { result(SettingsResult(error = SettingsError.UNSUPPORTED)) }
    }

    private data class ProfileWrite(val edit: SettingsProfileEdit, val result: (SettingsResult<Unit>) -> Unit)
    private data class Termination(val id: Long, val result: (SettingsResult<Unit>) -> Unit)

    private companion object {
        fun profile(name: String) = SettingsProfile(100, name, "Surname", "native_user", "Native bio", "+10000000000")
        fun session(id: Long, current: Boolean) = SettingsSession(id, current, "Native Device", "Native App", "1",
            "Windows", "11", 123, "192.0.2.1", "Native Location")
    }
}
