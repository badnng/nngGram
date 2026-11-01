package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import com.badnng.moe.security.LocalLockWitness
import org.drinkless.tdlib.TdApi
import kotlin.test.*

class OfficialTdNotificationExceptionsHotLockTest {
    @Test fun finalSuccessAndErrorAreRealReceiptsAfterPageLossWithoutPostWriteReads() {
        for (accepted in listOf(true, false)) {
            val f = Fixture(); val results = mutableListOf<NotificationExceptionWriteResult>(); val original = rich()
            f.api.save(NotificationExceptionEdit(peer(original), original.copy(showPreview = true),
                setOf(NotificationExceptionField.PREVIEW)), f::active, results::add)
            f.readSteps(original); val final = f.last; val count = f.requests.size; f.enabled = false
            final.reply(if (accepted) TdApi.Ok() else TdApi.Error(403, "synthetic")); final.reply(TdApi.Ok())
            assertEquals(1, results.size)
            assertEquals(if (accepted) NotificationSettingsWriteReceipt.ACCEPTED else NotificationSettingsWriteReceipt.REJECTED, results.single().receipt)
            assertEquals(if (accepted) SettingsError.NOT_READY else SettingsError.FORBIDDEN, results.single().error)
            assertEquals(count, f.requests.size)
        }
    }

    @Test fun unknownSendKeepsTheChatLaneUntilItsActualTerminalAndDoesNotDuplicateTheSet() {
        val f = Fixture(); val results = mutableListOf<NotificationExceptionWriteResult>(); val original = rich()
        f.api.save(NotificationExceptionEdit(peer(original), original.copy(showPreview = true),
            setOf(NotificationExceptionField.PREVIEW)), f::active, results::add)
        f.readSteps(original); val final = f.last; val count = f.requests.size
        final.failed()
        assertEquals(NotificationSettingsWriteReceipt.INDETERMINATE, results.single().receipt)
        val reads = mutableListOf<SettingsResult<NotificationExceptionPeer>>()
        f.api.read(NotificationScope.PRIVATE_CHATS, 10, f::active, reads::add)
        // A standalone context read can start; the per-chat lane remains owned until final.
        f.context(); assertEquals(count + 3, f.requests.size)
        final.reply(TdApi.Error(403, "synthetic"))
        assertEquals(NotificationSettingsWriteReceipt.REJECTED, results.last().receipt)
        assertEquals(1, f.requests.count { it.command is TdApi.SetChatNotificationSettings })
        assertIs<TdApi.GetChat>(f.last.command)
    }

    @Test fun aRejectedFinalDispatchIsNotAnUnknownOrAcceptedWrite() {
        val f = Fixture(); val results = mutableListOf<NotificationExceptionWriteResult>(); val original = rich()
        f.api.save(NotificationExceptionEdit(peer(original), original.copy(showPreview = true),
            setOf(NotificationExceptionField.PREVIEW)), f::active, results::add)
        f.denyFinal = true; f.readSteps(original)
        assertEquals(NotificationSettingsWriteReceipt.NOT_DISPATCHED, results.single().receipt)
        // The real blocked gate proves non-dispatch but supplies no callback error.
        // This page remains active, so the adapter retains its REQUEST_FAILED fallback.
        assertEquals(SettingsError.REQUEST_FAILED, results.single().error)
        assertTrue(f.requests.none { it.command is TdApi.SetChatNotificationSettings })
    }

    @Test fun anUnscopedReceiptCannotProveThatAnEnteredNativeWriteWasNotDispatched() {
        val f = Fixture(); val results = mutableListOf<NotificationExceptionWriteResult>(); val original = rich()
        f.api.save(NotificationExceptionEdit(peer(original), original.copy(showPreview = true),
            setOf(NotificationExceptionField.PREVIEW)), f::active, results::add)
        f.readSteps(original)
        val final = f.last
        assertIs<TdApi.SetChatNotificationSettings>(final.command)
        SettingsSpecialMutationContext.withReceipt(
            SettingsSpecialWriteReceipt(SettingsSpecialWriteOutcome.NOT_DISPATCHED, SettingsError.NOT_READY), final.failed)
        assertEquals(NotificationSettingsWriteReceipt.INDETERMINATE, results.single().receipt)
        assertEquals(SettingsError.REQUEST_FAILED, results.single().error)
        assertEquals(1, f.requests.count { it.command is TdApi.SetChatNotificationSettings })
        final.reply(TdApi.Error(403, "synthetic"))
        assertEquals(NotificationSettingsWriteReceipt.REJECTED, results.last().receipt)
        assertEquals(SettingsError.FORBIDDEN, results.last().error)
    }

    private data class Quad(val seconds: Int, val sampled: Long, val fresh: Int, val succeeds: Boolean)
    private class Pending(val command: TdApi.Function<*>, val reply: (TdApi.Object) -> Unit, val failed: () -> Unit)
    private class Fixture {
        var enabled = true
        val requests = mutableListOf<Pending>()
        var denyFinal = false
        // Only the real final gate can prove that this exact marked command never
        // entered raw send; an ordinary callback receipt is not dispatch evidence.
        private val deniedFinalSend = TdUserInteractionGate().apply {
            bindLocalWitness { LocalLockWitness(1, true) }
        }.guard { command, reply, failed -> requests += Pending(command, reply, failed) }
        val send: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit = { command, reply, failed ->
            if (denyFinal && command is TdApi.SetChatNotificationSettings) deniedFinalSend(command, reply, failed)
            else requests += Pending(command, reply, failed)
        }
        val coordinator = NativeChatNotificationMutations(send)
        val api = OfficialTdNotificationExceptions(coordinator, send)
        val last get() = requests.last()
        fun active() = enabled
        fun context() {
            assertIs<TdApi.GetMe>(last.command); last.reply(user(1))
            assertEquals("replies_bot_chat_id", assertIs<TdApi.GetOption>(last.command).name); last.reply(TdApi.OptionValueInteger(900))
            assertEquals("verification_codes_bot_chat_id", assertIs<TdApi.GetOption>(last.command).name); last.reply(TdApi.OptionValueInteger(901))
        }
        fun readSteps(values: ChatNotificationValues, scope: NotificationScope = NotificationScope.PRIVATE_CHATS) {
            context(); assertEquals(10L, assertIs<TdApi.GetChat>(last.command).chatId)
            last.reply(chat(10, scope, values))
            when (scope) {
                NotificationScope.PRIVATE_CHATS -> { assertEquals(71L, assertIs<TdApi.GetUser>(last.command).userId); last.reply(user(71)) }
                NotificationScope.GROUP_CHATS -> { assertEquals(72L, assertIs<TdApi.GetBasicGroup>(last.command).basicGroupId)
                    last.reply(TdApi.BasicGroup().apply { id = 72; isActive = true; status = TdApi.ChatMemberStatusMember() }) }
                NotificationScope.CHANNEL_CHATS -> { assertEquals(73L, assertIs<TdApi.GetSupergroup>(last.command).supergroupId)
                    last.reply(TdApi.Supergroup().apply { id = 73; isChannel = true; status = TdApi.ChatMemberStatusMember() }) }
            }
        }
        fun assertMetadataOnly() { assertTrue(requests.all { it.command is TdApi.GetMe || it.command is TdApi.GetOption ||
            it.command is TdApi.GetChat || it.command is TdApi.GetUser || it.command is TdApi.GetBasicGroup || it.command is TdApi.GetSupergroup ||
            it.command is TdApi.GetChats || it.command is TdApi.SearchChats || it.command is TdApi.SearchChatsOnServer ||
            it.command is TdApi.GetChatNotificationSettingsExceptions || it.command is TdApi.SetChatNotificationSettings }) }
    }
    private companion object {
        fun user(id: Long) = TdApi.User().apply { this.id = id; type = TdApi.UserTypeRegular() }
        fun chat(id: Long, scope: NotificationScope, values: ChatNotificationValues) = TdApi.Chat().apply {
            this.id = id; title = "synthetic peer"; notificationSettings = values.native()
            type = when (scope) { NotificationScope.PRIVATE_CHATS -> TdApi.ChatTypePrivate(71)
                NotificationScope.GROUP_CHATS -> TdApi.ChatTypeBasicGroup(72)
                NotificationScope.CHANNEL_CHATS -> TdApi.ChatTypeSupergroup(73, true) }
        }
        fun peer(values: ChatNotificationValues, sampled: Long = System.currentTimeMillis()) = NotificationExceptionPeer(10,
            NotificationScope.PRIVATE_CHATS, "synthetic peer", values, sampled)
        fun rich() = ChatNotificationValues(false, 60, false, -2, false, false, false, true, false, 88,
            false, false, false, true, false, true)
    }
}
