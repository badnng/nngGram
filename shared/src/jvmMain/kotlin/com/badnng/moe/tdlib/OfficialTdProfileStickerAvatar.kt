package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Accepted work is non-reentrant. A throw means unknown dispatch; only real exit can settle it. */
internal interface ProfileStickerAvatarMediaConsumerPermit {
    fun deliver(action: () -> Unit, exited: () -> Unit)
}

internal enum class ProfileStickerAvatarMediaAdmission { ACCEPTED, BUSY }

internal interface ProfileStickerAvatarMediaAsync {
    /** Nonblocking process-wide ticket; null is a definite pre-admission BUSY. */
    fun reserveConsumer(): ProfileStickerAvatarMediaConsumerPermit?
    fun dispatch(action: () -> Unit, exited: () -> Unit, rejected: () -> Unit)
}

/** Process-wide, separate bounded pools: foreign consumers cannot block dispatch or TDLib's receiver. */
internal object SharedProfileStickerAvatarMediaAsync : ProfileStickerAvatarMediaAsync {
    private class Task(val action: () -> Unit, val exited: () -> Unit) : Runnable {
        val started = AtomicBoolean()
        val closed = AtomicBoolean()
        override fun run() {
            if (started.compareAndSet(false, true)) try { action() } catch (_: Throwable) {}
        }
        fun afterExit() {
            if (started.get() && closed.compareAndSet(false, true)) try { exited() } catch (_: Throwable) {}
        }
    }
    private fun pool(name: String): ThreadPoolExecutor = object : ThreadPoolExecutor(
        8, 8, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(128),
        { task -> Thread(task, name).apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy(),
    ) {
        override fun afterExecute(task: Runnable, failure: Throwable?) {
            try { (task as? Task)?.afterExit() } finally { super.afterExecute(task, failure) }
        }
    }
    private val dispatchers = pool("profile-sticker-media-dispatch")
    private val consumers = pool("profile-sticker-media-consumer")
    private val consumerLock = Any()
    private var consumerTickets = 0

    override fun reserveConsumer(): ProfileStickerAvatarMediaConsumerPermit? = synchronized(consumerLock) {
        if (consumerTickets >= 128) null else {
            consumerTickets++
            object : ProfileStickerAvatarMediaConsumerPermit {
                private val submitted = AtomicBoolean()
                private val released = AtomicBoolean()
                override fun deliver(action: () -> Unit, exited: () -> Unit) {
                    check(submitted.compareAndSet(false, true))
                    val task = Task(action) {
                        try { exited() } finally {
                            if (released.compareAndSet(false, true)) synchronized(consumerLock) { consumerTickets-- }
                        }
                    }
                    // One accepted operation can enqueue one task, and 128 global tickets fit
                    // in this never-shutdown 8-worker / 128-queue executor. A throw is unknown:
                    // do not release its ticket or tell the caller that a result was delivered.
                    consumers.execute(task)
                }
            }
        }
    }

    override fun dispatch(action: () -> Unit, exited: () -> Unit, rejected: () -> Unit) {
        val task = Task(action, exited)
        try { dispatchers.execute(task) }
        catch (_: RejectedExecutionException) {
            // This exact AbortPolicy rejection is known not to have enqueued the task.
            rejected()
        }
    }
}

/**
 * Metadata/selection helper. The actor must supply its existing single profile-mutation
 * admission and an atomic, irrevocable page/engine lease. This helper never downloads artwork.
 */
internal class OfficialTdProfileStickerAvatar(
    private val send: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit,
) : TdProfileStickerAvatarSession {
    // Preserve the single-send constructor, including positional and trailing-lambda callers.
    private var mediaAsync: ProfileStickerAvatarMediaAsync = SharedProfileStickerAvatarMediaAsync
    private var receiverOrder: ProfileStickerAvatarOrderedReceiver? = null
    internal constructor(order: ProfileStickerAvatarOrderedReceiver?,
        send: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit,
    ) : this(send) { receiverOrder = order }
    internal constructor(async: ProfileStickerAvatarMediaAsync,
        send: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit,
    ) : this(send) { mediaAsync = async }
    internal constructor(async: ProfileStickerAvatarMediaAsync, order: ProfileStickerAvatarOrderedReceiver?,
        send: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit,
    ) : this(send) { mediaAsync = async; receiverOrder = order }

    private val lock = Any()
    private var retired = false // guarded by lock
    private var owner: Owner? = null // guarded by lock
    private val operations = LinkedHashSet<Operation<*>>() // guarded by lock
    private val mediaGrants = IdentityHashMap<ProfileStickerAvatarMediaGrant, MediaGrantRecord>() // guarded by lock
    // Consumer cancellation never removes a native reservation; only its own one-shot completion does.
    private val nativeInFlight = IdentityHashMap<NativeRequest, Unit>() // guarded by lock
    private val waitingMedia = java.util.ArrayDeque<MediaAdmission>() // guarded by lock
    private val waitingReads = java.util.ArrayDeque<PendingRead>() // guarded by lock
    private var mediaOrder = 0L // one FIFO sequence across both gates, guarded by lock
    private fun nextMediaOrderLocked(): Long { check(mediaOrder < Long.MAX_VALUE); return ++mediaOrder }

    private inner class NativeRequest(val mediaOperation: Operation<*>?) { val completed = AtomicBoolean() }
    private inner class MediaAdmission(
        val grant: ProfileStickerAvatarMediaGrant,
        val active: () -> Boolean,
        val result: (SettingsResult<ProfileStickerAvatarOwnedMedia>) -> Unit,
        val terminal: () -> Unit,
        val permit: ProfileStickerAvatarMediaConsumerPermit,
        val order: Long,
    )
    private inner class PendingRead(val operation: Operation<*>, val order: Long,
        val dispatch: (NativeRequest) -> Unit)

    private class Owner(val userId: Long, val active: () -> Boolean, val ticket: ProfileStickerAvatarPickerTicket) {
        var epoch: Any = Any()
        var mediaEpoch = AtomicBoolean(true)
        val packs = IdentityHashMap<ProfileStickerAvatarPackTicket, PackRecord>()
        val candidates = IdentityHashMap<ProfileStickerAvatarCandidateTicket, CandidateRecord>()
        val cursors = IdentityHashMap<ProfileStickerAvatarCatalogCursor, CursorRecord>()
        var windowPending = false
        var catalogSource: CatalogSource? = null
        var initialCatalogLoad = false
        var initialCatalogDirty = false
        var selection: Selection? = null
    }

    private data class PackRecord(val id: Long, val kind: ProfileStickerAvatarPackKind)
    private sealed interface CatalogSource {
        data object Default : CatalogSource
        data class Installed(val kind: ProfileStickerAvatarPackKind, val query: String) : CatalogSource
        data class Pack(val value: PackRecord) : CatalogSource
    }
    private data class CursorRecord(val source: CatalogSource, val offset: Int, val revision: ByteArray)
    private sealed interface Source {
        data object DefaultEmoji : Source
        data class InstalledPack(val pack: PackRecord) : Source
    }
    private data class CandidateRecord(
        val identity: ProfileStickerAvatarIdentity,
        val nativeStickerId: Long,
        val nativeSetId: Long,
        val width: Int,
        val height: Int,
        val repaint: Boolean,
        val source: Source,
        val media: ProfileStickerAvatarMediaFingerprint?,
        val sourceRevision: ByteArray,
        val mediaAlive: AtomicBoolean = AtomicBoolean(true),
    )
    private class Selection(val ticket: ProfileStickerAvatarSelectionTicket, val candidate: CandidateRecord) {
        val mediaAlive = AtomicBoolean(true)
    }
    private class MediaGrantRecord(
        val owner: Owner, val epoch: Any, val candidate: CandidateRecord, val selection: Selection?,
    )
    private enum class Stage { READING, WRITE, DONE }

    /** Every stage and the operation set are guarded by lock; callback is always called outside. */
    private inner class Operation<T>(
        val owner: Owner,
        val epoch: Any,
        val selection: Selection?,
        val result: (SettingsResult<T>) -> Unit,
        val saveActive: (() -> Boolean)? = null,
        val mediaGrant: ProfileStickerAvatarMediaGrant? = null,
        val mediaTerminal: (() -> Unit)? = null,
        val mediaPermit: ProfileStickerAvatarMediaConsumerPermit? = null,
        val windowTransition: Boolean = false,
    ) {
        var stage = Stage.READING
        var nativeHandlers = 0 // media dispatches whose processing handler has not returned
        var dispatchHandlers = 0 // executor actions, retained through afterExecute
        var consumerFinished = false
        var terminalDelivered = false
        var gateWaiting: ProfileStickerAvatarLargeResponseGate.Waiting? = null

        /** Caller holds the owner lock; this only removes a waiter, never a dispatched permit. */
        private fun cancelGateWaitingLocked() { gateWaiting?.cancel(); gateWaiting = null }

        fun takeTerminalLocked(): (() -> Unit)? {
            if (mediaTerminal == null || terminalDelivered || stage != Stage.DONE ||
                !consumerFinished || nativeHandlers != 0 || dispatchHandlers != 0) return null
            terminalDelivered = true
            return mediaTerminal
        }

        fun consumerExited() {
            val terminal = synchronized(lock) {
                consumerFinished = true
                takeTerminalLocked()
            }
            terminal?.let(::notifyTerminal)
            pumpMedia()
        }

        fun dispatchRejected() = finish(SettingsResult(error = SettingsError.RATE_LIMITED))

        fun dispatchExited() {
            val terminal = synchronized(lock) {
                check(dispatchHandlers > 0)
                dispatchHandlers--
                takeTerminalLocked()
            }
            terminal?.let(::notifyTerminal)
            pumpMedia()
        }

        private fun resultMediaCurrent(): Boolean {
            val grant = mediaGrant ?: return false
            fun capturedCurrent(): Boolean = synchronized(lock) {
                !retired && this@OfficialTdProfileStickerAvatar.owner === owner && owner.epoch === epoch &&
                    (selection == null || owner.selection === selection) && grant.atomicCurrent() &&
                    mediaGrants[grant]?.let { it.owner === owner && it.epoch === epoch } == true
            }
            if (!capturedCurrent() || !runCatching(owner.active).getOrDefault(false) ||
                saveActive?.let { !runCatching(it).getOrDefault(false) } == true) return false
            return capturedCurrent()
        }

        private fun conflictOwnerCurrent(conflictEpoch: Any): Boolean {
            fun capturedCurrent(): Boolean = synchronized(lock) {
                !retired && this@OfficialTdProfileStickerAvatar.owner === owner && owner.epoch === conflictEpoch
            }
            // The source conflict deliberately revoked the grant and selection. Only its error
            // may cross that revocation; a later source/window/owner transition still discards it.
            return capturedCurrent() && runCatching(owner.active).getOrDefault(false) && capturedCurrent()
        }

        private fun deliverResult(value: SettingsResult<T>, conflictEpoch: Any? = null) {
            val permit = mediaPermit
            if (permit == null) {
                try { notify(result, value) } finally { consumerExited() }
            } else {
                submitConsumer(permit, {
                    val sourceConflict = value.error == SettingsError.CONFLICT && conflictEpoch != null &&
                        conflictOwnerCurrent(conflictEpoch)
                    val output = if (sourceConflict || resultMediaCurrent()) value
                        else SettingsResult(error = SettingsError.NOT_READY)
                    notify(result, output)
                }, ::consumerExited)
            }
        }

        fun finish(value: SettingsResult<T>) {
            val deliver = synchronized(lock) {
                if (stage == Stage.DONE) null else {
                    cancelGateWaitingLocked()
                    if (owner.epoch === epoch) { owner.initialCatalogLoad = false; owner.initialCatalogDirty = false }
                    if (windowTransition && owner.windowPending && owner.epoch === epoch) {
                        owner.windowPending = false
                        owner.selection?.mediaAlive?.set(false)
                        owner.selection = null
                    }
                    val output: SettingsResult<T> = if (stage == Stage.WRITE || validLocked(this)) value
                        else SettingsResult(error = SettingsError.NOT_READY)
                    stage = Stage.DONE
                    operations.remove(this)
                    waitingReads.removeIf { it.operation === this }
                    val callback: () -> Unit = { deliverResult(output) }
                    callback
                }
            }
            if (deliver != null) {
                deliver() // enqueue only; foreign work cannot run on this producer stack
                pumpMedia()
            }
        }

        /** Call with lock held. A write admission is irreversible and keeps its native result. */
        fun cancelLocked(): (() -> Unit)? {
            if (stage != Stage.READING) return null
            cancelGateWaitingLocked()
            stage = Stage.DONE
            waitingReads.removeIf { it.operation === this }
            return {
                deliverResult(SettingsResult(error = SettingsError.NOT_READY))
                pumpMedia()
            }
        }

        /** Preserve a source-conflict result while revoking all other operations under the same lock. */
        fun sourceConflictLocked(conflictEpoch: Any): (() -> Unit)? {
            if (stage != Stage.READING) return null
            cancelGateWaitingLocked()
            stage = Stage.DONE
            operations.remove(this)
            waitingReads.removeIf { it.operation === this }
            return { deliverResult(SettingsResult(error = SettingsError.CONFLICT), conflictEpoch); pumpMedia() }
        }

        /** A throwing send has unknown dispatch status; the physical reservation remains held. */
        fun finishReadAfterUncertainThrow(request: NativeRequest) {
            val deliver = synchronized(lock) {
                if (request.completed.get() || stage != Stage.READING) null else {
                    cancelGateWaitingLocked()
                    if (owner.epoch === epoch) { owner.initialCatalogLoad = false; owner.initialCatalogDirty = false }
                    val error = if (validLocked(this)) SettingsError.REQUEST_FAILED else SettingsError.NOT_READY
                    if (windowTransition && owner.windowPending && owner.epoch === epoch) {
                        owner.windowPending = false
                        owner.selection?.mediaAlive?.set(false)
                        owner.selection = null
                    }
                    stage = Stage.DONE
                    operations.remove(this)
                    waitingReads.removeIf { it.operation === this }
                    val callback: () -> Unit = { deliverResult(SettingsResult(error = error)) }
                    callback
                }
            }
            if (deliver != null) {
                deliver()
                pumpMedia()
            }
        }
    }

    private fun submitConsumer(permit: ProfileStickerAvatarMediaConsumerPermit,
        action: () -> Unit, exited: () -> Unit,
    ) {
        val started = AtomicBoolean()
        val ended = AtomicBoolean()
        val run = { if (started.compareAndSet(false, true)) action() }
        val done = { if (started.get() && ended.compareAndSet(false, true)) exited() }
        try { permit.deliver(run, done) }
        catch (_: Throwable) { /* Unknown enqueue: the ticket and consumer remain held. */ }
    }

    private fun submitRead(operation: Operation<*>, request: NativeRequest, notStarted: () -> Unit = {},
        action: () -> Unit,
    ) {
        val started = AtomicBoolean()
        val ended = AtomicBoolean()
        val run = { if (started.compareAndSet(false, true)) action() }
        val done = { if (started.get() && ended.compareAndSet(false, true)) operation.dispatchExited() }
        val refused = {
            if (ended.compareAndSet(false, true)) {
                // A built-in AbortPolicy rejection proves this action never reached send().
                operation.dispatchRejected()
                if (request.completed.compareAndSet(false, true)) {
                    try { releaseNative(request) } finally { mediaHandlerExited(request) }
                }
                operation.dispatchExited()
                notStarted()
            }
        }
        try { mediaAsync.dispatch(run, done, refused) }
        catch (_: Throwable) { /* Unknown enqueue: keep the tag and dispatch reference. */ }
    }

    /** Starting a new picker retires the preceding picker, but not an admitted native write. */
    override fun open(userId: Long, active: () -> Boolean): ProfileStickerAvatarPickerTicket? {
        if (userId <= 0 || !runCatching(active).getOrDefault(false)) return null
        val result = synchronized(lock) {
            if (retired) null else {
                val cancelled = cancelReadsLocked(null)
                owner?.let(::invalidateLocked)
                val ticket = ProfileStickerAvatarPickerTicket()
                owner = Owner(userId, active, ticket)
                ticket to cancelled
            }
        } ?: return null
        result.second.forEach { it() }
        pumpMedia()
        return result.first
    }

    override fun close(ticket: ProfileStickerAvatarPickerTicket) {
        val cancelled = synchronized(lock) {
            val current = owner
            if (current == null || current.ticket !== ticket) emptyList() else {
                invalidateLocked(current)
                owner = null
                cancelReadsLocked(current)
            }
        }
        cancelled.forEach { it() }
        pumpMedia()
    }

    override fun retire() {
        val cancelled = synchronized(lock) {
            if (retired) emptyList() else {
                retired = true
                owner?.let(::invalidateLocked)
                owner = null
                cancelReadsLocked(null)
            }
        }
        cancelled.forEach { it() }
        pumpMedia()
    }

    override fun loadDefault(ticket: ProfileStickerAvatarPickerTicket, result: (SettingsResult<ProfileStickerAvatarCandidatePage>) -> Unit) {
        val pair = beginCatalog(ticket, null, candidateResult(result)) ?: return
        readCatalogWindow(pair.first, CatalogSource.Default, 0, null, false)
    }

    override fun loadInstalledPacks(ticket: ProfileStickerAvatarPickerTicket, kind: ProfileStickerAvatarPackKind,
        result: (SettingsResult<ProfileStickerAvatarPackPage>) -> Unit,
    ) = searchInstalled(ticket, kind, "", result)

    override fun searchInstalled(ticket: ProfileStickerAvatarPickerTicket, kind: ProfileStickerAvatarPackKind,
        query: String, result: (SettingsResult<ProfileStickerAvatarPackPage>) -> Unit,
    ) {
        if (query.length > MAX_CATALOG_QUERY) { notify(result, SettingsResult(error = SettingsError.INVALID_VALUE)); return }
        val pair = beginCatalog(ticket, null, packResult(result)) ?: return
        readCatalogWindow(pair.first, CatalogSource.Installed(kind, query.trim()), 0, null, false)
    }

    override fun openPack(ticket: ProfileStickerAvatarPickerTicket, packTicket: ProfileStickerAvatarPackTicket,
        result: (SettingsResult<ProfileStickerAvatarCandidatePage>) -> Unit,
    ) {
        val pair = beginCatalog(ticket, packTicket, candidateResult(result)) ?: return
        readCatalogWindow(pair.first, CatalogSource.Pack(checkNotNull(pair.second)), 0, null, false)
    }

    override fun loadWindow(ticket: ProfileStickerAvatarPickerTicket, cursor: ProfileStickerAvatarCatalogCursor,
        result: (SettingsResult<ProfileStickerAvatarCatalogWindow>) -> Unit,
    ) {
        val current = findOwner(ticket)
        if (current == null || !ownerCurrent(current)) { notify(result, SettingsResult(error = SettingsError.NOT_READY)); return }
        val began = synchronized(lock) {
            val source = current.cursors[cursor]
            if (retired || owner !== current || source == null || current.windowPending) null else {
                invalidateWindowLocked(current)
                val cancelled = cancelReadsLocked(current)
                if (operations.size >= MAX_OPERATIONS) Triple(null, source, cancelled) else {
                    current.windowPending = true
                    val operation = Operation(current, current.epoch, null, result, windowTransition = true)
                    operations.add(operation)
                    Triple(operation, source, cancelled)
                }
            }
        }
        if (began == null) { notify(result, SettingsResult(error = SettingsError.CONFLICT)); return }
        began.third.forEach { it() }; pumpMedia()
        val operation = began.first
        if (operation == null) {
            synchronized(lock) { current.selection?.mediaAlive?.set(false); current.selection = null }
            notify(result, SettingsResult(error = SettingsError.RATE_LIMITED)); return
        }
        readCatalogWindow(operation, began.second.source, began.second.offset, began.second.revision, true)
    }

    private fun candidateResult(result: (SettingsResult<ProfileStickerAvatarCandidatePage>) -> Unit):
        (SettingsResult<ProfileStickerAvatarCatalogWindow>) -> Unit = { response ->
        val value = (response.value as? ProfileStickerAvatarCatalogWindow.Candidates)?.page
        notify(result, if (value != null && response.error == null) SettingsResult(value)
            else SettingsResult(error = response.error ?: SettingsError.REQUEST_FAILED))
    }

    private fun packResult(result: (SettingsResult<ProfileStickerAvatarPackPage>) -> Unit):
        (SettingsResult<ProfileStickerAvatarCatalogWindow>) -> Unit = { response ->
        val value = (response.value as? ProfileStickerAvatarCatalogWindow.Packs)?.page
        notify(result, if (value != null && response.error == null) SettingsResult(value)
            else SettingsResult(error = response.error ?: SettingsError.REQUEST_FAILED))
    }

    private fun readCatalogWindow(operation: Operation<ProfileStickerAvatarCatalogWindow>, source: CatalogSource,
        offset: Int, expected: ByteArray?, preserving: Boolean,
    ) {
        synchronized(lock) { if (validLocked(operation)) {
            operation.owner.catalogSource = source
            operation.owner.initialCatalogLoad = !preserving && expected == null
            operation.owner.initialCatalogDirty = false
        } }
        fun fail(error: SettingsError) {
            if (preserving) synchronized(lock) {
                if (owner === operation.owner && operation.owner.epoch === operation.epoch) {
                    operation.owner.windowPending = false
                    operation.owner.selection?.mediaAlive?.set(false)
                    operation.owner.selection = null
                }
            }
            operation.finish(SettingsResult(error = error))
        }
        var retries = 0
        lateinit var requestPage: () -> Unit
        fun <T> scan(block: () -> ProfileAvatarScanPage<T>, publish: (ProfileAvatarScanPage<T>) -> Unit) {
            val page = try { block() } catch (_: ProfileAvatarScanStopped) { fail(SettingsError.NOT_READY); return }
                catch (_: ProfileAvatarScanBudget) { fail(SettingsError.RATE_LIMITED); return }
                catch (_: Exception) { fail(SettingsError.REQUEST_FAILED); return }
            if (expected != null && !expected.contentEquals(page.revision)) { sourceRevisionChanged(operation); return }
            if (!current(operation)) { fail(SettingsError.NOT_READY); return }
            // Nothing has been authorized during an initial read. Native updates can be
            // emitted by TDLib itself before its load promise resolves, so confirm again.
            val dirty = synchronized(lock) {
                if (!validLocked(operation)) null
                else if (operation.owner.initialCatalogLoad && operation.owner.initialCatalogDirty) {
                    operation.owner.initialCatalogDirty = false; true
                } else { operation.owner.initialCatalogLoad = false; false }
            }
            if (dirty == true) {
                if (++retries > 2) fail(SettingsError.RATE_LIMITED) else requestPage()
            } else if (dirty == false) publish(page)
        }
        requestPage = {
            when (source) {
                CatalogSource.Default -> requestRead(operation, { TdApi.GetDefaultProfilePhotoCustomEmojiStickers() }, large = true) { response ->
                    if (response !is TdApi.Stickers) { fail(settingsError(response)); return@requestRead }
                    val items = response.stickers
                    if (items == null) { fail(SettingsError.INVALID_VALUE); return@requestRead }
                    if (offset > items.size) { if (preserving) sourceRevisionChanged(operation) else fail(SettingsError.CONFLICT); return@requestRead }
                    if (preserving && !retainedSelectionMatches(operation, items)) return@requestRead
                    scan({ scanProfileAvatarStickers(items, null, null, offset) { current(operation) } }) {
                        publishCandidateWindow(operation, source, it)
                    }
                }
                is CatalogSource.Installed -> requestRead(operation, { TdApi.GetInstalledStickerSets(nativeType(source.kind)) }, large = true) { response ->
                    if (response !is TdApi.StickerSets) { fail(settingsError(response)); return@requestRead }
                    val items = response.sets
                    if (items == null) { fail(SettingsError.INVALID_VALUE); return@requestRead }
                    if (offset > items.size) { if (preserving) sourceRevisionChanged(operation) else fail(SettingsError.CONFLICT); return@requestRead }
                    scan({ scanProfileAvatarPacks(items, source.kind, source.query, offset) { current(operation) } }) {
                        publishPackWindow(operation, source, it)
                    }
                }
                is CatalogSource.Pack -> requestRead(operation, { TdApi.GetStickerSet(source.value.id) }, large = true) { response ->
                    if (response !is TdApi.StickerSet) { fail(settingsError(response)); return@requestRead }
                    if (!validPack(response, source.value)) { if (preserving) sourceRevisionChanged(operation) else fail(SettingsError.CONFLICT); return@requestRead }
                    val items = response.stickers
                    if (items == null) { fail(SettingsError.INVALID_VALUE); return@requestRead }
                    if (offset > items.size) { if (preserving) sourceRevisionChanged(operation) else fail(SettingsError.CONFLICT); return@requestRead }
                    if (preserving && !retainedSelectionMatches(operation, items)) return@requestRead
                    scan({ scanProfileAvatarStickers(items, source.value.kind, source.value.id, offset) { current(operation) } }) {
                        publishCandidateWindow(operation, source, it)
                    }
                }
            }
        }
        readSelf(operation, null, false) { requestPage() }
    }

    private fun cursorsLocked(owner: Owner, source: CatalogSource, revision: ByteArray, previous: Int?, next: Int?):
        Pair<ProfileStickerAvatarCatalogCursor?, ProfileStickerAvatarCatalogCursor?> {
        owner.cursors.clear()
        fun issue(offset: Int?) = offset?.let {
            ProfileStickerAvatarCatalogCursor().also { cursor -> owner.cursors[cursor] = CursorRecord(source, it, revision.copyOf()) }
        }
        return issue(previous) to issue(next)
    }

    private fun publishCandidateWindow(operation: Operation<ProfileStickerAvatarCatalogWindow>, source: CatalogSource,
        page: ProfileAvatarScanPage<ProfileAvatarScanCandidate>,
    ) {
        val value = synchronized(lock) {
            if (!validLocked(operation)) null else {
                val nativeSource = when (source) {
                    CatalogSource.Default -> Source.DefaultEmoji
                    is CatalogSource.Pack -> Source.InstalledPack(source.value)
                    is CatalogSource.Installed -> return@synchronized null
                }
                val sourceRevision = page.revision.copyOf()
                val values = page.values.map { item ->
                    val ticket = ProfileStickerAvatarCandidateTicket()
                    val record = CandidateRecord(item.identity, item.stickerId, item.setId, item.width, item.height,
                        item.repaint, nativeSource, item.media, sourceRevision)
                    operation.owner.candidates[ticket] = record
                    ProfileStickerAvatarCandidate(ticket, record.identity, record.nativeStickerId, record.nativeSetId,
                        record.width, record.height, record.repaint)
                }
                val (previous, next) = cursorsLocked(operation.owner, source, page.revision, page.previous, page.next)
                operation.owner.windowPending = false
                ProfileStickerAvatarCatalogWindow.Candidates(ProfileStickerAvatarCandidatePage(immutable(values), page.conflicts, previous, next))
            }
        }
        operation.finish(if (value == null) SettingsResult(error = SettingsError.NOT_READY) else SettingsResult(value))
    }

    private fun publishPackWindow(operation: Operation<ProfileStickerAvatarCatalogWindow>, source: CatalogSource.Installed,
        page: ProfileAvatarScanPage<ProfileAvatarScanPack>,
    ) {
        val value = synchronized(lock) {
            if (!validLocked(operation)) null else {
                val values = page.values.map { item ->
                    val ticket = ProfileStickerAvatarPackTicket()
                    operation.owner.packs[ticket] = PackRecord(item.id, item.kind)
                    ProfileStickerAvatarPack(ticket, item.title, item.size, item.kind, item.shortName)
                }
                val (previous, next) = cursorsLocked(operation.owner, source, page.revision, page.previous, page.next)
                operation.owner.windowPending = false
                ProfileStickerAvatarCatalogWindow.Packs(ProfileStickerAvatarPackPage(immutable(values), page.conflicts, previous, next))
            }
        }
        operation.finish(if (value == null) SettingsResult(error = SettingsError.NOT_READY) else SettingsResult(value))
    }

    /** A new selection invalidates old selection tickets, including selecting the same candidate again. */
    override fun select(ticket: ProfileStickerAvatarPickerTicket,
        candidateTicket: ProfileStickerAvatarCandidateTicket,
    ): ProfileStickerAvatarSelectionTicket? {
        val current = findOwner(ticket) ?: return null
        if (!ownerCurrent(current)) return null
        val selected = synchronized(lock) {
            val candidate = current.candidates[candidateTicket]
            if (retired || owner !== current || current.windowPending || candidate == null) null else {
                val cancelled = cancelReadsLocked(current, selectionOnly = true)
                current.selection?.mediaAlive?.set(false)
                revokeMediaGrantsLocked(current, selectionOnly = true)
                val result = ProfileStickerAvatarSelectionTicket()
                current.selection = Selection(result, candidate.copy(mediaAlive = AtomicBoolean(true)))
                result to cancelled
            }
        } ?: return null
        selected.second.forEach { it() }
        pumpMedia()
        return selected.first
    }

    /** Internal media capabilities do not extend the public metadata/markup Session API. */
    internal fun thumbnailMedia(ticket: ProfileStickerAvatarPickerTicket,
        candidateTicket: ProfileStickerAvatarCandidateTicket,
    ): ProfileStickerAvatarMediaGrant? = issueMedia(ticket, candidateTicket, null, ProfileStickerAvatarMediaRole.THUMBNAIL)

    internal fun fullMedia(ticket: ProfileStickerAvatarPickerTicket,
        selectionTicket: ProfileStickerAvatarSelectionTicket,
    ): ProfileStickerAvatarMediaGrant? = issueMedia(ticket, null, selectionTicket, ProfileStickerAvatarMediaRole.FULL)

    private fun issueMedia(ticket: ProfileStickerAvatarPickerTicket, candidateTicket: ProfileStickerAvatarCandidateTicket?,
        selectionTicket: ProfileStickerAvatarSelectionTicket?, role: ProfileStickerAvatarMediaRole,
    ): ProfileStickerAvatarMediaGrant? {
        val current = findOwner(ticket) ?: return null
        if (!ownerCurrent(current)) return null
        return synchronized(lock) {
            if (retired || owner !== current || current.windowPending || mediaGrants.size >= MAX_MEDIA_GRANTS) return@synchronized null
            val selected = if (role == ProfileStickerAvatarMediaRole.FULL)
                current.selection?.takeIf { it.ticket === selectionTicket } else null
            val candidate = if (role == ProfileStickerAvatarMediaRole.FULL) selected?.candidate
                else candidateTicket?.let { current.candidates[it] }
            if (candidate == null || role == ProfileStickerAvatarMediaRole.FULL && selected == null) return@synchronized null
            val media = candidate.media ?: return@synchronized null
            if (role == ProfileStickerAvatarMediaRole.THUMBNAIL &&
                (media.thumbnail == null || profileStickerAvatarSameFile(media.thumbnail.file, media.full.file))) return@synchronized null
            val grant = ProfileStickerAvatarMediaGrant(role, current.mediaEpoch, candidate.mediaAlive, selected?.mediaAlive)
            mediaGrants[grant] = MediaGrantRecord(current, current.epoch, candidate, selected)
            grant
        }
    }

    /** The legacy callback contract includes a synchronous, pre-admission BUSY rejection. */
    internal fun resolveMedia(grant: ProfileStickerAvatarMediaGrant, active: () -> Boolean,
        result: (SettingsResult<ProfileStickerAvatarOwnedMedia>) -> Unit,
    ) {
        if (resolveMediaOwned(grant, active, {}, result) == ProfileStickerAvatarMediaAdmission.BUSY)
            notify(result, SettingsResult(error = SettingsError.RATE_LIMITED))
    }

    private fun rejectMedia(admission: MediaAdmission, error: SettingsError) {
        submitConsumer(admission.permit, { notify(admission.result, SettingsResult(error = error)) },
            { notifyTerminal(admission.terminal) })
    }

    /** Terminal follows consumer, every actual native handler, and every dispatch afterExecute. */
    internal fun resolveMediaOwned(grant: ProfileStickerAvatarMediaGrant, active: () -> Boolean,
        terminal: () -> Unit, result: (SettingsResult<ProfileStickerAvatarOwnedMedia>) -> Unit,
    ): ProfileStickerAvatarMediaAdmission {
        val permit = mediaAsync.reserveConsumer() ?: return ProfileStickerAvatarMediaAdmission.BUSY
        val record = synchronized(lock) { mediaGrants[grant] }
        if (record == null || !grant.atomicCurrent() || !ownerCurrent(record.owner)) {
            rejectMedia(MediaAdmission(grant, active, result, terminal, permit, 0), SettingsError.NOT_READY)
            return ProfileStickerAvatarMediaAdmission.ACCEPTED
        }
        var operation: Operation<ProfileStickerAvatarOwnedMedia>? = null
        var initial: NativeRequest? = null
        var queued = false
        var busy = false
        synchronized(lock) {
            if (!retired && owner === record.owner && record.owner.epoch === record.epoch &&
                mediaGrants[grant] === record && grant.atomicCurrent()) {
                if (operations.size < MAX_MEDIA_CONCURRENT && nativeInFlight.size < MAX_MEDIA_CONCURRENT &&
                    waitingMedia.isEmpty() && waitingReads.isEmpty()) {
                    val admitted = Operation(record.owner, record.epoch, record.selection, result, active, grant, terminal, permit)
                    operations.add(admitted)
                    initial = checkNotNull(reserveNativeLocked(admitted))
                    admitted.dispatchHandlers++
                    operation = admitted
                } else if (waitingMedia.size < MAX_MEDIA_WAITING) {
                    waitingMedia.addLast(MediaAdmission(grant, active, result, terminal, permit, nextMediaOrderLocked()))
                    queued = true
                } else busy = true
            }
        }
        val admitted = operation
        val request = initial
        if (admitted != null && request != null) submitRead(admitted, request) { startMedia(admitted, record, request) }
        else if (queued) pumpMedia()
        else rejectMedia(MediaAdmission(grant, active, result, terminal, permit, 0),
            if (busy) SettingsError.RATE_LIMITED else SettingsError.NOT_READY)
        return ProfileStickerAvatarMediaAdmission.ACCEPTED
    }

    private fun startMedia(operation: Operation<ProfileStickerAvatarOwnedMedia>, record: MediaGrantRecord,
        initial: NativeRequest,
    ) {
        readSelf(operation, null, false, initial) {
            when (val source = record.candidate.source) {
                Source.DefaultEmoji -> requestRead(operation, { TdApi.GetDefaultProfilePhotoCustomEmojiStickers() }, large = true) { response ->
                    if (response !is TdApi.Stickers) { operation.finish(nativeError(response)); return@requestRead }
                    val stickers = response.stickers
                    if (stickers == null) {
                        operation.finish(SettingsResult(error = SettingsError.INVALID_VALUE)); return@requestRead
                    }
                    if (!unchangedStickerSource(operation, stickers, record.candidate.sourceRevision)) return@requestRead
                    val id = (record.candidate.identity as ProfileStickerAvatarIdentity.CustomEmoji).customEmojiId
                    val found = uniqueSticker(operation, stickers) { (it.fullType as? TdApi.StickerFullTypeCustomEmoji)?.customEmojiId == id }
                    finishMedia(operation, record.candidate, found)
                }
                is Source.InstalledPack -> requestRead(operation, { TdApi.GetStickerSet(source.pack.id) }, large = true) { response ->
                    if (response !is TdApi.StickerSet) { operation.finish(nativeError(response)); return@requestRead }
                    val stickers = response.stickers
                    if (!validPack(response, source.pack)) {
                        sourceRevisionChanged(operation); return@requestRead
                    }
                    if (stickers == null) {
                        operation.finish(SettingsResult(error = SettingsError.INVALID_VALUE)); return@requestRead
                    }
                    if (!unchangedStickerSource(operation, stickers, record.candidate.sourceRevision)) return@requestRead
                    val found = when (val identity = record.candidate.identity) {
                        is ProfileStickerAvatarIdentity.CustomEmoji -> uniqueSticker(operation, stickers) {
                            (it.fullType as? TdApi.StickerFullTypeCustomEmoji)?.customEmojiId == identity.customEmojiId
                        }
                        is ProfileStickerAvatarIdentity.RegularOrMask -> uniqueSticker(operation, stickers) { it.id == identity.stickerId }
                    }
                    if (!matchesSticker(found, source.pack)) {
                        operation.finish(SettingsResult(error = SettingsError.CONFLICT)); return@requestRead
                    }
                    finishMedia(operation, record.candidate, found)
                }
            }
        }
    }

    private fun finishMedia(operation: Operation<ProfileStickerAvatarOwnedMedia>, candidate: CandidateRecord,
        sticker: TdApi.Sticker?,
    ) {
        val captured = candidate.media
        val actual = sticker?.let { runCatching { profileStickerAvatarFingerprint(it) }.getOrNull() }
        val grant = operation.mediaGrant
        if (sticker == null || captured == null || actual == null || grant == null ||
            candidate.nativeStickerId != 0L && sticker.id != 0L && candidate.nativeStickerId != sticker.id ||
            candidate.nativeSetId != 0L && sticker.setId != 0L && candidate.nativeSetId != sticker.setId) {
            operation.finish(SettingsResult(error = SettingsError.CONFLICT)); return
        }
        if (!sameCandidateFingerprint(candidate, sticker)) { sourceRevisionChanged(operation); return }
        val media = resolveProfileStickerAvatarMedia(captured, actual, grant.role)
        operation.finish(if (media == null) SettingsResult(error = SettingsError.CONFLICT) else SettingsResult(media))
    }

    internal fun closeMedia(grant: ProfileStickerAvatarMediaGrant) {
        val callbacks = synchronized(lock) {
            mediaGrants.remove(grant)
            grant.revoke()
            val cancelled = ArrayList<() -> Unit>()
            val iterator = operations.iterator()
            while (iterator.hasNext()) {
                val operation = iterator.next()
                if (operation.mediaGrant === grant) {
                    val callback = operation.cancelLocked()
                    if (callback != null) { iterator.remove(); cancelled += callback }
                }
            }
            cancelled
        }
        callbacks.forEach { it() }
        pumpMedia()
    }

    fun save(ticket: ProfileStickerAvatarPickerTicket, selectionTicket: ProfileStickerAvatarSelectionTicket,
        expectedPhotoId: Long?, background: ProfileStickerAvatarBackground, result: (SettingsResult<Unit>) -> Unit,
    ) = save(ticket, selectionTicket, expectedPhotoId, background, { true }, result)

    /** Save admission uses a separate atomic lease, independent of the picker lifetime. */
    override fun save(ticket: ProfileStickerAvatarPickerTicket, selectionTicket: ProfileStickerAvatarSelectionTicket,
        expectedPhotoId: Long?, background: ProfileStickerAvatarBackground, active: () -> Boolean,
        result: (SettingsResult<Unit>) -> Unit,
    ) {
        if (expectedPhotoId == 0L) { notify(result, SettingsResult(error = SettingsError.INVALID_VALUE)); return }
        val current = findOwner(ticket)
        if (current == null || !ownerCurrent(current)) { notify(result, SettingsResult(error = SettingsError.NOT_READY)); return }
        val admission: Pair<Operation<Unit>?, SettingsError?> = synchronized(lock) {
            val selection = current.selection
            when {
                retired || owner !== current -> null to SettingsError.NOT_READY
                current.windowPending -> null to SettingsError.RATE_LIMITED
                selection == null || selection.ticket !== selectionTicket -> null to SettingsError.CONFLICT
                operations.size >= MAX_OPERATIONS -> null to SettingsError.NOT_READY
                else -> Operation(current, current.epoch, selection, result, active).also { operations.add(it) } to null
            }
        }
        val operation = admission.first
        if (operation == null) { notify(result, SettingsResult(error = admission.second)); return }
        readSelf(operation, expectedPhotoId, true) {
            revalidateSelection(operation) {
                // A source reread is another native interval. Re-read the self photo immediately before admission.
                readSelf(operation, expectedPhotoId, true) {
                    val identity = checkNotNull(operation.selection).candidate.identity
                    val nativeSticker = TdApi.ChatPhotoSticker(nativeIdentity(identity), nativeBackground(background))
                    write(operation, TdApi.SetProfilePhoto(TdApi.InputChatPhotoSticker(nativeSticker), false))
                }
            }
        }
    }

    private fun <T> beginCatalog(ticket: ProfileStickerAvatarPickerTicket, packTicket: ProfileStickerAvatarPackTicket?,
        result: (SettingsResult<T>) -> Unit,
    ): Pair<Operation<T>, PackRecord?>? {
        val current = findOwner(ticket)
        if (current == null || !ownerCurrent(current)) { notify(result, SettingsResult(error = SettingsError.NOT_READY)); return null }
        val began: Triple<Operation<T>?, PackRecord?, List<() -> Unit>>? = synchronized(lock) {
            val pack = if (packTicket == null) null else current.packs[packTicket]
            if (retired || owner !== current || packTicket != null && pack == null) null else {
                invalidateLocked(current)
                val cancelled = cancelReadsLocked(current)
                if (operations.size >= MAX_OPERATIONS) Triple(null, pack, cancelled) else {
                    val operation = Operation(current, current.epoch, null, result)
                    operations.add(operation)
                    Triple(operation, pack, cancelled)
                }
            }
        }
        if (began == null) { notify(result, SettingsResult(error = SettingsError.CONFLICT)); return null }
        began.third.forEach { it() }
        pumpMedia()
        val operation = began.first
        if (operation == null) { notify(result, SettingsResult(error = SettingsError.NOT_READY)); return null }
        return operation to began.second
    }

    private fun <T> readSelf(operation: Operation<T>, expectedPhotoId: Long?, comparePhoto: Boolean,
        initial: NativeRequest? = null, next: () -> Unit,
    ) {
        val continuation: (TdApi.Object) -> Unit = { response ->
            when {
                response !is TdApi.User -> operation.finish(nativeError(response))
                response.id != operation.owner.userId -> operation.finish(SettingsResult(error = SettingsError.NOT_READY))
                comparePhoto && response.profilePhoto?.id?.takeIf { it != 0L } != expectedPhotoId ->
                    operation.finish(SettingsResult(error = SettingsError.CONFLICT))
                else -> next()
            }
        }
        if (initial == null) requestRead(operation, { TdApi.GetMe() }, continuation = continuation)
        else dispatchRead(operation, { TdApi.GetMe() }, continuation, initial)
    }

    private fun revalidateSelection(operation: Operation<Unit>, next: () -> Unit) {
        val candidate = checkNotNull(operation.selection).candidate
        when (val source = candidate.source) {
            Source.DefaultEmoji -> requestRead(operation, { TdApi.GetDefaultProfilePhotoCustomEmojiStickers() }, large = true) { response ->
                if (response !is TdApi.Stickers) { operation.finish(nativeError(response)); return@requestRead }
                val stickers = response.stickers
                if (stickers == null) {
                    operation.finish(SettingsResult(error = SettingsError.INVALID_VALUE)); return@requestRead
                }
                if (!unchangedStickerSource(operation, stickers, candidate.sourceRevision)) return@requestRead
                val id = (candidate.identity as ProfileStickerAvatarIdentity.CustomEmoji).customEmojiId
                val matched = uniqueSticker(operation, stickers) { (it.fullType as? TdApi.StickerFullTypeCustomEmoji)?.customEmojiId == id }
                if (matched == null || !sameCandidateFingerprint(candidate, matched)) sourceRevisionChanged(operation) else next()
            }
            is Source.InstalledPack -> requestRead(operation, { TdApi.GetStickerSet(source.pack.id) }, large = true) { response ->
                if (response !is TdApi.StickerSet) { operation.finish(nativeError(response)); return@requestRead }
                val stickers = response.stickers
                if (!validPack(response, source.pack)) { sourceRevisionChanged(operation); return@requestRead }
                if (stickers == null) {
                    operation.finish(SettingsResult(error = SettingsError.INVALID_VALUE)); return@requestRead
                }
                if (!unchangedStickerSource(operation, stickers, candidate.sourceRevision)) return@requestRead
                val matched = when (val identity = candidate.identity) {
                    is ProfileStickerAvatarIdentity.CustomEmoji -> {
                        val found = uniqueSticker(operation, stickers) {
                            (it.fullType as? TdApi.StickerFullTypeCustomEmoji)?.customEmojiId == identity.customEmojiId
                        }
                        found?.takeIf { source.pack.kind == ProfileStickerAvatarPackKind.CUSTOM_EMOJI && matchesSticker(it, source.pack) }
                    }
                    is ProfileStickerAvatarIdentity.RegularOrMask ->
                        if (source.pack.kind == identity.kind && source.pack.id == identity.stickerSetId)
                            uniqueSticker(operation, stickers) { matchesSticker(it, source.pack) && it.id == identity.stickerId } else null
                }
                if (matched == null || !sameCandidateFingerprint(candidate, matched)) sourceRevisionChanged(operation) else next()
            }
        }
    }

    /** File size 0 is unknown; only two unequal known sizes identify a conflict. */
    private fun sameCandidateFingerprint(candidate: CandidateRecord, sticker: TdApi.Sticker): Boolean {
        val captured = candidate.media
        val actual = runCatching { profileStickerAvatarFingerprint(sticker) }.getOrNull()
        if (captured == null || actual == null) return captured == null && actual == null
        if (captured.repaint != actual.repaint || !sameProfileStickerAvatarAsset(captured.full, actual.full)) return false
        val oldThumb = captured.thumbnail; val newThumb = actual.thumbnail
        return if (oldThumb == null || newThumb == null) oldThumb == null && newThumb == null
            else sameProfileStickerAvatarAsset(oldThumb, newThumb)
    }

    private fun <T> retainedSelectionMatches(operation: Operation<T>, stickers: Array<out TdApi.Sticker?>): Boolean {
        val candidate = synchronized(lock) { if (validLocked(operation)) operation.owner.selection?.candidate else null } ?: return true
        val found = when (val identity = candidate.identity) {
            is ProfileStickerAvatarIdentity.CustomEmoji -> uniqueSticker(operation, stickers) {
                (it.fullType as? TdApi.StickerFullTypeCustomEmoji)?.customEmojiId == identity.customEmojiId
            }
            is ProfileStickerAvatarIdentity.RegularOrMask -> uniqueSticker(operation, stickers) {
                it.id == identity.stickerId && (candidate.source as? Source.InstalledPack)?.let { pack -> matchesSticker(it, pack.pack) } == true
            }
        }
        val valid = found != null && when (val source = candidate.source) {
            Source.DefaultEmoji -> true
            is Source.InstalledPack -> matchesSticker(found, source.pack)
        } && sameCandidateFingerprint(candidate, found)
        if (!valid) sourceRevisionChanged(operation)
        return valid
    }

    private fun <T> unchangedStickerSource(operation: Operation<T>, stickers: Array<out TdApi.Sticker?>,
        expected: ByteArray,
    ): Boolean {
        val actual = try { profileAvatarStickerRevision(stickers) { current(operation) } }
        catch (_: ProfileAvatarScanStopped) { operation.finish(SettingsResult(error = SettingsError.NOT_READY)); return false }
        catch (_: ProfileAvatarScanBudget) { operation.finish(SettingsResult(error = SettingsError.RATE_LIMITED)); return false }
        catch (_: Exception) { operation.finish(SettingsResult(error = SettingsError.REQUEST_FAILED)); return false }
        if (!current(operation)) { operation.finish(SettingsResult(error = SettingsError.NOT_READY)); return false }
        if (!expected.contentEquals(actual)) { sourceRevisionChanged(operation); return false }
        return true
    }

    private fun sourceRevisionChanged(operation: Operation<*>) {
        val affected = synchronized(lock) {
            val current = owner ?: return@synchronized null
            if (current !== operation.owner || !validLocked(operation)) null else {
                invalidateLocked(current)
                val conflict = operation.sourceConflictLocked(current.epoch)
                val revision = current.ticket.markCatalogInvalidated()
                Triple(current.ticket, revision, listOfNotNull(conflict) + cancelReadsLocked(current))
            }
        }
        if (affected == null) return
        affected.third.forEach { it() }
        affected.first.notifyCatalogInvalidated(affected.second)
        pumpMedia()
    }

    /** Do not retain or map the native vector; stop promptly on revoked ownership or ambiguity. */
    private inline fun <T> uniqueSticker(operation: Operation<T>, stickers: Array<out TdApi.Sticker?>,
        predicate: (TdApi.Sticker) -> Boolean,
    ): TdApi.Sticker? {
        var found: TdApi.Sticker? = null
        for (index in stickers.indices) {
            if (index % 256 == 0 && !current(operation)) {
                operation.finish(SettingsResult(error = SettingsError.NOT_READY)); return null
            }
            val sticker = stickers[index] ?: continue
            if (!predicate(sticker)) continue
            if (found != null) return null
            found = sticker
        }
        return found
    }

    private fun <T> requestRead(operation: Operation<T>, command: () -> TdApi.Function<*>, large: Boolean = false,
        continuation: (TdApi.Object) -> Unit,
    ) {
        if (large && receiverOrder != null) {
            val preferred = operation.selection != null || operation.mediaGrant?.role == ProfileStickerAvatarMediaRole.FULL
            val waiting = SharedProfileStickerAvatarLargeResponseGate.gate.enqueue(preferred) { permit ->
                synchronized(lock) { operation.gateWaiting = null }
                if (!current(operation)) {
                    operation.finish(SettingsResult(error = SettingsError.NOT_READY))
                    permit.complete()
                } else requestReadAdmitted(operation, command, continuation, permit)
            }
            if (waiting == null) operation.finish(SettingsResult(error = SettingsError.RATE_LIMITED))
            else {
                val stale = synchronized(lock) {
                    if (validLocked(operation)) {
                        // A synchronous fake can already have entered a later request.
                        if (operation.gateWaiting == null && waiting.pending()) operation.gateWaiting = waiting
                        false
                    } else true
                }
                if (stale) waiting.cancel()
            }
        } else requestReadAdmitted(operation, command, continuation, null)
    }

    private fun <T> requestReadAdmitted(operation: Operation<T>, command: () -> TdApi.Function<*>,
        continuation: (TdApi.Object) -> Unit, permit: ProfileStickerAvatarLargeResponseGate.Permit?,
    ) {
        if (!current(operation)) {
            operation.finish(SettingsResult(error = SettingsError.NOT_READY)); permit?.complete(); return
        }
        var request: NativeRequest? = null
        var queued = false
        synchronized(lock) {
            if (validLocked(operation)) {
                val media = operation.mediaGrant != null
                val admissionCanCompete = operations.size < MAX_MEDIA_CONCURRENT && waitingMedia.isNotEmpty()
                if (!media || waitingReads.isEmpty() && !admissionCanCompete) request = reserveNativeLocked(operation)
                if (request != null && media) operation.dispatchHandlers++
                if (request == null && media && permit == null && waitingReads.size < MAX_MEDIA_WAITING) {
                    waitingReads.addLast(PendingRead(operation, nextMediaOrderLocked()) {
                        dispatchRead(operation, command, continuation, it)
                    })
                    queued = true
                }
            }
        }
        val native = request
        if (native != null) {
            if (operation.mediaGrant == null) dispatchRead(operation, command, continuation, native, permit)
            else submitRead(operation, native, notStarted = { permit?.complete() }) {
                dispatchRead(operation, command, continuation, native, permit)
            }
        } else if (queued) pumpMedia()
        else {
            operation.finish(SettingsResult(error = if (permit != null) SettingsError.RATE_LIMITED else SettingsError.NOT_READY))
            permit?.complete()
        }
    }

    private fun <T> dispatchRead(operation: Operation<T>, command: () -> TdApi.Function<*>,
        continuation: (TdApi.Object) -> Unit, request: NativeRequest,
        permit: ProfileStickerAvatarLargeResponseGate.Permit? = null,
    ) {
        if (!current(operation)) {
            operation.finish(SettingsResult(error = SettingsError.NOT_READY))
            if (request.completed.compareAndSet(false, true)) {
                try { try { releaseNative(request) } finally { pumpMedia() } }
                finally { mediaHandlerExited(request) }
            }
            permit?.complete()
            return
        }
        try {
            send(command(), { response ->
                if (request.completed.compareAndSet(false, true)) {
                    if (permit == null) try {
                        try { handleReadResponse(operation, request, response, continuation) }
                        finally { pumpMedia() }
                    } finally { mediaHandlerExited(request) }
                    else processLargeResponse(operation, request, response, continuation, permit)
                }
            }, {
                if (request.completed.compareAndSet(false, true)) try {
                    try { handleReadFailure(operation, request) }
                    finally { pumpMedia() }
                } finally { mediaHandlerExited(request); permit?.complete() }
            })
        } catch (_: Throwable) {
            // The producer might have sent or already called back. Keep its physical tag until callback.
            operation.finishReadAfterUncertainThrow(request)
        }
    }

    /** The holder is cleared before worker exit; an unknown executor throw retains admission. */
    private fun <T> processLargeResponse(operation: Operation<T>, request: NativeRequest, response: TdApi.Object,
        continuation: (TdApi.Object) -> Unit, permit: ProfileStickerAvatarLargeResponseGate.Permit,
    ) {
        val exit = ProfileStickerAvatarLargeResponseExit(permit)
        val payload = AtomicReference<TdApi.Object?>(response)
        val started = AtomicBoolean()
        val ended = AtomicBoolean()
        val run = {
            if (started.compareAndSet(false, true)) payload.getAndSet(null)?.let {
                handleReadResponse(operation, request, it, continuation)
            }
        }
        val done = {
            if (started.get() && ended.compareAndSet(false, true)) {
                try { pumpMedia() } finally { mediaHandlerExited(request); exit.workerExited() }
            }
        }
        val rejected = {
            if (!started.get() && ended.compareAndSet(false, true)) {
                payload.set(null)
                try {
                    releaseNative(request)
                    operation.finish(SettingsResult(error = SettingsError.RATE_LIMITED))
                } finally { pumpMedia(); mediaHandlerExited(request); exit.workerExited() }
            }
        }
        try { mediaAsync.dispatch(run, done, rejected) }
        catch (_: Throwable) { operation.finish(SettingsResult(error = SettingsError.REQUEST_FAILED)) }
        finally { exit.callbackBodyDone() }
    }

    /** Return from the response-processing stack before reporting a media terminal. */
    private fun <T> handleReadResponse(operation: Operation<T>, request: NativeRequest,
        response: TdApi.Object, continuation: (TdApi.Object) -> Unit,
    ) {
        try {
            releaseNative(request)
            if (!current(operation)) operation.finish(SettingsResult(error = SettingsError.NOT_READY))
            else continuation(response)
        } catch (_: Throwable) { operation.finish(SettingsResult(error = SettingsError.REQUEST_FAILED)) }
    }

    private fun <T> handleReadFailure(operation: Operation<T>, request: NativeRequest) {
        try { releaseNative(request); operation.finish(SettingsResult(error = SettingsError.NOT_READY)) }
        catch (_: Throwable) { operation.finish(SettingsResult(error = SettingsError.REQUEST_FAILED)) }
    }

    private fun write(operation: Operation<Unit>, command: TdApi.SetProfilePhoto) {
        if (!current(operation)) { operation.finish(SettingsResult(error = SettingsError.NOT_READY)); return }
        // Atomic dispatch admission: actor must serialize its own current lease and mutation gate around this boundary.
        val request = synchronized(lock) {
            if (!validLocked(operation)) null else reserveNativeLocked()?.also { operation.stage = Stage.WRITE }
        }
        if (request == null) { operation.finish(SettingsResult(error = SettingsError.NOT_READY)); return }
        try {
            SettingsSpecialMutationContext.write(command, send, { response ->
                if (SettingsSpecialMutationContext.receipt()?.outcome != SettingsSpecialWriteOutcome.INDETERMINATE &&
                    completeNative(request)) operation.finish(
                    if (response is TdApi.Ok) SettingsResult(Unit) else nativeError(response))
            }, {
                // Unknown dispatch retains both the native reservation and the parent upload slot.
                if (SettingsSpecialMutationContext.receipt()?.outcome != SettingsSpecialWriteOutcome.INDETERMINATE &&
                    completeNative(request)) operation.finish(SettingsResult(error = SettingsError.NOT_READY))
            })
        } catch (_: Exception) {
            // Dispatch status is unknown. Keep WRITE and its reservation for a possible real callback.
        }
    }

    /** A reservation is global to this helper, including across owner replacement and retirement. */
    private fun reserveNativeLocked(operation: Operation<*>? = null): NativeRequest? {
        if (nativeInFlight.size >= MAX_NATIVE_IN_FLIGHT) return null
        // Preserve one operation and native interval for catalog / the single admitted save.
        if (operation?.mediaGrant != null && nativeInFlight.size >= MAX_MEDIA_CONCURRENT) return null
        val media = operation?.takeIf { it.mediaTerminal != null }
        if (media != null) media.nativeHandlers++
        return NativeRequest(media).also { nativeInFlight[it] = Unit }
    }

    private fun releaseNative(request: NativeRequest) {
        synchronized(lock) { check(nativeInFlight.remove(request) != null) }
    }

    /** The first actual native completion alone frees exactly this reservation. */
    private fun completeNative(request: NativeRequest): Boolean {
        if (!request.completed.compareAndSet(false, true)) return false
        releaseNative(request)
        pumpMedia()
        return true
    }

    private fun mediaHandlerExited(request: NativeRequest) {
        val terminal = synchronized(lock) {
            val operation = request.mediaOperation ?: return
            check(operation.nativeHandlers > 0)
            operation.nativeHandlers--
            operation.takeTerminalLocked()
        }
        terminal?.let(::notifyTerminal)
    }

    private fun notifyTerminal(terminal: () -> Unit) { try { terminal() } catch (_: Throwable) {} }

    /** One reservation order spans both gates; send execution on multiple workers may reorder. */
    private fun pumpMedia() {
        while (true) {
            val action: (() -> Unit)? = synchronized(lock) {
                val invalid = waitingMedia.firstOrNull {
                    val record = mediaGrants[it.grant]
                    retired || record == null || owner !== record.owner || record.owner.epoch !== record.epoch ||
                        !it.grant.atomicCurrent()
                }
                if (invalid != null) {
                    waitingMedia.remove(invalid)
                    ({ rejectMedia(invalid, SettingsError.NOT_READY) })
                } else if (nativeInFlight.size < MAX_MEDIA_CONCURRENT) {
                    val read = waitingReads.minWithOrNull(compareBy<PendingRead>(
                        { if (it.operation.mediaGrant?.role == ProfileStickerAvatarMediaRole.FULL) 0 else 1 },
                        { it.order },
                    ))
                    val admission = if (operations.size < MAX_MEDIA_CONCURRENT)
                        waitingMedia.minWithOrNull(compareBy<MediaAdmission>(
                            { if (it.grant.role == ProfileStickerAvatarMediaRole.FULL) 0 else 1 }, { it.order },
                        )) else null
                    val readFirst = read != null && (admission == null ||
                        (read.operation.mediaGrant?.role == ProfileStickerAvatarMediaRole.FULL &&
                            admission.grant.role != ProfileStickerAvatarMediaRole.FULL) ||
                        (read.operation.mediaGrant?.role == admission.grant.role && read.order < admission.order))
                    if (readFirst) {
                        val pending = checkNotNull(read)
                        check(waitingReads.remove(pending))
                        val request = checkNotNull(reserveNativeLocked(pending.operation))
                        pending.operation.dispatchHandlers++
                        ({ submitRead(pending.operation, request) { pending.dispatch(request) } })
                    } else if (admission != null) {
                        check(waitingMedia.remove(admission))
                        val record = checkNotNull(mediaGrants[admission.grant])
                        val operation = Operation(record.owner, record.epoch, record.selection, admission.result,
                            admission.active, admission.grant, admission.terminal, admission.permit)
                        operations.add(operation)
                        val request = checkNotNull(reserveNativeLocked(operation))
                        operation.dispatchHandlers++
                        ({ submitRead(operation, request) { startMedia(operation, record, request) } })
                    } else null
                } else null
            }
            if (action == null) return
            action()
        }
    }

    private fun findOwner(ticket: ProfileStickerAvatarPickerTicket): Owner? = synchronized(lock) {
        owner?.takeIf { !retired && it.ticket === ticket }
    }

    /** The injected lease callback is foreign code and must never run under lock. */
    private fun ownerCurrent(value: Owner): Boolean {
        if (!runCatching(value.active).getOrDefault(false)) { close(value.ticket); return false }
        return synchronized(lock) { !retired && owner === value }
    }

    private fun current(operation: Operation<*>): Boolean {
        if (!synchronized(lock) { validLocked(operation) }) return false
        if (!ownerCurrent(operation.owner)) return false
        val saveActive = operation.saveActive
        if (saveActive != null && !runCatching(saveActive).getOrDefault(false)) {
            val cancelled = synchronized(lock) {
                operation.cancelLocked()?.also { operations.remove(operation) }
            }
            cancelled?.invoke()
            return false
        }
        return synchronized(lock) { validLocked(operation) }
    }

    private fun validLocked(operation: Operation<*>): Boolean = !retired && owner === operation.owner &&
        operation.stage == Stage.READING && operation.owner.epoch === operation.epoch &&
        (operation.selection == null || operation.owner.selection === operation.selection) &&
        (operation.mediaGrant == null || operation.mediaGrant.atomicCurrent() && mediaGrants.containsKey(operation.mediaGrant))

    private fun invalidateLocked(value: Owner) {
        invalidateWindowLocked(value)
        value.catalogSource = null
        value.initialCatalogLoad = false
        value.initialCatalogDirty = false
        value.selection?.mediaAlive?.set(false)
        value.selection = null
    }

    /** Retire only window-issued grants; the separately copied selection can be revalidated. */
    private fun invalidateWindowLocked(value: Owner) {
        value.mediaEpoch.set(false)
        value.candidates.values.forEach { it.mediaAlive.set(false) }
        revokeMediaGrantsLocked(value, selectionOnly = false)
        value.epoch = Any()
        value.mediaEpoch = AtomicBoolean(true)
        value.windowPending = false
        value.cursors.clear()
        value.packs.clear()
        value.candidates.clear()
    }

    /** A native source update revokes every old window/selection before notifying its UI owner. */
    internal fun acceptCatalogUpdate(update: TdApi.Object) {
        val affected = synchronized(lock) {
            val current = owner ?: return
            val source = current.catalogSource ?: return
            val changed = when (update) {
                is TdApi.UpdateInstalledStickerSets -> when (source) {
                    CatalogSource.Default -> matches(update.stickerType, ProfileStickerAvatarPackKind.CUSTOM_EMOJI)
                    is CatalogSource.Installed -> matches(update.stickerType, source.kind)
                    is CatalogSource.Pack -> matches(update.stickerType, source.value.kind)
                }
                is TdApi.UpdateStickerSet -> when (source) {
                    CatalogSource.Default -> true
                    is CatalogSource.Installed -> matches(update.stickerSet?.stickerType, source.kind)
                    is CatalogSource.Pack -> update.stickerSet?.id == source.value.id
                }
                else -> false
            }
            if (!changed) null else if (current.initialCatalogLoad) {
                // A native update can precede the initial response. No authority exists yet;
                // the bounded initial read repeats before it can publish any capability.
                current.initialCatalogDirty = true
                null
            } else {
                invalidateLocked(current)
                Triple(current.ticket, current.ticket.markCatalogInvalidated(), cancelReadsLocked(current))
            }
        } ?: return
        affected.third.forEach { it() }
        affected.first.notifyCatalogInvalidated(affected.second)
        pumpMedia()
    }

    /** Called only under lock; associated pending callbacks are cancelled by the existing operation pass. */
    private fun revokeMediaGrantsLocked(target: Owner, selectionOnly: Boolean) {
        val iterator = mediaGrants.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.owner === target && (!selectionOnly || entry.value.selection != null)) {
                entry.key.revoke()
                iterator.remove()
            }
        }
    }

    private fun cancelReadsLocked(target: Owner?, selectionOnly: Boolean = false): List<() -> Unit> {
        val callbacks = ArrayList<() -> Unit>()
        val iterator = operations.iterator()
        while (iterator.hasNext()) {
            val operation = iterator.next()
            if ((target == null || operation.owner === target) && (!selectionOnly || operation.selection != null)) {
                val callback = operation.cancelLocked()
                if (callback != null) { iterator.remove(); callbacks += callback }
            }
        }
        return callbacks
    }

    private fun nativeIdentity(identity: ProfileStickerAvatarIdentity): TdApi.ChatPhotoStickerType = when (identity) {
        is ProfileStickerAvatarIdentity.CustomEmoji -> TdApi.ChatPhotoStickerTypeCustomEmoji(identity.customEmojiId)
        is ProfileStickerAvatarIdentity.RegularOrMask -> TdApi.ChatPhotoStickerTypeRegularOrMask(identity.stickerSetId, identity.stickerId)
    }

    private fun nativeBackground(background: ProfileStickerAvatarBackground): TdApi.BackgroundFill {
        val colors = background.colors()
        return when (colors.size) {
            1 -> TdApi.BackgroundFillSolid(colors[0])
            2 -> TdApi.BackgroundFillGradient(colors[0], colors[1], 0)
            else -> TdApi.BackgroundFillFreeformGradient(colors.toIntArray())
        }
    }

    private fun nativeType(kind: ProfileStickerAvatarPackKind): TdApi.StickerType = when (kind) {
        ProfileStickerAvatarPackKind.REGULAR -> TdApi.StickerTypeRegular()
        ProfileStickerAvatarPackKind.MASK -> TdApi.StickerTypeMask()
        ProfileStickerAvatarPackKind.CUSTOM_EMOJI -> TdApi.StickerTypeCustomEmoji()
    }

    private fun matches(type: TdApi.StickerType?, kind: ProfileStickerAvatarPackKind): Boolean = when (kind) {
        ProfileStickerAvatarPackKind.REGULAR -> type is TdApi.StickerTypeRegular
        ProfileStickerAvatarPackKind.MASK -> type is TdApi.StickerTypeMask
        ProfileStickerAvatarPackKind.CUSTOM_EMOJI -> type is TdApi.StickerTypeCustomEmoji
    }

    private fun validPack(set: TdApi.StickerSet, pack: PackRecord): Boolean =
        set.id == pack.id && set.isInstalled && !set.isArchived && matches(set.stickerType, pack.kind)

    private fun matchesSticker(sticker: TdApi.Sticker?, pack: PackRecord): Boolean = sticker != null && when (pack.kind) {
        ProfileStickerAvatarPackKind.REGULAR -> sticker.id != 0L && sticker.setId == pack.id &&
            sticker.fullType is TdApi.StickerFullTypeRegular
        ProfileStickerAvatarPackKind.MASK -> sticker.id != 0L && sticker.setId == pack.id &&
            sticker.fullType is TdApi.StickerFullTypeMask
        ProfileStickerAvatarPackKind.CUSTOM_EMOJI -> (sticker.setId == 0L || sticker.setId == pack.id) &&
            ((sticker.fullType as? TdApi.StickerFullTypeCustomEmoji)?.customEmojiId ?: 0L) != 0L
    }

    private fun <T> nativeError(response: TdApi.Object): SettingsResult<T> = SettingsResult(error = settingsError(response))
    private fun <T> notify(callback: (SettingsResult<T>) -> Unit, value: SettingsResult<T>) { runCatching { callback(value) } }
    private fun <T> immutable(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))

    private companion object {
        const val MAX_CATALOG_QUERY = 1024
        const val MAX_OPERATIONS = 8
        const val MAX_NATIVE_IN_FLIGHT = 8
        const val MAX_MEDIA_CONCURRENT = 7
        const val MAX_MEDIA_GRANTS = 8
        const val MAX_MEDIA_WAITING = 8
    }
}
