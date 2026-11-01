package com.badnng.moe.settings

import com.badnng.moe.chat.AttachmentLease

data class SettingsProfileExtrasToken(val generation: Long = 0, val owner: Long = 0, val userId: Long = 0)
data class SettingsCalendarDate(val day: Int, val month: Int, val year: Int)
data class SettingsBirthdate(val day: Int, val month: Int, val year: Int = 0) {
    override fun toString() = "SettingsBirthdate(<redacted>)"
}

/** Unknown year is intentional; invalid dates never mean 'remove'. */
fun SettingsBirthdate.isValidProfileBirthdate(today: SettingsCalendarDate, current: SettingsBirthdate? = null): Boolean {
    if (month !in 1..12 || year != 0 && year !in 1875..2100) return false
    val days = when (month) {
        2 -> if (year == 0 || year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }
    if (day !in 1..days) return false
    if (year == 0) return true
    fun key(y: Int, m: Int, d: Int) = y * 10000 + m * 100 + d
    val maximum = maxOf(key(today.year, today.month, today.day), current?.takeIf { it.year != 0 }
        ?.let { key(it.year, it.month, it.day) } ?: 0)
    return key(year, month, day) <= maximum
}

/** The immutable prepared JPEG is owned independently by the editor and the engine upload. */
data class SettingsPreparedProfilePhoto(
    val path: String, val size: Long, val dimension: Int, val sha256: String, val lease: AttachmentLease,
) { override fun toString() = "SettingsPreparedProfilePhoto(<redacted>)" }

data class SettingsProfileExtrasData(
    val userId: Long, val photoId: Long?, val birthdate: SettingsBirthdate?, val today: SettingsCalendarDate,
    val personalChatId: Long = 0, val personalChannel: SettingsPersonalChannel? = null,
    val personalChannelError: SettingsError? = null,
) { override fun toString() = "SettingsProfileExtrasData(<redacted>)" }
data class SettingsPersonalChannel(val chatId: Long, val title: String, val username: String) {
    override fun toString() = "SettingsPersonalChannel(<redacted>)"
}
data class SettingsPersonalChannelCatalog(val ids: List<Long>, val totalCount: Int?, val limited: Boolean) {
    override fun toString() = "SettingsPersonalChannelCatalog(<redacted>)"
}
data class SettingsPersonalChannelsPage(val entries: List<SettingsPersonalChannel>, val unavailableCount: Int) {
    override fun toString() = "SettingsPersonalChannelsPage(<redacted>)"
}
enum class SettingsProfileExtrasOperation { UPLOAD_PHOTO, REMOVE_PHOTO, BIRTHDATE, PERSONAL_CHANNEL, REUSE_PHOTO, REMOVE_HISTORY_PHOTO, UPLOAD_ANIMATION, UPLOAD_STICKER_AVATAR }
data class SettingsProfileExtrasSnapshot(
    val token: SettingsProfileExtrasToken = SettingsProfileExtrasToken(),
    val data: SettingsProfileExtrasData? = null, val loading: Boolean = false, val busy: Boolean = false,
    val error: SettingsError? = null, val operation: SettingsProfileExtrasOperation? = null,
    val startedRevision: Long = 0, val acceptedRevision: Long = 0,
    val completedRevision: Long = 0, val failedRevision: Long = 0,
    val channelsOpened: Boolean = false, val channelsLoading: Boolean = false,
    val channels: List<SettingsPersonalChannel> = emptyList(), val channelsTotal: Int? = null,
    val channelsHasMore: Boolean = false, val channelsLimited: Boolean = false,
    val channelsUnavailable: Int = 0, val channelsError: SettingsError? = null,
    val history: SettingsProfilePhotoHistorySnapshot = SettingsProfilePhotoHistorySnapshot(),
    val currentPhoto: SettingsCurrentProfilePhotoSnapshot = SettingsCurrentProfilePhotoSnapshot(),
) { override fun toString() = "SettingsProfileExtrasSnapshot(<redacted>, busy=$busy)" }

data class SettingsProfileExtrasActions(
    /** Synchronous external page lease also owns a queued open before its native token exists. */
    val open: (SettingsToken, Long, () -> Boolean) -> Unit = { _, _, _ -> },
    val close: (SettingsProfileExtrasToken) -> Unit = {},
    val refresh: (SettingsProfileExtrasToken) -> Unit = {},
    val saveBirthdate: (SettingsProfileExtrasToken, SettingsBirthdate?, SettingsBirthdate?) -> Unit = { _, _, _ -> },
    /** A live host synchronously retains photo.lease BEFORE enqueueing, releasing on rejected enqueue. */
    val uploadPhoto: (SettingsProfileExtrasToken, Long?, SettingsPreparedProfilePhoto) -> Unit = { _, _, _ -> },
    val removePhoto: (SettingsProfileExtrasToken, Long) -> Unit = { _, _ -> },
    val openPersonalChannels: (SettingsProfileExtrasToken, () -> Boolean) -> Unit = { _, _ -> },
    val closePersonalChannels: (SettingsProfileExtrasToken) -> Unit = {},
    val morePersonalChannels: (SettingsProfileExtrasToken) -> Unit = {},
    val savePersonalChannel: (SettingsProfileExtrasToken, Long, Long) -> Unit = { _, _, _ -> },
    val history: SettingsProfilePhotoHistoryActions = SettingsProfilePhotoHistoryActions(),
    /** Null declines admission; a revision identifies this actor mutation, never native success. Retains both handles before enqueueing. */
    val uploadAnimation: (SettingsProfileExtrasToken, Long?, SettingsPreparedProfileAnimation, (Long?) -> Unit) -> Unit =
        { _, _, _, admitted -> admitted(null) },
    val stickerAvatar: SettingsProfileStickerAvatarActions = SettingsProfileStickerAvatarActions(),
    val currentPhoto: SettingsCurrentProfilePhotoActions = SettingsCurrentProfilePhotoActions(),
)
