package com.badnng.moe

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import com.badnng.moe.media.LocalWindowActivity

/** Mirrors the existing desktop playback host: a supplied Tao flow controls activity. */
@Composable
internal actual fun profileStickerAvatarWindowActive(): Boolean {
    val activity = LocalWindowActivity.current
    return activity?.collectAsState()?.value != false
}
