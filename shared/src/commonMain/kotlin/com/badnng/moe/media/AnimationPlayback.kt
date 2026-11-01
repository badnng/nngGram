package com.badnng.moe.media

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.badnng.moe.chat.ChatResult

/** Native GIF and silent MPEG-4 animations share bounded video decoding and an inline loop. */
@Composable
expect fun AnimationPlayback(
    path: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    streamSize: Long = 0,
    readPart: ((Long, Int, (ChatResult<ByteArray>) -> Unit) -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    readerFactory: (() -> MediaRangeReader)? = null,
    /** Applied before the first decoder; subsequent animation loops begin at zero. */
    startPositionMillis: Long = 0,
    /** Display name and accessibility description; the existing GIF defaults are unchanged. */
    displayLabel: String = "GIF",
    contentDescription: String = "GIF 动画",
    outputGate: MediaOutputGate? = null,
)
