package com.badnng.moe

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopChatLayoutTest {
    @Test fun slimWindowsUseTheWholeWidthForOnePaneAndReachFoldersWithoutTheRail() {
        val layout = desktopChatLayout(380.dp, 796.dp)
        assertFalse(layout.threeColumns)
        assertFalse(layout.showRail)
        assertTrue(layout.compact)
        assertFalse(desktopChatLayout(599.dp, 796.dp).showRail)
        assertTrue(desktopChatLayout(600.dp, 796.dp).showRail)
    }

    @Test fun defaultWindowKeepsMostOfItsWidthForTheConversation() {
        val layout = desktopChatLayout(1160.dp, 796.dp)
        assertTrue(layout.compact)
        assertTrue(layout.threeColumns)
        val leftWidth = layout.railWidth + layout.listWidth
        assertTrue(leftWidth < 350.dp)
        assertTrue(1160.dp - leftWidth > 810.dp)
    }

    @Test fun designSizeRetainsTheReferenceAndLargerWindowsDoNotEnlargeControls() {
        val reference = desktopChatLayout(1494.dp, 949.dp)
        assertEquals(88.dp, reference.railWidth)
        assertEquals(354.dp, reference.listWidth)
        assertFalse(reference.compact)
        assertEquals(reference, desktopChatLayout(1920.dp, 1036.dp))
    }

    @Test fun smallWindowsRetainReadableColumnsAndSwitchToSinglePaneBelowTheBreakpoint() {
        val smallestWide = desktopChatLayout(980.dp, 700.dp)
        assertTrue(smallestWide.threeColumns)
        assertEquals(72.dp, smallestWide.railWidth)
        assertEquals(260.dp, smallestWide.listWidth)
        assertTrue(980.dp - smallestWide.railWidth - smallestWide.listWidth >= 640.dp)
        assertFalse(desktopChatLayout(979.dp, 700.dp).threeColumns)
        assertFalse(desktopChatLayout(780.dp, 556.dp).threeColumns)
    }

    @Test fun shortWideWindowsUseCompactSpacingWithoutChangingColumnProportions() {
        val layout = desktopChatLayout(1494.dp, 700.dp)
        assertTrue(layout.compact)
        assertEquals(88.dp, layout.railWidth)
        assertEquals(354.dp, layout.listWidth)
    }
}
