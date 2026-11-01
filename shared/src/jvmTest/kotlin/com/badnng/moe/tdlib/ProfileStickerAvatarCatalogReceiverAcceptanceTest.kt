package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import kotlin.test.*

/** Controlled callbacks are synthetic; only explicitly witnessed cases model one ordered receiver. */
class ProfileStickerAvatarCatalogReceiverAcceptanceTest {
    @Test fun witnessedInitialUpdateRechecksOnlyAfterWorkerExitAndCanBeRevokedBeforeDispatch() {
        val h = Harness(ordered = true)
        val result = h.start()
        h.helpers.single().acceptCatalogUpdate(TdApi.UpdateStickerSet(TdApi.StickerSet()))
        h.reply(0, 1)
        val first = h.workers.tasks.removeFirst()
        first.action()
        assertEquals(1, h.pending.size, "A dirty initial result only queues its recheck")
        assertTrue(result.isEmpty())
        first.exited()
        assertEquals(2, h.pending.size, "The recheck starts only after the worker really exits")
        h.reply(1, 2); h.workers.finishOne()
        assertEquals(2L, id(result.single()), "Only the rechecked result is published")
        h.close()

        val revoked = Harness(ordered = true)
        val lost = revoked.start()
        revoked.helpers.single().acceptCatalogUpdate(TdApi.UpdateStickerSet(TdApi.StickerSet()))
        revoked.reply(0, 1)
        val old = revoked.workers.tasks.removeFirst()
        old.action()
        revoked.helpers.single().retire()
        assertEquals(SettingsError.NOT_READY, lost.single().error)
        old.exited()
        assertEquals(1, revoked.pending.size, "An undispatched recheck loses its place when its owner retires")
        assertEquals(1, lost.size)
        revoked.close()
    }

    @Test fun anUnwitnessedReentrantFakeDoesNotAcquireTheOfficialReceiverCapability() {
        val h = Harness(ordered = false)
        val first = h.start()
        val second = h.start()
        assertEquals(2, h.pending.size, "A generic fake has not supplied the ordered-receiver witness")
        // Deliver in reverse order: neither owner may publish the other's result.
        h.reply(1, 2)
        h.reply(0, 1)
        assertEquals(1L, id(first.single()))
        assertEquals(2L, id(second.single()))
        h.close()
    }

    @Test fun retireAndACompletedCallbackDoNotFreeTheLargeResponseBeforeWorkerExit() {
        val h = Harness(ordered = true)
        val first = h.start()
        val second = h.start()
        assertEquals(1, h.pending.size)
        h.reply(0, 1) // callback returns, but the worker has not run
        val task = h.workers.tasks.removeFirst()
        h.helpers.first().retire()
        assertEquals(SettingsError.NOT_READY, first.single().error)
        assertEquals(1, h.pending.size, "Retirement cannot free the physical response")
        task.action()
        assertEquals(1, h.pending.size, "Returning from the scan body is not worker exit")
        task.exited()
        assertEquals(2, h.pending.size)
        h.reply(1, 2)
        h.workers.finishOne()
        assertEquals(2L, id(second.single()))
        assertEquals(1, first.size)
        h.close()
    }

    @Test fun workerExitInsideCallbackStillWaitsForTheCallbackBodyToFinish() {
        val h = Harness(ordered = true)
        val first = h.start()
        val second = h.start()
        val observations = mutableListOf<Int>()
        h.workers.runInline = {
            observations += h.pending.size
            it.action()
            observations += h.pending.size
            it.exited()
            observations += h.pending.size
        }
        h.reply(0, 1)
        h.workers.runInline = null
        assertEquals(listOf(1, 1, 1), observations)
        // Dispatch may happen just before the application callback returns. The real ordered
        // receiver cannot construct a new receive batch until it has cleared that event slot.
        assertEquals(2, h.pending.size)
        h.reply(1, 2)
        h.workers.finishOne()
        assertEquals(1L, id(first.single()))
        assertEquals(2L, id(second.single()))
        h.close()
    }

    @Test fun unknownThrowAfterWorkerEnqueueKeepsAdmissionUntilThatWorkerActuallyExits() {
        val h = Harness(ordered = true)
        val first = h.start()
        val second = h.start()
        h.workers.throwAfterEnqueue = true
        h.reply(0, 1)
        assertEquals(SettingsError.REQUEST_FAILED, first.single().error)
        val task = h.workers.tasks.removeFirst()
        assertEquals(1, h.pending.size)
        task.action()
        assertEquals(1, h.pending.size)
        task.exited()
        assertEquals(2, h.pending.size)
        h.reply(1, 2)
        h.workers.finishOne()
        assertEquals(2L, id(second.single()))
        assertEquals(1, first.size)
        h.close()
    }

    @Test fun revokedQueuedOwnerIsRemovedWhileTheDispatchedOwnerKeepsItsPhysicalPermit() {
        val h = Harness(ordered = true)
        val first = h.start()
        val revoked = h.start()
        assertEquals(1, h.pending.size)
        h.helpers[1].retire()
        assertEquals(SettingsError.NOT_READY, revoked.single().error)
        val fresh = h.start()
        assertEquals(1, h.pending.size, "The dispatched response still owns the gate")
        h.reply(0, 1)
        val held = h.workers.tasks.removeFirst()
        held.action()
        assertEquals(1, h.pending.size)
        held.exited()
        assertEquals(2, h.pending.size, "Only the fresh waiting owner should be dispatched")
        h.reply(1, 3)
        h.workers.finishOne()
        assertEquals(1L, id(first.single()))
        assertEquals(3L, id(fresh.single()))
        assertEquals(1, revoked.size)
        h.close()
    }

    @Test fun cancelledCatalogRequestsStillOwnTheEightNativeReadSlotsUntilTheirRealCallbacks() {
        val pending = mutableListOf<(TdApi.Object) -> Unit>()
        val api = OfficialTdProfileStickerAvatar { command, reply, failed ->
            when (command) {
                is TdApi.GetMe -> reply(TdApi.User().apply { id = 43 })
                is TdApi.GetDefaultProfilePhotoCustomEmojiStickers -> pending += reply
                else -> failed()
            }
        }
        fun start(): MutableList<SettingsResult<ProfileStickerAvatarCandidatePage>> {
            val picker = assertNotNull(api.open(43) { true })
            return mutableListOf<SettingsResult<ProfileStickerAvatarCandidatePage>>().also { api.loadDefault(picker, it::add) }
        }
        val firstEight = List(8) { start() }
        assertEquals(8, pending.size)
        val ninth = start()
        assertEquals(SettingsError.NOT_READY, ninth.single().error)
        assertTrue(firstEight.all { it.single().error == SettingsError.NOT_READY })
        val source = TdApi.Stickers(Array(4097) { index -> TdApi.Sticker().apply {
            width = 64; height = 64; fullType = TdApi.StickerFullTypeCustomEmoji(index.toLong() + 1, false)
        } })
        pending.first()(source)
        start()
        assertEquals(9, pending.size, "The real callback frees exactly its one reservation")
        pending.first()(source) // duplicate completion cannot free the replacement request
        val saturatedAgain = start()
        assertEquals(SettingsError.NOT_READY, saturatedAgain.single().error)
        assertEquals(9, pending.size)
        pending.drop(1).forEach { it(source) }
        api.retire()
    }

    private class Harness(ordered: Boolean) {
        data class Request(val reply: (TdApi.Object) -> Unit)
        val workers = Workers()
        val pending = mutableListOf<Request>()
        val helpers = mutableListOf<OfficialTdProfileStickerAvatar>()
        private val ordered = ordered
        private fun send(command: TdApi.Function<*>, reply: (TdApi.Object) -> Unit, failed: () -> Unit) {
            when (command) {
                is TdApi.GetMe -> reply(TdApi.User().apply { id = 43 })
                is TdApi.GetDefaultProfilePhotoCustomEmojiStickers -> pending += Request(reply)
                else -> failed()
            }
        }
        fun start(): MutableList<SettingsResult<ProfileStickerAvatarCandidatePage>> {
            // The witness is opt-in in this controlled simulation, never inferred from the fake.
            val helper = if (ordered) OfficialTdProfileStickerAvatar(workers, ProfileStickerAvatarOrderedReceiver, ::send)
                else OfficialTdProfileStickerAvatar(workers, ::send)
            helpers += helper
            val picker = assertNotNull(helper.open(43) { true })
            return mutableListOf<SettingsResult<ProfileStickerAvatarCandidatePage>>().also { helper.loadDefault(picker, it::add) }
        }
        fun reply(index: Int, id: Long) {
            pending[index].reply(TdApi.Stickers(arrayOf(TdApi.Sticker().apply {
                width = 64; height = 64; fullType = TdApi.StickerFullTypeCustomEmoji(id, false)
            })))
        }
        fun close() = helpers.forEach { it.retire() }
    }

    private class Workers : ProfileStickerAvatarMediaAsync {
        data class Task(val action: () -> Unit, val exited: () -> Unit)
        val tasks = ArrayDeque<Task>()
        var runInline: ((Task) -> Unit)? = null
        var throwAfterEnqueue = false
        override fun reserveConsumer(): ProfileStickerAvatarMediaConsumerPermit? = null
        override fun dispatch(action: () -> Unit, exited: () -> Unit, rejected: () -> Unit) {
            val task = Task(action, exited)
            val inline = runInline
            if (inline != null) inline(task) else tasks.addLast(task)
            if (throwAfterEnqueue) { throwAfterEnqueue = false; error("unknown enqueue result") }
        }
        fun finishOne() = tasks.removeFirst().also { it.action(); it.exited() }
    }

    private companion object {
        fun id(result: SettingsResult<ProfileStickerAvatarCandidatePage>): Long =
            assertIs<ProfileStickerAvatarIdentity.CustomEmoji>(assertNotNull(result.value).candidates.single().identity).customEmojiId
    }
}
