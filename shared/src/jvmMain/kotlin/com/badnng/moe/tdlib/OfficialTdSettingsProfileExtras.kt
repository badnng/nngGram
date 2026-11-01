package com.badnng.moe.tdlib

import com.badnng.moe.media.validatePreparedProfilePhoto
import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean

interface TdSettingsProfileExtrasSession {
    val stickerAvatar: TdProfileStickerAvatarSession? get() = null
    fun currentProfilePhoto(userId: Long, active: () -> Boolean, invalidated: () -> Unit,
        result: (SettingsResult<SettingsCurrentProfilePhotoRead>) -> Unit,
    ) { result(SettingsResult(error = SettingsError.UNSUPPORTED)) }
    fun acquireCurrentProfilePhotoFile(request: SettingsCurrentProfilePhotoAuthorizedFileRequest, active: () -> Boolean,
        result: (SettingsResult<com.badnng.moe.chat.MediaFile>) -> Unit,
    ): SettingsCurrentProfilePhotoFileLease = unavailableCurrentProfilePhotoFile(result)
    fun acquireHistoryFile(request: SettingsProfilePhotoHistoryAuthorizedFileRequest, active: () -> Boolean,
        result: (SettingsResult<com.badnng.moe.chat.MediaFile>) -> Unit,
    ): SettingsProfilePhotoHistoryFileLease {
        result(SettingsResult(error = SettingsError.UNSUPPORTED))
        return object : SettingsProfilePhotoHistoryFileLease { override fun close() {} }
    }
    fun retireHistoryFiles() {}
    fun retireProfileAnimations() {}
    fun load(userId: Long, isCurrent: () -> Boolean, result: (SettingsResult<SettingsProfileExtrasData>) -> Unit)
    fun setBirthdate(userId: Long, expected: SettingsBirthdate?, desired: SettingsBirthdate?, isCurrent: () -> Boolean,
        result: (SettingsResult<Unit>) -> Unit)
    fun uploadPhoto(userId: Long, expectedPhotoId: Long?, photo: SettingsPreparedProfilePhoto, isCurrent: () -> Boolean,
        result: (SettingsResult<Unit>) -> Unit)
    /** Takes ownership of validationLease on entry; local work keeps its own handle through process exit. */
    fun uploadAnimation(userId: Long, expectedPhotoId: Long?, animation: SettingsPreparedProfileAnimation,
        validationLease: com.badnng.moe.chat.AttachmentLease, isCurrent: () -> Boolean,
        result: (SettingsResult<Unit>) -> Unit,
    ) {
        validationLease.release()
        result(SettingsResult(error = SettingsError.UNSUPPORTED))
    }
    fun removePhoto(userId: Long, photoId: Long, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit)
    fun photoHistoryPage(userId: Long, offset: Int, limit: Int, isCurrent: () -> Boolean,
        result: (SettingsResult<SettingsProfilePhotoHistoryPage>) -> Unit,
    ) { result(SettingsResult(error = SettingsError.UNSUPPORTED)) }
    fun reuseHistoryPhoto(userId: Long, target: SettingsProfilePhotoHistoryTarget, isCurrent: () -> Boolean,
        result: (SettingsResult<Unit>) -> Unit,
    ) { result(SettingsResult(error = SettingsError.UNSUPPORTED)) }
    fun deleteHistoryPhoto(userId: Long, target: SettingsProfilePhotoHistoryTarget, isCurrent: () -> Boolean,
        result: (SettingsResult<Unit>) -> Unit,
    ) { result(SettingsResult(error = SettingsError.UNSUPPORTED)) }
    fun personalChannelCatalog(userId: Long, isCurrent: () -> Boolean,
        result: (SettingsResult<SettingsPersonalChannelCatalog>) -> Unit,
    ) { result(SettingsResult(error = SettingsError.UNSUPPORTED)) }
    fun personalChannelsPage(userId: Long, ids: List<Long>, isCurrent: () -> Boolean,
        result: (SettingsResult<SettingsPersonalChannelsPage>) -> Unit,
    ) { result(SettingsResult(error = SettingsError.UNSUPPORTED)) }
    fun setPersonalChannel(userId: Long, expectedChatId: Long, desiredChatId: Long, isCurrent: () -> Boolean,
        result: (SettingsResult<Unit>) -> Unit,
    ) { result(SettingsResult(error = SettingsError.UNSUPPORTED)) }
}

/** Every mutation revalidates the actual self identity, and completion is always delivered for lease cleanup. */
internal class OfficialTdSettingsProfileExtras(
    private val send: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit,
    private val today: () -> LocalDate = LocalDate::now,
    private val validatePhoto: (SettingsPreparedProfilePhoto) -> Boolean = ::validatePreparedProfilePhoto,
    receiverOrder: ProfileStickerAvatarOrderedReceiver? = null,
) : TdSettingsProfileExtrasSession, TdSavedProfileStickerSession {
    private val nativeStickerAvatar = OfficialTdProfileStickerAvatar(receiverOrder, send)
    override val stickerAvatar: TdProfileStickerAvatarSession get() = nativeStickerAvatar
    internal fun acceptStickerAvatarUpdate(update: TdApi.Object) = nativeStickerAvatar.acceptCatalogUpdate(update)
    internal fun stickerAvatarFiles(downloads: TdDownloadBroker): ProfileStickerAvatarMediaFiles =
        OfficialProfileStickerAvatarMediaFiles(OfficialTdProfileStickerAvatarFiles(nativeStickerAvatar, downloads),
            OfficialTdSavedProfileStickerResolver(send))
    private val photoHistory = OfficialTdSettingsProfilePhotoHistory(send)
    private val currentProfilePhoto = OfficialTdCurrentProfilePhoto(send)
    override fun savedStickerTicketWitness(source: SettingsSavedProfileStickerNativeSource): (() -> Boolean)? = when (source) {
        is SettingsSavedProfileStickerNativeSource.Current -> currentProfilePhoto.savedStickerTicketWitness(source)
        is SettingsSavedProfileStickerNativeSource.History -> photoHistory.savedStickerTicketWitness(source)
    }
    override fun captureSavedSticker(source: SettingsSavedProfileStickerNativeSource,
        onConflict: () -> Unit): SettingsResult<SavedProfileStickerCaptured> = when (source) {
        is SettingsSavedProfileStickerNativeSource.Current -> currentProfilePhoto.captureSavedSticker(source)
        is SettingsSavedProfileStickerNativeSource.History -> photoHistory.captureSavedSticker(source, onConflict)
    }
    private val profileAnimations = OfficialTdProfilePhotoAnimation(send)
    override fun retireProfileAnimations() = profileAnimations.retire()
    override fun uploadAnimation(userId: Long, expectedPhotoId: Long?, animation: SettingsPreparedProfileAnimation,
        validationLease: com.badnng.moe.chat.AttachmentLease, isCurrent: () -> Boolean,
        result: (SettingsResult<Unit>) -> Unit,
    ) = profileAnimations.upload(userId, expectedPhotoId, animation, validationLease, isCurrent, result)
    private val historyFilesLock = Any()
    @Volatile private var historyFiles: OfficialTdProfilePhotoHistoryFiles? = null
    private var historyDownloadsBound = false
    @Volatile private var historyFilesRetired = false
    internal fun bindHistoryDownloads(downloads: TdDownloadBroker) = synchronized(historyFilesLock) {
        check(!historyDownloadsBound) { "History downloads already bound" }
        historyDownloadsBound = true
        if (!historyFilesRetired) historyFiles = OfficialTdProfilePhotoHistoryFiles(photoHistory, downloads, send,
            currentCatalog = currentProfilePhoto)
    }
    override fun currentProfilePhoto(userId: Long, active: () -> Boolean, invalidated: () -> Unit,
        result: (SettingsResult<SettingsCurrentProfilePhotoRead>) -> Unit,
    ) = currentProfilePhoto.read(userId, active, invalidated, result)
    internal fun acceptCurrentProfilePhotoUpdate(update: TdApi.Object) = currentProfilePhoto.accept(update)
    override fun acquireCurrentProfilePhotoFile(request: SettingsCurrentProfilePhotoAuthorizedFileRequest, active: () -> Boolean,
        result: (SettingsResult<com.badnng.moe.chat.MediaFile>) -> Unit,
    ): SettingsCurrentProfilePhotoFileLease = historyFiles?.readCurrent(request, active, result) ?: run {
        result(SettingsResult(error = if (historyFilesRetired) SettingsError.NOT_READY else SettingsError.UNSUPPORTED))
        object : SettingsCurrentProfilePhotoFileLease { override fun close() {} }
    }
    override fun acquireHistoryFile(request: SettingsProfilePhotoHistoryAuthorizedFileRequest, active: () -> Boolean,
        result: (SettingsResult<com.badnng.moe.chat.MediaFile>) -> Unit,
    ): SettingsProfilePhotoHistoryFileLease = historyFiles?.read(request, active, result) ?: run {
        result(SettingsResult(error = if (historyFilesRetired) SettingsError.NOT_READY else SettingsError.UNSUPPORTED))
        object : SettingsProfilePhotoHistoryFileLease { override fun close() {} }
    }
    override fun retireHistoryFiles() {
        val files = synchronized(historyFilesLock) {
            if (historyFilesRetired) return
            historyFilesRetired = true
            historyFiles.also { historyFiles = null }
        }
        photoHistory.retire()
        currentProfilePhoto.retire()
        files?.retire()
    }
    override fun photoHistoryPage(userId: Long, offset: Int, limit: Int, isCurrent: () -> Boolean, result: (SettingsResult<SettingsProfilePhotoHistoryPage>) -> Unit) =
        photoHistory.page(userId, offset, limit, isCurrent, result)
    override fun reuseHistoryPhoto(userId: Long, target: SettingsProfilePhotoHistoryTarget, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) =
        photoHistory.reuse(userId, target, isCurrent, result)
    override fun deleteHistoryPhoto(userId: Long, target: SettingsProfilePhotoHistoryTarget, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) =
        photoHistory.delete(userId, target, isCurrent, result)
    private val personalChannels = OfficialTdSettingsPersonalChannel(send)
    override fun personalChannelCatalog(userId: Long, isCurrent: () -> Boolean, result: (SettingsResult<SettingsPersonalChannelCatalog>) -> Unit) =
        personalChannels.catalog(userId, isCurrent, result)
    override fun personalChannelsPage(userId: Long, ids: List<Long>, isCurrent: () -> Boolean, result: (SettingsResult<SettingsPersonalChannelsPage>) -> Unit) =
        personalChannels.page(userId, ids, isCurrent, result)
    override fun setPersonalChannel(userId: Long, expectedChatId: Long, desiredChatId: Long, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) =
        personalChannels.save(userId, expectedChatId, desiredChatId, isCurrent, result)
    override fun load(userId: Long, isCurrent: () -> Boolean, result: (SettingsResult<SettingsProfileExtrasData>) -> Unit) {
        self(userId, isCurrent) { response ->
            val user = response.value ?: run { result(SettingsResult(error = response.error)); return@self }
            request(TdApi.GetUserFullInfo(userId), isCurrent, { it as? TdApi.UserFullInfo }) { full ->
                val info = full.value ?: run { result(SettingsResult(error = full.error)); return@request }
                val date = today()
                val data = SettingsProfileExtrasData(user.id, user.profilePhoto?.id?.takeIf { it != 0L },
                    info.birthdate?.let { SettingsBirthdate(it.day, it.month, it.year) },
                    SettingsCalendarDate(date.dayOfMonth, date.monthValue, date.year), personalChatId = info.personalChatId)
                if (info.personalChatId == 0L) result(SettingsResult(data))
                else personalChannels.current(info.personalChatId, isCurrent) { channel ->
                    result(SettingsResult(data.copy(personalChannel = channel.value, personalChannelError = channel.error)))
                }
            }
        }
    }

    override fun setBirthdate(userId: Long, expected: SettingsBirthdate?, desired: SettingsBirthdate?,
        isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit,
    ) {
        self(userId, isCurrent) { identity ->
            if (identity.value == null) { result(SettingsResult(error = identity.error)); return@self }
            request(TdApi.GetUserFullInfo(userId), isCurrent, { it as? TdApi.UserFullInfo }) dateRead@ { response ->
                val full = response.value ?: run { result(SettingsResult(error = response.error)); return@dateRead }
                val current = full.birthdate?.let { SettingsBirthdate(it.day, it.month, it.year) }
                val now = today().let { SettingsCalendarDate(it.dayOfMonth, it.monthValue, it.year) }
                if (current != expected) result(SettingsResult(error = SettingsError.CONFLICT))
                else if (desired != null && !desired.isValidProfileBirthdate(now, current))
                    result(SettingsResult(error = SettingsError.INVALID_VALUE))
                else if (desired == expected) result(SettingsResult(Unit))
                else request(TdApi.SetBirthdate(desired?.let { TdApi.Birthdate(it.day, it.month, it.year) }), isCurrent, ::ok, finalWrite = true, result = result)
            }
        }
    }

    override fun uploadPhoto(userId: Long, expectedPhotoId: Long?, photo: SettingsPreparedProfilePhoto,
        isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit,
    ) {
        if (!isCurrent()) { result(SettingsResult(error = SettingsError.NOT_READY)); return }
        if (!runCatching { validatePhoto(photo) }.getOrDefault(false)) {
            result(SettingsResult(error = SettingsError.INVALID_VALUE)); return
        }
        self(userId, isCurrent) { fresh ->
            val user = fresh.value ?: run { result(SettingsResult(error = fresh.error)); return@self }
            if (user.profilePhoto?.id?.takeIf { it != 0L } != expectedPhotoId)
                result(SettingsResult(error = SettingsError.CONFLICT))
            else request(TdApi.SetProfilePhoto(TdApi.InputChatPhotoStatic(TdApi.InputFileLocal(photo.path)), false),
                isCurrent, ::ok, finalWrite = true, result = result)
        }
    }

    override fun removePhoto(userId: Long, photoId: Long, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) {
        if (photoId == 0L) { result(SettingsResult(error = SettingsError.INVALID_VALUE)); return }
        self(userId, isCurrent) { fresh ->
            val user = fresh.value ?: run { result(SettingsResult(error = fresh.error)); return@self }
            if (user.profilePhoto?.id != photoId) result(SettingsResult(error = SettingsError.CONFLICT))
            else request(TdApi.DeleteProfilePhoto(photoId), isCurrent, ::ok, finalWrite = true, result = result)
        }
    }

    private fun self(userId: Long, current: () -> Boolean, result: (SettingsResult<TdApi.User>) -> Unit) {
        if (userId <= 0) { result(SettingsResult(error = SettingsError.INVALID_VALUE)); return }
        request(TdApi.GetMe(), current, { it as? TdApi.User }) { response ->
            if (response.value != null && response.value.id != userId) result(SettingsResult(error = SettingsError.NOT_READY))
            else result(response)
        }
    }
    private fun ok(response: TdApi.Object) = if (response is TdApi.Ok) Unit else null
    private fun <T> request(command: TdApi.Function<*>, current: () -> Boolean, map: (TdApi.Object) -> T?,
        finalWrite: Boolean = false, result: (SettingsResult<T>) -> Unit,
    ) {
        val once = AtomicBoolean()
        fun finish(response: SettingsResult<T>) {
            if (SettingsSpecialMutationContext.receipt()?.outcome == SettingsSpecialWriteOutcome.INDETERMINATE) {
                result(response); return // The original raw callback may still arrive.
            }
            if (once.compareAndSet(false, true)) result(response)
        }
        if (!current()) { finish(SettingsResult(error = SettingsError.NOT_READY)); return }
        val done: (TdApi.Object) -> Unit = { response ->
            if (!finalWrite && !current()) finish(SettingsResult(error = SettingsError.NOT_READY))
            else {
                val mapped = runCatching { map(response) }.getOrNull()
                finish(if (mapped != null) SettingsResult(mapped) else SettingsResult(error = settingsError(response)))
            }
        }
        val failed = { finish(SettingsResult<T>(error = SettingsError.NOT_READY)) }
        try { if (finalWrite) SettingsSpecialMutationContext.write(command, send, done, failed)
            else SettingsSpecialMutationContext.without {
                send(command, { native -> SettingsSpecialMutationContext.withReceipt(null) { done(native) } },
                    { SettingsSpecialMutationContext.withReceipt(null, failed) })
            } }
        catch (_: Exception) { finish(SettingsResult(error = SettingsError.REQUEST_FAILED)) }
    }
}
