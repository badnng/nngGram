package com.badnng.moe.tdlib

import com.badnng.moe.chat.MediaFile
import com.badnng.moe.settings.*
import java.util.concurrent.atomic.AtomicBoolean

/** The actor supplies an atomics-only parent/epoch gate and a separate foreign-safe owner check. */
internal class OfficialTdSavedProfileStickerFileSource(
    private val captured: SavedProfileStickerCaptured,
    private val actorAtomic: () -> Boolean,
    private val actorCurrent: () -> Boolean,
    private val resolver: OfficialTdSavedProfileStickerResolver,
    private val async: ProfileStickerAvatarMediaAsync = SharedProfileStickerAvatarMediaAsync,
) : ProfileStickerAvatarFileSource {
    private val alive = AtomicBoolean(true)
    override fun atomicCurrent(): Boolean = alive.get() && actorAtomic() && captured.atomicCurrent()
    private fun current() = atomicCurrent() && runCatching(actorCurrent).getOrDefault(false) &&
        runCatching(captured.sourceCurrent).getOrDefault(false) && atomicCurrent()

    override fun resolve(active: () -> Boolean, terminal: () -> Unit,
        result: (SettingsResult<ProfileStickerAvatarOwnedMedia>) -> Unit,
    ): ProfileStickerAvatarMediaAdmission {
        val permit = async.reserveConsumer() ?: return ProfileStickerAvatarMediaAdmission.BUSY
        val emitted = AtomicBoolean(); val nativeExited = AtomicBoolean(); val consumerExited = AtomicBoolean()
        val fileStarted = AtomicBoolean(); val fileExited = AtomicBoolean()
        val terminalOnce = AtomicBoolean()
        fun completeTerminal() {
            if (nativeExited.get() && (!fileStarted.get() || fileExited.get()) && consumerExited.get() &&
                terminalOnce.compareAndSet(false, true)) runCatching(terminal)
        }
        fun emit(value: SettingsResult<ProfileStickerAvatarOwnedMedia>) {
            if (!emitted.compareAndSet(false, true)) return
            try {
                permit.deliver({ result(value) }, { consumerExited.set(true); completeTerminal() })
            } catch (_: Throwable) {
                // An unknown dispatch may still be running; its own exit retains the reservation.
            }
        }
        resolver.resolveOwned(captured.sticker, { current() && runCatching(active).getOrDefault(false) },
            terminal = { nativeExited.set(true); completeTerminal() }, checkNative = captured.facts::checkNative) { response ->
            try {
                val fingerprint = response.value
                val mapped: SettingsResult<ProfileStickerAvatarOwnedMedia> = if (response.error != null || fingerprint == null)
                    SettingsResult(error = response.error ?: SettingsError.REQUEST_FAILED)
                else captured.facts.accept(fingerprint)
                val media = mapped.value
                if (mapped.error != null || media == null) {
                    if (mapped.error == SettingsError.CONFLICT) conflict()
                    emit(SettingsResult(error = mapped.error ?: SettingsError.REQUEST_FAILED))
                } else {
                    // Lock a real GetFile fact before the broker, and repeat this on final metadata.
                    fileStarted.set(true)
                    resolver.freshFileOwned(media.asset.file.id,
                        { current() && runCatching(active).getOrDefault(false) },
                        terminal = { fileExited.set(true); completeTerminal() }) { fresh ->
                        try {
                            val file = fresh.value
                            val observed = if (fresh.error != null || file == null)
                                SettingsResult<Unit>(error = fresh.error ?: SettingsError.REQUEST_FAILED)
                            else captured.facts.observe(file)
                            if (observed.error == SettingsError.CONFLICT) conflict()
                            if (observed.error != null || file == null) emit(SettingsResult(error = observed.error ?: SettingsError.REQUEST_FAILED))
                            else emit(SettingsResult(media.copy(asset = media.asset.copy(file = file))))
                        } catch (_: Throwable) { emit(SettingsResult(error = SettingsError.REQUEST_FAILED)) }
                    }
                }
            } catch (_: Throwable) { emit(SettingsResult(error = SettingsError.REQUEST_FAILED)) }
        }
        return ProfileStickerAvatarMediaAdmission.ACCEPTED
    }

    override fun observe(file: MediaFile): SettingsResult<Unit> {
        if (!current()) return SettingsResult(error = SettingsError.NOT_READY)
        val result = captured.facts.observe(file)
        if (result.error == SettingsError.CONFLICT) conflict()
        return result
    }

    override fun savedEvidence() = captured.facts.evidence
    override fun hasConflict() = !captured.facts.current()
    override fun conflict() { runCatching(captured.invalidate) }
    override fun close() { alive.set(false) }
}
