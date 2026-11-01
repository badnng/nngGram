package com.badnng.moe

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ConversationToolsDateTest {
    @Test fun searchDateUsesTheWholeLocalDayWithInclusiveEnd() {
        val date = LocalDate.of(2026, 10, 23)
        val range = assertNotNull(conversationSearchDayRange("2026-10-23"))
        assertEquals(date.atStartOfDay(ZoneId.systemDefault()).toEpochSecond(), range.first.toLong())
        assertEquals(date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toEpochSecond() - 1, range.second.toLong())
        assertEquals("2026-10-23", conversationSearchDayLabel(range.first))
        assertEquals("2026-10-23", conversationSearchDayLabel(range.second))
    }

    @Test fun invalidDatesAndValuesOutsideNativeTimestampRangeAreRejected() {
        listOf("", "2026-2-03", "2026-02-30", "2026-13-01", "text", "1900-01-01", "2100-01-01").forEach {
            assertNull(conversationSearchDayRange(it), it)
        }
        assertNotNull(conversationSearchDayRange("2024-02-29"))
        assertEquals("", conversationSearchDayLabel(0))
    }
}
