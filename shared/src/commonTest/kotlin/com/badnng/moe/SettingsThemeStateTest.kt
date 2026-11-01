package com.badnng.moe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure model and migration cases. No file, platform event, UI or account access. */
class SettingsThemeStateTest {
    @Test fun oldThemeValuesMigrateWithoutTouchingUnrelatedSettings() {
        val extras = mapOf("send-key" to "CTRL_ENTER", "wallpaper" to "NIGHT", "sticker-loop" to "false",
            "interface-scale" to "125", "accent-color" to "GREEN", "font-mode" to "INSTALLED",
            "font-family" to "Synthetic 字体")
        val cases = listOf(
            null to SettingsThemePreferences(),
            "System" to SettingsThemePreferences(),
            "Light" to SettingsThemePreferences(followSystem = false),
            "Dark" to SettingsThemePreferences(followSystem = false, manualDark = true),
            "Future" to SettingsThemePreferences(),
            // The existing legacy reader did not recognize Monet values in files.
            "MonetDark" to SettingsThemePreferences(),
        )
        for ((legacy, expected) in cases) {
            val fields = extras + (legacy?.let { mapOf("theme" to it) } ?: emptyMap())
            val snapshot = fields.toMap()
            assertEquals(expected, decodeSettingsTheme(fields), "Legacy $legacy")
            assertEquals(snapshot, fields, "Decode must be a pure read")
            val updated = fields + encodeSettingsTheme(expected)
            extras.forEach { (key, value) -> assertEquals(value, updated[key], "$legacy/$key") }
            assertEquals(expected, decodeSettingsTheme(updated))
        }
    }

    @Test fun everyNewStateRoundTripsAndHasAnAccurateLegacyShadow() {
        for (follow in listOf(false, true)) for (manualDark in listOf(false, true))
            for (family in SettingsThemeFamily.entries) {
                val value = SettingsThemePreferences(follow, manualDark, family)
                val encoded = encodeSettingsTheme(value)
                assertEquals(value, decodeSettingsTheme(encoded))
                val shadow = if (follow) "System" else if (manualDark) "Dark" else "Light"
                assertEquals(shadow, encoded["theme"])
                val recoveredByOldFormat = decodeSettingsTheme(mapOf("theme" to shadow))
                assertEquals(follow, recoveredByOldFormat.followSystem)
                if (!follow) assertEquals(manualDark, recoveredByOldFormat.manualDark)
                assertEquals(SettingsThemeFamily.STANDARD, recoveredByOldFormat.family)
            }
    }

    @Test fun missingOrInvalidNewFieldsFallBackIndependentlyToTheOldTheme() {
        val fields = mapOf("theme" to "Dark", "theme-follow-system" to "unknown",
            "theme-manual-brightness" to "Light", "theme-family" to "Monet", "accent-color" to "GOLD")
        assertEquals(SettingsThemePreferences(followSystem = false, manualDark = false,
            family = SettingsThemeFamily.MONET), decodeSettingsTheme(fields))
        assertEquals(SettingsThemePreferences(followSystem = true, manualDark = true), decodeSettingsTheme(
            mapOf("theme" to "Dark", "theme-follow-system" to "true", "theme-manual-brightness" to "bad",
                "theme-family" to "Future")))
        assertEquals(SettingsThemePreferences(followSystem = false, manualDark = true), decodeSettingsTheme(
            mapOf("theme" to "Dark", "theme-follow-system" to "TRUE", "theme-family" to "Monet?")))
        assertEquals("GOLD", fields["accent-color"])
    }

    @Test fun initialMiuixModesPreserveBothBrightnessAndFamily() {
        val cases = mapOf(
            "System" to SettingsThemePreferences(),
            "Light" to SettingsThemePreferences(followSystem = false),
            "Dark" to SettingsThemePreferences(followSystem = false, manualDark = true),
            "MonetSystem" to SettingsThemePreferences(family = SettingsThemeFamily.MONET),
            "MonetLight" to SettingsThemePreferences(followSystem = false, family = SettingsThemeFamily.MONET),
            "MonetDark" to SettingsThemePreferences(followSystem = false, manualDark = true, family = SettingsThemeFamily.MONET),
        )
        cases.forEach { (mode, expected) ->
            assertEquals(expected, settingsThemeFromInitialMode(mode))
            assertEquals(mode, settingsMiuixModeName(initialSettingsThemeState(expected, systemDark = false)))
        }
    }

    @Test fun knownSystemSameStaysFollowAndOppositeRequiresSuccessfulConfirmation() {
        for (family in SettingsThemeFamily.entries) for (system in listOf(false, true)) {
            val preferences = SettingsThemePreferences(followSystem = true, manualDark = !system, family = family)
            val start = initialSettingsThemeState(preferences, system)
            assertEquals(SettingsThemeDecision.Unchanged, chooseSettingsThemeBrightness(start, system))
            assertEquals(preferences, start.preferences)
            val pending = assertIs<SettingsThemeDecision.Confirm>(chooseSettingsThemeBrightness(start, !system)).confirmation
            // Merely opening and dismissing the prompt does not alter the source state.
            assertEquals(preferences, start.preferences)
            val plan = assertNotNull(confirmSettingsThemeChange(start, pending))
            assertTrue(plan.needsPersistence)
            assertEquals(start, acceptSettingsThemeChange(start, plan, persistenceSucceeded = false))
            val accepted = acceptSettingsThemeChange(start, plan, persistenceSucceeded = true)
            assertEquals(preferences.copy(followSystem = false, manualDark = !system), accepted.preferences)
            assertEquals(!system, accepted.effectiveDark)
            assertEquals(family, accepted.preferences.family)
            val nextEvent = observeSettingsSystemTheme(accepted, !system)
            assertEquals(!system, nextEvent.effectiveDark)
            assertEquals(accepted.preferences, nextEvent.preferences)
            val oppositeEvent = observeSettingsSystemTheme(nextEvent, system)
            assertEquals(!system, oppositeEvent.effectiveDark)
            assertEquals(accepted.preferences, oppositeEvent.preferences)
        }
    }

    @Test fun unknownRetainsLastVisibleThemeAndManualChoiceKeepsFollowingAfterRecovery() {
        for (family in SettingsThemeFamily.entries) for (initialSystem in listOf(false, true)) {
            val prefs = SettingsThemePreferences(followSystem = true, manualDark = initialSystem, family = family)
            val start = initialSettingsThemeState(prefs, initialSystem)
            val unknown = observeSettingsSystemTheme(start, null)
            assertEquals(initialSystem, unknown.effectiveDark)
            assertEquals(prefs, unknown.preferences)
            val target = !initialSystem
            val plan = assertIs<SettingsThemeDecision.Apply>(chooseSettingsThemeBrightness(unknown, target)).change
            assertTrue(plan.needsPersistence)
            assertEquals(unknown, acceptSettingsThemeChange(unknown, plan, persistenceSucceeded = false))
            val accepted = acceptSettingsThemeChange(unknown, plan, persistenceSucceeded = true)
            assertTrue(accepted.preferences.followSystem)
            assertEquals(target, accepted.preferences.manualDark)
            assertEquals(target, accepted.effectiveDark)
            val knownAgain = observeSettingsSystemTheme(accepted, initialSystem)
            assertEquals(initialSystem, knownAgain.effectiveDark)
            assertEquals(accepted.preferences, knownAgain.preferences)
            val nextSystem = observeSettingsSystemTheme(knownAgain, target)
            assertEquals(target, nextSystem.effectiveDark)
            assertEquals(accepted.preferences, nextSystem.preferences)
            assertEquals(if (family == SettingsThemeFamily.MONET) "MonetSystem" else "System",
                settingsMiuixModeName(nextSystem))
        }
    }

    @Test fun unknownCanChangeOnlyTheDisplayWhenTheFallbackIsAlreadySelected() {
        val prefs = SettingsThemePreferences(followSystem = true, manualDark = false)
        val initial = initialSettingsThemeState(prefs, systemDark = true)
        val unknown = observeSettingsSystemTheme(initial, null)
        assertTrue(unknown.effectiveDark)
        val plan = assertIs<SettingsThemeDecision.Apply>(chooseSettingsThemeBrightness(unknown, false)).change
        assertFalse(plan.needsPersistence)
        val accepted = acceptSettingsThemeChange(unknown, plan, persistenceSucceeded = false)
        assertEquals(prefs, accepted.preferences)
        assertFalse(accepted.effectiveDark)
        assertEquals(SettingsThemeDecision.Unchanged, chooseSettingsThemeBrightness(accepted, false))
        assertEquals(accepted, observeSettingsSystemTheme(accepted, null))
    }

    @Test fun unknownSelectionMatchingTheVisibleThemeStillPersistsANewFallback() {
        val prefs = SettingsThemePreferences(followSystem = true, manualDark = false)
        val knownDark = initialSettingsThemeState(prefs, systemDark = true)
        val unknown = observeSettingsSystemTheme(knownDark, null)
        assertTrue(unknown.effectiveDark)
        val plan = assertIs<SettingsThemeDecision.Apply>(chooseSettingsThemeBrightness(unknown, true)).change
        assertTrue(plan.needsPersistence)
        val accepted = acceptSettingsThemeChange(unknown, plan, persistenceSucceeded = true)
        assertTrue(accepted.preferences.followSystem)
        assertTrue(accepted.preferences.manualDark)
        assertTrue(initialSettingsThemeState(accepted.preferences, null).effectiveDark)
    }

    @Test fun manualModeAndReturningToFollowKeepTheManualFallbackAndFamily() {
        for (system in listOf<Boolean?>(null, false, true)) {
            val initial = SettingsThemePreferences(followSystem = false, manualDark = false, family = SettingsThemeFamily.MONET)
            val start = initialSettingsThemeState(initial, system)
            assertFalse(start.effectiveDark)
            val dark = assertIs<SettingsThemeDecision.Apply>(chooseSettingsThemeBrightness(start, true)).change
            val manual = acceptSettingsThemeChange(start, dark, persistenceSucceeded = true)
            assertEquals("MonetDark", settingsMiuixModeName(manual))
            assertTrue(manual.preferences.manualDark)
            val follow = assertIs<SettingsThemeDecision.Apply>(chooseSettingsThemeFollow(manual)).change
            assertEquals(manual, acceptSettingsThemeChange(manual, follow, persistenceSucceeded = false))
            val followed = acceptSettingsThemeChange(manual, follow, persistenceSucceeded = true)
            assertTrue(followed.preferences.followSystem)
            assertTrue(followed.preferences.manualDark)
            assertEquals(system ?: true, followed.effectiveDark)
            assertEquals("MonetSystem", settingsMiuixModeName(followed))
        }
    }

    @Test fun oldConfirmationAndPlansAreRejectedEvenAfterObservedOrThemeAba() {
        val start = initialSettingsThemeState(SettingsThemePreferences(), false)
        val confirmation = assertIs<SettingsThemeDecision.Confirm>(chooseSettingsThemeBrightness(start, true)).confirmation
        val otherSystem = observeSettingsSystemTheme(start, true)
        val systemAba = observeSettingsSystemTheme(otherSystem, false)
        assertEquals(start.systemDark, systemAba.systemDark)
        assertNull(confirmSettingsThemeChange(systemAba, confirmation))

        val knownPlan = assertNotNull(confirmSettingsThemeChange(start, confirmation))
        assertFalse(knownPlan.isCurrent(systemAba))
        assertEquals(systemAba, acceptSettingsThemeChange(systemAba, knownPlan, persistenceSucceeded = true))

        val manual = acceptSettingsThemeChange(start, knownPlan, persistenceSucceeded = true)
        val resetManual = assertIs<SettingsThemeDecision.Apply>(chooseSettingsThemeBrightness(manual, false)).change
        val beforeFollow = acceptSettingsThemeChange(manual, resetManual, persistenceSucceeded = true)
        val returnPlan = assertIs<SettingsThemeDecision.Apply>(chooseSettingsThemeFollow(beforeFollow)).change
        val themeAba = acceptSettingsThemeChange(beforeFollow, returnPlan, persistenceSucceeded = true)
        assertEquals(start.preferences, themeAba.preferences)
        assertEquals(start.effectiveDark, themeAba.effectiveDark)
        assertTrue(themeAba.revision > start.revision)
        assertNull(confirmSettingsThemeChange(themeAba, confirmation))
        assertFalse(knownPlan.isCurrent(themeAba))
    }

    @Test fun coldUnknownUsesPersistedFallbackAndObservingSystemDoesNotAlterPreferences() {
        for (manualDark in listOf(false, true)) {
            val prefs = SettingsThemePreferences(followSystem = true, manualDark = manualDark)
            val cold = initialSettingsThemeState(prefs, null)
            assertEquals(manualDark, cold.effectiveDark)
            val known = observeSettingsSystemTheme(cold, !manualDark)
            assertEquals(!manualDark, known.effectiveDark)
            assertEquals(prefs, known.preferences)
            val lostAgain = observeSettingsSystemTheme(known, null)
            assertEquals(!manualDark, lostAgain.effectiveDark)
            assertEquals(prefs, lostAgain.preferences)
        }
    }
}
