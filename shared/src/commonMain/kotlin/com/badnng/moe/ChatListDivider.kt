package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal expect fun chatListResizePointerIcon(): PointerIcon

@Composable
internal fun ChatListDivider(width: Dp, maximum: Dp, onDelta: (Dp) -> Unit, onSetWidth: (Dp) -> Unit) {
    val density = LocalDensity.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val dragged by interaction.collectIsDraggedAsState()
    val colors = MiuixTheme.colorScheme
    Box(Modifier.width(6.dp).fillMaxHeight()
        .background(if (hovered || dragged) colors.primary.copy(alpha = 0.08f) else colors.surface)
        .pointerHoverIcon(chatListResizePointerIcon())
        .hoverable(interaction)
        .draggable(rememberDraggableState { onDelta(with(density) { it.toDp() }) }, Orientation.Horizontal,
            interactionSource = interaction, startDragImmediately = true)
        .semantics {
            contentDescription = "调整聊天列表宽度"
            progressBarRangeInfo = ProgressBarRangeInfo(width.value, MinimumChatListWidth.value..maximum.value)
            setProgress { onSetWidth(it.dp); true }
        }
        .onKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                Key.DirectionLeft -> { onDelta((-16).dp); true }
                Key.DirectionRight -> { onDelta(16.dp); true }
                Key.MoveHome -> { onSetWidth(MinimumChatListWidth); true }
                Key.MoveEnd -> { onSetWidth(maximum); true }
                else -> false
            }
        }.focusable(), contentAlignment = Alignment.Center) {
        // The resting split has no painted line; only an active resize shows its accent guide.
        if (hovered || dragged) Box(Modifier.width(2.dp).fillMaxHeight()
            .background(colors.primary.copy(alpha = 0.65f)))
    }
}
