package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import java.util.concurrent.atomic.AtomicBoolean

interface TdSettingsStorageSession {
    fun storageDetails(active: () -> Boolean, result: (SettingsResult<SettingsStorageDetails>) -> Unit)
    fun clearStorage(types: Set<SettingsStorageFileType>, active: () -> Boolean, result: (SettingsResult<Unit>) -> Unit)
    fun storageDetailsOwned(owner: SettingsProxyProbeOwner, result: (SettingsResult<SettingsStorageDetails>) -> Unit) =
        storageDetails(owner::current, result)
    fun clearStorageOwned(types: Set<SettingsStorageFileType>, owner: SettingsProxyProbeOwner, result: (SettingsResult<Unit>) -> Unit) =
        clearStorage(types, owner::current, result)
    fun lastClearError(): SettingsError? = null
}

/** Uses TDLib's native scanner and GC; never deletes filesystem paths directly. */
internal class OfficialTdSettingsStorage(
    coordinator: SettingsStorageCoordinator? = null,
    private val send: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit,
) : TdSettingsStorageSession {
    private val coordinator = coordinator ?: SettingsStorageCoordinator { command, reply, failed ->
        send(command, reply, failed); true
    }
    override fun lastClearError(): SettingsError? = coordinator.lastClearError()
    override fun storageDetails(active: () -> Boolean, result: (SettingsResult<SettingsStorageDetails>) -> Unit) {
        storageDetailsOwned(SettingsProxyProbeOwner(active), result)
    }

    override fun storageDetailsOwned(owner: SettingsProxyProbeOwner, result: (SettingsResult<SettingsStorageDetails>) -> Unit) {
        request(TdApi.GetStorageStatistics(0), owner, { response ->
            if (response is TdApi.StorageStatistics) mapStatistics(response) else SettingsResult(error = settingsError(response))
        }, result)
    }

    override fun clearStorage(types: Set<SettingsStorageFileType>, active: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) {
        clearStorageOwned(types, SettingsProxyProbeOwner(active), result)
    }

    override fun clearStorageOwned(types: Set<SettingsStorageFileType>, owner: SettingsProxyProbeOwner, result: (SettingsResult<Unit>) -> Unit) {
        if (types.isEmpty() || types.any { !it.removable }) {
            result(SettingsResult(error = SettingsError.INVALID_VALUE)); return
        }
        request(TdApi.OptimizeStorage(0, 0, 0, 0, types.sortedBy { it.ordinal }.map(::nativeType).toTypedArray(),
            longArrayOf(), longArrayOf(), false, 0), owner, { response ->
            if (response is TdApi.StorageStatistics) SettingsResult(Unit) else SettingsResult(error = settingsError(response))
        }, result)
    }

    private fun mapStatistics(native: TdApi.StorageStatistics): SettingsResult<SettingsStorageDetails> {
        if (native.size < 0 || native.count < 0 || native.byChat.orEmpty().size > MAX_CHAT_BUCKETS) return invalid()
        val totals = mutableMapOf<SettingsStorageFileType, Pair<Long, Long>>()
        for (chat in native.byChat.orEmpty()) {
            if (chat.size < 0 || chat.count < 0 || chat.byFileType.orEmpty().size > MAX_TYPE_BUCKETS) return invalid()
            for (bucket in chat.byFileType.orEmpty()) {
                if (bucket.size < 0 || bucket.count < 0) return invalid()
                val type = modelType(bucket.fileType) ?: if (bucket.size == 0L && bucket.count == 0) continue else return invalid()
                val previous = totals[type] ?: (0L to 0L)
                val bytes = Math.addExact(previous.first, bucket.size)
                val count = Math.addExact(previous.second, bucket.count.toLong())
                if (count > Int.MAX_VALUE) return invalid()
                totals[type] = bytes to count
            }
        }
        val categories = totals.entries.sortedBy { it.key.ordinal }
            .map { SettingsStorageCategory(it.key, it.value.first, it.value.second.toInt()) }
        // Missing native buckets are not fabricated as zero or silently attributed to another type.
        if (categories.sumOf { it.bytes } != native.size || categories.sumOf { it.count.toLong() } != native.count.toLong()) return invalid()
        return SettingsResult(SettingsStorageDetails(native.size, native.count, categories))
    }

    private fun <T> request(command: TdApi.Function<*>, owner: SettingsProxyProbeOwner,
        map: (TdApi.Object) -> SettingsResult<T>, result: (SettingsResult<T>) -> Unit,
    ) {
        coordinator.submit(command, owner) { response ->
            val value = response.value
            result(if (value == null) SettingsResult(error = response.error ?: SettingsError.REQUEST_FAILED)
                else try { map(value) } catch (_: Exception) { SettingsResult(error = SettingsError.REQUEST_FAILED) })
        }
    }

    private fun invalid() = SettingsResult<SettingsStorageDetails>(error = SettingsError.REQUEST_FAILED)

    private fun modelType(type: TdApi.FileType?): SettingsStorageFileType? = when (type) {
        is TdApi.FileTypePhoto -> SettingsStorageFileType.PHOTO
        is TdApi.FileTypeVideo -> SettingsStorageFileType.VIDEO
        is TdApi.FileTypeAnimation -> SettingsStorageFileType.ANIMATION
        is TdApi.FileTypeDocument -> SettingsStorageFileType.DOCUMENT
        is TdApi.FileTypeAudio -> SettingsStorageFileType.AUDIO
        is TdApi.FileTypeVoiceNote -> SettingsStorageFileType.VOICE_NOTE
        is TdApi.FileTypeVideoNote -> SettingsStorageFileType.VIDEO_NOTE
        is TdApi.FileTypeSticker -> SettingsStorageFileType.STICKER
        is TdApi.FileTypeThumbnail -> SettingsStorageFileType.THUMBNAIL
        is TdApi.FileTypeProfilePhoto -> SettingsStorageFileType.PROFILE_PHOTO
        is TdApi.FileTypeWallpaper -> SettingsStorageFileType.WALLPAPER
        is TdApi.FileTypeNotificationSound -> SettingsStorageFileType.NOTIFICATION_SOUND
        is TdApi.FileTypeLivePhotoVideo -> SettingsStorageFileType.LIVE_PHOTO_VIDEO
        is TdApi.FileTypePhotoStory -> SettingsStorageFileType.PHOTO_STORY
        is TdApi.FileTypeVideoStory -> SettingsStorageFileType.VIDEO_STORY
        is TdApi.FileTypeSecret -> SettingsStorageFileType.SECRET
        is TdApi.FileTypeSecretThumbnail -> SettingsStorageFileType.SECRET_THUMBNAIL
        is TdApi.FileTypeSecure -> SettingsStorageFileType.SECURE
        is TdApi.FileTypeSelfDestructingPhoto -> SettingsStorageFileType.SELF_DESTRUCTING_PHOTO
        is TdApi.FileTypeSelfDestructingVideo -> SettingsStorageFileType.SELF_DESTRUCTING_VIDEO
        is TdApi.FileTypeSelfDestructingLivePhotoVideo -> SettingsStorageFileType.SELF_DESTRUCTING_LIVE_PHOTO_VIDEO
        is TdApi.FileTypeSelfDestructingVideoNote -> SettingsStorageFileType.SELF_DESTRUCTING_VIDEO_NOTE
        is TdApi.FileTypeSelfDestructingVoiceNote -> SettingsStorageFileType.SELF_DESTRUCTING_VOICE_NOTE
        is TdApi.FileTypeUnknown -> SettingsStorageFileType.UNKNOWN
        else -> null
    }

    private fun nativeType(type: SettingsStorageFileType): TdApi.FileType = when (type) {
        SettingsStorageFileType.PHOTO -> TdApi.FileTypePhoto()
        SettingsStorageFileType.VIDEO -> TdApi.FileTypeVideo()
        SettingsStorageFileType.ANIMATION -> TdApi.FileTypeAnimation()
        SettingsStorageFileType.DOCUMENT -> TdApi.FileTypeDocument()
        SettingsStorageFileType.AUDIO -> TdApi.FileTypeAudio()
        SettingsStorageFileType.VOICE_NOTE -> TdApi.FileTypeVoiceNote()
        SettingsStorageFileType.VIDEO_NOTE -> TdApi.FileTypeVideoNote()
        SettingsStorageFileType.STICKER -> TdApi.FileTypeSticker()
        SettingsStorageFileType.THUMBNAIL -> TdApi.FileTypeThumbnail()
        SettingsStorageFileType.PROFILE_PHOTO -> TdApi.FileTypeProfilePhoto()
        SettingsStorageFileType.WALLPAPER -> TdApi.FileTypeWallpaper()
        SettingsStorageFileType.NOTIFICATION_SOUND -> TdApi.FileTypeNotificationSound()
        SettingsStorageFileType.LIVE_PHOTO_VIDEO -> TdApi.FileTypeLivePhotoVideo()
        SettingsStorageFileType.PHOTO_STORY -> TdApi.FileTypePhotoStory()
        SettingsStorageFileType.VIDEO_STORY -> TdApi.FileTypeVideoStory()
        SettingsStorageFileType.SECRET -> TdApi.FileTypeSecret()
        SettingsStorageFileType.SECRET_THUMBNAIL -> TdApi.FileTypeSecretThumbnail()
        SettingsStorageFileType.SECURE -> TdApi.FileTypeSecure()
        SettingsStorageFileType.SELF_DESTRUCTING_PHOTO -> TdApi.FileTypeSelfDestructingPhoto()
        SettingsStorageFileType.SELF_DESTRUCTING_VIDEO -> TdApi.FileTypeSelfDestructingVideo()
        SettingsStorageFileType.SELF_DESTRUCTING_LIVE_PHOTO_VIDEO -> TdApi.FileTypeSelfDestructingLivePhotoVideo()
        SettingsStorageFileType.SELF_DESTRUCTING_VIDEO_NOTE -> TdApi.FileTypeSelfDestructingVideoNote()
        SettingsStorageFileType.SELF_DESTRUCTING_VOICE_NOTE -> TdApi.FileTypeSelfDestructingVoiceNote()
        SettingsStorageFileType.UNKNOWN -> TdApi.FileTypeUnknown()
    }

    private companion object { const val MAX_CHAT_BUCKETS = 64; const val MAX_TYPE_BUCKETS = 64 }
}
