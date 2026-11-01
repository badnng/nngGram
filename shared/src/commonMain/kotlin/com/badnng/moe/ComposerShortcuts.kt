package com.badnng.moe

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType

internal enum class ComposerShortcutAction { PASS_THROUGH, CONSUME, SUBMIT, CANCEL, EDIT_LAST, ATTACH }

/** Stores only held keys, never message content. A held IME confirmation cannot become a send. */
internal class ComposerShortcutHandler {
    private val held = mutableMapOf<Key, Boolean>()
    private val modifiers = mutableSetOf<Key>()

    fun reset() {
        held.clear()
        modifiers.clear()
    }

    fun handle(
        key: Key,
        type: KeyEventType,
        shift: Boolean = false,
        ctrl: Boolean = false,
        alt: Boolean = false,
        meta: Boolean = false,
        composing: Boolean = false,
        enabled: Boolean = true,
        hasText: Boolean = true,
        allowEmptySubmit: Boolean = false,
        isEmpty: Boolean = false,
        canSubmit: Boolean = true,
        submitAvailable: Boolean = true,
        cancelAvailable: Boolean = false,
        editLastAvailable: Boolean = false,
        attachAvailable: Boolean = false,
        sendKey: SettingsSendKey = SettingsSendKey.ENTER,
    ): ComposerShortcutAction {
        // Some native hosts sample modifier flags after a queued chord was released.
        // Keep the actual modifier key sequence as well, without retaining typed text.
        if (key == Key.ShiftLeft || key == Key.ShiftRight || key == Key.CtrlLeft || key == Key.CtrlRight ||
            key == Key.AltLeft || key == Key.AltRight || key == Key.MetaLeft || key == Key.MetaRight) {
            when (type) {
                KeyEventType.KeyDown -> modifiers.add(key)
                KeyEventType.KeyUp -> modifiers.remove(key)
            }
            return ComposerShortcutAction.PASS_THROUGH
        }
        if (key != Key.Enter && key != Key.NumPadEnter && key != Key.Escape &&
            key != Key.DirectionUp && key != Key.NumPadDirectionUp && key != Key.O) return ComposerShortcutAction.PASS_THROUGH
        if (type == KeyEventType.KeyUp) {
            return if (held.remove(key) == true) ComposerShortcutAction.CONSUME else ComposerShortcutAction.PASS_THROUGH
        }
        if (type != KeyEventType.KeyDown) return ComposerShortcutAction.PASS_THROUGH
        held[key]?.let { return if (it) ComposerShortcutAction.CONSUME else ComposerShortcutAction.PASS_THROUGH }
        held[key] = false
        if (!enabled || composing) return ComposerShortcutAction.PASS_THROUGH
        val shiftPressed = shift || Key.ShiftLeft in modifiers || Key.ShiftRight in modifiers
        val control = ctrl || meta || Key.CtrlLeft in modifiers || Key.CtrlRight in modifiers ||
            Key.MetaLeft in modifiers || Key.MetaRight in modifiers
        val altPressed = alt || Key.AltLeft in modifiers || Key.AltRight in modifiers
        if (key == Key.Escape) {
            if (!cancelAvailable || control || shiftPressed || altPressed) return ComposerShortcutAction.PASS_THROUGH
            held[key] = true
            return ComposerShortcutAction.CANCEL
        }
        if (key == Key.DirectionUp || key == Key.NumPadDirectionUp) {
            if (!editLastAvailable || !isEmpty || control || shiftPressed || altPressed) return ComposerShortcutAction.PASS_THROUGH
            held[key] = true
            return ComposerShortcutAction.EDIT_LAST
        }
        if (key == Key.O) {
            if (!attachAvailable || !control || shiftPressed || altPressed) return ComposerShortcutAction.PASS_THROUGH
            held[key] = true
            return ComposerShortcutAction.ATTACH
        }
        // Ctrl+Shift+Enter always submits, while the selected mode controls the ordinary chord.
        // Keep this after the IME/held-key checks: changing a setting cannot turn a held
        // composition-confirmation key into a send.
        val submitGesture = !altPressed && ((control && shiftPressed) || when (sendKey) {
            SettingsSendKey.ENTER -> !control && !shiftPressed
            SettingsSendKey.CTRL_ENTER -> control && !shiftPressed
        })
        if (!submitAvailable || !submitGesture) return ComposerShortcutAction.PASS_THROUGH
        held[key] = true
        return if ((hasText || allowEmptySubmit) && canSubmit) ComposerShortcutAction.SUBMIT else ComposerShortcutAction.CONSUME
    }
}
