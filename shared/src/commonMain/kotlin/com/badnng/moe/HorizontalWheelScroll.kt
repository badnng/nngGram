package com.badnng.moe

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** A mouse wheel can reach overflowing desktop tabs without changing native trackpad X scrolling. */
internal fun Modifier.verticalWheelToHorizontal(state: ScrollableState): Modifier = pointerInput(state) {
    val wheelStep = 48.dp.toPx()
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type != PointerEventType.Scroll || event.changes.any { it.isConsumed }) continue
            val delta = event.changes.fold(Offset.Zero) { sum, change -> sum + change.scrollDelta }
            if (abs(delta.x) > 0.001f || delta.y == 0f) continue
            // Raw dispatch returns the distance actually consumed, including at either boundary.
            // Leave an unhandled wheel event to the surrounding vertical content at a boundary.
            if (state.dispatchRawDelta(delta.y * wheelStep) != 0f) event.changes.forEach { it.consume() }
        }
    }
}
