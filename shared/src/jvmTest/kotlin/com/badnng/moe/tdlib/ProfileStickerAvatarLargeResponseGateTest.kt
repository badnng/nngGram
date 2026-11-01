package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import kotlin.test.*

class ProfileStickerAvatarLargeResponseGateTest {
    @Test fun canceledUndispatchedWaiterFreesQueueCapacityWithoutReleasingPhysicalPermit() {
        val gate = ProfileStickerAvatarLargeResponseGate(maxWaiting = 1)
        val started = mutableListOf<String>()
        val permits = mutableListOf<ProfileStickerAvatarLargeResponseGate.Permit>()
        val physical = assertNotNull(gate.enqueue(false) { started += "physical"; permits += it })
        val stale = assertNotNull(gate.enqueue(false) { started += "stale"; permits += it })
        assertNull(gate.enqueue(false) { error("The waiting queue is full") })
        assertTrue(stale.cancel()); assertFalse(stale.cancel())
        assertNotNull(gate.enqueue(false) { started += "fresh"; permits += it })
        assertFalse(physical.cancel(), "A dispatched permit is never cancelled as an empty queue entry")
        assertEquals(listOf("physical"), started)
        permits.single().complete()
        assertEquals(listOf("physical", "fresh"), started)
        permits.last().complete()
    }

    @Test fun bothExitSignalsAreRequiredAndTheQueueIsBoundedAndFair() {
        val gate = ProfileStickerAvatarLargeResponseGate(maxWaiting = 2)
        val order = mutableListOf<String>()
        val permits = mutableListOf<ProfileStickerAvatarLargeResponseGate.Permit>()
        fun offer(label: String, preferred: Boolean) = gate.submit(preferred) { permit -> order += label; permits += permit }
        assertTrue(offer("a", true)); assertTrue(offer("b", true)); assertTrue(offer("c", false))
        assertFalse(offer("over-budget", true))
        assertEquals(listOf("a"), order)
        val exit = ProfileStickerAvatarLargeResponseExit(permits[0])
        exit.workerExited(); assertEquals(listOf("a"), order)
        exit.callbackBodyDone(); assertEquals(listOf("a", "b"), order)
        exit.callbackBodyDone(); exit.workerExited(); assertEquals(2, order.size)
        permits[1].complete(); assertEquals(listOf("a", "b", "c"), order)
        permits[2].complete()

        val fairness = ProfileStickerAvatarLargeResponseGate(maxWaiting = 8)
        val started = mutableListOf<String>()
        val received = mutableListOf<ProfileStickerAvatarLargeResponseGate.Permit>()
        fun submit(label: String, high: Boolean) = fairness.submit(high) { started += label; received += it }
        assertTrue(submit("p0", true)); assertTrue(submit("low", false))
        repeat(4) { assertTrue(submit("p${it + 1}", true)) }
        received[0].complete(); received[1].complete(); received[2].complete()
        assertEquals(listOf("p0", "p1", "p2", "low"), started)
        var index = 3
        while (index < received.size) { received[index].complete(); index++ }
    }

    @Test fun sequentialFakeReceiverAndWorkerExitPreventTwoBuilderResponsesBeingAdmitted() {
        data class Request(val command: TdApi.Function<*>, val reply: (TdApi.Object) -> Unit, val failed: () -> Unit)
        data class Task(val action: () -> Unit, val exited: () -> Unit)
        val requests = mutableListOf<Request>()
        val tasks = ArrayDeque<Task>()
        val async = object : ProfileStickerAvatarMediaAsync {
            override fun reserveConsumer(): ProfileStickerAvatarMediaConsumerPermit? = null
            override fun dispatch(action: () -> Unit, exited: () -> Unit, rejected: () -> Unit) {
                tasks.addLast(Task(action, exited))
            }
        }
        val api = OfficialTdProfileStickerAvatar(async, ProfileStickerAvatarOrderedReceiver) { command, reply, failed ->
            requests += Request(command, reply, failed)
        }
        val owner = assertNotNull(api.open(43) { true })
        val first = mutableListOf<SettingsResult<ProfileStickerAvatarCandidatePage>>()
        api.loadDefault(owner, first::add)
        requests.last().reply(TdApi.User().apply { id = 43 })
        val firstNative = requests.last()
        assertIs<TdApi.GetDefaultProfilePhotoCustomEmojiStickers>(firstNative.command)

        // A replacement owner retires the old consumer, but cannot release the physical permit.
        val secondOwner = assertNotNull(api.open(43) { true })
        val second = mutableListOf<SettingsResult<ProfileStickerAvatarCandidatePage>>()
        api.loadDefault(secondOwner, second::add)
        requests.last().reply(TdApi.User().apply { id = 43 })
        assertEquals(1, requests.count { it.command is TdApi.GetDefaultProfilePhotoCustomEmojiStickers })
        firstNative.reply(TdApi.Stickers(arrayOf(sticker(1))))
        val task = tasks.removeFirst()
        task.action()
        assertEquals(1, requests.count { it.command is TdApi.GetDefaultProfilePhotoCustomEmojiStickers },
            "Processing its payload is not the worker's physical exit.")
        task.exited()
        assertEquals(2, requests.count { it.command is TdApi.GetDefaultProfilePhotoCustomEmojiStickers })
        val secondNative = requests.last()
        secondNative.reply(TdApi.Stickers(arrayOf(sticker(2))))
        tasks.removeFirst().also { it.action(); it.exited() }
        assertEquals(SettingsError.NOT_READY, first.single().error)
        assertEquals(2L, assertIs<ProfileStickerAvatarIdentity.CustomEmoji>(second.single().value!!.candidates.single().identity).customEmojiId)
        firstNative.reply(TdApi.Stickers(arrayOf(sticker(3))))
        assertEquals(1, second.size)
        api.retire()
    }

    private fun sticker(id: Long) = TdApi.Sticker().apply {
        this.id = id; width = 64; height = 64; fullType = TdApi.StickerFullTypeCustomEmoji(id, false)
    }
}
