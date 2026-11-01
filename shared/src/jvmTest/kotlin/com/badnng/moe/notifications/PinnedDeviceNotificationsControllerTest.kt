package com.badnng.moe.notifications

import com.badnng.moe.chat.ChatListRef
import com.badnng.moe.tdlib.TdDeviceNotificationSession
import kotlin.test.*

class PinnedDeviceNotificationsControllerTest {
    @Test fun disabledSpecialPinsWithMutedOrUnknownOrdinaryPolicyNeverGainOutputOrNativeDismissal() {
        for (policy in listOf(null, DeviceOrdinaryNotificationPolicy(true, -1, true))) {
            val f = Fixture()
            f.add(group(1, pin(1)))
            assertTrue(f.state.cards.isEmpty())
            assertEquals(10L, f.native.policies.single().chatId)
            f.policy(policy)
            assertTrue(f.state.cards.isEmpty())
            assertTrue(f.signals.plays.isEmpty())
            assertEquals(0, f.signals.flashes)
            f.controller.activate(id(1, 1), fromWindows = true)
            f.controller.dismiss(id(1, 1), fromWindows = true)
            assertTrue(f.native.resolutions.isEmpty())
            assertTrue(f.native.dismissals.isEmpty())
            f.controller.event(DeviceNotificationEvent.Messages(ChatListRef(), 4, 2))
            assertEquals(4, f.state.unread[ChatListRef()]?.messages)
            assertEquals(1, f.native.policies.size, "Unknown policy is not retried by unrelated aggregate updates.")
        }
    }

    @Test fun unmutedFallbackUsesTargetSoundAndPreviewAndRetainsNativePreviewProtection() {
        val f = Fixture()
        f.add(group(1, pin(1)))
        f.policy(DeviceOrdinaryNotificationPolicy(false, 0, false))
        assertEquals("你收到了一条新消息", f.state.cards.single().text)
        assertTrue(f.signals.plays.isEmpty(), "The special sender's -1 sound cannot override target-chat silence.")
        assertEquals(1, f.signals.flashes)
        f.controller.event(DeviceNotificationEvent.PolicyChanged(10))
        f.policy(DeviceOrdinaryNotificationPolicy(false, -1, true))
        assertEquals("native pin text", f.state.cards.single().text)
        assertTrue(f.signals.plays.isEmpty(), "Policy refresh is a quiet projection.")
        f.controller.event(DeviceNotificationEvent.Replace(1, pin(1).copy(nativeShowPreview = false)))
        f.policy(DeviceOrdinaryNotificationPolicy(false, -1, true))
        assertEquals("你收到了一条新消息", f.state.cards.single().text)
        assertEquals(1, f.signals.flashes)
    }

    @Test fun mixedNativeBatchAlertsForOrdinaryMentionImmediatelyAndLatePinNeverAlertsAgain() {
        val f = Fixture()
        f.add(group(1, pin(1), ordinary(2)))
        assertEquals(listOf(id(1, 2)), f.state.cards.map { it.identity })
        assertEquals(listOf(100), f.signals.plays)
        assertEquals(1, f.signals.flashes)
        f.policy(DeviceOrdinaryNotificationPolicy(false, -1, true))
        assertEquals(2, f.state.cards.size)
        assertEquals(1, f.signals.plays.size)
        assertEquals(1, f.signals.flashes)
        f.add(group(2, pin(3), kind = DeviceNotificationGroupKind.MESSAGES))
        assertEquals(1, f.native.policies.size, "A Messages-group pin retains ordinary native delivery without lookup.")
        assertEquals(2, f.signals.plays.size)
        assertEquals(2, f.signals.flashes)
    }

    @Test fun restorePreferenceTogglesAndLivePolicyInvalidationOnlyReprojectQuietly() {
        val host = Host()
        val f = Fixture(host = host)
        f.controller.event(DeviceNotificationEvent.Active(listOf(group(1, pin(1)))))
        f.policy(DeviceOrdinaryNotificationPolicy(false, -1, true))
        assertTrue(f.signals.plays.isEmpty())
        assertEquals(1, host.cards.last().size)
        assertTrue(host.restored.last())
        f.save(f.state.preferences.copy(notifyAboutPinned = true))
        assertTrue(host.restored.last())
        f.save(f.state.preferences.copy(notifyAboutPinned = false))
        assertTrue(host.cards.last().isEmpty())
        assertTrue(host.restored.last())
        f.policy(DeviceOrdinaryNotificationPolicy(false, -1, true))
        assertEquals(1, host.cards.last().size)
        assertTrue(host.restored.last())
        f.controller.event(DeviceNotificationEvent.PolicyChanged())
        assertTrue(host.cards.last().isEmpty())
        f.policy(DeviceOrdinaryNotificationPolicy(true, -1, true))
        assertTrue(host.cards.last().isEmpty())
        assertTrue(f.signals.plays.isEmpty())
        assertEquals(0, f.signals.flashes)
        assertTrue(f.native.dismissals.isEmpty())
    }

    @Test fun metadataRequestsCoalescePerChatAndReplacementsOrRemovalRetireOldCallbacks() {
        val f = Fixture()
        f.add(group(1, pin(1)))
        val old = f.native.policies.single()
        f.add(group(2, pin(2)))
        assertEquals(1, f.native.policies.size)
        f.controller.event(DeviceNotificationEvent.Replace(1, ordinary(1)))
        assertFalse(old.active())
        assertEquals(2, f.native.policies.size)
        old.result(DeviceOrdinaryNotificationPolicy(false, -1, true)); f.flush()
        assertEquals(listOf(id(1, 1)), f.state.cards.map { it.identity })
        val pending = f.native.policies.last()
        f.controller.event(DeviceNotificationEvent.Group(group(2, total = 0), listOf(2)))
        assertFalse(pending.active())
        pending.result(DeviceOrdinaryNotificationPolicy(false, -1, true)); f.flush()
        assertEquals(listOf(id(1, 1)), f.state.cards.map { it.identity })
        assertTrue(f.signals.plays.isEmpty(), "Replace/removal consumes prior waiting arrivals without replay.")
        assertTrue(f.native.dismissals.isEmpty())
    }

    @Test fun failedPreferenceSaveKeepsTheOldSpecialPolicyAndExistingActivationOwner() {
        val f = Fixture(initial = preferences().copy(notifyAboutPinned = true))
        f.add(group(1, pin(1)))
        f.controller.activate(id(1, 1))
        f.store.allowSave = false
        f.save(f.state.preferences.copy(notifyAboutPinned = false))
        assertTrue(f.state.preferences.notifyAboutPinned)
        assertEquals(DeviceNotificationError.SAVE_FAILED, f.state.error)
        assertEquals(1, f.state.cards.size)
        assertTrue(f.native.policies.isEmpty())
        f.native.resolutions.single()(true); f.flush()
        assertEquals(listOf<Pair<Long, Long?>>(10L to 101L), f.navigations)
    }

    @Test fun policyCyclesCancelActivationsAndStaleWindowsActionsCannotBypassSuppressedEligibility() {
        val host = Host()
        val f = Fixture(host = host, initial = preferences().copy(notifyAboutPinned = true, useWindowsNotifications = true))
        f.add(group(1, pin(1)))
        host.events(WindowsToastHostEvent.Activate(id(1, 1))); f.flush()
        val oldActivation = f.native.resolutions.single()
        f.save(f.state.preferences.copy(notifyAboutPinned = false))
        oldActivation(true); f.flush()
        assertTrue(f.navigations.isEmpty())
        host.events(WindowsToastHostEvent.Activate(id(1, 1)))
        host.events(WindowsToastHostEvent.Dismiss(id(1, 1))); f.flush()
        assertEquals(1, f.native.resolutions.size)
        assertTrue(f.native.dismissals.isEmpty())
        f.policy(DeviceOrdinaryNotificationPolicy(false, -1, true))
        host.events(WindowsToastHostEvent.Activate(id(1, 1))); f.flush()
        val ordinaryActivation = f.native.resolutions.last()
        f.controller.event(DeviceNotificationEvent.PolicyChanged(10))
        f.policy(DeviceOrdinaryNotificationPolicy(false, -1, true))
        ordinaryActivation(true); f.flush()
        assertTrue(f.navigations.isEmpty(), "A policy cycle revokes the original activation even when eligibility later returns.")
        assertEquals(1, host.cards.last().size)
    }

    @Test fun asynchronousWindowsPolicyCannotPlayThePreviousOrdinarySoundAfterNativePolicyChanges() {
        val host = Host(delayedPolicy = true)
        val f = Fixture(host = host)
        f.add(group(1, pin(1)))
        f.policy(DeviceOrdinaryNotificationPolicy(false, -1, true))
        assertEquals(1, host.policies.size)
        f.controller.event(DeviceNotificationEvent.PolicyChanged(10))
        f.policy(DeviceOrdinaryNotificationPolicy(false, 0, false))
        host.policies.single()(WindowsNotificationPolicy(false, 5)); f.flush()
        assertTrue(f.signals.plays.isEmpty())
        assertEquals(0, f.signals.flashes)
        assertEquals("你收到了一条新消息", host.cards.last().single().text)
        assertTrue(host.restored.last())
    }

    @Test fun policyUpdateDuringInitialLookupPreservesTheUnclaimedArrivalForTheReplacementLookup() {
        val f = Fixture()
        f.add(group(1, pin(1)))
        val superseded = f.native.policies.single()
        f.controller.event(DeviceNotificationEvent.PolicyChanged(10))
        assertFalse(superseded.active())
        assertEquals(2, f.native.policies.size)
        superseded.result(DeviceOrdinaryNotificationPolicy(false, -1, true)); f.flush()
        assertTrue(f.state.cards.isEmpty())
        f.policy(DeviceOrdinaryNotificationPolicy(false, -1, true))
        assertEquals(1, f.state.cards.size)
        assertEquals(listOf(100), f.signals.plays)
        assertEquals(1, f.signals.flashes)
        superseded.result(DeviceOrdinaryNotificationPolicy(false, -1, true)); f.flush()
        assertEquals(1, f.signals.plays.size)
        assertEquals(1, f.signals.flashes)
    }

    @Test fun anotherChatsPinPolicyDoesNotCancelOrdinaryMentionsAttentionOrActivation() {
        val host = Host(delayedPolicy = true)
        val f = Fixture(host = host)
        f.add(group(2, ordinary(2)).copy(chatId = 20))
        assertEquals(1, host.policies.size)
        host.events(WindowsToastHostEvent.Activate(id(2, 2))); f.flush()
        val activation = f.native.resolutions.single()
        f.add(group(1, pin(1)))
        val lookup = f.native.policies.single()
        f.add(group(3, ordinary(3).copy(silent = true)))
        f.controller.event(DeviceNotificationEvent.Replace(3, ordinary(3).copy(silent = true, text = "updated ordinary text")))
        assertTrue(lookup.active(), "An ordinary replacement must not retire the same chat's unrelated pin metadata.")
        f.controller.event(DeviceNotificationEvent.PolicyChanged(10))
        host.policies.single()(WindowsNotificationPolicy(false, 5))
        activation(true); f.flush()
        assertEquals(listOf(100), f.signals.plays)
        assertEquals(1, f.signals.flashes)
        assertEquals(listOf<Pair<Long, Long?>>(20L to 102L), f.navigations)
        assertTrue(f.native.dismissals.isEmpty())
    }

    @Test fun disabledOutputsOwnNoMetadataOrDeferredAttentionAndReenablingOnlyRestoresOldPinsQuietly() {
        val f = Fixture(initial = preferences().copy(desktopEnabled = false, soundEnabled = false, flashEnabled = false))
        f.add(group(1, pin(1)))
        assertTrue(f.native.policies.isEmpty())
        f.controller.activate(id(1, 1), fromWindows = true)
        assertTrue(f.native.resolutions.isEmpty(), "Disabling metadata ownership cannot turn a suppressed pin into a special notification.")
        f.save(preferences())
        assertEquals(1, f.native.policies.size)
        val pending = f.native.policies.single()
        f.save(f.state.preferences.copy(desktopEnabled = false, soundEnabled = false, flashEnabled = false))
        assertFalse(pending.active())
        pending.result(DeviceOrdinaryNotificationPolicy(false, -1, true)); f.flush()
        assertTrue(f.signals.plays.isEmpty())
        f.save(preferences())
        assertEquals(2, f.native.policies.size)
        f.policy(DeviceOrdinaryNotificationPolicy(false, -1, true))
        assertEquals(1, f.state.cards.size)
        assertTrue(f.signals.plays.isEmpty())
        assertEquals(0, f.signals.flashes)
        assertTrue(f.native.dismissals.isEmpty())
    }

    @Test fun failedLookupRetriesOnlyForANewPinAndProjectsOldQuietBesideFreshIncludingSilentPins() {
        val host = Host()
        val f = Fixture(host = host)
        f.add(group(1, pin(1)))
        f.policy(null)
        f.controller.event(DeviceNotificationEvent.Messages(ChatListRef(), 8, 5))
        f.flush()
        assertEquals(1, f.native.policies.size, "Unread aggregate updates do not retry a failed metadata lookup.")
        f.add(group(1, pin(2), pin(3).copy(silent = true), total = 3))
        assertEquals(2, f.native.policies.size)
        f.policy(DeviceOrdinaryNotificationPolicy(false, -1, true))
        val cards = host.cards.last().associateBy { it.identity.notificationId }
        assertTrue(cards.getValue(1).quiet)
        assertFalse(cards.getValue(2).quiet)
        assertFalse(cards.getValue(3).quiet, "Native silent suppresses sound, not a real new pin's desktop popup eligibility.")
        assertEquals(listOf(100), f.signals.plays)
        assertEquals(1, f.signals.flashes)
        f.controller.event(DeviceNotificationEvent.Messages(ChatListRef(), 9, 6))
        assertEquals(cards, host.cards.last().associateBy { it.identity.notificationId },
            "Ordinary projections retain fresh popup provenance until its native identity or policy cycle retires.")
        assertEquals(2, f.native.policies.size)
    }

    @Test fun unlockingRetriesFailedMetadataQuietlyWithoutTreatingTheOldPinAsANewArrival() {
        val f = Fixture()
        f.add(group(1, pin(1)))
        f.policy(null)
        f.controller.event(DeviceNotificationEvent.Messages(ChatListRef(), 2, 1))
        assertEquals(1, f.native.policies.size)
        f.controller.setContext(false, null, true)
        f.controller.setContext(false, null, false)
        assertEquals(2, f.native.policies.size)
        f.policy(DeviceOrdinaryNotificationPolicy(false, -1, true))
        assertTrue(f.state.cards.single().quiet)
        assertTrue(f.signals.plays.isEmpty())
        assertEquals(0, f.signals.flashes)
        assertTrue(f.native.dismissals.isEmpty())
        f.controller.event(DeviceNotificationEvent.Messages(ChatListRef(), 3, 2))
        assertEquals(2, f.native.policies.size)
    }

    private class Fixture(host: Host? = null, initial: DeviceNotificationPreferences = preferences().copy(useWindowsNotifications = host != null)) {
        val native = Native()
        val store = Store(initial)
        val signals = Signals()
        val queue = ArrayDeque<() -> Unit>()
        var state = DeviceNotificationsSnapshot()
        val navigations = mutableListOf<Pair<Long, Long?>>()
        val controller = DeviceNotificationsController(7, native, store, signals, queue::addLast, { state = it },
            { chat, message -> navigations += chat to message }, windowsHost = host)
        init { controller.start(); flush() }
        fun flush() { while (queue.isNotEmpty()) queue.removeFirst().invoke() }
        fun add(group: DeviceNotificationGroup) { controller.event(DeviceNotificationEvent.Group(group, emptyList())); flush() }
        fun policy(value: DeviceOrdinaryNotificationPolicy?) { native.policies.last().result(value); flush() }
        fun save(value: DeviceNotificationPreferences) { controller.save(7, value); flush() }
    }

    private class Native : TdDeviceNotificationSession {
        data class Policy(val chatId: Long, val active: () -> Boolean, val result: (DeviceOrdinaryNotificationPolicy?) -> Unit)
        val policies = mutableListOf<Policy>()
        val resolutions = mutableListOf<(Boolean) -> Unit>()
        val dismissals = mutableListOf<Pair<Int, Int>>()
        override fun listen(listener: ((DeviceNotificationEvent) -> Unit)?) {}
        override fun enable(enabled: Boolean, result: (Boolean) -> Unit) = result(true)
        override fun ordinaryPolicy(chatId: Long, active: () -> Boolean, result: (DeviceOrdinaryNotificationPolicy?) -> Unit) { policies += Policy(chatId, active, result) }
        override fun resolveTarget(identity: DeviceNotificationIdentity, chatId: Long, messageId: Long, openMessage: Boolean, result: (Boolean) -> Unit) { resolutions += result }
        override fun dismiss(groupId: Int, notificationId: Int, result: (Boolean) -> Unit) { dismissals += groupId to notificationId; result(true) }
    }

    private class Store(private val initial: DeviceNotificationPreferences) : DeviceNotificationPreferencesStore {
        var allowSave = true
        override fun load() = initial
        override fun save(value: DeviceNotificationPreferences) = allowSave
    }

    private class Signals : DeviceNotificationSignals {
        val plays = mutableListOf<Int>()
        var flashes = 0
        override fun play(volumePercent: Int) { plays += volumePercent }
        override fun flash() { flashes++ }
        override fun stop() {}
        override fun setFailureListener(listener: (() -> Unit)?) {}
        override fun close() {}
    }

    private class Host(private val delayedPolicy: Boolean = false) : DeviceWindowsNotificationHost {
        var events: (WindowsToastHostEvent) -> Unit = {}
        val cards = mutableListOf<List<DeviceNotificationCard>>()
        val restored = mutableListOf<Boolean>()
        val policies = mutableListOf<(WindowsNotificationPolicy) -> Unit>()
        override fun bind(generation: Long, events: (WindowsToastHostEvent) -> Unit, changed: (WindowsDeviceNotificationState) -> Unit) {
            this.events = events
            changed(WindowsDeviceNotificationState(WindowsDeviceNotificationStatus.ENABLED, true))
        }
        override fun select(generation: Long, enabled: Boolean, result: (Boolean) -> Unit) = result(true)
        override fun uninstall(generation: Long, result: (Boolean) -> Unit) = result(true)
        override fun synchronize(generation: Long, cards: List<DeviceNotificationCard>, restored: Boolean) { this.cards += cards; this.restored += restored }
        override fun policy(generation: Long, result: (WindowsNotificationPolicy) -> Unit) {
            policies += result
            if (!delayedPolicy) result(WindowsNotificationPolicy(false, 5))
        }
        override fun retire(generation: Long) {}
    }

    private companion object {
        fun preferences() = DeviceNotificationPreferences(desktopEnabled = true, soundEnabled = true, flashEnabled = true, notifyAboutPinned = false)
        fun id(group: Int, item: Int) = DeviceNotificationIdentity(7, group, item)
        fun pin(id: Int) = DeviceNotificationItem(id, id, 100L + id, "Target native chat", "native pin text", false, true, true, DeviceNotificationCause.PINNED)
        fun ordinary(id: Int) = pin(id).copy(cause = DeviceNotificationCause.ORDINARY)
        fun group(id: Int, vararg items: DeviceNotificationItem, kind: DeviceNotificationGroupKind = DeviceNotificationGroupKind.MENTIONS, total: Int = items.size) =
            DeviceNotificationGroup(id, 10, total, 99, -1, items.toList(), kind)
    }
}
