package com.badnng.moe.security

import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal fun interface LocalLockDeadlineHandle {
    fun cancel()
}

/** A schedule is one-shot and must not invoke its callback before returning. */
internal fun interface LocalLockDeadlineScheduler {
    fun schedule(delayMillis: Long, callback: () -> Unit): LocalLockDeadlineHandle
}

/** One sleeping daemon, started lazily by the executor; there is no polling or frame loop. */
internal class LocalLockExecutorDeadlineScheduler : LocalLockDeadlineScheduler, AutoCloseable {
    private val executor = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "nngGram-local-lock-deadline").apply { isDaemon = true }
    }.apply {
        removeOnCancelPolicy = true
        setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
    }

    override fun schedule(delayMillis: Long, callback: () -> Unit): LocalLockDeadlineHandle {
        require(delayMillis > 0)
        val future = executor.schedule(Runnable { callback() }, delayMillis, TimeUnit.MILLISECONDS)
        return LocalLockDeadlineHandle { future.cancel(false) }
    }

    override fun close() {
        executor.shutdownNow()
    }
}

internal data class LocalLockIdleConfig(
    /** True only while local protection is enabled and the lock is open. */
    val enabled: Boolean,
    val hasSession: Boolean,
    val epoch: Long,
    val timeoutSeconds: Long = 3_600,
) {
    init {
        require(timeoutSeconds in 1..Long.MAX_VALUE / 1_000)
    }
}

internal enum class LocalLockIdleAvailability {
    SYSTEM_SESSION,
    APPLICATION_ONLY,
    CLOCK_UNAVAILABLE,
    SCHEDULER_UNAVAILABLE,
}

internal enum class LocalLockIdleDueReason { IDLE, LATE, CLOCK_UNAVAILABLE, SCHEDULER_UNAVAILABLE }

/** An owner must claim this once on its serialized lock boundary, then recheck its witness. */
internal class LocalLockIdleDue internal constructor(
    val epoch: Long,
    val reason: LocalLockIdleDueReason,
    internal val serial: Long,
)

/**
 * Single one-shot deadline. Configuration changes and disposal revoke old callbacks
 * and unclaimed due tickets. [onDue] only requests the real owner to lock; it must
 * serialize [claimDue], witness validation and locking against configuration changes.
 * [onAvailabilityChanged] is a signal: observers read [availability] when handling
 * it, so a queued old notification cannot publish an old availability value.
 *
 * Only actual application input calls [recordAppActivity]; focus loss/gain is not
 * input. A host may call [recheck] after resume/activation without marking activity.
 */
internal class LocalLockIdleController(
    private val source: LocalLockIdleSource,
    private val scheduler: LocalLockDeadlineScheduler,
    private val onDue: (LocalLockIdleDue) -> Unit,
    private val onAvailabilityChanged: () -> Unit = {},
) : AutoCloseable {
    private val guard = Any()
    private var config: LocalLockIdleConfig? = null
    private var closed = false
    private var serial = 0L
    private var timer: LocalLockDeadlineHandle? = null
    private var deadline: Long? = null
    private var pendingDue: LocalLockIdleDue? = null
    private var dueClaimed = false
    private var lastNow: Long? = null
    private var lastApp: Long? = null
    private var systemInput = LocalLockLastInputTracker()
    private var currentAvailability = LocalLockIdleAvailability.APPLICATION_ONLY

    fun availability(): LocalLockIdleAvailability = synchronized(guard) { currentAvailability }

    /** A changed duration starts a fresh interval, as does opening a new epoch. */
    fun update(value: LocalLockIdleConfig) = transition {
        if (closed || config == value) return@transition null
        cancelTimerLocked()
        pendingDue = null
        dueClaimed = false
        config = value
        lastApp = null
        systemInput = LocalLockLastInputTracker()
        if (!eligibleLocked()) {
            currentAvailability = LocalLockIdleAvailability.APPLICATION_ONLY
            return@transition null
        }
        evaluateLocked(previousDeadline = null, initializeActivity = true)
    }

    /** Event-driven only. Does not query system input or rearm a timer per keystroke. */
    fun recordAppActivity() = transition {
        if (!eligibleLocked() || pendingDue != null || dueClaimed) return@transition null
        val now = readNowLocked() ?: return@transition clockFailureLocked()
        // A Windows timed wait may stop counting during sleep. If the host has not
        // yet reported a resume, the first real input still cannot erase an already
        // substantially late deadline. This does not substitute for a wake signal.
        val scheduled = deadline
        if (scheduled != null && now > scheduled && now - scheduled > 3_000) {
            return@transition dueLocked(LocalLockIdleDueReason.LATE)
        }
        lastApp = now
        null
    }

    /** Recompute after waking, clock-change signals or actual owner state updates. */
    fun recheck() = transition {
        if (!eligibleLocked() || pendingDue != null || dueClaimed) return@transition null
        val previousDeadline = deadline
        cancelTimerLocked()
        evaluateLocked(previousDeadline, initializeActivity = false)
    }

    /** Rejects old epoch/configuration, closed owner and duplicate delivery. */
    fun claimDue(due: LocalLockIdleDue): Boolean = synchronized(guard) {
        if (!eligibleLocked() || pendingDue !== due || due.serial != serial || config?.epoch != due.epoch) false
        else {
            pendingDue = null
            dueClaimed = true
            true
        }
    }

    override fun close() {
        synchronized(guard) {
            if (closed) return
            closed = true
            cancelTimerLocked()
            pendingDue = null
            config = null
        }
    }

    private fun timerFired(ticket: Long) = transition {
        if (!eligibleLocked() || ticket != serial || pendingDue != null || dueClaimed) return@transition null
        timer = null
        val previousDeadline = deadline
        deadline = null
        evaluateLocked(previousDeadline, initializeActivity = false)
    }

    private fun evaluateLocked(previousDeadline: Long?, initializeActivity: Boolean): LocalLockIdleDue? {
        // Read input before now: any actual input accepted here is at or before now.
        val raw = try { source.lastInputDword() } catch (_: Exception) { null } catch (_: LinkageError) { null }
        val now = readNowLocked() ?: return clockFailureLocked()
        val lastSystem = raw?.let { systemInput.observe(now, it) }
        currentAvailability = if (lastSystem == null) LocalLockIdleAvailability.APPLICATION_ONLY
            else LocalLockIdleAvailability.SYSTEM_SESSION
        if (initializeActivity || lastApp == null) lastApp = now
        // A previous valid system observation remains useful if the present query fails.
        val newest = maxOf(checkNotNull(lastApp), lastSystem ?: systemInput.previous() ?: 0L)
        val timeout = checkNotNull(config).timeoutSeconds * 1_000
        val late = previousDeadline != null && now > previousDeadline && now - previousDeadline > 3_000
        if (late) return dueLocked(LocalLockIdleDueReason.LATE)
        if (now - newest >= timeout) return dueLocked(LocalLockIdleDueReason.IDLE)
        val next = saturatedAdd(newest, timeout)
        deadline = next
        val ticket = ++serial
        return try {
            timer = scheduler.schedule((next - now).coerceAtLeast(1)) { timerFired(ticket) }
            null
        } catch (_: Exception) {
            currentAvailability = LocalLockIdleAvailability.SCHEDULER_UNAVAILABLE
            dueLocked(LocalLockIdleDueReason.SCHEDULER_UNAVAILABLE)
        } catch (_: LinkageError) {
            currentAvailability = LocalLockIdleAvailability.SCHEDULER_UNAVAILABLE
            dueLocked(LocalLockIdleDueReason.SCHEDULER_UNAVAILABLE)
        }
    }

    private fun readNowLocked(): Long? {
        val now = try { source.tickMillis() } catch (_: Exception) { null } catch (_: LinkageError) { null }
        if (now == null || now < 0 || lastNow?.let { now < it } == true) return null
        lastNow = now
        return now
    }

    private fun clockFailureLocked(): LocalLockIdleDue {
        currentAvailability = LocalLockIdleAvailability.CLOCK_UNAVAILABLE
        return dueLocked(LocalLockIdleDueReason.CLOCK_UNAVAILABLE)
    }

    private fun dueLocked(reason: LocalLockIdleDueReason): LocalLockIdleDue {
        cancelTimerLocked()
        val due = LocalLockIdleDue(checkNotNull(config).epoch, reason, serial)
        pendingDue = due
        return due
    }

    private fun eligibleLocked(): Boolean = !closed && config?.let { it.enabled && it.hasSession } == true

    private fun cancelTimerLocked() {
        ++serial
        timer?.cancel()
        timer = null
        deadline = null
    }

    private inline fun transition(block: () -> LocalLockIdleDue?) {
        val result = synchronized(guard) {
            val before = currentAvailability
            val due = block()
            (before != currentAvailability) to due
        }
        // A failing status observer must never suppress a security-relevant due request.
        try {
            if (result.first) onAvailabilityChanged()
        } finally {
            result.second?.let(onDue)
        }
    }

    private fun saturatedAdd(first: Long, second: Long): Long =
        if (first > Long.MAX_VALUE - second) Long.MAX_VALUE else first + second
}
