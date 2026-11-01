package com.badnng.moe

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import com.badnng.moe.chat.ChatMessage
import com.badnng.moe.chat.richText

/** Runtime-only conversation state. Account restarts discard every entry. */
internal data class ConversationReadPosition(
    val chatId: Long,
    val messageId: Long,
    val offset: Int,
    val atLatest: Boolean = false,
) {
    override fun toString() = "ConversationReadPosition(<redacted>)"
}

internal data class ConversationReplyReturn(val destinationChatId: Long, val position: ConversationReadPosition) {
    override fun toString() = "ConversationReplyReturn(<redacted>)"
}

internal class ConversationSessionMemory {
    private val positions = mutableMapOf<Long, ConversationReadPosition>()
    private val restoreOnEntry = mutableMapOf<Long, Boolean>()
    private val requestedPositions = mutableMapOf<Long, ConversationReadPosition>()
    private val requestedJumpRevisions = mutableMapOf<Long, Long>()
    private val editDrafts = mutableMapOf<Triple<Long, Long, Long>, TextFieldValue>()
    private val returns = mutableListOf<ConversationReplyReturn>()
    var replyReturn by mutableStateOf<ConversationReplyReturn?>(null)
        private set
    var returnRevision by mutableStateOf(0L)
        private set

    fun prepareEntry(chatId: Long, restore: Boolean = true) { restoreOnEntry[chatId] = restore }

    fun takeEntryPosition(chatId: Long): ConversationReadPosition? = requestedPositions.remove(chatId)
        ?: positions[chatId]?.takeIf { restoreOnEntry[chatId] != false && !it.atLatest }

    fun requestedPosition(chatId: Long): ConversationReadPosition? = requestedPositions[chatId]
    fun requestedJumpRevision(chatId: Long): Long? = requestedJumpRevisions[chatId]
    fun completePosition(chatId: Long) { requestedPositions.remove(chatId); requestedJumpRevisions.remove(chatId) }
    fun cancelRequestedPositions() { requestedPositions.clear(); requestedJumpRevisions.clear() }

    fun rememberPosition(position: ConversationReadPosition) { positions[position.chatId] = position }

    fun editValue(message: ChatMessage, owner: Long = 0): TextFieldValue =
        editDrafts[Triple(message.chatId, message.id, owner)] ?: message.richText().editorValue()

    fun rememberEdit(message: ChatMessage, value: TextFieldValue, owner: Long = 0) {
        val key = Triple(message.chatId, message.id, owner)
        editDrafts.keys.removeAll { it.first == message.chatId && it != key }
        editDrafts[key] = value
    }

    fun clearEdit(chatId: Long, messageId: Long, owner: Long? = null) {
        editDrafts.keys.removeAll { it.first == chatId && it.second == messageId && (owner == null || it.third == owner) }
    }

    fun pushReplyReturn(destinationChatId: Long, position: ConversationReadPosition) {
        positions[position.chatId] = position
        restoreOnEntry[destinationChatId] = false
        returns += ConversationReplyReturn(destinationChatId, position)
        while (returns.size > 32) returns.removeAt(0)
        replyReturn = returns.last()
    }

    fun beginReplyReturn(destinationChatId: Long? = null, jumpRevision: Long? = null): ConversationReadPosition? {
        val entry = returns.lastOrNull()?.takeIf {
            destinationChatId == null || it.destinationChatId == destinationChatId
        } ?: return null
        returns.removeAt(returns.lastIndex)
        requestedPositions[entry.position.chatId] = entry.position
        if (jumpRevision != null) requestedJumpRevisions[entry.position.chatId] = jumpRevision
        else requestedJumpRevisions.remove(entry.position.chatId)
        restoreOnEntry[entry.position.chatId] = false
        replyReturn = returns.lastOrNull()
        returnRevision++
        return entry.position
    }
}
