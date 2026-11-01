package com.badnng.moe.notifications

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal interface DeviceWindowsNotificationHost {
    fun bind(generation: Long, events: (WindowsToastHostEvent) -> Unit, changed: (WindowsDeviceNotificationState) -> Unit)
    fun select(generation: Long, enabled: Boolean, result: (Boolean) -> Unit)
    fun uninstall(generation: Long, result: (Boolean) -> Unit)
    fun synchronize(generation: Long, cards: List<DeviceNotificationCard>, restored: Boolean)
    fun policy(generation: Long, result: (WindowsNotificationPolicy) -> Unit)
    fun retire(generation: Long)
    fun commitSelection(generation: Long) {}
    fun commitSelection(generation: Long, result: (Boolean) -> Unit) { commitSelection(generation); result(true) }
    fun cancelSelection(generation: Long) { select(generation, false) {} }
}

/** App lifetime COM bootstrap is independent of TDLib READY. Startup never creates/repairs registration. */
internal class WindowsDeviceNotificationHost(
    private val registration: WindowsToastRegistration,
    private val backend: WindowsToastBackend = WindowsToastBackend(),
    private val focusWindow: () -> Unit = {},
    private val executor: ExecutorService = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(32),
        { work -> Thread(work, "nngGram-toast-registration").apply { isDaemon = true } }),
) : DeviceWindowsNotificationHost, AutoCloseable {
    private val guard = Any()
    private val closed = AtomicBoolean()
    private val owner = AtomicLong()
    private val operation = AtomicLong()
    private val cleanup = ConcurrentHashMap.newKeySet<String>()
    private var selection: Selection? = null
    @Volatile private var state = WindowsDeviceNotificationState()
    @Volatile private var listener: (WindowsDeviceNotificationState) -> Unit = {}
    init { backend.setUnownedActivation { if (!closed.get()) focusWindow() } }

    fun bootstrap(previouslyEnabled: Boolean, activationLaunch: Boolean = false) {
        val revision = operation.incrementAndGet()
        execute {
            if (!current(revision)) return@execute
            val actual = registration.readState()
            if (!current(revision)) return@execute
            update { actual.toDeviceState() }
            val identity = actual.identity
            // A pending preference/manifest crash is shown as unfinished until explicit selection.
            synchronized(guard) {
                if ((previouslyEnabled || activationLaunch) && current(revision) && actual.status == WindowsToastRegistrationStatus.READY && identity != null) {
                    update { it.copy(status = WindowsDeviceNotificationStatus.ENABLING) }
                    backend.enable(identity.aumid, identity.clsid) { success ->
                        if (current(revision)) update { it.copy(status = if (success) WindowsDeviceNotificationStatus.ENABLED else WindowsDeviceNotificationStatus.FAILED) }
                    }
                }
            }
        }
    }

    override fun bind(generation: Long, events: (WindowsToastHostEvent) -> Unit, changed: (WindowsDeviceNotificationState) -> Unit) {
        val obsolete = synchronized(guard) {
            val old = selection.takeIf { it?.generation != generation }
            if (old != null) { operation.incrementAndGet(); selection = null; backend.disable() }
            owner.set(generation)
            backend.setOwner(generation)
            backend.setListener { event ->
                if (!closed.get() && owner.get() == generation) {
                    if (event is WindowsToastHostEvent.Failed) failRuntime(generation)
                    if (owner.get() == generation && !closed.get()) events(event)
                }
            }
            listener = { if (!closed.get() && owner.get() == generation) changed(it) }
            changed(state)
            old
        }
        cleanupLater(obsolete?.token)
    }

    override fun select(generation: Long, enabled: Boolean, result: (Boolean) -> Unit) {
        val complete = once(result)
        val chosen: Selection
        val previous: Selection?
        synchronized(guard) {
            if (closed.get() || owner.get() != generation) { complete(false); return }
            previous = selection
            chosen = Selection(generation, operation.incrementAndGet(), enabled,
                previousRuntime = state.status == WindowsDeviceNotificationStatus.ENABLED)
            selection = chosen
            backend.disable() // Invalidate an earlier enable before queued registration work.
            update { it.copy(status = WindowsDeviceNotificationStatus.ENABLING) }
        }
        cleanupLater(previous?.token)
        if (!enabled) {
            backend.disable { success ->
                execute(onRejected = { complete(false) }) {
                    val actual = registration.readState()
                    if (current(chosen)) {
                        update { actual.toDeviceState().let { value -> if (success) value else value.copy(status = WindowsDeviceNotificationStatus.FAILED) } }
                        complete(success)
                    } else complete(false)
                }
            }
            return
        }
        execute(onRejected = { complete(false) }) {
            if (!current(chosen)) { complete(false); return@execute }
            val installed = registration.enable(awaitSelectionCommit = true) { current(chosen) }
            val token = installed.state.pendingSelectionToken
            val accepted = synchronized(guard) {
                if (!current(chosen)) false else {
                    selection = chosen.copy(token = token)
                    update { installed.state.toDeviceState() }
                    true
                }
            }
            if (!accepted) { cleanupNow(token); complete(false); return@execute }
            val identity = installed.identity
            if (!installed.succeeded || installed.state.status != WindowsToastRegistrationStatus.READY || identity == null) {
                if (current(chosen)) update { it.copy(status = if (it.status == WindowsDeviceNotificationStatus.CONFLICT) it.status else WindowsDeviceNotificationStatus.FAILED) }
                complete(false)
                return@execute
            }
            synchronized(guard) {
                if (!current(chosen)) { cleanupLater(token); complete(false); return@synchronized }
                update { it.copy(status = WindowsDeviceNotificationStatus.ENABLING) }
                backend.enable(identity.aumid, identity.clsid) { success ->
                    if (!current(chosen)) { cleanupLater(token); complete(false) }
                    else if (!success) {
                        execute(onRejected = { complete(false) }) {
                            val actual = cleanupNow(token)
                            if (current(chosen)) {
                                synchronized(guard) { if (selection?.revision == chosen.revision) selection = null }
                                update { (actual?.state?.toDeviceState() ?: it).copy(status = WindowsDeviceNotificationStatus.FAILED) }
                            }
                            complete(false)
                        }
                    } else complete(true) // Only preference acceptance + durable commit publishes ENABLED.
                }
            }
        }
    }

    override fun commitSelection(generation: Long) = commitSelection(generation) {}
    override fun commitSelection(generation: Long, result: (Boolean) -> Unit) {
        val complete = once(result)
        val chosen = synchronized(guard) { selection?.takeIf { it.generation == generation && current(it) } }
        if (chosen == null) { complete(false); return }
        execute(onRejected = { complete(false) }) {
            if (!current(chosen)) { cleanupNow(chosen.token); complete(false); return@execute }
            val committed = chosen.token?.let { registration.commitPendingSelection(it) { current(chosen) }.succeeded } ?: true
            if (!committed || !current(chosen)) {
                val actual = cleanupNow(chosen.token) // Committed or different nonces cannot be deleted.
                synchronized(guard) {
                    if (current(chosen)) {
                        selection = null
                        backend.disable()
                        update { (actual?.state?.toDeviceState() ?: it).copy(status = WindowsDeviceNotificationStatus.FAILED) }
                    }
                }
                complete(false)
            } else {
                // Disabling runtime does not remove a historical pending/foreign registration.
                // Keep the actual conflict/recovery state visible after the preference commits.
                val actual = if (chosen.enabled) null else registration.readState().toDeviceState()
                synchronized(guard) {
                    if (current(chosen)) {
                        selection = null
                        update { actual ?: it.copy(status = WindowsDeviceNotificationStatus.ENABLED) }
                        complete(true)
                    } else complete(false)
                }
            }
        }
    }

    override fun cancelSelection(generation: Long) {
        val (chosen, revision) = synchronized(guard) {
            if (closed.get() || owner.get() != generation) return
            val value = selection ?: return
            val revision = operation.incrementAndGet(); selection = null; backend.disable()
            value to revision
        }
        execute {
            val actual = cleanupNow(chosen.token)?.state ?: registration.readState()
            if (!current(revision, generation)) return@execute
            update { actual.toDeviceState() }
            if (chosen.previousRuntime && actual.status == WindowsToastRegistrationStatus.READY && actual.identity != null) {
                synchronized(guard) {
                    if (current(revision, generation)) backend.enable(actual.identity.aumid, actual.identity.clsid) { success ->
                        if (current(revision, generation)) update { it.copy(status = if (success) WindowsDeviceNotificationStatus.ENABLED else WindowsDeviceNotificationStatus.FAILED) }
                    }
                }
            }
        }
    }

    override fun uninstall(generation: Long, result: (Boolean) -> Unit) {
        val (revision, obsolete) = synchronized(guard) {
            if (closed.get() || owner.get() != generation) { result(false); return }
            operation.incrementAndGet() to selection.also { selection = null }
        }
        cleanupLater(obsolete?.token)
        update { it.copy(status = WindowsDeviceNotificationStatus.ENABLING) }
        backend.disable { stopped ->
            if (!stopped || !current(revision, generation)) { result(false); return@disable }
            execute(onRejected = { result(false) }) {
                if (!current(revision, generation)) { result(false); return@execute }
                val removed = registration.uninstallOwned { current(revision, generation) }
                if (current(revision, generation)) { update { removed.state.toDeviceState() }; result(removed.succeeded) } else result(false)
            }
        }
    }

    override fun synchronize(generation: Long, cards: List<DeviceNotificationCard>, restored: Boolean) {
        if (state.status == WindowsDeviceNotificationStatus.ENABLED && owner.get() == generation && !closed.get()) backend.synchronize(generation, cards, restored)
    }
    override fun policy(generation: Long, result: (WindowsNotificationPolicy) -> Unit) {
        if (owner.get() != generation || closed.get()) { result(WindowsNotificationPolicy()); return }
        backend.policy { value ->
            if (owner.get() == generation && !closed.get()) { update { it.copy(focusBlocked = value.focusBlocked) }; result(value) }
        }
    }
    override fun retire(generation: Long) {
        val obsolete = synchronized(guard) {
            if (!owner.compareAndSet(generation, 0)) return
            val old = selection.also { selection = null }
            if (old != null) { operation.incrementAndGet(); backend.disable() }
            listener = {}; backend.retire(generation)
            old
        }
        cleanupLater(obsolete?.token)
    }
    fun snapshot(): WindowsDeviceNotificationState = state

    private fun failRuntime(generation: Long) {
        val obsolete = synchronized(guard) {
            if (closed.get() || owner.get() != generation) return
            operation.incrementAndGet()
            val old = selection.also { selection = null }
            backend.disable()
            update { it.copy(status = WindowsDeviceNotificationStatus.FAILED) }
            old
        }
        cleanupLater(obsolete?.token)
    }
    private fun cleanupNow(token: String?): WindowsToastRegistrationResult? = token?.let(registration::cancelPendingSelection)
    private fun cleanupLater(token: String?) {
        if (token == null) return
        cleanup.add(token)
        // Every already-queued job also drains this set. A full task queue cannot discard rollback.
        execute {}
    }
    private fun drainCleanup() {
        cleanup.toList().forEach { token ->
            if (!cleanup.remove(token)) return@forEach
            val actual = cleanupNow(token)
            synchronized(guard) {
                if (!closed.get() && selection == null && state.status != WindowsDeviceNotificationStatus.ENABLED && actual != null) {
                    val value = actual.state.toDeviceState()
                    update { value.copy(status = if (!actual.succeeded || it.status == WindowsDeviceNotificationStatus.FAILED) WindowsDeviceNotificationStatus.FAILED else value.status) }
                }
            }
        }
    }
    private fun current(value: Selection) = current(value.revision, value.generation)
    private fun current(revision: Long, generation: Long? = null) = !closed.get() && operation.get() == revision && (generation == null || owner.get() == generation)
    private fun execute(onRejected: () -> Unit = {}, work: () -> Unit) {
        try { executor.execute { try { drainCleanup(); work() } catch (_: Exception) { if (!closed.get()) update { it.copy(status = WindowsDeviceNotificationStatus.FAILED) }; onRejected() } finally { drainCleanup() } } }
        catch (_: java.util.concurrent.RejectedExecutionException) { if (!closed.get()) update { it.copy(status = WindowsDeviceNotificationStatus.FAILED) }; onRejected() }
    }
    private fun update(transform: (WindowsDeviceNotificationState) -> WindowsDeviceNotificationState) = synchronized(guard) {
        state = transform(state)
        runCatching { listener(state) }
        Unit
    }
    override fun close() {
        val obsolete = synchronized(guard) {
            if (!closed.compareAndSet(false, true)) return
            owner.set(0); operation.incrementAndGet(); listener = {}
            selection.also { selection = null }
        }
        backend.close()
        cleanupLater(obsolete?.token)
        executor.shutdown() // Drain exact-nonce rollback; shutdownNow would discard owned cleanup.
    }
    private data class Selection(val generation: Long, val revision: Long, val enabled: Boolean,
        val previousRuntime: Boolean, val token: String? = null)
}

private fun once(callback: (Boolean) -> Unit): (Boolean) -> Unit {
    val called = AtomicBoolean()
    return { value -> if (called.compareAndSet(false, true)) callback(value) }
}

private fun WindowsToastRegistrationState.toDeviceState() = WindowsDeviceNotificationState(
    status = when (status) {
        WindowsToastRegistrationStatus.UNAVAILABLE -> WindowsDeviceNotificationStatus.UNAVAILABLE
        WindowsToastRegistrationStatus.ABSENT -> WindowsDeviceNotificationStatus.DISABLED
        WindowsToastRegistrationStatus.READY -> WindowsDeviceNotificationStatus.REGISTERED
        WindowsToastRegistrationStatus.CONFLICT, WindowsToastRegistrationStatus.RECOVERY_REQUIRED -> WindowsDeviceNotificationStatus.CONFLICT
    }, registered = status == WindowsToastRegistrationStatus.READY, executable = identity?.executable?.toString(),
)
