package com.badnng.moe.tdlib

import com.badnng.moe.chat.MediaFile
import com.badnng.moe.settings.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** The UI can relinquish ownership before actor admission or native lease assignment. */
internal class ProfilePhotoHistoryFileBridge(
    private val active: () -> Boolean,
    private val result: (SettingsResult<MediaFile>) -> Unit,
) : SettingsProfilePhotoHistoryFileLease {
    private val closed = AtomicBoolean()
    private val delivered = AtomicBoolean()
    private val native = AtomicReference<SettingsProfilePhotoHistoryFileLease?>()

    fun current(): Boolean = !closed.get() && runCatching(active).getOrDefault(false) && !closed.get()

    fun bind(lease: SettingsProfilePhotoHistoryFileLease) {
        if (!native.compareAndSet(null, lease)) { runCatching { lease.close() }; return }
        if (closed.get()) releaseNative()
    }

    fun finish(response: SettingsResult<MediaFile>) {
        val output = when {
            !current() -> SettingsResult(error = SettingsError.NOT_READY)
            response.value == null && response.error == null -> SettingsResult(error = SettingsError.REQUEST_FAILED)
            else -> response
        }
        if (!delivered.compareAndSet(false, true)) return
        if (output.error != null || output.value == null) {
            closed.set(true)
            releaseNative()
        }
        try { result(output) } catch (_: Exception) { close() }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        releaseNative()
        if (delivered.compareAndSet(false, true)) runCatching { result(SettingsResult(error = SettingsError.NOT_READY)) }
    }

    private fun releaseNative() { native.getAndSet(null)?.let { runCatching { it.close() } } }
}
