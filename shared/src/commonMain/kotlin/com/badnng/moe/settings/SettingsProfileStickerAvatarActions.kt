package com.badnng.moe.settings

/** The original actor mutation result; success is an operation value, not proof of a server write. */
data class ProfileStickerAvatarMutationResult(
    val token: SettingsProfileExtrasToken,
    val revision: Long,
    val error: SettingsError?,
) { override fun toString() = "ProfileStickerAvatarMutationResult(<redacted>, error=$error)" }

/**
 * Native tickets are capabilities for this picker only. They do not authorize artwork downloads.
 * Every active predicate must be a thread-safe, one-way owner/query/confirmation lease.
 */
data class SettingsProfileStickerAvatarActions(
    val open: (SettingsProfileExtrasToken, () -> Boolean, (SettingsResult<ProfileStickerAvatarPickerTicket>) -> Unit) -> Unit =
        { _, _, result -> runCatching { result(SettingsResult(error = SettingsError.UNSUPPORTED)) } },
    val close: (SettingsProfileExtrasToken, ProfileStickerAvatarPickerTicket) -> Unit = { _, _ -> },
    val loadDefault: (SettingsProfileExtrasToken, ProfileStickerAvatarPickerTicket, () -> Boolean,
        (SettingsResult<ProfileStickerAvatarCandidatePage>) -> Unit) -> Unit =
        { _, _, _, result -> runCatching { result(SettingsResult(error = SettingsError.UNSUPPORTED)) } },
    val loadInstalledPacks: (SettingsProfileExtrasToken, ProfileStickerAvatarPickerTicket, ProfileStickerAvatarPackKind,
        () -> Boolean, (SettingsResult<ProfileStickerAvatarPackPage>) -> Unit) -> Unit =
        { _, _, _, _, result -> runCatching { result(SettingsResult(error = SettingsError.UNSUPPORTED)) } },
    val openPack: (SettingsProfileExtrasToken, ProfileStickerAvatarPickerTicket, ProfileStickerAvatarPackTicket,
        () -> Boolean, (SettingsResult<ProfileStickerAvatarCandidatePage>) -> Unit) -> Unit =
        { _, _, _, _, result -> runCatching { result(SettingsResult(error = SettingsError.UNSUPPORTED)) } },
    val select: (SettingsProfileExtrasToken, ProfileStickerAvatarPickerTicket, ProfileStickerAvatarCandidateTicket,
        () -> Boolean, (SettingsResult<ProfileStickerAvatarSelectionTicket>) -> Unit) -> Unit =
        { _, _, _, _, result -> runCatching { result(SettingsResult(error = SettingsError.UNSUPPORTED)) } },
    /** Result may precede admission; match both to the exact original submission and returned revision. */
    val save: (SettingsProfileExtrasToken, ProfileStickerAvatarPickerTicket, ProfileStickerAvatarSelectionTicket,
        Long?, ProfileStickerAvatarBackground, () -> Boolean, (Long?) -> Unit,
        (ProfileStickerAvatarMutationResult) -> Unit) -> Unit =
        { _, _, _, _, _, _, admitted, _ -> runCatching { admitted(null) } },
    val loadWindow: (SettingsProfileExtrasToken, ProfileStickerAvatarPickerTicket, ProfileStickerAvatarCatalogCursor,
        () -> Boolean, (SettingsResult<ProfileStickerAvatarCatalogWindow>) -> Unit) -> Unit =
        { _, _, _, _, result -> runCatching { result(SettingsResult(error = SettingsError.UNSUPPORTED)) } },
    val searchInstalled: (SettingsProfileExtrasToken, ProfileStickerAvatarPickerTicket, ProfileStickerAvatarPackKind,
        String, () -> Boolean, (SettingsResult<ProfileStickerAvatarPackPage>) -> Unit) -> Unit =
        { _, _, _, _, _, result -> runCatching { result(SettingsResult(error = SettingsError.UNSUPPORTED)) } },
)
