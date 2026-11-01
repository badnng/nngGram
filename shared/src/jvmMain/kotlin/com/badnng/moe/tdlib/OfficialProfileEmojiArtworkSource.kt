package com.badnng.moe.tdlib

import androidx.compose.ui.graphics.ImageBitmap
import com.badnng.moe.chat.MediaFile
import com.badnng.moe.chat.MediaFormat
import com.badnng.moe.media.*
import com.badnng.moe.settings.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** Session-local actual custom emoji. Catalog/files are borrowed; only acquired leases are owned. */
internal class OfficialProfileEmojiArtworkSource(
    override val generation: Long,
    private val catalog: TdSettingsProfileColorsSession,
    private val files: TdProfileEmojiFilesSession,
    private val scope: CoroutineScope,
    private val currentAccountUserId: () -> Long,
    private val outputAllowed: () -> Boolean,
    private val admit: (work: () -> Unit, discard: () -> Unit) -> Unit,
    private val decoder: ProfileEmojiArtworkDecoder = SkiaProfileEmojiArtworkDecoder(),
) : ProfileEmojiArtworkProvider {
    private val lock = Any()
    private var closed = false
    private var epoch = 0L
    private val workers = ProfileEmojiArtworkWorkers()
    private val owners = linkedMapOf<OwnerKey, Owner>()
    private val subscriptions = linkedSetOf<Load>()
    private val pending = linkedSetOf<Load>()
    private val reads = linkedMapOf<AssetKey, ReadFlight>()
    private val readQueue = ArrayDeque<ReadFlight>()
    private var nativeReaders = 0
    private val decoding = linkedMapOf<FrameKey, DecodeFlight>()
    private val encoded = LinkedHashMap<AssetKey, EncodedEntry>(16, .75f, true)
    private val prepared = LinkedHashMap<FrameKey, FrameEntry>(16, .75f, true)
    // Includes evicted-but-leased frames and every running/queued preparation reservation.
    private val heldFrames = linkedSetOf<FrameEntry>()
    private var preparedPixels = 0L
    private var preparedCount = 0
    private var encodedBytes = 0L
    private data class Failure(val error: SettingsError, val owners: Map<OwnerKey, Long>)
    private val negative = LinkedHashMap<FrameKey, Failure>(16, .75f, true)
    private val timings = linkedMapOf<AssetKey, Pair<Long, Long>>()
    private val scopeCompletion = scope.coroutineContext[Job]?.invokeOnCompletion { close() }

    private data class OwnerKey(val account: Long, val domain: ProfileEmojiResourceDomain, val owner: Long)
    private class Owner(val token: ProfileEmojiArtworkToken, val expected: Identity?) {
        var metadata: SettingsBackgroundEmoji? = null
    }
    private data class FileIdentity(val id: Int, val unique: String, val size: Long, val expectedSize: Long)
    private data class Identity(val custom: Long, val sticker: Long, val set: Long, val format: MediaFormat,
        val width: Int, val height: Int, val repaint: Boolean, val file: FileIdentity,
        val thumbnail: FileIdentity?, val thumbnailFormat: MediaFormat?) {
        override fun toString() = "ProfileEmojiIdentity(<redacted>)"
    }
    private data class AssetKey(val account: Long, val identity: Identity, val role: ProfileEmojiFileRole) {
        override fun toString() = "ProfileEmojiAssetKey(<redacted>)"
    }
    private data class FrameKey(val asset: AssetKey, val actualFile: FileIdentity, val encodedSize: Int,
        val format: MediaFormat, val role: ProfileEmojiArtworkRole, val edge: Int, val time: Long) {
        override fun toString() = "ProfileEmojiFrameKey(<redacted>)"
    }
    private class EncodedEntry(val value: ProfileEmojiArtworkInput) {
        var readers = 0
        var cached = true
    }
    private class FrameEntry(val key: FrameKey, val value: PreparedProfileEmojiFrame) {
        var readers = 0
        var cached = true
    }
    private inner class ReadFlight(val asset: AssetKey, val actual: SettingsBackgroundEmoji) {
        val members = linkedSetOf<Load>()
        var lease: ProfileEmojiFileLease? = null
        var started = false
        var finished = false
        fun current(): Boolean = synchronized(lock) { if (closed || finished) emptyList() else members.toList() }
            .any(Load::current)
    }
    private inner class DecodeFlight(val key: FrameKey, val input: EncodedEntry, val pixels: Long) {
        val members = linkedSetOf<Load>()
        var finished = false
        fun current(): Boolean = synchronized(lock) { if (closed || finished) emptyList() else members.toList() }
            .any(Load::current)
    }

    private inner class Load(
        val token: ProfileEmojiArtworkToken, val expected: SettingsBackgroundEmoji?,
        val request: ProfileEmojiArtworkRequest, val active: () -> Boolean,
        val result: (SettingsResult<ProfileEmojiArtwork>) -> Unit, val version: Long,
    ) : ProfileEmojiArtworkLease {
        val ownerKey = OwnerKey(token.accountUserId, domain(request.role), token.ownerId)
        var retired = false
        var callbackDelivered = false
        var stage = 0
        var file: ProfileEmojiFileLease? = null
        var worker: AutoCloseable? = null
        var watchdog: Job? = null
        var frame: FrameEntry? = null
        var reserved = false

        fun currentLocked() = !closed && !retired && version == epoch && owners[ownerKey]?.token == token
        fun current(): Boolean {
            if (!synchronized(lock) { currentLocked() }) return false
            // Never execute caller gates/account access while holding the source monitor.
            val allowed = runCatching { active() && outputAllowed() && currentAccountUserId() == token.accountUserId &&
                scope.isActive }.getOrDefault(false)
            return synchronized(lock) { allowed && currentLocked() }
        }
        fun advance(from: Int): Boolean = synchronized(lock) {
            if (!currentLocked() || callbackDelivered || stage != from) false else { stage++; true }
        }
        fun attachFile(lease: ProfileEmojiFileLease) {
            val reject = synchronized(lock) {
                if (!currentLocked() || callbackDelivered || stage != 2) true else { file = lease; false }
            }
            if (reject) runCatching { lease.close() }
        }
        fun attachWorker(lease: AutoCloseable) {
            val reject = synchronized(lock) {
                if (!currentLocked() || callbackDelivered) true else { worker = lease; false }
            }
            if (reject) runCatching { lease.close() }
        }
        fun watch() {
            val job = scope.launch {
                val started = System.nanoTime()
                while (true) {
                    delay(100)
                    if (!current()) { close(); break }
                    if (System.nanoTime() - started > 30_000_000_000L) {
                        fail(SettingsError.REQUEST_FAILED); break
                    }
                    if (synchronized(lock) { callbackDelivered }) break
                }
            }
            val reject = synchronized(lock) {
                if (retired || callbackDelivered) true else { watchdog = job; false }
            }
            if (reject) job.cancel()
        }
        fun actor(action: () -> Unit) {
            val once = AtomicBoolean()
            try {
                admit({
                    if (once.compareAndSet(false, true)) {
                        if (!current()) close()
                        else try { action() } catch (_: Exception) { fail(SettingsError.REQUEST_FAILED) }
                    }
                }, { if (once.compareAndSet(false, true)) close() })
            } catch (_: Exception) { if (once.compareAndSet(false, true)) fail(SettingsError.NOT_READY) }
        }
        fun fail(error: SettingsError) = complete(null, error)
        fun complete(value: FrameEntry?, error: SettingsError? = null) {
            val allowed = current()
            val resources: Triple<ProfileEmojiFileLease?, AutoCloseable?, Job?>
            val response: SettingsResult<ProfileEmojiArtwork>
            synchronized(lock) {
                if (callbackDelivered || retired) return
                callbackDelivered = true
                pending.remove(this)
                if (allowed && currentLocked() && value != null) {
                    value.readers++
                    frame = value
                    response = SettingsResult(LeaseArtwork(this, value))
                } else {
                    retired = true; subscriptions.remove(this)
                    response = SettingsResult(error = if (allowed && currentLocked()) error ?: SettingsError.REQUEST_FAILED
                        else if (allowed) error ?: SettingsError.NOT_READY else SettingsError.NOT_READY)
                }
                resources = Triple(file, worker, watchdog)
                file = null; worker = null; watchdog = null
            }
            runCatching { resources.first?.close() }
            // Completion is on the actor, so cancelling its finished worker never interrupts the actor.
            runCatching { resources.second?.close() }; resources.third?.cancel()
            if (response.error != null) detachReads(this)
            try { result(response) } catch (_: Exception) { close() }
        }
        override fun close() {
            val resources: Triple<ProfileEmojiFileLease?, AutoCloseable?, Job?>
            val notify: Boolean
            var release: PreparedProfileEmojiFrame? = null
            synchronized(lock) {
                if (retired) return
                retired = true
                notify = !callbackDelivered; callbackDelivered = true
                subscriptions.remove(this); pending.remove(this)
                resources = Triple(file, worker, watchdog); file = null; worker = null; watchdog = null
                frame?.let { entry ->
                    entry.readers--
                    if (!entry.cached && entry.readers == 0) release = freeFrameLocked(entry)
                }
                frame = null
            }
            runCatching { resources.first?.close() }; runCatching { resources.second?.close() }
            resources.third?.cancel(); release?.close()
            detachReads(this)
            if (notify) runCatching { result(SettingsResult(error = SettingsError.NOT_READY)) }
        }
    }

    private inner class LeaseArtwork(val load: Load, val entry: FrameEntry) : ProfileEmojiArtwork {
        override val image: ImageBitmap? get() = if (load.current()) synchronized(lock) {
            if (load.currentLocked() && load.frame === entry) entry.value.image else null
        } else null
        override val isAlphaMask get() = entry.value.isAlphaMask
        override val animated get() = entry.value.animated
        override val durationMillis get() = entry.value.durationMillis
        override val frameDurationMillis get() = entry.value.frameDurationMillis
    }

    override fun acquire(token: ProfileEmojiArtworkToken, expected: SettingsBackgroundEmoji?,
        request: ProfileEmojiArtworkRequest, active: () -> Boolean,
        result: (SettingsResult<ProfileEmojiArtwork>) -> Unit): ProfileEmojiArtworkLease {
        val work = synchronized(lock) { Load(token, expected, request, active, result, epoch) }
        // Capture epoch first, then call all foreign admission gates without the monitor.
        val allowed = runCatching { active() && outputAllowed() && currentAccountUserId() == token.accountUserId &&
            scope.isActive }.getOrDefault(false)
        val identity = expected?.let(::identity)
        val invalid = generation <= 0 || token.generation != generation || token.accountUserId <= 0 || token.ownerId == 0L ||
            token.revision < 0 || token.customEmojiId == 0L || request.physicalSizePx !in 1..512 ||
            request.frameTimeMillis < 0 || expected != null && (identity == null || expected.customEmojiId != token.customEmojiId)
        val superseded = mutableListOf<Load>()
        val error = synchronized(lock) {
            val owner = owners[work.ownerKey]
            when {
                closed || work.version != epoch || !allowed -> SettingsError.NOT_READY
                invalid -> SettingsError.INVALID_VALUE
                pending.size >= MAX_PENDING -> SettingsError.RATE_LIMITED
                owner != null && (token.revision < owner.token.revision || token.revision == owner.token.revision &&
                    (token != owner.token || identity != owner.expected)) -> SettingsError.CONFLICT
                else -> {
                    if (owner == null || owner.token != token) {
                        if (owner == null && owners.size >= MAX_OWNERS) {
                            val unused = owners.keys.firstOrNull { key -> subscriptions.none { it.ownerKey == key } }
                            if (unused != null) owners.remove(unused)
                        }
                        if (owner == null && owners.size >= MAX_OWNERS) SettingsError.RATE_LIMITED
                        else {
                            superseded += subscriptions.filter { it.ownerKey == work.ownerKey && it.token != token }
                            owners[work.ownerKey] = Owner(token, identity)
                            subscriptions.add(work); pending.add(work); null
                        }
                    } else { subscriptions.add(work); pending.add(work); null }
                }
            }
        }
        superseded.forEach(Load::close)
        if (error != null) {
            // Invalid requests have no registered owner; emit the actual validation error once.
            synchronized(lock) { work.retired = true; work.callbackDelivered = true }
            runCatching { result(SettingsResult(error = error)) }
        } else {
            work.watch()
            work.actor { metadata(work) }
        }
        return work
    }

    private fun metadata(work: Load) {
        if (!work.advance(0)) return
        val cached = synchronized(lock) { owners[work.ownerKey]?.metadata }
        if (cached != null) { select(work, cached); return }
        val once = AtomicBoolean()
        catalog.load(work.token.accountUserId, work::current) loaded@ { response ->
            if (!once.compareAndSet(false, true)) return@loaded
            work.actor actor@ {
                if (response.error != null || response.value == null) {
                    work.fail(response.error ?: SettingsError.REQUEST_FAILED); return@actor
                }
                if (response.value.userId != work.token.accountUserId) { work.fail(SettingsError.CONFLICT); return@actor }
                val resolved = AtomicBoolean()
                catalog.resolveEmojis(listOf(work.token.customEmojiId), work::current) resolved@ { response ->
                    if (!resolved.compareAndSet(false, true)) return@resolved
                    work.actor accepted@ {
                        val actual = response.value?.singleOrNull()
                        if (response.error != null || actual == null) {
                            work.fail(response.error ?: SettingsError.REQUEST_FAILED); return@accepted
                        }
                        val fingerprint = identity(actual)
                        if (fingerprint == null || actual.customEmojiId != work.token.customEmojiId ||
                            work.expected?.let { identity(it) != fingerprint } == true) {
                            work.fail(SettingsError.CONFLICT); return@accepted
                        }
                        synchronized(lock) {
                            if (work.currentLocked()) owners[work.ownerKey]?.metadata = sanitize(actual)
                        }
                        select(work, sanitize(actual))
                    }
                }
            }
        }
    }

    private fun select(work: Load, actual: SettingsBackgroundEmoji) {
        if (!work.advance(1)) return
        val media = actual.sticker.media
        // OPTION is the actual native thumbnail if one exists. Other roles require full artwork.
        val role = if (work.request.role == ProfileEmojiArtworkRole.OPTION_THUMBNAIL && media.thumbnail != null &&
            media.thumbnailFormat in DECODABLE) ProfileEmojiFileRole.THUMBNAIL else ProfileEmojiFileRole.FULL
        val asset = AssetKey(work.token.accountUserId, checkNotNull(identity(actual)), role)
        val hit = synchronized(lock) { encoded[asset] }
        if (hit != null) { prepare(work, asset, hit); return }
        val admitted = synchronized(lock) {
            val group = reads[asset]
            if (group != null) { group.members.add(work); true }
            else if (readQueue.size + maxOf(0, decoding.size - 2) >= MAX_WAITING) false
            else {
                ReadFlight(asset, actual).also { it.members.add(work); reads[asset] = it; readQueue.addLast(it) }
                true
            }
        }
        if (!admitted) work.fail(SettingsError.RATE_LIMITED) else pumpReads()
    }

    private fun pumpReads() {
        while (true) {
            val group = synchronized(lock) {
                if (closed || nativeReaders >= MAX_NATIVE_READERS || readQueue.isEmpty()) null
                else readQueue.removeFirst().also { it.started = true; nativeReaders++ }
            } ?: return
            val first = synchronized(lock) { group.members.toList() }.firstOrNull(Load::current)
            if (first == null) { finishRead(group, null, SettingsError.NOT_READY); continue }
            startRead(group, first)
        }
    }

    private fun startRead(group: ReadFlight, first: Load) {
        val asset = group.asset
        val actual = group.actual
        val role = asset.role
        val once = AtomicBoolean()
        val lease = try { files.read(ProfileEmojiResourceToken(generation, first.token.accountUserId, first.ownerKey.domain,
            first.token.ownerId, first.token.revision), actual, role, MAX_ENCODED, group::current) read@ { response ->
            if (!once.compareAndSet(false, true)) return@read
            val admission = AtomicBoolean()
            try { admit(accepted@ {
                if (!admission.compareAndSet(false, true)) return@accepted
                if (!group.current()) { finishRead(group, null, SettingsError.NOT_READY); return@accepted }
                val value = response.value
                if (response.error != null || value == null) {
                    finishRead(group, null, response.error ?: SettingsError.REQUEST_FAILED); return@accepted
                }
                val selected = if (role == ProfileEmojiFileRole.FULL) actual.sticker.media.file else actual.sticker.media.thumbnail
                if (!sameMetadata(asset.identity, identity(value.emoji)) || value.role != role || selected == null ||
                    !sameFile(selected, value.file) || value.file.size != value.size.toLong() || value.file.localPath != null ||
                    value.emoji.sticker.media.file.localPath != null || value.emoji.sticker.media.thumbnail?.localPath != null) {
                    finishRead(group, null, SettingsError.CONFLICT); return@accepted
                }
                val released = mutableListOf<EncodedEntry>()
                val entry = synchronized(lock) {
                    if (closed || group.finished) null else encoded[asset] ?: run {
                        evictEncodedLocked(value.size.toLong(), released)
                        if (encodedBytes + value.size > MAX_ENCODED) null
                        else EncodedEntry(ProfileEmojiArtworkInput(value)).also { encoded[asset] = it; encodedBytes += value.size }
                    }
                }
                finishRead(group, entry, if (entry == null) SettingsError.RATE_LIMITED else null)
            }, { if (admission.compareAndSet(false, true)) finishRead(group, null, SettingsError.NOT_READY) })
            } catch (_: Exception) { if (admission.compareAndSet(false, true)) finishRead(group, null, SettingsError.NOT_READY) }
        } } catch (_: Exception) { finishRead(group, null, SettingsError.REQUEST_FAILED); return }
        val reject = synchronized(lock) { if (group.finished || closed) true else { group.lease = lease; false } }
        if (reject) runCatching { lease.close() }
    }

    private fun finishRead(group: ReadFlight, value: EncodedEntry?, error: SettingsError?) {
        val subscribers: List<Load>
        val lease: ProfileEmojiFileLease?
        synchronized(lock) {
            if (group.finished) return
            group.finished = true
            reads.remove(group.asset, group)
            if (group.started) nativeReaders-- else readQueue.remove(group)
            subscribers = group.members.toList(); group.members.clear()
            lease = group.lease; group.lease = null
        }
        runCatching { lease?.close() }
        subscribers.forEach { work ->
            if (!work.current()) work.close()
            else if (value != null) prepare(work, group.asset, value)
            else work.fail(error ?: SettingsError.REQUEST_FAILED)
        }
        // Lease cancellation can originate on the UI thread; native starts must re-enter actor.
        try { admit({ pumpReads() }, {}) } catch (_: Exception) { /* closing owner drains remaining leases */ }
    }

    private fun detachReads(work: Load) {
        val abandoned = synchronized(lock) {
            reads.values.filter { it.members.remove(work); it.members.isEmpty() }.toList()
        }
        abandoned.forEach { finishRead(it, null, SettingsError.NOT_READY) }
    }

    private fun prepare(work: Load, asset: AssetKey, input: EncodedEntry) {
        if (!work.advance(2)) return
        val format = if (asset.role == ProfileEmojiFileRole.FULL) asset.identity.format else asset.identity.thumbnailFormat
        if (format == null) { work.fail(SettingsError.UNSUPPORTED); return }
        // The actual encoded identity is retained in every prepared/negative key.
        val timing = synchronized(lock) { timings[asset] }
        val time = if (format in setOf(MediaFormat.PHOTO, MediaFormat.WEBP)) 0L else timing?.let { (duration, interval) ->
            work.request.frameTimeMillis % duration / interval * interval
        } ?: work.request.frameTimeMillis
        val key = FrameKey(asset, fileIdentity(input.value.file), input.value.size, format, work.request.role,
            work.request.physicalSizePx, time)
        val hit = synchronized(lock) { prepared[key] }
        if (hit != null) { work.complete(hit); return }
        val failure = synchronized(lock) { negative[key]?.takeIf { it.owners[work.ownerKey] == work.token.revision }?.error }
        if (failure != null) { work.fail(failure); return }
        val existing = synchronized(lock) { decoding[key]?.also { it.members.add(work) } }
        if (existing != null) return
        val dispose = mutableListOf<PreparedProfileEmojiFrame>()
        val pixels = work.request.physicalSizePx.toLong() * work.request.physicalSizePx
        val reserved = synchronized(lock) {
            evictPreparedLocked(pixels, dispose)
            if (!work.currentLocked() || preparedCount >= MAX_FRAMES || preparedPixels + pixels > MAX_PIXELS ||
                readQueue.size + maxOf(0, decoding.size - 1) > MAX_WAITING) false
            else {
                preparedCount++; preparedPixels += pixels
                input.readers++
                true
            }
        }
        dispose.forEach(PreparedProfileEmojiFrame::close)
        if (!reserved) { work.fail(SettingsError.RATE_LIMITED); return }
        val flight = DecodeFlight(key, input, pixels).also { it.members.add(work) }
        synchronized(lock) { decoding[key] = flight }
        val mask = work.request.role == ProfileEmojiArtworkRole.PROFILE_MASK ||
            work.request.role == ProfileEmojiArtworkRole.REPLY_MASK ||
            work.request.role == ProfileEmojiArtworkRole.OPTION_THUMBNAIL && asset.identity.repaint
        val worker = workers.submit {
            var value: PreparedProfileEmojiFrame? = null
            var error: SettingsError? = null
            try {
                if (!flight.current()) throw InterruptedException()
                value = decoder.decode(input.value, work.request, mask, flight::current)
                if (value.pixels != pixels || value.image == null || value.isAlphaMask != mask ||
                    value.durationMillis < 0 || value.frameDurationMillis < 0 ||
                    value.animated && (value.durationMillis <= 0 || value.frameDurationMillis <= 0)) {
                    value.close(); value = null; error = SettingsError.INVALID_VALUE
                }
            } catch (_: InterruptedException) { error = SettingsError.NOT_READY }
            catch (_: ProfileEmojiArtworkBusyException) { error = SettingsError.RATE_LIMITED }
            catch (_: Exception) { error = SettingsError.REQUEST_FAILED }
            finally { releaseEncodedReader(input) }
            val frame = value
            val failure = error
            // Release the running reservation even if actor admission is later discarded.
            val finished = AtomicBoolean()
            fun discard() {
                if (!finished.compareAndSet(false, true)) return
                finishDecode(flight, null, SettingsError.NOT_READY)
                frame?.close()
            }
            try {
                admit({
                    if (finished.compareAndSet(false, true)) {
                        if (!flight.current()) { frame?.close(); finishDecode(flight, null, SettingsError.NOT_READY) }
                        else finishDecode(flight, frame, failure)
                    }
                }, ::discard)
            } catch (_: Exception) { discard() }
        }
        if (worker == null) {
            releaseEncodedReader(input)
            finishDecode(flight, null, SettingsError.RATE_LIMITED)
        }
    }

    private fun finishDecode(flight: DecodeFlight, frame: PreparedProfileEmojiFrame?, error: SettingsError?) {
        var unused: PreparedProfileEmojiFrame? = null
        val subscribers: List<Load>
        val entry = synchronized(lock) {
            if (flight.finished) { frame?.close(); return }
            flight.finished = true; decoding.remove(flight.key, flight)
            subscribers = flight.members.toList(); flight.members.clear()
            if (frame == null || closed || subscribers.none { it.currentLocked() }) {
                preparedCount--; preparedPixels -= flight.pixels
                if (frame != null) unused = frame
                if (!closed && error != SettingsError.NOT_READY && error != SettingsError.RATE_LIMITED) {
                    negative[flight.key] = Failure(error ?: SettingsError.REQUEST_FAILED,
                        subscribers.associate { it.ownerKey to it.token.revision })
                    while (negative.size > MAX_NEGATIVE) negative.remove(negative.keys.first())
                }
                null
            } else prepared[flight.key]?.also {
                preparedCount--; preparedPixels -= flight.pixels; unused = frame
            } ?: FrameEntry(flight.key, frame).also {
                prepared[flight.key] = it; heldFrames.add(it)
                if (frame.animated) {
                    timings[flight.key.asset] = frame.durationMillis to frame.frameDurationMillis
                    while (timings.size > MAX_FRAMES) timings.remove(timings.keys.first())
                }
            }
        }
        unused?.close()
        subscribers.forEach {
            if (entry != null) it.complete(entry) else it.fail(error ?: SettingsError.NOT_READY)
        }
    }

    /** Leased evictions stay in heldFrames and retain their budget until the last close. */
    private fun evictPreparedLocked(required: Long, dispose: MutableList<PreparedProfileEmojiFrame>) {
        val iterator = prepared.entries.iterator()
        while (iterator.hasNext() && (preparedCount >= MAX_FRAMES || preparedPixels + required > MAX_PIXELS)) {
            val entry = iterator.next().value
            iterator.remove(); entry.cached = false
            if (entry.readers == 0) dispose += freeFrameLocked(entry)
        }
    }
    private fun freeFrameLocked(entry: FrameEntry): PreparedProfileEmojiFrame {
        check(heldFrames.remove(entry))
        preparedCount--; preparedPixels -= entry.value.pixels
        return entry.value
    }
    private fun evictEncodedLocked(required: Long, released: MutableList<EncodedEntry>, preserve: EncodedEntry? = null) {
        val iterator = encoded.entries.iterator()
        while (iterator.hasNext() && (encodedBytes + required > MAX_ENCODED || encoded.size >= MAX_FRAMES)) {
            val entry = iterator.next().value
            if (entry.readers != 0 || entry === preserve) continue
            iterator.remove(); entry.cached = false; encodedBytes -= entry.value.size; released += entry
        }
    }
    private fun releaseEncodedReader(entry: EncodedEntry) = synchronized(lock) {
        entry.readers--
        if (!entry.cached && entry.readers == 0) encodedBytes -= entry.value.size
    }

    override fun close() {
        val leases: List<Load>
        val free = mutableListOf<PreparedProfileEmojiFrame>()
        synchronized(lock) {
            if (closed) return
            closed = true; epoch++
            leases = subscriptions.toList()
            prepared.values.forEach { it.cached = false }
            prepared.clear()
            heldFrames.filter { it.readers == 0 }.toList().forEach { free += freeFrameLocked(it) }
            owners.clear(); negative.clear(); timings.clear()
            encoded.values.forEach { entry ->
                entry.cached = false
                if (entry.readers == 0) encodedBytes -= entry.value.size
            }
            encoded.clear()
        }
        // No borrowed-session retire(), observe(null), process-global decoder close or actor-map read.
        leases.forEach(Load::close); free.forEach(PreparedProfileEmojiFrame::close)
        (decoder as? SkiaProfileEmojiArtworkDecoder)?.close()
        workers.close(); scopeCompletion?.dispose()
    }

    private fun identity(emoji: SettingsBackgroundEmoji): Identity? {
        val sticker = emoji.sticker; val media = sticker.media
        if (emoji.customEmojiId == 0L || sticker.id == 0L || media.width !in 1..2048 || media.height !in 1..2048 ||
            media.format !in FULL_FORMATS || !validFile(media.file) || media.thumbnail?.let { !validFile(it) } == true ||
            media.thumbnail != null && media.thumbnailFormat !in DECODABLE) return null
        return Identity(emoji.customEmojiId, sticker.id, sticker.setId, media.format, media.width, media.height,
            emoji.needsRepainting, fileIdentity(media.file), media.thumbnail?.let(::fileIdentity), media.thumbnailFormat)
    }
    private fun validFile(file: MediaFile) = file.id > 0 && file.uniqueId.isNotBlank() &&
        file.uniqueId.length <= 1024 && file.size in 0..MAX_ENCODED.toLong() && file.expectedSize in 0..MAX_ENCODED.toLong()
    private fun fileIdentity(file: MediaFile) = FileIdentity(file.id, file.uniqueId, file.size, file.expectedSize)
    private fun sameFile(expected: MediaFile, actual: MediaFile) = validFile(actual) && expected.id == actual.id &&
        expected.uniqueId == actual.uniqueId && (expected.size == 0L || expected.size == actual.size)
    private fun sameMetadata(expected: Identity, actual: Identity?): Boolean {
        if (actual == null) return false
        fun match(a: FileIdentity, b: FileIdentity) = a.id == b.id && a.unique == b.unique &&
            (a.size == 0L || b.size == 0L || a.size == b.size)
        return expected.copy(file = actual.file, thumbnail = actual.thumbnail) == actual &&
            match(expected.file, actual.file) && (expected.thumbnail?.let { a -> actual.thumbnail?.let { match(a, it) } }
                ?: (actual.thumbnail == null)) == true
    }
    private fun sanitize(emoji: SettingsBackgroundEmoji): SettingsBackgroundEmoji {
        val media = emoji.sticker.media
        return emoji.copy(sticker = emoji.sticker.copy(media = media.copy(file = media.file.copy(localPath = null),
            thumbnail = media.thumbnail?.copy(localPath = null))))
    }
    private fun domain(role: ProfileEmojiArtworkRole) = when (role) {
        ProfileEmojiArtworkRole.OPTION_THUMBNAIL -> ProfileEmojiResourceDomain.OPTION
        ProfileEmojiArtworkRole.PROFILE_MASK -> ProfileEmojiResourceDomain.PROFILE
        ProfileEmojiArtworkRole.REPLY_MASK, ProfileEmojiArtworkRole.GIFT -> ProfileEmojiResourceDomain.REPLY
    }
    private companion object {
        const val MAX_PENDING = 128
        const val MAX_NATIVE_READERS = 8
        const val MAX_WAITING = 32
        const val MAX_OWNERS = 64
        const val MAX_FRAMES = 64
        const val MAX_NEGATIVE = 64
        const val MAX_ENCODED = 24 * 1024 * 1024
        const val MAX_PIXELS = 4_194_304L
        val FULL_FORMATS = setOf(MediaFormat.WEBP, MediaFormat.TGS, MediaFormat.WEBM)
        val DECODABLE = FULL_FORMATS + MediaFormat.PHOTO
    }
}
