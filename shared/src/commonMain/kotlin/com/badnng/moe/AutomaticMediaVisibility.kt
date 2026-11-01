package com.badnng.moe

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalWindowInfo
import com.badnng.moe.chat.ChatToken

/** The real viewport, spoiler and page activity own AUTO; opening metadata creates no lease. */
@Composable
internal fun AutomaticMediaVisibility(features: ChatFeatureActions, token: ChatToken, messageId: Long, active: Boolean) {
    val effective = active && LocalWindowInfo.current.isWindowFocused
    DisposableEffect(token, messageId, effective) {
        features.automaticMedia(token, messageId, effective)
        onDispose { features.automaticMedia(token, messageId, false) }
    }
}
