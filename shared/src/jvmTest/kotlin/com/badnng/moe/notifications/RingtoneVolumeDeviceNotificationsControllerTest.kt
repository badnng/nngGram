package com.badnng.moe.notifications

import com.badnng.moe.chat.ChatListRef
import com.badnng.moe.settings.*
import com.badnng.moe.tdlib.TdDeviceNotificationSession
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

/** No account, disk, audio device or Windows notification service is involved. */
class RingtoneVolumeDeviceNotificationsControllerTest {
    @Test fun actualChatThenNativeCategoryThenGlobalAreReplacementVolumesForBothOutputs() {
        for (sound in listOf(-1L, -22L)) for (expected in listOf(81, 63, 37)) Fixture().use { f ->
            if (expected == 81) f.volumes.chat[10] = 81
            if (expected != 37) f.volumes.scopes[NotificationScope.GROUP_CHATS] = 63
            f.volumes.chat[99] = 12 // Mention sender settings must never select a volume.
            f.add(group(sound = sound).copy(settingsChatId = 99, kind = DeviceNotificationGroupKind.MENTIONS))
            assertEquals(10L, f.native.scopes.single().chat)
            assertEquals(1, f.signals.flashes, "Flash does not await category metadata.")
            assertTrue(f.signals.defaults.isEmpty())
            assertTrue(f.source.requests.isEmpty())
            f.scope(NotificationScope.GROUP_CHATS)
            assertEquals(Resolution(700, 10, NotificationScope.GROUP_CHATS, 37), f.volumes.resolutions.single())
            if (sound == -1L) assertEquals(listOf(expected), f.signals.defaults)
            else { f.ready(); assertEquals(expected, f.signals.prepared.single().volume) }
        }
    }

    @Test fun aRealOverrideCanPlayAtGlobalZeroAndUnsetGlobalZeroNeverLoadsOrPlays() {
        for (sound in listOf(-1L, -22L)) for (override in listOf<Int?>(null, 25)) Fixture(global = 0).use { f ->
            if (override != null) f.volumes.scopes[NotificationScope.PRIVATE_CHATS] = override
            f.add(group(sound = sound)); f.scope(NotificationScope.PRIVATE_CHATS)
            if (override == null) {
                assertTrue(f.source.requests.isEmpty()); assertTrue(f.signals.defaults.isEmpty())
            } else if (sound == -1L) assertEquals(listOf(25), f.signals.defaults)
            else { f.ready(); assertEquals(25, f.signals.prepared.single().volume) }
            assertNull(f.state.error)
        }
    }

    @Test fun missingScopeAndReadFailureAreVisibleAndNeverFallBackToAnotherVolumeOrDefault() {
        for (failure in 0..3) Fixture().use { f ->
            f.add(group(sound = if (failure % 2 == 0) -1 else -22))
            if (failure >= 2) f.volumes.fail = true
            f.scope(if (failure >= 2) NotificationScope.GROUP_CHATS else null)
            assertEquals(DeviceNotificationError.SOUND_VOLUME_UNAVAILABLE, f.state.error)
            assertTrue(f.signals.defaults.isEmpty()); assertTrue(f.source.requests.isEmpty())
            assertEquals(1, f.signals.flashes)
        }
    }

    @Test fun duplicateAndSynchronousScopeCallbacksResolveExactlyOnceOnTheActor() {
        Fixture().use { f ->
            f.add(group(sound = -1))
            f.onActor = false
            val request = f.native.scopes.single()
            assertTrue(request.active())
            request.result(NotificationScope.GROUP_CHATS)
            request.result(NotificationScope.PRIVATE_CHATS)
            assertTrue(f.volumes.resolutions.isEmpty())
            f.onActor = true; f.flush()
            assertEquals(1, f.volumes.resolutions.size)
            assertEquals(listOf(37), f.signals.defaults)
        }
        Fixture().use { f ->
            f.native.immediate = NotificationScope.CHANNEL_CHATS
            f.add(group(sound = -1))
            assertEquals(1, f.volumes.resolutions.size)
            assertEquals(listOf(37), f.signals.defaults)
        }
    }

    @Test fun scopeAndStoreExceptionsOrInvalidResolvedVolumeAreVisibleWithoutOutput() {
        for (failure in 0..2) Fixture().use { f ->
            if (failure == 0) f.native.throwScope = true
            if (failure == 1) f.volumes.throwResolve = true
            if (failure == 2) f.volumes.chat[10] = 101
            f.add(group(sound = -1))
            if (failure != 0) f.scope(NotificationScope.GROUP_CHATS)
            assertEquals(DeviceNotificationError.SOUND_VOLUME_UNAVAILABLE, f.state.error)
            assertTrue(f.signals.defaults.isEmpty()); assertTrue(f.source.requests.isEmpty())
        }
    }

    @Test fun oldDefaultMetadataCannotReplayAfterANewerDefaultHasFinished() {
        Fixture().use { f ->
            f.add(group(sound = -1))
            val old = f.native.scopes.single()
            f.add(group(sound = -1, item = item(2)))
            f.scope(NotificationScope.GROUP_CHATS, 1)
            assertEquals(listOf(37), f.signals.defaults)
            old.result(NotificationScope.PRIVATE_CHATS); f.flush()
            assertEquals(1, f.volumes.resolutions.size)
            assertEquals(listOf(37), f.signals.defaults)
            assertNull(f.state.error)
        }
    }

    @Test fun newerCustomOrDefaultOrSilentArrivalCannotBeRevivedByAnOldScopeResult() {
        for (nextSound in listOf(-22L, -1L, 0L)) Fixture().use { f ->
            f.add(group())
            val old = f.native.scopes.single()
            f.add(group(sound = nextSound, item = item(2)))
            assertFalse(old.active())
            old.result(NotificationScope.GROUP_CHATS); f.flush()
            assertTrue(f.volumes.resolutions.isEmpty())
            assertTrue(f.source.requests.isEmpty()); assertTrue(f.signals.defaults.isEmpty())
            if (nextSound != 0L) {
                f.scope(NotificationScope.GROUP_CHATS, 1)
                if (nextSound == -1L) assertEquals(listOf(37), f.signals.defaults)
                else { f.ready(); assertEquals(1, f.signals.prepared.size) }
            }
            assertNull(f.state.error)
        }
    }

    @Test fun defaultArrivalCancelsPreparedCustomAndLateDownloadDoesNotReopenIt() {
        Fixture().use { f ->
            f.add(group()); f.scope(NotificationScope.GROUP_CHATS)
            val old = f.source.requests.single()
            f.add(group(sound = -1, item = item(2)))
            assertFalse(old.active()); assertEquals(1, old.closed)
            f.ready(); assertTrue(f.signals.prepared.isEmpty())
            f.scope(NotificationScope.GROUP_CHATS, 1)
            assertEquals(listOf(37), f.signals.defaults)
            assertNull(f.state.error)
        }
    }

    @Test fun contextNativePolicyCatalogAndVolumeChangesConsumePendingMetadataWithoutReplay() {
        val changes: List<(Fixture) -> Unit> = listOf(
            { it.controller.setContext(true, 10, false) },
            { it.controller.setContext(false, null, true) },
            { it.controller.event(DeviceNotificationEvent.Group(group(total = 0), listOf(1))) },
            { it.controller.event(DeviceNotificationEvent.Replace(1, item().copy(text = "changed"))) },
            { it.controller.event(DeviceNotificationEvent.PolicyChanged(10)) },
            { it.controller.event(DeviceNotificationEvent.PolicyChanged(99)) },
            { it.controller.dismiss(DeviceNotificationIdentity(7, 1, 1)) },
            { it.controller.soundCatalogChanged() },
            { it.controller.soundVolumeChanged() },
            { it.controller.save(7, it.state.preferences.copy(volumePercent = 50)) },
        )
        for (sound in listOf(-1L, -22L)) for (change in changes) Fixture().use { f ->
            f.add(group(sound = sound).copy(settingsChatId = 99))
            val old = f.native.scopes.single()
            change(f); f.flush()
            assertFalse(old.active())
            f.scope(NotificationScope.GROUP_CHATS)
            f.controller.setContext(false, null, false)
            f.controller.event(DeviceNotificationEvent.Messages(ChatListRef(), 2, 2)); f.flush()
            assertTrue(f.volumes.resolutions.isEmpty())
            assertTrue(f.source.requests.isEmpty()); assertTrue(f.signals.defaults.isEmpty())
            assertEquals(1, f.native.scopes.size)
            assertNull(f.state.error)
        }
    }

    @Test fun accountMandatoryGateAndExternalLockAreRecheckedBeforeResolvingAndWorkersReadNoActorState() {
        val changes: List<(Fixture) -> Unit> = listOf(
            { it.account = 701 }, { it.allowed.set(false) }, { it.lockRevision++ }, { it.externalLocked = true },
        )
        for (change in changes) Fixture().use { f ->
            f.add(group(sound = -1))
            change(f)
            f.scope(NotificationScope.GROUP_CHATS)
            assertTrue(f.volumes.resolutions.isEmpty())
            assertTrue(f.signals.defaults.isEmpty())
            assertNull(f.state.error)
        }
        Fixture().use { f ->
            f.add(group())
            val request = f.native.scopes.single()
            f.onActor = false
            assertTrue(request.active())
            f.allowed.set(false); assertFalse(request.active())
            f.allowed.set(true); assertFalse(request.active())
            request.result(NotificationScope.GROUP_CHATS)
            f.onActor = true; f.flush()
            assertTrue(f.volumes.resolutions.isEmpty())
        }
    }

    @Test fun postScopeDefaultAndPostDecodeCustomRecheckCurrentWindowsFocus() {
        for (sound in listOf(-1L, -22L)) Fixture(windows = true).use { f ->
            f.add(group(sound = sound))
            assertTrue(f.native.scopes.isEmpty())
            f.host.reply(0, blocked = false); f.flush()
            f.scope(NotificationScope.GROUP_CHATS)
            if (sound != -1L) f.ready()
            assertEquals(2, f.host.policies.size)
            f.host.reply(1, blocked = true); f.flush()
            assertTrue(f.signals.defaults.isEmpty()); assertTrue(f.signals.prepared.isEmpty())
            assertNull(f.state.error)
        }
    }

    @Test fun volumeSaveDuringDownloadOrFinalPolicyCancelsWithoutResoundingHistory() {
        Fixture().use { f ->
            f.add(group()); f.scope(NotificationScope.GROUP_CHATS)
            f.controller.soundVolumeChanged()
            assertFalse(f.source.requests.single().active())
            f.ready()
            assertTrue(f.signals.prepared.isEmpty()); assertNull(f.state.error)
        }
        for (sound in listOf(-1L, -22L)) Fixture(windows = true).use { f ->
            f.add(group(sound = sound)); f.host.reply(0, blocked = false); f.flush()
            f.scope(NotificationScope.GROUP_CHATS)
            if (sound != -1L) f.ready()
            f.controller.soundVolumeChanged()
            f.host.reply(1, blocked = false); f.flush()
            assertTrue(f.signals.defaults.isEmpty()); assertTrue(f.signals.prepared.isEmpty())
            assertNull(f.state.error)
        }
    }

    @Test fun volumeSaveStopsAnAlreadyQueuedDefaultAfterItsScopeOwnerWasReleased() {
        Fixture().use { f ->
            f.add(group(sound = -1)); f.scope(NotificationScope.PRIVATE_CHATS)
            assertEquals(listOf(37), f.signals.defaults)
            assertEquals(0, f.signals.stops)
            f.controller.soundVolumeChanged()
            assertEquals(1, f.signals.stops)
            f.controller.event(DeviceNotificationEvent.Messages(ChatListRef(), 1, 1)); f.flush()
            assertEquals(listOf(37), f.signals.defaults)
            assertEquals(1, f.native.scopes.size)
            assertNull(f.state.error)
        }
    }

    @Test fun successfulVolumeSavePublishesVolumeErrorRecoveryWithoutReplayingTheFailedArrival() {
        Fixture().use { f ->
            f.add(group(sound = -1)); f.scope(null)
            assertEquals(DeviceNotificationError.SOUND_VOLUME_UNAVAILABLE, f.state.error)
            f.controller.soundVolumeChanged()
            assertNull(f.state.error, "Recovery must publish immediately from the successful save hook.")
            assertEquals(1, f.signals.stops)
            f.scope(NotificationScope.PRIVATE_CHATS)
            f.controller.event(DeviceNotificationEvent.Messages(ChatListRef(), 1, 1)); f.flush()
            assertNull(f.state.error)
            assertEquals(1, f.native.scopes.size)
            assertTrue(f.volumes.resolutions.isEmpty())
            assertTrue(f.signals.defaults.isEmpty()); assertTrue(f.source.requests.isEmpty())
        }
    }

    @Test fun successfulVolumeSavePreservesUnrelatedNativeCustomAndOutputErrors() {
        for (expected in listOf(DeviceNotificationError.NATIVE_FAILED,
            DeviceNotificationError.CUSTOM_SOUND_UNAVAILABLE, DeviceNotificationError.OUTPUT_FAILED)) Fixture().use { f ->
            when (expected) {
                DeviceNotificationError.NATIVE_FAILED -> {
                    f.add(group(sound = 0)); f.native.dismissSuccess = false
                    f.controller.dismiss(DeviceNotificationIdentity(7, 1, 1)); f.flush()
                }
                DeviceNotificationError.CUSTOM_SOUND_UNAVAILABLE -> {
                    f.add(group()); f.scope(NotificationScope.GROUP_CHATS)
                    f.source.requests.single().result(SettingsResult(error = SettingsError.REQUEST_FAILED)); f.flush()
                }
                else -> {
                    f.signals.throwDefault = true
                    f.add(group(sound = -1)); f.scope(NotificationScope.PRIVATE_CHATS)
                }
            }
            assertEquals(expected, f.state.error)
            val scopeCount = f.native.scopes.size
            val sourceCount = f.source.requests.size
            f.controller.soundVolumeChanged()
            assertEquals(expected, f.state.error)
            f.controller.event(DeviceNotificationEvent.Messages(ChatListRef(), 1, 1)); f.flush()
            assertEquals(expected, f.state.error)
            assertEquals(scopeCount, f.native.scopes.size)
            assertEquals(sourceCount, f.source.requests.size)
            assertTrue(f.signals.defaults.isEmpty()); assertTrue(f.signals.prepared.isEmpty())
        }
    }

    @Test fun pinnedProjectionUsesActualChatVolumeAndItsOrdinaryNativeSound() {
        Fixture(notifyPinned = false).use { f ->
            f.volumes.chat[10] = 76
            f.add(group(sound = -1).copy(settingsChatId = 99, kind = DeviceNotificationGroupKind.MENTIONS,
                items = listOf(item().copy(cause = DeviceNotificationCause.PINNED))))
            assertTrue(f.native.scopes.isEmpty())
            f.native.ordinary.single()(DeviceOrdinaryNotificationPolicy(false, -22, true)); f.flush()
            f.scope(NotificationScope.GROUP_CHATS); f.ready()
            assertEquals(-22L, f.source.requests.single().id)
            assertEquals(10L, f.volumes.resolutions.single().chat)
            assertEquals(76, f.signals.prepared.single().volume)
        }
    }

    @Test fun restoredSilentNoSoundAndClosedOwnersNeverRequestOrReviveVolumeMetadata() {
        Fixture().use { f ->
            f.controller.event(DeviceNotificationEvent.Active(listOf(group())))
            f.add(group(item = item(2).copy(silent = true)))
            f.add(group(sound = 0, item = item(3)))
            assertTrue(f.native.scopes.isEmpty())
            f.add(group(item = item(4)))
            val old = f.native.scopes.single()
            f.controller.close()
            assertFalse(old.active())
            f.scope(NotificationScope.GROUP_CHATS)
            assertTrue(f.volumes.resolutions.isEmpty()); assertTrue(f.source.requests.isEmpty())
        }
    }

    private class Fixture(global: Int = 37, windows: Boolean = false, notifyPinned: Boolean = true) : AutoCloseable {
        var onActor = true
        var account = 700L
        var lockRevision = 0L
        var externalLocked = false
        val allowed = AtomicBoolean(true)
        val queue = ArrayDeque<() -> Unit>()
        val native = Native()
        val source = Source()
        val signals = Signals()
        val host = Host()
        val volumes = Volumes { check(onActor) }
        var state = DeviceNotificationsSnapshot()
        val controller = DeviceNotificationsController(7, native,
            Store(DeviceNotificationPreferences(desktopEnabled = true, soundEnabled = true, flashEnabled = true,
                volumePercent = global, useWindowsNotifications = windows, notifyAboutPinned = notifyPinned)),
            signals, queue::addLast, { state = it }, { _, _ -> }, windowsHost = host.takeIf { windows },
            externallyLocked = { check(onActor); externalLocked }, externalLockRevision = { lockRevision },
            soundSource = source, soundAccountUserId = { check(onActor); account }, soundOutputAllowed = allowed::get,
            soundVolumes = volumes)
        init { controller.start(); flush() }
        fun flush() { check(onActor); while (queue.isNotEmpty()) queue.removeFirst().invoke() }
        fun add(group: DeviceNotificationGroup) { controller.event(DeviceNotificationEvent.Group(group, emptyList())); flush() }
        fun scope(value: NotificationScope?, index: Int = 0) { native.scopes[index].result(value); flush() }
        fun ready(index: Int = 0) { source.requests[index].let { it.result(SettingsResult(PreparedNotificationAudio(it.id, pcm()))) }; flush() }
        override fun close() { controller.close(); flush() }
    }

    private data class Resolution(val account: Long, val chat: Long, val scope: NotificationScope, val global: Int)
    private class Volumes(val actor: () -> Unit) : NotificationSoundVolumeStore {
        val chat = mutableMapOf<Long, Int>()
        val scopes = mutableMapOf<NotificationScope, Int>()
        val resolutions = mutableListOf<Resolution>()
        var fail = false
        var throwResolve = false
        override fun read(accountUserId: Long, target: NotificationSoundVolumeTarget): SettingsResult<NotificationSoundVolumeReading> = error("not requested")
        override fun save(expected: NotificationSoundVolumeReading, overridePercent: Int?): SettingsResult<NotificationSoundVolumeReading> = error("not requested")
        override fun resolve(accountUserId: Long, chatId: Long, scope: NotificationScope, globalVolume: Int): SettingsResult<Int> {
            actor(); resolutions.add(Resolution(accountUserId, chatId, scope, globalVolume))
            if (throwResolve) throw IllegalStateException("synthetic volume read failure")
            return if (fail) SettingsResult(error = SettingsError.REQUEST_FAILED)
                else SettingsResult(chat[chatId] ?: scopes[scope] ?: globalVolume)
        }
    }
    private class Native : TdDeviceNotificationSession {
        class Scope(val chat: Long, val active: () -> Boolean, val result: (NotificationScope?) -> Unit)
        val scopes = mutableListOf<Scope>()
        val ordinary = mutableListOf<(DeviceOrdinaryNotificationPolicy?) -> Unit>()
        var immediate: NotificationScope? = null
        var throwScope = false
        var dismissSuccess = true
        override fun listen(listener: ((DeviceNotificationEvent) -> Unit)?) = Unit
        override fun enable(enabled: Boolean, result: (Boolean) -> Unit) = result(true)
        override fun dismiss(groupId: Int, notificationId: Int, result: (Boolean) -> Unit) = result(dismissSuccess)
        override fun ordinaryPolicy(chatId: Long, active: () -> Boolean, result: (DeviceOrdinaryNotificationPolicy?) -> Unit) { ordinary.add(result) }
        override fun notificationScope(chatId: Long, active: () -> Boolean, result: (NotificationScope?) -> Unit) {
            if (throwScope) throw IllegalStateException("synthetic metadata failure")
            scopes.add(Scope(chatId, active, result)); immediate?.let(result)
        }
    }
    private class Source : NotificationSoundSource {
        class Request(val id: Long, val active: () -> Boolean, val result: (SettingsResult<PreparedNotificationAudio>) -> Unit) { var closed = 0 }
        val requests = mutableListOf<Request>()
        override fun load(accountUserId: Long, soundId: Long, active: () -> Boolean,
            result: (SettingsResult<PreparedNotificationAudio>) -> Unit): NotificationSoundLoad {
            val request = Request(soundId, active, result); requests.add(request)
            return NotificationSoundLoad { request.closed++ }
        }
        override fun invalidate() = Unit
        override fun close() = Unit
    }
    private class Signals : DeviceNotificationSignals {
        data class Prepared(val volume: Int, val active: () -> Boolean)
        val defaults = mutableListOf<Int>()
        val prepared = mutableListOf<Prepared>()
        var flashes = 0
        var stops = 0
        var throwDefault = false
        override fun play(volumePercent: Int) {
            if (throwDefault) throw IllegalStateException("synthetic output failure")
            defaults.add(volumePercent)
        }
        override fun playPrepared(pcm: NotificationSoundPcm, volumePercent: Int, active: () -> Boolean,
            completed: (Boolean) -> Unit): Boolean { assertTrue(active()); prepared.add(Prepared(volumePercent, active)); return true }
        override fun flash() { flashes++ }
        override fun stop() { stops++ }
        override fun close() = Unit
    }
    private class Store(val value: DeviceNotificationPreferences) : DeviceNotificationPreferencesStore {
        override fun load() = value
        override fun save(value: DeviceNotificationPreferences) = true
    }
    private class Host : DeviceWindowsNotificationHost {
        val policies = mutableListOf<(WindowsNotificationPolicy) -> Unit>()
        override fun bind(generation: Long, events: (WindowsToastHostEvent) -> Unit, changed: (WindowsDeviceNotificationState) -> Unit) {
            changed(WindowsDeviceNotificationState(WindowsDeviceNotificationStatus.ENABLED, true))
        }
        override fun select(generation: Long, enabled: Boolean, result: (Boolean) -> Unit) = result(true)
        override fun uninstall(generation: Long, result: (Boolean) -> Unit) = result(true)
        override fun synchronize(generation: Long, cards: List<DeviceNotificationCard>, restored: Boolean) = Unit
        override fun policy(generation: Long, result: (WindowsNotificationPolicy) -> Unit) { policies.add(result) }
        fun reply(index: Int, blocked: Boolean) = policies[index](WindowsNotificationPolicy(blocked, 5))
        override fun retire(generation: Long) = Unit
    }
    private companion object {
        fun pcm() = NotificationSoundPcm(byteArrayOf(1, 2, 3, 4))
        fun item(id: Int = 1) = DeviceNotificationItem(id, id, 100L + id, "native title", "native text", false, true, true)
        fun group(sound: Long = -22, item: DeviceNotificationItem = item(), total: Int = 1) =
            DeviceNotificationGroup(1, 10, total, 10, sound, listOf(item))
    }
}
