package com.badnng.moe.notifications

import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.WString
import kotlin.test.*

class WindowsFocusAssistTest {
    @Test fun activeWindows11AndQuietMomentProfilesUseTheRealReadSlotsAndStrictestValue() = Fake().use { f ->
        f.active = "windows.alarmsonly"
        f.selected = "windows.unrestricted"
        f.moment = "windows.priorityonly"
        f.momentId = 2
        assertEquals(true, f.probe.read().focusBlocked)
        assertEquals(listOf("settings:12", "settings:8", "moments:6", "settings:10", "settings:3"), f.calls)
        assertEquals(2, f.released.size)
        assertTrue(f.allocations.isEmpty(), "Every returned profile string must be freed.")
    }

    @Test fun priorityUsesTheChosenProfileAndActualAumidAndFreesArrayStringsAndProfile() {
        for (allowed in listOf(listOf("owned.install"), listOf("different.install"))) Fake().use { f ->
            f.active = "windows.priorityonly"
            f.apps = allowed
            assertEquals("owned.install" !in allowed, f.probe.read().focusBlocked)
            assertEquals("windows.priorityonly", f.requestedProfile)
            assertEquals(listOf("settings:12", "settings:8", "moments:6", "settings:3", "settings:5", "profile:11"), f.calls)
            assertEquals(3, f.released.size)
            assertTrue(f.allocations.isEmpty())
        }
    }

    @Test fun unsupportedReadOrMalformedProfileCannotTurnAnUnknownWindows11StateIntoOff() {
        Fake().use { f ->
            f.failSlot = 12
            assertNull(f.probe.read().focusBlocked)
            assertTrue(f.allocations.isEmpty())
            assertEquals(2, f.released.size)
        }
        Fake().use { f ->
            f.active = "unrecognized.profile"
            f.selected = "unrecognized.profile"
            assertNull(f.probe.read().focusBlocked)
        }
        Fake().use { f ->
            f.nonterminatedActive = true
            assertNull(f.probe.read().focusBlocked)
            assertTrue(f.allocations.isEmpty(), "An overlong/unterminated COM string still belongs to the caller.")
        }
    }

    @Test fun badAllowedAppCountIsUnknownAndNeverTraversesUntrustedNativePointers() = Fake().use { f ->
        f.active = "windows.priorityonly"
        f.badAppCount = 4_097
        assertNull(f.probe.read().focusBlocked)
        assertTrue(f.allocations.isEmpty())
        assertEquals(3, f.released.size)
    }

    @Test fun queryIsEventDemandCachedAndClassNotRegisteredRemainsUnknownWithoutRepeatedCreation() = Fake().use { f ->
        f.createError = -2147221164
        assertNull(f.probe.read().focusBlocked)
        f.now = 999_999_999
        assertNull(f.probe.read().focusBlocked)
        assertEquals(1, f.userQueries)
        f.now = 1_000_000_000
        assertNull(f.probe.read().focusBlocked)
        assertEquals(2, f.userQueries)
        assertEquals(1, f.creates)
    }

    @Test fun foregroundOrFullscreenAndInvalidUserStateAreIndependentOfTheDndProfiles() {
        Fake().use { f ->
            f.user = 3 // D3D fullscreen affects custom toast, not the Focus Assist profile.
            val value = f.probe.read()
            assertEquals(false, value.focusBlocked)
            assertTrue(value.skipCustomToast(false))
            assertFalse(value.skipSound(true, false))
        }
        Fake().use { f ->
            f.user = 900
            assertNull(f.probe.read().userState)
        }
        Fake().use { f ->
            f.userError = -2147467263
            assertNull(f.probe.read().userState)
            assertEquals(false, f.probe.read().focusBlocked)
        }
    }

    @Test fun failedHresultsWithPartialOwnedOutputsStillReleaseEveryBoundedAllocation() {
        for (slot in listOf(8, 5, 11)) Fake().use { f ->
            f.active = "windows.priorityonly"
            f.apps = listOf("owned.install", "other.install")
            f.failAfterOutputSlot = slot
            assertNull(f.probe.read().focusBlocked)
            assertTrue(f.allocations.isEmpty(), "HRESULT failure at slot $slot leaked a returned allocation.")
            assertEquals(3, f.released.size)
        }
    }

    private class Fake : WindowsFocusAssistAccess, AutoCloseable {
        private val settings = Memory(8)
        private val moments = Memory(8)
        private val profile = Memory(8)
        val allocations = linkedMapOf<Long, Memory>()
        val calls = mutableListOf<String>()
        val released = mutableListOf<Long>()
        var now = 0L
        var active = "windows.unrestricted"
        var selected = "windows.unrestricted"
        var moment = "windows.unrestricted"
        var momentId = 0
        var apps = listOf<String>()
        var badAppCount: Int? = null
        var failSlot: Int? = null
        var failAfterOutputSlot: Int? = null
        var nonterminatedActive = false
        var requestedProfile: String? = null
        var createError: Int? = null
        var creates = 0
        var userQueries = 0
        var user = 5
        var userError = 0
        val probe = WindowsFocusAssistProbe(WindowsToastRuntime(), "owned.install", { now }, this)
        override fun userState(output: Pointer): Int { userQueries++; output.setInt(0, user); return userError }
        override fun create(clsid: String, iid: String): Pointer {
            assertEquals("f53321fa-34f8-4b7f-b9a3-361877cb94cf", clsid)
            assertEquals("6bff4732-81ec-4ffb-ae67-b6c1bc29631f", iid)
            creates++
            createError?.let { throw WindowsToastNativeFailure(it) }
            return settings
        }
        override fun call(pointer: Pointer, slot: Int, vararg arguments: Any?): Int {
            val name = when (pointer) { settings -> "settings"; moments -> "moments"; profile -> "profile"; else -> error("Unexpected fake interface") }
            calls += "$name:$slot"
            if (pointer == settings && slot == failSlot) return -2147467263
            val output = arguments.last() as Pointer
            when {
                pointer == settings && slot in listOf(12, 10, 3) -> {
                    val value = when (slot) { 12 -> active; 10 -> moment; else -> selected }
                    output.setPointer(0, if (slot == 12 && nonterminatedActive) allocate(1024).apply {
                        for (index in 0 until 512) setShort(index * 2L, 'x'.code.toShort())
                    } else string(value))
                }
                pointer == settings && slot == 8 -> output.setPointer(0, moments)
                pointer == moments && slot == 6 -> output.setInt(0, momentId)
                pointer == settings && slot == 5 -> {
                    requestedProfile = (arguments[0] as WString).toString()
                    output.setPointer(0, profile)
                }
                pointer == profile && slot == 11 -> {
                    (arguments[0] as Pointer).setInt(0, badAppCount ?: apps.size)
                    val entries = allocate(if (badAppCount != null) 8 else (apps.size.coerceAtLeast(1) * 8L))
                    if (badAppCount == null) apps.forEachIndexed { index, value -> entries.setPointer(index * 8L, string(value)) }
                    output.setPointer(0, entries)
                }
                else -> error("Unexpected fake read slot $name:$slot")
            }
            return if (slot == failAfterOutputSlot) -2147467263 else 0
        }
        private fun allocate(size: Long): Memory = Memory(size).also { it.clear(); allocations[Pointer.nativeValue(it)] = it }
        private fun string(value: String): Memory = allocate((value.length + 1) * 2L).apply {
            value.forEachIndexed { index, unit -> setShort(index * 2L, unit.code.toShort()) }
        }
        override fun release(pointer: Pointer?) { if (pointer != null) released += Pointer.nativeValue(pointer) }
        override fun free(pointer: Pointer?) {
            if (pointer == null) return
            checkNotNull(allocations.remove(Pointer.nativeValue(pointer))) { "Unknown or duplicate fake free" }.close()
        }
        override fun close() {
            allocations.values.forEach(Memory::close)
            allocations.clear()
            settings.close(); moments.close(); profile.close()
        }
    }
}
