package com.badnng.moe

import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.File
import top.yukonga.miuix.kmp.icon.extended.FileDownloads
import top.yukonga.miuix.kmp.icon.extended.Play

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.font.FontWeight
import com.badnng.moe.chat.*
import com.badnng.moe.media.MediaPreview
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.pow
import kotlin.math.roundToInt

/** Downloads are requested only for a visible item, and failures wait for an explicit retry. */
@Composable
internal fun RemoteMedia(
    media: MessageMedia,
    snapshot: ChatSnapshot,
    features: ChatFeatureActions,
    visible: Boolean,
    animate: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onClick: (() -> Unit)? = null,
    cornerRadius: Dp = 12.dp,
    placeholder: (@Composable () -> Unit)? = null,
    retainWhenInactive: Boolean = false,
    contentScale: ContentScale = ContentScale.Fit,
    highQuality: Boolean = false,
    photoBackground: Boolean = false,
    messageId: Long? = null,
) {
    val file = snapshot.mediaFiles[media.file.id] ?: media.file
    val thumbnail = media.thumbnail?.let { snapshot.mediaFiles[it.id] ?: it }
    val error = snapshot.fileErrors[file.id]
    val busy = file.downloading || file.id in snapshot.downloadingFiles
    val path = file.localPath?.takeIf { file.downloaded }
    val inlinePhoto = photoBackground && media.format == MediaFormat.PHOTO
    val source = snapshot.selected?.autoMediaSource()
    val automaticPhoto = messageId != null && snapshot.autoMediaSettings.loaded && source != null &&
        snapshot.autoMediaSettings.policy.allows(source, snapshot.selected?.id ?: 0, AutoMediaType.PHOTO, file.size)
    val awaitingPhotoBytes = busy || file.uploading || file.canDownload && !file.downloaded && automaticPhoto
    val spoiler = rememberMediaSpoilerState(snapshot.token to media.file.id, media.hasSpoiler, visible && animate)
    val revealed = spoiler.revealed
    if (inlinePhoto && messageId != null) AutomaticMediaVisibility(features, snapshot.token, messageId, visible && animate && revealed)
    val loadingThumbnail = rememberLoadingThumbnailPath(media, snapshot, features,
        visible && revealed && inlinePhoto && path == null && error == null)
    var decodeFailed by remember(file.id, path) { mutableStateOf(false) }
    var photoPrepared by remember(file.id, path) { mutableStateOf(false) }
    MediaArtworkVisibility(snapshot, features, file, visible && revealed && media.format != MediaFormat.VIDEO &&
        media.format != MediaFormat.FILE && (media.format != MediaFormat.PHOTO || messageId == null))
    val click = when {
        !revealed -> ({ if (visible) spoiler.reveal() })
        onClick != null -> onClick
        compact && error != null && file.canDownload && !busy -> ({ features.downloadFile(snapshot.token, file.id) })
        else -> null
    }
    val decorated = if (click != null) modifier.chatSquircleSurface(MiuixTheme.colorScheme.surfaceContainer, cornerRadius).clickable(onClick = click)
        else modifier.chatSquircleBackground(MiuixTheme.colorScheme.surfaceContainer, cornerRadius)
    val previewModifier = Modifier.fillMaxSize().then(if (click == null) Modifier.chatSquircleClip(cornerRadius) else Modifier)
    Box(decorated, contentAlignment = Alignment.Center) {
        if (revealed) {
            val previewPath = thumbnail?.localPath?.takeIf { thumbnail.downloaded }
            val canDraw = visible || retainWhenInactive
            val backgroundPath = loadingThumbnail ?: previewPath ?: path
            val hasPhotoSource = backgroundPath != null || media.minithumbnail != null
            // Photo::prepareImageCacheWithLarge: a missing full image is blur-only. Once
            // ready, the expanding branch crops directly; the contained branch keeps Fit
            // over a separately cropped/blurred native thumbnail (or ready full fallback).
            val blurredPhoto = inlinePhoto && canDraw && (path == null || !photoPrepared || contentScale != ContentScale.Crop)
            if (blurredPhoto && hasPhotoSource) key(snapshot.token, media.file.id) {
                MediaPhotoBackground(backgroundPath, visible, previewModifier, media.minithumbnail)
            }
            when {
                path != null && (visible || retainWhenInactive) && media.format != MediaFormat.VIDEO && media.format != MediaFormat.FILE -> MediaPreview(path, media.format, previewModifier,
                    contentDescription = if (media.format == MediaFormat.PHOTO) "聊天图片" else "贴纸 ${media.emoji}",
                    animate = animate && visible,
                    placeholder = if (inlinePhoto) ({
                        // A native completed download still needs worker decode/preparation.
                        // Keep the real blurred source until MediaPreview actually draws its raster.
                        MediaLoadingIndicator(visible && animate && !decodeFailed && error == null, "图片加载中")
                        if (decodeFailed) Text("媒体暂时无法显示", fontSize = 12.sp, color = MiuixTheme.colorScheme.error)
                    }) else placeholder,
                    contentScale = contentScale, highQuality = highQuality,
                    onLoadResult = if (inlinePhoto) ({ decodeFailed = !it }) else null,
                    onPrepared = if (inlinePhoto) ({ photoPrepared = it }) else null)
                inlinePhoto -> {
                    // An absent local thumbnail is still a photo loading state. The generic
                    // media label would overlap its centered ring while native bytes arrive.
                    if (canDraw && placeholder != null) placeholder()
                    else if (canDraw && path == null && file.canDownload && !awaitingPhotoBytes && error == null)
                        ChatButton("下载图片", onClick = { features.downloadFile(snapshot.token, file.id) })
                    else if (canDraw && path == null && !hasPhotoSource && !awaitingPhotoBytes && error == null) Text("文件暂时不可用", fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
                previewPath != null && (visible || retainWhenInactive) -> MediaPreview(previewPath, media.thumbnailFormat ?: MediaFormat.PHOTO,
                    previewModifier, contentDescription = "缩略图", animate = false,
                    placeholder = placeholder, contentScale = contentScale, highQuality = highQuality)
                placeholder != null -> placeholder()
                compact -> Text(if (error != null) "!" else if (busy) "…" else media.emoji.ifEmpty { "图" },
                    fontSize = 16.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                else -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (media.emoji.isNotEmpty()) media.emoji else if (media.format == MediaFormat.PHOTO) "图片" else "贴纸", fontSize = 20.sp)
                    if (error != null) Text("媒体加载失败", fontSize = 12.sp, color = MiuixTheme.colorScheme.error)
                    if (!busy && file.canDownload) ChatButton(if (error != null) "重试下载" else "加载",
                        onClick = { features.downloadFile(snapshot.token, file.id) })
                    else if (!busy) Text("文件暂时不可用", fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
            if (inlinePhoto && path == null && !compact && error == null) {
                MediaLoadingIndicator(visible && animate && awaitingPhotoBytes, "图片加载中")
            } else if (inlinePhoto && file.uploading && !compact && error == null) {
                MediaLoadingIndicator(visible && animate && photoPrepared, "图片上传中")
            } else if (!inlinePhoto && !compact && (busy || file.uploading)) {
                val total = maxOf(file.size, file.expectedSize)
                val completed = if (file.uploading) file.uploadedSize else file.downloadedSize
                val percent = if (total > 0) " ${(completed.toDouble() / total * 100).toInt().coerceIn(0, 100)}%" else ""
                Text((if (file.uploading) "上传中" else "加载中") + percent, fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).chatSquircleBackground(MiuixTheme.colorScheme.surface, 6.dp).padding(5.dp))
            }
            if (inlinePhoto && path == null && error != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("媒体加载失败", fontSize = 12.sp, color = MiuixTheme.colorScheme.error)
                    if (file.canDownload && !busy) ChatButton("重试下载", onClick = { features.downloadFile(snapshot.token, file.id) })
                }
            }
        }
        if (media.hasSpoiler) MediaSpoilerOverlay(media, snapshot, features, spoiler, visible, animate,
            cornerRadius, Modifier.matchParentSize(), ContentScale.Crop)
    }
}

/** A conversation-sized viewer: it never creates another window or changes the message scroll position. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun MediaViewer(
    media: MessageMedia,
    snapshot: ChatSnapshot,
    features: ChatFeatureActions,
    canSave: Boolean,
    onSave: (String, String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val zoom = remember(media.file.id) { mutableFloatStateOf(1f) }
    val translation = remember(media.file.id) { mutableStateOf(Offset.Zero) }
    val viewport = remember(media.file.id) { mutableStateOf(IntSize.Zero) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(media.file.id) { focusRequester.requestFocus() }
    val zoomLabel by remember { derivedStateOf { "${(zoom.floatValue * 100).roundToInt()}%" } }
    val currentMedia by rememberUpdatedState(media)
    fun bounded(value: Offset, scale: Float): Offset {
        val size = viewport.value
        if (size.width == 0 || size.height == 0) return Offset.Zero
        val source = currentMedia
        val fit = if (source.width > 0 && source.height > 0)
            minOf(size.width.toFloat() / source.width, size.height.toFloat() / source.height) else 1f
        val fitWidth = if (source.width > 0) source.width * fit else size.width.toFloat()
        val fitHeight = if (source.height > 0) source.height * fit else size.height.toFloat()
        val maxX = ((fitWidth * scale - size.width) / 2f).coerceAtLeast(0f)
        val maxY = ((fitHeight * scale - size.height) / 2f).coerceAtLeast(0f)
        return Offset(value.x.coerceIn(-maxX, maxX), value.y.coerceIn(-maxY, maxY))
    }
    fun transform(factor: Float, pan: Offset = Offset.Zero, centroid: Offset? = null) {
        val before = zoom.floatValue
        val after = (before * factor).coerceIn(0.5f, 8f)
        val center = Offset(viewport.value.width / 2f, viewport.value.height / 2f)
        val pivot = centroid?.minus(center) ?: Offset.Zero
        val translated = (translation.value - pivot) * (after / before) + pivot + pan
        zoom.floatValue = after
        translation.value = bounded(translated, after)
    }
    val file = snapshot.mediaFiles[media.file.id] ?: media.file
    Column(modifier.background(MiuixTheme.colorScheme.surfaceContainer)
        .focusRequester(focusRequester).onPreviewKeyEvent {
            if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) { onDismiss(); true } else false
        }.focusable().pointerInput(Unit) {
            awaitPointerEventScope {
                // Participate in hit testing so the covered conversation cannot receive clicks.
                // Children still own their button/zoom gestures; consuming here would cancel them.
                while (true) awaitPointerEvent()
            }
        }.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ChatButton("返回聊天", onClick = onDismiss)
            ChatButton("−", enabled = zoom.floatValue > 0.5f, onClick = { transform(1f / 1.25f) })
            ChatButton(zoomLabel, onClick = { zoom.floatValue = 1f; translation.value = Offset.Zero })
            ChatButton("＋", enabled = zoom.floatValue < 8f, onClick = { transform(1.25f) })
            ChatButton("适合窗口", onClick = { zoom.floatValue = 1f; translation.value = Offset.Zero })
            if (file.downloaded && file.localPath != null) ChatButton(if (canSave) "保存图片" else "不可保存", enabled = canSave, onClick = {
                onSave(file.localPath, media.fileName.ifBlank { "Telegram-image.jpg" })
            })
        }
        Box(Modifier.fillMaxWidth().weight(1f).clipToBounds().onSizeChanged {
            viewport.value = it
            translation.value = bounded(translation.value, zoom.floatValue)
        }.onPointerEvent(PointerEventType.Scroll) { event ->
            val change = event.changes.firstOrNull()
            if (change != null) {
                transform(1.15f.pow(-change.scrollDelta.y.coerceIn(-8f, 8f)), centroid = change.position)
                event.changes.forEach { it.consume() }
            }
        }.pointerInput(media.file.id) {
            detectTransformGestures { centroid, pan, factor, _ -> transform(factor, pan, centroid) }
        }, contentAlignment = Alignment.Center) {
            RemoteMedia(media.copy(hasSpoiler = false), snapshot, features, visible = true, animate = false,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = zoom.floatValue
                    scaleY = zoom.floatValue
                    translationX = translation.value.x
                    translationY = translation.value.y
                })
        }
        Text("滚轮缩放 · 拖动平移 · 点击比例还原", fontSize = 11.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

/** Visible videos may stream automatically; documents still require an explicit download. */
@Composable
internal fun AttachmentMedia(
    media: MessageMedia,
    snapshot: ChatSnapshot,
    features: ChatFeatureActions,
    visible: Boolean,
    canSave: Boolean,
    onSave: (String, String) -> Unit,
    onPlay: () -> Unit,
    messageId: Long,
    playing: Boolean,
    active: Boolean,
    onStopPlaying: () -> Unit,
    playbackSession: com.badnng.moe.media.VideoPlaybackSession?,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val file = snapshot.mediaFiles[media.file.id] ?: media.file
    val busy = file.downloading || file.id in snapshot.downloadingFiles
    val error = snapshot.fileErrors[file.id]
    val video = media.format == MediaFormat.VIDEO
    val name = media.fileName.ifBlank { if (video) "Telegram-video.mp4" else "Telegram-file" }
    val path = file.localPath?.takeIf { file.downloaded }
    val size = file.size
    val playable = video && (path != null || file.canDownload || busy)
    val ratio = if (media.width > 0 && media.height > 0) media.width.toFloat() / media.height else 16f / 9f
    if (video) {
        val spoiler = rememberMediaSpoilerState(Triple(snapshot.token, messageId, media.file.id), media.hasSpoiler, visible && active)
        val revealed = spoiler.revealed
        AutomaticMediaVisibility(features, snapshot.token, messageId, visible && active && revealed)
        val thumbnail = media.thumbnail?.let { snapshot.mediaFiles[it.id] ?: it }
        val thumbnailPath = rememberLoadingThumbnailPath(media, snapshot, features, visible && active && revealed)
        val waitingForThumbnail = thumbnail != null && thumbnailPath == null && !thumbnail.downloaded && thumbnail.canDownload &&
            snapshot.fileErrors[thumbnail.id] == null
        val waitingForVideo = error == null && (busy || file.uploading || waitingForThumbnail)
        var thumbnailPrepared by remember(snapshot.token, file.id, thumbnailPath, playing, revealed) { mutableStateOf(false) }
        var thumbnailFailed by remember(snapshot.token, file.id, thumbnailPath, playing, revealed) { mutableStateOf(false) }
        val waitingForPoster = waitingForVideo || error == null && thumbnailPath != null && !thumbnailPrepared && !thumbnailFailed
        Column(modifier.fillMaxWidth()) {
            val hover = remember { MutableInteractionSource() }
            val hovered by hover.collectIsHoveredAsState()
            val save: (() -> Unit)? = if (path != null && canSave) ({ onSave(path, name) }) else null
            if (playing && revealed) key(file.id) {
                com.badnng.moe.media.VideoPlayback(show = true, path = path.orEmpty(), onDismiss = onStopPlaying,
                    modifier = Modifier.fillMaxWidth(), active = active, streamSize = size, aspectRatio = ratio, onSave = save,
                    session = playbackSession, onExpand = onExpand,
                    loadingThumbnailPath = thumbnailPath,
                    loadingMinithumbnail = media.minithumbnail,
                    downloadedFraction = if (file.downloaded) 1f else if (size > 0) (file.downloadedSize.toDouble() / size).toFloat() else 0f,
                    readerFactory = { com.badnng.moe.media.ownedMediaRangeReader(features, snapshot.token, messageId, file.id) })
            } else BoxWithConstraints(Modifier.fillMaxWidth().hoverable(hover)) {
                val available = constraints.maxDpSize(LocalDensity.current)
                val frameHeight = (available.width / ratio).coerceIn(120.dp, 320.dp)
                Box(Modifier.fillMaxWidth().height(frameHeight).chatSquircleBackground(MiuixTheme.colorScheme.surfaceContainer, 12.dp)
                    .clickable(enabled = playable && revealed, onClickLabel = "展开视频", onClick = onExpand),
                    contentAlignment = Alignment.Center) {
                    if (revealed) {
                        key(snapshot.token, file.id) {
                            if (thumbnailPath != null && (visible && active || thumbnailPrepared || thumbnailFailed)) {
                                MediaPreview(thumbnailPath, media.thumbnailFormat ?: MediaFormat.PHOTO,
                                    Modifier.matchParentSize().chatSquircleClip(12.dp).graphicsLayer { alpha = if (waitingForVideo) 0f else 1f },
                                    contentDescription = "视频缩略图", animate = false, placeholder = {},
                                    onPrepared = { thumbnailPrepared = it }, onLoadResult = { thumbnailFailed = !it })
                            }
                            // The same blur owner survives miniature -> local thumbnail and
                            // remains ready if downloading resumes after the poster has decoded.
                            MediaLoadingCover(thumbnailPath, visible && active, visible && active,
                                "视频加载中", Modifier.matchParentSize().chatSquircleClip(12.dp).graphicsLayer {
                                    alpha = if (waitingForPoster || !thumbnailPrepared) 1f else 0f
                                }, showIndicator = waitingForPoster, minithumbnail = media.minithumbnail)
                        }
                    }
                    Text("${media.durationSeconds / 60}:${(media.durationSeconds % 60).toString().padStart(2, '0')} · ${fileSizeLabel(size)}",
                        fontSize = 11.sp, modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                            .chatSquircleBackground(MiuixTheme.colorScheme.surface.copy(alpha = 0.88f), 10.dp).padding(horizontal = 8.dp, vertical = 4.dp))
                    if (revealed && !waitingForPoster) IconButton(onClick = onPlay, enabled = playable,
                        modifier = Modifier.size(46.dp).chatSquircleBackground(MiuixTheme.colorScheme.surface.copy(alpha = 0.88f), 23.dp)) {
                        Icon(MiuixIcons.Play, contentDescription = if (path == null) "边加载边播放" else "播放视频", modifier = Modifier.size(24.dp))
                    }
                    if (hovered && revealed) Row(Modifier.align(Alignment.TopEnd).padding(6.dp)) {
                        if (save != null) ChatButton("保存", onClick = save)
                        else if (file.canDownload && !busy) ChatButton("下载完整视频", onClick = { features.downloadFile(snapshot.token, file.id) })
                    }
                    if (busy) Text("已加载 ${fileSizeLabel(file.downloadedSize)}", fontSize = 11.sp,
                        modifier = Modifier.align(Alignment.BottomStart).padding(6.dp)
                            .chatSquircleBackground(MiuixTheme.colorScheme.surface.copy(alpha = 0.88f), 10.dp).padding(6.dp))
                    if (media.hasSpoiler) MediaSpoilerOverlay(media, snapshot, features, spoiler, visible, active,
                        12.dp, Modifier.matchParentSize())
                }
            }
            error?.let { ChatErrorText(it) }
        }
        return
    }
    val colors = MiuixTheme.colorScheme
    val downloadable = file.canDownload && file.id > 0
    val unavailable = path == null && !downloadable && !busy && !file.uploading
    val fileType = name.substringAfterLast('.', "").uppercase()
        .takeIf { it.length in 1..8 && it.all(Char::isLetterOrDigit) }
        ?.let { "$it 文档" } ?: "文件"
    val actionEnabled = !file.uploading && when {
        path != null -> canSave
        else -> downloadable && !busy
    }
    val actionLabel = when {
        file.uploading -> "文件上传中"
        path != null -> if (canSave) "保存文件" else "不可保存"
        busy -> "文件下载中"
        unavailable -> "文件暂时不可用"
        error != null -> "重新下载"
        else -> "下载文件"
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.width(44.dp).height(50.dp).chatSquircleBackground(colors.primary, 13.dp),
                contentAlignment = Alignment.Center) {
                Icon(MiuixIcons.Regular.File, contentDescription = "文件", tint = colors.onPrimary,
                    modifier = Modifier.size(25.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(name, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(buildString {
                    append(fileSizeLabel(size)).append(" · ").append(fileType)
                    if (unavailable) append(" · 暂不可用")
                    else if (path != null && !canSave) append(" · 不可保存")
                }, fontSize = 12.sp, color = colors.onSurfaceVariantSummary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = {
                if (path != null && canSave && !file.uploading) onSave(path, name)
                else if (path == null && downloadable && !busy && !file.uploading)
                    features.downloadFile(snapshot.token, file.id)
            }, enabled = actionEnabled, modifier = Modifier.size(40.dp), minWidth = 40.dp, minHeight = 40.dp,
                cornerRadius = 20.dp, backgroundColor = colors.primary.copy(alpha = 0.08f)) {
                Icon(MiuixIcons.Regular.FileDownloads, contentDescription = actionLabel,
                    tint = if (actionEnabled) colors.primary else colors.disabledOnSurface,
                    modifier = Modifier.size(24.dp))
            }
        }
        if (busy || file.uploading) {
            val completed = if (file.uploading) file.uploadedSize else file.downloadedSize
            val total = maxOf(file.size, file.expectedSize)
            val percent = if (total > 0) " · ${(completed.toDouble() / total * 100).toInt().coerceIn(0, 100)}%" else ""
            Text((if (file.uploading) "上传中" else "下载中") + " · " + fileSizeLabel(completed) + percent,
                fontSize = 12.sp, color = MiuixTheme.colorScheme.primary)
        }
        error?.let { ChatErrorText(it) }
    }
}

private fun fileSizeLabel(bytes: Long): String {
    if (bytes <= 0) return "大小未知"
    val (divisor, unit) = when {
        bytes >= 1_073_741_824L -> 1_073_741_824.0 to "GB"
        bytes >= 1_048_576 -> 1_048_576.0 to "MB"
        bytes >= 1024 -> 1024.0 to "KB"
        else -> return "$bytes B"
    }
    val tenths = (bytes / divisor * 10).roundToInt()
    return "${tenths / 10}.${tenths % 10} $unit"
}

