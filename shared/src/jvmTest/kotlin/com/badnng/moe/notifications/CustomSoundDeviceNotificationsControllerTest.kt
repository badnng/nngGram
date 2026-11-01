package com.badnng.moe.notifications

import com.badnng.moe.chat.ChatListRef
import com.badnng.moe.settings.SettingsError
import com.badnng.moe.settings.SettingsResult
import com.badnng.moe.tdlib.TdDeviceNotificationSession
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

class CustomSoundDeviceNotificationsControllerTest {
    @Test fun signedCustomSoundsPlayTheirPreparedBytesAtTheCurrentVolumeWithoutDefaultSubstitution() {
        for (id in listOf(Long.MIN_VALUE, -22L, 22L, Long.MAX_VALUE)) Fixture().use { f ->
            f.add(group(sound = id))
            assertEquals(id, f.source.requests.single().id)
            assertEquals(700L, f.source.requests.single().account)
            assertEquals(1, f.signals.flashes, "Flash does not wait for decoding.")
            assertTrue(f.signals.prepared.isEmpty())
            f.ready()
            assertEquals(37, f.signals.prepared.single().volume)
            assertContentEquals(pcm().samples(100), f.signals.prepared.single().pcm.samples(100))
            assertTrue(f.signals.defaults.isEmpty())
            assertNull(f.state.error)
            assertEquals(0, f.source.requests.single().closed)
            f.signals.prepared.single().completed(true); f.flush()
            assertEquals(1, f.source.requests.single().closed)
            assertFalse(f.signals.prepared.single().active())
            assertNull(f.state.error)
        }
    }

    @Test fun defaultKeepsTheOldSignalPathAndRestoresSilentItemsAndPreferenceChangesNeverReplay() {
        Fixture().use { f ->
            f.controller.event(DeviceNotificationEvent.Active(listOf(group())))
            f.flush()
            assertTrue(f.source.requests.isEmpty())
            f.add(group(sound = -1, item = item(2)))
            assertEquals(listOf(37), f.signals.defaults)
            assertTrue(f.source.requests.isEmpty())
            f.add(group(sound = -22, item = item(3).copy(silent = true)))
            f.save(f.state.preferences.copy(volumePercent = 51))
            f.controller.event(DeviceNotificationEvent.PolicyChanged())
            f.controller.event(DeviceNotificationEvent.Messages(ChatListRef(), 10, 8))
            f.flush()
            assertTrue(f.source.requests.isEmpty())
            assertEquals(1, f.signals.defaults.size)
            f.add(group(sound = 0, item = item(4)))
            assertTrue(f.source.requests.isEmpty())
            assertEquals(1, f.signals.defaults.size)
        }
    }

    @Test fun preparedMissingFallbackIsExplicitAndSourceFailuresNeverPlayTheDefault() {
        Fixture().use { f ->
            f.add(group())
            f.ready(fallback = true)
            assertEquals(1, f.signals.prepared.size)
            assertTrue(f.signals.defaults.isEmpty())
            f.signals.prepared.single().completed(true); f.flush()
            f.add(group(item = item(2)))
            f.ready(index = 1, error = SettingsError.FORBIDDEN)
            assertEquals(DeviceNotificationError.CUSTOM_SOUND_UNAVAILABLE, f.state.error)
            assertEquals(1, f.signals.prepared.size)
            assertTrue(f.signals.defaults.isEmpty())
        }
    }

    @Test fun soundMuteVolumeAndFocusPreferenceChangesCancelWithoutStoppingUnrelatedFlash() {
        val changes: List<(DeviceNotificationPreferences) -> DeviceNotificationPreferences> = listOf(
            { it.copy(soundEnabled = false) }, { it.copy(volumePercent = 0) },
            { it.copy(volumePercent = 65) }, { it.copy(respectWindowsFocusAssist = false) },
        )
        for (change in changes) Fixture().use { f ->
            f.add(group())
            val request = f.source.requests.single()
            val next = change(f.state.preferences)
            f.save(next)
            assertFalse(request.active())
            assertEquals(1, request.closed)
            assertEquals(if (next.soundEnabled) 0 else 1, f.signals.stops,
                "Only the existing explicit sound-disable action stops signals; canceling a pending file adds no stop.")
            f.ready(error = SettingsError.NOT_READY)
            assertTrue(f.signals.prepared.isEmpty())
            assertNull(f.state.error)
            f.save(preferences())
            assertEquals(1, f.source.requests.size, "Returning to an older preference cannot reclaim the arrival.")
        }
    }

    @Test fun foregroundAndLockCyclesConsumeTheArrivalAndWorkerGateReadsNoActorState() {
        for (foreground in listOf(false, true)) Fixture().use { f ->
            f.add(group())
            val request = f.source.requests.single()
            f.controller.setContext(foreground, if (foreground) 10 else null, !foreground)
            assertFalse(request.active())
            f.controller.setContext(false, null, false)
            f.ready()
            assertTrue(f.signals.prepared.isEmpty())
            assertNull(f.state.error)
            assertEquals(1, f.source.requests.size)
        }
        Fixture().use { f ->
            f.add(group())
            val request = f.source.requests.single()
            f.onActor = false
            assertTrue(request.active(), "The gate may not call account/foreground/lock actor suppliers.")
            f.lockRevision++
            assertFalse(request.active())
            f.lockRevision--
            assertFalse(request.active(), "A canceled external lock lease never revives.")
            f.onActor = true
            f.ready()
            assertTrue(f.signals.prepared.isEmpty())
            assertNull(f.state.error)
        }
    }

    @Test fun synchronousNativeReadyOrMandatoryGateFlipSuppressesReadyBeforeActorContextIsUpdated() {
        for (observeWorker in listOf(false, true)) Fixture().use { f ->
            f.add(group())
            f.outputAllowed.set(false) // No setContext/native-event publication has run yet.
            if (observeWorker) {
                f.onActor = false
                assertFalse(f.source.requests.single().active())
                f.outputAllowed.set(true)
                assertFalse(f.source.requests.single().active())
                f.onActor = true
            }
            f.ready()
            assertTrue(f.signals.prepared.isEmpty())
            assertNull(f.state.error)
            assertEquals(1, f.source.requests.single().closed)
            f.outputAllowed.set(true)
            f.controller.event(DeviceNotificationEvent.Messages(ChatListRef(), 4, 4)); f.flush()
            assertEquals(1, f.source.requests.size)
        }
    }

    @Test fun preparedOutputGateStopsSynchronouslyWithoutReadingActorSuppliersOrReportingCancellationAsFailure() {
        Fixture().use { f ->
            f.add(group()); f.ready()
            val output = f.signals.prepared.single()
            f.outputAllowed.set(false)
            f.onActor = false
            assertFalse(output.active())
            f.outputAllowed.set(true)
            assertFalse(output.active())
            output.completed(false)
            f.onActor = true
            f.flush()
            assertNull(f.state.error)
            assertEquals(1, f.source.requests.single().closed)
        }
    }

    @Test fun nativeRemovalReplacementRestoreAndLocalDismissalCancelPendingOwners() {
        val changes: List<(Fixture) -> Unit> = listOf(
            { it.controller.event(DeviceNotificationEvent.Group(group(total = 0), listOf(1))) },
            { it.controller.event(DeviceNotificationEvent.Replace(1, item(1).copy(text = "updated native text"))) },
            { it.controller.event(DeviceNotificationEvent.Replace(1, item(1))) },
            { it.controller.event(DeviceNotificationEvent.Group(group(sound = 44), emptyList())) },
            { it.controller.event(DeviceNotificationEvent.Active(listOf(group()))) },
            { it.controller.dismiss(DeviceNotificationIdentity(7, 1, 1)) },
            { it.controller.expire(DeviceNotificationIdentity(7, 1, 1)) },
        )
        for (change in changes) Fixture().use { f ->
            f.add(group())
            val request = f.source.requests.single()
            change(f); f.flush()
            assertEquals(1, request.closed)
            assertFalse(request.active())
            f.ready()
            assertTrue(f.signals.prepared.isEmpty())
            assertNull(f.state.error)
        }
    }

    @Test fun accountAndCatalogChangesCancelAndDoNotResoundOldNotifications() {
        Fixture().use { f ->
            f.add(group())
            f.controller.soundCatalogChanged()
            assertFalse(f.source.requests.single().active())
            f.ready(error = SettingsError.NOT_READY)
            assertNull(f.state.error)
            assertTrue(f.signals.prepared.isEmpty())
            f.add(group(item = item(2)))
            f.account = 701
            f.ready(index = 1)
            assertTrue(f.signals.prepared.isEmpty())
            assertNull(f.state.error)
            assertEquals(1, f.source.requests[1].closed)
            f.account = 700
            f.controller.event(DeviceNotificationEvent.Messages(ChatListRef(), 3, 3)); f.flush()
            assertEquals(2, f.source.requests.size)
        }
    }

    @Test fun unrelatedPolicyKeepsOwnershipAndAffectedPolicyRetiresItBeforeAnyLateReady() {
        Fixture().use { f ->
            f.add(group())
            f.controller.event(DeviceNotificationEvent.PolicyChanged(999))
            assertTrue(f.source.requests.single().active())
            f.controller.event(DeviceNotificationEvent.PolicyChanged(10))
            assertFalse(f.source.requests.single().active())
            f.ready()
            assertTrue(f.signals.prepared.isEmpty())
            assertNull(f.state.error)
        }
        Fixture().use { f ->
            f.add(group().copy(settingsChatId = 99))
            f.controller.event(DeviceNotificationEvent.PolicyChanged(99))
            assertFalse(f.source.requests.single().active())
            f.ready()
            assertTrue(f.signals.prepared.isEmpty())
        }
    }

    @Test fun policyIsCheckedBeforeDownloadAndAgainAfterReadyIncludingFocusAssistAndUserState() {
        val host = Host()
        Fixture(host = host).use { f ->
            f.add(group())
            assertTrue(f.source.requests.isEmpty())
            host.reply(0, WindowsNotificationPolicy(true, 5)); f.flush()
            assertTrue(f.source.requests.isEmpty())
            f.add(group(item = item(2)))
            host.reply(1, WindowsNotificationPolicy(false, 5)); f.flush()
            assertEquals(1, f.source.requests.size)
            f.ready()
            assertEquals(3, host.policies.size, "Ready PCM must request current Windows policy again.")
            host.reply(2, WindowsNotificationPolicy(true, 5)); f.flush()
            assertTrue(f.signals.prepared.isEmpty())
            assertNull(f.state.error)
            f.add(group(item = item(3)))
            host.reply(3, WindowsNotificationPolicy(false, 5)); f.flush()
            f.ready(index = 1)
            host.reply(4, WindowsNotificationPolicy(false, 1)); f.flush()
            assertTrue(f.signals.prepared.isEmpty())
            assertNull(f.state.error)
        }
    }

    @Test fun preferenceCatalogAndWindowsStateChangesDuringEitherPolicyWaitCannotStartOldLoads() {
        val host = Host()
        Fixture(host = host).use { f ->
            f.add(group())
            f.controller.soundCatalogChanged()
            host.reply(0, WindowsNotificationPolicy(false, 5)); f.flush()
            assertTrue(f.source.requests.isEmpty())
            f.add(group(item = item(2)))
            host.reply(1, WindowsNotificationPolicy(false, 5)); f.flush()
            f.ready()
            f.save(f.state.preferences.copy(volumePercent = 90))
            host.reply(2, WindowsNotificationPolicy(false, 5)); f.flush()
            assertTrue(f.signals.prepared.isEmpty())
            f.add(group(item = item(3)))
            host.changed(WindowsDeviceNotificationState(WindowsDeviceNotificationStatus.FAILED, true)); f.flush()
            host.reply(3, WindowsNotificationPolicy(false, 5)); f.flush()
            assertEquals(1, f.source.requests.size)
        }
    }

    @Test fun newArrivalsReplaceOnlyTheCustomOwnerAndOldCallbacksCannotAffectNewPlayback() {
        Fixture().use { f ->
            f.add(group())
            val old = f.source.requests.single()
            f.add(group(item = item(2)))
            assertEquals(1, old.closed)
            f.ready(index = 0, error = SettingsError.REQUEST_FAILED)
            assertNull(f.state.error)
            f.ready(index = 1)
            val output = f.signals.prepared.single()
            f.add(group(item = item(3)))
            assertEquals(1, f.signals.stops)
            output.completed(false); f.flush()
            assertNull(f.state.error)
            assertTrue(f.source.requests[2].active())
            f.ready(index = 2)
            assertEquals(2, f.signals.prepared.size)
            assertTrue(f.signals.prepared.last().active())
            output.completed(true); f.flush()
            assertTrue(f.signals.prepared.last().active())
        }
    }

    @Test fun staleDefaultPolicyCannotReplaceOrStopANewerCustomTone() {
        val host = Host()
        Fixture(host = host).use { f ->
            f.add(group(sound = -1))
            f.add(group(item = item(2)))
            host.reply(1, WindowsNotificationPolicy(false, 5)); f.flush()
            f.ready()
            host.reply(2, WindowsNotificationPolicy(false, 5)); f.flush()
            assertEquals(1, f.signals.prepared.size)
            host.reply(0, WindowsNotificationPolicy(false, 5)); f.flush()
            assertTrue(f.signals.defaults.isEmpty())
            assertEquals(0, f.signals.stops)
            assertTrue(f.signals.prepared.single().active())
        }
    }

    @Test fun realCompletionFailureAndRefusedAdmissionReportOutputFailureOnlyForCurrentOwner() {
        for (admit in listOf(false, true)) Fixture().use { f ->
            f.signals.admit = admit
            f.add(group())
            f.ready()
            if (admit) {
                assertNull(f.state.error, "Admission alone has no success or failure terminal.")
                f.signals.prepared.single().completed(false); f.flush()
            }
            assertEquals(DeviceNotificationError.OUTPUT_FAILED, f.state.error)
            assertEquals(1, f.source.requests.single().closed)
            f.signals.prepared.singleOrNull()?.completed(true); f.flush()
            assertEquals(DeviceNotificationError.OUTPUT_FAILED, f.state.error)
        }
    }

    @Test fun immediateSourceAndOutputCallbacksCompleteOnceAndLateSourceCannotReopenPlayback() {
        Fixture().use { f ->
            f.source.immediate = true
            f.signals.completeImmediately = true
            f.add(group())
            assertEquals(1, f.signals.prepared.size)
            assertEquals(1, f.source.requests.single().closed)
            assertNull(f.state.error)
            f.ready()
            f.signals.prepared.single().completed(false); f.flush()
            assertEquals(1, f.signals.prepared.size)
            assertNull(f.state.error)
        }
    }

    @Test fun aPreparedResultForAnotherServerIdCannotAcquireOutput() {
        Fixture().use { f ->
            f.add(group())
            f.source.requests.single().result(SettingsResult(PreparedNotificationAudio(99, pcm())))
            f.flush()
            assertTrue(f.signals.prepared.isEmpty())
            assertTrue(f.signals.defaults.isEmpty())
            assertEquals(DeviceNotificationError.CUSTOM_SOUND_UNAVAILABLE, f.state.error)
        }
    }

    @Test fun queuedOldOutputFailureCannotMarkANewerToneButCurrentFailureIsRetained() {
        Fixture().use { f ->
            f.add(group()); f.ready()
            f.signals.failure?.invoke()
            f.controller.event(DeviceNotificationEvent.Group(group(item = item(2)), emptyList()))
            f.flush()
            assertNull(f.state.error)
            f.ready(index = 1)
            f.signals.failure?.invoke(); f.flush()
            assertEquals(DeviceNotificationError.OUTPUT_FAILED, f.state.error)
            f.signals.prepared.last().completed(true); f.flush()
            assertEquals(DeviceNotificationError.OUTPUT_FAILED, f.state.error)
        }
    }

    @Test fun pinnedOrdinaryProjectionUsesTheActualTargetSoundAndPolicyRefreshDoesNotReplay() {
        Fixture(initial = preferences().copy(notifyAboutPinned = false)).use { f ->
            val pin = item(1).copy(cause = DeviceNotificationCause.PINNED)
            f.add(group(sound = -1, item = pin).copy(kind = DeviceNotificationGroupKind.MENTIONS))
            assertTrue(f.source.requests.isEmpty())
            f.native.policyResults.single()(DeviceOrdinaryNotificationPolicy(false, Long.MIN_VALUE, true)); f.flush()
            assertEquals(Long.MIN_VALUE, f.source.requests.single().id)
            f.controller.event(DeviceNotificationEvent.PolicyChanged(10))
            assertFalse(f.source.requests.single().active())
            f.ready()
            f.native.policyResults.last()(DeviceOrdinaryNotificationPolicy(false, Long.MIN_VALUE, true)); f.flush()
            assertEquals(1, f.source.requests.size)
            assertTrue(f.signals.prepared.isEmpty())
            assertTrue(f.signals.defaults.isEmpty())
        }
    }

    @Test fun missingAccountOrSourceIsTruthfullyUnavailableAndCloseNeverClosesTheSharedSource() {
        for (missingSource in listOf(false, true)) Fixture(withSource = !missingSource).use { f ->
            if (!missingSource) f.account = 0
            f.add(group())
            assertEquals(DeviceNotificationError.CUSTOM_SOUND_UNAVAILABLE, f.state.error)
            assertTrue(f.source.requests.isEmpty())
            assertTrue(f.signals.defaults.isEmpty())
        }
        val f = Fixture()
        f.add(group())
        f.controller.close()
        assertEquals(1, f.source.requests.single().closed)
        assertFalse(f.source.requests.single().active())
        f.ready(error = SettingsError.NOT_READY)
        assertTrue(f.signals.prepared.isEmpty())
        assertEquals(0, f.source.closes)
        assertFalse(f.state.enabled)
    }

    private class Fixture(
        val host: Host? = null,
        initial: DeviceNotificationPreferences = preferences().copy(useWindowsNotifications = host != null),
        withSource: Boolean = true,
    ) : AutoCloseable {
        var onActor = true
        var account = 700L
        var lockRevision = 0L
        var externalLocked = false
        val outputAllowed = AtomicBoolean(true)
        val source = Source()
        val native = Native()
        val signals = Signals()
        val queue = ArrayDeque<() -> Unit>()
        var state = DeviceNotificationsSnapshot()
        val controller = DeviceNotificationsController(7, native, Store(initial), signals, queue::addLast, { state = it }, { _, _ -> },
            windowsHost = host, externallyLocked = { check(onActor); externalLocked }, externalLockRevision = { lockRevision },
            soundSource = source.takeIf { withSource }, soundAccountUserId = { check(onActor); account },
            soundOutputAllowed = outputAllowed::get)
        init { controller.start(); flush() }
        fun flush() { check(onActor); while (queue.isNotEmpty()) queue.removeFirst().invoke() }
        fun add(group: DeviceNotificationGroup) { controller.event(DeviceNotificationEvent.Group(group, emptyList())); flush() }
        fun ready(index: Int = 0, fallback: Boolean = false, error: SettingsError? = null) {
            val request = source.requests[index]
            request.result(if (error != null) SettingsResult(error = error)
                else SettingsResult(PreparedNotificationAudio(request.id, pcm(), fallback)))
            flush()
        }
        fun save(value: DeviceNotificationPreferences) { controller.save(7, value); flush() }
        override fun close() { controller.close(); flush() }
    }

    private class Source : NotificationSoundSource {
        class Request(val account: Long, val id: Long, val active: () -> Boolean,
            val result: (SettingsResult<PreparedNotificationAudio>) -> Unit) { var closed = 0 }
        val requests = mutableListOf<Request>()
        var closes = 0
        var immediate = false
        override fun load(accountUserId: Long, soundId: Long, active: () -> Boolean,
            result: (SettingsResult<PreparedNotificationAudio>) -> Unit): NotificationSoundLoad {
            val request = Request(accountUserId, soundId, active, result)
            requests.add(request)
            if (immediate) result(SettingsResult(PreparedNotificationAudio(soundId, pcm())))
            return NotificationSoundLoad { if (request.closed == 0) { request.closed++; result(SettingsResult(error = SettingsError.NOT_READY)) } }
        }
        override fun invalidate() = Unit
        override fun close() { closes++ }
    }

    private class Signals : DeviceNotificationSignals {
        class Prepared(val pcm: NotificationSoundPcm, val volume: Int, val active: () -> Boolean, val completed: (Boolean) -> Unit)
        val defaults = mutableListOf<Int>()
        val prepared = mutableListOf<Prepared>()
        var flashes = 0
        var stops = 0
        var admit = true
        var completeImmediately = false
        var failure: (() -> Unit)? = null
        override fun play(volumePercent: Int) { defaults.add(volumePercent) }
        override fun playPrepared(pcm: NotificationSoundPcm, volumePercent: Int, active: () -> Boolean, completed: (Boolean) -> Unit): Boolean {
            if (!admit) return false
            assertTrue(active())
            prepared.add(Prepared(pcm, volumePercent, active, completed))
            if (completeImmediately) completed(true)
            return true
        }
        override fun flash() { flashes++ }
        override fun setFailureListener(listener: (() -> Unit)?) { failure = listener }
        override fun stop() { stops++ }
        override fun close() = Unit
    }

    private class Store(private val value: DeviceNotificationPreferences) : DeviceNotificationPreferencesStore {
        override fun load() = value
        override fun save(value: DeviceNotificationPreferences) = true
    }

    private class Native : TdDeviceNotificationSession {
        val policyResults = mutableListOf<(DeviceOrdinaryNotificationPolicy?) -> Unit>()
        override fun listen(listener: ((DeviceNotificationEvent) -> Unit)?) = Unit
        override fun enable(enabled: Boolean, result: (Boolean) -> Unit) = result(true)
        override fun dismiss(groupId: Int, notificationId: Int, result: (Boolean) -> Unit) = result(true)
        override fun ordinaryPolicy(chatId: Long, active: () -> Boolean, result: (DeviceOrdinaryNotificationPolicy?) -> Unit) { policyResults.add(result) }
    }

    private class Host : DeviceWindowsNotificationHost {
        var changed: (WindowsDeviceNotificationState) -> Unit = {}
        val policies = mutableListOf<(WindowsNotificationPolicy) -> Unit>()
        override fun bind(generation: Long, events: (WindowsToastHostEvent) -> Unit, changed: (WindowsDeviceNotificationState) -> Unit) {
            this.changed = changed
            changed(WindowsDeviceNotificationState(WindowsDeviceNotificationStatus.ENABLED, true))
        }
        override fun select(generation: Long, enabled: Boolean, result: (Boolean) -> Unit) = result(true)
        override fun uninstall(generation: Long, result: (Boolean) -> Unit) = result(true)
        override fun synchronize(generation: Long, cards: List<DeviceNotificationCard>, restored: Boolean) = Unit
        override fun policy(generation: Long, result: (WindowsNotificationPolicy) -> Unit) { policies.add(result) }
        fun reply(index: Int, value: WindowsNotificationPolicy) = policies[index](value)
        override fun retire(generation: Long) = Unit
    }

    private companion object {
        fun preferences() = DeviceNotificationPreferences(desktopEnabled = true, soundEnabled = true, flashEnabled = true, volumePercent = 37)
        fun pcm() = NotificationSoundPcm(byteArrayOf(1, 2, 3, 4))
        fun item(id: Int = 1) = DeviceNotificationItem(id, id, 100L + id, "native title", "native text", false, true, true)
        fun group(sound: Long = -22, item: DeviceNotificationItem = item(), total: Int = 1) =
            DeviceNotificationGroup(1, 10, total, 10, sound, listOf(item))
    }
}
