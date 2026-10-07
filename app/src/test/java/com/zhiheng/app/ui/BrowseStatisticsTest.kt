package com.zhiheng.app.ui

import com.zhiheng.app.data.BrowseEvent
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class BrowseStatisticsTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-10-07T12:30:00Z").toEpochMilli()
    private fun event(time: Long, name: String = "browser", text: String = "abc") =
        BrowseEvent(packageName = name, timestamp = time, eventType = 32, visibleText = text)

    @Test fun rollingDayIncludesBothBoundariesButExcludesOldAndFutureRecords() {
        val start = now - 24 * 3_600_000L
        val stats = calculateStatistics(listOf(event(start - 1), event(start), event(now), event(now + 1)), StatisticsPeriod.DAY, now, zone)
        assertEquals(2, stats.total)
        assertEquals(1, stats.trend.first().count)
        assertEquals(1, stats.trend.last().count)
        assertEquals(6L, stats.textCharacters)
    }

    @Test fun weekUsesLocalCalendarDaysAndIncludesToday() {
        val firstMidnight = Instant.parse("2026-09-30T16:00:00Z").toEpochMilli() // Oct 1, local midnight
        val todayMidnight = Instant.parse("2026-10-06T16:00:00Z").toEpochMilli()
        val stats = calculateStatistics(listOf(event(firstMidnight - 1), event(firstMidnight), event(todayMidnight), event(now)), StatisticsPeriod.WEEK, now, zone)
        assertEquals(3, stats.total)
        assertEquals("10/1", stats.trend.first().label)
        assertEquals(1, stats.trend.first().count)
        assertEquals("今天", stats.trend.last().label)
        assertEquals(2, stats.trend.last().count)
        assertEquals(3, stats.trend.sumOf { it.count })
    }

    @Test fun statisticsCountMoreThanHistoryPreviewAndPreserveSourceTotals() {
        val events = List(250) { event(now - 1000, if (it < 210) "browser" else "reader") }
        val stats = calculateStatistics(events, StatisticsPeriod.DAY, now, zone)
        assertEquals(250, stats.total)
        assertEquals(2, stats.appCount)
        assertEquals(AppShare("browser", 210), stats.apps.first())
        assertEquals(250, stats.apps.sumOf { it.count })
        assertEquals(750L, stats.textCharacters)
    }
}
