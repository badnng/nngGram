package com.badnng.moe.media

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.badnng.moe.chat.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Rect
import kotlin.math.max

/** Physical pixels, with two samples per output pixel and the existing small-cell floor. */
internal fun reactionPreviewRasterEdge(displayEdgePx: Int, maxRasterEdge: Int = 192): Int {
    val doubled = displayEdgePx.coerceAtLeast(0).toLong() * 2
    return (((doubled + 15) / 16) * 16).coerceIn(96L, maxRasterEdge.toLong()).toInt()
}

@Composable
internal actual fun ReactionMediaPreview(
    staticPath: String?, staticFormat: MediaFormat?, animationPath: String?, animationFormat: MediaFormat?,
    animate: Boolean, staticFirstFrame: Boolean, modifier: Modifier, placeholder: @Composable () -> Unit,
) {
    var displayEdge by remember { mutableIntStateOf(0) }
    GridMediaPreview(staticPath, staticFormat, animationPath, animationFormat, animate, staticFirstFrame,
        reactionPreviewRasterEdge(displayEdge), modifier.onSizeChanged { displayEdge = max(it.width, it.height) },
        placeholder, highQuality = true)
}

@Composable
internal actual fun ReactionEffectPreview(
    path: String, format: MediaFormat, playbackId: Long, modifier: Modifier,
    onReady: (Boolean) -> Unit, onFinished: () -> Unit,
) {
    var displayEdge by remember { mutableIntStateOf(0) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    // Inline native around art has a 64 dp canvas, independent of the 18 dp
    // chip slot and the ordinary picker budget. At 2x density it needs 256 px.
    val rasterEdge = reactionPreviewRasterEdge(displayEdge, maxRasterEdge = 256)
    val load = rememberReactionEffectMedia(path, format, rasterEdge)
    val media = load.media
    val prepared = rememberPreparedMedia(media, viewport, ContentScale.Fit)
    var rendering by remember(load, playbackId) { mutableStateOf(false) }
    val elapsed = remember(playbackId) { mutableLongStateOf(0) }
    val finished by rememberUpdatedState(onFinished)
    val ready by rememberUpdatedState(onReady)
    DisposableEffect(load, playbackId) { onDispose { rendering = false; ready(false) } }
    LaunchedEffect(load, prepared?.media, playbackId) {
        if (!load.completed) return@LaunchedEffect
        val decoded = media
        if (decoded == null) {
            ready(false)
            finished()
            return@LaunchedEffect
        }
        // Native decode and target-size preparation complete independently. Pending pixels
        // keep the stationary glyph visible and do not consume this effect's playback clock.
        if (prepared == null || prepared.media !== decoded) return@LaunchedEffect
        // Acquire glyph ownership before exposing even the first decoded frame.
        // The static layer observes that same state directly in graphicsLayer,
        // avoiding a center -> flight SideEffect -> footer recomposition delay.
        ready(true)
        rendering = true
        val schedule = decoded.animationSchedule
        if (schedule == null || !schedule.animated) {
            delay(180)
        } else {
            val start = System.nanoTime()
            val durationNanos = decoded.durationMillis.coerceIn(1, 4000) * 1_000_000L
            var time = 0L
            while (time < durationNanos) {
                elapsed.longValue = schedule.frameMillis(time)
                delay(schedule.delayMillis(time))
                time = (System.nanoTime() - start).coerceAtLeast(0)
            }
        }
        rendering = false
        ready(false)
        finished()
    }
    val drawObserver = LocalMediaDrawObserver.current
    Canvas(modifier.onSizeChanged {
        displayEdge = max(it.width, it.height)
        viewport = it
    }) {
        if (!rendering) return@Canvas
        val drawing = prepared ?: return@Canvas
        drawing.revision
        val decoded = drawing.media
        drawObserver?.invoke()
        val fit = ContentScale.Fit.computeScaleFactor(Size(decoded.width.toFloat(), decoded.height.toFloat()), size)
        val width = decoded.width * fit.scaleX
        val height = decoded.height * fit.scaleY
        drawIntoCanvas {
            decoded.drawHighQuality(it.skiaCanvas, Rect.makeXYWH((size.width - width) / 2, (size.height - height) / 2,
                width, height), elapsed.longValue)
        }
    }
}

/** A failed download path/decoder is a completed load, not an indefinitely blank effect. */
private data class ReactionEffectLoad(val completed: Boolean = false, val media: DecodedMedia? = null)

@Composable
private fun rememberReactionEffectMedia(path: String, format: MediaFormat, rasterEdge: Int): ReactionEffectLoad {
    val holder = remember(path, format, rasterEdge) { ReactionMediaHolder() }
    var load by remember(holder) { mutableStateOf(ReactionEffectLoad()) }
    DisposableEffect(holder) { onDispose { holder.close() } }
    LaunchedEffect(holder) {
        val decoded = withContext(Dispatchers.IO) {
            try { holder.install(MediaDecoder.decodeGridPreview(path, format, false, rasterEdge)) }
            catch (_: Exception) { null }
        }
        load = ReactionEffectLoad(completed = true, media = decoded)
    }
    return load
}

/** The two lazy native resources are shared by reaction and composer grids, with bounded cell-specific rasters. */
@Composable
internal fun GridMediaPreview(
    staticPath: String?, staticFormat: MediaFormat?, animationPath: String?, animationFormat: MediaFormat?,
    animate: Boolean, staticFirstFrame: Boolean, rasterEdge: Int, modifier: Modifier, placeholder: @Composable () -> Unit,
    highQuality: Boolean = false,
) {
    var animationRequested by remember(animationPath, animationFormat) { mutableStateOf(false) }
    SideEffect { if (animate) animationRequested = true }
    val still = rememberReactionMedia(staticPath, staticFormat, true, staticFirstFrame, rasterEdge)
    val shared = staticPath != null && staticPath == animationPath && staticFormat == animationFormat &&
        !(staticFirstFrame && staticFormat == MediaFormat.WEBM)
    val animation = if (shared) still else rememberReactionMedia(animationPath, animationFormat, animationRequested, false, rasterEdge)
    val elapsed = remember(animation) { mutableLongStateOf(0) }
    LaunchedEffect(animation, animate) {
        val schedule = animation?.animationSchedule
        if (animate && schedule != null) animateMedia(schedule) { elapsed.longValue = it }
        else elapsed.longValue = 0
    }
    val media = if (animate) animation ?: still else still ?: animation
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val prepared = rememberPreparedMedia(media, viewport, ContentScale.Fit)
    val drawObserver = LocalMediaDrawObserver.current
    Box(modifier.onSizeChanged { viewport = it }, contentAlignment = Alignment.Center) {
        if (prepared == null) placeholder()
        else Canvas(Modifier.fillMaxSize()) {
            prepared.revision
            val readyMedia = prepared.media
            drawObserver?.invoke()
            val fit = ContentScale.Fit.computeScaleFactor(Size(readyMedia.width.toFloat(), readyMedia.height.toFloat()), size)
            val width = readyMedia.width * fit.scaleX
            val height = readyMedia.height * fit.scaleY
            val bounds = Rect.makeXYWH((size.width - width) / 2, (size.height - height) / 2, width, height)
            clipRect { drawIntoCanvas {
                val time = if (animate && readyMedia === animation) elapsed.longValue else 0
                if (highQuality) readyMedia.drawHighQuality(it.skiaCanvas, bounds, time)
                else readyMedia.draw(it.skiaCanvas, bounds, time)
            } }
        }
    }
}

@Composable
private fun rememberReactionMedia(path: String?, format: MediaFormat?, load: Boolean, firstFrame: Boolean, rasterEdge: Int): DecodedMedia? {
    val holder = remember(path, format, firstFrame, rasterEdge) { ReactionMediaHolder() }
    var value by remember(holder) { mutableStateOf<DecodedMedia?>(null) }
    DisposableEffect(holder) { onDispose { holder.close() } }
    LaunchedEffect(holder, load) {
        if (load && path != null && format != null && value == null) {
            value = withContext(Dispatchers.IO) {
                try { holder.install(MediaDecoder.decodeGridPreview(path, format, firstFrame, rasterEdge)) }
                catch (_: Exception) { null }
            }
        }
    }
    return value
}

/** No global image cache: lazy-grid disposal releases both native resources. */
private class ReactionMediaHolder : AutoCloseable {
    private var disposed = false
    private var value: DecodedMedia? = null
    @Synchronized fun install(media: DecodedMedia): DecodedMedia? {
        if (disposed) { media.close(); return null }
        value?.let { installed -> if (installed !== media) media.close(); return installed }
        value = media
        return media
    }
    @Synchronized override fun close() {
        if (disposed) return
        disposed = true
        val owned = value
        value = null
        owned?.close()
    }
}
