package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import org.drinkless.tdlib.TdApi
import java.util.concurrent.atomic.AtomicBoolean

internal fun mapMessageForwardInfo(info: TdApi.MessageForwardInfo?): MessageForwardInfo? = info?.let {
    MessageForwardInfo(
        origin = when (val origin = info.origin) {
            is TdApi.MessageOriginUser -> MessageForwardOrigin.User(origin.senderUserId)
            is TdApi.MessageOriginChat -> MessageForwardOrigin.Chat(origin.senderChatId, origin.authorSignature.orEmpty())
            is TdApi.MessageOriginChannel -> MessageForwardOrigin.Channel(origin.chatId, origin.messageId, origin.authorSignature.orEmpty())
            is TdApi.MessageOriginHiddenUser -> MessageForwardOrigin.HiddenUser(origin.senderName.orEmpty())
            else -> MessageForwardOrigin.Unknown
        },
        date = info.date,
        source = info.source?.let { source -> MessageForwardSource(
            chatId = source.chatId,
            messageId = source.messageId,
            senderId = when (val sender = source.senderId) {
                is TdApi.MessageSenderUser -> sender.userId
                is TdApi.MessageSenderChat -> sender.chatId
                else -> 0
            },
            senderIsChat = source.senderId is TdApi.MessageSenderChat,
            senderName = source.senderName.orEmpty(),
            date = source.date,
            outgoing = source.isOutgoing,
        ) },
        publicServiceAnnouncementType = info.publicServiceAnnouncementType.orEmpty(),
    )
}

internal fun mapMessageImportInfo(info: TdApi.MessageImportInfo?): MessageImportInfo? = info?.let {
    MessageImportInfo(it.senderName.orEmpty(), it.date)
}

/** Resolve only native identities referenced by the selected messages. No history or profile navigation. */
internal class OfficialTdForwardSources(
    private val send: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit,
) {
    fun name(source: ForwardSourceIdentity, result: (ChatResult<String>) -> Unit) {
        presentation(source) { response ->
            result(ChatResult(response.value?.name, response.error, response.code))
        }
    }

    fun presentation(source: ForwardSourceIdentity, result: (ChatResult<PeerAppearancePresentation>) -> Unit) {
        val command = when (source) {
            is ForwardSourceIdentity.User -> if (source.userId > 0) TdApi.GetUser(source.userId) else null
            is ForwardSourceIdentity.Chat -> if (source.chatId != 0L) TdApi.GetChat(source.chatId) else null
        }
        if (command == null) {
            result(ChatResult(error = ChatError.REQUEST_FAILED))
            return
        }
        val completed = AtomicBoolean()
        fun complete(value: ChatResult<PeerAppearancePresentation>) {
            if (completed.compareAndSet(false, true)) result(value)
        }
        try {
            send(command, { response ->
                val presentation = when {
                    source is ForwardSourceIdentity.User && response is TdApi.User && response.id == source.userId ->
                        mapAccountProfile(response).let { PeerAppearancePresentation(source, it.displayName, it.appearance) }
                    source is ForwardSourceIdentity.Chat && response is TdApi.Chat && response.id == source.chatId ->
                        PeerAppearancePresentation(source, response.title.orEmpty(), mapPeerAppearance(response))
                    else -> null
                }?.takeIf { it.name.isNotBlank() }
                complete(if (presentation != null) ChatResult(presentation) else if (response is TdApi.Error)
                    ChatResult(error = classifyChatError(response), code = response.code)
                else ChatResult(error = ChatError.REQUEST_FAILED))
            }, { complete(ChatResult(error = ChatError.REQUEST_FAILED)) })
        } catch (_: Exception) {
            complete(ChatResult(error = ChatError.REQUEST_FAILED))
        }
    }
}
