package com.badnng.moe.tdlib

import com.badnng.moe.chat.AttachmentLease
import java.util.concurrent.atomic.AtomicBoolean

/** Engine lifetime, including close timeouts: TDLib may still read a queued local input file. */
internal class AttachmentSendLeases {
    private val guard = Any()
    private val retained = mutableSetOf<PendingAttachmentLease>()

    fun retain(source: AttachmentLease): PendingAttachmentLease? {
        val handle = source.retain() ?: return null
        return synchronized(guard) { PendingAttachmentLease(handle,
            onReleased = { released -> synchronized(guard) { retained.remove(released) } },
            retainHandle = ::retain).also { retained += it } }
    }

    /** Call only after native AuthorizationStateClosed, never when a controller is deactivated. */
    fun releaseAll() {
        val owned = synchronized(guard) { retained.toList().also { retained.clear() } }
        owned.forEach { runCatching { it.release() } }
    }
}

internal class PendingAttachmentLease(
    private val handle: AttachmentLease,
    private val onReleased: (PendingAttachmentLease) -> Unit,
    private val retainHandle: (AttachmentLease) -> PendingAttachmentLease?,
) {
    private val released = AtomicBoolean(false)
    fun release() {
        if (!released.compareAndSet(false, true)) return
        try { handle.release() } finally { onReleased(this) }
    }
    fun retain(): PendingAttachmentLease? = if (released.get()) null else retainHandle(handle)
    override fun toString() = "PendingAttachmentLease(<redacted>)"
}
