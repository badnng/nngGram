package com.badnng.moe.chat

/**
 * TextUtilities::CutPart, lib_ui 79c846fdf0327ff99d3efdc1cc4e11ef50bedc6d:
 * ui/text/text_entity.cpp, with UTF-16 entity offsets and a Unicode code-point limit.
 *
 * Cuts are chosen in the second half of a part, preferring paragraphs, lines, sentences and
 * words in that order. Only code and block quotes can cross a cut; an unavoidable cut through
 * another entity drops that entity, as in CutPart. No whitespace or text is removed here.
 */
fun splitMessageText(content: RichMessageText, maxCodePoints: Int): List<RichMessageText> {
    require(maxCodePoints > 0)
    if (content.text.isEmpty()) return emptyList()
    var remaining = content.copy(entities = content.validEntities().sortedWith(
        compareBy<MessageEntity> { it.offset }.thenByDescending { it.length },
    ))
    val parts = mutableListOf<RichMessageText>()
    while (remaining.text.isNotEmpty()) {
        val cut = messageTextCutPosition(remaining, maxCodePoints)
        if (cut == remaining.text.length) {
            parts += remaining
            break
        }
        val sendingEntities = mutableListOf<MessageEntity>()
        val remainingEntities = mutableListOf<MessageEntity>()
        for (entity in remaining.entities) {
            when {
                entity.end <= cut -> sendingEntities += entity
                entity.offset >= cut -> remainingEntities += entity.copy(offset = entity.offset - cut)
                entity.canCrossMessageSplit() -> {
                    sendingEntities += entity.copy(length = cut - entity.offset)
                    remainingEntities += entity.copy(offset = 0, length = entity.end - cut)
                }
                // A link, mention, custom emoji or other non-breakable entity must not acquire
                // a different meaning by becoming two independently interpreted entities.
                else -> Unit
            }
        }
        parts += RichMessageText(remaining.text.substring(0, cut), sendingEntities)
        remaining = RichMessageText(remaining.text.substring(cut), remainingEntities)
    }
    return parts
}

private fun messageTextCutPosition(content: RichMessageText, limit: Int): Int {
    val text = content.text
    var offset = 0
    var codePoints = 0
    var currentEntity = 0
    var good = 0
    var goodLevel = -1
    while (offset < text.length) {
        while (currentEntity < content.entities.size && content.entities[currentEntity].end <= offset) {
            currentEntity++
        }
        if (codePoints > limit / 2) {
            val entity = content.entities.getOrNull(currentEntity)
            val inEntity = entity != null && offset > entity.offset && offset < entity.end
            val canBreakEntity = entity?.canCrossMessageSplit() == true
            val previousEntity = content.entities.getOrNull(currentEntity - 1)
            val noEntityLevel = if (inEntity) 0 else 1
            val level = when {
                inEntity && !canBreakEntity -> 0
                text[offset].isMessageNewline() -> when {
                    inEntity -> if (text.getOrNull(offset + 1)?.isMessageNewline() == true) 12 else 11
                    text.getOrNull(offset + 1)?.isMessageNewline() == true -> 15
                    entity != null && offset + 1 == entity.offset && entity.isMessageBlock() -> 14
                    previousEntity != null && offset == previousEntity.end && previousEntity.isMessageBlock() -> 14
                    else -> 13
                }
                text[offset].isMessageSpace() -> when (text.getOrNull(offset - 1)) {
                    '.', '?', '!' -> 9 + noEntityLevel
                    ',', ':', ';' -> 7 + noEntityLevel
                    else -> 5 + noEntityLevel
                }
                text.getOrNull(offset - 1)?.isMessageWordSeparator() == true -> 3 + noEntityLevel
                else -> 1 + noEntityLevel
            }
            if (level >= goodLevel) {
                goodLevel = level
                good = offset
            }
        }

        val next = text.nextMessageTextUnit(offset)
        val unitCodePoints = text.messageCodePointCount(offset, next)
        if (unitCodePoints > limit - codePoints) {
            // CutPart's emoji traversal keeps complete emoji sequences. A pathological sequence
            // longer than the native limit must still make progress without exceeding that limit.
            if (good > 0) return good
            if (offset > 0) return offset
            var hardEnd = 0
            repeat(limit) {
                if (hardEnd < text.length) hardEnd += text.codePointWidth(hardEnd)
            }
            return hardEnd
        }
        codePoints += unitCodePoints
        offset = next
    }
    return text.length
}

private fun MessageEntity.canCrossMessageSplit(): Boolean = kind == MessageEntityKind.CODE || isMessageBlock()

private fun MessageEntity.isMessageBlock(): Boolean = kind == MessageEntityKind.PRE ||
    kind == MessageEntityKind.BLOCK_QUOTE || kind == MessageEntityKind.EXPANDABLE_BLOCK_QUOTE

private fun Char.isMessageNewline(): Boolean = this == '\n' || code == 156

private fun Char.isMessageSpace(): Boolean = isWhitespace() || code < 32 || this == '\uFFFC'

private fun Char.isMessageWordSeparator(): Boolean = this in " \n.,?!@#$:;-<>[](){}=/+%&^*'\"`~|" ||
    this in "\u2013\u2014\u2018\u2019\u201C\u201D\u2026\uFFFC"

private fun String.codePointWidth(offset: Int): Int =
    if (this[offset].isHighSurrogate() && getOrNull(offset + 1)?.isLowSurrogate() == true) 2 else 1

private fun String.messageCodePoint(offset: Int): Int = if (codePointWidth(offset) == 1) this[offset].code else
    0x10000 + ((this[offset].code - 0xD800) shl 10) + (this[offset + 1].code - 0xDC00)

private fun String.messageCodePointCount(start: Int, end: Int): Int {
    var offset = start
    var result = 0
    while (offset < end) {
        offset += codePointWidth(offset)
        result++
    }
    return result
}

/** Surrogates, CRLF and emoji presentation/skin/flag/keycap/ZWJ/tag sequences stay together. */
private fun String.nextMessageTextUnit(start: Int): Int {
    if (this[start] == '\r' && getOrNull(start + 1) == '\n') return start + 2
    val first = messageCodePoint(start)
    var end = start + codePointWidth(start)
    if (first in 0x1F1E6..0x1F1FF && end < length && messageCodePoint(end) in 0x1F1E6..0x1F1FF) {
        return end + codePointWidth(end)
    }
    if (first == '#'.code || first == '*'.code || first in '0'.code..'9'.code) {
        if (end < length && messageCodePoint(end) == 0xFE0F) end += codePointWidth(end)
        return if (end < length && messageCodePoint(end) == 0x20E3) end + codePointWidth(end)
        else start + codePointWidth(start)
    }
    if (!first.isMessageEmojiBase()) return end
    while (end < length) {
        val next = messageCodePoint(end)
        when {
            next == 0xFE0E || next == 0xFE0F || next in 0x1F3FB..0x1F3FF || next in 0xE0020..0xE007F ->
                end += codePointWidth(end)
            next == 0x200D -> {
                val joined = end + 1
                if (joined >= length || !messageCodePoint(joined).isMessageEmojiBase()) return end
                end = joined + codePointWidth(joined)
            }
            else -> return end
        }
    }
    return end
}

private fun Int.isMessageEmojiBase(): Boolean = this in 0x1F000..0x1FAFF || this in 0x2300..0x23FF ||
    this in 0x2600..0x27FF || this in 0x2B00..0x2BFF || this in 0x2190..0x21FF ||
    this == 0x00A9 || this == 0x00AE || this == 0x203C || this == 0x2049 || this == 0x2122 ||
    this == 0x2139 || this == 0x3030 || this == 0x303D || this == 0x3297 || this == 0x3299
