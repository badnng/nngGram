package com.badnng.moe.media

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.badnng.moe.chat.MediaFormat

/** One visible cell owns at most a static image and its lazily decoded hover animation. */
@Composable
internal expect fun ReactionMediaPreview(
    staticPath: String?, staticFormat: MediaFormat?, animationPath: String?, animationFormat: MediaFormat?,
    animate: Boolean, staticFirstFrame: Boolean = true, modifier: Modifier = Modifier, placeholder: @Composable () -> Unit,
)

/** A native center/around asset plays one cycle for one observed addition, then releases its decoder. */
@Composable
internal expect fun ReactionEffectPreview(
    path: String, format: MediaFormat, playbackId: Long, modifier: Modifier = Modifier,
    onReady: (Boolean) -> Unit = {}, onFinished: () -> Unit,
)
