package com.badnng.moe.notifications

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties

internal interface DeviceNotificationPreferencesStore {
    fun load(): DeviceNotificationPreferences
    fun save(value: DeviceNotificationPreferences): Boolean
}

internal class FileDeviceNotificationPreferencesStore(private val path: Path) : DeviceNotificationPreferencesStore {
    override fun load(): DeviceNotificationPreferences {
        if (!Files.isRegularFile(path)) return DeviceNotificationPreferences()
        val values = Properties().apply { Files.newBufferedReader(path).use(::load) }
        fun flag(key: String, default: Boolean) = values.getProperty(key)?.toBooleanStrictOrNull() ?: default
        val defaults = DeviceNotificationPreferences()
        return defaults.copy(
            desktopEnabled = flag("desktop", defaults.desktopEnabled),
            soundEnabled = flag("sound", defaults.soundEnabled),
            flashEnabled = flag("flash", defaults.flashEnabled),
            volumePercent = values.getProperty("volume")?.toIntOrNull()?.takeIf { it in 0..100 } ?: defaults.volumePercent,
            showSender = flag("sender", defaults.showSender),
            showPreview = flag("preview", defaults.showPreview),
            maximumVisible = values.getProperty("maximum-visible")?.toIntOrNull()?.takeIf { it in 1..5 } ?: defaults.maximumVisible,
            corner = DeviceNotificationCorner.entries.firstOrNull { it.name == values.getProperty("corner") } ?: defaults.corner,
            unreadMode = UnreadCounterMode.entries.firstOrNull { it.name == values.getProperty("unread-mode") } ?: defaults.unreadMode,
            includeMuted = flag("include-muted", defaults.includeMuted),
            includeMutedFolders = flag("include-muted-folders", defaults.includeMutedFolders),
            notifyAboutPinned = flag("notify-about-pinned", defaults.notifyAboutPinned),
            useWindowsNotifications = flag("windows-native", defaults.useWindowsNotifications),
            respectWindowsFocusAssist = flag("windows-focus-assist", defaults.respectWindowsFocusAssist),
        )
    }

    override fun save(value: DeviceNotificationPreferences): Boolean {
        if (!value.valid) return false
        var temporary: Path? = null
        return try {
            val destination = path.toAbsolutePath()
            Files.createDirectories(destination.parent)
            temporary = Files.createTempFile(destination.parent, "device-notifications-", ".tmp")
            val values = Properties().apply {
                setProperty("version", "1")
                setProperty("desktop", value.desktopEnabled.toString())
                setProperty("sound", value.soundEnabled.toString())
                setProperty("flash", value.flashEnabled.toString())
                setProperty("volume", value.volumePercent.toString())
                setProperty("sender", value.showSender.toString())
                setProperty("preview", value.showPreview.toString())
                setProperty("maximum-visible", value.maximumVisible.toString())
                setProperty("corner", value.corner.name)
                setProperty("unread-mode", value.unreadMode.name)
                setProperty("include-muted", value.includeMuted.toString())
                setProperty("include-muted-folders", value.includeMutedFolders.toString())
                setProperty("notify-about-pinned", value.notifyAboutPinned.toString())
                setProperty("windows-native", value.useWindowsNotifications.toString())
                setProperty("windows-focus-assist", value.respectWindowsFocusAssist.toString())
            }
            Files.newBufferedWriter(temporary).use { values.store(it, "Local device notifications; no account content") }
            try { Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING) }
            true
        } catch (_: Exception) { false }
        finally { temporary?.let { try { Files.deleteIfExists(it) } catch (_: Exception) { } } }
    }
}
