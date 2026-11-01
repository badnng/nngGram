package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import java.util.concurrent.atomic.AtomicBoolean

/** Actor-only gate. It checks parent data and must not throw after allocating a write revision. */
internal fun interface ProfileStickerAvatarMutationGate {
    fun submit(
        parent: SettingsProfileExtrasToken,
        expectedPhotoId: Long?,
        ownsMutation: () -> Boolean,
        observed: (Long, SettingsResult<Unit>) -> Unit,
        work: (() -> Boolean, (SettingsResult<Unit>) -> Unit) -> Unit,
    ): Long?
}

/**
 * Actor-owned picker facade. Native leases capture only immutable references and atomics;
 * physical RPC reservations remain the native session's responsibility.
 */
internal class SettingsProfileStickerAvatarController(
    private val session: TdProfileStickerAvatarSession,
    private val enqueue: (() -> Unit) -> Unit,
    private val allows: (SettingsProfileExtrasToken) -> Boolean,
    private val captureParent: (SettingsProfileExtrasToken) -> (() -> Boolean)?,
    private val allowsSave: (SettingsProfileExtrasToken, Long?) -> Boolean,
    private val mutate: ProfileStickerAvatarMutationGate,
) {
    private var retired = false // actor only
    private var transition: Any = Any() // actor only
    private var owner: Owner? = null // actor only

    private class Owner(
        val parent: SettingsProfileExtrasToken,
        val picker: ProfileStickerAvatarPickerTicket,
        val source: AtomicBoolean,
        val current: () -> Boolean,
    ) {
        var query: Any? = null
        var catalogSource = AtomicBoolean(true)
        var selectionAttempt: Any = Any()
        var selection: Selection? = null
        var windowPending = false
        var catalogVersion = CatalogVersion(picker)
        val reads = LinkedHashSet<ReadHandle>()
    }

    /** Safe for native threads: this witness reads only the ticket's native-owned atomic revision. */
    private class CatalogVersion(private val picker: ProfileStickerAvatarPickerTicket) {
        private val revision = picker.catalogRevision.value
        fun current(): Boolean = picker.catalogRevision.value == revision
    }

    private class Selection(
        val ticket: ProfileStickerAvatarSelectionTicket,
        val source: AtomicBoolean,
        val catalogVersion: CatalogVersion,
    )

    private interface ReadHandle { fun cancel() }

    private class Read<T>(
        val query: Any,
        val source: AtomicBoolean,
        val current: () -> Boolean,
        val result: (SettingsResult<T>) -> Unit,
    ) : ReadHandle {
        val nativeOnce = AtomicBoolean()
        val consumerOnce = AtomicBoolean()

        fun finish(value: SettingsResult<T>) {
            source.set(false)
            if (consumerOnce.compareAndSet(false, true)) runCatching { result(value) }
        }

        override fun cancel() = finish(SettingsResult(error = SettingsError.NOT_READY))
    }

    fun open(parent: SettingsProfileExtrasToken, active: () -> Boolean,
        result: (SettingsResult<ProfileStickerAvatarPickerTicket>) -> Unit,
    ) {
        if (retired || parent.userId <= 0 || !permitted(parent) || !safe(active)) {
            notify(result, SettingsResult(error = SettingsError.NOT_READY)); return
        }
        val marker = Any()
        transition = marker
        detachOwner()
        if (retired || transition !== marker || !permitted(parent) || !safe(active)) {
            notify(result, SettingsResult(error = SettingsError.NOT_READY)); return
        }
        val parentLease = runCatching { captureParent(parent) }.getOrNull()
        if (parentLease == null || retired || transition !== marker) {
            notify(result, SettingsResult(error = SettingsError.NOT_READY)); return
        }
        val source = AtomicBoolean(true)
        val current = oneWay(source, parentLease, active)
        val picker = try { session.open(parent.userId, current) } catch (_: Exception) { null }
        if (picker == null || retired || transition !== marker || !permitted(parent) || !current()) {
            source.set(false)
            if (picker != null) runCatching { session.close(picker) }
            notify(result, SettingsResult(error = SettingsError.NOT_READY)); return
        }
        owner = Owner(parent, picker, source, current)
        notify(result, SettingsResult(picker))
    }

    /** Cleanup must remain available even if parent or external leases have already closed. */
    fun close(parent: SettingsProfileExtrasToken, picker: ProfileStickerAvatarPickerTicket) {
        val current = owner ?: return
        if (current.parent != parent || current.picker !== picker) return
        transition = Any()
        detachOwner()
    }

    fun closeParent(parent: SettingsProfileExtrasToken) {
        if (owner?.parent != parent) return
        transition = Any()
        detachOwner()
    }

    /** Actor only. The returned predicate holds no actor-owned fields or collections. */
    fun captureMedia(parent: SettingsProfileExtrasToken, picker: ProfileStickerAvatarPickerTicket,
        selection: ProfileStickerAvatarSelectionTicket?): (() -> Boolean)? {
        val current = matching(parent, picker) { true } ?: return null
        if (current.windowPending) return null
        val selected = if (selection == null) null else {
            current.selection?.takeIf {
                it.ticket === selection && it.source.get() && it.catalogVersion.current()
            } ?: return null
        }
        val catalogVersion = selected?.catalogVersion ?: current.catalogVersion
        if (!catalogVersion.current()) return null
        val ownerCurrent = current.current
        val catalogSource = current.catalogSource
        val selectionSource = selected?.source
        return oneWay(AtomicBoolean(true), ownerCurrent, catalogSource::get,
            { selectionSource?.get() ?: true }, catalogVersion::current)
    }

    fun deactivate() {
        if (retired) return
        retired = true
        transition = Any()
        detachOwner()
    }

    fun loadDefault(parent: SettingsProfileExtrasToken, picker: ProfileStickerAvatarPickerTicket,
        active: () -> Boolean, result: (SettingsResult<ProfileStickerAvatarCandidatePage>) -> Unit,
    ) = read(parent, picker, active, result) { done -> session.loadDefault(picker, done) }

    fun loadInstalledPacks(parent: SettingsProfileExtrasToken, picker: ProfileStickerAvatarPickerTicket,
        kind: ProfileStickerAvatarPackKind, active: () -> Boolean,
        result: (SettingsResult<ProfileStickerAvatarPackPage>) -> Unit,
    ) = read(parent, picker, active, result) { done -> session.loadInstalledPacks(picker, kind, done) }

    fun openPack(parent: SettingsProfileExtrasToken, picker: ProfileStickerAvatarPickerTicket,
        pack: ProfileStickerAvatarPackTicket, active: () -> Boolean,
        result: (SettingsResult<ProfileStickerAvatarCandidatePage>) -> Unit,
    ) = read(parent, picker, active, result) { done -> session.openPack(picker, pack, done) }

    fun loadWindow(parent: SettingsProfileExtrasToken, picker: ProfileStickerAvatarPickerTicket,
        cursor: ProfileStickerAvatarCatalogCursor, active: () -> Boolean,
        result: (SettingsResult<ProfileStickerAvatarCatalogWindow>) -> Unit,
    ) = read(parent, picker, active, result, preservingWindow = true) { done -> session.loadWindow(picker, cursor, done) }

    fun searchInstalled(parent: SettingsProfileExtrasToken, picker: ProfileStickerAvatarPickerTicket,
        kind: ProfileStickerAvatarPackKind, query: String, active: () -> Boolean,
        result: (SettingsResult<ProfileStickerAvatarPackPage>) -> Unit,
    ) = read(parent, picker, active, result) { done -> session.searchInstalled(picker, kind, query, done) }

    fun select(parent: SettingsProfileExtrasToken, picker: ProfileStickerAvatarPickerTicket,
        candidate: ProfileStickerAvatarCandidateTicket, active: () -> Boolean,
        result: (SettingsResult<ProfileStickerAvatarSelectionTicket>) -> Unit,
    ) {
        val current = matching(parent, picker, active)
        if (current == null || current.windowPending) { notify(result, SettingsResult(error = SettingsError.NOT_READY)); return }
        val catalogVersion = current.catalogVersion
        if (!catalogVersion.current()) { notify(result, SettingsResult(error = SettingsError.CONFLICT)); return }
        val attempt = Any()
        current.selectionAttempt = attempt
        val previous = current.selection
        val selected = try { session.select(picker, candidate) } catch (_: Exception) { null }
        if (selected != null && current.selection === previous) {
            previous?.source?.set(false)
            current.selection = null
        }
        val checkedOwner = matching(parent, picker, active)
        if (checkedOwner !== current || current.selectionAttempt !== attempt || owner !== current || !catalogVersion.current()) {
            notify(result, SettingsResult(error = SettingsError.NOT_READY)); return
        }
        if (selected == null) { notify(result, SettingsResult(error = SettingsError.CONFLICT)); return }
        current.selection = Selection(selected, AtomicBoolean(true), catalogVersion)
        notify(result, SettingsResult(selected))
    }

    fun save(parent: SettingsProfileExtrasToken, picker: ProfileStickerAvatarPickerTicket,
        selection: ProfileStickerAvatarSelectionTicket, expectedPhotoId: Long?,
        background: ProfileStickerAvatarBackground, active: () -> Boolean,
        admitted: (Long?) -> Unit, result: (ProfileStickerAvatarMutationResult) -> Unit,
    ) {
        val current = matching(parent, picker, active)
        val selected = current?.selection
        if (current == null || current.windowPending || selected == null || selected.ticket !== selection || expectedPhotoId == 0L ||
            !selected.source.get() || !selected.catalogVersion.current() ||
            !runCatching { allowsSave(parent, expectedPhotoId) }.getOrDefault(false)) {
            notifyAdmission(admitted, null); return
        }
        val saveSource = AtomicBoolean(true)
        val saveCurrent = oneWay(saveSource, current.current, selected.source::get, selected.catalogVersion::current, active)
        if (!saveCurrent()) { notifyAdmission(admitted, null); return }
        val observedOnce = AtomicBoolean()
        val revision = try {
            mutate.submit(parent, expectedPhotoId, saveCurrent, { write, outcome ->
                if (observedOnce.compareAndSet(false, true)) {
                    val error = if (outcome.value != null) null else outcome.error ?: SettingsError.REQUEST_FAILED
                    runCatching { result(ProfileStickerAvatarMutationResult(parent, write, error)) }
                }
            }) { gateCurrent, done ->
                val nativeCurrent = oneWay(AtomicBoolean(true), saveCurrent, gateCurrent)
                session.save(picker, selection, expectedPhotoId, background, nativeCurrent, done)
            }
        } catch (_: Exception) { null }
        notifyAdmission(admitted, revision)
    }

    private fun <T> read(parent: SettingsProfileExtrasToken, picker: ProfileStickerAvatarPickerTicket,
        active: () -> Boolean, result: (SettingsResult<T>) -> Unit,
        preservingWindow: Boolean = false,
        submit: ((SettingsResult<T>) -> Unit) -> Unit,
    ) {
        val current = matching(parent, picker, active)
        if (current == null || current.reads.size >= MAX_READS || preservingWindow && current.windowPending) {
            notify(result, SettingsResult(error = SettingsError.NOT_READY)); return
        }
        if (preservingWindow && !current.catalogVersion.current()) {
            current.catalogSource.set(false)
            current.selection?.source?.set(false)
            current.selection = null
            notify(result, SettingsResult(error = SettingsError.CONFLICT)); return
        }
        if (!preservingWindow) current.selection?.source?.set(false)
        current.catalogSource.set(false)
        current.catalogSource = AtomicBoolean(true)
        val catalogVersion = CatalogVersion(picker)
        current.catalogVersion = catalogVersion
        if (!preservingWindow) current.selection = null
        current.windowPending = preservingWindow
        current.selectionAttempt = Any()
        val query = Any()
        current.query = query
        val source = AtomicBoolean(true)
        val request = Read(query, source, oneWay(source, current.current, catalogVersion::current, active), result)
        current.reads += request
        fun returned(response: SettingsResult<T>) {
            if (!request.nativeOnce.compareAndSet(false, true)) return
            runCatching {
                enqueue {
                    if (!current.reads.remove(request)) return@enqueue
                    val valid = owner === current && current.query === query &&
                        matching(parent, picker, active) === current && request.current()
                    if (owner === current && current.query === query && preservingWindow) {
                        current.windowPending = false
                        if (!valid || response.value == null || response.error != null) {
                            current.selection?.source?.set(false)
                            current.selection = null
                        }
                    }
                    request.finish(if (valid) response else SettingsResult(error = SettingsError.NOT_READY))
                }
            }
        }
        try { submit(::returned) } catch (_: Exception) { returned(SettingsResult(error = SettingsError.REQUEST_FAILED)) }
    }

    private fun matching(parent: SettingsProfileExtrasToken, picker: ProfileStickerAvatarPickerTicket,
        active: () -> Boolean,
    ): Owner? {
        val current = owner ?: return null
        return current.takeIf { !retired && it.parent == parent && it.picker === picker &&
            permitted(parent) && it.current() && safe(active) && it.source.get() }
    }

    private fun detachOwner() {
        val old = owner ?: return
        owner = null
        old.source.set(false)
        old.catalogSource.set(false)
        old.selection?.source?.set(false)
        old.selection = null
        old.query = null
        old.selectionAttempt = Any()
        val pending = old.reads.toList()
        old.reads.clear()
        pending.forEach { it.cancel() }
        runCatching { session.close(old.picker) }
    }

    private fun permitted(parent: SettingsProfileExtrasToken) = runCatching { allows(parent) }.getOrDefault(false)

    private fun <T> notify(result: (SettingsResult<T>) -> Unit, value: SettingsResult<T>) { runCatching { result(value) } }
    private fun notifyAdmission(admitted: (Long?) -> Unit, value: Long?) { runCatching { admitted(value) } }

    private companion object {
        const val MAX_READS = 8

        // Static helpers keep native predicates from capturing this actor or its collections.
        fun oneWay(source: AtomicBoolean, vararg checks: () -> Boolean): () -> Boolean = {
            if (!source.get()) false else {
                if (checks.any { !safe(it) }) source.set(false)
                source.get()
            }
        }

        fun safe(active: () -> Boolean) = runCatching(active).getOrDefault(false)
    }
}
