package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import org.drinkless.tdlib.TdApi
import kotlin.test.*

class ReportSpamTransportTest {
    @Test fun nativeActionBarsAndMembershipAreMappedWithoutGrantingGeneralReportPermission() {
        val events = mutableListOf<ChatEvent>()
        val mapper = TdChatUpdateMapper(events::add)
        mapper.accept(TdApi.UpdateSupergroup(TdApi.Supergroup().apply {
            id = 11; isChannel = true; status = TdApi.ChatMemberStatusMember()
        }))
        mapper.accept(TdApi.UpdateNewChat(TdApi.Chat().apply {
            id = -10; type = TdApi.ChatTypeSupergroup(11, true); actionBar = TdApi.ChatActionBarReportSpam(true)
            canBeReported = false
        }))
        val channel = events.filterIsInstance<ChatEvent.NewChat>().single().chat
        assertTrue(channel.canLeave)
        assertFalse(channel.isJoinedGroup, "A channel is not a custom emoji source group")
        assertFalse(channel.canBeReported)
        assertEquals(ChatActionBar(ChatActionBarKind.REPORT_SPAM, true), channel.actionBar)
        mapper.accept(TdApi.UpdateChatActionBar(-10, TdApi.ChatActionBarInviteMembers()))
        assertEquals(ChatEvent.ActionBar(-10, ChatActionBar(ChatActionBarKind.INVITE_MEMBERS)), events.last())
        mapper.accept(TdApi.UpdateChatActionBar(-10, null))
        assertEquals(ChatEvent.ActionBar(-10, null), events.last())
        mapper.accept(TdApi.UpdateSupergroup(TdApi.Supergroup().apply {
            id = 11; isChannel = true; status = TdApi.ChatMemberStatusLeft()
        }))
        assertEquals(ChatEvent.LeaveCapability(-10, false), events.filterIsInstance<ChatEvent.LeaveCapability>().last())
    }

    @Test fun capabilityReadsTheActualChatAndBasicGroupStatusIncludingSelfOnlyHistoryRemoval() {
        val f = Fixture()
        var result: ChatResult<ChatReportSpamCapability>? = null
        f.session.reportSpamCapability(-10) { result = it }
        assertEquals(-10L, assertIs<TdApi.GetChat>(f.last.command).chatId)
        f.last.result(TdApi.Chat().apply {
            id = -10; type = TdApi.ChatTypeBasicGroup(11); actionBar = TdApi.ChatActionBarReportSpam(false)
            canBeDeletedOnlyForSelf = true
        })
        assertEquals(11L, assertIs<TdApi.GetBasicGroup>(f.last.command).basicGroupId)
        f.last.result(TdApi.BasicGroup().apply { id = 11; isActive = true; status = TdApi.ChatMemberStatusMember() })
        val capability = assertNotNull(result?.value)
        assertTrue(capability.canLeave)
        assertTrue(capability.needsHistoryRemoval)
        assertTrue(capability.canDeleteHistory)
        assertEquals(ChatActionBarKind.REPORT_SPAM, capability.actionBar?.kind)
    }

    @Test fun wrongNativeIdentitiesAndPrivateChatsCannotBecomeLeaveCapabilities() {
        val f = Fixture()
        val results = mutableListOf<ChatResult<ChatReportSpamCapability>>()
        f.session.reportSpamCapability(-10, results::add)
        f.last.result(TdApi.Chat().apply { id = -20; type = TdApi.ChatTypeSupergroup(11, false) })
        assertNotNull(results.last().error)
        assertEquals(1, f.requests.size)
        f.session.reportSpamCapability(-10, results::add)
        f.last.result(TdApi.Chat().apply { id = -10; type = TdApi.ChatTypeSupergroup(11, false) })
        f.last.result(TdApi.Supergroup().apply { id = 11; isChannel = true; status = TdApi.ChatMemberStatusMember() })
        assertNotNull(results.last().error)
        f.session.reportSpamCapability(10, results::add)
        f.last.result(TdApi.Chat().apply { id = 10; type = TdApi.ChatTypePrivate(90); actionBar = TdApi.ChatActionBarReportSpam(false) })
        assertFalse(assertNotNull(results.last().value).canLeave)
        assertFalse(assertNotNull(results.last().value).needsHistoryRemoval)
        assertIs<TdApi.GetChat>(f.last.command)
    }

    @Test fun emptyActionBarReportAcceptsOnlyAnExplicitNativeOkAndNeverInventsModernOptions() {
        val f = Fixture()
        val results = mutableListOf<ChatResult<Unit>>()
        f.session.reportSpam(-10, results::add)
        val command = assertIs<TdApi.ReportChat>(f.last.command)
        assertEquals(-10L, command.chatId)
        assertContentEquals(byteArrayOf(), command.optionId)
        assertContentEquals(longArrayOf(), command.messageIds)
        assertEquals("", command.text)
        val first = f.last
        first.result(TdApi.ReportChatResultOk())
        first.result(TdApi.Error(500, "synthetic"))
        assertEquals(Unit, results.single().value)
        for (response in listOf(TdApi.ReportChatResultOptionRequired(), TdApi.ReportChatResultTextRequired(),
            TdApi.ReportChatResultMessagesRequired())) {
            f.session.reportSpam(-10, results::add)
            f.last.result(response)
            assertEquals(ChatError.UNSUPPORTED, results.last().error)
        }
        assertEquals(4, f.requests.size)
        assertTrue(f.requests.all { it.command is TdApi.ReportChat })
    }

    @Test fun dismissLeaveAndBasicCleanupHaveSeparateNativeCommandsAndNeverRevokeOthersHistory() {
        val f = Fixture()
        f.session.removeChatActionBar(-10) { }
        assertEquals(-10L, assertIs<TdApi.RemoveChatActionBar>(f.last.command).chatId)
        f.session.leaveChat(-10) { }
        assertEquals(-10L, assertIs<TdApi.LeaveChat>(f.last.command).chatId)
        f.session.clearReportedBasicGroupHistory(-10) { }
        val clear = assertIs<TdApi.DeleteChatHistory>(f.last.command)
        assertEquals(-10L, clear.chatId)
        assertTrue(clear.removeFromChatList)
        assertFalse(clear.revoke)
        assertEquals(3, f.requests.size)
    }

    private class Fixture {
        val requests = mutableListOf<Request>()
        val session = OfficialTdChatSession { command, result, failed -> requests += Request(command, result, failed) }
        val last get() = requests.last()
    }
    private data class Request(val command: TdApi.Function<*>, val result: (TdApi.Object) -> Unit, val failed: () -> Unit)
}
