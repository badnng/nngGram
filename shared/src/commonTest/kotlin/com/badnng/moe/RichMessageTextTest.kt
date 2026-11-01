package com.badnng.moe

import androidx.compose.ui.text.TextRange
import com.badnng.moe.chat.*
import kotlin.test.*

class RichMessageTextTest {
    @Test fun quotesKeepUtf16PositionsAndClippedFormatting() {
        val original = RichMessageText("🙂前文 引用片段 后文", listOf(MessageEntity(5, 4, MessageEntityKind.BOLD)))
        val selected = assertNotNull(original.slice(5, 9))
        assertEquals("引用片段", selected.text)
        assertEquals(0, selected.entities.single().offset)
        assertTrue(MessageQuote(5, selected).matches(original.text))
        assertFalse(MessageQuote(4, selected).matches(original.text))
        assertNull(original.slice(1, 4))
        assertNull(original.slice(0, 1))
    }

    @Test fun insertionAndImeReplacementKeepSurroundingStyles() {
        val source = RichMessageText("前文 hello 后文", listOf(MessageEntity(3, 5, MessageEntityKind.BOLD)))
        val edited = source.replaceText("🙂前文 hello 后文")
        assertEquals(MessageEntity(5, 5, MessageEntityKind.BOLD), edited.entities.single())
        assertEquals(MessageEntity(3, 2, MessageEntityKind.BOLD), source.replaceText("前文 你好 后文").entities.single())
    }

    @Test fun intersectingStylesAreNestedAndCodeRemovesIncompatibleStyles() {
        val value = RichMessageText("abcdefgh", listOf(MessageEntity(0, 5, MessageEntityKind.BOLD)))
            .editorValue(TextRange(3, 8)).applyFormat(MessageEntityKind.ITALIC)
        val entities = value.richText().entities
        assertTrue(entities.any { it.offset == 3 && it.length == 2 && it.kind == MessageEntityKind.ITALIC })
        val code = value.copy(selection = TextRange(0, 8)).applyFormat(MessageEntityKind.CODE).richText()
        assertEquals(listOf(MessageEntity(0, 8, MessageEntityKind.CODE)), code.entities)
    }

    @Test fun editorSelectionChangesDoNotEraseLinksAndClearOnlyAffectsTheSelection() {
        val source = RichMessageText("hello world", listOf(MessageEntity(0, 11, MessageEntityKind.TEXT_URL, "https://example.org")))
        val value = source.editorValue(TextRange(6, 11))
        assertEquals(source, value.richText())
        assertEquals(listOf(MessageEntity(0, 6, MessageEntityKind.TEXT_URL, "https://example.org")), value.applyFormat(null).richText().entities)
    }

    @Test fun externalLinksRejectLocalFilesCommandsAndControlCharacters() {
        listOf("file:///C:/private.exe", "javascript:alert(1)", "data:text/html,hi", "C:\\private.exe", "https://", "https://user@example.org", "https://example.org\nrun").forEach { assertNull(safeExternalLink(it)) }
        assertEquals("https://example.org/a", safeExternalLink("example.org/a"))
        assertEquals("https://example.org/a", safeExternalLink("https://example.org/a"))
    }

    @Test fun contentModelsNeverRenderPrivatePayloadsInDiagnostics() {
        val content = RichMessageText("private message", listOf(MessageEntity(0, 7, MessageEntityKind.TEXT_URL, "https://private.example")))
        listOf(content, content.entities.single(), MessageQuote(0, content), OutgoingReply(3, MessageQuote(0, content))).forEach {
            assertFalse(it.toString().contains("private"))
        }
    }
}
