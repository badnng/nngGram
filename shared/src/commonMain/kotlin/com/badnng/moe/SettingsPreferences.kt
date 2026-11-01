package com.badnng.moe

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Density
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

internal enum class SettingsSendKey { ENTER, CTRL_ENTER }

/** All choices use the existing, locally bundled official wallpaper assets. */
internal enum class SettingsWallpaper { AUTOMATIC, DAY, NIGHT }

/** Hue seeds from the pinned desktop DefaultAccentColors(Default/DayBlue), not Qt palette output. */
internal enum class SettingsAccentColor(val label: String, val seedArgb: Long?) {
    DEFAULT("默认", null),
    BLUE("蓝色", 0xFF45BCE7),
    GREEN("绿色", 0xFF52B440),
    PINK("粉色", 0xFFD46C99),
    ORANGE("橙色", 0xFFDF8A49),
    PURPLE("紫色", 0xFF9978C8),
    RED("红色", 0xFFC55245),
    SLATE("灰蓝", 0xFF687B98),
    GOLD("金色", 0xFFDEA922),
}

/** Device presentation preferences only. Drafts and account settings never enter this file. */
@Immutable
internal data class SettingsPreferences(
    val theme: ColorSchemeMode = ColorSchemeMode.System,
    val sendKey: SettingsSendKey = SettingsSendKey.ENTER,
    val wallpaper: SettingsWallpaper = SettingsWallpaper.AUTOMATIC,
    val stickerLoop: Boolean = true,
    /** Zero follows the host density; an explicit percentage multiplies that density once. */
    val interfaceScalePercent: Int = SETTINGS_INTERFACE_SCALE_AUTOMATIC,
    val accentColor: SettingsAccentColor = SettingsAccentColor.DEFAULT,
    val chatFont: SettingsChatFont = SettingsChatFont(),
    /** Last explicit brightness, retained independently while theme follows System. */
    val manualThemeDark: Boolean = theme == ColorSchemeMode.Dark || theme == ColorSchemeMode.MonetDark,
    /** Empty selects the built-in device model on the next engine start. */
    val customDeviceModel: String = "",
)

internal val LocalSettingsPreferences = compositionLocalOf { SettingsPreferences() }
/** Captured outside App's content-density override, so controls never reuse an already scaled value. */
internal val LocalSettingsSystemDensity = compositionLocalOf<Density?> { null }

internal const val SETTINGS_INTERFACE_SCALE_AUTOMATIC = 0
internal const val SETTINGS_INTERFACE_SCALE_MIN = 50
internal const val SETTINGS_INTERFACE_SCALE_MAX = 300
internal const val SETTINGS_INTERFACE_SCALE_STEP = 5

internal fun isValidSettingsInterfaceScale(percent: Int): Boolean =
    percent == SETTINGS_INTERFACE_SCALE_AUTOMATIC || percent in SETTINGS_INTERFACE_SCALE_MIN..SETTINGS_INTERFACE_SCALE_MAX

internal fun settingsInterfaceDensity(systemDensity: Density, percent: Int): Density {
    if (percent == SETTINGS_INTERFACE_SCALE_AUTOMATIC || !isValidSettingsInterfaceScale(percent)) return systemDensity
    return Density(systemDensity.density * (percent / 100f), systemDensity.fontScale)
}

internal expect fun loadSettingsPreferences(): SettingsPreferences
internal expect fun saveSettingsPreferences(preferences: SettingsPreferences): Boolean
internal expect fun saveSettingsPreferencesIfCurrent(preferences: SettingsPreferences, current: () -> Boolean): Boolean
