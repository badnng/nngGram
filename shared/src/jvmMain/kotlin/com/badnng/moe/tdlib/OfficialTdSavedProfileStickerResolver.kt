package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import com.badnng.moe.chat.MediaFile
import org.drinkless.tdlib.TdApi
import java.util.concurrent.atomic.AtomicBoolean

/** Native lookup only. The caller must first capture a current/history source grant. */
internal class OfficialTdSavedProfileStickerResolver(
    private val send: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit,
) {
    /** The id is taken only from the previously resolved native Sticker, never from a UI request. */
    fun freshFileOwned(fileId: Int, active: () -> Boolean, terminal: () -> Unit,
        result: (SettingsResult<MediaFile>) -> Unit,
    ) {
        val done = AtomicBoolean(); val physical = AtomicBoolean()
        fun current() = runCatching(active).getOrDefault(false)
        fun finish(value: SettingsResult<MediaFile>) {
            if (done.compareAndSet(false, true)) runCatching {
                result(if (current()) value else SettingsResult(error = SettingsError.NOT_READY))
            }
        }
        if (fileId <= 0 || !current()) {
            try { finish(SettingsResult(error = if (fileId <= 0) SettingsError.INVALID_VALUE else SettingsError.NOT_READY)) }
            finally { if (physical.compareAndSet(false, true)) runCatching(terminal) }
            return
        }
        try {
            send(TdApi.GetFile(fileId), received@ { response ->
                if (!physical.compareAndSet(false, true)) return@received
                try {
                    val file = response as? TdApi.File
                    if (file == null) finish(SettingsResult(error = settingsError(response)))
                    else if (file.size < 0 || (file.local?.downloadedSize ?: 0) < 0)
                        finish(SettingsResult(error = SettingsError.CONFLICT))
                    else finish(SettingsResult(mapMediaFile(file).copy(localPath = null)))
                } catch (_: Throwable) { finish(SettingsResult(error = SettingsError.REQUEST_FAILED)) }
                finally { runCatching(terminal) }
            }, failed@ {
                if (!physical.compareAndSet(false, true)) return@failed
                try { finish(SettingsResult(error = SettingsError.NOT_READY)) } finally { runCatching(terminal) }
            })
        } catch (_: Throwable) {
            // A thrown send may still have dispatched; only the real handler ends its reservation.
            finish(SettingsResult(error = SettingsError.REQUEST_FAILED))
        }
    }

    fun resolve(sticker: SettingsProfilePhotoSticker, active: () -> Boolean,
        result: (SettingsResult<ProfileStickerAvatarMediaFingerprint>) -> Unit,
    ) = resolveOwned(sticker, active, {}, result = result)

    /** Terminal represents the real native handler, even after a logical cancellation. */
    fun resolveOwned(sticker: SettingsProfilePhotoSticker, active: () -> Boolean, terminal: () -> Unit,
        checkNative: (TdApi.Sticker) -> SettingsError? = { null },
        result: (SettingsResult<ProfileStickerAvatarMediaFingerprint>) -> Unit,
    ) {
        val done = AtomicBoolean()
        val physical = AtomicBoolean()
        fun current() = runCatching(active).getOrDefault(false)
        fun finish(value: SettingsResult<ProfileStickerAvatarMediaFingerprint>) {
            if (done.compareAndSet(false, true)) runCatching {
                result(if (current()) value else SettingsResult(error = SettingsError.NOT_READY))
            }
        }
        fun finishBeforeSend(value: SettingsResult<ProfileStickerAvatarMediaFingerprint>) {
            try { finish(value) } finally { if (physical.compareAndSet(false, true)) runCatching(terminal) }
        }
        if (!current()) { finishBeforeSend(SettingsResult(error = SettingsError.NOT_READY)); return }
        val command: TdApi.Function<*> = when (val type = sticker.type) {
            is SettingsProfilePhotoSticker.Type.RegularOrMask -> if (type.stickerSetId != 0L && type.stickerId != 0L)
                TdApi.GetStickerSet(type.stickerSetId) else { finishBeforeSend(SettingsResult(error = SettingsError.INVALID_VALUE)); return }
            is SettingsProfilePhotoSticker.Type.CustomEmoji -> if (type.customEmojiId != 0L)
                TdApi.GetCustomEmojiStickers(longArrayOf(type.customEmojiId)) else { finishBeforeSend(SettingsResult(error = SettingsError.INVALID_VALUE)); return }
        }
        try {
            send(command, received@ { response ->
                if (!physical.compareAndSet(false, true)) return@received
                try {
                if (done.get()) return@received
                if (!current()) { finish(SettingsResult(error = SettingsError.NOT_READY)); return@received }
                if (response is TdApi.Error) { finish(SettingsResult(error = settingsError(response))); return@received }
                val found = when (val type = sticker.type) {
                    is SettingsProfilePhotoSticker.Type.RegularOrMask -> {
                        val set = response as? TdApi.StickerSet
                        if (set == null || set.id != type.stickerSetId) null else {
                            val selected = set.stickers.orEmpty().singleOrNull {
                                it != null && it.id == type.stickerId
                            }?.takeIf { it.setId == type.stickerSetId }
                            when (set.stickerType) {
                                is TdApi.StickerTypeRegular -> selected?.takeIf { it.fullType is TdApi.StickerFullTypeRegular }
                                is TdApi.StickerTypeMask -> selected?.takeIf {
                                    it.fullType is TdApi.StickerFullTypeMask && it.format is TdApi.StickerFormatWebp
                                }
                                else -> null
                            }
                        }
                    }
                    is SettingsProfilePhotoSticker.Type.CustomEmoji -> (response as? TdApi.Stickers)?.stickers.orEmpty()
                        .singleOrNull { (it?.fullType as? TdApi.StickerFullTypeCustomEmoji)?.customEmojiId == type.customEmojiId }
                }
                if (found == null) { finish(SettingsResult(error = SettingsError.CONFLICT)); return@received }
                val contradiction = checkNative(found)
                if (contradiction != null) { finish(SettingsResult(error = contradiction)); return@received }
                if (found.format !is TdApi.StickerFormatWebp && found.format !is TdApi.StickerFormatTgs &&
                    found.format !is TdApi.StickerFormatWebm) {
                    finish(SettingsResult(error = SettingsError.UNSUPPORTED)); return@received
                }
                if (found.sticker == null || found.sticker.remote?.uniqueId.isNullOrBlank()) {
                    finish(SettingsResult(error = SettingsError.NOT_READY)); return@received
                }
                val fingerprint = profileStickerAvatarFingerprint(found)
                finish(if (fingerprint == null) SettingsResult(error = SettingsError.INVALID_VALUE) else SettingsResult(fingerprint))
                } catch (_: Throwable) { finish(SettingsResult(error = SettingsError.REQUEST_FAILED)) }
                finally { runCatching(terminal) }
            }, failed@ {
                if (!physical.compareAndSet(false, true)) return@failed
                try { finish(SettingsResult(error = SettingsError.NOT_READY)) } finally { runCatching(terminal) }
            })
        } catch (_: Throwable) {
            // Dispatch may have happened; a real callback alone ends the physical reservation.
            finish(SettingsResult(error = SettingsError.REQUEST_FAILED))
        }
    }
}
