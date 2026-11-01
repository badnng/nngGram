package com.badnng.moe.settings

enum class ReactionNotificationSource { NONE, CONTACTS, ALL }

/** Complete account value. Story and sound are preserved by edits to the three visible fields. */
data class ReactionNotificationValue(
    val message: ReactionNotificationSource,
    val story: ReactionNotificationSource,
    val poll: ReactionNotificationSource,
    val soundId: Long,
    val showSender: Boolean,
) {
    override fun toString() = "ReactionNotificationValue(<redacted>)"
}

/** Only an actual native update establishes a value; its origin is not a server acknowledgement. */
data class ReactionNotificationNativeState(
    val value: ReactionNotificationValue? = null,
    val revision: Long = 0,
    val error: SettingsError? = null,
)

data class ReactionNotificationPatch(
    val message: ReactionNotificationSource? = null,
    val poll: ReactionNotificationSource? = null,
    val showSender: Boolean? = null,
) {
    val isEmpty: Boolean get() = message == null && poll == null && showSender == null
    fun applyTo(value: ReactionNotificationValue): ReactionNotificationValue = value.copy(
        message = message ?: value.message, poll = poll ?: value.poll,
        showSender = showSender ?: value.showSender,
    )
}

data class ReactionNotificationSaveResult(
    val locallyAccepted: Boolean = false,
    val desired: ReactionNotificationValue? = null,
    val actual: ReactionNotificationNativeState = ReactionNotificationNativeState(),
    val error: SettingsError? = null,
)

data class ReactionNotificationsToken(val generation: Long = 0, val owner: Long = 0)
data class ReactionNotificationsEdit(
    val expected: ReactionNotificationNativeState,
    val patch: ReactionNotificationPatch,
    val contentRevision: Long,
)
data class ReactionNotificationsOutcome(
    val attemptId: Long,
    val edit: ReactionNotificationsEdit,
    val locallyAccepted: Boolean = false,
    val error: SettingsError? = null,
)
data class ReactionNotificationsSnapshot(
    val token: ReactionNotificationsToken = ReactionNotificationsToken(),
    val opened: Boolean = false,
    val native: ReactionNotificationNativeState = ReactionNotificationNativeState(),
    val saving: Boolean = false,
    val activeAttempt: Long? = null,
    val outcome: ReactionNotificationsOutcome? = null,
    val awaitingUpdate: Boolean = false,
    val refreshRequired: Boolean = false,
    val error: SettingsError? = null,
    val revision: Long = 0,
)
data class ReactionNotificationsActions(
    val open: (SettingsToken, () -> Boolean) -> Unit = { _, _ -> },
    val close: (ReactionNotificationsToken) -> Unit = {},
    val refreshFromCache: (ReactionNotificationsToken) -> Unit = {},
    val save: (ReactionNotificationsToken, ReactionNotificationsEdit) -> Unit = { _, _ -> },
)
