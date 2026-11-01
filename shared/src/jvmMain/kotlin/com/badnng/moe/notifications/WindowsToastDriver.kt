package com.badnng.moe.notifications

import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.WString
import java.util.concurrent.atomic.AtomicBoolean

internal sealed interface WindowsToastEvent {
    data class Activated(val nonce: String) : WindowsToastEvent
    data class Dismissed(val nonce: String, val byUser: Boolean) : WindowsToastEvent
    data class Failed(val nonce: String) : WindowsToastEvent
}

/** This driver is called on exactly one MTA worker. Tests inject a complete fake. */
internal interface WindowsToastDriver : AutoCloseable {
    fun initialize(aumid: String, clsid: String, event: (WindowsToastEvent) -> Unit)
    fun show(nonce: String, tag: String, group: String, title: String, text: String, restored: Boolean)
    /** Per-call immutable lease: a later account must never revive this call. */
    fun show(nonce: String, tag: String, group: String, title: String, text: String, restored: Boolean, stillCurrent: () -> Boolean) {
        if (stillCurrent()) show(nonce, tag, group, title, text, restored)
    }
    fun remove(nonce: String)
    fun policy(): WindowsNotificationPolicy
}

internal class JnaWindowsToastDriver(private val runtime: WindowsToastRuntime = WindowsToastRuntime()) : WindowsToastDriver {
    private val alive = AtomicBoolean()
    private var aumid = ""
    private var event: (WindowsToastEvent) -> Unit = {}
    private var manager: Pointer? = null
    private var manager2: Pointer? = null
    private var notifier: Pointer? = null
    private var history: Pointer? = null
    private var toastFactory: Pointer? = null
    private var classCookie: Int? = null
    private var classFactory: WindowsToastComObject? = null
    @Volatile private var activator: WindowsToastComObject? = null
    private var focus: WindowsFocusAssistProbe? = null
    private val records = linkedMapOf<String, NativeToast>()

    override fun initialize(aumid: String, clsid: String, event: (WindowsToastEvent) -> Unit) {
        check(!alive.get())
        this.aumid = aumid
        this.event = event
        try {
            runtime.initialize()
            require(aumid.length in 1..128)
            WindowsToastComAbi.checkResult(NativeLibrary.getInstance("shell32").getFunction("SetCurrentProcessExplicitAppUserModelID")
                .invokeInt(arrayOf(WString(aumid))))
            alive.set(true)
            val activate = ToastActivateInvoke { _, source, args, _, count ->
                if (alive.get() && count in 0..32 && WindowsToastComAbi.wide(source) == aumid) {
                    val nonce = WindowsToastComAbi.wide(args)?.let(::windowsToastActivationNonce)
                    if (nonce != null) event(WindowsToastEvent.Activated(nonce))
                }
                0
            }
            activator = WindowsToastComObject(setOf(WindowsToastComAbi.ACTIVATION_CALLBACK), listOf(activate))
            val create = ToastCreateInstance { _, outer, iid, output ->
                output?.setPointer(0, null)
                if (output == null) WindowsToastComAbi.E_POINTER
                else if (outer != null) WindowsToastComAbi.CLASS_E_NOAGGREGATION
                else if (!alive.get()) WindowsToastComAbi.E_NOINTERFACE
                else activator?.query(iid, output) ?: WindowsToastComAbi.E_NOINTERFACE
            }
            classFactory = WindowsToastComObject(setOf(WindowsToastComAbi.ICLASS_FACTORY), listOf(create, ToastLockServer { _, _ -> 0 }))
            classCookie = runtime.register(clsid, checkNotNull(classFactory).pointer)
            manager = runtime.factory("Windows.UI.Notifications.ToastNotificationManager", WindowsToastComAbi.MANAGER)
            manager2 = runtime.factory("Windows.UI.Notifications.ToastNotificationManager", WindowsToastComAbi.MANAGER2)
            notifier = runtime.string(aumid) { id -> pointerOut(checkNotNull(manager), 7, id) }
            Memory(4).use { setting ->
                setting.clear()
                WindowsToastComAbi.checkResult(WindowsToastComAbi.call(checkNotNull(notifier), 8, setting))
                if (setting.getInt(0) != 0) throw WindowsToastNativeFailure(-1)
            }
            history = pointerOut(checkNotNull(manager2), 6)
            // AUMID belongs to this installation. Clear stale previous-process artwork before
            // restoring the actual current native groups silently; it grants no read/dismiss.
            runtime.string(aumid) { WindowsToastComAbi.checkResult(WindowsToastComAbi.call(checkNotNull(history), 12, it)) }
            toastFactory = runtime.factory("Windows.UI.Notifications.ToastNotification", WindowsToastComAbi.NOTIFICATION_FACTORY)
            focus = WindowsFocusAssistProbe(runtime, aumid)
        } catch (failure: Exception) { close(); throw failure }
    }

    override fun show(nonce: String, tag: String, group: String, title: String, text: String, restored: Boolean) {
        show(nonce, tag, group, title, text, restored) { true }
    }

    override fun show(nonce: String, tag: String, group: String, title: String, text: String, restored: Boolean, stillCurrent: () -> Boolean) {
        val lease = WindowsToastShowLease { alive.get() && stillCurrent() }
        lease.check()
        require(nonce.matches(Regex("[a-f0-9]{32}")) && tag.length in 1..16 && group.length in 1..16)
        remove(nonce)
        lease.check()
        val instance = runtime.activate("Windows.Data.Xml.Dom.XmlDocument")
        var document: Pointer? = null
        var io: Pointer? = null
        var notification: Pointer? = null
        val handlers = mutableListOf<NativeHandler>()
        try {
            lease.check()
            document = WindowsToastComAbi.query(instance, WindowsToastComAbi.XML_DOCUMENT)
            lease.check()
            io = WindowsToastComAbi.query(instance, WindowsToastComAbi.XML_IO)
            lease.check()
            runtime.string(windowsToastXml(nonce, title, text)) { xml ->
                lease.check()
                WindowsToastComAbi.checkResult(WindowsToastComAbi.call(checkNotNull(io), 6, xml))
            }
            lease.check()
            notification = pointerOut(checkNotNull(toastFactory), 6, document)
            lease.check()
            val extra = WindowsToastComAbi.query(notification, WindowsToastComAbi.NOTIFICATION2)
            try {
                lease.check()
                runtime.string(tag) { lease.check(); WindowsToastComAbi.checkResult(WindowsToastComAbi.call(extra, 6, it)) }
                lease.check()
                runtime.string(group) { lease.check(); WindowsToastComAbi.checkResult(WindowsToastComAbi.call(extra, 8, it)) }
                lease.check()
                WindowsToastComAbi.checkResult(WindowsToastComAbi.call(extra, 10, if (restored) 1.toByte() else 0.toByte()))
            } finally { WindowsToastComAbi.release(extra) }
            lease.check()
            handlers += handler(notification, WindowsToastComAbi.ACTIVATED_HANDLER, 11, 12) { _ ->
                if (lease.current()) event(WindowsToastEvent.Activated(nonce))
            }
            lease.check()
            handlers += handler(notification, WindowsToastComAbi.DISMISSED_HANDLER, 9, 10) { args ->
                val reason = if (args == null) null else runCatching { Memory(4).use { output ->
                    WindowsToastComAbi.checkResult(WindowsToastComAbi.call(args, 6, output)); output.getInt(0)
                } }.getOrNull()
                if (lease.current()) event(WindowsToastEvent.Dismissed(nonce, reason == 0))
            }
            lease.check()
            handlers += handler(notification, WindowsToastComAbi.FAILED_HANDLER, 13, 14) { _ -> if (lease.current()) event(WindowsToastEvent.Failed(nonce)) }
            lease.check()
            records[nonce] = NativeToast(notification, tag, group, handlers.toList())
            lease.check()
            WindowsToastComAbi.checkResult(WindowsToastComAbi.call(checkNotNull(notifier), 6, notification))
            lease.check() // A synchronous Show already entered can finish; immediately remove its retired result.
            notification = null // records now owns this reference
        } catch (failure: Exception) {
            if (nonce in records) { notification = null; runCatching { remove(nonce) } }
            else if (notification != null) detach(notification, handlers)
            throw failure
        } finally {
            WindowsToastComAbi.release(notification)
            WindowsToastComAbi.release(io)
            WindowsToastComAbi.release(document)
            WindowsToastComAbi.release(instance)
        }
    }

    override fun remove(nonce: String) {
        val record = records.remove(nonce) ?: return
        try {
            notifier?.let { runCatching { WindowsToastComAbi.checkResult(WindowsToastComAbi.call(it, 7, record.notification)) } }
            history?.let { store -> runtime.string(record.tag) { tag -> runtime.string(record.group) { group -> runtime.string(aumid) { id ->
                WindowsToastComAbi.checkResult(WindowsToastComAbi.call(store, 8, tag, group, id))
            } } } }
        } finally {
            detach(record.notification, record.handlers)
            WindowsToastComAbi.release(record.notification)
        }
    }

    override fun policy(): WindowsNotificationPolicy = focus?.read() ?: WindowsNotificationPolicy()

    override fun close() {
        alive.set(false) // late COM callbacks lose authority before any native reference is released
        records.keys.toList().forEach { runCatching { remove(it) } }
        classCookie?.let { runCatching { runtime.revoke(it) } }
        classCookie = null
        classFactory?.releaseOwner(); classFactory = null
        activator?.releaseOwner(); activator = null
        listOf(toastFactory, history, notifier, manager2, manager).forEach { runCatching { WindowsToastComAbi.release(it) } }
        toastFactory = null; history = null; notifier = null; manager2 = null; manager = null; focus = null
        event = {}
        runtime.uninitialize()
    }

    private fun handler(notification: Pointer, iid: String, add: Int, remove: Int, invoke: (Pointer?) -> Unit): NativeHandler {
        val callback = WindowsToastComObject(setOf(iid), listOf(ToastEventInvoke { _, _, args ->
            if (alive.get()) runCatching { invoke(args) }
            0
        }))
        try {
            Memory(8).use { token ->
                token.clear()
                WindowsToastComAbi.checkResult(WindowsToastComAbi.call(notification, add, callback.pointer, token))
                return NativeHandler(callback, token.getLong(0), remove)
            }
        } catch (failure: Exception) { callback.releaseOwner(); throw failure }
    }

    private fun detach(notification: Pointer, handlers: List<NativeHandler>) {
        handlers.forEach { record ->
            runCatching { WindowsToastComAbi.checkResult(WindowsToastComAbi.call(notification, record.removeSlot, record.token)) }
            record.callback.releaseOwner()
        }
    }
    private fun pointerOut(owner: Pointer, slot: Int, vararg arguments: Any?): Pointer = Memory(8).use { output ->
        output.clear()
        WindowsToastComAbi.checkResult(WindowsToastComAbi.call(owner, slot, *arguments, output))
        checkNotNull(output.getPointer(0))
    }
    private data class NativeHandler(val callback: WindowsToastComObject, val token: Long, val removeSlot: Int)
    private data class NativeToast(val notification: Pointer, val tag: String, val group: String, val handlers: List<NativeHandler>)
}

internal class WindowsToastShowLease(private val stillCurrent: () -> Boolean) {
    fun current(): Boolean = stillCurrent()
    fun check() { if (!current()) throw WindowsToastRetired() }
}
internal class WindowsToastRetired : RuntimeException("Windows notification owner retired")

internal fun windowsToastActivationNonce(arguments: String): String? = arguments.removePrefix("nng1:")
    .takeIf { arguments.startsWith("nng1:") && it.matches(Regex("[a-f0-9]{32}")) }

internal fun windowsToastXml(nonce: String, title: String, text: String): String {
    require(nonce.matches(Regex("[a-f0-9]{32}")))
    fun safe(value: String): String = buildString(value.length.coerceAtMost(16_384)) {
        var index = 0
        val limit = value.length.coerceAtMost(16_384)
        while (index < limit) {
            val unit = value[index++]
            when {
                unit.isHighSurrogate() -> {
                    if (index < limit && value[index].isLowSurrogate()) { append(unit); append(value[index++]) }
                    else append('\uFFFD')
                }
                unit.isLowSurrogate() -> append('\uFFFD')
                else -> when (unit) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> if (unit == '\n' || unit == '\r' || unit == '\t' || unit.code >= 0x20 && unit != '\uFFFE' && unit != '\uFFFF') append(unit)
                }
            }
        }
    }
    return "<toast launch=\"nng1:$nonce\"><visual><binding template=\"ToastGeneric\"><text>${safe(title)}</text><text>${safe(text)}</text></binding></visual><audio silent=\"true\"/></toast>"
}
