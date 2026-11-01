package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import kotlin.test.*

/** Synthetic actor/native callbacks only; no JNI, account, user data or live UI. */
class NotificationExceptionsHotLockTerminalTest {
    @Test fun partialClearKeepsPerItemFactsAndEpochLossNeverReplaysTheRemainingItems() {
        val f = Fixture(); val peers = listOf(peer(1), peer(2), peer(3))
        val initialList = f.native.lists.single()
        f.loaded(*peers.toTypedArray()); f.prepare(*peers.toTypedArray())
        val preparedList = f.native.lists.last()
        assertEquals(2, f.native.lists.size, "Opening and preparing the clear each require their own list read.")
        assertSame(initialList, f.native.lists.first())
        assertNotSame(initialList, preparedList)
        assertEquals(NotificationScope.PRIVATE_CHATS, preparedList.scope)
        assertFalse(initialList.active()); assertFalse(preparedList.active())
        val listsBeforeMutation = f.native.lists.size
        f.controller.clear(f.state.token, f.state.clearPlan!!.id)
        f.native.writes.last().result(NotificationExceptionWriteResult(true, defaults(peers[0]))); f.flush()
        val pending = f.native.writes.last()
        f.epoch++; f.allowed = false; f.controller.close(f.state.token)
        f.allowed = true; f.controller.open(NotificationScope.GROUP_CHATS) { true }
        assertEquals(listsBeforeMutation, f.native.lists.size, "A replacement page waits for the original physical mutation.")
        assertFalse(pending.active(), "Unlock cannot renew the old action authority.")
        pending.result(NotificationExceptionWriteResult(true, error = SettingsError.NOT_READY)); f.flush()
        assertEquals(mapOf(1L to NotificationSettingsWriteReceipt.ACCEPTED,
            2L to NotificationSettingsWriteReceipt.ACCEPTED, 3L to NotificationSettingsWriteReceipt.NOT_DISPATCHED), f.controller.lastClearReceipts)
        assertEquals(2, f.native.writes.size)
        assertFalse(f.state.saving); assertTrue(f.state.loading)
        assertEquals(listsBeforeMutation + 1, f.native.lists.size, "Only the replacement page gets one read after the real terminal.")
        val replacementList = f.native.lists.last()
        assertEquals(NotificationScope.GROUP_CHATS, replacementList.scope)
        assertTrue(replacementList.active())
        assertFalse(initialList.active()); assertFalse(preparedList.active())
        pending.result(NotificationExceptionWriteResult(true)); f.flush()
        assertSame(replacementList, f.native.lists.last())
        assertEquals(listsBeforeMutation + 1, f.native.lists.size, "A duplicate old terminal cannot start another read.")
        assertEquals(2, f.native.writes.size)
    }

    @Test fun indeterminateClearKeepsPhysicalOwnershipUntilTheRealLateErrorAndNeverAdvancesWhileUnknown() {
        val f = Fixture(); val peers = listOf(peer(1), peer(2))
        val initialList = f.native.lists.single()
        f.loaded(*peers.toTypedArray()); f.prepare(*peers.toTypedArray())
        val preparedList = f.native.lists.last()
        assertEquals(2, f.native.lists.size, "The clear plan has its own authoritative list preflight.")
        assertNotSame(initialList, preparedList)
        assertFalse(initialList.active()); assertFalse(preparedList.active())
        val listsBeforeMutation = f.native.lists.size
        f.controller.clear(f.state.token, f.state.clearPlan!!.id)
        val pending = f.native.writes.single()
        pending.result(NotificationExceptionWriteResult(error = SettingsError.REQUEST_FAILED,
            receipt = NotificationSettingsWriteReceipt.INDETERMINATE)); f.flush()
        assertTrue(f.state.saving); assertEquals(1, f.native.writes.size)
        assertEquals(listsBeforeMutation, f.native.lists.size, "An unknown receipt cannot start readback.")
        assertEquals(NotificationSettingsWriteReceipt.INDETERMINATE, f.controller.lastClearReceipts[1])
        f.epoch++; f.controller.close(f.state.token)
        assertFalse(pending.active())
        pending.result(NotificationExceptionWriteResult(error = SettingsError.FORBIDDEN,
            receipt = NotificationSettingsWriteReceipt.REJECTED)); pending.result(NotificationExceptionWriteResult(true)); f.flush()
        assertEquals(NotificationSettingsWriteReceipt.REJECTED, f.controller.lastClearReceipts[1])
        assertEquals(NotificationSettingsWriteReceipt.NOT_DISPATCHED, f.controller.lastClearReceipts[2])
        assertEquals(1, f.native.writes.size)
        assertEquals(listsBeforeMutation, f.native.lists.size, "A closed page's real terminal only settles internal ownership.")
        f.controller.open(NotificationScope.PRIVATE_CHATS) { true }
        assertFalse(f.state.saving)
        assertEquals(listsBeforeMutation + 1, f.native.lists.size)
        assertEquals(NotificationScope.PRIVATE_CHATS, f.native.lists.last().scope)
        assertTrue(f.native.lists.last().active())
        assertFalse(initialList.active()); assertFalse(preparedList.active())
    }

    @Test fun closedPageAndRetiredGenerationNeverPublishOldContentOrCountDuplicateAcceptances() {
        val f = Fixture(); val original = peer(1)
        f.loaded(original); f.editor(original)
        f.controller.save(f.state.token, f.state.editorRevision, edit(original))
        val pending = f.native.writes.single(); f.controller.close(f.state.token)
        val closed = f.state
        pending.result(NotificationExceptionWriteResult(true, original)); pending.result(NotificationExceptionWriteResult(true, original)); f.flush()
        assertFalse(f.state.opened); assertNull(f.state.editor)
        assertEquals(closed, f.state, "A closed owner terminal only settles internal bookkeeping.")
        val other = Fixture(); other.loaded(original); other.editor(original)
        other.controller.save(other.state.token, other.state.editorRevision, edit(original))
        val retired = other.native.writes.single(); other.controller.deactivate(); val last = other.state
        retired.result(NotificationExceptionWriteResult(true, original)); other.flush()
        assertEquals(last, other.state)
    }

    private class Fixture {
        val native = Native()
        val queue = ArrayDeque<() -> Unit>()
        var state = NotificationExceptionsSnapshot()
        var epoch = 0L
        var allowed = true
        var visible = true
        val controller = NotificationExceptionsController(7, native, queue::addLast, { state = it }, { allowed },
            terminal = SettingsSpecialMutationBoundary(queue::addLast,
                authority = { val captured = epoch; { epoch == captured && allowed } }))
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
