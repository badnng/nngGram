package com.badnng.moe

import com.badnng.moe.settings.SettingsStorageCategory
import com.badnng.moe.settings.SettingsStorageDetails
import com.badnng.moe.settings.SettingsStorageFileType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SettingsStorageSelectionTest {
    private val photo = SettingsStorageFileType.PHOTO
    private val video = SettingsStorageFileType.VIDEO
    private val document = SettingsStorageFileType.DOCUMENT
    private val secret = SettingsStorageFileType.SECRET_THUMBNAIL

    @Test fun selectionTracksRealCategoriesAndOnlySuccessResetsIt() {
        val initial = details(SettingsStorageCategory(photo, 3, 3), SettingsStorageCategory(video, 2, 2),
            SettingsStorageCategory(secret, 1, 1), SettingsStorageCategory(document, 0, 0))
        val all = settingsStorageSelection(null, initial, 0)
        assertEquals(setOf(photo, video), all.selected)
        val one = all.toggle(video, false)
        assertEquals(setOf(photo), one.selected)
        assertEquals(one, one.toggle(photo, false), "The last remaining real type cannot be deselected.")
        assertEquals(one, one.toggle(secret, true), "Non-removable types cannot enter the choice.")
        assertEquals(one, settingsStorageSelection(one, initial, 0), "A failure or reread alone is not success.")

        val expanded = details(SettingsStorageCategory(photo, 3, 3), SettingsStorageCategory(video, 2, 2),
            SettingsStorageCategory(secret, 1, 1), SettingsStorageCategory(document, 1, 1))
        val changed = settingsStorageSelection(one, expanded, 0)
        assertEquals(setOf(photo, document), changed.selected, "New real types begin selected; surviving choices remain.")
        assertEquals(setOf(photo, video, document), settingsStorageSelection(changed, expanded, 1).selected,
            "Only the native success revision restores every current removable type.")

        val removed = details(SettingsStorageCategory(video, 2, 2), SettingsStorageCategory(secret, 1, 1))
        assertEquals(setOf(video), settingsStorageSelection(one, removed, 0).selected,
            "A disappearing last choice must not leave a real remaining type unselected.")
        val none = settingsStorageSelection(one, details(SettingsStorageCategory(secret, 1, 1)), 0)
        assertEquals(emptySet(), none.selected)
        assertEquals(emptySet(), settingsStorageSelection(null, details(), 0).selected)
    }

    @Test fun summaryUsesValidatedTotalsAndNeverCountsNonRemovableTypes() {
        val native = details(SettingsStorageCategory(photo, 3, 3), SettingsStorageCategory(video, 2, 2),
            SettingsStorageCategory(secret, 1, 1))
        val both = settingsStorageSummary(native, setOf(photo, video, secret))
        assertEquals(6L, both.totalBytes)
        assertEquals(5L, both.selectedBytes)
        assertEquals(5L, both.selectedCount)
        assertEquals(833, both.percentTenths)
        assertEquals(5f / 6f, both.fraction!!, .00001f)
        val one = settingsStorageSummary(native, setOf(photo))
        assertEquals(3L, one.selectedBytes)
        assertEquals(500, one.percentTenths)
        val zero = settingsStorageSummary(details(), emptySet())
        assertEquals(0L, zero.totalBytes)
        assertEquals(0L, zero.selectedBytes)
        assertNull(zero.fraction, "A zero total has no defined percentage.")
        val zeroBytesWithFiles = settingsStorageSummary(details(SettingsStorageCategory(photo, 0, 4)), setOf(photo))
        assertEquals(4L, zeroBytesWithFiles.selectedCount)
        assertNull(zeroBytesWithFiles.fraction)
        val zeroSelectedFraction = settingsStorageSummary(details(SettingsStorageCategory(photo, 0, 1),
            SettingsStorageCategory(secret, 2, 1)), setOf(photo))
        assertEquals(0f, zeroSelectedFraction.fraction)
        assertEquals(0, zeroSelectedFraction.percentTenths)
        assertNull(settingsStorageSummary(null, emptySet()).totalBytes, "No data is unknown, not zero.")
        assertNull(settingsStorageSummary(SettingsStorageDetails(-1, 0, emptyList()), emptySet()).totalBytes)
        assertNull(settingsStorageSummary(SettingsStorageDetails(7, 6, native.categories), emptySet()).totalBytes)
        assertNull(settingsStorageSummary(SettingsStorageDetails(2, 2, listOf(
            SettingsStorageCategory(photo, 1, 1), SettingsStorageCategory(photo, 1, 1))), emptySet()).totalBytes)
        assertNull(settingsStorageSummary(SettingsStorageDetails(0, 2, listOf(
            SettingsStorageCategory(photo, Long.MAX_VALUE, 1), SettingsStorageCategory(video, 1, 1))), emptySet()).totalBytes)

        val large = SettingsStorageDetails(Long.MAX_VALUE, 2, listOf(
            SettingsStorageCategory(photo, Long.MAX_VALUE - 1, 1), SettingsStorageCategory(secret, 1, 1)))
        val result = settingsStorageSummary(large, setOf(photo, secret))
        assertEquals(Long.MAX_VALUE, result.totalBytes)
        assertEquals(Long.MAX_VALUE - 1, result.selectedBytes)
        assertEquals(1L, result.selectedCount)
    }

    private fun details(vararg categories: SettingsStorageCategory) = SettingsStorageDetails(
        categories.sumOf { it.bytes }, categories.sumOf { it.count }, categories.toList())
}
