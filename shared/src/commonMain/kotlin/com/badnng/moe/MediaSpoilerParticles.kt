package com.badnng.moe

import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

// lib_ui@79c846f ui/effects/spoiler_mess.cpp: DefaultDescriptorImage / GenerateSpoilerMess.
internal const val IMAGE_SPOILER_TILE_DP = 128
internal const val IMAGE_SPOILER_PARTICLES = 3000
internal const val IMAGE_SPOILER_FRAME_MILLIS = 33L
internal const val IMAGE_SPOILER_FRAMES = 60
internal const val IMAGE_SPOILER_CYCLE_MILLIS = IMAGE_SPOILER_FRAME_MILLIS * IMAGE_SPOILER_FRAMES
internal const val IMAGE_SPOILER_FADE_MILLIS = 300L
internal const val IMAGE_SPOILER_LIFETIME_MILLIS = 600L

internal fun advanceImageSpoilerTime(accumulated: Long, elapsed: Long): Long =
    (accumulated % IMAGE_SPOILER_CYCLE_MILLIS + elapsed.coerceAtLeast(0L) % IMAGE_SPOILER_CYCLE_MILLIS) % IMAGE_SPOILER_CYCLE_MILLIS

internal data class ImageSpoilerParticle(
    val start: Long,
    val sprite: Int,
    val x: Float,
    val y: Float,
    val dx: Float,
    val dy: Float,
)

internal data class ImageSpoilerParticleFrame(val x: Int, val y: Int, val width: Float, val height: Float, val opacity: Float)

/** The official mask is random artwork. A fixed seed keeps synthetic previews reproducible. */
internal val imageSpoilerParticles: List<ImageSpoilerParticle> = run {
    val random = Random(0x53706F69)
    List(IMAGE_SPOILER_PARTICLES) { index ->
        val value = random.nextInt(2 * IMAGE_SPOILER_PARTICLES + 2)
        val negative = value < IMAGE_SPOILER_PARTICLES + 1
        val module = if (negative) value else value - IMAGE_SPOILER_PARTICLES - 1
        val speed = 10f + 10f * module / IMAGE_SPOILER_PARTICLES
        val max = 20 * IMAGE_SPOILER_LIFETIME_MILLIS.toInt()
        val x = (random.nextInt(2 * max + 1) - max).toFloat() / max
        val y = sqrt((1f - x * x).coerceAtLeast(0f)) * if (negative) -1f else 1f
        ImageSpoilerParticle(index * IMAGE_SPOILER_CYCLE_MILLIS / IMAGE_SPOILER_PARTICLES,
            random.nextInt(5), random.nextFloat(), random.nextFloat(),
            speed / IMAGE_SPOILER_LIFETIME_MILLIS * x, speed / IMAGE_SPOILER_LIFETIME_MILLIS * y)
    }
}

internal fun imageSpoilerParticleFrame(particle: ImageSpoilerParticle, timeMillis: Long, density: Float): ImageSpoilerParticleFrame? {
    val frameTime = (timeMillis / IMAGE_SPOILER_FRAME_MILLIS % IMAGE_SPOILER_FRAMES) * IMAGE_SPOILER_FRAME_MILLIS
    val age = (frameTime - particle.start + IMAGE_SPOILER_CYCLE_MILLIS) % IMAGE_SPOILER_CYCLE_MILLIS
    if (age <= 0 || age >= IMAGE_SPOILER_LIFETIME_MILLIS) return null
    val tile = (IMAGE_SPOILER_TILE_DP * density).roundToInt().coerceAtLeast(1)
    fun wrapped(value: Int) = ((value % tile) + tile) % tile
    val opacity = if (age < IMAGE_SPOILER_FADE_MILLIS) age.toFloat() / IMAGE_SPOILER_FADE_MILLIS
        else (IMAGE_SPOILER_LIFETIME_MILLIS - age).toFloat() / IMAGE_SPOILER_FADE_MILLIS
    val minimum = 1.5f * density
    val delta = 0.5f * density
    val width = if (particle.sprite < 2) minimum + delta * (2 - particle.sprite) / 2f else minimum
    val height = if (particle.sprite > 2) minimum + delta * (particle.sprite - 2) / 2f else minimum
    return ImageSpoilerParticleFrame(
        wrapped((particle.x * tile).toInt() + (age * particle.dx).roundToInt()),
        wrapped((particle.y * tile).toInt() + (age * particle.dy).roundToInt()), width, height, opacity)
}

