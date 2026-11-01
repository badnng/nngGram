package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.MediaFile
import com.badnng.moe.chat.MediaFormat
import com.badnng.moe.media.AnimationPlayback
import com.badnng.moe.media.MediaOutputGate
import com.badnng.moe.media.MediaPreview
import com.badnng.moe.settings.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private class CurrentPhotoAttempt

/** One dialog owns one atomic opening; admission provides the exact actor epoch. */
internal class CurrentProfilePhotoOpening(
    val parent: SettingsProfileExtrasToken, val parentLease: SettingsProfileExtrasPageLease,
) {
    private val local = settingsProfileExtrasPageLease()
    val lease = object : SettingsProfileExtrasPageLease {
        override fun active() = local.active() && parentLease.active()
        override fun close() = local.close()
    }
    var epoch by mutableStateOf<Long?>(null)
    private var attempt: CurrentPhotoAttempt? = null
    var waiting by mutableStateOf(false)
        private set
    var rejected by mutableStateOf(false)
        private set
    private var item: CurrentProfilePhotoItem? = null

    fun begin(): Any {
        invalidateItem(); val next = CurrentPhotoAttempt(); attempt = next
        waiting = true; rejected = false
        return next
    }
    fun complete(ticket: Any, admitted: Long?): Boolean {
        if (attempt !== ticket || !lease.active()) return false
        attempt = null; waiting = false
        if (admitted != null && admitted > 0) { epoch = admitted; rejected = false }
        else { epoch = null; rejected = true }
        return true
    }
    fun own(value: CurrentProfilePhotoItem) {
        val previous = item; item = value
        if (previous !== value) previous?.close()
        if (!lease.active() || item !== value) value.close()
    }
    fun release(value: CurrentProfilePhotoItem) { if (item === value) item = null; value.close() }
    fun invalidateItem() { val old = item; item = null; old?.close() }
    fun close() { lease.close(); attempt = null; invalidateItem() }
}

internal class CurrentProfilePhotoItem(val epoch: Long, val photo: SettingsCurrentProfilePhoto) {
    val lease = settingsProfileExtrasPageLease()
    private var cover: ProfileHistoryAnimationOwner? = null
    private var video: ProfileHistoryAnimationOwner? = null
    fun own(use: SettingsCurrentProfilePhotoMediaUse, owner: ProfileHistoryAnimationOwner) {
        val previous = if (use == SettingsCurrentProfilePhotoMediaUse.STATIC_PREVIEW) cover else video
        if (use == SettingsCurrentProfilePhotoMediaUse.STATIC_PREVIEW) cover = owner else video = owner
        if (previous !== owner) previous?.close()
        if (!lease.active() || (if (use == SettingsCurrentProfilePhotoMediaUse.STATIC_PREVIEW) cover else video) !== owner) owner.close()
    }
    fun release(use: SettingsCurrentProfilePhotoMediaUse, owner: ProfileHistoryAnimationOwner) {
        if (use == SettingsCurrentProfilePhotoMediaUse.STATIC_PREVIEW && cover === owner) cover = null
        if (use == SettingsCurrentProfilePhotoMediaUse.ANIMATION_PREVIEW && video === owner) video = null
        owner.close()
    }
    fun close() {
        lease.close(); val oldCover = cover; val oldVideo = video; cover = null; video = null
        oldCover?.revoke(); oldVideo?.revoke(); oldCover?.close(); oldVideo?.close()
    }
}

internal class SettingsCurrentProfilePhotoUiState {
    var opening by mutableStateOf<CurrentProfilePhotoOpening?>(null)
        private set

    fun open(parent: SettingsProfileExtrasToken, parentLease: SettingsProfileExtrasPageLease,
        actions: SettingsCurrentProfilePhotoActions, scope: CoroutineScope) {
        if (opening != null || !parentLease.active()) return
        val next = CurrentProfilePhotoOpening(parent, parentLease); opening = next
        val attempt = next.begin()
        val delivered = MutableStateFlow(false)
        fun admitted(epoch: Long?) {
            if (!delivered.compareAndSet(false, true)) return
            scope.launch {
                if (opening === next && next.complete(attempt, epoch)) return@launch
                if (epoch != null && epoch > 0) actions.close(parent, epoch)
            }
        }
        try { actions.open(parent, next.lease::active, ::admitted) } catch (_: Exception) { admitted(null) }
    }

    fun refresh(expected: CurrentProfilePhotoOpening, actions: SettingsCurrentProfilePhotoActions, scope: CoroutineScope) {
        val epoch = expected.epoch ?: return
        if (opening !== expected || !expected.lease.active() || expected.waiting) return
        val attempt = expected.begin()
        val delivered = MutableStateFlow(false)
        fun admitted(nextEpoch: Long?) {
            if (!delivered.compareAndSet(false, true)) return
            scope.launch {
                if (opening === expected && expected.complete(attempt, nextEpoch)) return@launch
                if (nextEpoch != null && nextEpoch > 0) actions.close(expected.parent, nextEpoch)
            }
        }
        try { actions.refresh(expected.parent, epoch, ::admitted) } catch (_: Exception) { admitted(null) }
    }

    fun retry(expected: CurrentProfilePhotoOpening, actions: SettingsCurrentProfilePhotoActions, scope: CoroutineScope) {
        if (opening !== expected || !expected.lease.active() || !expected.rejected) return
        val parent = expected.parent; val parentLease = expected.parentLease
        close(expected, actions); open(parent, parentLease, actions, scope)
    }

    fun close(expected: CurrentProfilePhotoOpening, actions: SettingsCurrentProfilePhotoActions) {
        if (opening !== expected) return
        val epoch = expected.epoch
        expected.close(); opening = null
        if (epoch != null) runCatching { actions.close(expected.parent, epoch) }
        // The actor documents zero as cleanup only for an already inactive opening.
        runCatching { actions.close(expected.parent, 0) }
    }
}

internal class CurrentProfilePhotoUiCallbacks(
    val opening: CurrentProfilePhotoOpening, val close: () -> Unit, val refresh: () -> Unit,
)
internal val LocalCurrentProfilePhotoUiCallbacks = staticCompositionLocalOf<((CurrentProfilePhotoUiCallbacks) -> Unit)?> { null }
internal val LocalCurrentProfilePhotoEntryProbe = staticCompositionLocalOf<((() -> Unit) -> Unit)?> { null }

@Composable
internal fun SettingsCurrentProfilePhotoDialog(
    state: SettingsCurrentProfilePhotoUiState, extras: SettingsProfileExtrasSnapshot,
    actions: SettingsCurrentProfilePhotoActions,
) {
    // Retry can replace the keyed dialog in the same frame. Its admission belongs
    // to the persistent dialog host, not the outgoing dialog's coroutine scope.
    val scope = rememberCoroutineScope()
    val opening = state.opening ?: return
    key(opening) { CurrentProfilePhotoDialogOwned(state, opening, extras, actions, scope) }
}

@Composable
private fun CurrentProfilePhotoDialogOwned(state: SettingsCurrentProfilePhotoUiState,
    opening: CurrentProfilePhotoOpening, extras: SettingsProfileExtrasSnapshot, actions: SettingsCurrentProfilePhotoActions,
    scope: CoroutineScope) {
    val pageActive = LocalSettingsPageActive.current
    val latestExtras by rememberUpdatedState(extras)
    val latestPageActive by rememberUpdatedState(pageActive)
    val latestActions by rememberUpdatedState(actions)
    fun current() = state.opening === opening && opening.lease.active() && latestPageActive && latestExtras.token == opening.parent
    fun canRefresh(): Boolean {
        val snapshot = latestExtras.currentPhoto
        return current() && !opening.waiting && opening.epoch != null && snapshot.epoch == opening.epoch &&
            snapshot.status != SettingsCurrentProfilePhotoStatus.CLOSED && snapshot.status != SettingsCurrentProfilePhotoStatus.LOADING
    }
    val close: () -> Unit = { state.close(opening, latestActions) }
    val refresh: () -> Unit = {
        if (current()) {
            if (opening.rejected) state.retry(opening, latestActions, scope)
            else if (canRefresh()) state.refresh(opening, latestActions, scope)
        }
    }
    DisposableEffect(opening) { onDispose { state.close(opening, latestActions) } }
    LaunchedEffect(pageActive, extras.token) { if (!pageActive || extras.token != opening.parent) state.close(opening, actions) }
    val observer = LocalCurrentProfilePhotoUiCallbacks.current
    val payload = if (observer == null) null else CurrentProfilePhotoUiCallbacks(opening, close, refresh)
    SideEffect { if (payload != null) observer?.invoke(payload) }

    val epoch = opening.epoch
    val snapshot = extras.currentPhoto.takeIf { current() && !opening.waiting && epoch != null && it.epoch == epoch }
    val photo = snapshot?.takeIf { it.status == SettingsCurrentProfilePhotoStatus.READY }?.photo?.takeIf { it.photoId != 0L }
    val item = if (photo == null || epoch == null) null else remember(opening, epoch, photo) { CurrentProfilePhotoItem(epoch, photo) }
    if (item != null) DisposableEffect(opening, item) {
        opening.own(item); onDispose { opening.release(item) }
    }

    LocalSettingsSecurityConfirmationHost.current.Content(current(), "当前头像", null, 460.dp, close) {
        val density = LocalDensity.current
        val window = LocalWindowInfo.current
        val height = with(density) { window.containerSize.height.toDp() }
        val detailSize = LocalSettingsDetailViewportSize.current
        val detailHeight = with(density) { (detailSize?.height?.takeIf { it > 0 } ?: window.containerSize.height).toDp() }
        // Keep the complete square reachable in the fixed parent scroller viewport,
        // which may be smaller than the Tao window itself.
        val square = minOf((height - 210.dp).coerceIn(80.dp, 280.dp), (detailHeight - 8.dp).coerceAtLeast(0.dp))
        var viewport by remember(opening) { mutableStateOf(Rect.Zero) }
        Column(Modifier.fillMaxWidth().heightIn(max = (height - 148.dp).coerceIn(96.dp, 560.dp)),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(1f, fill = false).fillMaxWidth().onGloballyPositioned { viewport = it.boundsInWindow() }) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    when {
                        opening.waiting -> SettingsNote("正在读取当前头像……")
                        opening.rejected -> SettingsNote("当前头像未能打开，请关闭后重新打开。", error = true)
                        snapshot == null || snapshot.status == SettingsCurrentProfilePhotoStatus.CLOSED -> SettingsNote("正在等待当前头像资料……")
                        snapshot.status == SettingsCurrentProfilePhotoStatus.LOADING -> SettingsNote("正在读取当前头像……")
                        snapshot.status == SettingsCurrentProfilePhotoStatus.EMPTY -> SettingsNote("尚未设置当前头像。")
                        snapshot.status == SettingsCurrentProfilePhotoStatus.UNKNOWN -> SettingsNote("当前头像资料尚未就绪，请刷新。")
                        snapshot.status == SettingsCurrentProfilePhotoStatus.ROLE_CONFLICT -> SettingsNote("当前头像角色不一致，请刷新后查看。", error = true)
                        snapshot.status == SettingsCurrentProfilePhotoStatus.CONFLICT -> SettingsNote("当前头像资料已变化，请刷新后查看。", error = true)
                        snapshot.status == SettingsCurrentProfilePhotoStatus.ERROR -> SettingsNote("当前头像读取失败，请刷新。", error = true)
                        item == null -> SettingsNote("当前头像资料不完整，请刷新。", error = true)
                        else -> {
                            key(item) {
                                CurrentProfilePhotoPreview(opening, item, actions, viewport, current = {
                                    current() && !opening.waiting && opening.epoch == item.epoch &&
                                        latestExtras.currentPhoto.epoch == item.epoch &&
                                        latestExtras.currentPhoto.status == SettingsCurrentProfilePhotoStatus.READY &&
                                        latestExtras.currentPhoto.photo == item.photo
                                }, modifier = Modifier.widthIn(max = square).fillMaxWidth().aspectRatio(1f))
                            }
                            if (currentAnimation(item.photo) == null &&
                                (item.photo.hasAnimation || item.photo.invalidAnimation || item.photo.invalidSmallAnimation))
                                SettingsNote("视频信息暂不可用。")
                            else if (currentAnimation(item.photo) == null && item.photo.hasSticker && item.photo.invalidSticker)
                                SettingsNote("表情头像信息暂不可用。")
                            else if (currentAnimation(item.photo) == null && item.photo.hasSticker && item.photo.sticker == null)
                                SettingsNote("表情头像动画暂不可播放。")
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ChatButton("关闭", Modifier.weight(1f), onClick = close)
                ChatButton(if (opening.rejected) "重试" else "刷新", Modifier.weight(1f),
                    enabled = (opening.rejected && current() && !opening.waiting) || canRefresh(), onClick = refresh)
            }
        }
    }
}

private fun currentStaticVariant(photo: SettingsCurrentProfilePhoto): SettingsProfilePhotoVariant? =
    photo.variants.filter { it.width > 0 && it.height > 0 && it.width.toLong() * it.height <= 24_000_000 &&
        it.file.id > 0 && it.file.uniqueId.isNotBlank() && it.file.size in 0..(24L * 1024 * 1024) }
        .maxByOrNull { it.width.toLong() * it.height }

private fun currentAnimation(photo: SettingsCurrentProfilePhoto): SettingsProfilePhotoAnimation? = photo.preferredAnimation()?.takeIf {
    it.length in 1..1280 && (photo.animation != null || it.length == 160) && it.mainFrameTimestampMillis != null &&
        it.file.id > 0 && it.file.uniqueId.length <= 1024 && it.file.size in 0..(24L * 1024 * 1024) &&
        it.file.expectedSize >= 0 && it.file.downloadedSize >= 0
}

private fun currentVisible(vararg rects: Rect) = rects.isNotEmpty() &&
    rects.minOf { it.right } > rects.maxOf { it.left } && rects.minOf { it.bottom } > rects.maxOf { it.top }

/** The native lease has one observer; fan out its one-way signal without touching Compose off-scene. */
private class CurrentMediaOutput(
    private val owner: ProfileHistoryAnimationOwner,
    private val native: SettingsCurrentProfilePhotoFileLease,
) : MediaOutputGate {
    val invalidated = MutableStateFlow(false)
    override fun active() = !invalidated.value && owner.active() && native.active()
    fun requestCurrent() = !invalidated.value && owner.active() && native.requestCurrent()
    override fun onInvalidated(callback: () -> Unit): () -> Unit {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        scope.launch(start = CoroutineStart.UNDISPATCHED) { invalidated.first { it }; callback() }
        return { scope.cancel() }
    }
    fun invalidate() { owner.revoke(); invalidated.value = true }
}

/** A local identity failure closes its file; a native failure retains its source witness. */
private class CurrentMediaFailure(val witness: CurrentMediaOutput?)

/** Test renderers observe the same gate; production always uses the real silent video player. */
internal interface CurrentProfilePhotoRenderer {
    @Composable fun Content(file: MediaFile, startPositionMillis: Long, active: Boolean,
        outputGate: MediaOutputGate, modifier: Modifier)
}
private object NativeCurrentProfilePhotoRenderer : CurrentProfilePhotoRenderer {
    @Composable override fun Content(file: MediaFile, startPositionMillis: Long, active: Boolean,
        outputGate: MediaOutputGate, modifier: Modifier) {
        AnimationPlayback(path = checkNotNull(file.localPath), active = active, modifier = modifier,
            startPositionMillis = startPositionMillis, displayLabel = "头像动画", contentDescription = "头像动画",
            outputGate = outputGate)
    }
}
internal val LocalCurrentProfilePhotoRenderer = staticCompositionLocalOf<CurrentProfilePhotoRenderer> { NativeCurrentProfilePhotoRenderer }

@Composable
private fun CurrentProfilePhotoPreview(opening: CurrentProfilePhotoOpening, item: CurrentProfilePhotoItem,
    actions: SettingsCurrentProfilePhotoActions, viewport: Rect, current: () -> Boolean, modifier: Modifier) {
    val window = LocalWindowInfo.current
    var bounds by remember(item) { mutableStateOf(Rect.Zero) }
    val windowRect = Rect(0f, 0f, window.containerSize.width.toFloat(), window.containerSize.height.toFloat())
    val active = current() && item.lease.active() && window.isWindowFocused && currentVisible(bounds, viewport, windowRect)
    Box(modifier.clip(CircleShape).background(MiuixTheme.colorScheme.surfaceContainer)
        .onGloballyPositioned { bounds = it.boundsInWindow() }
        .semantics { contentDescription = "当前头像预览" }, contentAlignment = Alignment.Center) {
        val variant = currentStaticVariant(item.photo)
        if (variant != null) CurrentProfilePhotoMedia(opening, item, actions, SettingsCurrentProfilePhotoMediaUse.STATIC_PREVIEW,
            variant.file, null, active, current, Modifier.fillMaxSize())
        else Text("静态封面暂不可用", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        val animation = currentAnimation(item.photo)
        if (animation != null) CurrentProfilePhotoMedia(opening, item, actions, SettingsCurrentProfilePhotoMediaUse.ANIMATION_PREVIEW,
            animation.file, animation.mainFrameTimestampMillis, active, current, Modifier.fillMaxSize())
        else if (!item.photo.hasAnimation && item.photo.animation == null && item.photo.smallAnimation == null &&
            !item.photo.invalidAnimation && !item.photo.invalidSmallAnimation && item.photo.hasSticker && !item.photo.invalidSticker) {
            item.photo.sticker?.let { sticker ->
                SavedProfileStickerAvatarPreview(
                    SettingsSavedProfileStickerAvatarRequest.Current(opening.parent, item.epoch, item.photo.photoId),
                    sticker, opening.lease, item.lease, active, viewport, 0,
                    own = { item.own(SettingsCurrentProfilePhotoMediaUse.ANIMATION_PREVIEW, it) },
                    release = { item.release(SettingsCurrentProfilePhotoMediaUse.ANIMATION_PREVIEW, it) },
                    valid = current, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun CurrentProfilePhotoMedia(opening: CurrentProfilePhotoOpening, item: CurrentProfilePhotoItem,
    actions: SettingsCurrentProfilePhotoActions, use: SettingsCurrentProfilePhotoMediaUse, expected: MediaFile,
    startPosition: Long?, active: Boolean, current: () -> Boolean, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val window = rememberProfileHistoryWindowActivity()
    val activity = remember(window) { ProfileHistoryAnimationActivity(window) }
    val activityState by activity.state.collectAsState()
    DisposableEffect(activity) {
        val observer = window.observe(activity::inactive, activity::changed)
        onDispose { observer.close(); activity.close() }
    }
    var size by remember(item, use) { mutableStateOf(IntSize.Zero) }
    var retry by remember(item, use) { mutableIntStateOf(0) }
    var owner by remember(item, use) { mutableStateOf<ProfileHistoryAnimationOwner?>(null) }
    var output by remember(item, use) { mutableStateOf<CurrentMediaOutput?>(null) }
    var file by remember(item, use) { mutableStateOf<MediaFile?>(null) }
    var failure by remember(item, use) { mutableStateOf<CurrentMediaFailure?>(null) }
    var decoded by remember(item, use) { mutableStateOf(false) }
    val visible = active && activity.active()
    val latestVisible by rememberUpdatedState(visible)
    val latestCurrent by rememberUpdatedState(current)
    val latestActions by rememberUpdatedState(actions)
    val request = remember(opening, item, use, size, retry) {
        SettingsCurrentProfilePhotoMediaRequest(opening.parent, item.epoch, item.photo.photoId, use,
            size.width.coerceIn(1, 4096), size.height.coerceIn(1, 4096))
    }
    // A retry may produce an equal-valued native request; its explicit revision
    // is the ownership key that must dispose the previous lease and start a new one.
    DisposableEffect(request, retry, visible, activityState.revision) {
        file = null; failure = null; decoded = false; output = null
        if (visible && size.width > 0 && size.height > 0 && current()) {
            val captured = ProfileHistoryAnimationOwner(opening.lease, item.lease, activity)
            item.own(use, captured); activity.attach(captured); owner = captured
            val bound = CompletableDeferred<Boolean>(); val delivered = MutableStateFlow(false)
            var unsubscribe: (() -> Unit)? = null
            try {
                if (captured.active()) {
                    // Do not include native.active() here. The actor's source predicate calls
                    // this function, while the independent output gate below checks both.
                    val native = latestActions.acquireFile(request, captured::active) { result ->
                        scope.launch {
                            if (!bound.await() || !delivered.compareAndSet(false, true)) return@launch
                            if (owner !== captured || !latestVisible || !latestCurrent()) return@launch
                            val source = output ?: return@launch
                            if (result.error != null || result.value == null) {
                                // A normal failed file has no output permission but retains the
                                // exact native source witness until this attempt is released.
                                if (source.requestCurrent()) failure = CurrentMediaFailure(source)
                                return@launch
                            }
                            if (!source.active()) return@launch
                            val ready = result.value?.takeIf { actual -> result.error == null && actual.id == expected.id &&
                                actual.uniqueId.isNotBlank() && actual.uniqueId.length <= 1024 &&
                                (expected.uniqueId.isBlank() || actual.uniqueId == expected.uniqueId) &&
                                actual.size in 1..(24L * 1024 * 1024) && (expected.size == 0L || actual.size == expected.size) &&
                                actual.downloaded && !actual.localPath.isNullOrBlank() }
                            if (ready == null) { unsubscribe?.invoke(); captured.close(); failure = CurrentMediaFailure(null) } else file = ready
                        }
                    }
                    if (captured.bind(native) && visible && current()) {
                        val gate = CurrentMediaOutput(captured, native); output = gate
                        unsubscribe = native.onInvalidated { gate.invalidate(); activity.closeLater(captured) }
                        if (gate.requestCurrent() && visible && current()) bound.complete(true)
                        else { captured.close(); bound.complete(false) }
                    } else { captured.close(); bound.complete(false) }
                } else bound.complete(false)
            } catch (_: Exception) {
                val show = captured.active() && latestVisible && latestCurrent()
                unsubscribe?.invoke(); captured.close(); if (show) failure = CurrentMediaFailure(null); bound.complete(false)
            }
            onDispose { unsubscribe?.invoke(); bound.complete(false); captured.close(); activity.detach(captured); item.release(use, captured); if (owner === captured) owner = null }
        } else onDispose { }
    }
    val captured = owner
    val gate = output
    val invalidated = gate?.invalidated?.collectAsState()?.value == true
    val shown = file.takeIf { visible && gate?.active() == true && current() }
    val isVideo = use == SettingsCurrentProfilePhotoMediaUse.ANIMATION_PREVIEW
    Box(modifier.onSizeChanged { size = it }, contentAlignment = if (isVideo) Alignment.BottomCenter else Alignment.Center) {
        if (shown != null && isVideo && gate != null) key(item, captured, shown, gate, startPosition) {
            LocalCurrentProfilePhotoRenderer.current.Content(shown, checkNotNull(startPosition),
                visible && gate.active() && current(), gate, Modifier.fillMaxSize())
        }
        if (shown != null && !isVideo && gate != null) key(item, captured, shown, gate) {
            MediaPreview(checkNotNull(shown.localPath), MediaFormat.PHOTO, Modifier.fillMaxSize(),
                contentDescription = if (decoded) "当前头像静态封面 已载入" else "当前头像静态封面",
                animate = false, contentScale = ContentScale.Crop, onLoadResult = { success ->
                    if (latestVisible && latestCurrent() && gate.active() && owner === captured && file === shown) {
                        decoded = success; if (!success) failure = CurrentMediaFailure(gate)
                    }
                }, outputGate = gate)
        }
        val marker = failure?.takeIf { it.witness?.requestCurrent() != false }
        if (marker != null && visible) Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (isVideo) "视频暂不可用" else "封面暂不可用", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            ChatButton(if (isVideo) "重试视频" else "重试图片", onClick = {
                if (failure === marker && owner === captured && latestVisible && latestCurrent() &&
                    opening.lease.active() && item.lease.active() && activity.active() && marker.witness?.requestCurrent() != false) {
                    failure = null; retry++
                }
            })
        } else if (visible && invalidated) Text("头像资源暂不可用，请刷新。", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        else if (visible && shown == null && marker == null) Text(if (isVideo) "视频加载中" else "封面加载中",
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}
