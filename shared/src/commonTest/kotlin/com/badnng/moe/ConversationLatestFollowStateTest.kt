package com.badnng.moe

import com.badnng.moe.chat.ChatItem
import com.badnng.moe.chat.ChatMessage
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.ChatToken
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConversationLatestFollowStateTest {
    private val token = ChatToken(1, 1, 7)
    private val initial = ChatSnapshot(enabled = true, token = token, selected = ChatItem(7, "fixture"),
        messages = listOf(ChatMessage(1, 7)))

    @Test
    fun keyedInsertionAndReflowKeepPreviousBottomIntent() {
        val state = ConversationLatestFollowState(initial)
        assertTrue(state.observe(initial, atLatest = true, scrolling = false, interactive = true))
        val appended = initial.copy(messages = initial.messages + ChatMessage(2, 7))
        // The old bottom row remains keyed into place before the observer runs.
        assertTrue(state.observe(appended, atLatest = false, scrolling = false, interactive = true))
        assertTrue(state.observe(appended, atLatest = false, scrolling = false, interactive = true))
    }

    @Test
    fun smallDeliberateScrollCancelsFollowingUntilTheActualBottom() {
        val state = ConversationLatestFollowState(initial)
        // A 24 px touchpad movement can be below the floating button's threshold.
        assertFalse(state.observe(initial, atLatest = false, scrolling = true, interactive = true))
        assertFalse(state.observe(initial.copy(messages = initial.messages + ChatMessage(2, 7)),
            atLatest = false, scrolling = false, interactive = true))
        assertFalse(state.observe(initial, atLatest = true, scrolling = true, interactive = true))
        assertTrue(state.observe(initial, atLatest = true, scrolling = false, interactive = true))
    }

    @Test
    fun explicitSendFollowsButLateAcknowledgementsCannotOverrideLaterReading() {
        val state = ConversationLatestFollowState(initial)
        assertFalse(state.observe(initial, atLatest = false, scrolling = true, interactive = true))
        val pending = initial.copy(sendPending = true, sendAttemptRevision = 1, sendErrorChatId = token.chatId)
        assertTrue(state.observe(pending, atLatest = false, scrolling = false, interactive = true))
        assertFalse(state.observe(pending, atLatest = false, scrolling = true, interactive = true))
        val accepted = pending.copy(sendPending = false, acceptedSendId = 1, acceptedSendChatId = token.chatId,
            messages = initial.messages + ChatMessage(2, 7, outgoing = true, sendAnimationId = 5))
        assertFalse(state.observe(accepted, atLatest = false, scrolling = false, interactive = true))
        assertFalse(state.observe(accepted.copy(messages = accepted.messages.map {
            if (it.id == 2L) it.copy(id = 3, presentationId = 2) else it
        }), atLatest = false, scrolling = false, interactive = true))
        // A subsequent explicit request is a new return-to-bottom action.
        assertTrue(state.observe(accepted.copy(sendPending = true, sendAttemptRevision = 2),
            atLatest = false, scrolling = false, interactive = true))
    }

    @Test
    fun rejectedRequestsAndOtherChatRequestsDoNotLeaveAnOlderAnchor() {
        val state = ConversationLatestFollowState(initial)
        state.observe(initial, atLatest = false, scrolling = true, interactive = true)
        assertFalse(state.observe(initial.copy(sendAttemptRevision = 1),
            atLatest = false, scrolling = false, interactive = true))
        assertFalse(state.observe(initial.copy(sendAttemptRevision = 2, sendPending = true, sendErrorChatId = 8),
            atLatest = false, scrolling = false, interactive = true))
    }

    @Test
    fun coalescedPendingAndAcceptanceStillIdentifyOnlyOneExplicitSend() {
        val state = ConversationLatestFollowState(initial)
        state.observe(initial, atLatest = false, scrolling = true, interactive = true)
        val accepted = initial.copy(sendAttemptRevision = 1, sendErrorChatId = token.chatId,
            acceptedSendId = 1, acceptedSendChatId = token.chatId)
        assertTrue(state.observe(accepted, atLatest = false, scrolling = false, interactive = true))
        assertFalse(state.observe(accepted, atLatest = false, scrolling = true, interactive = true))
        assertFalse(state.observe(accepted.copy(acceptedSendId = 2),
            atLatest = false, scrolling = false, interactive = true))
    }

    @Test
    fun immediateReplyJumpsAndReturnActionsExplicitlyOwnTheirAnchors() {
        val state = ConversationLatestFollowState(initial)
        state.navigate(atLatest = false)
        assertFalse(state.observe(initial, atLatest = false, scrolling = false, interactive = true))
        state.navigate(atLatest = true)
        assertTrue(state.observe(initial, atLatest = true, scrolling = false, interactive = true))
    }

    @Test
    fun completedDiscreteWheelInputOwnsTheAnchorWithoutAnObservedScrollingFrame() {
        val state = ConversationLatestFollowState(initial)
        // onPostScroll reports the consumed 24 px movement synchronously; a desktop
        // dispatch can finish before a snapshot observer sees isScrollInProgress.
        state.navigate(atLatest = false)
        assertFalse(state.observe(initial, atLatest = false, scrolling = false, interactive = true))
        assertFalse(state.observe(initial.copy(messages = initial.messages + ChatMessage(2, 7)),
            atLatest = false, scrolling = false, interactive = true))
        state.navigate(atLatest = true)
        assertTrue(state.observe(initial, atLatest = true, scrolling = false, interactive = true))
    }

    @Test
    fun nativeGapAndJumpWindowsRemainInChargeOfTheirPosition() {
        val state = ConversationLatestFollowState(initial)
        assertFalse(state.observe(initial.copy(historyHasNewer = true, sendPending = true,
            sendAttemptRevision = 1, sendErrorChatId = token.chatId),
            atLatest = true, scrolling = false, interactive = true))
        assertFalse(state.observe(initial.copy(sendAttemptRevision = 1),
            atLatest = true, scrolling = false, interactive = true))
        assertFalse(state.observe(initial.copy(jumpMessageId = 1, jumpRevision = 1),
            atLatest = true, scrolling = false, interactive = true))
    }

    @Test
    fun completedJumpKeepsItsAnchorUntilTheReaderReturnsToTheRealBottom() {
        val state = ConversationLatestFollowState(initial)
        val jumped = initial.copy(jumpMessageId = 1, jumpRevision = 1)
        assertFalse(state.observe(jumped, atLatest = false, scrolling = false, interactive = true,
            positioning = false))
        assertFalse(state.observe(jumped, atLatest = true, scrolling = false, interactive = true,
            positioning = false), "Layout completion alone cannot take over the reading anchor.")
        state.navigate(atLatest = true)
        assertTrue(state.observe(jumped, atLatest = true, scrolling = false, interactive = true,
            positioning = false))
        assertTrue(state.observe(jumped.copy(messages = jumped.messages + ChatMessage(2, 7)),
            atLatest = false, scrolling = false, interactive = true, positioning = false))
        assertFalse(state.observe(jumped.copy(jumpRevision = 2), atLatest = false, scrolling = false,
            interactive = true, positioning = false), "Reselecting even the same target owns a new jump.")
    }

    @Test
    fun measuredJumpAtLatestFollowsWhileAnEarlierMeasuredAnchorDoesNot() {
        val jumped = initial.copy(jumpMessageId = 1, jumpRevision = 1)
        val state = ConversationLatestFollowState(jumped)
        state.completeJump(jumped, atLatest = true)
        assertTrue(state.observe(jumped.copy(messages = jumped.messages + ChatMessage(2, 7)),
            atLatest = false, scrolling = false, interactive = true, positioning = false))
        state.completeJump(jumped.copy(jumpRevision = 2), atLatest = false)
        assertFalse(state.observe(jumped.copy(jumpRevision = 2), atLatest = false, scrolling = false,
            interactive = true, positioning = false))
        state.completeJump(jumped.copy(token = token.copy(selection = 2)), atLatest = true)
        assertFalse(state.observe(jumped.copy(jumpRevision = 2), atLatest = true, scrolling = false,
            interactive = true, positioning = false))
    }

    @Test
    fun completedJumpCannotFollowAcrossANewerHistoryGap() {
        val jumped = initial.copy(jumpMessageId = 1, jumpRevision = 1, historyHasNewer = true)
        val state = ConversationLatestFollowState(jumped)
        state.navigate(atLatest = true)
        assertFalse(state.observe(jumped, atLatest = true, scrolling = false, interactive = true,
            positioning = false))
        val contiguous = jumped.copy(historyHasNewer = false)
        assertFalse(state.observe(contiguous, atLatest = true, scrolling = false, interactive = true,
            positioning = false))
        state.navigate(atLatest = true)
        assertTrue(state.observe(contiguous, atLatest = true, scrolling = false, interactive = true,
            positioning = false))
    }

    @Test
    fun loadingSelectionAndPageOwnershipGateScheduledScrolls() {
        val state = ConversationLatestFollowState(initial)
        assertFalse(state.observe(initial.copy(historyLoading = true),
            atLatest = true, scrolling = false, interactive = true))
        assertFalse(state.observe(initial.copy(newerLoading = true),
            atLatest = true, scrolling = false, interactive = true))
        assertFalse(state.observe(initial, atLatest = true, scrolling = false, interactive = false))
        assertFalse(state.observe(initial.copy(token = token.copy(selection = 2)),
            atLatest = true, scrolling = false, interactive = true))
        assertFalse(state.observe(initial.copy(selected = ChatItem(8, "other fixture")),
            atLatest = true, scrolling = false, interactive = true))
        assertTrue(state.observe(initial, atLatest = true, scrolling = false, interactive = true))
    }
}
