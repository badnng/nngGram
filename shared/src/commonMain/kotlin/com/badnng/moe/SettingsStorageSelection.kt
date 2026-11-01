package com.badnng.moe

import com.badnng.moe.settings.SettingsStorageDetails
import com.badnng.moe.settings.SettingsStorageFileType
import kotlin.math.roundToInt

/** The known types are retained so a newly appearing real category starts selected. */
internal data class SettingsStorageSelection(
    val available: Set<SettingsStorageFileType>,
    val selected: Set<SettingsStorageFileType>,
    val completedClearRevision: Long,
) {
    fun toggle(type: SettingsStorageFileType, checked: Boolean): SettingsStorageSelection {
        if (type !in available) return this
        val next = if (checked) selected + type else if (selected.size > 1) selected - type else selected
        return copy(selected = next)
    }
}

/** A successful native clear resets the choice; an error or a reread alone does not. */
internal fun settingsStorageSelection(
    previous: SettingsStorageSelection?,
    details: SettingsStorageDetails,
    completedClearRevision: Long,
): SettingsStorageSelection {
    val available = details.categories.asSequence().filter { it.type.removable && it.count > 0 }
        .map { it.type }.toSet()
    val selected = if (previous == null || previous.completedClearRevision != completedClearRevision) {
        available
    } else {
        val retained = (previous.selected intersect available) + (available - previous.available)
        if (retained.isEmpty() && available.isNotEmpty()) setOf(available.first()) else retained
    }
    return SettingsStorageSelection(available, selected, completedClearRevision)
}

/** Null fields mean that no validated total is available; zero is an actual known value. */
internal data class SettingsStorageSummary(
    val totalBytes: Long? = null,
    val totalCount: Long? = null,
    val selectedBytes: Long? = null,
    val selectedCount: Long? = null,
    val fraction: Float? = null,
    val percentTenths: Int? = null,
)

internal fun settingsStorageSummary(
    details: SettingsStorageDetails?,
    selected: Set<SettingsStorageFileType>,
): SettingsStorageSummary {
    if (details == null || details.bytes < 0 || details.count < 0) return SettingsStorageSummary()
    var bytes = 0L
    var count = 0L
    var selectedBytes = 0L
    var selectedCount = 0L
    val seen = mutableSetOf<SettingsStorageFileType>()
    for (category in details.categories) {
        if (!seen.add(category.type) || category.bytes < 0 || category.count < 0 ||
            category.bytes > Long.MAX_VALUE - bytes || category.count.toLong() > Long.MAX_VALUE - count
        ) return SettingsStorageSummary()
        bytes += category.bytes
        count += category.count.toLong()
        if (category.type.removable && category.count > 0 && category.type in selected) {
            selectedBytes += category.bytes // This is a subset of the already overflow-checked total.
            selectedCount += category.count.toLong()
        }
    }
    if (bytes != details.bytes || count != details.count.toLong()) return SettingsStorageSummary()
    val fraction = if (bytes == 0L) null else selectedBytes.toDouble() / bytes.toDouble()
    return SettingsStorageSummary(bytes, count, selectedBytes, selectedCount,
        fraction?.toFloat(), fraction?.let { (it * 1000).roundToInt() })
}
