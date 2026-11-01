package com.badnng.moe

import com.badnng.moe.chat.*
import kotlin.test.*

class IncomingAnimationTrackerTest {
    @Test
    fun onlyAnExplicitNativeArrivalEntersAndHistoryNeverDoes() {
        val tracker = ready()
        val frame = tracker.update(state(message(10), message(20, 1)), true, true)
        assertEquals(setOf(20L), frame.keys)
        assertFalse(frame.getValue(20).alreadyVisible)
        assertTrue(tracker.claim(frame.getValue(20)))
        val updated = tracker.update(state(message(5), message(10), message(20, 1).copy(editDate = 5)), true, true)
        assertEquals(frame, updated)
        assertFalse(tracker.claim(updated.getValue(20)), "Content and read updates cannot replay an entrance.")
    }

    @Test
    fun firstSnapshotAndEverySuppressedArrivalAreConsumedWithoutLaterReplay() {
        val initial = IncomingAnimationTracker()
        assertTrue(initial.update(state(message(1, 1)), true, true).isEmpty())
        assertTrue(initial.update(state(message(1, 1)), true, true).isEmpty())
        listOf("inactive", "scrolled", "history", "newer", "gap", "jump").forEach { scenario ->
            val tracker = ready()
            val arrival = state(message(20, 1))
            val hidden = when (scenario) {
                "history" -> arrival.copy(historyLoading = true)
                "newer" -> arrival.copy(newerLoading = true)
                "gap" -> arrival.copy(historyHasNewer = true)
                "jump" -> arrival.copy(jumpMessageId = 20, jumpRevision = 1)
                else -> arrival
            }
            assertTrue(tracker.update(hidden, scenario != "scrolled", scenario != "inactive").isEmpty(), scenario)
            assertTrue(tracker.update(arrival, true, true).isEmpty(), scenario)
            assertTrue(tracker.update(arrival, true, true).isEmpty(), scenario)
        }
    }

    @Test
    fun lastMessageMayPrecedeTheAuthoritativeNativeEventWithoutCollapsingItsRow() {
        val tracker = ready()
        tracker.update(state(message(20)), true, true)
        val plan = tracker.update(state(message(20, 1)), true, true).getValue(20)
        assertTrue(plan.alreadyVisible)
        assertTrue(tracker.claim(plan))
        assertFalse(tracker.claim(plan))
    }

    @Test
    fun sequentialAndCoalescedEventsKeepIndependentIdentitiesAndNativeReplacementDoesNotReplay() {
        val tracker = ready()
        val first = message(20, 1)
        val firstPlan = tracker.update(state(first), true, true).getValue(20)
        assertTrue(tracker.claim(firstPlan))
        val frame = tracker.update(state(first.copy(id = 21), message(30, 2), message(40, 3)), true, true)
        assertEquals(setOf(20L, 30L, 40L), frame.keys)
        assertEquals(firstPlan, frame.getValue(20))
        assertFalse(tracker.claim(frame.getValue(20)))
        assertTrue(tracker.claim(frame.getValue(30)))
        assertTrue(tracker.claim(frame.getValue(40)))
        tracker.update(state(), true, true)
        assertTrue(tracker.update(state(first), true, true).isEmpty(),
            "An evicted row must not replay when LazyColumn composes it again.")
        assertFalse(tracker.claim(firstPlan))
    }

    @Test
    fun outgoingAndInvalidIdentitiesNeverUseTheIncomingEffectAndDeactivationCancelsPlans() {
        val tracker = ready()
        assertTrue(tracker.update(state(message(0, 1), message(10, 2).copy(outgoing = true)), true, true).isEmpty())
        assertTrue(tracker.update(state(message(20, 3)), true, true).isNotEmpty())
        assertTrue(tracker.update(state(message(20, 3)), true, false).isEmpty())
        assertTrue(tracker.update(state(message(20, 3)), true, true).isEmpty())
    }

    private fun ready() = IncomingAnimationTracker().also { it.update(state(), true, true) }
    private fun message(id: Long, arrival: Long? = null) = ChatMessage(id, 10, incomingAnimationId = arrival)
    private fun state(vararg messages: ChatMessage) = ChatSnapshot(token = ChatToken(1, 1, 10), messages = messages.toList())
}
