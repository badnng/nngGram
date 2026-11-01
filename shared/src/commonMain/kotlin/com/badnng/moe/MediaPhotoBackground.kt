package com.badnng.moe

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.badnng.moe.chat.MediaMiniThumbnail

/** Official inline photo backdrop only; caller supplies a ready native photo/thumbnail path. */
@Composable
internal expect fun MediaPhotoBackground(
    path: String?,
    loadAllowed: Boolean,
    modifier: Modifier,
    minithumbnail: MediaMiniThumbnail? = null,
)
