package com.badnng.moe.notifications

import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal sealed interface WindowsToastHostEvent {
    data class Activate(val identity: DeviceNotificationIdentity) : WindowsToastHostEvent
    data class Dismiss(val identity: DeviceNotificationIdentity) : WindowsToastHostEvent
    data class Failed(val generation: Long) : WindowsToastHostEvent
}

/** App-owned COM apartment; an account retirement clears its map before queued OS work runs. */
internal class WindowsToastBackend(private val driverFactory: () -> WindowsToastDriver = { JnaWindowsToastDriver() }) : AutoCloseable {
    private val queue = LinkedBlockingQueue<() -> Unit>(128)
    private val closed = AtomicBoolean()
    private val revision = AtomicLong()
    private val wantedGeneration = AtomicLong()
    private val ownerRevision = AtomicLong()
    private val desired = AtomicReference<Map<DeviceNotificationIdentity, DeviceNotificationCard>>(emptyMap())
    private val pendingProjection = AtomicReference<ProjectionRequest?>(null)
    private val projectionQueued = AtomicBoolean()
    private val failureReported = AtomicBoolean()
    @Volatile private var worker: Thread? = null
    @Volatile private var enabled = false
    @Volatile private var listener: (WindowsToastHostEvent) -> Unit = {}
    @Volatile private var unownedActivation: () -> Unit = {}
    private var driver: WindowsToastDriver? = null
    private val visible = linkedMapOf<DeviceNotificationIdentity, Projection>()
    private val nonces = mutableMapOf<String, DeviceNotificationIdentity>()

    fun setListener(listener: (WindowsToastHostEvent) -> Unit) { this.listener = listener }
    fun setUnownedActivation(listener: () -> Unit) { unownedActivation = listener }
    fun setOwner(generation: Long) {
        ownerRevision.incrementAndGet()
        desired.set(emptyMap())
        pendingProjection.set(null)
        failureReported.set(false)
        wantedGeneration.set(generation)
        if (worker != null) offer { clearProjection() }
    }

    /** Identity must already have an owned, read-back-verified registration. Does not install it. */
    fun enable(aumid: String, clsid: String, result: (Boolean) -> Unit) {
        if (closed.get()) { result(false); return }
        val owner = revision.incrementAndGet()
        startWorker()
        if (!offer {
            if (closed.get() || owner != revision.get()) { result(false); return@offer }
            try {
                clearProjection()
                driver?.close()
                val next = driverFactory()
                driver = next
                next.initialize(aumid, clsid) { event ->
                    offer {
                        if (!closed.get() && owner == revision.get()) dispatch(event)
                    }
                }
                if (closed.get() || owner != revision.get()) { next.close(); driver = null; enabled = false; result(false) }
                else { enabled = true; failureReported.set(false); result(true) }
            } catch (_: Exception) {
                runCatching { driver?.close() }; driver = null; enabled = false; result(false)
            }
        }) result(false)
    }

    fun synchronize(generation: Long, cards: List<DeviceNotificationCard>, restored: Boolean) {
        if (closed.get() || !enabled || generation != wantedGeneration.get()) return
        val bounded = cards.take(625).filter { it.identity.generation == generation }.distinctBy { it.identity }
        // Publish the exact latest payload before queueing. Native removal, lock/redaction and
        // preview changes must invalidate an older XML preparation even on the same account.
        desired.set(bounded.associateBy { it.identity })
        val owner = ownerRevision.get()
        val backendRevision = revision.get()
        pendingProjection.updateAndGet { previous ->
            val popup = bounded.filterTo(mutableSetOf()) { !it.quiet && (!restored || it in previous?.popupCards.orEmpty()) }
            ProjectionRequest(generation, bounded, popup, owner, backendRevision)
        }
        enqueueProjection()
    }

    private fun enqueueProjection() {
        if (!projectionQueued.compareAndSet(false, true)) return
        if (!offer {
            try { pendingProjection.getAndSet(null)?.let(::applyProjection) }
            finally {
                projectionQueued.set(false)
                if (pendingProjection.get() != null && !closed.get()) enqueueProjection()
            }
        }) {
            projectionQueued.set(false)
            pendingProjection.set(null)
            reportFailure(wantedGeneration.get())
        }
    }

    private fun applyProjection(request: ProjectionRequest) {
        val generation = request.generation
        val owner = request.owner
        val backendRevision = request.backendRevision
        val current = { !closed.get() && enabled && generation == wantedGeneration.get() && owner == ownerRevision.get() && backendRevision == revision.get() }
            if (!current()) return
            try {
                val retained = desired.get().keys
                (visible.keys - retained).forEach(::remove)
                request.cards.forEach { card ->
                    if (!current()) return
                    val cardCurrent = { current() && desired.get()[card.identity] == card }
                    if (!cardCurrent()) return
                    val old = visible[card.identity]
                    if (old?.card == card) return@forEach
                    if (old != null) remove(card.identity)
                    if (!current()) return
                    val nonce = UUID.randomUUID().toString().replace("-", "")
                    val group = MessageDigest.getInstance("SHA-256").digest("$generation:${card.identity.groupId}".toByteArray())
                        .take(8).joinToString("") { "%02x".format(it) }
                    val tag = card.identity.notificationId.toString(16)
                    visible[card.identity] = Projection(card, nonce, owner)
                    nonces[nonce] = card.identity
                    if (!cardCurrent()) { remove(card.identity); return }
                    driver?.show(nonce, tag, group, card.title, card.text, card !in request.popupCards || old != null, cardCurrent)
                    if (!cardCurrent()) { remove(card.identity); return }
                }
            } catch (failure: Exception) {
                if (failure is WindowsToastRetired && current()) {
                    visible.filter { (id, value) -> desired.get()[id] != value.card }.keys.toList().forEach(::remove)
                } else if (current()) reportFailure(generation)
                else clearProjection()
            }
    }

    fun policy(result: (WindowsNotificationPolicy) -> Unit) {
        if (closed.get() || !enabled) { result(WindowsNotificationPolicy()); return }
        if (!offer { result(runCatching { driver?.policy() }.getOrNull() ?: WindowsNotificationPolicy()) })
            result(WindowsNotificationPolicy())
    }

    /** Retiring native UI is not a user dismissal and cannot send a read receipt. */
    fun retire(generation: Long) {
        if (wantedGeneration.compareAndSet(generation, 0)) {
            ownerRevision.incrementAndGet()
            desired.set(emptyMap())
            if (worker != null) offer { clearProjection() }
        }
    }

    fun disable(result: (Boolean) -> Unit = {}) {
        revision.incrementAndGet()
        enabled = false
        desired.set(emptyMap())
        pendingProjection.set(null)
        if (worker == null) { result(true); return }
        if (!offer {
            val success = runCatching { clearProjection(); driver?.close(); driver = null }.isSuccess
            result(success)
        }) result(false)
    }

    private fun dispatch(event: WindowsToastEvent) {
        val nonce = when (event) {
            is WindowsToastEvent.Activated -> event.nonce
            is WindowsToastEvent.Dismissed -> event.nonce
            is WindowsToastEvent.Failed -> event.nonce
        }
        val id = nonces[nonce] ?: run {
            if (event is WindowsToastEvent.Activated) runCatching(unownedActivation)
            return
        }
        val projected = visible[id]
        if (!enabled || id.generation != wantedGeneration.get() || projected == null || projected.nonce != nonce || projected.owner != ownerRevision.get()) return
        when (event) {
            is WindowsToastEvent.Activated -> notify(WindowsToastHostEvent.Activate(id))
            is WindowsToastEvent.Dismissed -> {
                if (event.byUser) { notify(WindowsToastHostEvent.Dismiss(id)); remove(id) }
                // TimedOut/ApplicationHidden ends the popup, not its Action Center history.
                // Retain the native toast and nonce until native removal or user dismissal.
            }
            is WindowsToastEvent.Failed -> reportFailure(id.generation)
        }
    }

    private fun remove(id: DeviceNotificationIdentity) {
        val value = visible.remove(id) ?: return
        nonces.remove(value.nonce) // callbacks lose authority before Hide/removal
        driver?.remove(value.nonce)
    }
    private fun clearProjection() { visible.keys.toList().forEach { runCatching { remove(it) } }; nonces.clear() }
    private fun clearRetiredProjection() {
        val currentOwner = ownerRevision.get()
        visible.filter { (id, value) -> id.generation != wantedGeneration.get() || value.owner != currentOwner }
            .keys.toList().forEach { runCatching { remove(it) } }
    }
    private fun notify(event: WindowsToastHostEvent) { runCatching { listener(event) } }
    private fun reportFailure(generation: Long) {
        if (generation > 0 && generation == wantedGeneration.get() && failureReported.compareAndSet(false, true)) notify(WindowsToastHostEvent.Failed(generation))
    }
    private fun offer(work: () -> Unit): Boolean = !closed.get() && queue.offer(work)
    @Synchronized private fun startWorker() {
        if (worker != null || closed.get()) return
        worker = Thread({
            try { while (!closed.get()) {
                val work = queue.take()
                try { clearRetiredProjection(); work() }
                catch (_: Exception) { reportFailure(wantedGeneration.get()) }
                finally { clearRetiredProjection() } // Full queue cannot discard account retirement cleanup.
            } }
            catch (_: InterruptedException) { }
            finally { runCatching { clearProjection(); driver?.close() }; driver = null; enabled = false }
        }, "nngGram-windows-toast").apply { isDaemon = true; start() }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        wantedGeneration.set(0)
        desired.set(emptyMap())
        pendingProjection.set(null)
        revision.incrementAndGet()
        listener = {}
        unownedActivation = {}
        queue.clear()
        worker?.interrupt()
    }
    private data class Projection(val card: DeviceNotificationCard, val nonce: String, val owner: Long)
    private data class ProjectionRequest(val generation: Long, val cards: List<DeviceNotificationCard>, val popupCards: Set<DeviceNotificationCard>,
        val owner: Long, val backendRevision: Long)
}
