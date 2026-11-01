package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.ChatMessage
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.ChatToken
import com.badnng.moe.chat.MessageEntity
import com.badnng.moe.chat.MessageEntityKind
import com.badnng.moe.chat.RichMessageText
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class MessageTextFooterRenderTest {
    @Test
    fun shortAndMultilineNativeTextShareTheirLastLineWithMetadataWithoutChangingEntities() {
        for (dark in listOf(false, true)) for (density in listOf(1f, 1.25f)) {
            val messages = listOf(
                RichMessageText("短消息"),
                RichMessageText("First native line\nA shorter end"),
                RichMessageText("Native link", listOf(MessageEntity(0, 6, MessageEntityKind.TEXT_URL, "https://example.org"))),
            )
            ComposePixelScene((380 * density).roundToInt(), (300 * density).roundToInt()) {
                val theme = remember { ThemeController(if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light) }
                CompositionLocalProvider(LocalDensity provides Density(density)) {
                    MiuixTheme(controller = theme) {
                        Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface).padding(16.dp)) {
                            messages.forEachIndexed { index, text -> NativeFooterFixture(text, true, "20:1$index") }
                        }
                    }
                }
            }.use { scene ->
                scene.settledFrame()
                messages.forEachIndexed { index, message ->
                    val text = assertNotNull(scene.textBounds(message.text))
                    val metadata = assertNotNull(scene.textBounds("20:1$index"))
                    val layouts = mutableListOf<TextLayoutResult>()
                    scene.unmergedSemanticsBounds {
                        val match = it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == message.text }
                        if (match) it.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
                        match
                    }
                    val layout = layouts.single()
                    val last = layout.lineCount - 1
                    if (index == 0) println("MESSAGE_TEXT_FOOTER_METRICS density=$density dark=$dark " +
                        "lineTop=${layout.getLineTop(last)} lineBottom=${layout.getLineBottom(last)} " +
                        "lineAdvance=${layout.multiParagraph.getLineHeight(last)} " +
                        "textSize=${layout.size} metadataHeight=${metadata.height}")
                    assertTrue(metadata.top < text.bottom && metadata.bottom <= text.bottom + 1,
                        "Metadata must occupy the actual final text line instead of a second paragraph: $text vs $metadata; " +
                            "density=$density index=$index lineTop=${layout.getLineTop(last)} " +
                            "lineBottom=${layout.getLineBottom(last)} textSize=${layout.size}")
                    assertEquals(message.text.length, layout.getLineEnd(last),
                        "No reserved placeholder may be appended to native text or its selection offsets")
                    assertTrue(metadata.left >= text.left + layout.getLineRight(last) + 9 * density,
                        "The actual trailing glyph needs the official 10dp metadata clearance")
                    if (index == 0) assertTrue((text.height + 16 * density) / density < 37,
                        "A one-line message must retain approximately 34dp native bubble capacity")
                }
            }
        }
    }

    @Test
    fun constrainedTextRtlAndNativeCodeOrQuotesKeepTheirMetadataBelowContent() {
        val wide = RichMessageText("Content needs its last line")
        val rtl = RichMessageText("رسالة عربية")
        val code = "println(\"native text\")"
        val quote = "完整原生引用"
        val messages = listOf(wide, rtl,
            RichMessageText(code, listOf(MessageEntity(0, code.length, MessageEntityKind.PRE))),
            RichMessageText(quote, listOf(MessageEntity(0, quote.length, MessageEntityKind.BLOCK_QUOTE))))
        ComposePixelScene(380, 480) {
            val theme = remember { ThemeController(ColorSchemeMode.Light) }
            MiuixTheme(controller = theme) {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    messages.forEachIndexed { index, text ->
                        NativeFooterFixture(text, index < 2, "21:0$index", narrow = index == 0)
                    }
                }
            }
        }.use { scene ->
            scene.settledFrame()
            messages.forEachIndexed { index, message ->
                val text = assertNotNull(scene.textBounds(message.text))
                val metadata = assertNotNull(scene.textBounds("21:0$index"))
                assertTrue(metadata.top >= text.bottom,
                    "Unavailable trailing space, RTL, code and quotes require an independent metadata row ($index): $text vs $metadata")
            }
        }
    }

    @Test
    fun replacingPlainTextWithAQuoteDiscardsItsOldLineGeometryAndTallMetadataGetsItsOwnRow() {
        val content = mutableStateOf(RichMessageText("Plain native text"))
        val quote = "Quoted replacement"
        ComposePixelScene(380, 320) {
            val theme = remember { ThemeController(ColorSchemeMode.Light) }
            MiuixTheme(controller = theme) {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    // Keep inline eligible deliberately: complex RichMessageBody does not report
                    // a final line, so changing its native layout key must drop previous geometry.
                    NativeFooterFixture(content.value, true, "22:01")
                    val lines = RichMessageText("First native line\nSecond native line\nShort")
                    MessageTextFooterLayout(true, Modifier.width(300.dp), layoutKey = lines,
                        body = { report -> RichMessageBody(lines, onTextLayout = report) },
                        footer = {
                            MessageReactionRow(ChatMessage(2, 10), ChatSnapshot(), ChatFeatureActions()) {
                                Column {
                                    Text("22:02", style = chatMessageTextStyle())
                                    Text("Delivery pending", style = chatMessageTextStyle())
                                    Text("Retry available", style = chatMessageTextStyle())
                                }
                            }
                        })
                }
            }
        }.use { scene ->
            scene.settledFrame()
            val plain = assertNotNull(scene.textBounds(content.value.text))
            assertTrue(assertNotNull(scene.textBounds("22:01")).top < plain.bottom)
            val body = assertNotNull(scene.textBounds("First native line\nSecond native line\nShort"))
            assertTrue(assertNotNull(scene.textBounds("22:02")).top >= body.bottom,
                "A multi-line delivery group must not be overlaid across previous message lines")
            content.value = RichMessageText(quote, listOf(MessageEntity(0, quote.length, MessageEntityKind.BLOCK_QUOTE)))
            scene.settledFrame()
            val replacement = assertNotNull(scene.textBounds(quote))
            assertTrue(assertNotNull(scene.textBounds("22:01")).top >= replacement.bottom,
                "A new native block without a line callback must not reuse old plain-text geometry")
        }
    }

    @Composable
    private fun NativeFooterFixture(text: RichMessageText, inline: Boolean, time: String, narrow: Boolean = false) {
        val message = ChatMessage(1, 10)
        val snapshot = ChatSnapshot(token = ChatToken(1, 1, 10))
        MessageTextFooterLayout(inline,
            Modifier.padding(bottom = 12.dp).widthIn(max = if (narrow) 112.dp else 320.dp).width(IntrinsicSize.Max),
            layoutKey = text,
            body = { onLayout -> RichMessageBody(text, onTextLayout = onLayout) },
            footer = {
                // Exercise the real reaction/footer fillMaxWidth owner, including a constrained
                // long delivery group that cannot occupy the final line's remaining space.
                Box(if (narrow) Modifier.width(100.dp) else Modifier) {
                    MessageReactionRow(message, snapshot, ChatFeatureActions()) {
                        Text(time, style = chatMessageTextStyle())
                    }
                }
            })
    }

    private fun ComposePixelScene.textBounds(text: String): Rect? = unmergedSemanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == text }
    }.firstOrNull()
}
