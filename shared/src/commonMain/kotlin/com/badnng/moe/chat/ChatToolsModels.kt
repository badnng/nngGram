package com.badnng.moe.chat

enum class ChatSearchCategory { CHATS, CHANNELS, APPS, POSTS, PHOTOS, VIDEOS, FILES, LINKS, MUSIC, VOICE }

/** Search content and filters are transient and must never enter diagnostics. */
data class ChatSearchQuery(
    val text: String = "",
    val chatId: Long? = null,
    val senderId: Long? = null,
    val minDate: Int = 0,
    val maxDate: Int = 0,
    val category: ChatSearchCategory = ChatSearchCategory.CHATS,
) {
    override fun toString() = "ChatSearchQuery(<redacted>)"
}

data class ChatSearchPage(
    val chats: List<ChatItem> = emptyList(),
    val messages: List<ChatMessage> = emptyList(),
    val nextOffset: String = "",
    val totalCount: Int = 0,
) {
    override fun toString() = "ChatSearchPage(<redacted>)"
}

data class ChatSearchState(
    val query: ChatSearchQuery = ChatSearchQuery(),
    val chats: List<ChatItem> = emptyList(),
    val messages: List<ChatMessage> = emptyList(),
    val frequent: List<ChatItem> = emptyList(),
    val recent: List<ChatItem> = emptyList(),
    val loading: Boolean = false,
    val error: ChatError? = null,
    val nextOffset: String = "",
    val totalCount: Int = 0,
    val revision: Long = 0,
    val overviewLoading: Boolean = false,
    val recentClearing: Boolean = false,
) {
    override fun toString() = "ChatSearchState(<redacted>)"
}

data class ChatAdministrator(val userId: Long, val name: String, val title: String = "", val owner: Boolean = false) {
    override fun toString() = "ChatAdministrator(<redacted>)"
}

data class ChatDetails(val chatId: Long, val description: String = "", val administrators: List<ChatAdministrator> = emptyList()) {
    override fun toString() = "ChatDetails(<redacted>)"
}

data class ChatDetailsState(
    val details: ChatDetails? = null,
    val loading: Boolean = false,
    val error: ChatError? = null,
) {
    override fun toString() = "ChatDetailsState(<redacted>)"
}

data class ChatPinnedState(
    val messages: List<ChatMessage> = emptyList(),
    val totalCount: Int = 0,
    val nextFromMessageId: Long = 0,
    val loading: Boolean = false,
    val error: ChatError? = null,
) {
    override fun toString() = "ChatPinnedState(<redacted>)"
}
