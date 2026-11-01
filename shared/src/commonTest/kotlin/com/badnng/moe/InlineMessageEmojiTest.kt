package com.badnng.moe

import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import com.badnng.moe.chat.MessageEntity
import com.badnng.moe.chat.MessageEntityKind
import com.badnng.moe.chat.RichMessageText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InlineMessageEmojiTest {
    @Test
    fun customEmojiKeepTheExactNativeUtf16RangeAndOriginalText() {
        val source = RichMessageText("前😀后", listOf(MessageEntity(1, 2, MessageEntityKind.CUSTOM_EMOJI, id = 42)))
        val decorated = AnnotatedString.Builder(source.text).apply {
            addStyle(SpanStyle(color = Color.Blue), 0, source.text.length)
            addStringAnnotation("source", "unchanged", 0, source.text.length)
        }.toAnnotatedString()
        val result = decorated.withInlineMessageEmoji(source)
        val inlineTag = buildAnnotatedString { appendInlineContent("example", "😀") }
            .getStringAnnotations(0, 2).single().tag

        assertEquals(source.text, result.text)
        assertEquals(decorated.spanStyles, result.spanStyles)
        assertEquals(decorated.getStringAnnotations("source", 0, source.text.length),
            result.getStringAnnotations("source", 0, source.text.length))
        val inline = result.getStringAnnotations(inlineTag, 0, result.length).single()
        assertEquals(1, inline.start)
        assertEquals(3, inline.end)
        assertEquals("inline-message-emoji:1:42", inline.item)
    }

    @Test
    fun hiddenSpoilersKeepLayoutAnnotationsWithoutSupplyingInlineResources() {
        val emoji = MessageEntity(1, 2, MessageEntityKind.CUSTOM_EMOJI, id = 42)
        val source = RichMessageText("前😀后", listOf(emoji,
            MessageEntity(0, 4, MessageEntityKind.SPOILER)))
        assertTrue(source.inlineMessageEmojiEntities().isEmpty())
        assertEquals(AnnotatedString(source.text).withInlineMessageEmoji(source, revealSpoilers = true),
            AnnotatedString(source.text).withInlineMessageEmoji(source),
            "The source paragraph and native placeholder geometry cannot change when a spoiler is revealed.")
        assertEquals(listOf(emoji), source.inlineMessageEmojiEntities(revealSpoilers = true))
    }

    @Test
    fun overlappingSpoilersHideTheEntireCustomEmoji() {
        val source = RichMessageText("abc", listOf(
            MessageEntity(0, 2, MessageEntityKind.CUSTOM_EMOJI, id = 42),
            MessageEntity(1, 2, MessageEntityKind.SPOILER),
        ))
        assertTrue(source.inlineMessageEmojiEntities().isEmpty())
    }

    @Test
    fun malformedSurrogateRangesAndZeroIdentitiesStayAsSourceText() {
        val source = RichMessageText("😀x", listOf(
            MessageEntity(1, 1, MessageEntityKind.CUSTOM_EMOJI, id = 42),
            MessageEntity(2, 1, MessageEntityKind.CUSTOM_EMOJI, id = 0),
        ))
        assertTrue(source.inlineMessageEmojiEntities().isEmpty())
        assertEquals(AnnotatedString(source.text), AnnotatedString(source.text).withInlineMessageEmoji(source))
    }

    @Test
    fun duplicateAndOverlappingNativeRangesNeverCreateOverlappingPlaceholders() {
        val first = MessageEntity(0, 2, MessageEntityKind.CUSTOM_EMOJI, id = 42)
        val last = MessageEntity(2, 1, MessageEntityKind.CUSTOM_EMOJI, id = 44)
        val source = RichMessageText("abc", listOf(first, first,
            MessageEntity(1, 1, MessageEntityKind.CUSTOM_EMOJI, id = 43), last))
        assertEquals(listOf(first, last), source.inlineMessageEmojiEntities())
    }

    @Test
    fun ordinaryEmojiWithoutANativeCustomEntityAreNeverReplaced() {
        val source = RichMessageText("😀 :emoji: **plain**")
        assertTrue(source.inlineMessageEmojiEntities().isEmpty())
        assertEquals(AnnotatedString(source.text), AnnotatedString(source.text).withInlineMessageEmoji(source))
    }
}
