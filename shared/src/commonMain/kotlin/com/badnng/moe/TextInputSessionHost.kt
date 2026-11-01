package com.badnng.moe

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/** The desktop host supplies native session cleanup at the editor's actual window. */
val LocalTextInputSessionHost = staticCompositionLocalOf<@Composable (content: @Composable () -> Unit) -> Unit> {
    { content -> content() }
}
