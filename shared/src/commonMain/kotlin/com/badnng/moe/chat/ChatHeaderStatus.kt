package com.badnng.moe.chat

enum class ChatActivityKind { TYPING, UPLOADING_PHOTO, UPLOADING_VIDEO, UPLOADING_DOCUMENT, CHOOSING_STICKER }

/** Transient native activity. The sender identity is content, never diagnostic text. */
data class ChatActivity(
    val kind: ChatActivityKind,
    val senderId: Long,
    val senderIsChat: Boolean = false,
    val progress: Int? = null,
    val expiresAtMillis: Long,
) {
    override fun toString() = "ChatActivity(<redacted>)"
}

/** Unknown native counts stay null; zero is not substituted for private or unavailable data. */
data class ChatHeaderStatus(
    val memberCount: Int? = null,
    val onlineCount: Int? = null,
    val activity: ChatActivity? = null,
) {
    override fun toString() = "ChatHeaderStatus(<redacted>)"
}
