package com.badnng.moe.tdlib

import com.badnng.moe.chat.ChatFolderInfo
import com.badnng.moe.folders.*
import com.badnng.moe.security.LocalLockWitness
import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import kotlin.test.*

/** Captured native callbacks and manually advanced actor/deadline queues only. No native client. */
class FolderSettingsHotLockTerminalTest {
    @Test fun realResponsesCrossLockButCannotStartAnotherRpcOrLeakTls() {
        for (response in listOf<TdApi.Object>(TdApi.Ok(), TdApi.Error(403, "synthetic"))) {
            val f = GateFixture()
            var received: TdApi.Object? = null
            var receipt: FolderMutationReceipt? = null
            var rejectedFollowup = 0
            FolderMutationTerminal.scoped {
                f.send(TdApi.ToggleChatFolderTags(true), {
                    received = it; receipt = FolderMutationTerminal.receipt()
                    assertFalse(f.gate.callbackContextIsCurrent())
                    f.send(TdApi.ToggleChatFolderTags(false), {}, { rejectedFollowup++ })
                }, { fail("A real physical response must not become a gate failure") })
            }
            f.lock()
            f.calls.single().result(response)
            assertSame(response, received)
            assertEquals(if (response is TdApi.Error) FolderMutationReceipt.ERROR_RESPONSE else FolderMutationReceipt.RESPONSE, receipt)
            assertEquals(1, rejectedFollowup)
            assertEquals(1, f.calls.size)
            assertFalse(FolderMutationTerminal.active())
            assertEquals(FolderMutationReceipt.NOT_DISPATCHED, FolderMutationTerminal.receipt())
            assertNull(f.gate.callbackWitness())
            assertNull(f.gate.callbackEmailRevision())
        }
    }

    @Test fun rejectionUnknownAndThrowAfterAdmissionHaveDifferentProvenance() {
        val f = GateFixture()
        var receipt: FolderMutationReceipt? = null
        FolderMutationTerminal.scoped {
            f.send(TdApi.ToggleChatFolderTags(true), {}, { receipt = FolderMutationTerminal.receipt() })
        }
        f.lock(); f.calls.single().failed()
        assertEquals(FolderMutationReceipt.UNKNOWN, receipt)
        FolderMutationTerminal.scoped {
            f.send(TdApi.ToggleChatFolderTags(false), {}, { receipt = FolderMutationTerminal.receipt() })
        }
        assertEquals(FolderMutationReceipt.NOT_DISPATCHED, receipt)
        assertEquals(1, f.calls.size)
        f.unlock()
        val throwing = f.gate.guard { _, _, _ -> throw IllegalStateException("synthetic admitted failure") }
        FolderMutationTerminal.scoped {
            throwing(TdApi.ToggleChatFolderTags(true), {}, { receipt = FolderMutationTerminal.receipt() })
        }
        assertEquals(FolderMutationReceipt.UNKNOWN, receipt)
    }

    @Test fun readPreflightCannotContinueToFinalWriteAfterLock() {
        val f = GateFixture()
        val native = OfficialTdFolderSettings(f.send)
        native.accept(TdApi.UpdateChatFolders(emptyArray(), 0, false))
        var result: SettingsResult<Unit>? = null
        var receipt: FolderMutationReceipt? = null
        FolderMutationTerminal.scoped {
            native.toggleTags(true, FolderNativeState(), { true }) {
                result = it; receipt = FolderMutationTerminal.receipt()
            }
        }
        assertIs<TdApi.GetMe>(f.calls.single().command)
        f.lock()
        f.calls.single().result(TdApi.User().apply { id = 71; isPremium = true })
        assertNotNull(result?.error)
        assertEquals(FolderMutationReceipt.NOT_DISPATCHED, receipt)
        assertEquals(1, f.calls.size)
    }

    @Test fun finalNativeChainKeepsReceiptProvenanceAcrossReadCallbacks() {
        val f = GateFixture()
        val native = OfficialTdFolderSettings(f.send)
        native.accept(TdApi.UpdateChatFolders(emptyArray(), 0, false))
        var receipt: FolderMutationReceipt? = null
        var value: SettingsResult<Unit>? = null
        FolderMutationTerminal.scoped {
            native.toggleTags(true, FolderNativeState(), { true }) {
                receipt = FolderMutationTerminal.receipt(); value = it
            }
        }
        f.calls.last().result(TdApi.User().apply { id = 71; isPremium = true })
        assertIs<TdApi.ToggleChatFolderTags>(f.calls.last().command)
        f.lock(); f.calls.last().result(TdApi.Ok())
        assertEquals(FolderMutationReceipt.RESPONSE, receipt)
        assertNotNull(value?.value)
        assertEquals(2, f.calls.size)
    }

    @Test fun deadlineExistsBeforeReceiptAndSurvivesLockWithoutProjection() {
        val f = Fixture().apply { open(); beginCreate() }
        assertEquals(1, f.deadlines.size)
        f.lockAndClose()
        val closed = f.state
        f.deadlines.single()(); f.flush()
        assertEquals(closed, f.state)
        f.unlock(); f.open()
        assertFalse(f.state.busy)
        assertTrue(f.state.conflict)
        f.controller.apply(f.state.token, f.state.draft!!.revision)
        assertEquals(1, f.session.writes.size)
    }

    @Test fun queuedReceiptThenLockCannotContinueBatchOrConsumeNewPageDraft() {
        val f = Fixture().apply { open() }
        f.controller.remove(f.state.token, 2)
        f.controller.stageRecommendation(f.state.token, 91)
        f.controller.apply(f.state.token, f.state.draft!!.revision)
        f.session.writes.single().complete()
        f.lockAndClose()
        f.controller.nativeUpdate(FolderNativeState(listOf(life)))
        f.flush()
        f.unlock(); f.open()
        assertFalse(f.state.busy)
        assertFalse(f.state.draft!!.dirty)
        assertEquals(1, f.session.writes.size, "Old accepted remove cannot start the next create")
    }

    @Test fun realSuccessBeforeOrAfterUpdateSettlesOnceAndDoesNotReplayAfterRelock() {
        for (updateFirst in listOf(false, true)) {
            val f = Fixture().apply { open() }
            f.controller.toggleTags(f.state.token, true)
            if (updateFirst) f.controller.nativeUpdate(f.source.copy(tagsEnabled = true))
            f.session.writes.single().complete(); f.flush()
            if (!updateFirst) f.controller.nativeUpdate(f.source.copy(tagsEnabled = true))
            assertFalse(f.state.busy)
            assertEquals(1, f.state.acceptedSteps)
            f.lockAndClose(); f.unlock(); f.open(); f.lockAndClose(); f.unlock(); f.open()
            f.deadlines.forEach { it() }; f.flush()
            assertFalse(f.state.busy)
            assertEquals(1, f.session.writes.size)
        }
    }

    @Test fun acceptedCreateTimeoutAcrossLockWaitsForMatchingUpdateWithoutSecondWrite() {
        val f = Fixture().apply { open(); beginCreate() }
        f.session.writes.single().create(SettingsResult(created)); f.flush()
        f.lockAndClose(); f.deadlines.single()(); f.flush()
        f.unlock(); f.open()
        assertFalse(f.state.busy); assertTrue(f.state.conflict)
        f.controller.reset(f.state.token)
        f.controller.stageRecommendation(f.state.token, 91)
        assertFalse(f.state.draft!!.dirty)
        f.controller.nativeUpdate(FolderNativeState(listOf(created, work, life)))
        assertFalse(f.state.conflict)
        assertEquals(listOf(4, 2, 3), f.state.draft!!.source.folders.map { it.id })
        assertEquals(1, f.session.writes.size)
    }

    @Test fun newPageRereadsNativeCacheWhenOldActorUpdateWasDropped() {
        val f = Fixture().apply { open(); beginCreate() }
        f.session.writes.single().create(SettingsResult(created)); f.flush()
        f.lockAndClose()
        // Native cache accepted this update, but its older actor envelope was revoked.
        f.session.cachedState = FolderNativeState(listOf(created, work, life))
        f.deadlines.single()(); f.flush()
        f.unlock(); f.open()
        assertFalse(f.state.busy); assertFalse(f.state.conflict)
        assertEquals(listOf(4, 2, 3), f.state.draft!!.source.folders.map { it.id })
        assertEquals(1, f.session.writes.size)
    }

    @Test fun withoutClearsAndRestoresAllFolderTerminalContext() {
        FolderMutationTerminal.scoped(receipt = FolderMutationReceipt.ERROR_RESPONSE) {
            FolderMutationTerminal.without {
                assertFalse(FolderMutationTerminal.active())
                assertEquals(FolderMutationReceipt.NOT_DISPATCHED, FolderMutationTerminal.receipt())
            }
            assertTrue(FolderMutationTerminal.active())
            assertEquals(FolderMutationReceipt.ERROR_RESPONSE, FolderMutationTerminal.receipt())
        }
        assertFalse(FolderMutationTerminal.active())
    }

    @Test fun unknownResultDoesNotCountAcceptanceAndMatchingNativeStateCanResolveIt() {
        val f = Fixture().apply { open() }
        f.controller.toggleTags(f.state.token, true)
        f.session.writes.single().complete(SettingsResult(error = SettingsError.REQUEST_FAILED), FolderMutationReceipt.UNKNOWN)
        f.flush()
        assertFalse(f.state.busy); assertTrue(f.state.conflict)
        assertEquals(0, f.state.acceptedSteps)
        f.controller.toggleTags(f.state.token, true)
        assertEquals(1, f.session.writes.size)
        f.controller.nativeUpdate(f.source.copy(tagsEnabled = true))
        assertFalse(f.state.conflict)
        assertEquals(0, f.state.acceptedSteps)
    }

    @Test fun timeoutThenLateCreateReceiptAndNativeUpdateNeverRestartOldBatch() {
        val f = Fixture().apply { open(); beginCreate() }
        f.deadlines.single()(); f.flush()
        assertFalse(f.state.busy); assertTrue(f.state.conflict)
        f.lockAndClose(); f.unlock(); f.open()
        f.session.writes.single().create(SettingsResult(created)); f.flush()
        assertFalse(f.state.busy); assertTrue(f.state.conflict)
        f.controller.nativeUpdate(FolderNativeState(listOf(created, work, life)))
        assertFalse(f.state.conflict)
        assertEquals(1, f.session.writes.size)
    }

    @Test fun unknownCreateAndMalformedSuccessNeverAuthorizeDuplicateWrite() {
        for (receipt in listOf(FolderMutationReceipt.UNKNOWN, FolderMutationReceipt.RESPONSE)) {
            val f = Fixture().apply { open(); beginCreate() }
            f.session.writes.single().createFailure(receipt)
            f.flush(); f.lockAndClose(); f.unlock(); f.open()
            f.controller.reset(f.state.token)
            f.controller.stageRecommendation(f.state.token, 91)
            f.controller.apply(f.state.token, f.state.draft!!.revision)
            assertFalse(f.state.busy); assertTrue(f.state.conflict)
            assertEquals(0, f.state.acceptedSteps)
            assertEquals(1, f.session.writes.size)
        }
    }

    @Test fun realErrorAndNotDispatchedNeverBecomeSuccessAndReopenIsUsable() {
        for (receipt in listOf(FolderMutationReceipt.ERROR_RESPONSE, FolderMutationReceipt.NOT_DISPATCHED)) {
            val f = Fixture().apply { open() }
            f.controller.toggleTags(f.state.token, true)
            f.lockAndClose()
            f.session.writes.single().complete(SettingsResult(error = SettingsError.NOT_READY), receipt)
            f.flush(); f.unlock(); f.open()
            assertFalse(f.state.busy)
            assertEquals(0, f.state.acceptedSteps)
            f.controller.reset(f.state.token)
            f.controller.toggleTags(f.state.token, true)
            assertEquals(2, f.session.writes.size, "Only explicit new user action may write again")
        }
    }

    @Test fun replacingPageOwnerAndRetiringGenerationCannotReviveOldInputs() {
        val f = Fixture().apply { open(); beginCreate() }
        val old = f.state.token
        f.open(page = 99)
        val newToken = f.state.token
        f.controller.stageRecommendation(old, 91)
        assertFalse(f.state.draft!!.dirty)
        f.session.writes.single().create(SettingsResult(created)); f.flush()
        assertEquals(newToken, f.state.token)
        assertFalse(f.state.draft!!.dirty)
        f.controller.deactivate()
        val state = f.state
        f.deadlines.forEach { it() }; f.flush()
        f.controller.nativeUpdate(FolderNativeState(listOf(created, work, life)))
        assertEquals(state, f.state)
        assertEquals(1, f.session.writes.size)
    }

    @Test fun duplicatePhysicalCallbacksAreIdempotentAndTimeoutDoesNotEraseAcceptedIdentity() {
        val f = Fixture().apply { open(); beginCreate() }
        val write = f.session.writes.single()
        write.create(SettingsResult(created)); write.create(SettingsResult(created)); f.flush()
        assertEquals(1, f.state.acceptedSteps)
        f.deadlines.single()(); f.flush()
        assertFalse(f.state.busy); assertTrue(f.state.conflict)
        assertTrue(f.state.draft!!.order.none { it is FolderDraftEntry.Recommended })
        f.controller.apply(f.state.token, f.state.draft!!.revision)
        assertEquals(1, f.session.writes.size)
    }

    private class GateFixture {
        data class Call(val command: TdApi.Function<*>, val result: (TdApi.Object) -> Unit, val failed: () -> Unit)
        var witness = LocalLockWitness(1, false)
        val gate = TdUserInteractionGate().also { it.bindLocalWitness { witness } }
        val calls = mutableListOf<Call>()
        val send = gate.guard { command, result, failed -> calls += Call(command, result, failed) }
        fun lock() { witness = LocalLockWitness(witness.epoch + 1, true); gate.localWitnessChanged() }
        fun unlock() { witness = LocalLockWitness(witness.epoch + 1, false); gate.localWitnessChanged() }
    }

    private class Fixture {
        val source = FolderNativeState(listOf(work, life))
        var epoch = 1L
        var blocked = false
        val session = Session()
        val queue = ArrayDeque<() -> Unit>()
        val deadlines = mutableListOf<() -> Unit>()
        var state = FolderSettingsSnapshot()
        val controller = FolderSettingsController(7, session, { queue.addLast(it) }, { delay, work ->
            assertEquals(5_000L, delay); deadlines += work
        }, { state = it }, enqueueTerminal = { queue.addLast(it) }, captureAuthority = {
            val captured = epoch
            val valid: () -> Boolean = { !blocked && epoch == captured }
            valid
        }, mutationReceipt = FolderMutationTerminal::receipt)
        init { controller.nativeUpdate(source) }
        fun flush() { while (queue.isNotEmpty()) queue.removeFirst()() }
        fun open(page: Long = 31) { controller.open({ true }, page); flush() }
        fun beginCreate() {
            controller.stageRecommendation(state.token, 91)
            controller.apply(state.token, state.draft!!.revision)
        }
        fun lockAndClose() { blocked = true; epoch++; controller.close(state.token) }
        fun unlock() { blocked = false; epoch++ }
    }

    private class Session : TdFolderSettingsSession {
        class Write(val current: () -> Boolean, private val unit: ((SettingsResult<Unit>) -> Unit)? = null,
            private val created: ((SettingsResult<ChatFolderInfo>) -> Unit)? = null,
        ) {
            fun complete(value: SettingsResult<Unit> = SettingsResult(Unit), receipt: FolderMutationReceipt = FolderMutationReceipt.RESPONSE) {
                FolderMutationTerminal.scoped(receipt = receipt) { unit!!.invoke(value) }
            }
            fun create(value: SettingsResult<ChatFolderInfo>) {
                FolderMutationTerminal.scoped(receipt = FolderMutationReceipt.RESPONSE) { created!!.invoke(value) }
            }
            fun createFailure(receipt: FolderMutationReceipt) {
                FolderMutationTerminal.scoped(receipt = receipt) {
                    created!!.invoke(SettingsResult(error = SettingsError.REQUEST_FAILED))
                }
            }
        }
        val writes = mutableListOf<Write>()
        var cachedState: FolderNativeState? = null
        override fun latestNativeState(current: () -> Boolean): FolderNativeState? = cachedState?.takeIf { current() }
        override fun capabilities(current: () -> Boolean, result: (SettingsResult<FolderCapabilities>) -> Unit) {
            if (current()) result(SettingsResult(FolderCapabilities(true, 10, 100)))
        }
        override fun recommendations(current: () -> Boolean, result: (SettingsResult<List<FolderRecommendation>>) -> Unit) {
            if (current()) result(SettingsResult(listOf(FolderRecommendation(91, "Unread", "Synthetic"))))
        }
        override fun createRecommended(key: Long, expected: FolderNativeState, current: () -> Boolean, result: (SettingsResult<ChatFolderInfo>) -> Unit) {
            writes += Write(current, created = result)
        }
        override fun remove(id: Int, expected: FolderNativeState, current: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) { writes += Write(current, unit = result) }
        override fun reorder(ids: List<Int>, mainPosition: Int, expected: FolderNativeState, current: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) { writes += Write(current, unit = result) }
        override fun toggleTags(enabled: Boolean, expected: FolderNativeState, current: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) { writes += Write(current, unit = result) }
        override fun color(folder: ChatFolderInfo, colorId: Int, expected: FolderNativeState, current: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) { writes += Write(current, unit = result) }
    }
    private companion object {
        val work = ChatFolderInfo(2, "Work")
        val life = ChatFolderInfo(3, "Life")
        val created = ChatFolderInfo(4, "Unread")
    }
}
