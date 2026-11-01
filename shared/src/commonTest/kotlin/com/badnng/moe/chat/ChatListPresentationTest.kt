package com.badnng.moe.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ChatListPresentationTest {
    @Test
    fun unreadViewIncludesManualMarkersAndPreservesNativeOrder() {
        val items = listOf(
            ChatItem(4, "read", unreadCount = 0),
            ChatItem(3, "manual", markedUnread = true),
            ChatItem(2, "incoming", unreadCount = 2),
            ChatItem(1, "read too"),
        )
        assertEquals(listOf(3L, 2L), visibleChatItems(items, ChatListFilter.UNREAD).map { it.id })
        assertSame(items, visibleChatItems(items, ChatListFilter.ALL))
        assertEquals(listOf(4L, 3L, 2L, 1L), items.map { it.id })
    }

    @Test
    fun unreadViewUpdatesWhenNativeUnreadMarkersClear() {
        val chat = ChatItem(1, "chat", markedUnread = true, unreadCount = 2)
        assertEquals(1, visibleChatItems(listOf(chat.copy(unreadCount = 0)), ChatListFilter.UNREAD).size)
        assertEquals(emptyList(), visibleChatItems(listOf(chat.copy(unreadCount = 0, markedUnread = false)), ChatListFilter.UNREAD))
    }

    @Test
    fun titleSearchTrimsWhitespaceIgnoresCaseAndPreservesTheNativeOrder() {
        val items = listOf(
            ChatItem(3, "Miuix 设计交流"),
            ChatItem(2, "nngGram 开发组"),
            ChatItem(1, "MIUIX 桌面研究所"),
        )
        assertEquals(listOf(3L, 1L), visibleChatItems(items, ChatListFilter.ALL, "  miuix  ").map { it.id })
        assertEquals(listOf(2L), visibleChatItems(items, ChatListFilter.ALL, "开发组").map { it.id })
        assertSame(items, visibleChatItems(items, ChatListFilter.ALL, " \t "))
        assertEquals(listOf(3L, 2L, 1L), items.map { it.id })
    }

    @Test
    fun searchAndUnreadFilterApplyTogetherIncludingManualUnreadMarkers() {
        val items = listOf(
            ChatItem(3, "开发组", unreadCount = 0),
            ChatItem(2, "开发交流", markedUnread = true),
            ChatItem(1, "设计交流", unreadCount = 5),
        )
        assertEquals(listOf(2L), visibleChatItems(items, ChatListFilter.UNREAD, "开发").map { it.id })
        assertEquals(emptyList(), visibleChatItems(items, ChatListFilter.UNREAD, "不存在"))
    }

    @Test
    fun searchDoesNotInspectMessageContentsOrChatsOutsideTheSuppliedList() {
        val items = listOf(ChatItem(1, "工作", lastMessage = ChatMessage(1, 1, text = "private query")))
        assertEquals(emptyList(), visibleChatItems(items, ChatListFilter.ALL, "private"))
        assertEquals(emptyList(), visibleChatItems(emptyList(), ChatListFilter.ALL, "工作"))
        assertEquals(listOf(1L), visibleChatItems(items, ChatListFilter.ALL, "工作").map { it.id })
    }
}
