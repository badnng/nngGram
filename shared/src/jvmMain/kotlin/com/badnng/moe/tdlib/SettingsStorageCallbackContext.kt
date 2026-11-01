package com.badnng.moe.tdlib

/** Internal storage receipt context; it never grants a page or a subsequent request ownership. */
internal class SettingsStorageCallbackContext(
    val current: () -> Boolean = { true },
    val deliver: (() -> Unit) -> Unit = { it() },
    val resume: (() -> Unit) -> Unit = { it() },
) {
    fun runDelivery(work: () -> Unit) = withContext(this) { deliver(work) }

    fun runNext(work: () -> Unit) {
        val previous = local.get()
        local.remove()
        try { resume(work) } finally {
            if (previous == null) local.remove() else local.set(previous)
        }
    }

    companion object {
        private val local = ThreadLocal<SettingsStorageCallbackContext?>()
        private val unrestricted = SettingsStorageCallbackContext()

        /** Metadata only: a fresh owner must not inherit an older storage receipt. */
        fun <T> without(work: () -> T): T {
            val previous = local.get()
            local.remove()
            try { return work() } finally {
                if (previous == null) local.remove() else local.set(previous)
            }
        }

        fun capture(): SettingsStorageCallbackContext = local.get() ?: unrestricted

        fun withContext(context: SettingsStorageCallbackContext, work: () -> Unit) {
            val previous = local.get()
            local.set(context)
            try { work() } finally {
                if (previous == null) local.remove() else local.set(previous)
            }
        }
    }
}
