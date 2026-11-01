package com.badnng.moe.media

import org.jetbrains.skia.Canvas
import androidx.compose.runtime.mutableIntStateOf
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt

internal const val MediaDerivedPixelBudget = 4_194_304L
private const val MAX_CACHED_TARGETS = 8
private val areaSlots = Semaphore(2)
private val areaWorkers = ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(32),
    { work -> Thread(work, "media-area-sampling").apply { isDaemon = true } })

/** Streaming/fallback drawing is explicitly linear, never nearest or a negative-lobed cubic. */
internal fun Canvas.drawMediaImage(image: Image, destination: Rect) {
    drawImageRect(image, Rect.makeWH(image.width.toFloat(), image.height.toFloat()), destination,
        SamplingMode.LINEAR, null, true)
}

/** One source and bounded area-filtered targets; the pixel budget is shared across animation frames. */
internal class AreaRasterImage(
    private val source: Image,
    private val pixelBudget: Long = MediaDerivedPixelBudget,
    private val readSourcePixels: (Image, ImageInfo) -> ByteArray = ::readAreaSourcePixels,
) : AutoCloseable {
    val width = source.width
    val height = source.height
    private val guard = Any()
    private val targets = linkedMapOf<Pair<Int, Int>, Image>()
    private val revision = mutableIntStateOf(0)
    private var closed = false
    private var readers = 0
    private var sourceClosed = false
    private var preparationFailed = false
    private var queued: Pair<Int, Int>? = null
    private var desiredTarget: Pair<Int, Int>? = null

    fun prepare(widthPx: Int, heightPx: Int) {
        val key = widthPx to heightPx
        // Only downsampling needs area integration. Preserve the original at 1:1/upscale.
        if (widthPx !in 1..width || heightPx !in 1..height || key == (width to height) ||
            widthPx.toLong() * heightPx > pixelBudget || width.toLong() * height > 24_000_000L) return
        synchronized(guard) {
            if (closed || preparationFailed || key in targets) return
        }
        var prepared: Image? = null
        var reading = false
        areaSlots.acquire()
        try {
            synchronized(guard) {
                if (closed || preparationFailed || key in targets) return
                readers++
                reading = true
            }
            val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL, source.colorSpace)
            val bytes = readSourcePixels(source, info)
            val output = areaPixels(bytes, width, height, widthPx, heightPx)
            prepared = Image.makeRaster(ImageInfo(widthPx, heightPx, ColorType.RGBA_8888,
                ColorAlphaType.PREMUL, source.colorSpace), output, widthPx * 4)
            synchronized(guard) {
                if (!closed && key !in targets) {
                    while (targets.isNotEmpty() && (targets.size >= MAX_CACHED_TARGETS ||
                            targets.values.sumOf { it.width.toLong() * it.height } + widthPx.toLong() * heightPx > pixelBudget)) {
                        targets.remove(targets.keys.first())?.close()
                    }
                    targets[key] = checkNotNull(prepared)
                    prepared = null
                    revision.intValue++
                }
            }
        } catch (error: Exception) {
            // The source is immutable. Keep its linear fallback after a read/preparation
            // failure instead of alternating failed sizes from several mounted consumers.
            synchronized(guard) { preparationFailed = true }
            throw error
        } finally {
            prepared?.close()
            if (reading) synchronized(guard) { readers--; closeSourceIfIdle() }
            areaSlots.release()
        }
    }

    fun draw(canvas: Canvas, destination: Rect, requestPreparation: Boolean = true) {
        revision.intValue // Background completion invalidates this draw without a frame loop.
        val matrix = canvas.localToDeviceAsMatrix33.mat
        val affine = matrix[6] == 0f && matrix[7] == 0f && matrix[8] == 1f
        val targetWidth = (destination.width * hypot(matrix[0], matrix[3])).roundToInt().coerceAtLeast(1)
        val targetHeight = (destination.height * hypot(matrix[1], matrix[4])).roundToInt().coerceAtLeast(1)
        val key = targetWidth to targetHeight
        synchronized(guard) {
            if (closed) return
            desiredTarget = key
            val prepared = if (affine) targets[key] else null
            canvas.drawMediaImage(prepared ?: source, destination)
            // Viewer transforms can change physical sampling without a new layout measurement.
            // Coalesce one bounded background request; draw never reads/resizes source pixels.
            if (requestPreparation && prepared == null && affine && !preparationFailed && queued == null && targetWidth <= width && targetHeight <= height &&
                key != (width to height) && targetWidth.toLong() * targetHeight <= pixelBudget) {
                queued = key
                try {
                    areaWorkers.execute {
                        try { prepare(targetWidth, targetHeight) } catch (_: Exception) { /* Keep linear fallback. */ }
                        finally { synchronized(guard) {
                            queued = null
                            // A transform can change while an older preparation is running.
                            // Wake that new target only; repeated failures of the same target
                            // must not create a draw/prepare/invalidate loop while idle.
                            if (!closed && !preparationFailed && desiredTarget != key) revision.intValue++
                        } }
                    }
                } catch (_: RejectedExecutionException) { queued = null }
            }
        }
    }

    override fun close() = synchronized(guard) {
        if (!closed) {
            closed = true
            targets.values.forEach(Image::close)
            targets.clear()
            closeSourceIfIdle()
        }
    }

    private fun closeSourceIfIdle() {
        if (closed && readers == 0 && !sourceClosed) { sourceClosed = true; source.close() }
    }
}

/** Native readback boundary; callers retain the source-reading lease until processing ends. */
private fun readAreaSourcePixels(source: Image, info: ImageInfo): ByteArray = Bitmap().use { bitmap ->
    check(bitmap.allocPixels(info) && source.readPixels(bitmap)) { "Image pixels unavailable" }
    checkNotNull(bitmap.readPixels(info))
}

/** FFmpeg emits straight RGBA; interpolate only after conversion to premultiplied storage. */
internal fun premultiplyMediaRgba(bytes: ByteArray) {
    for (index in bytes.indices step 4) {
        val alpha = bytes[index + 3].toInt() and 255
        if (alpha < 255) for (channel in 0..2) {
            bytes[index + channel] = (((bytes[index + channel].toInt() and 255) * alpha + 127) / 255).toByte()
        }
    }
}

/** Positive source-pixel coverage in premultiplied RGBA; no sharpening, gamma or blur pass. */
private fun areaPixels(source: ByteArray, width: Int, height: Int, targetWidth: Int, targetHeight: Int): ByteArray {
    val result = ByteArray(targetWidth * targetHeight * 4)
    val scaleX = width.toDouble() / targetWidth
    val scaleY = height.toDouble() / targetHeight
    val weight = 1.0 / (scaleX * scaleY)
    for (y in 0 until targetHeight) {
        val top = y * scaleY
        val bottom = (y + 1) * scaleY
        for (x in 0 until targetWidth) {
            val left = x * scaleX
            val right = (x + 1) * scaleX
            var red = 0.0
            var green = 0.0
            var blue = 0.0
            var alpha = 0.0
            for (sy in floor(top).toInt() until ceil(bottom).toInt().coerceAtMost(height)) {
                val yWeight = minOf(bottom, sy + 1.0) - maxOf(top, sy.toDouble())
                for (sx in floor(left).toInt() until ceil(right).toInt().coerceAtMost(width)) {
                    val coverage = (minOf(right, sx + 1.0) - maxOf(left, sx.toDouble())) * yWeight
                    val input = (sy * width + sx) * 4
                    red += (source[input].toInt() and 255) * coverage
                    green += (source[input + 1].toInt() and 255) * coverage
                    blue += (source[input + 2].toInt() and 255) * coverage
                    alpha += (source[input + 3].toInt() and 255) * coverage
                }
            }
            val output = (y * targetWidth + x) * 4
            result[output] = (red * weight).roundToInt().coerceIn(0, 255).toByte()
            result[output + 1] = (green * weight).roundToInt().coerceIn(0, 255).toByte()
            result[output + 2] = (blue * weight).roundToInt().coerceIn(0, 255).toByte()
            result[output + 3] = (alpha * weight).roundToInt().coerceIn(0, 255).toByte()
        }
    }
    return result
}
