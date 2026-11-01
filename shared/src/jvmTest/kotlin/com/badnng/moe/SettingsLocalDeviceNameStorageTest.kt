package com.badnng.moe

import com.badnng.moe.tdlib.ConfigurationStore
import com.badnng.moe.tdlib.DataDirectoryLease
import com.badnng.moe.tdlib.OfficialTdSession
import com.badnng.moe.tdlib.TdConfiguration
import java.io.IOException
import java.nio.file.Files
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.drinkless.tdlib.TdApi
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

class SettingsLocalDeviceNameStorageTest {
    @Test fun cleaningAndUtf16BoundaryPreserveUnicodeAndRejectInvalidSavedValues() {
        assertEquals("A B C D", cleanSettingsDeviceName(" \tA\u0001 B\u00A0\u0085C\nD  "))
        assertEquals("", cleanSettingsDeviceName("\t \n"))
        assertTrue(validSettingsDeviceName("A".repeat(30) + "😀"))
        assertFalse(validSettingsDeviceName("A".repeat(31) + "😀"))
        assertFalse(validSettingsDeviceName("broken\uD83D"))
        assertFalse(validSettingsDeviceName("broken\uDE00"))
        assertFalse(validSettingsDeviceName("two  spaces"))
        assertFalse(validSettingsDeviceName(" padded "))
        assertEquals("Desktop", settingsEffectiveDeviceName(""))
        assertEquals("Desktop", settingsEffectiveDeviceName("broken\uD83D"))
    }

    @Test fun realAtomicFileRetainsUnrelatedPreferencesAndFailureKeepsOldBytes() {
        val root = Files.createTempDirectory("device-name-prefs-")
        try {
            val path = root.resolve("ui-settings.properties")
            val old = SettingsPreferences(theme = ColorSchemeMode.Dark, sendKey = SettingsSendKey.CTRL_ENTER,
                wallpaper = SettingsWallpaper.NIGHT, interfaceScalePercent = 125, customDeviceModel = "Old Laptop")
            assertTrue(writeSettingsPreferences(path, old))
            val before = Files.readString(path)
            assertFalse(writeSettingsPreferences(path, old.copy(customDeviceModel = "New Laptop"),
                atomicReplace = { _, _ -> throw IOException("synthetic failure") }))
            assertEquals(before, Files.readString(path))
            assertEquals(old, readSettingsPreferences(path))
            assertFalse(writeSettingsPreferences(path, old.copy(customDeviceModel = "x".repeat(33))))
            assertEquals(before, Files.readString(path))
            var replaceCalled = false
            assertFalse(writeSettingsPreferencesIfCurrent(path, old.copy(customDeviceModel = "Revoked"),
                current = { false }, atomicReplace = { _, _ -> replaceCalled = true }))
            assertFalse(replaceCalled, "Revocation is checked immediately before the atomic move")
            assertEquals(before, Files.readString(path))
            assertTrue(writeSettingsPreferences(path, old.copy(customDeviceModel = "New Laptop")))
            assertEquals(old.copy(customDeviceModel = "New Laptop"), readSettingsPreferences(path))
            val values = Properties().apply { Files.newBufferedReader(path).use(::load) }
            assertEquals("New Laptop", values.getProperty("custom-device-model"))
            assertFalse(values.stringPropertyNames().any { it.contains("api") || it.contains("password") })
            assertTrue(writeSettingsPreferences(path, old.copy(customDeviceModel = "")))
            assertFalse(Properties().apply { Files.newBufferedReader(path).use(::load) }.containsKey("custom-device-model"))
            Files.list(root).use { assertEquals(1L, it.count()) }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun damagedNameFallsBackIndependentlyWithoutCreatingAFile() {
        val root = Files.createTempDirectory("device-name-damaged-")
        try {
            val path = root.resolve("ui-settings.properties")
            assertEquals("", readSettingsPreferences(path).customDeviceModel)
            assertFalse(Files.exists(path))
            for (bad in listOf("too-long-" + "x".repeat(33), " two-spaces  inside ", "\\uD83D")) {
                Files.writeString(path, "theme=Dark\nsend-key=CTRL_ENTER\ncustom-device-model=$bad\n")
                val read = readSettingsPreferences(path)
                assertEquals("", read.customDeviceModel)
                assertEquals(ColorSchemeMode.Dark, read.theme)
                assertEquals(SettingsSendKey.CTRL_ENTER, read.sendKey)
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun actualConfigurationSnapshotFeedsTypedParametersFromTheSameLocalFileOnTheNextRead() {
        val root = Files.createTempDirectory("device-name-parameters-")
        try {
            val configPath = root.resolve("local.properties")
            Files.writeString(configPath, "api_id=12345\napi_hash=${"a".repeat(32)}\n")
            val store = ConfigurationStore(configPath, root.resolve("data"))
            val first = assertNotNull(store.read())
            assertEquals("Desktop", first.deviceModel)
            val preferencesPath = root.resolve("ui-settings.properties")
            assertTrue(writeSettingsPreferences(preferencesPath, SettingsPreferences(customDeviceModel = "Office Laptop")))
            val second = assertNotNull(store.read())
            assertEquals("Office Laptop", second.deviceModel)
            val requests = mutableListOf<TdApi.Function<*>>()
            val session = OfficialTdSession(false) { request, _, _ -> requests += request }
            DataDirectoryLease.acquire(root.resolve("data")).use { lease ->
                session.setParameters(first, lease) {}
                session.setParameters(second, lease) {}
                val parameters = requests.filterIsInstance<TdApi.SetTdlibParameters>()
                assertEquals(listOf("Desktop", "Office Laptop"), parameters.map { it.deviceModel })
                val invalidDirect = TdConfiguration(12345, "a".repeat(32), root, deviceModel = "\uD800")
                session.setParameters(invalidDirect, lease) {}
                assertEquals("Desktop", assertIs<TdApi.SetTdlibParameters>(requests.last()).deviceModel)
            }
            Files.writeString(preferencesPath, "custom-device-model=${"x".repeat(33)}\n")
            assertEquals("Desktop", assertNotNull(store.read()).deviceModel)
        } finally { root.toFile().deleteRecursively() }
    }
}
