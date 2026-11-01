package com.badnng.moe.notifications

import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary.StdCallCallback
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Fixed Windows SDK 10.0.26100.0 x64 ABI. This file never creates registrations or notifications. */
internal object WindowsToastComAbi {
    const val IUNKNOWN = "00000000-0000-0000-c000-000000000046"
    const val IAGILE_OBJECT = "94ea2b94-e9cc-49e0-c0ff-ee64ca8f5b90"
    const val ICLASS_FACTORY = "00000001-0000-0000-c000-000000000046"
    const val ACTIVATION_CALLBACK = "53e31837-6600-4a81-9395-75cffe746f94"
    const val MANAGER = "50ac103f-d235-4598-bbef-98fe4d1a3ad4"
    const val MANAGER2 = "7ab93c52-0e48-4750-ba9d-1a4113981847"
    const val NOTIFICATION_FACTORY = "04124b20-82c6-4229-b109-fd9ed4662b53"
    const val NOTIFICATION2 = "9dfb9fd1-143a-490e-90bf-b9fba7132de7"
    const val XML_DOCUMENT = "f7f3a506-1e87-42d6-bcfb-b8c809fa5494"
    const val XML_IO = "6cd0e74e-ee65-4489-9ebf-ca43e87ba637"
    const val ACTIVATED_HANDLER = "ab54de2d-97d9-5528-b6ad-105afe156530"
    const val DISMISSED_HANDLER = "61c2402f-0ed0-5a18-ab69-59f4aa99a368"
    const val FAILED_HANDLER = "95e3e803-c969-5e3a-9753-ea2ad22a9a33"
    const val E_NOINTERFACE = -2147467262
    const val E_POINTER = -2147467261
    const val CLASS_E_NOAGGREGATION = -2147221232

    fun guid(value: String): Memory {
        val uuid = UUID.fromString(value)
        return Memory(16).apply {
            setInt(0, (uuid.mostSignificantBits ushr 32).toInt())
            setShort(4, (uuid.mostSignificantBits ushr 16).toShort())
            setShort(6, uuid.mostSignificantBits.toShort())
            for (index in 0..7) setByte(8L + index, (uuid.leastSignificantBits ushr (56 - index * 8)).toByte())
        }
    }

    fun matches(pointer: Pointer?, iid: String): Boolean {
        if (pointer == null) return false
        return guid(iid).use { pointer.getByteArray(0, 16).contentEquals(it.getByteArray(0, 16)) }
    }

    fun call(pointer: Pointer, slot: Int, vararg arguments: Any?): Int {
        require(Native.POINTER_SIZE == 8)
        val table = pointer.getPointer(0) ?: error("COM vtable unavailable")
        val target = table.getPointer(slot.toLong() * 8) ?: error("COM method unavailable")
        return Function.getFunction(target, Function.ALT_CONVENTION).invokeInt(arrayOf(pointer, *arguments))
    }

    fun query(pointer: Pointer, iid: String): Pointer = guid(iid).use { identifier ->
        Memory(8).use { output ->
            output.clear()
            checkResult(call(pointer, 0, identifier, output))
            checkNotNull(output.getPointer(0))
        }
    }

    fun release(pointer: Pointer?) { if (pointer != null) call(pointer, 2) }
    fun checkResult(result: Int) { if (result < 0) throw WindowsToastNativeFailure(result) }

    fun wide(pointer: Pointer?, maximumUnits: Int = 512): String? {
        if (pointer == null) return null
        val value = StringBuilder()
        for (index in 0 until maximumUnits) {
            val unit = pointer.getShort(index * 2L).toInt().and(0xffff).toChar()
            if (unit == '\u0000') return value.toString()
            value.append(unit)
        }
        return null
    }
}

internal class WindowsToastNativeFailure(val code: Int) : RuntimeException("Windows notification operation failed")

/** Strong references outlive native AddRef, including callbacks arriving after owner retirement. */
internal class WindowsToastComObject(iids: Set<String>, extra: List<Callback>) {
    private val references = AtomicInteger(1)
    // Ownership uses atomics; immutable argument reads and event forwarding are RPC-thread safe.
    private val supported = iids + setOf(WindowsToastComAbi.IUNKNOWN, WindowsToastComAbi.IAGILE_OBJECT)
    private val query: ToastQueryInterface = ToastQueryInterface { _, iid, output ->
        if (output == null) WindowsToastComAbi.E_POINTER
        else if (supported.any { WindowsToastComAbi.matches(iid, it) } && tryRetain() > 0) {
            output.setPointer(0, pointer)
            0
        } else { output.setPointer(0, null); WindowsToastComAbi.E_NOINTERFACE }
    }
    private val addRef: ToastReference = ToastReference { _ -> tryRetain() }
    private val release: ToastReference = ToastReference { _ ->
        val remaining = references.updateAndGet { if (it > 0) it - 1 else 0 }
        if (remaining == 0) retained.remove(Pointer.nativeValue(pointer))
        remaining
    }
    private val callbacks = listOf<Callback>(query, addRef, release) + extra
    private val table = Memory(callbacks.size * 8L).apply {
        callbacks.forEachIndexed { index, callback -> setPointer(index * 8L, CallbackReference.getFunctionPointer(callback)) }
    }
    val pointer: Memory = Memory(8).apply { setPointer(0, table) }
    init { retained[Pointer.nativeValue(pointer)] = this }
    fun releaseOwner() { WindowsToastComAbi.call(pointer, 2) }
    fun query(iid: Pointer?, output: Pointer?): Int = query.invoke(pointer, iid, output)
    val referenceCount: Int get() = references.get()

    private fun tryRetain(): Int {
        while (true) {
            val previous = references.get()
            if (previous <= 0 || previous == Int.MAX_VALUE) return 0
            if (references.compareAndSet(previous, previous + 1)) return previous + 1
        }
    }

    private companion object { val retained = ConcurrentHashMap<Long, WindowsToastComObject>() }
}

internal fun interface ToastQueryInterface : StdCallCallback {
    fun invoke(self: Pointer?, iid: Pointer?, output: Pointer?): Int
}
internal fun interface ToastReference : StdCallCallback { fun invoke(self: Pointer?): Int }
internal fun interface ToastEventInvoke : StdCallCallback { fun invoke(self: Pointer?, sender: Pointer?, args: Pointer?): Int }
internal fun interface ToastActivateInvoke : StdCallCallback {
    fun invoke(self: Pointer?, aumid: Pointer?, arguments: Pointer?, data: Pointer?, count: Int): Int
}
internal fun interface ToastCreateInstance : StdCallCallback {
    fun invoke(self: Pointer?, outer: Pointer?, iid: Pointer?, output: Pointer?): Int
}
internal fun interface ToastLockServer : StdCallCallback { fun invoke(self: Pointer?, lock: Int): Int }

/** Worker-owned WinRT strings; calls into combase only after the backend was explicitly enabled. */
internal class WindowsToastRuntime {
    private val combase by lazy { NativeLibrary.getInstance("combase") }
    private val ole32 by lazy { NativeLibrary.getInstance("ole32") }
    private var initialized = false
    fun initialize() {
        val code = combase.getFunction("RoInitialize").invokeInt(arrayOf(1))
        WindowsToastComAbi.checkResult(code)
        initialized = true
    }
    fun uninitialize() {
        if (initialized) { combase.getFunction("RoUninitialize").invokeVoid(emptyArray()); initialized = false }
    }
    fun <T> string(value: String, block: (Pointer) -> T): T {
        Memory((value.length + 1) * 2L).use { buffer ->
            value.forEachIndexed { index, unit -> buffer.setShort(index * 2L, unit.code.toShort()) }
            buffer.setShort(value.length * 2L, 0)
            Memory(8).use { result ->
                result.clear()
                WindowsToastComAbi.checkResult(combase.getFunction("WindowsCreateString").invokeInt(arrayOf(buffer, value.length, result)))
                val handle = result.getPointer(0)
                // WindowsCreateString("") may return a null HSTRING; all our activation strings are nonempty.
                checkNotNull(handle)
                try { return block(handle) }
                finally { combase.getFunction("WindowsDeleteString").invokeInt(arrayOf(handle)) }
            }
        }
    }
    fun factory(className: String, iid: String): Pointer = string(className) { name ->
        WindowsToastComAbi.guid(iid).use { identifier -> Memory(8).use { result ->
            result.clear()
            WindowsToastComAbi.checkResult(combase.getFunction("RoGetActivationFactory").invokeInt(arrayOf(name, identifier, result)))
            checkNotNull(result.getPointer(0))
        } }
    }
    fun activate(className: String): Pointer = string(className) { name -> Memory(8).use { result ->
        result.clear()
        WindowsToastComAbi.checkResult(combase.getFunction("RoActivateInstance").invokeInt(arrayOf(name, result)))
        checkNotNull(result.getPointer(0))
    } }
    fun register(clsid: String, factory: Pointer): Int = WindowsToastComAbi.guid(clsid).use { identifier -> Memory(4).use { cookie ->
        cookie.clear()
        WindowsToastComAbi.checkResult(ole32.getFunction("CoRegisterClassObject").invokeInt(arrayOf(identifier, factory, 4, 1, cookie)))
        cookie.getInt(0)
    } }
    fun revoke(cookie: Int) { WindowsToastComAbi.checkResult(ole32.getFunction("CoRevokeClassObject").invokeInt(arrayOf(cookie))) }
    fun create(clsid: String, iid: String): Pointer = WindowsToastComAbi.guid(clsid).use { classId ->
        WindowsToastComAbi.guid(iid).use { interfaceId -> Memory(8).use { result ->
            result.clear()
            WindowsToastComAbi.checkResult(ole32.getFunction("CoCreateInstance").invokeInt(arrayOf(classId, null, 4, interfaceId, result)))
            checkNotNull(result.getPointer(0))
        } }
    }
    fun free(pointer: Pointer?) { if (pointer != null) ole32.getFunction("CoTaskMemFree").invokeVoid(arrayOf(pointer)) }
}
