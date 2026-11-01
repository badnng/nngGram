package com.badnng.moe.media

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WindowActivityGateTest {
    @Test
    fun minimizationPausesWithoutSceneFramesAndFocusDoesNotResume() = runTest {
        val focused = MutableStateFlow(true)
        val minimized = MutableStateFlow(false)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val gate = WindowActivityGate(focused, minimized, dispatcher)
        var playing = true
        var pauses = 0
        val observer = observeWindowInactivity(gate.active, { playing = false; pauses++ }, dispatcher)
        try {
            runCurrent()
            assertTrue(gate.active.value)
            minimized.value = true
            runCurrent()
            assertFalse(gate.active.value)
            assertFalse(playing)
            assertEquals(1, pauses)
            minimized.value = false
            runCurrent()
            assertTrue(gate.active.value)
            assertFalse(playing)
            assertEquals(1, pauses)
            focused.value = false
            runCurrent()
            assertEquals(2, pauses)
        } finally { observer.close(); gate.close() }
    }

    @Test
    fun inactiveRegistrationPausesImmediatelyAndDisposalStopsObservation() = runTest {
        val activity = MutableStateFlow(false)
        var pauses = 0
        val observer = observeWindowInactivity(activity, { pauses++ }, StandardTestDispatcher(testScheduler))
        assertEquals(1, pauses)
        observer.close()
        activity.value = true
        runCurrent()
        activity.value = false
        runCurrent()
        assertEquals(1, pauses)
    }
}
