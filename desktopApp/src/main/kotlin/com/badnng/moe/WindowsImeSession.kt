@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.badnng.moe

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import com.sun.jna.Function
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import dev.nucleusframework.window.tao.LocalTaoWindow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Place at the actual editor so dialogs inherit their own Tao HWND, not the main window's. */
@Composable
fun WindowsImeTextInputHost(content: @Composable () -> Unit) {
    val window = LocalTaoWindow.current?.nativeHandle
    if (window == null || !System.getProperty("os.name").startsWith("Windows")) {
        content()
        return
    }
    val interceptor = remember(window) {
        PlatformTextInputInterceptor { request, nextHandler ->
            windowsImeSessions.withSession<Nothing>(window) { owner ->
                coroutineScope {
                    launch {
                        var hadText = false
                        snapshotFlow {
                            val value = request.value()
                            WindowsImeEditorShape(value.text.isEmpty(), value.composition != null)
                        }.collect { shape ->
                            if (!shape.empty) hadText = true
                            else if (!shape.composing && hadText) {
                                hadText = false
                                windowsImeSessions.editorCleared(owner) {
                                    val current = request.value()
                                    current.text.isEmpty() && current.composition == null
                                }
                            }
                        }
                    }
                    nextHandler.startInputMethod(request)
                }
            }
        }
    }
    InterceptPlatformTextInput(interceptor, content)
}

private data class WindowsImeEditorShape(val empty: Boolean, val composing: Boolean)
private val windowsImeSessions by lazy { WindowsImeSessionOwners(Win32ImeContext()) }

/** Borrowed native handles only. No string bytes, input mode changes or window subclassing. */
internal interface WindowsImeContext {
    fun isWindowThread(window: Long): Boolean
    fun acquire(window: Long): Long?
    fun compositionBytes(context: Long): Int
    fun cancel(context: Long)
    fun release(window: Long, context: Long)
}

internal class WindowsImeOwner internal constructor(val window: Long)

/** Per-HWND ownership is shared across fields; stale finalizers cannot cancel a newer field. */
internal class WindowsImeSessionOwners(private val native: WindowsImeContext) {
    private val owners = mutableMapOf<Long, WindowsImeOwner>()
    private val boundaries = mutableMapOf<Long, Any>()
    private val cancelling = mutableSetOf<Long>()

    fun begin(window: Long): WindowsImeOwner {
        val owner = WindowsImeOwner(window)
        synchronized(this) { owners[window] = owner; boundaries[window] = owner }
        cancelActive(window, owner)
        return owner
    }

    fun end(owner: WindowsImeOwner) {
        val boundary = synchronized(this) {
            if (owners[owner.window] !== owner) return
            owners.remove(owner.window) // Revoke first: ImmNotifyIME may synchronously re-enter focus/input.
            Any().also { boundaries[owner.window] = it }
        }
        cancelActive(owner.window, boundary)
        synchronized(this) {
            if (boundaries[owner.window] === boundary) boundaries.remove(owner.window)
        }
    }

    fun editorCleared(owner: WindowsImeOwner, stillEmpty: () -> Boolean) {
        if (!isCurrent(owner) || !runCatching(stillEmpty).getOrDefault(false) || !isCurrent(owner)) return
        cancelActive(owner.window, owner, stillEmpty)
    }

    @Synchronized fun isCurrent(owner: WindowsImeOwner): Boolean = owners[owner.window] === owner

    suspend fun <T> withSession(window: Long, operation: suspend (WindowsImeOwner) -> T): T {
        val owner = begin(window)
        try {
            if (!isCurrent(owner)) throw CancellationException("Text input session superseded")
            return operation(owner)
        } finally { end(owner) }
    }

    private fun cancelActive(window: Long, boundary: Any, stillValid: () -> Boolean = { true }) {
        synchronized(this) {
            if (boundaries[window] !== boundary || !cancelling.add(window)) return
        }
        try {
            if (!native.isWindowThread(window) || !isBoundary(window, boundary)) return
            val context = native.acquire(window) ?: return
            try {
                if (isBoundary(window, boundary) && native.compositionBytes(context) > 0 &&
                    isBoundary(window, boundary) && runCatching(stillValid).getOrDefault(false) &&
                    isBoundary(window, boundary)) {
                    native.cancel(context)
                }
            } finally { runCatching { native.release(window, context) } }
        } catch (_: Throwable) {
            // Native cleanup is best effort and must not replace the session's cancellation/error.
        } finally { synchronized(this) { cancelling.remove(window) } }
    }

    @Synchronized private fun isBoundary(window: Long, boundary: Any) = boundaries[window] === boundary
}

private class Win32ImeContext : WindowsImeContext {
    private val user = NativeLibrary.getInstance("user32")
    private val kernel = NativeLibrary.getInstance("kernel32")
    private val imm = NativeLibrary.getInstance("imm32")

    override fun isWindowThread(window: Long): Boolean {
        if (window == 0L) return false
        val owner = user.fn("GetWindowThreadProcessId").invokeInt(arrayOf(Pointer(window), null))
        return owner != 0 && owner == kernel.fn("GetCurrentThreadId").invokeInt(emptyArray())
    }

    override fun acquire(window: Long): Long? = imm.fn("ImmGetContext").invokePointer(arrayOf(Pointer(window)))
        ?.let(Pointer::nativeValue)?.takeIf { it != 0L }

    override fun compositionBytes(context: Long): Int = imm.fn("ImmGetCompositionStringW")
        .invokeInt(arrayOf(Pointer(context), GCS_COMPSTR, null, 0))

    override fun cancel(context: Long) {
        imm.fn("ImmNotifyIME").invokeInt(arrayOf(Pointer(context), NI_COMPOSITIONSTR, CPS_CANCEL, 0))
    }

    override fun release(window: Long, context: Long) {
        imm.fn("ImmReleaseContext").invokeInt(arrayOf(Pointer(window), Pointer(context)))
    }

    private fun NativeLibrary.fn(name: String) = getFunction(name, Function.ALT_CONVENTION)
    companion object {
        private const val GCS_COMPSTR = 0x0008
        private const val NI_COMPOSITIONSTR = 0x0015
        private const val CPS_CANCEL = 0x0004
    }
}
