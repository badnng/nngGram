package com.badnng.moe.settings

/** All eight native default/value pairs; account data stays transient. */
data class ChatNotificationValues(
    val useDefaultMuteFor: Boolean = true, val muteFor: Int = 0,
    val useDefaultSound: Boolean = true, val soundId: Long = -1,
    val useDefaultShowPreview: Boolean = true, val showPreview: Boolean = true,
    val useDefaultMuteStories: Boolean = true, val muteStories: Boolean = false,
    val useDefaultStorySound: Boolean = true, val storySoundId: Long = -1,
    val useDefaultShowStoryPoster: Boolean = true, val showStoryPoster: Boolean = true,
    val useDefaultDisablePinnedMessageNotifications: Boolean = true,
    val disablePinnedMessageNotifications: Boolean = false,
    val useDefaultDisableMentionNotifications: Boolean = true,
    val disableMentionNotifications: Boolean = false,
) {
    val hasNewMessageException: Boolean get() = !useDefaultMuteFor ||
        (!useDefaultSound && soundId != -1L) || !useDefaultShowPreview ||
        !useDefaultDisablePinnedMessageNotifications || !useDefaultDisableMentionNotifications
    fun newMessageDefaults() = copy(useDefaultMuteFor = true, useDefaultSound = true,
        useDefaultShowPreview = true, useDefaultDisablePinnedMessageNotifications = true,
        useDefaultDisableMentionNotifications = true)
    override fun toString() = "ChatNotificationValues(<redacted>)"
}

enum class NotificationExceptionField { MUTE, SOUND, PREVIEW, PINNED, MENTIONS }

data class NotificationExceptionPeer(
    val chatId: Long, val scope: NotificationScope, val title: String,
    val values: ChatNotificationValues, val sampledAtMillis: Long,
    val editable: Boolean = true,
) { override fun toString() = "NotificationExceptionPeer(<redacted>)" }

data class NotificationExceptionRow(
    val chatId: Long, val peer: NotificationExceptionPeer? = null, val error: SettingsError? = null,
) { override fun toString() = "NotificationExceptionRow(<redacted>, error=$error)" }

data class NotificationExceptionEdit(
    val expected: NotificationExceptionPeer, val desired: ChatNotificationValues,
    val dirty: Set<NotificationExceptionField>,
) { override fun toString() = "NotificationExceptionEdit(<redacted>)" }

/** Facts about the one final native write, never inferred from a failed readback. */
enum class NotificationSettingsWriteReceipt { ACCEPTED, REJECTED, INDETERMINATE, NOT_DISPATCHED }

data class NotificationExceptionWriteResult(
    val locallyAccepted: Boolean = false, val actual: NotificationExceptionPeer? = null,
    val error: SettingsError? = null,
    val receipt: NotificationSettingsWriteReceipt = if (locallyAccepted) NotificationSettingsWriteReceipt.ACCEPTED
        else NotificationSettingsWriteReceipt.NOT_DISPATCHED,
) { override fun toString() = "NotificationExceptionWriteResult(<redacted>, accepted=$locallyAccepted, error=$error)" }

data class NotificationExceptionsToken(
    val generation: Long = 0, val owner: Long = 0,
    val scope: NotificationScope = NotificationScope.PRIVATE_CHATS,
)

data class NotificationExceptionsClearPlan(
    val id: Long, val targets: List<NotificationExceptionPeer>,
    val unresolved: List<NotificationExceptionRow> = emptyList(),
    val completed: Set<Long> = emptySet(), val failures: Map<Long, SettingsError> = emptyMap(),
    val receipts: Map<Long, NotificationSettingsWriteReceipt> = emptyMap(),
) { override fun toString() = "NotificationExceptionsClearPlan(<redacted>)" }

enum class NotificationExceptionsView { LIST, SELECTOR, EDITOR }

data class NotificationExceptionsSnapshot(
    val token: NotificationExceptionsToken = NotificationExceptionsToken(), val opened: Boolean = false,
    val view: NotificationExceptionsView = NotificationExceptionsView.LIST,
    val loading: Boolean = false, val saving: Boolean = false,
    val revision: Long = 0, val acceptedRevision: Long = 0,
    val totalIds: Int? = null, val rows: List<NotificationExceptionRow> = emptyList(),
    val hasMore: Boolean = false, val error: SettingsError? = null,
    val query: String = "", val searching: Boolean = false,
    val candidates: List<NotificationExceptionRow> = emptyList(), val searchError: SettingsError? = null,
    val editor: NotificationExceptionPeer? = null, val editorRevision: Long = 0,
    val editorError: SettingsError? = null, val refreshRequired: Boolean = false,
    val readbackPending: Boolean = false,
    val preparingClear: Boolean = false, val clearPlan: NotificationExceptionsClearPlan? = null,
) { override fun toString() = "NotificationExceptionsSnapshot(<redacted>, opened=$opened)" }

data class NotificationExceptionsActions(
    val open: (SettingsToken, NotificationScope, () -> Boolean) -> Unit = { _, _, _ -> },
    val close: (NotificationExceptionsToken) -> Unit = {},
    val refresh: (NotificationExceptionsToken) -> Unit = {},
    val more: (NotificationExceptionsToken) -> Unit = {},
    val select: (NotificationExceptionsToken) -> Unit = {},
    val search: (NotificationExceptionsToken, String, Boolean) -> Unit = { _, _, _ -> },
    val edit: (NotificationExceptionsToken, Long) -> Unit = { _, _ -> },
    val back: (NotificationExceptionsToken) -> Unit = {},
    val reloadEditor: (NotificationExceptionsToken) -> Unit = {},
    val save: (NotificationExceptionsToken, Long, NotificationExceptionEdit) -> Unit = { _, _, _ -> },
    val prepareClear: (NotificationExceptionsToken) -> Unit = {},
    val cancelClear: (NotificationExceptionsToken) -> Unit = {},
    val clear: (NotificationExceptionsToken, Long) -> Unit = { _, _ -> },
)
