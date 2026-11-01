package com.badnng.moe

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

/** Production consumes the existing Compose/Nucleus Boolean, without claiming capability. */
internal interface SettingsSystemThemeObservation {
    @Composable fun current(): Boolean?
}

private object NucleusSystemThemeObservation : SettingsSystemThemeObservation {
    @Composable override fun current(): Boolean = isSystemInDarkTheme()
}

internal val LocalSettingsSystemThemeObservation = staticCompositionLocalOf<SettingsSystemThemeObservation> {
    NucleusSystemThemeObservation
}

/** Injectable for isolated App tests; the default uses the existing local settings file. */
internal interface SettingsPreferencesAccess {
    fun load(): SettingsPreferences
    fun save(preferences: SettingsPreferences): Boolean
    fun saveIfCurrent(preferences: SettingsPreferences, current: () -> Boolean): Boolean = current() && save(preferences)
}

internal object DeviceSettingsPreferencesAccess : SettingsPreferencesAccess {
    override fun load() = loadSettingsPreferences()
    override fun save(preferences: SettingsPreferences) = saveSettingsPreferences(preferences)
    override fun saveIfCurrent(preferences: SettingsPreferences, current: () -> Boolean) =
        saveSettingsPreferencesIfCurrent(preferences, current)
}

internal val LocalSettingsPreferencesAccess = staticCompositionLocalOf<SettingsPreferencesAccess?> { null }

/** Null only for standalone previews that supply their own Miuix palette. */
internal val LocalAppEffectiveDark = staticCompositionLocalOf<Boolean?> { null }

/** Offline checks observe actual consumers; production has no observer or frame work. */
internal val LocalSettingsThemeConsumerProbe = staticCompositionLocalOf<((String, Boolean) -> Unit)?> { null }

/** Offline tests capture the exact dialog callbacks before toolkit click wrappers. */
internal class SettingsThemeConfirmationCallbacks(
    val pending: PendingSettingsTheme,
    val dismiss: () -> Unit,
    val confirm: () -> Unit,
)

/** Production retains no observer or callback history. */
internal val LocalSettingsThemeConfirmationCallbacks =
    staticCompositionLocalOf<((SettingsThemeConfirmationCallbacks) -> Unit)?> { null }

internal fun SettingsPreferences.themeStatePreferences(): SettingsThemePreferences =
    settingsThemeFromInitialMode(theme.name).copy(manualDark = manualThemeDark)

internal fun SettingsPreferences.withThemeState(value: SettingsThemePreferences): SettingsPreferences {
    val mode = settingsMiuixModeName(initialSettingsThemeState(value, null))
    return copy(theme = ColorSchemeMode.valueOf(mode), manualThemeDark = value.manualDark)
}

internal fun settingsThemeBaseMode(mode: ColorSchemeMode): ColorSchemeMode = when (mode) {
    ColorSchemeMode.MonetSystem -> ColorSchemeMode.System
    ColorSchemeMode.MonetLight -> ColorSchemeMode.Light
    ColorSchemeMode.MonetDark -> ColorSchemeMode.Dark
    else -> mode
}

/** Synchronous epoch catches close/open ABA even before a Compose frame is emitted. */
internal class ThemeOwnedFlag(initial: Boolean = false) {
    private var current by mutableStateOf(initial)
    var revision by mutableLongStateOf(0L)
        private set
    var value: Boolean
        get() = current
        set(value) {
            if (current != value) { current = value; revision++ }
        }
}

internal class ThemePageLease {
    var alive = true
        private set
    fun close() { alive = false }
}

internal class PendingSettingsTheme(
    val confirmation: SettingsThemeConfirmation,
    val owner: () -> Boolean,
    val save: (SettingsThemePreferences) -> Boolean,
)

/** Shared by every real App entrance. All calls and synchronous saves are on the UI thread. */
internal class SettingsThemeCoordinator(initial: SettingsThemePreferences, systemDark: Boolean?) {
    var state by mutableStateOf(initialSettingsThemeState(initial, systemDark))
        private set
    var pending by mutableStateOf<PendingSettingsTheme?>(null)
        private set

    fun observe(systemDark: Boolean?) {
        state = observeSettingsSystemTheme(state, systemDark)
        pending?.let { if (!valid(it)) pending = null }
    }

    fun chooseFollow(owner: () -> Boolean, save: (SettingsThemePreferences) -> Boolean) {
        if (!owner()) return
        pending = null
        decide(chooseSettingsThemeFollow(state), owner, save)
    }

    fun chooseBrightness(dark: Boolean, owner: () -> Boolean, save: (SettingsThemePreferences) -> Boolean) {
        if (!owner()) return
        pending = null
        decide(chooseSettingsThemeBrightness(state, dark), owner, save)
    }

    private fun decide(decision: SettingsThemeDecision, owner: () -> Boolean, save: (SettingsThemePreferences) -> Boolean) {
        when (decision) {
            SettingsThemeDecision.Unchanged -> Unit
            is SettingsThemeDecision.Apply -> apply(decision.change, owner, save)
            is SettingsThemeDecision.Confirm -> pending = PendingSettingsTheme(decision.confirmation, owner, save)
        }
    }

    private fun apply(change: SettingsThemeChange, owner: () -> Boolean, save: (SettingsThemePreferences) -> Boolean) {
        if (!owner() || !change.isCurrent(state)) return
        val saved = !change.needsPersistence || save(change.targetPreferences)
        if (saved) state = acceptSettingsThemeChange(state, change, persistenceSucceeded = true)
    }

    fun valid(value: PendingSettingsTheme) = pending === value && value.owner() && value.confirmation.expected == state

    fun dismiss(value: PendingSettingsTheme?) { if (value != null && pending === value) pending = null }

    fun confirm(value: PendingSettingsTheme?) {
        if (value == null || !valid(value)) return
        val plan = confirmSettingsThemeChange(state, value.confirmation) ?: return
        pending = null
        apply(plan, value.owner, value.save)
    }
}

@Composable
internal fun SettingsThemeConfirmationDialog(coordinator: SettingsThemeCoordinator, enabled: Boolean = true) {
    val captured = coordinator.pending
    val show = enabled && captured != null && coordinator.valid(captured)
    val dismiss: () -> Unit = { coordinator.dismiss(captured) }
    val confirm: () -> Unit = { if (enabled) coordinator.confirm(captured) }
    val observer = LocalSettingsThemeConfirmationCallbacks.current
    SideEffect {
        if (show && captured != null) observer?.invoke(SettingsThemeConfirmationCallbacks(captured, dismiss, confirm))
    }
    SideEffect { if (captured != null && (!enabled || !coordinator.valid(captured))) coordinator.dismiss(captured) }
    SecurityConfirmation(show, title = "关闭跟随系统？", maxWidth = 440.dp,
        onDismissRequest = dismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsNote("切换明暗将关闭跟随系统。以后可在此重新开启。")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ChatButton("取消", Modifier.weight(1f), onClick = dismiss)
                ChatButton("关闭并切换", Modifier.weight(1f), enabled = show, primary = true, onClick = confirm)
            }
        }
    }
}

@Composable
internal fun SettingsSystemThemeControls(
    coordinator: SettingsThemeCoordinator,
    owner: () -> Boolean,
    save: (SettingsThemePreferences) -> Boolean,
) {
    val active = LocalSettingsPageActive.current
    val preferences = coordinator.state.preferences
    SettingsGroup("主题") {
        SettingsRow("跟随系统", if (preferences.followSystem) "已选中" else null, enabled = active) {
            coordinator.chooseFollow(owner, save)
        }
        listOf("浅色" to false, "深色" to true).forEach { (label, dark) ->
            SettingsRow(label, if (!preferences.followSystem && preferences.manualDark == dark) "已选中" else null,
                enabled = active) { coordinator.chooseBrightness(dark, owner, save) }
        }
    }
}
