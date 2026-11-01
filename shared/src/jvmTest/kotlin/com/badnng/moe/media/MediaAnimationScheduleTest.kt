package com.badnng.moe.media

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class MediaAnimationScheduleTest {
    @Test
    fun animatedEmojiFinishesAtTheOriginalFirstFrameAndLeavesNoIdleWork() = runTest {
        val frames = mutableListOf<Long>()
        val schedule = MediaAnimationSchedule(300, 20.0)
        val job = launch {
            animateMediaOnce(schedule, nanoTime = { testScheduler.currentTime * 1_000_000 }) { frames += it }
        }
        advanceTimeBy(300)
        runCurrent()
        assertTrue(job.isCompleted)
        assertEquals(listOf(50L, 100L, 150L, 200L, 250L, 300L), frames)
        assertEquals(0L, schedule.frameMillis(frames.last() * 1_000_000))
        advanceTimeBy(10_000)
        assertEquals(6, frames.size)
        animateMediaOnce(schedule, initialMillis = 300L) { fail("A completed emoji cannot replay on activation.") }
    }

    @Test
    fun pausingOneShotResumesItsCurrentCycleAndExplicitReplayStartsOneNewCycle() = runTest {
        val schedule = MediaAnimationSchedule(300, 20.0)
        var frame = 0L
        val first = launch {
            animateMediaOnce(schedule, nanoTime = { testScheduler.currentTime * 1_000_000 }) { frame = it }
        }
        advanceTimeBy(125)
        first.cancel()
        runCurrent()
        assertEquals(100L, frame)
        advanceTimeBy(2_000)
        assertEquals(100L, frame)
        val resumed = launch {
            animateMediaOnce(schedule, frame, nanoTime = { testScheduler.currentTime * 1_000_000 }) { frame = it }
        }
        advanceTimeBy(200)
        runCurrent()
        assertTrue(resumed.isCompleted)
        assertEquals(300L, frame)
        val replayed = mutableListOf<Long>()
        animateMediaOnce(schedule, nanoTime = { testScheduler.currentTime * 1_000_000 }) { replayed += it }
        assertEquals(listOf(50L, 100L, 150L, 200L, 250L, 300L), replayed)
    }

    @Test
    fun twentyFpsStickerDoesNotAdvanceAtDisplayRefreshRate() = runTest {
        val frames = mutableListOf<Long>()
        val job = launch {
            animateMedia(MediaAnimationSchedule(3_000, 20.0), nanoTime = { testScheduler.currentTime * 1_000_000 }) {
                frames += it
            }
        }
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(20, frames.size)
        assertEquals((1L..20).map { it * 50 }, frames)
        job.cancel()
    }

    @Test
    fun stoppingAnimationLeavesNoScheduledUpdates() = runTest {
        var frames = 0
        val job = launch {
            animateMedia(MediaAnimationSchedule(1_000, 30.0), nanoTime = { testScheduler.currentTime * 1_000_000 }) {
                frames++
            }
        }
        advanceTimeBy(200)
        job.cancel()
        runCurrent()
        val paused = frames
        advanceTimeBy(5_000)
        assertEquals(paused, frames)
        assertTrue(paused in 5..6)
    }

    @Test
    fun oneFrameMediaDoesNotScheduleATimer() = runTest {
        val schedule = MediaAnimationSchedule(50, 20.0)
        assertFalse(schedule.animated)
        animateMedia(schedule) { fail("A still frame must not advance") }
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun delayedCallbacksSkipMissedFramesAndRespectLoopBoundary() {
        val schedule = MediaAnimationSchedule(1_000, 20.0)
        assertEquals(450L, schedule.frameMillis(499_000_000))
        assertEquals(1L, schedule.delayMillis(499_000_000))
        assertEquals(0L, schedule.frameMillis(1_002_000_000))
        assertEquals(48L, schedule.delayMillis(1_002_000_000))
    }

    @Test
    fun vectorAnimationIsCappedAtSixtyFpsAndInvalidRatesAreRejected() {
        val schedule = MediaAnimationSchedule(1_000, 240.0)
        assertEquals(17L, schedule.delayMillis(0))
        assertFailsWith<IllegalArgumentException> { MediaAnimationSchedule(1_000, Double.NaN) }
        assertFailsWith<IllegalArgumentException> { MediaAnimationSchedule(1_000, 0.0) }
    }
}
