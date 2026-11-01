package com.badnng.moe

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.MediaFormat
import com.badnng.moe.media.*
import com.badnng.moe.settings.SettingsError
import com.badnng.moe.settings.SettingsPreparedProfilePhoto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.atomic.AtomicBoolean
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text

/** Each accepted worker result has one private preview handle; a late worker cannot revive a disposed dialog. */
internal class ProfilePhotoPreviewOwner : AutoCloseable {
    private var value: SettingsPreparedProfilePhoto? = null
    private var closed = false
    @Synchronized fun replace(next: SettingsPreparedProfilePhoto?): Boolean {
        if (closed) { next?.lease?.release(); return false }
        if (next !== value) value?.lease?.release()
        value = next
        return true
    }
    @Synchronized override fun close() { if (!closed) { closed = true; value?.lease?.release(); value = null } }
}

internal actual fun settingsProfileExtrasPageLease(): SettingsProfileExtrasPageLease = object : SettingsProfileExtrasPageLease {
    private val alive = AtomicBoolean(true)
    override fun active() = alive.get()
    override fun close() { alive.set(false) }
}

@Composable
internal actual fun SettingsProfilePhotoPicker(show: Boolean, busy: Boolean, accepted: Boolean, error: SettingsError?,
    onDismiss: () -> Unit, onUpload: (SettingsPreparedProfilePhoto) -> Unit,
    onVideo: ((String) -> Unit)?,
) {
    if (!show) return
    var source by remember { mutableStateOf<ProfilePhotoSource?>(null) }
    var crop by remember { mutableStateOf(ProfilePhotoCrop()) }
    var prepared by remember { mutableStateOf<SettingsPreparedProfilePhoto?>(null) }
    var preparedSource by remember { mutableStateOf<ProfilePhotoSource?>(null) }
    var preparedCrop by remember { mutableStateOf<ProfilePhotoCrop?>(null) }
    var selecting by remember { mutableStateOf(true) }
    var preparing by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    var preparationError by remember { mutableStateOf<String?>(null) }
    val owner = remember { ProfilePhotoPreviewOwner() }
    DisposableEffect(owner) { onDispose { owner.close() } }
    val dismiss by rememberUpdatedState(onDismiss)
    val video by rememberUpdatedState(onVideo)
    val selectionOwner = remember { ProfileAvatarSelectionOwner() }
    DisposableEffect(selectionOwner) { onDispose { selectionOwner.close() } }
    val picker = LocalNativeFilePicker.current
    val dialogOwner = LocalNativeDialogOwner.current
    LaunchedEffect(attempt) {
        val request = selectionOwner.begin()
        selecting = true; preparationError = null
        try {
            val selected = picker("选择个人头像", if (video != null) profileAvatarMediaExtensions else
                setOf("jpg", "jpeg", "png", "webp"), dialogOwner)
            currentCoroutineContext().ensureActive()
            if (!selectionOwner.current(request)) return@LaunchedEffect
            if (selected == null) { if (source == null) dismiss() }
            else {
                val selection = if (video != null) readProfileAvatarSelection(selected)
                    else ProfileAvatarSelection.Photo(readProfilePhotoSource(selected))
                currentCoroutineContext().ensureActive()
                if (!selectionOwner.current(request)) return@LaunchedEffect
                when (selection) {
                    is ProfileAvatarSelection.Photo -> { source = selection.source; crop = ProfilePhotoCrop() }
                    is ProfileAvatarSelection.Video -> video?.invoke(selection.path)
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { preparationError = if (video != null)
            "无法读取所选头像。图片需在 10 MB 内、至少 16 × 16 且总像素不超过 2400 万；也可选择 MP4、MOV 或 M4V 视频。"
            else "无法读取图片。请选择 10 MB 内的静态 JPG、PNG 或 WebP，至少 16 × 16 且总像素不超过 2400 万。" }
        finally { selecting = false }
    }
    LaunchedEffect(source, crop) {
        val current = source ?: return@LaunchedEffect
        val requestedCrop = crop
        preparing = true
        preparationError = null
        try {
            delay(100) // Coalesce slider changes before bounded worker preparation; no idle frame loop.
            val next = prepareProfilePhoto(current, requestedCrop)
            if (owner.replace(next)) { prepared = next; preparedSource = current; preparedCrop = requestedCrop }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { preparationError = "图片无法解码或裁切，请重新选择图片。" }
        finally { preparing = false }
    }
    LaunchedEffect(accepted) { if (accepted) dismiss() }
    LocalSettingsSecurityConfirmationHost.current.Content(!selecting, "设置头像", null, 460.dp, onDismiss) {
        ProfileExtrasDialogBody(content = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                prepared?.let { photo ->
                    MediaPreview(photo.path, MediaFormat.PHOTO, Modifier.widthIn(max = 220.dp).fillMaxWidth()
                        .aspectRatio(1f).clip(CircleShape), "头像裁切预览", animate = false, highQuality = true)
                }
                if (preparing) SettingsNote("正在准备头像预览……")
                SettingsNote(if (video != null && source == null) "选择图片可裁切头像；选择视频将进入视频头像编辑器，确认后才会上传。"
                    else "头像会裁切为正方形；圆形预览展示个人资料中的可见范围。")
                if (source?.animated == true) SettingsNote("此动图将使用首帧作为图片头像。")
            }
            source?.let {
                ProfileCropSlider("缩放", crop.zoom, 1f..4f, enabled = !busy && !selecting) { value -> crop = crop.copy(zoom = value) }
                ProfileCropSlider("水平位置", crop.x, 0f..1f, enabled = !busy && !selecting) { value -> crop = crop.copy(x = value) }
                ProfileCropSlider("垂直位置", crop.y, 0f..1f, enabled = !busy && !selecting) { value -> crop = crop.copy(y = value) }
            }
            preparationError?.let { SettingsNote(it, error = true) }
            if (error != null) SettingsNote(when (error) {
                SettingsError.CONFLICT -> "当前头像已更改，请关闭后刷新并重新确认。"
                SettingsError.RATE_LIMITED -> "更换头像过于频繁，请稍后重试。"
                SettingsError.FORBIDDEN -> "此账号暂时无法设置头像。"
                else -> "头像未上传成功，裁切结果已保留，可重试。"
            }, error = true)
            if (busy) SettingsNote("正在上传，关闭此窗口不会撤回已提交的头像。")
            ChatButton(if (video != null) "选择其他图片或视频" else "选择其他图片", enabled = !busy && !selecting && !preparing) { attempt++ }
        }) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ChatButton(if (busy) "关闭" else "取消", Modifier.weight(1f), onClick = onDismiss)
                ChatButton(if (busy) "正在上传……" else "上传头像", Modifier.weight(1f),
                    enabled = prepared != null && preparedSource === source && preparedCrop == crop && !busy && !preparing &&
                        !selecting && preparationError == null && error != SettingsError.CONFLICT, primary = true,
                    onClick = { if (preparedSource === source && preparedCrop == crop) prepared?.let(onUpload) })
            }
        }
    }
}

@Composable
private fun ProfileCropSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, enabled: Boolean,
    onValueChange: (Float) -> Unit,
) {
    Text(label)
    Slider(value, onValueChange = { if (enabled && it.isFinite()) onValueChange(it.coerceIn(range)) },
        valueRange = range, enabled = enabled,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "头像$label" }.onPreviewKeyEvent { event ->
            if (!enabled || event.key != Key.DirectionLeft && event.key != Key.DirectionRight) false
            else {
                if (event.type == KeyEventType.KeyDown) {
                    val step = (range.endInclusive - range.start) / 30f
                    onValueChange((value + if (event.key == Key.DirectionLeft) -step else step).coerceIn(range))
                }
                true
            }
        }.focusable(enabled))
}
