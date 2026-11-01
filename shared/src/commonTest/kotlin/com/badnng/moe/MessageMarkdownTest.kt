package com.badnng.moe

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.badnng.moe.chat.*
import kotlin.test.*

class MessageMarkdownTest {
    @Test fun fencedPythonPreservesIndentationAndEmojiWithoutParsingItsCode() {
        val content = RichMessageText("说明🙂\n```python\nif True:\n    print('**🙂**')\n```\n后文").withMarkdownFormatting()
        assertEquals("说明🙂\nif True:\n    print('**🙂**')\n后文", content.text)
        val code = content.entities.single()
        assertEquals(MessageEntityKind.PRE, code.kind)
        assertEquals("python", code.value)
        assertEquals(5, code.offset)
        val blocks = content.displayBlocks()
        assertEquals(3, blocks.size)
        assertEquals("if True:\n    print('**🙂**')", blocks[1].content.text)
        assertEquals("python", blocks[1].language)
        assertEquals(5, blocks[1].sourceOffset)
    }

    @Test fun blockSourceOffsetsRetainDiscardedWhitespaceAndDistinguishRepeatedText() {
        val content = RichMessageText("\n  重复\n\n重复\n\n重复", listOf(
            MessageEntity(3, 2, MessageEntityKind.PRE, "kotlin"),
            MessageEntity(7, 2, MessageEntityKind.BLOCK_QUOTE),
            MessageEntity(11, 2, MessageEntityKind.PRE),
        ))
        val blocks = content.displayBlocks()
        assertEquals(listOf("重复", "重复", "重复"), blocks.map { it.content.text })
        assertEquals(listOf(3, 7, 11), blocks.map { it.sourceOffset })
        blocks.forEach { block ->
            assertEquals(block.content.text,
                content.text.substring(block.sourceOffset, block.sourceOffset + block.content.text.length))
        }
    }

    @Test fun plainRegionsAndCodeBlocksKeepOriginalUtf16Offsets() {
        val content = RichMessageText("🙂\n相同\n相同\n相同", listOf(MessageEntity(6, 2, MessageEntityKind.PRE)))
        val blocks = content.displayBlocks()
        assertEquals(listOf(0, 6, 8), blocks.map { it.sourceOffset })
        assertEquals(listOf("🙂\n相同\n", "相同", "\n相同"), blocks.map { it.content.text })
        assertEquals(listOf(null, MessageEntityKind.PRE, null), blocks.map { it.kind })
    }

    @Test fun formattingAndNamedLinksCanBeSentTogetherWithUtf16Offsets() {
        val content = RichMessageText("🙂 **粗体** __斜体__ ~~删除~~ ||隐藏|| `a_b` [官网](https://example.org)").withMarkdownFormatting()
        assertEquals("🙂 粗体 斜体 删除 隐藏 a_b 官网", content.text)
        assertTrue(content.entities.any { it.kind == MessageEntityKind.BOLD && it.offset == 3 && it.length == 2 })
        assertTrue(content.entities.any { it.kind == MessageEntityKind.ITALIC && it.offset == 6 && it.length == 2 })
        assertFalse(content.entities.any { it.kind == MessageEntityKind.UNDERLINE })
        assertEquals(6, content.entities.size)
        assertEquals("https://example.org", content.entities.last().value)
    }

    @Test fun singleMarkersIdentifiersAndInvalidOfficialBoundariesRemainLiteral() {
        for (text in listOf(
            "*斜体*", "_斜体_", "some_identifier_here", "name__part__tail",
            "x**粗体**", "**粗体**y", "前__斜体__后", "***粗体***", "__斜体___", "`line\nbreak`",
        )) {
            assertEquals(RichMessageText(text), RichMessageText(text).withMarkdownFormatting(), text)
        }
    }

    @Test fun officialPunctuationBoundariesAndStrikeoutSuffixAreRespected() {
        val content = RichMessageText("（**原样**） [__斜体__] /`代码`/ ~~删除~~后缀").withMarkdownFormatting()
        assertEquals("（**原样**） [斜体] /代码/ 删除后缀", content.text)
        assertEquals(listOf(MessageEntityKind.ITALIC, MessageEntityKind.CODE, MessageEntityKind.STRIKETHROUGH),
            content.entities.map { it.kind })
        val nested = RichMessageText("**__中文🙂__**").withMarkdownFormatting()
        assertEquals("中文🙂", nested.text)
        assertEquals(setOf(MessageEntityKind.BOLD, MessageEntityKind.ITALIC), nested.entities.map { it.kind }.toSet())
    }

    @Test fun typedAndPastedUrlsKeepTheirExactBytesWithoutInventingLinkEntities() {
        for (url in listOf(
            "http://test.com/__test__/test",
            "HTTPS://example.org/a/**bold**/b?query=__value__",
            "example.org/__path__/~~value~~?query=||secret||",
            "https://example.org/a\\_b?query=\\*value",
            "http://127.0.0.1:8080/__path__",
            "https://例子.测试/__路径__",
            "https://example.org/path_(test)/__value__",
        )) {
            val source = "🙂 $url 中文"
            val result = RichMessageText(source).parseComposerMarkdown()
            assertEquals(RichMessageText(source), result.content, url)
            assertFalse(result.addedFormatting, url)
            assertContentEquals(IntArray(source.length + 1) { it }, result.positions, url)
        }
    }

    @Test fun wholeLinkCanBeFormattedButAClosingMarkerInsideItsPathCannotBeConsumed() {
        val url = "https://example.org/__path__"
        val content = RichMessageText("**$url**").withMarkdownFormatting()
        assertEquals(RichMessageText(url, listOf(MessageEntity(0, url.length, MessageEntityKind.BOLD))), content)
        val partial = "**前文 https://example.org/__path**/tail"
        assertEquals(RichMessageText(partial), RichMessageText(partial).withMarkdownFormatting())
        val bracketed = RichMessageText("(https://example.org/__path__) **后文**").withMarkdownFormatting()
        assertEquals("(https://example.org/__path__) 后文", bracketed.text)
        assertEquals(MessageEntity(bracketed.text.length - 2, 2, MessageEntityKind.BOLD), bracketed.entities.single())
    }

    @Test fun nativeLinkEntitiesAndUnderlineRemainIntactAfterNearbyUtf16MarkerRemoval() {
        val url = "https://example.org/__路径__"
        val source = "🙂 __中文__ $url 下划线"
        val content = RichMessageText(source, listOf(
            MessageEntity(source.indexOf(url), url.length, MessageEntityKind.URL),
            MessageEntity(source.indexOf("下划线"), 3, MessageEntityKind.UNDERLINE),
            MessageEntity(source.indexOf("下划线"), 3, MessageEntityKind.TEXT_URL, "https://example.org"),
        )).withMarkdownFormatting()
        assertEquals("🙂 中文 $url 下划线", content.text)
        assertTrue(content.entities.contains(MessageEntity(3, 2, MessageEntityKind.ITALIC)))
        assertTrue(content.entities.contains(MessageEntity(content.text.indexOf(url), url.length, MessageEntityKind.URL)))
        assertTrue(content.entities.contains(MessageEntity(content.text.indexOf("下划线"), 3, MessageEntityKind.UNDERLINE)))
        assertTrue(content.entities.contains(MessageEntity(content.text.indexOf("下划线"), 3, MessageEntityKind.TEXT_URL, "https://example.org")))
        val literal = RichMessageText("__链接__", listOf(MessageEntity(0, 6, MessageEntityKind.TEXT_URL, "https://example.org")))
        assertEquals(literal, literal.withMarkdownFormatting())
    }

    @Test fun explicitFormattingSurvivesMarkdownRemovalAndCodeIsLiteral() {
        val source = "**前文** 后文"
        val content = RichMessageText(source, listOf(MessageEntity(7, 2, MessageEntityKind.ITALIC))).withMarkdownFormatting()
        assertEquals("前文 后文", content.text)
        assertTrue(content.entities.contains(MessageEntity(3, 2, MessageEntityKind.ITALIC)))
        val literal = RichMessageText("**literal**", listOf(MessageEntity(0, 11, MessageEntityKind.CODE)))
        assertEquals(literal, literal.withMarkdownFormatting())
        assertEquals("**literal**", RichMessageText("\\*\\*literal\\*\\*").withMarkdownFormatting().text)
    }

    @Test fun incompleteFencesAndUnsafeLinksStayPlainText() {
        listOf("```python\nunfinished", "[run](file:///C:/secret.exe)", "some_identifier_here").forEach {
            assertEquals(RichMessageText(it), RichMessageText(it).withMarkdownFormatting())
        }
    }

    @Test fun receivedNativeStylesLinksAndSpoilersRenderWithoutParsingPlainFences() {
        val rich = RichMessageText("粗体 链接 隐藏", listOf(MessageEntity(0, 2, MessageEntityKind.BOLD),
            MessageEntity(3, 2, MessageEntityKind.TEXT_URL, "https://example.org"),
            MessageEntity(6, 2, MessageEntityKind.SPOILER), MessageEntity(6, 2, MessageEntityKind.TEXT_URL, "https://hidden.example")))
        val shown = displayRichText(rich, Color.Black, Color.Blue, Color.Gray, onLink = {})
        assertTrue(shown.spanStyles.any { it.start == 0 && it.item.fontWeight == FontWeight.SemiBold })
        assertEquals(1, shown.getLinkAnnotations(0, shown.length).size)
        assertEquals(2, displayRichText(rich, Color.Black, Color.Blue, Color.Gray, revealSpoilers = true, onLink = {}).getLinkAnnotations(0, shown.length).size)
        val plain = RichMessageText("```python\nplain\n```")
        assertEquals(plain.text, plain.displayBlocks().single().content.text)
        assertNull(plain.displayBlocks().single().kind)
    }
}
