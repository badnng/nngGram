package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import kotlin.test.*

class TdDownloadBrokerTest {
    @Test fun autoCannotRestartTheCurrentStreamOrResetItsNoProgressStall() {
        val f = Fixture()
        val stream = f.owner(MediaDownloadPurpose.STREAM)
        f.read(stream, 9_000_000)
        f.native.commands.single().accept(ChatResult(f.file))
        f.broker.full(f.owner(MediaDownloadPurpose.AUTO), f.file)
        f.advance(14_900)
        assertEquals(1, f.native.commands.size)
        assertTrue(f.native.cancelled.isEmpty(), "AUTO cannot preempt or republish the same current stream window")
        f.advance(100)
        assertEquals(DownloadLeaseState.FAILED, f.broker.state(stream, 7))
        assertEquals(1, f.native.cancelled.size)
        assertEquals(2, f.native.commands.size, "AUTO may proceed after the original stream really stalls")
        assertEquals(1, f.native.commands.last().priority)
    }

    @Test fun compatibleFullOwnersShareOneWindowWithoutRepeatedNativeCommands() {
        val f = Fixture()
        var userCompleted = 0
        var artworkCompleted = 0
        f.broker.full(f.owner(MediaDownloadPurpose.USER), f.file) { userCompleted++; assertTrue(it.value?.downloaded == true) }
        f.native.commands.single().accept(ChatResult(f.file))
        f.broker.full(f.owner(MediaDownloadPurpose.ARTWORK), f.file) { artworkCompleted++; assertTrue(it.value?.downloaded == true) }
        f.advance(14_900)
        assertEquals(1, f.native.commands.size)
        assertTrue(f.native.cancelled.isEmpty(), "Two FULL owners need the same cache rather than successive copies of the same native range")
        f.broker.observe(f.file.copy(downloaded = true))
        assertEquals(1, userCompleted)
        assertEquals(1, artworkCompleted)
    }

    @Test fun aDifferentUserFullGapStillGetsItsTwoSecondOpportunityAndThenReturnsToStream() {
        val f = Fixture()
        f.read(f.owner(MediaDownloadPurpose.STREAM), 9_000_000)
        f.native.commands.single().accept(ChatResult(f.file))
        f.broker.full(f.owner(MediaDownloadPurpose.USER), f.file)
        f.advance(1000)
        assertEquals(1, f.native.commands.size, "The first second cannot cancel only to reselect the same stream")
        f.advance(1000)
        assertEquals(2, f.native.commands.size)
        assertEquals(0L, f.native.commands.last().offset)
        assertEquals(TdDownloadBroker.FULL_WINDOW, f.native.commands.last().limit)
        assertEquals(16, f.native.commands.last().priority)
        f.native.commands.last().accept(ChatResult(f.file))
        f.advance(1000)
        assertEquals(3, f.native.commands.size)
        assertEquals(9_000_000L, f.native.commands.last().offset)
        assertEquals(32, f.native.commands.last().priority)
    }

    @Test fun equivalentStreamOwnersCannotHideADifferentWaitingRange() {
        val f = Fixture()
        val first = f.owner(MediaDownloadPurpose.STREAM)
        f.read(first, 0)
        f.read(first.copy(leaseId = 2), 0)
        f.read(first.copy(leaseId = 3), 9_000_000)
        f.native.commands.single().accept(ChatResult(f.file))
        f.advance(1000)
        assertEquals(listOf(0L, 9_000_000L), f.native.commands.map { it.offset }, "The whole equivalent turn must yield, rather than switching A@0 to B@0")
        assertEquals(1, f.native.cancelled.size)
    }

    @Test fun releasingTheLastStreamStillAllowsItsIndependentAutoLeaseToContinue() {
        val f = Fixture()
        val stream = f.owner(MediaDownloadPurpose.STREAM)
        f.read(stream, 9_000_000)
        f.native.commands.single().accept(ChatResult(f.file))
        f.broker.full(f.owner(MediaDownloadPurpose.AUTO), f.file)
        f.broker.release(stream, 7)
        assertEquals(2, f.native.commands.size)
        assertEquals(0L, f.native.commands.last().offset)
        assertEquals(TdDownloadBroker.FULL_WINDOW, f.native.commands.last().limit)
        assertEquals(1, f.native.commands.last().priority)
    }

    @Test fun releasedAndReacquiredOwnerCannotInheritAnOldPrefixCallback() {
        val f = Fixture()
        val owner = f.owner(MediaDownloadPurpose.STREAM)
        f.native.deferPrefix = true
        var old: ChatResult<ByteArray>? = null
        f.broker.read(owner, f.file, 0, 64) { old = it }
        f.broker.release(owner, 7)
        f.read(owner, 9_000_000)
        f.native.completePrefix(64)
        assertEquals(ChatError.NOT_READY, old?.error)
        assertTrue(f.native.reads.isEmpty())
    }

    @Test fun queuedFilesContinueWhenAnAdmittedFilesPrefixFailsSynchronously() {
        val f = Fixture()
        f.native.deferPrefix = true
        for (id in 1..5) f.broker.full(f.owner(MediaDownloadPurpose.USER, id.toLong()), f.file.copy(id = id))
        assertEquals(4, f.native.pendingPrefixes.size)
        f.native.pendingPrefixes.removeFirst()(ChatResult(error = ChatError.REQUEST_FAILED))
        assertEquals(4, f.native.pendingPrefixes.size, "File 5 must occupy the released preparation slot")
    }

    @Test fun fullPrefixRefreshFailureIsATerminalNativeError() {
        val f = Fixture()
        f.native.prefix = { _, _ -> f.file.size }
        f.native.onRefresh = { _, result -> result(ChatResult(error = ChatError.REQUEST_FAILED)) }
        var result: ChatResult<MediaFile>? = null
        f.broker.full(f.owner(MediaDownloadPurpose.USER), f.file) { result = it }
        assertEquals(ChatError.REQUEST_FAILED, result?.error)
        assertTrue(f.native.commands.isEmpty())
    }

    @Test fun synchronousAcceptancePrefixCancellationAndCompletionReturnWithoutRecursion() {
        val f = Fixture()
        val file = f.file.copy(size = TdDownloadBroker.FULL_WINDOW * 3)
        var readyOffset = -1L
        var readyLength = 0L
        f.native.prefix = { _, offset -> if (offset == readyOffset) readyLength else 0 }
        f.native.onDownload = { command ->
            readyOffset = command.offset; readyLength = command.limit
            command.accept(ChatResult(file))
        }
        f.native.onRefresh = { _, result -> result(ChatResult(file.copy(downloaded = true))) }
        var completed = 0
        f.broker.full(f.owner(MediaDownloadPurpose.USER), file) { completed++; assertTrue(it.value?.downloaded == true) }
        assertEquals(1, completed)
        assertEquals(3, f.native.commands.size)
        assertTrue(f.native.commands.all { it.limit == TdDownloadBroker.FULL_WINDOW })
    }

    @Test fun aLaterStreamFileCannotJumpAnEarlierQueuedFullFile() {
        val f = Fixture()
        for (id in 1..5) f.broker.full(f.owner(MediaDownloadPurpose.USER, id.toLong()), f.file.copy(id = id))
        f.broker.read(f.owner(MediaDownloadPurpose.STREAM), f.file.copy(id = 6), 0, 64) {}
        f.native.commands.first().accept(ChatResult(f.file.copy(id = 1)))
        f.native.prefix = { id, offset -> if (id == 1 && offset == 0L) TdDownloadBroker.FULL_WINDOW else 0 }
        f.advance(100)
        assertEquals(5, f.native.commands.last().id)
    }

    @Test fun asynchronousAcceptanceIsABarrierForNewSeekAndRetirement() {
        val f = Fixture()
        val owner = f.owner(MediaDownloadPurpose.STREAM)
        f.read(owner, 0)
        val old = f.native.commands.single()
        f.read(owner, 9_000_000)
        f.broker.release(owner, 7)
        assertEquals(1, f.native.commands.size)
        assertEquals(0, f.native.cancelled.size)
        old.accept(ChatResult(f.file))
        assertEquals(listOf(7), f.native.cancelled)
        f.advance(1000)
        assertEquals(1, f.native.commands.size)
    }

    @Test fun lateAcceptanceCannotRestoreAnEarlierSeek() {
        val f = Fixture()
        val owner = f.owner(MediaDownloadPurpose.STREAM)
        f.read(owner, 0)
        f.read(owner, 9_000_000)
        f.native.commands.first().accept(ChatResult(f.file))
        assertEquals(listOf(0L, 9_000_000L), f.native.commands.map { it.offset })
        assertTrue(f.native.commands.all { it.limit in 1..TdDownloadBroker.STREAM_WINDOW })
    }

    @Test fun aPrefixFromAnEarlierSeekCannotAuthorizeARead() {
        val f = Fixture()
        val owner = f.owner(MediaDownloadPurpose.STREAM)
        f.native.deferPrefix = true
        var first: ChatResult<ByteArray>? = null
        var second: ChatResult<ByteArray>? = null
        f.broker.read(owner, f.file, 0, 64) { first = it }
        f.broker.read(owner, f.file, 9_000_000, 64) { second = it }
        assertEquals(1, f.native.pendingPrefixes.size)
        f.native.completePrefix(64)
        assertEquals(ChatError.NOT_READY, first?.error)
        assertTrue(f.native.reads.isEmpty())
        f.native.completePrefix(16)
        assertEquals(16, second?.value?.size)
        assertEquals(listOf(9_000_000L), f.native.reads)
        assertEquals(1, f.native.maxPrefixes)
    }

    @Test fun oneOwnerReleaseDoesNotCancelAnotherOwnersFullDownload() {
        val f = Fixture()
        val user = f.owner(MediaDownloadPurpose.USER)
        val auto = f.owner(MediaDownloadPurpose.AUTO)
        var completed = 0
        f.broker.full(user, f.file) { completed++ }
        f.broker.full(auto, f.file)
        f.broker.release(auto, 7)
        assertTrue(f.native.cancelled.isEmpty())
        f.native.commands.single().accept(ChatResult(f.file))
        f.broker.observe(f.file.copy(downloaded = true, localPath = "C:/synthetic/complete"))
        assertEquals(1, completed)
        f.broker.observe(f.file.copy(downloaded = true))
        assertEquals(1, completed)
    }

    @Test fun fullDownloadStartsAtTheConfirmedContiguousPrefixAndNeedsNativeCompletion() {
        val f = Fixture()
        f.native.prefix = { _, offset -> if (offset == 0L) 1234 else 0 }
        var result: ChatResult<MediaFile>? = null
        f.broker.full(f.owner(MediaDownloadPurpose.USER), f.file) { result = it }
        assertEquals(1234L, f.native.commands.single().offset)
        assertEquals(TdDownloadBroker.FULL_WINDOW, f.native.commands.single().limit)
        f.native.commands.single().accept(ChatResult(f.file.copy(downloading = false)))
        assertNull(result)
        f.broker.observe(f.file.copy(downloaded = true, localPath = "C:/synthetic/complete"))
        assertTrue(assertNotNull(result?.value).downloaded)
    }

    @Test fun theFifthFileGetsTheNextCompletedFiniteQuantum() {
        val f = Fixture()
        for (id in 1..5) f.broker.full(f.owner(MediaDownloadPurpose.USER, id.toLong()), f.file.copy(id = id))
        assertEquals(listOf(1, 2, 3, 4), f.native.commands.map { it.id })
        f.native.commands.first().accept(ChatResult(f.file.copy(id = 1)))
        f.native.prefix = { id, offset -> if (id == 1 && offset == 0L) TdDownloadBroker.FULL_WINDOW else 0 }
        f.advance(100)
        assertEquals(5, f.native.commands.last().id)
        assertTrue(f.native.commands.take(4).sumOf { it.limit } <= 8L * 1024 * 1024)
    }

    @Test fun probeWindowsRemainFiniteWithoutACumulativeBudgetOrTotalDeadline() {
        val f = Fixture()
        val owner = f.owner(MediaDownloadPurpose.PROBE)
        for (part in 0..40) {
            val offset = part * TdDownloadBroker.PROBE_WINDOW
            f.native.prefix = { _, _ -> 0 }
            f.read(owner, offset)
            val command = f.native.commands.last()
            assertEquals(offset, command.offset)
            assertEquals(TdDownloadBroker.PROBE_WINDOW, command.limit)
            assertEquals(24, command.priority)
            command.accept(ChatResult(f.file))
            f.native.prefix = { _, from -> if (from == offset) command.limit else 0 }
            f.advance(900)
        }
        assertTrue(f.time > 30_000)
        assertTrue(f.native.commands.sumOf { it.limit } > 8L * 1024 * 1024)
        assertTrue(f.native.commands.none { it.limit == 0L })
    }

    @Test fun aNativeCommandErrorFailsOnlyItsCurrentOwner() {
        val f = Fixture()
        var user: ChatResult<MediaFile>? = null
        var auto: ChatResult<MediaFile>? = null
        f.broker.full(f.owner(MediaDownloadPurpose.USER), f.file) { user = it }
        f.broker.full(f.owner(MediaDownloadPurpose.AUTO), f.file) { auto = it }
        f.native.commands.first().accept(ChatResult(error = ChatError.FORBIDDEN))
        assertEquals(ChatError.FORBIDDEN, user?.error)
        assertNull(auto)
        assertEquals(2, f.native.commands.size)
    }

    @Test fun aFailedCancellationKeepsItsNativeLaneCharged() {
        val f = Fixture()
        f.native.cancelResult = ChatResult(error = ChatError.REQUEST_FAILED)
        val owner = f.owner(MediaDownloadPurpose.USER)
        f.broker.full(owner, f.file)
        f.native.commands.single().accept(ChatResult(f.file))
        f.broker.release(owner, 7)
        for (id in 1..4) f.broker.full(f.owner(MediaDownloadPurpose.USER, id.toLong()), f.file.copy(id = id))
        assertEquals(4, f.native.commands.size)
    }

    @Test fun unknownSizeUsesFiniteWindowsAndOnlyNativeCompletedReadReportsEof() {
        val f = Fixture()
        val owner = f.owner(MediaDownloadPurpose.STREAM)
        val unknown = f.file.copy(size = 0, expectedSize = 3)
        f.broker.read(owner, unknown, 100, 64) { assertEquals(ChatError.NOT_READY, it.error) }
        assertEquals(TdDownloadBroker.STREAM_WINDOW, f.native.commands.single().limit)
        var eof: ChatResult<ByteArray>? = null
        f.broker.read(owner, f.file.copy(size = 100, downloaded = true), 100, 64) { eof = it }
        assertContentEquals(ByteArray(0), eof?.value)
    }

    @Test fun generationCloseCannotReviveAWaitingNativeCommand() {
        val f = Fixture()
        f.read(f.owner(MediaDownloadPurpose.STREAM), 0)
        f.broker.close()
        f.native.commands.single().accept(ChatResult(f.file))
        f.advance(1000)
        assertEquals(1, f.native.commands.size)
        assertTrue(f.native.cancelled.isEmpty())
    }

    @Test fun boundedSoundWindowsNeverAdvancePastAnUnknownSizeBudgetOrCallItComplete() {
        val f = Fixture(); val unknown = f.file.copy(size = 0, expectedSize = 0)
        val results = mutableListOf<ChatResult<MediaFile>>()
        f.native.onRefresh = { _, callback -> callback(ChatResult(unknown)) }
        f.broker.boundedFull(f.owner(MediaDownloadPurpose.SOUND).copy(messageId = 0), unknown, 2048, completed = results::add)
        assertEquals(2048L, f.native.commands.single().limit)
        f.native.commands.single().accept(ChatResult(unknown)); f.native.prefix = { _, _ -> 2048 }
        f.advance(16_000)
        assertEquals(1, f.native.commands.size); assertNotNull(results.single().error)
        assertTrue(f.native.commands.all { it.offset + it.limit <= 2048 })
    }

    @Test fun retiringOneCompatibleFullOwnerDoesNotCancelTheOtherOwnersSharedWindow() {
        val f = Fixture(); val chat = f.owner(MediaDownloadPurpose.USER)
        val sound = f.owner(MediaDownloadPurpose.SOUND).copy(messageId = 0, leaseId = 2)
        val file = f.file.copy(size = 1024)
        f.broker.full(chat, file); f.native.commands.single().accept(ChatResult(file))
        val results = mutableListOf<ChatResult<MediaFile>>()
        f.broker.boundedFull(sound, file, 2048, completed = results::add)
        f.broker.release(chat, 7)
        assertTrue(f.native.cancelled.isEmpty()); assertEquals(1, f.native.commands.size)
        f.broker.observe(file.copy(downloaded = true))
        assertTrue(results.single().value?.downloaded == true)
    }

    @Test fun revokedRegistrationGuardStopsThePrefixContinuationBeforeAnyNativeDownload() {
        val f = Fixture(); f.native.deferPrefix = true; var active = true
        val results = mutableListOf<ChatResult<MediaFile>>()
        f.broker.fullIfActive(f.owner(MediaDownloadPurpose.USER), f.file, { active }, results::add)
        active = false; f.native.completePrefix(0)
        assertEquals(ChatError.NOT_READY, results.single().error)
        assertTrue(f.native.commands.isEmpty())
    }

    @Test fun anOldFailureCleanupCannotRemoveANewLeaseLaneCreatedByItsTerminalCallback() {
        val f = Fixture(); val old = f.owner(MediaDownloadPurpose.USER)
        val fresh = old.copy(leaseId = 2); val results = mutableListOf<ChatResult<MediaFile>>()
        f.broker.full(old, f.file) {
            f.broker.release(old, 7)
            f.broker.full(fresh, f.file, results::add)
        }
        f.native.commands.first().accept(ChatResult(error = ChatError.REQUEST_FAILED))
        assertEquals(2, f.native.commands.size); assertTrue(f.broker.manages(7))
        f.native.commands.last().accept(ChatResult(f.file))
        f.broker.observe(f.file.copy(downloaded = true))
        assertTrue(results.single().value?.downloaded == true)
    }

    @Test fun boundedSettingsPurposesKeepIndependentCapsAndRejectOrdinaryOwners() {
        val ready = MediaFile(7, size = 17L * 1024 * 1024, downloaded = true)
        for (purpose in MediaDownloadPurpose.entries) {
            val f = Fixture(); val results = mutableListOf<ChatResult<MediaFile>>()
            f.broker.boundedFull(f.owner(purpose), ready, 24 * 1024 * 1024, completed = results::add)
            if (purpose in setOf(MediaDownloadPurpose.PROFILE_EMOJI, MediaDownloadPurpose.PROFILE_PHOTO_HISTORY)) assertEquals(ready, results.single().value)
            else assertNotNull(results.single().error)
            assertTrue(f.native.commands.isEmpty())
        }
        val f = Fixture(); val results = mutableListOf<ChatResult<MediaFile>>()
        f.broker.boundedFull(f.owner(MediaDownloadPurpose.SOUND), ready.copy(size = 16L * 1024 * 1024),
            16 * 1024 * 1024, completed = results::add)
        assertNotNull(results.single().value)
        for (limit in listOf(0, 24 * 1024 * 1024 + 1)) {
            f.broker.boundedFull(f.owner(MediaDownloadPurpose.PROFILE_EMOJI), ready, limit, completed = results::add)
            assertNotNull(results.last().error)
        }
    }

    @Test fun aCancelledProfileOwnerCannotCancelSoundAndArtworkSharingItsFullWindow() {
        val f = Fixture(); val file = f.file.copy(size = 1024)
        val profile = f.owner(MediaDownloadPurpose.PROFILE_EMOJI).copy(messageId = 0, leaseId = 2)
        val sound = f.owner(MediaDownloadPurpose.SOUND).copy(messageId = 0, leaseId = 3)
        val artwork = f.owner(MediaDownloadPurpose.ARTWORK).copy(leaseId = 4)
        val profileResults = mutableListOf<ChatResult<MediaFile>>()
        val soundResults = mutableListOf<ChatResult<MediaFile>>()
        val artworkResults = mutableListOf<ChatResult<MediaFile>>()
        f.broker.boundedFull(profile, file, 2048, completed = profileResults::add)
        f.native.commands.single().accept(ChatResult(file))
        f.broker.boundedFull(sound, file, 2048, completed = soundResults::add)
        f.broker.full(artwork, file, artworkResults::add)
        f.broker.release(profile, file.id)
        assertTrue(f.native.cancelled.isEmpty()); assertEquals(1, f.native.commands.size)
        f.broker.observe(file.copy(downloaded = true))
        assertTrue(profileResults.isEmpty())
        assertTrue(soundResults.single().value?.downloaded == true)
        assertTrue(artworkResults.single().value?.downloaded == true)
    }

    @Test fun unknownProfileSizeHasItsOwnBoundAndNeverFinishesFromPrefixEvidenceAlone() {
        val f = Fixture(); val unknown = f.file.copy(size = 0, expectedSize = Long.MAX_VALUE)
        val results = mutableListOf<ChatResult<MediaFile>>()
        f.native.onRefresh = { _, completed -> completed(ChatResult(unknown)) }
        f.broker.boundedFull(f.owner(MediaDownloadPurpose.PROFILE_EMOJI), unknown, 4096, completed = results::add)
        assertEquals(4096L, f.native.commands.single().limit)
        f.native.commands.single().accept(ChatResult(unknown)); f.native.prefix = { _, _ -> 4096 }
        f.advance(16_000)
        assertNotNull(results.single().error)
        assertTrue(f.native.commands.all { it.offset + it.limit <= 4096 })
    }

    @Test fun revokedProfilePredicateRejectsALatePrefixWithoutAcquiringNativeWork() {
        val f = Fixture(); f.native.deferPrefix = true; var active = true
        val results = mutableListOf<ChatResult<MediaFile>>()
        f.broker.boundedFull(f.owner(MediaDownloadPurpose.PROFILE_EMOJI), f.file.copy(size = 1024), 2048,
            { active }, results::add)
        active = false; f.native.completePrefix(0)
        assertEquals(ChatError.NOT_READY, results.single().error)
        assertTrue(f.native.commands.isEmpty())
    }

    @Test fun profilePhotoHistoryUsesItsOwnBoundedPurposeAndCannotExceedTwentyFourMiB() {
        val f = Fixture(); val owner = f.owner(MediaDownloadPurpose.PROFILE_PHOTO_HISTORY)
        val results = mutableListOf<ChatResult<MediaFile>>()
        val unknown = f.file.copy(size = 0)
        f.broker.boundedFull(owner, unknown, 4096, completed = results::add)
        assertEquals(4096L, f.native.commands.single().limit)
        f.native.commands.single().accept(ChatResult(unknown))
        assertTrue(results.isEmpty())
        f.broker.observe(unknown.copy(size = 4097, downloaded = true))
        assertNotNull(results.single().error)
        f.broker.boundedFull(owner.copy(leaseId = 2), unknown, 24 * 1024 * 1024 + 1, completed = results::add)
        assertNotNull(results.last().error)
        assertEquals(1, f.native.commands.size)
    }

    @Test fun closingHistoryDemandLeavesExistingArtworkAndSoundOwnersOnTheSharedLane() {
        val f = Fixture(); val file = f.file.copy(size = 1024)
        val history = f.owner(MediaDownloadPurpose.PROFILE_PHOTO_HISTORY)
        val artwork = f.owner(MediaDownloadPurpose.ARTWORK).copy(leaseId = 2)
        val sound = f.owner(MediaDownloadPurpose.SOUND).copy(leaseId = 3)
        val completed = mutableListOf<ChatResult<MediaFile>>()
        f.broker.boundedFull(history, file, 2048) {}
        f.native.commands.single().accept(ChatResult(file))
        f.broker.full(artwork, file, completed::add)
        f.broker.boundedFull(sound, file, 2048, completed = completed::add)
        f.broker.release(history, file.id)
        assertTrue(f.native.cancelled.isEmpty()); assertEquals(1, f.native.commands.size)
        f.broker.observe(file.copy(downloaded = true))
        assertEquals(2, completed.size); assertTrue(completed.all { it.value?.downloaded == true })
    }

    private class Fixture {
        var time = 0L
        private val timers = mutableListOf<Pair<Long, () -> Unit>>()
        val file = MediaFile(7, size = 100_000_000, canDownload = true)
        val native = Native()
        val broker = TdDownloadBroker(native, { delay, task -> timers += time + delay to task }, { time })
        fun owner(purpose: MediaDownloadPurpose, message: Long = 10) = MediaDownloadOwner(ChatToken(1, 2, 3), message, purpose, 1)
        fun read(owner: MediaDownloadOwner, offset: Long) = broker.read(owner, file, offset, 64) { }
        fun advance(delta: Long) {
            val end = time + delta
            repeat(10_000) {
                val next = timers.filter { it.first <= end }.minByOrNull { it.first } ?: run { time = end; return }
                timers.remove(next); time = next.first; next.second()
            }
            error("Unbounded broker scheduling")
        }
    }
    private class Native : DownloadBrokerNative {
        data class Command(val id: Int, val priority: Int, val offset: Long, val limit: Long, val accept: (ChatResult<MediaFile>) -> Unit)
        val commands = mutableListOf<Command>()
        val cancelled = mutableListOf<Int>()
        val reads = mutableListOf<Long>()
        var prefix: (Int, Long) -> Long = { _, _ -> 0 }
        var deferPrefix = false
        var maxPrefixes = 0
        val pendingPrefixes = ArrayDeque<(ChatResult<Long>) -> Unit>()
        var cancelResult = ChatResult(Unit)
        var onDownload: ((Command) -> Unit)? = null
        var onRefresh: ((Int, (ChatResult<MediaFile>) -> Unit) -> Unit)? = null
        override fun download(fileId: Int, priority: Int, offset: Long, limit: Long, completed: (ChatResult<MediaFile>) -> Unit) {
            val command = Command(fileId, priority, offset, limit, completed)
            commands += command
            onDownload?.invoke(command)
        }
        override fun prefix(fileId: Int, offset: Long, completed: (ChatResult<Long>) -> Unit) {
            if (deferPrefix) { pendingPrefixes += completed; maxPrefixes = maxOf(maxPrefixes, pendingPrefixes.size) }
            else completed(ChatResult(prefix(fileId, offset)))
        }
        fun completePrefix(bytes: Long) { pendingPrefixes.removeFirst()(ChatResult(bytes)) }
        override fun read(fileId: Int, offset: Long, count: Int, completed: (ChatResult<ByteArray>) -> Unit) {
            reads += offset; completed(ChatResult(ByteArray(count)))
        }
        override fun refresh(fileId: Int, completed: (ChatResult<MediaFile>) -> Unit) { onRefresh?.invoke(fileId, completed) }
        override fun cancel(fileId: Int, completed: (ChatResult<Unit>) -> Unit) { cancelled += fileId; completed(cancelResult) }
    }
}
