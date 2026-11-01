package com.badnng.moe.notifications

import com.badnng.moe.chat.MediaFile
import com.badnng.moe.settings.*
import com.badnng.moe.tdlib.*
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.test.*

class OfficialNotificationSoundSourceTest {
    @Test fun defaultUsesThePinnedRecordingAndZeroNeverLoadsOrDecodes() {
        Fixture().use { f ->
            val direct = f.load(-1).single()
            assertFalse(direct.value!!.defaultFallback)
            assertContentEquals(deviceDefaultNotificationPcm(100), direct.value!!.pcm.samples(100))
            assertEquals(SettingsError.INVALID_VALUE, f.load(0).single().error)
            assertEquals(0, f.catalog.loads)
            assertEquals(0, f.files.reads)
            assertEquals(0, f.decodes)
        }
    }

    @Test fun onlyACompleteSuccessfulCatalogMissingTheSignedIdAllowsDefaultFallback() {
        Fixture().use { f ->
            f.catalog.sounds = emptyList()
            val absent = f.load(Long.MIN_VALUE).single()
            assertEquals(Long.MIN_VALUE, absent.value!!.requestedSoundId)
            assertTrue(absent.value!!.defaultFallback)
            assertEquals(0, f.files.reads)
            f.catalog.loadError = SettingsError.REQUEST_FAILED
            assertEquals(SettingsError.REQUEST_FAILED, f.load(Long.MIN_VALUE).single().error)
            f.catalog.loadError = SettingsError.FORBIDDEN
            assertEquals(SettingsError.FORBIDDEN, f.load(Long.MIN_VALUE).single().error)
            f.catalog.loadError = null
            f.catalog.account = 99
            assertEquals(SettingsError.CONFLICT, f.load(Long.MIN_VALUE).single().error)
        }
    }

    @Test fun customIdentityUsesTheRealFileAndCacheHitsStillReadCurrentMetadata() {
        Fixture().use { f ->
            val first = f.load().single()
            assertNull(first.error)
            assertFalse(first.value!!.defaultFallback)
            assertEquals(f.catalog.sound, f.files.expected)
            assertEquals(16 * 1024 * 1024, f.files.maximum)
            assertEquals(30_000L, f.durationCap)
            assertEquals(1, f.decodes)
            assertEquals(1, f.files.closes, "A synchronous byte callback must still close its returned lease.")
            assertNull(f.load().single().error)
            assertEquals(2, f.catalog.loads)
            assertEquals(2, f.catalog.reads)
            assertEquals(1, f.files.reads)
            assertEquals(1, f.decodes)
            f.catalog.sound = f.catalog.sound.copy(file = f.catalog.sound.file.copy(uniqueId = "replacement"))
            assertNull(f.load().single().error)
            assertEquals(2, f.files.reads)
            assertEquals(2, f.decodes)
        }
    }

    @Test fun finalMetadataComparesImmutableFieldsAndNotNativeDownloadProgress() {
        Fixture().use { f ->
            f.catalog.readTransform = { it.copy(file = it.file.copy(downloaded = true, downloading = false, downloadedSize = 100)) }
            assertNull(f.load().single().error)
            f.catalog.readTransform = { it.copy(title = "changed metadata") }
            assertEquals(SettingsError.CONFLICT, f.load().single().error)
            f.source.invalidate()
            f.catalog.readTransform = { it.copy(file = it.file.copy(id = 92)) }
            assertEquals(SettingsError.CONFLICT, f.load().single().error)
        }
    }

    @Test fun readDownloadAndDecodeFailuresNeverUseDefault() {
        Fixture().use { f ->
            f.files.error = SettingsError.FORBIDDEN
            assertEquals(SettingsError.FORBIDDEN, f.load().single().error)
            f.files.error = null
            f.decodeError = SettingsError.INVALID_VALUE
            assertEquals(SettingsError.INVALID_VALUE, f.load().single().error)
            f.decodeError = null
            f.catalog.readError = SettingsError.REQUEST_FAILED
            assertEquals(SettingsError.REQUEST_FAILED, f.load().single().error)
        }
    }

    @Test fun bytesCannotSubstituteAnotherImmutableNativeFile() {
        Fixture().use { f ->
            f.files.transform = { it.copy(file = it.file.copy(uniqueId = "other native file")) }
            assertEquals(SettingsError.CONFLICT, f.load().single().error)
            assertEquals(0, f.decodes)
            assertEquals(1, f.files.closes)
        }
    }

    @Test fun finalRevalidationIsRequiredBeforePublishingAndLateReadCannotSurviveInvalidation() {
        Fixture().use { f ->
            f.catalog.holdRead = true
            val results = f.load()
            assertTrue(results.isEmpty())
            assertEquals(1, f.decodes)
            f.source.invalidate()
            assertEquals(SettingsError.NOT_READY, results.single().error)
            f.catalog.releaseReads()
            assertEquals(1, results.size)
            f.catalog.holdRead = false
            assertNull(f.load().single().error)
            assertEquals(2, f.decodes, "An invalidated late decode must not populate the next generation cache.")
        }
    }

    @Test fun cancellationInvalidationAndCloseRejectLateOrDuplicateCallbacksExactlyOnce() {
        Fixture().use { f ->
            f.catalog.holdLoad = true
            val cancelled = mutableListOf<SettingsResult<PreparedNotificationAudio>>()
            val lease = f.source.load(7, -22, { true }, cancelled::add)
            lease.close()
            f.catalog.releaseLoads()
            assertEquals(listOf(SettingsError.NOT_READY), cancelled.map { it.error })
            assertEquals(0, f.files.reads)
            val invalidated = f.load()
            f.source.invalidate()
            f.catalog.releaseLoads()
            assertEquals(listOf(SettingsError.NOT_READY), invalidated.map { it.error })
            f.catalog.holdLoad = false
            f.files.duplicate = true
            assertEquals(1, f.load().size)
            assertEquals(1, f.decodes)
            f.source.close()
            assertEquals(SettingsError.NOT_READY, f.load().single().error)
        }
    }

    @Test fun inactiveOwnershipCannotReactivateAndPendingAdmissionIsBounded() {
        Fixture().use { f ->
            f.catalog.holdLoad = true
            var active = true
            val results = mutableListOf<SettingsResult<PreparedNotificationAudio>>()
            f.source.load(7, -22, { active }, results::add)
            active = false
            assertFalse(f.catalog.pending.single().first())
            active = true
            f.catalog.releaseLoads()
            assertEquals(SettingsError.NOT_READY, results.single().error)
            val waiters = List(8) { f.load() }
            assertTrue(waiters.all { it.isEmpty() })
            assertEquals(SettingsError.RATE_LIMITED, f.load().single().error)
            f.source.invalidate()
            assertTrue(waiters.all { it.single().error == SettingsError.NOT_READY })
        }
    }

    @Test fun fileLeaseIsReleasedOnCancellationAndOtherLoadsRetainTheirOwners() {
        Fixture().use { f ->
            f.files.hold = true
            val first = mutableListOf<SettingsResult<PreparedNotificationAudio>>()
            val lease = f.source.load(7, -22, { true }, first::add)
            val second = f.load()
            assertEquals(2, f.files.reads)
            lease.close()
            assertEquals(1, f.files.closes)
            f.files.release()
            assertEquals(SettingsError.NOT_READY, first.single().error)
            assertNull(second.single().error)
            assertEquals(2, f.files.closes)
            assertEquals(1, f.decodes)
        }
    }

    @Test fun cacheCannotCrossAccountOrInvalidatedGenerationAndEvictsOldEntries() {
        Fixture().use { f ->
            repeat(9) { index ->
                f.catalog.sound = sound((-22 - index).toLong(), index + 71)
                assertNull(f.load(f.catalog.sound.id).single().error)
            }
            assertEquals(9, f.decodes)
            f.catalog.sound = sound()
            assertNull(f.load().single().error)
            assertEquals(10, f.decodes)
            f.catalog.account = 8
            val other = mutableListOf<SettingsResult<PreparedNotificationAudio>>()
            f.source.load(8, -22, { true }, other::add)
            assertNull(other.single().error)
            assertEquals(11, f.decodes)
            f.source.invalidate()
            f.catalog.account = 7
            assertNull(f.load().single().error)
            assertEquals(12, f.decodes)
        }
    }

    @Test fun decodedByteBudgetEvictsEvenWhenTheEntryCountIsBelowEight() {
        Fixture().use { f ->
            f.pcmBytes = NotificationSoundPcm.MAX_BYTES
            repeat(3) { index ->
                f.catalog.sound = sound((-22 - index).toLong(), index + 71)
                assertNull(f.load(f.catalog.sound.id).single().error)
            }
            f.catalog.sound = sound()
            assertNull(f.load().single().error)
            assertEquals(4, f.decodes)
        }
    }

    @Test fun cancelledWorkerAndCancelledScopeCompleteWithoutASecondNativeRead() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val catalog = Catalog()
        val files = FilesFake()
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val source = OfficialNotificationSoundSource(catalog, files, worker, Dispatchers.Unconfined) { _, _, _ ->
            try { started.complete(Unit); awaitCancellation() } finally { stopped.complete(Unit) }
        }
        val results = mutableListOf<SettingsResult<PreparedNotificationAudio>>()
        val lease = source.load(7, -22, { true }, results::add)
        started.await()
        lease.close()
        stopped.await()
        assertEquals(SettingsError.NOT_READY, results.single().error)
        assertEquals(0, catalog.reads)
        source.close(); worker.cancel()
        val cancelled = CoroutineScope(Job().apply { cancel() } + Dispatchers.Unconfined)
        val next = OfficialNotificationSoundSource(catalog, files, cancelled, Dispatchers.Unconfined) { _, _, _ ->
            error("Canceled scope must never decode")
        }
        val after = mutableListOf<SettingsResult<PreparedNotificationAudio>>()
        next.load(7, -22, { true }, after::add)
        assertEquals(SettingsError.NOT_READY, after.single().error)
        next.close()
    }

    @Test fun foreignOwnerLockInsideActiveCannotBlockInvalidationOrCloseOfTheSource() {
        for (closing in listOf(false, true)) Fixture().use { f ->
            f.catalog.holdLoad = true
            val foreign = ReentrantLock()
            val evaluateForeign = AtomicBoolean()
            val ownerHeld = CountDownLatch(1)
            val gateEntered = CountDownLatch(1)
            val retired = CountDownLatch(1)
            val failures = ConcurrentLinkedQueue<Throwable>()
            val results = ConcurrentLinkedQueue<SettingsResult<PreparedNotificationAudio>>()
            val probe = AtomicBoolean(true)
            f.source.load(7, -22, {
                if (evaluateForeign.get()) {
                    gateEntered.countDown()
                    check(foreign.tryLock(5, TimeUnit.SECONDS)) { "Timed out waiting for synthetic owner lock" }
                    foreign.unlock()
                }
                true
            }, results::add)
            evaluateForeign.set(true)
            val owner = Thread {
                try {
                    foreign.lock()
                    try {
                        ownerHeld.countDown()
                        check(gateEntered.await(5, TimeUnit.SECONDS))
                        if (closing) f.source.close() else f.source.invalidate()
                        retired.countDown()
                    } finally { foreign.unlock() }
                } catch (failure: Throwable) { failures.add(failure) }
            }.apply { isDaemon = true }
            val reader = Thread {
                try {
                    check(ownerHeld.await(5, TimeUnit.SECONDS))
                    probe.set(f.catalog.pending.single().first())
                } catch (failure: Throwable) { failures.add(failure) }
            }.apply { isDaemon = true }
            try {
                owner.start(); reader.start()
                assertTrue(retired.await(2, TimeUnit.SECONDS), "Source retirement must not wait for active's foreign owner lock.")
            } finally { owner.join(6_000); reader.join(6_000) }
            assertFalse(owner.isAlive || reader.isAlive)
            assertTrue(failures.isEmpty())
            assertFalse(probe.get())
            assertEquals(SettingsError.NOT_READY, results.single().error)
        }
    }

    @Test fun loadWhoseAdmissionGateWasBlockedCannotJoinTheEpochCreatedByInvalidation() {
        Fixture().use { f ->
            val gateEntered = CountDownLatch(1)
            val releaseGate = CountDownLatch(1)
            val retired = CountDownLatch(1)
            val results = ConcurrentLinkedQueue<SettingsResult<PreparedNotificationAudio>>()
            val failures = ConcurrentLinkedQueue<Throwable>()
            val reader = Thread {
                try {
                    f.source.load(7, -22, {
                        gateEntered.countDown()
                        check(releaseGate.await(5, TimeUnit.SECONDS))
                        true
                    }, results::add)
                } catch (failure: Throwable) { failures.add(failure) }
            }.apply { isDaemon = true }
            val retire = Thread {
                try {
                    check(gateEntered.await(5, TimeUnit.SECONDS))
                    f.source.invalidate(); retired.countDown()
                } catch (failure: Throwable) { failures.add(failure) }
            }.apply { isDaemon = true }
            try {
                reader.start(); retire.start()
                assertTrue(retired.await(2, TimeUnit.SECONDS), "Foreign admission gate must not hold the source lock.")
            } finally { releaseGate.countDown(); reader.join(6_000); retire.join(6_000) }
            assertFalse(reader.isAlive || retire.isAlive)
            assertTrue(failures.isEmpty())
            assertEquals(0, f.catalog.loads)
            assertEquals(SettingsError.NOT_READY, results.single().error)
        }
    }

    @Test fun explicitWarmUsesOnlyTheSuppliedRealNativeFileWithoutDecodingOrPopulatingPcmCache() {
        Fixture().use { f ->
            f.files.duplicate = true
            val results = mutableListOf<SettingsResult<Unit>>()
            val lease = f.source.warm(7, f.catalog.sound, { true }, results::add)
            assertEquals(Unit, results.single().value)
            assertNull(results.single().error)
            assertEquals(f.catalog.sound, f.files.expected)
            assertEquals(listOf(7L), f.files.accounts)
            assertEquals(listOf(7L), f.catalog.readAccounts)
            assertEquals(16 * 1024 * 1024, f.files.maximum)
            assertEquals(0, f.catalog.loads, "Warming the supplied native metadata does not fabricate or reload a catalog.")
            assertEquals(0, f.decodes)
            assertEquals(1, f.files.closes, "Attach after a synchronous warm callback closes its own returned lease.")
            lease.close()
            assertEquals(1, f.files.closes)
            assertEquals(1, results.size)
            assertNull(f.load().single().error)
            assertEquals(2, f.files.reads, "Warm stores bytes only in the shared native cache, never the decoded PCM cache.")
            assertEquals(1, f.decodes)
        }
    }

    @Test fun warmRejectsInvalidIdentityAndPropagatesNativeFailureWithoutClaimingSuccess() {
        Fixture().use { f ->
            for (invalid in listOf(f.catalog.sound.copy(id = 0), f.catalog.sound.copy(id = -1),
                f.catalog.sound.copy(file = f.catalog.sound.file.copy(id = 0)),
                f.catalog.sound.copy(file = f.catalog.sound.file.copy(uniqueId = "")))) {
                val results = mutableListOf<SettingsResult<Unit>>()
                f.source.warm(7, invalid, { true }, results::add)
                assertEquals(SettingsError.INVALID_VALUE, results.single().error)
            }
            assertEquals(0, f.files.reads)
            f.files.error = SettingsError.FORBIDDEN
            val failed = mutableListOf<SettingsResult<Unit>>()
            f.source.warm(7, f.catalog.sound, { true }, failed::add)
            assertEquals(SettingsError.FORBIDDEN, failed.single().error)
            assertNull(failed.single().value)
            f.files.error = null
            f.files.transform = { it.copy(file = it.file.copy(uniqueId = "wrong file")) }
            val mismatched = mutableListOf<SettingsResult<Unit>>()
            f.source.warm(7, f.catalog.sound, { true }, mismatched::add)
            assertEquals(SettingsError.CONFLICT, mismatched.single().error)
            assertEquals(0, f.decodes)
        }
    }

    @Test fun warmRechecksTheOriginalAccountAndImmutableIdentityAfterBytesAreOwned() {
        Fixture().use { f ->
            f.files.hold = true
            val accountChanged = mutableListOf<SettingsResult<Unit>>()
            f.source.warm(7, f.catalog.sound, { true }, accountChanged::add)
            f.catalog.account = 99
            f.files.release()
            assertEquals(SettingsError.CONFLICT, accountChanged.single().error)
            assertEquals(listOf(7L), f.catalog.readAccounts)
            f.catalog.account = 7
            f.catalog.readTransform = { it.copy(title = "replaced metadata") }
            val changed = mutableListOf<SettingsResult<Unit>>()
            f.source.warm(7, f.catalog.sound, { true }, changed::add)
            f.files.release()
            assertEquals(SettingsError.CONFLICT, changed.single().error)
            f.catalog.readTransform = { it.copy(file = it.file.copy(downloadedSize = 100, downloaded = true)) }
            val progressOnly = mutableListOf<SettingsResult<Unit>>()
            f.source.warm(7, f.catalog.sound, { true }, progressOnly::add)
            f.files.release()
            assertEquals(Unit, progressOnly.single().value)
            assertEquals(0, f.decodes)
        }
    }

    @Test fun closingAWarmOwnerDoesNotCancelAnotherLoadForTheSameNativeFile() {
        Fixture().use { f ->
            f.files.hold = true
            val loading = f.load()
            val warmed = mutableListOf<SettingsResult<Unit>>()
            val lease = f.source.warm(7, f.catalog.sound, { true }, warmed::add)
            lease.close()
            assertEquals(SettingsError.NOT_READY, warmed.single().error)
            assertTrue(f.files.activeOwners[0]())
            assertFalse(f.files.activeOwners[1]())
            assertEquals(1, f.files.closes)
            assertTrue(loading.isEmpty())
            f.files.release()
            assertNull(loading.single().error)
            assertEquals(1, warmed.size)
            assertEquals(2, f.files.closes)
            assertEquals(1, f.decodes)
        }
    }

    @Test fun warmAndLoadShareThePendingCapAndInvalidationRetiresBothWithoutLateRevival() {
        Fixture().use { f ->
            f.files.hold = true
            val loads = List(4) { f.load() }
            val warms = List(4) {
                mutableListOf<SettingsResult<Unit>>().also { f.source.warm(7, f.catalog.sound, { true }, it::add) }
            }
            assertEquals(8, f.files.reads)
            assertEquals(SettingsError.RATE_LIMITED, f.load().single().error)
            val rejected = mutableListOf<SettingsResult<Unit>>()
            f.source.warm(7, f.catalog.sound, { true }, rejected::add)
            assertEquals(SettingsError.RATE_LIMITED, rejected.single().error)
            assertEquals(8, f.files.reads)
            f.source.invalidate()
            assertTrue(loads.all { it.single().error == SettingsError.NOT_READY })
            assertTrue(warms.all { it.single().error == SettingsError.NOT_READY })
            assertEquals(8, f.files.closes)
            f.files.release()
            assertTrue(loads.all { it.size == 1 })
            assertTrue(warms.all { it.size == 1 })
            assertEquals(0, f.decodes)
        }
    }

    @Test fun warmDeactivationCannotReviveAndSourceCloseRejectsOldAndFutureWarmCallbacks() {
        Fixture().use { f ->
            f.files.hold = true
            var active = true
            val inactive = mutableListOf<SettingsResult<Unit>>()
            f.source.warm(7, f.catalog.sound, { active }, inactive::add)
            active = false
            assertFalse(f.files.activeOwners.single()())
            active = true
            assertFalse(f.files.activeOwners.single()())
            f.files.release()
            assertEquals(SettingsError.NOT_READY, inactive.single().error)
            val closing = mutableListOf<SettingsResult<Unit>>()
            f.source.warm(7, f.catalog.sound, { true }, closing::add)
            f.source.close()
            assertEquals(SettingsError.NOT_READY, closing.single().error)
            f.files.release()
            assertEquals(1, closing.size)
            val after = mutableListOf<SettingsResult<Unit>>()
            f.source.warm(7, f.catalog.sound, { true }, after::add)
            assertEquals(SettingsError.NOT_READY, after.single().error)
            assertEquals(2, f.files.reads)
            assertEquals(0, f.decodes)
        }
    }

    @Test fun warmCanceledDuringFinalMetadataReadCannotPublishALateSuccessfulUnit() {
        Fixture().use { f ->
            f.catalog.holdRead = true
            val results = mutableListOf<SettingsResult<Unit>>()
            val lease = f.source.warm(7, f.catalog.sound, { true }, results::add)
            assertTrue(results.isEmpty())
            assertEquals(1, f.files.closes, "Owned native bytes permit releasing the file lease before delayed metadata verification.")
            lease.close()
            f.catalog.releaseReads()
            assertEquals(SettingsError.NOT_READY, results.single().error)
            assertEquals(0, f.decodes)
        }
    }

    @Test fun invalidatingWarmDoesNotWaitForItsForeignActiveGateOrResurrectAfterItReturns() {
        Fixture().use { f ->
            f.files.hold = true
            val block = AtomicBoolean()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val retired = CountDownLatch(1)
            val failures = ConcurrentLinkedQueue<Throwable>()
            val results = ConcurrentLinkedQueue<SettingsResult<Unit>>()
            f.source.warm(7, f.catalog.sound, {
                if (block.get()) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                true
            }, results::add)
            block.set(true)
            val reader = Thread {
                try { assertFalse(f.files.activeOwners.single()()) }
                catch (failure: Throwable) { failures.add(failure) }
            }.apply { isDaemon = true }
            val retire = Thread {
                try {
                    check(entered.await(5, TimeUnit.SECONDS))
                    f.source.invalidate(); retired.countDown()
                } catch (failure: Throwable) { failures.add(failure) }
            }.apply { isDaemon = true }
            try {
                reader.start(); retire.start()
                assertTrue(retired.await(2, TimeUnit.SECONDS), "Warm's foreign active gate cannot hold the source lock or block forced cancellation.")
            } finally { release.countDown(); reader.join(6_000); retire.join(6_000) }
            assertFalse(reader.isAlive || retire.isAlive)
            assertTrue(failures.isEmpty())
            assertEquals(SettingsError.NOT_READY, results.single().error)
            f.files.release()
            assertEquals(1, results.size)
            assertEquals(0, f.decodes)
        }
    }

    private class Fixture : AutoCloseable {
        val catalog = Catalog()
        val files = FilesFake()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var decodes = 0
        var durationCap = 0L
        var pcmBytes = 192
        var decodeError: SettingsError? = null
        val source = OfficialNotificationSoundSource(catalog, files, scope, Dispatchers.Unconfined) { _, maximum, active ->
            assertTrue(active()); decodes++; durationCap = maximum
            if (decodeError == null) SettingsResult(NotificationSoundPcm(ByteArray(pcmBytes))) else SettingsResult(error = decodeError)
        }
        fun load(id: Long = -22): MutableList<SettingsResult<PreparedNotificationAudio>> =
            mutableListOf<SettingsResult<PreparedNotificationAudio>>().also { source.load(7, id, { true }, it::add) }
        override fun close() { source.close(); scope.cancel() }
    }

    private class Catalog : TdSavedNotificationSoundsSession {
        var account = 7L
        var sound = sound()
        var sounds: List<SavedNotificationSound>? = null
        var loadError: SettingsError? = null
        var readError: SettingsError? = null
        var readTransform: (SavedNotificationSound) -> SavedNotificationSound = { it }
        var loads = 0
        var reads = 0
        val readAccounts = mutableListOf<Long>()
        var holdLoad = false
        var holdRead = false
        val pendingReads = mutableListOf<() -> Unit>()
        val pending = mutableListOf<Pair<() -> Boolean, (SettingsResult<SavedNotificationSoundsData>) -> Unit>>()
        override fun load(active: () -> Boolean, result: (SettingsResult<SavedNotificationSoundsData>) -> Unit) {
            loads++
            if (holdLoad) pending.add(active to result) else result(data())
        }
        fun releaseLoads() {
            val copy = pending.toList(); pending.clear()
            copy.forEach { it.second(data()); it.second(data()) }
        }
        private fun data(): SettingsResult<SavedNotificationSoundsData> = if (loadError != null) SettingsResult(error = loadError)
            else SettingsResult(SavedNotificationSoundsData(account, sounds ?: listOf(sound)))
        override fun read(accountUserId: Long, soundId: Long, active: () -> Boolean, result: (SettingsResult<SavedNotificationSound>) -> Unit) {
            reads++
            readAccounts.add(accountUserId)
            val respond = {
                val response: SettingsResult<SavedNotificationSound> = when {
                    accountUserId != account -> SettingsResult<SavedNotificationSound>(error = SettingsError.CONFLICT)
                    readError != null -> SettingsResult(error = readError)
                    else -> SettingsResult(readTransform(sound))
                }
                result(response); result(response)
            }
            if (holdRead) pendingReads.add(respond) else respond()
        }
        fun releaseReads() { val copy = pendingReads.toList(); pendingReads.clear(); copy.forEach { it() } }
        override fun add(expected: SavedNotificationSoundsData, prepared: PreparedNotificationSoundUpload, active: () -> Boolean,
            result: (SavedNotificationSoundMutationResult) -> Unit) = error("No mutations")
        override fun remove(accountUserId: Long, expected: SavedNotificationSound, active: () -> Boolean,
            result: (SavedNotificationSoundMutationResult) -> Unit) = error("No mutations")
    }

    private class FilesFake : TdNotificationSoundFilesSession {
        var reads = 0
        val accounts = mutableListOf<Long>()
        val activeOwners = mutableListOf<() -> Boolean>()
        var closes = 0
        var maximum = 0
        var expected: SavedNotificationSound? = null
        var error: SettingsError? = null
        var duplicate = false
        var hold = false
        var transform: (SavedNotificationSound) -> SavedNotificationSound = { it }
        val pending = mutableListOf<() -> Unit>()
        override fun read(accountUserId: Long, expected: SavedNotificationSound, maximumBytes: Int, active: () -> Boolean,
            result: (SettingsResult<NotificationSoundEncoded>) -> Unit): NotificationSoundFileLease {
            reads++; this.expected = expected; maximum = maximumBytes
            accounts.add(accountUserId); activeOwners.add(active)
            val respond = {
                val response = if (error != null) SettingsResult<NotificationSoundEncoded>(error = error)
                    else SettingsResult(NotificationSoundEncoded(transform(expected), byteArrayOf(1, 2, 3)))
                result(response)
                if (duplicate) result(response)
            }
            if (hold) pending.add(respond) else respond()
            return object : NotificationSoundFileLease {
                var closed = false
                override fun close() { if (!closed) { closed = true; closes++ } }
            }
        }
        fun release() { val copy = pending.toList(); pending.clear(); copy.forEach { it() } }
    }

    private companion object {
        fun sound(id: Long = -22, file: Int = 71) = SavedNotificationSound(id, 1, 10, "private title", "private data",
            MediaFile(file, uniqueId = "native-$file"))
    }
}
