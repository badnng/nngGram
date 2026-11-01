package com.badnng.moe

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import com.badnng.moe.chat.MessageReaction
import com.badnng.moe.chat.ReactionKey
import kotlin.math.sqrt

/** Initial history and a newly mounted lazy item establish a baseline; only a later addition plays. */
internal class MessageReactionAddition(initial: List<MessageReaction>) {
    private var chosen = initial.chosenKeys()

    fun update(reactions: List<MessageReaction>, active: Boolean): Set<ReactionKey> {
        val next = reactions.chosenKeys()
        val added = if (active) next - chosen else emptySet()
        chosen = next
        return added
    }

    private fun List<MessageReaction>.chosenKeys() = asSequence()
        .filter { it.isChosen && it.canToggle && it.totalCount > 0 }.map { it.key }.toSet()

    override fun toString() = "MessageReactionAddition(<redacted>)"
}

internal val LocalReactionAdditionIntents = staticCompositionLocalOf<ReactionAdditionIntents?> { null }

/** At most one transient local animation; it never changes the server count, selection or send result. */
internal class ReactionAdditionIntents {
    var current by mutableStateOf<ReactionAdditionIntent?>(null)
        private set
    private var pending: Pair<Long, ReactionKey>? = null
    private var sequence = 0L

    fun request(messageId: Long, key: ReactionKey, source: Rect?) {
        if (!key.valid) return
        pending = messageId to key
        current = ReactionAdditionIntent(++sequence, messageId, key, source)
    }

    fun acknowledge(messageId: Long, key: ReactionKey): Boolean {
        if (pending != (messageId to key)) return false
        pending = null
        return true
    }

    fun finish(id: Long) { if (current?.id == id) current = null }
    override fun toString() = "ReactionAdditionIntents(<redacted>)"
}

internal data class ReactionAdditionIntent(val id: Long, val messageId: Long, val key: ReactionKey, val source: Rect?) {
    override fun toString() = "ReactionAdditionIntent(<redacted>)"
}

/** Algebraic port of Telegram Desktop Ui::ComputeFlyParabola; its peak stays above both endpoints. */
internal fun reactionFlightTop(from: Float, to: Float, flyUp: Float, progress: Float): Float {
    val distance = to - from
    val peak = minOf(0f, distance) - flyUp
    val ratio = if (distance != 0f) peak / distance else 0f
    val root = if (distance != 0f) sqrt((ratio * (ratio - 1f)).coerceAtLeast(0f)) else 0f
    val peakTime = when {
        distance == 0f -> 0.5f
        distance > 0f -> ratio + root
        else -> ratio - root
    }
    val a = if (distance != 0f) distance / (1f - 2f * peakTime) else -4f * peak
    val b = distance - a
    val p = progress.coerceIn(0f, 1f)
    return a * p * p + b * p + from
}
