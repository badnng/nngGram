package com.badnng.moe

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StickerPickerPlacementTest {
    @Test fun officialTenPixelShadowMarginsKeepTheSameCapacityAndNativePointerGap() {
        val anchor = Rect(290f, 750f, 322f, 782f)
        val result = stickerPickerPlacement(IntSize(366, 800), anchor, IntSize(345, 554), 10, 20)
        assertEquals(IntSize(345, 554), result.size)
        assertEquals(10, result.offset.x)
        assertEquals(730, result.offset.y + result.size.height)
        val nativeSize = IntSize(result.size.width + 20, result.size.height + 20)
        val native = StickerPickerPopupPosition(anchor, 10, 20).calculatePosition(
            IntRect.Zero, IntSize(366, 800), LayoutDirection.Ltr, nativeSize)
        assertEquals(result.offset.x, native.x + 10)
        assertEquals(result.offset.y, native.y + 10)
        assertEquals(740, native.y + nativeSize.height)
    }

    @Test fun officialNarrowWidthKeepsFullBottomShadowWithoutSpendingItOnHorizontalMargins() {
        val anchor = Rect(290f, 750f, 322f, 782f)
        val result = stickerPickerPlacement(IntSize(366, 800), anchor, IntSize(345, 542), 32, 42, sideMargin = 10)
        assertEquals(IntSize(345, 542), result.size)
        assertEquals(10, result.offset.x)
        assertEquals(708, result.offset.y + result.size.height)
        val nativeSize = IntSize(result.size.width + 20, result.size.height + 64)
        val native = StickerPickerPopupPosition(anchor, 32, 42, 10).calculatePosition(
            IntRect.Zero, IntSize(366, 800), LayoutDirection.Ltr, nativeSize)
        assertEquals(result.offset.x, native.x + 10)
        assertEquals(result.offset.y, native.y + 32)
        assertEquals(740, native.y + nativeSize.height,
            "The transparent native shadow window must stop before the original composer target's 10px gap.")
        assertTrue(native.x >= 0 && native.x + nativeSize.width <= 366)
    }

    @Test fun aComposerButtonOpensThePanelAboveAndAlignsItsRightEdge() {
        val anchor = Rect(700f, 730f, 732f, 762f)
        val result = stickerPickerPlacement(IntSize(800, 800), anchor, IntSize(392, 460), 12, 10)
        assertTrue(result.opensAbove)
        assertEquals(732, result.offset.x + result.size.width)
        assertEquals(720, result.offset.y + result.size.height)
    }

    @Test fun aTopAnchorUsesTheSpaceBelowAndANarrowWindowShrinksThePanel() {
        val result = stickerPickerPlacement(IntSize(280, 520), Rect(230f, 15f, 262f, 47f), IntSize(392, 460), 12, 10)
        assertTrue(!result.opensAbove)
        assertEquals(256, result.size.width)
        assertEquals(57, result.offset.y)
        assertEquals(451, result.size.height)
    }

    @Test fun shortWindowsAndStaleOrMissingAnchorsKeepTheWholePanelInsideTheViewport() {
        for (window in listOf(IntSize(210, 180), IntSize(90, 76), IntSize.Zero)) {
            for (anchor in listOf(null, Rect(-100f, -100f, -68f, -68f), Rect(700f, 730f, 732f, 762f))) {
                val result = stickerPickerPlacement(window, anchor, IntSize(392, 460), 12, 10)
                assertTrue(result.size.width in 1..window.width.coerceAtLeast(1))
                assertTrue(result.size.height in 1..window.height.coerceAtLeast(1))
                assertTrue(result.offset.x >= 0 && result.offset.y >= 0)
                assertTrue(result.offset.x + result.size.width <= window.width.coerceAtLeast(1))
                assertTrue(result.offset.y + result.size.height <= window.height.coerceAtLeast(1))
            }
        }
        val fallback = stickerPickerPlacement(IntSize(900, 700), null, IntSize(392, 460), 12, 10)
        assertEquals(888, fallback.offset.x + fallback.size.width)
        assertEquals(688, fallback.offset.y + fallback.size.height)
    }
}
