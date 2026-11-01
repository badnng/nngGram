package com.badnng.moe.settings

/** An actual TDLib measurement, published only after the native proxy source is rechecked. */
sealed interface SettingsProxyProbe {
    data class Available(val milliseconds: Long) : SettingsProxyProbe
    data object Unavailable : SettingsProxyProbe
}
