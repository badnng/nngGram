package com.badnng.moe

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.badnng.moe.chat.ChatSnapshot

internal data class ConversationLatestFollowObservation(
    val snapshot: ChatSnapshot,
    val index: Int,
    val offset: Int,
    val scrolling: Boolean,
    val interactive: Boolean,
    val positioning: Boolean,
    val following: Boolean,
) {
    override fun toString() = "ConversationLatestFollowObservation(<redacted>)"
}

/**
 * Bottom-following is an intent, not the index left behind by a keyed list remeasure.
 * Native inserts keep the former last row anchored and may turn index zero into one
 * before the UI observes the new snapshot. Only an actual scroll changes this intent.
 */
internal class ConversationLatestFollowState(initial: ChatSnapshot) {
    private val token = initial.token
    private var sendAttemptRevision = initial.sendAttemptRevision
    private var acceptedSendId = initial.acceptedSendId
    private var jumpRevision = initial.jumpRevision
    private var jumpMessageId = initial.jumpMessageId
    private var following by mutableStateOf(initial.jumpMessageId == null && !initial.historyHasNewer)

    /** Read the pre-insertion intent; keyed remeasurement can already have moved index zero. */
    val followingLatest: Boolean get() = following

    /** Immediate reply/return jumps can finish before isScrollInProgress is observed. */
    fun navigate(atLatest: Boolean) {
        following = atLatest
    }

    /** Only the measured placement of this exact jump can establish its initial bottom. */
    fun completeJump(snapshot: ChatSnapshot, atLatest: Boolean) {
        if (snapshot.token != token || snapshot.jumpMessageId == null) return
        jumpRevision = snapshot.jumpRevision
        jumpMessageId = snapshot.jumpMessageId
        following = atLatest && !snapshot.historyHasNewer
    }

    fun observe(
        snapshot: ChatSnapshot,
        atLatest: Boolean,
        scrolling: Boolean,
        interactive: Boolean,
        positioning: Boolean = snapshot.jumpMessageId != null,
    ): Boolean {
        if (snapshot.token != token) return false
        val newJump = snapshot.jumpMessageId != null &&
            (snapshot.jumpRevision != jumpRevision || snapshot.jumpMessageId != jumpMessageId)
        jumpRevision = snapshot.jumpRevision
        jumpMessageId = snapshot.jumpMessageId
        if (newJump) following = false
        // A fast native callback may replace the pending snapshot before composition.
        // A matching acceptance may complete an unseen *new request*, but may never
        // turn an already observed request into a second bottom-navigation action.
        val acceptedNewRequest = snapshot.acceptedSendId > acceptedSendId &&
            snapshot.acceptedSendChatId == token.chatId
        val sendStarted = snapshot.sendAttemptRevision > sendAttemptRevision &&
            (snapshot.sendPending || acceptedNewRequest) && snapshot.sendErrorChatId == token.chatId
        sendAttemptRevision = snapshot.sendAttemptRevision
        acceptedSendId = snapshot.acceptedSendId

        // Pending navigation and an unloaded newer range own their position. A completed
        // jump marker may remain in a now-contiguous window: preserve its anchor until
        // the reader explicitly scrolls back to the real bottom or sends a new message.
        if (positioning || snapshot.historyHasNewer) {
            following = false
            return false
        }
        if (!interactive) return false

        // Telegram's current-history send action calls fastShowAtEnd. The request
        // revision identifies that action; delivery/acceptance arriving later must
        // not restore following after the reader has deliberately scrolled away.
        if (sendStarted) following = true
        else if (scrolling) following = atLatest

        return following && (!scrolling || sendStarted) && snapshot.enabled && snapshot.selected?.id == token.chatId &&
            !snapshot.historyLoading && !snapshot.newerLoading && snapshot.messages.isNotEmpty()
    }
}
