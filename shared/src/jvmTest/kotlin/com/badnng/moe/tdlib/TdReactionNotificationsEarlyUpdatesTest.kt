package com.badnng.moe.tdlib

import org.drinkless.tdlib.TdApi
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TdReactionNotificationsEarlyUpdatesTest {
    @Test
    fun earlyUpdatesKeepOnlyLatestFullSettingsValue() {
        val collector = TdReactionNotificationsEarlyUpdates()
        collector.accept(settingsUpdate(1, TdApi.ReactionNotificationSourceNone(),
            TdApi.ReactionNotificationSourceContacts(), TdApi.ReactionNotificationSourceAll(), false))
        collector.accept(settingsUpdate(2, TdApi.ReactionNotificationSourceAll(),
            TdApi.ReactionNotificationSourceNone(), TdApi.ReactionNotificationSourceContacts(), true))

        val received = mutableListOf<TdApi.Object>()
        collector.bind(received::add)

        assertEquals(1, received.size)
        assertSettingsUpdate(received.single(), 2, TdApi.ReactionNotificationSourceAll.CONSTRUCTOR,
            TdApi.ReactionNotificationSourceNone.CONSTRUCTOR,
            TdApi.ReactionNotificationSourceContacts.CONSTRUCTOR, true)
    }

    @Test
    fun cachedSettingsCopyEveryScalarAndRecreateNativeObjects() {
        val collector = TdReactionNotificationsEarlyUpdates()
        val sourceUpdate = settingsUpdate(17, TdApi.ReactionNotificationSourceContacts(),
            TdApi.ReactionNotificationSourceAll(), TdApi.ReactionNotificationSourceNone(), true)
        val sourceSettings = assertNotNull(sourceUpdate.notificationSettings)
        collector.accept(sourceUpdate)

        sourceSettings.messageReactionSource = TdApi.ReactionNotificationSourceAll()
        sourceSettings.storyReactionSource = TdApi.ReactionNotificationSourceNone()
        sourceSettings.pollVoteSource = TdApi.ReactionNotificationSourceContacts()
        sourceSettings.soundId = 91
        sourceSettings.showPreview = false

        val received = mutableListOf<TdApi.Object>()
        collector.bind(received::add)

        val copiedUpdate = assertIs<TdApi.UpdateReactionNotificationSettings>(received.single())
        val copiedSettings = assertNotNull(copiedUpdate.notificationSettings)
        assertNotSame(sourceUpdate, copiedUpdate)
        assertNotSame(sourceSettings, copiedSettings)
        assertNotSame(sourceSettings.messageReactionSource, copiedSettings.messageReactionSource)
        assertNotSame(sourceSettings.storyReactionSource, copiedSettings.storyReactionSource)
        assertNotSame(sourceSettings.pollVoteSource, copiedSettings.pollVoteSource)
        assertEquals(TdApi.ReactionNotificationSourceContacts.CONSTRUCTOR,
            assertNotNull(copiedSettings.messageReactionSource).constructor)
        assertEquals(TdApi.ReactionNotificationSourceAll.CONSTRUCTOR,
            assertNotNull(copiedSettings.storyReactionSource).constructor)
        assertEquals(TdApi.ReactionNotificationSourceNone.CONSTRUCTOR,
            assertNotNull(copiedSettings.pollVoteSource).constructor)
        assertEquals(17L, copiedSettings.soundId)
        assertTrue(copiedSettings.showPreview)
    }

    @Test
    fun nullSettingsAndNullOrUnknownSourcesReplayMalformedUpdates() {
        val unknown = object : TdApi.ReactionNotificationSource() {
            override fun getConstructor(): Int = Int.MAX_VALUE
        }
        val malformed = listOf(
            TdApi.UpdateReactionNotificationSettings(),
            settingsUpdate(1, null, TdApi.ReactionNotificationSourceContacts(), TdApi.ReactionNotificationSourceAll()),
            settingsUpdate(2, TdApi.ReactionNotificationSourceNone(), null, TdApi.ReactionNotificationSourceAll()),
            settingsUpdate(3, TdApi.ReactionNotificationSourceNone(), TdApi.ReactionNotificationSourceAll(), null),
            settingsUpdate(4, unknown, TdApi.ReactionNotificationSourceAll(), TdApi.ReactionNotificationSourceAll()),
            settingsUpdate(5, TdApi.ReactionNotificationSourceAll(), unknown, TdApi.ReactionNotificationSourceAll()),
            settingsUpdate(6, TdApi.ReactionNotificationSourceAll(), TdApi.ReactionNotificationSourceAll(), unknown),
        )

        for (update in malformed) {
            val collector = TdReactionNotificationsEarlyUpdates()
            collector.accept(update)
            val received = mutableListOf<TdApi.Object>()
            collector.bind(received::add)

            assertEquals(1, received.size)
            assertNull(assertIs<TdApi.UpdateReactionNotificationSettings>(received.single()).notificationSettings)
        }
    }

    @Test
    fun concurrentBindAndAcceptDeliverTheEarlyValueExactlyOnce() {
        val collector = TdReactionNotificationsEarlyUpdates()
        val start = CountDownLatch(1)
        val backgroundFailure = AtomicReference<Throwable?>()
        val received = Collections.synchronizedList(mutableListOf<Long>())
        val binder = Thread {
            try {
                assertTrue(start.await(5, TimeUnit.SECONDS))
                collector.bind { received += soundId(it) }
            } catch (failure: Throwable) {
                backgroundFailure.set(failure)
            }
        }
        val updater = Thread {
            try {
                assertTrue(start.await(5, TimeUnit.SECONDS))
                collector.accept(settingsUpdate(29))
            } catch (failure: Throwable) {
                backgroundFailure.set(failure)
            }
        }
        binder.start()
        updater.start()
        start.countDown()
        binder.join(5_000)
        updater.join(5_000)

        assertFalse(binder.isAlive)
        assertFalse(updater.isAlive)
        assertNull(backgroundFailure.get())
        assertEquals(listOf(29L), received.toList())
    }

    @Test
    fun reentrantAndConcurrentLiveUpdatesDrainSeriallyWithOnlyLatestPendingValue() {
        val collector = TdReactionNotificationsEarlyUpdates()
        val callbackEntered = CountDownLatch(1)
        val concurrentUpdatesFinished = CountDownLatch(1)
        val backgroundFailure = AtomicReference<Throwable?>()
        val received = Collections.synchronizedList(mutableListOf<Long>())
        var callbackDepth = 0
        var maximumCallbackDepth = 0
        var duplicateTargetCalls = 0

        collector.bind { update ->
            callbackDepth++
            maximumCallbackDepth = maxOf(maximumCallbackDepth, callbackDepth)
            try {
                val id = soundId(update)
                received += id
                if (id == 1L) {
                    collector.accept(settingsUpdate(2))
                    collector.bind { duplicateTargetCalls++ }
                    callbackEntered.countDown()
                    if (!concurrentUpdatesFinished.await(5, TimeUnit.SECONDS)) {
                        backgroundFailure.set(AssertionError("concurrent accepts did not finish"))
                    }
                }
            } finally {
                callbackDepth--
            }
        }

        val concurrent = Thread {
            try {
                if (!callbackEntered.await(5, TimeUnit.SECONDS)) {
                    backgroundFailure.set(AssertionError("first callback did not start"))
                } else {
                    collector.accept(settingsUpdate(3))
                    collector.accept(settingsUpdate(4))
                }
            } catch (failure: Throwable) {
                backgroundFailure.set(failure)
            } finally {
                concurrentUpdatesFinished.countDown()
            }
        }
        concurrent.start()
        collector.accept(settingsUpdate(1))
        concurrent.join(5_000)
        collector.bind { duplicateTargetCalls++ }

        assertFalse(concurrent.isAlive)
        assertNull(backgroundFailure.get())
        assertEquals(listOf(1L, 4L), received.toList())
        assertEquals(1, maximumCallbackDepth)
        assertEquals(0, duplicateTargetCalls)
    }

    @Test
    fun retireClearsEarlyAndPendingUpdatesAndRejectsLateAcceptsAndBinds() {
        val early = TdReactionNotificationsEarlyUpdates()
        early.accept(settingsUpdate(1))
        early.retire()
        val earlyReceived = mutableListOf<TdApi.Object>()
        early.bind(earlyReceived::add)
        early.accept(settingsUpdate(2))
        early.bind { error("retired collector must not replace its target") }
        assertTrue(earlyReceived.isEmpty())

        val live = TdReactionNotificationsEarlyUpdates()
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val received = Collections.synchronizedList(mutableListOf<Long>())
        live.bind {
            received += soundId(it)
            callbackEntered.countDown()
            releaseCallback.await(5, TimeUnit.SECONDS)
        }
        val firstAccept = Thread { live.accept(settingsUpdate(10)) }
        firstAccept.start()
        assertTrue(callbackEntered.await(5, TimeUnit.SECONDS))
        live.accept(settingsUpdate(11))
        live.retire()
        releaseCallback.countDown()
        firstAccept.join(5_000)
        live.accept(settingsUpdate(12))
        live.bind { error("retired collector must not bind again") }

        assertFalse(firstAccept.isAlive)
        assertEquals(listOf(10L), received.toList())
    }

    @Test
    fun receiverExceptionDoesNotWedgeLaterLiveUpdates() {
        val collector = TdReactionNotificationsEarlyUpdates()
        val received = mutableListOf<Long>()
        collector.bind { update ->
            val id = soundId(update)
            if (id == 1L) throw IllegalStateException("receiver failure")
            received += id
        }

        collector.accept(settingsUpdate(1))
        collector.accept(settingsUpdate(2))

        assertEquals(listOf(2L), received)
    }

    @Test
    fun nonReactionObjectsAreIgnoredBeforeAndAfterBinding() {
        val collector = TdReactionNotificationsEarlyUpdates()
        collector.accept(TdApi.Ok())
        val received = mutableListOf<TdApi.Object>()
        collector.bind(received::add)
        collector.accept(TdApi.Ok())
        collector.accept(settingsUpdate(8))
        collector.accept(TdApi.Ok())

        assertEquals(1, received.size)
        assertSettingsUpdate(received.single(), 8, TdApi.ReactionNotificationSourceNone.CONSTRUCTOR,
            TdApi.ReactionNotificationSourceContacts.CONSTRUCTOR,
            TdApi.ReactionNotificationSourceAll.CONSTRUCTOR, true)
    }

    private fun settingsUpdate(
        soundId: Long,
        message: TdApi.ReactionNotificationSource? = TdApi.ReactionNotificationSourceNone(),
        story: TdApi.ReactionNotificationSource? = TdApi.ReactionNotificationSourceContacts(),
        poll: TdApi.ReactionNotificationSource? = TdApi.ReactionNotificationSourceAll(),
        showPreview: Boolean = true,
    ) = TdApi.UpdateReactionNotificationSettings(
        TdApi.ReactionNotificationSettings(message, story, poll, soundId, showPreview))

    private fun soundId(update: TdApi.Object): Long = assertNotNull(
        assertIs<TdApi.UpdateReactionNotificationSettings>(update).notificationSettings).soundId

    private fun assertSettingsUpdate(
        update: TdApi.Object,
        soundId: Long,
        messageConstructor: Int,
        storyConstructor: Int,
        pollConstructor: Int,
        showPreview: Boolean,
    ) {
        val settings = assertNotNull(assertIs<TdApi.UpdateReactionNotificationSettings>(update).notificationSettings)
        assertEquals(messageConstructor, assertNotNull(settings.messageReactionSource).constructor)
        assertEquals(storyConstructor, assertNotNull(settings.storyReactionSource).constructor)
        assertEquals(pollConstructor, assertNotNull(settings.pollVoteSource).constructor)
        assertEquals(soundId, settings.soundId)
        assertEquals(showPreview, settings.showPreview)
    }
}
