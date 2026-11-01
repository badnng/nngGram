package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.*

class SettingsStorageVolumeTest {
    @Test fun metadataUsesOnlyTheAcceptedPathAndRejectsInvalidOrChangingLocations() {
        val source = Path.of("synthetic", "account", "files")
        val resolved = Path.of("synthetic", "resolved")
        val metadata = Metadata(resolved)
        val access = NioSettingsStorageVolumeAccess(metadata)
        metadata.sample = SettingsStorageVolumeSample("test-store", "test-type", 1000, 400)
        assertEquals(SettingsStorageVolume.Available(1000, 400), access.read(source))
        assertEquals(listOf(source, source), metadata.resolutions)
        assertEquals(listOf(resolved), metadata.samples)
        assertEquals(listOf(resolved), metadata.identities)
        metadata.sample = metadata.sample.copy(usable = 0)
        assertEquals(SettingsStorageVolume.Available(1000, 0), access.read(source))
        for ((total, available) in listOf(0L to 0L, -1L to 0L, Long.MAX_VALUE to 0L,
            100L to -1L, 100L to 101L)) {
            metadata.sample = metadata.sample.copy(total = total, usable = available)
            assertNull(access.read(source))
        }
        metadata.sample = SettingsStorageVolumeSample("test-store", "test-type", 1000, 400)
        metadata.nextResolved = Path.of("synthetic", "other")
        assertNull(access.read(source), "A redirected files directory is not the sampled location.")
        metadata.nextResolved = null
        metadata.finalIdentity = "another-store" to "test-type"
        assertNull(access.read(source))
        metadata.directory = false
        assertNull(access.read(source))
    }

    @Test fun requestsCoalesceWithoutRunningASecondPhysicalRead() {
        val executor = ManualExecutor()
        val visited = mutableListOf<Path>()
        val coordinator = SettingsStorageVolumeCoordinator(SettingsStorageVolumeAccess {
            visited.add(it)
            SettingsStorageVolume.Available(500, 200)
        }, executor)
        val first = SettingsProxyProbeOwner { true }
        val middle = SettingsProxyProbeOwner { true }
        val last = SettingsProxyProbeOwner { true }
        val values = mutableListOf<Pair<String, SettingsStorageVolume.Available?>>()
        coordinator.submit(Path.of("first"), first) { values += "first" to it }
        coordinator.submit(Path.of("middle"), middle) { values += "middle" to it }
        coordinator.submit(Path.of("last"), last) { values += "last" to it }
        assertEquals(listOf<Pair<String, SettingsStorageVolume.Available?>>("middle" to null), values)
        assertEquals(1, executor.tasks.size)
        executor.runNext()
        assertEquals(listOf(Path.of("first")), visited)
        assertEquals(1, executor.tasks.size)
        executor.runNext()
        assertEquals(listOf(Path.of("first"), Path.of("last")), visited)
        assertEquals(SettingsStorageVolume.Available(500, 200), values.last().second)
        assertTrue(executor.tasks.isEmpty())
    }

    @Test fun closeDoesNotReleaseTheSlotBeforeBlockedMetadataActuallyReturns() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val done = CountDownLatch(2)
        val calls = AtomicInteger()
        val workers = CopyOnWriteArrayList<Thread>()
        val coordinator = SettingsStorageVolumeCoordinator(SettingsStorageVolumeAccess { path ->
            calls.incrementAndGet()
            if (path == Path.of("first")) {
                entered.countDown()
                check(release.await(2, TimeUnit.SECONDS))
            } else secondEntered.countDown()
            SettingsStorageVolume.Available(100, 40)
        }, Executor { task -> workers += thread(isDaemon = true) { task.run() } })
        val first = SettingsProxyProbeOwner { true }
        try {
            coordinator.submit(Path.of("first"), first) { done.countDown() }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            first.revoke()
            coordinator.submit(Path.of("second"), SettingsProxyProbeOwner { true }) { done.countDown() }
            assertFalse(secondEntered.await(50, TimeUnit.MILLISECONDS))
            assertEquals(1, calls.get())
            release.countDown()
            assertTrue(secondEntered.await(2, TimeUnit.SECONDS))
            assertTrue(done.await(2, TimeUnit.SECONDS))
            assertEquals(2, calls.get())
        } finally {
            release.countDown()
            workers.forEach { it.join(2000) }
        }
    }

    @Test fun uncertainSchedulingDoesNotPretendTheFirstIoTerminated() {
        val tasks = mutableListOf<Runnable>()
        var schedules = 0
        val executor = Executor { task ->
            tasks += task
            if (schedules++ == 0) throw IllegalStateException("synthetic uncertainty")
        }
        val calls = mutableListOf<Path>()
        val coordinator = SettingsStorageVolumeCoordinator(SettingsStorageVolumeAccess {
            calls.add(it)
            SettingsStorageVolume.Available(100, 40)
        }, executor)
        val results = mutableListOf<Pair<String, SettingsStorageVolume.Available?>>()
        coordinator.submit(Path.of("first"), SettingsProxyProbeOwner { true }) { results += "first" to it }
        coordinator.submit(Path.of("second"), SettingsProxyProbeOwner { true }) { results += "second" to it }
        assertEquals(listOf<Pair<String, SettingsStorageVolume.Available?>>("first" to null), results)
        assertEquals(1, tasks.size)
        tasks.removeAt(0).run()
        assertEquals(listOf(Path.of("first")), calls)
        assertEquals(1, tasks.size)
        tasks.removeAt(0).run()
        assertEquals(listOf(Path.of("first"), Path.of("second")), calls)
        assertEquals(2, results.size, "The uncertain first request reports at most once.")
    }

    @Test fun controllerSeparatesVolumeFromNativeStatisticsAndRejectsOldOwnersAndPaths() {
        val executor = ManualExecutor()
        val calls = mutableListOf<Path>()
        val coordinator = SettingsStorageVolumeCoordinator(SettingsStorageVolumeAccess {
            calls.add(it)
            when (it) {
                Path.of("bad") -> null
                Path.of("invalid") -> SettingsStorageVolume.Available(0, 0)
                Path.of("throw") -> error("synthetic inaccessible store")
                else -> SettingsStorageVolume.Available(1000, 250)
            }
        }, executor)
        var source: Path? = null
        var snapshot = SettingsStorageSnapshot()
        val session = StorageSession()
        val controller = SettingsStorageController(7, session, { it() }, { snapshot = it }, { source }, coordinator)
        controller.open()
        assertEquals(SettingsStorageVolume.Unavailable, snapshot.volume)
        assertEquals(1, session.reads)
        assertTrue(executor.tasks.isEmpty(), "No accepted native path means no filesystem request.")
        source = Path.of("first")
        controller.acceptedVolumeAvailable()
        assertEquals(SettingsStorageVolume.Loading, snapshot.volume)
        val old = snapshot.token
        controller.close(old)
        source = Path.of("second")
        controller.open()
        val current = snapshot.token
        assertNotEquals(old, current)
        assertEquals(1, executor.tasks.size)
        executor.runNext()
        assertTrue(calls.isEmpty(), "A closed request cannot start its not-yet-running IO.")
        assertEquals(SettingsStorageVolume.Loading, snapshot.volume)
        executor.runNext()
        assertEquals(listOf(Path.of("second")), calls)
        assertEquals(SettingsStorageVolume.Available(1000, 250), snapshot.volume)
        assertEquals(2, session.reads)
        source = Path.of("bad")
        controller.refreshVolume(current)
        executor.runNext()
        assertEquals(SettingsStorageVolume.Unavailable, snapshot.volume)
        assertEquals(2, session.reads, "Volume retry never initiates a TDLib statistics read.")
        for (unavailable in listOf("invalid", "throw")) {
            source = Path.of(unavailable)
            controller.refreshVolume(current)
            executor.runNext()
            assertEquals(SettingsStorageVolume.Unavailable, snapshot.volume)
        }
        source = Path.of("third")
        controller.refreshVolume(current)
        source = Path.of("changed-before-result")
        executor.runNext()
        assertEquals(SettingsStorageVolume.Unavailable, snapshot.volume, "A result from a different source is never published.")
        controller.refreshVolume(current)
        executor.runNext()
        assertEquals(SettingsStorageVolume.Available(1000, 250), snapshot.volume, "The current source can be retried.")
        controller.close(current)
        assertFalse(snapshot.opened)
    }

    private class Metadata(private val resolved: Path) : SettingsStorageVolumeMetadata {
        var nextResolved: Path? = null
        var finalIdentity = "test-store" to "test-type"
        var directory = true
        var sample = SettingsStorageVolumeSample("test-store", "test-type", 1000, 400)
        val resolutions = mutableListOf<Path>()
        val samples = mutableListOf<Path>()
        val identities = mutableListOf<Path>()
        override fun realPath(path: Path): Path {
            resolutions.add(path)
            return if (nextResolved != null && resolutions.size % 2 == 0) checkNotNull(nextResolved) else resolved
        }
        override fun isDirectory(path: Path) = directory
        override fun sample(path: Path): SettingsStorageVolumeSample { samples.add(path); return sample }
        override fun identity(path: Path): Pair<String, String> { identities.add(path); return finalIdentity }
    }

    private class ManualExecutor : Executor {
        val tasks = mutableListOf<Runnable>()
        override fun execute(command: Runnable) { tasks += command }
        fun runNext() = tasks.removeAt(0).run()
    }

    private class StorageSession : TdSettingsStorageSession {
        var reads = 0
        override fun storageDetails(active: () -> Boolean, result: (SettingsResult<SettingsStorageDetails>) -> Unit) {
            reads++
            result(SettingsResult(SettingsStorageDetails(0, 0, emptyList())))
        }
        override fun clearStorage(types: Set<SettingsStorageFileType>, active: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) =
            error("Volume tests do not clear storage")
    }
}
