package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.*
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import kotlin.test.*
import top.yukonga.miuix.kmp.theme.*

class OfficialRichTextRenderTest {
    @Test fun nativeBlocksSpoilersAndQuotesRenderAndInteractAtBothScalesAndThemes() {
        for (dark in listOf(false, true)) for (density in listOf(1f, 1.25f)) for (width in listOf(260, 440)) {
            val quote = "原生引用第 1 行🙂\n第 2 行：中文和 English\n第 3 行保留完整字形\n第 4 行展开后出现\n第 5 行显示结束"
            val code = "// 充满中文的注释 42\nfun main() {\n    val count = 42\n    println(\"你好🙂\")\n}"
            val message = "粗体、斜体和链接\n$quote\n$code\n结尾🙂"
            val quoteOffset = message.indexOf(quote)
            val codeOffset = message.indexOf(code)
            val content = RichMessageText(message, listOf(
                MessageEntity(0, 2, MessageEntityKind.BOLD), MessageEntity(3, 2, MessageEntityKind.ITALIC),
                MessageEntity(6, 2, MessageEntityKind.TEXT_URL, "https://example.org"),
                MessageEntity(quoteOffset, quote.length, MessageEntityKind.EXPANDABLE_BLOCK_QUOTE),
                MessageEntity(codeOffset, code.length, MessageEntityKind.PRE, "kotlin"),
            ))
            val spoilerText = "点击直接揭示隐藏文字🙂"
            val spoiler = RichMessageText(spoilerText, listOf(MessageEntity(0, spoilerText.length, MessageEntityKind.SPOILER)))
            ComposePixelScene(((width + 40) * density).roundToInt(), (640 * density).roundToInt()) {
                val controller = remember { ThemeController(if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light) }
                CompositionLocalProvider(LocalDensity provides Density(density)) {
                    MiuixTheme(controller = controller) {
                        Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surfaceContainer)) {
                            Column(Modifier.padding(20.dp).width(width.dp)
                                .chatSquircleBackground(MiuixTheme.colorScheme.surface, 15.dp).padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                RichMessageBody(content)
                                RichMessageBody(spoiler)
                            }
                        }
                    }
                }
            }.use { scene ->
                val fixture = "${if (dark) "dark" else "light"}-$width-${density}x"
                scene.settledFrame()
                val collapsed = assertNotNull(scene.action("展开引用"))
                val copy = assertNotNull(scene.action("复制代码"))
                val codeBounds = assertNotNull(scene.text(code))
                assertTrue(copy.top > collapsed.bottom, "Code header must belong to the code block: $fixture")
                assertTrue(copy.bottom <= codeBounds.top + 2, "Header must be above its own code: $fixture")
                assertTrue(codeBounds.height >= 5 * 17 * density, "Preformatted code must retain every explicit newline: $fixture")
                scene.awaitSyntaxColors(codeBounds).export("$fixture-collapsed")
                scene.pressAt(collapsed.left + 3 * density, collapsed.top + 3 * density)
                scene.releaseAt(collapsed.left + 3 * density, collapsed.top + 3 * density)
                val expandedFrame = scene.settledFrame()
                val expanded = assertNotNull(scene.action("收起引用"))
                assertTrue(expanded.height > collapsed.height + 25 * density, "Expandable native quote must show remaining lines: $fixture")
                expandedFrame.export("$fixture-expanded")
                scene.pressAt(expanded.left + 3 * density, expanded.top + 3 * density)
                scene.releaseAt(expanded.left + 3 * density, expanded.top + 3 * density)
                val beforeReveal = scene.settledFrame()
                val spoilerBounds = assertNotNull(scene.text(spoilerText))
                scene.pressAt(spoilerBounds.center.x, spoilerBounds.center.y)
                scene.releaseAt(spoilerBounds.center.x, spoilerBounds.center.y)
                val revealed = scene.settledFrame()
                assertEquals(spoilerBounds, scene.text(spoilerText), "Reveal must never add a separate card or change text measurement")
                assertTrue(changedPixels(beforeReveal, revealed, spoilerBounds) > 15, "Direct spoiler click must reveal actual glyph pixels: $fixture")
                revealed.export("$fixture-revealed")
            }
        }
    }

    @Test fun protectedMessageKeepsFullBlockLayoutWithoutSelectionOrCopyAction() {
        val code = "println(\"受保护消息\")"
        val content = RichMessageText(code, listOf(MessageEntity(0, code.length, MessageEntityKind.PRE, "kotlin")))
        ComposePixelScene(360, 180) {
            val controller = remember { ThemeController(ColorSchemeMode.Light) }
            MiuixTheme(controller = controller) {
                Box(Modifier.width(320.dp).padding(12.dp)) { RichMessageBody(content, allowCopy = false) }
            }
        }.use { scene ->
            scene.settledFrame()
            assertNotNull(scene.text(code))
            assertNotNull(scene.text("kotlin"))
            assertNull(scene.action("复制代码"))
            assertTrue(scene.semanticsBounds { it.getOrNull(SemanticsActions.CopyText) != null }.isEmpty())
        }
    }

    private fun ComposePixelScene.action(label: String): Rect? = semanticsBounds {
        it.getOrNull(SemanticsActions.OnClick)?.label == label
    }.singleOrNull()

    private fun ComposePixelScene.text(text: String): Rect? = unmergedSemanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == text }
    }.firstOrNull()

    private fun changedPixels(first: ComposePixelFrame, second: ComposePixelFrame, bounds: Rect): Int {
        var changed = 0
        for (y in bounds.top.toInt().coerceAtLeast(0) until bounds.bottom.toInt().coerceAtMost(first.height))
            for (x in bounds.left.toInt().coerceAtLeast(0) until bounds.right.toInt().coerceAtMost(first.width))
                if (first[x, y] != second[x, y]) changed++
        return changed
    }

    private fun ComposePixelScene.awaitSyntaxColors(bounds: Rect): ComposePixelFrame {
        repeat(80) {
            val frame = settledFrame(2)
            var blue = 0
            for (y in bounds.top.toInt().coerceAtLeast(0) until bounds.bottom.toInt().coerceAtMost(frame.height))
                for (x in bounds.left.toInt().coerceAtLeast(0) until bounds.right.toInt().coerceAtMost(frame.width)) {
                    val color = frame[x, y]
                    val red = color ushr 16 and 255
                    val green = color ushr 8 and 255
                    val blueChannel = color and 255
                    if (blueChannel > red + 30 && blueChannel > green + 15) blue++
                }
            if (blue > 10) return frame
            Thread.sleep(20)
        }
        fail("Native language grammar must eventually color the code without replacing or truncating the text")
    }

    private fun ComposePixelFrame.export(name: String) {
        val directory = Path.of(System.getProperty("nnggram.richtext.render.output",
            Path.of(System.getProperty("java.io.tmpdir"), "official-richtext-renders").toString()))
        Files.createDirectories(directory)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, this[x, y])
        assertTrue(ImageIO.write(image, "png", directory.resolve("$name.png").toFile()))
    }
}
