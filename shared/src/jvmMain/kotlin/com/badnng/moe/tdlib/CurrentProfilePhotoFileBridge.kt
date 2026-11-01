package com.badnng.moe.tdlib

import com.badnng.moe.chat.MediaFile
import com.badnng.moe.settings.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One cancellable, at-most-once notification; foreign code is never called under a lock. */
internal class CurrentPhotoInvalidationObserver(private val callback: () -> Unit) {
    private val pending = AtomicBoolean(true)
    fun cancel() { pending.set(false) }
    fun fire() { if (pending.compareAndSet(true, false)) runCatching(callback) }
}

private enum class CurrentBridgeState { ACTIVE, FAILED, CLOSED }

/** Reuses the queued-read owner and carries separate output and request witnesses. */
internal class CurrentProfilePhotoFileBridge(private val outerActive: () -> Boolean, result: (SettingsResult<MediaFile>) -> Unit) :
    SettingsCurrentProfilePhotoFileLease {
    private val delegate = ProfilePhotoHistoryFileBridge(outerActive, result)
    private val state = AtomicReference(CurrentBridgeState.ACTIVE)
    private val child = AtomicReference<SettingsCurrentProfilePhotoFileLease?>()
    private val unsubscribe = AtomicReference<(() -> Unit)?>()
    private val observer = AtomicReference<CurrentPhotoInvalidationObserver?>()

    /** The actor consumes only the outer gate; including its own child here would recurse. */
    fun sourceCurrent(): Boolean = state.get() != CurrentBridgeState.CLOSED &&
        runCatching(outerActive).getOrDefault(false) && state.get() != CurrentBridgeState.CLOSED
    override fun requestCurrent(): Boolean = sourceCurrent() && child.get()?.requestCurrent() != false && sourceCurrent()
    fun current(): Boolean = state.get() == CurrentBridgeState.ACTIVE && sourceCurrent() && delegate.current() &&
        child.get()?.active() != false && state.get() == CurrentBridgeState.ACTIVE
    override fun active(): Boolean = current()
    override fun onInvalidated(callback: () -> Unit): () -> Unit {
        val next = CurrentPhotoInvalidationObserver(callback)
        observer.getAndSet(next)?.cancel()
        if (!current()) {
            if (state.get() == CurrentBridgeState.FAILED && requestCurrent()) {
                if (observer.compareAndSet(next, null)) next.cancel()
            } else { close(); observer.getAndSet(null)?.fire() }
        }
        return { next.cancel(); observer.compareAndSet(next, null); Unit }
    }
    fun finish(response: SettingsResult<MediaFile>) {
        if (response.value == null || response.error != null) {
            if (!requestCurrent()) { close(); return }
            if (!state.compareAndSet(CurrentBridgeState.ACTIVE, CurrentBridgeState.FAILED)) return
            try { delegate.finish(response) } finally {
                unsubscribe.getAndSet(null)?.invoke(); observer.getAndSet(null)?.cancel()
            }
        } else {
            if (!current()) { close(); return }
            delegate.finish(response)
            if (!current()) close()
        }
    }
    fun bind(lease: SettingsCurrentProfilePhotoFileLease) {
        if (!child.compareAndSet(null, lease)) { lease.close(); return }
        if (state.get() == CurrentBridgeState.FAILED) {
            if (!requestCurrent()) close()
            return
        }
        val cancel = lease.onInvalidated { close() }
        if (!unsubscribe.compareAndSet(null, cancel)) cancel()
        if (state.get() == CurrentBridgeState.CLOSED) unsubscribe.getAndSet(null)?.invoke()
        delegate.bind(object : SettingsProfilePhotoHistoryFileLease {
            override fun close() { if (state.get() != CurrentBridgeState.FAILED) lease.close() }
        })
        if (state.get() == CurrentBridgeState.ACTIVE && !current()) close()
    }
    override fun close() {
        val old = state.getAndSet(CurrentBridgeState.CLOSED)
        if (old == CurrentBridgeState.CLOSED) return
        try { unsubscribe.getAndSet(null)?.invoke() } finally {
            try { delegate.close(); child.get()?.close() } finally {
                val listener = observer.getAndSet(null)
                if (old == CurrentBridgeState.ACTIVE) listener?.fire() else listener?.cancel()
            }
        }
    }
}
