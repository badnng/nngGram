package com.badnng.moe

/** A theme family is independent of the user's separate accent-color preference. */
internal enum class SettingsThemeFamily { STANDARD, MONET }

/** Only these values are persistent. The observed and effective brightness are not. */
internal data class SettingsThemePreferences(
    val followSystem: Boolean = true,
    val manualDark: Boolean = false,
    val family: SettingsThemeFamily = SettingsThemeFamily.STANDARD,
)

private const val LEGACY_THEME = "theme"
private const val FOLLOW_SYSTEM = "theme-follow-system"
private const val MANUAL_BRIGHTNESS = "theme-manual-brightness"
private const val THEME_FAMILY = "theme-family"

/** The actual old file understands only System, Light and Dark. Reading never writes. */
internal fun decodeSettingsTheme(fields: Map<String, String>): SettingsThemePreferences {
    val legacy = when (fields[LEGACY_THEME]) {
        "Light" -> SettingsThemePreferences(followSystem = false, manualDark = false)
        "Dark" -> SettingsThemePreferences(followSystem = false, manualDark = true)
        else -> SettingsThemePreferences() // Old missing and unknown values mean System.
    }
    return SettingsThemePreferences(
        followSystem = fields[FOLLOW_SYSTEM]?.toBooleanStrictOrNull() ?: legacy.followSystem,
        manualDark = when (fields[MANUAL_BRIGHTNESS]) {
            "Light" -> false
            "Dark" -> true
            else -> legacy.manualDark
        },
        family = when (fields[THEME_FAMILY]) {
            "Standard" -> SettingsThemeFamily.STANDARD
            "Monet" -> SettingsThemeFamily.MONET
            else -> legacy.family
        },
    )
}

/** The legacy shadow lets the old reader recover follow or manual brightness on downgrade. */
internal fun encodeSettingsTheme(preferences: SettingsThemePreferences): Map<String, String> = mapOf(
    LEGACY_THEME to if (preferences.followSystem) "System" else if (preferences.manualDark) "Dark" else "Light",
    FOLLOW_SYSTEM to preferences.followSystem.toString(),
    MANUAL_BRIGHTNESS to if (preferences.manualDark) "Dark" else "Light",
    THEME_FAMILY to if (preferences.family == SettingsThemeFamily.MONET) "Monet" else "Standard",
)

/** App's non-persistent preview accepts all six actual Miuix enum names. */
internal fun settingsThemeFromInitialMode(modeName: String): SettingsThemePreferences = when (modeName) {
    "Light" -> SettingsThemePreferences(followSystem = false)
    "Dark" -> SettingsThemePreferences(followSystem = false, manualDark = true)
    "MonetSystem" -> SettingsThemePreferences(family = SettingsThemeFamily.MONET)
    "MonetLight" -> SettingsThemePreferences(followSystem = false, family = SettingsThemeFamily.MONET)
    "MonetDark" -> SettingsThemePreferences(followSystem = false, manualDark = true, family = SettingsThemeFamily.MONET)
    else -> SettingsThemePreferences()
}

/**
 * systemDark is an observation, not proof the platform can detect failure. effectiveDark
 * is process-local so loss of an observation does not itself change the rendered theme.
 */
internal data class SettingsThemeState(
    val preferences: SettingsThemePreferences,
    val systemDark: Boolean?,
    val effectiveDark: Boolean,
    val revision: Long,
)

internal fun initialSettingsThemeState(
    preferences: SettingsThemePreferences,
    systemDark: Boolean?,
): SettingsThemeState = SettingsThemeState(
    preferences = preferences,
    systemDark = systemDark,
    effectiveDark = if (preferences.followSystem) systemDark ?: preferences.manualDark else preferences.manualDark,
    revision = 0L,
)

/** Platform events change presentation only. Repeated identical observations are no-ops. */
internal fun observeSettingsSystemTheme(state: SettingsThemeState, systemDark: Boolean?): SettingsThemeState {
    if (state.systemDark == systemDark) return state
    return state.copy(
        systemDark = systemDark,
        effectiveDark = if (state.preferences.followSystem && systemDark != null) systemDark else state.effectiveDark,
        revision = state.revision + 1,
    )
}

/** The caller must validate the page/parent lease in addition to this theme snapshot. */
internal data class SettingsThemeConfirmation internal constructor(
    val expected: SettingsThemeState,
    val targetDark: Boolean,
)

/** No I/O occurs while planning. Save the full current preferences only after isCurrent. */
internal data class SettingsThemeChange internal constructor(
    val expected: SettingsThemeState,
    val targetPreferences: SettingsThemePreferences,
    val targetEffectiveDark: Boolean,
) {
    val needsPersistence: Boolean get() = targetPreferences != expected.preferences
    fun isCurrent(current: SettingsThemeState): Boolean = current == expected
}

internal sealed interface SettingsThemeDecision {
    data object Unchanged : SettingsThemeDecision
    data class Apply(val change: SettingsThemeChange) : SettingsThemeDecision
    data class Confirm(val confirmation: SettingsThemeConfirmation) : SettingsThemeDecision
}

internal fun chooseSettingsThemeBrightness(state: SettingsThemeState, targetDark: Boolean): SettingsThemeDecision {
    val preferences = state.preferences
    val observed = state.systemDark
    if (preferences.followSystem && observed != null) {
        if (observed == targetDark) return SettingsThemeDecision.Unchanged
        return SettingsThemeDecision.Confirm(SettingsThemeConfirmation(state, targetDark))
    }
    // In the unknown-follow branch this updates the manual fallback but retains follow.
    val updated = preferences.copy(manualDark = targetDark)
    if (updated == preferences && state.effectiveDark == targetDark) return SettingsThemeDecision.Unchanged
    return SettingsThemeDecision.Apply(SettingsThemeChange(state, updated, targetDark))
}

internal fun chooseSettingsThemeFollow(state: SettingsThemeState): SettingsThemeDecision {
    if (state.preferences.followSystem) return SettingsThemeDecision.Unchanged
    return SettingsThemeDecision.Apply(SettingsThemeChange(
        expected = state,
        targetPreferences = state.preferences.copy(followSystem = true),
        targetEffectiveDark = state.systemDark ?: state.effectiveDark,
    ))
}

/** Returns no plan when the observation, theme state or revision changed meanwhile. */
internal fun confirmSettingsThemeChange(
    state: SettingsThemeState,
    confirmation: SettingsThemeConfirmation,
): SettingsThemeChange? {
    if (state != confirmation.expected || !state.preferences.followSystem ||
        state.systemDark == null || state.systemDark == confirmation.targetDark) return null
    return SettingsThemeChange(state,
        state.preferences.copy(followSystem = false, manualDark = confirmation.targetDark), confirmation.targetDark)
}

/**
 * Apply only the accepted plan. A failed save or stale snapshot changes neither data nor
 * display; a presentation-only change needs no disk write. Page ownership remains external.
 */
internal fun acceptSettingsThemeChange(
    current: SettingsThemeState,
    change: SettingsThemeChange,
    persistenceSucceeded: Boolean,
): SettingsThemeState {
    if (!change.isCurrent(current) || (change.needsPersistence && !persistenceSucceeded)) return current
    if (!change.needsPersistence && change.targetEffectiveDark == current.effectiveDark) return current
    return current.copy(preferences = change.targetPreferences, effectiveDark = change.targetEffectiveDark,
        revision = current.revision + 1)
}

/** The adapter can keep System/MonetSystem while supplying effectiveDark to ThemeController. */
internal fun settingsMiuixModeName(state: SettingsThemeState): String = when (state.preferences.family) {
    SettingsThemeFamily.STANDARD -> if (state.preferences.followSystem) "System" else if (state.effectiveDark) "Dark" else "Light"
    SettingsThemeFamily.MONET -> if (state.preferences.followSystem) "MonetSystem" else if (state.effectiveDark) "MonetDark" else "MonetLight"
}
