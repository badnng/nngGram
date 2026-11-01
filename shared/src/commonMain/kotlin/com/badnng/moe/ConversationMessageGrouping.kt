package com.badnng.moe

import com.badnng.moe.chat.ChatMessage
import com.badnng.moe.chat.ChatItem
import com.badnng.moe.chat.MessageForwardOrigin
import com.badnng.moe.chat.MessageKind
import kotlin.math.abs

/** Presentation-only groups never cross a native sender, direction, day or unread boundary. */
internal fun conversationContinuationIds(
    messages: List<ChatMessage>,
    groupBreakIds: Set<Long>,
    savedMessages: Boolean = false,
): Set<Long> =
    messages.zipWithNext().asSequence().filter { (previous, message) ->
        message.id !in groupBreakIds && previous.chatId == message.chatId &&
            previous.outgoing == message.outgoing && message.senderId != 0L &&
            previous.senderId == message.senderId && previous.senderIsChat == message.senderIsChat &&
            previous.kind != MessageKind.SERVICE && message.kind != MessageKind.SERVICE &&
            previous.kind != MessageKind.OTHER && message.kind != MessageKind.OTHER &&
            previous.inlineKeyboard?.rows.orEmpty().all { it.isEmpty() } &&
            previous.date > 0 && message.date > 0 && abs(message.date.toLong() - previous.date) < 900L &&
            samePresentedOrigin(previous, message, savedMessages)
    }.map { it.second.id }.toSet()

/** Ordinary forwards group by the current sender; imported/saved messages group by their displayed origin. */
private fun samePresentedOrigin(previous: ChatMessage, message: ChatMessage, savedMessages: Boolean): Boolean {
    val imported = previous.importInfo
    val nextImported = message.importInfo
    if (imported != null || nextImported != null) {
        return imported != null && nextImported != null && imported.senderName == nextImported.senderName
    }
    if (!savedMessages) return true
    val first = previous.forwardInfo
    val second = message.forwardInfo
    if (first == null || second == null) return first == null && second == null
    fun ChatMessage.forwardIdentity(): Any? {
        val info = forwardInfo ?: return null
        val source = info.source
        if (source != null && source.senderId != 0L) return source.senderIsChat to source.senderId
        if (source != null && source.senderName.isNotBlank()) return source.senderName
        return when (val origin = info.origin) {
            is MessageForwardOrigin.User -> false to origin.userId
            is MessageForwardOrigin.Chat -> true to origin.chatId
            is MessageForwardOrigin.Channel -> true to origin.chatId
            is MessageForwardOrigin.HiddenUser -> origin.senderName
            MessageForwardOrigin.Unknown -> null
        }
    }
    val identity = previous.forwardIdentity()
    return identity != null && identity == message.forwardIdentity()
}

internal data class MessagePresentation(
    val showSenderName: Boolean,
    val showAvatar: Boolean,
    val reserveAvatarSpace: Boolean,
)

/** Default history mode: sender names start a group, while the sender's avatar ends it. */
internal fun conversationMessagePresentation(
    message: ChatMessage,
    chat: ChatItem?,
    startsGroup: Boolean,
    endsGroup: Boolean,
): MessagePresentation {
    val privateChat = chat?.privateUserId != null
    val group = chat != null && !privateChat && !chat.isChannel &&
        (chat.basicGroupId != null || chat.supergroupId != null || chat.isJoinedGroup)
    val hasPhoto = group && !message.outgoing && message.senderId != 0L && message.kind != MessageKind.SERVICE
    val hasName = !privateChat && chat != null &&
        (!message.outgoing || message.senderIsChat) && message.kind != MessageKind.SERVICE
    return MessagePresentation(hasName && startsGroup, hasPhoto && endsGroup, hasPhoto)
}
