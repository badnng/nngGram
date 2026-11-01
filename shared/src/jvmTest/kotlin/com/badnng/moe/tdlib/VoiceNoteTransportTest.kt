package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import org.drinkless.tdlib.TdApi
import kotlin.test.*

class VoiceNoteTransportTest {
    @Test
    fun voiceUsesThePinnedNativeInputWithActualWaveformReplyAndNoPaidSend() {
        val requests = mutableListOf<TdApi.Function<*>>()
        val session = OfficialTdChatSession { request, _, _ -> requests += request }
        val lease = ownedAttachmentLease {}
        try {
            val waveform = ByteArray(63) { it.toByte() }
            val draft = VoiceNoteDraft("C:/synthetic/voice.ogg", 4096, 3, waveform, lease)
            session.sendVoiceNoteReply(10, draft, OutgoingReply(20)) {}
            val send = assertIs<TdApi.SendMessage>(requests.single())
            val input = assertIs<TdApi.InputMessageVoiceNote>(send.inputMessageContent)
            assertEquals(draft.path, assertIs<TdApi.InputFileLocal>(input.voiceNote.voiceNote).path)
            assertEquals(3, input.voiceNote.duration)
            assertContentEquals(waveform, input.voiceNote.waveform)
            assertNotSame(waveform, input.voiceNote.waveform)
            assertEquals(20L, assertIs<TdApi.InputMessageReplyToMessage>(send.replyTo).messageId)
            assertNull(input.caption)
            assertNull(input.selfDestructType)
            assertEquals(0L, send.options.paidMessageStarCount)
            assertFalse(send.options.allowPaidBroadcast)
        } finally { lease.release() }
    }

    @Test
    fun nativeVoicePermissionRemainsIndependentFromTextAndChangesWithGroupRestrictions() {
        val events = mutableListOf<ChatEvent>()
        val mapper = TdChatUpdateMapper(events::add)
        mapper.accept(TdApi.UpdateBasicGroup(TdApi.BasicGroup(1, 5, TdApi.ChatMemberStatusMember(0), true, 0)))
        mapper.accept(TdApi.UpdateNewChat(TdApi.Chat().apply {
            id = 10; type = TdApi.ChatTypeBasicGroup(1)
            permissions = TdApi.ChatPermissions().apply { canSendVoiceNotes = true; canSendBasicMessages = false }
        }))
        val chat = assertIs<ChatEvent.NewChat>(events.last()).chat
        assertTrue(chat.canSendVoiceNotes)
        assertFalse(chat.canSend)
        mapper.accept(TdApi.UpdateChatPermissions(10, TdApi.ChatPermissions().apply {
            canSendVoiceNotes = false; canSendBasicMessages = true
        }))
        val permission = assertIs<ChatEvent.Permissions>(events.last())
        assertFalse(permission.canSendVoiceNotes)
        assertTrue(permission.canSend)
    }
}
