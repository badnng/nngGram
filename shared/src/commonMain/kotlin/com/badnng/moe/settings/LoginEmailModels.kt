package com.badnng.moe.settings

enum class LoginEmailEntryKind { OPTIONAL, MANDATORY }
enum class LoginEmailAvailability { UNKNOWN, CHECKING, ALLOWED, NOT_REQUIRED, FAILED }
enum class LoginEmailPhase { EMAIL, CODE, PROOF_PENDING, COMPLETE }
enum class LoginEmailIssue {
    NOT_AVAILABLE, BUSY, STATE_CHANGED, EMAIL_INVALID, CODE_INVALID, CODE_EXPIRED,
    TOO_MANY_ATTEMPTS, REQUEST_FAILED, PROOF_UNCONFIRMED,
}

/** Membership is the actual native delta, never a locally fabricated removed action. */
data class LoginEmailSuggestions(
    val revision: Long = 0, val optionalPresent: Boolean = false, val requiredPresent: Boolean = false,
    val optionalEpoch: Long = 0, val requiredEpoch: Long = 0,
) {
    fun contains(kind: LoginEmailEntryKind, epoch: Long): Boolean = when (kind) {
        LoginEmailEntryKind.OPTIONAL -> optionalPresent && optionalEpoch == epoch
        LoginEmailEntryKind.MANDATORY -> requiredPresent && requiredEpoch == epoch
    }
    fun epoch(kind: LoginEmailEntryKind): Long = when (kind) {
        LoginEmailEntryKind.OPTIONAL -> optionalEpoch
        LoginEmailEntryKind.MANDATORY -> requiredEpoch
    }
}

data class LoginEmailSetupToken(
    val generation: Long = 0, val owner: Long = 0, val suggestionEpoch: Long = 0,
    val kind: LoginEmailEntryKind = LoginEmailEntryKind.OPTIONAL,
)
data class LoginEmailChallengeToken(val owner: LoginEmailSetupToken, val revision: Long)
data class LoginEmailProof(
    val availability: LoginEmailAvailability, val hasLoginEmail: Boolean = false,
    val issue: LoginEmailIssue? = null,
)

/** Only masked native information may enter public state. */
data class LoginEmailOutcome(
    val codeInfo: SettingsEmailCodeInfo? = null, val challenge: LoginEmailChallengeToken? = null,
    val issue: LoginEmailIssue? = null, val completed: Boolean = false,
    val verificationAccepted: Boolean = false, val fulfilledElsewhere: Boolean = false,
) { override fun toString() = "LoginEmailOutcome(<redacted>, issue=$issue)" }

data class LoginEmailSnapshot(
    val generation: Long = 0, val suggestions: LoginEmailSuggestions = LoginEmailSuggestions(),
    val mandatory: Boolean = false, val satisfiedRequiredEpoch: Long = 0,
    val token: LoginEmailSetupToken? = null, val openingId: Long = 0,
    val availability: LoginEmailAvailability = LoginEmailAvailability.UNKNOWN,
    val phase: LoginEmailPhase = LoginEmailPhase.EMAIL, val busy: Boolean = false,
    val codeInfo: SettingsEmailCodeInfo? = null, val challenge: LoginEmailChallengeToken? = null,
    val issue: LoginEmailIssue? = null, val verificationAccepted: Boolean = false,
    val fulfilledElsewhere: Boolean = false,
) { override fun toString() = "LoginEmailSnapshot(generation=$generation, mandatory=$mandatory, phase=$phase)" }
