package com.badnng.moe.media

import com.badnng.moe.chat.AttachmentLease
import com.badnng.moe.settings.SettingsError
import com.badnng.moe.settings.SettingsPreparedProfileAnimation
import com.badnng.moe.settings.SettingsResult
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** All instances share one bounded worker. No probe, hash or blocking wait runs on the caller. */
internal class ProfilePhotoAnimationValidation(
    private val validate: (SettingsPreparedProfileAnimation) -> Boolean = ::validatePreparedProfileAnimation,
) {
    /** Takes ownership of this independent handle, including stale and rejected submissions. */
    fun validate(
        animation: SettingsPreparedProfileAnimation,
        validationLease: AttachmentLease,
        isCurrent: () -> Boolean,
        result: (SettingsResult<Unit>) -> Unit,
    ) {
        val once = AtomicBoolean()
        fun current() = runCatching(isCurrent).getOrDefault(false)
        fun finish(response: SettingsResult<Unit>) {
            if (once.compareAndSet(false, true)) runCatching { result(response) }
        }
        fun release() { runCatching { validationLease.release() } }
        if (!current()) {
            release()
            finish(SettingsResult(error = SettingsError.NOT_READY))
            return
        }
        try {
            worker.execute {
                val response: SettingsResult<Unit> = try {
                    if (!current()) SettingsResult(error = SettingsError.NOT_READY)
                    else {
                        // The synchronous validator retains its own child if a probe needs deferred reaping.
                        val valid = validate(animation.copy(lease = validationLease))
                        when {
                            !current() -> SettingsResult(error = SettingsError.NOT_READY)
                            valid -> SettingsResult(Unit)
                            else -> SettingsResult(error = SettingsError.INVALID_VALUE)
                        }
                    }
                } catch (_: Throwable) {
                    SettingsResult<Unit>(error = if (current()) SettingsError.REQUEST_FAILED else SettingsError.NOT_READY)
                } finally {
                    // Never release from retire/UI close while validate is still reading the file.
                    release()
                }
                finish(response)
            }
        } catch (_: Exception) {
            release()
            finish(SettingsResult(error = if (current()) SettingsError.REQUEST_FAILED else SettingsError.NOT_READY))
        }
    }

    private companion object {
        val worker = ThreadPoolExecutor(
            1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(8),
            { work -> Thread(work, "profile-animation-validation").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        )
    }
}

private val profilePhotoAnimationValidation = ProfilePhotoAnimationValidation()

internal fun validatePreparedProfileAnimationAsync(
    animation: SettingsPreparedProfileAnimation,
    validationLease: AttachmentLease,
    isCurrent: () -> Boolean,
    result: (SettingsResult<Unit>) -> Unit,
) = profilePhotoAnimationValidation.validate(animation, validationLease, isCurrent, result)
