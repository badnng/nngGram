package com.badnng.moe

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.badnng.moe.chat.ChatMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConversationSessionMemoryTest {
    @Test fun readingPositionsStayWithTheirChatAndExplicitTargetsTakePrecedence() {
        val memory = ConversationSessionMemory()
        val first = ConversationReadPosition(10, 100, 37)
        val second = ConversationReadPosition(20, 200, 9)
        memory.rememberPosition(first)
        memory.rememberPosition(second)
        assertEquals(first, memory.takeEntryPosition(10))
        assertEquals(second, memory.takeEntryPosition(20))
        memory.prepareEntry(10, restore = false)
        assertNull(memory.takeEntryPosition(10))
        memory.prepareEntry(10)
        assertEquals(first, memory.takeEntryPosition(10))
    }

    @Test fun latestPositionDoesNotReplaceTheNewNativeUnreadBoundary() {
        val memory = ConversationSessionMemory()
        memory.rememberPosition(ConversationReadPosition(10, 100, 0, atLatest = true))
        assertNull(memory.takeEntryPosition(10))
    }

    @Test fun editSelectionAndEntitiesStayIndependentForEachMessageAndChat() {
        val memory = ConversationSessionMemory()
        val first = ChatMessage(100, 10, text = "original")
        val other = ChatMessage(100, 20, text = "different")
        val changed = TextFieldValue("changed", selection = TextRange(2, 5))
        memory.rememberEdit(first, changed)
        assertEquals(changed, memory.editValue(first))
        assertEquals("different", memory.editValue(other).text)
        assertEquals(changed, memory.editValue(first.copy(text = "remote update")))
        assertEquals("remote update", memory.editValue(first.copy(text = "remote update"), owner = 1).text)
        memory.clearEdit(10, 100)
        assertEquals("original", memory.editValue(first).text)
    }

    @Test fun replyReturnStackPreservesCrossChatOffsetsAndDoesNotRestoreOrdinaryReadingOverTheTarget() {
        val memory = ConversationSessionMemory()
        val first = ConversationReadPosition(10, 100, 37)
        val second = ConversationReadPosition(20, 200, 9)
        memory.rememberPosition(ConversationReadPosition(20, 999, 2))
        memory.pushReplyReturn(20, first)
        assertNull(memory.takeEntryPosition(20))
        memory.pushReplyReturn(30, second)
        assertEquals(second, memory.beginReplyReturn())
        assertEquals(second, memory.takeEntryPosition(20))
        assertEquals(first, memory.beginReplyReturn())
        assertEquals(first, memory.takeEntryPosition(10))
        assertNull(memory.beginReplyReturn())
        assertNull(memory.replyReturn)
    }

    @Test fun anotherConversationCannotConsumeAReplyReturnOrInstallItsRequestedPosition() {
        val memory = ConversationSessionMemory()
        val origin = ConversationReadPosition(10, 100, 37)
        memory.pushReplyReturn(20, origin)
        assertNull(memory.beginReplyReturn(30))
        assertEquals(20L, memory.replyReturn?.destinationChatId)
        assertNull(memory.requestedPosition(10))
        assertEquals(0L, memory.returnRevision)
        assertEquals(origin, memory.beginReplyReturn(20))
        assertEquals(origin, memory.requestedPosition(10))
        assertEquals(1L, memory.returnRevision)
    }

    @Test fun failedCrossChatReturnClearsOnlyThePendingRestoreAndKeepsOtherReadingState() {
        val memory = ConversationSessionMemory()
        val earlier = ConversationReadPosition(5, 50, 4)
        val origin = ConversationReadPosition(10, 100, 37)
        memory.pushReplyReturn(10, earlier)
        memory.pushReplyReturn(20, origin)
        memory.beginReplyReturn(20, jumpRevision = 7)
        assertEquals(7L, memory.requestedJumpRevision(10))
        memory.cancelRequestedPositions()
        assertNull(memory.requestedPosition(10))
        assertNull(memory.requestedJumpRevision(10))
        assertEquals(10L, memory.replyReturn?.destinationChatId)
        assertEquals(earlier, memory.beginReplyReturn(10))
    }
}
