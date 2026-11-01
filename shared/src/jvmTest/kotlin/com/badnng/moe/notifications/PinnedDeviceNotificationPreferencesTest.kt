package com.badnng.moe.notifications

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PinnedDeviceNotificationPreferencesTest {
    @Test fun pinnedPreferenceRoundTripsWithoutReplacingAnyOtherDevicePreference() {
        val directory = Files.createTempDirectory("pinned-device-preferences-")
        val path = directory.resolve("prefs.properties")
        try {
            val store = FileDeviceNotificationPreferencesStore(path)
            val value = DeviceNotificationPreferences(
                desktopEnabled = true,
                soundEnabled = false,
                flashEnabled = true,
                volumePercent = 37,
                showSender = false,
                showPreview = false,
                maximumVisible = 5,
                corner = DeviceNotificationCorner.TOP_LEFT,
                unreadMode = UnreadCounterMode.CHATS,
                includeMuted = false,
                includeMutedFolders = false,
                useWindowsNotifications = true,
                respectWindowsFocusAssist = false,
                notifyAboutPinned = false,
            )

            assertTrue(store.save(value))
            assertEquals(value, store.load())
            val properties = java.util.Properties().apply {
                Files.newBufferedReader(path).use(::load)
            }
            assertEquals("false", properties.getProperty("notify-about-pinned"))
        } finally {
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
    }

    @Test fun legacyMissingAndMalformedPinnedKeysDefaultToEnabledAndKeepOtherStoredValues() {
        val directory = Files.createTempDirectory("pinned-device-legacy-preferences-")
        val path = directory.resolve("prefs.properties")
        try {
            val store = FileDeviceNotificationPreferencesStore(path)
            Files.writeString(path, "desktop=true\nsound=false\n")
            val missing = store.load()
            assertTrue(missing.notifyAboutPinned)
            assertTrue(missing.desktopEnabled)
            assertFalse(missing.soundEnabled)

            Files.writeString(path, "desktop=false\nnotify-about-pinned=perhaps\n")
            val malformed = store.load()
            assertTrue(malformed.notifyAboutPinned)
            assertFalse(malformed.desktopEnabled)

            Files.writeString(path, "notify-about-pinned=false\n")
            assertFalse(store.load().notifyAboutPinned)
        } finally {
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
    }

    @Test fun failedAtomicSaveLeavesAnExistingDestinationUntouched() {
        val directory = Files.createTempDirectory("pinned-device-save-failure-")
        val blockingFile = directory.resolve("not-a-directory")
        try {
            Files.writeString(blockingFile, "keep existing preferences")
            val store = FileDeviceNotificationPreferencesStore(blockingFile.resolve("prefs.properties"))

            assertFalse(store.save(DeviceNotificationPreferences(notifyAboutPinned = false)))
            assertEquals("keep existing preferences", Files.readString(blockingFile))
        } finally {
            Files.deleteIfExists(blockingFile)
            Files.deleteIfExists(directory)
        }
    }
}
