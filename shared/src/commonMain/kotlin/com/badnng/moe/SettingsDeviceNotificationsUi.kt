package com.badnng.moe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.sp
import com.badnng.moe.notifications.*
import com.badnng.moe.chat.ChatListRef
import com.badnng.moe.chat.ChatSnapshot
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

internal val LocalDeviceNotifications = compositionLocalOf { DeviceNotificationsSnapshot() }
internal val LocalDeviceNotificationActions = compositionLocalOf { DeviceNotificationActions() }

@Composable
internal fun deviceFolderUnread(snapshot: ChatSnapshot, list: ChatListRef): Int? {
    val notifications = LocalDeviceNotifications.current
    return if (notifications.enabled && notifications.generation == snapshot.token.generation) notifications.folderBadge(list)
    else snapshot.unreadChats[list]
}

/** Composed inside the notifications page's existing independent scroll owner. */
@Composable
internal fun SettingsDeviceNotificationsContent(
    snapshot: DeviceNotificationsSnapshot = LocalDeviceNotifications.current,
    actions: DeviceNotificationActions = LocalDeviceNotificationActions.current,
) {
    val preferences = snapshot.preferences
    val enabled = LocalSettingsPageActive.current && snapshot.enabled
    fun save(value: DeviceNotificationPreferences) { if (enabled) actions.save(snapshot.generation, value) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        SettingsGroup("置顶消息提醒") {
            SettingsToggle("当有人置顶消息时提醒我", preferences.notifyAboutPinned, enabled) {
                save(preferences.copy(notifyAboutPinned = it))
            }
            SettingsNote("此设置只影响此设备。关闭后，置顶消息按照普通消息的静音和预览规则处理；桌面通知、声音和任务栏闪烁仍由各自开关控制。")
        }
        SettingsGroup("此设备") {
            SettingsToggle(if (preferences.useWindowsNotifications && snapshot.windows.status == WindowsDeviceNotificationStatus.ENABLED)
                "显示通知" else "应用内通知", preferences.desktopEnabled, enabled) { save(preferences.copy(desktopEnabled = it)) }
            SettingsToggle("声音通知", preferences.soundEnabled, enabled) { save(preferences.copy(soundEnabled = it)) }
            SettingsToggle("闪烁任务栏窗口", preferences.flashEnabled, enabled) { save(preferences.copy(flashEnabled = it)) }
            var volume by remember(preferences.volumePercent) { mutableStateOf(preferences.volumePercent.toFloat()) }
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("通知音量：${volume.roundToInt()}%", fontSize = 14.sp)
                Slider(value = volume, onValueChange = { if (it.isFinite()) volume = it.coerceIn(0f, 100f) },
                    valueRange = 0f..100f, enabled = enabled && preferences.soundEnabled,
                    modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = "通知音量"
                        setProgress { value ->
                            if (!enabled || !preferences.soundEnabled || !value.isFinite()) false
                            else { save(preferences.copy(volumePercent = value.roundToInt().coerceIn(0, 100))); true }
                        }
                    }, onValueChangeFinished = { save(preferences.copy(volumePercent = volume.roundToInt())) })
                SettingsNote("默认铃声使用应用内置提示音。账号中选择的自定义铃声尚未接入播放，不会用默认音替代。")
            }
        }
        SettingsGroup("通知预览") {
            SettingsToggle("显示聊天名称", preferences.showSender, enabled) { save(preferences.copy(showSender = it)) }
            SettingsToggle("显示消息内容", preferences.showPreview, enabled) { save(preferences.copy(showPreview = it)) }
        }
        SettingsGroup("未读计数") {
            SettingsToggle("计算未读消息数", preferences.unreadMode == UnreadCounterMode.MESSAGES, enabled) {
                save(preferences.copy(unreadMode = if (it) UnreadCounterMode.MESSAGES else UnreadCounterMode.CHATS))
            }
            SettingsToggle("包括已静音的聊天", preferences.includeMuted, enabled) { save(preferences.copy(includeMuted = it)) }
            SettingsToggle("文件夹计数包括已静音的聊天", preferences.includeMutedFolders, enabled) { save(preferences.copy(includeMutedFolders = it)) }
            SettingsRow("账号未读计数", snapshot.accountBadge()?.toString() ?: "尚未收到完整原生计数")
        }
        if (snapshot.windows.status != WindowsDeviceNotificationStatus.ENABLED) SettingsGroup("应用内通知布局") {
            DeviceNotificationCorner.entries.forEach { corner ->
                SettingsRow(corner.label(), if (preferences.corner == corner) "已选中" else null,
                    enabled = enabled && preferences.desktopEnabled) { save(preferences.copy(corner = corner)) }
            }
            (1..5).forEach { count ->
                SettingsRow("最多显示 $count 条", if (preferences.maximumVisible == count) "已选中" else null,
                    enabled = enabled && preferences.desktopEnabled) { save(preferences.copy(maximumVisible = count)) }
            }
        }
        SettingsGroup("Windows 系统集成") {
            val windows = snapshot.windows
            val pending = windows.status == WindowsDeviceNotificationStatus.ENABLING
            if (windows.status == WindowsDeviceNotificationStatus.UNAVAILABLE) {
                SettingsRow("Windows 原生桌面通知", "仅发布版 EXE 可用",
                    "当前环境使用应用内通知。系统通知需要具有稳定可执行路径的 Native Image 发布版及本应用的系统注册。")
                if (preferences.useWindowsNotifications) SettingsAction("使用应用内通知", enabled) {
                    actions.windowsNotifications(snapshot.generation, false)
                }
            } else {
                SettingsToggle("Windows 原生桌面通知", preferences.useWindowsNotifications, enabled && !pending) {
                    actions.windowsNotifications(snapshot.generation, it)
                }
                SettingsNote("首次启用会为当前应用创建专属开始菜单快捷方式及通知激活注册。关闭后回到应用内通知；系统注册可单独移除。")
                windows.executable?.let { SettingsRow("通知程序", summary = it) }
                SettingsRow("Windows 通知状态", when (windows.status) {
                    WindowsDeviceNotificationStatus.UNAVAILABLE -> "当前环境不可用"
                    WindowsDeviceNotificationStatus.DISABLED -> "尚未启用"
                    WindowsDeviceNotificationStatus.REGISTERED -> "已注册，当前使用应用内通知"
                    WindowsDeviceNotificationStatus.ENABLING -> "正在处理"
                    WindowsDeviceNotificationStatus.ENABLED -> "系统通知后端已就绪"
                    WindowsDeviceNotificationStatus.FAILED -> "运行失败，使用应用内通知"
                    WindowsDeviceNotificationStatus.CONFLICT -> "注册冲突，既有项目保持不变"
                })
                SettingsToggle("遵循 Windows 勿扰与专注助手", preferences.respectWindowsFocusAssist,
                    enabled && !pending && windows.status == WindowsDeviceNotificationStatus.ENABLED) {
                    save(preferences.copy(respectWindowsFocusAssist = it))
                }
                SettingsNote("此选项控制应用播放的声音与任务栏闪烁；Windows 仍决定系统通知是否显示。")
                if (windows.status == WindowsDeviceNotificationStatus.ENABLED) SettingsRow("Windows 专注助手", when (windows.focusBlocked) {
                    true -> "当前阻止提醒"
                    false -> "当前允许此应用提醒"
                    null -> "尚未读取或当前无法读取"
                })
                if (windows.registered || windows.status == WindowsDeviceNotificationStatus.CONFLICT)
                    SettingsAction("移除本应用通知注册", enabled && !pending, destructive = true) {
                        actions.removeWindowsRegistration(snapshot.generation)
                    }
            }
        }
        snapshot.error?.takeUnless { it == DeviceNotificationError.MODE_COMMITTED_SAVE_FAILED }?.let { error -> SettingsNote(when (error) {
            DeviceNotificationError.SAVE_FAILED -> "设备通知偏好保存失败，保留原设置。"
            DeviceNotificationError.MODE_COMMITTED_SAVE_FAILED -> "当前通知方式尚未保存，重启后可能使用其他通知方式。请重试保存。"
            DeviceNotificationError.NATIVE_FAILED -> "原生通知请求失败，请重新启用对应选项重试。"
            DeviceNotificationError.CUSTOM_SOUND_UNAVAILABLE -> "该提示音暂时无法播放，可在声音设置中重新选择。"
            DeviceNotificationError.SOUND_VOLUME_UNAVAILABLE -> "通知音量暂时无法读取，请在声音设置中重试。"
            DeviceNotificationError.OUTPUT_FAILED -> "系统通知、声音或任务栏闪烁不可用，请重试。"
        }, error = true) }
        if (snapshot.modeSavePending) {
            SettingsNote("当前通知方式尚未保存，重启后可能使用其他通知方式。请重试保存。", error = true)
            SettingsAction("重试保存通知偏好", enabled) { save(preferences) }
        }
    }
}

/** Application-owned Miuix cards; explicitly not a Windows toast implementation. */
@Composable
internal fun DeviceNotificationOverlay(
    snapshot: DeviceNotificationsSnapshot = LocalDeviceNotifications.current,
    actions: DeviceNotificationActions = LocalDeviceNotificationActions.current,
    modifier: Modifier = Modifier,
) {
    if (!snapshot.enabled || snapshot.cards.isEmpty()) return
    val alignment = when (snapshot.preferences.corner) {
        DeviceNotificationCorner.TOP_LEFT -> Alignment.TopStart
        DeviceNotificationCorner.TOP_RIGHT -> Alignment.TopEnd
        DeviceNotificationCorner.BOTTOM_LEFT -> Alignment.BottomStart
        DeviceNotificationCorner.BOTTOM_RIGHT -> Alignment.BottomEnd
    }
    Box(modifier.fillMaxSize().padding(16.dp)) {
        val shape = rememberMiuixSquircleShape(15.dp)
        Column(Modifier.align(alignment).widthIn(max = 340.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (snapshot.error == DeviceNotificationError.NATIVE_FAILED) SettingsNote("通知操作失败，请重试。", error = true)
            if (snapshot.preferences.useWindowsNotifications && snapshot.windows.status == WindowsDeviceNotificationStatus.FAILED)
                SettingsNote("Windows 通知失败，已显示应用内通知。", error = true)
            snapshot.cards.forEach { card ->
                LaunchedEffect(card.identity) { delay(8_000); actions.expire(card.identity) }
                Column(Modifier.fillMaxWidth().dropShadow(shape, Shadow(radius = 6.dp, color = Color.Black.copy(alpha = .22f), offset = DpOffset(0.dp, 3.dp)))
                    .chatSquircleBackground(MiuixTheme.colorScheme.surface, 15.dp).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(card.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(card.text, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChatButton("打开", Modifier.weight(1f), onClick = { actions.activate(card.identity) })
                        ChatButton("关闭通知", Modifier.weight(1f), onClick = { actions.dismiss(card.identity) })
                    }
                }
            }
        }
    }
}

private fun DeviceNotificationCorner.label(): String = when (this) {
    DeviceNotificationCorner.TOP_LEFT -> "左上角"
    DeviceNotificationCorner.TOP_RIGHT -> "右上角"
    DeviceNotificationCorner.BOTTOM_LEFT -> "左下角"
    DeviceNotificationCorner.BOTTOM_RIGHT -> "右下角"
}
