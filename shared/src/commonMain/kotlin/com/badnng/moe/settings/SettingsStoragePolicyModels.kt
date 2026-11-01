package com.badnng.moe.settings

enum class SettingsStoragePolicySource { EXPLICIT, DEFAULT, UNKNOWN }
enum class SettingsStoragePolicyField { SIZE, UNUSED_TIME, AUTOMATIC, FILE_COUNT, IMMUNITY }
enum class SettingsStoragePolicyStep { DISABLE_AUTOMATIC, SIZE, UNUSED_TIME, ENABLE_AUTOMATIC }
enum class SettingsStoragePolicyStatus { CONFIRMED, UNCHANGED, CONFLICT, INCOMPLETE, REJECTED }

/** A transport failure is not evidence that TDLib rejected a write. */
enum class SettingsStoragePolicyReceipt { ACCEPTED, REJECTED, NOT_DISPATCHED, INDETERMINATE, NOT_EXECUTED }

data class SettingsStoragePolicyToken(val generation: Long = 0, val owner: Long = 0)

/** DEFAULT means an actual native Empty response; UNKNOWN never grants a guessed default. */
data class SettingsStoragePolicyNumber(
    val raw: Long? = null,
    val effective: Long? = null,
    val source: SettingsStoragePolicySource = SettingsStoragePolicySource.UNKNOWN,
) {
    val known: Boolean get() = effective != null && effective in 0..SETTINGS_STORAGE_POLICY_MAX && when (source) {
        SettingsStoragePolicySource.EXPLICIT -> raw == effective
        SettingsStoragePolicySource.DEFAULT -> raw == null
        SettingsStoragePolicySource.UNKNOWN -> false
    }
}

data class SettingsStoragePolicySwitch(
    val raw: Boolean? = null,
    val effective: Boolean? = null,
    val source: SettingsStoragePolicySource = SettingsStoragePolicySource.UNKNOWN,
) {
    val known: Boolean get() = effective != null && when (source) {
        SettingsStoragePolicySource.EXPLICIT -> raw == effective
        SettingsStoragePolicySource.DEFAULT -> raw == null && !effective
        SettingsStoragePolicySource.UNKNOWN -> false
    }
}

data class SettingsStoragePolicyValues(
    val sizeKiB: SettingsStoragePolicyNumber = SettingsStoragePolicyNumber(),
    val unusedSeconds: SettingsStoragePolicyNumber = SettingsStoragePolicyNumber(),
    val automatic: SettingsStoragePolicySwitch = SettingsStoragePolicySwitch(),
    val fileCount: SettingsStoragePolicyNumber = SettingsStoragePolicyNumber(),
    val immunitySeconds: SettingsStoragePolicyNumber = SettingsStoragePolicyNumber(),
) {
    val known: Boolean get() = sizeKiB.known && unusedSeconds.known && automatic.known && fileCount.known && immunitySeconds.known
    val sizeBytes: Long? get() = sizeKiB.effective?.takeIf { sizeKiB.known }?.times(1024)
}

/** Only explicit changes are submitted; no source value is rounded to a UI slider position. */
data class SettingsStoragePolicyDraft(
    val sizeKiB: Long? = null,
    val unusedSeconds: Long? = null,
    val automatic: Boolean? = null,
) {
    val valid: Boolean get() = (sizeKiB == null || sizeKiB in 0..SETTINGS_STORAGE_POLICY_MAX) &&
        (unusedSeconds == null || unusedSeconds in 0..SETTINGS_STORAGE_POLICY_MAX)
    val empty: Boolean get() = sizeKiB == null && unusedSeconds == null && automatic == null
}

data class SettingsStoragePolicyRead(
    val values: SettingsStoragePolicyValues = SettingsStoragePolicyValues(),
    val errors: Map<SettingsStoragePolicyField, SettingsError> = emptyMap(),
) { val complete: Boolean get() = values.known && errors.isEmpty() }

data class SettingsStoragePolicyStepResult(
    val step: SettingsStoragePolicyStep,
    val accepted: Boolean = false,
    val confirmed: Boolean = false,
    val error: SettingsError? = null,
    val receipt: SettingsStoragePolicyReceipt = if (accepted) SettingsStoragePolicyReceipt.ACCEPTED
        else if (error != null) SettingsStoragePolicyReceipt.INDETERMINATE else SettingsStoragePolicyReceipt.NOT_EXECUTED,
)

data class SettingsStoragePolicyCompletion(
    val token: SettingsStoragePolicyToken,
    val requestId: Long,
    val status: SettingsStoragePolicyStatus,
    val steps: List<SettingsStoragePolicyStepResult> = emptyList(),
    val actual: SettingsStoragePolicyRead? = null,
    val error: SettingsError? = null,
    val refreshRequired: Boolean = false,
    val remainingSteps: List<SettingsStoragePolicyStepResult> = emptyList(),
    val physicalPending: Boolean = false,
) {
    companion object {
        fun rejected(token: SettingsStoragePolicyToken, requestId: Long, error: SettingsError = SettingsError.NOT_READY) =
            SettingsStoragePolicyCompletion(token, requestId, SettingsStoragePolicyStatus.REJECTED,
                error = error, refreshRequired = error == SettingsError.CONFLICT)
    }
}

data class SettingsStoragePolicySnapshot(
    val token: SettingsStoragePolicyToken = SettingsStoragePolicyToken(),
    val opened: Boolean = false,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val revision: Long = 0,
    val values: SettingsStoragePolicyValues = SettingsStoragePolicyValues(),
    val readErrors: Map<SettingsStoragePolicyField, SettingsError> = emptyMap(),
    val refreshRequired: Boolean = false,
    val error: SettingsError? = null,
    val steps: List<SettingsStoragePolicyStepResult> = emptyList(),
    val lastCompletion: SettingsStoragePolicyCompletion? = null,
)

data class SettingsStoragePolicyActions(
    val open: (Long, () -> Boolean) -> Unit = { _, _ -> },
    val close: (SettingsStoragePolicyToken) -> Unit = {},
    val refresh: (SettingsStoragePolicyToken) -> Unit = {},
    val save: (SettingsStoragePolicyToken, Long, SettingsStoragePolicyDraft, Long, (SettingsStoragePolicyCompletion) -> Unit) -> Unit =
        { token, _, _, requestId, done -> done(SettingsStoragePolicyCompletion.rejected(token, requestId)) },
)

const val SETTINGS_STORAGE_POLICY_MAX: Long = 2_147_483_647L
/** A finite native TTL (about 68 years), never a guarantee of unlimited retention. */
const val SETTINGS_STORAGE_POLICY_LONG_RETENTION_SECONDS: Long = SETTINGS_STORAGE_POLICY_MAX

fun settingsStoragePolicyKiBFromBytes(bytes: Long): Long? =
    (bytes / 1024).takeIf { bytes >= 0 && bytes % 1024 == 0L && it <= SETTINGS_STORAGE_POLICY_MAX }
