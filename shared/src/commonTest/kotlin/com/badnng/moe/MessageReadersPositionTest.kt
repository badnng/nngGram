package com.badnng.moe

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals

class MessageReadersPositionTest {
    @Test fun flyoutStartsBesideTheActualReadRow() {
        assertEquals(IntOffset(544, 100), position(IntRect(300, 100, 530, 136)))
    }

    @Test fun rightEdgeFlipsToTheLeftOfTheMenu() {
        assertEquals(IntOffset(534, 100), position(IntRect(800, 100, 980, 136)))
    }

    @Test fun rtlPrefersTheLeftButCanFlipBackAtTheLeftEdge() {
        assertEquals(IntOffset(34, 100), position(IntRect(300, 100, 530, 136), direction = LayoutDirection.Rtl))
        assertEquals(IntOffset(256, 100), position(IntRect(12, 100, 242, 136), direction = LayoutDirection.Rtl))
    }

    @Test fun bottomAndTopRowsStayInsideWindowMargins() {
        assertEquals(IntOffset(434, 412), position(IntRect(700, 650, 930, 686)))
        assertEquals(IntOffset(544, 8), position(IntRect(300, 0, 530, 36)))
    }

    @Test fun aNarrowWindowClampsTheBoundedFlyoutInsteadOfPlacingItOutside() {
        assertEquals(IntOffset(8, 8), position(IntRect(16, 80, 250, 116),
            window = IntSize(280, 220), popup = IntSize(264, 204)))
    }

    @Test fun theGapIsMeasuredFromTheParentSurfaceInsteadOfItsInsetRow() {
        val row = IntRect(300, 100, 530, 136)
        val right = position(row)
        val left = position(row, direction = LayoutDirection.Rtl)
        assertEquals(8, right.x - (row.right + 6))
        assertEquals(8, (row.left - 6) - (left.x + 252))
    }

    private fun position(anchor: IntRect, window: IntSize = IntSize(1000, 700), popup: IntSize = IntSize(252, 280),
        direction: LayoutDirection = LayoutDirection.Ltr) = MessageReadersPosition(8, 8, 6).calculatePosition(anchor, window, direction, popup)
}
