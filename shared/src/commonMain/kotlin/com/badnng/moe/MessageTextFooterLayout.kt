package com.badnng.moe

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * The native trailing text skip-block reserves metadata space on the final LTR line.
 * Keep the text and selection offsets intact: the independently measured footer occupies
 * that region only when its actual final-line geometry permits it, otherwise it gets a row.
 */
@Composable
internal fun MessageTextFooterLayout(
    canInline: Boolean,
    modifier: Modifier = Modifier,
    layoutKey: Any? = Unit,
    body: @Composable (onTextLayout: (TextLayoutResult) -> Unit) -> Unit,
    footer: @Composable () -> Unit,
) {
    // BasicText reports its current layout synchronously while measuring. This is measurement
    // data, not composition state, and must not trigger a second composition or text copy.
    val metrics = remember(layoutKey) { MessageTextFooterMetrics() }
    val measurePolicy = remember(canInline, metrics) { MessageTextFooterMeasurePolicy(canInline, metrics) }
    Layout(content = {
        Box { body { metrics.text = it } }
        Box { footer() }
    }, modifier = modifier, measurePolicy = measurePolicy)
}

private class MessageTextFooterMetrics {
    var text: TextLayoutResult? = null
}

private class MessageTextFooterMeasurePolicy(
    private val canInline: Boolean,
    private val metrics: MessageTextFooterMetrics,
) : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val children = constraints.copy(minWidth = 0, minHeight = 0)
        // MessageReactionRow owns fillMaxWidth for its reaction layout. When it contains
        // metadata alone, constrain it to that natural width so it can occupy the skip block.
        val footerConstraints = if (canInline) children.copy(maxWidth =
            measurables[1].maxIntrinsicWidth(constraints.maxHeight).coerceAtMost(children.maxWidth)) else children
        val footer = measurables[1].measure(footerConstraints)
        val body = measurables[0].measure(children)
        // history_view_element.cpp: msgDateSpace(12) - msgDateDelta.x(2).
        val gap = 10.dp.roundToPx()
        val preferredWidth = if (canInline) addWidths(body.width, gap, footer.width) else max(body.width, footer.width)
        val width = constraints.constrainWidth(preferredWidth)
        val text = metrics.text
        val lastLine = (text?.lineCount ?: 0) - 1
        val lineTop = if (text != null && lastLine >= 0) floor(text.getLineTop(lastLine)).toInt() else 0
        val lineAdvance = if (text != null && lastLine >= 0) ceil(text.multiParagraph.getLineHeight(lastLine)).toInt() else 0
        val lineBottom = if (text != null && lastLine >= 0)
            max(max(ceil(text.getLineBottom(lastLine)).toInt(), text.size.height), lineTop + lineAdvance) else 0
        val lastCharacter = if (text != null && lastLine >= 0)
            (text.getLineEnd(lastLine, visibleEnd = true) - 1)
                .coerceIn(0, (text.layoutInput.text.length - 1).coerceAtLeast(0)) else 0
        val inline = canInline && text != null && lastLine >= 0 && !text.hasVisualOverflow &&
            text.getParagraphDirection(text.getLineStart(lastLine)) == ResolvedTextDirection.Ltr &&
            // A desktop paragraph can inherit LTR while its final Arabic/Hebrew run is RTL.
            // Native skip-block layout treats that trailing direction as a separate row too.
            (text.layoutInput.text.isEmpty() || text.getBidiRunDirection(lastCharacter) == ResolvedTextDirection.Ltr) &&
            // SkiaParagraph floors getLineBottom, while its measured paragraph keeps trailing
            // leading (for example line bottom 17, actual single-line height 18). The final
            // line owns that measured remainder; do not force a valid single-line footer down.
            footer.height <= lineBottom - lineTop &&
            ceil(text.getLineRight(lastLine)).toInt() + gap <= width - footer.width
        val footerY = if (inline) {
            (lineBottom - footer.height).coerceAtLeast(lineTop)
        } else {
            // A text skip-block consumes its own height; media/reaction footer sections have
            // the separate native mediaInBubbleSkip(5) between their body and metadata.
            body.height + if (canInline) 0 else 5.dp.roundToPx()
        }
        val height = constraints.constrainHeight(max(body.height, footerY + footer.height))
        return layout(width, height) {
            body.placeRelative(0, 0)
            footer.placeRelative(width - footer.width, footerY)
        }
    }

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        if (canInline) addWidths(measurables[0].maxIntrinsicWidth(height), 10.dp.roundToPx(), measurables[1].maxIntrinsicWidth(height))
        else measurables.maxOf { it.maxIntrinsicWidth(height) }

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        measurables.maxOf { it.minIntrinsicWidth(height) }

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        intrinsicHeight(measurables, width, minimum = true)

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        intrinsicHeight(measurables, width, minimum = false)

    private fun IntrinsicMeasureScope.intrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int, minimum: Boolean): Int {
        val body = measurables[0]
        val footer = measurables[1]
        val bodyHeight = if (minimum) body.minIntrinsicHeight(width) else body.maxIntrinsicHeight(width)
        val footerHeight = if (minimum) footer.minIntrinsicHeight(width) else footer.maxIntrinsicHeight(width)
        val fits = canInline && addWidths(body.maxIntrinsicWidth(bodyHeight), 10.dp.roundToPx(),
            footer.maxIntrinsicWidth(footerHeight)) <= width
        return if (fits) max(bodyHeight, footerHeight) else addWidths(bodyHeight, if (canInline) 0 else 5.dp.roundToPx(), footerHeight)
    }
}

private fun addWidths(first: Int, gap: Int, last: Int): Int =
    (first.toLong() + gap + last).coerceAtMost(Constraints.Infinity.toLong()).toInt()
