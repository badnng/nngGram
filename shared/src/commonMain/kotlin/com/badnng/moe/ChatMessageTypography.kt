package com.badnng.moe

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import demo.shared.generated.resources.Res
import demo.shared.generated.resources.telegram_open_sans_italic
import demo.shared.generated.resources.telegram_open_sans_regular
import demo.shared.generated.resources.telegram_open_sans_semibold
import demo.shared.generated.resources.telegram_open_sans_semibold_italic
import org.jetbrains.compose.resources.Font

/**
 * lib_ui 79c846f: basic.style fsize and defaultTextStyle; style_core_font.cpp ResolveFont.
 * Native message text uses the same bundled Open Sans faces and font-derived line metrics.
 * Explicit custom families share SettingsFontHost with normal Miuix text; the default stays unchanged.
 * Font family selection does not promise Qt/Skia raster equivalence.
 */
internal val CHAT_MESSAGE_FONT_SIZE = 13.sp

@Composable
internal fun chatMessageFontFamily(): FontFamily {
    val selected = LocalSettingsFontLoad.current
    if (selected.status == SettingsFontLoadStatus.READY && selected.family != null) return selected.family
    return bundledChatMessageFontFamily()
}

@Composable
internal fun bundledChatMessageFontFamily(): FontFamily {
    val regular = Font(Res.font.telegram_open_sans_regular, FontWeight.Normal, FontStyle.Normal)
    val semibold = Font(Res.font.telegram_open_sans_semibold, FontWeight.SemiBold, FontStyle.Normal)
    val italic = Font(Res.font.telegram_open_sans_italic, FontWeight.Normal, FontStyle.Italic)
    val semiboldItalic = Font(Res.font.telegram_open_sans_semibold_italic, FontWeight.SemiBold, FontStyle.Italic)
    return remember(regular, semibold, italic, semiboldItalic) {
        FontFamily(regular, semibold, italic, semiboldItalic)
    }
}

@Composable
internal fun chatMessageTextStyle(): TextStyle = TextStyle(
    fontFamily = chatMessageFontFamily(),
    fontSize = CHAT_MESSAGE_FONT_SIZE,
    fontWeight = FontWeight.Normal,
    fontStyle = FontStyle.Normal,
    letterSpacing = 0.sp,
    lineHeight = TextUnit.Unspecified,
)
