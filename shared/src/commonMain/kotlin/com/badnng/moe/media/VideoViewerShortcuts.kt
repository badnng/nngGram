package com.badnng.moe.media

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType

internal enum class VideoViewerShortcut(val seekDeltaSeconds: Double = 0.0) {
    CONSUME,
    TOGGLE_PLAY,
    SEEK_BACK(-10.0),
    SEEK_FORWARD(10.0),
    CLOSE,
}

/** Consume both phases of viewer gestures so focused controls never also activate. */
internal fun videoViewerShortcut(
    key: Key,
    type: KeyEventType,
    expanded: Boolean,
    shift: Boolean = false,
    ctrl: Boolean = false,
    alt: Boolean = false,
    meta: Boolean = false,
): VideoViewerShortcut? {
    if (!expanded || shift || ctrl || alt || meta) return null
    return when {
        type == KeyEventType.KeyDown && (key == Key.K || key == Key.Spacebar || key == Key.Escape) -> VideoViewerShortcut.CONSUME
        type == KeyEventType.KeyUp && (key == Key.K || key == Key.Spacebar) -> VideoViewerShortcut.TOGGLE_PLAY
        type == KeyEventType.KeyUp && key == Key.Escape -> VideoViewerShortcut.CLOSE
        type == KeyEventType.KeyDown && key == Key.J -> VideoViewerShortcut.SEEK_BACK
        type == KeyEventType.KeyDown && key == Key.L -> VideoViewerShortcut.SEEK_FORWARD
        type == KeyEventType.KeyUp && (key == Key.J || key == Key.L) -> VideoViewerShortcut.CONSUME
        else -> null
    }
}
