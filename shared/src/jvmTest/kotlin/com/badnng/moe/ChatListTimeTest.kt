package com.badnng.moe

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatListTimeTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val today = LocalDate.of(2026, 10, 22)

    @Test fun recentChatsUseTheClockYesterdayAndWeekdayLabels() {
        assertEquals("14:32", label(today))
        assertEquals("昨天", label(today.minusDays(1)))
        assertEquals("周二", label(today.minusDays(2)))
        assertEquals("周五", label(today.minusDays(6)))
        assertEquals("10/15", label(today.minusDays(7)))
    }

    @Test fun oldDatesKeepTheirYearAndInvalidDatesRemainEmpty() {
        assertEquals("2025/12/31", label(LocalDate.of(2025, 12, 31)))
        assertEquals("10/23", label(today.plusDays(1)))
        assertEquals("", formatChatListTime(0, today, zone))
        assertEquals("", formatChatListTime(-1, today, zone))
    }

    @Test fun dateBoundariesUseTheProvidedLocalZone() {
        val epoch = today.atTime(0, 15).atZone(zone).toEpochSecond().toInt()
        assertEquals("00:15", formatChatListTime(epoch, today, zone))
        assertEquals("昨天", formatChatListTime(epoch, today, ZoneId.of("UTC")))
    }

    private fun label(date: LocalDate) = formatChatListTime(date.atTime(14, 32).atZone(zone).toEpochSecond().toInt(), today, zone)
}
