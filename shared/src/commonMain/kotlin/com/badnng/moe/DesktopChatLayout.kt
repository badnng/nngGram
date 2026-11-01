package com.badnng.moe

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
internal data class DesktopChatLayout(
    val railWidth: Dp = 88.dp,
    val listWidth: Dp = 354.dp,
    val compact: Boolean = false,
    val threeColumns: Boolean = true,
    val showRail: Boolean = true,
)

internal val LocalDesktopChatLayout = staticCompositionLocalOf { DesktopChatLayout() }

/** Keep the reference's column proportions while preserving readable minimum widths. */
internal fun desktopChatLayout(width: Dp, height: Dp): DesktopChatLayout {
    val fraction = width / 1494.dp
    return DesktopChatLayout(
        railWidth = (88.dp * fraction).coerceIn(72.dp, 88.dp),
        listWidth = (354.dp * fraction).coerceIn(260.dp, 354.dp),
        compact = width < 1320.dp || height < 760.dp,
        threeColumns = width >= 980.dp,
        showRail = width >= 600.dp,
    )
}
