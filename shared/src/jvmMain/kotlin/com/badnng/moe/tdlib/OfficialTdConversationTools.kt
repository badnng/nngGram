package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import org.drinkless.tdlib.TdApi
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Transient native search and information requests; no query or native response enters diagnostics. */
internal class OfficialTdConversationTools(
    private val send: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit,
) {
    private val overviewRevision = AtomicLong()

    fun invalidateSearchOverview() { overviewRevision.incrementAndGet() }

    fun search(query: ChatSearchQuery, offset: String, result: (ChatResult<ChatSearchPage>) -> Unit) {
        if (query.chatId == 0L || query.senderId == 0L || query.minDate < 0 || query.maxDate < 0 ||
            (query.maxDate != 0 && query.minDate > query.maxDate)) {
            result(failure()); return
        }
        if (query.chatId != null) {
            val from = if (offset.isEmpty()) 0L else offset.toLongOrNull()
            if (from == null || from < 0) { result(failure()); return }
            if (from == 0L && query.maxDate > 0) {
                request<TdApi.Message>(TdApi.GetChatMessageByDate(query.chatId, query.maxDate), { response ->
                    if (response is TdApi.Message && validMessage(response, query.chatId)) ChatResult(response)
                    else failure(response)
                }) { anchor ->
                    if (anchor.code == 404) result(ChatResult(ChatSearchPage()))
                    else if (anchor.value == null) result(copyFailure(anchor))
                    else searchChat(query, anchor.value.id, result)
                }
            } else searchChat(query, from, result)
            return
        }
        when (query.category) {
            ChatSearchCategory.CHANNELS, ChatSearchCategory.APPS -> {
                // A date or author has no meaning for a list of matching chat titles.
                if (hasMessageFilters(query)) { result(ChatResult(error = ChatError.UNSUPPORTED)); return }
                if (offset.isNotEmpty()) { result(ChatResult(ChatSearchPage())); return }
                searchChats(query) { response ->
                    if (response.value == null) result(copyFailure(response))
                    else result(ChatResult(ChatSearchPage(chats = response.value, totalCount = response.value.size)))
                }
            }
            ChatSearchCategory.POSTS -> request<TdApi.FoundPublicPosts>(
                // Zero is the only authorized price. A quota error must never trigger a paid retry.
                TdApi.SearchPublicPosts(query.text, offset, PAGE_SIZE, 0),
                { response -> if (response is TdApi.FoundPublicPosts) ChatResult(response) else failure(response) },
            ) { response ->
                val page = response.value
                if (page == null) result(copyFailure(response))
                else if (page.areLimitsExceeded) result(ChatResult(error = ChatError.RATE_LIMITED))
                else messagePage(query, page.messages, page.nextOffset.orEmpty(), 0, result)
            }
            else -> request<TdApi.FoundMessages>(
                TdApi.SearchMessages(null, query.text, offset, PAGE_SIZE, messageFilter(query.category), null,
                    query.minDate, query.maxDate),
                { response -> if (response is TdApi.FoundMessages) ChatResult(response) else failure(response) },
            ) { response ->
                val page = response.value
                if (page == null) { result(copyFailure(response)); return@request }
                messagePage(query, page.messages, page.nextOffset.orEmpty(), page.totalCount, { messages ->
                    if (messages.value == null || query.category != ChatSearchCategory.CHATS ||
                        offset.isNotEmpty() || hasMessageFilters(query)) result(messages)
                    else searchChats(query) { chats ->
                        if (chats.value == null) result(copyFailure(chats))
                        else result(ChatResult(messages.value.copy(chats =
                            (chats.value + messages.value.chats).distinctBy(ChatItem::id))))
                    }
                })
            }
        }
    }

    private fun searchChat(query: ChatSearchQuery, from: Long, result: (ChatResult<ChatSearchPage>) -> Unit) {
        request<TdApi.FoundChatMessages>(TdApi.SearchChatMessages(
            query.chatId!!, null, query.text, nativeSender(query.senderId), from, 0, PAGE_SIZE, messageFilter(query.category),
        ), { response -> if (response is TdApi.FoundChatMessages) ChatResult(response) else failure(response) }) { response ->
            val page = response.value
            if (page == null) { result(copyFailure(response)); return@request }
            // Results are newest first. Once the minimum date is crossed, older pages cannot match.
            val passedMinimum = query.minDate > 0 && page.messages.orEmpty().filterNotNull().any { it.date < query.minDate }
            val next = page.nextFromMessageId.takeIf { it > 0 && it != from && !passedMinimum }?.toString().orEmpty()
            messagePage(query, page.messages, next, page.totalCount, result)
        }
    }

    /** Pair order is frequent, recent. Both lists contain actual GetChat results in native rank order. */
    fun searchOverview(result: (ChatResult<Pair<List<ChatItem>, List<ChatItem>>>) -> Unit) {
        val revision = overviewRevision.get()
        fun active() = overviewRevision.get() == revision
        fun <T> overviewRequest(command: TdApi.Function<*>, map: (TdApi.Object) -> ChatResult<T>,
            completed: (ChatResult<T>) -> Unit) {
            if (active()) request(command, map) { if (active()) completed(it) }
        }
        fun recent(frequent: List<ChatItem>) {
            overviewRequest<TdApi.Chats>(TdApi.SearchChats("", null, PAGE_SIZE), ::chatIds) { response ->
                if (response.value == null) result(copyFailure(response))
                else fetchChats(response.value.chatIds?.toList().orEmpty(), ::active) { chats ->
                    if (chats.value == null) result(copyFailure(chats))
                    else result(ChatResult(frequent to chats.value))
                }
            }
        }
        // Disabled GetTopChats is a native 400. Read the actual option instead of swallowing that
        // error, so recent searches still work and unrelated native failures retain their meaning.
        overviewRequest<Boolean>(TdApi.GetOption("disable_top_chats"), { response ->
            mapDisableTopChatsOption(response)?.let { ChatResult(it) } ?: failure(response)
        }) { disabled ->
            when (disabled.value) {
                null -> result(copyFailure(disabled))
                true -> recent(emptyList())
                false -> overviewRequest<TdApi.Chats>(TdApi.GetTopChats(TdApi.TopChatCategoryUsers(), 8), ::chatIds) { frequent ->
                    if (frequent.value == null) result(copyFailure(frequent))
                    else fetchChats(frequent.value.chatIds?.toList().orEmpty(), ::active) { chats ->
                        if (chats.value == null) result(copyFailure(chats))
                        else recent(chats.value)
                    }
                }
            }
        }
    }

    fun addRecentlyFoundChat(chatId: Long, result: (ChatResult<Unit>) -> Unit) {
        if (chatId == 0L) { result(failure()); return }
        request(TdApi.AddRecentlyFoundChat(chatId), ::ok, result)
    }

    fun clearRecentlyFoundChats(result: (ChatResult<Unit>) -> Unit) =
        request(TdApi.ClearRecentlyFoundChats(), ::ok, result)

    fun chatDetails(chat: ChatItem, result: (ChatResult<ChatDetails>) -> Unit) {
        if (chat.id == 0L) { result(failure()); return }
        request<TdApi.Chat>(TdApi.GetChat(chat.id), { response ->
            if (response is TdApi.Chat && response.id == chat.id) ChatResult(response) else failure(response)
        }) { nativeChat ->
            val value = nativeChat.value
            if (value == null) { result(copyFailure(nativeChat)); return@request }
            when (val type = value.type) {
                is TdApi.ChatTypeBasicGroup -> groupDetails(chat.id, TdApi.GetBasicGroupFullInfo(type.basicGroupId), result)
                is TdApi.ChatTypeSupergroup -> groupDetails(chat.id, TdApi.GetSupergroupFullInfo(type.supergroupId), result)
                is TdApi.ChatTypePrivate -> request(TdApi.GetUserFullInfo(type.userId), { response ->
                    if (response is TdApi.UserFullInfo) ChatResult(ChatDetails(chat.id, response.bio?.text.orEmpty()))
                    else failure(response)
                }, result)
                else -> result(ChatResult(error = ChatError.UNSUPPORTED))
            }
        }
    }

    private fun groupDetails(chatId: Long, command: TdApi.Function<*>, result: (ChatResult<ChatDetails>) -> Unit) {
        request<String>(command, { response ->
            when (response) {
                is TdApi.BasicGroupFullInfo -> ChatResult(response.description.orEmpty())
                is TdApi.SupergroupFullInfo -> ChatResult(response.description.orEmpty())
                else -> failure(response)
            }
        }) { description ->
            if (description.value == null) { result(copyFailure(description)); return@request }
            request<List<TdApi.ChatAdministrator>>(TdApi.GetChatAdministrators(chatId), { response ->
                if (response is TdApi.ChatAdministrators) {
                    val administrators = response.administrators.orEmpty().filterNotNull()
                    if (administrators.any { it.userId <= 0 }) failure()
                    else ChatResult(administrators.distinctBy { it.userId })
                } else failure(response)
            }) { administrators ->
                if (administrators.value == null) result(copyFailure(administrators))
                else resolveAdministrators(administrators.value, 0, emptyList()) { resolved ->
                    if (resolved.value == null) result(copyFailure(resolved))
                    else result(ChatResult(ChatDetails(chatId, description.value, resolved.value)))
                }
            }
        }
    }

    private fun resolveAdministrators(
        administrators: List<TdApi.ChatAdministrator>, index: Int, resolved: List<ChatAdministrator>,
        result: (ChatResult<List<ChatAdministrator>>) -> Unit,
    ) {
        if (index >= administrators.size) { result(ChatResult(resolved)); return }
        val administrator = administrators[index]
        request<ChatAdministrator>(TdApi.GetUser(administrator.userId), { response ->
            if (response is TdApi.User && response.id == administrator.userId)
                ChatResult(ChatAdministrator(response.id, mapAccountProfile(response).displayName,
                    administrator.customTitle.orEmpty(), administrator.isOwner))
            else failure(response)
        }) { user ->
            if (user.value == null) result(copyFailure(user))
            else resolveAdministrators(administrators, index + 1, resolved + user.value, result)
        }
    }

    fun pinnedMessages(chatId: Long, fromMessageId: Long, result: (ChatResult<ChatSearchPage>) -> Unit) {
        if (chatId == 0L || fromMessageId < 0) { result(failure()); return }
        request<TdApi.FoundChatMessages>(TdApi.SearchChatMessages(chatId, null, "", null, fromMessageId, 0,
            PAGE_SIZE, TdApi.SearchMessagesFilterPinned()), { response ->
            if (response is TdApi.FoundChatMessages) ChatResult(response) else failure(response)
        }) { response ->
            val page = response.value
            if (page == null) result(copyFailure(response))
            else messagePage(ChatSearchQuery(chatId = chatId), page.messages,
                page.nextFromMessageId.takeIf { it > 0 && it != fromMessageId }?.toString().orEmpty(), page.totalCount, result)
        }
    }

    fun historyAround(chatId: Long, messageId: Long, result: (ChatResult<List<ChatMessage>>) -> Unit) {
        if (chatId == 0L || messageId <= 0) { result(failure()); return }
        request(TdApi.GetChatHistory(chatId, messageId, -20, PAGE_SIZE, false), { response ->
            if (response is TdApi.Messages) {
                val messages = response.messages.orEmpty().filterNotNull()
                if (messages.any { !validMessage(it, chatId) }) failure()
                else ChatResult(messages.map(::mapChatMessage))
            } else failure(response)
        }, result)
    }

    fun newerHistory(chatId: Long, fromMessageId: Long, result: (ChatResult<List<ChatMessage>>) -> Unit) {
        if (chatId == 0L || fromMessageId <= 0) { result(failure()); return }
        request(TdApi.GetChatHistory(chatId, fromMessageId, 1 - PAGE_SIZE, PAGE_SIZE, false), { response ->
            if (response is TdApi.Messages) {
                val messages = response.messages.orEmpty().filterNotNull()
                if (messages.any { !validMessage(it, chatId) }) failure()
                else ChatResult(messages.map(::mapChatMessage))
            } else failure(response)
        }, result)
    }

    private fun messagePage(
        query: ChatSearchQuery, nativeMessages: Array<TdApi.Message>?, next: String, total: Int,
        result: (ChatResult<ChatSearchPage>) -> Unit,
    ) {
        val native = nativeMessages.orEmpty().filterNotNull()
        if (native.any { !validMessage(it, query.chatId) }) { result(failure()); return }
        val messages = native.map(::mapChatMessage).filter { message ->
            (query.minDate == 0 || message.date >= query.minDate) &&
                (query.maxDate == 0 || message.date <= query.maxDate) &&
                (query.senderId == null || message.senderId == query.senderId)
        }.distinctBy { it.chatId to it.id }
        fetchChats(messages.map(ChatMessage::chatId)) { chats ->
            if (chats.value == null) result(copyFailure(chats))
            // TDLib doesn't expose an exact total after the client-side author/date filters.
            else result(ChatResult(ChatSearchPage(chats.value, messages, next,
                if (query.senderId != null || (query.chatId != null && hasMessageFilters(query))) 0 else total.coerceAtLeast(0))))
        }
    }

    private fun searchChats(query: ChatSearchQuery, result: (ChatResult<List<ChatItem>>) -> Unit) {
        val type = when (query.category) {
            ChatSearchCategory.CHANNELS -> TdApi.SearchChatTypeFilterChannel()
            ChatSearchCategory.APPS -> TdApi.SearchChatTypeFilterBot()
            else -> null
        }
        val commands = listOf(TdApi.SearchChats(query.text, type, PAGE_SIZE),
            TdApi.SearchChatsOnServer(query.text, type, PAGE_SIZE), TdApi.SearchPublicChats(query.text, type))
        val ids = mutableListOf<Long>()
        var succeeded = false
        var firstFailure: ChatResult<TdApi.Chats>? = null
        fun next(index: Int) {
            if (index == commands.size) {
                if (succeeded) fetchChats(ids, result) else result(copyFailure(firstFailure ?: failure<TdApi.Chats>()))
                return
            }
            request<TdApi.Chats>(commands[index], ::chatIds) { response ->
                if (response.value == null) { if (firstFailure == null) firstFailure = response }
                else { succeeded = true; ids += response.value.chatIds?.toList().orEmpty() }
                next(index + 1)
            }
        }
        next(0)
    }

    private fun fetchChats(ids: List<Long>, result: (ChatResult<List<ChatItem>>) -> Unit) {
        fetchChats(ids, { true }, result)
    }

    private fun fetchChats(ids: List<Long>, active: () -> Boolean, result: (ChatResult<List<ChatItem>>) -> Unit) {
        val unique = ids.distinct()
        if (unique.any { it == 0L }) { result(failure()); return }
        val chats = mutableListOf<ChatItem>()
        fun next(index: Int) {
            if (!active()) return
            if (index == unique.size) { result(ChatResult(chats.toList())); return }
            val id = unique[index]
            request<ChatItem>(TdApi.GetChat(id), { response ->
                if (response is TdApi.Chat && response.id == id) ChatResult(mapSearchChat(response)) else failure(response)
            }) { response ->
                if (!active()) return@request
                if (response.value == null) result(copyFailure(response))
                else { chats += response.value; next(index + 1) }
            }
        }
        next(0)
    }

    private fun mapSearchChat(chat: TdApi.Chat): ChatItem {
        // Reuse the normal native chat mapper. Unknown group membership remains conservatively read-only
        // until the actor receives the corresponding membership update.
        var mapped: ChatItem? = null
        TdChatUpdateMapper { event -> if (event is ChatEvent.NewChat) mapped = event.chat }
            .accept(TdApi.UpdateNewChat(chat))
        return checkNotNull(mapped)
    }

    private fun messageFilter(category: ChatSearchCategory): TdApi.SearchMessagesFilter? = when (category) {
        ChatSearchCategory.PHOTOS -> TdApi.SearchMessagesFilterPhoto()
        ChatSearchCategory.VIDEOS -> TdApi.SearchMessagesFilterVideo()
        ChatSearchCategory.FILES -> TdApi.SearchMessagesFilterDocument()
        ChatSearchCategory.LINKS -> TdApi.SearchMessagesFilterUrl()
        ChatSearchCategory.MUSIC -> TdApi.SearchMessagesFilterAudio()
        ChatSearchCategory.VOICE -> TdApi.SearchMessagesFilterVoiceNote()
        else -> null
    }

    private fun nativeSender(id: Long?): TdApi.MessageSender? = when {
        id == null -> null
        id > 0 -> TdApi.MessageSenderUser(id)
        else -> TdApi.MessageSenderChat(id)
    }

    private fun hasMessageFilters(query: ChatSearchQuery) = query.senderId != null || query.minDate != 0 || query.maxDate != 0
    private fun validMessage(message: TdApi.Message, chatId: Long?) =
        message.id > 0 && message.chatId != 0L && (chatId == null || message.chatId == chatId) && message.content != null
    private fun chatIds(response: TdApi.Object): ChatResult<TdApi.Chats> =
        if (response is TdApi.Chats) ChatResult(response) else failure(response)
    private fun ok(response: TdApi.Object): ChatResult<Unit> =
        if (response is TdApi.Ok) ChatResult(Unit) else failure(response)
    private fun <T> copyFailure(response: ChatResult<*>) =
        ChatResult<T>(error = response.error ?: ChatError.REQUEST_FAILED, code = response.code)
    private fun <T> failure(response: TdApi.Object? = null): ChatResult<T> =
        if (response is TdApi.Error) ChatResult(error = classifyChatError(response), code = response.code)
        else ChatResult(error = ChatError.REQUEST_FAILED, code = -1)

    private fun <T> request(command: TdApi.Function<*>, map: (TdApi.Object) -> ChatResult<T>, result: (ChatResult<T>) -> Unit) {
        val completed = AtomicBoolean()
        fun complete(value: ChatResult<T>) { if (completed.compareAndSet(false, true)) result(value) }
        try {
            send(command, { response ->
                val value = try { map(response) } catch (_: Exception) { failure() }
                complete(value)
            }, { complete(failure()) })
        } catch (_: Exception) { complete(failure()) }
    }

    private companion object {
        const val PAGE_SIZE = 50
    }
}
