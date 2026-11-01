package com.badnng.moe

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.PopupProperties

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun composerPopupProperties(focusable: Boolean): PopupProperties =
    PopupProperties(focusable = focusable, consumePointerInputOutside = false)
