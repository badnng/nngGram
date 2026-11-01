package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals

class IncomingMessageEntranceRenderTest {
    @Test
    fun ordinaryCompletedAndCancelledRowsAllowReactionArtworkOutsideTheirLayoutSlot() {
        for (density in listOf(1f, 1.25f)) for (scenario in listOf("ordinary", "complete", "cancel")) {
            val tracker = IncomingAnimationTracker()
            val active = mutableStateOf(true)
            val plan = if (scenario == "ordinary") null else IncomingEntrancePlan(1, 1, false)
            ComposePixelScene((100 * density).roundToInt(), (100 * density).roundToInt()) {
                CompositionLocalProvider(LocalDensity provides Density(density)) {
                    Box(Modifier.size(100.dp).background(Color.White)) {
                        IncomingMessageEntrance(plan, tracker, active.value, atLatest = true,
                            modifier = Modifier.offset(24.dp, 24.dp)) {
                            // Models the independent 64dp around artwork in a smaller layout
                            // slot. Its parent must relinquish the temporary reveal clip.
                            Box(Modifier.size(32.dp).drawBehind {
                                drawRect(Color.Red, Offset(-12.dp.toPx(), -12.dp.toPx()),
                                    Size(size.width + 24.dp.toPx(), size.height + 24.dp.toPx()))
                            })
                        }
                    }
                }
            }.use { scene ->
                scene.settledFrame(1, stepMillis = 16)
                if (scenario == "cancel") active.value = false
                val frame = scene.settledFrame(if (scenario == "complete") 12 else 3, stepMillis = 16)
                assertEquals(0xFFFF0000.toInt(), frame[(16 * density).roundToInt(), (40 * density).roundToInt()],
                    "The $scenario row must not clip the independent reaction canvas after the reveal stops.")
            }
        }
    }
}
