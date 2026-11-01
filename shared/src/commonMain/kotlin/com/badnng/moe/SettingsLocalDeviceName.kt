package com.badnng.moe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.badnng.moe.settings.SettingsSecuritySection
import com.badnng.moe.settings.SettingsSecuritySnapshot
import com.badnng.moe.settings.SettingsSession
import com.badnng.moe.settings.matchesDevice
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.TextField

internal const val SETTINGS_DEVICE_NAME_LIMIT = 32
internal const val SETTINGS_DEFAULT_DEVICE_NAME = "Desktop"

/** Mirrors the pinned CleanAndSimplify stages; Qt/Kotlin Unicode tables can differ by version. */
internal fun cleanSettingsDeviceName(text: String): String = buildString {
    var space = false
    for (char in text) {
        if (char.code < 32 || char.isWhitespace() || char == '\u0085') {
            if (isNotEmpty()) space = true
        } else {
            if (space) append(' ')
            append(char)
            space = false
        }
    }
}

/** Reject ill-formed UTF-16 rather than persisting a name JNI/UTF-8 cannot represent. */
internal fun validSettingsDeviceName(value: String): Boolean {
    if (value.length > SETTINGS_DEVICE_NAME_LIMIT || cleanSettingsDeviceName(value) != value) return false
    var index = 0
    while (index < value.length) {
        val char = value[index]
        if (char.isHighSurrogate()) {
            if (index + 1 >= value.length || !value[index + 1].isLowSurrogate()) return false
            index += 2
        } else {
            if (char.isLowSurrogate()) return false
            index++
        }
    }
    return true
}

internal fun settingsEffectiveDeviceName(value: String): String =
    value.takeIf { validSettingsDeviceName(it) && it.isNotEmpty() } ?: SETTINGS_DEFAULT_DEVICE_NAME

private fun limitDeviceName(value: TextFieldValue): TextFieldValue {
    if (value.text.length <= SETTINGS_DEVICE_NAME_LIMIT) return value
    var end = SETTINGS_DEVICE_NAME_LIMIT
    if (value.text[end - 1].isHighSurrogate() && value.text[end].isLowSurrogate()) end--
    return value.copy(text = value.text.substring(0, end),
        selection = TextRange(value.selection.start.coerceAtMost(end), value.selection.end.coerceAtMost(end)),
        composition = null)
}

/** The host performs the local save only while both this predicate and its own owner remain current. */
internal class SettingsDeviceNameHost(
    val customName: String,
    val current: () -> Boolean,
    val retireSecurity: () -> Unit,
    val save: (String, () -> Boolean, (Boolean) -> Unit) -> Unit,
)
internal val LocalSettingsDeviceNameHost = staticCompositionLocalOf<SettingsDeviceNameHost?> { null }

/** Test-only observer of the same callbacks attached to the visible controls. */
internal class SettingsDeviceNameUiCallbacks(
    val open: () -> Unit,
    val change: ((TextFieldValue) -> Unit)?,
    val save: (() -> Unit)?,
    val cancel: (() -> Unit)?,
)
internal val LocalSettingsDeviceNameUiCallbacks = staticCompositionLocalOf<((SettingsDeviceNameUiCallbacks) -> Unit)?> { null }

private class DeviceNameEditor(val session: SettingsSession, val initial: String, val initialOwner: () -> Boolean) {
    var draft by mutableStateOf(TextFieldValue(initial))
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
}

private class DeviceNamePage {
    var alive = true
    var editor by mutableStateOf<DeviceNameEditor?>(null)
    var notice by mutableStateOf<String?>(null)
    fun retire() { alive = false; editor = null; notice = null }
}

private fun SettingsSession.sameDeviceNameTarget(other: SettingsSession) =
    matchesDevice(other) && logInDate == other.logInDate &&
        isPasswordPending == other.isPasswordPending && isUnconfirmed == other.isUnconfirmed

@Composable
internal fun SettingsLocalDeviceNameControl(security: SettingsSecuritySnapshot, active: Boolean) {
    val host = LocalSettingsDeviceNameHost.current
    val latestHost by rememberUpdatedState(host)
    val latestSecurity by rememberUpdatedState(security)
    val latestActive by rememberUpdatedState(active)
    val owner = remember(security.token, active) { DeviceNamePage() }
    val scope = rememberCoroutineScope()
    DisposableEffect(owner) { onDispose { owner.retire() } }
    val editor = owner.editor

    fun pageCurrent(): Boolean = owner.alive && latestActive && latestSecurity.token == security.token &&
        latestSecurity.section == SettingsSecuritySection.SESSIONS && !latestSecurity.loading &&
        !latestSecurity.busy && !latestSecurity.sessionsRefreshRequired && latestHost?.current?.invoke() == true

    fun editorCurrent(chosen: DeviceNameEditor): Boolean = pageCurrent() && chosen.initialOwner() && owner.editor === chosen &&
        latestSecurity.sessions.count { it.isCurrent } == 1 &&
        latestSecurity.sessions.any { it.isCurrent && chosen.session.sameDeviceNameTarget(it) }

    val open: () -> Unit = open@ {
        if (!pageCurrent()) return@open
        val session = latestSecurity.sessions.singleOrNull { it.isCurrent } ?: return@open
        val current = latestHost ?: return@open
        owner.editor = DeviceNameEditor(session, current.customName, current.current)
        owner.notice = null
    }
    val cancel: () -> Unit = {
        if (editor != null && owner.editor === editor && !editor.busy) owner.editor = null
    }
    val change: (TextFieldValue) -> Unit = { value ->
        if (editor != null && editorCurrent(editor) && !editor.busy) {
            editor.draft = limitDeviceName(value)
            editor.error = null
        }
    }
    val save: () -> Unit = save@ {
        val chosen = editor ?: return@save
        val currentHost = latestHost ?: return@save
        if (!editorCurrent(chosen) || chosen.busy) return@save
        if (currentHost.customName != chosen.initial) {
            chosen.error = "名称已更改，请关闭后重新打开。"
            return@save
        }
        val name = cleanSettingsDeviceName(chosen.draft.text)
        if (!validSettingsDeviceName(name)) {
            chosen.error = "名称包含无效字符，请检查后重试。"
            return@save
        }
        chosen.busy = true
        chosen.error = null
        val commitCurrent = {
            editorCurrent(chosen) && chosen.busy && latestHost?.customName == chosen.initial &&
                cleanSettingsDeviceName(chosen.draft.text) == name
        }
        val completion: (Boolean) -> Unit = { saved ->
            // Completion may be delivered by an injected asynchronous host; all page state
            // is read and written on the composition's UI scope, and old editors cannot win.
            scope.launch {
                if (!editorCurrent(chosen) || !chosen.busy) return@launch
                chosen.busy = false
                if (saved) {
                    owner.editor = null
                    owner.notice = "已保存，将在下次启动生效。"
                } else chosen.error = "保存失败，请重试。"
            }
        }
        try { currentHost.save(name, commitCurrent, completion) } catch (_: Exception) { completion(false) }
    }

    val observer = LocalSettingsDeviceNameUiCallbacks.current
    SideEffect {
        if (editor != null && !editorCurrent(editor)) owner.editor = null
        observer?.invoke(SettingsDeviceNameUiCallbacks(open,
            change.takeIf { editor != null }, save.takeIf { editor != null }, cancel.takeIf { editor != null }))
    }
    owner.notice?.let { SettingsNote(it) }
    SettingsRow("编辑当前设备名称", host?.customName?.let(::settingsEffectiveDeviceName),
        summary = "本地名称，下次启动生效。", enabled = pageCurrent() &&
            security.sessions.count { it.isCurrent } == 1, onClick = open)
    SecurityConfirmation(show = editor != null && editorCurrent(editor), title = "当前设备名称",
        maxWidth = 420.dp, onDismissRequest = cancel) {
        SecurityDialogBody {
            SettingsNote("保存在此设备，下次启动生效。留空使用 Desktop。已登录设备列表仍显示 Telegram 返回的名称。")
            if (editor != null) {
                TextField(editor.draft, change, label = "名称", enabled = !editor.busy,
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }))
                editor.error?.let { SettingsNote(it, error = true) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ChatButton("取消", Modifier.weight(1f), enabled = !editor.busy, onClick = cancel)
                    ChatButton("保存", Modifier.weight(1f), enabled = !editor.busy, primary = true, onClick = save)
                }
            }
        }
    }
}
