package com.badnng.moe

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily

internal enum class SettingsChatFontMode { DEFAULT_OPEN_SANS, SYSTEM_DEFAULT, INSTALLED }

@Immutable
internal data class SettingsChatFont(
    val mode: SettingsChatFontMode = SettingsChatFontMode.DEFAULT_OPEN_SANS,
    val familyName: String? = null,
) {
    val valid: Boolean get() = when (mode) {
        SettingsChatFontMode.INSTALLED -> isValidSettingsFontName(familyName)
        else -> familyName == null
    }
}

internal fun isValidSettingsFontName(value: String?): Boolean =
    value != null && value.length in 1..256 && value == value.trim() && value.none { it.isISOControl() }

internal fun settingsFontLabel(font: SettingsChatFont): String = when (font.mode) {
    SettingsChatFontMode.DEFAULT_OPEN_SANS -> "默认（Open Sans）"
    SettingsChatFontMode.SYSTEM_DEFAULT -> "系统默认"
    SettingsChatFontMode.INSTALLED -> font.familyName.orEmpty()
}

@Immutable
internal data class SettingsFontCatalog(val families: List<String>, val truncated: Boolean = false)

internal enum class SettingsFontLoadStatus { DEFAULT, LOADING, READY, MISSING, FAILED }

@Immutable
internal data class SettingsFontLoad(
    val requested: SettingsChatFont = SettingsChatFont(),
    val status: SettingsFontLoadStatus = SettingsFontLoadStatus.DEFAULT,
    val family: FontFamily? = null,
)

/** Implementations enumerate and preload on a bounded worker, never from drawing/composition. */
internal interface SettingsFontService {
    suspend fun catalog(): SettingsFontCatalog
    suspend fun load(font: SettingsChatFont): SettingsFontLoad
}

internal expect fun platformSettingsFontService(): SettingsFontService
internal val LocalSettingsFontService = compositionLocalOf<SettingsFontService> { platformSettingsFontService() }
internal val LocalSettingsFontLoad = compositionLocalOf { SettingsFontLoad() }

/** A new editor owns its draft; failed saves and unrelated preference updates never consume it. */
internal class SettingsFontEditor(initial: SettingsChatFont) {
    var draft: SettingsChatFont by mutableStateOf(initial)
        private set

    fun choose(font: SettingsChatFont) { if (font.valid) draft = font }
    fun save(save: (SettingsChatFont) -> Boolean): Boolean = draft.valid && save(draft)
}

/** Small transient draft only; owners may keep this across settings-page unmount/mandatory gates. */
internal class SettingsFontEditorSession {
    var editor: SettingsFontEditor? by mutableStateOf(null)
        private set
    var query: String by mutableStateOf("")
    var saveFailed: Boolean by mutableStateOf(false)

    fun open(initial: SettingsChatFont) {
        if (editor == null) editor = SettingsFontEditor(initial)
        saveFailed = false
    }

    fun cancel() { editor = null; query = ""; saveFailed = false }
}
