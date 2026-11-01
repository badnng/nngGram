package com.badnng.moe

import com.badnng.moe.chat.ChatToken
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ComposerModalFocusReturnTest {
    private val token = ChatToken(1, 1, 10)

    @Test fun onlyTheConfirmedMountedTargetCanReturnFocusAfterItsModalIsGone() {
        val state = ComposerModalFocusReturn()
        var focuses = 0
        var mounted = true
        state.register(token, { true }) { focuses++ }
        val intent = state.begin(token)
        state.accepted(intent) { !mounted }
        val pending = assertNotNull(state.pending)
        assertEquals(false, pending.ready())
        mounted = false
        state.complete(pending)
        state.complete(pending)
        assertEquals(1, focuses)
        assertNull(state.pending)
    }

    @Test fun laterRealInputCancellationAndTargetDisposalRevokeOldAcceptedCallbacks() {
        for (reason in listOf("input", "cancel", "dispose", "page", "new-dialog")) {
            val state = ComposerModalFocusReturn()
            var focuses = 0
            var allowed = true
            val owner = state.register(token, { allowed }) { focuses++ }
            val intent = state.begin(token)
            state.accepted(intent) { true }
            val pending = assertNotNull(state.pending)
            when (reason) {
                "input" -> state.userInput()
                "cancel" -> state.cancel(intent)
                "dispose" -> state.unregister(owner)
                "page" -> allowed = false
                "new-dialog" -> state.begin(token)
            }
            state.complete(pending)
            state.accepted(intent) { true }
            state.pending?.let(state::complete)
            assertEquals(0, focuses, "An old $reason result cannot reclaim focus.")
        }
    }

    @Test fun aNewChatRegistrationCannotReceiveAnOldDialogsFocusReturn() {
        val state = ComposerModalFocusReturn()
        var focuses = 0
        val previous = state.register(token, { true }) { focuses++ }
        val intent = state.begin(token)
        state.unregister(previous)
        state.register(token.copy(selection = 2, chatId = 11), { true }) { focuses++ }
        state.accepted(intent) { true }
        assertNull(state.pending)
        assertNull(state.begin(token))
        assertEquals(0, focuses)
    }
}
