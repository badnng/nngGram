package com.badnng.moe

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals

class ReactionPickerPositionTest {
    @Test fun messageNearBottomPlacesPickerAboveAndToItsRight() {
        assertEquals(IntOffset(208, 152), position(Rect(100f, 600f, 200f, 650f), IntSize(1000, 800), IntSize(320, 440)))
    }

    @Test fun rightEdgeFlipsPickerToTheLeftAndUsesSpaceBelow() {
        assertEquals(IntOffset(522, 258), position(Rect(850f, 200f, 950f, 250f), IntSize(1000, 800), IntSize(320, 440)))
    }

    @Test fun narrowWindowClampsBothCoordinatesInsideItsMargins() {
        assertEquals(IntOffset(12, 12), position(Rect(100f, 100f, 180f, 150f), IntSize(280, 300), IntSize(256, 276)))
    }

    @Test fun missingAnchorCentersThePicker() {
        assertEquals(IntOffset(340, 180), position(null, IntSize(1000, 800), IntSize(320, 440)))
    }

    private fun position(anchor: Rect?, window: IntSize, popup: IntSize) =
        ReactionPickerPosition(anchor, 12).calculatePosition(IntRect.Zero, window, LayoutDirection.Ltr, popup)
}
