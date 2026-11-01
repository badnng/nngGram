package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import kotlin.test.*

class UnreadHistoryControllerTest {
    @Test
    fun openingUnreadHistoryFreezesTheRealBoundaryAndLocatesTheFirstIncomingOutsideTheLatestPage() {
        val fixture = Fixture()
        fixture.chat(10, unread = 300, lastRead = 100, newest = 1_000)
        fixture.select(10)
        assertTrue(fixture.session.histories.isEmpty())
        assertEquals(100L, fixture.session.unread.single().from)
        assertEquals(ChatUnreadBoundary(100), fixture.state.unreadBoundary)
        fixture.complete(fixture.session.unread.last(), listOf(message(100), message(101, outgoing = true), message(103), message(104)))
        assertEquals(ChatUnreadBoundary(100, 103), fixture.state.unreadBoundary)
        assertEquals(103L, fixture.state.jumpMessageId)
        assertEquals(1L, fixture.state.jumpRevision)
        assertTrue(fixture.state.historyHasNewer)
        assertEquals(listOf(100L, 101L, 103L, 104L), fixture.state.messages.map { it.id })
        fixture.event(ChatEvent.Inbox(10, 0, 1_000))
        assertEquals(ChatUnreadBoundary(100, 103), fixture.state.unreadBoundary)
        assertEquals(1L, fixture.state.jumpRevision)
        fixture.event(ChatEvent.Inbox(10, 20, 900))
        assertEquals(ChatUnreadBoundary(100, 103), fixture.state.unreadBoundary)
    }

    @Test
    fun unknownOrReadCountsAndManualUnreadFlagsNeverInventAnUnreadBoundary() {
        val fixture = Fixture()
        fixture.chat(10, unread = 2, lastRead = 0, newest = 100)
        fixture.select(10)
        assertNull(fixture.state.unreadBoundary)
        assertEquals(0L, fixture.session.histories.last().from)
        fixture.chat(11, unread = 0, lastRead = 50, newest = 100, marked = true)
        fixture.select(11)
        assertNull(fixture.state.unreadBoundary)
        assertTrue(fixture.session.unread.isEmpty())
        fixture.event(ChatEvent.MarkedUnread(11, true))
        fixture.event(ChatEvent.Inbox(11, 5, 60))
        assertNull(fixture.state.unreadBoundary)
    }

    @Test
    fun aDeletedLastReadAnchorAndDeletedFirstUnreadUseOnlyRealSurvivingMessages() {
        val fixture = unreadFixture(newest = 200)
        fixture.event(ChatEvent.Deleted(10, listOf(100, 101), permanent = true))
        fixture.complete(fixture.session.unread.last(), listOf(message(99), message(100), message(101), message(102), message(103)))
        assertEquals(ChatUnreadBoundary(100, 102), fixture.state.unreadBoundary)
        assertEquals(listOf(99L, 102L, 103L), fixture.state.messages.map { it.id })
        fixture.event(ChatEvent.Deleted(10, listOf(102), permanent = true))
        assertEquals(ChatUnreadBoundary(100, 103), fixture.state.unreadBoundary)
        assertNull(fixture.state.jumpMessageId)
    }

    @Test
    fun unreadHistoryScansForwardPastOutgoingOnlyBatchesBeforeChoosingAnIncomingAnchor() {
        val fixture = unreadFixture(newest = 200)
        fixture.complete(fixture.session.unread.last(), listOf(message(100), message(101, outgoing = true)))
        assertTrue(fixture.state.historyLoading)
        assertEquals(101L, fixture.session.newer.last().from)
        fixture.complete(fixture.session.newer.last(), listOf(message(101, outgoing = true), message(102, outgoing = true), message(105)))
        assertEquals(105L, fixture.state.unreadBoundary?.firstUnreadMessageId)
        assertEquals(105L, fixture.state.jumpMessageId)
        assertFalse(fixture.state.historyLoading)
    }

    @Test
    fun anUnavailableUnreadWindowFallsBackToNativeLatestHistoryWithoutInventingAnAnchor() {
        val fixture = unreadFixture(newest = 1_000)
        fixture.session.unread.last().done(ChatResult(error = ChatError.FORBIDDEN))
        fixture.flush()
        assertEquals(0L, fixture.session.histories.single().from)
        assertEquals(ChatUnreadBoundary(100), fixture.state.unreadBoundary)
        assertNull(fixture.state.jumpMessageId)
        fixture.complete(fixture.session.histories.last(), listOf(message(999), message(1_000)))
        assertNull(fixture.state.unreadBoundary?.firstUnreadMessageId)
        assertFalse(fixture.state.historyHasNewer)
    }

    @Test
    fun selectionAndRetiredGenerationRejectLateUnreadResponses() {
        val fixture = unreadFixture(newest = 200)
        val old = fixture.session.unread.last()
        fixture.chat(11, unread = 0, lastRead = 0, newest = 300)
        fixture.select(11)
        fixture.complete(old, listOf(message(100), message(101)))
        assertEquals(11L, fixture.state.token.chatId)
        assertNull(fixture.state.unreadBoundary)
        assertTrue(fixture.state.messages.isEmpty())
        fixture.select(10)
        val pending = fixture.session.unread.last()
        val publishes = fixture.publishes
        fixture.controller.deactivate()
        fixture.complete(pending, listOf(message(100), message(101)))
        assertEquals(publishes, fixture.publishes)
    }

    @Test
    fun newIncomingMessagesBeyondTheGapUpdateTheNativePreviewWithoutEnteringVisibleHistoryOrReadReceipts() {
        val fixture = unreadFixture(newest = 110)
        fixture.event(ChatEvent.NewMessage(message(111)))
        assertTrue(fixture.state.messages.isEmpty())
        fixture.complete(fixture.session.unread.last(), listOf(message(100), message(101), message(102)))
        fixture.event(ChatEvent.NewMessage(message(112)))
        fixture.event(ChatEvent.NewMessage(message(-1, outgoing = true).copy(delivery = MessageDelivery.PENDING)))
        fixture.event(ChatEvent.NewMessage(message(101).copy(text = "current native text")))
        fixture.event(ChatEvent.LastMessage(10, message(113), 100))
        assertEquals(listOf(100L, 101L, 102L), fixture.state.messages.map { it.id })
        assertEquals("current native text", fixture.state.messages.single { it.id == 101L }.text,
            "The gap isolates missing rows but still accepts native changes to rows already in the window")
        assertEquals(113L, fixture.state.selected?.lastMessage?.id)
        fixture.controller.visibleMessages(fixture.state.token, listOf(111, 112, 113), active = true)
        assertTrue(fixture.session.reads.isEmpty())
        fixture.controller.visibleMessages(fixture.state.token, listOf(101), active = true)
        assertEquals(listOf(101L), fixture.session.reads.single().ids)
        assertFalse(fixture.session.reads.single().force)
    }

    @Test
    fun newerHistorySeriallyFillsShortNativeBatchesBeforeClosingTheGap() {
        val fixture = unreadFixture(newest = 106)
        fixture.complete(fixture.session.unread.last(), listOf(message(100), message(101), message(102)))
        fixture.controller.loadNewer(fixture.state.token)
        val first = fixture.session.newer.single()
        fixture.controller.loadNewer(fixture.state.token)
        assertEquals(1, fixture.session.newer.size)
        assertTrue(fixture.state.newerLoading)
        fixture.complete(first, listOf(message(102), message(103)))
        assertEquals(103L, fixture.session.newer.last().from)
        assertTrue(fixture.state.newerLoading)
        fixture.complete(first, listOf(message(999)))
        assertTrue(fixture.state.messages.none { it.id == 999L })
        fixture.complete(fixture.session.newer.last(), listOf(message(103), message(104), message(105), message(106)))
        assertFalse(fixture.state.historyHasNewer)
        assertFalse(fixture.state.newerLoading)
        assertNull(fixture.state.newerError)
        assertEquals((100L..106L).toList(), fixture.state.messages.map { it.id })
        fixture.event(ChatEvent.NewMessage(message(107)))
        assertEquals(107L, fixture.state.messages.last().id)
        fixture.controller.visibleMessages(fixture.state.token, listOf(107), active = true)
        assertEquals(listOf(107L), fixture.session.reads.last().ids)
    }

    @Test
    fun aCompletedNewerWindowStopsAtTheNativeBatchSizeAndRetainsTheRemainingGap() {
        val fixture = unreadFixture(newest = 1_000)
        fixture.complete(fixture.session.unread.last(), listOf(message(100), message(101)))
        fixture.controller.loadNewer(fixture.state.token)
        fixture.complete(fixture.session.newer.last(), (101L..150L).map { message(it) })
        assertFalse(fixture.state.newerLoading)
        assertTrue(fixture.state.historyHasNewer)
        assertEquals(1, fixture.session.newer.size)
        fixture.controller.loadNewer(fixture.state.token)
        assertEquals(150L, fixture.session.newer.last().from)
    }

    @Test
    fun aNativeNoProgressPageCannotPretendThatAKnownNewerRangeWasLoaded() {
        val fixture = unreadFixture(newest = 200)
        fixture.complete(fixture.session.unread.last(), listOf(message(100), message(101)))
        fixture.controller.loadNewer(fixture.state.token)
        fixture.complete(fixture.session.newer.last(), listOf(message(101)))
        assertTrue(fixture.state.historyHasNewer)
        assertEquals(ChatError.REQUEST_FAILED, fixture.state.newerError)
        assertFalse(fixture.state.newerLoading)
        fixture.controller.loadNewer(fixture.state.token)
        fixture.session.newer.last().done(ChatResult(error = ChatError.FORBIDDEN))
        fixture.flush()
        assertTrue(fixture.state.historyHasNewer)
        assertEquals(ChatError.FORBIDDEN, fixture.state.newerError)
    }

    @Test
    fun newerHistoryReconcilesLateContentAndDeletionUpdatesWithoutCrossChatLeakage() {
        val fixture = unreadFixture(newest = 104)
        fixture.complete(fixture.session.unread.last(), listOf(message(100), message(101)))
        fixture.controller.loadNewer(fixture.state.token)
        fixture.event(ChatEvent.Content(10, 102, "new text", MessageKind.TEXT))
        fixture.event(ChatEvent.Edited(10, 102, 9))
        fixture.event(ChatEvent.Deleted(10, listOf(103), permanent = false))
        fixture.complete(fixture.session.newer.last(), listOf(message(101), message(102), message(103), message(104), message(105, chatId = 11)))
        assertEquals(listOf(100L, 101L, 102L, 104L), fixture.state.messages.map { it.id })
        assertEquals("new text", fixture.state.messages.single { it.id == 102L }.text)
        assertEquals(9, fixture.state.messages.single { it.id == 102L }.editDate)
        assertFalse(fixture.state.historyHasNewer)
    }

    @Test
    fun returnToLatestUsesNativeLatestHistoryWithoutRestartingUnreadNavigation() {
        val fixture = unreadFixture(newest = 1_000)
        fixture.complete(fixture.session.unread.last(), listOf(message(100), message(101)))
        fixture.controller.loadNewer(fixture.state.token)
        val pending = fixture.session.newer.last()
        val boundary = fixture.state.unreadBoundary
        fixture.controller.returnToLatest(fixture.state.token)
        fixture.flush()
        assertEquals(1, fixture.session.unread.size)
        assertEquals(0L, fixture.session.histories.last().from)
        assertEquals(boundary, fixture.state.unreadBoundary)
        fixture.complete(pending, listOf(message(102), message(103)))
        assertTrue(fixture.state.messages.isEmpty())
        assertFalse(fixture.state.historyHasNewer)
        assertFalse(fixture.state.newerLoading)
    }

    @Test
    fun anOutgoingAcceptanceFromAnOlderWindowLoadsLatestAndClearsOnlyItsOwnComposerAcceptance() {
        val fixture = unreadFixture(newest = 1_000)
        fixture.complete(fixture.session.unread.last(), listOf(message(100), message(101)))
        val boundary = fixture.state.unreadBoundary
        val token = fixture.state.token
        fixture.states.clear()
        fixture.controller.sendText(fixture.state.token, "synthetic draft")
        fixture.session.sends.last()(ChatResult(message(1_001, outgoing = true)))
        fixture.flush()
        assertEquals(token, fixture.state.token)
        assertTrue(fixture.state.historyHasNewer)
        assertTrue(fixture.state.newerLoading)
        assertEquals(0L, fixture.session.histories.last().from)
        assertEquals(boundary, fixture.state.unreadBoundary)
        assertEquals("synthetic draft", fixture.state.acceptedSendText)
        assertEquals(listOf(100L, 101L), fixture.state.messages.map { it.id })
        fixture.complete(fixture.session.histories.last(), listOf(message(999), message(1_000), message(1_001, outgoing = true)))
        assertEquals(listOf(100L, 101L), fixture.state.messages.map { it.id }, "A partial latest batch stays private")
        assertTrue(fixture.states.all { it.messages.map { message -> message.id } == listOf(100L, 101L) })
        fixture.complete(fixture.session.histories.last(), emptyList())
        assertEquals(token, fixture.state.token)
        assertEquals(listOf(999L, 1_000L, 1_001L), fixture.state.messages.map { it.id })
        assertEquals(1_001L, fixture.state.jumpMessageId)
        assertFalse(fixture.state.historyHasNewer)
        assertFalse(fixture.state.newerLoading)
        assertEquals(boundary, fixture.state.unreadBoundary)
        assertTrue(fixture.states.all { it.messages.map { message -> message.id } in
            listOf(listOf(100L, 101L), listOf(999L, 1_000L, 1_001L)) }, "Every publication owns one contiguous window")
    }

    @Test
    fun aFailedSendLatestWindowRetainsTheOldRowsAndRetriesFromTheNativeHead() {
        val fixture = unreadFixture(newest = 1_000)
        fixture.complete(fixture.session.unread.last(), listOf(message(100), message(101)))
        val token = fixture.state.token
        fixture.controller.sendText(token, "synthetic draft")
        fixture.session.sends.last()(ChatResult(message(1_001, outgoing = true)))
        fixture.flush()
        fixture.complete(fixture.session.histories.last(), listOf(message(1_000), message(1_001, outgoing = true)))
        fixture.session.histories.last().done(ChatResult(error = ChatError.FORBIDDEN))
        fixture.flush()
        assertEquals(listOf(100L, 101L), fixture.state.messages.map { it.id })
        assertEquals(ChatError.FORBIDDEN, fixture.state.historyError)
        assertFalse(fixture.state.newerLoading)
        assertTrue(fixture.state.historyHasNewer)
        fixture.controller.loadOlder(token)
        assertEquals(0L, fixture.session.histories.last().from)
        fixture.complete(fixture.session.histories.last(), listOf(message(1_000), message(1_001, outgoing = true)))
        fixture.complete(fixture.session.histories.last(), emptyList())
        assertEquals(listOf(1_000L, 1_001L), fixture.state.messages.map { it.id })
        assertNull(fixture.state.historyError)
        assertEquals(1L, fixture.state.acceptedSendId, "Retrying the window must not repeat the send or consume another draft")
        assertEquals(1, fixture.session.sends.size)
        assertEquals(token, fixture.state.token)
    }

    @Test
    fun aNativeLatestCacheMissingTheAcceptedIdentityCannotReplaceTheWindowWithStaleHistory() {
        val fixture = unreadFixture(newest = 1_000)
        fixture.complete(fixture.session.unread.last(), listOf(message(100), message(101)))
        val token = fixture.state.token
        fixture.controller.sendText(token, "synthetic draft")
        fixture.session.sends.last()(ChatResult(message(1_001, outgoing = true)))
        fixture.flush()
        fixture.complete(fixture.session.histories.last(), listOf(message(999), message(1_000)))
        fixture.complete(fixture.session.histories.last(), emptyList())
        assertEquals(listOf(100L, 101L), fixture.state.messages.map { it.id })
        assertEquals(ChatError.REQUEST_FAILED, fixture.state.historyError)
        fixture.controller.loadOlder(token)
        fixture.complete(fixture.session.histories.last(), listOf(message(1_000), message(1_001, outgoing = true)))
        fixture.complete(fixture.session.histories.last(), emptyList())
        assertEquals(listOf(1_000L, 1_001L), fixture.state.messages.map { it.id })
        fixture.controller.cancelSendLatestNavigation(token)
        assertNull(fixture.state.jumpMessageId, "User input also cancels an unplaced send-owned latest jump")
        assertEquals(listOf(1_000L, 1_001L), fixture.state.messages.map { it.id })
    }

    @Test
    fun realScrollCancellationBeforeAndAfterAcceptanceCannotBeUndoneByLateResults() {
        val fixture = unreadFixture(newest = 1_000)
        fixture.complete(fixture.session.unread.last(), listOf(message(100), message(101)))
        val token = fixture.state.token
        val initialJump = fixture.state.jumpRevision
        fixture.controller.sendText(token, "first draft")
        fixture.controller.cancelSendLatestNavigation(token)
        fixture.session.sends.last()(ChatResult(message(1_001, outgoing = true)))
        fixture.flush()
        assertTrue(fixture.session.histories.isEmpty(), "A late acceptance cannot reacquire navigation after user input")
        assertEquals("first draft", fixture.state.acceptedSendText)
        fixture.controller.sendText(token, "second draft")
        fixture.session.sends.last()(ChatResult(message(1_002, outgoing = true)))
        fixture.flush()
        val pending = fixture.session.histories.last()
        fixture.controller.visibleMessages(token, emptyList(), active = false)
        assertTrue(fixture.state.newerLoading, "Visibility reports are not user-scroll cancellation signals")
        fixture.controller.cancelSendLatestNavigation(token)
        fixture.controller.cancelSendLatestNavigation(token)
        val publishes = fixture.publishes
        fixture.complete(pending, (953L..1_002L).map { message(it) })
        assertEquals(publishes, fixture.publishes)
        assertEquals(listOf(100L, 101L), fixture.state.messages.map { it.id })
        assertFalse(fixture.state.newerLoading)
        assertEquals(initialJump, fixture.state.jumpRevision)
        assertEquals(2L, fixture.state.acceptedSendId)
        assertEquals(token, fixture.state.token)
    }

    @Test
    fun sendLatestIsolatesOldPagingChatChangesAndOtherNavigation() {
        val fixture = unreadFixture(newest = 1_000)
        fixture.complete(fixture.session.unread.last(), listOf(message(100), message(101)))
        fixture.controller.loadOlder(fixture.state.token)
        val older = fixture.session.histories.last()
        fixture.controller.sendText(fixture.state.token, "synthetic draft")
        fixture.session.sends.last()(ChatResult(message(1_001, outgoing = true)))
        fixture.flush()
        val latest = fixture.session.histories.last()
        fixture.complete(older, listOf(message(99)))
        assertEquals(listOf(100L, 101L), fixture.state.messages.map { it.id })
        fixture.chat(11, unread = 0, lastRead = 0, newest = 300)
        fixture.select(11)
        val token = fixture.state.token
        val publishes = fixture.publishes
        fixture.complete(latest, (952L..1_001L).map { message(it) })
        assertEquals(publishes, fixture.publishes)
        assertEquals(token, fixture.state.token)
        assertTrue(fixture.state.messages.isEmpty())

        fixture.select(10)
        fixture.complete(fixture.session.unread.last(), listOf(message(100), message(101)))
        fixture.controller.sendText(fixture.state.token, "another draft")
        fixture.session.sends.last()(ChatResult(message(1_002, outgoing = true)))
        fixture.flush()
        val abandoned = fixture.session.histories.last()
        fixture.controller.openHistoryMessage(fixture.state.token, 100)
        val revision = fixture.state.jumpRevision
        fixture.complete(abandoned, (953L..1_002L).map { message(it) })
        assertEquals(100L, fixture.state.jumpMessageId)
        assertEquals(revision, fixture.state.jumpRevision)
        assertEquals(listOf(100L, 101L), fixture.state.messages.map { it.id })
    }

    @Test
    fun deliveryContentAndDeletionRacesReconcileInsideTheAtomicLatestWindow() {
        listOf(false, true).forEach { deliveryBeforeHistory ->
            val fixture = unreadFixture(newest = 1_000)
            fixture.complete(fixture.session.unread.last(), listOf(message(100), message(101)))
            fixture.controller.sendText(fixture.state.token, "synthetic draft")
            val pending = message(1_001, outgoing = true).copy(delivery = MessageDelivery.PENDING)
            fixture.session.sends.last()(ChatResult(pending))
            fixture.flush()
            fun deliver() = fixture.event(ChatEvent.SendSucceeded(
                pending.copy(id = 1_200, delivery = MessageDelivery.SENT), pending.id))
            if (deliveryBeforeHistory) deliver()
            fixture.complete(fixture.session.histories.last(), listOf(message(999), message(1_000), pending))
            if (!deliveryBeforeHistory) deliver()
            fixture.event(ChatEvent.Content(10, 1_200, "current native content", MessageKind.TEXT))
            fixture.event(ChatEvent.Deleted(10, listOf(999), permanent = false))
            assertEquals(listOf(100L, 101L), fixture.state.messages.map { it.id })
            fixture.complete(fixture.session.histories.last(), emptyList())
            assertEquals(listOf(1_000L, 1_200L), fixture.state.messages.map { it.id },
                "A native history alias stays valid when delivery arrived first: $deliveryBeforeHistory")
            val delivered = fixture.state.messages.last()
            assertEquals(pending.id, delivered.presentationId)
            assertNotNull(delivered.sendAnimationId)
            assertEquals("current native content", delivered.text)
            assertEquals(MessageDelivery.SENT, delivered.delivery)
            fixture.event(ChatEvent.NewMessage(pending))
            assertEquals(listOf(1_000L, 1_200L), fixture.state.messages.map { it.id })
        }
    }

    @Test
    fun aDeletedFinalMessageCannotReturnThroughItsRetiredPendingHistoryAlias() {
        val fixture = unreadFixture(newest = 1_000)
        fixture.complete(fixture.session.unread.last(), listOf(message(100), message(101)))
        fixture.controller.sendText(fixture.state.token, "synthetic draft")
        val pending = message(1_001, outgoing = true).copy(delivery = MessageDelivery.PENDING)
        fixture.session.sends.last()(ChatResult(pending))
        fixture.flush()
        fixture.event(ChatEvent.SendSucceeded(pending.copy(id = 1_200, delivery = MessageDelivery.SENT), pending.id))
        fixture.event(ChatEvent.Deleted(10, listOf(1_200), permanent = true))
        fixture.complete(fixture.session.histories.last(), listOf(message(1_000), pending))
        fixture.complete(fixture.session.histories.last(), emptyList())
        assertEquals(listOf(1_000L), fixture.state.messages.map { it.id })
        assertFalse(fixture.state.historyHasNewer)
    }

    @Test
    fun arrivalsOvertakingThePrivateLatestPageRestartAtTheHeadWithoutJoiningAGap() {
        val fixture = unreadFixture(newest = 1_000)
        fixture.complete(fixture.session.unread.last(), listOf(message(100), message(101)))
        fixture.controller.sendText(fixture.state.token, "synthetic draft")
        fixture.session.sends.last()(ChatResult(message(1_001, outgoing = true)))
        fixture.flush()
        fixture.complete(fixture.session.histories.last(), listOf(message(999), message(1_000), message(1_001, outgoing = true)))
        fixture.event(ChatEvent.NewMessage(message(1_002)))
        fixture.complete(fixture.session.histories.last(), emptyList())
        assertEquals(0L, fixture.session.histories.last().from)
        assertEquals(listOf(100L, 101L), fixture.state.messages.map { it.id })
        fixture.complete(fixture.session.histories.last(), (953L..1_002L).map { message(it) })
        assertEquals((953L..1_002L).toList(), fixture.state.messages.map { it.id })
        assertFalse(fixture.state.historyHasNewer)
        assertEquals(1_002L, fixture.state.jumpMessageId)
    }

    @Test
    fun sendingDuringInitialUnreadLoadingRejectsItsOldResponseWithoutAnEmptyReplacement() {
        val fixture = unreadFixture(newest = 1_000)
        val unread = fixture.session.unread.last()
        val token = fixture.state.token
        fixture.controller.sendText(token, "synthetic draft")
        fixture.session.sends.last()(ChatResult(message(1_001, outgoing = true)))
        fixture.flush()
        fixture.complete(unread, listOf(message(100), message(101)))
        assertTrue(fixture.state.messages.isEmpty(), "An unrelated old unread window cannot enter the replacement")
        assertTrue(fixture.state.newerLoading)
        fixture.complete(fixture.session.histories.last(), (952L..1_001L).map { message(it) })
        assertEquals((952L..1_001L).toList(), fixture.state.messages.map { it.id })
        assertEquals(token, fixture.state.token)
    }

    @Test
    fun anAtomicLatestWindowKeepsANewReplyAndAnInflightEditOwnedByTheirOriginalDrafts() {
        val fixture = unreadFixture(newest = 1_000)
        fixture.complete(fixture.session.unread.last(), listOf(message(100), message(101)))
        val token = fixture.state.token
        fixture.controller.sendText(token, "synthetic draft")
        fixture.session.sends.last()(ChatResult(message(1_001, outgoing = true)))
        fixture.flush()
        fixture.controller.setQuote(token, 100, MessageQuote(0, RichMessageText("synthetic")))
        fixture.controller.loadMessageActions(token, 101)
        fixture.flush()
        fixture.controller.startEdit(token, 101)
        val editOwner = fixture.state.editDraftRevision
        fixture.controller.editMessage(token, "unsubmitted replacement")
        assertTrue(fixture.state.editPending)
        fixture.complete(fixture.session.histories.last(), (952L..1_001L).map { message(it) })
        assertEquals(token, fixture.state.token)
        assertEquals(100L, fixture.state.replyTarget?.id)
        assertEquals("synthetic", fixture.state.replyQuote?.content?.text)
        assertEquals(101L, fixture.state.editTarget?.id)
        assertEquals(editOwner, fixture.state.editDraftRevision)
        assertTrue(fixture.state.editPending)
        fixture.session.edits.single()(ChatResult(message(101).copy(text = "unsubmitted replacement")))
        fixture.flush()
        assertNull(fixture.state.editTarget)
        assertFalse(fixture.state.editPending)
        assertEquals(100L, fixture.state.replyTarget?.id)
        assertTrue(fixture.state.messages.none { it.id == 100L || it.id == 101L },
            "Draft ownership cannot pull old source messages across the gap into the latest history")
    }

    @Test
    fun nativeChangesRefreshAnOffWindowReplySourceWithoutReplacingTheLocalEditOwner() {
        val fixture = unreadFixture(newest = 1_000)
        fixture.complete(fixture.session.unread.last(), listOf(message(100), message(101)))
        val token = fixture.state.token
        fixture.controller.sendText(token, "synthetic draft")
        fixture.session.sends.last()(ChatResult(message(1_001, outgoing = true)))
        fixture.flush()
        fixture.controller.setQuote(token, 100, MessageQuote(0, RichMessageText("synthetic")))
        fixture.controller.loadMessageActions(token, 101)
        fixture.flush()
        fixture.controller.startEdit(token, 101)
        val editTarget = fixture.state.editTarget
        val editOwner = fixture.state.editDraftRevision
        fixture.complete(fixture.session.histories.last(), (952L..1_001L).map { message(it) })
        fixture.event(ChatEvent.Content(10, 100, "new native reply source", MessageKind.TEXT))
        fixture.event(ChatEvent.Edited(10, 100, 17))
        fixture.event(ChatEvent.Content(10, 101, "new native edit source", MessageKind.TEXT))
        assertEquals("new native reply source", fixture.state.replyTarget?.text)
        assertEquals(17, fixture.state.replyTarget?.editDate)
        assertEquals(editTarget, fixture.state.editTarget, "The server source cannot replace the user's local editor snapshot")
        assertEquals(editOwner, fixture.state.editDraftRevision)
        fixture.controller.editMessage(token, "local edit survives")
        assertEquals(listOf("local edit survives"), fixture.session.editTexts)
        fixture.controller.cancelEdit(token)
        fixture.controller.sendText(token, "reply with a stale quote")
        assertEquals(ChatError.INVALID_QUOTE, fixture.state.sendError,
            "A quote must validate against the current native off-window source, not its old cached text")
        assertEquals(1, fixture.session.sends.size)
        assertTrue(fixture.state.messages.none { it.id == 100L || it.id == 101L })
    }

    @Test
    fun repeatedSameChatLatestSwapsRetainOnlySourcesStillOwnedByCurrentDrafts() {
        val fixture = unreadFixture(newest = 1_000)
        fixture.complete(fixture.session.unread.last(), listOf(message(100), message(101)))
        val token = fixture.state.token
        fixture.controller.rememberHistoryAnchor(token, 100)
        fixture.controller.sendText(token, "first draft")
        fixture.session.sends.last()(ChatResult(message(1_001, outgoing = true)))
        fixture.flush()
        fixture.controller.setReply(token, 100)
        fixture.complete(fixture.session.histories.last(), listOf(message(1_000), message(1_001, outgoing = true)))
        fixture.complete(fixture.session.histories.last(), emptyList())
        assertEquals(100L, fixture.state.replyTarget?.id)
        fixture.controller.loadHistoryAnchor(token, 100)
        fixture.complete(fixture.session.histories.last(), listOf(message(100), message(101)))
        assertTrue(fixture.state.historyHasNewer)
        fixture.controller.sendText(token, "second draft")
        fixture.session.sends.last()(ChatResult(message(1_002, outgoing = true)))
        fixture.flush()
        fixture.controller.setReply(token, 101)
        fixture.complete(fixture.session.histories.last(), listOf(message(1_001, outgoing = true), message(1_002, outgoing = true)))
        fixture.complete(fixture.session.histories.last(), emptyList())
        assertEquals(token, fixture.state.token)
        assertEquals(101L, fixture.state.replyTarget?.id)
        fixture.controller.setReply(token, 100)
        assertEquals(101L, fixture.state.replyTarget?.id,
            "An old source with no current owner cannot remain addressable through an accumulating cache")
        fixture.controller.cancelSendLatestNavigation(token)
        fixture.controller.setReply(token, null)
        fixture.controller.setReply(token, 101)
        assertEquals(101L, fixture.state.replyTarget?.id, "The current draft's real source remains available")
    }

    @Test
    fun manualUnreadRacesKeepTheirMarkerAndTheFrozenDividerWhileAutomaticReadCompletes() {
        val fixture = unreadFixture(newest = 102)
        fixture.complete(fixture.session.unread.last(), listOf(message(100), message(101), message(102)))
        fixture.controller.visibleMessages(fixture.state.token, listOf(101), active = true)
        val read = fixture.session.reads.single()
        fixture.event(ChatEvent.MarkedUnread(10, true))
        fixture.event(ChatEvent.Inbox(10, 0, 102))
        read.done(ChatResult(Unit))
        fixture.flush()
        assertTrue(fixture.state.selected!!.markedUnread)
        assertTrue(fixture.session.unreadMarkers.isEmpty())
        assertEquals(ChatUnreadBoundary(100, 101), fixture.state.unreadBoundary)
        assertEquals(1, fixture.session.reads.size)
    }

    private class Fixture {
        var state = ChatSnapshot()
        var publishes = 0
        val states = mutableListOf<ChatSnapshot>()
        private val queued = ArrayDeque<() -> Unit>()
        val session = FakeSession()
        val controller = ChatController(1, session, queued::addLast, { state = it; publishes++; states += it })

        init { controller.start(); flush() }

        fun chat(id: Long, unread: Int, lastRead: Long, newest: Long, marked: Boolean = false) = event(ChatEvent.NewChat(
            ChatItem(id, "synthetic chat", order = id, unreadCount = unread, lastReadInboxId = lastRead,
                lastMessage = message(newest, id), markedUnread = marked)))
        fun select(id: Long) { controller.select(state.token, id); flush() }
        fun event(event: ChatEvent) = controller.event(event)
        fun flush() { while (queued.isNotEmpty()) queued.removeFirst()() }
        fun complete(call: HistoryCall, messages: List<ChatMessage>) { call.done(ChatResult(messages)); flush() }
    }

    private class FakeSession : TdChatSession {
        val unread = mutableListOf<HistoryCall>()
        val newer = mutableListOf<HistoryCall>()
        val histories = mutableListOf<HistoryCall>()
        val reads = mutableListOf<ReadCall>()
        val unreadMarkers = mutableListOf<Pair<Long, Boolean>>()
        val sends = mutableListOf<(ChatResult<ChatMessage>) -> Unit>()
        val edits = mutableListOf<(ChatResult<ChatMessage>) -> Unit>()
        val editTexts = mutableListOf<String>()

        override fun unreadHistory(chatId: Long, afterMessageId: Long, result: (ChatResult<List<ChatMessage>>) -> Unit) {
            unread += HistoryCall(chatId, afterMessageId, result)
        }
        override fun newerHistory(chatId: Long, fromMessageId: Long, result: (ChatResult<List<ChatMessage>>) -> Unit) {
            newer += HistoryCall(chatId, fromMessageId, result)
        }
        override fun loadChats(limit: Int, result: (ChatResult<ChatLoadResult>) -> Unit) { result(ChatResult(ChatLoadResult(true))) }
        override fun history(chatId: Long, fromMessageId: Long, limit: Int, result: (ChatResult<List<ChatMessage>>) -> Unit) {
            histories += HistoryCall(chatId, fromMessageId, result)
        }
        override fun open(chatId: Long) = Unit
        override fun close(chatId: Long) = Unit
        override fun sendText(chatId: Long, text: String, result: (ChatResult<ChatMessage>) -> Unit) { sends += result }
        override fun sendText(chatId: Long, text: String, replyToMessageId: Long?, result: (ChatResult<ChatMessage>) -> Unit) {
            sends += result
        }
        override fun messageActions(chatId: Long, messageId: Long, result: (ChatResult<MessageActions>) -> Unit) {
            result(ChatResult(MessageActions(canEditText = true)))
        }
        override fun editMessage(chatId: Long, messageId: Long, text: String, caption: Boolean, result: (ChatResult<ChatMessage>) -> Unit) {
            editTexts += text
            edits += result
        }
        override fun retry(chatId: Long, messageId: Long, result: (ChatResult<List<ChatMessage>>) -> Unit) = Unit
        override fun markRead(chatId: Long, messageIds: List<Long>, result: (ChatResult<Unit>) -> Unit) { reads += ReadCall(messageIds, true, result) }
        override fun viewMessages(chatId: Long, messageIds: List<Long>, forceRead: Boolean, result: (ChatResult<Unit>) -> Unit) {
            reads += ReadCall(messageIds, forceRead, result)
        }
        override fun markUnread(chatId: Long, marked: Boolean, result: (ChatResult<Unit>) -> Unit) {
            unreadMarkers += chatId to marked
            result(ChatResult(Unit))
        }
    }

    private data class HistoryCall(val chatId: Long, val from: Long, val done: (ChatResult<List<ChatMessage>>) -> Unit)
    private data class ReadCall(val ids: List<Long>, val force: Boolean, val done: (ChatResult<Unit>) -> Unit)

    private companion object {
        fun unreadFixture(newest: Long) = Fixture().apply { chat(10, unread = 20, lastRead = 100, newest = newest); select(10) }
        fun message(id: Long, chatId: Long = 10, outgoing: Boolean = false) = ChatMessage(id, chatId, outgoing = outgoing, text = "synthetic text")
    }
}
