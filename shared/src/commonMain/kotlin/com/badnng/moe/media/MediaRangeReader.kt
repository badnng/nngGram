package com.badnng.moe.media

import com.badnng.moe.ChatFeatureActions
import com.badnng.moe.chat.*

/** One bridge instance owns separate playback and metadata leases, including across retries. */
class MediaRangeReader(
    val read: (Long, Int, (ChatResult<ByteArray>) -> Unit) -> Unit,
    val probe: (Long, Int, (ChatResult<ByteArray>) -> Unit) -> Unit = read,
    val release: () -> Unit = {},
    val releaseProbe: () -> Unit = {},
    val fork: ((Boolean) -> MediaRangeReader)? = null,
)

internal expect fun nextMediaDownloadLeaseId(): Long

internal fun ownedMediaRangeReader(features: ChatFeatureActions, token: ChatToken, messageId: Long, fileId: Int): MediaRangeReader {
    // This object is only a factory. HEAD and constructing a bridge open no owner. The JVM
    // bridge invokes fork exactly once per real GET while holding its parent lifecycle lock.
    return MediaRangeReader(read = { _, _, result -> result(ChatResult(error = ChatError.NOT_READY)) }, fork = { probe ->
        val owner = MediaDownloadOwner(token, messageId,
            if (probe) MediaDownloadPurpose.PROBE else MediaDownloadPurpose.STREAM, nextMediaDownloadLeaseId())
        features.openOwnedVideoPart(owner, fileId)
        val read: (Long, Int, (ChatResult<ByteArray>) -> Unit) -> Unit = { offset, count, result ->
            features.readOwnedVideoPart(owner, fileId, offset, count, result)
        }
        MediaRangeReader(read, read, release = { features.releaseOwnedVideoPart(owner, fileId) })
    })
}
