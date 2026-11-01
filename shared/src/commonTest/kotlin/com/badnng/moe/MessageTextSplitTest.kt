package com.badnng.moe

import com.badnng.moe.chat.MessageEntity
import com.badnng.moe.chat.MessageEntityKind
import com.badnng.moe.chat.RichMessageText
import com.badnng.moe.chat.splitMessageText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessageTextSplitTest {
    @Test fun nativeLimitCountsCodePointsWhileEntityPositionsRemainUtf16() {
        val text = "🙂".repeat(9)
        val source = RichMessageText(text, listOf(MessageEntity(0, text.length, MessageEntityKind.CODE)))
        val parts = splitMessageText(source, 4)
        assertEquals(listOf(8, 8, 2), parts.map { it.text.length })
        parts.forEach { assertEquals(listOf(MessageEntity(0, it.text.length, MessageEntityKind.CODE)), it.entities) }
        assertReconstructs(source, parts, 4)
    }

    @Test fun exactLimitIsOneMessageAndInvalidEntitiesNeverEscape() {
        val valid = MessageEntity(1, 2, MessageEntityKind.BOLD)
        val source = RichMessageText("abcd", listOf(valid, MessageEntity(3, 8, MessageEntityKind.ITALIC)))
        assertEquals(listOf(RichMessageText("abcd", listOf(valid))), splitMessageText(source, 4))
        assertEquals(emptyList(), splitMessageText(RichMessageText(""), 4))
        assertFailsWith<IllegalArgumentException> { splitMessageText(source, 0) }
    }

    @Test fun paragraphsBeatLaterWordsAndNoWhitespaceIsTrimmed() {
        val source = RichMessageText("abcdefghijk\n\nmore words follow here")
        val parts = splitMessageText(source, 20)
        assertEquals("abcdefghijk", parts.first().text)
        assertTrue(parts[1].text.startsWith("\n\n"))
        assertReconstructs(source, parts, 20)
    }

    @Test fun sentenceAndWordBoundariesUseTheOfficialPriorityAndLatestTie() {
        assertEquals("abcdefghijk.", splitMessageText(RichMessageText("abcdefghijk. words here plus"), 20).first().text)
        assertEquals("abcdefghijk words", splitMessageText(RichMessageText("abcdefghijk words here plus"), 20).first().text)
        assertEquals("abcdefghijk,", splitMessageText(RichMessageText("abcdefghijk, words here plus"), 20).first().text)
    }

    @Test fun shortLinkMovesIntactToTheNextPartWithItsNativeTarget() {
        val url = "https://example.org/path"
        val text = "abcdefghijklmnopq $url tail"
        val source = RichMessageText(text, listOf(MessageEntity(18, url.length, MessageEntityKind.TEXT_URL, url)))
        val parts = splitMessageText(source, 30)
        assertEquals("abcdefghijklmnopq", parts.first().text)
        assertEquals(MessageEntity(1, url.length, MessageEntityKind.TEXT_URL, url), parts[1].entities.single())
        assertReconstructs(source, parts, 30)
    }

    @Test fun forcedCutsDropTheEntireNonBreakableEntityInsteadOfInventingShorterLinksOrStyles() {
        for (kind in listOf(MessageEntityKind.URL, MessageEntityKind.TEXT_URL, MessageEntityKind.BOLD,
            MessageEntityKind.ITALIC, MessageEntityKind.SPOILER, MessageEntityKind.MENTION_NAME, MessageEntityKind.CUSTOM_EMOJI)) {
            val source = RichMessageText("x".repeat(31), listOf(MessageEntity(0, 31, kind, "https://example.org", 42)))
            val parts = splitMessageText(source, 10)
            assertTrue(parts.all { it.entities.isEmpty() }, kind.toString())
            assertReconstructs(source, parts, 10)
        }
    }

    @Test fun codeAndQuoteEntitiesClipAcrossCutsWithoutLosingLanguageOrUtf16Offsets() {
        for (kind in listOf(MessageEntityKind.CODE, MessageEntityKind.PRE,
            MessageEntityKind.BLOCK_QUOTE, MessageEntityKind.EXPANDABLE_BLOCK_QUOTE)) {
            val source = RichMessageText("🙂".repeat(13), listOf(MessageEntity(0, 26, kind, "kotlin")))
            val parts = splitMessageText(source, 5)
            assertTrue(parts.all { it.entities == listOf(MessageEntity(0, it.text.length, kind, "kotlin")) })
            assertReconstructs(source, parts, 5)
        }
    }

    @Test fun entitiesAfterTheCutShiftAndNestedEntitiesStayValid() {
        val source = RichMessageText("abcdefghijklmnopqrstuvwx", listOf(
            MessageEntity(0, 24, MessageEntityKind.PRE, "kotlin"),
            MessageEntity(15, 3, MessageEntityKind.BOLD),
            MessageEntity(20, 4, MessageEntityKind.TEXT_URL, "https://example.org"),
        ))
        val parts = splitMessageText(source, 12)
        assertEquals(MessageEntity(3, 3, MessageEntityKind.BOLD), parts[1].entities.first { it.kind == MessageEntityKind.BOLD })
        assertEquals(MessageEntity(8, 4, MessageEntityKind.TEXT_URL, "https://example.org"),
            parts[1].entities.first { it.kind == MessageEntityKind.TEXT_URL })
        assertReconstructs(source, parts, 12)
    }

    @Test fun emojiSequencesAndCrLfNeverBecomeHalfAnEmojiOrHalfALineEnding() {
        for (unit in listOf("👨‍👩‍👧‍👦", "👍🏽", "🇨🇳", "1️⃣", "☀️", "\r\n")) {
            val source = RichMessageText("x".repeat(13) + unit + "y".repeat(20))
            val parts = splitMessageText(source, 16)
            var position = 0
            parts.dropLast(1).forEach {
                position += it.text.length
                assertFalse(position > 13 && position < 13 + unit.length, unit)
            }
            assertReconstructs(source, parts, 16)
        }
    }

    @Test fun impossibleEmojiUnitStillMakesBoundedProgressWithoutBreakingSurrogatePairs() {
        val source = RichMessageText("👨‍👩‍👧‍👦")
        assertReconstructs(source, splitMessageText(source, 2), 2)
        assertReconstructs(RichMessageText("\r\n"), splitMessageText(RichMessageText("\r\n"), 1), 1)
    }

    @Test fun mixedUnicodeAndEntityInputsReconstructExactlyAtManyNativeLimits() {
        val text = ("一段文字 🙂 words, more!\r\n👩🏽‍💻\n\n").repeat(17)
        val source = RichMessageText(text, listOf(MessageEntity(0, text.length, MessageEntityKind.BLOCK_QUOTE)))
        for (limit in listOf(1, 2, 3, 7, 11, 32, 63, 128, 4096)) {
            assertReconstructs(source, splitMessageText(source, limit), limit)
        }
    }

    private fun assertReconstructs(source: RichMessageText, parts: List<RichMessageText>, limit: Int) {
        assertEquals(source.text, parts.joinToString("") { it.text })
        var totalOffset = 0
        parts.forEach { part ->
            assertTrue(part.text.isNotEmpty())
            val surrogatePairs = part.text.zipWithNext().count { (a, b) -> a.isHighSurrogate() && b.isLowSurrogate() }
            assertTrue(part.text.length - surrogatePairs <= limit)
            assertEquals(part.entities, part.validEntities())
            totalOffset += part.text.length
            assertFalse(totalOffset < source.text.length && source.text[totalOffset - 1].isHighSurrogate() &&
                source.text[totalOffset].isLowSurrogate())
        }
    }
}
