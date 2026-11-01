package com.badnng.moe.settings

import com.badnng.moe.chat.ChatToken

/** Admission is separate from the native open result; the page lease revokes late navigation. */
data class SettingsSupportActions(
    val open: (ChatToken, () -> Boolean, (SettingsResult<ChatToken>) -> Unit) -> Boolean = { _, _, result ->
        result(SettingsResult(error = SettingsError.NOT_READY))
        false
    },
)
