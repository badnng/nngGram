// The grammar reader and ordered greedy/rematch tokenizer are adapted from libprisma
// 75f26c17308871bee69f69a2831a8af2bc2f3627, an MIT-licensed Prism port.
// Copyright (c) 2012 Lea Verou. See third_party/libprisma/LICENSE.
package com.badnng.moe

import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import java.util.regex.Pattern
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_CODE_BYTES = 65_536
private const val MAX_TOKEN_DEPTH = 32
private const val MAX_MATCH_WORK = 4_000_000
private const val MAX_CACHE_ENTRIES = 256
private object GrammarResource
private val codeHighlightDispatcher = Dispatchers.Default.limitedParallelism(1)

/** One bounded worker queue, with cancellation checked before a result reaches its composition. */
internal actual suspend fun codeSyntaxTokensAsync(text: String, language: String): List<CodeSyntaxToken> =
    withContext(codeHighlightDispatcher) { codeSyntaxTokens(text, language) }

private val codeGrammars by lazy {
    val bytes = checkNotNull(GrammarResource.javaClass.getResourceAsStream("/code-highlight/grammars.dat"))
        .use { it.readBytes() }
    CodeGrammarBook(bytes)
}

private class CodeCacheKey(val text: String, val language: String) {
    override fun hashCode(): Int = 31 * text.hashCode() + language.hashCode()
    override fun equals(other: Any?): Boolean = other is CodeCacheKey && text == other.text && language == other.language
    override fun toString(): String = "CodeCacheKey(<redacted>)"
}
private val codeTokenCache = object : LinkedHashMap<CodeCacheKey, List<CodeSyntaxToken>>(32, .75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CodeCacheKey, List<CodeSyntaxToken>>): Boolean = size > MAX_CACHE_ENTRIES
}

internal actual fun codeSyntaxTokens(text: String, language: String): List<CodeSyntaxToken> {
    if (text.isEmpty() || text.length > MAX_CODE_BYTES) return emptyList()
    val normalized = when (val name = language.lowercase()) { "diff", "patch" -> "git"; else -> name }
    val key = CodeCacheKey(text, normalized)
    synchronized(codeTokenCache) { codeTokenCache[key]?.let { return it } }
    val result = try {
        val grammar = codeGrammars.languages[normalized] ?: return emptyList()
        val source = CodeByteSource.from(text) ?: return emptyList()
        CodeTokenRequest(codeGrammars).highlight(source, grammar)
    } catch (_: CodeWorkLimit) {
        emptyList()
    } catch (_: StackOverflowError) {
        // java.util.regex uses the VM stack for some repetitions; retain readable uncolored text.
        emptyList()
    } catch (_: RuntimeException) {
        // Any unavailable grammar or unsupported regex behavior must fall back to the native text.
        emptyList()
    }
    synchronized(codeTokenCache) { codeTokenCache[key] = result }
    return result
}

/** Used by fixture checks to validate the entire bundled grammar, including Native Image resources. */
internal fun codeGrammarLanguageNames(): Set<String> = codeGrammars.languages.keys
internal fun validateCodeGrammarPatterns(): Int = codeGrammars.patterns.count { it.compiled.pattern().isNotEmpty() }

/** libprisma tokenizes UTF-8 bytes; map only complete code-point boundaries back to Compose UTF-16. */
private class CodeByteSource(val bytes: String, val utf16Offsets: IntArray) {
    companion object {
        fun from(text: String): CodeByteSource? {
            val encoded = text.toByteArray(StandardCharsets.UTF_8)
            if (encoded.size > MAX_CODE_BYTES) return null
            val offsets = IntArray(encoded.size + 1) { -1 }
            var utf16 = 0
            var byte = 0
            while (utf16 < text.length) {
                val first = text[utf16]
                if (first.isLowSurrogate() || (first.isHighSurrogate() &&
                        (utf16 + 1 >= text.length || !text[utf16 + 1].isLowSurrogate()))) return null
                offsets[byte] = utf16
                val codePoint = Character.codePointAt(text, utf16)
                byte += when { codePoint <= 0x7f -> 1; codePoint <= 0x7ff -> 2; codePoint <= 0xffff -> 3; else -> 4 }
                utf16 += Character.charCount(codePoint)
            }
            if (byte != encoded.size) return null
            offsets[byte] = text.length
            return CodeByteSource(String(encoded, StandardCharsets.ISO_8859_1), offsets)
        }
    }
}

private data class CodeGrammarToken(val name: String, val patterns: IntArray)
private data class CodeGrammar(val tokens: List<CodeGrammarToken>)
private class CodePattern(encoded: String) {
    private val lastSlash = encoded.lastIndexOf('/')
    private val options = encoded.substring(lastSlash + 1).split(',')
    private val flags = options[0]
    val lookbehind = 'l' in flags
    val greedy = 'y' in flags
    val inside = options.getOrNull(2)?.toIntOrNull()
    val compiled: Pattern by lazy {
        Pattern.compile(javaCompatiblePrismPattern(encoded.substring(1, lastSlash), multiline = 'm' in flags),
            if ('i' in flags) Pattern.CASE_INSENSITIVE else 0)
    }

    fun match(text: String, start: Int, budget: CodeMatchBudget): IntRange? {
        budget.spend()
        val matcher = compiled.matcher(BoundedCodeSequence(text, budget)).region(start, text.length)
        if (!matcher.find()) return null
        val from = matcher.start() + if (lookbehind && matcher.groupCount() > 0 && matcher.start(1) >= 0)
            matcher.end(1) - matcher.start(1) else 0
        val end = matcher.end()
        return if (end > from) from until end else null
    }
}

/**
 * Preserve Boost's char/byte semantics: its separators are LF, CR and FF, never UTF-8 byte 0x85.
 * no_mod_m uses strict buffer anchors; multiline anchors never split the middle of CRLF.
 * ECMAScript classes also allow literal '[' and '&' which Java treats as set operators.
 */
internal fun javaCompatiblePrismPattern(source: String, multiline: Boolean = false): String = buildString(source.length) {
    var inClass = false
    var index = 0
    while (index < source.length) {
        val char = source[index++]
        when {
            char == '\\' && index < source.length -> append(char).append(source[index++])
            char == '[' -> { if (inClass) append('\\') else inClass = true; append(char) }
            char == ']' -> { inClass = false; append(char) }
            char == '&' && inClass -> append("\\&")
            char == '{' && !inClass && !prismQuantifierAt(source, index - 1) -> append("\\{")
            char == '.' && !inClass -> append("[^\\r\\n\\f]")
            char == '^' && !inClass -> append(if (multiline) "(?:\\A|(?<=[\\n\\f])|(?<=\\r)(?!\\n))" else "\\A")
            char == '$' && !inClass -> append(if (multiline) "(?:\\z|(?=[\\r\\f])|(?<!\\r)(?=\\n))" else "\\z")
            else -> append(char)
        }
    }
}

private fun prismQuantifierAt(source: String, start: Int): Boolean {
    var cursor = start + 1
    val digits = cursor
    while (cursor < source.length && source[cursor] in '0'..'9') cursor++
    if (cursor == digits) return false
    if (cursor < source.length && source[cursor] == ',') {
        cursor++
        while (cursor < source.length && source[cursor] in '0'..'9') cursor++
    }
    return cursor < source.length && source[cursor] == '}'
}

private class CodeGrammarBook(bytes: ByteArray) {
    val patterns: List<CodePattern>
    val grammars: List<CodeGrammar>
    val languages: Map<String, Int>
    init {
        val input = GrammarInput(bytes)
        patterns = List(input.uint16()) { CodePattern(input.string()) }
        grammars = List(input.uint16()) {
            CodeGrammar(List(input.uint8()) {
                CodeGrammarToken(input.string(), IntArray(input.uint8()) { input.uint16() })
            })
        }
        languages = buildMap {
            repeat(input.uint16()) {
                val name = input.string()
                input.string() // Display name; language headers continue using the native PRE label.
                put(name, input.uint16())
            }
        }
        require(input.atEnd)
        require(patterns.all { it.inside == null || it.inside in grammars.indices })
        require(grammars.all { grammar -> grammar.tokens.all { token -> token.patterns.all { it in patterns.indices } } })
        require(languages.values.all { it in grammars.indices })
    }
}

private class GrammarInput(private val bytes: ByteArray) {
    private var position = 0
    val atEnd: Boolean get() = position == bytes.size
    fun uint8(): Int { require(position < bytes.size); return bytes[position++].toInt() and 255 }
    fun uint16(): Int = uint8() or (uint8() shl 8)
    fun string(): String {
        val first = uint8()
        val count = if (first < 254) first else uint8() or (uint8() shl 8) or (uint8() shl 16)
        require(count <= bytes.size - position)
        return String(bytes, position, count, StandardCharsets.ISO_8859_1).also { position += count }
    }
}

private class CodeWorkLimit : RuntimeException(null, null, false, false)
private class CodeMatchBudget {
    private var remaining = MAX_MATCH_WORK
    fun spend() { if (--remaining < 0) throw CodeWorkLimit() }
}
private class BoundedCodeSequence(private val text: String, private val budget: CodeMatchBudget,
    private val start: Int = 0, override val length: Int = text.length) : CharSequence {
    override fun get(index: Int): Char { budget.spend(); return text[start + index] }
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        BoundedCodeSequence(text, budget, start + startIndex, endIndex - startIndex)
    override fun toString(): String = text.substring(start, start + length)
}

private class CodeNode(val text: String = "", val type: String? = null,
    val children: CodeNodeList? = null, val length: Int = text.length) {
    var previous: CodeNode = this
    var next: CodeNode = this
    val syntax: Boolean get() = type != null
}
private class CodeNodeList(text: String) {
    val head = CodeNode(type = "")
    var count = 0
        private set
    init { insert(head, CodeNode(text)) }
    fun insert(after: CodeNode, node: CodeNode): CodeNode {
        node.previous = after
        node.next = after.next
        after.next.previous = node
        after.next = node
        count++
        return node
    }
    fun remove(after: CodeNode, count: Int) {
        repeat(count) {
            check(after.next !== head)
            after.next = after.next.next
            after.next.previous = after
            this.count--
        }
    }
}
private class CodeRematch(val token: String, val pattern: Int, var reach: Int)

private class CodeTokenRequest(private val book: CodeGrammarBook) {
    private val budget = CodeMatchBudget()
    fun highlight(source: CodeByteSource, grammar: Int): List<CodeSyntaxToken> {
        val nodes = tokenize(source.bytes, grammar, 0)
        val result = ArrayList<CodeSyntaxToken>()
        var byteOffset = 0
        fun enumerate(list: CodeNodeList, type: String?) {
            var current = list.head.next
            while (current !== list.head) {
                budget.spend()
                if (current.syntax) enumerate(checkNotNull(current.children), current.type)
                else {
                    val endByte = byteOffset + current.length
                    require(endByte <= source.bytes.length)
                    val start = source.utf16Offsets[byteOffset]
                    val end = source.utf16Offsets[endByte]
                    val color = codeTokenColor(type)
                    if (color != 0 && start >= 0 && end > start) result.add(CodeSyntaxToken(start, end, color))
                    byteOffset = endByte
                }
                current = current.next
            }
        }
        enumerate(nodes, null)
        return if (byteOffset == source.bytes.length) result else emptyList()
    }

    private fun tokenize(text: String, grammar: Int, depth: Int): CodeNodeList {
        val nodes = CodeNodeList(text)
        if (depth < MAX_TOKEN_DEPTH) matchGrammar(text, nodes, grammar, nodes.head, 0, null, depth + 1)
        return nodes
    }

    private fun matchGrammar(text: String, nodes: CodeNodeList, grammar: Int, startNode: CodeNode,
        startPosition: Int, rematch: CodeRematch?, depth: Int) {
        if (depth > MAX_TOKEN_DEPTH) return
        for (token in book.grammars[grammar].tokens) for ((patternIndex, patternId) in token.patterns.withIndex()) {
            if (rematch?.token == token.name && rematch.pattern == patternIndex) return
            val pattern = book.patterns[patternId]
            var position = startPosition
            var current = startNode.next
            while (current !== nodes.head) {
                budget.spend()
                if (rematch != null && position >= rematch.reach) break
                if (nodes.count > text.length) return
                if (current.syntax) { position += current.length; current = current.next; continue }
                var value = current.text
                var removeCount = 1
                val match: IntRange
                if (pattern.greedy) {
                    val found = pattern.match(text, position, budget) ?: break
                    val from = found.first
                    val to = found.last + 1
                    var endPosition = position + current.length
                    while (from >= endPosition && current.next !== nodes.head) {
                        current = current.next
                        endPosition += current.length
                    }
                    endPosition -= current.length
                    position = endPosition
                    if (current.syntax) { position += current.length; current = current.next; continue }
                    var affected = current
                    while (affected !== nodes.head && (endPosition < to || !affected.syntax)) {
                        removeCount++
                        endPosition += affected.length
                        affected = affected.next
                    }
                    removeCount--
                    value = text.substring(position, endPosition)
                    match = (from - position) until (to - position)
                } else {
                    val found = pattern.match(value, 0, budget)
                    if (found == null) { position += current.length; current = current.next; continue }
                    match = found
                }
                val before = value.substring(0, match.first)
                val matched = value.substring(match.first, match.last + 1)
                val after = value.substring(match.last + 1)
                val reach = position + value.length
                if (rematch != null && reach > rematch.reach) rematch.reach = reach
                var removeFrom = current.previous
                if (before.isNotEmpty()) {
                    removeFrom = nodes.insert(removeFrom, CodeNode(before))
                    position += before.length
                }
                nodes.remove(removeFrom, removeCount)
                val children = pattern.inside?.let { tokenize(matched, it, depth) } ?: CodeNodeList(matched)
                current = nodes.insert(removeFrom, CodeNode(type = token.name, children = children, length = matched.length))
                if (after.isNotEmpty()) nodes.insert(current, CodeNode(after))
                if (removeCount > 1) {
                    val nested = CodeRematch(token.name, patternIndex, reach)
                    matchGrammar(text, nodes, grammar, current.previous, position, nested, depth + 1)
                    if (rematch != null && nested.reach > rematch.reach) rematch.reach = nested.reach
                }
                position += current.length
                current = current.next
            }
        }
    }
}

// Preserve Telegram's eight categories and its use of the actual token type (not Prism aliases).
private fun codeTokenColor(type: String?): Int = when (type) {
    "comment", "block-comment", "prolog", "doctype", "cdata" -> 1
    "punctuation" -> 2
    "property", "tag", "boolean", "number", "constant", "symbol", "deleted" -> 3
    "selector", "attr-name", "string", "char", "builtin" -> 4
    "operator", "entity", "url" -> 5
    "atrule", "attr-value", "keyword", "function" -> 6
    "class-name" -> 7
    "inserted" -> 8
    else -> 0
}
