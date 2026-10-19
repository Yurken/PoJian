package com.emerge.app.analysis

import com.emerge.app.data.BrowseEvent
import com.emerge.app.ui.StatisticsPeriod
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class PeriodExposureTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun at(value: String) = Instant.parse(value).toEpochMilli()
    private val now = at("2026-10-19T04:00:00Z") // Noon in Shanghai.
    private val quote = "当前招聘岗位数量减少"
    private fun event(id: Long, time: Long, work: String = "post-$id") =
        BrowseEvent(id, "reader", time, 32, quote, sampleCount = 1, workKey = work)

    private fun report(events: List<BrowseEvent>, period: StatisticsPeriod,
        stance: (BrowseEvent) -> String = { "减少" }) = CognitiveAnalyzer().analyze(
        events, period, now, zone, semantics = events.map {
            EventSemantics(it.id, it.visibleText, now, listOf(
                ArgumentUnit(quote, "就业", "招聘岗位", "数量", stance(it), "", "", false)))
        })

    @Test fun everyPeriodUsesItsOwnBoundariesAndKeepsTheLongTermWindow() {
        val events = listOf(
            event(1, at("2026-10-19T03:30:00Z")), // Last hour.
            event(2, at("2026-10-19T01:00:00Z")), // Today.
            event(3, at("2026-10-18T06:00:00Z")), // Last 24 hours.
            event(4, at("2026-10-12T16:00:00Z")), // First instant of seven calendar days.
            event(5, at("2026-10-12T15:59:59Z")), // Only in rolling long-term seven days.
            event(6, at("2026-09-19T16:00:00Z")), // First instant of thirty calendar days.
            event(7, at("2026-09-19T15:59:59Z")), // Outside all selected periods.
            event(8, now + 1) // Future data must not leak in.
        )
        val expected = mapOf(
            StatisticsPeriod.HOUR to ("2026-10-19T03:00:00Z" to setOf(1L)),
            StatisticsPeriod.DAY to ("2026-10-18T16:00:00Z" to setOf(1L, 2L)),
            StatisticsPeriod.HOURS24 to ("2026-10-18T04:00:00Z" to setOf(1L, 2L, 3L)),
            StatisticsPeriod.WEEK to ("2026-10-12T16:00:00Z" to setOf(1L, 2L, 3L, 4L)),
            StatisticsPeriod.MONTH to ("2026-09-19T16:00:00Z" to setOf(1L, 2L, 3L, 4L, 5L, 6L))
        )
        expected.forEach { (period, result) ->
            val report = report(events, period)
            val exposure = requireNotNull(report.periodExposure)
            assertEquals(period.name, at(result.first), exposure.start)
            assertEquals(period.name, now, exposure.end)
            assertEquals(period.name, result.second, exposure.claims.map { it.eventId }.toSet())
            assertEquals(period.name, result.second.size, exposure.topics.contacts)
            assertEquals(setOf(1L, 2L, 3L, 4L, 5L), report.exposure!!.claims.map { it.eventId }.toSet())
        }
    }

    @Test fun changingPeriodChangesConcentrationAndDoesNotBorrowHistoricalSampleSize() {
        val recent = (1L..30).map { event(it, now - it * 1000) }
        val earlier = (31L..60).map { event(it, now - 2 * 3_600_000 - it * 1000) }
        fun analyze(period: StatisticsPeriod) = report(recent + earlier, period) {
            if (it.id <= 30) "减少" else "增加"
        }
        val hour = analyze(StatisticsPeriod.HOUR).periodExposure!!
        val day = analyze(StatisticsPeriod.DAY).periodExposure!!
        assertEquals(1.0, hour.primary!!.share, .00001)
        assertTrue(hour.primary!!.concentrated)
        assertEquals(.5, day.primary!!.share, .00001)
        assertFalse(day.primary!!.concentrated)
        val insufficient = report(recent.take(1) + earlier, StatisticsPeriod.HOUR)
        assertNull(insufficient.periodExposure!!.primary)
        assertNotNull(insufficient.exposure!!.primary)
    }

    @Test fun anEarlierVisitToTheSameWorkDoesNotHideAnInWindowVisit() {
        val old = event(1, now - 2 * 3_600_000, "same-work")
        val current = event(2, now - 1000, "same-work")
        val exposure = report(listOf(old, current), StatisticsPeriod.HOUR).periodExposure!!
        assertEquals(1, exposure.topics.contacts)
        assertEquals(listOf(2L), exposure.claims.map { it.eventId })
    }

    @Test fun measuredVisitsOverlappingTheBoundaryRemainInTheSelectedWindow() {
        val crossing = event(1, now - 2 * 3_600_000).copy(
            endTimestamp = now - 1000, durationMillis = 2 * 3_600_000 - 1000)
        val endedAtBoundary = event(2, now - 2 * 3_600_000).copy(
            endTimestamp = now - 3_600_000, durationMillis = 3_600_000)
        val exposure = report(listOf(crossing, endedAtBoundary), StatisticsPeriod.HOUR).periodExposure!!
        assertEquals(1, exposure.topics.contacts)
        assertEquals(listOf(1L), exposure.claims.map { it.eventId })
    }

    @Test fun anEmptySelectedWindowDoesNotShowHistoricalConcentration() {
        val events = (1L..30).map { event(it, now - 2 * 86_400_000 - it * 1000) }
        val report = report(events, StatisticsPeriod.DAY)
        assertEquals(0, report.periodExposure!!.topics.contacts)
        assertTrue(report.periodExposure!!.claims.isEmpty())
        assertNull(report.periodExposure!!.primary)
        assertNotNull(report.exposure!!.primary)
    }

    @Test fun aVisitCrossingMidnightAndTodaysRevisitCountAsOneDailyContact() {
        val crossing = event(1, at("2026-10-18T15:59:00Z"), "same-work").copy(
            endTimestamp = at("2026-10-18T16:01:00Z"), durationMillis = 120_000)
        val revisit = event(2, now - 1000, "same-work")
        val report = report(listOf(crossing, revisit), StatisticsPeriod.DAY)
        assertEquals(1, report.periodExposure!!.topics.contacts)
        assertEquals(setOf(1L, 2L), report.periodExposure!!.claims.map { it.eventId }.toSet())
        assertEquals(2, report.exposure!!.topics.contacts)
    }
}
