package com.badnng.moe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FolderNavigationTest {
    @Test
    fun selectionCrossesTheActualMiddleItemWhenLabelsHaveDifferentHeights() {
        val visible = listOf(FolderItemMeasure(0, 0f, 70f), FolderItemMeasure(1, 70f, 100f), FolderItemMeasure(2, 170f, 70f))
        val middle = assertNotNull(folderIndicatorBounds(1f, 3, visible, emptyMap(), 70f))
        assertEquals(70f, middle.offset)
        val between = assertNotNull(folderIndicatorBounds(1.5f, 3, visible, emptyMap(), 70f))
        assertEquals(120f, between.offset)
        val reverse = listOf(1.8f, 1.3f, 0.9f, 0.2f).map {
            assertNotNull(folderIndicatorBounds(it, 3, visible, emptyMap(), 70f)).offset
        }
        assertTrue(reverse.zipWithNext().all { (before, after) -> after < before })
    }

    @Test
    fun scrollingOffsetsAndPreviouslyMeasuredOffscreenHeightsShareTheSameCoordinateSpace() {
        val visible = listOf(FolderItemMeasure(1, -30f, 100f), FolderItemMeasure(2, 70f, 70f))
        val bounds = assertNotNull(folderIndicatorBounds(0.5f, 3, visible, mapOf(0 to 70f), 80f))
        assertEquals(-65f, bounds.offset)
        assertEquals(85f, bounds.size)
        assertEquals(-100f, assertNotNull(folderIndicatorBounds(-2f, 3, visible, mapOf(0 to 70f), 80f)).offset)
        assertEquals(70f, assertNotNull(folderIndicatorBounds(9f, 3, visible, emptyMap(), 80f)).offset)
    }

    @Test
    fun horizontalSpacingAndDifferentTabWidthsRemainContinuousAndEmptyLayoutDrawsNothing() {
        val visible = listOf(FolderItemMeasure(0, 0f, 50f), FolderItemMeasure(1, 56f, 100f))
        val bounds = assertNotNull(folderIndicatorBounds(0.5f, 3, visible, emptyMap(), 60f, spacing = 6f))
        assertEquals(28f, bounds.offset)
        assertEquals(75f, bounds.size)
        assertEquals(162f, assertNotNull(folderIndicatorBounds(2f, 3, visible, emptyMap(), 60f, 6f)).offset)
        assertNull(folderIndicatorBounds(0f, 3, emptyList(), emptyMap(), 60f))
        assertNull(folderIndicatorBounds(0f, 0, visible, emptyMap(), 60f))
    }
}
