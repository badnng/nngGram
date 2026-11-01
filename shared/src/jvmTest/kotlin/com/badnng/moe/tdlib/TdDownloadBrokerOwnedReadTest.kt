package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import org.drinkless.tdlib.TdApi
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

/** Synthetic transport + real broker; terminal queue is deliberately not reentrant. */
class TdDownloadBrokerOwnedReadTest {
    @Test fun queuedCancellationTerminatesButDispatchedPrefixDrainsOnlyOnItsOwnLateCallback() {
        val f = Fixture()
        val firstResults = mutableListOf<ChatResult<ByteArray>>()
        val queuedResults = mutableListOf<ChatResult<ByteArray>>()
        var firstTerminal = 0
        var queuedTerminal = 0
        f.broker.readOwnedIfActive(f.first, f.file, 0, 8, { true }, firstResults::add) { firstTerminal++ }
        repeat(2) {
            f.broker.readOwnedIfActive(f.second, f.file, 0, 8, { true }, {
                queuedResults.add(it)
                f.broker.release(f.second, f.file.id)
            }) { queuedTerminal++ }
        }
        assertEquals(1, f.native.prefixes.size)
        f.broker.release(f.second, f.file.id)
        f.flushTerminals()
        assertEquals(2, queuedTerminal)
        assertEquals(listOf<ChatError?>(ChatError.NOT_READY, ChatError.NOT_READY), queuedResults.map { it.error })
        assertEquals(0, firstTerminal)
        f.broker.release(f.first, f.file.id)
        f.broker.close()
        f.flushTerminals()
        assertEquals(0, firstTerminal, "Closing a consumer is not a native prefix completion.")
        val delayed = f.native.prefixes.removeFirst()
        delayed(ChatResult(8L))
        delayed(ChatResult(8L))
        f.flushTerminals()
        assertEquals(1, firstTerminal)
        assertEquals(ChatError.NOT_READY, firstResults.single().error)
        assertTrue(f.native.reads.isEmpty())
    }

    @Test fun lateReadBytesCannotBecomeTheNewOwnersResultAndEachTokenDrainsOnce() {
        val f = Fixture()
        val oldResults = mutableListOf<ChatResult<ByteArray>>()
        val newResults = mutableListOf<ChatResult<ByteArray>>()
        var oldTerminal = 0
        var newTerminal = 0
        f.broker.readOwnedIfActive(f.first, f.file, 0, 8, { true }, oldResults::add) { oldTerminal++ }
        f.native.prefixes.removeFirst()(ChatResult(8L))
        val oldRead = f.native.reads.removeFirst()
        f.broker.release(f.first, f.file.id)
        f.broker.readOwnedIfActive(f.second, f.file, 0, 8, { true }, newResults::add) { newTerminal++ }
        f.native.prefixes.removeFirst()(ChatResult(8L))
        val newRead = f.native.reads.removeFirst()
        oldRead(ChatResult(byteArrayOf(1)))
        newRead(ChatResult(byteArrayOf(2)))
        oldRead(ChatResult(byteArrayOf(3)))
        f.flushTerminals()
        assertEquals(ChatError.NOT_READY, oldResults.single().error)
        assertContentEquals(byteArrayOf(2), newResults.single().value)
        assertEquals(1, oldTerminal)
        assertEquals(1, newTerminal)

        val closed = Fixture()
        val closedResults = mutableListOf<ChatResult<ByteArray>>()
        var closedTerminal = 0
        closed.broker.readOwnedIfActive(closed.first, closed.file, 0, 8, { true }, closedResults::add) { closedTerminal++ }
        closed.native.prefixes.removeFirst()(ChatResult(8L))
        val pendingRead = closed.native.reads.removeFirst()
        closed.broker.close()
        closed.flushTerminals()
        assertEquals(0, closedTerminal)
        pendingRead(ChatResult(byteArrayOf(9)))
        closed.flushTerminals()
        assertEquals(ChatError.NOT_READY, closedResults.single().error)
        assertEquals(1, closedTerminal)

        val revokedBeforeDelivery = Fixture()
        val revokedResults = mutableListOf<ChatResult<ByteArray>>()
        var revokedTerminal = 0
        revokedBeforeDelivery.broker.readOwnedIfActive(revokedBeforeDelivery.first, revokedBeforeDelivery.file, 0, 8,
            { true }, revokedResults::add) { revokedTerminal++ }
        revokedBeforeDelivery.native.prefixes.removeFirst()(ChatResult(8L))
        revokedBeforeDelivery.native.reads.removeFirst()(ChatResult(byteArrayOf(6)))
        revokedBeforeDelivery.broker.release(revokedBeforeDelivery.first, revokedBeforeDelivery.file.id)
        revokedBeforeDelivery.flushTerminals()
        assertEquals(ChatError.NOT_READY, revokedResults.single().error)
        assertEquals(1, revokedTerminal)
    }

    @Test fun uncertainNativeThrowDoesNotCountAsPhysicalCompletionAndAdapterKeepsLateCallback() {
        val synchronous = Fixture()
        synchronous.native.synchronous = true
        val ready = mutableListOf<ChatResult<ByteArray>>()
        var synchronousTerminal = 0
        synchronous.broker.readOwnedIfActive(synchronous.first, synchronous.file, 0, 8, { true }, ready::add) {
            synchronousTerminal++
        }
        assertEquals(0, synchronousTerminal)
        synchronous.flushTerminals()
        assertContentEquals(byteArrayOf(7), ready.single().value)
        assertEquals(1, synchronousTerminal)

        val f = Fixture()
        f.native.throwAfterPrefixDispatch = true
        val results = mutableListOf<ChatResult<ByteArray>>()
        var terminals = 0
        f.broker.readOwnedIfActive(f.first, f.file, 0, 8, { true }, results::add) { terminals++ }
        f.flushTerminals()
        assertEquals(ChatError.REQUEST_FAILED, results.single().error)
        assertEquals(0, terminals)
        val callback = f.native.prefixes.removeFirst()
        callback(ChatResult(8L))
        callback(ChatResult(8L))
        f.flushTerminals()
        assertEquals(1, terminals)
        assertTrue(f.native.reads.isEmpty())

        val uncertainRead = Fixture()
        uncertainRead.native.throwAfterReadDispatch = true
        val readResults = mutableListOf<ChatResult<ByteArray>>()
        var readTerminals = 0
        uncertainRead.broker.readOwnedIfActive(uncertainRead.first, uncertainRead.file, 0, 8, { true }, readResults::add) {
            readTerminals++
        }
        uncertainRead.native.prefixes.removeFirst()(ChatResult(8L))
        uncertainRead.flushTerminals()
        assertEquals(ChatError.REQUEST_FAILED, readResults.single().error)
        assertEquals(0, readTerminals)
        uncertainRead.native.reads.removeFirst()(ChatResult(byteArrayOf(4)))
        uncertainRead.flushTerminals()
        assertEquals(1, readTerminals)
        assertEquals(1, readResults.size)

        var nativeReply: ((TdApi.Object) -> Unit)? = null
        val adapter = OfficialTdDownloadNative { _, reply, _ -> nativeReply = reply; error("unknown dispatch") }
        val prefixes = mutableListOf<ChatResult<Long>>()
        var uncertain = 0
        adapter.ownedPrefix(7, 0, prefixes::add) { uncertain++ }
        assertEquals(1, uncertain)
        assertTrue(prefixes.isEmpty())
        val actualPrefix = TdApi.FileDownloadedPrefixSize(8)
        assertNotNull(nativeReply).invoke(actualPrefix)
        assertNotNull(nativeReply).invoke(actualPrefix)
        assertEquals(8L, prefixes.single().value)

        val parts = mutableListOf<ChatResult<ByteArray>>()
        adapter.ownedRead(7, 0, 8, parts::add) { uncertain++ }
        assertEquals(2, uncertain)
        assertTrue(parts.isEmpty())
        assertNotNull(nativeReply).invoke(TdApi.Data(byteArrayOf(5)))
        assertContentEquals(byteArrayOf(5), parts.single().value)
    }

    @Test fun successfulAndRejectedCompletedCallbacksPermitAnotherThreadToReenterBroker() {
        for (accepted in listOf(true, false)) {
            val f = Fixture()
            f.native.synchronous = true
            val secondThreadDone = CountDownLatch(1)
            val reenteredWhileCallbackWaited = AtomicBoolean()
            val order = mutableListOf<String>()
            f.broker.readOwnedIfActive(f.first, f.file, 0, if (accepted) 8 else 0, { true }, {
                order += "completed"
                Thread({ f.broker.manages(f.file.id); secondThreadDone.countDown() }, "owned-read-reentry").apply {
                    isDaemon = true
                    start()
                }
                reenteredWhileCallbackWaited.set(secondThreadDone.await(2, TimeUnit.SECONDS))
            }) { order += "terminal" }
            f.flushTerminals()
            assertTrue(reenteredWhileCallbackWaited.get(), "The broker monitor was held across a foreign callback")
            assertEquals(listOf("completed", "terminal"), order)
        }
    }

    @Test fun ownedNativeReadRejectsInvalidCountsBeforeDispatchInAdapterAndDefaultBridge() {
        val sent = mutableListOf<TdApi.Function<*>>()
        val adapter = OfficialTdDownloadNative { command, reply, _ ->
            sent += command
            reply(TdApi.Data(byteArrayOf(1)))
        }
        val bridge = Native()
        for (count in listOf(0, -1, 65_537, Int.MAX_VALUE)) {
            val adapterResults = mutableListOf<ChatResult<ByteArray>>()
            var adapterUncertain = 0
            adapter.ownedRead(7, 0, count, adapterResults::add) { adapterUncertain++ }
            assertEquals(ChatError.REQUEST_FAILED, adapterResults.single().error)
            assertEquals(0, adapterUncertain)
            val bridgeResults = mutableListOf<ChatResult<ByteArray>>()
            var bridgeUncertain = 0
            bridge.ownedRead(7, 0, count, bridgeResults::add) { bridgeUncertain++ }
            assertEquals(ChatError.REQUEST_FAILED, bridgeResults.single().error)
            assertEquals(0, bridgeUncertain)
        }
        assertTrue(sent.isEmpty())
        assertTrue(bridge.reads.isEmpty())
        assertTrue(bridge.readCounts.isEmpty())
        for (count in listOf(1, 65_536)) {
            val adapterResults = mutableListOf<ChatResult<ByteArray>>()
            adapter.ownedRead(7, 0, count, adapterResults::add) { fail("Valid count reported uncertainty") }
            assertContentEquals(byteArrayOf(1), adapterResults.single().value)
            assertEquals(count.toLong(), assertIs<TdApi.ReadFilePart>(sent.last()).count)
            val bridgeResults = mutableListOf<ChatResult<ByteArray>>()
            bridge.ownedRead(7, 0, count, bridgeResults::add) { fail("Valid count reported uncertainty") }
            bridge.reads.removeFirst()(ChatResult(byteArrayOf(2)))
            assertContentEquals(byteArrayOf(2), bridgeResults.single().value)
            assertEquals(count, bridge.readCounts.last())
        }
    }

    private class Fixture {
        val native = Native()
        val terminals = ArrayDeque<() -> Unit>()
        val broker = TdDownloadBroker(native, { _, _ -> }, { 0L }, { terminals.addLast(it) })
        val file = MediaFile(7, size = 64, downloaded = true, uniqueId = "synthetic")
        val first = MediaDownloadOwner(ChatToken(1, 1, 10), 5, MediaDownloadPurpose.PROFILE_EMOJI, 1)
        val second = first.copy(leaseId = 2)
        fun flushTerminals() { while (terminals.isNotEmpty()) terminals.removeFirst().invoke() }
    }

    private class Native : DownloadBrokerNative {
        val prefixes = ArrayDeque<(ChatResult<Long>) -> Unit>()
        val reads = ArrayDeque<(ChatResult<ByteArray>) -> Unit>()
        val readCounts = mutableListOf<Int>()
        var throwAfterPrefixDispatch = false
        var throwAfterReadDispatch = false
        var synchronous = false
        override fun prefix(fileId: Int, offset: Long, completed: (ChatResult<Long>) -> Unit) {
            if (synchronous) { completed(ChatResult(8L)); completed(ChatResult(8L)); return }
            prefixes.addLast(completed)
            if (throwAfterPrefixDispatch) error("unknown dispatch")
        }
        override fun read(fileId: Int, offset: Long, count: Int, completed: (ChatResult<ByteArray>) -> Unit) {
            readCounts += count
            if (synchronous) { completed(ChatResult(byteArrayOf(7))); completed(ChatResult(byteArrayOf(7))) }
            else {
                reads.addLast(completed)
                if (throwAfterReadDispatch) error("unknown read dispatch")
            }
        }
        override fun download(fileId: Int, priority: Int, offset: Long, limit: Long, completed: (ChatResult<MediaFile>) -> Unit) =
            error("Downloaded synthetic file needs no native download")
        override fun refresh(fileId: Int, completed: (ChatResult<MediaFile>) -> Unit) = error("Unexpected refresh")
        override fun cancel(fileId: Int, completed: (ChatResult<Unit>) -> Unit) { completed(ChatResult(Unit)) }
    }
}
