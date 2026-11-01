package com.badnng.moe.chat

/** Native attribution is transient message content, independently of a reply reference. */
data class MessageForwardInfo(
    val origin: MessageForwardOrigin,
    val date: Int = 0,
    val source: MessageForwardSource? = null,
    val publicServiceAnnouncementType: String = "",
) {
    override fun toString() = "MessageForwardInfo(<redacted>)"
}

sealed class MessageForwardOrigin {
    final override fun toString() = "MessageForwardOrigin(<redacted>)"

    data class User(val userId: Long) : MessageForwardOrigin()
    data class Chat(val chatId: Long, val authorSignature: String = "") : MessageForwardOrigin()
    data class Channel(val chatId: Long, val messageId: Long, val authorSignature: String = "") : MessageForwardOrigin()
    /** TDLib deliberately withholds an identity; this name must never become a user lookup. */
    data class HiddenUser(val senderName: String) : MessageForwardOrigin()
    data object Unknown : MessageForwardOrigin()
}

/** The immediate source of a forward is not necessarily its original author. */
data class MessageForwardSource(
    val chatId: Long,
    val messageId: Long,
    val senderId: Long = 0,
    val senderIsChat: Boolean = false,
    val senderName: String = "",
    val date: Int = 0,
    val outgoing: Boolean = false,
) {
    override fun toString() = "MessageForwardSource(<redacted>)"
}

/** Imported-only messages have an original author, but are not labelled as forwards. */
data class MessageImportInfo(val senderName: String, val date: Int = 0) {
    override fun toString() = "MessageImportInfo(<redacted>)"
}

sealed class ForwardSourceIdentity {
    final override fun toString() = "ForwardSourceIdentity(<redacted>)"

    data class User(val userId: Long) : ForwardSourceIdentity()
    data class Chat(val chatId: Long) : ForwardSourceIdentity()
}

fun MessageForwardOrigin.sourceIdentity(): ForwardSourceIdentity? = when (this) {
    is MessageForwardOrigin.User -> userId.takeIf { it > 0 }?.let { ForwardSourceIdentity.User(it) }
    is MessageForwardOrigin.Chat -> chatId.takeIf { it != 0L }?.let { ForwardSourceIdentity.Chat(it) }
    is MessageForwardOrigin.Channel -> chatId.takeIf { it != 0L }?.let { ForwardSourceIdentity.Chat(it) }
    is MessageForwardOrigin.HiddenUser, MessageForwardOrigin.Unknown -> null
}

/** A chat ID and a user ID remain different identities even when their numbers match. */
fun ChatSnapshot.forwardedSourceName(info: MessageForwardInfo): String? = when (val origin = info.origin) {
    is MessageForwardOrigin.User -> senderNames[origin.userId]?.takeIf(String::isNotBlank)
    is MessageForwardOrigin.Chat -> knownChats.firstOrNull { it.id == origin.chatId }?.title?.takeIf(String::isNotBlank)
    is MessageForwardOrigin.Channel -> knownChats.firstOrNull { it.id == origin.chatId }?.title?.takeIf(String::isNotBlank)
    is MessageForwardOrigin.HiddenUser -> origin.senderName.takeIf(String::isNotBlank)
    MessageForwardOrigin.Unknown -> null
} ?: info.origin.sourceIdentity()?.let { forwardSourceNames[it]?.takeIf(String::isNotBlank) }

fun ChatSnapshot.forwardedHeaderText(info: MessageForwardInfo): String {
    val name = forwardedSourceName(info) ?: return "转发的消息"
    val signature = when (val origin = info.origin) {
        is MessageForwardOrigin.Chat -> origin.authorSignature
        is MessageForwardOrigin.Channel -> origin.authorSignature
        else -> ""
    }
    return if (signature.isBlank()) "转发自 $name" else "转发自 $name ($signature)"
}
