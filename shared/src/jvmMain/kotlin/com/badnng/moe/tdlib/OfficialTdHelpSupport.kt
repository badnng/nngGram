package com.badnng.moe.tdlib

import com.badnng.moe.chat.ChatError
import com.badnng.moe.chat.ChatEvent
import com.badnng.moe.chat.ChatItem
import com.badnng.moe.chat.ChatResult
import org.drinkless.tdlib.TdApi
import java.util.concurrent.atomic.AtomicBoolean

/** An identity obtained from GetSupportUser and verified against the actual native private chat. */
data class TdSupportChat(val userId: Long, val chat: ChatItem) {
    override fun toString() = "TdSupportChat(redacted)"
}

interface TdHelpSupportSession {
    fun retire() {}
    fun resolve(active: () -> Boolean, result: (ChatResult<TdSupportChat>) -> Unit)
    /** A successful result records an actual OpenChat Ok, even if ownership expired after dispatch. */
    fun open(target: TdSupportChat, active: () -> Boolean, result: (ChatResult<Unit>) -> Unit)
}

/** Uses the same authorization/interaction guarded dispatcher as ordinary chat operations. */
internal class OfficialTdHelpSupport(
    private val send: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit,
) : TdHelpSupportSession {
    private val alive = AtomicBoolean(true)

    override fun retire() { alive.set(false) }

    override fun resolve(active: () -> Boolean, result: (ChatResult<TdSupportChat>) -> Unit) {
        request(TdApi.GetSupportUser(), active, { response ->
            (response as? TdApi.User)?.takeIf { it.id > 0 && it.isSupport }?.id
        }) support@ { support ->
            val userId = support.value ?: run { result(copyFailure(support)); return@support }
            request(TdApi.CreatePrivateChat(userId, false), active, { response ->
                (response as? TdApi.Chat)?.takeIf { validPrivate(it, userId) }?.id
            }) created@ { created ->
                val chatId = created.value ?: run { result(copyFailure(created)); return@created }
                request(TdApi.GetChat(chatId), active, { response ->
                    (response as? TdApi.Chat)?.takeIf { it.id == chatId && validPrivate(it, userId) }
                        ?.let { TdSupportChat(userId, mapChat(it)) }
                }, result = result)
            }
        }
    }

    override fun open(target: TdSupportChat, active: () -> Boolean, result: (ChatResult<Unit>) -> Unit) {
        if (target.userId <= 0 || target.chat.id == 0L || target.chat.privateUserId != target.userId ||
            target.chat.basicGroupId != null || target.chat.supergroupId != null || target.chat.isChannel) {
            result(failure()); return
        }
        request(TdApi.GetChat(target.chat.id), active, { response ->
            (response as? TdApi.Chat)?.takeIf { it.id == target.chat.id && validPrivate(it, target.userId) }
                ?.let { Unit }
        }) fresh@ { fresh ->
            if (fresh.value == null) { result(copyFailure(fresh)); return@fresh }
            // A late Ok must remain observable so the controller can close an unadopted open.
            request(TdApi.OpenChat(target.chat.id), active, { if (it is TdApi.Ok) Unit else null },
                preserveAcceptedOpen = true, result = result)
        }
    }

    private fun validPrivate(chat: TdApi.Chat, userId: Long) =
        chat.id != 0L && (chat.type as? TdApi.ChatTypePrivate)?.userId == userId

    private fun mapChat(chat: TdApi.Chat): ChatItem {
        var mapped: ChatItem? = null
        TdChatUpdateMapper { event -> if (event is ChatEvent.NewChat) mapped = event.chat }
            .accept(TdApi.UpdateNewChat(chat))
        return checkNotNull(mapped)
    }

    private fun current(active: () -> Boolean) = alive.get() && runCatching(active).getOrDefault(false)
    private fun <T> copyFailure(response: ChatResult<*>) =
        ChatResult<T>(error = response.error ?: ChatError.REQUEST_FAILED, code = response.code)
    private fun <T> failure(response: TdApi.Object? = null): ChatResult<T> =
        if (response is TdApi.Error) ChatResult(error = classifyChatError(response), code = response.code)
        else ChatResult(error = ChatError.REQUEST_FAILED, code = -1)

    private fun <T> request(
        command: TdApi.Function<*>, active: () -> Boolean, map: (TdApi.Object) -> T?,
        preserveAcceptedOpen: Boolean = false, result: (ChatResult<T>) -> Unit,
    ) {
        val once = AtomicBoolean()
        fun finish(value: ChatResult<T>) { if (once.compareAndSet(false, true)) result(value) }
        if (!current(active)) { finish(ChatResult(error = ChatError.NOT_READY)); return }
        try {
            send(command, received@ { response ->
                // Claim before invoking the continuation: duplicate callbacks cannot start another step.
                if (!once.compareAndSet(false, true)) return@received
                val value = runCatching { map(response) }.getOrNull()
                result(when {
                    preserveAcceptedOpen && response is TdApi.Ok && value != null -> ChatResult(value)
                    !current(active) -> ChatResult(error = ChatError.NOT_READY)
                    value != null -> ChatResult(value)
                    else -> failure(response)
                })
            }, { finish(if (current(active)) failure() else ChatResult(error = ChatError.NOT_READY)) })
        } catch (_: Exception) {
            finish(if (current(active)) failure() else ChatResult(error = ChatError.NOT_READY))
        }
    }
}
