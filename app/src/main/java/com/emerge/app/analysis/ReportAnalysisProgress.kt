package com.emerge.app.analysis

import com.emerge.app.data.BrowseEvent
import java.time.ZoneId

/** Task progress changes do not invalidate the immutable content analysis. */
internal class ReportAnalysisProgress(events: List<BrowseEvent>, report: CognitiveReport) {
    private val recordIds: List<Long> = ObservationWindow(report.start, report.end, ZoneId.of(report.zone)).let { window ->
        events.filter(window::contains).map(BrowseEvent::id)
    }

    fun applyTo(report: CognitiveReport, statuses: Map<Long, String>): CognitiveReport {
        var pending = 0
        var failed = 0
        recordIds.forEach { id ->
            when (statuses[id]) {
                "queued", "running" -> pending++
                "failed" -> failed++
            }
        }
        return if (report.pendingAnalysisRecords == pending && report.failedAnalysisRecords == failed) report
            else report.copy(pendingAnalysisRecords = pending, failedAnalysisRecords = failed)
    }
}
