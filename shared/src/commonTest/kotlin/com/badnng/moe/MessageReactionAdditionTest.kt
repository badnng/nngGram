package com.badnng.moe

import com.badnng.moe.chat.MessageReaction
import com.badnng.moe.chat.ReactionKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessageReactionAdditionTest {
    @Test fun officialFlightParabolaReachesBothEndpointsAndRisesAboveTheHigherOne() {
        for ((from, to) in listOf(20f to 180f, 180f to 20f, 90f to 90f)) {
            assertEquals(from, reactionFlightTop(from, to, 24f, 0f), 0.001f)
            assertEquals(to, reactionFlightTop(from, to, 24f, 1f), 0.001f)
            val peak = (0..1000).minOf { reactionFlightTop(from, to, 24f, it / 1000f) }
            assertEquals(minOf(from, to) - 24f, peak, 0.01f)
        }
    }

    @Test fun initialHistoryRemountAndCountUpdatesDoNotReplayChosenReactions() {
        val initial = listOf(MessageReaction("👍", 2, true))
        val additions = MessageReactionAddition(initial)
        assertTrue(additions.update(initial, true).isEmpty())
        assertTrue(additions.update(listOf(initial.single().copy(totalCount = 3)), true).isEmpty())
        assertTrue(MessageReactionAddition(initial).update(initial, true).isEmpty())
    }

    @Test fun aNewOrdinaryOrCustomChoicePlaysOnceButPaidAndInactiveUpdatesNeverPlay() {
        val additions = MessageReactionAddition(emptyList())
        val reactions = listOf(MessageReaction("🔥", 1, true),
            MessageReaction(totalCount = 1, isChosen = true, customEmojiId = 42),
            MessageReaction(totalCount = 5, isChosen = true, isPaid = true))
        assertEquals(setOf(ReactionKey("🔥"), ReactionKey(customEmojiId = 42)), additions.update(reactions, true))
        assertTrue(additions.update(reactions, true).isEmpty())
        additions.update(emptyList(), true)
        assertTrue(additions.update(reactions, false).isEmpty())
        assertTrue(additions.update(reactions, true).isEmpty())
    }

    @Test fun immediateIntentIsBoundedAndSuppressesOnlyItsMatchingNativeAcknowledgement() {
        val intents = ReactionAdditionIntents()
        val key = ReactionKey("🔥")
        intents.request(7, key, null)
        val id = intents.current!!.id
        assertFalse(intents.acknowledge(8, key))
        intents.finish(id)
        assertNull(intents.current)
        assertTrue(intents.acknowledge(7, key), "A late native update must not repeat the already finished local effect.")
        assertFalse(intents.acknowledge(7, key))
        intents.request(8, key, null)
        intents.finish(id)
        assertEquals(8L, intents.current!!.messageId)
    }
}
