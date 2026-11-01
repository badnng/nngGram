package com.badnng.moe

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Contract values from pinned Desktop a7f453612f4896ac04d0007a36ab0d377e5bcbcf. */
class ProfileEmojiArtworkGeometryTest {
    @Test fun replyUsesNineRightAnchoredInstancesRatherThanATile() {
        val points = officialProfileReplyPattern(240f, false)
        assertEquals(listOf(
            ProfileReplyPatternPoint(212f, 4f, 20, .32f),
            ProfileReplyPatternPoint(189f, 15f, 16, .32f),
            ProfileReplyPatternPoint(176f, -2f, 12, .28f),
            ProfileReplyPatternPoint(153f, 11f, 16, .24f),
            ProfileReplyPatternPoint(115f, -2f, 20, .16f),
            ProfileReplyPatternPoint(212f, 31f, 16, .24f),
            ProfileReplyPatternPoint(168f, 33f, 20, .20f),
            ProfileReplyPatternPoint(194f, 52f, 16, .24f),
            ProfileReplyPatternPoint(216f, 55f, 20, .18f),
        ), points)
        val wider = officialProfileReplyPattern(340f, false)
        points.zip(wider).forEach { (old, next) -> assertEquals(old.copy(x = old.x + 100f), next) }
        assertTrue(officialProfileReplyPattern(24f, false).any { it.x < 0 },
            "Narrow surfaces retain official geometry and leave cropping to their actual clip.")
    }

    @Test fun quoteShiftsTheNineInstancesAndAddsOnlyItsTwoOfficialPoints() {
        val ordinary = officialProfileReplyPattern(240f, false)
        val quote = officialProfileReplyPattern(240f, true)
        assertEquals(11, quote.size)
        ordinary.zip(quote.take(9)).forEach { (old, next) -> assertEquals(old.copy(x = old.x - 12f), next) }
        assertEquals(listOf(ProfileReplyPatternPoint(224f, 23f, 16, .28f),
            ProfileReplyPatternPoint(228f, 48f, 12, .24f)), quote.drop(9))
    }

    @Test fun profileEighteenPointsFollowTheActualAvatarAndRetainIndependentTiming() {
        val avatar = Rect(100f, 32f, 164f, 96f)
        val points = officialProfilePatternPoints(avatar)
        assertEquals(18, points.size)
        assertEquals(listOf(20, 20, 23, 18, 24, 24, 19, 19, 17, 17, 20, 20, 20, 19, 21, 18, 19, 19),
            points.map { it.size })
        assertEquals(listOf(.02f to .42f, 0f to .32f, 0f to .4f, 0f to .4f, 0f to .4f, 0f to .4f,
            .14f to .6f, .16f to .64f, .14f to .7f, .14f to .9f, .2f to .75f, .2f to .85f,
            .09f to .45f, .09f to .45f, .09f to .45f, .11f to .45f, .14f to .75f, .2f to .8f),
            points.map { it.start to it.end })
        near(Offset(132f, 12.8f), points[0].center)
        near(Offset(132f, 115.2f), points[1].center)
        near(Offset(87.2f, 41.6f), points[2].center)
        near(Offset(172.8f, 86.4f), points[5].center)
        near(Offset(23.2f, 64f), points[16].center)
        near(Offset(240.8f, 64f), points[17].center)
        val moved = officialProfilePatternPoints(avatar.translate(Offset(-70f, 20f)))
        points.zip(moved).forEach { (before, after) ->
            near(before.center + Offset(-70f, 20f), after.center)
            assertEquals(before.copy(center = after.center), after)
        }
    }

    @Test fun profilePlacementUsesTheNormalCanvasDistanceFadeAndBottomFade() {
        val avatar = Rect(100f, 32f, 164f, 96f)
        val points = officialProfilePatternPoints(avatar)
        assertTrue(points.all { officialProfilePatternPlacement(it, avatar, 0f) == null })
        assertNull(officialProfilePatternPlacement(points.first(), Rect.Zero, 1f))
        points.forEach { point ->
            val placed = assertNotNull(officialProfilePatternPlacement(point, avatar, 1f))
            near(point.center, placed.rect.center)
            near(18f, placed.rect.width)
            near(18f, placed.rect.height)
            assertTrue(placed.alpha in 0f..0.5f)
        }
        val top = assertNotNull(officialProfilePatternPlacement(points[0], avatar, 1f))
        val bottom = assertNotNull(officialProfilePatternPlacement(points[1], avatar, 1f))
        near(.3f, top.alpha)
        near(.24f, bottom.alpha)
        val collapsed = assertNotNull(officialProfilePatternPlacement(points[0], avatar, .7f))
        assertTrue(collapsed.rect.width < 18f)
        assertTrue(collapsed.alpha < top.alpha)
        assertTrue(collapsed.rect.center.y > top.rect.center.y,
            "Partial progress moves this top point toward the avatar while applying the official y offset.")
    }

    private fun near(expected: Float, actual: Float) = assertTrue(abs(expected - actual) < .0001f,
        "Expected $expected, got $actual")
    private fun near(expected: Offset, actual: Offset) { near(expected.x, actual.x); near(expected.y, actual.y) }
}
