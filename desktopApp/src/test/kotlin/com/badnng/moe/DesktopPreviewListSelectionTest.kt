package com.badnng.moe

import com.badnng.moe.chat.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class DesktopPreviewListSelectionTest {
    @Test
    fun previewFolderSwitchingKeepsTheSelectedConversationAndAllLoadingContext() {
        val main = ChatListRef()
        val first = ChatListRef(1)
        val third = ChatListRef(3)
        val selected = ChatItem(10, "synthetic selected", positions = mapOf(main to 100L))
        val other = ChatItem(20, "synthetic other", positions = mapOf(first to 100L, third to 200L))
        val message = ChatMessage(50, selected.id, text = "synthetic")
        val original = ChatSnapshot(enabled = true, token = ChatToken(1, 3, selected.id), selected = selected,
            items = listOf(selected), knownChats = listOf(selected, other), messages = listOf(message),
            folders = listOf(ChatFolderInfo(1, "first"), ChatFolderInfo(3, "third")),
            historyLoading = true, newerLoading = true, historyHasNewer = true, sendPending = true,
            replyTarget = message, replyQuote = MessageQuote(0, RichMessageText("synthetic")),
            unreadBoundary = ChatUnreadBoundary(40, 50), jumpMessageId = 50,
            pinned = ChatPinnedState(messages = listOf(message), totalCount = 1),
            details = ChatDetailsState(ChatDetails(selected.id, "synthetic description")))
        var current = original
        for (list in listOf(first, third, ChatListRef(-1), main)) {
            current = selectPreviewChatList(current, current.token, list)
            val expectedItems = when (list) { first, third -> listOf(other); main -> listOf(selected); else -> emptyList() }
            assertEquals(original.copy(activeList = list, items = expectedItems), current)
            assertSame(original.messages, current.messages)
            assertSame(original.selected, current.selected)
        }
    }

    @Test
    fun staleTokensAndUnknownListsCannotChangeTheSyntheticState() {
        val snapshot = ChatSnapshot(enabled = true, token = ChatToken(1, 3, 10))
        assertSame(snapshot, selectPreviewChatList(snapshot, ChatToken(1, 2, 10), ChatListRef(-1)))
        assertSame(snapshot, selectPreviewChatList(snapshot, snapshot.token, ChatListRef(999)))
        assertSame(snapshot, selectPreviewChatList(snapshot, snapshot.token, ChatListRef(-2)))
        val disabled = snapshot.copy(enabled = false)
        assertSame(disabled, selectPreviewChatList(disabled, snapshot.token, ChatListRef(-1)))
    }
}
