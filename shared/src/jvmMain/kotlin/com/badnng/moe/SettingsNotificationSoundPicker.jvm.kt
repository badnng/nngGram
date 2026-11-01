package com.badnng.moe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.badnng.moe.media.LocalNativeDialogOwner
import com.badnng.moe.media.LocalNativeFilePicker
import com.badnng.moe.media.prepareNotificationSoundUpload
import com.badnng.moe.settings.NotificationSoundLimits
import com.badnng.moe.settings.SettingsError
import com.badnng.moe.settings.SettingsPreparedNotificationSound
import com.badnng.moe.settings.SettingsResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.atomic.AtomicBoolean

internal val LocalNotificationSoundPreparation = staticCompositionLocalOf<
    suspend (String, NotificationSoundLimits, () -> Boolean) -> SettingsResult<SettingsPreparedNotificationSound>
> { ::prepareNotificationSoundUpload }

/** onPrepared takes the original lease only on a successful return; closing keeps no borrowed native owner. */
@Composable
internal actual fun SettingsNotificationSoundPicker(show: Boolean, limits: NotificationSoundLimits,
    onDismiss: () -> Unit, onPrepared: (SettingsPreparedNotificationSound) -> Unit,
) {
    if (!show) return
    val pageActive = LocalSettingsPageActive.current
    val alive = remember { AtomicBoolean(true) }
    val validity = remember(limits, pageActive) { AtomicBoolean(limits.known && pageActive) }
    DisposableEffect(alive) { onDispose { alive.set(false) } }
    DisposableEffect(validity) { onDispose { validity.set(false) } }
    var attempt by remember { mutableStateOf(0) }
    var selecting by remember { mutableStateOf(limits.known && pageActive) }
    var preparing by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<SettingsError?>(null) }
    val picker = LocalNativeFilePicker.current
    val dialogOwner = LocalNativeDialogOwner.current
    val prepare = LocalNotificationSoundPreparation.current
    val dismiss = { alive.set(false); onDismiss() }
    LaunchedEffect(attempt) {
        val local = AtomicBoolean(true)
        val active = { alive.get() && validity.get() && local.get() }
        var pending: SettingsPreparedNotificationSound? = null
        try {
            failure = null
            if (!active()) { failure = SettingsError.NOT_READY; return@LaunchedEffect }
            selecting = true
            val selected = picker("选择通知铃声", setOf("mp3", "mpeg3", "ogg", "oga"), dialogOwner)
            selecting = false
            currentCoroutineContext().ensureActive()
            if (!active()) { failure = SettingsError.NOT_READY; return@LaunchedEffect }
            if (selected == null) { dismiss(); return@LaunchedEffect }
            preparing = true
            val result = prepare(selected, limits, active)
            pending = result.value
            currentCoroutineContext().ensureActive()
            if (!active()) { failure = SettingsError.NOT_READY; return@LaunchedEffect }
            val value = pending
            if (result.error != null || value == null) failure = result.error ?: SettingsError.REQUEST_FAILED
            else {
                // Capture this request's callback: a newer page must validate its own owner before adopting it.
                onPrepared(value)
                pending = null
                dismiss()
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failure = SettingsError.REQUEST_FAILED }
        finally {
            local.set(false)
            pending?.lease?.release()
            selecting = false; preparing = false
        }
    }
    LocalSettingsSecurityConfirmationHost.current.Content(!selecting, "添加通知铃声", null, 460.dp, dismiss) {
        ProfileExtrasDialogBody(content = {
            if (preparing) SettingsNote("正在检查完整音频…")
            SettingsNote("支持 MP3 和 Vorbis/Opus OGG；上传文件保持原样。")
            if (!limits.known || failure == SettingsError.NOT_READY)
                SettingsNote("铃声限制尚未就绪或已改变，请刷新后重新选择。", error = true)
            else if (failure != null) SettingsNote(when (failure) {
                SettingsError.INVALID_VALUE -> "文件格式、大小或时长不符合要求，请重新选择。"
                SettingsError.UNSUPPORTED -> "音频组件不可用，请检查本地媒体工具。"
                else -> "无法准备此音频，请重新选择。"
            }, error = true)
        }) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ChatButton("取消", Modifier.weight(1f), onClick = dismiss)
                ChatButton("重新选择", Modifier.weight(1f), enabled = pageActive && limits.known && !selecting && !preparing) { attempt++ }
            }
        }
    }
}
