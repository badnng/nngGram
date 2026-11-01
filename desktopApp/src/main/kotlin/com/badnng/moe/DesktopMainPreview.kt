package com.badnng.moe

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.badnng.moe.chat.AccountProfile
import com.badnng.moe.chat.ChatFolderInfo
import com.badnng.moe.chat.ChatItem
import com.badnng.moe.chat.ChatListRef
import com.badnng.moe.chat.ChatMessage
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.ChatToken
import com.badnng.moe.chat.MediaFile
import com.badnng.moe.chat.MediaFormat
import com.badnng.moe.chat.MessageEntity
import com.badnng.moe.chat.MessageEntityKind
import com.badnng.moe.chat.MessageKind
import com.badnng.moe.chat.MessageReaction
import com.badnng.moe.chat.MessageReactionOptions
import com.badnng.moe.chat.MessageReply
import com.badnng.moe.chat.MessageMedia
import com.badnng.moe.chat.ChatHeaderStatus
import com.badnng.moe.chat.ChatPinnedState
import com.badnng.moe.chat.ChatSearchState
import com.badnng.moe.chat.ChatSearchCategory
import com.badnng.moe.chat.ChatDetailsState
import com.badnng.moe.chat.ChatDetails
import com.badnng.moe.chat.ChatAdministrator
import com.badnng.moe.chat.ChatFolder
import com.badnng.moe.chat.MessageActions
import com.badnng.moe.chat.MessageReadDetails
import com.badnng.moe.chat.MessageReadAvailability
import com.badnng.moe.chat.MessageReader
import com.badnng.moe.chat.ChatUnreadBoundary
import com.badnng.moe.chat.ChatListAction
import com.badnng.moe.chat.ChatResult
import com.badnng.moe.chat.ChatMediaIndexState
import com.badnng.moe.chat.StickerItem
import com.badnng.moe.chat.StickerSection
import com.badnng.moe.chat.StickerPack
import com.badnng.moe.status.AuthorizationPhase
import com.badnng.moe.status.ClientStatus
import com.badnng.moe.status.ConnectionPhase
import com.badnng.moe.status.EnginePhase
import com.badnng.moe.status.RuntimeSnapshot
import java.time.LocalDate
import java.time.ZoneId
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

/** Opt-in synthetic desktop fixture. Everything, including sent text, stays in this composition. */
@Composable
internal fun DesktopMainPreview(windowFocused: Boolean, dark: Boolean = false, unread: Boolean = false, startInList: Boolean = false) {
    val fixture = remember(unread) { DesktopMainFixture(unread) }
    DisposableEffect(fixture) { onDispose { fixture.close() } }
    var conversations by remember { mutableStateOf(fixture.conversations) }
    var snapshot by remember {
        mutableStateOf(ChatSnapshot(
            enabled = true,
            token = ChatToken(1, 1, fixture.chats.first().id.takeUnless { startInList }),
            account = AccountProfile(900001, "nngGram", "nnggram_preview"),
            items = fixture.chats,
            knownChats = fixture.chats,
            selected = fixture.chats.first().takeUnless { startInList },
            messages = if (startInList) emptyList() else conversations.getValue(fixture.chats.first().id),
            senderNames = mapOf(900001L to "你", 900002L to "设计伙伴", 900003L to "小窗"),
            listEnd = true,
            historyEnd = true,
            headerStatus = ChatHeaderStatus(memberCount = 3, onlineCount = 1),
            pinned = ChatPinnedState(messages = fixture.conversations.getValue(fixture.chats.first().id).take(1), totalCount = 1),
            unreadBoundary = if (unread) ChatUnreadBoundary(1001, 1003) else null,
            stickers = fixture.stickers,
            stickerSets = fixture.stickerSets,
            stickerSections = listOf(StickerSection(null, "最近", fixture.stickers, loaded = true)) +
                fixture.stickerSets.map { pack -> StickerSection(pack.id, pack.title,
                    fixture.stickers.filter { it.setId == pack.id }, loaded = true) },
            jumpMessageId = if (unread) 1003 else null,
            folders = listOf(
                ChatFolderInfo(1, "工作", "Work"),
                ChatFolderInfo(2, "群组", "Groups"),
                ChatFolderInfo(3, "频道", "Channels"),
            ),
            unreadChats = mapOf(ChatListRef() to 3, ChatListRef(1) to 2, ChatListRef(2) to 2, ChatListRef(3) to 1),
        ))
    }

    val previewSelect: (Long) -> Unit = { chatId ->
        snapshot.knownChats.firstOrNull { it.id == chatId }?.let { selected ->
            snapshot = snapshot.copy(token = snapshot.token.copy(selection = snapshot.token.selection + 1, chatId = chatId),
                selected = selected, messages = conversations[chatId].orEmpty(), replyTarget = null, replyQuote = null,
                jumpMessageId = null, details = ChatDetailsState(), mediaIndex = ChatMediaIndexState(),
                headerStatus = ChatHeaderStatus(memberCount = selected.memberCount, onlineCount = selected.memberCount?.let { 1 }),
                pinned = ChatPinnedState(messages = if (chatId == fixture.chats.first().id) conversations[chatId].orEmpty().take(1) else emptyList(),
                    totalCount = if (chatId == fixture.chats.first().id) 1 else 0))
        }
    }

    App(
        persistUiPreferences = false,
        runtimeInfo = RuntimeInfo("离线桌面主界面预览", "合成数据"),
        runtime = RuntimeSnapshot(
            status = ClientStatus(EnginePhase.INITIALIZED, AuthorizationPhase.READY, ConnectionPhase.READY),
            configPath = "离线预览 · 不读取本地配置",
            note = "合成界面预览，输入和消息只保存在当前窗口内存中。",
            clientActive = true,
            generation = 1,
            eventLogAvailable = false,
            chats = snapshot,
        ),
        initialTheme = if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light,
        windowFocused = windowFocused,
        onSelectChat = { token, chatId ->
            if (token == snapshot.token) previewSelect(chatId)
        },
        onSendText = { token, content, _ ->
            val chatId = token.chatId
            if (token == snapshot.token && chatId != null && content.text.isNotBlank()) {
                val acceptedId = snapshot.acceptedSendId + 1
                val message = ChatMessage(
                    id = 10000 + acceptedId, chatId = chatId, senderId = 900001,
                    outgoing = true, date = fixture.date + 8 * 60 + acceptedId.toInt(),
                    sendAnimationId = acceptedId,
                    text = content.text, entities = content.entities,
                    replyTo = snapshot.replyTarget?.let {
                        MessageReply(chatId, it.id, it.text, it.senderId, it.senderIsChat,
                            entities = it.entities)
                    },
                )
                val messages = conversations[chatId].orEmpty() + message
                conversations = conversations + (chatId to messages)
                val updatedChats = snapshot.knownChats.map {
                    if (it.id == chatId) it.copy(lastMessage = message, lastReadOutboxId = message.id) else it
                }
                snapshot = snapshot.copy(
                    messages = messages,
                    knownChats = updatedChats,
                    items = updatedChats.filter { (it.positions[snapshot.activeList] ?: 0) > 0 },
                    selected = updatedChats.first { it.id == chatId },
                    acceptedSendId = acceptedId,
                    acceptedSendChatId = chatId,
                    acceptedSendText = content.text,
                    acceptedSendEntities = content.entities,
                    replyTarget = null,
                    replyQuote = null,
                )
            }
        },
        features = ChatFeatureActions(
            loadStickers = { token, setId ->
                if (token == snapshot.token) snapshot = snapshot.copy(selectedStickerSetId = setId,
                    stickers = fixture.stickers.filter { setId == null || it.setId == setId },
                    stickersLoading = false, stickersError = null)
            },
            chatListAction = { token, chatId, list, action, result ->
                if (token == snapshot.token) {
                    val chats = snapshot.knownChats.map { chat -> if (chat.id != chatId) chat else when (action) {
                        ChatListAction.ARCHIVE -> chat.copy(positions = chat.positions - ChatListRef() + (ChatListRef(-1) to chat.order))
                        ChatListAction.UNARCHIVE -> chat.copy(positions = chat.positions - ChatListRef(-1) + (ChatListRef() to chat.order))
                        ChatListAction.PIN -> chat.copy(pinnedLists = chat.pinnedLists + list)
                        ChatListAction.UNPIN -> chat.copy(pinnedLists = chat.pinnedLists - list)
                        ChatListAction.MUTE -> chat.copy(isMuted = true)
                        ChatListAction.UNMUTE -> chat.copy(isMuted = false)
                        ChatListAction.MARK_READ -> chat.copy(unreadCount = 0, markedUnread = false)
                        ChatListAction.MARK_UNREAD -> chat.copy(markedUnread = true)
                    } }
                    snapshot = snapshot.copy(knownChats = chats, items = chats.filter { (it.positions[snapshot.activeList] ?: 0) > 0 },
                        selected = chats.firstOrNull { it.id == token.chatId })
                    result(ChatResult(Unit))
                }
            },
            clearRecentSearch = { token -> if (token == snapshot.token) snapshot = snapshot.copy(search = snapshot.search.copy(recent = emptyList())) },
            editFolder = { token, id -> if (token == snapshot.token) snapshot = snapshot.copy(
                folderEditorId = id, folderEditor = ChatFolder(id, snapshot.folders.firstOrNull { it.id == id }?.name.orEmpty(),
                    includedChatIds = snapshot.knownChats.filter { (it.positions[ChatListRef(id)] ?: 0) > 0 }.map { it.id }),
                folderRevision = snapshot.folderRevision + 1) },
            closeFolderEditor = { token -> if (token == snapshot.token) snapshot = snapshot.copy(
                folderEditorId = null, folderEditor = null, folderRevision = snapshot.folderRevision + 1) },
            loadMessageActions = { token, id -> if (token == snapshot.token) {
                val message = snapshot.messages.firstOrNull { it.id == id }
                snapshot = snapshot.copy(actionsMessageId = id, actionsLoading = false,
                    messageActions = MessageActions(canEditText = message?.outgoing == true && message.text.isNotEmpty(),
                        canForward = true, canDeleteForSelf = true, canDeleteForAll = message?.outgoing == true))
            } },
            closeMessageActions = { token -> if (token == snapshot.token) snapshot = snapshot.copy(actionsMessageId = null, messageActions = null) },
            loadReadDetails = { token, id -> if (token == snapshot.token) snapshot = snapshot.copy(readDetailsMessageId = id,
                readDetails = MessageReadDetails(listOf(MessageReader(900002, "设计伙伴", fixture.date + 9 * 60)), MessageReadAvailability.AVAILABLE)) },
            closeReadDetails = { token -> if (token == snapshot.token) snapshot = snapshot.copy(readDetailsMessageId = null, readDetails = null) },
            searchOverview = { token -> if (token == snapshot.token) snapshot = snapshot.copy(search = snapshot.search.copy(
                frequent = fixture.chats.take(4), recent = fixture.chats)) },
            search = { token, query, _ -> if (token == snapshot.token) {
                val found = conversations.values.flatten().filter { message ->
                    (query.chatId == null || message.chatId == query.chatId) &&
                        (query.senderId == null || message.senderId == query.senderId) &&
                        (query.minDate == 0 || message.date >= query.minDate) && (query.maxDate == 0 || message.date <= query.maxDate) &&
                        (query.text.isBlank() || message.text.contains(query.text, true) || message.media?.fileName?.contains(query.text, true) == true) &&
                        when (query.category) {
                            ChatSearchCategory.PHOTOS -> message.kind == MessageKind.PHOTO
                            ChatSearchCategory.VIDEOS -> message.kind == MessageKind.VIDEO
                            ChatSearchCategory.FILES -> message.kind == MessageKind.FILE
                            ChatSearchCategory.LINKS -> message.text.contains("https://")
                            ChatSearchCategory.VOICE -> message.kind == MessageKind.VOICE
                            ChatSearchCategory.CHANNELS, ChatSearchCategory.APPS, ChatSearchCategory.MUSIC -> false
                            else -> true
                        }
                }
                snapshot = snapshot.copy(search = snapshot.search.copy(query = query,
                    chats = if (query.chatId == null && query.text.isNotBlank()) fixture.chats.filter { it.title.contains(query.text, true) } else emptyList(),
                    messages = if (query.text.isBlank() && query.category == ChatSearchCategory.CHATS && query.senderId == null && query.minDate == 0 && query.maxDate == 0) emptyList() else found,
                    revision = snapshot.search.revision + 1, totalCount = found.size, loading = false))
            } },
            closeSearch = { token -> if (token == snapshot.token) snapshot = snapshot.copy(search = ChatSearchState()) },
            selectSearchChat = { token, chatId -> if (token == snapshot.token) previewSelect(chatId) },
            openSearchMessage = { token, chatId, messageId -> if (token == snapshot.token) {
                if (chatId != token.chatId) previewSelect(chatId)
                snapshot = snapshot.copy(jumpMessageId = messageId)
            } },
            openHistoryMessage = { token, messageId -> if (token == snapshot.token) snapshot = snapshot.copy(jumpMessageId = messageId) },
            openPinnedMessage = { token, messageId -> if (token == snapshot.token) snapshot = snapshot.copy(jumpMessageId = messageId) },
            returnToLatest = { token -> if (token == snapshot.token) previewSelect(token.chatId ?: fixture.chats.first().id) },
            loadChatDetails = { token -> if (token == snapshot.token && token.chatId != null) snapshot = snapshot.copy(details = ChatDetailsState(
                details = ChatDetails(checkNotNull(token.chatId), "讨论桌面界面与交互细节。此页面使用合成数据验证布局和动画。",
                    listOf(ChatAdministrator(900002, "设计伙伴", "设计", owner = true), ChatAdministrator(900003, "小窗", "开发"))))) },
            loadMediaIndex = { token, category, more -> snapshot = fixture.mediaIndex.load(snapshot, token, category, more) },
            openMediaIndexMessage = { token, id ->
                snapshot = fixture.mediaIndex.open(snapshot, token, id)
                if (token == snapshot.token && snapshot.jumpMessageId == id) {
                    token.chatId?.let { conversations = conversations + (it to snapshot.messages) }
                }
            },
            selectList = { token, list ->
                snapshot = selectPreviewChatList(snapshot, token, list)
            },
            markUnread = { token ->
                if (token == snapshot.token) {
                    val chats = snapshot.knownChats.map {
                        if (it.id == token.chatId) it.copy(markedUnread = true) else it
                    }
                    snapshot = snapshot.copy(
                        knownChats = chats,
                        items = chats.filter { (it.positions[snapshot.activeList] ?: 0) > 0 },
                        selected = chats.firstOrNull { it.id == token.chatId },
                    )
                }
            },
            setReply = { token, messageId ->
                if (token == snapshot.token) snapshot = snapshot.copy(
                    replyTarget = snapshot.messages.firstOrNull { it.id == messageId }, replyQuote = null,
                )
            },
            setQuote = { token, messageId, quote ->
                if (token == snapshot.token) snapshot = snapshot.copy(
                    replyTarget = snapshot.messages.firstOrNull { it.id == messageId }, replyQuote = quote,
                )
            },
            loadReactions = { token, messageId ->
                if (token == snapshot.token) snapshot = snapshot.copy(
                    reactionMessageId = messageId,
                    reactionOptions = MessageReactionOptions(listOf("👍", "❤", "🔥", "🎉", "🥰", "👏", "😁", "🤔", "🤯", "😱", "😢", "🤮", "💩", "🙏", "👌", "🕊", "🤡", "🥱", "🥴", "😍", "🐳", "❤️‍🔥", "🌚", "💯", "⚡", "🍌", "🏆", "💔", "🤨", "😐", "🍓", "🍾", "💋", "🖕", "😈", "😴", "😭", "🤓", "👻", "👨‍💻", "👀", "🎃", "🙈", "😇", "😨", "🤝", "✍", "🤗", "🫡", "🎅", "🎄", "☃", "💅", "🤪", "🗿", "🆒", "💘", "🙉", "🦄", "😘", "💊", "🙊", "😎", "👾", "🤷‍♂", "😡"), canAdd = true),
                )
            },
            closeReactions = { token ->
                if (token == snapshot.token) snapshot = snapshot.copy(reactionMessageId = null, reactionOptions = null)
            },
            setReaction = { token, messageId, emoji, add ->
                if (token == snapshot.token) {
                    val messages = snapshot.messages.map { message ->
                        if (message.id != messageId) message else {
                            val old = message.reactions.firstOrNull { it.emoji == emoji }
                            val count = (old?.totalCount ?: 0) + when {
                                add && old?.isChosen != true -> 1
                                !add && old?.isChosen == true -> -1
                                else -> 0
                            }
                            val reactions = message.reactions.filterNot { it.emoji == emoji } +
                                if (count > 0) listOf(MessageReaction(emoji, count, add)) else emptyList()
                            message.copy(reactions = reactions)
                        }
                    }
                    token.chatId?.let { conversations = conversations + (it to messages) }
                    snapshot = snapshot.copy(messages = messages, reactionMessageId = null, reactionOptions = null)
                }
            },
        ),
    )
}

/** Synthetic list navigation follows the live controller without replacing conversation state. */
internal fun selectPreviewChatList(snapshot: ChatSnapshot, token: ChatToken, list: ChatListRef): ChatSnapshot {
    if (!snapshot.enabled || token != snapshot.token || list == snapshot.activeList || list.folderId < -1 ||
        (list.folderId > 0 && snapshot.folders.none { it.id == list.folderId })) return snapshot
    return snapshot.copy(activeList = list,
        items = snapshot.knownChats.filter { (it.positions[list] ?: 0) > 0 }
            .sortedWith(compareByDescending<ChatItem> { it.positions[list] ?: 0 }.thenByDescending { it.id }))
}

private class DesktopMainFixture(private val unread: Boolean = false) : AutoCloseable {
    private val mediaAssets = DesktopPreviewMediaAssets.create()
    // Explicitly synthetic owned PNGs show picker geometry, never official sticker fidelity.
    val stickers = mediaAssets.photos.mapIndexed { index, file ->
        val emoji = listOf("☀️", "🌅", "🌿", "🌙", "🌊", "⭐")[index]
        StickerItem(300_000L + index, 301L + index % 2, emoji,
            MessageMedia(file, width = 720, height = 480, emoji = emoji, setId = 301L + index % 2))
    }
    val stickerSets = listOf(StickerPack(301, "合成布局样例 A", 3), StickerPack(302, "合成布局样例 B", 3))
    val date = LocalDate.now().atTime(14, 32).atZone(ZoneId.systemDefault()).toEpochSecond().toInt()
    private val mainId = 98L
    private val code = "Column {\n    ChatList()\n    MessageTimeline()\n}"
    private val mainMessages = listOf(
        ChatMessage(1001, mainId, senderId = 900002, date = date,
            text = "主界面的间距调过了，会话列表更清楚了。\n浅色先用这一版，你看看效果。"),
        ChatMessage(1002, mainId, senderId = 900001, outgoing = true, date = date + 2 * 60,
            text = "收到，我来把列表、消息区和输入框一起对齐。\n常用操作放近一点，阅读空间也会更舒服。",
            replyTo = MessageReply(mainId, 1001, "主界面的间距调过了，会话列表更清楚了。", senderId = 900002)),
        ChatMessage(1003, mainId, senderId = 900003, date = date + 3 * 60,
            text = "主区域按这个结构排就好：\n$code",
            entities = listOf(MessageEntity("主区域按这个结构排就好：\n".length, code.length, MessageEntityKind.PRE, "Kotlin"))),
        ChatMessage(1004, mainId, senderId = 900003, date = date + 4 * 60,
            kind = MessageKind.FILE,
            media = MessageMedia(
                // Metadata only: synthetic files are never downloadable or associated with local paths.
                file = MediaFile(0, size = 2_400_000), format = MediaFormat.FILE,
                fileName = "桌面交互说明.pdf", mimeType = "application/pdf",
            ),
            reactions = listOf(MessageReaction("❤", 3, true))),
        ChatMessage(1005, mainId, senderId = 900001, outgoing = true, date = date + 6 * 60,
            text = "这版就按这个方向继续，细节我来补齐。"),
    )
    val mediaIndex = DesktopPreviewMediaIndex(mapOf(mainId to desktopPreviewMediaCatalog(mainId, date, mediaAssets,
        mainMessages.filter { it.kind == MessageKind.FILE })))

    override fun close() = mediaAssets.close()
    private val titles = listOf("nngGram 开发组", "Miuix 设计交流", "收藏夹", "桌面应用研究所", "开源周刊", "设计伙伴", "周末计划", "Telegram 服务通知")
    private val snippets = listOf("这版就按这个方向继续，细节我来补齐。", "设计伙伴：组件间距已经同步", "桌面版使用记录", "小窗：附件已经发到群里", "本周值得关注的几个小项目", "好的，稍后我来补充。", "一起去看一场日落吧", "登录成功，欢迎回来")
    val conversations: Map<Long, List<ChatMessage>> = titles.mapIndexed { index, _ ->
        val id = mainId + index
        id to if (index == 0) mainMessages else listOf(ChatMessage(
            id = 2000 + id, chatId = id, senderId = 900002,
            date = when (index) {
                2 -> date - 86_400
                6 -> date - 3 * 86_400
                7 -> date - 4 * 86_400
                else -> date - index * 15 * 60
            }, text = snippets[index],
        ))
    }.toMap()
    val chats = titles.mapIndexed { index, title ->
        val id = mainId + index
        val order = (1000 - index * 10).toLong()
        val folder = when (index) { 0, 3, 5 -> 1; 1, 6 -> 2; 4, 7 -> 3; else -> 0 }
        ChatItem(
            id = id, title = title, order = order,
            unreadCount = when (index) { 0 -> if (unread) 2 else 0; 1 -> 3; 3 -> 12; 4 -> 18; else -> 0 },
            lastReadInboxId = if (unread && index == 0) 1001 else 0,
            lastMessage = conversations.getValue(id).last(),
            lastReadOutboxId = 1005,
            positions = buildMap {
                put(ChatListRef(), order)
                if (folder > 0) put(ChatListRef(folder), order)
                if (index == 7) put(ChatListRef(-1), order)
            },
            privateUserId = if (index == 2) 900001 else null,
            pinnedLists = if (index == 0 || index == 2) setOf(ChatListRef()) else emptySet(),
            basicGroupId = if (index == 0) 800001 else null,
            memberCount = if (index == 0) 3 else null,
            canSendPhotos = true, canSendStickers = true, canSendVideos = true, canSendDocuments = true,
        )
    }
}
