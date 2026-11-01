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

class TdProfileColorsEarlyUpdatesTest {
    @Test
    fun earlyUpdatesKeepOnlyEachTypesLatestValueAndDrainByLatestArrival() {
        val collector = TdProfileColorsEarlyUpdates()
        collector.accept(accentUpdate(1))
        collector.accept(profileUpdate(8))
        collector.accept(TdApi.Ok())
        collector.accept(accentUpdate(2))

        val received = mutableListOf<TdApi.Object>()
        collector.bind(received::add)

        assertEquals(listOf("profile-8", "accent-2"), received.map(::label))
    }

    @Test
    fun cachedUpdatesDeepCopyNativeObjectsAndEveryNestedArray() {
        val collector = TdProfileColorsEarlyUpdates()
        val sourceAccent = accentUpdate(3, intArrayOf(3, 1))
        val sourceProfile = profileUpdate(9, intArrayOf(9, 2))
        collector.accept(sourceAccent)
        collector.accept(sourceProfile)

        sourceAccent.colors[0].builtInAccentColorId = 77
        sourceAccent.colors[0].lightThemeColors[0] = 0x010203
        sourceAccent.colors[0].darkThemeColors[0] = 0x040506
        sourceAccent.availableAccentColorIds[0] = 77
        sourceProfile.colors[0].lightThemeColors.paletteColors[0] = 0x010203
        sourceProfile.colors[0].lightThemeColors.backgroundColors[0] = 0x040506
        sourceProfile.colors[0].lightThemeColors.storyColors[0] = 0x070809
        sourceProfile.colors[0].darkThemeColors.paletteColors[0] = 0x0a0b0c
        sourceProfile.colors[0].darkThemeColors.backgroundColors[0] = 0x0d0e0f
        sourceProfile.colors[0].darkThemeColors.storyColors[1] = 0x101112
        sourceProfile.availableAccentColorIds[0] = 77

        val received = mutableListOf<TdApi.Object>()
        collector.bind(received::add)

        val copiedAccent = assertIs<TdApi.UpdateAccentColors>(received[0])
        val copiedAccentColor = assertNotNull(copiedAccent.colors?.single())
        assertNotSame(sourceAccent, copiedAccent)
        assertNotSame(sourceAccent.colors, copiedAccent.colors)
        assertNotSame(sourceAccent.colors[0], copiedAccentColor)
        assertNotSame(sourceAccent.colors[0].lightThemeColors, copiedAccentColor.lightThemeColors)
        assertNotSame(sourceAccent.availableAccentColorIds, copiedAccent.availableAccentColorIds)
        assertEquals(103, copiedAccentColor.builtInAccentColorId)
        assertEquals(0x112233, copiedAccentColor.lightThemeColors[0])
        assertEquals(0x334455, copiedAccentColor.darkThemeColors[0])
        assertEquals(3, copiedAccent.availableAccentColorIds[0])

        val copiedProfile = assertIs<TdApi.UpdateProfileAccentColors>(received[1])
        val copiedProfileColor = assertNotNull(copiedProfile.colors?.single())
        assertNotSame(sourceProfile, copiedProfile)
        assertNotSame(sourceProfile.colors, copiedProfile.colors)
        assertNotSame(sourceProfile.colors[0], copiedProfileColor)
        assertNotSame(sourceProfile.colors[0].lightThemeColors, copiedProfileColor.lightThemeColors)
        assertNotSame(sourceProfile.colors[0].lightThemeColors.paletteColors,
            copiedProfileColor.lightThemeColors.paletteColors)
        assertNotSame(sourceProfile.availableAccentColorIds, copiedProfile.availableAccentColorIds)
        assertEquals(0x223344, copiedProfileColor.lightThemeColors.paletteColors[0])
        assertEquals(0x445566, copiedProfileColor.lightThemeColors.backgroundColors[0])
        assertEquals(0x778899, copiedProfileColor.lightThemeColors.storyColors[0])
        assertEquals(0xaabbcc, copiedProfileColor.darkThemeColors.paletteColors[0])
        assertEquals(0xddeeff, copiedProfileColor.darkThemeColors.backgroundColors[0])
        assertEquals(0x234567, copiedProfileColor.darkThemeColors.storyColors[1])
        assertEquals(9, copiedProfile.availableAccentColorIds[0])
    }

    @Test
    fun liveDrainIsSerialAcrossReentrantAndConcurrentUpdates() {
        val collector = TdProfileColorsEarlyUpdates()
        val callbackEntered = CountDownLatch(1)
        val concurrentUpdatesFinished = CountDownLatch(1)
        val backgroundFailure = AtomicReference<Throwable?>()
        val received = Collections.synchronizedList(mutableListOf<String>())
        var callbackDepth = 0
        var duplicateTargetCalls = 0

        collector.bind { update ->
            callbackDepth++
            try {
                assertEquals(1, callbackDepth, "callbacks must not recurse")
                received += label(update)
                if (update is TdApi.UpdateAccentColors && update.colors?.firstOrNull()?.id == 1) {
                    collector.accept(profileUpdate(11))
                    collector.bind { duplicateTargetCalls++ }
                    callbackEntered.countDown()
                    assertTrue(concurrentUpdatesFinished.await(5, TimeUnit.SECONDS))
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
                    collector.accept(profileUpdate(22))
                    collector.accept(accentUpdate(2))
                }
            } catch (failure: Throwable) {
                backgroundFailure.set(failure)
            } finally {
                concurrentUpdatesFinished.countDown()
            }
        }
        concurrent.start()
        collector.accept(accentUpdate(1))
        concurrent.join(5_000)

        assertFalse(concurrent.isAlive)
        assertNull(backgroundFailure.get())
        assertEquals(listOf("accent-1", "profile-22", "accent-2"), received.toList())
        assertEquals(0, duplicateTargetCalls)
    }

    @Test
    fun oversizedDirectoryArraysForwardAnInvalidMarkerInsteadOfTruncating() {
        val collector = TdProfileColorsEarlyUpdates()
        val tooManyColors = Array(TdProfileColorsEarlyUpdates.MAX_DIRECTORY_ITEMS + 1) {
            TdApi.AccentColor(it, 0, intArrayOf(1), intArrayOf(2), 0)
        }
        val tooManyAvailableIds = IntArray(TdProfileColorsEarlyUpdates.MAX_DIRECTORY_ITEMS + 1) { it }
        collector.accept(TdApi.UpdateAccentColors(tooManyColors, intArrayOf(1)))
        collector.accept(TdApi.UpdateProfileAccentColors(arrayOf(profileColor(4)), tooManyAvailableIds))

        val received = mutableListOf<TdApi.Object>()
        collector.bind(received::add)

        assertEquals(2, received.size)
        val invalidAccent = assertIs<TdApi.UpdateAccentColors>(received[0])
        assertNull(invalidAccent.colors)
        assertNull(invalidAccent.availableAccentColorIds)
        val invalidProfile = assertIs<TdApi.UpdateProfileAccentColors>(received[1])
        assertNull(invalidProfile.colors)
        assertNull(invalidProfile.availableAccentColorIds)
        assertEquals(TdProfileColorsEarlyUpdates.MAX_DIRECTORY_ITEMS + 1, tooManyColors.size)
        assertEquals(TdProfileColorsEarlyUpdates.MAX_DIRECTORY_ITEMS + 1, tooManyAvailableIds.size)
    }

    @Test
    fun retireDropsCachedUpdatesAndDuplicateBindNeverReplacesTheReceiver() {
        val retired = TdProfileColorsEarlyUpdates()
        var retiredCalls = 0
        retired.accept(accentUpdate(1))
        retired.retire()
        retired.bind { retiredCalls++ }
        retired.accept(profileUpdate(2))
        retired.bind { retiredCalls++ }
        assertEquals(0, retiredCalls)

        val bound = TdProfileColorsEarlyUpdates()
        var firstTargetCalls = 0
        var secondTargetCalls = 0
        bound.bind { firstTargetCalls++ }
        bound.bind { secondTargetCalls++ }
        bound.accept(accentUpdate(3))
        assertEquals(1, firstTargetCalls)
        assertEquals(0, secondTargetCalls)
        bound.retire()
        bound.accept(accentUpdate(4))
        assertEquals(1, firstTargetCalls)
    }

    private fun label(update: TdApi.Object): String = when (update) {
        is TdApi.UpdateAccentColors -> "accent-${update.colors?.firstOrNull()?.id}"
        is TdApi.UpdateProfileAccentColors -> "profile-${update.colors?.firstOrNull()?.id}"
        else -> error("unexpected update type: ${update.javaClass.simpleName}")
    }

    private fun accentUpdate(id: Int, availableIds: IntArray = intArrayOf(id)) =
        TdApi.UpdateAccentColors(arrayOf(TdApi.AccentColor(id, 100 + id, intArrayOf(0x112233, 0x223344),
            intArrayOf(0x334455), 2)), availableIds)

    private fun profileUpdate(id: Int, availableIds: IntArray = intArrayOf(id)) =
        TdApi.UpdateProfileAccentColors(arrayOf(profileColor(id)), availableIds)

    private fun profileColor(id: Int) = TdApi.ProfileAccentColor(id,
        TdApi.ProfileAccentColors(intArrayOf(0x223344, 0x334455), intArrayOf(0x445566), intArrayOf(0x778899, 0x8899aa)),
        TdApi.ProfileAccentColors(intArrayOf(0xaabbcc), intArrayOf(0xddeeff, 0xeef0f1), intArrayOf(0x123456, 0x234567)),
        3, 4)
}
