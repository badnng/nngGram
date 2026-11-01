package com.badnng.moe

import com.badnng.moe.chat.*
import kotlin.test.*

class SendAnimationTrackerTest {
    @Test
    fun initialHistoryAndLaterPagesNeverBecomeNewSendEntrances() {
        val tracker = SendAnimationTracker()
        val existing = message(30, send = 1, delivery = MessageDelivery.SENT)
        assertTrue(tracker.update(snapshot(existing), true, true).plans.isEmpty())
        tracker.update(snapshot(existing).copy(historyLoading = true), true, true)
        val older = message(10, send = 2, delivery = MessageDelivery.SENT)
        assertTrue(tracker.update(snapshot(older, existing), true, true).plans.isEmpty())
        assertTrue(tracker.update(snapshot(older, existing), true, true).plans.isEmpty())
    }

    @Test
    fun identicalTextSendsHaveIndependentEntrancesAndOutOfOrderConfirmationsNeverReplayThem() {
        val tracker = readyTracker()
        val first = message(-1, send = 11)
        val second = message(-2, send = 12)
        val frame = tracker.update(snapshot(first, second), true, true)
        assertEquals(setOf(-1L, -2L), frame.plans.keys)
        frame.plans.values.forEach { assertTrue(tracker.claim(it)) }

        val secondConfirmed = second.copy(id = 100, delivery = MessageDelivery.SENT)
        val firstConfirmed = first.copy(id = 200, delivery = MessageDelivery.SENT)
        val confirmed = tracker.update(snapshot(secondConfirmed, firstConfirmed), true, true)
        assertEquals(frame.plans, confirmed.plans)
        confirmed.plans.values.forEach { assertFalse(tracker.claim(it)) }
        assertEquals(frame.placementRevision, confirmed.placementRevision,
            "Replacing native ids must not restart placement for the whole conversation.")
    }

    @Test
    fun realRetryAcceptanceEntersOnceAndConfirmationKeepsTheSameRow() {
        val tracker = SendAnimationTracker()
        val failed = message(50, send = 1, delivery = MessageDelivery.FAILED)
        tracker.update(snapshot(failed), true, true)
        val requestPending = tracker.update(snapshot(failed).copy(retryingIds = setOf(50)), true, true)
        assertTrue(requestPending.plans.isEmpty())
        val retry = failed.copy(id = -3, delivery = MessageDelivery.PENDING, sendAnimationId = 2)
        val accepted = tracker.update(snapshot(retry), true, true)
        val plan = assertNotNull(accepted.plans[50])
        assertTrue(plan.alreadyVisible)
        assertTrue(tracker.claim(plan))
        val confirmed = tracker.update(snapshot(retry.copy(id = 90, delivery = MessageDelivery.SENT)), true, true)
        assertEquals(plan, confirmed.plans[50])
        assertFalse(tracker.claim(plan))
    }

    @Test
    fun unrelatedOutgoingHistoryWithMatchingTextDoesNotBorrowAnActiveSendIdentity() {
        val tracker = readyTracker()
        val history = message(10, send = null, delivery = MessageDelivery.SENT)
        val actualSend = message(-1, send = 7)
        val frame = tracker.update(snapshot(history, actualSend).copy(sendPending = true), true, true)
        assertEquals(setOf(-1L), frame.plans.keys)
    }

    @Test
    fun hiddenScrolledOrJumpingSendsAreConsumedWithoutPlayingLater() {
        listOf("scrolled", "inactive", "jump", "gap").forEach { scenario ->
            val tracker = readyTracker()
            val sent = message(-1, send = 1)
            var state = snapshot(sent)
            if (scenario == "jump") state = state.copy(jumpMessageId = 30, jumpRevision = 1)
            if (scenario == "gap") state = state.copy(historyHasNewer = true)
            assertTrue(tracker.update(state, scenario != "scrolled", scenario != "inactive").plans.isEmpty())
            assertTrue(tracker.update(snapshot(sent), true, true).plans.isEmpty())
        }
    }

    @Test
    fun invalidNativeIdsAndIncomingMessagesCannotAnimateAsSends() {
        val tracker = readyTracker()
        val frame = tracker.update(snapshot(message(0, 1), message(10, 2).copy(outgoing = false)), true, true)
        assertTrue(frame.plans.isEmpty())
    }

    private fun readyTracker() = SendAnimationTracker().also { it.update(snapshot(), true, true) }
    private fun snapshot(vararg messages: ChatMessage) = ChatSnapshot(token = ChatToken(1, 1, 10), messages = messages.toList())
    private fun message(id: Long, send: Long?, delivery: MessageDelivery = MessageDelivery.PENDING) =
        ChatMessage(id, 10, outgoing = true, text = "same synthetic text", delivery = delivery, sendAnimationId = send)
}
