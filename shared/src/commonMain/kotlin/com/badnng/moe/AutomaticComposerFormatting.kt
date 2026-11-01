package com.badnng.moe

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.badnng.moe.chat.parseComposerMarkdown

/** Apply completed composer markup as native styles, without editing an active IME composition. */
internal fun automaticComposerFormatting(previous: TextFieldValue, next: TextFieldValue): TextFieldValue {
    if (next.composition != null || next.text.length > 65_536 ||
        (next.text == previous.text && previous.composition == null)) return next
    val parsed = next.richText().parseComposerMarkdown()
    if (!parsed.addedFormatting || parsed.content.text == next.text) return next
    return parsed.content.editorValue(TextRange(
        parsed.positions[next.selection.start], parsed.positions[next.selection.end],
    ))
}
