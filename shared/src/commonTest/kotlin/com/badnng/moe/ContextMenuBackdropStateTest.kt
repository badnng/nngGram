package com.badnng.moe

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContextMenuBackdropStateTest {
    @Test
    fun aLocallyBlurredSelectorStillOwnsTimelineSuppressionWithoutBlurringItsAnchor() {
        val state = ContextMenuBackdropState()
        val selector = Any()
        val menu = Any()
        state.update(selector, true, blurBackground = false)
        assertTrue(state.isExpanded && state.hasMountedMenus)
        assertFalse(state.hasExpandedBlurOwners)
        state.update(menu, true)
        assertTrue(state.hasExpandedBlurOwners, "Ordinary menus retain their whole-workspace blur.")
        state.update(menu, false)
        assertFalse(state.hasExpandedBlurOwners)
        assertTrue(state.isExpanded && state.hasMountedMenus)
        state.update(selector, false, blurBackground = false)
        assertFalse(state.isExpanded)
        assertTrue(state.hasMountedMenus, "Both retained exit layers still suppress timeline activity.")
        state.remove(menu)
        state.remove(selector)
        assertFalse(state.hasMountedMenus)
    }

    @Test
    fun closingOneMenuKeepsOtherOwnersAndRetainsTheClosingOwnerUntilDisposed() {
        val state = ContextMenuBackdropState()
        val first = Any()
        val second = Any()
        state.update(first, true)
        state.update(second, true)
        state.update(first, false)
        assertTrue(state.isExpanded)
        state.remove(second)
        assertFalse(state.isExpanded)
        assertTrue(state.hasMountedMenus, "The closing popup still blocks automatic read receipts.")
        state.remove(first)
        assertFalse(state.hasMountedMenus)
    }

    @Test
    fun popupPaddingKeepsTheCardAtThePointerAndClampsTheWholeShadowInsideTheWindow() {
        val anchor = IntRect(80, 120, 360, 200)
        val window = IntSize(800, 600)
        val popup = IntSize(302, 316)
        val position = ChatListMenuPosition(Offset(100f, 40f), shadowPadding = 32)
        assertEquals(IntOffset(148, 128), position.calculatePosition(anchor, window, LayoutDirection.Ltr, popup))

        val edge = ChatListMenuPosition(Offset(500f, 450f), shadowPadding = 32)
        assertEquals(IntOffset(498, 284), edge.calculatePosition(anchor, window, LayoutDirection.Ltr, popup))
        assertEquals(IntOffset.Zero, edge.calculatePosition(anchor, IntSize(240, 180), LayoutDirection.Ltr, popup))
    }
}
