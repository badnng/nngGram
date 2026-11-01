package com.badnng.moe.tdlib

import com.badnng.moe.media.ProfileStickerAvatarEncodedReader
import com.badnng.moe.settings.*
import java.util.concurrent.atomic.AtomicBoolean

/** Internal only: it never exposes raw file identities as authority to callers. */
internal interface ProfileStickerAvatarMediaSession {
    val stickerAvatarMedia: ProfileStickerAvatarMediaFiles
}

internal interface ProfileStickerAvatarMediaFiles {
    fun thumbnail(picker: ProfileStickerAvatarPickerTicket, candidate: ProfileStickerAvatarCandidateTicket,
        active: () -> Boolean, result: (SettingsResult<ProfileStickerAvatarEncoded>) -> Unit): ProfileStickerAvatarReadLease
    fun full(picker: ProfileStickerAvatarPickerTicket, selection: ProfileStickerAvatarSelectionTicket,
        active: () -> Boolean, result: (SettingsResult<ProfileStickerAvatarEncoded>) -> Unit): ProfileStickerAvatarReadLease
    fun saved(grant: SavedProfileStickerActorGrant, active: () -> Boolean,
        result: (SettingsResult<ProfileStickerAvatarEncoded>) -> Unit): ProfileStickerAvatarReadLease {
        result(SettingsResult(error = SettingsError.UNSUPPORTED))
        return object : ProfileStickerAvatarReadLease { override fun close() {} }
    }
    fun retire()
}

internal class OfficialProfileStickerAvatarMediaFiles(private val delegate: OfficialTdProfileStickerAvatarFiles,
    private val savedResolver: OfficialTdSavedProfileStickerResolver? = null,
) : ProfileStickerAvatarMediaFiles {
    override fun thumbnail(picker: ProfileStickerAvatarPickerTicket, candidate: ProfileStickerAvatarCandidateTicket,
        active: () -> Boolean, result: (SettingsResult<ProfileStickerAvatarEncoded>) -> Unit) =
        delegate.thumbnail(picker, candidate, active, result)
    override fun full(picker: ProfileStickerAvatarPickerTicket, selection: ProfileStickerAvatarSelectionTicket,
        active: () -> Boolean, result: (SettingsResult<ProfileStickerAvatarEncoded>) -> Unit) =
        delegate.full(picker, selection, active, result)
    override fun saved(grant: SavedProfileStickerActorGrant, active: () -> Boolean,
        result: (SettingsResult<ProfileStickerAvatarEncoded>) -> Unit): ProfileStickerAvatarReadLease =
        delegate.saved({
            val resolver = savedResolver
            if (resolver == null || !grant.readCurrent() || !runCatching(grant.sourceCurrent).getOrDefault(false)) null
            else OfficialTdSavedProfileStickerFileSource(grant.captured, grant::readCurrent, grant.sourceCurrent, resolver)
        }, active, result)
    override fun retire() = delegate.retire()
}

internal data class ProfileStickerAvatarMediaRequest(
    val parent: SettingsProfileExtrasToken,
    val picker: ProfileStickerAvatarPickerTicket,
    val candidate: ProfileStickerAvatarCandidateTicket? = null,
    val selection: ProfileStickerAvatarSelectionTicket? = null,
)

/** Generation owner. Retirement closes request bridges only; UI owns and closes its provider/pixels. */
internal class ProfileStickerAvatarMediaEnvironment(
    val generation: Long,
    private val outputAllowed: () -> Boolean,
    private val savedRead: ((SettingsSavedProfileStickerAvatarRequest, () -> Boolean,
        SettingsSavedProfileStickerAvatarDemand,
        (SettingsResult<ProfileStickerAvatarEncoded>) -> Unit) -> SettingsSavedProfileStickerAvatarLease)? = null,
    private val admit: (ProfileStickerAvatarMediaRequest, ProfileStickerAvatarMediaBridge) -> Unit,
) : AutoCloseable {
    private val alive = AtomicBoolean(true)
    private val lock = Any()
    private val reads = linkedSetOf<ProfileStickerAvatarMediaBridge>()
    private val savedReads = linkedSetOf<SavedProfileStickerMediaBridge>()
    internal fun current() = alive.get() && runCatching(outputAllowed).getOrDefault(false) && alive.get()

    internal fun register(bridge: SavedProfileStickerMediaBridge): Boolean = synchronized(lock) {
        if (!alive.get() || reads.size + savedReads.size >= 64) false else savedReads.add(bridge)
    }
    internal fun remove(bridge: SavedProfileStickerMediaBridge) { synchronized(lock) { savedReads.remove(bridge) } }

    val reader: ProfileStickerAvatarEncodedReader = object : ProfileStickerAvatarEncodedReader {
        override fun thumbnail(parent: SettingsProfileExtrasToken, picker: ProfileStickerAvatarPickerTicket,
            candidate: ProfileStickerAvatarCandidateTicket, active: () -> Boolean,
            result: (SettingsResult<ProfileStickerAvatarEncoded>) -> Unit): AutoCloseable =
            request(ProfileStickerAvatarMediaRequest(parent, picker, candidate = candidate), active, result)

        override fun preview(parent: SettingsProfileExtrasToken, picker: ProfileStickerAvatarPickerTicket,
            selection: ProfileStickerAvatarSelectionTicket, active: () -> Boolean,
            result: (SettingsResult<ProfileStickerAvatarEncoded>) -> Unit): AutoCloseable =
            request(ProfileStickerAvatarMediaRequest(parent, picker, selection = selection), active, result)

        override fun savedPreview(request: SettingsSavedProfileStickerAvatarRequest, active: () -> Boolean,
            result: (SettingsResult<ProfileStickerAvatarEncoded>) -> Unit): SettingsSavedProfileStickerAvatarLease =
            savedPreviewOwned(request, active, SettingsSavedProfileStickerAvatarDemand(), result)

        override fun savedPreviewOwned(request: SettingsSavedProfileStickerAvatarRequest, active: () -> Boolean,
            demand: SettingsSavedProfileStickerAvatarDemand,
            result: (SettingsResult<ProfileStickerAvatarEncoded>) -> Unit): SettingsSavedProfileStickerAvatarLease {
            val source = savedRead
            if (source != null && current()) return source(request, { current() && active() }, demand, result)
            runCatching { result(SettingsResult(error = if (source == null) SettingsError.UNSUPPORTED else SettingsError.NOT_READY)) }
            return object : SettingsSavedProfileStickerAvatarLease {
                override fun requestCurrent() = false
                override fun close() {}
            }
        }
    }

    private fun request(request: ProfileStickerAvatarMediaRequest, active: () -> Boolean,
        result: (SettingsResult<ProfileStickerAvatarEncoded>) -> Unit): AutoCloseable {
        val bridge = ProfileStickerAvatarMediaBridge(::current, active, result) { finished ->
            synchronized(lock) { reads.remove(finished) }
        }
        val accepted = current() && request.parent.generation == generation && synchronized(lock) {
            if (!alive.get() || reads.size + savedReads.size >= 64) false else reads.add(bridge)
        }
        if (!accepted) { bridge.discard(); return bridge }
        try { admit(request, bridge) } catch (_: Throwable) { bridge.discard() }
        return bridge
    }

    override fun close() {
        val pending = synchronized(lock) {
            if (!alive.compareAndSet(true, false)) null else {
                val all = reads.toList() to savedReads.toList()
                reads.clear(); savedReads.clear(); all
            }
        }
        pending?.first?.forEach { it.discard() }
        pending?.second?.forEach { it.close() }
    }
}

/** One-way consumer lease; the source, not this bridge, owns all physical reservations. */
internal class ProfileStickerAvatarMediaBridge(
    private val environment: () -> Boolean,
    private val caller: () -> Boolean,
    private val result: (SettingsResult<ProfileStickerAvatarEncoded>) -> Unit,
    private val removed: (ProfileStickerAvatarMediaBridge) -> Unit,
) : AutoCloseable {
    private val alive = AtomicBoolean(true)
    private val valid = AtomicBoolean(true)
    private val lock = Any()
    private var owner: (() -> Boolean)? = null
    private var source: ProfileStickerAvatarReadLease? = null
    private var returned = false
    private var first: ProfileStickerAvatarEncoded? = null

    fun current(): Boolean {
        if (!alive.get() || !valid.get()) return false
        val captured = synchronized(lock) { owner }
        val permitted = runCatching { environment() && caller() && (captured?.invoke() ?: true) }.getOrDefault(false)
        if (!permitted) valid.set(false)
        return permitted && valid.get() && alive.get()
    }

    /** Called on the actor after captureMedia. Source may call returned synchronously before bind. */
    fun start(captured: () -> Boolean,
        submit: (() -> Boolean, (SettingsResult<ProfileStickerAvatarEncoded>) -> Unit) -> ProfileStickerAvatarReadLease) {
        synchronized(lock) { if (alive.get()) owner = captured }
        if (!current()) { discard(); return }
        val lease = try { submit(::current, ::returned) } catch (_: Throwable) {
            returned(SettingsResult(error = SettingsError.REQUEST_FAILED)); return
        }
        val close = synchronized(lock) { if (!alive.get()) true else { source = lease; false } }
        if (close) runCatching { lease.close() }
    }

    private fun returned(response: SettingsResult<ProfileStickerAvatarEncoded>) {
        val value = response.value
        val allowed = current()
        val outcome = synchronized(lock) {
            if (returned) 0 else {
                returned = true
                first = value
                if (allowed && alive.get() && response.error == null && value != null) 1 else 2
            }
        }
        when (outcome) {
            0 -> if (value !== synchronized(lock) { first }) value?.close()
            1 -> {
                // Long-lived source authority must not retain this bridge or its caller/Scope.
                val actorLease = synchronized(lock) { owner }
                val environmentLease = environment
                val narrowed = if (actorLease == null) null else value?.restrictSource {
                    runCatching(environmentLease).getOrDefault(false) && runCatching(actorLease).getOrDefault(false)
                }
                value?.close()
                if (narrowed == null || !current() || !runCatching(narrowed.sourceLease()).getOrDefault(false)) {
                    narrowed?.close()
                    if (alive.get()) runCatching { result(SettingsResult(error = SettingsError.NOT_READY)) }
                    close()
                } else try { result(SettingsResult(narrowed)) } catch (_: Throwable) { narrowed.close(); close() }
            }
            else -> {
                value?.close()
                // Only a still-live consumer receives an error; explicit close is silent.
                if (alive.get()) runCatching {
                    result(SettingsResult(error = if (!allowed) SettingsError.NOT_READY else response.error ?: SettingsError.REQUEST_FAILED))
                }
                close()
            }
        }
    }

    /** Actor-queue rejection reports once, unless the consumer already explicitly closed. */
    fun discard() {
        val shouldReport = synchronized(lock) {
            if (!alive.get() || returned) false else { returned = true; true }
        }
        if (shouldReport) runCatching { result(SettingsResult(error = SettingsError.NOT_READY)) }
        close()
    }

    override fun close() {
        alive.set(false)
        val old = synchronized(lock) { source.also { source = null } }
        removed(this)
        if (old != null) runCatching { old.close() }
    }
}
