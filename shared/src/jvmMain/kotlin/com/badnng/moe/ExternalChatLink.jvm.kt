package com.badnng.moe

import com.badnng.moe.chat.safeExternalLink
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.WString

/** ShellExecute receives a validated URI directly, never a command line or a local file. */
internal actual fun openExternalChatLink(url: String): Boolean {
    val safe = safeExternalLink(url) ?: return false
    return runCatching {
        val function = NativeLibrary.getInstance("shell32").getFunction("ShellExecuteW")
        val result = function.invoke(Pointer::class.java, arrayOf(null, WString("open"), WString(safe), null, null, 1)) as? Pointer
        result != null && Pointer.nativeValue(result) > 32
    }.getOrDefault(false)
}
