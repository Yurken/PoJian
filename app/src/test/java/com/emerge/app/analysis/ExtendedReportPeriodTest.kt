package com.emerge.app.analysis

import com.emerge.app.data.BrowseEvent
import com.emerge.app.ui.StatisticsPeriod
import com.emerge.app.ui.calculateStatistics
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ExtendedReportPeriodTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-10-16T04:30:00Z").toEpochMilli()
    private fun event(id: Long, at: Long) = BrowseEvent(id = id, packageName = "reader",
        timestamp = at, eventType = 32, visibleText = "作品 $id", workKey = "work-$id")

    @Test fun hourlyReportIncludesOnlyTheLastSixtyMinutes() {
        val period = StatisticsPeriod.valueOf("HOUR")
        val window = ObservationWindow.forPeriod(period, now, zone)
        assertEquals(now - 3_600_000L, window.start)
        val rows = listOf(event(1, window.start - 1), event(2, window.start),
            event(3, now), event(4, now + 1))
        val statistics = calculateStatistics(rows, period, now, zone)
        assertEquals(2, statistics.total)
        assertEquals(listOf(1, 1), statistics.trend.map { it.count })
        assertEquals(setOf(2L, 3L), ViewpointTimeline.build(rows, period, now, zone)
            .flatMap { it.items }.map { it.record.id }.toSet())
    }

    @Test fun thirtyDayReportUsesLocalCalendarDaysAndConsistentBuckets() {
        val period = StatisticsPeriod.valueOf("MONTH")
        val firstMidnight = Instant.parse("2026-09-16T16:00:00Z").toEpochMilli()
        assertEquals(firstMidnight, ObservationWindow.forPeriod(period, now, zone).start)
        val rows = listOf(event(1, firstMidnight - 1), event(2, firstMidnight), event(3, now))
        val statistics = calculateStatistics(rows, period, now, zone)
        assertEquals(30, statistics.trend.size)
        assertEquals(2, statistics.total)
        assertEquals(1, statistics.trend.first().count)
        assertEquals(1, statistics.trend.last().count)
        val timeline = ViewpointTimeline.build(rows, period, now, zone)
        assertEquals(30, timeline.size)
        assertEquals(2L, timeline.first().items.single().record.id)
        assertEquals(3L, timeline.last().items.single().record.id)
    }

    @Test fun hourlyReportClipsCrossingSessionWithoutInventingDuration() {
        val period = StatisticsPeriod.valueOf("HOUR")
        val start = now - 3_600_000L
        val row = event(1, start - 60_000).copy(endTimestamp = start + 60_000,
            durationMillis = 120_000, sampleCount = 61)
        val statistics = calculateStatistics(listOf(row), period, now, zone)
        assertEquals(60_000L, statistics.observedDurationMillis)
        assertEquals(1, statistics.total)
    }
}
