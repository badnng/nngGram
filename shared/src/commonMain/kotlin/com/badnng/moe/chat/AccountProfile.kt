package com.badnng.moe.chat

/** Current account content is transient UI state and must never enter diagnostics. */
data class AccountProfile(
    val userId: Long,
    val displayName: String,
    val username: String = "",
    val phoneNumber: String = "",
    val avatar: MediaFile? = null,
    val isPremium: Boolean = false,
    val appearance: com.badnng.moe.settings.SettingsProfileColorsValues? = null,
) {
    override fun toString() = "AccountProfile(<redacted>)"
}
