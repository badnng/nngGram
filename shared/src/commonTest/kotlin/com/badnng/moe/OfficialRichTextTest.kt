package com.badnng.moe

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.badnng.moe.chat.*
import kotlin.test.*

class OfficialRichTextTest {
    @Test fun expandableQuoteKeepsItsNativeTypeAndNestedUtf16Styles() {
        val source = RichMessageText("🙂前文\n引用🙂内容\n后文", listOf(
            MessageEntity(5, 6, MessageEntityKind.EXPANDABLE_BLOCK_QUOTE),
            MessageEntity(5, 2, MessageEntityKind.BOLD),
        ))
        val quote = source.displayBlocks().single { it.kind == MessageEntityKind.EXPANDABLE_BLOCK_QUOTE }
        assertEquals(5, quote.sourceOffset)
        assertEquals("引用🙂内容", quote.content.text)
        assertEquals(listOf(MessageEntity(0, 2, MessageEntityKind.BOLD)), quote.content.entities)
        assertEquals(source, source.editorValue().richText())
        assertEquals(3, OFFICIAL_COLLAPSED_QUOTE_LINES)
    }

    @Test fun quoteDoesNotInventItalicAndReceivedPlainUrlsDoNotInventEntities() {
        val quoted = RichMessageText("引用 **原文**", listOf(MessageEntity(0, 9, MessageEntityKind.BLOCK_QUOTE)))
        val result = displayRichText(quoted, Color.Black, Color.Blue, Color.Gray)
        assertEquals(quoted.text, result.text)
        assertFalse(result.spanStyles.any { it.item.fontStyle == FontStyle.Italic || it.item.fontWeight == FontWeight.Bold })
        val plain = displayRichText(RichMessageText("https://example.org **plain**"), Color.Black, Color.Blue, Color.Gray, onLink = {})
        assertTrue(plain.getLinkAnnotations(0, plain.length).isEmpty())
    }

    @Test fun hiddenSpoilerClickRevealsBeforeAnyNestedLinkCanActivate() {
        var revealed = false
        var opened = false
        val content = RichMessageText("隐藏链接", listOf(MessageEntity(0, 4, MessageEntityKind.SPOILER),
            MessageEntity(0, 4, MessageEntityKind.TEXT_URL, "https://example.org")))
        val hidden = displayRichText(content, Color.Black, Color.Blue, Color.Gray,
            onRevealSpoiler = { revealed = true }, onLink = { opened = true })
        val reveal = assertIs<LinkAnnotation.Clickable>(hidden.getLinkAnnotations(0, 4).single().item)
        reveal.linkInteractionListener?.onClick(reveal)
        assertTrue(revealed)
        assertFalse(opened)
        val visible = displayRichText(content, Color.Black, Color.Blue, Color.Gray, revealSpoilers = true, onLink = { opened = true })
        assertIs<LinkAnnotation.Url>(visible.getLinkAnnotations(0, 4).single().item)
    }

    @Test fun whitespaceInsideNativePreAndQuoteBlocksIsNotDiscarded() {
        val source = RichMessageText("A\n \nB", listOf(MessageEntity(2, 1, MessageEntityKind.PRE, "text")))
        val code = source.displayBlocks().single { it.kind == MessageEntityKind.PRE }
        assertEquals(" ", code.content.text)
        assertEquals(2, code.sourceOffset)
    }

    @Test fun codeHeaderCopiesTheOriginalCodeWithExactlyOneRequiredTrailingNewline() {
        assertEquals("code\n", codeBlockClipboardText("code"))
        assertEquals("code\n", codeBlockClipboardText("code\n"))
        assertEquals("code\n\n", codeBlockClipboardText("code\n\n"))
    }

    @Test fun changingExpandableQuoteToOrdinaryQuoteDoesNotNestTwoBlockEntities() {
        val source = RichMessageText("引用内容", listOf(MessageEntity(0, 4, MessageEntityKind.EXPANDABLE_BLOCK_QUOTE)))
        val edited = source.editorValue(TextRange(0, 4)).applyFormat(MessageEntityKind.BLOCK_QUOTE).richText()
        assertEquals(listOf(MessageEntity(0, 4, MessageEntityKind.BLOCK_QUOTE)), edited.entities)
    }
}
