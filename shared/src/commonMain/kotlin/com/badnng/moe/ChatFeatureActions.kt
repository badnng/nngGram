package com.badnng.moe

import com.badnng.moe.chat.*
import androidx.compose.runtime.Immutable

/** UI actions carry the current account/selection token; the engine rechecks it before dispatch. */
@Immutable
data class ChatFeatureActions(
    val folders: com.badnng.moe.folders.FolderSettingsActions = com.badnng.moe.folders.FolderSettingsActions(),
    val chatListAction: (ChatToken, Long, ChatListRef, ChatListAction, (ChatResult<Unit>) -> Unit) -> Unit =
        { _, _, _, _, result -> result(ChatResult(error = ChatError.UNSUPPORTED)) },
    val addChatToFolder: (ChatToken, Long, Int, (ChatResult<Unit>) -> Unit) -> Unit =
        { _, _, _, result -> result(ChatResult(error = ChatError.UNSUPPORTED)) },
    val requestMessageLink: (ChatToken, Long, (ChatResult<String>) -> Unit) -> Unit =
        { _, _, result -> result(ChatResult(error = ChatError.UNSUPPORTED)) },
    val selectList: (ChatToken, ChatListRef) -> Unit = { _, _ -> },
    val visibleMessages: (ChatToken, List<Long>, Boolean) -> Unit = { _, _, _ -> },
    val markUnread: (ChatToken) -> Unit = {},
    val downloadFile: (ChatToken, Int) -> Unit = { _, _ -> },
    val acquireOwnedMedia: (MediaDownloadOwner, Int) -> Unit = { owner, id -> downloadFile(owner.token, id) },
    val releaseOwnedMedia: (MediaDownloadOwner, Int) -> Unit = { _, _ -> },
    val automaticMedia: (ChatToken, Long, Boolean) -> Unit = { _, _, _ -> },
    val releaseVideoPart: (ChatToken, Long, Int) -> Unit = { _, _, _ -> },
    val openOwnedVideoPart: (MediaDownloadOwner, Int) -> Unit = { _, _ -> },
    val readOwnedVideoPart: (MediaDownloadOwner, Int, Long, Int, (ChatResult<ByteArray>) -> Unit) -> Unit =
        { _, _, _, _, result -> result(ChatResult(error = ChatError.UNSUPPORTED)) },
    val releaseOwnedVideoPart: (MediaDownloadOwner, Int) -> Unit = { _, _ -> },
    /** Admission is distinct from the persisted policy/revision published by the controller. */
    val saveAutoMedia: (ChatToken, Long, AutoMediaPolicy, (Boolean) -> Unit, (Boolean) -> Unit) -> Unit =
        { _, _, _, admitted, _ -> admitted(false) },
    val loadStickerOutline: (ChatToken, Int) -> Unit = { _, _ -> },
    val loadStickers: (ChatToken, Long?) -> Unit = { _, _ -> },
    val ensureStickerSection: (ChatToken, Long?) -> Unit = { _, _ -> },
    val sendSticker: (ChatToken, StickerItem) -> Unit = { _, _ -> },
    val sendPhoto: (ChatToken, String, RichMessageText) -> Unit = { _, _, _ -> },
    val sendOwnedPhoto: (ChatToken, String, RichMessageText, AttachmentLease) -> Unit = { token, path, caption, _ -> sendPhoto(token, path, caption) },
    val submitClipboardPhoto: (ChatToken, String, RichMessageText, AttachmentLease?, (ChatResult<Unit>) -> Unit) -> Unit =
        { _, _, _, _, completed -> completed(ChatResult(error = ChatError.UNSUPPORTED)) },
    val submitClipboardFile: (ChatToken, LocalAttachment, RichMessageText, (ChatResult<Unit>) -> Unit) -> Unit =
        { _, _, _, completed -> completed(ChatResult(error = ChatError.UNSUPPORTED)) },
    val sendVoiceNote: (ChatToken, VoiceNoteDraft) -> Unit = { _, _ -> },
    val sendAttachment: (ChatToken, LocalAttachment, RichMessageText) -> Unit = { _, _, _ -> },
    val setReply: (ChatToken, Long?) -> Unit = { _, _ -> },
    val setQuote: (ChatToken, Long, MessageQuote) -> Unit = { _, _, _ -> },
    val loadReactions: (ChatToken, Long) -> Unit = { _, _ -> },
    val closeReactions: (ChatToken) -> Unit = {},
    val setReaction: (ChatToken, Long, String, Boolean) -> Unit = { _, _, _, _ -> },
    val setCustomReaction: (ChatToken, Long, Long, Boolean) -> Unit = { _, _, _, _ -> },
    val loadReactionVisual: (ChatToken, ReactionKey) -> Unit = { _, _ -> },
    val loadReactionPacks: (ChatToken, Long?) -> Unit = { _, _ -> },
    val loadReactionGroupPack: (ChatToken, Long?) -> Unit = { _, _ -> },
    val readVideoPart: (ChatToken, Long, Int, Long, Int, (ChatResult<ByteArray>) -> Unit) -> Unit = { _, _, _, _, _, result -> result(ChatResult(error = ChatError.UNSUPPORTED)) },
    val loadReadDetails: (ChatToken, Long) -> Unit = { _, _ -> },
    val closeReadDetails: (ChatToken) -> Unit = {},
    val loadMessageActions: (ChatToken, Long) -> Unit = { _, _ -> },
    val closeMessageActions: (ChatToken) -> Unit = {},
    val startEdit: (ChatToken, Long) -> Unit = { _, _ -> },
    val cancelEdit: (ChatToken) -> Unit = {},
    val editMessage: (ChatToken, RichMessageText) -> Unit = { _, _ -> },
    val deleteMessage: (ChatToken, Long, Boolean) -> Unit = { _, _, _ -> },
    val forwardMessage: (ChatToken, Long, Long) -> Unit = { _, _, _ -> },
    val editFolder: (ChatToken, Int) -> Unit = { _, _ -> },
    val closeFolderEditor: (ChatToken) -> Unit = {},
    val saveFolder: (ChatToken, ChatFolder) -> Unit = { _, _ -> },
    val deleteFolder: (ChatToken, Int) -> Unit = { _, _ -> },
    val search: (ChatToken, ChatSearchQuery, Boolean) -> Unit = { _, _, _ -> },
    val searchOverview: (ChatToken) -> Unit = {},
    val clearRecentSearch: (ChatToken) -> Unit = {},
    val closeSearch: (ChatToken) -> Unit = {},
    val openSearchMessage: (ChatToken, Long, Long) -> Unit = { _, _, _ -> },
    val loadChatDetails: (ChatToken) -> Unit = {},
    val loadMediaIndex: (ChatToken, ChatMediaCategory, Boolean) -> Unit = { _, _, _ -> },
    val openMediaIndexMessage: (ChatToken, Long) -> Unit = { _, _ -> },
    val loadPinned: (ChatToken, Boolean) -> Unit = { _, _ -> },
    val openPinnedMessage: (ChatToken, Long) -> Unit = { _, _ -> },
    val selectSearchChat: (ChatToken, Long) -> Unit = { _, _ -> },
    val returnToLatest: (ChatToken) -> Unit = {},
    val openHistoryMessage: (ChatToken, Long) -> Unit = { _, _ -> },
    val rememberHistoryAnchor: (ChatToken, Long) -> Unit = { _, _ -> },
    val loadHistoryAnchor: (ChatToken, Long) -> Unit = { _, _ -> },
    val openReplyMessage: (ChatToken, Long) -> Unit = { _, _ -> },
    val openReplyReturn: (ChatToken, Long, Long) -> Unit = { _, _, _ -> },
    val requestPreviousEditable: (ChatToken, Long?, (ChatResult<Long>) -> Unit) -> Unit =
        { _, _, completed -> completed(ChatResult(error = ChatError.UNSUPPORTED)) },
    val loadNewer: (ChatToken) -> Unit = {},
    val completeHistoryJump: (ChatToken, Long) -> Unit = { _, _ -> },
    val cancelSendLatestNavigation: (ChatToken) -> Unit = {},
    val reportSpamAndLeave: (ChatToken) -> Unit = {},
    val dismissSpamBar: (ChatToken) -> Unit = {},
)
