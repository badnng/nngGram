package com.badnng.moe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnimationMessageGeometryTest {
    @Test
    fun nativeLandscapeAndPortraitAnimationsUseTheOfficial320DpCap() {
        assertEquals(MessageMediaSize(320f, 160f), animationMessageSize(640, 320))
        assertEquals(MessageMediaSize(160f, 320f), animationMessageSize(320, 640))
        assertEquals(MessageMediaSize(240f, 320f), animationMessageSize(480, 640))
    }

    @Test
    fun animationCanvasReservesTheOfficialMinimumAndShrinksWithTheConversation() {
        assertEquals(MessageMediaSize(100f, 100f), animationMessageSize(32, 24))
        assertEquals(MessageMediaSize(120f, 240f), animationMessageSize(320, 640, 120f))
        assertEquals(MessageMediaSize(72f, 72f), animationMessageSize(32, 24, 72f))
        for ((width, height) in listOf(0 to 0, -1 to 100, Int.MAX_VALUE to 1, 1 to Int.MAX_VALUE)) {
            val size = animationMessageSize(width, height, 160f)
            assertTrue(size.width.isFinite() && size.width in 1f..160f)
            assertTrue(size.height.isFinite() && size.height in 1f..320f)
        }
    }
}
