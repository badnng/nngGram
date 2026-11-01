package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import java.util.concurrent.atomic.AtomicBoolean

/** User-only Add chooser; metadata requests never open a chat or download media. */
internal class OfficialTdSettingsBlockedPeers(
    private val send: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit,
) {
    fun search(query: SettingsBlockedPeerQuery, result: (SettingsResult<SettingsBlockedCandidates>) -> Unit) {
        val requestedLong = query.offset.toLong() + query.limit.toLong() + 1L
        if (query.limit !in 1..100 || query.query.length > 256 || query.offset < 0 || requestedLong > Int.MAX_VALUE ||
            query.source == SettingsBlockedPeerSource.SERVER && query.query.isBlank()) {
            result(SettingsResult(error = SettingsError.INVALID_VALUE)); return
        }
        // Bound the actual native ID array allocation, not the number of reachable candidates.
        // No source truncation or exhaustion is inferred when this physical request cannot fit.
        val estimatedPrefixBytes = RAW_ARRAY_HEADER_BYTES + requestedLong * java.lang.Long.BYTES
        if (estimatedPrefixBytes > MAX_RAW_PREFIX_BYTES) {
            result(SettingsResult(error = SettingsError.REQUEST_FAILED)); return
        }
        val requested = requestedLong.toInt()
        context { response ->
            val excluded = response.value
            if (excluded == null) { result(SettingsResult(error = response.error)); return@context }
            // These native methods have a limit, not an offset. Page only the returned raw prefix;
            // filtering users must never turn a short visible page into inferred exhaustion.
            fun resolvePage(raw: LongArray, total: Int, chats: Boolean) {
                if (raw.size > requested || RAW_ARRAY_HEADER_BYTES + raw.size.toLong() * java.lang.Long.BYTES > MAX_RAW_PREFIX_BYTES) {
                    result(SettingsResult(error = SettingsError.REQUEST_FAILED)); return
                }
                // Retain only a bounded page. Do not box/copy the complete growing prefix.
                val start = query.offset.coerceAtMost(raw.size)
                val slice = raw.copyOfRange(start, (start.toLong() + query.limit).coerceAtMost(raw.size.toLong()).toInt())
                val nextOffset = query.offset + slice.size
                val knownMore = raw.size > nextOffset
                // Pinned DialogManager::search_dialogs_on_server clamps its prefix to 100.
                // Its Chats.totalCount is the returned size, not a proven directory total.
                val serverLimited = query.source == SettingsBlockedPeerSource.SERVER &&
                    !knownMore && raw.size >= NATIVE_SERVER_PREFIX_LIMIT
                val end = !knownMore && raw.size < requested && total >= 0 && total <= raw.size && !serverLimited
                if (!end && !serverLimited && !knownMore && nextOffset <= query.offset) {
                    // An unknown/incomplete native boundary that cannot advance is a failed read,
                    // not exhaustion or an invented source limit. The user may retry/refine it.
                    result(SettingsResult(error = SettingsError.REQUEST_FAILED)); return
                }
                val limitReached = serverLimited
                val values = mutableListOf<SettingsBlockedCandidate>()
                fun next(index: Int) {
                    if (index >= slice.size) {
                        result(SettingsResult(SettingsBlockedCandidates(values.distinctBy { it.userId }, nextOffset, end, limitReached)))
                        return
                    }
                    resolve(slice[index], chats, excluded) { candidate ->
                        if (candidate.value != null) { values += candidate.value; next(index + 1) }
                        else if (candidate.error == SettingsError.INVALID_VALUE) next(index + 1)
                        else result(SettingsResult(error = candidate.error ?: SettingsError.REQUEST_FAILED))
                    }
                }
                next(0)
            }
            if (query.source == SettingsBlockedPeerSource.CONTACTS) {
                request(TdApi.SearchContacts(query.query, requested), { it as? TdApi.Users }) { response ->
                    val native = response.value
                    if (native?.userIds == null) result(SettingsResult(error = response.error ?: SettingsError.REQUEST_FAILED))
                    else resolvePage(native.userIds, native.totalCount, false)
                }
            } else {
                val command = when {
                    query.source == SettingsBlockedPeerSource.SERVER -> TdApi.SearchChatsOnServer(query.query, null, requested)
                    query.query.isBlank() -> TdApi.GetChats(TdApi.ChatListMain(), requested)
                    else -> TdApi.SearchChats(query.query, null, requested)
                }
                request(command, { it as? TdApi.Chats }) { response ->
                    val native = response.value
                    if (native?.chatIds == null) result(SettingsResult(error = response.error ?: SettingsError.REQUEST_FAILED))
                    else resolvePage(native.chatIds, native.totalCount, true)
                }
            }
        }
    }

    /** Controller admission is required; re-read native identity and current main-block state. */
    fun block(userId: Long, result: (SettingsResult<Unit>) -> Unit) {
        context { response ->
            val excluded = response.value
            if (excluded == null) { result(SettingsResult(error = response.error)); return@context }
            resolve(userId, false, excluded) { fresh ->
                val user = fresh.value
                if (user == null) result(SettingsResult(error = fresh.error ?: SettingsError.INVALID_VALUE))
                else if (user.blocked) result(SettingsResult(error = SettingsError.CONFLICT))
                else request(TdApi.SetMessageSenderBlockList(TdApi.MessageSenderUser(user.userId), TdApi.BlockListMain()),
                    { if (it is TdApi.Ok) Unit else null }, result, mutation = true)
            }
        }
    }

    private fun context(result: (SettingsResult<Set<Long>>) -> Unit) {
        request(TdApi.GetMe(), { (it as? TdApi.User)?.takeIf { user -> user.id > 0 } }) { me ->
            val user = me.value
            if (user == null) { result(SettingsResult(error = me.error ?: SettingsError.NOT_READY)); return@request }
            request(TdApi.GetOption("replies_bot_chat_id"), { (it as? TdApi.OptionValueInteger)?.value?.takeIf { id -> id > 0 } }) replies@ { replies ->
                val repliesId = replies.value
                if (repliesId == null) { result(SettingsResult(error = replies.error ?: SettingsError.NOT_READY)); return@replies }
                request(TdApi.GetOption("verification_codes_bot_chat_id"), { (it as? TdApi.OptionValueInteger)?.value?.takeIf { id -> id > 0 } }) { verify ->
                    val verifyId = verify.value
                    if (verifyId == null) result(SettingsResult(error = verify.error ?: SettingsError.NOT_READY))
                    else result(SettingsResult(setOf(user.id, repliesId, verifyId)))
                }
            }
        }
    }

    private fun resolve(id: Long, isChat: Boolean, excluded: Set<Long>, result: (SettingsResult<SettingsBlockedCandidate>) -> Unit) {
        if (isChat) {
            if (id == 0L) { result(SettingsResult(error = SettingsError.INVALID_VALUE)); return }
            request(TdApi.GetChat(id), { (it as? TdApi.Chat)?.takeIf { chat -> chat.id == id } }) { response ->
                val chat = response.value
                val type = chat?.type as? TdApi.ChatTypePrivate
                if (chat == null) result(SettingsResult(error = response.error ?: SettingsError.INVALID_VALUE))
                else if (type == null) result(SettingsResult(error = SettingsError.INVALID_VALUE))
                else resolve(type.userId, false, excluded, result)
            }
            return
        }
        // Desktop a7f4536 data_peer.h PeerData::isServiceUser(): user id divisible by 1000.
        if (id <= 0 || id % 1000L == 0L || id in excluded) { result(SettingsResult(error = SettingsError.INVALID_VALUE)); return }
        request(TdApi.GetUser(id), { it as? TdApi.User }) { response ->
            val user = response.value
            if (user == null) { result(SettingsResult(error = response.error ?: SettingsError.REQUEST_FAILED)); return@request }
            if (user.id != id || user.type !is TdApi.UserTypeRegular && user.type !is TdApi.UserTypeBot) {
                result(SettingsResult(error = SettingsError.INVALID_VALUE)); return@request
            }
            request(TdApi.GetUserFullInfo(id), { it as? TdApi.UserFullInfo }) { full ->
                val info = full.value
                if (info == null) result(SettingsResult(error = full.error ?: SettingsError.REQUEST_FAILED))
                else result(SettingsResult(SettingsBlockedCandidate(id,
                    listOf(user.firstName.orEmpty(), user.lastName.orEmpty()).filter(String::isNotBlank)
                        .joinToString(" ").ifBlank { "未命名用户" }, info.blockList is TdApi.BlockListMain)))
            }
        }
    }

    private fun <T> request(command: TdApi.Function<*>, map: (TdApi.Object) -> T?, result: (SettingsResult<T>) -> Unit) =
        request(command, map, result, mutation = false)
    private fun <T> request(command: TdApi.Function<*>, map: (TdApi.Object) -> T?, result: (SettingsResult<T>) -> Unit,
        mutation: Boolean,
    ) {
        val completed = AtomicBoolean()
        fun finish(response: SettingsResult<T>) {
            if (mutation && SettingsSpecialMutationContext.receipt()?.outcome == SettingsSpecialWriteOutcome.INDETERMINATE) {
                if (!completed.get()) result(response)
            } else if (completed.compareAndSet(false, true)) result(response)
        }
        val receive: (TdApi.Object) -> Unit = { response ->
            val value = runCatching { map(response) }.getOrNull()
            finish(if (value != null) SettingsResult(value) else SettingsResult(error = settingsError(response)))
        }
        val failed = { finish(SettingsResult<T>(error = SettingsError.NOT_READY)) }
        try {
            if (mutation) SettingsSpecialMutationContext.write(command, send, receive, failed)
            else send(command, receive, failed)
        } catch (_: Exception) { finish(SettingsResult(error = SettingsError.REQUEST_FAILED)) }
    }

    private companion object {
        const val MAX_RAW_PREFIX_BYTES = 16L * 1024 * 1024
        const val RAW_ARRAY_HEADER_BYTES = 32L
        // TDLib 9d92b49 DialogManager.h:342; this is its actual server source limit.
        const val NATIVE_SERVER_PREFIX_LIMIT = 100
    }
}
