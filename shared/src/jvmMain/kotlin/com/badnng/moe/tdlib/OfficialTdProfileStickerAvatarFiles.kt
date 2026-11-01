package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import com.badnng.moe.settings.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal interface ProfileStickerAvatarReadLease { fun close() }

/** Internal authority adapter; the broker calls only atomicCurrent under its monitor. */
internal interface ProfileStickerAvatarFileSource {
    fun atomicCurrent(): Boolean
    fun resolve(active: () -> Boolean, terminal: () -> Unit,
        result: (SettingsResult<ProfileStickerAvatarOwnedMedia>) -> Unit): ProfileStickerAvatarMediaAdmission
    fun observe(file: MediaFile): SettingsResult<Unit> = SettingsResult(Unit)
    fun savedEvidence(): SavedProfileStickerFileEvidence? = null
    fun hasConflict(): Boolean = false
    fun conflict() {}
    fun close()
}

/**
 * Source authorization and bytes are internal only. The injected scheduler must enqueue work on
 * a serial, non-reentrant executor; broker callbacks may hold the broker monitor. The default
 * DownloadBrokerScheduler satisfies that contract. The caller must close on invisibility/focus loss.
 */
internal class OfficialTdProfileStickerAvatarFiles(
    private val authority: OfficialTdProfileStickerAvatar,
    private val downloads: TdDownloadBroker,
    private val budget: ProfileStickerAvatarEncodedBudget = sharedEncodedBudget,
    private val readers: ProfileStickerAvatarReaderBudget = sharedReaderBudget,
    private val schedule: (Long, () -> Unit) -> Unit = DownloadBrokerScheduler::schedule,
    private val now: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val alive = AtomicBoolean(true)
    private val requests = ConcurrentHashMap<Long, Read>()
    private val admissions = Any()

    fun thumbnail(picker: ProfileStickerAvatarPickerTicket, candidate: ProfileStickerAvatarCandidateTicket,
        active: () -> Boolean, result: (SettingsResult<ProfileStickerAvatarEncoded>) -> Unit,
    ): ProfileStickerAvatarReadLease = start(ProfileStickerAvatarMediaRole.THUMBNAIL, active, result) {
        authority.thumbnailMedia(picker, candidate)?.let(::builderSource)
    }

    fun full(picker: ProfileStickerAvatarPickerTicket, selection: ProfileStickerAvatarSelectionTicket,
        active: () -> Boolean, result: (SettingsResult<ProfileStickerAvatarEncoded>) -> Unit,
    ): ProfileStickerAvatarReadLease = start(ProfileStickerAvatarMediaRole.FULL, active, result) {
        authority.fullMedia(picker, selection)?.let(::builderSource)
    }

    /** Only an actor-captured saved-photo authority may supply this private source. */
    fun saved(acquire: () -> ProfileStickerAvatarFileSource?, active: () -> Boolean,
        result: (SettingsResult<ProfileStickerAvatarEncoded>) -> Unit,
    ): ProfileStickerAvatarReadLease = start(ProfileStickerAvatarMediaRole.FULL, active, result, acquire)

    private fun builderSource(grant: ProfileStickerAvatarMediaGrant) = object : ProfileStickerAvatarFileSource {
        override fun atomicCurrent() = grant.atomicCurrent()
        override fun resolve(active: () -> Boolean, terminal: () -> Unit,
            result: (SettingsResult<ProfileStickerAvatarOwnedMedia>) -> Unit) =
            authority.resolveMediaOwned(grant, active, terminal, result)
        override fun close() = authority.closeMedia(grant)
    }

    private fun start(role: ProfileStickerAvatarMediaRole, active: () -> Boolean,
        result: (SettingsResult<ProfileStickerAvatarEncoded>) -> Unit,
        acquire: () -> ProfileStickerAvatarFileSource?,
    ): ProfileStickerAvatarReadLease {
        val id = nextLease.incrementAndGet()
        val read = Read(id, role, active, result, acquire)
        val current = runCatching(active).getOrDefault(false)
        val registered = synchronized(admissions) {
            if (!alive.get() || !current || id <= 0 || requests.size >= MAX_REQUESTS) false
            else { requests[id] = read; true }
        }
        if (!registered) {
            read.fail(if (!current || !alive.get()) SettingsError.NOT_READY else SettingsError.RATE_LIMITED)
            return read
        }
        // Install before submit: immediate and already-released-slot delivery can reenter inline.
        val waiter = readers.waiter(role, read::admitted)
        if (!read.attachWaiter(waiter)) { waiter.cancel(); return read }
        when (readers.submit(waiter)) {
            ProfileStickerAvatarReaderBudget.Submission.ACCEPTED -> Unit
            ProfileStickerAvatarReaderBudget.Submission.CANCELLED -> read.close()
            ProfileStickerAvatarReaderBudget.Submission.FULL -> read.fail(SettingsError.RATE_LIMITED)
        }
        return read
    }

    /** Existing handed-out encoded handles remain charged until their final owner/worker closes. */
    fun retire() {
        val pending = synchronized(admissions) {
            if (!alive.compareAndSet(true, false)) emptyList() else requests.values.toList()
        }
        pending.forEach { it.close() }
    }

    private inner class Read(
        val id: Long,
        val role: ProfileStickerAvatarMediaRole,
        val callerActive: () -> Boolean,
        val result: (SettingsResult<ProfileStickerAvatarEncoded>) -> Unit,
        val acquire: () -> ProfileStickerAvatarFileSource?,
    ) : ProfileStickerAvatarReadLease {
        private val done = AtomicBoolean()
        private val lock = Any()
        // Waiting itself is unbounded in time but bounded in count; no watchdog until admission.
        private var started = 0L
        // A distinct sentinel namespace avoids colliding with existing PROFILE_EMOJI owners.
        private val owner = MediaDownloadOwner(ChatToken(Long.MIN_VALUE, Long.MIN_VALUE, null), 0,
            MediaDownloadPurpose.PROFILE_EMOJI, id)
        @Volatile private var grant: ProfileStickerAvatarFileSource? = null
        private var selected: ProfileStickerAvatarOwnedMedia? = null
        private var downloaded: MediaFile? = null
        private var buffer: ByteArray? = null
        private var reservation: ProfileStickerAvatarEncodedBudget.Reservation? = null
        private var offset = 0
        @Volatile private var readerToken: ProfileStickerAvatarReaderBudget.Token? = null
        private var waiter: ProfileStickerAvatarReaderBudget.Waiter? = null
        private val maximum = if (role == ProfileStickerAvatarMediaRole.THUMBNAIL) THUMB_BYTES else FULL_BYTES

        fun attach(value: ProfileStickerAvatarFileSource): Boolean = synchronized(lock) {
            if (done.get()) false else { grant = value; true }
        }

        fun attachWaiter(value: ProfileStickerAvatarReaderBudget.Waiter): Boolean = synchronized(lock) {
            if (done.get()) false else { waiter = value; true }
        }

        /** Called outside the reader-budget lock; token is transferred even after a close race. */
        fun admitted(slot: ProfileStickerAvatarReaderBudget.Token, deferred: Boolean) {
            val installed = synchronized(lock) {
                if (done.get() || !slot.retain()) false else { readerToken = slot; true }
            }
            if (!installed) { slot.release(); return }
            // The extra slot reference represents this producer stack / scheduled runnable.
            // Consumer close cannot free it until the runnable has really exited.
            val once = AtomicBoolean()
            val open: () -> Unit = open@ {
                if (!once.compareAndSet(false, true)) return@open
                try { beginAdmitted() }
                catch (_: Throwable) { fail(SettingsError.REQUEST_FAILED) }
                finally { slot.release() }
            }
            if (!deferred) open() else {
                try { schedule(0, open) }
                catch (_: Throwable) {
                    // Possibly queued despite the throw. Do not release its reference here.
                    fail(SettingsError.REQUEST_FAILED)
                }
            }
        }

        private fun beginAdmitted() {
            if (done.get() || !alive.get() || !runCatching(callerActive).getOrDefault(false) ||
                done.get() || !alive.get()) { close(); return }
            // The captured original ticket is resolved only now, without the budget lock.
            val value = try { acquire() } catch (_: Exception) { null }
            if (value == null) fail(SettingsError.CONFLICT)
            else if (!attach(value)) value.close()
            else { started = now(); start() }
        }

        private fun atomicCurrent(): Boolean = alive.get() && !done.get() && grant?.atomicCurrent() == true
        private fun current(): Boolean {
            val source = grant
            if (source?.hasConflict() == true) { runCatching { source.conflict() }; return false }
            if (!atomicCurrent()) return false
            val active = runCatching(callerActive).getOrDefault(false)
            return active && atomicCurrent()
        }

        // This exact predicate is called under the broker lock: atomics only, no foreign callback.
        private val brokerActive: () -> Boolean = { atomicCurrent() }

        fun start() {
            if (!current()) { close(); return }
            watchdog()
            metadata { first ->
                if (!current()) { close(); return@metadata }
                val file = first.asset.file
                if (first.role != role || file.size < 0 || file.size > maximum) { fail(SettingsError.INVALID_VALUE); return@metadata }
                synchronized(lock) { if (!done.get()) selected = first }
                val once = AtomicBoolean()
                try {
                    val completed: (ChatResult<MediaFile>) -> Unit = completed@ { response ->
                        if (!once.compareAndSet(false, true)) return@completed
                        afterBroker {
                            if (!current()) { close(); return@afterBroker }
                            val value = response.value
                            if (response.error != null || value == null) { fail(error(response.error)); return@afterBroker }
                            if (!sameFile(file, value) || !value.downloaded || value.size !in 1..maximum.toLong()) {
                                fail(SettingsError.CONFLICT); return@afterBroker
                            }
                            val observed = grant?.observe(value)
                            if (observed?.value != Unit || observed?.error != null) {
                                fail(observed?.error ?: SettingsError.CONFLICT); return@afterBroker
                            }
                            prepare(value.copy(localPath = null))
                        }
                    }
                    val evidence = grant?.savedEvidence()
                    if (evidence == null) downloads.boundedFull(owner, file, maximum, brokerActive, completed)
                    else downloads.boundedFullWithEvidence(owner, file, maximum, brokerActive, evidence, completed)
                } catch (_: Exception) { fail(SettingsError.REQUEST_FAILED) }
            }
        }

        /** The authority's result callback is outside its lock, including synchronous failures. */
        private fun metadata(next: (ProfileStickerAvatarOwnedMedia) -> Unit) {
            if (!current()) { close(); return }
            val value = grant ?: run { close(); return }
            val slot = readerToken
            if (slot == null || !slot.retain()) { close(); return }
            val once = AtomicBoolean()
            val terminal = AtomicBoolean()
            try {
                val admission = value.resolve(::current, terminal = {
                    if (terminal.compareAndSet(false, true)) laterAlways { slot.release() }
                }) received@ { response ->
                    if (!once.compareAndSet(false, true) || done.get()) return@received
                    // The authority revokes its grant before delivering a source conflict.
                    // Before bytes exist that is stale admission (NOT_READY); after the
                    // complete read it is a publication conflict. Neither path returns media.
                    if (response.error == SettingsError.CONFLICT) {
                        val completeRead = synchronized(lock) {
                            val data = buffer
                            data != null && offset > 0 && offset == data.size
                        }
                        if (completeRead && alive.get() && runCatching(callerActive).getOrDefault(false))
                            fail(SettingsError.CONFLICT)
                        else close()
                        return@received
                    }
                    if (!current()) { close(); return@received }
                    val media = response.value
                    if (response.error != null || media == null) fail(response.error ?: SettingsError.REQUEST_FAILED)
                    else next(media)
                }
                if (admission == ProfileStickerAvatarMediaAdmission.BUSY) {
                    // No consumer ticket, native tag, or dispatch was admitted on this path.
                    if (terminal.compareAndSet(false, true)) laterAlways { slot.release() }
                    fail(SettingsError.RATE_LIMITED)
                }
            } catch (_: Throwable) {
                // The helper may have dispatched native work. Its terminal alone releases this ref.
                fail(SettingsError.REQUEST_FAILED)
            }
        }

        private fun prepare(value: MediaFile) {
            if (!current()) { close(); return }
            val size = value.size.toInt()
            // One exact-size target plus one native callback chunk; no ByteArrayOutputStream/copy.
            val held = budget.reserve(size.toLong() + CHUNK_BYTES) ?: run { fail(SettingsError.RATE_LIMITED); return }
            val bytes = try { ByteArray(size) } catch (_: OutOfMemoryError) {
                held.release(); fail(SettingsError.RATE_LIMITED); return
            }
            val installed = synchronized(lock) {
                if (done.get()) false else {
                    downloaded = value; buffer = bytes; reservation = held; offset = 0; true
                }
            }
            if (!installed) { laterAlways { held.release() }; return }
            part()
        }

        private fun part() {
            if (!current()) { close(); return }
            val snapshot = synchronized(lock) {
                val file = downloaded
                val held = reservation
                if (done.get() || file == null || buffer == null || held == null) null
                else Triple(file, offset, held)
            } ?: return
            val (file, at, held) = snapshot
            if (at.toLong() == file.size) { verifyAndPublish(file); return }
            if (at < 0 || at.toLong() >= file.size || at >= maximum) { fail(SettingsError.CONFLICT); return }
            val count = minOf(CHUNK_BYTES.toLong(), file.size - at, maximum.toLong() - at).toInt()
            // This reference survives cancellation until the physical callback and its scheduled
            // consumer have exited. An unknown dispatch never returns budget early.
            if (!held.retain()) { close(); return }
            val slot = readerToken
            if (slot == null || !slot.retain()) { held.release(); close(); return }
            val once = AtomicBoolean()
            val responseOffered = AtomicBoolean()
            val responseFinished = AtomicBoolean()
            val physicallyFinished = AtomicBoolean()
            val released = AtomicBoolean()
            val advance = AtomicBoolean()
            fun releaseAfterBoth() {
                if (physicallyFinished.get() && (!responseOffered.get() || responseFinished.get()) &&
                    released.compareAndSet(false, true)) laterAlways {
                    // The serial scheduler cannot run this until the previous handler exits.
                    // Reusing one chunk reservation is safe only after both physical and handler
                    // ownership have ended and their retained references have been released.
                    held.release()
                    slot.release()
                    if (advance.get() && !done.get()) part()
                }
            }
            try {
                downloads.readOwnedIfActive(owner, file, at.toLong(), count, brokerActive, completed = completed@ { response ->
                    if (!once.compareAndSet(false, true)) return@completed
                    responseOffered.set(true)
                    afterBroker {
                        try {
                            if (!current()) { close(); return@afterBroker }
                            val bytes = response.value
                            if (response.error != null || bytes == null || bytes.isEmpty() || bytes.size > count) {
                                fail(error(response.error)); return@afterBroker
                            }
                            val copied = synchronized(lock) {
                                val target = buffer
                                if (done.get() || target == null || offset != at || at + bytes.size > target.size) false
                                else { bytes.copyInto(target, at); offset += bytes.size; true }
                            }
                            if (!copied) { close(); return@afterBroker }
                            advance.set(true)
                        } finally {
                            // Terminal and the queued response handler may arrive in either order.
                            responseFinished.set(true)
                            releaseAfterBoth()
                        }
                    }
                }, terminal = {
                    physicallyFinished.set(true)
                    releaseAfterBoth()
                })
            } catch (_: Exception) {
                // Dispatch may have happened. Keep both references until an actual terminal.
                fail(SettingsError.REQUEST_FAILED)
            }
        }

        private fun verifyAndPublish(file: MediaFile) {
            metadata { actual ->
                // Native metadata may return on any thread. Publish and the queued release must
                // share this serial, non-reentrant scheduler so the publisher stack exits first.
                later(0) {
                    if (!current()) { close(); return@later }
                    val initial = synchronized(lock) { selected }
                    if (initial == null || actual.role != role || !sameProfileStickerAvatarAsset(initial.asset, actual.asset) ||
                        !sameFile(file, actual.asset.file) || actual.asset.file.size > 0 && actual.asset.file.size != file.size) {
                        fail(SettingsError.CONFLICT); return@later
                    }
                    publish(actual)
                }
            }
        }

        private fun publish(media: ProfileStickerAvatarOwnedMedia) {
            if (!current()) { close(); return }
            val published = synchronized(lock) {
                val data = buffer
                val held = reservation
                val file = downloaded
                if (done.get() || grant?.atomicCurrent() != true || data == null || held == null || file == null ||
                    offset != data.size || data.size.toLong() != file.size || !held.retain()) null
                else {
                    done.set(true); buffer = null; reservation = null
                    ProfileStickerAvatarEncoded.take(media, data, held) to held
                }
            }
            if (published == null) { close(); return }
            val (encoded, publishingHold) = published
            // Preserve the actor/source result independently of the UI; stale UI receives no data.
            val deliver = alive.get() && grant?.atomicCurrent() == true && runCatching(callerActive).getOrDefault(false)
            try {
                cleanup()
                if (!deliver) { encoded.close(); notify(SettingsResult(error = SettingsError.NOT_READY)) }
                else notify(SettingsResult(encoded), encoded)
            } finally {
                // A synchronous callback may close its handle while this stack still holds bytes.
                laterAlways { publishingHold.release() }
            }
        }

        override fun close() = fail(SettingsError.NOT_READY)

        fun fail(error: SettingsError) {
            val held = synchronized(lock) {
                if (!done.compareAndSet(false, true)) return
                val value = reservation
                reservation = null; buffer = null
                value
            }
            if (error == SettingsError.CONFLICT) runCatching { grant?.conflict() }
            held?.let { laterAlways { it.release() } }
            cleanup()
            notify(SettingsResult(error = error))
        }

        private fun cleanup() {
            requests.remove(id, this)
            val held = synchronized(lock) {
                val state = Triple(waiter, selected?.asset?.file?.id, readerToken)
                waiter = null
                readerToken = null
                state
            }
            // All foreign operations and budget wakeups occur after the Read lock is released.
            held.first?.cancel()
            val value = grant
            if (value != null) value.close()
            if (held.second != null) runCatching { downloads.release(owner, held.second!!) }
            if (held.third != null) laterAlways { held.third!!.release() }
        }

        private fun notify(value: SettingsResult<ProfileStickerAvatarEncoded>, owned: ProfileStickerAvatarEncoded? = null) {
            // A callback that throws did not transfer this handle, even for AssertionError/Error.
            try { result(value) } catch (_: Throwable) { owned?.close() }
        }

        private fun watchdog() {
            if (done.get()) return
            if (!current()) { close(); return }
            if (now() - started >= TIMEOUT_MILLIS) { fail(SettingsError.REQUEST_FAILED); return }
            later(100) { watchdog() }
        }

        private fun later(delay: Long, action: () -> Unit) {
            try { schedule(delay) { if (!done.get()) action() } }
            catch (_: Exception) { fail(SettingsError.REQUEST_FAILED) }
        }

        private fun laterAlways(action: () -> Unit) {
            val once = AtomicBoolean()
            val guarded: () -> Unit = { if (once.compareAndSet(false, true)) action() }
            try { schedule(0, guarded) }
            catch (_: Exception) { /* Unknown enqueue: retain until the queued release actually runs. */ }
        }

        private fun afterBroker(action: () -> Unit) {
            val once = AtomicBoolean()
            try { schedule(0) { if (once.compareAndSet(false, true)) action() } }
            catch (_: Exception) {
                try { DownloadBrokerScheduler.schedule(0) {
                    // Whether the injected scheduler queued its callback is unknown: fail only
                    // the consumer and leave physical reservations for that callback or a drain.
                    fail(SettingsError.REQUEST_FAILED)
                } }
                catch (_: Exception) { /* keep reservations if no safe executor remains */ }
            }
        }
    }

    private fun sameFile(a: MediaFile, b: MediaFile): Boolean = a.id > 0 && a.id == b.id &&
        a.uniqueId.isNotBlank() && a.uniqueId == b.uniqueId && (a.size == 0L || b.size == 0L || a.size == b.size)

    private fun error(error: ChatError?) = when (error) {
        ChatError.NOT_READY -> SettingsError.NOT_READY
        ChatError.FORBIDDEN -> SettingsError.FORBIDDEN
        ChatError.RATE_LIMITED -> SettingsError.RATE_LIMITED
        else -> SettingsError.REQUEST_FAILED
    }

    private companion object {
        const val THUMB_BYTES = 512 * 1024
        const val FULL_BYTES = 24 * 1024 * 1024
        // Also bounds pre-submit races within one service; the shared budget bounds the queue.
        const val MAX_REQUESTS = 128 + 8
        const val CHUNK_BYTES = 65_536
        const val TIMEOUT_MILLIS = 30_000L
        val nextLease = AtomicLong()
        val sharedEncodedBudget = ProfileStickerAvatarEncodedBudget()
        val sharedReaderBudget = ProfileStickerAvatarReaderBudget()
    }
}
