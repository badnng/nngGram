package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badnng.moe.chat.ChatMessage
import com.badnng.moe.chat.ChatPinnedState
import com.badnng.moe.chat.ChatSnapshot
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Compare real glyphs with an unclipped reference, rather than inspecting only text semantics. */
class PinnedMessageContentRenderTest {
    @Test
    fun bothPinnedLinesKeepTheirCompleteChineseEmojiAndDescenderInkInsideTheHoverSurface() {
        for (density in listOf(1f, 1.25f)) for (width in listOf(240, 480)) {
            val caption = "🥳 感谢 A/ gjpq"
            val title = "已置顶消息 · 3075"
            val snapshot = ChatSnapshot(pinned = ChatPinnedState(
                messages = listOf(ChatMessage(100, 10, text = caption)), totalCount = 3075))
            var referenceOrigin by mutableStateOf<IntOffset?>(null)
            var background = 0
            ComposePixelScene(((width + 40) * density).roundToInt(), (250 * density).roundToInt()) {
                val controller = remember { ThemeController(ColorSchemeMode.Light) }
                CompositionLocalProvider(LocalDensity provides Density(density)) {
                    MiuixTheme(controller = controller) {
                        val colors = MiuixTheme.colorScheme
                        background = colors.surfaceContainer.toArgb()
                        Box(Modifier.fillMaxSize().background(colors.surfaceContainer)) {
                            PinnedMessageBar(snapshot, ChatFeatureActions(), {},
                                Modifier.offset(20.dp, 20.dp).width(width.dp))
                            referenceOrigin?.let { origin ->
                                // The reference starts at the real text's pixel position and is
                                // shifted by a whole-pixel distance, so raster phases match.
                                Column(Modifier.offset { origin }.width(width.dp)) {
                                    Text(title, fontSize = 12.sp, lineHeight = 16.sp,
                                        fontWeight = FontWeight.Medium, color = colors.primary)
                                    Text(caption, fontSize = 13.sp, lineHeight = 18.sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }.use { scene ->
                scene.settledFrame()
                val actualTitle = assertNotNull(scene.textBounds(title).singleOrNull())
                referenceOrigin = IntOffset(actualTitle.left.roundToInt(), actualTitle.top.roundToInt() + (120 * density).roundToInt())
                val frame = scene.settledFrame()
                for (text in listOf(title, caption)) {
                    val bounds = scene.textBounds(text).sortedBy { it.top }
                    assertTrue(bounds.size == 2, "The actual pinned line and its uncut reference must both be drawn.")
                    assertCompleteInk(frame, bounds[0], bounds[1], background, "$width/$density/$text")
                }
            }
        }
    }

    private fun assertCompleteInk(frame: ComposePixelFrame, actual: Rect, reference: Rect, background: Int, fixture: String) {
        val offsetX = actual.left.roundToInt() - reference.left.roundToInt()
        val offsetY = actual.top.roundToInt() - reference.top.roundToInt()
        var referenceInk = 0
        var missingInk = 0
        for (y in reference.top.roundToInt() until reference.bottom.roundToInt()) {
            for (x in reference.left.roundToInt() until reference.right.roundToInt()) {
                val expected = frame[x, y]
                if (distance(expected, background) >= 40) {
                    referenceInk++
                    if (distance(frame[x + offsetX, y + offsetY], expected) >= 20) missingInk++
                }
            }
        }
        assertTrue(referenceInk > 30, "The comparison must observe actual text/emoji pixels: $fixture")
        assertTrue(missingInk <= 2,
            "The pinned action's rounded clip must not remove any line's real glyph ink: $fixture, missing=$missingInk/$referenceInk")
    }

    private fun ComposePixelScene.textBounds(text: String): List<Rect> = unmergedSemanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == text }
    }

    private fun distance(first: Int, second: Int): Int = listOf(16, 8, 0).maxOf {
        abs((first ushr it and 255) - (second ushr it and 255))
    }
}
