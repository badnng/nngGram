package com.badnng.moe.tdlib

import java.util.concurrent.atomic.AtomicBoolean

/** Engine-owned across page visits and SettingsController generations. */
internal class SettingsProxyProbeBudget(private val limit: Int = 2) {
    init { require(limit > 0) }

    private val lock = Any()
    private var used = 0
    private var wakeOwner: Any? = null
    private var wake: (() -> Unit)? = null

    internal fun attach(owner: Any, callback: () -> Unit) = synchronized(lock) {
        wakeOwner = owner
        wake = callback
    }

    internal fun detach(owner: Any) = synchronized(lock) {
        if (wakeOwner === owner) { wakeOwner = null; wake = null }
    }

    internal fun tryAcquire(): Permit? = synchronized(lock) {
        if (used >= limit) null else {
            used++
            Permit(this)
        }
    }

    private fun released() {
        val callback = synchronized(lock) {
            check(used > 0)
            used--
            wake
        }
        // Never execute arbitrary scheduling while holding the pool lock.
        if (callback != null) runCatching { callback() }
    }

    internal class Permit(private val budget: SettingsProxyProbeBudget) {
        private val released = AtomicBoolean()
        fun release() {
            if (released.compareAndSet(false, true)) budget.released()
        }
    }
}
