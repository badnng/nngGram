package com.badnng.moe.settings

import com.badnng.moe.chat.MediaFile

enum class SettingsProfilePhotoHistoryMediaUse { VISIBLE_CELL, EXPLICIT_PREVIEW, EXPLICIT_ANIMATION }

/** A request names an existing entry, never a caller-supplied file or local path. */
data class SettingsProfilePhotoHistoryMediaRequest(
    val parent: SettingsProfileExtrasToken,
    val epoch: Long,
    val photoId: Long,
    val use: SettingsProfilePhotoHistoryMediaUse,
    val targetWidthPx: Int,
    val targetHeightPx: Int,
) { override fun toString() = "SettingsProfilePhotoHistoryMediaRequest(<redacted>)" }

interface SettingsProfilePhotoHistoryFileLease { fun close() }

internal fun unavailableProfilePhotoHistoryFile(
    result: (SettingsResult<MediaFile>) -> Unit,
): SettingsProfilePhotoHistoryFileLease {
    result(SettingsResult(error = SettingsError.UNSUPPORTED))
    return object : SettingsProfilePhotoHistoryFileLease { override fun close() {} }
}
