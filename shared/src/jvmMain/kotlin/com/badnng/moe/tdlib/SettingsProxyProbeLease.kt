package com.badnng.moe.tdlib

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** A page/source owner. [valid] must be thread-safe and must not take a lock which can revoke this owner. */
class SettingsProxyProbeOwner(private val valid: () -> Boolean) {
    internal val lock = Any()
    private var revoked = false
    private val dispatching = AtomicInteger()
    private val draining = AtomicBoolean()
    private val callbacks = ConcurrentLinkedQueue<() -> Unit>()

    internal fun currentLocked(): Boolean {
        if (revoked) return false
        if (!runCatching(valid).getOrDefault(false)) revoked = true
        return !revoked
    }

    fun current(): Boolean = synchronized(lock) { currentLocked() }

    /** When this returns, no child can commit a new dispatch using this owner. */
    fun revoke() = synchronized(lock) { revoked = true }

    fun newLease(onDrained: () -> Unit = {}): SettingsProxyProbeLease = SettingsProxyProbeLease(this, onDrained)

    internal fun dispatch(lease: SettingsProxyProbeLease, submit: () -> Unit): Boolean {
        dispatching.incrementAndGet()
        try {
            return synchronized(lock) {
                if (!lease.currentLocked()) false else {
                    submit()
                    true
                }
            }
        } finally {
            if (dispatching.decrementAndGet() == 0) drainCallbacks()
        }
    }

    /** In particular, a synchronously reentrant native fake cannot invoke the chain under the dispatch lock. */
    internal fun deliver(action: () -> Unit) {
        callbacks.add(action)
        drainCallbacks()
    }

    private fun drainCallbacks() {
        if (dispatching.get() != 0 || !draining.compareAndSet(false, true)) return
        do {
            try {
                while (dispatching.get() == 0) {
                    val action = callbacks.poll() ?: break
                    runCatching(action)
                }
            } finally {
                draining.set(false)
            }
            if (dispatching.get() != 0 || callbacks.isEmpty() || !draining.compareAndSet(false, true)) return
        } while (true)
    }
}

/** One probe operation. Revocation and physical submission share the page owner's lock. */
class SettingsProxyProbeLease internal constructor(
    private val owner: SettingsProxyProbeOwner,
    private val onDrained: () -> Unit,
) {
    constructor(valid: () -> Boolean, onDrained: () -> Unit = {}) : this(SettingsProxyProbeOwner(valid), onDrained)

    private var revoked = false
    private var started = false
    private var finished = false
    private var drained = false
    private var outstanding = 0

    internal fun currentLocked(): Boolean = !revoked && owner.currentLocked()
    fun current(): Boolean = synchronized(owner.lock) { currentLocked() }
    fun revoke() = synchronized(owner.lock) { revoked = true }
    fun dispatch(submit: () -> Unit): Boolean = owner.dispatch(this, submit)

    internal class Stage { val terminal = AtomicBoolean() }

    internal fun beginProbe(): Boolean = synchronized(owner.lock) {
        if (started) false else { started = true; true }
    }

    internal fun beginStage(): Stage? = synchronized(owner.lock) {
        if (!started || finished) null else {
            outstanding++
            Stage()
        }
    }

    internal fun endStage(stage: Stage) {
        if (!stage.terminal.compareAndSet(false, true)) return
        val notify = synchronized(owner.lock) {
            outstanding--
            takeDrainedLocked()
        }
        notify?.let { runCatching(it) }
    }

    internal fun finishProbe() {
        val notify = synchronized(owner.lock) {
            finished = true
            takeDrainedLocked()
        }
        notify?.let { runCatching(it) }
    }

    internal fun deliver(action: () -> Unit) = owner.deliver(action)

    private fun takeDrainedLocked(): (() -> Unit)? {
        if (drained || !finished || outstanding != 0) return null
        drained = true
        return onDrained
    }
}
