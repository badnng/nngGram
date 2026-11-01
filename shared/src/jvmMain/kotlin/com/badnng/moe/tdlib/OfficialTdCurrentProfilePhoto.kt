package com.badnng.moe.tdlib

import com.badnng.moe.chat.MediaFile
import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Self-only current photo authority. It never issues history or picker capabilities. */
internal class OfficialTdCurrentProfilePhoto(
    private val send: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit,
) : CurrentProfilePhotoCatalog {
    private data class KnownFile(val unique: String?, val size: Long)
    private class Record(val userId: Long, val photo: SettingsCurrentProfilePhoto?, val source: () -> Boolean,
        val invalidated: () -> Unit) {
        val valid = AtomicBoolean(true)
        val facts = mutableMapOf<Int, KnownFile>()
        val savedStickerFacts = SavedProfileStickerFacts()
    }
    private class Pending(val userId: Long, val revision: Long, val source: () -> Boolean, val invalidated: () -> Unit) {
        var selfKnown = false
        var currentId: Long? = null
        var userUpdateSeen = false
        var userUpdateId: Long? = null
        var fullUpdate: SettingsCurrentProfilePhoto? = null
        val facts = mutableMapOf<Int, KnownFile>()
    }
    private val lock = Any()
    private val sequence = AtomicLong()
    @Volatile private var retired = false
    private var record: Record? = null
    private var pending: Pending? = null

    fun retire() = synchronized(lock) {
        retired = true; sequence.incrementAndGet(); record?.valid?.set(false); record = null; pending = null
    }

    fun read(userId: Long, active: () -> Boolean, invalidated: () -> Unit,
        result: (SettingsResult<SettingsCurrentProfilePhotoRead>) -> Unit,
    ) {
        if (userId <= 0) { result(SettingsResult(error = SettingsError.INVALID_VALUE)); return }
        if (retired || !safe(active)) { result(SettingsResult(error = SettingsError.NOT_READY)); return }
        val revision = synchronized(lock) {
            if (retired) return@synchronized 0L
            record?.valid?.set(false); record = null
            val next = sequence.incrementAndGet()
            pending = Pending(userId, next, active, invalidated)
            next
        }
        if (revision <= 0) { result(SettingsResult(error = SettingsError.NOT_READY)); return }
        val current = { !retired && sequence.get() == revision && safe(active) }
        request(TdApi.GetMe(), current, { it as? TdApi.User }) self@ { response ->
            val user = response.value ?: run { clearPending(revision); result(SettingsResult(error = response.error)); return@self }
            if (user.id != userId) { clearPending(revision); result(SettingsResult(error = SettingsError.NOT_READY)); return@self }
            val currentId = user.profilePhoto?.id?.takeIf { it != 0L }
            val personalFlag = user.profilePhoto?.isPersonal == true
            val started = synchronized(lock) {
                pending?.takeIf { it.revision == revision }?.let {
                    if (it.userUpdateSeen && it.userUpdateId != currentId) {
                        pending = null; -1
                    } else { it.selfKnown = true; it.currentId = currentId; 1 }
                } ?: 0
            }
            if (started != 1) {
                result(SettingsResult(error = if (started < 0) SettingsError.CONFLICT else SettingsError.NOT_READY)); return@self
            }
            request(TdApi.GetUserFullInfo(userId), current, { it as? TdApi.UserFullInfo }) full@ { full ->
                val info = full.value ?: run { clearPending(revision); result(SettingsResult(error = full.error)); return@full }
                val main = info.photo
                val mainId = main?.id?.takeIf { it != 0L }
                val personal = info.personalPhoto?.id?.takeIf { it != 0L }
                val public = info.publicPhoto?.id?.takeIf { it != 0L }
                val status = when {
                    user.profilePhoto != null && currentId == null -> SettingsCurrentProfilePhotoStatus.CONFLICT
                    currentId != null && mainId == currentId -> SettingsCurrentProfilePhotoStatus.READY
                    personalFlag || currentId != null && (currentId == personal || currentId == public) ||
                        currentId == null && (personal != null || public != null) -> SettingsCurrentProfilePhotoStatus.ROLE_CONFLICT
                    currentId == null && main == null -> SettingsCurrentProfilePhotoStatus.EMPTY
                    currentId != null && main == null -> SettingsCurrentProfilePhotoStatus.UNKNOWN
                    main != null && mainId == null -> SettingsCurrentProfilePhotoStatus.CONFLICT
                    currentId == null || currentId != mainId -> SettingsCurrentProfilePhotoStatus.CONFLICT
                    else -> SettingsCurrentProfilePhotoStatus.CONFLICT
                }
                val captured = if (status == SettingsCurrentProfilePhotoStatus.READY)
                    mapPhoto(checkNotNull(main)).copy(authorization = nextTicket.incrementAndGet()) else null
                val canonical = captured?.copy(variants = Collections.unmodifiableList(captured.variants.toList()))
                val installed = synchronized(lock) {
                    val inFlight = pending?.takeIf { it.revision == revision }
                    if (retired || sequence.get() != revision || inFlight == null) null else {
                        pending = null
                        val update = inFlight.fullUpdate
                        val conflict = update != null && (canonical == null && status == SettingsCurrentProfilePhotoStatus.EMPTY ||
                            canonical != null && !sameCurrentMetadata(canonical, update, inFlight.facts)) ||
                            canonical != null && !rememberPhotoFacts(canonical, inFlight.facts)
                        if (!conflict && (status == SettingsCurrentProfilePhotoStatus.READY || status == SettingsCurrentProfilePhotoStatus.EMPTY))
                            record = Record(userId, canonical, current, invalidated).also { it.facts.putAll(inFlight.facts) }
                        !conflict
                    }
                }
                if (installed == null || !current()) { result(SettingsResult(error = SettingsError.NOT_READY)); return@full }
                if (!installed) { result(SettingsResult(error = SettingsError.CONFLICT)); return@full }
                result(SettingsResult(SettingsCurrentProfilePhotoRead(status, canonical?.let(::publicPhoto))))
            }
        }
    }

    override fun resolve(request: SettingsCurrentProfilePhotoAuthorizedFileRequest): SettingsResult<SettingsProfilePhotoVariant> {
        if (request.use != SettingsCurrentProfilePhotoMediaUse.STATIC_PREVIEW) return SettingsResult(error = SettingsError.UNSUPPORTED)
        val found = authorized(request)
        val photo = found.value?.photo ?: return SettingsResult(error = found.error ?: SettingsError.CONFLICT)
        return selectCurrentProfilePhotoVariant(photo.variants, request)
    }

    override fun resolveAnimation(request: SettingsCurrentProfilePhotoAuthorizedFileRequest): SettingsResult<SettingsProfilePhotoAnimation> {
        if (request.use != SettingsCurrentProfilePhotoMediaUse.ANIMATION_PREVIEW) return SettingsResult(error = SettingsError.UNSUPPORTED)
        val found = authorized(request)
        val photo = found.value?.photo ?: return SettingsResult(error = found.error ?: SettingsError.CONFLICT)
        return selectCurrentProfilePhotoAnimation(photo, request)
    }

    override fun observeFile(request: SettingsCurrentProfilePhotoAuthorizedFileRequest, actual: MediaFile): SettingsResult<Unit> {
        val found = authorized(request)
        val source = found.value ?: return SettingsResult(error = found.error ?: SettingsError.CONFLICT)
        val photo = source.photo ?: return SettingsResult(error = SettingsError.CONFLICT)
        val expected = when (request.use) {
            SettingsCurrentProfilePhotoMediaUse.STATIC_PREVIEW -> selectCurrentProfilePhotoVariant(photo.variants, request).value?.file
            SettingsCurrentProfilePhotoMediaUse.ANIMATION_PREVIEW -> selectCurrentProfilePhotoAnimation(photo, request).value?.file
        } ?: return SettingsResult(error = SettingsError.CONFLICT)
        var notify = false
        val outcome: SettingsResult<Unit> = synchronized(lock) {
            if (retired || record !== source || !source.valid.get()) return@synchronized SettingsResult(error = SettingsError.NOT_READY)
            fun conflict(): SettingsResult<Unit> {
                source.valid.set(false); if (record === source) record = null
                notify = true
                return SettingsResult(error = SettingsError.CONFLICT)
            }
            if (actual.id != expected.id || actual.size !in 0..MAX_BYTES || actual.downloadedSize !in 0..MAX_BYTES ||
                request.use == SettingsCurrentProfilePhotoMediaUse.ANIMATION_PREVIEW && actual.uniqueId.length > 1024) return@synchronized conflict()
            val known = source.facts[actual.id]
            if (actual.uniqueId.isBlank()) {
                if (expected.uniqueId.isBlank() && known?.unique == null) {
                    if (expected.size > 0 && expected.size != actual.size ||
                        known != null && known.size > 0 && known.size != actual.size) return@synchronized conflict()
                    source.facts[actual.id] = KnownFile(null, known?.size?.takeIf { it > 0 } ?: actual.size)
                    return@synchronized SettingsResult(error = SettingsError.NOT_READY)
                }
                return@synchronized conflict()
            }
            if (expected.uniqueId.isNotBlank() && expected.uniqueId != actual.uniqueId ||
                expected.size > 0 && expected.size != actual.size ||
                known != null && (known.unique != null && known.unique != actual.uniqueId || known.size > 0 && known.size != actual.size)) return@synchronized conflict()
            source.facts[actual.id] = KnownFile(actual.uniqueId, known?.size?.takeIf { it > 0 } ?: actual.size)
            SettingsResult(Unit)
        }
        if (notify) runCatching(source.invalidated)
        return outcome
    }

    internal fun savedStickerTicketWitness(request: SettingsSavedProfileStickerNativeSource.Current): (() -> Boolean)? = synchronized(lock) {
        val source = record?.takeIf { !retired && it.valid.get() && it.userId == request.userId &&
            it.photo?.authorization == request.authorization && it.photo?.photoId == request.photoId } ?: return@synchronized null
        val witness: () -> Boolean = { !retired && source.valid.get() && source.savedStickerFacts.current() }
        witness
    }

    internal fun captureSavedSticker(request: SettingsSavedProfileStickerNativeSource.Current): SettingsResult<SavedProfileStickerCaptured> {
        val found = authorized(request.userId, request.authorization, request.photoId)
        val source = found.value ?: return SettingsResult(error = found.error ?: SettingsError.CONFLICT)
        val photo = source.photo ?: return SettingsResult(error = SettingsError.CONFLICT)
        if (photo.hasAnimation || photo.invalidAnimation || photo.invalidSmallAnimation) return SettingsResult(error = SettingsError.UNSUPPORTED)
        if (photo.invalidSticker) return SettingsResult(error = SettingsError.INVALID_VALUE)
        val sticker = photo.sticker ?: return SettingsResult(error = SettingsError.UNSUPPORTED)
        return SettingsResult(SavedProfileStickerCaptured(sticker,
            atomicCurrent = { !retired && source.valid.get() && source.savedStickerFacts.current() },
            sourceCurrent = { !retired && source.valid.get() && source.savedStickerFacts.current() && safe(source.source) },
            facts = source.savedStickerFacts,
            invalidate = {
                val changed = synchronized(lock) {
                    if (retired || record !== source || !source.valid.get()) false
                    else { source.valid.set(false); record = null; true }
                }
                if (changed) runCatching(source.invalidated)
            }))
    }

    private fun authorized(request: SettingsCurrentProfilePhotoAuthorizedFileRequest): SettingsResult<Record> =
        authorized(request.userId, request.authorization, request.photoId)

    private fun authorized(userId: Long, authorization: Long, photoId: Long): SettingsResult<Record> {
        if (userId <= 0 || authorization <= 0 || photoId == 0L)
            return SettingsResult(error = SettingsError.INVALID_VALUE)
        val current = synchronized(lock) { record } ?: return SettingsResult(error = SettingsError.CONFLICT)
        if (!current.valid.get() || !safe(current.source)) {
            current.valid.set(false)
            synchronized(lock) { if (record === current) record = null }
            return SettingsResult(error = SettingsError.NOT_READY)
        }
        val accepted = synchronized(lock) { !retired && record === current && current.valid.get() &&
            current.userId == userId && current.photo?.authorization == authorization && current.photo?.photoId == photoId }
        return if (accepted) SettingsResult(current) else SettingsResult(error = SettingsError.CONFLICT)
    }

    private fun clearPending(revision: Long) = synchronized(lock) {
        if (pending?.revision == revision) pending = null
    }

    /** Called by the existing TDLib update receiver; no UI-provided file may enter this path. */
    fun accept(update: TdApi.Object) {
        val user = (update as? TdApi.UpdateUser)?.user
        val full = update as? TdApi.UpdateUserFullInfo
        if (user == null && full == null) return
        val updateUserId = user?.id ?: full?.userId ?: return
        val main = full?.userFullInfo?.photo
        val mapped = main?.let { runCatching { mapPhoto(it) }.getOrNull() }
        val inFlight = synchronized(lock) { pending }
        var notify: (() -> Unit)? = null
        if (inFlight != null && inFlight.userId == updateUserId && safe(inFlight.source)) {
            synchronized(lock) {
                if (pending === inFlight) {
                    val id = user?.profilePhoto?.id?.takeIf { it != 0L }
                    if (user != null && (user.profilePhoto != null && id == null ||
                            inFlight.selfKnown && id != inFlight.currentId ||
                            inFlight.userUpdateSeen && id != inFlight.userUpdateId)) {
                        pending = null; sequence.incrementAndGet(); notify = inFlight.invalidated
                    } else if (user != null) {
                        inFlight.userUpdateSeen = true; inFlight.userUpdateId = id
                    } else if (main != null) {
                        val prior = inFlight.fullUpdate
                        if (mapped == null || prior != null && !sameCurrentMetadata(prior, mapped, inFlight.facts) ||
                            !rememberPhotoFacts(mapped, inFlight.facts)) {
                            pending = null; sequence.incrementAndGet(); notify = inFlight.invalidated
                        } else inFlight.fullUpdate = mapped
                    }
                }
            }
        }
        val active = synchronized(lock) { record }
        if (active != null && active.userId == updateUserId && safe(active.source)) {
            synchronized(lock) {
                if (record === active && active.valid.get()) {
                    val id = user?.profilePhoto?.id?.takeIf { it != 0L }
                    val conflict = if (user != null) user.profilePhoto != null && id == null || id != active.photo?.photoId
                    else if (main == null) false // FullInfo.photo may be unknown.
                    else mapped == null || active.photo == null || !sameCurrentMetadata(active.photo, mapped, active.facts) ||
                        !rememberPhotoFacts(mapped, active.facts)
                    if (conflict) {
                        active.valid.set(false); record = null; notify = active.invalidated
                    }
                }
            }
        }
        notify?.let { runCatching(it) }
    }

    private fun rememberPhotoFacts(photo: SettingsCurrentProfilePhoto, facts: MutableMap<Int, KnownFile>): Boolean {
        val files = photo.variants.map { it.file } + listOfNotNull(photo.animation?.file, photo.smallAnimation?.file)
        for (file in files) {
            val previous = facts[file.id]
            val unique = file.uniqueId.takeIf { it.isNotBlank() }
            if (previous != null && (previous.unique != null && unique != null && previous.unique != unique ||
                    previous.size > 0 && file.size > 0 && previous.size != file.size)) return false
            facts[file.id] = KnownFile(previous?.unique ?: unique, previous?.size?.takeIf { it > 0 } ?: file.size)
        }
        return true
    }

    /** Compare stable native descriptors, ignoring local paths, progress and estimates. */
    private fun sameCurrentMetadata(a: SettingsCurrentProfilePhoto, b: SettingsCurrentProfilePhoto,
        facts: Map<Int, KnownFile>,
    ): Boolean {
        fun known(file: MediaFile): MediaFile {
            val fact = facts[file.id] ?: return file
            return file.copy(uniqueId = file.uniqueId.ifBlank { fact.unique.orEmpty() },
                size = if (file.size == 0L) fact.size else file.size)
        }
        fun sameFile(left: MediaFile, right: MediaFile): Boolean {
            val x = known(left)
            val y = known(right)
            return x.id == y.id && (x.uniqueId.isBlank() || y.uniqueId.isBlank() || x.uniqueId == y.uniqueId) &&
                x.size >= 0 && y.size >= 0 && (x.size == 0L || y.size == 0L || x.size == y.size)
        }
        fun sameAnimation(x: SettingsProfilePhotoAnimation?, y: SettingsProfilePhotoAnimation?): Boolean = when {
            x == null || y == null -> x == null && y == null
            else -> x.length == y.length && x.mainFrameTimestampSeconds == y.mainFrameTimestampSeconds && sameFile(x.file, y.file)
        }
        if (a.photoId != b.photoId || a.hasAnimation != b.hasAnimation || a.hasSticker != b.hasSticker ||
            a.sticker != b.sticker || a.invalidSticker != b.invalidSticker ||
            a.invalidAnimation != b.invalidAnimation || a.invalidSmallAnimation != b.invalidSmallAnimation ||
            a.addedDate != 0 && b.addedDate != 0 && a.addedDate != b.addedDate ||
            !sameAnimation(a.animation, b.animation) || !sameAnimation(a.smallAnimation, b.smallAnimation) ||
            a.variants.size != b.variants.size || a.variants.size > 16) return false
        val matched = IntArray(b.variants.size) { -1 }
        fun assign(left: Int, seen: BooleanArray): Boolean {
            val x = a.variants[left]
            for (i in b.variants.indices) {
                val y = b.variants[i]
                if (seen[i] || x.width != y.width || x.height != y.height || !sameFile(x.file, y.file)) continue
                seen[i] = true
                if (matched[i] < 0 || assign(matched[i], seen)) { matched[i] = left; return true }
            }
            return false
        }
        return a.variants.indices.all { assign(it, BooleanArray(b.variants.size)) }
    }

    private fun mapPhoto(photo: TdApi.ChatPhoto): SettingsCurrentProfilePhoto {
        val big = mapAnimation(photo.animation, false); val small = mapAnimation(photo.smallAnimation, true)
        val sticker = mapProfilePhotoSticker(photo.sticker)
        return SettingsCurrentProfilePhoto(photo.id, photo.addedDate,
            photo.sizes.orEmpty().asSequence().take(16).mapNotNull { size ->
                val file = size?.photo
                if (file == null || file.id <= 0 || size.width !in 1..8192 || size.height !in 1..8192) null
                else SettingsProfilePhotoVariant(mapMediaFile(file), size.width, size.height)
            }.toList(), mapMiniThumbnail(photo.minithumbnail),
            hasAnimation = photo.animation != null || photo.smallAnimation != null, hasSticker = photo.sticker != null,
            animation = big, smallAnimation = small,
            invalidAnimation = photo.animation != null && big == null, invalidSmallAnimation = photo.smallAnimation != null && small == null,
            sticker = sticker, invalidSticker = photo.sticker != null && sticker == null)
    }

    private fun mapAnimation(native: TdApi.AnimatedChatPhoto?, small: Boolean): SettingsProfilePhotoAnimation? {
        val animation = native ?: return null; val file = animation.file ?: return null
        val unique = file.remote?.uniqueId.orEmpty()
        if ((if (small) animation.length != 160 else animation.length !in 1..1280) ||
            !animation.mainFrameTimestamp.isFinite() || animation.mainFrameTimestamp < 0 || file.id <= 0 ||
            file.size < 0 || file.expectedSize < 0 || (file.local?.downloadedSize ?: 0) < 0 ||
            (file.remote?.uploadedSize ?: 0) < 0 || unique.length > 1024) return null
        return SettingsProfilePhotoAnimation(mapMediaFile(file), animation.length, animation.mainFrameTimestamp)
    }

    private fun publicPhoto(photo: SettingsCurrentProfilePhoto): SettingsCurrentProfilePhoto {
        fun clean(file: MediaFile) = file.copy(localPath = null, downloaded = false, downloading = false,
            downloadedSize = 0, uploading = false, uploadedSize = 0)
        return photo.copy(variants = Collections.unmodifiableList(photo.variants.map { it.copy(file = clean(it.file)) }),
            animation = photo.animation?.let { it.copy(file = clean(it.file)) },
            smallAnimation = photo.smallAnimation?.let { it.copy(file = clean(it.file)) })
    }

    private fun safe(active: () -> Boolean) = runCatching(active).getOrDefault(false)
    private fun <T> request(command: TdApi.Function<*>, active: () -> Boolean, map: (TdApi.Object) -> T?,
        result: (SettingsResult<T>) -> Unit,
    ) {
        val done = AtomicBoolean()
        fun finish(value: SettingsResult<T>) { if (done.compareAndSet(false, true)) result(value) }
        if (!safe(active)) { finish(SettingsResult(error = SettingsError.NOT_READY)); return }
        try { send(command, received@ { native ->
            if (!done.compareAndSet(false, true)) return@received
            val value = if (safe(active)) runCatching { map(native) }.getOrNull() else null
            result(if (!safe(active)) SettingsResult(error = SettingsError.NOT_READY)
                else if (value != null) SettingsResult(value) else SettingsResult(error = settingsError(native)))
        }, { finish(SettingsResult(error = SettingsError.NOT_READY)) }) }
        catch (_: Exception) { finish(SettingsResult(error = SettingsError.REQUEST_FAILED)) }
    }

    private companion object {
        const val MAX_BYTES = 24L * 1024 * 1024
        val nextTicket = AtomicLong()
    }
}

internal fun selectCurrentProfilePhotoVariant(variants: List<SettingsProfilePhotoVariant>,
    request: SettingsCurrentProfilePhotoAuthorizedFileRequest,
): SettingsResult<SettingsProfilePhotoVariant> {
    if (request.use != SettingsCurrentProfilePhotoMediaUse.STATIC_PREVIEW) return SettingsResult(error = SettingsError.UNSUPPORTED)
    if (request.targetWidthPx !in 1..4096 || request.targetHeightPx !in 1..4096 ||
        request.targetWidthPx.toLong() * request.targetHeightPx > 24_000_000L) return SettingsResult(error = SettingsError.INVALID_VALUE)
    val selected = variants.filter { it.width > 0 && it.height > 0 && it.width.toLong() * it.height <= 24_000_000L &&
        it.file.id > 0 && it.file.uniqueId.isNotBlank() && it.file.size in 0..(24L * 1024 * 1024) }
        .maxByOrNull { it.width.toLong() * it.height }
    return selected?.let { SettingsResult(it) } ?: SettingsResult(error = SettingsError.UNSUPPORTED)
}

internal fun selectCurrentProfilePhotoAnimation(photo: SettingsCurrentProfilePhoto,
    request: SettingsCurrentProfilePhotoAuthorizedFileRequest,
): SettingsResult<SettingsProfilePhotoAnimation> {
    if (request.use != SettingsCurrentProfilePhotoMediaUse.ANIMATION_PREVIEW) return SettingsResult(error = SettingsError.UNSUPPORTED)
    if (request.targetWidthPx !in 1..4096 || request.targetHeightPx !in 1..4096 ||
        request.targetWidthPx.toLong() * request.targetHeightPx > 24_000_000L) return SettingsResult(error = SettingsError.INVALID_VALUE)
    if (photo.invalidAnimation || photo.animation == null && photo.invalidSmallAnimation)
        return SettingsResult(error = SettingsError.INVALID_VALUE)
    val value = photo.preferredAnimation() ?: return SettingsResult(error = SettingsError.UNSUPPORTED)
    if (value.length !in 1..1280 || photo.animation == null && value.length != 160 ||
        value.mainFrameTimestampMillis == null || value.file.id <= 0 || value.file.uniqueId.length > 1024 ||
        value.file.size !in 0..(24L * 1024 * 1024) || value.file.expectedSize < 0 || value.file.downloadedSize < 0)
        return SettingsResult(error = SettingsError.INVALID_VALUE)
    return SettingsResult(value)
}
