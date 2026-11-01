package com.badnng.moe

import androidx.compose.runtime.*
import com.badnng.moe.chat.*
import com.badnng.moe.media.nextMediaDownloadLeaseId

/** A mounted visible artwork consumer releases only its own native full-cache lease. */
@Composable
internal fun MediaArtworkVisibility(snapshot: ChatSnapshot, features: ChatFeatureActions, file: MediaFile?, active: Boolean) {
    val owner = remember(snapshot.token, file?.id) {
        MediaDownloadOwner(snapshot.token, file?.id?.toLong() ?: 0L, MediaDownloadPurpose.ARTWORK, nextMediaDownloadLeaseId())
    }
    val acquire = active && file != null && !file.downloaded && file.canDownload && snapshot.fileErrors[file.id] == null
    DisposableEffect(owner, file?.id, acquire) {
        if (acquire) features.acquireOwnedMedia(owner, checkNotNull(file).id)
        onDispose { file?.id?.let { features.releaseOwnedMedia(owner, it) } }
    }
}
