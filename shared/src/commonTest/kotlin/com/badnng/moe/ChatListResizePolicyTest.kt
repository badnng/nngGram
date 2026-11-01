package com.badnng.moe

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatListResizePolicyTest {
    @Test
    fun draggingCanReachTheAvatarOnlyMinimumAndRestoreFullRows() {
        assertEquals(72.dp, constrainedChatListWidth((-50).dp, 480.dp))
        assertEquals(280.dp, constrainedChatListWidth(280.dp, 480.dp))
        assertEquals(480.dp, constrainedChatListWidth(900.dp, 480.dp))
    }

    @Test
    fun openingInformationOrShrinkingTheWindowKeepsConversationSpace() {
        val max = maximumChatListWidth(980.dp, 72.dp, 300.dp)
        assertEquals(242.dp, max)
        assertEquals(242.dp, constrainedChatListWidth(400.dp, max))
        assertEquals(480.dp, maximumChatListWidth(1494.dp, 88.dp, 0.dp))
    }

    @Test
    fun unreasonablySmallAvailableSpaceStillHasAValidMinimumRange() {
        assertEquals(72.dp, maximumChatListWidth(380.dp, 72.dp, 300.dp))
        assertEquals(72.dp, constrainedChatListWidth(300.dp, 40.dp))
    }
}
