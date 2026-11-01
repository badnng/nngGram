package com.badnng.moe

import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal actual fun WorkspaceScrollbar(state: LazyListState, modifier: Modifier, reverseLayout: Boolean) {
    if (!state.canScrollBackward && !state.canScrollForward) return
    val color = MiuixTheme.colorScheme.onSurfaceVariantSummary
    VerticalScrollbar(adapter = rememberScrollbarAdapter(state), modifier = modifier, reverseLayout = reverseLayout,
        style = ScrollbarStyle(minimalHeight = 28.dp, thickness = 4.dp, shape = rememberMiuixSquircleShape(2.dp),
            hoverDurationMillis = 140, unhoverColor = color.copy(alpha = 0.28f), hoverColor = color.copy(alpha = 0.65f)))
}

@Composable
internal actual fun WorkspaceScrollbar(state: ScrollState, modifier: Modifier) {
    if (!state.canScrollBackward && !state.canScrollForward) return
    val color = MiuixTheme.colorScheme.onSurfaceVariantSummary
    VerticalScrollbar(adapter = rememberScrollbarAdapter(state), modifier = modifier,
        style = ScrollbarStyle(minimalHeight = 28.dp, thickness = 4.dp, shape = rememberMiuixSquircleShape(2.dp),
            hoverDurationMillis = 140, unhoverColor = color.copy(alpha = .28f), hoverColor = color.copy(alpha = .65f)))
}
