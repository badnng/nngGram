package com.badnng.moe.notifications

import com.badnng.moe.media.NotificationSoundDecoder
import com.badnng.moe.settings.SavedNotificationSound
import com.badnng.moe.settings.SettingsError
import com.badnng.moe.settings.SettingsResult
import com.badnng.moe.tdlib.NotificationSoundFileLease
import com.badnng.moe.tdlib.TdNotificationSoundFilesSession
import com.badnng.moe.tdlib.TdSavedNotificationSoundsSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One session generation owns playback preparation and explicit native ringtone cache warming. */
internal class OfficialNotificationSoundSource(
    private val catalog: TdSavedNotificationSoundsSession,
    private val files: TdNotificationSoundFilesSession,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val decode: suspend (ByteArray, Long, () -> Boolean) -> SettingsResult<NotificationSoundPcm> =
        NotificationSoundDecoder()::decode,
) : NotificationSoundSource {
    private val lock = Any()
    private var closed = false
    private var epoch = 0L
    private val pending = mutableSetOf<NotificationSoundLoad>()
    private val cache = LinkedHashMap<Key, NotificationSoundPcm>(8, 0.75f, true)
    private var cachedBytes = 0

    private data class Key(val account: Long, val epoch: Long, val sound: Long, val file: Int, val unique: String) {
        override fun toString() = "NotificationSoundCacheKey(<redacted>)"
    }

    private inner class Load(
        val account: Long, val sound: Long, val version: Long,
        val active: () -> Boolean, val result: (SettingsResult<PreparedNotificationAudio>) -> Unit,
    ) : NotificationSoundLoad {
        var done = false
        var inactive = false
        var stage = 0
        var file: NotificationSoundFileLease? = null
        var job: Job? = null

        /** Only source-owned fields are inspected under [lock]; caller gates can take other locks. */
        fun currentLocked(): Boolean {
            if (closed || version != epoch) inactive = true
            return !done && !inactive
        }

        fun current(): Boolean {
            if (!synchronized(lock) { currentLocked() }) return false
            val allowed = runCatching(active).getOrDefault(false)
            return synchronized(lock) {
                if (!allowed) inactive = true
                currentLocked()
            }
        }

        fun advance(expected: Int): Boolean = synchronized(lock) {
            if (done || stage != expected) false else { stage++; true }
        }

        fun attach(value: NotificationSoundFileLease) {
            val reject = synchronized(lock) { if (!currentLocked() || stage > 1) true else { file = value; false } }
            if (reject) runCatching { value.close() }
        }

        fun attach(value: Job) {
            val reject = synchronized(lock) { if (!currentLocked()) true else { job = value; false } }
            if (reject) value.cancel()
        }

        fun finish(value: SettingsResult<PreparedNotificationAudio>, cancel: Boolean = false) {
            // Cancellation must retire an owner even while a foreign gate is blocked elsewhere.
            val allowed = !cancel && current()
            val terminal: SettingsResult<PreparedNotificationAudio>
            val resources = synchronized(lock) {
                if (done) return
                if (!allowed) inactive = true
                terminal = if (currentLocked()) value else SettingsResult(error = SettingsError.NOT_READY)
                done = true
                pending.remove(this)
                (file to job).also { file = null; job = null }
            }
            runCatching { resources.first?.close() }
            resources.second?.cancel()
            result(terminal)
        }

        override fun close() = finish(SettingsResult(error = SettingsError.NOT_READY), cancel = true)
    }

    /** Native bytes only. This owner never creates a decoder job or retains audio in the PCM cache. */
    private inner class Warm(
        val version: Long, val active: () -> Boolean, val result: (SettingsResult<Unit>) -> Unit,
    ) : NotificationSoundLoad {
        var done = false
        var inactive = false
        var received = false
        var file: NotificationSoundFileLease? = null

        fun currentLocked(): Boolean {
            if (closed || version != epoch) inactive = true
            return !done && !inactive
        }

        fun current(): Boolean {
            if (!synchronized(lock) { currentLocked() }) return false
            val allowed = runCatching(active).getOrDefault(false)
            return synchronized(lock) {
                if (!allowed) inactive = true
                currentLocked()
            }
        }

        fun receive(): Boolean = synchronized(lock) {
            if (done || received) false else { received = true; true }
        }

        fun attach(value: NotificationSoundFileLease) {
            val reject = synchronized(lock) {
                if (!currentLocked() || received) true else { file = value; false }
            }
            if (reject) runCatching { value.close() }
        }

        fun finish(value: SettingsResult<Unit>, cancel: Boolean = false) {
            val allowed = !cancel && current()
            val terminal: SettingsResult<Unit>
            val resource = synchronized(lock) {
                if (done) return
                if (!allowed) inactive = true
                terminal = if (currentLocked()) value else SettingsResult(error = SettingsError.NOT_READY)
                done = true
                pending.remove(this)
                file.also { file = null }
            }
            runCatching { resource?.close() }
            result(terminal)
        }

        override fun close() = finish(SettingsResult(error = SettingsError.NOT_READY), cancel = true)
    }

    override fun warm(accountUserId: Long, sound: SavedNotificationSound, active: () -> Boolean,
        result: (SettingsResult<Unit>) -> Unit,
    ): NotificationSoundLoad {
        val work = synchronized(lock) { Warm(epoch, active, result) }
        val allowed = work.current()
        val rejected = synchronized(lock) {
            when {
                !allowed || !work.currentLocked() -> SettingsError.NOT_READY
                accountUserId <= 0 || !valid(sound) || sound.file.size !in 0L..MAX_ENCODED_BYTES.toLong() -> SettingsError.INVALID_VALUE
                pending.size >= MAX_PENDING -> SettingsError.RATE_LIMITED
                else -> { pending.add(work); null }
            }
        }
        if (rejected != null) work.finish(SettingsResult(error = rejected))
        else try {
            val lease = files.read(accountUserId, sound, MAX_ENCODED_BYTES, work::current) read@ { response ->
                if (!work.receive()) return@read
                if (!work.current()) { work.close(); return@read }
                val encoded = response.value
                if (response.error != null || encoded == null) {
                    work.finish(SettingsResult(error = response.error ?: SettingsError.REQUEST_FAILED)); return@read
                }
                if (!sameIdentity(sound, encoded.sound) || encoded.size !in 1..MAX_ENCODED_BYTES) {
                    work.finish(SettingsResult(error = SettingsError.CONFLICT)); return@read
                }
                // files.read already validates its account. Revalidate that original account and
                // immutable catalog identity after acquisition, without copying/decoding the bytes.
                val once = java.util.concurrent.atomic.AtomicBoolean()
                try {
                    catalog.read(accountUserId, sound.id, work::current) verified@ { actual ->
                        if (!once.compareAndSet(false, true)) return@verified
                        if (!work.current()) { work.close(); return@verified }
                        val metadata = actual.value
                        if (actual.error != null || metadata == null)
                            work.finish(SettingsResult(error = actual.error ?: SettingsError.REQUEST_FAILED))
                        else if (!sameIdentity(sound, metadata)) work.finish(SettingsResult(error = SettingsError.CONFLICT))
                        else work.finish(SettingsResult(Unit))
                    }
                } catch (_: Exception) { work.finish(SettingsResult(error = SettingsError.REQUEST_FAILED)) }
            }
            work.attach(lease)
        } catch (_: Exception) { work.finish(SettingsResult(error = SettingsError.REQUEST_FAILED)) }
        return work
    }

    override fun load(accountUserId: Long, soundId: Long, active: () -> Boolean,
        result: (SettingsResult<PreparedNotificationAudio>) -> Unit,
    ): NotificationSoundLoad {
        // Capture the epoch before entering foreign code, so invalidation cannot admit an old load
        // into a newer epoch when its gate eventually returns.
        val work = synchronized(lock) { Load(accountUserId, soundId, epoch, active, result) }
        val allowed = work.current()
        val rejected = synchronized(lock) {
            when {
                !allowed || !work.currentLocked() -> SettingsError.NOT_READY
                accountUserId <= 0 || soundId == 0L -> SettingsError.INVALID_VALUE
                pending.size >= MAX_PENDING -> SettingsError.RATE_LIMITED
                else -> { pending.add(work); null }
            }
        }
        if (rejected != null) work.finish(SettingsResult(error = rejected))
        else if (soundId == -1L) default(work, fallback = false)
        else try {
            catalog.load(work::current) loaded@ { response ->
                if (!work.advance(0)) return@loaded
                if (!work.current()) { work.close(); return@loaded }
                val data = response.value
                if (response.error != null || data == null) {
                    work.finish(SettingsResult(error = response.error ?: SettingsError.REQUEST_FAILED)); return@loaded
                }
                if (data.accountUserId != accountUserId) {
                    work.finish(SettingsResult(error = SettingsError.CONFLICT)); return@loaded
                }
                if (data.sounds.size > 4096 || data.sounds.any { !valid(it) } ||
                    data.sounds.map { it.id }.distinct().size != data.sounds.size) {
                    work.finish(SettingsResult(error = SettingsError.INVALID_VALUE)); return@loaded
                }
                val sound = data.sounds.singleOrNull { it.id == soundId }
                if (sound == null) { default(work, fallback = true); return@loaded }
                val key = key(work, sound)
                val cached = synchronized(lock) { if (work.currentLocked()) cache[key] else null }
                if (cached != null) validate(work, sound, cached)
                else acquire(work, sound)
            }
        } catch (_: Exception) { work.finish(SettingsResult(error = SettingsError.REQUEST_FAILED)) }
        return work
    }

    private fun acquire(work: Load, expected: SavedNotificationSound) {
        if (!work.current()) { work.close(); return }
        try {
            val lease = files.read(work.account, expected, MAX_ENCODED_BYTES, work::current) read@ { response ->
                if (!work.advance(1)) return@read
                if (!work.current()) { work.close(); return@read }
                val encoded = response.value
                if (response.error != null || encoded == null) {
                    work.finish(SettingsResult(error = response.error ?: SettingsError.REQUEST_FAILED)); return@read
                }
                if (!sameIdentity(expected, encoded.sound) || encoded.size !in 1..MAX_ENCODED_BYTES) {
                    work.finish(SettingsResult(error = SettingsError.CONFLICT)); return@read
                }
                val job = scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        val decoded = withContext(dispatcher) {
                            // Account upload limits can change after saving. Rendering has its own client cap.
                            decode(encoded.bytes(), NotificationSoundPcm.MAX_DURATION_MILLIS, work::current)
                        }
                        if (!work.current()) { work.close(); return@launch }
                        val pcm = decoded.value
                        if (decoded.error != null || pcm == null) work.finish(SettingsResult(error = decoded.error ?: SettingsError.REQUEST_FAILED))
                        else validate(work, expected, pcm)
                    } catch (_: CancellationException) { work.close() }
                    catch (_: Exception) { work.finish(SettingsResult(error = SettingsError.REQUEST_FAILED)) }
                }
                work.attach(job)
                // A scope canceled before launch still completes its request exactly once.
                job.invokeOnCompletion { if (it != null) work.close() }
                job.start()
            }
            work.attach(lease)
        } catch (_: Exception) { work.finish(SettingsResult(error = SettingsError.REQUEST_FAILED)) }
    }

    private fun validate(work: Load, expected: SavedNotificationSound, pcm: NotificationSoundPcm) {
        if (!work.current()) { work.close(); return }
        try {
            // GetMe and immutable metadata are rechecked even for decoded-cache hits.
            val once = java.util.concurrent.atomic.AtomicBoolean()
            catalog.read(work.account, work.sound, work::current) read@ { response ->
                if (!once.compareAndSet(false, true)) return@read
                if (!work.current()) { work.close(); return@read }
                val actual = response.value
                if (response.error != null || actual == null) {
                    work.finish(SettingsResult(error = response.error ?: SettingsError.REQUEST_FAILED)); return@read
                }
                if (!sameIdentity(expected, actual)) {
                    work.finish(SettingsResult(error = SettingsError.CONFLICT)); return@read
                }
                val allowed = work.current()
                synchronized(lock) {
                    if (allowed && work.currentLocked()) {
                        val key = key(work, actual)
                        cache.put(key, pcm)?.let { cachedBytes -= it.sizeBytes }
                        cachedBytes += pcm.sizeBytes
                        while (cache.size > MAX_CACHE_ENTRIES || cachedBytes > MAX_CACHE_BYTES) {
                            val oldest = cache.entries.iterator()
                            cachedBytes -= oldest.next().value.sizeBytes
                            oldest.remove()
                        }
                    }
                }
                work.finish(SettingsResult(PreparedNotificationAudio(work.sound, pcm)))
            }
        } catch (_: Exception) { work.finish(SettingsResult(error = SettingsError.REQUEST_FAILED)) }
    }

    private fun default(work: Load, fallback: Boolean) {
        if (!work.current()) { work.close(); return }
        try {
            work.finish(SettingsResult(PreparedNotificationAudio(work.sound,
                NotificationSoundPcm(deviceDefaultNotificationPcm(100)), defaultFallback = fallback)))
        } catch (_: Exception) { work.finish(SettingsResult(error = SettingsError.REQUEST_FAILED)) }
    }

    override fun invalidate() = retire(closing = false)
    override fun close() = retire(closing = true)

    private fun retire(closing: Boolean) {
        val old = synchronized(lock) {
            if (closed) return
            if (closing) closed = true
            epoch++
            cache.clear(); cachedBytes = 0
            pending.toList()
        }
        old.forEach { runCatching { it.close() } }
    }

    private fun key(work: Load, sound: SavedNotificationSound) = Key(work.account, work.version, sound.id, sound.file.id, sound.file.uniqueId)
    private fun valid(sound: SavedNotificationSound) = sound.id != 0L && sound.id != -1L && sound.file.id > 0 &&
        sound.file.uniqueId.isNotBlank() && sound.durationSeconds >= 0 && sound.date >= 0
    private fun sameIdentity(a: SavedNotificationSound, b: SavedNotificationSound) =
        valid(a) && valid(b) && a.id == b.id && a.file.id == b.file.id && a.file.uniqueId == b.file.uniqueId &&
            a.durationSeconds == b.durationSeconds && a.date == b.date && a.title == b.title && a.data == b.data

    private companion object {
        const val MAX_PENDING = 8
        const val MAX_CACHE_ENTRIES = 8
        const val MAX_CACHE_BYTES = 16 * 1024 * 1024
        const val MAX_ENCODED_BYTES = 16 * 1024 * 1024
    }
}
