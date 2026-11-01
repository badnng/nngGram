package com.badnng.moe.settings

import com.badnng.moe.chat.MediaFile
import com.badnng.moe.chat.MediaMiniThumbnail

data class SettingsProfilePhotoIdentity(
    val userId: Long,
    val currentPhotoId: Long? = null,
    val personalPhotoId: Long? = null,
    val publicPhotoId: Long? = null,
    val isBot: Boolean = false,
) {
    fun isProtected(photoId: Long) = photoId == currentPhotoId || photoId == personalPhotoId || photoId == publicPhotoId
    override fun toString() = "SettingsProfilePhotoIdentity(<redacted>)"
}
data class SettingsProfilePhotoVariant(val file: MediaFile, val width: Int, val height: Int)
/** One real native MPEG4 variant; this metadata never grants file access on its own. */
data class SettingsProfilePhotoAnimation(
    val file: MediaFile,
    val length: Int,
    val mainFrameTimestampSeconds: Double,
) {
    /** Desktop floors nonnegative seconds to milliseconds and clamps to the signed-int maximum. */
    val mainFrameTimestampMillis: Long?
        get() = mainFrameTimestampSeconds.takeIf { it.isFinite() && it >= 0.0 }
            ?.let { (it * 1_000.0).coerceAtMost(Int.MAX_VALUE.toDouble()).toLong() }
    override fun toString() = "SettingsProfilePhotoAnimation(<redacted>)"
}
data class SettingsProfilePhotoHistoryEntry(
    val photoId: Long,
    val addedDate: Int,
    val rawOffset: Int,
    val variants: List<SettingsProfilePhotoVariant>,
    val minithumbnail: MediaMiniThumbnail? = null,
    val hasAnimation: Boolean = false,
    val hasSticker: Boolean = false,
    val authorization: Long = 0,
    val animation: SettingsProfilePhotoAnimation? = null,
    val smallAnimation: SettingsProfilePhotoAnimation? = null,
    /** Present but unusable under local identity/bounds rules, distinct from absent. */
    val invalidAnimation: Boolean = false,
    val invalidSmallAnimation: Boolean = false,
    val sticker: SettingsProfilePhotoSticker? = null,
    val invalidSticker: Boolean = false,
) {
    /** Metadata selection only. Native catalog still controls file access. */
    fun preferredAnimation(): SettingsProfilePhotoAnimation? =
        if (invalidAnimation) null else animation ?: if (invalidSmallAnimation) null else smallAnimation
    override fun toString() = "SettingsProfilePhotoHistoryEntry(<redacted>)"
}
data class SettingsProfilePhotoHistoryPage(
    val identity: SettingsProfilePhotoIdentity,
    val entries: List<SettingsProfilePhotoHistoryEntry>,
    val rawCount: Int,
    val totalCount: Int?,
    val offset: Int,
    /** Internal catalog found conflicting facts; actor must revoke the whole history epoch. */
    val metadataConflict: Boolean = false,
)

/** Merge only facts observed from an authorized native File; no path or progress is learned. */
internal fun historyPhotoWithObservedFile(entry: SettingsProfilePhotoHistoryEntry, observed: MediaFile): SettingsProfilePhotoHistoryEntry {
    fun merge(file: MediaFile): MediaFile {
        if (file.id != observed.id || observed.uniqueId.isBlank() ||
            file.uniqueId.isNotBlank() && file.uniqueId != observed.uniqueId ||
            file.size > 0 && observed.size > 0 && file.size != observed.size) return file
        return file.copy(uniqueId = file.uniqueId.ifBlank { observed.uniqueId },
            size = if (file.size == 0L) observed.size else file.size)
    }
    return entry.copy(variants = entry.variants.map { it.copy(file = merge(it.file)) },
        animation = entry.animation?.let { it.copy(file = merge(it.file)) },
        smallAnimation = entry.smallAnimation?.let { it.copy(file = merge(it.file)) })
}

/** Compare only facts describing the native photo, not a page offset or transfer state. */
internal fun sameHistoryPhotoMetadata(a: SettingsProfilePhotoHistoryEntry, b: SettingsProfilePhotoHistoryEntry): Boolean {
    fun sameFile(x: MediaFile, y: MediaFile) = x.id == y.id &&
        (x.uniqueId == y.uniqueId || x.uniqueId.isBlank() || y.uniqueId.isBlank()) &&
        x.size >= 0 && y.size >= 0 && (x.size == 0L || y.size == 0L || x.size == y.size)
    fun sameAnimation(x: SettingsProfilePhotoAnimation?, y: SettingsProfilePhotoAnimation?): Boolean = when {
        x == null || y == null -> x == null && y == null
        else -> x.length == y.length && x.mainFrameTimestampSeconds == y.mainFrameTimestampSeconds &&
            sameFile(x.file, y.file)
    }
    if (a.photoId != b.photoId || a.hasAnimation != b.hasAnimation || a.hasSticker != b.hasSticker ||
        a.sticker != b.sticker || a.invalidSticker != b.invalidSticker ||
        a.invalidAnimation != b.invalidAnimation || a.invalidSmallAnimation != b.invalidSmallAnimation ||
        a.addedDate != 0 && b.addedDate != 0 && a.addedDate != b.addedDate ||
        !sameAnimation(a.animation, b.animation) || !sameAnimation(a.smallAnimation, b.smallAnimation) ||
        a.variants.size != b.variants.size || a.variants.size > 16) return false

    // At most 16 native variants. A bounded matching handles order changes and unknown
    // size/unique IDs without letting one unknown stand in for multiple known files.
    val matchedLeft = IntArray(b.variants.size) { -1 }
    fun assign(leftIndex: Int, seen: BooleanArray): Boolean {
        val left = a.variants[leftIndex]
        for (index in b.variants.indices) {
            val right = b.variants[index]
            if (seen[index] || left.width != right.width || left.height != right.height ||
                !sameFile(left.file, right.file)) continue
            seen[index] = true
            if (matchedLeft[index] < 0 || assign(matchedLeft[index], seen)) {
                matchedLeft[index] = leftIndex
                return true
            }
        }
        return false
    }
    for (index in a.variants.indices) if (!assign(index, BooleanArray(b.variants.size))) return false
    return true
}

data class SettingsProfilePhotoHistoryTarget(
    val identity: SettingsProfilePhotoIdentity,
    val photoId: Long,
    val rawOffset: Int,
) { override fun toString() = "SettingsProfilePhotoHistoryTarget(<redacted>)" }
data class SettingsProfilePhotoHistorySnapshot(
    val opened: Boolean = false,
    val epoch: Long = 0,
    val identity: SettingsProfilePhotoIdentity? = null,
    val loading: Boolean = false,
    val entries: List<SettingsProfilePhotoHistoryEntry> = emptyList(),
    val totalCount: Int? = null,
    val nextOffset: Int = 0,
    val hasMore: Boolean = false,
    val limited: Boolean = false,
    val unavailableCount: Int = 0,
    val error: SettingsError? = null,
    val refreshRequired: Boolean = false,
)
data class SettingsProfilePhotoHistoryActions(
    val open: (SettingsProfileExtrasToken, () -> Boolean) -> Unit = { _, _ -> },
    val close: (SettingsProfileExtrasToken) -> Unit = {},
    val refresh: (SettingsProfileExtrasToken) -> Unit = {},
    val more: (SettingsProfileExtrasToken) -> Unit = {},
    val reuse: (SettingsProfileExtrasToken, Long, SettingsProfilePhotoHistoryTarget, () -> Boolean) -> Unit = { _, _, _, _ -> },
    val remove: (SettingsProfileExtrasToken, Long, SettingsProfilePhotoHistoryTarget, () -> Boolean) -> Unit = { _, _, _, _ -> },
    val acquireFile: (SettingsProfilePhotoHistoryMediaRequest, () -> Boolean, (SettingsResult<MediaFile>) -> Unit) -> SettingsProfilePhotoHistoryFileLease =
        { _, _, result -> unavailableProfilePhotoHistoryFile(result) },
)
