package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import com.badnng.moe.settings.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Native catalog authority plus the session's sole broker. No path reads or independent downloads. */
internal class OfficialTdNotificationSoundFiles(
    private val catalog: TdSavedNotificationSoundsSession,
    private val downloads: TdDownloadBroker,
    private val schedule: (Long, () -> Unit) -> Unit = DownloadBrokerScheduler::schedule,
    private val now: () -> Long = { System.nanoTime() / 1_000_000L },
) : TdNotificationSoundFilesSession {
    private val alive = AtomicBoolean(true)
    private val requests = ConcurrentHashMap<Long, Read>()
    private val admissions = Any()

    override fun read(accountUserId: Long, expected: SavedNotificationSound, maximumBytes: Int, active: () -> Boolean,
        result: (SettingsResult<NotificationSoundEncoded>) -> Unit,
    ): NotificationSoundFileLease {
        val request = Read(nextLease.incrementAndGet(), accountUserId, expected, maximumBytes, active, result)
        synchronized(admissions) {
            if (!request.current() || accountUserId <= 0 || expected.id == 0L || expected.id == -1L || expected.file.id <= 0 ||
                expected.file.uniqueId.isBlank() || maximumBytes !in 1..MAX_BYTES) {
                request.finish(SettingsResult(error = if (!request.current()) SettingsError.NOT_READY else SettingsError.INVALID_VALUE))
                return request
            }
            if (requests.size >= MAX_READERS) { request.finish(SettingsResult(error = SettingsError.RATE_LIMITED)); return request }
            requests[request.id] = request
        }
        try { request.start() } catch (_: Exception) { request.finish(SettingsResult(error = SettingsError.REQUEST_FAILED)) }
        return request
    }

    override fun retire() {
        if (!alive.compareAndSet(true, false)) return
        requests.values.toList().forEach { runCatching { it.close() } }
    }

    private inner class Read(
        val id: Long, private val accountUserId: Long, private val expected: SavedNotificationSound,
        private val maximumBytes: Int, private val active: () -> Boolean,
        private val result: (SettingsResult<NotificationSoundEncoded>) -> Unit,
    ) : NotificationSoundFileLease {
        private val done = AtomicBoolean()
        private val owner = MediaDownloadOwner(ChatToken(0, 0, null), 0, MediaDownloadPurpose.SOUND, id)
        private val started = now()
        @Volatile private var bytes = ByteArrayOutputStream(minOf(maximumBytes.coerceAtLeast(0), 65_536))
        private var ready: MediaFile? = null
        fun current() = alive.get() && !done.get() && runCatching(active).getOrDefault(false)
        override fun close() = finish(SettingsResult(error = SettingsError.NOT_READY))

        fun finish(value: SettingsResult<NotificationSoundEncoded>) {
            if (!done.compareAndSet(false, true)) return
            requests.remove(id, this)
            bytes = ByteArrayOutputStream(0)
            ready = null
            runCatching { downloads.release(owner, expected.file.id) }
            result(value)
        }

        fun start() {
            watchdog()
            metadata first@ { response ->
                val sound = response.value ?: run { finish(SettingsResult(error = response.error)); return@first }
                if (!sameIdentity(expected, sound) || sound.file.size > maximumBytes) {
                    finish(SettingsResult(error = SettingsError.CONFLICT)); return@first
                }
                val once = AtomicBoolean()
                downloads.boundedFull(owner, sound.file, maximumBytes, ::current) full@ { value ->
                    if (!once.compareAndSet(false, true) || done.get()) return@full
                    if (!current()) { close(); return@full }
                    val file = value.value
                    if (value.error != null || file == null) { finish(SettingsResult(error = error(value.error))); return@full }
                    if (file.id != sound.file.id || file.uniqueId != sound.file.uniqueId || !file.downloaded ||
                        file.size <= 0 || file.size > maximumBytes) {
                        finish(SettingsResult(error = SettingsError.CONFLICT)); return@full
                    }
                    ready = file
                    part()
                }
            }
        }

        private fun metadata(completed: (SettingsResult<SavedNotificationSound>) -> Unit) {
            if (!current()) { close(); return }
            val once = AtomicBoolean()
            try { catalog.read(accountUserId, expected.id, ::current) received@ { response ->
                if (!once.compareAndSet(false, true) || done.get()) return@received
                if (!current()) close() else if (response.error != null || response.value == null)
                    finish(SettingsResult(error = response.error ?: SettingsError.REQUEST_FAILED))
                else completed(response)
            } } catch (_: Exception) { if (once.compareAndSet(false, true)) finish(SettingsResult(error = SettingsError.REQUEST_FAILED)) }
        }

        private fun part() {
            if (!current()) { close(); return }
            val file = ready ?: return
            val offset = bytes.size().toLong()
            if (offset == file.size) {
                metadata verified@ { response ->
                    val actual = response.value ?: return@verified
                    if (!sameIdentity(expected, actual) || actual.file.size > 0 && actual.file.size != offset || !current()) {
                        finish(SettingsResult(error = SettingsError.CONFLICT)); return@verified
                    }
                    finish(SettingsResult(NotificationSoundEncoded(actual.copy(file = actual.file.copy(localPath = null)), bytes.toByteArray())))
                }
                return
            }
            if (offset < 0 || offset >= maximumBytes || offset > file.size) {
                finish(SettingsResult(error = SettingsError.INVALID_VALUE)); return
            }
            val count = minOf(65_536L, file.size - offset, maximumBytes.toLong() - offset).toInt()
            val once = AtomicBoolean()
            downloads.readIfActive(owner, file, offset, count, ::current) received@ { response ->
                if (!once.compareAndSet(false, true) || done.get()) return@received
                if (!current()) { close(); return@received }
                val content = response.value
                if (response.error != null || content == null || content.isEmpty() || content.size > count) {
                    finish(SettingsResult(error = error(response.error))); return@received
                }
                bytes.write(content)
                // Bound synchronous fake recursion and give cancellation a boundary between chunks.
                later(0) { if (!done.get()) part() }
            }
        }

        private fun watchdog() {
            if (done.get()) return
            if (!current()) { close(); return }
            if (now() - started >= TIMEOUT_MILLIS) { finish(SettingsResult(error = SettingsError.REQUEST_FAILED)); return }
            later(100) { watchdog() }
        }

        private fun later(delay: Long, action: () -> Unit) {
            try { schedule(delay, action) } catch (_: Exception) { finish(SettingsResult(error = SettingsError.REQUEST_FAILED)) }
        }
    }

    private fun sameIdentity(a: SavedNotificationSound, b: SavedNotificationSound) = a.id == b.id &&
        a.durationSeconds == b.durationSeconds && a.date == b.date && a.title == b.title && a.data == b.data &&
        a.file.id == b.file.id && a.file.uniqueId.isNotBlank() && a.file.uniqueId == b.file.uniqueId
    private fun error(value: ChatError?) = when (value) {
        ChatError.NOT_READY -> SettingsError.NOT_READY
        ChatError.FORBIDDEN -> SettingsError.FORBIDDEN
        ChatError.RATE_LIMITED -> SettingsError.RATE_LIMITED
        else -> SettingsError.REQUEST_FAILED
    }
    private companion object {
        const val MAX_BYTES = 16 * 1024 * 1024
        const val MAX_READERS = 8
        const val TIMEOUT_MILLIS = 30_000L
        val nextLease = AtomicLong()
    }
}
