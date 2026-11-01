package com.badnng.moe.settings

/** UI-only projection; never contains a password, key, native response or directory identity. */
enum class LocalLockPhase { CHECKING, NEW_ROOT_SETUP, UNPROTECTED, COLD_LOCKED, HOT_LOCKED, UNLOCKING, UNLOCKED, RECOVERY }

enum class LocalLockIssue {
    NONE, WRONG_PASSWORD, INVALID_PASSWORD, PASSWORD_MISMATCH, PASSWORD_UNCHANGED, INVALID_AUTO_LOCK, BUSY, WAITING,
    MIGRATION_UNAVAILABLE, CORRUPT_RECORD, UNSUPPORTED_RECORD, ROOT_IDENTITY_UNAVAILABLE,
    IO_FAILURE, RESOURCE_UNAVAILABLE, CRYPTO_UNAVAILABLE, DATABASE_MISMATCH, STALE,
}

data class LocalLockSnapshot(
    val phase: LocalLockPhase = LocalLockPhase.CHECKING,
    val enabled: Boolean = false,
    val busy: Boolean = false,
    val issue: LocalLockIssue = LocalLockIssue.NONE,
    val retryAfterMillis: Long = 0,
    val epoch: Long = 0,
    val operationRevision: Long = 0,
    val managementAuthorized: Boolean = false,
    val canCreate: Boolean = false,
    val canManage: Boolean = false,
    val canChange: Boolean = false,
    val canRemove: Boolean = false,
    val canAutoLock: Boolean = false,
    val canLockNow: Boolean = false,
    val autoLockSeconds: Long = 3600,
    val idleFallback: Boolean = false,
    /** System resume notifications are unavailable; independent of system idle input. */
    val resumeNotificationsUnavailable: Boolean = false,
) {
    val rootBlocked: Boolean get() = phase != LocalLockPhase.UNPROTECTED && phase != LocalLockPhase.UNLOCKED
}

/**
 * Password arrays are transferred to the action: every path must clear them, including
 * rejection and cancellation. Callers must not reuse or concurrently mutate them.
 * The inert defaults also consume inputs, so previews cannot accidentally retain them.
 */
class LocalLockActions(
    val create: (CharArray, CharArray) -> Unit = { password, confirmation ->
        password.fill('\u0000')
        confirmation.fill('\u0000')
    },
    val unlock: (CharArray) -> Unit = { it.fill('\u0000') },
    val verifyManagement: (CharArray) -> Unit = { it.fill('\u0000') },
    val change: (CharArray, CharArray) -> Unit = { password, confirmation ->
        password.fill('\u0000')
        confirmation.fill('\u0000')
    },
    val remove: () -> Unit = {},
    val lockNow: () -> Unit = {},
    val managementActivity: () -> Unit = {},
    val closeManagement: () -> Unit = {},
    val setAutoLockSeconds: (Long) -> Unit = {},
    val skipSetup: () -> Unit = {},
    val cancel: () -> Unit = {},
)
