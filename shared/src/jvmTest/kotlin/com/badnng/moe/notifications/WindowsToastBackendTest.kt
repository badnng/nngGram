package com.badnng.moe.notifications

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class WindowsToastBackendTest {
    @Test fun quietRestoredPinnedHistoryCannotPopupBesideATrueNewArrival() {
        val f = Fixture()
        try {
            f.enable()
            val restoredPin = card(1).copy(text = "Restored synthetic pin", quiet = true)
            val arrival = card(2).copy(text = "New synthetic arrival")
            f.backend.synchronize(7, listOf(restoredPin, arrival), false)
            f.flush()
            assertEquals(2, f.driver.shown.size)
            assertTrue(f.driver.shown.single { it.text == restoredPin.text }.restored)
            assertFalse(f.driver.shown.single { it.text == arrival.text }.restored)
            f.backend.synchronize(7, listOf(restoredPin, arrival), false)
            f.flush()
            assertEquals(2, f.driver.shown.size, "A repeated projection does not replay either native identity.")
        } finally { f.backend.close() }
    }

    @Test fun coalescingKeepsPerCardQuietHistoryAndPendingArrivalPopupIntentSeparate() {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val f = Fixture()
        try {
            f.enable()
            val restoredPin = card(1).copy(text = "Quiet synthetic history", quiet = true)
            val arrival = card(2).copy(text = "Pending synthetic arrival")
            f.driver.beforePolicy = { entered.countDown(); assertTrue(resume.await(5, TimeUnit.SECONDS)) }
            f.backend.policy {}
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            f.backend.synchronize(7, listOf(restoredPin), true)
            f.backend.synchronize(7, listOf(restoredPin, arrival), false)
            f.backend.synchronize(7, listOf(restoredPin, arrival), true)
            f.driver.beforePolicy = {}
            resume.countDown()
            f.flush(); f.flush()
            assertEquals(2, f.driver.shown.size)
            assertTrue(f.driver.shown.single { it.text == restoredPin.text }.restored)
            assertFalse(f.driver.shown.single { it.text == arrival.text }.restored,
                "An unrelated quiet restore must not silence an already pending real arrival.")
        } finally { resume.countDown(); f.backend.close() }
    }

    @Test fun originalNativeIdsAreProjectedOnceAndReplacedSilentlyWithNewNonces() {
        val f = Fixture()
        try {
            f.enable()
            val card = card(1)
            f.backend.synchronize(7, listOf(card), false); f.flush()
            assertEquals(1, f.driver.shown.size)
            val first = f.driver.shown.single()
            assertFalse(first.restored)
            f.backend.synchronize(7, listOf(card), false); f.flush()
            assertEquals(1, f.driver.shown.size)
            f.backend.synchronize(7, listOf(card.copy(text = "native edit")), false); f.flush()
            assertEquals(listOf(first.nonce), f.driver.removed)
            assertTrue(f.driver.shown.last().restored)
            assertNotEquals(first.nonce, f.driver.shown.last().nonce)
            f.driver.events(WindowsToastEvent.Activated(first.nonce)); f.flush()
            assertTrue(f.events.isEmpty(), "An old OS callback cannot activate replaced artwork.")
            f.driver.events(WindowsToastEvent.Activated(f.driver.shown.last().nonce)); f.flush()
            assertEquals<List<WindowsToastHostEvent>>(listOf(WindowsToastHostEvent.Activate(card.identity)), f.events.toList())
        } finally { f.backend.close() }
    }

    @Test fun restoredGroupsAreSilentAndOwnerRetirementRejectsQueuedAndLateNativeCallbacks() {
        val f = Fixture()
        try {
            f.enable()
            f.backend.synchronize(7, listOf(card(1)), true); f.flush()
            assertTrue(f.driver.shown.single().restored)
            val old = f.driver.shown.single().nonce
            f.backend.retire(7)
            f.driver.events(WindowsToastEvent.Activated(old))
            f.flush()
            assertTrue(f.events.isEmpty())
            assertEquals(listOf(old), f.driver.removed)
            f.backend.synchronize(7, listOf(card(2)), false); f.flush()
            assertEquals(1, f.driver.shown.size)
        } finally { f.backend.close() }
    }

    @Test fun onlyActualUserDismissalProducesTheNativeDismissActionAndFailureIsReported() {
        val f = Fixture()
        try {
            f.enable()
            f.backend.synchronize(7, listOf(card(1), card(2)), false); f.flush()
            f.driver.events(WindowsToastEvent.Dismissed(f.driver.shown[0].nonce, false)); f.flush()
            assertTrue(f.events.isEmpty())
            f.driver.events(WindowsToastEvent.Dismissed(f.driver.shown[1].nonce, true)); f.flush()
            assertEquals<List<WindowsToastHostEvent>>(listOf(WindowsToastHostEvent.Dismiss(card(2).identity)), f.events.toList())
            f.backend.synchronize(7, listOf(card(3)), false); f.flush()
            f.driver.events(WindowsToastEvent.Failed(f.driver.shown.last().nonce)); f.flush()
            assertEquals(WindowsToastHostEvent.Failed(7), f.events.last())
        } finally { f.backend.close() }
    }

    @Test fun fourthArrivalAndPopupTimeoutKeepEarlierActionCenterIdentitiesUntilNativeRemoval() {
        val f = Fixture()
        try {
            f.enable()
            val all = (1..4).map(::card)
            f.backend.synchronize(7, all, false); f.flush()
            assertEquals(4, f.driver.shown.size)
            val oldest = f.driver.shown.first().nonce
            f.driver.events(WindowsToastEvent.Dismissed(oldest, false)); f.flush()
            assertTrue(f.driver.removed.isEmpty(), "Popup timeout cannot remove unread Action Center history.")
            f.backend.synchronize(7, all, false); f.flush()
            assertEquals(4, f.driver.shown.size)
            f.driver.events(WindowsToastEvent.Activated(oldest)); f.flush()
            assertEquals(WindowsToastHostEvent.Activate(card(1).identity), f.events.single())
            f.backend.synchronize(7, all.drop(1), false); f.flush()
            assertEquals(listOf(oldest), f.driver.removed)
        } finally { f.backend.close() }
    }

    @Test fun driverInitializationFailureIsNotAnEnabledBackendAndNeverShows() {
        val driver = Driver().apply { failInitialize = true }
        val f = Fixture(driver)
        try {
            assertFalse(f.enable(accepted = false))
            f.backend.synchronize(7, listOf(card(1)), false)
            assertTrue(driver.shown.isEmpty())
            assertEquals(1, driver.closes)
        } finally { f.backend.close() }
    }

    @Test fun retirementDuringFakeXmlPreparationStopsFinalShowAndTheRemainingNativeBatch() {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val driver = Driver().apply { beforeFinalShow = { entered.countDown(); assertTrue(resume.await(5, TimeUnit.SECONDS)) } }
        val f = Fixture(driver)
        try {
            f.enable()
            f.backend.synchronize(7, (1..4).map(::card), false)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            f.backend.retire(7)
            resume.countDown(); f.flush()
            assertEquals(1, driver.showAttempts, "No remaining item may enter preparation after retirement.")
            assertTrue(driver.shown.isEmpty(), "The captured lease must reject the final Show stage.")
            assertTrue(f.events.isEmpty())
        } finally { resume.countDown(); f.backend.close() }
    }

    @Test fun replacementOwnerCannotReviveAnOldShowLeaseEvenIfTheNumericGenerationReturns() {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val driver = Driver().apply { beforeFinalShow = { entered.countDown(); assertTrue(resume.await(5, TimeUnit.SECONDS)) } }
        val f = Fixture(driver)
        try {
            f.enable()
            f.backend.synchronize(7, listOf(card(1), card(2)), false)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            f.backend.setOwner(8)
            f.backend.setOwner(7)
            resume.countDown(); f.flush()
            assertTrue(driver.shown.isEmpty())
            assertEquals(1, driver.showAttempts)
            driver.beforeFinalShow = {}
            f.backend.synchronize(7, listOf(card(3)), true); f.flush()
            assertEquals(1, driver.shown.size, "Only a new operation may authorize the replacement owner.")
        } finally { resume.countDown(); f.backend.close() }
    }

    @Test fun sameGenerationNativeRemovalInvalidatesTheInFlightPayloadBeforeFinalShow() {
        val f = Fixture()
        try {
            f.enable()
            f.driver.beforeFinalShow = { f.backend.synchronize(7, emptyList(), true) }
            f.backend.synchronize(7, listOf(card(1)), false); f.flush()
            assertTrue(f.driver.shown.isEmpty(), "A queued removal must revoke the old final Show immediately.")
            assertTrue(f.events.isEmpty(), "Retirement is not an OS failure or native dismissal.")
        } finally { f.backend.close() }
    }

    @Test fun sameGenerationLockRedactionInvalidatesOldXmlAndShowsOnlyTheCurrentSafePayload() {
        val f = Fixture()
        try {
            f.enable()
            val redacted = card(1).copy(title = "nngGram", text = "你收到了一条新消息")
            f.driver.beforeFinalShow = {
                f.driver.beforeFinalShow = {}
                f.backend.synchronize(7, listOf(redacted), true)
            }
            f.backend.synchronize(7, listOf(card(1)), false); f.flush(); f.flush()
            assertEquals(1, f.driver.shown.size)
            assertEquals(redacted.text, f.driver.shown.single().text)
            assertTrue(f.driver.shown.single().restored)
            assertTrue(f.events.isEmpty())
        } finally { f.backend.close() }
    }

    @Test fun projectionBurstsCoalesceAndAFullBlockedQueueReportsOnlyOneFailureWithoutFeedback() {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val driver = Driver().apply { beforeFinalShow = { entered.countDown(); assertTrue(resume.await(5, TimeUnit.SECONDS)) } }
        val f = Fixture(driver)
        try {
            f.enable()
            f.backend.setListener { event ->
                f.events += event
                if (event is WindowsToastHostEvent.Failed) f.backend.synchronize(7, emptyList(), true)
            }
            f.backend.synchronize(7, listOf(card(1)), false)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            repeat(2_000) { revision -> f.backend.synchronize(7, listOf(card(1).copy(text = "synthetic $revision")), false) }
            val policies = CountDownLatch(256)
            repeat(256) { f.backend.policy { policies.countDown() } }
            resume.countDown()
            assertTrue(policies.await(5, TimeUnit.SECONDS))
            f.flush(); f.flush()
            assertEquals(1, driver.showAttempts, "A burst holds one pending latest projection rather than thousands of jobs.")
            assertTrue(driver.shown.isEmpty())
            assertEquals<List<WindowsToastHostEvent>>(listOf(WindowsToastHostEvent.Failed(7)), f.events.toList())
        } finally { resume.countDown(); f.backend.close() }
    }

    @Test fun coalescingAnUnchangedRestoreSnapshotPreservesThePendingArrivalPopupIntent() {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val f = Fixture()
        try {
            f.enable()
            f.driver.beforePolicy = { entered.countDown(); assertTrue(resume.await(5, TimeUnit.SECONDS)) }
            f.backend.policy {}
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            f.backend.synchronize(7, listOf(card(1)), false)
            f.backend.synchronize(7, listOf(card(1)), true)
            f.driver.beforePolicy = {}
            resume.countDown(); f.flush(); f.flush()
            assertFalse(f.driver.shown.single().restored, "A repeated state snapshot cannot silence a pending real arrival.")
        } finally { resume.countDown(); f.backend.close() }
    }

    @Test fun ownerRetirementStillRemovesActionCenterHistoryWhenItsCleanupOfferFindsAFullQueue() {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val removed = CountDownLatch(1)
        val f = Fixture()
        try {
            f.enable()
            f.backend.synchronize(7, listOf(card(1)), true); f.flush()
            val nonce = f.driver.shown.single().nonce
            f.driver.onRemove = { removed.countDown() }
            f.driver.beforePolicy = { entered.countDown(); assertTrue(resume.await(5, TimeUnit.SECONDS)) }
            f.backend.policy {}
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val policies = CountDownLatch(256)
            repeat(256) { f.backend.policy { policies.countDown() } }
            f.backend.retire(7) // The 128-slot queue is full; this ordinary cleanup offer cannot enqueue.
            f.driver.beforePolicy = {}
            resume.countDown()
            assertTrue(removed.await(5, TimeUnit.SECONDS), "A synchronous call returning must clear retired history before waiting again.")
            assertTrue(policies.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(nonce), f.driver.removed)
            assertTrue(f.events.isEmpty())
        } finally { resume.countDown(); f.backend.close() }
    }

    @Test fun retirementWakesAnOtherwiseIdleWorkerWithoutNeedingAnotherInputOrPolicyRequest() {
        val removed = CountDownLatch(1)
        val f = Fixture()
        try {
            f.enable()
            f.backend.synchronize(7, listOf(card(1)), true); f.flush()
            f.driver.onRemove = { removed.countDown() }
            f.backend.retire(7)
            assertTrue(removed.await(5, TimeUnit.SECONDS))
            assertEquals(1, f.driver.removed.size)
        } finally { f.backend.close() }
    }

    private class Fixture(val driver: Driver = Driver()) {
        val backend = WindowsToastBackend { driver }
        val events = CopyOnWriteArrayList<WindowsToastHostEvent>()
        init { backend.setOwner(7); backend.setListener { events += it } }
        fun enable(accepted: Boolean = true): Boolean {
            val done = CountDownLatch(1)
            var value = false
            backend.enable("owned.test", "3c46fce0-3bb0-4161-b5af-3f384ed2eeb7") { value = it; done.countDown() }
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals(accepted, value)
            return value
        }
        fun flush() {
            val done = CountDownLatch(1)
            backend.policy { done.countDown() }
            assertTrue(done.await(5, TimeUnit.SECONDS))
        }
    }
    private class Driver : WindowsToastDriver {
        var events: (WindowsToastEvent) -> Unit = {}
        val shown = CopyOnWriteArrayList<Shown>()
        val removed = CopyOnWriteArrayList<String>()
        var closes = 0
        var failInitialize = false
        var beforeFinalShow: () -> Unit = {}
        @Volatile var beforePolicy: () -> Unit = {}
        @Volatile var onRemove: () -> Unit = {}
        var showAttempts = 0
        override fun initialize(aumid: String, clsid: String, event: (WindowsToastEvent) -> Unit) {
            events = event
            if (failInitialize) error("Fake failure")
        }
        override fun show(nonce: String, tag: String, group: String, title: String, text: String, restored: Boolean) { shown += Shown(nonce, restored, text) }
        override fun show(nonce: String, tag: String, group: String, title: String, text: String, restored: Boolean, stillCurrent: () -> Boolean) {
            showAttempts++
            val lease = WindowsToastShowLease(stillCurrent)
            lease.check()
            beforeFinalShow() // A fake native XML/QI call pauses here; it never opens a real OS boundary.
            lease.check()
            show(nonce, tag, group, title, text, restored)
        }
        override fun remove(nonce: String) { removed += nonce; onRemove() }
        override fun policy(): WindowsNotificationPolicy { beforePolicy(); return WindowsNotificationPolicy(false, 5) }
        override fun close() { closes++ }
    }
    private data class Shown(val nonce: String, val restored: Boolean, val text: String)
    private companion object {
        fun card(id: Int) = DeviceNotificationCard(DeviceNotificationIdentity(7, 4, id), "Synthetic title", "Synthetic text", id)
    }
}
