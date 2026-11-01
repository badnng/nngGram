package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import java.util.concurrent.atomic.AtomicBoolean

/** Metadata only: no OpenChat/history, full-info, file download or public identity fabrication. */
internal class OfficialTdSettingsPrivacyPeers(
    private val send: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit,
) {
    fun search(query: PrivacyPeerQuery, result: (SettingsResult<SettingsPrivacyPeers>) -> Unit) {
        if (query.limit !in listOf(32, 64, 100) || query.query.length > 256 ||
            query.source == PrivacyPeerSource.SERVER && query.query.isBlank() ||
            query.source == PrivacyPeerSource.SELECTED && query.selected.size > 32) {
            result(SettingsResult(error = SettingsError.INVALID_VALUE)); return
        }
        if (query.source == PrivacyPeerSource.SELECTED && query.selected.isEmpty()) {
            result(SettingsResult(SettingsPrivacyPeers(emptyList(), false))); return
        }
        context { response ->
            val context = response.value
            if (context == null) { result(SettingsResult(error = response.error)); return@context }
            fun resolve(ids: List<PrivacyPeerId>, more: Boolean) {
                resolveAll(ids.distinct().take(query.limit), context, strict = false) { peers ->
                    if (peers.value == null) result(SettingsResult(error = peers.error))
                    else result(SettingsResult(SettingsPrivacyPeers(peers.value, more)))
                }
            }
            when (query.source) {
                PrivacyPeerSource.SELECTED -> resolve(query.selected, false)
                PrivacyPeerSource.CONTACTS -> request(TdApi.SearchContacts(query.query, query.limit), { it as? TdApi.Users }) {
                    val users = it.value
                    if (users == null) result(SettingsResult(error = it.error))
                    else resolve((users.userIds ?: longArrayOf()).take(query.limit).map { id -> PrivacyPeerId(id, false) }, (users.userIds ?: longArrayOf()).size >= query.limit)
                }
                PrivacyPeerSource.CHATS, PrivacyPeerSource.SERVER -> {
                    val command = when {
                        query.source == PrivacyPeerSource.SERVER -> TdApi.SearchChatsOnServer(query.query, null, query.limit)
                        query.query.isBlank() -> TdApi.GetChats(TdApi.ChatListMain(), query.limit)
                        else -> TdApi.SearchChats(query.query, null, query.limit)
                    }
                    request(command, { it as? TdApi.Chats }) {
                        val chats = it.value
                        if (chats == null) result(SettingsResult(error = it.error))
                        else resolve((chats.chatIds ?: longArrayOf()).take(query.limit).map { id -> PrivacyPeerId(id, true) }, (chats.chatIds ?: longArrayOf()).size >= query.limit)
                    }
                }
            }
        }
    }

    /** All newly submitted identities are re-read; existing native rules provide their own identity evidence. */
    fun validate(key: PrivacyKey, identities: List<PrivacyPeerId>, result: (SettingsResult<Unit>) -> Unit) {
        if (identities.size > 1020) { result(SettingsResult(error = SettingsError.INVALID_VALUE)); return }
        context { response ->
            val context = response.value
            if (context == null) { result(SettingsResult(error = response.error)); return@context }
            if (key == PrivacyKey.VOICE_VIDEO_MESSAGES && !context.premium) {
                result(SettingsResult(error = SettingsError.FORBIDDEN)); return@context
            }
            resolveAll(identities.distinct(), context, strict = true) {
                result(if (it.value != null) SettingsResult(Unit) else SettingsResult(error = it.error))
            }
        }
    }

    private data class Context(val excluded: Set<Long>, val premium: Boolean)
    private fun context(result: (SettingsResult<Context>) -> Unit) {
        request(TdApi.GetMe(), { it as? TdApi.User }) { me ->
            val user = me.value
            if (user == null || user.id <= 0) { result(SettingsResult(error = me.error ?: SettingsError.NOT_READY)); return@request }
            request(TdApi.GetOption("replies_bot_chat_id"), { (it as? TdApi.OptionValueInteger)?.value?.takeIf { id -> id > 0 } }) replies@ { replies ->
                val repliesId = replies.value
                if (repliesId == null) { result(SettingsResult(error = replies.error ?: SettingsError.NOT_READY)); return@replies }
                request(TdApi.GetOption("verification_codes_bot_chat_id"), { (it as? TdApi.OptionValueInteger)?.value?.takeIf { id -> id > 0 } }) verify@ { verify ->
                    val verifyId = verify.value
                    if (verifyId == null) result(SettingsResult(error = verify.error ?: SettingsError.NOT_READY))
                    else result(SettingsResult(Context(setOf(user.id, repliesId, verifyId), user.isPremium)))
                }
            }
        }
    }

    private fun resolveAll(ids: List<PrivacyPeerId>, context: Context, strict: Boolean,
        result: (SettingsResult<List<SettingsPrivacyPeer>>) -> Unit,
    ) {
        val values = mutableListOf<SettingsPrivacyPeer>()
        fun step(index: Int) {
            if (index >= ids.size) { result(SettingsResult(values.distinctBy { it.identity })); return }
            resolve(ids[index], context) { response ->
                val value = response.value
                if (strict && (value == null || value.identity != ids[index]))
                    result(SettingsResult(error = response.error ?: SettingsError.INVALID_VALUE))
                else if (value == null && response.error != SettingsError.INVALID_VALUE)
                    result(SettingsResult(error = response.error ?: SettingsError.REQUEST_FAILED))
                else { value?.let(values::add); step(index + 1) }
            }
        }
        step(0)
    }

    private fun resolve(identity: PrivacyPeerId, context: Context, result: (SettingsResult<SettingsPrivacyPeer>) -> Unit) {
        if (!identity.isChat) {
            if (identity.id <= 0 || identity.id in context.excluded) { result(SettingsResult(error = SettingsError.INVALID_VALUE)); return }
            request(TdApi.GetUser(identity.id), { it as? TdApi.User }) {
                val user = it.value
                if (user == null || user.id != identity.id || user.type !is TdApi.UserTypeRegular && user.type !is TdApi.UserTypeBot)
                    result(SettingsResult(error = it.error ?: SettingsError.INVALID_VALUE))
                else result(SettingsResult(SettingsPrivacyPeer(identity,
                    listOf(user.firstName.orEmpty(), user.lastName.orEmpty()).filter(String::isNotBlank).joinToString(" ").ifBlank { "未命名用户" })))
            }
            return
        }
        if (identity.id == 0L) { result(SettingsResult(error = SettingsError.INVALID_VALUE)); return }
        request(TdApi.GetChat(identity.id), { it as? TdApi.Chat }) chat@ { response ->
            val chat = response.value
            if (chat == null || chat.id != identity.id) { result(SettingsResult(error = response.error ?: SettingsError.INVALID_VALUE)); return@chat }
            when (val type = chat.type) {
                is TdApi.ChatTypePrivate -> resolve(PrivacyPeerId(type.userId, false), context, result)
                is TdApi.ChatTypeBasicGroup -> request(TdApi.GetBasicGroup(type.basicGroupId), { it as? TdApi.BasicGroup }) {
                    val group = it.value
                    if (group == null || group.id != type.basicGroupId || !group.isActive) result(SettingsResult(error = it.error ?: SettingsError.INVALID_VALUE))
                    else result(SettingsResult(SettingsPrivacyPeer(identity, chat.title.orEmpty().ifBlank { "未命名群组" }, true)))
                }
                is TdApi.ChatTypeSupergroup -> if (type.isChannel) result(SettingsResult(error = SettingsError.INVALID_VALUE))
                    else request(TdApi.GetSupergroup(type.supergroupId), { it as? TdApi.Supergroup }) {
                        val group = it.value
                        if (group == null || group.id != type.supergroupId || group.isChannel || group.isDirectMessagesGroup)
                            result(SettingsResult(error = it.error ?: SettingsError.INVALID_VALUE))
                        else result(SettingsResult(SettingsPrivacyPeer(identity, chat.title.orEmpty().ifBlank { "未命名群组" }, true)))
                    }
                else -> result(SettingsResult(error = SettingsError.INVALID_VALUE))
            }
        }
    }

    private fun <T> request(command: TdApi.Function<*>, map: (TdApi.Object) -> T?, result: (SettingsResult<T>) -> Unit) {
        val complete = AtomicBoolean()
        fun finish(response: SettingsResult<T>) { if (complete.compareAndSet(false, true)) result(response) }
        try { send(command, { response ->
            val value = runCatching { map(response) }.getOrNull()
            finish(if (value == null) SettingsResult(error = settingsError(response)) else SettingsResult(value))
        }, { finish(SettingsResult(error = SettingsError.REQUEST_FAILED)) }) }
        catch (_: Exception) { finish(SettingsResult(error = SettingsError.REQUEST_FAILED)) }
    }
}
