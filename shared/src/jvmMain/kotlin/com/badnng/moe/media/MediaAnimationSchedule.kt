package com.badnng.moe.media

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.ceil

/** Animation time advances only when the decoded media can display a different frame. */
internal class MediaAnimationSchedule(durationMillis: Long, framesPerSecond: Double) {
    val durationMillis = durationMillis.coerceIn(1, 60_000)
    private val durationNanos = this.durationMillis * 1_000_000
    private val intervalNanos = (1_000_000_000.0 / framesPerSecond.also {
        require(it.isFinite() && it > 0.0)
    }.coerceAtMost(60.0)).toLong().coerceAtLeast(1)
    val animated = durationNanos > intervalNanos

    fun frameMillis(elapsedNanos: Long): Long {
        val position = elapsedNanos.coerceAtLeast(0) % durationNanos
        return (position / intervalNanos * intervalNanos) / 1_000_000
    }

    fun delayMillis(elapsedNanos: Long): Long {
        val position = elapsedNanos.coerceAtLeast(0) % durationNanos
        val next = minOf((position / intervalNanos + 1) * intervalNanos, durationNanos)
        return ceil((next - position) / 1_000_000.0).toLong().coerceAtLeast(1)
    }
}

/** Isolated animated emoji completes one cycle and rests on frame zero, with no idle timer. */
internal suspend fun animateMediaOnce(
    schedule: MediaAnimationSchedule,
    initialMillis: Long = 0,
    nanoTime: () -> Long = System::nanoTime,
    onFrame: (Long) -> Unit,
) {
    if (!schedule.animated || initialMillis >= schedule.durationMillis) return
    val start = nanoTime() - initialMillis * 1_000_000
    var previous = initialMillis
    while (currentCoroutineContext().isActive) {
        val elapsed = (nanoTime() - start).coerceAtLeast(0)
        if (elapsed >= schedule.durationMillis * 1_000_000) {
            // Decoders resolve the exact duration to the original first frame.
            onFrame(schedule.durationMillis)
            return
        }
        val frame = schedule.frameMillis(elapsed)
        if (frame != previous) {
            onFrame(frame)
            previous = frame
        }
        delay(schedule.delayMillis(elapsed))
    }
}

internal suspend fun animateMedia(
    schedule: MediaAnimationSchedule,
    initialMillis: Long = 0,
    nanoTime: () -> Long = System::nanoTime,
    onFrame: (Long) -> Unit,
) {
    if (!schedule.animated) return
    val start = nanoTime() - initialMillis * 1_000_000
    var previous = initialMillis
    while (currentCoroutineContext().isActive) {
        val elapsed = (nanoTime() - start).coerceAtLeast(0)
        val frame = schedule.frameMillis(elapsed)
        if (frame != previous) {
            onFrame(frame)
            previous = frame
        }
        delay(schedule.delayMillis(elapsed))
    }
}
