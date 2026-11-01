package com.badnng.moe

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import java.security.MessageDigest
import java.util.regex.Pattern
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RichCodeHighlightTest {
    @Test
    fun bundledOfficialGrammarIsCompleteAndAllPatternsCompile() {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/code-highlight/grammars.dat")).use { it.readBytes() }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals("56fdf805f4a9fef8f5ed97006f18005d6b07398d8b28434c2d3aa39564e6715b", hash)
        assertEquals(3_674, validateCodeGrammarPatterns())
        val languages = codeGrammarLanguageNames()
        assertTrue(languages.size > 350)
        assertTrue(setOf("kotlin", "kt", "javascript", "js", "python", "py", "json", "markup", "rust", "tl")
            .all { it in languages })
        assertTrue(checkNotNull(javaClass.getResourceAsStream("/code-highlight/LICENSE")).use { it.reader().readText() }
            .contains("MIT LICENSE"))
    }

    @Test
    fun keywordTextInsideCommentsAndStringsKeepsItsGrammarContext() {
        val code = "// val false 42\nval name = \"class true 12\"\nval count = 42"
        val tokens = codeSyntaxTokens(code, "kotlin")
        assertTokenCovers(code, tokens, "// val false 42", 1)
        assertTokenCovers(code, tokens, "\"class true 12\"", 4)
        assertTokenCovers(code, tokens, "42", 3, code.lastIndexOf("42"))
        assertTokenCovers(code, tokens, "val", 6, code.indexOf("val name"))
        val stringStart = code.indexOf('"')
        val stringEnd = code.lastIndexOf('"') + 1
        assertFalse(tokens.any { it.color == 6 && it.start >= stringStart && it.end <= stringEnd },
            "Quoted keywords must not be painted as language keywords.")
    }

    @Test
    fun utf8ContinuationBytesNeverEndACommentOrCreateALineAnchor() {
        // U+5145 encodes as E5 85 85: Java must not interpret those continuation bytes as NEL.
        val code = "// 充 true 42\nval x=1"
        val tokens = codeSyntaxTokens(code, "kotlin")
        assertTokenCovers(code, tokens, "// 充 true 42", 1)
        assertTokenCovers(code, tokens, "val", 6)
        assertFalse(tokens.any { it.color != 1 && it.start < code.indexOf('\n') })
        assertEquals(tokens, runBlocking { codeSyntaxTokensAsync(code, "kotlin") })

        val byteText = "a\u0085b\r\nc\rd\ne\u000cf"
        fun matches(regex: String) = Pattern.compile(javaCompatiblePrismPattern(regex, multiline = true))
            .matcher(byteText).results().map { it.group() }.toList()
        assertEquals(listOf("a\u0085b", "c", "d", "e", "f"), matches("^.+$"),
            "Boost char regex treats CR/LF/FF as separators, without splitting CRLF or byte 0x85.")
        assertFalse(Pattern.compile(javaCompatiblePrismPattern("^code$")).matcher("code\n").find(),
            "Official no_mod_m $ is the strict end of the current byte buffer.")
        assertTrue(Pattern.compile(javaCompatiblePrismPattern("[.$^]")).matcher("$").matches(),
            "Literal dots and anchors inside a character class must stay literal.")
    }

    @Test
    fun syntaxColorsNeverOverwriteHiddenSpoilersOrExplicitNativeLinkColors() {
        val code = "// visible hidden visible\nval target = 42"
        val hiddenStart = code.indexOf("hidden")
        val hiddenEnd = hiddenStart + "hidden".length
        val targetStart = code.indexOf("target")
        val targetEnd = targetStart + "target".length
        for (alpha in listOf(0f, .35f)) {
            val source = AnnotatedString.Builder(code).apply {
                addStyle(SpanStyle(color = Color.White.copy(alpha = alpha)), hiddenStart, hiddenEnd)
                addStyle(SpanStyle(color = Color.Cyan), targetStart, targetEnd)
                addStringAnnotation("spoiler", "native", hiddenStart, hiddenEnd)
            }.toAnnotatedString()
            val colored = highlightCodeText(source, "kotlin", false)
            assertEquals(code, colored.text)
            assertEquals(source.spanStyles, colored.spanStyles.take(source.spanStyles.size))
            assertTrue(colored.spanStyles.drop(source.spanStyles.size).none { style ->
                (style.start < hiddenEnd && style.end > hiddenStart) ||
                    (style.start < targetEnd && style.end > targetStart)
            }, "Added syntax spans must be split around hidden/revealing spoilers and native link foregrounds.")
            assertTrue(colored.spanStyles.drop(source.spanStyles.size).any { it.start == 0 && it.end == hiddenStart })
            assertTrue(colored.spanStyles.drop(source.spanStyles.size).any { it.start == hiddenEnd && it.end == code.indexOf('\n') })
            assertEquals(source.getStringAnnotations(0, code.length), colored.getStringAnnotations(0, code.length))
        }
    }

    @Test
    fun greedyMarkupStringsAndNestedJavascriptUseTheShippedInsideGrammars() {
        val markup = "<div title=\"if > true\">hello</div>"
        val markupTokens = codeSyntaxTokens(markup, "markup")
        assertTokenCovers(markup, markupTokens, "title", 4)
        assertTokenCovers(markup, markupTokens, "if > true", 6)
        assertTokenCovers(markup, markupTokens, "div", 3)

        val javascript = "const text = `hello ${'$'}{true ? 12 : 13}`;"
        val nested = codeSyntaxTokens(javascript, "js")
        assertTokenCovers(javascript, nested, "const", 6)
        assertTokenCovers(javascript, nested, "true", 3)
        assertTokenCovers(javascript, nested, "12", 3)
        assertTokenCovers(javascript, nested, "hello ", 4)
    }

    @Test
    fun unicodeAndExistingAnnotationsKeepExactUtf16Offsets() {
        val code = "// 中文 😀\nval 标题 = \"中文🚀\"\nval answer = 42"
        val original = AnnotatedString.Builder(code).apply {
            addStyle(SpanStyle(fontWeight = FontWeight.Bold, background = Color.Black), 0, code.length)
            addStringAnnotation("source", "native-entity", 0, code.length)
        }.toAnnotatedString()
        val highlighted = highlightCodeText(original, "KT", false)
        assertEquals(code, highlighted.text)
        assertEquals(original.getStringAnnotations(0, code.length), highlighted.getStringAnnotations(0, code.length))
        assertEquals(original.spanStyles.first(), highlighted.spanStyles.first())
        assertTrue(highlighted.spanStyles.size > original.spanStyles.size)
        val tokens = codeSyntaxTokens(code, "kt")
        assertTokenCovers(code, tokens, "// 中文 😀", 1)
        assertTokenCovers(code, tokens, "\"中文🚀\"", 4)
        assertTokenCovers(code, tokens, "42", 3)
        assertTrue(tokens.all { token ->
            token.start in 0..code.length && token.end in 0..code.length &&
                (token.start == code.length || !code[token.start].isLowSurrogate()) &&
                (token.end == code.length || !code[token.end].isLowSurrogate())
        })
    }

    @Test
    fun diffAndPatchFollowTheOfficialGitAliasWhileUnknownLanguageStaysPlain() {
        val code = "diff --git a/file b/file\n--- a/file\n+++ b/file\n@@ -1 +1 @@\n-old\n+new\n"
        val git = codeSyntaxTokens(code, "git")
        assertTrue(git.isNotEmpty())
        assertEquals(git, codeSyntaxTokens(code, "diff"))
        assertEquals(git, codeSyntaxTokens(code, "PATCH"))
        assertTokenCovers(code, git, "+new", 8)
        assertTokenCovers(code, git, "-old", 3)
        val original = AnnotatedString("val text = \"plain\"")
        assertEquals(original, highlightCodeText(original, "unknown-not-a-language", true))
        assertEquals(original, highlightCodeText(original, "", false))
    }

    @Test
    fun malformedOrOversizeInputFallsBackWithoutChangingTheCode() {
        assertTrue(codeSyntaxTokens("\uD800", "javascript").isEmpty())
        assertTrue(codeSyntaxTokens("a".repeat(65_537), "javascript").isEmpty())
        assertTrue(codeSyntaxTokens("字".repeat(22_000), "kotlin").isEmpty())
        // Recursive grammars have an explicit depth cap, matching the upstream recursion fix.
        val source = "#if " + "(".repeat(80) + "true" + ")".repeat(80) + "\n#endif"
        val highlighted = highlightCodeText(AnnotatedString(source), "brightscript", false)
        assertEquals(source, highlighted.text)
    }

    private fun assertTokenCovers(code: String, tokens: List<CodeSyntaxToken>, value: String, color: Int,
        start: Int = code.indexOf(value)) {
        assertTrue(start >= 0)
        assertTrue(tokens.any { it.color == color && it.start <= start && it.end >= start + value.length },
            "Expected category $color at UTF-16 range $start..${start + value.length}; actual ranges: $tokens")
    }
}
