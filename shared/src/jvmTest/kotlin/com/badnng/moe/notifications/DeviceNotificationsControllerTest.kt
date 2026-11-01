package com.badnng.moe.notifications

import com.badnng.moe.chat.ChatListRef
import com.badnng.moe.tdlib.TdDeviceNotificationSession
import java.nio.file.Files
import kotlin.test.*

class DeviceNotificationsControllerTest {
    @Test fun aResolvedClickCannotCrossALockCycleOrCompleteANewerClickWithTheSameIdentity() {
        var locked = false
        var revision = 0L
        val f = Fixture(initial = DeviceNotificationPreferences(desktopEnabled = true),
            externalLocked = { locked }, externalLockRevision = { revision })
        f.controller.event(DeviceNotificationEvent.Group(group(1, item(1)), emptyList()))
        f.flush()
        val id = f.state.cards.single().identity
        f.native.resolveAutomatically = false
        f.controller.activate(id)
        val beforeLock = f.native.resolveResults.single()
        locked = true; revision++
        f.controller.setContext(false, null, true)
        locked = false
        f.controller.setContext(false, null, false)
        beforeLock(true); f.flush()
        assertTrue(f.navigations.isEmpty())
        assertEquals(0L, f.state.openChatRevision)
        f.controller.activate(id)
        beforeLock(true); f.flush()
        assertTrue(f.navigations.isEmpty(), "An old result cannot finish a newer click with the same native identity.")
        f.native.resolveResults.last()(true); f.flush()
        assertEquals(1, f.navigations.size)
        f.controller.close()
    }

    @Test fun anExternalLockCycleInvalidatesOldResolutionEvenBeforeContextIsPublished() {
        var revision = 0L
        val f = Fixture(initial = DeviceNotificationPreferences(desktopEnabled = true), externalLockRevision = { revision })
        f.controller.event(DeviceNotificationEvent.Group(group(1, item(1)), emptyList()))
        f.flush()
        f.native.resolveAutomatically = false
        f.controller.activate(f.state.cards.single().identity)
        revision++ // The native lock and unlock happened before the actor's current-state publication.
        f.native.resolveResults.single()(true); f.flush()
        assertTrue(f.navigations.isEmpty())
        assertEquals(0L, f.state.openChatRevision)
        f.controller.close()
    }

    @Test fun anExternalMandatoryLockRechecksAnAlreadyPendingActivationAndRedactsWithoutUiContext() {
        var locked = false
        val f = Fixture(initial = DeviceNotificationPreferences(desktopEnabled = true, soundEnabled = true),
            externalLocked = { locked })
        f.controller.event(DeviceNotificationEvent.Group(group(1, item(1)), emptyList()))
        f.flush()
        val id = f.state.cards.single().identity
        f.native.resolveAutomatically = false
        f.controller.activate(id)
        assertEquals(1, f.native.resolveResults.size)
        locked = true
        f.controller.event(DeviceNotificationEvent.Messages(ChatListRef(), 1, 1))
        assertEquals("nngGram", f.state.cards.single().title)
        assertEquals("你收到了一条新消息", f.state.cards.single().text)
        f.native.resolveResults.single()(true)
        f.flush()
        assertTrue(f.navigations.isEmpty())
        assertEquals(1, f.lockFocus)
        val previousPlays = f.signals.plays.size
        f.controller.event(DeviceNotificationEvent.Group(group(2, item(2)), emptyList()))
        f.flush()
        assertEquals(previousPlays, f.signals.plays.size)
        locked = false
        f.native.resolveAutomatically = true
        f.controller.activate(id)
        f.flush()
        assertEquals(1, f.navigations.size)
        f.controller.close()
    }

    @Test fun anUnavailableExternalLockPredicateCannotExposeOrNavigatePrivateContent() {
        val f = Fixture(initial = DeviceNotificationPreferences(desktopEnabled = true, soundEnabled = true),
            externalLocked = { error("synthetic unavailable gate") })
        f.controller.event(DeviceNotificationEvent.Group(group(1, item(1)), emptyList()))
        f.flush()
        assertEquals("nngGram", f.state.cards.single().title)
        assertEquals("你收到了一条新消息", f.state.cards.single().text)
        f.controller.activate(f.state.cards.single().identity)
        f.flush()
        assertTrue(f.native.resolveResults.isEmpty())
        assertTrue(f.signals.plays.isEmpty())
        assertEquals(1, f.lockFocus)
        f.controller.close()
    }

    @Test fun aTemporaryPreferenceWriteKeepsItsResponsibilityAcrossANewerFailedOperationAndLateCommit() {
        for (remove in listOf(false, true)) {
            val host = WindowsHost().apply { autoCommit = false }
            val f = Fixture(host)
            f.controller.selectWindows(7, true)
            host.complete(0, true); f.flush()
            assertTrue(f.state.modeSavePending)
            assertTrue(f.store.saved.last().useWindowsNotifications)
            if (remove) {
                host.uninstallResult = false
                f.controller.removeWindowsRegistration(7)
            } else {
                f.controller.selectWindows(7, false)
                host.complete(1, false)
            }
            f.flush()
            assertEquals(DeviceNotificationError.OUTPUT_FAILED, f.state.error)
            assertTrue(f.state.modeSavePending)
            host.commitResults.single()(true); f.flush()
            assertFalse(f.state.preferences.useWindowsNotifications)
            assertTrue(f.state.modeSavePending, "Ignoring a stale callback cannot erase its earlier disk write.")
            f.save(f.state.preferences)
            assertFalse(f.state.modeSavePending)
            assertFalse(f.store.saved.last().useWindowsNotifications)
            f.controller.close()
        }
    }

    @Test fun closingAfterATemporaryWriteRestoresOnlyTheActualPreferencesAndRejectsLateCommit() {
        val host = WindowsHost().apply { autoCommit = false }
        val f = Fixture(host)
        f.controller.selectWindows(7, true)
        host.complete(0, true); f.flush()
        assertTrue(f.state.modeSavePending)
        f.controller.close()
        assertFalse(f.store.saved.last().useWindowsNotifications)
        assertFalse(f.state.modeSavePending)
        assertFalse(f.state.enabled)
        val savedCount = f.store.saved.size
        host.commitResults.single()(true); f.flush()
        assertEquals(savedCount, f.store.saved.size)
        assertEquals(listOf(7L), host.retired)
        assertEquals(1, host.results.size, "Retirement must not start a fresh Host selection.")
    }

    @Test fun aFailedLocalRestoreDuringRetirementRemainsUnresolvedAndCannotBeRevivedByALateCallback() {
        val host = WindowsHost().apply { autoCommit = false }
        val f = Fixture(host)
        f.controller.selectWindows(7, true)
        host.complete(0, true); f.flush()
        f.store.allowSave = false
        f.controller.close()
        assertTrue(f.store.saved.last().useWindowsNotifications)
        assertTrue(f.state.modeSavePending)
        assertEquals(DeviceNotificationError.SAVE_FAILED, f.state.error)
        assertFalse(f.state.preferences.useWindowsNotifications)
        assertFalse(f.state.enabled)
        val attempted = f.store.attempts
        host.commitResults.single()(false); f.flush()
        assertEquals(attempted, f.store.attempts)
        assertTrue(f.state.modeSavePending)
        assertEquals(listOf(7L), host.retired)
    }

    @Test fun lockedApplicationFallbackCannotPlayAndFailedSystemPolicyRemainsUnknown() {
        val app = Fixture(initial = DeviceNotificationPreferences(soundEnabled = true))
        app.controller.setContext(false, null, true)
        app.controller.event(DeviceNotificationEvent.Group(group(1, item(1)), emptyList()))
        assertTrue(app.signals.plays.isEmpty(), "Lock is an independent sound gate even without a system backend.")
        app.controller.close()
        val host = WindowsHost(WindowsDeviceNotificationState(WindowsDeviceNotificationStatus.FAILED, true))
        val system = Fixture(host, DeviceNotificationPreferences(soundEnabled = true, flashEnabled = true,
            useWindowsNotifications = true, respectWindowsFocusAssist = true))
        system.controller.event(DeviceNotificationEvent.Group(group(1, item(1)), emptyList()))
        assertTrue(system.signals.plays.isEmpty())
        assertEquals(0, system.signals.flashes)
        system.save(system.state.preferences.copy(respectWindowsFocusAssist = false))
        system.controller.event(DeviceNotificationEvent.Group(group(1, item(2)), emptyList()))
        assertEquals(listOf(100), system.signals.plays)
        assertEquals(1, system.signals.flashes)
        system.controller.close()
    }

    @Test fun failedDurableRegistrationCommitRestoresOldPreferencesAndNeverClaimsSystemMode() {
        val host = WindowsHost().apply { commitResult = false }
        val f = Fixture(host)
        f.controller.selectWindows(7, true)
        host.complete(0, true); f.flush()
        assertFalse(f.state.preferences.useWindowsNotifications)
        assertFalse(f.store.saved.last().useWindowsNotifications)
        assertEquals(DeviceNotificationError.SAVE_FAILED, f.state.error)
        assertEquals(1, host.cancels)
        f.controller.close()
    }

    @Test fun failedSystemOutputDoesNotRequeueItsProjectionFromTheFailureSnapshot() {
        val host = WindowsHost(WindowsDeviceNotificationState(WindowsDeviceNotificationStatus.ENABLED, true))
        val f = Fixture(host, DeviceNotificationPreferences(desktopEnabled = true, useWindowsNotifications = true))
        f.controller.event(DeviceNotificationEvent.Group(group(1, item(1)), emptyList()))
        f.flush() // Drain the arrival's earlier legitimate policy callback before the failure baseline.
        val count = host.projections.size
        host.events(WindowsToastHostEvent.Failed(7)); f.flush()
        assertEquals(count, host.projections.size)
        assertEquals(WindowsDeviceNotificationStatus.FAILED, f.state.windows.status)
        assertEquals(DeviceNotificationError.OUTPUT_FAILED, f.state.error)
        f.controller.close()
    }

    @Test fun committedSystemModeRemainsRealWhenSavingAnUnrelatedMergedPreferenceFails() {
        val host = WindowsHost().apply { autoCommit = false }
        val f = Fixture(host)
        f.controller.selectWindows(7, true)
        host.complete(0, true); f.flush()
        assertFalse(f.state.preferences.useWindowsNotifications)
        f.save(f.state.preferences.copy(volumePercent = 37))
        assertEquals(37, f.state.preferences.volumePercent)
        f.store.allowSave = false
        host.commitResults.single()(true); f.flush()
        assertTrue(f.state.preferences.useWindowsNotifications, "Already committed OS mode must match the effective UI mode.")
        assertEquals(37, f.state.preferences.volumePercent)
        assertEquals(WindowsDeviceNotificationStatus.ENABLED, f.state.windows.status)
        assertEquals(DeviceNotificationError.MODE_COMMITTED_SAVE_FAILED, f.state.error)
        assertTrue(f.state.modeSavePending)
        assertEquals(0, host.cancels, "A completed registration is no longer a rollback candidate.")
        f.save(f.state.preferences)
        assertEquals(DeviceNotificationError.MODE_COMMITTED_SAVE_FAILED, f.state.error, "A failed retry must preserve partial-success semantics.")
        assertTrue(f.state.modeSavePending)
        f.store.allowSave = true
        f.save(f.state.preferences)
        assertTrue(f.store.saved.last().useWindowsNotifications)
        assertNull(f.state.error)
        assertFalse(f.state.modeSavePending)
        f.controller.close()
    }

    @Test fun unsavedCommittedModeSurvivesASeparateNativeConfigurationFailureUntilSaved() {
        val host = WindowsHost().apply { autoCommit = false }
        val f = Fixture(host)
        f.controller.selectWindows(7, true)
        host.complete(0, true); f.flush()
        f.native.autoComplete = false
        f.store.allowSave = false
        host.commitResults.single()(true); f.flush()
        assertTrue(f.state.modeSavePending)
        f.native.results.last()(false); f.flush()
        assertEquals(DeviceNotificationError.NATIVE_FAILED, f.state.error)
        assertTrue(f.state.modeSavePending)
        assertTrue(f.state.preferences.useWindowsNotifications)
        f.store.allowSave = true
        f.save(f.state.preferences)
        assertFalse(f.state.modeSavePending)
        assertNull(f.state.error)
        f.controller.close()
    }

    @Test fun failedDurableCommitAndFailedPreferenceRollbackKeepTheActualOldModeMarkedUnsaved() {
        val host = WindowsHost().apply { autoCommit = false }
        val f = Fixture(host)
        f.controller.selectWindows(7, true)
        host.complete(0, true); f.flush()
        assertTrue(f.store.saved.last().useWindowsNotifications)
        f.store.allowSave = false
        host.commitResults.single()(false); f.flush()
        assertFalse(f.state.preferences.useWindowsNotifications)
        assertTrue(f.state.modeSavePending)
        assertEquals(DeviceNotificationError.SAVE_FAILED, f.state.error)
        assertEquals(1, host.cancels)
        f.signals.failed(); f.flush()
        assertTrue(f.state.modeSavePending)
        assertEquals(DeviceNotificationError.OUTPUT_FAILED, f.state.error)
        f.store.allowSave = true
        f.save(f.state.preferences)
        assertFalse(f.state.modeSavePending)
        assertFalse(f.store.saved.last().useWindowsNotifications)
        assertNull(f.state.error)
        f.controller.close()
    }

    @Test fun unsavedCommittedModeSurvivesSoundAndWindowsOutputFailuresUntilARealSave() {
        val host = WindowsHost().apply { autoCommit = false }
        val f = Fixture(host)
        f.controller.selectWindows(7, true)
        host.complete(0, true); f.flush()
        f.store.allowSave = false
        host.commitResults.single()(true); f.flush()
        f.signals.failed(); f.flush()
        assertEquals(DeviceNotificationError.OUTPUT_FAILED, f.state.error)
        assertTrue(f.state.modeSavePending)
        host.events(WindowsToastHostEvent.Failed(7)); f.flush()
        assertEquals(WindowsDeviceNotificationStatus.FAILED, f.state.windows.status)
        assertTrue(f.state.modeSavePending)
        assertTrue(f.state.preferences.useWindowsNotifications)
        f.store.allowSave = true
        f.save(f.state.preferences)
        assertFalse(f.state.modeSavePending)
        assertNull(f.state.error)
        f.controller.close()
    }

    @Test fun removingARealRegistrationAdoptsAppModeAndTracksItsFailedPersistenceSeparately() {
        val host = WindowsHost(WindowsDeviceNotificationState(WindowsDeviceNotificationStatus.ENABLED, true))
        val f = Fixture(host, DeviceNotificationPreferences(useWindowsNotifications = true))
        f.store.allowSave = false
        f.controller.removeWindowsRegistration(7); f.flush()
        assertFalse(f.state.preferences.useWindowsNotifications)
        assertTrue(f.state.modeSavePending)
        assertEquals(DeviceNotificationError.MODE_COMMITTED_SAVE_FAILED, f.state.error)
        f.store.allowSave = true
        f.save(f.state.preferences)
        assertFalse(f.state.modeSavePending)
        assertFalse(f.store.saved.last().useWindowsNotifications)
        f.controller.close()
    }

    @Test fun aSameValueSaveWhileCommitIsPendingCannotPersistTheOldModeAfterAcceptance() {
        val host = WindowsHost().apply { autoCommit = false }
        val f = Fixture(host)
        f.controller.selectWindows(7, true)
        host.complete(0, true); f.flush()
        f.save(f.state.preferences)
        assertFalse(f.store.saved.last().useWindowsNotifications)
        host.commitResults.single()(true); f.flush()
        assertTrue(f.state.preferences.useWindowsNotifications)
        assertTrue(f.store.saved.last().useWindowsNotifications)
        assertNull(f.state.error)
        f.controller.close()
    }

    @Test fun evictedNativeGroupsDoNotRetainOldActivationOrPopupOwnership() {
        val f = Fixture()
        f.save(DeviceNotificationPreferences(desktopEnabled = true))
        val id = DeviceNotificationIdentity(7, 1, 1)
        f.controller.event(DeviceNotificationEvent.Group(group(1, item(1)), emptyList()))
        f.controller.activate(id); f.flush()
        assertEquals(1, f.navigations.size)
        (2..27).forEach { groupId -> f.controller.event(DeviceNotificationEvent.Group(group(groupId, item(1)), emptyList())) }
        f.controller.event(DeviceNotificationEvent.Group(group(1, item(1)), emptyList()))
        f.controller.activate(id); f.flush()
        assertEquals(2, f.navigations.size, "Evicted identities cannot retain authority or block a fresh native group.")
        f.controller.close()
    }

    @Test fun windowsBackendDoesNotPersistAnOptimisticSelectionAndOldSuccessCannotOverrideAppMode() {
        val host = WindowsHost()
        val f = Fixture(host)
        f.controller.selectWindows(7, true)
        f.flush()
        assertFalse(f.state.preferences.useWindowsNotifications)
        assertEquals(WindowsDeviceNotificationStatus.ENABLING, f.state.windows.status)
        f.controller.selectWindows(7, false)
        f.flush()
        host.complete(1, true); f.flush()
        host.complete(0, true); f.flush()
        assertFalse(f.state.preferences.useWindowsNotifications)
        assertEquals(1, host.commits)
    }

    @Test fun failedPreferenceCommitCancelsOnlyTheNewSelectionAndNeverClaimsSystemMode() {
        val host = WindowsHost()
        val f = Fixture(host)
        f.store.allowSave = false
        f.controller.selectWindows(7, true)
        host.complete(0, true); f.flush()
        assertFalse(f.state.preferences.useWindowsNotifications)
        assertEquals(DeviceNotificationError.SAVE_FAILED, f.state.error)
        assertEquals(1, host.cancels)
        assertEquals(0, host.commits)
        f.controller.close()
        assertEquals(listOf(7L), host.retired)
    }

    @Test fun fullWindowsProjectionIgnoresOverlayCapacityAndLocalExpiryButHonorsNativeRemoval() {
        val host = WindowsHost(WindowsDeviceNotificationState(WindowsDeviceNotificationStatus.ENABLED, true))
        val f = Fixture(host, DeviceNotificationPreferences(desktopEnabled = true, maximumVisible = 1, useWindowsNotifications = true))
        (1..4).forEach { id -> f.controller.event(DeviceNotificationEvent.Group(group(1, item(id), total = 50), emptyList())) }
        assertEquals(4, host.projections.last().size)
        assertTrue(f.state.cards.isEmpty())
        val first = DeviceNotificationIdentity(7, 1, 1)
        f.controller.expire(first)
        assertEquals(4, host.projections.last().size)
        host.events(WindowsToastHostEvent.Dismiss(first)); f.flush()
        assertEquals(listOf(1 to 1), f.native.dismissals)
        f.controller.event(DeviceNotificationEvent.Group(group(1, item(4), total = 49), listOf(1)))
        assertEquals(3, host.projections.last().size)
    }

    @Test fun pendingWindowsSelectionAndLateNativeActivationLoseAuthorityAtRetirement() {
        val host = WindowsHost()
        val f = Fixture(host)
        f.controller.selectWindows(7, true)
        f.controller.close()
        host.complete(0, true)
        host.events(WindowsToastHostEvent.Activate(DeviceNotificationIdentity(7, 1, 1)))
        f.flush()
        assertFalse(f.state.enabled)
        assertEquals(0, host.commits)
        assertTrue(f.navigations.isEmpty())
    }

    @Test fun activationRechecksNativeReplacementAndFailedResolutionKeepsTheLiveCard() {
        val f = Fixture()
        f.save(DeviceNotificationPreferences(desktopEnabled = true))
        f.native.resolveAutomatically = false
        f.controller.event(DeviceNotificationEvent.Group(group(1, item(1)), emptyList()))
        val id = f.state.cards.single().identity
        f.controller.activate(id)
        val old = f.native.resolveResults.last()
        f.controller.event(DeviceNotificationEvent.Replace(1, item(1).copy(messageId = 500)))
        old(true)
        f.flush()
        assertTrue(f.navigations.isEmpty())
        f.controller.activate(id)
        f.native.resolveResults.last()(false)
        f.flush()
        assertEquals(DeviceNotificationError.NATIVE_FAILED, f.state.error)
        assertEquals(id, f.state.cards.single().identity)
        f.controller.activate(id)
        val late = f.native.resolveResults.last()
        f.controller.close()
        late(true)
        f.flush()
        assertTrue(f.navigations.isEmpty())
        assertFalse(f.state.enabled)
    }

    @Test fun outputsAreOptInAndActiveRestoreNeverPlaysOrFlashes() {
        val f = Fixture()
        assertEquals(listOf(false), f.native.enabled)
        f.controller.event(DeviceNotificationEvent.Group(group(1, item(1)), emptyList()))
        assertEquals(0, f.signals.plays.size)
        assertTrue(f.state.cards.isEmpty())
        f.save(DeviceNotificationPreferences(desktopEnabled = true, soundEnabled = true, flashEnabled = true))
        f.controller.event(DeviceNotificationEvent.Active(listOf(group(1, item(2)))))
        assertEquals(0, f.signals.plays.size)
        assertEquals(0, f.signals.flashes)
        assertEquals(2, f.state.cards.single().identity.notificationId)
        f.controller.event(DeviceNotificationEvent.Group(group(1, item(3), total = 20), emptyList()))
        assertEquals(listOf(100), f.signals.plays)
        assertEquals(1, f.signals.flashes)
        // Re-delivery/replacement of a current native identity cannot ring twice.
        f.controller.event(DeviceNotificationEvent.Group(group(1, item(3)), emptyList()))
        f.controller.event(DeviceNotificationEvent.Replace(1, item(3).copy(text = "edited")))
        assertEquals(1, f.signals.plays.size)
    }

    @Test fun silentCustomAndZeroSoundRemainDistinctAndAllThreeDeviceSwitchesAreIndependent() {
        val f = Fixture()
        f.save(DeviceNotificationPreferences(soundEnabled = true, volumePercent = 35))
        f.controller.event(DeviceNotificationEvent.Group(group(1, item(1).copy(silent = true)), emptyList()))
        f.controller.event(DeviceNotificationEvent.Group(group(1, item(2)).copy(soundId = 0), emptyList()))
        f.controller.event(DeviceNotificationEvent.Group(group(1, item(3)).copy(soundId = 58), emptyList()))
        assertEquals(DeviceNotificationError.CUSTOM_SOUND_UNAVAILABLE, f.state.error)
        assertTrue(f.signals.plays.isEmpty())
        assertTrue(f.state.cards.isEmpty())
        assertEquals(0, f.signals.flashes)
        f.controller.event(DeviceNotificationEvent.Group(group(1, item(4)), emptyList()))
        assertEquals(listOf(35), f.signals.plays)
    }

    @Test fun removalsAndRetirementInvalidateLateClicksWithoutNativeDismissOrReadSideEffects() {
        val f = Fixture()
        f.save(DeviceNotificationPreferences(desktopEnabled = true))
        f.controller.event(DeviceNotificationEvent.Group(group(1, item(1)), emptyList()))
        val stale = f.state.cards.single().identity
        f.controller.event(DeviceNotificationEvent.Group(group(1, item(2)), listOf(1)))
        f.controller.activate(stale)
        assertTrue(f.navigations.isEmpty())
        assertTrue(f.native.dismissals.isEmpty())
        val live = f.state.cards.single().identity
        f.controller.activate(live.copy(generation = 999))
        f.controller.expire(live)
        f.controller.activate(live)
        assertTrue(f.navigations.isEmpty())
        assertTrue(f.native.dismissals.isEmpty())
        f.controller.close()
        f.controller.event(DeviceNotificationEvent.Group(group(1, item(3)), emptyList()))
        f.controller.activate(live)
        assertFalse(f.state.enabled)
        assertTrue(f.state.cards.isEmpty())
        assertEquals(1, f.signals.closed)
        assertNull(f.native.listener)
    }

    @Test fun clickingRevalidatesLockAndPushOnlyMessagesNavigateOnlyToActualChat() {
        val f = Fixture()
        f.save(DeviceNotificationPreferences(desktopEnabled = true))
        f.controller.event(DeviceNotificationEvent.Group(group(1, item(1)), emptyList()))
        val id = f.state.cards.single().identity
        f.controller.setContext(false, null, true)
        assertEquals("nngGram", f.state.cards.single().title)
        assertEquals("你收到了一条新消息", f.state.cards.single().text)
        f.controller.activate(id)
        assertEquals(1, f.lockFocus)
        assertTrue(f.navigations.isEmpty())
        f.controller.setContext(false, null, false)
        f.controller.activate(id)
        f.flush()
        assertEquals(listOf<Pair<Long, Long?>>(10L to 101L), f.navigations)
        f.controller.event(DeviceNotificationEvent.Group(group(1, item(2).copy(canOpenMessage = false)), emptyList()))
        f.controller.activate(f.state.cards.single().identity)
        f.flush()
        assertEquals<Pair<Long, Long?>>(10L to null, f.navigations.last())
    }

    @Test fun onlyUserDismissSubmitsRemovalAndFailureRestoresTheCurrentCard() {
        val f = Fixture()
        f.save(DeviceNotificationPreferences(desktopEnabled = true))
        f.controller.event(DeviceNotificationEvent.Group(group(1, item(1)), emptyList()))
        val id = f.state.cards.single().identity
        f.controller.dismiss(id)
        f.controller.dismiss(id)
        assertEquals(listOf(1 to 1), f.native.dismissals)
        assertTrue(f.state.cards.isEmpty())
        f.native.dismissResult!!(false)
        f.flush()
        assertEquals(id, f.state.cards.single().identity)
        assertEquals(DeviceNotificationError.NATIVE_FAILED, f.state.error)
    }

    @Test fun failedSaveDoesNotChangeConsumersAndOldNativeConfigCannotOverrideTheNewerOne() {
        val f = Fixture()
        f.store.allowSave = false
        f.save(DeviceNotificationPreferences(desktopEnabled = true))
        assertFalse(f.state.preferences.desktopEnabled)
        assertEquals(DeviceNotificationError.SAVE_FAILED, f.state.error)
        f.store.allowSave = true
        f.native.autoComplete = false
        f.save(DeviceNotificationPreferences(desktopEnabled = true))
        val old = f.native.results.last()
        f.save(DeviceNotificationPreferences())
        old(true)
        f.native.results.last()(true)
        f.flush()
        assertFalse(f.state.nativeGroupsEnabled)
    }

    @Test fun nativeCountersDoNotDependOnLoadedTimelineAndFolderModeIsIndependent() {
        val f = Fixture()
        f.controller.event(DeviceNotificationEvent.Messages(ChatListRef(4), 80, 20))
        f.controller.event(DeviceNotificationEvent.Chats(ChatListRef(4), 10, 3, 2, 1))
        assertEquals(82, f.state.listBadge(ChatListRef(4)))
        assertEquals(10, f.state.folderBadge(ChatListRef(4)))
        f.save(DeviceNotificationPreferences(unreadMode = UnreadCounterMode.CHATS, includeMuted = false, includeMutedFolders = true))
        assertEquals(3, f.state.listBadge(ChatListRef(4)))
        assertEquals(10, f.state.folderBadge(ChatListRef(4)))
    }

    @Test fun versionedPreferencesRoundTripAndCorruptValuesUseBoundedSafeDefaults() {
        val directory = Files.createTempDirectory("notification-preferences-")
        val path = directory.resolve("prefs.properties")
        try {
            val store = FileDeviceNotificationPreferencesStore(path)
            val value = DeviceNotificationPreferences(true, true, true, 22, false, false, 5,
                DeviceNotificationCorner.TOP_LEFT, UnreadCounterMode.CHATS, false, false)
            assertTrue(store.save(value))
            assertEquals(value, store.load())
            assertFalse(store.save(value.copy(volumePercent = 101)))
            assertEquals(value, store.load())
            Files.writeString(path, "volume=999\nmaximum-visible=500\ncorner=arbitrary\ndesktop=maybe\n")
            assertEquals(DeviceNotificationPreferences(), store.load())
        } finally { Files.deleteIfExists(path); Files.deleteIfExists(directory) }
    }

    private class Fixture(windows: WindowsHost? = null, initial: DeviceNotificationPreferences = DeviceNotificationPreferences(),
        externalLocked: () -> Boolean = { false },
        externalLockRevision: () -> Long = { 0L },
    ) {
        val native = Native()
        val store = Store(initial)
        val signals = Signals()
        val work = ArrayDeque<() -> Unit>()
        val navigations = mutableListOf<Pair<Long, Long?>>()
        var state = DeviceNotificationsSnapshot()
        var lockFocus = 0
        val controller = DeviceNotificationsController(7, native, store, signals, { work.addLast(it) }, { state = it },
            { chat, message -> navigations += chat to message }, { lockFocus++ }, windowsHost = windows,
            externallyLocked = externalLocked, externalLockRevision = externalLockRevision)
        init { controller.start(); flush() }
        fun flush() { while (work.isNotEmpty()) work.removeFirst()() }
        fun save(value: DeviceNotificationPreferences) { controller.save(7, value); flush() }
    }
    private class Native : TdDeviceNotificationSession {
        var listener: ((DeviceNotificationEvent) -> Unit)? = null
        val enabled = mutableListOf<Boolean>()
        val results = mutableListOf<(Boolean) -> Unit>()
        var autoComplete = true
        val dismissals = mutableListOf<Pair<Int, Int>>()
        var dismissResult: ((Boolean) -> Unit)? = null
        var resolveAutomatically = true
        val resolveResults = mutableListOf<(Boolean) -> Unit>()
        override fun listen(listener: ((DeviceNotificationEvent) -> Unit)?) { this.listener = listener }
        override fun enable(enabled: Boolean, result: (Boolean) -> Unit) {
            this.enabled += enabled; results += result; if (autoComplete) result(true)
        }
        override fun dismiss(groupId: Int, notificationId: Int, result: (Boolean) -> Unit) {
            dismissals += groupId to notificationId; dismissResult = result
        }
        override fun resolveTarget(identity: DeviceNotificationIdentity, chatId: Long, messageId: Long, openMessage: Boolean,
            result: (Boolean) -> Unit) { resolveResults += result; if (resolveAutomatically) result(true) }
    }
    private class Store(private val initial: DeviceNotificationPreferences) : DeviceNotificationPreferencesStore {
        var allowSave = true
        var attempts = 0
        val saved = mutableListOf<DeviceNotificationPreferences>()
        override fun load() = initial
        override fun save(value: DeviceNotificationPreferences): Boolean { attempts++; if (allowSave) saved += value; return allowSave }
    }
    private class WindowsHost(private var state: WindowsDeviceNotificationState = WindowsDeviceNotificationState(WindowsDeviceNotificationStatus.REGISTERED, true)) : DeviceWindowsNotificationHost {
        var events: (WindowsToastHostEvent) -> Unit = {}
        var changed: (WindowsDeviceNotificationState) -> Unit = {}
        val results = mutableListOf<Pair<Boolean, (Boolean) -> Unit>>()
        val projections = mutableListOf<List<DeviceNotificationCard>>()
        val retired = mutableListOf<Long>()
        var commits = 0
        var cancels = 0
        var commitResult = true
        var autoCommit = true
        var uninstallResult = true
        val commitResults = mutableListOf<(Boolean) -> Unit>()
        override fun bind(generation: Long, events: (WindowsToastHostEvent) -> Unit, changed: (WindowsDeviceNotificationState) -> Unit) {
            this.events = events; this.changed = changed; changed(state)
        }
        override fun select(generation: Long, enabled: Boolean, result: (Boolean) -> Unit) {
            results += enabled to result
            changed(state.copy(status = WindowsDeviceNotificationStatus.ENABLING))
        }
        fun complete(index: Int, success: Boolean) {
            val (enabled, result) = results[index]
            if (index == results.lastIndex) {
                state = state.copy(status = if (success && enabled) WindowsDeviceNotificationStatus.ENABLED else WindowsDeviceNotificationStatus.REGISTERED)
                changed(state)
            }
            result(success)
        }
        override fun uninstall(generation: Long, result: (Boolean) -> Unit) { result(uninstallResult) }
        override fun synchronize(generation: Long, cards: List<DeviceNotificationCard>, restored: Boolean) { projections += cards }
        override fun policy(generation: Long, result: (WindowsNotificationPolicy) -> Unit) { result(WindowsNotificationPolicy(false, 5)) }
        override fun retire(generation: Long) { retired += generation }
        override fun commitSelection(generation: Long) { commits++ }
        override fun commitSelection(generation: Long, result: (Boolean) -> Unit) {
            commits++
            commitResults += result
            if (autoCommit) result(commitResult)
        }
        override fun cancelSelection(generation: Long) { cancels++ }
    }
    private class Signals : DeviceNotificationSignals {
        private var failure: () -> Unit = {}
        val plays = mutableListOf<Int>()
        var flashes = 0
        var closed = 0
        override fun play(volumePercent: Int) { plays += volumePercent }
        override fun flash() { flashes++ }
        override fun stop() {}
        override fun setFailureListener(listener: (() -> Unit)?) { failure = listener ?: {} }
        fun failed() { failure() }
        override fun close() { closed++ }
    }
    private companion object {
        fun item(id: Int) = DeviceNotificationItem(id, id, 100L + id, "Native chat", "private text", false, true, true)
        fun group(id: Int, item: DeviceNotificationItem, total: Int = 1) = DeviceNotificationGroup(id, 10, total, 99, -1, listOf(item))
    }
}
