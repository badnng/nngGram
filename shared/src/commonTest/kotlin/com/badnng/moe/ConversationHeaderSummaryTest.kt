package com.badnng.moe

import com.badnng.moe.chat.*
import kotlin.test.Test
import kotlin.test.assertEquals

class ConversationHeaderSummaryTest {
    private val chat = ChatItem(1, "Synthetic group")

    @Test fun unknownCountsUseGenericSummaryInsteadOfInventingMembers() {
        assertEquals("聊天", conversationHeaderSummary(chat, ChatSnapshot()))
        assertEquals("3 位成员", conversationHeaderSummary(chat,
            ChatSnapshot(headerStatus = ChatHeaderStatus(memberCount = 3))))
        assertEquals("3 位成员，1 位在线", conversationHeaderSummary(chat,
            ChatSnapshot(headerStatus = ChatHeaderStatus(memberCount = 3, onlineCount = 1))))
    }

    @Test fun nativeActivityTakesPrecedenceAndResolvesTheCorrectSenderIdentity() {
        val status = ChatHeaderStatus(memberCount = 3, activity = ChatActivity(
            ChatActivityKind.TYPING, senderId = 7, expiresAtMillis = 1000))
        val snapshot = ChatSnapshot(headerStatus = status, senderNames = mapOf(7L to "Synthetic user"),
            knownChats = listOf(ChatItem(7, "Synthetic sender chat")))
        assertEquals("Synthetic user 正在输入", conversationHeaderSummary(chat, snapshot))
        assertEquals("Synthetic sender chat 正在输入", conversationHeaderSummary(chat,
            snapshot.copy(headerStatus = status.copy(activity = status.activity?.copy(senderIsChat = true)))))
    }

    @Test fun uploadAndStickerStatesUseTheNativeActionRatherThanAStaticSubtitle() {
        listOf(ChatActivityKind.UPLOADING_PHOTO to "正在发送照片", ChatActivityKind.UPLOADING_VIDEO to "正在发送视频",
            ChatActivityKind.UPLOADING_DOCUMENT to "正在发送文件", ChatActivityKind.CHOOSING_STICKER to "正在寻找贴纸").forEach { (kind, text) ->
            assertEquals(text, conversationHeaderSummary(chat, ChatSnapshot(headerStatus = ChatHeaderStatus(
                activity = ChatActivity(kind, senderId = 99, expiresAtMillis = 1000)))))
        }
    }
}
