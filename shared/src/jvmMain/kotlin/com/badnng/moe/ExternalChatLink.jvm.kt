package com.badnng.moe

import com.badnng.moe.chat.safeExternalLink
import com.badnng.moe.platform.DesktopPlatform
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.WString
import java.net.URI

/** Opens a validated URI using the platform's default handler. */
internal actual fun openExternalChatLink(url: String): Boolean {
    val safe = safeExternalLink(url) ?: return false
    return when {
        DesktopPlatform.current.isWindows -> runCatching {
            val function = NativeLibrary.getInstance("shell32").getFunction("ShellExecuteW")
            val result = function.invoke(Pointer::class.java, arrayOf(null, WString("open"), WString(safe), null, null, 1)) as? Pointer
            result != null && Pointer.nativeValue(result) > 32
        }.getOrDefault(false)
        else -> runCatching {
            val desktop = java.awt.Desktop.getDesktop()
            desktop.browse(URI.create(safe))
            true
        }.getOrDefault(false)
    }
}
