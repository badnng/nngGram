package com.badnng.moe.notifications

import com.badnng.moe.chat.ChatListRef
import kotlin.test.*

class DeviceUnreadCountsTest {
    @Test fun messagesAddOnlyTheNativeMarkedOnlyChatsAndChatModeDoesNotDoubleCount() {
        val value = DeviceUnreadCounts(100, 30, 15, 8, 4, 2)
        assertEquals(104, value.count(UnreadCounterMode.MESSAGES, true))
        assertEquals(32, value.count(UnreadCounterMode.MESSAGES, false))
        assertEquals(15, value.count(UnreadCounterMode.CHATS, true))
        assertEquals(8, value.count(UnreadCounterMode.CHATS, false))
    }

    @Test fun missingNativeAggregateIsUnknownAndOverlappingFoldersDoNotEnterAccountSum() {
        assertNull(DeviceUnreadCounts(messages = 4).count(UnreadCounterMode.MESSAGES, true))
        val value = DeviceNotificationsSnapshot(unread = mapOf(
            ChatListRef() to DeviceUnreadCounts(8, 5, 3, 2, 1, 1),
            ChatListRef(-1) to DeviceUnreadCounts(10, 2, 4, 1, 0, 0),
            ChatListRef(7) to DeviceUnreadCounts(18, 7, 7, 3, 1, 1),
        ))
        assertEquals(19, value.accountBadge())
        assertEquals(7, value.folderBadge(ChatListRef(7)))
        assertEquals(3, value.copy(preferences = value.preferences.copy(includeMutedFolders = false)).folderBadge(ChatListRef(7)))
        assertNull(value.copy(unread = value.unread - ChatListRef(-1)).accountBadge())
    }
}
