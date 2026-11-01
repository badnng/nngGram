package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Actor queues and session callbacks are synthetic; no native client or account is used. */
class LocalLockSettingsMutationTest {
    @Test
    fun queuedWriteReceiptSurvivesLockButReconciliationUsesOnlyTheCurrentReadEpoch() {
        val f = Fixture()
        f.loadProfile()
        val token = f.state.token
        f.controller.saveProfile(token, SettingsProfileEdit(ProfileField.NAME, "requested"))
        val revision = f.state.startedSaveRevision.getValue(SettingsSection.PROFILE)
        f.session.profileWrites.single()(SettingsResult(Unit)) // Queued before the witness is revoked.
        f.lock()
        f.flush()
        assertEquals(1, f.session.profileWrites.size)
        assertEquals(1, f.session.profiles.size, "A blocked owner must not start the readback.")
        assertNull(f.state.completedSaveRevision[SettingsSection.PROFILE])

        f.unlock()
        assertEquals(2, f.session.profiles.size)
        f.session.profiles.last()(SettingsResult(profile("old read")))
        f.lock() // The ordinary actor work is already queued but no longer owns its epoch.
        f.flush()
        assertEquals("original", f.state.profile?.firstName)
        assertNull(f.state.completedSaveRevision[SettingsSection.PROFILE])
        f.unlock()
        assertEquals(3, f.session.profiles.size, "A later owner must retry the lost read, not the write.")
        f.session.profiles.last()(SettingsResult(profile("confirmed")))
        f.flush()
        assertEquals("confirmed", f.state.profile?.firstName)
        assertEquals(revision, f.state.completedSaveRevision[SettingsSection.PROFILE])
        assertEquals(1, f.session.profileWrites.size)
        f.controller.resumePendingWrites()
        assertEquals(3, f.session.profiles.size)
    }

    @Test
    fun actualNativeWriteErrorSettlesTheOriginalRevisionWithoutStartingReadback() {
        val f = Fixture()
        f.loadProfile()
        f.controller.saveProfile(f.state.token, SettingsProfileEdit(ProfileField.BIO, "requested"))
        val revision = f.state.startedSaveRevision.getValue(SettingsSection.PROFILE)
        f.lock()
        f.session.profileWrites.single()(SettingsResult(error = SettingsError.RATE_LIMITED))
        f.flush()
        assertEquals(SettingsError.RATE_LIMITED, f.state.errors[SettingsSection.PROFILE])
        assertEquals(revision, f.state.failedSaveRevision[SettingsSection.PROFILE])
        assertNull(f.state.completedSaveRevision[SettingsSection.PROFILE])
        f.unlock()
        assertEquals(1, f.session.profiles.size)
        assertEquals(1, f.session.profileWrites.size)
    }

    @Test
    fun admittedTransportFailureIsUnknownAndReadbackCannotClaimAConfirmedSave() {
        val f = Fixture()
        f.loadProfile()
        f.controller.saveProfile(f.state.token, SettingsProfileEdit(ProfileField.NAME, "requested"))
        val revision = f.state.startedSaveRevision.getValue(SettingsSection.PROFILE)
        f.lock()
        f.controller.mutationOutcomeUnknown(SettingsSection.PROFILE, revision)
        assertNull(f.state.failedSaveRevision[SettingsSection.PROFILE])
        assertNull(f.state.completedSaveRevision[SettingsSection.PROFILE])
        assertEquals(SettingsError.REQUEST_FAILED, f.state.errors[SettingsSection.PROFILE])
        f.unlock()
        assertEquals(2, f.session.profiles.size)
        f.session.profiles.last()(SettingsResult(profile("observed")))
        f.flush()
        assertEquals("observed", f.state.profile?.firstName)
        assertNull(f.state.failedSaveRevision[SettingsSection.PROFILE])
        assertNull(f.state.completedSaveRevision[SettingsSection.PROFILE])
        assertEquals(1, f.session.profileWrites.size)
    }

    @Test
    fun retiredGenerationCannotUseItsLateReceiptOrResumeOnTheReplacementOwner() {
        val f = Fixture(generation = 7)
        f.loadProfile()
        val oldToken = f.state.token
        f.controller.saveProfile(oldToken, SettingsProfileEdit(ProfileField.NAME, "old"))
        f.lock()
        f.controller.deactivate()
        val retired = f.state
        f.session.profileWrites.single()(SettingsResult(Unit))
        f.flush()
        f.unlock()
        assertEquals(retired, f.state)
        assertFalse(f.controller.accepts(oldToken))
        assertEquals(1, f.session.profiles.size)

        val replacement = Fixture(generation = 8)
        replacement.controller.saveProfile(oldToken, SettingsProfileEdit(ProfileField.NAME, "stale owner"))
        replacement.controller.resumePendingWrites()
        assertTrue(replacement.session.profileWrites.isEmpty())
        assertTrue(replacement.session.profiles.isEmpty())
    }

    @Test
    fun proxyPageDepartureKeepsAcceptedWritePendingUntilANewPageUsesItsBudget() {
        val f = Fixture()
        val oldPage = SettingsProxyChecksPageLease { true }
        var oldToken: SettingsProxyChecksToken? = null
        f.controller.openProxyChecks(f.state.token, oldPage) { oldToken = it }
        assertNotNull(oldToken)
        f.session.proxyReads.single().complete(SettingsResult(SettingsAdvancedData(null,
            listOf(SettingsProxy(1, "synthetic.invalid", 1080, SettingsProxyType.SOCKS5, enabled = false)))))
        f.flush()
        f.controller.enableProxy(f.state.token, 1)
        val revision = f.state.startedSaveRevision.getValue(SettingsSection.ADVANCED)
        assertEquals(1, f.session.proxyWrites.size)
        f.lock()
        oldPage.revoke()
        f.controller.closeProxyChecks(f.state.token, oldPage)
        f.session.proxyWrites.single()(SettingsResult(Unit))
        f.flush()
        f.unlock()
        f.flush()
        assertEquals(1, f.session.proxyReads.size)
        assertTrue(f.session.ordinaryAdvancedReads.isEmpty(), "A departed page cannot bypass its physical read budget.")
        assertNull(f.state.failedSaveRevision[SettingsSection.ADVANCED])
        assertNull(f.state.completedSaveRevision[SettingsSection.ADVANCED])

        val newPage = SettingsProxyChecksPageLease { true }
        var newToken: SettingsProxyChecksToken? = null
        f.controller.openProxyChecks(f.state.token, newPage) { newToken = it }
        assertNotNull(newToken)
        assertEquals(2, f.session.proxyReads.size, "The replacement page owns the budgeted reconciliation.")
        f.session.proxyReads.last().complete(SettingsResult(SettingsAdvancedData(null,
            listOf(SettingsProxy(1, "synthetic.invalid", 1080, SettingsProxyType.SOCKS5, enabled = true)))))
        f.flush()
        assertEquals(revision, f.state.completedSaveRevision[SettingsSection.ADVANCED])
        assertEquals(1, f.session.proxyWrites.size, "The accepted write must not be submitted twice.")
        assertTrue(f.session.ordinaryAdvancedReads.isEmpty())
    }

    private class Fixture(generation: Long = 1) {
        val session = FakeSettingsSession()
        private val queue = ArrayDeque<() -> Unit>()
        private var epoch = 1L
        private var open = true
        var state = SettingsSnapshot()
        val controller = SettingsController(
            generation = generation,
            session = session,
            enqueue = { work ->
                val captured = epoch
                queue.addLast { if (open && epoch == captured) work() }
            },
            enqueueMutation = { _, _, work -> queue.addLast(work) },
            publish = { state = it },
            ordinaryAllowed = { open },
            readbackRevision = { epoch },
        )

        init { controller.start() }

        fun flush() { while (queue.isNotEmpty()) queue.removeFirst().invoke() }
        fun lock() { open = false; epoch++ }
        fun unlock() { open = true; epoch++; controller.resumePendingWrites() }
        fun loadProfile() {
            controller.load(state.token, SettingsSection.PROFILE)
            session.profiles.single()(SettingsResult(profile("original")))
            flush()
        }
    }

    private class FakeSettingsSession : TdSettingsSession {
        val profiles = mutableListOf<(SettingsResult<SettingsProfile>) -> Unit>()
        val profileWrites = mutableListOf<(SettingsResult<Unit>) -> Unit>()
        val proxyReads = mutableListOf<ProxyRead>()
        val ordinaryAdvancedReads = mutableListOf<(SettingsResult<SettingsAdvancedData>) -> Unit>()
        val proxyWrites = mutableListOf<(SettingsResult<Unit>) -> Unit>()
        override fun profile(result: (SettingsResult<SettingsProfile>) -> Unit) { profiles += result }
        override fun saveProfile(edit: SettingsProfileEdit, result: (SettingsResult<Unit>) -> Unit) { profileWrites += result }
        override fun notifications(result: (SettingsResult<Map<NotificationScope, NotificationPreferences>>) -> Unit) = unsupported(result)
        override fun privacy(result: (SettingsResult<SettingsPrivacyData>) -> Unit) = unsupported(result)
        override fun saveNotifications(scope: NotificationScope, value: NotificationPreferences, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun savePrivacy(key: PrivacyKey, base: PrivacyBase, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun setAccountTtl(days: Int, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun terminateSession(id: Long, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun unblock(sender: SettingsBlockedSender, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun advanced(result: (SettingsResult<SettingsAdvancedData>) -> Unit) { ordinaryAdvancedReads += result }
        override fun advancedForProxyChecks(lease: SettingsProxyProbeLease, result: (SettingsResult<SettingsAdvancedData>) -> Unit) {
            check(lease.beginProbe())
            val stage = checkNotNull(lease.beginStage())
            if (!lease.dispatch { proxyReads += ProxyRead(lease, stage, result) }) {
                lease.endStage(stage)
                lease.finishProbe()
                result(SettingsResult(error = SettingsError.NOT_READY))
            }
        }
        override fun optimizeStorage(result: (SettingsResult<SettingsStorage>) -> Unit) = unsupported(result)
        override fun addProxy(draft: SettingsProxyDraft, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun enableProxy(id: Int?, result: (SettingsResult<Unit>) -> Unit) { proxyWrites += result }
        override fun removeProxy(id: Int, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        private fun <T> unsupported(result: (SettingsResult<T>) -> Unit) { result(SettingsResult(error = SettingsError.UNSUPPORTED)) }
    }

    private class ProxyRead(
        private val lease: SettingsProxyProbeLease,
        private val stage: SettingsProxyProbeLease.Stage,
        private val result: (SettingsResult<SettingsAdvancedData>) -> Unit,
    ) {
        private var complete = false
        fun complete(value: SettingsResult<SettingsAdvancedData>) {
            check(!complete)
            complete = true
            lease.endStage(stage)
            lease.finishProbe()
            result(value)
        }
    }

    private companion object {
        fun profile(name: String) = SettingsProfile(100, name, "Surname", "native_user", "Native bio", "+10000000000")
    }
}
