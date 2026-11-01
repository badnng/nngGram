package com.badnng.moe

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

internal data class StickerPickerPlacement(val size: IntSize, val offset: IntOffset, val opensAbove: Boolean)

/** Window-pixel placement: prefer the composer's upper edge and keep the complete panel in view. */
internal fun stickerPickerPlacement(window: IntSize, anchor: Rect?, preferred: IntSize, margin: Int, gap: Int,
    sideMargin: Int = margin): StickerPickerPlacement {
    val width = window.width.coerceAtLeast(1)
    val height = window.height.coerceAtLeast(1)
    val horizontalMargin = sideMargin.coerceIn(0, (width - 1) / 2)
    val verticalMargin = margin.coerceIn(0, (height - 1) / 2)
    val availableWidth = width - horizontalMargin * 2
    val availableHeight = height - verticalMargin * 2
    val panelWidth = preferred.width.coerceIn(1, availableWidth)
    val above = anchor?.top?.roundToInt()?.minus(gap.coerceAtLeast(0))?.minus(verticalMargin)?.coerceIn(0, availableHeight) ?: availableHeight
    val below = anchor?.bottom?.roundToInt()?.let { height - verticalMargin - it - gap.coerceAtLeast(0) }?.coerceIn(0, availableHeight) ?: 0
    val opensAbove = above >= preferred.height || above >= below
    val sideHeight = if (opensAbove) above else below
    val usableHeight = if (sideHeight >= minOf(160, availableHeight)) sideHeight else availableHeight
    val panelHeight = preferred.height.coerceIn(1, usableHeight)
    val x = (anchor?.right?.roundToInt()?.minus(panelWidth) ?: (width - horizontalMargin - panelWidth))
        .coerceIn(horizontalMargin, width - horizontalMargin - panelWidth)
    val desiredY = if (sideHeight < minOf(160, availableHeight) || anchor == null) height - verticalMargin - panelHeight
        else if (opensAbove) anchor.top.roundToInt() - gap.coerceAtLeast(0) - panelHeight
        else anchor.bottom.roundToInt() + gap.coerceAtLeast(0)
    return StickerPickerPlacement(IntSize(panelWidth, panelHeight), IntOffset(x,
        desiredY.coerceIn(verticalMargin, height - verticalMargin - panelHeight)), opensAbove)
}
