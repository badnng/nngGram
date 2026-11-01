package com.badnng.moe.notifications

import com.sun.jna.Memory

/** Runs only in-memory fake COM vtables; never loads shell32/combase or creates a real toast. */
object WindowsToastNativeSmoke {
    fun handle(args: Array<String>): Boolean {
        if (args.firstOrNull() != "--windows-toast-fake-smoke") return false
        require(args.size == 1)
        run()
        println("WINDOWS_TOAST_FAKE_SMOKE_OK")
        return true
    }

    fun run() {
        var invoked = 0
        val iid = "a0fd3d7b-a38a-4b22-b413-328ec4c511a9"
        val callback = WindowsToastComObject(setOf(iid), listOf(ToastEventInvoke { _, _, _ -> invoked++; 19 }))
        check(callback.referenceCount == 1)
        check(WindowsToastComAbi.call(callback.pointer, 3, null, null) == 19 && invoked == 1)
        WindowsToastComAbi.guid(iid).use { requested -> Memory(8).use { output ->
            output.clear()
            check(WindowsToastComAbi.call(callback.pointer, 0, requested, output) == 0)
            check(callback.referenceCount == 2)
            check(output.getPointer(0) == callback.pointer)
            WindowsToastComAbi.release(output.getPointer(0))
        } }
        check(callback.referenceCount == 1)
        WindowsToastComAbi.guid(WindowsToastComAbi.IAGILE_OBJECT).use { requested -> Memory(8).use { output ->
            output.clear()
            check(WindowsToastComAbi.call(callback.pointer, 0, requested, output) == 0)
            check(output.getPointer(0) == callback.pointer && callback.referenceCount == 2)
            WindowsToastComAbi.release(output.getPointer(0))
        } }
        callback.releaseOwner()
        check(callback.referenceCount == 0)
        check(WindowsToastComAbi.call(callback.pointer, 1) == 0 && callback.referenceCount == 0)
        factoryCallbacks()
        check(windowsToastActivationNonce("nng1:" + "a".repeat(32)) == "a".repeat(32))
        check(windowsToastActivationNonce("nng1:invalid") == null)
    }

    /** Exercise the remaining stdcall signatures through real in-memory JNA function pointers. */
    fun factoryCallbacks() {
        var activated = 0
        var locks = 0
        val activator = WindowsToastComObject(setOf(WindowsToastComAbi.ACTIVATION_CALLBACK), listOf(
            ToastActivateInvoke { _, source, arguments, data, count ->
                check(source == null && arguments == null && data == null && count == 0)
                activated++
                0
            },
        ))
        val factory = WindowsToastComObject(setOf(WindowsToastComAbi.ICLASS_FACTORY), listOf(
            ToastCreateInstance { _, outer, iid, output ->
                output?.setPointer(0, null)
                if (output == null) WindowsToastComAbi.E_POINTER
                else if (outer != null) WindowsToastComAbi.CLASS_E_NOAGGREGATION
                else activator.query(iid, output)
            },
            ToastLockServer { _, lock -> locks += if (lock != 0) 1 else -1; 0 },
        ))
        try {
            check(WindowsToastComAbi.call(factory.pointer, 4, 1) == 0 && locks == 1)
            check(WindowsToastComAbi.call(factory.pointer, 4, 0) == 0 && locks == 0)
            WindowsToastComAbi.guid(WindowsToastComAbi.ACTIVATION_CALLBACK).use { iid -> Memory(8).use { output ->
                output.clear()
                check(WindowsToastComAbi.call(factory.pointer, 3, null, iid, output) == 0)
                val created = checkNotNull(output.getPointer(0))
                check(created == activator.pointer && activator.referenceCount == 2)
                try { check(WindowsToastComAbi.call(created, 3, null, null, null, 0) == 0 && activated == 1) }
                finally { WindowsToastComAbi.release(created) }
                check(activator.referenceCount == 1)
                output.setPointer(0, activator.pointer)
                check(WindowsToastComAbi.call(factory.pointer, 3, factory.pointer, iid, output) == WindowsToastComAbi.CLASS_E_NOAGGREGATION)
                check(output.getPointer(0) == null && activator.referenceCount == 1)
            } }
        } finally { factory.releaseOwner(); activator.releaseOwner() }
        check(factory.referenceCount == 0 && activator.referenceCount == 0)
    }
}
