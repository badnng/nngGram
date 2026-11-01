package com.badnng.moe

import com.badnng.moe.chat.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ClipboardSendQueueTest {
    private val token = ChatToken(1, 1, 100)
    private val photo = PreparedClipboardItem(LocalAttachment(AttachmentKind.FILE, "synthetic.png", "image.png", 20), true)
    private val file = PreparedClipboardItem(LocalAttachment(AttachmentKind.FILE, "synthetic.txt", "file.txt", 20), false)
    private fun state() = ChatSnapshot(enabled = true, token = token, selected = ChatItem(100, "synthetic"))

    @Test fun onlyTheMatchingNativeCompletionAdvancesEachSerialAttachment() {
        val queue = ClipboardSendQueue(token, listOf(photo, file), RichMessageText("caption"))
        val first = assertNotNull(queue.next(state()))
        assertNull(queue.next(state()))
        queue.observe(state().copy(acceptedSendId = 80, acceptedSendChatId = 100, sendAttemptRevision = 90))
        assertEquals(ClipboardQueuePhase.WAITING, queue.phase, "Another send's accepted counter cannot identify this attachment.")
        queue.complete(first.requestId + 1, ChatResult(Unit), state())
        assertEquals(0, queue.index)
        queue.complete(first.requestId, ChatResult(Unit), state())
        assertEquals(1, queue.index)
        val second = assertNotNull(queue.next(state()))
        queue.complete(first.requestId, ChatResult(Unit), state())
        assertEquals(ClipboardQueuePhase.WAITING, queue.phase, "A duplicate first callback cannot accept the second item.")
        queue.complete(second.requestId, ChatResult(Unit), state())
        assertEquals(ClipboardQueuePhase.COMPLETE, queue.phase)
        assertEquals(2, queue.index)
    }

    @Test fun busyOrRepeatedTypedRefusalsNeverLookLikeAcceptedAttachments() {
        for (error in listOf(ChatError.NOT_READY, ChatError.FORBIDDEN, ChatError.REQUEST_FAILED)) {
            val queue = ClipboardSendQueue(token, listOf(photo), RichMessageText(""))
            val snapshot = state().copy(sendError = error, sendErrorChatId = 100)
            val dispatch = assertNotNull(queue.next(snapshot))
            queue.complete(dispatch.requestId, ChatResult(error = error), snapshot)
            assertEquals(ClipboardQueuePhase.FAILED, queue.phase)
            assertEquals(error, queue.failure)
            assertEquals(0, queue.index)
        }
    }

    @Test fun sharedRichCaptionIsDispatchedOnlyWithTheFirstAcceptedFile() {
        val caption = RichMessageText("一份说明 😀", listOf(MessageEntity(0, 4, MessageEntityKind.BOLD)))
        val queue = ClipboardSendQueue(token, listOf(photo, file, photo), caption)
        val first = assertNotNull(queue.next(state()))
        assertEquals(caption, first.caption)
        queue.complete(first.requestId, ChatResult(Unit), state())
        val second = assertNotNull(queue.next(state()))
        assertEquals(RichMessageText(""), second.caption)
        queue.complete(second.requestId, ChatResult(Unit), state())
        assertEquals(RichMessageText(""), assertNotNull(queue.next(state())).caption)
    }

    @Test fun failedFirstFileRetainsItsCaptionForAnExplicitRetry() {
        val caption = RichMessageText("not accepted")
        val failed = ClipboardSendQueue(token, listOf(photo, file), caption)
        val dispatch = assertNotNull(failed.next(state()))
        failed.complete(dispatch.requestId, ChatResult(error = ChatError.REQUEST_FAILED), state())
        assertEquals(0, failed.index)
        val retry = ClipboardSendQueue(token, failed.remainingItems, failed.caption)
        assertEquals(caption, assertNotNull(retry.next(state())).caption)
    }

    @Test fun changingTheSelectionOrRevokingPermissionsStopsUnsentItems() {
        val queue = ClipboardSendQueue(token, listOf(file, photo), RichMessageText(""))
        val first = assertNotNull(queue.next(state()))
        queue.complete(first.requestId, ChatResult(Unit), state())
        assertNull(queue.next(state().copy(selected = ChatItem(100, "synthetic", canSendPhotos = false))))
        assertEquals(ClipboardQueuePhase.FAILED, queue.phase)
        assertEquals(ChatError.FORBIDDEN, queue.failure)

        val stale = ClipboardSendQueue(token, listOf(photo), RichMessageText(""))
        val dispatch = assertNotNull(stale.next(state()))
        stale.complete(dispatch.requestId, ChatResult(Unit), state().copy(token = ChatToken(1, 2, 101)))
        assertEquals(ClipboardQueuePhase.STALE, stale.phase)
        assertEquals(0, stale.index)
    }

    @Test fun enteringEditStopsAnUnsentBatchAndLateCompletionsCannotResumeIt() {
        val editing = state().copy(editTarget = ChatMessage(42, 100, text = "existing"))
        val waiting = ClipboardSendQueue(token, listOf(file, photo), RichMessageText(""))
        val first = assertNotNull(waiting.next(state()))
        waiting.complete(first.requestId, ChatResult(Unit), editing)
        assertEquals(ClipboardQueuePhase.STALE, waiting.phase)
        assertNull(waiting.next(state()), "Leaving edit must not restart a cancelled attachment batch.")
        assertEquals(0, waiting.index)

        val notStarted = ClipboardSendQueue(token, listOf(photo), RichMessageText(""))
        assertNull(notStarted.next(editing))
        assertEquals(ClipboardQueuePhase.STALE, notStarted.phase)
    }
}
