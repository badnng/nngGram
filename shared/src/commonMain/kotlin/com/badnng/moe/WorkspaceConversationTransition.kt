package com.badnng.moe

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import kotlin.math.sqrt

// Telegram's Window::SlideAnimation uses easeOutCirc, slideDuration=240 and slideShift=100.
private val ConversationSlideEasing = Easing { fraction ->
    sqrt((1f - (fraction - 1f) * (fraction - 1f)).coerceAtLeast(0f))
}

internal class WorkspaceConversationMotion(
    val open: Boolean,
    val wide: Boolean,
    private val animatedProgress: State<Float>,
) {
    val progress: Float get() = if (wide) 0f else animatedProgress.value
    val running: Boolean get() = !wide && progress != if (open) 1f else 0f
    val listMounted: Boolean get() = wide || !open || progress < 1f
    val conversationMounted: Boolean get() = wide || open || progress > 0f
    val listInteractive: Boolean get() = wide || !open && !running
    val conversationInteractive: Boolean get() = wide || open && !running
}

@Composable
internal fun rememberWorkspaceConversationMotion(open: Boolean, wide: Boolean): WorkspaceConversationMotion {
    // Changing layout mode is a resize, not a new navigation request. Do not carry an
    // unfinished single-column slide into the side-by-side layout or back out of it.
    val progress = key(wide) {
        animateFloatAsState(if (open && !wide) 1f else 0f,
            tween(240, easing = ConversationSlideEasing), label = "workspace list and conversation")
    }
    return WorkspaceConversationMotion(open, wide, progress)
}

internal fun Modifier.workspaceListMotion(motion: WorkspaceConversationMotion): Modifier = graphicsLayer {
    translationX = -100.dp.toPx() * motion.progress
}

internal fun Modifier.workspaceConversationMotion(motion: WorkspaceConversationMotion): Modifier = graphicsLayer {
    translationX = if (motion.wide) 0f else size.width * (1f - motion.progress)
    clip = true
}

/** Paint only the exposed departing list and the moving page's left edge. */
internal fun Modifier.workspaceNavigationShade(motion: WorkspaceConversationMotion): Modifier = drawWithContent {
    drawContent()
    if (motion.running) {
        val boundary = size.width * (1f - motion.progress)
        if (boundary > 0f) {
            drawRect(Color.Black.copy(alpha = 0.08f * motion.progress), size = Size(boundary, size.height))
            val shadowWidth = 16.dp.toPx().coerceAtMost(boundary)
            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.18f)),
                startX = boundary - shadowWidth, endX = boundary),
                topLeft = Offset(boundary - shadowWidth, 0f), size = Size(shadowWidth, size.height))
        }
    }
}

/** Outgoing pages keep their drawing, but cannot own focus, input, or accessibility actions. */
@Composable
internal fun Modifier.workspacePageInput(interactive: Boolean): Modifier {
    val focus = LocalFocusManager.current
    var ownsFocus by remember { mutableStateOf(false) }
    LaunchedEffect(interactive) { if (!interactive && ownsFocus) focus.clearFocus(force = true) }
    return focusProperties { canFocus = interactive }.onFocusChanged { ownsFocus = it.hasFocus }.focusGroup()
        .onPreviewKeyEvent { !interactive }.pointerInput(interactive) {
            if (!interactive) awaitPointerEventScope {
                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        }.then(if (interactive) Modifier else Modifier.clearAndSetSemantics {})
}
