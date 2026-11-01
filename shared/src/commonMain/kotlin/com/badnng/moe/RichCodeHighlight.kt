package com.badnng.moe

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle

/** UTF-16 ranges into the unchanged native PRE entity. No source text is stored in a token. */
internal data class CodeSyntaxToken(val start: Int, val end: Int, val color: Int)

internal expect fun codeSyntaxTokens(text: String, language: String): List<CodeSyntaxToken>
internal expect suspend fun codeSyntaxTokensAsync(text: String, language: String): List<CodeSyntaxToken>

/** Like Telegram's queued highlighter, first paint plain code and color it after background work. */
@Composable
internal fun rememberHighlightedCodeText(
    text: AnnotatedString,
    language: String,
    dark: Boolean,
    loadTokens: suspend (String, String) -> List<CodeSyntaxToken> = ::codeSyntaxTokensAsync,
): AnnotatedString {
    // An annotation/reveal-animation update must not cancel and restart the same code analysis.
    val raw = text.text
    var tokens by remember(raw, language) { mutableStateOf(emptyList<CodeSyntaxToken>()) }
    val currentLoad by rememberUpdatedState(loadTokens)
    LaunchedEffect(raw, language) {
        if (raw.isNotEmpty() && language.isNotBlank()) tokens = currentLoad(raw, language)
    }
    return remember(text, tokens, dark) { applyCodeSyntaxColors(text, tokens, dark) }
}

/** Color only: copying, entity annotations and selected-quote source offsets stay untouched. */
internal fun highlightCodeText(text: AnnotatedString, language: String, dark: Boolean): AnnotatedString {
    if (text.isEmpty() || language.isBlank()) return text
    val tokens = codeSyntaxTokens(text.text, language)
    return applyCodeSyntaxColors(text, tokens, dark)
}

internal fun applyCodeSyntaxColors(text: AnnotatedString, tokens: List<CodeSyntaxToken>, dark: Boolean): AnnotatedString {
    if (tokens.isEmpty()) return text
    val colors = if (dark) DARK_CODE_COLORS else LIGHT_CODE_COLORS
    // Native explicit foregrounds take precedence, including links and transparent spoiler masks.
    // Split tokens around them rather than recoloring a whole token across concealed characters.
    val preserved = text.spanStyles.filter { it.item.color.isSpecified || it.item.brush != null }
        .sortedBy { it.start }
    return AnnotatedString.Builder(text).apply {
        tokens.forEach { token ->
            if (token.start >= 0 && token.end <= text.length && token.start < token.end && token.color in 1..8) {
                val style = SpanStyle(color = colors[token.color - 1])
                var cursor = token.start
                for (range in preserved) {
                    if (range.end <= cursor) continue
                    if (range.start >= token.end) break
                    if (range.start > cursor) addStyle(style, cursor, minOf(range.start, token.end))
                    cursor = maxOf(cursor, range.end)
                    if (cursor >= token.end) break
                }
                if (cursor < token.end) addStyle(style, cursor, token.end)
            }
        }
    }.toAnnotatedString()
}

// Telegram ChatStyle::SyntaxHighlightColors: light blue, red, red, orange, red, blue, purple, green.
private val LIGHT_CODE_COLORS = listOf(Color(0xFF58A8ED), Color(0xFFE05356), Color(0xFFE05356),
    Color(0xFFF28C39), Color(0xFFE05356), Color(0xFF327FE5), Color(0xFF9F79E8), Color(0xFF61C752))
// The pinned official night palette does not override these statistics colors.
private val DARK_CODE_COLORS = LIGHT_CODE_COLORS
