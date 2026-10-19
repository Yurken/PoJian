package com.emerge.app.analysis

import com.emerge.app.data.BrowseEvent
import com.emerge.app.ui.StatisticsPeriod
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ReportAnalysisProgressTest {
    private val now = Instant.parse("2026-10-19T12:00:00Z").toEpochMilli()
    private val zone = ZoneId.of("Asia/Shanghai")
    private val events = listOf(
        BrowseEvent(1, "reader", now - 1_000, 1, "当前招聘岗位减少"),
        BrowseEvent(2, "reader", now - 2_000, 1, "当前招聘岗位增加"),
        BrowseEvent(3, "reader", now - 86_400_000, 1, "昨天招聘岗位减少"))

    @Test fun statusRefreshUpdatesOnlyInWindowCountersAndKeepsAnalyzedContent() {
        val report = CognitiveAnalyzer().analyze(events, StatisticsPeriod.DAY, now, zone)
        val progress = ReportAnalysisProgress(events, report)
        val refreshed = progress.applyTo(report, mapOf(1L to "running", 2L to "failed", 3L to "queued", 99L to "failed"))
        assertEquals(1, refreshed.pendingAnalysisRecords)
        assertEquals(1, refreshed.failedAnalysisRecords)
        assertSame(report.claims, refreshed.claims)
        assertSame(report.periodExposure, refreshed.periodExposure)
        assertSame(report.topicDigests, refreshed.topicDigests)
        assertEquals(report.generatedAt, refreshed.generatedAt)
        val finished = progress.applyTo(refreshed, mapOf(1L to "done", 2L to "done"))
        assertEquals(0, finished.pendingAnalysisRecords)
        assertEquals(0, finished.failedAnalysisRecords)
    }

    @Test fun unrelatedOrEquivalentTaskChangesDoNotEmitAnotherReport() {
        val report = CognitiveAnalyzer().analyze(events, StatisticsPeriod.DAY, now, zone,
            analysisStatuses = mapOf(1L to "queued"))
        assertSame(report, ReportAnalysisProgress(events, report).applyTo(report, mapOf(1L to "running", 3L to "failed")))
    }

    @Test fun aMeasuredVisitCrossingTheWindowBoundaryStillCounts() {
        val event = events.first().copy(timestamp = now - 2 * 3_600_000,
            endTimestamp = now - 1_000, durationMillis = 2 * 3_600_000 - 1_000, sampleCount = 1)
        val report = CognitiveAnalyzer().analyze(listOf(event), StatisticsPeriod.HOUR, now, zone)
        assertEquals(1, ReportAnalysisProgress(listOf(event), report).applyTo(report, mapOf(1L to "queued")).pendingAnalysisRecords)
    }
}
