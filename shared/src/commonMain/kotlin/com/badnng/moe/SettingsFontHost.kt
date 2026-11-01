package com.badnng.moe

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.TextStyles

/** Stable host: changing fonts remeasures text without recreating editors, drafts or selection. */
@Composable
internal fun SettingsFontHost(
    preferences: SettingsPreferences,
    active: Boolean = true,
    service: SettingsFontService = LocalSettingsFontService.current,
    reloadKey: Long = 0L,
    content: @Composable () -> Unit,
) {
    val font = preferences.chatFont
    var loaded by remember { mutableStateOf(SettingsFontLoad()) }
    LaunchedEffect(font, active, service, reloadKey) {
        if (active) loaded = loadSettingsFontSafely(service, font)
    }
    // Never expose the previous requested family's result while a new request is still loading.
    val current = loaded.takeIf { it.requested == font } ?: SettingsFontLoad(font,
        if (font.mode == SettingsChatFontMode.DEFAULT_OPEN_SANS) SettingsFontLoadStatus.DEFAULT else SettingsFontLoadStatus.LOADING)
    val styles = MiuixTheme.textStyles
    val family = current.family.takeIf { current.status == SettingsFontLoadStatus.READY }
    val effectiveStyles = settingsFontTextStyles(styles, family)
    CompositionLocalProvider(LocalSettingsFontLoad provides current, LocalSettingsFontService provides service) {
        MiuixTheme(colors = MiuixTheme.colorScheme, textStyles = effectiveStyles, content = content)
    }
}

internal suspend fun loadSettingsFontSafely(service: SettingsFontService, font: SettingsChatFont): SettingsFontLoad = try {
    val result = service.load(font)
    coroutineContext.ensureActive()
    result.takeIf { it.requested == font &&
        (it.status != SettingsFontLoadStatus.READY || it.family != null) }
        ?: SettingsFontLoad(font, SettingsFontLoadStatus.FAILED)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    coroutineContext.ensureActive()
    SettingsFontLoad(font, SettingsFontLoadStatus.FAILED)
}

/** Preserve every Miuix size, weight, line metric and explicit code override. */
internal fun settingsFontTextStyles(styles: TextStyles, family: FontFamily?): TextStyles =
    if (family == null) styles else styles.copy(
        main = styles.main.copy(fontFamily = family), paragraph = styles.paragraph.copy(fontFamily = family),
        body1 = styles.body1.copy(fontFamily = family), body2 = styles.body2.copy(fontFamily = family),
        button = styles.button.copy(fontFamily = family), footnote1 = styles.footnote1.copy(fontFamily = family),
        footnote2 = styles.footnote2.copy(fontFamily = family), headline1 = styles.headline1.copy(fontFamily = family),
        headline2 = styles.headline2.copy(fontFamily = family), subtitle = styles.subtitle.copy(fontFamily = family),
        title1 = styles.title1.copy(fontFamily = family), title2 = styles.title2.copy(fontFamily = family),
        title3 = styles.title3.copy(fontFamily = family), title4 = styles.title4.copy(fontFamily = family),
    )
