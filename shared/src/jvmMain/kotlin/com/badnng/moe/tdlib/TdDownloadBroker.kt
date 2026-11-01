package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import java.util.IdentityHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Foreign callbacks cannot block the broker's shared timers or its native scheduling thread. */
private object OwnedReadCallbackExecutor {
    private val workers = Executors.newFixedThreadPool(8) { action ->
        Thread(action, "owned-read-callback").apply { isDaemon = true }
    }
    fun dispatch(action: () -> Unit) { workers.execute(action) }
}

/** The public TDLib API has ONE internal download id per file. Every writer shares this boundary. */
internal interface DownloadBrokerNative {
    fun download(fileId: Int, priority: Int, offset: Long, limit: Long, completed: (ChatResult<MediaFile>) -> Unit)
    fun prefix(fileId: Int, offset: Long, completed: (ChatResult<Long>) -> Unit)
    fun read(fileId: Int, offset: Long, count: Int, completed: (ChatResult<ByteArray>) -> Unit)
    fun refresh(fileId: Int, completed: (ChatResult<MediaFile>) -> Unit)
    fun cancel(fileId: Int, completed: (ChatResult<Unit>) -> Unit)

    /** Implementations that swallow send exceptions must override to report dispatch uncertainty. */
    fun ownedPrefix(fileId: Int, offset: Long, completed: (ChatResult<Long>) -> Unit, uncertain: () -> Unit) {
        try { prefix(fileId, offset, completed) } catch (_: Exception) { uncertain() }
    }
    fun ownedRead(fileId: Int, offset: Long, count: Int, completed: (ChatResult<ByteArray>) -> Unit, uncertain: () -> Unit) {
        if (count !in 1..65_536) { completed(ChatResult(error = ChatError.REQUEST_FAILED)); return }
        try { read(fileId, offset, count, completed) } catch (_: Exception) { uncertain() }
    }
}

internal enum class DownloadLeaseState { WAITING, READY_RANGE, POLICY_STOPPED, USER_CANCELLED, FAILED }

/**
 * Serialized false-acceptance barriers, finite native ranges and owner-scoped retirement.
 * These are logical windows, not a claim about exact network byte accounting. No cumulative
 * byte/time cap stops advancing metadata probes. All callbacks may be synchronous in fakes.
 */
internal class TdDownloadBroker(
    private val native: DownloadBrokerNative,
    private val schedule: (Long, () -> Unit) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    /** Must enqueue asynchronously; owned callbacks are never run under the broker monitor. */
    private val dispatchOwnedCallbacks: (() -> Unit) -> Unit = OwnedReadCallbackExecutor::dispatch,
) {
    private data class Owner(
        val key: MediaDownloadOwner,
        val full: Boolean,
        var cursor: Long = 0,
        var need: Long = 0,
        var servedUntil: Long = -1,
        var waitingSince: Long,
        val completed: ((ChatResult<MediaFile>) -> Unit)? = null,
        val byteLimit: Long? = null,
        val active: () -> Boolean = { true },
        val savedEvidence: SavedProfileStickerFileEvidence? = null,
        var revision: Long = 0,
        var failure: ChatError? = null,
        var checking: Boolean = false,
        var pendingReads: Int = 0,
        var eofSince: Long? = null,
    )
    private data class Window(val owner: MediaDownloadOwner, val offset: Long, val length: Long, var started: Long,
        val revision: Long, val ownerRevision: Long, val lease: Owner, val priority: Int, var progress: Long = 0, var progressedAt: Long = started)
    private class Lane(var file: MediaFile) {
        val owners = linkedMapOf<MediaDownloadOwner, Owner>()
        var window: Window? = null
        var accepting = false
        var querying = false
        var cancelling = false
        var nativeOwned = false
        var revision = 0L
        var consecutiveStreams = 0
        var refreshPending = false
        var nextRefreshAt = 0L
        var lastQueued = 0L
        var fileRevision = 0L
        var prefixPending = false
        var preparing = false
        var terminalError: ChatError? = null
        val prefixes = ArrayDeque<() -> Unit>()
    }
    private enum class OwnedStage { QUEUED, PREFIX, READ, FINISHED }
    private sealed interface OwnedEvent {
        class Response(value: ChatResult<ByteArray>) : OwnedEvent {
            private var held: ChatResult<ByteArray>? = value
            fun take(): ChatResult<ByteArray> = checkNotNull(held).also { held = null }
        }
        data object Terminal : OwnedEvent
    }
    private class OwnedDelivery(
        val completed: (ChatResult<ByteArray>) -> Unit,
        val terminal: () -> Unit,
    ) {
        val events = ArrayDeque<OwnedEvent>()
        var running = false
        var token: OwnedRead? = null
    }
    private class OwnedRead(
        val owner: MediaDownloadOwner,
        val fileId: Int,
        val lane: Lane,
        val lease: Owner,
        val revision: Long,
        val active: () -> Boolean,
        val delivery: OwnedDelivery,
    ) {
        var stage = OwnedStage.QUEUED
        var consumerDone = false
    }
    private val lock = Any()
    private val lanes = linkedMapOf<Int, Lane>()
    private val states = linkedMapOf<Pair<MediaDownloadOwner, Int>, DownloadLeaseState>()
    private var closed = false
    private var tickPending = false
    private var order = 0L
    private var pumping = false
    private var pumpAgain = false
    private val ownedReads = IdentityHashMap<OwnedRead, Unit>() // physical work survives owner/lane retirement
    private val ownedDeliveries = IdentityHashMap<OwnedDelivery, Unit>()
    private val ownedDepth = ThreadLocal.withInitial { 0 }
    private var ownedWakePending = false

    fun full(owner: MediaDownloadOwner, file: MediaFile, completed: ((ChatResult<MediaFile>) -> Unit)? = null) = full(owner, file, completed, null, { true })
    fun fullIfActive(owner: MediaDownloadOwner, file: MediaFile, active: () -> Boolean,
        completed: ((ChatResult<MediaFile>) -> Unit)? = null) = full(owner, file, completed, null, active)

    /** Unknown-size settings assets cannot advance native windows beyond the caller's input budget. */
    fun boundedFull(owner: MediaDownloadOwner, file: MediaFile, maximumBytes: Int, active: () -> Boolean = { true },
        completed: (ChatResult<MediaFile>) -> Unit) = boundedFullInternal(owner, file, maximumBytes, active, null, completed)

    /** Saved-photo evidence is a final atomics-only value, never an external callback. */
    fun boundedFullWithEvidence(owner: MediaDownloadOwner, file: MediaFile, maximumBytes: Int,
        active: () -> Boolean, evidence: SavedProfileStickerFileEvidence,
        completed: (ChatResult<MediaFile>) -> Unit) = boundedFullInternal(owner, file, maximumBytes, active, evidence, completed)

    private fun boundedFullInternal(owner: MediaDownloadOwner, file: MediaFile, maximumBytes: Int, active: () -> Boolean,
        evidence: SavedProfileStickerFileEvidence?, completed: (ChatResult<MediaFile>) -> Unit) {
        val cap = when (owner.purpose) {
            MediaDownloadPurpose.SOUND -> 16 * 1024 * 1024
            MediaDownloadPurpose.PROFILE_EMOJI, MediaDownloadPurpose.PROFILE_PHOTO_HISTORY -> 24 * 1024 * 1024
            else -> 0
        }
        if (maximumBytes !in 1..cap) { completed(failed()); return }
        full(owner, file, completed, maximumBytes.toLong(), active, evidence)
    }

    private fun full(owner: MediaDownloadOwner, file: MediaFile, completed: ((ChatResult<MediaFile>) -> Unit)?, byteLimit: Long?,
        active: () -> Boolean, evidence: SavedProfileStickerFileEvidence? = null) = synchronized(lock) {
        if (closed || file.id <= 0) { completed?.invoke(failed()); return@synchronized }
        if (!runCatching(active).getOrDefault(false)) { completed?.invoke(ChatResult(error = ChatError.NOT_READY)); return@synchronized }
        if (evidence?.record(file, strict = true) != null) { completed?.invoke(failed()); return@synchronized }
        if (byteLimit != null && file.size > byteLimit) { completed?.invoke(failed()); return@synchronized }
        if (file.downloaded) { completed?.invoke(ChatResult(file)); return@synchronized }
        if (file.id !in lanes && lanes.size >= MAX_FILES) { completed?.invoke(failed()); return@synchronized }
        val lane = lanes.getOrPut(file.id) { Lane(file).also { it.lastQueued = ++order } }
        lane.terminalError?.let { completed?.invoke(ChatResult(error = it)); return@synchronized }
        lane.file = file
        if (owner !in lane.owners) {
            if (lane.owners.size >= MAX_OWNERS_PER_FILE) { completed?.invoke(failed()); return@synchronized }
            lane.owners[owner] = Owner(owner, true, waitingSince = now(), completed = completed, byteLimit = byteLimit,
                active = active, savedEvidence = evidence)
            mark(owner, file.id, DownloadLeaseState.WAITING)
            lane.lastQueued = ++order
        }
        pump()
    }

    /** Only a confirmed prefix authorizes ReadFilePart. An accepted request is never byte evidence. */
    fun read(owner: MediaDownloadOwner, file: MediaFile, offset: Long, count: Int, completed: (ChatResult<ByteArray>) -> Unit) =
        readIfActive(owner, file, offset, count, { true }, completed)
    fun readIfActive(owner: MediaDownloadOwner, file: MediaFile, offset: Long, count: Int, active: () -> Boolean,
        completed: (ChatResult<ByteArray>) -> Unit) = synchronized(lock) {
        if (!runCatching(active).getOrDefault(false)) { completed(ChatResult(error = ChatError.NOT_READY)); return@synchronized }
        if (!closed && file.downloaded && file.size > 0 && offset >= file.size && offset >= 0 && count in 1..65_536) {
            completed(ChatResult(ByteArray(0))); return@synchronized
        }
        if (closed || file.id <= 0 || offset < 0 || count !in 1..65_536 || file.size > 0 && offset >= file.size) {
            completed(failed()); return@synchronized
        }
        if (file.id !in lanes && lanes.size >= MAX_FILES) { completed(failed()); return@synchronized }
        val lane = lanes.getOrPut(file.id) { Lane(file).also { it.lastQueued = ++order } }
        lane.terminalError?.let { completed(ChatResult(error = it)); return@synchronized }
        lane.file = file
        if (owner !in lane.owners && lane.owners.size >= MAX_OWNERS_PER_FILE) { completed(failed()); return@synchronized }
        val lease = lane.owners.getOrPut(owner) { Owner(owner, false, cursor = offset, need = offset, waitingSince = now(), active = active) }
        if (lease.full) { completed(failed()); return@synchronized }
        val current = lane.window?.takeIf { it.owner == owner }
        if (offset < lease.need || lease.need != offset && current?.let { offset < it.offset || offset >= it.offset + it.length } != false &&
            (lease.servedUntil < 0 || offset >= lease.servedUntil)) { lease.revision++; lease.failure = null }
        lease.need = offset
        if (offset < lease.cursor || lease.servedUntil >= 0 && offset >= lease.servedUntil) {
            lease.cursor = offset
            lease.servedUntil = -1
            lease.waitingSince = now()
        }
        lease.failure?.let { completed(ChatResult(error = it)); return@synchronized }
        mark(owner, file.id, DownloadLeaseState.WAITING)
        if (lease.pendingReads >= 2) { completed(ChatResult(error = ChatError.NOT_READY)); return@synchronized }
        val revision = lease.revision
        if (lane.prefixes.size >= MAX_PREFIX_QUEUE) { completed(ChatResult(error = ChatError.NOT_READY)); return@synchronized }
        val nativeCount = if (file.size > 0) minOf(count.toLong(), file.size - offset).toInt() else count
        lease.pendingReads++
        query(lane, offset, { live(lane, owner, revision, lease) && runCatching(lease.active).getOrDefault(false) }) { value ->
            if (!live(lane, owner, revision, lease) || !runCatching(lease.active).getOrDefault(false)) {
                lease.pendingReads--; completed(ChatResult(error = ChatError.NOT_READY)); return@query
            }
            val prefix = value.value
            if (value.error != null || prefix == null || prefix < 0) { lease.pendingReads--; completed(ChatResult(error = value.error ?: ChatError.REQUEST_FAILED)); return@query }
            if (prefix > 0) {
                lease.cursor = offset
                lease.servedUntil = offset + prefix
                mark(owner, file.id, DownloadLeaseState.READY_RANGE)
                native.read(file.id, offset, minOf(nativeCount.toLong(), prefix).toInt()) { bytes -> synchronized(lock) {
                    lease.pendingReads--
                    if (!live(lane, owner, revision, lease)) completed(ChatResult(error = ChatError.NOT_READY)) else completed(bytes)
                } }
            }
            else if (lane.file.downloaded) { lease.pendingReads--; completed(ChatResult(ByteArray(0))) }
            else { lease.pendingReads--; lease.servedUntil = -1; completed(ChatResult(error = ChatError.NOT_READY)); pump() }
        }
        pump()
    }

    /**
     * Internal owned read. Consumer completion and physical terminal are separate: release/close
     * may retire the consumer, but a sent prefix or read remains charged until its own callback.
     * The current predicate must read captured atomics only. Terminal is asynchronously dispatched.
     */
    fun readOwnedIfActive(owner: MediaDownloadOwner, file: MediaFile, offset: Long, count: Int,
        active: () -> Boolean, completed: (ChatResult<ByteArray>) -> Unit, terminal: () -> Unit,
    ) = ownedBoundary { synchronized(lock) {
        val delivery = OwnedDelivery(completed, terminal)
        fun reject(value: ChatResult<ByteArray>) {
            enqueueOwned(delivery, OwnedEvent.Response(value))
            enqueueOwned(delivery, OwnedEvent.Terminal)
        }
        if (!runCatching(active).getOrDefault(false)) { reject(ChatResult(error = ChatError.NOT_READY)); return@synchronized }
        if (!closed && file.downloaded && file.size > 0 && offset >= file.size && offset >= 0 && count in 1..65_536) {
            reject(ChatResult(ByteArray(0))); return@synchronized
        }
        if (closed || file.id <= 0 || offset < 0 || count !in 1..65_536 || file.size > 0 && offset >= file.size ||
            ownedReads.size >= MAX_OWNED_READS) { reject(failed()); return@synchronized }
        if (file.id !in lanes && lanes.size >= MAX_FILES) { reject(failed()); return@synchronized }
        val lane = lanes.getOrPut(file.id) { Lane(file).also { it.lastQueued = ++order } }
        lane.terminalError?.let { reject(ChatResult(error = it)); return@synchronized }
        lane.file = file
        if (owner !in lane.owners && lane.owners.size >= MAX_OWNERS_PER_FILE) { reject(failed()); return@synchronized }
        val lease = lane.owners.getOrPut(owner) {
            Owner(owner, false, cursor = offset, need = offset, waitingSince = now(), active = active)
        }
        if (lease.full) { reject(failed()); return@synchronized }
        if (!runCatching(lease.active).getOrDefault(false)) {
            reject(ChatResult(error = ChatError.NOT_READY)); pump(); return@synchronized
        }
        val current = lane.window?.takeIf { it.owner == owner }
        if (offset < lease.need || lease.need != offset && current?.let { offset < it.offset || offset >= it.offset + it.length } != false &&
            (lease.servedUntil < 0 || offset >= lease.servedUntil)) { lease.revision++; lease.failure = null }
        lease.need = offset
        if (offset < lease.cursor || lease.servedUntil >= 0 && offset >= lease.servedUntil) {
            lease.cursor = offset; lease.servedUntil = -1; lease.waitingSince = now()
        }
        lease.failure?.let { reject(ChatResult(error = it)); return@synchronized }
        mark(owner, file.id, DownloadLeaseState.WAITING)
        if (lease.pendingReads >= 2 || lane.prefixes.size >= MAX_PREFIX_QUEUE) {
            reject(ChatResult(error = ChatError.NOT_READY)); return@synchronized
        }
        val nativeCount = if (file.size > 0) minOf(count.toLong(), file.size - offset).toInt() else count
        val token = OwnedRead(owner, file.id, lane, lease, lease.revision, active, delivery)
        delivery.token = token
        lease.pendingReads++
        ownedReads[token] = Unit
        ownedPrefix(token, offset, nativeCount)
        pump()
    } }

    /** Caller holds the broker monitor; tokens retain their own lane even after it leaves the map. */
    private fun ownedPrefix(token: OwnedRead, offset: Long, count: Int) {
        val lane = token.lane
        val lease = token.lease
        lane.prefixes.addLast {
            if (token.stage == OwnedStage.FINISHED) {
                lane.prefixPending = false; nextPrefix(lane); return@addLast
            }
            if (!ownedCurrent(token)) {
                lease.pendingReads--; lane.prefixPending = false
                finishOwned(token, ChatResult(error = ChatError.NOT_READY))
                nextPrefix(lane); return@addLast
            }
            token.stage = OwnedStage.PREFIX
            val once = AtomicBoolean()
            try {
                native.ownedPrefix(token.fileId, offset, completed = prefix@ { value ->
                    if (!once.compareAndSet(false, true)) return@prefix
                    ownedBoundary { synchronized(lock) prefixLock@ {
                        if (token.stage != OwnedStage.PREFIX) return@prefixLock
                        val laneCurrent = !closed && lanes[token.fileId] === lane
                        if (laneCurrent) lane.prefixPending = false
                        if (!laneCurrent || token.consumerDone || !ownedCurrent(token)) {
                            lease.pendingReads--
                            finishOwned(token, ChatResult(error = ChatError.NOT_READY))
                        } else {
                            val prefix = value.value
                            if (value.error != null || prefix == null || prefix < 0) {
                                lease.pendingReads--
                                finishOwned(token, ChatResult(error = value.error ?: ChatError.REQUEST_FAILED))
                            } else if (prefix > 0) {
                                lease.cursor = offset
                                lease.servedUntil = if (prefix > Long.MAX_VALUE - offset) Long.MAX_VALUE else offset + prefix
                                mark(token.owner, token.fileId, DownloadLeaseState.READY_RANGE)
                                token.stage = OwnedStage.READ
                                val readOnce = AtomicBoolean()
                                try {
                                    native.ownedRead(token.fileId, offset, minOf(count.toLong(), prefix).toInt(), completed = read@ { bytes ->
                                        if (!readOnce.compareAndSet(false, true)) return@read
                                        ownedBoundary { synchronized(lock) readLock@ {
                                            if (token.stage != OwnedStage.READ) return@readLock
                                            lease.pendingReads--
                                            val output = if (ownedCurrent(token) && !token.consumerDone) bytes
                                                else ChatResult(error = ChatError.NOT_READY)
                                            finishOwned(token, output)
                                        } }
                                    }, uncertain = {
                                        ownedBoundary { synchronized(lock) {
                                            if (token.stage == OwnedStage.READ)
                                                notifyOwned(token, ChatResult(error = ChatError.REQUEST_FAILED))
                                        } }
                                    })
                                } catch (_: Exception) {
                                    // Dispatch status is unknown. Only the real callback can end READ.
                                    if (token.stage == OwnedStage.READ) notifyOwned(token, ChatResult(error = ChatError.REQUEST_FAILED))
                                }
                            } else {
                                lease.pendingReads--
                                if (!lane.file.downloaded) lease.servedUntil = -1
                                finishOwned(token, if (lane.file.downloaded) ChatResult(ByteArray(0))
                                    else ChatResult(error = ChatError.NOT_READY))
                            }
                        }
                        if (laneCurrent) {
                            nextPrefix(lane)
                            if (lane.owners.isEmpty()) { retireLane(token.fileId, lane); pump() }
                            else if (lane.window != null || pick(lane) != null) queueTick()
                        }
                    } }
                }, uncertain = {
                    ownedBoundary { synchronized(lock) {
                        if (token.stage == OwnedStage.PREFIX)
                            notifyOwned(token, ChatResult(error = ChatError.REQUEST_FAILED))
                    } }
                })
            } catch (_: Exception) {
                // It may have reached native code. Keep PREFIX and the lane barrier until callback.
                if (token.stage == OwnedStage.PREFIX) notifyOwned(token, ChatResult(error = ChatError.REQUEST_FAILED))
            }
        }
        nextPrefix(lane)
    }

    private fun notifyOwned(token: OwnedRead, value: ChatResult<ByteArray>) {
        if (token.consumerDone) return
        token.consumerDone = true
        enqueueOwned(token.delivery, OwnedEvent.Response(value))
    }

    private fun ownedCurrent(token: OwnedRead): Boolean =
        live(token.lane, token.owner, token.revision, token.lease) &&
            runCatching(token.active).getOrDefault(false) && runCatching(token.lease.active).getOrDefault(false)

    /** Only a never-dispatched queued request or its own native callback may call this. */
    private fun finishOwned(token: OwnedRead, value: ChatResult<ByteArray>) {
        if (token.stage == OwnedStage.FINISHED) return
        token.stage = OwnedStage.FINISHED
        notifyOwned(token, value)
        enqueueOwned(token.delivery, OwnedEvent.Terminal)
    }

    /** A nested synchronous native callback defers delivery to its outermost broker boundary. */
    private fun <T> ownedBoundary(action: () -> T): T {
        val previous = ownedDepth.get()
        ownedDepth.set(previous + 1)
        try { return action() } finally {
            ownedDepth.set(previous)
            if (previous == 0) flushOwned()
        }
    }

    /** Called under the monitor; it only records events, never invokes foreign code. */
    private fun enqueueOwned(delivery: OwnedDelivery, event: OwnedEvent) {
        delivery.events.addLast(event)
        ownedDeliveries[delivery] = Unit
        if (ownedDepth.get() == 0) wakeOwnedLocked()
    }

    /** Handles a queued owned prefix reached through a legacy broker callback. */
    private fun wakeOwnedLocked() {
        if (ownedWakePending) return
        ownedWakePending = true
        try {
            DownloadBrokerScheduler.schedule(0) {
                synchronized(lock) { ownedWakePending = false }
                flushOwned()
            }
        } catch (_: Exception) { ownedWakePending = false /* retain undelivered state */ }
    }

    /** Both the injected dispatcher and every foreign callback run after releasing the monitor. */
    private fun flushOwned() {
        if (Thread.holdsLock(lock)) { wakeOwnedLocked(); return }
        val pending = synchronized(lock) {
            ownedDeliveries.keys.filter { !it.running && it.events.isNotEmpty() }.onEach { it.running = true }
        }
        pending.forEach { delivery ->
            val once = AtomicBoolean()
            val run: () -> Unit = { if (once.compareAndSet(false, true)) drainOwned(delivery) }
            try { dispatchOwnedCallbacks(run) }
            catch (_: Exception) {
                try { OwnedReadCallbackExecutor.dispatch(run) } catch (_: Exception) { /* retain undelivered state */ }
            }
        }
    }

    /** Response and terminal for one token are strictly ordered even if a callback blocks. */
    private fun drainOwned(delivery: OwnedDelivery) {
        while (true) {
            val event = synchronized(lock) {
                if (delivery.events.isEmpty()) {
                    delivery.running = false
                    ownedDeliveries.remove(delivery)
                    null
                } else delivery.events.removeFirst().also {
                    // Keep a completed token charged while its foreign response callback is running.
                    // Free it just before terminal notification so that callback may reenter.
                    if (it === OwnedEvent.Terminal) delivery.token?.let(ownedReads::remove)
                }
            } ?: return
            when (event) {
                is OwnedEvent.Response -> deliverOwnedResponse(delivery, event)
                OwnedEvent.Terminal -> runCatching { delivery.terminal() }
            }
        }
    }

    /** The response wrapper drops its byte reference before the later terminal event executes. */
    private fun deliverOwnedResponse(delivery: OwnedDelivery, event: OwnedEvent.Response) {
        val value = event.take()
        val safe = synchronized(lock) {
            val token = delivery.token
            if (value.value != null && token != null && !ownedCurrent(token))
                ChatResult(error = ChatError.NOT_READY) else value
        }
        runCatching { delivery.completed(safe) }
    }

    fun release(owner: MediaDownloadOwner, fileId: Int) = ownedBoundary { synchronized(lock) {
        val lane = lanes[fileId] ?: return@synchronized
        lane.owners.remove(owner)?.let { it.revision++ }
        ownedReads.keys.toList().filter { it.owner == owner && it.fileId == fileId && it.stage == OwnedStage.QUEUED }
            .forEach { if (it.stage == OwnedStage.QUEUED) {
                it.lease.pendingReads--; finishOwned(it, ChatResult(error = ChatError.NOT_READY))
            } }
        mark(owner, fileId, if (owner.purpose == MediaDownloadPurpose.AUTO) DownloadLeaseState.POLICY_STOPPED else DownloadLeaseState.USER_CANCELLED)
        if (lane.owners.isEmpty()) retireLane(fileId, lane)
        else pump()
    } }

    fun observe(file: MediaFile) = synchronized(lock) {
        val lane = lanes[file.id] ?: return@synchronized
        // This exact final evidence type uses only CAS/atomics; no foreign callback is invoked here.
        lane.owners.values.filter { it.savedEvidence != null && runCatching(it.active).getOrDefault(false) }
            .toList().forEach { owner ->
                if (owner.savedEvidence?.record(file, strict = true) != null) fail(lane, owner, ChatError.REQUEST_FAILED)
            }
        lane.file = file
        lane.fileRevision++
        lane.owners.values.filter { it.byteLimit != null && file.size > it.byteLimit }.toList()
            .forEach { fail(lane, it, ChatError.REQUEST_FAILED) }
        if (file.downloaded) {
            if (lane.terminalError != null) { lane.terminalError = null; lane.cancelling = false }
            val done = lane.owners.values.filter { it.full }
            done.forEach { lane.owners.remove(it.key); mark(it.key, file.id, DownloadLeaseState.READY_RANGE) }
            if (!lane.accepting) { lane.window = null; lane.nativeOwned = false }
            done.forEach { runCatching { it.completed?.invoke(ChatResult(file)) } }
            if (lanes[file.id] === lane && lane.owners.isEmpty() && !lane.accepting && !lane.cancelling) lanes.remove(file.id)
        }
        pump()
    }

    fun manages(fileId: Int): Boolean = synchronized(lock) { lanes[fileId]?.let { it.nativeOwned || it.owners.isNotEmpty() } == true }
    fun state(owner: MediaDownloadOwner, fileId: Int) = synchronized(lock) { states[owner to fileId] }

    /** Client close owns final native cancellation; no late callback may start a new request. */
    fun close() = ownedBoundary { synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        ownedReads.keys.toList().filter { it.stage == OwnedStage.QUEUED }
            .forEach { if (it.stage == OwnedStage.QUEUED) {
                it.lease.pendingReads--; finishOwned(it, ChatResult(error = ChatError.NOT_READY))
            } }
        val waiting = lanes.values.flatMap { it.owners.values }.mapNotNull { it.completed }
        lanes.clear()
        states.clear()
        waiting.forEach { runCatching { it(ChatResult(error = ChatError.NOT_READY)) } }
    } }

    private fun live(lane: Lane, owner: MediaDownloadOwner, revision: Long? = null, expected: Owner? = null) = !closed && lanes[lane.file.id] === lane &&
        lane.owners[owner]?.let { (expected == null || it === expected) && (revision == null || it.revision == revision) } == true
    private fun eligible(owner: Owner, lane: Lane): Boolean = !lane.file.downloaded &&
        owner.failure == null && !owner.checking && owner.pendingReads == 0 && runCatching(owner.active).getOrDefault(false) &&
        (owner.byteLimit == null || owner.cursor < owner.byteLimit) &&
        (if (owner.full) lane.file.size == 0L || owner.cursor < lane.file.size else owner.servedUntil < 0 || owner.need >= owner.servedUntil)

    private fun pick(lane: Lane, rotated: Set<Owner> = emptySet()): Owner? {
        val ready = lane.owners.values.filter { eligible(it, lane) }
        fun waiting(owner: Owner) = if (owner in rotated) now() else owner.waitingSince
        val user = ready.filter { it.full && it.key.purpose != MediaDownloadPurpose.AUTO }.minByOrNull(::waiting)
        val stream = ready.filter { !it.full }.minByOrNull(::waiting)
        return when {
            user != null && stream != null && (lane.consecutiveStreams >= 3 || now() - waiting(user) >= 2000) -> user
            stream != null -> stream
            user != null -> user
            else -> ready.minByOrNull(::waiting)
        }
    }

    private fun pump() {
        if (closed) return
        if (pumping) { pumpAgain = true; return }
        pumping = true
        try { do {
        pumpAgain = false
        // Native ranges do not cease because a logical lane is requeued. Cancel and await the
        // cancellation barrier before admitting a fifth active file or reusing that file's slot.
        lanes.toMap().forEach { (id, lane) ->
            if (lanes[id] !== lane) return@forEach
            lane.owners.values.filter { !runCatching(it.active).getOrDefault(false) }.toList().forEach {
                if (it.full) fail(lane, it, ChatError.NOT_READY)
                else { lane.owners.remove(it.key); it.revision++ }
            }
            if (lane.owners.isEmpty()) retireLane(id, lane)
            else if (lane.window != null && !lane.accepting && !lane.cancelling &&
                !live(lane, checkNotNull(lane.window).owner, checkNotNull(lane.window).ownerRevision, checkNotNull(lane.window).lease) &&
                !windowNeeded(lane, checkNotNull(lane.window))) releaseWindow(id, lane)
            else if (lane.window != null && !lane.accepting && !lane.querying && !lane.cancelling) poll(id, lane)
            if (!lane.file.downloaded) {
                val eof = lane.owners.values.filter { it.full && ((lane.file.size > 0 && it.cursor >= lane.file.size) ||
                    (it.byteLimit != null && it.cursor >= it.byteLimit)) }
                eof.forEach { owner ->
                    val since = owner.eofSince ?: now().also { owner.eofSince = it }
                    if (now() - since >= STALL_MILLIS) fail(lane, owner, ChatError.REQUEST_FAILED)
                }
                if (!lane.refreshPending && now() >= lane.nextRefreshAt && eof.any { lane.owners[it.key] === it }) refresh(lane)
            }
        }
        val occupied = lanes.values.count { it.preparing || it.accepting || it.cancelling || it.window != null }
        if (occupied < MAX_LANES) {
            lanes.values.filter { !it.preparing && !it.accepting && !it.cancelling && it.window == null && pick(it) != null }
                .sortedBy { it.lastQueued }.take(MAX_LANES - occupied).forEach { prepare(it) }
        }
        } while (pumpAgain && !closed) } finally { pumping = false }
        if (lanes.values.any { it.window != null && !it.accepting && !it.cancelling ||
                it.owners.values.any { owner -> owner.eofSince != null } }) queueTick()
    }

    private fun prepare(lane: Lane) {
        val owner = pick(lane) ?: return
        if (!owner.full) { begin(lane); return }
        lane.preparing = true
        owner.checking = true
        val revision = owner.revision
        query(lane, owner.cursor, { live(lane, owner.key, revision, owner) && runCatching(owner.active).getOrDefault(false) }) { result ->
            owner.checking = false
            lane.preparing = false
            if (!live(lane, owner.key, revision, owner)) return@query
            if (!runCatching(owner.active).getOrDefault(false)) { fail(lane, owner, ChatError.NOT_READY); pump(); return@query }
            val prefix = result.value?.takeIf { result.error == null && it >= 0 }
            if (prefix == null) { fail(lane, owner, result.error ?: ChatError.REQUEST_FAILED); return@query }
            owner.cursor += if (owner.byteLimit != null) minOf(prefix, (owner.byteLimit - owner.cursor).coerceAtLeast(0)) else prefix
            if (lane.file.size > 0 && owner.cursor >= lane.file.size || owner.byteLimit != null && owner.cursor >= owner.byteLimit) {
                owner.eofSince = now(); refresh(lane)
            }
            else begin(lane, owner)
        }
    }

    private fun begin(lane: Lane, selected: Owner? = null) {
        val owner = selected ?: pick(lane) ?: return
        if (lane.window != null || lane.accepting || lane.cancelling || !live(lane, owner.key, owner.revision, owner)) return
        if (!runCatching(owner.active).getOrDefault(false)) { fail(lane, owner, ChatError.NOT_READY); pump(); return }
        val offset = if (owner.full) owner.cursor else owner.need
        val maximum = if (owner.full) FULL_WINDOW else if (owner.key.purpose == MediaDownloadPurpose.PROBE) PROBE_WINDOW else STREAM_WINDOW
        val remaining = owner.byteLimit?.let { (it - offset).coerceAtLeast(0) } ?: Long.MAX_VALUE
        val length = minOf(if (lane.file.size > 0) minOf(maximum, lane.file.size - offset) else maximum, remaining)
        if (length <= 0) return
        val priority = priority(owner)
        val window = Window(owner.key, offset, length, now(), ++lane.revision, owner.revision, owner, priority)
        lane.window = window
        lane.accepting = true
        lane.nativeOwned = true
        if (owner.full) lane.consecutiveStreams = 0 else lane.consecutiveStreams++
        val fileRevision = lane.fileRevision
        native.download(lane.file.id, priority, offset, length) { response -> synchronized(lock) {
            if (closed || lanes[lane.file.id] !== lane || lane.window?.revision != window.revision) return@synchronized
            lane.accepting = false
            if (response.error != null || response.value == null) {
                lane.window = null
                lane.nativeOwned = false
                if (live(lane, owner.key, window.ownerRevision, owner)) fail(lane, owner, response.error ?: ChatError.REQUEST_FAILED)
            } else {
                window.started = now(); window.progressedAt = now()
                if (lane.fileRevision == fileRevision) observe(response.value)
            }
            if (lane.owners.isEmpty()) retireLane(lane.file.id, lane)
            pump()
        } }
    }

    private fun poll(id: Int, lane: Lane) {
        val window = lane.window ?: return
        lane.querying = true
        query(lane, window.offset, { !closed && lane.window?.revision == window.revision &&
            (live(lane, window.owner, window.ownerRevision, window.lease) || windowNeeded(lane, window)) }) { result ->
            if (closed || lanes[id] !== lane) return@query
            lane.querying = false
            if (lane.window?.revision != window.revision) return@query
            val prefix = result.value?.takeIf { result.error == null && it >= 0 }
            if (prefix == null) {
                fail(lane, window.lease, result.error ?: ChatError.REQUEST_FAILED)
                releaseWindow(id, lane); return@query
            }
            if (prefix > window.progress) { window.progress = prefix; window.progressedAt = now() }
            if (prefix >= window.length) {
                val served = if (window.lease.full) lane.owners.values.filter { it.full && it.cursor >= window.offset &&
                    it.cursor <= window.offset + window.length && runCatching(it.active).getOrDefault(false) }
                    else listOfNotNull(lane.owners[window.owner]?.takeIf { it === window.lease && it.revision == window.ownerRevision })
                served.forEach { owner ->
                    if (owner.full) owner.cursor = maxOf(owner.cursor, window.offset +
                        if (owner.byteLimit != null) minOf(prefix, (owner.byteLimit - window.offset).coerceAtLeast(0)) else prefix)
                    else { owner.cursor = window.offset; owner.servedUntil = window.offset + prefix }
                    owner.waitingSince = now()
                    mark(owner.key, id, DownloadLeaseState.READY_RANGE)
                }
                lane.lastQueued = ++order
                releaseWindow(id, lane)
                if (lane.file.size > 0 && window.offset + prefix >= lane.file.size) refresh(lane)
            } else if (now() - window.progressedAt >= STALL_MILLIS) {
                fail(lane, window.lease, ChatError.REQUEST_FAILED)
                releaseWindow(id, lane)
            } else if (now() - window.started >= 1000) {
                rotation(lane, window)?.let { served ->
                    served.forEach { it.waitingSince = now() }
                    lane.lastQueued = ++order
                    releaseWindow(id, lane)
                }
            }
            queueTick()
        }
    }

    private fun rotation(lane: Lane, window: Window): Set<Owner>? {
        // Owners already served by the same effective native work share one turn. This
        // also keeps A@0 + B@0 from hiding an older C@9MiB when previewing the next turn.
        val served = lane.owners.values.filter { owner ->
            val offset = if (owner.full) owner.cursor else owner.need
            priority(owner) <= window.priority && (owner.full && window.lease.full ||
                offset >= window.offset && offset < window.offset + window.length)
        }.toSet() + window.lease
        val next = pick(lane, rotated = served) ?: return null
        // AUTO may wait; it never interrupts current user or playback work. A timer
        // alone cannot cancel and republish the exact range that is already serving it.
        return served.takeIf { next !in served && next.key.purpose != MediaDownloadPurpose.AUTO }
    }

    private fun priority(owner: Owner) = if (!owner.full) {
        if (owner.key.purpose == MediaDownloadPurpose.PROBE) 24 else 32
    } else if (owner.key.purpose == MediaDownloadPurpose.AUTO) 1 else 16

    private fun fail(lane: Lane, owner: Owner, error: ChatError) {
        if (lane.owners[owner.key] !== owner) return
        owner.failure = error
        mark(owner.key, lane.file.id, DownloadLeaseState.FAILED)
        if (owner.full) { lane.owners.remove(owner.key); runCatching { owner.completed?.invoke(ChatResult(error = error)) } }
    }

    private fun refresh(lane: Lane) {
        if (closed || lane.refreshPending) return
        lane.refreshPending = true
        lane.nextRefreshAt = now() + 100
        val revision = lane.fileRevision
        native.refresh(lane.file.id) { fresh -> synchronized(lock) {
            if (closed || lanes[lane.file.id] !== lane) return@synchronized
            lane.refreshPending = false
            if (lane.fileRevision != revision) return@synchronized
            if (fresh.error != null || fresh.value?.id != lane.file.id) {
                lane.owners.values.filter { it.full }.toList().forEach { fail(lane, it, fresh.error ?: ChatError.REQUEST_FAILED) }
                queueTick()
            } else observe(checkNotNull(fresh.value))
        } }
    }

    private fun windowNeeded(lane: Lane, window: Window) = lane.owners.values.any {
        it.full && window.lease.full && it.failure == null && runCatching(it.active).getOrDefault(false) &&
            it.cursor >= window.offset && it.cursor < window.offset + window.length &&
            (it.byteLimit == null || it.byteLimit >= window.offset + window.length)
    }

    private fun query(lane: Lane, offset: Long, allowed: () -> Boolean = { true }, completed: (ChatResult<Long>) -> Unit) {
        lane.prefixes.addLast {
            if (!allowed()) {
                lane.prefixPending = false
                completed(ChatResult(error = ChatError.NOT_READY))
                nextPrefix(lane)
                return@addLast
            }
            native.prefix(lane.file.id, offset) { result -> synchronized(lock) {
                if (closed || lanes[lane.file.id] !== lane) return@synchronized
                lane.prefixPending = false
                completed(result)
                nextPrefix(lane)
                if (lane.owners.isEmpty()) { retireLane(lane.file.id, lane); pump() }
                else if (lane.window != null || pick(lane) != null) queueTick()
            } }
        }
        nextPrefix(lane)
    }

    private fun nextPrefix(lane: Lane) {
        if (closed || lane.prefixPending || lane.prefixes.isEmpty()) return
        lane.prefixPending = true
        lane.prefixes.removeFirst().invoke()
    }

    private fun releaseWindow(id: Int, lane: Lane) {
        if (lane.accepting || lane.cancelling || !lane.nativeOwned) return
        lane.cancelling = true
        native.cancel(id) { result -> synchronized(lock) {
            if (closed || lanes[id] !== lane) return@synchronized
            if (result.error != null || result.value == null) {
                // The native slot may still be active. Keep its lane charged until client close;
                // a cancellation error cannot authorize a fifth range or overwrite this slot.
                lane.terminalError = result.error ?: ChatError.REQUEST_FAILED
                lane.owners.values.toList().forEach { fail(lane, it, checkNotNull(lane.terminalError)) }
                return@synchronized
            }
            lane.cancelling = false
            lane.window = null
            lane.nativeOwned = false
            if (lane.owners.isEmpty()) lanes.remove(id)
            pump()
        } }
    }

    private fun retireLane(id: Int, lane: Lane) {
        if (lanes[id] !== lane) return
        if (lane.accepting || lane.cancelling) return // Tombstone until the false acceptance barrier.
        if (lane.nativeOwned) releaseWindow(id, lane)
        else if (!lane.prefixPending && lane.prefixes.isEmpty()) lanes.remove(id)
    }

    private fun queueTick() {
        if (closed || tickPending) return
        tickPending = true
        schedule(100) { synchronized(lock) { tickPending = false; pump() } }
    }

    private fun <T> failed(): ChatResult<T> = ChatResult(error = ChatError.REQUEST_FAILED)
    private fun mark(owner: MediaDownloadOwner, fileId: Int, state: DownloadLeaseState) {
        states.remove(owner to fileId)
        states[owner to fileId] = state
        while (states.size > MAX_FILES) states.remove(states.keys.first())
    }
    companion object {
        const val MAX_LANES = 4
        const val MAX_FILES = 256
        const val MAX_OWNERS_PER_FILE = 64
        const val MAX_PREFIX_QUEUE = 16
        const val MAX_OWNED_READS = 8
        const val FULL_WINDOW = 512L * 1024
        const val PROBE_WINDOW = 256L * 1024
        const val STREAM_WINDOW = 2L * 1024 * 1024
        const val STALL_MILLIS = 15_000L
    }
}
