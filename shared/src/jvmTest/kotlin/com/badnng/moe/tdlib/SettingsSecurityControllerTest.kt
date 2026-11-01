package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import kotlin.test.*

class SettingsSecurityControllerTest {
    @Test
    fun managementReadsAreLazyAndSwitchingOwnersRetiresEveryOldLeaseAndResult() {
        val fixture = Fixture()
        assertTrue(fixture.session.passwordReads.isEmpty())
        assertTrue(fixture.session.websiteReads.isEmpty())
        fixture.controller.open(SettingsSecuritySection.PASSWORD)
        val oldToken = fixture.state.token
        val old = fixture.session.passwordReads.single()
        fixture.controller.open(SettingsSecuritySection.WEBSITES)
        assertFalse(old.active())
        old.result(SettingsResult(passwordState()))
        fixture.flush()
        assertEquals(SettingsSecuritySection.WEBSITES, fixture.state.section)
        assertNull(fixture.state.password)
        assertNotEquals(oldToken, fixture.state.token)
        fixture.session.websiteReads.single().result(SettingsResult(SettingsWebsitesData(emptyList(), 0)))
        fixture.flush()
        var rejected: SettingsResult<SettingsPasswordOutcome>? = null
        fixture.controller.password(oldToken, SettingsPasswordCommand(SettingsPasswordOperation.VERIFY_CURRENT,
            currentPassword = "private password")) { rejected = it }
        assertEquals(SettingsError.NOT_READY, rejected?.error)
        assertTrue(fixture.session.passwordWrites.isEmpty())
    }

    @Test
    fun closingAnInFlightPasswordOwnerClearsTheSnapshotAndNeverCallsItsOldFormBack() {
        val fixture = Fixture()
        fixture.controller.open(SettingsSecuritySection.PASSWORD)
        fixture.session.passwordReads.single().result(SettingsResult(passwordState()))
        fixture.flush()
        var completions = 0
        fixture.controller.password(fixture.state.token, SettingsPasswordCommand(SettingsPasswordOperation.SET_PASSWORD,
            currentPassword = "secret old", newPassword = "secret new", email = "private@example.test", code = "123456")) { completions++ }
        val write = fixture.session.passwordWrites.single()
        assertTrue(fixture.state.busy)
        assertFalse(fixture.state.toString().contains("secret"))
        assertFalse(write.command.toString().contains("123456"))
        fixture.controller.close(fixture.state.token)
        assertFalse(write.active())
        val closed = fixture.state
        write.result(SettingsResult(SettingsPasswordOutcome(state = passwordState(), verified = true)))
        fixture.flush()
        assertEquals(0, completions)
        assertEquals(closed, fixture.state)
        assertNull(fixture.state.section)
    }

    @Test
    fun loginEmailCommandsCarryTheActualSecurityOwnerAndEveryPageReplacementRetiresIt() {
        val fixture = Fixture()
        fixture.controller.open(SettingsSecuritySection.PASSWORD)
        val oldToken = fixture.state.token
        fixture.session.passwordReads.last().result(SettingsResult(passwordState()))
        fixture.flush()
        var completed = 0
        fixture.controller.password(oldToken, SettingsPasswordCommand(SettingsPasswordOperation.SET_LOGIN_EMAIL,
            email = "private@example.test")) { completed++ }
        assertEquals(listOf(oldToken), fixture.session.passwordOwners)
        val pending = fixture.session.passwordWrites.single()
        fixture.controller.open(SettingsSecuritySection.WEBSITES)
        assertTrue(oldToken in fixture.session.retiredPasswordOwners)
        assertFalse(pending.active())
        pending.result(SettingsResult(SettingsPasswordOutcome(codeInfo = SettingsEmailCodeInfo("p***", 4))))
        fixture.flush()
        assertEquals(0, completed)
        assertEquals(SettingsSecuritySection.WEBSITES, fixture.state.section)
        val websiteOwner = fixture.state.token
        fixture.controller.close(websiteOwner)
        assertTrue(websiteOwner in fixture.session.retiredPasswordOwners)
        fixture.controller.open(SettingsSecuritySection.PASSWORD)
        val replacement = fixture.state.token
        assertNotEquals(oldToken, replacement)
        fixture.controller.deactivate()
        assertTrue(replacement in fixture.session.retiredPasswordOwners)
    }

    @Test
    fun websitePartialSuccessRefreshesTheActualListBeforeReturningTheDistinctBlockError() {
        val fixture = Fixture()
        fixture.controller.open(SettingsSecuritySection.WEBSITES)
        fixture.session.websiteReads.single().result(SettingsResult(SettingsWebsitesData(listOf(website()), 1)))
        fixture.flush()
        var outcome: SettingsResult<SettingsWebsiteDisconnectOutcome>? = null
        fixture.controller.disconnect(fixture.state.token, website(), true) { outcome = it }
        fixture.session.disconnections.single()(SettingsResult(SettingsWebsiteDisconnectOutcome(true, false), SettingsError.FORBIDDEN))
        fixture.flush()
        assertTrue(fixture.state.busy)
        assertNull(outcome)
        fixture.session.websiteReads.last().result(SettingsResult(SettingsWebsitesData(emptyList(), 0)))
        fixture.flush()
        assertTrue(fixture.state.websites.isEmpty())
        assertEquals(SettingsWebsiteDisconnectOutcome(true, false), outcome?.value)
        assertEquals(SettingsError.FORBIDDEN, outcome?.error)
        assertEquals(SettingsError.FORBIDDEN, fixture.state.error)
        assertFalse(fixture.state.busy)
    }

    @Test
    fun blockedPagingUsesTheNativeCursorAndCoalescesExternalChangesIntoAReset() {
        val fixture = Fixture()
        fixture.controller.open(SettingsSecuritySection.BLOCKED)
        val token = fixture.state.token
        fixture.session.blockedReads.single().result(SettingsResult(SettingsBlockedPage(
            listOf(SettingsBlockedSender(1, false)), 16, 80, false)))
        fixture.flush()
        fixture.controller.moreBlocked(token)
        assertEquals(16, fixture.session.blockedReads.last().offset)
        fixture.session.changed?.invoke()
        fixture.session.changed?.invoke()
        fixture.flush()
        assertEquals(2, fixture.session.blockedReads.size)
        fixture.session.blockedReads.last().result(SettingsResult(SettingsBlockedPage(
            listOf(SettingsBlockedSender(1, false), SettingsBlockedSender(2, false)), 56, 79, false)))
        fixture.flush()
        assertEquals(3, fixture.session.blockedReads.size)
        assertEquals(0, fixture.session.blockedReads.last().offset)
        assertTrue(fixture.state.loading)
        fixture.session.blockedReads.last().result(SettingsResult(SettingsBlockedPage(
            listOf(SettingsBlockedSender(2, false)), 1, 1, true)))
        fixture.flush()
        assertEquals(listOf(2L), fixture.state.blocked.map { it.id })
        assertTrue(fixture.state.blockedEnd)
        fixture.controller.moreBlocked(token)
        assertEquals(3, fixture.session.blockedReads.size)
    }

    @Test
    fun aFailedBlockedPageKeepsItsRowsAndCursorForAnExplicitRetryAndClosingIgnoresUpdates() {
        val fixture = Fixture()
        fixture.controller.open(SettingsSecuritySection.BLOCKED)
        fixture.session.blockedReads.single().result(SettingsResult(SettingsBlockedPage(listOf(SettingsBlockedSender(1, false)), 16, 50, false)))
        fixture.flush()
        val token = fixture.state.token
        fixture.controller.moreBlocked(token)
        fixture.session.blockedReads.last().result(SettingsResult(error = SettingsError.REQUEST_FAILED))
        fixture.flush()
        assertEquals(listOf(1L), fixture.state.blocked.map { it.id })
        fixture.controller.moreBlocked(token)
        assertEquals(16, fixture.session.blockedReads.last().offset)
        fixture.controller.close(token)
        fixture.session.changed?.invoke()
        fixture.session.blockedReads.last().result(SettingsResult(SettingsBlockedPage(emptyList(), 16, 16, true)))
        fixture.flush()
        assertNull(fixture.state.section)
        assertTrue(fixture.state.blocked.isEmpty())
        assertEquals(3, fixture.session.blockedReads.size)
    }

    @Test
    fun aFailedExternalResetNeverReusesTheInvalidatedOffsetForLoadingMore() {
        val fixture = Fixture()
        fixture.controller.open(SettingsSecuritySection.BLOCKED)
        fixture.session.blockedReads.single().result(SettingsResult(SettingsBlockedPage(listOf(SettingsBlockedSender(1, false)), 16, 80, false)))
        fixture.flush()
        fixture.session.changed?.invoke()
        fixture.flush()
        assertEquals(0, fixture.session.blockedReads.last().offset)
        fixture.session.blockedReads.last().result(SettingsResult(error = SettingsError.REQUEST_FAILED))
        fixture.flush()
        assertEquals(listOf(1L), fixture.state.blocked.map { it.id })
        fixture.controller.moreBlocked(fixture.state.token)
        assertEquals(0, fixture.session.blockedReads.last().offset)
        assertTrue(fixture.state.loading)
        fixture.session.blockedReads.last().result(SettingsResult(SettingsBlockedPage(listOf(SettingsBlockedSender(2, false)), 16, 79, false)))
        fixture.flush()
        fixture.controller.moreBlocked(fixture.state.token)
        assertEquals(16, fixture.session.blockedReads.last().offset)
        assertEquals(listOf(2L), fixture.state.blocked.map { it.id })
    }

    private class Fixture {
        val session = FakeSession()
        var state = SettingsSecuritySnapshot()
        private val queued = ArrayDeque<() -> Unit>()
        val controller = SettingsSecurityController(7, session, { queued.addLast(it) }, { state = it })
        fun flush() { while (queued.isNotEmpty()) queued.removeFirst().invoke() }
    }
    private class FakeSession : NoSettingsSession() {
        var changed: (() -> Unit)? = null
        val passwordReads = mutableListOf<Read<SettingsPasswordState>>()
        val websiteReads = mutableListOf<Read<SettingsWebsitesData>>()
        val passwordWrites = mutableListOf<PasswordWrite>()
        val passwordOwners = mutableListOf<SettingsSecurityToken>()
        val retiredPasswordOwners = mutableListOf<SettingsSecurityToken>()
        val disconnections = mutableListOf<(SettingsResult<SettingsWebsiteDisconnectOutcome>) -> Unit>()
        val blockedReads = mutableListOf<BlockedRead>()
        override fun observeSecurityChanges(observer: (() -> Unit)?) { changed = observer }
        override fun passwordState(isCurrent: () -> Boolean, result: (SettingsResult<SettingsPasswordState>) -> Unit) { passwordReads += Read(isCurrent, result) }
        override fun websites(isCurrent: () -> Boolean, result: (SettingsResult<SettingsWebsitesData>) -> Unit) { websiteReads += Read(isCurrent, result) }
        override fun password(command: SettingsPasswordCommand, isCurrent: () -> Boolean, result: (SettingsResult<SettingsPasswordOutcome>) -> Unit) {
            passwordWrites += PasswordWrite(command, isCurrent, result)
        }
        override fun passwordForOwner(token: SettingsSecurityToken, command: SettingsPasswordCommand, isCurrent: () -> Boolean,
            result: (SettingsResult<SettingsPasswordOutcome>) -> Unit,
        ) { passwordOwners += token; password(command, isCurrent, result) }
        override fun retirePasswordOwner(token: SettingsSecurityToken) { retiredPasswordOwners += token }
        override fun disconnectWebsite(target: SettingsWebsite, blockBot: Boolean, isCurrent: () -> Boolean, result: (SettingsResult<SettingsWebsiteDisconnectOutcome>) -> Unit) {
            assertEquals(website(), target); assertTrue(blockBot); disconnections += result
        }
        override fun blockedPage(offset: Int, isCurrent: () -> Boolean, result: (SettingsResult<SettingsBlockedPage>) -> Unit) { blockedReads += BlockedRead(offset, result) }
    }
    private data class Read<T>(val active: () -> Boolean, val result: (SettingsResult<T>) -> Unit)
    private data class PasswordWrite(val command: SettingsPasswordCommand, val active: () -> Boolean, val result: (SettingsResult<SettingsPasswordOutcome>) -> Unit)
    private data class BlockedRead(val offset: Int, val result: (SettingsResult<SettingsBlockedPage>) -> Unit)
    private open class NoSettingsSession : TdSettingsSession {
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
        private fun <T> unsupported(result: (SettingsResult<T>) -> Unit) { result(SettingsResult(error = SettingsError.UNSUPPORTED)) }
    }
    private companion object {
        fun passwordState() = SettingsPasswordState(true, "Native hint", true, false, null, "", 0)
        fun website() = SettingsWebsite(-77, "native.test", 42, "Browser", "Windows", 1, 2, "192.0.2.1", "Location")
    }
}
