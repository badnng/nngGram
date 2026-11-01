package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.*

/** No native client, filesystem contents or real FileStore are accessed by these tests. */
class SettingsStorageVolumeRecoveryTest {
    @Test
    fun filesystemFailuresStayUnknownUntilAnExplicitRetryAndDoNotRefreshCacheStatistics() {
        for (fault in Fault.entries) {
            val metadata = Metadata(fault)
            val worker = ManualExecutor()
            val coordinator = SettingsStorageVolumeCoordinator(NioSettingsStorageVolumeAccess(metadata), worker)
            val session = StorageSession()
            val history = mutableListOf<SettingsStorageSnapshot>()
            var snapshot = SettingsStorageSnapshot()
            val controller = SettingsStorageController(7, session, { it() }, {
                snapshot = it
                history += it
            }, { Path.of("synthetic", "files") }, coordinator)

            controller.open()
            val token = snapshot.token
            assertEquals(SettingsStorageVolume.Loading, snapshot.volume, fault.name)
            // A repeated action during Loading must not submit additional work.
            controller.refreshVolume(token)
            assertEquals(1, worker.tasks.size, fault.name)
            worker.runNext()
            assertEquals(SettingsStorageVolume.Unavailable, snapshot.volume, fault.name)
            assertTrue(worker.tasks.isEmpty(), "No periodic retry for $fault")
            assertEquals(1, session.reads, "A volume failure cannot request new TDLib statistics")
            assertTrue(history.none { it.toString().contains(PRIVATE_ERROR) }, "The error is not a UI snapshot")
            assertEquals(DETAILS, snapshot.details, "Volume failure cannot alter the cache summary")

            metadata.reset(null)
            controller.refreshVolume(token)
            assertEquals(SettingsStorageVolume.Loading, snapshot.volume)
            assertEquals(1, worker.tasks.size)
            worker.runNext()
            assertEquals(SettingsStorageVolume.Available(1000, 0), snapshot.volume,
                "Zero available space is a valid sampled value")
            assertEquals(1, session.reads, "Volume retry is independent of native statistics")
            assertEquals(DETAILS, snapshot.details)
            assertTrue(worker.tasks.isEmpty())
        }
    }

    @Test
    fun pathChangesBothBeforeSubmissionAndAfterIoBecomeRetryableWithoutPublishingOldValues() {
        val initial = Path.of("synthetic", "first", "files")
        val replacement = Path.of("synthetic", "second", "files")
        val worker = ManualExecutor()
        val visited = mutableListOf<Path>()
        val coordinator = SettingsStorageVolumeCoordinator(SettingsStorageVolumeAccess { path ->
            visited.add(path)
            if (path == initial) SettingsStorageVolume.Available(100, 75)
            else SettingsStorageVolume.Available(200, 40)
        }, worker)
        val session = StorageSession()
        var source: Path? = initial
        var changeOnLoading = true
        var snapshot = SettingsStorageSnapshot()
        val actor = ManualActor()
        val controller = SettingsStorageController(11, session, actor::enqueue, {
            snapshot = it
            if (changeOnLoading && it.volume is SettingsStorageVolume.Loading) {
                changeOnLoading = false
                source = replacement
            }
        }, { source }, coordinator)

        controller.open()
        actor.drain()
        val token = snapshot.token
        assertEquals(SettingsStorageVolume.Unavailable, snapshot.volume)
        assertTrue(worker.tasks.isEmpty(), "A path replaced during publication cannot be submitted")
        assertTrue(visited.isEmpty())

        controller.refreshVolume(token)
        assertEquals(SettingsStorageVolume.Loading, snapshot.volume)
        worker.runNext()
        // The IO finished, but the actor has not yet applied its result.
        source = null
        actor.drain()
        assertEquals(listOf(replacement), visited)
        assertEquals(SettingsStorageVolume.Unavailable, snapshot.volume)
        assertTrue(worker.tasks.isEmpty(), "Path loss is not an automatic retry")

        source = initial
        controller.refreshVolume(token)
        worker.runNext()
        actor.drain()
        assertEquals(listOf(replacement, initial), visited)
        assertEquals(SettingsStorageVolume.Available(100, 75), snapshot.volume)
        assertEquals(1, session.reads)
        assertEquals(DETAILS, snapshot.details)
    }

    @Test
    fun aRevokedVisitCannotPublishEvenWhenItsRealReadAlreadyStarted() {
        val firstPath = Path.of("synthetic", "old", "files")
        val nextPath = Path.of("synthetic", "new", "files")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val visited = CopyOnWriteArrayList<Path>()
        val worker = ManualExecutor()
        val coordinator = SettingsStorageVolumeCoordinator(SettingsStorageVolumeAccess { path ->
            visited.add(path)
            if (path == firstPath) {
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS))
                SettingsStorageVolume.Available(999, 888)
            } else SettingsStorageVolume.Available(500, 125)
        }, worker)
        val actor = ManualActor()
        var source = firstPath
        var snapshot = SettingsStorageSnapshot()
        val published = mutableListOf<SettingsStorageSnapshot>()
        val controller = SettingsStorageController(21, StorageSession(), actor::enqueue, {
            snapshot = it
            published += it
        }, { source }, coordinator)
        var oldWorker: Thread? = null
        try {
            controller.open()
            actor.drain()
            val oldToken = snapshot.token
            val started = thread(isDaemon = true) { worker.runNext() }
            oldWorker = started
            assertTrue(entered.await(2, TimeUnit.SECONDS), "The first physical read must have started")
            controller.close(oldToken)
            source = nextPath
            controller.open()
            actor.drain()
            val newToken = snapshot.token
            assertNotEquals(oldToken, newToken)
            assertEquals(SettingsStorageVolume.Loading, snapshot.volume)
            assertTrue(worker.tasks.isEmpty(), "Closing a page cannot free a live physical read")
            assertEquals(listOf(firstPath), visited.toList())

            release.countDown()
            started.join(2000)
            assertFalse(started.isAlive)
            assertEquals(1, worker.tasks.size, "The new visit starts only after the old IO exits")
            actor.drain()
            assertEquals(SettingsStorageVolume.Loading, snapshot.volume, "A late old value cannot replace the new owner")
            assertTrue(published.none { it.token == newToken && it.volume == SettingsStorageVolume.Available(999, 888) })
            worker.runNext()
            actor.drain()
            assertEquals(listOf(firstPath, nextPath), visited.toList())
            assertEquals(newToken, snapshot.token)
            assertEquals(SettingsStorageVolume.Available(500, 125), snapshot.volume)
        } finally {
            release.countDown()
            oldWorker?.join(2000)
        }
    }

    @Test
    fun mandatoryGateAndVolumePublicationHaveASingleCommitOrder() {
        // First, the gate closes while a completed read is waiting to enter the commit.
        run {
            val gate = TdUserInteractionGate()
            val atCommit = CountDownLatch(1)
            val releaseCommit = CountDownLatch(1)
            val actor = ManualActor()
            val worker = ManualExecutor()
            val states = CopyOnWriteArrayList<SettingsStorageSnapshot>()
            val controller = SettingsStorageController(31, StorageSession(), actor::enqueue, states::add,
                { Path.of("synthetic", "files") },
                SettingsStorageVolumeCoordinator(SettingsStorageVolumeAccess {
                    SettingsStorageVolume.Available(1000, 250)
                }, worker), volumeCommit = { work ->
                    atCommit.countDown()
                    check(releaseCommit.await(3, TimeUnit.SECONDS))
                    gate.dispatchStorage { work(); true }
                })
            controller.open()
            actor.drain()
            worker.runNext()
            val publishing = thread(isDaemon = true) { actor.drain() }
            try {
                assertTrue(atCommit.await(2, TimeUnit.SECONDS))
                gate.update(LoginEmailSuggestions(1, requiredPresent = true, requiredEpoch = 1))
                assertFalse(gate.allowsOrdinary())
                releaseCommit.countDown()
                publishing.join(2000)
                assertFalse(publishing.isAlive)
                assertTrue(states.none { it.volume is SettingsStorageVolume.Available },
                    "A gate that closed before commit cannot publish the completed read")
            } finally {
                releaseCommit.countDown()
                publishing.join(2000)
            }
        }

        // If publication already entered the gate, closing waits until that short commit exits.
        run {
            val gate = TdUserInteractionGate()
            val atSource = CountDownLatch(1)
            val releaseSource = CountDownLatch(1)
            val closeStarted = CountDownLatch(1)
            val closed = CountDownLatch(1)
            val pauseSource = AtomicBoolean(false)
            val events = CopyOnWriteArrayList<String>()
            val actor = ManualActor()
            val worker = ManualExecutor()
            val controller = SettingsStorageController(32, StorageSession(), actor::enqueue, {
                if (it.volume is SettingsStorageVolume.Available) events += "published"
            }, {
                if (pauseSource.get()) {
                    atSource.countDown()
                    check(releaseSource.await(3, TimeUnit.SECONDS))
                }
                Path.of("synthetic", "files")
            }, SettingsStorageVolumeCoordinator(SettingsStorageVolumeAccess {
                SettingsStorageVolume.Available(1000, 250)
            }, worker), volumeCommit = { work -> gate.dispatchStorage { work(); true } })
            controller.open()
            actor.drain()
            worker.runNext()
            pauseSource.set(true)
            val publishing = thread(isDaemon = true) { actor.drain() }
            var closer: Thread? = null
            try {
                assertTrue(atSource.await(2, TimeUnit.SECONDS))
                val closing = thread(isDaemon = true) {
                    closeStarted.countDown()
                    gate.update(LoginEmailSuggestions(1, requiredPresent = true, requiredEpoch = 1))
                    events += "closed"
                    closed.countDown()
                }
                closer = closing
                assertTrue(closeStarted.await(2, TimeUnit.SECONDS))
                assertFalse(closed.await(50, TimeUnit.MILLISECONDS), "The entered short commit owns the gate")
                releaseSource.countDown()
                publishing.join(2000)
                closing.join(2000)
                assertFalse(publishing.isAlive)
                assertFalse(closing.isAlive)
                assertEquals(listOf("published", "closed"), events.toList())
            } finally {
                releaseSource.countDown()
                publishing.join(2000)
                closer?.join(2000)
            }
        }
    }

    @Test
    fun nativeAuthorizationReadyAndVolumeCommitAreOrderedByTheSameGate() {
        for (authorizationFirst in listOf(true, false)) {
            val gate = TdUserInteractionGate()
            val ready = AtomicBoolean(true)
            val pauseInsideSource = AtomicBoolean(false)
            val atBoundary = CountDownLatch(1)
            val release = CountDownLatch(1)
            val authorizationUpdated = CountDownLatch(1)
            val events = CopyOnWriteArrayList<String>()
            val actor = ManualActor()
            val worker = ManualExecutor()
            val controller = SettingsStorageController(41, StorageSession(), actor::enqueue, {
                if (it.volume is SettingsStorageVolume.Available) events += "published"
            }, {
                if (!authorizationFirst && pauseInsideSource.get()) {
                    atBoundary.countDown()
                    check(release.await(3, TimeUnit.SECONDS))
                }
                Path.of("synthetic", "files")
            }, SettingsStorageVolumeCoordinator(SettingsStorageVolumeAccess {
                SettingsStorageVolume.Available(1000, 250)
            }, worker), volumeCommit = { work ->
                if (authorizationFirst) {
                    atBoundary.countDown()
                    check(release.await(3, TimeUnit.SECONDS))
                }
                gate.dispatchStorage {
                    if (!ready.get()) false else { work(); true }
                }
            })
            controller.open()
            actor.drain()
            worker.runNext()
            pauseInsideSource.set(true)
            val publishing = thread(isDaemon = true) { actor.drain() }
            var updating: Thread? = null
            try {
                assertTrue(atBoundary.await(2, TimeUnit.SECONDS))
                val update = thread(isDaemon = true) {
                    gate.updateAuthorizationReady { ready.set(false) }
                    events += "not-ready"
                    authorizationUpdated.countDown()
                }
                updating = update
                if (authorizationFirst) {
                    assertTrue(authorizationUpdated.await(2, TimeUnit.SECONDS))
                } else {
                    assertFalse(authorizationUpdated.await(50, TimeUnit.MILLISECONDS),
                        "Native authorization changes cannot cross a committed publication")
                }
                release.countDown()
                publishing.join(2000)
                update.join(2000)
                assertFalse(publishing.isAlive)
                assertFalse(update.isAlive)
                assertEquals(if (authorizationFirst) listOf("not-ready") else listOf("published", "not-ready"),
                    events.toList())
            } finally {
                release.countDown()
                publishing.join(2000)
                updating?.join(2000)
            }
        }
    }

    private enum class Fault { FIRST_PATH, FIRST_DIRECTORY, SAMPLE, SECOND_PATH, SECOND_DIRECTORY, IDENTITY }

    private class Metadata(private var fault: Fault?) : SettingsStorageVolumeMetadata {
        private var paths = 0
        private var directories = 0
        private val resolved = Path.of("synthetic", "resolved")
        fun reset(next: Fault?) { fault = next; paths = 0; directories = 0 }
        override fun realPath(path: Path): Path {
            paths++
            if (fault == (if (paths == 1) Fault.FIRST_PATH else Fault.SECOND_PATH)) throw IOException(PRIVATE_ERROR)
            return resolved
        }
        override fun isDirectory(path: Path): Boolean {
            directories++
            return fault != (if (directories == 1) Fault.FIRST_DIRECTORY else Fault.SECOND_DIRECTORY)
        }
        override fun sample(path: Path): SettingsStorageVolumeSample {
            if (fault == Fault.SAMPLE) throw IOException(PRIVATE_ERROR)
            return SettingsStorageVolumeSample("synthetic-store", "synthetic-type", 1000, 0)
        }
        override fun identity(path: Path): Pair<String, String> {
            if (fault == Fault.IDENTITY) throw IOException(PRIVATE_ERROR)
            return "synthetic-store" to "synthetic-type"
        }
    }

    private class ManualExecutor : Executor {
        val tasks = CopyOnWriteArrayList<Runnable>()
        override fun execute(command: Runnable) { tasks += command }
        fun runNext() = tasks.removeAt(0).run()
    }

    private class ManualActor {
        private val tasks = CopyOnWriteArrayList<() -> Unit>()
        fun enqueue(task: () -> Unit) { tasks += task }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeAt(0).invoke() }
    }

    private class StorageSession : TdSettingsStorageSession {
        var reads = 0
        override fun storageDetails(active: () -> Boolean, result: (SettingsResult<SettingsStorageDetails>) -> Unit) {
            reads++
            result(SettingsResult(DETAILS))
        }
        override fun clearStorage(types: Set<SettingsStorageFileType>, active: () -> Boolean,
            result: (SettingsResult<Unit>) -> Unit) = error("The volume tests do not clear storage")
    }

    private companion object {
        const val PRIVATE_ERROR = "synthetic-private-path"
        val DETAILS = SettingsStorageDetails(30, 3, listOf(SettingsStorageCategory(SettingsStorageFileType.PHOTO, 30, 3)))
    }
}
