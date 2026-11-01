package com.badnng.moe.status

import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.settings.SettingsSnapshot
import com.badnng.moe.settings.SettingsStorageSnapshot
import com.badnng.moe.settings.LoginEmailSnapshot
import com.badnng.moe.settings.SettingsStoragePolicySnapshot
import com.badnng.moe.settings.LocalLockPhase
import com.badnng.moe.settings.LocalLockSnapshot
import com.badnng.moe.notifications.DeviceNotificationsSnapshot

data class NativeInfo(val version: String, val commit: String, val directory: String)

enum class ProxyStatus { NOT_CONFIGURED, CONFIGURING, DIRECT, ENABLED, FAILED }

data class RuntimeSnapshot(
    val status: ClientStatus = ClientStatus(),
    val native: NativeInfo? = null,
    val configPath: String,
    val dataPath: String? = null,
    val note: String = "准备检查 TDLib 原生库与本地配置。",
    val clientActive: Boolean = false,
    val generation: Long = 0,
    val events: List<DiagnosticEvent> = emptyList(),
    val eventLogPath: String? = null,
    val eventLogAvailable: Boolean = true,
    val closeTimedOut: Boolean = false,
    val terminated: Boolean = false,
    val proxy: ProxyStatus = ProxyStatus.NOT_CONFIGURED,
    val proxyFailure: EngineFailure? = null,
    val login: LoginState = LoginState(),
    val chats: ChatSnapshot = ChatSnapshot(),
    val settings: SettingsSnapshot = SettingsSnapshot(),
    val deviceNotifications: DeviceNotificationsSnapshot = DeviceNotificationsSnapshot(),
    val storageSettings: SettingsStorageSnapshot = SettingsStorageSnapshot(),
    val loginEmail: LoginEmailSnapshot = LoginEmailSnapshot(),
    val loginInitialPhoneMode: Boolean = false,
    val loginQrPhoneSwitchAvailable: Boolean = true,
    val storagePolicySettings: SettingsStoragePolicySnapshot = SettingsStoragePolicySnapshot(),
    /** Inert previews remain compatible; the live Engine starts in CHECKING. */
    val localLock: LocalLockSnapshot = LocalLockSnapshot(phase = LocalLockPhase.UNPROTECTED),
) {
    val loginToken: LoginToken get() = LoginToken(generation, login.revision)

    fun canSubmitLogin(kind: LoginRequestKind): Boolean {
        if (terminated || !clientActive || status.engine != EnginePhase.INITIALIZED ||
            proxy !in setOf(ProxyStatus.DIRECT, ProxyStatus.ENABLED) ||
            login.pending || login.awaitingAuthorization) return false
        val mayReplacePhone = status.authorization in setOf(
                AuthorizationPhase.WAITING_LOGIN, AuthorizationPhase.WAITING_CODE, AuthorizationPhase.WAITING_PASSWORD,
            ) || (status.authorization == AuthorizationPhase.OTHER_STEP && login.challenge.otherStep in setOf(
                LoginOtherStep.EMAIL_ADDRESS, LoginOtherStep.EMAIL_CODE, LoginOtherStep.REGISTRATION, LoginOtherStep.PREMIUM_PURCHASE,
            ))
        return when (kind) {
            LoginRequestKind.PHONE, LoginRequestKind.QR_CODE -> mayReplacePhone
            LoginRequestKind.CODE -> status.authorization == AuthorizationPhase.WAITING_CODE &&
                login.challenge.codeDelivery != LoginCodeDelivery.FIREBASE
            LoginRequestKind.PASSWORD -> status.authorization == AuthorizationPhase.WAITING_PASSWORD
            LoginRequestKind.RESET_PHONE -> loginQrPhoneSwitchAvailable && status.authorization == AuthorizationPhase.OTHER_STEP &&
                login.challenge.otherStep == LoginOtherStep.DEVICE_CONFIRMATION
            LoginRequestKind.RESEND_CODE -> (status.authorization == AuthorizationPhase.WAITING_CODE &&
                login.challenge.nextCodeDelivery != LoginCodeDelivery.UNKNOWN) ||
                (status.authorization == AuthorizationPhase.OTHER_STEP && login.challenge.otherStep == LoginOtherStep.EMAIL_CODE)
            LoginRequestKind.EMAIL_ADDRESS, LoginRequestKind.EMAIL_CODE -> status.authorization == AuthorizationPhase.OTHER_STEP &&
                login.challenge.otherStep in setOf(LoginOtherStep.EMAIL_ADDRESS, LoginOtherStep.EMAIL_CODE)
            LoginRequestKind.PASSWORD_RECOVERY_REQUEST, LoginRequestKind.PASSWORD_RECOVERY_CODE ->
                status.authorization == AuthorizationPhase.WAITING_PASSWORD && login.challenge.hasRecoveryEmail
        }
    }

    val canStart: Boolean
        get() = !terminated && !clientActive && status.engine !in setOf(EnginePhase.STARTING, EnginePhase.CLOSING)
}
