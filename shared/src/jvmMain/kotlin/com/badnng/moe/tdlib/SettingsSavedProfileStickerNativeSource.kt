package com.badnng.moe.tdlib

import com.badnng.moe.settings.SettingsProfilePhotoSticker
import com.badnng.moe.settings.SettingsError
import com.badnng.moe.settings.SettingsResult
import com.badnng.moe.settings.SettingsSavedProfileStickerAvatarDemand
import com.badnng.moe.chat.MediaFile
import com.badnng.moe.chat.MediaFormat
import org.drinkless.tdlib.TdApi
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Actor-derived canonical ticket; never constructed from UI sticker/file fields. */
internal sealed interface SettingsSavedProfileStickerNativeSource {
    val userId: Long
    val authorization: Long
    val photoId: Long
    data class Current(override val userId: Long, override val authorization: Long,
        override val photoId: Long) : SettingsSavedProfileStickerNativeSource
    data class History(override val userId: Long, override val authorization: Long,
        override val photoId: Long, val rawOffset: Int) : SettingsSavedProfileStickerNativeSource
}

/** atomicCurrent is safe for a broker monitor; sourceCurrent may inspect foreign view predicates. */
internal class SavedProfileStickerCaptured(
    val sticker: SettingsProfilePhotoSticker,
    val atomicCurrent: () -> Boolean,
    val sourceCurrent: () -> Boolean,
    val facts: SavedProfileStickerFacts,
    val invalidate: () -> Unit,
)

/** Created only by the Extras actor from its verified current/history owner. */
internal class SavedProfileStickerActorGrant(
    val captured: SavedProfileStickerCaptured,
    val atomicCurrent: () -> Boolean,
    val sourceCurrent: () -> Boolean,
    private val callerDemand: SettingsSavedProfileStickerAvatarDemand = SettingsSavedProfileStickerAvatarDemand(),
    private val removed: (SavedProfileStickerActorGrant) -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val demand = AtomicBoolean(true)
    private val read = AtomicReference<ProfileStickerAvatarReadLease?>()
    /** Demand gate is distinct from sourceCurrent, which survives ordinary read closure. */
    fun readCurrent(): Boolean = demand.get() && callerDemand.current() && !closed.get() && atomicCurrent()
    fun revokeDemand() { demand.set(false) }
    fun bind(value: ProfileStickerAvatarReadLease) {
        if (!read.compareAndSet(null, value)) { value.close(); return }
        if (closed.get()) read.getAndSet(null)?.close()
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        demand.set(false)
        try { read.getAndSet(null)?.close() } finally { runCatching { removed(this) } }
    }
}

internal interface TdSavedProfileStickerSession {
    /** Non-authorizing atomic witness for an already-issued exact native photo ticket. */
    fun savedStickerTicketWitness(source: SettingsSavedProfileStickerNativeSource): (() -> Boolean)?
    fun captureSavedSticker(source: SettingsSavedProfileStickerNativeSource,
        onConflict: () -> Unit): SettingsResult<SavedProfileStickerCaptured>
}

/** Final atomics-only evidence, safe to inspect while the broker monitor is held. */
internal class SavedProfileStickerFileEvidence {
    private data class State(val id: Int, val unique: String, val size: Long, val conflict: Boolean = false)
    private val state = AtomicReference<State?>()

    fun current(): Boolean = state.get()?.conflict != true
    fun knownSize(): Long = state.get()?.size ?: 0L

    /** Metadata may omit a known size; a fresh native File may not retreat to unknown. */
    fun record(file: MediaFile, strict: Boolean): SettingsError? {
        while (true) {
            val previous = state.get()
            if (previous?.conflict == true) return SettingsError.CONFLICT
            if (previous != null && (file.id != previous.id || file.uniqueId.isBlank() ||
                    file.uniqueId != previous.unique || file.size < 0 || file.downloadedSize < 0 ||
                    previous.size > 0 && (file.size > 0 && file.size != previous.size || strict && file.size == 0L))) {
                if (state.compareAndSet(previous, previous.copy(conflict = true))) return SettingsError.CONFLICT
                continue
            }
            if (file.uniqueId.isBlank()) return SettingsError.NOT_READY
            if (file.id <= 0 || file.uniqueId.length > 1024 || file.size !in 0..MAX_BYTES ||
                file.downloadedSize !in 0..MAX_BYTES) return SettingsError.INVALID_VALUE
            val next = if (previous == null) State(file.id, file.uniqueId, file.size)
                else previous.copy(size = if (previous.size > 0) previous.size else file.size)
            if (state.compareAndSet(previous, next)) return null
        }
    }

    private companion object { const val MAX_BYTES = 24L * 1024 * 1024 }
}

/** Shared only by pages that describe the same native photo/source epoch. */
internal class SavedProfileStickerFacts {
    private val lock = Any()
    private var fingerprint: ProfileStickerAvatarMediaFingerprint? = null
    val evidence = SavedProfileStickerFileEvidence()
    fun current(): Boolean = evidence.current()

    /** Compare already learned facts before malformed/unsupported native values are rejected. */
    fun checkNative(sticker: TdApi.Sticker): SettingsError? = synchronized(lock) {
        val old = fingerprint ?: return@synchronized null
        val file = sticker.sticker ?: return@synchronized SettingsError.CONFLICT
        val unique = file.remote?.uniqueId.orEmpty()
        val format = when (sticker.format) {
            is TdApi.StickerFormatWebp -> MediaFormat.WEBP
            is TdApi.StickerFormatTgs -> MediaFormat.TGS
            is TdApi.StickerFormatWebm -> MediaFormat.WEBM
            else -> null
        }
        val repaint = (sticker.fullType as? TdApi.StickerFullTypeCustomEmoji)?.needsRepainting ?: false
        if (format != old.full.format || sticker.width != old.full.width || sticker.height != old.full.height ||
            repaint != old.repaint || file.id != old.full.file.id ||
            unique.isBlank() || unique != old.full.file.uniqueId ||
            file.size < 0 || evidence.knownSize() > 0 && file.size > 0 && evidence.knownSize() != file.size)
            SettingsError.CONFLICT else null
    }

    fun accept(value: ProfileStickerAvatarMediaFingerprint): SettingsResult<ProfileStickerAvatarOwnedMedia> = synchronized(lock) {
        val previous = fingerprint
        val file = value.full.file
        if (previous != null && (previous.repaint != value.repaint || !sameProfileStickerAvatarAsset(previous.full, value.full)))
            return@synchronized SettingsResult(error = SettingsError.CONFLICT)
        evidence.record(file, strict = false)?.let { return@synchronized SettingsResult(error = it) }
        val size = evidence.knownSize()
        val full = value.full.copy(file = file.copy(size = if (size > 0) size else file.size))
        fingerprint = value.copy(full = full)
        SettingsResult(ProfileStickerAvatarOwnedMedia(ProfileStickerAvatarMediaRole.FULL, full, value.repaint))
    }

    fun observe(file: MediaFile): SettingsResult<Unit> = evidence.record(file, strict = true)
        ?.let { SettingsResult(error = it) } ?: SettingsResult(Unit)
}
