package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
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
import com.badnng.moe.media.MediaPreview
import com.badnng.moe.settings.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Exact application callbacks, before the toolkit's attached-node click feedback wrapper. */
internal class ProfileHistoryUiCallbacks(
    val selection: ProfileHistoryUiSelection?,
    val confirmation: ProfileHistoryUiConfirmation?,
    val returnToList: () -> Unit,
    val dismissConfirmation: () -> Unit,
    val confirm: () -> Unit,
)

/** Optional offline ownership probe. Production keeps no observer or retained callback history. */
internal val LocalProfileHistoryUiCallbacks = staticCompositionLocalOf<((ProfileHistoryUiCallbacks) -> Unit)?> { null }

/** Optional offline capture of the same application actions handed to the selected-row buttons. */
internal class ProfileHistorySelectionCallbacks(
    val selection: ProfileHistoryUiSelection,
    val reuse: () -> Unit,
    val remove: () -> Unit,
)
internal val LocalProfileHistorySelectionCallbacks = staticCompositionLocalOf<((ProfileHistorySelectionCallbacks) -> Unit)?> { null }

/** An opening, a selection and a confirmation each have distinct, synchronously revocable owners. */
internal class SettingsProfilePhotoHistoryUiState {
    var session by mutableStateOf<ProfileHistoryUiSession?>(null)
        private set

    fun open(extras: SettingsProfileExtrasSnapshot, actions: SettingsProfilePhotoHistoryActions) {
        session?.close()
        val opened = ProfileHistoryUiSession(extras.token, extras.history.epoch)
        session = opened
        actions.open(opened.parent, opened.lease::active)
    }

    fun close(expected: ProfileHistoryUiSession, actions: SettingsProfilePhotoHistoryActions) {
        if (session !== expected) return
        expected.close()
        session = null
        actions.close(expected.parent)
    }
}

internal class ProfileHistoryUiSession(val parent: SettingsProfileExtrasToken, private var epochFloor: Long) {
    val lease = settingsProfileExtrasPageLease()
    private var newestEpoch = epochFloor
    var selection by mutableStateOf<ProfileHistoryUiSelection?>(null)
    var confirmation by mutableStateOf<ProfileHistoryUiConfirmation?>(null)

    fun observe(history: SettingsProfilePhotoHistorySnapshot) {
        if (!matches(history)) return
        newestEpoch = history.epoch
    }
    fun matches(history: SettingsProfilePhotoHistorySnapshot) = lease.active() && history.opened &&
        history.epoch > epochFloor && history.epoch >= newestEpoch

    fun choose(epoch: Long, identity: SettingsProfilePhotoIdentity, entry: SettingsProfilePhotoHistoryEntry) {
        clearSelection()
        selection = ProfileHistoryUiSelection(epoch, identity, entry.photoId, entry.rawOffset)
    }
    fun clearSelection() {
        confirmation?.lease?.close(); confirmation = null
        selection?.close(); selection = null
    }
    fun awaitRefresh(epoch: Long) {
        clearSelection()
        epochFloor = maxOf(epochFloor, newestEpoch, epoch)
    }
    fun close() { lease.close(); clearSelection() }
}

internal class ProfileHistoryUiSelection(
    val epoch: Long, val identity: SettingsProfilePhotoIdentity, val photoId: Long, val rawOffset: Int,
) {
    val lease = settingsProfileExtrasPageLease()
    private var animation: ProfileHistoryAnimationOwner? = null
    var coverOwner: ProfileHistoryAnimationOwner? = null
        private set
    var animationRevision by mutableStateOf(0L)
        private set
    var coverRevision by mutableStateOf(0L)
        private set
    fun own(owner: ProfileHistoryAnimationOwner) {
        val previous = animation; animation = owner
        if (previous !== owner) previous?.close()
        if (!lease.active() || animation !== owner) owner.close()
    }
    fun release(owner: ProfileHistoryAnimationOwner) { if (animation === owner) animation = null; owner.close() }
    fun ownCover(owner: ProfileHistoryAnimationOwner) {
        val previous = coverOwner; coverOwner = owner
        if (previous !== owner) previous?.close()
        if (!lease.active() || coverOwner !== owner) owner.close()
    }
    fun releaseCover(owner: ProfileHistoryAnimationOwner) { if (coverOwner === owner) coverOwner = null; owner.close() }
    fun suspendMedia() {
        val oldAnimation = animation; val oldCover = coverOwner
        animation = null; coverOwner = null
        // Revoke both predicates before either foreign close can synchronously call back or block.
        oldAnimation?.revoke(); oldCover?.revoke()
        if (oldAnimation != null) animationRevision++
        if (oldCover != null) coverRevision++
        oldAnimation?.close(); oldCover?.close()
    }
    fun close() { lease.close(); suspendMedia() }
}

private sealed class ProfileHistoryAnimationSlot {
    object Open : ProfileHistoryAnimationSlot()
    class Bound(val close: () -> Unit) : ProfileHistoryAnimationSlot()
    object Closed : ProfileHistoryAnimationSlot()
}

/** Binding and close can race; a file lease is claimed once and closed outside the CAS. */
internal class ProfileHistoryAnimationOwner(
    private val parentLease: SettingsProfileExtrasPageLease, private val selectionLease: SettingsProfileExtrasPageLease?,
    private val activity: ProfileHistoryAnimationActivity?,
    private val savedDemand: SettingsSavedProfileStickerAvatarDemand? = null,
) {
    constructor(session: ProfileHistoryUiSession, selectionLease: SettingsProfileExtrasPageLease?, activity: ProfileHistoryAnimationActivity?) :
        this(session.lease, selectionLease, activity)
    constructor(session: ProfileHistoryUiSession, selection: ProfileHistoryUiSelection, activity: ProfileHistoryAnimationActivity) :
        this(session.lease, selection.lease, activity)
    private val slot = MutableStateFlow<ProfileHistoryAnimationSlot>(ProfileHistoryAnimationSlot.Open)
    private val allowed = MutableStateFlow(true)
    fun revoke() { savedDemand?.revokeDemand(); allowed.value = false }
    fun active(): Boolean {
        if (activity != null && !activity.active()) {
            // The saved broker reads this final one-way token directly, including
            // before the provider or native read has returned its lease.
            savedDemand?.revokeDemand()
            // The native broker may call this under its monitor: revoke atomically here and
            // schedule the foreign lease close elsewhere, never invoke it under that monitor.
            if (allowed.compareAndSet(true, false)) activity.revokeLater(this)
            return false
        }
        val current = allowed.value && slot.value !== ProfileHistoryAnimationSlot.Closed &&
            parentLease.active() && selectionLease?.active() != false
        if (!current) savedDemand?.revokeDemand()
        return current
    }
    fun bind(value: SettingsProfilePhotoHistoryFileLease): Boolean = bindClose { value.close() }
    fun bind(value: SettingsCurrentProfilePhotoFileLease): Boolean = bindClose { value.close() }
    private fun bindClose(release: () -> Unit): Boolean {
        while (true) {
            val current = slot.value
            if (current !== ProfileHistoryAnimationSlot.Open || !active()) {
                close(); runCatching(release); return false
            }
            if (slot.compareAndSet(current, ProfileHistoryAnimationSlot.Bound(release))) {
                if (!active()) { close(); return false }
                return true
            }
        }
    }
    fun close(): Boolean {
        revoke()
        while (true) {
            val current = slot.value
            if (current === ProfileHistoryAnimationSlot.Closed) return false
            if (slot.compareAndSet(current, ProfileHistoryAnimationSlot.Closed)) {
                if (current is ProfileHistoryAnimationSlot.Bound) runCatching(current.close)
                return true
            }
        }
    }
}

/** Off-scene observer holds only atomics/flows, never Compose state or the mutable selection. */
internal data class ProfileHistoryAnimationActivityState(
    val owner: ProfileHistoryAnimationOwner? = null, val revision: Long = 0, val open: Boolean = true,
)
internal class ProfileHistoryAnimationActivity(private val window: ProfileHistoryWindowActivity) {
    val state = MutableStateFlow(ProfileHistoryAnimationActivityState())
    private val releaseScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun active(): Boolean = state.value.open && window.active()
    fun changed() {
        while (true) {
            val old = state.value
            if (!old.open) return
            if (state.compareAndSet(old, old.copy(revision = old.revision + 1))) return
        }
    }
    private fun changedFor(owner: ProfileHistoryAnimationOwner) {
        while (true) {
            val old = state.value
            if (!old.open || old.owner !== owner) return
            if (state.compareAndSet(old, old.copy(revision = old.revision + 1))) return
        }
    }
    fun inactive() {
        val owner = state.value.owner ?: return
        owner.close(); changedFor(owner)
    }
    fun revokeLater(owner: ProfileHistoryAnimationOwner) {
        releaseScope.launch { owner.close(); changedFor(owner) }
    }
    // A native output invalidation is not a new window-visibility revision: do not
    // automatically request the same stale source again.
    fun closeLater(owner: ProfileHistoryAnimationOwner) {
        releaseScope.launch { owner.close() }
    }
    fun attach(owner: ProfileHistoryAnimationOwner) {
        while (true) {
            val previous = state.value
            if (!previous.open) { owner.close(); return }
            if (state.compareAndSet(previous, previous.copy(owner = owner))) {
                if (previous.owner !== owner) previous.owner?.close()
                if (!active()) { owner.close(); changedFor(owner) }
                return
            }
        }
    }
    fun detach(owner: ProfileHistoryAnimationOwner) {
        while (true) {
            val previous = state.value
            if (previous.owner !== owner) return
            if (state.compareAndSet(previous, previous.copy(owner = null))) return
        }
    }
    fun close() {
        while (true) {
            val previous = state.value
            if (!previous.open) break
            if (state.compareAndSet(previous, previous.copy(owner = null, open = false))) { previous.owner?.close(); break }
        }
        releaseScope.cancel()
    }
}

/** Offline tests may inject a renderer; production always uses the real silent animation player. */
internal interface ProfileHistoryAnimationRenderer {
    @Composable fun Content(file: MediaFile, startPositionMillis: Long, active: Boolean, modifier: Modifier)
}
private object NativeHistoryAnimationRenderer : ProfileHistoryAnimationRenderer {
    @Composable override fun Content(file: MediaFile, startPositionMillis: Long, active: Boolean, modifier: Modifier) {
        AnimationPlayback(path = checkNotNull(file.localPath), active = active, modifier = modifier,
            startPositionMillis = startPositionMillis, displayLabel = "头像动画", contentDescription = "头像动画")
    }
}
internal val LocalProfileHistoryAnimationRenderer = staticCompositionLocalOf<ProfileHistoryAnimationRenderer> { NativeHistoryAnimationRenderer }
internal class ProfileHistoryUiConfirmation(
    private val session: ProfileHistoryUiSession, val selection: ProfileHistoryUiSelection,
    val target: SettingsProfilePhotoHistoryTarget, val reuse: Boolean,
) {
    val lease = settingsProfileExtrasPageLease()
    var submittedAfter by mutableStateOf<Long?>(null)
    val dismiss: () -> Unit = {
        if (session.confirmation === this) { lease.close(); session.confirmation = null }
    }
}

/** Kept in the existing profile category, including its narrow-window back navigation. */
@Composable
internal fun SettingsProfilePhotoHistoryUi(
    state: SettingsProfilePhotoHistoryUiState, extras: SettingsProfileExtrasSnapshot,
    actions: SettingsProfilePhotoHistoryActions, enabled: Boolean,
) {
    val session = state.session ?: return
    val pageActive = LocalSettingsPageActive.current
    val latestExtras by rememberUpdatedState(extras)
    val latestEnabled by rememberUpdatedState(enabled)
    val latestPageActive by rememberUpdatedState(pageActive)
    val latestActions by rememberUpdatedState(actions)
    val history = extras.history
    val owned = extras.token == session.parent && session.matches(history)
    // Epoch observation belongs to the committed composition, never a speculative composition read.
    SideEffect { if (owned) session.observe(history) }
    fun current(): Boolean = state.session === session && latestPageActive &&
        latestExtras.token == session.parent && session.matches(latestExtras.history)
    fun canWrite(): Boolean = current() && latestEnabled && !latestExtras.busy && !latestExtras.loading &&
        !latestExtras.history.loading && !latestExtras.history.refreshRequired &&
        latestExtras.history.identity?.isBot == false
    fun select(entry: SettingsProfilePhotoHistoryEntry) {
        val identity = latestExtras.history.identity
        if (current() && identity != null && entry in latestExtras.history.entries)
            session.choose(latestExtras.history.epoch, identity, entry)
    }
    fun refresh() {
        if (current() && !latestExtras.busy && !latestExtras.history.loading) {
            session.awaitRefresh(latestExtras.history.epoch)
            latestActions.refresh(session.parent)
        }
    }
    DisposableEffect(session) { onDispose { state.close(session, latestActions) } }
    LaunchedEffect(pageActive, extras.token) {
        if (!pageActive || extras.token != session.parent) state.close(session, actions)
    }
    LaunchedEffect(history.epoch, history.identity, history.entries, history.refreshRequired, owned) {
        val chosen = session.selection
        if (chosen != null && (!owned || chosen.epoch != history.epoch || chosen.identity != history.identity || history.refreshRequired ||
                history.entries.none { it.photoId == chosen.photoId && it.rawOffset == chosen.rawOffset })) session.clearSelection()
    }
    val confirmation = session.confirmation
    LaunchedEffect(extras.acceptedRevision, extras.failedRevision, confirmation) {
        val submitted = confirmation?.submittedAfter
        if (submitted != null && session.confirmation === confirmation) {
            val operation = if (confirmation.reuse) SettingsProfileExtrasOperation.REUSE_PHOTO else SettingsProfileExtrasOperation.REMOVE_HISTORY_PHOTO
            if (extras.operation == operation && extras.startedRevision == submitted + 1 && extras.acceptedRevision == submitted + 1) {
                confirmation.lease.close(); session.confirmation = null
            } else if (extras.operation == operation && extras.startedRevision == submitted + 1 &&
                extras.failedRevision == submitted + 1) confirmation.submittedAfter = null
        }
    }
    val capturedSelection = session.selection
    val returnToList: () -> Unit = {
        if (capturedSelection != null && session.selection === capturedSelection) session.clearSelection()
    }

    SettingsGroup("头像历史") {
        ChatButton("返回头像设置", Modifier.padding(12.dp), onClick = { state.close(session, latestActions) })
        if (!owned || history.loading && history.entries.isEmpty()) SettingsNote("正在读取头像历史……")
        if (owned) {
            SettingsNote(history.totalCount?.let { "已显示 ${history.entries.size} 张 · 共 $it 张" }
                ?: "已显示 ${history.entries.size} 张 · 总数未知")
            if (!history.loading && history.entries.isEmpty() && history.error == null && !history.refreshRequired &&
                history.unavailableCount == 0 && !history.hasMore) SettingsNote("还没有头像历史。")
            if (history.identity?.isBot == true) SettingsNote("此账号的头像历史仅供查看。")
            if (history.unavailableCount > 0) SettingsNote("${history.unavailableCount} 张头像暂时无法读取。", error = true)
            if (history.limited) SettingsNote("已达到本次 512 张的读取上限。")
            if (history.error != null || history.refreshRequired) {
                SettingsNote(if (history.refreshRequired) "头像资料已改变，请重新读取后选择。" else "头像历史读取失败，请重试。", error = true)
            }
            val chosen = session.selection?.takeIf { it.epoch == history.epoch && it.lease.active() }
            val selected = chosen?.let { selected -> history.entries.firstOrNull { it.photoId == selected.photoId && it.rawOffset == selected.rawOffset } }
            if (chosen != null && selected != null && !history.refreshRequired) {
                key(chosen) {
                    ProfileHistorySelected(session, chosen, selected, extras, actions, ::current, ::canWrite,
                        onSelect = ::select, onClear = returnToList)
                }
            } else if (history.entries.isNotEmpty()) {
                ProfileHistoryGrid(session, history, actions, current = ::current,
                    entryCurrent = { candidate -> current() && candidate in latestExtras.history.entries &&
                        latestExtras.history.epoch == history.epoch }, onSelect = ::select)
            }
            if (history.loading && history.entries.isNotEmpty()) SettingsNote("正在读取更多头像……")
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChatButton("重新读取", Modifier.weight(1f), enabled = !extras.busy && !history.loading, onClick = ::refresh)
                if (history.hasMore && !history.limited && !history.refreshRequired) ChatButton("更多头像", Modifier.weight(1f),
                    enabled = !extras.busy && !history.loading, onClick = {
                        if (current() && !latestExtras.busy && !latestExtras.history.loading) latestActions.more(session.parent)
                    })
            }
            if (extras.busy) SettingsNote("正在保存头像……")
            if (extras.operation == SettingsProfileExtrasOperation.REUSE_PHOTO || extras.operation == SettingsProfileExtrasOperation.REMOVE_HISTORY_PHOTO) {
                if (extras.error != null) SettingsNote(when {
                    extras.acceptedRevision > extras.completedRevision -> "头像修改已提交成功，但最新资料读取失败。请重新读取，不必再次提交。"
                    extras.error == SettingsError.CONFLICT -> "这张头像或当前头像已改变，请重新读取后确认。"
                    extras.error == SettingsError.RATE_LIMITED -> "修改过于频繁，请稍后重试。"
                    extras.error == SettingsError.FORBIDDEN -> "此账号暂时无法修改这张头像。"
                    else -> "头像修改失败。你的选择已保留，可明确重试。"
                }, error = true)
            }
        }
    }

    // Popup callbacks can outlive composition. Validate both the captured object and the latest snapshot here.
    val frozen = session.confirmation
    fun confirmationCurrent(): Boolean = frozen != null && current() && canWrite() &&
        session.confirmation === frozen && session.selection === frozen.selection && frozen.lease.active() &&
        frozen.selection.lease.active() && frozen.selection.epoch == latestExtras.history.epoch &&
        latestExtras.history.identity == frozen.target.identity &&
        !frozen.target.identity.isProtected(frozen.target.photoId) && latestExtras.history.entries.any {
            it.photoId == frozen.target.photoId && it.rawOffset == frozen.target.rawOffset
        }
    val dismissConfirmation: () -> Unit = frozen?.dismiss ?: {}
    val confirm: () -> Unit = {
        if (frozen != null && confirmationCurrent() && frozen.submittedAfter == null) {
            frozen.submittedAfter = latestExtras.startedRevision
            val alive = { session.lease.active() && frozen.selection.lease.active() && frozen.lease.active() }
            if (frozen.reuse) latestActions.reuse(session.parent, frozen.selection.epoch, frozen.target, alive)
            else latestActions.remove(session.parent, frozen.selection.epoch, frozen.target, alive)
        }
    }
    val callbackObserver = LocalProfileHistoryUiCallbacks.current
    SideEffect { callbackObserver?.invoke(ProfileHistoryUiCallbacks(capturedSelection, frozen, returnToList, dismissConfirmation, confirm)) }
    ProfileExtrasConfirmation(frozen != null && current(), if (frozen?.reuse == true) "设为当前头像？" else "删除这张历史头像？",
        confirmationCurrent() && frozen?.submittedAfter == null, onDismiss = dismissConfirmation, onConfirm = confirm) {
        SettingsNote(if (frozen?.reuse == true) "将把已选历史照片设为当前头像。提交前会再次核对头像身份。"
            else "将删除已选的这一张旧头像。当前头像仍使用头像设置中的独立删除入口。")
        if (extras.busy) SettingsNote("正在提交，请稍候……")
    }
}

@Composable
private fun ProfileHistoryGrid(
    session: ProfileHistoryUiSession, history: SettingsProfilePhotoHistorySnapshot,
    actions: SettingsProfilePhotoHistoryActions, current: () -> Boolean,
    entryCurrent: (SettingsProfilePhotoHistoryEntry) -> Boolean,
    onSelect: (SettingsProfilePhotoHistoryEntry) -> Unit,
) {
    val density = LocalDensity.current
    val window = LocalWindowInfo.current
    val height = with(density) { window.containerSize.height.toDp() }
    var viewport by remember { mutableStateOf(Rect.Zero) }
    LazyVerticalGrid(GridCells.Adaptive(112.dp), Modifier.fillMaxWidth()
        .heightIn(min = 100.dp, max = (height - 260.dp).coerceIn(100.dp, 520.dp))
        .clip(androidx.compose.ui.graphics.RectangleShape)
        .onGloballyPositioned { viewport = it.boundsInWindow() }.padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(history.entries, key = { "${history.epoch}:${it.photoId}:${it.rawOffset}" }) { entry ->
            var bounds by remember { mutableStateOf(Rect.Zero) }
            val visible = bounds.width > 0 && bounds.height > 0 && viewport.width > 0 && viewport.height > 0 && bounds.overlaps(viewport)
            Column(Modifier.fillMaxWidth().onGloballyPositioned { bounds = it.boundsInWindow() }
                .chatSquircleSurface(Color.Transparent, 15.dp).clickable { if (current()) onSelect(entry) }.padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ProfileHistoryImage(session, history.epoch, entry, SettingsProfilePhotoHistoryMediaUse.VISIBLE_CELL,
                    actions, visible && window.isWindowFocused && current(), Modifier.fillMaxWidth().aspectRatio(1f),
                    "查看头像 ${entry.rawOffset + 1}", valid = { entryCurrent(entry) })
                Text(if (entry.photoId == history.identity?.currentPhotoId) "当前头像" else "历史头像 ${entry.rawOffset + 1}")
                Text(profileHistoryDate(entry.addedDate), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                if (entry.hasAnimation || entry.hasSticker) Text(profileHistoryType(entry))
            }
        }
    }
}

@Composable
private fun ProfileHistorySelected(
    session: ProfileHistoryUiSession, selection: ProfileHistoryUiSelection, entry: SettingsProfilePhotoHistoryEntry,
    extras: SettingsProfileExtrasSnapshot, actions: SettingsProfilePhotoHistoryActions,
    current: () -> Boolean, canWrite: () -> Boolean, onSelect: (SettingsProfilePhotoHistoryEntry) -> Unit, onClear: () -> Unit,
) {
    val history = extras.history
    val identity = history.identity ?: return
    val latestExtras by rememberUpdatedState(extras)
    val window = LocalWindowInfo.current
    val focused = window.isWindowFocused
    // Use the fixed scroller viewport, not the selected content's scroll-clipped bounds.
    val viewportSize = LocalSettingsDetailViewportSize.current
    val viewportHeight = with(LocalDensity.current) { (viewportSize?.height ?: window.containerSize.height).toDp() }
    var contentViewport by remember(selection) { mutableStateOf(Rect.Zero) }
    val visibleOwner = current() && focused && selection.lease.active() && session.confirmation == null
    val index = history.entries.indexOf(entry)
    Column(Modifier.fillMaxWidth().padding(12.dp).onGloballyPositioned { contentViewport = it.boundsInWindow() },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val previewSide = minOf(216.dp, maxWidth, (viewportHeight - 8.dp).coerceAtLeast(0.dp))
            if (previewSide > 0.dp) Box(Modifier.size(previewSide).clip(CircleShape), contentAlignment = Alignment.Center) {
                ProfileHistoryImage(session, selection.epoch, entry, SettingsProfilePhotoHistoryMediaUse.EXPLICIT_PREVIEW,
                    actions, visibleOwner, Modifier.fillMaxSize(), "已选头像静态预览",
                    selection, valid = { current() && session.selection === selection && selection.lease.active() &&
                        session.confirmation == null && latestExtras.history.epoch == selection.epoch && entry in latestExtras.history.entries })
                val animation = entry.preferredAnimation()
                if (animation != null && usableProfileHistoryAnimation(entry, animation)) {
                    key(entry, animation) {
                        ProfileHistorySelectedAnimation(session, selection, entry, animation, actions, contentViewport,
                            visibleOwner, Modifier.fillMaxSize(), valid = { current() && session.selection === selection &&
                                selection.lease.active() && session.confirmation == null && latestExtras.history.epoch == selection.epoch &&
                                latestExtras.history.identity == selection.identity && !latestExtras.history.refreshRequired &&
                                latestExtras.history.entries.any { it == entry } })
                    }
                } else if (!entry.hasAnimation && entry.animation == null && entry.smallAnimation == null &&
                    !entry.invalidAnimation && !entry.invalidSmallAnimation && entry.hasSticker && !entry.invalidSticker) {
                    entry.sticker?.let { sticker ->
                        SavedProfileStickerAvatarPreview(
                            SettingsSavedProfileStickerAvatarRequest.History(session.parent, selection.epoch, entry.photoId),
                            sticker, session.lease, selection.lease, visibleOwner, contentViewport, selection.animationRevision,
                            own = selection::own, release = selection::release,
                            valid = { current() && session.selection === selection && selection.lease.active() &&
                                session.confirmation == null && latestExtras.history.epoch == selection.epoch &&
                                latestExtras.history.identity == selection.identity && !latestExtras.history.refreshRequired &&
                                latestExtras.history.entries.any { it == entry } }, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
        Text(profileHistoryDate(entry.addedDate))
        if (entry.preferredAnimation() == null || entry.preferredAnimation()?.let { !usableProfileHistoryAnimation(entry, it) } == true) {
            if (entry.hasAnimation || entry.invalidAnimation || entry.invalidSmallAnimation) SettingsNote("视频信息暂不可用，当前显示静态封面。")
            else if (entry.hasSticker && entry.invalidSticker) SettingsNote("表情头像信息暂不可用，当前显示静态封面。")
            else if (entry.hasSticker && entry.sticker == null) SettingsNote("表情头像动画暂不可播放，当前显示静态封面。")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChatButton("上一张", Modifier.weight(1f), enabled = index > 0,
                onClick = { if (current() && session.selection === selection && index > 0) onSelect(history.entries[index - 1]) })
            ChatButton("返回列表", Modifier.weight(1f), onClick = onClear)
            ChatButton("下一张", Modifier.weight(1f), enabled = index >= 0 && index < history.entries.lastIndex,
                onClick = { if (current() && session.selection === selection && index < history.entries.lastIndex) onSelect(history.entries[index + 1]) })
        }
        when {
            entry.photoId == identity.currentPhotoId -> SettingsNote("这是当前头像。如需删除，请返回头像设置，使用“删除当前头像”。")
            identity.isProtected(entry.photoId) -> SettingsNote("这张头像当前不可作为普通历史头像修改。")
            identity.isBot -> SettingsNote("此账号的历史头像仅供查看。")
            else -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                fun confirm(reuse: Boolean) {
                    if (canWrite() && session.selection === selection && selection.lease.active() &&
                        latestExtras.history.epoch == selection.epoch && latestExtras.history.identity == identity &&
                        !identity.isProtected(entry.photoId) && entry in latestExtras.history.entries) {
                        selection.suspendMedia()
                        session.confirmation?.lease?.close()
                        session.confirmation = ProfileHistoryUiConfirmation(session, selection,
                            SettingsProfilePhotoHistoryTarget(identity, entry.photoId, entry.rawOffset), reuse)
                    }
                }
                val reuseAction: () -> Unit = { confirm(true) }
                val removeAction: () -> Unit = { confirm(false) }
                val observer = LocalProfileHistorySelectionCallbacks.current
                val callbacks = ProfileHistorySelectionCallbacks(selection, reuseAction, removeAction)
                SideEffect { observer?.invoke(callbacks) }
                ChatButton("设为当前头像", Modifier.weight(1f), enabled = canWrite(), primary = true, onClick = reuseAction)
                ChatButton("删除这张头像", Modifier.weight(1f), enabled = canWrite(), onClick = removeAction)
            }
        }
    }
}

private fun usableProfileHistoryAnimation(entry: SettingsProfilePhotoHistoryEntry, value: SettingsProfilePhotoAnimation): Boolean {
    val file = value.file
    return value.length in 1..1280 && (entry.animation != null || value.length == 160) &&
        value.mainFrameTimestampMillis != null && file.id > 0 && file.uniqueId.length <= 1024 &&
        file.size in 0..(24L * 1024 * 1024) && file.expectedSize >= 0 && file.downloadedSize >= 0
}

/** Return true only for a nonempty intersection of the clipped preview, its content and window. */
private fun historyAnimationVisible(vararg rects: Rect): Boolean = rects.isNotEmpty() &&
    rects.minOf { it.right } > rects.maxOf { it.left } && rects.minOf { it.bottom } > rects.maxOf { it.top }

@Composable
private fun ProfileHistorySelectedAnimation(
    session: ProfileHistoryUiSession, selection: ProfileHistoryUiSelection, entry: SettingsProfilePhotoHistoryEntry,
    animation: SettingsProfilePhotoAnimation, actions: SettingsProfilePhotoHistoryActions, contentViewport: Rect,
    active: Boolean, modifier: Modifier, valid: () -> Boolean,
) {
    val scope = rememberCoroutineScope()
    val window = LocalWindowInfo.current
    val windowActivity = rememberProfileHistoryWindowActivity()
    val activityGate = remember(windowActivity) { ProfileHistoryAnimationActivity(windowActivity) }
    val activityState by activityGate.state.collectAsState()
    val activityRevision = activityState.revision
    DisposableEffect(activityGate) {
        val observer = windowActivity.observe(activityGate::inactive, activityGate::changed)
        onDispose { observer.close(); activityGate.close() }
    }
    val windowBounds = Rect(0f, 0f, window.containerSize.width.toFloat(), window.containerSize.height.toFloat())
    var bounds by remember(selection, entry, animation) { mutableStateOf(Rect.Zero) }
    var size by remember(selection, entry, animation) { mutableStateOf(IntSize.Zero) }
    var retry by remember(selection, entry, animation) { mutableStateOf(0) }
    var owner by remember(selection, entry, animation) { mutableStateOf<ProfileHistoryAnimationOwner?>(null) }
    var file by remember(selection, entry, animation) { mutableStateOf<MediaFile?>(null) }
    var failure by remember(selection, entry, animation) { mutableStateOf<Any?>(null) }
    val visible = active && activityGate.active() && historyAnimationVisible(bounds, contentViewport, windowBounds)
    val latestVisible by rememberUpdatedState(visible)
    val latestValid by rememberUpdatedState(valid)
    val latestActions by rememberUpdatedState(actions)
    val width = size.width.coerceIn(1, 4096); val height = size.height.coerceIn(1, 4096)
    val request = remember(session, selection, entry, animation, width, height, retry) {
        SettingsProfilePhotoHistoryMediaRequest(session.parent, selection.epoch, entry.photoId,
            SettingsProfilePhotoHistoryMediaUse.EXPLICIT_ANIMATION, width, height)
    }
    DisposableEffect(request, visible, retry, selection.animationRevision, activityGate, activityRevision) {
        file = null; failure = null
        if (visible && activityGate.active() && size.width > 0 && size.height > 0 && valid()) {
            val captured = ProfileHistoryAnimationOwner(session, selection, activityGate)
            selection.own(captured); activityGate.attach(captured); owner = captured
            val bound = CompletableDeferred<Boolean>()
            val delivered = MutableStateFlow(false)
            try {
                if (captured.active()) {
                    val lease = latestActions.acquireFile(request, captured::active) { result ->
                        scope.launch {
                            if (!bound.await() || !delivered.compareAndSet(false, true)) return@launch
                            if (owner !== captured || !captured.active() || !latestVisible || !latestValid()) return@launch
                            val ready = result.value?.takeIf { actual ->
                                result.error == null && actual.id == animation.file.id && actual.id > 0 &&
                                    actual.uniqueId.isNotBlank() && actual.uniqueId.length <= 1024 &&
                                    (animation.file.uniqueId.isBlank() || actual.uniqueId == animation.file.uniqueId) &&
                                    actual.size in 1..(24L * 1024 * 1024) &&
                                    (animation.file.size == 0L || actual.size == animation.file.size) &&
                                    actual.downloaded && !actual.localPath.isNullOrBlank()
                            }
                            if (ready == null) { captured.close(); failure = Any() } else file = ready
                        }
                    }
                    if (captured.bind(lease) && visible && valid()) bound.complete(true)
                    else { captured.close(); bound.complete(false) }
                } else bound.complete(false)
            } catch (_: Exception) {
                val current = captured.active() && latestVisible && latestValid()
                captured.close(); if (current) failure = Any(); bound.complete(false)
            }
            onDispose {
                bound.complete(false); captured.close(); activityGate.detach(captured); selection.release(captured)
                if (owner === captured) owner = null
            }
        } else onDispose { }
    }
    val captured = owner
    val shown = file.takeIf { visible && captured?.active() == true && valid() }
    Box(modifier.onSizeChanged { size = it }.onGloballyPositioned { bounds = it.boundsInWindow() }
        .semantics { contentDescription = "已选头像动画预览" }, contentAlignment = Alignment.Center) {
        if (shown != null) key(selection, captured, shown, animation.mainFrameTimestampMillis) {
            LocalProfileHistoryAnimationRenderer.current.Content(shown, checkNotNull(animation.mainFrameTimestampMillis),
                visible && captured?.active() == true && valid(), Modifier.fillMaxSize())
        }
        val marker = failure
        if (marker != null && visible) Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("视频暂不可用", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            ChatButton("重试视频", onClick = {
                if (failure === marker && owner === captured && latestVisible && latestValid() && selection.lease.active()) {
                    failure = null; retry++
                }
            })
        }
        else if (visible && shown == null && marker == null) Text("视频加载中", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

/** Only ready native files enter the existing worker-decoded, area-prepared raster pipeline. */
@Composable
private fun ProfileHistoryImage(
    session: ProfileHistoryUiSession, epoch: Long, entry: SettingsProfilePhotoHistoryEntry,
    use: SettingsProfilePhotoHistoryMediaUse, actions: SettingsProfilePhotoHistoryActions, active: Boolean,
    modifier: Modifier, description: String, selection: ProfileHistoryUiSelection? = null,
    valid: () -> Boolean,
) {
    val scope = rememberCoroutineScope()
    val selectionLease = selection?.lease
    var size by remember { mutableStateOf(IntSize.Zero) }
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val window = LocalWindowInfo.current
    // Only the selected static cover needs its own off-scene activity owner; grid ownership stays unchanged.
    val windowActivity = if (selectionLease != null) rememberProfileHistoryWindowActivity() else null
    val activityGate = remember(windowActivity) { windowActivity?.let(::ProfileHistoryAnimationActivity) }
    val activityState = activityGate?.state?.collectAsState()?.value
    val activityRevision = activityState?.revision ?: 0L
    val coverRevision = selection?.coverRevision ?: 0L
    if (activityGate != null && windowActivity != null) DisposableEffect(activityGate) {
        val observer = windowActivity.observe(activityGate::inactive, activityGate::changed)
        onDispose { observer.close(); activityGate.close() }
    }
    val windowBounds = Rect(0f, 0f, window.containerSize.width.toFloat(), window.containerSize.height.toFloat())
    val renderingActive = active && activityGate?.active() != false &&
        bounds.width > 0 && bounds.height > 0 && bounds.overlaps(windowBounds)
    var retry by remember(session, epoch, entry, use, selectionLease) { mutableStateOf(0) }
    var file by remember(session, epoch, entry, use, selectionLease) { mutableStateOf<MediaFile?>(null) }
    var failed by remember(session, epoch, entry, use, selectionLease) { mutableStateOf(false) }
    var decoded by remember(session, epoch, entry, use, selectionLease) { mutableStateOf(false) }
    val latestActive by rememberUpdatedState(renderingActive)
    val latestValid by rememberUpdatedState(valid)
    val latestActions by rememberUpdatedState(actions)
    val width = size.width.coerceIn(1, 4096)
    val height = size.height.coerceIn(1, 4096)
    val request = remember(session, epoch, entry.photoId, entry.rawOffset, entry.authorization, use, width, height, retry) {
        SettingsProfilePhotoHistoryMediaRequest(session.parent, epoch, entry.photoId, use, width, height)
    }
    var owner by remember { mutableStateOf<ProfileHistoryAnimationOwner?>(null) }
    DisposableEffect(request, entry, renderingActive, selectionLease, retry, activityGate, activityRevision, coverRevision) {
        file = null; failed = false; decoded = false
        if (renderingActive && size.width > 0 && size.height > 0 && session.lease.active() && valid()) {
            val captured = ProfileHistoryAnimationOwner(session, selectionLease, activityGate)
            selection?.ownCover(captured); activityGate?.attach(captured); owner = captured
            val bound = CompletableDeferred<Boolean>()
            val delivered = MutableStateFlow(false)
            try {
                if (captured.active()) {
                    val lease = latestActions.acquireFile(request, captured::active) { result ->
                        scope.launch {
                            if (!bound.await() || !delivered.compareAndSet(false, true)) return@launch
                            if (owner === captured && captured.active() && latestActive && latestValid()) {
                                val ready = result.value?.takeIf { result.error == null && it.id > 0 && it.uniqueId.isNotBlank() &&
                                    it.downloaded && !it.localPath.isNullOrBlank() }
                                file = ready; failed = ready == null
                            }
                        }
                    }
                    if (captured.bind(lease) && renderingActive && valid()) bound.complete(true)
                    else { captured.close(); bound.complete(false) }
                } else bound.complete(false)
            } catch (_: Exception) {
                if (captured.active() && latestActive && latestValid()) failed = true
                bound.complete(false)
            }
            onDispose {
                bound.complete(false); captured.close(); activityGate?.detach(captured); selection?.releaseCover(captured)
                if (owner === captured) owner = null
            }
        } else onDispose {}
    }
    val shown = file.takeIf { renderingActive && owner?.active() == true }
    Box(modifier.clip(CircleShape).background(MiuixTheme.colorScheme.surfaceContainer)
        .onSizeChanged { size = it }.onGloballyPositioned { bounds = it.boundsInWindow() }
        .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        val captured = owner
        if (shown != null) key(captured, shown) { MediaPreview(shown.localPath!!, MediaFormat.PHOTO, Modifier.fillMaxSize(),
            contentDescription = if (decoded) "$description 已载入" else description, animate = false,
            contentScale = ContentScale.Crop, onLoadResult = { success ->
                if (latestActive && latestValid() && captured?.active() == true && owner === captured && file === shown) {
                    decoded = success; if (!success) failed = true
                }
            }) }
        if (failed) ChatButton("重试图片", enabled = renderingActive,
            onClick = { if (latestActive && latestValid() && owner === captured && captured?.active() == true) retry++ })
        else if (shown == null) Text(if (renderingActive) "读取中" else "头像", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

private fun profileHistoryType(entry: SettingsProfilePhotoHistoryEntry) = when {
    entry.hasAnimation && entry.hasSticker -> "视频 · 表情头像"
    entry.hasAnimation -> "视频头像"
    else -> "表情头像"
}

/** Native epoch seconds are formatted in the desktop's actual local timezone. */
internal expect fun profileHistoryDate(seconds: Int): String
