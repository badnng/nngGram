package com.badnng.moe

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType

internal enum class AttachmentPasteAction { PASS, CONSUME, PASTE }

/** Files/images are intercepted once; ordinary text and IME composition stay with TextField. */
internal class AttachmentPasteShortcuts {
    private var held: Key? = null

    fun reset() { held = null }

    fun handle(key: Key, type: KeyEventType, control: Boolean, shift: Boolean, alt: Boolean,
        composing: Boolean, enabled: Boolean, attachments: () -> Boolean): AttachmentPasteAction {
        if (type == KeyEventType.KeyUp && key == held) {
            held = null
            return AttachmentPasteAction.CONSUME
        }
        if (!enabled || composing || alt || type != KeyEventType.KeyDown) return AttachmentPasteAction.PASS
        if (key == held) return AttachmentPasteAction.CONSUME
        if ((key == Key.V && control && !shift || key == Key.Insert && shift && !control) && attachments()) {
            held = key
            return AttachmentPasteAction.PASTE
        }
        return AttachmentPasteAction.PASS
    }
}
