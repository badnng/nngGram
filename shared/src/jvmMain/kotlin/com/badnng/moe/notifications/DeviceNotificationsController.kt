package com.badnng.moe.notifications

import com.badnng.moe.chat.ChatListRef
import com.badnng.moe.settings.SettingsError
import com.badnng.moe.settings.SettingsResult
import com.badnng.moe.settings.NotificationScope
import com.badnng.moe.tdlib.TdDeviceNotificationSession
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** All methods run on the owning engine actor. Native/OS callbacks only enqueue through post. */
internal class DeviceNotificationsController(
    private val generation: Long,
    private val native: TdDeviceNotificationSession,
    private val store: DeviceNotificationPreferencesStore,
    private val signals: DeviceNotificationSignals,
    private val post: (() -> Unit) -> Unit,
    private val publish: (DeviceNotificationsSnapshot) -> Unit,
    private val navigate: (chatId: Long, messageId: Long?) -> Unit,
    private val focusLocked: () -> Unit = {},
    private val windowsHost: DeviceWindowsNotificationHost? = null,
    private val externallyLocked: () -> Boolean = { false },
    private val externalLockRevision: () -> Long = { 0L },
    private val soundSource: NotificationSoundSource? = null,
    private val soundAccountUserId: () -> Long = { 0L },
    private val soundOutputAllowed: () -> Boolean = { true },
    private val soundVolumes: NotificationSoundVolumeStore? = null,
) {
    private var alive = true
    private var started = false
    private var activating: DeviceNotificationIdentity? = null
    private var activationRequest = 0L
    private var preferences = DeviceNotificationPreferences()
    private var error: DeviceNotificationError? = null
    private var nativeEnabled = false
    private var configurationRevision = 0L
    private var locked = false
    private var foreground = false
    private var activeChatId: Long? = null
    private var windows = WindowsDeviceNotificationState()
    private var windowsSelection = 0L
    private var openChatRevision = 0L
    private var modeSavePending = false
    private val groups = linkedMapOf<Int, DeviceNotificationGroup>()
    private val hidden = mutableSetOf<DeviceNotificationIdentity>()
    private val nativeDismissed = mutableSetOf<DeviceNotificationIdentity>()
    private val activatedIdentities = mutableSetOf<DeviceNotificationIdentity>()
    private val unread = mutableMapOf<ChatListRef, DeviceUnreadCounts>()
    private val ordinaryPolicies = mutableMapOf<Long, DeviceOrdinaryNotificationPolicy?>()
    private val ordinaryRequests = mutableMapOf<Long, AtomicBoolean>()
    private var nextPinRevision = 0L
    private val pinRevisions = mutableMapOf<Long, Long>()
    private var arrivalSequence = 0L
    private data class Arrival(val group: DeviceNotificationGroup, val items: List<DeviceNotificationItem>)
    private val arrivals = linkedMapOf<Long, Arrival>()
    private data class PinSource(val chatId: Long, val item: DeviceNotificationItem)
    private val freshPins = mutableMapOf<DeviceNotificationIdentity, PinSource>()
    /** Also owns the default tone's asynchronous scope request when local volume overrides are enabled. */
    private class CustomTone(
        val groupId: Int, val itemIds: Set<Int>,
        val chatId: Long, val settingsChatId: Long, val accountUserId: Long, val soundId: Long,
        val lockRevision: Long, val sequence: Long,
        val currentArrival: () -> Boolean,
    ) {
        val active = AtomicBoolean(true)
        val loaded = AtomicBoolean()
        val completed = AtomicBoolean()
        var load: NotificationSoundLoad? = null
        var requested = false
        var output = false
        var volumePercent: Int? = null
        val scopeCompleted = AtomicBoolean()
        var scopeRequested = false
    }
    private var customTone: CustomTone? = null
    private var soundSequence = 0L
    /** Callback threads can capture an opaque owner, but never inspect actor maps. */
    private val preparedFailureOwner = AtomicReference<CustomTone?>()

    fun start() {
        if (!alive || started) return
        started = true
        try { preferences = store.load().takeIf { it.valid } ?: DeviceNotificationPreferences() }
        catch (_: Exception) { error = DeviceNotificationError.SAVE_FAILED }
        native.listen { event -> post { if (alive) this.event(event) } }
        signals.setFailureListener {
            val owner = preparedFailureOwner.get()
            post { if (alive && (owner == null || currentTone(owner))) {
                error = DeviceNotificationError.OUTPUT_FAILED; emit()
            } }
        }
        windowsHost?.bind(generation, { event -> post {
            if (!alive) return@post
            when (event) {
                is WindowsToastHostEvent.Activate -> activate(event.identity, fromWindows = true)
                is WindowsToastHostEvent.Dismiss -> dismiss(event.identity, fromWindows = true)
                is WindowsToastHostEvent.Failed -> if (event.generation == generation) {
                    error = DeviceNotificationError.OUTPUT_FAILED
                    windows = windows.copy(status = WindowsDeviceNotificationStatus.FAILED)
                    emit()
                }
            }
        } }, { state -> post { if (alive) {
            if (windows != state) cancelCustomTone()
            windows = state; emit(restored = true)
        } } })
        configureNative()
        emit()
    }

    fun save(tokenGeneration: Long, value: DeviceNotificationPreferences) {
        if (!alive || tokenGeneration != generation || !value.valid) return
        // Selecting a native backend must go through its explicit registration/initialization action.
        if (value.useWindowsNotifications != preferences.useWindowsNotifications) return
        val saved = try { store.save(value) } catch (_: Exception) { false }
        if (!saved) {
            if (error != DeviceNotificationError.MODE_COMMITTED_SAVE_FAILED) error = DeviceNotificationError.SAVE_FAILED
            emit(); return
        }
        val previous = preferences
        preferences = value
        modeSavePending = false
        error = null
        if (previous.soundEnabled != value.soundEnabled || previous.volumePercent != value.volumePercent ||
            previous.respectWindowsFocusAssist != value.respectWindowsFocusAssist ||
            previous.useWindowsNotifications != value.useWindowsNotifications ||
            previous.flashEnabled && !value.flashEnabled ||
            previous.notifyAboutPinned != value.notifyAboutPinned) cancelCustomTone()
        if (previous.soundEnabled && !value.soundEnabled || previous.flashEnabled && !value.flashEnabled) safely { signals.stop() }
        if (previous.needsNativeGroups != value.needsNativeGroups || !nativeEnabled && value.needsNativeGroups) configureNative()
        val pinnedChanged = previous.notifyAboutPinned != value.notifyAboutPinned
        val outputsChanged = previous.needsNativeGroups != value.needsNativeGroups
        if (pinnedChanged) invalidateOrdinary()
        if (pinnedChanged || outputsChanged) {
            if (outputsChanged) { arrivals.clear(); freshPins.clear() }
            reconcileOrdinary()
        }
        emit(restored = pinnedChanged || outputsChanged)
    }

    fun selectWindows(tokenGeneration: Long, enabled: Boolean) {
        if (!alive || tokenGeneration != generation || windowsHost == null) return
        val request = ++windowsSelection
        windowsHost.select(generation, enabled) { success -> post {
            if (!alive || request != windowsSelection) return@post
            if (!success) { error = DeviceNotificationError.OUTPUT_FAILED; emit(); return@post }
            val next = preferences.copy(useWindowsNotifications = enabled,
                desktopEnabled = preferences.desktopEnabled || enabled)
            val saved = try { store.save(next) } catch (_: Exception) { false }
            if (!saved) {
                windowsHost.cancelSelection(generation)
                error = DeviceNotificationError.SAVE_FAILED
            } else {
                // This temporary disk write outlives the callback's UI revision. A newer
                // request failing or retiring cannot discard the responsibility to restore it.
                modeSavePending = next.useWindowsNotifications != preferences.useWindowsNotifications
                windowsHost.commitSelection(generation) { committed -> post commitResult@{
                    if (!alive || request != windowsSelection) return@commitResult
                    if (!committed) {
                        windowsHost.cancelSelection(generation)
                        modeSavePending = !runCatching { store.save(preferences) }.getOrDefault(false)
                        error = DeviceNotificationError.SAVE_FAILED
                    } else {
                        // Unrelated settings may have changed while the durable commit ran.
                        val final = preferences.copy(useWindowsNotifications = enabled,
                            desktopEnabled = preferences.desktopEnabled || enabled)
                        // The OS mode is already committed. A later, unrelated preference write
                        // cannot undo that fact or cancel a registration that is no longer pending.
                        preferences = final
                        cancelCustomTone()
                        // Even a same-value save while pending can have persisted the old mode.
                        if (!runCatching { store.save(final) }.getOrDefault(false)) {
                            modeSavePending = true
                            error = DeviceNotificationError.MODE_COMMITTED_SAVE_FAILED
                        } else { modeSavePending = false; error = null }
                        hidden.clear()
                        configureNative()
                    }
                    emit(restored = true)
                } }
            }
            emit(restored = true)
        } }
    }

    fun removeWindowsRegistration(tokenGeneration: Long) {
        if (!alive || tokenGeneration != generation || windowsHost == null) return
        val request = ++windowsSelection
        windowsHost.uninstall(generation) { success -> post {
            if (!alive || request != windowsSelection) return@post
            if (!success) error = DeviceNotificationError.OUTPUT_FAILED
            else {
                val next = preferences.copy(useWindowsNotifications = false)
                preferences = next // The system registration has actually been removed.
                cancelCustomTone()
                if (runCatching { store.save(next) }.getOrDefault(false)) { modeSavePending = false; error = null }
                else { modeSavePending = true; error = DeviceNotificationError.MODE_COMMITTED_SAVE_FAILED }
            }
            emit(restored = true)
        } }
    }

    private fun configureNative() {
        val revision = ++configurationRevision
        val requested = preferences.needsNativeGroups
        native.enable(requested) { success -> post {
            if (!alive || revision != configurationRevision) return@post
            nativeEnabled = requested && success
            if (!success) error = DeviceNotificationError.NATIVE_FAILED
            emit(restored = true)
        } }
    }

    fun setContext(foreground: Boolean, activeChatId: Long?, locked: Boolean) {
        if (!alive) return
        val wasLocked = isLocked()
        val contextChanged = this.foreground != foreground || foreground && this.activeChatId != activeChatId || this.locked != locked
        this.foreground = foreground
        this.activeChatId = activeChatId
        this.locked = locked
        val nowLocked = isLocked()
        if (contextChanged || nowLocked || customTone?.lockRevision?.let { it != currentLockRevision() } == true) cancelCustomTone()
        if (nowLocked) {
            activationRequest++; activating = null
            arrivals.clear(); freshPins.clear()
        }
        if (wasLocked && !nowLocked) ordinaryPolicies.entries.removeAll { it.value == null }
        if (wasLocked != nowLocked || nowLocked) reconcileOrdinary()
        if (foreground) safely { signals.stop() }
        emit(restored = wasLocked != nowLocked)
    }

    fun event(value: DeviceNotificationEvent) {
        if (!alive) return
        when (value) {
            is DeviceNotificationEvent.PolicyChanged -> {
                cancelCustomTone(value.chatId)
                invalidateOrdinary(value.chatId, preserveArrivals = true)
            }
            is DeviceNotificationEvent.Active -> {
                // Restart restores native identities and artwork only, never sound/attention.
                cancelCustomTone()
                invalidateOrdinary()
                groups.clear()
                hidden.clear()
                nativeDismissed.clear()
                activatedIdentities.clear()
                value.groups.take(25).filter { it.id > 0 && it.chatId != 0L && it.totalCount > 0 }.forEach {
                    groups[it.id] = it.copy(items = it.items.filter { item -> item.id > 0 }.distinctBy { item -> item.id }.takeLast(25))
                }
            }
            is DeviceNotificationEvent.Group -> {
                val incoming = value.group
                if (incoming.id <= 0 || incoming.chatId == 0L) return
                val previous = groups[incoming.id]?.takeIf { it.chatId == incoming.chatId }
                if (previous != null && ((previous.kind != incoming.kind && previous.items.any { isPinnedMention(previous, it) }) ||
                    ((value.removedIds.isNotEmpty() || incoming.totalCount <= 0) && previous.items.any { needsOrdinary(previous, it) }) ||
                    incoming.items.any { next ->
                        previous.items.any { old -> old.id == next.id && old != next &&
                            (isPinnedMention(previous, old) || isPinnedMention(incoming, next)) }
                    })) invalidateOrdinary(incoming.chatId, preserveArrivals = true)
                val removed = value.removedIds.toSet()
                val old = previous?.items.orEmpty().filterNot { it.id in removed }
                val newItems = incoming.items.filter { it.id > 0 && it.id !in removed }.distinctBy { it.id }
                val merged = (old.filterNot { item -> newItems.any { it.id == item.id } } + newItems).takeLast(25)
                removed.forEach { hidden.remove(identity(incoming.id, it)) }
                removed.forEach { nativeDismissed.remove(identity(incoming.id, it)) }
                removed.forEach { activatedIdentities.remove(identity(incoming.id, it)) }
                if (incoming.totalCount <= 0) {
                    groups.remove(incoming.id)
                    hidden.removeAll { it.groupId == incoming.id }
                    nativeDismissed.removeAll { it.groupId == incoming.id }
                    activatedIdentities.removeAll { it.groupId == incoming.id }
                } else {
                    if (groups.size >= 25 && incoming.id !in groups) {
                        val evicted = groups.keys.first()
                        groups.remove(evicted)
                        hidden.removeAll { it.groupId == evicted }
                    }
                    groups[incoming.id] = incoming.copy(items = merged)
                    val additions = newItems.filter { item -> previous?.items?.none { it.id == item.id } != false }
                    val newPins = additions.filter { it in merged && isPinnedMention(incoming, it) }
                    if (preferences.needsNativeGroups && !isLocked() && newPins.isNotEmpty()) {
                        if (ordinaryPolicies.containsKey(incoming.chatId) && ordinaryPolicies[incoming.chatId] == null)
                            ordinaryPolicies.remove(incoming.chatId)
                        newPins.forEach { freshPins[identity(incoming.id, it.id)] = PinSource(incoming.chatId, it) }
                    }
                    if (preferences.needsNativeGroups && additions.any { !it.silent } && !(foreground && activeChatId == incoming.chatId)) {
                        if (arrivals.size >= 625) arrivals.remove(arrivals.keys.first())
                        arrivals[++arrivalSequence] = Arrival(incoming.copy(items = emptyList()), additions.filter { it in merged }.takeLast(25))
                    }
                }
            }
            is DeviceNotificationEvent.Replace -> groups[value.groupId]?.let { group ->
                if (group.items.any { it.id == value.item.id }) {
                    customTone?.takeIf { it.groupId == value.groupId && value.item.id in it.itemIds }
                        ?.let { releaseCustomTone(it, stop = true) }
                    if (group.items.any { it.id == value.item.id &&
                            (isPinnedMention(group, it) || isPinnedMention(group, value.item)) })
                        invalidateOrdinary(group.chatId, preserveArrivals = true)
                    groups[value.groupId] = group.copy(items = group.items.map { if (it.id == value.item.id) value.item else it })
                }
            }
            is DeviceNotificationEvent.Messages -> {
                val old = unread[value.list] ?: DeviceUnreadCounts()
                unread[value.list] = old.copy(messages = value.count.coerceAtLeast(0), unmutedMessages = value.unmuted.coerceIn(0, value.count.coerceAtLeast(0)))
            }
            is DeviceNotificationEvent.Chats -> {
                val old = unread[value.list] ?: DeviceUnreadCounts()
                unread[value.list] = old.copy(chats = value.count.coerceAtLeast(0), unmutedChats = value.unmuted.coerceIn(0, value.count.coerceAtLeast(0)),
                    markedOnlyChats = value.markedOnly.coerceAtLeast(0), unmutedMarkedOnlyChats = value.unmutedMarkedOnly.coerceIn(0, value.markedOnly.coerceAtLeast(0)))
            }
        }
        if (value is DeviceNotificationEvent.Active || value is DeviceNotificationEvent.Group || value is DeviceNotificationEvent.Replace) {
            val retained = groups.values.flatMap { group -> group.items.map { identity(group.id, it.id) } }.toSet()
            hidden.retainAll(retained)
            nativeDismissed.retainAll(retained)
            activatedIdentities.retainAll(retained)
            freshPins.entries.removeAll { (id, source) ->
                id !in retained || current(id)?.let { (group, item) ->
                    group.chatId == source.chatId && isPinnedMention(group, item) && item == source.item
                } != true
            }
        }
        customTone?.let { if (!currentTone(it)) cancelCustomTone() }
        reconcileOrdinary()
        drainArrivals()
        emit(restored = value is DeviceNotificationEvent.Active || value is DeviceNotificationEvent.PolicyChanged ||
            value is DeviceNotificationEvent.Replace)
    }

    private fun isPinnedMention(group: DeviceNotificationGroup, item: DeviceNotificationItem) =
        group.kind == DeviceNotificationGroupKind.MENTIONS && item.cause == DeviceNotificationCause.PINNED

    private fun needsOrdinary(group: DeviceNotificationGroup, item: DeviceNotificationItem) =
        !preferences.notifyAboutPinned && isPinnedMention(group, item)

    private fun freshPin(group: DeviceNotificationGroup, item: DeviceNotificationItem): Boolean =
        freshPins[identity(group.id, item.id)]?.let { source ->
            source.chatId == group.chatId && source.item == group.items.firstOrNull { it.id == item.id }
        } == true

    private fun projected(group: DeviceNotificationGroup, item: DeviceNotificationItem): Pair<DeviceNotificationGroup, DeviceNotificationItem>? {
        if (!needsOrdinary(group, item)) return group to item
        val policy = ordinaryPolicies[group.chatId] ?: return null
        if (policy.muted) return null
        return group.copy(settingsChatId = group.chatId, soundId = policy.soundId) to
            item.copy(nativeShowPreview = item.nativeShowPreview && policy.showPreview)
    }

    /** Only these atomic leases are read by native callback threads; actor maps stay on this actor. */
    private fun invalidateOrdinary(chatId: Long? = null, preserveArrivals: Boolean = false) {
        pinRevisions.keys.filter { chatId == null || it == chatId }.forEach { pinRevisions[it] = ++nextPinRevision }
        activating?.let(::current)?.let { (group, item) ->
            if ((chatId == null || group.chatId == chatId) && isPinnedMention(group, item)) {
                activationRequest++; activating = null
            }
        }
        freshPins.entries.removeAll { (_, source) ->
            (chatId == null || source.chatId == chatId) && (!preserveArrivals || source.chatId !in ordinaryRequests)
        }
        ordinaryRequests.keys.filter { chatId == null || it == chatId }.forEach { ordinaryRequests.remove(it)?.set(false) }
        ordinaryPolicies.keys.removeAll { chatId == null || it == chatId }
        if (!preserveArrivals) arrivals.entries.removeAll { chatId == null || it.value.group.chatId == chatId }
    }

    private fun reconcileOrdinary() {
        val pinnedChats = groups.values.filter { group -> group.items.any { isPinnedMention(group, it) } }.map { it.chatId }.toSet()
        pinRevisions.keys.retainAll(pinnedChats)
        pinnedChats.forEach { if (it !in pinRevisions) pinRevisions[it] = ++nextPinRevision }
        val needed = if (!preferences.needsNativeGroups || isLocked()) emptySet() else groups.values
            .filter { group -> group.items.any { needsOrdinary(group, it) } }.map { it.chatId }.toSet()
        ordinaryPolicies.keys.retainAll(needed)
        (ordinaryRequests.keys - needed).forEach { ordinaryRequests.remove(it)?.set(false) }
        for (chatId in needed) {
            if (ordinaryPolicies.containsKey(chatId) || ordinaryRequests.containsKey(chatId)) continue
            val lease = AtomicBoolean(true)
            val completed = AtomicBoolean()
            ordinaryRequests[chatId] = lease
            val receive: (DeviceOrdinaryNotificationPolicy?) -> Unit = { policy ->
                if (completed.compareAndSet(false, true)) post {
                    if (alive && lease.get() && ordinaryRequests[chatId] === lease) {
                        ordinaryRequests.remove(chatId)
                        lease.set(false)
                        ordinaryPolicies[chatId] = policy
                        if (policy == null || policy.muted) freshPins.entries.removeAll { it.value.chatId == chatId }
                        val arriving = drainArrivals()
                        val popup = groups.values.any { group -> group.chatId == chatId && group.items.any { item ->
                            freshPin(group, item) && projected(group, item) != null
                        } }
                        emit(restored = !arriving && !popup)
                    }
                }
            }
            try { native.ordinaryPolicy(chatId, lease::get, receive) }
            catch (_: Exception) { receive(null) }
        }
    }

    /** One native additions batch can claim attention once, including a delayed pin-only batch. */
    private fun drainArrivals(): Boolean {
        if (!preferences.needsNativeGroups || isLocked()) { arrivals.clear(); return false }
        var signaled = false
        val iterator = arrivals.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val arrival = entry.value
            val current = groups[arrival.group.id]
            if (current == null || current.chatId != arrival.group.chatId || current.kind != arrival.group.kind ||
                foreground && activeChatId == current.chatId) { iterator.remove(); continue }
            val retained = arrival.items.filter { !it.silent && it in current.items }
            val candidate = retained.sortedBy { isPinnedMention(current, it) }.firstNotNullOfOrNull { item ->
                projected(current, item)
            }
            if (candidate != null) {
                iterator.remove() // Claim before an asynchronous Windows policy request.
                val pinnedSource = isPinnedMention(candidate.first, candidate.second)
                signal(candidate.first, retained.filter { isPinnedMention(current, it) == pinnedSource })
                signaled = true
            } else if (retained.isEmpty() || ordinaryPolicies.containsKey(current.chatId) ||
                retained.none { needsOrdinary(current, it) }) iterator.remove()
            else entry.setValue(arrival.copy(items = retained))
        }
        return signaled
    }

    private fun signal(group: DeviceNotificationGroup, nativeItems: List<DeviceNotificationItem>) {
        val soundIntent = ++soundSequence
        // Every new audible intent supersedes the previous volume/scope lease, including default tones.
        if (soundVolumes != null) cancelCustomTone()
        val pinRevision = pinRevisions[group.chatId].takeIf { nativeItems.any { isPinnedMention(group, it) } }
        fun currentArrival(): Boolean {
            if (!alive || !runCatching(soundOutputAllowed).getOrDefault(false) ||
                pinRevision != null && pinRevision != pinRevisions[group.chatId] ||
                foreground && activeChatId == group.chatId) return false
            val current = groups[group.id]?.takeIf { it.chatId == group.chatId } ?: return false
            return nativeItems.any { nativeItem ->
                !nativeItem.silent && nativeItem in current.items &&
                    projected(current, nativeItem)?.first?.soundId == group.soundId
            }
        }
        val tone = if (group.soundId != 0L && (group.soundId != -1L || soundVolumes != null) &&
            preferences.soundEnabled && (preferences.volumePercent > 0 || soundVolumes != null)) {
            cancelCustomTone()
            CustomTone(group.id, nativeItems.map { it.id }.toSet(), group.chatId, group.settingsChatId,
                runCatching(soundAccountUserId).getOrDefault(0L), group.soundId,
                currentLockRevision(), soundIntent) {
                val latest = groups[group.id]
                currentArrival() && latest != null && latest.kind == group.kind && nativeItems.any { item ->
                    val id = identity(group.id, item.id)
                    id !in hidden && id !in nativeDismissed && id !in activatedIdentities &&
                        !item.silent && item in latest.items && projected(latest, item)?.first?.let {
                            it.soundId == group.soundId && it.settingsChatId == group.settingsChatId
                        } == true
                }
            }.also {
                if (soundVolumes == null) it.volumePercent = preferences.volumePercent
                customTone = it
            }
        } else null
        fun output(policy: WindowsNotificationPolicy?) {
            if (!currentArrival()) {
                if (tone != null && customTone === tone) cancelCustomTone()
                return
            }
            val effectivePolicy = policy ?: WindowsNotificationPolicy(
                focusBlocked = if (preferences.useWindowsNotifications) null else false)
            if (preferences.soundEnabled && (preferences.volumePercent > 0 || soundVolumes != null) && group.soundId != 0L &&
                !effectivePolicy.skipSound(preferences.respectWindowsFocusAssist, isLocked())) {
                if (soundVolumes != null && tone != null) resolveToneVolume(tone)
                else if (group.soundId == -1L) {
                    if (customTone?.sequence?.let { it > soundIntent } != true) {
                        cancelCustomTone()
                        safely { signals.play(preferences.volumePercent) }
                    }
                } else if (tone != null) prepareCustomTone(tone)
            } else if (tone != null && customTone === tone) cancelCustomTone()
            if (preferences.flashEnabled && !foreground && !effectivePolicy.skipCustomToast(preferences.respectWindowsFocusAssist))
                safely { signals.flash() }
        }
        if (preferences.useWindowsNotifications && windows.status == WindowsDeviceNotificationStatus.ENABLED && windowsHost != null) {
            val once = AtomicBoolean()
            try {
                windowsHost.policy(generation) { policy -> if (once.compareAndSet(false, true)) post {
                    output(policy); if (alive) emit(restored = true)
                } }
            } catch (_: Exception) {
                if (once.compareAndSet(false, true)) { output(null); if (alive) emit(restored = true) }
            }
        }
        else output(null)
    }

    /** Actor-only eligibility. Neither the source nor the audio worker receives this closure. */
    private fun currentTone(tone: CustomTone): Boolean = alive && customTone === tone && tone.active.get() &&
        runCatching(soundOutputAllowed).getOrDefault(false) &&
        tone.lockRevision >= 0 && tone.lockRevision == currentLockRevision() && !isLocked() &&
        preferences.soundEnabled && (preferences.volumePercent > 0 || soundVolumes != null) &&
        runCatching(soundAccountUserId).getOrDefault(0L) == tone.accountUserId && tone.currentArrival()

    private fun workerActive(tone: CustomTone): Boolean {
        if (!tone.active.get()) return false
        if (runCatching(externalLockRevision).getOrDefault(-1L) != tone.lockRevision ||
            !runCatching(soundOutputAllowed).getOrDefault(false)) tone.active.set(false)
        return tone.active.get()
    }

    private fun resolveToneVolume(tone: CustomTone) {
        if (!currentTone(tone)) { if (customTone === tone) cancelCustomTone(); return }
        if (tone.scopeRequested) return
        tone.scopeRequested = true
        val volumeStore = soundVolumes ?: return
        if (tone.accountUserId <= 0) {
            customToneFailed(tone, DeviceNotificationError.SOUND_VOLUME_UNAVAILABLE); return
        }
        val receive: (NotificationScope?) -> Unit = { scope ->
            if (tone.scopeCompleted.compareAndSet(false, true)) post scopeReady@ {
                if (!currentTone(tone)) { if (customTone === tone) cancelCustomTone(); return@scopeReady }
                if (scope == null) {
                    customToneFailed(tone, DeviceNotificationError.SOUND_VOLUME_UNAVAILABLE); return@scopeReady
                }
                // The mention sender's settingsChatId controls native eligibility only. Volume
                // belongs to the actual destination chat and its native category.
                val resolved = try {
                    volumeStore.resolve(tone.accountUserId, tone.chatId, scope, preferences.volumePercent)
                } catch (_: Exception) { SettingsResult<Int>(error = SettingsError.REQUEST_FAILED) }
                val volume = resolved.value
                if (resolved.error != null || volume == null || volume !in 0..100) {
                    customToneFailed(tone, DeviceNotificationError.SOUND_VOLUME_UNAVAILABLE); return@scopeReady
                }
                tone.volumePercent = volume
                if (volume == 0) releaseCustomTone(tone, stop = false)
                else if (tone.soundId == -1L) playDefaultTone(tone)
                else prepareCustomTone(tone)
            }
        }
        try { native.notificationScope(tone.chatId, { workerActive(tone) }, receive) }
        catch (_: Exception) { receive(null) }
    }

    /** Scope metadata can arrive after the initial policy, so default output checks it again. */
    private fun playDefaultTone(tone: CustomTone) {
        val once = AtomicBoolean()
        fun output(policy: WindowsNotificationPolicy?) {
            if (!currentTone(tone)) { if (customTone === tone) cancelCustomTone(); return }
            val effective = policy ?: WindowsNotificationPolicy(
                focusBlocked = if (preferences.useWindowsNotifications) null else false)
            if (effective.skipSound(preferences.respectWindowsFocusAssist, isLocked())) {
                cancelCustomTone(); return
            }
            val volume = tone.volumePercent ?: return
            if (volume > 0) safely { signals.play(volume) }
            releaseCustomTone(tone, stop = false)
            emit(restored = true)
        }
        if (preferences.useWindowsNotifications && windows.status == WindowsDeviceNotificationStatus.ENABLED && windowsHost != null) {
            try {
                windowsHost.policy(generation) { policy -> if (once.compareAndSet(false, true)) post { output(policy) } }
            } catch (_: Exception) { if (once.compareAndSet(false, true)) output(null) }
        } else output(null)
    }

    private fun prepareCustomTone(tone: CustomTone) {
        if (!currentTone(tone)) { if (customTone === tone) cancelCustomTone(); return }
        if (tone.requested) return
        tone.requested = true
        val source = soundSource
        if (source == null || tone.accountUserId <= 0) {
            customToneFailed(tone, DeviceNotificationError.CUSTOM_SOUND_UNAVAILABLE); return
        }
        val receive: (SettingsResult<PreparedNotificationAudio>) -> Unit = { response ->
            if (tone.loaded.compareAndSet(false, true)) post ready@ {
                if (!currentTone(tone)) { if (customTone === tone) cancelCustomTone(); return@ready }
                val audio = response.value
                if (response.error != null || audio == null || audio.requestedSoundId != tone.soundId) {
                    customToneFailed(tone, DeviceNotificationError.CUSTOM_SOUND_UNAVAILABLE); return@ready
                }
                val once = AtomicBoolean()
                fun policyReady(policy: WindowsNotificationPolicy?) {
                    if (!currentTone(tone)) { if (customTone === tone) cancelCustomTone(); return }
                    val effective = policy ?: WindowsNotificationPolicy(
                        focusBlocked = if (preferences.useWindowsNotifications) null else false)
                    if (effective.skipSound(preferences.respectWindowsFocusAssist, isLocked())) {
                        cancelCustomTone(); return
                    }
                    playCustomTone(tone, audio)
                }
                if (preferences.useWindowsNotifications && windows.status == WindowsDeviceNotificationStatus.ENABLED && windowsHost != null) {
                    try {
                        windowsHost.policy(generation) { policy -> if (once.compareAndSet(false, true)) post {
                            policyReady(policy)
                        } }
                    } catch (_: Exception) {
                        // An unavailable latest policy is unknown, never permission to replay.
                        if (once.compareAndSet(false, true)) policyReady(null)
                    }
                } else policyReady(null)
            }
        }
        try {
            val lease = source.load(tone.accountUserId, tone.soundId, { workerActive(tone) }, receive)
            if (customTone === tone && tone.active.get()) tone.load = lease else runCatching { lease.close() }
        } catch (_: Exception) { receive(SettingsResult(error = SettingsError.REQUEST_FAILED)) }
    }

    private fun playCustomTone(tone: CustomTone, audio: PreparedNotificationAudio) {
        if (!currentTone(tone)) { if (customTone === tone) cancelCustomTone(); return }
        val volume = tone.volumePercent ?: run {
            customToneFailed(tone, DeviceNotificationError.SOUND_VOLUME_UNAVAILABLE); return
        }
        tone.output = true
        preparedFailureOwner.set(tone)
        val completed: (Boolean) -> Unit = { played ->
            if (tone.completed.compareAndSet(false, true)) post {
                if (customTone === tone) {
                    if (!currentTone(tone)) cancelCustomTone()
                    else {
                        if (!played) error = DeviceNotificationError.OUTPUT_FAILED
                        releaseCustomTone(tone, stop = false)
                        emit(restored = true)
                    }
                }
            }
        }
        try {
            val admitted = signals.playPrepared(audio.pcm, volume, { workerActive(tone) }, completed)
            if (!admitted) {
                tone.output = false // Rejected admission never owns a native output to stop.
                completed(false)
            }
        } catch (_: Exception) { customToneFailed(tone, DeviceNotificationError.OUTPUT_FAILED, stop = true) }
    }

    private fun customToneFailed(tone: CustomTone, failure: DeviceNotificationError, stop: Boolean = false) {
        if (currentTone(tone)) {
            error = failure
            releaseCustomTone(tone, stop)
            emit(restored = true)
        } else if (customTone === tone) cancelCustomTone()
    }

    private fun releaseCustomTone(tone: CustomTone, stop: Boolean) {
        if (customTone !== tone) return
        customTone = null
        tone.active.set(false)
        tone.scopeCompleted.set(true)
        tone.loaded.set(true)
        tone.completed.set(true)
        val lease = tone.load.also { tone.load = null }
        runCatching { lease?.close() }
        // Pending decoding owns no native output: do not stop unrelated flash/default signals.
        if (stop && tone.output) safely { signals.stop() }
        preparedFailureOwner.compareAndSet(tone, null)
    }

    private fun cancelCustomTone(chatId: Long? = null) {
        customTone?.takeIf { chatId == null || it.chatId == chatId || it.settingsChatId == chatId }
            ?.let { releaseCustomTone(it, stop = true) }
    }

    fun soundCatalogChanged() {
        if (alive) cancelCustomTone()
    }

    fun soundVolumeChanged() {
        if (!alive) return
        cancelCustomTone()
        // Canonical default output has no per-owner completion/active lease. A local
        // volume save must also invalidate its already queued or playing recording.
        safely { signals.stop() }
        if (error == DeviceNotificationError.SOUND_VOLUME_UNAVAILABLE) error = null
        emit(restored = true)
    }

    fun activate(id: DeviceNotificationIdentity, fromWindows: Boolean = false) {
        if (id in activatedIdentities || id in nativeDismissed || id in hidden && !fromWindows || activating == id) return
        val (group, item) = eligibleCurrent(id) ?: return
        val pinRevision = pinRevisions[group.chatId].takeIf { isPinnedMention(group, item) }
        val lockRevision = currentLockRevision()
        if (lockRevision < 0 || isLocked()) { focusLocked(); return }
        val messageId = item.messageId.takeIf { item.canOpenMessage && it > 0 }
        val request = ++activationRequest
        activating = id
        native.resolveTarget(id, group.chatId, item.messageId, item.canOpenMessage) { success -> post {
            if (!alive || activating != id || activationRequest != request) return@post
            activating = null
            if (lockRevision != currentLockRevision()) return@post
            if (pinRevision != null && pinRevision != pinRevisions[group.chatId]) return@post
            val current = eligibleCurrent(id) ?: return@post
            if (id in nativeDismissed || id in activatedIdentities || id in hidden && !fromWindows || current.first.chatId != group.chatId || current.second.messageId != item.messageId ||
                current.second.canOpenMessage != item.canOpenMessage) return@post
            if (isLocked()) { focusLocked(); return@post }
            if (!success) { error = DeviceNotificationError.NATIVE_FAILED; emit(); return@post }
            hidden += id
            activatedIdentities += id
            customTone?.let { if (!currentTone(it)) cancelCustomTone() }
            navigate(group.chatId, messageId)
            openChatRevision++
            emit()
        } }
    }

    fun dismiss(id: DeviceNotificationIdentity, fromWindows: Boolean = false) {
        if (id in nativeDismissed || id in hidden && !fromWindows) return
        eligibleCurrent(id) ?: return
        hidden += id
        nativeDismissed += id
        customTone?.let { if (!currentTone(it)) cancelCustomTone() }
        emit()
        native.dismiss(id.groupId, id.notificationId) { success -> post {
            if (!alive || current(id) == null) return@post
            if (!success) { hidden.remove(id); nativeDismissed.remove(id); error = DeviceNotificationError.NATIVE_FAILED }
            emit(restored = true)
        } }
    }

    fun expire(id: DeviceNotificationIdentity) {
        eligibleCurrent(id) ?: return
        hidden += id
        customTone?.let { if (!currentTone(it)) cancelCustomTone() }
        emit()
    }

    fun close() {
        if (!alive) return
        alive = false
        cancelCustomTone()
        invalidateOrdinary()
        windowsSelection++
        windowsHost?.retire(generation)
        // Local preferences only. No Host activation/registration is allowed during retirement.
        if (modeSavePending) modeSavePending = !runCatching { store.save(preferences) }.getOrDefault(false)
        activating = null
        ++configurationRevision
        native.listen(null)
        try { signals.close() } catch (_: Exception) { }
        groups.clear()
        pinRevisions.clear()
        freshPins.clear()
        hidden.clear()
        nativeDismissed.clear()
        activatedIdentities.clear()
        unread.clear()
        publish(DeviceNotificationsSnapshot(generation = generation, preferences = preferences,
            error = if (modeSavePending) DeviceNotificationError.SAVE_FAILED else null, modeSavePending = modeSavePending))
    }

    private fun current(id: DeviceNotificationIdentity): Pair<DeviceNotificationGroup, DeviceNotificationItem>? {
        if (!alive || id.generation != generation) return null
        val group = groups[id.groupId] ?: return null
        val item = group.items.firstOrNull { it.id == id.notificationId } ?: return null
        return group to item
    }

    private fun eligibleCurrent(id: DeviceNotificationIdentity): Pair<DeviceNotificationGroup, DeviceNotificationItem>? =
        current(id)?.let { (group, item) -> projected(group, item) }

    private fun identity(groupId: Int, itemId: Int) = DeviceNotificationIdentity(generation, groupId, itemId)
    private fun currentLockRevision() = runCatching(externalLockRevision).getOrDefault(-1L)
    private fun isLocked() = locked || currentLockRevision() < 0 || runCatching(externallyLocked).getOrDefault(true)
    private fun safely(block: () -> Unit) { try { block() } catch (_: Exception) { error = DeviceNotificationError.OUTPUT_FAILED } }
    private fun emit(restored: Boolean = false) {
        fun card(group: DeviceNotificationGroup, item: DeviceNotificationItem): DeviceNotificationCard = DeviceNotificationCard(identity(group.id, item.id),
            if (isLocked() || !preferences.showSender) "nngGram" else item.title.ifBlank { "新消息" },
            if (isLocked() || !preferences.showPreview || !item.nativeShowPreview) "你收到了一条新消息" else item.text.ifBlank { "新消息" }, item.date,
            quiet = isPinnedMention(group, item) && !freshPin(group, item))
        // Action Center owns the complete native unread set, independent of overlay capacity,
        // local popup expiration, active chat visibility and an activation's hidden artwork.
        val nativeCards = if (!preferences.desktopEnabled || !preferences.useWindowsNotifications) emptyList() else groups.values
            .flatMap { group -> group.items.filterNot { identity(group.id, it.id) in nativeDismissed }
                .mapNotNull { item -> projected(group, item)?.let { card(it.first, it.second) } } }
        if (windows.status != WindowsDeviceNotificationStatus.FAILED) windowsHost?.synchronize(generation, nativeCards, restored)
        val nativeActive = preferences.useWindowsNotifications && windows.status == WindowsDeviceNotificationStatus.ENABLED
        val cards = if (!preferences.desktopEnabled || nativeActive) emptyList() else groups.values
            .filterNot { foreground && activeChatId == it.chatId }
            .flatMap { group -> group.items.mapNotNull { item ->
                val id = identity(group.id, item.id)
                if (id in hidden) null else projected(group, item)?.let { card(it.first, it.second) }
            } }.sortedByDescending { it.date }.take(preferences.maximumVisible)
        publish(DeviceNotificationsSnapshot(generation, alive, preferences, nativeEnabled, error, cards, unread.toMap(), windows, openChatRevision, modeSavePending))
    }
}
