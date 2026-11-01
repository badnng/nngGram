package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import com.badnng.moe.media.AttachmentFiles
import org.drinkless.tdlib.TdApi
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Content-bearing request handling remains separate from the safe diagnostic journal. */
internal class OfficialTdChatExtras(
    private val send: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit,
    captureFolderView: (() -> Boolean) -> (() -> Boolean) = { it },
    sharedDownloads: TdDownloadBroker? = null,
) {
    constructor(send: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit,
        captureFolderView: (() -> Boolean) -> (() -> Boolean)) : this(send, captureFolderView, null)
    private val usesSharedDownloads = sharedDownloads != null
    private val downloads = sharedDownloads ?: TdDownloadBroker(OfficialTdDownloadNative(send), DownloadBrokerScheduler::schedule)
    private val downloadOwners = java.util.concurrent.ConcurrentHashMap.newKeySet<Pair<MediaDownloadOwner, Int>>()
    private val downloadsRetired = AtomicBoolean()
    private val folderEditor = OfficialTdFolderEditor(send, captureFolderView)

    fun reportSpamCapability(chatId: Long, result: (ChatResult<ChatReportSpamCapability>) -> Unit) {
        if (chatId == 0L) { result(failure()); return }
        request<TdApi.Chat>(TdApi.GetChat(chatId), { response ->
            if (response is TdApi.Chat && response.id == chatId) ChatResult(response) else failure(response)
        }) { loaded ->
            val chat = loaded.value
            if (loaded.error != null || chat == null) { result(copyFailure(loaded)); return@request }
            fun capability(member: Boolean?, active: Boolean = true) = ChatReportSpamCapability(
                mapChatActionBar(chat.actionBar), active && member == true, chat.canBeReported,
                needsHistoryRemoval = chat.type is TdApi.ChatTypeBasicGroup, canDeleteHistory = chat.canBeDeletedOnlyForSelf,
                isMember = member)
            when (val type = chat.type) {
                is TdApi.ChatTypeBasicGroup -> request(TdApi.GetBasicGroup(type.basicGroupId), { response ->
                    if (response is TdApi.BasicGroup && response.id == type.basicGroupId)
                        ChatResult(capability(nativeGroupMember(response.status), response.isActive)) else failure(response)
                }, result)
                is TdApi.ChatTypeSupergroup -> request(TdApi.GetSupergroup(type.supergroupId), { response ->
                    if (response is TdApi.Supergroup && response.id == type.supergroupId && response.isChannel == type.isChannel)
                        ChatResult(capability(nativeGroupMember(response.status))) else failure(response)
                }, result)
                else -> result(ChatResult(capability(null)))
            }
        }
    }

    /** Only the action-bar contract authorizes this empty initial report, never a fabricated reason. */
    fun reportSpam(chatId: Long, result: (ChatResult<Unit>) -> Unit) {
        request(TdApi.ReportChat(chatId, byteArrayOf(), longArrayOf(), ""), { response ->
            when (response) {
                is TdApi.ReportChatResultOk -> ChatResult(Unit)
                is TdApi.ReportChatResultOptionRequired, is TdApi.ReportChatResultTextRequired,
                is TdApi.ReportChatResultMessagesRequired -> ChatResult(error = ChatError.UNSUPPORTED)
                else -> failure(response)
            }
        }, result)
    }

    fun leaveChat(chatId: Long, result: (ChatResult<Unit>) -> Unit) = request(TdApi.LeaveChat(chatId), ::ok, result)
    fun removeChatActionBar(chatId: Long, result: (ChatResult<Unit>) -> Unit) = request(TdApi.RemoveChatActionBar(chatId), ::ok, result)
    fun clearReportedBasicGroupHistory(chatId: Long, result: (ChatResult<Unit>) -> Unit) =
        request(TdApi.DeleteChatHistory(chatId, true, false), ::ok, result)

    /** Downloaded-prefix queries, never sparse-file length, decide which bytes may be read. */
    fun readVideoPart(fileId: Int, offset: Long, count: Int, result: (ChatResult<ByteArray>) -> Unit) {
        readVideoPart(legacyOwner(fileId, MediaDownloadPurpose.STREAM), MediaFile(fileId), offset, count, result)
    }

    fun readVideoPart(owner: MediaDownloadOwner, file: MediaFile, offset: Long, count: Int,
        result: (ChatResult<ByteArray>) -> Unit) {
        if (downloadsRetired.get()) { result(ChatResult(error = ChatError.NOT_READY)); return }
        downloadOwners += owner to file.id
        if (downloadsRetired.get()) { releaseDownload(owner, file.id); result(ChatResult(error = ChatError.NOT_READY)); return }
        downloads.readIfActive(owner, file, offset, count, { !downloadsRetired.get() }) { value ->
            if (value.error != null && value.error != ChatError.NOT_READY || value.value?.isEmpty() == true) releaseDownload(owner, file.id)
            result(if (downloadsRetired.get()) ChatResult(error = ChatError.NOT_READY) else value)
        }
    }
    fun acquireDownload(owner: MediaDownloadOwner, file: MediaFile, result: ((ChatResult<MediaFile>) -> Unit)? = null) {
        if (downloadsRetired.get()) { result?.invoke(ChatResult(error = ChatError.NOT_READY)); return }
        downloadOwners += owner to file.id
        if (downloadsRetired.get()) { releaseDownload(owner, file.id); result?.invoke(ChatResult(error = ChatError.NOT_READY)); return }
        downloads.fullIfActive(owner, file, { !downloadsRetired.get() }) { value ->
            downloadOwners.remove(owner to file.id)
            result?.invoke(if (downloadsRetired.get()) ChatResult(error = ChatError.NOT_READY) else value)
        }
    }
    fun releaseDownload(owner: MediaDownloadOwner, fileId: Int) {
        downloadOwners.remove(owner to fileId)
        downloads.release(owner, fileId)
    }
    fun observeDownload(file: MediaFile) { if (!usesSharedDownloads) downloads.observe(file) }
    fun managesDownload(fileId: Int) = downloads.manages(fileId)
    fun retireDownloads() {
        if (!downloadsRetired.compareAndSet(false, true)) return
        downloadOwners.toList().forEach { (owner, id) -> releaseDownload(owner, id) }
        if (!usesSharedDownloads) downloads.close()
    }
    private fun legacyOwner(fileId: Int, purpose: MediaDownloadPurpose) = MediaDownloadOwner(ChatToken(0, 0, null), fileId.toLong(), purpose)

    fun prepareReply(chatId: Long, reply: OutgoingReply?, result: (ChatResult<ChatMessage>) -> Unit,
        ready: (TdApi.InputMessageReplyTo?) -> Unit) {
        if (reply == null) { ready(null); return }
        if (reply.messageId <= 0) { result(failure()); return }
        val quote = reply.quote
        if (quote == null) { ready(nativeReplyTo(reply.messageId)); return }
        messageContext(chatId, reply.messageId) { response ->
            val context = response.value
            if (response.error != null || context == null) { result(copyFailure(response)); return@messageContext }
            if (!context.properties.canBeReplied) { result(ChatResult(error = ChatError.FORBIDDEN)); return@messageContext }
            val original = formattedContent(context.message.content)
            val native = original?.let { nativeQuote(it, quote) }
            if (native == null) { result(ChatResult(error = ChatError.INVALID_QUOTE)); return@messageContext }
            request<Long>(TdApi.GetOption("message_reply_quote_length_max"), { option ->
                if (option is TdApi.OptionValueInteger && option.value > 0) ChatResult(option.value) else failure(option)
            }) { limit ->
                if (limit.error != null || limit.value == null) result(copyFailure(limit))
                else if (quote.content.text.codePointCount(0, quote.content.text.length) > limit.value) result(ChatResult(error = ChatError.INVALID_QUOTE))
                else ready(TdApi.InputMessageReplyToMessage(reply.messageId, native, 0, ""))
            }
        }
    }
    fun markUnread(chatId: Long, marked: Boolean, result: (ChatResult<Unit>) -> Unit) =
        request(TdApi.ToggleChatIsMarkedAsUnread(chatId, marked), ::ok, result)

    fun download(fileId: Int, result: (ChatResult<MediaFile>) -> Unit) {
        if (fileId <= 0) { result(failure()); return }
        request(TdApi.GetFile(fileId), { response ->
            when (response) {
                is TdApi.File -> ChatResult(mapMediaFile(response))
                else -> failure(response)
            }
        }) { loaded: ChatResult<MediaFile> ->
            val file = loaded.value
            if (file == null || loaded.error != null) result(loaded)
            else acquireDownload(legacyOwner(fileId, MediaDownloadPurpose.USER), file, result)
        }
    }

    /** TDLib decodes its already-known minithumbnail; this method performs no download. */
    fun stickerOutline(fileId: Int, forAnimatedEmoji: Boolean = false, result: (ChatResult<String>) -> Unit) {
        if (fileId <= 0) { result(failure()); return }
        request(TdApi.GetStickerOutlineSvgPath(fileId, forAnimatedEmoji, false), { response ->
            if (response is TdApi.Text && response.text.orEmpty().length <= 65_536)
                ChatResult(response.text.orEmpty()) else failure(response)
        }, result)
    }

    fun recentStickers(result: (ChatResult<List<StickerItem>>) -> Unit) =
        request(TdApi.GetRecentStickers(false), { response ->
            when (response) {
                is TdApi.Stickers -> ChatResult(response.stickers.orEmpty().mapNotNull(::mapSticker))
                else -> failure(response)
            }
        }, result)

    fun installedStickerSets(result: (ChatResult<List<StickerPack>>) -> Unit) =
        request(TdApi.GetInstalledStickerSets(TdApi.StickerTypeRegular()), { response ->
            when (response) {
                is TdApi.StickerSets -> ChatResult(response.sets.orEmpty().filterNotNull().map {
                    StickerPack(it.id, it.title.orEmpty(), it.size, it.covers.orEmpty().firstNotNullOfOrNull(::mapSticker))
                })
                else -> failure(response)
            }
        }, result)

    fun stickerSet(setId: Long, result: (ChatResult<List<StickerItem>>) -> Unit) =
        request(TdApi.GetStickerSet(setId), { response ->
            when (response) {
                is TdApi.StickerSet -> if (response.id == setId && response.stickerType is TdApi.StickerTypeRegular)
                    ChatResult(response.stickers.orEmpty().mapNotNull(::mapSticker)) else failure()
                else -> failure(response)
            }
        }, result)

    fun sendSticker(chatId: Long, sticker: StickerItem, result: (ChatResult<ChatMessage>) -> Unit, replyToMessageId: Long? = null, quote: MessageQuote? = null) {
        if (sticker.media.file.id <= 0) { result(failure()); return }
        val input = TdApi.InputSticker(TdApi.InputFileId(sticker.media.file.id), null, sticker.media.width, sticker.media.height)
        sendContent(chatId, TdApi.InputMessageSticker(input, sticker.emoji), replyToMessageId, result, quote)
    }

    fun sendPhoto(chatId: Long, path: String, caption: String, result: (ChatResult<ChatMessage>) -> Unit, replyToMessageId: Long? = null, quote: MessageQuote? = null, entities: List<MessageEntity> = emptyList()) {
        if (path.isBlank() || caption.codePointCount(0, caption.length) > 1024) { result(failure()); return }
        val input = TdApi.InputPhoto(TdApi.InputFileLocal(path), null, null, intArrayOf(), 0, 0)
        val formatted = try { RichMessageText(caption, entities).toNative() } catch (_: IllegalArgumentException) { result(failure()); return }
        sendContent(chatId, TdApi.InputMessagePhoto(input, formatted, false, null, false), replyToMessageId, result, quote)
    }

    fun sendVoiceNote(chatId: Long, draft: VoiceNoteDraft, reply: OutgoingReply?, result: (ChatResult<ChatMessage>) -> Unit) {
        if (draft.path.isBlank() || '\u0000' in draft.path || draft.size !in 1..8_000_000 ||
            draft.durationSeconds !in 1..300 || draft.waveform.size > 63) { result(failure()); return }
        val input = TdApi.InputVoiceNote(TdApi.InputFileLocal(draft.path), draft.durationSeconds, draft.waveform.copyOf())
        sendContent(chatId, TdApi.InputMessageVoiceNote(input, null, null), reply?.messageId, result, reply?.quote)
    }

    fun sendAttachment(chatId: Long, attachment: LocalAttachment, caption: String, replyToMessageId: Long?, result: (ChatResult<ChatMessage>) -> Unit, quote: MessageQuote? = null, entities: List<MessageEntity> = emptyList()) {
        val thumbnail = attachment.thumbnail
        if (attachment.path.isBlank() || '\u0000' in attachment.path || attachment.size !in 1..AttachmentFiles.MAX_BYTES || caption.codePointCount(0, caption.length) > 1024 ||
            (attachment.kind == AttachmentKind.VIDEO && (attachment.width !in 1..16_384 || attachment.height !in 1..16_384 || attachment.durationSeconds <= 0)) ||
            (thumbnail != null && (attachment.kind != AttachmentKind.VIDEO || thumbnail.path.isBlank() || '\u0000' in thumbnail.path ||
                thumbnail.width !in 1..320 || thumbnail.height !in 1..320 || thumbnail.size !in 1 until 200_000L))) {
            result(failure()); return
        }
        val file = TdApi.InputFileLocal(attachment.path)
        val formattedCaption = try { RichMessageText(caption, entities).toNative() } catch (_: IllegalArgumentException) { result(failure()); return }
        val content = when (attachment.kind) {
            AttachmentKind.VIDEO -> TdApi.InputMessageVideo(
                TdApi.InputVideo(file, thumbnail?.let { TdApi.InputThumbnail(TdApi.InputFileLocal(it.path), it.width, it.height) },
                    null, 0, intArrayOf(), attachment.durationSeconds, attachment.width, attachment.height, attachment.supportsStreaming),
                formattedCaption, false, null, false,
            )
            // Preserve a deliberate "send as file" choice; TDLib must not reclassify a video or image.
            AttachmentKind.FILE -> TdApi.InputMessageDocument(TdApi.InputDocument(file, null, true), formattedCaption)
        }
        sendContent(chatId, content, replyToMessageId, result, quote) { AttachmentFiles.matchesSelection(attachment) }
    }

    private fun sendContent(chatId: Long, content: TdApi.InputMessageContent, replyToMessageId: Long?, result: (ChatResult<ChatMessage>) -> Unit,
        quote: MessageQuote? = null, validateInput: () -> Boolean = { true }) {
        if (replyToMessageId != null && replyToMessageId <= 0) { result(failure()); return }
        val options = TdApi.MessageSendOptions().apply {
            allowPaidBroadcast = false
            paidMessageStarCount = 0
            onlyPreview = false
        }
        prepareReply(chatId, replyToMessageId?.let { OutgoingReply(it, quote) }, result) { reply ->
        if (!validateInput()) { result(failure()); return@prepareReply }
        request(TdApi.SendMessage(chatId, null, reply, options, null, content), { response ->
            when (response) {
                is TdApi.Message -> ChatResult(mapChatMessage(response))
                else -> failure(response)
            }
        }, result)
        }
    }

    fun getReplyMessage(chatId: Long, messageId: Long, result: (ChatResult<ChatMessage>) -> Unit) {
        if (messageId <= 0) { result(failure()); return }
        request(TdApi.GetRepliedMessage(chatId, messageId), { response ->
            if (response is TdApi.Message) ChatResult(mapChatMessage(response)) else failure(response)
        }, result)
    }

    fun messageActions(chatId: Long, messageId: Long, result: (ChatResult<MessageActions>) -> Unit) {
        messageContext(chatId, messageId) { response ->
            val context = response.value
            if (response.error != null || context == null) result(copyFailure(response))
            else result(ChatResult(actions(context)))
        }
    }

    fun deleteMessage(chatId: Long, messageId: Long, revoke: Boolean, result: (ChatResult<Unit>) -> Unit) {
        messageContext(chatId, messageId) { response ->
            val context = response.value
            if (response.error != null || context == null) { result(copyFailure(response)); return@messageContext }
            val allowed = actions(context)
            // TDLib always revokes in supergroups/channels. Never silently widen a self-only choice.
            if (!(if (revoke) allowed.canDeleteForAll else allowed.canDeleteForSelf)) {
                result(ChatResult(error = ChatError.FORBIDDEN)); return@messageContext
            }
            request(TdApi.DeleteMessages(chatId, longArrayOf(messageId), revoke), ::ok, result)
        }
    }

    fun forwardMessage(fromChatId: Long, messageId: Long, toChatId: Long, result: (ChatResult<List<ChatMessage>>) -> Unit) {
        if (toChatId == 0L) { result(failure()); return }
        messageContext(fromChatId, messageId) { response ->
            val context = response.value
            if (response.error != null || context == null) { result(copyFailure(response)); return@messageContext }
            if (!actions(context).canForward) { result(ChatResult(error = ChatError.FORBIDDEN)); return@messageContext }
            val options = TdApi.MessageSendOptions().apply {
                allowPaidBroadcast = false
                paidMessageStarCount = 0
                onlyPreview = false
            }
            request(TdApi.ForwardMessages(toChatId, null, fromChatId, longArrayOf(messageId), options, false, false), { forwarded ->
                if (forwarded is TdApi.Messages) {
                    val messages = forwarded.messages.orEmpty()
                    if (messages.size != 1 || messages[0] == null || messages[0].chatId != toChatId ||
                        messages[0].id <= 0 || messages[0].content == null) failure()
                    else ChatResult(messages.map(::mapChatMessage))
                } else failure(forwarded)
            }, result)
        }
    }

    /** Unchanged edits retain the entire native text, including entities not displayed by this client. */
    fun editMessage(chatId: Long, messageId: Long, text: String, caption: Boolean, result: (ChatResult<ChatMessage>) -> Unit, entities: List<MessageEntity>? = null) {
        val length = text.codePointCount(0, text.length)
        if (!caption && text.isBlank()) { result(ChatResult(error = ChatError.EMPTY_TEXT)); return }
        if (length > (if (caption) 1024 else 4096)) { result(ChatResult(error = ChatError.TEXT_TOO_LONG)); return }
        messageContext(chatId, messageId) { response ->
            val context = response.value
            if (response.error != null || context == null) { result(copyFailure(response)); return@messageContext }
            val allowed = actions(context)
            if (!(if (caption) allowed.canEditCaption else allowed.canEditText)) {
                result(ChatResult(error = ChatError.FORBIDDEN)); return@messageContext
            }
            val original = editableText(context.message.content, caption)
            if (original?.text.orEmpty() == text && (entities == null ||
                mapRichText(original).normalized().entities == RichMessageText(text, entities).normalized().entities)) {
                result(ChatResult(mapChatMessage(context.message)))
                return@messageContext
            }
            val formatted = try { RichMessageText(text, entities.orEmpty()).toNative() }
                catch (_: IllegalArgumentException) { result(failure()); return@messageContext }
            val command = if (caption) TdApi.EditMessageCaption(chatId, messageId, null, formatted,
                captionAboveMedia(context.message.content))
            else TdApi.EditMessageText(chatId, messageId, null, TdApi.InputMessageText(formatted,
                (context.message.content as? TdApi.MessageText)?.linkPreviewOptions?.copyPreviewOptions()
                    ?: TdApi.LinkPreviewOptions(), false))
            request(command, { edited ->
                if (edited is TdApi.Message && edited.chatId == chatId && edited.id == messageId &&
                    (if (caption) supportsCaption(edited.content) else isTextContent(edited.content))) ChatResult(mapChatMessage(edited))
                else failure(edited)
            }, result)
        }
    }

    private fun messageContext(chatId: Long, messageId: Long, result: (ChatResult<MessageContext>) -> Unit) {
        if (chatId == 0L || messageId <= 0) { result(failure()); return }
        request<TdApi.MessageProperties>(TdApi.GetMessageProperties(chatId, messageId), { response ->
            if (response is TdApi.MessageProperties) ChatResult(response) else failure(response)
        }) { properties ->
            val allowed = properties.value
            if (properties.error != null || allowed == null) { result(copyFailure(properties)); return@request }
            request(TdApi.GetMessage(chatId, messageId), { response ->
                if (response is TdApi.Message && response.chatId == chatId && response.id == messageId && response.content != null)
                    ChatResult(MessageContext(response, allowed)) else failure(response)
            }, result)
        }
    }

    private fun actions(context: MessageContext): MessageActions {
        val properties = context.properties
        val content = context.message.content
        val sent = context.message.sendingState == null
        return MessageActions(
            canEditText = properties.canBeEdited && sent && isTextContent(content),
            canEditCaption = properties.canBeEdited && sent && supportsCaption(content),
            canDeleteForSelf = properties.canBeDeletedOnlyForSelf,
            canDeleteForAll = properties.canBeDeletedForAllUsers,
            canForward = properties.canBeForwarded && sent,
        )
    }

    private fun isTextContent(content: TdApi.MessageContent?): Boolean =
        content is TdApi.MessageText || content is TdApi.MessageAnimatedEmoji

    private fun supportsCaption(content: TdApi.MessageContent?): Boolean = when (content) {
        is TdApi.MessagePhoto, is TdApi.MessageVideo, is TdApi.MessageDocument,
        is TdApi.MessageAudio, is TdApi.MessageAnimation, is TdApi.MessageVoiceNote -> true
        else -> false
    }

    private fun editableText(content: TdApi.MessageContent?, caption: Boolean): TdApi.FormattedText? =
        if (!caption) when (content) {
            is TdApi.MessageText -> content.text
            is TdApi.MessageAnimatedEmoji -> animatedEmojiText(content)
            else -> null
        } else when (content) {
            is TdApi.MessagePhoto -> content.caption
            is TdApi.MessageVideo -> content.caption
            is TdApi.MessageDocument -> content.caption
            is TdApi.MessageAudio -> content.caption
            is TdApi.MessageAnimation -> content.caption
            is TdApi.MessageVoiceNote -> content.caption
            else -> null
        }

    private fun <T> copyFailure(result: ChatResult<*>) = ChatResult<T>(error = result.error ?: ChatError.REQUEST_FAILED, code = result.code)

    private class MessageContext(val message: TdApi.Message, val properties: TdApi.MessageProperties) {
        override fun toString() = "MessageContext(<redacted>)"
    }

    fun messageReadDetails(chatId: Long, messageId: Long, result: (ChatResult<MessageReadDetails>) -> Unit) {
        if (messageId <= 0) { result(ChatResult(MessageReadDetails())); return }
        request<TdApi.MessageProperties>(TdApi.GetMessageProperties(chatId, messageId), { response ->
            if (response is TdApi.MessageProperties) ChatResult(response) else failure(response)
        }) { properties ->
            val value = properties.value
            if (properties.error != null || value == null) {
                result(ChatResult(error = properties.error ?: ChatError.REQUEST_FAILED, code = properties.code))
            } else when {
                value.canGetViewers -> request<TdApi.MessageViewers>(TdApi.GetMessageViewers(chatId, messageId), { response ->
                    if (response is TdApi.MessageViewers) ChatResult(response) else failure(response)
                }) { viewers ->
                    if (viewers.error != null || viewers.value == null)
                        result(ChatResult(error = viewers.error ?: ChatError.REQUEST_FAILED, code = viewers.code))
                    else resolveReaders(viewers.value.viewers.orEmpty().filterNotNull().map { it.userId to it.viewDate }, result)
                }
                value.canGetReadDate -> privateReadDetails(chatId, messageId, result)
                else -> result(ChatResult(MessageReadDetails()))
            }
        }
    }

    private fun privateReadDetails(chatId: Long, messageId: Long, result: (ChatResult<MessageReadDetails>) -> Unit) {
        request<TdApi.MessageReadDate>(TdApi.GetMessageReadDate(chatId, messageId), { response ->
            if (response is TdApi.MessageReadDate) ChatResult(response) else failure(response)
        }) { date ->
            val value = date.value
            if (date.error != null || value == null) {
                result(ChatResult(error = date.error ?: ChatError.REQUEST_FAILED, code = date.code))
            } else if (value is TdApi.MessageReadDateRead) {
                request<TdApi.Chat>(TdApi.GetChat(chatId), { response ->
                    if (response is TdApi.Chat && response.id == chatId) ChatResult(response) else failure(response)
                }) { chat ->
                    if (chat.error != null || chat.value == null) {
                        result(ChatResult(error = chat.error ?: ChatError.REQUEST_FAILED, code = chat.code))
                    } else {
                        val type = chat.value.type as? TdApi.ChatTypePrivate
                        if (type == null || type.userId <= 0) result(ChatResult(MessageReadDetails()))
                        else resolveReaders(listOf(type.userId to value.readDate), result)
                    }
                }
            } else result(ChatResult(MessageReadDetails(availability = when (value) {
                is TdApi.MessageReadDateUnread -> MessageReadAvailability.UNREAD
                is TdApi.MessageReadDateTooOld -> MessageReadAvailability.TOO_OLD
                is TdApi.MessageReadDateUserPrivacyRestricted -> MessageReadAvailability.USER_PRIVACY_RESTRICTED
                is TdApi.MessageReadDateMyPrivacyRestricted -> MessageReadAvailability.MY_PRIVACY_RESTRICTED
                else -> MessageReadAvailability.UNAVAILABLE
            })))
        }
    }

    private fun resolveReaders(viewers: List<Pair<Long, Int>>, result: (ChatResult<MessageReadDetails>) -> Unit) {
        val distinct = viewers.distinctBy { it.first }
        if (distinct.isEmpty()) { result(ChatResult(MessageReadDetails(availability = MessageReadAvailability.AVAILABLE))); return }
        if (distinct.any { it.first <= 0 }) { result(failure()); return }
        val readers = arrayOfNulls<MessageReader>(distinct.size)
        val remaining = AtomicInteger(distinct.size)
        val finished = AtomicBoolean()
        fun complete(value: ChatResult<MessageReadDetails>) { if (finished.compareAndSet(false, true)) result(value) }
        distinct.forEachIndexed { index, (id, date) ->
            request<TdApi.User>(TdApi.GetUser(id), { response ->
                if (response is TdApi.User && response.id == id) ChatResult(response) else failure(response)
            }) { user ->
                val value = user.value
                if (user.error != null || value == null) {
                    complete(ChatResult(error = user.error ?: ChatError.REQUEST_FAILED, code = user.code))
                } else {
                    readers[index] = MessageReader(id,
                        listOf(value.firstName.orEmpty(), value.lastName.orEmpty()).filter(String::isNotBlank).joinToString(" "),
                        date.coerceAtLeast(0), value.profilePhoto?.small?.let(::mapMediaFile))
                    if (remaining.decrementAndGet() == 0)
                        complete(ChatResult(MessageReadDetails(readers.filterNotNull(), MessageReadAvailability.AVAILABLE)))
                }
            }
        }
    }

    fun getFolder(id: Int, result: (ChatResult<ChatFolder>) -> Unit) = folderEditor.get(id, { true }, result)
    fun getFolder(id: Int, current: () -> Boolean, result: (ChatResult<ChatFolder>) -> Unit) = folderEditor.get(id, current, result)
    fun saveFolder(folder: ChatFolder, result: (ChatResult<ChatFolderInfo>) -> Unit) = folderEditor.save(folder, { true }, result)
    fun saveFolder(folder: ChatFolder, current: () -> Boolean, result: (ChatResult<ChatFolderInfo>) -> Unit) = folderEditor.save(folder, current, result)

    fun deleteFolder(id: Int, result: (ChatResult<Unit>) -> Unit) {
        if (id <= 0) { result(failure()); return }
        // Removing a folder never leaves any group or channel, including shared folders.
        request(TdApi.DeleteChatFolder(id, longArrayOf()), ::ok, result)
    }

    private fun ok(response: TdApi.Object): ChatResult<Unit> = if (response is TdApi.Ok) ChatResult(Unit) else failure(response)

    private fun <T> failure(response: TdApi.Object? = null): ChatResult<T> = if (response is TdApi.Error)
        ChatResult(error = classifyChatError(response), code = response.code) else ChatResult(error = ChatError.REQUEST_FAILED, code = -1)

    private fun <T> request(command: TdApi.Function<*>, map: (TdApi.Object) -> ChatResult<T>, result: (ChatResult<T>) -> Unit) {
        val finished = AtomicBoolean()
        fun complete(value: ChatResult<T>) { if (finished.compareAndSet(false, true)) result(value) }
        try {
            send(command, { response ->
                val value = try { map(response) } catch (_: Exception) { failure() }
                complete(value)
            }, { complete(failure()) })
        } catch (_: Exception) { complete(failure()) }
    }
}

internal fun nativeChatList(list: ChatListRef): TdApi.ChatList = when (list.folderId) {
    0 -> TdApi.ChatListMain()
    -1 -> TdApi.ChatListArchive()
    else -> TdApi.ChatListFolder(list.folderId)
}

internal fun nativeReplyTo(messageId: Long?): TdApi.InputMessageReplyTo? =
    messageId?.let { TdApi.InputMessageReplyToMessage(it, null, 0, "") }

internal fun mapMessageReply(reply: TdApi.MessageReplyTo?): MessageReply? {
    val message = reply as? TdApi.MessageReplyToMessage ?: return null
    val origin = message.origin
    return MessageReply(message.chatId, message.messageId,
        message.quote?.text?.text ?: mapMessageContent(message.content).first,
        when (origin) {
            is TdApi.MessageOriginUser -> origin.senderUserId
            is TdApi.MessageOriginChat -> origin.senderChatId
            is TdApi.MessageOriginChannel -> origin.chatId
            else -> 0
        }, origin is TdApi.MessageOriginChat || origin is TdApi.MessageOriginChannel,
        entities = mapRichText(message.quote?.text ?: formattedContent(message.content)).entities,
        isQuote = message.quote != null, quotePosition = message.quote?.position ?: 0,
        contentAuthor = when (origin) {
            is TdApi.MessageOriginUser -> origin.senderUserId.takeIf { it > 0 }?.let { ForwardSourceIdentity.User(it) }
            is TdApi.MessageOriginChat -> origin.senderChatId.takeIf { it != 0L }?.let { ForwardSourceIdentity.Chat(it) }
            is TdApi.MessageOriginChannel -> origin.chatId.takeIf { it != 0L }?.let { ForwardSourceIdentity.Chat(it) }
            else -> null
        }, contentAuthorKnown = origin != null)
}

internal fun mapChatList(list: TdApi.ChatList?): ChatListRef? = when (list) {
    is TdApi.ChatListMain -> ChatListRef()
    is TdApi.ChatListArchive -> ChatListRef(-1)
    is TdApi.ChatListFolder -> ChatListRef(list.chatFolderId)
    else -> null
}

internal fun mapChatActionBar(bar: TdApi.ChatActionBar?): ChatActionBar? = when (bar) {
    is TdApi.ChatActionBarReportSpam -> ChatActionBar(ChatActionBarKind.REPORT_SPAM, bar.canUnarchive)
    is TdApi.ChatActionBarReportAddBlock -> ChatActionBar(ChatActionBarKind.REPORT_ADD_BLOCK, bar.canUnarchive)
    is TdApi.ChatActionBarInviteMembers -> ChatActionBar(ChatActionBarKind.INVITE_MEMBERS)
    is TdApi.ChatActionBarAddContact -> ChatActionBar(ChatActionBarKind.ADD_CONTACT)
    is TdApi.ChatActionBarSharePhoneNumber -> ChatActionBar(ChatActionBarKind.SHARE_PHONE_NUMBER)
    is TdApi.ChatActionBarJoinRequest -> ChatActionBar(ChatActionBarKind.JOIN_REQUEST)
    else -> null
}

private fun nativeGroupMember(status: TdApi.ChatMemberStatus?): Boolean? = when (status) {
    is TdApi.ChatMemberStatusCreator -> status.isMember
    is TdApi.ChatMemberStatusAdministrator, is TdApi.ChatMemberStatusMember -> true
    is TdApi.ChatMemberStatusRestricted -> status.isMember
    is TdApi.ChatMemberStatusLeft, is TdApi.ChatMemberStatusBanned -> false
    else -> null
}

internal fun mapMediaFile(file: TdApi.File): MediaFile = MediaFile(
    id = file.id,
    size = file.size.coerceAtLeast(0),
    expectedSize = file.expectedSize.coerceAtLeast(0),
    localPath = file.local?.path?.takeIf { file.local.isDownloadingCompleted && it.isNotBlank() },
    downloaded = file.local?.isDownloadingCompleted == true,
    downloading = file.local?.isDownloadingActive == true,
    downloadedSize = file.local?.downloadedSize?.coerceAtLeast(0) ?: 0,
    canDownload = file.local?.canBeDownloaded == true,
    uploading = file.remote?.isUploadingActive == true,
    uploadedSize = file.remote?.uploadedSize?.coerceAtLeast(0) ?: 0,
    uniqueId = file.remote?.uniqueId.orEmpty(),
)

internal fun mapSticker(sticker: TdApi.Sticker?): StickerItem? {
    val file = sticker?.sticker ?: return null
    val format = when (sticker.format) {
        is TdApi.StickerFormatWebp -> MediaFormat.WEBP
        is TdApi.StickerFormatTgs -> MediaFormat.TGS
        is TdApi.StickerFormatWebm -> MediaFormat.WEBM
        else -> return null
    }
    return StickerItem(sticker.id, sticker.setId, sticker.emoji.orEmpty(), MessageMedia(
        file = mapMediaFile(file), thumbnail = sticker.thumbnail?.file?.let(::mapMediaFile),
        width = sticker.width, height = sticker.height, format = format,
        emoji = sticker.emoji.orEmpty(), setId = sticker.setId, thumbnailFormat = mapThumbnailFormat(sticker.thumbnail?.format),
    ))
}

internal fun mapThumbnailFormat(format: TdApi.ThumbnailFormat?): MediaFormat? = when (format) {
    is TdApi.ThumbnailFormatJpeg, is TdApi.ThumbnailFormatPng, is TdApi.ThumbnailFormatGif -> MediaFormat.PHOTO
    is TdApi.ThumbnailFormatWebp -> MediaFormat.WEBP
    is TdApi.ThumbnailFormatTgs -> MediaFormat.TGS
    is TdApi.ThumbnailFormatWebm -> MediaFormat.WEBM
    else -> null
}

internal fun mapMiniThumbnail(thumbnail: TdApi.Minithumbnail?): MediaMiniThumbnail? {
    if (thumbnail == null || thumbnail.width !in 1..MediaMiniThumbnail.MAX_DIMENSION ||
        thumbnail.height !in 1..MediaMiniThumbnail.MAX_DIMENSION) return null
    val encoded = thumbnail.data ?: return null
    if (encoded.size !in 1..MediaMiniThumbnail.MAX_ENCODED_BYTES) return null
    return MediaMiniThumbnail(thumbnail.width, thumbnail.height, encoded)
}

internal fun mapMessageMedia(content: TdApi.MessageContent?): MessageMedia? = when (content) {
    is TdApi.MessagePhoto -> if (content.isSecret) null else {
        val sizes = content.photo?.sizes.orEmpty().filterNotNull().filter { it.photo != null }
        val largest = sizes.maxByOrNull { it.width.toLong() * it.height }
        val preview = sizes.filter { it !== largest }.maxByOrNull { it.width.toLong() * it.height }
        largest?.let { MessageMedia(mapMediaFile(it.photo), preview?.photo?.let(::mapMediaFile), it.width, it.height,
            thumbnailFormat = MediaFormat.PHOTO, hasSpoiler = content.hasSpoiler,
            minithumbnail = mapMiniThumbnail(content.photo?.minithumbnail)) }
    }
    is TdApi.MessageSticker -> mapSticker(content.sticker)?.media
    is TdApi.MessageAnimatedEmoji -> content.animatedEmoji?.let { emoji ->
        val sticker = emoji.sticker?.takeIf { (it.sticker?.id ?: 0) > 0 }
        mapSticker(sticker)?.media?.copy(emoji = content.emoji.orEmpty(), animatedEmoji = AnimatedEmojiMedia(
            emoji.stickerWidth, emoji.stickerHeight, emoji.fitzpatrickType, emoji.isRegularEmoji,
        ))
    }
    is TdApi.MessageVideo -> if (content.isSecret) null else content.video?.let { video ->
        video.video?.let { file -> MessageMedia(
            file = mapMediaFile(file), thumbnail = video.thumbnail?.file?.let(::mapMediaFile),
            width = video.width.coerceAtLeast(0), height = video.height.coerceAtLeast(0), format = MediaFormat.VIDEO,
            thumbnailFormat = mapThumbnailFormat(video.thumbnail?.format), hasSpoiler = content.hasSpoiler,
            fileName = video.fileName.orEmpty(), mimeType = video.mimeType.orEmpty(), durationSeconds = video.duration.coerceAtLeast(0),
            minithumbnail = mapMiniThumbnail(video.minithumbnail),
        ) }
    }
    is TdApi.MessageAnimation -> if (content.isSecret) null else content.animation?.let { animation ->
        animation.animation?.let { file -> MessageMedia(
            file = mapMediaFile(file), thumbnail = animation.thumbnail?.file?.let(::mapMediaFile),
            width = animation.width.takeIf { it > 0 } ?: animation.thumbnail?.width?.coerceAtLeast(0) ?: 0,
            height = animation.height.takeIf { it > 0 } ?: animation.thumbnail?.height?.coerceAtLeast(0) ?: 0,
            format = MediaFormat.ANIMATION,
            thumbnailFormat = mapThumbnailFormat(animation.thumbnail?.format), hasSpoiler = content.hasSpoiler,
            fileName = animation.fileName.orEmpty(), mimeType = animation.mimeType.orEmpty(),
            durationSeconds = animation.duration.coerceAtLeast(0),
            minithumbnail = mapMiniThumbnail(animation.minithumbnail),
        ) }
    }
    is TdApi.MessageDocument -> content.document?.let { document ->
        document.document?.let { file -> MessageMedia(
            file = mapMediaFile(file), thumbnail = document.thumbnail?.file?.let(::mapMediaFile), format = MediaFormat.FILE,
            thumbnailFormat = mapThumbnailFormat(document.thumbnail?.format),
            fileName = document.fileName.orEmpty(), mimeType = document.mimeType.orEmpty(),
        ) }
    }
    else -> null
}

internal fun mapFolderInfo(folder: TdApi.ChatFolderInfo) = ChatFolderInfo(
    folder.id, folder.name?.text?.text.orEmpty(), folder.icon?.name, folder.colorId, folder.isShareable, folder.hasMyInviteLinks,
)

internal fun mapChatFolder(id: Int, folder: TdApi.ChatFolder) = ChatFolder(
    id, folder.name?.text?.text.orEmpty(), folder.icon?.name, folder.colorId, folder.isShareable,
    folder.pinnedChatIds?.toList().orEmpty(), folder.includedChatIds?.toList().orEmpty(), folder.excludedChatIds?.toList().orEmpty(),
    folder.excludeMuted, folder.excludeRead, folder.excludeArchived, folder.includeContacts, folder.includeNonContacts,
    folder.includeBots, folder.includeGroups, folder.includeChannels,
    nameEntities = mapRichText(folder.name?.text).entities,
    animateCustomEmoji = folder.name?.animateCustomEmoji ?: false,
    nameEditable = folder.name?.text?.entities.orEmpty().all { it.type is TdApi.TextEntityTypeCustomEmoji },
)
