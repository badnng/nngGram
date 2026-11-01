package com.badnng.moe

import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class SettingsInterfaceDensityTest {
    @Test
    fun automaticKeepsTheHostDensityAndManualPercentMultipliesOnlyThatBase() {
        for (base in listOf(1f, 1.25f, 1.5f, 2f)) {
            val system = Density(base, fontScale = 1.3f)
            assertSame(system, settingsInterfaceDensity(system, 0))
            for (percent in listOf(50, 100, 125, 137, 200, 300)) {
                val actual = settingsInterfaceDensity(system, percent)
                assertEquals(base * percent / 100f, actual.density, .0001f)
                assertEquals(system.fontScale, actual.fontScale)
            }
        }
    }

    @Test
    fun hostDpiChangesRecomputeFromTheNewBaseWithoutCompoundingOldApplicationScale() {
        val oldHost = Density(1.25f, fontScale = 1.4f)
        val newHost = Density(2f, fontScale = 1.4f)
        assertEquals(1.5625f, settingsInterfaceDensity(oldHost, 125).density)
        assertEquals(2.5f, settingsInterfaceDensity(newHost, 125).density)
        assertEquals(2f, settingsInterfaceDensity(newHost, 100).density)
        assertSame(newHost, settingsInterfaceDensity(newHost, 0))
        assertSame(newHost, settingsInterfaceDensity(newHost, -1))
    }
}
