package com.badnng.moe.tdlib

import com.badnng.moe.notifications.NotificationSoundLoad
import com.badnng.moe.notifications.NotificationSoundPreview
import com.badnng.moe.notifications.NotificationSoundVolumeStore
import com.badnng.moe.settings.*
import java.util.concurrent.atomic.AtomicBoolean

/** Actor-owned catalog page. Native writes and their retained files outlive the original page. */
internal class SavedNotificationSoundsController(
    private val generation: Long,
    private val session: TdSavedNotificationSoundsSession,
    private val preview: NotificationSoundPreview,
    private val enqueue: (() -> Unit) -> Unit,
    private val publish: (SavedNotificationSoundsSnapshot) -> Unit,
    private val isAllowed: () -> Boolean = { true },
    private val invalidateSoundSource: () -> Unit = {},
    private val warmSound: (Long, SavedNotificationSound, () -> Boolean, (SettingsResult<Unit>) -> Unit) -> NotificationSoundLoad =
        { _, _, _, result -> result(SettingsResult(Unit)); NotificationSoundLoad {} },
    private val soundVolumes: NotificationSoundVolumeStore? = null,
    private val soundVolumeChanged: () -> Unit = {},
    private val terminal: SettingsSpecialMutationBoundary = SettingsSpecialMutationBoundary(enqueue),
) {
    private var snapshot = SavedNotificationSoundsSnapshot(token = SavedNotificationSoundsToken(generation))
    private var nextOwner = 0L
    private var revision = 0L
    private var acceptedRevision = 0L
    private var changes = 0L
    private var accountUserId: Long? = null
    private var owner = AtomicBoolean(false)
    private var page: () -> Boolean = { false }
    @Volatile private var retired = false
    private var readLease: AtomicBoolean? = null
    private var choice: Choice? = null
    private var playback: Playback? = null
    private var mutation: Mutation? = null
    private var pendingReadback: PendingReadback? = null
    internal var lastMutationReceipt: Pair<NotificationSettingsWriteReceipt, SettingsError?>? = null
        private set
    private var warming: Warming? = null

    private class Choice(val result: (SettingsResult<Long>) -> Unit) {
        val active = AtomicBoolean(true)
        val terminal = AtomicBoolean(false)
        fun finish(value: SettingsResult<Long>) {
            if (terminal.compareAndSet(false, true)) {
                active.set(false)
                runCatching { result(value) }
            }
        }
    }
    private class Playback {
        val active = AtomicBoolean(true)
        var load: NotificationSoundLoad? = null
        fun stop() { active.set(false); load?.let { runCatching { it.close() } }; load = null }
    }
    private class Mutation(val token: SavedNotificationSoundsToken, val active: () -> Boolean)
    private class WarmItem {
        val active = AtomicBoolean(true)
        val terminal = AtomicBoolean()
        var load: NotificationSoundLoad? = null
        fun stop() { active.set(false); load?.let { runCatching { it.close() } }; load = null }
    }
    private class Warming(val token: SavedNotificationSoundsToken, val data: SavedNotificationSoundsData,
        val pageGate: () -> Boolean,
    ) {
        val active = AtomicBoolean(true)
        val running = mutableSetOf<WarmItem>()
        var cursor = 0
        var pumping = false
    }
    private class PendingReadback(val added: SavedNotificationSound? = null, val removed: Long? = null) {
        fun matches(data: SavedNotificationSoundsData) = if (removed != null) data.sounds.none { it.id == removed }
            else added == null || data.sounds.any { sameIdentity(it, added) }
    }

    init { session.observe { enqueue { changed() } } }

    fun accepts(token: SavedNotificationSoundsToken) = !retired && token == snapshot.token &&
        snapshot.opened && owner.get() && allowed(page)

    fun open(active: () -> Boolean) {
        if (!allowed(active) || (owner.get() && allowed(page) && snapshot.opened)) return
        retirePage()
        owner = AtomicBoolean(true); page = active
        snapshot = SavedNotificationSoundsSnapshot(token = SavedNotificationSoundsToken(generation, ++nextOwner),
            opened = true, saving = mutation != null, readbackPending = pendingReadback != null)
        emit()
        if (mutation == null) read()
    }

    fun close(token: SavedNotificationSoundsToken) {
        if (retired || token != snapshot.token) return
        retirePage()
        snapshot = SavedNotificationSoundsSnapshot(token = SavedNotificationSoundsToken(generation, ++nextOwner))
        emit()
    }

    fun deactivate() {
        if (retired) return
        retirePage(); retired = true
        session.observe(null)
        runCatching { preview.close() }
        pendingReadback = null
        snapshot = SavedNotificationSoundsSnapshot(token = SavedNotificationSoundsToken(generation, ++nextOwner))
        emit()
    }

    fun refresh(token: SavedNotificationSoundsToken) {
        if (!accepts(token) || mutation != null || snapshot.loading || snapshot.choosing) return
        read()
    }

    fun choose(token: SavedNotificationSoundsToken, expectedRevision: Long, soundId: Long,
        result: (SettingsResult<Long>) -> Unit,
    ) {
        val pending = Choice(result)
        val failure = admission(token, expectedRevision)
        if (failure != null) { pending.finish(SettingsResult(error = failure)); return }
        val data = snapshot.data!!
        if (soundId == 0L || soundId == -1L) { pending.finish(SettingsResult(soundId)); return }
        val expected = data.sounds.singleOrNull { it.id == soundId }
        if (expected == null) { pending.finish(SettingsResult(error = SettingsError.CONFLICT)); return }
        choice = pending
        val pageActive = currentPage(); val observed = changes
        val active = { pending.active.get() && pageActive() }
        val once = AtomicBoolean()
        snapshot = snapshot.copy(choosing = true, error = null); emit()
        fun finish(value: SettingsResult<SavedNotificationSound>) {
            if (!once.compareAndSet(false, true)) return
            // Closing/deactivating the controller explicitly terminates this callback even if actor work is discarded.
            if (!active()) pending.finish(SettingsResult(error = SettingsError.NOT_READY))
            enqueue {
                if (choice !== pending) return@enqueue
                choice = null
                val current = accepts(token) && active()
                val error = when {
                    !current -> SettingsError.NOT_READY
                    observed != changes -> SettingsError.CONFLICT
                    value.error != null -> value.error
                    value.value == null || !sameIdentity(expected, value.value) -> SettingsError.CONFLICT
                    else -> null
                }
                if (current) { snapshot = snapshot.copy(choosing = false, error = error); emit() }
                pending.finish(if (error == null) SettingsResult(soundId) else SettingsResult(error = error))
                if (!current) retireInactivePage(token)
            }
        }
        try { session.read(data.accountUserId, soundId, active, ::finish) }
        catch (_: Exception) { finish(SettingsResult(error = SettingsError.REQUEST_FAILED)) }
    }

    fun readVolume(token: SavedNotificationSoundsToken, target: NotificationSoundVolumeTarget,
        result: (SettingsResult<NotificationSoundVolumeReading>) -> Unit,
    ) {
        val store = soundVolumes
        val error = volumeAdmission(token, saving = false) ?: when {
            !validVolumeTarget(target) -> SettingsError.INVALID_VALUE
            store == null -> SettingsError.NOT_READY
            else -> null
        }
        val outcome: SettingsResult<NotificationSoundVolumeReading> = if (error != null) SettingsResult(error = error) else {
            val account = snapshot.data!!.accountUserId
            val read: SettingsResult<NotificationSoundVolumeReading> = try { store!!.read(account, target) }
                catch (_: Exception) { SettingsResult(error = SettingsError.REQUEST_FAILED) }
            validatedVolume(read, account, target)
        }
        val delivered: SettingsResult<NotificationSoundVolumeReading> =
            if (accepts(token)) outcome else SettingsResult(error = SettingsError.NOT_READY)
        // Local store calls are synchronous. Callback code runs after the store has returned and released its lock.
        runCatching { result(delivered) }
    }

    fun saveVolume(token: SavedNotificationSoundsToken, expected: NotificationSoundVolumeReading, percent: Int?,
        result: (SettingsResult<NotificationSoundVolumeReading>) -> Unit,
    ) {
        val store = soundVolumes
        val error = volumeAdmission(token, saving = true) ?: when {
            !validVolumeTarget(expected.target) || !validVolumePercent(percent) ||
                !validVolumePercent(expected.overridePercent) -> SettingsError.INVALID_VALUE
            expected.accountUserId != snapshot.data!!.accountUserId -> SettingsError.CONFLICT
            store == null -> SettingsError.NOT_READY
            else -> null
        }
        val outcome: SettingsResult<NotificationSoundVolumeReading> = if (error != null) SettingsResult(error = error) else {
            val saved: SettingsResult<NotificationSoundVolumeReading> = try { store!!.save(expected, percent) }
                catch (_: Exception) { SettingsResult(error = SettingsError.REQUEST_FAILED) }
            val validated = validatedVolume(saved, expected.accountUserId, expected.target)
            if (validated.error == null && validated.value?.overridePercent != percent)
                SettingsResult(error = SettingsError.CONFLICT) else validated
        }
        if (outcome.error == null && outcome.value != null) runCatching(soundVolumeChanged)
        val delivered: SettingsResult<NotificationSoundVolumeReading> =
            if (accepts(token)) outcome else SettingsResult(error = SettingsError.NOT_READY)
        // This is a local CAS preference save; do not increment catalog or native acceptance revisions.
        runCatching { result(delivered) }
    }

    private fun volumeAdmission(token: SavedNotificationSoundsToken, saving: Boolean): SettingsError? {
        val data = snapshot.data
        return when {
            !accepts(token) || data == null || snapshot.loading -> SettingsError.NOT_READY
            saving && (snapshot.saving || mutation != null || snapshot.choosing || snapshot.readbackPending) -> SettingsError.NOT_READY
            snapshot.refreshRequired || !currentData(data, changes) -> SettingsError.CONFLICT
            else -> null
        }
    }

    private fun validatedVolume(value: SettingsResult<NotificationSoundVolumeReading>, account: Long,
        target: NotificationSoundVolumeTarget,
    ): SettingsResult<NotificationSoundVolumeReading> {
        val reading = value.value
        return when {
            value.error != null -> SettingsResult(error = value.error)
            reading == null || !validVolumePercent(reading.overridePercent) -> SettingsResult(error = SettingsError.REQUEST_FAILED)
            reading.accountUserId != account || reading.target != target -> SettingsResult(error = SettingsError.CONFLICT)
            else -> value
        }
    }

    private fun validVolumeTarget(target: NotificationSoundVolumeTarget) = when (target) {
        is NotificationSoundVolumeTarget.Scope -> true
        is NotificationSoundVolumeTarget.Chat -> target.chatId != 0L
    }
    private fun validVolumePercent(percent: Int?) = percent == null || percent in 1..100

    fun preview(token: SavedNotificationSoundsToken, soundId: Long, volumePercent: Int) {
        if (!accepts(token)) return
        val data = snapshot.data
        val error = when {
            data == null || snapshot.loading || mutation != null || snapshot.refreshRequired -> SettingsError.NOT_READY
            volumePercent !in 0..100 -> SettingsError.INVALID_VALUE
            soundId != 0L && soundId != -1L && data.sounds.none { it.id == soundId } -> SettingsError.CONFLICT
            else -> null
        }
        stopPlayback()
        if (error != null) { snapshot = snapshot.copy(previewError = error); emit(); return }
        if (soundId == 0L) { snapshot = snapshot.copy(previewSoundId = 0L, previewError = null); emit(); return }
        val operation = Playback(); playback = operation
        val pageActive = currentPage()
        val active = { operation.active.get() && pageActive() }
        val once = AtomicBoolean()
        snapshot = snapshot.copy(previewSoundId = soundId, previewLoading = true, previewError = null,
            previewDefaultFallback = false); emit()
        fun finish(value: SettingsResult<Boolean>) {
            if (!once.compareAndSet(false, true)) return
            enqueue {
                if (playback !== operation) return@enqueue
                if (!active() || !accepts(token)) {
                    operation.stop(); playback = null; retireInactivePage(token); return@enqueue
                }
                val failed = value.error ?: if (value.value == null) SettingsError.REQUEST_FAILED else null
                if (failed != null) { operation.stop(); playback = null }
                snapshot = snapshot.copy(previewLoading = false, previewError = failed,
                    previewSoundId = if (failed == null) soundId else null,
                    previewDefaultFallback = failed == null && value.value == true)
                emit()
            }
        }
        try {
            val load = preview.play(data!!.accountUserId, soundId, volumePercent, active, ::finish)
            if (operation.active.get()) operation.load = load else runCatching { load.close() }
        } catch (_: Exception) { finish(SettingsResult(error = SettingsError.REQUEST_FAILED)) }
    }

    fun stopPreview(token: SavedNotificationSoundsToken) {
        if (retired || token != snapshot.token) return
        stopPlayback(); emit()
    }

    fun upload(token: SavedNotificationSoundsToken, expectedRevision: Long,
        prepared: SettingsPreparedNotificationSound, retained: PendingAttachmentLease,
    ) {
        val data = snapshot.data
        val upload = prepared.upload
        val error = admission(token, expectedRevision) ?: when {
            data == null || !data.limits.known || data.limitsError != null -> data?.limitsError ?: SettingsError.NOT_READY
            upload.path.isBlank() || upload.sizeBytes <= 0 || upload.durationMillis <= 0 ||
                !upload.sha256.matches(Regex("[a-fA-F0-9]{64}")) -> SettingsError.INVALID_VALUE
            upload.sizeBytes > data.limits.maximumBytes!! ||
                upload.durationMillis > data.limits.maximumDurationSeconds!!.toLong() * 1000 ||
                data.sounds.size >= data.limits.maximumCount!! -> SettingsError.INVALID_VALUE
            else -> null
        }
        if (accepts(token)) snapshot = snapshot.copy(lastAddedSoundId = null)
        if (error != null) {
            try { reject(token, error) } finally { retained.release() }
            return
        }
        write(token, retained, { actual -> PendingReadback(added = actual.sound) }, uploaded = true) { active, finish ->
            session.add(data!!, upload, active, finish)
        }
    }

    fun remove(token: SavedNotificationSoundsToken, expectedRevision: Long, expectedSound: SavedNotificationSound) {
        val data = snapshot.data
        val error = admission(token, expectedRevision) ?: if (data?.sounds?.singleOrNull {
                it.id == expectedSound.id && sameIdentity(it, expectedSound)
            } == null) SettingsError.CONFLICT else null
        if (error != null) { reject(token, error); return }
        write(token, null, { PendingReadback(removed = expectedSound.id) }) { active, finish ->
            session.remove(data!!.accountUserId, expectedSound, active, finish)
        }
    }

    private fun write(token: SavedNotificationSoundsToken, retained: PendingAttachmentLease?,
        pending: (SavedNotificationSoundMutationResult) -> PendingReadback?,
        uploaded: Boolean = false,
        perform: (() -> Boolean, (SavedNotificationSoundMutationResult) -> Unit) -> Unit,
    ) {
        val operation = Mutation(token, mutationAuthority()); mutation = operation
        val observed = changes
        val once = AtomicBoolean()
        stopPlayback(); stopWarming()
        snapshot = snapshot.copy(saving = true, error = null, lastAddedSoundId = null); emit()
        fun finish(value: SavedNotificationSoundMutationResult) {
            if (value.receipt == NotificationSettingsWriteReceipt.INDETERMINATE) {
                if (!once.get()) terminal.enqueue {
                    if (mutation !== operation) return@enqueue
                    lastMutationReceipt = value.receipt to value.error
                    if (!retired && accepts(token) && operation.active()) {
                        snapshot = snapshot.copy(readbackPending = true, refreshRequired = true,
                            error = value.error ?: SettingsError.REQUEST_FAILED)
                        emit()
                    }
                }
                return
            }
            if (!once.compareAndSet(false, true)) return
            // Only a true terminal or confirmed non-dispatch releases this owner's file handle.
            try {
                // A send-path failure is not a physical terminal; the session lease registry releases on Closed.
                if (value.receipt != NotificationSettingsWriteReceipt.INDETERMINATE) runCatching { retained?.release() }
            } finally {
                terminal.enqueue {
                    if (mutation !== operation) return@enqueue
                    mutation = null
                    lastMutationReceipt = value.receipt to value.error
                    if (retired) return@enqueue
                    if (value.locallyAccepted) {
                        acceptedRevision++
                        pendingReadback = pending(value)
                        runCatching(invalidateSoundSource)
                    }
                    if (accepts(token) && operation.active()) {
                        val actual = value.actual?.takeIf { validData(it) }
                        val fresh = actual != null && currentData(actual, observed)
                        if (fresh && pendingReadback?.matches(actual!!) == true) pendingReadback = null
                        val error = value.error ?: if (value.actual != null && actual == null) SettingsError.CONFLICT
                            else if (actual == null || pendingReadback != null) SettingsError.REQUEST_FAILED else null
                        snapshot = snapshot.copy(saving = false, data = actual ?: snapshot.data,
                            error = error, readbackPending = pendingReadback != null || (value.locallyAccepted && actual == null),
                            refreshRequired = error != null || !fresh || pendingReadback != null,
                            lastAddedSoundId = if (uploaded && value.locallyAccepted) value.sound?.id?.takeIf {
                                it != 0L && it != -1L
                            } else null)
                        emit()
                        if (fresh) startWarming(actual!!)
                    } else if (accepts(snapshot.token)) {
                        snapshot = snapshot.copy(saving = false)
                        val current = snapshot.token
                        terminal.readback({ accepts(current) && mutation == null }) { read() }
                    } else retireInactivePage(token)
                }
            }
        }
        try { perform(operation.active, ::finish) }
        catch (_: Exception) { finish(SavedNotificationSoundMutationResult(error = SettingsError.REQUEST_FAILED)) }
    }

    private fun read() {
        stopWarming()
        readLease?.set(false)
        val lease = AtomicBoolean(true); readLease = lease
        val token = snapshot.token; val pageActive = currentPage(); val observed = changes
        val active = { lease.get() && pageActive() }
        val once = AtomicBoolean()
        snapshot = snapshot.copy(loading = true, error = null); emit()
        fun finish(value: SettingsResult<SavedNotificationSoundsData>) {
            if (!once.compareAndSet(false, true)) return
            enqueue {
                if (readLease !== lease || !active() || !accepts(token)) {
                    retireInactivePage(token); return@enqueue
                }
                readLease = null; lease.set(false)
                val actual = value.value?.takeIf { value.error == null && validData(it) }
                val fresh = actual != null && currentData(actual, observed)
                if (actual != null) {
                    accountUserId = actual.accountUserId
                    if (fresh && pendingReadback?.matches(actual) == true) pendingReadback = null
                }
                snapshot = snapshot.copy(loading = false, data = actual ?: snapshot.data,
                    error = value.error ?: if (actual == null) SettingsError.CONFLICT else null,
                    readbackPending = pendingReadback != null,
                    refreshRequired = !fresh || pendingReadback != null)
                emit()
                if (fresh) startWarming(actual!!)
            }
        }
        try { session.load(active, ::finish) }
        catch (_: Exception) { finish(SettingsResult(error = SettingsError.REQUEST_FAILED)) }
    }

    private fun admission(token: SavedNotificationSoundsToken, expectedRevision: Long): SettingsError? = when {
        !accepts(token) || mutation != null || snapshot.loading || snapshot.saving || snapshot.choosing ||
            snapshot.readbackPending -> SettingsError.NOT_READY
        snapshot.data == null || snapshot.refreshRequired || snapshot.revision != expectedRevision -> SettingsError.CONFLICT
        else -> null
    }

    private fun validData(data: SavedNotificationSoundsData) = data.accountUserId > 0 &&
        (accountUserId == null || accountUserId == data.accountUserId) && data.sounds.size <= 4096 &&
        data.sounds.all { it.id != 0L && it.id != -1L && it.durationSeconds >= 0 && it.date >= 0 &&
            it.file.id > 0 && it.file.uniqueId.isNotBlank() } && data.sounds.map { it.id }.distinct().size == data.sounds.size

    private fun currentData(data: SavedNotificationSoundsData, observed: Long) = when {
        data.nativeRevision > 0 -> runCatching { session.isCurrent(data) }.getOrDefault(false)
        data.nativeRevision == 0L -> observed == changes
        else -> false
    }

    private fun changed() {
        if (retired) return
        runCatching(invalidateSoundSource)
        changes++
        stopPlayback(); stopWarming()
        if (accepts(snapshot.token)) { snapshot = snapshot.copy(refreshRequired = true); emit() }
    }

    private fun allowed(active: () -> Boolean) = !retired && runCatching { isAllowed() && active() }.getOrDefault(false)
    private fun mutationAuthority(): () -> Boolean {
        val active = currentPage()
        val authority = terminal.captureAuthority()
        return { active() && authority() }
    }
    private fun currentPage(): () -> Boolean {
        val local = owner; val visible = page
        return { local.get() && allowed(visible) }
    }
    private fun reject(token: SavedNotificationSoundsToken, error: SettingsError) {
        if (accepts(token)) { snapshot = snapshot.copy(error = error); emit() }
    }
    private fun retirePage() {
        owner.set(false); page = { false }; readLease?.set(false); readLease = null
        choice?.finish(SettingsResult(error = SettingsError.NOT_READY)); choice = null
        stopPlayback(); stopWarming()
    }
    private fun retireInactivePage(token: SavedNotificationSoundsToken) {
        if (!retired && snapshot.opened && snapshot.token == token && !accepts(token)) close(token)
    }
    private fun stopPlayback() {
        playback?.stop(); playback = null
        snapshot = snapshot.copy(previewSoundId = null, previewLoading = false, previewError = null,
            previewDefaultFallback = false)
    }
    private fun startWarming(data: SavedNotificationSoundsData) {
        stopWarming()
        if (!accepts(snapshot.token)) return
        // Worker predicates only capture atomic ownership and the caller's page gate, never actor maps.
        val localOwner = owner; val visible = page
        val batch = Warming(snapshot.token, data, { localOwner.get() && runCatching(visible).getOrDefault(false) })
        warming = batch
        pumpWarming(batch)
    }
    private fun pumpWarming(batch: Warming) {
        if (warming !== batch || batch.pumping || !batch.active.get()) return
        if (!accepts(batch.token)) { stopWarming(); retireInactivePage(batch.token); return }
        batch.pumping = true
        try {
            while (warming === batch && batch.active.get() && accepts(batch.token) &&
                batch.running.size < 2 && batch.cursor < batch.data.sounds.size) {
                val sound = batch.data.sounds[batch.cursor++]
                val item = WarmItem(); batch.running += item
                val active = { item.active.get() && batch.active.get() && batch.pageGate() }
                fun finish(@Suppress("UNUSED_PARAMETER") value: SettingsResult<Unit>) {
                    if (!item.terminal.compareAndSet(false, true)) return
                    enqueue {
                        item.stop()
                        if (warming !== batch || !batch.active.get()) return@enqueue
                        batch.running.remove(item)
                        // An old callback may release its own lease, but cannot start work for another page.
                        if (!accepts(batch.token)) { stopWarming(); retireInactivePage(batch.token); return@enqueue }
                        pumpWarming(batch)
                    }
                }
                try {
                    val load = warmSound(batch.data.accountUserId, sound, active, ::finish)
                    if (item.active.get()) item.load = load else runCatching { load.close() }
                } catch (_: Exception) { finish(SettingsResult(error = SettingsError.REQUEST_FAILED)) }
            }
        } finally { batch.pumping = false }
    }
    private fun stopWarming() {
        val batch = warming ?: return
        warming = null; batch.active.set(false)
        batch.running.toList().forEach { it.stop() }; batch.running.clear()
    }
    private fun emit() {
        snapshot = snapshot.copy(revision = ++revision, acceptedRevision = acceptedRevision)
        publish(snapshot)
    }

    private companion object {
        fun sameIdentity(a: SavedNotificationSound, b: SavedNotificationSound) = a.id == b.id &&
            a.durationSeconds == b.durationSeconds && a.date == b.date && a.title == b.title && a.data == b.data &&
            a.file.id == b.file.id && a.file.uniqueId.isNotBlank() && a.file.uniqueId == b.file.uniqueId
    }
}
