package com.badnng.moe

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConversationHeaderLayoutTest {
    @Test fun widerConversationsAndMaximizedWindowsKeepTheSameCapsuleWidth() {
        val ordinary = conversationHeaderWidth(1060.dp)
        assertEquals(ordinary, conversationHeaderWidth(1920.dp))
        assertTrue(ordinary <= 940.dp)
    }

    @Test fun narrowConversationsShrinkTheCapsuleInsteadOfClippingItsControls() {
        listOf(0.dp, 320.dp, 356.dp, 380.dp, 700.dp).forEach { available ->
            assertEquals(available, conversationHeaderWidth(available))
        }
        assertEquals(940.dp, conversationHeaderWidth(940.dp))
    }
}
