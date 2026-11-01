package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

interface TdSettingsSession {
    val profileExtras: TdSettingsProfileExtrasSession? get() = null
    val storage: TdSettingsStorageSession? get() = null
    val storagePolicy: TdSettingsStoragePolicySession? get() = null
    val privacyCommon: TdPrivacyCommonSession? get() = null
    val contactSignup: TdContactSignupSession? get() = null
    val notificationExceptions: TdNotificationExceptionsSession? get() = null
    val savedNotificationSounds: TdSavedNotificationSoundsSession? get() = null
    val profileColors: TdSettingsProfileColorsSession? get() = null
    val reactionNotifications: TdReactionNotificationsSession? get() = null
    /** Stops chained native work when its owning account generation is retired. */
    fun retire() {}
    fun observeSecurityChanges(observer: (() -> Unit)?) {}
    fun profile(result: (SettingsResult<SettingsProfile>) -> Unit)
    fun saveProfile(edit: SettingsProfileEdit, result: (SettingsResult<Unit>) -> Unit)
    fun checkUsername(userId: Long, username: String, isCurrent: () -> Boolean,
        result: (SettingsResult<SettingsUsernameAvailability>) -> Unit,
    ) { result(SettingsResult(error = SettingsError.UNSUPPORTED)) }
    fun notifications(result: (SettingsResult<Map<NotificationScope, NotificationPreferences>>) -> Unit)
    fun saveNotifications(scope: NotificationScope, value: NotificationPreferences, result: (SettingsResult<Unit>) -> Unit)
    fun privacy(result: (SettingsResult<SettingsPrivacyData>) -> Unit)
    fun savePrivacy(key: PrivacyKey, base: PrivacyBase, result: (SettingsResult<Unit>) -> Unit)
    fun savePrivacyDraft(key: PrivacyKey, draft: PrivacyDraft, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) {
        result(SettingsResult(error = SettingsError.UNSUPPORTED))
    }
    fun privacyPeers(query: PrivacyPeerQuery, isCurrent: () -> Boolean, result: (SettingsResult<SettingsPrivacyPeers>) -> Unit) {
        result(SettingsResult(error = SettingsError.UNSUPPORTED))
    }
    fun privacyEditorAllowed(key: PrivacyKey, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) {
        result(SettingsResult(Unit))
    }
    fun setAccountTtl(days: Int, result: (SettingsResult<Unit>) -> Unit)
    fun terminateSession(id: Long, result: (SettingsResult<Unit>) -> Unit)
    fun unblock(sender: SettingsBlockedSender, result: (SettingsResult<Unit>) -> Unit)
    fun advanced(result: (SettingsResult<SettingsAdvancedData>) -> Unit)
    /** One budgeted page read; legacy sessions do not initiate native work. */
    fun advancedForProxyChecks(lease: SettingsProxyProbeLease, result: (SettingsResult<SettingsAdvancedData>) -> Unit) {
        if (!lease.beginProbe()) { result(SettingsResult(error = SettingsError.CONFLICT)); return }
        lease.finishProbe()
        result(SettingsResult(error = SettingsError.UNSUPPORTED))
    }
    fun optimizeStorage(result: (SettingsResult<SettingsStorage>) -> Unit)
    fun addProxy(draft: SettingsProxyDraft, result: (SettingsResult<Unit>) -> Unit)
    fun enableProxy(id: Int?, result: (SettingsResult<Unit>) -> Unit)
    fun removeProxy(id: Int, result: (SettingsResult<Unit>) -> Unit)
    /** The caller supplies a revocable owner and bounds physical native work. */
    fun pingProxy(expected: SettingsProxy, isCurrent: () -> Boolean,
        result: (SettingsResult<SettingsProxyLatency>) -> Unit,
    ) { result(SettingsResult(error = SettingsError.UNSUPPORTED)) }
    /** Each call consumes one lease; the legacy default sends no native request. */
    fun probeProxy(expected: SettingsProxy, lease: SettingsProxyProbeLease,
        result: (SettingsResult<SettingsProxyProbe>) -> Unit,
    ) {
        if (!lease.beginProbe()) { result(SettingsResult(error = SettingsError.CONFLICT)); return }
        lease.finishProbe()
        result(SettingsResult(error = SettingsError.UNSUPPORTED))
    }
    /** A single permit owns the complete, serial source/link/source chain. */
    fun shareProxy(target: SettingsProxyShareTarget, isHttp: Boolean, lease: SettingsProxyProbeLease,
        result: (SettingsResult<List<String>>) -> Unit,
    ) {
        if (!lease.beginProbe()) { result(SettingsResult(error = SettingsError.CONFLICT)); return }
        lease.finishProbe()
        result(SettingsResult(error = SettingsError.UNSUPPORTED))
    }
    fun passwordState(isCurrent: () -> Boolean, result: (SettingsResult<SettingsPasswordState>) -> Unit) {
        result(SettingsResult(error = SettingsError.UNSUPPORTED))
    }
    fun password(command: SettingsPasswordCommand, isCurrent: () -> Boolean, result: (SettingsResult<SettingsPasswordOutcome>) -> Unit) {
        result(SettingsResult(error = SettingsError.UNSUPPORTED))
    }
    fun passwordForOwner(token: SettingsSecurityToken, command: SettingsPasswordCommand, isCurrent: () -> Boolean,
        result: (SettingsResult<SettingsPasswordOutcome>) -> Unit,
    ) = password(command, isCurrent, result)
    fun retirePasswordOwner(token: SettingsSecurityToken) {}
    fun websites(isCurrent: () -> Boolean, result: (SettingsResult<SettingsWebsitesData>) -> Unit) {
        result(SettingsResult(error = SettingsError.UNSUPPORTED))
    }
    fun disconnectWebsite(target: SettingsWebsite, blockBot: Boolean, isCurrent: () -> Boolean,
        result: (SettingsResult<SettingsWebsiteDisconnectOutcome>) -> Unit,
    ) { result(SettingsResult(error = SettingsError.UNSUPPORTED)) }
    fun disconnectAllWebsites(isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) {
        result(SettingsResult(error = SettingsError.UNSUPPORTED))
    }
    fun blockedPage(offset: Int, isCurrent: () -> Boolean, result: (SettingsResult<SettingsBlockedPage>) -> Unit) {
        result(SettingsResult(error = SettingsError.UNSUPPORTED))
    }
    fun blockedCandidates(query: SettingsBlockedPeerQuery, isCurrent: () -> Boolean,
        result: (SettingsResult<SettingsBlockedCandidates>) -> Unit,
    ) { result(SettingsResult(error = SettingsError.UNSUPPORTED)) }
    fun blockSecurity(userId: Long, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) {
        result(SettingsResult(error = SettingsError.UNSUPPORTED))
    }
    fun securitySessions(isCurrent: () -> Boolean, result: (SettingsResult<List<SettingsSession>>) -> Unit) {
        result(SettingsResult(error = SettingsError.UNSUPPORTED))
    }
    fun securitySessionsData(isCurrent: () -> Boolean, result: (SettingsResult<SettingsSessionsData>) -> Unit) {
        securitySessions(isCurrent) { result(SettingsResult(it.value?.let(::SettingsSessionsData), it.error)) }
    }
    fun terminateOtherSecuritySessions(expected: List<SettingsSession>, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) {
        result(SettingsResult(error = SettingsError.UNSUPPORTED))
    }
    fun setInactiveSessionTtl(expected: Int, desired: Int, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) {
        result(SettingsResult(error = SettingsError.UNSUPPORTED))
    }
    fun defaultMessageAutoDeleteTime(isCurrent: () -> Boolean, result: (SettingsResult<Int>) -> Unit) {
        result(SettingsResult(error = SettingsError.UNSUPPORTED))
    }
    fun setDefaultMessageAutoDeleteTime(expected: Int, desired: Int, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) {
        result(SettingsResult(error = SettingsError.UNSUPPORTED))
    }
    fun unblockSecurity(sender: SettingsBlockedSender, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) {
        result(SettingsResult(error = SettingsError.UNSUPPORTED))
    }
    fun terminateSecuritySession(id: Long, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) {
        result(SettingsResult(error = SettingsError.UNSUPPORTED))
    }
}

data class SettingsPrivacyData(
    val rules: Map<PrivacyKey, PrivacyRules>, val errors: Map<PrivacyKey, SettingsError>,
    val accountTtlDays: Int?, val sessions: List<SettingsSession>, val blocked: List<SettingsBlockedSender>,
    val managementError: SettingsError? = null,
)
data class SettingsAdvancedData(val storage: SettingsStorage?, val proxies: List<SettingsProxy>)
data class SettingsWebsitesData(val entries: List<SettingsWebsite>, val totalCount: Int)

/** Pinned Java API boundary; UI never receives a TDLib object or a native error message. */
internal class OfficialTdSettingsSession(
    sharedLoginEmail: OfficialTdLoginEmail? = null,
    sharedChatNotifications: NativeChatNotificationMutations? = null,
    storageSend: SettingsStorageNativeSend? = null,
    profileStickerReceiverOrder: ProfileStickerAvatarOrderedReceiver? = null,
    private val send: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit,
) : TdSettingsSession {
    private val loginEmailBroker = sharedLoginEmail ?: OfficialTdLoginEmail(send)
    private val active = AtomicBoolean(true)
    private val passwordOwnerLock = Any()
    private val passwordOwners = mutableMapOf<SettingsSecurityToken, MutableSet<OfficialTdSettingsPassword>>()
    private val storageGate = Any()
    private val storageCoordinator = SettingsStorageCoordinator { command, reply, failed ->
        synchronized(storageGate) {
            if (!active.get()) false
            else if (storageSend != null) storageSend(command, reply, failed)
            else { send(command, reply, failed); true }
        }
    }
    private val guardedSend: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit = { request, result, failed ->
        if (active.get()) send(request, result, failed) else failed()
    }
    private val advanced = OfficialTdSettingsAdvanced(guardedSend)
    private val nativeProfileExtras = OfficialTdSettingsProfileExtras(guardedSend, receiverOrder = profileStickerReceiverOrder)
    override val profileExtras: TdSettingsProfileExtrasSession get() = nativeProfileExtras
    internal fun bindProfileHistoryDownloads(downloads: TdDownloadBroker) { nativeProfileExtras.bindHistoryDownloads(downloads) }
    private var stickerAvatarMedia: ProfileStickerAvatarMediaFiles? = null
    internal fun bindStickerAvatarDownloads(downloads: TdDownloadBroker) {
        check(stickerAvatarMedia == null)
        stickerAvatarMedia = nativeProfileExtras.stickerAvatarFiles(downloads)
    }
    internal fun stickerAvatarMedia(): ProfileStickerAvatarMediaFiles = checkNotNull(stickerAvatarMedia)
    override val storage: TdSettingsStorageSession = OfficialTdSettingsStorage(storageCoordinator, guardedSend)
    private val nativeStoragePolicy = OfficialTdSettingsStoragePolicy(guardedSend)
    override val storagePolicy: TdSettingsStoragePolicySession get() = nativeStoragePolicy
    private val nativePrivacyCommon = OfficialTdPrivacyCommon(guardedSend)
    override val privacyCommon: TdPrivacyCommonSession get() = nativePrivacyCommon
    private val nativeContactSignup = OfficialTdContactSignup(guardedSend)
    override val contactSignup: TdContactSignupSession get() = nativeContactSignup
    private val chatNotificationMutations = sharedChatNotifications ?: NativeChatNotificationMutations(guardedSend)
    private val nativeNotificationExceptions = OfficialTdNotificationExceptions(chatNotificationMutations, guardedSend)
    override val notificationExceptions: TdNotificationExceptionsSession get() = nativeNotificationExceptions
    private val nativeSavedNotificationSounds = OfficialTdSavedNotificationSounds(guardedSend)
    override val savedNotificationSounds: TdSavedNotificationSoundsSession get() = nativeSavedNotificationSounds
    private val nativeProfileColors = OfficialTdSettingsProfileColors(guardedSend)
    override val profileColors: TdSettingsProfileColorsSession get() = nativeProfileColors
    private val nativeReactionNotifications = OfficialTdReactionNotifications(guardedSend)
    override val reactionNotifications: TdReactionNotificationsSession get() = nativeReactionNotifications
    private val nativePrivacy = OfficialTdSettingsPrivacy(guardedSend)
    private val nativeUsernameAvailability = OfficialTdUsernameAvailability(guardedSend)
    override fun checkUsername(userId: Long, username: String, isCurrent: () -> Boolean,
        result: (SettingsResult<SettingsUsernameAvailability>) -> Unit,
    ) = nativeUsernameAvailability.check(userId, username, { active.get() && isCurrent() }, result)
    private val notificationLock = Any()
    private var notificationReadId = 0L
    private var loadedNotifications = emptyMap<NotificationScope, NotificationPreferences>()
    private val bioLengthMax = AtomicInteger(-1)
    private val securityObserver = java.util.concurrent.atomic.AtomicReference<(() -> Unit)?>(null)

    override fun retire() {
        synchronized(passwordOwnerLock) { passwordOwners.values.flatMap { it.toList() } }.forEach { it.retire() }
        synchronized(storageGate) { active.set(false) }
        storageCoordinator.retire()
        stickerAvatarMedia?.retire()
        nativeProfileExtras.retireHistoryFiles()
        nativeProfileExtras.retireProfileAnimations()
        nativeProfileExtras.stickerAvatar.retire()
        securityObserver.set(null)
        nativePrivacyCommon.retire()
        nativeStoragePolicy.retire()
        nativeContactSignup.retire()
        nativeNotificationExceptions.retire()
        nativeSavedNotificationSounds.retire()
        nativeProfileColors.retire()
        nativeReactionNotifications.retire()
        chatNotificationMutations.retire()
    }
    override fun savePrivacyDraft(key: PrivacyKey, draft: PrivacyDraft, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) =
        nativePrivacy.saveDraft(key, draft, { active.get() && isCurrent() }, result)
    override fun privacyPeers(query: PrivacyPeerQuery, isCurrent: () -> Boolean, result: (SettingsResult<SettingsPrivacyPeers>) -> Unit) =
        OfficialTdSettingsPrivacyPeers(scopedSend(isCurrent)).search(query, result)
    override fun privacyEditorAllowed(key: PrivacyKey, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) {
        if (key != PrivacyKey.VOICE_VIDEO_MESSAGES) { result(SettingsResult(Unit)); return }
        val boundary = OfficialTdSettingsPrivacyPeers(scopedSend(isCurrent))
        boundary.validate(key, emptyList(), result)
    }
    override fun observeSecurityChanges(observer: (() -> Unit)?) { securityObserver.set(observer) }
    internal fun accept(update: TdApi.Object) {
        if (active.get()) {
            nativeProfileExtras.acceptCurrentProfilePhotoUpdate(update)
            nativeProfileExtras.acceptStickerAvatarUpdate(update)
        }
        nativePrivacyCommon.accept(update)
        nativeStoragePolicy.accept(update)
        nativeContactSignup.accept(update)
        nativeNotificationExceptions.accept(update)
        nativeSavedNotificationSounds.accept(update)
        nativeProfileColors.accept(update)
        nativeReactionNotifications.accept(update)
        if (active.get() && (update is TdApi.UpdateChatBlockList || update is TdApi.UpdateUserFullInfo))
            securityObserver.get()?.invoke()
    }

    override fun profile(result: (SettingsResult<SettingsProfile>) -> Unit) {
        request(TdApi.GetMe(), { it as? TdApi.User }) { userResult ->
            val user = userResult.value
            if (user == null || user.id <= 0) result(SettingsResult(error = userResult.error ?: SettingsError.REQUEST_FAILED))
            else request(TdApi.GetUserFullInfo(user.id), { it as? TdApi.UserFullInfo }) { full ->
                val value = full.value
                if (value == null) result(SettingsResult(error = full.error ?: SettingsError.REQUEST_FAILED))
                else request(TdApi.GetOption("bio_length_max"), { response ->
                    (response as? TdApi.OptionValueInteger)?.value?.takeIf { it in 1..4096 }?.toInt()
                }) { limit ->
                    bioLengthMax.set(limit.value ?: -1)
                    result(SettingsResult(SettingsProfile(user.id, user.firstName.orEmpty(), user.lastName.orEmpty(),
                        user.usernames?.editableUsername.orEmpty(), value.bio?.text.orEmpty(), user.phoneNumber.orEmpty(),
                        mapAccountProfile(user).avatar, limit.value)))
                }
            }
        }
    }

    override fun saveProfile(edit: SettingsProfileEdit, result: (SettingsResult<Unit>) -> Unit) {
        val valid = when (edit.field) {
            ProfileField.NAME -> edit.value.isNotBlank() && edit.value.codePointCount(0, edit.value.length) <= 64 &&
                edit.secondary.codePointCount(0, edit.secondary.length) <= 64 &&
                '\n' !in edit.value && '\r' !in edit.value && '\n' !in edit.secondary && '\r' !in edit.secondary
            ProfileField.USERNAME -> edit.value.isEmpty() || edit.value.length in 5..32 &&
                edit.value.first().isLetter() && edit.value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' }
            ProfileField.BIO -> bioLengthMax.get().let { it > 0 && edit.value.codePointCount(0, edit.value.length) <= it } &&
                '\n' !in edit.value && '\r' !in edit.value
        }
        if (!valid) { result(SettingsResult(error = SettingsError.INVALID_VALUE)); return }
        val command = when (edit.field) {
            ProfileField.NAME -> TdApi.SetName(edit.value, edit.secondary)
            ProfileField.USERNAME -> TdApi.SetUsername(edit.value)
            ProfileField.BIO -> TdApi.SetBio(edit.value)
        }
        request(command, { if (it is TdApi.Ok) Unit else null }, result)
    }

    override fun notifications(result: (SettingsResult<Map<NotificationScope, NotificationPreferences>>) -> Unit) {
        val readId = synchronized(notificationLock) { ++notificationReadId }
        val values = mutableMapOf<NotificationScope, NotificationPreferences>()
        val remaining = AtomicInteger(NotificationScope.entries.size)
        val firstError = java.util.concurrent.atomic.AtomicReference<SettingsError?>()
        for (scope in NotificationScope.entries) request(TdApi.GetScopeNotificationSettings(scope.native()), {
            (it as? TdApi.ScopeNotificationSettings)?.let(::mapNotifications)
        }) { response ->
            synchronized(values) { response.value?.let { values[scope] = it } }
            response.error?.let { firstError.compareAndSet(null, it) }
            if (remaining.decrementAndGet() == 0) {
                val error = firstError.get()
                if (error != null) result(SettingsResult(error = error))
                else {
                    val loaded = synchronized(values) { values.toMap() }
                    synchronized(notificationLock) { if (notificationReadId == readId) loadedNotifications = loaded }
                    result(SettingsResult(loaded))
                }
            }
        }
    }

    override fun saveNotifications(scope: NotificationScope, value: NotificationPreferences, result: (SettingsResult<Unit>) -> Unit) {
        if (value.muteFor < 0) {
            result(SettingsResult(error = SettingsError.INVALID_VALUE)); return
        }
        val expected = synchronized(notificationLock) { loadedNotifications[scope] }
        if (expected == null) { result(SettingsResult(error = SettingsError.NOT_READY)); return }
        request(TdApi.GetScopeNotificationSettings(scope.native()), { (it as? TdApi.ScopeNotificationSettings)?.let(::mapNotifications) }) { fresh ->
            val current = fresh.value
            if (current == null) result(SettingsResult(error = fresh.error ?: SettingsError.REQUEST_FAILED))
            else {
                val merged = mergeNotifications(expected, value, current)
                if (merged == null) { result(SettingsResult(error = SettingsError.NOT_READY)); return@request }
                // Native fields absent from the small editor (stories, pins, mentions) are retained.
                val settings = TdApi.ScopeNotificationSettings(merged.muteFor, merged.soundId, merged.showPreview,
                    merged.useDefaultMuteStories, merged.muteStories, merged.storySoundId, merged.showStoryPoster,
                    merged.disablePinnedMessageNotifications, merged.disableMentionNotifications)
                request(TdApi.SetScopeNotificationSettings(scope.native(), settings), { if (it is TdApi.Ok) Unit else null }, result)
            }
        }
    }

    override fun privacy(result: (SettingsResult<SettingsPrivacyData>) -> Unit) {
        val remaining = AtomicInteger(2)
        val lock = Any()
        var rules = emptyMap<PrivacyKey, PrivacyRules>()
        var errors = emptyMap<PrivacyKey, SettingsError>()
        var ttl: Int? = null
        var sessions = emptyList<SettingsSession>()
        var blocked = emptyList<SettingsBlockedSender>()
        var managementError: SettingsError? = null
        fun finished() {
            if (remaining.decrementAndGet() == 0) synchronized(lock) {
                result(SettingsResult(SettingsPrivacyData(rules, errors, ttl, sessions, blocked, managementError)))
            }
        }
        nativePrivacy.load { response ->
            synchronized(lock) {
                response.value?.let { rules = it.first; errors = it.second }
                response.error?.let { managementError = it }
            }
            finished()
        }
        advanced.accountTtl { response ->
            synchronized(lock) { ttl = response.value; response.error?.let { managementError = it } }; finished()
        }
    }

    override fun savePrivacy(key: PrivacyKey, base: PrivacyBase, result: (SettingsResult<Unit>) -> Unit) =
        nativePrivacy.save(key, base, result)
    override fun setAccountTtl(days: Int, result: (SettingsResult<Unit>) -> Unit) = advanced.setAccountTtl(days, result)
    override fun terminateSession(id: Long, result: (SettingsResult<Unit>) -> Unit) = advanced.terminateSession(id, result)
    override fun unblock(sender: SettingsBlockedSender, result: (SettingsResult<Unit>) -> Unit) = advanced.unblock(sender, result)
    override fun advanced(result: (SettingsResult<SettingsAdvancedData>) -> Unit) {
        val remaining = AtomicInteger(2)
        val lock = Any()
        var storage: SettingsStorage? = null
        var proxies = emptyList<SettingsProxy>()
        var error: SettingsError? = null
        fun finished() {
            if (remaining.decrementAndGet() == 0) synchronized(lock) {
                if (error == null) result(SettingsResult(SettingsAdvancedData(storage, proxies)))
                else result(SettingsResult(error = error))
            }
        }
        advanced.storage { response ->
            synchronized(lock) { storage = response.value; response.error?.let { error = it } }; finished()
        }
        advanced.proxies { response ->
            synchronized(lock) { proxies = response.value.orEmpty(); response.error?.let { error = it } }; finished()
        }
    }
    override fun advancedForProxyChecks(lease: SettingsProxyProbeLease, result: (SettingsResult<SettingsAdvancedData>) -> Unit) {
        val owner = { active.get() && lease.current() }
        OfficialTdSettingsProxyChecksRead(scopedSend(owner)).read(lease, owner, result)
    }
    override fun optimizeStorage(result: (SettingsResult<SettingsStorage>) -> Unit) = advanced.optimizeStorage(result)
    override fun addProxy(draft: SettingsProxyDraft, result: (SettingsResult<Unit>) -> Unit) = advanced.addProxy(draft, result)
    override fun enableProxy(id: Int?, result: (SettingsResult<Unit>) -> Unit) = advanced.enableProxy(id, result)
    override fun removeProxy(id: Int, result: (SettingsResult<Unit>) -> Unit) = advanced.removeProxy(id, result)
    override fun pingProxy(expected: SettingsProxy, isCurrent: () -> Boolean,
        result: (SettingsResult<SettingsProxyLatency>) -> Unit,
    ) {
        val owner = { active.get() && isCurrent() }
        OfficialTdSettingsProxyLatency(scopedSend(owner)).ping(expected, owner, result)
    }
    override fun probeProxy(expected: SettingsProxy, lease: SettingsProxyProbeLease,
        result: (SettingsResult<SettingsProxyProbe>) -> Unit,
    ) {
        val owner = { active.get() && lease.current() }
        OfficialTdSettingsProxyProbe(scopedSend(owner)).probe(expected, lease, owner, result)
    }
    override fun shareProxy(target: SettingsProxyShareTarget, isHttp: Boolean, lease: SettingsProxyProbeLease,
        result: (SettingsResult<List<String>>) -> Unit,
    ) {
        val owner = { active.get() && lease.current() }
        OfficialTdSettingsProxyShare(scopedSend(owner)).share(target, isHttp, lease, owner, result)
    }
    override fun passwordState(isCurrent: () -> Boolean, result: (SettingsResult<SettingsPasswordState>) -> Unit) =
        OfficialTdSettingsPassword(scopedSend(isCurrent)).state(result)
    override fun password(command: SettingsPasswordCommand, isCurrent: () -> Boolean,
        result: (SettingsResult<SettingsPasswordOutcome>) -> Unit,
    ) = passwordForOwner(SettingsSecurityToken(), command, isCurrent, result)
    override fun passwordForOwner(token: SettingsSecurityToken, command: SettingsPasswordCommand, isCurrent: () -> Boolean,
        result: (SettingsResult<SettingsPasswordOutcome>) -> Unit,
    ) {
        val owner = { active.get() && isCurrent() }
        val helper = OfficialTdSettingsPassword(scopedSend(owner), owner, specialWrites = true).apply {
            loginEmailDelegate = { loginCommand, done -> loginEmailBroker.configured(token, loginCommand, owner, done) }
        }
        synchronized(passwordOwnerLock) { passwordOwners.getOrPut(token) { mutableSetOf() }.add(helper) }
        helper.execute(command) { response ->
            if (SettingsSpecialMutationContext.receipt()?.outcome != SettingsSpecialWriteOutcome.INDETERMINATE) {
                synchronized(passwordOwnerLock) {
                    passwordOwners[token]?.let { helpers ->
                        helpers.remove(helper)
                        if (helpers.isEmpty()) passwordOwners.remove(token)
                    }
                }
            }
            result(response)
        }
    }
    override fun retirePasswordOwner(token: SettingsSecurityToken): Unit {
        synchronized(passwordOwnerLock) { passwordOwners[token]?.toList().orEmpty() }.forEach { it.retire() }
        loginEmailBroker.retireConfigured(token)
    }
    override fun websites(isCurrent: () -> Boolean, result: (SettingsResult<SettingsWebsitesData>) -> Unit) =
        OfficialTdSettingsSecurity(scopedSend(isCurrent)).websites(result)
    override fun disconnectWebsite(target: SettingsWebsite, blockBot: Boolean, isCurrent: () -> Boolean,
        result: (SettingsResult<SettingsWebsiteDisconnectOutcome>) -> Unit,
    ) = OfficialTdSettingsSecurity(scopedSend(isCurrent)).disconnect(target, blockBot, result)
    override fun disconnectAllWebsites(isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) =
        OfficialTdSettingsSecurity(scopedSend(isCurrent)).disconnectAll(result)
    override fun blockedPage(offset: Int, isCurrent: () -> Boolean, result: (SettingsResult<SettingsBlockedPage>) -> Unit) =
        OfficialTdSettingsSecurity(scopedSend(isCurrent)).blockedPage(offset, result)
    override fun blockedCandidates(query: SettingsBlockedPeerQuery, isCurrent: () -> Boolean,
        result: (SettingsResult<SettingsBlockedCandidates>) -> Unit,
    ) = OfficialTdSettingsBlockedPeers(scopedSend(isCurrent)).search(query, result)
    override fun blockSecurity(userId: Long, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) =
        OfficialTdSettingsBlockedPeers(scopedSend(isCurrent)).block(userId, result)
    override fun securitySessions(isCurrent: () -> Boolean, result: (SettingsResult<List<SettingsSession>>) -> Unit) =
        OfficialTdSettingsAdvanced(scopedSend(isCurrent)).sessions(result)
    override fun securitySessionsData(isCurrent: () -> Boolean, result: (SettingsResult<SettingsSessionsData>) -> Unit) =
        OfficialTdSettingsSecurity(scopedSend(isCurrent)).sessions(result)
    override fun terminateOtherSecuritySessions(expected: List<SettingsSession>, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) =
        OfficialTdSettingsSecurity(scopedSend(isCurrent)).terminateOthers(expected, result)
    override fun setInactiveSessionTtl(expected: Int, desired: Int, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) =
        OfficialTdSettingsSecurity(scopedSend(isCurrent)).setInactiveTtl(expected, desired, result)
    override fun defaultMessageAutoDeleteTime(isCurrent: () -> Boolean, result: (SettingsResult<Int>) -> Unit) =
        OfficialTdSettingsSecurity(scopedSend(isCurrent)).defaultAutoDelete(result)
    override fun setDefaultMessageAutoDeleteTime(expected: Int, desired: Int, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) =
        OfficialTdSettingsSecurity(scopedSend(isCurrent)).setDefaultAutoDelete(expected, desired, result)
    override fun unblockSecurity(sender: SettingsBlockedSender, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) =
        OfficialTdSettingsAdvanced(scopedSend(isCurrent), specialSecurityWrites = true).unblock(sender, result)
    override fun terminateSecuritySession(id: Long, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) =
        OfficialTdSettingsAdvanced(scopedSend(isCurrent), specialSecurityWrites = true).terminateSession(id, result)

    private fun scopedSend(isCurrent: () -> Boolean): (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit =
        { command, result, failed -> if (active.get() && isCurrent()) guardedSend(command, result, failed) else failed() }

    private fun <T> request(function: TdApi.Function<*>, map: (TdApi.Object) -> T?, result: (SettingsResult<T>) -> Unit) {
        val completed = AtomicBoolean()
        fun finish(value: SettingsResult<T>) { if (completed.compareAndSet(false, true)) result(value) }
        try {
            guardedSend(function, { response ->
                val value = runCatching { map(response) }.getOrNull()
                if (value != null) finish(SettingsResult(value)) else finish(SettingsResult(error = settingsError(response)))
            }, { finish(SettingsResult(error = SettingsError.REQUEST_FAILED)) })
        } catch (_: Exception) { finish(SettingsResult(error = SettingsError.REQUEST_FAILED)) }
    }
}

internal fun NotificationScope.native(): TdApi.NotificationSettingsScope = when (this) {
    NotificationScope.PRIVATE_CHATS -> TdApi.NotificationSettingsScopePrivateChats()
    NotificationScope.GROUP_CHATS -> TdApi.NotificationSettingsScopeGroupChats()
    NotificationScope.CHANNEL_CHATS -> TdApi.NotificationSettingsScopeChannelChats()
}
internal fun mapNotifications(value: TdApi.ScopeNotificationSettings) = NotificationPreferences(
    value.muteFor.coerceAtLeast(0), value.soundId, value.showPreview, value.useDefaultMuteStories,
    value.muteStories, value.storySoundId, value.showStoryPoster, value.disablePinnedMessageNotifications,
    value.disableMentionNotifications,
)

/** Apply only the explicit edit to the latest server scope; muteFor is a live relative countdown. */
internal fun mergeNotifications(expected: NotificationPreferences, desired: NotificationPreferences,
    current: NotificationPreferences,
): NotificationPreferences? {
    var conflict = false
    fun <T> field(before: T, after: T, fresh: T): T {
        if (before == after) return fresh
        if (fresh != before && fresh != after) conflict = true
        return after
    }
    val muteFor = if (desired.muteFor == expected.muteFor) current.muteFor else {
        if (current.enabled != expected.enabled && current.enabled != desired.enabled) conflict = true
        desired.muteFor
    }
    val merged = NotificationPreferences(muteFor,
        field(expected.soundId, desired.soundId, current.soundId),
        field(expected.showPreview, desired.showPreview, current.showPreview),
        field(expected.useDefaultMuteStories, desired.useDefaultMuteStories, current.useDefaultMuteStories),
        field(expected.muteStories, desired.muteStories, current.muteStories),
        field(expected.storySoundId, desired.storySoundId, current.storySoundId),
        field(expected.showStoryPoster, desired.showStoryPoster, current.showStoryPoster),
        field(expected.disablePinnedMessageNotifications, desired.disablePinnedMessageNotifications, current.disablePinnedMessageNotifications),
        field(expected.disableMentionNotifications, desired.disableMentionNotifications, current.disableMentionNotifications))
    return if (conflict) null else merged
}
