package com.badnng.moe.chat

enum class ChatError { REQUEST_FAILED, FORBIDDEN, RATE_LIMITED, TEXT_TOO_LONG, EMPTY_TEXT, INVALID_QUOTE, UNSUPPORTED, NOT_READY }

enum class MessageDelivery { SENT, PENDING, FAILED }

enum class MessageKind { TEXT, PHOTO, VIDEO, ANIMATION, FILE, VOICE, STICKER, ANIMATED_EMOJI, SERVICE, OTHER }

internal val MessageKind.isTextContent: Boolean
    get() = this == MessageKind.TEXT || this == MessageKind.ANIMATED_EMOJI

/** Native preview state is retained even when this surface only renders the message text. */
data class MessageLinkPreviewOptions(
    val disabled: Boolean = false,
    val url: String = "",
    val forceSmallMedia: Boolean = false,
    val forceLargeMedia: Boolean = false,
    val showAboveText: Boolean = false,
) {
    override fun toString() = "MessageLinkPreviewOptions(<redacted>)"
}

data class MessageLinkPreview(
    val url: String,
    val displayUrl: String = "",
    val siteName: String = "",
    val title: String = "",
    val description: RichMessageText = RichMessageText(""),
    val showAboveText: Boolean = false,
) {
    override fun toString() = "MessageLinkPreview(<redacted>)"
}

/** Account content is held in memory for the UI; it must never be included in diagnostics. */
data class ChatMessage(
    val id: Long,
    val chatId: Long,
    val senderId: Long = 0,
    val senderIsChat: Boolean = false,
    val outgoing: Boolean = false,
    val date: Int = 0,
    val text: String = "",
    val kind: MessageKind = MessageKind.TEXT,
    val delivery: MessageDelivery = MessageDelivery.SENT,
    val canRetry: Boolean = false,
    val retryAfterSeconds: Int = 0,
    val media: MessageMedia? = null,
    val replyTo: MessageReply? = null,
    val canBeSaved: Boolean = false,
    val editDate: Int = 0,
    val entities: List<MessageEntity> = emptyList(),
    val reactions: List<MessageReaction> = emptyList(),
    /** UI identity follows native oldMessageId replacements; native operations always use id. */
    val presentationId: Long = id,
    /** Transient application send/retry request identity, never a delivery-success claim. */
    val sendAnimationId: Long? = null,
    val forwardInfo: MessageForwardInfo? = null,
    val importInfo: MessageImportInfo? = null,
    val inlineKeyboard: MessageInlineKeyboard? = null,
    /** Transient native incoming-event identity; history and delivery updates never create it. */
    val incomingAnimationId: Long? = null,
    val showCaptionAboveMedia: Boolean = false,
    val linkPreviewOptions: MessageLinkPreviewOptions? = null,
    val linkPreview: MessageLinkPreview? = null,
) {
    override fun toString() = "ChatMessage(redacted, kind=$kind, delivery=$delivery)"
}

data class ChatItem(
    val id: Long,
    val title: String,
    val order: Long = 0,
    val unreadCount: Int = 0,
    val lastMessage: ChatMessage? = null,
    val canSend: Boolean = true,
    val lastReadOutboxId: Long = 0,
    val positions: Map<ChatListRef, Long> = mapOf(ChatListRef() to order),
    val lastReadInboxId: Long = 0,
    val markedUnread: Boolean = false,
    val avatar: MediaFile? = null,
    val canSendPhotos: Boolean = canSend,
    val canSendStickers: Boolean = canSend,
    val canSendVideos: Boolean = canSend,
    val canSendDocuments: Boolean = canSend,
    val hasProtectedContent: Boolean = false,
    val privateUserId: Long? = null,
    val pinnedLists: Set<ChatListRef> = emptySet(),
    val basicGroupId: Long? = null,
    val supergroupId: Long? = null,
    val isChannel: Boolean = false,
    val memberCount: Int? = null,
    val isMuted: Boolean = false,
    val canSendVoiceNotes: Boolean = canSend,
    val isJoinedGroup: Boolean = false,
    val actionBar: ChatActionBar? = null,
    val canBeReported: Boolean = false,
    val canLeave: Boolean = isJoinedGroup,
    /** Native membership is independent of ordering positions; null means not known. */
    val chatLists: Set<ChatListRef>? = null,
    val appearance: com.badnng.moe.settings.SettingsProfileColorsValues? = null,
) {
    override fun toString() = "ChatItem(redacted)"
}

data class ChatToken(val generation: Long, val selection: Long, val chatId: Long?)

/** Only application-owned error classifications and numeric codes cross the native boundary. */
data class ChatResult<T>(val value: T? = null, val error: ChatError? = null, val code: Int? = null) {
    override fun toString() = "ChatResult(redacted, error=$error, code=$code)"
}

data class ChatLoadResult(val endReached: Boolean = false)

/** These content-bearing events are UI input, not diagnostic events. */
sealed class ChatEvent {
    final override fun toString() = "ChatEvent(redacted)"

    data class NewChat(val chat: ChatItem) : ChatEvent()
    data class ChatAppearance(val chatId: Long, val appearance: com.badnng.moe.settings.SettingsProfileColorsValues) : ChatEvent()
    data class AppearanceCatalog(val catalog: com.badnng.moe.settings.SettingsProfileColorsCatalog) : ChatEvent()
    data class Position(val chatId: Long, val order: Long, val list: ChatListRef = ChatListRef(), val isPinned: Boolean = false) : ChatEvent()
    data class Positions(val chatId: Long, val positions: Map<ChatListRef, Long>, val pinnedLists: Set<ChatListRef> = emptySet()) : ChatEvent()
    data class Title(val chatId: Long, val title: String) : ChatEvent()
    data class LastMessage(val chatId: Long, val message: ChatMessage?, val order: Long, val positions: Map<ChatListRef, Long>? = null,
        val pinnedLists: Set<ChatListRef>? = null) : ChatEvent()
    data class Inbox(val chatId: Long, val unreadCount: Int, val lastReadId: Long = 0) : ChatEvent()
    data class Outbox(val chatId: Long, val lastReadId: Long) : ChatEvent()
    data class Permissions(
        val chatId: Long,
        val canSend: Boolean,
        val canSendPhotos: Boolean = canSend,
        val canSendStickers: Boolean = canSend,
        val canSendVideos: Boolean = canSend,
        val canSendDocuments: Boolean = canSend,
        val canSendVoiceNotes: Boolean = canSend,
    ) : ChatEvent()
    data class NewMessage(val message: ChatMessage) : ChatEvent()
    data class SendSucceeded(val message: ChatMessage, val oldMessageId: Long) : ChatEvent()
    data class SendFailed(val message: ChatMessage, val oldMessageId: Long) : ChatEvent()
    data class Content(val chatId: Long, val messageId: Long, val text: String, val kind: MessageKind, val media: MessageMedia? = null,
        val entities: List<MessageEntity> = emptyList(), val showCaptionAboveMedia: Boolean = false,
        val linkPreviewOptions: MessageLinkPreviewOptions? = null, val linkPreview: MessageLinkPreview? = null) : ChatEvent()
    data class Edited(val chatId: Long, val messageId: Long, val editDate: Int) : ChatEvent()
    data class ReplyMarkup(val chatId: Long, val messageId: Long, val inlineKeyboard: MessageInlineKeyboard?) : ChatEvent()
    data class Reactions(val chatId: Long, val messageId: Long, val reactions: List<MessageReaction>) : ChatEvent()
    data class ReactionAvailabilityChanged(val chatId: Long? = null) : ChatEvent()
    data class GroupMembership(val chatId: Long, val isMember: Boolean) : ChatEvent()
    data class LeaveCapability(val chatId: Long, val canLeave: Boolean) : ChatEvent()
    data class ActionBar(val chatId: Long, val actionBar: ChatActionBar?) : ChatEvent()
    data class Deleted(val chatId: Long, val messageIds: List<Long>, val permanent: Boolean) : ChatEvent()
    data class SenderName(val id: Long, val name: String, val isChat: Boolean = false, val profile: AccountProfile? = null) : ChatEvent()
    data class MarkedUnread(val chatId: Long, val marked: Boolean) : ChatEvent()
    data class NotificationMuted(val chatId: Long, val muted: Boolean) : ChatEvent()
    data class Photo(val chatId: Long, val avatar: MediaFile?) : ChatEvent()
    data class ProtectedContent(val chatId: Long, val hasProtectedContent: Boolean) : ChatEvent()
    data class FileUpdated(val file: MediaFile) : ChatEvent()
    data class Folders(val folders: List<ChatFolderInfo>, val mainPosition: Int = 0, val tagsEnabled: Boolean = false) : ChatEvent()
    data class ListMembership(val chatId: Long, val list: ChatListRef, val added: Boolean) : ChatEvent()
    data class UnreadChats(val list: ChatListRef, val count: Int) : ChatEvent()
    /** null is an unexpected native value, never permission to show cached suggestions. */
    data class TopChatsDisabled(val disabled: Boolean?) : ChatEvent()
    data class MemberCount(val chatId: Long, val count: Int?) : ChatEvent()
    data class OnlineCount(val chatId: Long, val count: Int?) : ChatEvent()
    data class ActivityChanged(val chatId: Long, val senderId: Long, val senderIsChat: Boolean,
        val kind: ChatActivityKind?, val progress: Int? = null) : ChatEvent()
    data class PinnedChanged(val chatId: Long) : ChatEvent()
    data object RecentStickersChanged : ChatEvent()
    data object InstalledStickersChanged : ChatEvent()
}
