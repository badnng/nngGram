package com.badnng.moe

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider

/** Native permissions can add rows after opening. Keep the opening position and scroll the body. */
internal class MessageMenuPosition(
    private val margin: Int,
    private val onAvailableHeight: (Int) -> Unit = {},
) : PopupPositionProvider {
    private var openingPosition: IntOffset? = null
    private var lastAvailableHeight: Int? = null

    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection,
        popupContentSize: IntSize): IntOffset {
        val maximumX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val maximumY = (windowSize.height - popupContentSize.height).coerceAtLeast(0)
        val edgeX = margin.coerceIn(0, maximumX / 2)
        val edgeY = margin.coerceIn(0, maximumY / 2)
        val opening = openingPosition ?: IntOffset(
            if (layoutDirection == LayoutDirection.Ltr) anchorBounds.right - popupContentSize.width else anchorBounds.left,
            anchorBounds.top,
        )
        val position = IntOffset(opening.x.coerceIn(edgeX, (maximumX - edgeX).coerceAtLeast(edgeX)),
            opening.y.coerceIn(edgeY, (maximumY - edgeY).coerceAtLeast(edgeY)))
        if (popupContentSize.width > 0 && popupContentSize.height > 0) {
            if (openingPosition == null) openingPosition = position
            val available = (windowSize.height - position.y - margin).coerceAtLeast(1)
            if (available != lastAvailableHeight) {
                lastAvailableHeight = available
                onAvailableHeight(available)
            }
        }
        return position
    }
}
