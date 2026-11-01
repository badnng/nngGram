package com.badnng.moe.chat

/** Each category has a corresponding native chat-message search filter. */
enum class ChatMediaCategory { PHOTOS_AND_VIDEOS, FILES, LINKS, AUDIO, VOICE }

/** Transient, independently paged media index; unknown counts are never represented as zero. */
data class ChatMediaIndexState(
    val chatId: Long? = null,
    val category: ChatMediaCategory = ChatMediaCategory.PHOTOS_AND_VIDEOS,
    val messages: List<ChatMessage> = emptyList(),
    val totalCount: Int? = null,
    val nextOffset: String = "",
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: ChatError? = null,
    val counts: Map<ChatMediaCategory, Int> = emptyMap(),
    val countsLoading: Set<ChatMediaCategory> = emptySet(),
    val countErrors: Map<ChatMediaCategory, ChatError> = emptyMap(),
    val revision: Long = 0,
) {
    override fun toString() = "ChatMediaIndexState(<redacted>)"
}
