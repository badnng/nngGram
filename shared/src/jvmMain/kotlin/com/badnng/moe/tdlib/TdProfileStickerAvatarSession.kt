package com.badnng.moe.tdlib

import com.badnng.moe.settings.*

/** Metadata/markup service; tickets do not authorize downloading or decoding artwork. */
interface TdProfileStickerAvatarSession {
    fun open(userId: Long, active: () -> Boolean): ProfileStickerAvatarPickerTicket?
    fun close(ticket: ProfileStickerAvatarPickerTicket)
    /** Retires reads and future admissions; a delivered write keeps its original native result. */
    fun retire()
    fun loadDefault(ticket: ProfileStickerAvatarPickerTicket,
        result: (SettingsResult<ProfileStickerAvatarCandidatePage>) -> Unit)
    fun loadInstalledPacks(ticket: ProfileStickerAvatarPickerTicket, kind: ProfileStickerAvatarPackKind,
        result: (SettingsResult<ProfileStickerAvatarPackPage>) -> Unit)
    fun openPack(ticket: ProfileStickerAvatarPickerTicket, packTicket: ProfileStickerAvatarPackTicket,
        result: (SettingsResult<ProfileStickerAvatarCandidatePage>) -> Unit)
    fun loadWindow(ticket: ProfileStickerAvatarPickerTicket, cursor: ProfileStickerAvatarCatalogCursor,
        result: (SettingsResult<ProfileStickerAvatarCatalogWindow>) -> Unit) {
        result(SettingsResult(error = SettingsError.UNSUPPORTED))
    }
    fun searchInstalled(ticket: ProfileStickerAvatarPickerTicket, kind: ProfileStickerAvatarPackKind, query: String,
        result: (SettingsResult<ProfileStickerAvatarPackPage>) -> Unit) {
        result(SettingsResult(error = SettingsError.UNSUPPORTED))
    }
    fun select(ticket: ProfileStickerAvatarPickerTicket,
        candidateTicket: ProfileStickerAvatarCandidateTicket): ProfileStickerAvatarSelectionTicket?
    /** Caller supplies an irrevocable thread-safe confirmation/mutation lease independently of the picker. */
    fun save(ticket: ProfileStickerAvatarPickerTicket, selectionTicket: ProfileStickerAvatarSelectionTicket,
        expectedPhotoId: Long?, background: ProfileStickerAvatarBackground, active: () -> Boolean,
        result: (SettingsResult<Unit>) -> Unit)
}
