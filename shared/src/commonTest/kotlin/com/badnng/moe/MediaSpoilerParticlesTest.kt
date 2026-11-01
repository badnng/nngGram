package com.badnng.moe

import kotlin.math.hypot
import kotlin.test.*

class MediaSpoilerParticlesTest {
    @Test fun droppedFramesAdvanceByRealElapsedTimeWithoutSlowingTheMaskOrOverflowingItsCycle() {
        assertEquals(50L, advanceImageSpoilerTime(0, 50))
        assertEquals(99L, advanceImageSpoilerTime(50, 49))
        assertEquals(17L, advanceImageSpoilerTime(1970, 27))
        assertEquals(17L, advanceImageSpoilerTime(1970, IMAGE_SPOILER_CYCLE_MILLIS * 1000 + 27))
        assertEquals(99L, advanceImageSpoilerTime(99, -10))
        val particle = ImageSpoilerParticle(0, 2, 0.5f, 0.5f, 0.02f, -0.01f)
        assertEquals(imageSpoilerParticleFrame(particle, 99, 1f),
            imageSpoilerParticleFrame(particle, advanceImageSpoilerTime(0, 99), 1f))
    }

    @Test fun particleLifecycleUsesTheOfficialLinearFadeAndSixtyFrameLoop() {
        val particle = ImageSpoilerParticle(0, 2, 0.5f, 0.5f, 0.02f, -0.01f)
        assertNull(imageSpoilerParticleFrame(particle, 0, 1f))
        val first = assertNotNull(imageSpoilerParticleFrame(particle, 165, 1f))
        assertEquals(0.55f, first.opacity, 0.001f)
        assertEquals(67, first.x)
        assertEquals(62, first.y)
        val last = assertNotNull(imageSpoilerParticleFrame(particle, 495, 1f))
        assertEquals(0.35f, last.opacity, 0.001f)
        assertNull(imageSpoilerParticleFrame(particle, 627, 1f))
        assertEquals(first, imageSpoilerParticleFrame(particle, 165 + IMAGE_SPOILER_CYCLE_MILLIS, 1f))
        assertEquals(first, imageSpoilerParticleFrame(particle, 180, 1f), "Masks advance at 33ms, without invented intermediate trajectories.")
    }

    @Test fun densityAndFiveSpriteShapesKeepNativeSizeAndTravelInEveryDirection() {
        val values = (0..4).map { sprite ->
            assertNotNull(imageSpoilerParticleFrame(ImageSpoilerParticle(0, sprite, 0f, 0f, 0f, 0f), 165, 1.25f))
        }
        assertEquals(listOf(2f, 1.75f, 1.5f, 1.5f, 1.5f).map { it * 1.25f }, values.map { it.width })
        assertEquals(listOf(1.5f, 1.5f, 1.5f, 1.75f, 2f).map { it * 1.25f }, values.map { it.height })
        assertEquals(3000, imageSpoilerParticles.size)
        assertTrue(imageSpoilerParticles.all { hypot(it.dx, it.dy) * 600f in 9.99f..20.01f })
        assertEquals(setOf(true to true, true to false, false to true, false to false),
            imageSpoilerParticles.map { (it.dx > 0) to (it.dy > 0) }.toSet())
        assertTrue(imageSpoilerParticles.map { it.start }.distinct().size > 1900)
    }
}
