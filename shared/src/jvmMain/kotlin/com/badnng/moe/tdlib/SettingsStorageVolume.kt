package com.badnng.moe.tdlib

import com.badnng.moe.settings.SettingsStorageVolume
import com.badnng.moe.settings.validSettingsStorageVolume
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/** This access is metadata-only: no directory listing, file contents or native request. */
internal fun interface SettingsStorageVolumeAccess {
    fun read(filesDirectory: Path): SettingsStorageVolume.Available?
}

/** Separate boundary permits synthetic path, failure and topology tests without a real volume. */
internal interface SettingsStorageVolumeMetadata {
    fun realPath(path: Path): Path
    fun isDirectory(path: Path): Boolean
    fun sample(path: Path): SettingsStorageVolumeSample
    fun identity(path: Path): Pair<String, String>
}

internal data class SettingsStorageVolumeSample(
    val name: String, val type: String, val total: Long, val usable: Long,
)

private object NioSettingsStorageVolumeMetadata : SettingsStorageVolumeMetadata {
    override fun realPath(path: Path): Path = path.toRealPath()
    override fun isDirectory(path: Path): Boolean = Files.isDirectory(path)
    override fun sample(path: Path): SettingsStorageVolumeSample = Files.getFileStore(path).let {
        SettingsStorageVolumeSample(it.name(), it.type(), it.totalSpace, it.usableSpace)
    }
    override fun identity(path: Path): Pair<String, String> = Files.getFileStore(path).let { it.name() to it.type() }
}

internal class NioSettingsStorageVolumeAccess(
    private val metadata: SettingsStorageVolumeMetadata = NioSettingsStorageVolumeMetadata,
) : SettingsStorageVolumeAccess {
    override fun read(filesDirectory: Path): SettingsStorageVolume.Available? {
        val first = metadata.realPath(filesDirectory)
        if (!metadata.isDirectory(first)) return null
        val sample = metadata.sample(first)
        val last = metadata.realPath(filesDirectory)
        if (last != first || !metadata.isDirectory(last)) return null
        if (metadata.identity(last) != (sample.name to sample.type)) return null
        val result = SettingsStorageVolume.Available(sample.total, sample.usable)
        return result.takeIf(::validSettingsStorageVolume)
    }
}

/**
 * One physical read for the whole process, with at most one waiting request. A page close
 * revokes its output, but cannot free the slot while the filesystem call is still running.
 * No callbacks or owner locks are entered under the coordinator lock.
 */
internal class SettingsStorageVolumeCoordinator(
    private val access: SettingsStorageVolumeAccess,
    private val worker: Executor,
) {
    private class Request(
        val path: Path,
        val owner: SettingsProxyProbeOwner,
        val result: (SettingsStorageVolume.Available?) -> Unit,
    ) {
        val delivered = AtomicBoolean()
    }

    private val lock = Any()
    private var flight: Request? = null
    private var pending: Request? = null

    fun submit(path: Path, owner: SettingsProxyProbeOwner, result: (SettingsStorageVolume.Available?) -> Unit) {
        val request = Request(path, owner, result)
        var displaced: Request? = null
        var start: Request? = null
        synchronized(lock) {
            if (flight == null) {
                flight = request
                start = request
            } else {
                displaced = pending
                pending = request
            }
        }
        displaced?.let { deliver(it, null) }
        start?.let(::start)
    }

    private fun start(request: Request) {
        if (!request.owner.current()) { finish(request, null); return }
        try {
            worker.execute {
                var value: SettingsStorageVolume.Available? = null
                try {
                    if (request.owner.current()) value = access.read(request.path)?.takeIf(::validSettingsStorageVolume)
                } catch (_: Exception) {
                    // An inaccessible, disappearing or unsupported filesystem is unknown.
                } finally {
                    finish(request, value)
                }
            }
        } catch (_: RejectedExecutionException) {
            // Executor contract: a rejected task has not been accepted for execution.
            finish(request, null)
        } catch (_: Exception) {
            // Any other scheduling failure may have happened after submission. Report failure
            // but retain the physical slot until the runnable really exits, if it was started.
            deliver(request, null)
        }
    }

    private fun finish(request: Request, value: SettingsStorageVolume.Available?) {
        val next = synchronized(lock) {
            if (flight !== request) return
            flight = pending
            pending = null
            flight
        }
        deliver(request, value)
        next?.let(::start)
    }

    private fun deliver(request: Request, value: SettingsStorageVolume.Available?) {
        if (request.delivered.compareAndSet(false, true)) runCatching { request.result(value) }
    }
}

/** The daemon executor is created once; idle storage pages do not schedule periodic work. */
internal object SharedSettingsStorageVolumeCoordinator {
    val instance = SettingsStorageVolumeCoordinator(NioSettingsStorageVolumeAccess(),
        Executors.newSingleThreadExecutor { task -> Thread(task, "settings-storage-volume").apply { isDaemon = true } })
}
