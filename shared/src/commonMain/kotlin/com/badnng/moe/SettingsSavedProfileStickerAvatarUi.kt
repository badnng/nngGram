package com.badnng.moe

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badnng.moe.settings.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import top.yukonga.miuix.kmp.basic.Text

/** Explicitly selected saved-photo source. No identity or file is supplied by the UI. */
@Composable
internal fun SavedProfileStickerAvatarPreview(
    request: SettingsSavedProfileStickerAvatarRequest,
    sticker: SettingsProfilePhotoSticker,
    parentLease: SettingsProfileExtrasPageLease,
    itemLease: SettingsProfileExtrasPageLease,
    mediaActive: Boolean,
    viewport: Rect,
    ownerRevision: Long,
    own: (ProfileHistoryAnimationOwner) -> Unit,
    release: (ProfileHistoryAnimationOwner) -> Unit,
    valid: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val client = LocalProfileStickerAvatarArtwork.current
    val scope = rememberCoroutineScope()
    val window = LocalWindowInfo.current
    val windowActivity = rememberProfileHistoryWindowActivity()
    val activity = remember(windowActivity) { ProfileHistoryAnimationActivity(windowActivity) }
    val activityState by activity.state.collectAsState()
    DisposableEffect(activity) {
        val observer = windowActivity.observe(activity::inactive, activity::changed)
        onDispose { observer.close(); activity.close() }
    }
    val windowRect = Rect(0f, 0f, window.containerSize.width.toFloat(), window.containerSize.height.toFloat())
    var bounds by remember(request, sticker) { mutableStateOf(Rect.Zero) }
    var size by remember(request, sticker) { mutableStateOf(IntSize.Zero) }
    var retry by remember(request, sticker) { mutableIntStateOf(0) }
    var captured by remember(request, sticker) { mutableStateOf<ProfileHistoryAnimationOwner?>(null) }
    var attempt by remember(request, sticker) { mutableStateOf<ProfileStickerAvatarSavedPreviewLease?>(null) }
    val px = (minOf(size.width, size.height) * .67f).roundToInt().coerceIn(1, 1024)
    val state = remember(request, sticker, client, px, retry) { AvatarClipState() }
    val visible = mediaActive && window.isWindowFocused && activity.active() && savedVisible(bounds, viewport, windowRect)
    val provider = client.provider?.takeIf { client.generation == request.parent.generation && it.generation == client.generation }
    val latestValid by rememberUpdatedState(valid)
    val latestVisible by rememberUpdatedState(visible)

    DisposableEffect(state, visible, size, ownerRevision, activityState.revision) {
        if (visible && size.width > 0 && size.height > 0 && provider != null && valid()) {
            state.failed = false; state.failure = null
            // Created and bound before entering provider/Engine so a window loss can
            // revoke the exact broker demand even while acquisition has not returned.
            val demand = SettingsSavedProfileStickerAvatarDemand()
            val owner = ProfileHistoryAnimationOwner(parentLease, itemLease, activity, demand)
            own(owner); activity.attach(owner); captured = owner
            val gate = AvatarUiLease()
            val bound = CompletableDeferred<Boolean>()
            val delivered = MutableStateFlow(false)
            val claimed = MutableStateFlow<ProfileStickerAvatarPreviewClip?>(null)
            var lease: ProfileStickerAvatarSavedPreviewLease? = null
            try {
                lease = provider.savedPreviewOwned(request, px, { gate.active() && owner.active() }, demand) { result ->
                    val incoming = result.value
                    val handled = MutableStateFlow(false)
                    val job = scope.launch {
                        if (!bound.await() || !delivered.compareAndSet(false, true)) {
                            if (incoming !== claimed.value) runCatching { incoming?.close() }
                            handled.value = true; return@launch
                        }
                        val live = captured === owner && gate.active() && owner.active() && latestVisible && latestValid() &&
                            attempt === lease && lease?.requestCurrent() == true
                        if (live && result.error == null && incoming != null && incoming.active()) {
                            claimed.value = incoming; handled.value = true; state.clip = incoming
                        } else {
                            runCatching { incoming?.close() }; handled.value = true
                            if (live) state.failRetainingSource()
                        }
                    }
                    job.invokeOnCompletion { if (handled.compareAndSet(false, true) && incoming !== claimed.value) runCatching { incoming?.close() } }
                }
                if (gate.active() && owner.active() && visible && valid()) {
                    val actual = checkNotNull(lease)
                    attempt = actual; state.begin(gate, actual); bound.complete(true)
                } else { gate.close(); runCatching { lease?.close() }; bound.complete(false) }
            } catch (_: Exception) {
                gate.close(); runCatching { lease?.close() }; bound.complete(false); state.failRetainingSource()
            }
            onDispose {
                // Window callbacks only revoke owner; borrowed frames and clip are closed here on the UI thread.
                owner.revoke(); gate.close(); bound.complete(false); state.close(); activity.detach(owner); release(owner)
                if (captured === owner) captured = null
                if (attempt === lease) attempt = null
            }
        } else onDispose { }
    }

    val owner = captured
    val lease = attempt
    val clip = state.clip
    LaunchedEffect(state, clip, owner, visible) {
        if (visible && clip != null && owner != null) snapshotFlow { state.target }.filterNotNull().collect { target ->
            if (!owner.active() || lease?.requestCurrent() != true || !clip.active() || state.clip !== clip || !state.current(target)) return@collect
            val frameOwner = AvatarFrameOwner(); var transferred = false
            state.beginFrame(frameOwner)
            try {
                frameOwner.lease = clip.frame(target.timeMillis, {
                    frameOwner.gate.active() && owner.active() && lease?.requestCurrent() == true && clip.active()
                }) {
                    frameOwner.completion.complete(it)
                }
                val result = frameOwner.completion.await() ?: return@collect
                val current = frameOwner.gate.active() && owner.active() && clip.active() && latestVisible && latestValid() &&
                    captured === owner && state.clip === clip && state.current(target) && lease?.requestCurrent() == true
                if (current && result.error == null && result.value?.image != null)
                    transferred = state.frame(frameOwner, target, result.value)
                else if (current) state.failRetainingSource()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (owner.active() && clip.active() && latestVisible && latestValid() && lease?.requestCurrent() == true)
                    state.failRetainingSource()
            } finally { state.endFrame(frameOwner); if (!transferred) frameOwner.close() }
        }
    }
    LaunchedEffect(state, clip, owner, visible) {
        if (visible && clip != null && owner != null && owner.active() && lease?.requestCurrent() == true && clip.active()) {
            val animated = clip.animated && clip.durationMillis > 0 && clip.frameDurationMillis > 0
            val playback = state.startPlayback(animated)
            if (!animated) return@LaunchedEffect
            val start = withFrameNanos { it }
            while (owner.active() && lease?.requestCurrent() == true && clip.active() && !state.failed) {
                val elapsed = withFrameNanos { (it - start) / 1_000_000L }
                if (!owner.active() || lease?.requestCurrent() != true || !clip.active()) break
                val interval = clip.frameDurationMillis.coerceAtLeast(1L)
                state.aim(playback, ((elapsed % clip.durationMillis) / interval) * interval)
            }
        }
    }
    Box(modifier.onSizeChanged { size = it }.onGloballyPositioned { bounds = it.boundsInWindow() }.semantics {
        contentDescription = when {
            state.art?.image == null || clip?.active() != true || lease?.requestCurrent() != true -> "已保存表情头像预览"
            clip?.animated == true -> "已保存动态表情头像"
            else -> "已保存静态表情头像"
        }
    }, contentAlignment = Alignment.Center) {
        SavedAvatarBackground(sticker.background, { owner?.active() == true && lease?.requestCurrent() == true }, Modifier.fillMaxSize())
        Canvas(Modifier.fillMaxSize(.67f)) {
            if (owner?.active() == true && clip?.active() == true && lease?.requestCurrent() == true) drawAvatarArt(state.art, Color.White)
        }
        val marker = state.failure
        when {
            provider == null -> SavedStickerStatus("不可用", "表情头像暂不可用")
            state.failed && marker != null && lease?.requestCurrent() == true -> SavedStickerStatus("不可用", "表情头像暂不可用") {
                    if (state.failure === marker && captured === owner && owner?.active() == true && latestVisible && latestValid() &&
                        lease?.requestCurrent() == true) retry++
            }
            state.failed -> SavedStickerStatus("不可用", "表情头像暂不可用")
            visible && (lease?.requestCurrent() == false || clip?.active() == false) -> SavedStickerStatus("不可用", "表情头像暂不可用")
            visible && state.art == null -> SavedStickerStatus("加载中", "正在加载表情头像")
        }
    }
}

@Composable
private fun SavedStickerStatus(label: String, description: String, retry: (() -> Unit)? = null) {
    Column(Modifier.chatSquircleBackground(Color.Black.copy(alpha = .82f), 12.dp)
        .semantics { contentDescription = description }.padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 12.sp, color = Color.White)
        if (retry != null) ChatButton("重试", Modifier.semantics { contentDescription = "重试表情头像" }, onClick = retry)
    }
}

private fun savedVisible(vararg rects: Rect) = rects.isNotEmpty() &&
    rects.minOf { it.right } > rects.maxOf { it.left } && rects.minOf { it.bottom } > rects.maxOf { it.top }

@Composable
private fun SavedAvatarBackground(background: SettingsProfilePhotoSticker.Background, active: () -> Boolean, modifier: Modifier) {
    val field by produceState<ImageBitmap?>(null, background) {
        value = withContext(Dispatchers.Default) {
            val colors = (background as? SettingsProfilePhotoSticker.Background.Freeform)?.colors()
                ?.let { AvatarPreviewColors.from(it) }
            colors?.let(AvatarPreviewGradient::complex45)?.let { pixels ->
                val bitmap = ImageBitmap(64, 64); val canvas = androidx.compose.ui.graphics.Canvas(bitmap); val paint = Paint()
                for (y in 0 until 64) for (x in 0 until 64) {
                    paint.color = Color(0xFF000000L or pixels[y * 64 + x].toLong())
                    canvas.drawRect(Rect(x.toFloat(), y.toFloat(), x + 1f, y + 1f), paint)
                }
                bitmap
            }
        }
    }
    Canvas(modifier) {
        if (!active()) return@Canvas
        fun rgb(value: Int) = Color(0xFF000000L or value.toLong())
        when (background) {
            is SettingsProfilePhotoSticker.Background.Solid -> drawRect(rgb(background.color))
            is SettingsProfilePhotoSticker.Background.Gradient -> {
                val angle = background.rotationAngle.toDouble() * PI / 180.0
                // TDLib defines clockwise rotation of the initial top-to-bottom axis.
                val dx = -sin(angle).toFloat() * size.width / 2f; val dy = cos(angle).toFloat() * size.height / 2f
                val center = Offset(size.width / 2f, size.height / 2f)
                drawRect(Brush.linearGradient(listOf(rgb(background.topColor), rgb(background.bottomColor)),
                    start = center - Offset(dx, dy), end = center + Offset(dx, dy)))
            }
            is SettingsProfilePhotoSticker.Background.Freeform -> {
                val image = field
                if (image != null) drawImage(image, srcOffset = IntOffset.Zero, srcSize = IntSize(64, 64),
                    dstOffset = IntOffset.Zero, dstSize = IntSize(size.width.roundToInt().coerceAtLeast(1), size.height.roundToInt().coerceAtLeast(1)),
                    filterQuality = FilterQuality.Low)
                else drawRect(rgb(background.first))
            }
        }
    }
}
