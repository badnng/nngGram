package com.badnng.moe.settings

import com.badnng.moe.chat.MediaFile
import com.badnng.moe.chat.MediaMiniThumbnail

/** The current view is independent of the ordinary profile-photo history. */
data class SettingsCurrentProfilePhoto(
    val photoId: Long,
    val addedDate: Int,
    val variants: List<SettingsProfilePhotoVariant>,
    val minithumbnail: MediaMiniThumbnail? = null,
    val hasAnimation: Boolean = false,
    val hasSticker: Boolean = false,
    val animation: SettingsProfilePhotoAnimation? = null,
    val smallAnimation: SettingsProfilePhotoAnimation? = null,
    val invalidAnimation: Boolean = false,
    val invalidSmallAnimation: Boolean = false,
    /** Opaque native capability consumed only by the actor, never by a UI file request. */
    val authorization: Long = 0,
    val sticker: SettingsProfilePhotoSticker? = null,
    val invalidSticker: Boolean = false,
) {
    fun preferredAnimation(): SettingsProfilePhotoAnimation? =
        if (invalidAnimation) null else animation ?: if (invalidSmallAnimation) null else smallAnimation
    override fun toString() = "SettingsCurrentProfilePhoto(<redacted>)"
}

enum class SettingsCurrentProfilePhotoStatus { CLOSED, LOADING, EMPTY, UNKNOWN, READY, ROLE_CONFLICT, CONFLICT, ERROR }

/** Native result remains distinct from actor-owned opening/epoch state. */
data class SettingsCurrentProfilePhotoRead(
    val status: SettingsCurrentProfilePhotoStatus,
    val photo: SettingsCurrentProfilePhoto? = null,
) { override fun toString() = "SettingsCurrentProfilePhotoRead(<redacted>, status=$status)" }

data class SettingsCurrentProfilePhotoSnapshot(
    val epoch: Long = 0,
    val status: SettingsCurrentProfilePhotoStatus = SettingsCurrentProfilePhotoStatus.CLOSED,
    val photo: SettingsCurrentProfilePhoto? = null,
    val error: SettingsError? = null,
) { override fun toString() = "SettingsCurrentProfilePhotoSnapshot(<redacted>, status=$status)" }

enum class SettingsCurrentProfilePhotoMediaUse { STATIC_PREVIEW, ANIMATION_PREVIEW }

/** UI selects a current snapshot only; it cannot nominate a native file or local path. */
data class SettingsCurrentProfilePhotoMediaRequest(
    val parent: SettingsProfileExtrasToken,
    val epoch: Long,
    val photoId: Long,
    val use: SettingsCurrentProfilePhotoMediaUse,
    val targetWidthPx: Int,
    val targetHeightPx: Int,
) { override fun toString() = "SettingsCurrentProfilePhotoMediaRequest(<redacted>)" }

interface SettingsCurrentProfilePhotoFileLease {
    /** Thread-safe output gate; invalidated leases never become active again. */
    fun active(): Boolean = false
    /** Request ownership survives ordinary file failure while its actual source is current. */
    fun requestCurrent(): Boolean = false
    /** One source-revocation observer per lease; ordinary file failure is reported only by result. */
    fun onInvalidated(callback: () -> Unit): () -> Unit { runCatching(callback); return {} }
    fun close()
}

internal fun unavailableCurrentProfilePhotoFile(
    result: (SettingsResult<MediaFile>) -> Unit,
): SettingsCurrentProfilePhotoFileLease {
    result(SettingsResult(error = SettingsError.UNSUPPORTED))
    return object : SettingsCurrentProfilePhotoFileLease { override fun close() {} }
}

data class SettingsCurrentProfilePhotoActions(
    /** Admission is an exact actor epoch, not a native success; null means rejected. */
    val open: (SettingsProfileExtrasToken, () -> Boolean, (Long?) -> Unit) -> Unit = { _, _, admitted -> admitted(null) },
    /** Exact epoch prevents an old opening affecting a later one; zero only cleans an already-inactive opening. */
    val close: (SettingsProfileExtrasToken, Long) -> Unit = { _, _ -> },
    val refresh: (SettingsProfileExtrasToken, Long, (Long?) -> Unit) -> Unit = { _, _, admitted -> admitted(null) },
    val acquireFile: (SettingsCurrentProfilePhotoMediaRequest, () -> Boolean,
        (SettingsResult<MediaFile>) -> Unit) -> SettingsCurrentProfilePhotoFileLease =
        { _, _, result -> unavailableCurrentProfilePhotoFile(result) },
)
