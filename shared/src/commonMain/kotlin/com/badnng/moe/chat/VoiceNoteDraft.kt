package com.badnng.moe.chat

/** Each retained handle has an independent, idempotent release. It contains no account metadata. */
interface AttachmentLease {
    fun retain(): AttachmentLease?
    fun release()
}

expect fun ownedAttachmentLease(cleanup: () -> Unit): AttachmentLease

/** A recording is temporary until the engine retains its lease for a native send. */
data class VoiceNoteDraft(
    val path: String,
    val size: Long,
    val durationSeconds: Int,
    val waveform: ByteArray,
    val lease: AttachmentLease,
) {
    override fun toString() = "VoiceNoteDraft(<redacted>)"
}
