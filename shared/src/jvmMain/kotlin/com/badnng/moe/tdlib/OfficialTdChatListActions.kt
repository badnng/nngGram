package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import org.drinkless.tdlib.TdApi
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

/** Native updates, rather than request acknowledgements, own list positions, pins and mute state. */
internal class OfficialTdChatListActions(
    private val chatNotifications: NativeChatNotificationMutations,
    private val send: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit,
) {
    constructor(send: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit) : this(NativeChatNotificationMutations(send), send)
    fun perform(chatId: Long, list: ChatListRef, action: ChatListAction, result: (ChatResult<Unit>) -> Unit) {
        if (chatId == 0L || list.folderId < -1) { result(failure()); return }
        when (action) {
            ChatListAction.ARCHIVE -> request(TdApi.AddChatToList(chatId, TdApi.ChatListArchive()), ::ok, result)
            ChatListAction.UNARCHIVE -> request(TdApi.AddChatToList(chatId, TdApi.ChatListMain()), ::ok, result)
            ChatListAction.PIN, ChatListAction.UNPIN -> request(TdApi.ToggleChatIsPinned(nativeChatList(list), chatId,
                action == ChatListAction.PIN), ::ok, result)
            ChatListAction.MUTE, ChatListAction.UNMUTE -> chatNotifications.mute(chatId, action == ChatListAction.MUTE, result)
            // Read-marker ordering is coordinated by the actor, using its current manual-marker revision.
            ChatListAction.MARK_READ, ChatListAction.MARK_UNREAD -> result(ChatResult(error = ChatError.UNSUPPORTED))
        }
    }

    fun addToFolder(chatId: Long, folderId: Int, result: (ChatResult<Unit>) -> Unit) {
        if (chatId == 0L || folderId <= 0) { result(failure()); return }
        request<TdApi.ChatFolder>(TdApi.GetChatFolder(folderId), { response ->
            if (response is TdApi.ChatFolder) ChatResult(response) else failure(response)
        }) { response ->
            val folder = response.value
            if (folder == null) { result(copyFailure(response)); return@request }
            if (folder.isShareable) { result(ChatResult(error = ChatError.FORBIDDEN)); return@request }
            val included = folder.includedChatIds?.toList().orEmpty()
            val pinned = folder.pinnedChatIds?.toList().orEmpty()
            val excluded = folder.excludedChatIds?.toList().orEmpty()
            if ((chatId in included || chatId in pinned) && chatId !in excluded) { result(ChatResult(Unit)); return@request }
            val updated = TdApi.ChatFolder(folder.name, folder.icon, folder.colorId, folder.isShareable,
                folder.pinnedChatIds ?: longArrayOf(), (if (chatId in pinned) included else included + chatId).distinct().toLongArray(),
                excluded.filterNot { it == chatId }.toLongArray(), folder.excludeMuted, folder.excludeRead, folder.excludeArchived,
                folder.includeContacts, folder.includeNonContacts, folder.includeBots, folder.includeGroups, folder.includeChannels)
            request(TdApi.EditChatFolder(folderId, updated), { edited ->
                if (edited is TdApi.ChatFolderInfo && edited.id == folderId) ChatResult(Unit) else failure(edited)
            }, result)
        }
    }

    fun messageLink(chatId: Long, messageId: Long, result: (ChatResult<String>) -> Unit) {
        if (chatId == 0L || messageId <= 0) { result(failure()); return }
        request<TdApi.MessageProperties>(TdApi.GetMessageProperties(chatId, messageId), { response ->
            if (response is TdApi.MessageProperties) ChatResult(response) else failure(response)
        }) { properties ->
            if (properties.value == null) { result(copyFailure(properties)); return@request }
            if (!properties.value.canGetLink) { result(ChatResult(error = ChatError.FORBIDDEN)); return@request }
            request<TdApi.Message>(TdApi.GetMessage(chatId, messageId), { response ->
                if (response is TdApi.Message && response.chatId == chatId && response.id == messageId &&
                    response.content != null && response.sendingState == null) ChatResult(response) else failure(response)
            }) { message ->
                if (message.value == null) result(copyFailure(message))
                else request(TdApi.GetMessageLink(chatId, messageId, 0, 0, "", false, false), { response ->
                    if (response is TdApi.MessageLink) {
                        val link = response.link.orEmpty()
                        val uri = try { URI(link) } catch (_: Exception) { null }
                        if (uri?.scheme == "https" && !uri.host.isNullOrBlank()) ChatResult(link) else failure()
                    } else failure(response)
                }, result)
            }
        }
    }

    private fun ok(response: TdApi.Object): ChatResult<Unit> = if (response is TdApi.Ok) ChatResult(Unit) else failure(response)
    private fun <T> copyFailure(response: ChatResult<*>) = ChatResult<T>(error = response.error ?: ChatError.REQUEST_FAILED, code = response.code)
    private fun <T> failure(response: TdApi.Object? = null): ChatResult<T> =
        if (response is TdApi.Error) ChatResult(error = classifyChatError(response), code = response.code)
        else ChatResult(error = ChatError.REQUEST_FAILED, code = -1)
    private fun <T> request(command: TdApi.Function<*>, map: (TdApi.Object) -> ChatResult<T>, result: (ChatResult<T>) -> Unit) {
        val completed = AtomicBoolean()
        fun complete(value: ChatResult<T>) { if (completed.compareAndSet(false, true)) result(value) }
        try {
            send(command, { response ->
                val mapped = try { map(response) } catch (_: Exception) { failure() }
                complete(mapped)
            }, { complete(failure()) })
        } catch (_: Exception) { complete(failure()) }
    }
}
