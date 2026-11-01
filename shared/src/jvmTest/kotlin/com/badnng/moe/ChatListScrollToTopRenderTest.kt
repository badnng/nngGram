package com.badnng.moe

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.badnng.moe.chat.ChatItem
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.ChatToken
import com.badnng.moe.chat.MediaFile
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/** Exercise the actual lazy list, visibility wrapper, backdrop and floating action together. */
class ChatListScrollToTopRenderTest {
    @Test
    fun clickingTheRealListActionScrollsThroughSeveralFramesWithoutClippingItsShadow() {
        for (density in listOf(1f, 1.25f)) for (narrow in listOf(false, true)) verifyReturn(density, narrow)
    }

    private fun verifyReturn(density: Float, narrow: Boolean) {
        val photo = Files.createTempFile("nnggram-list-return-", ".png")
        try {
            // The left strip stays neutral while rows move, including avatar regions.
            // That lets us inspect a real shadow profile rather than mistaking a newly
            // exposed colorful avatar for a clipped shadow or comparing old/new pixels.
            val image = BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until 8) for (x in 0 until 8) image.setRGB(x, y, 0xffffffff.toInt())
            check(ImageIO.write(image, "png", photo.toFile()))
            val file = MediaFile(700, downloaded = true, localPath = photo.toString())
            val rows = (0 until 40).map { index ->
                ChatItem(index + 1L, "第${index.toString().padStart(2, '0')}个合成会话", order = 100L - index, avatar = file)
            }
            val snapshot = ChatSnapshot(enabled = true, token = ChatToken(1, 0, null), items = rows,
                knownChats = rows, listEnd = true, mediaFiles = mapOf(file.id to file))
            val widthDp = if (narrow) 260 else 354
            val width = (widthDp * density).roundToInt()
            val height = (500 * density).roundToInt()
            var selected = 0
            ComposePixelScene(width, height) {
                val colors = remember { lightColorScheme(surface = Color.White, surfaceContainer = Color.White) }
                CompositionLocalProvider(LocalDensity provides Density(density),
                    LocalDesktopChatLayout provides DesktopChatLayout(compact = narrow, showRail = !narrow)) {
                    MiuixTheme(colors = colors) {
                        ChatListPane(snapshot, onSelect = { selected++ }, onRetryLoad = {}, onSelectList = {},
                            onManageFolders = {}, features = ChatFeatureActions(), modifier = Modifier.fillMaxSize())
                    }
                }
            }.use { scene ->
                scene.moveTo(width - 2f, height - 2f)
                scene.settledFrame()
                val firstRow = assertNotNull(scene.rowBounds(rows.first().title).firstOrNull { it.height > density })
                assertEquals(0f, scene.listPosition(), 0.001f)
                assertTrue(scene.topButtonBounds().isEmpty(), "The return action must start hidden at the actual top.")

                repeat(6) {
                    if (scene.listPosition() < 5f) {
                        scene.scrollAt(width / 2f, height / 2f, deltaY = 4f)
                        scene.settledFrame(8)
                    }
                }
                val from = scene.listPosition()
                assertTrue(from >= 5f, "Real wheel input must move several of the forty native list rows: $density/$narrow/$from")
                val opened = scene.settledFrame()
                val button = assertNotNull(scene.topButtonBounds().singleOrNull(), "The scrolled list must expose one real return action.")
                assertTrue(button.left >= 8 * density && button.top >= 8 * density &&
                    button.right <= width - 8 * density && button.bottom <= height - 8 * density,
                    "The button's actual exterior needs room inside the pane: $button")
                assertShadow(opened, button, density, "before click")

                scene.moveTo(button.center.x, button.center.y)
                scene.pressAt(button.center.x, button.center.y)
                scene.releaseAt(button.center.x, button.center.y)
                scene.moveTo(width - 2f, height - 2f)
                val positions = mutableListOf<Float>()
                var shadowFrames = 0
                repeat(20) { index ->
                    val frame = scene.settledFrame(1)
                    val position = scene.listPosition()
                    positions += position
                    val currentButton = scene.topButtonBounds().singleOrNull()
                    if (position >= 1f) {
                        val bounds = assertNotNull(currentButton, "Returning from a later row must retain the action: frame $index")
                        assertTrue(abs(bounds.left - button.left) <= 1 && abs(bounds.top - button.top) <= 1 &&
                            abs(bounds.width - button.width) <= 1 && abs(bounds.height - button.height) <= 1,
                            "The visibility wrapper must not crop or reshape the circle on click: $bounds vs $button")
                        assertShadow(frame, bounds, density, "return frame $index")
                        shadowFrames++
                    }
                }
                val middlePositions = positions.filter { it > 0.001f && it < from - 0.001f }
                assertTrue(middlePositions.map { (it * 100_000).roundToInt() }.distinct().size >= 2,
                    "The real list must pass through multiple intermediate scroll positions, not flash to the first row: $positions")
                assertTrue(shadowFrames >= 2, "The exterior must be inspected during actual returning frames.")
                assertTrue(positions.zipWithNext().all { (before, after) -> after <= before + 0.001f },
                    "Returning to the first row must keep moving toward the top: $positions")
                scene.settledFrame()
                assertEquals(0f, scene.listPosition(), 0.001f)
                val restoredRow = assertNotNull(scene.rowBounds(rows.first().title).firstOrNull { it.height > density })
                assertTrue(abs(restoredRow.top - firstRow.top) <= density && abs(restoredRow.bottom - firstRow.bottom) <= density,
                    "The first row must return to its original fully visible position below the measured header.")
                assertTrue(scene.topButtonBounds().isEmpty(), "The action must finish its real exit after reaching the top.")
                assertEquals(0, selected, "Clicking the overlay action must not select the chat behind it.")
            }
        } finally { Files.deleteIfExists(photo) }
    }

    private fun assertShadow(frame: ComposePixelFrame, button: Rect, density: Float, phase: String) {
        val y = button.center.y.roundToInt()
        val near = frame[(button.left - 2 * density).roundToInt(), y]
        val far = frame[(button.left - 7 * density).roundToInt(), y]
        assertTrue(luminance(far) - luminance(near) >= 2,
            "The soft shadow must really continue outside the circular control during $phase: $near -> $far")
        // Inspect the visibility wrapper's exterior in this same frame. Comparing a
        // neighbouring spatial profile tolerates resampling of the changing list.
        val exterior = (button.left - 8 * density).roundToInt()
        val outer = frame[(exterior - density).roundToInt(), y]
        val inner = frame[(exterior + density).roundToInt(), y]
        assertTrue(abs(luminance(inner) - luminance(outer)) <= 8,
            "The shadow must fade across the visibility wrapper edge instead of exposing a cut rectangle during $phase.")
    }

    private fun ComposePixelScene.listPosition(): Float {
        val values = mutableListOf<Float>()
        semanticsBounds {
            it.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.let { range ->
                if (range.maxValue() > 0f) values += range.value()
            }
            false
        }
        return assertNotNull(values.maxOrNull(), "The real LazyColumn must expose its vertical scroll position.")
    }

    private fun ComposePixelScene.topButtonBounds() = semanticsBounds {
        it.getOrNull(SemanticsProperties.Role) == Role.Button &&
            "回到顶部" in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }

    private fun ComposePixelScene.rowBounds(title: String) = semanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == title }
    }

    private fun luminance(pixel: Int): Int = ((pixel ushr 16 and 255) + (pixel ushr 8 and 255) + (pixel and 255)) / 3
}
