package com.badnng.moe

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.LocalFocusManager

/** Keep the search field and back action mounted through their real exit frames. */
@Composable
internal fun WorkspaceSearchTransition(open: Boolean, modifier: Modifier = Modifier,
    content: @Composable (search: Boolean, interactive: Boolean) -> Unit) {
    AnimatedContent(open, modifier, label = "workspace search and back", transitionSpec = {
        val direction = if (targetState) 1 else -1
        (fadeIn(tween(180)) + slideInHorizontally(tween(240)) { direction * it / 8 }) togetherWith
            (fadeOut(tween(160)) + slideOutHorizontally(tween(240)) { -direction * it / 8 })
    }) { search ->
        val interactive = search == open
        val focus = LocalFocusManager.current
        var ownsFocus by remember { mutableStateOf(false) }
        LaunchedEffect(interactive) { if (!interactive && ownsFocus) focus.clearFocus(force = true) }
        Box(Modifier.focusProperties { canFocus = interactive }.onFocusChanged { ownsFocus = it.hasFocus }.focusGroup()
            .onPreviewKeyEvent { !interactive }.pointerInput(interactive) {
            if (!interactive) awaitPointerEventScope {
                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        }.then(if (interactive) Modifier else Modifier.clearAndSetSemantics {})) {
            content(search, interactive)
        }
    }
}
