package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import kotlin.test.*

class ReportSpamControllerTest {
    @Test fun onlyTheNativeSpamBarOnAJoinedGroupAuthorizesThisCombinedAction() {
        val f = Fixture()
        assertTrue(f.state.reportSpam.available)
        f.controller.reportSpamAndLeave(f.state.token.copy(selection = -1))
        assertTrue(f.session.capabilities.isEmpty())
        for (chat in listOf(
            f.chat.copy(actionBar = null, canBeReported = true),
            f.chat.copy(actionBar = ChatActionBar(ChatActionBarKind.REPORT_ADD_BLOCK)),
            f.chat.copy(actionBar = spamBar.copy(canUnarchive = true)),
            f.chat.copy(canLeave = false),
            f.chat.copy(supergroupId = null, privateUserId = 90),
        )) {
            f.event(ChatEvent.NewChat(chat))
            assertFalse(f.state.reportSpam.available)
            f.controller.reportSpamAndLeave(f.state.token)
            assertTrue(f.session.capabilities.isEmpty())
        }
        assertTrue(f.session.reports.isEmpty())
        assertTrue(f.session.leaves.isEmpty())
    }

    @Test fun aFreshNativeReadCanRevokeTheCachedBarBeforeAnyReport() {
        val f = Fixture()
        f.controller.reportSpamAndLeave(f.state.token)
        assertTrue(f.state.reportSpam.reportPending)
        f.capability(ChatReportSpamCapability(canLeave = true, canBeReported = true))
        assertFalse(f.state.reportSpam.available)
        assertEquals(ChatError.FORBIDDEN, f.state.reportSpam.error)
        assertTrue(f.state.reportSpam.visible)
        assertTrue(f.session.reports.isEmpty())
    }

    @Test fun reportAcceptanceSurvivesTheOptimisticBarRemovalAndLeaveRetryNeverReportsAgain() {
        val f = Fixture()
        f.controller.reportSpamAndLeave(f.state.token)
        f.capability()
        assertEquals(1L, f.session.reports.single().chatId)
        f.event(ChatEvent.ActionBar(1, null))
        assertTrue(f.state.reportSpam.visible)
        assertTrue(f.state.reportSpam.reportPending)
        f.session.reports.single().result(ChatResult(Unit)); f.flush()
        assertTrue(f.state.reportSpam.reported)
        assertTrue(f.state.reportSpam.leavePending)
        f.capability(ChatReportSpamCapability(canLeave = true))
        assertEquals(1L, f.session.leaves.single().chatId)
        f.session.leaves.single().result(ChatResult(error = ChatError.REQUEST_FAILED)); f.flush()
        assertTrue(f.state.reportSpam.reported)
        assertFalse(f.state.reportSpam.left)
        assertEquals(ChatError.REQUEST_FAILED, f.state.reportSpam.error)
        f.controller.dismissSpamBar(f.state.token)
        assertTrue(f.session.dismissals.isEmpty())
        f.controller.reportSpamAndLeave(f.state.token)
        f.capability(ChatReportSpamCapability(canLeave = true))
        assertEquals(1, f.session.reports.size)
        assertEquals(2, f.session.leaves.size)
        f.session.leaves.last().result(ChatResult(Unit)); f.flush()
        assertTrue(f.completed(1).reportSpam.left)
        assertFalse(f.state.reportSpam.visible)
        assertNull(f.state.selected)
        assertNull(f.state.token.chatId)
        assertEquals(listOf(1L), f.session.closed)
        assertEquals(100L, f.state.knownChats.first { it.id == 1L }.positions[ChatListRef()], "No list position is fabricated")
        f.select(1)
        assertTrue(f.state.reportSpam.completed, "Revisiting preserves accepted report and leave phases")
        assertTrue(assertNotNull(f.state.selected).canLeave, "Membership remains native-update driven, not fabricated from an ack")
        f.controller.reportSpamAndLeave(f.state.token)
        assertEquals(1, f.session.reports.size)
        assertEquals(2, f.session.leaves.size)
    }

    @Test fun failureAndBarDisappearanceNeverCountAsAnAcceptedReportOrStartALeave() {
        val f = Fixture()
        f.controller.reportSpamAndLeave(f.state.token); f.capability()
        f.event(ChatEvent.ActionBar(1, null))
        f.session.reports.single().result(ChatResult(error = ChatError.REQUEST_FAILED)); f.flush()
        assertFalse(f.state.reportSpam.reported)
        assertTrue(f.state.reportSpam.visible)
        assertTrue(f.session.leaves.isEmpty())
        f.controller.reportSpamAndLeave(f.state.token)
        assertEquals(1, f.session.reports.size)
        // TDLib reloads the action bar on a failed report; only its real restoration permits retry.
        f.event(ChatEvent.ActionBar(1, spamBar))
        f.controller.reportSpamAndLeave(f.state.token); f.capability()
        assertEquals(2, f.session.reports.size)
        assertTrue(f.session.leaves.isEmpty())
    }

    @Test fun closingOnlyRemovesTheBarAndAnAbsentBarOnlyClearsTheLocalFailure() {
        val f = Fixture()
        f.controller.dismissSpamBar(f.state.token)
        f.capability()
        assertEquals(1L, f.session.dismissals.single().chatId)
        assertTrue(f.session.reports.isEmpty())
        assertTrue(f.session.leaves.isEmpty())
        f.session.dismissals.single().result(ChatResult(error = ChatError.REQUEST_FAILED)); f.flush()
        assertEquals(ChatError.REQUEST_FAILED, f.state.reportSpam.error)
        f.controller.dismissSpamBar(f.state.token)
        f.capability(ChatReportSpamCapability(canLeave = true))
        assertFalse(f.state.reportSpam.visible)
        assertEquals(1, f.session.dismissals.size)
        assertTrue(f.session.reports.isEmpty())
    }

    @Test fun switchingChatsOrANewerNativeCapabilityDuringValidationRevokesTheMutation() {
        val f = Fixture()
        val firstToken = f.state.token
        f.controller.reportSpamAndLeave(firstToken)
        f.select(2)
        f.capability()
        assertTrue(f.session.reports.isEmpty())
        assertEquals(2L, f.state.reportSpam.chatId)
        assertFalse(f.state.reportSpam.reportPending)
        f.select(1)
        f.controller.reportSpamAndLeave(f.state.token)
        f.event(ChatEvent.ActionBar(1, null))
        f.capability()
        assertEquals(ChatError.NOT_READY, f.state.reportSpam.error)
        assertTrue(f.session.reports.isEmpty())
        f.controller.reportSpamAndLeave(firstToken)
        assertEquals(2, f.session.capabilities.size)
    }

    @Test fun aLateAcceptedReportIsRememberedButCannotLeaveAnotherSelectedChat() {
        val f = Fixture()
        f.controller.reportSpamAndLeave(f.state.token); f.capability()
        val submitted = f.session.reports.single()
        f.select(2)
        submitted.result(ChatResult(Unit)); submitted.result(ChatResult(error = ChatError.REQUEST_FAILED)); f.flush()
        assertTrue(f.session.leaves.isEmpty())
        assertEquals(2L, f.state.selected?.id)
        assertFalse(f.state.reportSpam.reported)
        f.select(1)
        assertTrue(f.state.reportSpam.reported)
        f.controller.reportSpamAndLeave(f.state.token)
        f.capability(ChatReportSpamCapability(canLeave = true))
        assertEquals(1, f.session.reports.size)
        assertEquals(1, f.session.leaves.size)
    }

    @Test fun duplicateCallbacksCannotRepeatRequestsAndAClosedEngineCannotContinueTheChain() {
        val f = Fixture()
        f.controller.reportSpamAndLeave(f.state.token)
        val read = f.session.capabilities.single()
        read.result(ChatResult(eligible)); read.result(ChatResult(eligible)); f.flush()
        assertEquals(1, f.session.reports.size)
        f.controller.deactivate()
        f.session.reports.single().result(ChatResult(Unit)); f.flush()
        assertTrue(f.session.leaves.isEmpty())
        assertEquals(1, f.session.capabilities.size)
    }

    @Test fun alreadyLeftNativeMembershipFinishesAnAcceptedReportWithoutAnotherLeave() {
        val f = Fixture()
        f.controller.reportSpamAndLeave(f.state.token); f.capability()
        f.session.reports.single().result(ChatResult(Unit)); f.flush()
        f.capability(ChatReportSpamCapability(canLeave = false, isMember = false))
        assertTrue(f.completed(1).reportSpam.left)
        assertNull(f.state.token.chatId)
        assertTrue(f.session.leaves.isEmpty())
    }

    @Test fun anInactiveButStillJoinedGroupIsNotMistakenForAnAcceptedLeave() {
        val f = Fixture()
        f.controller.reportSpamAndLeave(f.state.token); f.capability()
        f.session.reports.single().result(ChatResult(Unit)); f.flush()
        f.capability(eligible.copy(canLeave = false, isMember = true))
        assertFalse(f.state.reportSpam.left)
        assertEquals(ChatError.FORBIDDEN, f.state.reportSpam.error)
        assertTrue(f.session.leaves.isEmpty())
    }

    @Test fun aLeaveAcknowledgementCannotOverwriteANewerNativeBarOrRejoinedMembership() {
        val f = Fixture()
        f.controller.reportSpamAndLeave(f.state.token); f.capability()
        f.session.reports.single().result(ChatResult(Unit)); f.flush(); f.capability()
        val newer = ChatActionBar(ChatActionBarKind.INVITE_MEMBERS)
        f.event(ChatEvent.ActionBar(1, newer))
        f.event(ChatEvent.GroupMembership(1, true))
        f.session.leaves.single().result(ChatResult(Unit)); f.flush()
        val currentNative = f.state.knownChats.first { it.id == 1L }
        assertEquals(newer, currentNative.actionBar)
        assertTrue(currentNative.isJoinedGroup)
        assertTrue(currentNative.canLeave)
        assertNull(f.state.token.chatId)
        assertEquals(2, f.session.capabilities.size)
    }

    @Test fun aBasicGroupResumesOnlyItsRemainingSelfHistoryCleanupAfterPartialSuccess() {
        val f = Fixture()
        f.event(ChatEvent.NewChat(f.chat.copy(supergroupId = null, basicGroupId = 11)))
        val basic = eligible.copy(needsHistoryRemoval = true, canDeleteHistory = true)
        f.controller.reportSpamAndLeave(f.state.token); f.capability(basic)
        f.session.reports.single().result(ChatResult(Unit)); f.flush()
        f.capability(basic)
        f.session.leaves.single().result(ChatResult(Unit)); f.flush()
        assertTrue(f.state.reportSpam.left)
        assertTrue(f.state.reportSpam.visible)
        assertTrue(f.state.reportSpam.clearPending)
        f.capability(basic.copy(canLeave = false, actionBar = null, isMember = false))
        assertEquals(1, f.session.clears.size)
        f.session.clears.single().result(ChatResult(error = ChatError.REQUEST_FAILED)); f.flush()
        assertFalse(f.state.reportSpam.completed)
        assertTrue(f.state.reportSpam.reported)
        f.controller.reportSpamAndLeave(f.state.token)
        f.capability(basic.copy(canLeave = false, actionBar = null, isMember = false))
        assertEquals(1, f.session.reports.size)
        assertEquals(1, f.session.leaves.size)
        assertEquals(2, f.session.clears.size)
        f.session.clears.last().result(ChatResult(Unit)); f.flush()
        assertTrue(f.completed(1).reportSpam.cleared)
        assertTrue(f.completed(1).reportSpam.completed)
        assertFalse(f.state.reportSpam.visible)
        assertNull(f.state.selected)
        assertNull(f.state.token.chatId)
    }

    @Test fun aLateLeaveCannotClearHistoryAfterSwitchingAndCleanupRequiresFreshBasicSelfDeletePermission() {
        val f = Fixture()
        f.event(ChatEvent.NewChat(f.chat.copy(supergroupId = null, basicGroupId = 11)))
        val basic = eligible.copy(needsHistoryRemoval = true, canDeleteHistory = true)
        f.controller.reportSpamAndLeave(f.state.token); f.capability(basic)
        f.session.reports.single().result(ChatResult(Unit)); f.flush(); f.capability(basic)
        f.select(2)
        f.session.leaves.single().result(ChatResult(Unit)); f.flush()
        assertTrue(f.session.clears.isEmpty())
        f.select(1)
        assertTrue(f.state.reportSpam.left)
        for (denied in listOf(
            basic.copy(canLeave = false, canDeleteHistory = false, isMember = false),
            basic.copy(canLeave = false, needsHistoryRemoval = false, isMember = false),
            basic,
        )) {
            f.controller.reportSpamAndLeave(f.state.token); f.capability(denied)
            assertEquals(ChatError.FORBIDDEN, f.state.reportSpam.error)
            assertTrue(f.session.clears.isEmpty())
        }
        assertEquals(1, f.session.reports.size)
        assertEquals(1, f.session.leaves.size)
    }

    @Test fun aLateCompletedCleanupCannotCloseAnotherConversationOrAReopenedToken() {
        val f = Fixture()
        f.event(ChatEvent.NewChat(f.chat.copy(supergroupId = null, basicGroupId = 11)))
        val basic = eligible.copy(needsHistoryRemoval = true, canDeleteHistory = true)
        f.controller.reportSpamAndLeave(f.state.token); f.capability(basic)
        f.session.reports.single().result(ChatResult(Unit)); f.flush(); f.capability(basic)
        f.session.leaves.single().result(ChatResult(Unit)); f.flush()
        f.capability(basic.copy(canLeave = false, isMember = false))
        val submitted = f.session.clears.single()
        f.select(2)
        f.select(1)
        val reopened = f.state.token
        submitted.result(ChatResult(Unit)); submitted.result(ChatResult(error = ChatError.REQUEST_FAILED)); f.flush()
        assertEquals(reopened, f.state.token)
        assertEquals(1L, f.state.selected?.id)
        assertTrue(f.state.reportSpam.completed)
        assertFalse(f.state.reportSpam.visible)
        f.controller.reportSpamAndLeave(reopened)
        assertEquals(1, f.session.reports.size)
        assertEquals(1, f.session.leaves.size)
        assertEquals(1, f.session.clears.size)
    }

    private class Fixture {
        val session = FakeSession()
        private val queue = ArrayDeque<() -> Unit>()
        var state = ChatSnapshot()
        val published = mutableListOf<ChatSnapshot>()
        val chat = ChatItem(1, "synthetic group", order = 100, supergroupId = 11, isJoinedGroup = true, actionBar = spamBar)
        val controller = ChatController(5, session, queue::addLast, { state = it; published += it })
        init {
            controller.start(); flush()
            event(ChatEvent.NewChat(chat))
            event(ChatEvent.NewChat(chat.copy(id = 2, supergroupId = 12)))
            select(1)
        }
        fun event(event: ChatEvent) = controller.event(event)
        fun select(chatId: Long) { controller.select(state.token, chatId); flush() }
        fun flush() { while (queue.isNotEmpty()) queue.removeFirst().invoke() }
        fun completed(chatId: Long) = published.last { it.token.chatId == chatId && it.reportSpam.completed }
        fun capability(value: ChatReportSpamCapability = eligible) {
            session.capabilities.last().result(ChatResult(value)); flush()
        }
    }

    private class FakeSession : TdChatSession {
        val capabilities = mutableListOf<CapabilityRead>()
        val reports = mutableListOf<Mutation>()
        val leaves = mutableListOf<Mutation>()
        val dismissals = mutableListOf<Mutation>()
        val clears = mutableListOf<Mutation>()
        val closed = mutableListOf<Long>()
        override fun reportSpamCapability(chatId: Long, result: (ChatResult<ChatReportSpamCapability>) -> Unit) { capabilities += CapabilityRead(chatId, result) }
        override fun reportSpam(chatId: Long, result: (ChatResult<Unit>) -> Unit) { reports += Mutation(chatId, result) }
        override fun leaveChat(chatId: Long, result: (ChatResult<Unit>) -> Unit) { leaves += Mutation(chatId, result) }
        override fun removeChatActionBar(chatId: Long, result: (ChatResult<Unit>) -> Unit) { dismissals += Mutation(chatId, result) }
        override fun clearReportedBasicGroupHistory(chatId: Long, result: (ChatResult<Unit>) -> Unit) { clears += Mutation(chatId, result) }
        override fun loadChats(limit: Int, result: (ChatResult<ChatLoadResult>) -> Unit) = result(ChatResult(ChatLoadResult(true)))
        override fun history(chatId: Long, fromMessageId: Long, limit: Int, result: (ChatResult<List<ChatMessage>>) -> Unit) = result(ChatResult(emptyList()))
        override fun open(chatId: Long) = Unit
        override fun close(chatId: Long) { closed += chatId }
        override fun sendText(chatId: Long, text: String, result: (ChatResult<ChatMessage>) -> Unit) = error("No send in report fixtures")
        override fun retry(chatId: Long, messageId: Long, result: (ChatResult<List<ChatMessage>>) -> Unit) = error("No retry in report fixtures")
        override fun markRead(chatId: Long, messageIds: List<Long>, result: (ChatResult<Unit>) -> Unit) = result(ChatResult(Unit))
    }
    private data class CapabilityRead(val chatId: Long, val result: (ChatResult<ChatReportSpamCapability>) -> Unit)
    private data class Mutation(val chatId: Long, val result: (ChatResult<Unit>) -> Unit)
    companion object {
        private val spamBar = ChatActionBar(ChatActionBarKind.REPORT_SPAM)
        private val eligible = ChatReportSpamCapability(spamBar, canLeave = true)
    }
}
