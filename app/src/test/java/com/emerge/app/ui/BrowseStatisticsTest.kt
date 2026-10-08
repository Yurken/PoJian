package com.emerge.app.ui

import com.emerge.app.data.BrowseEvent
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class BrowseStatisticsTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-10-07T12:30:00Z").toEpochMilli()
    private fun event(time: Long, name: String = "browser", text: String = "abc") =
        BrowseEvent(packageName = name, timestamp = time, eventType = 32, visibleText = text)

    @Test fun todayIncludesLocalMidnightAndExcludesYesterdayAndFutureRecords() {
        val start = Instant.parse("2026-10-06T16:00:00Z").toEpochMilli()
        val stats = calculateStatistics(listOf(event(start - 1), event(start), event(now), event(now + 1)), StatisticsPeriod.DAY, now, zone)
        assertEquals(2, stats.total)
        assertEquals(1, stats.trend.first().count)
        assertEquals(1, stats.trend[20].count)
        assertEquals("00:00", stats.trend.first().label)
        assertEquals("23:00", stats.trend.last().label)
        assertEquals(0, stats.trend.last().count)
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
        assertEquals(AppShare("browser", 210, unknownDurationRecords = 210), stats.apps.first())
        assertEquals(250, stats.apps.sumOf { it.count })
        assertEquals(250, stats.unknownDurationRecords)
        assertEquals(750L, stats.textCharacters)
    }

    @Test fun crossingMidnightAndHourConservesMeasuredDurationAndOverallRecordCount() {
        val midnight = Instant.parse("2026-10-06T16:00:00Z").toEpochMilli()
        val events = listOf(event(midnight - 60_000).copy(endTimestamp = midnight + 60_000,
            durationMillis = 120_000, sampleCount = 61),
            event(midnight + 59 * 60_000).copy(endTimestamp = midnight + 61 * 60_000,
                durationMillis = 120_000, sampleCount = 61))
        val stats = calculateStatistics(events, StatisticsPeriod.DAY, now, zone)
        assertEquals(2, stats.total)
        assertEquals(180_000L, stats.observedDurationMillis)
        assertEquals(120_000L, stats.trend[0].durationMillis)
        assertEquals(60_000L, stats.trend[1].durationMillis)
        assertEquals(180_000L, stats.trend.sumOf { it.durationMillis })
        assertEquals(180_000L, stats.apps.sumOf { it.durationMillis })
        assertEquals(3, stats.trend.sumOf { it.count })
    }

    @Test fun weeklyBucketsSplitASessionAtLocalMidnight() {
        val midnight = Instant.parse("2026-10-06T16:00:00Z").toEpochMilli()
        val session = event(midnight - 60_000).copy(endTimestamp = midnight + 60_000,
            durationMillis = 120_000, sampleCount = 61)
        val stats = calculateStatistics(listOf(session), StatisticsPeriod.WEEK, now, zone)
        assertEquals(1, stats.total)
        assertEquals(60_000L, stats.trend[5].durationMillis)
        assertEquals(60_000L, stats.trend[6].durationMillis)
        assertEquals(120_000L, stats.observedDurationMillis)
    }

    @Test fun legacyDurationRemainsUnknownAndAFutureEndpointIsClippedToNow() {
        val stats = calculateStatistics(listOf(event(now - 1000).copy(durationMillis = 500),
            event(now - 1000).copy(endTimestamp = now + 1000, durationMillis = 2000, sampleCount = 2)),
            StatisticsPeriod.DAY, now, zone)
        assertEquals(1, stats.unknownDurationRecords)
        assertEquals(1000L, stats.observedDurationMillis)
        assertEquals(1000L, stats.trend.sumOf { it.durationMillis })
    }
}
