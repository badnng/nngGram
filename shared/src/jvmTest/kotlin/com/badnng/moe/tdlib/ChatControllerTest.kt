package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import kotlin.test.*

class ChatControllerTest {
    @Test
    fun mainListSortsByOrderAndIdAndRemovalKeepsSelectedChat() {
        val fixture = Fixture()
        fixture.chat(1, 100)
        fixture.chat(2, 200)
        fixture.chat(3, 200)
        assertEquals(listOf(3L, 2L, 1L), fixture.state.items.map { it.id })
        fixture.select(2)
        fixture.event(ChatEvent.Position(2, 0))
        fixture.event(ChatEvent.Inbox(2, 7))
        fixture.event(ChatEvent.Title(2, "changed"))
        assertEquals(listOf(3L, 1L), fixture.state.items.map { it.id })
        assertEquals("changed", fixture.state.selected?.title)
        assertEquals(7, fixture.state.selected?.unreadCount)
    }

    @Test
    fun listPagesContinueWithoutNewItemsUntilExplicitEndAndRepeatedStartIsIdempotent() {
        val fixture = Fixture()
        fixture.controller.start()
        fixture.controller.loadChats(fixture.state.token)
        assertEquals(1, fixture.transport.loads.size)
        repeat(3) { index ->
            fixture.transport.loads[index](ChatResult(ChatLoadResult()))
            fixture.flush()
            assertEquals(index + 2, fixture.transport.loads.size)
            assertTrue(fixture.state.items.isEmpty())
            assertTrue(fixture.state.listLoading)
            assertFalse(fixture.state.listEnd)
        }
        fixture.transport.loads.last()(ChatResult(ChatLoadResult(endReached = true)))
        fixture.flush()
        fixture.controller.loadChats(fixture.state.token)
        fixture.controller.start()
        assertEquals(4, fixture.transport.loads.size)
        assertTrue(fixture.state.listEnd)
        assertFalse(fixture.state.listLoading)
    }

    @Test
    fun selectingAChatDoesNotInterruptAutomaticListLoading() {
        val fixture = Fixture()
        val initialToken = fixture.state.token
        fixture.chat(1)
        fixture.select(1)
        assertNotEquals(initialToken, fixture.state.token)

        fixture.transport.loads.single()(ChatResult(ChatLoadResult()))
        fixture.flush()
        assertEquals(2, fixture.transport.loads.size)
        assertTrue(fixture.state.listLoading)
        assertEquals(1L, fixture.state.selected?.id)
        fixture.transport.loads.last()(ChatResult(ChatLoadResult(endReached = true)))
        fixture.flush()
        assertTrue(fixture.state.listEnd)
        assertFalse(fixture.state.listLoading)
        assertEquals(1L, fixture.state.selected?.id)
        assertEquals(1, fixture.transport.histories.size)
    }

    @Test
    fun mainArchiveAndFolderListsContinueIndependentlyAfterSwitchingAway() {
        val fixture = Fixture()
        val main = ChatListRef()
        val folder = ChatListRef(7)
        val archive = ChatListRef(-1)
        fixture.event(ChatEvent.Folders(listOf(ChatFolderInfo(7, "Work"))))
        fixture.controller.selectList(fixture.state.token, folder)
        fixture.controller.selectList(fixture.state.token, archive)
        assertEquals(listOf(main, folder, archive), fixture.transport.loadedLists)

        repeat(3) { index ->
            fixture.transport.loads[index](ChatResult(ChatLoadResult()))
            fixture.flush()
        }
        assertEquals(listOf(main, folder, archive, main, folder, archive), fixture.transport.loadedLists)
        assertEquals(archive, fixture.state.activeList)
        assertTrue(fixture.state.listLoading)

        for (index in 3..4) {
            fixture.transport.loads[index](ChatResult(ChatLoadResult(endReached = true)))
            fixture.flush()
            assertFalse(fixture.state.listEnd)
            assertTrue(fixture.state.listLoading)
        }
        fixture.transport.loads[5](ChatResult(ChatLoadResult(endReached = true)))
        fixture.flush()
        assertTrue(fixture.state.listEnd)
        assertFalse(fixture.state.listLoading)
        fixture.controller.selectList(fixture.state.token, main)
        assertTrue(fixture.state.listEnd)
        fixture.controller.selectList(fixture.state.token, folder)
        assertTrue(fixture.state.listEnd)
        assertEquals(6, fixture.transport.loads.size)
    }

    @Test
    fun duplicateListCallbacksCannotContinueOrFinishTheNextPage() {
        val fixture = Fixture()
        val first = fixture.transport.loads.single()
        first(ChatResult(ChatLoadResult()))
        fixture.flush()
        assertEquals(2, fixture.transport.loads.size)

        first(ChatResult(ChatLoadResult(endReached = true)))
        first(ChatResult(error = ChatError.REQUEST_FAILED))
        fixture.flush()
        assertEquals(2, fixture.transport.loads.size)
        assertTrue(fixture.state.listLoading)
        assertFalse(fixture.state.listEnd)
        assertNull(fixture.state.listError)

        fixture.transport.loads.last()(ChatResult(ChatLoadResult(endReached = true)))
        fixture.flush()
        assertTrue(fixture.state.listEnd)
    }

    @Test
    fun deactivatedControllerCannotContinueListLoadingOrPublishLateResults() {
        val fixture = Fixture()
        val published = fixture.state
        val pending = fixture.transport.loads.single()
        fixture.controller.deactivate()
        pending(ChatResult(ChatLoadResult()))
        fixture.flush()
        fixture.controller.loadChats(published.token)
        fixture.controller.start()
        assertEquals(1, fixture.transport.loads.size)
        assertSame(published, fixture.state)
    }

    @Test
    fun listErrorsKeepPartialChatsAndStopUntilAnExplicitRetryContinuesLoading() {
        val fixture = Fixture()
        fixture.chat(1)
        fixture.transport.loads.single()(ChatResult(ChatLoadResult()))
        fixture.flush()
        val failed = fixture.transport.loads.last()
        failed(ChatResult(error = ChatError.RATE_LIMITED))
        fixture.flush()
        assertEquals(2, fixture.transport.loads.size)
        assertEquals(listOf(1L), fixture.state.items.map { it.id })
        assertFalse(fixture.state.listLoading)
        assertFalse(fixture.state.listEnd)
        assertEquals(ChatError.RATE_LIMITED, fixture.state.listError)

        failed(ChatResult(ChatLoadResult()))
        fixture.flush()
        assertEquals(2, fixture.transport.loads.size)
        assertEquals(ChatError.RATE_LIMITED, fixture.state.listError)

        fixture.controller.loadChats(fixture.state.token)
        assertEquals(3, fixture.transport.loads.size)
        assertTrue(fixture.state.listLoading)
        assertNull(fixture.state.listError)
        fixture.transport.loads.last()(ChatResult(ChatLoadResult()))
        fixture.flush()
        assertEquals(4, fixture.transport.loads.size)
        fixture.transport.loads.last()(ChatResult(ChatLoadResult(endReached = true)))
        fixture.flush()
        assertTrue(fixture.state.listEnd)
        assertEquals(listOf(1L), fixture.state.items.map { it.id })
    }

    @Test
    fun removedFolderSuccessCannotRestartLoadingOrFinishARecreatedFolder() {
        val fixture = Fixture()
        val folder = ChatListRef(7)
        fixture.event(ChatEvent.Folders(listOf(ChatFolderInfo(7, "Work"))))
        fixture.controller.selectList(fixture.state.token, folder)
        val removedFolderPage = fixture.transport.loads.last()
        fixture.event(ChatEvent.Folders(emptyList()))
        assertEquals(ChatListRef(), fixture.state.activeList)
        removedFolderPage(ChatResult(ChatLoadResult()))
        fixture.flush()
        assertEquals(2, fixture.transport.loads.size)
        assertEquals(listOf(ChatListRef(), folder), fixture.transport.loadedLists)

        fixture.event(ChatEvent.Folders(listOf(ChatFolderInfo(7, "Work"))))
        fixture.controller.selectList(fixture.state.token, folder)
        assertEquals(3, fixture.transport.loads.size)
        removedFolderPage(ChatResult(ChatLoadResult(endReached = true)))
        fixture.flush()
        assertTrue(fixture.state.listLoading)
        assertFalse(fixture.state.listEnd)
        fixture.transport.loads.last()(ChatResult(ChatLoadResult(endReached = true)))
        fixture.flush()
        assertTrue(fixture.state.listEnd)
        assertEquals(3, fixture.transport.loads.size)
    }

    @Test
    fun lateHistoryAndReadCallbacksCannotPolluteAnotherSelection() {
        val fixture = Fixture()
        fixture.chat(1)
        fixture.chat(2)
        fixture.select(1)
        val token = fixture.state.token
        fixture.event(ChatEvent.NewMessage(message(10)))
        fixture.controller.markRead(token)
        fixture.select(2)
        fixture.transport.histories[0].result(ChatResult(listOf(message(9))))
        fixture.transport.reads.single().result(ChatResult(error = ChatError.FORBIDDEN))
        fixture.flush()
        assertEquals(2L, fixture.state.selected?.id)
        assertTrue(fixture.state.historyLoading)
        assertTrue(fixture.state.messages.isEmpty())
        assertNull(fixture.state.readError)
        fixture.controller.sendText(token, "stale input")
        assertTrue(fixture.transport.sends.isEmpty())
        assertEquals(listOf(1L, 2L), fixture.transport.opened)
        assertEquals(listOf(1L), fixture.transport.closed)
    }

    @Test
    fun shortHistoryPagesContinueUntilEmptyAndDeduplicateLiveMessages() {
        val fixture = selectedFixture()
        fixture.event(ChatEvent.NewMessage(message(30)))
        fixture.history(listOf(message(30), message(20)))
        assertEquals(listOf(20L, 30L), fixture.state.messages.map { it.id })
        assertFalse(fixture.state.historyEnd)
        assertTrue(fixture.state.historyLoading)
        assertEquals(2, fixture.transport.histories.size)
        fixture.controller.loadOlder(fixture.state.token)
        assertEquals(2, fixture.transport.histories.size)
        assertEquals(20L, fixture.transport.histories.last().from)
        fixture.history(listOf(message(10)))
        assertFalse(fixture.state.historyEnd)
        fixture.controller.loadOlder(fixture.state.token)
        assertEquals(10L, fixture.transport.histories.last().from)
        fixture.history(emptyList())
        assertTrue(fixture.state.historyEnd)
        assertFalse(fixture.state.historyLoading)
    }

    @Test
    fun singleMessageHistoryBatchesAutomaticallyFillOnePage() {
        val fixture = selectedFixture()
        repeat(50) { index ->
            assertEquals(50 - index, fixture.transport.histories.last().limit)
            assertEquals(if (index == 0) 0L else 101L - index, fixture.transport.histories.last().from)
            fixture.history(listOf(message(100L - index)))
            assertEquals(index + 1, fixture.state.messages.size)
            assertEquals(index < 49, fixture.state.historyLoading)
        }
        assertEquals(50, fixture.transport.histories.size)
        assertFalse(fixture.state.historyEnd)
        fixture.controller.loadOlder(fixture.state.token)
        assertEquals(51L, fixture.transport.histories.last().from)
        fixture.history(emptyList())
        assertTrue(fixture.state.historyEnd)
    }

    @Test
    fun historyBatchErrorsKeepPartialResultsAndRetryFromLastCursor() {
        val fixture = selectedFixture()
        fixture.history(listOf(message(100)))
        fixture.transport.histories.last().result(ChatResult(error = ChatError.RATE_LIMITED))
        fixture.flush()
        assertEquals(listOf(100L), fixture.state.messages.map { it.id })
        assertFalse(fixture.state.historyLoading)
        assertFalse(fixture.state.historyEnd)
        assertEquals(ChatError.RATE_LIMITED, fixture.state.historyError)
        fixture.controller.loadOlder(fixture.state.token)
        assertEquals(100L, fixture.transport.histories.last().from)
        assertNull(fixture.state.historyError)
        fixture.history(listOf(message(99)))
        assertEquals(listOf(99L, 100L), fixture.state.messages.map { it.id })
        assertTrue(fixture.state.historyLoading)
    }

    @Test
    fun historyBatchCallbacksAreDeduplicatedAndStopAfterSelectionChanges() {
        val fixture = selectedFixture()
        val first = fixture.transport.histories.single()
        fixture.history(listOf(message(100)))
        first.result(ChatResult(listOf(message(90))))
        fixture.flush()
        assertEquals(2, fixture.transport.histories.size)
        assertEquals(listOf(100L), fixture.state.messages.map { it.id })
        val pending = fixture.transport.histories.last()
        fixture.chat(2)
        fixture.select(2)
        pending.result(ChatResult(listOf(message(99))))
        fixture.flush()
        assertEquals(3, fixture.transport.histories.size)
        assertTrue(fixture.state.messages.isEmpty())
        assertTrue(fixture.state.historyLoading)
        fixture.history(emptyList())
        assertTrue(fixture.state.historyEnd)
    }

    @Test
    fun historyCountsUniqueSurvivingMessagesAndStopsNonAdvancingPages() {
        val fixture = selectedFixture()
        fixture.event(ChatEvent.Deleted(1, listOf(100), permanent = true))
        fixture.history(listOf(message(100), message(99), message(99)))
        assertEquals(49, fixture.transport.histories.last().limit)
        assertEquals(listOf(99L), fixture.state.messages.map { it.id })
        fixture.history(listOf(message(99)))
        assertFalse(fixture.state.historyLoading)
        assertFalse(fixture.state.historyEnd)
        assertEquals(ChatError.REQUEST_FAILED, fixture.state.historyError)
        assertEquals(2, fixture.transport.histories.size)
    }

    @Test
    fun historyBoundsWorkWhenEveryReturnedMessageHasBeenDeleted() {
        val fixture = selectedFixture()
        fixture.event(ChatEvent.Deleted(1, (1L..200L).toList(), permanent = true))
        repeat(100) { fixture.history(listOf(message(200L - it))) }
        assertTrue(fixture.state.messages.isEmpty())
        assertFalse(fixture.state.historyLoading)
        assertFalse(fixture.state.historyEnd)
        assertEquals(ChatError.REQUEST_FAILED, fixture.state.historyError)
        assertEquals(100, fixture.transport.histories.size)
    }

    @Test
    fun inFlightHistoryCannotUndoEditsOrPermanentDeletes() {
        val fixture = selectedFixture()
        fixture.event(ChatEvent.Content(1, 20, "edited", MessageKind.TEXT))
        fixture.event(ChatEvent.Deleted(1, listOf(10), permanent = true))
        fixture.event(ChatEvent.NewMessage(message(30, text = "live")))
        fixture.history(listOf(message(30, text = "old"), message(20), message(10)))
        assertEquals(listOf(20L, 30L), fixture.state.messages.map { it.id })
        assertEquals(listOf("edited", "live"), fixture.state.messages.map { it.text })
        fixture.chat(2)
        fixture.select(2)
        fixture.select(1)
        fixture.history(listOf(message(10)))
        assertTrue(fixture.state.messages.isEmpty())
    }

    @Test
    fun temporarilyInaccessibleMessagesDoNotReappearFromAnOldRequestButCanBeFetchedLater() {
        val fixture = selectedFixture()
        fixture.event(ChatEvent.NewMessage(message(10)))
        fixture.event(ChatEvent.Deleted(1, listOf(10), permanent = false))
        fixture.history(listOf(message(10)))
        assertTrue(fixture.state.messages.isEmpty())
        fixture.chat(2)
        fixture.select(2)
        fixture.select(1)
        fixture.history(listOf(message(10, text = "accessible again")))
        assertEquals("accessible again", fixture.state.messages.single().text)
    }

    @Test
    fun lastMessageUpdateCanDeliverAChatMessageWithoutNewMessage() {
        val fixture = selectedFixture()
        fixture.event(ChatEvent.LastMessage(1, message(50, text = "latest"), 500))
        assertEquals("latest", fixture.state.messages.single().text)
        assertEquals(50L, fixture.state.selected?.lastMessage?.id)
        assertEquals(500L, fixture.state.selected?.order)
    }

    @Test
    fun textValidationCountsUnicodeCodePointsAndPreservesIntentionalWhitespace() {
        val fixture = selectedFixture()
        fixture.controller.sendText(fixture.state.token, " \n\t ")
        assertEquals(ChatError.EMPTY_TEXT, fixture.state.sendError)
        fixture.controller.sendText(fixture.state.token, "a".repeat(1_048_577))
        assertEquals(ChatError.TEXT_TOO_LONG, fixture.state.sendError)
        val text = "😀".repeat(4094) + " \n"
        fixture.controller.sendText(fixture.state.token, text)
        assertEquals(text, fixture.transport.sends.single().text)
        assertTrue(fixture.state.sendPending)
    }

    @Test
    fun permissionsAndDuplicateSubmitAreCheckedInsideActor() {
        val fixture = selectedFixture()
        fixture.event(ChatEvent.Permissions(1, false))
        fixture.controller.sendText(fixture.state.token, "hello")
        assertEquals(ChatError.FORBIDDEN, fixture.state.sendError)
        assertTrue(fixture.transport.sends.isEmpty())
        fixture.event(ChatEvent.Permissions(1, true))
        repeat(3) { fixture.controller.sendText(fixture.state.token, "hello") }
        assertEquals(1, fixture.transport.sends.size)
    }

    @Test
    fun initialSendAcceptanceRemainsPendingUntilDeliveryUpdate() {
        val fixture = selectedFixture()
        fixture.controller.sendText(fixture.state.token, "hello")
        fixture.transport.sends.single().result(ChatResult(message(10, delivery = MessageDelivery.PENDING)))
        fixture.flush()
        assertFalse(fixture.state.sendPending)
        assertEquals(1L, fixture.state.acceptedSendId)
        assertEquals(1L, fixture.state.acceptedSendChatId)
        assertEquals("hello", fixture.state.acceptedSendText)
        assertEquals(MessageDelivery.PENDING, fixture.state.messages.single().delivery)
        fixture.event(ChatEvent.SendSucceeded(message(20), 10))
        assertEquals(20L, fixture.state.messages.single().id)
        assertEquals(MessageDelivery.SENT, fixture.state.messages.single().delivery)
    }

    @Test
    fun sendSuccessBeforeAcknowledgementDoesNotResurrectTemporaryId() {
        val fixture = selectedFixture()
        fixture.controller.sendText(fixture.state.token, "hello")
        fixture.event(ChatEvent.NewMessage(message(10, delivery = MessageDelivery.PENDING)))
        fixture.event(ChatEvent.SendSucceeded(message(20), 10))
        fixture.transport.sends.single().result(ChatResult(message(10, delivery = MessageDelivery.PENDING)))
        fixture.flush()
        fixture.history(listOf(message(10, delivery = MessageDelivery.PENDING)))
        assertEquals(listOf(20L), fixture.state.messages.map { it.id })
        assertEquals(1L, fixture.state.acceptedSendId)
    }

    @Test
    fun failedSendWithSameIdIsNotDowngradedByLateAcknowledgement() {
        val fixture = selectedFixture()
        fixture.controller.sendText(fixture.state.token, "hello")
        fixture.event(ChatEvent.SendFailed(message(10, delivery = MessageDelivery.FAILED, canRetry = true), 10))
        fixture.transport.sends.single().result(ChatResult(message(10, delivery = MessageDelivery.PENDING)))
        fixture.flush()
        assertEquals(MessageDelivery.FAILED, fixture.state.messages.single().delivery)
        assertTrue(fixture.state.messages.single().canRetry)
    }

    @Test
    fun editedSentMessageRemainsEditedAfterSwitchingAwayAndFetchingAgain() {
        val fixture = selectedFixture()
        fixture.event(ChatEvent.SendSucceeded(message(20, text = "original"), 10))
        fixture.event(ChatEvent.Content(1, 20, "edited", MessageKind.TEXT))
        fixture.chat(2)
        fixture.select(2)
        fixture.select(1)
        fixture.history(listOf(message(20, text = "newest edit")))
        assertEquals("newest edit", fixture.state.messages.single().text)
    }

    @Test
    fun failedRequestKeepsDraftReceiptUnchangedAndReportsItsTargetChat() {
        val fixture = selectedFixture()
        fixture.controller.sendText(fixture.state.token, "draft")
        fixture.chat(2)
        fixture.select(2)
        fixture.transport.sends.single().result(ChatResult(error = ChatError.RATE_LIMITED, code = 429))
        fixture.flush()
        assertEquals(0L, fixture.state.acceptedSendId)
        assertNull(fixture.state.acceptedSendText)
        assertEquals(1L, fixture.state.sendErrorChatId)
        assertEquals(ChatError.RATE_LIMITED, fixture.state.sendError)
        assertTrue(fixture.state.messages.isEmpty())
    }

    @Test
    fun backgroundSendAcknowledgementClearsOnlyItsOwnDraftAndDoesNotEnterAnotherChat() {
        val fixture = selectedFixture()
        fixture.controller.sendText(fixture.state.token, "draft")
        fixture.chat(2)
        fixture.select(2)
        fixture.transport.sends.single().result(ChatResult(message(10, delivery = MessageDelivery.PENDING)))
        fixture.flush()
        assertEquals(1L, fixture.state.acceptedSendChatId)
        assertTrue(fixture.state.messages.isEmpty())
        assertFalse(fixture.state.sendPending)
    }

    @Test
    fun retryHonorsDelayAndDeduplicatesRequestAndTerminalUpdates() {
        val fixture = selectedFixture()
        fixture.event(ChatEvent.SendFailed(message(10, delivery = MessageDelivery.FAILED, canRetry = true, retryAfter = 2), 10))
        fixture.controller.retry(fixture.state.token, 10)
        assertTrue(fixture.transport.retries.isEmpty())
        assertEquals(ChatError.RATE_LIMITED, fixture.state.sendError)
        fixture.now += 2_000
        repeat(3) { fixture.controller.retry(fixture.state.token, 10) }
        assertEquals(1, fixture.transport.retries.size)
        assertEquals(setOf(10L), fixture.state.retryingIds)
        fixture.event(ChatEvent.NewMessage(message(20, delivery = MessageDelivery.PENDING)))
        fixture.event(ChatEvent.SendSucceeded(message(30), 20))
        fixture.transport.retries.single().result(ChatResult(listOf(message(20, delivery = MessageDelivery.PENDING))))
        fixture.flush()
        assertEquals(listOf(30L), fixture.state.messages.map { it.id })
        assertTrue(fixture.state.retryingIds.isEmpty())
    }

    @Test
    fun unsupportedFailureCannotBeResentAndFailedRetryCanBeTriedAgain() {
        val fixture = selectedFixture()
        fixture.event(ChatEvent.NewMessage(message(10, delivery = MessageDelivery.FAILED)))
        fixture.controller.retry(fixture.state.token, 10)
        assertEquals(ChatError.UNSUPPORTED, fixture.state.sendError)
        assertTrue(fixture.transport.retries.isEmpty())
        fixture.event(ChatEvent.SendFailed(message(10, delivery = MessageDelivery.FAILED, canRetry = true), 10))
        fixture.controller.retry(fixture.state.token, 10)
        fixture.transport.retries.single().result(ChatResult(error = ChatError.REQUEST_FAILED))
        fixture.flush()
        assertTrue(fixture.state.retryingIds.isEmpty())
        assertEquals(MessageDelivery.FAILED, fixture.state.messages.single().delivery)
        fixture.controller.retry(fixture.state.token, 10)
        assertEquals(2, fixture.transport.retries.size)
    }

    @Test
    fun readAcknowledgementDoesNotInventUnreadState() {
        val fixture = selectedFixture()
        fixture.event(ChatEvent.Inbox(1, 4))
        fixture.event(ChatEvent.NewMessage(message(10)))
        fixture.event(ChatEvent.NewMessage(message(20, delivery = MessageDelivery.PENDING)))
        repeat(2) { fixture.controller.markRead(fixture.state.token) }
        assertEquals(listOf(10L), fixture.transport.reads.single().ids)
        fixture.transport.reads.single().result(ChatResult(Unit))
        fixture.flush()
        assertEquals(4, fixture.state.selected?.unreadCount)
        fixture.event(ChatEvent.Inbox(1, 0))
        assertEquals(0, fixture.state.selected?.unreadCount)
    }

    @Test
    fun nativeCompletionsOnlyPublishAfterActorEnqueuesThem() {
        val fixture = Fixture()
        fixture.transport.loads.single()(ChatResult(ChatLoadResult(endReached = true)))
        assertTrue(fixture.state.listLoading)
        assertFalse(fixture.state.listEnd)
        fixture.flush()
        assertFalse(fixture.state.listLoading)
        assertTrue(fixture.state.listEnd)
    }

    @Test
    fun snapshotsAndContentModelsNeverPrintPersonalContent() {
        val fixture = selectedFixture()
        fixture.event(ChatEvent.Title(1, "private chat title"))
        fixture.event(ChatEvent.SenderName(1, "private sender name"))
        fixture.event(ChatEvent.NewMessage(message(10, text = "private message body")))
        val printed = listOf(fixture.state.copy(acceptedSendText = "private accepted draft"), fixture.state.selected, fixture.state.messages.single(),
            ChatResult(fixture.state.messages), ChatEvent.Content(1, 10, "private message body", MessageKind.TEXT)).joinToString()
        assertFalse(printed.contains("private"))
        assertEquals("private sender name", fixture.state.senderNames[1])
    }

    @Test
    fun foldersUseIndependentPositionsAndPaginationIncludingArchive() {
        val fixture = Fixture()
        val folder = ChatListRef(7)
        fixture.event(ChatEvent.Folders(listOf(ChatFolderInfo(7, "Work")), mainPosition = 1))
        fixture.event(ChatEvent.NewChat(ChatItem(1, "one", order = 100,
            positions = mapOf(ChatListRef() to 100L, folder to 30L))))
        fixture.event(ChatEvent.NewChat(ChatItem(2, "two", order = 50,
            positions = mapOf(ChatListRef() to 50L, folder to 60L, ChatListRef(-1) to 10L))))
        assertEquals(listOf(1L, 2L), fixture.state.items.map { it.id })
        fixture.transport.loads[0](ChatResult(ChatLoadResult(endReached = true)))
        fixture.flush()
        fixture.controller.selectList(fixture.state.token, folder)
        assertEquals(folder, fixture.transport.loadedLists.last())
        assertEquals(listOf(2L, 1L), fixture.state.items.map { it.id })
        assertFalse(fixture.state.listEnd)
        fixture.event(ChatEvent.Position(2, 0, folder))
        assertEquals(listOf(1L), fixture.state.items.map { it.id })
        fixture.controller.selectList(fixture.state.token, ChatListRef(-1))
        assertEquals(listOf(2L), fixture.state.items.map { it.id })
        fixture.controller.selectList(fixture.state.token, ChatListRef())
        assertTrue(fixture.state.listEnd)
        assertEquals(listOf(1L, 2L), fixture.state.items.map { it.id })
        assertEquals(3, fixture.transport.loads.size)
        assertEquals(1, fixture.state.mainListPosition)
    }

    @Test
    fun oldListCompletionCannotFinishAnotherFolderAndRemovedFolderPreservesTheOpenConversation() {
        val fixture = Fixture()
        val folder = ChatListRef(7)
        fixture.event(ChatEvent.Folders(listOf(ChatFolderInfo(7, "Work"))))
        fixture.event(ChatEvent.NewChat(ChatItem(1, "one", positions = mapOf(folder to 10L))))
        fixture.controller.selectList(fixture.state.token, folder)
        fixture.select(1)
        fixture.transport.loads[0](ChatResult(ChatLoadResult(endReached = true)))
        fixture.flush()
        assertFalse(fixture.state.listEnd)
        assertTrue(fixture.state.listLoading)
        val oldToken = fixture.state.token
        fixture.event(ChatEvent.Folders(emptyList()))
        assertEquals(ChatListRef(), fixture.state.activeList)
        assertEquals(1L, fixture.state.selected?.id)
        assertEquals(oldToken, fixture.state.token)
        fixture.transport.histories.single().result(ChatResult(listOf(message(1))))
        fixture.flush()
        assertEquals(listOf(1L), fixture.state.messages.map { it.id })
        fixture.controller.sendText(oldToken, "still selected")
        assertEquals("still selected", fixture.transport.sends.single().text)
    }

    @Test
    fun changingFoldersKeepsTheConversationAndItsPendingHistoryEvenWhenItIsAbsentFromTheList() {
        val fixture = selectedFixture()
        val token = fixture.state.token
        val pending = fixture.transport.histories.single()
        fixture.event(ChatEvent.Folders(listOf(ChatFolderInfo(7, "one"), ChatFolderInfo(9, "three"))))
        for (list in listOf(ChatListRef(7), ChatListRef(9), ChatListRef(-1))) {
            fixture.controller.selectList(token, list)
            assertEquals(token, fixture.state.token)
            assertEquals(1L, fixture.state.selected?.id)
            assertTrue(fixture.state.items.isEmpty())
            assertTrue(fixture.state.historyLoading)
        }
        assertEquals(listOf(1L), fixture.transport.opened)
        assertTrue(fixture.transport.closed.isEmpty())
        pending.result(ChatResult((1L..50L).map { message(it) })); fixture.flush()
        assertEquals((1L..50L).toList(), fixture.state.messages.map { it.id })
        assertFalse(fixture.state.historyLoading)
        assertEquals(1, fixture.transport.histories.size)
    }

    @Test
    fun switchingOnlyTheListPreservesReplyAndPendingSendButSelectingAnotherChatStillRejectsOldCallbacks() {
        val fixture = selectedFixture()
        fixture.history((1L..50L).map { message(it) })
        fixture.controller.setReply(fixture.state.token, 50)
        fixture.controller.sendText(fixture.state.token, "synthetic draft")
        val pending = fixture.transport.sends.single()
        val token = fixture.state.token
        val folder = ChatListRef(7)
        fixture.event(ChatEvent.Folders(listOf(ChatFolderInfo(7, "one"))))
        fixture.event(ChatEvent.NewChat(ChatItem(2, "other", positions = mapOf(folder to 10L))))
        fixture.controller.selectList(token, folder)
        assertEquals(token, fixture.state.token)
        assertEquals(50L, fixture.state.replyTarget?.id)
        assertTrue(fixture.state.sendPending)
        pending.result(ChatResult(message(100, text = "synthetic draft").copy(outgoing = true))); fixture.flush()
        assertEquals(1L, fixture.state.acceptedSendChatId)
        assertEquals("synthetic draft", fixture.state.acceptedSendText)
        assertTrue(fixture.state.messages.any { it.id == 100L })

        fixture.controller.loadOlder(token)
        val oldHistory = fixture.transport.histories.last()
        fixture.select(2)
        assertNotEquals(token, fixture.state.token)
        oldHistory.result(ChatResult(listOf(message(101)))); fixture.flush()
        assertTrue(fixture.state.messages.none { it.chatId == 1L })
        assertEquals(2L, fixture.state.selected?.id)
    }

    @Test
    fun automaticReadsRequireVisibleIncomingMessagesAndAnActiveWindow() {
        val fixture = selectedFixture()
        fixture.event(ChatEvent.Inbox(1, 3, 10))
        fixture.history(listOf(message(10), message(20), message(30).copy(outgoing = true)))
        fixture.controller.visibleMessages(fixture.state.token, listOf(20, 30), active = false)
        assertTrue(fixture.transport.reads.isEmpty())
        fixture.controller.visibleMessages(fixture.state.token, listOf(10, 20, 30, 999), active = true)
        assertEquals(listOf(20L), fixture.transport.reads.single().ids)
        assertFalse(fixture.transport.reads.single().forceRead)
        fixture.transport.reads.single().result(ChatResult(Unit))
        fixture.flush()
        fixture.controller.visibleMessages(fixture.state.token, listOf(10, 20, 30, 999), active = true)
        assertEquals(1, fixture.transport.reads.size)
        assertEquals(3, fixture.state.selected?.unreadCount)
        fixture.controller.markRead(fixture.state.token)
        assertTrue(fixture.transport.reads.last().forceRead)
    }

    @Test
    fun automaticReadCompletionDoesNotReadQueuedMessagesAfterWindowLosesFocus() {
        val fixture = selectedFixture()
        fixture.history(listOf(message(10), message(20)))
        fixture.controller.visibleMessages(fixture.state.token, listOf(10), active = true)
        fixture.controller.visibleMessages(fixture.state.token, listOf(20), active = true)
        fixture.controller.visibleMessages(fixture.state.token, emptyList(), active = false)
        fixture.transport.reads.single().result(ChatResult(Unit))
        fixture.flush()
        assertEquals(1, fixture.transport.reads.size)
        fixture.controller.visibleMessages(fixture.state.token, listOf(20), active = true)
        assertEquals(listOf(20L), fixture.transport.reads.last().ids)
    }

    @Test
    fun failedAutomaticReadDoesNotLoopAndNewViewportCanRetry() {
        val fixture = selectedFixture()
        fixture.history(listOf(message(10), message(20)))
        fixture.controller.visibleMessages(fixture.state.token, listOf(10), active = true)
        fixture.transport.reads.single().result(ChatResult(error = ChatError.RATE_LIMITED))
        fixture.flush()
        repeat(3) { fixture.controller.visibleMessages(fixture.state.token, listOf(10), active = true) }
        assertEquals(1, fixture.transport.reads.size)
        assertEquals(ChatError.RATE_LIMITED, fixture.state.readError)
        fixture.controller.visibleMessages(fixture.state.token, listOf(20), active = true)
        assertEquals(2, fixture.transport.reads.size)
        assertNull(fixture.state.readError)
    }

    @Test
    fun anUnreadMarkerCreatedDuringAnAutomaticReadIsNotClearedByItsLateAck() {
        val fixture = selectedFixture()
        fixture.history(listOf(message(10)))
        fixture.controller.visibleMessages(fixture.state.token, listOf(10), active = true)
        fixture.event(ChatEvent.MarkedUnread(1, true))
        fixture.transport.reads.single().result(ChatResult(Unit))
        fixture.flush()
        fixture.controller.visibleMessages(fixture.state.token, listOf(10), active = true)
        assertTrue(fixture.transport.unread.isEmpty())
        assertEquals(1, fixture.transport.reads.size)
        assertTrue(fixture.state.selected?.markedUnread == true)
        fixture.select(1)
        assertEquals(2, fixture.transport.reads.size) // Explicit reopening allows a new read.
    }

    @Test
    fun aReadAckAfterLosingFocusDoesNotClearTheExistingManualUnreadMarker() {
        val fixture = selectedFixture()
        fixture.history(listOf(message(10)))
        fixture.event(ChatEvent.MarkedUnread(1, true))
        fixture.controller.visibleMessages(fixture.state.token, listOf(10), active = true)
        fixture.controller.visibleMessages(fixture.state.token, emptyList(), active = false)
        fixture.transport.reads.single().result(ChatResult(Unit))
        fixture.flush()
        assertTrue(fixture.transport.unread.isEmpty())
        assertTrue(fixture.state.selected?.markedUnread == true)
        fixture.controller.visibleMessages(fixture.state.token, listOf(10), active = true)
        assertEquals(2, fixture.transport.reads.size)
    }

    @Test
    fun manualUnreadRemainsUntilExplicitReadingOrReopeningConversation() {
        val fixture = selectedFixture()
        fixture.history(listOf(message(10)))
        fixture.controller.markUnread(fixture.state.token)
        assertTrue(fixture.transport.unread.single().marked)
        fixture.event(ChatEvent.MarkedUnread(1, true))
        fixture.transport.unread.single().result(ChatResult(Unit))
        fixture.flush()
        fixture.controller.visibleMessages(fixture.state.token, listOf(10), active = true)
        assertTrue(fixture.transport.reads.isEmpty())
        fixture.controller.markRead(fixture.state.token)
        fixture.transport.reads.single().result(ChatResult(Unit))
        fixture.flush()
        assertFalse(fixture.transport.unread.last().marked)
        fixture.transport.unread.last().result(ChatResult(Unit))
        fixture.flush()
        assertEquals(1, fixture.transport.reads.size) // No repeat loop while native marker update is delayed.
        assertTrue(fixture.state.selected?.markedUnread == true)
        fixture.event(ChatEvent.MarkedUnread(1, false))
        assertFalse(fixture.state.selected?.markedUnread == true)
    }

    @Test
    fun reopeningTheSameChatAfterReturningToTheListResumesAutomaticReading() {
        val fixture = selectedFixture()
        fixture.history(listOf(message(10)))
        fixture.controller.markUnread(fixture.state.token)
        fixture.event(ChatEvent.MarkedUnread(1, true))
        fixture.transport.unread.single().result(ChatResult(Unit))
        fixture.flush()
        fixture.controller.visibleMessages(fixture.state.token, listOf(10), active = true)
        assertTrue(fixture.transport.reads.isEmpty())
        fixture.controller.visibleMessages(fixture.state.token, emptyList(), active = false)
        fixture.select(1)
        fixture.controller.visibleMessages(fixture.state.token, listOf(10), active = true)
        assertEquals(listOf(10L), fixture.transport.reads.single().ids)
        assertFalse(fixture.transport.reads.single().forceRead)
    }

    @Test
    fun unknownFilesCannotDownloadAndLateDownloadResponseCannotUndoCompletion() {
        val fixture = selectedFixture()
        fixture.controller.downloadFile(fixture.state.token, 999)
        assertTrue(fixture.transport.downloads.isEmpty())
        val file = MediaFile(4, canDownload = true)
        fixture.event(ChatEvent.NewMessage(message(10).copy(kind = MessageKind.PHOTO, media = MessageMedia(file))))
        repeat(3) { fixture.controller.downloadFile(fixture.state.token, 4) }
        assertEquals(1, fixture.transport.downloads.size)
        fixture.event(ChatEvent.FileUpdated(file.copy(downloaded = true, localPath = "private/local/photo.png")))
        fixture.transport.downloads.single().result(ChatResult(file.copy(downloading = true)))
        fixture.flush()
        assertTrue(fixture.state.mediaFiles.getValue(4).downloaded)
        assertEquals("private/local/photo.png", fixture.state.mediaFiles.getValue(4).localPath)
        assertFalse(4 in fixture.state.downloadingFiles)
        assertFalse(fixture.state.toString().contains("private"))
    }

    @Test
    fun fileDownloadFailureCanRetryAndContentReplacementUsesTheNewFile() {
        val fixture = selectedFixture()
        val first = MediaFile(4, canDownload = true)
        fixture.event(ChatEvent.NewMessage(message(10).copy(kind = MessageKind.PHOTO, media = MessageMedia(first))))
        fixture.controller.downloadFile(fixture.state.token, 4)
        fixture.transport.downloads.single().result(ChatResult(error = ChatError.REQUEST_FAILED))
        fixture.flush()
        assertEquals(ChatError.REQUEST_FAILED, fixture.state.fileErrors[4])
        fixture.controller.downloadFile(fixture.state.token, 4)
        assertEquals(2, fixture.transport.downloads.size)
        assertNull(fixture.state.fileErrors[4])
        fixture.event(ChatEvent.Content(1, 10, "caption", MessageKind.PHOTO, MessageMedia(MediaFile(5, canDownload = true))))
        assertEquals(5, fixture.state.messages.single().media?.file?.id)
        fixture.controller.downloadFile(fixture.state.token, 5)
        assertEquals(5, fixture.transport.downloads.last().id)
    }

    @Test
    fun photoAndStickerPermissionsAreIndependentAndMediaAckDoesNotClearTextDraft() {
        val fixture = selectedFixture()
        fixture.event(ChatEvent.Permissions(1, canSend = false, canSendPhotos = true, canSendStickers = false))
        fixture.controller.sendText(fixture.state.token, "text")
        assertEquals(ChatError.FORBIDDEN, fixture.state.sendError)
        fixture.controller.sendPhoto(fixture.state.token, "synthetic.png", "caption")
        assertEquals("synthetic.png", fixture.transport.photos.single().path)
        fixture.transport.photos.single().result(ChatResult(message(10).copy(kind = MessageKind.PHOTO)))
        fixture.flush()
        assertEquals(1L, fixture.state.acceptedSendId)
        assertNull(fixture.state.acceptedSendText)
        fixture.controller.sendSticker(fixture.state.token, sticker(5))
        assertEquals(ChatError.FORBIDDEN, fixture.state.sendError)
        assertTrue(fixture.transport.stickerSends.isEmpty())
    }

    @Test
    fun switchingStickerSetsIgnoresOldResultsAndOnlyKnownStickersCanBeSent() {
        val fixture = selectedFixture()
        fixture.controller.loadStickers(fixture.state.token)
        fixture.transport.packs.single()(ChatResult(listOf(StickerPack(7, "Synthetic pack"))))
        fixture.flush()
        fixture.controller.loadStickers(fixture.state.token, 7)
        fixture.transport.recents.single()(ChatResult(listOf(sticker(1))))
        fixture.flush()
        assertTrue(fixture.state.stickersLoading)
        assertTrue(fixture.state.stickers.isEmpty())
        fixture.transport.sets.single().result(ChatResult(listOf(sticker(5))))
        fixture.flush()
        assertEquals(listOf(5), fixture.state.stickers.map { it.media.file.id })
        fixture.controller.sendSticker(fixture.state.token, sticker(999))
        assertEquals(ChatError.UNSUPPORTED, fixture.state.sendError)
        fixture.controller.sendSticker(fixture.state.token, sticker(5))
        assertEquals(5, fixture.transport.stickerSends.single().sticker.media.file.id)
        fixture.transport.stickerSends.single().result(ChatResult(message(10).copy(kind = MessageKind.STICKER)))
        fixture.flush()
        assertNull(fixture.state.acceptedSendText)
    }

    @Test
    fun stickerSectionsKeepRecentAndNativePackOrderWithoutEagerPackOrFileRequests() {
        val fixture = selectedFixture()
        val historyRequests = fixture.transport.histories.size
        fixture.controller.loadStickers(fixture.state.token)
        fixture.transport.packs.single()(ChatResult(listOf(
            StickerPack(9, "First native pack", 30, sticker(90)),
            StickerPack(7, "Second native pack", 40, sticker(70)),
        )))
        fixture.transport.recents.single()(ChatResult(listOf(sticker(1), sticker(2))))
        fixture.flush()
        assertEquals(listOf(null, 9L, 7L), fixture.state.stickerSections.map { it.setId })
        assertEquals(listOf(1, 2), fixture.state.stickerSections.first().stickers.map { it.media.file.id })
        assertEquals(listOf("First native pack", "Second native pack"), fixture.state.stickerSections.drop(1).map { it.title })
        assertTrue(fixture.state.stickerSections.first().loaded)
        assertTrue(fixture.state.stickerSections.drop(1).all { !it.loaded && !it.loading })
        assertEquals(listOf(90, 70), fixture.state.stickerSets.map { it.cover?.media?.file?.id })
        assertTrue(fixture.transport.sets.isEmpty())
        assertTrue(fixture.transport.downloads.isEmpty())
        assertTrue(fixture.transport.reads.isEmpty())
        assertEquals(historyRequests, fixture.transport.histories.size)
        assertFalse(fixture.state.stickerSections.toString().contains("native pack"))
    }

    @Test
    fun ensuringVisibleStickerSectionsDoesNotNavigateOrReplaceOtherLoadedSections() {
        val fixture = selectedFixture()
        fixture.controller.loadStickers(fixture.state.token)
        fixture.transport.packs.single()(ChatResult(listOf(StickerPack(7, "Seven"), StickerPack(9, "Nine"))))
        fixture.transport.recents.single()(ChatResult(listOf(sticker(1))))
        fixture.flush()
        fixture.controller.loadStickers(fixture.state.token, 7)
        fixture.controller.ensureStickerSection(fixture.state.token, 9)
        fixture.transport.sets.last().result(ChatResult(listOf(sticker(90))))
        fixture.flush()
        assertEquals(7L, fixture.state.selectedStickerSetId)
        assertTrue(fixture.state.stickersLoading)
        assertEquals(listOf(90), fixture.state.stickerSections.last().stickers.map { it.media.file.id })
        fixture.transport.sets.first().result(ChatResult(listOf(sticker(70))))
        fixture.flush()
        assertEquals(listOf(70), fixture.state.stickers.map { it.media.file.id })
        assertEquals(listOf(1), fixture.state.stickerSections.first().stickers.map { it.media.file.id })
        fixture.controller.ensureStickerSection(fixture.state.token, 7)
        fixture.controller.ensureStickerSection(fixture.state.token, 9)
        assertEquals(2, fixture.transport.sets.size)
        fixture.controller.loadStickers(fixture.state.token, 7)
        assertEquals(3, fixture.transport.sets.size)
        assertEquals(listOf(70), fixture.state.stickers.map { it.media.file.id })
        assertTrue(fixture.state.stickersLoading)
    }

    @Test
    fun stickerSectionRequestsAreBoundedAndErrorsRequireAnExplicitRetry() {
        val fixture = selectedFixture()
        fixture.controller.loadStickers(fixture.state.token)
        fixture.transport.packs.single()(ChatResult((7L..10L).map { StickerPack(it, "Pack") }))
        fixture.flush()
        for (id in 7L..10L) {
            fixture.controller.ensureStickerSection(fixture.state.token, id)
            fixture.controller.ensureStickerSection(fixture.state.token, id)
        }
        assertEquals(listOf(7L), fixture.transport.sets.map { it.id })
        fixture.transport.recents.single()(ChatResult(emptyList()))
        fixture.flush()
        assertEquals(listOf(7L, 8L), fixture.transport.sets.map { it.id })
        fixture.transport.sets.first().result(ChatResult(error = ChatError.REQUEST_FAILED))
        fixture.flush()
        assertEquals(listOf(7L, 8L, 9L), fixture.transport.sets.map { it.id })
        fixture.controller.ensureStickerSection(fixture.state.token, 7)
        assertEquals(3, fixture.transport.sets.size)
        val failed = fixture.state.stickerSections.single { it.setId == 7L }
        assertFalse(failed.loading)
        assertEquals(ChatError.REQUEST_FAILED, failed.error)
        fixture.controller.loadStickers(fixture.state.token, 7)
        assertTrue(fixture.state.stickerSections.single { it.setId == 7L }.loading)
        fixture.transport.sets[1].result(ChatResult(listOf(sticker(80))))
        fixture.flush()
        assertEquals(listOf(7L, 8L, 9L, 10L), fixture.transport.sets.map { it.id })
        fixture.transport.sets[2].result(ChatResult(listOf(sticker(90))))
        fixture.flush()
        assertEquals(listOf(7L, 8L, 9L, 10L, 7L), fixture.transport.sets.map { it.id })
        assertTrue(fixture.transport.downloads.isEmpty())
    }

    @Test
    fun lateStickerSectionsAndPackListsCannotCrossConversationSelection() {
        val fixture = selectedFixture()
        fixture.controller.loadStickers(fixture.state.token)
        val oldRecent = fixture.transport.recents.single()
        val oldPacks = fixture.transport.packs.single()
        fixture.chat(2)
        fixture.select(2)
        fixture.controller.loadStickers(fixture.state.token)
        oldPacks(ChatResult(listOf(StickerPack(99, "Old selection"))))
        oldRecent(ChatResult(listOf(sticker(99))))
        fixture.flush()
        assertTrue(fixture.state.stickerSets.isEmpty())
        assertTrue(fixture.state.stickers.isEmpty())
        assertTrue(fixture.state.stickersLoading)
        fixture.transport.packs.last()(ChatResult(listOf(StickerPack(7, "Current selection"))))
        fixture.transport.recents.last()(ChatResult(listOf(sticker(7))))
        fixture.flush()
        assertEquals(listOf(7), fixture.state.stickers.map { it.media.file.id })
        assertEquals(listOf(7L), fixture.state.stickerSets.map { it.id })
        fixture.controller.sendSticker(fixture.state.token, sticker(99))
        assertEquals(ChatError.UNSUPPORTED, fixture.state.sendError)
        assertTrue(fixture.transport.stickerSends.isEmpty())
    }

    @Test
    fun removedStickerSectionsRejectLateResponsesAndQueuedLoads() {
        val fixture = selectedFixture()
        fixture.controller.loadStickers(fixture.state.token)
        fixture.transport.packs.single()(ChatResult(listOf(StickerPack(7, "Seven"), StickerPack(8, "Eight"))))
        fixture.flush()
        fixture.controller.ensureStickerSection(fixture.state.token, 7)
        fixture.controller.ensureStickerSection(fixture.state.token, 8)
        fixture.event(ChatEvent.InstalledStickersChanged)
        fixture.transport.packs.last()(ChatResult(emptyList()))
        fixture.flush()
        assertEquals(listOf<Long?>(null), fixture.state.stickerSections.map { it.setId })
        fixture.transport.sets.single().result(ChatResult(listOf(sticker(70))))
        fixture.transport.recents.single()(ChatResult(emptyList()))
        fixture.flush()
        assertEquals(1, fixture.transport.sets.size)
        assertTrue(fixture.state.stickerSections.single().stickers.isEmpty())
        assertFalse(fixture.state.mediaFiles.containsKey(70))
        fixture.controller.ensureStickerSection(fixture.state.token, 8)
        assertEquals(1, fixture.transport.sets.size)
    }

    @Test
    fun nativeRecentUpdatesRefreshTheirSectionWithoutChangingTheSelectedPack() {
        val fixture = selectedFixture()
        fixture.controller.loadStickers(fixture.state.token)
        fixture.transport.packs.single()(ChatResult(listOf(StickerPack(7, "Seven"))))
        fixture.flush()
        fixture.controller.loadStickers(fixture.state.token, 7)
        fixture.event(ChatEvent.RecentStickersChanged)
        fixture.transport.recents.single()(ChatResult(listOf(sticker(1))))
        fixture.flush()
        assertEquals(2, fixture.transport.recents.size)
        fixture.transport.recents.last()(ChatResult(listOf(sticker(2))))
        fixture.transport.sets.single().result(ChatResult(listOf(sticker(70))))
        fixture.flush()
        assertEquals(7L, fixture.state.selectedStickerSetId)
        assertEquals(listOf(70), fixture.state.stickers.map { it.media.file.id })
        assertEquals(listOf(2), fixture.state.stickerSections.first().stickers.map { it.media.file.id })
        assertTrue(fixture.transport.reads.isEmpty())
    }

    @Test
    fun stickersFromAnUnselectedLoadedSectionUseOnlyTheActorsNativePayload() {
        val fixture = selectedFixture()
        val native = sticker(70)
        fixture.controller.loadStickers(fixture.state.token)
        fixture.transport.packs.single()(ChatResult(listOf(StickerPack(7, "Seven"))))
        fixture.transport.recents.single()(ChatResult(listOf(sticker(1))))
        fixture.flush()
        fixture.controller.ensureStickerSection(fixture.state.token, 7)
        fixture.transport.sets.single().result(ChatResult(listOf(native)))
        fixture.flush()
        assertNull(fixture.state.selectedStickerSetId)
        assertEquals(listOf(1), fixture.state.stickers.map { it.media.file.id })
        val forged = native.copy(emoji = "changed", media = native.media.copy(
            file = native.media.file.copy(localPath = "C:/untrusted/file"), width = 1, height = 1))
        fixture.controller.sendSticker(fixture.state.token, forged)
        assertEquals(native, fixture.transport.stickerSends.single().sticker)
        assertEquals(1L, fixture.transport.stickerSends.single().chatId)
        fixture.transport.stickerSends.single().result(ChatResult(message(10).copy(kind = MessageKind.STICKER)))
        fixture.flush()
        fixture.controller.sendSticker(fixture.state.token, native.copy(id = native.id + 1))
        assertEquals(ChatError.UNSUPPORTED, fixture.state.sendError)
        fixture.controller.sendSticker(fixture.state.token, native.copy(setId = 8))
        assertEquals(ChatError.UNSUPPORTED, fixture.state.sendError)
        assertEquals(1, fixture.transport.stickerSends.size)
    }

    @Test
    fun stickerSendsRecheckCurrentSelectionAndNativeStickerPermission() {
        val fixture = selectedFixture()
        fixture.controller.loadStickers(fixture.state.token)
        fixture.transport.recents.single()(ChatResult(listOf(sticker(1))))
        fixture.flush()
        val oldToken = fixture.state.token
        fixture.chat(2)
        fixture.select(2)
        fixture.controller.sendSticker(oldToken, sticker(1))
        assertTrue(fixture.transport.stickerSends.isEmpty())
        fixture.event(ChatEvent.Permissions(2, canSend = true, canSendPhotos = true, canSendStickers = false))
        fixture.controller.sendSticker(fixture.state.token, sticker(1))
        assertEquals(ChatError.FORBIDDEN, fixture.state.sendError)
        assertTrue(fixture.transport.stickerSends.isEmpty())
    }

    @Test
    fun refreshedOrUninstalledStickerSectionsCannotAuthorizeTheirRemovedStickers() {
        val fixture = selectedFixture()
        fixture.controller.loadStickers(fixture.state.token)
        fixture.transport.packs.single()(ChatResult(listOf(StickerPack(7, "Seven", cover = sticker(90)))))
        fixture.transport.recents.single()(ChatResult(listOf(sticker(1))))
        fixture.flush()
        fixture.controller.sendSticker(fixture.state.token, sticker(90))
        assertEquals(ChatError.UNSUPPORTED, fixture.state.sendError)
        fixture.controller.ensureStickerSection(fixture.state.token, 7)
        fixture.transport.sets.single().result(ChatResult(listOf(sticker(70))))
        fixture.flush()
        fixture.event(ChatEvent.RecentStickersChanged)
        fixture.transport.recents.last()(ChatResult(emptyList()))
        fixture.flush()
        fixture.controller.sendSticker(fixture.state.token, sticker(1))
        assertEquals(ChatError.UNSUPPORTED, fixture.state.sendError)
        fixture.event(ChatEvent.InstalledStickersChanged)
        fixture.transport.packs.last()(ChatResult(emptyList()))
        fixture.flush()
        fixture.controller.sendSticker(fixture.state.token, sticker(70))
        assertEquals(ChatError.UNSUPPORTED, fixture.state.sendError)
        assertTrue(fixture.transport.stickerSends.isEmpty())
    }

    @Test
    fun ensureStickerSectionCanInitializeRecentAndInstalledMetadataWithoutAnExplicitOpen() {
        val fixture = selectedFixture()
        fixture.controller.ensureStickerSection(fixture.state.token, null)
        assertEquals(1, fixture.transport.packs.size)
        assertEquals(1, fixture.transport.recents.size)
        fixture.transport.packs.single()(ChatResult(listOf(StickerPack(7, "Seven"))))
        fixture.transport.recents.single()(ChatResult(listOf(sticker(1))))
        fixture.flush()
        assertEquals(listOf(null, 7L), fixture.state.stickerSections.map { it.setId })
        assertEquals(listOf(1), fixture.state.stickers.map { it.media.file.id })
        assertTrue(fixture.transport.downloads.isEmpty())
        assertTrue(fixture.transport.reads.isEmpty())
    }

    @Test
    fun folderSavePreservesHiddenMembershipAndDeleteNeverLeavesChats() {
        val fixture = Fixture()
        fixture.event(ChatEvent.Folders(listOf(ChatFolderInfo(7, "Work"))))
        fixture.controller.editFolder(fixture.state.token, 7)
        val original = ChatFolder(7, "Work", colorId = 4, pinnedChatIds = listOf(100),
            includedChatIds = listOf(200), excludedChatIds = listOf(300), includeGroups = true)
        fixture.transport.folderLoads.single().result(ChatResult(original))
        fixture.flush()
        fixture.controller.saveFolder(fixture.state.token, fixture.state.folderEditor!!.copy(name = "Projects"))
        val saved = fixture.transport.folderSaves.single().folder
        assertEquals(original.pinnedChatIds, saved.pinnedChatIds)
        assertEquals(original.includedChatIds, saved.includedChatIds)
        assertEquals(original.excludedChatIds, saved.excludedChatIds)
        assertEquals(4, saved.colorId)
        fixture.transport.folderSaves.single().result(ChatResult(ChatFolderInfo(7, "Projects")))
        fixture.flush()
        assertNull(fixture.state.folderEditorId)
        assertEquals("Projects", fixture.state.folders.single().name)
        fixture.controller.deleteFolder(fixture.state.token, 7)
        assertEquals(7, fixture.transport.folderDeletes.single().id)
        fixture.transport.folderDeletes.single().result(ChatResult(Unit))
        fixture.flush()
        assertTrue(fixture.state.folders.isEmpty())
    }

    @Test
    fun sharedFolderEditsAreRejectedAndClosingEditorIgnoresLateDetails() {
        val fixture = Fixture()
        fixture.event(ChatEvent.Folders(listOf(ChatFolderInfo(7, "Shared", isShareable = true))))
        fixture.controller.editFolder(fixture.state.token, 7)
        fixture.controller.closeFolderEditor(fixture.state.token)
        val shared = ChatFolder(7, "Shared", isShareable = true, includedChatIds = listOf(100))
        fixture.transport.folderLoads.single().result(ChatResult(shared))
        fixture.flush()
        assertNull(fixture.state.folderEditorId)
        assertNull(fixture.state.folderEditor)
        fixture.controller.editFolder(fixture.state.token, 7)
        fixture.transport.folderLoads.last().result(ChatResult(shared))
        fixture.flush()
        fixture.controller.saveFolder(fixture.state.token, fixture.state.folderEditor!!.copy(name = "Changed"))
        assertEquals(ChatError.UNSUPPORTED, fixture.state.folderError)
        assertTrue(fixture.transport.folderSaves.isEmpty())
    }

    @Test
    fun lateFolderSaveCannotRestoreADeletedFolderOrUndoANewerRename() {
        for (replacement in listOf(emptyList(), listOf(ChatFolderInfo(7, "New remote name")))) {
            val fixture = Fixture()
            fixture.event(ChatEvent.Folders(listOf(ChatFolderInfo(7, "Work"))))
            fixture.controller.editFolder(fixture.state.token, 7)
            val original = ChatFolder(7, "Work", includeGroups = true)
            fixture.transport.folderLoads.single().result(ChatResult(original))
            fixture.flush()
            fixture.controller.saveFolder(fixture.state.token, fixture.state.folderEditor!!.copy(name = "Local name"))
            fixture.event(ChatEvent.Folders(replacement))
            fixture.transport.folderSaves.single().result(ChatResult(ChatFolderInfo(7, "Local name")))
            fixture.flush()
            assertEquals(replacement, fixture.state.folders)
            assertFalse(fixture.state.folderSaving)
        }
    }

    @Test
    fun folderSourcesBindTheSameFolderToItsActualEditorRevision() {
        val fixture = Fixture()
        fixture.event(ChatEvent.Folders(listOf(ChatFolderInfo(7, "Work"))))
        fixture.controller.editFolder(fixture.state.token, 7)
        fixture.transport.folderLoads.last().result(ChatResult(ChatFolder(7, "Work", sourceId = 100, includeGroups = true)))
        fixture.flush()
        val old = fixture.state.folderEditor!!.copy(name = "Old draft")
        fixture.controller.editFolder(fixture.state.token, 7)
        fixture.transport.folderLoads.last().result(ChatResult(ChatFolder(7, "New source", sourceId = 101, includeGroups = true)))
        fixture.flush()
        val current = fixture.state.folderEditor!!
        fixture.controller.saveFolder(fixture.state.token, old)
        fixture.controller.saveFolder(fixture.state.token, old.copy(sourceId = current.sourceId))
        assertTrue(fixture.transport.folderSaves.isEmpty(), "A matching folder ID and even a new source key cannot authorize an old editor revision.")
        fixture.controller.saveFolder(fixture.state.token, current.copy(name = "Own draft"))
        assertEquals(1, fixture.transport.folderSaves.size)
    }

    @Test
    fun folderOpeningAndSubmissionLeasesRetireBeforeNativeCallbacksCanSendAnotherStage() {
        val fixture = Fixture()
        fixture.event(ChatEvent.Folders(listOf(ChatFolderInfo(7, "Work"))))
        fixture.controller.editFolder(fixture.state.token, 7)
        val opening = fixture.transport.folderLoads.last()
        assertTrue(opening.current())
        fixture.controller.closeFolderEditor(fixture.state.token)
        assertFalse(opening.current())
        fixture.controller.editFolder(fixture.state.token, 7)
        fixture.transport.folderLoads.last().result(ChatResult(ChatFolder(7, "Work", sourceId = 100, includeGroups = true)))
        fixture.flush()
        fixture.controller.saveFolder(fixture.state.token, fixture.state.folderEditor!!.copy(name = "Own draft"))
        val submission = fixture.transport.folderSaves.single()
        assertTrue(submission.current())
        val before = fixture.state
        fixture.controller.deactivate()
        assertFalse(submission.current())
        submission.result(ChatResult(ChatFolderInfo(7, "Own draft")))
        fixture.flush()
        assertEquals(before, fixture.state, "A retired native acceptance cannot publish or consume another editor.")
    }

    @Test
    fun conflictingFolderSavesKeepTheOriginalEditorRevisionAndAllowOnlyAnExplicitMatchingRetry() {
        val fixture = Fixture()
        fixture.event(ChatEvent.Folders(listOf(ChatFolderInfo(7, "Work"))))
        fixture.controller.editFolder(fixture.state.token, 7)
        fixture.transport.folderLoads.last().result(ChatResult(ChatFolder(7, "Work", sourceId = 100, includeGroups = true)))
        fixture.flush()
        val original = fixture.state.folderEditor!!
        val desired = original.copy(includeBots = true)
        fixture.controller.saveFolder(fixture.state.token, desired)
        fixture.transport.folderSaves.single().result(ChatResult(error = ChatError.NOT_READY))
        fixture.flush()
        assertEquals(original, fixture.state.folderEditor)
        assertEquals(original.editorRevision, fixture.state.folderRevision)
        assertEquals(ChatError.NOT_READY, fixture.state.folderError)
        assertFalse(fixture.state.folderSaving)
        assertEquals(1, fixture.transport.folderSaves.size, "A failed result cannot automatically reissue the native edit.")
        fixture.controller.saveFolder(fixture.state.token, desired.copy(editorRevision = original.editorRevision - 1))
        assertEquals(1, fixture.transport.folderSaves.size)
        fixture.controller.saveFolder(fixture.state.token, desired)
        assertEquals(desired, fixture.transport.folderSaves.last().folder)
        assertEquals(2, fixture.transport.folderSaves.size)
        fixture.transport.folderSaves.last().result(ChatResult(ChatFolderInfo(7, "Work")))
        fixture.flush()
        assertNull(fixture.state.folderEditor)
        assertFalse(fixture.state.folderSaving)
    }

    @Test
    fun replyIsCapturedBySendAndOnlyTheUnchangedSelectionIsCleared() {
        val fixture = selectedFixture()
        fixture.history(listOf(message(10), message(11)))
        fixture.controller.setReply(fixture.state.token, 10)
        fixture.controller.sendText(fixture.state.token, "first response")
        assertEquals(10L, fixture.transport.sends.single().replyId)
        fixture.controller.setReply(fixture.state.token, 11)
        fixture.transport.sends.single().result(ChatResult(message(20, delivery = MessageDelivery.PENDING)))
        fixture.flush()
        assertEquals(11L, fixture.state.replyTarget?.id)

        fixture.controller.sendText(fixture.state.token, "second response")
        fixture.controller.setReply(fixture.state.token, null)
        fixture.controller.setReply(fixture.state.token, 11)
        fixture.transport.sends.last().result(ChatResult(message(21, delivery = MessageDelivery.PENDING)))
        fixture.flush()
        assertEquals(11L, fixture.state.replyTarget?.id)

        fixture.controller.sendText(fixture.state.token, "retryable response")
        fixture.transport.sends.last().result(ChatResult(error = ChatError.REQUEST_FAILED))
        fixture.flush()
        assertEquals(11L, fixture.state.replyTarget?.id)
        fixture.controller.sendText(fixture.state.token, "accepted response")
        fixture.transport.sends.last().result(ChatResult(message(22, delivery = MessageDelivery.PENDING)))
        fixture.flush()
        assertNull(fixture.state.replyTarget)
    }

    @Test
    fun replyRequiresAServerMessageAndTracksEditsDeletionAndChatSelection() {
        val fixture = selectedFixture()
        fixture.history(listOf(message(10), message(0), message(-1),
            message(20, delivery = MessageDelivery.PENDING), message(30, delivery = MessageDelivery.FAILED)))
        for (id in listOf(0L, -1L, 20L, 30L, 999L)) fixture.controller.setReply(fixture.state.token, id)
        assertNull(fixture.state.replyTarget)
        fixture.controller.setReply(fixture.state.token, 10)
        fixture.event(ChatEvent.Content(1, 10, "edited target", MessageKind.TEXT))
        assertEquals("edited target", fixture.state.replyTarget?.text)
        fixture.event(ChatEvent.Deleted(1, listOf(10), true))
        assertNull(fixture.state.replyTarget)

        fixture.event(ChatEvent.NewMessage(message(11)))
        fixture.controller.setReply(fixture.state.token, 11)
        fixture.controller.sendText(fixture.state.token, "in flight")
        val oldToken = fixture.state.token
        fixture.chat(2)
        fixture.select(2)
        fixture.history(listOf(message(11, chatId = 2)))
        fixture.controller.setReply(fixture.state.token, 11)
        fixture.controller.setReply(oldToken, null)
        fixture.transport.sends.single().result(ChatResult(message(40, delivery = MessageDelivery.PENDING)))
        fixture.flush()
        assertEquals(2L, fixture.state.replyTarget?.chatId)
        assertEquals(11L, fixture.state.replyTarget?.id)
    }

    @Test
    fun photoStickerAndAttachmentSendsIncludeReplyWithoutClearingTextDrafts() {
        val fixture = selectedFixture()
        fixture.history(listOf(message(10)))
        fixture.controller.loadStickers(fixture.state.token)
        fixture.transport.recents.single()(ChatResult(listOf(sticker(5))))
        fixture.flush()
        fixture.controller.setReply(fixture.state.token, 10)
        fixture.controller.sendPhoto(fixture.state.token, "C:/synthetic/photo.jpg", "photo caption")
        assertEquals(10L, fixture.transport.photos.single().replyId)
        fixture.transport.photos.single().result(ChatResult(message(20, delivery = MessageDelivery.PENDING).copy(kind = MessageKind.PHOTO)))
        fixture.flush()
        assertNull(fixture.state.replyTarget)
        assertNull(fixture.state.acceptedSendText)

        fixture.controller.setReply(fixture.state.token, 10)
        fixture.controller.sendSticker(fixture.state.token, sticker(5))
        assertEquals(10L, fixture.transport.stickerSends.single().replyId)
        fixture.transport.stickerSends.single().result(ChatResult(message(21, delivery = MessageDelivery.PENDING).copy(kind = MessageKind.STICKER)))
        fixture.flush()
        assertNull(fixture.state.replyTarget)
        assertNull(fixture.state.acceptedSendText)

        fixture.controller.setReply(fixture.state.token, 10)
        fixture.controller.sendAttachment(fixture.state.token, attachment(AttachmentKind.FILE), "file caption")
        assertEquals(10L, fixture.transport.attachments.single().replyId)
        fixture.transport.attachments.single().result(ChatResult(message(22, delivery = MessageDelivery.PENDING).copy(kind = MessageKind.FILE)))
        fixture.flush()
        assertNull(fixture.state.replyTarget)
        assertNull(fixture.state.acceptedSendText)
    }

    @Test
    fun videoAndDocumentPermissionsAreIndependentAndCheckedAgainForRetry() {
        val fixture = selectedFixture()
        fixture.event(ChatEvent.Permissions(1, canSend = false, canSendVideos = true, canSendDocuments = false))
        fixture.controller.sendAttachment(fixture.state.token, attachment(AttachmentKind.FILE), "caption")
        assertEquals(ChatError.FORBIDDEN, fixture.state.sendError)
        assertTrue(fixture.transport.attachments.isEmpty())
        fixture.controller.sendAttachment(fixture.state.token, attachment(AttachmentKind.VIDEO), "caption")
        fixture.controller.sendAttachment(fixture.state.token, attachment(AttachmentKind.VIDEO), "duplicate")
        assertEquals(1, fixture.transport.attachments.size)
        fixture.transport.attachments.single().result(ChatResult(message(30, delivery = MessageDelivery.PENDING).copy(kind = MessageKind.VIDEO)))
        fixture.flush()
        fixture.event(ChatEvent.SendFailed(message(30, delivery = MessageDelivery.FAILED, canRetry = true).copy(kind = MessageKind.VIDEO), 30))
        fixture.event(ChatEvent.Permissions(1, canSend = true, canSendVideos = false, canSendDocuments = true))
        fixture.controller.retry(fixture.state.token, 30)
        assertEquals(ChatError.FORBIDDEN, fixture.state.sendError)
        assertTrue(fixture.transport.retries.isEmpty())
        fixture.controller.sendAttachment(fixture.state.token, attachment(AttachmentKind.FILE), "caption")
        assertEquals(AttachmentKind.FILE, fixture.transport.attachments.last().attachment.kind)
        fixture.transport.attachments.last().result(ChatResult(message(31, delivery = MessageDelivery.FAILED, canRetry = true).copy(kind = MessageKind.FILE)))
        fixture.flush()
        fixture.controller.retry(fixture.state.token, 31)
        assertEquals(31L, fixture.transport.retries.single().id)
    }

    @Test
    fun invalidAttachmentsNeverDispatchAndVideoUploadUsesAuthoritativeFileUpdates() {
        val fixture = selectedFixture()
        val valid = attachment(AttachmentKind.VIDEO)
        for (invalid in listOf(valid.copy(path = ""), valid.copy(path = "bad\u0000path"), valid.copy(fileName = ""),
            valid.copy(size = 0), valid.copy(size = -1), valid.copy(durationSeconds = -1), valid.copy(width = -1), valid.copy(height = -1))) {
            fixture.controller.sendAttachment(fixture.state.token, invalid, "")
            assertEquals(ChatError.REQUEST_FAILED, fixture.state.sendError)
        }
        fixture.controller.sendAttachment(fixture.state.token, valid, "a".repeat(1025))
        assertEquals(ChatError.TEXT_TOO_LONG, fixture.state.sendError)
        assertTrue(fixture.transport.attachments.isEmpty())
        fixture.controller.sendAttachment(fixture.state.token, valid, "🙂".repeat(1024))
        val progressed = MediaFile(50, size = 100, uploading = true, uploadedSize = 75)
        fixture.event(ChatEvent.FileUpdated(progressed))
        fixture.transport.attachments.single().result(ChatResult(message(30, delivery = MessageDelivery.PENDING).copy(
            kind = MessageKind.VIDEO, media = MessageMedia(progressed.copy(uploadedSize = 0), format = MediaFormat.VIDEO))))
        fixture.flush()
        assertEquals(75L, fixture.state.mediaFiles[50]?.uploadedSize)
        fixture.event(ChatEvent.FileUpdated(progressed.copy(uploading = false, uploadedSize = 100)))
        assertFalse(fixture.state.mediaFiles.getValue(50).uploading)
        assertEquals(100L, fixture.state.mediaFiles[50]?.uploadedSize)
    }

    @Test
    fun readDetailsOnlyUseExplicitServerResultsAndIgnoreOlderRequests() {
        val fixture = selectedFixture()
        fixture.history(listOf(message(10), message(11), message(12, delivery = MessageDelivery.PENDING)))
        fixture.controller.loadReadDetails(fixture.state.token, 12)
        fixture.controller.loadReadDetails(fixture.state.token, 999)
        assertTrue(fixture.transport.readDetails.isEmpty())
        fixture.controller.loadReadDetails(fixture.state.token, 10)
        fixture.controller.loadReadDetails(fixture.state.token, 10)
        assertEquals(1, fixture.transport.readDetails.size)
        fixture.controller.loadReadDetails(fixture.state.token, 11)
        fixture.transport.readDetails.first().result(ChatResult(MessageReadDetails(availability = MessageReadAvailability.UNREAD)))
        fixture.flush()
        assertEquals(11L, fixture.state.readDetailsMessageId)
        assertTrue(fixture.state.readDetailsLoading)
        assertNull(fixture.state.readDetails)
        val readers = mutableListOf(MessageReader(55, "Synthetic Reader", 1234, MediaFile(90, canDownload = true)))
        fixture.transport.readDetails.last().result(ChatResult(MessageReadDetails(readers, MessageReadAvailability.AVAILABLE)))
        fixture.flush()
        readers.clear()
        assertEquals(55L, fixture.state.readDetails?.readers?.single()?.userId)
        assertEquals(1234, fixture.state.readDetails?.readers?.single()?.readDate)
        assertEquals(MessageReadAvailability.AVAILABLE, fixture.state.readDetails?.availability)
        assertTrue(fixture.transport.reads.isEmpty())
        fixture.controller.downloadFile(fixture.state.token, 90)
        assertEquals(90, fixture.transport.downloads.single().id)
    }

    @Test
    fun closingDeletingOrChangingChatDiscardsLateReadDetails() {
        for (action in listOf("close", "delete", "chat")) {
            val fixture = selectedFixture()
            fixture.history(listOf(message(10)))
            fixture.controller.loadReadDetails(fixture.state.token, 10)
            when (action) {
                "close" -> fixture.controller.closeReadDetails(fixture.state.token)
                "delete" -> fixture.event(ChatEvent.Deleted(1, listOf(10), true))
                "chat" -> {
                    fixture.chat(2)
                    fixture.select(2)
                    fixture.history(listOf(message(10, chatId = 2)))
                }
            }
            fixture.transport.readDetails.single().result(ChatResult(MessageReadDetails(
                listOf(MessageReader(55, "Synthetic Reader", 1234)), MessageReadAvailability.AVAILABLE)))
            fixture.flush()
            assertNull(fixture.state.readDetailsMessageId)
            assertNull(fixture.state.readDetails)
            assertNull(fixture.state.readDetailsError)
            assertFalse(fixture.state.readDetailsLoading)
        }
    }

    @Test
    fun readDetailsPreserveUnavailableStatesAndAllowErrorRetry() {
        val fixture = selectedFixture()
        fixture.history(listOf(message(10)))
        fixture.controller.loadReadDetails(fixture.state.token, 10)
        fixture.transport.readDetails.last().result(ChatResult(error = ChatError.RATE_LIMITED))
        fixture.flush()
        assertEquals(ChatError.RATE_LIMITED, fixture.state.readDetailsError)
        assertFalse(fixture.state.readDetailsLoading)
        for (availability in MessageReadAvailability.entries) {
            fixture.controller.loadReadDetails(fixture.state.token, 10)
            fixture.transport.readDetails.last().result(ChatResult(MessageReadDetails(availability = availability)))
            fixture.flush()
            assertEquals(availability, fixture.state.readDetails?.availability)
            assertTrue(fixture.state.readDetails?.readers.orEmpty().isEmpty())
            assertNull(fixture.state.readDetailsError)
        }
    }

    @Test
    fun emptyReplyPreviewResolvesOnceWithoutOverwritingEditedMessageText() {
        val fixture = selectedFixture()
        val referring = message(20).copy(replyTo = MessageReply(1, 10))
        fixture.history(listOf(referring))
        fixture.event(ChatEvent.NewMessage(referring))
        assertEquals(20L, fixture.transport.replyMessages.single().id)
        fixture.event(ChatEvent.Content(1, 20, "edited response", MessageKind.TEXT))
        fixture.transport.replyMessages.single().result(ChatResult(message(10, text = "quoted original").copy(senderId = 55)))
        fixture.flush()
        assertEquals("edited response", fixture.state.messages.single().text)
        assertEquals("quoted original", fixture.state.messages.single().replyTo?.text)
        assertEquals(55L, fixture.state.messages.single().replyTo?.senderId)
        fixture.event(ChatEvent.NewMessage(referring))
        assertEquals("quoted original", fixture.state.messages.single().replyTo?.text)
        assertEquals(1, fixture.transport.replyMessages.size)
    }

    @Test
    fun replyPreviewUsesLoadedTargetsAndRejectsUnrelatedOrDeletedTargets() {
        val fixture = selectedFixture()
        fixture.history(listOf(message(10, text = "known target")))
        fixture.event(ChatEvent.NewMessage(message(20).copy(replyTo = MessageReply(1, 10))))
        assertEquals("known target", fixture.state.messages.last().replyTo?.text)
        assertTrue(fixture.transport.replyMessages.isEmpty())
        fixture.event(ChatEvent.NewMessage(message(30).copy(replyTo = MessageReply(1, 25))))
        fixture.transport.replyMessages.last().result(ChatResult(message(25, chatId = 2, text = "wrong chat")))
        fixture.flush()
        assertEquals("", fixture.state.messages.last().replyTo?.text)
        fixture.event(ChatEvent.NewMessage(message(40).copy(replyTo = MessageReply(1, 35))))
        fixture.event(ChatEvent.Deleted(1, listOf(35), true))
        fixture.transport.replyMessages.last().result(ChatResult(message(35, text = "deleted target")))
        fixture.flush()
        assertEquals("", fixture.state.messages.last().replyTo?.text)
    }

    @Test
    fun replyPreviewCallbacksCannotCrossSelectionOrReviveDeletedMessages() {
        for (changeChat in listOf(false, true)) {
            val fixture = selectedFixture()
            fixture.history(listOf(message(20).copy(replyTo = MessageReply(1, 10))))
            if (changeChat) {
                fixture.chat(2)
                fixture.select(2)
                fixture.history(listOf(message(20, chatId = 2)))
            } else fixture.event(ChatEvent.Deleted(1, listOf(20), true))
            fixture.transport.replyMessages.single().result(ChatResult(message(10, text = "late quote")))
            fixture.flush()
            assertTrue(fixture.state.messages.none { it.chatId == 1L })
            assertTrue(fixture.state.messages.all { it.replyTo == null })
        }
    }

    @Test
    fun deletingResolvedReplyTargetsInvalidatesFetchedPreviewsButPreservesNativeQuotes() {
        for (targetChatId in listOf(1L, 2L)) {
            val fixture = selectedFixture()
            val referring = message(20).copy(replyTo = MessageReply(targetChatId, 10))
            fixture.history(listOf(referring, message(30).copy(replyTo = MessageReply(targetChatId, 10, "explicit quote"))))
            fixture.transport.replyMessages.single().result(ChatResult(message(10, chatId = targetChatId, text = "fetched preview")))
            fixture.flush()
            assertEquals("fetched preview", fixture.state.messages.first().replyTo?.text)
            fixture.event(ChatEvent.Deleted(targetChatId, listOf(10), true))
            assertEquals("", fixture.state.messages.first().replyTo?.text)
            assertEquals("explicit quote", fixture.state.messages.last().replyTo?.text)
            fixture.event(ChatEvent.NewMessage(referring))
            assertEquals("", fixture.state.messages.first().replyTo?.text)
            fixture.event(ChatEvent.NewMessage(message(40).copy(replyTo = MessageReply(targetChatId, 10))))
            assertEquals(1, fixture.transport.replyMessages.size)
        }
    }

    @Test
    fun replyResolutionPrefersLiveTargetEditsAndLoadedTargetsToOlderResponses() {
        for (targetLoaded in listOf(false, true)) {
            val fixture = selectedFixture()
            fixture.history(listOf(message(20).copy(replyTo = MessageReply(1, 10))))
            if (targetLoaded) fixture.event(ChatEvent.NewMessage(message(10, text = "newly loaded target")))
            else fixture.event(ChatEvent.Content(1, 10, "live target edit", MessageKind.TEXT))
            fixture.transport.replyMessages.single().result(ChatResult(message(10, text = "old response")))
            fixture.flush()
            assertEquals(if (targetLoaded) "newly loaded target" else "live target edit", fixture.state.messages.last().replyTo?.text)
        }
    }

    @Test
    fun resolvedReplyPreviewsRefreshAfterTargetEditsWithoutChangingExplicitQuotes() {
        for (targetChatId in listOf(1L, 2L)) {
            val fixture = selectedFixture()
            val referring = message(20).copy(replyTo = MessageReply(targetChatId, 10))
            val explicit = message(30).copy(replyTo = MessageReply(targetChatId, 10, "explicit quote"))
            fixture.history(listOf(referring, explicit))
            fixture.transport.replyMessages.single().result(ChatResult(message(10, chatId = targetChatId, text = "fetched preview")))
            fixture.flush()
            assertTrue(fixture.state.messages.none { it.id == 10L })
            fixture.event(ChatEvent.Content(targetChatId, 10, "edited original", MessageKind.TEXT))
            assertEquals("edited original", fixture.state.messages.first().replyTo?.text)
            assertEquals("explicit quote", fixture.state.messages.last().replyTo?.text)
            fixture.event(ChatEvent.NewMessage(referring))
            assertEquals("edited original", fixture.state.messages.first().replyTo?.text)
            assertEquals("explicit quote", fixture.state.messages.last().replyTo?.text)
            assertEquals(1, fixture.transport.replyMessages.size)
        }
    }

    @Test
    fun protectedContentUpdatesKeepNativeSavePermissionsSeparateFromDownloads() {
        val fixture = selectedFixture()
        fixture.history(listOf(message(10).copy(canBeSaved = true, kind = MessageKind.FILE,
            media = MessageMedia(MediaFile(80, canDownload = true), format = MediaFormat.FILE))))
        fixture.event(ChatEvent.ProtectedContent(1, true))
        assertTrue(fixture.state.selected?.hasProtectedContent == true)
        assertTrue(fixture.state.messages.single().canBeSaved)
        fixture.controller.downloadFile(fixture.state.token, 80)
        assertEquals(80, fixture.transport.downloads.single().id)
    }

    @Test
    fun messageActionCallbacksCannotReopenClosedDeletedOrAnotherChatMenus() {
        for (action in listOf("close", "delete", "chat")) {
            val fixture = selectedFixture()
            fixture.history(listOf(message(10), message(20, delivery = MessageDelivery.PENDING)))
            fixture.controller.loadMessageActions(fixture.state.token, 0)
            fixture.controller.loadMessageActions(fixture.state.token, 999)
            assertTrue(fixture.transport.actions.isEmpty())
            fixture.controller.loadMessageActions(fixture.state.token, 10)
            fixture.controller.loadMessageActions(fixture.state.token, 10)
            assertEquals(1, fixture.transport.actions.size)
            when (action) {
                "close" -> fixture.controller.closeMessageActions(fixture.state.token)
                "delete" -> fixture.event(ChatEvent.Deleted(1, listOf(10), true))
                "chat" -> { fixture.chat(2); fixture.select(2) }
            }
            fixture.transport.actions.single().result(ChatResult(MessageActions(canEditText = true)))
            fixture.flush()
            assertNull(fixture.state.actionsMessageId)
            assertNull(fixture.state.messageActions)
            assertFalse(fixture.state.actionsLoading)
        }
    }

    @Test
    fun editingPreservesTheNormalReplyAndQuoteWithoutRestoringDeletedOrOtherChatTargets() {
        for (finish in listOf("cancel", "save", "delete", "switch")) {
            val fixture = selectedFixture()
            fixture.history(listOf(message(10, text = "reply source"), message(11, text = "edit source")))
            fixture.controller.setQuote(fixture.state.token, 10, MessageQuote(0, RichMessageText("reply")))
            fixture.actions(11, MessageActions(canEditText = true))
            fixture.controller.startEdit(fixture.state.token, 11)
            assertEquals(10L, fixture.state.replyTarget?.id)
            assertEquals("reply", fixture.state.replyQuote?.content?.text)
            when (finish) {
                "cancel" -> fixture.controller.cancelEdit(fixture.state.token)
                "save" -> {
                    fixture.controller.editMessage(fixture.state.token, "changed")
                    fixture.transport.edits.single().result(ChatResult(message(11, text = "changed")))
                    fixture.flush()
                }
                "delete" -> {
                    fixture.event(ChatEvent.Deleted(1, listOf(10), true))
                    fixture.controller.cancelEdit(fixture.state.token)
                }
                "switch" -> { fixture.chat(2); fixture.select(2) }
            }
            assertNull(fixture.state.editTarget)
            if (finish == "cancel" || finish == "save") {
                assertEquals(10L, fixture.state.replyTarget?.id)
                assertEquals("reply", fixture.state.replyQuote?.content?.text)
            } else {
                assertNull(fixture.state.replyTarget)
                assertNull(fixture.state.replyQuote)
            }
        }
    }

    @Test
    fun editsRequirePermissionsAndDoNotClearTheNormalComposerReceipt() {
        val fixture = selectedFixture()
        fixture.history(listOf(message(10)))
        fixture.controller.startEdit(fixture.state.token, 10)
        assertNull(fixture.state.editTarget)
        fixture.actions(10, MessageActions(canEditText = true))
        fixture.controller.closeMessageActions(fixture.state.token)
        fixture.controller.startEdit(fixture.state.token, 10)
        fixture.controller.editMessage(fixture.state.token, "")
        assertEquals(ChatError.EMPTY_TEXT, fixture.state.editError)
        assertTrue(fixture.transport.edits.isEmpty())
        fixture.controller.editMessage(fixture.state.token, " edited text ")
        fixture.controller.editMessage(fixture.state.token, "duplicate")
        assertEquals(1, fixture.transport.edits.size)
        assertEquals(" edited text ", fixture.transport.edits.single().text)
        assertFalse(fixture.transport.edits.single().caption)
        fixture.transport.edits.single().result(ChatResult(error = ChatError.RATE_LIMITED))
        fixture.flush()
        assertEquals(10L, fixture.state.editTarget?.id)
        assertEquals(ChatError.RATE_LIMITED, fixture.state.editError)
        fixture.controller.editMessage(fixture.state.token, " edited text ")
        fixture.transport.edits.last().result(ChatResult(message(10, text = " edited text ").copy(editDate = 1234)))
        fixture.flush()
        assertNull(fixture.state.editTarget)
        assertEquals(" edited text ", fixture.state.messages.single().text)
        assertEquals(1234, fixture.state.messages.single().editDate)
        assertEquals(0L, fixture.state.acceptedSendId)
        assertNull(fixture.state.acceptedSendText)
    }

    @Test
    fun captionEditsAllowClearingAndCountUnicodeCodePoints() {
        val fixture = selectedFixture()
        fixture.history(listOf(message(10, text = "caption").copy(kind = MessageKind.VIDEO)))
        fixture.actions(10, MessageActions(canEditCaption = true))
        fixture.controller.startEdit(fixture.state.token, 10)
        fixture.controller.editMessage(fixture.state.token, "😀".repeat(1025))
        assertEquals(ChatError.TEXT_TOO_LONG, fixture.state.editError)
        fixture.controller.editMessage(fixture.state.token, "")
        assertTrue(fixture.transport.edits.single().caption)
        assertEquals("", fixture.transport.edits.single().text)
        fixture.transport.edits.single().result(ChatResult(message(10, text = "").copy(kind = MessageKind.VIDEO)))
        fixture.flush()
        assertNull(fixture.state.editTarget)
        assertEquals("", fixture.state.messages.single().text)
    }

    @Test
    fun editAcknowledgementsCannotUndoNewContentOrEditedDates() {
        val fixture = selectedFixture()
        fixture.history(listOf(message(10)))
        fixture.event(ChatEvent.Content(1, 10, "previous edit", MessageKind.TEXT))
        fixture.actions(10, MessageActions(canEditText = true))
        fixture.controller.startEdit(fixture.state.token, 10)
        fixture.controller.editMessage(fixture.state.token, "my edit")
        fixture.event(ChatEvent.Content(1, 10, "new remote edit", MessageKind.TEXT))
        fixture.event(ChatEvent.Edited(1, 10, 200))
        fixture.transport.edits.single().result(ChatResult(message(10, text = "my edit").copy(editDate = 100)))
        fixture.flush()
        assertEquals("new remote edit", fixture.state.messages.single().text)
        assertEquals(200, fixture.state.messages.single().editDate)
        assertNull(fixture.state.editTarget)
        fixture.controller.startEdit(fixture.state.token, 10)
        fixture.controller.editMessage(fixture.state.token, "new local edit")
        fixture.transport.edits.last().result(ChatResult(message(10, text = "new local edit").copy(editDate = 300)))
        fixture.flush()
        assertEquals("new local edit", fixture.state.messages.single().text)
        assertEquals(300, fixture.state.messages.single().editDate)
    }

    @Test
    fun canceledDeletedAndOtherChatEditsDiscardLateAcknowledgements() {
        for (action in listOf("cancel", "delete", "chat")) {
            val fixture = selectedFixture()
            fixture.history(listOf(message(10)))
            fixture.actions(10, MessageActions(canEditText = true))
            fixture.controller.startEdit(fixture.state.token, 10)
            fixture.controller.editMessage(fixture.state.token, "old edit")
            when (action) {
                "cancel" -> {
                    fixture.controller.cancelEdit(fixture.state.token)
                    fixture.controller.startEdit(fixture.state.token, 10)
                    fixture.controller.editMessage(fixture.state.token, "new edit")
                }
                "delete" -> fixture.event(ChatEvent.Deleted(1, listOf(10), true))
                "chat" -> { fixture.chat(2); fixture.select(2) }
            }
            fixture.transport.edits.first().result(ChatResult(message(10, text = "old edit")))
            fixture.flush()
            assertTrue(fixture.state.messages.none { it.text == "old edit" })
            if (action == "cancel") {
                assertEquals(10L, fixture.state.editTarget?.id)
                assertTrue(fixture.state.editPending)
            } else {
                assertNull(fixture.state.editTarget)
                assertFalse(fixture.state.editPending)
            }
        }
    }

    @Test
    fun deleteUsesExplicitScopePermissionsAndWaitsForServerAcknowledgement() {
        val fixture = selectedFixture()
        fixture.history(listOf(message(10)))
        fixture.actions(10, MessageActions(canDeleteForSelf = true))
        fixture.controller.deleteMessage(fixture.state.token, 10, revoke = true)
        assertEquals(ChatError.FORBIDDEN, fixture.state.mutationError)
        assertTrue(fixture.transport.deletes.isEmpty())
        fixture.controller.closeMessageActions(fixture.state.token)
        fixture.controller.deleteMessage(fixture.state.token, 10, revoke = false)
        fixture.controller.deleteMessage(fixture.state.token, 10, revoke = false)
        assertFalse(fixture.transport.deletes.single().revoke)
        assertEquals(10L, fixture.state.messages.single().id)
        fixture.transport.deletes.single().result(ChatResult(error = ChatError.REQUEST_FAILED))
        fixture.flush()
        assertEquals(10L, fixture.state.messages.single().id)
        assertEquals(ChatError.REQUEST_FAILED, fixture.state.mutationError)
        assertEquals(0L, fixture.state.mutationRevision)
        fixture.controller.deleteMessage(fixture.state.token, 10, revoke = false)
        fixture.transport.deletes.last().result(ChatResult(Unit))
        fixture.flush()
        assertTrue(fixture.state.messages.isEmpty())
        assertFalse(fixture.state.mutationPending)
        assertNull(fixture.state.mutationError)
        assertEquals(1L, fixture.state.mutationRevision)
        assertEquals(10L, fixture.state.mutationMessageId)
        assertEquals(ChatMutationKind.DELETE, fixture.state.mutationKind)
    }

    @Test
    fun pendingAndFailedMessagesCanBeDeletedButCannotBeRepliedEditedOrForwarded() {
        for (delivery in listOf(MessageDelivery.PENDING, MessageDelivery.FAILED)) {
            val fixture = selectedFixture()
            fixture.history(listOf(message(10, delivery = delivery)))
            fixture.chat(2)
            fixture.actions(10, MessageActions(canEditText = true, canDeleteForSelf = true, canForward = true))
            assertFalse(fixture.state.actionsLoading)
            assertEquals(10L, fixture.state.actionsMessageId)
            fixture.controller.setReply(fixture.state.token, 10)
            fixture.controller.startEdit(fixture.state.token, 10)
            fixture.controller.forwardMessage(fixture.state.token, 10, 2)
            assertNull(fixture.state.replyTarget)
            assertNull(fixture.state.editTarget)
            assertTrue(fixture.transport.forwards.isEmpty())
            fixture.controller.deleteMessage(fixture.state.token, 10, false)
            assertEquals(10L, fixture.transport.deletes.single().id)
            fixture.transport.deletes.single().result(ChatResult(Unit))
            fixture.flush()
            assertTrue(fixture.state.messages.isEmpty())
        }
    }

    @Test
    fun oldMutationFailuresCannotChangeNewSelectionFeedback() {
        val fixture = selectedFixture()
        fixture.history(listOf(message(10)))
        fixture.actions(10, MessageActions(canDeleteForSelf = true))
        fixture.controller.deleteMessage(fixture.state.token, 10, false)
        fixture.chat(2)
        fixture.select(2)
        fixture.history(listOf(message(10, chatId = 2)))
        fixture.actions(10, MessageActions(canDeleteForAll = true))
        fixture.controller.deleteMessage(fixture.state.token, 10, true)
        fixture.transport.deletes.first().result(ChatResult(error = ChatError.FORBIDDEN))
        fixture.flush()
        assertTrue(fixture.state.mutationPending)
        assertNull(fixture.state.mutationError)
        fixture.transport.deletes.last().result(ChatResult(Unit))
        fixture.flush()
        assertTrue(fixture.state.messages.isEmpty())
        assertEquals(1L, fixture.state.mutationRevision)
    }

    @Test
    fun forwardingChecksProtectionAndTargetPermissionsBeforeDispatch() {
        val fixture = selectedFixture()
        fixture.history(listOf(message(10).copy(kind = MessageKind.VIDEO)))
        fixture.actions(10, MessageActions(canForward = true))
        fixture.controller.forwardMessage(fixture.state.token, 10, 999)
        assertEquals(ChatError.UNSUPPORTED, fixture.state.mutationError)
        fixture.chat(2)
        fixture.event(ChatEvent.Permissions(2, true, canSendVideos = false))
        fixture.controller.forwardMessage(fixture.state.token, 10, 2)
        assertEquals(ChatError.FORBIDDEN, fixture.state.mutationError)
        fixture.event(ChatEvent.Permissions(2, false, canSendVideos = true))
        fixture.event(ChatEvent.ProtectedContent(1, true))
        fixture.controller.forwardMessage(fixture.state.token, 10, 2)
        assertTrue(fixture.transport.forwards.isEmpty())
        fixture.event(ChatEvent.ProtectedContent(1, false))
        fixture.controller.forwardMessage(fixture.state.token, 10, 2)
        assertEquals(2L, fixture.transport.forwards.single().targetId)
    }

    @Test
    fun forwardedMessagesUpdateTargetPreviewAndReconcileDeliveryWithoutClearingDrafts() {
        val fixture = selectedFixture()
        fixture.history(listOf(message(10)))
        fixture.chat(2, order = 75)
        fixture.actions(10, MessageActions(canForward = true))
        fixture.controller.forwardMessage(fixture.state.token, 10, 2)
        val callback = fixture.transport.forwards.single().result
        callback(ChatResult(listOf(message(20, chatId = 2, delivery = MessageDelivery.PENDING))))
        fixture.flush()
        assertEquals(20L, fixture.state.knownChats.single { it.id == 2L }.lastMessage?.id)
        assertEquals(75L, fixture.state.knownChats.single { it.id == 2L }.order)
        assertEquals(listOf(10L), fixture.state.messages.map { it.id })
        assertEquals(1L, fixture.state.mutationRevision)
        assertEquals(10L, fixture.state.mutationMessageId)
        assertEquals(ChatMutationKind.FORWARD, fixture.state.mutationKind)
        fixture.event(ChatEvent.SendSucceeded(message(30, chatId = 2), 20))
        callback(ChatResult(listOf(message(20, chatId = 2, delivery = MessageDelivery.PENDING))))
        fixture.flush()
        assertEquals(30L, fixture.state.knownChats.single { it.id == 2L }.lastMessage?.id)
        assertEquals(1L, fixture.state.mutationRevision)
        assertEquals(0L, fixture.state.acceptedSendId)
        assertNull(fixture.state.acceptedSendText)
    }

    @Test
    fun forwardingOtherMediaStillChecksTargetWritePermissionsInsideTheActor() {
        val fixture = selectedFixture()
        fixture.history(listOf(message(10).copy(kind = MessageKind.VOICE)))
        fixture.chat(2)
        fixture.actions(10, MessageActions(canForward = true))
        fixture.event(ChatEvent.Permissions(2, false))
        fixture.controller.forwardMessage(fixture.state.token, 10, 2)
        assertEquals(ChatError.FORBIDDEN, fixture.state.mutationError)
        assertTrue(fixture.transport.forwards.isEmpty())
    }

    @Test
    fun forwardDeliveryBeforeAckUsesFinalMessageAndDoesNotOverwriteNewerTargetPreview() {
        for (newerPreview in listOf(false, true)) {
            val fixture = selectedFixture()
            fixture.history(listOf(message(10)))
            fixture.chat(2)
            fixture.actions(10, MessageActions(canForward = true))
            fixture.controller.forwardMessage(fixture.state.token, 10, 2)
            fixture.event(ChatEvent.SendSucceeded(message(30, chatId = 2), 20))
            if (newerPreview) fixture.event(ChatEvent.LastMessage(2, message(40, chatId = 2), 200))
            fixture.transport.forwards.single().result(ChatResult(listOf(message(20, chatId = 2, delivery = MessageDelivery.PENDING))))
            fixture.flush()
            assertEquals(if (newerPreview) 40L else 30L, fixture.state.knownChats.single { it.id == 2L }.lastMessage?.id)
        }
    }

    @Test
    fun forwardAckAfterChatSwitchOnlyUpdatesTargetModel() {
        val fixture = selectedFixture()
        fixture.history(listOf(message(10)))
        fixture.chat(2)
        fixture.actions(10, MessageActions(canForward = true))
        fixture.controller.forwardMessage(fixture.state.token, 10, 2)
        fixture.select(2)
        fixture.transport.forwards.single().result(ChatResult(listOf(message(20, chatId = 2, delivery = MessageDelivery.PENDING))))
        fixture.flush()
        assertEquals(20L, fixture.state.messages.single().id)
        assertEquals(20L, fixture.state.selected?.lastMessage?.id)
        assertNull(fixture.state.mutationMessageId)
        assertNull(fixture.state.mutationError)
        assertEquals(0L, fixture.state.mutationRevision)
    }

    @Test
    fun quotedSendRetainsUtf16RangeAndLateSuccessDoesNotClearANewerQuote() {
        val fixture = selectedFixture()
        fixture.history(listOf(message(10, text = "🙂 原文 后文")))
        val first = MessageQuote(3, RichMessageText("原文"))
        fixture.controller.setQuote(fixture.state.token, 10, first)
        fixture.controller.sendText(fixture.state.token, RichMessageText("答复", listOf(MessageEntity(0, 2, MessageEntityKind.BOLD))))
        assertEquals(first, fixture.transport.richSends.single().reply?.quote)
        val second = MessageQuote(6, RichMessageText("后文"))
        fixture.controller.setQuote(fixture.state.token, 10, second)
        fixture.transport.richSends.single().result(ChatResult(message(20)))
        fixture.flush()
        assertEquals(second, fixture.state.replyQuote)
        assertEquals(MessageEntityKind.BOLD, fixture.state.acceptedSendEntities.single().kind)
    }

    @Test
    fun editedOrDeletedQuoteCannotSilentlyBecomeAnOrdinaryReply() {
        val fixture = selectedFixture()
        fixture.history(listOf(message(10, text = "原文")))
        fixture.controller.setQuote(fixture.state.token, 10, MessageQuote(0, RichMessageText("原文")))
        fixture.event(ChatEvent.Content(1, 10, "已修改", MessageKind.TEXT))
        fixture.controller.sendText(fixture.state.token, "回复")
        assertEquals(ChatError.INVALID_QUOTE, fixture.state.sendError)
        assertTrue(fixture.transport.sends.isEmpty())
        assertTrue(fixture.transport.richSends.isEmpty())
        fixture.event(ChatEvent.Deleted(1, listOf(10), true))
        assertNull(fixture.state.replyQuote)
        assertNull(fixture.state.replyTarget)
    }

    @Test
    fun streamingRejectsArbitraryFilesAndCallbacksAfterChatChanges() {
        val fixture = selectedFixture()
        fixture.history(listOf(message(10).copy(kind = MessageKind.VIDEO,
            media = MessageMedia(MediaFile(7, size = 1000, canDownload = true), format = MediaFormat.VIDEO))))
        var result: ChatResult<ByteArray>? = null
        fixture.controller.readVideoPart(fixture.state.token, 10, 8, 0, 20) { result = it }
        assertEquals(ChatError.FORBIDDEN, result?.error)
        assertTrue(fixture.transport.videoParts.isEmpty())
        fixture.controller.readVideoPart(fixture.state.token, 10, 7, 0, 20) { result = it }
        fixture.chat(2)
        fixture.select(2)
        fixture.transport.videoParts.single()(ChatResult(ByteArray(20)))
        fixture.flush()
        assertEquals(ChatError.FORBIDDEN, result?.error)
    }

    @Test fun ownedUnknownLengthReadsUseNativeSizeAndAnOldSourceReleaseCannotRetireItsReplacement() {
        val fixture = selectedFixture()
        fixture.history(listOf(message(10).copy(kind = MessageKind.VIDEO,
            media = MessageMedia(MediaFile(7, size = 0, expectedSize = 1, canDownload = true), format = MediaFormat.VIDEO))))
        val old = MediaDownloadOwner(fixture.state.token, 10, MediaDownloadPurpose.PROBE, 101)
        val next = old.copy(leaseId = 102)
        var oldResult: ChatResult<ByteArray>? = null
        var newResult: ChatResult<ByteArray>? = null
        fixture.controller.openOwnedVideoPart(old, 7)
        fixture.controller.readOwnedVideoPart(old, 7, 9000, 20) { oldResult = it }
        fixture.controller.releaseOwnedVideoPart(old, 7)
        fixture.controller.openOwnedVideoPart(next, 7)
        fixture.controller.readOwnedVideoPart(next, 7, 12000, 20) { newResult = it }
        fixture.controller.releaseOwnedVideoPart(old, 7)
        var retired: ChatResult<ByteArray>? = null
        fixture.controller.readOwnedVideoPart(old, 7, 9000, 20) { retired = it }
        assertEquals(ChatError.FORBIDDEN, retired?.error)
        assertEquals(2, fixture.transport.videoParts.size)
        fixture.transport.videoParts[0](ChatResult(ByteArray(20)))
        fixture.transport.videoParts[1](ChatResult(ByteArray(20)))
        fixture.flush()
        assertEquals(ChatError.FORBIDDEN, oldResult?.error)
        assertEquals(20, newResult?.value?.size)
        assertEquals(listOf(old to 7), fixture.transport.releasedDownloads)
    }

    @Test fun finiteNativeRangesStoppingAreNotFileFailuresAndSelectionReleasesOnlyCurrentScopes() {
        val fixture = selectedFixture()
        val file = MediaFile(7, size = 1000, canDownload = true)
        fixture.history(listOf(message(10).copy(kind = MessageKind.VIDEO, media = MessageMedia(file, format = MediaFormat.VIDEO))))
        val artwork = MediaDownloadOwner(fixture.state.token, 7, MediaDownloadPurpose.ARTWORK, 301)
        val stream = MediaDownloadOwner(fixture.state.token, 10, MediaDownloadPurpose.STREAM, 302)
        fixture.controller.acquireOwnedMedia(artwork, 7)
        fixture.controller.openOwnedVideoPart(stream, 7)
        fixture.controller.readOwnedVideoPart(stream, 7, 0, 20) { }
        fixture.event(ChatEvent.FileUpdated(file.copy(downloading = true)))
        fixture.event(ChatEvent.FileUpdated(file.copy(downloading = false, downloadedSize = 512)))
        assertNull(fixture.state.fileErrors[7])
        fixture.chat(2)
        fixture.select(2)
        assertEquals(setOf(artwork to 7, stream to 7), fixture.transport.releasedDownloads.toSet())
        fixture.transport.downloads.single().result(ChatResult(error = ChatError.FORBIDDEN))
        fixture.flush()
        assertNull(fixture.state.fileErrors[7])
    }

    @Test
    fun animationStreamingUsesOnlyTheCurrentNativeFileAndRejectsReplacementOrDeletion() {
        for (delete in listOf(false, true)) {
            val fixture = selectedFixture()
            val media = MessageMedia(MediaFile(7, size = 1000, canDownload = true), format = MediaFormat.ANIMATION)
            fixture.history(listOf(message(10).copy(kind = MessageKind.ANIMATION, media = media)))
            var result: ChatResult<ByteArray>? = null
            fixture.controller.readVideoPart(fixture.state.token, 10, 7, 0, 20) { result = it }
            fixture.transport.videoParts.single()(ChatResult(ByteArray(20)))
            fixture.flush()
            assertEquals(20, result?.value?.size)
            fixture.controller.readVideoPart(fixture.state.token, 10, 7, 20, 20) { result = it }
            if (delete) fixture.event(ChatEvent.Deleted(1, listOf(10), true))
            else fixture.event(ChatEvent.Content(1, 10, "", MessageKind.ANIMATION,
                media.copy(file = MediaFile(8, size = 1000, canDownload = true))))
            fixture.transport.videoParts.last()(ChatResult(ByteArray(20)))
            fixture.flush()
            assertEquals(ChatError.FORBIDDEN, result?.error)
        }
    }

    @Test
    fun identicalPendingSendsKeepDistinctUiIdentitiesThroughNativeReplacementsAndLateAcknowledgements() {
        val fixture = selectedFixture()
        fixture.controller.sendText(fixture.state.token, "same synthetic text")
        val first = message(-1, text = "same synthetic text", delivery = MessageDelivery.PENDING)
        fixture.event(ChatEvent.NewMessage(first))
        val firstUi = fixture.state.messages.single { it.id == -1L }
        fixture.transport.sends[0].result(ChatResult(first)); fixture.flush()
        fixture.controller.sendText(fixture.state.token, "same synthetic text")
        val second = message(-2, text = "same synthetic text", delivery = MessageDelivery.PENDING)
        fixture.event(ChatEvent.NewMessage(second))
        val secondUi = fixture.state.messages.single { it.id == -2L }
        assertNotNull(firstUi.sendAnimationId)
        assertNotNull(secondUi.sendAnimationId)
        assertNotEquals(firstUi.sendAnimationId, secondUi.sendAnimationId)
        fixture.event(ChatEvent.SendSucceeded(second.copy(id = 100, delivery = MessageDelivery.SENT), -2))
        fixture.event(ChatEvent.SendSucceeded(first.copy(id = 200, delivery = MessageDelivery.SENT), -1))
        fixture.transport.sends[1].result(ChatResult(second)); fixture.flush()
        assertEquals(listOf(100L, 200L), fixture.state.messages.map { it.id })
        assertEquals(listOf(-2L, -1L), fixture.state.messages.map { it.presentationId })
        assertEquals(secondUi.sendAnimationId, fixture.state.messages.first().sendAnimationId)
        assertEquals(firstUi.sendAnimationId, fixture.state.messages.last().sendAnimationId)
        assertFalse(fixture.state.messages.toString().contains("synthetic text"))
    }

    @Test
    fun retryUsesARealNewSendIdentityWithoutChangingDeliveryBeforeNativeConfirmation() {
        val fixture = selectedFixture()
        fixture.history(listOf(message(50, delivery = MessageDelivery.FAILED, canRetry = true)))
        fixture.controller.retry(fixture.state.token, 50)
        assertEquals(MessageDelivery.FAILED, fixture.state.messages.single().delivery)
        fixture.transport.retries.single().result(ChatResult(listOf(message(-3, delivery = MessageDelivery.PENDING))))
        fixture.flush()
        val pending = fixture.state.messages.single()
        assertEquals(-3L, pending.id)
        assertEquals(50L, pending.presentationId)
        assertEquals(MessageDelivery.PENDING, pending.delivery)
        assertNotNull(pending.sendAnimationId)
        fixture.event(ChatEvent.SendSucceeded(message(90).copy(outgoing = true), -3))
        val confirmed = fixture.state.messages.single()
        assertEquals(90L, confirmed.id)
        assertEquals(50L, confirmed.presentationId)
        assertEquals(pending.sendAnimationId, confirmed.sendAnimationId)
        assertEquals(MessageDelivery.SENT, confirmed.delivery)
    }

    private fun attachment(kind: AttachmentKind) = LocalAttachment(kind, "C:/synthetic/attachment.bin", "attachment.bin", 100,
        durationSeconds = if (kind == AttachmentKind.VIDEO) 1 else 0,
        width = if (kind == AttachmentKind.VIDEO) 64 else 0, height = if (kind == AttachmentKind.VIDEO) 48 else 0)

    @Test fun markdownCodeIsSentAsRichTextWhileTheReceiptIdentifiesTheOriginalDraft() {
        val fixture = selectedFixture()
        val draft = "```python\nprint('🙂')\n```"
        fixture.controller.sendText(fixture.state.token, draft)
        val send = fixture.transport.richSends.single()
        assertEquals("print('🙂')", send.text.text)
        assertEquals("python", send.text.entities.single().value)
        assertEquals(MessageEntityKind.PRE, send.text.entities.single().kind)
        send.result(ChatResult(message(20, text = send.text.text).copy(entities = send.text.entities)))
        fixture.flush()
        assertEquals(draft, fixture.state.acceptedSendText)
        assertTrue(fixture.state.acceptedSendEntities.isEmpty())
    }

    private fun sticker(fileId: Int) = StickerItem(fileId.toLong(), 7, "🙂",
        MessageMedia(MediaFile(fileId, canDownload = true), format = MediaFormat.WEBP, emoji = "🙂", setId = 7))

    @Test fun replyMarkupUpdatesBeforeHistoryReplaceAndRemoveNativeRowsWithoutStaleRestoration() {
        val keyboard = MessageInlineKeyboard(listOf(listOf(MessageInlineButton("native", MessageInlineButtonKind.URL, "https://example.org"))))
        for (updated in listOf(keyboard, null)) {
            val fixture = selectedFixture()
            fixture.event(ChatEvent.ReplyMarkup(1, 10, updated))
            fixture.history(listOf(message(10).copy(inlineKeyboard = if (updated == null) keyboard else null)))
            assertEquals(updated, fixture.state.messages.single().inlineKeyboard)
            fixture.event(ChatEvent.NewMessage(message(10).copy(inlineKeyboard = keyboard)))
            assertEquals(updated, fixture.state.messages.single().inlineKeyboard)
            assertEquals(updated, fixture.state.selected?.lastMessage?.inlineKeyboard)
            fixture.event(ChatEvent.Content(1, 10, "edited text", MessageKind.TEXT))
            assertEquals(updated, fixture.state.messages.single().inlineKeyboard)
        }
    }

    @Test fun inlineKeyboardRemovalSurvivesLateSendAcknowledgementAndOtherChatUpdates() {
        val keyboard = MessageInlineKeyboard(listOf(listOf(MessageInlineButton("native", MessageInlineButtonKind.URL, "https://example.org"))))
        val fixture = selectedFixture()
        fixture.history(listOf(message(10).copy(inlineKeyboard = keyboard)))
        fixture.event(ChatEvent.ReplyMarkup(2, 10, null))
        assertEquals(keyboard, fixture.state.messages.single().inlineKeyboard)
        fixture.event(ChatEvent.ReplyMarkup(1, 10, null))
        fixture.event(ChatEvent.SendSucceeded(message(10).copy(inlineKeyboard = keyboard), 10))
        assertNull(fixture.state.messages.single().inlineKeyboard)
        assertNull(fixture.state.selected?.lastMessage?.inlineKeyboard)
        fixture.event(ChatEvent.Deleted(1, listOf(10), true))
        fixture.event(ChatEvent.ReplyMarkup(1, 10, keyboard))
        fixture.event(ChatEvent.NewMessage(message(10).copy(inlineKeyboard = keyboard)))
        assertTrue(fixture.state.messages.isEmpty())
    }

    private fun selectedFixture() = Fixture().also {
        it.chat(1)
        it.select(1)
    }

    private fun message(
        id: Long,
        chatId: Long = 1,
        text: String = "synthetic message",
        delivery: MessageDelivery = MessageDelivery.SENT,
        canRetry: Boolean = false,
        retryAfter: Int = 0,
    ) = ChatMessage(id, chatId, outgoing = delivery != MessageDelivery.SENT, text = text,
        delivery = delivery, canRetry = canRetry, retryAfterSeconds = retryAfter)

    private class Fixture {
        val transport = FakeChatSession()
        var state = ChatSnapshot()
        var now = 10_000L
        private val queued = ArrayDeque<() -> Unit>()
        val controller = ChatController(3, transport, queued::addLast, { state = it }, { now })

        init { controller.start() }

        fun chat(id: Long, order: Long = 100) = event(ChatEvent.NewChat(ChatItem(id, "synthetic chat", order)))
        fun select(id: Long) = controller.select(state.token, id)
        fun event(event: ChatEvent) = controller.event(event)
        fun history(messages: List<ChatMessage>) {
            transport.histories.last().result(ChatResult(messages))
            flush()
        }
        fun actions(id: Long, actions: MessageActions) {
            controller.loadMessageActions(state.token, id)
            transport.actions.last().result(ChatResult(actions))
            flush()
        }
        fun flush() { while (queued.isNotEmpty()) queued.removeFirst().invoke() }
    }

    private class FakeChatSession : TdChatSession {
        data class RichSend(val text: RichMessageText, val reply: OutgoingReply?, val result: (ChatResult<ChatMessage>) -> Unit)
        val richSends = mutableListOf<RichSend>()
        val videoParts = mutableListOf<(ChatResult<ByteArray>) -> Unit>()
        override fun sendRichText(chatId: Long, text: RichMessageText, reply: OutgoingReply?, result: (ChatResult<ChatMessage>) -> Unit) {
            if (text.entities.isEmpty() && reply?.quote == null) sendText(chatId, text.text, reply?.messageId, result)
            else richSends.add(RichSend(text, reply, result))
        }
        override fun readVideoPart(fileId: Int, offset: Long, count: Int, result: (ChatResult<ByteArray>) -> Unit) { videoParts.add(result) }
        data class History(val chatId: Long, val from: Long, val limit: Int, val result: (ChatResult<List<ChatMessage>>) -> Unit)
        data class Send(val chatId: Long, val text: String, val result: (ChatResult<ChatMessage>) -> Unit, val replyId: Long? = null)
        data class Retry(val chatId: Long, val id: Long, val result: (ChatResult<List<ChatMessage>>) -> Unit)
        data class Read(val chatId: Long, val ids: List<Long>, val forceRead: Boolean, val result: (ChatResult<Unit>) -> Unit)
        data class Unread(val chatId: Long, val marked: Boolean, val result: (ChatResult<Unit>) -> Unit)
        data class Download(val id: Int, val result: (ChatResult<MediaFile>) -> Unit)
        data class Photo(val chatId: Long, val path: String, val caption: String, val result: (ChatResult<ChatMessage>) -> Unit, val replyId: Long? = null)
        data class StickerSend(val chatId: Long, val sticker: StickerItem, val result: (ChatResult<ChatMessage>) -> Unit, val replyId: Long? = null)
        data class Attachment(val chatId: Long, val attachment: LocalAttachment, val caption: String, val replyId: Long?, val result: (ChatResult<ChatMessage>) -> Unit)
        data class ReadDetails(val chatId: Long, val id: Long, val result: (ChatResult<MessageReadDetails>) -> Unit)
        data class ReplyMessage(val chatId: Long, val id: Long, val result: (ChatResult<ChatMessage>) -> Unit)
        data class Actions(val chatId: Long, val id: Long, val result: (ChatResult<MessageActions>) -> Unit)
        data class Edit(val chatId: Long, val id: Long, val text: String, val caption: Boolean, val result: (ChatResult<ChatMessage>) -> Unit)
        data class Delete(val chatId: Long, val id: Long, val revoke: Boolean, val result: (ChatResult<Unit>) -> Unit)
        data class Forward(val chatId: Long, val id: Long, val targetId: Long, val result: (ChatResult<List<ChatMessage>>) -> Unit)
        data class StickerSet(val id: Long, val result: (ChatResult<List<StickerItem>>) -> Unit)
        data class FolderLoad(val id: Int, val result: (ChatResult<ChatFolder>) -> Unit, val current: () -> Boolean = { true })
        data class FolderSave(val folder: ChatFolder, val result: (ChatResult<ChatFolderInfo>) -> Unit, val current: () -> Boolean = { true })
        data class FolderDelete(val id: Int, val result: (ChatResult<Unit>) -> Unit)
        val loads = mutableListOf<(ChatResult<ChatLoadResult>) -> Unit>()
        val histories = mutableListOf<History>()
        val sends = mutableListOf<Send>()
        val retries = mutableListOf<Retry>()
        val reads = mutableListOf<Read>()
        val unread = mutableListOf<Unread>()
        val downloads = mutableListOf<Download>()
        val photos = mutableListOf<Photo>()
        val stickerSends = mutableListOf<StickerSend>()
        val attachments = mutableListOf<Attachment>()
        val readDetails = mutableListOf<ReadDetails>()
        val replyMessages = mutableListOf<ReplyMessage>()
        val actions = mutableListOf<Actions>()
        val edits = mutableListOf<Edit>()
        val deletes = mutableListOf<Delete>()
        val forwards = mutableListOf<Forward>()
        val packs = mutableListOf<(ChatResult<List<StickerPack>>) -> Unit>()
        val recents = mutableListOf<(ChatResult<List<StickerItem>>) -> Unit>()
        val sets = mutableListOf<StickerSet>()
        val folderLoads = mutableListOf<FolderLoad>()
        val folderSaves = mutableListOf<FolderSave>()
        val folderDeletes = mutableListOf<FolderDelete>()
        val loadedLists = mutableListOf<ChatListRef>()
        val opened = mutableListOf<Long>()
        val closed = mutableListOf<Long>()
        override fun loadChats(limit: Int, result: (ChatResult<ChatLoadResult>) -> Unit) = loadChats(ChatListRef(), limit, result)
        override fun loadChats(list: ChatListRef, limit: Int, result: (ChatResult<ChatLoadResult>) -> Unit) {
            loadedLists += list
            loads += result
        }
        override fun history(chatId: Long, fromMessageId: Long, limit: Int, result: (ChatResult<List<ChatMessage>>) -> Unit) {
            histories += History(chatId, fromMessageId, limit, result)
        }
        override fun open(chatId: Long) { opened += chatId }
        override fun close(chatId: Long) { closed += chatId }
        override fun sendText(chatId: Long, text: String, result: (ChatResult<ChatMessage>) -> Unit) {
            sends += Send(chatId, text, result)
        }
        override fun sendText(chatId: Long, text: String, replyToMessageId: Long?, result: (ChatResult<ChatMessage>) -> Unit) {
            sends += Send(chatId, text, result, replyToMessageId)
        }
        override fun retry(chatId: Long, messageId: Long, result: (ChatResult<List<ChatMessage>>) -> Unit) {
            retries += Retry(chatId, messageId, result)
        }
        override fun markRead(chatId: Long, messageIds: List<Long>, result: (ChatResult<Unit>) -> Unit) {
            viewMessages(chatId, messageIds, true, result)
        }
        override fun viewMessages(chatId: Long, messageIds: List<Long>, forceRead: Boolean, result: (ChatResult<Unit>) -> Unit) {
            reads += Read(chatId, messageIds, forceRead, result)
        }
        override fun markUnread(chatId: Long, marked: Boolean, result: (ChatResult<Unit>) -> Unit) { unread += Unread(chatId, marked, result) }
        val releasedDownloads = mutableListOf<Pair<MediaDownloadOwner, Int>>()
        override fun releaseDownload(owner: MediaDownloadOwner, fileId: Int) { releasedDownloads += owner to fileId }
        override fun download(fileId: Int, result: (ChatResult<MediaFile>) -> Unit) { downloads += Download(fileId, result) }
        override fun sendPhoto(chatId: Long, path: String, caption: String, result: (ChatResult<ChatMessage>) -> Unit) { photos += Photo(chatId, path, caption, result) }
        override fun sendSticker(chatId: Long, sticker: StickerItem, result: (ChatResult<ChatMessage>) -> Unit) { stickerSends += StickerSend(chatId, sticker, result) }
        override fun sendPhoto(chatId: Long, path: String, caption: String, replyToMessageId: Long?, result: (ChatResult<ChatMessage>) -> Unit) {
            photos += Photo(chatId, path, caption, result, replyToMessageId)
        }
        override fun sendSticker(chatId: Long, sticker: StickerItem, replyToMessageId: Long?, result: (ChatResult<ChatMessage>) -> Unit) {
            stickerSends += StickerSend(chatId, sticker, result, replyToMessageId)
        }
        override fun sendAttachment(chatId: Long, attachment: LocalAttachment, caption: String, replyToMessageId: Long?, result: (ChatResult<ChatMessage>) -> Unit) {
            attachments += Attachment(chatId, attachment, caption, replyToMessageId, result)
        }
        override fun messageReadDetails(chatId: Long, messageId: Long, result: (ChatResult<MessageReadDetails>) -> Unit) {
            readDetails += ReadDetails(chatId, messageId, result)
        }
        override fun getReplyMessage(chatId: Long, messageId: Long, result: (ChatResult<ChatMessage>) -> Unit) {
            replyMessages += ReplyMessage(chatId, messageId, result)
        }
        override fun messageActions(chatId: Long, messageId: Long, result: (ChatResult<MessageActions>) -> Unit) {
            actions += Actions(chatId, messageId, result)
        }
        override fun editMessage(chatId: Long, messageId: Long, text: String, caption: Boolean, result: (ChatResult<ChatMessage>) -> Unit) {
            edits += Edit(chatId, messageId, text, caption, result)
        }
        override fun deleteMessage(chatId: Long, messageId: Long, revoke: Boolean, result: (ChatResult<Unit>) -> Unit) {
            deletes += Delete(chatId, messageId, revoke, result)
        }
        override fun forwardMessage(fromChatId: Long, messageId: Long, toChatId: Long, result: (ChatResult<List<ChatMessage>>) -> Unit) {
            forwards += Forward(fromChatId, messageId, toChatId, result)
        }
        override fun recentStickers(result: (ChatResult<List<StickerItem>>) -> Unit) { recents += result }
        override fun installedStickerSets(result: (ChatResult<List<StickerPack>>) -> Unit) { packs += result }
        override fun stickerSet(setId: Long, result: (ChatResult<List<StickerItem>>) -> Unit) { sets += StickerSet(setId, result) }
        override fun getFolder(id: Int, result: (ChatResult<ChatFolder>) -> Unit) { folderLoads += FolderLoad(id, result) }
        override fun getFolder(id: Int, current: () -> Boolean, result: (ChatResult<ChatFolder>) -> Unit) { folderLoads += FolderLoad(id, result, current) }
        override fun saveFolder(folder: ChatFolder, result: (ChatResult<ChatFolderInfo>) -> Unit) { folderSaves += FolderSave(folder, result) }
        override fun saveFolder(folder: ChatFolder, current: () -> Boolean, result: (ChatResult<ChatFolderInfo>) -> Unit) { folderSaves += FolderSave(folder, result, current) }
        override fun deleteFolder(id: Int, result: (ChatResult<Unit>) -> Unit) { folderDeletes += FolderDelete(id, result) }
    }
}
