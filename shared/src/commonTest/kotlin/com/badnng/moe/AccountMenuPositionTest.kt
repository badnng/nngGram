package com.badnng.moe

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccountMenuPositionTest {
    @Test
    fun menuStartsBelowAvatarAndArrowPointsToItsCenter() {
        val anchor = Rect(14f, 64f, 66f, 116f)
        val menu = accountMenuPosition(IntSize(1200, 800), anchor, 1f)
        assertEquals(121f, menu.top)
        assertEquals(anchor.center.x, menu.left + menu.arrowCenter)
        assertEquals(296f, menu.width)
        assertEquals(671f, menu.maxHeight)
    }

    @Test
    fun rightAlignedNarrowConversationAvatarKeepsCardInsideWindow() {
        val viewport = IntSize(400, 680)
        val anchor = Rect(328f, 8f, 380f, 60f)
        val menu = accountMenuPosition(viewport, anchor, 1f)
        assertEquals(392f, menu.left + menu.width)
        assertEquals(anchor.center.x, menu.left + menu.arrowCenter)
        assertTrue(menu.left >= 8f)
    }

    @Test
    fun resizedSmallViewportBoundsWidthAndScrollableHeight() {
        val menu = accountMenuPosition(IntSize(240, 250), Rect(14f, 64f, 66f, 116f), 1f)
        assertEquals(224f, menu.width)
        assertEquals(121f, menu.maxHeight)
        assertEquals(8f, menu.left)
        assertEquals(242f, menu.top + menu.maxHeight)
    }

    @Test
    fun densityScalesGapWidthAndArrowWithoutLosingAnchor() {
        val anchor = Rect(28f, 128f, 132f, 232f)
        val menu = accountMenuPosition(IntSize(2400, 1600), anchor, 2f)
        assertEquals(242f, menu.top)
        assertEquals(592f, menu.width)
        assertEquals(anchor.center.x, menu.left + menu.arrowCenter)
    }

    @Test
    fun collapsedOrTransientViewportNeverProducesNegativeConstraints() {
        for (viewport in listOf(IntSize.Zero, IntSize(8, 3), IntSize(32, 40))) {
            val menu = accountMenuPosition(viewport, Rect(0f, 60f, 52f, 112f), 1f)
            assertTrue(menu.width >= 0f)
            assertTrue(menu.maxHeight >= 0f)
            assertTrue(menu.top <= viewport.height)
            assertTrue(menu.arrowCenter in 0f..menu.width)
        }
    }
}
