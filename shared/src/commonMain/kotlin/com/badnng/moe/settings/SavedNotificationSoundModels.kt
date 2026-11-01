package com.badnng.moe.settings

import com.badnng.moe.chat.MediaFile
import com.badnng.moe.chat.AttachmentLease

/** Server sound identity and TDLib download identity are independent. */
data class SavedNotificationSound(
    val id: Long, val durationSeconds: Int, val date: Int,
    val title: String, val data: String, val file: MediaFile,
) { override fun toString() = "SavedNotificationSound(<redacted>)" }

/** Missing or malformed native options stay unknown; they are never replaced with guessed limits. */
data class NotificationSoundLimits(
    val maximumBytes: Long? = null, val maximumDurationSeconds: Int? = null, val maximumCount: Int? = null,
) {
    val known: Boolean get() = maximumBytes != null && maximumBytes > 0 &&
        maximumDurationSeconds != null && maximumDurationSeconds > 0 && maximumCount != null && maximumCount > 0
}

data class SavedNotificationSoundsData(
    val accountUserId: Long, val sounds: List<SavedNotificationSound>,
    val limits: NotificationSoundLimits = NotificationSoundLimits(), val limitsError: SettingsError? = null,
    val nativeRevision: Long = 0,
) { override fun toString() = "SavedNotificationSoundsData(<redacted>)" }

/** A caller-owned staged file, validated by bounded decoding before the native upload boundary. */
data class PreparedNotificationSoundUpload(
    val path: String, val sizeBytes: Long, val durationMillis: Long,
    val sha256: String = "",
) { override fun toString() = "PreparedNotificationSoundUpload(<redacted>)" }

data class SavedNotificationSoundMutationResult(
    val locallyAccepted: Boolean = false, val sound: SavedNotificationSound? = null,
    val actual: SavedNotificationSoundsData? = null, val error: SettingsError? = null,
    val receipt: NotificationSettingsWriteReceipt = if (locallyAccepted) NotificationSettingsWriteReceipt.ACCEPTED
        else NotificationSettingsWriteReceipt.NOT_DISPATCHED,
) { override fun toString() = "SavedNotificationSoundMutationResult(<redacted>, accepted=$locallyAccepted, error=$error)" }

data class SettingsPreparedNotificationSound(val upload: PreparedNotificationSoundUpload, val lease: AttachmentLease) {
    override fun toString() = "SettingsPreparedNotificationSound(<redacted>)"
}

data class SavedNotificationSoundsToken(val generation: Long = 0, val owner: Long = 0)

data class SavedNotificationSoundsSnapshot(
    val token: SavedNotificationSoundsToken = SavedNotificationSoundsToken(), val opened: Boolean = false,
    val loading: Boolean = false, val saving: Boolean = false, val choosing: Boolean = false,
    val revision: Long = 0, val acceptedRevision: Long = 0,
    val data: SavedNotificationSoundsData? = null, val error: SettingsError? = null,
    val refreshRequired: Boolean = false, val readbackPending: Boolean = false,
    val previewSoundId: Long? = null, val previewLoading: Boolean = false,
    val previewError: SettingsError? = null, val previewDefaultFallback: Boolean = false,
    val lastAddedSoundId: Long? = null,
) { override fun toString() = "SavedNotificationSoundsSnapshot(<redacted>, opened=$opened)" }

data class SavedNotificationSoundsActions(
    val open: (SettingsToken, () -> Boolean) -> Unit = { _, _ -> },
    val close: (SavedNotificationSoundsToken) -> Unit = {},
    val refresh: (SavedNotificationSoundsToken) -> Unit = {},
    val choose: (SavedNotificationSoundsToken, Long, Long, (SettingsResult<Long>) -> Unit) -> Unit =
        { _, _, _, result -> result(SettingsResult(error = SettingsError.NOT_READY)) },
    val preview: (SavedNotificationSoundsToken, Long, Int) -> Unit = { _, _, _ -> },
    val stopPreview: (SavedNotificationSoundsToken) -> Unit = {},
    /** Live admission retains prepared.lease synchronously before actor enqueue. */
    val upload: (SavedNotificationSoundsToken, Long, SettingsPreparedNotificationSound) -> Unit = { _, _, _ -> },
    val remove: (SavedNotificationSoundsToken, Long, SavedNotificationSound) -> Unit = { _, _, _ -> },
    val readVolume: (SavedNotificationSoundsToken, NotificationSoundVolumeTarget, (SettingsResult<NotificationSoundVolumeReading>) -> Unit) -> Unit =
        { _, _, result -> result(SettingsResult(error = SettingsError.NOT_READY)) },
    val saveVolume: (SavedNotificationSoundsToken, NotificationSoundVolumeReading, Int?, (SettingsResult<NotificationSoundVolumeReading>) -> Unit) -> Unit =
        { _, _, _, result -> result(SettingsResult(error = SettingsError.NOT_READY)) },
)
