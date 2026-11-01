package com.badnng.moe

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class MessageReaderTimeTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val today = LocalDate.of(2026, 10, 23)

    @Test fun todayAndYesterdayKeepTheirRealLocalClock() {
        assertEquals("今天 14:32", label(today))
        assertEquals("昨天 14:32", label(today.minusDays(1)))
    }

    @Test fun olderDatesKeepTheDateAndClockWithoutUtcSuffixes() {
        assertEquals("2026/10/20 14:32", label(today.minusDays(3)))
        assertEquals("2025/12/31 14:32", label(LocalDate.of(2025, 12, 31)))
    }

    @Test fun dayAndClockAreBothComputedInTheSuppliedLocalZone() {
        val epoch = Instant.parse("2026-10-22T17:25:00Z").epochSecond.toInt()
        assertEquals("今天 01:25", formatReaderReadTime(epoch, today, zone))
        assertEquals("昨天 17:25", formatReaderReadTime(epoch, today, ZoneId.of("UTC")))
    }

    @Test fun anUnavailableNativeTimestampNeverInventsAReadTime() {
        assertEquals("未提供时间", formatReaderReadTime(0, today, zone))
        assertEquals("未提供时间", formatReaderReadTime(-1, today, zone))
    }

    private fun label(date: LocalDate) = formatReaderReadTime(date.atTime(14, 32).atZone(zone).toEpochSecond().toInt(), today, zone)
}
