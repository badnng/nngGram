package com.badnng.moe

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Keep the actual information content mounted until its horizontal exit has completed. */
@Composable
internal fun RowScope.ConversationInfoTransition(
    visible: Boolean,
    onWidthChanged: (Dp) -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.onSizeChanged { onWidthChanged(with(density) { it.width.toDp() }) },
        enter = expandHorizontally(tween(220, easing = FastOutSlowInEasing), expandFrom = Alignment.End) +
            slideInHorizontally(tween(220, easing = FastOutSlowInEasing)) { it / 5 } + fadeIn(tween(180)),
        exit = shrinkHorizontally(tween(220, easing = FastOutSlowInEasing), shrinkTowards = Alignment.End) +
            slideOutHorizontally(tween(220, easing = FastOutSlowInEasing)) { it / 5 } + fadeOut(tween(180)),
        label = "conversation information",
    ) {
        DisposableEffect(Unit) { onDispose { onWidthChanged(0.dp) } }
        ConversationInfoContent(visible, Modifier.width(300.dp).fillMaxHeight(), content)
    }
}

/** A closing surface remains painted, but no longer accepts input or exposes stale actions. */
@Composable
internal fun ConversationInfoContent(interactive: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val focusManager = LocalFocusManager.current
    var ownsFocus by remember { mutableStateOf(false) }
    LaunchedEffect(interactive) {
        if (!interactive && ownsFocus) focusManager.clearFocus(force = true)
    }
    Box(modifier.focusProperties { canFocus = interactive }.onFocusChanged { ownsFocus = it.hasFocus }.focusGroup()
        .onPreviewKeyEvent { !interactive }.pointerInput(interactive) {
            if (!interactive) awaitPointerEventScope {
                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        }.then(if (interactive) Modifier else Modifier.clearAndSetSemantics {})) {
        content()
    }
}
