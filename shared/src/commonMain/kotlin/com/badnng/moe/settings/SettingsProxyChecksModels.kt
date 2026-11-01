package com.badnng.moe.settings

/** One actor-issued owner for a single interactive visit to the ADVANCED page. */
data class SettingsProxyChecksToken(val parent: SettingsToken, val owner: Long)

sealed interface SettingsProxyCheckStatus {
    /** Includes rows queued behind the bounded native work. */
    data object Checking : SettingsProxyCheckStatus
    data class Available(val milliseconds: Long) : SettingsProxyCheckStatus
    data object Unavailable : SettingsProxyCheckStatus
    data object NotTested : SettingsProxyCheckStatus
}

/** Index and the complete redacted row together identify the published source. */
data class SettingsProxyCheckRow(
    val index: Int,
    val proxy: SettingsProxy,
    val status: SettingsProxyCheckStatus,
    val error: SettingsError? = null,
)

data class SettingsProxyChecksSnapshot(
    val token: SettingsProxyChecksToken? = null,
    val listEpoch: Long = 0,
    val rows: List<SettingsProxyCheckRow> = emptyList(),
)

/** Closing synchronously revokes this page before scheduling actor cleanup. */
fun interface SettingsProxyChecksLoad {
    fun close()
    fun refresh() { }
    fun share(request: SettingsProxyShareRequest, result: (SettingsResult<SettingsProxyShareContent>) -> Unit) {
        result(SettingsResult(error = SettingsError.UNSUPPORTED))
    }
}

data class SettingsProxyChecksActions(
    val open: (SettingsToken, () -> Boolean, (SettingsProxyChecksToken?) -> Unit) -> SettingsProxyChecksLoad =
        { _, _, opened ->
            opened(null)
            SettingsProxyChecksLoad { }
        },
)
