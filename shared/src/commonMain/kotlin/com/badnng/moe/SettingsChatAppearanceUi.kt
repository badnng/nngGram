package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.RadioButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.lightColorScheme

/** Preserve the exact default Miuix palette; explicit hue choices use its native tonal generator. */
@Composable
internal fun rememberSettingsThemeController(
    mode: ColorSchemeMode,
    accent: SettingsAccentColor,
    systemDark: Boolean? = null,
): ThemeController = remember(mode, accent, systemDark) {
    if (accent == SettingsAccentColor.DEFAULT) ThemeController(mode, isDark = systemDark)
    else ThemeController(colorSchemeMode = when (mode) {
        ColorSchemeMode.Light, ColorSchemeMode.MonetLight -> ColorSchemeMode.MonetLight
        ColorSchemeMode.Dark, ColorSchemeMode.MonetDark -> ColorSchemeMode.MonetDark
        else -> ColorSchemeMode.MonetSystem
    }, keyColor = Color(checkNotNull(accent.seedArgb)), isDark = systemDark)
}

@Composable
internal fun SettingsAccentColorControls(
    preferences: SettingsPreferences,
    onPreferences: (SettingsPreferences) -> Unit,
    enabled: Boolean = LocalSettingsPageActive.current,
) {
    val currentPreferences by rememberUpdatedState(preferences)
    val currentOnPreferences by rememberUpdatedState(onPreferences)
    val currentEnabled by rememberUpdatedState(enabled)
    val defaultPreview = remember { lightColorScheme().primary }
    SettingsGroup("强调色") {
        FlowRow(Modifier.fillMaxWidth().padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SettingsAccentColor.entries.forEach { accent ->
                val selected = preferences.accentColor == accent
                Row(Modifier.widthIn(min = 104.dp)
                    .chatSquircleSurface(if (selected) MiuixTheme.colorScheme.tertiaryContainer else Color.Transparent, 15.dp)
                    .selectable(selected, enabled = enabled, role = Role.RadioButton) {
                        if (currentEnabled && currentPreferences.accentColor != accent)
                            currentOnPreferences(currentPreferences.copy(accentColor = accent))
                    }.semantics { contentDescription = "强调色 ${accent.label}" }
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    androidx.compose.foundation.layout.Box(Modifier.size(16.dp).background(
                        accent.seedArgb?.let(::Color) ?: defaultPreview, CircleShape))
                    Text(accent.label, fontSize = 13.sp,
                        color = if (enabled) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.disabledOnSurface)
                    RadioButton(selected, null, enabled = enabled,
                        modifier = Modifier.clearAndSetSemantics {})
                }
            }
        }
    }
}
