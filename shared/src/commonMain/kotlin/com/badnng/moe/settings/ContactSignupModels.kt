package com.badnng.moe.settings

data class ContactSignupToken(val generation: Long = 0, val owner: Long = 0)
enum class ContactSignupValueSource { DEFAULT, EXPLICIT }
data class ContactSignupValue(val enabled: Boolean, val source: ContactSignupValueSource)

/** Actual GetOption result and local write acceptance are deliberately separate. */
data class ContactSignupSaveResult(
    val desiredEnabled: Boolean,
    val locallyAccepted: Boolean = false,
    val actual: ContactSignupValue? = null,
    val error: SettingsError? = null,
)

data class ContactSignupSnapshot(
    val token: ContactSignupToken = ContactSignupToken(),
    val opened: Boolean = false,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val value: ContactSignupValue? = null,
    val desiredEnabled: Boolean? = null,
    val readbackPending: Boolean = false,
    val revision: Long = 0,
    val acceptedRevision: Long = 0,
    val error: SettingsError? = null,
    val refreshRequired: Boolean = false,
)

data class ContactSignupActions(
    val open: (SettingsToken, () -> Boolean) -> Unit = { _, _ -> },
    val close: (ContactSignupToken) -> Unit = {},
    val refresh: (ContactSignupToken) -> Unit = {},
    val save: (ContactSignupToken, Long, ContactSignupValue, Boolean) -> Unit = { _, _, _, _ -> },
)
