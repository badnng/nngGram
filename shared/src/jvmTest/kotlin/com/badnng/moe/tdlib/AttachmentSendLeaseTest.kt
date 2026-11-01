package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import java.nio.file.Files
import kotlin.test.*

class AttachmentSendLeaseTest {
    @Test
    fun theConfirmationCompletionCanCloseItsVideoSelectionWhileNativeStillOwnsTheThumbnail() {
        val fixture = Fixture()
        val thumbnail = Files.createTempFile("synthetic-confirmation-thumbnail-", ".jpg")
        val ui = ownedAttachmentLease { Files.deleteIfExists(thumbnail) }
        val video = LocalAttachment(AttachmentKind.VIDEO, "C:/synthetic/clip.mp4", "clip.mp4", 100, 1, 64, 48,
            thumbnail = LocalAttachmentThumbnail(thumbnail.toString(), 64, 48, 50), lease = ui)
        val results = mutableListOf<ChatResult<Unit>>()
        try {
            fixture.controller.submitClipboardFile(fixture.state.token, video, RichMessageText("caption"),
                assertNotNull(fixture.leases.retain(ui))) { result ->
                results += result
                if (result.error == null) ui.release() // The actual confirmation closes on this completion.
            }
            val pending = ChatMessage(-10, 10, outgoing = true, kind = MessageKind.VIDEO, delivery = MessageDelivery.PENDING)
            fixture.session.sent.single()(ChatResult(pending)); fixture.flush()
            assertNull(results.single().error)
            assertTrue(Files.exists(thumbnail), "Acceptance closes UI ownership before TDLib finishes reading the local thumbnail")
            fixture.event(ChatEvent.SendFailed(pending.copy(delivery = MessageDelivery.FAILED, canRetry = true), -10))
            assertTrue(Files.exists(thumbnail))
            fixture.controller.retry(fixture.state.token, -10)
            fixture.session.retries.single()(ChatResult(listOf(pending.copy(id = -11)))); fixture.flush()
            fixture.event(ChatEvent.SendSucceeded(pending.copy(id = 100, delivery = MessageDelivery.SENT), -11))
            assertFalse(Files.exists(thumbnail))
            assertEquals(1, results.size, "Delivery/retry must not complete the original confirmation again")
        } finally {
            ui.release()
            fixture.leases.releaseAll()
            Files.deleteIfExists(thumbnail)
        }
    }

    @Test
    fun retryableFailureRetainsTheOriginalFileUntilTheRetriedMessageActuallySucceeds() {
        for (failureBeforeAcknowledgement in listOf(false, true)) {
            val fixture = Fixture()
            var cleaned = 0
            val ui = ownedAttachmentLease { cleaned++ }
            fixture.controller.sendOwnedPhoto(fixture.state.token, "C:/synthetic/photo.png", RichMessageText(""),
                assertNotNull(fixture.leases.retain(ui)))
            ui.release()
            val pending = ChatMessage(-10, 10, outgoing = true, kind = MessageKind.PHOTO, delivery = MessageDelivery.PENDING)
            if (!failureBeforeAcknowledgement) {
                fixture.session.sent.single()(ChatResult(pending))
                fixture.flush()
            }
            fixture.event(ChatEvent.SendFailed(pending.copy(delivery = MessageDelivery.FAILED, canRetry = true), -10))
            assertEquals(0, cleaned, "A retryable native failure can still depend on the original file.")
            fixture.controller.retry(fixture.state.token, -10)
            fixture.session.retries.single()(ChatResult(listOf(pending.copy(id = -11))))
            fixture.flush()
            if (failureBeforeAcknowledgement) {
                fixture.session.sent.single()(ChatResult(pending))
                fixture.flush()
            }
            assertEquals(0, cleaned)
            fixture.event(ChatEvent.SendSucceeded(pending.copy(id = 100, delivery = MessageDelivery.SENT), -11))
            assertEquals(1, cleaned)
        }
    }

    @Test
    fun duplicateAcknowledgementCannotReleaseAStillPendingNativeInput() {
        val fixture = Fixture()
        var cleaned = 0
        val ui = ownedAttachmentLease { cleaned++ }
        fixture.controller.sendOwnedPhoto(fixture.state.token, "C:/synthetic/photo.png", RichMessageText(""),
            assertNotNull(fixture.leases.retain(ui)))
        ui.release()
        val pending = ChatMessage(-10, 10, outgoing = true, delivery = MessageDelivery.PENDING)
        repeat(2) { fixture.session.sent.single()(ChatResult(pending)); fixture.flush() }
        assertEquals(0, cleaned)
        fixture.event(ChatEvent.SendSucceeded(pending.copy(id = 100, delivery = MessageDelivery.SENT), -10))
        assertEquals(1, cleaned)
    }

    @Test
    fun clipboardRequestsReceiveOnlyTheirOwnNativeAcknowledgementOrTypedRefusal() {
        val fixture = Fixture()
        val first = mutableListOf<ChatResult<Unit>>()
        val busy = mutableListOf<ChatResult<Unit>>()
        fixture.controller.submitClipboardPhoto(fixture.state.token, "C:/synthetic/photo.png", RichMessageText(""), null, first::add)
        fixture.controller.submitClipboardFile(fixture.state.token,
            LocalAttachment(AttachmentKind.FILE, "C:/synthetic/file.txt", "file.txt", 8), RichMessageText(""), busy::add)
        assertTrue(first.isEmpty())
        assertEquals(ChatError.NOT_READY, busy.single().error)
        fixture.session.sent.single()(ChatResult(error = ChatError.REQUEST_FAILED))
        fixture.flush()
        assertEquals(ChatError.REQUEST_FAILED, first.single().error)
        assertEquals(1, busy.size)
        assertEquals(0L, fixture.state.acceptedSendId)

        val stale = mutableListOf<ChatResult<Unit>>()
        fixture.controller.submitClipboardPhoto(fixture.state.token.copy(selection = -1), "C:/synthetic/photo.png",
            RichMessageText(""), null, stale::add)
        assertEquals(ChatError.NOT_READY, stale.single().error)
        assertEquals(1, fixture.session.sent.size)
    }

    @Test
    fun oneHandleCannotReleaseAnotherAndNativePendingOwnershipSurvivesUiClosure() {
        var cleaned = 0
        val ui = ownedAttachmentLease { cleaned++ }
        val engine = AttachmentSendLeases()
        val pending = assertNotNull(engine.retain(ui))
        ui.release()
        ui.release()
        assertNull(ui.retain())
        assertEquals(0, cleaned)
        pending.release()
        pending.release()
        assertEquals(1, cleaned)
        engine.releaseAll()
        assertEquals(1, cleaned)
    }

    @Test
    fun nativePendingPhotoIsReleasedByItsTerminalUpdateEvenAfterChatSwitch() {
        val fixture = Fixture()
        var cleaned = 0
        val ui = ownedAttachmentLease { cleaned++ }
        fixture.controller.sendOwnedPhoto(fixture.state.token, "C:/synthetic/photo.png", RichMessageText(""),
            assertNotNull(fixture.leases.retain(ui)))
        ui.release()
        fixture.session.sent.single()(ChatResult(ChatMessage(-10, 10, outgoing = true, delivery = MessageDelivery.PENDING)))
        fixture.flush()
        assertEquals(0, cleaned)
        fixture.controller.select(fixture.state.token, 11)
        fixture.event(ChatEvent.SendSucceeded(ChatMessage(100, 10, outgoing = true), -10))
        assertEquals(1, cleaned)
    }

    @Test
    fun terminalUpdateBeforeSendAcknowledgementStillReleasesTheInputExactlyOnce() {
        val fixture = Fixture()
        var cleaned = 0
        val ui = ownedAttachmentLease { cleaned++ }
        fixture.controller.sendOwnedPhoto(fixture.state.token, "C:/synthetic/photo.png", RichMessageText(""),
            assertNotNull(fixture.leases.retain(ui)))
        ui.release()
        fixture.event(ChatEvent.SendSucceeded(ChatMessage(100, 10, outgoing = true), -10))
        fixture.session.sent.single()(ChatResult(ChatMessage(-10, 10, outgoing = true, delivery = MessageDelivery.PENDING)))
        fixture.flush()
        assertEquals(1, cleaned)
        fixture.leases.releaseAll()
        assertEquals(1, cleaned)
    }

    @Test
    fun aRetiredControllerKeepsPendingInputUntilConfirmedNativeClosure() {
        val fixture = Fixture()
        var cleaned = 0
        val ui = ownedAttachmentLease { cleaned++ }
        fixture.controller.sendOwnedPhoto(fixture.state.token, "C:/synthetic/photo.png", RichMessageText(""),
            assertNotNull(fixture.leases.retain(ui)))
        ui.release()
        fixture.controller.deactivate()
        assertEquals(0, cleaned, "Deactivation and a close timeout do not prove that TDLib stopped reading files.")
        fixture.leases.releaseAll()
        assertEquals(1, cleaned)
    }

    @Test
    fun repeatedTypedVoiceRefusalsAdvanceTheAttemptAndReleaseEachOwnedHandle() {
        val fixture = Fixture(voiceAllowed = false)
        val initial = fixture.state.sendAttemptRevision
        repeat(2) { attempt ->
            var cleaned = false
            val ui = ownedAttachmentLease { cleaned = true }
            val draft = VoiceNoteDraft("C:/synthetic/voice.ogg", 1024, 1, ByteArray(63), ui)
            fixture.controller.sendVoiceNote(fixture.state.token, draft, assertNotNull(fixture.leases.retain(ui)))
            ui.release()
            assertTrue(cleaned)
            assertEquals(ChatError.FORBIDDEN, fixture.state.sendError)
            assertEquals(initial + attempt + 1, fixture.state.sendAttemptRevision)
        }
        assertTrue(fixture.session.sent.isEmpty())
    }

    @Test
    fun aPreparedVideoThumbnailSurvivesUiClosureFailureRetryAndLateAcceptanceUntilNativeDelivery() {
        for (failureBeforeAcknowledgement in listOf(false, true)) {
            val fixture = Fixture()
            var cleaned = 0
            val ui = ownedAttachmentLease { cleaned++ }
            val video = LocalAttachment(AttachmentKind.VIDEO, "C:/synthetic/clip.mp4", "clip.mp4", 100, 1, 64, 48,
                thumbnail = LocalAttachmentThumbnail("C:/synthetic/thumb.jpg", 64, 48, 50), lease = ui)
            fixture.controller.sendOwnedAttachment(fixture.state.token, video, RichMessageText("caption"),
                assertNotNull(fixture.leases.retain(ui)))
            ui.release()
            val pending = ChatMessage(-10, 10, outgoing = true, kind = MessageKind.VIDEO, delivery = MessageDelivery.PENDING)
            if (!failureBeforeAcknowledgement) {
                fixture.session.sent.single()(ChatResult(pending)); fixture.flush()
            }
            fixture.event(ChatEvent.SendFailed(pending.copy(delivery = MessageDelivery.FAILED, canRetry = true), -10))
            assertEquals(0, cleaned)
            fixture.controller.retry(fixture.state.token, -10)
            fixture.session.retries.single()(ChatResult(listOf(pending.copy(id = -11))))
            fixture.flush()
            if (failureBeforeAcknowledgement) {
                fixture.session.sent.single()(ChatResult(pending)); fixture.flush()
            }
            assertEquals(0, cleaned)
            fixture.controller.select(fixture.state.token, 11)
            fixture.event(ChatEvent.SendSucceeded(pending.copy(id = 100, delivery = MessageDelivery.SENT), -11))
            assertEquals(1, cleaned)
            fixture.leases.releaseAll()
            assertEquals(1, cleaned)
        }
    }

    @Test
    fun aVideoRefusalReleasesOnlyTheNativeHandleAndLeavesTheConfirmationSelectionReusable() {
        val fixture = Fixture()
        var cleaned = 0
        val ui = ownedAttachmentLease { cleaned++ }
        val video = LocalAttachment(AttachmentKind.VIDEO, "C:/synthetic/clip.mp4", "clip.mp4", 100, 1, 64, 48, lease = ui)
        fixture.event(ChatEvent.Permissions(10, canSend = true, canSendVideos = false))
        fixture.controller.sendOwnedAttachment(fixture.state.token, video, RichMessageText("caption"),
            assertNotNull(fixture.leases.retain(ui)))
        assertEquals(ChatError.FORBIDDEN, fixture.state.sendError)
        assertEquals(0, cleaned, "The failed native attempt cannot dispose the open confirmation's selection")
        fixture.event(ChatEvent.Permissions(10, canSend = true, canSendVideos = true))
        fixture.controller.sendOwnedAttachment(fixture.state.token, video, RichMessageText("caption"),
            assertNotNull(fixture.leases.retain(ui)))
        fixture.session.sent.single()(ChatResult(error = ChatError.REQUEST_FAILED)); fixture.flush()
        assertEquals(0, cleaned)
        ui.release()
        assertEquals(1, cleaned)
    }

    private class Fixture(voiceAllowed: Boolean = true) {
        var state = ChatSnapshot()
        val session = FakeSession()
        val queue = ArrayDeque<() -> Unit>()
        val leases = AttachmentSendLeases()
        val controller = ChatController(1, session, queue::addLast, { state = it })
        init {
            controller.start()
            event(ChatEvent.NewChat(ChatItem(10, "合成会话", order = 100, canSendVoiceNotes = voiceAllowed)))
            event(ChatEvent.NewChat(ChatItem(11, "其他合成会话", order = 90)))
            controller.select(state.token, 10)
            flush()
        }
        fun flush() { while (queue.isNotEmpty()) queue.removeFirst().invoke() }
        fun event(event: ChatEvent) { controller.event(event); flush() }
    }
    private class FakeSession : TdChatSession {
        val sent = mutableListOf<(ChatResult<ChatMessage>) -> Unit>()
        val retries = mutableListOf<(ChatResult<List<ChatMessage>>) -> Unit>()
        override fun loadChats(limit: Int, result: (ChatResult<ChatLoadResult>) -> Unit) = result(ChatResult(ChatLoadResult(true)))
        override fun history(chatId: Long, fromMessageId: Long, limit: Int, result: (ChatResult<List<ChatMessage>>) -> Unit) = result(ChatResult(emptyList()))
        override fun open(chatId: Long) = Unit
        override fun close(chatId: Long) = Unit
        override fun sendText(chatId: Long, text: String, result: (ChatResult<ChatMessage>) -> Unit) { sent += result }
        override fun sendPhotoReply(chatId: Long, path: String, caption: RichMessageText, reply: OutgoingReply?, result: (ChatResult<ChatMessage>) -> Unit) { sent += result }
        override fun sendVoiceNoteReply(chatId: Long, draft: VoiceNoteDraft, reply: OutgoingReply?, result: (ChatResult<ChatMessage>) -> Unit) { sent += result }
        override fun sendAttachmentReply(chatId: Long, attachment: LocalAttachment, caption: RichMessageText, reply: OutgoingReply?, result: (ChatResult<ChatMessage>) -> Unit) { sent += result }
        override fun retry(chatId: Long, messageId: Long, result: (ChatResult<List<ChatMessage>>) -> Unit) { retries += result }
        override fun markRead(chatId: Long, messageIds: List<Long>, result: (ChatResult<Unit>) -> Unit) = Unit
    }
}
