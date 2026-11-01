package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import com.badnng.moe.security.LocalLockWitness
import org.drinkless.tdlib.TdApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Real gate and chat controller; all native results and actor scheduling are synthetic. */
class LocalLockMessageTerminalTest {
    @Test
    fun acceptedFirstPartUnderLockStopsUntilExplicitMatchingRetry() {
        val f = Fixture()
        val original = RichMessageText("abcdefghij")
        f.controller.sendText(f.state.token, original, draftRevision = 7)
        assertEquals(1, f.session.calls.size)
        val first = f.session.calls.single().text
        f.lock()
        f.session.calls[0].native(TdApi.Ok())
        f.flush()
        assertEquals(1, f.session.calls.size, "A terminal callback cannot send the next part across a lock.")
        assertEquals(0L, f.state.acceptedSendId)
        assertFalse(f.state.sendPending)
        f.unlock()
        assertEquals(1, f.session.calls.size, "Unlock alone does not replay a remaining part.")

        f.controller.sendText(f.state.token, original, draftRevision = 7)
        assertEquals(2, f.session.calls.size)
        var next = 1
        while (next < f.session.calls.size) {
            f.session.calls[next].native(TdApi.Ok())
            f.flush()
            next++
        }
        assertTrue(f.session.calls.size > 1)
        assertEquals(original.text, f.session.calls.joinToString("") { it.text })
        assertEquals(1, f.session.calls.count { it.text == first }, "The accepted first part must not repeat.")
        assertEquals(1L, f.state.acceptedSendId)
        assertEquals(original.text, f.state.acceptedSendText)
    }

    @Test
    fun acceptedAttachmentReceiptAndDeliveryKeepItsLeaseAcrossLock() {
        for (failFirst in listOf(false, true)) {
            val f = Fixture()
            val owners = AttachmentSendLeases()
            var cleaned = 0
            val ui = ownedAttachmentLease { cleaned++ }
            f.controller.sendOwnedPhoto(f.state.token, "C:/synthetic/photo.png", RichMessageText("caption"),
                assertNotNull(owners.retain(ui)))
            ui.release()
            assertEquals(1, f.session.calls.size)
            f.lock()
            f.session.calls.single().native(TdApi.Ok())
            f.flush()
            assertEquals(0, cleaned, "Native acceptance still owns the file while delivery is pending.")
            val pending = ChatMessage(-1, 10, outgoing = true, kind = MessageKind.PHOTO, delivery = MessageDelivery.PENDING)
            if (failFirst) {
                f.controller.event(ChatEvent.SendFailed(pending.copy(delivery = MessageDelivery.FAILED, canRetry = true), -1))
                f.flush()
                assertEquals(0, cleaned, "A retryable failure retains the original file.")
                f.unlock()
                owners.releaseAll() // Synthetic confirmed engine closure, without an actual retry.
            } else {
                f.controller.event(ChatEvent.SendSucceeded(pending.copy(id = 100, delivery = MessageDelivery.SENT), -1))
                f.flush()
            }
            assertEquals(1, cleaned)
            owners.releaseAll()
            assertEquals(1, cleaned)
        }
    }

    private class Fixture {
        var witness = LocalLockWitness(1, false)
        val gate = TdUserInteractionGate().apply { bindLocalWitness { witness } }
        val queue = ArrayDeque<() -> Unit>()
        var state = ChatSnapshot()
        val session = SyntheticSession(gate)
        val controller = ChatController(1, session, enqueue = { work ->
            val captured = gate.callbackWitness()
            val email = gate.callbackEmailRevision()
            queue.addLast {
                val old = gate.pushCallbackWitness(captured)
                val oldEmail = gate.pushCallbackEmailRevision(email)
                try { work() } finally {
                    gate.pushCallbackWitness(old)
                    gate.pushCallbackEmailRevision(oldEmail)
                }
            }
        }, publish = { state = it }, isAccountAllowed = { gate.allowsOrdinary() && gate.callbackContextIsCurrent() })

        init {
            controller.start()
            event(ChatEvent.NewChat(ChatItem(10, "Synthetic", order = 100)))
            controller.select(state.token, 10)
            flush()
        }
        fun lock() { witness = LocalLockWitness(2, true); gate.localWitnessChanged() }
        fun unlock() { witness = LocalLockWitness(3, false); gate.localWitnessChanged() }
        fun flush() { while (queue.isNotEmpty()) queue.removeFirst().invoke() }
        private fun event(event: ChatEvent) { controller.event(event); flush() }
    }

    private class SyntheticSession(private val gate: TdUserInteractionGate) : TdChatSession {
        class Call(val text: String, val native: (TdApi.Object) -> Unit)
        val calls = mutableListOf<Call>()
        override val knownMessageTextLimit: Int = 4
        override fun loadChats(limit: Int, result: (ChatResult<ChatLoadResult>) -> Unit) = result(ChatResult(ChatLoadResult(true)))
        override fun history(chatId: Long, fromMessageId: Long, limit: Int, result: (ChatResult<List<ChatMessage>>) -> Unit) =
            result(ChatResult(emptyList()))
        override fun open(chatId: Long) = Unit
        override fun close(chatId: Long) = Unit
        override fun sendText(chatId: Long, text: String, result: (ChatResult<ChatMessage>) -> Unit) =
            sendRichText(chatId, RichMessageText(text), null, result)
        override fun sendRichText(chatId: Long, text: RichMessageText, reply: OutgoingReply?, result: (ChatResult<ChatMessage>) -> Unit) {
            send(chatId, text.text, MessageKind.TEXT, result)
        }
        override fun sendPhotoReply(chatId: Long, path: String, caption: RichMessageText, reply: OutgoingReply?,
            result: (ChatResult<ChatMessage>) -> Unit) = send(chatId, caption.text, MessageKind.PHOTO, result)
        override fun retry(chatId: Long, messageId: Long, result: (ChatResult<List<ChatMessage>>) -> Unit) = Unit
        override fun markRead(chatId: Long, messageIds: List<Long>, result: (ChatResult<Unit>) -> Unit) = Unit

        private fun send(chatId: Long, text: String, kind: MessageKind, result: (ChatResult<ChatMessage>) -> Unit) {
            val send = gate.guard { _: TdApi.Function<*>, callback: (TdApi.Object) -> Unit, _: () -> Unit ->
                calls += Call(text, callback)
            }
            send(TdApi.SendMessage(), {
                result(ChatResult(ChatMessage(-calls.size.toLong(), chatId, outgoing = true, text = text,
                    kind = kind, delivery = MessageDelivery.PENDING)))
            }, { result(ChatResult(error = ChatError.NOT_READY)) })
        }
    }
}
