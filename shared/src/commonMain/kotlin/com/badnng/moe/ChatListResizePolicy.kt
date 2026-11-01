package com.badnng.moe

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal val MinimumChatListWidth = 72.dp
internal val CollapsedChatListThreshold = 180.dp

/** Keep a usable conversation when the information pane or window size changes. */
internal fun maximumChatListWidth(workspaceWidth: Dp, railWidth: Dp, informationWidth: Dp): Dp =
    (workspaceWidth - railWidth - informationWidth - 360.dp - 6.dp).coerceIn(MinimumChatListWidth, 480.dp)

internal fun constrainedChatListWidth(requested: Dp, maximum: Dp): Dp =
    requested.coerceIn(MinimumChatListWidth, maximum.coerceAtLeast(MinimumChatListWidth))
