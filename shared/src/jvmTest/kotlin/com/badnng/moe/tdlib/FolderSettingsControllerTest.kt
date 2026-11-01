package com.badnng.moe.tdlib

import com.badnng.moe.chat.ChatFolderInfo
import com.badnng.moe.folders.*
import com.badnng.moe.settings.*
import kotlin.test.*

class FolderSettingsControllerTest {
    @Test fun aQueuedOpenWhosePageAlreadyLeftCannotReadOrReviveAnOwner() {
        val f = Fixture()
        f.controller.open({ false }, 31)
        assertFalse(f.state.opened)
        assertTrue(f.session.reads.isEmpty())
        f.open()
        assertEquals(31L, f.state.token.pageId)
        val token = f.state.token
        f.controller.close(token.copy(pageId = 99))
        assertTrue(f.controller.accepts(token))
    }

    @Test fun closingAnOpeningBeforeCapabilitiesFinishStopsRecommendationAndPublication() {
        val f = Fixture()
        f.controller.open({ true }, 31)
        val token = f.state.token
        val pending = f.session.reads.single()
        f.controller.close(token)
        assertFalse(pending.current())
        pending.done(SettingsResult(FolderCapabilities(true, 10, 100)))
        f.flush()
        assertTrue(f.session.suggestions.isEmpty())
        assertFalse(f.state.opened)
    }

    @Test fun recommendationsAndRemovedRowsOnlyChangeTheParentDraftBeforeExplicitApply() {
        val f = Fixture().apply { open() }
        f.controller.remove(f.state.token, 2)
        assertEquals(setOf(2), f.state.draft?.removed)
        f.controller.restore(f.state.token, 2)
        assertFalse(assertNotNull(f.state.draft).dirty)
        f.controller.stageRecommendation(f.state.token, 91)
        assertTrue(FolderDraftEntry.Recommended(91) in f.state.draft!!.order)
        f.controller.stageRecommendation(f.state.token, 91)
        assertFalse(f.state.draft!!.dirty)
        assertTrue(f.session.writes.isEmpty())
    }

    @Test fun ordinaryAccountsCannotMoveMainOrChangeTagsAndSharedDefinitionsRemainReadOnly() {
        val shared = ChatFolderInfo(2, "Shared", isShareable = true)
        val f = Fixture(source = FolderNativeState(listOf(shared, life))).apply { open(premium = false) }
        val before = f.state.draft
        f.controller.move(f.state.token, FolderDraftEntry.Main, 1)
        f.controller.move(f.state.token, FolderDraftEntry.Existing(2), -1)
        f.controller.remove(f.state.token, 2)
        f.controller.toggleTags(f.state.token, true)
        f.controller.color(f.state.token, shared, 3)
        assertEquals(before, f.state.draft)
        assertTrue(f.session.writes.isEmpty())
    }

    @Test fun aKnownPremiumLossImmediatelyRevokesItsActionsAndRetainsTheExistingOverviewDraft() {
        val f = Fixture(source = FolderNativeState(listOf(work, life), tagsEnabled = true)).apply { open() }
        // A yet unknown GetMe identity must not be silently treated as an ordinary account.
        f.controller.move(f.state.token, FolderDraftEntry.Main, 1)
        f.controller.remove(f.state.token, 3)
        val draft = f.state.draft
        f.controller.accountPremium(false)
        assertFalse(f.state.capabilities!!.premium)
        assertTrue(f.state.nativeState.tagsEnabled, "Losing mutation eligibility never invents a native display flag change.")
        f.controller.move(f.state.token, FolderDraftEntry.Main, 1)
        f.controller.apply(f.state.token, draft!!.revision)
        assertEquals(draft, f.state.draft)
        assertEquals(SettingsError.FORBIDDEN, f.state.error)
        assertTrue(f.session.writes.isEmpty(), "Do not start ordinary Delete/Create steps of a draft whose final Main position needs Premium.")
        f.controller.reset(f.state.token)
        f.controller.toggleTags(f.state.token, false)
        f.controller.color(f.state.token, work, 3)
        assertTrue(f.session.writes.isEmpty())
        f.controller.move(f.state.token, FolderDraftEntry.Existing(3), -1)
        f.controller.apply(f.state.token, f.state.draft!!.revision)
        assertEquals("reorder", f.session.writes.single().kind, "Ordinary Main=0 ordering remains available.")
    }

    @Test fun premiumLossRevokesPendingNativeSendLeasesAndLateCapabilitiesCannotGrantItBack() {
        val f = Fixture().apply { open() }
        f.controller.toggleTags(f.state.token, true)
        val write = f.session.writes.single()
        assertTrue(write.current())
        f.controller.accountPremium(false)
        assertFalse(write.current(), "Every later native stage rechecks the authoritative account eligibility.")
        write.unit!!.invoke(SettingsResult(Unit)); f.flush()
        f.controller.nativeUpdate(f.source.copy(tagsEnabled = true))
        assertFalse(f.state.busy)
        assertTrue(f.state.nativeState.tagsEnabled, "Already sent native acceptance is still accountable.")
        assertFalse(f.state.capabilities!!.premium)
        f.controller.refresh(f.state.token)
        f.session.reads.last().done(SettingsResult(FolderCapabilities(true, 10, 100))); f.flush()
        assertFalse(f.state.capabilities!!.premium, "An older GetMe result cannot overrule a newer authoritative account loss.")
        f.controller.accountPremium(true)
        assertFalse(f.state.capabilities!!.premium, "A profile alone cannot fabricate the refreshed options/capability contract.")
    }

    @Test fun configuredCapacityCountsRemovedRowsAndRestoreCannotExceedItsActualLimit() {
        val f = Fixture().apply { open(limit = 2) }
        f.controller.stageRecommendation(f.state.token, 91)
        assertFalse(f.state.draft!!.dirty)
        f.controller.remove(f.state.token, 2)
        f.controller.stageRecommendation(f.state.token, 91)
        f.controller.restore(f.state.token, 2)
        assertEquals(setOf(2), f.state.draft!!.removed)
        assertTrue(FolderDraftEntry.Recommended(91) in f.state.draft!!.order)
    }

    @Test fun eitherNativeUpdateOrAcknowledgementMayArriveFirstWithoutLosingTheMatchingResult() {
        for (updateFirst in listOf(false, true)) {
            val f = Fixture().apply { open() }
            f.controller.move(f.state.token, FolderDraftEntry.Existing(3), -1)
            val revision = f.state.draft!!.revision
            f.controller.apply(f.state.token, revision)
            val write = f.session.writes.single()
            assertEquals("reorder", write.kind)
            assertEquals(listOf(3, 2), write.ids)
            assertEquals(0, write.main)
            val reordered = FolderNativeState(listOf(life, work))
            if (updateFirst) f.controller.nativeUpdate(reordered)
            else { write.unit!!.invoke(SettingsResult(Unit)); f.flush(); assertTrue(f.state.busy) }
            if (updateFirst) { write.unit!!.invoke(SettingsResult(Unit)); f.flush() } else f.controller.nativeUpdate(reordered)
            assertFalse(f.state.busy)
            assertFalse(f.state.draft!!.dirty)
            assertEquals(revision, f.state.completedRevision)
            assertEquals(1, f.state.acceptedSteps)
        }
    }

    @Test fun externalListChangesConflictRatherThanSilentlyRebaseASortDraft() {
        val f = Fixture().apply { open() }
        f.controller.move(f.state.token, FolderDraftEntry.Existing(3), -1)
        val draft = f.state.draft
        f.controller.nativeUpdate(f.source.copy(folders = f.source.folders + ChatFolderInfo(8, "Remote")))
        assertTrue(f.state.conflict)
        assertEquals(draft, f.state.draft)
        f.controller.apply(f.state.token, draft!!.revision)
        assertTrue(f.session.writes.isEmpty())
        f.controller.reset(f.state.token)
        assertFalse(f.state.conflict)
        assertEquals(listOf(2, 3, 8), f.state.draft!!.source.folders.map { it.id })
    }

    @Test fun deletingAddingThenAFailedReorderRetriesOnlyTheUnacceptedRemainder() {
        val f = Fixture().apply { open() }
        f.controller.remove(f.state.token, 2)
        f.controller.stageRecommendation(f.state.token, 91)
        f.controller.apply(f.state.token, f.state.draft!!.revision)
        val remove = f.session.writes.single()
        assertEquals("remove", remove.kind)
        f.controller.nativeUpdate(FolderNativeState(listOf(life)))
        remove.unit!!.invoke(SettingsResult(Unit)); f.flush()
        val add = f.session.writes.last()
        assertEquals("create", add.kind)
        add.created!!.invoke(SettingsResult(created)); f.flush()
        assertEquals(2, f.session.writes.size)
        f.controller.nativeUpdate(FolderNativeState(listOf(created, life)))
        val reorder = f.session.writes.last()
        assertEquals("reorder", reorder.kind)
        assertEquals(listOf(3, 4), reorder.ids)
        reorder.unit!!.invoke(SettingsResult(error = SettingsError.REQUEST_FAILED)); f.flush()
        assertFalse(f.state.busy)
        assertEquals(2, f.state.acceptedSteps)
        assertTrue(f.state.draft!!.removed.isEmpty())
        assertTrue(f.state.draft!!.order.none { it is FolderDraftEntry.Recommended })
        f.controller.apply(f.state.token, f.state.draft!!.revision)
        assertEquals(listOf("remove", "create", "reorder", "reorder"), f.session.writes.map { it.kind })
        f.controller.nativeUpdate(FolderNativeState(listOf(life, created)))
        f.session.writes.last().unit!!.invoke(SettingsResult(Unit)); f.flush()
        assertFalse(f.state.busy)
        assertFalse(f.state.draft!!.dirty)
    }

    @Test fun closingAfterNativeAcceptanceStopsTheBatchAndReopeningNeverDuplicatesItsCreate() {
        val f = Fixture().apply { open() }
        f.controller.stageRecommendation(f.state.token, 91)
        f.controller.apply(f.state.token, f.state.draft!!.revision)
        val add = f.session.writes.single()
        f.controller.close(f.state.token)
        assertFalse(add.current())
        add.created!!.invoke(SettingsResult(created)); f.flush()
        f.controller.nativeUpdate(FolderNativeState(listOf(created, work, life)))
        assertEquals(1, f.session.writes.size)
        assertFalse(f.state.busy)
        f.open()
        assertTrue(f.state.draft!!.order.none { it is FolderDraftEntry.Recommended })
        f.controller.apply(f.state.token, f.state.draft!!.revision)
        assertEquals(listOf("create"), f.session.writes.map { it.kind }, "Closing revokes the old batch draft; reopening reads native state.")
    }

    @Test fun acceptedButDelayedUpdatesTimeOutWithoutRetryingACreateOrInventingCloudRollback() {
        val f = Fixture().apply { open() }
        f.controller.stageRecommendation(f.state.token, 91)
        f.controller.apply(f.state.token, f.state.draft!!.revision)
        f.session.writes.single().created!!.invoke(SettingsResult(created)); f.flush()
        assertTrue(f.state.busy)
        f.timeouts.last().invoke(); f.flush()
        assertFalse(f.state.busy)
        assertTrue(f.state.conflict)
        assertEquals(1, f.state.acceptedSteps)
        assertTrue(f.state.draft!!.order.none { it is FolderDraftEntry.Recommended })
        f.controller.apply(f.state.token, f.state.draft!!.revision)
        assertEquals(1, f.session.writes.size)
    }

    @Test fun anUnconfirmedAcceptedWriteBlocksResetUntilItsMatchingNativeUpdate() {
        val f = Fixture().apply { open() }
        f.controller.stageRecommendation(f.state.token, 91)
        f.controller.apply(f.state.token, f.state.draft!!.revision)
        f.session.writes.single().created!!.invoke(SettingsResult(created)); f.flush()
        f.timeouts.last().invoke(); f.flush()
        val unresolved = f.state.draft
        f.controller.reset(f.state.token)
        f.controller.move(f.state.token, FolderDraftEntry.Existing(3), -1)
        assertEquals(unresolved, f.state.draft, "Unconfirmed writes block reset and duplicate submissions.")
        f.controller.nativeUpdate(FolderNativeState(listOf(created, work, life)))
        assertFalse(f.state.conflict)
        assertEquals(1, f.session.writes.size)
    }

    @Test fun tagsAndColorUseNativeMatchingUpdatesAndNeverChangeDraftMembership() {
        val f = Fixture().apply { open() }
        f.controller.toggleTags(f.state.token, true)
        f.session.writes.single().unit!!.invoke(SettingsResult(Unit)); f.flush()
        assertTrue(f.state.busy)
        f.controller.nativeUpdate(f.source.copy(tagsEnabled = true))
        assertFalse(f.state.busy)
        assertTrue(f.state.nativeState.tagsEnabled)
        f.controller.color(f.state.token, work, 5)
        val color = f.session.writes.last()
        assertEquals("color", color.kind)
        f.controller.nativeUpdate(f.source.copy(tagsEnabled = true, folders = listOf(work.copy(colorId = 5), life)))
        color.unit!!.invoke(SettingsResult(Unit)); f.flush()
        assertFalse(f.state.busy)
        assertFalse(f.state.draft!!.dirty)
        assertEquals(listOf(2, 3), f.state.nativeState.folders.map { it.id })
    }

    @Test fun retiredGenerationDropsEveryLateResultAndNeverReleasesANewOwner() {
        val f = Fixture().apply { open() }
        f.controller.toggleTags(f.state.token, true)
        val write = f.session.writes.single()
        val before = f.state
        f.controller.deactivate()
        assertFalse(write.current())
        write.unit!!.invoke(SettingsResult(Unit)); f.flush()
        f.controller.nativeUpdate(f.source.copy(tagsEnabled = true))
        assertEquals(before, f.state)
        assertTrue(f.session.retired)
    }

    private class Fixture(val source: FolderNativeState = FolderNativeState(listOf(work, life))) {
        val session = Session()
        val queue = ArrayDeque<() -> Unit>()
        val timeouts = mutableListOf<() -> Unit>()
        var state = FolderSettingsSnapshot()
        val controller = FolderSettingsController(7, session, { queue.addLast(it) }, { millis, work ->
            assertEquals(5_000L, millis); timeouts += work
        }, { state = it }, mutationReceipt = FolderMutationTerminal::receipt)
        init { controller.nativeUpdate(source) }
        fun flush() { while (queue.isNotEmpty()) queue.removeFirst().invoke() }
        fun open(premium: Boolean = true, limit: Int = 10) {
            controller.open({ true }, 31)
            session.reads.last().done(SettingsResult(FolderCapabilities(premium, limit, 100))); flush()
            session.suggestions.lastOrNull()?.let { if (!it.completed) {
                it.completed = true; it.done(SettingsResult(listOf(FolderRecommendation(91, "Unread", "Synthetic native description")))); flush()
            } }
        }
    }
    private class Session : TdFolderSettingsSession {
        class Read<T>(val current: () -> Boolean, val done: (SettingsResult<T>) -> Unit) { var completed = false }
        data class Write(val kind: String, val current: () -> Boolean, val expected: FolderNativeState,
            val id: Int = 0, val key: Long = 0, val ids: List<Int> = emptyList(), val main: Int = 0,
            val unit: ((SettingsResult<Unit>) -> Unit)? = null, val created: ((SettingsResult<ChatFolderInfo>) -> Unit)? = null)
        val reads = mutableListOf<Read<FolderCapabilities>>()
        val suggestions = mutableListOf<Read<List<FolderRecommendation>>>()
        val writes = mutableListOf<Write>()
        private fun <T> terminal(done: (SettingsResult<T>) -> Unit): (SettingsResult<T>) -> Unit = { value ->
            FolderMutationTerminal.scoped(receipt = if (value.error == null) FolderMutationReceipt.RESPONSE
                else FolderMutationReceipt.ERROR_RESPONSE) { done(value) }
        }
        var retired = false
        override fun retire() { retired = true }
        override fun capabilities(current: () -> Boolean, result: (SettingsResult<FolderCapabilities>) -> Unit) { reads += Read(current, result) }
        override fun recommendations(current: () -> Boolean, result: (SettingsResult<List<FolderRecommendation>>) -> Unit) { suggestions += Read(current, result) }
        override fun createRecommended(key: Long, expected: FolderNativeState, current: () -> Boolean, result: (SettingsResult<ChatFolderInfo>) -> Unit) { writes += Write("create", current, expected, key = key, created = terminal(result)) }
        override fun remove(id: Int, expected: FolderNativeState, current: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) { writes += Write("remove", current, expected, id = id, unit = terminal(result)) }
        override fun reorder(ids: List<Int>, mainPosition: Int, expected: FolderNativeState, current: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) { writes += Write("reorder", current, expected, ids = ids, main = mainPosition, unit = terminal(result)) }
        override fun toggleTags(enabled: Boolean, expected: FolderNativeState, current: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) { writes += Write("tags", current, expected, unit = terminal(result)) }
        override fun color(folder: ChatFolderInfo, colorId: Int, expected: FolderNativeState, current: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) { writes += Write("color", current, expected, id = folder.id, unit = terminal(result)) }
    }
    private companion object {
        val work = ChatFolderInfo(2, "Work")
        val life = ChatFolderInfo(3, "Life")
        val created = ChatFolderInfo(4, "Unread")
    }
}
