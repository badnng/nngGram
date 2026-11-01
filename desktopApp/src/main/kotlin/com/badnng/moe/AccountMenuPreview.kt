package com.badnng.moe

import androidx.compose.runtime.Composable
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
import com.badnng.moe.status.AuthorizationPhase
import com.badnng.moe.status.ClientStatus
import com.badnng.moe.status.ConnectionPhase
import com.badnng.moe.status.EnginePhase
import com.badnng.moe.status.RuntimeSnapshot

/** Explicit synthetic UI fixture: no configuration, account engine, disk state or network client. */
@Composable
internal fun AccountMenuPreview(windowFocused: Boolean) {
    val allChats = remember {
        listOf(
            previewChat(101, "界面预览", 300, 1),
            previewChat(102, "设计交流", 200, 2),
            previewChat(103, "中文输入测试", 100, 1),
            previewChat(104, "我的收藏", 50, 1).copy(privateUserId = 900001),
        )
    }
    var snapshot by remember {
        mutableStateOf(ChatSnapshot(
            enabled = true,
            token = ChatToken(1, 1, allChats.first().id),
            account = AccountProfile(900001, "预览用户", "preview_user", "00000000000"),
            items = allChats,
            knownChats = allChats,
            selected = allChats.first(),
            messages = listOf(previewMessage(allChats.first().id)),
            listEnd = true,
            historyEnd = true,
            folders = listOf(ChatFolderInfo(1, "日常"), ChatFolderInfo(2, "工作")),
        ))
    }
    App(
        persistUiPreferences = false,
        runtimeInfo = RuntimeInfo("离线头像菜单预览", "合成数据"),
        runtime = RuntimeSnapshot(
            status = ClientStatus(EnginePhase.INITIALIZED, AuthorizationPhase.READY, ConnectionPhase.READY),
            configPath = "离线预览 · 不读取本地配置",
            note = "合成界面预览，可测试头像菜单、主题、窗口缩放和中文输入。消息只保存在当前预览内存中。",
            clientActive = true,
            generation = 1,
            eventLogAvailable = false,
            chats = snapshot,
        ),
        windowFocused = windowFocused,
        onSelectChat = { token, chatId ->
            if (token == snapshot.token) {
                val selected = allChats.firstOrNull { it.id == chatId }
                if (selected != null) snapshot = snapshot.copy(
                    token = token.copy(selection = token.selection + 1, chatId = chatId),
                    selected = selected,
                    messages = listOf(previewMessage(chatId)),
                )
            }
        },
        onSendText = { token, content, _ ->
            // Local acknowledgment exercises draft clearing without calling any real engine.
            val chatId = token.chatId
            if (token == snapshot.token && chatId != null && content.text.isNotBlank()) {
                val nextId = snapshot.acceptedSendId + 1
                snapshot = snapshot.copy(
                    messages = snapshot.messages + ChatMessage(
                        id = 1000 + nextId,
                        chatId = chatId,
                        outgoing = true,
                        sendAnimationId = nextId,
                        text = content.text,
                        entities = content.entities,
                    ),
                    acceptedSendId = nextId,
                    acceptedSendChatId = chatId,
                    acceptedSendText = content.text,
                    acceptedSendEntities = content.entities,
                )
            }
        },
        features = ChatFeatureActions(selectList = { token, list ->
            snapshot = selectPreviewChatList(snapshot, token, list)
        }),
    )
}

private fun previewChat(id: Long, title: String, order: Long, folderId: Int) = ChatItem(
    id = id,
    title = title,
    order = order,
    lastMessage = previewMessage(id),
    positions = mapOf(ChatListRef() to order, ChatListRef(folderId) to order),
    canSendPhotos = false,
    canSendStickers = false,
    canSendVideos = false,
    canSendDocuments = false,
)

private fun previewMessage(chatId: Long) = ChatMessage(
    id = chatId,
    chatId = chatId,
    text = "这里是离线界面预览。可点击顶部头像切换夜间模式、打开设置，并在这里测试中文输入。",
)
