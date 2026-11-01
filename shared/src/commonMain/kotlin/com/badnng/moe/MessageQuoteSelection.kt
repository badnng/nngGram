package com.badnng.moe

import androidx.compose.ui.text.AnnotatedString
import com.badnng.moe.chat.MessageQuote
import com.badnng.moe.chat.RichMessageText

private const val QUOTE_SOURCE_OFFSET = "quote-source-offset"

/** SelectionState exposes clipped annotated texts, so keep source positions through clipping. */
internal fun AnnotatedString.withQuoteSourceOffsets(sourceOffset: Int = 0): AnnotatedString =
    AnnotatedString.Builder(this).apply {
        text.indices.forEach { index ->
            addStringAnnotation(QUOTE_SOURCE_OFFSET, (sourceOffset + index).toString(), index, index + 1)
        }
    }.toAnnotatedString()

/** Never locate a selection by searching its text: a message can contain repeated fragments. */
internal fun RichMessageText.sourceTextSelection(selectedTexts: List<AnnotatedString>): Pair<Int, RichMessageText>? {
    var start: Int? = null
    var end = 0
    for (part in selectedTexts.filter { it.isNotEmpty() }) {
        val offsets = part.getStringAnnotations(QUOTE_SOURCE_OFFSET, 0, part.length)
        if (offsets.size != part.length) return null
        val partStart = offsets.first().item.toIntOrNull() ?: return null
        if (offsets.withIndex().any { (index, offset) ->
                offset.start != index || offset.end != index + 1 || offset.item.toIntOrNull() != partStart + index
            }) return null
        val partEnd = partStart + part.length
        if (slice(partStart, partEnd)?.text != part.text) return null
        // Block rendering may omit whitespace-only separators. Restore those from the source.
        if (start != null && (partStart < end || text.substring(end, partStart).isNotBlank())) return null
        if (start == null) start = partStart
        end = partEnd
    }
    val position = start ?: return null
    val content = slice(position, end) ?: return null
    return position to content
}

internal fun RichMessageText.quoteFromSelection(selectedTexts: List<AnnotatedString>): MessageQuote? =
    sourceTextSelection(selectedTexts)?.takeIf { it.second.text.isNotBlank() }?.let { (position, content) ->
        MessageQuote(position, content)
    }
