package com.badnng.moe

import androidx.compose.ui.window.PopupProperties

/** Keep keyboard input inside a composer popup without blocking its trigger's hover region. */
internal expect fun composerPopupProperties(focusable: Boolean): PopupProperties
