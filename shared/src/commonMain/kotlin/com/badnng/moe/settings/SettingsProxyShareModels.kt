package com.badnng.moe.settings

/** Public links are HTTPS; internal links are tg: and are also used for QR. */
enum class SettingsProxyShareFormat { PUBLIC, INTERNAL }

/** UI identities are witnesses, never a source from which to reconstruct credentials. */
sealed interface SettingsProxyShareTarget {
    data class One(val index: Int, val proxy: SettingsProxy) : SettingsProxyShareTarget
    /** Rows are in the source snapshot order, before the display projection reverses them. */
    data class All(val rows: List<SettingsProxy>) : SettingsProxyShareTarget
}

/** The exact page visit and source epoch must still be active when the request is admitted. */
data class SettingsProxyShareRequest(
    val token: SettingsProxyChecksToken,
    val listEpoch: Long,
    val target: SettingsProxyShareTarget,
    val format: SettingsProxyShareFormat,
) {
    override fun toString() = "SettingsProxyShareRequest(<redacted>)"
}

/**
 * A sensitive transient result, revoked with its exact page and source owner.
 * Implementations must redact toString and may never retain this in public snapshots.
 */
interface SettingsProxyShareContent {
    /** For short-lived local preview or QR preparation, not an asynchronous clipboard write. */
    fun textIfCurrent(): String?

    /**
     * Admit an explicit synchronous commit under the same boundary as revocation.
     * True means the closure was invoked; it does not itself prove OS clipboard success.
     */
    fun useIfCurrent(commit: (String) -> Unit): Boolean

    fun close()
}
