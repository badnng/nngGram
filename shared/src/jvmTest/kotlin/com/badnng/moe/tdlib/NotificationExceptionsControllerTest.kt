package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import kotlin.test.*

class NotificationExceptionsControllerTest {
    @Test fun nativeIdsAreFrozenAndResolvedOnlyInExplicitClientBatchesOfThirtyTwo() {
        val f = Fixture()
        val listed = f.native.lists.single()
        val ids = (1L..65L).toList()
        listed.result(SettingsResult(ids)); listed.result(SettingsResult(emptyList())); f.flush()
        assertEquals(ids.take(32), f.native.resolves.single().ids)
        f.resolve(f.native.resolves.last().ids.map { peer(it) })
        assertEquals(65, f.state.totalIds)
        assertEquals(32, f.state.rows.size)
        assertTrue(f.state.hasMore)
        assertEquals(1, f.native.lists.size)
        f.controller.more(f.state.token)
        assertEquals(ids.drop(32).take(32), f.native.resolves.last().ids)
        val second = f.native.resolves.last()
        second.result(SettingsResult(second.ids.map { NotificationExceptionRow(it, peer(it)) }))
        second.result(SettingsResult(error = SettingsError.FORBIDDEN)); f.flush()
        assertEquals(64, f.state.rows.size)
        f.controller.more(f.state.token)
        assertEquals(listOf(65L), f.native.resolves.last().ids)
        f.resolve(listOf(peer(65)))
        assertEquals(65, f.state.rows.size)
        assertFalse(f.state.hasMore)
        assertTrue(f.native.resolves.all { it.ids.size <= 32 })
    }

    @Test fun pageAndSearchLeasesRejectOldCallbacksAndRequireExplicitRemoteSearch() {
        val f = Fixture()
        val old = f.native.lists.single()
        f.controller.open(NotificationScope.GROUP_CHATS) { true }
        assertFalse(old.active())
        old.result(SettingsResult(listOf(900))); f.flush()
        assertTrue(f.native.resolves.isEmpty())
        f.loaded(peer(2, scope = NotificationScope.GROUP_CHATS))
        f.controller.select(f.state.token)
        val overview = f.native.searches.single()
        assertEquals("", overview.query)
        assertFalse(overview.remote)
        f.controller.search(f.state.token, "first", false)
        val first = f.native.searches.last()
        f.controller.search(f.state.token, "second", true)
        val second = f.native.searches.last()
        assertFalse(first.active())
        assertTrue(second.remote)
        first.result(SettingsResult(listOf(NotificationExceptionRow(3, peer(3))))); f.flush()
        assertTrue(f.state.candidates.isEmpty())
        second.result(SettingsResult(listOf(NotificationExceptionRow(4, peer(4, scope = NotificationScope.GROUP_CHATS)))))
        second.result(SettingsResult(emptyList())); f.flush()
        assertEquals(listOf(4L), f.state.candidates.map { it.chatId })
        val requests = f.native.searches.size
        f.controller.search(f.state.token, "x".repeat(129), true)
        assertEquals(SettingsError.INVALID_VALUE, f.state.searchError)
        assertEquals(requests, f.native.searches.size)
        overview.result(SettingsResult(emptyList())); f.flush()
        assertEquals(SettingsError.INVALID_VALUE, f.state.searchError)
    }

    @Test fun updatesAndFailedWritesKeepTheEditorBaselineUntilExplicitSuccessfulReadback() {
        val f = Fixture()
        val original = peer(1)
        f.loaded(original); f.editor(original)
        val revision = f.state.editorRevision
        f.native.observer?.invoke(999); f.flush()
        assertFalse(f.state.refreshRequired, "An unrelated chat update cannot stale the current editor baseline.")
        assertEquals(revision, f.state.editorRevision)
        f.native.observer?.invoke(null); f.flush()
        assertTrue(f.state.refreshRequired)
        assertEquals(revision, f.state.editorRevision)
        val edit = edit(original)
        f.controller.save(f.state.token, revision, edit)
        val write = f.native.writes.single()
        assertEquals(edit, write.edit)
        write.result(NotificationExceptionWriteResult(locallyAccepted = true, error = SettingsError.REQUEST_FAILED))
        write.result(NotificationExceptionWriteResult(actual = original)); f.flush()
        assertEquals(original, f.state.editor)
        assertEquals(revision, f.state.editorRevision)
        assertEquals(SettingsError.REQUEST_FAILED, f.state.editorError)
        assertTrue(f.state.readbackPending)
        assertEquals(1L, f.state.acceptedRevision)
        f.controller.reloadEditor(f.state.token)
        val reload = f.native.reads.last()
        reload.result(SettingsResult(error = SettingsError.FORBIDDEN)); f.flush()
        assertEquals(original, f.state.editor)
        assertEquals(revision, f.state.editorRevision)
        f.controller.reloadEditor(f.state.token)
        val actual = original.copy(values = edit.desired)
        f.native.reads.last().result(SettingsResult(actual)); f.flush()
        assertEquals(actual, f.state.editor)
        assertTrue(f.state.editorRevision > revision)
        assertFalse(f.state.readbackPending)
    }

    @Test fun aNewPageWaitsForTheActualOldMutationTerminalAndNeverAdoptsItsEditorResult() {
        val f = Fixture()
        val oldPeer = peer(1)
        f.loaded(oldPeer); f.editor(oldPeer)
        f.controller.save(f.state.token, f.state.editorRevision, edit(oldPeer))
        val write = f.native.writes.single()
        val oldToken = f.state.token
        f.controller.close(oldToken)
        f.controller.open(NotificationScope.GROUP_CHATS) { true }
        val current = f.state.token
        assertFalse(write.active())
        assertTrue(f.state.saving)
        assertEquals(1, f.native.lists.size)
        val result = NotificationExceptionWriteResult(true, oldPeer.copy(values = edit(oldPeer).desired))
        write.result(result); write.result(result); f.flush()
        assertEquals(current, f.state.token)
        assertNull(f.state.editor)
        assertEquals(1L, f.state.acceptedRevision)
        assertTrue(f.state.loading)
        assertEquals(2, f.native.lists.size)
        assertEquals(NotificationScope.GROUP_CHATS, f.native.lists.last().scope)
        f.loaded(peer(2, scope = NotificationScope.GROUP_CHATS))
        assertEquals(listOf(2L), f.state.rows.map { it.chatId })
        assertFalse(f.state.saving)
    }

    @Test fun clearPreparationFreezesVerifiedMetadataAndHonestUnresolvedRowsInBoundedBatches() {
        val f = Fixture()
        f.loaded()
        f.controller.prepareClear(f.state.token)
        f.native.lists.last().result(SettingsResult((1L..33L).toList())); f.flush()
        assertEquals(32, f.native.resolves.last().ids.size)
        val first = f.native.resolves.last()
        val rows = first.ids.map { id -> when (id) {
            1L -> NotificationExceptionRow(id, peer(id))
            2L -> NotificationExceptionRow(id, peer(id).copy(editable = false))
            3L -> NotificationExceptionRow(id, peer(id, scope = NotificationScope.CHANNEL_CHATS))
            else -> NotificationExceptionRow(id, peer(id).copy(values = ChatNotificationValues()))
        } }
        first.result(SettingsResult(rows)); f.flush()
        assertEquals(listOf(33L), f.native.resolves.last().ids)
        f.native.resolves.last().result(SettingsResult(error = SettingsError.RATE_LIMITED)); f.flush()
        val plan = assertNotNull(f.state.clearPlan)
        assertEquals(listOf(1L), plan.targets.map { it.chatId })
        assertEquals(setOf(2L, 3L, 33L), plan.unresolved.map { it.chatId }.toSet())
        assertTrue(f.native.writes.isEmpty(), "Preparing a confirmation never mutates native settings.")
        f.controller.cancelClear(f.state.token)
        assertNull(f.state.clearPlan)
    }

    @Test fun clearRetriesOnlyItsOriginalFailuresAndVerifiedDefaultsCompleteAnEarlierAcceptedWrite() {
        val f = Fixture()
        val first = peer(1); val second = peer(2)
        f.loaded(first, second)
        f.prepare(first, second)
        val planId = f.state.clearPlan!!.id
        f.controller.clear(f.state.token, planId)
        val one = f.native.writes.single()
        assertEquals(NotificationExceptionField.entries.toSet(), one.edit.dirty)
        assertEquals(first.values.newMessageDefaults(), one.edit.desired)
        one.result(NotificationExceptionWriteResult(true, defaults(first))); f.flush()
        val two = f.native.writes.last()
        assertEquals(2L, two.edit.expected.chatId)
        two.result(NotificationExceptionWriteResult(locallyAccepted = true, error = SettingsError.REQUEST_FAILED)); f.flush()
        assertEquals(setOf(1L), f.state.clearPlan!!.completed)
        assertEquals(setOf(2L), f.state.clearPlan!!.failures.keys)
        assertTrue(f.state.readbackPending)
        assertEquals(listOf(1L, 2L), f.state.rows.map { it.chatId }, "Completion does not invent native list membership removal.")
        f.native.observer?.invoke(99); f.flush()
        assertTrue(f.state.refreshRequired)
        f.controller.clear(f.state.token, planId)
        assertEquals(listOf(1L, 2L, 2L), f.native.writes.map { it.edit.expected.chatId })
        val retry = f.native.writes.last()
        assertEquals(second.values.muteStories, retry.edit.desired.muteStories)
        assertEquals(second.values.storySoundId, retry.edit.desired.storySoundId)
        assertFalse(retry.edit.desired.useDefaultStorySound)
        val alreadyDefault = NotificationExceptionWriteResult(actual = defaults(second), error = SettingsError.CONFLICT)
        retry.result(alreadyDefault); retry.result(alreadyDefault); f.flush()
        assertEquals(setOf(1L, 2L), f.state.clearPlan!!.completed)
        assertTrue(f.state.clearPlan!!.failures.isEmpty())
        assertFalse(f.state.readbackPending)
        assertEquals(2L, f.state.acceptedRevision)
        assertEquals(2, f.native.lists.size, "An observer cannot silently enroll a newly added exception in the frozen plan.")
    }

    @Test fun closingOrRetiringDuringClearStopsItsNativeChainAndReleasesTheMutationForANewPage() {
        for (retire in listOf(false, true)) {
            val f = Fixture()
            val first = peer(1); val second = peer(2)
            f.loaded(first, second); f.prepare(first, second)
            f.controller.clear(f.state.token, f.state.clearPlan!!.id)
            val pending = f.native.writes.single()
            if (retire) f.controller.deactivate() else {
                f.controller.close(f.state.token)
                f.controller.open(NotificationScope.GROUP_CHATS) { true }
                assertTrue(f.state.saving)
            }
            val published = f.state
            assertFalse(pending.active())
            pending.result(NotificationExceptionWriteResult(true, defaults(first)))
            pending.result(NotificationExceptionWriteResult(true, defaults(first))); f.flush()
            assertEquals(1, f.native.writes.size)
            if (retire) assertSame(published, f.state) else {
                assertEquals(NotificationScope.GROUP_CHATS, f.native.lists.last().scope)
                assertTrue(f.state.loading)
                assertFalse(f.state.saving)
            }
        }
    }

    @Test fun oversizedPlansAndCanceledPreparationNeverResolveOrPublishAnUnreviewedClearPlan() {
        val f = Fixture()
        f.loaded()
        f.controller.prepareClear(f.state.token)
        f.native.lists.last().result(SettingsResult((1L..32_769L).toList())); f.flush()
        assertEquals(SettingsError.INVALID_VALUE, f.state.error)
        assertFalse(f.state.preparingClear)
        assertTrue(f.native.resolves.isEmpty())
        assertNull(f.state.clearPlan)
        f.controller.prepareClear(f.state.token)
        val pending = f.native.lists.last()
        f.controller.cancelClear(f.state.token)
        assertFalse(pending.active())
        pending.result(SettingsResult(listOf(1))); f.flush()
        assertTrue(f.native.resolves.isEmpty())
        assertNull(f.state.clearPlan)
    }

    @Test fun invalidMetadataOldOwnersAndAccountGateCannotAuthorizeAnEditorWrite() {
        val f = Fixture()
        f.native.lists.single().result(SettingsResult(listOf(1, 2))); f.flush()
        f.native.resolves.single().result(SettingsResult(listOf(NotificationExceptionRow(1, peer(9))))); f.flush()
        assertTrue(f.state.rows.all { it.peer == null && it.error != null })
        val token = f.state.token
        f.controller.edit(token, 1)
        f.native.reads.single().result(SettingsResult(peer(1, scope = NotificationScope.GROUP_CHATS))); f.flush()
        assertNull(f.state.editor)
        assertEquals(0L, f.state.editorRevision)
        assertNotNull(f.state.editorError)
        f.controller.reloadEditor(token)
        val read = f.native.reads.last()
        f.allowed = false
        assertFalse(read.active())
        read.result(SettingsResult(peer(1))); f.flush()
        f.controller.save(token, 0, edit(peer(1)))
        assertTrue(f.native.writes.isEmpty())
        assertFalse(f.controller.accepts(token.copy(generation = token.generation - 1)))
        f.allowed = true
        f.controller.close(token)
        f.controller.open(NotificationScope.PRIVATE_CHATS) { true }
        f.controller.save(token, 0, edit(peer(1)))
        assertTrue(f.native.writes.isEmpty())
    }

    @Test fun verifiedSaveReadbackRebasesAfterOwnUpdateAndBackReloadsActualNewMembership() {
        val f = Fixture()
        val existing = peer(1)
        val candidate = peer(2).copy(values = ChatNotificationValues())
        f.loaded(existing)
        f.controller.select(f.state.token)
        f.native.searches.last().result(SettingsResult(listOf(NotificationExceptionRow(2, candidate)))); f.flush()
        f.controller.edit(f.state.token, 2)
        f.native.reads.last().result(SettingsResult(candidate)); f.flush()
        val firstEdit = NotificationExceptionEdit(candidate,
            candidate.values.copy(useDefaultShowPreview = false, showPreview = false), setOf(NotificationExceptionField.PREVIEW))
        f.controller.save(f.state.token, f.state.editorRevision, firstEdit)
        f.native.observer?.invoke(2); f.flush()
        assertTrue(f.state.refreshRequired)
        val saved = candidate.copy(values = firstEdit.desired)
        f.native.writes.last().result(NotificationExceptionWriteResult(true, saved)); f.flush()
        assertEquals(saved, f.state.editor)
        assertFalse(f.state.refreshRequired, "A verified full actual readback covers the write's own native update.")
        assertEquals(listOf(1L), f.state.rows.map { it.chatId }, "The controller cannot invent membership for the added exception.")
        val next = NotificationExceptionEdit(saved, saved.values.copy(showPreview = true), setOf(NotificationExceptionField.PREVIEW))
        f.controller.save(f.state.token, f.state.editorRevision, next)
        assertEquals(2, f.native.writes.size, "Successful readback restores editor admission without a redundant reload.")
        val latest = saved.copy(values = next.desired)
        f.native.writes.last().result(NotificationExceptionWriteResult(true, latest)); f.flush()
        f.controller.back(f.state.token)
        assertTrue(f.state.loading)
        assertEquals(2, f.native.lists.size)
        f.loaded(existing, latest)
        assertEquals(listOf(1L, 2L), f.state.rows.map { it.chatId })
        assertEquals(2, f.state.totalIds)
    }

    @Test fun gateLossStopsTheClearChainAndOnlyAnExplicitRetryResumesItsUnfinishedOriginalTargets() {
        val f = Fixture()
        val peers = listOf(peer(1), peer(2), peer(3))
        f.loaded(*peers.toTypedArray()); f.prepare(*peers.toTypedArray())
        val planId = f.state.clearPlan!!.id
        f.controller.clear(f.state.token, planId)
        f.native.writes.last().result(NotificationExceptionWriteResult(true, defaults(peers[0]))); f.flush()
        val pending = f.native.writes.last()
        f.allowed = false
        assertFalse(pending.active())
        pending.result(NotificationExceptionWriteResult(locallyAccepted = true, error = SettingsError.NOT_READY)); f.flush()
        assertEquals(listOf(1L, 2L), f.native.writes.map { it.edit.expected.chatId })
        assertFalse(f.state.saving)
        assertEquals(setOf(1L), f.state.clearPlan!!.completed)
        assertEquals(setOf(2L, 3L), f.state.clearPlan!!.failures.keys)
        assertTrue(f.state.readbackPending)
        f.allowed = true
        assertEquals(2, f.native.writes.size, "Restoring permission never automatically resumes a retired native chain.")
        f.controller.clear(f.state.token, planId)
        f.native.writes.last().result(NotificationExceptionWriteResult(actual = defaults(peers[1]), error = SettingsError.CONFLICT)); f.flush()
        f.native.writes.last().result(NotificationExceptionWriteResult(true, defaults(peers[2]))); f.flush()
        assertEquals(listOf(1L, 2L, 2L, 3L), f.native.writes.map { it.edit.expected.chatId })
        assertEquals(setOf(1L, 2L, 3L), f.state.clearPlan!!.completed)
        assertTrue(f.state.clearPlan!!.failures.isEmpty())
        assertFalse(f.state.readbackPending)
    }

    @Test fun anUnclaimedInactivePageRetiresOnReadOrOldWriteTerminalAndCanBeOpenedAgain() {
        val f = Fixture()
        val unclaimed = f.state.token
        f.visible = false
        f.native.lists.single().result(SettingsResult(listOf(1))); f.flush()
        assertFalse(f.state.opened)
        assertFalse(f.state.loading)
        assertNotEquals(unclaimed, f.state.token)
        assertTrue(f.native.resolves.isEmpty())
        f.visible = true
        f.controller.open(NotificationScope.PRIVATE_CHATS) { f.visible }
        f.loaded(peer(2))
        assertTrue(f.state.opened)
        assertEquals(listOf(2L), f.state.rows.map { it.chatId })

        val pending = Fixture()
        val original = peer(1)
        pending.loaded(original); pending.editor(original)
        pending.controller.save(pending.state.token, pending.state.editorRevision, edit(original))
        val write = pending.native.writes.single()
        pending.controller.close(pending.state.token)
        pending.controller.open(NotificationScope.GROUP_CHATS) { pending.visible }
        val waiting = pending.state.token
        pending.visible = false
        write.result(NotificationExceptionWriteResult(true, original)); pending.flush()
        assertFalse(pending.state.opened)
        assertFalse(pending.state.saving)
        assertNotEquals(waiting, pending.state.token)
        assertEquals(1, pending.native.lists.size, "No read may start for the unclaimed inactive replacement page.")
        pending.visible = true
        pending.controller.open(NotificationScope.GROUP_CHATS) { pending.visible }
        pending.loaded(peer(3, scope = NotificationScope.GROUP_CHATS))
        assertEquals(listOf(3L), pending.state.rows.map { it.chatId })
    }

    private class Fixture {
        val native = Native()
        val queue = ArrayDeque<() -> Unit>()
        var state = NotificationExceptionsSnapshot()
        var allowed = true
        var visible = true
        val controller = NotificationExceptionsController(7, native, queue::addLast, { state = it }, { allowed })
        init { controller.open(NotificationScope.PRIVATE_CHATS) { visible } }
        fun flush() { while (queue.isNotEmpty()) queue.removeFirst().invoke() }
        fun loaded(vararg peers: NotificationExceptionPeer) {
            native.lists.last().result(SettingsResult(peers.map { it.chatId })); flush()
            if (peers.isNotEmpty()) resolve(peers.toList())
        }
        fun resolve(peers: List<NotificationExceptionPeer>) {
            native.resolves.last().result(SettingsResult(peers.map { NotificationExceptionRow(it.chatId, it) })); flush()
        }
        fun editor(peer: NotificationExceptionPeer) {
            controller.edit(state.token, peer.chatId)
            native.reads.last().result(SettingsResult(peer)); flush()
        }
        fun prepare(vararg peers: NotificationExceptionPeer) {
            controller.prepareClear(state.token)
            native.lists.last().result(SettingsResult(peers.map { it.chatId })); flush()
            if (peers.isNotEmpty()) resolve(peers.toList())
        }
    }

    /** No active checks or callback deduplication: the actual controller must enforce ownership. */
    private class Native : TdNotificationExceptionsSession {
        data class ListRead(val scope: NotificationScope, val active: () -> Boolean, val result: (SettingsResult<List<Long>>) -> Unit)
        data class Resolve(val ids: List<Long>, val active: () -> Boolean, val result: (SettingsResult<List<NotificationExceptionRow>>) -> Unit)
        data class Search(val query: String, val remote: Boolean, val active: () -> Boolean, val result: (SettingsResult<List<NotificationExceptionRow>>) -> Unit)
        data class Read(val active: () -> Boolean, val result: (SettingsResult<NotificationExceptionPeer>) -> Unit)
        data class Write(val edit: NotificationExceptionEdit, val active: () -> Boolean, val result: (NotificationExceptionWriteResult) -> Unit)
        val lists = mutableListOf<ListRead>()
        val resolves = mutableListOf<Resolve>()
        val searches = mutableListOf<Search>()
        val reads = mutableListOf<Read>()
        val writes = mutableListOf<Write>()
        var observer: ((Long?) -> Unit)? = null
        override fun observe(observer: ((Long?) -> Unit)?) { this.observer = observer }
        override fun list(scope: NotificationScope, active: () -> Boolean, result: (SettingsResult<List<Long>>) -> Unit) { lists += ListRead(scope, active, result) }
        override fun resolve(scope: NotificationScope, ids: List<Long>, active: () -> Boolean, result: (SettingsResult<List<NotificationExceptionRow>>) -> Unit) { resolves += Resolve(ids, active, result) }
        override fun search(scope: NotificationScope, query: String, remote: Boolean, active: () -> Boolean, result: (SettingsResult<List<NotificationExceptionRow>>) -> Unit) { searches += Search(query, remote, active, result) }
        override fun read(scope: NotificationScope, chatId: Long, active: () -> Boolean, result: (SettingsResult<NotificationExceptionPeer>) -> Unit) { reads += Read(active, result) }
        override fun save(edit: NotificationExceptionEdit, active: () -> Boolean, result: (NotificationExceptionWriteResult) -> Unit) { writes += Write(edit, active, result) }
    }

    private companion object {
        fun peer(id: Long, scope: NotificationScope = NotificationScope.PRIVATE_CHATS) = NotificationExceptionPeer(id, scope, "Synthetic $id",
            ChatNotificationValues(useDefaultShowPreview = false, useDefaultMuteStories = false, muteStories = true,
                useDefaultStorySound = false, storySoundId = 77, useDefaultShowStoryPoster = false, showStoryPoster = false), 10_000)
        fun edit(peer: NotificationExceptionPeer) = NotificationExceptionEdit(peer, peer.values.copy(showPreview = false), setOf(NotificationExceptionField.PREVIEW))
        fun defaults(peer: NotificationExceptionPeer) = peer.copy(values = peer.values.newMessageDefaults())
    }
}
