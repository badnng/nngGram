package com.badnng.moe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

/** Changes App content only; the system's density remains an independent read-only input. */
@Composable
internal fun SettingsInterfaceScaleControl(
    preferences: SettingsPreferences,
    onPreferences: (SettingsPreferences) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val systemDensity = LocalSettingsSystemDensity.current ?: LocalDensity.current
    val systemPercent = (systemDensity.density * 100f).roundToInt()
    val automatic = preferences.interfaceScalePercent == SETTINGS_INTERFACE_SCALE_AUTOMATIC
    val selectedPercent = preferences.interfaceScalePercent.takeIf { it > 0 && isValidSettingsInterfaceScale(it) } ?: 100
    var previewPercent by remember { mutableStateOf<Int?>(null) }
    val currentPreferences by rememberUpdatedState(preferences)
    val currentOnPreferences by rememberUpdatedState(onPreferences)
    val currentEnabled by rememberUpdatedState(enabled)
    LaunchedEffect(preferences.interfaceScalePercent, enabled) { previewPercent = null }
    fun commit(percent: Int) {
        previewPercent = null
        if (currentEnabled) currentOnPreferences(currentPreferences.copy(interfaceScalePercent = percent))
    }

    Column(modifier) {
        SettingsGroup("界面缩放") {
            SettingsToggle("默认界面缩放比例", automatic, enabled) { checked ->
                if (currentEnabled) {
                    previewPercent = null
                    currentOnPreferences(currentPreferences.copy(interfaceScalePercent =
                        if (checked) SETTINGS_INTERFACE_SCALE_AUTOMATIC else 100))
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsValueLayout("应用比例", "${previewPercent ?: selectedPercent}%", enabled = enabled)
                Slider(value = (previewPercent ?: selectedPercent).toFloat(),
                    onValueChange = { value -> if (currentEnabled && value.isFinite()) previewPercent = scaleFromProgress(value) },
                    modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = "界面缩放比例"
                        // Miuix's semantic action has no drag-finished callback.
                        setProgress { value ->
                            if (!currentEnabled || !value.isFinite()) false
                            else { commit(scaleFromProgress(value)); true }
                        }
                    }.onPreviewKeyEvent { event ->
                        if (!currentEnabled || event.isAltPressed || event.isCtrlPressed || event.isMetaPressed ||
                            event.key != Key.DirectionLeft && event.key != Key.DirectionRight) false
                        else {
                            if (event.type == KeyEventType.KeyDown) {
                                val current = currentPreferences.interfaceScalePercent.takeIf { it > 0 } ?: 100
                                val direction = if (event.key == Key.DirectionLeft) -1 else 1
                                commit((current + direction * SETTINGS_INTERFACE_SCALE_STEP)
                                    .coerceIn(SETTINGS_INTERFACE_SCALE_MIN, SETTINGS_INTERFACE_SCALE_MAX))
                            }
                            true
                        }
                    }.focusable(enabled),
                    enabled = enabled,
                    valueRange = SETTINGS_INTERFACE_SCALE_MIN.toFloat()..SETTINGS_INTERFACE_SCALE_MAX.toFloat(),
                    steps = (SETTINGS_INTERFACE_SCALE_MAX - SETTINGS_INTERFACE_SCALE_MIN) / SETTINGS_INTERFACE_SCALE_STEP - 1,
                    onValueChangeFinished = {
                        val selected = previewPercent
                        previewPercent = null
                        if (selected != null) commit(selected)
                    })
                SettingsNote("系统缩放：$systemPercent%")
                SettingsNote(if (automatic) "跟随系统缩放。" else "手动比例基于系统缩放，立即生效。")
            }
        }
    }
}

private fun scaleFromProgress(value: Float): Int = SETTINGS_INTERFACE_SCALE_MIN +
    ((value.coerceIn(SETTINGS_INTERFACE_SCALE_MIN.toFloat(), SETTINGS_INTERFACE_SCALE_MAX.toFloat()) -
        SETTINGS_INTERFACE_SCALE_MIN) / SETTINGS_INTERFACE_SCALE_STEP).roundToInt() * SETTINGS_INTERFACE_SCALE_STEP
