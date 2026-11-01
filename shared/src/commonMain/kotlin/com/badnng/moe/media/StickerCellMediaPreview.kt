package com.badnng.moe.media

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.badnng.moe.chat.MediaFormat

/** Native thumbnails remain visible while the first hover animation decodes. */
@Composable
internal expect fun StickerCellMediaPreview(
    staticPath: String?, staticFormat: MediaFormat?, animationPath: String?, animationFormat: MediaFormat?,
    animate: Boolean, modifier: Modifier = Modifier, placeholder: @Composable () -> Unit,
)
