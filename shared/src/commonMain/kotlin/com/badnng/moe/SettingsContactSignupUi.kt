package com.badnng.moe

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.badnng.moe.settings.ContactSignupActions
import com.badnng.moe.settings.ContactSignupSnapshot
import com.badnng.moe.settings.ContactSignupToken
import com.badnng.moe.settings.ContactSignupValueSource
import com.badnng.moe.settings.SettingsToken
import kotlinx.coroutines.flow.MutableStateFlow

@Composable
internal fun SettingsContactSignupControls(
    accountToken: SettingsToken,
    snapshot: ContactSignupSnapshot,
    actions: ContactSignupActions,
    active: Boolean = LocalSettingsPageActive.current,
) {
    val pageActive = active && LocalSettingsPageActive.current
    val pageLease = remember(pageActive, accountToken) {
        ContactSignupPageLease(accountToken.generation, snapshot.token, snapshot.opened, pageActive)
    }
    val latestActions = rememberUpdatedState(actions)
    val latestSnapshot = rememberUpdatedState(snapshot)
    val latestPageActive = rememberUpdatedState(pageActive)
    var ownedToken by remember(pageLease) { mutableStateOf<ContactSignupToken?>(null) }

    SideEffect {
        pageLease.observe(snapshot)?.let { observed ->
            if (ownedToken != observed) ownedToken = observed
        }
    }
    DisposableEffect(pageLease) {
        if (pageLease.active()) latestActions.value.open(accountToken, pageLease::active)
        onDispose {
            pageLease.close()?.let { latestActions.value.close(it) }
        }
    }

    val tokenMatches = pageActive && pageLease.active() && ownedToken == snapshot.token &&
        snapshot.token.generation == accountToken.generation
    val value = snapshot.value.takeIf { tokenMatches && snapshot.opened }
    val editable = value != null && !snapshot.loading && !snapshot.saving &&
        !snapshot.readbackPending && !snapshot.refreshRequired
    val refreshEnabled = tokenMatches && snapshot.opened && !snapshot.loading && !snapshot.saving

    SettingsGroup("联系人加入提醒") {
        if (value == null) {
            SettingsRow("联系人加入 Telegram 时通知我", when {
                snapshot.loading -> "正在读取…"
                !tokenMatches -> "尚未读取"
                else -> "尚未读取"
            })
        } else {
            SettingsToggle("联系人加入 Telegram 时通知我", value.enabled, enabled = editable) { desired ->
                val current = latestSnapshot.value
                val expected = value
                if (!pageLease.active() || !latestPageActive.value || !editable ||
                    current.token != snapshot.token || current.token != ownedToken || !current.opened ||
                    current.revision != snapshot.revision || current.value != expected || current.loading ||
                    current.saving || current.readbackPending || current.refreshRequired) return@SettingsToggle
                latestActions.value.save(current.token, current.revision, expected, desired)
            }
            SettingsNote(when (value.source) {
                ContactSignupValueSource.DEFAULT -> "当前读取值使用 Telegram 默认通知设置。"
                ContactSignupValueSource.EXPLICIT -> "当前读取值来自此账号的单独设置。"
            })
        }

        if (tokenMatches && snapshot.loading) SettingsNote("正在读取联系人加入提醒…")
        if (tokenMatches && snapshot.saving) SettingsNote("正在提交更改…")
        if (tokenMatches && snapshot.readbackPending) {
            val intent = when (snapshot.desiredEnabled) {
                true -> "已提交开启联系人加入提醒"
                false -> "已提交关闭联系人加入提醒"
                null -> "更改已提交"
            }
            SettingsNote("$intent，尚未读回本次设置。Telegram 可能需要一些时间在其他设备上显示更改。")
        }
        if (tokenMatches && snapshot.refreshRequired) {
            SettingsNote("当前读取值尚未核实。请刷新后再更改此开关。", error = true)
        }
        if (tokenMatches) snapshot.error?.let { SettingsNote(settingsErrorText(it), error = true) }

        SettingsNote("这只影响你是否收到联系人加入 Telegram 的提醒，不会向联系人发送消息。")
        SettingsAction("刷新联系人加入提醒", enabled = refreshEnabled) {
            val current = latestSnapshot.value
            if (!pageLease.active() || !latestPageActive.value || current.token != ownedToken ||
                current.token != snapshot.token || !current.opened || current.loading || current.saving) return@SettingsAction
            latestActions.value.refresh(current.token)
        }
    }
}

/** Flow state gives the native actor a thread-safe lease without putting platform atomics in commonMain. */
private class ContactSignupPageLease(
    private val generation: Long,
    private val initialToken: ContactSignupToken,
    private val initiallyOpened: Boolean,
    initiallyActive: Boolean,
) {
    private val alive = MutableStateFlow(initiallyActive)
    private val token = MutableStateFlow<ContactSignupToken?>(null)

    fun active() = alive.value

    fun observe(snapshot: ContactSignupSnapshot): ContactSignupToken? {
        if (!active() || snapshot.token.generation != generation) return token.value
        if (!snapshot.opened) {
            token.value = null
            return null
        }
        if (snapshot.token != initialToken || !initiallyOpened) token.value = snapshot.token
        return token.value
    }

    fun close(): ContactSignupToken? {
        if (!alive.compareAndSet(true, false)) return null
        return token.value
    }
}
