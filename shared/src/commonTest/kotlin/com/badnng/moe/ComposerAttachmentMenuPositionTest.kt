package com.badnng.moe

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.*
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ComposerAttachmentMenuPositionTest {
    @Test
    fun requestedSmallLiftAddsOnlyFourPixelsBeyondTheOfficialShadowAllowance() {
        for (density in listOf(1f, 1.25f)) {
            fun px(value: Int) = (value * density).roundToInt()
            val padding = px(10)
            val lift = px(4)
            val anchor = Rect(px(26).toFloat(), px(740).toFloat(), px(58).toFloat(), px(772).toFloat())
            val popup = IntSize(px(208) + 2 * padding, px(136) + 2 * padding)
            val position = ComposerAttachmentMenuPosition(anchor, padding, gap = padding + lift)
                .calculatePosition(IntRect.Zero, IntSize(px(366), px(800)), LayoutDirection.Ltr, popup)
            assertEquals(anchor.top - padding - lift, (position.y + popup.height - padding).toFloat(),
                "The card body gap is the official padding plus the user's 4dp adjustment, with no generic-menu padding.")
            assertEquals(anchor.top - lift, (position.y + popup.height).toFloat(),
                "The complete popup shadow ends 4dp above the button and never overlaps its hit region.")
        }
    }

    @Test
    fun officialInnerDropdownUsesOnlyItsTenPixelPaddingBetweenTheBodyAndButton() {
        for (density in listOf(1f, 1.25f)) for (direction in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
            fun px(value: Int) = (value * density).roundToInt()
            val padding = px(10)
            val window = IntSize(px(366), px(800))
            val anchor = Rect(px(26).toFloat(), px(740).toFloat(), px(58).toFloat(), px(772).toFloat())
            val popup = IntSize(px(208) + 2 * padding, px(136) + 2 * padding)
            val position = ComposerAttachmentMenuPosition(anchor, padding, gap = padding)
                .calculatePosition(IntRect.Zero, window, direction, popup)
            val bodyBottom = position.y + popup.height - padding
            assertEquals(anchor.top - padding, bodyBottom.toFloat(),
                "defaultInnerDropdown's real 10dp padding is the entire visible body gap.")
            assertEquals(anchor.top, (position.y + popup.height).toFloat(),
                "The native popup, including the complete bottom shadow, must stop at the original button.")
            assertTrue(position.x >= 0 && position.x + popup.width <= window.width)
            assertTrue(position.y >= 0 && position.y + popup.height <= window.height)
        }
    }

    @Test
    fun cardBodyAlignsToTheButtonAndTheShadowStaysInsideTheWindow() {
        val anchor = Rect(40f, 410f, 72f, 442f)
        val popup = IntSize(232, 160)
        val window = IntSize(600, 500)
        val position = ComposerAttachmentMenuPosition(anchor, padding = 12, gap = 8)
            .calculatePosition(IntRect.Zero, window, LayoutDirection.Ltr, popup)
        assertEquals(IntOffset(28, 254), position)
        assertEquals(anchor.left, position.x + 12f)
        assertEquals(anchor.top - 8, position.y + popup.height - 12f)
        val edge = ComposerAttachmentMenuPosition(Rect(580f, 10f, 612f, 42f), 12, 8)
            .calculatePosition(IntRect.Zero, window, LayoutDirection.Ltr, popup)
        assertEquals(IntOffset(368, 38), edge)
        assertTrue(edge.x >= 0 && edge.y >= 0 && edge.x + popup.width <= window.width && edge.y + popup.height <= window.height)
    }
}
