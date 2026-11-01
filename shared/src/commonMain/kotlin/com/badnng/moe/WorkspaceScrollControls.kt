package com.badnng.moe

import androidx.compose.foundation.border
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Scrolls the actual lazy list, including dragging and clicking its native desktop thumb. */
@Composable
internal expect fun WorkspaceScrollbar(
    state: LazyListState,
    modifier: Modifier = Modifier,
    reverseLayout: Boolean = false,
)

@Composable
internal expect fun WorkspaceScrollbar(state: ScrollState, modifier: Modifier = Modifier)

/** The caller owns both visibility and the scroll action so history loading keeps its native semantics. */
@Composable
internal fun FloatingScrollButton(
    up: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    unreadCount: Int = 0,
    size: Dp = 40.dp,
    downwardShadow: Boolean = false,
) {
    val colors = MiuixTheme.colorScheme
    val label = if (up) "回到顶部" else "回到最新消息"
    Box(modifier.size(size)) {
        // Keep the indication on the full button surface, inside its circular clip.
        // The outer box leaves room for the unread badge and the unclipped shadow.
        IconButton(
            onClick = onClick,
            modifier = Modifier.matchParentSize()
                .chatGlassSurface(size / 2f, circular = true, downwardShadow = downwardShadow)
                .border(1.dp, colors.dividerLine.copy(alpha = 0.55f), CircleShape)
                .semantics {
                    contentDescription = if (unreadCount > 0) "$label，$unreadCount 条未读" else label
                },
            backgroundColor = Color.Transparent,
            cornerRadius = size / 2f,
            minWidth = size,
            minHeight = size,
        ) {
            Icon(MiuixIcons.Regular.Back, null, Modifier.size(size * 0.5f).rotate(if (up) 90f else -90f),
                tint = colors.onSurfaceVariantSummary)
        }
        if (unreadCount > 0) {
            UnreadBadge(unreadCount, compact = true,
                modifier = Modifier.align(Alignment.TopCenter).offset(y = (-9).dp).clearAndSetSemantics {})
        }
    }
}
