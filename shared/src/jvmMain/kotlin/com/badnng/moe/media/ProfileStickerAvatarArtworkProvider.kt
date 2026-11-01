package com.badnng.moe.media

import androidx.compose.ui.graphics.ImageBitmap
import com.badnng.moe.chat.MediaFormat
import com.badnng.moe.settings.*
import com.badnng.moe.tdlib.ProfileStickerAvatarEncoded
import com.badnng.moe.tdlib.ProfileStickerAvatarMediaRole
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** The adapter must enter the Engine actor and validate parent plus the private media grant. */
internal interface ProfileStickerAvatarEncodedReader {
    fun thumbnail(parent: SettingsProfileExtrasToken, picker: ProfileStickerAvatarPickerTicket,
        candidate: ProfileStickerAvatarCandidateTicket, active: () -> Boolean,
        result: (SettingsResult<ProfileStickerAvatarEncoded>) -> Unit): AutoCloseable
    fun preview(parent: SettingsProfileExtrasToken, picker: ProfileStickerAvatarPickerTicket,
        selection: ProfileStickerAvatarSelectionTicket, active: () -> Boolean,
        result: (SettingsResult<ProfileStickerAvatarEncoded>) -> Unit): AutoCloseable
    fun savedPreview(request: SettingsSavedProfileStickerAvatarRequest, active: () -> Boolean,
        result: (SettingsResult<ProfileStickerAvatarEncoded>) -> Unit): SettingsSavedProfileStickerAvatarLease {
        runCatching { if (active()) result(SettingsResult(error = SettingsError.UNSUPPORTED)) }
        return object : SettingsSavedProfileStickerAvatarLease {
            override fun requestCurrent() = false
            override fun close() {}
        }
    }
    fun savedPreviewOwned(request: SettingsSavedProfileStickerAvatarRequest, active: () -> Boolean,
        demand: SettingsSavedProfileStickerAvatarDemand,
        result: (SettingsResult<ProfileStickerAvatarEncoded>) -> Unit): SettingsSavedProfileStickerAvatarLease =
        savedPreview(request, { demand.current() && active() }, result)
}

/** Dispatch and all owner changes must be serialized on the UI thread. Discard runs if unavailable. */
internal fun interface ProfileStickerAvatarUiDispatch {
    fun post(deliver: () -> Unit, discard: () -> Unit)
}

internal interface ProfileStickerAvatarCodec {
    fun thumbnail(encoded: ProfileStickerAvatarEncoded, edge: Int, active: () -> Boolean,
        permit: ArtworkWorkerPermit): ArtworkFrame
    fun open(encoded: ProfileStickerAvatarEncoded, edge: Int, active: () -> Boolean,
        permit: ArtworkWorkerPermit): ArtworkCodecClip
}

internal interface ArtworkCodecClip : AutoCloseable {
    val animated: Boolean
    val durationMillis: Long
    val frameDurationMillis: Long
    fun frame(timeMillis: Long, active: () -> Boolean, permit: ArtworkWorkerPermit): ArtworkFrame
}

/** Shared immutable prepared pixels. Each successful consumer gets one reference. */
internal class ArtworkFrame(
    val image: ImageBitmap,
    val needsRepainting: Boolean,
    private val dispose: () -> Unit,
) : AutoCloseable {
    private val lock = Any()
    private var references = 1
    fun retain(): ArtworkFrame? = synchronized(lock) {
        if (references == 0) null else { references++; this }
    }
    override fun close() {
        val last = synchronized(lock) { check(references > 0); references--; references == 0 }
        if (last) dispose()
    }
}

/** A physical worker slot is retained independently by any child process that may outlive it. */
internal class ArtworkWorkerPermit(private val releaseSlot: () -> Unit) {
    private val lock = Any()
    private var references = 1
    fun retain(): Boolean = synchronized(lock) {
        if (references == 0) false else { references++; true }
    }
    fun release() {
        val last = synchronized(lock) { check(references > 0); references--; references == 0 }
        if (last) releaseSlot()
    }
}

/** Queue entries always run their cleanup; no Future.cancel can release an executing task. */
internal class ProfileStickerAvatarArtworkWorkers : AutoCloseable {
    enum class Admission { ACCEPTED, REJECTED, UNKNOWN }
    private val slots = Semaphore(2)
    private class Task(val work: (ArtworkWorkerPermit) -> Unit, val exited: (Boolean) -> Unit) : Runnable {
        lateinit var slots: Semaphore
        private var permit: ArtworkWorkerPermit? = null
        private val decision = CountDownLatch(1)
        @Volatile private var admission = Admission.UNKNOWN
        fun decided(value: Admission) { admission = value; decision.countDown() }
        override fun run() {
            slots.acquireUninterruptibly()
            val actual = ArtworkWorkerPermit { slots.release() }
            permit = actual
            work(actual)
        }
        fun afterExit() {
            var interrupted = false
            while (true) {
                try { decision.await(); break } catch (_: InterruptedException) { interrupted = true }
            }
            try { permit?.release() } finally {
                try { exited(admission == Admission.ACCEPTED) }
                finally { if (interrupted) Thread.currentThread().interrupt() }
            }
        }
    }
    private val executor = object : ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(8), { work -> Thread(work, "profile-avatar-artwork").apply { isDaemon = true } }) {
        override fun afterExecute(work: Runnable, failure: Throwable?) {
            super.afterExecute(work, failure)
            runCatching { (work as Task).afterExit() }
        }
    }
    fun submit(work: (ArtworkWorkerPermit) -> Unit, exited: (Boolean) -> Unit = {}): Admission {
        val task = Task(work, exited).also { it.slots = slots }
        val result = try { executor.execute(task); Admission.ACCEPTED }
        catch (_: RejectedExecutionException) { Admission.REJECTED }
        catch (_: Throwable) { Admission.UNKNOWN }
        task.decided(result)
        return result
    }
    override fun close() { executor.shutdown() }
}

internal class ProfileStickerAvatarArtworkBusy : IllegalStateException("Artwork resource budget exhausted")

private object SharedProfileStickerAvatarArtworkWorkers {
    val instance = ProfileStickerAvatarArtworkWorkers()
}

/** Independent per-generation provider; it does not consult profile-color authority or cache. */
internal class JvmProfileStickerAvatarArtworkProvider(
    override val generation: Long,
    private val reader: ProfileStickerAvatarEncodedReader,
    private val ui: ProfileStickerAvatarUiDispatch,
    private val codec: ProfileStickerAvatarCodec = SkiaProfileStickerAvatarCodec(),
    private val workers: ProfileStickerAvatarArtworkWorkers = SharedProfileStickerAvatarArtworkWorkers.instance,
) : ProfileStickerAvatarArtworkProvider {
    private val closed = AtomicBoolean()
    private val scopes = ConcurrentHashMap.newKeySet<Scope>()
    private val savedOwners = ConcurrentHashMap.newKeySet<SavedPreviewOwner>()
    private val admissions = Any()

    private inner class Scope(val active: () -> Boolean) : ProfileStickerAvatarArtworkLease {
        val alive = AtomicBoolean(true)
        private val source = java.util.concurrent.atomic.AtomicReference<() -> Boolean>({ true })
        private val lock = Any()
        private var read: AutoCloseable? = null
        private var readFinished = false
        private val children = linkedSetOf<AutoCloseable>()
        fun current(): Boolean = alive.get() && !closed.get() && safe(active) && safe(source.get()) && alive.get() && !closed.get()
        fun bindSource(value: () -> Boolean) { source.set(value) }
        fun attachRead(value: AutoCloseable) {
            val reject = synchronized(lock) { if (!alive.get() || readFinished) true else { read = value; false } }
            if (reject) runCatching { value.close() }
        }
        fun finishRead() {
            val old = synchronized(lock) { readFinished = true; read.also { read = null } }
            runCatching { old?.close() }
        }
        fun add(value: AutoCloseable): Boolean = synchronized(lock) {
            if (!alive.get()) false else { children.add(value); true }
        }
        fun remove(value: AutoCloseable) { synchronized(lock) { children.remove(value) } }
        override fun close() {
            if (!alive.compareAndSet(true, false)) return
            val (oldRead, oldChildren) = synchronized(lock) {
                readFinished = true
                (read.also { read = null }) to children.toList().also { children.clear() }
            }
            scopes.remove(this)
            runCatching { oldRead?.close() }
            oldChildren.forEach { runCatching { it.close() } }
        }
    }

    private fun scope(parent: SettingsProfileExtrasToken, active: () -> Boolean): Scope? {
        if (!safe(active)) return null
        return synchronized(admissions) {
            if (closed.get() || generation <= 0 || parent.generation != generation || scopes.size >= 64) null
            else Scope(active).also { scopes.add(it) }
        }
    }

    override fun thumbnail(parent: SettingsProfileExtrasToken, picker: ProfileStickerAvatarPickerTicket,
        candidate: ProfileStickerAvatarCandidateTicket, physicalSizePx: Int, active: () -> Boolean,
        result: (SettingsResult<ProfileStickerAvatarArtwork>) -> Unit): ProfileStickerAvatarArtworkLease {
        val owner = scope(parent, active) ?: return rejected(result, SettingsError.NOT_READY, active)
        if (physicalSizePx !in 1..512) { fail(owner, result, SettingsError.INVALID_VALUE); return owner }
        val output = DisplayLease(owner, active)
        if (!owner.add(output)) return owner
        read(owner, ProfileStickerAvatarMediaRole.THUMBNAIL,
            { error -> fail(owner, result, error) },
            { callback -> reader.thumbnail(parent, picker, candidate, owner::current, callback) }) { encoded ->
            var frame: ArtworkFrame? = null
            var error: SettingsError? = null
            val queued = workers.submit({ permit ->
                try {
                    if (!output.current()) throw InterruptedException()
                    frame = codec.thumbnail(encoded, physicalSizePx, output::current, permit)
                } catch (failure: Throwable) { error = mapFailure(failure) }
            }, { accepted ->
                encoded.close()
                if (accepted) publishFrame(output, frame, error, result, onFailure = owner::close)
                else frame?.close()
            })
            when (queued) {
                ProfileStickerAvatarArtworkWorkers.Admission.REJECTED -> { encoded.close(); fail(owner, result, SettingsError.RATE_LIMITED) }
                ProfileStickerAvatarArtworkWorkers.Admission.UNKNOWN -> fail(owner, result, SettingsError.REQUEST_FAILED)
                ProfileStickerAvatarArtworkWorkers.Admission.ACCEPTED -> Unit
            }
        }
        return owner
    }

    override fun preview(parent: SettingsProfileExtrasToken, picker: ProfileStickerAvatarPickerTicket,
        selection: ProfileStickerAvatarSelectionTicket, physicalSizePx: Int, active: () -> Boolean,
        result: (SettingsResult<ProfileStickerAvatarPreviewClip>) -> Unit): ProfileStickerAvatarArtworkLease =
        fullPreview(parent, physicalSizePx, active, result) { source, callback ->
            reader.preview(parent, picker, selection, source, callback)
        }

    override fun savedPreview(request: SettingsSavedProfileStickerAvatarRequest, physicalSizePx: Int, active: () -> Boolean,
        result: (SettingsResult<ProfileStickerAvatarPreviewClip>) -> Unit): ProfileStickerAvatarSavedPreviewLease =
        savedPreviewOwned(request, physicalSizePx, active, SettingsSavedProfileStickerAvatarDemand(), result)

    override fun savedPreviewOwned(request: SettingsSavedProfileStickerAvatarRequest, physicalSizePx: Int,
        active: () -> Boolean, demand: SettingsSavedProfileStickerAvatarDemand,
        result: (SettingsResult<ProfileStickerAvatarPreviewClip>) -> Unit): ProfileStickerAvatarSavedPreviewLease {
        val current = { demand.current() && safe(active) }
        if (!current()) return rejectedSaved(result, SettingsError.NOT_READY, current)
        val saved = SavedPreviewOwner(active, demand)
        val accepted = synchronized(admissions) {
            if (closed.get() || savedOwners.size >= 64) false else { savedOwners.add(saved); true }
        }
        if (!accepted) return rejectedSaved(result, if (closed.get()) SettingsError.NOT_READY else SettingsError.RATE_LIMITED, current)
        val delegate = fullPreview(request.parent, physicalSizePx, saved::sourceCurrent, { response ->
            if (saved.requestCurrent()) {
                try { result(response) } catch (failure: Throwable) { saved.close(); throw failure }
            } else response.value?.close()
        }) { _, callback ->
            readSaved(request, saved, callback)
        }
        saved.bindDelegate(delegate)
        return saved
    }

    private fun rejectedSaved(result: (SettingsResult<ProfileStickerAvatarPreviewClip>) -> Unit, error: SettingsError,
        current: () -> Boolean): ProfileStickerAvatarSavedPreviewLease {
        val rejected = rejected(result, error, current)
        return object : ProfileStickerAvatarSavedPreviewLease {
            override fun requestCurrent() = false
            override fun close() = rejected.close()
        }
    }

    /** Caller/source is distinct from the output scope, so a FAILED output retains its source witness. */
    private inner class SavedPreviewOwner(private val caller: () -> Boolean,
        val demand: SettingsSavedProfileStickerAvatarDemand) : ProfileStickerAvatarSavedPreviewLease {
        private val live = AtomicBoolean(true)
        private val callerLive = AtomicBoolean(true)
        private val witnessLive = AtomicBoolean(true)
        private val lock = Any()
        private var delegate: ProfileStickerAvatarArtworkLease? = null
        private var native: SettingsSavedProfileStickerAvatarLease? = null
        private var encodedSource: (() -> Boolean)? = null
        fun sourceCurrent(): Boolean {
            if (!live.get() || !callerLive.get() || closed.get()) return false
            if (!safe(caller)) callerLive.set(false)
            return live.get() && callerLive.get() && !closed.get()
        }
        override fun requestCurrent(): Boolean {
            if (!sourceCurrent() || !witnessLive.get()) return false
            val (read, source) = synchronized(lock) { native to encodedSource }
            val current = (read?.let { runCatching(it::requestCurrent).getOrDefault(false) } == true ||
                source?.let(::safe) == true) && sourceCurrent()
            if (!current) witnessLive.set(false)
            return current && witnessLive.get()
        }
        fun bindNative(value: SettingsSavedProfileStickerAvatarLease) {
            val reject = synchronized(lock) { if (!live.get()) true else { native = value; false } }
            if (reject) runCatching { value.close() }
            else if (!demand.current()) runCatching { value.revokeDemand() }
        }
        fun demandCurrent() = demand.current() && sourceCurrent()
        override fun revokeDemand() {
            demand.revokeDemand()
            val read = synchronized(lock) { native }
            runCatching { read?.revokeDemand() }
        }
        fun bindSource(value: () -> Boolean) { synchronized(lock) { if (live.get()) encodedSource = value } }
        fun finishSuccessfulRead() {
            val old = synchronized(lock) { native.also { native = null } }
            runCatching { old?.close() }
        }
        fun cancelPendingRead() {
            val old = synchronized(lock) { native.also { native = null } }
            runCatching { old?.close() }
        }
        fun bindDelegate(value: ProfileStickerAvatarArtworkLease) {
            val reject = synchronized(lock) { if (!live.get()) true else { delegate = value; false } }
            if (reject) runCatching { value.close() }
        }
        override fun close() {
            if (!live.compareAndSet(true, false)) return
            demand.revokeDemand()
            savedOwners.remove(this)
            val (read, output) = synchronized(lock) {
                val old = native to delegate; native = null; delegate = null; encodedSource = null; old
            }
            runCatching { read?.close() }; runCatching { output?.close() }
        }
    }

    /** A synchronous callback waits until its exact native request witness has been bound. */
    private fun readSaved(request: SettingsSavedProfileStickerAvatarRequest, owner: SavedPreviewOwner,
        callback: (SettingsResult<ProfileStickerAvatarEncoded>) -> Unit): AutoCloseable {
        val lock = Any()
        var attached = false
        var received = false
        var first: ProfileStickerAvatarEncoded? = null
        var pending: SettingsResult<ProfileStickerAvatarEncoded>? = null
        // Pending cancellation must close the native read; a normal failure keeps
        // its released native request witness until explicit UI owner teardown.
        val outcome = java.util.concurrent.atomic.AtomicReference(SavedReadOutcome.PENDING)
        fun forward(response: SettingsResult<ProfileStickerAvatarEncoded>) {
            val value = response.value
            if (!owner.demandCurrent()) { value?.close(); return }
            if (response.error == null && value != null) {
                owner.bindSource(value.sourceLease()); outcome.set(SavedReadOutcome.SUCCESS)
            } else outcome.set(SavedReadOutcome.FAILURE)
            callback(response)
        }
        val native = try {
            reader.savedPreviewOwned(request, owner::sourceCurrent, owner.demand) { response ->
                val action = synchronized(lock) {
                    if (received) 0 else {
                        received = true; first = response.value
                        if (attached) 1 else { pending = response; 2 }
                    }
                }
                when (action) {
                    0 -> if (response.value !== first) response.value?.close()
                    1 -> forward(response)
                }
            }
        } catch (failure: Throwable) {
            synchronized(lock) { pending.also { pending = null } }?.value?.close()
            throw failure
        }
        owner.bindNative(native)
        val buffered = synchronized(lock) { attached = true; pending.also { pending = null } }
        buffered?.let(::forward)
        return AutoCloseable {
            when (outcome.get()) {
                SavedReadOutcome.PENDING -> owner.cancelPendingRead()
                SavedReadOutcome.SUCCESS -> owner.finishSuccessfulRead()
                SavedReadOutcome.FAILURE -> Unit
            }
        }
    }

    private enum class SavedReadOutcome { PENDING, SUCCESS, FAILURE }

    private fun fullPreview(parent: SettingsProfileExtrasToken, physicalSizePx: Int, active: () -> Boolean,
        result: (SettingsResult<ProfileStickerAvatarPreviewClip>) -> Unit,
        request: (() -> Boolean, (SettingsResult<ProfileStickerAvatarEncoded>) -> Unit) -> AutoCloseable,
    ): ProfileStickerAvatarArtworkLease {
        val owner = scope(parent, active) ?: return rejected(result, SettingsError.NOT_READY, active)
        if (physicalSizePx !in 1..1024) { fail(owner, result, SettingsError.INVALID_VALUE); return owner }
        read(owner, ProfileStickerAvatarMediaRole.FULL,
            { error -> fail(owner, result, error) },
            { callback -> request(owner::current, callback) }) { encoded ->
            var clip: ArtworkCodecClip? = null
            var error: SettingsError? = null
            val queued = workers.submit({ permit ->
                try {
                    if (!owner.current()) throw InterruptedException()
                    val opened = codec.open(encoded, physicalSizePx, owner::current, permit)
                    clip = opened
                    require(if (opened.animated) opened.durationMillis > 0 && opened.frameDurationMillis > 0
                        else opened.durationMillis == 0L && opened.frameDurationMillis == 0L)
                } catch (failure: Throwable) { runCatching { clip?.close() }; clip = null; error = mapFailure(failure) }
            }, { accepted ->
                encoded.close()
                // Delivery can throw while constructing Preview or after it has entered the owner.
                // Both cleanup paths share one close without revoking a successfully returned clip.
                val prepared = clip?.let { original ->
                    val released = AtomicBoolean()
                    object : ArtworkCodecClip by original {
                        override fun close() { if (released.compareAndSet(false, true)) original.close() }
                    }
                }
                if (!accepted) prepared?.close() else post({
                    if (!owner.current() || prepared == null) {
                        prepared?.close()
                        if (owner.current()) failDirect(owner, result, error ?: SettingsError.REQUEST_FAILED)
                        else owner.close()
                    } else {
                        val actual = Preview(owner, prepared)
                        if (!owner.add(actual)) actual.closeInternal()
                        else try { result(SettingsResult(actual)) } catch (_: Throwable) { owner.close() }
                    }
                }, { prepared?.close(); owner.close() }, {
                    try { prepared?.close() } finally {
                        if (owner.current()) failDirect(owner, result, SettingsError.REQUEST_FAILED) else owner.close()
                    }
                })
            })
            when (queued) {
                ProfileStickerAvatarArtworkWorkers.Admission.REJECTED -> { encoded.close(); fail(owner, result, SettingsError.RATE_LIMITED) }
                ProfileStickerAvatarArtworkWorkers.Admission.UNKNOWN -> fail(owner, result, SettingsError.REQUEST_FAILED)
                ProfileStickerAvatarArtworkWorkers.Admission.ACCEPTED -> Unit
            }
        }
        return owner
    }

    private fun read(owner: Scope, role: ProfileStickerAvatarMediaRole, onError: (SettingsError) -> Unit,
        request: ((SettingsResult<ProfileStickerAvatarEncoded>) -> Unit) -> AutoCloseable,
        consume: (ProfileStickerAvatarEncoded) -> Unit,
    ) {
        val callbackLock = Any()
        var called = false
        var firstValue: ProfileStickerAvatarEncoded? = null
        try {
            val lease = request callback@ { response ->
                val value = response.value
                val duplicate = synchronized(callbackLock) {
                    if (called) true else { called = true; firstValue = value; false }
                }
                if (duplicate) { if (value !== firstValue) value?.close(); return@callback }
                if (value != null) owner.bindSource(value.sourceLease())
                owner.finishRead()
                if (!owner.current()) { value?.close(); owner.close(); return@callback }
                if (response.error != null || value == null) {
                    value?.close(); onError(response.error ?: SettingsError.REQUEST_FAILED)
                    return@callback
                }
                val supported = value.role == role && value.width in 1..2048 && value.height in 1..2048 &&
                    value.size in 1..(if (role == ProfileStickerAvatarMediaRole.THUMBNAIL) 512 * 1024 else 24 * 1024 * 1024) && when (role) {
                        ProfileStickerAvatarMediaRole.THUMBNAIL -> value.format in setOf(MediaFormat.WEBP, MediaFormat.PHOTO)
                        ProfileStickerAvatarMediaRole.FULL -> value.format in setOf(MediaFormat.WEBP, MediaFormat.TGS, MediaFormat.WEBM)
                    }
                if (!supported) { value.close(); onError(SettingsError.INVALID_VALUE); return@callback }
                // The worker owns this independent handle from before queue admission to physical exit.
                val retained = value.retain()
                value.close()
                if (retained == null) onError(SettingsError.NOT_READY) else try { consume(retained) }
                catch (_: Throwable) { retained.close(); onError(SettingsError.REQUEST_FAILED) }
            }
            owner.attachRead(lease)
        } catch (_: Throwable) {
            val report = synchronized(callbackLock) { if (called) false else { called = true; true } }
            if (report) onError(SettingsError.REQUEST_FAILED)
        }
    }

    private fun <T> fail(owner: Scope, result: (SettingsResult<T>) -> Unit, error: SettingsError) {
        post({ if (owner.current()) failDirect(owner, result, error) else owner.close() }, owner::close)
    }
    private fun <T> failDirect(owner: Scope, result: (SettingsResult<T>) -> Unit, error: SettingsError) {
        try { result(SettingsResult(error = error)) } catch (_: Throwable) { } finally { owner.close() }
    }
    private fun <T> rejected(result: (SettingsResult<T>) -> Unit, error: SettingsError,
        current: () -> Boolean): ProfileStickerAvatarArtworkLease {
        val alive = AtomicBoolean(true)
        post({ if (alive.compareAndSet(true, false) && safe(current)) runCatching { result(SettingsResult(error = error)) } },
            { alive.set(false) })
        return object : ProfileStickerAvatarArtworkLease { override fun close() { alive.set(false) } }
    }

    private inner class DisplayLease(private val owner: Scope, private val active: () -> Boolean) : AutoCloseable,
        ProfileStickerAvatarArtworkLease {
        private val alive = AtomicBoolean(true)
        private val lock = Any()
        private var value: ArtworkFrame? = null
        private var frozenIssued = false
        fun current() = alive.get() && owner.current() && safe(active) && alive.get()
        fun install(frame: ArtworkFrame): Boolean = synchronized(lock) {
            if (!alive.get() || !owner.alive.get() || value != null) false else { value = frame; true }
        }
        fun artwork() = object : ProfileStickerAvatarArtwork {
            override val image: ImageBitmap? get() = if (current()) synchronized(lock) { value?.image } else null
            override val needsRepainting: Boolean get() = synchronized(lock) { value?.needsRepainting ?: false }
            override fun freezeDisplay(): ProfileStickerAvatarFrozenDisplay? {
                if (!current()) return null
                val frame = synchronized(lock) {
                    val existing = value
                    if (!alive.get() || !owner.alive.get() || frozenIssued || existing == null ||
                        existing.image.width !in 1..1024 || existing.image.height !in 1..1024) null
                    else existing.retain()?.also { frozenIssued = true }
                } ?: return null
                if (!current()) {
                    try { frame.close() } finally { synchronized(lock) { frozenIssued = false } }
                    return null
                }
                return FrozenDisplay(frame) { synchronized(lock) { frozenIssued = false } }
            }
        }
        override fun close() {
            if (!alive.compareAndSet(true, false)) return
            val frame = synchronized(lock) { value.also { value = null } }
            owner.remove(this); frame?.close()
        }
    }

    /** The codec worker prepared this immutable bitmap and its memory charge before publication. */
    private class FrozenDisplay(frame: ArtworkFrame, private val released: () -> Unit) : ProfileStickerAvatarFrozenDisplay {
        private val retained = AtomicReference<ArtworkFrame?>(frame)
        override val image: ImageBitmap? get() = retained.get()?.image
        override val needsRepainting: Boolean get() = retained.get()?.needsRepainting ?: false
        override fun close() { retained.getAndSet(null)?.let { try { it.close() } finally { released() } } }
    }

    private fun publishFrame(output: DisplayLease, frame: ArtworkFrame?, error: SettingsError?,
        result: (SettingsResult<ProfileStickerAvatarArtwork>) -> Unit, current: () -> Boolean = output::current,
        onFailure: () -> Unit = {}) {
        val released = AtomicBoolean()
        val transferred = AtomicBoolean()
        val failureCalled = AtomicBoolean()
        fun releaseUntransferred() { if (released.compareAndSet(false, true)) frame?.close() }
        fun failed() { if (failureCalled.compareAndSet(false, true)) onFailure() }
        post({
            if (!current()) { releaseUntransferred(); output.close(); failed() }
            else if (frame == null) { try { result(SettingsResult(error = error ?: SettingsError.REQUEST_FAILED)) }
                catch (_: Throwable) { } finally { output.close(); failed() } }
            else if (!output.install(frame)) { releaseUntransferred(); failed() }
            else {
                transferred.set(true)
                try { result(SettingsResult(output.artwork())) } catch (_: Throwable) { output.close(); failed() }
            }
        }, { if (!transferred.get()) releaseUntransferred(); output.close(); failed() })
    }

    private inner class Preview(private val owner: Scope, private val codecClip: ArtworkCodecClip) :
        ProfileStickerAvatarPreviewClip, AutoCloseable {
        override val animated = codecClip.animated
        override val durationMillis = codecClip.durationMillis
        override val frameDurationMillis = codecClip.frameDurationMillis
        private val alive = AtomicBoolean(true)
        private val lock = Any()
        private val requests = linkedSetOf<FrameRequest>()
        private var nextSequence = 0L
        private var deliveredSequence = 0L
        private var running = false
        private var pending: FrameRequest? = null
        private var references = 1
        private var codecClosed = false

        override fun active(): Boolean = alive.get() && owner.current() && alive.get()

        private inner class FrameRequest(val time: Long, val output: DisplayLease,
            val callback: (SettingsResult<ProfileStickerAvatarArtwork>) -> Unit, val sequence: Long) : AutoCloseable {
            val open = AtomicBoolean(true)
            // A newer pending target must not cancel the only physical frame in progress.
            fun current() = open.get() && alive.get() && owner.current() && output.current()
            // UI dispatch can be delayed or reordered; never replace a newer delivered frame.
            fun publishCurrent(): Boolean {
                if (!current()) return false
                return synchronized(lock) {
                    if (sequence <= deliveredSequence) false else { deliveredSequence = sequence; true }
                }
            }
            override fun close() {
                if (!open.compareAndSet(true, false)) return
                synchronized(lock) { requests.remove(this); if (pending === this) pending = null }
                output.close()
            }
        }
        private fun hold(): Boolean = synchronized(lock) {
            if (!alive.get() || codecClosed) false else { references++; true }
        }
        private fun release() {
            val dispose = synchronized(lock) {
                check(references > 0)
                references--
                if (references == 0 && !codecClosed) { codecClosed = true; true } else false
            }
            if (dispose) codecClip.close()
        }
        override fun frame(timeMillis: Long, active: () -> Boolean,
            result: (SettingsResult<ProfileStickerAvatarArtwork>) -> Unit): ProfileStickerAvatarArtworkLease {
            if (timeMillis < 0 || !alive.get() || !owner.current() || !safe(active))
                return rejected(result, if (timeMillis < 0) SettingsError.INVALID_VALUE else SettingsError.NOT_READY,
                    { alive.get() && owner.current() && safe(active) })
            val display = DisplayLease(owner, active)
            if (!owner.add(display)) return rejected(result, SettingsError.NOT_READY, { alive.get() && owner.current() && safe(active) })
            var old: FrameRequest? = null
            var start: FrameRequest? = null
            val request = synchronized(lock) {
                if (!alive.get() || requests.size >= 64 || nextSequence == Long.MAX_VALUE) null else {
                    val seq = ++nextSequence
                    FrameRequest(timeMillis, display, result, seq).also { request ->
                        requests.add(request)
                        old = pending; pending = null
                        if (!running) { running = true; start = request } else pending = request
                    }
                }
            }
            old?.close()
            if (request == null) {
                display.close()
                return rejected(result, SettingsError.RATE_LIMITED, { alive.get() && owner.current() && safe(active) })
            }
            start?.let(::start)
            return object : ProfileStickerAvatarArtworkLease { override fun close() { request.close() } }
        }
        private fun start(request: FrameRequest) {
            if (!hold()) { finish(request, null, SettingsError.NOT_READY); return }
            var frame: ArtworkFrame? = null
            var error: SettingsError? = null
            val accepted = workers.submit({ permit ->
                try {
                    if (!request.current()) throw InterruptedException()
                    frame = codecClip.frame(request.time, request::current, permit)
                } catch (failure: Throwable) { error = mapFailure(failure) }
            }, { admitted ->
                try { release() } finally {
                    if (admitted) finish(request, frame, error) else frame?.close()
                }
            })
            when (accepted) {
                ProfileStickerAvatarArtworkWorkers.Admission.REJECTED -> { release(); finish(request, null, SettingsError.RATE_LIMITED) }
                ProfileStickerAvatarArtworkWorkers.Admission.UNKNOWN -> fail(owner, request.callback, SettingsError.REQUEST_FAILED)
                ProfileStickerAvatarArtworkWorkers.Admission.ACCEPTED -> Unit
            }
        }
        private fun finish(request: FrameRequest, frame: ArtworkFrame?, error: SettingsError?) {
            var following: FrameRequest? = null
            synchronized(lock) {
                running = false
                if (alive.get()) pending?.let { following = it; pending = null; running = true }
            }
            publishFrame(request.output, frame, error, request.callback, request::publishCurrent, request::close)
            following?.let(::start)
        }
        override fun close() { if (owner.alive.get()) owner.close() else closeInternal() }
        fun closeInternal() {
            if (!alive.compareAndSet(true, false)) return
            val old = synchronized(lock) { pending = null; requests.toList().also { requests.clear() } }
            old.forEach(FrameRequest::close)
            release()
        }
    }

    private fun post(deliver: () -> Unit, discard: () -> Unit, onDeliveryFailure: () -> Unit = discard) {
        val once = AtomicBoolean()
        try { ui.post({ if (once.compareAndSet(false, true)) {
                try { deliver() } catch (_: Throwable) { runCatching(onDeliveryFailure) }
            } }, { if (once.compareAndSet(false, true)) runCatching(discard) }) }
        catch (_: Throwable) { if (once.compareAndSet(false, true)) runCatching(discard) }
    }
    override fun close() {
        val (oldScopes, oldSaved) = synchronized(admissions) {
            if (!closed.compareAndSet(false, true)) emptyList<Scope>() to emptyList<SavedPreviewOwner>()
            else scopes.toList() to savedOwners.toList()
        }
        oldSaved.forEach(SavedPreviewOwner::close)
        oldScopes.forEach(Scope::close)
        // The shared pool survives generation replacement; retired queued tasks drain their own handles.
    }
    private fun safe(active: () -> Boolean) = runCatching(active).getOrDefault(false)
    private fun mapFailure(failure: Throwable): SettingsError = when (failure) {
        is InterruptedException -> SettingsError.NOT_READY
        is ProfileStickerAvatarArtworkBusy, is OutOfMemoryError -> SettingsError.RATE_LIMITED
        is IllegalArgumentException -> SettingsError.INVALID_VALUE
        else -> SettingsError.REQUEST_FAILED
    }
}
