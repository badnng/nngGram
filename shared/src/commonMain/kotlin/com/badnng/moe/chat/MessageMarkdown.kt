package com.badnng.moe.chat

/** Composer-only Telegram-style markup. Incoming plain text is never reinterpreted as markup. */
fun RichMessageText.withMarkdownFormatting(): RichMessageText = parseComposerMarkdown().content

/** Source positions include the removed delimiters, so an editor can retain its UTF-16 selection. */
internal class ComposerMarkdownResult(val content: RichMessageText, val positions: IntArray, val addedFormatting: Boolean)

// lib_ui@79c846f: TextUtilities::Separators* / InputField::TagStartExpressions.
private const val COMPOSER_MARKDOWN_SEPARATORS = " \u0010\n\r\t.,:;<>|'\"[]{}!?%^()-+=\ufdd0\ufdd1\u2029\u2028«»“”‘’…"

private data class ComposerMarkdownMarker(
    val text: String,
    val kind: MessageEntityKind,
    val separators: String,
    val badEdge: String = text.take(1),
    val unrestrictedCloseAfter: Boolean = false,
)

private val COMPOSER_MARKDOWN_MARKERS = listOf(
    ComposerMarkdownMarker("**", MessageEntityKind.BOLD, COMPOSER_MARKDOWN_SEPARATORS + "`~/"),
    ComposerMarkdownMarker("__", MessageEntityKind.ITALIC, COMPOSER_MARKDOWN_SEPARATORS + "`*~/"),
    ComposerMarkdownMarker("~~", MessageEntityKind.STRIKETHROUGH, COMPOSER_MARKDOWN_SEPARATORS + "`*~/", unrestrictedCloseAfter = true),
    ComposerMarkdownMarker("||", MessageEntityKind.SPOILER, COMPOSER_MARKDOWN_SEPARATORS + "|*~/"),
    ComposerMarkdownMarker("`", MessageEntityKind.CODE, COMPOSER_MARKDOWN_SEPARATORS + "*~/", "`\n\r"),
)

// These ranges protect typed/pasted URL bytes only. Native TDLib entities still own links.
private val COMPOSER_LINK_START = Regex(
    "(?i)(?<![\\p{L}\\p{N}_@])(?:https?://(?:\\[[0-9a-f:.]+]|[\\p{L}\\p{N}](?:[\\p{L}\\p{N}.-]*[\\p{L}\\p{N}])?)|" +
        "(?:[\\p{L}\\p{N}](?:[\\p{L}\\p{N}-]{0,61}[\\p{L}\\p{N}])?\\.)+(?:[\\p{L}]{2,63}|xn--[a-z0-9-]+))(?::[0-9]{1,5})?",
)

private fun Char.endsComposerLink(): Boolean =
    isWhitespace() || code < 32 || isHighSurrogate() || isLowSurrogate() || this == '\ufffc'

private fun String.composerLinkRanges(): List<IntRange> = COMPOSER_LINK_START.findAll(this).map { match ->
    var end = match.range.last + 1
    if (end < length && this[end] in "/?#") {
        val brackets = mutableListOf<Int>()
        while (end < length && !this[end].endsComposerLink()) {
            var character = this[end]
            if (character in "?,.\":!'") {
                var next = end + 1
                while (next < length && this[next] in "?,.\":!'") next++
                if (next == length || this[next].endsComposerLink()) break
                end = next
                character = this[end]
            }
            if (character in "([{<") brackets.add(end)
            else if (character in ")]}>") {
                val opening = brackets.removeLastOrNull() ?: break
                if ("([{<".indexOf(this[opening]) != ")]}>".indexOf(character)) {
                    end = opening
                    break
                }
            }
            end++
        }
    }
    match.range.first until end
}.toList()

internal fun RichMessageText.parseComposerMarkdown(): ComposerMarkdownResult {
    if (text.length > 65_536) return ComposerMarkdownResult(this, IntArray(0), false)
    val output = StringBuilder()
    val positions = IntArray(text.length + 1)
    val added = mutableListOf<MessageEntity>()
    val existing = validEntities()
    val literal = existing.filter { it.kind in setOf(MessageEntityKind.CODE, MessageEntityKind.PRE, MessageEntityKind.URL, MessageEntityKind.TEXT_URL) }
    val links = text.composerLinkRanges()
    val fences = Regex("(?m)^```([A-Za-z0-9_+#.\\-]{0,32})[ \\t]*\\r?\\n([\\s\\S]*?)\\r?\\n```[ \\t]*(?=\\r?\\n|$)")
        .findAll(text).associateBy { it.range.first }
    fun skip(start: Int, end: Int) { for (index in start..end) positions[index] = output.length }
    fun copy(start: Int, end: Int) {
        for (index in start until end) { positions[index] = output.length; output.append(text[index]) }
        positions[end] = output.length
    }
    fun cutsLink(start: Int, end: Int): Boolean = links.any { link ->
        link.first < end && link.last >= start && (link.first < start || link.last >= end)
    }
    fun opens(marker: ComposerMarkdownMarker, index: Int): Boolean =
        (index == 0 || text[index - 1] in marker.separators) &&
            (index + marker.text.length == text.length || text[index + marker.text.length] !in marker.badEdge)
    fun closes(marker: ComposerMarkdownMarker, index: Int): Boolean =
        (index == 0 || text[index - 1] !in marker.badEdge) &&
            (marker.unrestrictedCloseAfter || index + marker.text.length == text.length ||
                text[index + marker.text.length] in marker.separators)
    fun parse(start: Int, end: Int, depth: Int) {
        if (depth > 12) { copy(start, end); return }
        var index = start
        while (index < end) {
            val protected = literal.firstOrNull { index in it.offset until it.end }
            if (protected != null) { val next = minOf(end, protected.end); copy(index, next); index = next; continue }
            val link = links.firstOrNull { index in it }
            if (link != null) { val next = minOf(end, link.last + 1); copy(index, next); index = next; continue }
            val fence = fences[index]?.takeIf { it.range.last < end }
            if (fence != null) {
                val body = checkNotNull(fence.groups[2])
                skip(index, body.range.first)
                val offset = output.length
                copy(body.range.first, body.range.last + 1)
                if (output.length > offset) added.add(MessageEntity(offset, output.length - offset, MessageEntityKind.PRE, fence.groupValues[1]))
                skip(body.range.last + 1, fence.range.last + 1)
                index = fence.range.last + 1
                continue
            }
            if (text[index] == '\\' && index + 1 < end && text[index + 1] in "`*_~|[\\") {
                skip(index, index + 1); copy(index + 1, index + 2); index += 2; continue
            }
            if (text[index] == '[') {
                val close = text.indexOf("](", index + 1)
                val stop = if (close > index) text.indexOf(')', close + 2) else -1
                val url = if (stop in (close + 3) until end) safeExternalLink(text.substring(close + 2, stop)) else null
                if (url != null && close > index + 1 && '\n' !in text.substring(index, stop)) {
                    skip(index, index + 1)
                    val offset = output.length
                    parse(index + 1, close, depth + 1)
                    skip(close, stop + 1)
                    added.add(MessageEntity(offset, output.length - offset, MessageEntityKind.TEXT_URL, url))
                    index = stop + 1
                    continue
                }
            }
            val marker = COMPOSER_MARKDOWN_MARKERS.firstOrNull { text.startsWith(it.text, index) && opens(it, index) }
            if (marker != null) {
                val mark = marker.text
                val kind = marker.kind
                var close = text.indexOf(mark, index + mark.length + 1)
                while (close >= 0 && close + mark.length <= end && !closes(marker, close)) {
                    close = text.indexOf(mark, close + mark.length)
                }
                if (close > index + mark.length && close + mark.length <= end &&
                    !cutsLink(index, close + mark.length) &&
                    (kind != MessageEntityKind.CODE || text.substring(index, close).none { it == '\n' || it == '\r' })) {
                    skip(index, index + mark.length)
                    val offset = output.length
                    if (kind == MessageEntityKind.CODE) copy(index + mark.length, close) else parse(index + mark.length, close, depth + 1)
                    skip(close, close + mark.length)
                    added.add(MessageEntity(offset, output.length - offset, kind))
                    index = close + mark.length
                    continue
                }
            }
            copy(index, index + 1)
            index++
        }
    }
    parse(0, text.length, 0)
    val mapped = existing.mapNotNull { entity ->
        val start = positions[entity.offset]
        val end = positions[entity.end]
        if (end > start) entity.copy(offset = start, length = end - start) else null
    }
    return ComposerMarkdownResult(RichMessageText(output.toString(), mapped + added).normalized(), positions, added.isNotEmpty())
}

data class RichMessageBlock(
    val content: RichMessageText,
    val kind: MessageEntityKind? = null,
    val language: String = "",
    val sourceOffset: Int = 0,
) {
    override fun toString() = "RichMessageBlock(<redacted>, kind=$kind)"
}

/** Block entities occupy separate regions; inline entities keep offsets relative to each region. */
fun RichMessageText.displayBlocks(): List<RichMessageBlock> {
    val blockKinds = setOf(MessageEntityKind.PRE, MessageEntityKind.BLOCK_QUOTE, MessageEntityKind.EXPANDABLE_BLOCK_QUOTE)
    val blocks = validEntities().filter { it.kind in blockKinds }
        .sortedWith(compareBy<MessageEntity> { it.offset }.thenByDescending { it.length })
    val result = mutableListOf<RichMessageBlock>()
    var cursor = 0
    fun append(start: Int, end: Int, kind: MessageEntityKind? = null, language: String = "") {
        slice(start, end)?.let { part ->
            if (part.text.isNotBlank() || kind != null) result.add(RichMessageBlock(part.copy(entities = part.entities.filter {
                it.kind !in blockKinds
            }), kind, language, sourceOffset = start))
        }
    }
    for (block in blocks) {
        if (block.offset < cursor) continue
        append(cursor, block.offset)
        append(block.offset, block.end, block.kind, block.value)
        cursor = block.end
    }
    append(cursor, text.length)
    return result
}
