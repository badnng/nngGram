package com.badnng.moe.chat

import androidx.compose.ui.geometry.Rect

/** Count viewport content outside both floating control surfaces, subtracting their union once. */
internal fun visibleConversationArea(
    bounds: Rect,
    viewport: Rect,
    occlusion: Rect = Rect.Zero,
    bottomOcclusion: Rect = Rect.Zero,
): Float {
    if (!bounds.hasFiniteCoordinates() || !viewport.hasFiniteCoordinates() ||
        bounds.width <= 0f || bounds.height <= 0f || viewport.width <= 0f || viewport.height <= 0f) return 0f
    // Invalid control measurements must not mark covered content as read or start playback.
    if (!occlusion.hasValidOcclusion() || !bottomOcclusion.hasValidOcclusion()) return 0f

    val visible = bounds.intersect(viewport)
    if (visible.width <= 0f || visible.height <= 0f) return 0f
    val area = visible.width.toDouble() * visible.height
    val headerOverlap = visible.intersect(occlusion)
    val bottomOverlap = visible.intersect(bottomOcclusion)
    val covered = headerOverlap.nonNegativeArea() + bottomOverlap.nonNegativeArea() -
        headerOverlap.intersect(bottomOverlap).nonNegativeArea()
    return (area - covered).coerceIn(0.0, Float.MAX_VALUE.toDouble()).toFloat()
}

private fun Rect.nonNegativeArea(): Double = width.coerceAtLeast(0f).toDouble() * height.coerceAtLeast(0f)

private fun Rect.hasValidOcclusion(): Boolean = hasFiniteCoordinates() && width >= 0f && height >= 0f

private fun Rect.hasFiniteCoordinates(): Boolean =
    left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() &&
        width.isFinite() && height.isFinite()
