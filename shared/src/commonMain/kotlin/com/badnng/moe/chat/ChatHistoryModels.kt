package com.badnng.moe.chat

/** Frozen when a conversation opens; subsequent read acknowledgements never move this boundary. */
data class ChatUnreadBoundary(val afterMessageId: Long, val firstUnreadMessageId: Long? = null) {
    override fun toString() = "ChatUnreadBoundary(<redacted>)"
}
