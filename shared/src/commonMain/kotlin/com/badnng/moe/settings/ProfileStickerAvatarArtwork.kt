package com.badnng.moe.settings

import androidx.compose.ui.graphics.ImageBitmap

/** Pixels are borrowed until the corresponding lease, clip, or provider is closed. */
interface ProfileStickerAvatarArtwork {
    val image: ImageBitmap?
    /** Only the native sticker's needsRepainting flag permits a white overlay. */
    val needsRepainting: Boolean
    /** Optional display-only retention of this already prepared immutable frame. */
    fun freezeDisplay(): ProfileStickerAvatarFrozenDisplay? = null
}

/** A separately owned display frame, with no authority to read media or save a selection. */
interface ProfileStickerAvatarFrozenDisplay : ProfileStickerAvatarArtwork, ProfileStickerAvatarArtworkLease

interface ProfileStickerAvatarArtworkLease { fun close() }
interface ProfileStickerAvatarSavedPreviewLease : ProfileStickerAvatarArtworkLease {
    fun requestCurrent(): Boolean
    /** Revokes only pending demand; it never disposes UI-borrowed artwork. */
    fun revokeDemand() {}
}

interface ProfileStickerAvatarPreviewClip {
    val animated: Boolean
    val durationMillis: Long
    /** Scheduling hint; VFR frame lookup follows the clip's native frame timestamps. */
    val frameDurationMillis: Long

    /** Optional source gate; old implementations remain usable by existing builder clients. */
    fun active(): Boolean = true

    fun frame(
        timeMillis: Long,
        active: () -> Boolean,
        result: (SettingsResult<ProfileStickerAvatarArtwork>) -> Unit,
    ): ProfileStickerAvatarArtworkLease

    fun close()
}

/** Opaque tickets identify authority-owned native media; IDs alone grant no file access. */
interface ProfileStickerAvatarArtworkProvider {
    val generation: Long

    fun thumbnail(
        parent: SettingsProfileExtrasToken,
        picker: ProfileStickerAvatarPickerTicket,
        candidate: ProfileStickerAvatarCandidateTicket,
        physicalSizePx: Int,
        active: () -> Boolean,
        result: (SettingsResult<ProfileStickerAvatarArtwork>) -> Unit,
    ): ProfileStickerAvatarArtworkLease

    fun preview(
        parent: SettingsProfileExtrasToken,
        picker: ProfileStickerAvatarPickerTicket,
        selection: ProfileStickerAvatarSelectionTicket,
        physicalSizePx: Int,
        active: () -> Boolean,
        result: (SettingsResult<ProfileStickerAvatarPreviewClip>) -> Unit,
    ): ProfileStickerAvatarArtworkLease

    /** Saved-photo authority is supplied by its owner, never by a builder ticket. */
    fun savedPreview(
        request: SettingsSavedProfileStickerAvatarRequest,
        physicalSizePx: Int,
        active: () -> Boolean,
        result: (SettingsResult<ProfileStickerAvatarPreviewClip>) -> Unit,
    ): ProfileStickerAvatarSavedPreviewLease {
        runCatching { if (active()) result(SettingsResult(error = SettingsError.UNSUPPORTED)) }
        return object : ProfileStickerAvatarSavedPreviewLease {
            override fun requestCurrent() = false
            override fun close() {}
        }
    }

    /** The caller creates and owns demand before any request can enter the native actor. */
    fun savedPreviewOwned(
        request: SettingsSavedProfileStickerAvatarRequest,
        physicalSizePx: Int,
        active: () -> Boolean,
        demand: SettingsSavedProfileStickerAvatarDemand,
        result: (SettingsResult<ProfileStickerAvatarPreviewClip>) -> Unit,
    ): ProfileStickerAvatarSavedPreviewLease = savedPreview(request, physicalSizePx, { demand.current() && active() }, result)

    fun close()
}
