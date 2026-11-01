package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import org.drinkless.tdlib.TdApi
import kotlin.test.*

/** Actual search facade, native update mapper and actor; all identities/responses are synthetic. */
class TopPeersSearchConsumerTest {
    @Test
    fun disabledOptionSkipsTopChatsButStillResolvesRealRecentSearchesInNativeOrder() {
        val fixture = NativeFixture()
        fixture.overview()
        assertEquals("disable_top_chats", assertIs<TdApi.GetOption>(fixture.last.command).name)
        fixture.last.result(TdApi.OptionValueBoolean(true))
        val recent = assertIs<TdApi.SearchChats>(fixture.last.command)
        assertEquals("", recent.query)
        assertNull(recent.typeFilter)
        assertEquals(50, recent.limit)
        fixture.last.result(TdApi.Chats(3, longArrayOf(20, 10, 20)))
        assertEquals(20L, assertIs<TdApi.GetChat>(fixture.last.command).chatId)
        fixture.last.result(nativeChat(20))
        assertEquals(10L, assertIs<TdApi.GetChat>(fixture.last.command).chatId)
        val final = fixture.last
        final.result(nativeChat(10))
        final.error()
        final.result(TdApi.Error(403, "private-native-text"))
        assertEquals(1, fixture.results.size)
        val overview = assertNotNull(fixture.results.single().value)
        assertTrue(overview.first.isEmpty())
        assertEquals(listOf(20L, 10L), overview.second.map(ChatItem::id))
        assertTrue(fixture.requests.none { it.command is TdApi.GetTopChats })
    }

    @Test
    fun falseAndKnownOptionEmptyBothEnableNativeFrequentSearchWithoutInventedRanks() {
        listOf(TdApi.OptionValueBoolean(false), TdApi.OptionValueEmpty()).forEach { option ->
            val fixture = NativeFixture()
            fixture.overview()
            fixture.last.result(option)
            val top = assertIs<TdApi.GetTopChats>(fixture.last.command)
            assertIs<TdApi.TopChatCategoryUsers>(top.category)
            assertEquals(8, top.limit)
            fixture.last.result(TdApi.Chats(3, longArrayOf(30, 10, 30)))
            fixture.last.result(nativeChat(30))
            fixture.last.result(nativeChat(10))
            assertIs<TdApi.SearchChats>(fixture.last.command)
            fixture.last.result(TdApi.Chats(1, longArrayOf(20)))
            fixture.last.result(nativeChat(20))
            val overview = assertNotNull(fixture.results.single().value)
            assertEquals(listOf(30L, 10L), overview.first.map(ChatItem::id))
            assertEquals(listOf(20L), overview.second.map(ChatItem::id))
        }
    }

    @Test
    fun unknownOptionTypesFailInsteadOfPermittingEitherNativeSearch() {
        listOf(TdApi.OptionValueInteger(0), TdApi.OptionValueString("false"), TdApi.Ok()).forEach { option ->
            val fixture = NativeFixture()
            fixture.overview()
            fixture.last.result(option)
            assertEquals(ChatError.REQUEST_FAILED, fixture.results.single().error)
            assertNull(fixture.results.single().value)
            assertEquals(1, fixture.requests.size)
        }
    }

    @Test
    fun optionFailureAndTransportFailureRetainTypedErrorsAndDoNotAssumeEnabled() {
        val fixture = NativeFixture()
        fixture.overview()
        fixture.last.result(TdApi.Error(403, "private-native-text"))
        assertEquals(ChatError.FORBIDDEN, fixture.results.single().error)
        assertEquals(403, fixture.results.single().code)
        assertEquals(1, fixture.requests.size)
        assertFalse(fixture.results.toString().contains("private"))
        val failed = NativeFixture()
        failed.overview()
        failed.last.error()
        assertEquals(ChatError.REQUEST_FAILED, failed.results.single().error)
        assertEquals(1, failed.requests.size)
    }

    @Test
    fun enabledTopChatErrorsAreNotSwallowedOrRelabelledAsAnEmptyFrequentList() {
        listOf(403 to ChatError.FORBIDDEN, 400 to ChatError.REQUEST_FAILED).forEach { (code, error) ->
            val fixture = NativeFixture()
            fixture.overview()
            fixture.last.result(TdApi.OptionValueBoolean(false))
            assertIs<TdApi.GetTopChats>(fixture.last.command)
            fixture.last.result(TdApi.Error(code, "private-native-text"))
            assertEquals(error, fixture.results.single().error)
            assertEquals(code, fixture.results.single().code)
            assertNull(fixture.results.single().value)
            assertEquals(2, fixture.requests.size)
        }
    }

    @Test
    fun disabledRecentSearchErrorsRemainVisible() {
        val fixture = NativeFixture()
        fixture.overview()
        fixture.last.result(TdApi.OptionValueBoolean(true))
        assertIs<TdApi.SearchChats>(fixture.last.command)
        fixture.last.result(TdApi.Error(429, "private-native-text"))
        assertEquals(ChatError.RATE_LIMITED, fixture.results.single().error)
        assertNull(fixture.results.single().value)
    }

    @Test
    fun invalidatingAnOptionReadStopsItsLateEnabledResultBeforeTopChats() {
        val fixture = NativeFixture()
        fixture.overview()
        val option = fixture.last
        fixture.session.invalidateSearchOverview()
        option.result(TdApi.OptionValueBoolean(false))
        assertEquals(1, fixture.requests.size)
        assertTrue(fixture.results.isEmpty())
    }

    @Test
    fun invalidatingTopChatsStopsItsLateResponseBeforeMetadataOrRecentRequests() {
        val fixture = NativeFixture()
        fixture.overview()
        fixture.last.result(TdApi.OptionValueEmpty())
        val top = fixture.last
        fixture.session.invalidateSearchOverview()
        top.result(TdApi.Chats(2, longArrayOf(10, 20)))
        assertEquals(2, fixture.requests.size)
        assertTrue(fixture.results.isEmpty())
    }

    @Test
    fun invalidatingFrequentMetadataStopsTheRemainingNativeChain() {
        val fixture = NativeFixture()
        fixture.overview()
        fixture.last.result(TdApi.OptionValueEmpty())
        fixture.last.result(TdApi.Chats(2, longArrayOf(10, 20)))
        val chat = fixture.last
        assertEquals(10L, assertIs<TdApi.GetChat>(chat.command).chatId)
        fixture.session.invalidateSearchOverview()
        chat.result(nativeChat(10))
        assertEquals(3, fixture.requests.size)
        assertTrue(fixture.results.isEmpty())
    }

    @Test
    fun invalidatingRecentOrItsMetadataCannotPublishAnObsoleteOverview() {
        repeat(2) { stage ->
            val fixture = NativeFixture()
            fixture.overview()
            fixture.last.result(TdApi.OptionValueBoolean(true))
            if (stage == 1) fixture.last.result(TdApi.Chats(2, longArrayOf(10, 20)))
            val pending = fixture.last
            val count = fixture.requests.size
            fixture.session.invalidateSearchOverview()
            if (stage == 0) pending.result(TdApi.Chats(2, longArrayOf(10, 20)))
            else pending.result(nativeChat(10))
            assertEquals(count, fixture.requests.size)
            assertTrue(fixture.results.isEmpty())
        }
    }

    @Test
    fun updateMapperOnlyInterpretsTheExactNativeOptionAndKeepsUnknownDistinct() {
        val events = mutableListOf<ChatEvent>()
        val mapper = TdChatUpdateMapper(events::add)
        mapper.accept(TdApi.UpdateOption("another_option", TdApi.OptionValueEmpty()))
        assertTrue(events.isEmpty())
        listOf(TdApi.OptionValueBoolean(true), TdApi.OptionValueBoolean(false), TdApi.OptionValueEmpty(),
            TdApi.OptionValueInteger(0), null).forEach { value ->
            mapper.accept(TdApi.UpdateOption("disable_top_chats", value))
        }
        assertEquals(listOf(true, false, false, null, null), events.map { assertIs<ChatEvent.TopChatsDisabled>(it).disabled })
    }

    @Test
    fun disablingClearsOnlyFrequentAndDoesNotResetAnActiveQueryOrConversation() {
        val fixture = ActorFixture()
        fixture.overview()
        fixture.controller.search(fixture.state.token, ChatSearchQuery("synthetic query"))
        val before = fixture.state
        fixture.update(TdApi.OptionValueBoolean(true))
        assertEquals(before.copy(search = before.search.copy(frequent = emptyList(), overviewLoading = false)), fixture.state)
        assertTrue(fixture.state.search.loading)
        assertEquals(listOf(20L), fixture.state.search.recent.map(ChatItem::id))
        assertEquals(1, fixture.session.invalidations)
    }

    @Test
    fun disablingRevokesPendingOverviewAndLateSuccessOrFailureCannotReviveIt() {
        listOf(true, null).forEach { disabled ->
            val fixture = ActorFixture()
            fixture.overview()
            fixture.controller.searchOverview(fixture.state.token)
            val pending = fixture.session.overviews.last()
            val recent = fixture.state.search.recent
            fixture.update(if (disabled == null) TdApi.OptionValueInteger(0) else TdApi.OptionValueBoolean(disabled))
            val before = fixture.state
            assertFalse(before.search.overviewLoading)
            assertTrue(before.search.frequent.isEmpty())
            assertEquals(recent, before.search.recent)
            pending(ChatResult(listOf(chat(30)) to listOf(chat(40))))
            pending(ChatResult(error = ChatError.FORBIDDEN))
            fixture.flush()
            assertEquals(before, fixture.state)
            assertEquals(1, fixture.session.invalidations)
        }
    }

    @Test
    fun enablingNeverReloadsUntilTheNextExplicitOverviewAndRetiredControllersIgnoreUpdates() {
        val fixture = ActorFixture()
        fixture.overview()
        fixture.update(TdApi.OptionValueBoolean(true))
        val count = fixture.session.overviews.size
        fixture.update(TdApi.OptionValueEmpty())
        assertEquals(count, fixture.session.overviews.size)
        assertTrue(fixture.state.search.frequent.isEmpty())
        fixture.overview()
        assertEquals(count + 1, fixture.session.overviews.size)
        assertEquals(listOf(10L), fixture.state.search.frequent.map(ChatItem::id))
        val before = fixture.state
        fixture.controller.deactivate()
        fixture.update(TdApi.OptionValueBoolean(true))
        assertEquals(before, fixture.state)
        assertEquals(1, fixture.session.invalidations)
    }

    private class NativeFixture {
        val requests = mutableListOf<Request>()
        val results = mutableListOf<ChatResult<Pair<List<ChatItem>, List<ChatItem>>>>()
        val session = OfficialTdChatSession { command, result, error -> requests += Request(command, result, error) }
        val last get() = requests.last()
        fun overview() = session.searchOverview(results::add)
    }

    private class Request(val command: TdApi.Function<*>, val result: (TdApi.Object) -> Unit, val error: () -> Unit)

    private class ActorFixture {
        private val queued = ArrayDeque<() -> Unit>()
        var state = ChatSnapshot()
        val session = ActorSession()
        val controller = ChatController(1, session, queued::addLast, { state = it })
        val mapper = TdChatUpdateMapper(controller::event)
        init { controller.start(); flush() }
        fun flush() { while (queued.isNotEmpty()) queued.removeFirst()() }
        fun update(value: TdApi.OptionValue?) = mapper.accept(TdApi.UpdateOption("disable_top_chats", value))
        fun overview() {
            controller.searchOverview(state.token)
            session.overviews.last()(ChatResult(listOf(chat(10)) to listOf(chat(20))))
            flush()
        }
    }

    private class ActorSession : TdChatSession {
        var invalidations = 0
        val overviews = mutableListOf<(ChatResult<Pair<List<ChatItem>, List<ChatItem>>>) -> Unit>()
        override fun invalidateSearchOverview() { invalidations++ }
        override fun searchOverview(result: (ChatResult<Pair<List<ChatItem>, List<ChatItem>>>) -> Unit) { overviews += result }
        override fun search(query: ChatSearchQuery, offset: String, result: (ChatResult<ChatSearchPage>) -> Unit) = Unit
        override fun loadChats(limit: Int, result: (ChatResult<ChatLoadResult>) -> Unit) { result(ChatResult(ChatLoadResult(true))) }
        override fun history(chatId: Long, fromMessageId: Long, limit: Int, result: (ChatResult<List<ChatMessage>>) -> Unit) = Unit
        override fun open(chatId: Long) = Unit
        override fun close(chatId: Long) = Unit
        override fun sendText(chatId: Long, text: String, result: (ChatResult<ChatMessage>) -> Unit) = Unit
        override fun retry(chatId: Long, messageId: Long, result: (ChatResult<List<ChatMessage>>) -> Unit) = Unit
        override fun markRead(chatId: Long, messageIds: List<Long>, result: (ChatResult<Unit>) -> Unit) = Unit
    }

    private companion object {
        fun nativeChat(id: Long) = TdApi.Chat().apply { this.id = id; type = TdApi.ChatTypePrivate(7); title = "synthetic chat" }
        fun chat(id: Long) = ChatItem(id, "synthetic chat")
    }
}
