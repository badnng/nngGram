package com.badnng.moe.media

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.badnng.moe.ChatButton
import com.badnng.moe.RichMessageBody
import com.badnng.moe.MediaLoadingCover
import com.badnng.moe.MediaLoadingIndicator
import com.badnng.moe.chatSquircleBackground
import com.badnng.moe.chatSquircleClip
import com.badnng.moe.chat.ChatResult
import com.badnng.moe.chat.RichMessageText
import com.badnng.moe.chat.MediaMiniThumbnail
import kotlinx.coroutines.isActive
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Pause
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.icon.extended.VolumeOff
import top.yukonga.miuix.kmp.icon.extended.VolumeUp
import top.yukonga.miuix.kmp.theme.MiuixTheme

private class DesktopVideoSession(
    private val path: String,
    private val size: Long,
    private val readPart: ((Long, Int, (ChatResult<ByteArray>) -> Unit) -> Unit)?,
    active: Boolean,
    private val initiallyMuted: Boolean,
    private val windowIsActive: () -> Boolean,
    private val readerFactory: (() -> MediaRangeReader)?,
    private val currentSize: () -> Long,
) : VideoPlaybackSession, AutoCloseable {
    var player by mutableStateOf<InlineVideoPlayer?>(null)
        private set
    private var source: ProgressiveVideoSource? = null
    private val presentation = VideoPresentationState()
    @Volatile private var contentActive = active
    private var fallbackPreferences = VideoPlaybackPreferences(if (initiallyMuted) 0f else 1f, active)
    init { retry(active) }
    fun retry(active: Boolean) {
        val volume = currentPreferences().volume
        close()
        val play = active && contentActive && windowIsActive()
        fallbackPreferences = VideoPlaybackPreferences(volume, play)
        if (!play) return
        source = if (path.isEmpty() && (readPart != null || readerFactory != null)) runCatching {
            ProgressiveVideoSource(size, readerFactory?.invoke() ?: MediaRangeReader(checkNotNull(readPart)), currentSize)
        }.getOrNull() else null
        player = runCatching { InlineVideoPlayer(path, autoPlay = play, progressiveSource = source, initialVolume = volume) }.getOrNull()
    }
    fun setActive(active: Boolean) {
        contentActive = active
        if (!active || !windowIsActive()) suspendPlayback()
        else if (player == null) retry(true)
    }
    fun suspendPlayback() {
        if (player?.state?.value?.ready == false) close() else player?.pause()
    }
    override fun enterViewer() {
        if (!presentation.inViewer) applyPreferences(presentation.enterViewer(currentPreferences(), contentActive && windowIsActive()))
    }
    override fun leaveViewer() {
        if (presentation.inViewer) applyPreferences(presentation.leaveViewer(currentPreferences(), contentActive && windowIsActive()))
    }
    private fun currentPreferences() = player?.playbackPreferences() ?: fallbackPreferences
    private fun applyPreferences(preferences: VideoPlaybackPreferences) {
        fallbackPreferences = preferences
        player?.applyPlaybackPreferences(preferences) { contentActive && windowIsActive() }
    }
    override fun close() { player?.close(); source?.close(); player = null; source = null }
}

@Composable
actual fun rememberVideoPlaybackSession(path: String, active: Boolean, streamSize: Long,
    readPart: ((Long, Int, (ChatResult<ByteArray>) -> Unit) -> Unit)?, initiallyMuted: Boolean,
    readerFactory: (() -> MediaRangeReader)?): VideoPlaybackSession {
    val currentReadPart by rememberUpdatedState(readPart)
    val currentReaderFactory by rememberUpdatedState(readerFactory)
    val currentStreamSize by rememberUpdatedState(streamSize)
    val activity = LocalWindowActivity.current
    val currentActivity by rememberUpdatedState(activity)
    val focused = activity?.collectAsStateWithLifecycle()?.value != false
    // Ownership is keyed by the caller's message/file identity, not download progress or layout.
    val session = remember {
        DesktopVideoSession(path, streamSize, if (readPart == null) null else { offset, count, result ->
            currentReadPart?.invoke(offset, count, result)
        }, active, initiallyMuted, { currentActivity?.value != false },
            if (readerFactory == null) null else { { checkNotNull(currentReaderFactory).invoke() } }, { currentStreamSize })
    }
    DisposableEffect(session) { onDispose { session.close() } }
    DisposableEffect(session, activity) {
        val observer = activity?.let { observeWindowInactivity(it, { session.suspendPlayback() }) }
        onDispose { observer?.close() }
    }
    SideEffect { session.setActive(active && focused) }
    return session
}

/** Both presentations share one session. Closing the enlarged view keeps playback in the bubble. */
@Composable
actual fun VideoPlayback(show: Boolean, path: String, onDismiss: () -> Unit, modifier: Modifier,
    active: Boolean, streamSize: Long, readPart: ((Long, Int, (ChatResult<ByteArray>) -> Unit) -> Unit)?,
    aspectRatio: Float, onSave: (() -> Unit)?, session: VideoPlaybackSession?, expanded: Boolean,
    onExpand: (() -> Unit)?, caption: RichMessageText, title: String, downloadedFraction: Float,
    allowCaptionCopy: Boolean, canCopyCaptionNow: () -> Boolean, loadingThumbnailPath: String?,
    loadingMinithumbnail: MediaMiniThumbnail?, readerFactory: (() -> MediaRangeReader)?) {
    if (!show) return
    val owner = (session ?: rememberVideoPlaybackSession(path, active, streamSize, readPart, readerFactory = readerFactory)) as DesktopVideoSession
    DisposableEffect(owner, expanded) {
        if (expanded) owner.enterViewer()
        onDispose { if (expanded) owner.leaveViewer() }
    }
    val player = owner.player
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(expanded) { if (expanded) focusRequester.requestFocus() }
    val root = modifier.focusRequester(focusRequester).onPreviewKeyEvent { event ->
        val shortcut = videoViewerShortcut(event.key, event.type, expanded,
            event.isShiftPressed, event.isCtrlPressed, event.isAltPressed, event.isMetaPressed)
        if (expanded) when (shortcut) {
            VideoViewerShortcut.CONSUME -> true
            VideoViewerShortcut.CLOSE -> { onDismiss(); true }
            VideoViewerShortcut.TOGGLE_PLAY -> {
                if (active && player != null) {
                    if (player.playbackPreferences().playing) player.pause() else player.play()
                }
                true
            }
            VideoViewerShortcut.SEEK_BACK, VideoViewerShortcut.SEEK_FORWARD -> {
                if (active && player?.state?.value?.ready == true) {
                    player.seek(player.currentPosition() + shortcut.seekDeltaSeconds)
                }
                true
            }
            null -> false
        } else if (event.type != KeyEventType.KeyUp || event.isShiftPressed || event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) false else when {
            event.key == Key.Escape -> { onDismiss(); true }
            event.key == Key.Spacebar && active && player != null -> {
                if (player.state.value.playing) player.pause() else player.play()
                true
            }
            else -> false
        }
    }.focusable()
    BoxWithConstraints(root) {
        val ratio = aspectRatio.takeIf { it.isFinite() && it > 0 } ?: (16f / 9f)
        val frameModifier = if (expanded) Modifier.fillMaxSize() else Modifier.fillMaxWidth().height((maxWidth / ratio).coerceIn(120.dp, 320.dp))
        if (player == null) Box(frameModifier.background(Color.Black), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("视频未能加载，请重试。", color = Color.White, fontSize = 13.sp)
                ChatButton("重试播放", onClick = { owner.retry(active) })
                ChatButton("关闭", onClick = onDismiss)
            }
        } else PlayerBody(player, active, expanded, frameModifier,
            onRetry = { owner.retry(active) }, onDismiss = onDismiss, onSave = onSave, onExpand = onExpand,
            caption = caption, title = title, downloadedFraction = if (path.isNotEmpty()) 1f else downloadedFraction,
            allowCaptionCopy = allowCaptionCopy, canCopyCaptionNow = canCopyCaptionNow,
            loadingThumbnailPath = loadingThumbnailPath, loadingMinithumbnail = loadingMinithumbnail)
    }
}

@Composable
internal fun PlayerBody(player: InlineVideoPlayer, active: Boolean, expanded: Boolean, modifier: Modifier,
    onRetry: () -> Unit, onDismiss: () -> Unit, onSave: (() -> Unit)?, onExpand: (() -> Unit)?,
    caption: RichMessageText, title: String, downloadedFraction: Float,
    allowCaptionCopy: Boolean, canCopyCaptionNow: () -> Boolean, loadingThumbnailPath: String?,
    loadingMinithumbnail: MediaMiniThumbnail?) {
    val state by player.state.collectAsStateWithLifecycle()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var lastAudibleVolume by remember(player) { mutableFloatStateOf(1f) }
    val position = remember(player) { mutableDoubleStateOf(0.0) }
    val drag = remember(player) { mutableStateOf<Float?>(null) }
    val duration = state.duration.takeIf { it.isFinite() && it > 0 } ?: 0.0
    val currentDuration by rememberUpdatedState(duration)
    val canSeek = active && state.ready && !state.failed && duration > 0
    val canPlay = active && state.ready && !state.failed && (!state.loading || state.playing)
    val togglePlay = { if (state.playing) player.pause() else player.play() }
    val toggleMute = {
        if (state.volume > 0f) { lastAudibleVolume = state.volume; player.setVolume(0f) }
        else player.setVolume(lastAudibleVolume)
    }
    val sampled = active && state.playing && !state.loading && !state.buffering && !state.failed
    LaunchedEffect(player, sampled) {
        if (sampled) while (isActive) withFrameNanos { position.doubleValue = player.currentPosition() }
    }
    LaunchedEffect(state.position, sampled) { if (!sampled) position.doubleValue = state.position }
    LaunchedEffect(active) { if (!active) drag.value = null }
    val fraction = remember(player) { { drag.value ?: if (currentDuration > 0) (position.doubleValue / currentDuration).toFloat().coerceIn(0f, 1f) else 0f } }
    val seek: (Float) -> Unit = { value -> if (canSeek) player.seek(value.coerceIn(0f, 1f) * duration) }
    val overlay = Color.Black.copy(alpha = 0.68f)
    val shape = if (expanded) Modifier else Modifier.chatSquircleClip(12.dp)
    val expandOnClick = !expanded && onExpand != null
    Box(modifier.then(shape).background(Color.Black.copy(alpha = if (expanded) 0.94f else 1f)).hoverable(interaction)
        .pointerInput(expanded) {
            // Participate in hit testing so the covered conversation cannot receive clicks.
            awaitPointerEventScope { while (true) awaitPointerEvent() }
        }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().then(if (expanded) Modifier.padding(top = 42.dp, bottom = 90.dp, start = 12.dp, end = 12.dp) else Modifier)
            .clickable(enabled = if (expandOnClick) active else canPlay,
                onClickLabel = if (expandOnClick) "展开视频" else if (state.playing) "暂停视频" else "播放视频",
                onClick = if (expandOnClick) checkNotNull(onExpand) else togglePlay)
            .semantics { contentDescription = if (expanded) "聊天区域内的视频大图" else "聊天气泡内视频" }) {
            drawIntoCanvas { canvas -> player.draw(canvas.skiaCanvas, size.width, size.height) }
        }
        if (!expanded) InlineVideoLoadingBackdrop(state, active, loadingThumbnailPath, Modifier.matchParentSize(), loadingMinithumbnail)
        if (expanded) {
            Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                PlayerTextButton("返回聊天", onDismiss)
            }
        } else {
            Row(Modifier.align(Alignment.TopStart).padding(6.dp).chatSquircleBackground(overlay, 12.dp).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(playbackTime((duration - state.position).coerceAtLeast(0.0)), color = Color.White, fontSize = 11.sp)
                VolumeButton(state, toggleMute)
            }
        }
        when {
            state.failed -> Column(Modifier.chatSquircleBackground(overlay, 12.dp).padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("视频暂时无法播放", fontSize = 12.sp, color = Color.White)
                ChatButton("重试", onClick = onRetry)
            }
            state.buffering || state.loading || !state.ready -> {
                if (expanded) VideoLoadingIndicator(state, active)
            }
            !expanded && (hovered || !state.playing) -> PlayButton(state, canPlay, togglePlay, Modifier.background(overlay, CircleShape))
        }
        if (expanded) {
            Column(Modifier.align(Alignment.BottomCenter).widthIn(max = 680.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (caption.text.isNotEmpty()) RichMessageBody(caption,
                    modifier = Modifier.widthIn(max = 500.dp).chatSquircleBackground(overlay, 10.dp).padding(10.dp),
                    maxLines = 4, foreground = Color.White, accent = Color(0xFF7AC5FF),
                    allowCopy = allowCaptionCopy, canCopyNow = canCopyCaptionNow)
                Column(Modifier.fillMaxWidth().chatSquircleBackground(Color.Black.copy(alpha = 0.9f), 12.dp).padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            VolumeButton(state, toggleMute)
                            Slider(value = state.volume, onValueChange = { if (it > 0) lastAudibleVolume = it; player.setVolume(it) },
                                height = 4.dp, enabled = state.ready && !state.audioUnavailable, modifier = Modifier.width(72.dp).semantics { contentDescription = "视频音量" })
                        }
                        PlayButton(state, canPlay, togglePlay)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            onSave?.let { PlayerTextButton("保存", it) }
                            PlayerTextButton("收起", onDismiss)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(playbackTime(state.position), color = Color.White, fontSize = 11.sp)
                        PlaybackProgressBar(fraction, downloadedFraction, drag, canSeek, seek, Modifier.weight(1f))
                        Text("−${playbackTime((duration - state.position).coerceAtLeast(0.0))}", color = Color.White, fontSize = 11.sp)
                    }
                }
            }
        } else {
            if (hovered) Row(Modifier.align(Alignment.TopEnd).padding(6.dp).chatSquircleBackground(overlay, 12.dp), verticalAlignment = Alignment.CenterVertically) {
                onExpand?.let { PlayerTextButton("展开", it) }
                onSave?.let { PlayerTextButton("保存", it) }
                PlayerTextButton("停止", onDismiss)
            }
            InlineVideoProgressOverlay(state, active, fraction, Modifier.matchParentSize())
        }
    }
}

/**
 * Ordinary Telegram videos paint their timestamp mark against the media's bottom edge and
 * open the viewer on click; only the viewer (and round-video controls) handle seeking.
 * See tdesktop a7f4536: PaintVideoTimestampMark and Gif::textState.
 */
@Composable
internal fun InlineVideoProgressOverlay(
    state: VideoPlayerState,
    active: Boolean,
    progress: () -> Float,
    modifier: Modifier = Modifier,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        VideoLoadingIndicator(state, active)
        InlineVideoProgressBar(progress, Modifier.align(Alignment.BottomCenter).fillMaxWidth())
    }
}

/** A native thumbnail covers only the wait for the first raster; later stalls retain that raster. */
@Composable
internal fun InlineVideoLoadingBackdrop(
    state: VideoPlayerState,
    active: Boolean,
    thumbnailPath: String?,
    modifier: Modifier = Modifier,
    minithumbnail: MediaMiniThumbnail? = null,
) {
    if (state.failed || state.hasFrame) return
    // Without a real thumbnail keep the existing video/neutral fallback, never invent an image.
    if (thumbnailPath != null || minithumbnail != null) MediaLoadingCover(thumbnailPath, active, active,
        if (state.buffering) "视频缓冲中" else "视频加载中", modifier, showIndicator = false,
        minithumbnail = minithumbnail)
}

/** Mount the stock Miuix arc only while this visible player is waiting. */
@Composable
private fun VideoLoadingIndicator(state: VideoPlayerState, active: Boolean) {
    if (!active || state.failed || (state.ready && !state.loading && !state.buffering)) return
    MediaLoadingIndicator(active, if (state.buffering) "视频缓冲中" else "视频加载中")
}

/** A read-only three-dp media overlay: no seek semantics, focus target or pointer handlers. */
@Composable
private fun InlineVideoProgressBar(progress: () -> Float, modifier: Modifier = Modifier) {
    val fraction = { progress().takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f }
    Box(modifier.height(3.dp).semantics {
        contentDescription = "视频播放进度"
        progressBarRangeInfo = ProgressBarRangeInfo(fraction(), 0f..1f)
    }) {
        val track = ProgressIndicatorDefaults.progressIndicatorColors(
            foregroundColor = Color.White.copy(alpha = 0.48f), backgroundColor = Color.Transparent)
        val played = ProgressIndicatorDefaults.progressIndicatorColors(
            foregroundColor = MiuixTheme.colorScheme.primary, backgroundColor = Color.Transparent)
        LinearProgressIndicator(progress = 1f, colors = track, height = 3.dp,
            modifier = Modifier.clearAndSetSemantics {})
        LinearProgressIndicator(progress = 1f, colors = played, height = 3.dp,
            modifier = Modifier.clearAndSetSemantics {}.drawWithContent {
                clipRect(right = size.width * fraction()) { this@drawWithContent.drawContent() }
            })
    }
}

/** Miuix progress bars with draw-phase clipping: smooth progress never recomposes the chat. */
@Composable
private fun PlaybackProgressBar(progress: () -> Float, downloaded: Float, drag: MutableState<Float?>,
    enabled: Boolean, onSeek: (Float) -> Unit, modifier: Modifier = Modifier) {
    val currentSeek by rememberUpdatedState(onSeek)
    val buffered = animateFloatAsState(downloaded.coerceIn(0f, 1f), tween(280))
    Box(modifier.height(22.dp).semantics {
        contentDescription = "视频播放进度；浅色为已下载比例"
        progressBarRangeInfo = ProgressBarRangeInfo(progress(), 0f..1f)
        if (enabled) setProgress { currentSeek(it); true }
    }.onPreviewKeyEvent { event ->
        if (!enabled || event.type != KeyEventType.KeyDown) false else when (event.key) {
            Key.DirectionLeft -> { currentSeek((progress() - 0.02f).coerceAtLeast(0f)); true }
            Key.DirectionRight -> { currentSeek((progress() + 0.02f).coerceAtMost(1f)); true }
            else -> false
        }
    }.focusable(enabled).pointerInput(enabled) {
        if (enabled) awaitEachGesture {
            val down = awaitFirstDown()
            down.consume()
            drag.value = (down.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
            try {
                var pressed = true
                while (pressed) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    drag.value = (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    change.consume()
                    pressed = change.pressed
                }
                drag.value?.let { currentSeek(it) }
            } finally { drag.value = null }
        }
    }, contentAlignment = Alignment.Center) {
        val track = ProgressIndicatorDefaults.progressIndicatorColors(foregroundColor = Color.White.copy(alpha = 0.23f), backgroundColor = Color.Transparent)
        val cache = ProgressIndicatorDefaults.progressIndicatorColors(foregroundColor = Color.White.copy(alpha = 0.48f), backgroundColor = Color.Transparent)
        val played = ProgressIndicatorDefaults.progressIndicatorColors(foregroundColor = MiuixTheme.colorScheme.primary, backgroundColor = Color.Transparent)
        LinearProgressIndicator(progress = 1f, colors = track, height = 3.dp, modifier = Modifier.clearAndSetSemantics {})
        LinearProgressIndicator(progress = 1f, colors = cache, height = 3.dp, modifier = Modifier.clearAndSetSemantics {}.drawWithContent {
            clipRect(right = size.width * buffered.value) { this@drawWithContent.drawContent() }
        })
        LinearProgressIndicator(progress = 1f, colors = played, height = 3.dp, modifier = Modifier.clearAndSetSemantics {}.drawWithContent {
            clipRect(right = size.width * progress()) { this@drawWithContent.drawContent() }
        })
    }
}

@Composable
private fun PlayButton(state: VideoPlayerState, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier.size(46.dp).clip(CircleShape)) {
        Icon(if (state.playing) MiuixIcons.Pause else MiuixIcons.Play, tint = Color.White,
            contentDescription = if (state.ended) "重播" else if (state.playing) "暂停" else "播放", modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun VolumeButton(state: VideoPlayerState, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = state.ready && !state.audioUnavailable, modifier = Modifier.size(35.dp)) {
        Icon(if (state.volume > 0f && !state.audioUnavailable) MiuixIcons.VolumeUp else MiuixIcons.VolumeOff,
            tint = Color.White, contentDescription = if (state.volume > 0f) "静音" else "恢复声音", modifier = Modifier.size(17.dp))
    }
}

@Composable
private fun PlayerTextButton(label: String, onClick: () -> Unit) {
    Text(label, color = Color.White, fontSize = 12.sp,
        modifier = Modifier.clickable(onClick = onClick).heightIn(min = 35.dp).padding(horizontal = 10.dp, vertical = 9.dp))
}

internal fun playbackTime(seconds: Double): String {
    val total = seconds.takeIf { it.isFinite() }?.toLong()?.coerceAtLeast(0L) ?: 0L
    val hours = total / 3600
    val minutes = (total / 60) % 60
    val remaining = total % 60
    return if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}:${remaining.toString().padStart(2, '0')}"
        else "$minutes:${remaining.toString().padStart(2, '0')}"
}
