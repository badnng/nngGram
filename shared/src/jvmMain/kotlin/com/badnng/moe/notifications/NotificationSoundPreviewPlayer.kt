package com.badnng.moe.notifications

import com.badnng.moe.settings.SettingsError
import com.badnng.moe.settings.SettingsResult
import java.util.concurrent.atomic.AtomicBoolean

/** The factory supplies a preview-only output; caller active gates must be thread-safe leases. */
internal class NotificationSoundPreviewPlayer(
    private val source: NotificationSoundSource,
    private val signals: DeviceNotificationSignals,
) : NotificationSoundPreview {
    private val lock = Any()
    @Volatile private var closed = false
    @Volatile private var current: Preview? = null

    private inner class Preview(
        val ownerActive: () -> Boolean,
        val result: (SettingsResult<Boolean>) -> Unit,
    ) : NotificationSoundLoad {
        @Volatile var done = false
        val inactive = AtomicBoolean()
        var loaded = false
        var outputQueued = false
        var sourceLease: NotificationSoundLoad? = null

        /** Native file callbacks can hold their own broker lock, so this gate never takes [lock]. */
        fun active(): Boolean {
            if (done || inactive.get()) return false
            if (closed || current !== this || !runCatching(ownerActive).getOrDefault(false)) inactive.set(true)
            return !done && !inactive.get() && !closed && current === this
        }

        fun finish(value: SettingsResult<Boolean>, stop: Boolean = false) {
            val callback: () -> Unit
            synchronized(lock) {
                if (done) return
                val terminal = if (active()) value else SettingsResult(error = SettingsError.NOT_READY)
                done = true
                if (current === this) {
                    current = null
                    // Keep ownership check and stop atomic with replacement admission.
                    if (stop && outputQueued) runCatching { signals.stop() }
                }
                val lease = sourceLease.also { sourceLease = null }
                callback = { runCatching { lease?.close() }; result(terminal) }
            }
            callback()
        }

        override fun close() = finish(SettingsResult(error = SettingsError.NOT_READY), stop = true)
    }

    override fun play(accountUserId: Long, soundId: Long, volumePercent: Int, active: () -> Boolean,
        result: (SettingsResult<Boolean>) -> Unit,
    ): NotificationSoundLoad {
        val work = Preview(active, result)
        val previous = synchronized(lock) {
            val old = current
            current = work
            // Stop the previous native instance before a replacement can reach its first write.
            if (old?.outputQueued == true) runCatching { signals.stop() }
            old
        }
        previous?.close()
        if (!work.active()) { work.close(); return work }
        if (volumePercent !in 1..100) {
            work.finish(SettingsResult(error = SettingsError.INVALID_VALUE)); return work
        }
        try {
            val lease = source.load(accountUserId, soundId, work::active) loaded@ { response ->
                synchronized(lock) {
                    if (work.loaded || work.done) return@loaded
                    work.loaded = true
                    if (!work.active()) { work.close(); return@loaded }
                    val audio = response.value
                    if (response.error != null || audio == null) {
                        work.finish(SettingsResult(error = response.error ?: SettingsError.REQUEST_FAILED)); return@loaded
                    }
                    if (audio.requestedSoundId != soundId) {
                        work.finish(SettingsResult(error = SettingsError.CONFLICT)); return@loaded
                    }
                    work.outputQueued = true
                    val admitted = signals.playPrepared(audio.pcm, volumePercent, work::active) { played ->
                        work.finish(if (played) SettingsResult(audio.defaultFallback)
                        else SettingsResult(error = if (work.active()) SettingsError.REQUEST_FAILED else SettingsError.NOT_READY))
                    }
                    if (!admitted) work.finish(SettingsResult(error = SettingsError.REQUEST_FAILED))
                }
            }
            val reject = synchronized(lock) {
                if (work.done) true else { work.sourceLease = lease; false }
            }
            if (reject) runCatching { lease.close() }
        } catch (_: Exception) { work.finish(SettingsResult(error = SettingsError.REQUEST_FAILED), stop = true) }
        return work
    }

    override fun close() {
        val previous = synchronized(lock) {
            if (closed) return
            closed = true
            current.also { runCatching { signals.close() } }
        }
        previous?.close()
    }
}
