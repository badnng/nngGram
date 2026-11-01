package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import kotlin.test.*

class ConversationNavigationControllerTest {
    @Test fun rememberedAnchorCanReloadAfterSwitchingAndArbitraryOrStaleAnchorsCannot() {
        val f = Fixture()
        f.add(message(100))
        val oldToken = f.state.token
        f.controller.rememberHistoryAnchor(oldToken, 100)
        f.select(20)
        f.controller.loadHistoryAnchor(oldToken, 100)
        assertTrue(f.session.around.isEmpty())
        assertEquals(0L, f.state.navigationFailureRevision)
        f.select(10)
        f.controller.loadHistoryAnchor(f.state.token, 999)
        assertTrue(f.session.around.isEmpty())
        assertEquals(1L, f.state.navigationFailureRevision)
        f.controller.loadHistoryAnchor(f.state.token, 100)
        assertEquals(10L to 100L, f.session.around.last().identity)
        f.complete(f.session.around.last(), listOf(message(100), message(99)))
        assertEquals(100L, f.state.jumpMessageId)
        assertEquals(listOf(99L, 100L), f.state.messages.map { it.id })
    }

    @Test fun replyUsesItsActualCrossChatTargetAndReturnsOnlyToTheRegisteredOrigin() {
        val f = Fixture()
        f.add(message(100))
        f.add(message(200).copy(replyTo = MessageReply(20, 300)))
        f.controller.rememberHistoryAnchor(f.state.token, 100)
        f.controller.openReplyMessage(f.state.token, 200)
        assertEquals(20L, f.state.token.chatId)
        assertEquals(20L to 300L, f.session.around.last().identity)
        f.complete(f.session.around.last(), listOf(message(300, 20)))
        val requests = f.session.around.size
        f.controller.openReplyReturn(f.state.token, 10, 200)
        assertEquals(requests, f.session.around.size)
        f.controller.openReplyReturn(f.state.token, 10, 100)
        assertEquals(10L, f.state.token.chatId)
        assertEquals(10L to 100L, f.session.around.last().identity)
        f.complete(f.session.around.last(), listOf(message(100)))
        assertEquals(100L, f.state.jumpMessageId)
    }

    @Test fun deletedReplyTargetsAndUnknownChatsNeverDispatchHistoryRequests() {
        val f = Fixture()
        f.add(message(100).copy(replyTo = MessageReply(20, 300)))
        f.controller.event(ChatEvent.Deleted(20, listOf(300), true))
        f.controller.openReplyMessage(f.state.token, 100)
        f.add(message(101).copy(replyTo = MessageReply(999, 300)))
        f.controller.openReplyMessage(f.state.token, 101)
        assertTrue(f.session.around.isEmpty())
        assertEquals(10L, f.state.token.chatId)
        assertEquals(2L, f.state.navigationFailureRevision)
    }

    @Test fun replyQuoteAndEditTargetRestoreIndependentlyWithoutPopulatingTheTimeline() {
        val f = Fixture()
        val reply = message(100).copy(text = "quote source")
        val edit = message(200).copy(outgoing = true, text = "edit source")
        f.add(reply); f.add(edit)
        f.controller.setQuote(f.state.token, 100, MessageQuote(0, RichMessageText("quote")))
        f.allowEdit(200)
        f.controller.startEdit(f.state.token, 200)
        f.select(20)
        f.select(10)
        val restored = f.session.around.takeLast(2)
        f.complete(restored.first { it.id == 100L }, listOf(reply))
        f.complete(restored.first { it.id == 200L }, listOf(edit))
        assertEquals(100L, f.state.replyTarget?.id)
        assertEquals("quote", f.state.replyQuote?.content?.text)
        assertNull(f.state.editTarget)
        f.completeActions(f.session.actions.last(), MessageActions(canEditText = true))
        assertEquals(200L, f.state.editTarget?.id)
        assertTrue(f.state.messages.isEmpty())
        f.controller.cancelEdit(f.state.token)
        assertEquals(100L, f.state.replyTarget?.id)
    }

    @Test fun restoredEditRequiresFreshPermissionAndLateRestorationCannotCrossSelection() {
        val f = Fixture()
        val edit = message(200).copy(outgoing = true)
        f.add(edit); f.allowEdit(200); f.controller.startEdit(f.state.token, 200)
        f.select(20); f.select(10)
        f.complete(f.session.around.last(), listOf(edit))
        f.completeActions(f.session.actions.last(), MessageActions())
        assertNull(f.state.editTarget)
        f.select(20); f.select(10)
        val pending = f.session.around.last()
        f.select(20)
        f.complete(pending, listOf(edit))
        assertEquals(20L, f.state.token.chatId)
        assertNull(f.state.editTarget)
    }

    @Test fun clearingAReplyWhileRestorationIsPendingPreventsTheLateQuoteFromReturning() {
        val f = Fixture()
        val reply = message(100)
        f.add(reply); f.controller.setReply(f.state.token, 100)
        f.select(20); f.select(10)
        val pending = f.session.around.last()
        f.controller.setReply(f.state.token, null)
        f.complete(pending, listOf(reply))
        assertNull(f.state.replyTarget)
    }

    @Test fun acceptanceInAnotherChatConsumesOnlyTheSubmittedReplyContext() {
        val f = Fixture()
        f.add(message(100)); f.controller.setReply(f.state.token, 100)
        f.controller.sendText(f.state.token, "submitted")
        val sent = f.session.sends.last()
        f.select(20)
        sent(ChatResult(message(200).copy(outgoing = true)))
        f.flush()
        f.select(10)
        // A consumed reply does not even require a preview restoration fetch.
        assertTrue(f.session.around.isEmpty())
        assertNull(f.state.replyTarget)
    }

    @Test fun acceptanceWhileTheOriginalChatsPreviewIsRestoringCannotReviveTheConsumedReply() {
        val f = Fixture()
        val reply = message(100)
        f.add(reply); f.controller.setReply(f.state.token, 100)
        f.controller.sendText(f.state.token, "submitted")
        val sent = f.session.sends.last()
        f.select(20); f.select(10)
        val restore = f.session.around.last()
        sent(ChatResult(message(200).copy(outgoing = true))); f.flush()
        f.complete(restore, listOf(reply))
        assertNull(f.state.replyTarget)
    }

    @Test fun anOldAcceptancePreservesAReplyReselectedWithTheSameMessageId() {
        val f = Fixture()
        val reply = message(100)
        f.add(reply); f.controller.setReply(f.state.token, 100)
        f.controller.sendText(f.state.token, "submitted")
        val sent = f.session.sends.last()
        f.select(20); f.select(10)
        f.add(reply)
        f.controller.setReply(f.state.token, 100)
        sent(ChatResult(message(200).copy(outgoing = true))); f.flush()
        assertEquals(100L, f.state.replyTarget?.id)
    }

    @Test fun acceptedEditInAnotherChatDoesNotRestoreAnAlreadyCompletedEditor() {
        val f = Fixture()
        val edited = message(200).copy(outgoing = true)
        f.add(edited); f.allowEdit(200); f.controller.startEdit(f.state.token, 200)
        f.controller.editMessage(f.state.token, "modified")
        val accepted = f.session.edits.last()
        f.select(20)
        accepted(ChatResult(edited.copy(text = "modified"))); f.flush()
        f.select(10)
        assertTrue(f.session.around.isEmpty())
        assertNull(f.state.editTarget)
    }

    @Test fun acceptedEditInvalidatesItsPendingRestorationButPreservesANewEditorForTheSameId() {
        val f = Fixture()
        val edited = message(200).copy(outgoing = true)
        f.add(edited); f.allowEdit(200); f.controller.startEdit(f.state.token, 200)
        val firstOwner = f.state.editDraftRevision
        f.controller.editMessage(f.state.token, "modified")
        val accepted = f.session.edits.last()
        f.select(20); f.select(10)
        val pending = f.session.around.last()
        accepted(ChatResult(edited.copy(text = "modified"))); f.flush()
        f.complete(pending, listOf(edited))
        assertNull(f.state.editTarget)

        f.add(edited); f.allowEdit(200); f.controller.startEdit(f.state.token, 200)
        assertNotEquals(firstOwner, f.state.editDraftRevision)
        accepted(ChatResult(edited.copy(text = "modified"))); f.flush()
        assertEquals(200L, f.state.editTarget?.id)
    }

    @Test fun pendingEditRemainsPendingAcrossSelectionAndFailureReleasesItsOriginalDraft() {
        val f = Fixture()
        val edited = message(200).copy(outgoing = true)
        f.add(edited); f.allowEdit(200); f.controller.startEdit(f.state.token, 200)
        val owner = f.state.editDraftRevision
        f.controller.editMessage(f.state.token, "modified")
        val completed = f.session.edits.last()
        f.select(20); f.select(10)
        f.complete(f.session.around.last(), listOf(edited))
        f.completeActions(f.session.actions.last(), MessageActions(canEditText = true))
        assertEquals(owner, f.state.editDraftRevision)
        assertTrue(f.state.editPending)
        completed(ChatResult(error = ChatError.REQUEST_FAILED)); f.flush()
        assertFalse(f.state.editPending)
        assertEquals(200L, f.state.editTarget?.id)
        assertEquals(owner, f.state.editDraftRevision)
    }

    @Test fun upShortcutSkipsNewerDeniedMessagesAndIncludesEmptyMediaCaptions() {
        val f = Fixture()
        f.add(message(100).copy(outgoing = true, text = "", kind = MessageKind.PHOTO))
        f.add(message(200).copy(outgoing = true))
        var chosen: Long? = null
        f.controller.requestPreviousEditable(f.state.token, null) { chosen = it.value }
        assertEquals(200L, f.session.actions.last().id)
        f.completeActions(f.session.actions.last(), MessageActions())
        assertEquals(100L, f.session.actions.last().id)
        f.completeActions(f.session.actions.last(), MessageActions(canEditCaption = true))
        assertEquals(100L, chosen)
        // Permission resolution does not open the editor; the UI must still approve its current empty draft.
        assertNull(f.state.editTarget)
        f.controller.startEdit(f.state.token, chosen!!)
        assertEquals(100L, f.state.editTarget?.id)
    }

    @Test fun upShortcutRejectsFrozenWindowsAndDropsAnOldSelectionsPermissionCallback() {
        val f = Fixture()
        f.add(message(100).copy(outgoing = true))
        var calls = 0
        f.controller.requestPreviousEditable(f.state.token, null) { calls++ }
        val pending = f.session.actions.last()
        f.select(20)
        f.completeActions(pending, MessageActions(canEditText = true))
        assertEquals(0, calls)
        f.add(message(300, 20).copy(outgoing = true))
        f.controller.openHistoryMessage(f.state.token, 300)
        val count = f.session.actions.size
        f.controller.requestPreviousEditable(f.state.token, null) { assertEquals(ChatError.UNSUPPORTED, it.error) }
        assertEquals(count, f.session.actions.size)
    }

    @Test fun completingALatestJumpOnlyClearsItsMarkerAndRestoresTheUpShortcut() {
        val f = Fixture()
        f.add(message(100).copy(text = "quote source"))
        f.add(message(200).copy(outgoing = true))
        f.controller.setQuote(f.state.token, 100, MessageQuote(0, RichMessageText("quote")))
        f.allowEdit(200)
        f.controller.startEdit(f.state.token, 200)
        f.controller.openHistoryMessage(f.state.token, 200)
        val before = f.state
        val historyRequests = f.session.around.size

        f.controller.completeHistoryJump(before.token, before.jumpRevision)

        assertEquals(before.copy(jumpMessageId = null, jumpHighlight = false), f.state,
            "Completion retains the selection, window, reply/quote and independent edit owner.")
        assertEquals(historyRequests, f.session.around.size)
        f.controller.cancelEdit(f.state.token)
        var chosen: Long? = null
        f.controller.requestPreviousEditable(f.state.token, null) { chosen = it.value }
        assertEquals(200L, f.session.actions.last().id)
        f.completeActions(f.session.actions.last(), MessageActions(canEditText = true))
        assertEquals(200L, chosen)
        assertEquals(100L, f.state.replyTarget?.id)
        assertEquals("quote", f.state.replyQuote?.content?.text)
    }

    @Test fun completingAJumpRejectsStaleSelectionAndRevisionAndIsIdempotent() {
        val f = Fixture()
        f.add(message(100))
        f.controller.openHistoryMessage(f.state.token, 100)
        val first = f.state
        f.controller.completeHistoryJump(first.token.copy(selection = first.token.selection + 1), first.jumpRevision)
        f.controller.completeHistoryJump(first.token, first.jumpRevision - 1)
        assertEquals(first, f.state)

        f.controller.openHistoryMessage(f.state.token, 100)
        val reselected = f.state
        f.controller.completeHistoryJump(first.token, first.jumpRevision)
        assertEquals(reselected, f.state, "A late completion cannot consume a new jump to the same message.")
        f.controller.completeHistoryJump(reselected.token, reselected.jumpRevision)
        val completed = f.state
        assertNull(completed.jumpMessageId)
        f.controller.completeHistoryJump(reselected.token, reselected.jumpRevision)
        assertEquals(completed, f.state)
    }

    @Test fun completingAJumpPreservesTheRegisteredReplyReturn() {
        val f = Fixture()
        f.add(message(100)); f.add(message(200))
        f.add(message(300).copy(replyTo = MessageReply(10, 200)))
        f.controller.rememberHistoryAnchor(f.state.token, 100)
        f.controller.openReplyMessage(f.state.token, 300)
        assertEquals(200L, f.state.jumpMessageId)
        f.controller.completeHistoryJump(f.state.token, f.state.jumpRevision)
        assertNull(f.state.jumpMessageId)
        val token = f.state.token
        f.controller.openReplyReturn(token, 10, 100)
        assertEquals(token, f.state.token)
        assertEquals(100L, f.state.jumpMessageId)
        assertTrue(f.session.around.isEmpty(), "The registered return uses the retained loaded window.")
        assertEquals(0L, f.state.navigationFailureRevision)
    }

    @Test fun completingAJumpRejectsAnUnloadedNewerGapAndItsPendingNativePage() {
        val f = Fixture()
        f.add(message(100)); f.add(message(200))
        f.controller.rememberHistoryAnchor(f.state.token, 100)
        f.select(20); f.select(10)
        f.controller.loadHistoryAnchor(f.state.token, 100)
        val pendingJump = f.state
        assertTrue(pendingJump.historyLoading)
        f.controller.completeHistoryJump(pendingJump.token, pendingJump.jumpRevision)
        assertEquals(pendingJump, f.state)
        f.complete(f.session.around.last(), listOf(message(100)))
        val gap = f.state
        assertEquals(100L, gap.jumpMessageId)
        assertTrue(gap.historyHasNewer)
        f.controller.completeHistoryJump(gap.token, gap.jumpRevision)
        assertEquals(gap, f.state)

        f.controller.loadNewer(gap.token)
        val pendingNewer = f.state
        assertTrue(pendingNewer.newerLoading)
        f.controller.completeHistoryJump(gap.token, gap.jumpRevision)
        assertEquals(pendingNewer, f.state)
        f.session.newer.last()(ChatResult(listOf(message(200)))); f.flush()
        assertFalse(f.state.historyHasNewer)
        assertFalse(f.state.newerLoading)
        f.controller.completeHistoryJump(f.state.token, gap.jumpRevision)
        assertNull(f.state.jumpMessageId)
    }

    @Test fun completingAJumpWaitsForAnOlderHistoryRequestWithoutResettingItsWindow() {
        val f = Fixture()
        f.add(message(100))
        f.controller.rememberHistoryAnchor(f.state.token, 100)
        f.select(20); f.select(10)
        f.controller.loadHistoryAnchor(f.state.token, 100)
        f.complete(f.session.around.last(), listOf(message(100)))
        assertFalse(f.state.historyHasNewer)
        f.session.holdHistory = true
        f.controller.loadOlder(f.state.token)
        val pending = f.state
        assertTrue(pending.historyLoading)
        assertEquals(100L, pending.jumpMessageId)
        f.controller.completeHistoryJump(pending.token, pending.jumpRevision)
        assertEquals(pending, f.state)
        f.session.history.last()(ChatResult(emptyList())); f.flush()
        val ready = f.state
        f.controller.completeHistoryJump(ready.token, ready.jumpRevision)
        assertEquals(ready.copy(jumpMessageId = null, jumpHighlight = false), f.state)
    }

    private class Fixture {
        var state = ChatSnapshot()
        val session = Session()
        private val queued = ArrayDeque<() -> Unit>()
        val controller = ChatController(1, session, queued::addLast, { state = it })
        init {
            controller.start(); flush()
            controller.event(ChatEvent.NewChat(ChatItem(10, "synthetic one", order = 100)))
            controller.event(ChatEvent.NewChat(ChatItem(20, "synthetic two", order = 90)))
            select(10)
        }
        fun select(id: Long) { controller.select(state.token, id); flush() }
        fun add(message: ChatMessage) { controller.event(ChatEvent.NewMessage(message)); flush() }
        fun flush() { while (queued.isNotEmpty()) queued.removeFirst()() }
        fun complete(call: Around, messages: List<ChatMessage>) { call.done(ChatResult(messages)); flush() }
        fun completeActions(call: Actions, actions: MessageActions) { call.done(ChatResult(actions)); flush() }
        fun allowEdit(id: Long) {
            controller.loadMessageActions(state.token, id)
            completeActions(session.actions.last(), MessageActions(canEditText = true))
        }
    }

    private data class Around(val chatId: Long, val id: Long, val done: (ChatResult<List<ChatMessage>>) -> Unit) {
        val identity get() = chatId to id
    }
    private data class Actions(val id: Long, val done: (ChatResult<MessageActions>) -> Unit)
    private class Session : TdChatSession {
        val around = mutableListOf<Around>()
        val actions = mutableListOf<Actions>()
        val sends = mutableListOf<(ChatResult<ChatMessage>) -> Unit>()
        val edits = mutableListOf<(ChatResult<ChatMessage>) -> Unit>()
        val newer = mutableListOf<(ChatResult<List<ChatMessage>>) -> Unit>()
        val history = mutableListOf<(ChatResult<List<ChatMessage>>) -> Unit>()
        var holdHistory = false
        override fun historyAround(chatId: Long, messageId: Long, result: (ChatResult<List<ChatMessage>>) -> Unit) {
            around += Around(chatId, messageId, result)
        }
        override fun messageActions(chatId: Long, messageId: Long, result: (ChatResult<MessageActions>) -> Unit) {
            actions += Actions(messageId, result)
        }
        override fun loadChats(limit: Int, result: (ChatResult<ChatLoadResult>) -> Unit) { result(ChatResult(ChatLoadResult(true))) }
        override fun history(chatId: Long, fromMessageId: Long, limit: Int, result: (ChatResult<List<ChatMessage>>) -> Unit) {
            if (holdHistory) history += result else result(ChatResult(emptyList()))
        }
        override fun newerHistory(chatId: Long, fromMessageId: Long, result: (ChatResult<List<ChatMessage>>) -> Unit) {
            newer += result
        }
        override fun open(chatId: Long) = Unit
        override fun close(chatId: Long) = Unit
        override fun sendText(chatId: Long, text: String, result: (ChatResult<ChatMessage>) -> Unit) = Unit
        override fun sendRichText(chatId: Long, text: RichMessageText, reply: OutgoingReply?, result: (ChatResult<ChatMessage>) -> Unit) {
            sends += result
        }
        override fun editMessage(chatId: Long, messageId: Long, text: String, caption: Boolean, result: (ChatResult<ChatMessage>) -> Unit) {
            edits += result
        }
        override fun retry(chatId: Long, messageId: Long, result: (ChatResult<List<ChatMessage>>) -> Unit) = Unit
        override fun markRead(chatId: Long, messageIds: List<Long>, result: (ChatResult<Unit>) -> Unit) = Unit
    }

    companion object { private fun message(id: Long, chatId: Long = 10) = ChatMessage(id, chatId, text = "synthetic") }
}
