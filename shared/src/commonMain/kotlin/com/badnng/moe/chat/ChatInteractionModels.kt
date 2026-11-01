package com.badnng.moe.chat

/** Native permissions narrowed to operations supported by this client. */
data class MessageActions(
    val canEditText: Boolean = false,
    val canEditCaption: Boolean = false,
    val canDeleteForSelf: Boolean = false,
    val canDeleteForAll: Boolean = false,
    val canForward: Boolean = false,
)

enum class AttachmentKind { VIDEO, FILE }

/** Validated local selection; paths and names remain transient UI content. */
data class LocalAttachment(
    val kind: AttachmentKind,
    val path: String,
    val fileName: String,
    val size: Long,
    val durationSeconds: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val supportsStreaming: Boolean = false,
    val thumbnail: LocalAttachmentThumbnail? = null,
    val source: LocalAttachmentSource? = null,
    val lease: AttachmentLease? = null,
) {
    override fun toString() = "LocalAttachment(<redacted>, kind=$kind)"
}

/** Transient file identity; rechecked immediately before the native send, never persisted. */
data class LocalAttachmentSource(val modifiedMillis: Long, val fileKey: String?) {
    override fun toString() = "LocalAttachmentSource(<redacted>)"
}

/** A real, bounded JPEG prepared from the selected video; its lifetime belongs to the attachment lease. */
data class LocalAttachmentThumbnail(
    val path: String,
    val width: Int,
    val height: Int,
    val size: Long,
    val source: LocalAttachmentSource? = null,
) {
    override fun toString() = "LocalAttachmentThumbnail(<redacted>)"
}

/** A server reply reference may omit the preview until getRepliedMessage resolves it. */
data class MessageReply(
    val chatId: Long,
    val messageId: Long,
    val text: String = "",
    val senderId: Long = 0,
    val senderIsChat: Boolean = false,
    val entities: List<MessageEntity> = emptyList(),
    val isQuote: Boolean = false,
    val quotePosition: Int = 0,
    /** Actual content author, independently of the sender label/forwarder. */
    val contentAuthor: ForwardSourceIdentity? = null,
    /** true plus null deliberately conceals an author and forbids sender fallback. */
    val contentAuthorKnown: Boolean = false,
) {
    override fun toString() = "MessageReply(<redacted>)"
}

enum class MessageReadAvailability {
    AVAILABLE,
    UNREAD,
    UNAVAILABLE,
    TOO_OLD,
    USER_PRIVACY_RESTRICTED,
    MY_PRIVACY_RESTRICTED,
}

data class MessageReader(
    val userId: Long,
    val name: String,
    val readDate: Int,
    val avatar: MediaFile? = null,
) {
    override fun toString() = "MessageReader(<redacted>)"
}

/** AVAILABLE with an empty list means the server returned no viewers. */
data class MessageReadDetails(
    val readers: List<MessageReader> = emptyList(),
    val availability: MessageReadAvailability = MessageReadAvailability.UNAVAILABLE,
) {
    override fun toString() = "MessageReadDetails(<redacted>, availability=$availability)"
}
