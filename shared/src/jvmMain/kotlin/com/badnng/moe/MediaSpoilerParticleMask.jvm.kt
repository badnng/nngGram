package com.badnng.moe

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.jetbrains.skia.*
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.roundToInt

internal const val MEDIA_SPOILER_MASK_PIXEL_BUDGET = 4_194_304L
internal const val MEDIA_SPOILER_MASK_MAX_TILE = 256

/** Shared 60-frame premultiplied atlases, including pending generation, stay below the pixel budget. */
internal class MediaSpoilerMaskCache(
    private val pixelBudget: Long = MEDIA_SPOILER_MASK_PIXEL_BUDGET,
    private val prepare: (Int) -> Image = ::prepareMediaSpoilerMask,
) : AutoCloseable {
    internal class Entry(val tile: Int, val image: Image, var users: Int = 0)
    private val guard = Any()
    private val preparation = Semaphore(1)
    private val entries = linkedMapOf<Int, Entry>()
    private var closed = false
    private fun pixels(tile: Int) = tile.toLong() * tile * IMAGE_SPOILER_FRAMES

    fun acquire(requestedTile: Int): MediaSpoilerMaskLease {
        val tile = requestedTile.coerceIn(1, MEDIA_SPOILER_MASK_MAX_TILE)
        require(pixels(tile) <= pixelBudget)
        preparation.acquire()
        try {
            synchronized(guard) {
                check(!closed)
                entries[tile]?.let { it.users++; return MediaSpoilerMaskLease(this, it) }
                while (entries.values.sumOf { pixels(it.tile) } + pixels(tile) > pixelBudget) {
                    val unused = entries.values.firstOrNull { it.users == 0 } ?: break
                    entries.remove(unused.tile)
                    unused.image.close()
                }
                if (entries.values.sumOf { pixels(it.tile) } + pixels(tile) > pixelBudget) {
                    // Several windows/densities may overlap. Reuse a ready mask with linear sampling
                    // rather than grow the atlas cache or continually retry a larger preparation.
                    val ready = entries.values.minBy { kotlin.math.abs(it.tile - tile) }
                    ready.users++
                    return MediaSpoilerMaskLease(this, ready)
                }
            }
            val image = prepare(tile)
            synchronized(guard) {
                if (closed) { image.close(); error("Spoiler cache closed") }
                val entry = Entry(tile, image, users = 1)
                entries[tile] = entry
                return MediaSpoilerMaskLease(this, entry)
            }
        } finally { preparation.release() }
    }

    internal fun draw(entry: Entry, canvas: org.jetbrains.skia.Canvas, source: Rect, destination: Rect) = synchronized(guard) {
        if (entry.users > 0) canvas.drawImageRect(entry.image, source, destination, SamplingMode.LINEAR, null, true)
    }

    internal fun release(entry: Entry) = synchronized(guard) {
        check(entry.users > 0)
        entry.users--
        if (closed && entry.users == 0) { entries.remove(entry.tile); entry.image.close() }
    }

    override fun close() = synchronized(guard) {
        closed = true
        entries.values.filter { it.users == 0 }.toList().forEach { entries.remove(it.tile); it.image.close() }
    }
}

internal class MediaSpoilerMaskLease(private val cache: MediaSpoilerMaskCache, private val entry: MediaSpoilerMaskCache.Entry) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    fun draw(canvas: org.jetbrains.skia.Canvas, timeMillis: Long, destination: Rect) {
        if (closed.get()) return
        val index = (timeMillis / IMAGE_SPOILER_FRAME_MILLIS % IMAGE_SPOILER_FRAMES).toInt()
        val source = Rect.makeXYWH((index % 10 * entry.tile).toFloat(), (index / 10 * entry.tile).toFloat(),
            entry.tile.toFloat(), entry.tile.toFloat())
        cache.draw(entry, canvas, source, destination)
    }
    override fun close() { if (closed.compareAndSet(false, true)) cache.release(entry) }
}

private val sharedMediaSpoilerMasks = MediaSpoilerMaskCache()

/** GenerateSprite / GenerateSpoilerMess run only on a worker; UI draws ready atlas rectangles. */
internal fun prepareMediaSpoilerMask(tile: Int): Image {
    require(tile in 1..MEDIA_SPOILER_MASK_MAX_TILE)
    val density = tile.toFloat() / IMAGE_SPOILER_TILE_DP
    val spriteExtent = 2 + ceil(2f * density).toInt()
    return Surface.makeRasterN32Premul(tile * 10, tile * 6).use { surface ->
        val canvas = surface.canvas
        canvas.clear(0x20000000)
        Paint().use { paint ->
            paint.isAntiAlias = true
            repeat(IMAGE_SPOILER_FRAMES) { frameIndex ->
                val left = frameIndex % 10 * tile
                val top = frameIndex / 10 * tile
                canvas.save()
                canvas.clipRect(Rect.makeXYWH(left.toFloat(), top.toFloat(), tile.toFloat(), tile.toFloat()))
                for (particle in imageSpoilerParticles) {
                    val frame = imageSpoilerParticleFrame(particle, frameIndex * IMAGE_SPOILER_FRAME_MILLIS, density) ?: continue
                    paint.color = org.jetbrains.skia.Color.makeARGB((frame.opacity * 255).roundToInt(), 255, 255, 255)
                    fun sprite(dx: Int, dy: Int) {
                        canvas.drawRRect(RRect.makeXYWH((left + frame.x + dx + 1).toFloat(), (top + frame.y + dy + 1).toFloat(),
                            frame.width, frame.height, 0.75f * density), paint)
                    }
                    sprite(0, 0)
                    if (frame.x + spriteExtent > tile) {
                        sprite(-tile, 0)
                        if (frame.y + spriteExtent > tile) { sprite(0, -tile); sprite(-tile, -tile) }
                    } else if (frame.y + spriteExtent > tile) sprite(0, -tile)
                }
                canvas.restore()
            }
        }
        surface.makeImageSnapshot()
    }
}

/** Each mounted view releases its reference; a completion arriving after disposal releases immediately. */
private class MediaSpoilerMaskHolder : AutoCloseable {
    private var disposed = false
    private var lease: MediaSpoilerMaskLease? = null
    @Synchronized fun install(value: MediaSpoilerMaskLease): Boolean {
        if (disposed) { value.close(); return false }
        lease = value
        return true
    }
    @Synchronized fun draw(canvas: org.jetbrains.skia.Canvas, timeMillis: Long, bounds: Rect) { if (!disposed) lease?.draw(canvas, timeMillis, bounds) }
    @Synchronized override fun close() { disposed = true; lease?.close(); lease = null }
}

@Composable
internal actual fun MediaSpoilerParticleMask(timeMillis: Long, progress: Float, loadAllowed: Boolean, modifier: Modifier): Boolean {
    val density = LocalDensity.current.density
    val tile = (IMAGE_SPOILER_TILE_DP * density).roundToInt().coerceIn(1, MEDIA_SPOILER_MASK_MAX_TILE)
    val holder = remember(tile) { MediaSpoilerMaskHolder() }
    val currentLoadAllowed by rememberUpdatedState(loadAllowed)
    var ready by remember(holder) { mutableStateOf(false) }
    val observer = LocalMediaSpoilerFrameObserver.current
    DisposableEffect(holder) { onDispose { holder.close() } }
    LaunchedEffect(holder) {
        snapshotFlow { currentLoadAllowed }.first { it }
        ready = withContext(Dispatchers.IO) {
            runCatching { holder.install(sharedMediaSpoilerMasks.acquire(tile)) }.getOrDefault(false)
        }
    }
    Canvas(modifier.semantics { if (ready) contentDescription = "剧透粒子遮罩" }) {
        observer?.invoke(timeMillis, progress)
        if (ready) {
            val targetTile = IMAGE_SPOILER_TILE_DP * density
            val columns = ceil(size.width / targetTile).toInt()
            val rows = ceil(size.height / targetTile).toInt()
            drawIntoCanvas { canvas ->
                for (y in 0 until rows) for (x in 0 until columns) holder.draw(canvas.skiaCanvas, timeMillis,
                    Rect.makeXYWH(x * targetTile, y * targetTile, targetTile, targetTile))
            }
        } else drawRect(ComposeColor.Black.copy(alpha = 32f / 255f))
    }
    return ready
}
