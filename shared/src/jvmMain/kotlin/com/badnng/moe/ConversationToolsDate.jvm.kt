package com.badnng.moe

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

internal actual fun conversationSearchDayRange(text: String): Pair<Int, Int>? {
    if (!text.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) return null
    return try {
        val day = LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE)
        val zone = ZoneId.systemDefault()
        val start = day.atStartOfDay(zone).toEpochSecond()
        val end = day.plusDays(1).atStartOfDay(zone).toEpochSecond() - 1
        if (start !in 1..Int.MAX_VALUE.toLong() || end !in start..Int.MAX_VALUE.toLong()) null
        else start.toInt() to end.toInt()
    } catch (_: DateTimeParseException) {
        null
    }
}

internal actual fun conversationSearchDayLabel(epochSeconds: Int): String =
    if (epochSeconds <= 0) "" else Instant.ofEpochSecond(epochSeconds.toLong()).atZone(ZoneId.systemDefault()).toLocalDate().toString()
