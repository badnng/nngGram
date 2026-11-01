package com.badnng.moe

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.ChatSnapshot
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal data class IncomingEntrancePlan(val presentationId: Long, val eventId: Long, val alreadyVisible: Boolean)

/** Only native UpdateNewMessage identities authorize an entrance. Historical rows never do. */
internal class IncomingAnimationTracker {
    private var initialized = false
    private var historyWasLoading = false
    private var jumpRevision: Long? = null
    private val knownRows = mutableSetOf<Long>()
    private val seenArrivals = mutableSetOf<Pair<Long, Long>>()
    private val claimed = mutableSetOf<Pair<Long, Long>>()
    private val plans = mutableMapOf<Long, IncomingEntrancePlan>()

    fun update(snapshot: ChatSnapshot, atLatest: Boolean, active: Boolean): Map<Long, IncomingEntrancePlan> {
        val canAnimate = initialized && !historyWasLoading && !snapshot.historyLoading && !snapshot.newerLoading &&
            !snapshot.historyHasNewer && snapshot.jumpMessageId == null &&
            (jumpRevision == null || jumpRevision == snapshot.jumpRevision) && atLatest && active
        val rows = snapshot.messages.map { it.presentationId }.toSet()
        plans.keys.retainAll(rows)
        if (!canAnimate) plans.clear()
        snapshot.messages.forEach { message ->
            val arrival = message.incomingAnimationId
            if (arrival != null && !message.outgoing && message.id != 0L && message.presentationId != 0L &&
                seenArrivals.add(message.presentationId to arrival) && canAnimate) {
                plans[message.presentationId] = IncomingEntrancePlan(message.presentationId, arrival,
                    message.presentationId in knownRows)
            }
        }
        knownRows += rows
        initialized = true
        historyWasLoading = snapshot.historyLoading || snapshot.newerLoading
        jumpRevision = snapshot.jumpRevision
        return plans.toMap()
    }

    fun claim(plan: IncomingEntrancePlan): Boolean = claimed.add(plan.presentationId to plan.eventId)
}

// Telegram Desktop chat.style::itemRevealDuration and HistoryWidget::startItemRevealAnimations.
internal const val IncomingMessageRevealDuration = 150
private val IncomingMessageRevealEasing = Easing { fraction ->
    sqrt((1f - (fraction - 1f) * (fraction - 1f)).coerceIn(0f, 1f))
}

/** Grow the actual row height, never scale its text or avatar bitmap. */
@Composable
internal fun IncomingMessageEntrance(
    plan: IncomingEntrancePlan?,
    tracker: IncomingAnimationTracker,
    active: Boolean,
    atLatest: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val shouldAnimate = remember(plan?.eventId) { plan != null && tracker.claim(plan) }
    val progress = remember(plan?.eventId) { Animatable(if (shouldAnimate && active && atLatest) 0f else 1f) }
    LaunchedEffect(progress, active, atLatest) {
        if (shouldAnimate && active && atLatest) {
            progress.animateTo(1f, tween(IncomingMessageRevealDuration, easing = IncomingMessageRevealEasing))
        } else progress.snapTo(1f)
    }
    val entrance = if (!shouldAnimate) Modifier else if (plan?.alreadyVisible == true) {
        // LastMessage may publish this row before UpdateNewMessage. Keep its full measured
        // height instead of collapsing already drawn content when the authoritative event arrives.
        Modifier.graphicsLayer { translationY = 8.dp.toPx() * (1f - progress.value) }
    } else Modifier.graphicsLayer { clip = progress.value < 1f }.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val height = (placeable.height * progress.value).roundToInt().coerceIn(0, placeable.height)
        layout(placeable.width, height) { placeable.placeRelative(0, 0) }
    }
    Column(modifier.then(entrance)) { content() }
}
