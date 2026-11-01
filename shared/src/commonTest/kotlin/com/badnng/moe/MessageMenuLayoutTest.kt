package com.badnng.moe

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MessageMenuLayoutTest {
    private val viewport = IntRect(300, 100, 1100, 750)
    private fun place(anchor: IntRect, height: Int = 260, viewport: IntRect = this.viewport,
        end: Boolean = false) = anchoredMessageMenuPlacement(anchor, viewport, IntSize(248, height),
        shadow = 32, gap = 8, minimumHeight = 120, alignEnd = end)

    @Test
    fun ordinaryMenusAvoidTheActualBubbleWithoutUsingItsFullWidthAlignmentRow() {
        val upper = IntRect(370, 180, 640, 240)
        val below = place(upper)
        assertEquals(MessageMenuSide.BELOW, below.side)
        assertEquals(248, below.body.top)
        assertEquals(370, below.body.left)

        val lower = IntRect(760, 620, 1020, 675)
        val above = place(lower, end = true)
        assertEquals(MessageMenuSide.ABOVE, above.side)
        assertEquals(612, above.body.bottom)
        assertEquals(1020, above.body.right)
    }

    @Test
    fun tallMediaUsesFreeSideSpaceBeforeOverlappingItsVisiblePixels() {
        val media = IntRect(370, 160, 710, 650)
        val right = place(media, height = 400)
        assertEquals(MessageMenuSide.RIGHT, right.side)
        assertTrue(right.body.left >= media.right + 8)
        assertTrue(right.body.top >= viewport.top + 32 && right.body.bottom <= viewport.bottom - 32)
        val outgoing = IntRect(700, 160, 1030, 650)
        val left = place(outgoing, height = 400, end = true)
        assertEquals(MessageMenuSide.LEFT, left.side)
        assertTrue(left.body.right <= outgoing.left - 8)
    }

    @Test
    fun aWindowFillingSourceGetsOnlyASmallScrollablePanelWithinTheTimeline() {
        val narrowViewport = IntRect(350, 190, 820, 700)
        val media = IntRect(370, 190, 800, 700)
        val overlap = place(media, height = 600, viewport = narrowViewport)
        assertEquals(120, overlap.body.height)
        assertTrue(overlap.body.top >= narrowViewport.top + 32)
        assertTrue(overlap.body.bottom <= narrowViewport.bottom - 32)
        assertTrue(overlap.body.left >= narrowViewport.left + 32)
        assertTrue(overlap.body.right <= narrowViewport.right - 32)
    }

    @Test
    fun resizeAndPartialVisibilityAlwaysReserveTheRealShadowInsideTheViewport() {
        for (width in listOf(180, 310, 800)) for (height in listOf(190, 300, 650)) {
            val window = IntRect(20, 70, 20 + width, 70 + height)
            for (anchor in listOf(IntRect(20, 70, 140, 110),
                IntRect(20, window.bottom - 30, 140, window.bottom))) {
                val result = place(anchor, height = 400, viewport = window)
                assertTrue(result.body.left >= window.left + 32)
                assertTrue(result.body.top >= window.top + 32)
                assertTrue(result.body.right <= window.right - 32)
                assertTrue(result.body.bottom <= window.bottom - 32)
            }
        }
    }

    @Test
    fun laterNativePermissionRowsKeepTheirOpeningSideAndGrowAwayFromTheSource() {
        val anchor = IntRect(370, 360, 640, 410)
        val initial = place(anchor, height = 160)
        assertEquals(MessageMenuSide.BELOW, initial.side)
        val loaded = anchoredMessageMenuPlacement(anchor, viewport, IntSize(248, 600),
            shadow = 32, gap = 8, minimumHeight = 120, openingSide = initial.side)
        assertEquals(initial.side, loaded.side)
        assertEquals(initial.body.top, loaded.body.top)
        assertTrue(loaded.body.bottom <= viewport.bottom - 32)
    }

    @Test
    fun sidewaysOpeningAndLaterRowsKeepTheSameSourceRelativeTop() {
        val anchor = IntRect(370, 260, 710, 630)
        val initial = place(anchor, height = 600)
        assertEquals(MessageMenuSide.RIGHT, initial.side)
        val measured = anchoredMessageMenuPlacement(anchor, viewport, IntSize(248, 300),
            shadow = 32, gap = 8, minimumHeight = 120, openingSide = initial.side)
        val loaded = anchoredMessageMenuPlacement(anchor, viewport, IntSize(248, 600),
            shadow = 32, gap = 8, minimumHeight = 120, openingSide = initial.side)
        assertEquals(anchor.top, initial.body.top)
        assertEquals(initial.body.top, measured.body.top)
        assertEquals(initial.body.top, loaded.body.top)
        assertTrue(loaded.body.bottom <= viewport.bottom - 32)
    }
}
