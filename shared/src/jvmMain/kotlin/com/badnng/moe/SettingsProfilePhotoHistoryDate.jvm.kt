package com.badnng.moe

import java.time.Instant
import java.time.ZoneId

internal actual fun profileHistoryDate(seconds: Int): String = formatProfileHistoryDate(seconds, ZoneId.systemDefault())

internal fun formatProfileHistoryDate(seconds: Int, zone: ZoneId): String {
    if (seconds <= 0) return "日期未知"
    val date = Instant.ofEpochSecond(seconds.toLong()).atZone(zone).toLocalDate()
    return "${date.year} 年 ${date.monthValue} 月 ${date.dayOfMonth} 日"
}
