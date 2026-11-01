package com.badnng.moe.tdlib

import com.badnng.moe.security.LocalLockWitness
import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

/** Synthetic callbacks only: no account, native client, filesystem scan or cleanup. */
class SettingsStorageHotLockTerminalTest {
    @Test fun realStatisticsAfterBlockedWitnessDrainsWithoutPublishingThroughTheOwnerGap() {
        val f = Fixture()
        f.controller.open()
        val old = f.snapshot.token
        val publications = f.publications
        // Deliberately no observer: the old C owner still reports current.
        f.lock(notify = false)
        f.storageCalls.single().reply(stats())
        assertEquals(publications, f.publications)
        assertNull(f.snapshot.details)
        f.unlock()
        f.controller.open()
        assertEquals(2, f.storageCalls.size)
        f.storageCalls.last().reply(stats())
        assertEquals(30L, f.snapshot.details!!.bytes)
        assertEquals(1L, f.snapshot.revision)
        f.controller.clear(old, 1, setOf(SettingsStorageFileType.PHOTO))
        assertEquals(2, f.storageCalls.size)
    }

    @Test fun realCleanupAcceptedWhileLockedNeverRepeatsAndNewVisitReadsOnce() {
        val f = Fixture()
        f.openReady()
        f.controller.clear(f.snapshot.token, f.snapshot.revision, setOf(SettingsStorageFileType.PHOTO))
        val clear = f.storageCalls.last()
        assertIs<TdApi.OptimizeStorage>(clear.command)
        f.lock()
        val publications = f.publications
        clear.reply(emptyStats())
        clear.reply(TdApi.Error(403, "duplicate private native text"))
        assertEquals(publications, f.publications)
        assertEquals(2, f.storageCalls.size)
        f.unlock()
        f.controller.open()
        assertEquals(3, f.storageCalls.size)
        assertIs<TdApi.GetStorageStatistics>(f.storageCalls.last().command)
        f.storageCalls.last().reply(emptyStats())
        assertNull(f.snapshot.lastClearError)
        assertEquals(0L, f.snapshot.completedClearRevision)
        assertEquals(1, f.storageCalls.count { it.command is TdApi.OptimizeStorage })
    }

    @Test fun realStatisticsErrorAfterLockAlsoDrainsTheOriginalPhysicalSlot() {
        val f = Fixture()
        f.controller.open()
        f.lock()
        val publications = f.publications
        f.storageCalls.single().reply(TdApi.Error(500, "private scanner details"))
        assertEquals(publications, f.publications)
        f.unlock()
        f.controller.open()
        assertEquals(2, f.storageCalls.size)
        f.storageCalls.last().reply(stats())
        assertEquals(30L, f.snapshot.details!!.bytes)
        assertNull(f.snapshot.error)
    }

    @Test fun realCleanupErrorAfterLockIsRetainedInternallyAndDoesNotBlockFreshStatistics() {
        val f = Fixture()
        f.openReady()
        f.controller.clear(f.snapshot.token, f.snapshot.revision, setOf(SettingsStorageFileType.PHOTO))
        f.lock()
        f.storageCalls.last().reply(TdApi.Error(403, "private-path"))
        f.unlock()
        f.controller.open()
        assertEquals(3, f.storageCalls.size)
        f.storageCalls.last().reply(stats())
        assertEquals(SettingsError.FORBIDDEN, f.snapshot.lastClearError)
        assertEquals(0L, f.snapshot.completedClearRevision)
        assertFalse(f.snapshot.toString().contains("private-path"))
    }

    @Test fun anOldCleanupDuplicateCannotClearTheCurrentCleanupFailure() {
        val f = Fixture()
        f.openReady()
        f.controller.clear(f.snapshot.token, f.snapshot.revision, setOf(SettingsStorageFileType.PHOTO))
        val original = f.storageCalls.last()
        f.lock()
        original.reply(emptyStats())
        f.unlock()
        f.controller.open()
        f.storageCalls.last().reply(stats())
        f.controller.clear(f.snapshot.token, f.snapshot.revision, setOf(SettingsStorageFileType.PHOTO))
        f.storageCalls.last().reply(TdApi.Error(403, "current cleanup failure"))
        original.reply(emptyStats())
        original.failed()
        assertEquals(SettingsError.FORBIDDEN, f.native.settings.storage!!.lastClearError())
        assertEquals(SettingsError.FORBIDDEN, f.snapshot.lastClearError)
        assertEquals(2, f.storageCalls.count { it.command is TdApi.OptimizeStorage })
        assertEquals(0L, f.snapshot.completedClearRevision)
    }

    @Test fun waitingBeforeLockAndSubmittedWhileLockedNeverEnterNative() {
        val f = Fixture()
        val results = mutableListOf<SettingsResult<SettingsStorageDetails>>()
        val storage = f.native.settings.storage!!
        storage.storageDetailsOwned(SettingsProxyProbeOwner { true }, results::add)
        storage.storageDetailsOwned(SettingsProxyProbeOwner { true }, results::add)
        f.lock()
        f.storageCalls.single().reply(stats())
        assertEquals(1, f.storageCalls.size)
        storage.storageDetailsOwned(SettingsProxyProbeOwner { true }, results::add)
        assertEquals(1, f.storageCalls.size)
        assertTrue(results.all { it.value == null })
        f.unlock()
        storage.storageDetailsOwned(SettingsProxyProbeOwner { true }, results::add)
        assertEquals(2, f.storageCalls.size)
        f.storageCalls.last().reply(stats())
        assertEquals(30L, results.last().value!!.bytes)
    }

    @Test fun oldTerminalThreadContextCannotRejectTheNewUnlockedQueuedOwner() {
        val f = Fixture()
        f.controller.open()
        val oldWitness = f.witness.get()
        f.lock()
        f.unlock()
        f.controller.open()
        assertEquals(1, f.storageCalls.size)
        val previous = f.gate.pushCallbackWitness(oldWitness)
        try { f.storageCalls.single().reply(stats()) }
        finally { f.gate.pushCallbackWitness(previous) }
        assertEquals(2, f.storageCalls.size)
        assertNull(f.snapshot.details)
        f.storageCalls.last().reply(stats())
        assertEquals(1L, f.snapshot.revision)
    }

    @Test fun resultQueuedBeforeLockCannotPublishAfterUnlockEvenWithItsOwnerStillCurrent() {
        val f = Fixture(queue = true)
        f.controller.open()
        f.storageCalls.single().reply(stats())
        assertEquals(1, f.actions.size)
        val publications = f.publications
        f.lock(notify = false)
        f.unlock()
        f.drain()
        assertEquals(publications, f.publications)
        assertNull(f.snapshot.details)
        f.controller.open()
        f.storageCalls.last().reply(stats())
        f.drain()
        assertEquals(30L, f.snapshot.details!!.bytes)
    }

    @Test fun uncertainCleanupKeepsItsSlotUntilActualTerminalThenUsesItsRealOutcome() {
        val f = Fixture()
        f.openReady()
        f.controller.clear(f.snapshot.token, f.snapshot.revision, setOf(SettingsStorageFileType.PHOTO))
        val clear = f.storageCalls.last()
        clear.failed()
        assertEquals(SettingsError.REQUEST_FAILED, f.snapshot.lastClearError)
        f.lock()
        f.unlock()
        f.controller.open()
        assertEquals(2, f.storageCalls.size)
        clear.reply(TdApi.Error(403, "real terminal"))
        assertEquals(3, f.storageCalls.size)
        f.storageCalls.last().reply(stats())
        assertEquals(SettingsError.FORBIDDEN, f.snapshot.lastClearError)
        assertEquals(1, f.storageCalls.count { it.command is TdApi.OptimizeStorage })
    }

    @Test fun synchronousReceiptCapturesItsContextBeforeLeaseDeferredDelivery() {
        val f = Fixture()
        f.onSend = { call ->
            call.reply(stats())
            f.lock(notify = false)
        }
        f.controller.open()
        assertNull(f.snapshot.details)
        assertEquals(1, f.storageCalls.size)
        f.onSend = null
        f.unlock()
        f.controller.open()
        assertEquals(2, f.storageCalls.size)
        f.storageCalls.last().reply(stats())
        assertEquals(30L, f.snapshot.details!!.bytes)
    }

    @Test fun realSynchronousTerminalWinsOverASubsequentSendThrow() {
        val f = Fixture()
        f.onSend = { call ->
            call.reply(stats())
            throw IllegalStateException("synthetic throw after the real response")
        }
        f.controller.open()
        assertEquals(30L, f.snapshot.details!!.bytes)
        assertNull(f.snapshot.error)
        f.onSend = null
        f.controller.refresh(f.snapshot.token)
        assertEquals(2, f.storageCalls.size)
        f.storageCalls.last().reply(emptyStats())
        assertEquals(0L, f.snapshot.details!!.bytes)
    }

    @Test fun sendThrowWithoutARealResponseKeepsTheOriginalSlotAcrossLockAndUnlock() {
        val f = Fixture()
        f.onSend = { throw IllegalStateException("synthetic ambiguous submission") }
        f.controller.open()
        assertEquals(SettingsError.REQUEST_FAILED, f.snapshot.error)
        f.onSend = null
        f.lock()
        f.unlock()
        f.controller.open()
        assertEquals(1, f.storageCalls.size)
        f.storageCalls.single().reply(stats())
        assertEquals(2, f.storageCalls.size)
        assertNull(f.snapshot.details)
        f.storageCalls.last().reply(stats())
        assertEquals(30L, f.snapshot.details!!.bytes)
    }

    @Test fun emailRevisionAndAuthorizationChangesSuppressTheOldValueButReleaseTheFlight() {
        val f = Fixture()
        f.controller.open()
        f.gate.update(LoginEmailSuggestions(1, requiredPresent = true, requiredEpoch = 1))
        f.storageCalls.single().reply(stats())
        assertNull(f.snapshot.details)
        f.gate.satisfyRequired(1)
        f.controller.open()
        assertEquals(2, f.storageCalls.size)
        f.ready = false
        f.storageCalls.last().reply(stats())
        assertNull(f.snapshot.details)
        f.ready = true
        f.controller.open()
        assertEquals(3, f.storageCalls.size)
        f.storageCalls.last().reply(stats())
        assertEquals(30L, f.snapshot.details!!.bytes)
    }

    @Test fun closeOkAndAnotherSessionDoNotResolveTheOriginalPendingIoOrPublishItsLateValue() {
        val f = Fixture()
        f.controller.open()
        f.controller.open()
        assertEquals(1, f.storageCalls.size)
        f.native.close()
        f.calls.single { it.command is TdApi.Close }.reply(TdApi.Ok())
        val other = Fixture()
        other.controller.open()
        other.storageCalls.single().reply(stats())
        assertEquals(1L, other.snapshot.revision)
        assertEquals(1, f.storageCalls.size)
        f.storageCalls.single().reply(stats())
        assertNull(f.snapshot.details)
        assertEquals(1, f.storageCalls.size)
        assertEquals(1L, other.snapshot.revision)
    }

    private class Fixture(val queue: Boolean = false) {
        val witness = AtomicReference(LocalLockWitness(1, false))
        val gate = TdUserInteractionGate().also { it.bindLocalWitness(witness::get) }
        var ready = true
        val calls = mutableListOf<Call>()
        val storageCalls: List<Call> get() = calls.filter {
            it.command is TdApi.GetStorageStatistics || it.command is TdApi.OptimizeStorage
        }
        var onSend: ((Call) -> Unit)? = null
        val actions = ArrayDeque<() -> Unit>()
        var publications = 0
        var snapshot = SettingsStorageSnapshot()
        val native = OfficialTdSession(offline = true, interactionGate = gate, isAuthorizationReady = { ready },
            send = { command, reply, failed ->
                val call = Call(command, reply, failed)
                calls += call
                onSend?.invoke(call)
            })
        val controller = SettingsStorageController(7, native.settings.storage!!,
            enqueue = { if (queue) actions.addLast(it) else it() },
            publish = { snapshot = it; publications++ })

        fun openReady() { controller.open(); storageCalls.single().reply(stats()); drain() }
        fun lock(notify: Boolean = true) {
            witness.updateAndGet { LocalLockWitness(it.epoch + 1, true) }
            if (notify) gate.localWitnessChanged()
        }
        fun unlock() {
            witness.updateAndGet { LocalLockWitness(it.epoch + 1, false) }
            gate.localWitnessChanged()
        }
        fun drain() { while (actions.isNotEmpty()) actions.removeFirst()() }
    }

    private class Call(val command: TdApi.Function<*>, val reply: (TdApi.Object) -> Unit, val failed: () -> Unit)

    private companion object {
        fun emptyStats() = TdApi.StorageStatistics(0, 0, emptyArray())
        fun stats() = TdApi.StorageStatistics(30, 3, arrayOf(TdApi.StorageStatisticsByChat(0, 30, 3,
            arrayOf(TdApi.StorageStatisticsByFileType(TdApi.FileTypePhoto(), 30, 3)))))
    }
}
