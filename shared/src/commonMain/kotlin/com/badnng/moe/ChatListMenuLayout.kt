package com.badnng.moe

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider
import kotlin.math.roundToInt

internal object ChatListOverlayPosition : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
        layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset = IntOffset.Zero
}

internal data class ChatListMenuPlacement(val offset: IntOffset, val above: Boolean)

/** Only the actions avoid the window edges. Their source never participates in this calculation. */
internal fun anchoredChatListMenuPlacement(anchor: IntRect, window: IntSize, menu: IntSize,
    shadow: Int, gap: Int, layoutDirection: LayoutDirection = LayoutDirection.Ltr): ChatListMenuPlacement {
    val bodyHeight = (menu.height - 2 * shadow).coerceAtLeast(0)
    val below = (window.height - anchor.bottom - gap - shadow).coerceAtLeast(0)
    val above = (anchor.top - gap - shadow).coerceAtLeast(0)
    val opensAbove = bodyHeight > below && above > below
    val x = if (layoutDirection == LayoutDirection.Rtl) anchor.right - menu.width + shadow else anchor.left - shadow
    val y = if (opensAbove) anchor.top - gap - bodyHeight - shadow else anchor.bottom + gap - shadow
    return ChatListMenuPlacement(IntOffset(
        x.coerceIn(0, (window.width - menu.width).coerceAtLeast(0)),
        y.coerceIn(0, (window.height - menu.height).coerceAtLeast(0))), opensAbove)
}

/** One fixed source layer and an independently measured menu share the native overlay window. */
@Composable
internal fun ChatListAnchoredMenuLayout(
    source: ChatListMenuPreview,
    viewport: IntSize,
    shadow: Int,
    gap: Int,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onDirection: (Boolean) -> Unit,
    menuContent: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val visible = source.visibleBounds
    val left = visible.left.roundToInt()
    val top = visible.top.roundToInt()
    val width = visible.width.roundToInt().coerceAtLeast(0)
    val height = visible.height.roundToInt().coerceAtLeast(0)
    Layout(modifier = Modifier.requiredSize(with(density) { viewport.width.toDp() }, with(density) { viewport.height.toDp() })
        .pointerInput(expanded) { detectTapGestures { if (expanded) onDismiss() } },
        content = {
            Box { menuContent() }
            Box(Modifier.clipToBounds().drawWithContent {
                translate(source.bounds.left - left, source.bounds.top - top) { drawLayer(source.layer) }
            }.pointerInput(Unit) { detectTapGestures {} }
                .then(if (expanded) Modifier.semantics { contentDescription = "会话卡片" }
                    else Modifier.clearAndSetSemantics {}))
        }) { measurables, constraints ->
        val menu = measurables[0].measure(Constraints(maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight))
        val card = measurables[1].measure(Constraints.fixed(width, height))
        val placement = anchoredChatListMenuPlacement(IntRect(left, top, left + width, top + height),
            viewport, IntSize(menu.width, menu.height), shadow, gap, layoutDirection)
        onDirection(placement.above)
        layout(viewport.width, viewport.height) {
            menu.place(placement.offset)
            // The layer never scales or fades with the menu; its original composition remains mounted.
            card.place(left, top)
        }
    }
}
