package com.badnng.moe.settings

data class PrivacyCommonToken(val generation: Long = 0, val owner: Long = 0)
enum class PrivacyCommonField { TOP_PEERS, ARCHIVE, ARCHIVE_CAPABILITY, ACCOUNT_TTL }
enum class PrivacyArchiveCapability { UNKNOWN, ALLOWED, DENIED }
data class PrivacyArchiveSettings(
    val archiveAndMuteNewChats: Boolean,
    val keepUnmutedChatsArchived: Boolean,
    val keepChatsFromFoldersArchived: Boolean,
)
data class PrivacyCommonData(
    val topPeersEnabled: Boolean? = null,
    val archive: PrivacyArchiveSettings? = null,
    val archiveCapability: PrivacyArchiveCapability = PrivacyArchiveCapability.UNKNOWN,
    val accountTtlDays: Int? = null,
)
data class PrivacyCommonRead(val data: PrivacyCommonData, val errors: Map<PrivacyCommonField, SettingsError> = emptyMap())
data class PrivacyCommonUpdate(val field: PrivacyCommonField, val booleanValue: Boolean?)
data class PrivacyCommonSnapshot(
    val token: PrivacyCommonToken = PrivacyCommonToken(), val opened: Boolean = false,
    val loading: Boolean = false, val busy: Boolean = false,
    val data: PrivacyCommonData = PrivacyCommonData(),
    val loaded: Set<PrivacyCommonField> = emptySet(), val errors: Map<PrivacyCommonField, SettingsError> = emptyMap(),
    val refreshRequired: Set<PrivacyCommonField> = emptySet(), val operation: PrivacyCommonField? = null,
    val acceptedRevision: Long = 0, val failedRevision: Long = 0,
) { override fun toString() = "PrivacyCommonSnapshot(opened=$opened, loading=$loading, busy=$busy)" }

data class PrivacyCommonActions(
    val open: (SettingsToken, () -> Boolean) -> Unit = { _, _ -> },
    val close: (PrivacyCommonToken) -> Unit = {},
    val refresh: (PrivacyCommonToken) -> Unit = {},
    val setTopPeers: (PrivacyCommonToken, Boolean, Boolean) -> Unit = { _, _, _ -> },
    val setArchive: (PrivacyCommonToken, PrivacyArchiveSettings, PrivacyArchiveSettings) -> Unit = { _, _, _ -> },
    val setAccountTtl: (PrivacyCommonToken, Int, Int) -> Unit = { _, _, _ -> },
)
