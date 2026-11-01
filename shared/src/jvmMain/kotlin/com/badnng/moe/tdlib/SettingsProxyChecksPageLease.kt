package com.badnng.moe.tdlib

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Created by Engine for one visit. valid is a thread-safe captured Engine/UI witness;
 * it must never read actor-owned fields or Compose state from a native thread.
 */
internal class SettingsProxyChecksPageLease(private val valid: () -> Boolean) {
    private val alive = AtomicBoolean(true)
    private val ownerLock = Any()
    private val revokeLock = Any()
    private val owners = mutableSetOf<SettingsProxyProbeOwner>()

    fun current(): Boolean {
        if (!alive.get()) return false
        if (!runCatching(valid).getOrDefault(false)) {
            // A temporary false result can never revive the same visit.
            alive.set(false)
            return false
        }
        return alive.get()
    }

    /** Register before a probe may be dispatched; do not hold this lock while revoking. */
    internal fun attach(owner: SettingsProxyProbeOwner): Boolean {
        if (!current()) { owner.revoke(); return false }
        val registered = synchronized(ownerLock) {
            if (!alive.get()) false else { owners.add(owner); true }
        }
        if (!registered) owner.revoke()
        return registered
    }

    internal fun detach(owner: SettingsProxyProbeOwner) {
        synchronized(ownerLock) { owners.remove(owner) }
    }

    /** Waits for all registered owner revocations, including a concurrent caller's close. */
    fun revoke() {
        alive.set(false)
        synchronized(revokeLock) {
            val previous = synchronized(ownerLock) { owners.toList().also { owners.clear() } }
            previous.forEach { it.revoke() }
        }
    }
}
