package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Metadata authority plus the session's existing broker; never opens a native/local path. */
internal class OfficialTdProfileEmojiFiles(
    private val catalog: TdSettingsProfileColorsSession,
    private val downloads: TdDownloadBroker,
    private val send: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit,
    private val schedule: (Long, () -> Unit) -> Unit = DownloadBrokerScheduler::schedule,
    private val now: () -> Long = { System.nanoTime() / 1_000_000L },
) : TdProfileEmojiFilesSession {
    private val alive = AtomicBoolean(true)
    private val requests = ConcurrentHashMap<Long, Read>()
    private val admissions = Any()

    override fun read(
        token: ProfileEmojiResourceToken, expected: SettingsBackgroundEmoji, role: ProfileEmojiFileRole,
        maximumBytes: Int, active: () -> Boolean, result: (SettingsResult<ProfileEmojiEncoded>) -> Unit,
    ): ProfileEmojiFileLease {
        val request = Read(nextLease.incrementAndGet(), token, expected, role, maximumBytes, active, result)
        // Foreign predicates and consumers must never run under admissions or the per-read lock.
        val current = request.current()
        val invalid = token.generation <= 0 || token.accountUserId <= 0 || token.ownerId == 0L || token.revision < 0 ||
            maximumBytes !in 1..MAX_BYTES || !validIdentity(expected) || file(expected, role) == null
        val error = synchronized(admissions) {
            when {
                !current || !alive.get() || request.done.get() -> SettingsError.NOT_READY
                invalid -> SettingsError.INVALID_VALUE
                requests.size >= MAX_READERS -> SettingsError.RATE_LIMITED
                else -> { requests[request.id] = request; null }
            }
        }
        if (error != null) request.finish(SettingsResult(error = error))
        else request.attempt { request.start() }
        return request
    }

    override fun retire() {
        if (!alive.compareAndSet(true, false)) return
        requests.values.toList().forEach { it.close() }
    }

    private inner class Read(
        val id: Long, private val token: ProfileEmojiResourceToken, private val expected: SettingsBackgroundEmoji,
        private val role: ProfileEmojiFileRole, private val maximumBytes: Int, private val active: () -> Boolean,
        private val result: (SettingsResult<ProfileEmojiEncoded>) -> Unit,
    ) : ProfileEmojiFileLease {
        val done = AtomicBoolean()
        private val lock = Any()
        // A sentinel cannot authorize chat/message reads. Every resource lease is globally unique.
        private val owner = MediaDownloadOwner(ChatToken(0, 0, null), 0, MediaDownloadPurpose.PROFILE_EMOJI, id)
        private val started = now()
        private var content = ByteArrayOutputStream(minOf(maximumBytes.coerceAtLeast(0), CHUNK_BYTES))
        private var ready: MediaFile? = null

        fun current(): Boolean {
            if (!alive.get() || done.get()) return false
            val accepted = runCatching(active).getOrDefault(false)
            return accepted && alive.get() && !done.get()
        }

        // Broker invokes this while holding its own lock. It must only read captured atomics.
        private val brokerActive: () -> Boolean = { alive.get() && !done.get() }

        override fun close() = finish(SettingsResult(error = SettingsError.NOT_READY))

        fun finish(value: SettingsResult<ProfileEmojiEncoded>) {
            if (!done.compareAndSet(false, true)) return
            requests.remove(id, this)
            synchronized(lock) { content = ByteArrayOutputStream(0); ready = null }
            file(expected, role)?.let { selected -> runCatching { downloads.release(owner, selected.id) } }
            runCatching { result(value) }
        }

        fun attempt(action: () -> Unit) {
            try { action() } catch (_: Exception) { finish(SettingsResult(error = SettingsError.REQUEST_FAILED)) }
        }

        fun start() {
            watchdog()
            metadata { actual ->
                val selected = file(actual, role) ?: run { conflict(); return@metadata }
                if (selected.size < 0 || selected.size > maximumBytes) { conflict(); return@metadata }
                if (!current()) { close(); return@metadata }
                val once = AtomicBoolean()
                attempt {
                    downloads.boundedFull(owner, selected, maximumBytes, brokerActive) full@ { response ->
                        if (!once.compareAndSet(false, true) || done.get()) return@full
                        // Broker completions may hold its monitor: cross to the scheduler before
                        // consulting caller activity or publishing into a source/actor.
                        later(0) received@ {
                            if (!current()) { close(); return@received }
                            val downloaded = response.value
                            if (response.error != null || downloaded == null) {
                                finish(SettingsResult(error = error(response.error))); return@received
                            }
                            if (!sameFile(selected, downloaded) || !downloaded.downloaded ||
                                downloaded.size !in 1..maximumBytes.toLong()) { conflict(); return@received }
                            synchronized(lock) { if (!done.get()) ready = downloaded }
                            part()
                        }
                    }
                }
            }
        }

        /** Revalidate both self and exact catalog/native metadata before acquire and publication. */
        private fun metadata(completed: (SettingsBackgroundEmoji) -> Unit) {
            if (!current()) { close(); return }
            val loaded = AtomicBoolean()
            attempt {
                catalog.load(token.accountUserId, ::current) load@ { response ->
                    if (!loaded.compareAndSet(false, true) || done.get()) return@load
                    attempt loaded@ {
                        if (!current()) { close(); return@loaded }
                        val self = response.value
                        if (response.error != null || self == null) {
                            finish(SettingsResult(error = response.error ?: SettingsError.REQUEST_FAILED)); return@loaded
                        }
                        if (self.userId != token.accountUserId) { conflict(); return@loaded }
                        resolve(completed)
                    }
                }
            }
        }

        private fun resolve(completed: (SettingsBackgroundEmoji) -> Unit) {
            if (!current()) { close(); return }
            val resolved = AtomicBoolean()
            attempt {
                catalog.resolveEmojis(listOf(expected.customEmojiId), ::current) resolve@ { response ->
                    if (!resolved.compareAndSet(false, true) || done.get()) return@resolve
                    attempt received@ {
                        if (!current()) { close(); return@received }
                        if (response.error != null) { finish(SettingsResult(error = response.error)); return@received }
                        val actual = response.value?.singleOrNull()
                        if (actual == null || !sameIdentity(expected, actual)) { conflict(); return@received }
                        nativeMetadata(completed)
                    }
                }
            }
        }

        private fun nativeMetadata(completed: (SettingsBackgroundEmoji) -> Unit) {
            if (!current()) { close(); return }
            val resolved = AtomicBoolean()
            attempt {
                send(TdApi.GetCustomEmojiStickers(longArrayOf(expected.customEmojiId)), received@ { response ->
                    if (!resolved.compareAndSet(false, true) || done.get()) return@received
                    attempt accepted@ {
                        if (!current()) { close(); return@accepted }
                        val native = (response as? TdApi.Stickers)?.stickers?.singleOrNull()
                        val type = native?.fullType as? TdApi.StickerFullTypeCustomEmoji
                        val actual = if (type == null) null else mapSticker(native)?.let {
                            SettingsBackgroundEmoji(type.customEmojiId, it, type.needsRepainting)
                        }
                        if (actual == null || !sameIdentity(expected, actual)) { conflict(); return@accepted }
                        if (!current()) close() else completed(actual)
                    }
                }, {
                    if (resolved.compareAndSet(false, true)) finish(SettingsResult(error = SettingsError.REQUEST_FAILED))
                })
            }
        }

        private fun part() {
            if (!current()) { close(); return }
            val state = synchronized(lock) { ready?.let { it to content.size().toLong() } } ?: return
            val (downloaded, offset) = state
            if (offset == downloaded.size) {
                metadata { actual ->
                    val selected = file(actual, role)
                    if (selected == null || !sameFile(selected, downloaded) ||
                        selected.size > 0 && selected.size != offset || !current()) { conflict(); return@metadata }
                    val bytes = synchronized(lock) { if (done.get()) null else content.toByteArray() } ?: return@metadata
                    if (bytes.size.toLong() != downloaded.size || bytes.size > maximumBytes) { conflict(); return@metadata }
                    val sanitized = sanitize(actual)
                    finish(SettingsResult(ProfileEmojiEncoded(sanitized, role, downloaded.copy(localPath = null), bytes)))
                }
                return
            }
            if (offset < 0 || offset >= maximumBytes || offset > downloaded.size) { conflict(); return }
            val count = minOf(CHUNK_BYTES.toLong(), downloaded.size - offset, maximumBytes.toLong() - offset).toInt()
            val once = AtomicBoolean()
            attempt {
                downloads.readIfActive(owner, downloaded, offset, count, brokerActive) received@ { response ->
                    if (!once.compareAndSet(false, true) || done.get()) return@received
                    later(0) accepted@ {
                        if (!current()) { close(); return@accepted }
                        val bytes = response.value
                        if (response.error != null || bytes == null || bytes.isEmpty() || bytes.size > count) {
                            finish(SettingsResult(error = error(response.error))); return@accepted
                        }
                        synchronized(lock) {
                            if (!done.get() && content.size().toLong() == offset) content.write(bytes)
                        }
                        // No recursive fake stack or foreign predicate under the per-read lock.
                        later(0) { part() }
                    }
                }
            }
        }

        private fun conflict() = finish(SettingsResult(error = SettingsError.CONFLICT))

        private fun watchdog() {
            if (done.get()) return
            if (!current()) { close(); return }
            if (now() - started >= TIMEOUT_MILLIS) { finish(SettingsResult(error = SettingsError.REQUEST_FAILED)); return }
            later(100) { watchdog() }
        }

        private fun later(delay: Long, action: () -> Unit) = attempt {
            schedule(delay) { if (!done.get()) attempt(action) }
        }
    }

    private fun file(emoji: SettingsBackgroundEmoji, role: ProfileEmojiFileRole) =
        if (role == ProfileEmojiFileRole.FULL) emoji.sticker.media.file else emoji.sticker.media.thumbnail

    private fun validFile(file: MediaFile) = file.id > 0 && file.uniqueId.isNotBlank() && file.size >= 0
    private fun sameFile(a: MediaFile, b: MediaFile) = validFile(a) && validFile(b) && a.id == b.id && a.uniqueId == b.uniqueId
    private fun validIdentity(emoji: SettingsBackgroundEmoji): Boolean {
        val media = emoji.sticker.media
        return emoji.customEmojiId != 0L && emoji.sticker.id != 0L && media.width > 0 && media.height > 0 &&
            media.format in setOf(MediaFormat.WEBP, MediaFormat.TGS, MediaFormat.WEBM) && validFile(media.file) &&
            (media.thumbnail == null || validFile(media.thumbnail))
    }

    private fun sameIdentity(a: SettingsBackgroundEmoji, b: SettingsBackgroundEmoji): Boolean {
        val x = a.sticker.media; val y = b.sticker.media
        return validIdentity(a) && validIdentity(b) && a.customEmojiId == b.customEmojiId &&
            a.needsRepainting == b.needsRepainting && a.sticker.id == b.sticker.id && a.sticker.setId == b.sticker.setId &&
            x.width == y.width && x.height == y.height && x.format == y.format && x.thumbnailFormat == y.thumbnailFormat &&
            sameFile(x.file, y.file) && (if (x.thumbnail == null) y.thumbnail == null
                else y.thumbnail?.let { sameFile(x.thumbnail, it) } == true)
    }

    private fun sanitize(emoji: SettingsBackgroundEmoji): SettingsBackgroundEmoji {
        val media = emoji.sticker.media
        return emoji.copy(sticker = emoji.sticker.copy(media = media.copy(
            file = media.file.copy(localPath = null), thumbnail = media.thumbnail?.copy(localPath = null),
        )))
    }

    private fun error(value: ChatError?) = when (value) {
        ChatError.NOT_READY -> SettingsError.NOT_READY
        ChatError.FORBIDDEN -> SettingsError.FORBIDDEN
        ChatError.RATE_LIMITED -> SettingsError.RATE_LIMITED
        else -> SettingsError.REQUEST_FAILED
    }

    private companion object {
        const val MAX_BYTES = 24 * 1024 * 1024
        const val MAX_READERS = 8
        const val CHUNK_BYTES = 65_536
        const val TIMEOUT_MILLIS = 30_000L
        val nextLease = AtomicLong()
    }
}
