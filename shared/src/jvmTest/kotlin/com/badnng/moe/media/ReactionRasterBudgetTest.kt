package com.badnng.moe.media

import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReactionRasterBudgetTest {
    @Test
    fun realIconAndPickerCellPixelsKeepSamplingHeadroomWithoutUnboundedVideoFrames() {
        // Real emoji targets are 18 dp in chips and 29/36 dp in quick/full choice cells.
        for (density in listOf(1f, 1.25f, 2f)) for (edgeDp in listOf(18, 29, 36)) {
            val displayPixels = ceil(edgeDp * density).toInt()
            val raster = reactionPreviewRasterEdge(displayPixels)
            assertTrue(raster >= displayPixels * 2,
                "Video emoji must retain real physical-pixel sampling headroom at $edgeDp dp / $density density.")
            assertTrue(raster in 96..192, "Reaction video frames must remain within their independent bounded budget.")
        }
        assertEquals(96, reactionPreviewRasterEdge(0), "An unmeasured cell must retain a usable initial preview.")
        assertEquals(192, reactionPreviewRasterEdge(100_000), "An extreme host size must not request an unbounded raster.")
    }

    @Test
    fun largerTransientEffectsMayUseTheirOwnCapWithoutChangingOrdinaryReactionBudget() {
        for (density in listOf(1f, 1.25f, 2f)) for (edgeDp in listOf(32, 64)) {
            val displayPixels = ceil(edgeDp * density).toInt()
            assertTrue(reactionPreviewRasterEdge(displayPixels, maxRasterEdge = 256) >= displayPixels * 2,
                "The official $edgeDp dp animation canvas needs physical-pixel sampling headroom at $density density.")
        }
        assertEquals(192, reactionPreviewRasterEdge(180))
        assertEquals(256, reactionPreviewRasterEdge(180, maxRasterEdge = 256))
        assertEquals(192, reactionPreviewRasterEdge(180), "An effect request must not mutate the ordinary grid's budget.")
    }
}
