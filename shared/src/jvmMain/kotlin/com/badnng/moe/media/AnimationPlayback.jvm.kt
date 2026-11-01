package com.badnng.moe.media

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.badnng.moe.ChatButton
import com.badnng.moe.chat.ChatResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text

/** The native file identity is supplied by the caller's key and survives download completion. */
private class DesktopAnimationSession(
    private val path: String,
    private val size: Long,
    private val readPart: ((Long, Int, (ChatResult<ByteArray>) -> Unit) -> Unit)?,
    private val windowIsActive: () -> Boolean,
    private val readerFactory: (() -> MediaRangeReader)?,
    private val currentSize: () -> Long,
    private val startPositionMillis: Long,
    private val sourceIsActive: () -> Boolean,
) : AutoCloseable {
    var player by mutableStateOf<InlineVideoPlayer?>(null)
        private set
    private var source: ProgressiveVideoSource? = null
    private var disposed = false

    @Synchronized fun setActive(active: Boolean) {
        if (disposed) return
        if (!active || !windowIsActive() || !sourceIsActive()) { suspendPlayback(); return }
        if (player == null) {
            val reader = readPart
            source = if (path.isEmpty() && (reader != null || readerFactory != null))
                runCatching { ProgressiveVideoSource(size, readerFactory?.invoke() ?: MediaRangeReader(checkNotNull(reader)), currentSize) }.getOrNull() else null
            player = InlineVideoPlayer(path, autoPlay = true, progressiveSource = source,
                initialVolume = 0f, videoOnly = true, loop = true, initialPositionMillis = startPositionMillis,
                sourceIsActive = sourceIsActive)
        } else if (player?.state?.value?.failed == false) player?.play()
    }

    @Synchronized fun suspendPlayback() {
        // A not-yet-ready player owns a metadata probe. Closing its range bridge prevents
        // an offscreen probe from requesting more native bytes or creating a late decoder.
        if (player?.state?.value?.ready == false) release() else player?.pause()
    }

    @Synchronized fun retry(active: Boolean) {
        if (disposed || !active || !windowIsActive() || !sourceIsActive()) return
        release()
        setActive(active)
    }

    private fun release() {
        player?.close()
        source?.close()
        player = null
        source = null
    }

    @Synchronized override fun close() { disposed = true; release() }
}

/** Gif::startStreamedPlayer uses video-only looping; inactivity pauses native decoder work immediately. */
@Composable
actual fun AnimationPlayback(
    path: String,
    active: Boolean,
    modifier: Modifier,
    streamSize: Long,
    readPart: ((Long, Int, (ChatResult<ByteArray>) -> Unit) -> Unit)?,
    placeholder: (@Composable () -> Unit)?,
    readerFactory: (() -> MediaRangeReader)?,
    startPositionMillis: Long,
    displayLabel: String,
    contentDescription: String,
    outputGate: MediaOutputGate?,
) {
    val currentReadPart by rememberUpdatedState(readPart)
    val currentReaderFactory by rememberUpdatedState(readerFactory)
    val currentStreamSize by rememberUpdatedState(streamSize)
    val activity = LocalWindowActivity.current
    val currentActivity by rememberUpdatedState(activity)
    val focused = activity?.collectAsStateWithLifecycle()?.value != false
    val session = remember(startPositionMillis, outputGate) {
        DesktopAnimationSession(path, streamSize, if (readPart == null) null else { offset, count, result ->
            currentReadPart?.invoke(offset, count, result)
        }, { currentActivity?.value != false }, if (readerFactory == null) null else { { checkNotNull(currentReaderFactory).invoke() } },
            { currentStreamSize }, startPositionMillis, { outputGate?.active() != false })
    }
    DisposableEffect(session) { onDispose { session.close() } }
    DisposableEffect(session, activity) {
        val observer = activity?.let { observeWindowInactivity(it, { session.suspendPlayback() }) }
        onDispose { observer?.close() }
    }
    DisposableEffect(session, outputGate) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val cancel = outputGate?.onInvalidated { scope.launch { session.suspendPlayback() } }
        onDispose { cancel?.invoke(); scope.cancel() }
    }
    val enabled = active && focused && outputGate?.active() != false
    val playbackDescription = contentDescription
    SideEffect { session.setActive(enabled) }
    Box(modifier, contentAlignment = Alignment.Center) {
        val player = session.player
        val state = player?.state?.collectAsStateWithLifecycle()?.value
        if (player == null || !player.hasFrame()) placeholder?.invoke()
        if (player != null) Canvas(Modifier.fillMaxSize().semantics { this.contentDescription = playbackDescription }) {
            if (outputGate?.active() != false) clipRect { drawIntoCanvas { player.draw(it.skiaCanvas, size.width, size.height) } }
        }
        if (state?.failed == true) Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(8.dp)) {
            Text("$displayLabel 暂时无法播放", fontSize = 12.sp)
            ChatButton("重试", enabled = enabled, onClick = { session.retry(enabled) })
        } else if (enabled && (state == null || !state.ready || state.loading || state.buffering)) {
            InfiniteProgressIndicator(color = Color.Gray, modifier = Modifier.semantics {
                this.contentDescription = "$displayLabel 加载中"
                progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
            })
        }
    }
}
