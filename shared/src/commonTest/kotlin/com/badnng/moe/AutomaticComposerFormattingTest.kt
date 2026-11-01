package com.badnng.moe

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.badnng.moe.chat.MessageEntity
import com.badnng.moe.chat.MessageEntityKind
import com.badnng.moe.chat.RichMessageText
import com.badnng.moe.chat.parseComposerMarkdown
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals

class AutomaticComposerFormattingTest {
    @Test fun closingMarkupBecomesAStyleAndKeepsTheCaretAfterChineseAndEmoji() {
        val result = automaticComposerFormatting(TextFieldValue("🙂 **中文*"), TextFieldValue("🙂 **中文**", TextRange(9)))
        assertEquals("🙂 中文", result.text)
        assertEquals(TextRange(5), result.selection)
        assertEquals(listOf(MessageEntity(3, 2, MessageEntityKind.BOLD)), result.richText().entities)
    }

    @Test fun incompleteDoubleDelimitersAndEscapedLiteralsRemainEditable() {
        for (text in listOf("**文字*", "__文字_", "\\*\\*文字\\*\\*", "```kotlin\n尚未结束")) {
            val next = TextFieldValue(text, TextRange(text.length))
            assertEquals(next, automaticComposerFormatting(TextFieldValue(), next))
        }
    }

    @Test fun compositionIsUntouchedUntilTheImeCommitsIt() {
        val composing = TextFieldValue("**中文**", TextRange(6), TextRange(2, 4))
        assertEquals(composing, automaticComposerFormatting(TextFieldValue("**中**"), composing))
        val committed = automaticComposerFormatting(composing, composing.copy(composition = null))
        assertEquals("中文", committed.text)
        assertEquals(TextRange(2), committed.selection)
    }

    @Test fun selectionDirectionAndUnchangedFormattingSurviveMarkerRemoval() {
        val next = RichMessageText("**前文** 后文", listOf(MessageEntity(7, 2, MessageEntityKind.ITALIC)))
            .editorValue(TextRange(9, 2))
        val result = automaticComposerFormatting(TextFieldValue(), next)
        assertEquals("前文 后文", result.text)
        assertEquals(TextRange(5, 0), result.selection)
        assertEquals(listOf(MessageEntity(0, 2, MessageEntityKind.BOLD), MessageEntity(3, 2, MessageEntityKind.ITALIC)), result.richText().entities)
    }

    @Test fun formattedCodeKeepsItsLiteralMarkers() {
        val next = RichMessageText("**literal**", listOf(MessageEntity(0, 11, MessageEntityKind.CODE))).editorValue()
        assertEquals(next, automaticComposerFormatting(TextFieldValue(), next))
    }

    @Test fun officialItalicRetainsUtf16PositionsAndReverseSelection() {
        val source = "🙂 __中文__"
        val parsed = RichMessageText(source).parseComposerMarkdown()
        assertContentEquals(intArrayOf(0, 1, 2, 3, 3, 3, 4, 5, 5, 5), parsed.positions)
        val next = TextFieldValue(source, TextRange(source.length, 3))
        val result = automaticComposerFormatting(TextFieldValue(), next)
        assertEquals("🙂 中文", result.text)
        assertEquals(TextRange(5, 3), result.selection)
        assertEquals(listOf(MessageEntity(3, 2, MessageEntityKind.ITALIC)), result.richText().entities)
    }

    @Test fun pastedUrlSelectionAndCompositionSurviveNearbyFormatting() {
        val url = "https://example.org/__路径__/test"
        val source = "🙂 $url __中文__"
        val end = source.indexOf(" __中文__")
        val next = TextFieldValue(source, TextRange(end, 3))
        val result = automaticComposerFormatting(TextFieldValue(), next)
        assertEquals("🙂 $url 中文", result.text)
        assertEquals(next.selection, result.selection)
        assertEquals(url, result.text.substring(result.selection.min, result.selection.max))
        val composing = next.copy(composition = TextRange(source.length - 4, source.length - 2))
        assertEquals(composing, automaticComposerFormatting(TextFieldValue(), composing))
    }
}
