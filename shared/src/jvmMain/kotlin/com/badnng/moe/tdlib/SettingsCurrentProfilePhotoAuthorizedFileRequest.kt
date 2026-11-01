package com.badnng.moe.tdlib

import com.badnng.moe.chat.MediaFile
import com.badnng.moe.settings.*

/** Only the actor constructs this after checking current parent, epoch and photo identity. */
data class SettingsCurrentProfilePhotoAuthorizedFileRequest(
    val userId: Long,
    val authorization: Long,
    val photoId: Long,
    val use: SettingsCurrentProfilePhotoMediaUse,
    val targetWidthPx: Int,
    val targetHeightPx: Int,
) { override fun toString() = "SettingsCurrentProfilePhotoAuthorizedFileRequest(<redacted>)" }

/** Independent current-photo native authority; caller ids/paths never authorize files. */
internal interface CurrentProfilePhotoCatalog {
    fun resolve(request: SettingsCurrentProfilePhotoAuthorizedFileRequest): SettingsResult<SettingsProfilePhotoVariant>
    fun resolveAnimation(request: SettingsCurrentProfilePhotoAuthorizedFileRequest): SettingsResult<SettingsProfilePhotoAnimation>
    fun observeFile(request: SettingsCurrentProfilePhotoAuthorizedFileRequest, actual: MediaFile): SettingsResult<Unit>
}
