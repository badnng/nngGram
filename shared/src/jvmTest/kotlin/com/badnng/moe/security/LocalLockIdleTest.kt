package com.badnng.moe.security

import com.sun.jna.Pointer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** All clock samples, task delivery and cancellation are synthetic. No Win32 input is queried. */
class LocalLockIdleTest {
    @Test
    fun windowsAdapterUsesTheReadOnlyAbiAndPreservesUnsignedDword() {
        var calls = 0
        val access = object : WindowsLocalLockIdleAccess {
            override fun tickMillis(): Long = 4_294_967_300L
            override fun readLastInput(info: Pointer): Boolean {
                calls++
                assertEquals(8, info.getInt(0), "cbSize must match the Windows structure.")
                info.setInt(4, 0xffff_fffeL.toInt())
                return true
            }
        }
        val source = WindowsLocalLockIdleSource(access, available = true)
        assertEquals(4_294_967_300L, source.tickMillis())
        assertEquals(4_294_967_294L, source.lastInputDword())
        assertEquals(1, calls)

        val failing = WindowsLocalLockIdleSource(object : WindowsLocalLockIdleAccess {
            override fun tickMillis(): Long = throw IllegalStateException("synthetic")
            override fun readLastInput(info: Pointer): Boolean = false
        }, available = true)
        assertEquals(null, failing.tickMillis())
        assertEquals(null, failing.lastInputDword())
        val unsupported = WindowsLocalLockIdleSource(access, available = false)
        assertEquals(null, unsupported.tickMillis())
        assertEquals(null, unsupported.lastInputDword())
        assertEquals(1, calls, "Unsupported hosts never touch the native boundary.")
    }

    @Test
    fun presetAndCustomIntervalsStartOneDeadlineAndRejectInvalidLengths() {
        for (seconds in listOf(60L, 300L, 3_600L, 18_000L, 10 * 3_600L + 15 * 60L)) {
            val fixture = Fixture()
            fixture.controller.update(LocalLockIdleConfig(true, true, 4, seconds))
            assertEquals(seconds * 1_000, fixture.timer.tasks.single().delay)
            assertEquals(1, fixture.source.inputReads)
            assertEquals(1, fixture.timer.liveCount())
        }
        assertEquals(3_600L, LocalLockIdleConfig(true, true, 1).timeoutSeconds)
        assertFailsWith<IllegalArgumentException> { LocalLockIdleConfig(true, true, 1, 0) }
        assertFailsWith<IllegalArgumentException> { LocalLockIdleConfig(true, true, 1, Long.MAX_VALUE) }
    }

    @Test
    fun dueCheckUsesNewestSystemOrApplicationActivityWithoutPollingForEachInput() {
        val fixture = Fixture()
        fixture.controller.update(LocalLockIdleConfig(true, true, 4, 60))
        fixture.source.now = 30_000
        repeat(12) { fixture.controller.recordAppActivity() }
        assertEquals(1, fixture.source.inputReads, "Input events must not become system idle polling.")
        assertEquals(1, fixture.timer.tasks.size, "Activity does not create a new timer per event.")

        // Other applications also received input, more recently than this application.
        fixture.source.now = 61_000
        fixture.source.input = 50_000
        fixture.timer.fire(0)
        assertTrue(fixture.due.isEmpty())
        assertEquals(49_000L, fixture.timer.tasks.last().delay)
        assertEquals(1, fixture.timer.liveCount())

        fixture.source.now = 110_000
        fixture.timer.fire(1)
        assertEquals(LocalLockIdleDueReason.IDLE, fixture.due.single().reason)
        assertTrue(fixture.controller.claimDue(fixture.due.single()))
        assertFalse(fixture.controller.claimDue(fixture.due.single()))
        fixture.controller.recordAppActivity()
        fixture.controller.recheck()
        assertEquals(1, fixture.due.size)
        assertEquals(0, fixture.timer.liveCount())
    }

    @Test
    fun applicationActivityCanOutliveAnOlderSystemInput() {
        val fixture = Fixture()
        fixture.controller.update(LocalLockIdleConfig(true, true, 4, 60))
        fixture.source.now = 45_000
        fixture.controller.recordAppActivity()
        fixture.source.now = 61_000
        fixture.source.input = 20_000
        fixture.timer.fire(0)
        assertEquals(44_000L, fixture.timer.tasks.last().delay)
        fixture.source.now = 105_000
        fixture.timer.fire(1)
        assertEquals(LocalLockIdleDueReason.IDLE, fixture.due.single().reason)
    }

    @Test
    fun durationChangeRestartsFromNowAndCancelledCallbackCannotAct() {
        val fixture = Fixture()
        fixture.controller.update(LocalLockIdleConfig(true, true, 1, 60))
        fixture.source.now = 55_000
        fixture.controller.update(LocalLockIdleConfig(true, true, 1, 300))
        assertTrue(fixture.timer.tasks[0].cancelled)
        assertEquals(300_000L, fixture.timer.tasks[1].delay)
        fixture.source.now = 61_000
        fixture.timer.fire(0) // The operating system can deliver cancellation late.
        assertTrue(fixture.due.isEmpty())
        assertEquals(1, fixture.timer.liveCount())

        fixture.source.now = 90_000
        fixture.controller.update(LocalLockIdleConfig(true, true, 1, 60))
        assertEquals(60_000L, fixture.timer.tasks[2].delay)
        fixture.source.now = 150_000
        fixture.timer.fire(2)
        assertEquals(LocalLockIdleDueReason.IDLE, fixture.due.single().reason)
    }

    @Test
    fun disablingChangingEpochAndClosingRevokeBothScheduledAndAlreadyDeliveredTickets() {
        val fixture = Fixture()
        fixture.controller.update(LocalLockIdleConfig(true, true, 7, 60))
        fixture.source.now = 61_000
        fixture.timer.fire(0)
        val stale = fixture.due.single()
        fixture.controller.update(LocalLockIdleConfig(true, true, 8, 60))
        assertFalse(fixture.controller.claimDue(stale))
        fixture.timer.fire(0)
        assertEquals(1, fixture.due.size)

        fixture.controller.update(LocalLockIdleConfig(true, false, 8, 60))
        fixture.timer.fire(1)
        assertEquals(1, fixture.due.size)
        assertEquals(0, fixture.timer.liveCount())
        fixture.controller.update(LocalLockIdleConfig(true, true, 9, 60))
        fixture.controller.close()
        fixture.timer.fire(2)
        assertEquals(1, fixture.due.size)
        assertFalse(fixture.controller.claimDue(stale))
    }

    @Test
    fun slightlyLateCallbackChecksActivityButOverThreeSecondsLateLocks() {
        val slight = Fixture()
        slight.controller.update(LocalLockIdleConfig(true, true, 1, 60))
        slight.source.now = 64_000 // Exactly 3000 ms after the original deadline.
        slight.source.input = 63_500
        slight.timer.fire(0)
        assertTrue(slight.due.isEmpty())
        assertEquals(59_500L, slight.timer.tasks.last().delay)

        val late = Fixture()
        late.controller.update(LocalLockIdleConfig(true, true, 1, 60))
        late.source.now = 64_001
        late.source.input = 64_000
        late.timer.fire(0)
        assertEquals(LocalLockIdleDueReason.LATE, late.due.single().reason)
    }

    @Test
    fun resumeRechecksWithoutTreatingFocusAsActivityAndHandlesLongSleep() {
        val fixture = Fixture()
        fixture.controller.update(LocalLockIdleConfig(true, true, 5, 60))
        fixture.source.now = (1L shl 32) + 20_000 // Tick includes a long suspension.
        fixture.controller.recheck()
        assertEquals(LocalLockIdleDueReason.LATE, fixture.due.single().reason)
        assertTrue(fixture.timer.tasks[0].cancelled)
        fixture.timer.fire(0)
        assertEquals(1, fixture.due.size)
    }

    @Test
    fun focusRecheckAloneDoesNotResetTheIdleDeadline() {
        val fixture = Fixture()
        fixture.controller.update(LocalLockIdleConfig(true, true, 5, 60))
        fixture.source.now = 30_000
        fixture.controller.recheck()
        assertTrue(fixture.due.isEmpty())
        assertEquals(31_000L, fixture.timer.tasks.last().delay)
        fixture.source.now = 61_000
        fixture.timer.fire(1)
        assertEquals(LocalLockIdleDueReason.IDLE, fixture.due.single().reason)
    }

    @Test
    fun firstInputAfterAStalledLateDeadlineCannotEraseIt() {
        val fixture = Fixture()
        fixture.controller.update(LocalLockIdleConfig(true, true, 4, 60))
        fixture.source.now = 64_001 // Callback has not been delivered after the deadline.
        fixture.controller.recordAppActivity()
        assertEquals(LocalLockIdleDueReason.LATE, fixture.due.single().reason)
        assertTrue(fixture.timer.tasks[0].cancelled)
        fixture.timer.fire(0)
        assertEquals(1, fixture.due.size)
    }

    @Test
    fun dwordUnwrapHandlesRolloverNonIncrementingEventsAndVeryLongUnchangedInput() {
        val wrap = 1L shl 32
        val tracker = LocalLockLastInputTracker()
        assertEquals(wrap - 150, tracker.observe(wrap - 50, DWORD_MASK - 149))
        assertEquals(wrap + 50, tracker.observe(wrap + 100, 50))
        // An older injected tick is nevertheless evidence of a new input observation.
        assertEquals(wrap + 130, tracker.observe(wrap + 130, 25))

        val unchanged = LocalLockLastInputTracker()
        assertEquals(800L, unchanged.observe(1_000, 800))
        assertEquals(null, unchanged.observe(wrap + 5_000, 800),
            "An entire unsampled DWORD cycle is ambiguous and must report fallback.")
        assertEquals(800L, unchanged.previous(), "Only the last known input may be retained.")
        assertEquals(null, unchanged.observe(wrap + 6_000, 800))
        assertEquals(wrap + 6_100, unchanged.observe(wrap + 6_100, 6_050))
        assertEquals(null, LocalLockLastInputTracker().observe(1_000, 2_000))
        assertEquals(null, LocalLockLastInputTracker().observe(1_000, DWORD_MASK + 1))
    }

    @Test
    fun queryFailureExposesFallbackAndRetainsOnlyValidObservedSystemActivity() {
        val fixture = Fixture()
        fixture.controller.update(LocalLockIdleConfig(true, true, 2, 60))
        assertEquals(LocalLockIdleAvailability.SYSTEM_SESSION, fixture.controller.availability())
        fixture.source.now = 20_000
        fixture.source.input = 19_000
        fixture.controller.recheck()
        fixture.source.now = 30_000
        fixture.controller.recordAppActivity()
        fixture.source.now = 79_000
        fixture.source.input = null
        fixture.timer.fire(1)
        assertEquals(LocalLockIdleAvailability.APPLICATION_ONLY, fixture.controller.availability())
        assertTrue(fixture.due.isEmpty())
        assertEquals(11_000L, fixture.timer.tasks.last().delay)
        fixture.source.now = 90_000
        fixture.timer.fire(2)
        assertEquals(LocalLockIdleDueReason.IDLE, fixture.due.single().reason)
        assertTrue(fixture.availabilityNotifications >= 2)
    }

    @Test
    fun clockFailureOrBackwardTickFailsClosedWithoutMixingClockDomains() {
        val fixture = Fixture()
        fixture.controller.update(LocalLockIdleConfig(true, true, 3, 60))
        fixture.source.now = 999 // Initial sample was 1000.
        fixture.controller.recordAppActivity()
        assertEquals(LocalLockIdleAvailability.CLOCK_UNAVAILABLE, fixture.controller.availability())
        assertEquals(LocalLockIdleDueReason.CLOCK_UNAVAILABLE, fixture.due.single().reason)
        fixture.timer.fire(0)
        assertEquals(1, fixture.due.size)
        assertTrue(fixture.controller.claimDue(fixture.due.single()))

        val missing = Fixture()
        missing.source.now = null
        missing.controller.update(LocalLockIdleConfig(true, true, 9, 60))
        assertEquals(LocalLockIdleDueReason.CLOCK_UNAVAILABLE, missing.due.single().reason)
        assertTrue(missing.timer.tasks.isEmpty())
    }

    @Test
    fun schedulerFailureRequestsLockAndDisabledConfigurationDoesNoReads() {
        val fixture = Fixture(failScheduling = true)
        fixture.controller.update(LocalLockIdleConfig(false, true, 1, 60))
        fixture.controller.recordAppActivity()
        fixture.controller.recheck()
        assertEquals(0, fixture.source.tickReads)
        assertEquals(0, fixture.source.inputReads)
        fixture.controller.update(LocalLockIdleConfig(true, true, 2, 60))
        assertEquals(LocalLockIdleAvailability.SCHEDULER_UNAVAILABLE, fixture.controller.availability())
        assertEquals(LocalLockIdleDueReason.SCHEDULER_UNAVAILABLE, fixture.due.single().reason)
        assertTrue(fixture.controller.claimDue(fixture.due.single()))
    }

    @Test
    fun failingAvailabilityObserverCannotSuppressAClockFailureLockRequest() {
        val source = FakeSource().apply { now = null }
        val timer = FakeScheduler(false)
        val due = mutableListOf<LocalLockIdleDue>()
        val controller = LocalLockIdleController(source, timer, { due += it }) {
            error("synthetic status observer failure")
        }
        assertFailsWith<IllegalStateException> { controller.update(LocalLockIdleConfig(true, true, 42, 60)) }
        assertEquals(LocalLockIdleDueReason.CLOCK_UNAVAILABLE, due.single().reason)
        assertTrue(controller.claimDue(due.single()))
    }

    private class Fixture(failScheduling: Boolean = false) {
        val source = FakeSource()
        val timer = FakeScheduler(failScheduling)
        val due = mutableListOf<LocalLockIdleDue>()
        var availabilityNotifications = 0
        val controller = LocalLockIdleController(source, timer, { due.add(it) }) { availabilityNotifications++ }
    }

    private class FakeSource : LocalLockIdleSource {
        var now: Long? = 1_000
        var input: Long? = 1_000
        var tickReads = 0
        var inputReads = 0
        override fun tickMillis(): Long? { tickReads++; return now }
        override fun lastInputDword(): Long? { inputReads++; return input }
    }

    private class FakeScheduler(private val fail: Boolean) : LocalLockDeadlineScheduler {
        class Task(val delay: Long, val action: () -> Unit, var cancelled: Boolean = false, var fired: Boolean = false)
        val tasks = mutableListOf<Task>()

        override fun schedule(delayMillis: Long, callback: () -> Unit): LocalLockDeadlineHandle {
            if (fail) error("Synthetic scheduler rejection")
            val task = Task(delayMillis, callback).also(tasks::add)
            return LocalLockDeadlineHandle { task.cancelled = true }
        }

        fun fire(index: Int) {
            val task = tasks[index]
            task.fired = true
            task.action()
        }

        fun liveCount(): Int = tasks.count { !it.cancelled && !it.fired }
    }
}
