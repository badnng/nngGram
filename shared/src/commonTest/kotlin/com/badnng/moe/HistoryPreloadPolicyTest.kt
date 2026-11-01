package com.badnng.moe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HistoryPreloadPolicyTest {
    @Test
    fun shortInitialWindowsLoadAnotherPageWithoutRequiringAScrollGesture() {
        val rows = (0..2).map { HistoryPreloadItem(it, it * 80, 80) }
        assertEquals(HistoryPreloadDirection.OLDER, historyPreloadDirection(3, 0, 600, rows, true, false))
        assertEquals(HistoryPreloadDirection.NEWER, historyPreloadDirection(3, 0, 600, rows, true, true))
    }

    @Test
    fun eitherLoadedEdgePreloadsWithinThreeViewportHeights() {
        val nearNewer = (17..22).map { HistoryPreloadItem(it, (it - 17) * 100, 100) }
        val nearOlder = (77..82).map { HistoryPreloadItem(it, (it - 77) * 100, 100) }
        assertEquals(HistoryPreloadDirection.NEWER, historyPreloadDirection(100, 0, 600, nearNewer, true, true))
        assertEquals(HistoryPreloadDirection.OLDER, historyPreloadDirection(100, 0, 600, nearOlder, true, true))
        assertNull(historyPreloadDirection(100, 0, 600, (40..45).map {
            HistoryPreloadItem(it, (it - 40) * 100, 100)
        }, true, true))
    }

    @Test
    fun measuredPixelHeightsRatherThanAFixedMessageCountControlTheBudget() {
        assertEquals(HistoryPreloadDirection.NEWER, historyPreloadDirection(100, 0, 600,
            listOf(HistoryPreloadItem(2, 0, 40)), false, true))
        assertNull(historyPreloadDirection(100, 0, 600,
            listOf(HistoryPreloadItem(2, 0, 1_000)), false, true))
    }

    @Test
    fun finishedEdgesAndUnmeasuredOrCollapsedViewportsDoNotLoad() {
        val rows = listOf(HistoryPreloadItem(0, 0, 80))
        assertNull(historyPreloadDirection(1, 0, 600, rows, false, false))
        assertNull(historyPreloadDirection(1, 0, 0, rows, true, true))
        assertNull(historyPreloadDirection(1, 0, 600, emptyList(), true, true))
        assertNull(historyPreloadDirection(1, 0, 600, listOf(HistoryPreloadItem(1, 0, 30)), true, true))
    }
}
