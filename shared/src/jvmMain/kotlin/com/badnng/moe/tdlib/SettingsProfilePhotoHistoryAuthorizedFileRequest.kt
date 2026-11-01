package com.badnng.moe.tdlib

import com.badnng.moe.settings.SettingsProfilePhotoHistoryMediaUse

/** Constructed by the actor only from the current entry and its native page authorization. */
data class SettingsProfilePhotoHistoryAuthorizedFileRequest(
    val userId: Long,
    val authorization: Long,
    val photoId: Long,
    val rawOffset: Int,
    val use: SettingsProfilePhotoHistoryMediaUse,
    val targetWidthPx: Int,
    val targetHeightPx: Int,
) { override fun toString() = "SettingsProfilePhotoHistoryAuthorizedFileRequest(<redacted>)" }
