package com.badnng.moe.settings

import com.badnng.moe.notifications.DeviceNotificationActions

/** Defaults are inert previews. A live host supplies actor-owned native operations. */
data class SettingsActions(
    val profileExtras: SettingsProfileExtrasActions = SettingsProfileExtrasActions(),
    val privacyCommon: PrivacyCommonActions = PrivacyCommonActions(),
    val load: (SettingsToken, SettingsSection) -> Unit = { _, _ -> },
    val saveProfile: (SettingsToken, SettingsProfileEdit) -> Unit = { _, _ -> },
    val saveNotifications: (SettingsToken, NotificationScope, NotificationPreferences) -> Unit = { _, _, _ -> },
    val savePrivacy: (SettingsToken, PrivacyKey, PrivacyBase) -> Unit = { _, _, _ -> },
    val openPrivacyEditor: (SettingsToken, PrivacyKey, Long, (SettingsResult<PrivacyEditorToken>) -> Unit) -> Unit =
        { _, _, _, result -> result(SettingsResult(error = SettingsError.NOT_READY)) },
    val cancelPrivacyEditorOpening: (SettingsToken, Long) -> Unit = { _, _ -> },
    val closePrivacyEditor: (SettingsToken, PrivacyEditorToken) -> Unit = { _, _ -> },
    val privacyPeers: (SettingsToken, PrivacyEditorToken, PrivacyPeerQuery, (SettingsResult<SettingsPrivacyPeers>) -> Unit) -> Unit =
        { _, _, _, result -> result(SettingsResult(error = SettingsError.NOT_READY)) },
    val savePrivacyDraft: (SettingsToken, PrivacyEditorToken, PrivacyDraft) -> Unit = { _, _, _ -> },
    val saveAccountTtl: (SettingsToken, Int) -> Unit = { _, _ -> },
    val terminateSession: (SettingsToken, Long) -> Unit = { _, _ -> },
    val unblock: (SettingsToken, SettingsBlockedSender) -> Unit = { _, _ -> },
    val optimizeStorage: (SettingsToken) -> Unit = {},
    val addProxy: (SettingsToken, SettingsProxyDraft) -> Unit = { _, _ -> },
    val enableProxy: (SettingsToken, Int?) -> Unit = { _, _ -> },
    val removeProxy: (SettingsToken, Int) -> Unit = { _, _ -> },
    val openSecurity: (SettingsToken, SettingsSecuritySection) -> Unit = { _, _ -> },
    val closeSecurity: (SettingsSecurityToken) -> Unit = {},
    val refreshSecurity: (SettingsSecurityToken) -> Unit = {},
    val password: (SettingsSecurityToken, SettingsPasswordCommand, (SettingsResult<SettingsPasswordOutcome>) -> Unit) -> Unit =
        { _, _, result -> result(SettingsResult(error = SettingsError.NOT_READY)) },
    val disconnectWebsite: (SettingsSecurityToken, SettingsWebsite, Boolean, (SettingsResult<SettingsWebsiteDisconnectOutcome>) -> Unit) -> Unit =
        { _, _, _, result -> result(SettingsResult(error = SettingsError.NOT_READY)) },
    val disconnectAllWebsites: (SettingsSecurityToken, (SettingsResult<Unit>) -> Unit) -> Unit =
        { _, result -> result(SettingsResult(error = SettingsError.NOT_READY)) },
    val openBlockedPicker: (SettingsSecurityToken) -> Unit = {},
    val closeBlockedPicker: (SettingsBlockedPickerToken) -> Unit = {},
    val searchBlockedPicker: (SettingsBlockedPickerToken, SettingsBlockedPeerQuery) -> Unit = { _, _ -> },
    val blockPickerUser: (SettingsBlockedPickerToken, Long, (SettingsResult<Unit>) -> Unit) -> Unit =
        { _, _, result -> result(SettingsResult(error = SettingsError.NOT_READY)) },
    val loadMoreBlocked: (SettingsSecurityToken) -> Unit = {},
    val unblockSecurity: (SettingsSecurityToken, SettingsBlockedSender, (SettingsResult<Unit>) -> Unit) -> Unit =
        { _, _, result -> result(SettingsResult(error = SettingsError.NOT_READY)) },
    val terminateSecuritySession: (SettingsSecurityToken, Long, (SettingsResult<Unit>) -> Unit) -> Unit =
        { _, _, result -> result(SettingsResult(error = SettingsError.NOT_READY)) },
    val terminateOtherSecuritySessions: (SettingsSecurityToken, List<SettingsSession>, (SettingsResult<Unit>) -> Unit) -> Unit =
        { _, _, result -> result(SettingsResult(error = SettingsError.NOT_READY)) },
    val saveInactiveSessionTtl: (SettingsSecurityToken, Int, Int, (SettingsResult<Unit>) -> Unit) -> Unit =
        { _, _, _, result -> result(SettingsResult(error = SettingsError.NOT_READY)) },
    val saveDefaultMessageAutoDeleteTime: (SettingsSecurityToken, Int, Int, (SettingsResult<Unit>) -> Unit) -> Unit =
        { _, _, _, result -> result(SettingsResult(error = SettingsError.NOT_READY)) },
    val deviceNotifications: DeviceNotificationActions = DeviceNotificationActions(),
    val storage: SettingsStorageActions = SettingsStorageActions(),
    val loginEmail: LoginEmailActions = LoginEmailActions(),
    val storagePolicy: SettingsStoragePolicyActions = SettingsStoragePolicyActions(),
    val support: SettingsSupportActions = SettingsSupportActions(),
    val contactSignup: ContactSignupActions = ContactSignupActions(),
    val notificationExceptions: NotificationExceptionsActions = NotificationExceptionsActions(),
    val savedNotificationSounds: SavedNotificationSoundsActions = SavedNotificationSoundsActions(),
    val profileColors: SettingsProfileColorsActions = SettingsProfileColorsActions(),
    val reactionNotifications: ReactionNotificationsActions = ReactionNotificationsActions(),
    /** Read-only hint for one live editor/query. Saving still uses its own native result. */
    val checkUsername: (SettingsToken, Long, String, () -> Boolean, (SettingsResult<SettingsUsernameAvailability>) -> Unit) -> Unit =
        { _, _, _, _, result -> result(SettingsResult(error = SettingsError.UNSUPPORTED)) },
    /** A live owner witness; default previews cannot commit a device-name preference. */
    val captureDeviceNameSecurityOwner: (SettingsSecurityToken, SettingsSession) -> (() -> Boolean) = { _, _ -> { false } },
    val proxyChecks: SettingsProxyChecksActions = SettingsProxyChecksActions(),
    val localLock: LocalLockActions = LocalLockActions(),
)
