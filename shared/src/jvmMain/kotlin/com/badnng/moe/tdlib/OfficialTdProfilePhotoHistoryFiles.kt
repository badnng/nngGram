package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Only the native page reader implements this authority; callers cannot register files or paths. */
internal fun interface ProfilePhotoHistoryCatalog {
    fun resolve(request: SettingsProfilePhotoHistoryAuthorizedFileRequest): SettingsResult<SettingsProfilePhotoVariant>
    fun resolveAnimation(request: SettingsProfilePhotoHistoryAuthorizedFileRequest): SettingsResult<SettingsProfilePhotoAnimation> =
        SettingsResult(error = SettingsError.UNSUPPORTED)
    /** Only this helper calls this with a fresh native GetFile result, never caller-supplied metadata. */
    fun observeFile(request: SettingsProfilePhotoHistoryAuthorizedFileRequest, actual: MediaFile): SettingsResult<Unit> =
        SettingsResult(Unit)
}

internal fun selectProfilePhotoHistoryVariant(variants: List<SettingsProfilePhotoVariant>,
    request: SettingsProfilePhotoHistoryAuthorizedFileRequest,
): SettingsResult<SettingsProfilePhotoVariant> {
    if (request.use == SettingsProfilePhotoHistoryMediaUse.EXPLICIT_ANIMATION)
        return SettingsResult(error = SettingsError.UNSUPPORTED)
    if (request.targetWidthPx !in 1..4096 || request.targetHeightPx !in 1..4096 ||
        request.targetWidthPx.toLong() * request.targetHeightPx > 24_000_000)
        return SettingsResult(error = SettingsError.INVALID_VALUE)
    val cap = if (request.use == SettingsProfilePhotoHistoryMediaUse.VISIBLE_CELL) 8L * 1024 * 1024 else 24L * 1024 * 1024
    val valid = variants.filter { it.width > 0 && it.height > 0 && it.width.toLong() * it.height <= 24_000_000 &&
        it.file.id > 0 && it.file.uniqueId.isNotBlank() && it.file.size in 0..cap }
    val area: (SettingsProfilePhotoVariant) -> Long = { it.width.toLong() * it.height }
    val selected = if (request.use == SettingsProfilePhotoHistoryMediaUse.VISIBLE_CELL) {
        val small = valid.filter { it.width <= 512 && it.height <= 512 }
        small.filter { it.width >= request.targetWidthPx && it.height >= request.targetHeightPx }.minByOrNull(area)
            ?: small.maxByOrNull(area)
    } else valid.maxByOrNull(area)
    return selected?.let { SettingsResult(it) } ?: SettingsResult(error = SettingsError.UNSUPPORTED)
}

private const val ANIMATION_BYTES = 24L * 1024 * 1024

private fun validHistoryAnimation(value: SettingsProfilePhotoAnimation, small: Boolean = false) =
    (if (small) value.length == 160 else value.length in 1..1280) &&
        value.mainFrameTimestampSeconds.isFinite() && value.mainFrameTimestampSeconds >= 0.0 &&
        value.file.id > 0 && value.file.uniqueId.length <= 1024 &&
        value.file.size in 0..ANIMATION_BYTES && value.file.expectedSize >= 0 && value.file.downloadedSize >= 0

internal fun selectProfilePhotoHistoryAnimation(entry: SettingsProfilePhotoHistoryEntry,
    request: SettingsProfilePhotoHistoryAuthorizedFileRequest,
): SettingsResult<SettingsProfilePhotoAnimation> {
    if (request.use != SettingsProfilePhotoHistoryMediaUse.EXPLICIT_ANIMATION)
        return SettingsResult(error = SettingsError.UNSUPPORTED)
    if (request.targetWidthPx !in 1..4096 || request.targetHeightPx !in 1..4096 ||
        request.targetWidthPx.toLong() * request.targetHeightPx > 24_000_000)
        return SettingsResult(error = SettingsError.INVALID_VALUE)
    if (entry.invalidAnimation) return SettingsResult(error = SettingsError.INVALID_VALUE)
    val large = entry.animation
    if (large != null) return if (validHistoryAnimation(large)) SettingsResult(large)
        else SettingsResult(error = SettingsError.INVALID_VALUE)
    if (entry.invalidSmallAnimation) return SettingsResult(error = SettingsError.INVALID_VALUE)
    val small = entry.smallAnimation ?: return SettingsResult(error = SettingsError.UNSUPPORTED)
    return if (validHistoryAnimation(small, small = true)) SettingsResult(small)
        else SettingsResult(error = SettingsError.INVALID_VALUE)
}

private sealed class HistoryFileAsset {
    abstract val file: MediaFile
    data class Still(val variant: SettingsProfilePhotoVariant) : HistoryFileAsset() {
        override val file get() = variant.file
    }
    data class Animation(val variant: SettingsProfilePhotoAnimation) : HistoryFileAsset() {
        override val file get() = variant.file
    }
}

private sealed class ProfilePhotoFileRequest {
    abstract val width: Int
    abstract val height: Int
    abstract val animation: Boolean
    abstract val cell: Boolean
    data class History(val value: SettingsProfilePhotoHistoryAuthorizedFileRequest) : ProfilePhotoFileRequest() {
        override val width get() = value.targetWidthPx
        override val height get() = value.targetHeightPx
        override val animation get() = value.use == SettingsProfilePhotoHistoryMediaUse.EXPLICIT_ANIMATION
        override val cell get() = value.use == SettingsProfilePhotoHistoryMediaUse.VISIBLE_CELL
    }
    data class Current(val value: SettingsCurrentProfilePhotoAuthorizedFileRequest) : ProfilePhotoFileRequest() {
        override val width get() = value.targetWidthPx
        override val height get() = value.targetHeightPx
        override val animation get() = value.use == SettingsCurrentProfilePhotoMediaUse.ANIMATION_PREVIEW
        override val cell get() = false
    }
}

/** Session-borrowed broker, two finite GetFile checks, and independently disposable demand. */
internal class OfficialTdProfilePhotoHistoryFiles(
    private val catalog: ProfilePhotoHistoryCatalog,
    private val downloads: TdDownloadBroker,
    private val send: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit,
    private val schedule: (Long, () -> Unit) -> Unit = DownloadBrokerScheduler::schedule,
    private val now: () -> Long = { System.nanoTime() / 1_000_000L },
    private val currentCatalog: CurrentProfilePhotoCatalog? = null,
) {
    private val alive = AtomicBoolean(true)
    private val requests = ConcurrentHashMap<Long, Read>()
    private val admission = Any()

    fun read(request: SettingsProfilePhotoHistoryAuthorizedFileRequest, active: () -> Boolean,
        result: (SettingsResult<MediaFile>) -> Unit,
    ): SettingsProfilePhotoHistoryFileLease = start(ProfilePhotoFileRequest.History(request), active, result)

    fun readCurrent(request: SettingsCurrentProfilePhotoAuthorizedFileRequest, active: () -> Boolean,
        result: (SettingsResult<MediaFile>) -> Unit,
    ): SettingsCurrentProfilePhotoFileLease = start(ProfilePhotoFileRequest.Current(request), active, result)

    private fun start(request: ProfilePhotoFileRequest, active: () -> Boolean,
        result: (SettingsResult<MediaFile>) -> Unit,
    ): Read {
        val read = Read(nextLease.incrementAndGet(), request, active, result)
        val current = read.current()
        val error = synchronized(admission) {
            when {
                !current || !alive.get() -> SettingsError.NOT_READY
                requests.size >= MAX_READERS -> SettingsError.RATE_LIMITED
                else -> { requests[read.id] = read; null }
            }
        }
        if (error != null) read.finish(SettingsResult(error = error)) else read.attempt { read.start() }
        return read
    }

    fun retire() {
        val pending = synchronized(admission) {
            if (!alive.compareAndSet(true, false)) return
            requests.values.toList()
        }
        pending.forEach { it.close() }
    }

    private inner class Read(val id: Long, private val request: ProfilePhotoFileRequest,
        private val active: () -> Boolean, private val result: (SettingsResult<MediaFile>) -> Unit,
    ) : SettingsProfilePhotoHistoryFileLease, SettingsCurrentProfilePhotoFileLease {
        val done = AtomicBoolean()
        private val allowed = AtomicBoolean(true)
        private val selected = java.util.concurrent.atomic.AtomicReference<HistoryFileAsset?>()
        private val identityLock = Any()
        private var observedSize = 0L
        private var observedUnique: String? = null
        private val owner = MediaDownloadOwner(ChatToken(0, 0, null), 0, MediaDownloadPurpose.PROFILE_PHOTO_HISTORY, id)
        private val started = now()
        private val maximumBytes = if (request.cell) CELL_BYTES else PREVIEW_BYTES
        // The broker holds its monitor when reading this predicate: captured atomics only.
        private val brokerActive = { alive.get() && allowed.get() && !done.get() }

        fun current(): Boolean {
            if (!brokerActive()) return false
            if (!runCatching(active).getOrDefault(false)) allowed.set(false)
            return brokerActive()
        }

        override fun close() { allowed.set(false); finish(SettingsResult(error = SettingsError.NOT_READY)) }

        fun finish(value: SettingsResult<MediaFile>) {
            if (!done.compareAndSet(false, true)) return
            allowed.set(false)
            requests.remove(id, this)
            selected.get()?.let { runCatching { downloads.release(owner, it.file.id) } }
            runCatching { result(value) }
        }

        fun attempt(action: () -> Unit) {
            try { action() } catch (_: Exception) { finish(SettingsResult(error = SettingsError.REQUEST_FAILED)) }
        }

        fun start() {
            if (!current()) { close(); return }
            val response = resolveAsset()
            val asset = response.value ?: run { finish(SettingsResult(error = response.error ?: SettingsError.CONFLICT)); return }
            selected.set(asset)
            later(TIMEOUT_MILLIS) {
                if (!current()) close() else finish(SettingsResult(error = SettingsError.REQUEST_FAILED))
            }
            nativeFile(asset.file) first@ { fresh ->
                if (!current() || !authorized(asset)) { close(); return@first }
                val once = AtomicBoolean()
                downloads.boundedFull(owner, fresh, maximumBytes, brokerActive) downloaded@ { response ->
                    if (!once.compareAndSet(false, true) || done.get()) return@downloaded
                    // No source, actor, caller, result, or release callback runs under the broker monitor.
                    afterBroker accepted@ {
                        if (!current()) { close(); return@accepted }
                        val file = response.value
                        if (response.error != null || file == null) {
                            finish(SettingsResult(error = error(response.error))); return@accepted
                        }
                        val mismatch = checkFile(asset.file, file)
                        if (mismatch != null) { finish(SettingsResult(error = mismatch)); return@accepted }
                        if (!file.downloaded || file.localPath.isNullOrBlank()) { conflict(); return@accepted }
                        nativeFile(asset.file) final@ { verified ->
                            if (!current()) { close(); return@final }
                            if (!authorized(asset)) { conflict(); return@final }
                            if (!verified.downloaded || verified.size !in 1..maximumBytes.toLong() ||
                                verified.localPath.isNullOrBlank() || verified.localPath?.let(::readable) != true) { conflict(); return@final }
                            finish(SettingsResult(verified))
                        }
                    }
                }
            }
        }

        /** Reapply all bounds even if an internal catalog implementation is broken. */
        private fun resolveAsset(): SettingsResult<HistoryFileAsset> {
            if (request.animation) {
                val response = when (request) {
                    is ProfilePhotoFileRequest.History -> catalog.resolveAnimation(request.value)
                    is ProfilePhotoFileRequest.Current -> currentCatalog?.resolveAnimation(request.value)
                        ?: SettingsResult(error = SettingsError.UNSUPPORTED)
                }
                val value = response.value ?: return SettingsResult(error = response.error ?: SettingsError.CONFLICT)
                if (request.width !in 1..4096 || request.height !in 1..4096 ||
                    request.width.toLong() * request.height > 24_000_000 || !validHistoryAnimation(value))
                    return SettingsResult(error = SettingsError.INVALID_VALUE)
                return SettingsResult(HistoryFileAsset.Animation(value))
            }
            val response = when (request) {
                is ProfilePhotoFileRequest.History -> catalog.resolve(request.value)
                is ProfilePhotoFileRequest.Current -> currentCatalog?.resolve(request.value)
                    ?: SettingsResult(error = SettingsError.UNSUPPORTED)
            }
            val value = response.value ?: return SettingsResult(error = response.error ?: SettingsError.CONFLICT)
            val checked = when (request) {
                is ProfilePhotoFileRequest.History -> selectProfilePhotoHistoryVariant(listOf(value), request.value)
                is ProfilePhotoFileRequest.Current -> selectCurrentProfilePhotoVariant(listOf(value), request.value)
            }
            if (checked.value != value)
                return SettingsResult(error = SettingsError.CONFLICT)
            return SettingsResult(HistoryFileAsset.Still(value))
        }

        private fun authorized(asset: HistoryFileAsset): Boolean = resolveAsset().value == asset

        private fun nativeFile(expected: MediaFile, completed: (MediaFile) -> Unit) {
            if (!current()) { close(); return }
            if (now() - started >= TIMEOUT_MILLIS) { finish(SettingsResult(error = SettingsError.REQUEST_FAILED)); return }
            val once = AtomicBoolean()
            send(TdApi.GetFile(expected.id), received@ { response ->
                if (!once.compareAndSet(false, true) || done.get()) return@received
                attempt {
                    if (!current()) { close(); return@attempt }
                    val native = response as? TdApi.File
                    val actual = native?.takeIf { it.size >= 0 && (it.local?.downloadedSize ?: 0) >= 0 }?.let(::mapMediaFile)
                    if (actual == null) { finish(SettingsResult(error = settingsError(response))); return@attempt }
                    val observed = when (request) {
                        is ProfilePhotoFileRequest.History -> catalog.observeFile(request.value, actual)
                        is ProfilePhotoFileRequest.Current -> currentCatalog?.observeFile(request.value, actual)
                            ?: SettingsResult(error = SettingsError.UNSUPPORTED)
                    }
                    if (observed.value != Unit || observed.error != null) {
                        finish(SettingsResult(error = observed.error ?: SettingsError.CONFLICT)); return@attempt
                    }
                    val mismatch = checkFile(expected, actual)
                    if (mismatch != null) { finish(SettingsResult(error = mismatch)); return@attempt }
                    completed(actual)
                }
            }, { if (once.compareAndSet(false, true)) finish(SettingsResult(error = SettingsError.NOT_READY)) })
        }

        private fun checkFile(expected: MediaFile, actual: MediaFile): SettingsError? {
            if (actual.id != expected.id || selected.get() is HistoryFileAsset.Animation && actual.uniqueId.length > 1024 ||
                actual.size !in 0..maximumBytes.toLong() || actual.downloadedSize !in 0..maximumBytes.toLong() ||
                expected.size !in 0..maximumBytes.toLong()) return SettingsError.CONFLICT
            // A zero native size means unknown, not permission for different known sizes later.
            // expectedSize is an estimate and never participates in identity. Only the
            // animation use may defer an unknown canonical unique ID to the first GetFile.
            return synchronized(identityLock) {
                val sourceUnique = expected.uniqueId.takeIf { it.isNotBlank() }
                val allowedDeferred = selected.get() is HistoryFileAsset.Animation && sourceUnique == null
                if (actual.uniqueId.isBlank()) return@synchronized if (allowedDeferred && observedUnique == null)
                    SettingsError.NOT_READY else SettingsError.CONFLICT
                if (sourceUnique != null && actual.uniqueId != sourceUnique ||
                    observedUnique != null && actual.uniqueId != observedUnique ||
                    !allowedDeferred && sourceUnique == null) return@synchronized SettingsError.CONFLICT
                if (expected.size > 0 && observedSize > 0 && expected.size != observedSize)
                    return@synchronized SettingsError.CONFLICT
                val known = if (observedSize > 0) observedSize else expected.size
                if (known > 0 && actual.size != known) return@synchronized SettingsError.CONFLICT
                observedUnique = actual.uniqueId
                if (actual.size > 0) observedSize = actual.size
                null
            }
        }
        private fun conflict() = finish(SettingsResult(error = SettingsError.CONFLICT))
        private fun later(delay: Long, action: () -> Unit) = attempt {
            schedule(delay) { if (!done.get()) attempt(action) }
        }
        private fun afterBroker(action: () -> Unit) {
            try { schedule(0) { if (!done.get()) attempt(action) } }
            catch (_: Exception) {
                // Even a broken injected scheduler must not publish or release under the broker lock.
                DownloadBrokerScheduler.schedule(0) { finish(SettingsResult(error = SettingsError.REQUEST_FAILED)) }
            }
        }
    }

    private fun readable(path: String) = runCatching { Path.of(path).let { Files.isRegularFile(it) && Files.isReadable(it) } }.getOrDefault(false)
    private fun error(error: ChatError?) = when (error) {
        ChatError.NOT_READY -> SettingsError.NOT_READY
        ChatError.FORBIDDEN -> SettingsError.FORBIDDEN
        ChatError.RATE_LIMITED -> SettingsError.RATE_LIMITED
        else -> SettingsError.REQUEST_FAILED
    }

    private companion object {
        const val CELL_BYTES = 8 * 1024 * 1024
        const val PREVIEW_BYTES = 24 * 1024 * 1024
        const val MAX_READERS = 8
        const val TIMEOUT_MILLIS = 30_000L
        val nextLease = AtomicLong()
    }
}
