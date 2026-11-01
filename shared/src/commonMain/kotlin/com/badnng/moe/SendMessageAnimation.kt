package com.badnng.moe

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badnng.moe.chat.*
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal data class SendEntrancePlan(val presentationId: Long, val eventId: Long, val alreadyVisible: Boolean)
internal data class SendAnimationFrame(val plans: Map<Long, SendEntrancePlan>, val placementRevision: Long)

/** Uses explicit app send identities, never message text, timestamps or guessed temporary-id matches. */
internal class SendAnimationTracker {
    private var initialized = false
    private var historyWasLoading = false
    private var jumpRevision: Long? = null
    private var placementRevision = 0L
    private val knownRows = mutableSetOf<Long>()
    private val seenSends = mutableSetOf<Pair<Long, Long>>()
    private val claimed = mutableSetOf<Pair<Long, Long>>()
    private val plans = mutableMapOf<Long, SendEntrancePlan>()

    fun update(snapshot: ChatSnapshot, atLatest: Boolean, active: Boolean): SendAnimationFrame {
        val canAnimate = initialized && !historyWasLoading && !snapshot.historyLoading && !snapshot.newerLoading &&
            !snapshot.historyHasNewer && snapshot.jumpMessageId == null &&
            (jumpRevision == null || jumpRevision == snapshot.jumpRevision) && atLatest && active
        var added = false
        val rows = snapshot.messages.map { it.presentationId }.toSet()
        plans.keys.retainAll(rows)
        snapshot.messages.forEach { message ->
            val send = message.sendAnimationId
            if (send != null && message.outgoing && message.id != 0L && message.presentationId != 0L &&
                seenSends.add(message.presentationId to send) && canAnimate) {
                val alreadyVisible = message.presentationId in knownRows
                plans[message.presentationId] = SendEntrancePlan(message.presentationId, send, alreadyVisible)
                added = added || !alreadyVisible
            }
        }
        knownRows += rows
        initialized = true
        historyWasLoading = snapshot.historyLoading || snapshot.newerLoading
        jumpRevision = snapshot.jumpRevision
        // A native id/receipt update keeps the same row. It must not restart placement
        // animation on every other visible message in the conversation.
        if (added) placementRevision++
        return SendAnimationFrame(plans.toMap(), placementRevision)
    }

    /** A row evicted by LazyColumn must not replay its old entrance when it comes back into view. */
    fun claim(plan: SendEntrancePlan): Boolean = claimed.add(plan.presentationId to plan.eventId)
}

@Composable
internal fun SendBubbleEntrance(plan: SendEntrancePlan?, tracker: SendAnimationTracker,
    active: Boolean = true, atLatest: Boolean = true, content: @Composable () -> Unit) {
    val shouldAnimate = remember(plan?.eventId) { plan != null && !plan.alreadyVisible && tracker.claim(plan) }
    val progress = remember(plan?.eventId) { Animatable(if (shouldAnimate && active && atLatest) 0f else 1f) }
    LaunchedEffect(progress, active, atLatest) {
        if (shouldAnimate && active && atLatest) progress.animateTo(1f, tween(200, easing = FastOutSlowInEasing))
        else progress.snapTo(1f)
    }
    Box(Modifier.graphicsLayer {
        val shown = if (active && atLatest) progress.value else 1f
        alpha = shown
        translationY = 14.dp.toPx() * (1f - shown)
    }) { content() }
}

private data class DeliveryPresentation(val delivery: MessageDelivery, val read: Boolean, val retrying: Boolean)

@Composable
internal fun OutgoingDeliveryIndicator(message: ChatMessage, read: Boolean, retrying: Boolean, compact: Boolean,
    foreground: androidx.compose.ui.graphics.Color? = null) {
    val state = DeliveryPresentation(message.delivery, read, retrying)
    AnimatedContent(state, modifier = Modifier.width(26.dp).height(16.dp), contentAlignment = Alignment.CenterEnd,
        label = "native message delivery", transitionSpec = {
            (fadeIn(tween(120)) + slideInVertically(tween(160)) { it / 4 }) togetherWith
                (fadeOut(tween(90)) + slideOutVertically(tween(140)) { -it / 4 })
        }) { target ->
        val description = when {
            target.retrying -> "正在重试"
            target.delivery == MessageDelivery.PENDING -> "等待发送"
            target.delivery == MessageDelivery.FAILED -> "发送失败"
            target.read -> "已读"
            else -> "已发送"
        }
        Text(when {
            target.retrying -> "↻"
            target.delivery == MessageDelivery.PENDING -> "…"
            target.delivery == MessageDelivery.FAILED -> "!"
            target.read -> "✓✓"
            else -> "✓"
        }, fontSize = if (compact) 10.sp else 11.sp,
            modifier = Modifier.semantics { contentDescription = description },
            color = when {
                target.delivery == MessageDelivery.FAILED && !target.retrying -> MiuixTheme.colorScheme.error
                foreground != null -> foreground
                target.delivery == MessageDelivery.SENT && target.read -> MiuixTheme.colorScheme.primary
                else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
            })
    }
}
