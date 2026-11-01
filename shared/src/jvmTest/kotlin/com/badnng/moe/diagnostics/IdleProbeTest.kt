package com.badnng.moe.diagnostics

import java.nio.file.Path
import kotlin.test.*

class IdleProbeTest {
    @Test
    fun ordinaryStartupDoesNotEnableOrResolveProbeOutput() {
        assertNull(IdleProbeConfig.parse(emptyArray()))
        assertNull(IdleProbeConfig.parse(arrayOf("--some-other-option")))
    }

    @Test
    fun probeNeedsExplicitModeAndAbsoluteOutputAndBoundedDuration() {
        val path = Path.of("build/probe-result.json").toAbsolutePath().toString()
        val config = assertNotNull(IdleProbeConfig.parse(arrayOf("--idle-probe=static", "--idle-probe-output=$path")))
        assertEquals(IdleProbeMode.STATIC, config.mode)
        assertEquals(30, config.seconds)
        assertFalse(config.toString().contains(path))
        assertFails { IdleProbeConfig.parse(arrayOf("--idle-probe=static")) }
        assertFails { IdleProbeConfig.parse(arrayOf("--idle-probe-output=$path")) }
        assertFails { IdleProbeConfig.parse(arrayOf("--idle-probe=static", "--idle-probe-output=relative.json")) }
        assertFails { IdleProbeConfig.parse(arrayOf("--idle-probe=static", "--idle-probe-output=$path", "--idle-probe-seconds=999")) }
        assertFails { IdleProbeConfig.parse(arrayOf("--idle-probe=static", "--idle-probe=video", "--idle-probe-output=$path")) }
    }

    @Test
    fun measurementExcludesWarmupDrawsAndCountsActualActiveDuration() {
        var now = 0L
        val recorder = IdleProbeRecorder { now }
        recorder.windowActive(true)
        repeat(12) { recorder.draw() }
        recorder.mediaDraw()
        recorder.begin()
        now = 2_000_000_000
        recorder.windowActive(false)
        recorder.draw()
        now = 5_000_000_000
        val report = recorder.finish()
        assertEquals(5_000L, report.elapsedMillis)
        assertEquals(2_000L, report.activeMillis)
        assertEquals(0.4, report.activeFraction)
        assertEquals(1L, report.rootDraws)
        assertEquals(0L, report.mediaDraws)
    }

    @Test
    fun idleSceneDoesNotNeedPeriodicDrawsToCompleteMeasurement() {
        var now = 0L
        val recorder = IdleProbeRecorder { now }
        recorder.draw()
        recorder.windowActive(true)
        recorder.begin()
        now = 30_000_000_000
        val report = recorder.finish()
        assertTrue(recorder.hasDrawn())
        assertEquals(0L, report.rootDraws)
        assertEquals(30_000L, report.activeMillis)
        assertEquals(1.0, report.activeFraction)
    }
}
