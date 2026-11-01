package com.badnng.moe

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.MediaFormat
import com.badnng.moe.chat.MessageMedia
import com.badnng.moe.chat.StickerOutline
import com.badnng.moe.media.MediaPreview
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Bare sticker artwork: no card, inner media fill, rounded mask, emoji or download label. */
@Composable
internal fun StickerMessageMedia(
    media: MessageMedia,
    snapshot: ChatSnapshot,
    features: ChatFeatureActions,
    visible: Boolean,
    animate: Boolean,
    modifier: Modifier = Modifier,
) {
    val loop = LocalSettingsPreferences.current.stickerLoop
    val file = snapshot.mediaFiles[media.file.id] ?: media.file
    val thumbnail = media.thumbnail?.let { snapshot.mediaFiles[it.id] ?: it }
    val outline = snapshot.stickerOutlines[file.id]
    val error = snapshot.fileErrors[file.id]
    val busy = file.downloading || file.id in snapshot.downloadingFiles
    val path = file.localPath?.takeIf { file.downloaded }
    val thumbnailPath = thumbnail?.localPath?.takeIf { thumbnail.downloaded }
    var decodeFailed by remember(file.id, path, thumbnailPath) { mutableStateOf(false) }
    MediaArtworkVisibility(snapshot, features, file, visible)
    LaunchedEffect(snapshot.token, file.id, visible) {
        if (visible && file.id > 0 && outline == null) features.loadStickerOutline(snapshot.token, file.id)
    }
    val retry = visible && error != null && !busy && file.canDownload
    val interactionSource = remember(file.id) { MutableInteractionSource() }
    Box(modifier.then(if (retry) Modifier.clickable(
        interactionSource = interactionSource, indication = null,
        onClickLabel = "重试贴纸下载", onClick = { features.downloadFile(snapshot.token, file.id) },
    ) else Modifier)) {
        val loading: @Composable () -> Unit = {
            StickerLoadingOutline(outline, animate = visible && animate && error == null && !decodeFailed)
        }
        when {
            path != null -> MediaPreview(path, media.format, Modifier.fillMaxSize(),
                contentDescription = "贴纸 ${media.emoji}", animate = animate && visible,
                placeholder = loading, contentScale = ContentScale.Fit,
                onLoadResult = { decodeFailed = !it }, playOnce = !loop)
            thumbnailPath != null -> MediaPreview(thumbnailPath, media.thumbnailFormat ?: MediaFormat.PHOTO,
                Modifier.fillMaxSize(), contentDescription = "贴纸缩略图", animate = false,
                placeholder = loading, contentScale = ContentScale.Fit,
                onLoadResult = { decodeFailed = !it })
            else -> loading()
        }
    }
}

/** The pinned PathShiftGradient sweeps for 1 s, then rests for 1 s, inside the native silhouette. */
@Composable
internal fun StickerLoadingOutline(outline: StickerOutline?, animate: Boolean, modifier: Modifier = Modifier) {
    val path = remember(outline?.svgPath) {
        outline?.svgPath?.takeIf { it.isNotEmpty() && it.length <= 65_536 }?.let {
            runCatching { PathParser().parsePathString(it).toPath() }.getOrNull()
        }
    }
    if (path == null || outline == null || outline.width <= 0 || outline.height <= 0) return
    var period by remember(path) { mutableLongStateOf(1_000L) }
    LaunchedEffect(path, animate) {
        if (!animate) {
            period = 1_000L
            return@LaunchedEffect
        }
        while (true) {
            val current = withFrameNanos { (it / 1_000_000L) % 2_000L }
            period = current
            // The official waiting half has no per-frame redraws.
            if (current >= 1_000L) delay(2_000L - current)
        }
    }
    val background = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.14f)
    val highlight = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.25f)
    Canvas(modifier.fillMaxSize().semantics { contentDescription = "贴纸加载轮廓" }) {
        withTransform({
            scale(size.width / outline.width, size.height / outline.height, pivot = Offset.Zero)
        }) {
            if (period < 1_000L) {
                val width = outline.width.toFloat()
                val gradientWidth = width * 0.6f
                val left = -gradientWidth + (width + gradientWidth) * (period / 1_000f)
                drawPath(path, Brush.horizontalGradient(listOf(background, highlight, background),
                    startX = left, endX = left + gradientWidth))
            } else drawPath(path, background)
        }
    }
}
