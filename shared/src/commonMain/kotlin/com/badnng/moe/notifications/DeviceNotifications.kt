package com.badnng.moe.notifications

import com.badnng.moe.chat.ChatListRef

enum class UnreadCounterMode { MESSAGES, CHATS }
enum class DeviceNotificationCorner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }
enum class DeviceNotificationCause { ORDINARY, PINNED }
enum class DeviceNotificationGroupKind { MESSAGES, MENTIONS }

/** A real target chat's ordinary policy, independent of a special mention sender's settings. */
data class DeviceOrdinaryNotificationPolicy(val muted: Boolean, val soundId: Long, val showPreview: Boolean)
enum class DeviceNotificationError { SAVE_FAILED, MODE_COMMITTED_SAVE_FAILED, NATIVE_FAILED, CUSTOM_SOUND_UNAVAILABLE, OUTPUT_FAILED, SOUND_VOLUME_UNAVAILABLE }
enum class WindowsDeviceNotificationStatus { UNAVAILABLE, DISABLED, REGISTERED, ENABLING, ENABLED, FAILED, CONFLICT }
data class WindowsDeviceNotificationState(
    val status: WindowsDeviceNotificationStatus = WindowsDeviceNotificationStatus.UNAVAILABLE,
    val registered: Boolean = false,
    val executable: String? = null,
    val focusBlocked: Boolean? = null,
) {
    override fun toString() = "WindowsDeviceNotificationState(status=$status, registered=$registered)"
}

/** Device-only preferences. Outputs are opt-in; none is an account scope setting. */
data class DeviceNotificationPreferences(
    val desktopEnabled: Boolean = false,
    val soundEnabled: Boolean = false,
    val flashEnabled: Boolean = false,
    val volumePercent: Int = 100,
    val showSender: Boolean = true,
    val showPreview: Boolean = true,
    val maximumVisible: Int = 3,
    val corner: DeviceNotificationCorner = DeviceNotificationCorner.BOTTOM_RIGHT,
    val unreadMode: UnreadCounterMode = UnreadCounterMode.MESSAGES,
    val includeMuted: Boolean = true,
    val includeMutedFolders: Boolean = true,
    val useWindowsNotifications: Boolean = false,
    val respectWindowsFocusAssist: Boolean = true,
    val notifyAboutPinned: Boolean = true,
) {
    val valid: Boolean get() = volumePercent in 0..100 && maximumVisible in 1..5
    val needsNativeGroups: Boolean get() = desktopEnabled || soundEnabled || flashEnabled
}

data class DeviceNotificationIdentity(val generation: Long, val groupId: Int, val notificationId: Int)

/** Private notification content belongs to the current engine generation, never diagnostics. */
data class DeviceNotificationItem(
    val id: Int,
    val date: Int,
    val messageId: Long,
    val title: String,
    val text: String,
    val silent: Boolean,
    val nativeShowPreview: Boolean,
    val canOpenMessage: Boolean,
    val cause: DeviceNotificationCause = DeviceNotificationCause.ORDINARY,
) {
    override fun toString(): String = "DeviceNotificationItem(<redacted>)"
}

data class DeviceNotificationGroup(
    val id: Int,
    val chatId: Long,
    val totalCount: Int,
    val settingsChatId: Long = chatId,
    val soundId: Long = 0,
    val items: List<DeviceNotificationItem> = emptyList(),
    val kind: DeviceNotificationGroupKind = DeviceNotificationGroupKind.MESSAGES,
) {
    override fun toString(): String = "DeviceNotificationGroup(<redacted>)"
}

data class DeviceNotificationCard(
    val identity: DeviceNotificationIdentity,
    val title: String,
    val text: String,
    val date: Int,
    /** Restored metadata may add history beside a real arrival; only that history suppresses a popup. */
    val quiet: Boolean = false,
) {
    override fun toString(): String = "DeviceNotificationCard(<redacted>)"
}

/** Null fields remain unknown until the corresponding native aggregate update arrives. */
data class DeviceUnreadCounts(
    val messages: Int? = null,
    val unmutedMessages: Int? = null,
    val chats: Int? = null,
    val unmutedChats: Int? = null,
    val markedOnlyChats: Int? = null,
    val unmutedMarkedOnlyChats: Int? = null,
) {
    fun count(mode: UnreadCounterMode, includeMuted: Boolean): Int? {
        if (mode == UnreadCounterMode.CHATS) return if (includeMuted) chats else unmutedChats
        val messages = (if (includeMuted) messages else unmutedMessages) ?: return null
        val marks = (if (includeMuted) markedOnlyChats else unmutedMarkedOnlyChats) ?: return null
        return (messages.toLong() + marks).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}

data class DeviceNotificationsSnapshot(
    val generation: Long = 0,
    val enabled: Boolean = false,
    val preferences: DeviceNotificationPreferences = DeviceNotificationPreferences(),
    val nativeGroupsEnabled: Boolean = false,
    val error: DeviceNotificationError? = null,
    val cards: List<DeviceNotificationCard> = emptyList(),
    val unread: Map<ChatListRef, DeviceUnreadCounts> = emptyMap(),
    val windows: WindowsDeviceNotificationState = WindowsDeviceNotificationState(),
    val openChatRevision: Long = 0,
    val modeSavePending: Boolean = false,
) {
    /** Folders always count chats independently of the account's message/chat mode. */
    fun folderBadge(list: ChatListRef): Int? = unread[list]?.count(UnreadCounterMode.CHATS, preferences.includeMutedFolders)
    fun listBadge(list: ChatListRef): Int? = unread[list]?.count(preferences.unreadMode, preferences.includeMuted)
    /** Main/archive are disjoint. Custom folders overlap and must never enter this sum. */
    fun accountBadge(): Int? {
        val main = listBadge(ChatListRef()) ?: return null
        val archive = listBadge(ChatListRef(-1)) ?: return null
        return (main.toLong() + archive).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}

sealed interface DeviceNotificationEvent {
    data class PolicyChanged(val chatId: Long? = null) : DeviceNotificationEvent {
        override fun toString() = "DeviceNotificationEvent.PolicyChanged(<redacted>)"
    }
    data class Active(val groups: List<DeviceNotificationGroup>) : DeviceNotificationEvent
    data class Group(val group: DeviceNotificationGroup, val removedIds: List<Int>) : DeviceNotificationEvent
    data class Replace(val groupId: Int, val item: DeviceNotificationItem) : DeviceNotificationEvent
    data class Messages(val list: ChatListRef, val count: Int, val unmuted: Int) : DeviceNotificationEvent
    data class Chats(val list: ChatListRef, val count: Int, val unmuted: Int,
        val markedOnly: Int, val unmutedMarkedOnly: Int) : DeviceNotificationEvent
}

data class DeviceNotificationActions(
    val save: (Long, DeviceNotificationPreferences) -> Unit = { _, _ -> },
    val activate: (DeviceNotificationIdentity) -> Unit = {},
    val dismiss: (DeviceNotificationIdentity) -> Unit = {},
    /** Expiration only hides local artwork; it is not a user native dismissal. */
    val expire: (DeviceNotificationIdentity) -> Unit = {},
    val context: (Long, Boolean, Long?, Boolean) -> Unit = { _, _, _, _ -> },
    val windowsNotifications: (Long, Boolean) -> Unit = { _, _ -> },
    val removeWindowsRegistration: (Long) -> Unit = {},
)
