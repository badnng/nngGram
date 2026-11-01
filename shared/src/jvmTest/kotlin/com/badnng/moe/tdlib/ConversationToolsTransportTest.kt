package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import org.drinkless.tdlib.TdApi
import kotlin.test.*

class ConversationToolsTransportTest {
    @Test
    fun chatSearchAnchorsMaximumDateAndStopsAfterTheMinimumDate() {
        val fixture = Fixture()
        var result: ChatResult<ChatSearchPage>? = null
        fixture.session.search(ChatSearchQuery("synthetic", 10, 7, 100, 200, ChatSearchCategory.PHOTOS), "") { result = it }
        val anchor = assertIs<TdApi.GetChatMessageByDate>(fixture.last.command)
        assertEquals(10L, anchor.chatId)
        assertEquals(200, anchor.date)
        fixture.last.result(message(900, date = 200))
        val search = assertIs<TdApi.SearchChatMessages>(fixture.last.command)
        assertEquals(900L, search.fromMessageId)
        assertEquals(7L, assertIs<TdApi.MessageSenderUser>(search.senderId).userId)
        assertIs<TdApi.SearchMessagesFilterPhoto>(search.filter)
        fixture.last.result(TdApi.FoundChatMessages(9, arrayOf(message(899, date = 190), message(800, date = 99)), 700))
        assertEquals(10L, assertIs<TdApi.GetChat>(fixture.last.command).chatId)
        fixture.last.result(chat(10))
        val page = assertNotNull(result?.value)
        assertEquals(listOf(899L), page.messages.map(ChatMessage::id))
        assertEquals(listOf(10L), page.chats.map(ChatItem::id))
        assertEquals("", page.nextOffset)
        assertEquals(0, page.totalCount)
    }

    @Test
    fun missingDateAnchorIsAnEmptyPageAndAnInvalidCursorNeverResetsTheSearch() {
        val fixture = Fixture()
        val results = mutableListOf<ChatResult<ChatSearchPage>>()
        fixture.session.search(ChatSearchQuery(chatId = 10, maxDate = 200), "", results::add)
        fixture.last.result(TdApi.Error(404, "synthetic private error"))
        assertEquals(emptyList(), results.single().value?.messages)
        assertEquals(1, fixture.requests.size)
        fixture.session.search(ChatSearchQuery(chatId = 10), "invalid-cursor", results::add)
        assertEquals(ChatError.REQUEST_FAILED, results.last().error)
        assertEquals(1, fixture.requests.size)
    }

    @Test
    fun globalSearchUsesNativeDateAndCursorThenFiltersAuthorWithoutLosingPagination() {
        val fixture = Fixture()
        var result: ChatResult<ChatSearchPage>? = null
        fixture.session.search(ChatSearchQuery("synthetic", senderId = 7, minDate = 100, maxDate = 200,
            category = ChatSearchCategory.FILES), "native-offset") { result = it }
        val search = assertIs<TdApi.SearchMessages>(fixture.last.command)
        assertNull(search.chatList)
        assertEquals("native-offset", search.offset)
        assertEquals(100, search.minDate)
        assertEquals(200, search.maxDate)
        assertIs<TdApi.SearchMessagesFilterDocument>(search.filter)
        fixture.last.result(TdApi.FoundMessages(20,
            arrayOf(message(2, date = 150), message(3, chatId = 20, date = 150, senderId = 8)), "next-native"))
        fixture.last.result(chat(10))
        val page = assertNotNull(result?.value)
        assertEquals(listOf(2L), page.messages.map(ChatMessage::id))
        assertEquals("next-native", page.nextOffset)
        assertEquals(listOf(10L), page.chats.map(ChatItem::id))
        assertEquals(0, page.totalCount)
    }

    @Test
    fun anEmptyAuthorFilteredPageStillRetainsTheNativeNextCursor() {
        val fixture = Fixture()
        var result: ChatResult<ChatSearchPage>? = null
        fixture.session.search(ChatSearchQuery(senderId = 8, category = ChatSearchCategory.LINKS), "old") { result = it }
        fixture.last.result(TdApi.FoundMessages(4, arrayOf(message(2)), "next"))
        assertEquals(emptyList(), result?.value?.messages)
        assertEquals("next", result?.value?.nextOffset)
        assertEquals(1, fixture.requests.size)
    }

    @Test
    fun everyMediaCategoryUsesItsNativeFilterAndChatSendersRemainChatIdentities() {
        val expected = mapOf(
            ChatSearchCategory.PHOTOS to TdApi.SearchMessagesFilterPhoto::class,
            ChatSearchCategory.VIDEOS to TdApi.SearchMessagesFilterVideo::class,
            ChatSearchCategory.FILES to TdApi.SearchMessagesFilterDocument::class,
            ChatSearchCategory.LINKS to TdApi.SearchMessagesFilterUrl::class,
            ChatSearchCategory.MUSIC to TdApi.SearchMessagesFilterAudio::class,
            ChatSearchCategory.VOICE to TdApi.SearchMessagesFilterVoiceNote::class,
        )
        expected.forEach { (category, filter) ->
            val fixture = Fixture()
            fixture.session.search(ChatSearchQuery(category = category), "") { }
            assertEquals(filter, assertNotNull(assertIs<TdApi.SearchMessages>(fixture.last.command).filter)::class)
        }
        val fixture = Fixture()
        fixture.session.search(ChatSearchQuery(chatId = 10, senderId = -100), "500") { }
        val request = assertIs<TdApi.SearchChatMessages>(fixture.last.command)
        assertEquals(-100L, assertIs<TdApi.MessageSenderChat>(request.senderId).chatId)
        assertEquals(500L, request.fromMessageId)
    }

    @Test
    fun allSearchCombinesKnownServerAndPublicChatsWithMessageChatMetadata() {
        val fixture = Fixture()
        var result: ChatResult<ChatSearchPage>? = null
        fixture.session.search(ChatSearchQuery("synthetic"), "") { result = it }
        fixture.last.result(TdApi.FoundMessages(1, arrayOf(message(2, chatId = 13)), "next"))
        fixture.last.result(chat(13))
        assertIs<TdApi.SearchChats>(fixture.last.command)
        fixture.last.result(TdApi.Chats(2, longArrayOf(10, 11)))
        assertIs<TdApi.SearchChatsOnServer>(fixture.last.command)
        fixture.last.result(TdApi.Chats(2, longArrayOf(11, 12)))
        assertIs<TdApi.SearchPublicChats>(fixture.last.command)
        fixture.last.result(TdApi.Error(429, "synthetic native text"))
        for (id in listOf(10L, 11L, 12L)) {
            assertEquals(id, assertIs<TdApi.GetChat>(fixture.last.command).chatId)
            fixture.last.result(chat(id))
        }
        assertEquals(listOf(10L, 11L, 12L, 13L), result?.value?.chats?.map(ChatItem::id))
        assertEquals("next", result?.value?.nextOffset)
    }

    @Test
    fun channelAndAppSearchUseNativeChatTypeFiltersAndHaveNoInventedPagination() {
        val fixture = Fixture()
        fixture.session.search(ChatSearchQuery("synthetic", category = ChatSearchCategory.CHANNELS), "") { }
        assertIs<TdApi.SearchChatTypeFilterChannel>(assertIs<TdApi.SearchChats>(fixture.last.command).typeFilter)
        fixture.session.search(ChatSearchQuery("synthetic", category = ChatSearchCategory.APPS), "") { }
        assertIs<TdApi.SearchChatTypeFilterBot>(assertIs<TdApi.SearchChats>(fixture.last.command).typeFilter)
        val count = fixture.requests.size
        var page: ChatResult<ChatSearchPage>? = null
        fixture.session.search(ChatSearchQuery(category = ChatSearchCategory.CHANNELS), "unsupported-next") { page = it }
        assertEquals("", page?.value?.nextOffset)
        assertEquals(count, fixture.requests.size)
        fixture.session.search(ChatSearchQuery(category = ChatSearchCategory.APPS, minDate = 100), "") { page = it }
        assertEquals(ChatError.UNSUPPORTED, page?.error)
    }

    @Test
    fun publicPostSearchIsAlwaysFreeAndQuotaExhaustionNeverRetriesForPayment() {
        val fixture = Fixture()
        val results = mutableListOf<ChatResult<ChatSearchPage>>()
        fixture.session.search(ChatSearchQuery("synthetic", category = ChatSearchCategory.POSTS), "next-post", results::add)
        val request = assertIs<TdApi.SearchPublicPosts>(fixture.last.command)
        assertEquals(0L, request.starCount)
        assertEquals("next-post", request.offset)
        val pending = fixture.last
        pending.result(TdApi.FoundPublicPosts(emptyArray(), "", null, true))
        pending.result(TdApi.FoundPublicPosts(arrayOf(message(2)), "", null, false))
        pending.error()
        assertEquals(ChatError.RATE_LIMITED, results.single().error)
        assertEquals(1, fixture.requests.size)
    }

    @Test
    fun overviewKeepsNativeRankAndResolvesRealChatIdsForFrequentAndRecent() {
        val fixture = Fixture()
        var result: ChatResult<Pair<List<ChatItem>, List<ChatItem>>>? = null
        fixture.session.searchOverview { result = it }
        assertEquals("disable_top_chats", assertIs<TdApi.GetOption>(fixture.last.command).name)
        fixture.last.result(TdApi.OptionValueEmpty())
        assertIs<TdApi.TopChatCategoryUsers>(assertIs<TdApi.GetTopChats>(fixture.last.command).category)
        fixture.last.result(TdApi.Chats(3, longArrayOf(30, 10, 30)))
        fixture.last.result(chat(30))
        fixture.last.result(chat(10))
        val recent = assertIs<TdApi.SearchChats>(fixture.last.command)
        assertEquals("", recent.query)
        assertNull(recent.typeFilter)
        assertEquals(50, recent.limit)
        fixture.last.result(TdApi.Chats(2, longArrayOf(20, 30)))
        fixture.last.result(chat(20))
        fixture.last.result(chat(30))
        assertEquals(listOf(30L, 10L), result?.value?.first?.map(ChatItem::id))
        assertEquals(listOf(20L, 30L), result?.value?.second?.map(ChatItem::id))
    }

    @Test
    fun recentlyFoundMutationsUseOnlyTheExplicitNativeCommandsAndCompleteOnce() {
        val fixture = Fixture()
        val results = mutableListOf<ChatResult<Unit>>()
        fixture.session.addRecentlyFoundChat(0, results::add)
        assertTrue(fixture.requests.isEmpty())
        assertEquals(ChatError.REQUEST_FAILED, results.single().error)
        fixture.session.addRecentlyFoundChat(-100, results::add)
        assertEquals(-100L, assertIs<TdApi.AddRecentlyFoundChat>(fixture.last.command).chatId)
        val add = fixture.last
        add.result(TdApi.Ok())
        add.result(TdApi.Error(403, "private native response"))
        assertEquals(Unit, results.last().value)
        assertEquals(2, results.size)
        fixture.session.clearRecentlyFoundChats(results::add)
        assertIs<TdApi.ClearRecentlyFoundChats>(fixture.last.command)
        fixture.last.result(TdApi.Error(403, "private native response"))
        assertEquals(ChatError.FORBIDDEN, results.last().error)
        assertFalse(results.toString().contains("private"))
        fixture.session.clearRecentlyFoundChats(results::add)
        fixture.last.result(TdApi.Ok())
        assertEquals(Unit, results.last().value)
    }

    @Test
    fun unreadAndNewerWindowsUseRealForwardHistoryAndValidateTheirChatIdentity() {
        val fixture = Fixture()
        val results = mutableListOf<ChatResult<List<ChatMessage>>>()
        fixture.session.unreadHistory(-100, 900, results::add)
        val unread = assertIs<TdApi.GetChatHistory>(fixture.last.command)
        assertEquals(-100L, unread.chatId)
        assertEquals(900L, unread.fromMessageId)
        assertEquals(-49, unread.offset)
        assertEquals(50, unread.limit)
        assertFalse(unread.onlyLocal)
        fixture.last.result(TdApi.Messages(2, arrayOf(message(900, -100), message(901, -100))))
        assertEquals(listOf(900L, 901L), results.last().value?.map { it.id })
        fixture.session.newerHistory(-100, 901, results::add)
        assertEquals(901L, assertIs<TdApi.GetChatHistory>(fixture.last.command).fromMessageId)
        fixture.last.result(TdApi.Messages(1, arrayOf(message(902, 10))))
        assertEquals(ChatError.REQUEST_FAILED, results.last().error)
        val count = fixture.requests.size
        fixture.session.newerHistory(-100, 0, results::add)
        assertEquals(count, fixture.requests.size)
        assertEquals(ChatError.REQUEST_FAILED, results.last().error)
    }

    @Test
    fun groupDetailsUseFreshChatTypeAndResolveAdministratorNamesFromUsers() {
        val fixture = Fixture()
        var result: ChatResult<ChatDetails>? = null
        fixture.session.chatDetails(ChatItem(10, "synthetic", basicGroupId = 999)) { result = it }
        fixture.last.result(chat(10, TdApi.ChatTypeSupergroup(42, false)))
        assertEquals(42L, assertIs<TdApi.GetSupergroupFullInfo>(fixture.last.command).supergroupId)
        fixture.last.result(TdApi.SupergroupFullInfo().apply { description = "synthetic description" })
        assertEquals(10L, assertIs<TdApi.GetChatAdministrators>(fixture.last.command).chatId)
        fixture.last.result(TdApi.ChatAdministrators(arrayOf(TdApi.ChatAdministrator(7, "synthetic title", true, false))))
        assertEquals(7L, assertIs<TdApi.GetUser>(fixture.last.command).userId)
        fixture.last.result(TdApi.User().apply { id = 7; firstName = "Synthetic"; lastName = "User" })
        val details = assertNotNull(result?.value)
        assertEquals("synthetic description", details.description)
        assertEquals(ChatAdministrator(7, "Synthetic User", "synthetic title", true), details.administrators.single())
        assertFalse(details.toString().contains("synthetic"))
    }

    @Test
    fun aDeniedAdministratorListOrWrongUserIsAnErrorInsteadOfAnEmptyOrInventedList() {
        val fixture = Fixture()
        var result: ChatResult<ChatDetails>? = null
        fixture.session.chatDetails(ChatItem(10, "synthetic")) { result = it }
        fixture.last.result(chat(10, TdApi.ChatTypeBasicGroup(42)))
        assertEquals(42L, assertIs<TdApi.GetBasicGroupFullInfo>(fixture.last.command).basicGroupId)
        fixture.last.result(TdApi.BasicGroupFullInfo().apply { description = "synthetic" })
        fixture.last.result(TdApi.Error(403, "synthetic private native text"))
        assertNull(result?.value)
        assertEquals(ChatError.FORBIDDEN, result?.error)
        assertFalse(result.toString().contains("private"))

        fixture.session.chatDetails(ChatItem(10, "synthetic")) { result = it }
        fixture.last.result(chat(10, TdApi.ChatTypeBasicGroup(42)))
        fixture.last.result(TdApi.BasicGroupFullInfo())
        fixture.last.result(TdApi.ChatAdministrators(arrayOf(TdApi.ChatAdministrator(7, "", false, false))))
        fixture.last.result(TdApi.User().apply { id = 8; firstName = "Unrelated" })
        assertEquals(ChatError.REQUEST_FAILED, result?.error)
        assertNull(result?.value)
    }

    @Test
    fun pinnedPagesAndHistoryAroundUseNativeMessageIdsAndRejectCrossChatResults() {
        val fixture = Fixture()
        var pinned: ChatResult<ChatSearchPage>? = null
        fixture.session.pinnedMessages(10, 900) { pinned = it }
        val request = assertIs<TdApi.SearchChatMessages>(fixture.last.command)
        assertEquals(900L, request.fromMessageId)
        assertIs<TdApi.SearchMessagesFilterPinned>(request.filter)
        fixture.last.result(TdApi.FoundChatMessages(3, arrayOf(message(800)), 700))
        fixture.last.result(chat(10))
        assertEquals("700", pinned?.value?.nextOffset)
        var around: ChatResult<List<ChatMessage>>? = null
        fixture.session.historyAround(10, 800) { around = it }
        val history = assertIs<TdApi.GetChatHistory>(fixture.last.command)
        assertEquals(800L, history.fromMessageId)
        assertEquals(-20, history.offset)
        assertEquals(50, history.limit)
        assertFalse(history.onlyLocal)
        fixture.last.result(TdApi.Messages(1, arrayOf(message(800, chatId = 20))))
        assertEquals(ChatError.REQUEST_FAILED, around?.error)
        assertNull(around?.value)
    }

    @Test
    fun invalidArgumentsAndTransportFailuresStayTypedAndDoNotLeakNativeText() {
        val fixture = Fixture()
        val results = mutableListOf<ChatResult<ChatSearchPage>>()
        fixture.session.search(ChatSearchQuery(chatId = 0), "", results::add)
        fixture.session.search(ChatSearchQuery(minDate = 200, maxDate = 100), "", results::add)
        fixture.session.pinnedMessages(0, 1, results::add)
        assertTrue(fixture.requests.isEmpty())
        assertTrue(results.all { it.error == ChatError.REQUEST_FAILED })
        fixture.session.search(ChatSearchQuery(category = ChatSearchCategory.PHOTOS), "", results::add)
        fixture.last.result(TdApi.Error(403, "synthetic-private-query"))
        assertEquals(ChatError.FORBIDDEN, results.last().error)
        assertFalse(results.toString().contains("private"))
        assertFalse(ChatSearchQuery("synthetic-private-query").toString().contains("private"))
        val throwing = OfficialTdChatSession { _, _, _ -> throw IllegalStateException("synthetic-private-query") }
        throwing.pinnedMessages(10, 0, results::add)
        assertEquals(ChatError.REQUEST_FAILED, results.last().error)
        assertFalse(results.toString().contains("private"))
    }

    private fun message(id: Long, chatId: Long = 10, date: Int = 150, senderId: Long = 7) = TdApi.Message().apply {
        this.id = id; this.chatId = chatId; this.date = date
        this.senderId = TdApi.MessageSenderUser(senderId)
        content = TdApi.MessageText(TdApi.FormattedText("synthetic message", emptyArray()), null, null)
    }

    private fun chat(id: Long, type: TdApi.ChatType = TdApi.ChatTypePrivate(7)) = TdApi.Chat().apply {
        this.id = id; this.type = type; title = "synthetic chat"
    }

    private class Fixture {
        val requests = mutableListOf<Request>()
        val session = OfficialTdChatSession { command, result, error -> requests += Request(command, result, error) }
        val last get() = requests.last()
    }

    private class Request(val command: TdApi.Function<*>, val result: (TdApi.Object) -> Unit, val error: () -> Unit)
}
