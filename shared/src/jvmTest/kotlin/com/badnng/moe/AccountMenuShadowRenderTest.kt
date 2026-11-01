package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.status.ConnectionPhase
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.lightColorScheme

class AccountMenuShadowRenderTest {
    @Test
    fun animatedShadowContinuesBeyondTheCardBoundsWithoutExposingARectangle() {
        for (densityScale in listOf(1f, 1.25f)) {
            for (height in listOf(600, 900)) verifyShadowAtDensity(densityScale, height)
        }
    }

    private fun verifyShadowAtDensity(densityScale: Float, height: Int) {
        val viewport = IntSize((580 * densityScale).toInt(), (height * densityScale).toInt())
        val anchor = Rect(8f * densityScale, 8f * densityScale, 60f * densityScale, 52f * densityScale)
        val position = accountMenuPosition(viewport, anchor, densityScale)
        val background = 221
        for (progress in listOf(0.5f, 0.99f, 1f)) {
            ComposePixelScene(viewport.width, viewport.height) {
                val controller = remember { ThemeController(ColorSchemeMode.Light) }
                CompositionLocalProvider(LocalDensity provides Density(densityScale)) {
                    MiuixTheme(controller = controller) {
                        Box(Modifier.fillMaxSize().background(Color(0xffdddddd))) {
                            AccountMenuOverlay(
                                snapshot = ChatSnapshot(), features = ChatFeatureActions(),
                                connection = ConnectionPhase.READY, anchor = anchor, viewport = viewport,
                                darkTheme = false, onDarkTheme = {}, showPhone = false, onShowPhone = {},
                                onProfile = {}, onSavedMessages = null, onArchive = {},
                                onManageFolders = {}, onSettings = {}, onDismiss = {},
                                progress = progress, interactive = false,
                            )
                        }
                    }
                }
            }.use { scene ->
                val frame = scene.settledFrame()
                // Observe the actual fill boundary instead of assuming maxHeight: natural-height
                // cards exposed an extra seam which the constrained-height fixture did not catch.
                val scale = 0.92f + 0.08f * progress
                val left = position.left + position.arrowCenter * (1f - scale)
                val right = left + position.width * scale
                val top = position.top - 8f * densityScale * (1f - progress)
                val centerX = ((left + right) / 2).toInt()
                val fillTop = ceil(top + 14f * densityScale * scale).toInt()
                val fillColor = frame[centerX, fillTop] and 255
                val bottom = (fillTop until viewport.height).last { (frame[centerX, it] and 255) >= fillColor - 1 } + 1
                // Include the interior of the rounded-away corner: the old elevation shadow
                // had a light occluder ledge here while its outermost columns were smooth.
                val cornerXs = listOf(1, 3, 7, 11).map { ceil(left).toInt() + (it * densityScale).toInt() } +
                    listOf(2, 4, 8, 12).map { floor(right).toInt() - (it * densityScale).toInt() }
                for (x in cornerXs) {
                    val before = frame[x, bottom] and 0xff
                    val after = frame[x, bottom + 1] and 0xff
                    assertTrue(abs(before - after) <= 6,
                        "Shadow is cut for density=$densityScale, progress=$progress, x=$x: $before -> $after")
                    assertTrue(before <= background + 2 && after <= background + 2,
                        "A light rectangular surface escaped the rounded corner for progress=$progress, x=$x")
                }
            }
        }
    }

    @Test
    fun menuCornersRetainPartialPixelCoverageAtStandardAndFractionalDensity() {
        for (densityScale in listOf(1f, 1.25f)) {
            val viewport = IntSize((580 * densityScale).toInt(), (600 * densityScale).toInt())
            val anchor = Rect(8f * densityScale, 8f * densityScale, 60f * densityScale, 52f * densityScale)
            val position = accountMenuPosition(viewport, anchor, densityScale)
            ComposePixelScene(viewport.width, viewport.height) {
                val colors = remember { lightColorScheme(surfaceContainer = Color.Red) }
                CompositionLocalProvider(LocalDensity provides Density(densityScale)) {
                    MiuixTheme(colors = colors) {
                        Box(Modifier.fillMaxSize().background(Color(0xffdddddd))) {
                            AccountMenuOverlay(ChatSnapshot(), ChatFeatureActions(), ConnectionPhase.READY, anchor, viewport,
                                false, {}, false, {}, {}, null, {}, {}, {}, {}, progress = 1f, interactive = false)
                        }
                    }
                }
            }.use { scene ->
                val frame = scene.settledFrame()
                val coverage = mutableListOf<Int>()
                val left = floor(position.left).toInt()
                val top = floor(position.top + 10f * densityScale).toInt()
                val corner = ceil(22f * densityScale).toInt()
                // Red fill against neutral background/shadow separates silhouette coverage from
                // blur gradients. Text and the avatar are outside this top-left corner patch.
                for (y in top + 2 until top + corner) for (x in left until left + corner) {
                    val pixel = frame[x, y]
                    val redCoverage = ((pixel ushr 16) and 255) - ((pixel ushr 8) and 255)
                    if (redCoverage in 2..253) coverage += redCoverage
                }
                assertTrue(coverage.size >= 8 && coverage.distinct().size >= 4,
                    "Rounded menu silhouette lost antialiasing at density=$densityScale: $coverage")
                val arrowCoverage = mutableListOf<Int>()
                val arrowCenter = position.left + position.arrowCenter
                for (y in ceil(position.top).toInt() + 1 until top) {
                    for (x in floor(arrowCenter - 10f * densityScale).toInt() until ceil(arrowCenter + 10f * densityScale).toInt()) {
                        val pixel = frame[x, y]
                        val redCoverage = ((pixel ushr 16) and 255) - ((pixel ushr 8) and 255)
                        if (redCoverage in 2..253) arrowCoverage += redCoverage
                    }
                }
                assertTrue(arrowCoverage.size >= 4,
                    "Menu arrow lost antialiasing at density=$densityScale: $arrowCoverage")
            }
        }
    }
}
