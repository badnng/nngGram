package com.badnng.moe.tdlib

import com.badnng.moe.chat.MediaFile
import com.badnng.moe.chat.MediaFormat
import org.drinkless.tdlib.TdApi
import java.util.concurrent.atomic.AtomicBoolean

internal enum class ProfileStickerAvatarMediaRole { THUMBNAIL, FULL }

/** A private source-bound handle; only the issuing helper's identity map authorizes it. */
internal class ProfileStickerAvatarMediaGrant internal constructor(
    val role: ProfileStickerAvatarMediaRole,
    private val epoch: AtomicBoolean,
    private val candidate: AtomicBoolean,
    private val selection: AtomicBoolean?,
) {
    private val live = AtomicBoolean(true)
    internal fun atomicCurrent(): Boolean = live.get() && epoch.get() && candidate.get() && (selection?.get() != false)
    internal fun revoke() { live.set(false) }
    override fun toString() = "ProfileStickerAvatarMediaGrant(<redacted>)"
}

internal data class ProfileStickerAvatarAsset(
    val file: MediaFile,
    val format: MediaFormat,
    val width: Int,
    val height: Int,
) {
    override fun toString() = "ProfileStickerAvatarAsset(<redacted>)"
}

/** Immutable snapshots made immediately from a native Sticker; no mutable TdApi object is retained. */
internal data class ProfileStickerAvatarMediaFingerprint(
    val full: ProfileStickerAvatarAsset,
    val thumbnail: ProfileStickerAvatarAsset?,
    val repaint: Boolean,
) {
    override fun toString() = "ProfileStickerAvatarMediaFingerprint(<redacted>)"
}

internal data class ProfileStickerAvatarOwnedMedia(
    val role: ProfileStickerAvatarMediaRole,
    val asset: ProfileStickerAvatarAsset,
    val repaint: Boolean,
) {
    override fun toString() = "ProfileStickerAvatarOwnedMedia(<redacted>)"
}

internal fun profileStickerAvatarFingerprint(sticker: TdApi.Sticker): ProfileStickerAvatarMediaFingerprint? {
    val fullFormat = when (sticker.format) {
        is TdApi.StickerFormatWebp -> MediaFormat.WEBP
        is TdApi.StickerFormatTgs -> MediaFormat.TGS
        is TdApi.StickerFormatWebm -> MediaFormat.WEBM
        else -> return null
    }
    if (sticker.width !in 1..2048 || sticker.height !in 1..2048) return null
    val fullFile = sticker.sticker?.let(::profileStickerAvatarFile) ?: return null
    val full = ProfileStickerAvatarAsset(fullFile, fullFormat, sticker.width, sticker.height)
    val thumb = sticker.thumbnail?.let { native ->
        val format = when (native.format) {
            is TdApi.ThumbnailFormatWebp -> MediaFormat.WEBP
            is TdApi.ThumbnailFormatJpeg -> MediaFormat.PHOTO
            else -> null
        }
        val file = native.file?.let(::profileStickerAvatarFile)
        if (format == null || file == null || native.width !in 1..2048 || native.height !in 1..2048) null
        else ProfileStickerAvatarAsset(file, format, native.width, native.height)
    }
    val repaint = (sticker.fullType as? TdApi.StickerFullTypeCustomEmoji)?.needsRepainting ?: false
    return ProfileStickerAvatarMediaFingerprint(full, thumb, repaint)
}

private fun profileStickerAvatarFile(file: TdApi.File): MediaFile? {
    if (file.id <= 0 || file.size < 0 || file.expectedSize < 0) return null
    val unique = file.remote?.uniqueId.orEmpty()
    if (unique.isBlank() || unique.length > 1024) return null
    return mapMediaFile(file).copy(localPath = null)
}

/** Unknown actual size may become known. Expected size and download progress are not identity. */
internal fun sameProfileStickerAvatarAsset(a: ProfileStickerAvatarAsset, b: ProfileStickerAvatarAsset): Boolean =
    a.format == b.format && a.width == b.width && a.height == b.height &&
        a.file.id == b.file.id && a.file.uniqueId == b.file.uniqueId &&
        (a.file.size == 0L || b.file.size == 0L || a.file.size == b.file.size)

internal fun resolveProfileStickerAvatarMedia(
    captured: ProfileStickerAvatarMediaFingerprint, actual: ProfileStickerAvatarMediaFingerprint,
    role: ProfileStickerAvatarMediaRole,
): ProfileStickerAvatarOwnedMedia? {
    if (captured.repaint != actual.repaint || !sameProfileStickerAvatarAsset(captured.full, actual.full)) return null
    val asset = when (role) {
        ProfileStickerAvatarMediaRole.FULL -> actual.full
        ProfileStickerAvatarMediaRole.THUMBNAIL -> {
            val old = captured.thumbnail ?: return null
            val current = actual.thumbnail ?: return null
            if (profileStickerAvatarSameFile(old.file, captured.full.file) ||
                profileStickerAvatarSameFile(current.file, actual.full.file) ||
                !sameProfileStickerAvatarAsset(old, current)) return null
            current
        }
    }
    return ProfileStickerAvatarOwnedMedia(role, asset, actual.repaint)
}

internal fun profileStickerAvatarSameFile(a: MediaFile, b: MediaFile): Boolean =
    a.id == b.id || a.uniqueId.isNotBlank() && a.uniqueId == b.uniqueId

/**
 * Process-shared logical consumers and physical chunks share eight non-resetting slots.
 * A queued ticket owns neither a slot nor a media grant; only this object chooses wakeups.
 */
internal class ProfileStickerAvatarReaderBudget(
    private val limit: Int = 8,
    private val maxWaiting: Int = 128,
) {
    init { require(limit in 1..8); require(maxWaiting >= 0) }

    private val lock = Any()
    private var held = 0
    private var waiting = 0
    private val full = java.util.ArrayDeque<Waiter>()
    private val thumbnails = java.util.ArrayDeque<Waiter>()

    internal enum class State { NEW, WAITING, DISPATCHED, CANCELLED, REJECTED }
    internal enum class Submission { ACCEPTED, CANCELLED, FULL }

    /** The callback owns a reserved token on invocation, even if cancellation raced dispatch. */
    internal inner class Waiter internal constructor(
        internal val role: ProfileStickerAvatarMediaRole,
        internal val ready: (Token, Boolean) -> Unit,
    ) {
        internal var state = State.NEW // guarded by the budget lock
        fun cancel() {
            synchronized(lock) {
                when (state) {
                    State.NEW -> state = State.CANCELLED
                    State.WAITING -> {
                        check(queue(role).remove(this))
                        waiting--
                        state = State.CANCELLED
                    }
                    State.DISPATCHED -> state = State.CANCELLED // delivery still owns its token
                    State.CANCELLED, State.REJECTED -> Unit
                }
            }
        }
    }

    private inner class Delivery(val waiter: Waiter, val token: Token, val deferred: Boolean) {
        fun deliver() = waiter.ready(token, deferred) // never under the budget lock
    }

    fun used(): Int = synchronized(lock) { held }
    fun waiting(): Int = synchronized(lock) { waiting }
    fun waiter(role: ProfileStickerAvatarMediaRole, ready: (Token, Boolean) -> Unit): Waiter =
        Waiter(role, ready)

    fun submit(waiter: Waiter): Submission {
        var delivery: Delivery? = null
        val result = synchronized(lock) {
            if (waiter.state != State.NEW) Submission.CANCELLED
            else if (held < limit) {
                check(waiting == 0)
                held++
                waiter.state = State.DISPATCHED
                delivery = Delivery(waiter, Token(), false)
                Submission.ACCEPTED
            } else if (waiting >= maxWaiting) {
                waiter.state = State.REJECTED
                Submission.FULL
            } else {
                waiter.state = State.WAITING
                queue(waiter.role).addLast(waiter)
                waiting++
                Submission.ACCEPTED
            }
        }
        delivery?.deliver()
        return result
    }

    private fun queue(role: ProfileStickerAvatarMediaRole) =
        if (role == ProfileStickerAvatarMediaRole.FULL) full else thumbnails

    /** Called only for a physical zero transition and reserves the replacement under the same lock. */
    private fun replacement(): Delivery? {
        val waiter = if (full.isNotEmpty()) full.removeFirst() else thumbnails.pollFirst() ?: return null
        check(waiter.state == State.WAITING)
        waiting--
        waiter.state = State.DISPATCHED
        held++
        return Delivery(waiter, Token(), true)
    }

    internal inner class Token {
        private var references = 1
        fun retain(): Boolean = synchronized(lock) {
            if (references <= 0) false else { references++; true }
        }
        fun release() {
            val delivery = synchronized(lock) {
                check(references > 0)
                references--
                if (references == 0) { held--; replacement() } else null
            }
            delivery?.deliver()
        }
    }
}

/** Shared across instances: the receiver allowance is permanently inside the same total cap. */
internal class ProfileStickerAvatarEncodedBudget(private val limit: Long = 64L * 1024 * 1024) {
    private val lock = Any()
    private var held = 0L
    init { require(limit >= NATIVE_RETURN_HEADROOM) }
    /** Dynamic reservations only; retained for diagnostics and pre-existing tests. */
    fun used(): Long = synchronized(lock) { held }
    fun headroom(): Long = NATIVE_RETURN_HEADROOM
    fun accounted(): Long = synchronized(lock) { NATIVE_RETURN_HEADROOM + held }
    fun dynamicCapacity(): Long = limit - NATIVE_RETURN_HEADROOM

    fun reserve(amount: Long): Reservation? = synchronized(lock) {
        val capacity = limit - NATIVE_RETURN_HEADROOM
        if (amount <= 0 || amount > capacity || held > capacity - amount) null else {
            held += amount
            Reservation(amount)
        }
    }

    internal inner class Reservation(private val amount: Long) {
        private var references = 1
        fun retain(): Boolean = synchronized(lock) {
            if (references <= 0) false else { references++; true }
        }
        fun release() = synchronized(lock) {
            check(references > 0)
            references--
            if (references == 0) held -= amount
        }
        fun reserveAdditional(bytes: Long): Reservation? = this@ProfileStickerAvatarEncodedBudget.reserve(bytes)
    }

    private companion object { const val NATIVE_RETURN_HEADROOM = 65_536L }
}

/** Reserve before a later worker makes a separate encoded-byte copy; close after it is discarded. */
internal class ProfileStickerAvatarCopyLease internal constructor(
    private val reservation: ProfileStickerAvatarEncodedBudget.Reservation,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    override fun close() { if (closed.compareAndSet(false, true)) reservation.release() }
}

/** Each worker retains before queueing and closes only after physically exiting. */
internal class ProfileStickerAvatarEncoded private constructor(
    private val shared: Shared,
    private val sourceCurrent: () -> Boolean = { true },
) : AutoCloseable {
    private val closed = AtomicBoolean()
    val role: ProfileStickerAvatarMediaRole get() = shared.media.role
    val format: MediaFormat get() = shared.media.asset.format
    val width: Int get() = shared.media.asset.width
    val height: Int get() = shared.media.asset.height
    val repaint: Boolean get() = shared.media.repaint
    val size: Int get() = shared.size

    private class Shared(
        val media: ProfileStickerAvatarOwnedMedia,
        bytes: ByteArray,
        private val reservation: ProfileStickerAvatarEncodedBudget.Reservation,
    ) {
        private val lock = Any()
        private var refs = 1
        private var bytes: ByteArray? = bytes
        val size = bytes.size
        fun retain(): Boolean = synchronized(lock) {
            if (refs <= 0) false else { refs++; true }
        }
        fun borrow(): ByteArray? = synchronized(lock) { bytes }
        fun reserveCopy(size: Long): ProfileStickerAvatarCopyLease? = synchronized(lock) {
            if (refs <= 0) null else reservation.reserveAdditional(size)?.let(::ProfileStickerAvatarCopyLease)
        }
        fun release() {
            val last = synchronized(lock) {
                check(refs > 0)
                refs--
                if (refs == 0) { bytes = null; true } else false
            }
            if (last) reservation.release()
        }
    }

    fun retain(): ProfileStickerAvatarEncoded? {
        if (closed.get() || !shared.retain()) return null
        if (closed.get()) { shared.release(); return null }
        return ProfileStickerAvatarEncoded(shared, sourceCurrent)
    }

    /** This read-only lease is independent of handle closure and cannot grant access to bytes. */
    internal fun sourceLease(): () -> Boolean = sourceCurrent

    /** Creates an independently owned handle with a one-way, strictly narrower source lease. */
    internal fun restrictSource(check: () -> Boolean): ProfileStickerAvatarEncoded? {
        if (closed.get() || !shared.retain()) return null
        if (closed.get()) { shared.release(); return null }
        val previous = sourceCurrent
        val live = AtomicBoolean(true)
        val narrowed: () -> Boolean = {
            if (!live.get()) false else {
                if (!runCatching(previous).getOrDefault(false) || !runCatching(check).getOrDefault(false)) live.set(false)
                live.get()
            }
        }
        return ProfileStickerAvatarEncoded(shared, narrowed)
    }

    /** Bytes are read-only and valid only inside this block; never retain or mutate the array. */
    fun borrow(block: (ByteArray) -> Unit): Boolean {
        val held = retain() ?: return false
        try { block(checkNotNull(shared.borrow())); return true } finally { held.close() }
    }

    /** Workers that copy encoded bytes must keep this charge until that copy is actually discarded. */
    fun reserveCopy(size: Long): ProfileStickerAvatarCopyLease? {
        if (closed.get()) return null
        val copy = shared.reserveCopy(size) ?: return null
        if (closed.get()) { copy.close(); return null }
        return copy
    }

    override fun close() { if (closed.compareAndSet(false, true)) shared.release() }
    override fun toString() = "ProfileStickerAvatarEncoded(<redacted>)"

    companion object {
        internal fun take(media: ProfileStickerAvatarOwnedMedia, bytes: ByteArray,
            reservation: ProfileStickerAvatarEncodedBudget.Reservation) =
            ProfileStickerAvatarEncoded(Shared(media, bytes, reservation))
    }
}
