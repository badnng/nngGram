package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.MediaFormat
import com.badnng.moe.chat.AttachmentLease
import com.badnng.moe.media.*
import com.badnng.moe.settings.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToLong
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal interface ProfileVideoEditorServices {
    suspend fun read(path: String): ProfilePhotoVideoSource
    suspend fun prepare(source: ProfilePhotoVideoSource, edit: ProfilePhotoVideoEdit): SettingsPreparedProfileAnimation
    suspend fun cover(animation: SettingsPreparedProfileAnimation): SettingsPreparedProfilePhoto
}
private object RealProfileVideoEditorServices : ProfileVideoEditorServices {
    override suspend fun read(path: String) = readProfilePhotoVideoSource(path)
    override suspend fun prepare(source: ProfilePhotoVideoSource, edit: ProfilePhotoVideoEdit) = prepareProfilePhotoVideo(source, edit)
    override suspend fun cover(animation: SettingsPreparedProfileAnimation) = prepareProfilePhotoVideoCover(animation)
}
internal val LocalProfileVideoEditorServices = staticCompositionLocalOf<ProfileVideoEditorServices> { RealProfileVideoEditorServices }

internal class ProfileVideoPrepared(
    val sourceRevision: Long, val editRevision: Long,
    val edit: ProfilePhotoVideoEdit, val animation: SettingsPreparedProfileAnimation, val cover: SettingsPreparedProfilePhoto,
) : AutoCloseable {
    private val alive = AtomicBoolean(true)
    fun active() = alive.get()
    override fun close() { if (alive.compareAndSet(true, false)) { cover.lease.release(); animation.lease.release() } }
}
internal class ProfileVideoConfirmation(val prepared: ProfileVideoPrepared) {
    private val alive = AtomicBoolean(true)
    fun active() = alive.get()
    fun close() { alive.set(false) }
}
internal class ProfileVideoSubmission(val confirmation: ProfileVideoConfirmation) {
    private val callbackOnce = AtomicBoolean()
    var admittedRevision by mutableStateOf<Long?>(null)
    fun acceptCallback() = callbackOnce.compareAndSet(false, true)
}

internal data class ProfileVideoDraft(
    val crop: ProfilePhotoCrop = ProfilePhotoCrop(), val from: String = "0", val till: String = "9.6", val cover: String = "0",
) {
    fun edit(source: ProfilePhotoVideoSource): ProfilePhotoVideoEdit? {
        fun millis(value: String) = value.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 && it <= Long.MAX_VALUE / 1000.0 }
            ?.let { (it * 1000).roundToLong() }
        val start = millis(from) ?: return null
        val end = millis(till) ?: return null
        val selectedCover = millis(cover) ?: return null
        return ProfilePhotoVideoEdit(crop, start, end, selectedCover).takeIf { ProfilePhotoVideoPreparation.validEdit(source, it) }
    }
}

/** All mutable UI fields are touched on the UI context. Workers own their own retained input handles. */
internal class ProfileVideoEditor(val opening: SettingsProfileVideoOpening) : AutoCloseable {
    private val alive = AtomicBoolean(true)
    var source by mutableStateOf<ProfilePhotoVideoSource?>(null); private set
    var sourceRevision by mutableStateOf(0L); private set
    var editRevision by mutableStateOf(0L); private set
    var draft by mutableStateOf(ProfileVideoDraft()); private set
    var prepared by mutableStateOf<ProfileVideoPrepared?>(null); private set
    var confirmation by mutableStateOf<ProfileVideoConfirmation?>(null); private set
    var reading by mutableStateOf(false); private set
    var preparing by mutableStateOf(false); private set
    var submitting by mutableStateOf(false); private set
    var submission by mutableStateOf<ProfileVideoSubmission?>(null); private set
    var admissionRejected by mutableStateOf(false); private set
    var lastAdmittedRevision: Long? = null; private set
    var playing by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    private var revision = 0L
    private var job: Job? = null
    fun active() = alive.get() && opening.lease.active()
    private fun invalidate() {
        ++revision; job?.cancel(); job = null; preparing = false; playing = false
        confirmation?.close(); confirmation = null; prepared?.close(); prepared = null
    }
    fun change(next: ProfileVideoDraft) {
        if (!active() || submitting || reading || next == draft) return
        invalidate(); ++editRevision; draft = next; error = null
    }
    fun cancelConfirmation(expected: ProfileVideoConfirmation) {
        if (confirmation === expected) { expected.close(); confirmation = null }
    }
    fun confirmPrepared() {
        val value = prepared ?: return
        if (active() && !submitting && !reading && !preparing && value.active() &&
            value.sourceRevision == sourceRevision && value.editRevision == editRevision) {
            confirmation?.close(); confirmation = ProfileVideoConfirmation(value); playing = false
        }
    }
    fun play(value: Boolean) { if (active() && !submitting && prepared?.active() == true) playing = value }
    fun markSubmitted(expected: ProfileVideoConfirmation): ProfileVideoSubmission? {
        if (!active() || submitting || confirmation !== expected || !expected.active() ||
            prepared !== expected.prepared || !expected.prepared.active() ||
            expected.prepared.sourceRevision != sourceRevision || expected.prepared.editRevision != editRevision) return null
        submitting = true; playing = false; admissionRejected = false
        return ProfileVideoSubmission(expected).also { submission = it }
    }
    fun admitted(expected: ProfileVideoSubmission, revision: Long) {
        if (active() && submission === expected) { expected.admittedRevision = revision; lastAdmittedRevision = revision }
    }
    fun admissionFailed(expected: ProfileVideoSubmission) {
        if (active() && submission === expected && confirmation === expected.confirmation && prepared === expected.confirmation.prepared) {
            submitting = false; submission = null; admissionRejected = true
        }
    }
    fun submissionFailed() { if (active()) { submitting = false; submission = null } }
    fun choose(scope: CoroutineScope, picker: suspend () -> String?, services: ProfileVideoEditorServices,
        current: () -> Boolean, dismiss: () -> Unit,
    ) {
        if (!current() || submitting || reading) return
        ++revision; job?.cancel(); playing = false; reading = true; preparing = false; error = null
        confirmation?.close(); confirmation = null
        val requested = revision
        job = scope.launch {
            var acquired: ProfilePhotoVideoSource? = null
            try {
                val path = picker()
                if (path == null) { if (source == null && current() && requested == revision) dismiss(); return@launch }
                acquired = services.read(path)
                if (current() && requested == revision) {
                    prepared?.close(); prepared = null; source?.close(); source = acquired; acquired = null
                    ++sourceRevision; ++editRevision
                    draft = ProfileVideoDraft(till = videoSeconds(minOf(9600, checkNotNull(source).durationMillis)))
                    reading = false
                    prepare(scope, services, current)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (current() && requested == revision) error = videoEditorFailure(failure, "无法读取视频，请重试或选择其他视频。") }
            finally { acquired?.close(); if (active() && requested == revision) reading = false }
        }
    }
    fun prepare(scope: CoroutineScope, services: ProfileVideoEditorServices, current: () -> Boolean) {
        val input = source ?: return
        val edit = draft.edit(input) ?: return
        if (!current() || submitting || reading) return
        invalidate(); preparing = true; error = null
        val requested = revision
        val sourceVersion = sourceRevision
        val editVersion = editRevision
        job = scope.launch {
            var animation: SettingsPreparedProfileAnimation? = null
            var cover: SettingsPreparedProfilePhoto? = null
            try {
                animation = services.prepare(input, edit)
                if (!current() || requested != revision) return@launch
                cover = services.cover(checkNotNull(animation))
                if (current() && requested == revision && source === input && sourceRevision == sourceVersion && editRevision == editVersion) {
                    prepared = ProfileVideoPrepared(sourceVersion, editVersion, edit, checkNotNull(animation), checkNotNull(cover))
                    animation = null; cover = null
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (current() && requested == revision) error = videoEditorFailure(failure, "视频预览未能生成，所选视频和编辑已保留，请重试。") }
            finally {
                cover?.lease?.release(); animation?.lease?.release()
                if (active() && requested == revision) preparing = false
            }
        }
    }
    override fun close() {
        if (alive.compareAndSet(true, false)) {
            invalidate(); source?.close(); source = null; reading = false
        }
    }
}

internal interface ProfileVideoPreviewHost {
    @Composable fun Content(prepared: ProfileVideoPrepared, playing: Boolean, modifier: Modifier)
}
private object RealProfileVideoPreviewHost : ProfileVideoPreviewHost {
    @Composable override fun Content(prepared: ProfileVideoPrepared, playing: Boolean, modifier: Modifier) {
        key(prepared) {
            Box(modifier) {
                MediaPreview(prepared.cover.path, MediaFormat.PHOTO, Modifier.fillMaxSize(), "选中的视频封面", animate = false)
                if (playing) AnimationPlayback(prepared.animation.path, true, Modifier.fillMaxSize(), streamSize = prepared.animation.size)
            }
        }
    }
}
internal val LocalProfileVideoPreviewHost = staticCompositionLocalOf<ProfileVideoPreviewHost> { RealProfileVideoPreviewHost }

/** Preview outlives synchronous edit invalidation until its actual child holders have been disposed. */
private class ProfileVideoPreviewLeases(private val animation: AttachmentLease, private val cover: AttachmentLease) : RememberObserver {
    private val alive = AtomicBoolean(true)
    fun close() { if (alive.compareAndSet(true, false)) { cover.release(); animation.release() } }
    override fun onRemembered() = Unit
    override fun onForgotten() = close()
    override fun onAbandoned() = close()
    companion object {
        fun retain(prepared: ProfileVideoPrepared): ProfileVideoPreviewLeases? {
            if (!prepared.active()) return null
            val animation = prepared.animation.lease.retain() ?: return null
            val cover = prepared.cover.lease.retain() ?: run { animation.release(); return null }
            return ProfileVideoPreviewLeases(animation, cover)
        }
    }
}

@Composable
private fun RetainedVideoPreview(prepared: ProfileVideoPrepared, host: ProfileVideoPreviewHost, playing: Boolean, modifier: Modifier) {
    key(prepared) {
        val retained = remember { ProfileVideoPreviewLeases.retain(prepared) }
        // Declared before the child. Compose forgets in reverse order: child MediaHolder/player
        // closes first, then this retained file owner releases. Abandoned compositions also clean up.
        DisposableEffect(retained) { onDispose { retained?.close() } }
        if (retained != null) host.Content(prepared, playing, modifier)
    }
}

/** Exact app callbacks permit disposal ownership tests without invoking a detached toolkit click wrapper. */
internal class ProfileVideoUiProbe(
    val editor: ProfileVideoEditor, val dismiss: () -> Unit, val submit: () -> Unit,
    val contentViewport: Rect, val actionsViewport: Rect, val previewBounds: Rect,
)
internal val LocalProfileVideoUiProbe = staticCompositionLocalOf<((ProfileVideoUiProbe) -> Unit)?> { null }

@Composable
internal actual fun SettingsProfileVideoPicker(
    opening: SettingsProfileVideoOpening?, snapshot: SettingsProfileExtrasSnapshot, enabled: Boolean,
    onDismiss: (SettingsProfileVideoOpening) -> Unit,
    onUpload: (SettingsProfileVideoOpening, SettingsPreparedProfileAnimation, (Long?) -> Unit) -> Unit,
) {
    if (opening == null) return
    key(opening) {
        val editor = remember { ProfileVideoEditor(opening) }
        var contentViewport by remember { mutableStateOf(Rect.Zero) }
        var actionsViewport by remember { mutableStateOf(Rect.Zero) }
        var previewBounds by remember { mutableStateOf(Rect.Zero) }
        val scope = rememberCoroutineScope()
        val services = LocalProfileVideoEditorServices.current
        val preview = LocalProfileVideoPreviewHost.current
        val picker = LocalNativeFilePicker.current
        val dialogOwner = LocalNativeDialogOwner.current
        val pageActive = LocalSettingsPageActive.current
        val latestSnapshot by rememberUpdatedState(snapshot)
        val latestEnabled by rememberUpdatedState(enabled)
        val latestPageActive by rememberUpdatedState(pageActive)
        val latestDismiss by rememberUpdatedState(onDismiss)
        val latestUpload by rememberUpdatedState(onUpload)
        fun current() = editor.active() && latestEnabled && latestPageActive && latestSnapshot.token == opening.parent
        fun editable() = current() && !latestSnapshot.busy && !latestSnapshot.loading && latestSnapshot.data != null &&
            !editor.submitting && !editor.reading
        fun photoChanged() = latestSnapshot.data?.let { it.photoId != opening.expectedPhotoId } == true
        fun ownConflict() = photoChanged() || latestSnapshot.operation == SettingsProfileExtrasOperation.UPLOAD_ANIMATION &&
            latestSnapshot.error == SettingsError.CONFLICT && editor.lastAdmittedRevision?.let {
                latestSnapshot.failedRevision == it
            } == true
        val dismiss: () -> Unit = { editor.close(); opening.close(); latestDismiss(opening) }
        DisposableEffect(editor) { onDispose { editor.close(); opening.close() } }
        LaunchedEffect(Unit) {
            editor.choose(scope, {
                opening.initialPath ?: picker("选择视频头像", profileAvatarVideoExtensions, dialogOwner)
            }, services, ::current, dismiss)
        }
        LaunchedEffect(pageActive, snapshot.token, enabled) { if (!current()) dismiss() }
        val submission = editor.submission
        val admittedRevision = submission?.admittedRevision
        LaunchedEffect(snapshot.acceptedRevision, snapshot.failedRevision, submission, admittedRevision) {
            if (submission != null && admittedRevision != null && snapshot.token == opening.parent && editor.submission === submission) {
                if (snapshot.acceptedRevision == admittedRevision) dismiss()
                else if (snapshot.failedRevision == admittedRevision) {
                    opening.submissionPending = false; editor.submissionFailed()
                }
            }
        }
        val frozen = editor.confirmation
        val submit: () -> Unit = {
            if (frozen != null && editable() && editor.error == null && !ownConflict() && !opening.submissionPending) {
                val request = editor.markSubmitted(frozen)
                if (request != null) latestUpload(opening, frozen.prepared.animation) { revision ->
                    if (request.acceptCallback()) scope.launch {
                        if (current() && editor.submission === request && editor.confirmation === frozen &&
                            editor.prepared === frozen.prepared && frozen.active() && frozen.prepared.active()) {
                            if (revision == null) {
                                opening.submissionPending = false
                                editor.admissionFailed(request)
                            } else editor.admitted(request, revision)
                        }
                    }
                }
            }
        }
        val observer = LocalProfileVideoUiProbe.current
        // Subscribe to actual measured geometry only when an offline observer is installed.
        // SideEffect runs after composition; state reads inside it do not subscribe this scope.
        val probePayload = if (observer != null)
            ProfileVideoUiProbe(editor, dismiss, submit, contentViewport, actionsViewport, previewBounds) else null
        SideEffect { if (probePayload != null) observer?.invoke(probePayload) }
        LocalSettingsSecurityConfirmationHost.current.Content(true,
            if (frozen == null) "设置视频头像" else "上传视频头像？", null, 520.dp, dismiss) {
            ProfileExtrasDialogBody(content = {
                val source = editor.source
                if (editor.reading) SettingsNote("正在读取视频……")
                if (source != null) {
                    SettingsNote("原视频 ${source.width} × ${source.height} · 时长 ${videoSeconds(source.durationMillis)} 秒")
                    VideoCropPreview(editor, preview, contentViewport, current = current(), editable = editable(), onBounds = { previewBounds = it })
                    if (frozen == null) {
                        VideoSlider("缩放", editor.draft.crop.zoom, 1f..4f, editable()) { editor.change(editor.draft.copy(crop = editor.draft.crop.copy(zoom = it))) }
                        VideoSlider("水平位置", editor.draft.crop.x, 0f..1f, editable()) { editor.change(editor.draft.copy(crop = editor.draft.crop.copy(x = it))) }
                        VideoSlider("垂直位置", editor.draft.crop.y, 0f..1f, editable()) { editor.change(editor.draft.copy(crop = editor.draft.crop.copy(y = it))) }
                        VideoTimeField("片段起点（秒）", editor.draft.from, editable()) { editor.change(editor.draft.copy(from = it)) }
                        VideoTimeField("片段终点（秒）", editor.draft.till, editable()) { editor.change(editor.draft.copy(till = it)) }
                        VideoTimeField("封面位置（秒）", editor.draft.cover, editable()) { editor.change(editor.draft.copy(cover = it)) }
                        val valid = editor.draft.edit(source)
                        if (valid == null) SettingsNote("片段需为 1–9.6 秒，且封面必须位于片段内。", error = true)
                        ChatButton("生成预览", Modifier.fillMaxWidth(), enabled = editable() && !editor.preparing && valid != null,
                            onClick = { if (editable()) editor.prepare(scope, services, ::current) })
                    } else {
                        val chosen = frozen.prepared
                        SettingsNote("成片 ${videoSeconds(chosen.animation.durationMillis)} 秒 · 原片段 ${videoSeconds(chosen.edit.fromMillis)}–" +
                            "${videoSeconds(chosen.edit.tillMillis)} 秒 · 封面在成片 ${videoSeconds((chosen.animation.mainFrameTimestampSeconds * 1000).roundToLong())} 秒")
                        SettingsNote("将使用此封面和已预览的片段设置视频头像。")
                    }
                }
                if (frozen == null) ChatButton("选择其他视频", enabled = editable(), onClick = {
                    if (editable()) editor.choose(scope,
                        { picker("选择视频头像", profileAvatarVideoExtensions, dialogOwner) }, services, ::current, dismiss)
                })
                if (editor.preparing) SettingsNote("正在生成视频和封面……")
                editor.error?.let { SettingsNote(it, error = true) }
                if (editor.admissionRejected) SettingsNote("本次上传未开始，请重试。", error = true)
                if (photoChanged()) SettingsNote("当前头像已更改，请关闭后重新选择。", error = true)
                if (snapshot.error != null && snapshot.operation == SettingsProfileExtrasOperation.UPLOAD_ANIMATION &&
                    editor.lastAdmittedRevision == snapshot.failedRevision && !opening.submissionPending)
                    SettingsNote(when (snapshot.error) {
                        SettingsError.CONFLICT -> "当前头像已更改，请关闭后重新选择。"
                        SettingsError.RATE_LIMITED -> "更换过于频繁，请稍后重试。"
                        SettingsError.FORBIDDEN -> "此账号暂时无法设置视频头像。"
                        else -> "视频头像未上传成功，编辑已保留，可明确重试。"
                    }, error = true)
                if (editor.submitting || snapshot.busy) SettingsNote("正在上传，关闭窗口不会撤回已提交的头像。")
            }, onContentViewport = { contentViewport = it }, onActionsViewport = { actionsViewport = it }) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChatButton(if (editor.submitting || snapshot.busy) "关闭" else "取消", Modifier.weight(1f), onClick = dismiss)
                    if (frozen == null) ChatButton(if (editor.playing) "暂停预览" else "静音播放", Modifier.weight(1f),
                        enabled = editable() && editor.prepared?.active() == true,
                        onClick = { if (editable()) editor.play(!editor.playing) })
                    if (frozen != null && !editor.submitting) ChatButton("返回编辑", Modifier.weight(1f), onClick = { editor.cancelConfirmation(frozen) })
                    ChatButton(if (frozen == null) "上传视频头像" else "确认上传", Modifier.weight(1f), primary = true,
                        enabled = editable() && !editor.preparing && editor.error == null && editor.prepared?.active() == true && !ownConflict(),
                        onClick = if (frozen == null) { { if (editable()) editor.confirmPrepared() } } else submit)
                }
            }
        }
    }
}

@Composable
private fun VideoCropPreview(
    editor: ProfileVideoEditor, host: ProfileVideoPreviewHost, contentViewport: Rect, current: Boolean, editable: Boolean,
    onBounds: (Rect) -> Unit,
) {
    val window = LocalWindowInfo.current
    val density = LocalDensity.current
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val latestEditable by rememberUpdatedState(editable)
    val visible = bounds.width > 0 && bounds.height > 0 && contentViewport.width > 0 && contentViewport.height > 0 &&
        bounds.overlaps(contentViewport) && bounds.overlaps(Rect(0f, 0f,
            window.containerSize.width.toFloat(), window.containerSize.height.toFloat()))
    val rendered = current && visible && window.isWindowFocused
    val prepared = editor.prepared
    val side = with(density) {
        if (contentViewport.width <= 0 || contentViewport.height <= 0) 0.dp
        else minOf(240.dp, (contentViewport.height.toDp() - 8.dp).coerceAtLeast(0.dp), contentViewport.width.toDp())
    }
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        if (side > 0.dp) Box(Modifier.size(side).clip(RectangleShape)
            .background(MiuixTheme.colorScheme.surfaceContainer).onGloballyPositioned {
                val origin = it.localToWindow(Offset.Zero)
                bounds = Rect(origin.x, origin.y, origin.x + it.size.width, origin.y + it.size.height)
                onBounds(bounds)
            }
            .semantics { contentDescription = "视频头像正方形裁切区域" }
            .pointerInput(editor, editor.sourceRevision) {
                detectDragGestures { change, drag ->
                    if (latestEditable && editor.confirmation == null) {
                        change.consume()
                        val crop = editor.draft.crop
                        editor.change(editor.draft.copy(crop = crop.copy(
                            x = (crop.x - drag.x / size.width.coerceAtLeast(1) / crop.zoom).coerceIn(0f, 1f),
                            y = (crop.y - drag.y / size.height.coerceAtLeast(1) / crop.zoom).coerceIn(0f, 1f))))
                    }
                }
            }, contentAlignment = Alignment.Center) {
            if (prepared != null && prepared.active() && rendered) RetainedVideoPreview(prepared, host, editor.playing, Modifier.fillMaxSize())
            else Text(if (editor.preparing) "正在生成预览……" else "调整裁切后生成预览")
        }
    }
}

@Composable
private fun VideoTimeField(label: String, value: String, enabled: Boolean, change: (String) -> Unit) {
    TextField(value, onValueChange = { if (enabled) change(it) }, label = label, enabled = enabled, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth()
            .semantics { contentDescription = "视频头像$label" })
}

@Composable
private fun VideoSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, enabled: Boolean,
    onChange: (Float) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    Text(label)
    Slider(value, onValueChange = { if (enabled && it.isFinite()) onChange(it.coerceIn(range)) }, enabled = enabled, valueRange = range,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "视频头像$label" }
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                if (!enabled || event.key != Key.DirectionLeft && event.key != Key.DirectionRight) false
                else {
                    if (event.type == KeyEventType.KeyDown) onChange((value +
                        (if (event.key == Key.DirectionLeft) -1 else 1) * (range.endInclusive - range.start) / 30).coerceIn(range))
                    true
                }
            }.focusable(enabled).pointerInput(enabled) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (enabled && event.type == PointerEventType.Press) focusRequester.requestFocus()
                    }
                }
            })
}

private fun videoSeconds(millis: Long) = String.format(Locale.ROOT, "%.3f", millis / 1000.0).trimEnd('0').trimEnd('.')

private fun videoEditorFailure(failure: Exception, fallback: String) =
    if (failure is ProfilePhotoVideoFailure && failure.reason == ProfilePhotoVideoFailure.Reason.TOOLS_MISSING)
        "缺少视频处理组件，暂时无法编辑视频头像。" else fallback
