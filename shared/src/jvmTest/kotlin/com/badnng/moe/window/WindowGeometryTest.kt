package com.badnng.moe.window

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WindowGeometryTest {
    @Test
    fun permitsATelegramSizedNarrowWindowWithoutForcingNarrowStartup() {
        val window = initialWindowGeometry(WorkArea(0, 0, 1920, 1040))
        assertTrue(window.minimumWidth <= 380)
        assertEquals(1160, window.width)
        assertEquals(840, window.height)
        assertEquals(540, window.minimumHeight)
    }

    @Test
    fun windowAndMinimumFitSmallOrOffsetScreens() {
        for (area in listOf(WorkArea(0, 0, 1920, 1040), WorkArea(0, 32, 640, 448), WorkArea(-1280, 40, 1280, 680))) {
            val window = initialWindowGeometry(area)
            assertTrue(window.x >= area.x && window.y >= area.y)
            assertTrue(window.x + window.width <= area.x + area.width)
            assertTrue(window.y + window.height <= area.y + area.height)
            assertTrue(window.minimumWidth <= window.width && window.minimumHeight <= window.height)
        }
    }
}
