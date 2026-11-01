package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import com.badnng.moe.folders.*
import com.badnng.moe.media.AutoMediaPreferencesAccess
import com.badnng.moe.settings.SettingsError
import com.badnng.moe.settings.SettingsResult
import com.badnng.moe.settings.SettingsProfileColorsValues
import java.util.concurrent.atomic.AtomicBoolean

/** All entry points and completions run on TdEngine's actor, never on native callback threads. */
internal class ChatController(
    private val generation: Long,
    private val session: TdChatSession,
    private val enqueue: (() -> Unit) -> Unit,
    private val publish: (ChatSnapshot) -> Unit,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val schedule: (Long, () -> Unit) -> Unit = { _, _ -> },
    private val autoMediaAccess: AutoMediaPreferencesAccess? = null,
    private val isAccountAllowed: () -> Boolean = { true },
    private val enqueueFolderTerminal: (() -> Unit) -> Unit = enqueue,
    private val scheduleFolderTerminal: (Long, () -> Unit) -> Unit = schedule,
    private val captureFolderAuthority: () -> (() -> Boolean) = { { true } },
) {
    private var snapshot = ChatSnapshot(token = ChatToken(generation, 0, null))
    private val chats = mutableMapOf<Long, ChatItem>()
    private val names = mutableMapOf<Long, String>()
    private val forwardSourceNames = linkedMapOf<ForwardSourceIdentity, String>()
    private val forwardSourceRequests = mutableMapOf<ForwardSourceIdentity, Long>()
    private val attemptedForwardSources = mutableSetOf<ForwardSourceIdentity>()
    private val userAppearances = linkedMapOf<Long, SettingsProfileColorsValues>()
    private val chatAppearances = linkedMapOf<Long, SettingsProfileColorsValues>()
    private val appearanceVersions = linkedMapOf<ForwardSourceIdentity, Long>()
    private val userAvatars = mutableMapOf<Long, MediaFile>()
    private val messageSenders = mutableSetOf<Long>()
    private val messages = mutableMapOf<Long, ChatMessage>()
    // Only the current loaded window needs to distinguish history from native arrivals.
    private val historyMessageIds = mutableSetOf<Long>()
    private val contentOverrides = mutableMapOf<Long, ContentOverride>()
    private val permanentlyDeleted = mutableSetOf<MessageKey>()
    private val retired = mutableSetOf<MessageKey>()
    private val terminalMessages = mutableMapOf<MessageKey, ChatMessage>()
    private val replacementTargets = mutableMapOf<MessageKey, MessageKey>()
    private val contentVersions = mutableMapOf<MessageKey, Long>()
    private val editedDates = mutableMapOf<MessageKey, Int>()
    private val inlineKeyboardUpdates = mutableMapOf<MessageKey, Pair<Long, MessageInlineKeyboard?>>()
    private val retryAllowedAt = mutableMapOf<MessageKey, Long>()
    private val retryRequests = mutableMapOf<MessageKey, Long>()
    private val historyRemoved = mutableSetOf<Long>()
    private val inaccessibleSince = mutableMapOf<MessageKey, Long>()
    private var historyCursor = 0L
    private var newerCursor = 0L
    private var sequence = 0L
    private var supportOpening: SupportOpening? = null
    private var supportOpenInFlight: SupportOpening? = null
    private val listActionRequests = mutableMapOf<Long, Pair<ChatToken, Long>>()
    private val spamStates = mutableMapOf<Long, ChatReportSpamState>()
    private val spamRequests = mutableMapOf<Long, Long>()
    private val spamCapabilityVersions = mutableMapOf<Long, Long>()
    private var activeListRevision = 0L
    private var searchRequest: Long? = null
    private var overviewRequest: Long? = null
    private var clearRecentRequest: Long? = null
    private var recentRevision = 0L
    private val recentAddedDuringClear = mutableSetOf<Long>()
    private var detailsRequest: Long? = null
    private val mediaPages = mutableMapOf<ChatMediaCategory, ChatMediaIndexState>()
    private val mediaPageRequests = mutableMapOf<ChatMediaCategory, Long>()
    private val mediaCounts = mutableMapOf<ChatMediaCategory, Int>()
    private val mediaCountRequests = mutableMapOf<ChatMediaCategory, Long>()
    private val mediaCountErrors = mutableMapOf<ChatMediaCategory, ChatError>()
    private val mediaContentChanges = mutableMapOf<Long, Long>()
    private var mediaContentRevision = 0L
    private var pinnedRequest: Long? = null
    private var pinnedRefreshPending = false
    private var jumpRequest: Long? = null
    private var historyRequest: Long? = null
    private var historyPageRequest: Long? = null
    private var sendLatestRequest: Long? = null
    private var sendLatestExpectedMessage: MessageKey? = null
    private var sendLatestJumpRevision: Long? = null
    private var retrySendLatestWindow = false
    private var sendLatestNavigationRevision = 0L
    private var initialUnreadRequest: Long? = null
    private var newerRequest: Long? = null
    private var newerPageRequest: Long? = null
    private var sendRequest: Long? = null
    private var activeTextBatch: TextSendBatch? = null
    private val textBatches = linkedMapOf<Long, TextSendBatch>()
    private val ownedAttachmentSends = mutableMapOf<MessageKey, PendingAttachmentLease>()
    private val ownedAttachmentRetryTargets = mutableMapOf<MessageKey, List<ChatMessage>>()
    private var readRequest: Long? = null
    private var replyRevision = 0L
    private val replyOwners = mutableMapOf<Long, Long>()
    private var readDetailsRequest: Long? = null
    private var actionsRequest: Long? = null
    private var reactionsRequest: Long? = null
    private var reactionOptionsDirty = false
    private var reactionMutationRequest: Long? = null
    private var reactionMutationMessageId: Long? = null
    private val reactionUpdates = mutableMapOf<MessageKey, Pair<Long, List<MessageReaction>>>()
    private val reactionVisuals = linkedMapOf<ReactionKey, ReactionVisual>()
    private val reactionVisualRequests = mutableMapOf<ReactionKey, Long>()
    private val reactionVisualErrors = linkedSetOf<ReactionKey>()
    private var reactionPacksRequest: Long? = null
    private var reactionPackRequest: Long? = null
    private var reactionGroupRequest: Long? = null
    private data class SavedComposerContext(val reply: ChatMessage?, val quote: MessageQuote?, val edit: ChatMessage?, val editOwner: Long = 0)
    private data class PendingComposerContext(val chatId: Long, val context: SavedComposerContext,
        val replyVersion: Long, val editVersion: Long)
    private val savedComposerContexts = mutableMapOf<Long, SavedComposerContext>()
    private val confirmedDraftMessages = mutableMapOf<MessageKey, ChatMessage>()
    private var pendingComposerContext: PendingComposerContext? = null
    private var editRevision = 0L
    private val rememberedHistoryAnchors = linkedSetOf<MessageKey>()
    private val lastHistoryAnchors = mutableMapOf<Long, Long>()
    private data class ReplyNavigation(val destination: Long, val origin: MessageKey)
    private val replyNavigation = mutableListOf<ReplyNavigation>()
    private var previousEditableRequest: Long? = null
    private val messageActions = mutableMapOf<Long, MessageActions>()
    private var editRequest: Long? = null
    private data class PendingEdit(val messageId: Long, val owner: Long, val request: Long)
    private val pendingEdits = mutableMapOf<Long, PendingEdit>()
    private var editCaption = false
    private var mutationRequest: Long? = null
    private val outstandingMutations = mutableSetOf<Long>()
    private val replyRequests = mutableMapOf<Long, Long>()
    private val attemptedReplies = mutableSetOf<Long>()
    private val resolvedReplies = mutableMapOf<Long, MessageReply>()
    private data class QuoteAuthorResolution(val source: MessageReply, val resolved: MessageReply)
    private val quotedReplyAuthorRequests = mutableMapOf<Long, Long>()
    private val attemptedQuotedReplyAuthors = mutableSetOf<Long>()
    private val resolvedQuoteAuthors = mutableMapOf<Long, QuoteAuthorResolution>()
    private val listPages = mutableMapOf<ChatListRef, ListPage>()
    private val files = mutableMapOf<Int, MediaFile>()
    private val knownFileIds = mutableSetOf<Int>()
    private val fileRequests = mutableMapOf<Int, Long>()
    private val fileVersions = mutableMapOf<Int, Long>()
    private val fileErrors = mutableMapOf<Int, ChatError>()
    private val downloadOwners = mutableMapOf<Int, MediaDownloadOwner>()
    private val ownedFullRequests = mutableMapOf<Pair<MediaDownloadOwner, Int>, Long>()
    private val streamOwners = mutableSetOf<Pair<MediaDownloadOwner, Int>>()
    private val stickerCatalog = mutableMapOf<Int, StickerItem>()
    private val stickerOutlines = linkedMapOf<Int, StickerOutline>()
    private val stickerOutlineRequests = mutableMapOf<Int, Long>()
    private val stickerSectionRequests = mutableMapOf<Long?, Long>()
    private val queuedStickerSections = linkedSetOf<Long?>()
    private val dirtyStickerSections = mutableSetOf<Long?>()
    private var packsRequest: Long? = null
    private var packsLoaded = false
    private var packsDirty = false
    private var stickersOpened = false
    private var folderRequest: Long? = null
    private var folderEditorLease = AtomicBoolean(false)
    private var foldersVersion = 0L
    private var visibleIds = emptySet<Long>()
    private var visibleActive = false
    private var autoReadSuppressed = false
    private val viewedMessages = mutableSetOf<MessageKey>()
    private val clearedManualMarkers = mutableSetOf<Long>()
    private val markerRevisions = mutableMapOf<Long, Long>()
    private var deactivated = false
    private val autoMedia = AutoMediaController(session, autoMediaAccess, enqueue, schedule,
        { AutoMediaContext(snapshot.token, snapshot.token.chatId?.let(chats::get), messages.toMap(), files.toMap()) },
        { value -> if (!deactivated) { snapshot = snapshot.copy(autoMediaSettings = value); emit() } },
        { fileId, error -> if (!deactivated) { fileErrors[fileId] = error; emit() } })
    private val folderSettings = session.folderSettings?.let { native ->
        FolderSettingsController(generation, native, enqueue, scheduleFolderTerminal,
            publish = { if (!deactivated) { snapshot = snapshot.copy(folderSettings = it); emit() } },
            enqueueTerminal = enqueueFolderTerminal, captureAuthority = captureFolderAuthority,
            mutationReceipt = FolderMutationTerminal::receipt,
        )
    }
    private var accountRequest: Long? = null
    private var linkDomainsRequest: Long? = null
    private val pendingProfiles = linkedMapOf<Long, AccountProfile>()
    private val headerActivities = linkedMapOf<HeaderActivityKey, ChatActivity>()
    private var onlineMemberCount: Int? = null
    private var headerExpiryRevision = 0L
    private var headerExpiryDue: Long? = null

    fun start() {
        if (snapshot.enabled || deactivated) return
        snapshot = snapshot.copy(enabled = true)
        emit()
        loadAccount()
        loadLinkConfirmationDomains()
        loadChats(snapshot.token)
    }

    /** A retired controller can never publish account content into another client session. */
    fun deactivate() {
        deactivated = true
        cancelSupportOpening()
        autoMedia.close()
        releasePageDownloads()
        session.retireDownloads()
        folderEditorLease.set(false)
        folderSettings?.deactivate()
        spamRequests.clear()
        spamStates.clear()
        spamCapabilityVersions.clear()
        sendLatestRequest = null
        sendLatestExpectedMessage = null
        sendLatestJumpRevision = null
        retrySendLatestWindow = false
        sendLatestNavigationRevision++
        activeTextBatch = null
        textBatches.clear()
        savedComposerContexts.clear()
        confirmedDraftMessages.clear()
        pendingComposerContext = null
        rememberedHistoryAnchors.clear()
        replyNavigation.clear()
        lastHistoryAnchors.clear()
        replyOwners.clear()
        pendingEdits.clear()
        previousEditableRequest = null
        stickerOutlineRequests.clear()
        stickerOutlines.clear()
        clearHeaderActivities()
        accountRequest = null
        linkDomainsRequest = null
        pendingProfiles.clear()
        forwardSourceNames.clear()
        forwardSourceRequests.clear()
        attemptedForwardSources.clear()
        quotedReplyAuthorRequests.clear()
        attemptedQuotedReplyAuthors.clear()
        resolvedQuoteAuthors.clear()
        userAppearances.clear()
        chatAppearances.clear()
        appearanceVersions.clear()
        historyMessageIds.clear()
        snapshot = snapshot.copy(enabled = false, account = null, headerStatus = ChatHeaderStatus(), stickerOutlines = emptyMap(),
            forwardSourceNames = emptyMap(), senderAppearances = emptyMap(),
            appearanceCatalog = com.badnng.moe.settings.SettingsProfileColorsCatalog(), linkConfirmationDomains = null)
    }

    private fun clearHeaderActivities() {
        headerActivities.clear()
        onlineMemberCount = null
        headerExpiryDue = null
        headerExpiryRevision++
    }

    private fun activityChanged(event: ChatEvent.ActivityChanged) {
        if (!snapshot.enabled || event.chatId != snapshot.token.chatId ||
            (!event.senderIsChat && event.senderId <= 0) || event.senderId == 0L) return
        val key = HeaderActivityKey(event.senderId, event.senderIsChat)
        headerActivities.remove(key)
        event.kind?.let { kind ->
            headerActivities[key] = ChatActivity(kind = kind, senderId = event.senderId, senderIsChat = event.senderIsChat,
                progress = event.progress?.takeIf { it in 0..100 }, expiresAtMillis = nowMillis() + HEADER_ACTIVITY_TIMEOUT_MILLIS)
        }
        scheduleHeaderExpiry()
    }

    private fun scheduleHeaderExpiry() {
        val due = headerActivities.values.minOfOrNull { it.expiresAtMillis }
        if (due == null) {
            headerExpiryDue = null
            headerExpiryRevision++
            return
        }
        if (headerExpiryDue?.let { it <= due } == true) return
        val revision = ++headerExpiryRevision
        val token = snapshot.token
        headerExpiryDue = due
        schedule((due - nowMillis()).coerceAtLeast(1)) {
            enqueue {
                if (!deactivated && snapshot.enabled && snapshot.token == token && headerExpiryRevision == revision) {
                    headerExpiryDue = null
                    headerActivities.entries.removeAll { it.value.expiresAtMillis <= nowMillis() }
                    emit()
                    scheduleHeaderExpiry()
                }
            }
        }
    }

    private fun loadAccount() {
        val request = ++sequence
        accountRequest = request
        safely<AccountProfile>({ session.getMe(it) }) { result ->
            if (deactivated || !snapshot.enabled || accountRequest != request) return@safely
            accountRequest = null
            val profile = result.value?.takeIf { errorOf(result) == null && it.userId > 0 }
            if (profile != null) setAccount(pendingProfiles[profile.userId] ?: profile)
            pendingProfiles.clear()
            emit()
        }
    }

    private fun loadLinkConfirmationDomains() {
        val request = ++sequence
        linkDomainsRequest = request
        safely<Set<String>>({ session.linkConfirmationDomains(it) }) { result ->
            if (deactivated || !snapshot.enabled || linkDomainsRequest != request) return@safely
            linkDomainsRequest = null
            if (result.error == null && result.value != null) {
                snapshot = snapshot.copy(linkConfirmationDomains = result.value.toSet())
                emit()
            }
        }
    }

    private fun setAccount(profile: AccountProfile) {
        profile.appearance?.let { rememberUserAppearance(profile.userId, it) }
        profile.avatar?.let(::rememberFile)
        snapshot = snapshot.copy(account = profile)
        folderSettings?.accountPremium(profile.isPremium)
        autoMedia.account(profile.userId)
    }

    fun event(event: ChatEvent) {
        if (deactivated) return
        when (event) {
            is ChatEvent.NewChat -> {
                spamCapabilityChanged(event.chat.id)
                event.chat.appearance?.let { rememberChatAppearance(event.chat.id, it) }
                chats[event.chat.id] = event.chat.copy(appearance = event.chat.appearance ?: chatAppearances[event.chat.id]
                    ?: chats[event.chat.id]?.appearance, lastMessage = event.chat.lastMessage?.let {
                    it.copy(inlineKeyboard = currentInlineKeyboard(it))
                })
                event.chat.avatar?.let(::rememberFile)
                event.chat.lastMessage?.let(::rememberSender)
                event.chat.lastMessage?.reactions?.let(::rememberReactionSenders)
                event.chat.lastMessage?.media?.let(::rememberMedia)
            }
            is ChatEvent.ChatAppearance -> {
                rememberChatAppearance(event.chatId, event.appearance)
                updateChat(event.chatId) { copy(appearance = event.appearance) }
            }
            is ChatEvent.AppearanceCatalog -> snapshot = snapshot.copy(appearanceCatalog = event.catalog)
            is ChatEvent.Position -> updateChat(event.chatId) {
                copy(order = if (event.list == ChatListRef()) event.order else order,
                    positions = positions + (event.list to event.order),
                    pinnedLists = if (event.isPinned && event.order > 0) pinnedLists + event.list else pinnedLists - event.list)
            }
            is ChatEvent.ListMembership -> updateChat(event.chatId) {
                // A partial membership update cannot manufacture an initially unknown full list.
                copy(chatLists = chatLists?.let { if (event.added) it + event.list else it - event.list })
            }
            is ChatEvent.Positions -> updateChat(event.chatId) {
                copy(order = event.positions[ChatListRef()] ?: 0, positions = event.positions.toMap(),
                    pinnedLists = event.pinnedLists.filterTo(mutableSetOf()) { (event.positions[it] ?: 0) > 0 })
            }
            is ChatEvent.Title -> updateChat(event.chatId) { copy(title = event.title) }
            is ChatEvent.TopChatsDisabled -> if (event.disabled != false) {
                // Both disabled and unknown revoke already displayed suggestions. Do not reset the
                // query, recent records, selection or UI scroll, and do not reload on enable.
                overviewRequest = null
                runCatching { session.invalidateSearchOverview() }
                snapshot = snapshot.copy(search = snapshot.search.copy(frequent = emptyList(), overviewLoading = false))
            }
            is ChatEvent.LastMessage -> {
                val message = event.message?.let { it.copy(inlineKeyboard = currentInlineKeyboard(it)) }
                updateChat(event.chatId) {
                    val nextPositions = event.positions?.toMap() ?: (positions + (ChatListRef() to event.order))
                    copy(lastMessage = message, order = event.order,
                        positions = nextPositions,
                        pinnedLists = (event.pinnedLists ?: pinnedLists).filterTo(mutableSetOf()) { (nextPositions[it] ?: 0) > 0 })
                }
                message?.let { acceptMessage(it) }
            }
            is ChatEvent.Inbox -> updateChat(event.chatId) {
                copy(unreadCount = event.unreadCount, lastReadInboxId = event.lastReadId)
            }
            is ChatEvent.Outbox -> updateChat(event.chatId) { copy(lastReadOutboxId = event.lastReadId) }
            is ChatEvent.Permissions -> updateChat(event.chatId) {
                copy(canSend = event.canSend, canSendPhotos = event.canSendPhotos, canSendStickers = event.canSendStickers,
                    canSendVideos = event.canSendVideos, canSendDocuments = event.canSendDocuments,
                    canSendVoiceNotes = event.canSendVoiceNotes)
            }
            is ChatEvent.ProtectedContent -> updateChat(event.chatId) { copy(hasProtectedContent = event.hasProtectedContent) }
            is ChatEvent.ActionBar -> {
                spamCapabilityChanged(event.chatId)
                updateChat(event.chatId) { copy(actionBar = event.actionBar) }
            }
            is ChatEvent.LeaveCapability -> {
                spamCapabilityChanged(event.chatId)
                updateChat(event.chatId) { copy(canLeave = event.canLeave) }
            }
            is ChatEvent.GroupMembership -> {
                spamCapabilityChanged(event.chatId)
                updateChat(event.chatId) { copy(isJoinedGroup = event.isMember, canLeave = event.isMember) }
                if (!event.isMember && snapshot.reactionGroupSourceId == event.chatId) {
                    reactionGroupRequest = null
                    snapshot = snapshot.copy(reactionGroupLoading = false, reactionGroupPack = null,
                        reactionGroupError = ChatError.FORBIDDEN, reactionPackId = null, reactionPackEmojis = emptyList())
                }
            }
            is ChatEvent.NewMessage -> {
                val native = event.message
                val existing = messages[native.id]?.takeIf { it.chatId == native.chatId }
                val animateIncoming = !native.outgoing && native.kind != MessageKind.SERVICE &&
                    native.chatId == snapshot.token.chatId && !snapshot.historyHasNewer &&
                    initialUnreadRequest == null && jumpRequest == null &&
                    existing?.incomingAnimationId == null && native.id !in historyMessageIds
                val message = native.copy(
                    inlineKeyboard = currentInlineKeyboard(native),
                    incomingAnimationId = existing?.incomingAnimationId ?: if (animateIncoming) ++sequence else null,
                )
                acceptMessage(message)
                updateChat(message.chatId) {
                    if (message.id > 0 && message.id >= (lastMessage?.id ?: 0)) copy(lastMessage = message) else this
                }
            }
            is ChatEvent.SendSucceeded -> {
                ownedAttachmentSends.remove(MessageKey(event.message.chatId, event.oldMessageId))?.release()
                replaceMessage(event.oldMessageId, event.message)
            }
            is ChatEvent.SendFailed -> {
                replaceMessage(event.oldMessageId, event.message)
                ownedAttachmentSends.remove(MessageKey(event.message.chatId, event.oldMessageId))?.let {
                    trackOwnedAttachment(event.message, it)
                }
            }
            is ChatEvent.Content -> {
                val key = MessageKey(event.chatId, event.messageId)
                contentVersions[key] = ++sequence
                if (event.chatId == snapshot.token.chatId) mediaContentChanges[event.messageId] = sequence
                terminalMessages[key]?.let {
                    terminalMessages[key] = ContentOverride(event).applyTo(it)
                }
                confirmedDraftMessages[key]?.let {
                    confirmedDraftMessages[key] = ContentOverride(event).applyTo(it)
                }
                if (snapshot.token.chatId == event.chatId) {
                    contentOverrides[event.messageId] = ContentOverride(event)
                    messages[event.messageId]?.let {
                        messages[event.messageId] = ContentOverride(event).applyTo(it)
                    }
                }
                updateChat(event.chatId) {
                    copy(lastMessage = lastMessage?.let {
                        if (it.id == event.messageId) ContentOverride(event).applyTo(it) else it
                    })
                }
                refreshResolvedReplies(event.chatId, event.messageId, event.text, event.entities)
                event.media?.let(::rememberMedia)
            }
            is ChatEvent.Edited -> {
                val key = MessageKey(event.chatId, event.messageId)
                contentVersions[key] = ++sequence
                editedDates[key] = event.editDate
                terminalMessages[key]?.let { terminalMessages[key] = it.copy(editDate = event.editDate) }
                confirmedDraftMessages[key]?.let { confirmedDraftMessages[key] = it.copy(editDate = event.editDate) }
                if (snapshot.token.chatId == event.chatId) messages[event.messageId]?.let {
                    messages[event.messageId] = it.copy(editDate = event.editDate)
                }
                updateChat(event.chatId) {
                    copy(lastMessage = lastMessage?.let { if (it.id == event.messageId) it.copy(editDate = event.editDate) else it })
                }
            }
            is ChatEvent.ReplyMarkup -> {
                val key = MessageKey(event.chatId, event.messageId)
                if (key !in permanentlyDeleted && key !in retired) {
                    inlineKeyboardUpdates[key] = ++sequence to event.inlineKeyboard
                    contentVersions[key] = sequence
                    terminalMessages[key]?.let { terminalMessages[key] = it.copy(inlineKeyboard = event.inlineKeyboard) }
                    if (snapshot.token.chatId == event.chatId) messages[event.messageId]?.let {
                        messages[event.messageId] = it.copy(inlineKeyboard = event.inlineKeyboard)
                    }
                    updateChat(event.chatId) {
                        copy(lastMessage = lastMessage?.let {
                            if (it.id == event.messageId) it.copy(inlineKeyboard = event.inlineKeyboard) else it
                        })
                    }
                    snapshot = snapshot.copy(
                        search = snapshot.search.copy(messages = snapshot.search.messages.map { reconcileToolMessage(it, 0) }),
                        pinned = snapshot.pinned.copy(messages = snapshot.pinned.messages.map { reconcileToolMessage(it, 0) }),
                    )
                }
            }
            is ChatEvent.Reactions -> {
                val key = MessageKey(event.chatId, event.messageId)
                if (key !in permanentlyDeleted) {
                    rememberReactionSenders(event.reactions)
                    reactionUpdates[key] = ++sequence to event.reactions.toList()
                    terminalMessages[key]?.let { terminalMessages[key] = it.copy(reactions = event.reactions) }
                    if (snapshot.token.chatId == event.chatId) messages[event.messageId]?.let {
                        messages[event.messageId] = it.copy(reactions = event.reactions)
                    }
                    updateChat(event.chatId) {
                        copy(lastMessage = lastMessage?.let { if (it.id == event.messageId) it.copy(reactions = event.reactions) else it })
                    }
                    if (snapshot.token.chatId == event.chatId && snapshot.reactionMessageId == event.messageId) refreshReactionOptions()
                }
            }
            is ChatEvent.ReactionAvailabilityChanged -> if (event.chatId == null || event.chatId == snapshot.token.chatId) refreshReactionOptions()
            is ChatEvent.Deleted -> {
                val removed = event.messageIds.toSet()
                savedComposerContexts[event.chatId]?.let { saved ->
                    val reply = saved.reply?.takeUnless { it.id in removed }
                    val edit = saved.edit?.takeUnless { it.id in removed }
                    if (reply == null && edit == null) savedComposerContexts.remove(event.chatId)
                    else savedComposerContexts[event.chatId] = saved.copy(reply = reply, quote = saved.quote.takeIf { reply != null },
                        edit = edit, editOwner = saved.editOwner.takeIf { edit != null } ?: 0)
                }
                if (event.permanent) event.messageIds.forEach { ownedAttachmentSends.remove(MessageKey(event.chatId, it))?.release() }
                event.messageIds.forEach { id ->
                    confirmedDraftMessages.remove(MessageKey(event.chatId, id))
                    rememberedHistoryAnchors.remove(MessageKey(event.chatId, id))
                    if (lastHistoryAnchors[event.chatId] == id) lastHistoryAnchors.remove(event.chatId)
                    replyNavigation.removeAll { it.origin == MessageKey(event.chatId, id) }
                    reactionUpdates.remove(MessageKey(event.chatId, id))
                    inlineKeyboardUpdates.remove(MessageKey(event.chatId, id))
                    contentVersions[MessageKey(event.chatId, id)] = ++sequence
                    if (event.permanent) permanentlyDeleted += MessageKey(event.chatId, id)
                    else inaccessibleSince[MessageKey(event.chatId, id)] = ++sequence
                    if (snapshot.token.chatId == event.chatId) {
                        messages.remove(id)
                        contentOverrides.remove(id)
                        replyRequests.remove(id)
                        resolvedReplies.remove(id)
                        quotedReplyAuthorRequests.remove(id)
                        resolvedQuoteAuthors.remove(id)
                        messageActions.remove(id)
                        if (snapshot.actionsMessageId == id) clearMessageActions()
                        if (snapshot.editTarget?.id == id) clearEdit()
                        if (snapshot.replyTarget?.id == id) clearReply()
                        if (snapshot.readDetailsMessageId == id) clearReadDetails()
                        if (snapshot.reactionMessageId == id) clearReactions()
                        if (reactionMutationMessageId == id) {
                            reactionMutationRequest = null
                            reactionMutationMessageId = null
                            snapshot = snapshot.copy(reactionPending = false, reactionError = null, reactionErrorMessageId = null)
                        }
                        if (historyRequest != null) historyRemoved += id
                    }
                }
                invalidateResolvedReplies(event.chatId, event.messageIds.toSet())
                val removedIds = event.messageIds.toSet()
                val searchMessages = snapshot.search.messages.filterNot { it.chatId == event.chatId && it.id in removedIds }
                val pinnedMessages = snapshot.pinned.messages.filterNot { it.chatId == event.chatId && it.id in removedIds }
                snapshot = snapshot.copy(
                    search = snapshot.search.copy(messages = searchMessages),
                    pinned = snapshot.pinned.copy(messages = pinnedMessages,
                        totalCount = (snapshot.pinned.totalCount - (snapshot.pinned.messages.size - pinnedMessages.size)).coerceAtLeast(0)),
                    jumpMessageId = snapshot.jumpMessageId?.takeUnless { snapshot.token.chatId == event.chatId && it in removedIds },
                    jumpHighlight = snapshot.jumpHighlight && snapshot.jumpMessageId != null &&
                        !(snapshot.token.chatId == event.chatId && snapshot.jumpMessageId in removedIds),
                    unreadBoundary = snapshot.unreadBoundary?.let { boundary ->
                        if (snapshot.token.chatId == event.chatId && boundary.firstUnreadMessageId?.let { it in removedIds } == true)
                            boundary.copy(firstUnreadMessageId = messages.values.filter {
                                it.id > boundary.afterMessageId && !it.outgoing && it.delivery == MessageDelivery.SENT
                            }.minOfOrNull { it.id }) else boundary
                    },
                )
                updateChat(event.chatId) {
                    copy(lastMessage = lastMessage?.takeUnless { it.id in event.messageIds })
                }
            }
            is ChatEvent.SenderName -> {
                val source = senderSource(event.id, event.isChat)
                source?.let { appearanceVersions[it] = ++sequence; trimAppearances(appearanceVersions, 1024) }
                if (event.isChat) updateChat(event.id) { copy(title = event.name) }
                else {
                    names[event.id] = event.name
                    event.profile?.takeIf { it.userId == event.id && it.userId > 0 }?.let { profile ->
                        profile.appearance?.let { rememberUserAppearance(profile.userId, it) }
                        val avatar = profile.avatar
                        if (avatar == null) userAvatars.remove(profile.userId)
                        else {
                            userAvatars[profile.userId] = avatar
                            if (profile.userId in messageSenders) rememberFile(avatar)
                        }
                        if (snapshot.account?.userId == profile.userId) setAccount(profile)
                        else if (snapshot.account == null && accountRequest != null) {
                            // GetMe establishes identity; UpdateUser alone never selects an account.
                            pendingProfiles.remove(profile.userId)
                            pendingProfiles[profile.userId] = profile
                            if (pendingProfiles.size > 256) pendingProfiles.remove(pendingProfiles.keys.first())
                        }
                    }
                }
            }
            is ChatEvent.MarkedUnread -> {
                markerRevisions[event.chatId] = (markerRevisions[event.chatId] ?: 0) + 1
                if (event.marked) clearedManualMarkers.remove(event.chatId)
                updateChat(event.chatId) { copy(markedUnread = event.marked) }
            }
            is ChatEvent.NotificationMuted -> updateChat(event.chatId) { copy(isMuted = event.muted) }
            is ChatEvent.Photo -> {
                event.avatar?.let(::rememberFile)
                updateChat(event.chatId) { copy(avatar = event.avatar) }
            }
            is ChatEvent.FileUpdated -> updateFile(event.file)
            is ChatEvent.Folders -> {
                foldersVersion++
                snapshot = snapshot.copy(folders = event.folders.toList(), mainListPosition = event.mainPosition, folderTagsEnabled = event.tagsEnabled)
                folderSettings?.nativeUpdate(FolderNativeState(event.folders.toList(), event.mainPosition, event.tagsEnabled))
                val folderIds = event.folders.map { it.id }.toSet()
                listPages.keys.removeAll { it.folderId > 0 && it.folderId !in folderIds }
                if (snapshot.activeList.folderId > 0 && event.folders.none { it.id == snapshot.activeList.folderId }) {
                    selectList(snapshot.token, ChatListRef())
                }
                val editing = snapshot.folderEditorId
                if (editing != null && editing > 0 && event.folders.none { it.id == editing } && !snapshot.folderSaving) {
                    clearFolderEditor()
                }
            }
            is ChatEvent.UnreadChats -> snapshot = snapshot.copy(unreadChats = snapshot.unreadChats + (event.list to event.count.coerceAtLeast(0)))
            is ChatEvent.MemberCount -> updateChat(event.chatId) { copy(memberCount = event.count?.takeIf { it > 0 }) }
            is ChatEvent.OnlineCount -> if (event.chatId == snapshot.token.chatId) {
                onlineMemberCount = event.count?.takeIf { it > 0 }
            }
            is ChatEvent.ActivityChanged -> activityChanged(event)
            is ChatEvent.PinnedChanged -> if (event.chatId == snapshot.token.chatId) {
                if (pinnedRequest != null) pinnedRefreshPending = true else loadPinned(snapshot.token)
            }
            ChatEvent.RecentStickersChanged -> if (stickersOpened) requestStickerSection(snapshot.token, null, refresh = true)
            ChatEvent.InstalledStickersChanged -> if (stickersOpened) loadStickerPacks(snapshot.token)
        }
        updateMediaIndex(event)
        emit()
    }

    fun select(token: ChatToken, chatId: Long) {
        if (!valid(token) || (chats[chatId]?.positions?.get(snapshot.activeList) ?: 0) <= 0) return
        if (token.chatId == chatId) {
            autoReadSuppressed = false
            submitVisibleRead()
            return
        }
        resetSelection(chatId)
        runCatching { session.open(chatId) }
        emit()
        loadInitialHistory(snapshot.token)
        loadPinned(snapshot.token)
    }

    /** Support chats can be genuine native dialogs without a position in the current list. */
    fun openSupport(token: ChatToken, active: () -> Boolean, completed: (SettingsResult<ChatToken>) -> Unit) {
        if (!valid(token) || !isAccountAllowed() || !active() || supportOpenInFlight != null) {
            completed(SettingsResult(error = SettingsError.NOT_READY)); return
        }
        val previous = supportOpening
        if (previous != null && previous.owner.get() && previous.active()) {
            completed(SettingsResult(error = SettingsError.NOT_READY)); return
        }
        cancelSupportOpening()
        val support = session.helpSupport
        if (support == null) {
            completed(SettingsResult(error = SettingsError.UNSUPPORTED)); return
        }
        val request = SupportOpening(active, completed)
        supportOpening = request
        val lease = { request.owner.get() && active() && isAccountAllowed() }
        val resolved = AtomicBoolean()
        fun finish(value: SettingsResult<ChatToken>) {
            if (supportOpening === request) supportOpening = null
            request.owner.set(false)
            if (request.delivered.compareAndSet(false, true)) completed(value)
        }
        fun failure(error: ChatError?) = SettingsResult<ChatToken>(error = when (error) {
            ChatError.FORBIDDEN -> SettingsError.FORBIDDEN
            ChatError.RATE_LIMITED -> SettingsError.RATE_LIMITED
            ChatError.UNSUPPORTED -> SettingsError.UNSUPPORTED
            ChatError.NOT_READY -> SettingsError.NOT_READY
            else -> SettingsError.REQUEST_FAILED
        })
        try {
            support.resolve(lease) { result ->
                if (resolved.compareAndSet(false, true)) enqueue resolved@{
                    if (!lease() || !valid(token) || supportOpening !== request) {
                        finish(SettingsResult(error = SettingsError.NOT_READY)); return@resolved
                    }
                    val target = result.value
                    if (result.error != null || target == null || target.userId <= 0 || target.chat.id == 0L ||
                        target.chat.privateUserId != target.userId) {
                        finish(failure(result.error)); return@resolved
                    }
                    val opened = AtomicBoolean()
                    supportOpenInFlight = request
                    try {
                        support.open(target, lease) { response ->
                            if (opened.compareAndSet(false, true)) enqueue opened@{
                                if (supportOpenInFlight === request) supportOpenInFlight = null
                                val accepted = response.error == null && response.value != null
                                if (!lease() || !valid(token) || supportOpening !== request) {
                                    // OpenChat is idempotent. Do not close a dialog independently selected meanwhile.
                                    if (accepted && !deactivated && snapshot.token.chatId != target.chat.id)
                                        runCatching { session.close(target.chat.id) }
                                    finish(SettingsResult(error = SettingsError.NOT_READY)); return@opened
                                }
                                if (!accepted) { finish(failure(response.error)); return@opened }
                                supportOpening = null
                                request.owner.set(false)
                                // Updates already processed by the actor retain their newer native metadata.
                                if (chats[target.chat.id] == null) chats[target.chat.id] = target.chat
                                val changed = token.chatId != target.chat.id
                                if (changed) resetSelection(target.chat.id)
                                emit()
                                if (changed) {
                                    loadInitialHistory(snapshot.token)
                                    loadPinned(snapshot.token)
                                }
                                finish(SettingsResult(value = snapshot.token))
                            }
                        }
                    } catch (_: Exception) {
                        if (supportOpenInFlight === request) supportOpenInFlight = null
                        finish(failure(ChatError.REQUEST_FAILED))
                    }
                }
            }
        } catch (_: Exception) {
            finish(failure(ChatError.REQUEST_FAILED))
        }
    }

    fun cancelSupportOpening() {
        val request = supportOpening ?: return
        supportOpening = null
        request.owner.set(false)
        if (request.delivered.compareAndSet(false, true))
            request.completed(SettingsResult(error = SettingsError.NOT_READY))
    }

    fun returnToLatest(token: ChatToken) {
        val chatId = token.chatId ?: return
        if (!valid(token)) return
        val boundary = snapshot.unreadBoundary
        resetSelection(chatId, captureUnread = false)
        snapshot = snapshot.copy(unreadBoundary = boundary)
        runCatching { session.open(chatId) }
        emit()
        loadOlder(snapshot.token)
        loadPinned(snapshot.token)
    }

    /** Real user scrolling or leaving the page revokes a pending send's navigation, not its acceptance. */
    fun cancelSendLatestNavigation(token: ChatToken) {
        if (!valid(token)) return
        sendLatestNavigationRevision++
        retrySendLatestWindow = false
        sendLatestExpectedMessage = null
        val completedJump = sendLatestJumpRevision == snapshot.jumpRevision && snapshot.jumpMessageId != null
        sendLatestJumpRevision = null
        if (completedJump) snapshot = snapshot.copy(jumpMessageId = null, jumpHighlight = false)
        val request = sendLatestRequest
        sendLatestRequest = null
        if (request != null && historyRequest == request) {
            historyRequest = null
            historyPageRequest = null
            historyRemoved.clear()
            snapshot = snapshot.copy(historyLoading = false, newerLoading = false)
            emit()
        } else if (completedJump) emit()
    }

    /** Keep the old drawable window until a bounded, contiguous native latest window is ready. */
    private fun loadLatestAfterSend(token: ChatToken, expected: MessageKey? = sendLatestExpectedMessage) {
        val chatId = token.chatId ?: return
        if (!valid(token)) return
        val request = ++sequence
        sendLatestRequest = request
        sendLatestExpectedMessage = expected
        retrySendLatestWindow = false
        historyRequest = request
        historyPageRequest = null
        historyRemoved.clear()
        initialUnreadRequest = null
        jumpRequest = null
        newerRequest = null
        newerPageRequest = null
        snapshot = snapshot.copy(historyLoading = false, historyError = null, newerLoading = true,
            newerError = null, historyHasNewer = true)
        emit()
        val found = linkedMapOf<Long, ChatMessage>()
        var cursor = 0L
        var batches = 0

        fun current() = valid(token) && sendLatestRequest == request && historyRequest == request
        fun finish(error: ChatError) {
            sendLatestRequest = null
            historyRequest = null
            historyPageRequest = null
            historyRemoved.clear()
            retrySendLatestWindow = true
            snapshot = snapshot.copy(historyLoading = false, newerLoading = false, historyError = error)
            emit()
        }
        fun reconciledWindow(): List<ChatMessage> = found.values.mapNotNull { supplied ->
            val suppliedKey = MessageKey(chatId, supplied.id)
            val native = if (suppliedKey in retired) acknowledgedMessage(supplied) ?: return@mapNotNull null else supplied
            if (!toolMessageAvailable(native, request)) return@mapNotNull null
            val stable = messages[native.id] ?: terminalMessages[MessageKey(chatId, native.id)]
            reconcileToolMessage(native, request).copy(
                presentationId = stable?.presentationId ?: native.presentationId,
                sendAnimationId = stable?.sendAnimationId ?: native.sendAnimationId,
                incomingAnimationId = stable?.incomingAnimationId ?: native.incomingAnimationId,
            )
        }.distinctBy { it.id }.sortedBy { it.id }

        fun nextPage() {
            val from = cursor
            val pageRequest = ++sequence
            historyPageRequest = pageRequest
            batches++
            safely<List<ChatMessage>>({ callback ->
                session.history(chatId, from, (PAGE_SIZE - found.size).coerceAtLeast(1), callback)
            }) { result ->
                if (!current() || historyPageRequest != pageRequest) return@safely
                val error = errorOf(result)
                if (error != null) { finish(error); return@safely }
                val raw = result.value.orEmpty().filter { it.chatId == chatId && it.id > 0 }
                if (result.value.orEmpty().isNotEmpty() && raw.isEmpty()) {
                    finish(ChatError.REQUEST_FAILED)
                    return@safely
                }
                val oldest = raw.minOfOrNull { it.id }
                if (raw.isNotEmpty() && (oldest == null || from != 0L && oldest >= from)) {
                    finish(ChatError.REQUEST_FAILED)
                    return@safely
                }
                if (oldest != null) cursor = oldest
                raw.forEach { message ->
                    val current = if (MessageKey(chatId, message.id) in retired) acknowledgedMessage(message) else message
                    if (found.size < PAGE_SIZE && current != null && toolMessageAvailable(current, request)) {
                        found.putIfAbsent(message.id, message)
                    }
                }
                val complete = raw.isEmpty() || found.size >= PAGE_SIZE
                if (complete) {
                    val window = reconciledWindow()
                    if (window.isEmpty()) { finish(ChatError.REQUEST_FAILED); return@safely }
                    var expectedKey = expected
                    val visited = mutableSetOf<MessageKey>()
                    while (expectedKey != null && visited.add(expectedKey)) expectedKey = replacementTargets[expectedKey] ?: break
                    val expectedMessage = expectedKey
                    if (expectedMessage != null && expectedMessage !in permanentlyDeleted &&
                        (inaccessibleSince[expectedMessage] ?: 0) <= request && window.none { it.id == expectedMessage.messageId }) {
                        // SendMessage acceptance can precede the native latest history cache.
                        // A missing accepted row is not permission to append it across a gap.
                        finish(ChatError.REQUEST_FAILED)
                        return@safely
                    }
                    val latest = window.last().id
                    val nativeLatest = chats[chatId]?.lastMessage?.id?.takeIf { it > 0 }
                    // New arrivals can overtake the first native page while the older pages fill it.
                    // Start again at the real head rather than joining those arrivals across a gap.
                    if (nativeLatest != null && nativeLatest > latest) {
                        if (batches >= MAX_HISTORY_BATCHES) { finish(ChatError.REQUEST_FAILED); return@safely }
                        found.clear()
                        cursor = 0
                        nextPage()
                        return@safely
                    }
                    val draftTargets = listOfNotNull(snapshot.replyTarget, snapshot.editTarget)
                    val restoring = pendingComposerContext?.takeIf { it.chatId == chatId }?.context
                    val retainedDraftKeys = (draftTargets + listOfNotNull(restoring?.reply, restoring?.edit))
                        .map { MessageKey(it.chatId, it.id) }.toSet()
                    confirmedDraftMessages.keys.retainAll(retainedDraftKeys)
                    draftTargets.forEach { target ->
                        val key = MessageKey(target.chatId, target.id)
                        val source = messages[target.id]?.takeIf { it.chatId == target.chatId } ?: confirmedDraftMessages[key] ?: target
                        confirmedDraftMessages[key] = reconcileToolMessage(source, request)
                    }
                    messages.clear()
                    historyMessageIds.clear()
                    newerCursor = 0
                    window.forEach { acceptMessage(it, request, fromHistory = true) }
                    historyCursor = cursor
                    visibleIds = emptySet()
                    sendLatestRequest = null
                    sendLatestExpectedMessage = null
                    historyRequest = null
                    historyPageRequest = null
                    historyRemoved.clear()
                    snapshot = snapshot.copy(historyLoading = false, historyEnd = raw.isEmpty(), historyError = null,
                        newerLoading = false, newerError = null, historyHasNewer = false,
                        jumpMessageId = latest, jumpRevision = snapshot.jumpRevision + 1, jumpHighlight = false)
                    sendLatestJumpRevision = snapshot.jumpRevision
                    emit()
                } else if (batches >= MAX_HISTORY_BATCHES) finish(ChatError.REQUEST_FAILED)
                else nextPage()
            }
        }
        nextPage()
    }

    /** The UI has placed this jump at the real latest edge; retain its current window and context. */
    fun completeHistoryJump(token: ChatToken, jumpRevision: Long) {
        if (!valid(token) || snapshot.jumpRevision != jumpRevision || snapshot.jumpMessageId == null ||
            snapshot.historyHasNewer || snapshot.historyLoading || snapshot.newerLoading || jumpRequest != null) return
        snapshot = snapshot.copy(jumpMessageId = null, jumpHighlight = false)
        if (sendLatestJumpRevision == jumpRevision) sendLatestJumpRevision = null
        emit()
    }

    fun selectList(token: ChatToken, list: ChatListRef) {
        if (!valid(token) || list == snapshot.activeList || list.folderId < -1 ||
            (list.folderId > 0 && snapshot.folders.none { it.id == list.folderId })) return
        // Folder navigation is independent of the open conversation and its in-flight requests.
        activeListRevision++
        snapshot = snapshot.copy(activeList = list)
        emit()
        loadChats(snapshot.token)
    }

    private fun resetSelection(chatId: Long?, captureUnread: Boolean = true) {
        cancelSupportOpening()
        autoMedia.clearPage()
        releasePageDownloads()
        sendLatestRequest = null
        sendLatestExpectedMessage = null
        sendLatestJumpRevision = null
        retrySendLatestWindow = false
        sendLatestNavigationRevision++
        rememberComposerContext()
        confirmedDraftMessages.clear()
        previousEditableRequest = null
        searchRequest = null
        overviewRequest = null
        clearRecentRequest = null
        recentRevision++
        recentAddedDuringClear.clear()
        detailsRequest = null
        mediaPages.clear()
        mediaPageRequests.clear()
        mediaCounts.clear()
        mediaCountRequests.clear()
        mediaCountErrors.clear()
        mediaContentChanges.clear()
        mediaContentRevision++
        pinnedRequest = null
        pinnedRefreshPending = false
        jumpRequest = null
        snapshot.token.chatId?.let { runCatching { session.close(it) } }
        clearHeaderActivities()
        messages.clear()
        historyMessageIds.clear()
        contentOverrides.clear()
        historyRemoved.clear()
        historyCursor = 0
        newerCursor = 0
        historyRequest = null
        historyPageRequest = null
        initialUnreadRequest = null
        newerRequest = null
        newerPageRequest = null
        readRequest = null
        clearReply(changeOwner = false)
        clearReadDetails()
        clearMessageActions()
        clearReactions()
        reactionMutationRequest = null
        reactionMutationMessageId = null
        messageActions.clear()
        clearEdit()
        mutationRequest = null
        replyRequests.clear()
        attemptedReplies.clear()
        resolvedReplies.clear()
        quotedReplyAuthorRequests.clear()
        attemptedQuotedReplyAuthors.clear()
        resolvedQuoteAuthors.clear()
        forwardSourceRequests.clear()
        attemptedForwardSources.clear()
        visibleIds = emptySet()
        visibleActive = false
        autoReadSuppressed = false
        stickerSectionRequests.clear()
        stickerOutlineRequests.clear()
        queuedStickerSections.clear()
        dirtyStickerSections.clear()
        packsRequest = null
        packsDirty = false
        stickersOpened = false
        snapshot = snapshot.copy(
            token = ChatToken(generation, snapshot.token.selection + 1, chatId),
            messages = emptyList(), historyLoading = false, historyEnd = false, historyError = null,
            readPending = false, readError = null,
            stickerSections = snapshot.stickerSections.map { it.copy(loading = false) }, stickersLoading = false,
            mutationMessageId = null, mutationKind = null, mutationPending = false, mutationError = null,
            reactionPending = false, reactionError = null, reactionErrorMessageId = null,
            headerStatus = ChatHeaderStatus(),
            search = snapshot.search.copy(loading = false, overviewLoading = false, recentClearing = false), details = ChatDetailsState(),
            pinned = ChatPinnedState(), mediaIndex = ChatMediaIndexState(), jumpMessageId = null, jumpHighlight = false,
            unreadBoundary = chats[chatId]?.takeIf { captureUnread && it.lastReadInboxId > 0 && it.unreadCount > 0 }
                ?.let { ChatUnreadBoundary(it.lastReadInboxId) },
            historyHasNewer = false, newerLoading = false, newerError = null,
        )
        restoreComposerContext()
    }

    private fun rememberComposerContext() {
        val chatId = snapshot.token.chatId ?: return
        val pending = pendingComposerContext?.takeIf { it.chatId == chatId }
        val reply = snapshot.replyTarget ?: pending?.takeIf { it.replyVersion == replyRevision }?.context?.reply
        val quote = if (snapshot.replyTarget != null) snapshot.replyQuote
            else pending?.takeIf { it.replyVersion == replyRevision }?.context?.quote
        val edit = snapshot.editTarget ?: pending?.takeIf { it.editVersion == editRevision }?.context?.edit
        if (reply == null && edit == null) savedComposerContexts.remove(chatId)
        else savedComposerContexts[chatId] = SavedComposerContext(reply, quote, edit,
            if (edit == null) 0 else snapshot.editDraftRevision.takeIf { it > 0 } ?: pending?.context?.editOwner ?: 0)
        pendingComposerContext = null
    }

    private fun restoreComposerContext() {
        val token = snapshot.token
        val chatId = token.chatId ?: return
        val context = savedComposerContexts[chatId] ?: return
        val pending = PendingComposerContext(chatId, context, replyRevision, editRevision)
        pendingComposerContext = pending
        fun resolve(saved: ChatMessage, completed: (ChatMessage) -> Unit) {
            val key = MessageKey(chatId, saved.id)
            if (key in permanentlyDeleted) return
            val request = ++sequence
            safely<List<ChatMessage>>({ session.historyAround(chatId, saved.id, it) }) { result ->
                if (!valid(token) || pendingComposerContext !== pending || key in permanentlyDeleted) return@safely
                val fresh = result.value?.firstOrNull { it.chatId == chatId && it.id == saved.id && it.delivery == MessageDelivery.SENT &&
                    toolMessageAvailable(it, request) }?.let { reconcileToolMessage(it, request) }
                if (errorOf(result) == null && fresh != null) {
                    confirmedDraftMessages[key] = fresh
                    rememberToolMessage(fresh)
                    completed(fresh)
                }
            }
        }
        context.reply?.let { saved -> resolve(saved) { fresh ->
            if (replyRevision == pending.replyVersion && snapshot.replyTarget == null) {
                snapshot = snapshot.copy(replyTarget = fresh,
                    replyQuote = context.quote?.takeIf { it.matches(fresh.text) })
                emit()
            }
        } }
        context.edit?.let { saved -> resolve(saved) { fresh ->
            safely<MessageActions>({ session.messageActions(chatId, fresh.id, it) }) { result ->
                if (!valid(token) || pendingComposerContext !== pending || editRevision != pending.editVersion ||
                    snapshot.editTarget != null || MessageKey(chatId, fresh.id) in permanentlyDeleted) return@safely
                val actions = result.value?.takeIf { errorOf(result) == null } ?: return@safely
                if (actions.canEditText || actions.canEditCaption) {
                    messageActions[fresh.id] = actions
                    editCaption = !actions.canEditText && actions.canEditCaption
                    val submitted = pendingEdits[chatId]?.takeIf { it.messageId == fresh.id && it.owner == context.editOwner }
                    if (submitted != null) editRequest = submitted.request
                    snapshot = snapshot.copy(editTarget = fresh, editDraftRevision = context.editOwner,
                        editPending = submitted != null, editError = null)
                    emit()
                }
            }
        } }
    }

    fun search(token: ChatToken, query: ChatSearchQuery, more: Boolean = false) {
        if (!valid(token) || query.chatId != null && query.chatId !in chats ||
            query.minDate < 0 || query.maxDate < 0 || query.maxDate > 0 && query.maxDate < query.minDate) return
        val current = snapshot.search
        if (more && (query != current.query || current.loading || current.nextOffset.isEmpty())) return
        val request = ++sequence
        searchRequest = request
        overviewRequest = null
        val offset = if (more) current.nextOffset else ""
        snapshot = snapshot.copy(search = current.copy(query = query, loading = true, error = null,
            chats = if (more) current.chats else emptyList(), messages = if (more) current.messages else emptyList(),
            nextOffset = if (more) current.nextOffset else "", revision = request, overviewLoading = false))
        emit()
        if (query.text.isBlank() && query.senderId == null && query.minDate == 0 && query.maxDate == 0 &&
            query.category == ChatSearchCategory.CHATS) {
            searchRequest = null
            snapshot = snapshot.copy(search = snapshot.search.copy(loading = false))
            emit()
            return
        }
        safely<ChatSearchPage>({ session.search(query, offset, it) }) { result ->
            if (!valid(token) || searchRequest != request || snapshot.search.query != query) return@safely
            searchRequest = null
            val page = result.value
            val error = errorOf(result)
            if (error == null && page != null) {
                val foundChats = page.chats.filter { it.id != 0L }
                foundChats.forEach(::rememberToolChat)
                val found = page.messages.filter { (query.chatId == null || it.chatId == query.chatId) && toolMessageAvailable(it, request) }
                    .map { reconcileToolMessage(it, request) }
                found.forEach(::rememberToolMessage)
                snapshot = snapshot.copy(search = snapshot.search.copy(
                    chats = (snapshot.search.chats + foundChats).distinctBy { it.id },
                    messages = (snapshot.search.messages + found).distinctBy { it.chatId to it.id },
                    totalCount = page.totalCount.coerceAtLeast(0), nextOffset = page.nextOffset.takeUnless { it == offset }.orEmpty(),
                    loading = false, error = null))
            } else snapshot = snapshot.copy(search = snapshot.search.copy(loading = false, error = error))
            emit()
        }
    }

    fun searchOverview(token: ChatToken) {
        if (!valid(token) || overviewRequest != null || clearRecentRequest != null) return
        val request = ++sequence
        overviewRequest = request
        searchRequest = null
        snapshot = snapshot.copy(search = snapshot.search.copy(query = ChatSearchQuery(), loading = false,
            overviewLoading = true, error = null))
        emit()
        safely<Pair<List<ChatItem>, List<ChatItem>>>({ session.searchOverview(it) }) { result ->
            if (!valid(token) || overviewRequest != request) return@safely
            overviewRequest = null
            val error = errorOf(result)
            snapshot = snapshot.copy(search = snapshot.search.copy(overviewLoading = false, error = error))
            result.value?.takeIf { error == null }?.let { (frequent, recent) ->
                val foundFrequent = frequent.filter { it.id != 0L }.distinctBy { it.id }
                val foundRecent = recent.filter { it.id != 0L }.distinctBy { it.id }
                (foundFrequent + foundRecent).forEach(::rememberToolChat)
                snapshot = snapshot.copy(search = snapshot.search.copy(frequent = foundFrequent, recent = foundRecent))
            }
            emit()
        }
    }

    /** Called only by the explicit search-history clear action. */
    fun clearRecentSearch(token: ChatToken) {
        if (!valid(token) || clearRecentRequest != null) return
        val request = ++sequence
        clearRecentRequest = request
        recentRevision++
        recentAddedDuringClear.clear()
        overviewRequest = null
        snapshot = snapshot.copy(search = snapshot.search.copy(recentClearing = true, overviewLoading = false, error = null))
        emit()
        safely<Unit>({ session.clearRecentlyFoundChats(it) }) { result ->
            if (!valid(token) || clearRecentRequest != request) return@safely
            clearRecentRequest = null
            val error = errorOf(result)
            snapshot = snapshot.copy(search = snapshot.search.copy(recentClearing = false, error = error,
                recent = if (error == null) snapshot.search.recent.filter { it.id in recentAddedDuringClear } else snapshot.search.recent))
            recentAddedDuringClear.clear()
            emit()
        }
    }

    private fun addRecentSearch(token: ChatToken, chatId: Long) {
        if (!valid(token) || chatId !in chats) return
        val revision = recentRevision
        safely<Unit>({ session.addRecentlyFoundChat(chatId, it) }) { result ->
            if (!valid(token) || recentRevision != revision || errorOf(result) != null) return@safely
            val chat = chats[chatId] ?: return@safely
            if (clearRecentRequest != null) recentAddedDuringClear += chatId
            overviewRequest = null
            snapshot = snapshot.copy(search = snapshot.search.copy(overviewLoading = false,
                recent = (listOf(chat) + snapshot.search.recent.filterNot { it.id == chatId }).take(PAGE_SIZE)))
            emit()
        }
    }

    fun closeSearch(token: ChatToken) {
        if (!valid(token)) return
        searchRequest = null
        overviewRequest = null
        clearRecentRequest = null
        recentRevision++
        recentAddedDuringClear.clear()
        snapshot = snapshot.copy(search = ChatSearchState())
        emit()
    }

    fun selectSearchChat(token: ChatToken, chatId: Long) {
        if (!valid(token) || (snapshot.search.chats + snapshot.search.frequent + snapshot.search.recent).none { it.id == chatId }) return
        resetSelection(chatId)
        runCatching { session.open(chatId) }
        emit()
        loadInitialHistory(snapshot.token)
        loadPinned(snapshot.token)
        addRecentSearch(snapshot.token, chatId)
    }

    fun reportSpamAndLeave(token: ChatToken) {
        if (!valid(token)) return
        val chatId = token.chatId ?: return
        val state = spamState(chatId)
        if (state.pending || state.completed) return
        if (!state.reported && !spamAvailable(chats[chatId])) {
            changeSpam(chatId) { copy(error = ChatError.FORBIDDEN) }
            return
        }
        verifySpamAction(token, when {
            !state.reported -> SpamAction.REPORT
            state.left && state.needsHistoryRemoval && !state.cleared -> SpamAction.CLEAR
            else -> SpamAction.LEAVE
        })
    }

    fun dismissSpamBar(token: ChatToken) {
        if (!valid(token)) return
        val chatId = token.chatId ?: return
        val state = spamState(chatId)
        if (state.pending || state.reported || state.left || (!spamAvailable(chats[chatId]) && state.error == null)) return
        verifySpamAction(token, SpamAction.DISMISS)
    }

    private enum class SpamAction { REPORT, LEAVE, CLEAR, DISMISS }

    private fun spamCapabilityChanged(chatId: Long) {
        spamCapabilityVersions[chatId] = (spamCapabilityVersions[chatId] ?: 0) + 1
    }

    private fun spamAvailable(chat: ChatItem?): Boolean = chat?.canLeave == true &&
        (chat.basicGroupId != null || chat.supergroupId != null) && chat.actionBar?.kind == ChatActionBarKind.REPORT_SPAM &&
        chat.actionBar?.canUnarchive == false

    private fun spamState(chatId: Long) = spamStates[chatId] ?: ChatReportSpamState(chatId = chatId)

    private fun changeSpam(chatId: Long, transform: ChatReportSpamState.() -> ChatReportSpamState) {
        if (deactivated) return
        val previous = spamState(chatId)
        spamStates[chatId] = previous.transform().copy(revision = previous.revision + 1)
        emit()
    }

    private fun finishSpam(chatId: Long, error: ChatError?) = changeSpam(chatId) {
        copy(reportPending = false, leavePending = false, dismissPending = false, clearPending = false, error = error)
    }

    /** The native read and the mutation are separate actor steps so switching chats revokes consent. */
    private fun verifySpamAction(token: ChatToken, action: SpamAction) {
        val chatId = token.chatId ?: return
        val request = ++sequence
        val sourceVersion = spamCapabilityVersions[chatId] ?: 0
        spamRequests[chatId] = request
        changeSpam(chatId) { copy(reportPending = action == SpamAction.REPORT, leavePending = action == SpamAction.LEAVE,
            dismissPending = action == SpamAction.DISMISS, clearPending = action == SpamAction.CLEAR, error = null) }
        safely<ChatReportSpamCapability>({ session.reportSpamCapability(chatId, it) }) { loaded ->
            if (deactivated || spamRequests[chatId] != request) return@safely
            spamRequests.remove(chatId)
            if (!valid(token) || (spamCapabilityVersions[chatId] ?: 0) != sourceVersion) {
                finishSpam(chatId, ChatError.NOT_READY)
                return@safely
            }
            val capability = loaded.value
            if (errorOf(loaded) != null || capability == null) {
                finishSpam(chatId, errorOf(loaded) ?: ChatError.REQUEST_FAILED)
                return@safely
            }
            updateChat(chatId) { copy(actionBar = capability.actionBar, canLeave = capability.canLeave,
                canBeReported = capability.canBeReported) }
            if (action == SpamAction.REPORT) changeSpam(chatId) { copy(needsHistoryRemoval = capability.needsHistoryRemoval) }
            when {
                action == SpamAction.DISMISS && capability.actionBar == null -> finishSpam(chatId, null)
                action == SpamAction.LEAVE && capability.isMember == false -> acceptedSpamLeave(token)
                action == SpamAction.LEAVE && !capability.canLeave -> finishSpam(chatId, ChatError.FORBIDDEN)
                action == SpamAction.CLEAR && (!capability.needsHistoryRemoval || !capability.canDeleteHistory || capability.isMember != false) ->
                    finishSpam(chatId, ChatError.FORBIDDEN)
                (action == SpamAction.REPORT || action == SpamAction.DISMISS) && !spamAvailable(chats[chatId]) ->
                    finishSpam(chatId, ChatError.FORBIDDEN)
                else -> submitSpamAction(token, action)
            }
        }
    }

    private fun submitSpamAction(token: ChatToken, action: SpamAction) {
        if (!valid(token)) return
        val chatId = token.chatId ?: return
        val request = ++sequence
        val sourceVersion = spamCapabilityVersions[chatId] ?: 0
        spamRequests[chatId] = request
        val invoke: ((ChatResult<Unit>) -> Unit) -> Unit = when (action) {
            SpamAction.REPORT -> { callback -> session.reportSpam(chatId, callback) }
            SpamAction.LEAVE -> { callback -> session.leaveChat(chatId, callback) }
            SpamAction.CLEAR -> { callback -> session.clearReportedBasicGroupHistory(chatId, callback) }
            SpamAction.DISMISS -> { callback -> session.removeChatActionBar(chatId, callback) }
        }
        safely(invoke) { result ->
            if (deactivated || spamRequests[chatId] != request) return@safely
            spamRequests.remove(chatId)
            val error = errorOf(result)
            if (error != null) { finishSpam(chatId, error); return@safely }
            when (action) {
                SpamAction.REPORT -> {
                    changeSpam(chatId) { copy(reported = true, reportPending = false, error = null) }
                    if (valid(token)) verifySpamAction(token, SpamAction.LEAVE)
                }
                SpamAction.LEAVE -> acceptedSpamLeave(token)
                SpamAction.CLEAR -> {
                    changeSpam(chatId) { copy(cleared = true, clearPending = false, error = null) }
                    closeCompletedSpam(token)
                }
                SpamAction.DISMISS -> {
                    if ((spamCapabilityVersions[chatId] ?: 0) == sourceVersion) updateChat(chatId) { copy(actionBar = null) }
                    finishSpam(chatId, null)
                }
            }
        }
    }

    private fun acceptedSpamLeave(token: ChatToken) {
        val chatId = token.chatId ?: return
        changeSpam(chatId) { copy(left = true, leavePending = false, error = null) }
        if (valid(token) && spamState(chatId).needsHistoryRemoval && !spamState(chatId).cleared)
            verifySpamAction(token, SpamAction.CLEAR)
        else closeCompletedSpam(token)
    }

    private fun closeCompletedSpam(token: ChatToken) {
        val chatId = token.chatId ?: return
        if (!valid(token) || !spamState(chatId).completed) return
        resetSelection(null)
        emit()
    }

    fun chatListAction(token: ChatToken, chatId: Long, list: ChatListRef, action: ChatListAction, result: (ChatResult<Unit>) -> Unit) {
        val chat = chats[chatId]
        if (!valid(token) || chat == null || list != snapshot.activeList ||
            (chat.positions[list] ?: 0) <= 0 || listActionRequests[chatId]?.first == token) {
            result(ChatResult(error = ChatError.NOT_READY)); return
        }
        val request = token to ++sequence
        val sourceRevision = activeListRevision
        listActionRequests[chatId] = request
        fun currentSource() = valid(token) && snapshot.activeList == list && activeListRevision == sourceRevision
        fun complete(outcome: ChatResult<Unit>) {
            if (listActionRequests[chatId] != request) return
            listActionRequests.remove(chatId)
            result(if (currentSource()) outcome else ChatResult(error = ChatError.NOT_READY))
        }
        when (action) {
            ChatListAction.MARK_UNREAD -> {
                // Protect a newer explicit marker even before its native update is delivered.
                markerRevisions[chatId] = (markerRevisions[chatId] ?: 0) + 1
                safely<Unit>({ session.markUnread(chatId, true, it) }) { outcome ->
                    if (valid(token) && errorOf(outcome) == null) {
                        clearedManualMarkers.remove(chatId)
                        if (chatId == token.chatId) autoReadSuppressed = true
                    }
                    complete(outcome)
                }
            }
            ChatListAction.MARK_READ -> {
                val markerRevision = markerRevisions[chatId] ?: 0
                fun clearMarker(outcome: ChatResult<Unit>) {
                    if (!currentSource() || listActionRequests[chatId] != request) { complete(ChatResult(error = ChatError.NOT_READY)); return }
                    if (errorOf(outcome) != null || (markerRevisions[chatId] ?: 0) != markerRevision) { complete(outcome); return }
                    safely<Unit>({ session.markUnread(chatId, false, it) }) { completed ->
                        if (valid(token) && errorOf(completed) == null && (markerRevisions[chatId] ?: 0) == markerRevision &&
                            chatId == token.chatId) autoReadSuppressed = false
                        complete(completed)
                    }
                }
                val latest = chat.lastMessage?.id?.takeIf { it > 0 }
                if (latest == null) clearMarker(ChatResult(Unit))
                else safely<Unit>({ session.viewMessages(chatId, listOf(latest), true, it) }, ::clearMarker)
            }
            else -> safely<Unit>({ session.performChatListAction(chatId, list, action, it) }, ::complete)
        }
    }

    fun addChatToFolder(token: ChatToken, chatId: Long, folderId: Int, result: (ChatResult<Unit>) -> Unit) {
        val folder = snapshot.folders.firstOrNull { it.id == folderId }
        if (!valid(token) || chatId !in chats || folder == null || folder.isShareable ||
            (chats[chatId]?.positions?.get(snapshot.activeList) ?: 0) <= 0 ||
            listActionRequests[chatId]?.first == token) {
            result(ChatResult(error = if (folder?.isShareable == true) ChatError.FORBIDDEN else ChatError.NOT_READY)); return
        }
        val request = token to ++sequence
        val source = snapshot.activeList
        val sourceRevision = activeListRevision
        listActionRequests[chatId] = request
        safely<Unit>({ session.addChatToFolder(chatId, folderId, it) }) { outcome ->
            if (listActionRequests[chatId] != request) return@safely
            listActionRequests.remove(chatId)
            result(if (valid(token) && snapshot.activeList == source && activeListRevision == sourceRevision) outcome
                else ChatResult(error = ChatError.NOT_READY))
        }
    }

    fun requestMessageLink(token: ChatToken, messageId: Long, result: (ChatResult<String>) -> Unit) {
        val chatId = token.chatId
        fun allowed() = valid(token) && chatId != null && messageId > 0 && messages[messageId]?.let {
            it.chatId == chatId && it.delivery == MessageDelivery.SENT && MessageKey(chatId, messageId) !in permanentlyDeleted
        } == true
        if (!allowed()) { result(ChatResult(error = ChatError.NOT_READY)); return }
        var completed = false
        safely<String>({ session.messageLink(chatId!!, messageId, it) }) { outcome ->
            if (!completed) {
                completed = true
                result(if (allowed()) outcome else ChatResult(error = ChatError.NOT_READY))
            }
        }
    }

    fun loadChatDetails(token: ChatToken) {
        if (!valid(token) || detailsRequest != null) return
        val chat = chats[token.chatId] ?: return
        val request = ++sequence
        detailsRequest = request
        snapshot = snapshot.copy(details = snapshot.details.copy(loading = true, error = null))
        emit()
        safely<ChatDetails>({ session.chatDetails(chat, it) }) { result ->
            if (!valid(token) || detailsRequest != request) return@safely
            detailsRequest = null
            val details = result.value?.takeIf { it.chatId == token.chatId && errorOf(result) == null }
            snapshot = snapshot.copy(details = ChatDetailsState(details,
                error = errorOf(result) ?: if (details == null) ChatError.REQUEST_FAILED else null))
            emit()
        }
    }

    /** The information pane owns these pages independently of history cursors and read receipts. */
    fun loadMediaIndex(token: ChatToken, category: ChatMediaCategory, more: Boolean = false) {
        val chatId = token.chatId ?: return
        if (!valid(token) || chatId !in chats) return
        val current = mediaPages[category] ?: ChatMediaIndexState(chatId = chatId, category = category)
        if (more && (snapshot.mediaIndex.category != category || current.loading || !current.loaded || current.nextOffset.isEmpty())) return
        if (!more) {
            snapshot = snapshot.copy(mediaIndex = current)
            mediaCountErrors.remove(category)
            ensureMediaCounts(token)
        }
        if (current.loading || !more && current.loaded && current.error == null) {
            publishMediaIndex(category)
            emit()
            return
        }
        val from = if (more) current.nextOffset.toLongOrNull()?.takeIf { it > 0 } ?: return else 0L
        val request = ++sequence
        val contentRevision = mediaContentRevision
        mediaPageRequests[category] = request
        mediaPages[category] = current.copy(loading = true, error = null, revision = request)
        publishMediaIndex(category)
        emit()
        safely<ChatSearchPage>({ session.mediaIndex(chatId, category, from, it) }) { result ->
            if (!valid(token) || mediaPageRequests[category] != request) return@safely
            mediaPageRequests.remove(category)
            val pending = mediaPages[category] ?: return@safely
            val page = result.value
            val error = errorOf(result)
            mediaPages[category] = if (error == null && page != null) {
                val found = page.messages.filter {
                    it.chatId == chatId && toolMessageAvailable(it, request) && (mediaContentChanges[it.id] ?: 0) <= request
                }.map { reconcileToolMessage(it, request) }
                found.forEach(::rememberToolMessage)
                pending.copy(
                    messages = ((if (more) pending.messages else emptyList()) + found).distinctBy { it.id },
                    totalCount = page.totalCount.takeIf { it >= 0 && contentRevision == mediaContentRevision },
                    nextOffset = page.nextOffset.toLongOrNull()?.takeIf { it > 0 && it != from }?.toString().orEmpty(),
                    loading = false, loaded = contentRevision == mediaContentRevision, error = null, revision = request,
                )
            } else pending.copy(loading = false, error = error ?: ChatError.REQUEST_FAILED, revision = request)
            // A page from the previous tab can populate its own cache, never change the visible tab.
            publishMediaIndex()
            emit()
        }
    }

    private fun ensureMediaCounts(token: ChatToken) {
        val chatId = token.chatId ?: return
        ChatMediaCategory.entries.forEach { category ->
            if (category in mediaCounts || category in mediaCountRequests || category in mediaCountErrors) return@forEach
            val request = ++sequence
            val contentRevision = mediaContentRevision
            mediaCountRequests[category] = request
            safely<Int>({ session.mediaIndexCount(chatId, category, it) }) { result ->
                if (!valid(token) || mediaCountRequests[category] != request) return@safely
                mediaCountRequests.remove(category)
                if (contentRevision == mediaContentRevision) {
                    val count = result.value?.takeIf { errorOf(result) == null && it >= 0 }
                    if (count != null) mediaCounts[category] = count
                    else mediaCountErrors[category] = errorOf(result) ?: ChatError.REQUEST_FAILED
                }
                publishMediaIndex()
                emit()
            }
        }
    }

    private fun publishMediaIndex(category: ChatMediaCategory = snapshot.mediaIndex.category) {
        val page = mediaPages[category] ?: ChatMediaIndexState(chatId = snapshot.token.chatId, category = category)
        snapshot = snapshot.copy(mediaIndex = page.copy(
            totalCount = mediaCounts[category] ?: page.totalCount,
            counts = mediaCounts.toMap(), countsLoading = mediaCountRequests.keys.toSet(), countErrors = mediaCountErrors.toMap(),
        ))
    }

    private fun updateMediaIndex(event: ChatEvent) {
        if (snapshot.mediaIndex.chatId != snapshot.token.chatId || mediaPages.isEmpty()) return
        val chatId = snapshot.token.chatId
        val eventChatId = when (event) {
            is ChatEvent.Content -> event.chatId
            is ChatEvent.Edited -> event.chatId
            is ChatEvent.ReplyMarkup -> event.chatId
            is ChatEvent.Reactions -> event.chatId
            is ChatEvent.Deleted -> event.chatId
            is ChatEvent.NewMessage -> event.message.chatId
            is ChatEvent.SendSucceeded -> event.message.chatId
            else -> return
        }
        if (eventChatId != chatId) return
        val changedContent = event is ChatEvent.Content
        val removed = (event as? ChatEvent.Deleted)?.messageIds?.toSet().orEmpty()
        val newMessage = event is ChatEvent.NewMessage || event is ChatEvent.SendSucceeded
        if (changedContent || removed.isNotEmpty() || newMessage) {
            mediaContentRevision++
            // Native index membership may change. Do not retain or accept an obsolete server count.
            mediaCounts.clear()
            mediaCountRequests.clear()
            mediaCountErrors.clear()
        }
        mediaPages.replaceAll { _, page ->
            val changedId = (event as? ChatEvent.Content)?.takeIf { it.chatId == chatId }?.messageId
            page.copy(
                messages = page.messages.filterNot { it.id in removed || it.id == changedId }
                    .map { reconcileToolMessage(it, 0) },
                totalCount = if (changedContent || removed.isNotEmpty() || newMessage) null else page.totalCount,
                loaded = page.loaded && !changedContent && removed.isEmpty() && !newMessage,
            )
        }
        publishMediaIndex()
    }

    fun openMediaIndexMessage(token: ChatToken, messageId: Long) {
        val chatId = token.chatId ?: return
        if (!valid(token) || snapshot.mediaIndex.chatId != chatId ||
            snapshot.mediaIndex.messages.none { it.chatId == chatId && it.id == messageId }) return
        openToolMessage(token, chatId, messageId)
    }

    fun loadPinned(token: ChatToken, more: Boolean = false) {
        if (!valid(token) || token.chatId == null || pinnedRequest != null || more && snapshot.pinned.nextFromMessageId == 0L) return
        val request = ++sequence
        pinnedRequest = request
        val from = if (more) snapshot.pinned.nextFromMessageId else 0L
        snapshot = snapshot.copy(pinned = snapshot.pinned.copy(loading = true, error = null))
        emit()
        safely<ChatSearchPage>({ session.pinnedMessages(token.chatId, from, it) }) { result ->
            if (!valid(token) || pinnedRequest != request) return@safely
            pinnedRequest = null
            if (pinnedRefreshPending) {
                pinnedRefreshPending = false
                loadPinned(token)
                return@safely
            }
            val page = result.value
            if (errorOf(result) == null && page != null) {
                val found = page.messages.filter { it.chatId == token.chatId && toolMessageAvailable(it, request) }
                    .map { reconcileToolMessage(it, request) }
                found.forEach(::rememberToolMessage)
                snapshot = snapshot.copy(pinned = ChatPinnedState(
                    messages = ((if (more) snapshot.pinned.messages else emptyList()) + found).distinctBy { it.id },
                    totalCount = page.totalCount.coerceAtLeast(0),
                    nextFromMessageId = page.nextOffset.toLongOrNull()?.takeIf { it > 0 && it != from } ?: 0))
            } else snapshot = snapshot.copy(pinned = snapshot.pinned.copy(loading = false, error = errorOf(result)))
            emit()
        }
    }

    fun openSearchMessage(token: ChatToken, chatId: Long, messageId: Long) {
        if (!valid(token) || snapshot.search.messages.none { it.chatId == chatId && it.id == messageId }) return
        openToolMessage(token, chatId, messageId, addRecent = true)
    }

    fun openPinnedMessage(token: ChatToken, messageId: Long) {
        if (!valid(token) || snapshot.pinned.messages.none { it.id == messageId }) return
        openToolMessage(token, token.chatId ?: return, messageId)
    }

    fun rememberHistoryAnchor(token: ChatToken, messageId: Long) {
        if (!valid(token) || messageForActions(messageId) == null) return
        val key = MessageKey(token.chatId ?: return, messageId)
        lastHistoryAnchors[key.chatId] = messageId
        rememberedHistoryAnchors.remove(key)
        rememberedHistoryAnchors.add(key)
        while (rememberedHistoryAnchors.size > 256) rememberedHistoryAnchors.remove(rememberedHistoryAnchors.first())
    }

    fun loadHistoryAnchor(token: ChatToken, messageId: Long) {
        val chatId = token.chatId ?: return
        if (!valid(token)) return
        val key = MessageKey(chatId, messageId)
        if (messageId <= 0 || chatId !in chats || key in permanentlyDeleted ||
            key !in rememberedHistoryAnchors && lastHistoryAnchors[chatId] != messageId) {
            return navigationFailed()
        }
        openToolMessage(token, chatId, messageId, highlight = false)
    }

    fun openReplyMessage(token: ChatToken, sourceMessageId: Long) {
        if (!valid(token)) return
        val source = messageForActions(sourceMessageId) ?: return navigationFailed()
        val reply = source.replyTo ?: return navigationFailed()
        val target = MessageKey(reply.chatId, reply.messageId)
        if (reply.messageId <= 0 || reply.chatId !in chats || target in permanentlyDeleted) return navigationFailed()
        lastHistoryAnchors[token.chatId]?.let { MessageKey(source.chatId, it) }?.let { anchor ->
            replyNavigation += ReplyNavigation(reply.chatId, anchor)
            while (replyNavigation.size > 32) replyNavigation.removeAt(0)
        }
        openToolMessage(token, reply.chatId, reply.messageId)
    }

    fun openReplyReturn(token: ChatToken, chatId: Long, messageId: Long) {
        if (!valid(token)) return
        val expected = replyNavigation.lastOrNull() ?: return navigationFailed()
        val anchor = MessageKey(chatId, messageId)
        if (expected.destination != token.chatId || expected.origin != anchor ||
            anchor in permanentlyDeleted || chatId !in chats) return navigationFailed()
        replyNavigation.removeAt(replyNavigation.lastIndex)
        openToolMessage(token, chatId, messageId)
    }

    private fun navigationFailed() {
        snapshot = snapshot.copy(historyError = ChatError.REQUEST_FAILED,
            navigationFailureRevision = snapshot.navigationFailureRevision + 1)
        emit()
    }

    fun requestPreviousEditable(token: ChatToken, beforeMessageId: Long?, completed: (ChatResult<Long>) -> Unit) {
        val chatId = token.chatId ?: run { completed(ChatResult(error = ChatError.NOT_READY)); return }
        if (!valid(token) || snapshot.historyLoading || snapshot.historyHasNewer || snapshot.jumpMessageId != null ||
            snapshot.editTarget != null) { completed(ChatResult(error = ChatError.UNSUPPORTED)); return }
        val request = ++sequence
        previousEditableRequest = request
        val candidates = messages.values.filter { it.chatId == chatId && it.outgoing && it.id > 0 &&
            it.delivery == MessageDelivery.SENT && (beforeMessageId == null || it.id < beforeMessageId) }.sortedByDescending { it.id }
        fun next(index: Int) {
            if (!valid(token) || previousEditableRequest != request || snapshot.historyHasNewer || snapshot.jumpMessageId != null) return
            val message = candidates.getOrNull(index)
            if (message == null) { previousEditableRequest = null; completed(ChatResult(error = ChatError.FORBIDDEN)); return }
            if (messageForActions(message.id) == null) { next(index + 1); return }
            safely<MessageActions>({ session.messageActions(chatId, message.id, it) }) { result ->
                if (!valid(token) || previousEditableRequest != request || snapshot.historyHasNewer || snapshot.jumpMessageId != null) return@safely
                val actions = result.value?.takeIf { errorOf(result) == null }
                if (messageForActions(message.id) != null && (actions?.canEditText == true || actions?.canEditCaption == true)) {
                    messageActions[message.id] = checkNotNull(actions)
                    previousEditableRequest = null
                    completed(ChatResult(value = message.id))
                } else if (result.error == ChatError.RATE_LIMITED) {
                    previousEditableRequest = null
                    completed(ChatResult(error = result.error, code = result.code))
                } else next(index + 1)
            }
        }
        next(0)
    }

    fun openHistoryMessage(token: ChatToken, messageId: Long) {
        if (!valid(token) || messageId !in messages) return
        openToolMessage(token, token.chatId ?: return, messageId)
    }

    /** Called only after the notification owner resolves the actual native chat/message identity. */
    fun openNotificationTarget(token: ChatToken, chatId: Long, messageId: Long?) {
        if (!valid(token)) return
        if (chatId !in chats || messageId != null && MessageKey(chatId, messageId) in permanentlyDeleted) {
            navigationFailed(); return
        }
        if (messageId == null) select(token, chatId) else openToolMessage(token, chatId, messageId)
    }

    private fun openToolMessage(token: ChatToken, chatId: Long, messageId: Long, addRecent: Boolean = false,
        highlight: Boolean = true) {
        if (!valid(token) || messageId <= 0 || chatId !in chats || MessageKey(chatId, messageId) in permanentlyDeleted) return
        cancelSendLatestNavigation(token)
        if (chatId != token.chatId) {
            resetSelection(chatId, captureUnread = false)
            runCatching { session.open(chatId) }
            loadPinned(snapshot.token)
        }
        val selection = snapshot.token
        initialUnreadRequest = null
        newerRequest = null
        newerPageRequest = null
        val wasJumping = jumpRequest != null || snapshot.historyLoading && historyRequest == null
        jumpRequest = null
        if (messageId in messages) {
            snapshot = snapshot.copy(jumpMessageId = messageId, jumpRevision = snapshot.jumpRevision + 1, jumpHighlight = highlight,
                historyLoading = if (wasJumping) false else snapshot.historyLoading, newerLoading = false, newerError = null)
            emit()
            if (addRecent) addRecentSearch(selection, chatId)
            return
        }
        val request = ++sequence
        jumpRequest = request
        historyRequest = null
        historyPageRequest = null
        snapshot = snapshot.copy(historyLoading = true, historyError = null, jumpMessageId = null, jumpHighlight = false,
            newerLoading = false, newerError = null)
        emit()
        safely<List<ChatMessage>>({ session.historyAround(chatId, messageId, it) }) { result ->
            if (!valid(selection) || jumpRequest != request) return@safely
            jumpRequest = null
            val error = errorOf(result)
            val found = result.value.orEmpty().filter { it.chatId == chatId && toolMessageAvailable(it, request) }
            if (error == null && found.any { it.id == messageId }) {
                messages.clear()
                historyMessageIds.clear()
                newerCursor = 0
                found.forEach { acceptMessage(it, request, fromHistory = true) }
                historyCursor = found.minOf { it.id }
                snapshot = snapshot.copy(historyLoading = false, historyEnd = false, jumpMessageId = messageId,
                    jumpRevision = snapshot.jumpRevision + 1, jumpHighlight = highlight,
                    historyHasNewer = hasNewerHistory(chatId, newerCursor))
                if (addRecent) addRecentSearch(selection, chatId)
            } else snapshot = snapshot.copy(historyLoading = false, historyError = error ?: ChatError.REQUEST_FAILED)
            emit()
        }
    }

    private fun toolMessageAvailable(message: ChatMessage, request: Long): Boolean {
        val key = MessageKey(message.chatId, message.id)
        return message.id > 0 && message.chatId != 0L && key !in permanentlyDeleted && key !in retired &&
            (inaccessibleSince[key] ?: 0) <= request
    }

    private fun rememberToolChat(chat: ChatItem) {
        chats.putIfAbsent(chat.id, chat)
        chats[chat.id]?.avatar?.let(::rememberFile)
    }

    private fun rememberToolMessage(message: ChatMessage) {
        rememberSender(message)
        rememberReactionSenders(message.reactions)
        message.media?.let(::rememberMedia)
    }

    private fun reconcileToolMessage(message: ChatMessage, request: Long): ChatMessage {
        val key = MessageKey(message.chatId, message.id)
        val override = contentOverrides[message.id].takeIf { message.chatId == snapshot.token.chatId }
        return (override?.applyTo(message) ?: message).copy(
            editDate = maxOf(message.editDate, editedDates[key] ?: 0),
            inlineKeyboard = currentInlineKeyboard(message, request),
            reactions = reactionUpdates[key]?.takeIf { it.first > request }?.second ?: message.reactions)
    }

    private fun currentInlineKeyboard(message: ChatMessage, request: Long? = null): MessageInlineKeyboard? {
        val update = inlineKeyboardUpdates[MessageKey(message.chatId, message.id)]
            ?.takeIf { request == null || it.first > request }
        return if (update == null) message.inlineKeyboard else update.second
    }

    fun loadChats(token: ChatToken) {
        if (!valid(token)) return
        loadChatPage(snapshot.activeList)
    }

    /** Continue each opened list independently, even if the selected conversation changes. */
    private fun loadChatPage(list: ChatListRef) {
        if (deactivated || !snapshot.enabled ||
            list.folderId > 0 && snapshot.folders.none { it.id == list.folderId }) return
        val page = listPages[list] ?: ListPage()
        if (page.request != null || page.end) return
        val request = ++sequence
        listPages[list] = page.copy(request = request, error = null)
        emit()
        safely<ChatLoadResult>({ session.loadChats(list, PAGE_SIZE, it) }) { result ->
            if (deactivated || !snapshot.enabled || listPages[list]?.request != request) return@safely
            val loaded = ListPage(
                end = result.value?.endReached == true && result.error == null,
                error = errorOf(result),
            )
            listPages[list] = loaded
            if (loaded.error == null && !loaded.end) loadChatPage(list) else emit()
        }
    }

    private fun hasNewerHistory(chatId: Long, cursor: Long): Boolean =
        chats[chatId]?.lastMessage?.id?.takeIf { it > 0 }?.let { it > cursor } ?: true

    private fun loadInitialHistory(token: ChatToken) {
        val chatId = token.chatId ?: return
        val boundary = snapshot.unreadBoundary ?: return loadOlder(token)
        if (!valid(token)) return
        val request = ++sequence
        initialUnreadRequest = request
        snapshot = snapshot.copy(historyLoading = true, historyError = null, historyHasNewer = true)
        emit()
        var cursor = boundary.afterMessageId
        var firstPage = true
        var batches = 0
        var pageRequest = 0L
        var context = emptyList<ChatMessage>()
        fun fallback() {
            initialUnreadRequest = null
            snapshot = snapshot.copy(historyLoading = false, historyHasNewer = false)
            loadOlder(token)
        }
        fun nextPage() {
            if (!valid(token) || initialUnreadRequest != request) return
            val from = cursor
            val nativeRequest = ++sequence
            pageRequest = nativeRequest
            batches++
            val initial = firstPage
            firstPage = false
            safely<List<ChatMessage>>({ callback ->
                if (initial) session.unreadHistory(chatId, from, callback) else session.newerHistory(chatId, from, callback)
            }) { result ->
                if (!valid(token) || initialUnreadRequest != request || pageRequest != nativeRequest) return@safely
                if (errorOf(result) != null) { fallback(); return@safely }
                val raw = result.value.orEmpty().filter { it.chatId == chatId && it.id > 0 }
                val found = raw.filter { toolMessageAvailable(it, request) }
                context = (context + found).distinctBy { it.id }.sortedBy { it.id }.takeLast(PAGE_SIZE * 2)
                val firstUnread = context.firstOrNull { it.id > boundary.afterMessageId && !it.outgoing && it.delivery == MessageDelivery.SENT }
                val newest = raw.maxOfOrNull { it.id }
                if (firstUnread != null) {
                    initialUnreadRequest = null
                    messages.clear()
                    historyMessageIds.clear()
                    newerCursor = 0
                    context.forEach { acceptMessage(it, request, fromHistory = true) }
                    if (newest != null) newerCursor = maxOf(newerCursor, newest)
                    historyCursor = context.minOf { it.id }
                    snapshot = snapshot.copy(historyLoading = false, historyEnd = false,
                        unreadBoundary = boundary.copy(firstUnreadMessageId = firstUnread.id), jumpMessageId = firstUnread.id,
                        jumpRevision = snapshot.jumpRevision + 1, jumpHighlight = false,
                        historyHasNewer = hasNewerHistory(chatId, newerCursor))
                    emit()
                } else if (newest != null && newest > from && batches < MAX_HISTORY_BATCHES && hasNewerHistory(chatId, newest)) {
                    cursor = newest
                    nextPage()
                } else fallback()
            }
        }
        nextPage()
    }

    /** Serial native batches fill the next window without crossing an unloaded range. */
    fun loadNewer(token: ChatToken) {
        val chatId = token.chatId ?: return
        if (!valid(token) || !snapshot.historyHasNewer || newerCursor <= 0 || newerRequest != null ||
            historyRequest != null || initialUnreadRequest != null || jumpRequest != null) return
        val request = ++sequence
        newerRequest = request
        snapshot = snapshot.copy(newerLoading = true, newerError = null)
        emit()
        val received = mutableSetOf<Long>()
        var batches = 0
        fun finish(error: ChatError?, hasNewer: Boolean = snapshot.historyHasNewer) {
            newerRequest = null
            newerPageRequest = null
            snapshot = snapshot.copy(newerLoading = false, newerError = error, historyHasNewer = hasNewer)
            emit()
        }
        fun nextPage() {
            if (!valid(token) || newerRequest != request) return
            val from = newerCursor
            val pageRequest = ++sequence
            newerPageRequest = pageRequest
            batches++
            safely<List<ChatMessage>>({ session.newerHistory(chatId, from, it) }) { result ->
                if (!valid(token) || newerRequest != request || newerPageRequest != pageRequest) return@safely
                val error = errorOf(result)
                if (error != null) { finish(error); return@safely }
                val raw = result.value.orEmpty().filter { it.chatId == chatId && it.id > 0 }
                val newest = raw.maxOfOrNull { it.id }
                if (newest == null || newest <= from) {
                    val knownNewest = chats[chatId]?.lastMessage?.id?.takeIf { it > 0 }
                    val gap = knownNewest != null && knownNewest > from
                    finish(if (gap) ChatError.REQUEST_FAILED else null, hasNewer = gap)
                    return@safely
                }
                raw.filter { toolMessageAvailable(it, request) }.forEach { message ->
                    if (message.id !in messages) acceptMessage(message, request, fromHistory = true)
                    if (message.id in messages) {
                        historyMessageIds += message.id
                        if (message.id > from) received += message.id
                    }
                }
                newerCursor = maxOf(newerCursor, newest)
                val gap = hasNewerHistory(chatId, newerCursor)
                if (!gap) { finish(null, hasNewer = false); return@safely }
                if (received.size >= PAGE_SIZE - 1) { finish(null, hasNewer = true); return@safely }
                if (batches >= MAX_HISTORY_BATCHES) { finish(ChatError.REQUEST_FAILED, hasNewer = true); return@safely }
                emit()
                nextPage()
            }
        }
        nextPage()
    }

    fun loadOlder(token: ChatToken) {
        val chatId = token.chatId ?: return
        if (valid(token) && retrySendLatestWindow) {
            loadLatestAfterSend(token)
            return
        }
        if (!valid(token) || historyRequest != null || jumpRequest != null || initialUnreadRequest != null ||
            newerRequest != null || snapshot.historyEnd) return
        val request = ++sequence
        historyRequest = request
        historyRemoved.clear()
        snapshot = snapshot.copy(historyLoading = true, historyError = null)
        emit()
        val received = mutableSetOf<Long>()
        var batches = 0
        fun finish(error: ChatError?, end: Boolean = false) {
            historyRequest = null
            historyPageRequest = null
            historyRemoved.clear()
            snapshot = snapshot.copy(historyLoading = false, historyEnd = end, historyError = error)
            emit()
        }
        fun nextPage() {
            if (!valid(token) || historyRequest != request) return
            val cursor = historyCursor
            val pageRequest = ++sequence
            historyPageRequest = pageRequest
            batches++
            safely<List<ChatMessage>>({ session.history(chatId, cursor, (PAGE_SIZE - received.size).coerceAtLeast(1), it) }) { result ->
                if (!valid(token) || historyRequest != request || historyPageRequest != pageRequest) return@safely
                val error = errorOf(result)
                if (error != null) { finish(error); return@safely }
                val raw = result.value.orEmpty()
                if (raw.isEmpty()) { finish(null, end = true); return@safely }
                val page = raw.filter { it.chatId == chatId && it.id > 0 }
                val oldest = page.minOfOrNull { it.id }
                val advanced = oldest != null && (cursor == 0L || oldest < cursor)
                if (!advanced) { finish(ChatError.REQUEST_FAILED); return@safely }
                historyCursor = checkNotNull(oldest)
                page.forEach { message ->
                    if (message.id !in messages && message.id !in historyRemoved) acceptMessage(message, request, fromHistory = true)
                    if (message.id in messages && message.id !in historyRemoved) {
                        historyMessageIds += message.id
                        received += message.id
                    }
                }
                if (received.size >= PAGE_SIZE) { finish(null); return@safely }
                if (batches >= MAX_HISTORY_BATCHES) { finish(ChatError.REQUEST_FAILED); return@safely }
                // TDLib may return one cached message first. Publish every partial batch,
                // then keep serially filling this user request without treating short pages as EOF.
                emit()
                nextPage()
            }
        }
        nextPage()
    }

    fun sendText(token: ChatToken, text: String) = sendText(token, RichMessageText(text))

    fun sendText(token: ChatToken, content: RichMessageText, draftRevision: Long = 0) {
        val chatId = token.chatId ?: return
        if (!valid(token) || sendRequest != null || activeTextBatch != null) return
        if (content.text.length > MAX_TEXT_BATCH_UNITS) return sendError(chatId, ChatError.TEXT_TOO_LONG)
        val formatted = content.withMarkdownFormatting()
        val text = formatted.text
        val invalid = when {
            chats[chatId]?.canSend != true -> ChatError.FORBIDDEN
            text.isBlank() -> ChatError.EMPTY_TEXT
            else -> null
        }
        if (invalid != null) {
            snapshot = snapshot.copy(sendError = invalid, sendErrorChatId = chatId)
            emit()
            return
        }
        val existing = textBatches[chatId]?.takeIf { it.original == content && it.draftRevision == draftRevision &&
            it.replyOwner == (replyOwners[chatId] ?: 0L) }
        if (existing != null) {
            activeTextBatch = existing
            existing.attemptStartPart = existing.nextPart
            existing.navigationRevision = sendLatestNavigationRevision
            submitTextPart(token, existing)
            return
        }
        textBatches.remove(chatId)
        if (textBatches.size >= MAX_TEXT_BATCHES || textBatches.values.sumOf { it.original.text.length } + content.text.length > MAX_TEXT_BATCH_TOTAL_UNITS) {
            return sendError(chatId, ChatError.NOT_READY)
        }
        val replyId = snapshot.replyTarget?.id
        if (replyId != null && replyableMessage(replyId) == null) return sendError(chatId, ChatError.UNSUPPORTED)
        val quote = snapshot.replyQuote
        if (quote != null && (replyId == null || !quote.matches(replyableMessage(replyId)?.text.orEmpty())))
            return sendError(chatId, ChatError.INVALID_QUOTE)
        val batch = TextSendBatch(chatId, content, draftRevision, replyId?.let { OutgoingReply(it, quote) },
            replyOwners[chatId] ?: 0L, sendLatestNavigationRevision)
        activeTextBatch = batch
        val knownLimit = session.knownMessageTextLimit
        if (knownLimit != null) {
            prepareTextParts(token, batch, formatted, knownLimit)
            return
        }
        val request = ++sequence
        sendRequest = request
        snapshot = snapshot.copy(sendPending = true, sendError = null, sendErrorChatId = chatId)
        emit()
        safely<Int>({ session.messageTextLimit(it) }) { result ->
            if (sendRequest != request || activeTextBatch !== batch) return@safely
            sendRequest = null
            val limit = result.value?.takeIf { it in 1..65_536 }
            if (!valid(token) || result.error != null || limit == null) {
                activeTextBatch = null
                snapshot = snapshot.copy(sendPending = false, sendError = if (valid(token)) result.error ?: ChatError.REQUEST_FAILED else null)
                emit()
                return@safely
            }
            prepareTextParts(token, batch, formatted, limit)
        }
    }

    private fun prepareTextParts(token: ChatToken, batch: TextSendBatch, formatted: RichMessageText, limit: Int) {
        val count = formatted.text.codePointCount(0, formatted.text.length)
        val withinBudget = limit in 1..65_536 && (count.toLong() + limit - 1) / limit <= MAX_TEXT_BATCH_PARTS
        val parts = if (withinBudget) splitMessageText(formatted, limit).filter { it.text.isNotBlank() } else emptyList()
        if (parts.isEmpty() || parts.size > MAX_TEXT_BATCH_PARTS) {
            activeTextBatch = null
            snapshot = snapshot.copy(sendPending = false)
            return sendError(batch.chatId, ChatError.TEXT_TOO_LONG)
        }
        batch.parts = parts
        textBatches[batch.chatId] = batch
        submitTextPart(token, batch)
    }

    /** Native acceptance advances only this batch; explicit retry starts at its first unaccepted part. */
    private fun submitTextPart(token: ChatToken, batch: TextSendBatch) {
        if (!valid(token) || token.chatId != batch.chatId || activeTextBatch !== batch || batch.nextPart !in batch.parts.indices) {
            if (activeTextBatch === batch) activeTextBatch = null
            return
        }
        val part = batch.parts[batch.nextPart]
        val last = batch.nextPart == batch.parts.lastIndex
        send(token, batch.original, announceAcceptance = last, clearReplyOnAcceptance = last,
            announceAttempt = batch.nextPart == batch.attemptStartPart,
            latestNavigationRevision = batch.navigationRevision,
            capturedReply = CapturedReply(batch.reply, batch.replyOwner), completed = { result ->
                if (activeTextBatch !== batch) return@send
                if (result.error != null) {
                    activeTextBatch = null
                    if (batch.nextPart == 0) textBatches.remove(batch.chatId)
                    if (snapshot.sendPending) { snapshot = snapshot.copy(sendPending = false); emit() }
                    return@send
                }
                batch.nextPart++
                if (last) {
                    textBatches.remove(batch.chatId)
                    activeTextBatch = null
                } else if (valid(token) && isAccountAllowed()) submitTextPart(token, batch)
                else {
                    activeTextBatch = null
                    snapshot = snapshot.copy(sendPending = false)
                    emit()
                }
            }) { reply, callback -> session.sendRichText(batch.chatId, part, reply, callback) }
    }

    fun sendPhoto(token: ChatToken, path: String, caption: String) = sendPhoto(token, path, RichMessageText(caption))

    fun sendPhoto(token: ChatToken, path: String, content: RichMessageText) {
        sendPhotoContent(token, path, content, null)
    }

    fun sendOwnedPhoto(token: ChatToken, path: String, content: RichMessageText, owned: PendingAttachmentLease) {
        sendPhotoContent(token, path, content, owned)
    }

    fun submitClipboardPhoto(token: ChatToken, path: String, content: RichMessageText, owned: PendingAttachmentLease?,
        completed: (ChatResult<Unit>) -> Unit) = sendPhotoContent(token, path, content, owned, completed)

    private fun sendPhotoContent(token: ChatToken, path: String, content: RichMessageText, owned: PendingAttachmentLease?,
        completed: ((ChatResult<Unit>) -> Unit)? = null) {
        val caption = content.withMarkdownFormatting()
        val chatId = token.chatId ?: run { owned?.release(); completed?.invoke(ChatResult(error = ChatError.NOT_READY)); return }
        if (!valid(token) || sendRequest != null) {
            owned?.release(); completed?.invoke(ChatResult(error = ChatError.NOT_READY)); return
        }
        val error = when {
            chats[chatId]?.canSendPhotos != true -> ChatError.FORBIDDEN
            path.isBlank() -> ChatError.REQUEST_FAILED
            caption.text.codePointCount(0, caption.text.length) > MAX_CAPTION_LENGTH -> ChatError.TEXT_TOO_LONG
            else -> null
        }
        if (error != null) {
            owned?.release(); completed?.invoke(ChatResult(error = error)); return sendError(chatId, error)
        }
        // Media requests must never clear the unrelated text composer draft.
        send(token, null, owned, completed) { reply, callback -> session.sendPhotoReply(chatId, path, caption, reply, callback) }
    }

    fun sendVoiceNote(token: ChatToken, draft: VoiceNoteDraft, owned: PendingAttachmentLease) {
        val chatId = token.chatId ?: run { owned.release(); return }
        if (!valid(token) || sendRequest != null) { owned.release(); return }
        val error = when {
            chats[chatId]?.canSendVoiceNotes != true -> ChatError.FORBIDDEN
            draft.path.isBlank() || '\u0000' in draft.path || draft.size !in 1..8_000_000 ||
                draft.durationSeconds !in 1..300 || draft.waveform.size > 63 -> ChatError.REQUEST_FAILED
            else -> null
        }
        if (error != null) { owned.release(); return sendError(chatId, error) }
        send(token, null, owned) { reply, callback -> session.sendVoiceNoteReply(chatId, draft, reply, callback) }
    }

    fun sendSticker(token: ChatToken, sticker: StickerItem) {
        val chatId = token.chatId ?: return
        if (!valid(token) || sendRequest != null) return
        val known = stickerCatalog[sticker.media.file.id]?.takeIf { native ->
            snapshot.stickerSections.any { section ->
                section.loaded && section.stickers.any { current ->
                    current.id == native.id && current.setId == native.setId && current.media.file.id == native.media.file.id
                }
            }
        }
        val error = when {
            chats[chatId]?.canSendStickers != true -> ChatError.FORBIDDEN
            known == null || known.id != sticker.id || known.setId != sticker.setId -> ChatError.UNSUPPORTED
            else -> null
        }
        if (error != null) return sendError(chatId, error)
        send(token, null) { reply, callback -> session.sendStickerReply(chatId, checkNotNull(known), reply, callback) }
    }

    fun sendAttachment(token: ChatToken, attachment: LocalAttachment, caption: String) = sendAttachment(token, attachment, RichMessageText(caption))

    fun sendAttachment(token: ChatToken, attachment: LocalAttachment, content: RichMessageText) {
        sendAttachmentContent(token, attachment, content, null)
    }

    fun sendOwnedAttachment(token: ChatToken, attachment: LocalAttachment, content: RichMessageText, owned: PendingAttachmentLease?) {
        sendAttachmentContent(token, attachment, content, null, owned)
    }

    fun submitClipboardFile(token: ChatToken, attachment: LocalAttachment, content: RichMessageText,
        completed: (ChatResult<Unit>) -> Unit) = sendAttachmentContent(token, attachment, content, completed)

    fun submitClipboardFile(token: ChatToken, attachment: LocalAttachment, content: RichMessageText,
        owned: PendingAttachmentLease?, completed: (ChatResult<Unit>) -> Unit) =
        sendAttachmentContent(token, attachment, content, completed, owned)

    private fun sendAttachmentContent(token: ChatToken, attachment: LocalAttachment, content: RichMessageText,
        completed: ((ChatResult<Unit>) -> Unit)?, owned: PendingAttachmentLease? = null) {
        val caption = content.withMarkdownFormatting()
        val chatId = token.chatId ?: run { owned?.release(); completed?.invoke(ChatResult(error = ChatError.NOT_READY)); return }
        if (!valid(token) || sendRequest != null) { owned?.release(); completed?.invoke(ChatResult(error = ChatError.NOT_READY)); return }
        val kind = when (attachment.kind) {
            AttachmentKind.VIDEO -> MessageKind.VIDEO
            AttachmentKind.FILE -> MessageKind.FILE
        }
        val error = when {
            !canSendMessage(chatId, kind) -> ChatError.FORBIDDEN
            attachment.path.isBlank() || '\u0000' in attachment.path || attachment.fileName.isBlank() ||
                attachment.size !in 1..4_000_000_000L || attachment.durationSeconds < 0 || attachment.width < 0 || attachment.height < 0 ||
                attachment.kind == AttachmentKind.VIDEO && (attachment.durationSeconds == 0 || attachment.width !in 1..16_384 || attachment.height !in 1..16_384) -> ChatError.REQUEST_FAILED
            caption.text.codePointCount(0, caption.text.length) > MAX_CAPTION_LENGTH -> ChatError.TEXT_TOO_LONG
            else -> null
        }
        if (error != null) { owned?.release(); completed?.invoke(ChatResult(error = error)); return sendError(chatId, error) }
        send(token, null, owned, completed) { reply, callback -> session.sendAttachmentReply(chatId, attachment, caption, reply, callback) }
    }

    fun setReply(token: ChatToken, messageId: Long?) {
        if (!valid(token)) return
        if (messageId == null) clearReply()
        else {
            val message = replyableMessage(messageId) ?: return
            replyRevision++
            replyOwners[message.chatId] = (replyOwners[message.chatId] ?: 0L) + 1
            snapshot = snapshot.copy(replyTarget = message, replyQuote = null)
        }
        emit()
    }

    fun setQuote(token: ChatToken, messageId: Long, quote: MessageQuote) {
        if (!valid(token)) return
        val message = replyableMessage(messageId) ?: return
        if (!quote.matches(message.text)) return sendError(message.chatId, ChatError.INVALID_QUOTE)
        val content = message.richText().slice(quote.position, quote.position + quote.content.text.length) ?: return
        replyRevision++
        replyOwners[message.chatId] = (replyOwners[message.chatId] ?: 0L) + 1
        snapshot = snapshot.copy(replyTarget = message, replyQuote = MessageQuote(quote.position, content), sendError = null)
        emit()
    }

    private fun replyableMessage(messageId: Long): ChatMessage? =
        (messages[messageId] ?: confirmedDraftMessages[MessageKey(snapshot.token.chatId ?: 0, messageId)])?.takeIf {
        it.id > 0 && it.chatId == snapshot.token.chatId && it.delivery == MessageDelivery.SENT &&
            MessageKey(it.chatId, it.id) !in permanentlyDeleted
    }

    private fun messageForActions(messageId: Long): ChatMessage? = messages[messageId]?.takeIf {
        it.id > 0 && it.chatId == snapshot.token.chatId && MessageKey(it.chatId, it.id) !in permanentlyDeleted
    }

    private fun clearReply(changeOwner: Boolean = true) {
        if (changeOwner) snapshot.token.chatId?.let { replyOwners[it] = (replyOwners[it] ?: 0L) + 1 }
        replyRevision++
        snapshot = snapshot.copy(replyTarget = null, replyQuote = null)
    }

    private fun consumeAcceptedReply(chatId: Long, reply: OutgoingReply?, owner: Long) {
        if (reply == null || (replyOwners[chatId] ?: 0L) != owner) return
        replyOwners[chatId] = owner + 1
        savedComposerContexts[chatId]?.let { saved ->
            if (saved.reply?.id == reply.messageId && saved.quote == reply.quote) {
                if (saved.edit == null) savedComposerContexts.remove(chatId)
                else savedComposerContexts[chatId] = saved.copy(reply = null, quote = null)
            }
        }
        if (snapshot.token.chatId == chatId) {
            if (snapshot.replyTarget?.id == reply.messageId && snapshot.replyQuote == reply.quote ||
                pendingComposerContext?.takeIf { it.chatId == chatId }?.context?.reply?.id == reply.messageId) {
                clearReply(changeOwner = false)
            }
        }
    }

    private fun send(token: ChatToken, acceptedText: RichMessageText?, owned: PendingAttachmentLease? = null,
        completed: ((ChatResult<Unit>) -> Unit)? = null,
        announceAcceptance: Boolean = true, clearReplyOnAcceptance: Boolean = true, announceAttempt: Boolean = true,
        latestNavigationRevision: Long = sendLatestNavigationRevision,
        capturedReply: CapturedReply? = null,
        requestBlock: (OutgoingReply?, (ChatResult<ChatMessage>) -> Unit) -> Unit) {
        val chatId = token.chatId ?: return
        val replyId = if (capturedReply != null) capturedReply.reply?.messageId else snapshot.replyTarget?.id
        if (replyId != null && (if (capturedReply == null) replyableMessage(replyId) == null
                else MessageKey(chatId, replyId) in permanentlyDeleted)) {
            owned?.release(); completed?.invoke(ChatResult(error = ChatError.UNSUPPORTED)); return sendError(chatId, ChatError.UNSUPPORTED)
        }
        val quote = if (capturedReply != null) capturedReply.reply?.quote else snapshot.replyQuote
        if (quote != null && (replyId == null || capturedReply == null && !quote.matches(replyableMessage(replyId)?.text.orEmpty()))) {
            owned?.release(); completed?.invoke(ChatResult(error = ChatError.INVALID_QUOTE)); return sendError(chatId, ChatError.INVALID_QUOTE)
        }
        val reply = replyId?.let { OutgoingReply(it, quote) }
        val capturedReplyOwner = capturedReply?.owner ?: (replyOwners[chatId] ?: 0L)
        val request = ++sequence
        sendRequest = request
        snapshot = snapshot.copy(sendPending = true, sendError = null, sendErrorChatId = chatId,
            sendAttemptRevision = snapshot.sendAttemptRevision + if (announceAttempt) 1 else 0)
        emit()
        var callbackHandled = false
        safely<ChatMessage>({ callback -> requestBlock(reply, callback) }) { result ->
            if (callbackHandled) return@safely
            callbackHandled = true
            if (sendRequest != request) { owned?.release(); completed?.invoke(ChatResult(error = ChatError.NOT_READY)); return@safely }
            sendRequest = null
            val error = errorOf(result).let {
                if (it == null && result.value?.chatId != chatId) ChatError.REQUEST_FAILED else it
            }
            if (error == null) {
                owned?.let { lease ->
                    trackOwnedAttachment(checkNotNull(result.value), lease)
                }
                if (clearReplyOnAcceptance) consumeAcceptedReply(chatId, reply, capturedReplyOwner)
                val accepted = acknowledgedMessage(checkNotNull(result.value)) ?: result.value
                if (announceAcceptance && valid(token) && isAccountAllowed() && latestNavigationRevision == sendLatestNavigationRevision &&
                    (snapshot.historyHasNewer || initialUnreadRequest != null)) {
                    loadLatestAfterSend(token, MessageKey(chatId, checkNotNull(accepted).id))
                }
                acceptMessage(checkNotNull(accepted).copy(sendAnimationId = request), request)
            } else owned?.release()
            snapshot = snapshot.copy(
                sendPending = error == null && !announceAcceptance, sendError = error, sendErrorChatId = chatId,
                acceptedSendId = snapshot.acceptedSendId + if (error == null && announceAcceptance) 1 else 0,
                acceptedSendChatId = if (error == null && announceAcceptance) chatId else snapshot.acceptedSendChatId,
                acceptedSendText = if (error == null && announceAcceptance) acceptedText?.text else snapshot.acceptedSendText,
                acceptedSendEntities = if (error == null && announceAcceptance) acceptedText?.entities.orEmpty() else snapshot.acceptedSendEntities,
            )
            emit()
            completed?.invoke(if (error == null) ChatResult(Unit) else ChatResult(error = error, code = result.code))
        }
    }

    private fun sendError(chatId: Long, error: ChatError) {
        snapshot = snapshot.copy(sendError = error, sendErrorChatId = chatId,
            sendAttemptRevision = snapshot.sendAttemptRevision + 1)
        emit()
    }

    private fun trackOwnedAttachment(message: ChatMessage, lease: PendingAttachmentLease, visited: Set<MessageKey> = emptySet()) {
        val key = MessageKey(message.chatId, message.id)
        if (key in visited) {
            // A malformed identity cycle cannot prove that the local input is no longer read.
            ownedAttachmentSends.put(key, lease)?.release()
            return
        }
        val retryTargets = ownedAttachmentRetryTargets[key]
        if (!retryTargets.isNullOrEmpty()) {
            val handles = retryTargets.mapIndexed { index, _ -> if (index == 0) lease else lease.retain() }
            retryTargets.forEachIndexed { index, replacement -> handles[index]?.let {
                trackOwnedAttachment(replacement, it, visited + key)
            } }
            return
        }
        val target = replacementTargets[key]
        if (target != null && target != key) {
            val replacement = terminalMessages[target] ?: message.copy(id = target.messageId)
            trackOwnedAttachment(replacement, lease, visited + key)
            return
        }
        val current = terminalMessages[key] ?: message
        when {
            key in permanentlyDeleted || current.delivery == MessageDelivery.SENT ||
                current.delivery == MessageDelivery.FAILED && !current.canRetry -> lease.release()
            else -> ownedAttachmentSends.put(key, lease)?.release()
        }
    }

    fun loadReadDetails(token: ChatToken, messageId: Long) {
        val chatId = token.chatId ?: return
        if (!valid(token) || replyableMessage(messageId) == null) return
        if (snapshot.readDetailsMessageId == messageId && readDetailsRequest != null) return
        val request = ++sequence
        readDetailsRequest = request
        snapshot = snapshot.copy(readDetailsMessageId = messageId, readDetails = null,
            readDetailsLoading = true, readDetailsError = null)
        emit()
        safely<MessageReadDetails>({ session.messageReadDetails(chatId, messageId, it) }) { result ->
            if (!valid(token) || readDetailsRequest != request || snapshot.readDetailsMessageId != messageId || messageId !in messages) return@safely
            readDetailsRequest = null
            val error = errorOf(result)
            val details = result.value?.takeIf { error == null }?.let { it.copy(readers = it.readers.toList()) }
            details?.readers?.forEach { it.avatar?.let(::rememberFile) }
            snapshot = snapshot.copy(readDetails = details, readDetailsLoading = false, readDetailsError = error)
            emit()
        }
    }

    fun closeReadDetails(token: ChatToken) {
        if (!valid(token)) return
        clearReadDetails()
        emit()
    }

    private fun clearReadDetails() {
        readDetailsRequest = null
        snapshot = snapshot.copy(readDetailsMessageId = null, readDetails = null,
            readDetailsLoading = false, readDetailsError = null)
    }

    fun loadMessageActions(token: ChatToken, messageId: Long) {
        val chatId = token.chatId ?: return
        if (!valid(token) || messageForActions(messageId) == null) return
        if (snapshot.actionsMessageId == messageId && actionsRequest != null) return
        val request = ++sequence
        actionsRequest = request
        snapshot = snapshot.copy(actionsMessageId = messageId, actionsLoading = true, actionsError = null, messageActions = null)
        emit()
        safely<MessageActions>({ session.messageActions(chatId, messageId, it) }) { result ->
            if (!valid(token) || actionsRequest != request || snapshot.actionsMessageId != messageId || messageId !in messages) return@safely
            actionsRequest = null
            val error = errorOf(result)
            val actions = result.value.takeIf { error == null }
            if (actions != null) messageActions[messageId] = actions else messageActions.remove(messageId)
            snapshot = snapshot.copy(actionsLoading = false, actionsError = error, messageActions = actions)
            emit()
        }
    }

    fun closeMessageActions(token: ChatToken) {
        if (!valid(token)) return
        clearMessageActions()
        emit()
    }

    private fun clearMessageActions() {
        actionsRequest = null
        snapshot = snapshot.copy(actionsMessageId = null, actionsLoading = false, actionsError = null, messageActions = null)
    }

    fun loadReactions(token: ChatToken, messageId: Long) {
        val chatId = token.chatId ?: return
        if (!valid(token) || replyableMessage(messageId) == null) return
        if (snapshot.reactionMessageId == messageId && reactionsRequest != null) return
        if (snapshot.reactionMessageId != messageId) {
            reactionVisualErrors.clear()
            clearReactions()
        }
        val request = ++sequence
        reactionsRequest = request
        snapshot = snapshot.copy(reactionMessageId = messageId,
            reactionOptions = snapshot.reactionOptions.takeIf { snapshot.reactionMessageId == messageId }, reactionsLoading = true,
            reactionError = null, reactionErrorMessageId = messageId)
        emit()
        safely<MessageReactionOptions>({ session.availableReactions(chatId, messageId, it) }) { result ->
            if (!valid(token) || reactionsRequest != request || snapshot.reactionMessageId != messageId || messageId !in messages) return@safely
            reactionsRequest = null
            val error = errorOf(result)
            snapshot = snapshot.copy(reactionOptions = result.value.takeIf { error == null }, reactionsLoading = false,
                reactionError = error, reactionErrorMessageId = messageId.takeIf { error != null })
            emit()
            if (reactionOptionsDirty) {
                reactionOptionsDirty = false
                loadReactions(snapshot.token, messageId)
            }
        }
    }

    fun closeReactions(token: ChatToken) {
        if (!valid(token)) return
        clearReactions()
        emit()
    }

    private fun clearReactions() {
        reactionsRequest = null
        reactionOptionsDirty = false
        reactionVisualRequests.clear()
        reactionPacksRequest = null
        reactionPackRequest = null
        reactionGroupRequest = null
        snapshot = snapshot.copy(reactionMessageId = null, reactionOptions = null, reactionsLoading = false,
            reactionPacks = emptyList(), reactionPackEmojis = emptyList(), reactionPackId = null, reactionPacksLoading = false,
            reactionGroupSourceId = null, reactionGroupPack = null, reactionGroupLoading = false, reactionGroupError = null)
    }

    private fun refreshReactionOptions() {
        val messageId = snapshot.reactionMessageId ?: return
        if (reactionsRequest != null) reactionOptionsDirty = true else loadReactions(snapshot.token, messageId)
    }

    fun loadReactionVisual(token: ChatToken, key: ReactionKey) {
        if (!valid(token) || !key.valid || !knownReaction(key) || key in reactionVisuals ||
            key in reactionVisualRequests || key in reactionVisualErrors || reactionVisualRequests.size >= 64) return
        val request = ++sequence
        reactionVisualRequests[key] = request
        emit()
        safely<ReactionVisual>({ session.reactionVisual(key, it) }) { result ->
            if (!valid(token) || reactionVisualRequests[key] != request) return@safely
            reactionVisualRequests.remove(key)
            val visual = result.value?.takeIf { result.error == null && it.key == key && knownReaction(key) }
            if (visual != null) {
                listOfNotNull(visual.staticIcon, visual.selectAnimation, visual.appearAnimation,
                    visual.centerAnimation, visual.aroundAnimation).forEach(::rememberMedia)
                reactionVisuals[key] = visual
                while (reactionVisuals.size > 96) reactionVisuals.remove(reactionVisuals.keys.first())
            } else {
                reactionVisualErrors += key
                while (reactionVisualErrors.size > 96) reactionVisualErrors.remove(reactionVisualErrors.first())
            }
            emit()
        }
    }

    private fun knownReaction(key: ReactionKey): Boolean =
        snapshot.reactionOptions?.allChoices()?.any { it.key == key } == true ||
            snapshot.reactionPackEmojis.any { it.key == key } || messages.values.any { message ->
                message.reactions.any { it.key == key && !it.isPaid } ||
                    key.customEmojiId != 0L && message.richText().validEntities().any {
                        it.kind == MessageEntityKind.CUSTOM_EMOJI && it.id == key.customEmojiId
                    }
            }

    fun loadReactionPacks(token: ChatToken, setId: Long?) {
        val chatId = token.chatId ?: return
        val messageId = snapshot.reactionMessageId ?: return
        if (!valid(token)) return
        if (setId == null) {
            if (reactionPacksRequest != null || snapshot.reactionPacks.isNotEmpty()) return
            val request = ++sequence
            reactionPacksRequest = request
            snapshot = snapshot.copy(reactionPacksLoading = true)
            emit()
            safely<List<StickerPack>>({ session.reactionPacks(chatId, it) }) { result ->
                if (!valid(token) || reactionPacksRequest != request || snapshot.reactionMessageId != messageId) return@safely
                reactionPacksRequest = null
                snapshot = snapshot.copy(reactionPacks = result.value.orEmpty().take(100),
                    reactionPacksLoading = reactionPackRequest != null)
                emit()
            }
        } else {
            if (setId !in snapshot.reactionPacks.map { it.id } || snapshot.reactionPackId == setId && reactionPackRequest != null) return
            val request = ++sequence
            reactionGroupRequest = null
            reactionPackRequest = request
            snapshot = snapshot.copy(reactionPackId = setId, reactionPackEmojis = emptyList(), reactionPacksLoading = true,
                reactionGroupSourceId = null, reactionGroupPack = null, reactionGroupLoading = false, reactionGroupError = null)
            emit()
            safely<List<ReactionChoice>>({ session.reactionPack(setId, it) }) { result ->
                if (!valid(token) || reactionPackRequest != request || snapshot.reactionPackId != setId || snapshot.reactionMessageId != messageId) return@safely
                reactionPackRequest = null
                snapshot = snapshot.copy(reactionPackEmojis = result.value.orEmpty().filter { it.key.valid }.take(1000), reactionPacksLoading = false)
                emit()
            }
        }
    }

    fun loadReactionGroupPack(token: ChatToken, sourceChatId: Long?) {
        val messageId = snapshot.reactionMessageId ?: return
        if (!valid(token)) return
        if (sourceChatId != null && !knownReactionGroup(sourceChatId)) return
        reactionPackRequest = null
        reactionGroupRequest = null
        snapshot = snapshot.copy(reactionGroupSourceId = sourceChatId, reactionGroupPack = null,
            reactionGroupLoading = sourceChatId != null, reactionGroupError = null,
            reactionPackId = null, reactionPackEmojis = emptyList(), reactionPacksLoading = reactionPacksRequest != null)
        if (sourceChatId == null) { emit(); return }
        val request = ++sequence
        reactionGroupRequest = request
        emit()
        safely<ReactionGroupPack>({ session.reactionGroupPack(sourceChatId, it) }) { result ->
            if (!valid(token) || reactionGroupRequest != request || snapshot.reactionMessageId != messageId ||
                snapshot.reactionGroupSourceId != sourceChatId || !knownReactionGroup(sourceChatId)) return@safely
            reactionGroupRequest = null
            val groupPack = result.value?.takeIf { result.error == null && it.sourceChatId == sourceChatId && it.pack.id != 0L }
            snapshot = snapshot.copy(reactionGroupLoading = false,
                reactionGroupError = if (groupPack == null) result.error ?: ChatError.REQUEST_FAILED else null,
                reactionGroupPack = groupPack?.pack, reactionPackId = groupPack?.pack?.id,
                reactionPackEmojis = groupPack?.choices.orEmpty().filter { it.key.valid && it.key.customEmojiId != 0L }
                    .distinctBy { it.key }.take(1000))
            emit()
        }
    }

    private fun knownReactionGroup(chatId: Long): Boolean = chats[chatId]?.let {
        it.id != snapshot.token.chatId && it.supergroupId != null && !it.isChannel && it.isJoinedGroup
    } == true

    fun setReaction(token: ChatToken, messageId: Long, emoji: String, add: Boolean) =
        setReaction(token, messageId, ReactionKey(emoji), add)

    fun setCustomReaction(token: ChatToken, messageId: Long, customEmojiId: Long, add: Boolean) =
        setReaction(token, messageId, ReactionKey(customEmojiId = customEmojiId), add)

    private fun setReaction(token: ChatToken, messageId: Long, key: ReactionKey, add: Boolean) {
        val chatId = token.chatId ?: return
        if (!valid(token) || snapshot.reactionPending || replyableMessage(messageId) == null || !key.valid) return
        val request = ++sequence
        reactionMutationRequest = request
        reactionMutationMessageId = messageId
        snapshot = snapshot.copy(reactionPending = true, reactionError = null, reactionErrorMessageId = messageId)
        emit()
        safely<Unit>({ callback ->
            if (key.customEmojiId != 0L) session.setCustomReaction(chatId, messageId, key.customEmojiId, add, callback)
            else session.setReaction(chatId, messageId, key.emoji, add, callback)
        }) { result ->
            if (!valid(token) || reactionMutationRequest != request || messageId !in messages) return@safely
            reactionMutationRequest = null
            reactionMutationMessageId = null
            val error = errorOf(result)
            // Counts and chosen state come from UpdateMessageInteractionInfo, never an optimistic toggle.
            snapshot = snapshot.copy(reactionPending = false, reactionError = error,
                reactionErrorMessageId = messageId.takeIf { error != null })
            emit()
        }
    }

    fun startEdit(token: ChatToken, messageId: Long) {
        if (!valid(token)) return
        val message = replyableMessage(messageId) ?: return
        val actions = messageActions[messageId] ?: return
        if (!actions.canEditText && !actions.canEditCaption) return
        clearEdit()
        editCaption = !actions.canEditText && actions.canEditCaption
        // The normal draft keeps its reply while the same field displays the edit draft.
        // Editing never sends that reply; normal selection/deletion rules still invalidate it.
        snapshot = snapshot.copy(editTarget = message, editDraftRevision = ++sequence)
        emit()
    }

    fun cancelEdit(token: ChatToken) {
        if (!valid(token)) return
        clearEdit()
        emit()
    }

    private fun clearEdit() {
        editRevision++
        editRequest = null
        snapshot = snapshot.copy(editTarget = null, editDraftRevision = 0, editPending = false, editError = null)
    }

    fun editMessage(token: ChatToken, text: String) = editMessage(token, RichMessageText(text), preserveUnchanged = true)

    fun editMessage(token: ChatToken, content: RichMessageText, preserveUnchanged: Boolean = false) {
        val chatId = token.chatId ?: return
        val target = snapshot.editTarget ?: return
        val formatted = if (preserveUnchanged || content == target.richText()) content else content.withMarkdownFormatting()
        val text = formatted.text
        if (!valid(token) || editRequest != null || replyableMessage(target.id) == null) return
        val actions = messageActions[target.id]
        val invalid = when {
            (if (editCaption) actions?.canEditCaption else actions?.canEditText) != true -> ChatError.FORBIDDEN
            !editCaption && text.isBlank() -> ChatError.EMPTY_TEXT
            text.codePointCount(0, text.length) > if (editCaption) MAX_CAPTION_LENGTH else MAX_TEXT_LENGTH -> ChatError.TEXT_TOO_LONG
            else -> null
        }
        if (invalid != null) {
            snapshot = snapshot.copy(editError = invalid)
            emit()
            return
        }
        val request = ++sequence
        val key = MessageKey(chatId, target.id)
        val version = contentVersions[key] ?: 0
        val editOwner = snapshot.editDraftRevision
        pendingEdits[chatId] = PendingEdit(target.id, editOwner, request)
        editRequest = request
        snapshot = snapshot.copy(editPending = true, editError = null)
        emit()
        safely<ChatMessage>({
            if (preserveUnchanged) session.editMessage(chatId, target.id, text, editCaption, it)
            else session.editRichMessage(chatId, target.id, formatted, editCaption, it)
        }) { result ->
            if (pendingEdits[chatId]?.request == request) pendingEdits.remove(chatId)
            if (!valid(token) || editRequest != request || snapshot.editTarget?.id != target.id) {
                if (errorOf(result) == null && result.value?.chatId == chatId && result.value.id == target.id) {
                    val saved = savedComposerContexts[chatId]
                    if (saved?.editOwner == editOwner && saved.edit?.id == target.id) {
                        if (saved.reply == null) savedComposerContexts.remove(chatId)
                        else savedComposerContexts[chatId] = saved.copy(edit = null, editOwner = 0)
                    }
                    val pending = pendingComposerContext?.takeIf { it.chatId == chatId &&
                        it.context.editOwner == editOwner && it.context.edit?.id == target.id && it.editVersion == editRevision }
                    if (snapshot.token.chatId == chatId && (snapshot.editDraftRevision == editOwner ||
                            snapshot.editTarget == null && pending != null)) clearEdit()
                    emit()
                } else if (snapshot.token.chatId == chatId && snapshot.editDraftRevision == editOwner &&
                    snapshot.editTarget?.id == target.id && editRequest == request) {
                    editRequest = null
                    snapshot = snapshot.copy(editPending = false, editError = errorOf(result) ?: ChatError.REQUEST_FAILED)
                    emit()
                }
                return@safely
            }
            editRequest = null
            val error = errorOf(result).let {
                if (it == null && (result.value?.chatId != chatId || result.value?.id != target.id)) ChatError.REQUEST_FAILED else it
            }
            if (error == null) {
                val edited = checkNotNull(result.value)
                if ((contentVersions[key] ?: 0) == version && key !in permanentlyDeleted) {
                    contentVersions[key] = ++sequence
                    contentOverrides[target.id] = ContentOverride(edited.text, edited.kind, edited.media, edited.entities,
                        edited.showCaptionAboveMedia, edited.linkPreviewOptions, edited.linkPreview)
                    editedDates[key] = maxOf(edited.editDate, editedDates[key] ?: 0)
                    terminalMessages[key]?.let { terminalMessages[key] = edited }
                    if (target.id in messages) acceptMessage(edited, request)
                    else { confirmedDraftMessages[key] = edited; rememberToolMessage(edited) }
                    refreshResolvedReplies(chatId, target.id, edited.text, edited.entities)
                    updateChat(chatId) { if (lastMessage?.id == target.id) copy(lastMessage = messages[target.id] ?: edited) else this }
                }
                clearEdit()
            } else snapshot = snapshot.copy(editPending = false, editError = error)
            emit()
        }
    }

    fun deleteMessage(token: ChatToken, messageId: Long, revoke: Boolean) {
        val chatId = token.chatId ?: return
        if (!valid(token) || mutationRequest != null || messageForActions(messageId) == null) return
        val actions = messageActions[messageId]
        if ((if (revoke) actions?.canDeleteForAll else actions?.canDeleteForSelf) != true) {
            mutationError(messageId, ChatMutationKind.DELETE, ChatError.FORBIDDEN)
            return
        }
        val request = beginMutation(messageId, ChatMutationKind.DELETE)
        safely<Unit>({ session.deleteMessage(chatId, messageId, revoke, it) }) { result ->
            if (!outstandingMutations.remove(request)) return@safely
            val error = errorOf(result)
            if (error == null) event(ChatEvent.Deleted(chatId, listOf(messageId), permanent = true))
            finishMutation(token, request, error)
        }
    }

    fun forwardMessage(token: ChatToken, messageId: Long, targetChatId: Long) {
        val chatId = token.chatId ?: return
        if (!valid(token) || mutationRequest != null) return
        val message = replyableMessage(messageId) ?: return
        val target = chats[targetChatId]
        val error = when {
            messageActions[messageId]?.canForward != true || chats[chatId]?.hasProtectedContent == true -> ChatError.FORBIDDEN
            target == null || targetChatId == 0L -> ChatError.UNSUPPORTED
            message.kind in setOf(MessageKind.TEXT, MessageKind.ANIMATED_EMOJI, MessageKind.PHOTO, MessageKind.VIDEO, MessageKind.FILE, MessageKind.STICKER) &&
                !canSendMessage(targetChatId, message.kind) -> ChatError.FORBIDDEN
            message.kind !in setOf(MessageKind.TEXT, MessageKind.ANIMATED_EMOJI, MessageKind.PHOTO, MessageKind.VIDEO, MessageKind.FILE, MessageKind.STICKER) &&
                !target.canSend -> ChatError.FORBIDDEN
            else -> null
        }
        if (error != null) return mutationError(messageId, ChatMutationKind.FORWARD, error)
        val previousLastMessage = target?.lastMessage
        val request = beginMutation(messageId, ChatMutationKind.FORWARD)
        safely<List<ChatMessage>>({ session.forwardMessage(chatId, messageId, targetChatId, it) }) { result ->
            if (!outstandingMutations.remove(request)) return@safely
            val forwarded = result.value.orEmpty()
            val failure = errorOf(result).let {
                if (it == null && (forwarded.isEmpty() || forwarded.any { item -> item.chatId != targetChatId })) ChatError.REQUEST_FAILED else it
            }
            if (failure == null) {
                forwarded.forEach { acceptMessage(it, request) }
                val latest = forwarded.mapNotNull(::acknowledgedMessage).maxByOrNull { it.id }
                if (latest != null) updateChat(targetChatId) {
                    if (lastMessage == previousLastMessage || lastMessage?.id == latest.id) copy(lastMessage = latest) else this
                }
            }
            finishMutation(token, request, failure)
        }
    }

    private fun acknowledgedMessage(message: ChatMessage): ChatMessage? {
        var key = MessageKey(message.chatId, message.id)
        val visited = mutableSetOf<MessageKey>()
        while (visited.add(key)) key = replacementTargets[key] ?: break
        if (key in permanentlyDeleted || key in retired) return null
        return terminalMessages[key] ?: message.takeIf { it.id == key.messageId }
    }

    private fun beginMutation(messageId: Long, kind: ChatMutationKind): Long {
        val request = ++sequence
        mutationRequest = request
        outstandingMutations += request
        snapshot = snapshot.copy(mutationMessageId = messageId, mutationKind = kind, mutationPending = true, mutationError = null)
        emit()
        return request
    }

    private fun finishMutation(token: ChatToken, request: Long, error: ChatError?) {
        if (valid(token) && mutationRequest == request) {
            mutationRequest = null
            snapshot = snapshot.copy(mutationPending = false, mutationError = error,
                mutationRevision = snapshot.mutationRevision + if (error == null) 1 else 0)
        }
        emit()
    }

    private fun mutationError(messageId: Long, kind: ChatMutationKind, error: ChatError) {
        snapshot = snapshot.copy(mutationMessageId = messageId, mutationKind = kind, mutationPending = false, mutationError = error)
        emit()
    }

    fun retry(token: ChatToken, messageId: Long) {
        val chatId = token.chatId ?: return
        if (!valid(token)) return
        val key = MessageKey(chatId, messageId)
        if (key in retryRequests) return
        val message = messages[messageId] ?: return
        val error = when {
            !canSendMessage(chatId, message.kind) -> ChatError.FORBIDDEN
            message.delivery != MessageDelivery.FAILED || !message.canRetry -> ChatError.UNSUPPORTED
            nowMillis() < (retryAllowedAt[key] ?: 0) -> ChatError.RATE_LIMITED
            else -> null
        }
        if (error != null) {
            snapshot = snapshot.copy(sendError = error, sendErrorChatId = chatId)
            emit()
            return
        }
        val request = ++sequence
        retryRequests[key] = request
        snapshot = snapshot.copy(sendError = null, sendErrorChatId = chatId)
        emit()
        safely<List<ChatMessage>>({ session.retry(chatId, messageId, it) }) { result ->
            if (retryRequests[key] != request) return@safely
            retryRequests.remove(key)
            val replacements = result.value.orEmpty()
            val failure = errorOf(result).let {
                if (it == null && (replacements.isEmpty() || replacements.any { item -> item.chatId != chatId }))
                    ChatError.REQUEST_FAILED else it
            }
            if (failure == null) {
                ownedAttachmentRetryTargets[key] = replacements.toList()
                ownedAttachmentSends.remove(key)?.let { trackOwnedAttachment(message, it) }
                retired += key
                if (snapshot.token.chatId == chatId) messages.remove(messageId)
                replacements.forEachIndexed { index, replacement ->
                    acceptMessage(replacement.copy(presentationId = if (index == 0) message.presentationId else replacement.presentationId,
                        sendAnimationId = request), request)
                }
            }
            snapshot = snapshot.copy(sendError = failure, sendErrorChatId = chatId)
            emit()
        }
    }

    fun markRead(token: ChatToken) {
        val chatId = token.chatId ?: return
        if (!valid(token) || readRequest != null) return
        autoReadSuppressed = false
        val ids = (messages.values.filter { it.delivery == MessageDelivery.SENT }.map { it.id } +
            listOfNotNull(chats[chatId]?.lastMessage?.takeIf { it.delivery == MessageDelivery.SENT }?.id)).distinct()
        submitRead(token, ids, forceRead = true)
    }

    /** The UI reports only visible message keys while the conversation and window are active. */
    fun visibleMessages(token: ChatToken, ids: List<Long>, active: Boolean) {
        if (!valid(token)) return
        val filtered = if (active) ids.filter { it in messages }.toSet() else emptySet()
        val changed = filtered != visibleIds || active != visibleActive
        visibleIds = filtered
        visibleActive = active
        // Metadata is presentation work owned by the actual active visible references.
        if (active) resolveVisiblePeerMetadata()
        if (changed && snapshot.readError != null) {
            snapshot = snapshot.copy(readError = null)
            emit()
        }
        if (changed || snapshot.readError == null) submitVisibleRead()
    }

    private fun submitVisibleRead() {
        val token = snapshot.token
        val chatId = token.chatId ?: return
        if (!visibleActive || autoReadSuppressed || readRequest != null || snapshot.readError != null) return
        val chat = chats[chatId] ?: return
        val ids = visibleIds.mapNotNull(messages::get).filter {
            !it.outgoing && it.delivery == MessageDelivery.SENT &&
                ((it.id > chat.lastReadInboxId && MessageKey(chatId, it.id) !in viewedMessages) ||
                    (chat.markedUnread && chatId !in clearedManualMarkers))
        }.map { it.id }
        if (ids.isEmpty()) return
        submitRead(token, ids, forceRead = false)
    }

    private fun submitRead(token: ChatToken, ids: List<Long>, forceRead: Boolean) {
        val chatId = token.chatId ?: return
        if (ids.isEmpty() && chats[chatId]?.markedUnread != true) return
        val startedMarked = chats[chatId]?.markedUnread == true
        val markerRevision = markerRevisions[chatId] ?: 0
        val request = ++sequence
        readRequest = request
        snapshot = snapshot.copy(readPending = true, readError = null)
        emit()
        fun complete(result: ChatResult<Unit>) {
            if (snapshot.token != token || readRequest != request) return
            readRequest = null
            val error = errorOf(result)
            if (error == null) ids.forEach { viewedMessages += MessageKey(chatId, it) }
            if ((markerRevisions[chatId] ?: 0) != markerRevision && chats[chatId]?.markedUnread == true) {
                // A manual unread action performed after this read began takes precedence.
                // Do not immediately enqueue another automatic read from its old viewport.
                autoReadSuppressed = true
            }
            snapshot = snapshot.copy(readPending = false, readError = error)
            emit()
            if (error == null) submitVisibleRead()
        }
        fun clearManualMarker(result: ChatResult<Unit>) {
            if (snapshot.token != token || readRequest != request) return
            if (errorOf(result) == null && startedMarked && chats[chatId]?.markedUnread == true &&
                (markerRevisions[chatId] ?: 0) == markerRevision && (forceRead || visibleActive)) {
                safely<Unit>({ session.markUnread(chatId, false, it) }) {
                    if (snapshot.token == token && readRequest == request && errorOf(it) == null &&
                        (markerRevisions[chatId] ?: 0) == markerRevision) clearedManualMarkers += chatId
                    complete(it)
                }
            } else complete(result)
        }
        if (ids.isEmpty()) clearManualMarker(ChatResult(Unit))
        else safely<Unit>({ session.viewMessages(chatId, ids, forceRead, it) }) { clearManualMarker(it) }
    }

    fun markUnread(token: ChatToken) {
        val chatId = token.chatId ?: return
        if (!valid(token) || readRequest != null) return
        markerRevisions[chatId] = (markerRevisions[chatId] ?: 0) + 1
        val request = ++sequence
        readRequest = request
        snapshot = snapshot.copy(readPending = true, readError = null)
        emit()
        safely<Unit>({ session.markUnread(chatId, true, it) }) { result ->
            if (snapshot.token != token || readRequest != request) return@safely
            readRequest = null
            if (errorOf(result) == null) {
                autoReadSuppressed = true
                clearedManualMarkers.remove(chatId)
            }
            snapshot = snapshot.copy(readPending = false, readError = errorOf(result))
            // Both the manual marker and unread badge remain driven by TDLib updates.
            emit()
        }
    }

    fun downloadFile(token: ChatToken, fileId: Int) {
        if (!valid(token) || fileId !in knownFileIds || fileId in fileRequests) return
        val file = files[fileId] ?: return
        if (file.downloaded) return
        if (!file.canDownload) {
            fileErrors[fileId] = ChatError.UNSUPPORTED
            emit()
            return
        }
        val request = ++sequence
        val version = fileVersions[fileId] ?: 0
        fileRequests[fileId] = request
        val owner = MediaDownloadOwner(token, fileId.toLong(), MediaDownloadPurpose.USER)
        downloadOwners[fileId] = owner
        fileErrors.remove(fileId)
        emit()
        safely<MediaFile>({ session.acquireDownload(owner, file, it) }) { result ->
            if (!valid(token) || fileRequests[fileId] != request || downloadOwners[fileId] != owner) return@safely
            fileRequests.remove(fileId)
            downloadOwners.remove(fileId)
            val error = errorOf(result).let { if (it == null && result.value?.id != fileId) ChatError.REQUEST_FAILED else it }
            if (error != null) {
                if (files[fileId]?.downloaded != true) fileErrors[fileId] = error
            } else if ((fileVersions[fileId] ?: 0) == version) {
                files[fileId] = checkNotNull(result.value)
            }
            emit()
        }
    }

    /** Visible sticker placeholders may request only TDLib's local outline, never arbitrary files. */
    fun loadStickerOutline(token: ChatToken, fileId: Int) {
        if (!valid(token) || fileId <= 0 || fileId in stickerOutlineRequests ||
            stickerOutlineRequests.size >= MAX_STICKER_OUTLINE_REQUESTS) return
        val media = knownStickerMedia(fileId) ?: return
        // TDLib scales small custom-emoji outline coordinates along with this
        // animated-emoji sticker's dimensions. Keep both from the same variant.
        val forAnimatedEmoji = media.animatedEmoji != null
        if (fileId in stickerOutlines) {
            // Touch the bounded in-memory cache when a previously visible sticker returns.
            val outline = stickerOutlines.remove(fileId) ?: return
            stickerOutlines[fileId] = outline
            return
        }
        val request = ++sequence
        stickerOutlineRequests[fileId] = request
        safely<String>({ session.stickerOutline(fileId, forAnimatedEmoji, it) }) { result ->
            if (!valid(token) || stickerOutlineRequests[fileId] != request) return@safely
            stickerOutlineRequests.remove(fileId)
            val current = knownStickerMedia(fileId) ?: return@safely
            if (current.width != media.width || current.height != media.height ||
                (current.animatedEmoji != null) != forAnimatedEmoji) return@safely
            // An unavailable native outline remains an empty placeholder rather than a fabricated shape.
            val path = result.value?.takeIf { result.error == null && it.length <= MAX_STICKER_OUTLINE_LENGTH }.orEmpty()
            stickerOutlines[fileId] = StickerOutline(path, media.width, media.height)
            while (stickerOutlines.size > MAX_STICKER_OUTLINES) stickerOutlines.remove(stickerOutlines.keys.first())
            emit()
        }
    }

    private fun knownStickerMedia(fileId: Int): MessageMedia? =
        messages.values.firstOrNull { it.kind == MessageKind.ANIMATED_EMOJI && it.media?.animatedEmoji != null &&
            it.media?.file?.id == fileId }?.media
            ?: messages.values.firstOrNull { it.kind == MessageKind.STICKER && it.media?.file?.id == fileId }?.media
            ?: snapshot.stickerSections.asSequence().flatMap { it.stickers.asSequence() }
                .firstOrNull { it.media.file.id == fileId }?.media
            ?: snapshot.stickerSets.firstNotNullOfOrNull { it.cover?.media?.takeIf { media -> media.file.id == fileId } }

    fun readVideoPart(token: ChatToken, messageId: Long, fileId: Int, offset: Long, count: Int, result: (ChatResult<ByteArray>) -> Unit) {
        readOwnedVideoPart(MediaDownloadOwner(token, messageId, MediaDownloadPurpose.STREAM), fileId, offset, count, result)
    }

    fun readOwnedVideoPart(owner: MediaDownloadOwner, fileId: Int, offset: Long, count: Int, result: (ChatResult<ByteArray>) -> Unit) {
        val token = owner.token
        val messageId = owner.messageId
        fun allowed(): Boolean {
            val message = messages[messageId] ?: return false
            val media = message.media ?: return false
            val file = files[fileId] ?: media.file
            return valid(token) && owner.leaseId >= 0 && owner.purpose in setOf(MediaDownloadPurpose.STREAM, MediaDownloadPurpose.PROBE) && message.chatId == token.chatId &&
                (media.format == MediaFormat.VIDEO || message.kind == MessageKind.ANIMATION && media.format == MediaFormat.ANIMATION) &&
                media.file.id == fileId && fileId in knownFileIds && offset >= 0 && count in 1..65_536 &&
                (file.size == 0L || offset < file.size || file.downloaded && offset == file.size) && MessageKey(message.chatId, messageId) !in permanentlyDeleted
        }
        if (!allowed() || owner.leaseId > 0 && owner to fileId !in streamOwners) { result(ChatResult(error = ChatError.FORBIDDEN)); return }
        // Only the legacy synthetic entry can register implicitly. Production readers open once
        // under their source's parent lock, then every late read requires that live registration.
        if (owner.leaseId == 0L) streamOwners += owner to fileId
        val file = files[fileId] ?: checkNotNull(messages[messageId]?.media).file
        safely<ByteArray>({ session.readVideoPart(owner, file, offset, count, it) }) {
            result(if (allowed() && owner to fileId in streamOwners) it else ChatResult(error = ChatError.FORBIDDEN))
        }
    }

    fun releaseVideoPart(token: ChatToken, messageId: Long, fileId: Int) {
        releaseOwnedVideoPart(MediaDownloadOwner(token, messageId, MediaDownloadPurpose.STREAM), fileId)
    }

    fun openOwnedVideoPart(owner: MediaDownloadOwner, fileId: Int) {
        if (!valid(owner.token) || owner.leaseId <= 0 || streamOwners.size >= 256 ||
            owner.purpose !in setOf(MediaDownloadPurpose.STREAM, MediaDownloadPurpose.PROBE)) return
        val message = messages[owner.messageId] ?: return
        val media = message.media ?: return
        if (message.chatId != owner.token.chatId || media.file.id != fileId || fileId !in knownFileIds ||
            (media.format != MediaFormat.VIDEO && !(message.kind == MessageKind.ANIMATION && media.format == MediaFormat.ANIMATION)) ||
            MessageKey(message.chatId, message.id) in permanentlyDeleted) return
        streamOwners += owner to fileId
    }

    fun releaseOwnedVideoPart(owner: MediaDownloadOwner, fileId: Int) {
        if (streamOwners.remove(owner to fileId)) session.releaseDownload(owner, fileId)
    }

    fun acquireOwnedMedia(owner: MediaDownloadOwner, fileId: Int) {
        // Profile resources have separate owners; message membership grants no profile-resource permission.
        if (owner.purpose == MediaDownloadPurpose.PROFILE_EMOJI || owner.purpose == MediaDownloadPurpose.PROFILE_PHOTO_HISTORY) return
        if (!valid(owner.token) || owner.purpose !in setOf(MediaDownloadPurpose.ARTWORK, MediaDownloadPurpose.USER) ||
            fileId !in knownFileIds || owner to fileId in ownedFullRequests) return
        val file = files[fileId]?.takeIf { !it.downloaded && it.canDownload } ?: return
        val key = owner to fileId
        val request = ++sequence
        ownedFullRequests[key] = request
        safely<MediaFile>({ session.acquireDownload(owner, file, it) }) { result ->
            if (!valid(owner.token) || ownedFullRequests[key] != request) return@safely
            ownedFullRequests.remove(key)
            result.error?.let { if (files[fileId]?.downloaded != true) fileErrors[fileId] = it }
            emit()
        }
        emit()
    }

    fun releaseOwnedMedia(owner: MediaDownloadOwner, fileId: Int) {
        if (ownedFullRequests.remove(owner to fileId) == null) return
        session.releaseDownload(owner, fileId)
        if (valid(owner.token)) emit()
    }

    fun automaticMedia(token: ChatToken, messageId: Long, active: Boolean) {
        if (valid(token)) autoMedia.visible(token, messageId, active)
    }

    fun saveAutoMedia(token: ChatToken, revision: Long, policy: AutoMediaPolicy,
        completed: (Boolean) -> Unit = {},
    ): Boolean = valid(token) && autoMedia.save(revision, policy, completed)

    private fun releasePageDownloads() {
        downloadOwners.toMap().forEach { (fileId, owner) -> session.releaseDownload(owner, fileId) }
        streamOwners.toList().forEach { (owner, fileId) -> session.releaseDownload(owner, fileId) }
        ownedFullRequests.keys.toList().forEach { (owner, fileId) -> session.releaseDownload(owner, fileId) }
        downloadOwners.clear()
        streamOwners.clear()
        ownedFullRequests.clear()
        fileRequests.clear()
    }

    fun loadStickers(token: ChatToken, setId: Long? = null) {
        if (!valid(token) || (setId != null && snapshot.stickerSets.none { it.id == setId })) return
        stickersOpened = true
        if (!packsLoaded && packsRequest == null) loadStickerPacks(token)
        snapshot = snapshot.copy(selectedStickerSetId = setId)
        requestStickerSection(token, setId, refresh = true)
    }

    /** Viewport-driven loading does not navigate, refresh, or discard another section. */
    fun ensureStickerSection(token: ChatToken, setId: Long?) {
        if (!valid(token) || (setId != null && snapshot.stickerSets.none { it.id == setId })) return
        stickersOpened = true
        if (!packsLoaded && packsRequest == null) loadStickerPacks(token)
        requestStickerSection(token, setId, refresh = false)
    }

    private fun requestStickerSection(token: ChatToken, setId: Long?, refresh: Boolean) {
        if (!valid(token)) return
        rebuildStickerSections()
        val section = snapshot.stickerSections.firstOrNull { it.setId == setId } ?: return
        if (section.loading || !refresh && (section.loaded || section.error != null)) {
            if (section.loading && refresh) dirtyStickerSections += setId
            syncSelectedStickerSection()
            emit()
            return
        }
        updateStickerSection(setId) { copy(loading = true, error = null) }
        queuedStickerSections += setId
        syncSelectedStickerSection()
        emit()
        startQueuedStickerSections(token)
    }

    private fun startQueuedStickerSections(token: ChatToken) {
        while (valid(token) && stickerSectionRequests.size < MAX_STICKER_SECTION_REQUESTS && queuedStickerSections.isNotEmpty()) {
            val setId = queuedStickerSections.first()
            queuedStickerSections.remove(setId)
            if (snapshot.stickerSections.none { it.setId == setId }) continue
            val request = ++sequence
            stickerSectionRequests[setId] = request
            safely<List<StickerItem>>({ callback ->
                if (setId == null) session.recentStickers(callback) else session.stickerSet(setId, callback)
            }) { result ->
                if (!valid(token) || stickerSectionRequests[setId] != request) return@safely
                stickerSectionRequests.remove(setId)
                if (snapshot.stickerSections.any { it.setId == setId }) {
                    val error = errorOf(result)
                    val stickers = result.value.orEmpty().takeIf { error == null }
                    stickers?.forEach(::rememberSticker)
                    updateStickerSection(setId) {
                        copy(stickers = stickers?.toList() ?: this.stickers, loading = false,
                            loaded = error == null || loaded, error = error)
                    }
                    syncSelectedStickerSection()
                    emit()
                    if (dirtyStickerSections.remove(setId)) requestStickerSection(token, setId, refresh = true)
                }
                startQueuedStickerSections(token)
            }
        }
    }

    private fun rebuildStickerSections() {
        val previous = snapshot.stickerSections.associateBy { it.setId }
        snapshot = snapshot.copy(stickerSections = listOf(previous[null] ?: StickerSection()) +
            snapshot.stickerSets.map { pack ->
                (previous[pack.id] ?: StickerSection(setId = pack.id)).copy(title = pack.title)
            })
    }

    private inline fun updateStickerSection(setId: Long?, transform: StickerSection.() -> StickerSection) {
        snapshot = snapshot.copy(stickerSections = snapshot.stickerSections.map { if (it.setId == setId) it.transform() else it })
    }

    private fun syncSelectedStickerSection() {
        val selected = snapshot.stickerSections.firstOrNull { it.setId == snapshot.selectedStickerSetId }
        snapshot = snapshot.copy(stickers = selected?.stickers.orEmpty(), stickersLoading = selected?.loading == true,
            stickersError = selected?.error)
    }

    fun loadStickerPacks(token: ChatToken) {
        if (!valid(token)) return
        if (packsRequest != null) { packsDirty = true; return }
        val request = ++sequence
        packsRequest = request
        safely<List<StickerPack>>({ session.installedStickerSets(it) }) { result ->
            if (!valid(token) || packsRequest != request) return@safely
            packsRequest = null
            val error = errorOf(result)
            if (error == null) {
                packsLoaded = true
                val packs = result.value.orEmpty().filter { it.id > 0 }.distinctBy { it.id }
                packs.forEach { it.cover?.let(::rememberSticker) }
                snapshot = snapshot.copy(stickerSets = packs.toList())
                rebuildStickerSections()
                queuedStickerSections.removeAll { id -> id != null && packs.none { it.id == id } }
                dirtyStickerSections.removeAll { id -> id != null && packs.none { it.id == id } }
                if (snapshot.selectedStickerSetId != null && packs.none { it.id == snapshot.selectedStickerSetId }) {
                    loadStickers(snapshot.token)
                }
            } else snapshot = snapshot.copy(stickersError = error)
            emit()
            if (packsDirty) {
                packsDirty = false
                loadStickerPacks(snapshot.token)
            }
        }
    }

    fun acceptsFolderSettings(token: FolderSettingsToken) = !deactivated && snapshot.enabled && folderSettings?.accepts(token) == true
    fun openFolderSettings(token: ChatToken, pageId: Long, active: () -> Boolean) {
        if (valid(token) && !snapshot.folderSaving && snapshot.folderEditorId == null && active()) folderSettings?.open(active, pageId)
    }
    fun closeFolderSettings(token: FolderSettingsToken) { folderSettings?.close(token) }
    fun refreshFolderSettings(token: FolderSettingsToken) { folderSettings?.refresh(token) }
    fun stageFolderRecommendation(token: FolderSettingsToken, key: Long) { folderSettings?.stageRecommendation(token, key) }
    fun moveFolder(token: FolderSettingsToken, entry: FolderDraftEntry, delta: Int) { folderSettings?.move(token, entry, delta) }
    fun stageFolderRemoval(token: FolderSettingsToken, id: Int) { folderSettings?.remove(token, id) }
    fun restoreFolder(token: FolderSettingsToken, id: Int) { folderSettings?.restore(token, id) }
    fun resetFolderSettings(token: FolderSettingsToken) { folderSettings?.reset(token) }
    fun applyFolderSettings(token: FolderSettingsToken, revision: Long) { folderSettings?.apply(token, revision) }
    fun toggleFolderTags(token: FolderSettingsToken, enabled: Boolean) { folderSettings?.toggleTags(token, enabled) }
    fun colorFolder(token: FolderSettingsToken, folder: ChatFolderInfo, colorId: Int) { folderSettings?.color(token, folder, colorId) }

    fun editFolder(token: ChatToken, id: Int = 0) {
        if (!valid(token) || snapshot.folderSaving || id < 0 || (id > 0 && snapshot.folders.none { it.id == id })) return
        val request = ++sequence
        folderEditorLease.set(false)
        val lease = AtomicBoolean(true)
        folderEditorLease = lease
        folderRequest = request
        val openingRevision = snapshot.folderRevision + 1
        snapshot = snapshot.copy(folderEditorId = id, folderEditor = if (id == 0) ChatFolder(name = "", editorRevision = openingRevision) else null,
            folderLoading = id != 0, folderError = null, folderRevision = snapshot.folderRevision + 1)
        emit()
        if (id == 0) { folderRequest = null; return }
        safely<ChatFolder>({ session.getFolder(id, lease::get, it) }) { result ->
            if (folderRequest != request || snapshot.folderEditorId != id) return@safely
            folderRequest = null
            val error = errorOf(result).let { if (it == null && result.value?.id != id) ChatError.REQUEST_FAILED else it }
            snapshot = snapshot.copy(folderEditor = result.value.takeIf { error == null }?.copy(editorRevision = snapshot.folderRevision + 1), folderLoading = false,
                folderError = error, folderRevision = snapshot.folderRevision + 1)
            emit()
        }
    }

    fun closeFolderEditor(token: ChatToken) {
        if (!valid(token) || snapshot.folderSaving) return
        clearFolderEditor()
        emit()
    }

    fun saveFolder(token: ChatToken, folder: ChatFolder) {
        if (!valid(token) || snapshot.folderSaving || snapshot.folderLoading || snapshot.folderEditorId != folder.id) return
        val original = snapshot.folderEditor ?: return
        if (folder.sourceId != original.sourceId || folder.editorRevision != original.editorRevision ||
            folder.editorRevision != snapshot.folderRevision) return
        val error = when {
            original.isShareable || folder.isShareable -> ChatError.UNSUPPORTED
            folder.name.isBlank() -> ChatError.EMPTY_TEXT
            folder.name.codePointCount(0, folder.name.length) > 12 -> ChatError.TEXT_TOO_LONG
            folder.includedChatIds.isEmpty() && folder.pinnedChatIds.isEmpty() &&
                !folder.includeContacts && !folder.includeNonContacts && !folder.includeBots && !folder.includeGroups && !folder.includeChannels -> ChatError.EMPTY_TEXT
            else -> null
        }
        if (error != null) {
            snapshot = snapshot.copy(folderError = error)
            emit()
            return
        }
        val request = ++sequence
        val lease = folderEditorLease
        folderRequest = request
        val version = foldersVersion
        snapshot = snapshot.copy(folderSaving = true, folderError = null)
        emit()
        safely<ChatFolderInfo>({ session.saveFolder(folder, lease::get, it) }) { result ->
            if (folderRequest != request) return@safely
            folderRequest = null
            val failure = errorOf(result)
            if (failure == null) {
                val info = checkNotNull(result.value)
                // A newer native folders update wins over the request's older snapshot,
                // including a deletion that arrived before this acknowledgement.
                if (version == foldersVersion) {
                    snapshot = snapshot.copy(folders = if (snapshot.folders.any { it.id == info.id })
                        snapshot.folders.map { if (it.id == info.id) info else it } else snapshot.folders + info)
                }
                listPages.remove(ChatListRef(info.id))
                clearFolderEditor()
                if (snapshot.activeList.folderId == info.id) loadChats(snapshot.token)
            } else snapshot = snapshot.copy(folderSaving = false, folderError = failure)
            emit()
        }
    }

    fun deleteFolder(token: ChatToken, id: Int) {
        if (!valid(token) || id <= 0 || snapshot.folderSaving || snapshot.folders.none { it.id == id }) return
        val request = ++sequence
        folderRequest = request
        snapshot = snapshot.copy(folderSaving = true, folderError = null)
        emit()
        safely<Unit>({ session.deleteFolder(id, it) }) { result ->
            if (folderRequest != request) return@safely
            folderRequest = null
            val error = errorOf(result)
            if (error == null) {
                snapshot = snapshot.copy(folders = snapshot.folders.filterNot { it.id == id })
                listPages.remove(ChatListRef(id))
                clearFolderEditor()
                if (snapshot.activeList.folderId == id) selectList(snapshot.token, ChatListRef())
            } else snapshot = snapshot.copy(folderSaving = false, folderError = error)
            emit()
        }
    }

    private fun clearFolderEditor() {
        folderEditorLease.set(false)
        folderRequest = null
        snapshot = snapshot.copy(folderEditorId = null, folderEditor = null, folderLoading = false,
            folderSaving = false, folderError = null, folderRevision = snapshot.folderRevision + 1)
    }

    private fun replaceMessage(oldId: Long, message: ChatMessage) {
        val oldKey = MessageKey(message.chatId, oldId)
        val previous = messages[oldId]?.takeIf { it.chatId == message.chatId } ?: terminalMessages[oldKey]
        val historyOrigin = snapshot.token.chatId == message.chatId && oldId in historyMessageIds
        val replacement = message.copy(
            inlineKeyboard = currentInlineKeyboard(message),
            presentationId = previous?.presentationId ?: oldId.takeIf { it != 0L } ?: message.presentationId,
            sendAnimationId = previous?.sendAnimationId ?: message.sendAnimationId,
            incomingAnimationId = previous?.incomingAnimationId ?: message.incomingAnimationId,
        )
        if (oldId != message.id) {
            retired += oldKey
            replacementTargets[oldKey] = MessageKey(message.chatId, message.id)
        }
        if (snapshot.token.chatId == message.chatId && oldId != message.id) {
            messages.remove(oldId)
            historyMessageIds.remove(oldId)
            contentOverrides.remove(oldId)
            if (historyRequest != null) historyRemoved += oldId
        }
        terminalMessages[MessageKey(message.chatId, message.id)] = replacement
        acceptMessage(replacement)
        if (historyOrigin && messages[message.id]?.chatId == message.chatId) historyMessageIds += message.id
        updateChat(message.chatId) {
            if (lastMessage?.id == oldId || lastMessage?.id == message.id) copy(lastMessage = replacement) else this
        }
    }

    private fun acceptMessage(supplied: ChatMessage, request: Long? = null, fromHistory: Boolean = false) {
        val key = MessageKey(supplied.chatId, supplied.id)
        if (key in permanentlyDeleted || key in retired) return
        if (request != null && (inaccessibleSince[key] ?: 0) > request) return
        if (request == null) inaccessibleSince.remove(key)
        // The cache protects against a late SendMessage acknowledgement. Fresh history
        // and content updates must remain authoritative after a later edit.
        val reconciled = if (supplied.delivery == MessageDelivery.PENDING) terminalMessages[key] ?: supplied else supplied
        val existing = messages[supplied.id]?.takeIf { it.chatId == supplied.chatId }
        val latestReactions = reactionUpdates[key]?.takeIf { request != null && it.first > request }?.second
        val message = reconciled.copy(editDate = maxOf(reconciled.editDate, editedDates[key] ?: 0),
            inlineKeyboard = currentInlineKeyboard(reconciled, request),
            reactions = latestReactions ?: reconciled.reactions,
            presentationId = existing?.presentationId ?: reconciled.presentationId,
            sendAnimationId = supplied.sendAnimationId ?: existing?.sendAnimationId ?: reconciled.sendAnimationId ?:
                sendRequest?.takeIf { !fromHistory && reconciled.outgoing && reconciled.delivery == MessageDelivery.PENDING },
            incomingAnimationId = existing?.incomingAnimationId ?: reconciled.incomingAnimationId,
        )
        if (request == null && snapshot.token.chatId == message.chatId) messages[message.id]?.let { existing ->
            if (existing.text != message.text || existing.entities != message.entities || existing.kind != message.kind || existing.media != message.media || existing.editDate != message.editDate) {
                contentVersions[key] = ++sequence
            }
        }
        rememberSender(message)
        rememberReactionSenders(message.reactions)
        message.media?.let(::rememberMedia)
        if (message.kind == MessageKind.STICKER) message.media?.let { media ->
            stickerCatalog.putIfAbsent(media.file.id, StickerItem(0, media.setId, media.emoji, media))
        }
        if (message.delivery == MessageDelivery.FAILED) {
            retryAllowedAt.putIfAbsent(key, nowMillis() + message.retryAfterSeconds.coerceAtLeast(0).toLong() * 1_000)
        }
        if (snapshot.token.chatId != message.chatId) return
        // Only an explicit native history page can extend an isolated window. Temporary
        // send ids and late updates are not evidence that the intervening range is loaded.
        if (!fromHistory && message.id !in messages && (snapshot.historyHasNewer || initialUnreadRequest != null ||
            jumpRequest != null || sendLatestRequest != null)) {
            terminalMessages[key] = message
            return
        }
        val override = contentOverrides[message.id]
        val current = override?.applyTo(message) ?: message
        if (!current.replyTo?.text.isNullOrBlank()) resolvedReplies.remove(message.id)
        val resolved = resolvedReplies[message.id]?.takeIf { it.chatId == current.replyTo?.chatId && it.messageId == current.replyTo?.messageId }
        val quoteAuthor = resolvedQuoteAuthors[message.id]?.takeIf { current.replyTo == it.source || current.replyTo == it.resolved }
        if (quoteAuthor == null) resolvedQuoteAuthors.remove(message.id)
        messages[message.id] = when {
            quoteAuthor != null -> current.copy(replyTo = quoteAuthor.resolved)
            current.replyTo?.text.isNullOrBlank() && resolved != null -> current.copy(replyTo = resolved)
            else -> current
        }
        if (fromHistory) historyMessageIds += message.id
        if (message.id > 0) newerCursor = maxOf(newerCursor, message.id)
        resolveReply(messages.getValue(message.id))
        resolveForwardSource(messages.getValue(message.id))
    }

    private fun resolveForwardSource(message: ChatMessage) {
        val source = message.forwardInfo?.origin?.sourceIdentity() ?: return
        val known = when (source) {
            is ForwardSourceIdentity.User -> names[source.userId]
            is ForwardSourceIdentity.Chat -> chats[source.chatId]?.title
        }
        if (!known.isNullOrBlank() || source in forwardSourceNames) return
        resolvePeerPresentation(source)
    }

    /** Ordinary sender/reply metadata never loads history or opens a chat. */
    private fun resolveVisiblePeerMetadata() {
        if (!visibleActive) return
        visibleIds.asSequence().mapNotNull(messages::get).forEach { message ->
            resolveQuotedReplyAuthor(message)
            visiblePeerSources(message).forEach { source ->
                val known = when (source) {
                    is ForwardSourceIdentity.User -> userAppearances[source.userId] ?: snapshot.account?.takeIf { it.userId == source.userId }?.appearance
                    is ForwardSourceIdentity.Chat -> chats[source.chatId]?.appearance ?: chatAppearances[source.chatId]
                }
                if (known == null) resolvePeerPresentation(source, message.id)
            }
        }
    }

    private fun senderSource(id: Long, isChat: Boolean): ForwardSourceIdentity? = when {
        isChat && id != 0L -> ForwardSourceIdentity.Chat(id)
        !isChat && id > 0 -> ForwardSourceIdentity.User(id)
        else -> null
    }

    private fun visiblePeerSources(message: ChatMessage): Set<ForwardSourceIdentity> = buildSet {
        // Ordinary private messages retain their existing no-sender-slot geometry.
        val group = chats[message.chatId]?.let { it.basicGroupId != null || it.supergroupId != null } == true
        if (group) senderSource(message.senderId, message.senderIsChat)?.let(::add)
        message.replyTo?.let { reply ->
            val original = messages[reply.messageId]?.takeIf { it.chatId == reply.chatId }
            val author = when {
                original != null -> messageContentAuthor(original)
                reply.contentAuthorKnown -> reply.contentAuthor
                else -> senderSource(reply.senderId, reply.senderIsChat)
            }
            author?.let(::add)
        }
    }

    private fun messageContentAuthor(message: ChatMessage): ForwardSourceIdentity? =
        if (message.forwardInfo != null) message.forwardInfo.origin.sourceIdentity()
        else senderSource(message.senderId, message.senderIsChat)

    private fun peerMetadataWitness(message: ChatMessage): List<Any?> = listOf(
        message.senderId, message.senderIsChat, message.replyTo?.chatId, message.replyTo?.messageId,
        message.replyTo?.senderId, message.replyTo?.senderIsChat, message.replyTo?.contentAuthor,
        message.replyTo?.contentAuthorKnown, visiblePeerSources(message),
    )

    private fun resolvePeerPresentation(source: ForwardSourceIdentity, visibleMessageId: Long? = null) {
        if (source in forwardSourceRequests || attemptedForwardSources.size >= 512 || forwardSourceRequests.size >= 8 ||
            !attemptedForwardSources.add(source)) return
        val token = snapshot.token
        val request = ++sequence
        val witness = visibleMessageId?.let(messages::get)?.let(::peerMetadataWitness)
        val version = appearanceVersions.getOrPut(source) { request }
        trimAppearances(appearanceVersions, 1024)
        forwardSourceRequests[source] = request
        safely<PeerAppearancePresentation>({ session.forwardSourcePresentation(source, it) }) { result ->
            if (!valid(token) || forwardSourceRequests[source] != request) return@safely
            forwardSourceRequests.remove(source)
            val referenced = if (visibleMessageId == null) {
                messages.values.any { it.forwardInfo?.origin?.sourceIdentity() == source }
            } else visibleActive && visibleMessageId in visibleIds && messages[visibleMessageId]?.let {
                peerMetadataWitness(it) == witness && source in visiblePeerSources(it)
            } == true
            val peer = result.value
            if (referenced && appearanceVersions[source] == version && errorOf(result) == null && peer?.identity == source) {
                if (peer.name.isNotBlank()) {
                    forwardSourceNames[source] = peer.name
                    trimAppearances(forwardSourceNames, 512)
                    when (source) {
                        is ForwardSourceIdentity.User -> names[source.userId] = peer.name
                        is ForwardSourceIdentity.Chat -> updateChat(source.chatId) { copy(title = peer.name) }
                    }
                }
                peer.appearance?.let { appearance ->
                    when (source) {
                        is ForwardSourceIdentity.User -> rememberUserAppearance(source.userId, appearance)
                        is ForwardSourceIdentity.Chat -> {
                            rememberChatAppearance(source.chatId, appearance)
                            updateChat(source.chatId) { copy(appearance = appearance) }
                        }
                    }
                }
            }
            if (!referenced) attemptedForwardSources.remove(source)
            emit()
        }
    }

    private fun <K, V> trimAppearances(values: MutableMap<K, V>, maximum: Int) {
        while (values.size > maximum) values.remove(values.keys.first())
    }

    private fun rememberUserAppearance(id: Long, appearance: SettingsProfileColorsValues) {
        if (id <= 0) return
        userAppearances.remove(id)
        userAppearances[id] = appearance
        appearanceVersions[ForwardSourceIdentity.User(id)] = ++sequence
        trimAppearances(userAppearances, 512)
        trimAppearances(appearanceVersions, 1024)
    }

    private fun rememberChatAppearance(id: Long, appearance: SettingsProfileColorsValues) {
        if (id == 0L) return
        chatAppearances.remove(id)
        chatAppearances[id] = appearance
        appearanceVersions[ForwardSourceIdentity.Chat(id)] = ++sequence
        trimAppearances(chatAppearances, 512)
        trimAppearances(appearanceVersions, 1024)
    }

    private fun resolveReply(message: ChatMessage) {
        val reply = message.replyTo ?: return
        if (reply.text.isNotBlank() || reply.messageId <= 0 || message.id <= 0 ||
            MessageKey(reply.chatId, reply.messageId) in permanentlyDeleted ||
            message.delivery != MessageDelivery.SENT || !attemptedReplies.add(message.id)) return
        val token = snapshot.token
        val request = ++sequence
        replyRequests[message.id] = request
        fun applyReply(target: ChatMessage) {
            if (target.id != reply.messageId || target.chatId != reply.chatId ||
                MessageKey(target.chatId, target.id) in permanentlyDeleted ||
                (inaccessibleSince[MessageKey(target.chatId, target.id)] ?: 0) > request) return
            val current = messages[message.id] ?: return
            if (current.replyTo != reply) return
            val latest = messages[target.id]?.takeIf { it.chatId == target.chatId } ?: target
            val override = contentOverrides[target.id].takeIf { target.chatId == token.chatId }
            val resolved = reply.copy(text = override?.text ?: latest.text, entities = override?.entities ?: latest.entities,
                senderId = latest.senderId, senderIsChat = latest.senderIsChat,
                contentAuthor = messageContentAuthor(latest), contentAuthorKnown = true)
            resolvedReplies[message.id] = resolved
            messages[message.id] = current.copy(replyTo = resolved)
        }
        val loaded = messages[reply.messageId]?.takeIf { it.chatId == reply.chatId }
        if (loaded != null) {
            replyRequests.remove(message.id)
            applyReply(loaded)
            return
        }
        safely<ChatMessage>({ session.getReplyMessage(message.chatId, message.id, it) }) { result ->
            if (!valid(token) || replyRequests[message.id] != request) return@safely
            replyRequests.remove(message.id)
            if (errorOf(result) == null) applyReply(checkNotNull(result.value))
            emit()
        }
    }

    /** A native quote can have text but no origin; only visible ownership may resolve its author. */
    private fun resolveQuotedReplyAuthor(message: ChatMessage) {
        val reply = message.replyTo ?: return
        val targetKey = MessageKey(reply.chatId, reply.messageId)
        if (!visibleActive || message.id !in visibleIds || reply.text.isBlank() || reply.contentAuthorKnown ||
            reply.messageId <= 0 || message.id <= 0 || message.delivery != MessageDelivery.SENT ||
            targetKey in permanentlyDeleted || messages[reply.messageId]?.chatId == reply.chatId ||
            message.id in quotedReplyAuthorRequests || quotedReplyAuthorRequests.size >= 8 ||
            attemptedQuotedReplyAuthors.size >= 512 || !attemptedQuotedReplyAuthors.add(message.id)) return
        val token = snapshot.token
        val request = ++sequence
        val witness = peerMetadataWitness(message)
        quotedReplyAuthorRequests[message.id] = request
        safely<ChatMessage>({ session.getReplyMessage(message.chatId, message.id, it) }) { result ->
            if (!valid(token) || quotedReplyAuthorRequests[message.id] != request) return@safely
            quotedReplyAuthorRequests.remove(message.id)
            val current = messages[message.id]
            val owned = visibleActive && message.id in visibleIds && current != null && current.replyTo == reply &&
                peerMetadataWitness(current) == witness
            if (!owned) {
                attemptedQuotedReplyAuthors.remove(message.id)
                emit()
                return@safely
            }
            val target = result.value
            if (errorOf(result) == null && target != null && target.id == reply.messageId && target.chatId == reply.chatId &&
                targetKey !in permanentlyDeleted && (inaccessibleSince[targetKey] ?: 0) <= request) {
                val latest = messages[target.id]?.takeIf { it.chatId == target.chatId } ?: target
                // Never replace the sender-provided quote text, entities, position or isQuote flag.
                val resolved = reply.copy(senderId = latest.senderId, senderIsChat = latest.senderIsChat,
                    contentAuthor = messageContentAuthor(latest), contentAuthorKnown = true)
                resolvedQuoteAuthors[message.id] = QuoteAuthorResolution(reply, resolved)
                messages[message.id] = checkNotNull(current).copy(replyTo = resolved)
            }
            emit()
        }
    }

    private fun refreshResolvedReplies(chatId: Long, messageId: Long, text: String, entities: List<MessageEntity>) {
        val matching = resolvedReplies.filterValues { it.chatId == chatId && it.messageId == messageId }
        matching.forEach { (id, resolved) ->
            val current = messages[id] ?: return@forEach
            if (current.replyTo == resolved) {
                val updated = resolved.copy(text = text, entities = entities)
                resolvedReplies[id] = updated
                messages[id] = current.copy(replyTo = updated)
            }
        }
    }

    private fun invalidateResolvedReplies(chatId: Long, deletedIds: Set<Long>) {
        val quoteAuthors = resolvedQuoteAuthors.filterValues { it.source.chatId == chatId && it.source.messageId in deletedIds }
        quoteAuthors.forEach { (id, resolution) ->
            resolvedQuoteAuthors.remove(id)
            val current = messages[id] ?: return@forEach
            if (current.replyTo == resolution.resolved) messages[id] = current.copy(
                replyTo = resolution.resolved.copy(senderId = 0, senderIsChat = false,
                    contentAuthor = null, contentAuthorKnown = false),
            )
        }
        val invalid = resolvedReplies.filterValues { it.chatId == chatId && it.messageId in deletedIds }
        invalid.forEach { (id, resolved) ->
            resolvedReplies.remove(id)
            val current = messages[id] ?: return@forEach
            // Only our fetched preview is invalidated; a quote included by the sender
            // remains part of the message even after the original is deleted.
            if (current.replyTo == resolved) messages[id] = current.copy(
                replyTo = resolved.copy(text = "", entities = emptyList(), senderId = 0, senderIsChat = false,
                    contentAuthor = null, contentAuthorKnown = false),
            )
        }
    }

    private fun canSendMessage(chatId: Long, kind: MessageKind): Boolean {
        val chat = chats[chatId] ?: return false
        return when (kind) {
            MessageKind.PHOTO -> chat.canSendPhotos
            MessageKind.STICKER -> chat.canSendStickers
            MessageKind.VIDEO -> chat.canSendVideos
            MessageKind.FILE -> chat.canSendDocuments
            MessageKind.VOICE -> chat.canSendVoiceNotes
            MessageKind.TEXT, MessageKind.ANIMATED_EMOJI -> chat.canSend
            else -> false
        }
    }

    private fun rememberFile(file: MediaFile) {
        if (file.id <= 0) return
        knownFileIds += file.id
        files.putIfAbsent(file.id, file)
    }

    private fun rememberSender(message: ChatMessage) {
        if (message.senderIsChat || message.senderId <= 0) return
        messageSenders += message.senderId
        userAvatars[message.senderId]?.let(::rememberFile)
    }

    private fun rememberReactionSenders(reactions: List<MessageReaction>) {
        reactions.flatMap { it.recentSenders }.forEach { sender ->
            if (sender.senderIsChat) chats[sender.id]?.avatar?.let(::rememberFile)
            else if (sender.id > 0) {
                messageSenders += sender.id
                userAvatars[sender.id]?.let(::rememberFile)
            }
        }
    }

    private fun rememberMedia(media: MessageMedia) {
        rememberFile(media.file)
        media.thumbnail?.let(::rememberFile)
    }

    private fun rememberSticker(sticker: StickerItem) {
        stickerCatalog[sticker.media.file.id] = sticker
        rememberMedia(sticker.media)
    }

    private fun updateFile(file: MediaFile) {
        if (file.id <= 0) return
        // A standalone chat session still feeds its broker before the presentation cache cap.
        // Shared native sessions dispatch once at the client boundary; their actor hook is a no-op.
        session.observeDownload(file)
        // File updates may precede their containing message. Keep a bounded early-update
        // cache, but only expose and allow downloading files referenced by known content.
        if (file.id !in knownFileIds && files.size >= knownFileIds.size + 256) return
        files[file.id] = file
        fileVersions[file.id] = ++sequence
        if (file.downloaded) {
            fileErrors.remove(file.id)
            fileRequests.remove(file.id)
        }
        // Finite range completion, quantum rotation and owner retirement are normal states.
        // A current broker command's explicit failure is delivered through its own callback.
    }

    private fun valid(token: ChatToken) = snapshot.enabled && snapshot.token == token

    private fun updateChat(chatId: Long, transform: ChatItem.() -> ChatItem) {
        chats[chatId]?.let { chats[chatId] = it.transform() }
    }

    private fun emit() {
        historyMessageIds.retainAll(messages.keys)
        if (deactivated) return
        if (isAccountAllowed()) autoMedia.reconcile()
        headerActivities.entries.removeAll { it.value.expiresAtMillis <= nowMillis() }
        val selectedId = snapshot.token.chatId
        val page = listPages[snapshot.activeList] ?: ListPage()
        val forwardedSources = messages.values.mapNotNull { it.forwardInfo?.origin?.sourceIdentity() }.toSet()
        val referencedPeers = buildSet {
            (messages.values + listOfNotNull(snapshot.replyTarget, snapshot.editTarget)).forEach { message ->
                senderSource(message.senderId, message.senderIsChat)?.let(::add)
                addAll(visiblePeerSources(message))
                message.forwardInfo?.origin?.sourceIdentity()?.let(::add)
            }
        }
        val metadataChats = referencedPeers.filterIsInstance<ForwardSourceIdentity.Chat>().mapNotNull { source ->
            if (source.chatId in chats) null else chatAppearances[source.chatId]?.let { appearance ->
                forwardSourceNames[source]?.takeIf(String::isNotBlank)?.let { name ->
                    ChatItem(source.chatId, name, canSend = false, appearance = appearance)
                }
            }
        }
        snapshot = snapshot.copy(
            items = chats.values.filter { (it.positions[snapshot.activeList] ?: 0) > 0 }
                .sortedWith(compareByDescending<ChatItem> { it.positions[snapshot.activeList] ?: 0 }.thenByDescending { it.id }),
            knownChats = (chats.values + metadataChats).sortedBy { it.title }.toList(),
            selected = chats[selectedId],
            reportSpam = selectedId?.let { spamState(it).copy(available = spamAvailable(chats[it])) } ?: ChatReportSpamState(),
            replyTarget = snapshot.replyTarget?.id?.let(::replyableMessage),
            messages = messages.values.sortedBy { it.id }.map { message ->
                val available = retryAllowedAt[MessageKey(message.chatId, message.id)]
                if (available == null) message else message.copy(
                    retryAfterSeconds = ((available - nowMillis()).coerceAtLeast(0).plus(999) / 1_000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                )
            },
            retryingIds = retryRequests.keys.filter { it.chatId == selectedId }.map { it.messageId }.toSet(),
            senderNames = names.toMap(),
            senderAppearances = userAppearances.filterKeys { ForwardSourceIdentity.User(it) in referencedPeers },
            forwardSourceNames = forwardSourceNames.filterKeys { it in forwardedSources },
            senderAvatars = userAvatars.filterKeys { it in messageSenders },
            headerStatus = ChatHeaderStatus(memberCount = chats[selectedId]?.memberCount,
                onlineCount = onlineMemberCount, activity = headerActivities.values.lastOrNull()),
            listLoading = page.request != null,
            listEnd = page.end,
            listError = page.error,
            mediaFiles = files.filterKeys { it in knownFileIds },
            fileErrors = fileErrors.toMap(),
            downloadingFiles = (fileRequests.keys + ownedFullRequests.keys.map { it.second } +
                files.values.filter { it.id in knownFileIds && it.downloading }.map { it.id }).toSet(),
            stickerOutlines = stickerOutlines.toMap(),
            reactionVisuals = reactionVisuals.toMap(),
            reactionVisualLoading = reactionVisualRequests.keys.toSet(),
            reactionVisualErrors = reactionVisualErrors.toSet(),
        )
        publish(snapshot)
        // A bounded completion can make room for another already-referenced peer.
        if (isAccountAllowed()) {
            messages.values.toList().forEach(::resolveForwardSource)
            resolveVisiblePeerMetadata()
        }
    }

    private fun <T> safely(request: ((ChatResult<T>) -> Unit) -> Unit, completed: (ChatResult<T>) -> Unit) {
        try {
            request { result -> enqueue { completed(result) } }
        } catch (_: Exception) {
            enqueue { completed(ChatResult(error = ChatError.REQUEST_FAILED)) }
        }
    }

    private fun <T> errorOf(result: ChatResult<T>): ChatError? =
        result.error ?: if (result.value == null) ChatError.REQUEST_FAILED else null

    private data class MessageKey(val chatId: Long, val messageId: Long)
    private class SupportOpening(val active: () -> Boolean, val completed: (SettingsResult<ChatToken>) -> Unit) {
        val owner = AtomicBoolean(true)
        val delivered = AtomicBoolean()
    }
    private class TextSendBatch(val chatId: Long, val original: RichMessageText, val draftRevision: Long,
        val reply: OutgoingReply?, val replyOwner: Long, var navigationRevision: Long) {
        var parts: List<RichMessageText> = emptyList()
        var nextPart: Int = 0
        var attemptStartPart: Int = 0
        override fun toString() = "TextSendBatch(<redacted>)"
    }
    private class CapturedReply(val reply: OutgoingReply?, val owner: Long)
    private data class HeaderActivityKey(val senderId: Long, val senderIsChat: Boolean) {
        override fun toString() = "HeaderActivityKey(<redacted>)"
    }
    private data class ContentOverride(val text: String, val kind: MessageKind, val media: MessageMedia?, val entities: List<MessageEntity>,
        val showCaptionAboveMedia: Boolean = false, val linkPreviewOptions: MessageLinkPreviewOptions? = null,
        val linkPreview: MessageLinkPreview? = null) {
        constructor(event: ChatEvent.Content) : this(event.text, event.kind, event.media, event.entities,
            event.showCaptionAboveMedia, event.linkPreviewOptions, event.linkPreview)
        fun applyTo(message: ChatMessage) = message.copy(text = text, kind = kind, media = media, entities = entities,
            showCaptionAboveMedia = showCaptionAboveMedia, linkPreviewOptions = linkPreviewOptions, linkPreview = linkPreview)
        override fun toString() = "ContentOverride(<redacted>)"
    }
    private data class ListPage(val request: Long? = null, val end: Boolean = false, val error: ChatError? = null)

    private companion object {
        const val PAGE_SIZE = 50
        const val MAX_STICKER_SECTION_REQUESTS = 2
        const val MAX_STICKER_OUTLINE_REQUESTS = 32
        const val MAX_STICKER_OUTLINES = 128
        const val MAX_STICKER_OUTLINE_LENGTH = 65_536
        const val MAX_HISTORY_BATCHES = 100
        const val MAX_TEXT_LENGTH = 4096
        const val MAX_TEXT_BATCH_UNITS = 1_048_576
        const val MAX_TEXT_BATCH_TOTAL_UNITS = 4_194_304
        const val MAX_TEXT_BATCH_PARTS = 256
        const val MAX_TEXT_BATCHES = 8
        const val MAX_CAPTION_LENGTH = 1024
        const val HEADER_ACTIVITY_TIMEOUT_MILLIS = 6_000L
    }
}
