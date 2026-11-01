package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicBoolean

/** Catalog metadata and explicit account mutations only. The caller retains staged upload files. */
internal class OfficialTdSavedNotificationSounds(
    private val send: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit,
) : TdSavedNotificationSoundsSession {
    private val lock = Any()
    private var alive = true
    private var revision = 0L
    private var knownIds: List<Long>? = null
    private var accountUserId: Long? = null
    @Volatile private var observer: (() -> Unit)? = null
    private class Mutation(val active: () -> Boolean, val rejected: (SettingsError) -> Unit, val body: (() -> Unit) -> Unit)
    private val queued = ArrayDeque<Mutation>()
    private var running = false

    override fun observe(observer: (() -> Unit)?) = synchronized(lock) { if (alive) this.observer = observer }
    override fun isCurrent(data: SavedNotificationSoundsData) = synchronized(lock) {
        alive && accountUserId == data.accountUserId && data.nativeRevision > 0 && data.nativeRevision == revision &&
            knownIds == data.sounds.map { it.id }
    }
    override fun retire() = synchronized(lock) {
        if (!alive) return@synchronized
        alive = false; observer = null; revision++; knownIds = null
        while (queued.isNotEmpty()) runCatching { queued.removeFirst().rejected(SettingsError.NOT_READY) }
    }
    fun accept(update: TdApi.Object) {
        val changed = synchronized(lock) {
            if (!alive) false else when (update) {
                is TdApi.UpdateSavedNotificationSounds -> {
                    val ids = update.notificationSoundIds
                    val valid = ids != null && ids.size <= MAX_SOUNDS && ids.all(::validId) && ids.distinct().size == ids.size
                    val latest = if (valid) ids.toList() else null
                    if (latest != null && latest == knownIds) false
                    else {
                        // The first native list establishes the baseline for a load already in progress.
                        // Invalidating that same load would silence its first real notification.
                        val hadBaseline = knownIds != null
                        knownIds = latest; revision++
                        hadBaseline
                    }
                }
                is TdApi.UpdateOption -> if (update.name in OPTIONS) { revision++; true } else false
                is TdApi.UpdateUser -> if (update.user?.id == accountUserId && update.user?.type !is TdApi.UserTypeRegular) {
                    revision++; true
                } else false
                else -> false
            }
        }
        if (changed) observer?.invoke()
    }

    override fun load(active: () -> Boolean, result: (SettingsResult<SavedNotificationSoundsData>) -> Unit) = load(null, active, result)

    private fun load(expectedAccount: Long?, active: () -> Boolean, result: (SettingsResult<SavedNotificationSoundsData>) -> Unit) {
        account(expectedAccount, active) account@ { user ->
            val id = user.value ?: run { result(SettingsResult(error = user.error)); return@account }
            val started = version()
            request(TdApi.GetSavedNotificationSounds(), active) catalog@ { response ->
                val native = (response.value as? TdApi.NotificationSounds)?.notificationSounds
                if (native == null || native.size > MAX_SOUNDS) {
                    result(SettingsResult(error = response.error ?: if (native != null) SettingsError.INVALID_VALUE else SettingsError.REQUEST_FAILED)); return@catalog
                }
                val sounds = native.map { mapSound(it) }
                if (sounds.any { it == null } || sounds.map { it!!.id }.distinct().size != sounds.size) {
                    result(SettingsResult(error = SettingsError.REQUEST_FAILED)); return@catalog
                }
                val values = sounds.filterNotNull()
                val ids = values.map { it.id }
                val snapshotVersion = synchronized(lock) {
                    if (revision != started && knownIds != ids) null
                    else { if (knownIds != ids) { knownIds = ids; revision++ }; revision }
                }
                if (snapshotVersion == null) { result(SettingsResult(error = SettingsError.CONFLICT)); return@catalog }
                val limits = arrayOfNulls<Long>(3)
                var limitError: SettingsError? = null
                fun option(index: Int) {
                    if (index == OPTIONS.size) {
                        synchronized(lock) {
                            if (knownIds != ids) { result(SettingsResult(error = SettingsError.CONFLICT)); return }
                            val changed = revision != snapshotVersion
                            val actual = if (changed) NotificationSoundLimits() else NotificationSoundLimits(limits[0], limits[1]?.toInt(), limits[2]?.toInt())
                            result(SettingsResult(SavedNotificationSoundsData(id, values, actual,
                                if (changed) SettingsError.CONFLICT else limitError, nativeRevision = revision)))
                        }
                        return
                    }
                    request(TdApi.GetOption(OPTIONS[index]), active) { response ->
                        val raw = (response.value as? TdApi.OptionValueInteger)?.value
                        val valid = raw != null && raw > 0 && (index == 0 || raw <= Int.MAX_VALUE)
                        if (valid) limits[index] = raw else if (limitError == null) limitError = response.error ?: SettingsError.UNSUPPORTED
                        if (!current(active)) { result(SettingsResult(error = SettingsError.NOT_READY)); return@request }
                        option(index + 1)
                    }
                }
                option(0)
            }
        }
    }

    override fun read(accountUserId: Long, soundId: Long, active: () -> Boolean,
        result: (SettingsResult<SavedNotificationSound>) -> Unit,
    ) {
        if (accountUserId <= 0 || !validId(soundId)) { result(SettingsResult(error = SettingsError.INVALID_VALUE)); return }
        account(accountUserId, active) account@ { account ->
            if (account.value == null) { result(SettingsResult(error = account.error)); return@account }
            val started = version()
            request(TdApi.GetSavedNotificationSound(soundId), active) { response ->
                val sound = mapSound(response.value as? TdApi.NotificationSound)?.takeIf { it.id == soundId }
                synchronized(lock) {
                    result(when {
                        sound == null -> SettingsResult(error = response.error ?: SettingsError.REQUEST_FAILED)
                        revision != started -> SettingsResult(error = SettingsError.CONFLICT)
                        else -> SettingsResult(sound)
                    })
                }
            }
        }
    }

    override fun add(expected: SavedNotificationSoundsData, prepared: PreparedNotificationSoundUpload, active: () -> Boolean,
        result: (SavedNotificationSoundMutationResult) -> Unit,
    ) = mutate(active, result) { finish ->
        load(expected.accountUserId, active) fresh@ { fresh ->
            val data = fresh.value ?: run { finish(SavedNotificationSoundMutationResult(error = fresh.error)); return@fresh }
            val limits = data.limits
            if (!limits.known || data.limitsError != null) {
                finish(SavedNotificationSoundMutationResult(error = data.limitsError ?: SettingsError.NOT_READY)); return@fresh
            }
            val started = version()
            val path = stagedFile(prepared)
            if (path == null || prepared.sizeBytes > limits.maximumBytes!! || prepared.durationMillis <= 0 ||
                prepared.durationMillis > limits.maximumDurationSeconds!!.toLong() * 1000 || data.sounds.size >= limits.maximumCount!!) {
                finish(SavedNotificationSoundMutationResult(error = SettingsError.INVALID_VALUE)); return@fresh
            }
            request(TdApi.AddSavedNotificationSound(TdApi.InputFileLocal(path.toString())), active, started,
                { it is TdApi.NotificationSound }) accepted@ { response ->
                val receipt = nativeReceipt(response.value)
                val accepted = receipt == NotificationSettingsWriteReceipt.ACCEPTED
                val sound = mapSound(response.value as? TdApi.NotificationSound)
                if (!accepted || sound == null) {
                    finish(SavedNotificationSoundMutationResult(accepted, error = response.error ?: SettingsError.REQUEST_FAILED, receipt = receipt)); return@accepted
                }
                load(expected.accountUserId, active) { actual ->
                    val matches = actual.value?.sounds?.any { sameIdentity(it, sound) } == true
                    finish(SavedNotificationSoundMutationResult(true, sound, actual.value,
                        actual.error ?: if (!matches) SettingsError.CONFLICT else null, receipt))
                }
            }
        }
    }

    override fun remove(accountUserId: Long, expected: SavedNotificationSound, active: () -> Boolean,
        result: (SavedNotificationSoundMutationResult) -> Unit,
    ) = mutate(active, result) { finish ->
        read(accountUserId, expected.id, active) fresh@ { fresh ->
            val actual = fresh.value ?: run { finish(SavedNotificationSoundMutationResult(error = fresh.error)); return@fresh }
            if (!sameIdentity(expected, actual)) { finish(SavedNotificationSoundMutationResult(error = SettingsError.CONFLICT)); return@fresh }
            val started = version()
            request(TdApi.RemoveSavedNotificationSound(expected.id), active, started, { it is TdApi.Ok }) accepted@ { response ->
                val receipt = nativeReceipt(response.value)
                if (response.value !is TdApi.Ok) { finish(SavedNotificationSoundMutationResult(error = response.error ?: SettingsError.REQUEST_FAILED, receipt = receipt)); return@accepted }
                load(accountUserId, active) { readback ->
                    finish(SavedNotificationSoundMutationResult(true, actual = readback.value, error = readback.error ?:
                        if (readback.value?.sounds?.any { it.id == expected.id } == true) SettingsError.CONFLICT else null, receipt = receipt))
                }
            }
        }
    }

    private fun mutate(active: () -> Boolean, result: (SavedNotificationSoundMutationResult) -> Unit,
        body: ((SavedNotificationSoundMutationResult) -> Unit) -> Unit,
    ) {
        val once = AtomicBoolean()
        fun complete(value: SavedNotificationSoundMutationResult) {
            if (value.receipt == NotificationSettingsWriteReceipt.INDETERMINATE) {
                if (!once.get()) result(value)
            } else if (once.compareAndSet(false, true)) result(value)
        }
        synchronized(lock) {
            if (!current(active)) { complete(SavedNotificationSoundMutationResult(error = SettingsError.NOT_READY)); return }
            if (queued.size >= 32) { complete(SavedNotificationSoundMutationResult(error = SettingsError.RATE_LIMITED)); return }
            queued.addLast(Mutation(active, { complete(SavedNotificationSoundMutationResult(error = it)) }) { released ->
                fun finish(value: SavedNotificationSoundMutationResult) {
                    try { complete(value) } finally {
                        if (value.receipt != NotificationSettingsWriteReceipt.INDETERMINATE) released()
                    }
                }
                try { body(::finish) } catch (_: Exception) { finish(SavedNotificationSoundMutationResult(error = SettingsError.REQUEST_FAILED)) }
            })
            advance()
        }
    }

    private fun advance() {
        if (running) return
        while (queued.isNotEmpty()) {
            val work = queued.removeFirst()
            if (!current(work.active)) { runCatching { work.rejected(SettingsError.NOT_READY) }; continue }
            running = true
            val released = AtomicBoolean()
            work.body {
                if (released.compareAndSet(false, true)) synchronized(lock) { running = false; advance() }
            }
            return
        }
    }

    private fun account(expected: Long?, active: () -> Boolean, result: (SettingsResult<Long>) -> Unit) {
        if (expected != null && expected <= 0) { result(SettingsResult(error = SettingsError.INVALID_VALUE)); return }
        val started = version()
        request(TdApi.GetMe(), active) { response ->
            val user = response.value as? TdApi.User
            synchronized(lock) {
                result(when {
                    user == null -> SettingsResult(error = response.error ?: SettingsError.REQUEST_FAILED)
                    revision != started -> SettingsResult(error = SettingsError.CONFLICT)
                    user.id <= 0 || user.type !is TdApi.UserTypeRegular -> SettingsResult(error = SettingsError.FORBIDDEN)
                    expected != null && user.id != expected -> SettingsResult(error = SettingsError.CONFLICT)
                else -> synchronized(lock) {
                    if (accountUserId != null && accountUserId != user.id) SettingsResult(error = SettingsError.CONFLICT)
                    else { accountUserId = user.id; SettingsResult(user.id) }
                }
                })
            }
        }
    }

    private fun stagedFile(prepared: PreparedNotificationSoundUpload): Path? = runCatching {
        val path = Path.of(prepared.path).normalize()
        if (!path.isAbsolute || prepared.sizeBytes <= 0) return@runCatching null
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        path.takeIf { attributes.isRegularFile && attributes.size() == prepared.sizeBytes &&
            (prepared.sha256.isEmpty() || com.badnng.moe.media.validatePreparedNotificationSoundUpload(prepared)) }
    }.getOrNull()

    private fun mapSound(native: TdApi.NotificationSound?): SavedNotificationSound? {
        val file = native?.sound ?: return null
        if (!validId(native.id) || native.duration < 0 || native.date < 0 || file.id <= 0 || file.size < 0 ||
            file.expectedSize < 0 || file.remote?.uniqueId.isNullOrBlank() || native.title == null || native.data == null) return null
        return SavedNotificationSound(native.id, native.duration, native.date, native.title, native.data, mapMediaFile(file))
    }
    private fun sameIdentity(a: SavedNotificationSound, b: SavedNotificationSound) = a.id == b.id && a.file.id == b.file.id &&
        a.file.uniqueId.isNotBlank() && a.file.uniqueId == b.file.uniqueId && a.durationSeconds == b.durationSeconds &&
        a.date == b.date && a.title == b.title && a.data == b.data
    private fun version() = synchronized(lock) { revision }
    private fun current(active: () -> Boolean) = synchronized(lock) { alive && runCatching(active).getOrDefault(false) }
    private fun request(command: TdApi.Function<*>, active: () -> Boolean, expectedRevision: Long? = null,
        preserveAccepted: (TdApi.Object) -> Boolean = { false }, result: (SettingsResult<TdApi.Object>) -> Unit,
    ) {
        val once = AtomicBoolean()
        fun finish(value: SettingsResult<TdApi.Object>) {
            if (SettingsSpecialMutationContext.receipt()?.outcome == SettingsSpecialWriteOutcome.INDETERMINATE) {
                if (!once.get()) result(value)
            } else if (once.compareAndSet(false, true)) result(value)
        }
        synchronized(lock) {
            if (!current(active)) { finish(SettingsResult(error = SettingsError.NOT_READY)); return }
            if (expectedRevision != null && expectedRevision != revision) { finish(SettingsResult(error = SettingsError.CONFLICT)); return }
            val finalWrite = command is TdApi.AddSavedNotificationSound || command is TdApi.RemoveSavedNotificationSound
            val dispatch: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit =
                if (finalWrite) { command, result, failed ->
                    SettingsSpecialMutationContext.write(command, send, result, failed)
                } else send
            try { dispatch(command, received@ { response ->
                // Add returns a newly created object; only this exact adapter proves its identity is valid.
                val receipt = if (command is TdApi.AddSavedNotificationSound &&
                    response is TdApi.NotificationSound && mapSound(response) != null)
                    SettingsSpecialWriteReceipt(SettingsSpecialWriteOutcome.ACCEPTED)
                    else SettingsSpecialMutationContext.receipt()
                SettingsSpecialMutationContext.withReceipt(receipt) {
                    if ((!finalWrite || receipt?.outcome != SettingsSpecialWriteOutcome.INDETERMINATE) &&
                        !once.compareAndSet(false, true)) return@withReceipt
                    result(when {
                        finalWrite && response is TdApi.Error -> SettingsResult(error = settingsError(response))
                        preserveAccepted(response) -> SettingsResult(response)
                        !current(active) -> SettingsResult(error = SettingsError.NOT_READY)
                        response is TdApi.Error -> SettingsResult(error = settingsError(response))
                        else -> SettingsResult(response)
                    })
                }
            }, {
                val rejected = if (finalWrite) SettingsSpecialMutationContext.receipt()?.error else null
                finish(SettingsResult(error = rejected ?: if (current(active)) SettingsError.REQUEST_FAILED else SettingsError.NOT_READY))
            }) }
            catch (_: Exception) { finish(SettingsResult(error = if (current(active)) SettingsError.REQUEST_FAILED else SettingsError.NOT_READY)) }
        }
    }

    private fun nativeReceipt(response: TdApi.Object?) = SettingsSpecialMutationContext.receipt()?.let {
        NotificationSettingsWriteReceipt.valueOf(it.outcome.name)
    } ?: when (response) {
        is TdApi.Ok, is TdApi.NotificationSound -> NotificationSettingsWriteReceipt.ACCEPTED
        is TdApi.Error -> NotificationSettingsWriteReceipt.REJECTED
        else -> NotificationSettingsWriteReceipt.NOT_DISPATCHED
    }

    private companion object {
        const val MAX_SOUNDS = 4096
        val OPTIONS = listOf("notification_sound_size_max", "notification_sound_duration_max", "notification_sound_count_max")
        fun validId(id: Long) = id != 0L && id != -1L
    }
}
