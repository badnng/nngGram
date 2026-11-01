package com.badnng.moe.tdlib

import java.util.concurrent.atomic.AtomicBoolean

/** Process-wide builder-only admission; one native whole-catalog response at a time. */
internal class ProfileStickerAvatarLargeResponseGate(private val maxWaiting: Int = 32) {
    private val lock = Any()
    private val waiting = ArrayList<Request>()
    private var current: Permit? = null
    private var sequence = 0L
    private var preferredStreak = 0

    internal data class Request(val priority: Int, val sequence: Long, val start: (Permit) -> Unit)

    /** Removing an undispatched waiter never changes the physical current permit. */
    inner class Waiting internal constructor(private val request: Request) {
        fun pending(): Boolean = synchronized(lock) { waiting.contains(request) }
        fun cancel(): Boolean = synchronized(lock) { waiting.remove(request) }
    }

    inner class Permit internal constructor() {
        private val ended = AtomicBoolean()
        /** Call only after the response worker has exited and its payload holder is empty. */
        fun complete() {
            if (!ended.compareAndSet(false, true)) return
            val next = synchronized(lock) {
                check(current === this)
                current = null
                chooseLocked()
            }
            next?.first?.invoke(next.second)
        }
    }

    /** False means this exact request was not admitted to the bounded queue. */
    fun submit(preferred: Boolean, start: (Permit) -> Unit): Boolean = enqueue(preferred, start) != null

    fun enqueue(preferred: Boolean, start: (Permit) -> Unit): Waiting? {
        lateinit var request: Request
        val next = synchronized(lock) {
            if (waiting.size >= maxWaiting) return null
            check(sequence < Long.MAX_VALUE)
            request = Request(if (preferred) 0 else 1, ++sequence, start)
            waiting += request
            chooseLocked()
        }
        next?.first?.invoke(next.second)
        return Waiting(request)
    }

    private fun chooseLocked(): Pair<(Permit) -> Unit, Permit>? {
        if (current != null || waiting.isEmpty()) return null
        // After three preferred operations, the oldest waiting request gets a turn.
        val request = if (preferredStreak >= 3) waiting.minBy { it.sequence }
            else waiting.minWith(compareBy<Request>({ it.priority }, { it.sequence }))
        waiting.remove(request)
        preferredStreak = if (request.priority == 0) minOf(3, preferredStreak + 1) else 0
        val permit = Permit()
        current = permit
        return request.start to permit
    }
}

/** Only callers backed by the pinned, sequential official Java receiver may share this gate. */
internal object SharedProfileStickerAvatarLargeResponseGate {
    val gate = ProfileStickerAvatarLargeResponseGate()
}

/** This witness is supplied only by the pinned Client.ResponseReceiver transport. */
internal object ProfileStickerAvatarOrderedReceiver

/** Both signals are needed, including when the worker happens to finish before the callback body. */
internal class ProfileStickerAvatarLargeResponseExit(private val permit: ProfileStickerAvatarLargeResponseGate.Permit) {
    private val lock = Any()
    private var callbackDone = false
    private var workerDone = false
    private var released = false

    fun callbackBodyDone() = mark(callback = true)
    fun workerExited() = mark(callback = false)

    private fun mark(callback: Boolean) {
        val done = synchronized(lock) {
            if (callback) callbackDone = true else workerDone = true
            if (!released && callbackDone && workerDone) { released = true; true } else false
        }
        if (done) permit.complete()
    }
}
