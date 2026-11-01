package com.badnng.moe.tdlib

import com.badnng.moe.chat.AttachmentLease
import com.badnng.moe.media.validatePreparedProfileAnimationAsync
import com.badnng.moe.settings.SettingsError
import com.badnng.moe.settings.SettingsPreparedProfileAnimation
import com.badnng.moe.settings.SettingsResult
import org.drinkless.tdlib.TdApi
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Foundation for the existing extras mutation gate; owns no actor, UI or native-send file handle. */
internal class OfficialTdProfilePhotoAnimation(
    private val send: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit,
    /** Ownership of validationLease transfers on entry; implementations must release even on failure. */
    private val validateAsync: (
        SettingsPreparedProfileAnimation, AttachmentLease, () -> Boolean, (SettingsResult<Unit>) -> Unit,
    ) -> Unit = ::validatePreparedProfileAnimationAsync,
) {
    private val retired = AtomicBoolean()
    private val operations = ConcurrentHashMap.newKeySet<Upload>()

    fun upload(
        userId: Long,
        expectedPhotoId: Long?,
        animation: SettingsPreparedProfileAnimation,
        validationLease: AttachmentLease,
        isCurrent: () -> Boolean,
        result: (SettingsResult<Unit>) -> Unit,
    ) {
        val operation = Upload(userId, expectedPhotoId, animation, isCurrent, result)
        operations.add(operation)
        val admission = when {
            !operation.current() -> SettingsError.NOT_READY
            userId <= 0 || expectedPhotoId == 0L || !validDescriptor(animation) -> SettingsError.INVALID_VALUE
            else -> null
        }
        if (admission != null) {
            runCatching { validationLease.release() }
            operation.finish(SettingsResult(error = admission))
            return
        }
        try {
            validateAsync(animation, validationLease, operation::current) { checked -> operation.validated(checked) }
        } catch (_: Exception) {
            // The injected adapter has the same ownership contract as the default bounded adapter.
            // A callback may already have advanced to native work before an injected adapter throws.
            operation.finishIf(Stage.VALIDATE, SettingsResult(error = SettingsError.REQUEST_FAILED))
        }
    }

    fun retire() {
        retired.set(true)
        operations.forEach { it.retireBeforeWrite() }
    }

    private enum class Stage { VALIDATE, SELF, READY_WRITE, WRITE, DONE }

    private inner class Upload(
        val userId: Long,
        val expectedPhotoId: Long?,
        val animation: SettingsPreparedProfileAnimation,
        val isCurrent: () -> Boolean,
        val result: (SettingsResult<Unit>) -> Unit,
    ) {
        private val stage = AtomicReference(Stage.VALIDATE)
        fun current(): Boolean = !retired.get() && runCatching(isCurrent).getOrDefault(false) && !retired.get()

        fun finish(response: SettingsResult<Unit>) {
            if (stage.getAndSet(Stage.DONE) != Stage.DONE) deliver(response)
        }

        fun finishIf(expected: Stage, response: SettingsResult<Unit>) {
            if (stage.compareAndSet(expected, Stage.DONE)) deliver(response)
        }

        fun retireBeforeWrite() {
            while (true) {
                val previous = stage.get()
                if (previous == Stage.WRITE || previous == Stage.DONE) return
                if (stage.compareAndSet(previous, Stage.DONE)) {
                    deliver(SettingsResult(error = SettingsError.NOT_READY))
                    return
                }
            }
        }

        private fun deliver(response: SettingsResult<Unit>) {
            operations.remove(this)
            runCatching { result(response) }
        }

        fun validated(response: SettingsResult<Unit>) {
            if (stage.get() != Stage.VALIDATE) return
            when {
                !current() -> finishIf(Stage.VALIDATE, SettingsResult(error = SettingsError.NOT_READY))
                response.value == null -> finishIf(Stage.VALIDATE,
                    SettingsResult(error = response.error ?: SettingsError.REQUEST_FAILED))
                stage.compareAndSet(Stage.VALIDATE, Stage.SELF) -> request(TdApi.GetMe(), Stage.SELF, ::self)
            }
        }

        fun self(response: TdApi.Object) {
            if (stage.get() != Stage.SELF) return
            val error = when {
                !current() -> SettingsError.NOT_READY
                response !is TdApi.User -> settingsError(response)
                response.id != userId -> SettingsError.NOT_READY
                response.profilePhoto?.id?.takeIf { it != 0L } != expectedPhotoId -> SettingsError.CONFLICT
                else -> null
            }
            if (error != null) { finishIf(Stage.SELF, SettingsResult(error = error)); return }
            if (!stage.compareAndSet(Stage.SELF, Stage.READY_WRITE)) return
            request(TdApi.SetProfilePhoto(
                TdApi.InputChatPhotoAnimation(TdApi.InputFileLocal(animation.path), animation.mainFrameTimestampSeconds),
                false,
            ), Stage.READY_WRITE) { native ->
                finish(if (native is TdApi.Ok) SettingsResult(Unit) else SettingsResult(error = settingsError(native)))
            }
        }

        fun request(command: TdApi.Function<*>, expectedStage: Stage, response: (TdApi.Object) -> Unit) {
            val once = AtomicBoolean()
            fun fail(error: SettingsError) {
                if (once.compareAndSet(false, true)) finish(SettingsResult(error = error))
            }
            if (stage.get() != expectedStage) return
            if (!current()) { finishIf(expectedStage, SettingsResult(error = SettingsError.NOT_READY)); return }
            // Only this final admission claims WRITE. Retirement can still end READY_WRITE.
            if (expectedStage == Stage.READY_WRITE) {
                if (!stage.compareAndSet(Stage.READY_WRITE, Stage.WRITE)) return
            } else if (stage.get() != expectedStage) return
            try {
                val done: (TdApi.Object) -> Unit = { native ->
                    if (expectedStage != Stage.READY_WRITE ||
                        SettingsSpecialMutationContext.receipt()?.outcome != SettingsSpecialWriteOutcome.INDETERMINATE) {
                        if (once.compareAndSet(false, true)) response(native)
                    }
                }
                val failed = {
                    // An entered send without a native terminal still owns its WRITE stage.
                    if (SettingsSpecialMutationContext.receipt()?.outcome != SettingsSpecialWriteOutcome.INDETERMINATE)
                        fail(SettingsError.NOT_READY)
                }
                if (expectedStage == Stage.READY_WRITE) SettingsSpecialMutationContext.write(command, send, done, failed)
                else SettingsSpecialMutationContext.without {
                    send(command, { native -> SettingsSpecialMutationContext.withReceipt(null) { done(native) } },
                        { SettingsSpecialMutationContext.withReceipt(null) { fail(SettingsError.NOT_READY) } })
                }
            } catch (_: Exception) { fail(SettingsError.REQUEST_FAILED) }
        }
    }

    private fun validDescriptor(value: SettingsPreparedProfileAnimation): Boolean =
        value.path.isNotBlank() && '\u0000' !in value.path && value.path.length <= 32_767 &&
            value.size in 1..2_000_000L && value.dimension == 800 && value.durationMillis in 1_000..9_600L &&
            value.mainFrameTimestampSeconds.isFinite() && value.mainFrameTimestampSeconds >= 0 &&
            value.mainFrameTimestampSeconds < value.durationMillis / 1_000.0 &&
            value.sha256.length == 64 && value.sha256.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
}
