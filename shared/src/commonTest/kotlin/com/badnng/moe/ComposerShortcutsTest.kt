package com.badnng.moe

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import kotlin.test.*

class ComposerShortcutsTest {
    @Test fun configuredControlEnterModeKeepsPlainEnterAndShiftEnterAsNewlines() {
        for (key in listOf(Key.Enter, Key.NumPadEnter)) {
            fun action(ctrl: Boolean = false, shift: Boolean = false, meta: Boolean = false, alt: Boolean = false) =
                ComposerShortcutHandler().handle(key, KeyEventType.KeyDown, ctrl = ctrl, shift = shift,
                    meta = meta, alt = alt, sendKey = SettingsSendKey.CTRL_ENTER)
            assertEquals(ComposerShortcutAction.PASS_THROUGH, action())
            assertEquals(ComposerShortcutAction.PASS_THROUGH, action(shift = true))
            assertEquals(ComposerShortcutAction.SUBMIT, action(ctrl = true))
            assertEquals(ComposerShortcutAction.SUBMIT, action(meta = true))
            assertEquals(ComposerShortcutAction.SUBMIT, action(ctrl = true, shift = true))
            assertEquals(ComposerShortcutAction.PASS_THROUGH, action(ctrl = true, alt = true))
        }
    }

    @Test fun configuredControlEnterTracksQueuedModifiersAndSubmitsOnlyOnce() {
        val handler = ComposerShortcutHandler()
        handler.handle(Key.CtrlLeft, KeyEventType.KeyDown, sendKey = SettingsSendKey.CTRL_ENTER)
        assertEquals(ComposerShortcutAction.SUBMIT,
            handler.handle(Key.Enter, KeyEventType.KeyDown, sendKey = SettingsSendKey.CTRL_ENTER))
        handler.handle(Key.CtrlLeft, KeyEventType.KeyUp, sendKey = SettingsSendKey.CTRL_ENTER)
        assertEquals(ComposerShortcutAction.CONSUME,
            handler.handle(Key.Enter, KeyEventType.KeyDown, sendKey = SettingsSendKey.CTRL_ENTER))
        assertEquals(ComposerShortcutAction.CONSUME,
            handler.handle(Key.Enter, KeyEventType.KeyUp, sendKey = SettingsSendKey.CTRL_ENTER))
        assertEquals(ComposerShortcutAction.PASS_THROUGH,
            handler.handle(Key.Enter, KeyEventType.KeyDown, sendKey = SettingsSendKey.CTRL_ENTER))
    }

    @Test fun changingSendModeCannotTurnAHeldNewlineOrImeConfirmationIntoSubmission() {
        val handler = ComposerShortcutHandler()
        assertEquals(ComposerShortcutAction.PASS_THROUGH,
            handler.handle(Key.Enter, KeyEventType.KeyDown, sendKey = SettingsSendKey.CTRL_ENTER))
        assertEquals(ComposerShortcutAction.PASS_THROUGH,
            handler.handle(Key.Enter, KeyEventType.KeyDown, sendKey = SettingsSendKey.ENTER))
        handler.handle(Key.Enter, KeyEventType.KeyUp)
        assertEquals(ComposerShortcutAction.PASS_THROUGH,
            handler.handle(Key.Enter, KeyEventType.KeyDown, ctrl = true, composing = true,
                sendKey = SettingsSendKey.CTRL_ENTER))
        assertEquals(ComposerShortcutAction.PASS_THROUGH,
            handler.handle(Key.Enter, KeyEventType.KeyDown, ctrl = true, sendKey = SettingsSendKey.CTRL_ENTER))
        handler.handle(Key.Enter, KeyEventType.KeyUp)
        assertEquals(ComposerShortcutAction.SUBMIT,
            handler.handle(Key.Enter, KeyEventType.KeyDown, ctrl = true, sendKey = SettingsSendKey.CTRL_ENTER))
    }

    @Test fun configuredControlEnterStillHonorsPendingEmptyAndAbsentSubmitCallbacks() {
        fun action(canSubmit: Boolean = true, hasText: Boolean = true, submitAvailable: Boolean = true) =
            ComposerShortcutHandler().handle(Key.Enter, KeyEventType.KeyDown, ctrl = true,
                canSubmit = canSubmit, hasText = hasText, submitAvailable = submitAvailable,
                sendKey = SettingsSendKey.CTRL_ENTER)
        assertEquals(ComposerShortcutAction.CONSUME, action(canSubmit = false))
        assertEquals(ComposerShortcutAction.CONSUME, action(hasText = false))
        assertEquals(ComposerShortcutAction.PASS_THROUGH, action(submitAvailable = false))
    }

    @Test fun queuedShiftEnterWithoutFlagsRemainsANewlineUntilEnterIsReleased() {
        for (shift in listOf(Key.ShiftLeft, Key.ShiftRight)) {
            for (enter in listOf(Key.Enter, Key.NumPadEnter)) {
                val handler = ComposerShortcutHandler()
                assertEquals(ComposerShortcutAction.PASS_THROUGH, handler.handle(shift, KeyEventType.KeyDown))
                assertEquals(ComposerShortcutAction.PASS_THROUGH, handler.handle(enter, KeyEventType.KeyDown))
                handler.handle(shift, KeyEventType.KeyUp)
                assertEquals(ComposerShortcutAction.PASS_THROUGH, handler.handle(enter, KeyEventType.KeyDown))
                assertEquals(ComposerShortcutAction.PASS_THROUGH, handler.handle(enter, KeyEventType.KeyUp))
                assertEquals(ComposerShortcutAction.SUBMIT, handler.handle(enter, KeyEventType.KeyDown))
            }
        }
    }

    @Test fun queuedControlOrMetaShiftEnterWithoutFlagsSubmitsOnce() {
        for (control in listOf(Key.CtrlLeft, Key.CtrlRight, Key.MetaLeft, Key.MetaRight)) {
            val handler = ComposerShortcutHandler()
            handler.handle(control, KeyEventType.KeyDown)
            handler.handle(Key.ShiftLeft, KeyEventType.KeyDown)
            assertEquals(ComposerShortcutAction.SUBMIT, handler.handle(Key.Enter, KeyEventType.KeyDown))
            assertEquals(ComposerShortcutAction.CONSUME, handler.handle(Key.Enter, KeyEventType.KeyDown))
            assertEquals(ComposerShortcutAction.CONSUME, handler.handle(Key.Enter, KeyEventType.KeyUp))
            handler.handle(Key.ShiftLeft, KeyEventType.KeyUp)
            assertEquals(ComposerShortcutAction.PASS_THROUGH, handler.handle(Key.Enter, KeyEventType.KeyDown))
            handler.handle(Key.Enter, KeyEventType.KeyUp)
            handler.handle(control, KeyEventType.KeyUp)
            assertEquals(ComposerShortcutAction.SUBMIT, handler.handle(Key.Enter, KeyEventType.KeyDown))
        }
    }

    @Test fun releasingOneShiftDoesNotClearTheOtherPhysicalShift() {
        val handler = ComposerShortcutHandler()
        handler.handle(Key.ShiftLeft, KeyEventType.KeyDown)
        handler.handle(Key.ShiftRight, KeyEventType.KeyDown)
        handler.handle(Key.ShiftLeft, KeyEventType.KeyUp)
        assertEquals(ComposerShortcutAction.PASS_THROUGH, handler.handle(Key.Enter, KeyEventType.KeyDown))
        handler.handle(Key.Enter, KeyEventType.KeyUp)
        handler.handle(Key.ShiftRight, KeyEventType.KeyUp)
        assertEquals(ComposerShortcutAction.SUBMIT, handler.handle(Key.Enter, KeyEventType.KeyDown))
    }

    @Test fun nativeFlagsAndTrackedModifierKeysCanSupplyDifferentPartsOfAChord() {
        val trackedShift = ComposerShortcutHandler()
        trackedShift.handle(Key.ShiftLeft, KeyEventType.KeyDown)
        assertEquals(ComposerShortcutAction.SUBMIT, trackedShift.handle(Key.Enter, KeyEventType.KeyDown, ctrl = true))
        val trackedControl = ComposerShortcutHandler()
        trackedControl.handle(Key.CtrlRight, KeyEventType.KeyDown)
        assertEquals(ComposerShortcutAction.SUBMIT, trackedControl.handle(Key.Enter, KeyEventType.KeyDown, shift = true))
    }

    @Test fun focusResetClearsEveryTrackedModifierEvenWhenItsReleaseWasLost() {
        for (modifier in listOf(Key.ShiftLeft, Key.ShiftRight, Key.CtrlLeft, Key.CtrlRight,
            Key.AltLeft, Key.AltRight, Key.MetaLeft, Key.MetaRight)) {
            val handler = ComposerShortcutHandler()
            handler.handle(modifier, KeyEventType.KeyDown)
            handler.handle(Key.Enter, KeyEventType.KeyDown, composing = true)
            handler.reset()
            assertEquals(ComposerShortcutAction.SUBMIT, handler.handle(Key.Enter, KeyEventType.KeyDown))
        }
    }

    @Test fun modifierReleasesCannotTurnAHeldImeConfirmationIntoASend() {
        val handler = ComposerShortcutHandler()
        handler.handle(Key.CtrlLeft, KeyEventType.KeyDown)
        handler.handle(Key.ShiftRight, KeyEventType.KeyDown)
        assertEquals(ComposerShortcutAction.PASS_THROUGH, handler.handle(Key.Enter, KeyEventType.KeyDown, composing = true))
        handler.handle(Key.CtrlLeft, KeyEventType.KeyUp)
        handler.handle(Key.ShiftRight, KeyEventType.KeyUp)
        assertEquals(ComposerShortcutAction.PASS_THROUGH, handler.handle(Key.Enter, KeyEventType.KeyDown))
        assertEquals(ComposerShortcutAction.PASS_THROUGH, handler.handle(Key.Enter, KeyEventType.KeyUp))
        assertEquals(ComposerShortcutAction.SUBMIT, handler.handle(Key.Enter, KeyEventType.KeyDown))
    }

    @Test fun queuedAltEnterCannotSendWhileEitherAltIsHeld() {
        for (alt in listOf(Key.AltLeft, Key.AltRight)) {
            val handler = ComposerShortcutHandler()
            handler.handle(alt, KeyEventType.KeyDown)
            assertEquals(ComposerShortcutAction.PASS_THROUGH, handler.handle(Key.Enter, KeyEventType.KeyDown))
            handler.handle(Key.Enter, KeyEventType.KeyUp)
            handler.handle(alt, KeyEventType.KeyUp)
            assertEquals(ComposerShortcutAction.SUBMIT, handler.handle(Key.Enter, KeyEventType.KeyDown))
        }
    }

    @Test fun queuedControlOUsesTheSameTrackedChordAndReturnsToPlainTypingOnRelease() {
        val handler = ComposerShortcutHandler()
        handler.handle(Key.CtrlLeft, KeyEventType.KeyDown)
        assertEquals(ComposerShortcutAction.ATTACH, handler.handle(Key.O, KeyEventType.KeyDown, attachAvailable = true))
        assertEquals(ComposerShortcutAction.CONSUME, handler.handle(Key.O, KeyEventType.KeyDown, attachAvailable = true))
        assertEquals(ComposerShortcutAction.CONSUME, handler.handle(Key.O, KeyEventType.KeyUp))
        handler.handle(Key.CtrlLeft, KeyEventType.KeyUp)
        assertEquals(ComposerShortcutAction.PASS_THROUGH, handler.handle(Key.O, KeyEventType.KeyDown, attachAvailable = true))
    }

    @Test fun enterSubmitsOnceUntilReleasedIncludingNumpadEnter() {
        for (key in listOf(Key.Enter, Key.NumPadEnter)) {
            val handler = ComposerShortcutHandler()
            assertEquals(ComposerShortcutAction.SUBMIT, handler.handle(key, KeyEventType.KeyDown))
            repeat(5) { assertEquals(ComposerShortcutAction.CONSUME, handler.handle(key, KeyEventType.KeyDown)) }
            assertEquals(ComposerShortcutAction.CONSUME, handler.handle(key, KeyEventType.KeyUp))
            assertEquals(ComposerShortcutAction.SUBMIT, handler.handle(key, KeyEventType.KeyDown))
        }
    }

    @Test fun shiftAndControlEnterRemainNewlinesWhileControlShiftEnterSubmits() {
        assertEquals(ComposerShortcutAction.PASS_THROUGH, ComposerShortcutHandler().handle(Key.Enter, KeyEventType.KeyDown, shift = true))
        assertEquals(ComposerShortcutAction.PASS_THROUGH, ComposerShortcutHandler().handle(Key.Enter, KeyEventType.KeyDown, ctrl = true))
        assertEquals(ComposerShortcutAction.SUBMIT, ComposerShortcutHandler().handle(Key.Enter, KeyEventType.KeyDown, ctrl = true, shift = true))
        assertEquals(ComposerShortcutAction.SUBMIT, ComposerShortcutHandler().handle(Key.Enter, KeyEventType.KeyDown, meta = true, shift = true))
        assertEquals(ComposerShortcutAction.PASS_THROUGH, ComposerShortcutHandler().handle(Key.Enter, KeyEventType.KeyDown, alt = true))
    }

    @Test fun imeConfirmationAndItsRepeatsCannotSendAfterCompositionCommits() {
        val handler = ComposerShortcutHandler()
        assertEquals(ComposerShortcutAction.PASS_THROUGH, handler.handle(Key.Enter, KeyEventType.KeyDown, composing = true))
        assertEquals(ComposerShortcutAction.PASS_THROUGH, handler.handle(Key.Enter, KeyEventType.KeyDown, composing = false))
        assertEquals(ComposerShortcutAction.PASS_THROUGH, handler.handle(Key.Enter, KeyEventType.KeyUp))
        assertEquals(ComposerShortcutAction.SUBMIT, handler.handle(Key.Enter, KeyEventType.KeyDown))
        assertEquals(ComposerShortcutAction.PASS_THROUGH, ComposerShortcutHandler().handle(Key.Enter, KeyEventType.KeyDown,
            ctrl = true, shift = true, composing = true))
    }

    @Test fun sendGesturesWithNoTextOrPendingSendAreConsumedWithoutSubmitting() {
        assertEquals(ComposerShortcutAction.CONSUME, ComposerShortcutHandler().handle(Key.Enter, KeyEventType.KeyDown, hasText = false))
        assertEquals(ComposerShortcutAction.CONSUME, ComposerShortcutHandler().handle(Key.Enter, KeyEventType.KeyDown, canSubmit = false))
        assertEquals(ComposerShortcutAction.PASS_THROUGH, ComposerShortcutHandler().handle(Key.Enter, KeyEventType.KeyDown, enabled = false))
    }

    @Test fun editorsWithoutSubmitCallbacksKeepTheirNormalEnterBehavior() {
        val handler = ComposerShortcutHandler()
        assertEquals(ComposerShortcutAction.PASS_THROUGH, handler.handle(Key.Enter, KeyEventType.KeyDown, submitAvailable = false))
        assertEquals(ComposerShortcutAction.PASS_THROUGH, handler.handle(Key.Enter, KeyEventType.KeyUp, submitAvailable = false))
        assertEquals(ComposerShortcutAction.PASS_THROUGH, handler.handle(Key.Enter, KeyEventType.KeyDown,
            ctrl = true, shift = true, submitAvailable = false))
    }

    @Test fun clearingACaptionCanSubmitWithoutAllowingEmptyMessagesOrBypassingImeAndPendingGuards() {
        assertEquals(ComposerShortcutAction.CONSUME, ComposerShortcutHandler().handle(Key.Enter, KeyEventType.KeyDown,
            hasText = false))
        assertEquals(ComposerShortcutAction.SUBMIT, ComposerShortcutHandler().handle(Key.Enter, KeyEventType.KeyDown,
            hasText = false, allowEmptySubmit = true))
        assertEquals(ComposerShortcutAction.CONSUME, ComposerShortcutHandler().handle(Key.Enter, KeyEventType.KeyDown,
            hasText = false, allowEmptySubmit = true, canSubmit = false))
        assertEquals(ComposerShortcutAction.PASS_THROUGH, ComposerShortcutHandler().handle(Key.Enter, KeyEventType.KeyDown,
            hasText = false, allowEmptySubmit = true, composing = true))
    }

    @Test fun escapeCancelsOnceAndLeavesImeOrUnconfiguredEditorsAlone() {
        val handler = ComposerShortcutHandler()
        assertEquals(ComposerShortcutAction.CANCEL, handler.handle(Key.Escape, KeyEventType.KeyDown, cancelAvailable = true))
        assertEquals(ComposerShortcutAction.CONSUME, handler.handle(Key.Escape, KeyEventType.KeyDown, cancelAvailable = true))
        assertEquals(ComposerShortcutAction.CONSUME, handler.handle(Key.Escape, KeyEventType.KeyUp))
        assertEquals(ComposerShortcutAction.PASS_THROUGH, handler.handle(Key.Escape, KeyEventType.KeyDown, cancelAvailable = false))
        assertEquals(ComposerShortcutAction.PASS_THROUGH, ComposerShortcutHandler().handle(Key.Escape, KeyEventType.KeyDown,
            composing = true, cancelAvailable = true))
    }

    @Test fun losingFocusResetsHeldKeysWithoutCapturingTheDraft() {
        val handler = ComposerShortcutHandler()
        assertEquals(ComposerShortcutAction.SUBMIT, handler.handle(Key.Enter, KeyEventType.KeyDown))
        handler.reset()
        assertEquals(ComposerShortcutAction.SUBMIT, handler.handle(Key.Enter, KeyEventType.KeyDown))
        assertEquals(ComposerShortcutAction.PASS_THROUGH, handler.handle(Key.A, KeyEventType.KeyDown))
        assertEquals(ComposerShortcutAction.PASS_THROUGH, handler.handle(Key.Escape, KeyEventType.KeyUp))
    }

    @Test fun upEditsTheLastMessageOnceOnlyWhenTheDraftIsEmpty() {
        val handler = ComposerShortcutHandler()
        assertEquals(ComposerShortcutAction.EDIT_LAST, handler.handle(Key.DirectionUp, KeyEventType.KeyDown,
            isEmpty = true, editLastAvailable = true))
        assertEquals(ComposerShortcutAction.CONSUME, handler.handle(Key.DirectionUp, KeyEventType.KeyDown,
            isEmpty = true, editLastAvailable = true))
        assertEquals(ComposerShortcutAction.CONSUME, handler.handle(Key.DirectionUp, KeyEventType.KeyUp))
        assertEquals(ComposerShortcutAction.PASS_THROUGH, handler.handle(Key.DirectionUp, KeyEventType.KeyDown,
            isEmpty = false, hasText = false, editLastAvailable = true))
        assertEquals(ComposerShortcutAction.PASS_THROUGH, ComposerShortcutHandler().handle(Key.DirectionUp, KeyEventType.KeyDown,
            isEmpty = true, composing = true, editLastAvailable = true))
        assertEquals(ComposerShortcutAction.PASS_THROUGH, ComposerShortcutHandler().handle(Key.DirectionUp, KeyEventType.KeyDown,
            isEmpty = true, shift = true, editLastAvailable = true))
        assertEquals(ComposerShortcutAction.PASS_THROUGH, ComposerShortcutHandler().handle(Key.DirectionUp, KeyEventType.KeyDown,
            isEmpty = true, editLastAvailable = false))
    }

    @Test fun controlOOpensAttachmentsOnceWithoutTakingPlainTypingOrImeKeys() {
        val handler = ComposerShortcutHandler()
        assertEquals(ComposerShortcutAction.ATTACH, handler.handle(Key.O, KeyEventType.KeyDown, ctrl = true, attachAvailable = true))
        assertEquals(ComposerShortcutAction.CONSUME, handler.handle(Key.O, KeyEventType.KeyDown, ctrl = true, attachAvailable = true))
        assertEquals(ComposerShortcutAction.CONSUME, handler.handle(Key.O, KeyEventType.KeyUp))
        assertEquals(ComposerShortcutAction.PASS_THROUGH, handler.handle(Key.O, KeyEventType.KeyDown, attachAvailable = true))
        assertEquals(ComposerShortcutAction.PASS_THROUGH, ComposerShortcutHandler().handle(Key.O, KeyEventType.KeyDown,
            ctrl = true, composing = true, attachAvailable = true))
        assertEquals(ComposerShortcutAction.PASS_THROUGH, ComposerShortcutHandler().handle(Key.O, KeyEventType.KeyDown,
            ctrl = true, attachAvailable = false))
        assertEquals(ComposerShortcutAction.PASS_THROUGH, ComposerShortcutHandler().handle(Key.O, KeyEventType.KeyDown,
            ctrl = true, shift = true, attachAvailable = true))
        assertEquals(ComposerShortcutAction.PASS_THROUGH, ComposerShortcutHandler().handle(Key.O, KeyEventType.KeyDown,
            ctrl = true, enabled = false, attachAvailable = true))
    }
}
