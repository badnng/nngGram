package com.badnng.moe.chat

internal enum class ChatListFilter { ALL, UNREAD }

/** Search the loaded list's titles without changing native list order, membership or pagination. */
internal fun visibleChatItems(items: List<ChatItem>, filter: ChatListFilter, query: String = ""): List<ChatItem> {
    val filtered = when (filter) {
        ChatListFilter.ALL -> items
        ChatListFilter.UNREAD -> items.filter { it.unreadCount > 0 || it.markedUnread }
    }
    val search = query.trim()
    return if (search.isEmpty()) filtered else filtered.filter { it.title.contains(search, ignoreCase = true) }
}
