package com.badnng.moe

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val chatClockFormat = DateTimeFormatter.ofPattern("HH:mm")

internal actual fun readerReadTime(epochSeconds: Int): String {
    val zone = ZoneId.systemDefault()
    return formatReaderReadTime(epochSeconds, LocalDate.now(zone), zone)
}

internal fun formatReaderReadTime(epochSeconds: Int, today: LocalDate, zone: ZoneId): String {
    if (epochSeconds <= 0) return "未提供时间"
    val time = Instant.ofEpochSecond(epochSeconds.toLong()).atZone(zone)
    val date = time.toLocalDate()
    val day = when (date) {
        today -> "今天"
        today.minusDays(1) -> "昨天"
        else -> "${date.year}/${date.monthValue}/${date.dayOfMonth}"
    }
    return "$day ${time.format(chatClockFormat)}"
}

internal actual fun messageClock(epochSeconds: Int): String = if (epochSeconds <= 0) "" else
    Instant.ofEpochSecond(epochSeconds.toLong()).atZone(ZoneId.systemDefault()).format(chatClockFormat)

internal actual fun chatListTime(epochSeconds: Int): String {
    val zone = ZoneId.systemDefault()
    return formatChatListTime(epochSeconds, LocalDate.now(zone), zone)
}

internal fun formatChatListTime(epochSeconds: Int, today: LocalDate, zone: ZoneId): String {
    if (epochSeconds <= 0) return ""
    val time = Instant.ofEpochSecond(epochSeconds.toLong()).atZone(zone)
    val date = time.toLocalDate()
    return when {
        date == today -> time.format(chatClockFormat)
        date == today.minusDays(1) -> "昨天"
        date < today && date >= today.minusDays(6) -> "周" + "一二三四五六日"[date.dayOfWeek.value - 1]
        date.year == today.year -> "${date.monthValue}/${date.dayOfMonth}"
        else -> "${date.year}/${date.monthValue}/${date.dayOfMonth}"
    }
}

internal actual fun messageDayLabel(epochSeconds: Int): String {
    if (epochSeconds <= 0) return ""
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochSecond(epochSeconds.toLong()).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    return when (date) {
        today -> "今天"
        today.minusDays(1) -> "昨天"
        else -> "${date.year}年${date.monthValue}月${date.dayOfMonth}日"
    }
}
