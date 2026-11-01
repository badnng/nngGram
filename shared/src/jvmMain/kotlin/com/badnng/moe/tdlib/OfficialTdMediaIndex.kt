package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import org.drinkless.tdlib.TdApi
import java.util.concurrent.atomic.AtomicBoolean

/** Native indexed queries only; this provider never reads the timeline or starts a media download. */
internal class OfficialTdMediaIndex(
    private val send: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit,
) {
    fun page(chatId: Long, category: ChatMediaCategory, fromMessageId: Long, result: (ChatResult<ChatSearchPage>) -> Unit) {
        if (chatId == 0L || fromMessageId < 0) { result(failure()); return }
        request(TdApi.SearchChatMessages(chatId, null, "", null, fromMessageId, 0, PAGE_SIZE, filter(category)), { response ->
            if (response !is TdApi.FoundChatMessages) failure(response)
            else {
                val messages = response.messages.orEmpty().filterNotNull()
                if (messages.any { it.chatId != chatId || it.id <= 0 || it.content == null } || response.totalCount < -1) failure()
                else ChatResult(ChatSearchPage(
                    messages = messages.distinctBy { it.id }.map(::mapChatMessage),
                    totalCount = response.totalCount,
                    nextOffset = response.nextFromMessageId.takeIf { it > 0 && it != fromMessageId }?.toString().orEmpty(),
                ))
            }
        }, result)
    }

    fun count(chatId: Long, category: ChatMediaCategory, result: (ChatResult<Int>) -> Unit) {
        if (chatId == 0L) { result(failure()); return }
        // returnLocal=false asks the server instead of reporting an incomplete locally cached count.
        request(TdApi.GetChatMessageCount(chatId, null, filter(category), false), { response ->
            if (response is TdApi.Count && response.count >= 0) ChatResult(response.count) else failure(response)
        }, result)
    }

    private fun filter(category: ChatMediaCategory): TdApi.SearchMessagesFilter = when (category) {
        ChatMediaCategory.PHOTOS_AND_VIDEOS -> TdApi.SearchMessagesFilterPhotoAndVideo()
        ChatMediaCategory.FILES -> TdApi.SearchMessagesFilterDocument()
        ChatMediaCategory.LINKS -> TdApi.SearchMessagesFilterUrl()
        ChatMediaCategory.AUDIO -> TdApi.SearchMessagesFilterAudio()
        ChatMediaCategory.VOICE -> TdApi.SearchMessagesFilterVoiceNote()
    }

    private fun <T> failure(response: TdApi.Object? = null): ChatResult<T> =
        if (response is TdApi.Error) ChatResult(error = classifyChatError(response), code = response.code)
        else ChatResult(error = ChatError.REQUEST_FAILED)

    private fun <T> request(command: TdApi.Function<*>, map: (TdApi.Object) -> ChatResult<T>, result: (ChatResult<T>) -> Unit) {
        val completed = AtomicBoolean()
        fun complete(value: ChatResult<T>) { if (completed.compareAndSet(false, true)) result(value) }
        try {
            send(command, { response ->
                complete(try { map(response) } catch (_: Exception) { failure() })
            }, { complete(failure()) })
        } catch (_: Exception) { complete(failure()) }
    }

    private companion object {
        const val PAGE_SIZE = 50
    }
}
