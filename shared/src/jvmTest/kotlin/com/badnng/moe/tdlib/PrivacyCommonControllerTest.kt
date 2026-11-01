package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import kotlin.test.*

class PrivacyCommonControllerTest {
    @Test fun pageIsLazyAndClosedPendingOpeningCannotIssueARead() {
        val f = Fixture()
        assertTrue(f.session.reads.isEmpty())
        f.visible = false
        f.controller.open { f.visible }
        assertTrue(f.session.reads.isEmpty())
        assertFalse(f.state.opened)
        f.visible = true
        f.open()
        assertTrue(f.state.opened)
        assertEquals(1, f.session.reads.size)
    }

    @Test fun closeRetiresTheActualLeaseAndEveryLateCallbackWithoutReopening() {
        val f = Fixture()
        f.controller.open { f.visible }
        val owner = f.state.token
        val pending = f.session.reads.single()
        f.visible = false
        f.controller.close(owner)
        assertFalse(pending.active())
        val closed = f.state
        pending.complete(SettingsResult(PrivacyCommonRead(data())))
        f.flush()
        assertEquals(closed, f.state)
        assertFalse(f.state.opened)
    }

    @Test fun fieldReadErrorsKeepOtherActualFieldsUsableAndExplicitRefreshClearsTheGate() {
        val f = Fixture()
        f.controller.open { f.visible }
        f.session.reads.last().complete(SettingsResult(PrivacyCommonRead(data().copy(topPeersEnabled = null),
            mapOf(PrivacyCommonField.TOP_PEERS to SettingsError.REQUEST_FAILED))))
        f.flush()
        assertNull(f.state.data.topPeersEnabled)
        assertEquals(365, f.state.data.accountTtlDays)
        f.controller.topPeers(f.state.token, true, false)
        assertTrue(f.session.topWrites.isEmpty())
        f.controller.accountTtl(f.state.token, 365, 720)
        assertEquals(1, f.session.ttlWrites.size)
        f.session.ttlWrites.single().complete(SettingsResult(error = SettingsError.REQUEST_FAILED))
        f.flush()
        f.controller.refresh(f.state.token)
        f.session.reads.last().complete(SettingsResult(PrivacyCommonRead(data())))
        f.flush()
        assertTrue(f.state.refreshRequired.isEmpty())
        assertTrue(f.state.errors.isEmpty())
    }

    @Test fun topPeersConflictKeepsSourceAndRequiresFreshReadBeforeAnyRetry() {
        val f = Fixture()
        f.open()
        f.controller.topPeers(f.state.token, true, false)
        assertTrue(f.state.busy)
        f.controller.topPeers(f.state.token, true, false)
        assertEquals(1, f.session.topWrites.size)
        f.session.topWrites.single().complete(SettingsResult(error = SettingsError.CONFLICT))
        f.flush()
        assertEquals(true, f.state.data.topPeersEnabled)
        assertTrue(PrivacyCommonField.TOP_PEERS in f.state.refreshRequired)
        f.controller.topPeers(f.state.token, true, false)
        assertEquals(1, f.session.topWrites.size)
        assertEquals(SettingsError.CONFLICT, f.state.errors[PrivacyCommonField.TOP_PEERS])
    }

    @Test fun successfulAccountMutationAndFailedReadbackCannotAuthorizeTheOldDaysAgain() {
        val f = Fixture()
        f.open()
        f.controller.accountTtl(f.state.token, 365, 730)
        val write = f.session.ttlWrites.single()
        assertEquals(365 to 730, write.expected to write.desired)
        write.complete(SettingsResult(Unit))
        f.flush()
        assertTrue(f.state.acceptedRevision > 0)
        assertTrue(f.state.loading)
        f.session.reads.last().complete(SettingsResult(PrivacyCommonRead(data().copy(accountTtlDays = null),
            mapOf(PrivacyCommonField.ACCOUNT_TTL to SettingsError.REQUEST_FAILED))))
        f.flush()
        assertEquals(365, f.state.data.accountTtlDays)
        assertTrue(PrivacyCommonField.ACCOUNT_TTL in f.state.refreshRequired)
        f.controller.accountTtl(f.state.token, 365, 720)
        assertEquals(1, f.session.ttlWrites.size)
        f.controller.refresh(f.state.token)
        f.session.reads.last().complete(SettingsResult(PrivacyCommonRead(data().copy(accountTtlDays = 730))))
        f.flush()
        assertEquals(730, f.state.data.accountTtlDays)
        assertTrue(f.state.refreshRequired.isEmpty())
    }

    @Test fun accountTtlExpectedMismatchAndBoundsDoNotReachNative() {
        val f = Fixture()
        f.open()
        f.controller.accountTtl(f.state.token, 365, 29)
        assertEquals(SettingsError.INVALID_VALUE, f.state.errors[PrivacyCommonField.ACCOUNT_TTL])
        f.controller.accountTtl(f.state.token, 365, 731)
        assertEquals(SettingsError.INVALID_VALUE, f.state.errors[PrivacyCommonField.ACCOUNT_TTL])
        f.controller.accountTtl(f.state.token, 730, 30)
        assertEquals(SettingsError.CONFLICT, f.state.errors[PrivacyCommonField.ACCOUNT_TTL])
        assertTrue(f.session.ttlWrites.isEmpty())
    }

    @Test fun nativeCapabilityRevocationDisablesOpeningButStillAllowsClosingExistingArchiveSetting() {
        val f = Fixture()
        f.open()
        val old = f.state.data.archive!!
        f.session.updates?.invoke(PrivacyCommonUpdate(PrivacyCommonField.ARCHIVE_CAPABILITY, false))
        f.flush()
        assertEquals(PrivacyArchiveCapability.DENIED, f.state.data.archiveCapability)
        f.controller.archive(f.state.token, old, old.copy(archiveAndMuteNewChats = true))
        assertEquals(SettingsError.FORBIDDEN, f.state.errors[PrivacyCommonField.ARCHIVE])
        assertTrue(f.session.archiveWrites.isEmpty())
        f.controller.refresh(f.state.token)
        f.session.reads.last().complete(SettingsResult(PrivacyCommonRead(data().copy(
            archive = old.copy(archiveAndMuteNewChats = true), archiveCapability = PrivacyArchiveCapability.DENIED))))
        f.flush()
        val actual = f.state.data.archive!!
        f.controller.archive(f.state.token, actual, actual.copy(archiveAndMuteNewChats = false))
        assertEquals(1, f.session.archiveWrites.size)
    }

    @Test fun nativeOptionUpdatesInvalidateAnOlderReadButDoNotReadWhileMutating() {
        val f = Fixture()
        f.controller.open { f.visible }
        val old = f.session.reads.single()
        f.session.updates?.invoke(PrivacyCommonUpdate(PrivacyCommonField.TOP_PEERS, false))
        f.flush()
        assertEquals(2, f.session.reads.size)
        assertEquals(false, f.state.data.topPeersEnabled)
        old.complete(SettingsResult(PrivacyCommonRead(data().copy(topPeersEnabled = true))))
        f.flush()
        assertEquals(false, f.state.data.topPeersEnabled)
        assertTrue(f.state.loading)
        f.session.reads.last().complete(SettingsResult(PrivacyCommonRead(data().copy(topPeersEnabled = false))))
        f.flush()
        f.controller.topPeers(f.state.token, false, true)
        f.session.updates?.invoke(PrivacyCommonUpdate(PrivacyCommonField.TOP_PEERS, true))
        f.flush()
        assertEquals(2, f.session.reads.size)
        assertTrue(f.state.busy)
    }

    @Test fun oldMutationCannotClaimNewPageAcceptanceAndNewPageWaitsForItsActualCompletion() {
        val f = Fixture()
        f.open()
        val oldOwner = f.state.token
        f.controller.accountTtl(oldOwner, 365, 720)
        val old = f.session.ttlWrites.single()
        f.controller.close(oldOwner)
        assertFalse(old.active())
        f.controller.open { f.visible }
        assertTrue(f.state.busy)
        assertEquals(1, f.session.reads.size)
        old.complete(SettingsResult(Unit))
        f.flush()
        assertEquals(0L, f.state.acceptedRevision)
        assertTrue(f.state.loading)
        assertFalse(f.state.busy)
        f.session.reads.last().complete(SettingsResult(PrivacyCommonRead(data().copy(accountTtlDays = 720))))
        f.flush()
        assertEquals(720, f.state.data.accountTtlDays)
        assertNotEquals(oldOwner, f.state.token)
    }

    @Test fun retiredOwnerClearsObserverAndCannotConsumeUpdatesOrDuplicateResults() {
        val f = Fixture()
        f.open()
        f.controller.topPeers(f.state.token, true, false)
        val pending = f.session.topWrites.single()
        f.controller.deactivate()
        assertNull(f.session.updates)
        assertFalse(pending.active())
        pending.complete(SettingsResult(Unit))
        pending.complete(SettingsResult(Unit))
        f.flush()
        assertEquals(1, f.session.reads.size)
        f.controller.open { true }
        assertEquals(1, f.session.reads.size)
    }

    private class Fixture {
        var visible = true
        var state = PrivacyCommonSnapshot()
        val session = FakeSession()
        private val queued = ArrayDeque<() -> Unit>()
        val controller = PrivacyCommonController(23, session, { queued += it }, { state = it })
        fun flush() { while (queued.isNotEmpty()) queued.removeFirst().invoke() }
        fun open() {
            controller.open { visible }
            session.reads.last().complete(SettingsResult(PrivacyCommonRead(data())))
            flush()
        }
    }
    private class FakeSession : TdPrivacyCommonSession {
        var updates: ((PrivacyCommonUpdate) -> Unit)? = null
        val reads = mutableListOf<Read>()
        val topWrites = mutableListOf<Write<Boolean>>()
        val archiveWrites = mutableListOf<Write<PrivacyArchiveSettings>>()
        val ttlWrites = mutableListOf<Write<Int>>()
        override fun observe(observer: ((PrivacyCommonUpdate) -> Unit)?) { updates = observer }
        override fun load(isCurrent: () -> Boolean, result: (SettingsResult<PrivacyCommonRead>) -> Unit) { reads += Read(isCurrent, result) }
        override fun setTopPeers(expected: Boolean, desired: Boolean, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) {
            topWrites += Write(expected, desired, isCurrent, result)
        }
        override fun setArchive(expected: PrivacyArchiveSettings, desired: PrivacyArchiveSettings, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) {
            archiveWrites += Write(expected, desired, isCurrent, result)
        }
        override fun setAccountTtl(expected: Int, desired: Int, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) {
            ttlWrites += Write(expected, desired, isCurrent, result)
        }
    }
    private data class Read(val active: () -> Boolean, val complete: (SettingsResult<PrivacyCommonRead>) -> Unit)
    private data class Write<T>(val expected: T, val desired: T, val active: () -> Boolean, val complete: (SettingsResult<Unit>) -> Unit)
    private companion object {
        fun data() = PrivacyCommonData(true, PrivacyArchiveSettings(false, true, false), PrivacyArchiveCapability.ALLOWED, 365)
    }
}
