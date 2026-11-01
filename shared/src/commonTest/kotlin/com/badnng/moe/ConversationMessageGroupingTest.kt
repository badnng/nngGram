package com.badnng.moe

import com.badnng.moe.chat.*
import kotlin.test.Test
import kotlin.test.assertEquals

class ConversationMessageGroupingTest {
    private val first = ChatMessage(1, 10, senderId = 100, date = 1_000)

    @Test
    fun consecutiveIncomingAndOutgoingMessagesCanBothContinueAGroup() {
        val incoming = listOf(first, first.copy(id = 2, date = 1_001), first.copy(id = 3, date = 1_300))
        assertEquals(setOf(2L, 3L), conversationContinuationIds(incoming, emptySet()))
        assertEquals(setOf(2L, 3L), conversationContinuationIds(incoming.map { it.copy(outgoing = true) }, emptySet()))
    }

    @Test
    fun differentNativeSenderKindsAndDirectionsDoNotShareAGroup() {
        for (second in listOf(first.copy(id = 2, senderId = 101), first.copy(id = 2, senderIsChat = true),
            first.copy(id = 2, outgoing = true), first.copy(id = 2, chatId = 11), first.copy(id = 2, senderId = 0))) {
            assertEquals(emptySet(), conversationContinuationIds(listOf(first, second), emptySet()))
        }
    }

    @Test
    fun dateAndUnreadBoundariesSplitOtherwiseMatchingMessages() {
        val messages = listOf(first, first.copy(id = 2, date = 1_001), first.copy(id = 3, date = 1_002))
        assertEquals(setOf(3L), conversationContinuationIds(messages, setOf(2)))
        assertEquals(emptySet(), conversationContinuationIds(messages, setOf(2, 3)))
    }

    @Test
    fun unknownDatesServiceMessagesAndFifteenMinuteGapsRemainSeparate() {
        for (second in listOf(first.copy(id = 2, date = 1_900),
            first.copy(id = 2, kind = MessageKind.SERVICE))) {
            assertEquals(emptySet(), conversationContinuationIds(listOf(first, second), emptySet()))
        }
        assertEquals(emptySet(), conversationContinuationIds(listOf(first.copy(date = 0), first.copy(id = 2, date = 1)), emptySet()))
    }

    @Test
    fun officialGapIsAbsoluteAndStrictlyLessThanNineHundredSeconds() {
        for (date in listOf(101, 999, 1_301, 1_899)) {
            assertEquals(setOf(2L), conversationContinuationIds(listOf(first, first.copy(id = 2, date = date)), emptySet()))
        }
        assertEquals(emptySet(), conversationContinuationIds(listOf(first, first.copy(id = 2, date = 100)), emptySet()))
    }

    @Test
    fun visiblePreviousInlineKeyboardEndsTheGroup() {
        val keyboard = MessageInlineKeyboard(listOf(listOf(MessageInlineButton("Open"))))
        val next = first.copy(id = 2)
        assertEquals(emptySet(), conversationContinuationIds(listOf(first.copy(inlineKeyboard = keyboard), next), emptySet()))
        assertEquals(setOf(2L), conversationContinuationIds(listOf(first, next.copy(inlineKeyboard = keyboard)), emptySet()))
    }

    @Test
    fun importedNamesAndSavedForwardOriginsNeverMergeDifferentPresentedAuthors() {
        val imported = first.copy(importInfo = MessageImportInfo("First", 1))
        assertEquals(setOf(2L), conversationContinuationIds(listOf(imported,
            imported.copy(id = 2, importInfo = MessageImportInfo("First", 2))), emptySet()))
        assertEquals(emptySet(), conversationContinuationIds(listOf(imported,
            imported.copy(id = 2, importInfo = MessageImportInfo("Second", 2))), emptySet()))
        assertEquals(emptySet(), conversationContinuationIds(listOf(imported, first.copy(id = 2)), emptySet()))
        val forwarded = first.copy(forwardInfo = MessageForwardInfo(MessageForwardOrigin.Channel(40, 1)))
        val sameOrigin = forwarded.copy(id = 2, forwardInfo = MessageForwardInfo(MessageForwardOrigin.Channel(40, 2)))
        val otherOrigin = sameOrigin.copy(forwardInfo = MessageForwardInfo(MessageForwardOrigin.Channel(41, 2)))
        assertEquals(setOf(2L), conversationContinuationIds(listOf(forwarded, sameOrigin), emptySet(), savedMessages = true))
        assertEquals(emptySet(), conversationContinuationIds(listOf(forwarded, otherOrigin), emptySet(), savedMessages = true))
        assertEquals(setOf(2L), conversationContinuationIds(listOf(forwarded, otherOrigin), emptySet()))
        assertEquals(emptySet(), conversationContinuationIds(listOf(forwarded, first.copy(id = 2)), emptySet(), savedMessages = true))
    }

    @Test
    fun privateGroupAndChannelNamesAndAvatarSlotsHaveIndependentOwnership() {
        val privateChat = ChatItem(10, "User", privateUserId = 100)
        val group = ChatItem(10, "Group", basicGroupId = 1)
        val channel = ChatItem(10, "Channel", supergroupId = 1, isChannel = true)
        assertEquals(MessagePresentation(false, false, false), conversationMessagePresentation(first, privateChat, true, true))
        assertEquals(MessagePresentation(true, false, true), conversationMessagePresentation(first, group, true, false))
        assertEquals(MessagePresentation(false, true, true), conversationMessagePresentation(first, group, false, true))
        assertEquals(MessagePresentation(true, true, true), conversationMessagePresentation(first, group, true, true))
        assertEquals(MessagePresentation(true, false, false), conversationMessagePresentation(first, channel, true, true))
        assertEquals(MessagePresentation(false, false, false), conversationMessagePresentation(first.copy(outgoing = true), group, true, true))
        assertEquals(MessagePresentation(true, false, false), conversationMessagePresentation(first.copy(outgoing = true, senderIsChat = true), group, true, true))
    }
}
