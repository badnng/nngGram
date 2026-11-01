package com.badnng.moe.notifications

import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.WString

/** Raw, fakeable read-only ABI; tests supply memory without loading COM or shell32. */
internal interface WindowsFocusAssistAccess {
    fun userState(output: Pointer): Int
    fun create(clsid: String, iid: String): Pointer
    fun call(pointer: Pointer, slot: Int, vararg arguments: Any?): Int
    fun release(pointer: Pointer?)
    fun free(pointer: Pointer?)
}

private class JnaWindowsFocusAssistAccess(private val runtime: WindowsToastRuntime) : WindowsFocusAssistAccess {
    override fun userState(output: Pointer): Int = NativeLibrary.getInstance("shell32")
        .getFunction("SHQueryUserNotificationState").invokeInt(arrayOf(output))
    override fun create(clsid: String, iid: String): Pointer = runtime.create(clsid, iid)
    override fun call(pointer: Pointer, slot: Int, vararg arguments: Any?): Int = WindowsToastComAbi.call(pointer, slot, *arguments)
    override fun release(pointer: Pointer?) = WindowsToastComAbi.release(pointer)
    override fun free(pointer: Pointer?) = runtime.free(pointer)
}

internal data class WindowsNotificationPolicy(val focusBlocked: Boolean? = null, val userState: Int? = null) {
    fun skipSound(respectFocus: Boolean, locked: Boolean): Boolean = locked || userState == 1 || userState == 4 ||
        respectFocus && focusBlocked != false
    fun skipCustomToast(respectFocus: Boolean): Boolean = userState == 3 || userState == 4 ||
        respectFocus && focusBlocked != false
}

/** Fixed Desktop's read-only QuietHours COM projection, called only on an initialized MTA worker. */
internal class WindowsFocusAssistProbe(
    private val runtime: WindowsToastRuntime,
    private val aumid: String,
    private val nowNanos: () -> Long = System::nanoTime,
    private val access: WindowsFocusAssistAccess = JnaWindowsFocusAssistAccess(runtime),
) {
    private var lastQuery: Long? = null
    private var cached = WindowsNotificationPolicy()
    private var classUnavailable = false

    fun read(): WindowsNotificationPolicy {
        val now = nowNanos()
        val previous = lastQuery
        if (previous != null && now >= previous && now - previous < 1_000_000_000L) return cached
        lastQuery = now
        val userState = runCatching {
            Memory(4).use { output ->
                output.clear()
                WindowsToastComAbi.checkResult(access.userState(output))
                output.getInt(0).takeIf { it in 1..7 }
            }
        }.getOrNull()
        val focus = if (classUnavailable) null else try { focusBlocked() }
        catch (failure: WindowsToastNativeFailure) {
            if (failure.code == -2147221164) classUnavailable = true // REGDB_E_CLASSNOTREG
            null
        } catch (_: Exception) { null }
        return WindowsNotificationPolicy(focus, userState).also { cached = it }
    }

    private fun focusBlocked(): Boolean? {
        val settings = access.create("f53321fa-34f8-4b7f-b9a3-361877cb94cf", "6bff4732-81ec-4ffb-ae67-b6c1bc29631f")
        try {
            val profiles = mutableListOf<String>()
            var unknown = false
            stringProperty(settings, 12)?.let(profiles::add) ?: run { unknown = true } // Windows 11 DND
            val moments = pointerProperty(settings, 8)
            if (moments != null) try {
                Memory(4).use { id ->
                    id.clear()
                    if (access.call(moments, 6, id) < 0) unknown = true
                    else if (id.getInt(0) != 0) stringProperty(settings, 10)?.let(profiles::add) ?: run { unknown = true }
                }
            } finally { access.release(moments) } else unknown = true
            stringProperty(settings, 3)?.let(profiles::add) ?: run { unknown = true }
            if (profiles.any { value -> !value.endsWith(".alarmsonly", true) && !value.endsWith(".priorityonly", true) && !value.endsWith(".unrestricted", true) }) unknown = true
            val blocked = windowsFocusProfileBlocked(profiles, aumid) { chosen -> allowedApps(settings, chosen) }
            return if (blocked == true) true else if (unknown) null else blocked
        } finally { access.release(settings) }
    }

    private fun allowedApps(settings: Pointer, profile: String): Set<String>? {
        Memory(8).use { result ->
            result.clear()
            val profileStatus = access.call(settings, 5, WString(profile), result)
            val selected = result.getPointer(0) ?: return null
            if (profileStatus < 0) { access.release(selected); return null }
            try {
                Memory(4).use { count -> Memory(8).use { array ->
                    count.clear(); array.clear()
                    val status = access.call(selected, 11, count, array)
                    val entries = array.getPointer(0)
                    val size = count.getInt(0)
                    // Native COM memory is only traversed after a defensive count check.
                    try {
                        if (status < 0 || size !in 0..4_096 || size > 0 && entries == null) return null
                        val names = mutableSetOf<String>()
                        var invalid = false
                        for (index in 0 until size) {
                            val name = entries?.getPointer(index * 8L)
                            WindowsToastComAbi.wide(name, 512)?.let(names::add) ?: run { invalid = true }
                        }
                        return if (invalid) null else names
                    } finally {
                        if (entries != null && size in 0..4_096) {
                            for (index in 0 until size) runCatching { access.free(entries.getPointer(index * 8L)) }
                        }
                        access.free(entries)
                    }
                } }
            } finally { access.release(selected) }
        }
    }

    private fun stringProperty(pointer: Pointer, slot: Int): String? = Memory(8).use { result ->
        result.clear()
        val status = access.call(pointer, slot, result)
        val value = result.getPointer(0)
        try { if (status < 0) null else WindowsToastComAbi.wide(value, 512)?.takeIf { it.isNotBlank() } }
        finally { access.free(value) }
    }
    private fun pointerProperty(pointer: Pointer, slot: Int): Pointer? = Memory(8).use { result ->
        result.clear()
        val status = access.call(pointer, slot, result)
        val value = result.getPointer(0)
        if (status >= 0) value else { access.release(value); null }
    }
}

/** The selected profile alone is insufficient on Windows 11. Unknown profiles stay unknown. */
internal fun windowsFocusProfileBlocked(profiles: List<String>, aumid: String, allowedApps: (String) -> Set<String>?): Boolean? {
    val selected = profiles.filter(String::isNotBlank).maxByOrNull { profile ->
        when {
            profile.endsWith(".alarmsonly", true) -> 2
            profile.endsWith(".priorityonly", true) -> 1
            else -> 0
        }
    } ?: return null
    return when {
        selected.endsWith(".alarmsonly", true) -> true
        selected.endsWith(".priorityonly", true) -> allowedApps(selected)?.let { aumid !in it }
        selected.endsWith(".unrestricted", true) -> false
        else -> null
    }
}
