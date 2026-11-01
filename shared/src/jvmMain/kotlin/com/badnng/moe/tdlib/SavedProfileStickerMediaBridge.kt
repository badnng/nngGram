package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import java.util.concurrent.atomic.AtomicBoolean

/** A request witness is distinct from both the native read and an exported artwork source. */
internal class SavedProfileStickerMediaBridge(
    private val environment: () -> Boolean,
    private val caller: () -> Boolean,
    private val result: (SettingsResult<ProfileStickerAvatarEncoded>) -> Unit,
    private val callerDemand: SettingsSavedProfileStickerAvatarDemand = SettingsSavedProfileStickerAvatarDemand(),
    private val removed: (SavedProfileStickerMediaBridge) -> Unit,
) : SettingsSavedProfileStickerAvatarLease {
    private val alive = AtomicBoolean(true)
    private val valid = AtomicBoolean(true)
    private val demand = AtomicBoolean(true)
    private val lock = Any()
    private var grant: SavedProfileStickerActorGrant? = null
    private var returned = false
    private var first: ProfileStickerAvatarEncoded? = null

    override fun requestCurrent(): Boolean {
        if (!alive.get() || !valid.get()) return false
        val bound = synchronized(lock) { grant }
        val allowed = runCatching { environment() && caller() && (bound?.sourceCurrent?.invoke() ?: true) }.getOrDefault(false)
        if (!allowed) valid.set(false)
        return allowed && valid.get() && alive.get()
    }

    fun demandCurrent(): Boolean = demand.get() && callerDemand.current() && requestCurrent()

    /** Safe on an off-scene owner callback: only revoke atomic demand, never release pixels. */
    override fun revokeDemand() {
        demand.set(false)
        val bound = synchronized(lock) { grant }
        bound?.revokeDemand()
    }

    /** Invoked by the actor only after the native source and exact photo epoch are captured. */
    fun start(value: SavedProfileStickerActorGrant,
        submit: (() -> Boolean, (SettingsResult<ProfileStickerAvatarEncoded>) -> Unit) -> ProfileStickerAvatarReadLease) {
        val bound = synchronized(lock) {
            if (!alive.get() || grant != null) false else { grant = value; true }
        }
        if (!bound || !demandCurrent()) { value.close(); close(); return }
        val read = try { submit(::demandCurrent, ::returned) } catch (_: Throwable) {
            returned(SettingsResult(error = SettingsError.REQUEST_FAILED)); return
        }
        value.bind(read) // Handles synchronous result/close before submit returns.
    }

    private fun returned(response: SettingsResult<ProfileStickerAvatarEncoded>) {
        val original = response.value
        val isFirst = synchronized(lock) {
            if (returned) false else { returned = true; first = original; true }
        }
        if (!isFirst) {
            if (original !== synchronized(lock) { first }) original?.close()
            return
        }
        revokeDemand()
        val bound = synchronized(lock) { grant }
        if (!requestCurrent() || bound == null) { original?.close(); close(); return }
        if (response.error != null || original == null) {
            original?.close()
            bound.close() // physical demand is released; bound.sourceCurrent remains a witness.
            if (requestCurrent()) runCatching { result(SettingsResult(error = response.error ?: SettingsError.REQUEST_FAILED)) }
            return
        }
        // The exported gate deliberately does not retain this bridge or the read's closed flag.
        val environmentLease = environment
        val callerLease = caller
        val sourceLease = bound.sourceCurrent
        val narrowed = original.restrictSource {
            runCatching { environmentLease() && callerLease() && sourceLease() }.getOrDefault(false)
        }
        original.close()
        bound.close()
        if (narrowed == null || !requestCurrent() || !runCatching(narrowed.sourceLease()).getOrDefault(false)) {
            narrowed?.close(); close(); return
        }
        try { result(SettingsResult(narrowed)) } catch (_: Throwable) { narrowed.close(); close() }
    }

    /** Rejected admission never creates a source witness or publishes stale artwork. */
    fun reject() {
        val notify = synchronized(lock) { if (returned) false else { returned = true; true } }
        close()
        if (notify) runCatching { result(SettingsResult(error = SettingsError.NOT_READY)) }
    }

    override fun close() {
        if (!alive.compareAndSet(true, false)) return
        revokeDemand()
        val bound = synchronized(lock) { grant }
        runCatching { bound?.close() }
        runCatching { removed(this) }
    }
}
