package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import com.badnng.moe.media.*
import kotlin.test.*

class AutoMediaControllerTest {
    @Test fun metadataAndPolicyLoadingCreateNoNativeDownloadLease() {
        val f = Fixture()
        f.controller.account(17)
        assertTrue(f.session.acquired.isEmpty())
        f.access.reads.single()(AutoMediaStored())
        f.flush()
        assertTrue(f.settings.loaded)
        assertTrue(f.session.acquired.isEmpty())
    }

    @Test fun unknownZeroStartsAutoAndARealSizeChangeRevokesOnlyThatOwner() {
        val f = Fixture()
        f.loaded()
        f.controller.visible(f.token, 10, true)
        val auto = f.session.acquired.single().first
        assertEquals(MediaDownloadPurpose.AUTO, auto.purpose)
        f.file = f.file.copy(size = 9 * MIB, expectedSize = 1)
        f.controller.reconcile()
        assertEquals(listOf(auto to 7), f.session.released)
        assertTrue(f.session.released.none { it.first.purpose == MediaDownloadPurpose.USER || it.first.purpose == MediaDownloadPurpose.STREAM })
    }

    @Test fun sourceDenialAndAskLocationCannotGrantOrdinaryDocumentCloudBytes() {
        val f = Fixture()
        f.loaded(AutoMediaPolicy().sourceEnabled(AutoMediaSource.USER, false))
        f.controller.visible(f.token, 10, true)
        assertTrue(f.session.acquired.isEmpty())
        val files = Fixture(format = MediaFormat.FILE)
        files.loaded()
        files.controller.visible(files.token, 10, true)
        assertTrue(files.session.acquired.isEmpty(), "An ordinary document requires a known non-asking external destination")
    }

    @Test fun aFailedPolicyWriteRetainsItsCurrentVisibleLease() {
        val f = Fixture()
        f.loaded()
        f.controller.visible(f.token, 10, true)
        f.controller.save(0, AutoMediaPolicy().sourceEnabled(AutoMediaSource.USER, false))
        assertTrue(f.settings.saving)
        f.access.writeResult(AutoMediaWriteResult.FAILED)
        f.flush()
        assertTrue(f.settings.error)
        assertTrue(f.session.released.isEmpty())
        assertEquals(1, f.session.acquired.size)
    }

    @Test fun saveAdmissionOnlyAcknowledgesAnActualWriteAndFailureRemainsRetryableUntilCommittedSnapshot() {
        val f = Fixture()
        val original = AutoMediaPolicy()
        val desired = original.sourceEnabled(AutoMediaSource.USER, false)
        assertFalse(f.controller.save(0, desired), "No actual account means no admission.")
        f.controller.account(17)
        assertFalse(f.controller.save(0, desired), "Pending preference loading cannot start a write.")
        assertTrue(f.access.writes.isEmpty())
        f.access.reads.single()(AutoMediaStored())
        f.flush()
        assertFalse(f.controller.save(1, desired), "An old/mismatching revision must be refused before storage.")
        assertFalse(f.controller.save(0, desired.copy(sources = emptyMap())), "Invalid policy cannot start storage.")
        assertTrue(f.access.writes.isEmpty())
        assertFalse(f.settings.saving)

        assertTrue(f.controller.save(0, desired))
        assertEquals(1, f.access.writes.size)
        assertEquals(17L, f.access.writes.single().userId)
        assertEquals(AutoMediaStored(), f.access.writes.single().expected)
        assertEquals(desired, f.access.writes.single().desired)
        assertTrue(f.settings.saving)
        assertEquals(0L, f.settings.revision, "Admission is not a committed revision.")
        assertEquals(original, f.settings.policy, "Admission cannot publish the requested policy as saved.")
        assertFalse(f.controller.save(0, original.sourceEnabled(AutoMediaSource.GROUP, false)))
        assertEquals(1, f.access.writes.size, "A second pending save must not replace the accepted write.")

        f.access.writeResult(AutoMediaWriteResult.FAILED)
        assertTrue(f.settings.saving, "The worker result still has to rejoin the owning actor.")
        f.flush()
        assertFalse(f.settings.saving)
        assertTrue(f.settings.error)
        assertEquals(AutoMediaStored(), AutoMediaStored(f.settings.revision, f.settings.policy))
        assertTrue(f.controller.save(0, desired), "A failed admitted request must allow an explicit matching retry.")
        assertEquals(2, f.access.writes.size)
        assertTrue(f.settings.saving)
        assertFalse(f.settings.error)
        assertEquals(original, f.settings.policy)
        f.access.writeResult(AutoMediaWriteResult.SAVED)
        assertEquals(0L, f.settings.revision)
        f.flush()
        assertEquals(AutoMediaStored(1, desired), AutoMediaStored(f.settings.revision, f.settings.policy))
        assertFalse(f.settings.saving)
        assertFalse(f.settings.error)
        assertFalse(f.controller.save(0, original))
        f.controller.close()
        assertFalse(f.controller.save(1, original))
        assertEquals(2, f.access.writes.size)

        val unavailable = Fixture(preferencesAvailable = false)
        unavailable.controller.account(17)
        assertTrue(unavailable.settings.loaded)
        assertFalse(unavailable.controller.save(0, desired), "A loaded fallback without a real store cannot admit persistence.")
        assertTrue(unavailable.access.writes.isEmpty())
    }

    @Test fun saveTerminalFollowsItsPublishedSnapshotAndOldDuplicateResultsCannotSettleTheRetry() {
        val f = Fixture()
        f.loaded()
        val original = f.settings.policy
        val desired = original.sourceEnabled(AutoMediaSource.USER, false)
        val first = mutableListOf<Pair<Boolean, AutoMediaSettings>>()
        val retry = mutableListOf<Pair<Boolean, AutoMediaSettings>>()
        assertTrue(f.controller.save(0, desired) { first += it to f.settings })
        val oldWrite = f.access.writeResult
        assertTrue(first.isEmpty(), "Admission does not supply a terminal outcome.")
        oldWrite(AutoMediaWriteResult.FAILED)
        oldWrite(AutoMediaWriteResult.SAVED)
        assertTrue(first.isEmpty(), "A worker result must first enter the owning actor.")
        assertTrue(f.settings.saving)
        f.flush()
        val failed = first.single()
        assertFalse(failed.first)
        assertEquals(f.settings, failed.second, "The terminal callback must observe the already published snapshot.")
        assertFalse(failed.second.saving)
        assertTrue(failed.second.error)
        assertEquals(AutoMediaStored(0, original), AutoMediaStored(failed.second.revision, failed.second.policy))

        assertTrue(f.controller.save(0, desired) { retry += it to f.settings })
        val currentWrite = f.access.writeResult
        oldWrite(AutoMediaWriteResult.SAVED)
        oldWrite(AutoMediaWriteResult.FAILED)
        f.flush()
        assertTrue(retry.isEmpty(), "A duplicate from the failed request cannot finish a new admitted retry.")
        assertEquals(1, first.size)
        assertTrue(f.settings.saving)
        assertEquals(AutoMediaStored(0, original), AutoMediaStored(f.settings.revision, f.settings.policy))

        currentWrite(AutoMediaWriteResult.SAVED)
        currentWrite(AutoMediaWriteResult.FAILED)
        assertTrue(retry.isEmpty())
        assertTrue(f.settings.saving)
        f.flush()
        val saved = retry.single()
        assertTrue(saved.first)
        assertEquals(f.settings, saved.second)
        assertFalse(saved.second.saving)
        assertFalse(saved.second.error)
        assertEquals(AutoMediaStored(1, desired), AutoMediaStored(saved.second.revision, saved.second.policy))
        oldWrite(AutoMediaWriteResult.SAVED)
        currentWrite(AutoMediaWriteResult.SAVED)
        f.flush()
        assertEquals(1, first.size)
        assertEquals(1, retry.size)
        assertEquals(AutoMediaStored(1, desired), AutoMediaStored(f.settings.revision, f.settings.policy))
        assertEquals(2, f.access.writes.size)
    }

    @Test fun retiredAccountOrClosedControllerCannotDeliverAnOldQueuedOrLateSaveTerminal() {
        listOf(false, true).forEach { close ->
            listOf(false, true).forEach { resultAlreadyQueued ->
                val f = Fixture()
                f.loaded()
                val oldDesired = f.settings.policy.sourceEnabled(AutoMediaSource.USER, false)
                val oldTerminal = mutableListOf<Boolean>()
                assertTrue(f.controller.save(0, oldDesired) { oldTerminal += it })
                val oldWrite = f.access.writeResult
                if (resultAlreadyQueued) oldWrite(AutoMediaWriteResult.SAVED)
                if (close) f.controller.close() else f.controller.account(18)
                val afterRetirement = f.settings
                if (!resultAlreadyQueued) oldWrite(AutoMediaWriteResult.SAVED)
                oldWrite(AutoMediaWriteResult.FAILED)
                val newPolicy = AutoMediaPolicy().sourceEnabled(AutoMediaSource.CHANNEL, false)
                if (!close) f.access.reads.last()(AutoMediaStored(9, newPolicy))
                f.flush()
                assertTrue(oldTerminal.isEmpty(), "A retired owner cannot receive a queued or late completion.")
                if (close) {
                    assertEquals(afterRetirement, f.settings, "A closed controller cannot republish the old save.")
                    assertFalse(f.controller.save(0, oldDesired) { oldTerminal += it })
                    assertEquals(1, f.access.writes.size)
                } else {
                    assertEquals(18L, f.settings.accountUserId)
                    assertEquals(AutoMediaStored(9, newPolicy), AutoMediaStored(f.settings.revision, f.settings.policy))
                    val newDesired = newPolicy.sourceEnabled(AutoMediaSource.GROUP, false)
                    val newTerminal = mutableListOf<Pair<Boolean, AutoMediaSettings>>()
                    assertTrue(f.controller.save(9, newDesired) { newTerminal += it to f.settings })
                    oldWrite(AutoMediaWriteResult.SAVED)
                    f.flush()
                    assertTrue(f.settings.saving)
                    assertTrue(newTerminal.isEmpty())
                    f.access.writeResult(AutoMediaWriteResult.SAVED)
                    f.flush()
                    val completed = newTerminal.single()
                    assertTrue(completed.first)
                    assertEquals(f.settings, completed.second)
                    assertEquals(18L, completed.second.accountUserId)
                    assertEquals(AutoMediaStored(10, newDesired), AutoMediaStored(completed.second.revision, completed.second.policy))
                    assertTrue(oldTerminal.isEmpty())
                }
            }
        }
    }

    @Test fun pageRetirementAndLateAccountReadsCannotReviveTheEarlierOwner() {
        val f = Fixture()
        f.controller.account(17)
        val old = f.access.reads.single()
        f.controller.account(18)
        old(AutoMediaStored())
        f.flush()
        assertEquals(18L, f.settings.accountUserId)
        assertFalse(f.settings.loaded)
        f.access.reads.last()(AutoMediaStored())
        f.flush()
        f.controller.visible(f.token, 10, true)
        f.controller.clearPage()
        f.controller.reconcile()
        assertEquals(1, f.session.acquired.size)
        assertEquals(1, f.session.released.size)
    }

    @Test fun failedAutoCommandsDoNotRetryOnEverySnapshotAndAnOldCallbackHasNoErrorAuthority() {
        val f = Fixture()
        f.loaded()
        f.controller.visible(f.token, 10, true)
        f.session.results.single()?.invoke(ChatResult(error = ChatError.REQUEST_FAILED))
        f.flush()
        repeat(5) { f.controller.reconcile() }
        assertEquals(1, f.session.acquired.size)
        assertEquals(listOf(7 to ChatError.REQUEST_FAILED), f.errors)
        f.controller.clearPage()
        f.session.results.single()?.invoke(ChatResult(error = ChatError.FORBIDDEN))
        f.flush()
        assertEquals(1, f.errors.size)
    }

    private class Fixture(val format: MediaFormat = MediaFormat.PHOTO, preferencesAvailable: Boolean = true) {
        val token = ChatToken(1, 2, 3)
        var file = MediaFile(7, size = 0, expectedSize = 100 * MIB, canDownload = true)
        val access = Access()
        val session = Session()
        val errors = mutableListOf<Pair<Int, ChatError>>()
        var settings = AutoMediaSettings()
        private val queued = ArrayDeque<() -> Unit>()
        val controller = AutoMediaController(session, access.takeIf { preferencesAvailable }, queued::addLast, { _, _ -> }, {
            AutoMediaContext(token, ChatItem(3, "synthetic", privateUserId = 17), mapOf(10L to ChatMessage(10, 3,
                media = MessageMedia(file, format = format, fileName = "synthetic.txt"))), mapOf(7 to file))
        }, { settings = it }, { id, error -> errors += id to error })
        fun loaded(policy: AutoMediaPolicy = AutoMediaPolicy()) { controller.account(17); access.reads.last()(AutoMediaStored(policy = policy)); flush() }
        fun flush() { while (queued.isNotEmpty()) queued.removeFirst().invoke() }
    }
    private class Access : AutoMediaPreferencesAccess {
        data class Write(val userId: Long, val expected: AutoMediaStored, val desired: AutoMediaPolicy)
        val reads = mutableListOf<(AutoMediaStored?) -> Unit>()
        val writes = mutableListOf<Write>()
        lateinit var writeResult: (AutoMediaWriteResult) -> Unit
        override fun read(userId: Long, completed: (AutoMediaStored?) -> Unit) { reads += completed }
        override fun write(userId: Long, expected: AutoMediaStored, desired: AutoMediaPolicy, active: () -> Boolean,
            completed: (AutoMediaWriteResult) -> Unit) {
            writes += Write(userId, expected, desired)
            writeResult = completed
        }
    }
    private class Session : TdChatSession {
        val acquired = mutableListOf<Pair<MediaDownloadOwner, Int>>()
        val released = mutableListOf<Pair<MediaDownloadOwner, Int>>()
        val results = mutableListOf<((ChatResult<MediaFile>) -> Unit)?>()
        override fun acquireDownload(owner: MediaDownloadOwner, file: MediaFile, result: ((ChatResult<MediaFile>) -> Unit)?) {
            acquired += owner to file.id; results += result
        }
        override fun releaseDownload(owner: MediaDownloadOwner, fileId: Int) { released += owner to fileId }
        override fun loadChats(limit: Int, result: (ChatResult<ChatLoadResult>) -> Unit) {}
        override fun history(chatId: Long, fromMessageId: Long, limit: Int, result: (ChatResult<List<ChatMessage>>) -> Unit) {}
        override fun open(chatId: Long) {}
        override fun close(chatId: Long) {}
        override fun sendText(chatId: Long, text: String, result: (ChatResult<ChatMessage>) -> Unit) {}
        override fun retry(chatId: Long, messageId: Long, result: (ChatResult<List<ChatMessage>>) -> Unit) {}
        override fun markRead(chatId: Long, messageIds: List<Long>, result: (ChatResult<Unit>) -> Unit) {}
    }
}
