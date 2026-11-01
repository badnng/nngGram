package com.badnng.moe

import com.badnng.moe.chat.*

internal enum class ClipboardQueuePhase { READY, WAITING, FAILED, COMPLETE, STALE }

internal data class ClipboardDispatch(val item: PreparedClipboardItem, val requestId: Long, val caption: RichMessageText)

/** Only the completion belonging to this dispatch advances the batch. Global send counters cannot. */
internal class ClipboardSendQueue(
    val token: ChatToken,
    private val items: List<PreparedClipboardItem>,
    val caption: RichMessageText,
) {
    var index: Int = 0
        private set
    var phase = ClipboardQueuePhase.READY
        private set
    var failure: ChatError? = null
        private set
    private var requestSequence = 0L
    private var waitingRequest: Long? = null
    val remainingItems get() = items.drop(index)

    fun next(snapshot: ChatSnapshot): ClipboardDispatch? {
        observe(snapshot)
        if (phase != ClipboardQueuePhase.READY || snapshot.sendPending) return null
        if (index >= items.size) { phase = ClipboardQueuePhase.COMPLETE; return null }
        val item = items[index]
        val chat = snapshot.selected
        if (chat == null || chat.id != token.chatId || if (item.asPhoto) !chat.canSendPhotos else !chat.canSendDocuments) {
            failure = ChatError.FORBIDDEN
            phase = ClipboardQueuePhase.FAILED
            return null
        }
        waitingRequest = ++requestSequence
        phase = ClipboardQueuePhase.WAITING
        // A shared confirmation caption belongs to the first accepted file only.
        // Failed dispatches keep index zero so an explicit retry still owns it.
        return ClipboardDispatch(item, requestSequence, if (index == 0) caption else RichMessageText(""))
    }

    fun observe(snapshot: ChatSnapshot) {
        if (!snapshot.enabled || snapshot.token != token || snapshot.editTarget != null) {
            phase = ClipboardQueuePhase.STALE
        }
    }

    fun complete(requestId: Long, result: ChatResult<Unit>, snapshot: ChatSnapshot) {
        observe(snapshot)
        if (phase != ClipboardQueuePhase.WAITING || waitingRequest != requestId) return
        waitingRequest = null
        when {
            result.error == null && result.value != null -> {
                items[index].lease?.release()
                index++
                phase = if (index == items.size) ClipboardQueuePhase.COMPLETE else ClipboardQueuePhase.READY
            }
            else -> {
                failure = result.error ?: ChatError.REQUEST_FAILED
                phase = ClipboardQueuePhase.FAILED
            }
        }
    }

    override fun toString() = "ClipboardSendQueue(<redacted>)"
}
