package com.badnng.moe

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
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp

/** Telegram InlineList's trailing skip block: metadata owns space at the right of the last row. */
@Composable
internal fun MessageReactionFooter(modifier: Modifier = Modifier, alignRight: Boolean = false, content: @Composable () -> Unit) {
    Layout(content, modifier, remember(alignRight) { ReactionFooterMeasurePolicy(alignRight) })
}

private class ReactionFooterMeasurePolicy(private val alignRight: Boolean) : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val gap = 4.dp.roundToPx()
        val rowGap = 4.dp.roundToPx()
        val measured = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        // Image info is painted on the photo, so its reaction row has no trailing
        // metadata. An empty slot must not add a gap or displace the last chip.
        val hasMetadata = measured.lastOrNull()?.let { it.width > 0 || it.height > 0 } == true
        val children = if (hasMetadata) measured else measured.dropLast(1)
        val width = constraints.constrainWidth(children.sumOf { it.width } + gap * (children.size - 1).coerceAtLeast(0))
        val rows = mutableListOf<MutableList<Int>>(mutableListOf())
        var used = 0
        children.forEachIndexed { index, child ->
            if (used > 0 && used + gap + child.width > width) { rows += mutableListOf<Int>(); used = 0 }
            rows.last().add(index)
            used += (if (used == 0) 0 else gap) + child.width
        }
        val heights = rows.map { row -> row.maxOfOrNull { children[it].height } ?: 0 }
        val height = constraints.constrainHeight(heights.sum() + rowGap * (rows.size - 1))
        return layout(width, height) {
            var y = 0
            rows.forEachIndexed { rowIndex, row ->
                var x = 0
                row.forEach { index ->
                    val child = children[index]
                    val childX = when {
                        hasMetadata && index == children.lastIndex -> width - child.width
                        !hasMetadata && alignRight -> width - x - child.width
                        else -> x
                    }
                    child.placeRelative(childX, y + heights[rowIndex] - child.height)
                    x += child.width + gap
                }
                y += heights[rowIndex] + rowGap
            }
        }
    }

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        measurables.sumOf { it.maxIntrinsicWidth(height) } + 4.dp.roundToPx() * (measurables.size - 1).coerceAtLeast(0)

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        measurables.maxOfOrNull { it.minIntrinsicWidth(height) } ?: 0
}
