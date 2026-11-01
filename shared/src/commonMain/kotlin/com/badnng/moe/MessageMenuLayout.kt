package com.badnng.moe

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import kotlin.math.roundToInt

internal enum class MessageMenuSide { BELOW, ABOVE, RIGHT, LEFT }

internal data class MessageMenuPlacement(val body: IntRect, val side: MessageMenuSide) {
    val maxSize: IntSize get() = IntSize(body.width, body.height)
    val above: Boolean get() = side == MessageMenuSide.ABOVE
}

/** The actions use free space beside the actual bubble; the source never enters this solver. */
internal fun anchoredMessageMenuPlacement(
    anchor: IntRect,
    viewport: IntRect,
    desired: IntSize,
    shadow: Int,
    gap: Int,
    minimumHeight: Int,
    alignEnd: Boolean = false,
    openingSide: MessageMenuSide? = null,
): MessageMenuPlacement {
    val insetX = shadow.coerceIn(0, (viewport.width - 1).coerceAtLeast(0) / 2)
    val insetY = shadow.coerceIn(0, (viewport.height - 1).coerceAtLeast(0) / 2)
    val safe = IntRect(viewport.left + insetX, viewport.top + insetY,
        viewport.right - insetX, viewport.bottom - insetY)
    val width = desired.width.coerceIn(1, safe.width.coerceAtLeast(1))
    val height = desired.height.coerceIn(1, safe.height.coerceAtLeast(1))
    val below = (safe.bottom - anchor.bottom - gap).coerceAtLeast(0)
    val above = (anchor.top - gap - safe.top).coerceAtLeast(0)
    val right = (safe.right - anchor.right - gap).coerceAtLeast(0)
    val left = (anchor.left - gap - safe.left).coerceAtLeast(0)
    // Sideways panels keep a source-relative top through initial measurement and later
    // native rows. Their available height grows downward, without a virtual tall menu
    // first pushing that top to the viewport edge.
    val sideTop = anchor.top.coerceIn(safe.top,
        (safe.bottom - minOf(minimumHeight, safe.height.coerceAtLeast(1))).coerceAtLeast(safe.top))
    val sideHeight = (safe.bottom - sideTop).coerceAtLeast(1)
    val side = openingSide ?: when {
        below >= height -> MessageMenuSide.BELOW
        above >= height -> MessageMenuSide.ABOVE
        right >= width && sideHeight > maxOf(below, above) -> MessageMenuSide.RIGHT
        left >= width && sideHeight > maxOf(below, above) -> MessageMenuSide.LEFT
        maxOf(below, above) >= minOf(minimumHeight, height) ->
            if (below >= above) MessageMenuSide.BELOW else MessageMenuSide.ABOVE
        // A window-filling or partly visible media message may leave no usable free side.
        // Keep a small scrollable action panel against the least overlapping vertical edge.
        else -> if (below >= above) MessageMenuSide.BELOW else MessageMenuSide.ABOVE
    }
    val verticalSpace = if (side == MessageMenuSide.BELOW) below else above
    val bodyHeight = if (side == MessageMenuSide.BELOW || side == MessageMenuSide.ABOVE)
        minOf(height, maxOf(verticalSpace, minOf(minimumHeight, safe.height.coerceAtLeast(1)))) else minOf(height, sideHeight)
    val desiredX = when (side) {
        MessageMenuSide.RIGHT -> anchor.right + gap
        MessageMenuSide.LEFT -> anchor.left - gap - width
        else -> if (alignEnd) anchor.right - width else anchor.left
    }
    val desiredY = when (side) {
        MessageMenuSide.BELOW -> anchor.bottom + gap
        MessageMenuSide.ABOVE -> anchor.top - gap - bodyHeight
        else -> sideTop
    }
    val x = desiredX.coerceIn(safe.left, (safe.right - width).coerceAtLeast(safe.left))
    val y = desiredY.coerceIn(safe.top, (safe.bottom - bodyHeight).coerceAtLeast(safe.top))
    return MessageMenuPlacement(IntRect(x, y, x + width, y + bodyHeight), side)
}

/** The native window includes only the source and actions, rather than the entire desktop. */
internal class MessageMenuOverlayPosition : PopupPositionProvider {
    var origin: IntOffset = IntOffset.Zero
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
        layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset = origin
}

/** One display list stays at its original coordinates, independently of the animated actions. */
@Composable
internal fun MessageAnchoredMenuLayout(
    source: MessageMenuPreview,
    viewport: IntSize,
    placement: MessageMenuPlacement,
    shadow: Int,
    expanded: Boolean,
    position: MessageMenuOverlayPosition,
    onDismiss: () -> Unit,
    fractionProgress: () -> Float,
    alphaProgress: () -> Float,
    rightAligned: Boolean,
    menuContent: @Composable () -> Unit,
) {
    val sourceExclusion = remember { arrayOfNulls<IntRect>(1) }
    val shape = rememberMiuixSquircleShape(15.dp)
    val visible = source.visibleBounds
    val left = visible.left.roundToInt().coerceIn(0, viewport.width)
    val top = visible.top.roundToInt().coerceIn(0, viewport.height)
    val right = visible.right.roundToInt().coerceIn(left, viewport.width)
    val bottom = visible.bottom.roundToInt().coerceIn(top, viewport.height)
    Layout(modifier = Modifier.pointerInput(expanded) { detectTapGestures { if (expanded) onDismiss() } },
        content = {
            Box { menuContent() }
            Box(Modifier.clipToBounds().drawWithContent {
                val exclude = sourceExclusion[0]
                val progress = fractionProgress().coerceIn(0f, 1f)
                if (exclude == null || progress <= 0f || alphaProgress() <= 0f) {
                    translate(source.bounds.left - left, source.bounds.top - top) { drawLayer(source.layer) }
                } else {
                    // Match chatShortcutAnimatedSurface's native Miuix outline, revealed
                    // height and scale. Only its actually revealed pixels replace source
                    // pixels; the source paints over shadow, including on tall media.
                    val visibleHeight = exclude.height * progress
                    val clipTop = if (placement.above) exclude.height - visibleHeight else 0f
                    val scale = 0.15f + 0.85f * progress
                    val originX = if (rightAligned) exclude.width.toFloat() else 0f
                    val originY = if (placement.above) exclude.height.toFloat() else 0f
                    val outline = shape.createOutline(Size(exclude.width.toFloat(), visibleHeight), layoutDirection, this)
                    val path = Path().apply {
                        when (outline) {
                            is Outline.Generic -> addPath(outline.path)
                            is Outline.Rounded -> addRoundRect(outline.roundRect)
                            is Outline.Rectangle -> addRect(outline.rect)
                        }
                        transform(Matrix().apply {
                            this[0, 0] = scale
                            this[1, 1] = scale
                            this[3, 0] = exclude.left - left + originX * (1f - scale)
                            this[3, 1] = exclude.top - top + originY * (1f - scale) + clipTop * scale
                        })
                    }
                    clipPath(path, ClipOp.Difference) {
                        translate(source.bounds.left - left, source.bounds.top - top) { drawLayer(source.layer) }
                    }
                }
            }
                .then(if (expanded) Modifier.semantics { contentDescription = "原位消息" }
                    else Modifier.clearAndSetSemantics {}))
        }) { measurables, constraints ->
        val menu = measurables[0].measure(Constraints(maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight))
        val row = measurables[1].measure(Constraints.fixed(right - left, bottom - top))
        val body = placement.body
        val menuLeft = (body.left - shadow).coerceAtLeast(0)
        val menuTop = (if (placement.above) body.bottom - (menu.height - shadow * 2) - shadow
            else body.top - shadow).coerceAtLeast(0)
        val menuBody = IntRect(menuLeft + shadow, menuTop + shadow,
            menuLeft + menu.width - shadow, menuTop + menu.height - shadow)
        sourceExclusion[0] = menuBody
        val origin = IntOffset(minOf(left, menuLeft), minOf(top, menuTop))
        position.origin = origin
        val width = maxOf(right, menuLeft + menu.width) - origin.x
        val height = maxOf(bottom, menuTop + menu.height) - origin.y
        layout(width, height) {
            menu.place(menuLeft - origin.x, menuTop - origin.y)
            row.place(left - origin.x, top - origin.y)
        }
    }
}
