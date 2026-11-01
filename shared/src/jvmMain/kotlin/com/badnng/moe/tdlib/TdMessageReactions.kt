package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import org.drinkless.tdlib.TdApi
import java.util.concurrent.atomic.AtomicBoolean

/** Native allowed identities and native artwork; paid reactions are never dispatched here. */
internal class OfficialTdMessageReactions(
    private val send: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit,
) {
    fun available(chatId: Long, messageId: Long, result: (ChatResult<MessageReactionOptions>) -> Unit) {
        if (chatId == 0L || messageId <= 0) { result(failure()); return }
        request(TdApi.GetMessageAvailableReactions(chatId, messageId, 8), { response ->
            if (response is TdApi.AvailableReactions) ChatResult(mapAvailableReactions(response)) else failure(response)
        }, result)
    }

    fun set(chatId: Long, messageId: Long, emoji: String, add: Boolean, result: (ChatResult<Unit>) -> Unit) {
        set(chatId, messageId, ReactionKey(emoji), add, result)
    }

    fun set(chatId: Long, messageId: Long, key: ReactionKey, add: Boolean, result: (ChatResult<Unit>) -> Unit) {
        if (chatId == 0L || messageId <= 0 || !key.valid) { result(failure()); return }
        request<TdApi.Message>(TdApi.GetMessage(chatId, messageId), { response ->
            if (response is TdApi.Message && response.chatId == chatId && response.id == messageId &&
                response.sendingState == null && response.content != null) ChatResult(response) else failure(response)
        }) { response ->
            val message = response.value
            if (response.error != null || message == null) { result(ChatResult(error = response.error ?: ChatError.REQUEST_FAILED, code = response.code)); return@request }
            val chosen = mapMessageReactions(message.interactionInfo?.reactions).any { it.canToggle && it.key == key && it.isChosen }
            if (chosen == add) { result(ChatResult(Unit)); return@request }
            val native = if (key.customEmojiId != 0L) TdApi.ReactionTypeCustomEmoji(key.customEmojiId) else TdApi.ReactionTypeEmoji(key.emoji)
            if (!add) {
                // TDLib explicitly permits removing an already chosen reaction even after additions are disabled.
                request(TdApi.RemoveMessageReaction(chatId, messageId, native), ::ok, result)
            } else available(chatId, messageId) { options ->
                if (options.error != null) result(ChatResult(error = options.error, code = options.code))
                else {
                    val allowed = options.value
                    val choice = allowed?.allChoices()?.firstOrNull { it.key == key }
                    fun dispatch() = request(TdApi.AddMessageReaction(chatId, messageId, native, false, true), ::ok, result)
                    when {
                        allowed?.canAdd != true -> result(ChatResult(error = ChatError.FORBIDDEN))
                        choice != null && !choice.needsPremium -> dispatch()
                        key.customEmojiId != 0L && allowed.allowCustomEmoji -> request(TdApi.GetMe(), { account ->
                            if (account is TdApi.User) ChatResult(account.isPremium) else failure(account)
                        }) { premium ->
                            if (premium.value == true) dispatch() else result(ChatResult(error = premium.error ?: ChatError.FORBIDDEN))
                        }
                        else -> result(ChatResult(error = ChatError.FORBIDDEN))
                    }
                }
            }
        }
    }

    fun visual(key: ReactionKey, result: (ChatResult<ReactionVisual>) -> Unit) {
        if (!key.valid) { result(failure()); return }
        if (key.customEmojiId == 0L) request(TdApi.GetEmojiReaction(key.emoji), { response ->
            if (response is TdApi.EmojiReaction && response.emoji == key.emoji) ChatResult(ReactionVisual(key,
                mapSticker(response.staticIcon)?.media, mapSticker(response.selectAnimation)?.media,
                mapSticker(response.appearAnimation)?.media, mapSticker(response.centerAnimation)?.media,
                mapSticker(response.aroundAnimation)?.media)) else failure(response)
        }, result)
        else request(TdApi.GetCustomEmojiStickers(longArrayOf(key.customEmojiId)), { response ->
            val sticker = (response as? TdApi.Stickers)?.stickers.orEmpty().firstOrNull {
                (it?.fullType as? TdApi.StickerFullTypeCustomEmoji)?.customEmojiId == key.customEmojiId
            }
            val media = mapSticker(sticker)?.media
            if (media != null) {
                val thumbnail = media.thumbnail
                val thumbnailFormat = media.thumbnailFormat
                val still = if (thumbnail != null && thumbnailFormat != null) MessageMedia(thumbnail,
                    width = media.width, height = media.height, format = thumbnailFormat, emoji = media.emoji, setId = media.setId) else media
                ChatResult(ReactionVisual(key, still, media))
            } else failure(response)
        }, result)
    }

    fun packs(chatId: Long, result: (ChatResult<List<StickerPack>>) -> Unit) {
        request(TdApi.GetInstalledStickerSets(TdApi.StickerTypeCustomEmoji()), { response ->
            if (response is TdApi.StickerSets) ChatResult(response.sets.orEmpty().filterNotNull().take(100).map {
                StickerPack(it.id, it.title.orEmpty(), it.size, it.covers.orEmpty().firstOrNull()?.let(::mapSticker))
            }) else failure(response)
        }) { installed ->
            if (installed.error != null) { result(installed); return@request }
            request(TdApi.GetChat(chatId), { response ->
                if (response is TdApi.Chat && response.id == chatId) ChatResult(response) else failure(response)
            }) { chat ->
                val groupId = (chat.value?.type as? TdApi.ChatTypeSupergroup)?.supergroupId
                if (groupId == null) { result(installed); return@request }
                request(TdApi.GetSupergroupFullInfo(groupId), { response ->
                    if (response is TdApi.SupergroupFullInfo) ChatResult(response.customEmojiStickerSetId) else failure(response)
                }) { full ->
                    val setId = full.value?.takeIf { it != 0L }
                    if (setId == null || installed.value.orEmpty().any { it.id == setId }) { result(installed); return@request }
                    request(TdApi.GetStickerSet(setId), { response ->
                        if (response is TdApi.StickerSet && response.id == setId && response.stickerType is TdApi.StickerTypeCustomEmoji)
                            ChatResult(listOf(StickerPack(response.id, response.title.orEmpty(), response.stickers.orEmpty().size,
                                response.stickers.orEmpty().firstOrNull()?.let(::mapSticker))) + installed.value.orEmpty()) else installed
                    }, result)
                }
            }
        }
    }

    fun pack(setId: Long, result: (ChatResult<List<ReactionChoice>>) -> Unit) {
        if (setId == 0L) { result(failure()); return }
        request(TdApi.GetStickerSet(setId), { response ->
            if (response is TdApi.StickerSet && response.id == setId && response.stickerType is TdApi.StickerTypeCustomEmoji) {
                ChatResult(customChoices(response))
            } else failure(response)
        }, result)
    }

    /** A selected known group supplies artwork identities, never permission to use them in the target chat. */
    fun groupPack(sourceChatId: Long, result: (ChatResult<ReactionGroupPack>) -> Unit) {
        if (sourceChatId == 0L) { result(failure()); return }
        request<TdApi.ChatTypeSupergroup>(TdApi.GetChat(sourceChatId), { response ->
            if (response !is TdApi.Chat || response.id != sourceChatId) failure(response)
            else {
                val type = response.type as? TdApi.ChatTypeSupergroup
                if (type == null || type.isChannel || type.supergroupId == 0L) ChatResult(error = ChatError.UNSUPPORTED)
                else ChatResult(type)
            }
        }) chat@{ chat ->
            val type = chat.value
            if (type == null || chat.error != null) { result(ChatResult(error = chat.error ?: ChatError.REQUEST_FAILED, code = chat.code)); return@chat }
            request<TdApi.Supergroup>(TdApi.GetSupergroup(type.supergroupId), { response ->
                when {
                    response !is TdApi.Supergroup || response.id != type.supergroupId -> failure(response)
                    response.isChannel -> ChatResult(error = ChatError.UNSUPPORTED)
                    !isJoinedReactionGroup(response.status) -> ChatResult(error = ChatError.FORBIDDEN)
                    else -> ChatResult(response)
                }
            }) group@{ group ->
                if (group.value == null || group.error != null) { result(ChatResult(error = group.error ?: ChatError.REQUEST_FAILED, code = group.code)); return@group }
                request<Long>(TdApi.GetSupergroupFullInfo(type.supergroupId), { response ->
                    if (response !is TdApi.SupergroupFullInfo) failure(response)
                    else if (response.customEmojiStickerSetId == 0L) ChatResult(error = ChatError.UNSUPPORTED)
                    else ChatResult(response.customEmojiStickerSetId)
                }) full@{ full ->
                    val setId = full.value
                    if (setId == null || full.error != null) { result(ChatResult(error = full.error ?: ChatError.REQUEST_FAILED, code = full.code)); return@full }
                    request(TdApi.GetStickerSet(setId), { response ->
                        if (response is TdApi.StickerSet && response.id == setId && response.stickerType is TdApi.StickerTypeCustomEmoji) {
                            val pack = StickerPack(response.id, response.title.orEmpty(), response.stickers.orEmpty().size,
                                response.stickers.orEmpty().firstOrNull()?.let(::mapSticker))
                            ChatResult(ReactionGroupPack(sourceChatId, pack, customChoices(response)))
                        } else failure(response)
                    }, result)
                }
            }
        }
    }

    private fun customChoices(set: TdApi.StickerSet): List<ReactionChoice> = set.stickers.orEmpty().asSequence().mapNotNull {
        (it?.fullType as? TdApi.StickerFullTypeCustomEmoji)?.customEmojiId?.takeIf { id -> id != 0L }
    }.distinct().take(1000).map { ReactionChoice(ReactionKey(customEmojiId = it), source = ReactionSource.PACK) }.toList()

    private fun ok(response: TdApi.Object): ChatResult<Unit> = if (response is TdApi.Ok) ChatResult(Unit) else failure(response)
    private fun <T> failure(response: TdApi.Object? = null): ChatResult<T> = if (response is TdApi.Error)
        ChatResult(error = classifyChatError(response), code = response.code) else ChatResult(error = ChatError.REQUEST_FAILED)

    private fun <T> request(command: TdApi.Function<*>, map: (TdApi.Object) -> ChatResult<T>, result: (ChatResult<T>) -> Unit) {
        val completed = AtomicBoolean()
        fun complete(value: ChatResult<T>) { if (completed.compareAndSet(false, true)) result(value) }
        try {
            send(command, { response -> complete(try { map(response) } catch (_: Exception) { failure() }) }, { complete(failure()) })
        } catch (_: Exception) { complete(failure()) }
    }
}

internal fun isJoinedReactionGroup(status: TdApi.ChatMemberStatus?): Boolean = when (status) {
    is TdApi.ChatMemberStatusCreator -> status.isMember
    is TdApi.ChatMemberStatusAdministrator, is TdApi.ChatMemberStatusMember -> true
    is TdApi.ChatMemberStatusRestricted -> status.isMember
    else -> false
}

internal fun mapAvailableReactions(value: TdApi.AvailableReactions): MessageReactionOptions {
    val allowed = value.unavailabilityReason == null
    val emojis = (value.topReactions.orEmpty().asSequence() + value.recentReactions.orEmpty().asSequence() + value.popularReactions.orEmpty().asSequence())
        .filter { it != null && !it.needsPremium }
        .mapNotNull { (it.type as? TdApi.ReactionTypeEmoji)?.emoji?.takeIf(String::isNotBlank) }
        .distinct().toList()
    val choices = listOf(value.topReactions to ReactionSource.TOP, value.recentReactions to ReactionSource.RECENT,
        value.popularReactions to ReactionSource.POPULAR).flatMap { (entries, source) ->
        entries.orEmpty().mapNotNull { entry ->
            val key = when (val type = entry?.type) {
                is TdApi.ReactionTypeEmoji -> type.emoji?.takeIf(String::isNotBlank)?.let { ReactionKey(it) }
                is TdApi.ReactionTypeCustomEmoji -> type.customEmojiId.takeIf { it != 0L }?.let { ReactionKey(customEmojiId = it) }
                else -> null
            }
            key?.let { ReactionChoice(it, entry.needsPremium, source) }
        }
    }.distinctBy { it.key }
    return MessageReactionOptions(emojis, allowed, choices, value.allowCustomEmoji)
}

internal fun mapMessageReactions(value: TdApi.MessageReactions?): List<MessageReaction> = value?.reactions.orEmpty().mapNotNull { reaction ->
    if (reaction == null || reaction.totalCount <= 0) return@mapNotNull null
    val senders = reaction.recentSenderIds.orEmpty().mapNotNull { sender ->
        when (sender) {
            is TdApi.MessageSenderUser -> sender.userId.takeIf { it > 0 }?.let { MessageReactionSender(it) }
            is TdApi.MessageSenderChat -> sender.chatId.takeIf { it != 0L }?.let { MessageReactionSender(it, senderIsChat = true) }
            else -> null
        }
    }.distinct()
    when (val type = reaction.type) {
        is TdApi.ReactionTypeEmoji -> type.emoji?.takeIf(String::isNotBlank)?.let { MessageReaction(it, reaction.totalCount, reaction.isChosen, recentSenders = senders) }
        is TdApi.ReactionTypeCustomEmoji -> MessageReaction(totalCount = reaction.totalCount, isChosen = reaction.isChosen, customEmojiId = type.customEmojiId, recentSenders = senders)
        is TdApi.ReactionTypePaid -> MessageReaction(totalCount = reaction.totalCount, isChosen = reaction.isChosen, isPaid = true, recentSenders = senders)
        else -> null
    }
}
