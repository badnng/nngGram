package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import kotlin.test.*

class ChatToolsControllerTest {
    @Test
    fun anOlderQueryAndDuplicateCompletionCannotReplaceTheNewestSearch() {
        val fixture = Fixture()
        fixture.search(ChatSearchQuery("first"))
        val first = fixture.session.searches.last()
        fixture.search(ChatSearchQuery("second"))
        val second = fixture.session.searches.last()
        fixture.complete(first, ChatSearchPage(messages = listOf(message(1))))
        assertEquals("second", fixture.state.search.query.text)
        assertTrue(fixture.state.search.loading)
        assertTrue(fixture.state.search.messages.isEmpty())
        fixture.complete(second, ChatSearchPage(messages = listOf(message(2))))
        assertEquals(listOf(2L), fixture.state.search.messages.map { it.id })
        fixture.complete(first, ChatSearchPage(messages = listOf(message(3))))
        assertEquals(listOf(2L), fixture.state.search.messages.map { it.id })
    }

    @Test
    fun searchPaginationDeduplicatesByChatAndMessageAndStopsARepeatedCursor() {
        val fixture = Fixture()
        val query = ChatSearchQuery("synthetic")
        fixture.search(query)
        fixture.complete(fixture.session.searches.last(), ChatSearchPage(
            messages = listOf(message(1), message(1, 11), message(0)), nextOffset = "next", totalCount = 3))
        fixture.controller.search(fixture.state.token, query, more = true)
        assertEquals("next", fixture.session.searches.last().offset)
        fixture.complete(fixture.session.searches.last(), ChatSearchPage(
            messages = listOf(message(1), message(2)), nextOffset = "next", totalCount = 3))
        assertEquals(listOf(10L to 1L, 11L to 1L, 10L to 2L),
            fixture.state.search.messages.map { it.chatId to it.id })
        assertEquals("", fixture.state.search.nextOffset)
        val count = fixture.session.searches.size
        fixture.controller.search(fixture.state.token, query, more = true)
        assertEquals(count, fixture.session.searches.size)
    }

    @Test
    fun scopedSearchFiltersOtherChatsAndCannotReviveMessagesDeletedDuringItsRequest() {
        val fixture = selectedFixture()
        fixture.search(ChatSearchQuery("synthetic", chatId = 10))
        fixture.event(ChatEvent.Deleted(10, listOf(20), permanent = false))
        fixture.event(ChatEvent.Deleted(10, listOf(21), permanent = true))
        fixture.complete(fixture.session.searches.last(), ChatSearchPage(
            messages = listOf(message(20), message(21), message(22), message(22, 11))))
        assertEquals(listOf(22L), fixture.state.search.messages.map { it.id })
        fixture.event(ChatEvent.Deleted(10, listOf(22), permanent = true))
        assertTrue(fixture.state.search.messages.isEmpty())
        fixture.search(ChatSearchQuery("fresh", chatId = 10))
        fixture.complete(fixture.session.searches.last(), ChatSearchPage(messages = listOf(message(20), message(21))))
        assertEquals(listOf(20L), fixture.state.search.messages.map { it.id })
    }

    @Test
    fun invalidFiltersAreRejectedBeforeDispatch() {
        val fixture = Fixture()
        val invalid = listOf(ChatSearchQuery(chatId = 999), ChatSearchQuery(minDate = -1),
            ChatSearchQuery(maxDate = -1), ChatSearchQuery(minDate = 20, maxDate = 10))
        invalid.forEach(fixture::search)
        assertTrue(fixture.session.searches.isEmpty())
    }

    @Test
    fun closingSearchOrChangingSelectionInvalidatesPendingSearchAndOverview() {
        val fixture = selectedFixture()
        fixture.search(ChatSearchQuery("closed"))
        val first = fixture.session.searches.last()
        fixture.controller.searchOverview(fixture.state.token)
        val overview = fixture.session.overviews.last()
        fixture.controller.closeSearch(fixture.state.token)
        fixture.complete(first, ChatSearchPage(messages = listOf(message(1))))
        overview(ChatResult(listOf(chat(30, 0)) to listOf(chat(31, 0))))
        fixture.flush()
        assertEquals(ChatSearchState(), fixture.state.search)
        assertTrue(fixture.state.knownChats.none { it.id == 30L || it.id == 31L })

        fixture.search(ChatSearchQuery("old selection"))
        val pending = fixture.session.searches.last()
        fixture.select(11)
        fixture.complete(pending, ChatSearchPage(chats = listOf(chat(32, 0)), messages = listOf(message(2))))
        assertEquals(11L, fixture.state.token.chatId)
        assertTrue(fixture.state.search.messages.isEmpty())
        assertTrue(fixture.state.knownChats.none { it.id == 32L })
    }

    @Test
    fun searchCanSelectARealChatOutsideTheActiveListButCannotSelectAnUnlistedResult() {
        val fixture = selectedFixture()
        val archived = chat(30, 0).copy(positions = mapOf(ChatListRef(-1) to 300))
        fixture.search(ChatSearchQuery("archived"))
        fixture.complete(fixture.session.searches.last(), ChatSearchPage(chats = listOf(archived)))
        fixture.controller.selectSearchChat(fixture.state.token, 11)
        assertEquals(10L, fixture.state.token.chatId)
        fixture.controller.selectSearchChat(fixture.state.token, 30)
        fixture.flush()
        assertEquals(30L, fixture.state.token.chatId)
        assertEquals(ChatListRef(), fixture.state.activeList)
        assertTrue(fixture.state.items.none { it.id == 30L })
        assertTrue(30L in fixture.session.opened)
    }

    @Test
    fun aMessageIdInAnotherChatDoesNotAuthorizeOpeningIt() {
        val fixture = selectedFixture()
        fixture.search(ChatSearchQuery("target"))
        fixture.complete(fixture.session.searches.last(), ChatSearchPage(messages = listOf(message(50))))
        fixture.controller.openSearchMessage(fixture.state.token, 11, 50)
        assertTrue(fixture.session.around.isEmpty())
        assertEquals(10L, fixture.state.token.chatId)
        fixture.controller.openSearchMessage(fixture.state.token, 10, 50)
        assertEquals(10L, fixture.session.around.single().chatId)
        assertEquals(50L, fixture.session.around.single().messageId)
    }

    @Test
    fun detailsRejectMismatchedIdentityAndIgnoreLateResultsFromAnotherSelection() {
        val fixture = selectedFixture()
        fixture.controller.loadChatDetails(fixture.state.token)
        fixture.session.details.last().done(ChatResult(ChatDetails(11, "other chat")))
        fixture.flush()
        assertNull(fixture.state.details.details)
        assertEquals(ChatError.REQUEST_FAILED, fixture.state.details.error)
        fixture.controller.loadChatDetails(fixture.state.token)
        val pending = fixture.session.details.last()
        fixture.select(11)
        pending.done(ChatResult(ChatDetails(10, "late")))
        fixture.flush()
        assertEquals(ChatDetailsState(), fixture.state.details)
        fixture.controller.loadChatDetails(fixture.state.token)
        fixture.session.details.last().done(ChatResult(ChatDetails(11, "current")))
        fixture.flush()
        assertEquals("current", fixture.state.details.details?.description)
    }

    @Test
    fun pinnedPaginationUsesOnlyPositiveCurrentChatMessagesAndAForwardCursor() {
        val fixture = selectedFixture()
        fixture.controller.loadPinned(fixture.state.token)
        fixture.complete(fixture.session.pins.last(), ChatSearchPage(
            messages = listOf(message(50), message(50, 11), message(0)), nextOffset = "40", totalCount = 2))
        assertEquals(listOf(50L), fixture.state.pinned.messages.map { it.id })
        fixture.controller.loadPinned(fixture.state.token, more = true)
        assertEquals(40L, fixture.session.pins.last().from)
        fixture.complete(fixture.session.pins.last(), ChatSearchPage(
            messages = listOf(message(50), message(40)), nextOffset = "40", totalCount = 2))
        assertEquals(listOf(50L, 40L), fixture.state.pinned.messages.map { it.id })
        assertEquals(0L, fixture.state.pinned.nextFromMessageId)
        fixture.controller.loadPinned(fixture.state.token)
        fixture.complete(fixture.session.pins.last(), ChatSearchPage(nextOffset = "-10", totalCount = -5))
        assertEquals(0L, fixture.state.pinned.nextFromMessageId)
        assertEquals(0, fixture.state.pinned.totalCount)
    }

    @Test
    fun aNativePinChangeDuringLoadingDiscardsTheOldPageAndCoalescesARefresh() {
        val fixture = selectedFixture()
        fixture.controller.loadPinned(fixture.state.token)
        val first = fixture.session.pins.last()
        val count = fixture.session.pins.size
        fixture.event(ChatEvent.PinnedChanged(11))
        fixture.event(ChatEvent.PinnedChanged(10))
        fixture.event(ChatEvent.PinnedChanged(10))
        assertEquals(count, fixture.session.pins.size)
        fixture.complete(first, ChatSearchPage(messages = listOf(message(10)), totalCount = 1))
        assertTrue(fixture.state.pinned.messages.isEmpty())
        assertTrue(fixture.state.pinned.loading)
        assertEquals(count + 1, fixture.session.pins.size)
        assertEquals(0L, fixture.session.pins.last().from)
        fixture.complete(fixture.session.pins.last(), ChatSearchPage(messages = listOf(message(20)), totalCount = 1))
        assertEquals(listOf(20L), fixture.state.pinned.messages.map { it.id })
        assertFalse(fixture.state.pinned.loading)
    }

    @Test
    fun deletionRemovesPinnedPreviewsAndCannotBeUndoneByAnOlderPinRequest() {
        val fixture = selectedFixture()
        fixture.controller.loadPinned(fixture.state.token)
        fixture.event(ChatEvent.Deleted(10, listOf(20), permanent = false))
        fixture.event(ChatEvent.Deleted(10, listOf(21), permanent = true))
        fixture.complete(fixture.session.pins.last(), ChatSearchPage(
            messages = listOf(message(20), message(21), message(22)), totalCount = 3))
        assertEquals(listOf(22L), fixture.state.pinned.messages.map { it.id })
        fixture.event(ChatEvent.Deleted(11, listOf(22), permanent = true))
        assertEquals(listOf(22L), fixture.state.pinned.messages.map { it.id })
        fixture.event(ChatEvent.Deleted(10, listOf(22), permanent = true))
        assertTrue(fixture.state.pinned.messages.isEmpty())
    }

    @Test
    fun historyAroundPreservesContentEditsReactionsAndDeletionEventsNewerThanItsRequest() {
        val fixture = selectedFixture()
        fixture.search(ChatSearchQuery("target"))
        fixture.complete(fixture.session.searches.last(), ChatSearchPage(messages = listOf(message(100))))
        fixture.controller.openSearchMessage(fixture.state.token, 10, 100)
        val pending = fixture.session.around.last()
        fixture.event(ChatEvent.Content(10, 100, "edited content", MessageKind.TEXT))
        fixture.event(ChatEvent.Edited(10, 100, 9))
        val reactions = listOf(MessageReaction("heart", 3))
        fixture.event(ChatEvent.Reactions(10, 100, reactions))
        fixture.event(ChatEvent.Deleted(10, listOf(99), permanent = false))
        pending.done(ChatResult(listOf(message(100), message(99), message(100, 11), message(-1))))
        fixture.flush()
        val current = fixture.state.messages.single()
        assertEquals(100L, current.id)
        assertEquals("edited content", current.text)
        assertEquals(9, current.editDate)
        assertEquals(reactions, current.reactions)
        assertEquals(100L, fixture.state.jumpMessageId)
    }

    @Test
    fun aDeletedJumpTargetCannotClearTheCurrentTimelineOrReturnInHistoryAround() {
        val fixture = selectedFixture()
        fixture.event(ChatEvent.NewMessage(message(200)))
        fixture.search(ChatSearchQuery("target"))
        fixture.complete(fixture.session.searches.last(), ChatSearchPage(messages = listOf(message(100))))
        fixture.controller.openSearchMessage(fixture.state.token, 10, 100)
        fixture.event(ChatEvent.Deleted(10, listOf(100), permanent = true))
        fixture.session.around.last().done(ChatResult(listOf(message(100))))
        fixture.flush()
        assertEquals(listOf(200L), fixture.state.messages.map { it.id })
        assertNull(fixture.state.jumpMessageId)
        assertEquals(ChatError.REQUEST_FAILED, fixture.state.historyError)
    }

    @Test
    fun returningToLatestCancelsOldNavigationBeforeItsLateResponse() {
        val fixture = selectedFixture()
        fixture.search(ChatSearchQuery("target"))
        fixture.complete(fixture.session.searches.last(), ChatSearchPage(messages = listOf(message(100))))
        fixture.controller.openSearchMessage(fixture.state.token, 10, 100)
        val pending = fixture.session.around.last()
        val previous = fixture.state.token
        fixture.controller.returnToLatest(previous)
        fixture.flush()
        fixture.event(ChatEvent.NewMessage(message(200)))
        pending.done(ChatResult(listOf(message(100))))
        fixture.flush()
        assertTrue(fixture.state.token.selection > previous.selection)
        assertEquals(listOf(200L), fixture.state.messages.map { it.id })
        assertNull(fixture.state.jumpMessageId)
    }

    @Test
    fun jumpingToAnAlreadyLoadedMessageCancelsAnotherPendingJump() {
        val fixture = selectedFixture()
        fixture.event(ChatEvent.NewMessage(message(200)))
        fixture.search(ChatSearchQuery("targets"))
        fixture.complete(fixture.session.searches.last(), ChatSearchPage(messages = listOf(message(100), message(200))))
        fixture.controller.openSearchMessage(fixture.state.token, 10, 100)
        val pending = fixture.session.around.last()
        val historyCount = fixture.session.histories.size
        fixture.controller.loadOlder(fixture.state.token)
        assertEquals(historyCount, fixture.session.histories.size)
        fixture.controller.openSearchMessage(fixture.state.token, 10, 200)
        assertFalse(fixture.state.historyLoading)
        pending.done(ChatResult(listOf(message(100))))
        fixture.flush()
        assertEquals(200L, fixture.state.jumpMessageId)
        assertEquals(listOf(200L), fixture.state.messages.map { it.id })
    }

    @Test
    fun retiredControllersIgnoreAllPendingToolCompletions() {
        val fixture = selectedFixture()
        fixture.search(ChatSearchQuery("pending"))
        fixture.controller.searchOverview(fixture.state.token)
        fixture.controller.loadChatDetails(fixture.state.token)
        fixture.controller.loadPinned(fixture.state.token)
        val before = fixture.state
        val publishes = fixture.publishes
        fixture.controller.deactivate()
        fixture.complete(fixture.session.searches.last(), ChatSearchPage(messages = listOf(message(1))))
        fixture.session.overviews.last()(ChatResult(listOf(chat(30, 0)) to emptyList()))
        fixture.session.details.last().done(ChatResult(ChatDetails(10, "late")))
        fixture.complete(fixture.session.pins.last(), ChatSearchPage(messages = listOf(message(2))))
        fixture.flush()
        assertEquals(publishes, fixture.publishes)
        assertEquals(before, fixture.state)
    }

    @Test
    fun onlyValidatedSearchAndPinnedMediaGrantThumbnailDownloadAccess() {
        val fixture = selectedFixture()
        fixture.search(ChatSearchQuery("media", chatId = 10))
        fixture.complete(fixture.session.searches.last(), ChatSearchPage(
            chats = listOf(chat(30, 0).copy(avatar = MediaFile(3, canDownload = true))),
            messages = listOf(
                message(20).copy(media = MessageMedia(MediaFile(1, canDownload = true), MediaFile(2, canDownload = true))),
                message(20, 11).copy(media = MessageMedia(MediaFile(4, canDownload = true))),
                message(0).copy(media = MessageMedia(MediaFile(5, canDownload = true))),
            )))
        (1..5).forEach { fixture.controller.downloadFile(fixture.state.token, it) }
        fixture.flush()
        assertEquals(listOf(1, 2, 3), fixture.session.downloads)
        fixture.controller.loadPinned(fixture.state.token)
        fixture.complete(fixture.session.pins.last(), ChatSearchPage(messages = listOf(
            message(21).copy(media = MessageMedia(MediaFile(6, canDownload = true))),
            message(21, 11).copy(media = MessageMedia(MediaFile(7, canDownload = true))),
        )))
        fixture.controller.downloadFile(fixture.state.token, 6)
        fixture.controller.downloadFile(fixture.state.token, 7)
        fixture.flush()
        assertEquals(listOf(1, 2, 3, 6), fixture.session.downloads)
        assertTrue(fixture.state.mediaFiles.keys.containsAll(listOf(1, 2, 3, 6)))
        assertTrue(fixture.state.mediaFiles.keys.intersect(setOf(4, 5, 7)).isEmpty())
    }

    @Test
    fun overviewPublishesLoadingAndTypedFailureWhileKeepingPreviouslyLoadedRecords() {
        val fixture = Fixture()
        fixture.controller.searchOverview(fixture.state.token)
        assertTrue(fixture.state.search.overviewLoading)
        assertFalse(fixture.state.search.loading)
        fixture.controller.searchOverview(fixture.state.token)
        assertEquals(1, fixture.session.overviews.size)
        fixture.session.overviews.last()(ChatResult(listOf(chat(10, 100)) to listOf(chat(11, 90))))
        fixture.flush()
        assertFalse(fixture.state.search.overviewLoading)
        assertNull(fixture.state.search.error)
        fixture.controller.searchOverview(fixture.state.token)
        fixture.session.overviews.last()(ChatResult(error = ChatError.FORBIDDEN))
        fixture.flush()
        assertFalse(fixture.state.search.overviewLoading)
        assertEquals(ChatError.FORBIDDEN, fixture.state.search.error)
        assertEquals(listOf(10L), fixture.state.search.frequent.map { it.id })
        assertEquals(listOf(11L), fixture.state.search.recent.map { it.id })
        fixture.controller.searchOverview(fixture.state.token)
        assertTrue(fixture.state.search.overviewLoading)
        assertNull(fixture.state.search.error)
    }

    @Test
    fun aQueryAndAnOverviewCannotCompleteIntoEachOthersLoadingOrErrorState() {
        val fixture = Fixture()
        fixture.controller.searchOverview(fixture.state.token)
        val overview = fixture.session.overviews.last()
        fixture.search(ChatSearchQuery("new query"))
        overview(ChatResult(error = ChatError.FORBIDDEN))
        fixture.flush()
        assertTrue(fixture.state.search.loading)
        assertFalse(fixture.state.search.overviewLoading)
        assertNull(fixture.state.search.error)
        val query = fixture.session.searches.last()
        fixture.controller.searchOverview(fixture.state.token)
        fixture.complete(query, ChatSearchPage(messages = listOf(message(1))))
        assertTrue(fixture.state.search.overviewLoading)
        assertFalse(fixture.state.search.loading)
        assertEquals(ChatSearchQuery(), fixture.state.search.query)
        assertTrue(fixture.state.search.messages.isEmpty())
    }

    @Test
    fun onlyTheExplicitClearActionClearsRecentRecordsAfterNativeSuccess() {
        val fixture = selectedFixture()
        fixture.overview(listOf(chat(10, 100)), listOf(chat(11, 90)))
        assertTrue(fixture.session.clears.isEmpty())
        fixture.controller.clearRecentSearch(fixture.state.token)
        fixture.controller.clearRecentSearch(fixture.state.token)
        assertEquals(1, fixture.session.clears.size)
        assertTrue(fixture.state.search.recentClearing)
        assertEquals(listOf(11L), fixture.state.search.recent.map { it.id })
        fixture.session.clears.last()(ChatResult(error = ChatError.FORBIDDEN))
        fixture.flush()
        assertFalse(fixture.state.search.recentClearing)
        assertEquals(ChatError.FORBIDDEN, fixture.state.search.error)
        assertEquals(listOf(11L), fixture.state.search.recent.map { it.id })
        fixture.controller.clearRecentSearch(fixture.state.token)
        fixture.session.clears.last()(ChatResult(Unit))
        fixture.flush()
        assertTrue(fixture.state.search.recent.isEmpty())
        assertEquals(listOf(10L), fixture.state.search.frequent.map { it.id })
        assertNull(fixture.state.search.error)
    }

    @Test
    fun anOverviewStartedBeforeClearCannotRepopulateRecentlyFoundChats() {
        val fixture = selectedFixture()
        fixture.overview(recent = listOf(chat(11, 90)))
        fixture.controller.searchOverview(fixture.state.token)
        val oldOverview = fixture.session.overviews.last()
        fixture.controller.clearRecentSearch(fixture.state.token)
        assertFalse(fixture.state.search.overviewLoading)
        val requests = fixture.session.overviews.size
        fixture.controller.searchOverview(fixture.state.token)
        assertEquals(requests, fixture.session.overviews.size)
        fixture.session.clears.last()(ChatResult(Unit))
        oldOverview(ChatResult(emptyList<ChatItem>() to listOf(chat(11, 90))))
        fixture.flush()
        assertTrue(fixture.state.search.recent.isEmpty())
        assertFalse(fixture.state.search.recentClearing)
    }

    @Test
    fun selectingASearchChatAddsItOnlyAfterNavigationAndFailureDoesNotUndoSelection() {
        val fixture = selectedFixture()
        fixture.search(ChatSearchQuery("chat"))
        fixture.complete(fixture.session.searches.last(), ChatSearchPage(chats = listOf(chat(11, 90))))
        assertTrue(fixture.session.adds.isEmpty())
        fixture.controller.selectSearchChat(fixture.state.token, 11)
        fixture.flush()
        assertEquals(11L, fixture.state.token.chatId)
        assertEquals(11L, fixture.session.adds.single().chatId)
        assertTrue(fixture.state.search.recent.isEmpty())
        fixture.session.adds.last().done(ChatResult(error = ChatError.FORBIDDEN))
        fixture.flush()
        assertEquals(11L, fixture.state.token.chatId)
        assertNull(fixture.state.search.error)
        fixture.controller.selectSearchChat(fixture.state.token, 11)
        fixture.session.adds.last().done(ChatResult(Unit))
        fixture.flush()
        assertEquals(listOf(11L), fixture.state.search.recent.map { it.id })
    }

    @Test
    fun searchMessagesEnterRecentHistoryOnlyWhenTheirTargetWasActuallyOpened() {
        val fixture = selectedFixture()
        fixture.event(ChatEvent.NewMessage(message(200)))
        fixture.search(ChatSearchQuery("targets"))
        fixture.complete(fixture.session.searches.last(), ChatSearchPage(messages = listOf(message(100), message(200))))
        fixture.controller.openSearchMessage(fixture.state.token, 11, 200)
        assertTrue(fixture.session.adds.isEmpty())
        fixture.controller.openSearchMessage(fixture.state.token, 10, 100)
        assertTrue(fixture.session.adds.isEmpty())
        fixture.session.around.last().done(ChatResult(error = ChatError.FORBIDDEN))
        fixture.flush()
        assertTrue(fixture.session.adds.isEmpty())
        fixture.controller.openSearchMessage(fixture.state.token, 10, 100)
        fixture.session.around.last().done(ChatResult(listOf(message(100))))
        fixture.flush()
        assertEquals(10L, fixture.session.adds.single().chatId)
        assertEquals(100L, fixture.state.jumpMessageId)
        fixture.session.adds.last().done(ChatResult(error = ChatError.FORBIDDEN))
        fixture.flush()
        assertEquals(100L, fixture.state.jumpMessageId)
        fixture.controller.openHistoryMessage(fixture.state.token, 100)
        fixture.controller.loadPinned(fixture.state.token)
        fixture.complete(fixture.session.pins.last(), ChatSearchPage(messages = listOf(message(100))))
        fixture.controller.openPinnedMessage(fixture.state.token, 100)
        assertEquals(1, fixture.session.adds.size)
        fixture.controller.openSearchMessage(fixture.state.token, 10, 100)
        assertEquals(2, fixture.session.adds.size)
    }

    @Test
    fun clearDiscardsOldAddCompletionsButRetainsConfirmedSearchesMadeAfterItStarted() {
        val fixture = selectedFixture()
        fixture.overview(recent = listOf(chat(11, 90)))
        fixture.event(ChatEvent.NewMessage(message(100)))
        fixture.search(ChatSearchQuery("loaded"))
        fixture.complete(fixture.session.searches.last(), ChatSearchPage(messages = listOf(message(100))))
        fixture.controller.openSearchMessage(fixture.state.token, 10, 100)
        val oldAdd = fixture.session.adds.last()
        fixture.controller.clearRecentSearch(fixture.state.token)
        oldAdd.done(ChatResult(Unit))
        fixture.flush()
        assertEquals(listOf(11L), fixture.state.search.recent.map { it.id })
        fixture.controller.openSearchMessage(fixture.state.token, 10, 100)
        fixture.session.adds.last().done(ChatResult(Unit))
        fixture.flush()
        fixture.session.clears.last()(ChatResult(Unit))
        fixture.flush()
        assertEquals(listOf(10L), fixture.state.search.recent.map { it.id })
    }

    @Test
    fun lateAddAndClearCallbacksCannotCrossSelectionOrADeactivatedGeneration() {
        val fixture = selectedFixture()
        fixture.overview(recent = listOf(chat(11, 90)))
        fixture.event(ChatEvent.NewMessage(message(100)))
        fixture.search(ChatSearchQuery("loaded"))
        fixture.complete(fixture.session.searches.last(), ChatSearchPage(messages = listOf(message(100))))
        fixture.controller.openSearchMessage(fixture.state.token, 10, 100)
        val add = fixture.session.adds.last()
        fixture.controller.clearRecentSearch(fixture.state.token)
        val clear = fixture.session.clears.last()
        fixture.select(11)
        add.done(ChatResult(Unit))
        clear(ChatResult(Unit))
        fixture.flush()
        assertEquals(listOf(11L), fixture.state.search.recent.map { it.id })
        assertFalse(fixture.state.search.recentClearing)
        fixture.controller.clearRecentSearch(fixture.state.token)
        val pending = fixture.session.clears.last()
        val publishes = fixture.publishes
        fixture.controller.deactivate()
        pending(ChatResult(Unit))
        fixture.flush()
        assertEquals(publishes, fixture.publishes)
        assertEquals(listOf(11L), fixture.state.search.recent.map { it.id })
    }

    @Test
    fun actualRecentReactionUsersExposeOnlyAlreadyKnownAvatarFilesWithoutExtraLookups() {
        val fixture = selectedFixture()
        val avatar = MediaFile(30, canDownload = true)
        fixture.event(ChatEvent.SenderName(7, "synthetic user", profile = AccountProfile(7, "synthetic user", avatar = avatar)))
        assertTrue(fixture.state.senderAvatars.isEmpty())
        fixture.event(ChatEvent.NewMessage(message(100).copy(reactions = listOf(MessageReaction("heart", 1,
            recentSenders = listOf(MessageReactionSender(7)))))))
        assertEquals(avatar, fixture.state.senderAvatars[7])
        assertEquals(avatar, fixture.state.mediaFiles[30])
        fixture.event(ChatEvent.Reactions(10, 100, listOf(MessageReaction("heart", 2,
            recentSenders = listOf(MessageReactionSender(8), MessageReactionSender(7, senderIsChat = true))))))
        assertNull(fixture.state.senderAvatars[8])
        fixture.event(ChatEvent.SenderName(8, "another synthetic user", profile = AccountProfile(8, "another synthetic user",
            avatar = MediaFile(31, canDownload = true))))
        assertEquals(31, fixture.state.senderAvatars[8]?.id)
        assertTrue(fixture.session.downloads.isEmpty())
    }

    @Test
    fun repeatedlyOpeningTheSameLoadedSearchOrPinnedMessageAlwaysPublishesANewJumpRevision() {
        val fixture = selectedFixture()
        fixture.event(ChatEvent.NewMessage(message(200)))
        fixture.search(ChatSearchQuery("loaded target"))
        fixture.complete(fixture.session.searches.last(), ChatSearchPage(messages = listOf(message(200))))
        fixture.controller.loadPinned(fixture.state.token)
        fixture.complete(fixture.session.pins.last(), ChatSearchPage(messages = listOf(message(200))))
        val initial = fixture.state.jumpRevision
        fixture.controller.openSearchMessage(fixture.state.token, 10, 200)
        assertEquals(initial + 1, fixture.state.jumpRevision)
        assertEquals(200L, fixture.state.jumpMessageId)
        fixture.controller.openSearchMessage(fixture.state.token, 10, 200)
        assertEquals(initial + 2, fixture.state.jumpRevision)
        assertEquals(200L, fixture.state.jumpMessageId)
        fixture.controller.openPinnedMessage(fixture.state.token, 200)
        assertEquals(initial + 3, fixture.state.jumpRevision)
        assertTrue(fixture.session.around.isEmpty())
    }

    private class Fixture {
        var state = ChatSnapshot()
        var publishes = 0
        private val queued = ArrayDeque<() -> Unit>()
        val session = FakeSession()
        val controller = ChatController(1, session, queued::addLast, { state = it; publishes++ })

        init {
            controller.start()
            flush()
            event(ChatEvent.NewChat(chat(10, 100)))
            event(ChatEvent.NewChat(chat(11, 90)))
        }

        fun select(chatId: Long) {
            controller.select(state.token, chatId)
            flush()
            // Normal selection may eagerly load pinned messages; tests begin with a settled empty page.
            session.pins.filterNot { it.completed }.forEach { complete(it, ChatSearchPage()) }
        }

        fun search(query: ChatSearchQuery) = controller.search(state.token, query)
        fun overview(frequent: List<ChatItem> = emptyList(), recent: List<ChatItem> = emptyList()) {
            controller.searchOverview(state.token)
            session.overviews.last()(ChatResult(frequent to recent))
            flush()
        }
        fun event(event: ChatEvent) = controller.event(event)
        fun flush() { while (queued.isNotEmpty()) queued.removeFirst()() }
        fun complete(call: SearchCall, page: ChatSearchPage) { call.done(ChatResult(page)); flush() }
        fun complete(call: PinnedCall, page: ChatSearchPage) { call.completed = true; call.done(ChatResult(page)); flush() }
    }

    private class FakeSession : TdChatSession {
        val searches = mutableListOf<SearchCall>()
        val overviews = mutableListOf<(ChatResult<Pair<List<ChatItem>, List<ChatItem>>>) -> Unit>()
        val details = mutableListOf<DetailsCall>()
        val pins = mutableListOf<PinnedCall>()
        val around = mutableListOf<AroundCall>()
        val histories = mutableListOf<Pair<Long, Long>>()
        val opened = mutableListOf<Long>()
        val downloads = mutableListOf<Int>()
        val adds = mutableListOf<AddCall>()
        val clears = mutableListOf<(ChatResult<Unit>) -> Unit>()

        override fun search(query: ChatSearchQuery, offset: String, result: (ChatResult<ChatSearchPage>) -> Unit) {
            searches += SearchCall(query, offset, result)
        }
        override fun searchOverview(result: (ChatResult<Pair<List<ChatItem>, List<ChatItem>>>) -> Unit) { overviews += result }
        override fun addRecentlyFoundChat(chatId: Long, result: (ChatResult<Unit>) -> Unit) { adds += AddCall(chatId, result) }
        override fun clearRecentlyFoundChats(result: (ChatResult<Unit>) -> Unit) { clears += result }
        override fun chatDetails(chat: ChatItem, result: (ChatResult<ChatDetails>) -> Unit) { details += DetailsCall(chat.id, result) }
        override fun pinnedMessages(chatId: Long, fromMessageId: Long, result: (ChatResult<ChatSearchPage>) -> Unit) {
            pins += PinnedCall(chatId, fromMessageId, result)
        }
        override fun historyAround(chatId: Long, messageId: Long, result: (ChatResult<List<ChatMessage>>) -> Unit) {
            around += AroundCall(chatId, messageId, result)
        }
        override fun loadChats(limit: Int, result: (ChatResult<ChatLoadResult>) -> Unit) { result(ChatResult(ChatLoadResult(endReached = true))) }
        override fun history(chatId: Long, fromMessageId: Long, limit: Int, result: (ChatResult<List<ChatMessage>>) -> Unit) {
            histories += chatId to fromMessageId
            result(ChatResult(emptyList()))
        }
        override fun open(chatId: Long) { opened += chatId }
        override fun download(fileId: Int, result: (ChatResult<MediaFile>) -> Unit) {
            downloads += fileId
            result(ChatResult(MediaFile(fileId, downloaded = true)))
        }
        override fun close(chatId: Long) = Unit
        override fun sendText(chatId: Long, text: String, result: (ChatResult<ChatMessage>) -> Unit) = Unit
        override fun retry(chatId: Long, messageId: Long, result: (ChatResult<List<ChatMessage>>) -> Unit) = Unit
        override fun markRead(chatId: Long, messageIds: List<Long>, result: (ChatResult<Unit>) -> Unit) = Unit
    }

    private data class SearchCall(val query: ChatSearchQuery, val offset: String, val done: (ChatResult<ChatSearchPage>) -> Unit)
    private data class DetailsCall(val chatId: Long, val done: (ChatResult<ChatDetails>) -> Unit)
    private data class AddCall(val chatId: Long, val done: (ChatResult<Unit>) -> Unit)
    private data class AroundCall(val chatId: Long, val messageId: Long, val done: (ChatResult<List<ChatMessage>>) -> Unit)
    private data class PinnedCall(val chatId: Long, val from: Long, val done: (ChatResult<ChatSearchPage>) -> Unit, var completed: Boolean = false)

    private companion object {
        fun selectedFixture() = Fixture().apply { select(10) }
        fun chat(id: Long, order: Long) = ChatItem(id, "synthetic chat", order = order)
        fun message(id: Long, chatId: Long = 10) = ChatMessage(id, chatId, text = "synthetic content")
    }
}
