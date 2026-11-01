package com.badnng.moe

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.badnng.moe.chat.ChatError
import com.badnng.moe.chat.ChatItem
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.ChatToken
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/** Inspect and drag the real list thumb where it crosses the header's blur feather. */
class ChatListScrollbarRenderTest {
    @Test
    fun theRealThumbStaysSharpAboveTheGlassAndDraggingRespectsTheErrorRow() {
        for (density in listOf(1f, 1.25f)) for (narrow in listOf(false, true)) {
            for (dark in listOf(false, true)) verifyThumb(density, narrow, dark)
        }
    }

    private fun verifyThumb(density: Float, narrow: Boolean, dark: Boolean) {
        val chats = (1L..40L).map { ChatItem(it, "合成会话 $it", order = 100 - it) }
        val snapshot = mutableStateOf(ChatSnapshot(enabled = true, token = ChatToken(1, 0, null),
            items = chats, knownChats = chats, listEnd = true))
        val width = ((if (narrow) 260 else 354) * density).roundToInt()
        val height = (500 * density).roundToInt()
        val background = if (dark) Color.Black else Color.White
        val foreground = if (dark) Color.White else Color.Black
        var selected = 0
        ComposePixelScene(width, height) {
            // Neutral, equal pane/card fills make any halo around the thumb observable.
            // All chat content remains in the actual LazyColumn and native scrollbar.
            val colors = remember {
                if (dark) darkColorScheme(surface = background, surfaceContainer = background,
                    onSurfaceVariantSummary = foreground)
                else lightColorScheme(surface = background, surfaceContainer = background,
                    onSurfaceVariantSummary = foreground)
            }
            CompositionLocalProvider(LocalDensity provides Density(density),
                LocalDesktopChatLayout provides DesktopChatLayout(compact = narrow, showRail = !narrow)) {
                MiuixTheme(colors = colors) {
                    ChatListPane(snapshot.value, onSelect = { selected++ }, onRetryLoad = {}, onSelectList = {},
                        onManageFolders = {}, features = ChatFeatureActions(), modifier = Modifier.fillMaxSize())
                }
            }
        }.use { scene ->
            scene.moveTo(width - 1f, height - 1f)
            val initial = scene.settledFrame()
            val tab = assertNotNull(scene.semanticsBounds {
                it.getOrNull(SemanticsProperties.Role) == Role.Tab &&
                    it.getOrNull(SemanticsProperties.Selected) == true
            }.singleOrNull())
            val scanTop = (tab.bottom + 12 * density).roundToInt()
            val thumb = findThumb(initial, density, scanTop, dark)
            assertTrue(thumb.top < tab.bottom + 24 * density,
                "The observed thumb must begin in the real header feather: $thumb, tab=$tab")
            assertSharpThumb(initial, thumb, density, dark)
            if (density == 1f && !narrow) savePreview(initial, dark)
            val before = scene.listPosition()

            dragThumb(scene, thumb, 120 * density)
            scene.moveTo(width - 1f, height - 1f)
            val moved = scene.settledFrame()
            val movedThumb = findThumb(moved, density, scanTop, dark)
            assertTrue(scene.listPosition() > before + 2f,
                "Dragging the actual thumb must scroll several real chat rows.")
            assertTrue(movedThumb.top > thumb.top + 40 * density,
                "The visible thumb must move with the list rather than leaving a sampled ghost behind.")
            assertEquals(0, selected, "Dragging the overlay must not select a chat beneath it.")

            snapshot.value = snapshot.value.copy(listError = ChatError.REQUEST_FAILED)
            val errorFrame = scene.settledFrame()
            val retry = assertNotNull(scene.semanticsBounds {
                it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == "重试" }
            }.singleOrNull(), "The fixture must show the actual bottom error action.")
            val shortenedThumb = findThumb(errorFrame, density, scanTop, dark)
            dragThumb(scene, shortenedThumb, height.toFloat())
            scene.moveTo(width - 1f, height - 1f)
            val bottomThumb = findThumb(scene.settledFrame(), density, scanTop, dark)
            assertTrue(bottomThumb.bottom <= retry.top - density,
                "The real scrollbar's track must end before the bottom error action: $bottomThumb, retry=$retry")
            assertEquals(0, selected)
        }
    }

    private fun findThumb(frame: ComposePixelFrame, density: Float, scanTop: Int, dark: Boolean): Rect {
        val x = (frame.width - 4 * density).roundToInt()
        val background = if (dark) 0 else 255
        val rows = (scanTop until frame.height - 2).filter { y ->
            abs(luminance(frame[x, y]) - background) >= 25
        }
        assertTrue(rows.isNotEmpty(), "The real desktop scrollbar must paint a visible thumb.")
        val top = rows.first()
        val bottom = rows.last() + 1
        assertTrue((top until bottom).all { it in rows }, "The observed thumb must be one continuous shape.")
        assertTrue(bottom - top >= 24 * density, "The pixel scan must locate a full thumb, not a stray glyph.")
        return Rect(frame.width - 6 * density, top.toFloat(), frame.width - 2 * density, bottom.toFloat())
    }

    private fun assertSharpThumb(frame: ComposePixelFrame, thumb: Rect, density: Float, dark: Boolean) {
        val background = if (dark) 0 else 255
        val x = thumb.center.x.roundToInt()
        val topY = (thumb.top + 4 * density).roundToInt()
        val middleY = thumb.center.y.roundToInt()
        assertTrue(abs(luminance(frame[x, topY]) - background) >= 60,
            "The top of the thumb must retain its normal foreground contrast inside the blur feather.")
        assertTrue(abs(luminance(frame[x, topY]) - luminance(frame[x, middleY])) <= 3,
            "The header must not wash out or soften the upper part of the thumb.")
        for (y in topY..middleY) {
            for (outsideX in listOf((thumb.left - density).roundToInt(), (thumb.right + density).roundToInt())) {
                assertTrue(abs(luminance(frame[outsideX, y]) - background) <= 2,
                    "The thumb's sides must remain sharp without a blurred halo at ($outsideX, $y).")
            }
        }
        val above = (thumb.top - 2 * density).roundToInt()
        assertTrue(abs(luminance(frame[x, above]) - background) <= 2,
            "The rounded thumb tip must not leave a sampled blur above its painted shape.")
    }

    private fun dragThumb(scene: ComposePixelScene, thumb: Rect, distance: Float) {
        scene.moveTo(thumb.center.x, thumb.center.y)
        scene.settledFrame(4)
        scene.pressAt(thumb.center.x, thumb.center.y)
        scene.settledFrame(2)
        repeat(4) { step ->
            scene.dragTo(thumb.center.x, thumb.center.y + distance * (step + 1) / 4)
            scene.settledFrame(2)
        }
        scene.releaseAt(thumb.center.x, thumb.center.y + distance)
    }

    private fun ComposePixelScene.listPosition(): Float {
        val values = mutableListOf<Float>()
        semanticsBounds {
            it.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.let { range ->
                if (range.maxValue() > 0f) values += range.value()
            }
            false
        }
        return assertNotNull(values.maxOrNull(), "The real LazyColumn must expose a scroll position.")
    }

    private fun savePreview(frame: ComposePixelFrame, dark: Boolean) {
        val image = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until frame.height) for (x in 0 until frame.width) image.setRGB(x, y, frame[x, y])
        val name = if (dark) "header-scrollbar-dark.png" else "header-scrollbar-light.png"
        check(ImageIO.write(image, "png", File(System.getProperty("java.io.tmpdir"), name)))
    }

    private fun luminance(pixel: Int): Int = ((pixel ushr 16 and 255) + (pixel ushr 8 and 255) + (pixel and 255)) / 3
}
