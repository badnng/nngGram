package com.badnng.moe.chat

enum class ChatActionBarKind { REPORT_SPAM, REPORT_ADD_BLOCK, INVITE_MEMBERS, ADD_CONTACT, SHARE_PHONE_NUMBER, JOIN_REQUEST }

data class ChatActionBar(val kind: ChatActionBarKind, val canUnarchive: Boolean = false)

/** A fresh native read, not a permission inferred from a title or general report capability. */
data class ChatReportSpamCapability(
    val actionBar: ChatActionBar? = null,
    val canLeave: Boolean = false,
    val canBeReported: Boolean = false,
    val needsHistoryRemoval: Boolean = false,
    val canDeleteHistory: Boolean = false,
    val isMember: Boolean? = if (canLeave) true else null,
)

/** Kept per chat during one engine generation so a successful report is never sent twice. */
data class ChatReportSpamState(
    val chatId: Long? = null,
    val available: Boolean = false,
    val reported: Boolean = false,
    val left: Boolean = false,
    val reportPending: Boolean = false,
    val leavePending: Boolean = false,
    val dismissPending: Boolean = false,
    val needsHistoryRemoval: Boolean = false,
    val cleared: Boolean = false,
    val clearPending: Boolean = false,
    val error: ChatError? = null,
    val revision: Long = 0,
) {
    val pending: Boolean get() = reportPending || leavePending || dismissPending || clearPending
    val completed: Boolean get() = left && (!needsHistoryRemoval || cleared)
    val visible: Boolean get() = !completed && (available || reported || pending || error != null)
}
