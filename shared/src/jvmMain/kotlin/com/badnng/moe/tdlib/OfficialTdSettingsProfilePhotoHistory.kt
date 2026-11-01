package com.badnng.moe.tdlib

import com.badnng.moe.chat.MediaFile
import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.Collections

/** Self-only native metadata and exact old-photo writes. The Extras actor owns the shared mutation gate. */
internal class OfficialTdSettingsProfilePhotoHistory(
    private val send: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit,
) : ProfilePhotoHistoryCatalog {
    private data class KnownFile(val uniqueId: String, val size: Long)
    private data class CachedPage(val ticket: Long, val identity: SettingsProfilePhotoIdentity,
        val entries: List<SettingsProfilePhotoHistoryEntry>, val rawCount: Int, val source: () -> Boolean,
        val blockedIds: Set<Long> = emptySet(),
        val knownFiles: MutableMap<Pair<Long, Int>, KnownFile> = mutableMapOf(),
        val savedStickerFacts: Map<Long, SavedProfileStickerFacts> = emptyMap(),
        val valid: AtomicBoolean = AtomicBoolean(true))
    private val cacheLock = Any()
    private val pages = linkedMapOf<Long, CachedPage>()
    private var lastIdentity: SettingsProfilePhotoIdentity? = null
    private var lastSelf: Self? = null
    @Volatile private var retired = false

    fun retire() = synchronized(cacheLock) {
        retired = true
        pages.values.forEach { it.valid.set(false) }
        pages.clear()
        lastIdentity = null
        lastSelf = null
    }

    override fun resolve(request: SettingsProfilePhotoHistoryAuthorizedFileRequest): SettingsResult<SettingsProfilePhotoVariant> {
        val found = authorizedEntry(request)
        val entry = found.value ?: return SettingsResult(error = found.error ?: SettingsError.CONFLICT)
        return selectProfilePhotoHistoryVariant(entry.variants, request)
    }

    override fun resolveAnimation(request: SettingsProfilePhotoHistoryAuthorizedFileRequest): SettingsResult<SettingsProfilePhotoAnimation> {
        if (request.use != SettingsProfilePhotoHistoryMediaUse.EXPLICIT_ANIMATION)
            return SettingsResult(error = SettingsError.UNSUPPORTED)
        val found = authorizedEntry(request)
        val entry = found.value ?: return SettingsResult(error = found.error ?: SettingsError.CONFLICT)
        return selectProfilePhotoHistoryAnimation(entry, request)
    }

    override fun observeFile(request: SettingsProfilePhotoHistoryAuthorizedFileRequest, actual: MediaFile): SettingsResult<Unit> {
        pruneInactivePages()
        val found = authorizedEntry(request)
        val entry = found.value ?: return SettingsResult(error = found.error ?: SettingsError.CONFLICT)
        val expected = if (request.use == SettingsProfilePhotoHistoryMediaUse.EXPLICIT_ANIMATION) {
            val selected = selectProfilePhotoHistoryAnimation(entry, request)
            selected.value?.file ?: return SettingsResult(error = selected.error ?: SettingsError.CONFLICT)
        } else {
            val selected = selectProfilePhotoHistoryVariant(entry.variants, request)
            selected.value?.file ?: return SettingsResult(error = selected.error ?: SettingsError.CONFLICT)
        }
        val maximum = if (request.use == SettingsProfilePhotoHistoryMediaUse.VISIBLE_CELL) 8L * 1024 * 1024 else 24L * 1024 * 1024
        if (actual.id != expected.id || actual.size !in 0..maximum || actual.downloadedSize !in 0..maximum ||
            request.use == SettingsProfilePhotoHistoryMediaUse.EXPLICIT_ANIMATION && actual.uniqueId.length > 1024) {
            synchronized(cacheLock) { invalidatePhotoLocked(entry.photoId) }
            return SettingsResult(error = SettingsError.CONFLICT)
        }
        synchronized(cacheLock) {
            val page = pages[request.authorization]
            if (retired || page == null || !page.valid.get() || page.identity != lastIdentity ||
                page.identity.userId != request.userId || request.photoId in page.blockedIds ||
                page.entries.none { it.photoId == entry.photoId && it.rawOffset == entry.rawOffset })
                return SettingsResult(error = SettingsError.CONFLICT)
            val key = entry.photoId to actual.id
            val earlier = pages.values.filter { it.identity == page.identity }.mapNotNull { it.knownFiles[key] }
            if (actual.uniqueId.isBlank()) {
                if (expected.uniqueId.isBlank() && earlier.isEmpty()) return SettingsResult(error = SettingsError.NOT_READY)
                invalidatePhotoLocked(entry.photoId)
                return SettingsResult(error = SettingsError.CONFLICT)
            }
            if (expected.uniqueId.isNotBlank() && actual.uniqueId != expected.uniqueId ||
                expected.size > 0 && actual.size != expected.size ||
                earlier.any { it.uniqueId != actual.uniqueId || it.size > 0 && it.size != actual.size }) {
                invalidatePhotoLocked(entry.photoId)
                return SettingsResult(error = SettingsError.CONFLICT)
            }
            val observedEntry = historyPhotoWithObservedFile(entry, actual)
            val contradictory = pages.values.any { old -> old.identity == page.identity && old.entries.any { previous ->
                previous.photoId == entry.photoId && !sameHistoryPhotoMetadata(withKnownFiles(previous, old), observedEntry)
            } }
            if (contradictory) {
                invalidatePhotoLocked(entry.photoId)
                return SettingsResult(error = SettingsError.CONFLICT)
            }
            val previous = page.knownFiles[key]
            page.knownFiles[key] = KnownFile(actual.uniqueId, previous?.size?.takeIf { it > 0 } ?: actual.size)
        }
        return SettingsResult(Unit)
    }

    /** Must run under cacheLock. A ticket is page-wide, so every page containing the ID is revoked. */
    private fun invalidatePhotoLocked(photoId: Long) {
        pages.values.filter { old -> old.entries.any { it.photoId == photoId } }.toList().forEach { old ->
            old.valid.set(false); pages.remove(old.ticket)
        }
    }

    private fun withKnownFiles(entry: SettingsProfilePhotoHistoryEntry, page: CachedPage): SettingsProfilePhotoHistoryEntry {
        val ids = (entry.variants.map { it.file.id } + listOfNotNull(entry.animation?.file?.id, entry.smallAnimation?.file?.id)).distinct()
        return ids.fold(entry) { current, id ->
            val fact = page.knownFiles[entry.photoId to id] ?: return@fold current
            historyPhotoWithObservedFile(current, MediaFile(id, size = fact.size, uniqueId = fact.uniqueId))
        }
    }

    private fun pruneInactivePages() {
        val previous = synchronized(cacheLock) { pages.values.toList() }
        // Foreign predicates may reenter; never invoke them under the catalog monitor.
        val expired = previous.filter { !it.valid.get() || !current(it.source) }
        synchronized(cacheLock) {
            expired.forEach { old ->
                if (pages[old.ticket] === old) { old.valid.set(false); pages.remove(old.ticket) }
            }
        }
    }

    internal fun savedStickerTicketWitness(request: SettingsSavedProfileStickerNativeSource.History): (() -> Boolean)? = synchronized(cacheLock) {
        val page = pages[request.authorization]?.takeIf { !retired && it.valid.get() && it.identity == lastIdentity &&
            it.identity.userId == request.userId && request.photoId !in it.blockedIds &&
            it.entries.singleOrNull { entry -> entry.photoId == request.photoId && entry.rawOffset == request.rawOffset } != null }
            ?: return@synchronized null
        val facts = page.savedStickerFacts[request.photoId]
        val witness: () -> Boolean = { !retired && page.valid.get() && facts?.current() != false }
        witness
    }

    internal fun captureSavedSticker(request: SettingsSavedProfileStickerNativeSource.History,
        onConflict: () -> Unit,
    ): SettingsResult<SavedProfileStickerCaptured> {
        val found = authorizedEntry(request.userId, request.authorization, request.photoId, request.rawOffset)
        val entry = found.value ?: return SettingsResult(error = found.error ?: SettingsError.CONFLICT)
        if (entry.hasAnimation || entry.invalidAnimation || entry.invalidSmallAnimation) return SettingsResult(error = SettingsError.UNSUPPORTED)
        if (entry.invalidSticker) return SettingsResult(error = SettingsError.INVALID_VALUE)
        val sticker = entry.sticker ?: return SettingsResult(error = SettingsError.UNSUPPORTED)
        val page = synchronized(cacheLock) { pages[request.authorization] }
            ?: return SettingsResult(error = SettingsError.CONFLICT)
        if (!page.valid.get() || !current(page.source)) return SettingsResult(error = SettingsError.NOT_READY)
        val facts = page.savedStickerFacts[request.photoId] ?: return SettingsResult(error = SettingsError.CONFLICT)
        return SettingsResult(SavedProfileStickerCaptured(sticker,
            atomicCurrent = { !retired && page.valid.get() && facts.current() },
            sourceCurrent = { !retired && page.valid.get() && facts.current() && current(page.source) },
            facts = facts,
            invalidate = {
                val changed = synchronized(cacheLock) {
                    if (retired || pages[page.ticket] !== page || !page.valid.get()) false
                    else { invalidatePhotoLocked(request.photoId); true }
                }
                if (changed) runCatching(onConflict)
            }))
    }

    private fun authorizedEntry(request: SettingsProfilePhotoHistoryAuthorizedFileRequest): SettingsResult<SettingsProfilePhotoHistoryEntry> =
        authorizedEntry(request.userId, request.authorization, request.photoId, request.rawOffset)

    private fun authorizedEntry(userId: Long, authorization: Long, photoId: Long, rawOffset: Int): SettingsResult<SettingsProfilePhotoHistoryEntry> {
        if (userId <= 0 || authorization <= 0 || photoId == 0L || rawOffset < 0)
            return SettingsResult(error = SettingsError.INVALID_VALUE)
        val page = synchronized(cacheLock) { pages[authorization] }
            ?: return SettingsResult(error = SettingsError.CONFLICT)
        // Foreign source checks run outside the catalog monitor. A false source is irrevocable.
        if (!page.valid.get() || !current(page.source)) {
            page.valid.set(false)
            synchronized(cacheLock) { pages.remove(page.ticket, page) }
            return SettingsResult(error = SettingsError.NOT_READY)
        }
        val entry = synchronized(cacheLock) {
            if (retired || !page.valid.get() || pages[page.ticket] !== page || page.identity != lastIdentity ||
                page.identity.userId != userId || photoId in page.blockedIds) null
            else page.entries.singleOrNull { it.photoId == photoId && it.rawOffset == rawOffset }
        } ?: return SettingsResult(error = SettingsError.CONFLICT)
        return SettingsResult(entry)
    }

    private fun rememberIdentity(identity: SettingsProfilePhotoIdentity) = synchronized(cacheLock) {
        if (lastIdentity != null && lastIdentity != identity) {
            pages.values.forEach { it.valid.set(false) }; pages.clear()
        }
        lastIdentity = identity
    }

    private fun invalidateIdentity() = synchronized(cacheLock) {
        pages.values.forEach { it.valid.set(false) }; pages.clear(); lastIdentity = null
    }

    private fun rememberSelf(self: Self) = synchronized(cacheLock) {
        if (lastSelf != null && lastSelf != self) {
            pages.values.forEach { it.valid.set(false) }; pages.clear(); lastIdentity = null
        }
        lastSelf = self
    }

    private fun publish(page: SettingsProfilePhotoHistoryPage, source: () -> Boolean): SettingsProfilePhotoHistoryPage? {
        if (!current(source)) return null
        val ticket = nextTicket.incrementAndGet()
        val canonical = Collections.unmodifiableList(page.entries.map { entry -> entry.copy(
            authorization = ticket, variants = Collections.unmodifiableList(entry.variants.toList())) })
        pruneInactivePages()
        var metadataConflict = false
        synchronized(cacheLock) {
            if (retired || page.identity != lastIdentity) return null
            // A later native page may repeat an ID. Preserve harmless repeats, but an
            // incompatible description must revoke every older ticket that carried it.
            val conflictingIds = mutableSetOf<Long>()
            canonical.forEachIndexed { index, earlier ->
                for (later in canonical.drop(index + 1)) {
                    if (earlier.photoId == later.photoId && !sameHistoryPhotoMetadata(earlier, later))
                        conflictingIds += earlier.photoId
                }
            }
            pages.values.filter { it.identity == page.identity }.forEach { old ->
                old.entries.forEach { earlier -> canonical.forEach { later ->
                    if (earlier.photoId == later.photoId && !sameHistoryPhotoMetadata(withKnownFiles(earlier, old), later))
                        conflictingIds += earlier.photoId
                } }
            }
            metadataConflict = conflictingIds.isNotEmpty()
            if (conflictingIds.isNotEmpty()) pages.values.filter { old ->
                old.entries.any { it.photoId in conflictingIds }
            }.toList().forEach { old -> old.valid.set(false); pages.remove(old.ticket) }
            if (page.rawCount > 0 && canonical.isNotEmpty()) {
                // Each retained page consumes at least one of the finite 512 raw positions.
                while (pages.isNotEmpty() && pages.values.sumOf { it.rawCount } + page.rawCount > MAX_CACHED_RAW) {
                    pages.remove(pages.keys.first())?.valid?.set(false)
                }
                val savedFacts = canonical.asSequence().filter { it.sticker != null && !it.invalidSticker }
                    .map { it.photoId }.distinct().associateWith { id ->
                        pages.values.firstNotNullOfOrNull { old ->
                            if (old.identity == page.identity) old.savedStickerFacts[id] else null
                        } ?: SavedProfileStickerFacts()
                    }
                pages[ticket] = CachedPage(ticket, page.identity, canonical, page.rawCount, source,
                    conflictingIds.toSet(), savedStickerFacts = savedFacts)
            }
        }
        fun sanitized(animation: SettingsProfilePhotoAnimation?) = animation?.copy(file = animation.file.copy(
            localPath = null, downloaded = false, downloading = false, downloadedSize = 0,
            uploading = false, uploadedSize = 0))
        return page.copy(entries = Collections.unmodifiableList(canonical.map { entry -> entry.copy(
            variants = Collections.unmodifiableList(entry.variants.map { variant -> variant.copy(file = variant.file.copy(
                localPath = null, downloaded = false, downloading = false, downloadedSize = 0,
                uploading = false, uploadedSize = 0)) }), animation = sanitized(entry.animation),
            smallAnimation = sanitized(entry.smallAnimation)) }), metadataConflict = metadataConflict)
    }
    fun page(userId: Long, offset: Int, limit: Int, isCurrent: () -> Boolean,
        result: (SettingsResult<SettingsProfilePhotoHistoryPage>) -> Unit,
    ) {
        if (offset < 0 || limit !in 1..MAX_PAGE || offset > Int.MAX_VALUE - limit) {
            result(SettingsResult(error = SettingsError.INVALID_VALUE)); return
        }
        identity(userId, isCurrent) identityRead@ { response ->
            val identity = response.value ?: run { result(SettingsResult(error = response.error)); return@identityRead }
            request(TdApi.GetUserProfilePhotos(identity.userId, offset, limit), isCurrent, map = { native ->
                val photos = (native as? TdApi.ChatPhotos)?.photos
                if (native !is TdApi.ChatPhotos || photos == null || photos.size > limit) null
                else publish(SettingsProfilePhotoHistoryPage(identity, photos.mapIndexedNotNull { index, photo ->
                    // Current may be displayed, but personal/public fallback never becomes ordinary history.
                    photo?.takeIf { it.id != 0L && it.id != identity.personalPhotoId && it.id != identity.publicPhotoId }
                        ?.let { mapPhoto(it, offset + index) }
                }.toList(), photos.size, native.totalCount.takeIf { it >= 0 }, offset), isCurrent)
            }, result = result)
        }
    }

    fun reuse(userId: Long, target: SettingsProfilePhotoHistoryTarget, isCurrent: () -> Boolean,
        result: (SettingsResult<Unit>) -> Unit,
    ) = mutate(userId, target, reuse = true, isCurrent, result)

    fun delete(userId: Long, target: SettingsProfilePhotoHistoryTarget, isCurrent: () -> Boolean,
        result: (SettingsResult<Unit>) -> Unit,
    ) = mutate(userId, target, reuse = false, isCurrent, result)

    private fun mutate(userId: Long, target: SettingsProfilePhotoHistoryTarget, reuse: Boolean,
        current: () -> Boolean, result: (SettingsResult<Unit>) -> Unit,
    ) {
        if (target.photoId == 0L || target.rawOffset < 0 || target.rawOffset == Int.MAX_VALUE ||
            target.identity.userId != userId) {
            result(SettingsResult(error = SettingsError.INVALID_VALUE)); return
        }
        if (target.identity.isProtected(target.photoId)) {
            result(SettingsResult(error = SettingsError.CONFLICT)); return
        }
        identity(userId, current) firstRead@ { first ->
            val fresh = first.value ?: run { result(SettingsResult(error = first.error)); return@firstRead }
            if (fresh != target.identity || fresh.isProtected(target.photoId)) {
                result(SettingsResult(error = SettingsError.CONFLICT)); return@firstRead
            }
            // TDLib forbids bot Previous and rewrites a bot DeleteProfilePhoto id to its current photo.
            // Neither behavior is an exact ordinary-history mutation.
            if (fresh.isBot) { result(SettingsResult(error = SettingsError.UNSUPPORTED)); return@firstRead }
            request(TdApi.GetUserProfilePhotos(fresh.userId, target.rawOffset, 1), current,
                map = { native -> (native as? TdApi.ChatPhotos)?.photos?.let { photos ->
                    photos.size == 1 && photos[0]?.id == target.photoId
                } },
            ) targetRead@ { slice ->
                if (slice.value != true) {
                    result(SettingsResult(error = slice.error ?: SettingsError.CONFLICT)); return@targetRead
                }
                // A target-slice read is another native interval: recheck every role immediately before writing.
                identity(userId, current) finalRead@ { last ->
                    val final = last.value ?: run { result(SettingsResult(error = last.error)); return@finalRead }
                    if (final != target.identity || final.isProtected(target.photoId)) {
                        result(SettingsResult(error = SettingsError.CONFLICT)); return@finalRead
                    }
                    val command = if (reuse) TdApi.SetProfilePhoto(TdApi.InputChatPhotoPrevious(target.photoId), false)
                        else TdApi.DeleteProfilePhoto(target.photoId)
                    // Dispatch cannot be withdrawn. Deliver the native outcome even after this page loses ownership.
                    request(command, current, acceptLate = true, map = { if (it is TdApi.Ok) Unit else null }, result = result)
                }
            }
        }
    }

    private data class Self(val userId: Long, val photoId: Long?, val isBot: Boolean)
    private data class Roles(val photoId: Long?, val personalPhotoId: Long?, val publicPhotoId: Long?)

    private fun identity(userId: Long, current: () -> Boolean, result: (SettingsResult<SettingsProfilePhotoIdentity>) -> Unit) {
        if (userId <= 0) { result(SettingsResult(error = SettingsError.INVALID_VALUE)); return }
        request(TdApi.GetMe(), current, map = { native -> (native as? TdApi.User)?.let {
            Self(it.id, it.profilePhoto?.id?.nonzero(), it.type is TdApi.UserTypeBot)
        } }) selfRead@ { response ->
            val self = response.value ?: run { result(SettingsResult(error = response.error)); return@selfRead }
            rememberSelf(self)
            if (self.userId != userId) { invalidateIdentity(); result(SettingsResult(error = SettingsError.NOT_READY)); return@selfRead }
            request(TdApi.GetUserFullInfo(self.userId), current, map = { native -> (native as? TdApi.UserFullInfo)?.let {
                Roles(it.photo?.id?.nonzero(), it.personalPhoto?.id?.nonzero(), it.publicPhoto?.id?.nonzero())
            } }) fullRead@ { response ->
                val roles = response.value ?: run { result(SettingsResult(error = response.error)); return@fullRead }
                if (self.photoId != null && roles.photoId != null && self.photoId != roles.photoId) {
                    invalidateIdentity()
                    result(SettingsResult(error = SettingsError.CONFLICT))
                } else {
                    val identity = SettingsProfilePhotoIdentity(self.userId, self.photoId ?: roles.photoId,
                        roles.personalPhotoId, roles.publicPhotoId, self.isBot)
                    rememberIdentity(identity)
                    result(SettingsResult(identity))
                }
            }
        }
    }

    private fun mapPhoto(photo: TdApi.ChatPhoto, offset: Int): SettingsProfilePhotoHistoryEntry {
        val animation = mapAnimation(photo.animation, small = false)
        val smallAnimation = mapAnimation(photo.smallAnimation, small = true)
        val sticker = mapProfilePhotoSticker(photo.sticker)
        return SettingsProfilePhotoHistoryEntry(
            photoId = photo.id, addedDate = photo.addedDate, rawOffset = offset,
            variants = photo.sizes.orEmpty().asSequence().take(MAX_VARIANTS).mapNotNull { size ->
                val file = size?.photo
                if (file == null || file.id <= 0 || size.width !in 1..MAX_DIMENSION || size.height !in 1..MAX_DIMENSION) null
                else SettingsProfilePhotoVariant(mapMediaFile(file), size.width, size.height)
            }.toList(),
            minithumbnail = mapMiniThumbnail(photo.minithumbnail),
            hasAnimation = photo.animation != null || photo.smallAnimation != null,
            hasSticker = photo.sticker != null,
            animation = animation, smallAnimation = smallAnimation,
            invalidAnimation = photo.animation != null && animation == null,
            invalidSmallAnimation = photo.smallAnimation != null && smallAnimation == null,
            sticker = sticker, invalidSticker = photo.sticker != null && sticker == null,
        )
    }

    private fun mapAnimation(native: TdApi.AnimatedChatPhoto?, small: Boolean): SettingsProfilePhotoAnimation? {
        val animation = native ?: return null
        val file = animation.file ?: return null
        val unique = file.remote?.uniqueId.orEmpty()
        if ((if (small) animation.length != 160 else animation.length !in 1..1280) ||
            !animation.mainFrameTimestamp.isFinite() || animation.mainFrameTimestamp < 0.0 ||
            file.id <= 0 || file.size < 0 || file.expectedSize < 0 ||
            (file.local?.downloadedSize ?: 0) < 0 || (file.remote?.uploadedSize ?: 0) < 0 ||
            unique.length > 1024) return null
        return SettingsProfilePhotoAnimation(mapMediaFile(file), animation.length, animation.mainFrameTimestamp)
    }

    private fun Long.nonzero() = takeIf { it != 0L }
    private fun current(check: () -> Boolean) = !retired && runCatching(check).getOrDefault(false)

    /** No helper locks surround owner predicates, send, or result callbacks. */
    private fun <T> request(command: TdApi.Function<*>, isCurrent: () -> Boolean, acceptLate: Boolean = false,
        map: (TdApi.Object) -> T?, result: (SettingsResult<T>) -> Unit,
    ) {
        val once = AtomicBoolean()
        fun finish(response: SettingsResult<T>) {
            if (SettingsSpecialMutationContext.receipt()?.outcome == SettingsSpecialWriteOutcome.INDETERMINATE) {
                result(response); return
            }
            if (once.compareAndSet(false, true)) result(response)
        }
        if (!current(isCurrent)) { finish(SettingsResult(error = SettingsError.NOT_READY)); return }
        try {
            val done: (TdApi.Object) -> Unit = { native ->
                if (SettingsSpecialMutationContext.receipt()?.outcome != SettingsSpecialWriteOutcome.INDETERMINATE &&
                    once.compareAndSet(false, true)) {
                    val mapped = if (acceptLate || current(isCurrent)) runCatching { map(native) }.getOrNull() else null
                    result(if (!acceptLate && !current(isCurrent)) SettingsResult(error = SettingsError.NOT_READY)
                        else if (mapped != null) SettingsResult(mapped) else SettingsResult(error = settingsError(native)))
                }
            }
            val failed = { finish(SettingsResult<T>(error = SettingsError.NOT_READY)) }
            if (acceptLate) SettingsSpecialMutationContext.write(command, send, done, failed)
            else SettingsSpecialMutationContext.without {
                send(command, { native -> SettingsSpecialMutationContext.withReceipt(null) { done(native) } },
                    { SettingsSpecialMutationContext.withReceipt(null, failed) })
            }
        } catch (_: Exception) { finish(SettingsResult(error = SettingsError.REQUEST_FAILED)) }
    }

    private companion object {
        const val MAX_PAGE = 100
        const val MAX_VARIANTS = 16
        const val MAX_DIMENSION = 8192
        const val MAX_CACHED_RAW = 512
        val nextTicket = AtomicLong()
    }
}
