package com.badnng.moe.settings

enum class SettingsSecuritySection { PASSWORD, WEBSITES, BLOCKED, SESSIONS, AUTO_DELETE }
data class SettingsSecurityToken(val generation: Long = 0, val owner: Long = 0)
data class SettingsEmailCodeInfo(val pattern: String, val length: Int) {
    override fun toString() = "SettingsEmailCodeInfo(<redacted>)"
}
data class SettingsPasswordState(
    val hasPassword: Boolean, val passwordHint: String, val hasRecoveryEmail: Boolean,
    val hasPassportData: Boolean, val recoveryEmailCodeInfo: SettingsEmailCodeInfo?,
    val loginEmailPattern: String, val pendingResetDate: Int,
) {
    val loginEmailSupported: Boolean get() = loginEmailPattern.isNotBlank()
    override fun toString() = "SettingsPasswordState(<redacted>)"
}
enum class SettingsPasswordOperation {
    VERIFY_CURRENT, SET_PASSWORD, REMOVE_PASSWORD, SET_RECOVERY_EMAIL,
    CONFIRM_RECOVERY_EMAIL, RESEND_RECOVERY_EMAIL, CANCEL_RECOVERY_EMAIL,
    REQUEST_RECOVERY, VERIFY_RECOVERY_CODE, RECOVER_PASSWORD, REQUEST_RESET, CANCEL_RESET,
    SET_LOGIN_EMAIL, CONFIRM_LOGIN_EMAIL, RESEND_LOGIN_EMAIL,
}
/** Secrets exist only in this explicit request and the UI's transient form, never in a snapshot. */
data class SettingsPasswordCommand(
    val operation: SettingsPasswordOperation, val currentPassword: String = "", val newPassword: String = "",
    val hint: String = "", val email: String = "", val code: String = "", val setRecoveryEmail: Boolean = false,
) { override fun toString() = "SettingsPasswordCommand($operation, <redacted>)" }
enum class SettingsPasswordIssue {
    PASSWORD_INVALID, PASSWORD_CHANGED, CODE_INVALID, CODE_EXPIRED, EMAIL_INVALID,
    RECOVERY_UNAVAILABLE, CONFIRMATION_NOT_ACCEPTED, STATE_CHANGED,
}
enum class SettingsPasswordResetKind { OK, PENDING, DECLINED }
data class SettingsPasswordReset(val kind: SettingsPasswordResetKind, val date: Int = 0)
data class SettingsPasswordOutcome(
    val state: SettingsPasswordState? = null, val codeInfo: SettingsEmailCodeInfo? = null,
    val verified: Boolean = false, val reset: SettingsPasswordReset? = null, val issue: SettingsPasswordIssue? = null,
) { override fun toString() = "SettingsPasswordOutcome(<redacted>, issue=$issue)" }

data class SettingsWebsite(
    val id: Long, val domain: String, val botUserId: Long, val browser: String, val platform: String,
    val logInDate: Int, val lastActiveDate: Int, val ipAddress: String, val location: String,
) { override fun toString() = "SettingsWebsite(<redacted>)" }
data class SettingsWebsiteDisconnectOutcome(val disconnected: Boolean, val botBlocked: Boolean? = null)
data class SettingsBlockedPage(
    val entries: List<SettingsBlockedSender>, val nextOffset: Int, val totalCount: Int?, val end: Boolean,
) { override fun toString() = "SettingsBlockedPage(<redacted>)" }

data class SettingsBlockedPickerToken(val security: SettingsSecurityToken, val owner: Long)
enum class SettingsBlockedPeerSource { CONTACTS, CHATS, SERVER }
data class SettingsBlockedPeerQuery(
    val source: SettingsBlockedPeerSource, val query: String = "", val offset: Int = 0,
    val limit: Int = 32, val revision: Long = 0,
) { override fun toString() = "SettingsBlockedPeerQuery($source, <redacted>, revision=$revision)" }
data class SettingsBlockedCandidate(val userId: Long, val title: String, val blocked: Boolean) {
    override fun toString() = "SettingsBlockedCandidate(<redacted>)"
}
data class SettingsBlockedCandidates(
    val entries: List<SettingsBlockedCandidate>, val nextOffset: Int, val end: Boolean,
    val limitReached: Boolean = false,
) { override fun toString() = "SettingsBlockedCandidates(<redacted>)" }
data class SettingsBlockedPickerState(
    val token: SettingsBlockedPickerToken, val query: SettingsBlockedPeerQuery,
    val entries: List<SettingsBlockedCandidate> = emptyList(), val nextOffset: Int = 0,
    val loading: Boolean = false, val end: Boolean = false, val limitReached: Boolean = false,
    val error: SettingsError? = null,
) { override fun toString() = "SettingsBlockedPickerState(<redacted>)" }

data class SettingsSessionsData(val entries: List<SettingsSession>, val inactiveTtlDays: Int? = null) {
    override fun toString() = "SettingsSessionsData(<redacted>)"
}

/** Identity fields exclude lastActiveDate: activity while confirming must not fabricate a new device. */
fun SettingsSession.matchesDevice(other: SettingsSession): Boolean = id == other.id && isCurrent == other.isCurrent &&
    deviceModel == other.deviceModel && applicationName == other.applicationName && applicationVersion == other.applicationVersion &&
    platform == other.platform && systemVersion == other.systemVersion && ipAddress == other.ipAddress && location == other.location

fun sameSettingsDevices(expected: List<SettingsSession>, current: List<SettingsSession>): Boolean =
    expected.size == current.size && expected.map { it.id }.distinct().size == expected.size &&
        current.map { it.id }.distinct().size == current.size && expected.all { device -> current.any(device::matchesDevice) }

/** Only masked/native public state is exposed; no password, full email or verification code lives here. */
data class SettingsSecuritySnapshot(
    val token: SettingsSecurityToken = SettingsSecurityToken(), val section: SettingsSecuritySection? = null,
    val loading: Boolean = false, val busy: Boolean = false, val error: SettingsError? = null,
    val password: SettingsPasswordState? = null,
    val websites: List<SettingsWebsite> = emptyList(), val websitesTotalCount: Int? = null,
    val blocked: List<SettingsBlockedSender> = emptyList(), val blockedTotal: Int? = null,
    val blockedEnd: Boolean = false, val blockedLoadingMore: Boolean = false, val blockedLimitReached: Boolean = false,
    val blockedPicker: SettingsBlockedPickerState? = null,
    val sessions: List<SettingsSession> = emptyList(),
    val inactiveSessionTtlDays: Int? = null,
    val defaultMessageAutoDeleteSeconds: Int? = null,
    val sessionsRefreshRequired: Boolean = false,
    val defaultAutoDeleteRefreshRequired: Boolean = false,
) { override fun toString() = "SettingsSecuritySnapshot(<redacted>, section=$section)" }
