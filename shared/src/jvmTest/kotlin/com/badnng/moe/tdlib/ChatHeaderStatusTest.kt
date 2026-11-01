package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import org.drinkless.tdlib.TdApi
import kotlin.test.*

class ChatHeaderStatusTest {
    @Test
    fun nativeGroupCountsKeepBasicAndSupergroupIdentitiesSeparateBeforeAndAfterChatsArrive() {
        val fixture = Fixture()
        fixture.basicGroup(30, 12)
        fixture.supergroup(30, 42)
        fixture.chat(10, TdApi.ChatTypeBasicGroup(30))
        fixture.chat(11, TdApi.ChatTypeSupergroup(30, false))
        fixture.select(10)
        assertEquals(30L, fixture.state.selected?.basicGroupId)
        assertNull(fixture.state.selected?.supergroupId)
        assertEquals(12, fixture.state.headerStatus.memberCount)

        fixture.basicGroup(30, 15)
        fixture.supergroup(30, 46)
        assertEquals(15, fixture.state.headerStatus.memberCount)
        fixture.select(11)
        assertEquals(30L, fixture.state.selected?.supergroupId)
        assertNull(fixture.state.selected?.basicGroupId)
        assertFalse(fixture.state.selected!!.isChannel)
        assertEquals(46, fixture.state.headerStatus.memberCount)
    }

    @Test
    fun unavailableCountsRemainUnknownAndFullInformationUsesOnlyItsRealCount() {
        val fixture = Fixture()
        fixture.chat(10, TdApi.ChatTypeSupergroup(30, true))
        fixture.select(10)
        assertTrue(fixture.state.selected!!.isChannel)
        assertNull(fixture.state.headerStatus.memberCount)
        fixture.supergroup(30, 0)
        assertNull(fixture.state.headerStatus.memberCount)
        val requests = fixture.requests.size
        fixture.mapper.accept(TdApi.UpdateSupergroupFullInfo(30, TdApi.SupergroupFullInfo().apply { memberCount = 234 }))
        assertEquals(234, fixture.state.headerStatus.memberCount)
        fixture.mapper.accept(TdApi.UpdateSupergroupFullInfo(30, TdApi.SupergroupFullInfo().apply { memberCount = 0 }))
        assertNull(fixture.state.headerStatus.memberCount)
        assertEquals(requests, fixture.requests.size)
    }

    @Test
    fun onlineCountsBelongOnlyToTheOpenedSelectionAndZeroMeansUnknown() {
        val fixture = Fixture()
        fixture.chat(10)
        fixture.chat(11)
        fixture.select(10)
        fixture.mapper.accept(TdApi.UpdateChatOnlineMemberCount(10, 4))
        assertEquals(4, fixture.state.headerStatus.onlineCount)
        fixture.mapper.accept(TdApi.UpdateChatOnlineMemberCount(11, 99))
        assertEquals(4, fixture.state.headerStatus.onlineCount)
        fixture.mapper.accept(TdApi.UpdateChatOnlineMemberCount(10, 0))
        assertNull(fixture.state.headerStatus.onlineCount)
        fixture.mapper.accept(TdApi.UpdateChatOnlineMemberCount(10, 3))
        fixture.select(11)
        assertNull(fixture.state.headerStatus.onlineCount)
        fixture.mapper.accept(TdApi.UpdateChatOnlineMemberCount(10, 6))
        assertNull(fixture.state.headerStatus.onlineCount)
    }

    @Test
    fun typingUploadsAndStickerChoicesUseActualNativeActionsAndValidatedProgress() {
        val fixture = selectedFixture()
        val cases = listOf(
            TdApi.ChatActionTyping() to ChatActivityKind.TYPING,
            TdApi.ChatActionUploadingPhoto(25) to ChatActivityKind.UPLOADING_PHOTO,
            TdApi.ChatActionUploadingVideo(50) to ChatActivityKind.UPLOADING_VIDEO,
            TdApi.ChatActionUploadingDocument(75) to ChatActivityKind.UPLOADING_DOCUMENT,
            TdApi.ChatActionChoosingSticker() to ChatActivityKind.CHOOSING_STICKER,
        )
        val requests = fixture.requests.size
        cases.forEach { (native, expected) ->
            fixture.action(10, 17, native)
            val activity = assertNotNull(fixture.state.headerStatus.activity)
            assertEquals(expected, activity.kind)
            assertEquals(17L, activity.senderId)
            assertFalse(activity.senderIsChat)
        }
        fixture.action(10, 17, TdApi.ChatActionUploadingPhoto(101))
        assertNull(fixture.state.headerStatus.activity?.progress)
        fixture.action(10, 17, TdApi.ChatActionUploadingVideo(50))
        assertEquals(50, fixture.state.headerStatus.activity?.progress)
        assertEquals(requests, fixture.requests.size)
    }

    @Test
    fun cancellationAndSenderIdentitiesDoNotClearAnotherParticipant() {
        val fixture = selectedFixture()
        fixture.action(10, 17, TdApi.ChatActionTyping())
        fixture.action(10, 17, TdApi.ChatActionChoosingSticker(), isChat = true)
        fixture.action(10, 17, TdApi.ChatActionCancel())
        assertEquals(ChatActivityKind.CHOOSING_STICKER, fixture.state.headerStatus.activity?.kind)
        assertTrue(fixture.state.headerStatus.activity!!.senderIsChat)
        fixture.action(10, 17, TdApi.ChatActionCancel(), isChat = true)
        assertNull(fixture.state.headerStatus.activity)
        fixture.advance(10_000)
        assertNull(fixture.state.headerStatus.activity)
    }

    @Test
    fun refreshedActivitySurvivesTheOldDeadlineAndExpiresWithoutFurtherNativeUpdates() {
        val fixture = selectedFixture()
        fixture.action(10, 17, TdApi.ChatActionTyping())
        fixture.advance(5_000)
        fixture.action(10, 17, TdApi.ChatActionUploadingPhoto(10))
        fixture.advance(1_000)
        assertEquals(ChatActivityKind.UPLOADING_PHOTO, fixture.state.headerStatus.activity?.kind)
        fixture.advance(4_999)
        assertNotNull(fixture.state.headerStatus.activity)
        fixture.advance(1)
        assertNull(fixture.state.headerStatus.activity)
    }

    @Test
    fun actionsAndExpiryCallbacksCannotCrossAConversationSelection() {
        val fixture = selectedFixture()
        fixture.chat(11)
        fixture.action(10, 17, TdApi.ChatActionTyping())
        fixture.advance(2_000)
        fixture.select(11)
        assertNull(fixture.state.headerStatus.activity)
        fixture.action(10, 17, TdApi.ChatActionUploadingPhoto(20))
        assertNull(fixture.state.headerStatus.activity)
        fixture.action(11, 18, TdApi.ChatActionChoosingSticker())
        fixture.advance(4_000)
        assertEquals(18L, fixture.state.headerStatus.activity?.senderId)
        assertEquals(ChatActivityKind.CHOOSING_STICKER, fixture.state.headerStatus.activity?.kind)
    }

    @Test
    fun retiredControllerTimersAndNativeUpdatesCannotPublishIntoAnotherGeneration() {
        var shared = ChatSnapshot()
        val old = Fixture(generation = 1) { shared = it }
        old.chat(10)
        old.select(10)
        old.action(10, 17, TdApi.ChatActionTyping())
        old.controller.deactivate()
        val current = Fixture(generation = 2) { shared = it }
        old.action(10, 17, TdApi.ChatActionChoosingSticker())
        old.advance(10_000)
        assertSame(current.state, shared)
        assertNull(shared.headerStatus.activity)
    }

    @Test
    fun unrelatedTopicActionsAreIgnoredAndHeaderContentDoesNotEnterStringRendering() {
        val fixture = selectedFixture()
        fixture.mapper.accept(TdApi.UpdateUser(TdApi.User().apply { id = 9973; firstName = "private sender" }))
        fixture.action(10, 9973, TdApi.ChatActionTyping())
        fixture.mapper.accept(TdApi.UpdateChatAction(10, TdApi.MessageTopicForum(7),
            TdApi.MessageSenderUser(9973), TdApi.ChatActionCancel()))
        val activity = assertNotNull(fixture.state.headerStatus.activity)
        assertEquals(9973L, activity.senderId)
        assertEquals("private sender", fixture.state.senderNames[9973])
        val text = listOf(fixture.state, fixture.state.headerStatus, activity,
            ChatEvent.ActivityChanged(10, 9973, false, ChatActivityKind.TYPING)).joinToString()
        assertFalse(text.contains("private"))
        assertFalse(text.contains("9973"))
    }

    private fun selectedFixture() = Fixture().also { it.chat(10); it.select(10) }

    private class Fixture(generation: Long = 1, publish: (ChatSnapshot) -> Unit = {}) {
        data class Timer(val due: Long, val work: () -> Unit)
        val requests = mutableListOf<TdApi.Function<*>>()
        var state = ChatSnapshot()
        private var now = 10_000L
        private val queued = ArrayDeque<() -> Unit>()
        private val timers = mutableListOf<Timer>()
        private val session = OfficialTdChatSession { command, _, _ -> requests += command }
        val controller = ChatController(generation, session, queued::addLast, { state = it; publish(it) }, { now },
            schedule = { delay, work -> timers += Timer(now + delay, work) })
        val mapper = TdChatUpdateMapper(controller::event)

        init { controller.start() }

        fun chat(id: Long, nativeType: TdApi.ChatType = TdApi.ChatTypeBasicGroup(id + 20)) {
            mapper.accept(TdApi.UpdateNewChat(TdApi.Chat().apply {
                this.id = id
                title = "synthetic group"
                type = nativeType
                positions = arrayOf(TdApi.ChatPosition(TdApi.ChatListMain(), 100, false, null))
            }))
        }

        fun basicGroup(id: Long, count: Int) = mapper.accept(TdApi.UpdateBasicGroup(TdApi.BasicGroup().apply {
            this.id = id
            memberCount = count
            isActive = true
            status = TdApi.ChatMemberStatusMember(0)
        }))

        fun supergroup(id: Long, count: Int) = mapper.accept(TdApi.UpdateSupergroup(TdApi.Supergroup().apply {
            this.id = id
            memberCount = count
            status = TdApi.ChatMemberStatusMember(0)
        }))

        fun select(id: Long) = controller.select(state.token, id)

        fun action(chatId: Long, senderId: Long, action: TdApi.ChatAction, isChat: Boolean = false) {
            val sender = if (isChat) TdApi.MessageSenderChat(senderId) else TdApi.MessageSenderUser(senderId)
            mapper.accept(TdApi.UpdateChatAction(chatId, null, sender, action))
        }

        fun advance(millis: Long) {
            now += millis
            while (true) {
                val index = timers.indexOfFirst { it.due <= now }
                if (index < 0) return
                timers.removeAt(index).work()
                while (queued.isNotEmpty()) queued.removeFirst().invoke()
            }
        }
    }
}
