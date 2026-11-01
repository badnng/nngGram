package com.badnng.moe.chat

import kotlin.test.Test
import kotlin.test.assertEquals

class ReactionEmojiSearchTest {
    @Test fun searchingNeverAddsEmojiOutsideTheNativeAndChosenLists() {
        assertEquals(emptyList(), filterReactionEmojis(listOf("👍", "🔥"), query = "爱"))
        assertEquals(listOf("👍"), filterReactionEmojis(listOf("👍", "🔥", "👍"), query = "赞"))
        assertEquals(listOf("🔥"), filterReactionEmojis(listOf("👍", "🔥"), query = "fire"))
    }

    @Test fun chosenReactionCanStillBeRemovedAfterItDisappearsFromNativeAdditions() {
        assertEquals(listOf("🔥", "❤"), filterReactionEmojis(listOf("🔥"), chosen = setOf("❤")))
    }

    @Test fun categoryFiltersRetainNativeOrderAndSupportSupplementaryCharacters() {
        assertEquals(listOf("👍", "🙏"), filterReactionEmojis(listOf("🔥", "👍", "😂", "🙏"), category = ReactionEmojiCategory.GESTURES))
        assertEquals(listOf("😂"), filterReactionEmojis(listOf("🔥", "😂", "❤"), category = ReactionEmojiCategory.FACES))
    }
}
