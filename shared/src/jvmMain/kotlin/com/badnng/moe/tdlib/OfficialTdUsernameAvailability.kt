package com.badnng.moe.tdlib

import com.badnng.moe.settings.SettingsError
import com.badnng.moe.settings.SettingsResult
import com.badnng.moe.settings.SettingsUsernameAvailability
import com.badnng.moe.settings.SettingsUsernameAvailabilityStatus
import org.drinkless.tdlib.TdApi
import java.util.concurrent.atomic.AtomicBoolean

/** Read-only helper. It does not own the caller's query token or any username mutation. */
internal class OfficialTdUsernameAvailability(
    private val send: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit,
) {
    fun check(
        userId: Long,
        username: String,
        isCurrent: () -> Boolean,
        result: (SettingsResult<SettingsUsernameAvailability>) -> Unit,
    ) {
        val completed = AtomicBoolean()
        fun current() = runCatching(isCurrent).getOrDefault(false)
        fun finish(value: SettingsResult<SettingsUsernameAvailability>) {
            if (completed.compareAndSet(false, true)) {
                result(if (current()) value else SettingsResult(error = SettingsError.NOT_READY))
            }
        }
        fun failure(error: SettingsError) = finish(SettingsResult(error = error))

        if (userId <= 0) {
            failure(SettingsError.INVALID_VALUE)
            return
        }
        if (!current()) {
            failure(SettingsError.NOT_READY)
            return
        }

        request(TdApi.GetMe(), ::current, ::finish) { response ->
            val me = response as? TdApi.User
            if (me == null) {
                failure(settingsError(response))
                return@request
            }
            if (me.id != userId) {
                failure(SettingsError.NOT_READY)
                return@request
            }

            // This is a self-identity result, not a seventh native result. Only an exact
            // match is local; all other input is checked by TDLib without case folding.
            val editableUsername = me.usernames?.editableUsername.orEmpty()
            if (username == editableUsername) {
                finish(SettingsResult(SettingsUsernameAvailability(
                    me.id,
                    username,
                    SettingsUsernameAvailabilityStatus.CURRENT_USERNAME,
                )))
                return@request
            }

            request(TdApi.CheckChatUsername(me.id, username), ::current, ::finish) { native ->
                val checkResult = native as? TdApi.CheckChatUsernameResult
                if (checkResult == null) {
                    finish(SettingsResult(error = SettingsError.REQUEST_FAILED))
                    return@request
                }
                val status = when (checkResult) {
                    is TdApi.CheckChatUsernameResultOk -> SettingsUsernameAvailabilityStatus.CAN_BE_SET
                    is TdApi.CheckChatUsernameResultUsernameInvalid -> SettingsUsernameAvailabilityStatus.INVALID
                    is TdApi.CheckChatUsernameResultUsernameOccupied -> SettingsUsernameAvailabilityStatus.OCCUPIED
                    is TdApi.CheckChatUsernameResultUsernamePurchasable -> SettingsUsernameAvailabilityStatus.PURCHASABLE
                    is TdApi.CheckChatUsernameResultPublicChatsTooMany -> SettingsUsernameAvailabilityStatus.PUBLIC_CHATS_TOO_MANY
                    is TdApi.CheckChatUsernameResultPublicGroupsUnavailable -> SettingsUsernameAvailabilityStatus.PUBLIC_GROUPS_UNAVAILABLE
                    else -> SettingsUsernameAvailabilityStatus.UNKNOWN
                }
                finish(SettingsResult(SettingsUsernameAvailability(me.id, username, status)))
            }
        }
    }

    private fun request(
        command: TdApi.Function<*>,
        isCurrent: () -> Boolean,
        finish: (SettingsResult<SettingsUsernameAvailability>) -> Unit,
        handle: (TdApi.Object) -> Unit,
    ) {
        fun current() = runCatching(isCurrent).getOrDefault(false)
        val requestCompleted = AtomicBoolean()
        fun response(native: TdApi.Object) {
            if (!requestCompleted.compareAndSet(false, true)) return
            if (!current()) {
                finish(SettingsResult(error = SettingsError.NOT_READY))
            } else if (native is TdApi.Error) {
                finish(SettingsResult(error = settingsError(native)))
            } else {
                handle(native)
            }
        }
        if (!current()) {
            if (requestCompleted.compareAndSet(false, true)) finish(SettingsResult(error = SettingsError.NOT_READY))
            return
        }
        try {
            send(command, ::response, {
                if (requestCompleted.compareAndSet(false, true)) finish(SettingsResult(error = SettingsError.NOT_READY))
            })
        } catch (_: Exception) {
            if (requestCompleted.compareAndSet(false, true)) finish(SettingsResult(error = SettingsError.REQUEST_FAILED))
        }
    }
}
