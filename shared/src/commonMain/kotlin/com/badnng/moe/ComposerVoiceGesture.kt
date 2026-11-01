package com.badnng.moe

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** Pointer recording keeps keyboard activation on the normal clickable icon. */
@Composable
internal fun composerVoiceGesture(
    enabled: Boolean,
    fieldBounds: () -> Rect,
    onPress: (() -> Unit)?,
    onRelease: ((cancelled: Boolean) -> Unit)?,
    onLock: (() -> Unit)?,
): Modifier {
    if (onPress == null || onRelease == null) return Modifier
    val currentPress by rememberUpdatedState(onPress)
    val currentRelease by rememberUpdatedState(onRelease)
    val currentLock by rememberUpdatedState(onLock)
    val currentField by rememberUpdatedState(fieldBounds)
    val bounds = remember { arrayOf(Rect.Zero) }
    val density = LocalDensity.current
    val lockDistance = with(density) { 48.dp.toPx() }
    val cancelDistance = with(density) { 80.dp.toPx() }
    val tolerance = with(density) { 32.dp.toPx() }
    return Modifier.onGloballyPositioned { bounds[0] = it.boundsInWindow(clipBounds = false) }
        .pointerInput(enabled, density) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (!currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
                down.consume()
                val release = currentRelease
                val lock = currentLock
                val downWindow = bounds[0].topLeft + down.position
                var locked = false
                var finished = false
                currentPress?.invoke()
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val point = bounds[0].topLeft + change.position
                        val delta = point - downWindow
                        if (!locked && lock != null && delta.y <= -lockDistance && delta.x > -cancelDistance) {
                            locked = true
                            lock()
                        }
                        change.consume()
                        if (!change.pressed) {
                            finished = true
                            if (!locked) {
                                val field = currentField()
                                val allowed = if (field == Rect.Zero) bounds[0] else field
                                val inside = Rect(allowed.left - tolerance, allowed.top - tolerance,
                                    allowed.right + tolerance, allowed.bottom + tolerance).contains(point)
                                release?.invoke(delta.x <= -cancelDistance || !inside)
                            }
                            break
                        }
                    }
                } finally {
                    if (!finished && !locked) release?.invoke(true)
                }
            }
        }
}
