package com.badnng.moe

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

/** These use isolated temporary paths and the real preferences codec/writer. */
class SettingsThemePreferencesStorageTest {
    @Test fun legacyFilesAreReadWithoutWritesAndRoundTripEveryOtherPreference() {
        val root = Files.createTempDirectory("theme-storage-legacy-")
        try {
            for ((legacy, follow, manual) in listOf("System" to (true to false), "Light" to (false to false),
                "Dark" to (false to true)).map { Triple(it.first, it.second.first, it.second.second) }) {
                val path = root.resolve("$legacy.properties")
                Files.writeString(path, "theme=$legacy\nsend-key=CTRL_ENTER\nwallpaper=NIGHT\nsticker-loop=false\n" +
                    "interface-scale=125\naccent-color=GREEN\nfont-mode=INSTALLED\nfont-family=Synthetic Font\n")
                val before = Files.readAllBytes(path)
                val value = readSettingsPreferences(path)
                assertContentEquals(before, Files.readAllBytes(path))
                assertEquals(follow, value.themeStatePreferences().followSystem)
                assertEquals(manual, value.themeStatePreferences().manualDark)
                assertEquals(SettingsAccentColor.GREEN, value.accentColor)
                assertEquals(SettingsSendKey.CTRL_ENTER, value.sendKey)
                assertEquals(SettingsWallpaper.NIGHT, value.wallpaper)
                assertEquals(125, value.interfaceScalePercent)
                assertEquals(SettingsChatFont(SettingsChatFontMode.INSTALLED, "Synthetic Font"), value.chatFont)
                assertFalse(value.stickerLoop)
                assertTrue(writeSettingsPreferences(path, value))
                assertEquals(value, readSettingsPreferences(path))
                val stored = Properties().apply { Files.newBufferedReader(path).use(::load) }
                assertEquals(legacy, stored.getProperty("theme"))
                assertEquals(follow.toString(), stored.getProperty("theme-follow-system"))
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun allFamiliesAndUnknownFallbackSurviveTheRealFileCodec() {
        val root = Files.createTempDirectory("theme-storage-family-")
        try {
            val path = root.resolve("preferences.properties")
            for (follow in listOf(false, true)) for (manualDark in listOf(false, true))
                for (family in SettingsThemeFamily.entries) {
                    val target = SettingsThemePreferences(follow, manualDark, family)
                    val value = SettingsPreferences(theme = ColorSchemeMode.System, accentColor = SettingsAccentColor.PURPLE,
                        wallpaper = SettingsWallpaper.DAY, sendKey = SettingsSendKey.CTRL_ENTER).withThemeState(target)
                    assertTrue(writeSettingsPreferences(path, value))
                    val read = readSettingsPreferences(path)
                    assertEquals(value, read)
                    assertEquals(target, read.themeStatePreferences())
                    assertEquals(SettingsAccentColor.PURPLE, read.accentColor)
                    assertEquals(SettingsWallpaper.DAY, read.wallpaper)
                }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun unsupportedAtomicReplacementDoesNotReplaceOrDamageExistingFile() {
        val root = Files.createTempDirectory("theme-storage-atomic-")
        try {
            val path = root.resolve("preferences.properties")
            val old = SettingsPreferences(theme = ColorSchemeMode.Light, accentColor = SettingsAccentColor.RED)
            assertTrue(writeSettingsPreferences(path, old))
            val before = Files.readAllBytes(path)
            val next = old.withThemeState(SettingsThemePreferences(false, true, SettingsThemeFamily.MONET))
            assertFalse(writeSettingsPreferences(path, next) { source, destination ->
                throw AtomicMoveNotSupportedException(source.toString(), destination.toString(), "synthetic unsupported")
            })
            assertContentEquals(before, Files.readAllBytes(path))
            assertEquals(old, readSettingsPreferences(path))
            Files.list(root).use { assertEquals(1L, it.count(), "Failed temporary file must be removed") }
        } finally { root.toFile().deleteRecursively() }
    }
}
