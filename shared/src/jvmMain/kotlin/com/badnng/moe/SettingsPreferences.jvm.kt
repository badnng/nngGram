package com.badnng.moe

import com.badnng.moe.tdlib.ConfigurationStore
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

internal actual fun loadSettingsPreferences(): SettingsPreferences = try {
    readSettingsPreferences(settingsPreferencesPath())
} catch (_: Exception) { SettingsPreferences() }

internal actual fun saveSettingsPreferences(preferences: SettingsPreferences): Boolean = try {
    writeSettingsPreferences(settingsPreferencesPath(), preferences)
} catch (_: Exception) { false }

internal actual fun saveSettingsPreferencesIfCurrent(preferences: SettingsPreferences, current: () -> Boolean): Boolean = try {
    writeSettingsPreferencesIfCurrent(settingsPreferencesPath(), preferences, current)
} catch (_: Exception) { false }

private fun settingsPreferencesPath(): Path =
    ConfigurationStore.default().path.toAbsolutePath().resolveSibling("ui-settings.properties")

internal fun readSettingsPreferences(path: Path): SettingsPreferences = try {
    if (!Files.isRegularFile(path)) SettingsPreferences() else {
        val values = Properties().apply { Files.newBufferedReader(path).use(::load) }
        val themeState = decodeSettingsTheme(values.stringPropertyNames().associateWith { values.getProperty(it) })
        SettingsPreferences(
            theme = ColorSchemeMode.valueOf(settingsMiuixModeName(initialSettingsThemeState(themeState, null))),
            manualThemeDark = themeState.manualDark,
            sendKey = SettingsSendKey.entries.firstOrNull { it.name == values.getProperty("send-key") }
                ?: SettingsSendKey.ENTER,
            wallpaper = SettingsWallpaper.entries.firstOrNull { it.name == values.getProperty("wallpaper") }
                ?: SettingsWallpaper.AUTOMATIC,
            stickerLoop = values.getProperty("sticker-loop")?.toBooleanStrictOrNull() ?: true,
            interfaceScalePercent = values.getProperty("interface-scale")?.toIntOrNull()
                ?.takeIf(::isValidSettingsInterfaceScale) ?: SETTINGS_INTERFACE_SCALE_AUTOMATIC,
            accentColor = SettingsAccentColor.entries.firstOrNull { it.name == values.getProperty("accent-color") }
                ?: SettingsAccentColor.DEFAULT,
            chatFont = SettingsChatFontMode.entries.firstOrNull { it.name == values.getProperty("font-mode") }?.let { mode ->
                SettingsChatFont(mode, if (mode == SettingsChatFontMode.INSTALLED) values.getProperty("font-family") else null)
                    .takeIf { it.valid }
            } ?: SettingsChatFont(),
            customDeviceModel = values.getProperty("custom-device-model")?.takeIf(::validSettingsDeviceName).orEmpty(),
        )
    }
} catch (_: Exception) { SettingsPreferences() }

internal fun writeSettingsPreferences(path: Path, preferences: SettingsPreferences,
    atomicReplace: (Path, Path) -> Unit = { source, destination ->
        Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        Unit
    },
): Boolean = writeSettingsPreferencesIfCurrent(path, preferences, { true }, atomicReplace)

internal fun writeSettingsPreferencesIfCurrent(path: Path, preferences: SettingsPreferences, current: () -> Boolean,
    atomicReplace: (Path, Path) -> Unit = { source, destination ->
        Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        Unit
    },
): Boolean {
    if (!isValidSettingsInterfaceScale(preferences.interfaceScalePercent) || !preferences.chatFont.valid ||
        !validSettingsDeviceName(preferences.customDeviceModel)) return false
    var temporary: Path? = null
    return try {
        val destination = path.toAbsolutePath()
        val parent = destination.parent
        Files.createDirectories(parent)
        val temp = Files.createTempFile(parent, "ui-settings-", ".tmp")
        temporary = temp
        val values = Properties().apply {
            encodeSettingsTheme(preferences.themeStatePreferences()).forEach { (key, value) -> setProperty(key, value) }
            setProperty("send-key", preferences.sendKey.name)
            setProperty("wallpaper", preferences.wallpaper.name)
            setProperty("sticker-loop", preferences.stickerLoop.toString())
            setProperty("interface-scale", preferences.interfaceScalePercent.toString())
            setProperty("accent-color", preferences.accentColor.name)
            setProperty("font-mode", preferences.chatFont.mode.name)
            preferences.chatFont.familyName?.let { setProperty("font-family", it) }
            preferences.customDeviceModel.takeIf(String::isNotEmpty)?.let { setProperty("custom-device-model", it) }
        }
        Files.newBufferedWriter(temp).use { values.store(it, "Local desktop settings") }
        // No non-atomic fallback: a failed replacement leaves the old preferences intact.
        if (!current()) return false
        atomicReplace(temp, destination)
        true
    } catch (_: Exception) { false }
    finally { temporary?.let { try { Files.deleteIfExists(it) } catch (_: Exception) { } } }
}
