package com.badnng.moe.tdlib

import com.badnng.moe.settings.SettingsError
import com.badnng.moe.settings.SettingsResult
import com.badnng.moe.settings.SettingsToken
import com.badnng.moe.settings.SettingsUsernameAvailability
import java.util.concurrent.atomic.AtomicBoolean

/** Actor-owned physical request slot and latest pending query; cancelling a view does not cancel its RPC. */
internal class SettingsUsernameAvailabilityController(
    private val session: TdSettingsSession,
    private val enqueue: (() -> Unit) -> Unit,
    private val accepts: (SettingsToken, Long) -> Boolean,
) {
    private var retired = false
    private var running: Request? = null
    private var pending: Request? = null

    fun check(token: SettingsToken, userId: Long, username: String, current: () -> Boolean,
        result: (SettingsResult<SettingsUsernameAvailability>) -> Unit,
    ) {
        val request = Request(token, userId, username, current, result)
        if (!allowed(request)) {
            cancel(request)
            return
        }
        if (userId <= 0 || username.length > 32) {
            finishConsumer(request, SettingsResult(error = SettingsError.INVALID_VALUE))
            return
        }

        val oldPending = pending
        val oldRunning = running
        // Install before calling foreign code: a reentrant newer query can replace this one.
        pending = request
        oldPending?.source?.set(false)
        oldRunning?.source?.set(false)
        oldPending?.let(::cancel)
        oldRunning?.let(::cancel)
        pump()
    }

    fun retire() {
        if (retired) return
        retired = true
        val oldPending = pending
        pending = null
        running?.source?.set(false)
        oldPending?.source?.set(false)
        running?.let(::cancel)
        oldPending?.let(::cancel)
        // Keep the physical slot occupied until its actual callback, even though no new work is allowed.
    }

    private fun pump() {
        while (!retired && running == null) {
            val request = pending ?: return
            pending = null
            if (!allowed(request)) {
                cancel(request)
                continue
            }
            running = request
            val source = request.source
            val externalCurrent = request.current
            // No actor-owned fields, guard, snapshot or request collection is read by this predicate.
            val nativeCurrent = {
                if (!source.get()) false
                else if (!runCatching(externalCurrent).getOrDefault(false)) {
                    source.set(false)
                    false
                } else source.get()
            }
            fun nativeFinished(response: SettingsResult<SettingsUsernameAvailability>) {
                if (!request.nativeOnce.compareAndSet(false, true)) return
                enqueue { completed(request, response) }
            }
            try { session.checkUsername(request.userId, request.username, nativeCurrent, ::nativeFinished) }
            catch (_: Exception) { nativeFinished(SettingsResult(error = SettingsError.REQUEST_FAILED)) }
        }
    }

    private fun completed(request: Request, response: SettingsResult<SettingsUsernameAvailability>) {
        if (running !== request) return
        val mayPublish = allowed(request)
        val value = response.value
        val output: SettingsResult<SettingsUsernameAvailability> = when {
            !mayPublish -> SettingsResult(error = SettingsError.NOT_READY)
            response.error != null -> SettingsResult(error = response.error)
            value == null || value.userId != request.userId || value.username != request.username ->
                SettingsResult(error = SettingsError.REQUEST_FAILED)
            else -> SettingsResult(value)
        }
        running = null
        finishConsumer(request, output)
        pump()
    }

    /** Called only by the actor. The native predicate deliberately does not use this guard. */
    private fun allowed(request: Request): Boolean = !retired && request.source.get() &&
        accepts(request.token, request.userId) && runCatching(request.current).getOrDefault(false) && request.source.get()

    private fun cancel(request: Request) = finishConsumer(request, SettingsResult(error = SettingsError.NOT_READY))

    private fun finishConsumer(request: Request, response: SettingsResult<SettingsUsernameAvailability>) {
        request.source.set(false)
        if (request.consumerOnce.compareAndSet(false, true)) runCatching { request.result(response) }
    }

    /** Object identity is the query revision, including deletion/retyping of exactly the same username. */
    private class Request(val token: SettingsToken, val userId: Long, val username: String,
        val current: () -> Boolean, val result: (SettingsResult<SettingsUsernameAvailability>) -> Unit,
    ) {
        val source = AtomicBoolean(true)
        val consumerOnce = AtomicBoolean()
        val nativeOnce = AtomicBoolean()
    }
}
