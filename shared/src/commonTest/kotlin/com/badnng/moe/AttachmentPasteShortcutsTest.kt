package com.badnng.moe

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import kotlin.test.Test
import kotlin.test.assertEquals

class AttachmentPasteShortcutsTest {
    @Test
    fun textAndCompositionPassThroughWhileFilePasteConsumesOnlyOnePress() {
        val shortcuts = AttachmentPasteShortcuts()
        fun press(available: Boolean, composing: Boolean = false) = shortcuts.handle(Key.V, KeyEventType.KeyDown,
            control = true, shift = false, alt = false, composing = composing, enabled = true) { available }
        assertEquals(AttachmentPasteAction.PASS, press(false))
        assertEquals(AttachmentPasteAction.PASS, press(true, composing = true))
        assertEquals(AttachmentPasteAction.PASTE, press(true))
        assertEquals(AttachmentPasteAction.CONSUME, press(true))
        assertEquals(AttachmentPasteAction.CONSUME, shortcuts.handle(Key.V, KeyEventType.KeyUp,
            control = false, shift = false, alt = false, composing = false, enabled = true) { false })
        assertEquals(AttachmentPasteAction.PASTE, press(true))
    }

    @Test
    fun shiftInsertWorksAndLosingFocusClearsTheHeldKey() {
        val shortcuts = AttachmentPasteShortcuts()
        fun insert() = shortcuts.handle(Key.Insert, KeyEventType.KeyDown, control = false, shift = true,
            alt = false, composing = false, enabled = true) { true }
        assertEquals(AttachmentPasteAction.PASTE, insert())
        assertEquals(AttachmentPasteAction.CONSUME, insert())
        shortcuts.reset()
        assertEquals(AttachmentPasteAction.PASTE, insert())
    }
}
