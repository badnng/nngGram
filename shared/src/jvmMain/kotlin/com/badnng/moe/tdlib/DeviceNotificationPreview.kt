package com.badnng.moe.tdlib

import org.drinkless.tdlib.TdApi

/** Notification-only projection. Native UTF-16 message/entity identities are never modified. */
internal fun deviceNotificationPreview(value: TdApi.FormattedText?): String {
    val text = value?.text.orEmpty()
    val entities = value?.entities.orEmpty()
    // Bounded work and fail-closed fallback prevent malformed spoiler metadata from exposing text.
    if (text.length > 16_384 || entities.size > 2_048) return "新消息"
    val ranges = ArrayList<IntRange>()
    for (entity in entities) {
        if (entity?.type !is TdApi.TextEntityTypeSpoiler) continue
        val end = entity.offset.toLong() + entity.length.toLong()
        if (entity.offset < 0 || entity.length <= 0 || end > text.length || end <= entity.offset) return "消息含隐藏内容"
        var startIndex = entity.offset
        var endIndex = end.toInt()
        // If malformed native offsets touch half an emoji, conceal the complete surrogate pair.
        if (startIndex > 0 && startIndex < text.length && text[startIndex].isLowSurrogate() && text[startIndex - 1].isHighSurrogate()) startIndex--
        if (endIndex > 0 && endIndex < text.length && text[endIndex - 1].isHighSurrogate() && text[endIndex].isLowSurrogate()) endIndex++
        ranges += startIndex until endIndex
    }
    if (ranges.isEmpty()) return text
    val merged = ArrayList<IntRange>()
    ranges.sortedBy { it.first }.forEach { next ->
        val last = merged.lastOrNull()
        if (last != null && next.first <= last.last + 1) merged[merged.lastIndex] = last.first..maxOf(last.last, next.last)
        else merged += next
    }
    return buildString(text.length) {
        var position = 0
        merged.forEach { range ->
            append(text, position, range.first)
            // Fixed Desktop TextWithPermanentSpoiler: one U+259A per native UTF-16 unit.
            repeat(range.last - range.first + 1) { append('\u259A') }
            position = range.last + 1
        }
        append(text, position, text.length)
    }
}

internal fun deviceNotificationFormattedText(content: TdApi.MessageContent?): TdApi.FormattedText? = when (content) {
    is TdApi.MessageText -> content.text
    is TdApi.MessagePhoto -> content.caption
    is TdApi.MessageVideo -> content.caption
    is TdApi.MessageAnimation -> content.caption
    is TdApi.MessageVoiceNote -> content.caption
    is TdApi.MessageAudio -> content.caption
    is TdApi.MessageDocument -> content.caption
    else -> null
}
