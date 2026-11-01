package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import com.badnng.moe.settings.SettingsProfileColorsCatalog
import org.drinkless.tdlib.TdApi
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil

interface TdChatSession {
    val folderSettings: TdFolderSettingsSession? get() = null
    val helpSupport: TdHelpSupportSession? get() = null
    /** Synthetic sessions may expose a known limit; the official session obtains it from TDLib. */
    val knownMessageTextLimit: Int? get() = 4096
    fun messageTextLimit(result: (ChatResult<Int>) -> Unit) { result(ChatResult(4096)) }
    fun linkConfirmationDomains(result: (ChatResult<Set<String>>) -> Unit) { result(ChatResult(error = ChatError.UNSUPPORTED)) }
    fun reportSpamCapability(chatId: Long, result: (ChatResult<ChatReportSpamCapability>) -> Unit) { result(ChatResult(error = ChatError.UNSUPPORTED)) }
    fun reportSpam(chatId: Long, result: (ChatResult<Unit>) -> Unit) { result(ChatResult(error = ChatError.UNSUPPORTED)) }
    fun leaveChat(chatId: Long, result: (ChatResult<Unit>) -> Unit) { result(ChatResult(error = ChatError.UNSUPPORTED)) }
    fun removeChatActionBar(chatId: Long, result: (ChatResult<Unit>) -> Unit) { result(ChatResult(error = ChatError.UNSUPPORTED)) }
    fun clearReportedBasicGroupHistory(chatId: Long, result: (ChatResult<Unit>) -> Unit) { result(ChatResult(error = ChatError.UNSUPPORTED)) }
    fun performChatListAction(chatId: Long, list: ChatListRef, action: ChatListAction, result: (ChatResult<Unit>) -> Unit) { result(ChatResult(error = ChatError.UNSUPPORTED)) }
    fun addChatToFolder(chatId: Long, folderId: Int, result: (ChatResult<Unit>) -> Unit) { result(ChatResult(error = ChatError.UNSUPPORTED)) }
    fun messageLink(chatId: Long, messageId: Long, result: (ChatResult<String>) -> Unit) { result(ChatResult(error = ChatError.UNSUPPORTED)) }
    fun search(query: ChatSearchQuery, offset: String, result: (ChatResult<ChatSearchPage>) -> Unit) { result(ChatResult(error = ChatError.UNSUPPORTED)) }
    fun searchOverview(result: (ChatResult<Pair<List<ChatItem>, List<ChatItem>>>) -> Unit) { result(ChatResult(error = ChatError.UNSUPPORTED)) }
    /** Cancel only discarded overview chains; ordinary query searches remain independent. */
    fun invalidateSearchOverview() = Unit
    fun addRecentlyFoundChat(chatId: Long, result: (ChatResult<Unit>) -> Unit) { result(ChatResult(error = ChatError.UNSUPPORTED)) }
    fun clearRecentlyFoundChats(result: (ChatResult<Unit>) -> Unit) { result(ChatResult(error = ChatError.UNSUPPORTED)) }
    fun chatDetails(chat: ChatItem, result: (ChatResult<ChatDetails>) -> Unit) { result(ChatResult(error = ChatError.UNSUPPORTED)) }
    fun mediaIndex(chatId: Long, category: ChatMediaCategory, fromMessageId: Long, result: (ChatResult<ChatSearchPage>) -> Unit) { result(ChatResult(error = ChatError.UNSUPPORTED)) }
    fun mediaIndexCount(chatId: Long, category: ChatMediaCategory, result: (ChatResult<Int>) -> Unit) { result(ChatResult(error = ChatError.UNSUPPORTED)) }
    fun pinnedMessages(chatId: Long, fromMessageId: Long, result: (ChatResult<ChatSearchPage>) -> Unit) { result(ChatResult(error = ChatError.UNSUPPORTED)) }
    fun historyAround(chatId: Long, messageId: Long, result: (ChatResult<List<ChatMessage>>) -> Unit) { history(chatId, messageId, 50, result) }
    fun unreadHistory(chatId: Long, afterMessageId: Long, result: (ChatResult<List<ChatMessage>>) -> Unit) { historyAround(chatId, afterMessageId, result) }
    fun newerHistory(chatId: Long, fromMessageId: Long, result: (ChatResult<List<ChatMessage>>) -> Unit) { historyAround(chatId, fromMessageId, result) }
    fun getMe(result: (ChatResult<AccountProfile>) -> Unit) { result(ChatResult(error = ChatError.UNSUPPORTED)) }
    fun forwardSourceName(source: ForwardSourceIdentity, result: (ChatResult<String>) -> Unit) { result(ChatResult(error = ChatError.UNSUPPORTED)) }
    fun forwardSourcePresentation(source: ForwardSourceIdentity, result: (ChatResult<PeerAppearancePresentation>) -> Unit) {
        forwardSourceName(source) { response ->
            result(ChatResult(response.value?.let { PeerAppearancePresentation(source, it) }, response.error, response.code))
        }
    }
    fun sendRichText(chatId: Long, text: RichMessageText, reply: OutgoingReply?, result: (ChatResult<ChatMessage>) -> Unit) {
        if (text.entities.isEmpty() && reply?.quote == null) sendText(chatId, text.text, reply?.messageId, result)
        else result(ChatResult(error = ChatError.UNSUPPORTED))
    }
    fun sendPhotoReply(chatId: Long, path: String, caption: RichMessageText, reply: OutgoingReply?, result: (ChatResult<ChatMessage>) -> Unit) {
        if (caption.entities.isEmpty() && reply?.quote == null) sendPhoto(chatId, path, caption.text, reply?.messageId, result)
        else result(ChatResult(error = ChatError.UNSUPPORTED))
    }
    fun sendVoiceNoteReply(chatId: Long, draft: VoiceNoteDraft, reply: OutgoingReply?, result: (ChatResult<ChatMessage>) -> Unit) {
        result(ChatResult(error = ChatError.UNSUPPORTED))
    }
    fun sendStickerReply(chatId: Long, sticker: StickerItem, reply: OutgoingReply?, result: (ChatResult<ChatMessage>) -> Unit) {
        if (reply?.quote == null) sendSticker(chatId, sticker, reply?.messageId, result)
        else result(ChatResult(error = ChatError.UNSUPPORTED))
    }
    fun sendAttachmentReply(chatId: Long, attachment: LocalAttachment, caption: RichMessageText, reply: OutgoingReply?, result: (ChatResult<ChatMessage>) -> Unit) {
        if (caption.entities.isEmpty() && reply?.quote == null) sendAttachment(chatId, attachment, caption.text, reply?.messageId, result)
        else result(ChatResult(error = ChatError.UNSUPPORTED))
    }
    fun editRichMessage(chatId: Long, messageId: Long, text: RichMessageText, caption: Boolean, result: (ChatResult<ChatMessage>) -> Unit) {
        if (text.entities.isEmpty()) editMessage(chatId, messageId, text.text, caption, result)
        else result(ChatResult(error = ChatError.UNSUPPORTED))
    }
    fun readVideoPart(fileId: Int, offset: Long, count: Int, result: (ChatResult<ByteArray>) -> Unit) { result(ChatResult(error = ChatError.UNSUPPORTED)) }
    fun readVideoPart(owner: MediaDownloadOwner, file: MediaFile, offset: Long, count: Int, result: (ChatResult<ByteArray>) -> Unit) =
        readVideoPart(file.id, offset, count, result)
    fun acquireDownload(owner: MediaDownloadOwner, file: MediaFile, result: ((ChatResult<MediaFile>) -> Unit)? = null) {
        download(file.id) { result?.invoke(it) }
    }
    fun releaseDownload(owner: MediaDownloadOwner, fileId: Int) {}
    fun observeDownload(file: MediaFile) {}
    fun managesDownload(fileId: Int): Boolean = false
    fun retireDownloads() {}
    fun loadChats(limit: Int, result: (ChatResult<ChatLoadResult>) -> Unit)
    fun history(chatId: Long, fromMessageId: Long, limit: Int, result: (ChatResult<List<ChatMessage>>) -> Unit)
    fun open(chatId: Long)
    fun close(chatId: Long)
    fun sendText(chatId: Long, text: String, result: (ChatResult<ChatMessage>) -> Unit)
    fun retry(chatId: Long, messageId: Long, result: (ChatResult<List<ChatMessage>>) -> Unit)
    fun markRead(chatId: Long, messageIds: List<Long>, result: (ChatResult<Unit>) -> Unit)
    fun loadChats(list: ChatListRef, limit: Int, result: (ChatResult<ChatLoadResult>) -> Unit) {
        if (list.folderId == 0) loadChats(limit, result) else result(ChatResult(error = ChatError.UNSUPPORTED))
    }
    fun viewMessages(chatId: Long, messageIds: List<Long>, forceRead: Boolean, result: (ChatResult<Unit>) -> Unit) {
        if (forceRead) markRead(chatId, messageIds, result) else result(ChatResult(error = ChatError.UNSUPPORTED))
    }
    fun markUnread(chatId: Long, marked: Boolean, result: (ChatResult<Unit>) -> Unit) { result(ChatResult(error = ChatError.UNSUPPORTED)) }
    fun download(fileId: Int, result: (ChatResult<MediaFile>) -> Unit) { result(ChatResult(error = ChatError.UNSUPPORTED)) }
    fun stickerOutline(fileId: Int, forAnimatedEmoji: Boolean = false, result: (ChatResult<String>) -> Unit) { result(ChatResult(error = ChatError.UNSUPPORTED)) }
    fun recentStickers(result: (ChatResult<List<StickerItem>>) -> Unit) { result(ChatResult(error = ChatError.UNSUPPORTED)) }
    fun installedStickerSets(result: (ChatResult<List<StickerPack>>) -> Unit) { result(ChatResult(error = ChatError.UNSUPPORTED)) }
    fun stickerSet(setId: Long, result: (ChatResult<List<StickerItem>>) -> Unit) { result(ChatResult(error = ChatError.UNSUPPORTED)) }
    fun sendSticker(chatId: Long, sticker: StickerItem, result: (ChatResult<ChatMessage>) -> Unit) { result(ChatResult(error = ChatError.UNSUPPORTED)) }
    fun sendPhoto(chatId: Long, path: String, caption: String, result: (ChatResult<ChatMessage>) -> Unit) { result(ChatResult(error = ChatError.UNSUPPORTED)) }
    fun sendText(chatId: Long, text: String, replyToMessageId: Long?, result: (ChatResult<ChatMessage>) -> Unit) {
        if (replyToMessageId == null) sendText(chatId, text, result) else result(ChatResult(error = ChatError.UNSUPPORTED))
    }
    fun sendPhoto(chatId: Long, path: String, caption: String, replyToMessageId: Long?, result: (ChatResult<ChatMessage>) -> Unit) {
        if (replyToMessageId == null) sendPhoto(chatId, path, caption, result) else result(ChatResult(error = ChatError.UNSUPPORTED))
    }
    fun sendSticker(chatId: Long, sticker: StickerItem, replyToMessageId: Long?, result: (ChatResult<ChatMessage>) -> Unit) {
        if (replyToMessageId == null) sendSticker(chatId, sticker, result) else result(ChatResult(error = ChatError.UNSUPPORTED))
    }
    fun sendAttachment(chatId: Long, attachment: LocalAttachment, caption: String, replyToMessageId: Long?, result: (ChatResult<ChatMessage>) -> Unit) {
        result(ChatResult(error = ChatError.UNSUPPORTED))
    }
    fun messageReadDetails(chatId: Long, messageId: Long, result: (ChatResult<MessageReadDetails>) -> Unit) { result(ChatResult(error = ChatError.UNSUPPORTED)) }
    /** messageId identifies the message containing the reply, not the referenced message. */
    fun getReplyMessage(chatId: Long, messageId: Long, result: (ChatResult<ChatMessage>) -> Unit) { result(ChatResult(error = ChatError.UNSUPPORTED)) }
    fun messageActions(chatId: Long, messageId: Long, result: (ChatResult<MessageActions>) -> Unit) { result(ChatResult(error = ChatError.UNSUPPORTED)) }
    fun availableReactions(chatId: Long, messageId: Long, result: (ChatResult<MessageReactionOptions>) -> Unit) { result(ChatResult(error = ChatError.UNSUPPORTED)) }
    fun setReaction(chatId: Long, messageId: Long, emoji: String, add: Boolean, result: (ChatResult<Unit>) -> Unit) { result(ChatResult(error = ChatError.UNSUPPORTED)) }
    fun setCustomReaction(chatId: Long, messageId: Long, customEmojiId: Long, add: Boolean, result: (ChatResult<Unit>) -> Unit) { result(ChatResult(error = ChatError.UNSUPPORTED)) }
    fun reactionVisual(key: ReactionKey, result: (ChatResult<ReactionVisual>) -> Unit) { result(ChatResult(error = ChatError.UNSUPPORTED)) }
    fun reactionPacks(chatId: Long, result: (ChatResult<List<StickerPack>>) -> Unit) { result(ChatResult(error = ChatError.UNSUPPORTED)) }
    fun reactionPack(setId: Long, result: (ChatResult<List<ReactionChoice>>) -> Unit) { result(ChatResult(error = ChatError.UNSUPPORTED)) }
    fun reactionGroupPack(sourceChatId: Long, result: (ChatResult<ReactionGroupPack>) -> Unit) { result(ChatResult(error = ChatError.UNSUPPORTED)) }
    fun deleteMessage(chatId: Long, messageId: Long, revoke: Boolean, result: (ChatResult<Unit>) -> Unit) { result(ChatResult(error = ChatError.UNSUPPORTED)) }
    fun forwardMessage(fromChatId: Long, messageId: Long, toChatId: Long, result: (ChatResult<List<ChatMessage>>) -> Unit) { result(ChatResult(error = ChatError.UNSUPPORTED)) }
    fun editMessage(chatId: Long, messageId: Long, text: String, caption: Boolean, result: (ChatResult<ChatMessage>) -> Unit) { result(ChatResult(error = ChatError.UNSUPPORTED)) }
    fun getFolder(id: Int, result: (ChatResult<ChatFolder>) -> Unit) { result(ChatResult(error = ChatError.UNSUPPORTED)) }
    fun getFolder(id: Int, current: () -> Boolean, result: (ChatResult<ChatFolder>) -> Unit) {
        if (current()) getFolder(id, result) else result(ChatResult(error = ChatError.NOT_READY))
    }
    fun saveFolder(folder: ChatFolder, result: (ChatResult<ChatFolderInfo>) -> Unit) { result(ChatResult(error = ChatError.UNSUPPORTED)) }
    fun saveFolder(folder: ChatFolder, current: () -> Boolean, result: (ChatResult<ChatFolderInfo>) -> Unit) {
        if (current()) saveFolder(folder, result) else result(ChatResult(error = ChatError.NOT_READY))
    }
    fun deleteFolder(id: Int, result: (ChatResult<Unit>) -> Unit) { result(ChatResult(error = ChatError.UNSUPPORTED)) }
}

/** All requests can be tested with synthetic responses, without loading JNI or creating a client. */
internal class OfficialTdChatSession(
    sharedChatNotifications: NativeChatNotificationMutations? = null,
    sharedDownloads: TdDownloadBroker? = null,
    private val send: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit,
) : TdChatSession {
    constructor(send: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit) : this(null, null, send)
    constructor(sharedChatNotifications: NativeChatNotificationMutations?,
        send: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit) : this(sharedChatNotifications, null, send)
    private val chatNotifications = sharedChatNotifications ?: NativeChatNotificationMutations(send)
    override val folderSettings = OfficialTdFolderSettings(send)
    override val helpSupport = OfficialTdHelpSupport(send)
    override val knownMessageTextLimit: Int? get() = null
    private val extras = OfficialTdChatExtras(send, captureFolderView = folderSettings::captureEditorView, sharedDownloads = sharedDownloads)
    private val reactions = OfficialTdMessageReactions(send)
    private val conversationTools = OfficialTdConversationTools(send)
    private val listActions = OfficialTdChatListActions(chatNotifications, send)
    private val mediaIndex = OfficialTdMediaIndex(send)
    private val forwardSources = OfficialTdForwardSources(send)

    override fun messageTextLimit(result: (ChatResult<Int>) -> Unit) {
        request(TdApi.GetOption("message_text_length_max"), { response ->
            when (response) {
                is TdApi.OptionValueInteger -> if (response.value in 1..65_536)
                    ChatResult(response.value.toInt()) else unexpected()
                is TdApi.Error -> failure(response)
                else -> unexpected()
            }
        }, result)
    }

    override fun linkConfirmationDomains(result: (ChatResult<Set<String>>) -> Unit) {
        request(TdApi.GetApplicationConfig(), { response ->
            val config = response as? TdApi.JsonValueObject
            val member = config?.members.orEmpty().firstOrNull { it?.key == "whitelisted_domains" }
            val values = member?.value as? TdApi.JsonValueArray
            if (values == null || values.values == null || values.values.size > 4096 ||
                values.values.any { it !is TdApi.JsonValueString || it.value == null || it.value.length > 253 }) unexpected()
            else ChatResult(values.values.map { (it as TdApi.JsonValueString).value }.toSet())
        }, result)
    }

    override fun forwardSourceName(source: ForwardSourceIdentity, result: (ChatResult<String>) -> Unit) = forwardSources.name(source, result)
    override fun forwardSourcePresentation(source: ForwardSourceIdentity, result: (ChatResult<PeerAppearancePresentation>) -> Unit) =
        forwardSources.presentation(source, result)
    override fun reportSpamCapability(chatId: Long, result: (ChatResult<ChatReportSpamCapability>) -> Unit) = extras.reportSpamCapability(chatId, result)
    override fun reportSpam(chatId: Long, result: (ChatResult<Unit>) -> Unit) = extras.reportSpam(chatId, result)
    override fun leaveChat(chatId: Long, result: (ChatResult<Unit>) -> Unit) = extras.leaveChat(chatId, result)
    override fun removeChatActionBar(chatId: Long, result: (ChatResult<Unit>) -> Unit) = extras.removeChatActionBar(chatId, result)
    override fun clearReportedBasicGroupHistory(chatId: Long, result: (ChatResult<Unit>) -> Unit) = extras.clearReportedBasicGroupHistory(chatId, result)

    override fun performChatListAction(chatId: Long, list: ChatListRef, action: ChatListAction, result: (ChatResult<Unit>) -> Unit) = listActions.perform(chatId, list, action, result)
    override fun addChatToFolder(chatId: Long, folderId: Int, result: (ChatResult<Unit>) -> Unit) = listActions.addToFolder(chatId, folderId, result)
    override fun messageLink(chatId: Long, messageId: Long, result: (ChatResult<String>) -> Unit) = listActions.messageLink(chatId, messageId, result)

    override fun search(query: ChatSearchQuery, offset: String, result: (ChatResult<ChatSearchPage>) -> Unit) = conversationTools.search(query, offset, result)
    override fun searchOverview(result: (ChatResult<Pair<List<ChatItem>, List<ChatItem>>>) -> Unit) = conversationTools.searchOverview(result)
    override fun invalidateSearchOverview() = conversationTools.invalidateSearchOverview()
    override fun addRecentlyFoundChat(chatId: Long, result: (ChatResult<Unit>) -> Unit) = conversationTools.addRecentlyFoundChat(chatId, result)
    override fun clearRecentlyFoundChats(result: (ChatResult<Unit>) -> Unit) = conversationTools.clearRecentlyFoundChats(result)
    override fun chatDetails(chat: ChatItem, result: (ChatResult<ChatDetails>) -> Unit) = conversationTools.chatDetails(chat, result)
    override fun mediaIndex(chatId: Long, category: ChatMediaCategory, fromMessageId: Long, result: (ChatResult<ChatSearchPage>) -> Unit) = mediaIndex.page(chatId, category, fromMessageId, result)
    override fun mediaIndexCount(chatId: Long, category: ChatMediaCategory, result: (ChatResult<Int>) -> Unit) = mediaIndex.count(chatId, category, result)
    override fun pinnedMessages(chatId: Long, fromMessageId: Long, result: (ChatResult<ChatSearchPage>) -> Unit) = conversationTools.pinnedMessages(chatId, fromMessageId, result)
    override fun historyAround(chatId: Long, messageId: Long, result: (ChatResult<List<ChatMessage>>) -> Unit) = conversationTools.historyAround(chatId, messageId, result)
    override fun unreadHistory(chatId: Long, afterMessageId: Long, result: (ChatResult<List<ChatMessage>>) -> Unit) = conversationTools.newerHistory(chatId, afterMessageId, result)
    override fun newerHistory(chatId: Long, fromMessageId: Long, result: (ChatResult<List<ChatMessage>>) -> Unit) = conversationTools.newerHistory(chatId, fromMessageId, result)

    override fun getMe(result: (ChatResult<AccountProfile>) -> Unit) {
        request(TdApi.GetMe(), { response ->
            when (response) {
                is TdApi.User -> if (response.id > 0) ChatResult(mapAccountProfile(response)) else unexpected()
                is TdApi.Error -> failure(response)
                else -> unexpected()
            }
        }, result)
    }

    override fun loadChats(limit: Int, result: (ChatResult<ChatLoadResult>) -> Unit) = loadChats(ChatListRef(), limit, result)

    override fun loadChats(list: ChatListRef, limit: Int, result: (ChatResult<ChatLoadResult>) -> Unit) {
        request(TdApi.LoadChats(nativeChatList(list), limit.coerceIn(1, 100)), { response ->
            when (response) {
                is TdApi.Ok -> ChatResult(ChatLoadResult())
                is TdApi.Error -> if (response.code == 404) ChatResult(ChatLoadResult(endReached = true)) else failure(response)
                else -> unexpected()
            }
        }, result)
    }

    override fun history(chatId: Long, fromMessageId: Long, limit: Int, result: (ChatResult<List<ChatMessage>>) -> Unit) {
        request(TdApi.GetChatHistory(chatId, fromMessageId, 0, limit.coerceIn(1, 100), false), { response ->
            when (response) {
                is TdApi.Messages -> ChatResult(response.messages.orEmpty().filterNotNull().map(::mapChatMessage))
                is TdApi.Error -> failure(response)
                else -> unexpected()
            }
        }, result)
    }

    override fun open(chatId: Long) = discard(TdApi.OpenChat(chatId))

    override fun close(chatId: Long) = discard(TdApi.CloseChat(chatId))

    override fun sendText(chatId: Long, text: String, result: (ChatResult<ChatMessage>) -> Unit) = sendText(chatId, text, null, result)

    override fun sendText(chatId: Long, text: String, replyToMessageId: Long?, result: (ChatResult<ChatMessage>) -> Unit) =
        sendRichText(chatId, RichMessageText(text), replyToMessageId?.let { OutgoingReply(it) }, result)

    override fun sendRichText(chatId: Long, text: RichMessageText, reply: OutgoingReply?, result: (ChatResult<ChatMessage>) -> Unit) {
        if (text.text.isBlank()) {
            result(ChatResult(error = ChatError.EMPTY_TEXT))
            return
        }
        if (reply != null && reply.messageId <= 0) { result(unexpected()); return }
        val formatted = try { text.toNative() } catch (_: IllegalArgumentException) { result(unexpected()); return }
        val content = TdApi.InputMessageText(
            formatted,
            TdApi.LinkPreviewOptions(),
            false,
        )
        // Explicitly opt out of payments, broadcasting, scheduling and preview-only sends.
        val options = TdApi.MessageSendOptions().apply {
            allowPaidBroadcast = false
            paidMessageStarCount = 0
            onlyPreview = false
        }
        extras.prepareReply(chatId, reply, result) { nativeReply ->
        request(TdApi.SendMessage(chatId, null, nativeReply, options, null, content), { response ->
            when (response) {
                is TdApi.Message -> ChatResult(mapChatMessage(response))
                is TdApi.Error -> failure(response)
                else -> unexpected()
            }
        }, result)
        }
    }

    override fun retry(chatId: Long, messageId: Long, result: (ChatResult<List<ChatMessage>>) -> Unit) {
        request(TdApi.ResendMessages(chatId, longArrayOf(messageId), null, 0), { response ->
            when (response) {
                is TdApi.Messages -> {
                    val messages = response.messages.orEmpty().filterNotNull().map(::mapChatMessage)
                    if (messages.isEmpty()) unexpected() else ChatResult(messages)
                }
                is TdApi.Error -> failure(response)
                else -> unexpected()
            }
        }, result)
    }

    override fun markRead(chatId: Long, messageIds: List<Long>, result: (ChatResult<Unit>) -> Unit) {
        viewMessages(chatId, messageIds, true, result)
    }

    override fun viewMessages(chatId: Long, messageIds: List<Long>, forceRead: Boolean, result: (ChatResult<Unit>) -> Unit) {
        if (messageIds.isEmpty()) {
            result(ChatResult(Unit))
            return
        }
        // Automatic calls contain only visible messages in a focused conversation; explicit reads force the receipt.
        request(TdApi.ViewMessages(chatId, messageIds.distinct().toLongArray(), TdApi.MessageSourceChatHistory(), forceRead), { response ->
            when (response) {
                is TdApi.Ok -> ChatResult(Unit)
                is TdApi.Error -> failure(response)
                else -> unexpected()
            }
        }, result)
    }

    override fun markUnread(chatId: Long, marked: Boolean, result: (ChatResult<Unit>) -> Unit) = extras.markUnread(chatId, marked, result)
    override fun download(fileId: Int, result: (ChatResult<MediaFile>) -> Unit) = extras.download(fileId, result)
    override fun stickerOutline(fileId: Int, forAnimatedEmoji: Boolean, result: (ChatResult<String>) -> Unit) =
        extras.stickerOutline(fileId, forAnimatedEmoji, result)
    override fun recentStickers(result: (ChatResult<List<StickerItem>>) -> Unit) = extras.recentStickers(result)
    override fun installedStickerSets(result: (ChatResult<List<StickerPack>>) -> Unit) = extras.installedStickerSets(result)
    override fun stickerSet(setId: Long, result: (ChatResult<List<StickerItem>>) -> Unit) = extras.stickerSet(setId, result)
    override fun sendSticker(chatId: Long, sticker: StickerItem, result: (ChatResult<ChatMessage>) -> Unit) = extras.sendSticker(chatId, sticker, result)
    override fun sendPhoto(chatId: Long, path: String, caption: String, result: (ChatResult<ChatMessage>) -> Unit) = extras.sendPhoto(chatId, path, caption, result)
    override fun sendSticker(chatId: Long, sticker: StickerItem, replyToMessageId: Long?, result: (ChatResult<ChatMessage>) -> Unit) = extras.sendSticker(chatId, sticker, result, replyToMessageId)
    override fun sendPhoto(chatId: Long, path: String, caption: String, replyToMessageId: Long?, result: (ChatResult<ChatMessage>) -> Unit) = extras.sendPhoto(chatId, path, caption, result, replyToMessageId)
    override fun sendAttachment(chatId: Long, attachment: LocalAttachment, caption: String, replyToMessageId: Long?, result: (ChatResult<ChatMessage>) -> Unit) = extras.sendAttachment(chatId, attachment, caption, replyToMessageId, result)
    override fun messageReadDetails(chatId: Long, messageId: Long, result: (ChatResult<MessageReadDetails>) -> Unit) = extras.messageReadDetails(chatId, messageId, result)
    override fun getReplyMessage(chatId: Long, messageId: Long, result: (ChatResult<ChatMessage>) -> Unit) = extras.getReplyMessage(chatId, messageId, result)
    override fun messageActions(chatId: Long, messageId: Long, result: (ChatResult<MessageActions>) -> Unit) = extras.messageActions(chatId, messageId, result)
    override fun availableReactions(chatId: Long, messageId: Long, result: (ChatResult<MessageReactionOptions>) -> Unit) = reactions.available(chatId, messageId, result)
    override fun setReaction(chatId: Long, messageId: Long, emoji: String, add: Boolean, result: (ChatResult<Unit>) -> Unit) = reactions.set(chatId, messageId, emoji, add, result)
    override fun setCustomReaction(chatId: Long, messageId: Long, customEmojiId: Long, add: Boolean, result: (ChatResult<Unit>) -> Unit) = reactions.set(chatId, messageId, ReactionKey(customEmojiId = customEmojiId), add, result)
    override fun reactionVisual(key: ReactionKey, result: (ChatResult<ReactionVisual>) -> Unit) = reactions.visual(key, result)
    override fun reactionPacks(chatId: Long, result: (ChatResult<List<StickerPack>>) -> Unit) = reactions.packs(chatId, result)
    override fun reactionPack(setId: Long, result: (ChatResult<List<ReactionChoice>>) -> Unit) = reactions.pack(setId, result)
    override fun reactionGroupPack(sourceChatId: Long, result: (ChatResult<ReactionGroupPack>) -> Unit) = reactions.groupPack(sourceChatId, result)
    override fun deleteMessage(chatId: Long, messageId: Long, revoke: Boolean, result: (ChatResult<Unit>) -> Unit) = extras.deleteMessage(chatId, messageId, revoke, result)
    override fun forwardMessage(fromChatId: Long, messageId: Long, toChatId: Long, result: (ChatResult<List<ChatMessage>>) -> Unit) = extras.forwardMessage(fromChatId, messageId, toChatId, result)
    override fun editMessage(chatId: Long, messageId: Long, text: String, caption: Boolean, result: (ChatResult<ChatMessage>) -> Unit) = extras.editMessage(chatId, messageId, text, caption, result)
    override fun sendPhotoReply(chatId: Long, path: String, caption: RichMessageText, reply: OutgoingReply?, result: (ChatResult<ChatMessage>) -> Unit) = extras.sendPhoto(chatId, path, caption.text, result, reply?.messageId, reply?.quote, caption.entities)
    override fun sendVoiceNoteReply(chatId: Long, draft: VoiceNoteDraft, reply: OutgoingReply?, result: (ChatResult<ChatMessage>) -> Unit) = extras.sendVoiceNote(chatId, draft, reply, result)
    override fun sendStickerReply(chatId: Long, sticker: StickerItem, reply: OutgoingReply?, result: (ChatResult<ChatMessage>) -> Unit) = extras.sendSticker(chatId, sticker, result, reply?.messageId, reply?.quote)
    override fun sendAttachmentReply(chatId: Long, attachment: LocalAttachment, caption: RichMessageText, reply: OutgoingReply?, result: (ChatResult<ChatMessage>) -> Unit) = extras.sendAttachment(chatId, attachment, caption.text, reply?.messageId, result, reply?.quote, caption.entities)
    override fun editRichMessage(chatId: Long, messageId: Long, text: RichMessageText, caption: Boolean, result: (ChatResult<ChatMessage>) -> Unit) = extras.editMessage(chatId, messageId, text.text, caption, result, text.entities)
    override fun readVideoPart(fileId: Int, offset: Long, count: Int, result: (ChatResult<ByteArray>) -> Unit) = extras.readVideoPart(fileId, offset, count, result)
    override fun readVideoPart(owner: MediaDownloadOwner, file: MediaFile, offset: Long, count: Int, result: (ChatResult<ByteArray>) -> Unit) =
        extras.readVideoPart(owner, file, offset, count, result)
    override fun acquireDownload(owner: MediaDownloadOwner, file: MediaFile, result: ((ChatResult<MediaFile>) -> Unit)?) = extras.acquireDownload(owner, file, result)
    override fun releaseDownload(owner: MediaDownloadOwner, fileId: Int) = extras.releaseDownload(owner, fileId)
    override fun observeDownload(file: MediaFile) = extras.observeDownload(file)
    override fun managesDownload(fileId: Int) = extras.managesDownload(fileId)
    override fun retireDownloads() {
        helpSupport.retire()
        chatNotifications.retire()
        extras.retireDownloads()
    }
    override fun getFolder(id: Int, result: (ChatResult<ChatFolder>) -> Unit) = extras.getFolder(id, result)
    override fun getFolder(id: Int, current: () -> Boolean, result: (ChatResult<ChatFolder>) -> Unit) = extras.getFolder(id, current, result)
    override fun saveFolder(folder: ChatFolder, result: (ChatResult<ChatFolderInfo>) -> Unit) = extras.saveFolder(folder, result)
    override fun saveFolder(folder: ChatFolder, current: () -> Boolean, result: (ChatResult<ChatFolderInfo>) -> Unit) = extras.saveFolder(folder, current, result)
    override fun deleteFolder(id: Int, result: (ChatResult<Unit>) -> Unit) = extras.deleteFolder(id, result)

    private fun discard(request: TdApi.Function<*>) {
        request(request, { ChatResult(Unit) }) { }
    }

    private fun <T> request(
        request: TdApi.Function<*>,
        map: (TdApi.Object) -> ChatResult<T>,
        result: (ChatResult<T>) -> Unit,
    ) {
        val completed = AtomicBoolean()
        fun complete(value: ChatResult<T>) {
            if (completed.compareAndSet(false, true)) result(value)
        }
        try {
            send(request, { response -> complete(map(response)) }, { complete(unexpected()) })
        } catch (_: Exception) {
            complete(unexpected())
        }
    }

    private fun <T> failure(error: TdApi.Error) = ChatResult<T>(error = classifyChatError(error), code = error.code)
    private fun <T> unexpected() = ChatResult<T>(error = ChatError.REQUEST_FAILED, code = -1)
}

/** A native object's toString is never used, including for unknown message types and errors. */
internal fun mapChatMessage(message: TdApi.Message): ChatMessage {
    val content = mapMessageContent(message.content)
    val state = message.sendingState
    val failed = state as? TdApi.MessageSendingStateFailed
    val retryRepresentable = failed != null && failed.retryAfter.isFinite() && failed.retryAfter <= Int.MAX_VALUE
    return ChatMessage(
        id = message.id,
        chatId = message.chatId,
        senderId = when (val sender = message.senderId) {
            is TdApi.MessageSenderUser -> sender.userId
            is TdApi.MessageSenderChat -> sender.chatId
            else -> 0
        },
        senderIsChat = message.senderId is TdApi.MessageSenderChat,
        outgoing = message.isOutgoing,
        date = message.date,
        text = content.first,
        kind = content.second,
        delivery = when (state) {
            is TdApi.MessageSendingStatePending -> MessageDelivery.PENDING
            is TdApi.MessageSendingStateFailed -> MessageDelivery.FAILED
            else -> MessageDelivery.SENT
        },
        canRetry = failed != null && failed.canRetry && !failed.needAnotherSender && !failed.needAnotherReplyQuote &&
            !failed.needDropReply && failed.requiredPaidMessageStarCount == 0L && retryRepresentable,
        retryAfterSeconds = if (retryRepresentable) ceil(failed!!.retryAfter.coerceAtLeast(0.0)).toInt() else 0,
        media = mapMessageMedia(message.content),
        replyTo = mapMessageReply(message.replyTo),
        forwardInfo = mapMessageForwardInfo(message.forwardInfo),
        importInfo = mapMessageImportInfo(message.importInfo),
        inlineKeyboard = mapMessageInlineKeyboard(message.replyMarkup),
        reactions = mapMessageReactions(message.interactionInfo?.reactions),
        canBeSaved = message.canBeSaved,
        editDate = message.editDate.coerceAtLeast(0),
        entities = mapRichText(formattedContent(message.content)).entities,
        showCaptionAboveMedia = captionAboveMedia(message.content),
        linkPreviewOptions = (message.content as? TdApi.MessageText)?.linkPreviewOptions?.let(::mapLinkPreviewOptions),
        linkPreview = (message.content as? TdApi.MessageText)?.linkPreview?.let(::mapLinkPreview),
    )
}

internal fun mapMessageContent(content: TdApi.MessageContent?): Pair<String, MessageKind> = when (content) {
    is TdApi.MessageText -> content.text?.text.orEmpty() to MessageKind.TEXT
    is TdApi.MessageAnimatedEmoji -> content.emoji.orEmpty() to MessageKind.ANIMATED_EMOJI
    is TdApi.MessagePhoto -> content.caption?.text.orEmpty() to MessageKind.PHOTO
    is TdApi.MessageVideo -> content.caption?.text.orEmpty() to MessageKind.VIDEO
    is TdApi.MessageVideoNote -> "" to MessageKind.VIDEO
    is TdApi.MessageDocument -> content.caption?.text.orEmpty() to MessageKind.FILE
    is TdApi.MessageAudio -> content.caption?.text.orEmpty() to MessageKind.FILE
    is TdApi.MessageAnimation -> content.caption?.text.orEmpty() to MessageKind.ANIMATION
    is TdApi.MessageVoiceNote -> content.caption?.text.orEmpty() to MessageKind.VOICE
    is TdApi.MessageSticker -> "" to MessageKind.STICKER
    is TdApi.MessageBasicGroupChatCreate, is TdApi.MessageSupergroupChatCreate,
    is TdApi.MessageChatChangeTitle, is TdApi.MessageChatChangePhoto, is TdApi.MessageChatDeletePhoto,
    is TdApi.MessageChatAddMembers, is TdApi.MessageChatJoinByLink, is TdApi.MessageChatJoinByRequest,
    is TdApi.MessageChatDeleteMember, is TdApi.MessageChatUpgradeTo, is TdApi.MessageChatUpgradeFrom,
    is TdApi.MessagePinMessage, is TdApi.MessageScreenshotTaken, is TdApi.MessageChatSetMessageAutoDeleteTime -> "" to MessageKind.SERVICE
    else -> "" to MessageKind.OTHER
}

/** Inspect only known tokens; no native response/error text leaves this file. */
internal fun classifyChatError(error: TdApi.Error): ChatError = when {
    error.code == 429 || error.message?.matches(Regex("(?:FLOOD_(?:PREMIUM_)?WAIT|SLOWMODE_WAIT)_[0-9]+")) == true -> ChatError.RATE_LIMITED
    error.message?.matches(Regex("ALLOW_PAYMENT_REQUIRED_[0-9]+")) == true -> ChatError.UNSUPPORTED
    error.code == 403 -> ChatError.FORBIDDEN
    else -> when (error.message) {
        "MESSAGE_TOO_LONG" -> ChatError.TEXT_TOO_LONG
        "MESSAGE_EMPTY" -> ChatError.EMPTY_TEXT
        "QUOTE_TEXT_INVALID", "QUOTE_TOO_LONG", "QUOTE_NOT_FOUND" -> ChatError.INVALID_QUOTE
        "CHAT_WRITE_FORBIDDEN", "CHAT_SEND_PLAIN_FORBIDDEN", "CHAT_SEND_PHOTOS_FORBIDDEN", "CHAT_SEND_STICKERS_FORBIDDEN", "CHAT_SEND_VIDEOS_FORBIDDEN", "CHAT_SEND_DOCS_FORBIDDEN", "CHAT_SEND_MEDIA_FORBIDDEN", "USER_BANNED_IN_CHANNEL", "USER_IS_BLOCKED",
        "INPUT_USER_DEACTIVATED", "CHAT_RESTRICTED", "CHAT_ADMIN_REQUIRED", "TOPIC_CLOSED", "CHANNEL_PRIVATE",
        "MESSAGE_DELETE_FORBIDDEN", "CHAT_FORWARDS_RESTRICTED" -> ChatError.FORBIDDEN
        else -> ChatError.REQUEST_FAILED
    }
}

/** Only this known option uses Empty to represent its native default, false. */
internal fun mapDisableTopChatsOption(value: TdApi.Object?): Boolean? = when (value) {
    is TdApi.OptionValueBoolean -> value.value
    is TdApi.OptionValueEmpty -> false
    else -> null
}

/** Per-client mapper. TDLib delivers updates serially; only minimal permission metadata is retained. */
internal class TdChatUpdateMapper(private val emit: (ChatEvent) -> Unit) {
    /** The shared settings helper owns parsing and cache identity, including unknown/error states. */
    fun acceptProfileColorsCatalog(catalog: SettingsProfileColorsCatalog) {
        emit(ChatEvent.AppearanceCatalog(catalog))
    }

    private enum class Type { PRIVATE, BASIC_GROUP, SUPERGROUP, CHANNEL, UNSUPPORTED }
    private data class PermissionBits(val text: Boolean = false, val photos: Boolean = false, val stickers: Boolean = false,
        val videos: Boolean = false, val documents: Boolean = false, val voiceNotes: Boolean = false)
    private data class ChatAccess(val type: Type, val groupId: Long, val defaults: PermissionBits)
    private data class GroupAccess(val active: Boolean, val administrator: Boolean, val canPost: Boolean, val member: Boolean, val restricted: PermissionBits?)
    private val chats = mutableMapOf<Long, ChatAccess>()
    private val basicGroups = mutableMapOf<Long, GroupAccess>()
    private val supergroups = mutableMapOf<Long, GroupAccess>()
    private val basicGroupCounts = mutableMapOf<Long, Int>()
    private val supergroupCounts = mutableMapOf<Long, Int>()
    private data class MuteBits(val useDefault: Boolean, val muteFor: Int)
    private val chatMute = mutableMapOf<Long, MuteBits>()
    private val chatMuteScopes = mutableMapOf<Long, Int>()
    private val scopeMute = mutableMapOf<Int, Boolean>()

    fun accept(update: TdApi.Object) {
        when (update) {
            is TdApi.UpdateOption -> if (update.name == "disable_top_chats") {
                emit(ChatEvent.TopChatsDisabled(mapDisableTopChatsOption(update.value)))
            }
            is TdApi.UpdateNewChat -> {
                val chat = update.chat ?: return
                chats[chat.id] = access(chat)
                chatMute[chat.id] = muteBits(chat.notificationSettings)
                chatMuteScopes[chat.id] = when (val type = chat.type) {
                    is TdApi.ChatTypeSupergroup -> if (type.isChannel) 2 else 1
                    is TdApi.ChatTypeBasicGroup -> 1
                    else -> 0
                }
                val permissions = permissions(chats.getValue(chat.id))
                emit(ChatEvent.NewChat(ChatItem(chat.id, chat.title.orEmpty(), mainOrder(chat.positions),
                    chat.unreadCount.coerceAtLeast(0), chat.lastMessage?.let(::mapChatMessage), permissions.text, chat.lastReadOutboxMessageId,
                    positions(chat.positions), chat.lastReadInboxMessageId, chat.isMarkedAsUnread, chat.photo?.small?.let(::mapMediaFile),
                    permissions.photos, permissions.stickers, permissions.videos, permissions.documents, chat.hasProtectedContent,
                    (chat.type as? TdApi.ChatTypePrivate)?.userId, pinnedLists(chat.positions),
                    basicGroupId = (chat.type as? TdApi.ChatTypeBasicGroup)?.basicGroupId,
                    supergroupId = (chat.type as? TdApi.ChatTypeSupergroup)?.supergroupId,
                    isChannel = (chat.type as? TdApi.ChatTypeSupergroup)?.isChannel == true,
                    memberCount = when (val type = chat.type) {
                        is TdApi.ChatTypeBasicGroup -> basicGroupCounts[type.basicGroupId]
                        is TdApi.ChatTypeSupergroup -> supergroupCounts[type.supergroupId]
                        else -> null
                    }, isMuted = muted(chat.id), canSendVoiceNotes = permissions.voiceNotes,
                    isJoinedGroup = chats.getValue(chat.id).let { access ->
                        access.type == Type.SUPERGROUP && supergroups[access.groupId]?.member == true
                    }, actionBar = mapChatActionBar(chat.actionBar), canBeReported = chat.canBeReported,
                    canLeave = joined(chats.getValue(chat.id)), chatLists = chat.chatLists?.mapNotNull(::mapChatList)?.toSet(),
                    appearance = mapPeerAppearance(chat))))
            }
            is TdApi.UpdateChatAccentColors -> emit(ChatEvent.ChatAppearance(update.chatId, mapPeerAppearance(update)))
            is TdApi.UpdateChatPosition -> mapChatList(update.position?.list)?.let {
                emit(ChatEvent.Position(update.chatId, update.position.order, it, update.position.isPinned))
            }
            is TdApi.UpdateChatAddedToList -> mapChatList(update.chatList)?.let {
                emit(ChatEvent.ListMembership(update.chatId, it, true))
            }
            is TdApi.UpdateChatRemovedFromList -> mapChatList(update.chatList)?.let {
                emit(ChatEvent.ListMembership(update.chatId, it, false))
            }
            is TdApi.UpdateChatDraftMessage -> emit(ChatEvent.Positions(update.chatId, positions(update.positions), pinnedLists(update.positions)))
            is TdApi.UpdateChatTitle -> emit(ChatEvent.Title(update.chatId, update.title.orEmpty()))
            is TdApi.UpdateChatLastMessage -> emit(ChatEvent.LastMessage(update.chatId, update.lastMessage?.let(::mapChatMessage),
                mainOrder(update.positions), positions(update.positions), pinnedLists(update.positions)))
            is TdApi.UpdateChatReadInbox -> emit(ChatEvent.Inbox(update.chatId, update.unreadCount.coerceAtLeast(0), update.lastReadInboxMessageId))
            is TdApi.UpdateChatReadOutbox -> emit(ChatEvent.Outbox(update.chatId, update.lastReadOutboxMessageId))
            is TdApi.UpdateChatIsMarkedAsUnread -> emit(ChatEvent.MarkedUnread(update.chatId, update.isMarkedAsUnread))
            is TdApi.UpdateChatNotificationSettings -> {
                chatMute[update.chatId] = muteBits(update.notificationSettings)
                if (update.chatId in chats) emit(ChatEvent.NotificationMuted(update.chatId, muted(update.chatId)))
            }
            is TdApi.UpdateScopeNotificationSettings -> {
                val scope = when (update.scope) {
                    is TdApi.NotificationSettingsScopePrivateChats -> 0
                    is TdApi.NotificationSettingsScopeGroupChats -> 1
                    is TdApi.NotificationSettingsScopeChannelChats -> 2
                    else -> null
                }
                if (scope != null && update.notificationSettings != null) {
                    scopeMute[scope] = update.notificationSettings.muteFor > 0
                    chatMuteScopes.filterValues { it == scope }.keys.forEach { id ->
                        if (chatMute[id]?.useDefault == true) emit(ChatEvent.NotificationMuted(id, muted(id)))
                    }
                }
            }
            is TdApi.UpdateChatPhoto -> emit(ChatEvent.Photo(update.chatId, update.photo?.small?.let(::mapMediaFile)))
            is TdApi.UpdateChatHasProtectedContent -> emit(ChatEvent.ProtectedContent(update.chatId, update.hasProtectedContent))
            is TdApi.UpdateChatActionBar -> emit(ChatEvent.ActionBar(update.chatId, mapChatActionBar(update.actionBar)))
            is TdApi.UpdateChatFolders -> {
                emit(ChatEvent.Folders(update.chatFolders.orEmpty().filterNotNull().map(::mapFolderInfo), update.mainChatListPosition, update.areTagsEnabled))
            }
            is TdApi.UpdateUnreadChatCount -> mapChatList(update.chatList)?.let {
                emit(ChatEvent.UnreadChats(it, update.unreadCount.coerceAtLeast(0)))
            }
            is TdApi.UpdateChatOnlineMemberCount -> emit(ChatEvent.OnlineCount(update.chatId,
                update.onlineMemberCount.takeIf { it > 0 }))
            is TdApi.UpdateChatAction -> emitActivity(update)
            is TdApi.UpdateMessageIsPinned -> emit(ChatEvent.PinnedChanged(update.chatId))
            is TdApi.UpdateFile -> update.file?.let { emit(ChatEvent.FileUpdated(mapMediaFile(it))) }
            is TdApi.UpdateRecentStickers -> if (!update.isAttached) emit(ChatEvent.RecentStickersChanged)
            is TdApi.UpdateInstalledStickerSets -> if (update.stickerType is TdApi.StickerTypeRegular) emit(ChatEvent.InstalledStickersChanged)
            is TdApi.UpdateChatPermissions -> chats[update.chatId]?.let { old ->
                val next = old.copy(defaults = bits(update.permissions))
                chats[update.chatId] = next
                emitPermissions(update.chatId, next)
            }
            is TdApi.UpdateBasicGroup -> update.basicGroup?.let { group ->
                basicGroups[group.id] = groupAccess(group.status, group.isActive)
                updateMemberCount(group.id, basic = true, supplied = group.memberCount)
                emitGroupPermissions(group.id, basic = true)
            }
            is TdApi.UpdateSupergroup -> update.supergroup?.let { group ->
                supergroups[group.id] = groupAccess(group.status, true)
                updateMemberCount(group.id, basic = false, supplied = group.memberCount)
                emitGroupPermissions(group.id, basic = false)
            }
            is TdApi.UpdateSupergroupFullInfo -> update.supergroupFullInfo?.let {
                updateMemberCount(update.supergroupId, basic = false, supplied = it.memberCount)
            }
            is TdApi.UpdateNewMessage -> update.message?.let { emit(ChatEvent.NewMessage(mapChatMessage(it))) }
            is TdApi.UpdateMessageSendSucceeded -> update.message?.let { emit(ChatEvent.SendSucceeded(mapChatMessage(it), update.oldMessageId)) }
            is TdApi.UpdateMessageSendFailed -> update.message?.let {
                emit(ChatEvent.SendFailed(mapChatMessage(it).copy(delivery = MessageDelivery.FAILED), update.oldMessageId))
            }
            is TdApi.UpdateMessageContent -> {
                val content = mapMessageContent(update.newContent)
                val text = update.newContent as? TdApi.MessageText
                emit(ChatEvent.Content(update.chatId, update.messageId, content.first, content.second, mapMessageMedia(update.newContent),
                    mapRichText(formattedContent(update.newContent)).entities, captionAboveMedia(update.newContent),
                    text?.linkPreviewOptions?.let(::mapLinkPreviewOptions), text?.linkPreview?.let(::mapLinkPreview)))
            }
            is TdApi.UpdateMessageEdited -> {
                emit(ChatEvent.Edited(update.chatId, update.messageId, update.editDate.coerceAtLeast(0)))
                emit(ChatEvent.ReplyMarkup(update.chatId, update.messageId, mapMessageInlineKeyboard(update.replyMarkup)))
            }
            is TdApi.UpdateMessageInteractionInfo -> emit(ChatEvent.Reactions(update.chatId, update.messageId, mapMessageReactions(update.interactionInfo?.reactions)))
            is TdApi.UpdateChatAvailableReactions -> emit(ChatEvent.ReactionAvailabilityChanged(update.chatId))
            is TdApi.UpdateActiveEmojiReactions -> emit(ChatEvent.ReactionAvailabilityChanged())
            // Cache eviction is not account deletion: keep already displayed messages intact.
            is TdApi.UpdateDeleteMessages -> if (!update.fromCache) emit(ChatEvent.Deleted(update.chatId, update.messageIds?.toList().orEmpty(), update.isPermanent))
            is TdApi.UpdateUser -> update.user?.let {
                val profile = mapAccountProfile(it)
                emit(ChatEvent.SenderName(it.id, profile.displayName, profile = profile))
            }
        }
    }

    private fun muteBits(settings: TdApi.ChatNotificationSettings?) =
        MuteBits(settings?.useDefaultMuteFor ?: true, settings?.muteFor?.coerceAtLeast(0) ?: 0)

    private fun muted(chatId: Long): Boolean {
        val settings = chatMute[chatId] ?: return false
        return if (settings.useDefault) scopeMute[chatMuteScopes[chatId]] == true else settings.muteFor > 0
    }

    private fun mainOrder(positions: Array<TdApi.ChatPosition>?): Long =
        positions.orEmpty().firstOrNull { it.list is TdApi.ChatListMain }?.order ?: 0

    private fun positions(values: Array<TdApi.ChatPosition>?): Map<ChatListRef, Long> = values.orEmpty().mapNotNull {
        mapChatList(it.list)?.let { list -> list to it.order }
    }.toMap()

    private fun pinnedLists(values: Array<TdApi.ChatPosition>?): Set<ChatListRef> = values.orEmpty().mapNotNull {
        if (it.isPinned && it.order > 0) mapChatList(it.list) else null
    }.toSet()

    private fun updateMemberCount(groupId: Long, basic: Boolean, supplied: Int) {
        val count = supplied.takeIf { it > 0 }
        val counts = if (basic) basicGroupCounts else supergroupCounts
        if (count == null) counts.remove(groupId) else counts[groupId] = count
        chats.forEach { (id, chat) ->
            if (chat.groupId == groupId && (chat.type == Type.BASIC_GROUP) == basic &&
                chat.type != Type.PRIVATE && chat.type != Type.UNSUPPORTED) emit(ChatEvent.MemberCount(id, count))
        }
    }

    private fun emitActivity(update: TdApi.UpdateChatAction) {
        // The current conversation surface has no topic selector; do not label an unrelated thread as active here.
        if (update.topicId != null) return
        val sender = update.senderId
        val id = when (sender) {
            is TdApi.MessageSenderUser -> sender.userId.takeIf { it > 0 }
            is TdApi.MessageSenderChat -> sender.chatId.takeIf { it != 0L }
            else -> null
        } ?: return
        val kind = when (update.action) {
            is TdApi.ChatActionTyping -> ChatActivityKind.TYPING
            is TdApi.ChatActionUploadingPhoto -> ChatActivityKind.UPLOADING_PHOTO
            is TdApi.ChatActionUploadingVideo -> ChatActivityKind.UPLOADING_VIDEO
            is TdApi.ChatActionUploadingDocument -> ChatActivityKind.UPLOADING_DOCUMENT
            is TdApi.ChatActionChoosingSticker -> ChatActivityKind.CHOOSING_STICKER
            else -> null
        }
        val progress = when (val action = update.action) {
            is TdApi.ChatActionUploadingPhoto -> action.progress
            is TdApi.ChatActionUploadingVideo -> action.progress
            is TdApi.ChatActionUploadingDocument -> action.progress
            else -> null
        }?.takeIf { it in 0..100 }
        emit(ChatEvent.ActivityChanged(update.chatId, id, sender is TdApi.MessageSenderChat, kind, progress))
    }

    private fun bits(value: TdApi.ChatPermissions?) = PermissionBits(
        value?.canSendBasicMessages == true, value?.canSendPhotos == true, value?.canSendOtherMessages == true,
        value?.canSendVideos == true, value?.canSendDocuments == true,
        value?.canSendVoiceNotes == true,
    )

    private fun access(chat: TdApi.Chat): ChatAccess {
        val type = chat.type
        return ChatAccess(when (type) {
            is TdApi.ChatTypePrivate -> Type.PRIVATE
            is TdApi.ChatTypeBasicGroup -> Type.BASIC_GROUP
            is TdApi.ChatTypeSupergroup -> if (type.isChannel) Type.CHANNEL else Type.SUPERGROUP
            else -> Type.UNSUPPORTED
        }, when (type) {
            is TdApi.ChatTypeBasicGroup -> type.basicGroupId
            is TdApi.ChatTypeSupergroup -> type.supergroupId
            else -> 0
        }, bits(chat.permissions))
    }

    private fun groupAccess(status: TdApi.ChatMemberStatus?, active: Boolean): GroupAccess = when (status) {
        is TdApi.ChatMemberStatusCreator -> GroupAccess(active, true, true, status.isMember, null)
        is TdApi.ChatMemberStatusAdministrator -> GroupAccess(active, true, status.rights?.canPostMessages == true, true, null)
        is TdApi.ChatMemberStatusMember -> GroupAccess(active, false, false, true, null)
        is TdApi.ChatMemberStatusRestricted -> GroupAccess(active, false, false, status.isMember, bits(status.permissions))
        else -> GroupAccess(active, false, false, false, PermissionBits())
    }

    private fun permissions(chat: ChatAccess): PermissionBits {
        val all = PermissionBits(true, true, true, true, true, true)
        if (chat.type == Type.PRIVATE) return all
        if (chat.type == Type.UNSUPPORTED) return PermissionBits()
        val group = (if (chat.type == Type.BASIC_GROUP) basicGroups else supergroups)[chat.groupId] ?: return PermissionBits()
        if (!group.active || !group.member) return PermissionBits()
        if (chat.type == Type.CHANNEL) return if (group.administrator && group.canPost) all else PermissionBits()
        if (group.administrator) return all
        return PermissionBits(chat.defaults.text && group.restricted?.text != false,
            chat.defaults.photos && group.restricted?.photos != false, chat.defaults.stickers && group.restricted?.stickers != false,
            chat.defaults.videos && group.restricted?.videos != false, chat.defaults.documents && group.restricted?.documents != false,
            chat.defaults.voiceNotes && group.restricted?.voiceNotes != false)
    }

    private fun emitPermissions(id: Long, chat: ChatAccess) {
        val allowed = permissions(chat)
        emit(ChatEvent.Permissions(id, allowed.text, allowed.photos, allowed.stickers, allowed.videos, allowed.documents, allowed.voiceNotes))
    }

    private fun joined(chat: ChatAccess): Boolean {
        if (chat.type == Type.PRIVATE || chat.type == Type.UNSUPPORTED) return false
        val group = (if (chat.type == Type.BASIC_GROUP) basicGroups else supergroups)[chat.groupId]
        return group?.active == true && group.member
    }

    private fun emitGroupPermissions(groupId: Long, basic: Boolean) {
        chats.forEach { (id, chat) ->
            if (chat.groupId == groupId && (chat.type == Type.BASIC_GROUP) == basic && chat.type != Type.PRIVATE && chat.type != Type.UNSUPPORTED) {
                emit(ChatEvent.LeaveCapability(id, joined(chat)))
                emitPermissions(id, chat)
                if (chat.type == Type.SUPERGROUP) emit(ChatEvent.GroupMembership(id, supergroups[groupId]?.member == true))
            }
        }
    }
}

internal fun mapAccountProfile(user: TdApi.User) = AccountProfile(
    userId = user.id,
    displayName = listOf(user.firstName.orEmpty(), user.lastName.orEmpty()).filter(String::isNotBlank).joinToString(" "),
    username = user.usernames?.activeUsernames.orEmpty().firstOrNull { it.isNotBlank() }.orEmpty(),
    phoneNumber = user.phoneNumber.orEmpty(),
    avatar = user.profilePhoto?.small?.let(::mapMediaFile),
    isPremium = user.isPremium,
    appearance = mapPeerAppearance(user),
)
