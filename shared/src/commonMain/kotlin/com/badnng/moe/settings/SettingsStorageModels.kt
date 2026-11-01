package com.badnng.moe.settings

data class SettingsStorageToken(val generation: Long = 0, val owner: Long = 0)

/** Exact native types; secret thumbnails are visible but TDLib does not permit their deletion. */
enum class SettingsStorageFileType(val label: String, val removable: Boolean = true) {
    PHOTO("照片"), VIDEO("视频"), ANIMATION("动画 GIF"), DOCUMENT("文件"), AUDIO("音乐"),
    VOICE_NOTE("语音消息"), VIDEO_NOTE("圆形视频"), STICKER("贴纸"),
    THUMBNAIL("缩略图"), PROFILE_PHOTO("头像"), WALLPAPER("聊天背景"), NOTIFICATION_SOUND("通知声音"),
    LIVE_PHOTO_VIDEO("实况照片视频"), PHOTO_STORY("限时动态照片"), VIDEO_STORY("限时动态视频"),
    SECRET("私密聊天文件"), SECRET_THUMBNAIL("私密聊天缩略图", false), SECURE("Telegram Passport 文件"),
    SELF_DESTRUCTING_PHOTO("阅后即焚照片"), SELF_DESTRUCTING_VIDEO("阅后即焚视频"),
    SELF_DESTRUCTING_LIVE_PHOTO_VIDEO("阅后即焚实况视频"), SELF_DESTRUCTING_VIDEO_NOTE("阅后即焚圆形视频"),
    SELF_DESTRUCTING_VOICE_NOTE("阅后即焚语音"), UNKNOWN("其他文件"),
}

data class SettingsStorageCategory(val type: SettingsStorageFileType, val bytes: Long, val count: Int)
data class SettingsStorageDetails(val bytes: Long, val count: Int, val categories: List<SettingsStorageCategory>)

/** A system filesystem sample for the accepted native files directory, never a cache measurement. */
sealed interface SettingsStorageVolume {
    data object Unavailable : SettingsStorageVolume
    data object Loading : SettingsStorageVolume
    data class Available(val totalBytes: Long, val usableBytes: Long) : SettingsStorageVolume
}

fun validSettingsStorageVolume(value: SettingsStorageVolume.Available): Boolean =
    value.totalBytes > 0 && value.totalBytes < Long.MAX_VALUE && value.usableBytes in 0L..value.totalBytes

data class SettingsStorageSnapshot(
    val token: SettingsStorageToken = SettingsStorageToken(),
    val opened: Boolean = false,
    val loading: Boolean = false,
    val clearing: Boolean = false,
    val revision: Long = 0,
    val details: SettingsStorageDetails? = null,
    val error: SettingsError? = null,
    /** Native deletion succeeded, but a later statistics read may still fail. */
    val completedClearRevision: Long = 0,
    val refreshAfterClearFailed: Boolean = false,
    /** Cleared only by a new validated full statistics read. */
    val needsFreshStatistics: Boolean = false,
    /** Kept across the required reread; a failure may have partially deleted files. */
    val lastClearError: SettingsError? = null,
    val volume: SettingsStorageVolume = SettingsStorageVolume.Unavailable,
)

fun interface SettingsStorageHandle {
    fun close()
    /** The production owner is synchronously revoked on close, stop or authorization loss. */
    fun current(): Boolean = true
}

data class SettingsStorageActions(
    /** The page lease prevents a queued open from reviving a manager that already left composition. */
    val open: (() -> Boolean) -> Unit = {},
    val close: (SettingsStorageToken) -> Unit = {},
    val refresh: (SettingsStorageToken) -> Unit = {},
    val clear: (SettingsStorageToken, Long, Set<SettingsStorageFileType>) -> Unit = { _, _, _ -> },
    /** Production supplies a synchronously revocable visit; legacy previews can use open. */
    val openOwned: ((() -> Boolean) -> SettingsStorageHandle?)? = null,
    /** A separate filesystem sample; it never starts a TDLib statistics read. */
    val refreshVolume: (SettingsStorageToken) -> Unit = {},
    /** Null handle rejects synchronously; otherwise reports this visit's exact token or null on rejection/revocation. */
    val openOwnedObserved: ((() -> Boolean, (SettingsStorageToken?) -> Unit) -> SettingsStorageHandle?)? = null,
)

/** Frozen confirmation matches its read revision; a refresh requires a fresh confirmation. */
data class SettingsStorageCleanup(
    val token: SettingsStorageToken,
    val revision: Long,
    val categories: List<SettingsStorageCategory>,
) {
    val bytes: Long get() = categories.sumOf { it.bytes }
    val count: Long get() = categories.sumOf { it.count.toLong() }
    val types: Set<SettingsStorageFileType> get() = categories.map { it.type }.toSet()
}
