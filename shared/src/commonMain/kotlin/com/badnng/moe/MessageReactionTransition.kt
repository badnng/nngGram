package com.badnng.moe

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** One continuously sized surface within the existing message Popup and emoji-layout cache. */
@Composable
internal fun MessageReactionTransition(
    pickerExpanded: Boolean,
    interactive: Boolean,
    embeddedSurface: Boolean = false,
    menu: @Composable (Boolean) -> Unit,
    picker: @Composable (Boolean) -> Unit,
) {
    val transition = updateTransition(pickerExpanded, label = "reaction picker expansion")
    transition.AnimatedContent(
        modifier = (if (embeddedSurface) Modifier else Modifier.chatShortcutShadow()
            .chatSquircleSurface(MiuixTheme.colorScheme.surfaceContainer, 15.dp))
            .then(if (embeddedSurface) Modifier else Modifier.semantics { paneTitle = "消息操作面板" }),
        contentAlignment = Alignment.TopStart,
        transitionSpec = {
            (fadeIn(tween(140, delayMillis = 70)) + slideInVertically(tween(240)) { it / 24 }) togetherWith
                (fadeOut(tween(90)) + slideOutVertically(tween(180)) { -it / 32 }) using
                SizeTransform(clip = false) { _, _ -> tween(240, easing = FastOutSlowInEasing) }
        },
    ) { fullPicker ->
        val acceptsInput = interactive && !transition.isRunning &&
            transition.currentState == fullPicker && transition.targetState == fullPicker
        ReactionTransitionContent(acceptsInput) {
            if (fullPicker) picker(acceptsInput) else menu(acceptsInput)
        }
    }
}

/** Moving targets cannot receive clicks or take search focus before the shape has settled. */
@Composable
private fun ReactionTransitionContent(interactive: Boolean, content: @Composable () -> Unit) {
    val focusManager = LocalFocusManager.current
    var ownsFocus by remember { mutableStateOf(false) }
    LaunchedEffect(interactive) { if (!interactive && ownsFocus) focusManager.clearFocus(force = true) }
    Box(Modifier.focusProperties { canFocus = interactive }.onFocusChanged { ownsFocus = it.hasFocus }.focusGroup()
        .onPreviewKeyEvent { !interactive }.pointerInput(interactive) {
            if (!interactive) awaitPointerEventScope {
                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        }.then(if (interactive) Modifier else Modifier.clearAndSetSemantics {})) {
        content()
    }
}
