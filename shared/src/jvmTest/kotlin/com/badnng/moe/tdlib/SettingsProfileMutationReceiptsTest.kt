package com.badnng.moe.tdlib

import com.badnng.moe.chat.AttachmentLease
import com.badnng.moe.settings.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.*

/** Draft fake-session and actor-queue coverage. This file has not been compiled or run. */
class SettingsProfileMutationReceiptsTest {
    @Test fun workerResultIsRecordedBeforeActorDrainAndTypedErrorRemainsExact() {
        val f = Fixture().apply { loaded(); birthdate() }
        val token = f.snapshot.token
        val revision = f.snapshot.startedRevision
        val before = f.snapshot
        f.onWorker { f.session.writes.single().done(SettingsResult(error = SettingsError.RATE_LIMITED)) }

        assertEquals(before, f.snapshot)
        assertEquals(1, f.queue.size)
        assertEquals(SettingsResult<Unit>(error = SettingsError.RATE_LIMITED),
            f.store.find(f.scope, token, revision, SettingsProfileExtrasOperation.BIRTHDATE))
        f.drain()
        assertFalse(f.snapshot.busy)
        assertEquals(revision, f.snapshot.failedRevision)
        assertEquals(SettingsError.RATE_LIMITED, f.snapshot.error)
    }

    @Test fun oldOwnerSuccessKeepsOriginalReceiptWithoutAcceptingNewOwner() {
        val f = Fixture().apply { loaded(); birthdate() }
        val old = f.snapshot.token
        val revision = f.snapshot.startedRevision
        val oldWrite = f.session.writes.single()
        f.controller.close(old)
        f.controller.open(43)
        val replacement = f.snapshot.token
        assertNotEquals(old, replacement)
        f.session.reads.last().done(SettingsResult(f.data))
        f.drain()
        assertTrue(f.snapshot.busy)

        f.onWorker { oldWrite.done(SettingsResult(Unit)) }
        assertEquals(SettingsResult(Unit), f.store.find(f.scope, old, revision, SettingsProfileExtrasOperation.BIRTHDATE))
        assertNull(f.store.find(f.scope, replacement, revision, SettingsProfileExtrasOperation.BIRTHDATE))
        f.drain()
        assertTrue(f.snapshot.loading)
        f.session.reads.last().done(SettingsResult(f.data))
        f.drain()
        assertEquals(replacement, f.snapshot.token)
        assertEquals(0L, f.snapshot.acceptedRevision)
        assertFalse(f.snapshot.busy)
    }

    @Test fun deactivationAndDiscardedWorkDoNotEraseAReceivedOutcomeOrInventOne() {
        val f = Fixture().apply { loaded(); birthdate() }
        val token = f.snapshot.token
        val revision = f.snapshot.startedRevision
        val write = f.session.writes.single()
        f.controller.deactivate()
        f.dropQueue.set(true)
        assertTrue(f.store.snapshot().isEmpty(), "No callback, close, or timeout creates a receipt")

        f.onWorker { write.done(SettingsResult(Unit)) }
        f.onWorker { write.done(SettingsResult(error = SettingsError.FORBIDDEN)) }
        assertTrue(f.queue.isEmpty())
        assertEquals(listOf(SettingsResult(Unit)), f.store.snapshot().map { it.result })
        assertEquals(SettingsResult(Unit), f.store.find(f.scope, token, revision, SettingsProfileExtrasOperation.BIRTHDATE))
    }

    @Test fun exactKeyIncludesIssuerIncarnationGenerationOwnerUserRevisionAndOperation() {
        val store = SettingsProfileMutationReceipts()
        val a = store.issueScope()
        val b = store.issueScope()
        val token = SettingsProfileExtrasToken(7, 1, 43)
        val success = SettingsResult(Unit)
        val operation = SettingsProfileExtrasOperation.BIRTHDATE
        assertNotSame(a, b)
        assertTrue(store.record(a, token, 4, operation, success))
        assertEquals(success, store.find(a, token, 4, operation))
        assertNull(store.find(b, token, 4, operation))
        assertNull(store.find(a, token.copy(generation = 8), 4, operation))
        assertNull(store.find(a, token.copy(owner = 2), 4, operation))
        assertNull(store.find(a, token.copy(userId = 44), 4, operation))
        assertNull(store.find(a, token, 5, operation))
        assertNull(store.find(a, token, 4, SettingsProfileExtrasOperation.REMOVE_PHOTO))
        val otherStore = SettingsProfileMutationReceipts()
        assertFalse(otherStore.record(a, token, 4, operation, success))
        assertNull(otherStore.find(a, token, 4, operation))
        assertTrue(otherStore.snapshot().isEmpty())
    }

    @Test fun separateControllersWithEqualTokensAndRevisionsNeverOverwriteEachOther() {
        val shared = SettingsProfileMutationReceipts()
        val first = Fixture(shared, generation = 7).apply { loaded(); birthdate() }
        val second = Fixture(shared, generation = 7).apply { loaded(); birthdate() }
        val laterGeneration = Fixture(shared, generation = 8).apply { loaded(); birthdate() }
        assertEquals(first.snapshot.token, second.snapshot.token)
        assertEquals(first.snapshot.startedRevision, second.snapshot.startedRevision)
        assertNotSame(first.scope, second.scope)
        first.onWorker { first.session.writes.single().done(SettingsResult(Unit)) }
        second.onWorker { second.session.writes.single().done(SettingsResult(error = SettingsError.FORBIDDEN)) }
        laterGeneration.onWorker { laterGeneration.session.writes.single().done(SettingsResult(error = SettingsError.CONFLICT)) }
        val revision = first.snapshot.startedRevision
        val op = SettingsProfileExtrasOperation.BIRTHDATE
        assertEquals(SettingsResult(Unit), shared.find(first.scope, first.snapshot.token, revision, op))
        assertEquals(SettingsResult<Unit>(error = SettingsError.FORBIDDEN),
            shared.find(second.scope, second.snapshot.token, revision, op))
        assertEquals(SettingsResult<Unit>(error = SettingsError.CONFLICT),
            shared.find(laterGeneration.scope, laterGeneration.snapshot.token, revision, op))
        assertEquals(3, shared.snapshot().size)
    }

    @Test fun capacityEvictsTheOldestReceivedResultWithoutRefreshingDuplicates() {
        val store = SettingsProfileMutationReceipts()
        val scope = store.issueScope()
        val token = SettingsProfileExtrasToken(7, 1, 43)
        val op = SettingsProfileExtrasOperation.BIRTHDATE
        repeat(64) { index -> assertTrue(store.record(scope, token, (index + 1).toLong(), op, SettingsResult(Unit))) }
        assertFalse(store.record(scope, token, 1, op, SettingsResult(error = SettingsError.FORBIDDEN)))
        assertTrue(store.record(scope, token, 65, op, SettingsResult(Unit)))
        assertNull(store.find(scope, token, 1, op))
        assertEquals(SettingsResult(Unit), store.find(scope, token, 2, op))
        val snapshot = store.snapshot()
        assertEquals(64, snapshot.size)
        assertEquals(2L, snapshot.first().key.revision)
        assertEquals(65L, snapshot.last().key.revision)
        assertFailsWith<UnsupportedOperationException> { (snapshot as MutableList<SettingsProfileMutationReceipts.Receipt>).clear() }

        val start = CountDownLatch(1)
        val workers = (66L..81L).map { revision -> thread(isDaemon = true) {
            start.await()
            store.record(scope, token, revision, op, SettingsResult(Unit))
        } }
        start.countDown()
        workers.forEach { it.join(2_000); assertFalse(it.isAlive) }
        val after = store.snapshot()
        assertEquals(64, after.size)
        assertEquals(64, after.map { it.key }.toSet().size)
    }

    @Test fun workThrowRemainsUnknownUntilOneLateActualTerminalSettlesTheMutation() {
        val session = Session().apply { onBirthdate = { error("ambiguous dispatch") } }
        val f = Fixture(session = session).apply { loaded(); birthdate() }
        val token = f.snapshot.token
        val revision = f.snapshot.startedRevision
        assertTrue(f.store.snapshot().isEmpty())
        assertTrue(f.queue.isEmpty(), "An ambiguous throw cannot invent a UI settlement")
        f.drain()
        assertTrue(f.snapshot.busy)
        assertNull(f.snapshot.error)
        assertEquals(0L, f.snapshot.failedRevision)
        assertEquals(0L, f.snapshot.acceptedRevision)
        val unknown = f.snapshot

        f.onWorker { session.writes.single().done(SettingsResult(Unit)) }
        f.onWorker { session.writes.single().done(SettingsResult(error = SettingsError.FORBIDDEN)) }
        assertEquals(SettingsResult(Unit), f.store.find(f.scope, token, revision, SettingsProfileExtrasOperation.BIRTHDATE))
        assertEquals(1, f.store.snapshot().size)
        assertEquals(1, f.queue.size, "Only the first actual terminal enqueues a real UI settlement")
        assertEquals(unknown, f.snapshot, "The worker records the fact before actor publication")
        f.drain()
        assertEquals(revision, f.snapshot.acceptedRevision)
        assertEquals(0L, f.snapshot.failedRevision)
        assertNull(f.snapshot.error)
        f.session.reads.last().done(SettingsResult(f.data))
        f.drain()
        assertFalse(f.snapshot.busy)
        assertEquals(revision, f.snapshot.completedRevision)
        assertEquals(1, f.store.snapshot().size)
        synchronousActualCallbackWinsBeforeThrowAndDuplicateCallback()
    }

    private fun synchronousActualCallbackWinsBeforeThrowAndDuplicateCallback() {
        val session = Session().apply {
            onBirthdate = { done ->
                done(SettingsResult(Unit))
                done(SettingsResult(error = SettingsError.FORBIDDEN))
                error("throw after callback")
            }
        }
        val f = Fixture(session = session).apply { loaded(); birthdate() }
        val token = f.snapshot.token
        val revision = f.snapshot.startedRevision
        assertEquals(SettingsResult(Unit), f.store.find(f.scope, token, revision, SettingsProfileExtrasOperation.BIRTHDATE))
        assertEquals(1, f.queue.size)
        f.drain()
        assertEquals(revision, f.snapshot.acceptedRevision)
        assertEquals(0L, f.snapshot.failedRevision)
        assertEquals(1, f.store.snapshot().size)
    }

    @Test fun throwingCleanupCannotPreventTheReceiptOrActorConvergence() {
        val f = Fixture().apply { loaded() }
        var releases = 0
        var removed = 0
        var recordedBeforeRelease = false
        val handle = object : AttachmentLease {
            override fun retain(): AttachmentLease = this
            override fun release() {
                releases++
                recordedBeforeRelease = f.store.snapshot().size == 1
                error("synthetic release failure")
            }
        }
        val retained = PendingAttachmentLease(handle, { removed++ }, { null })
        val photo = SettingsPreparedProfilePhoto("synthetic.jpg", 512, 32, "00".repeat(32), handle)
        f.controller.upload(f.snapshot.token, 91, photo, retained)
        val revision = f.snapshot.startedRevision
        f.session.writes.single().done(SettingsResult(Unit))
        f.session.writes.single().done(SettingsResult(error = SettingsError.FORBIDDEN))
        assertEquals(1, releases)
        assertEquals(1, removed)
        assertTrue(recordedBeforeRelease, "Actual result must be recorded before cleanup")
        assertEquals(1, f.queue.size)
        f.drain()
        assertEquals(revision, f.snapshot.acceptedRevision)
        f.session.reads.last().done(SettingsResult(f.data))
        f.drain()
        assertFalse(f.snapshot.busy)
        assertEquals(revision, f.snapshot.completedRevision)
    }

    @Test fun parentControllerForwardsItsInjectedStoreToTheActualExtrasController() {
        val store = SettingsProfileMutationReceipts()
        val extras = Session()
        val session = ParentSession(extras)
        val queue = ConcurrentLinkedQueue<() -> Unit>()
        var snapshot = SettingsSnapshot()
        val controller = SettingsController(7, session, { queue.add(it) }, { snapshot = it },
            profileMutationReceipts = store)
        fun drain() { while (true) (queue.poll() ?: break).invoke() }

        controller.start()
        controller.load(snapshot.token, SettingsSection.PROFILE)
        drain()
        controller.openProfileExtras(snapshot.token, 43) { true }
        extras.reads.single().done(SettingsResult(SettingsProfileExtrasData(43, 91, null, SettingsCalendarDate(25, 10, 2026))))
        drain()
        val parent = snapshot.profileExtras.token
        controller.saveProfileBirthdate(parent, null, SettingsBirthdate(4, 5))
        val revision = snapshot.profileExtras.startedRevision
        controller.deactivate()
        extras.writes.single().done(SettingsResult(Unit))

        val receipt = store.snapshot().single()
        assertEquals(parent, receipt.key.token)
        assertEquals(revision, receipt.key.revision)
        assertEquals(SettingsProfileExtrasOperation.BIRTHDATE, receipt.key.operation)
        assertEquals(SettingsResult(Unit), receipt.result)
    }

    private class Fixture(
        val store: SettingsProfileMutationReceipts = SettingsProfileMutationReceipts(),
        generation: Long = 7,
        val session: Session = Session(),
    ) {
        val data = SettingsProfileExtrasData(43, 91, null, SettingsCalendarDate(25, 10, 2026))
        val queue = ConcurrentLinkedQueue<() -> Unit>()
        val dropQueue = AtomicBoolean()
        var snapshot = SettingsProfileExtrasSnapshot()
        val controller = SettingsProfileExtrasController(generation, session,
            { if (!dropQueue.get()) queue.add(it) }, { snapshot = it }, profileMutationReceipts = store)
        val scope get() = checkNotNull(controller.mutationReceiptScope)

        fun loaded() {
            controller.open(43)
            session.reads.last().done(SettingsResult(data))
            drain()
        }

        fun birthdate() = controller.birthdate(snapshot.token, null, SettingsBirthdate(4, 5))

        fun drain() {
            while (true) (queue.poll() ?: break).invoke()
        }

        fun onWorker(action: () -> Unit) {
            val done = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            val worker = thread(isDaemon = true, name = "profile-receipt-fake-worker") {
                try { action() } catch (error: Throwable) { failure.set(error) } finally { done.countDown() }
            }
            assertTrue(done.await(2, TimeUnit.SECONDS))
            worker.join()
            failure.get()?.let { throw it }
        }
    }

    private class Session : TdSettingsProfileExtrasSession {
        data class Read(val done: (SettingsResult<SettingsProfileExtrasData>) -> Unit)
        data class Write(val done: (SettingsResult<Unit>) -> Unit)
        val reads = mutableListOf<Read>()
        val writes = mutableListOf<Write>()
        var onBirthdate: (((SettingsResult<Unit>) -> Unit) -> Unit)? = null
        /** Explicit final fake native reply; duplicate and late replies still reach production. */
        private fun nativeWriteResult(result: (SettingsResult<Unit>) -> Unit): (SettingsResult<Unit>) -> Unit = { response ->
            val receipt = when {
                response.value != null && response.error == null -> SettingsSpecialWriteReceipt(SettingsSpecialWriteOutcome.ACCEPTED)
                response.error != null -> SettingsSpecialWriteReceipt(SettingsSpecialWriteOutcome.REJECTED, response.error)
                else -> SettingsSpecialWriteReceipt(SettingsSpecialWriteOutcome.INDETERMINATE)
            }
            SettingsSpecialMutationContext.withReceipt(receipt) { result(response) }
        }

        override fun load(userId: Long, isCurrent: () -> Boolean, result: (SettingsResult<SettingsProfileExtrasData>) -> Unit) {
            reads += Read(result)
        }
        override fun setBirthdate(userId: Long, expected: SettingsBirthdate?, desired: SettingsBirthdate?,
            isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit,
        ) {
            val nativeResult = nativeWriteResult(result)
            writes += Write(nativeResult)
            onBirthdate?.invoke(nativeResult)
        }
        override fun uploadPhoto(userId: Long, expectedPhotoId: Long?, photo: SettingsPreparedProfilePhoto,
            isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit,
        ) { writes += Write(nativeWriteResult(result)) }
        override fun removePhoto(userId: Long, photoId: Long, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) {
            writes += Write(nativeWriteResult(result))
        }
    }

    private class ParentSession(override val profileExtras: TdSettingsProfileExtrasSession) : TdSettingsSession {
        override fun profile(result: (SettingsResult<SettingsProfile>) -> Unit) {
            result(SettingsResult(SettingsProfile(43, "Synthetic", "", "", "", "")))
        }
        override fun saveProfile(edit: SettingsProfileEdit, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun notifications(result: (SettingsResult<Map<NotificationScope, NotificationPreferences>>) -> Unit) = unsupported(result)
        override fun saveNotifications(scope: NotificationScope, value: NotificationPreferences, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun privacy(result: (SettingsResult<SettingsPrivacyData>) -> Unit) = unsupported(result)
        override fun savePrivacy(key: PrivacyKey, base: PrivacyBase, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun setAccountTtl(days: Int, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun terminateSession(id: Long, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun unblock(sender: SettingsBlockedSender, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun advanced(result: (SettingsResult<SettingsAdvancedData>) -> Unit) = unsupported(result)
        override fun optimizeStorage(result: (SettingsResult<SettingsStorage>) -> Unit) = unsupported(result)
        override fun addProxy(draft: SettingsProxyDraft, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun enableProxy(id: Int?, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun removeProxy(id: Int, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        private fun <T> unsupported(result: (SettingsResult<T>) -> Unit) = result(SettingsResult(error = SettingsError.UNSUPPORTED))
    }
}
