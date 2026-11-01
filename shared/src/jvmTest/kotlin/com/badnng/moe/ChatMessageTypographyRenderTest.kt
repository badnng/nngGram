package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.RichMessageText
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class ChatMessageTypographyRenderTest {
    @Test
    fun messagesKeepNativeLineDensityWithoutResizingTheirTypeWhenWindowBecomesCompact() {
        val latin = "Open Sans Agyj 013\nNative message line two\nThird line is still visible"
        val chinese = "中文与 English 混排\n第二行带表情 🙂\n最后一行保留完整字形"
        for (dark in listOf(false, true)) for (density in listOf(1f, 1.25f)) {
            ComposePixelScene((700 * density).roundToInt(), (260 * density).roundToInt()) {
                val controller = remember { ThemeController(if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light) }
                CompositionLocalProvider(LocalDensity provides Density(density)) {
                    MiuixTheme(controller = controller) {
                        Row(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface).padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            for (compact in listOf(false, true)) {
                                CompositionLocalProvider(LocalDesktopChatLayout provides DesktopChatLayout(compact = compact)) {
                                    Column(Modifier.width(320.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                        RichMessageBody(RichMessageText(latin))
                                        RichMessageBody(RichMessageText(chinese))
                                    }
                                }
                            }
                        }
                    }
                }
            }.use { scene ->
                val frame = scene.settledFrame()
                for (text in listOf(latin, chinese)) {
                    val results = mutableListOf<TextLayoutResult>()
                    val bounds = scene.unmergedSemanticsBounds { semantics ->
                        val matches = semantics.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text }
                        if (matches) semantics.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
                        matches
                    }
                    assertEquals(2, bounds.size, "Both real layouts must retain their complete native message")
                    assertEquals(2, results.size)
                    assertEquals(bounds[0].height, bounds[1].height,
                        "Changing window layout must not enlarge or shrink message text")
                    assertEquals(bounds[0].width, bounds[1].width,
                        "The same message keeps its glyph advances in both layout modes")
                    results.forEach { layout ->
                        assertEquals(3, layout.lineCount, "Explicit native newlines must retain three complete lines")
                        assertEquals(text.length, layout.getLineEnd(2), "The last native character must not be truncated")
                        assertTrue(layout.size.height / density < 60f,
                            "Three message lines use font metrics instead of the old fixed 63dp paragraph: ${layout.size}")
                        assertTrue(!layout.hasVisualOverflow, "Font metrics must retain Chinese, descenders and emoji")
                    }
                    assertTrue(frame.inkPixels(bounds.first()) > 100 * density,
                        "Actual foreground glyphs must be painted, rather than only a semantics placeholder")
                }
                frame.export("${if (dark) "dark" else "light"}-${density}x")
            }
        }
    }

    private fun ComposePixelFrame.inkPixels(bounds: Rect): Int {
        val background = this[(bounds.left + 1).toInt(), (bounds.top + 1).toInt()]
        var count = 0
        for (y in bounds.top.toInt() until bounds.bottom.toInt()) for (x in bounds.left.toInt() until bounds.right.toInt()) {
            val value = this[x, y]
            if (abs((value and 255) - (background and 255)) > 40) count++
        }
        return count
    }

    private fun ComposePixelFrame.export(name: String) {
        val directory = Path.of(System.getProperty("java.io.tmpdir"), "chat-message-typography")
        Files.createDirectories(directory)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, this[x, y])
        assertTrue(ImageIO.write(image, "png", directory.resolve("$name.png").toFile()))
    }
}
