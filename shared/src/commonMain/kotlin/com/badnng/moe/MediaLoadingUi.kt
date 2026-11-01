package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.MessageMedia
import com.badnng.moe.chat.MediaMiniThumbnail
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Request only the native thumbnail, once per current source; failures wait for an explicit retry. */
@Composable
internal fun rememberLoadingThumbnailPath(
    media: MessageMedia,
    snapshot: ChatSnapshot,
    features: ChatFeatureActions,
    loadAllowed: Boolean,
): String? {
    val thumbnail = media.thumbnail?.let { snapshot.mediaFiles[it.id] ?: it }
    MediaArtworkVisibility(snapshot, features, thumbnail?.takeIf { it.id > 0 && it.id != media.file.id }, loadAllowed)
    return thumbnail?.localPath?.takeIf { thumbnail.downloaded }
}

/** Loading covers share the bounded worker blur, without changing photo, sticker or viewer geometry. */
@Composable
internal expect fun MediaLoadingThumbnail(
    path: String?,
    loadAllowed: Boolean,
    modifier: Modifier,
    minithumbnail: MediaMiniThumbnail? = null,
)

@Composable
internal fun MediaLoadingCover(
    thumbnailPath: String?,
    loadAllowed: Boolean,
    active: Boolean,
    description: String,
    modifier: Modifier = Modifier,
    showIndicator: Boolean = true,
    minithumbnail: MediaMiniThumbnail? = null,
) {
    Box(modifier.background(MiuixTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.Center) {
        if (thumbnailPath != null || minithumbnail != null) {
            MediaLoadingThumbnail(thumbnailPath, loadAllowed, Modifier.fillMaxSize(), minithumbnail)
        }
        if (showIndicator) MediaLoadingIndicator(active, description)
    }
}

/** The reference is Miuix's moving arc and gray track; unmount it when its owner cannot animate. */
@Composable
internal fun MediaLoadingIndicator(active: Boolean, description: String, modifier: Modifier = Modifier) {
    if (!active || !LocalWindowInfo.current.isWindowFocused) return
    CircularProgressIndicator(
        progress = null,
        size = 30.dp,
        strokeWidth = 2.dp,
        colors = ProgressIndicatorDefaults.progressIndicatorColors(
            foregroundColor = MiuixTheme.colorScheme.primary,
            backgroundColor = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = .4f),
        ),
        modifier = modifier.semantics {
            contentDescription = description
            progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
        },
    )
}
