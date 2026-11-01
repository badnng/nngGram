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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import com.badnng.moe.chat.MediaFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Rect
import top.yukonga.miuix.kmp.basic.Text
import kotlin.math.roundToInt

/** Optional synthetic-probe instrumentation; never observes paths or account content. */
internal val LocalMediaDrawObserver = staticCompositionLocalOf<(() -> Unit)?> { null }

/** A new revision invalidates drawing after the same native owner installs a prepared raster. */
internal data class PreparedMedia(val media: DecodedMedia, val revision: Long)

/**
 * Prepare the full image's physical display size off the UI thread. Crop can extend beyond
 * the viewport; its full scaled dimensions, rather than the clipped square, determine filtering.
 * The existing owner retains a ready image through a resize while its next raster is prepared.
 */
@Composable
internal fun rememberPreparedMedia(
    media: DecodedMedia?,
    viewport: IntSize,
    contentScale: ContentScale,
    outputGate: MediaOutputGate? = null,
): PreparedMedia? {
    var prepared by remember(media) { mutableStateOf<PreparedMedia?>(null) }
    LaunchedEffect(media, viewport, contentScale, outputGate) {
        val source = media ?: return@LaunchedEffect
        if (outputGate?.active() == false) return@LaunchedEffect
        if (viewport.width <= 0 || viewport.height <= 0) return@LaunchedEffect
        val scale = contentScale.computeScaleFactor(
            Size(source.width.toFloat(), source.height.toFloat()),
            Size(viewport.width.toFloat(), viewport.height.toFloat()),
        )
        val width = (source.width * scale.scaleX).roundToInt().coerceAtLeast(1)
        val height = (source.height * scale.scaleY).roundToInt().coerceAtLeast(1)
        try {
            withContext(Dispatchers.IO) { if (outputGate?.active() != false) source.prepareForSize(width, height); Unit }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A bounded preparation failure still exposes the owner's safe linear fallback.
        }
        currentCoroutineContext().ensureActive()
        if (outputGate?.active() == false) return@LaunchedEffect
        prepared = PreparedMedia(source, (prepared?.revision ?: 0L) + 1L)
    }
    return prepared
}

@Composable
actual fun MediaPreview(
    path: String,
    format: MediaFormat,
    modifier: Modifier,
    contentDescription: String?,
    animate: Boolean,
    placeholder: (@Composable () -> Unit)?,
    contentScale: ContentScale,
    onLoadResult: ((Boolean) -> Unit)?,
    highQuality: Boolean,
    playOnce: Boolean,
    replayKey: Long,
    fitzpatrickType: Int,
    alignTopStart: Boolean,
    onPrepared: ((Boolean) -> Unit)?,
    outputGate: MediaOutputGate?,
) {
    val holder = remember(path, format, fitzpatrickType, outputGate) { MediaHolder() }
    var loaded by remember(holder) { mutableStateOf<DecodedMedia?>(null) }
    var failed by remember(holder) { mutableStateOf(false) }
    // Retain the measured viewport across same-size path replacements: no new measure event
    // is guaranteed merely because a different native image becomes the owner.
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val elapsed = remember(holder, playOnce, replayKey) { mutableLongStateOf(0L) }
    val drawObserver = LocalMediaDrawObserver.current
    val currentLoadResult by rememberUpdatedState(onLoadResult)
    val currentPrepared by rememberUpdatedState(onPrepared)
    DisposableEffect(holder) { onDispose { holder.close() } }
    LaunchedEffect(holder) {
        val media = withContext(Dispatchers.IO) {
            try {
                if (outputGate?.active() == false) null else {
                    val decoded = MediaDecoder.decode(path, format, fitzpatrickType)
                    if (outputGate?.active() == false) { decoded.close(); null } else holder.install(decoded)
                }
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        }
        if (outputGate?.active() == false) return@LaunchedEffect
        loaded = media
        failed = media == null
        currentLoadResult?.invoke(media != null)
    }
    val prepared = rememberPreparedMedia(loaded, viewport, contentScale, outputGate)
    LaunchedEffect(holder, prepared != null) { if (outputGate?.active() != false) currentPrepared?.invoke(prepared != null) }
    LaunchedEffect(prepared?.media, animate, playOnce, replayKey) {
        val schedule = prepared?.media?.animationSchedule
        if (schedule != null && animate) {
            if (playOnce) animateMediaOnce(schedule, elapsed.longValue) { elapsed.longValue = it }
            else animateMedia(schedule, elapsed.longValue) { elapsed.longValue = it }
        }
    }
    Box(modifier.onSizeChanged { viewport = it }, contentAlignment = Alignment.Center) {
        if (prepared != null) {
            Canvas(Modifier.fillMaxSize().semantics {
                contentDescription?.let { this.contentDescription = it }
            }) {
                if (outputGate?.active() == false) return@Canvas
                // Capture the revision-bearing value, so resizing the same media invalidates
                // this draw after its background area preparation completes.
                val media = prepared.media
                drawObserver?.invoke()
                val scale = contentScale.computeScaleFactor(Size(media.width.toFloat(), media.height.toFloat()), size)
                val width = media.width * scale.scaleX
                val height = media.height * scale.scaleY
                val left = if (alignTopStart) {
                    if (layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl) size.width - width else 0f
                } else (size.width - width) / 2
                val top = if (alignTopStart) 0f else (size.height - height) / 2
                val bounds = Rect.makeXYWH(left, top, width, height)
                clipRect { drawIntoCanvas {
                    if (highQuality) media.drawHighQuality(it.skiaCanvas, bounds, elapsed.longValue)
                    else media.draw(it.skiaCanvas, bounds, elapsed.longValue)
                } }
            }
        } else {
            if (placeholder != null) placeholder()
            else Text(if (failed) "媒体暂时无法显示" else "正在解码……")
        }
    }
}

/** A cancelled background load still disposes its native object after it finishes. */
private class MediaHolder : AutoCloseable {
    private var disposed = false
    private var value: DecodedMedia? = null
    @Synchronized fun install(media: DecodedMedia): DecodedMedia? {
        if (disposed) { media.close(); return null }
        value = media
        return media
    }
    @Synchronized override fun close() {
        disposed = true
        value?.close()
        value = null
    }
}
