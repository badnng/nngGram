package com.badnng.moe

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.badnng.moe.settings.LoginEmailActions
import com.badnng.moe.settings.LoginEmailEntryKind
import com.badnng.moe.settings.LoginEmailSnapshot

/** Optional setup is exposed only by the actual native suggestion, with its own transient form. */
@Composable
internal fun SettingsLoginEmailContent(snapshot: LoginEmailSnapshot, actions: LoginEmailActions, active: Boolean) {
    if (!snapshot.suggestions.optionalPresent || snapshot.mandatory) return
    var opened by rememberSaveable(snapshot.generation, snapshot.suggestions.optionalEpoch) { mutableStateOf(false) }
    if (opened) {
        LoginEmailSetupPage(snapshot, actions, LoginEmailEntryKind.OPTIONAL, active = active, onClose = { opened = false })
    } else SettingsGroup("登录邮箱") {
        SettingsAction("设置登录邮箱", active) { opened = true }
    }
}
