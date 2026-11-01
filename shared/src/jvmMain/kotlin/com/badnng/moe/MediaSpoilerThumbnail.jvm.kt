package com.badnng.moe

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import com.badnng.moe.chat.MediaMiniThumbnail
import com.badnng.moe.media.AreaRasterImage
import com.badnng.moe.media.drawMediaImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.jetbrains.skia.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Semaphore
import kotlin.math.roundToInt

/** One bounded raster per mounted source; late worker completion cannot revive a disposed owner. */
internal class MediaSpoilerThumbnailHolder : AutoCloseable {
    private var disposed = false
    private var image: Image? = null
    private var preparation = 0L
    @Synchronized fun beginPreparation(): Long = ++preparation
    @Synchronized fun invalidatePreparation(request: Long) {
        if (request == preparation) preparation++
    }
    @Synchronized fun install(value: Image, request: Long? = null): Image? {
        if (disposed || request != null && request != preparation) { value.close(); return null }
        image?.close()
        image = value
        return value
    }
    @Synchronized fun draw(canvas: org.jetbrains.skia.Canvas, bounds: Rect) {
        if (!disposed) image?.let { canvas.drawMediaImage(it, bounds) }
    }
    @Synchronized fun drawCropped(canvas: org.jetbrains.skia.Canvas, width: Float, height: Float) {
        if (!disposed) image?.let {
            val factor = maxOf(width / it.width, height / it.height)
            val targetWidth = it.width * factor
            val targetHeight = it.height * factor
            canvas.drawMediaImage(it, Rect.makeXYWH((width - targetWidth) / 2f, (height - targetHeight) / 2f, targetWidth, targetHeight))
        }
    }
    @Synchronized override fun close() {
        disposed = true
        image?.close()
        image = null
    }
}

/** Downsample before blur on a worker. The source and every intermediate native object stay local. */
internal fun prepareMediaSpoilerThumbnail(path: String): Image {
    mediaSpoilerPreparationSlots.acquire()
    try { return prepareMediaSpoilerThumbnailOwned(path) }
    finally { mediaSpoilerPreparationSlots.release() }
}

private val mediaSpoilerPreparationSlots = Semaphore(2)

/** PrepareBlurredBackground first center-crops to the actual canvas aspect, then bounds large crops. */
internal fun prepareMediaPhotoBackground(path: String, viewport: IntSize): Image {
    require(viewport.width > 0 && viewport.height > 0)
    mediaSpoilerPreparationSlots.acquire()
    try { return prepareMediaSpoilerThumbnailOwned(path, viewport) }
    finally { mediaSpoilerPreparationSlots.release() }
}

/** Native minithumbnail bytes never need a temporary file or a full-media download. */
internal fun prepareMediaPhotoBackground(minithumbnail: MediaMiniThumbnail, viewport: IntSize): Image {
    require(viewport.width > 0 && viewport.height > 0)
    mediaSpoilerPreparationSlots.acquire()
    try { return prepareMediaMiniThumbnailOwned(minithumbnail, viewport) }
    finally { mediaSpoilerPreparationSlots.release() }
}

/** A ready native thumbnail wins; corrupt/missing local bytes may fall back to the inline source. */
internal fun prepareMediaPhotoBackground(path: String?, minithumbnail: MediaMiniThumbnail?, viewport: IntSize): Image {
    require(viewport.width > 0 && viewport.height > 0)
    require(path != null || minithumbnail != null)
    mediaSpoilerPreparationSlots.acquire()
    try {
        if (path != null) {
            try { return prepareMediaSpoilerThumbnailOwned(path, viewport) }
            catch (error: Exception) { if (minithumbnail == null) throw error }
        }
        return prepareMediaMiniThumbnailOwned(checkNotNull(minithumbnail), viewport)
    } finally { mediaSpoilerPreparationSlots.release() }
}

private fun prepareMediaMiniThumbnailOwned(minithumbnail: MediaMiniThumbnail, viewport: IntSize): Image {
    val bytes = minithumbnail.encodedCopy()
    require(bytes.size in 1..MediaMiniThumbnail.MAX_ENCODED_BYTES)
    return prepareMediaSpoilerThumbnailOwned(bytes, viewport, minithumbnail = true)
}

private fun prepareMediaSpoilerThumbnailOwned(path: String, photoViewport: IntSize? = null): Image {
    val file = Path.of(path)
    require(Files.isRegularFile(file) && Files.size(file) in 1..24_000_000L)
    val bytes = Files.readAllBytes(file)
    return prepareMediaSpoilerThumbnailOwned(bytes, photoViewport)
}

private fun prepareMediaSpoilerThumbnailOwned(bytes: ByteArray, photoViewport: IntSize?, minithumbnail: Boolean = false): Image {
    Data.makeFromBytes(bytes).use { data -> Codec.makeFromData(data).use { codec ->
        require(codec.width > 0 && codec.height > 0 && codec.width.toLong() * codec.height <= 24_000_000L)
        // The model's advertised dimensions cannot authorize a compressed oversized raster.
        if (minithumbnail) require(codec.width <= MediaMiniThumbnail.MAX_DIMENSION && codec.height <= MediaMiniThumbnail.MAX_DIMENSION)
    } }
    val decoded = Image.makeFromEncoded(bytes)
    val source = if (photoViewport != null) {
        try {
            val cropWidth = minOf(decoded.width.toLong(), maxOf(1L,
                photoViewport.width.toLong() * decoded.height / photoViewport.height)).toInt()
            val cropHeight = minOf(decoded.height.toLong(), maxOf(1L,
                photoViewport.height.toLong() * decoded.width / photoViewport.width)).toInt()
            Bitmap().use { bitmap ->
                val info = ImageInfo(cropWidth, cropHeight, ColorType.RGBA_8888, ColorAlphaType.PREMUL, decoded.colorSpace)
                check(bitmap.allocPixels(info) && decoded.readPixels(bitmap,
                    (decoded.width - cropWidth) / 2, (decoded.height - cropHeight) / 2))
                Image.makeFromBitmap(bitmap)
            }
        } finally { decoded.close() }
    } else decoded
    AreaRasterImage(source).use { raster ->
        val factor = if (photoViewport != null && source.width <= 100 && source.height <= 100) 1f
            else minOf(1f, MEDIA_SPOILER_THUMBNAIL_EDGE.toFloat() / maxOf(source.width, source.height))
        val width = (source.width * factor).roundToInt().coerceAtLeast(1)
        val height = (source.height * factor).roundToInt().coerceAtLeast(1)
        raster.prepare(width, height)
        val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL, source.colorSpace)
        val pixels = Surface.makeRaster(info).use { surface ->
            surface.canvas.clear(0)
            raster.draw(surface.canvas, Rect.makeWH(width.toFloat(), height.toFloat()))
            surface.makeImageSnapshot().use { image -> Bitmap().use { bitmap ->
                check(bitmap.allocPixels(info) && image.readPixels(bitmap))
                checkNotNull(bitmap.readPixels(info))
            } }
        }
        // Images::Blur(ignoreAlpha=true): radius-3 triangular kernel, once per axis.
        blurSpoilerPixels(pixels, width, height)
        return Image.makeRaster(info, pixels, width * 4)
    }
}

private fun blurSpoilerPixels(pixels: ByteArray, width: Int, height: Int) {
    val radius = 3
    if (width <= radius * 2 + 1 || height <= radius * 2 + 1) return
    val horizontal = ByteArray(pixels.size)
    for (y in 0 until height) for (x in 0 until width) for (channel in 0..3) {
        var sum = 0
        for (dx in -radius..radius) sum += (pixels[(y * width + (x + dx).coerceIn(0, width - 1)) * 4 + channel].toInt() and 255) *
            (radius + 1 - kotlin.math.abs(dx))
        horizontal[(y * width + x) * 4 + channel] = (sum shr 4).toByte()
    }
    for (y in 0 until height) for (x in 0 until width) for (channel in 0..3) {
        var sum = 0
        for (dy in -radius..radius) sum += (horizontal[((y + dy).coerceIn(0, height - 1) * width + x) * 4 + channel].toInt() and 255) *
            (radius + 1 - kotlin.math.abs(dy))
        pixels[(y * width + x) * 4 + channel] = (sum shr 4).toByte()
    }
}

@Composable
internal actual fun MediaSpoilerThumbnail(path: String, loadAllowed: Boolean, modifier: Modifier, contentScale: ContentScale) {
    val holder = remember(path) { MediaSpoilerThumbnailHolder() }
    var loaded by remember(holder) { mutableStateOf<Size?>(null) }
    val currentLoadAllowed by rememberUpdatedState(loadAllowed)
    DisposableEffect(holder) { onDispose { holder.close() } }
    LaunchedEffect(holder) {
        snapshotFlow { currentLoadAllowed }.first { it }
        loaded = withContext(Dispatchers.IO) {
            runCatching {
                val image = prepareMediaSpoilerThumbnail(path)
                val dimensions = Size(image.width.toFloat(), image.height.toFloat())
                holder.install(image)?.let { dimensions }
            }.getOrNull()
        }
    }
    val sourceSize = loaded
    if (sourceSize != null) Canvas(modifier.semantics { contentDescription = "模糊剧透缩略图" }) {
        val scale = contentScale.computeScaleFactor(sourceSize, size)
        val width = sourceSize.width * scale.scaleX
        val height = sourceSize.height * scale.scaleY
        drawIntoCanvas { holder.draw(it.skiaCanvas, Rect.makeXYWH((size.width - width) / 2f, (size.height - height) / 2f, width, height)) }
        // Media::Streaming::FillBlurredBackground darkens the image before the separate particle mask.
        drawRect(androidx.compose.ui.graphics.Color.Black.copy(alpha = 48f / 255f))
    }
}

/** A resize replaces one owned ready raster; stale/cancelled preparations cannot install over it. */
@Composable
internal actual fun MediaPhotoBackground(path: String?, loadAllowed: Boolean, modifier: Modifier, minithumbnail: MediaMiniThumbnail?) {
    PreparedBlurredMedia(path, loadAllowed, modifier, "照片模糊底图", minithumbnail)
}

@Composable
internal actual fun MediaLoadingThumbnail(path: String?, loadAllowed: Boolean, modifier: Modifier, minithumbnail: MediaMiniThumbnail?) {
    PreparedBlurredMedia(path, loadAllowed, modifier, "真实模糊缩略图", minithumbnail)
}

@Composable
private fun PreparedBlurredMedia(path: String?, loadAllowed: Boolean, modifier: Modifier, description: String, minithumbnail: MediaMiniThumbnail?) {
    // Ownership is the caller's mounted media identity. Keep its ready pixels across a
    // minithumbnail -> downloaded-thumbnail replacement and while a resize prepares.
    val holder = remember { MediaSpoilerThumbnailHolder() }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var revision by remember(holder) { mutableIntStateOf(0) }
    var failed by remember(path, minithumbnail) { mutableStateOf(false) }
    val request = remember(holder, path, minithumbnail, viewport, loadAllowed) { holder.beginPreparation() }
    DisposableEffect(holder) { onDispose { holder.close() } }
    DisposableEffect(holder, request) { onDispose { holder.invalidatePreparation(request) } }
    LaunchedEffect(holder, path, minithumbnail, viewport, loadAllowed) {
        val targetViewport = viewport
        if (failed || !loadAllowed || path == null && minithumbnail == null ||
            targetViewport.width <= 0 || targetViewport.height <= 0) return@LaunchedEffect
        val prepared = withContext(Dispatchers.IO) {
            runCatching { holder.install(prepareMediaPhotoBackground(path, minithumbnail, targetViewport), request) != null }
        }
        if (prepared.isSuccess) { if (prepared.getOrThrow()) revision++ }
        else failed = true
    }
    Canvas(modifier.onSizeChanged { viewport = it }.semantics { if (revision > 0) contentDescription = description }) {
        if (revision > 0 && size.width > 0f && size.height > 0f) {
            drawIntoCanvas { holder.drawCropped(it.skiaCanvas, size.width, size.height) }
            drawRect(androidx.compose.ui.graphics.Color.Black.copy(alpha = 48f / 255f))
        }
    }
}
