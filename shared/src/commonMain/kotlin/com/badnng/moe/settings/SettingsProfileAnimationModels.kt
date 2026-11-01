package com.badnng.moe.settings

import com.badnng.moe.chat.AttachmentLease

/** An actual encoded local animation. UI and queued/native upload must retain separate handles. */
data class SettingsPreparedProfileAnimation(
    val path: String,
    val size: Long,
    val dimension: Int,
    val durationMillis: Long,
    val mainFrameTimestampSeconds: Double,
    val sha256: String,
    val lease: AttachmentLease,
) {
    override fun toString() = "SettingsPreparedProfileAnimation(<redacted>)"
}
