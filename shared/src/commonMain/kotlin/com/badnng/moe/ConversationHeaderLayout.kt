package com.badnng.moe

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The expanded toolbar has a fixed preferred width; its height and controls never scale. */
internal fun conversationHeaderWidth(availableWidth: Dp): Dp =
    minOf(940.dp, availableWidth.coerceAtLeast(0.dp))
