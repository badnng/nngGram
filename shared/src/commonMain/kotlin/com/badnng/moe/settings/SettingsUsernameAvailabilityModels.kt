package com.badnng.moe.settings

/** Typed TDLib check results; CURRENT_USERNAME is a local fresh-self identity result. */
enum class SettingsUsernameAvailabilityStatus {
    CURRENT_USERNAME,
    CAN_BE_SET,
    INVALID,
    OCCUPIED,
    PURCHASABLE,
    PUBLIC_CHATS_TOO_MANY,
    PUBLIC_GROUPS_UNAVAILABLE,
    UNKNOWN,
}

/** Ephemeral account data for one username-check revision. */
data class SettingsUsernameAvailability(
    val userId: Long,
    val username: String,
    val status: SettingsUsernameAvailabilityStatus,
) {
    override fun toString() = "SettingsUsernameAvailability(<redacted>, status=$status)"
}
