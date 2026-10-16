package com.emerge.app.ui

import com.emerge.app.analysis.CognitiveAnalyzer
import com.emerge.app.data.BrowseEvent
import org.junit.Assert.*
import org.junit.Test

class SwissReportExportSessionTest {
    private val now = 1_792_000_000_000L
    private val event = BrowseEvent(7, "app", now - 1000, 1, "保留的原文")
    private val request = SwissReportRequest(listOf(event), emptyList(), StatisticsPeriod.HOUR, now)
    private val report = CognitiveAnalyzer().analyze(request.events, request.period, now)

    @Test fun recreatedPresentationKeepsPendingExportForTheSameSnapshot() {
        val session = SwissReportExportSession()
        session.updateRequest(request)
        assertTrue(session.prepare(request, report, false))
        session.updateRequest(request.copy(events = listOf(event.copy())))
        assertEquals(report, session.takePending()?.report)
    }

    @Test fun changedRetainedContentInvalidatesThePickerSnapshot() {
        val session = SwissReportExportSession()
        session.updateRequest(request)
        session.prepare(request, report, false)
        session.updateRequest(request.copy(events = emptyList()))
        assertNull(session.takePending())
        assertNotNull(session.status)
    }

    @Test fun changingWindowInvalidatesAnActiveWriteAndRejectsItsCompletion() {
        val session = SwissReportExportSession()
        session.updateRequest(request)
        session.prepare(request, report, true)
        val pending = session.takePending()!!
        val generation = session.begin(pending)!!
        session.updateRequest(request.copy(period = StatisticsPeriod.MONTH))
        assertFalse(session.isCurrent(pending, generation))
        session.finish(generation, "已保存")
        assertFalse(session.exporting)
        assertNotEquals("已保存", session.status)
    }

    @Test fun closingDialogDiscardsPendingAndCompletedStatus() {
        val session = SwissReportExportSession()
        session.updateRequest(request)
        session.prepare(request, report, false)
        session.close()
        assertNull(session.takePending())
        assertNull(session.status)
        assertFalse(session.exporting)
    }

    @Test fun changedPurposeInvalidatesThePreparedReport() {
        val session = SwissReportExportSession()
        session.updateRequest(request)
        session.prepare(request, report, false)
        session.updateRequest(request.copy(purpose = "核对科技新闻"))
        assertNull(session.takePending())
    }

    @Test fun changingAnalysisStatusInvalidatesThePreparedReport() {
        val session = SwissReportExportSession()
        val queued = request.copy(analysisStatuses = mapOf(event.id to "queued"))
        session.updateRequest(queued)
        session.prepare(queued, report, false)
        session.updateRequest(queued.copy(analysisStatuses = mapOf(event.id to "failed")))
        assertNull(session.takePending())
        assertNotNull(session.status)
    }

    @Test fun reportIncludesActualQueueAndFailureCountsWithinItsWindow() {
        val records = listOf(event, event.copy(id = 8), event.copy(id = 9),
            event.copy(id = 10, timestamp = now - 2 * 60 * 60_000))
        val input = request.copy(events = records,
            analysisStatuses = mapOf(7L to "queued", 8L to "running", 9L to "failed", 10L to "failed"))
        val result = buildSwissReport(input)
        assertEquals(2, result.pendingAnalysisRecords)
        assertEquals(1, result.failedAnalysisRecords)
    }

    @Test fun documentResultAfterLostProcessStateHasAnExplicitFailure() {
        val session = SwissReportExportSession()
        session.updateRequest(request)
        assertNull(session.takePending(destinationSelected = true))
        assertEquals("导出已失效，请重新导出", session.status)
    }

    @Test fun unknownDurationDoesNotBecomeZeroSeconds() {
        assertEquals("时长未统计", swissReportDuration(report.copy(observedDurationMillis = 0, unknownDurationRecords = 3)))
        assertEquals("1 分 0 秒", swissReportDuration(report.copy(observedDurationMillis = 60_000, unknownDurationRecords = 3)))
        assertEquals("0 秒", swissReportDuration(report.copy(observedDurationMillis = 0, unknownDurationRecords = 0)))
    }
}
