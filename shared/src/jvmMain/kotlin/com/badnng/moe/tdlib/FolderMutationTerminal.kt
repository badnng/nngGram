package com.badnng.moe.tdlib

/** Provenance of this original request chain, never a capability to perform another write. */
internal enum class FolderMutationReceipt { NOT_DISPATCHED, RESPONSE, ERROR_RESPONSE, UNKNOWN }

internal object FolderMutationTerminal {
    private data class Context(val active: Boolean, val receipt: FolderMutationReceipt)
    private val context = ThreadLocal<Context?>()
    fun <T> without(work: () -> T): T = scoped(active = false, work = work)
    fun active(): Boolean = context.get()?.active == true
    fun receipt(): FolderMutationReceipt = context.get()?.receipt ?: FolderMutationReceipt.NOT_DISPATCHED
    fun <T> scoped(active: Boolean = true, receipt: FolderMutationReceipt = FolderMutationReceipt.NOT_DISPATCHED,
        work: () -> T,
    ): T {
        val before = context.get()
        context.set(Context(active, receipt))
        try { return work() } finally {
            if (before == null) context.remove() else context.set(before)
        }
    }
}
