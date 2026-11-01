package com.badnng.moe.settings

import com.badnng.moe.chat.MediaFile

/** Account settings live in memory and never enter diagnostics or local preference files. */
enum class SettingsSection { PROFILE, NOTIFICATIONS, PRIVACY, ADVANCED }
data class SettingsToken(val generation: Long = 0, val revision: Long = 0)
enum class SettingsError { NOT_READY, REQUEST_FAILED, RESULT_UNKNOWN, FORBIDDEN, RATE_LIMITED, INVALID_VALUE, UNSUPPORTED, CONFLICT }
data class SettingsResult<T>(val value: T? = null, val error: SettingsError? = null) {
    override fun toString() = "SettingsResult(<redacted>, error=$error)"
}

data class SettingsProfile(
    val userId: Long, val firstName: String, val lastName: String, val username: String,
    val bio: String, val phoneNumber: String, val avatar: MediaFile? = null,
    val bioLengthMax: Int? = null,
) { override fun toString() = "SettingsProfile(<redacted>)" }
enum class ProfileField { NAME, USERNAME, BIO }
/** One native mutation per explicit save, so a form cannot claim an atomic multi-field write. */
data class SettingsProfileEdit(val field: ProfileField, val value: String, val secondary: String = "") {
    override fun toString() = "SettingsProfileEdit(<redacted>)"
}

enum class NotificationScope { PRIVATE_CHATS, GROUP_CHATS, CHANNEL_CHATS }
data class NotificationPreferences(
    val muteFor: Int = 0, val soundId: Long = -1, val showPreview: Boolean = true,
    val useDefaultMuteStories: Boolean = true, val muteStories: Boolean = false,
    val storySoundId: Long = -1, val showStoryPoster: Boolean = true,
    val disablePinnedMessageNotifications: Boolean = false, val disableMentionNotifications: Boolean = false,
) {
    val enabled: Boolean get() = muteFor == 0
    val soundEnabled: Boolean get() = soundId != 0L
}

enum class PrivacyKey {
    PHONE_NUMBER, FIND_BY_PHONE_NUMBER, LAST_SEEN, PROFILE_PHOTO, FORWARDED_LINK,
    CALLS, PEER_TO_PEER_CALLS, VOICE_VIDEO_MESSAGES, BIO, BIRTHDATE, CHAT_INVITES,
}
enum class PrivacyBase { EVERYBODY, CONTACTS, NOBODY }
enum class PrivacyRuleKind {
    ALLOW_ALL, ALLOW_CONTACTS, ALLOW_BOTS, ALLOW_PREMIUM, ALLOW_USERS, ALLOW_CHAT_MEMBERS,
    RESTRICT_ALL, RESTRICT_CONTACTS, RESTRICT_BOTS, RESTRICT_USERS, RESTRICT_CHAT_MEMBERS,
}
/** Order and IDs are preserved exactly. Editing the base never rewrites native exceptions. */
data class PrivacyRule(val kind: PrivacyRuleKind, val ids: List<Long> = emptyList()) {
    override fun toString() = "PrivacyRule($kind, <redacted>)"
}
data class PrivacyRules(val base: PrivacyBase?, val rules: List<PrivacyRule>, val editable: Boolean = true) {
    val exceptionCount: Int get() = rules.sumOf { if (it.ids.isNotEmpty()) it.ids.size else when (it.kind) {
        PrivacyRuleKind.ALLOW_BOTS, PrivacyRuleKind.ALLOW_PREMIUM, PrivacyRuleKind.RESTRICT_CONTACTS,
        PrivacyRuleKind.RESTRICT_BOTS -> 1
        else -> 0
    } }
    override fun toString() = "PrivacyRules(<redacted>)"
}

data class SettingsSession(
    val id: Long, val isCurrent: Boolean, val deviceModel: String, val applicationName: String,
    val applicationVersion: String, val platform: String, val systemVersion: String,
    val lastActiveDate: Int, val ipAddress: String, val location: String,
    val logInDate: Int = 0, val isPasswordPending: Boolean = false, val isUnconfirmed: Boolean = false,
) { override fun toString() = "SettingsSession(<redacted>)" }
data class SettingsBlockedSender(val id: Long, val isChat: Boolean, val title: String = "") {
    override fun toString() = "SettingsBlockedSender(<redacted>)"
}
data class SettingsStorage(val totalSize: Long, val totalCount: Int, val databaseSize: Long = 0)
enum class SettingsProxyType { SOCKS5, HTTP, MTPROTO, WEB }
data class SettingsProxy(
    val id: Int, val server: String, val port: Int, val type: SettingsProxyType, val enabled: Boolean,
) { override fun toString() = "SettingsProxy(<redacted>)" }
data class SettingsProxyDraft(
    val server: String, val port: Int, val type: SettingsProxyType,
    val username: String = "", val password: String = "", val secret: String = "",
) { override fun toString() = "SettingsProxyDraft(<redacted>)" }

data class SettingsSnapshot(
    val token: SettingsToken = SettingsToken(), val enabled: Boolean = false,
    val loading: Set<SettingsSection> = emptySet(), val saving: Set<SettingsSection> = emptySet(),
    val loaded: Set<SettingsSection> = emptySet(), val errors: Map<SettingsSection, SettingsError> = emptyMap(),
    val completedSaveRevision: Map<SettingsSection, Long> = emptyMap(),
    val startedSaveRevision: Map<SettingsSection, Long> = emptyMap(),
    val failedSaveRevision: Map<SettingsSection, Long> = emptyMap(),
    val profile: SettingsProfile? = null,
    val notifications: Map<NotificationScope, NotificationPreferences> = emptyMap(),
    val privacy: Map<PrivacyKey, PrivacyRules> = emptyMap(),
    val privacyErrors: Map<PrivacyKey, SettingsError> = emptyMap(),
    val accountTtlDays: Int? = null, val sessions: List<SettingsSession> = emptyList(),
    val blocked: List<SettingsBlockedSender> = emptyList(), val blockedHasMore: Boolean = false,
    val storage: SettingsStorage? = null, val proxies: List<SettingsProxy> = emptyList(),
    val security: SettingsSecuritySnapshot = SettingsSecuritySnapshot(),
    val profileExtras: SettingsProfileExtrasSnapshot = SettingsProfileExtrasSnapshot(),
    val privacyCommon: PrivacyCommonSnapshot = PrivacyCommonSnapshot(),
    val contactSignup: ContactSignupSnapshot = ContactSignupSnapshot(),
    val notificationExceptions: NotificationExceptionsSnapshot = NotificationExceptionsSnapshot(),
    val savedNotificationSounds: SavedNotificationSoundsSnapshot = SavedNotificationSoundsSnapshot(),
    val profileColors: SettingsProfileColorsSnapshot = SettingsProfileColorsSnapshot(),
    val reactionNotifications: ReactionNotificationsSnapshot = ReactionNotificationsSnapshot(),
    val proxyChecks: SettingsProxyChecksSnapshot = SettingsProxyChecksSnapshot(),
) { override fun toString() = "SettingsSnapshot(<redacted>, enabled=$enabled)" }
