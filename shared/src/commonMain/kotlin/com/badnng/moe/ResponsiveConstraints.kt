package com.badnng.moe

import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize

/**
 * Convert the immutable pixel constraints with the density read by the current composition.
 * BoxWithConstraints' reused measure scope can still contain the preceding density during recomposition.
 */
internal fun Constraints.maxDpSize(density: Density): DpSize = with(density) {
    DpSize(if (hasBoundedWidth) maxWidth.toDp() else Dp.Infinity,
        if (hasBoundedHeight) maxHeight.toDp() else Dp.Infinity)
}
