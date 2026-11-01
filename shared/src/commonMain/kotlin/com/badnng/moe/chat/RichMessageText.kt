package com.badnng.moe.chat

enum class MessageEntityKind {
    BOLD, ITALIC, UNDERLINE, STRIKETHROUGH, SPOILER, CODE, PRE, BLOCK_QUOTE,
    URL, TEXT_URL, EMAIL, PHONE, MENTION, MENTION_NAME, HASHTAG, CASHTAG, CUSTOM_EMOJI,
    EXPANDABLE_BLOCK_QUOTE,
}

/** Offsets and lengths use UTF-16, matching Telegram and Compose text selections. */
data class MessageEntity(
    val offset: Int,
    val length: Int,
    val kind: MessageEntityKind,
    val value: String = "",
    val id: Long = 0,
) {
    val end: Int get() = offset + length
    override fun toString() = "MessageEntity(<redacted>, kind=$kind)"
}

data class RichMessageText(val text: String, val entities: List<MessageEntity> = emptyList()) {
    override fun toString() = "RichMessageText(<redacted>)"

    fun validEntities(): List<MessageEntity> = entities.filter {
        it.offset >= 0 && it.length > 0 && it.length <= text.length - it.offset &&
            text.isUtf16Boundary(it.offset) && text.isUtf16Boundary(it.end)
    }

    fun slice(start: Int, end: Int): RichMessageText? {
        if (start < 0 || end <= start || end > text.length ||
            !text.isUtf16Boundary(start) || !text.isUtf16Boundary(end)) return null
        return RichMessageText(text.substring(start, end), validEntities().mapNotNull {
            val left = maxOf(start, it.offset)
            val right = minOf(end, it.end)
            if (right <= left) null else it.copy(offset = left - start, length = right - left)
        })
    }
}

data class MessageQuote(val position: Int, val content: RichMessageText) {
    fun matches(source: String): Boolean = position >= 0 && content.text.isNotBlank() &&
        position <= source.length && content.text.length <= source.length - position &&
        source.isUtf16Boundary(position) && source.isUtf16Boundary(position + content.text.length) &&
        source.regionMatches(position, content.text, 0, content.text.length)
    override fun toString() = "MessageQuote(<redacted>)"
}

data class OutgoingReply(val messageId: Long, val quote: MessageQuote? = null) {
    override fun toString() = "OutgoingReply(<redacted>)"
}

fun ChatMessage.richText() = RichMessageText(text, entities)

internal fun String.isUtf16Boundary(index: Int): Boolean = index in 0..length &&
    !(index > 0 && index < length && this[index - 1].isHighSurrogate() && this[index].isLowSurrogate())

/** Only user-clicked network links may reach the OS; never shell/file/data/javascript URLs. */
fun safeExternalLink(input: String): String? {
    if (input.isBlank() || input.any { it.isWhitespace() || it.code < 32 || it.code == 127 || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069' } || '\\' in input) return null
    val url = if (":" !in input.substringBefore('/')) "https://$input" else input
    val scheme = url.substringBefore(':').lowercase()
    return when (scheme) {
        "http", "https" -> url.takeIf {
            val authority = it.substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore('#')
            authority.isNotBlank() && '@' !in authority
        }
        "mailto" -> url.takeIf { '@' in it.substringAfter(':') && '?' !in it && '%' !in it }
        "tel" -> url.takeIf { it.substringAfter(':').matches(Regex("[+0-9().-]+")) }
        else -> null
    }
}

fun MessageEntity.link(text: String): String? = when (kind) {
    MessageEntityKind.TEXT_URL -> safeExternalLink(value)
    MessageEntityKind.URL -> safeExternalLink(text.substring(offset, end))
    MessageEntityKind.EMAIL -> safeExternalLink("mailto:" + text.substring(offset, end))
    MessageEntityKind.PHONE -> safeExternalLink("tel:" + text.substring(offset, end))
    MessageEntityKind.MENTION -> text.substring(offset, end).removePrefix("@").takeIf {
        it.matches(Regex("[A-Za-z0-9_]+"))
    }?.let { "https://t.me/$it" }
    else -> null
}

/** Styles are split at shared boundaries so partially overlapping selections never form invalid entities. */
fun RichMessageText.normalized(): RichMessageText {
    val valid = validEntities().distinct()
    val styles = setOf(MessageEntityKind.BOLD, MessageEntityKind.ITALIC, MessageEntityKind.UNDERLINE,
        MessageEntityKind.STRIKETHROUGH, MessageEntityKind.SPOILER)
    val boundaries = valid.flatMap { listOf(it.offset, it.end) }.distinct().sorted()
    val result = valid.flatMap { entity ->
        if (entity.kind !in styles) listOf(entity) else boundaries.zipWithNext().mapNotNull { (start, end) ->
            if (start >= entity.offset && end <= entity.end && valid.none {
                it.kind in setOf(MessageEntityKind.CODE, MessageEntityKind.PRE) && start < it.end && end > it.offset
            }) entity.copy(offset = start, length = end - start) else null
        }
    }.distinct().sortedWith(compareBy<MessageEntity> { it.offset }.thenByDescending { it.length }.thenBy { it.kind.ordinal })
    return copy(entities = result)
}

/** Text input changes retain unaffected formatting, including IME replacement and pasted text. */
fun RichMessageText.replaceText(newText: String): RichMessageText {
    if (newText == text) return this
    var prefix = 0
    while (prefix < minOf(text.length, newText.length) && text[prefix] == newText[prefix]) prefix++
    while (!text.isUtf16Boundary(prefix) || !newText.isUtf16Boundary(prefix)) prefix--
    var suffix = 0
    while (suffix < minOf(text.length, newText.length) - prefix && text[text.lastIndex - suffix] == newText[newText.lastIndex - suffix]) suffix++
    while (!text.isUtf16Boundary(text.length - suffix) || !newText.isUtf16Boundary(newText.length - suffix)) suffix--
    val oldEnd = text.length - suffix
    val delta = newText.length - text.length
    val mapped = validEntities().mapNotNull { entity ->
        val start = when {
            entity.offset >= oldEnd -> entity.offset + delta
            entity.offset >= prefix -> prefix
            else -> entity.offset
        }
        val end = when {
            entity.end <= prefix -> entity.end
            entity.end >= oldEnd -> entity.end + delta
            else -> newText.length - suffix
        }
        if (end <= start) null else entity.copy(offset = start, length = end - start)
    }
    return RichMessageText(newText, mapped).normalized()
}
