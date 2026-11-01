package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import kotlin.test.*

/** Synthetic owner/candidate callbacks only; never opens an account. */
class SettingsBlockedPickerControllerTest {
    @Test fun cancelRetiresCandidateReadsAndNeverWrites() {
        val f = Fixture()
        val picker = f.open()
        val pending = f.session.reads.single()
        f.controller.closeBlockedPicker(picker)
        assertFalse(pending.current())
        pending.reply(SettingsResult(SettingsBlockedCandidates(listOf(candidate()), 1, true)))
        f.flush()
        assertNull(f.state.blockedPicker)
        assertTrue(f.session.writes.isEmpty())
    }

    @Test fun onlyCurrentVisibleUnblockedIdentitiesCanBeSubmittedOnce() {
        val f = Fixture()
        val picker = f.open()
        f.session.reads.last().reply(SettingsResult(SettingsBlockedCandidates(
            listOf(candidate(), candidate(52, blocked = true)), 2, true)))
        f.flush()
        val refused = mutableListOf<SettingsResult<Unit>>()
        f.controller.blockPickerUser(picker, 999, refused::add)
        f.controller.blockPickerUser(picker, 52, refused::add)
        assertTrue(f.session.writes.isEmpty(), "An arbitrary ID or disabled blocked row has no dispatch authority.")
        assertTrue(refused.all { it.value == null })
        f.controller.blockPickerUser(picker, 51) { }
        f.controller.blockPickerUser(picker, 51, refused::add)
        assertEquals(listOf(51L), f.session.writes.map { it.userId })
    }

    @Test fun replacedQueryAndReopenedPickerCannotPublishOrAuthorizeOldCandidates() {
        val f = Fixture()
        val first = f.open()
        val oldRead = f.session.reads.last()
        f.controller.searchBlockedPicker(first, f.state.blockedPicker!!.query.copy(
            source = SettingsBlockedPeerSource.SERVER, query = "new"))
        assertFalse(oldRead.current())
        oldRead.reply(SettingsResult(SettingsBlockedCandidates(listOf(candidate()), 1, true)))
        f.flush()
        assertTrue(f.state.blockedPicker!!.entries.isEmpty())
        f.controller.blockPickerUser(first, 51) { assertNull(it.value) }
        assertTrue(f.session.writes.isEmpty())
        f.controller.closeBlockedPicker(first)
        f.controller.openBlockedPicker(f.state.token)
        val second = f.state.blockedPicker!!.token
        assertNotEquals(first, second)
        f.controller.blockPickerUser(first, 51) { assertNull(it.value) }
        assertTrue(f.session.writes.isEmpty())
    }

    @Test fun closingPickerDoesNotCancelItsAdmittedWriteAndAcceptedResultRefreshesBlockedPage() {
        val f = Fixture()
        val picker = f.open()
        f.session.reads.last().reply(SettingsResult(SettingsBlockedCandidates(listOf(candidate()), 1, true)))
        f.flush()
        var completed = 0
        f.controller.blockPickerUser(picker, 51) { completed++ }
        val write = f.session.writes.single()
        f.controller.closeBlockedPicker(picker)
        assertTrue(write.current(), "Closing a chooser only retires reads, not its parent security write.")
        write.terminal(SettingsSpecialWriteOutcome.ACCEPTED, SettingsResult(Unit))
        f.flush()
        assertEquals(SettingsSpecialWriteOutcome.ACCEPTED, f.controller.lastMutationReceipt?.outcome)
        assertEquals(2, f.session.pages.size, "Native acceptance must reread the blocked list from offset zero.")
        assertEquals(0, f.session.pages.last().first)
        f.session.pages.last().second(SettingsResult(SettingsBlockedPage(listOf(SettingsBlockedSender(51, false)), 1, 1, true)))
        f.flush()
        assertEquals(listOf(51L), f.state.blocked.map { it.id })
        assertEquals(1, completed)
    }

    @Test fun aTerminalAfterParentReplacementIsRecordedWithoutPublishingIntoTheNewPage() {
        for (outcome in listOf(SettingsSpecialWriteOutcome.ACCEPTED, SettingsSpecialWriteOutcome.REJECTED)) {
            val f = Fixture()
            val picker = f.open()
            f.session.reads.last().reply(SettingsResult(SettingsBlockedCandidates(listOf(candidate()), 1, true)))
            f.flush()
            var completed = 0
            f.controller.blockPickerUser(picker, 51) { completed++ }
            val pending = f.session.writes.single()
            f.controller.open(SettingsSecuritySection.WEBSITES)
            val replacement = f.state
            pending.terminal(outcome, if (outcome == SettingsSpecialWriteOutcome.ACCEPTED) SettingsResult(Unit)
                else SettingsResult(error = SettingsError.FORBIDDEN))
            f.flush()
            assertEquals(outcome, f.controller.lastMutationReceipt?.outcome)
            assertEquals(replacement, f.state)
            assertEquals(0, completed)
            assertEquals(1, f.session.pages.size, "A retired write cannot initiate its old blocked-page readback.")
        }
    }

    @Test fun unknownKeepsThePhysicalSlotUntilItsLaterRealTerminal() {
        val f = Fixture()
        val picker = f.open()
        f.session.reads.last().reply(SettingsResult(SettingsBlockedCandidates(listOf(candidate()), 1, true)))
        f.flush()
        val results = mutableListOf<SettingsResult<Unit>>()
        f.controller.blockPickerUser(picker, 51, results::add)
        val pending = f.session.writes.single()
        pending.terminal(SettingsSpecialWriteOutcome.INDETERMINATE, SettingsResult(error = SettingsError.RESULT_UNKNOWN))
        f.flush()
        assertEquals(SettingsError.RESULT_UNKNOWN, results.single().error)
        assertEquals(SettingsSpecialWriteOutcome.INDETERMINATE, f.controller.lastMutationReceipt?.outcome)
        var refusal: SettingsResult<Unit>? = null
        f.controller.blockPickerUser(picker, 51) { refusal = it }
        assertNotNull(refusal)
        assertNull(refusal?.value)
        assertEquals(1, f.session.writes.size, "Unknown delivery cannot authorize a repeat physical write.")
        pending.terminal(SettingsSpecialWriteOutcome.ACCEPTED, SettingsResult(Unit))
        f.flush()
        assertEquals(SettingsSpecialWriteOutcome.ACCEPTED, f.controller.lastMutationReceipt?.outcome)
        assertEquals(1, f.session.writes.size)
        assertEquals(1, results.size, "The caller receives no second form completion from a late terminal.")
    }

    private class Fixture {
        val session = Session()
        var state = SettingsSecuritySnapshot()
        private val queue = ArrayDeque<() -> Unit>()
        val controller = SettingsSecurityController(7, session, queue::addLast, { state = it })
        fun flush() { while (queue.isNotEmpty()) queue.removeFirst()() }
        fun open(): SettingsBlockedPickerToken {
            controller.open(SettingsSecuritySection.BLOCKED)
            session.pages.last().second(SettingsResult(SettingsBlockedPage(emptyList(), 0, 0, true)))
            flush()
            controller.openBlockedPicker(state.token)
            return assertNotNull(state.blockedPicker).token
        }
    }
    private data class Read(val current: () -> Boolean, val reply: (SettingsResult<SettingsBlockedCandidates>) -> Unit)
    private data class Write(val userId: Long, val current: () -> Boolean, val reply: (SettingsResult<Unit>) -> Unit) {
        fun terminal(outcome: SettingsSpecialWriteOutcome, value: SettingsResult<Unit>) =
            SettingsSpecialMutationContext.withReceipt(SettingsSpecialWriteReceipt(outcome, value.error)) { reply(value) }
    }
    private class Session : TdSettingsSession {
        val reads = mutableListOf<Read>()
        val writes = mutableListOf<Write>()
        val pages = mutableListOf<Pair<Int, (SettingsResult<SettingsBlockedPage>) -> Unit>>()
        override fun blockedCandidates(query: SettingsBlockedPeerQuery, isCurrent: () -> Boolean, result: (SettingsResult<SettingsBlockedCandidates>) -> Unit) { reads += Read(isCurrent, result) }
        override fun blockSecurity(userId: Long, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) { writes += Write(userId, isCurrent, result) }
        override fun blockedPage(offset: Int, isCurrent: () -> Boolean, result: (SettingsResult<SettingsBlockedPage>) -> Unit) { pages += offset to result }
        override fun websites(isCurrent: () -> Boolean, result: (SettingsResult<SettingsWebsitesData>) -> Unit) { }
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
        fun candidate(id: Long = 51, blocked: Boolean = false) = SettingsBlockedCandidate(id, "Native user $id", blocked)
    }
}
