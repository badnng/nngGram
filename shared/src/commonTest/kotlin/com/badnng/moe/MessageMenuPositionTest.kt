package com.badnng.moe

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals

class MessageMenuPositionTest {
    @Test fun initiallyHiddenAnimationDoesNotLockAZeroSizedPopupPosition() {
        val provider = MessageMenuPosition(12)
        val anchor = IntRect(300, 100, 500, 200)
        provider.calculatePosition(anchor, IntSize(800, 600), LayoutDirection.Ltr, IntSize.Zero)
        assertEquals(IntOffset(252, 100), provider.calculatePosition(anchor, IntSize(800, 600), LayoutDirection.Ltr, IntSize(248, 250)))
    }

    @Test fun nativeRowsCanGrowWithinAvailableSpaceWithoutMovingTheMenu() {
        val provider = MessageMenuPosition(12)
        val anchor = IntRect(300, 100, 500, 200)
        val initial = provider.calculatePosition(anchor, IntSize(800, 600), LayoutDirection.Ltr, IntSize(248, 200))
        assertEquals(initial, provider.calculatePosition(anchor, IntSize(800, 600), LayoutDirection.Ltr, IntSize(248, 450)))
    }

    @Test fun openingNearTheBottomReportsItsFixedBodyHeightBudget() {
        var available = 0
        val provider = MessageMenuPosition(12) { available = it }
        assertEquals(IntOffset(252, 338), provider.calculatePosition(IntRect(300, 500, 500, 540),
            IntSize(800, 600), LayoutDirection.Ltr, IntSize(248, 250)))
        assertEquals(250, available)
    }

    @Test fun windowResizeClampsTheExistingPositionInsideTheNewViewport() {
        val provider = MessageMenuPosition(12)
        val anchor = IntRect(300, 100, 500, 200)
        provider.calculatePosition(anchor, IntSize(800, 600), LayoutDirection.Ltr, IntSize(248, 250))
        assertEquals(IntOffset(140, 38), provider.calculatePosition(anchor, IntSize(400, 300), LayoutDirection.Ltr, IntSize(248, 250)))
    }

    @Test fun shrinkingAfterAnExpansionReturnsToTheOriginalOpeningPoint() {
        val provider = MessageMenuPosition(12)
        val anchor = IntRect(700, 500, 888, 580)
        val viewport = IntSize(900, 650)
        val original = provider.calculatePosition(anchor, viewport, LayoutDirection.Ltr, IntSize(248, 180))
        assertEquals(IntOffset(640, 458), original)
        assertEquals(IntOffset(568, 238), provider.calculatePosition(anchor, viewport, LayoutDirection.Ltr, IntSize(320, 400)))
        assertEquals(original, provider.calculatePosition(anchor, viewport, LayoutDirection.Ltr, IntSize(248, 180)))
    }
}
