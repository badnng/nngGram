package com.badnng.moe.security

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer

/** Both values belong to the Windows boot-tick domain. Null means unavailable, not zero. */
internal interface LocalLockIdleSource {
    fun tickMillis(): Long?
    fun lastInputDword(): Long?
}

/** Injectable raw ABI; the fake can inspect LASTINPUTINFO without loading user32. */
internal interface WindowsLocalLockIdleAccess {
    fun tickMillis(): Long
    fun readLastInput(info: Pointer): Boolean
}

private class JnaWindowsLocalLockIdleAccess : WindowsLocalLockIdleAccess {
    override fun tickMillis(): Long = NativeLibrary.getInstance("kernel32")
        .getFunction("GetTickCount64", Function.ALT_CONVENTION)
        .invokeLong(emptyArray())

    override fun readLastInput(info: Pointer): Boolean = NativeLibrary.getInstance("user32")
        .getFunction("GetLastInputInfo", Function.ALT_CONVENTION)
        .invokeInt(arrayOf(info)) != 0
}

/**
 * Read-only and lazy: no hooks, windows, timers, session changes or input injection.
 * GetLastInputInfo is scoped to this process's Windows session. Tests inject a source
 * and never call this native implementation. Tick is read after LASTINPUTINFO when
 * evaluating a deadline so a just-arrived event cannot look like a future timestamp.
 */
internal class WindowsLocalLockIdleSource(
    private val access: WindowsLocalLockIdleAccess = JnaWindowsLocalLockIdleAccess(),
    private val available: Boolean = System.getProperty("os.name", "").startsWith("Windows", ignoreCase = true),
) : LocalLockIdleSource {

    override fun tickMillis(): Long? {
        if (!available) return null
        return nativeValue { access.tickMillis().takeIf { it >= 0 } }
    }

    override fun lastInputDword(): Long? {
        if (!available) return null
        return nativeValue {
            Memory(8).use { info ->
                info.clear()
                info.setInt(0, 8) // sizeof(LASTINPUTINFO): two DWORDs on Windows x64
                if (!access.readLastInput(info)) null else info.getInt(4).toLong() and DWORD_MASK
            }
        }
    }

    private inline fun <T> nativeValue(block: () -> T?): T? = try {
        block()
    } catch (_: LinkageError) {
        null
    } catch (_: Exception) {
        null
    }
}

internal const val DWORD_MASK = 0xffff_ffffL
private const val DWORD_PERIOD = 0x1_0000_0000L

/**
 * Reconstruct a 64-bit tick across ordinary DWORD rollover. A changed value which
 * projects behind the previously seen input may be a non-incrementing event: treat
 * its observation as activity now. If samples are at least one whole 49.7-day period
 * apart, the DWORD alone cannot distinguish old and new events. Mark that reading
 * unavailable until a later distinct event; never manufacture a recent input. An
 * impossible future sample during the first boot cycle is also unavailable.
 */
internal class LocalLockLastInputTracker {
    private var lastDword: Long? = null
    private var lastInput: Long? = null
    private var lastObservedAt: Long? = null
    private var ambiguous = false

    fun observe(now: Long, dword: Long): Long? {
        if (now < 0 || dword !in 0..DWORD_MASK) return null
        val observed = lastObservedAt
        if (observed != null && now < observed) return null
        if (observed != null && now - observed >= DWORD_PERIOD) {
            lastObservedAt = now
            lastDword = dword
            ambiguous = true
            return null
        }
        lastObservedAt = now
        if (dword == lastDword) return if (ambiguous) null else lastInput?.takeIf { it <= now }
        val age = ((now and DWORD_MASK) - dword) and DWORD_MASK
        if (age > now) return null
        val projected = now - age
        val previous = lastInput
        val result = if (ambiguous || (previous != null && projected < previous)) now else projected
        ambiguous = false
        lastDword = dword
        lastInput = result
        return result
    }

    fun previous(): Long? = lastInput
}
