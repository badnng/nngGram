package com.badnng.moe

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import com.badnng.moe.chat.*
import kotlin.test.*

class MessageQuoteSelectionTest {
    @Test fun compactCodeLayoutKeepsQuoteCoordinatesAndOmittedSeparators() {
        val source = RichMessageText("开头\n代码\n结尾", listOf(MessageEntity(3, 2, MessageEntityKind.PRE, "kotlin")))
        val blocks = source.displayBlocks().map { it.withoutBlockSeparator(source.text.length) }
        assertEquals(listOf("开头", "代码", "结尾"), blocks.map { it.content.text })
        assertEquals(listOf(0, 3, 6), blocks.map { it.sourceOffset })
        val selections = blocks.map { AnnotatedString(it.content.text).withQuoteSourceOffsets(it.sourceOffset) }
        assertEquals(source, assertNotNull(source.quoteFromSelection(selections)).content)
        val tail = assertNotNull(source.quoteFromSelection(listOf(selections.last())))
        assertEquals(6, tail.position)
        assertEquals("结尾", tail.content.text)
    }

    @Test fun compactCodeLayoutPreservesIntentionalBlankLinesAndCodeWhitespace() {
        val source = RichMessageText("开头\r\n\r\ncode\r\n\r\n结尾",
            listOf(MessageEntity(6, 4, MessageEntityKind.PRE, "text")))
        val blocks = source.displayBlocks().map { it.withoutBlockSeparator(source.text.length) }
        assertEquals(listOf("开头\r\n", "code", "\r\n结尾"), blocks.map { it.content.text })
        assertEquals(listOf(0, 6, 12), blocks.map { it.sourceOffset })
        val selections = blocks.map { AnnotatedString(it.content.text).withQuoteSourceOffsets(it.sourceOffset) }
        assertEquals(source, assertNotNull(source.quoteFromSelection(selections)).content)
        val code = RichMessageBlock(RichMessageText("\ncode\n"), MessageEntityKind.PRE, "text", 3)
        assertSame(code, code.withoutBlockSeparator(12))
    }

    @Test fun repeatedTextQuotesTheSelectedOccurrenceAndClipsItsFormatting() {
        val source = RichMessageText("重复 前文 重复 后文", listOf(MessageEntity(6, 5, MessageEntityKind.BOLD)))
        val selected = AnnotatedString(source.text).withQuoteSourceOffsets().subSequence(6, 8)
        val quote = assertNotNull(source.quoteFromSelection(listOf(selected)))
        assertEquals(6, quote.position)
        assertEquals("重复", quote.content.text)
        assertEquals(listOf(MessageEntity(0, 2, MessageEntityKind.BOLD)), quote.content.entities)
    }

    @Test fun forwardAndReverseSelectionsKeepEmojiAndUtf16Positions() {
        val source = RichMessageText("🙂前文\n选中🙂文字 后文")
        val display = AnnotatedString(source.text).withQuoteSourceOffsets()
        for (range in listOf(TextRange(5, 11), TextRange(11, 5))) {
            val quote = assertNotNull(source.quoteFromSelection(listOf(display.subSequence(range.min, range.max))))
            assertEquals(5, quote.position)
            assertEquals("选中🙂文字", quote.content.text)
        }
        assertNull(source.quoteFromSelection(listOf(display.subSequence(1, 3))))
        assertNull(source.quoteFromSelection(listOf(display.subSequence(0, 1))))
    }

    @Test fun selectionAcrossBlocksRestoresOriginalWhitespaceAndNativeEntities() {
        val source = RichMessageText("开头\n\n代码\n\n结尾", listOf(MessageEntity(4, 2, MessageEntityKind.PRE, "kotlin")))
        val selected = source.displayBlocks().map { block ->
            AnnotatedString(block.content.text).withQuoteSourceOffsets(block.sourceOffset)
        }
        val quote = assertNotNull(source.quoteFromSelection(selected))
        assertEquals(0, quote.position)
        assertEquals(source, quote.content)
        val code = assertNotNull(source.quoteFromSelection(listOf(selected[1])))
        assertEquals(4, code.position)
        assertEquals("代码", code.content.text)
        assertEquals(listOf(MessageEntity(0, 2, MessageEntityKind.PRE, "kotlin")), code.content.entities)
    }

    @Test fun blockSelectionSkipsLeadingWhitespaceWithoutMatchingAnEarlierCopy() {
        val source = RichMessageText("同文\n\n同文", listOf(MessageEntity(4, 2, MessageEntityKind.BLOCK_QUOTE)))
        val block = source.displayBlocks().last()
        val selected = AnnotatedString(block.content.text).withQuoteSourceOffsets(block.sourceOffset)
        assertEquals(4, assertNotNull(source.quoteFromSelection(listOf(selected))).position)
    }

    @Test fun selectionAcrossTwoCodeBlocksIncludesTheirOmittedBlankSeparator() {
        val source = RichMessageText("🙂\n\n代码", listOf(MessageEntity(0, 2, MessageEntityKind.PRE, "text"),
            MessageEntity(4, 2, MessageEntityKind.PRE, "kotlin")))
        val blocks = source.displayBlocks()
        assertEquals(2, blocks.size)
        val selected = blocks.map { AnnotatedString(it.content.text).withQuoteSourceOffsets(it.sourceOffset) }
        assertEquals(source, assertNotNull(source.quoteFromSelection(selected)).content)
    }

    @Test fun missingEmptyWhitespaceAndStaleSelectionsCannotCreateQuotes() {
        val source = RichMessageText("原文 空白 ")
        val display = AnnotatedString(source.text).withQuoteSourceOffsets()
        assertNull(source.quoteFromSelection(emptyList()))
        assertNull(source.quoteFromSelection(listOf(AnnotatedString("原文"))))
        assertNull(source.quoteFromSelection(listOf(display.subSequence(2, 3))))
        assertNull(source.quoteFromSelection(listOf(display.subSequence(0, 0))))
        assertNull(RichMessageText("修改 空白 ").quoteFromSelection(listOf(display.subSequence(0, 2))))
    }

    @Test fun noncontiguousOrReorderedPartsCannotQuoteUnselectedWords() {
        val source = RichMessageText("前文 中间 后文")
        val display = AnnotatedString(source.text).withQuoteSourceOffsets()
        val first = display.subSequence(0, 2)
        val last = display.subSequence(6, 8)
        assertNull(source.quoteFromSelection(listOf(first, last)))
        assertNull(source.quoteFromSelection(listOf(last, first)))
        assertNull(source.quoteFromSelection(listOf(first, first)))
    }
}
