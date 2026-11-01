package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import com.badnng.moe.settings.*
import kotlin.test.*

class SupportChatControllerTest {
    @Test fun genuineUnlistedSupportIsSelectedOnlyAfterConfirmedOpenWithoutInventingAListPosition() {
        val f = Fixture()
        val owner = f.state.token
        val results = f.begin()
        f.native.support.resolutions.single().result(ChatResult(target))
        f.flush()
        assertEquals(owner, f.state.token)
        assertEquals(listOf(1L), f.native.histories)
        assertEquals(target, f.native.support.opens.single().target)
        assertTrue(results.isEmpty(), "Resolution and admission are not a confirmed native open.")
        f.native.support.opens.single().result(ChatResult(Unit))
        f.flush()
        val selected = results.single().value
        assertNotNull(selected)
        assertNull(results.single().error)
        assertEquals(selected, f.state.token)
        assertEquals(target.chat.id, f.state.selected?.id)
        assertEquals(listOf(1L), f.state.items.map { it.id }, "An unlisted native chat does not acquire a fabricated card.")
        assertEquals(listOf(1L, target.chat.id), f.native.histories)
        assertEquals(listOf(target.chat.id), f.native.pins)
        assertEquals(listOf(1L), f.native.opened, "The confirmed support OpenChat must not be issued a second time by selection.")
    }

    @Test fun resolveAndOpenFailuresKeepTheOriginalSelectionAndAnExplicitRetryCanSucceed() {
        val f = Fixture()
        val owner = f.state.token
        val resolveFailure = f.begin()
        f.native.support.resolutions.last().result(ChatResult(error = ChatError.FORBIDDEN))
        f.flush()
        assertEquals(SettingsError.FORBIDDEN, resolveFailure.single().error)
        assertTrue(f.native.support.opens.isEmpty())
        assertEquals(owner, f.state.token)
        val openFailure = f.begin()
        f.resolve()
        f.native.support.opens.last().result(ChatResult(error = ChatError.REQUEST_FAILED))
        f.flush()
        assertEquals(SettingsError.REQUEST_FAILED, openFailure.single().error)
        assertEquals(owner, f.state.token)
        assertEquals(listOf(1L), f.native.histories)
        val retry = f.begin()
        f.resolve()
        f.native.support.opens.last().result(ChatResult(Unit))
        f.flush()
        assertEquals(target.chat.id, retry.single().value?.chatId)
        assertEquals(3, f.native.support.resolutions.size)
        assertEquals(2, f.native.support.opens.size)
    }

    @Test fun duplicateRequestsAndCallbacksCannotOpenTwiceOrDeliverASecondTerminalResult() {
        val f = Fixture()
        val first = f.begin()
        val duplicate = f.begin()
        assertNotNull(duplicate.single().error)
        assertEquals(1, f.native.support.resolutions.size)
        val resolved = f.native.support.resolutions.single().result
        resolved(ChatResult(target)); resolved(ChatResult(target))
        f.flush()
        assertEquals(1, f.native.support.opens.size)
        val opened = f.native.support.opens.single().result
        opened(ChatResult(Unit)); opened(ChatResult(Unit)); opened(ChatResult(error = ChatError.FORBIDDEN))
        f.flush()
        assertEquals(target.chat.id, first.single().value?.chatId)
        assertEquals(1, duplicate.size)
        assertEquals(listOf(1L, target.chat.id), f.native.histories)
    }

    @Test fun staleAdmissionAndLeaseOrAccountLossBeforeResolutionNeverReachOpen() {
        val f = Fixture()
        val old = f.state.token.copy(selection = f.state.token.selection - 1)
        val oldResult = mutableListOf<SettingsResult<ChatToken>>()
        f.controller.openSupport(old, { true }, oldResult::add)
        assertEquals(SettingsError.NOT_READY, oldResult.single().error)
        f.active = false
        assertEquals(SettingsError.NOT_READY, f.begin().single().error)
        f.active = true; f.allowed = false
        assertEquals(SettingsError.NOT_READY, f.begin().single().error)
        assertTrue(f.native.support.resolutions.isEmpty())
        for (loseAccount in listOf(false, true)) {
            val current = Fixture()
            val result = current.begin()
            if (loseAccount) current.allowed = false else current.active = false
            assertFalse(current.native.support.resolutions.single().active())
            current.resolve()
            assertEquals(SettingsError.NOT_READY, result.single().error)
            assertTrue(current.native.support.opens.isEmpty())
            assertEquals(1L, current.state.selected?.id)
        }
    }

    @Test fun acceptedOpenAfterLeaseOrAccountLossClosesOnlyTheUnadoptedTargetAndNeverNavigates() {
        for (loseAccount in listOf(false, true)) {
            val f = Fixture()
            val owner = f.state.token
            val result = f.begin()
            f.resolve()
            val opened = f.native.support.opens.single()
            if (loseAccount) f.allowed = false else f.active = false
            assertFalse(opened.active())
            opened.result(ChatResult(Unit)); opened.result(ChatResult(Unit))
            f.flush()
            assertEquals(SettingsError.NOT_READY, result.single().error)
            assertEquals(owner, f.state.token)
            assertEquals(listOf(target.chat.id), f.native.closed)
            assertEquals(listOf(1L), f.native.histories)
        }
    }

    @Test fun selectionChangesCancelOldOpeningAndLateSuccessCannotCloseTheIndependentlySelectedTarget() {
        for (selectSupport in listOf(false, true)) {
            val f = Fixture()
            val result = f.begin()
            f.resolve()
            val opening = f.native.support.opens.single()
            val destination = if (selectSupport) target.chat.copy(order = 100, positions = mapOf(ChatListRef() to 100L))
                else ChatItem(2, "Other synthetic chat", 100)
            f.controller.event(ChatEvent.NewChat(destination))
            f.controller.select(f.state.token, destination.id)
            val selected = f.state.token
            assertFalse(opening.active())
            opening.result(ChatResult(Unit)); f.flush()
            assertEquals(SettingsError.NOT_READY, result.single().error)
            assertEquals(selected, f.state.token)
            assertEquals(if (selectSupport) 0 else 1, f.native.closed.count { it == target.chat.id })
            assertEquals(1, f.native.histories.count { it == destination.id })
        }
    }

    @Test fun canceledOpenMustFinishCleanupBeforeAReenteredPageCanRetryTheSameTarget() {
        val f = Fixture()
        val owner = f.state.token
        val first = f.begin()
        f.resolve()
        val oldOpen = f.native.support.opens.single()
        f.active = false
        f.controller.cancelSupportOpening()
        assertEquals(SettingsError.NOT_READY, first.single().error)
        assertFalse(oldOpen.active())
        f.active = true
        val reentered = f.begin()
        assertEquals(SettingsError.NOT_READY, reentered.single().error)
        assertEquals(1, f.native.support.resolutions.size)
        assertEquals(1, f.native.support.opens.size)
        oldOpen.result(ChatResult(Unit))
        val beforeCleanup = f.begin()
        assertEquals(SettingsError.NOT_READY, beforeCleanup.single().error,
            "The actor must process the old accepted Open cleanup before admitting a replacement.")
        assertEquals(1, f.native.support.resolutions.size)
        f.flush()
        assertEquals(listOf(target.chat.id), f.native.closed)
        assertEquals(owner, f.state.token)
        assertEquals(1, first.size)
        val retry = f.begin()
        assertEquals(2, f.native.support.resolutions.size)
        f.resolve()
        assertEquals(2, f.native.support.opens.size)
        oldOpen.result(ChatResult(Unit)); oldOpen.result(ChatResult(error = ChatError.FORBIDDEN))
        f.flush()
        assertTrue(retry.isEmpty())
        assertEquals(listOf(target.chat.id), f.native.closed)
        f.native.support.opens.last().result(ChatResult(Unit))
        f.flush()
        val adopted = f.state.token
        assertEquals(target.chat.id, retry.single().value?.chatId)
        assertEquals(adopted, retry.single().value)
        oldOpen.result(ChatResult(Unit)); f.flush()
        assertEquals(adopted, f.state.token)
        assertEquals(1, f.native.closed.count { it == target.chat.id })
        assertEquals(1, first.size)
        assertEquals(1, reentered.size)
        assertEquals(1, beforeCleanup.size)
        assertEquals(1, retry.size)
        assertEquals(listOf(1L, target.chat.id), f.native.histories)
    }

    @Test fun deactivationRetiresBothResolveAndOpenCallbacksWithoutPublishingOrNavigating() {
        for (afterResolve in listOf(false, true)) {
            val f = Fixture()
            val result = f.begin()
            if (afterResolve) f.resolve()
            f.controller.deactivate()
            val retired = f.state
            assertEquals(SettingsError.NOT_READY, result.single().error)
            if (afterResolve) {
                val opened = f.native.support.opens.single()
                assertFalse(opened.active())
                opened.result(ChatResult(Unit)); opened.result(ChatResult(Unit))
            } else f.resolve()
            f.flush()
            assertSame(retired, f.state)
            assertEquals(1, result.size)
            assertEquals(listOf(1L), f.native.histories)
            assertEquals(if (afterResolve) 1 else 0, f.native.support.opens.size)
        }
    }

    private class Fixture {
        val native = Chats()
        var state = ChatSnapshot()
        var active = true
        var allowed = true
        private val queue = ArrayDeque<() -> Unit>()
        val controller = ChatController(3, native, queue::addLast, { state = it }, isAccountAllowed = { allowed })
        init {
            controller.start()
            controller.event(ChatEvent.NewChat(ChatItem(1, "Original synthetic chat", 100)))
            controller.select(state.token, 1)
            flush()
            native.pins.clear()
        }
        fun begin(): MutableList<SettingsResult<ChatToken>> = mutableListOf<SettingsResult<ChatToken>>().also {
            controller.openSupport(state.token, { active }, it::add)
        }
        fun resolve() {
            native.support.resolutions.last().result(ChatResult(target))
            flush()
        }
        fun flush() { while (queue.isNotEmpty()) queue.removeFirst().invoke() }
    }

    /** Deliberately ungated, including duplicate completions: ownership belongs to the actual controller. */
    private class Support : TdHelpSupportSession {
        data class Resolution(val active: () -> Boolean, val result: (ChatResult<TdSupportChat>) -> Unit)
        data class Open(val target: TdSupportChat, val active: () -> Boolean, val result: (ChatResult<Unit>) -> Unit)
        val resolutions = mutableListOf<Resolution>()
        val opens = mutableListOf<Open>()
        override fun resolve(active: () -> Boolean, result: (ChatResult<TdSupportChat>) -> Unit) { resolutions += Resolution(active, result) }
        override fun open(target: TdSupportChat, active: () -> Boolean, result: (ChatResult<Unit>) -> Unit) { opens += Open(target, active, result) }
    }

    private class Chats : TdChatSession {
        val support = Support()
        override val helpSupport: TdHelpSupportSession get() = support
        val histories = mutableListOf<Long>()
        val pins = mutableListOf<Long>()
        val opened = mutableListOf<Long>()
        val closed = mutableListOf<Long>()
        override fun loadChats(limit: Int, result: (ChatResult<ChatLoadResult>) -> Unit) = result(ChatResult(ChatLoadResult(true)))
        override fun history(chatId: Long, fromMessageId: Long, limit: Int, result: (ChatResult<List<ChatMessage>>) -> Unit) {
            histories += chatId; result(ChatResult(emptyList()))
        }
        override fun pinnedMessages(chatId: Long, fromMessageId: Long, result: (ChatResult<ChatSearchPage>) -> Unit) {
            pins += chatId; result(ChatResult(ChatSearchPage(emptyList())))
        }
        override fun open(chatId: Long) { opened += chatId }
        override fun close(chatId: Long) { closed += chatId }
        override fun sendText(chatId: Long, text: String, result: (ChatResult<ChatMessage>) -> Unit) = error("No messages are sent by support navigation")
        override fun retry(chatId: Long, messageId: Long, result: (ChatResult<List<ChatMessage>>) -> Unit) = error("No message retries")
        override fun markRead(chatId: Long, messageIds: List<Long>, result: (ChatResult<Unit>) -> Unit) = error("Navigation must not mark the original chat read")
    }

    private companion object {
        val target = TdSupportChat(700, ChatItem(900, "Synthetic support", privateUserId = 700, positions = emptyMap()))
    }
}
