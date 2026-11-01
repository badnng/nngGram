package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/** Actor-owned lazy storage inspector, with no filesystem deletion capability. */
internal class SettingsStorageController(
    private val generation: Long,
    private val session: TdSettingsStorageSession,
    private val enqueue: (() -> Unit) -> Unit,
    private val publish: (SettingsStorageSnapshot) -> Unit,
    private val volumeSource: () -> Path? = { null },
    private val volumeCoordinator: SettingsStorageVolumeCoordinator = SharedSettingsStorageVolumeCoordinator.instance,
    private val volumeCommit: (() -> Unit) -> Boolean = { work -> work(); true },
) {
    private var snapshot = SettingsStorageSnapshot(token = SettingsStorageToken(generation))
    private var ownerId = 0L
    private var requestId = 0L
    private var volumeRequestId = 0L
    private var awaitingVolumeSource = false
    private var owner = SettingsProxyProbeOwner { false }
    private var retired = false

    fun open(active: () -> Boolean = { true }) = openOwned(SettingsProxyProbeOwner(active))

    fun openOwned(next: SettingsProxyProbeOwner) {
        if (retired || !next.current()) return
        owner.revoke()
        owner = next
        requestId++
        volumeRequestId++
        awaitingVolumeSource = false
        val request = requestId
        snapshot = SettingsStorageSnapshot(SettingsStorageToken(generation, ++ownerId), opened = true, loading = true)
        val token = snapshot.token
        emit()
        if (owner === next && accepts(token) && requestId == request) readVolume()
        if (owner === next && accepts(token) && requestId == request && snapshot.loading) read()
    }

    fun closeOwned(expected: SettingsProxyProbeOwner) {
        if (owner === expected) close(snapshot.token)
    }

    fun ownedToken(expected: SettingsProxyProbeOwner): SettingsStorageToken? =
        snapshot.token.takeIf { owner === expected && accepts(it) }

    fun close(token: SettingsStorageToken) {
        if (retired || token != snapshot.token) return
        owner.revoke()
        requestId++
        volumeRequestId++
        awaitingVolumeSource = false
        snapshot = SettingsStorageSnapshot(token = SettingsStorageToken(generation, ++ownerId))
        emit()
    }

    fun deactivate() {
        owner.revoke()
        retired = true
        requestId++
        volumeRequestId++
        awaitingVolumeSource = false
        snapshot = SettingsStorageSnapshot(token = SettingsStorageToken(generation, ++ownerId))
        emit()
    }

    fun refresh(token: SettingsStorageToken) {
        if (!accepts(token) || snapshot.loading || snapshot.clearing) return
        val captured = owner
        val request = requestId
        snapshot = snapshot.copy(loading = true, error = null)
        emit()
        if (owner === captured && accepts(token) && requestId == request && snapshot.loading) read()
    }

    fun refreshVolume(token: SettingsStorageToken) {
        if (!accepts(token) || snapshot.volume is SettingsStorageVolume.Loading) return
        readVolume()
    }

    /** READY may be delivered before the actor receives the successful parameter response. */
    fun acceptedVolumeAvailable() {
        if (awaitingVolumeSource && accepts(snapshot.token)) readVolume()
    }

    private fun readVolume() {
        val token = snapshot.token
        val captured = owner
        val path = runCatching(volumeSource).getOrNull()
        val request = ++volumeRequestId
        if (path == null) {
            awaitingVolumeSource = true
            snapshot = snapshot.copy(volume = SettingsStorageVolume.Unavailable)
            emit()
            return
        }
        awaitingVolumeSource = false
        snapshot = snapshot.copy(volume = SettingsStorageVolume.Loading)
        emit()
        if (owner !== captured || !accepts(token) || volumeRequestId != request) return
        if (runCatching(volumeSource).getOrNull() != path) {
            finishVolume(captured, token, request, path, null)
            return
        }
        volumeCoordinator.submit(path, captured) { result ->
            enqueue { finishVolume(captured, token, request, path, result) }
        }
    }

    private fun finishVolume(captured: SettingsProxyProbeOwner, token: SettingsStorageToken, request: Long,
        path: Path, result: SettingsStorageVolume.Available?,
    ) {
        // The exact owner synchronizes publication with close/revocation; no IO is
        // performed while holding this lock, so a blocked filesystem cannot block exit.
        captured.newLease().dispatch {
            if (owner === captured && accepts(token) && volumeRequestId == request) {
                volumeCommit {
                    if (owner === captured && accepts(token) && volumeRequestId == request) {
                        val currentPath = runCatching(volumeSource).getOrNull()
                        awaitingVolumeSource = currentPath == null
                        snapshot = snapshot.copy(volume = if (currentPath == path) result ?: SettingsStorageVolume.Unavailable
                            else SettingsStorageVolume.Unavailable)
                        emit()
                    }
                }
            }
        }
    }

    fun clear(token: SettingsStorageToken, expectedRevision: Long, types: Set<SettingsStorageFileType>) {
        if (!accepts(token) || snapshot.loading || snapshot.clearing || snapshot.details == null) return
        if (snapshot.needsFreshStatistics || snapshot.refreshAfterClearFailed || snapshot.revision != expectedRevision ||
            types.isEmpty() || types.any { !it.removable } ||
            types.any { type -> snapshot.details!!.categories.none { it.type == type && it.count > 0 } }) {
            snapshot = snapshot.copy(error = SettingsError.CONFLICT)
            emit()
            return
        }
        val captured = owner
        val request = requestId
        val selected = types.toSet()
        snapshot = snapshot.copy(clearing = true, error = null, lastClearError = null,
            needsFreshStatistics = true, refreshAfterClearFailed = false)
        emit()
        if (owner !== captured || !accepts(token) || requestId != request || !snapshot.clearing ||
            snapshot.revision != expectedRevision) return
        safely(captured, { owner, callback -> session.clearStorageOwned(selected, owner, callback) }) { result ->
            if (result.value == null) {
                val error = result.error ?: SettingsError.REQUEST_FAILED
                snapshot = snapshot.copy(clearing = false, error = error, lastClearError = error)
                emit()
            } else {
                // Native response acknowledges the request, not the exact bytes unlinked.
                snapshot = snapshot.copy(clearing = false, loading = true,
                    completedClearRevision = snapshot.completedClearRevision + 1)
                val readRequest = requestId
                emit()
                if (owner === captured && accepts(token) && requestId == readRequest && snapshot.loading)
                    read(afterClear = true)
            }
        }
    }

    private fun read(afterClear: Boolean = false) {
        val captured = owner
        safely(captured, { owner, callback -> session.storageDetailsOwned(owner, callback) }) { result ->
            snapshot = if (result.value != null) snapshot.copy(loading = false, details = result.value,
                revision = snapshot.revision + 1, error = null, needsFreshStatistics = false, refreshAfterClearFailed = false,
                lastClearError = session.lastClearError() ?: snapshot.lastClearError)
            else snapshot.copy(loading = false, error = result.error ?: SettingsError.REQUEST_FAILED,
                refreshAfterClearFailed = afterClear || snapshot.refreshAfterClearFailed)
            emit()
        }
    }

    private fun accepts(token: SettingsStorageToken): Boolean =
        !retired && owner.current() && snapshot.opened && snapshot.token == token

    private fun <T> safely(captured: SettingsProxyProbeOwner,
        invoke: (SettingsProxyProbeOwner, (SettingsResult<T>) -> Unit) -> Unit,
        completed: (SettingsResult<T>) -> Unit,
    ) {
        val token = snapshot.token
        val request = ++requestId
        val done = AtomicBoolean()
        fun respond(result: SettingsResult<T>) {
            if (!done.compareAndSet(false, true)) return
            val context = SettingsStorageCallbackContext.capture()
            enqueue {
                if (context.current() && owner === captured && accepts(token) && requestId == request) completed(result)
            }
        }
        try { invoke(captured, ::respond) }
        catch (_: Exception) { respond(SettingsResult(error = SettingsError.REQUEST_FAILED)) }
    }

    private fun emit() = publish(snapshot)
}
