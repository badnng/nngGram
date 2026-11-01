package com.badnng.moe

import androidx.compose.ui.geometry.Rect
import com.badnng.moe.chat.VideoAutoplayCandidate
import com.badnng.moe.chat.selectAutoplayVideo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VideoAutoplayTest {
    private val viewport = Rect(0f, 0f, 400f, 400f)

    @Test fun startsTheVideoClosestToTheViewportCenter() {
        val candidates = listOf(video(1, 0f, 100f), video(2, 150f, 250f), video(3, 300f, 400f))
        assertEquals(2L, selectAutoplayVideo(candidates, viewport, null, emptySet()))
    }

    @Test fun keepsTheCurrentEligibleVideoWhenAnotherIsCloser() {
        val candidates = listOf(video(1, 0f, 100f), video(2, 150f, 250f))
        assertEquals(1L, selectAutoplayVideo(candidates, viewport, 1, emptySet()))
    }

    @Test fun videosTallerThanTheViewportCanStartWithoutHalfTheirTotalHeightVisible() {
        assertEquals(1L, selectAutoplayVideo(listOf(video(1, -300f, 700f)), viewport, null, emptySet()))
        assertNull(selectAutoplayVideo(listOf(video(1, 201f, 1201f)), viewport, null, emptySet()))
        assertEquals(1L, selectAutoplayVideo(listOf(video(1, 200f, 1200f)), viewport, null, emptySet()))
    }

    @Test fun aSmallVisibleSliverDoesNotStartANewVideoButKeepsTheCurrentPlayer() {
        val candidates = listOf(video(1, 380f, 500f))
        assertNull(selectAutoplayVideo(candidates, viewport, null, emptySet()))
        assertEquals(1L, selectAutoplayVideo(candidates, viewport, 1, emptySet()))
    }

    @Test fun aHalfVisibleVideoReplacesACurrentVideoWithOnlyASliverVisible() {
        val candidates = listOf(video(1, 380f, 500f), video(2, 100f, 200f))
        assertEquals(2L, selectAutoplayVideo(candidates, viewport, 1, emptySet()))
    }

    @Test fun completelyOffscreenVideosAreReleased() {
        listOf(video(1, 400f, 500f), video(1, -100f, 0f)).forEach { candidate ->
            assertNull(selectAutoplayVideo(listOf(candidate), viewport, 1, emptySet()))
        }
    }

    @Test fun stoppingAVideoSuppressesBothInitialSelectionAndCurrentPlayerRetention() {
        val centered = video(1, 150f, 250f)
        assertNull(selectAutoplayVideo(listOf(centered), viewport, 1, setOf(1)))
        assertNull(selectAutoplayVideo(listOf(video(1, 380f, 500f)), viewport, 1, setOf(1)))
        assertEquals(2L, selectAutoplayVideo(listOf(centered, video(2, 0f, 100f)), viewport, 1, setOf(1)))
    }

    @Test fun horizontalIntersectionIsRequiredEvenForTheCurrentPlayer() {
        listOf(Rect(400f, 100f, 500f, 200f), Rect(-100f, 100f, 0f, 200f)).forEach { bounds ->
            assertNull(selectAutoplayVideo(listOf(VideoAutoplayCandidate(1, bounds)), viewport, 1, emptySet()))
        }
        assertEquals(1L, selectAutoplayVideo(listOf(VideoAutoplayCandidate(1, Rect(399f, 100f, 499f, 200f))),
            viewport, null, emptySet()))
    }

    @Test fun emptyOrInvalidGeometryNeverStartsAPlayer() {
        listOf(Rect.Zero, Rect(20f, 0f, 10f, 400f), Rect(0f, 0f, Float.NaN, 400f),
            Rect(0f, 0f, 400f, Float.POSITIVE_INFINITY)).forEach { invalid ->
            assertNull(selectAutoplayVideo(listOf(video(1, 100f, 200f)), invalid, 1, emptySet()))
            assertNull(selectAutoplayVideo(listOf(VideoAutoplayCandidate(1, invalid)), viewport, 1, emptySet()))
        }
        assertNull(selectAutoplayVideo(emptyList(), viewport, 1, emptySet()))
    }

    @Test fun aVideoFullyCoveredByTheHeaderCannotStartOrKeepPlaying() {
        val candidate = VideoAutoplayCandidate(1, Rect(100f, 20f, 300f, 100f))
        val header = Rect(80f, 0f, 320f, 120f)
        assertNull(selectAutoplayVideo(listOf(candidate), viewport, null, emptySet(), header))
        assertNull(selectAutoplayVideo(listOf(candidate), viewport, 1, emptySet(), header))
    }

    @Test fun aHeaderCoveredVideoNeedsHalfItsClippedAreaToStart() {
        val candidate = VideoAutoplayCandidate(1, Rect(100f, 20f, 300f, 120f))
        assertEquals(1L, selectAutoplayVideo(listOf(candidate), viewport, null, emptySet(), Rect(100f, 20f, 300f, 70f)))
        assertNull(selectAutoplayVideo(listOf(candidate), viewport, null, emptySet(), Rect(100f, 20f, 300f, 71f)))
        assertEquals(1L, selectAutoplayVideo(listOf(candidate), viewport, 1, emptySet(), Rect(100f, 20f, 300f, 119f)))
    }

    @Test fun videosBesideTheHeaderCanStartWithinTheSameVerticalBand() {
        val header = Rect(100f, 0f, 300f, 120f)
        listOf(Rect(0f, 20f, 80f, 100f), Rect(320f, 20f, 400f, 100f)).forEach { bounds ->
            assertEquals(1L, selectAutoplayVideo(listOf(VideoAutoplayCandidate(1, bounds)), viewport, null, emptySet(), header))
        }
    }

    @Test fun horizontalHeaderOverlapCountsTowardTheVisibleAreaThreshold() {
        val candidate = video(1, 0f, 100f)
        assertEquals(1L, selectAutoplayVideo(listOf(candidate), viewport, null, emptySet(), Rect(0f, 0f, 200f, 100f)))
        assertNull(selectAutoplayVideo(listOf(candidate), viewport, null, emptySet(), Rect(0f, 0f, 201f, 100f)))
    }

    @Test fun anUncoveredVideoReplacesTheCurrentPlayerWhenTheHeaderLeavesOnlyASliver() {
        val candidates = listOf(VideoAutoplayCandidate(1, Rect(100f, 20f, 300f, 120f)), video(2, 200f, 300f))
        assertEquals(2L, selectAutoplayVideo(candidates, viewport, 1, emptySet(), Rect(100f, 20f, 300f, 119f)))
    }

    @Test fun invalidHeaderGeometryCannotStartOrRetainPlayback() {
        val candidate = video(1, 150f, 250f)
        val invalidHeader = Rect(0f, 0f, Float.NaN, 120f)
        assertNull(selectAutoplayVideo(listOf(candidate), viewport, null, emptySet(), invalidHeader))
        assertNull(selectAutoplayVideo(listOf(candidate), viewport, 1, emptySet(), invalidHeader))
    }

    @Test fun aVideoBehindTheFloatingComposerCannotStartOrKeepPlaying() {
        val candidate = video(1, 310f, 370f)
        val composer = Rect(0f, 300f, 400f, 380f)
        assertNull(selectAutoplayVideo(listOf(candidate), viewport, null, emptySet(), bottomOcclusion = composer))
        assertNull(selectAutoplayVideo(listOf(candidate), viewport, 1, emptySet(), bottomOcclusion = composer))
    }

    @Test fun composerOverlapUsesTheSameHalfVisibleThresholdAsTheHeader() {
        val candidate = video(1, 250f, 350f)
        assertEquals(1L, selectAutoplayVideo(listOf(candidate), viewport, null, emptySet(),
            bottomOcclusion = Rect(0f, 300f, 400f, 380f)))
        assertNull(selectAutoplayVideo(listOf(candidate), viewport, null, emptySet(),
            bottomOcclusion = Rect(0f, 299f, 400f, 380f)))
    }

    private fun video(id: Long, top: Float, bottom: Float) =
        VideoAutoplayCandidate(id, Rect(0f, top, 400f, bottom))
}
