package com.badnng.moe.media

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.badnng.moe.chat.MediaFile
import com.badnng.moe.chat.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Rect
import top.yukonga.miuix.kmp.basic.Text

@Composable
internal actual fun rememberAccountAvatarPhoto(generation: Long, userId: Long?, file: MediaFile?): AccountAvatarPhoto? {
    val path = file?.localPath?.takeIf { userId != null && file.downloaded } ?: return null
    val photo = remember(generation, userId, file) { SharedAccountAvatarPhoto() }
    DisposableEffect(photo) { onDispose { photo.close() } }
    LaunchedEffect(photo) {
        val decoded = withContext(Dispatchers.IO) {
            try { photo.install(MediaDecoder.decode(path, MediaFormat.PHOTO)) } catch (_: Exception) { null }
        }
        photo.publish(decoded)
    }
    return photo
}

/** Own exactly one native image, including cancellation while the bounded decoder is running. */
internal class SharedAccountAvatarPhoto : AccountAvatarPhoto, AutoCloseable {
    private var disposed = false
    private var resource: DecodedMedia? = null
    private var decoded by mutableStateOf<DecodedMedia?>(null)
    private var failed by mutableStateOf(false)

    @Synchronized
    fun install(media: DecodedMedia): DecodedMedia? {
        if (disposed) {
            media.close()
            return null
        }
        // A workspace identity owns one result. A duplicate completion cannot add a cache entry.
        resource?.let { installed ->
            if (media !== installed) media.close()
            return installed
        }
        resource = media
        return media
    }

    @Synchronized
    fun publish(media: DecodedMedia?) {
        if (disposed || media !== resource) return
        decoded = media
        failed = media == null
    }

    @Synchronized
    override fun close() {
        if (disposed) return
        disposed = true
        val owned = resource
        resource = null
        decoded = null
        failed = false
        owned?.close()
    }

    @Composable
    override fun Content(modifier: Modifier, contentDescription: String) {
        val media = decoded
        var viewport by remember { mutableStateOf(IntSize.Zero) }
        val prepared = rememberPreparedMedia(media, viewport, ContentScale.Crop)
        val drawObserver = LocalMediaDrawObserver.current
        Box(modifier.onSizeChanged { viewport = it }, contentAlignment = Alignment.Center) {
            if (media == null) Text(if (failed) "头像暂时无法显示" else "正在加载头像……")
            else Canvas(Modifier.fillMaxSize().semantics { this.contentDescription = contentDescription }) {
                prepared?.revision
                // A newly mounted menu/profile reuses the ready account photo immediately.
                // A new size can prepare in the background without flashing a placeholder.
                val readyMedia = prepared?.media ?: media
                drawObserver?.invoke()
                val scale = ContentScale.Crop.computeScaleFactor(Size(readyMedia.width.toFloat(), readyMedia.height.toFloat()), size)
                val width = readyMedia.width * scale.scaleX
                val height = readyMedia.height * scale.scaleY
                val destination = Rect.makeXYWH((size.width - width) / 2, (size.height - height) / 2, width, height)
                clipRect { drawIntoCanvas { readyMedia.drawHighQuality(it.skiaCanvas, destination, 0L) } }
            }
        }
    }

    override fun toString() = "SharedAccountAvatarPhoto(<redacted>)"
}
