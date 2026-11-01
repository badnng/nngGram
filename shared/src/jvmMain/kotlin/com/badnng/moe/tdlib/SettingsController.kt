package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import com.badnng.moe.notifications.*
import java.util.concurrent.atomic.AtomicBoolean

/** The engine actor owns this state. Every native callback re-enters that actor before publication. */
internal class SettingsController(
    private val generation: Long,
    private val session: TdSettingsSession,
    private val enqueue: (() -> Unit) -> Unit,
    private val publish: (SettingsSnapshot) -> Unit,
    private val proxyAccepted: () -> Boolean = { true },
    private val ordinaryAllowed: () -> Boolean = { true },
    soundSource: NotificationSoundSource? = null,
    soundPreview: NotificationSoundPreview? = null,
    soundCatalogChanged: () -> Unit = {},
    soundVolumes: NotificationSoundVolumeStore? = null,
    soundVolumeChanged: () -> Unit = {},
    profileMutationReceipts: SettingsProfileMutationReceipts? = null,
    proxyProbeBudget: SettingsProxyProbeBudget = SettingsProxyProbeBudget(),
    private val enqueueMutation: (SettingsSection, Long, () -> Unit) -> Unit = { _, _, work -> enqueue(work) },
    private val withMutationContext: (() -> Unit) -> Unit = { it() },
    private val readbackRevision: () -> Long = { 0L },
    private val specialTerminal: SettingsSpecialMutationBoundary = SettingsSpecialMutationBoundary(enqueue),
) {
    private var snapshot = SettingsSnapshot(token = SettingsToken(generation, 1))
    private val requests = mutableMapOf<SettingsSection, Long>()
    private val acceptedWrites = mutableMapOf<SettingsSection, Long>()
    private val uncertainWrites = mutableMapOf<SettingsSection, Long>()
    private val mutationInFlight = mutableMapOf<SettingsSection, Long>()
    private val pendingOwnership = mutableMapOf<SettingsSection, () -> Boolean>()
    private val proxyPageRequired = mutableSetOf<SettingsSection>()
    private val readbackAttempts = mutableMapOf<SettingsSection, Long>()
    private var retired = false
    private val proxyChecks = SettingsProxyChecksController(
        source = { snapshot },
        advancedRevision = { requests[SettingsSection.ADVANCED] },
        startProbe = session::probeProxy,
        startAdvancedRead = session::advancedForProxyChecks,
        budget = proxyProbeBudget,
        enqueue = enqueue,
        publish = { if (!retired) { snapshot = snapshot.copy(proxyChecks = it); emit() } },
        requestAdvancedLoad = { token, pageLease -> loadSection(token, SettingsSection.ADVANCED, pageLease) },
        advancedReadResult = ::finishProxyChecksAdvancedRead,
        advancedReadCancelled = ::cancelProxyChecksAdvancedRead,
        startShare = session::shareProxy,
    )
    @Volatile private var privacyEditor: PrivacyEditorToken? = null
    private var nextPrivacyOwner = 0L
    @Volatile private var privacyOpeningRequest: Long? = null
    @Volatile private var privacyQuery: PrivacyPeerQuery? = null
    private var privacyParentRevision = 0L
    private var privacyQueryRevision = -1L
    private val security = SettingsSecurityController(generation, session, enqueue,
        publish = { if (!retired) { snapshot = snapshot.copy(security = it); emit() } },
        terminal = specialTerminal)
    private val usernameAvailability = SettingsUsernameAvailabilityController(session, enqueue) { token, userId ->
        !retired && accepts(token) && snapshot.profile?.userId == userId && ordinaryAllowed()
    }
    private val profileExtras = session.profileExtras?.let { extras ->
        SettingsProfileExtrasController(generation, extras, enqueue,
            publish = { if (!retired) { snapshot = snapshot.copy(profileExtras = it); emit() } },
            profileChanged = { if (!retired && snapshot.enabled) load(snapshot.token, SettingsSection.PROFILE) },
            profileMutationReceipts = profileMutationReceipts, terminal = specialTerminal)
    }
    private val privacyCommon = session.privacyCommon?.let { common ->
        PrivacyCommonController(generation, common, enqueue,
            publish = { if (!retired) { snapshot = snapshot.copy(privacyCommon = it); emit() } },
            terminal = specialTerminal, isAllowed = { !retired && snapshot.enabled && ordinaryAllowed() })
    }
    private val contactSignup = session.contactSignup?.let { signup ->
        ContactSignupController(generation, signup, enqueue,
            publish = { if (!retired) { snapshot = snapshot.copy(contactSignup = it); emit() } },
            terminal = specialTerminal, isAllowed = { !retired && snapshot.enabled && ordinaryAllowed() })
    }
    private val profileColors = session.profileColors?.let { colors ->
        SettingsProfileColorsController(generation, colors, enqueue,
            publish = { if (!retired) { snapshot = snapshot.copy(profileColors = it); emit() } },
            terminal = specialTerminal, isAllowed = { !retired && snapshot.enabled && ordinaryAllowed() })
    }
    private val reactionNotifications = session.reactionNotifications?.let { reactions ->
        ReactionNotificationsController(generation, reactions, enqueue,
            publish = { if (!retired) { snapshot = snapshot.copy(reactionNotifications = it); emit() } },
            terminal = specialTerminal, isAllowed = { !retired && snapshot.enabled && ordinaryAllowed() })
    }
    private val notificationExceptions = session.notificationExceptions?.let { exceptions ->
        NotificationExceptionsController(generation, exceptions, enqueue,
            publish = { if (!retired) { snapshot = snapshot.copy(notificationExceptions = it); emit() } },
            terminal = specialTerminal, isAllowed = { !retired && snapshot.enabled && ordinaryAllowed() })
    }
    private val savedSounds = session.savedNotificationSounds?.let { catalog ->
        val preview = soundPreview ?: object : NotificationSoundPreview {
            override fun play(accountUserId: Long, soundId: Long, volumePercent: Int, active: () -> Boolean,
                result: (SettingsResult<Boolean>) -> Unit,
            ): NotificationSoundLoad {
                result(SettingsResult(error = SettingsError.UNSUPPORTED))
                return NotificationSoundLoad {}
            }
            override fun close() {}
        }
        SavedNotificationSoundsController(generation, catalog, preview, enqueue,
            publish = { if (!retired) { snapshot = snapshot.copy(savedNotificationSounds = it); emit() } },
            isAllowed = ordinaryAllowed,
            invalidateSoundSource = { soundSource?.invalidate(); soundCatalogChanged() },
            warmSound = { account, sound, active, result ->
                soundSource?.warm(account, sound, active, result) ?: NotificationSoundLoad {}.also { result(SettingsResult(Unit)) }
            }, soundVolumes = soundVolumes, soundVolumeChanged = soundVolumeChanged, terminal = specialTerminal)
    }

    fun start() {
        if (retired || snapshot.enabled) return
        snapshot = snapshot.copy(enabled = true)
        emit()
    }

    fun deactivate() {
        if (retired) return
        retired = true
        proxyChecks.retire()
        privacyEditor = null
        privacyOpeningRequest = null
        privacyQuery = null
        usernameAvailability.retire()
        security.deactivate()
        profileExtras?.deactivate()
        privacyCommon?.deactivate()
        contactSignup?.deactivate()
        profileColors?.deactivate()
        reactionNotifications?.deactivate()
        notificationExceptions?.deactivate()
        savedSounds?.deactivate()
        session.retire()
        requests.clear()
        acceptedWrites.clear()
        uncertainWrites.clear()
        mutationInFlight.clear()
        pendingOwnership.clear()
        proxyPageRequired.clear()
        readbackAttempts.clear()
        snapshot = SettingsSnapshot(token = SettingsToken(generation, snapshot.token.revision + 1))
        emit()
    }

    fun accepts(token: SettingsToken) = !retired && snapshot.enabled && snapshot.token == token
    fun openProxyChecks(token: SettingsToken, pageLease: SettingsProxyChecksPageLease,
        opened: (SettingsProxyChecksToken?) -> Unit,
    ) {
        if (!accepts(token) || !ordinaryAllowed() || !pageLease.current()) {
            pageLease.revoke()
            runCatching { opened(null) }
            return
        }
        proxyChecks.open(token, pageLease, opened)
    }
    fun closeProxyChecks(token: SettingsToken, pageLease: SettingsProxyChecksPageLease) =
        proxyChecks.close(token, pageLease)
    fun refreshProxyChecks(token: SettingsToken, pageLease: SettingsProxyChecksPageLease) =
        proxyChecks.refresh(token, pageLease)
    fun shareProxyChecks(token: SettingsToken, pageLease: SettingsProxyChecksPageLease,
        request: SettingsProxyShareRequest, owner: SettingsProxyProbeOwner,
        result: (SettingsResult<SettingsProxyShareContent>) -> Unit,
    ) {
        if (!accepts(token) || !ordinaryAllowed() || !pageLease.current()) {
            runCatching { result(SettingsResult(error = SettingsError.NOT_READY)) }
            return
        }
        proxyChecks.share(token, pageLease, request, result, suppliedOwner = owner)
    }
    fun revokeProxyChecks() = proxyChecks.closeActive()
    fun checkUsername(token: SettingsToken, userId: Long, username: String, current: () -> Boolean,
        result: (SettingsResult<SettingsUsernameAvailability>) -> Unit,
    ) = usernameAvailability.check(token, userId, username, current, result)
    fun acceptsSecurity(token: SettingsSecurityToken) = !retired && snapshot.enabled && security.accepts(token)
    fun acceptsProfileExtras(token: SettingsProfileExtrasToken) = !retired && snapshot.enabled && profileExtras?.accepts(token) == true
    fun acceptsPrivacyCommon(token: PrivacyCommonToken) = !retired && snapshot.enabled && privacyCommon?.accepts(token) == true
    fun acceptsContactSignup(token: ContactSignupToken) = !retired && snapshot.enabled && contactSignup?.accepts(token) == true
    fun acceptsProfileColors(token: SettingsProfileColorsToken) =
        !retired && snapshot.enabled && profileColors?.accepts(token) == true
    fun acceptsReactionNotifications(token: ReactionNotificationsToken) =
        !retired && snapshot.enabled && reactionNotifications?.accepts(token) == true
    fun openReactionNotifications(token: SettingsToken, pageLease: () -> Boolean) {
        if (accepts(token) && pageLease()) reactionNotifications?.open(pageLease)
    }
    fun closeReactionNotifications(token: ReactionNotificationsToken) { reactionNotifications?.close(token) }
    fun refreshReactionNotificationsFromCache(token: ReactionNotificationsToken) { reactionNotifications?.refreshFromCache(token) }
    fun saveReactionNotifications(token: ReactionNotificationsToken, edit: ReactionNotificationsEdit) {
        reactionNotifications?.save(token, edit)
    }
    fun openProfileColors(token: SettingsToken, userId: Long, pageLease: () -> Boolean) {
        if (accepts(token) && pageLease()) profileColors?.open(userId, pageLease)
    }
    fun closeProfileColors(token: SettingsProfileColorsToken) { profileColors?.close(token) }
    fun openCurrentProfilePhoto(token: SettingsProfileExtrasToken, view: () -> Boolean): Long? = profileExtras?.openCurrentPhoto(token, view)
    fun closeCurrentProfilePhoto(token: SettingsProfileExtrasToken, epoch: Long) { profileExtras?.closeCurrentPhoto(token, epoch) }
    fun refreshCurrentProfilePhoto(token: SettingsProfileExtrasToken, epoch: Long): Long? = profileExtras?.refreshCurrentPhoto(token, epoch)
    fun acquireCurrentProfilePhotoFile(request: SettingsCurrentProfilePhotoMediaRequest, active: () -> Boolean,
        result: (SettingsResult<com.badnng.moe.chat.MediaFile>) -> Unit,
    ): SettingsCurrentProfilePhotoFileLease = profileExtras?.acquireCurrentPhotoFile(request, active, result)
        ?: unavailableCurrentProfilePhotoFile(result)
    fun captureSavedProfileSticker(request: SettingsSavedProfileStickerAvatarRequest,
        witnessOnly: Boolean = false,
        demand: SettingsSavedProfileStickerAvatarDemand = SettingsSavedProfileStickerAvatarDemand(),
    ): SettingsResult<SavedProfileStickerActorGrant> =
        profileExtras?.captureSavedProfileSticker(request, witnessOnly, demand) ?: SettingsResult(error = SettingsError.UNSUPPORTED)
    fun savedStickerEpochWitness(request: SettingsSavedProfileStickerAvatarRequest): (() -> Boolean)? =
        profileExtras?.savedStickerEpochWitness(request)
    fun savedStickerTicketWitness(source: SettingsSavedProfileStickerNativeSource): (() -> Boolean)? =
        profileExtras?.savedStickerTicketWitness(source)
    fun openProfilePhotoHistory(token: SettingsProfileExtrasToken, selectorLease: () -> Boolean) {
        profileExtras?.openHistory(token, selectorLease)
    }
    fun closeProfilePhotoHistory(token: SettingsProfileExtrasToken) { profileExtras?.closeHistory(token) }
    fun refreshProfilePhotoHistory(token: SettingsProfileExtrasToken) { profileExtras?.refreshHistory(token) }
    fun moreProfilePhotoHistory(token: SettingsProfileExtrasToken) { profileExtras?.moreHistory(token) }
    fun acquireProfileHistoryFile(request: SettingsProfilePhotoHistoryMediaRequest, active: () -> Boolean,
        result: (SettingsResult<com.badnng.moe.chat.MediaFile>) -> Unit,
    ): SettingsProfilePhotoHistoryFileLease = profileExtras?.acquireHistoryFile(request, active, result)
        ?: unavailableProfilePhotoHistoryFile(result)
    fun reuseProfileHistoryPhoto(token: SettingsProfileExtrasToken, epoch: Long, target: SettingsProfilePhotoHistoryTarget,
        selectionLease: () -> Boolean,
    ) { profileExtras?.reuseHistory(token, epoch, target, selectionLease) }
    fun removeProfileHistoryPhoto(token: SettingsProfileExtrasToken, epoch: Long, target: SettingsProfilePhotoHistoryTarget,
        selectionLease: () -> Boolean,
    ) { profileExtras?.removeHistory(token, epoch, target, selectionLease) }
    fun refreshProfileColors(token: SettingsProfileColorsToken) { profileColors?.refresh(token) }
    fun saveProfileColors(token: SettingsProfileColorsToken, edit: SettingsProfileColorEdit) { profileColors?.save(token, edit) }
    fun openProfileColorBackgrounds(token: SettingsProfileColorsToken) { profileColors?.openBackgrounds(token) }
    fun resolveProfileColorBackgrounds(token: SettingsProfileColorsToken, ids: List<Long>) { profileColors?.resolveBackgrounds(token, ids) }
    fun closeProfileColorBackgrounds(token: SettingsProfileColorsToken) { profileColors?.closeBackgrounds(token) }
    fun acceptsNotificationExceptions(token: NotificationExceptionsToken) =
        !retired && snapshot.enabled && notificationExceptions?.accepts(token) == true
    fun acceptsSavedNotificationSounds(token: SavedNotificationSoundsToken) =
        !retired && snapshot.enabled && savedSounds?.accepts(token) == true
    fun openSavedNotificationSounds(token: SettingsToken, pageLease: () -> Boolean) {
        if (accepts(token) && pageLease()) savedSounds?.open(pageLease)
    }
    fun closeSavedNotificationSounds(token: SavedNotificationSoundsToken) { savedSounds?.close(token) }
    fun refreshSavedNotificationSounds(token: SavedNotificationSoundsToken) { savedSounds?.refresh(token) }
    fun chooseSavedNotificationSound(token: SavedNotificationSoundsToken, revision: Long, soundId: Long,
        result: (SettingsResult<Long>) -> Unit,
    ) {
        val controller = savedSounds
        if (controller == null) result(SettingsResult(error = SettingsError.NOT_READY))
        else controller.choose(token, revision, soundId, result)
    }
    fun previewSavedNotificationSound(token: SavedNotificationSoundsToken, soundId: Long, volume: Int) {
        savedSounds?.preview(token, soundId, volume)
    }
    fun stopSavedNotificationSoundPreview(token: SavedNotificationSoundsToken) { savedSounds?.stopPreview(token) }
    fun readSavedNotificationSoundVolume(token: SavedNotificationSoundsToken, target: NotificationSoundVolumeTarget,
        result: (SettingsResult<NotificationSoundVolumeReading>) -> Unit,
    ) {
        val controller = savedSounds
        if (controller == null) result(SettingsResult(error = SettingsError.NOT_READY))
        else controller.readVolume(token, target, result)
    }
    fun saveSavedNotificationSoundVolume(token: SavedNotificationSoundsToken, expected: NotificationSoundVolumeReading,
        percent: Int?, result: (SettingsResult<NotificationSoundVolumeReading>) -> Unit,
    ) {
        val controller = savedSounds
        if (controller == null) result(SettingsResult(error = SettingsError.NOT_READY))
        else controller.saveVolume(token, expected, percent, result)
    }
    fun uploadSavedNotificationSound(token: SavedNotificationSoundsToken, revision: Long,
        prepared: SettingsPreparedNotificationSound, retained: PendingAttachmentLease,
    ) {
        val controller = savedSounds
        if (controller == null) retained.release() else controller.upload(token, revision, prepared, retained)
    }
    fun removeSavedNotificationSound(token: SavedNotificationSoundsToken, revision: Long, expected: SavedNotificationSound) {
        savedSounds?.remove(token, revision, expected)
    }
    fun openNotificationExceptions(token: SettingsToken, scope: NotificationScope, pageLease: () -> Boolean) {
        if (accepts(token) && pageLease()) notificationExceptions?.open(scope, pageLease)
    }
    fun closeNotificationExceptions(token: NotificationExceptionsToken) { notificationExceptions?.close(token) }
    fun refreshNotificationExceptions(token: NotificationExceptionsToken) { notificationExceptions?.refresh(token) }
    fun moreNotificationExceptions(token: NotificationExceptionsToken) { notificationExceptions?.more(token) }
    fun selectNotificationException(token: NotificationExceptionsToken) { notificationExceptions?.select(token) }
    fun searchNotificationExceptions(token: NotificationExceptionsToken, query: String, remote: Boolean) {
        notificationExceptions?.search(token, query, remote)
    }
    fun editNotificationException(token: NotificationExceptionsToken, chatId: Long) { notificationExceptions?.edit(token, chatId) }
    fun backNotificationExceptions(token: NotificationExceptionsToken) { notificationExceptions?.back(token) }
    fun reloadNotificationException(token: NotificationExceptionsToken) { notificationExceptions?.reloadEditor(token) }
    fun saveNotificationException(token: NotificationExceptionsToken, revision: Long, edit: NotificationExceptionEdit) {
        notificationExceptions?.save(token, revision, edit)
    }
    fun prepareClearNotificationExceptions(token: NotificationExceptionsToken) { notificationExceptions?.prepareClear(token) }
    fun cancelClearNotificationExceptions(token: NotificationExceptionsToken) { notificationExceptions?.cancelClear(token) }
    fun clearNotificationExceptions(token: NotificationExceptionsToken, planId: Long) { notificationExceptions?.clear(token, planId) }
    fun openContactSignup(token: SettingsToken, pageLease: () -> Boolean) {
        if (accepts(token) && pageLease()) contactSignup?.open(pageLease)
    }
    fun closeContactSignup(token: ContactSignupToken) { contactSignup?.close(token) }
    fun refreshContactSignup(token: ContactSignupToken) { contactSignup?.refresh(token) }
    fun saveContactSignup(token: ContactSignupToken, revision: Long, expected: ContactSignupValue, desired: Boolean) {
        contactSignup?.save(token, revision, expected, desired)
    }
    fun openPrivacyCommon(token: SettingsToken, pageLease: () -> Boolean) {
        if (accepts(token) && pageLease()) privacyCommon?.open(pageLease)
    }
    fun closePrivacyCommon(token: PrivacyCommonToken) { privacyCommon?.close(token) }
    fun refreshPrivacyCommon(token: PrivacyCommonToken) { privacyCommon?.refresh(token) }
    fun setPrivacyTopPeers(token: PrivacyCommonToken, expected: Boolean, desired: Boolean) { privacyCommon?.topPeers(token, expected, desired) }
    fun setPrivacyArchive(token: PrivacyCommonToken, expected: PrivacyArchiveSettings, desired: PrivacyArchiveSettings) {
        privacyCommon?.archive(token, expected, desired)
    }
    fun setPrivacyAccountTtl(token: PrivacyCommonToken, expected: Int, desired: Int) { privacyCommon?.accountTtl(token, expected, desired) }
    fun openProfileExtras(token: SettingsToken, userId: Long, pageLease: () -> Boolean) {
        if (accepts(token) && snapshot.profile?.userId == userId && pageLease()) profileExtras?.open(userId, pageLease)
    }
    fun closeProfileExtras(token: SettingsProfileExtrasToken) { profileExtras?.close(token) }
    fun refreshProfileExtras(token: SettingsProfileExtrasToken) { profileExtras?.refresh(token) }
    fun saveProfileBirthdate(token: SettingsProfileExtrasToken, expected: SettingsBirthdate?, desired: SettingsBirthdate?) {
        profileExtras?.birthdate(token, expected, desired)
    }
    fun captureProfileStickerAvatarMedia(parent: SettingsProfileExtrasToken, picker: ProfileStickerAvatarPickerTicket,
        selection: ProfileStickerAvatarSelectionTicket?): (() -> Boolean)? =
        profileExtras?.captureStickerAvatarMedia(parent, picker, selection)

    fun openProfileStickerAvatar(parent: SettingsProfileExtrasToken, active: () -> Boolean,
        result: (SettingsResult<ProfileStickerAvatarPickerTicket>) -> Unit,
    ) {
        val extras = profileExtras ?: return stickerAvatarUnavailable(result)
        extras.openStickerAvatar(parent, active, result)
    }
    fun closeProfileStickerAvatar(parent: SettingsProfileExtrasToken, picker: ProfileStickerAvatarPickerTicket) {
        profileExtras?.closeStickerAvatar(parent, picker)
    }
    fun loadProfileStickerAvatarDefault(parent: SettingsProfileExtrasToken, picker: ProfileStickerAvatarPickerTicket,
        active: () -> Boolean, result: (SettingsResult<ProfileStickerAvatarCandidatePage>) -> Unit,
    ) {
        val extras = profileExtras ?: return stickerAvatarUnavailable(result)
        extras.loadStickerAvatarDefault(parent, picker, active, result)
    }
    fun loadProfileStickerAvatarPacks(parent: SettingsProfileExtrasToken, picker: ProfileStickerAvatarPickerTicket,
        kind: ProfileStickerAvatarPackKind, active: () -> Boolean, result: (SettingsResult<ProfileStickerAvatarPackPage>) -> Unit,
    ) {
        val extras = profileExtras ?: return stickerAvatarUnavailable(result)
        extras.loadStickerAvatarPacks(parent, picker, kind, active, result)
    }
    fun openProfileStickerAvatarPack(parent: SettingsProfileExtrasToken, picker: ProfileStickerAvatarPickerTicket,
        pack: ProfileStickerAvatarPackTicket, active: () -> Boolean, result: (SettingsResult<ProfileStickerAvatarCandidatePage>) -> Unit,
    ) {
        val extras = profileExtras ?: return stickerAvatarUnavailable(result)
        extras.openStickerAvatarPack(parent, picker, pack, active, result)
    }
    fun loadProfileStickerAvatarWindow(parent: SettingsProfileExtrasToken, picker: ProfileStickerAvatarPickerTicket,
        cursor: ProfileStickerAvatarCatalogCursor, active: () -> Boolean,
        result: (SettingsResult<ProfileStickerAvatarCatalogWindow>) -> Unit,
    ) {
        val extras = profileExtras ?: return stickerAvatarUnavailable(result)
        extras.loadStickerAvatarWindow(parent, picker, cursor, active, result)
    }
    fun searchProfileStickerAvatarInstalled(parent: SettingsProfileExtrasToken, picker: ProfileStickerAvatarPickerTicket,
        kind: ProfileStickerAvatarPackKind, query: String, active: () -> Boolean,
        result: (SettingsResult<ProfileStickerAvatarPackPage>) -> Unit,
    ) {
        val extras = profileExtras ?: return stickerAvatarUnavailable(result)
        extras.searchStickerAvatarInstalled(parent, picker, kind, query, active, result)
    }
    fun selectProfileStickerAvatar(parent: SettingsProfileExtrasToken, picker: ProfileStickerAvatarPickerTicket,
        candidate: ProfileStickerAvatarCandidateTicket, active: () -> Boolean,
        result: (SettingsResult<ProfileStickerAvatarSelectionTicket>) -> Unit,
    ) {
        val extras = profileExtras ?: return stickerAvatarUnavailable(result)
        extras.selectStickerAvatar(parent, picker, candidate, active, result)
    }
    fun saveProfileStickerAvatar(parent: SettingsProfileExtrasToken, picker: ProfileStickerAvatarPickerTicket,
        selection: ProfileStickerAvatarSelectionTicket, expectedPhotoId: Long?, background: ProfileStickerAvatarBackground,
        active: () -> Boolean, admitted: (Long?) -> Unit, result: (ProfileStickerAvatarMutationResult) -> Unit,
    ) {
        val extras = profileExtras
        if (extras == null) { runCatching { admitted(null) }; return }
        extras.saveStickerAvatar(parent, picker, selection, expectedPhotoId, background, active, admitted, result)
    }
    private fun <T> stickerAvatarUnavailable(result: (SettingsResult<T>) -> Unit) {
        runCatching { result(SettingsResult(error = SettingsError.UNSUPPORTED)) }
    }
    fun uploadProfilePhoto(token: SettingsProfileExtrasToken, expectedPhotoId: Long?, photo: SettingsPreparedProfilePhoto,
        retained: PendingAttachmentLease,
    ) {
        val extras = profileExtras
        if (extras != null) extras.upload(token, expectedPhotoId, photo, retained) else retained.release()
    }
    fun removeProfilePhoto(token: SettingsProfileExtrasToken, photoId: Long) { profileExtras?.removePhoto(token, photoId) }
    fun uploadProfileAnimation(token: SettingsProfileExtrasToken, expectedPhotoId: Long?, animation: SettingsPreparedProfileAnimation,
        retained: PendingAttachmentLease, validationLease: com.badnng.moe.chat.AttachmentLease,
    ): Long? {
        val extras = profileExtras
        if (extras != null) return extras.uploadAnimation(token, expectedPhotoId, animation, retained, validationLease)
        retained.release(); validationLease.release()
        return null
    }
    fun openProfilePersonalChannels(token: SettingsProfileExtrasToken, selectorLease: () -> Boolean) { profileExtras?.openPersonalChannels(token, selectorLease) }
    fun closeProfilePersonalChannels(token: SettingsProfileExtrasToken) { profileExtras?.closePersonalChannels(token) }
    fun moreProfilePersonalChannels(token: SettingsProfileExtrasToken) { profileExtras?.morePersonalChannels(token) }
    fun saveProfilePersonalChannel(token: SettingsProfileExtrasToken, expectedChatId: Long, desiredChatId: Long) {
        profileExtras?.savePersonalChannel(token, expectedChatId, desiredChatId)
    }
    fun openSecurity(token: SettingsToken, section: SettingsSecuritySection) {
        if (accepts(token)) security.open(section)
    }
    fun closeSecurity(token: SettingsSecurityToken) = security.close(token)
    fun refreshSecurity(token: SettingsSecurityToken) = security.refresh(token)
    fun password(token: SettingsSecurityToken, command: SettingsPasswordCommand,
        result: (SettingsResult<SettingsPasswordOutcome>) -> Unit,
    ) = security.password(token, command, result)
    fun disconnectWebsite(token: SettingsSecurityToken, target: SettingsWebsite, blockBot: Boolean,
        result: (SettingsResult<SettingsWebsiteDisconnectOutcome>) -> Unit,
    ) = security.disconnect(token, target, blockBot, result)
    fun disconnectAllWebsites(token: SettingsSecurityToken, result: (SettingsResult<Unit>) -> Unit) = security.disconnectAll(token, result)
    fun openBlockedPicker(token: SettingsSecurityToken) = security.openBlockedPicker(token)
    fun closeBlockedPicker(token: SettingsBlockedPickerToken) = security.closeBlockedPicker(token)
    fun searchBlockedPicker(token: SettingsBlockedPickerToken, query: SettingsBlockedPeerQuery) = security.searchBlockedPicker(token, query)
    fun blockPickerUser(token: SettingsBlockedPickerToken, userId: Long, result: (SettingsResult<Unit>) -> Unit) =
        security.blockPickerUser(token, userId, result)
    fun moreBlocked(token: SettingsSecurityToken) = security.moreBlocked(token)
    fun unblockSecurity(token: SettingsSecurityToken, sender: SettingsBlockedSender, result: (SettingsResult<Unit>) -> Unit) =
        security.unblock(token, sender, result)
    fun terminateSecuritySession(token: SettingsSecurityToken, id: Long, result: (SettingsResult<Unit>) -> Unit) = security.terminate(token, id, result)
    fun terminateOtherSecuritySessions(token: SettingsSecurityToken, expected: List<SettingsSession>, result: (SettingsResult<Unit>) -> Unit) =
        security.terminateOthers(token, expected, result)
    fun saveInactiveSessionTtl(token: SettingsSecurityToken, expected: Int, desired: Int, result: (SettingsResult<Unit>) -> Unit) =
        security.inactiveTtl(token, expected, desired, result)
    fun saveDefaultMessageAutoDeleteTime(token: SettingsSecurityToken, expected: Int, desired: Int, result: (SettingsResult<Unit>) -> Unit) =
        security.defaultAutoDelete(token, expected, desired, result)

    fun load(token: SettingsToken, section: SettingsSection) = loadSection(token, section,
        if (section == SettingsSection.ADVANCED) proxyChecks.activePage(token) else null)

    private fun loadSection(token: SettingsToken, section: SettingsSection, pageLease: SettingsProxyChecksPageLease?) {
        if (!accepts(token) || pageLease?.current() == false) return
        if (acceptedWrites[section] != null || uncertainWrites[section] != null) {
            readbackAttempts.remove(section)
            resumePendingWrites()
            return
        }
        if (mutationInFlight[section] != null) return
        if (section in snapshot.saving) return
        val previousRevision = requests[section]
        if (section == SettingsSection.ADVANCED) proxyChecks.invalidateAdvanced()
        if (!accepts(token) || requests[section] != previousRevision || section in snapshot.saving ||
            pageLease?.current() == false || (pageLease != null && !proxyChecks.matches(token, pageLease))) return
        val revision = next(section)
        snapshot = snapshot.copy(loading = snapshot.loading + section, errors = snapshot.errors - section)
        emit()
        // publish is foreign code and can synchronously close the page or start a newer load.
        if (!accepts(token) || requests[section] != revision || section !in snapshot.loading) return
        if (pageLease == null) read(section, revision)
        else if (!proxyChecks.queueAdvancedRead(token, pageLease, revision) && requests[section] == revision &&
            section in snapshot.loading) {
            snapshot = snapshot.copy(loading = snapshot.loading - section)
            emit()
        }
    }

    fun saveProfile(token: SettingsToken, edit: SettingsProfileEdit) =
        mutate(token, SettingsSection.PROFILE, { session.saveProfile(edit, it) })

    fun saveNotifications(token: SettingsToken, scope: NotificationScope, value: NotificationPreferences) {
        if (snapshot.notifications[scope] == null) return
        mutate(token, SettingsSection.NOTIFICATIONS, { session.saveNotifications(scope, value, it) })
    }

    fun savePrivacy(token: SettingsToken, key: PrivacyKey, base: PrivacyBase) {
        if (snapshot.privacy[key]?.editable != true) return
        mutate(token, SettingsSection.PRIVACY, { session.savePrivacy(key, base, it) })
    }

    fun openPrivacyEditor(token: SettingsToken, key: PrivacyKey, requestId: Long, result: (SettingsResult<PrivacyEditorToken>) -> Unit) {
        if (requestId == 0L || !canMutate(token, SettingsSection.PRIVACY) || snapshot.privacy[key]?.editable != true) {
            result(SettingsResult(error = SettingsError.NOT_READY)); return
        }
        privacyQuery = null
        privacyParentRevision = 0
        privacyQueryRevision = -1
        val editor = PrivacyEditorToken(generation, ++nextPrivacyOwner, key)
        privacyEditor = editor
        privacyOpeningRequest = requestId
        val complete = AtomicBoolean()
        val current = { !retired && privacyEditor == editor }
        fun finish(response: SettingsResult<Unit>) {
            if (complete.compareAndSet(false, true)) enqueue {
                if (current()) {
                    if (response.value != null) result(SettingsResult(editor))
                    else { privacyEditor = null; privacyOpeningRequest = null; result(SettingsResult(error = response.error)) }
                }
            }
        }
        try { session.privacyEditorAllowed(key, current, ::finish) }
        catch (_: Exception) { finish(SettingsResult(error = SettingsError.REQUEST_FAILED)) }
    }

    fun cancelPrivacyEditorOpening(token: SettingsToken, requestId: Long) {
        if (accepts(token) && privacyOpeningRequest == requestId) {
            privacyOpeningRequest = null; privacyEditor = null; privacyQuery = null
        }
    }

    fun closePrivacyEditor(token: SettingsToken, editor: PrivacyEditorToken) {
        if (accepts(token) && privacyEditor == editor) { privacyEditor = null; privacyQuery = null; privacyOpeningRequest = null }
    }

    fun privacyPeers(token: SettingsToken, editor: PrivacyEditorToken, query: PrivacyPeerQuery,
        result: (SettingsResult<SettingsPrivacyPeers>) -> Unit,
    ) {
        if (!accepts(token) || privacyEditor != editor || editor.generation != generation ||
            query.parentRevision < privacyParentRevision || query.queryRevision < 0 ||
            query.queryRevision <= privacyQueryRevision) {
            result(SettingsResult(error = SettingsError.NOT_READY)); return
        }
        privacyQuery = query
        privacyParentRevision = query.parentRevision
        privacyQueryRevision = query.queryRevision
        val completed = AtomicBoolean()
        val current = { !retired && privacyEditor == editor && privacyQuery == query }
        fun finish(value: SettingsResult<SettingsPrivacyPeers>) {
            if (completed.compareAndSet(false, true)) enqueue { if (current()) result(value) }
        }
        try { session.privacyPeers(query, current, ::finish) }
        catch (_: Exception) { finish(SettingsResult(error = SettingsError.REQUEST_FAILED)) }
    }

    fun savePrivacyDraft(token: SettingsToken, editor: PrivacyEditorToken, draft: PrivacyDraft) {
        if (!canMutate(token, SettingsSection.PRIVACY) || privacyEditor != editor || editor.generation != generation || snapshot.privacy[editor.key] != draft.source ||
            draft.revision < privacyParentRevision) return
        privacyParentRevision = draft.revision
        privacyQuery = null
        mutate(token, SettingsSection.PRIVACY, { result ->
            session.savePrivacyDraft(editor.key, draft, { !retired && privacyEditor == editor }, result)
        })
    }

    fun setAccountTtl(token: SettingsToken, days: Int) {
        if (snapshot.accountTtlDays == null) return
        mutate(token, SettingsSection.PRIVACY, { session.setAccountTtl(days, it) })
    }

    fun terminateSession(token: SettingsToken, id: Long) {
        if (snapshot.sessions.none { it.id == id && !it.isCurrent }) return
        mutate(token, SettingsSection.PRIVACY, { session.terminateSession(id, it) })
    }

    fun unblock(token: SettingsToken, sender: SettingsBlockedSender) {
        if (snapshot.blocked.none { it.id == sender.id && it.isChat == sender.isChat }) return
        mutate(token, SettingsSection.PRIVACY, { session.unblock(sender, it) })
    }

    fun optimizeStorage(token: SettingsToken) {
        if (!canMutate(token, SettingsSection.ADVANCED) || snapshot.storage == null) return
        val section = SettingsSection.ADVANCED
        val revision = beginMutation(token, section) ?: return
        safely(section, revision, { session.optimizeStorage(it) }) { response ->
            val storage = response.value
            if (storage == null) failed(section, response.error ?: SettingsError.REQUEST_FAILED)
            else {
                snapshot = snapshot.copy(storage = storage, saving = snapshot.saving - section, errors = snapshot.errors - section,
                    completedSaveRevision = snapshot.completedSaveRevision + (section to revision))
                emit()
                proxyChecks.refreshIfOpen()
            }
        }
    }

    fun addProxy(token: SettingsToken, draft: SettingsProxyDraft) =
        mutateProxy(token) { session.addProxy(draft, it) }

    fun enableProxy(token: SettingsToken, id: Int?) {
        if (id != null && snapshot.proxies.none { it.id == id }) return
        mutateProxy(token) { session.enableProxy(id, it) }
    }

    fun removeProxy(token: SettingsToken, id: Int) {
        if (snapshot.proxies.none { it.id == id }) return
        mutateProxy(token) { session.removeProxy(id, it) }
    }

    private fun mutateProxy(token: SettingsToken, submit: ((SettingsResult<Unit>) -> Unit) -> Unit) =
        mutate(token, SettingsSection.ADVANCED, submit, afterAccepted = proxyAccepted,
            proxyPageAtStart = proxyChecks.registeredPage(token))

    private fun canMutate(token: SettingsToken, section: SettingsSection) = accepts(token) &&
        section in snapshot.loaded && section !in snapshot.loading && section !in snapshot.saving &&
        acceptedWrites[section] == null && uncertainWrites[section] == null && mutationInFlight[section] == null

    private fun mutate(token: SettingsToken, section: SettingsSection, submit: ((SettingsResult<Unit>) -> Unit) -> Unit,
        afterAccepted: () -> Boolean = { true },
        proxyPageAtStart: SettingsProxyChecksPageLease? = null,
    ) {
        if (!canMutate(token, section)) return
        val revision = beginMutation(token, section) ?: return
        mutationInFlight[section] = revision
        if (section == SettingsSection.ADVANCED && proxyPageAtStart != null) {
            proxyPageRequired.add(section)
        }
        safely(section, revision, submit, mutation = true) { response ->
            if (mutationInFlight[section] != revision) return@safely
            mutationInFlight.remove(section)
            if (response.value == null) failed(section, response.error ?: SettingsError.REQUEST_FAILED)
            else {
                acceptedWrites[section] = revision
                (proxyPageAtStart ?: if (section == SettingsSection.ADVANCED) proxyChecks.activePage(token) else null)
                    ?.let { proxyPageRequired.add(section) }
                if (!afterAccepted()) {
                    pendingOwnership[section] = afterAccepted
                    snapshot = snapshot.copy(saving = snapshot.saving - section,
                        errors = snapshot.errors + (section to SettingsError.REQUEST_FAILED))
                    emit()
                } else if (ordinaryAllowed()) resumePendingWrites()
            }
        }
    }

    /** The transport failed after admission: the original native effect is unknown, never a proven rejection. */
    fun mutationOutcomeUnknown(section: SettingsSection, revision: Long) {
        if (retired || mutationInFlight[section] != revision || requests[section] != revision) return
        mutationInFlight.remove(section)
        uncertainWrites[section] = revision
        if (section == SettingsSection.ADVANCED) {
            pendingOwnership[section] = proxyAccepted
        }
        readbackAttempts.remove(section)
        snapshot = snapshot.copy(saving = snapshot.saving - section,
            errors = snapshot.errors + (section to SettingsError.REQUEST_FAILED))
        emit()
    }

    /** Call on the current actor after reopening, never from the old native callback context. */
    fun resumePendingWrites() {
        if (retired || !snapshot.enabled || !ordinaryAllowed()) return
        val epoch = readbackRevision()
        val pending = (acceptedWrites.keys + uncertainWrites.keys).toList()
        for (section in pending) {
            val revision = acceptedWrites[section] ?: uncertainWrites[section] ?: continue
            if (requests[section] != revision || readbackAttempts[section] == epoch) continue
            readbackAttempts[section] = epoch
            val markOwnership = pendingOwnership[section]
            if (markOwnership != null) {
                if (!markOwnership()) {
                    snapshot = snapshot.copy(saving = snapshot.saving - section,
                        errors = snapshot.errors + (section to SettingsError.REQUEST_FAILED))
                    emit()
                    continue
                }
                pendingOwnership.remove(section)
            }
            val page = if (section == SettingsSection.ADVANCED && section in proxyPageRequired)
                proxyChecks.activePage(snapshot.token) else null
            if (section in proxyPageRequired && page == null) {
                snapshot = snapshot.copy(saving = snapshot.saving - section,
                    errors = snapshot.errors + (section to SettingsError.NOT_READY))
                emit()
                continue
            }
            snapshot = snapshot.copy(saving = snapshot.saving + section, errors = snapshot.errors - section)
            emit()
            if (!ordinaryAllowed() || readbackRevision() != epoch || requests[section] != revision ||
                (acceptedWrites[section] != revision && uncertainWrites[section] != revision)) {
                readbackAttempts.remove(section)
                continue
            }
            if (page != null) {
                if (!proxyChecks.queueAdvancedRead(snapshot.token, page, revision)) {
                    snapshot = snapshot.copy(saving = snapshot.saving - section,
                        errors = snapshot.errors + (section to SettingsError.NOT_READY))
                    emit()
                }
            } else {
                read(section, revision)
            }
        }
    }

    private fun beginMutation(token: SettingsToken, section: SettingsSection): Long? {
        val previousRevision = requests[section]
        if (section == SettingsSection.ADVANCED) proxyChecks.invalidateAdvanced()
        if (requests[section] != previousRevision || !canMutate(token, section)) return null
        val revision = next(section)
        snapshot = snapshot.copy(saving = snapshot.saving + section, errors = snapshot.errors - section,
            startedSaveRevision = snapshot.startedSaveRevision + (section to revision))
        emit()
        return revision.takeIf { accepts(token) && requests[section] == revision && section in snapshot.saving }
    }

    private fun read(section: SettingsSection, revision: Long) {
        when (section) {
            SettingsSection.PROFILE -> safely(section, revision, session::profile) { response ->
                response.value?.let { snapshot = snapshot.copy(profile = it); loaded(section) }
                    ?: failed(section, response.error ?: SettingsError.REQUEST_FAILED)
            }
            SettingsSection.NOTIFICATIONS -> safely(section, revision, session::notifications) { response ->
                response.value?.let { snapshot = snapshot.copy(notifications = it); loaded(section) }
                    ?: failed(section, response.error ?: SettingsError.REQUEST_FAILED)
            }
            SettingsSection.PRIVACY -> safely(section, revision, session::privacy) { response ->
                response.value?.let {
                    snapshot = snapshot.copy(privacy = it.rules, privacyErrors = it.errors,
                        accountTtlDays = it.accountTtlDays, sessions = it.sessions, blocked = it.blocked,
                        blockedHasMore = it.blocked.size >= 100)
                    loaded(section, it.managementError)
                } ?: failed(section, response.error ?: SettingsError.REQUEST_FAILED)
            }
            SettingsSection.ADVANCED -> safely(section, revision, session::advanced) { response ->
                response.value?.let {
                    snapshot = snapshot.copy(storage = it.storage, proxies = it.proxies)
                    loaded(section)
                    proxyChecks.advancedLoaded(revision)
                } ?: failed(section, response.error ?: SettingsError.REQUEST_FAILED)
            }
        }
    }

    private fun next(section: SettingsSection): Long = ((requests[section] ?: 0L) + 1L).also { requests[section] = it }

    private fun finishProxyChecksAdvancedRead(token: SettingsToken, revision: Long, response: SettingsResult<SettingsAdvancedData>) {
        val section = SettingsSection.ADVANCED
        if (!accepts(token) || requests[section] != revision) return
        val value = response.value
        if (response.error != null || value == null) failed(section, response.error ?: SettingsError.REQUEST_FAILED)
        else {
            snapshot = snapshot.copy(storage = value.storage, proxies = value.proxies)
            loaded(section)
            proxyChecks.advancedLoaded(revision)
        }
    }

    private fun cancelProxyChecksAdvancedRead(token: SettingsToken, revision: Long) {
        val section = SettingsSection.ADVANCED
        if (!accepts(token) || requests[section] != revision) return
        if (mutationInFlight[section] == revision || acceptedWrites[section] == revision || uncertainWrites[section] == revision) {
            readbackAttempts.remove(section)
            snapshot = snapshot.copy(loading = snapshot.loading - section, saving = snapshot.saving - section,
                errors = snapshot.errors + (section to SettingsError.NOT_READY))
            emit()
            // Page revocation can happen under its owner lock. Reconcile later on the actor.
            enqueue { resumePendingWrites() }
            return
        }
        val wasSaving = section in snapshot.saving
        val accepted = acceptedWrites.remove(section)
        snapshot = snapshot.copy(loading = snapshot.loading - section, saving = snapshot.saving - section,
            errors = if (wasSaving && accepted == revision) snapshot.errors + (section to SettingsError.NOT_READY) else snapshot.errors,
            failedSaveRevision = if (wasSaving && accepted == revision) snapshot.failedSaveRevision + (section to revision)
                else snapshot.failedSaveRevision)
        emit()
    }

    private fun loaded(section: SettingsSection, error: SettingsError? = null) {
        val accepted = acceptedWrites[section]
        val uncertain = uncertainWrites[section]
        if (error != null && (accepted != null || uncertain != null)) {
            snapshot = snapshot.copy(loading = snapshot.loading - section, saving = snapshot.saving - section,
                errors = snapshot.errors + (section to error))
            emit()
            return
        }
        acceptedWrites.remove(section)
        uncertainWrites.remove(section)
        pendingOwnership.remove(section)
        proxyPageRequired.remove(section)
        readbackAttempts.remove(section)
        snapshot = snapshot.copy(loading = snapshot.loading - section, saving = snapshot.saving - section,
            loaded = snapshot.loaded + section,
            errors = if (uncertain != null) snapshot.errors + (section to SettingsError.REQUEST_FAILED)
                else if (error == null) snapshot.errors - section else snapshot.errors + (section to error),
            completedSaveRevision = if (error == null && accepted == requests[section])
                snapshot.completedSaveRevision + (section to checkNotNull(accepted)) else snapshot.completedSaveRevision,
        )
        emit()
    }

    private fun failed(section: SettingsSection, error: SettingsError) {
        if (acceptedWrites[section] != null || uncertainWrites[section] != null) {
            // Readback failure does not undo or disprove a prior native write.
            snapshot = snapshot.copy(loading = snapshot.loading - section, saving = snapshot.saving - section,
                errors = snapshot.errors + (section to error))
            if (section == SettingsSection.ADVANCED) proxyChecks.advancedFailed()
            emit()
            return
        }
        mutationInFlight.remove(section)
        pendingOwnership.remove(section)
        proxyPageRequired.remove(section)
        readbackAttempts.remove(section)
        acceptedWrites.remove(section)
        val failedRevision = requests[section]?.takeIf { section in snapshot.saving }
        snapshot = snapshot.copy(loading = snapshot.loading - section, saving = snapshot.saving - section,
            errors = snapshot.errors + (section to error),
            failedSaveRevision = if (failedRevision != null) snapshot.failedSaveRevision + (section to failedRevision)
                else snapshot.failedSaveRevision)
        if (section == SettingsSection.ADVANCED) proxyChecks.advancedFailed()
        emit()
    }

    private fun <T> safely(section: SettingsSection, revision: Long,
        submit: ((SettingsResult<T>) -> Unit) -> Unit, mutation: Boolean = false, receive: (SettingsResult<T>) -> Unit,
    ) {
        val completed = AtomicBoolean()
        fun finish(response: SettingsResult<T>) {
            if (!completed.compareAndSet(false, true)) return
            val deliver = {
                if (!retired && snapshot.enabled && requests[section] == revision) receive(response)
            }
            if (mutation) enqueueMutation(section, revision, deliver) else enqueue(deliver)
        }
        try {
            if (mutation) withMutationContext { submit(::finish) } else submit(::finish)
        } catch (_: Exception) { finish(SettingsResult(error = SettingsError.REQUEST_FAILED)) }
    }

    private fun emit() = publish(snapshot)
}
