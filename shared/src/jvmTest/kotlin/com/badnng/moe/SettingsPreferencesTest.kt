package com.badnng.moe

import java.nio.file.Files
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

class SettingsPreferencesTest {
    @Test
    fun actualFileRoundTripIncludesEveryRuntimePreferenceAndReplacesThePreviousVersion() {
        val directory = Files.createTempDirectory("nnggram-settings-prefs-test-")
        try {
            val path = directory.resolve("nested/ui-settings.properties")
            for (theme in listOf(ColorSchemeMode.System, ColorSchemeMode.Light, ColorSchemeMode.Dark)) {
                for (sendKey in SettingsSendKey.entries) {
                    for (wallpaper in SettingsWallpaper.entries) {
                        val expected = SettingsPreferences(theme, sendKey, wallpaper, stickerLoop = false)
                        assertTrue(writeSettingsPreferences(path, expected))
                        assertEquals(expected, readSettingsPreferences(path))
                    }
                }
            }
            val stored = Properties().apply { Files.newBufferedReader(path).use(::load) }
            assertEquals(setOf("theme", "theme-follow-system", "theme-manual-brightness", "theme-family", "send-key", "wallpaper", "sticker-loop", "interface-scale", "accent-color", "font-mode"), stored.stringPropertyNames())
            assertTrue(writeSettingsPreferences(path, SettingsPreferences()))
            assertEquals(SettingsPreferences(), readSettingsPreferences(path))
            Files.list(path.parent).use { assertEquals(1L, it.count()) }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun unknownValuesFallBackIndependentlyAndMissingPreferencesDoNotCreateAFile() {
        val directory = Files.createTempDirectory("nnggram-settings-prefs-test-")
        try {
            val path = directory.resolve("ui-settings.properties")
            assertEquals(SettingsPreferences(), readSettingsPreferences(path))
            assertFalse(Files.exists(path))
            Files.writeString(path, "theme=Future\nsend-key=CTRL_ENTER\nwallpaper=NIGHT\nsticker-loop=false\n")
            assertEquals(SettingsPreferences(ColorSchemeMode.System, SettingsSendKey.CTRL_ENTER,
                SettingsWallpaper.NIGHT, false), readSettingsPreferences(path))
            Files.writeString(path, "theme=Dark\nsend-key=Future\nwallpaper=Future\nsticker-loop=perhaps\n")
            assertEquals(SettingsPreferences(theme = ColorSchemeMode.Dark), readSettingsPreferences(path))
            Files.writeString(path, "theme=Light\nsticker-loop=FALSE\n")
            assertEquals(SettingsPreferences(theme = ColorSchemeMode.Light), readSettingsPreferences(path))
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun malformedPropertiesOrUnreadableTargetsUseSafeDefaults() {
        val directory = Files.createTempDirectory("nnggram-settings-prefs-test-")
        try {
            val path = directory.resolve("ui-settings.properties")
            Files.writeString(path, "theme=Dark\nwallpaper=\\uNOT_HEX\n")
            assertEquals(SettingsPreferences(), readSettingsPreferences(path))
            assertEquals(SettingsPreferences(), readSettingsPreferences(directory))
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun interfaceScaleRetainsAutomaticAndExplicitPercentagesIndependentlyOfTheme() {
        val directory = Files.createTempDirectory("nnggram-settings-scale-test-")
        try {
            val path = directory.resolve("ui-settings.properties")
            for (percent in listOf(0, 50, 100, 125, 137, 200, 300)) {
                val expected = SettingsPreferences(theme = ColorSchemeMode.Dark, interfaceScalePercent = percent)
                assertTrue(writeSettingsPreferences(path, expected))
                assertEquals(expected, readSettingsPreferences(path))
            }
            for (bad in listOf("-1", "49", "301", "NaN", "AUTOMATIC")) {
                Files.writeString(path, "theme=Dark\nsend-key=CTRL_ENTER\ninterface-scale=$bad\n")
                assertEquals(SettingsPreferences(theme = ColorSchemeMode.Dark, sendKey = SettingsSendKey.CTRL_ENTER),
                    readSettingsPreferences(path))
            }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun everyAccentIdentityRoundTripsAndUnknownValuesRetainTheOtherPreferences() {
        val directory = Files.createTempDirectory("nnggram-settings-accent-test-")
        try {
            val path = directory.resolve("ui-settings.properties")
            for (accent in SettingsAccentColor.entries) {
                val expected = SettingsPreferences(theme = ColorSchemeMode.Dark, interfaceScalePercent = 125, accentColor = accent)
                assertTrue(writeSettingsPreferences(path, expected))
                assertEquals(expected, readSettingsPreferences(path))
            }
            Files.writeString(path, "theme=Dark\nsend-key=CTRL_ENTER\ninterface-scale=125\naccent-color=FuturePalette\n")
            assertEquals(SettingsPreferences(theme = ColorSchemeMode.Dark, sendKey = SettingsSendKey.CTRL_ENTER,
                interfaceScalePercent = 125), readSettingsPreferences(path))
            Files.writeString(path, "theme=Light\naccent-color=GREEN\n")
            assertEquals(SettingsPreferences(theme = ColorSchemeMode.Light, accentColor = SettingsAccentColor.GREEN),
                readSettingsPreferences(path))
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun invalidInterfaceScaleCannotReplaceTheExistingPreferences() {
        val directory = Files.createTempDirectory("nnggram-settings-scale-test-")
        try {
            val path = directory.resolve("ui-settings.properties")
            val existing = SettingsPreferences(interfaceScalePercent = 125)
            assertTrue(writeSettingsPreferences(path, existing))
            val previousFile = Files.readString(path)
            for (percent in listOf(-1, 49, 301, Int.MAX_VALUE)) {
                assertFalse(writeSettingsPreferences(path, existing.copy(interfaceScalePercent = percent)))
                assertEquals(previousFile, Files.readString(path))
                assertEquals(existing, readSettingsPreferences(path))
            }
            Files.list(directory).use { assertEquals(1L, it.count()) }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun failedReplacementKeepsTheExistingTargetAndRemovesTemporaryFiles() {
        val directory = Files.createTempDirectory("nnggram-settings-prefs-test-")
        try {
            val path = Files.createDirectory(directory.resolve("occupied.properties"))
            Files.writeString(path.resolve("keep.txt"), "synthetic")
            assertFalse(writeSettingsPreferences(path, SettingsPreferences(theme = ColorSchemeMode.Dark)))
            assertEquals("synthetic", Files.readString(path.resolve("keep.txt")))
            Files.list(directory).use { assertEquals(1L, it.count()) }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun fontModesRoundTripAndMissingInstalledFamilyIsNotDiscarded() {
        val directory = Files.createTempDirectory("nnggram-font-prefs-test-")
        try {
            val path = directory.resolve("ui-settings.properties")
            val fonts = listOf(SettingsChatFont(), SettingsChatFont(SettingsChatFontMode.SYSTEM_DEFAULT),
                SettingsChatFont(SettingsChatFontMode.INSTALLED, "Synthetic 字体 Not Installed"))
            for (font in fonts) {
                val expected = SettingsPreferences(theme = ColorSchemeMode.Dark, chatFont = font)
                assertTrue(writeSettingsPreferences(path, expected))
                assertEquals(expected, readSettingsPreferences(path))
            }
            assertTrue(writeSettingsPreferences(path, SettingsPreferences()))
            val stored = Properties().apply { Files.newBufferedReader(path).use(::load) }
            assertFalse(stored.containsKey("font-family"), "Returning to default must retire a stale family name.")
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun invalidFontPreferencesFallBackWithoutLosingOtherFieldsAndCannotReplaceExistingFile() {
        val directory = Files.createTempDirectory("nnggram-font-prefs-test-")
        try {
            val path = directory.resolve("ui-settings.properties")
            for (value in listOf("font-mode=FUTURE", "font-mode=INSTALLED", "font-mode=INSTALLED\nfont-family=")) {
                Files.writeString(path, "theme=Dark\nsend-key=CTRL_ENTER\n$value\n")
                assertEquals(SettingsPreferences(theme = ColorSchemeMode.Dark, sendKey = SettingsSendKey.CTRL_ENTER),
                    readSettingsPreferences(path))
            }
            val original = Files.readString(path)
            for (font in listOf(SettingsChatFont(SettingsChatFontMode.INSTALLED, " "),
                    SettingsChatFont(SettingsChatFontMode.INSTALLED, "a\nb"),
                    SettingsChatFont(SettingsChatFontMode.SYSTEM_DEFAULT, "Unexpected"))) {
                assertFalse(writeSettingsPreferences(path, SettingsPreferences(chatFont = font)))
                assertEquals(original, Files.readString(path))
            }
        } finally { directory.toFile().deleteRecursively() }
    }
}
