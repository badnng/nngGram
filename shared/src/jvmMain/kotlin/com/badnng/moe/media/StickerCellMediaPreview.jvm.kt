package com.badnng.moe.media

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.badnng.moe.chat.MediaFormat

@Composable
internal actual fun StickerCellMediaPreview(
    staticPath: String?, staticFormat: MediaFormat?, animationPath: String?, animationFormat: MediaFormat?,
    animate: Boolean, modifier: Modifier, placeholder: @Composable () -> Unit,
) = GridMediaPreview(staticPath, staticFormat, animationPath, animationFormat, animate,
    staticFirstFrame = true, rasterEdge = 160, modifier = modifier, placeholder = placeholder)
