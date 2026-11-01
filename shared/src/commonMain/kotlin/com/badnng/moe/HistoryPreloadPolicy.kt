package com.badnng.moe

internal enum class HistoryPreloadDirection { OLDER, NEWER }

/** Main-axis offsets from LazyListLayoutInfo; indices follow the reverse chronological list. */
internal data class HistoryPreloadItem(val index: Int, val offset: Int, val size: Int)

/**
 * Telegram Desktop preloads within three viewport heights of either loaded edge. LazyColumn
 * only measures the visible rows, so use their mean height for the remaining loaded rows.
 * This is a pixel budget: three tall rows and three one-line rows do not produce the same edge.
 */
internal fun historyPreloadDirection(
    messageCount: Int,
    viewportStart: Int,
    viewportEnd: Int,
    items: List<HistoryPreloadItem>,
    hasOlder: Boolean,
    hasNewer: Boolean,
): HistoryPreloadDirection? {
    val viewportHeight = viewportEnd - viewportStart
    if (messageCount <= 0 || viewportHeight <= 0 || items.isEmpty()) return null
    val visible = items.filter { it.index in 0 until messageCount && it.size > 0 }
    if (visible.isEmpty()) return null
    val first = visible.minBy { it.index }
    val last = visible.maxBy { it.index }
    val averageHeight = visible.sumOf { it.size.toDouble() } / visible.size
    val newerDistance = first.index * averageHeight + (viewportStart - first.offset).coerceAtLeast(0)
    val olderDistance = (messageCount - 1 - last.index) * averageHeight +
        (last.offset + last.size - viewportEnd).coerceAtLeast(0)
    val budget = 3.0 * viewportHeight
    val older = hasOlder && olderDistance <= budget
    val newer = hasNewer && newerDistance <= budget
    return when {
        older && newer -> if (newerDistance <= olderDistance) HistoryPreloadDirection.NEWER else HistoryPreloadDirection.OLDER
        newer -> HistoryPreloadDirection.NEWER
        older -> HistoryPreloadDirection.OLDER
        else -> null
    }
}
