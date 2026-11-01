package com.badnng.moe.tdlib

import com.badnng.moe.settings.SettingsError
import com.badnng.moe.settings.SettingsResult
import org.drinkless.tdlib.TdApi
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** False is only valid when the native send was definitely never entered. */
internal typealias SettingsStorageNativeSend =
    (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Boolean

/** Exactly one outstanding statistics/GC request for one physical native session. */
internal class SettingsStorageCoordinator(private val send: SettingsStorageNativeSend) {
    private class Request(
        val command: TdApi.Function<*>,
        val owner: SettingsProxyProbeOwner,
        val result: (SettingsResult<TdApi.Object>) -> Unit,
    ) {
        val lease = owner.newLease()
        val logicalDone = AtomicBoolean()
        val terminal = AtomicBoolean()
    }

    private val lock = Any()
    private val waiting = ArrayDeque<Request>()
    private var flight: Request? = null
    private var retired = false
    private val clearError = AtomicReference<SettingsError?>(null)

    fun lastClearError(): SettingsError? = clearError.get()

    fun submit(command: TdApi.Function<*>, owner: SettingsProxyProbeOwner,
        result: (SettingsResult<TdApi.Object>) -> Unit,
    ) {
        val request = Request(command, owner, result)
        while (true) {
            // null means retired, an empty snapshot means this request was admitted.
            val snapshot = synchronized(lock) {
                if (retired) null
                else if (waiting.size < MAX_WAITING) { waiting.addLast(request); emptyList() }
                else waiting.toList()
            }
            if (snapshot == null) {
                request.lease.deliver { complete(request, SettingsResult(error = SettingsError.NOT_READY)) }
                return
            }
            if (snapshot.isEmpty()) { pump(); return }

            // owner.current() can acquire an owner lock; never call it with the queue lock held.
            val stale = snapshot.filterNot { it.owner.current() }
            if (stale.isEmpty()) {
                // A concurrent pump or admission changes the snapshot; retry in that case.
                val full = synchronized(lock) {
                    retired || (waiting.size >= MAX_WAITING && waiting.toList() == snapshot)
                }
                if (full) {
                    request.lease.deliver { complete(request, SettingsResult(error = SettingsError.NOT_READY)) }
                    return
                }
            } else {
                // The pump may have reserved a stale entry meanwhile; remove only entries
                // still waiting and leave that reserved request to its dispatch gate.
                val removed = synchronized(lock) { stale.filter { waiting.remove(it) } }
                removed.forEach { old ->
                    old.lease.deliver { complete(old, SettingsResult(error = SettingsError.NOT_READY)) }
                }
            }
        }
    }

    /** Retiring never releases an already submitted or possibly submitted request. */
    fun retire() {
        val pending = synchronized(lock) {
            retired = true
            waiting.toList().also { waiting.clear() }
        }
        pending.forEach { request ->
            request.lease.deliver { complete(request, SettingsResult(error = SettingsError.NOT_READY)) }
        }
    }

    private fun pump() {
        val request = synchronized(lock) {
            if (retired || flight != null || waiting.isEmpty()) null
            else waiting.removeFirst().also { flight = it }
        } ?: return
        try {
            val allowed = request.lease.dispatch {
                if (request.command is TdApi.OptimizeStorage) clearError.set(null)
                val submitted = send(request.command, { response ->
                    // Capture before lease delivery: synchronous send callbacks may
                    // be drained later, after the gate's thread-local scope ends.
                    val context = SettingsStorageCallbackContext.capture()
                    request.lease.deliver { raw(request, response, context) }
                }, {
                    // The sender's exception handler is not evidence of a native response.
                    val context = SettingsStorageCallbackContext.capture()
                    request.lease.deliver { uncertain(request, context) }
                })
                if (!submitted) request.lease.deliver { rejected(request) }
            }
            if (!allowed) request.lease.deliver { rejected(request) }
        } catch (_: Throwable) {
            // A throw can happen after client.send submitted. Keep the physical slot.
            request.lease.deliver { uncertain(request) }
        }
    }

    private fun raw(request: Request, response: TdApi.Object, context: SettingsStorageCallbackContext) {
        if (!request.terminal.compareAndSet(false, true)) return
        synchronized(lock) {
            // A real terminal supersedes this same flight's earlier uncertainty.
            if (flight === request && request.command is TdApi.OptimizeStorage)
                clearError.set(if (response is TdApi.StorageStatistics) null else settingsError(response))
            if (flight === request) flight = null
        }
        try { complete(request, SettingsResult(response), context) }
        finally { context.runNext { pump() } }
    }

    private fun rejected(request: Request) {
        if (!request.terminal.compareAndSet(false, true)) return
        synchronized(lock) { if (flight === request) flight = null }
        complete(request, SettingsResult(error = SettingsError.NOT_READY))
        pump()
    }

    private fun uncertain(request: Request, context: SettingsStorageCallbackContext = SettingsStorageCallbackContext.capture()) {
        val report = synchronized(lock) {
            if (request.terminal.get() || flight !== request) false else {
                if (request.command is TdApi.OptimizeStorage) clearError.compareAndSet(null, SettingsError.REQUEST_FAILED)
                true
            }
        }
        if (report) complete(request, SettingsResult(error = SettingsError.REQUEST_FAILED), context)
    }

    private fun complete(request: Request, value: SettingsResult<TdApi.Object>,
        context: SettingsStorageCallbackContext = SettingsStorageCallbackContext.capture(),
    ) {
        if (!request.logicalDone.compareAndSet(false, true)) return
        context.runDelivery {
            // The local blocked witness can precede asynchronous owner revocation.
            // Do not publish even a cleanup-state change through that narrow gap.
            if (context.current()) {
                val actual: SettingsResult<TdApi.Object> =
                    if (request.owner.current()) value else SettingsResult(error = SettingsError.NOT_READY)
                runCatching { request.result(actual) }
            }
        }
    }

    private companion object { const val MAX_WAITING = 16 }
}
