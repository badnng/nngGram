package com.badnng.moe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MessageMediaGeometryTest {
    @Test
    fun photoResizeExpandsOnlyThePinnedThreeQuarterCropBranch() {
        assertTrue(photoResizeExpands(80, 100, 100f, 100f))
        assertTrue(photoResizeExpands(75, 100, 100f, 100f))
        assertTrue(!photoResizeExpands(74, 100, 100f, 100f))
        assertTrue(!photoResizeExpands(64, 48, 100f, 100f))
        assertTrue(!photoResizeExpands(64, 48, 298f, 100f))
        assertTrue(photoResizeExpands(1200, 600, 430f, 215f))
        assertTrue(!photoResizeExpands(0, 0, 100f, 100f))
    }

    @Test
    fun photosUseTheirOwnFourHundredThirtyDpBoundAndMinimumCanvas() {
        assertEquals(MessageMediaSize(430f, 215f), photoMessageSize(1600, 800))
        assertEquals(MessageMediaSize(215f, 430f), photoMessageSize(800, 1600))
        assertEquals(MessageMediaSize(100f, 100f), photoMessageSize(64, 48))
        assertEquals(MessageMediaSize(200f, 100f), photoMessageSize(64, 48, hasBubble = true))
        assertEquals(MessageMediaSize(100f, 430f), photoMessageSize(1, 10_000))
    }

    @Test
    fun photoCaptionExpandsCanvasOnlyWithinOfficialBoundsAndPreservesLessCropThreshold() {
        assertEquals(MessageMediaSize(400f, 100f), photoMessageSize(64, 48, hasBubble = true, captionWidth = 400f))
        assertEquals(MessageMediaSize(430f, 100f), photoMessageSize(64, 48, hasBubble = true, captionWidth = 5_000f))
        assertEquals(MessageMediaSize(200f, 210f), photoMessageSize(800, 1600, availableWidth = 210f, hasBubble = true))
        val narrow = photoMessageSize(1600, 800, availableWidth = 60f, hasBubble = true, captionWidth = 400f)
        assertEquals(MessageMediaSize(60f, 100f), narrow)
        val lessCrop = photoMessageSize(600, 900, hasBubble = true, captionWidth = 350f)
        assertEquals(MessageMediaSize(350f, 525f), lessCrop)
    }

    @Test
    fun landscapePortraitAndSmallSourcesKeepTheirOwnAspectAndNaturalSize() {
        assertEquals(MessageMediaSize(520f, 260f), messageMediaSize(1600, 800))
        assertEquals(MessageMediaSize(260f, 520f), messageMediaSize(800, 1600))
        assertEquals(MessageMediaSize(64f, 48f), messageMediaSize(64, 48))
        assertEquals(MessageMediaSize(1f, 520f), messageMediaSize(1, 10_000))
    }

    @Test
    fun aNarrowConversationShrinksBothDimensionsTogether() {
        val size = messageMediaSize(1200, 800, availableWidth = 210f)
        assertEquals(210f, size.width)
        assertEquals(140f, size.height)
        assertEquals(MessageMediaSize(96f, 192f), messageMediaSize(800, 1600, maxEdge = 192f))
    }

    @Test
    fun unavailableAndUntrustedDimensionsStayFiniteAndWithinTheViewport() {
        for ((width, height) in listOf(0 to 0, -1 to 120, Int.MAX_VALUE to 1, 1 to Int.MAX_VALUE)) {
            val size = messageMediaSize(width, height, availableWidth = 160f)
            assertTrue(size.width.isFinite() && size.height.isFinite())
            assertTrue(size.width in 1f..160f && size.height in 1f..520f)
        }
    }
}
