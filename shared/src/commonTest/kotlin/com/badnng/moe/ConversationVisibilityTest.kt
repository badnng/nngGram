package com.badnng.moe

import androidx.compose.ui.geometry.Rect
import com.badnng.moe.chat.visibleConversationArea
import kotlin.test.Test
import kotlin.test.assertEquals

class ConversationVisibilityTest {
    private val viewport = Rect(0f, 0f, 400f, 400f)
    private val header = Rect(100f, 20f, 300f, 100f)

    @Test fun contentFullyBehindTheHeaderHasNoVisibleArea() {
        assertEquals(0f, visibleConversationArea(Rect(120f, 30f, 280f, 90f), viewport, header))
    }

    @Test fun partialOverlapSubtractsOnlyTheCoveredPart() {
        assertEquals(10_500f, visibleConversationArea(Rect(50f, 50f, 250f, 140f), viewport, header))
    }

    @Test fun contentOnEitherSideOfTheFloatingHeaderRemainsVisible() {
        assertEquals(4_800f, visibleConversationArea(Rect(20f, 20f, 80f, 100f), viewport, header))
        assertEquals(4_800f, visibleConversationArea(Rect(320f, 20f, 380f, 100f), viewport, header))
    }

    @Test fun scrollingOutOfTheViewportClipsBeforeSubtractingTheHeader() {
        assertEquals(5_000f, visibleConversationArea(Rect(0f, -30f, 200f, 30f), viewport, header))
        assertEquals(0f, visibleConversationArea(Rect(0f, -30f, 200f, 0f), viewport, header))
        assertEquals(0f, visibleConversationArea(Rect(0f, 400f, 200f, 430f), viewport, header))
        assertEquals(0f, visibleConversationArea(Rect(400f, 20f, 420f, 100f), viewport, header))
    }

    @Test fun anEmptyOrDisjointHeaderDoesNotHideContent() {
        val content = Rect(50f, 50f, 250f, 140f)
        assertEquals(18_000f, visibleConversationArea(content, viewport))
        assertEquals(18_000f, visibleConversationArea(content, viewport, Rect(150f, 0f, 150f, 200f)))
        assertEquals(18_000f, visibleConversationArea(content, viewport, Rect(0f, 200f, 400f, 300f)))
    }

    @Test fun invalidOrEmptyContentAndViewportCannotBecomeVisible() {
        val content = Rect(50f, 50f, 250f, 140f)
        listOf(Rect.Zero, Rect(20f, 0f, 10f, 100f), Rect(0f, 0f, Float.NaN, 100f),
            Rect(0f, 0f, 100f, Float.POSITIVE_INFINITY)).forEach { invalid ->
            assertEquals(0f, visibleConversationArea(invalid, viewport, header))
            assertEquals(0f, visibleConversationArea(content, invalid, header))
        }
    }

    @Test fun anInvalidHeaderMeasurementConservativelyHidesContent() {
        val content = Rect(50f, 50f, 250f, 140f)
        listOf(Rect(20f, 0f, 10f, 100f), Rect(0f, 0f, Float.NaN, 100f),
            Rect(0f, 0f, 100f, Float.POSITIVE_INFINITY)).forEach { invalid ->
            assertEquals(0f, visibleConversationArea(content, viewport, invalid))
        }
    }

    @Test fun contentFullyBehindTheFloatingComposerHasNoVisibleArea() {
        val composer = Rect(12f, 300f, 376f, 380f)
        assertEquals(0f, visibleConversationArea(Rect(30f, 310f, 200f, 370f), viewport, header, composer))
        assertEquals(2_000f, visibleConversationArea(Rect(30f, 280f, 130f, 330f), viewport, header, composer))
    }

    @Test fun transparentComposerMarginsAndTheGapBelowItRemainVisible() {
        val composer = Rect(12f, 300f, 376f, 380f)
        assertEquals(600f, visibleConversationArea(Rect(0f, 310f, 10f, 370f), viewport, header, composer))
        assertEquals(2_000f, visibleConversationArea(Rect(30f, 380f, 130f, 410f), viewport, header, composer))
    }

    @Test fun overlappingHeaderAndComposerAreSubtractedOnlyOnceInSmallWindows() {
        val content = Rect(100f, 50f, 200f, 150f)
        assertEquals(1_000f, visibleConversationArea(content, viewport,
            Rect(100f, 50f, 200f, 110f), Rect(100f, 90f, 200f, 140f)))
    }

    @Test fun invalidComposerMeasurementsCannotMarkCoveredContentVisible() {
        assertEquals(0f, visibleConversationArea(Rect(30f, 310f, 200f, 370f), viewport, header,
            Rect(0f, Float.NaN, 400f, 380f)))
    }
}
