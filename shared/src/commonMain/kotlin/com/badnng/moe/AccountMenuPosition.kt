package com.badnng.moe

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize

/** Pixel coordinates relative to the workspace, independent of the native window host. */
internal data class AccountMenuPosition(
    val left: Float,
    val top: Float,
    val width: Float,
    val maxHeight: Float,
    val arrowCenter: Float,
)

internal fun accountMenuPosition(viewport: IntSize, anchor: Rect, density: Float): AccountMenuPosition {
    val edge = (8f * density).coerceAtMost(viewport.width / 2f)
    val width = (296f * density).coerceAtMost((viewport.width - 2 * edge).coerceAtLeast(0f))
    val left = (anchor.center.x - 28f * density).coerceIn(edge, (viewport.width - edge - width).coerceAtLeast(edge))
    val top = (anchor.bottom + 5f * density).coerceIn(0f, viewport.height.toFloat())
    // Keep the arrow base on the flat part of the 20dp squircle edge.
    val arrowInset = (28f * density).coerceAtMost(width / 2f)
    return AccountMenuPosition(left, top, width, (viewport.height - top - 8f * density).coerceAtLeast(0f),
        (anchor.center.x - left).coerceIn(arrowInset, width - arrowInset))
}
