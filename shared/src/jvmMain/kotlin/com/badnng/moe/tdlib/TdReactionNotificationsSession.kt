package com.badnng.moe.tdlib

import com.badnng.moe.settings.*

/** Shared for the entire native generation. There is no public TDLib Get for these settings. */
interface TdReactionNotificationsSession {
    fun state(): ReactionNotificationNativeState
    fun observe(observer: ((ReactionNotificationNativeState) -> Unit)?)
    fun save(expected: ReactionNotificationNativeState, patch: ReactionNotificationPatch,
        active: () -> Boolean, result: (ReactionNotificationSaveResult) -> Unit)
    fun retire() {}
}
