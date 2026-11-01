package com.badnng.moe

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.badnng.moe.chat.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal const val MEDIA_SPOILER_REVEAL_MILLIS = 200
internal const val MEDIA_SPOILER_THUMBNAIL_EDGE = 40

@Stable
internal class MediaSpoilerState(initiallyRevealed: Boolean) {
    var revealed by mutableStateOf(initiallyRevealed)
        private set
    internal val animation = Animatable(if (initiallyRevealed) 1f else 0f)
    val progress: Float get() = animation.value
    fun reveal() { revealed = true }
}

/** A paused/departing media finishes an accepted reveal without keeping a frame callback alive. */
@Composable
internal fun rememberMediaSpoilerState(identity: Any, hasSpoiler: Boolean, active: Boolean): MediaSpoilerState {
    val state = remember(identity, hasSpoiler) { MediaSpoilerState(!hasSpoiler) }
    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(state, state.revealed, active, focused) {
        val target = if (state.revealed) 1f else 0f
        if (active && focused && state.revealed) {
            state.animation.animateTo(target, tween(MEDIA_SPOILER_REVEAL_MILLIS, easing = LinearEasing))
        } else state.animation.snapTo(target)
    }
    return state
}

internal fun mediaSpoilerFramesActive(visible: Boolean, active: Boolean, focused: Boolean, progress: Float): Boolean =
    visible && active && focused && progress < 1f

/** Synthetic probes observe time/progress only, never native identities, paths or image content. */
internal val LocalMediaSpoilerFrameObserver = staticCompositionLocalOf<((Long, Float) -> Unit)?> { null }

@Composable
internal expect fun MediaSpoilerThumbnail(path: String, loadAllowed: Boolean, modifier: Modifier, contentScale: ContentScale)

@Composable
internal expect fun MediaSpoilerParticleMask(timeMillis: Long, progress: Float, loadAllowed: Boolean, modifier: Modifier): Boolean

/** Only a TDLib thumbnail may be requested for a concealed image; a ready full photo may be reused. */
@Composable
private fun spoilerThumbnailPath(media: MessageMedia, snapshot: ChatSnapshot, features: ChatFeatureActions, visible: Boolean): String? {
    val thumbnail = media.thumbnail?.let { snapshot.mediaFiles[it.id] ?: it }
    MediaArtworkVisibility(snapshot, features, thumbnail?.takeIf { it.id > 0 && it.id != media.file.id }, visible)
    val readyThumbnail = thumbnail?.localPath?.takeIf { thumbnail.downloaded }
    if (readyThumbnail != null) return readyThumbnail
    val file = snapshot.mediaFiles[media.file.id] ?: media.file
    return file.localPath?.takeIf { file.downloaded && media.format in setOf(MediaFormat.PHOTO, MediaFormat.WEBP) }
}

/** Photo/Gif::draw: draw the real content below, then fade the blurred thumbnail and particle layer. */
@Composable
internal fun MediaSpoilerOverlay(
    media: MessageMedia,
    snapshot: ChatSnapshot,
    features: ChatFeatureActions,
    state: MediaSpoilerState,
    visible: Boolean,
    active: Boolean,
    cornerRadius: Dp,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    if (state.progress >= 1f) return
    val thumbnail = spoilerThumbnailPath(media, snapshot, features, visible)
    val focused = LocalWindowInfo.current.isWindowFocused
    val running = mediaSpoilerFramesActive(visible, active, focused, state.progress)
    var timeMillis by remember(state) { mutableLongStateOf(0L) }
    val background = MiuixTheme.colorScheme.surfaceContainer
    Box(modifier.graphicsLayer { alpha = 1f - state.progress }.chatSquircleClip(cornerRadius)
        .background(background).semantics { contentDescription = "包含剧透的媒体" }
        .then(if (!state.revealed) Modifier.clickable(enabled = visible, onClickLabel = "显示剧透媒体") { state.reveal() } else Modifier)) {
        if (thumbnail != null) MediaSpoilerThumbnail(thumbnail, visible, Modifier.fillMaxSize(), contentScale)
        val maskReady = MediaSpoilerParticleMask(timeMillis, state.progress, visible, Modifier.fillMaxSize())
        LaunchedEffect(state, running, maskReady) {
            if (running && maskReady) {
                var previous = withFrameNanos { it }
                var accumulated = timeMillis
                while (isActive) {
                    delay(IMAGE_SPOILER_FRAME_MILLIS)
                    val now = withFrameNanos { it }
                    accumulated = advanceImageSpoilerTime(accumulated, (now - previous) / 1_000_000L)
                    timeMillis = (accumulated / IMAGE_SPOILER_FRAME_MILLIS * IMAGE_SPOILER_FRAME_MILLIS) % IMAGE_SPOILER_CYCLE_MILLIS
                    previous = now
                }
            }
        }
    }
}
