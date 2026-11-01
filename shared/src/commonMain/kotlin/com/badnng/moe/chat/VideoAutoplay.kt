package com.badnng.moe.chat

import androidx.compose.ui.geometry.Rect

internal data class VideoAutoplayCandidate(val messageId: Long, val bounds: Rect)

/** Retain a visible player until another video has enough room to start. */
internal fun selectAutoplayVideo(
    candidates: List<VideoAutoplayCandidate>,
    viewport: Rect,
    currentMessageId: Long?,
    suppressed: Set<Long>,
    occlusion: Rect = Rect.Zero,
    bottomOcclusion: Rect = Rect.Zero,
): Long? {
    if (!viewport.isValidAutoplayBounds()) return null
    val visible = candidates.mapNotNull { candidate ->
        if (candidate.messageId in suppressed) return@mapNotNull null
        val area = visibleConversationArea(candidate.bounds, viewport, occlusion, bottomOcclusion)
        if (area > 0f) candidate to area else null
    }
    val eligible = visible.filter { (candidate, area) ->
        val bounds = candidate.bounds
        val visibleWidth = minOf(bounds.right, viewport.right) - maxOf(bounds.left, viewport.left)
        // Preserve the existing half-height threshold, discounted by both controls' covered area.
        area.toDouble() >= visibleWidth.toDouble() * minOf(bounds.height, viewport.height) / 2.0
    }.map { it.first }
    eligible.firstOrNull { it.messageId == currentMessageId }?.let { return it.messageId }
    val nearest = eligible.minByOrNull { candidate ->
        val bounds = candidate.bounds
        val horizontal = (bounds.left.toDouble() + bounds.right - viewport.left - viewport.right) / 2.0
        val vertical = (bounds.top.toDouble() + bounds.bottom - viewport.top - viewport.bottom) / 2.0
        horizontal * horizontal + vertical * vertical
    }
    return nearest?.messageId ?: visible.firstOrNull { it.first.messageId == currentMessageId }?.first?.messageId
}

private fun Rect.isValidAutoplayBounds(): Boolean =
    left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() &&
        width.isFinite() && height.isFinite() && width > 0f && height > 0f
