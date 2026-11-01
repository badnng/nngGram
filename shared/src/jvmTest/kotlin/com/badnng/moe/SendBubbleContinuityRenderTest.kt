package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Sample every animation frame instead of checking only the settled send/delivery result. */
class SendBubbleContinuityRenderTest {
    @Test
    fun aLateSendIdentityAndAcceptedRetryNeverFadeOrMoveAnAlreadyVisibleRow() {
        for (mode in modes) for (density in densities) {
            val fixture = Fixture(mode, density)
            fixture.scene().use { scene ->
                scene.settledFrame(16, stepMillis = 16)
                fixture.assertResting(scene, scene.settledFrame(1, stepMillis = 16), "before send identity")

                fixture.plan.value = SendEntrancePlan(1, 11, alreadyVisible = true)
                repeat(16) { frame ->
                    fixture.assertResting(scene, scene.settledFrame(1, stepMillis = 16),
                        "late accepted send identity, frame $frame")
                }

                // A retry has a new native request identity but retains the same drawn row.
                fixture.plan.value = SendEntrancePlan(1, 12, alreadyVisible = true)
                repeat(16) { frame ->
                    fixture.assertResting(scene, scene.settledFrame(1, stepMillis = 16),
                        "accepted retry identity, frame $frame")
                }
            }
        }
    }

    @Test
    fun losingActivityOrLatestIntentCompletesTheEntranceWithoutReplayingItOnReturn() {
        for (mode in modes) for (density in densities) for (cancel in listOf("inactive", "older reading")) {
            val fixture = Fixture(mode, density)
            fixture.plan.value = SendEntrancePlan(1, 11, alreadyVisible = false)
            fixture.scene().use { scene ->
                val entering = scene.settledFrame(3, stepMillis = 16)
                assertNotEquals(ARTWORK, entering[fixture.px(64), fixture.px(44)],
                    "The cancellation probe must actually interrupt an unfinished entrance: $mode/$density/$cancel")

                if (cancel == "inactive") fixture.active.value = false else fixture.atLatest.value = false
                // Yield once for the ownership effect, then inspect the next drawn frame.
                fixture.assertResting(scene, scene.settledFrame(2, stepMillis = 16), "cancelled by $cancel")
                repeat(3) { frame ->
                    fixture.assertResting(scene, scene.settledFrame(1, stepMillis = 16),
                        "cancelled by $cancel, frame $frame")
                }

                fixture.active.value = true
                fixture.atLatest.value = true
                repeat(16) { frame ->
                    fixture.assertResting(scene, scene.settledFrame(1, stepMillis = 16),
                        "ownership restored after $cancel, frame $frame")
                }

                // LazyColumn can dispose an offscreen item and later compose the same event.
                fixture.mounted.value = false
                scene.settledFrame(2, stepMillis = 16)
                fixture.mounted.value = true
                repeat(16) { frame ->
                    fixture.assertResting(scene, scene.settledFrame(1, stepMillis = 16),
                        "same event remounted after $cancel, frame $frame")
                }
            }
        }
    }

    private class Fixture(private val mode: ColorSchemeMode, private val density: Float) {
        val plan = mutableStateOf<SendEntrancePlan?>(null)
        val active = mutableStateOf(true)
        val atLatest = mutableStateOf(true)
        val mounted = mutableStateOf(true)
        private val tracker = SendAnimationTracker()
        private val restingBounds = Rect(32f * density, 24f * density, 96f * density, 64f * density)

        fun px(dp: Int): Int = (dp * density).roundToInt()

        fun scene() = ComposePixelScene(px(160), px(100)) {
            val theme = remember { ThemeController(mode) }
            CompositionLocalProvider(LocalDensity provides Density(density)) {
                MiuixTheme(controller = theme) {
                    Box(Modifier.size(160.dp, 100.dp).background(MiuixTheme.colorScheme.surface)) {
                        if (mounted.value) Box(Modifier.offset(32.dp, 24.dp)) {
                            SendBubbleEntrance(plan.value, tracker, active = active.value, atLatest = atLatest.value) {
                                // This opaque, fixed-size body isolates the real entrance layer:
                                // its top band detects translation, its center detects fading.
                                Box(Modifier.size(64.dp, 40.dp).background(Color(ARTWORK))
                                    .semantics { contentDescription = CONTENT })
                            }
                        }
                    }
                }
            }
        }

        fun assertResting(scene: ComposePixelScene, frame: ComposePixelFrame, stage: String) {
            val bounds = assertNotNull(scene.semanticsBounds {
                CONTENT in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            }.singleOrNull(), "The same content must remain mounted: $mode/$density/$stage")
            assertEquals(restingBounds, bounds,
                "An already drawn or cancelled send must retain its position: $mode/$density/$stage")
            for ((x, y) in listOf(36 to 28, 92 to 28, 64 to 44, 36 to 60, 92 to 60)) {
                assertEquals(ARTWORK, frame[px(x), px(y)],
                    "The actual entrance layer must stay fully opaque and stationary at $x/$y: $mode/$density/$stage")
            }
        }
    }

    private companion object {
        val ARTWORK = 0xFF25A752.toInt()
        const val CONTENT = "synthetic send continuity body"
        val modes = listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)
        val densities = listOf(1f, 1.25f)
    }
}
