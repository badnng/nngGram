package com.badnng.moe.status

enum class LoginRequestKind {
    PHONE, CODE, PASSWORD, QR_CODE, RESET_PHONE, RESEND_CODE, EMAIL_ADDRESS, EMAIL_CODE,
    PASSWORD_RECOVERY_REQUEST, PASSWORD_RECOVERY_CODE,
}

enum class LoginCodeDelivery {
    UNKNOWN, TELEGRAM, SMS, CALL, FLASH_CALL, MISSED_CALL, FRAGMENT, SMS_WORD, SMS_PHRASE, FIREBASE,
}

enum class LoginOtherStep {
    UNKNOWN, EMAIL_ADDRESS, EMAIL_CODE, REGISTRATION, DEVICE_CONFIRMATION, PREMIUM_PURCHASE,
}

/** Presentation data lives only in the current authorization challenge; never serialize or log it. */
data class LoginPresentation(
    val qrLink: String? = null,
    val phoneNumber: String? = null,
    val passwordHint: String? = null,
    val recoveryEmailPattern: String? = null,
    val emailPattern: String? = null,
) {
    override fun toString(): String = "LoginPresentation(<redacted>)"
}

/** Safe finite metadata and redacted, transient presentation data from the current native state. */
data class AuthorizationDetails(
    val phase: AuthorizationPhase,
    val codeDelivery: LoginCodeDelivery = LoginCodeDelivery.UNKNOWN,
    val codeLength: Int? = null,
    val otherStep: LoginOtherStep = LoginOtherStep.UNKNOWN,
    val nextCodeDelivery: LoginCodeDelivery = LoginCodeDelivery.UNKNOWN,
    val resendTimeoutSeconds: Int = 0,
    val hasRecoveryEmail: Boolean = false,
    val presentation: LoginPresentation = LoginPresentation(),
)

data class LoginToken(val generation: Long, val revision: Long)

enum class LoginError {
    INVALID_PHONE, INVALID_CODE, EXPIRED_CODE, INVALID_PASSWORD, PHONE_BANNED,
    TOO_MANY_ATTEMPTS, REQUEST_FAILED, EMPTY_INPUT,
    INVALID_EMAIL, EMAIL_NOT_ALLOWED, RECOVERY_UNAVAILABLE,
}

val LoginRequestKind.requiresInput: Boolean get() = this !in setOf(
    LoginRequestKind.QR_CODE, LoginRequestKind.RESET_PHONE, LoginRequestKind.RESEND_CODE,
    LoginRequestKind.PASSWORD_RECOVERY_REQUEST,
)

/** Native recovery and resend requests can succeed without changing the authorization phase. */
val LoginRequestKind.awaitsAuthorization: Boolean get() = this !in setOf(
    LoginRequestKind.RESEND_CODE, LoginRequestKind.PASSWORD_RECOVERY_REQUEST,
)

/** The native boundary converts all errors to these application-owned values. */
data class LoginResult(val error: LoginError? = null, val code: Int? = null)

data class LoginState(
    val challenge: AuthorizationDetails = AuthorizationDetails(AuthorizationPhase.UNKNOWN),
    val revision: Long = 0,
    val pending: Boolean = false,
    val awaitingAuthorization: Boolean = false,
    val error: LoginError? = null,
)
