package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import java.util.concurrent.atomic.AtomicBoolean

/** Pinned personal-channel metadata only; no history, read receipts, avatar downloads or chat creation. */
internal class OfficialTdSettingsPersonalChannel(
    private val send: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit,
) {
    fun current(chatId: Long, isCurrent: () -> Boolean, result: (SettingsResult<SettingsPersonalChannel>) -> Unit) {
        if (chatId == 0L) { result(SettingsResult(error = SettingsError.INVALID_VALUE)); return }
        chat(chatId, isCurrent) { response ->
            result(response.value?.let { SettingsResult(SettingsPersonalChannel(it.id, it.title.orEmpty(), "")) }
                ?: SettingsResult(error = response.error))
        }
    }

    fun catalog(userId: Long, isCurrent: () -> Boolean, result: (SettingsResult<SettingsPersonalChannelCatalog>) -> Unit) {
        self(userId, isCurrent) { identity ->
            if (identity.value == null) { result(SettingsResult(error = identity.error)); return@self }
            request(TdApi.GetSuitablePersonalChats(), isCurrent, { it as? TdApi.Chats }) { response ->
                val chats = response.value ?: run { result(SettingsResult(error = response.error)); return@request }
                val raw = chats.chatIds ?: LongArray(0)
                if (raw.take(MAX_CATALOG + 1).any { it == 0L }) {
                    result(SettingsResult(error = SettingsError.REQUEST_FAILED)); return@request
                }
                val ids = raw.asSequence().take(MAX_CATALOG).distinct().toList()
                result(SettingsResult(SettingsPersonalChannelCatalog(ids, chats.totalCount.takeIf { it >= 0 },
                    limited = raw.size > MAX_CATALOG || chats.totalCount > raw.size)))
            }
        }
    }

    fun page(userId: Long, ids: List<Long>, isCurrent: () -> Boolean, result: (SettingsResult<SettingsPersonalChannelsPage>) -> Unit) {
        if (ids.size > PAGE_SIZE || ids.any { it == 0L } || ids.distinct().size != ids.size) {
            result(SettingsResult(error = SettingsError.INVALID_VALUE)); return
        }
        self(userId, isCurrent) { identity ->
            if (identity.value == null) { result(SettingsResult(error = identity.error)); return@self }
            val entries = mutableListOf<SettingsPersonalChannel>()
            var unavailable = 0
            var offset = 0
            fun next() {
                if (!isCurrent()) { result(SettingsResult(error = SettingsError.NOT_READY)); return }
                val id = ids.getOrNull(offset++) ?: run {
                    result(SettingsResult(SettingsPersonalChannelsPage(entries.toList(), unavailable))); return
                }
                eligible(id, isCurrent) { response ->
                    response.value?.let { entries += it } ?: run { unavailable++ }
                    next()
                }
            }
            next()
        }
    }

    fun save(userId: Long, expectedChatId: Long, desiredChatId: Long, isCurrent: () -> Boolean,
        result: (SettingsResult<Unit>) -> Unit,
    ) {
        self(userId, isCurrent) { identity ->
            if (identity.value == null) { result(SettingsResult(error = identity.error)); return@self }
            request(TdApi.GetUserFullInfo(userId), isCurrent, { it as? TdApi.UserFullInfo }) full@ { fresh ->
                val info = fresh.value ?: run { result(SettingsResult(error = fresh.error)); return@full }
                if (info.personalChatId != expectedChatId) { result(SettingsResult(error = SettingsError.CONFLICT)); return@full }
                fun commit() {
                    // Candidate resolution is a multi-request interval. Do not overwrite a current channel changed during it.
                    request(TdApi.GetUserFullInfo(userId), isCurrent, { it as? TdApi.UserFullInfo }) finalRead@ { final ->
                        val current = final.value ?: run { result(SettingsResult(error = final.error)); return@finalRead }
                        if (current.personalChatId != expectedChatId) result(SettingsResult(error = SettingsError.CONFLICT))
                        else request(TdApi.SetPersonalChat(desiredChatId), isCurrent, { if (it is TdApi.Ok) Unit else null }, finalWrite = true, result = result)
                    }
                }
                if (desiredChatId == 0L) { commit(); return@full }
                // Getter data may be cached. Native server acknowledgement remains authoritative for final permission.
                request(TdApi.GetSuitablePersonalChats(), isCurrent, { it as? TdApi.Chats }) candidates@ { response ->
                    val suitable = response.value ?: run { result(SettingsResult(error = response.error)); return@candidates }
                    if (suitable.chatIds?.asSequence()?.take(MAX_CATALOG)?.any { it == desiredChatId } != true) {
                        result(SettingsResult(error = SettingsError.CONFLICT)); return@candidates
                    }
                    eligible(desiredChatId, isCurrent) { channel ->
                        if (channel.value != null) commit()
                        else result(SettingsResult(error = channel.error ?: SettingsError.CONFLICT))
                    }
                }
            }
        }
    }

    private fun eligible(chatId: Long, isCurrent: () -> Boolean, result: (SettingsResult<SettingsPersonalChannel>) -> Unit) {
        chat(chatId, isCurrent) chat@ { response ->
            val value = response.value ?: run { result(SettingsResult(error = response.error)); return@chat }
            val type = value.type as TdApi.ChatTypeSupergroup
            request(TdApi.GetSupergroup(type.supergroupId), isCurrent, { it as? TdApi.Supergroup }) group@ { response ->
                val group = response.value ?: run { result(SettingsResult(error = response.error)); return@group }
                val username = group.usernames?.activeUsernames?.firstOrNull { it.isNotBlank() }
                if (group.id != type.supergroupId || !group.isChannel || group.status !is TdApi.ChatMemberStatusCreator || username == null)
                    result(SettingsResult(error = SettingsError.CONFLICT))
                else result(SettingsResult(SettingsPersonalChannel(value.id, value.title.orEmpty(), username)))
            }
        }
    }
    private fun chat(chatId: Long, current: () -> Boolean, result: (SettingsResult<TdApi.Chat>) -> Unit) {
        request(TdApi.GetChat(chatId), current, { it as? TdApi.Chat }) { response ->
            val chat = response.value
            if (chat != null && (chat.id != chatId || (chat.type as? TdApi.ChatTypeSupergroup)?.isChannel != true || chat.title.isNullOrBlank()))
                result(SettingsResult(error = SettingsError.CONFLICT))
            else result(response)
        }
    }
    private fun self(userId: Long, current: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) {
        if (userId <= 0) { result(SettingsResult(error = SettingsError.INVALID_VALUE)); return }
        request(TdApi.GetMe(), current, { it as? TdApi.User }) { response ->
            result(when {
                response.value == null -> SettingsResult(error = response.error)
                response.value.id != userId -> SettingsResult(error = SettingsError.NOT_READY)
                else -> SettingsResult(Unit)
            })
        }
    }
    private fun <T> request(command: TdApi.Function<*>, current: () -> Boolean, map: (TdApi.Object) -> T?, finalWrite: Boolean = false, result: (SettingsResult<T>) -> Unit) {
        val once = AtomicBoolean()
        fun finish(value: SettingsResult<T>) {
            if (SettingsSpecialMutationContext.receipt()?.outcome == SettingsSpecialWriteOutcome.INDETERMINATE) {
                result(value); return
            }
            if (once.compareAndSet(false, true)) result(value)
        }
        if (!current()) { finish(SettingsResult(error = SettingsError.NOT_READY)); return }
        val done: (TdApi.Object) -> Unit = { response ->
            if (!finalWrite && !current()) finish(SettingsResult(error = SettingsError.NOT_READY))
            else {
                val value = runCatching { map(response) }.getOrNull()
                finish(if (value != null) SettingsResult(value) else SettingsResult(error = settingsError(response)))
            }
        }
        val failed = { finish(SettingsResult<T>(error = SettingsError.NOT_READY)) }
        try { if (finalWrite) SettingsSpecialMutationContext.write(command, send, done, failed)
            else SettingsSpecialMutationContext.without {
                send(command, { native -> SettingsSpecialMutationContext.withReceipt(null) { done(native) } },
                    { SettingsSpecialMutationContext.withReceipt(null, failed) })
            } }
        catch (_: Exception) { finish(SettingsResult(error = SettingsError.REQUEST_FAILED)) }
    }
    private companion object { const val MAX_CATALOG = 512; const val PAGE_SIZE = 32 }
}
